package common

import java.net._
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import java.util.concurrent.{ConcurrentLinkedQueue, TimeUnit}

import akka.actor.ActorSystem
import akka.cluster.Cluster
import com.typesafe.config.{Config, ConfigFactory}
import config.Env
import org.jgroups.{JChannel, Message, ReceiverAdapter}

import scala.collection.JavaConversions._
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.duration.Duration
import scala.concurrent.{Promise, ExecutionContext, Future}
import scala.util.{Random, Try}

class SeedConfig(conf: Config, channel: JChannel, address: String, port: Int) {
  private[this] val joined = new AtomicBoolean(false)
  private[this] val noMore = new AtomicBoolean(false)
  private[this] val clusterRef = new AtomicReference[Cluster]()
  private[this] val addresses = new ConcurrentLinkedQueue[akka.actor.Address]()
  private[this] val waitingFor = 2
  private[this] val p = Promise[Unit]()
  def config() = conf
  def shutdown() = channel.close()
  def joinCluster(cluster: Cluster): Future[Unit] = {
    joined.set(true)
    clusterRef.set(cluster)
    addresses.offer(akka.actor.Address("akka.tcp", Env.systemName, address, port))
    p.future
  }
  private[this] def joinIfReady() = {
    if (addresses.size() == waitingFor) {
      clusterRef.get().joinSeedNodes(scala.collection.immutable.Seq().++(addresses.toSeq))
      noMore.set(true)
      p.trySuccess(())
    }
  }
  def forceJoin(): Unit = {
    clusterRef.get().joinSeedNodes(scala.collection.immutable.Seq().++(addresses.toSeq))
    noMore.set(true)
    p.trySuccess(())
  }
  private[common] def newSeed(message: String) = {
    if (joined.get() && !noMore.get()) {
      message.split("\\:").toList match {
        case addr :: prt :: Nil => {
          addresses.offer(akka.actor.Address("akka.tcp", Env.systemName, addr, prt.toInt))
          joinIfReady()
        }
        case _ =>
      }
    }
  }
}

case class ClusterConfig(config: Config, address: String, port: Int) {
  def join(cluster: Cluster, seedNodes: Seq[String]) = {
    val addresses = scala.collection.immutable.Seq().++(seedNodes.:+(s"$address:$port").map { message =>
      message.split("\\:").toList match {
        case addr :: prt :: Nil => akka.actor.Address("akka.tcp", Env.systemName, addr, prt.toInt)
      }
    }.toSeq)
    cluster.joinSeedNodes(addresses)
  }
}

object SeedHelper {

  def freePort: Int = {
    Try {
      val serverSocket = new ServerSocket(0)
      val port = serverSocket.getLocalPort
      serverSocket.close()
      port
    }.toOption.getOrElse(Random.nextInt(1000) + 7000)
  }

  def manualBootstrap(address: String, port: Int, configuration: Configuration, clientOnly: Boolean)(implicit ec: ExecutionContext): ClusterConfig = {
    val configBuilder = new StringBuilder()
    var config = configuration.underlying.getConfig("map-config")
    val fallback = configuration.underlying.getConfig("map-config")
    //val address = InetAddress.getLocalHost.getHostAddress
    //val port = freePort
    configBuilder.append(s"akka.remote.netty.tcp.port=$port\n")
    configBuilder.append(s"akka.remote.netty.tcp.hostname=$address\n")
    if (clientOnly) {
      configBuilder.append(s"""akka.cluster.roles=["${Env.clientRole}"]\n""")
    } else {
      configBuilder.append(s"""akka.cluster.roles=["${Env.nodeRole}"]\n""")
    }
    config = ConfigFactory.parseString(configBuilder.toString()).withFallback(fallback)
    Logger("SeedHelper").debug(s"Akka remoting will be bound to akka.tcp://${Env.systemName}@$address:$port")
    new ClusterConfig(config, address, port)
  }

  def bootstrapSeed(system: ActorSystem, configuration: Configuration, clientOnly: Boolean)(implicit ec: ExecutionContext): SeedConfig = {
    val configBuilder = new StringBuilder()
    var config = configuration.underlying.getConfig("map-config")
    val fallback = configuration.underlying.getConfig("map-config")
    val address = InetAddress.getLocalHost.getHostAddress
    val port = freePort
    configBuilder.append(s"akka.remote.netty.tcp.port=$port\n")
    configBuilder.append(s"akka.remote.netty.tcp.hostname=$address\n")
    if (clientOnly) {
      configBuilder.append(s"""akka.cluster.roles=["${Env.clientRole}"]\n""")
    } else {
      configBuilder.append(s"""akka.cluster.roles=["${Env.nodeRole}"]\n""")
    }
    config = ConfigFactory.parseString(configBuilder.toString()).withFallback(fallback)
    Logger("SeedHelper").debug(s"Akka remoting will be bound to akka.tcp://${Env.systemName}@$address:$port")
    val channel = new JChannel()
    channel.connect("distributed-map")
    val seeds = new SeedConfig(config, channel, address, port)
    channel.setReceiver(new ReceiverAdapter() {
      override def receive(msg: Message): Unit = {
        val myself = channel.getAddress
        if (msg.getSrc != myself) {
          seeds.newSeed(msg.getObject.asInstanceOf[String])
        }
      }
    })
    def broadcastWhoIAm(duration: FiniteDuration)(implicit ec: ExecutionContext): Unit = {
      Try {
        system.scheduler.scheduleOnce(duration) {
          val msg = new Message(null, channel.getAddress, s"$address:$port")
          Try {
            channel.send(msg)
            broadcastWhoIAm(Duration(2, TimeUnit.SECONDS))(ec)
          }
        }
      }
    }
    if (!clientOnly) broadcastWhoIAm(Duration(1, TimeUnit.SECONDS))(ec)
    seeds
  }
}

