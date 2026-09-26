package com.chipprbots.ethereum.network

import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference

import org.apache.pekko.actor.Actor as ClassicActor
import org.apache.pekko.actor.ActorRef
import org.apache.pekko.actor.Props
import org.apache.pekko.actor.typed.ActorRef as TypedActorRef
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.ActorContext
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.io.IO
import org.apache.pekko.io.Tcp
import org.apache.pekko.io.Tcp.Bind
import org.apache.pekko.io.Tcp.Bound
import org.apache.pekko.io.Tcp.Close
import org.apache.pekko.io.Tcp.CommandFailed
import org.apache.pekko.io.Tcp.Connected
import org.apache.pekko.pattern.after

import scala.concurrent.Future
import scala.concurrent.duration.*
import scala.util.Failure
import scala.util.Success

import org.bouncycastle.util.encoders.Hex

import com.chipprbots.ethereum.blockchain.sync.Blacklist
import com.chipprbots.ethereum.utils.NodeStatus
import com.chipprbots.ethereum.utils.ServerStatus

object ServerActor:

  /** Upper bound on `ExternalIPDetector.detect()`. Every individual UPnP/STUN/HTTP probe inside `detect()` has its own
    * timeout, but those only bound the socket-level connect/receive phase — the DNS lookups that precede them
    * (`InetAddress.getByName`, implicit hostname resolution inside `URLConnection`) are not covered, and can hang
    * indefinitely under network-isolated deployments (airgapped nodes, restricted-egress containers, hive's client
    * sandbox). `serverStatus` no longer depends on this completing (see [[waitingForBindingResult]]), but an unbounded
    * background Future is still a latent resource leak / never-resolves footgun on its own — bound it as defence in
    * depth so the advertised-address refinement always eventually settles one way or the other.
    */
  private val IpDetectionTimeout: FiniteDuration = 20.seconds

  /** How often a listening server whose advertised address was auto-detected (not configured, not `none` mode) re-runs
    * detection to catch a changed external address (e.g. a dynamic-IP ISP reassignment).
    */
  private val RefreshInterval: FiniteDuration = 30.minutes

  private case object RefreshTimerKey

  /** Behavior factory for the Typed ServerActor.
    *
    * The Classic state machine (`receive` → `waitingForBindingResult` → `listening`) is expressed as a set of named
    * `Behavior[Command]` functions, with the previously-mutable `advertisedAddressOverride` threaded through the
    * transition.
    *
    * `serverStatus` is set to `Listening` as soon as the socket is bound (`TcpBound`), using whatever address is
    * available at that moment — the bound address verbatim, or the caller-supplied override. Readiness (are we
    * accepting connections) and advertisement (what address should peers use to reach us) are different questions;
    * gating the former on the latter caused `net_listening`/`admin_nodeInfo` to depend on `ExternalIPDetector`
    * completing, which can hang indefinitely in network-isolated environments (see [[IpDetectionTimeout]] doc). When no
    * explicit advertised address was given and the bind address is a wildcard (`0.0.0.0`/`::`), external IP detection
    * still runs in the background and *refines* the advertised host in place once it (or its bounded timeout) resolves
    * — see the `DetectedIP` cases in [[listening]].
    *
    * Pekko's TCP extension (`IO(Tcp)`) is a Classic API and is intentionally kept as-is. The Classic `Tcp.Event`
    * messages (`Bound`, `CommandFailed`, `Connected`) are received by a small Classic bridge child that forwards them
    * into the typed [[Command]] ADT. The bridge captures `sender()` for [[Connected]] (the TCP connection actor), which
    * is the Typed-equivalent of the old `sender()` lookup — there is no typed `sender()`, so the connection ref must be
    * captured at the Classic boundary and embedded in [[TcpConnected]].
    */
  def apply(
      nodeStatusHolder: AtomicReference[NodeStatus],
      peerManager: TypedActorRef[PeerManagerActor.Command],
      blacklist: Blacklist
  ): Behavior[Command] =
    behavior(nodeStatusHolder, peerManager, blacklist, tcpManagerRef = None, detect = ExternalIPDetector.detect(_))

  /** Test entry point: injects a TCP manager ref (a TestProbe) to avoid real port binding, and a stub detector so no
    * test ever reaches the real UPnP/STUN/HTTP cascade. `detect` has no default deliberately — every call site must
    * state what detection should return, rather than silently inheriting network-touching behaviour.
    */
  def testApply(
      nodeStatusHolder: AtomicReference[NodeStatus],
      peerManager: TypedActorRef[PeerManagerActor.Command],
      blacklist: Blacklist,
      tcpManager: ActorRef,
      detect: DetectionMode => Option[InetAddress]
  ): Behavior[Command] =
    behavior(nodeStatusHolder, peerManager, blacklist, tcpManagerRef = Some(tcpManager), detect = detect)

  private def behavior(
      nodeStatusHolder: AtomicReference[NodeStatus],
      peerManager: TypedActorRef[PeerManagerActor.Command],
      blacklist: Blacklist,
      tcpManagerRef: Option[ActorRef],
      detect: DetectionMode => Option[InetAddress]
  ): Behavior[Command] =
    Behaviors.setup { ctx =>
      val classicSystem = ctx.system.toClassic
      // Tests supply their own ActorRef (TestProbe) to avoid real port binding.
      val tcpManager: ActorRef = tcpManagerRef.getOrElse(IO(Tcp)(classicSystem))

      // Classic bridge: the `Bind` handler that the TCP extension notifies of Bound/CommandFailed/Connected.
      // Captures sender() for Connected (the connection actor) before lifting into the typed Command ADT.
      val tcpBridge: ActorRef =
        ctx.toClassic.actorOf(Props(new TcpEventBridge(ctx.self)), "tcp-event-bridge")

      initial(ctx, nodeStatusHolder, peerManager, blacklist, tcpManager, tcpBridge, detect)
    }

  private def initial(
      ctx: ActorContext[Command],
      nodeStatusHolder: AtomicReference[NodeStatus],
      peerManager: TypedActorRef[PeerManagerActor.Command],
      blacklist: Blacklist,
      tcpManager: ActorRef,
      tcpBridge: ActorRef,
      detect: DetectionMode => Option[InetAddress]
  ): Behavior[Command] =
    Behaviors.receiveMessagePartial { case StartServer(address, advertisedAddress, detectionMode) =>
      // `tcpBridge` must be BOTH the handler and the sender.
      //
      // Pekko IO TCP routes these differently: `Connected` goes to the handler named inside
      // `Bind`, but `Bound` and `CommandFailed` go to the SENDER of the `Bind` command. The
      // Classic original relied on Classic's implicit sender:
      //
      //     IO(Tcp) ! Bind(self, address)        // sender == self, so Bound came back to self
      //
      // Typed has no implicit sender, so a bare `tcpManager ! Bind(...)` is sent with
      // `ActorRef.noSender` and both `Bound` and `CommandFailed` are delivered to dead letters.
      // The socket still binds at the OS level, but `TcpBound` never arrives, the actor sits in
      // `waitingForBindingResult` forever, and `serverStatus` stays `NotListening` — which is
      // what `net_listening` and `admin_nodeInfo` report on. A bind FAILURE was equally
      // invisible, since `CommandFailed` was lost the same way.
      //
      // Use the explicit two-arg `tell` so the bridge receives all three events.
      tcpManager.tell(Bind(tcpBridge, address), tcpBridge)
      waitingForBindingResult(ctx, nodeStatusHolder, peerManager, blacklist, advertisedAddress, detectionMode, detect)
    }

  private def waitingForBindingResult(
      ctx: ActorContext[Command],
      nodeStatusHolder: AtomicReference[NodeStatus],
      peerManager: TypedActorRef[PeerManagerActor.Command],
      blacklist: Blacklist,
      advertisedAddressOverride: Option[InetAddress],
      detectionMode: DetectionMode,
      detect: DetectionMode => Option[InetAddress]
  ): Behavior[Command] =
    Behaviors.receiveMessagePartial {
      case TcpBound(localAddress) =>
        // The socket is genuinely bound and accepting connections from this point on. Report Listening
        // immediately — do not gate readiness on the cosmetic question of which address to advertise to peers.
        advertisedAddressOverride match
          case Some(override_) =>
            reportListening(ctx, nodeStatusHolder, localAddress, new InetSocketAddress(override_, localAddress.getPort))
            listening(ctx, nodeStatusHolder, peerManager, blacklist, localAddress, detectionMode, detect, false)
          case None if localAddress.getAddress.isAnyLocalAddress =>
            // Provisional advertised address is the bound (wildcard) address itself — refined below once
            // detection (or its timeout) resolves. Status is already Listening; nothing downstream waits on this.
            reportListening(ctx, nodeStatusHolder, localAddress, localAddress)
            scheduleIpDetection(ctx, detectionMode, detect)
            listening(ctx, nodeStatusHolder, peerManager, blacklist, localAddress, detectionMode, detect, true)
          case None =>
            reportListening(
              ctx,
              nodeStatusHolder,
              localAddress,
              new InetSocketAddress(localAddress.getAddress, localAddress.getPort)
            )
            listening(ctx, nodeStatusHolder, peerManager, blacklist, localAddress, detectionMode, detect, false)

      case TcpCommandFailed(b) =>
        // Terminal failure: no restart follows (Behaviors.stopped is a clean stop, not a supervised Throwable,
        // so the parent's restartWithBackoff strategy never fires). ERROR (not WARN) because this failure is
        // otherwise externally invisible: hive runs at logging.logs-level=ERROR, and `ServerStatus` has no
        // variant distinguishing "still starting" from "gave up after a bind failure" (both read as
        // NotListening to NetService). Flagged for the coordinator rather than silently added here.
        ctx.log.error("Binding to {} failed — server will not accept connections on this port", b.localAddress)
        Behaviors.stopped

      case TcpConnected(connection, _) =>
        // Should be unreachable in practice — the TCP manager can't dispatch Connected for this listener
        // before it dispatches Bound to the same handler — but this state used to be `waitingForIpDetection`,
        // which had no case for TcpConnected at all: an accepted connection arriving here was silently
        // dropped without ever sending Tcp.Register back to the connection actor, leaving the peer's socket
        // half-open with no data flowing (see incident notes for reportListening/scheduleIpDetection above).
        // Defensive: never silently swallow an accepted connection — close it cleanly instead.
        connection ! Close
        Behaviors.same
    }

  /** Sets `serverStatus = Listening(advertisedAddress)` and logs both the bind confirmation and the node's enode. */
  private def reportListening(
      ctx: ActorContext[Command],
      nodeStatusHolder: AtomicReference[NodeStatus],
      localAddress: InetSocketAddress,
      advertisedAddress: InetSocketAddress
  ): Unit =
    ctx.log.info("Listening on {}", localAddress)
    nodeStatusHolder.getAndUpdate(_.copy(serverStatus = ServerStatus.Listening(advertisedAddress)))
    ctx.log.info(
      "Node address: enode://{}@{}:{}",
      Hex.toHexString(nodeStatusHolder.get().nodeId),
      getHostName(advertisedAddress.getAddress),
      advertisedAddress.getPort
    )

  /** Updates only the advertised host of an already-`Listening` status once external IP detection (or its timeout)
    * resolves. Does not touch peer-manager wiring or re-enter any earlier state — the node has been accepting
    * connections since [[reportListening]] ran.
    */
  private def refineAdvertisedAddress(
      ctx: ActorContext[Command],
      nodeStatusHolder: AtomicReference[NodeStatus],
      localAddress: InetSocketAddress,
      advertisedHost: InetAddress
  ): Unit =
    val advertisedAddress = new InetSocketAddress(advertisedHost, localAddress.getPort)
    nodeStatusHolder.getAndUpdate(_.copy(serverStatus = ServerStatus.Listening(advertisedAddress)))
    ctx.log.info(
      "Node address: enode://{}@{}:{}",
      Hex.toHexString(nodeStatusHolder.get().nodeId),
      getHostName(advertisedHost),
      advertisedAddress.getPort
    )

  /** Runs `detect(detectionMode)` off the dispatcher thread, bounded by [[IpDetectionTimeout]], and pipes the result
    * back as `DetectedIP`. Fire-and-forget from the caller's perspective — `serverStatus` is already `Listening` before
    * this is called; this only ever refines the advertised host.
    */
  private def scheduleIpDetection(
      ctx: ActorContext[Command],
      detectionMode: DetectionMode,
      detect: DetectionMode => Option[InetAddress]
  ): Unit =
    given ec: scala.concurrent.ExecutionContext = ctx.executionContext
    val detection = Future(detect(detectionMode))
    val bounded = Future.firstCompletedOf(
      Seq(
        detection,
        after(IpDetectionTimeout, using = ctx.system.classicSystem.scheduler)(Future.successful(None))
      )
    )
    ctx.pipeToSelf(bounded) {
      case Success(ip) => DetectedIP(ip)
      case Failure(_)  => DetectedIP(None)
    }

  /** Handles inbound connections, applies the initial background detection result (`DetectedIP`) and, when the
    * advertised address was auto-detected (never when explicitly configured, never in `none` mode), periodically
    * re-runs `detect` to catch a changed external address. A refreshed address only replaces the advertised one when it
    * validates as public (via [[ExternalIPDetector.isPublicIPv4]]) and differs from what's currently advertised; the
    * change is logged at INFO.
    */
  private def listening(
      ctx: ActorContext[Command],
      nodeStatusHolder: AtomicReference[NodeStatus],
      peerManager: TypedActorRef[PeerManagerActor.Command],
      blacklist: Blacklist,
      localAddress: InetSocketAddress,
      detectionMode: DetectionMode,
      detect: DetectionMode => Option[InetAddress],
      wasAutoDetected: Boolean,
      refreshInterval: FiniteDuration = RefreshInterval
  ): Behavior[Command] =
    Behaviors.withTimers { timers =>
      if wasAutoDetected && detectionMode != DetectionMode.None then
        timers.startTimerWithFixedDelay(RefreshTimerKey, RefreshExternalIP, refreshInterval)

      Behaviors.receiveMessagePartial {
        case TcpConnected(connection, remoteAddress) =>
          val addr = remoteAddress.getAddress
          val isLocal = addr.isLoopbackAddress || addr.isSiteLocalAddress
          if !isLocal && blacklist.isBlacklisted(PeerManagerActor.PeerAddress(remoteAddress.getHostString)) then
            ctx.log.debug("Dropping inbound TCP from blacklisted {}", remoteAddress.getHostString)
            connection ! Close
          else peerManager ! PeerManagerActor.HandlePeerConnectionCmd(connection, remoteAddress)
          Behaviors.same

        case DetectedIP(Some(ip)) =>
          ctx.log.info("External IP detected for advertisement: {}", ip.getHostAddress)
          refineAdvertisedAddress(ctx, nodeStatusHolder, localAddress, ip)
          Behaviors.same

        case DetectedIP(None) =>
          ctx.log.warn(
            "External IP detection failed or timed out (UPnP/STUN/HTTP/interface all unavailable, detection mode is " +
              "'none', or exceeded {}); advertising loopback — inbound peers on other hosts may not reach this node. " +
              "Set fukuii.network.server-address.advertised-address to advertise a specific reachable address, or " +
              "adjust fukuii.network.server-address.external-ip-detection (none|upnp|full) to change the detection " +
              "strategy.",
            IpDetectionTimeout
          )
          refineAdvertisedAddress(ctx, nodeStatusHolder, localAddress, InetAddress.getLoopbackAddress)
          Behaviors.same

        case RefreshExternalIP if wasAutoDetected && detectionMode != DetectionMode.None =>
          // Same off-thread pattern as the initial detection, and the same injected `detect` function —
          // production re-runs the real cascade; tests supply a stub.
          ctx.pipeToSelf(Future(detect(detectionMode))(ctx.executionContext)) {
            case Success(ip) => RefreshedIP(ip)
            case Failure(_)  => RefreshedIP(None)
          }
          Behaviors.same

        case RefreshExternalIP =>
          // Belt-and-suspenders: the timer is never started for a configured address or `none` mode, but
          // guard the handler too so a stray/test-injected RefreshExternalIP can never re-detect or
          // overwrite a deliberately configured address.
          Behaviors.same

        case RefreshedIP(Some(ip)) if ExternalIPDetector.isPublicIPv4(ip) =>
          val currentHost = nodeStatusHolder.get().serverStatus match
            case ServerStatus.Listening(addr) => Some(addr.getAddress)
            case _                            => None
          if !currentHost.contains(ip) then
            val refreshedAddress = new InetSocketAddress(ip, localAddress.getPort)
            nodeStatusHolder.getAndUpdate(_.copy(serverStatus = ServerStatus.Listening(refreshedAddress)))
            ctx.log.info(
              "External address refreshed: {} -> {}",
              currentHost.map(_.getHostAddress).getOrElse("unknown"),
              ip.getHostAddress
            )
          Behaviors.same

        case RefreshedIP(_) =>
          // None, or a non-public candidate — keep advertising the current address.
          Behaviors.same
      }
    }

  /** Classic bridge actor: registered as the `Bind` handler with the TCP extension. Lifts Classic `Tcp.Event` messages
    * into the typed [[Command]] ADT, capturing `sender()` (the connection actor) for [[Connected]].
    */
  private class TcpEventBridge(parent: TypedActorRef[Command]) extends ClassicActor:
    override def receive: Receive = {
      case Bound(localAddress)    => parent ! TcpBound(localAddress)
      case CommandFailed(b: Bind) => parent ! TcpCommandFailed(b)
      case Connected(remote, _)   => parent ! TcpConnected(sender(), remote)
    }

  sealed trait Command
  // detectionMode has no default: production always passes the configured mode (StdNode reads
  // network.server-address.external-ip-detection); an implicit default here previously caused every
  // call site that omitted it — including several tests — to silently run the full network-touching
  // UPnP/STUN/HTTP cascade.
  case class StartServer(
      address: InetSocketAddress,
      advertisedAddress: Option[InetAddress] = None,
      detectionMode: DetectionMode
  ) extends Command
  private[network] case class DetectedIP(ip: Option[InetAddress]) extends Command

  // Periodic external-address refresh, scheduled only for an auto-detected (non-configured, non-`none`-mode)
  // advertised address. Tests can send RefreshExternalIP directly to trigger a refresh without waiting for
  // the real timer.
  private[network] case object RefreshExternalIP extends Command
  private[network] case class RefreshedIP(ip: Option[InetAddress]) extends Command

  // Internal wrappers lifting Classic Tcp.Event messages (delivered via the TcpEventBridge) into the typed ADT.
  private[network] case class TcpBound(localAddress: InetSocketAddress) extends Command
  private[network] case class TcpCommandFailed(bind: Bind) extends Command
  private[network] case class TcpConnected(connection: ActorRef, remoteAddress: InetSocketAddress) extends Command
