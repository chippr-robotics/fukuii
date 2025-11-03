package com.chipprbots.scalanet.peergroup.dynamictls

import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.nio.channels.ClosedChannelException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLKeyException

import cats.effect.IO
import cats.effect.unsafe.implicits.global

import scala.concurrent.Promise
import scala.util.control.NonFatal

import com.chipprbots.scalanet.peergroup.Channel
import com.chipprbots.scalanet.peergroup.Channel.AllIdle
import com.chipprbots.scalanet.peergroup.Channel.ChannelEvent
import com.chipprbots.scalanet.peergroup.Channel.ChannelIdle
import com.chipprbots.scalanet.peergroup.Channel.DecodingError
import com.chipprbots.scalanet.peergroup.Channel.MessageReceived
import com.chipprbots.scalanet.peergroup.Channel.ReaderIdle
import com.chipprbots.scalanet.peergroup.Channel.UnexpectedError
import com.chipprbots.scalanet.peergroup.Channel.WriterIdle
import com.chipprbots.scalanet.peergroup.CloseableQueue
import com.chipprbots.scalanet.peergroup.InetMultiAddress
import com.chipprbots.scalanet.peergroup.NettyFutureUtils.toTask
import com.chipprbots.scalanet.peergroup.PeerGroup
import com.chipprbots.scalanet.peergroup.PeerGroup.ChannelBrokenException
import com.chipprbots.scalanet.peergroup.PeerGroup.HandshakeException
import com.chipprbots.scalanet.peergroup.PeerGroup.ProxySupport.Socks5Config
import com.chipprbots.scalanet.peergroup.PeerGroup.ServerEvent
import com.chipprbots.scalanet.peergroup.PeerGroup.ServerEvent.ChannelCreated
import com.chipprbots.scalanet.peergroup.dynamictls.CustomHandlers.ThrottlingIpFilter
import com.chipprbots.scalanet.peergroup.dynamictls.DynamicTLSPeerGroup.FramingConfig
import com.chipprbots.scalanet.peergroup.dynamictls.DynamicTLSPeerGroup.PeerInfo
import com.chipprbots.scalanet.peergroup.dynamictls.DynamicTLSPeerGroup.StalePeerDetectionConfig
import com.typesafe.scalalogging.StrictLogging
import io.netty.bootstrap.Bootstrap
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelConfig
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.ChannelInitializer
import io.netty.channel.ChannelPipeline
import io.netty.channel.EventLoop
import io.netty.channel.socket.SocketChannel
import io.netty.handler.codec.LengthFieldBasedFrameDecoder
import io.netty.handler.codec.LengthFieldPrepender
import io.netty.handler.codec.TooLongFrameException
import io.netty.handler.proxy.Socks5ProxyHandler
import io.netty.handler.ssl.SslContext
import io.netty.handler.ssl.SslHandler
import io.netty.handler.ssl.SslHandshakeCompletionEvent
import io.netty.handler.timeout.IdleState
import io.netty.handler.timeout.IdleStateEvent
import io.netty.handler.timeout.IdleStateHandler
import scodec.Attempt.Failure
import scodec.Attempt.Successful
import scodec.Codec
import scodec.bits.BitVector

private[peergroup] object DynamicTLSPeerGroupInternals {
  def buildFramingCodecs(config: FramingConfig): (LengthFieldBasedFrameDecoder, LengthFieldPrepender) = {
    val encoder = new LengthFieldPrepender(
      config.byteOrder,
      config.lengthFieldLength.value,
      config.encodingLengthAdjustment,
      config.lengthIncludesLengthFieldLength
    )

    val decoder = new LengthFieldBasedFrameDecoder(
      config.byteOrder,
      config.maxFrameLength,
      config.lengthFieldOffset,
      config.lengthFieldLength.value,
      config.decodingLengthAdjustment,
      config.initialBytesToStrip,
      config.failFast
    )

    (decoder, encoder)
  }

  def buildIdlePeerHandler(config: StalePeerDetectionConfig): IdleStateHandler = {
    new IdleStateHandler(
      config.readerIdleTime.toMillis,
      config.writerIdleTime.toMillis,
      config.allIdleTime.toMillis,
      TimeUnit.MILLISECONDS
    )
  }

  implicit class ChannelOps(val channel: io.netty.channel.Channel) {
    def sendMessage[M](m: M)(implicit codec: Codec[M]): IO[Unit] =
      for {
        enc <- IO.fromTry(codec.encode(m).toTry)
        _ <- toTask(channel.writeAndFlush(Unpooled.wrappedBuffer(enc.toByteBuffer)))
      } yield ()
  }

  class MessageNotifier[M](
      messageQueue: ChannelAwareQueue[ChannelEvent[M]],
      codec: Codec[M],
      @annotation.unused eventLoop: EventLoop
  ) extends ChannelInboundHandlerAdapter
      with StrictLogging {

    private def idleEventToChannelEvent(idleStateEvent: IdleStateEvent): ChannelIdle = {
      idleStateEvent.state() match {
        case IdleState.READER_IDLE => ChannelIdle(ReaderIdle, idleStateEvent.isFirst)
        case IdleState.WRITER_IDLE => ChannelIdle(WriterIdle, idleStateEvent.isFirst)
        case IdleState.ALL_IDLE => ChannelIdle(AllIdle, idleStateEvent.isFirst)
      }
    }

    // Message ordering guarantee: Netty invokes handler methods sequentially on the same
    // event loop thread for a given channel. However, using unsafeRunAndForget with the
    // global execution context means the IO execution order is not guaranteed - thread
    // scheduling determines execution order. Ordering is only preserved at the Netty
    // handler invocation level, not at the IO execution level. If strict ordering of
    // IO execution is required, a single-threaded execution context should be used instead.
    import cats.effect.unsafe.implicits.global

    override def channelInactive(channelHandlerContext: ChannelHandlerContext): Unit = {
      logger.debug("Channel to peer {} inactive", channelHandlerContext.channel().remoteAddress())
      executeAsync(messageQueue.close(discard = false))
    }

    override def channelRead(ctx: ChannelHandlerContext, msg: Any): Unit = {
      val byteBuf = msg.asInstanceOf[ByteBuf]
      try {
        codec.decodeValue(BitVector(byteBuf.nioBuffer())) match {
          case Successful(message) =>
            handleEvent(MessageReceived(message))
          case Failure(ex) =>
            logger.error("Unexpected decoding error {} from peer {}", ex.message, ctx.channel().remoteAddress(): Any)
            handleEvent(DecodingError)
        }
      } catch {
        case NonFatal(e) =>
          handleEvent(UnexpectedError(e))
      } finally {
        byteBuf.release()
        ()
      }
    }

    override def exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable): Unit = {
      cause match {
        case e: TooLongFrameException =>
          logger.error("Too long frame {} on channel to peer {}", e.getMessage, ctx.channel().remoteAddress())
          handleEvent(DecodingError)
        case e =>
          // swallow netty's default logging of the stack trace.
          logger.error(
            "Unexpected exception {} on channel to peer {}",
            cause.getMessage: Any,
            ctx.channel().remoteAddress(): Any
          )
          handleEvent(UnexpectedError(cause))
      }
    }

    override def userEventTriggered(ctx: ChannelHandlerContext, evt: Any): Unit = {
      evt match {
        case idleStateEvent: IdleStateEvent =>
          val channelIdleEvent = idleEventToChannelEvent(idleStateEvent)
          logger.debug("Peer with address {} generated idle event {}", ctx.channel().remoteAddress(), channelIdleEvent)
          handleEvent(channelIdleEvent)
      }
    }

    private def handleEvent(event: ChannelEvent[M]): Unit =
      // Don't want to lose message, so `offer`, not `tryOffer`.
      executeAsync(messageQueue.offer(event).void)

    private def executeAsync(task: IO[Unit]): Unit =
      task.unsafeRunAndForget()(global)
  }

  object MessageNotifier {
    val MessageNotifiedHandlerName = "MessageNotifier"
  }

  class ClientChannelBuilder[M](
      localId: BitVector,
      peerInfo: PeerInfo,
      clientBootstrap: Bootstrap,
      sslClientCtx: SslContext,
      framingConfig: DynamicTLSPeerGroup.FramingConfig,
      maxIncomingQueueSize: Int,
      socks5Config: Option[Socks5Config],
      idlePeerConfig: Option[StalePeerDetectionConfig]
  )(implicit codec: Codec[M])
      extends StrictLogging {
    val (decoder, encoder) = buildFramingCodecs(framingConfig)
    private val to = peerInfo
    private val activation = Promise[(SocketChannel, ChannelAwareQueue[ChannelEvent[M]])]()
    private val activationF = activation.future
    private val bootstrap: Bootstrap = clientBootstrap
      .clone()
      .handler(new ChannelInitializer[SocketChannel]() {
        def initChannel(ch: SocketChannel): Unit = {
          logger.debug("Initiating connection to peer {}", peerInfo)
          val pipeline = ch.pipeline()
          val sslHandler = sslClientCtx.newHandler(ch.alloc())
          val messageQueue = makeMessageQueue[M](maxIncomingQueueSize, ch.config())

          socks5Config.foreach { config =>
            val sock5Proxy = config.authConfig.fold(new Socks5ProxyHandler(config.proxyAddress)) { authConfig =>
              new Socks5ProxyHandler(config.proxyAddress, authConfig.user, authConfig.password)
            }
            pipeline.addLast(sock5Proxy)
          }

          pipeline
            .addLast("ssl", sslHandler) //This needs to be first
            .addLast(new ChannelInboundHandlerAdapter() {
              override def userEventTriggered(ctx: ChannelHandlerContext, evt: Any): Unit = {
                evt match {
                  case e: SslHandshakeCompletionEvent =>
                    logger.info(
                      s"Ssl Handshake client channel from ${ctx.channel().localAddress()} " +
                        s"to ${ctx.channel().remoteAddress()} with channel id ${ctx.channel().id} and ssl status ${e.isSuccess}"
                    )
                    if (e.isSuccess) {
                      logger.debug("Handshake to peer {} succeeded", peerInfo)

                      // idle peer handler is installed only after successful tls handshake so that only time after connection
                      // is counted to idle time counter (not time of the handshake itself)
                      idlePeerConfig.foreach(
                        config =>
                          pipeline.addBefore(
                            MessageNotifier.MessageNotifiedHandlerName,
                            "IdlePeerHandler",
                            buildIdlePeerHandler(config)
                          )
                      )

                      activation.success((ch, messageQueue))
                    } else {
                      logger.debug("Handshake to peer {} failed due to {}", peerInfo, e: Any)
                      activation.failure(e.cause())
                    }

                  case ev =>
                    logger.debug(
                      s"User Event $ev on client channel from ${ctx.channel().localAddress()} " +
                        s"to ${ctx.channel().remoteAddress()} with channel id ${ctx.channel().id}"
                    )
                }
              }
            })
            .addLast(encoder)
            .addLast(decoder)
            .addLast(
              MessageNotifier.MessageNotifiedHandlerName,
              new MessageNotifier[M](messageQueue, codec, ch.eventLoop)
            )
          ()
        }
      })

    private[dynamictls] def initialize = {
      val connectIO = for {
        _ <- IO(logger.debug("Initiating connection to peer {}", peerInfo))
        _ <- toTask(bootstrap.connect(peerInfo.address.inetSocketAddress))
        ch <- IO.fromFuture(IO.pure(activationF))
        _ <- IO(logger.debug("Connection to peer {} finished successfully", peerInfo))
      } yield new DynamicTlsChannel[M](localId, peerInfo, ch._1, ch._2, ClientChannel)

      connectIO.handleErrorWith {
        case t: Throwable =>
          IO.raiseError(mapException(t))
      }
    }

    private def mapException(t: Throwable): Throwable = t match {
      case _: ClosedChannelException =>
        new PeerGroup.ChannelBrokenException(to, t)
      case _: ConnectException =>
        new PeerGroup.ChannelSetupException(to, t)
      case _: SSLKeyException =>
        new PeerGroup.HandshakeException(to, t)
      case _: SSLHandshakeException =>
        new PeerGroup.HandshakeException(to, t)
      case _: SSLException =>
        new PeerGroup.HandshakeException(to, t)
      case _ =>
        t
    }
  }

  class ServerChannelBuilder[M](
      localId: BitVector,
      serverQueue: CloseableQueue[ServerEvent[PeerInfo, M]],
      val nettyChannel: SocketChannel,
      sslServerCtx: SslContext,
      framingConfig: DynamicTLSPeerGroup.FramingConfig,
      maxIncomingQueueSize: Int,
      throttlingIpFilter: Option[ThrottlingIpFilter],
      idlePeerConfig: Option[StalePeerDetectionConfig]
  )(implicit codec: Codec[M])
      extends StrictLogging {
    val sslHandler: SslHandler = sslServerCtx.newHandler(nettyChannel.alloc())

    val messageQueue: ChannelAwareQueue[ChannelEvent[M]] = makeMessageQueue[M](maxIncomingQueueSize, nettyChannel.config())
    val sslEngine: SSLEngine = sslHandler.engine()

    val pipeline: ChannelPipeline = nettyChannel.pipeline()

    val (decoder, encoder) = buildFramingCodecs(framingConfig)

    // adding throttling filter as first (if configures), so if its connection from address which breaks throttling rules
    // it will be closed immediately without using more resources
    throttlingIpFilter.foreach(filter => pipeline.addLast(filter))
    pipeline
      .addLast("ssl", sslHandler) //This needs to be first
      .addLast(new ChannelInboundHandlerAdapter() {
        override def userEventTriggered(ctx: ChannelHandlerContext, evt: Any): Unit = {
          evt match {
            case e: SslHandshakeCompletionEvent =>
              val localAddress = InetMultiAddress(ctx.channel().localAddress().asInstanceOf[InetSocketAddress])
              val remoteAddress = InetMultiAddress(ctx.channel().remoteAddress().asInstanceOf[InetSocketAddress])
              if (e.isSuccess) {
                // after handshake handshake session becomes session, so during handshake sslEngine.getHandshakeSession needs
                // to be called to put value in session, but after handshake sslEngine.getSession needs to be called
                // get the same session with value
                val peerId = sslEngine.getSession.getValue(DynamicTLSPeerGroupUtils.peerIdKey).asInstanceOf[BitVector]
                logger.debug(
                  s"Ssl Handshake server channel from $localAddress " +
                    s"to $remoteAddress with channel id ${ctx.channel().id} and ssl status ${e.isSuccess}"
                )

                // idle peer handler is installed only after successful tls handshake so that only time after connection
                // is counted to idle time counter (not time of the handshake itself)
                idlePeerConfig.foreach(
                  config =>
                    pipeline.addBefore(
                      MessageNotifier.MessageNotifiedHandlerName,
                      "IdlePeerHandler",
                      buildIdlePeerHandler(config)
                    )
                )

                val info = PeerInfo(peerId, InetMultiAddress(nettyChannel.remoteAddress()))
                val channel = new DynamicTlsChannel[M](localId, info, nettyChannel, messageQueue, ServerChannel)
                handleEvent(ChannelCreated(channel, channel.close()))
              } else {
                logger.debug("Ssl handshake failed from peer with address {}", remoteAddress)
                // Handshake failed we do not have id of remote peer
                handleEvent(
                  PeerGroup.ServerEvent
                    .HandshakeFailed(new HandshakeException(PeerInfo(BitVector.empty, remoteAddress), e.cause()))
                )
              }
            case ev =>
              logger.debug(
                s"User Event $ev on server channel from ${ctx.channel().localAddress()} " +
                  s"to ${ctx.channel().remoteAddress()} with channel id ${ctx.channel().id}"
              )
          }
        }
      })
      .addLast(encoder)
      .addLast(decoder)
      .addLast(
        MessageNotifier.MessageNotifiedHandlerName,
        new MessageNotifier(messageQueue, codec, nettyChannel.eventLoop)
      )

    private def handleEvent(event: ServerEvent[PeerInfo, M]): Unit =
      serverQueue.offer(event).void.unsafeRunAndForget()(global)
  }

  class DynamicTlsChannel[M](
      localId: BitVector,
      val to: PeerInfo,
      nettyChannel: SocketChannel,
      incomingMessagesQueue: ChannelAwareQueue[ChannelEvent[M]],
      channelType: TlsChannelType
  )(implicit codec: Codec[M])
      extends Channel[PeerInfo, M]
      with StrictLogging {

    logger.debug(
      s"Creating $channelType from ${nettyChannel.localAddress()} to ${nettyChannel.remoteAddress()} with channel id ${nettyChannel.id}"
    )

    override val from: PeerInfo = PeerInfo(localId, InetMultiAddress(nettyChannel.localAddress()))

    override def sendMessage(message: M): IO[Unit] = {
      logger.debug("Sending message to peer {} via {}", nettyChannel.localAddress(), channelType)
      nettyChannel.sendMessage(message)(codec).handleErrorWith {
        case e: IOException =>
          logger.debug("Sending message to {} failed due to {}", message, e)
          IO.raiseError(new ChannelBrokenException[PeerInfo](to, e))
      }
    }

    override def nextChannelEvent: IO[Option[ChannelEvent[M]]] = incomingMessagesQueue.next

    private[peergroup] def incomingQueueSize: Long = incomingMessagesQueue.size

    /**
      * To be sure that `channelInactive` had run before returning from close, we are also waiting for nettyChannel.closeFuture() after
      * nettyChannel.close()
      */
    private[peergroup] def close(): IO[Unit] =
      for {
        _ <- IO(logger.debug("Closing {} to peer {}", channelType, to))
        _ <- toTask(nettyChannel.close())
        _ <- toTask(nettyChannel.closeFuture())
        _ <- incomingMessagesQueue.close(discard = true).attempt
        _ <- IO(logger.debug("{} to peer {} closed", channelType, to))
      } yield ()
  }

  private def makeMessageQueue[M](limit: Int, channelConfig: ChannelConfig) = {
    ChannelAwareQueue[ChannelEvent[M]](limit, channelConfig).unsafeRunSync()(global)
  }

  sealed abstract class TlsChannelType
  case object ClientChannel extends TlsChannelType {
    override def toString: String = "tls client channel"
  }
  case object ServerChannel extends TlsChannelType {
    override def toString: String = "tls server channel"
  }

}
