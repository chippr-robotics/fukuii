package com.chipprbots.ethereum.network.rlpx

import java.net.InetSocketAddress
import java.net.URI

import org.apache.pekko.actor._
import org.apache.pekko.io.IO
import org.apache.pekko.io.Tcp
import org.apache.pekko.io.Tcp._
import org.apache.pekko.util.ByteString

import scala.collection.immutable.Queue
import scala.concurrent.duration._
import scala.util.Failure
import scala.util.Success
import scala.util.Try

import org.bouncycastle.util.encoders.Hex

import com.chipprbots.ethereum.network.p2p.EthereumMessageDecoder
import com.chipprbots.ethereum.network.p2p.Message
import com.chipprbots.ethereum.network.p2p.MessageDecoder._
import com.chipprbots.ethereum.network.p2p.MessageSerializable
import com.chipprbots.ethereum.network.p2p.NetworkMessageDecoder
import com.chipprbots.ethereum.network.p2p.messages.Capability
import com.chipprbots.ethereum.network.p2p.messages.WireProtocol.Hello
import com.chipprbots.ethereum.network.p2p.messages.WireProtocol.Hello.HelloEnc
import com.chipprbots.ethereum.network.rlpx.RLPxConnectionHandler.HelloCodec
import com.chipprbots.ethereum.network.rlpx.RLPxConnectionHandler.RLPxConfiguration

import com.chipprbots.ethereum.utils.ByteUtils

/** This actors takes care of initiating a secure connection (auth handshake) between peers. Once such connection is
  * established it allows to send/receive frames (messages) over it.
  *
  * The actor can be in one of four states:
  *   1. when created it waits for initial command (either handle incoming connection or connect using uri) 2. when new
  *      connection is requested the actor waits for the result (waitingForConnectionResult) 3. once underlying
  *      connection is established it either waits for handshake init message or for response message (depending on who
  *      initiated the connection) 4. once handshake is done (and secure connection established) actor can send/receive
  *      messages (`handshaked` state)
  */
class RLPxConnectionHandler(
    capabilities: List[Capability],
    authHandshaker: AuthHandshaker,
    messageCodecFactory: (FrameCodec, Capability, Long) => MessageCodec,
    rlpxConfiguration: RLPxConfiguration,
    extractor: Secrets => HelloCodec
) extends Actor
    with ActorLogging {

  import AuthHandshaker.{InitiatePacketLength, ResponsePacketLength}
  import RLPxConnectionHandler._
  import context.{dispatcher, system}

  val peerId: String = context.parent.path.name

  override def receive: Receive = waitingForCommand

  override def postStop(): Unit = {
    log.debug("[RLPx] Connection handler for peer {} stopped", peerId)
    super.postStop()
  }

  def tcpActor: ActorRef = IO(Tcp)

  /** State to handle graceful shutdown, preventing dead letters by accepting and dropping messages */
  def stopping: Receive = {
    case _: SendMessage =>
      log.debug("[RLPx] Ignoring SendMessage during shutdown for peer {}", peerId)
    case msg =>
      log.debug("[RLPx] Ignoring message {} during shutdown for peer {}", msg.getClass.getSimpleName, peerId)
  }

  /** Transition to stopping state before terminating the actor to prevent dead letters */
  private def gracefulStop(): Unit = {
    context.become(stopping)
    self ! PoisonPill
  }

  def waitingForCommand: Receive = {
    case ConnectTo(uri) =>
      log.debug("[RLPx] Initiating connection to peer {} at {}", peerId, uri)
      tcpActor ! Connect(new InetSocketAddress(uri.getHost, uri.getPort))
      context.become(waitingForConnectionResult(uri))

    case HandleConnection(connection) =>
      log.debug("[RLPx] Handling incoming connection for peer {}", peerId)
      context.watch(connection)
      connection ! Register(self)
      val timeout = system.scheduler.scheduleOnce(rlpxConfiguration.waitForHandshakeTimeout, self, AuthHandshakeTimeout)
      context.become(new ConnectedHandler(connection).waitingForAuthHandshakeInit(authHandshaker, timeout))
  }

  def waitingForConnectionResult(uri: URI): Receive = {
    case Connected(_, _) =>
      log.debug("[RLPx] TCP connection established for peer {}, starting auth handshake", peerId)
      val connection = sender()
      context.watch(connection)
      connection ! Register(self)
      val (initPacket, handshaker) = authHandshaker.initiate(uri)
      connection ! Write(initPacket, Ack)
      val timeout = system.scheduler.scheduleOnce(rlpxConfiguration.waitForHandshakeTimeout, self, AuthHandshakeTimeout)
      context.become(new ConnectedHandler(connection).waitingForAuthHandshakeResponse(handshaker, timeout))

    case CommandFailed(_: Connect) =>
      log.error("[Stopping Connection] TCP connection to {} failed for peer {}", uri, peerId)
      context.parent ! ConnectionFailed
      gracefulStop()
  }

  class ConnectedHandler(connection: ActorRef) {

    val handleConnectionTerminated: Receive = { case Terminated(`connection`) =>
      log.debug("[Stopping Connection] TCP connection actor terminated for peer {}", peerId)
      context.parent ! ConnectionFailed
      gracefulStop()
    }

    def waitingForAuthHandshakeInit(handshaker: AuthHandshaker, timeout: Cancellable): Receive =
      handleConnectionTerminated.orElse(handleWriteFailed).orElse(handleTimeout).orElse(handleConnectionClosed).orElse {
        case Received(data) =>
          log.debug("[RLPx] Received auth handshake init message for peer {} ({} bytes)", peerId, data.length)
          timeout.cancel()
          // FIXME EIP8 is 6 years old, time to drop it
          val maybePreEIP8Result = Try {
            val (responsePacket, result) = handshaker.handleInitialMessage(data.take(InitiatePacketLength))
            val remainingData = data.drop(InitiatePacketLength)
            (responsePacket, result, remainingData)
          }
          lazy val maybePostEIP8Result = Try {
            val (packetData, remainingData) = decodeV4Packet(data)
            val (responsePacket, result) = handshaker.handleInitialMessageV4(packetData)
            (responsePacket, result, remainingData)
          }

          maybePreEIP8Result.orElse(maybePostEIP8Result) match {
            case Success((responsePacket, result, remainingData)) =>
              log.debug("[RLPx] Auth handshake init processed for peer {}, sending response", peerId)
              connection ! Write(responsePacket, Ack)
              processHandshakeResult(result, remainingData)

            case Failure(ex) =>
              log.error(
                "[Stopping Connection] Init AuthHandshaker message handling failed for peer {}",
                peerId,
                ex
              )
              context.parent ! ConnectionFailed
              gracefulStop()
          }
      }

    def waitingForAuthHandshakeResponse(handshaker: AuthHandshaker, timeout: Cancellable): Receive =
      handleConnectionTerminated.orElse(handleWriteFailed).orElse(handleTimeout).orElse(handleConnectionClosed).orElse {
        case Ack =>
          log.debug("[RLPx] Auth init packet write acknowledged for peer {}", peerId)
          // Init packet write succeeded, continue waiting for response
          ()

        case Received(data) =>
          log.debug("[RLPx] Received auth handshake response for peer {} ({} bytes)", peerId, data.length)
          timeout.cancel()
          val maybePreEIP8Result = Try {
            val result = handshaker.handleResponseMessage(data.take(ResponsePacketLength))
            val remainingData = data.drop(ResponsePacketLength)
            (result, remainingData)
          }
          val maybePostEIP8Result = Try {
            val (packetData, remainingData) = decodeV4Packet(data)
            val result = handshaker.handleResponseMessageV4(packetData)
            (result, remainingData)
          }
          maybePreEIP8Result.orElse(maybePostEIP8Result) match {
            case Success((result, remainingData)) =>
              log.debug("[RLPx] Auth handshake response processed for peer {}", peerId)
              processHandshakeResult(result, remainingData)

            case Failure(ex) =>
              log.error(
                "[Stopping Connection] Response AuthHandshaker message handling failed for peer {}",
                peerId,
                ex
              )
              context.parent ! ConnectionFailed
              gracefulStop()
          }
      }

    /** Decode V4 packet
      *
      * @param data
      *   , includes both the V4 packet with bytes from next messages
      * @return
      *   data of the packet and the remaining data
      */
    private def decodeV4Packet(data: ByteString): (ByteString, ByteString) = {
      val encryptedPayloadSize = ByteUtils.bigEndianToShort(data.take(2).toArray)
      val (packetData, remainingData) = data.splitAt(encryptedPayloadSize + 2)
      packetData -> remainingData
    }

    def handleTimeout: Receive = { case AuthHandshakeTimeout =>
      log.error(
        "[Stopping Connection] Auth handshake timeout for peer {} after {}ms",
        peerId,
        rlpxConfiguration.waitForHandshakeTimeout.toMillis
      )
      context.parent ! ConnectionFailed
      gracefulStop()
    }

    def processHandshakeResult(result: AuthHandshakeResult, remainingData: ByteString): Unit =
      result match {
        case AuthHandshakeSuccess(secrets, remotePubKey) =>
          log.info("[RLPx] Auth handshake SUCCESS for peer {}, establishing secure connection", peerId)
          context.parent ! ConnectionEstablished(remotePubKey)
          // following the specification at https://github.com/ethereum/devp2p/blob/master/rlpx.md#initial-handshake
          // point 6 indicates that the next messages needs to be initial 'Hello'
          // Unfortunately it is hard to figure out the proper order for messages to be handled in.
          // FrameCodec assumes that bytes will arrive in the expected order
          // To alleviate potential lapses in order each chunk of data needs to be passed to FrameCodec immediately
          extractHello(extractor(secrets), remainingData)

        case AuthHandshakeError =>
          log.error("[Stopping Connection] Auth handshake FAILED for peer {}", peerId)
          context.parent ! ConnectionFailed
          gracefulStop()
      }

    def awaitInitialHello(
        extractor: HelloCodec,
        cancellableAckTimeout: Option[CancellableAckTimeout] = None,
        seqNumber: Int = 0
    ): Receive =
      handleConnectionTerminated.orElse(handleWriteFailed).orElse(handleConnectionClosed).orElse {
        // TODO when cancellableAckTimeout is Some
        case SendMessage(h: HelloEnc) =>
          val out = extractor.writeHello(h)
          connection ! Write(out, Ack)
          val timeout =
            system.scheduler.scheduleOnce(rlpxConfiguration.waitForTcpAckTimeout, self, AckTimeout(seqNumber))
          context.become(
            awaitInitialHello(
              extractor,
              Some(CancellableAckTimeout(seqNumber, timeout)),
              increaseSeqNumber(seqNumber)
            )
          )
        case Ack if cancellableAckTimeout.nonEmpty =>
          // Cancel pending message timeout
          cancellableAckTimeout.foreach(_.cancellable.cancel())
          context.become(awaitInitialHello(extractor, None, seqNumber))

        case Ack =>
          // Ack for auth handshake response packet write, no timeout to cancel
          ()

        case AckTimeout(ackSeqNumber) if cancellableAckTimeout.exists(_.seqNumber == ackSeqNumber) =>
          cancellableAckTimeout.foreach(_.cancellable.cancel())
          log.error("[Stopping Connection] Sending 'Hello' to {} failed", peerId)
          gracefulStop()
        case Received(data) =>
          extractHello(extractor, data, cancellableAckTimeout, seqNumber)
      }

    private def extractHello(
        extractor: HelloCodec,
        data: ByteString,
        cancellableAckTimeout: Option[CancellableAckTimeout] = None,
        seqNumber: Int = 0
    ): Unit =
      extractor.readHello(data) match {
        case Some((hello, restFrames)) =>
          log.debug(
            "[RLPx] Extracted Hello message from peer {}, protocol version: {}, capabilities: {}",
            peerId,
            hello.p2pVersion,
            hello.capabilities.mkString(", ")
          )
          val messageCodecOpt = for {
            opt <- negotiateCodec(hello, extractor)
            (messageCodec, negotiated) = opt
            _ = log.debug("[RLPx] Protocol negotiated with peer {}: {}", peerId, negotiated)
            _ = context.parent ! InitialHelloReceived(hello, negotiated)
            _ = processFrames(restFrames, messageCodec)
          } yield messageCodec
          messageCodecOpt match {
            case Some(messageCodec) =>
              log.info("[RLPx] Connection FULLY ESTABLISHED with peer {}, entering handshaked state", peerId)
              context.become(
                handshaked(
                  messageCodec,
                  cancellableAckTimeout = cancellableAckTimeout,
                  seqNumber = seqNumber
                )
              )
            case None =>
              log.error("[Stopping Connection] Unable to negotiate protocol with peer {}", peerId)
              context.parent ! ConnectionFailed
              gracefulStop()
          }
        case None =>
          log.warning("[RLPx] Did not find 'Hello' in message from peer {}, continuing to await", peerId)
          context.become(awaitInitialHello(extractor, cancellableAckTimeout, seqNumber))
      }

    private def negotiateCodec(hello: Hello, extractor: HelloCodec): Option[(MessageCodec, Capability)] =
      Capability.negotiate(hello.capabilities.toList, capabilities).map { negotiated =>
        (messageCodecFactory(extractor.frameCodec, negotiated, hello.p2pVersion), negotiated)
      }

    private def processFrames(frames: Seq[Frame], messageCodec: MessageCodec): Unit =
      if (frames.nonEmpty) {
        val messagesSoFar = messageCodec.readFrames(frames) // omit hello
        messagesSoFar.foreach(processMessage)
      }

    def processMessage(messageTry: Either[DecodingError, Message]): Unit = messageTry match {
      case Right(message) =>
        context.parent ! MessageReceived(message)

      case Left(ex) =>
        val errorMsg = Option(ex.getMessage).getOrElse(ex.toString)
        log.error("Cannot decode message from {}, because of {}", peerId, errorMsg)
        // Enhanced debugging for decompression failures
        if (errorMsg.contains("FAILED_TO_UNCOMPRESS")) {
          log.error(
            "DECODE_ERROR_DEBUG: Peer {} failed message decode - connection will be closed. Error details: {}",
            peerId,
            errorMsg
          )
        }
        // break connection in case of failed decoding, to avoid attack which would send us garbage
        connection ! Close
      // Let handleConnectionTerminated clean up after TCP connection closes
    }

    /** Handles sending and receiving messages from the Akka TCP connection, while also handling acknowledgement of
      * messages sent. Messages are only sent when all Ack from previous messages were received.
      *
      * @param messageCodec
      *   , for encoding the messages sent
      * @param messagesNotSent
      *   , messages not yet sent
      * @param cancellableAckTimeout
      *   , timeout for the message sent for which we are awaiting an acknowledgement (if there is one)
      * @param seqNumber
      *   , sequence number for the next message to be sent
      */
    def handshaked(
        messageCodec: MessageCodec,
        messagesNotSent: Queue[MessageSerializable] = Queue.empty,
        cancellableAckTimeout: Option[CancellableAckTimeout] = None,
        seqNumber: Int = 0
    ): Receive =
      handleConnectionTerminated.orElse(handleWriteFailed).orElse(handleConnectionClosed).orElse {
        case sm: SendMessage =>
          if (cancellableAckTimeout.isEmpty)
            sendMessage(messageCodec, sm.serializable, seqNumber, messagesNotSent)
          else
            context.become(
              handshaked(
                messageCodec,
                messagesNotSent :+ sm.serializable,
                cancellableAckTimeout,
                seqNumber
              )
            )

        case Received(data) =>
          val messages = messageCodec.readMessages(data)
          messages.foreach(processMessage)

        case Ack if cancellableAckTimeout.nonEmpty =>
          // Cancel pending message timeout
          cancellableAckTimeout.foreach(_.cancellable.cancel())

          // Send next message if there is one
          if (messagesNotSent.nonEmpty)
            sendMessage(messageCodec, messagesNotSent.head, seqNumber, messagesNotSent.tail)
          else
            context.become(handshaked(messageCodec, Queue.empty, None, seqNumber))

        case AckTimeout(ackSeqNumber) if cancellableAckTimeout.exists(_.seqNumber == ackSeqNumber) =>
          cancellableAckTimeout.foreach(_.cancellable.cancel())
          log.debug("[Stopping Connection] Write to {} failed", peerId)
          gracefulStop()
      }

    /** Sends an encoded message through the TCP connection, an Ack will be received when the message was successfully
      * queued for delivery. A cancellable timeout is created for the Ack message.
      *
      * @param messageCodec
      *   , for encoding the messages sent
      * @param messageToSend
      *   , message to be sent
      * @param seqNumber
      *   , sequence number for the message to be sent
      * @param remainingMsgsToSend
      *   , messages not yet sent
      */
    private def sendMessage(
        messageCodec: MessageCodec,
        messageToSend: MessageSerializable,
        seqNumber: Int,
        remainingMsgsToSend: Queue[MessageSerializable]
    ): Unit = {
      val out = messageCodec.encodeMessage(messageToSend)
      connection ! Write(out, Ack)
      log.debug("Sent message: {} to {}", messageToSend.underlyingMsg.toShortString, peerId)

      val timeout = system.scheduler.scheduleOnce(rlpxConfiguration.waitForTcpAckTimeout, self, AckTimeout(seqNumber))
      context.become(
        handshaked(
          messageCodec = messageCodec,
          messagesNotSent = remainingMsgsToSend,
          cancellableAckTimeout = Some(CancellableAckTimeout(seqNumber, timeout)),
          seqNumber = increaseSeqNumber(seqNumber)
        )
      )
    }

    /** Given a sequence number for the AckTimeouts, the next seq number is returned
      *
      * @param seqNumber
      *   , the current sequence number
      * @return
      *   the sequence number for the next message sent
      */
    private def increaseSeqNumber(seqNumber: Int): Int = seqNumber match {
      case Int.MaxValue => 0
      case _            => seqNumber + 1
    }

    def handleWriteFailed: Receive = { case CommandFailed(cmd: Write) =>
      log.debug(
        "[Stopping Connection] Write to peer {} failed, trying to send {}",
        peerId,
        Hex.toHexString(cmd.data.toArray[Byte])
      )
      gracefulStop()
    }

    def handleConnectionClosed: Receive = { case msg: ConnectionClosed =>
      if (msg.isPeerClosed) {
        log.debug("[Stopping Connection] Connection with {} closed by peer", peerId)
      }
      if (msg.isErrorClosed) {
        log.debug("[Stopping Connection] Connection with {} closed because of error {}", peerId, msg.getErrorCause)
      }

      gracefulStop()
    }
  }
}

object RLPxConnectionHandler {
  def props(
      capabilities: List[Capability],
      authHandshaker: AuthHandshaker,
      rlpxConfiguration: RLPxConfiguration
  ): Props =
    Props(
      new RLPxConnectionHandler(
        capabilities,
        authHandshaker,
        ethMessageCodecFactory,
        rlpxConfiguration,
        HelloCodec.apply
      )
    )

  def ethMessageCodecFactory(
      frameCodec: FrameCodec,
      negotiated: Capability,
      p2pVersion: Long
  ): MessageCodec = {
    val md = NetworkMessageDecoder.orElse(EthereumMessageDecoder.ethMessageDecoder(negotiated))
    new MessageCodec(frameCodec, md, p2pVersion)
  }

  case class ConnectTo(uri: URI)

  case class HandleConnection(connection: ActorRef)

  case class ConnectionEstablished(nodeId: ByteString)

  case object ConnectionFailed

  case class MessageReceived(message: Message)

  case class InitialHelloReceived(message: Hello, capability: Capability)

  case class SendMessage(serializable: MessageSerializable)

  private case object AuthHandshakeTimeout

  case object Ack extends Tcp.Event

  case class AckTimeout(seqNumber: Int)

  case class CancellableAckTimeout(seqNumber: Int, cancellable: Cancellable)

  trait RLPxConfiguration {
    val waitForHandshakeTimeout: FiniteDuration
    val waitForTcpAckTimeout: FiniteDuration
  }

  case class HelloCodec(secrets: Secrets) {
    import MessageCodec._
    lazy val frameCodec = new FrameCodec(secrets)

    def readHello(remainingData: ByteString): Option[(Hello, Seq[Frame])] = {
      val frames = frameCodec.readFrames(remainingData)
      frames.headOption.flatMap(extractHello).map(h => (h, frames.drop(1)))
    }

    // 'Hello' will always fit into a frame
    def writeHello(h: HelloEnc): ByteString = {
      val encoded: Array[Byte] = h.toBytes
      val numFrames = Math.ceil(encoded.length / MaxFramePayloadSize.toDouble).toInt
      val frames = (0 until numFrames).map { frameNo =>
        val payload = encoded.drop(frameNo * MaxFramePayloadSize).take(MaxFramePayloadSize)
        val header = Header(payload.length, 0, None, None)
        Frame(header, h.code, ByteString(payload))
      }
      frameCodec.writeFrames(frames)
    }

    private def extractHello(frame: Frame): Option[Hello] = {
      val frameData = frame.payload.toArray
      if (frame.`type` == Hello.code) {
        NetworkMessageDecoder.fromBytes(frame.`type`, frameData) match {
          case Left(err)  => throw err // TODO: rethink throwing here
          case Right(msg) => Some(msg.asInstanceOf[Hello])
        }
      } else {
        None
      }
    }
  }
}
