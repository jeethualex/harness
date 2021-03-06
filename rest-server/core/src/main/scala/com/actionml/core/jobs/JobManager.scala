/*
 * Copyright ActionML, LLC under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * ActionML licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.actionml.core.jobs

import java.util.{Date, UUID}

import com.actionml.core.jobs.JobStatuses.JobStatus
import com.actionml.core.model.Response
import com.actionml.core.spark.LivyJobServerSupport
import com.actionml.core.store.Ordering.asc
import com.actionml.core.store.Ordering.desc
import com.actionml.core.store.backends.MongoStorage
import com.actionml.core.store.{DAO, OrderBy}
import com.typesafe.config.ConfigFactory
import com.typesafe.scalalogging.LazyLogging
import org.bson.codecs.configuration.{CodecProvider, CodecRegistry}
import org.bson.codecs.{Codec, DecoderContext, EncoderContext}
import org.bson.{BsonInvalidOperationException, BsonReader, BsonWriter}

import scala.collection.concurrent.TrieMap
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.{Failure, Success, Try}
import scala.util.control.NonFatal
import net.ceedubs.ficus.Ficus._
import net.ceedubs.ficus.readers.ArbitraryTypeReader._
import net.ceedubs.ficus.readers.namemappers.implicits.hyphenCase

import scala.concurrent.duration.FiniteDuration


trait JobManagerInterface {
  def addJob(engineId: String,
             c: Cancellable = Cancellable.noop,
             comment: String = "",
             initStatus: JobStatus = JobStatuses.queued): JobDescription
  def updateJob(engineId: String, jobId: String, status: JobStatuses.JobStatus, c: Cancellable): Unit
  def getActiveJobDescriptions(engineId: String): Iterable[JobDescription]
  @deprecated("Use this method with care. All jobs should be stored with the correct status", since = "0.5.1")
  def removeJob(jobId: String): Unit
  def removeAllJobs(engineId: String): Future[Unit]
  def finishJob(jobId: String): Unit
  def markJobFailed(jobId: String): Unit
  def cancelJob(engineId: String, jobId: String): Future[Unit]
  def abortExecutingJobs: Future[Unit]
}

/** Creates Futures and unique jobDescriptions, both are returned immediately but any arbitrary block of code can be
  * executed in the Future.map. At the end of the block, the consumer must call removeJob. The jobDescriptions are
  * mapped to engineId for status reporting purposes and have UUIDs. If a JobDescription is in the list it is "queued",
  *  or "executing" otherwise it is "notQueued". No information is kept about the completion status of a job so the logs
  * must be scanned for any error reports. Todo: do we want to remember some number of old finished jobs?
  */
object JobManager extends JobManagerInterface with LazyLogging {
  import com.actionml.core.store.DaoQuery.syntax._
  private val jobsStore: DAO[JobRecord] = MongoStorage.getStorage("harness_meta_store", codecs = JobRecord.mongoCodecs).createDao[JobRecord]("jobs")
  // This [duplicate] map stores runtime stuff (futures and cancellables). EngineId is the key
  private val jobDescriptions = TrieMap.empty[String, List[(Cancellable, JobDescription)]]

  override def addJob(engineId: String, c: Cancellable, comment: String, status: JobStatus): JobDescription = {
    val description = JobDescription.create(status = status, comment = comment)
    jobsStore.insert(JobRecord(engineId, description))
    jobDescriptions.put(engineId,
      (c -> description) :: jobDescriptions.getOrElse(engineId, Nil)
    )
    description
  }

  override def updateJob(engineId: String, jobId: String, status: JobStatuses.JobStatus, c: Cancellable): Unit = {
    jobsStore.updateAsync("jobId" === jobId)("status" -> status)
    for {
      descriptions <- jobDescriptions.get(engineId)
      (_, description) <- descriptions.find { case (_, d) => d.jobId == jobId }
    } yield jobDescriptions.put(engineId,
      (c -> description.copy(status = status)) :: descriptions.filterNot(_._2 == description)
    )
  }

  /** Gets any active Jobs for the specified Engine */
  override def getActiveJobDescriptions(engineId: String): Iterable[JobDescription] = {
    jobsStore
      .findMany(orderBy = Option(OrderBy(desc, "createdAt")))("engineId" === engineId)
      .toVector
      .foldLeft(List.empty[JobDescription]) {
        case (acc, j) if j.status == JobStatuses.queued || j.status == JobStatuses.executing =>
          j.toJobDescription :: acc
        case (acc, j) if acc.count(d => d.status != JobStatuses.queued && d.status != JobStatuses.executing) < 10 =>
          j.toJobDescription :: acc
        case (acc, _) => acc
      }.sortBy(_.status.id)
  }

  override def removeJob(harnessJobId: String): Unit = {
    jobsStore.removeOneAsync(("jobId" === harnessJobId))
      .onSuccess { case _ =>
        removeLocal(harnessJobId)
        logger.info(s"Job $harnessJobId removed")
      }
  }

  override def removeAllJobs(engineId: String): Future[Unit] = {
    jobDescriptions.remove(engineId).filter(_.nonEmpty).fold(Future.successful ()) { list =>
      val cancelJobs = Future.reduce(list.map { case (c, _) => c.cancel() })((_, _) => ())
        .recover { case NonFatal(e) => logger.error("Job cancel error", e)}
      for {
        _ <- cancelJobs
        _ <- jobsStore.removeManyAsync("engineId" === engineId)
      } yield ()
    }
  }

  override def cancelJob(engineId: String, jobId: String): Future[Unit] = {
    jobDescriptions.get(engineId).fold(Future.successful(())) { jds =>
      jds.find(_._2.jobId == jobId).fold(Future.successful(())) {
        case (cancellable, _) =>
          val f = cancellable.cancel().flatMap(_ => LivyJobServerSupport.cancel(jobId))
          f.onComplete {
            case Success(_) =>
              jobsStore.update("jobId" === jobId)("status" -> JobStatuses.cancelled)
              logger.info(s"Job $jobId [engine id $engineId] cancelled")
            case Failure(NonFatal(e)) =>
              logger.error(s"Cancel job error", e)
          }
          f
      }
    }
  }

  override def abortExecutingJobs: Future[Unit] = {
    jobsStore
      .updateAsync("status" !== JobStatuses.failed,
                   "status" !== JobStatuses.successful,
                   "status" !== JobStatuses.cancelled)("status" -> JobStatuses.failed, "completedAt" -> new Date)
      .map(_ => ())
  }

  override def finishJob(jobId: String): Unit = {
    jobsStore.updateAsync("jobId" === jobId)("status" -> JobStatuses.successful, "completedAt" -> new Date)
      .onSuccess { case _ =>
        logger.info(s"Job $jobId completed successfully")
        removeLocal(jobId)
      }
  }

  override def markJobFailed(jobId: String): Unit = {
    jobsStore.updateAsync("jobId" === jobId)("status" -> JobStatuses.failed, "completedAt" -> new Date)
      .onSuccess { case _ =>
        removeLocal(jobId)
        logger.info(s"Job $jobId marked as failed")
      }
  }


  private def removeLocal(jobId: String): Unit = {
    jobDescriptions.collectFirst { case (engineId, jds) =>
      jobDescriptions.update(engineId, jds.filterNot(_._2.jobId == jobId))
    }
  }
}

final case class JobManagerConfig(expireAfter: Option[FiniteDuration])

final case class JobRecord(
  engineId: String,
  jobId: String,
  status: JobStatuses.JobStatus,
  comment: String,
  createdAt: Option[Date],
  completedAt: Option[Date],
  expireAt: Option[Date]
) {
  def toJobDescription = {
    val newStatus = expireAt.collect {
      case d if (Seq(JobStatuses.executing, JobStatuses.queued).contains(status)) && (d.compareTo(new Date) <= 0) => JobStatuses.expired
    }.getOrElse(status)
    JobDescription(jobId, newStatus, comment, createdAt, completedAt)
  }
}
object JobRecord {
  private val config = ConfigFactory.load
  private val jobsConfig = config.as[JobManagerConfig]("jobs")
  private[jobs] val defaultExpireMillis: Long = jobsConfig.expireAfter.map(_.toMillis).getOrElse(43200000) // 12 hours by default
  private def expireAt(start: Option[Date]): Option[Date] = {
    start.map(d => new Date(d.getTime + defaultExpireMillis))
  }

  def apply(engineId: String, jd: JobDescription): JobRecord =
    JobRecord(engineId, jd.jobId, jd.status, jd.comment, jd.createdAt, jd.completedAt, expireAt(jd.createdAt))

  val mongoCodecs: List[CodecProvider] = {
    import org.mongodb.scala.bson.codecs.Macros._
    object JobStatusesEnumCodecProvider extends CodecProvider {
      def isCaseObjectEnum[T](clazz: Class[T]): Boolean = {
        clazz.isInstance(JobStatuses.queued) ||
          clazz.isInstance(JobStatuses.executing) ||
          clazz.isInstance(JobStatuses.failed) ||
          clazz.isInstance(JobStatuses.successful) ||
          clazz.isInstance(JobStatuses.cancelled)
      }

      override def get[T](clazz: Class[T], registry: CodecRegistry): Codec[T] = {
        if (isCaseObjectEnum(clazz)) {
          CaseObjectEnumCodec.asInstanceOf[Codec[T]]
        } else {
          null
        }
      }

      object CaseObjectEnumCodec extends Codec[JobStatuses.JobStatus] {
        override def decode(reader: BsonReader, decoderContext: DecoderContext): JobStatuses.JobStatus = {
          val enumName = reader.readString()
          Try(JobStatuses.withName(enumName))
            .toOption
            .getOrElse(throw new BsonInvalidOperationException(s"$enumName is an invalid value for a JobStatuses object"))
        }

        override def encode(writer: BsonWriter, value: JobStatuses.JobStatus, encoderContext: EncoderContext): Unit = {
          writer.writeString(value.toString)
        }

        override def getEncoderClass: Class[JobStatuses.JobStatus] = JobStatuses.getClass.asInstanceOf[Class[JobStatuses.JobStatus]]
      }
    }
    List(JobStatusesEnumCodecProvider, classOf[JobRecord])
  }
}

trait Cancellable {
  def cancel(): Future[Unit]
}

object Cancellable {
  val noop: Cancellable = new Cancellable {
    override def cancel() = Future.successful(())
  }
}

case class JobDescription (
  jobId: String,
  status: JobStatus = JobStatuses.queued,
  comment: String = "",
  createdAt: Option[Date] = Option(new Date),
  completedAt: Option[Date] = None
) extends Response

object JobDescription {
  def create: JobDescription = JobDescription(UUID.randomUUID().toString)
  def create(status: JobStatuses.JobStatus, comment: String): JobDescription =
    JobDescription(UUID.randomUUID().toString, status = status, comment = comment)
}

object JobStatuses extends Enumeration {
  type JobStatus = Value
  val queued = Value("queued")
  val executing = Value("executing")
  val successful = Value("successful")
  val failed = Value("failed")
  val cancelled = Value("cancelled")
  val expired = Value("expired")
}

