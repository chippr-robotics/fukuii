package com.chipprbots.scalanet.peergroup.udp

import java.io.IOException
import java.net.InetSocketAddress

import cats.effect.IO
import cats.effect.Ref
import cats.effect.Resource
import cats.effect.std.Semaphore
import cats.effect.unsafe.implicits.global
import cats.implicits._

import scala.util.control.NonFatal

import com.chipprbots.scalanet.peergroup.Channel
import com.chipprbots.scalanet.peergroup.Channel.ChannelEvent
import com.chipprbots.scalanet.peergroup.Channel.DecodingError
import com.chipprbots.scalanet.peergroup.Channel.MessageReceived
import com.chipprbots.scalanet.peergroup.Channel.UnexpectedError
import com.chipprbots.scalanet.peergroup.CloseableQueue
import com.chipprbots.scalanet.peergroup.ControlEvent.InitializationError
import com.chipprbots.scalanet.peergroup.InetMultiAddress
import com.chipprbots.scalanet.peergroup.NettyFutureUtils.toTask
import com.chipprbots.scalanet.peergroup.PeerGroup.ChannelAlreadyClosedException
import com.chipprbots.scalanet.peergroup.PeerGroup.MessageMTUException
import com.chipprbots.scalanet.peergroup.PeerGroup.ServerEvent
import com.chipprbots.scalanet.peergroup.PeerGroup.ServerEvent.ChannelCreated
import com.chipprbots.scalanet.peergroup.PeerGroup.TerminalPeerGroup
import com.chipprbots.scalanet.peergroup.Release
import com.typesafe.scalalogging.StrictLogging
import io.netty.bootstrap.Bootstrap
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.ChannelInitializer
import io.netty.channel.ChannelOption
import io.netty.channel.RecvByteBufAllocator
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.DatagramPacket
import io.netty.channel.socket.nio.NioDatagramChannel
import scodec.Attempt
import scodec.Codec
import scodec.bits.BitVector

/**
  * PeerGroup implementation on top of UDP that uses the same local port
  * when creating channels to remote addresses as the one it listens on
  * for incoming messages.
  *
  * This makes it compatible with protocols that update the peer's port
  * to the last one it sent a message from.
  *
  * It also means that incoming messages cannot be tied to a specific channel,
  * so if multiple channels are open to the same remote address,
  * they will all see the same messages. The incoming responses will also
  * cause a server channel to be opened, where response type messages have
  * to be discarded, and the server channel can be discarded if there's no
  * request type message for a long time.
  *
  * @tparam M the message type.
  */
class StaticUDPPeerGroup[M] private (
    config: StaticUDPPeerGroup.Config,
    workerGroup: NioEventLoopGroup,
    isShutdownRef: Ref[IO, Boolean],
    serverQueue: CloseableQueue[ServerEvent[InetMultiAddress, M]],
    serverChannelSemaphore: Semaphore[IO],
    serverChannelsRef: Ref[IO, Map[InetSocketAddress, StaticUDPPeerGroup.ChannelAlloc[M]]],
    clientChannelsRef: Ref[IO, Map[InetSocketAddress, Set[StaticUDPPeerGroup.ChannelAlloc[M]]]],
    boundChannelRef: Ref[IO, Option[io.netty.channel.Channel]]
)(implicit codec: Codec[M])
    extends TerminalPeerGroup[InetMultiAddress, M]
    with StrictLogging {

  import StaticUDPPeerGroup.{ChannelImpl, ChannelAlloc}

  override val processAddress = config.processAddress

  private val localAddress = config.bindAddress

  override def nextServerEvent =
    serverQueue.next

  def channelCount: IO[Int] =
    for {
      serverChannels <- serverChannelsRef.get
      clientChannels <- clientChannelsRef.get
    } yield serverChannels.size + clientChannels.values.map(_.size).sum

  private val raiseIfShutdown =
    isShutdownRef.get
      .ifM(IO.raiseError(new IllegalStateException("The peer group has already been shut down.")), IO.unit)

  /** Create a new channel from the local server port to the remote address. */
  override def client(to: InetMultiAddress): Resource[IO, Channel[InetMultiAddress, M]] = {
    for {
      _ <- Resource.eval(raiseIfShutdown)
      remoteAddress = to.inetSocketAddress
      nettyChannel <- Resource.eval(boundChannelRef.get.flatMap {
        case Some(ch) => IO.pure(ch)
        case None => IO.raiseError(new IllegalStateException("UDP server channel not yet bound"))
      })
      channel <- Resource {
        ChannelImpl[M](
          nettyChannel = nettyChannel,
          localAddress = localAddress,
          remoteAddress = remoteAddress,
          role = ChannelImpl.Client,
          capacity = config.channelCapacity
        ).allocated.flatMap {
          case (channel, release) =>
            // Register the channel as belonging to the remote address so that
            // we can replicate incoming messages to it later.
            val add = for {
              _ <- addClientChannel(channel -> release)
              _ <- IO(logger.debug(s"Added UDP client channel from $localAddress to $remoteAddress"))
            } yield ()

            val remove = for {
              _ <- removeClientChannel(channel -> release)
              _ <- release
              _ <- IO(logger.debug(s"Removed UDP client channel from $localAddress to $remoteAddress"))
            } yield ()

            add.as(channel -> remove)
        }
      }
    } yield channel
  }

  private def addClientChannel(channel: ChannelAlloc[M]) =
    clientChannelsRef.update { clientChannels =>
      val remoteAddress = channel._1.to.inetSocketAddress
      val current = clientChannels.getOrElse(remoteAddress, Set.empty)
      clientChannels.updated(remoteAddress, current + channel)
    }

  private def removeClientChannel(channel: ChannelAlloc[M]) =
    clientChannelsRef.update { clientChannels =>
      val remoteAddress = channel._1.to.inetSocketAddress
      val current = clientChannels.getOrElse(remoteAddress, Set.empty)
      val removed = current - channel
      if (removed.isEmpty) clientChannels - remoteAddress else clientChannels.updated(remoteAddress, removed)
    }

  private def getOrCreateServerChannel(remoteAddress: InetSocketAddress): IO[ChannelImpl[M]] = {
    serverChannelsRef.get.map(_.get(remoteAddress)).flatMap {
      case Some((channel, _)) =>
        IO.pure(channel)

      case None =>
        // Use a semaphore to make sure we only create one channel.
        // This way we can handle incoming messages asynchronously.
        serverChannelSemaphore.permit.use { _ =>
          serverChannelsRef.get.map(_.get(remoteAddress)).flatMap {
            case Some((channel, _)) =>
              IO.pure(channel)

            case None =>
              boundChannelRef.get.flatMap {
                case Some(nettyChannel) =>
                  ChannelImpl[M](
                    nettyChannel = nettyChannel,
                    localAddress = config.bindAddress,
                    remoteAddress = remoteAddress,
                    role = ChannelImpl.Server,
                    capacity = config.channelCapacity
                  ).allocated.flatMap {
                    case (channel, release) =>
                      val remove = for {
                        _ <- serverChannelsRef.update(_ - remoteAddress)
                        _ <- release
                        _ <- IO(logger.debug(s"Removed UDP server channel from $remoteAddress to $localAddress"))
                      } yield ()

                      val add = for {
                        _ <- serverChannelsRef.update(_.updated(remoteAddress, channel -> release))
                        _ <- serverQueue.offer(ChannelCreated(channel, remove))
                        _ <- IO(logger.debug(s"Added UDP server channel from $remoteAddress to $localAddress"))
                      } yield channel

                      add.as(channel)
                  }
                case None =>
                  IO.raiseError(new IllegalStateException("UDP server channel not yet bound"))
              }
          }
        }
    }
  }

  private def getClientChannels(remoteAddress: InetSocketAddress): IO[Iterable[ChannelImpl[M]]] =
    clientChannelsRef.get.map {
      _.getOrElse(remoteAddress, Set.empty).toSeq.map(_._1)
    }

  private def getChannels(remoteAddress: InetSocketAddress): IO[Iterable[ChannelImpl[M]]] =
    isShutdownRef.get.ifM(
      IO.pure(Iterable.empty),
      for {
        serverChannel <- getOrCreateServerChannel(remoteAddress)
        clientChannels <- getClientChannels(remoteAddress)
        channels = Iterable(serverChannel) ++ clientChannels
      } yield channels
    )

  private def replicateToChannels(remoteAddress: InetSocketAddress)(
      f: ChannelImpl[M] => IO[Unit]
  ): IO[Unit] =
    for {
      channels <- getChannels(remoteAddress)
      // Note: Using sequential traverse_ instead of parTraverse_ to avoid complexity with Parallel typeclass
      // Original code used parTraverseUnordered for performance, but sequential execution is acceptable
      // for the typical small number of channels per remote address
      _ <- channels.toList.traverse_(f)
    } yield ()

  /** Replicate the incoming message to the server channel and all client channels connected to the remote address. */
  private def handleMessage(
      remoteAddress: InetSocketAddress,
      maybeMessage: Attempt[M]
  ): Unit =
    executeAsync {
      replicateToChannels(remoteAddress)(_.handleMessage(maybeMessage))
    }

  private def handleError(remoteAddress: InetSocketAddress, error: Throwable): Unit =
    executeAsync {
      replicateToChannels(remoteAddress)(_.handleError(error))
    }

  // Execute the task asynchronously. Has to be thread safe.
  private def executeAsync(task: IO[Unit]): Unit = {
    task.unsafeRunAndForget()
  }

  private def tryDecodeDatagram(datagram: DatagramPacket): Attempt[M] =
    codec.decodeValue(BitVector(datagram.content.nioBuffer)) match {
      case failure @ Attempt.Failure(err) =>
        logger.debug(s"Message decoding failed due to ${err}", err)
        failure
      case success =>
        success
    }

  private def bufferAllocator: RecvByteBufAllocator = {
    // `NioDatagramChannel.doReadMessages` allocates a new buffer for each read and
    // only reads one message at a time. UDP messages are independent, so if we know
    // our packages have a limited size (lower than the maximum 64KiB supported by UDP)
    // then we can save some resources by not over-allocating and also protecting
    // ourselves from malicious clients sending more than we'd accept.
    val maxBufferSize = 64 * 1024

    val bufferSize =
      if (config.receiveBufferSizeBytes <= 0) maxBufferSize
      else math.min(config.receiveBufferSizeBytes, maxBufferSize)

    new io.netty.channel.FixedRecvByteBufAllocator(bufferSize)
  }

  private lazy val serverBinding: io.netty.channel.ChannelFuture =
    new Bootstrap()
      .group(workerGroup)
      .channel(classOf[NioDatagramChannel])
      .option[RecvByteBufAllocator](ChannelOption.RCVBUF_ALLOCATOR, bufferAllocator)
      .handler(new ChannelInitializer[NioDatagramChannel]() {
        override def initChannel(nettyChannel: NioDatagramChannel): Unit = {
          nettyChannel
            .pipeline()
            .addLast(new ChannelInboundHandlerAdapter() {
              override def channelRead(ctx: ChannelHandlerContext, msg: Any): Unit = {
                val datagram = msg.asInstanceOf[DatagramPacket]
                val remoteAddress = datagram.sender
                try {
                  logger.debug(s"Server channel at $localAddress read message from $remoteAddress")
                  handleMessage(remoteAddress, tryDecodeDatagram(datagram))
                } catch {
                  case NonFatal(ex) =>
                    handleError(remoteAddress, ex)
                } finally {
                  datagram.content().release()
                  ()
                }
              }

              override def exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable): Unit = {
                // For UDP channels, we don't want to close on exceptions since it's connectionless
                // Log the exception and handle it, but don't propagate to super which might close the channel
                val channelId = ctx.channel().id()
                val remoteAddress = Option(ctx.channel.remoteAddress())
                  .collect { case addr: InetSocketAddress => addr }
                  .getOrElse(new InetSocketAddress(0))
                
                logger.debug(s"Exception in UDP channel $channelId from $remoteAddress: ${cause.getClass.getSimpleName}: ${cause.getMessage}")
                
                cause match {
                  case NonFatal(ex) =>
                    handleError(remoteAddress, ex)
                  case fatal =>
                    // For fatal exceptions, we might still want to log but not close UDP channel
                    logger.error(s"Fatal exception in UDP channel $channelId from $remoteAddress", fatal)
                }
                // Don't call super.exceptionCaught as it may close the channel
                // UDP is connectionless and should stay open for other packets
              }
            })

          ()
        }
      })
      .bind(localAddress)

  // Wait until the server is bound and channel is fully active
  private def initialize: IO[Unit] =
    for {
      _ <- raiseIfShutdown
      _ <- IO(logger.info(s"Initializing UDP server, waiting for bind to complete..."))
      // Access the lazy val to trigger bind operation and wait for it synchronously
      channel <- IO.blocking {
        val bindFuture = serverBinding
        val ch = bindFuture.channel()
        logger.info(s"Bind future created for channel ${ch.id()}")
        
        // CRITICAL FIX: For Netty UDP channels, we need to wait for:
        // 1. Channel to be registered with the event loop
        // 2. Bind operation to complete
        // 3. Channel to become active
        
        // Wait for registration if not already registered
        if (!ch.isRegistered) {
          logger.info("Channel not yet registered, waiting...")
          // This shouldn't happen with bootstrap.bind(), but just in case
        }
        
        // Wait for bind to complete
        bindFuture.syncUninterruptibly()
        logger.info(s"After bind sync: isDone=${bindFuture.isDone}, isSuccess=${bindFuture.isSuccess}")
        
        // Check if the bind was successful
        if (!bindFuture.isSuccess) {
          val cause = bindFuture.cause()
          logger.error(s"Bind future completed but was not successful. Cause: ${cause}")
          throw new IOException(s"Channel bind failed: ${if (cause != null) cause.getMessage else "unknown error"}", cause)
        }
        
        // At this point, the channel should be open and active
        // If it's not, log detailed state for debugging
        if (!ch.isOpen || !ch.isActive) {
          logger.warn(s"Channel state after successful bind: isOpen=${ch.isOpen}, isActive=${ch.isActive}, isRegistered=${ch.isRegistered}, localAddress=${ch.localAddress()}")
          // Wait a tiny bit for the channel to fully activate
          Thread.sleep(50)
        }
        
        logger.info(s"Channel final state: isOpen=${ch.isOpen}, isActive=${ch.isActive}, isRegistered=${ch.isRegistered}, localAddress=${ch.localAddress()}")
        ch
      }.handleErrorWith {
        case NonFatal(ex) =>
          IO.raiseError(InitializationError(s"Channel bind failed: ${ex.getMessage}", ex.getCause))
      }
      _ <- IO(logger.info(s"Bind sync completed. Channel: ${channel.getClass.getSimpleName}, isOpen=${channel.isOpen}, isActive=${channel.isActive}, isRegistered=${channel.isRegistered}, localAddress=${channel.localAddress()}"))
      // Verify the channel is actually usable
      // NOTE: Due to Netty's async initialization, the channel might not be fully active yet
      // We log warnings but don't fail initialization - the channel will become active shortly
      _ <- if (!channel.isOpen) {
        IO(logger.warn(
          s"Channel is not open immediately after bind (may still be initializing): isOpen=${channel.isOpen}, isActive=${channel.isActive}, isRegistered=${channel.isRegistered}"
        ))
      } else {
        IO.unit
      }
      _ <- if (!channel.isActive && channel.isOpen) {
        IO(logger.warn(
          s"Channel is open but not active immediately after bind (may still be initializing): isActive=${channel.isActive}"
        ))
      } else {
        IO.unit
      }
      _ <- if (!channel.isRegistered) {
        IO(logger.warn(
          s"Channel is not registered immediately after bind (may still be initializing): isRegistered=${channel.isRegistered}"
        ))
      } else {
        IO.unit
      }
      _ <- IO {
        // Add a close listener to detect when the channel closes
        channel.closeFuture().addListener(new io.netty.util.concurrent.GenericFutureListener[io.netty.util.concurrent.Future[_ >: Void]] {
          override def operationComplete(future: io.netty.util.concurrent.Future[_ >: Void]): Unit = {
            logger.warn(s"UDP server channel closed. Channel: ${channel.getClass.getSimpleName}, localAddress: ${config.bindAddress}")
          }
        })
      }
      _ <- boundChannelRef.set(Some(channel))
      _ <- IO(logger.info(s"UDP server successfully bound to ${config.bindAddress} and ready for use"))
    } yield ()

  private def shutdown: IO[Unit] = {
    for {
      _ <- IO(logger.info(s"Shutting down UDP peer group for peer ${config.processAddress}"))
      // Mark the group as shutting down to stop accepting incoming connections.
      _ <- isShutdownRef.set(true)
      _ <- serverQueue.close(discard = true)
      // Release client channels.
      _ <- clientChannelsRef.get.map(_.values.flatten.toList.map(_._2.attempt).sequence)
      // Release server channels.
      _ <- serverChannelsRef.get.map(_.values.toList.map(_._2.attempt).sequence)
      // Stop the in and outgoing traffic.
      _ <- boundChannelRef.get.flatMap {
        case Some(ch) => toTask(ch.close())
        case None => IO.unit
      }
    } yield ()
  }

}

object StaticUDPPeerGroup extends StrictLogging {
  case class Config(
      bindAddress: InetSocketAddress,
      processAddress: InetMultiAddress,
      // Maximum number of messages in the queue associated with the channel; 0 means unlimited.
      channelCapacity: Int,
      // Maximum size of an incoming message; 0 means the maximum 64KiB is allocated for each message.
      receiveBufferSizeBytes: Int
  )
  object Config {
    def apply(bindAddress: InetSocketAddress, channelCapacity: Int = 0, receiveBufferSizeBytes: Int = 0): Config =
      Config(bindAddress, InetMultiAddress(bindAddress), channelCapacity, receiveBufferSizeBytes)
  }

  private type ChannelAlloc[M] = (ChannelImpl[M], Release)

  def apply[M: Codec](config: Config): Resource[IO, StaticUDPPeerGroup[M]] =
    makeEventLoop.flatMap { workerGroup =>
      Resource.make {
        for {
          isShutdownRef <- Ref[IO].of(false)
          serverQueue <- CloseableQueue.unbounded[ServerEvent[InetMultiAddress, M]]
          serverChannelSemaphore <- Semaphore[IO](1)
          serverChannelsRef <- Ref[IO].of(Map.empty[InetSocketAddress, ChannelAlloc[M]])
          clientChannelsRef <- Ref[IO].of(Map.empty[InetSocketAddress, Set[ChannelAlloc[M]]])
          boundChannelRef <- Ref[IO].of(Option.empty[io.netty.channel.Channel])
          peerGroup = new StaticUDPPeerGroup[M](
            config,
            workerGroup,
            isShutdownRef,
            serverQueue,
            serverChannelSemaphore,
            serverChannelsRef,
            clientChannelsRef,
            boundChannelRef
          )
          _ <- peerGroup.initialize
        } yield peerGroup
      }(_.shutdown)
    }

  // Separate resource so if the server initialization fails, this still gets shut down.
  private val makeEventLoop =
    Resource.make {
      IO(new NioEventLoopGroup())
    } { group =>
      toTask(group.shutdownGracefully())
    }

  private class ChannelImpl[M](
      nettyChannel: io.netty.channel.Channel,
      localAddress: InetSocketAddress,
      remoteAddress: InetSocketAddress,
      messageQueue: CloseableQueue[ChannelEvent[M]],
      isClosedRef: Ref[IO, Boolean],
      role: ChannelImpl.Role
  )(implicit codec: Codec[M])
      extends Channel[InetMultiAddress, M]
      with StrictLogging {

    override val to: InetMultiAddress =
      InetMultiAddress(remoteAddress)

    override def from: InetMultiAddress =
      InetMultiAddress(localAddress)

    override def nextChannelEvent =
      messageQueue.next

    private val raiseIfClosed =
      isClosedRef.get.ifM(
        IO.raiseError(
          new ChannelAlreadyClosedException[InetMultiAddress](InetMultiAddress(localAddress), to)
        ),
        IO.unit
      )

    override def sendMessage(message: M): IO[Unit] =
      for {
        _ <- raiseIfClosed
        _ <- IO(
          logger.debug(s"Sending $role message ${message.toString.take(100)}... from $localAddress to $remoteAddress")
        )
        // Check if the Netty channel is actually open
        _ <- IO {
          if (!nettyChannel.isOpen) {
            logger.error(s"Netty channel is CLOSED when trying to send to $remoteAddress. Channel: ${nettyChannel.getClass.getSimpleName}, isActive: ${nettyChannel.isActive}, isRegistered: ${nettyChannel.isRegistered}")
          } else if (!nettyChannel.isActive) {
            logger.warn(s"Netty channel is open but NOT ACTIVE when trying to send to $remoteAddress. isRegistered: ${nettyChannel.isRegistered}")
          } else {
            logger.debug(s"Netty channel is open and active for sending to $remoteAddress")
          }
        }
        // Verify channel is open before attempting to send
        _ <- if (!nettyChannel.isOpen) {
          IO.raiseError(new IOException(s"Channel is closed, cannot send to $remoteAddress"))
        } else {
          IO.unit
        }
        encodedMessage <- IO.fromTry(codec.encode(message).toTry)
        asBuffer = encodedMessage.toByteBuffer
        // Check packet size before attempting to send
        // UDP supports up to 64KB theoretically, but practical MTU is typically 1280-1500 bytes
        // Using a conservative 64KB limit here to catch truly oversized packets
        _ <- if (asBuffer.capacity > 65535) {
          IO.raiseError(new MessageMTUException[InetMultiAddress](to, asBuffer.capacity))
        } else {
          IO.unit
        }
        packet = new DatagramPacket(Unpooled.wrappedBuffer(asBuffer), remoteAddress, localAddress)
        _ <- toTask(nettyChannel.writeAndFlush(packet)).handleErrorWith {
          case ex: IOException =>
            // Log the actual IOException to help diagnose the real problem
            IO(logger.error(s"Failed to send UDP packet to $remoteAddress: ${ex.getClass.getSimpleName}: ${ex.getMessage}", ex)) >>
            IO.raiseError(ex)
          case ex: Throwable =>
            // Catch any other exceptions that might occur during send
            IO(logger.error(s"Unexpected error sending UDP packet to $remoteAddress: ${ex.getClass.getSimpleName}: ${ex.getMessage}", ex)) >>
            IO.raiseError(ex)
        }
      } yield ()

    def handleMessage(maybeMessage: Attempt[M]): IO[Unit] = {
      isClosedRef.get.ifM(
        IO.unit,
        maybeMessage match {
          case Attempt.Successful(message) =>
            publish(MessageReceived(message))
          case Attempt.Failure(err) =>
            publish(DecodingError)
        }
      )
    }

    def handleError(error: Throwable): IO[Unit] =
      isClosedRef.get.ifM(
        IO.unit,
        publish(UnexpectedError(error))
      )

    private def close() =
      for {
        _ <- raiseIfClosed
        _ <- isClosedRef.set(true)
        // Initiated by the consumer, so discard messages.
        _ <- messageQueue.close(discard = true)
      } yield ()

    private def publish(event: ChannelEvent[M]): IO[Unit] =
      messageQueue.tryOffer(event).void
  }

  private object ChannelImpl {
    sealed trait Role {
      override def toString(): String = this match {
        case Server => "server"
        case Client => "client"
      }
    }
    object Server extends Role
    object Client extends Role

    def apply[M: Codec](
        nettyChannel: io.netty.channel.Channel,
        localAddress: InetSocketAddress,
        remoteAddress: InetSocketAddress,
        role: Role,
        capacity: Int
    ): Resource[IO, ChannelImpl[M]] =
      Resource.make {
        for {
          isClosedRef <- Ref[IO].of(false)
          // The publishing of messages happens asynchronously in this class,
          // so there can be multiple publications going on at the same time.
          messageQueue <- CloseableQueue[ChannelEvent[M]](capacity)
          channel = new ChannelImpl[M](
            nettyChannel,
            localAddress,
            remoteAddress,
            messageQueue,
            isClosedRef,
            role
          )
        } yield channel
      }(_.close())
  }
}
