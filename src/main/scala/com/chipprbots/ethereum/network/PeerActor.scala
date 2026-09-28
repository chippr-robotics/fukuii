package com.chipprbots.ethereum.network

import java.net.InetSocketAddress
import java.net.URI

import org.apache.pekko.NotUsed
import org.apache.pekko.actor.Cancellable
import org.apache.pekko.actor.ActorRef as ClassicActorRef
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.ActorContext
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.scaladsl.StashBuffer
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.ByteString

import scala.concurrent.duration.FiniteDuration

import org.bouncycastle.util.encoders.Hex

import com.chipprbots.ethereum.network.PeerActor.Status.*
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerEvent.MessageFromPeer
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerEvent.PeerHandshakeSuccessful
import com.chipprbots.ethereum.network.PeerEventBusActor.Command as PeerEventBusCommand
import com.chipprbots.ethereum.network.PeerEventBusActor.PublishCmd
import com.chipprbots.ethereum.network.PeerManagerActor.PeerConfiguration
import com.chipprbots.ethereum.network.handshaker.Handshaker
import com.chipprbots.ethereum.network.handshaker.Handshaker.HandshakeComplete.HandshakeFailure
import com.chipprbots.ethereum.network.handshaker.Handshaker.HandshakeComplete.HandshakeSuccess
import com.chipprbots.ethereum.network.handshaker.Handshaker.HandshakeResult
import com.chipprbots.ethereum.network.handshaker.Handshaker.NextMessage
import com.chipprbots.ethereum.network.p2p.*
import com.chipprbots.ethereum.network.p2p.messages.Capability
import com.chipprbots.ethereum.network.p2p.messages.Codes
import com.chipprbots.ethereum.network.p2p.messages.WireProtocol.*
import com.chipprbots.ethereum.network.rlpx.AuthHandshaker
import com.chipprbots.ethereum.network.rlpx.RLPxConnectionHandler
import com.chipprbots.ethereum.network.rlpx.RLPxConnectionHandler.RLPxConfiguration

/** Peer actor is responsible for initiating and handling high-level connection with peer. It creates child
  * RLPxConnectionActor for handling underlying RLPx communication. Once RLPx connection is established it proceeds with
  * protocol handshake (i.e `Hello` and `Status` exchange). Once that's done it can send/receive messages with peer
  * (handshaked behavior).
  *
  * Migrated from Pekko Classic to Typed. The six former receive states are now named `Behavior[Command]` defs threading
  * their previously-mutable state as explicit parameters:
  *   1. waitingForInitialCommand 2. waitingForConnectionResult 3. processingHandshaking 4. waitingForRetry 5.
  *      disconnected 6. handshaked
  *
  * The child RLPxConnectionHandler is itself a Typed behavior (spawned once in `setup`). Its `supervisorStrategy =
  * Escalate` (Classic) maps to spawning the child with no `Behaviors.supervise` wrapper plus a `watchWith` death watch
  * — any unhandled child exception escalates to (terminates) this actor implicitly.
  */
object PeerActor:

  // =========================================================================
  // Command ADT — intentionally NOT sealed.
  // The parent-direction RLPx messages (ConnectionEstablished, ConnectionFailed,
  // MessageReceived, InitialHelloReceived) are defined in RLPxConnectionHandler.scala
  // and `extends PeerActor.Command`. Scala 3 `sealed` permits subtypes only in the
  // same source file, so the trait stays open to keep those cross-file subtypes legal
  // (an established co-existence point from the RLPxConnectionHandler migration). All
  // state behaviors below match exhaustively with a trailing `case _` fall-through, so
  // openness costs no correctness.
  // =========================================================================

  trait Command

  // External commands (from PeerManagerActor / callers)
  // NB: HandleConnection.connection is a Classic ActorRef by design — the Pekko TCP
  // extension is permanently Classic. This is a supported co-existence point.
  final case class HandleConnection(connection: ClassicActorRef, remoteAddress: InetSocketAddress) extends Command
  final case class ConnectTo(uri: URI) extends Command
  final case class SendMessage(message: MessageSerializable) extends Command
  final case class DisconnectPeer(reason: Int) extends Command
  final case class GetStatus(replyTo: ActorRef[StatusResponse]) extends Command

  // Self-scheduled timer messages (private)
  private case object RetryConnectionTimeout extends Command
  private case object ResponseTimeout extends Command
  private case object StopActor extends Command

  // Death-watch notification for the RLPx child (replaces Classic Terminated)
  final private case class RlpxTerminated(ref: ActorRef[RLPxConnectionHandler.Command]) extends Command

  // =========================================================================
  // Public message/value types
  // =========================================================================

  final case class RLPxConnection(
      ref: ActorRef[RLPxConnectionHandler.Command],
      remoteAddress: InetSocketAddress,
      uriOpt: Option[URI]
  ):
    def sendMessage(message: MessageSerializable): Unit =
      ref ! RLPxConnectionHandler.SendMessage(message)

  final case class IncomingConnectionHandshakeSuccess(peer: Peer)

  final case class PeerClosedConnection(peerHostAddress: String, reason: Long)

  final case class StatusResponse(status: Status)

  sealed trait Status

  object Status:
    case object Idle extends Status
    case object Connecting extends Status
    final case class Handshaking(numRetries: Int) extends Status
    case object Handshaked extends Status
    case object Disconnected extends Status

  // =========================================================================
  // Factories
  // =========================================================================

  /** Behavior factory. `rlpxConnectionFactory` spawns the now-Typed RLPxConnectionHandler as a child of this Typed
    * actor.
    */
  // scalastyle:off parameter.number method.length
  def apply[R <: HandshakeResult](
      peerAddress: InetSocketAddress,
      rlpxConnectionFactory: ActorContext[Command] => ActorRef[RLPxConnectionHandler.Command],
      peerConfiguration: PeerConfiguration,
      peerEventBus: ActorRef[PeerEventBusCommand],
      knownNodesManager: ActorRef[KnownNodesManager.Command],
      incomingConnection: Boolean,
      initHandshaker: Handshaker[R]
  ): Behavior[Command] =
    Behaviors.withStash(100) { stash =>
      Behaviors.setup { context =>
        new Impl(
          peerAddress,
          rlpxConnectionFactory,
          peerConfiguration,
          peerEventBus,
          knownNodesManager,
          incomingConnection,
          initHandshaker,
          stash,
          context
        ).waitingForInitialCommand()
      }
    }
  // scalastyle:on parameter.number method.length

  /** Classic-callable factory. PeerManagerActor (still Classic) spawns this via PropsAdapter, getting back a Classic
    * ActorRef. The spawned behavior is fully Typed; the parent-direction RLPx co-existence is preserved.
    */
  // scalastyle:off parameter.number
  def props[R <: HandshakeResult](
      peerAddress: InetSocketAddress,
      peerConfiguration: PeerConfiguration,
      peerEventBus: ActorRef[PeerEventBusCommand],
      knownNodesManager: ActorRef[KnownNodesManager.Command],
      incomingConnection: Boolean,
      handshaker: Handshaker[R],
      authHandshaker: AuthHandshaker,
      capabilities: List[Capability]
  ): org.apache.pekko.actor.Props =
    org.apache.pekko.actor.typed.scaladsl.adapter.PropsAdapter(
      apply(
        peerAddress,
        rlpxConnectionFactory(authHandshaker, peerConfiguration.rlpxConfiguration, capabilities),
        peerConfiguration,
        peerEventBus,
        knownNodesManager,
        incomingConnection,
        initHandshaker = handshaker
      )
    )
  // scalastyle:on parameter.number

  def rlpxConnectionFactory(
      authHandshaker: AuthHandshaker,
      rlpxConfiguration: RLPxConfiguration,
      capabilities: List[Capability]
  ): ActorContext[Command] => ActorRef[RLPxConnectionHandler.Command] = ctx =>
    // RLPxConnectionHandler: default stop intentional — connection-scoped leaf actor,
    // restart would re-handshake from scratch; the PeerActor wrapper handles reconnect.
    ctx.spawn(
      RLPxConnectionHandler.apply(
        capabilities,
        authHandshaker,
        RLPxConnectionHandler.ethMessageCodecFactory,
        rlpxConfiguration,
        RLPxConnectionHandler.HelloCodec.apply,
        ctx.self
      ),
      "rlpx-connection"
    )

  // =========================================================================
  // Behaviour implementation
  // =========================================================================

  // scalastyle:off number.of.methods
  final private class Impl[R <: HandshakeResult](
      peerAddress: InetSocketAddress,
      rlpxConnectionFactory: ActorContext[Command] => ActorRef[RLPxConnectionHandler.Command],
      peerConfiguration: PeerConfiguration,
      peerEventBus: ActorRef[PeerEventBusCommand],
      knownNodesManager: ActorRef[KnownNodesManager.Command],
      incomingConnection: Boolean,
      initHandshaker: Handshaker[R],
      stash: StashBuffer[Command],
      context: ActorContext[Command]
  ):

    private val log = context.log

    private def schedule(delay: FiniteDuration, msg: Command): Cancellable =
      context.system.scheduler.scheduleOnce(delay, () => context.self ! msg)(context.executionContext)

    // Spawn (or, in tests, obtain) a fresh RLPx child for each (re)connection attempt — mirrors the Classic
    // createRlpxConnection lifecycle (the RLPx child stops itself on failure, freeing the "rlpx-connection" name
    // for a subsequent reconnect). watchWith maps the child's termination to a RlpxTerminated command; the absence
    // of a Behaviors.supervise wrapper means any unhandled child exception escalates to (terminates) this actor.
    private def newRlpxConnection(remoteAddress: InetSocketAddress, uriOpt: Option[URI]): RLPxConnection =
      val rlpxRef = rlpxConnectionFactory(context)
      context.watchWith(rlpxRef, RlpxTerminated(rlpxRef))
      RLPxConnection(rlpxRef, remoteAddress, uriOpt)

    private def modifyOutGoingUri(remoteNodeId: ByteString, rlpxConnection: RLPxConnection, uri: URI): URI =
      val host = getHostName(rlpxConnection.remoteAddress.getAddress)
      val port = rlpxConnection.remoteAddress.getPort
      val query = Option(uri.getQuery).getOrElse(s"discport=$port")
      new URI(s"enode://${Hex.toHexString(remoteNodeId.toArray)}@$host:$port?$query")

    // -----------------------------------------------------------------------
    // State 1: waitingForInitialCommand
    // -----------------------------------------------------------------------

    def waitingForInitialCommand(): Behavior[Command] =
      Behaviors.receiveMessage {
        case HandleConnection(connection, remoteAddress) =>
          val rlpxConnection = newRlpxConnection(remoteAddress, None)
          rlpxConnection.ref ! RLPxConnectionHandler.HandleConnection(connection)
          waitingForConnectionResult(rlpxConnection)

        case ConnectTo(uri) =>
          val rlpxConnection =
            newRlpxConnection(new InetSocketAddress(uri.getHost, uri.getPort), Some(uri))
          rlpxConnection.ref ! RLPxConnectionHandler.ConnectTo(uri)
          waitingForConnectionResult(rlpxConnection)

        case GetStatus(replyTo) =>
          replyTo ! StatusResponse(Idle)
          Behaviors.same

        case msg @ (_: SendMessage | _: DisconnectPeer) =>
          stash.stash(msg)
          Behaviors.same

        case _ => Behaviors.same
      }

    // -----------------------------------------------------------------------
    // State 2: waitingForConnectionResult
    // -----------------------------------------------------------------------

    def waitingForConnectionResult(rlpxConnection: RLPxConnection, numRetries: Int = 0): Behavior[Command] =
      Behaviors.receiveMessage {
        case RLPxConnectionHandler.ConnectionEstablished(remoteNodeId) =>
          val newUri =
            rlpxConnection.uriOpt.map(outGoingUri => modifyOutGoingUri(remoteNodeId, rlpxConnection, outGoingUri))
          processHandshakerNextMessage(
            initHandshaker,
            remoteNodeId,
            rlpxConnection.copy(uriOpt = newUri),
            numRetries
          )

        case RLPxConnectionHandler.ConnectionFailed =>
          log.debug("Failed to establish RLPx connection")
          rlpxConnection.uriOpt match
            case Some(uri) if numRetries < peerConfiguration.connectMaxRetries =>
              scheduleConnectRetry(uri, numRetries)
            case Some(uri) =>
              knownNodesManager ! KnownNodesManager.RemoveKnownNode(uri)
              Behaviors.stopped
            case None =>
              log.debug("Connection was initiated by remote peer, not attempting to reconnect")
              Behaviors.stopped

        case RlpxTerminated(ref) if ref == rlpxConnection.ref =>
          handleTerminated(rlpxConnection, numRetries)

        case GetStatus(replyTo) =>
          replyTo ! StatusResponse(Connecting)
          Behaviors.same

        case msg @ (_: SendMessage | _: DisconnectPeer) =>
          stash.stash(msg)
          Behaviors.same

        case _ => Behaviors.same
      }

    // -----------------------------------------------------------------------
    // State 3: processingHandshaking
    // -----------------------------------------------------------------------

    private def processingHandshaking(
        handshaker: Handshaker[R],
        remoteNodeId: ByteString,
        rlpxConnection: RLPxConnection,
        timeout: Cancellable,
        numRetries: Int
    ): Behavior[Command] =
      Behaviors.receiveMessage {
        case RlpxTerminated(ref) if ref == rlpxConnection.ref =>
          handleTerminated(rlpxConnection, numRetries)

        case RLPxConnectionHandler.MessageReceived(d: Disconnect) =>
          handleDisconnect(rlpxConnection, d, Handshaking(numRetries))

        case RLPxConnectionHandler.MessageReceived(_: Ping) =>
          rlpxConnection.sendMessage(Pong())
          Behaviors.same

        case RLPxConnectionHandler.InitialHelloReceived(msg, _) =>
          handshaker.respondToRequest(msg).foreach(msgToSend => rlpxConnection.sendMessage(msgToSend))
          handshaker.applyMessage(msg) match
            case Some(newHandshaker) =>
              timeout.cancel()
              processHandshakerNextMessage(newHandshaker, remoteNodeId, rlpxConnection, numRetries)
            case None =>
              Behaviors.same

        case RLPxConnectionHandler.MessageReceived(msg) =>
          log.debug("Message received: {} from peer {}", msg, peerAddress)
          handshaker.respondToRequest(msg).foreach(msgToSend => rlpxConnection.sendMessage(msgToSend))
          handshaker.applyMessage(msg) match
            case Some(newHandshaker) =>
              timeout.cancel()
              processHandshakerNextMessage(newHandshaker, remoteNodeId, rlpxConnection, numRetries)
            case None =>
              log.debug("Stashing message during handshake: {}", msg.getClass.getSimpleName)
              stash.stash(RLPxConnectionHandler.MessageReceived(msg))
              Behaviors.same

        case ResponseTimeout =>
          timeout.cancel()
          val newHandshaker = handshaker.processTimeout
          processHandshakerNextMessage(newHandshaker, remoteNodeId, rlpxConnection, numRetries)

        case GetStatus(replyTo) =>
          replyTo ! StatusResponse(Handshaking(numRetries))
          Behaviors.same

        case msg @ (_: SendMessage | _: DisconnectPeer) =>
          stash.stash(msg)
          Behaviors.same

        case _ => Behaviors.same
      }

    /** Asks for the next message to send to the handshaker, or, if there is None, becomes handshaked if handshake was
      * successful or disconnects from the peer otherwise.
      */
    private def processHandshakerNextMessage(
        handshaker: Handshaker[R],
        remoteNodeId: ByteString,
        rlpxConnection: RLPxConnection,
        numRetries: Int
    ): Behavior[Command] =
      handshaker.nextMessage match
        case Right(NextMessage(msgToSend, timeoutTime)) =>
          rlpxConnection.sendMessage(msgToSend)
          val newTimeout = schedule(timeoutTime, ResponseTimeout)
          processingHandshaking(handshaker, remoteNodeId, rlpxConnection, newTimeout, numRetries)

        case Left(HandshakeSuccess(handshakeResult)) =>
          rlpxConnection.uriOpt.foreach(uri => knownNodesManager ! KnownNodesManager.AddKnownNode(uri))
          val next = handshaked(remoteNodeId, rlpxConnection, handshakeResult)
          stash.unstashAll(next)

        case Left(HandshakeFailure(reason)) =>
          log.info(
            "HANDSHAKE_FAILURE: Handshake failed with peer {}:{} - reason code: 0x{} ({}). Disconnecting.",
            peerAddress.getHostString,
            peerAddress.getPort,
            reason.toHexString,
            Disconnect.reasonToString(reason)
          )
          rlpxConnection.uriOpt.foreach(uri => knownNodesManager ! KnownNodesManager.RemoveKnownNode(uri))
          disconnectFromPeer(rlpxConnection, reason)

    // -----------------------------------------------------------------------
    // State 4: waitingForRetry (former scheduleConnectRetry inline become)
    // -----------------------------------------------------------------------

    private def scheduleConnectRetry(uri: URI, numRetries: Int): Behavior[Command] =
      log.debug("Scheduling connection retry in {}", peerConfiguration.connectRetryDelay)
      schedule(peerConfiguration.connectRetryDelay, RetryConnectionTimeout)
      waitingForRetry(uri, numRetries)

    private def waitingForRetry(uri: URI, numRetries: Int): Behavior[Command] =
      Behaviors.receiveMessage {
        case RetryConnectionTimeout => reconnect(uri, numRetries + 1)
        case GetStatus(replyTo) =>
          replyTo ! StatusResponse(Connecting)
          Behaviors.same
        case msg @ (_: SendMessage | _: DisconnectPeer) =>
          stash.stash(msg)
          Behaviors.same
        case _ => Behaviors.same
      }

    private def reconnect(uri: URI, numRetries: Int): Behavior[Command] =
      log.debug("Trying to reconnect")
      val address = new InetSocketAddress(uri.getHost, uri.getPort)
      val newConnection = newRlpxConnection(address, Some(uri))
      newConnection.ref ! RLPxConnectionHandler.ConnectTo(uri)
      waitingForConnectionResult(newConnection, numRetries)

    // -----------------------------------------------------------------------
    // State 5: disconnected
    // -----------------------------------------------------------------------

    private def disconnectFromPeer(rlpxConnection: RLPxConnection, reason: Int): Behavior[Command] =
      rlpxConnection.sendMessage(Disconnect(reason))
      schedule(peerConfiguration.disconnectPoisonPillTimeout, StopActor)
      disconnected()

    private def disconnected(): Behavior[Command] =
      Behaviors.receiveMessage {
        case StopActor => Behaviors.stopped
        case GetStatus(replyTo) =>
          replyTo ! StatusResponse(Disconnected)
          Behaviors.same
        case _ => Behaviors.same
      }

    // -----------------------------------------------------------------------
    // Shared transitions
    // -----------------------------------------------------------------------

    /** Handles RLPx termination for the PRE-handshake states only (waitingForConnectionResult, processingHandshaking).
      * Retrying here is safe because no peer identity has been published yet — nothing else in the system references
      * this connection attempt. Do NOT call this from the `handshaked` state: see `handleHandshakedTerminated` for why
      * a HANDSHAKED peer must stop instead of self-reconnecting.
      */
    private def handleTerminated(rlpxConnection: RLPxConnection, numRetries: Int): Behavior[Command] =
      rlpxConnection.uriOpt.foreach(uri => log.debug(s"Underlying rlpx connection with peer ${uri.getUserInfo} closed"))
      rlpxConnection.uriOpt match
        case Some(uri) if numRetries < peerConfiguration.connectMaxRetries =>
          scheduleConnectRetry(uri, numRetries + 1)
        case Some(uri) =>
          knownNodesManager ! KnownNodesManager.RemoveKnownNode(uri)
          // TCP already closed remotely — no need for the disconnect PoisonPill delay
          // (normally used to let a Disconnect wire message flush). Stop immediately so
          // PeerManagerActor decrements its handshaked count, freeing the slot for
          // new incoming peers. Matters in test environments (hive) where many
          // short-lived connections arrive rapidly.
          Behaviors.stopped
        case None =>
          Behaviors.stopped

    /** Handles RLPx termination for the HANDSHAKED state. A handshaked peer has already been published to the peer
      * event bus (`PeerHandshakeSuccessful`) and is tracked by PeerManagerActor's `connectedPeers` under this actor's
      * `context.self` ref. If the underlying TCP link merely drops (a transient blip, not a bad peer), we must STOP
      * rather than self-reconnect:
      *
      * Previously this called `handleTerminated(rlpxConnection, 0)`, which — since 0 < connectMaxRetries — scheduled a
      * reconnect on this SAME actor (`scheduleConnectRetry`). ~`connectRetryDelay` later the SAME actor completed a
      * fresh handshake and published a SECOND `PeerHandshakeSuccessful` with the same `ref`. But PeerManagerActor's
      * `connectedPeers` still held the OLD (stale) handshaked entry for this ref — the actor never stopped, so no
      * `Terminated` ever fired to remove it. `hasHandshakedWith(nodeId)` was therefore true, so PeerManagerActor
      * treated the actor's own fresh handshake as a duplicate and sent `DisconnectPeer(AlreadyConnected)` back to it —
      * disconnecting a perfectly good peer ~30s (retry delay + disconnectPoisonPillTimeout) after a transient TCP drop.
      *
      * Stopping immediately instead lets PeerManagerActor's own death-watch (`PeerTerminated`) remove the stale entry
      * and publish `PeerDisconnected` right away. Maintained peers are still re-dialed — but by PeerManagerActor
      * (`handleTerminated`'s `maintainedPeersByNodeId` branch, keyed on the peer's node ID) with a FRESH actor, not by
      * this actor reconnecting itself; that branch fires on ANY termination of a maintained peer's actor and is
      * independent of this code path. Non-maintained peers are picked up again by the normal dial loop / discovery
      * demand. We also do NOT call `RemoveKnownNode`: this was a good, already-vetted peer — only the pre-handshake
      * path (`handleTerminated`, used for connection attempts that never proved themselves) removes known nodes on
      * exhausted retries.
      */
    private def handleHandshakedTerminated(): Behavior[Command] =
      log.debug("Underlying rlpx connection with HANDSHAKED peer {} closed - stopping (no self-reconnect)", peerAddress)
      Behaviors.stopped

    private def handleDisconnect(
        rlpxConnection: RLPxConnection,
        d: Disconnect,
        status: Status
    ): Behavior[Command] =
      import Disconnect.Reasons.*
      log.info(
        s"DISCONNECT_DEBUG: Received disconnect from ${peerAddress.getHostString}:${peerAddress.getPort} - reason code: 0x${d.reason.toHexString} (${Disconnect
            .reasonToString(d.reason)}), status: $status"
      )
      if d.reason == Other then
        log.info(
          s"DISCONNECT_DEBUG: Subprotocol disconnect (0x10) from ${peerAddress.getHostString}:${peerAddress.getPort}. " +
            s"This typically indicates: ForkId mismatch, malformed message, or protocol incompatibility. " +
            s"Check peer logs or enable debug logging for RLP bytes."
        )
      d.reason match
        case IncompatibleP2pProtocolVersion | UselessPeer | NullNodeIdentityReceived | UnexpectedIdentity |
            IdentityTheSame | Other =>
          rlpxConnection.uriOpt.foreach(uri => knownNodesManager ! KnownNodesManager.RemoveKnownNode(uri))
        case _ => // nothing
      log.debug(s"Received {}. Closing connection with peer ${peerAddress.getHostString}:${peerAddress.getPort}", d)
      status match
        case Handshaked =>
          // graceful stop — let the Disconnect wire message flush before stopping
          schedule(peerConfiguration.disconnectPoisonPillTimeout, StopActor)
          disconnected()
        case _ =>
          Behaviors.stopped

    // -----------------------------------------------------------------------
    // State 6: handshaked
    // -----------------------------------------------------------------------

    /** main behavior of actor that handles peer communication and subscriptions for messages */
    private def handshaked(
        remoteNodeId: ByteString,
        rlpxConnection: RLPxConnection,
        handshakeResult: R
    ): Behavior[Command] = Behaviors.setup { _ =>
      val peerId: PeerId = PeerId(Hex.toHexString(remoteNodeId.toArray))
      val source: Source[Message, NotUsed] = PeerEventBusActor
        .messageSource(
          peerEventBus,
          PeerEventBusActor.SubscriptionClassifier
            .MessageClassifier(
              Set(Codes.BlockBodiesCode, Codes.BlockHeadersCode),
              PeerEventBusActor.PeerSelector.WithId(peerId)
            )
        )
        .map(_.message)
      val peer: Peer =
        Peer(peerId, peerAddress, context.self, incomingConnection, source, Some(remoteNodeId))
      peerEventBus ! PublishCmd(PeerHandshakeSuccessful(peer, handshakeResult))

      Behaviors.receiveMessage {
        case RlpxTerminated(ref) if ref == rlpxConnection.ref =>
          handleHandshakedTerminated()

        case RLPxConnectionHandler.MessageReceived(d: Disconnect) =>
          handleDisconnect(rlpxConnection, d, Handshaked)

        case RLPxConnectionHandler.MessageReceived(_: Ping) =>
          rlpxConnection.sendMessage(Pong())
          Behaviors.same

        case RLPxConnectionHandler.MessageReceived(message) =>
          message match
            case bru: com.chipprbots.ethereum.network.p2p.messages.ETHPackets.BlockRangeUpdate =>
              if bru.earliestBlock > bru.latestBlock || bru.latestBlockHash == org.apache.pekko.util.ByteString(
                  new Array[Byte](32)
                )
              then
                log.warn(
                  "Invalid BlockRangeUpdate from peer {}: earliest={} > latest={} — disconnecting",
                  peerId,
                  bru.earliestBlock,
                  bru.latestBlock
                )
                disconnectFromPeer(
                  rlpxConnection,
                  com.chipprbots.ethereum.network.p2p.messages.WireProtocol.Disconnect.Reasons.BreachOfProtocol
                )
              else
                MessageLogger.logMessage(peerId, message)
                peerEventBus ! PublishCmd(MessageFromPeer(message, peerId))
                Behaviors.same
            case _ =>
              MessageLogger.logMessage(peerId, message)
              peerEventBus ! PublishCmd(MessageFromPeer(message, peerId))
              Behaviors.same

        case DisconnectPeer(reason) =>
          disconnectFromPeer(rlpxConnection, reason)

        case SendMessage(message) =>
          rlpxConnection.sendMessage(message)
          Behaviors.same

        case GetStatus(replyTo) =>
          replyTo ! StatusResponse(Handshaked)
          Behaviors.same

        case _ => Behaviors.same
      }
    }

    // The actor logs incoming messages, which can be quite verbose even for DEBUG mode.
    object MessageLogger:
      def logMessage(peerId: PeerId, message: Message): Unit =
        if log.isTraceEnabled then log.trace(s"Received message: {} from $peerId", message)
        else log.debug(s"Received message: {} from $peerId", message.toShortString)
  // scalastyle:on number.of.methods
