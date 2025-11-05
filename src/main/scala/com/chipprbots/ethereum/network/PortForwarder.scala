package com.chipprbots.ethereum.network

import java.net.InetAddress
import java.util.concurrent.ExecutorService

import cats.effect.IO
import cats.effect.Resource
import cats.implicits._

import scala.jdk.CollectionConverters._
import scala.util.chaining._

import org.jupnp.DefaultUpnpServiceConfiguration
import org.jupnp.QueueingThreadPoolExecutor
import org.jupnp.UpnpService
import org.jupnp.UpnpServiceImpl
import org.jupnp.support.igd.PortMappingListener
import org.jupnp.support.model.PortMapping
import org.jupnp.support.model.PortMapping.Protocol.TCP
import org.jupnp.support.model.PortMapping.Protocol.UDP
import org.jupnp.transport.Router
import org.jupnp.transport.spi.NetworkAddressFactory
import org.jupnp.transport.spi.StreamClient
import org.jupnp.transport.spi.StreamClientConfiguration
import org.jupnp.transport.spi.StreamServer
import org.jupnp.transport.spi.StreamServerConfiguration

import com.chipprbots.ethereum.utils.Logger

private class ClientOnlyUpnpServiceConfiguration extends DefaultUpnpServiceConfiguration() {
  final private val THREAD_POOL_SIZE = 4 // seemingly the minimum required to perform port mapping

  override def createDefaultExecutorService(): ExecutorService =
    QueueingThreadPoolExecutor.createInstance("fukuii-jupnp", THREAD_POOL_SIZE);

  override def createStreamClient(): StreamClient[_ <: StreamClientConfiguration] = {
    // Use Apache HttpClient-based transport to avoid URLStreamHandlerFactory issues
    val config = new StreamClientConfiguration() {
      override def getTimeoutSeconds(): Int = 10
      override def getLogWarningSeconds(): Int = 5
      override def getRetryAfterSeconds(): Int = 60
      override def getRequestExecutorService(): java.util.concurrent.ExecutorService = 
        getSyncProtocolExecutorService()
      override def getUserAgentValue(majorVersion: Int, minorVersion: Int): String = 
        s"Fukuii/$majorVersion.$minorVersion UPnP/1.1"
    }
    new ApacheHttpClientStreamClient(config)
  }

  override def createStreamServer(networkAddressFactory: NetworkAddressFactory): NoStreamServer.type =
    NoStreamServer // prevent a StreamServer from running needlessly
}

private object NoStreamServer extends StreamServer[StreamServerConfiguration] {
  def run(): Unit = ()
  def init(_1: InetAddress, _2: Router): Unit = ()
  def getPort(): Int = 0
  def stop(): Unit = ()
  def getConfiguration(): StreamServerConfiguration = new StreamServerConfiguration {
    def getListenPort(): Int = 0
  }
}

/** A no-op UPnP service implementation used as a fallback when UPnP initialization fails.
  * This allows the node to continue running without automatic port forwarding.
  */
private class NoOpUpnpService extends UpnpService {
  import org.jupnp.UpnpServiceConfiguration
  import org.jupnp.controlpoint.ControlPoint
  import org.jupnp.protocol.ProtocolFactory
  import org.jupnp.registry.Registry

  def getConfiguration(): UpnpServiceConfiguration = null
  def getControlPoint(): ControlPoint = null
  def getProtocolFactory(): ProtocolFactory = null
  def getRegistry(): Registry = null
  def getRouter(): Router = null
  def shutdown(): Unit = ()
  def startup(): Unit = ()
}

object PortForwarder extends Logger {
  final private val description = "Fukuii"

  def openPorts(tcpPorts: Seq[Int], udpPorts: Seq[Int]): Resource[IO, Unit] =
    Resource.make(startForwarding(tcpPorts, udpPorts))(stopForwarding).void

  private def startForwarding(tcpPorts: Seq[Int], udpPorts: Seq[Int]): IO[UpnpService] = IO {
    log.info("Attempting port forwarding for TCP ports {} and UDP ports {}", tcpPorts, udpPorts)
    try {
      new UpnpServiceImpl(new ClientOnlyUpnpServiceConfiguration()).tap { service =>
        service.startup()

        val bindAddresses =
          service
            .getConfiguration()
            .createNetworkAddressFactory()
            .getBindAddresses()
            .asScala
            .map(_.getHostAddress())
            .toArray

        val portMappings = for {
          address <- bindAddresses
          (port, protocol) <- tcpPorts.map(_ -> TCP) ++ udpPorts.map(_ -> UDP)
        } yield new PortMapping(port, address, protocol).tap(_.setDescription(description))

        service.getRegistry().addListener(new PortMappingListener(portMappings))
        log.info("UPnP port forwarding initialized successfully")
      }
    } catch {
      case ex: org.jupnp.transport.spi.InitializationException =>
        log.warn(
          "Failed to initialize UPnP port forwarding: {}. " +
            "The node will continue to run, but automatic port forwarding is disabled. " +
            "Please configure port forwarding manually on your router if needed.",
          ex.getMessage
        )
        // Return a no-op service that can be safely shut down
        new NoOpUpnpService()
      case ex: Throwable =>
        log.warn(
          "Unexpected error during UPnP initialization: {}. " +
            "The node will continue to run without automatic port forwarding.",
          ex.getMessage
        )
        new NoOpUpnpService()
    }
  }

  private def stopForwarding(service: UpnpService) = IO {
    if (service != null) {
      service.shutdown()
    }
  }
}
