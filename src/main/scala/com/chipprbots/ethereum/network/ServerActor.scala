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

import scala.concurrent.Future
import scala.util.Failure
import scala.util.Success

import org.bouncycastle.util.encoders.Hex

import com.chipprbots.ethereum.blockchain.sync.Blacklist
import com.chipprbots.ethereum.utils.NodeStatus
import com.chipprbots.ethereum.utils.ServerStatus

object ServerActor:

  /** Behavior factory for the Typed ServerActor.
    *
    * The Classic state machine (`receive` → `waitingForBindingResult` → `waitingForIpDetection` → `listening`) is
    * expressed as a set of named `Behavior[Command]` functions, with the previously-mutable `advertisedAddressOverride`
    * threaded through the transition.
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
    behavior(nodeStatusHolder, peerManager, blacklist, tcpManagerRef = None)

  /** Test entry point: injects a TCP manager ref (a TestProbe) to avoid real port binding. */
  def testApply(
      nodeStatusHolder: AtomicReference[NodeStatus],
      peerManager: TypedActorRef[PeerManagerActor.Command],
      blacklist: Blacklist,
      tcpManager: ActorRef
  ): Behavior[Command] =
    behavior(nodeStatusHolder, peerManager, blacklist, tcpManagerRef = Some(tcpManager))

  private def behavior(
      nodeStatusHolder: AtomicReference[NodeStatus],
      peerManager: TypedActorRef[PeerManagerActor.Command],
      blacklist: Blacklist,
      tcpManagerRef: Option[ActorRef]
  ): Behavior[Command] =
    Behaviors.setup { ctx =>
      val classicSystem = ctx.system.toClassic
      // Tests supply their own ActorRef (TestProbe) to avoid real port binding.
      val tcpManager: ActorRef = tcpManagerRef.getOrElse(IO(Tcp)(classicSystem))

      // Classic bridge: the `Bind` handler that the TCP extension notifies of Bound/CommandFailed/Connected.
      // Captures sender() for Connected (the connection actor) before lifting into the typed Command ADT.
      val tcpBridge: ActorRef =
        ctx.toClassic.actorOf(Props(new TcpEventBridge(ctx.self)), "tcp-event-bridge")

      initial(ctx, nodeStatusHolder, peerManager, blacklist, tcpManager, tcpBridge)
    }

  private def initial(
      ctx: ActorContext[Command],
      nodeStatusHolder: AtomicReference[NodeStatus],
      peerManager: TypedActorRef[PeerManagerActor.Command],
      blacklist: Blacklist,
      tcpManager: ActorRef,
      tcpBridge: ActorRef
  ): Behavior[Command] =
    Behaviors.receiveMessagePartial { case StartServer(address, advertisedAddress, detectionMode) =>
      tcpManager ! Bind(tcpBridge, address)
      waitingForBindingResult(ctx, nodeStatusHolder, peerManager, blacklist, advertisedAddress, detectionMode)
    }

  private def waitingForBindingResult(
      ctx: ActorContext[Command],
      nodeStatusHolder: AtomicReference[NodeStatus],
      peerManager: TypedActorRef[PeerManagerActor.Command],
      blacklist: Blacklist,
      advertisedAddressOverride: Option[InetAddress],
      detectionMode: DetectionMode
  ): Behavior[Command] =
    Behaviors.receiveMessagePartial {
      case TcpBound(localAddress) =>
        advertisedAddressOverride match
          case Some(override_) =>
            finishBinding(ctx, nodeStatusHolder, peerManager, blacklist, localAddress, override_)
          case None if localAddress.getAddress.isAnyLocalAddress =>
            // ExternalIPDetector.detect() can block up to ~13s — run it off the dispatcher thread.
            ctx.pipeToSelf(Future(ExternalIPDetector.detect(detectionMode))(ctx.executionContext)) {
              case Success(ip) => DetectedIP(ip)
              case Failure(_)  => DetectedIP(None)
            }
            waitingForIpDetection(ctx, nodeStatusHolder, peerManager, blacklist, localAddress)
          case None =>
            finishBinding(ctx, nodeStatusHolder, peerManager, blacklist, localAddress, localAddress.getAddress)

      case TcpCommandFailed(b) =>
        ctx.log.warn("Binding to {} failed", b.localAddress)
        Behaviors.stopped
    }

  private def waitingForIpDetection(
      ctx: ActorContext[Command],
      nodeStatusHolder: AtomicReference[NodeStatus],
      peerManager: TypedActorRef[PeerManagerActor.Command],
      blacklist: Blacklist,
      localAddress: InetSocketAddress
  ): Behavior[Command] =
    Behaviors.receiveMessagePartial {
      case DetectedIP(Some(ip)) =>
        ctx.log.info("External IP detected for advertisement: {}", ip.getHostAddress)
        finishBinding(ctx, nodeStatusHolder, peerManager, blacklist, localAddress, ip)

      case DetectedIP(None) =>
        ctx.log.warn(
          "External IP detection failed (UPnP/STUN/HTTP/interface all unavailable, or detection mode is 'none'); " +
            "advertising loopback — inbound peers on other hosts may not reach this node. Set " +
            "fukuii.network.server-address.advertised-address to advertise a specific reachable address, or " +
            "adjust fukuii.network.server-address.external-ip-detection (none|upnp|full) to change the detection strategy."
        )
        finishBinding(
          ctx,
          nodeStatusHolder,
          peerManager,
          blacklist,
          localAddress,
          InetAddress.getLoopbackAddress
        )

      case TcpCommandFailed(b) =>
        ctx.log.warn("Binding to {} failed during IP detection", b.localAddress)
        Behaviors.stopped
    }

  private def finishBinding(
      ctx: ActorContext[Command],
      nodeStatusHolder: AtomicReference[NodeStatus],
      peerManager: TypedActorRef[PeerManagerActor.Command],
      blacklist: Blacklist,
      localAddress: InetSocketAddress,
      advertisedHost: InetAddress
  ): Behavior[Command] =
    val advertisedAddress = new InetSocketAddress(advertisedHost, localAddress.getPort)
    ctx.log.info("Listening on {}", localAddress)
    ctx.log.info(
      "Node address: enode://{}@{}:{}",
      Hex.toHexString(nodeStatusHolder.get().nodeId),
      getHostName(advertisedHost),
      advertisedAddress.getPort
    )
    nodeStatusHolder.getAndUpdate(_.copy(serverStatus = ServerStatus.Listening(advertisedAddress)))
    listening(ctx, peerManager, blacklist)

  private def listening(
      ctx: ActorContext[Command],
      peerManager: TypedActorRef[PeerManagerActor.Command],
      blacklist: Blacklist
  ): Behavior[Command] =
    Behaviors.receiveMessagePartial { case TcpConnected(connection, remoteAddress) =>
      val addr = remoteAddress.getAddress
      val isLocal = addr.isLoopbackAddress || addr.isSiteLocalAddress
      if !isLocal && blacklist.isBlacklisted(PeerManagerActor.PeerAddress(remoteAddress.getHostString)) then
        ctx.log.debug("Dropping inbound TCP from blacklisted {}", remoteAddress.getHostString)
        connection ! Close
      else peerManager ! PeerManagerActor.HandlePeerConnectionCmd(connection, remoteAddress)
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
  case class StartServer(
      address: InetSocketAddress,
      advertisedAddress: Option[InetAddress] = None,
      detectionMode: DetectionMode = DetectionMode.Full
  ) extends Command
  private[network] case class DetectedIP(ip: Option[InetAddress]) extends Command

  // Internal wrappers lifting Classic Tcp.Event messages (delivered via the TcpEventBridge) into the typed ADT.
  private[network] case class TcpBound(localAddress: InetSocketAddress) extends Command
  private[network] case class TcpCommandFailed(bind: Bind) extends Command
  private[network] case class TcpConnected(connection: ActorRef, remoteAddress: InetSocketAddress) extends Command
