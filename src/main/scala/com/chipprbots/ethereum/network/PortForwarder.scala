package com.chipprbots.ethereum.network

import java.net.InetAddress
import java.util.concurrent.ExecutorService

import cats.effect.IO
import cats.effect.Resource
import cats.implicits.*

import scala.jdk.CollectionConverters.*
import scala.util.chaining.*

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

private[network] class ClientOnlyUpnpServiceConfiguration extends DefaultUpnpServiceConfiguration():
  final private val THREAD_POOL_SIZE = 4 // seemingly the minimum required to perform port mapping

  override def createDefaultExecutorService(): ExecutorService =
    QueueingThreadPoolExecutor.createInstance("fukuii-jupnp", THREAD_POOL_SIZE);

  override def createStreamClient(): StreamClient[? <: StreamClientConfiguration] =
    // Use Apache HttpClient-based transport to avoid URLStreamHandlerFactory issues
    val config = new StreamClientConfiguration():
      override def getTimeoutSeconds(): Int = 10
      override def getLogWarningSeconds(): Int = 5
      override def getRetryAfterSeconds(): Int = 60
      override def getRetryIterations(): Int = 0
      override def getRequestExecutorService(): java.util.concurrent.ExecutorService =
        getSyncProtocolExecutorService()
      override def getUserAgentValue(majorVersion: Int, minorVersion: Int): String =
        s"Fukuii/$majorVersion.$minorVersion UPnP/1.1"
    new ApacheHttpClientStreamClient(config)

  override def createStreamServer(networkAddressFactory: NetworkAddressFactory): NoStreamServer.type =
    NoStreamServer // prevent a StreamServer from running needlessly

private[network] object NoStreamServer extends StreamServer[StreamServerConfiguration]:
  def run(): Unit = ()
  def init(_1: InetAddress, _2: Router): Unit = ()
  def getPort(): Int = 0
  def stop(): Unit = ()
  def getConfiguration(): StreamServerConfiguration = new StreamServerConfiguration:
    def getListenPort(): Int = 0

/** A no-op UPnP service implementation used as a fallback when UPnP initialization fails. This allows the node to
  * continue running without automatic port forwarding.
  *
  * WARNING: This service returns null for all getter methods. It should only be used for the shutdown lifecycle method
  * and should not have its methods called. The service is created only when UPnP initialization fails, and is
  * immediately passed to stopForwarding for cleanup.
  */
private class NoOpUpnpService extends UpnpService:
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

object PortForwarder extends Logger:
  final private val description = "Fukuii"

  /** Ask any UPnP gateway on the network to map `tcpPorts` and `udpPorts` to this host, for as long as the resource is
    * held. What becomes of it is recorded in `mappingState` (B-287).
    */
  def openPorts(
      tcpPorts: Seq[Int],
      udpPorts: Seq[Int],
      mappingState: PortMappingState = new PortMappingState
  ): Resource[IO, Unit] =
    Resource.make(startForwarding(tcpPorts, udpPorts, mappingState))(stopForwarding).void

  /** jupnp's listener, with what the gateway did kept for the `net` report: its refusals, and the mappings it gave. */
  final private class ObservedPortMappingListener(mappings: Array[PortMapping], mappingState: PortMappingState)
      extends PortMappingListener(mappings):
    def granted: Int =
      // Written by jupnp's callbacks on its own threads; read for display only.
      scala.util.Try(synchronized(activePortMappings.values.asScala.map(_.size).sum)).getOrElse(0)

    override protected def handleFailureMessage(s: String): Unit =
      mappingState.failed(s)
      super.handleFailureMessage(s)

  private def startForwarding(
      tcpPorts: Seq[Int],
      udpPorts: Seq[Int],
      mappingState: PortMappingState
  ): IO[UpnpService] = IO {
    log.info("Attempting port forwarding for TCP ports {} and UDP ports {}", tcpPorts, udpPorts)
    try
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

        val portMappings = for
          address <- bindAddresses
          (port, protocol) <- tcpPorts.map(_ -> TCP) ++ udpPorts.map(_ -> UDP)
        yield new PortMapping(port, address, protocol).tap(_.setDescription(description))

        val listener = new ObservedPortMappingListener(portMappings, mappingState)
        mappingState.asked(portMappings.length, () => listener.granted)
        service.getRegistry().addListener(listener)
        log.info("UPnP port forwarding initialized successfully")
      }
    catch
      case ex: org.jupnp.transport.spi.InitializationException =>
        log.warn(
          "Failed to initialize UPnP port forwarding: {}. " +
            "The node will continue to run, but automatic port forwarding is disabled. " +
            "Please configure port forwarding manually on your router if needed.",
          ex.getMessage
        )
        mappingState.unavailable(Option(ex.getMessage).getOrElse(ex.toString))
        // Return a no-op service that can be safely shut down
        new NoOpUpnpService()
      case ex: Throwable =>
        log.warn(
          "Unexpected error during UPnP initialization: {}. " +
            "The node will continue to run without automatic port forwarding.",
          ex.getMessage
        )
        mappingState.unavailable(Option(ex.getMessage).getOrElse(ex.toString))
        new NoOpUpnpService()
  }

  private def stopForwarding(service: UpnpService) = IO {
    service.shutdown()
  }
