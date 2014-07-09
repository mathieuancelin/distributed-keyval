import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.{Executors, TimeUnit}

import common.{IdGenerator, Logger}
import org.specs2.mutable.{Specification, Tags}
import play.api.libs.json.Json
import server.{ClusterEnv, KeyValNode, NodeClient}

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext}

class BasicSpec extends Specification with Tags {
  sequential

  "Distributed Map" should {

    implicit val timeout = Duration(10, TimeUnit.SECONDS)
    implicit val ec = ExecutionContext.fromExecutor(Executors.newCachedThreadPool())

    val env = ClusterEnv(4)
    val node1 = KeyValNode(s"node1-${IdGenerator.token(6)}", env)
    val node2 = KeyValNode(s"node2-${IdGenerator.token(6)}", env)
    val node3 = KeyValNode(s"node3-${IdGenerator.token(6)}", env)
    val node4 = KeyValNode(s"node4-${IdGenerator.token(6)}", env)
    val node5 = KeyValNode(s"node5-${IdGenerator.token(6)}", env)
    val node6 = KeyValNode(s"node6-${IdGenerator.token(6)}", env)
    val node7 = KeyValNode(s"node7-${IdGenerator.token(6)}", env)
    val node8 = KeyValNode(s"node8-${IdGenerator.token(6)}", env)
    val node9 = KeyValNode(s"node9-${IdGenerator.token(6)}", env)
    val client = NodeClient(env)
    var keys = Seq[String]()
    val counterOk = new AtomicLong(0L)
    val counterKo = new AtomicLong(0L)

    "Start some nodes" in {
      node1.start()
      node2.start()
      node3.start()
      node4.start()
      node5.start()
      node6.start()
      node7.start()
      node8.start()
      node9.start()
      client.start()
      Thread.sleep(6000)   // Wait for cluster setup
      success
    }

    "Insert some stuff" in {
      for (i <- 0 to 1000) {
        val id = IdGenerator.uuid
        keys = keys :+ id
        Await.result( client.set(id, Json.obj(
          "Hello" -> "World", "key" -> id
        )), timeout)
      }
      success
    }

    "Shutdown some nodes" in {
      node2.displayStats().stop().destroy()
      node4.displayStats().stop().destroy()
      node6.displayStats().stop().destroy()
      Thread.sleep(30000)
      success
    }

    "Read some stuff" in {
      keys.foreach { key =>
        val expected = Some(Json.obj("Hello" -> "World", "key" -> key))
        if (Await.result(client.get(key), timeout) == expected) counterOk.incrementAndGet()
        else counterKo.incrementAndGet()
        //shouldEqual Some(Json.obj("Hello" -> "World", "key" -> key))
      }
      success
    }

    "Delete stuff" in {
      keys.foreach { key =>
        Await.result(client.delete(key), timeout)
      }
      success
    }

    "Stop the nodes" in {
      node1.displayStats().stop().destroy()
      node3.displayStats().stop().destroy()
      node5.displayStats().stop().destroy()
      node7.displayStats().stop().destroy()
      node8.displayStats().stop().destroy()
      node9.displayStats().stop().destroy()
      client.stop()
      Thread.sleep(5000)
      Logger.info(s"Read OK ${counterOk.get()}")
      Logger.info(s"Read KO ${counterKo.get()}")
      counterKo.get() shouldEqual 0L
      success
    }
  }
}