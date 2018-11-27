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

package com.actionml.engines.ur

import cats.data.Validated
import cats.data.Validated.Valid
import com.actionml.core.drawInfo
import com.actionml.core.engine.{Engine, QueryResult}
import com.actionml.core.jobs.{JobDescription, JobManager}
import com.actionml.core.model.{EngineParams, Event, Query}
import com.actionml.core.store.Ordering._
import com.actionml.core.store.backends.MongoStorage
import com.actionml.core.store.indexes.annotations.Indexed
import com.actionml.core.validate.{JsonSupport, ValidateError}
import com.actionml.engines.ur.UREngine.{UREngineParams, UREvent, URQuery}
import com.actionml.engines.ur.URDataset
import org.json4s.JValue

import scala.concurrent.duration._


class UREngine extends Engine with JsonSupport {

  private var dataset: URDataset = _
  private var algo: URAlgorithm = _
  private var params: UREngineParams = _

  /** Initializing the Engine sets up all needed objects */
  override def init(jsonConfig: String, update: Boolean = false): Validated[ValidateError, String] = {
    super.init(jsonConfig).andThen { _ =>

      parseAndValidate[UREngineParams](jsonConfig).andThen { p =>
        params = p
        engineId = params.engineId
        val dbName = p.sharedDBName.getOrElse(engineId)
        dataset = new URDataset(engineId = engineId, store = MongoStorage.getStorage(dbName, MongoStorageHelper.codecs))
        val eventsDao = dataset.store.createDao[UREvent](dataset.getIndicatorEventsCollectionName)
        algo = URAlgorithm(this, jsonConfig, dataset, eventsDao)
        logStatus(p)
        Valid(p)
      }.andThen { p =>
        dataset.init(jsonConfig).andThen { r =>
          algo.init(this)
        }
      }
    }
  }

  def logStatus(p: UREngineParams) = {
    drawInfo("UR Engine", Seq(
      ("════════════════════════════════════════", "══════════════════════════════════════"),
      ("Engine ID: ", engineId),
      ("Mirror type: ", p.mirrorType),
      ("Mirror Container: ", p.mirrorContainer),
      ("Shared DB name: ", p.sharedDBName)))
  }

  // Used starting Harness and adding new engines, persisted means initializing a pre-existing engine. Only called from
  // the administrator.
  // Todo: This method for re-init or new init needs to be refactored, seem ugly
  // Todo: should return null for bad init
  override def initAndGet(jsonConfig: String): UREngine = {
    val response = init(jsonConfig)
    if (response.isValid) {
      logger.trace(s"Initialized with JSON: $jsonConfig")
      this
    } else {
      logger.error(s"Parse error with JSON: $jsonConfig")
      null.asInstanceOf[UREngine] // todo: ugly, replace
    }
  }

  override def input(jsonEvent: String): Validated[ValidateError, String] = {
    logger.trace("Got JSON body: " + jsonEvent)
    // validation happens as the input goes to the dataset
    //super.input(jsonEvent).andThen(_ => dataset.input(jsonEvent)).andThen { _ =>
    super.input(jsonEvent).andThen(_ => dataset.input(jsonEvent)).andThen { _ =>
      parseAndValidate[UREvent](jsonEvent).andThen(algo.input)
    }
    //super.input(jsonEvent).andThen(dataset.input(jsonEvent)).andThen(algo.input(jsonEvent)).map(_ => true)
  }

  // todo: should merge base engine status with UREngine's status
  override def status(): Validated[ValidateError, String] = {
    import org.json4s.jackson.Serialization.write

    logStatus(params)
    Valid(s"""
       |{
       |    "engineParams": ${write(params)},
       |    "jobStatuses": ${write[Map[String, JobDescription]](JobManager.getActiveJobDescriptions(engineId))}
       |}
     """.stripMargin)
  }

  override def train(): Validated[ValidateError, String] = {
    algo.train()
  }

  /** triggers parse, validation of the query then returns the result as JSONharness */
  def query(jsonQuery: String): Validated[ValidateError, String] = {
    logger.trace(s"Got a query JSON string: $jsonQuery")
    parseAndValidate[URQuery](jsonQuery).andThen { query =>
      val result = algo.query(query)
      Valid(result.toJson)
    }
  }

  // todo: should kill any pending Spark jobs
  override def destroy(): Unit = {
    logger.info(s"Dropping persisted data for id: $engineId")
    dataset.destroy()
    algo.destroy()
  }

}

object UREngine {
  def apply(jsonConfig: String): UREngine = {
    val engine = new UREngine()
    engine.initAndGet(jsonConfig)
  }

  case class UREngineParams(
      engineId: String, // required, resourceId for engine
      engineFactory: String,
      mirrorType: Option[String] = None,
      mirrorContainer: Option[String] = None,
      sharedDBName: Option[String] = None,
      sparkConf: Map[String, JValue])
    extends EngineParams {

    import org.json4s._
    import org.json4s.jackson.Serialization
    import org.json4s.jackson.Serialization.write

    implicit val formats = Serialization.formats(NoTypeHints)

    def toJson: String = {
      write(this)
    }

  }

  case class UREvent (
      //eventId: String, // not used in Harness, but allowed for PIO compatibility
      event: String,
      entityType: String,
      @Indexed(order = asc) entityId: String,
      targetEntityId: Option[String] = None,
      properties: Map[String, Boolean] = Map.empty,
      conversionId: Option[String] = None, // only set when copying converted journey's where event = nav-event
      @Indexed(order = desc, ttl = 30 days) eventTime: String) // ISO8601 date
    extends Event with Serializable

  case class ItemProperties (
      _id: String, // must be the same as the targetEntityId for the $set event that changes properties in the model
      properties: Map[String, Any] // properties to be written to the model, this is saved in the input dataset
  ) extends Serializable

  case class URQuery(
      user: String, // ignored for non-personalized
      eligibleNavIds: Array[String])
    extends Query

  case class URQueryResult(
      navHints: Seq[(String, Double)] = Seq.empty)
    extends QueryResult {

    def toJson: String = {
      val jsonStart =
        s"""
           |{
           |  "result": [
        """.stripMargin
      val jsonMiddle = navHints.map{ case (k, v) =>
        s"""
           |   {$k, $v},
       """.stripMargin
      }.mkString
      val jsonEnd =
        s"""
           |  ]
           |}
        """.stripMargin
      val retVal = jsonStart + jsonMiddle + jsonEnd
      retVal
    }
  }

}
