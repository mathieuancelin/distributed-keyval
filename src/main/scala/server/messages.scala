package server

import com.codahale.metrics.Timer.Context
import play.api.libs.json.JsValue

import scala.concurrent.Promise

trait Operation {
  val key: String
  val timestamp: Long
  val operationId: Long
}
case class Coordinates(operation: Operation)
case class SetOperation(key: String, value: JsValue, timestamp: Long, operationId: Long, start: Long = System.nanoTime()) extends Operation
case class GetOperation(key: String, timestamp: Long, operationId: Long, start: Long = System.nanoTime()) extends Operation
case class DeleteOperation(key: String, timestamp: Long, operationId: Long, start: Long = System.nanoTime()) extends Operation
case class OpStatus(successful: Boolean, key: String, value: Option[JsValue], timestamp: Long, operationId: Long, old: Option[JsValue] = None)
case class SyncCacheAndBalance()
case class Rollback(status: OpStatus)
case class RollbackOperation(trigger: Promise[Unit], block: () => Unit)
case class RollbackPusher(key: String)

