package com.chipprbots.ethereum.network.rlpx

import java.net.InetSocketAddress
import java.net.URI

import org.apache.pekko.actor.{Actor as ClassicActor, ActorRef as ClassicActorRef, Cancellable, Props, Terminated}
import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.actor.typed.scaladsl.{ActorContext, Behaviors}
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.io.{IO, Tcp}
import org.apache.pekko.io.Tcp.*
import org.apache.pekko.util.ByteString

import scala.collection.immutable.Queue
import scala.concurrent.duration.*
import scala.util.{Failure, Success, Try}

import org.bouncycastle.util.encoders.Hex

import com.chipprbots.ethereum.network.PeerActor
import com.chipprbots.ethereum.network.handshaker.HelloExchangeState
import com.chipprbots.ethereum.network.p2p.EthereumMessageDecoder
import com.chipprbots.ethereum.network.p2p.Message
import com.chipprbots.ethereum.network.p2p.MessageDecoder
import com.chipprbots.ethereum.network.p2p.MessageDecoder.*
import com.chipprbots.ethereum.network.p2p.MessageSerializable
import com.chipprbots.ethereum.network.p2p.NetworkMessageDecoder
import com.chipprbots.ethereum.network.p2p.SNAPMessageDecoder
import com.chipprbots.ethereum.network.p2p.messages.Capability
import com.chipprbots.ethereum.network.p2p.messages.WireProtocol.Hello
import com.chipprbots.ethereum.network.p2p.messages.WireProtocol.Hello.HelloEnc
import com.chipprbots.ethereum.network.rlpx.MessageCodec.CompressionPolicy
import com.chipprbots.ethereum.utils.ByteUtils

/** RLPxConnectionHandler manages the RLPx secure connection lifecycle and message framing.
  *
  * Behaviour states:
  *   1. waitingForCommand — idle, awaiting ConnectTo or HandleConnection 2. waitingForConnectionResult — outbound TCP
  *      connect in flight 3. waitingForAuthHandshakeInit — inbound: awaiting auth-init packet 4.
  *      waitingForAuthHandshakeResponse — outbound: awaiting auth-response packet 5. awaitInitialHello — awaiting Hello
  *      exchange completion 6. handshaked — steady-state message framing
  *
  * All raw TCP I/O is delegated to a Classic bridge actor (OutboundTcpBridge / InboundTcpBridge) so sender() is never
  * needed in this Typed behavior.
  */
object RLPxConnectionHandler:

  // =========================================================================
  // Command ADT
  // =========================================================================

  sealed trait Command

  // External commands (received from PeerActor)
  final case class ConnectTo(uri: URI) extends Command
  final case class HandleConnection(connection: ClassicActorRef) extends Command
  final case class SendMessage(serializable: MessageSerializable) extends Command

  // TCP bridge → Typed actor events (private)
  final private case class TcpConnected(conn: Tcp.Connected, connectionRef: ClassicActorRef) extends Command
  private case object TcpConnectFailed extends Command
  final private case class TcpReceived(data: ByteString) extends Command
  private case object TcpAckReceived extends Command
  private case object TcpWriteFailed extends Command
  final private case class TcpClosed(event: Tcp.ConnectionClosed) extends Command
  private case object TcpConnectionTerminated extends Command

  // Self-scheduled timer messages (private)
  final private case class AuthHandshakeTimedOut() extends Command
  final case class AckTimeout(seqNumber: Int) extends Command

  // =========================================================================
  // Metrics (read every 60 s by NetworkPeerManagerActor)
  // =========================================================================

  val tcpFailedCount = new java.util.concurrent.atomic.AtomicInteger(0)
  val authFailedCount = new java.util.concurrent.atomic.AtomicInteger(0)
  val authTimeoutCount = new java.util.concurrent.atomic.AtomicInteger(0)

  // =========================================================================
  // Canonical message-id bases (exposed for unit-testing offset logic)
  // =========================================================================

  val CanonicalEthBase: Int = 0x10
  val CanonicalEthSize: Int = 0x11
  val CanonicalSnapBase: Int = 0x30
  val CanonicalSnapSize: Int = 0x08

  // =========================================================================
  // Parent-direction types — messages this actor sends to PeerActor
  // =========================================================================

  final case class ConnectionEstablished(nodeId: ByteString) extends PeerActor.Command
  case object ConnectionFailed extends PeerActor.Command
  final case class MessageReceived(message: Message) extends PeerActor.Command
  final case class InitialHelloReceived(message: Hello, capability: Capability) extends PeerActor.Command

  // =========================================================================
  // Supporting types
  // =========================================================================

  case object Ack extends Tcp.Event

  case class CancellableAckTimeout(seqNumber: Int, cancellable: Cancellable)

  trait RLPxConfiguration:
    val waitForHandshakeTimeout: FiniteDuration
    val waitForTcpAckTimeout: FiniteDuration

  // =========================================================================
  // Pure functions
  // =========================================================================

  /** Message-ID slots an eth capability reserves, per go-ethereum's own `protocolLengths`
    * (eth/protocols/eth/protocol.go): {66:17, 67:17, 68:17, 69:18, 70:18, 71:20, 72:22}.
    *
    * This is a property of the specific (name, version) pair, not a constant — devp2p's rlpx.md ("Message ID-based
    * Multiplexing") says each capability statically specifies how many message IDs it requires, and offsets are
    * assigned from that. Getting it wrong shifts the SNAP base and misroutes every snap message; see
    * RLPxCapabilityOffsetsSpec for the regression cases.
    *
    * ETH70 was negotiable (Capability.scala:29,40) but fell through to 17 here, which would have put the SNAP base one
    * slot low on any ETH70 + snap peering. Dormant only because the hive profile in use caps advertisement at eth/69.
    */
  def ethWireSizeFor(cap: Capability): Int = cap match
    case Capability.ETH69 | Capability.ETH70 => 0x12
    case _                                   => 0x11

  case class CapabilityOffsets(peerEthBase: Int, peerEthSize: Int, peerSnapBase: Option[Int])

  def computeCapabilityOffsets(
      peerCaps: List[Capability],
      negotiatedEth: Capability,
      supportsSnap: Boolean
  ): CapabilityOffsets =
    val snapPresent = peerCaps.contains(Capability.SNAP1)
    val ethPresent = peerCaps.exists(_.name == com.chipprbots.ethereum.network.p2p.messages.ProtocolFamily.ETH)
    val snapOnlyPeer = snapPresent && !ethPresent
    val peerEthWireSize = ethWireSizeFor(negotiatedEth)

    val peerSnapBase: Option[Int] =
      if !supportsSnap then None
      else if snapOnlyPeer then Some(CanonicalEthBase)
      else Some(CanonicalEthBase + peerEthWireSize)

    CapabilityOffsets(peerEthBase = CanonicalEthBase, peerEthWireSize, peerSnapBase)

  def ethMessageCodecFactory(
      frameCodec: FrameCodec,
      negotiated: Capability,
      p2pVersion: Long,
      clientId: String,
      compressionPolicy: CompressionPolicy,
      supportsSnap: Boolean
  ): MessageCodec =
    val ethDecoder = EthereumMessageDecoder.ethMessageDecoder(negotiated)
    val decoderWithSnap =
      if supportsSnap then NetworkMessageDecoder.orElse(ethDecoder).orElse(SNAPMessageDecoder)
      else NetworkMessageDecoder.orElse(ethDecoder)
    new MessageCodec(frameCodec, decoderWithSnap, p2pVersion, clientId, compressionPolicy)

  // =========================================================================
  // HelloCodec (pure value, no actor lifecycle — unchanged)
  // =========================================================================

  case class HelloCodec(secrets: Secrets):
    import MessageCodec.*
    lazy val frameCodec = new FrameCodec(secrets)

    def readHello(remainingData: ByteString): Option[(Hello, Seq[Frame])] =
      val frames = frameCodec.readFrames(remainingData)
      frames.headOption.flatMap(extractHello).map(h => (h, frames.drop(1)))

    def writeHello(h: HelloEnc): ByteString =
      val encoded: Array[Byte] = h.toBytes
      val numFrames = Math.ceil(encoded.length / MaxFramePayloadSize.toDouble).toInt
      val frames = (0 until numFrames).map { frameNo =>
        val payload = encoded.drop(frameNo * MaxFramePayloadSize).take(MaxFramePayloadSize)
        val header = Header(payload.length, 0, None, None)
        Frame(header, h.code, ByteString(payload))
      }
      frameCodec.writeFrames(frames)

    private def extractHello(frame: Frame): Option[Hello] =
      if frame.`type` == Hello.code then
        NetworkMessageDecoder.fromBytes(frame.`type`, frame.payload.toArray) match
          case Left(err)       => throw err
          case Right(h: Hello) => Some(h)
          case Right(_)        => None
      else None

  // =========================================================================
  // Classic TCP bridge — outbound (initiates TCP Connect)
  // =========================================================================

  private class OutboundTcpBridge(
      tcpManager: ClassicActorRef,
      remoteAddress: InetSocketAddress,
      typedSelf: ActorRef[Command]
  ) extends ClassicActor:

    override def preStart(): Unit = tcpManager ! Tcp.Connect(remoteAddress)

    override def receive: ClassicActor.Receive = awaitingConnect

    private val awaitingConnect: ClassicActor.Receive = {
      case conn @ Tcp.Connected(_, _) =>
        val connectionRef = sender()
        connectionRef ! Tcp.Register(self)
        context.watch(connectionRef)
        typedSelf ! TcpConnected(conn, connectionRef)
        context.become(active(connectionRef))

      case Tcp.CommandFailed(_: Tcp.Connect) =>
        typedSelf ! TcpConnectFailed
        context.stop(self)
    }

    private def active(connectionRef: ClassicActorRef): ClassicActor.Receive = {
      case Tcp.Received(data)              => typedSelf ! TcpReceived(data)
      case Ack                             => typedSelf ! TcpAckReceived
      case Tcp.CommandFailed(_: Tcp.Write) => typedSelf ! TcpWriteFailed
      case event: Tcp.ConnectionClosed     => typedSelf ! TcpClosed(event)
      case Terminated(_)                   => typedSelf ! TcpConnectionTerminated
      // Forward from Typed actor → TCP connection
      case w: Tcp.Write => connectionRef ! w
      case Tcp.Close    => connectionRef ! Tcp.Close
    }

  // =========================================================================
  // Classic TCP bridge — inbound (TCP connection already established)
  // =========================================================================

  private class InboundTcpBridge(
      connectionRef: ClassicActorRef,
      typedSelf: ActorRef[Command]
  ) extends ClassicActor:

    override def preStart(): Unit =
      connectionRef ! Tcp.Register(self)
      context.watch(connectionRef)

    override def receive: ClassicActor.Receive = {
      case Tcp.Received(data)              => typedSelf ! TcpReceived(data)
      case Ack                             => typedSelf ! TcpAckReceived
      case Tcp.CommandFailed(_: Tcp.Write) => typedSelf ! TcpWriteFailed
      case event: Tcp.ConnectionClosed     => typedSelf ! TcpClosed(event)
      case Terminated(_)                   => typedSelf ! TcpConnectionTerminated
      case w: Tcp.Write                    => connectionRef ! w
      case Tcp.Close                       => connectionRef ! Tcp.Close
    }

  // =========================================================================
  // Props factory — used by Classic PeerActor (co-existence, signature unchanged)
  // =========================================================================

  def props(
      capabilities: List[Capability],
      authHandshaker: AuthHandshaker,
      rlpxConfiguration: RLPxConfiguration,
      parent: ActorRef[PeerActor.Command]
  ): Props =
    PropsAdapter(
      apply(capabilities, authHandshaker, ethMessageCodecFactory, rlpxConfiguration, HelloCodec.apply, parent)
    )

  // =========================================================================
  // Behavior factory
  // =========================================================================

  def apply(
      capabilities: List[Capability],
      authHandshaker: AuthHandshaker,
      messageCodecFactory: (FrameCodec, Capability, Long, String, CompressionPolicy, Boolean) => MessageCodec,
      rlpxConfiguration: RLPxConfiguration,
      extractorFactory: Secrets => HelloCodec,
      parent: ActorRef[PeerActor.Command],
      tcpManagerForTest: Option[ClassicActorRef] = None
  ): Behavior[Command] = Behaviors.setup { context =>
    val peerId: String = parent.path.name
    val tcpManager: ClassicActorRef = tcpManagerForTest.getOrElse(IO(Tcp)(context.system.classicSystem))
    new Impl(
      capabilities,
      authHandshaker,
      messageCodecFactory,
      rlpxConfiguration,
      extractorFactory,
      parent,
      peerId,
      tcpManager,
      context
    ).waitingForCommand()
  }

  // =========================================================================
  // Behaviour implementation
  // =========================================================================

  final private class Impl(
      capabilities: List[Capability],
      authHandshaker: AuthHandshaker,
      messageCodecFactory: (FrameCodec, Capability, Long, String, CompressionPolicy, Boolean) => MessageCodec,
      rlpxConfiguration: RLPxConfiguration,
      extractorFactory: Secrets => HelloCodec,
      parent: ActorRef[PeerActor.Command],
      peerId: String,
      tcpManager: ClassicActorRef,
      context: ActorContext[Command]
  ):
    import AuthHandshaker.{InitiatePacketLength, ResponsePacketLength}

    private val log = context.log
    private given ec: scala.concurrent.ExecutionContext = context.executionContext

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private def schedule(delay: FiniteDuration, msg: Command): Cancellable =
      context.system.scheduler.scheduleOnce(
        delay,
        new Runnable:
          def run() = context.self ! msg
      )

    private def bridgeWrite(bridge: ClassicActorRef, data: ByteString): Unit =
      bridge.tell(Tcp.Write(data, Ack), context.self.toClassic)

    private def increaseSeqNumber(n: Int): Int = if n == Int.MaxValue then 0 else n + 1

    private def writeUncompressedHello(hello: HelloEnc, messageCodec: MessageCodec): ByteString =
      import MessageCodec.MaxFramePayloadSize
      val encoded = hello.toBytes
      val numFrames = Math.ceil(encoded.length / MaxFramePayloadSize.toDouble).toInt
      val frames = (0 until numFrames).map { frameNo =>
        val payload = encoded.drop(frameNo * MaxFramePayloadSize).take(MaxFramePayloadSize)
        Frame(Header(payload.length, 0, None, None), hello.code, ByteString(payload))
      }
      messageCodec.frameCodec.writeFrames(frames)

    private def spawnOutboundBridge(remoteAddress: InetSocketAddress): ClassicActorRef =
      context.actorOf(
        Props(new OutboundTcpBridge(tcpManager, remoteAddress, context.self)),
        "tcp-bridge"
      )

    private def spawnInboundBridge(connection: ClassicActorRef): ClassicActorRef =
      context.actorOf(
        Props(new InboundTcpBridge(connection, context.self)),
        "tcp-bridge"
      )

    // -----------------------------------------------------------------------
    // InboundTranslator (capability offset mapping — unchanged logic)
    // -----------------------------------------------------------------------

    private case class InboundTranslator(
        peerEthBase: Int,
        peerEthSize: Int,
        peerSnapBase: Option[Int]
    ):
      def translateType(messageType: Int): Int =
        if messageType < CanonicalEthBase then messageType
        else
          peerSnapBase match
            case Some(snapBase) if messageType >= snapBase && messageType < snapBase + CanonicalSnapSize =>
              CanonicalSnapBase + (messageType - snapBase)
            case _ =>
              if messageType >= peerEthBase && messageType < peerEthBase + peerEthSize then
                CanonicalEthBase + (messageType - peerEthBase)
              else messageType

      def toPeerWireType(canonicalType: Int): Int =
        if canonicalType < CanonicalEthBase then canonicalType
        else if canonicalType >= CanonicalSnapBase && canonicalType < CanonicalSnapBase + CanonicalSnapSize then
          peerSnapBase match
            case Some(snapBase) => snapBase + (canonicalType - CanonicalSnapBase)
            case None =>
              log.warn(
                "[RLPx] Attempting to send SNAP message to peer {} without SNAP capability: canonType=0x{}",
                peerId,
                canonicalType.toHexString
              )
              canonicalType
        else if canonicalType >= CanonicalEthBase && canonicalType < CanonicalEthBase + CanonicalEthSize then
          peerEthBase + (canonicalType - CanonicalEthBase)
        else canonicalType

      def translateFrames(frames: Seq[Frame]): Seq[Frame] =
        if frames.isEmpty then frames
        else
          if log.isDebugEnabled then
            val translations = frames.flatMap { frame =>
              val t = translateType(frame.`type`)
              if t == frame.`type` then None
              else Some(s"0x${frame.`type`.toHexString}->0x${t.toHexString}")
            }
            if translations.nonEmpty then
              log.debug(
                "INBOUND_WIRE_TRANSLATE: peer={}, count={}, mappings={}",
                peerId,
                translations.size,
                translations.mkString(",")
              )
          frames.map { frame =>
            val t = translateType(frame.`type`)
            if t == frame.`type` then frame else frame.copy(`type` = t)
          }

    private def computeInboundTranslator(
        hello: Hello,
        negotiatedEth: Capability,
        supportsSnap: Boolean
    ): InboundTranslator =
      val offsets = RLPxConnectionHandler.computeCapabilityOffsets(
        peerCaps = hello.capabilities.toList,
        negotiatedEth = negotiatedEth,
        supportsSnap = supportsSnap
      )
      val snapBaseStr = offsets.peerSnapBase.map(b => s"0x${b.toHexString}").getOrElse("<disabled>")
      log.debug(
        s"INBOUND_CAP_OFFSETS: peer=$peerId clientId=${hello.clientId} " +
          s"peerEthBase=0x${offsets.peerEthBase.toHexString} peerSnapBase=$snapBaseStr"
      )
      InboundTranslator(offsets.peerEthBase, offsets.peerEthSize, offsets.peerSnapBase)

    // -----------------------------------------------------------------------
    // processMessage — sends to parent, returns Unit (no state change)
    // -----------------------------------------------------------------------

    private def processMessage(messageTry: Either[DecodingError, Message]): Unit = messageTry match
      case Right(message) =>
        parent ! MessageReceived(message)

      case Left(ex: UnknownMessageTypeError) =>
        log.warn(
          "DECODE_ERROR: Peer {} sent unknown message type: 0x{} ({}). " +
            "Skipping this message but keeping connection alive. Error: {}",
          peerId,
          ex.messageType.toHexString,
          ex.messageType,
          ex.getMessage
        )

      case Left(ex) if MessageDecoder.isDecompressionFailure(ex) =>
        log.warn(
          "DECODE_ERROR: Peer {} sent message that failed to decompress. " +
            "Skipping this message but keeping connection alive. Error: {}",
          peerId,
          ex.getMessage
        )

      case Left(ex) =>
        val errMsg = Option(ex.getMessage).map(m => if m.length > 80 then m.take(80) + "…" else m).getOrElse("null")
        log.warn("DECODE_ERROR: Cannot decode message from {} - disconnecting. Error: {}", peerId, errMsg)
        parent ! com.chipprbots.ethereum.network.PeerActor.DisconnectPeer(
          com.chipprbots.ethereum.network.p2p.messages.WireProtocol.Disconnect.Reasons.BreachOfProtocol
        )

    private def processFrames(
        frames: Seq[Frame],
        messageCodec: MessageCodec,
        inboundTranslator: InboundTranslator
    ): Unit =
      if frames.nonEmpty then
        val translatedFrames = inboundTranslator.translateFrames(frames)
        messageCodec.readFrames(translatedFrames).foreach(processMessage)

    // -----------------------------------------------------------------------
    // Codec negotiation helpers
    // -----------------------------------------------------------------------

    private def negotiateCodec(
        hello: Hello,
        extractor: HelloCodec
    ): Option[(MessageCodec, Capability, InboundTranslator)] =
      Capability.negotiate(hello.capabilities.toList, capabilities).map { negotiated =>
        val compressionPolicy =
          CompressionPolicy.fromHandshake(HelloExchangeState.P2pVersion, hello.p2pVersion)
        val supportsSnap =
          capabilities.contains(Capability.SNAP1) && hello.capabilities.contains(Capability.SNAP1)
        if supportsSnap then log.debug("[RLPx] SNAP/1 capability enabled for peer {}", peerId)
        val inboundTranslator = computeInboundTranslator(hello, negotiated, supportsSnap)
        (
          messageCodecFactory(
            extractor.frameCodec,
            negotiated,
            hello.p2pVersion,
            hello.clientId,
            compressionPolicy,
            supportsSnap
          ),
          negotiated,
          inboundTranslator
        )
      }

    // -----------------------------------------------------------------------
    // decodeV4Packet — unchanged
    // -----------------------------------------------------------------------

    private def decodeV4Packet(data: ByteString): (ByteString, ByteString) =
      if data.length < 2 then
        val headHex = Hex.toHexString(data.toArray)
        throw new RuntimeException(s"EIP-8 packet too short for size prefix: len=${data.length} headHex=${headHex}")
      val encryptedPayloadSize = ByteUtils.bigEndianToShort(data.take(2).toArray)
      if encryptedPayloadSize > 2048 then
        throw new RuntimeException(
          s"EIP-8 handshake packet too large: sizePrefix=${encryptedPayloadSize} max=2048"
        )
      val totalFrameSize = encryptedPayloadSize + 2
      if encryptedPayloadSize <= 0 || totalFrameSize > data.length then
        val headHex = Hex.toHexString(data.take(Math.min(32, data.length)).toArray)
        throw new RuntimeException(
          s"EIP-8 frame size mismatch: sizePrefix=${encryptedPayloadSize} totalFrameSize=${totalFrameSize} bufferLen=${data.length} headHex=${headHex}"
        )
      data.splitAt(totalFrameSize)

    // -----------------------------------------------------------------------
    // processHandshakeResult — returns the next behavior
    // -----------------------------------------------------------------------

    private def processHandshakeResult(
        result: AuthHandshakeResult,
        remainingData: ByteString,
        bridge: ClassicActorRef
    ): Behavior[Command] = result match
      case AuthHandshakeSuccess(secrets, remotePubKey) =>
        log.debug("[RLPx] Auth handshake SUCCESS for peer {}, establishing secure connection", peerId)
        parent ! ConnectionEstablished(remotePubKey)
        extractHelloAndTransition(
          extractorFactory(secrets),
          remainingData,
          bridge,
          helloAckPending = false,
          helloWriteAcknowledged = false
        )

      case AuthHandshakeError =>
        authFailedCount.incrementAndGet()
        log.warn("[Stopping Connection] Auth handshake FAILED for peer {}", peerId)
        parent ! ConnectionFailed
        stopping()

    // -----------------------------------------------------------------------
    // extractHelloAndTransition — reads frames looking for Hello, returns next behavior
    // -----------------------------------------------------------------------

    private def extractHelloAndTransition(
        extractor: HelloCodec,
        data: ByteString,
        bridge: ClassicActorRef,
        helloAckPending: Boolean,
        helloWriteAcknowledged: Boolean,
        cancellableAckTimeout: Option[CancellableAckTimeout] = None,
        seqNumber: Int = 0
    ): Behavior[Command] =
      Try(extractor.readHello(data)) match
        case Failure(err) =>
          log.warn("[RLPx] Malformed Hello from peer {}: {} — disconnecting", peerId, err.getMessage)
          parent ! ConnectionFailed
          stopping()

        case Success(Some((hello, restFrames))) =>
          log.debug(
            "[RLPx] Extracted Hello message from peer {}, protocol version: {}, capabilities: {}",
            peerId,
            hello.p2pVersion,
            hello.capabilities.mkString(", ")
          )
          negotiateCodec(hello, extractor) match
            case None =>
              log.debug(
                "[Stopping Connection] Unable to negotiate protocol with peer {} — peerCaps=[{}], ourCaps=[{}]",
                peerId,
                hello.capabilities.mkString(", "),
                capabilities.mkString(", ")
              )
              parent ! ConnectionFailed
              stopping()

            case Some((messageCodec, negotiated, inboundTranslator)) =>
              log.debug("[RLPx] Protocol negotiated with peer {}: {}", peerId, negotiated)
              parent ! InitialHelloReceived(hello, negotiated)
              processFrames(restFrames, messageCodec, inboundTranslator)
              // Enable inbound compression after full Hello exchange (mirrors core-geth SetSnappy timing)
              if helloWriteAcknowledged then
                messageCodec.enableInboundCompression("handshake-complete")
                log.debug("[RLPx] Enabled inbound compression for peer {} after handshake complete", peerId)
              log.debug("[RLPx] Connection FULLY ESTABLISHED with peer {}, entering handshaked state", peerId)
              handshaked(
                messageCodec,
                inboundTranslator,
                bridge,
                cancellableAckTimeout = cancellableAckTimeout,
                seqNumber = seqNumber
              )

        case Success(None) =>
          log.debug("[RLPx] Did not find 'Hello' in message from peer {}, continuing to await", peerId)
          awaitInitialHello(
            extractor,
            bridge,
            helloAckPending,
            helloWriteAcknowledged,
            cancellableAckTimeout,
            seqNumber
          )

    // -----------------------------------------------------------------------
    // stopping — terminal behavior
    // -----------------------------------------------------------------------

    def stopping(): Behavior[Command] =
      log.debug("[RLPx] Connection handler for peer {} stopping", peerId)
      Behaviors.stopped

    // -----------------------------------------------------------------------
    // State 1: waitingForCommand
    // -----------------------------------------------------------------------

    def waitingForCommand(): Behavior[Command] = Behaviors.receiveMessage {
      case ConnectTo(uri) =>
        log.debug("[RLPx] Initiating connection to peer {} at {}", peerId, uri)
        val bridge = spawnOutboundBridge(new InetSocketAddress(uri.getHost, uri.getPort))
        waitingForConnectionResult(uri, bridge)

      case HandleConnection(connection) =>
        log.debug("[RLPx] Handling incoming connection for peer {}", peerId)
        val bridge = spawnInboundBridge(connection)
        val timeout = schedule(rlpxConfiguration.waitForHandshakeTimeout, AuthHandshakeTimedOut())
        waitingForAuthHandshakeInit(authHandshaker, timeout, bridge)

      case _ => Behaviors.unhandled
    }

    // -----------------------------------------------------------------------
    // State 2: waitingForConnectionResult
    // -----------------------------------------------------------------------

    def waitingForConnectionResult(uri: URI, bridge: ClassicActorRef): Behavior[Command] =
      Behaviors.receiveMessage {
        case TcpConnected(_, _) =>
          log.debug("[RLPx] TCP connection established for peer {}, starting auth handshake", peerId)
          val (initPacket, handshaker) = authHandshaker.initiate(uri)
          bridgeWrite(bridge, initPacket)
          val timeout = schedule(rlpxConfiguration.waitForHandshakeTimeout, AuthHandshakeTimedOut())
          waitingForAuthHandshakeResponse(handshaker, timeout, bridge)

        case TcpConnectFailed =>
          tcpFailedCount.incrementAndGet()
          log.debug("[Stopping Connection] TCP connection failed for peer {}", peerId)
          parent ! ConnectionFailed
          stopping()

        case TcpConnectionTerminated =>
          log.debug("[Stopping Connection] Bridge terminated while waiting for connect for peer {}", peerId)
          parent ! ConnectionFailed
          stopping()

        case _ => Behaviors.unhandled
      }

    // -----------------------------------------------------------------------
    // State 3: waitingForAuthHandshakeInit  (inbound — we receive init packet)
    // -----------------------------------------------------------------------

    def waitingForAuthHandshakeInit(
        handshaker: AuthHandshaker,
        timeout: Cancellable,
        bridge: ClassicActorRef
    ): Behavior[Command] = Behaviors.receiveMessage {
      case TcpReceived(data) =>
        log.debug(
          "[RLPx] Received auth handshake init message for peer {} ({} bytes)",
          peerId,
          data.length
        )
        timeout.cancel()
        log.debug(
          "[RLPx] Attempting pre-EIP8 auth-init decode for peer {} (taking {} bytes from {} total)",
          peerId,
          InitiatePacketLength,
          data.length
        )
        val maybePreEIP8 = Try {
          val (responsePacket, result) = handshaker.handleInitialMessage(data.take(InitiatePacketLength))
          (responsePacket, result, data.drop(InitiatePacketLength))
        }
        maybePreEIP8.failed.foreach(ex => log.debug("[RLPx] pre-EIP8 auth-init decode failed for peer {}", peerId, ex))

        lazy val maybePostEIP8 = Try {
          log.debug("[RLPx] Attempting EIP-8 (v4) auth-init decode for peer {}", peerId)
          val (packetData, remainingData) = decodeV4Packet(data)
          val (responsePacket, result) = handshaker.handleInitialMessageV4(packetData, peerId.toString)
          (responsePacket, result, remainingData)
        }
        maybePostEIP8.failed.foreach(ex =>
          log.debug("[RLPx] EIP-8 (v4) auth-init decode failed for peer {}", peerId, ex)
        )

        maybePreEIP8.orElse(maybePostEIP8) match
          case Success((responsePacket, result, remainingData)) =>
            log.debug("[RLPx] Auth handshake init processed for peer {}, sending response", peerId)
            bridgeWrite(bridge, responsePacket)
            processHandshakeResult(result, remainingData, bridge)
          case Failure(ex) =>
            log.debug(
              "[Stopping Connection] Auth decode failed for peer {} - both pre-EIP8 and EIP-8 failed: {}",
              peerId,
              ex.getMessage
            )
            parent ! ConnectionFailed
            stopping()

      case TcpAckReceived =>
        Behaviors.same // auth handshake response write ack — no action

      case TcpWriteFailed =>
        log.debug("[Stopping Connection] Write to peer {} failed during auth handshake init", peerId)
        stopping()

      case AuthHandshakeTimedOut() =>
        authTimeoutCount.incrementAndGet()
        log.warn(
          "[Stopping Connection] Auth handshake timeout for peer {} after {}ms",
          peerId,
          rlpxConfiguration.waitForHandshakeTimeout.toMillis
        )
        parent ! ConnectionFailed
        stopping()

      case TcpClosed(_) | TcpConnectionTerminated =>
        log.debug("[Stopping Connection] TCP connection closed/terminated for peer {} during auth init", peerId)
        parent ! ConnectionFailed
        stopping()

      case _ => Behaviors.unhandled
    }

    // -----------------------------------------------------------------------
    // State 4: waitingForAuthHandshakeResponse  (outbound — we sent init, await response)
    // -----------------------------------------------------------------------

    def waitingForAuthHandshakeResponse(
        handshaker: AuthHandshaker,
        timeout: Cancellable,
        bridge: ClassicActorRef
    ): Behavior[Command] = Behaviors.receiveMessage {
      case TcpAckReceived =>
        log.debug("[RLPx] Auth init packet write acknowledged for peer {}", peerId)
        Behaviors.same

      case TcpReceived(data) =>
        log.debug(
          "[RLPx] Received auth handshake response for peer {} ({} bytes)",
          peerId,
          data.length
        )
        timeout.cancel()
        val maybePreEIP8 = Try {
          val result = handshaker.handleResponseMessage(data.take(ResponsePacketLength))
          (result, data.drop(ResponsePacketLength))
        }
        val maybePostEIP8 = Try {
          val (packetData, remainingData) = decodeV4Packet(data)
          val result = handshaker.handleResponseMessageV4(packetData, peerId.toString)
          (result, remainingData)
        }
        maybePreEIP8.orElse(maybePostEIP8) match
          case Success((result, remainingData)) =>
            log.debug("[RLPx] Auth handshake response processed for peer {}", peerId)
            processHandshakeResult(result, remainingData, bridge)
          case Failure(ex) =>
            log.debug(
              "[Stopping Connection] Response AuthHandshaker message handling failed for peer {}",
              peerId,
              ex
            )
            parent ! ConnectionFailed
            stopping()

      case TcpWriteFailed =>
        log.debug("[Stopping Connection] Write to peer {} failed during auth handshake response", peerId)
        stopping()

      case AuthHandshakeTimedOut() =>
        authTimeoutCount.incrementAndGet()
        log.warn(
          "[Stopping Connection] Auth handshake timeout for peer {} after {}ms",
          peerId,
          rlpxConfiguration.waitForHandshakeTimeout.toMillis
        )
        parent ! ConnectionFailed
        stopping()

      case TcpClosed(_) | TcpConnectionTerminated =>
        log.debug("[Stopping Connection] TCP connection closed/terminated for peer {} during auth response", peerId)
        parent ! ConnectionFailed
        stopping()

      case _ => Behaviors.unhandled
    }

    // -----------------------------------------------------------------------
    // State 5: awaitInitialHello
    //
    // CRITICAL: helloAckPending and helloWriteAcknowledged are explicit named
    // parameters — NOT mutable vars.  This encodes the Snappy-enable invariant:
    //   markHelloAsSent()    → helloAckPending = true
    //   markHelloAckReceived() → if (helloAckPending) helloAckPending=false; helloWriteAcknowledged=true
    //   registerMessageCodec() → if (helloWriteAcknowledged) enableInboundCompression
    // -----------------------------------------------------------------------

    def awaitInitialHello(
        extractor: HelloCodec,
        bridge: ClassicActorRef,
        helloAckPending: Boolean,
        helloWriteAcknowledged: Boolean,
        cancellableAckTimeout: Option[CancellableAckTimeout] = None,
        seqNumber: Int = 0
    ): Behavior[Command] = Behaviors.receiveMessage {
      case SendMessage(h: HelloEnc) =>
        val out = extractor.writeHello(h)
        bridgeWrite(bridge, out)
        log.debug("[RLPx] Hello write queued for peer {}", peerId)
        val timeout = schedule(rlpxConfiguration.waitForTcpAckTimeout, AckTimeout(seqNumber))
        awaitInitialHello(
          extractor,
          bridge,
          helloAckPending = true,
          helloWriteAcknowledged = helloWriteAcknowledged,
          Some(CancellableAckTimeout(seqNumber, timeout)),
          increaseSeqNumber(seqNumber)
        )

      case TcpAckReceived if cancellableAckTimeout.nonEmpty =>
        cancellableAckTimeout.foreach(_.cancellable.cancel())
        val (newPending, newAcknowledged) =
          if helloAckPending then
            log.debug("[RLPx] Hello write acknowledged for peer {} - deferring compression enable", peerId)
            (false, true)
          else (helloAckPending, helloWriteAcknowledged)
        awaitInitialHello(extractor, bridge, newPending, newAcknowledged, None, seqNumber)

      case TcpAckReceived =>
        // Ack for auth handshake response write — no pending hello timeout
        Behaviors.same

      case AckTimeout(n) if cancellableAckTimeout.exists(_.seqNumber == n) =>
        cancellableAckTimeout.foreach(_.cancellable.cancel())
        log.warn("[Stopping Connection] Sending 'Hello' to {} failed", peerId)
        stopping()

      case TcpReceived(data) =>
        extractHelloAndTransition(
          extractor,
          data,
          bridge,
          helloAckPending,
          helloWriteAcknowledged,
          cancellableAckTimeout,
          seqNumber
        )

      case TcpWriteFailed =>
        log.debug("[Stopping Connection] Write to peer {} failed while awaiting Hello", peerId)
        stopping()

      case TcpClosed(_) | TcpConnectionTerminated =>
        log.debug("[Stopping Connection] TCP connection closed/terminated for peer {} while awaiting Hello", peerId)
        stopping()

      case _ => Behaviors.unhandled
    }

    // -----------------------------------------------------------------------
    // State 6: handshaked — steady-state message framing
    // -----------------------------------------------------------------------

    private def handshaked(
        messageCodec: MessageCodec,
        inboundTranslator: InboundTranslator,
        bridge: ClassicActorRef,
        messagesNotSent: Queue[MessageSerializable] = Queue.empty,
        cancellableAckTimeout: Option[CancellableAckTimeout],
        seqNumber: Int
    ): Behavior[Command] = Behaviors.receiveMessage {

      case SendMessage(h: HelloEnc) =>
        // Late Hello (after handshake) must be sent uncompressed — see core-geth p2p/transport.go
        log.debug(
          "[RLPx] Received late Hello message for peer {} after handshake complete - " +
            "sending uncompressed to prevent 'Cannot decode Hello' error on peer",
          peerId
        )
        val out = writeUncompressedHello(h, messageCodec)
        bridgeWrite(bridge, out)
        val timeout = schedule(rlpxConfiguration.waitForTcpAckTimeout, AckTimeout(seqNumber))
        handshaked(
          messageCodec,
          inboundTranslator,
          bridge,
          messagesNotSent,
          Some(CancellableAckTimeout(seqNumber, timeout)),
          increaseSeqNumber(seqNumber)
        )

      case sm: SendMessage =>
        if cancellableAckTimeout.isEmpty then
          sendMessage(messageCodec, inboundTranslator, bridge, sm.serializable, seqNumber, messagesNotSent)
        else
          handshaked(
            messageCodec,
            inboundTranslator,
            bridge,
            messagesNotSent :+ sm.serializable,
            cancellableAckTimeout,
            seqNumber
          )

      case TcpReceived(data) =>
        val frames = messageCodec.frameCodec.readFrames(data)
        val translatedFrames = inboundTranslator.translateFrames(frames)
        val messages = messageCodec.readFrames(translatedFrames)
        log.debug("RECV_MSG: peer={}, decoded {} message(s)", peerId, messages.size)
        messages.zipWithIndex.foreach { case (msgResult, idx) =>
          msgResult match
            case Right(msg) =>
              log.debug(
                "RECV_MSG: peer={}, msg[{}] type={}, code=0x{}",
                peerId,
                idx,
                msg.getClass.getSimpleName,
                msg.code.toHexString
              )
              if msg.code >= CanonicalSnapBase && msg.code < CanonicalSnapBase + CanonicalSnapSize then
                log.info("RECV_SNAP_MSG: peer={}, msg[{}] {}", peerId, idx, msg.toShortString)
            case Left(err) =>
              log.debug("RECV_MSG: peer={}, msg[{}] DECODE_ERROR: {}", peerId, idx, err.getMessage)
        }
        messages.foreach(processMessage)
        Behaviors.same

      case TcpAckReceived if cancellableAckTimeout.nonEmpty =>
        log.debug("SEND_MSG_ACK: peer={}, seqNum={}", peerId, cancellableAckTimeout.map(_.seqNumber).getOrElse(-1))
        cancellableAckTimeout.foreach(_.cancellable.cancel())
        if messagesNotSent.nonEmpty then
          sendMessage(messageCodec, inboundTranslator, bridge, messagesNotSent.head, seqNumber, messagesNotSent.tail)
        else handshaked(messageCodec, inboundTranslator, bridge, Queue.empty, None, seqNumber)

      case AckTimeout(n) if cancellableAckTimeout.exists(_.seqNumber == n) =>
        cancellableAckTimeout.foreach(_.cancellable.cancel())
        log.warn("[Stopping Connection] SEND_MSG_TIMEOUT: peer={}, seqNum={}", peerId, n)
        stopping()

      case TcpWriteFailed =>
        log.debug("[Stopping Connection] Write to peer {} failed in handshaked state", peerId)
        stopping()

      case TcpClosed(_) | TcpConnectionTerminated =>
        log.debug("[Stopping Connection] TCP connection closed/terminated for peer {}", peerId)
        stopping()

      case _ => Behaviors.unhandled
    }

    // -----------------------------------------------------------------------
    // sendMessage — encodes and writes one message, transitions to new handshaked
    // -----------------------------------------------------------------------

    private def sendMessage(
        messageCodec: MessageCodec,
        inboundTranslator: InboundTranslator,
        bridge: ClassicActorRef,
        messageToSend: MessageSerializable,
        seqNumber: Int,
        remainingMsgsToSend: Queue[MessageSerializable]
    ): Behavior[Command] =
      val canonicalCode = messageToSend.code
      val wireCode = inboundTranslator.toPeerWireType(canonicalCode)

      val serializableToEncode: MessageSerializable =
        if wireCode == canonicalCode then messageToSend
        else
          new MessageSerializable:
            override def code: Int = wireCode
            override def toBytes: Array[Byte] = messageToSend.toBytes
            override def underlyingMsg: Message = messageToSend.underlyingMsg
            override def toShortString: String = messageToSend.toShortString

      val out = messageCodec.encodeMessage(serializableToEncode)
      val msgType = messageToSend.underlyingMsg.getClass.getSimpleName
      log.debug(
        "SEND_MSG: peer={}, type={}, codes={}, seqNum={}",
        peerId,
        msgType,
        s"canon=0x${canonicalCode.toHexString} wire=0x${wireCode.toHexString}",
        seqNumber
      )
      val outHex = MessageCodec.truncateHex(out.toArray[Byte])
      log.debug("SEND_MSG: encodedLen={}, remaining={}, hex={}", out.length, remainingMsgsToSend.size, outHex)

      bridgeWrite(bridge, out)
      log.debug("Sent message: {} to {}", messageToSend.underlyingMsg.toShortString, peerId)

      val timeout = schedule(rlpxConfiguration.waitForTcpAckTimeout, AckTimeout(seqNumber))
      handshaked(
        messageCodec,
        inboundTranslator,
        bridge,
        remainingMsgsToSend,
        Some(CancellableAckTimeout(seqNumber, timeout)),
        increaseSeqNumber(seqNumber)
      )
