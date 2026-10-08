package com.chipprbots.ethereum.network

import java.net.InetSocketAddress
import java.net.URI
import java.util.Collections.newSetFromMap

import org.apache.pekko.actor.ActorRef
import org.apache.pekko.actor.PoisonPill
import org.apache.pekko.actor.Scheduler
import org.apache.pekko.actor.typed
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.SupervisorStrategy
import org.apache.pekko.actor.typed.scaladsl.ActorContext as TypedActorContext
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.util.ByteString
import org.apache.pekko.util.Timeout

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import cats.syntax.parallel.*

import scala.collection.mutable
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

import org.bouncycastle.util.encoders.Hex

import com.chipprbots.ethereum.blockchain.sync.Blacklist
import com.chipprbots.ethereum.blockchain.sync.Blacklist.BlacklistId
import com.chipprbots.ethereum.network.PeerActor.Status.Handshaked
import com.chipprbots.ethereum.network.PeerEventBusActor.*
import com.chipprbots.ethereum.network.discovery.DiscoveryConfig
import com.chipprbots.ethereum.network.discovery.Node
import com.chipprbots.ethereum.network.discovery.PeerDiscoveryManager
import com.chipprbots.ethereum.network.handshaker.Handshaker
import com.chipprbots.ethereum.network.handshaker.Handshaker.HandshakeResult
import com.chipprbots.ethereum.network.p2p.MessageSerializable
import com.chipprbots.ethereum.network.p2p.messages.Capability
import com.chipprbots.ethereum.network.p2p.messages.WireProtocol.Disconnect
import com.chipprbots.ethereum.network.rlpx.AuthHandshaker
import com.chipprbots.ethereum.network.rlpx.RLPxConnectionHandler.RLPxConfiguration

object PeerManagerActor:

  // =========================================================================
  // Command ADT — internal Typed dispatch protocol.
  //
  // NOT sealed: the discovery/known-nodes wire messages that the core schedules to itself
  // (KnownNodesManager.KnownNodes, PeerDiscoveryManager.RandomNodeInfo/DiscoveredNodesInfo)
  // are public companion-object messages from other actors and arrive at the core via tell.
  // Each behavior matches exhaustively with a trailing `case _` fall-through, so openness
  // costs no correctness.
  // =========================================================================

  trait Command

  // 8 ask-paths (typed reply channels):
  final case class GetPeersCmd(replyTo: typed.ActorRef[Peers]) extends Command
  final case class DisconnectPeerByIdCmd(peerId: PeerId, replyTo: typed.ActorRef[DisconnectPeerResponse])
      extends Command
  final case class AddToBlacklistCmd(req: AddToBlacklistRequest, replyTo: typed.ActorRef[AddToBlacklistResponse])
      extends Command
  final case class RemoveFromBlacklistCmd(
      req: RemoveFromBlacklistRequest,
      replyTo: typed.ActorRef[RemoveFromBlacklistResponse]
  ) extends Command
  final case class AddMaintainedPeerCmd(uri: URI, replyTo: typed.ActorRef[AddMaintainedPeerResponse]) extends Command
  final case class AddTrustedPeerCmd(uri: URI, replyTo: typed.ActorRef[AddTrustedPeerResponse]) extends Command
  final case class RemoveTrustedPeerCmd(nodeId: String, replyTo: typed.ActorRef[RemoveTrustedPeerResponse])
      extends Command
  final case class SetMaxPeersCmd(n: Int, replyTo: typed.ActorRef[SetMaxPeersResponse]) extends Command

  // Fire-and-forget wire messages forwarded by the shell:
  case object StartConnectingCmd extends Command
  final case class HandlePeerConnectionCmd(connection: ActorRef, remoteAddress: InetSocketAddress) extends Command

  /** Dial `uri`. `explicit` marks an operator request (net_connectToPeer, configured snap-server peers): it bypasses
    * the wrong-network dial exclusion (#88), logged at INFO. Internal dial paths (known nodes, discovery) leave it
    * false.
    */
  final case class ConnectToPeerCmd(uri: URI, explicit: Boolean = false) extends Command
  final case class RemoveMaintainedPeerCmd(nodeId: String) extends Command
  final case class DisconnectPeerFireAndForgetCmd(peerId: PeerId) extends Command
  final case class SendMessageCmd(message: MessageSerializable, peerId: PeerId) extends Command

  /** Outgoing slots above `maxOutgoingPeers` that remembered good snap peers may use (spec 012). */
  val SnapGoodPeersReservedSlots: Int = 2

  /** SNAP data served by one peer since the last report (spec 012). */
  final case class SnapServed(responses: Int, bytes: Long)

  /** Periodic report from NetworkPeerManagerActor of peers that answered SNAP requests with data. */
  final case class SnapServedReportCmd(served: Map[PeerId, SnapServed]) extends Command

  /** Self-scheduled: (re-)dial the remembered good snap peers that are not connected. */
  private case object RedialSnapGoodPeers extends Command
  final case class PeerClosedConnectionCmd(peerHostAddress: String, reason: Long) extends Command

  /** Sent by NetworkPeerManagerActor for a handshaked peer whose head is still genesis after the grace period while our
    * own chain is past genesis (spec 011). Policy (maintained/trusted exemption, disconnect, dial exclusion) is applied
    * here, next to the wrong-network exclusion it reuses.
    */
  final case class EvictGenesisHeadPeerCmd(peerId: PeerId, clientId: String) extends Command

  /** A [[PeerEventBusActor.PeerEvent]] delivered to the shell (the subscriber) and forwarded to the core. */
  final case class PeerEventReceived(ev: PeerEvent) extends Command

  // Reply from KnownNodesManager (Typed ask), mapped back to the actor thread as a Command.
  final private case class KnownNodesReceived(nodes: Set[URI]) extends Command

  /** A Typed ask to KnownNodesManager timed out or failed. Known-node bootstrap is best-effort; the actor starts
    * connecting to discovery nodes, so a missed known-nodes response is non-fatal.
    */
  private case object KnownNodesFailed extends Command

  // Replies from the (now-Typed) PeerDiscoveryManager, delivered by the `context.ask` mapping.
  // private[ethereum] so integration tests outside the `network` package can inject discovery results directly.
  final private[ethereum] case class DiscoveredNodesReceived(nodes: Set[Node]) extends Command
  final private[ethereum] case class RandomNodeReceived(node: Node) extends Command

  /** A typed ask to PeerDiscoveryManager timed out or failed. Discovery is best-effort; the next scan / demand trigger
    * re-requests, so this is logged and otherwise ignored.
    */
  private case object DiscoveryFailed extends Command

  /** Self-scheduled trigger to re-request the discovered-nodes set. Posted by the periodic discovery timer (which runs
    * off the actor thread) so that the `context.ask` itself always executes on the actor thread.
    */
  private case object RequestDiscoveredNodes extends Command

  // Self-scheduled / internal messages:
  private case object RefreshPeerStatuses extends Command
  private case object SchedulePruneIncomingPeers extends Command
  final private case class PruneIncomingPeers(stats: PeerStatisticsActor.StatsForAll) extends Command
  final private case class PeerStatusCacheUpdated(statuses: Map[Peer, PeerActor.Status]) extends Command
  final private case class PruneStatsFailed(ex: Throwable) extends Command
  final private case class StatusRefreshFailed(ex: Throwable) extends Command

  /** Death-watch notification for a spawned PeerActor (replaces Classic Terminated). */
  final private case class PeerTerminated(ref: typed.ActorRef[PeerActor.Command]) extends Command

  // =========================================================================
  // Behaviour factory + implementation
  // =========================================================================

  // scalastyle:off parameter.number method.length
  def behavior(
      peerEventBus: typed.ActorRef[PeerEventBusActor.Command],
      peerDiscoveryManager: typed.ActorRef[PeerDiscoveryManager.Command],
      peerConfiguration: PeerConfiguration,
      knownNodesManager: typed.ActorRef[KnownNodesManager.Command],
      peerStatistics: typed.ActorRef[PeerStatisticsActor.Command],
      peerFactory: (TypedActorContext[Command], InetSocketAddress, Boolean) => typed.ActorRef[PeerActor.Command],
      discoveryConfig: DiscoveryConfig,
      blacklist: Blacklist,
      externalSchedulerOpt: Option[Scheduler] = None,
      snapGoodPeers: Option[SnapGoodPeers] = None
  ): Behavior[Command] =
    Behaviors.setup { context =>
      new Impl(
        peerEventBus,
        peerDiscoveryManager,
        peerConfiguration,
        knownNodesManager,
        peerStatistics,
        peerFactory,
        discoveryConfig,
        blacklist,
        externalSchedulerOpt,
        snapGoodPeers,
        context
      ).waitingForStart()
    }
  // scalastyle:on parameter.number method.length

  // scalastyle:off number.of.methods
  final private class Impl(
      peerEventBus: typed.ActorRef[PeerEventBusActor.Command],
      peerDiscoveryManager: typed.ActorRef[PeerDiscoveryManager.Command],
      peerConfiguration: PeerConfiguration,
      knownNodesManager: typed.ActorRef[KnownNodesManager.Command],
      peerStatistics: typed.ActorRef[PeerStatisticsActor.Command],
      peerFactory: (TypedActorContext[Command], InetSocketAddress, Boolean) => typed.ActorRef[PeerActor.Command],
      discoveryConfig: DiscoveryConfig,
      val blacklist: Blacklist,
      externalSchedulerOpt: Option[Scheduler],
      snapGoodPeers: Option[SnapGoodPeers],
      context: TypedActorContext[Command]
  ):

    private val log = context.log
    implicit private val ec: scala.concurrent.ExecutionContext = context.executionContext
    // CE3: Using global IORuntime for actor operations
    implicit private val ioRuntime: IORuntime = IORuntime.global

    private def scheduler: Scheduler = externalSchedulerOpt.getOrElse(context.system.classicSystem.scheduler)

    // Timeout for the typed asks to KnownNodesManager and PeerDiscoveryManager. Both are best-effort, so a lapsed ask is
    // simply logged (DiscoveryFailed) and retried by the next scan / connection-demand trigger.
    implicit private val discoveryAskTimeout: Timeout = Timeout(peerConfiguration.updateNodesInterval)

    /** Typed ask to KnownNodesManager for the persisted known-node set. The mapped result lands back on the actor
      * thread as [[KnownNodesReceived]] (success) or [[KnownNodesFailed]] (timeout/failure).
      */
    private def requestKnownNodes(): Unit =
      context.ask[KnownNodesManager.Command, KnownNodesManager.KnownNodes](
        knownNodesManager,
        KnownNodesManager.GetKnownNodesReq(_)
      ) {
        case scala.util.Success(kn) => KnownNodesReceived(kn.nodes)
        case scala.util.Failure(_)  => KnownNodesFailed
      }

    /** Typed ask to the Typed PeerDiscoveryManager for the current discovered-nodes set. The mapped result lands back
      * on the actor thread as [[DiscoveredNodesReceived]] (success) or [[DiscoveryFailed]] (timeout/failure).
      */
    private def requestDiscoveredNodes(): Unit =
      context.ask[PeerDiscoveryManager.Command, PeerDiscoveryManager.DiscoveredNodesInfo](
        peerDiscoveryManager,
        PeerDiscoveryManager.GetDiscoveredNodesInfoReq(_)
      ) {
        case scala.util.Success(info) => DiscoveredNodesReceived(info.nodes)
        case scala.util.Failure(_)    => DiscoveryFailed
      }

    /** Typed ask to the Typed PeerDiscoveryManager for a single random node. The mapped result lands back on the actor
      * thread as [[RandomNodeReceived]] (success) or [[DiscoveryFailed]] (timeout/failure).
      */
    private def requestRandomNode(): Unit =
      context.ask[PeerDiscoveryManager.Command, PeerDiscoveryManager.RandomNodeInfo](
        peerDiscoveryManager,
        PeerDiscoveryManager.GetRandomNodeInfoReq(_)
      ) {
        case scala.util.Success(info) => RandomNodeReceived(info.node)
        case scala.util.Failure(_)    => DiscoveryFailed
      }

    // HERALD-2 #2: the core's own subscriber ref for PeerEventBus events. Spawned PeerActors publish
    // PeerHandshakeSuccessful to the event bus, which delivers it here as PeerEventReceived.
    private val peerEventAdapter: typed.ActorRef[PeerEvent] =
      context.messageAdapter[PeerEvent](PeerEventReceived(_))

    // Subscribe the core to the handshake event of any peer.
    peerEventBus ! SubscribeCmd(SubscriptionClassifier.PeerHandshaked, peerEventAdapter)
    // ...and to wrong-network handshake failures, so their node IDs stop being re-dialled (#88).
    peerEventBus ! SubscribeCmd(SubscriptionClassifier.WrongNetworkPeersClassifier, peerEventAdapter)

    /** Maximum number of blacklisted nodes will never be larger than number of peers provided by discovery Discovery
      * provides remote nodes from all networks (ETC,ETH, Mordor etc.) only during handshake we learn that some of the
      * remote nodes are not compatible that's why we mark them as useless (blacklist them).
      *
      * The number of nodes in the current discovery is unlimited, but a guide may be the size of the routing table: one
      * bucket for each bit in the hash of the public key, times the bucket size.
      */
    private val maxBlacklistedNodes: Int = 32 * 8 * discoveryConfig.kademliaBucketSize

    private val triedNodes: mutable.Set[ByteString] = lruSet[ByteString](maxBlacklistedNodes)

    /** Node IDs whose STATUS named another network or genesis (#88). On Sepolia 94 of 133 handshakes were such peers
      * (networkIds 9745, 137, 100, 1, ...), and the same node IDs were re-dialled within minutes: the soft-tier IP
      * blacklist for `UselessPeer` is minutes long by design (it must not exile peers that merely rejected us). A node
      * on another network will not change networks, so it is excluded by node ID for
      * `peerConfiguration.wrongNetworkExclusionDuration` (24 h default). Node ID, not IP, so a good node behind the
      * same NAT is unaffected. ForkId rejections never land here.
      */
    private val wrongNetworkNodes = new WrongNetworkExclusions(maxBlacklistedNodes)

    /** Every live PeerActor whose PeerHandshakeSuccessful we processed: its handshake peer ID and node ID. Subscribers
      * (NetworkPeerManagerActor, PendingTransactionsManager, ...) register a peer under that ID on the handshake event
      * whether or not we promote it, so a rejected handshake (excluded node, TooManyPeers, AlreadyConnected) must still
      * end with PeerDisconnected under that ID, or each crawler redial leaves a permanent entry behind. An entry lives
      * until the ref terminates, so the map is bounded by the number of live peer actors.
      */
    private val handshakeIdsByRef =
      mutable.Map.empty[typed.ActorRef[PeerActor.Command], (PeerId, ByteString)]

    /** True if a live connection other than `ref` is known under `nodeId`, promoted or not. */
    private def nodeIdOwnedElsewhere(
        nodeId: ByteString,
        ref: typed.ActorRef[PeerActor.Command],
        connectedPeers: ConnectedPeers
    ): Boolean =
      connectedPeers.hasOtherWithNodeId(nodeId, ref) ||
        handshakeIdsByRef.exists { case (r, (_, nid)) => r != ref && nid == nodeId && connectedPeers.isTracked(r) }

    private def isExcludedWrongNetwork(nodeId: ByteString): Boolean =
      wrongNetworkNodes.isExcluded(nodeId, System.currentTimeMillis())

    /** In-process cache of peer statuses, updated reactively and via scheduled refresh. This allows GetPeers to return
      * immediately without querying individual peer actors.
      */
    private var peerStatusCache: Map[PeerId, PeerActor.Status] = Map.empty

    /** Peers that should always remain connected. On disconnect, a reconnect is scheduled after 30s. Keyed by hex node
      * ID (the userInfo portion of the enode URI), matching PeerId.value for handshaked peers. Mirrors Besu's
      * DefaultP2PNetwork.maintainedPeers set.
      */
    private var maintainedPeersByNodeId: Map[String, URI] = Map.empty

    /** Trusted peers bypass the max-peer limit and are always accepted. core-geth reference: p2p/server.go — trusted
      * map[enode.ID]bool in run loop. Keyed by lowercase hex node ID.
      */
    private var trustedPeersByNodeId: Set[String] = Set.empty

    /** Tracks outbound peer actors that were spawned for maintained peers but have not yet completed the ETH handshake.
      * When a Terminated fires with no matching PeerId in connectedPeers (pre-handshake death), this map identifies the
      * URI so the reconnect timer can be scheduled. Cleared on successful handshake or actor termination.
      */
    private val pendingMaintainedConnections: mutable.Map[typed.ActorRef[PeerActor.Command], URI] = mutable.Map.empty

    /** Consecutive pre-handshake TCP failures per remote IP. After 5 failures the IP is blacklisted with exponential
      * backoff (5→10→20→30 min). Cleared on successful handshake to avoid blacklisting peers that recovered.
      */
    private val consecutiveTcpFailures: mutable.Map[String, Int] = mutable.Map.empty

    /** Hosts (remote IP strings) of peers that have completed an ETH handshake advertising snap/1 capability. On
      * peer-scarce networks (ETC mainnet: 1-3 snap servers) snap peers are the only source of state, yet they get
      * timed-blacklisted for soft, peer-initiated reasons (`TooManyPeers`, `UselessPeer`, `TcpSubsystemError`) exactly
      * like any other peer — one disconnect locks out ~50% of state-download capacity for `shortBlacklistDuration`. We
      * remember which hosts are snap-capable so the blacklist path can give them a much shorter fixed backoff instead
      * of exiling them. Protocol violations (`BreachOfProtocol` etc.) still get the permanent ban — the snap exemption
      * only relaxes the *soft* tier, never swallows a genuine breach.
      */
    private val snapCapableHosts: mutable.Set[String] = mutable.Set.empty

    /** Per-host count of lenient (snap-exempt) soft blacklists applied since the last successful handshake. Caps the
      * flap rate: a snap host that keeps soft-disconnecting gets the short backoff up to
      * [[PeerManagerActor.MaxSnapLenientBlacklists]] times, after which it falls back to the normal short duration.
      * Reset to 0 whenever the host completes a fresh handshake (it proved itself useful again).
      */
    private val snapLenientBlacklists: mutable.Map[String, Int] = mutable.Map.empty

    /** Runtime max-outgoing-peers override set by admin_maxPeers. core-geth reference: eth/api_admin.go MaxPeers — sets
      * handler.maxPeers + p2pServer.MaxPeers.
      */
    private var maxOutgoingPeersOverride: Option[Int] = None

    private def effectiveMaxOutgoing: Int =
      maxOutgoingPeersOverride.getOrElse(peerConfiguration.maxOutgoingPeers)

    implicit private class ConnectedPeersOps(connectedPeers: ConnectedPeers):

      /** Number of new connections the node should try to open at any given time. Uses effectiveMaxOutgoing so
        * admin_maxPeers overrides take effect.
        */
      def outgoingConnectionDemand: Int =
        if connectedPeers.outgoingHandshakedPeersCount >= peerConfiguration.minOutgoingPeers then 0
        else effectiveMaxOutgoing - connectedPeers.outgoingPeersCount

      def canConnectTo(node: Node): Boolean =
        val socketAddress = node.tcpSocketAddress
        val alreadyConnected =
          connectedPeers.isConnectionHandled(socketAddress) ||
            connectedPeers.hasHandshakedWith(node.id)

        !alreadyConnected && !blacklist.isBlacklisted(PeerAddress(socketAddress.getHostString)) &&
        !isExcludedWrongNetwork(node.id)

    // -----------------------------------------------------------------------
    // State 1: waitingForStart (stash everything until StartConnecting)
    // -----------------------------------------------------------------------

    def waitingForStart(): Behavior[Command] =
      Behaviors.withStash(100) { stash =>
        Behaviors.receiveMessage {
          case StartConnectingCmd =>
            scheduleNodesUpdate()
            schedulePeerStatusRefresh()
            requestKnownNodes()
            // Also request discovered/bootstrap nodes immediately. On a fresh node, KnownNodes is empty
            // but bootstrap/static nodes are available as alreadyDiscoveredNodes in PeerDiscoveryManager.
            // Without this, the first connection attempt waits for updateNodesInitialDelay.
            // Core-geth dials bootstrap nodes at t+0; we should too.
            requestDiscoveredNodes()
            // Spec 012: remembered snap servers are dialled first, ahead of the discovery scan.
            snapGoodPeers.foreach { store =>
              store.load(System.currentTimeMillis())
              log.info("SNAP_GOOD_PEERS: loaded {} remembered snap peers from {}", store.size, store.config.file)
            }
            context.self ! RedialSnapGoodPeers
            stash.unstashAll(listening(ConnectedPeers.empty))
          case other =>
            stash.stash(other)
            Behaviors.same
        }
      }

    private def scheduleNodesUpdate(): Unit =
      // The timer runs off the actor thread, so it cannot call `context.ask` directly. Post a self-message instead; the
      // actor thread handles RequestDiscoveredNodes by issuing the typed ask.
      scheduler.scheduleWithFixedDelay(
        peerConfiguration.updateNodesInitialDelay,
        peerConfiguration.updateNodesInterval
      )(() => context.self ! RequestDiscoveredNodes)(ec)

    private def schedulePeerStatusRefresh(): Unit =
      scheduler.scheduleWithFixedDelay(10.seconds, 10.seconds)(() => context.self ! RefreshPeerStatuses)(ec)

    // -----------------------------------------------------------------------
    // State 2: listening (the only stateful behavior — threads ConnectedPeers)
    // -----------------------------------------------------------------------

    private def listening(connectedPeers: ConnectedPeers): Behavior[Command] =
      Behaviors.receiveMessage { msg =>
        handleCommonMessages(connectedPeers, msg)
          .orElse(handleConnections(connectedPeers, msg))
          .orElse(handleNewNodesToConnectMessages(connectedPeers, msg))
          .orElse(handlePruning(connectedPeers, msg))
          .getOrElse(Behaviors.same)
      }

    private def handleNewNodesToConnectMessages(
        connectedPeers: ConnectedPeers,
        msg: Command
    ): Option[Behavior[Command]] =
      msg match
        case KnownNodesReceived(nodes) =>
          val nodesToConnect = nodes.take(peerConfiguration.maxOutgoingPeers)
          if nodesToConnect.nonEmpty then
            log.debug("Trying to connect to {} known nodes", nodesToConnect.size)
            nodesToConnect.foreach(n => context.self ! ConnectToPeerCmd(n))
          else log.debug("The known nodes list is empty")
          Some(Behaviors.same)

        case RandomNodeReceived(node) =>
          maybeConnectToRandomNode(connectedPeers, node)
          Some(Behaviors.same)

        case DiscoveredNodesReceived(nodes) =>
          Some(maybeConnectToDiscoveredNodes(connectedPeers, nodes))

        case RequestDiscoveredNodes =>
          requestDiscoveredNodes()
          Some(Behaviors.same)

        case RedialSnapGoodPeers =>
          snapGoodPeers.foreach(store => redialSnapGoodPeers(store, connectedPeers))
          Some(Behaviors.same)

        case KnownNodesFailed =>
          log.debug("KnownNodesManager ask timed out or failed; starting with empty known-nodes list")
          Some(Behaviors.same)

        case DiscoveryFailed =>
          log.debug("Discovery ask to PeerDiscoveryManager timed out or failed; will retry on next scan")
          Some(Behaviors.same)

        case _ => None

    /** Dial remembered good snap peers that are not connected, best first. An attempt without a later handshake counts
      * against the entry (it is dropped past `maxFailedDials`); wrong-network exclusions and IP blacklists are
      * respected (skipped without counting). Re-arms itself while any candidate is still unconnected.
      */
    /** `uri.getHost` keeps the brackets of an IPv6 literal; InetSocketAddress and the blacklist want it bare. */
    private def bareHost(uri: URI): String = uri.getHost.stripPrefix("[").stripSuffix("]")

    private def redialSnapGoodPeers(store: SnapGoodPeers, connectedPeers: ConnectedPeers): Unit =
      val now = System.currentTimeMillis()
      val pruned = store.prune(now)
      val pending = store.candidates(now).filterNot { case (id, _) =>
        connectedPeers.hasHandshakedWith(ByteString(Hex.decode(id)))
      }
      var dialled = 0
      pending.foreach { case (id, uri) =>
        val nodeId = ByteString(Hex.decode(id))
        val host = bareHost(uri)
        // A strike is counted only when connectWith will really dial: not excluded, not blacklisted, not already
        // handled/pending, and within the (reserved-budget-extended) outgoing limit. Otherwise a full table would
        // strike every remembered peer without any dial and delete the best servers.
        val handled = connectedPeers.isConnectionHandled(new InetSocketAddress(host, uri.getPort)) ||
          connectedPeers.hasIncomingPendingFromHost(host)
        val hasCapacity = connectedPeers.outgoingPeersCount < effectiveMaxOutgoing + SnapGoodPeersReservedSlots
        if !isExcludedWrongNetwork(nodeId) && !blacklist.isBlacklisted(PeerAddress(host)) && !handled && hasCapacity
        then
          store.recordDialAttempt(id)
          dialled += 1
          log.info("SNAP_GOOD_PEERS: dialling remembered snap peer {}@{}", id.take(16), host)
          context.self ! ConnectToPeerCmd(uri)
      }
      if pruned > 0 || dialled > 0 then store.save()
      if pending.nonEmpty then
        scheduler.scheduleOnce(store.config.redialInterval)(context.self ! RedialSnapGoodPeers)(ec)

    private def maybeConnectToRandomNode(connectedPeers: ConnectedPeers, node: Node): Unit =
      if connectedPeers.outgoingConnectionDemand > 0 then
        if connectedPeers.canConnectTo(node) then
          log.debug(
            "Random node candidate {} accepted (outgoing demand: {}, tried: {}, pending: {})",
            formatNodeForLogs(node),
            connectedPeers.outgoingConnectionDemand,
            triedNodes.size,
            connectedPeers.pendingPeersCount
          )
          triedNodes.add(node.id)
          context.self ! ConnectToPeerCmd(node.toUri)
        else
          log.debug(
            "Random node candidate {} rejected (already connected or blacklisted). Requesting replacement",
            formatNodeForLogs(node)
          )
          requestRandomNode()
      else
        log.debug(
          "Skipping random node {} because outgoing demand is zero (handshaked {}/{})",
          formatNodeForLogs(node),
          connectedPeers.handshakedPeersCount,
          peerConfiguration.maxOutgoingPeers + peerConfiguration.maxIncomingPeers
        )

    private def maybeConnectToDiscoveredNodes(connectedPeers: ConnectedPeers, nodes: Set[Node]): Behavior[Command] =
      val discoveredNodes = nodes
        .filter(connectedPeers.canConnectTo)

      val nodesToConnect = discoveredNodes
        .filterNot(n => triedNodes.contains(n.id)) match
        case seq if seq.size >= connectedPeers.outgoingConnectionDemand =>
          seq.take(connectedPeers.outgoingConnectionDemand)
        case _ => discoveredNodes.take(connectedPeers.outgoingConnectionDemand)

      NetworkMetrics.DiscoveredPeersSize.set(nodes.size)
      NetworkMetrics.BlacklistedPeersSize.set(blacklist.keys.size)
      NetworkMetrics.PendingPeersSize.set(connectedPeers.pendingPeersCount)
      NetworkMetrics.TriedPeersSize.set(triedNodes.size)

      log.debug(
        s"Total number of discovered nodes ${nodes.size}. " +
          s"Total number of connection attempts ${triedNodes.size}, blacklisted ${blacklist.keys.size} nodes. " +
          s"Handshaked ${connectedPeers.handshakedPeersCount}/${peerConfiguration.maxOutgoingPeers + peerConfiguration.maxIncomingPeers}, " +
          s"pending connection attempts ${connectedPeers.pendingPeersCount}. " +
          s"Trying to connect to ${nodesToConnect.size} more nodes."
      )

      if nodesToConnect.nonEmpty then
        log.debug("Trying to connect to {} nodes", nodesToConnect.size)
        nodesToConnect.foreach { n =>
          triedNodes.add(n.id)
          context.self ! ConnectToPeerCmd(n.toUri)
        }
      else log.debug("The nodes list is empty, no new nodes to connect to")

      // Make sure the background lookups keep going and we don't get stuck with 0
      // nodes to connect to until the next discovery scan loop. Only sending 1
      // request so we don't rack up too many pending futures, just trigger a
      // search if needed.
      if connectedPeers.outgoingConnectionDemand > nodesToConnect.size then requestRandomNode()
      Behaviors.same

    private def formatNodeForLogs(node: Node): String =
      val id = Hex.toHexString(node.id.take(6).toArray)
      s"${getHostName(node.addr)}:${node.tcpPort}/$id"

    private def handleConnections(connectedPeers: ConnectedPeers, msg: Command): Option[Behavior[Command]] =
      msg match
        case PeerClosedConnectionCmd(peerAddress, reason) =>
          val isMaintainedPeer = maintainedPeersByNodeId.values.exists(_.getHost == peerAddress)
          if !isMaintainedPeer then
            blacklist.add(
              PeerAddress(peerAddress),
              getBlacklistDuration(reason, peerAddress),
              Blacklist.BlacklistReason.getP2PBlacklistReasonByDescription(Disconnect.reasonToString(reason))
            )
          Some(Behaviors.same)

        case HandlePeerConnectionCmd(connection, remoteAddress) =>
          Some(handleConnection(connection, remoteAddress, connectedPeers))

        case ConnectToPeerCmd(uri, explicit) =>
          Some(connectWith(uri, connectedPeers, explicit))

        case AddMaintainedPeerCmd(uri, replyTo) =>
          // Key by lowercase hex: every lookup (handshake, terminate, prune) uses the lowercase form, so a mixed-case
          // enode would otherwise never be recognised as maintained and would not be re-dialled (#57).
          val nodeId = uri.getUserInfo.toLowerCase
          val wasAdded = !maintainedPeersByNodeId.contains(nodeId)
          maintainedPeersByNodeId = maintainedPeersByNodeId + (nodeId -> uri)
          replyTo ! AddMaintainedPeerResponse(wasAdded)
          peerEventBus ! PublishCmd(PeerEvent.MaintainedPeersChanged(maintainedPeersByNodeId.keySet))
          Some(connectWith(uri, connectedPeers))

        case RemoveMaintainedPeerCmd(nodeId) =>
          maintainedPeersByNodeId = maintainedPeersByNodeId - nodeId.toLowerCase
          peerEventBus ! PublishCmd(PeerEvent.MaintainedPeersChanged(maintainedPeersByNodeId.keySet))
          Some(Behaviors.same)

        // ── Geth-compatible trusted peer / max-peers management ───────────────
        // core-geth references: node/api.go AddTrustedPeer/RemoveTrustedPeer, eth/api_admin.go MaxPeers

        case AddTrustedPeerCmd(uri, replyTo) =>
          val nodeId = uri.getUserInfo.toLowerCase
          trustedPeersByNodeId = trustedPeersByNodeId + nodeId
          replyTo ! AddTrustedPeerResponse(success = true)
          // Attempt connection immediately (like AddMaintainedPeer) so the trusted
          // peer is dialled even if we're currently at the max-peer limit.
          Some(connectWith(uri, connectedPeers))

        case RemoveTrustedPeerCmd(nodeId, replyTo) =>
          // Removes trust but does NOT disconnect the live connection.
          // core-geth: server.RemoveTrustedPeer does not call removePeer.
          trustedPeersByNodeId = trustedPeersByNodeId - nodeId.toLowerCase
          replyTo ! RemoveTrustedPeerResponse(success = true)
          Some(Behaviors.same)

        case SetMaxPeersCmd(n, replyTo) =>
          maxOutgoingPeersOverride = Some(n)
          replyTo ! SetMaxPeersResponse(success = true)
          Some(Behaviors.same)

        case _ => None

    private def getBlacklistDuration(reason: Long, peerAddress: String): FiniteDuration =
      val baseDuration = PeerManagerActor.blacklistDurationForDisconnect(
        reason,
        shortBlacklistDuration = peerConfiguration.shortBlacklistDuration,
        longBlacklistDuration = peerConfiguration.longBlacklistDuration
      )
      // Peer-retention for scarce snap pools: when a snap-capable host disconnects us for a SOFT reason (it maps to the
      // short tier above — never a protocol breach, which stays permanent), give it a brief fixed backoff instead of
      // the full short duration so we can re-dial it within seconds. Bounded by MaxSnapLenientBlacklists to stop a
      // misbehaving peer from flap-looping. Permanent (BreachOfProtocol-tier) bans are left untouched — the exemption
      // must never swallow a genuine breach.
      val isSoftTier = baseDuration == peerConfiguration.shortBlacklistDuration
      if isSoftTier && snapCapableHosts.contains(peerAddress) then
        val lenientCount = snapLenientBlacklists.getOrElse(peerAddress, 0)
        if lenientCount < PeerManagerActor.MaxSnapLenientBlacklists then
          snapLenientBlacklists(peerAddress) = lenientCount + 1
          val snapBackoff = PeerManagerActor.SnapPeerSoftBlacklistDuration
          log.debug(
            s"Snap-capable host $peerAddress soft-disconnected (reason $reason) — lenient backoff $snapBackoff " +
              s"(#${lenientCount + 1}/${PeerManagerActor.MaxSnapLenientBlacklists}) instead of $baseDuration"
          )
          snapBackoff
        else baseDuration
      else baseDuration

    private def handleConnection(
        connection: ActorRef,
        remoteAddress: InetSocketAddress,
        connectedPeers: ConnectedPeers
    ): Behavior[Command] =
      val alreadyConnectedToPeer = connectedPeers.isConnectionHandled(remoteAddress)
      val isPendingPeersNotMaxValue = connectedPeers.incomingPendingPeersCount < peerConfiguration.maxPendingPeers
      // Reject inbound dials from blacklisted addresses. Previously the blacklist was only
      // honored on the outbound (`canConnectTo`) path, so a peer we disconnected for chronic
      // lag would simply re-dial us inbound seconds later and rejoin the handshaked set —
      // making PR #1242's 30-min hold ineffective. Sepolia 2026-05-14: same peer IPs
      // re-connected 25s after being blacklisted for 30 min.
      val isNotBlacklisted = !blacklist.isBlacklisted(PeerAddress(remoteAddress.getHostString))

      val validConnection = for
        validHandler <- validateConnection(
          remoteAddress,
          IncomingConnectionAlreadyHandled(remoteAddress, connection),
          !alreadyConnectedToPeer
        )
        validNumber <- validateConnection(
          validHandler,
          MaxIncomingPendingConnections(connection),
          isPendingPeersNotMaxValue
        )
        _ <- validateConnection(
          validNumber,
          IncomingConnectionBlacklisted(remoteAddress, connection),
          isNotBlacklisted
        )
      yield validNumber

      validConnection match
        case Right(address) =>
          log.debug(
            "Accepting incoming connection from {} (pending={}/{})",
            remoteAddress,
            connectedPeers.incomingPendingPeersCount,
            peerConfiguration.maxPendingPeers
          )
          val (peer, newConnectedPeers) = createPeer(address, incomingConnection = true, connectedPeers)
          // Send with peerEventAdapter as the Classic sender so a reply (in tests, probe.reply(PeerHandshakeSuccessful))
          // routes back to the core as PeerEventReceived. In production the real PeerActor publishes to the event bus.
          // `.toClassic` on the typed ref allows the 2-arg Classic tell (sets sender for test probes).
          peer.ref.toClassic.tell(PeerActor.HandleConnection(connection, remoteAddress), peerEventAdapter.toClassic)
          listening(newConnectedPeers)

        case Left(error) =>
          log.debug("Rejecting incoming connection from {}: {}", remoteAddress, error)
          handleConnectionErrors(error)
          Behaviors.same

    private def connectWith(uri: URI, connectedPeers: ConnectedPeers, explicit: Boolean = false): Behavior[Command] =
      val nodeIdHex = uri.getUserInfo.toLowerCase
      val nodeId = ByteString(Hex.decode(nodeIdHex))
      val host = bareHost(uri)
      val remoteAddress = new InetSocketAddress(host, uri.getPort)

      val alreadyConnectedToPeer =
        connectedPeers.hasHandshakedWith(nodeId) ||
          connectedPeers.isConnectionHandled(remoteAddress) ||
          connectedPeers.hasIncomingPendingFromHost(host)
      // Trusted + maintained peers bypass the max outgoing limit.
      // Besu: DefaultPeerPrivileges.canExceedConnectionLimits checks maintainedPeers set.
      // go-ethereum: trustedConn flag skips maxPeers for trusted only; Fukuii extends this to maintained peers.
      val isMaintainedOrTrusted =
        trustedPeersByNodeId.contains(nodeIdHex) || maintainedPeersByNodeId.contains(nodeIdHex)
      // Remembered good snap peers (spec 012) may use a small reserved budget above the normal limit, so that slots
      // full of crawlers/useless peers cannot keep the proven snap servers out.
      val isRememberedGood = snapGoodPeers.exists(_.contains(nodeIdHex))
      val outgoingLimit =
        effectiveMaxOutgoing + (if isRememberedGood then PeerManagerActor.SnapGoodPeersReservedSlots else 0)
      val isOutgoingPeersNotMaxValue = isMaintainedOrTrusted || connectedPeers.outgoingPeersCount < outgoingLimit

      val validConnection = for
        validHandler <- validateConnection(
          remoteAddress,
          OutgoingConnectionAlreadyHandled(uri),
          !alreadyConnectedToPeer
        )
        validNumber <- validateConnection(validHandler, MaxOutgoingConnections, isOutgoingPeersNotMaxValue)
      yield validNumber

      val excludedWrongNetwork = isExcludedWrongNetwork(nodeId)
      // An operator's explicit request (admin_addPeer via maintained/trusted, net_connectToPeer, snap-server-peers)
      // overrides the exclusion; internal dial paths do not.
      val operatorRequested = isMaintainedOrTrusted || explicit

      validConnection match
        case Right(_) if excludedWrongNetwork && !operatorRequested =>
          // Known-nodes and other internal paths funnel here; discovery is also filtered in canConnectTo.
          log.debug("Not dialling {}: node is excluded as wrong-network", uri)
          Behaviors.same

        case Right(address) =>
          if excludedWrongNetwork then
            log.info(
              "WRONG_NETWORK: dialling excluded node={} on explicit operator request; its last STATUS named another " +
                "network or genesis, so the handshake will likely fail again",
              nodeIdHex.take(16)
            )
          val (peer, newConnectedPeers) = createPeer(address, incomingConnection = false, connectedPeers)
          peer.ref.toClassic.tell(PeerActor.ConnectTo(uri), peerEventAdapter.toClassic)
          if maintainedPeersByNodeId.values.exists(_ == uri) then pendingMaintainedConnections(peer.ref) = uri
          listening(newConnectedPeers)

        case Left(error) =>
          handleConnectionErrors(error)
          Behaviors.same

    private def handleCommonMessages(connectedPeers: ConnectedPeers, msg: Command): Option[Behavior[Command]] =
      msg match
        case GetPeersCmd(replyTo) =>
          // Return cached statuses immediately — no actor asks needed.
          // Cache is updated reactively on connect/disconnect/handshake and via scheduled refresh.
          val cachedPeers = connectedPeers.peers.values.map { peer =>
            val status = peerStatusCache.getOrElse(peer.id, PeerActor.Status.Connecting)
            peer -> status
          }.toMap
          replyTo ! Peers(cachedPeers)
          Some(Behaviors.same)

        case RefreshPeerStatuses =>
          refreshPeerStatusCache(connectedPeers)
          Some(Behaviors.same)

        case PeerStatusCacheUpdated(statuses) =>
          peerStatusCache = statuses.map { case (peer, status) => peer.id -> status }
          Some(Behaviors.same)

        case SendMessageCmd(message, peerId) if connectedPeers.getPeer(peerId).isDefined =>
          connectedPeers.getPeer(peerId).foreach(peer => peer.ref ! PeerActor.SendMessage(message))
          Some(Behaviors.same)

        case DisconnectPeerByIdCmd(peerId, replyTo) =>
          connectedPeers.getPeer(peerId) match
            case Some(peer) =>
              peer.ref ! PeerActor.DisconnectPeer(Disconnect.Reasons.DisconnectRequested)
              replyTo ! DisconnectPeerResponse(disconnected = true)
            case None =>
              replyTo ! DisconnectPeerResponse(disconnected = false)
          Some(Behaviors.same)

        case EvictGenesisHeadPeerCmd(peerId, clientId) =>
          if connectedPeers.getPeer(peerId).isEmpty then
            log.debug("GENESIS_HEAD_EVICT: {} is no longer connected; nothing to evict", peerId.value.take(16))
          connectedPeers.getPeer(peerId).foreach { peer =>
            val nodeIdOpt = peer.nodeId
            val nodeIdHex = nodeIdOpt.map(n => Hex.toHexString(n.toArray)).getOrElse("")
            if maintainedPeersByNodeId.contains(nodeIdHex) || trustedPeersByNodeId.contains(nodeIdHex) then
              log.debug("GENESIS_HEAD_EVICT: skipping maintained/trusted peer {}", nodeIdHex.take(16))
            else
              val duration = peerConfiguration.genesisHeadExclusionDuration
              nodeIdOpt.foreach(n => wrongNetworkNodes.add(n, System.currentTimeMillis() + duration.toMillis))
              log.info(
                "GENESIS_HEAD_EVICT: disconnecting node={} host={} client={}; head still genesis after {}; " +
                  "excluded from dialling for {}",
                nodeIdHex.take(16),
                peer.remoteAddress.getHostString,
                clientId,
                peerConfiguration.genesisHeadEvictionGrace,
                duration
              )
              peer.ref ! PeerActor.DisconnectPeer(Disconnect.Reasons.UselessPeer)
          }
          Some(Behaviors.same)

        case DisconnectPeerFireAndForgetCmd(peerId) =>
          connectedPeers.getPeer(peerId).foreach { peer =>
            peer.ref ! PeerActor.DisconnectPeer(Disconnect.Reasons.DisconnectRequested)
          }
          Some(Behaviors.same)

        case SnapServedReportCmd(served) =>
          snapGoodPeers.foreach { store =>
            val now = System.currentTimeMillis()
            var changed = false
            served.foreach { case (peerId, s) =>
              connectedPeers.getPeer(peerId).foreach { peer =>
                peer.nodeId.filterNot(_ => peer.incomingConnection).foreach { nid =>
                  // 1 per answered request + 1 per 64 KiB: rewards both frequency and volume.
                  val weight = s.responses.toDouble + s.bytes.toDouble / 65536.0
                  store.recordServed(
                    Hex.toHexString(nid.toArray),
                    peer.remoteAddress.getHostString,
                    peer.remoteAddress.getPort,
                    weight,
                    now
                  )
                  changed = true
                }
              }
            }
            if changed then store.save()
          }
          Some(Behaviors.same)

        case AddToBlacklistCmd(req, replyTo) =>
          try
            val duration = req.duration.getOrElse(PeerManagerActor.DefaultPermanentBlacklistDuration)
            val reason = Blacklist.BlacklistReason.getP2PBlacklistReasonByDescription(req.reason)
            blacklist.add(PeerAddress(req.address), duration, reason)
            replyTo ! AddToBlacklistResponse(added = true)
          catch
            case e: Exception =>
              log.error(s"Failed to add address ${req.address} to blacklist", e)
              replyTo ! AddToBlacklistResponse(added = false)
          Some(Behaviors.same)

        case RemoveFromBlacklistCmd(req, replyTo) =>
          try
            blacklist.remove(PeerAddress(req.address))
            replyTo ! RemoveFromBlacklistResponse(removed = true)
          catch
            case e: Exception =>
              log.error(s"Failed to remove address ${req.address} from blacklist", e)
              replyTo ! RemoveFromBlacklistResponse(removed = false)
          Some(Behaviors.same)

        case PeerTerminated(ref) =>
          Some(handleTerminated(ref, connectedPeers))

        case PeerEventReceived(PeerEvent.PeerHandshakeSuccessful(handshakedPeer, handshakeResult)) =>
          Some(handlePeerHandshakeSuccessful(handshakedPeer, handshakeResult, connectedPeers))

        case PeerEventReceived(PeerEvent.PeerOnWrongNetwork(nodeId, remoteHost)) =>
          val nodeIdHex = Hex.toHexString(nodeId.toArray)
          // An operator-configured maintained/trusted peer is the operator's call, not ours.
          if maintainedPeersByNodeId.contains(nodeIdHex) || trustedPeersByNodeId.contains(nodeIdHex) then
            log.warn("Maintained/trusted peer {} ({}) is on a different network or genesis", nodeIdHex, remoteHost)
          else
            val duration = peerConfiguration.wrongNetworkExclusionDuration
            val isNew = wrongNetworkNodes.add(nodeId, System.currentTimeMillis() + duration.toMillis)
            // INFO once per newly excluded node; a still-excluded node can only reappear by dialling us, so DEBUG.
            if isNew then
              log.info(
                "WRONG_NETWORK: excluding node={} host={} from dialling for {} (excluded={})",
                nodeIdHex.take(16),
                remoteHost,
                duration,
                wrongNetworkNodes.size
              )
            else log.debug("WRONG_NETWORK: node={} host={} still excluded", nodeIdHex.take(16), remoteHost)
          Some(Behaviors.same)

        case PeerEventReceived(_) =>
          Some(Behaviors.same)

        case _ => None

    private def handleTerminated(
        ref: typed.ActorRef[PeerActor.Command],
        connectedPeers: ConnectedPeers
    ): Behavior[Command] =
      // Pre-handshake path: if a maintained peer's TCP actor dies before the ETH handshake
      // completes, no PeerId was assigned — the post-handshake reconnect path won't fire.
      pendingMaintainedConnections.remove(ref).foreach { uri =>
        val nodeIdHex = uri.getUserInfo.toLowerCase
        val nodeIdBytes = ByteString(Hex.decode(nodeIdHex))
        if connectedPeers.hasHandshakedWith(nodeIdBytes) then
          log.debug("Maintained peer {} already connected via inbound — skipping pre-handshake reconnect", uri)
        else
          log.debug(
            "Maintained peer {} TCP connection failed pre-handshake — scheduling retry in {}",
            uri,
            peerConfiguration.connectRetryDelay
          )
          scheduler.scheduleOnce(peerConfiguration.connectRetryDelay)(context.self ! ConnectToPeerCmd(uri))(ec)
      }
      // Track TCP-level pre-handshake failures for non-maintained outgoing peers.
      // pendingMaintainedConnections already consumed the ref if it was maintained, so any
      // peer still in outgoingPendingPeers here is a non-maintained discovery peer that failed
      // before the ETH handshake completed (TCP unreachable, TLS failure, etc.).
      connectedPeers.peers.get(PeerId.fromRef(ref)).foreach { peer =>
        if !peer.incomingConnection then
          val ip = peer.remoteAddress.getHostString
          val count = consecutiveTcpFailures.getOrElse(ip, 0) + 1
          consecutiveTcpFailures(ip) = count
          if count >= 5 then
            // Peer-retention: a previously snap-capable host that now flaps at the TCP layer (NAT churn, transient
            // unreachability) must NOT be exiled for 5-30 min — on a 2-snap-peer pool that drops state capacity to
            // zero. Cap its backoff to a short fixed duration so it re-enters the dial rotation quickly. Non-snap
            // hosts keep the full exponential escalation.
            val backoff: FiniteDuration =
              if snapCapableHosts.contains(ip) then PeerManagerActor.SnapPeerSoftBlacklistDuration
              else math.min(30, 5 * (1 << (count - 5))).minutes
            log.info("TCP failure #{} for {} — blacklisting for {}", count, ip, backoff)
            blacklist.add(PeerAddress(ip), backoff, Blacklist.BlacklistReason.TcpSubsystemError)
            consecutiveTcpFailures.remove(ip)
      }
      val (terminatedPeersIds, newConnectedPeers) = connectedPeers.removeTerminatedPeer(ref)
      // A handshake we saw but did not promote: subscribers know it only under its handshake ID, which
      // removeTerminatedPeer (keyed on the pending path ID) never reports.
      handshakeIdsByRef.remove(ref).foreach { case (handshakeId, nodeId) =>
        if !terminatedPeersIds.exists(_ == handshakeId) && !nodeIdOwnedElsewhere(nodeId, ref, newConnectedPeers) then
          log.debug("REJECTED_HANDSHAKE_GONE: publishing PeerDisconnected for {} ref={}", handshakeId, ref)
          peerEventBus ! PublishCmd(PeerEvent.PeerDisconnected(handshakeId))
      }
      terminatedPeersIds.foreach { peerId =>
        peerStatusCache = peerStatusCache - peerId
        // Pending peers have path-based IDs (non-hex). Only handshaked peers have real hex node
        // IDs so we can check whether the winner is still alive in connectedPeers.
        // Gate PeerDisconnected on stillConnected — mirrors Besu's registerDisconnect guard
        // (peer.getConnection().equals(connection)) and go-ethereum's d.peers[id] tracking:
        // when a duplicate connection (the loser of a simultaneous-dial collision) terminates,
        // the winner is still alive in connectedPeers so stillConnected=true. Publishing
        // PeerDisconnected unconditionally was causing NetworkPeerManagerActor to evict the
        // live winner entry, creating an infinite reconnect cycle with core-geth.
        val stillConnected = scala.util
          .Try(ByteString(Hex.decode(peerId.value)))
          .map(newConnectedPeers.hasHandshakedWith)
          .getOrElse(false)
        if stillConnected then
          log.info(
            "DUPLICATE_TERMINATED: suppressing PeerDisconnected for {} — winner still connected, loser ref={} dropped",
            peerId,
            ref
          )
        else
          log.debug("GENUINE_DISCONNECT: publishing PeerDisconnected for {} ref={}", peerId, ref)
          peerEventBus ! PublishCmd(PeerEvent.PeerDisconnected(peerId))
        maintainedPeersByNodeId.get(peerId.value).foreach { uri =>
          if stillConnected then log.debug("Maintained peer {} already connected via winner — skipping reconnect", uri)
          else
            log.debug("Maintained peer {} disconnected — scheduling reconnect in 5s", uri)
            scheduler.scheduleOnce(5.seconds)(context.self ! ConnectToPeerCmd(uri))(ec)
        }
      }
      // Try to replace a lost connection with another one.
      if newConnectedPeers.outgoingConnectionDemand > 0 then requestRandomNode()
      // watchWith death-watch is one-shot per spawned ref; no explicit unwatch needed (the ref is terminated).
      listening(newConnectedPeers)

    private def handlePeerHandshakeSuccessful(
        handshakedPeer: Peer,
        handshakeResult: HandshakeResult,
        connectedPeers: ConnectedPeers
    ): Behavior[Command] =
      val host = handshakedPeer.remoteAddress.getHostString
      consecutiveTcpFailures.remove(host)
      // Track snap-capability per host so the blacklist paths can retain scarce snap peers (see snapCapableHosts).
      // A fresh handshake also proves the host is useful again, so reset its lenient-blacklist flap counter.
      handshakeResult match
        case info: NetworkPeerManagerActor.PeerInfo if info.remoteStatus.supportsSnap =>
          snapCapableHosts += host
          snapLenientBlacklists.remove(host)
        case _ =>
      val isMaintained =
        handshakedPeer.nodeId.exists(nid => maintainedPeersByNodeId.contains(Hex.toHexString(nid.toArray)))
      val isTrusted =
        handshakedPeer.nodeId.exists(nid => trustedPeersByNodeId.contains(Hex.toHexString(nid.toArray)))
      if !connectedPeers.isTracked(handshakedPeer.ref) then
        // The PeerActor published PeerHandshakeSuccessful and then stopped (TCP closed right after STATUS, as the
        // enrscout crawler does) and its termination was processed first: the death-watch notification comes straight
        // here, while the handshake event takes an extra hop through the event bus. Promoting it now would leave a
        // handshaked entry whose actor is gone and whose termination will never arrive again: it held a slot, blocked
        // re-dials of the node ID, and was "evicted" every ~70 s by the genesis-head check with DisconnectPeer going to
        // dead letters (Sepolia v0.9.6).
        log.info(
          "STALE_HANDSHAKE: ignoring handshake of {} from {}: its peer actor has already terminated",
          handshakedPeer.id.value.take(16),
          handshakedPeer.remoteAddress
        )
        // Subscribers that registered the handshake must drop it again, unless another live connection to the same
        // node ID (handshaked, or pending with its handshake seen) owns that peer ID.
        if !handshakedPeer.nodeId.exists(nodeIdOwnedElsewhere(_, handshakedPeer.ref, connectedPeers)) then
          peerEventBus ! PublishCmd(PeerEvent.PeerDisconnected(handshakedPeer.id))
        listening(connectedPeers)
      else
        handshakedPeer.nodeId.foreach(nid => handshakeIdsByRef.update(handshakedPeer.ref, (handshakedPeer.id, nid)))
        handleLiveHandshake(handshakedPeer, isMaintained, isTrusted, host, connectedPeers)

    private def handleLiveHandshake(
        handshakedPeer: Peer,
        isMaintained: Boolean,
        isTrusted: Boolean,
        host: String,
        connectedPeers: ConnectedPeers
    ): Behavior[Command] =
      if handshakedPeer.incomingConnection && !isMaintained && !isTrusted &&
        handshakedPeer.nodeId.exists(isExcludedWrongNetwork)
      then
        // Dial exclusion only stops OUR dials. A node excluded by node ID (wrong network #88, or a genesis-head crawler
        // evicted under spec 011) must not take an inbound slot back for the rest of its exclusion either: reject it
        // as soon as the handshake names it, with no new grace period. Outbound handshakes are already gated in
        // connectWith, where an explicit operator dial may bypass the exclusion.
        log.info(
          "EXCLUDED_PEER_REJECTED: inbound node={} host={} is excluded; disconnecting",
          handshakedPeer.id.value.take(16),
          host
        )
        handshakedPeer.ref ! PeerActor.DisconnectPeer(Disconnect.Reasons.UselessPeer)
        listening(connectedPeers)
      else handleTrackedPeerHandshake(handshakedPeer, isMaintained, connectedPeers)

    private def handleTrackedPeerHandshake(
        handshakedPeer: Peer,
        isMaintained: Boolean,
        connectedPeers: ConnectedPeers
    ): Behavior[Command] =
      snapGoodPeers.foreach { store =>
        if handshakedPeer.nodeId.exists(nid => store.markConnected(Hex.toHexString(nid.toArray))) then store.save()
      }
      if handshakedPeer.incomingConnection && connectedPeers.incomingHandshakedPeersCount >= peerConfiguration.maxIncomingPeers && !isMaintained
      then
        handshakedPeer.ref ! PeerActor.DisconnectPeer(Disconnect.Reasons.TooManyPeers)

        // It looks like all incoming slots are taken; try to make some room.
        context.self ! SchedulePruneIncomingPeers

        listening(connectedPeers)
      else if handshakedPeer.nodeId.exists(connectedPeers.hasHandshakedWith) then
        val nodeId = handshakedPeer.nodeId.get
        // Defense in depth (see PeerActor.handleHandshakedTerminated for the root-cause fix): if the
        // already-connected entry for this nodeId IS this very actor (identical ref), it is
        // re-publishing its own handshake success, not a genuine second connection. This is
        // unreachable now that a HANDSHAKED PeerActor stops instead of self-reconnecting, but
        // sending DisconnectPeer(AlreadyConnected) to your own
        // live connection would disconnect a perfectly good peer, so guard the symptom here too.
        val existingSameRef =
          connectedPeers.peers.values.exists(p => p.nodeId.contains(nodeId) && p.ref == handshakedPeer.ref)
        if existingSameRef then
          log.warn(
            "Ignoring duplicate PeerHandshakeSuccessful for {} — same ref ({}) already handshaked",
            handshakedPeer.id,
            handshakedPeer.ref
          )
          listening(connectedPeers)
        else
          val existingOutboundOpt = connectedPeers.peers.values
            .find(p => p.nodeId.contains(nodeId) && !p.incomingConnection)
          if handshakedPeer.incomingConnection && isMaintained && existingOutboundOpt.isDefined then
            // Inbound wins for maintained peers — drop the outbound, keep the inbound.
            // Mirrors go-ethereum's static-pool removal when a peer connects either direction,
            // preventing core-geth's static-dial timer from firing a new outbound every 30-45s.
            log.debug("Maintained peer {} inbound wins tiebreak — dropping outbound", handshakedPeer.remoteAddress)
            existingOutboundOpt.foreach(_.ref ! PeerActor.DisconnectPeer(Disconnect.Reasons.AlreadyConnected))
            pendingMaintainedConnections.remove(handshakedPeer.ref)
            peerStatusCache = peerStatusCache + (handshakedPeer.id -> PeerActor.Status.Handshaked)
            listening(connectedPeers.promotePeerToHandshaked(handshakedPeer))
          else
            log.debug(s"Disconnecting from ${handshakedPeer.remoteAddress} as we are already connected to them")
            handshakedPeer.ref ! PeerActor.DisconnectPeer(Disconnect.Reasons.AlreadyConnected)
            listening(connectedPeers)
      else
        pendingMaintainedConnections.remove(handshakedPeer.ref)
        peerStatusCache = peerStatusCache + (handshakedPeer.id -> PeerActor.Status.Handshaked)
        listening(connectedPeers.promotePeerToHandshaked(handshakedPeer))

    private def createPeer(
        address: InetSocketAddress,
        incomingConnection: Boolean,
        connectedPeers: ConnectedPeers
    ): (Peer, ConnectedPeers) =
      val ref = peerFactory(context, address, incomingConnection)
      // Death-watch the spawned PeerActor. watchWith maps its termination to PeerTerminated so the Typed
      // handleTerminated handler can remove the peer from ConnectedPeers and schedule reconnects.
      context.watchWith(ref, PeerTerminated(ref))

      // The peerId is unknown for a pending peer, hence it is created from the PeerActor's path.
      // Upon successful handshake, the pending peer is updated with the actual peerId derived from
      // the Node's public key. See: ConnectedPeers#promotePeerToHandshaked
      val pendingPeer =
        Peer(
          PeerId.fromRef(ref),
          address,
          ref,
          incomingConnection
        )

      peerStatusCache = peerStatusCache + (pendingPeer.id -> PeerActor.Status.Connecting)
      val newConnectedPeers = connectedPeers.addNewPendingPeer(pendingPeer)

      (pendingPeer, newConnectedPeers)

    private def handlePruning(connectedPeers: ConnectedPeers, msg: Command): Option[Behavior[Command]] =
      msg match
        case SchedulePruneIncomingPeers =>
          import org.apache.pekko.actor.typed.scaladsl.AskPattern.*
          given timeout: Timeout = Timeout(peerConfiguration.updateNodesInterval)
          given typedScheduler: org.apache.pekko.actor.typed.Scheduler = context.system.scheduler

          // Ask for the whole statistics duration, we'll use averages to make it fair.
          val window = peerConfiguration.statSlotCount * peerConfiguration.statSlotDuration

          // Typed ask to PeerStatisticsActor; lift the resulting Future into IO and pipe the outcome to self,
          // converting failures to PruneStatsFailed (replaces the Classic Status.Failure plumbing).
          val task: IO[PeerStatisticsActor.StatsForAll] =
            IO.fromFuture(
              IO(
                peerStatistics.ask[PeerStatisticsActor.StatsForAll](
                  PeerStatisticsActor.GetStatsForAll(window, _)
                )
              )
            )
          pipeToSelf(task)(PruneIncomingPeers.apply, PruneStatsFailed.apply)
          Some(Behaviors.same)

        case PruneIncomingPeers(PeerStatisticsActor.StatsForAll(stats)) =>
          val prunedConnectedPeers = pruneIncomingPeers(connectedPeers, stats)
          Some(listening(prunedConnectedPeers))

        case PruneStatsFailed(ex) =>
          log.warn("Failed to get peer statistics for pruning: {}", ex.getMessage)
          // Continue with existing peers without pruning
          Some(Behaviors.same)

        case StatusRefreshFailed(ex) =>
          log.warn("Failed to refresh peer status cache: {}", ex.getMessage)
          Some(Behaviors.same)

        case _ => None

    /** Disconnect some incoming connections so we can free up slots. */
    private def pruneIncomingPeers(
        connectedPeers: ConnectedPeers,
        stats: Map[PeerId, PeerStat]
    ): ConnectedPeers =
      val pruneCount = PeerManagerActor.numberOfIncomingConnectionsToPrune(connectedPeers, peerConfiguration)
      val now = System.currentTimeMillis
      val excludedNodeIds =
        (maintainedPeersByNodeId.keySet ++ trustedPeersByNodeId).map(hex => ByteString(Hex.decode(hex)))
      val (peersToPrune, prunedConnectedPeers) =
        connectedPeers.prunePeers(
          incoming = true,
          minAge = peerConfiguration.minPruneAge,
          numPeers = pruneCount,
          priority = prunePriority(stats, now),
          currentTimeMillis = now,
          excludedNodeIds = excludedNodeIds
        )

      peersToPrune.foreach { peer =>
        peer.ref ! PeerActor.DisconnectPeer(Disconnect.Reasons.TooManyPeers)
      }

      prunedConnectedPeers

    /** Run an IO task and deliver its outcome to self as a Command (success → `onSuccess`, failure → `onFailure`).
      * Replaces the Classic `pipeTo(self)` + `Status.Failure` plumbing. The plain SLF4J logger inside the IO lambda is
      * avoided by routing the failure back through the actor mailbox rather than logging in the Future.
      */
    private def pipeToSelf[T](task: IO[T])(onSuccess: T => Command, onFailure: Throwable => Command): Unit =
      val attemptedF = task.attempt.unsafeToFuture()
      attemptedF.foreach {
        case Right(value) => context.self ! onSuccess(value)
        case Left(ex)     => context.self ! onFailure(ex)
      }

    /** Background refresh of peer status cache using the existing parTraverse pattern. Results are piped back to self
      * and applied to the cache.
      */
    private def refreshPeerStatusCache(connectedPeers: ConnectedPeers): Unit =
      val peers = connectedPeers.peers.values.toSet
      val task = peers.toList
        .parTraverse(getPeerStatus)
        .map(_.flatten.toMap)
      pipeToSelf(task)(PeerStatusCacheUpdated.apply, StatusRefreshFailed.apply)

    private def getPeerStatus(peer: Peer): IO[Option[(Peer, PeerActor.Status)]] =
      import org.apache.pekko.actor.typed.scaladsl.AskPattern.*
      given timeout: Timeout = Timeout(2.seconds)
      given typedScheduler: org.apache.pekko.actor.typed.Scheduler = context.system.scheduler
      // Extract a plain SLF4J logger before the IO lambda — ctx.log is thread-confined.
      val slf4jLog = org.slf4j.LoggerFactory.getLogger("com.chipprbots.ethereum.network.PeerManagerActor")
      IO.fromFuture(
        IO(peer.ref.ask[PeerActor.StatusResponse](replyTo => PeerActor.GetStatus(replyTo)))
      ).map(sr => Some((peer, sr.status)))
        .handleErrorWith {
          case _: java.util.concurrent.TimeoutException =>
            IO.pure(None) // Expected timeout, no logging needed
          case err =>
            IO.delay(slf4jLog.error(s"Failed to get status for peer: ${peer.id}", err)).as(None)
        }

    private def validateConnection(
        remoteAddress: InetSocketAddress,
        error: ConnectionError,
        stateCondition: Boolean
    ): Either[ConnectionError, InetSocketAddress] =
      Either.cond(stateCondition, remoteAddress, error)

    private def handleConnectionErrors(error: ConnectionError): Unit = error match
      case MaxIncomingPendingConnections(connection) =>
        log.debug("Maximum number of pending incoming peers reached")
        connection ! PoisonPill

      case IncomingConnectionAlreadyHandled(remoteAddress, connection) =>
        log.debug("Another connection with {} is already opened. Disconnecting", remoteAddress)
        connection ! PoisonPill

      case IncomingConnectionBlacklisted(remoteAddress, connection) =>
        log.debug("Rejecting incoming connection from blacklisted address {}", remoteAddress)
        connection ! PoisonPill

      case MaxOutgoingConnections =>
        log.debug("Maximum number of connected peers reached")

      case OutgoingConnectionAlreadyHandled(uri) =>
        log.debug("Another connection with {} is already opened", uri)
  // scalastyle:on number.of.methods

  /** Sanitize an InetSocketAddress string for use as a Pekko actor path element. Pekko only allows ASCII letters/digits
    * and -_.*$+:@&=,!~'; in path elements. IPv6 brackets ([2a01:4f8::2]) and DNS-failure placeholders (<unresolved>)
    * otherwise crash PeerManagerActor with InvalidActorNameException.
    */
  private[network] def sanitizeActorPathElement(raw: String): String =
    val validSymbols = "-_.*$+:@&=,!~';"
    raw.filterNot(_ == '/').map { c =>
      if c.isLetterOrDigit || validSymbols.contains(c) then c else '_'
    }

  def peerFactory[R <: HandshakeResult](
      config: PeerConfiguration,
      eventBus: typed.ActorRef[PeerEventBusActor.Command],
      knownNodesManager: typed.ActorRef[KnownNodesManager.Command],
      handshaker: Handshaker[R],
      authHandshaker: AuthHandshaker,
      capabilities: List[Capability]
  ): (TypedActorContext[Command], InetSocketAddress, Boolean) => typed.ActorRef[PeerActor.Command] =
    (ctx, address, incomingConnection) =>
      val id: String = sanitizeActorPathElement(address.toString)
      // Spawn PeerActor as a Typed child. The child's path name is the sanitized address — identical to the prior
      // `ctx.actorOf(PeerActor.props, id)`, so PeerId.fromRef is preserved.
      val behavior: Behavior[PeerActor.Command] =
        Behaviors
          .supervise(
            PeerActor.apply(
              address,
              PeerActor.rlpxConnectionFactory(authHandshaker, config.rlpxConfiguration, capabilities),
              config,
              eventBus,
              knownNodesManager,
              incomingConnection,
              initHandshaker = handshaker
            )
          )
          .onFailure[Throwable](
            SupervisorStrategy.restartWithBackoff(1.second, 30.seconds, 0.2).withMaxRestarts(3)
          )
      ctx.spawn(behavior, id)

  trait PeerConfiguration extends PeerConfiguration.ConnectionLimits:
    val connectRetryDelay: FiniteDuration
    val connectMaxRetries: Int
    val disconnectPoisonPillTimeout: FiniteDuration
    val waitForHelloTimeout: FiniteDuration
    val waitForStatusTimeout: FiniteDuration
    val waitForChainCheckTimeout: FiniteDuration
    val fastSyncHostConfiguration: FastSyncHostConfiguration
    val rlpxConfiguration: RLPxConfiguration
    val networkId: Long
    val p2pVersion: Int
    val updateNodesInitialDelay: FiniteDuration
    val updateNodesInterval: FiniteDuration
    val shortBlacklistDuration: FiniteDuration
    val longBlacklistDuration: FiniteDuration
    val statSlotDuration: FiniteDuration
    val statSlotCount: Int

    /** How long a node whose STATUS named another network or genesis is kept off the dial list (#88). Concrete so the
      * many test configurations keep compiling; overridden from `network.peer.wrong-network-exclusion-duration`.
      */
    val wrongNetworkExclusionDuration: FiniteDuration = PeerConfiguration.DefaultWrongNetworkExclusionDuration

    /** Spec 011: how long a handshaked peer may keep a genesis head, while our chain is past genesis, before it is
      * evicted. Zero disables. Overridden from `network.peer.genesis-head-eviction-grace`.
      */
    val genesisHeadEvictionGrace: FiniteDuration = PeerConfiguration.DefaultGenesisHeadEvictionGrace

    /** How long an evicted genesis-head peer stays off the dial list (`network.peer.genesis-head-exclusion-duration`).
      */
    val genesisHeadExclusionDuration: FiniteDuration = PeerConfiguration.DefaultGenesisHeadExclusionDuration
  object PeerConfiguration:
    val DefaultGenesisHeadEvictionGrace: FiniteDuration = 60.seconds
    val DefaultGenesisHeadExclusionDuration: FiniteDuration = 1.hour
    val DefaultWrongNetworkExclusionDuration: FiniteDuration = 24.hours

    trait ConnectionLimits:
      val minOutgoingPeers: Int
      val maxOutgoingPeers: Int
      val maxIncomingPeers: Int
      val maxPendingPeers: Int
      val pruneIncomingPeers: Int
      val minPruneAge: FiniteDuration

  trait FastSyncHostConfiguration:
    val maxBlocksHeadersPerMessage: Int
    val maxBlocksBodiesPerMessage: Int
    val maxReceiptsPerMessage: Int
    val maxMptComponentsPerMessage: Int

  case object StartConnecting

  case class HandlePeerConnection(connection: ActorRef, remoteAddress: InetSocketAddress)

  case class ConnectToPeer(uri: URI)

  case object GetPeers

  case class Peers(peers: Map[Peer, PeerActor.Status]):
    def handshaked: Seq[Peer] = peers.collect { case (peer, Handshaked) => peer }.toSeq

  case class SendMessage(message: MessageSerializable, peerId: PeerId)

  // New messages for enhanced peer management
  case class DisconnectPeerById(peerId: PeerId)
  case class DisconnectPeerResponse(disconnected: Boolean)

  case class AddToBlacklistRequest(address: String, duration: Option[FiniteDuration], reason: String)
  case class AddToBlacklistResponse(added: Boolean)

  case class RemoveFromBlacklistRequest(address: String)
  case class RemoveFromBlacklistResponse(removed: Boolean)

  /** Besu alignment: admin_addPeer / admin_removePeer maintained-peers set. AddMaintainedPeer returns wasAdded=false if
    * the peer was already in the set (duplicate call). RemoveMaintainedPeer removes from the set by hex node ID (enode
    * userInfo).
    */
  case class AddMaintainedPeer(uri: URI)
  case class AddMaintainedPeerResponse(wasAdded: Boolean)
  case class RemoveMaintainedPeer(nodeId: String)

  /** core-geth alignment: admin_addTrustedPeer / admin_removeTrustedPeer / admin_maxPeers. Trusted peers bypass the
    * max-peer limit and are always accepted. core-geth references: node/api.go AddTrustedPeer/RemoveTrustedPeer,
    * eth/api_admin.go MaxPeers
    */
  case class AddTrustedPeer(uri: URI)
  case class AddTrustedPeerResponse(success: Boolean)
  case class RemoveTrustedPeer(nodeId: String)
  case class RemoveTrustedPeerResponse(success: Boolean)
  case class SetMaxPeers(n: Int)
  case class SetMaxPeersResponse(success: Boolean)

  /** Default blacklist duration when none specified (permanent blacklist). Set to 365 days as a practical "permanent"
    * duration.
    */
  val DefaultPermanentBlacklistDuration: FiniteDuration = 365.days

  /** Short fixed backoff applied to snap-capable hosts in place of the soft-tier short blacklist (and the 5-30 min TCP
    * escalation). Long enough to avoid a hot reconnect loop, short enough that a scarce snap peer re-enters the dial
    * rotation almost immediately. See `snapCapableHosts`.
    */
  val SnapPeerSoftBlacklistDuration: FiniteDuration = 20.seconds

  /** Cap on consecutive lenient (snap-exempt) soft blacklists for one host before it falls back to the normal short
    * duration. Bounds the flap rate of a snap host that keeps soft-disconnecting; reset on a fresh handshake.
    */
  val MaxSnapLenientBlacklists: Int = 20

  /** Translate an inbound `Disconnect.Reasons.*` code into the blacklist duration we apply when the peer initiated the
    * disconnect. Extracted from the actor instance so it's directly unit-testable without spinning up a TestActorRef.
    *
    * Policy tiers (longest-first for readability):
    *   - **Permanent** for protocol violations (`BreachOfProtocol`, `IncompatibleP2pProtocolVersion`,
    *     `NullNodeIdentityReceived`). Besu's PeerDenylistManager does the same.
    *   - **Short** for soft "peer selection policy" rejections — the peer disconnected us because of how they pick
    *     counterparties, not because we did anything wrong. Covers `Other`, `UselessPeer`, `TooManyPeers`,
    *     `AlreadyConnected`, `ClientQuitting`, `TcpSubsystemError`, `DisconnectRequested`,
    *     `TimeoutOnReceivingAMessage`. Long blacklist on these classes destroys the peer pool during initial sync:
    *     peers reject us as uninteresting (we're at genesis / mid-SNAP-sync), we IP-blacklist them, and by the time
    *     they'd accept us again we still can't re-dial. Sepolia 2026-05-13: 100+ peers blacklisted within 5 min, pool
    *     collapsed to one peer; ETC mainnet has the same pattern.
    *   - **Long** for anything else (catch-all).
    *
    * The classification of `UselessPeer` as a SHORT-tier rejection (was: LONG, via the `_` wildcard) is the substantive
    * change. `UselessPeer` is a *the-peer-rejected-us* signal, not a protocol violation, and our state is highly
    * dynamic during initial sync — fast re-dials are the right policy.
    */
  private[network] def blacklistDurationForDisconnect(
      reason: Long,
      shortBlacklistDuration: FiniteDuration,
      longBlacklistDuration: FiniteDuration
  ): FiniteDuration =
    import Disconnect.Reasons.*
    reason match
      case BreachOfProtocol | IncompatibleP2pProtocolVersion | NullNodeIdentityReceived =>
        DefaultPermanentBlacklistDuration
      case TooManyPeers | AlreadyConnected | ClientQuitting | Other | UselessPeer | TcpSubsystemError |
          DisconnectRequested | TimeoutOnReceivingAMessage =>
        shortBlacklistDuration
      case _ => longBlacklistDuration

  sealed abstract class ConnectionError

  case class MaxIncomingPendingConnections(connection: ActorRef) extends ConnectionError

  case class IncomingConnectionAlreadyHandled(address: InetSocketAddress, connection: ActorRef) extends ConnectionError

  case class IncomingConnectionBlacklisted(address: InetSocketAddress, connection: ActorRef) extends ConnectionError

  case object MaxOutgoingConnections extends ConnectionError

  case class OutgoingConnectionAlreadyHandled(uri: URI) extends ConnectionError

  case class PeerAddress(value: String) extends BlacklistId

  /** Number of new connections the node should try to open at any given time. */
  def outgoingConnectionDemand(
      connectedPeers: ConnectedPeers,
      peerConfiguration: PeerConfiguration.ConnectionLimits
  ): Int =
    if connectedPeers.outgoingHandshakedPeersCount >= peerConfiguration.minOutgoingPeers then
      // We have established at least the minimum number of working connections.
      0
    else
      // Try to connect to more, up to the maximum, including pending peers.
      peerConfiguration.maxOutgoingPeers - connectedPeers.outgoingPeersCount

  def numberOfIncomingConnectionsToPrune(
      connectedPeers: ConnectedPeers,
      peerConfiguration: PeerConfiguration.ConnectionLimits
  ): Int =
    val minIncomingPeers = peerConfiguration.maxIncomingPeers - peerConfiguration.pruneIncomingPeers
    math.max(
      0,
      connectedPeers.incomingHandshakedPeersCount - connectedPeers.incomingPruningPeersCount - minIncomingPeers
    )

  /** Assign a priority to peers that we can use to order connections, with lower priorities being the ones to prune
    * first.
    */
  def prunePriority(stats: Map[PeerId, PeerStat], currentTimeMillis: Long)(peerId: PeerId): Double =
    stats
      .get(peerId)
      .flatMap { stat =>
        val maybeAgeSeconds = stat.firstSeenTimeMillis
          .map(currentTimeMillis - _)
          .map(_ * 1000)
          .filter(_ > 0)

        // Use the average number of responses per second over the lifetime of the connection
        // as an indicator of how fruitful the peer is for us.
        maybeAgeSeconds.map(age => stat.responsesReceived.toDouble / age)
      }
      .getOrElse(0.0)

  /** Bounded node-ID exclusion list with per-entry expiry (wrong-network peers, #88). Insertion-ordered: past
    * `maxEntries` the oldest entry is dropped. Not thread-safe; owned by the PeerManagerActor.
    */
  final class WrongNetworkExclusions(maxEntries: Int):
    require(maxEntries >= 1, s"maxEntries must be >= 1, got $maxEntries")
    private val untilMs = mutable.LinkedHashMap.empty[ByteString, Long]

    /** Exclude `nodeId` until `expiresAtMs`. Returns true if the node was not already listed. */
    def add(nodeId: ByteString, expiresAtMs: Long): Boolean =
      val wasExcluded = untilMs.contains(nodeId)
      untilMs.remove(nodeId)
      untilMs.update(nodeId, expiresAtMs)
      while untilMs.size > maxEntries do untilMs.remove(untilMs.head._1)
      !wasExcluded

    /** True while the exclusion has not expired. An expired entry is dropped on lookup. */
    def isExcluded(nodeId: ByteString, nowMs: Long): Boolean =
      untilMs.get(nodeId) match
        case Some(until) if until > nowMs => true
        case Some(_) =>
          untilMs.remove(nodeId)
          false
        case None => false

    def size: Int = untilMs.size

  def lruSet[A](maxEntries: Int): mutable.Set[A] =
    newSetFromMap[A](
      new java.util.LinkedHashMap[A, java.lang.Boolean]():
        override def removeEldestEntry(eldest: java.util.Map.Entry[A, java.lang.Boolean]): Boolean = size > maxEntries
    ).asScala
