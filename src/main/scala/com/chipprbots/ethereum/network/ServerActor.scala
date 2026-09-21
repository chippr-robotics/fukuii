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
    behavior(
      nodeStatusHolder,
      peerManager,
      blacklist,
      tcpManagerRef = None,
      ipDetector = () => ExternalIPDetector.detect()
    )

  /** Test entry point: injects a TCP manager ref (a TestProbe) to avoid real port binding, and optionally an
    * IP-detector hook so specs can control timing/outcome deterministically instead of hitting the real network.
    */
  def testApply(
      nodeStatusHolder: AtomicReference[NodeStatus],
      peerManager: TypedActorRef[PeerManagerActor.Command],
      blacklist: Blacklist,
      tcpManager: ActorRef,
      ipDetector: () => Option[InetAddress] = () => ExternalIPDetector.detect()
  ): Behavior[Command] =
    behavior(nodeStatusHolder, peerManager, blacklist, tcpManagerRef = Some(tcpManager), ipDetector)

  private def behavior(
      nodeStatusHolder: AtomicReference[NodeStatus],
      peerManager: TypedActorRef[PeerManagerActor.Command],
      blacklist: Blacklist,
      tcpManagerRef: Option[ActorRef],
      ipDetector: () => Option[InetAddress]
  ): Behavior[Command] =
    Behaviors.setup { ctx =>
      val classicSystem = ctx.system.toClassic
      // Tests supply their own ActorRef (TestProbe) to avoid real port binding.
      val tcpManager: ActorRef = tcpManagerRef.getOrElse(IO(Tcp)(classicSystem))

      // Classic bridge: the `Bind` handler that the TCP extension notifies of Bound/CommandFailed/Connected.
      // Captures sender() for Connected (the connection actor) before lifting into the typed Command ADT.
      val tcpBridge: ActorRef =
        ctx.toClassic.actorOf(Props(new TcpEventBridge(ctx.self)), "tcp-event-bridge")

      initial(ctx, nodeStatusHolder, peerManager, blacklist, tcpManager, tcpBridge, ipDetector)
    }

  private def initial(
      ctx: ActorContext[Command],
      nodeStatusHolder: AtomicReference[NodeStatus],
      peerManager: TypedActorRef[PeerManagerActor.Command],
      blacklist: Blacklist,
      tcpManager: ActorRef,
      tcpBridge: ActorRef,
      ipDetector: () => Option[InetAddress]
  ): Behavior[Command] =
    Behaviors.receiveMessagePartial { case StartServer(address, advertisedAddress) =>
      tcpManager ! Bind(tcpBridge, address)
      waitingForBindingResult(ctx, nodeStatusHolder, peerManager, blacklist, advertisedAddress, ipDetector)
    }

  private def waitingForBindingResult(
      ctx: ActorContext[Command],
      nodeStatusHolder: AtomicReference[NodeStatus],
      peerManager: TypedActorRef[PeerManagerActor.Command],
      blacklist: Blacklist,
      advertisedAddressOverride: Option[InetAddress],
      ipDetector: () => Option[InetAddress]
  ): Behavior[Command] =
    Behaviors.receiveMessagePartial {
      case TcpBound(localAddress) =>
        // The socket is genuinely bound and accepting connections from this point on. Report Listening
        // immediately — do not gate readiness on the cosmetic question of which address to advertise to peers.
        advertisedAddressOverride match
          case Some(override_) =>
            reportListening(ctx, nodeStatusHolder, localAddress, new InetSocketAddress(override_, localAddress.getPort))
            listening(ctx, nodeStatusHolder, peerManager, blacklist, localAddress)
          case None if localAddress.getAddress.isAnyLocalAddress =>
            // Provisional advertised address is the bound (wildcard) address itself — refined below once
            // detection (or its timeout) resolves. Status is already Listening; nothing downstream waits on this.
            reportListening(ctx, nodeStatusHolder, localAddress, localAddress)
            scheduleIpDetection(ctx, ipDetector)
            listening(ctx, nodeStatusHolder, peerManager, blacklist, localAddress)
          case None =>
            reportListening(
              ctx,
              nodeStatusHolder,
              localAddress,
              new InetSocketAddress(localAddress.getAddress, localAddress.getPort)
            )
            listening(ctx, nodeStatusHolder, peerManager, blacklist, localAddress)

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

  /** Runs `ipDetector()` off the dispatcher thread, bounded by [[IpDetectionTimeout]], and pipes the result back as
    * `DetectedIP`. Fire-and-forget from the caller's perspective — `serverStatus` is already `Listening` before this is
    * called; this only ever refines the advertised host.
    */
  private def scheduleIpDetection(ctx: ActorContext[Command], ipDetector: () => Option[InetAddress]): Unit =
    given ec: scala.concurrent.ExecutionContext = ctx.executionContext
    val detection = Future(ipDetector())
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

  private def listening(
      ctx: ActorContext[Command],
      nodeStatusHolder: AtomicReference[NodeStatus],
      peerManager: TypedActorRef[PeerManagerActor.Command],
      blacklist: Blacklist,
      localAddress: InetSocketAddress
  ): Behavior[Command] =
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
          "External IP detection failed or timed out (STUN/HTTP/interface all unavailable, or exceeded {}); " +
            "advertising loopback — inbound peers on other hosts may not reach this node",
          IpDetectionTimeout
        )
        refineAdvertisedAddress(ctx, nodeStatusHolder, localAddress, InetAddress.getLoopbackAddress)
        Behaviors.same
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
  case class StartServer(address: InetSocketAddress, advertisedAddress: Option[InetAddress] = None) extends Command
  private[network] case class DetectedIP(ip: Option[InetAddress]) extends Command

  // Internal wrappers lifting Classic Tcp.Event messages (delivered via the TcpEventBridge) into the typed ADT.
  private[network] case class TcpBound(localAddress: InetSocketAddress) extends Command
  private[network] case class TcpCommandFailed(bind: Bind) extends Command
  private[network] case class TcpConnected(connection: ActorRef, remoteAddress: InetSocketAddress) extends Command
