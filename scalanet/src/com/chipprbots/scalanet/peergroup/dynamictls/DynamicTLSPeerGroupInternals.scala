package com.chipprbots.scalanet.peergroup.dynamictls

import java.io.IOException
import java.net.{ConnectException, InetSocketAddress}
import java.nio.channels.ClosedChannelException
import cats.effect.unsafe.implicits.global // For unsafeRunSync
import com.typesafe.scalalogging.StrictLogging
import com.chipprbots.scalanet.peergroup.Channel.{
  AllIdle,
  ChannelEvent,
  ChannelIdle,
  DecodingError,
  MessageReceived,
  ReaderIdle,
  UnexpectedError,
  WriterIdle
}
import com.chipprbots.scalanet.peergroup.NettyFutureUtils.toTask
import com.chipprbots.scalanet.peergroup.PeerGroup.ProxySupport.Socks5Config
import com.chipprbots.scalanet.peergroup.PeerGroup.ServerEvent.ChannelCreated
import com.chipprbots.scalanet.peergroup.PeerGroup.{ChannelBrokenException, HandshakeException, ServerEvent}
import com.chipprbots.scalanet.peergroup.dynamictls.CustomHandlers.ThrottlingIpFilter
import com.chipprbots.scalanet.peergroup.dynamictls.DynamicTLSPeerGroup.{FramingConfig, PeerInfo, StalePeerDetectionConfig}
import com.chipprbots.scalanet.peergroup.{Channel, CloseableQueue, InetMultiAddress, PeerGroup}
import io.netty.bootstrap.Bootstrap
import io.netty.buffer.{ByteBuf, Unpooled}
import io.netty.channel.socket.SocketChannel
import io.netty.channel.{
  ChannelConfig,
  ChannelHandlerContext,
  ChannelInboundHandlerAdapter,
  ChannelInitializer,
  EventLoop
}
import io.netty.handler.ssl.{SslContext, SslHandshakeCompletionEvent}

import javax.net.ssl.{SSLException, SSLHandshakeException, SSLKeyException}
import cats.effect.IO
import scodec.bits.BitVector

import scala.concurrent.Promise
import scala.util.control.NonFatal
import io.netty.handler.codec.{LengthFieldBasedFrameDecoder, LengthFieldPrepender, TooLongFrameException}
import io.netty.handler.proxy.Socks5ProxyHandler
import io.netty.handler.timeout.{IdleState, IdleStateEvent, IdleStateHandler}
import scodec.Attempt.{Failure, Successful}
import scodec.Codec

import java.util.concurrent.TimeUnit

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
      eventLoop: EventLoop
  ) extends ChannelInboundHandlerAdapter
      with StrictLogging {

    private def idleEventToChannelEvent(idleStateEvent: IdleStateEvent): ChannelIdle = {
      idleStateEvent.state() match {
        case IdleState.READER_IDLE => ChannelIdle(ReaderIdle, idleStateEvent.isFirst)
        case IdleState.WRITER_IDLE => ChannelIdle(WriterIdle, idleStateEvent.isFirst)
        case IdleState.ALL_IDLE => ChannelIdle(AllIdle, idleStateEvent.isFirst)
      }
    }

    // Execution should preserve message ordering
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
      task.unsafeRunAndForget()
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
    val sslHandler = sslServerCtx.newHandler(nettyChannel.alloc())

    val messageQueue = makeMessageQueue[M](maxIncomingQueueSize, nettyChannel.config())
    val sslEngine = sslHandler.engine()

    val pipeline = nettyChannel.pipeline()

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
      serverQueue.offer(event).void.unsafeRunSync()(global)
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
