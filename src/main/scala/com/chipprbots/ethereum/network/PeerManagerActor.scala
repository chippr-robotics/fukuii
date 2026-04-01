package com.chipprbots.ethereum.network

import java.net.InetSocketAddress
import java.net.URI
import java.util.Collections.newSetFromMap

import org.apache.pekko.actor.SupervisorStrategy.Stop
import org.apache.pekko.actor._
import org.apache.pekko.util.ByteString
import org.apache.pekko.util.Timeout

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import cats.syntax.parallel._

import scala.collection.mutable
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

import org.bouncycastle.util.encoders.Hex

import com.chipprbots.ethereum.blockchain.sync.Blacklist
import com.chipprbots.ethereum.blockchain.sync.Blacklist.BlacklistId
import com.chipprbots.ethereum.jsonrpc.AkkaTaskOps.TaskActorOps
import com.chipprbots.ethereum.network.PeerActor.PeerClosedConnection
import com.chipprbots.ethereum.network.PeerActor.Status.Handshaked
import com.chipprbots.ethereum.network.PeerEventBusActor._
import com.chipprbots.ethereum.network.PeerManagerActor.PeerConfiguration
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

class PeerManagerActor(
    peerEventBus: ActorRef,
    peerDiscoveryManager: ActorRef,
    peerConfiguration: PeerConfiguration,
    knownNodesManager: ActorRef,
    peerStatistics: ActorRef,
    peerFactory: (ActorContext, InetSocketAddress, Boolean) => ActorRef,
    discoveryConfig: DiscoveryConfig,
    val blacklist: Blacklist,
    blockedIPRegistry: BlockedIPRegistry,
    staticNodes: Set[URI] = Set.empty,
    externalSchedulerOpt: Option[Scheduler] = None,
    autoBlocker: Option[AutoBlocker] = None
) extends Actor
    with ActorLogging
    with Stash {

  /** Maximum number of blacklisted nodes will never be larger than number of peers provided by discovery Discovery
    * provides remote nodes from all networks (ETC,ETH, Mordor etc.) only during handshake we learn that some of the
    * remote nodes are not compatible that's why we mark them as useless (blacklist them).
    *
    * The number of nodes in the current discovery is unlimited, but a guide may be the size of the routing table: one
    * bucket for each bit in the hash of the public key, times the bucket size.
    */
  val maxBlacklistedNodes: Int = 32 * 8 * discoveryConfig.kademliaBucketSize

  import PeerManagerActor._
  import org.apache.pekko.pattern.pipe

  val triedNodes: mutable.Set[ByteString] = lruSet[ByteString](maxBlacklistedNodes)

  /** In-process cache of peer statuses, updated reactively and via scheduled refresh. This allows GetPeers to return
    * immediately without querying individual peer actors.
    */
  private var peerStatusCache: Map[PeerId, PeerActor.Status] = Map.empty

  /** Peers that should always remain connected. On disconnect, a reconnect is scheduled after 30s. Keyed by hex node ID
    * (the userInfo portion of the enode URI), matching PeerId.value for handshaked peers. Mirrors Besu's
    * DefaultP2PNetwork.maintainedPeers set.
    */
  private var maintainedPeersByNodeId: Map[String, URI] = Map.empty

  /** Trusted peers bypass the max-peer limit and are always accepted. core-geth reference: p2p/server.go — trusted
    * map[enode.ID]bool in run loop. Keyed by lowercase hex node ID.
    */
  private var trustedPeersByNodeId: Set[String] = Set.empty

  /** Runtime max-outgoing-peers override set by admin_maxPeers. core-geth reference: eth/api_admin.go MaxPeers — sets
    * handler.maxPeers + p2pServer.MaxPeers.
    */
  private var maxOutgoingPeersOverride: Option[Int] = None

  private def effectiveMaxOutgoing: Int =
    maxOutgoingPeersOverride.getOrElse(peerConfiguration.maxOutgoingPeers)

  implicit class ConnectedPeersOps(connectedPeers: ConnectedPeers) {

    /** Number of new connections the node should try to open at any given time. Uses effectiveMaxOutgoing so
      * admin_maxPeers overrides take effect.
      */
    def outgoingConnectionDemand: Int =
      if (connectedPeers.outgoingHandshakedPeersCount >= peerConfiguration.minOutgoingPeers) 0
      else effectiveMaxOutgoing - connectedPeers.outgoingPeersCount

    def canConnectTo(node: Node): Boolean = {
      val socketAddress = node.tcpSocketAddress
      val alreadyConnected =
        connectedPeers.isConnectionHandled(socketAddress) ||
          connectedPeers.hasHandshakedWith(node.id)

      !alreadyConnected && !blacklist.isBlacklisted(PeerAddress(socketAddress.getHostString))
    }
  }

  // Subscribe to the handshake event of any peer
  peerEventBus ! Subscribe(SubscriptionClassifier.PeerHandshaked)

  def scheduler: Scheduler = externalSchedulerOpt.getOrElse(context.system.scheduler)
  // CE3: Using global IORuntime for actor operations
  implicit val ioRuntime: IORuntime = IORuntime.global

  override val supervisorStrategy: OneForOneStrategy =
    OneForOneStrategy() { case _ =>
      Stop
    }

  override def receive: Receive = {
    case StartConnecting =>
      scheduleNodesUpdate()
      schedulePeerStatusRefresh()
      knownNodesManager ! KnownNodesManager.GetKnownNodes
      // Also request discovered/bootstrap nodes immediately. On a fresh node, KnownNodes is empty
      // but bootstrap/static nodes are available as alreadyDiscoveredNodes in PeerDiscoveryManager.
      // Without this, the first connection attempt waits for updateNodesInitialDelay.
      // Core-geth dials bootstrap nodes at t+0; we should too.
      peerDiscoveryManager ! PeerDiscoveryManager.GetDiscoveredNodesInfo
      context.become(listening(ConnectedPeers.empty))
      unstashAll()
    case _ =>
      stash()
  }

  private def scheduleNodesUpdate(): Unit = {
    implicit val ec = context.dispatcher
    scheduler.scheduleWithFixedDelay(
      peerConfiguration.updateNodesInitialDelay,
      peerConfiguration.updateNodesInterval,
      peerDiscoveryManager,
      PeerDiscoveryManager.GetDiscoveredNodesInfo
    )
  }

  private def schedulePeerStatusRefresh(): Unit = {
    implicit val ec = context.dispatcher
    scheduler.scheduleWithFixedDelay(
      10.seconds,
      10.seconds,
      self,
      RefreshPeerStatuses
    )
  }

  private def listening(connectedPeers: ConnectedPeers): Receive =
    handleCommonMessages(connectedPeers)
      .orElse(handleConnections(connectedPeers))
      .orElse(handleNewNodesToConnectMessages(connectedPeers))
      .orElse(handlePruning(connectedPeers))

  private def handleNewNodesToConnectMessages(connectedPeers: ConnectedPeers): Receive = {
    case KnownNodesManager.KnownNodes(nodes) =>
      val nodesToConnect = nodes.take(peerConfiguration.maxOutgoingPeers)

      if (nodesToConnect.nonEmpty) {
        log.debug("Trying to connect to {} known nodes", nodesToConnect.size)
        nodesToConnect.foreach(n => self ! ConnectToPeer(n))
      } else {
        log.debug("The known nodes list is empty")
      }

    case PeerDiscoveryManager.RandomNodeInfo(node) =>
      maybeConnectToRandomNode(connectedPeers, node)

    case PeerDiscoveryManager.DiscoveredNodesInfo(nodes) =>
      maybeConnectToDiscoveredNodes(connectedPeers, nodes)
  }

  private def maybeConnectToRandomNode(connectedPeers: ConnectedPeers, node: Node): Unit =
    if (connectedPeers.outgoingConnectionDemand > 0) {
      if (connectedPeers.canConnectTo(node)) {
        log.debug(
          "Random node candidate {} accepted (outgoing demand: {}, tried: {}, pending: {})",
          formatNodeForLogs(node),
          connectedPeers.outgoingConnectionDemand,
          triedNodes.size,
          connectedPeers.pendingPeersCount
        )
        triedNodes.add(node.id)
        self ! ConnectToPeer(node.toUri)
      } else {
        log.debug(
          "Random node candidate {} rejected (already connected or blacklisted). Requesting replacement",
          formatNodeForLogs(node)
        )
        peerDiscoveryManager ! PeerDiscoveryManager.GetRandomNodeInfo
      }
    } else {
      log.debug(
        "Skipping random node {} because outgoing demand is zero (handshaked {}/{})",
        formatNodeForLogs(node),
        connectedPeers.handshakedPeersCount,
        peerConfiguration.maxOutgoingPeers + peerConfiguration.maxIncomingPeers
      )
    }

  private def maybeConnectToDiscoveredNodes(connectedPeers: ConnectedPeers, nodes: Set[Node]): Unit = {
    val discoveredNodes = nodes
      .filter(connectedPeers.canConnectTo)

    val nodesToConnect = discoveredNodes
      .filterNot(n => triedNodes.contains(n.id)) match {
      case seq if seq.size >= connectedPeers.outgoingConnectionDemand =>
        seq.take(connectedPeers.outgoingConnectionDemand)
      case _ => discoveredNodes.take(connectedPeers.outgoingConnectionDemand)
    }

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

    if (nodesToConnect.nonEmpty) {
      log.debug("Trying to connect to {} nodes", nodesToConnect.size)
      nodesToConnect.foreach { n =>
        triedNodes.add(n.id)
        self ! ConnectToPeer(n.toUri)
      }
    } else {
      log.debug("The nodes list is empty, no new nodes to connect to")
    }

    // Make sure the background lookups keep going and we don't get stuck with 0
    // nodes to connect to until the next discovery scan loop. Only sending 1
    // request so we don't rack up too many pending futures, just trigger a
    // search if needed.
    if (connectedPeers.outgoingConnectionDemand > nodesToConnect.size) {
      peerDiscoveryManager ! PeerDiscoveryManager.GetRandomNodeInfo
    }
  }

  private def formatNodeForLogs(node: Node): String = {
    val id = Hex.toHexString(node.id.take(6).toArray)
    s"${getHostName(node.addr)}:${node.tcpPort}/$id"
  }

  private def handleConnections(connectedPeers: ConnectedPeers): Receive = {
    case PeerClosedConnection(peerAddress, reason) =>
      blacklist.add(
        PeerAddress(peerAddress),
        getBlacklistDuration(reason),
        Blacklist.BlacklistReason.getP2PBlacklistReasonByDescription(Disconnect.reasonToString(reason))
      )
      if (reason == Disconnect.Reasons.IncompatibleP2pProtocolVersion) {
        autoBlocker.foreach(_.recordHardFailure(peerAddress, "IncompatibleP2pProtocolVersion"))
      }

    case HandlePeerConnection(connection, remoteAddress) =>
      handleConnection(connection, remoteAddress, connectedPeers)

    case ConnectToPeer(uri) =>
      connectWith(uri, connectedPeers)

    case AddMaintainedPeer(uri) =>
      val nodeId = uri.getUserInfo
      val wasAdded = !maintainedPeersByNodeId.contains(nodeId)
      maintainedPeersByNodeId = maintainedPeersByNodeId + (nodeId -> uri)
      sender() ! AddMaintainedPeerResponse(wasAdded)
      connectWith(uri, connectedPeers)

    case RemoveMaintainedPeer(nodeId) =>
      maintainedPeersByNodeId = maintainedPeersByNodeId - nodeId

    // ── Geth-compatible trusted peer / max-peers management ───────────────
    // core-geth references: node/api.go AddTrustedPeer/RemoveTrustedPeer, eth/api_admin.go MaxPeers

    case AddTrustedPeer(uri) =>
      val nodeId = uri.getUserInfo.toLowerCase
      trustedPeersByNodeId = trustedPeersByNodeId + nodeId
      sender() ! AddTrustedPeerResponse(success = true)
      // Attempt connection immediately (like AddMaintainedPeer) so the trusted
      // peer is dialled even if we're currently at the max-peer limit.
      connectWith(uri, connectedPeers)

    case RemoveTrustedPeer(nodeId) =>
      // Removes trust but does NOT disconnect the live connection.
      // core-geth: server.RemoveTrustedPeer does not call removePeer.
      trustedPeersByNodeId = trustedPeersByNodeId - nodeId.toLowerCase
      sender() ! RemoveTrustedPeerResponse(success = true)

    case SetMaxPeers(n) =>
      maxOutgoingPeersOverride = Some(n)
      sender() ! SetMaxPeersResponse(success = true)
  }

  private def getBlacklistDuration(reason: Long): FiniteDuration = {
    import Disconnect.Reasons._
    reason match {
      case TooManyPeers | AlreadyConnected | ClientQuitting => peerConfiguration.shortBlacklistDuration
      // Use short blacklist for 0x10 (Other) disconnects - these are often due to peer selection
      // policies (e.g., rejecting nodes at genesis) rather than actual protocol issues.
      // Peers may be willing to connect later once we've synced past genesis.
      case Other => peerConfiguration.shortBlacklistDuration
      // TcpSubsystemError (0x01) may indicate temporary network issues or peer-side problems
      // Use short blacklist to allow quick reconnection attempts
      case TcpSubsystemError | DisconnectRequested | TimeoutOnReceivingAMessage =>
        peerConfiguration.shortBlacklistDuration
      // Permanent blacklist for protocol violations.
      // Besu: PeerDenylistManager.java triggers denylist on BREACH_OF_PROTOCOL and
      // INCOMPATIBLE_P2P_PROTOCOL_VERSION (maintained peers are exempt in Besu, but we
      // apply permanent duration here — maintained peers are reconnected anyway via the
      // Terminated handler regardless of IP blacklist state).
      case BreachOfProtocol | IncompatibleP2pProtocolVersion | NullNodeIdentityReceived =>
        DefaultPermanentBlacklistDuration
      case _ => peerConfiguration.longBlacklistDuration
    }
  }

  private def handleConnection(
      connection: ActorRef,
      remoteAddress: InetSocketAddress,
      connectedPeers: ConnectedPeers
  ): Unit = {
    val alreadyConnectedToPeer = connectedPeers.isConnectionHandled(remoteAddress)
    val isPendingPeersNotMaxValue = connectedPeers.incomingPendingPeersCount < peerConfiguration.maxPendingPeers

    val validConnection = for {
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
    } yield validNumber

    validConnection match {
      case Right(address) =>
        log.error(
          "[HIVE-DEBUG] Accepting incoming connection from {} (pending={}/{})",
          remoteAddress,
          connectedPeers.incomingPendingPeersCount,
          peerConfiguration.maxPendingPeers
        )
        val (peer, newConnectedPeers) = createPeer(address, incomingConnection = true, connectedPeers)
        peer.ref ! PeerActor.HandleConnection(connection, remoteAddress)
        context.become(listening(newConnectedPeers))

      case Left(error) =>
        log.error("[HIVE-DEBUG] Rejecting incoming connection from {}: {}", remoteAddress, error)
        handleConnectionErrors(error)
    }
  }

  private def connectWith(uri: URI, connectedPeers: ConnectedPeers): Unit = {
    val nodeIdHex = uri.getUserInfo.toLowerCase
    val nodeId = ByteString(Hex.decode(nodeIdHex))
    val remoteAddress = new InetSocketAddress(uri.getHost, uri.getPort)

    val alreadyConnectedToPeer =
      connectedPeers.hasHandshakedWith(nodeId) || connectedPeers.isConnectionHandled(remoteAddress)
    // Trusted peers bypass the max peer limit (core-geth: trustedConn flag skips maxPeers check)
    val isTrusted = trustedPeersByNodeId.contains(nodeIdHex)
    val isOutgoingPeersNotMaxValue = isTrusted || connectedPeers.outgoingPeersCount < effectiveMaxOutgoing

    val validConnection = for {
      validHandler <- validateConnection(remoteAddress, OutgoingConnectionAlreadyHandled(uri), !alreadyConnectedToPeer)
      validNumber <- validateConnection(validHandler, MaxOutgoingConnections, isOutgoingPeersNotMaxValue)
    } yield validNumber

    validConnection match {
      case Right(address) =>
        val (peer, newConnectedPeers) = createPeer(address, incomingConnection = false, connectedPeers)
        peer.ref ! PeerActor.ConnectTo(uri)
        context.become(listening(newConnectedPeers))

      case Left(error) => handleConnectionErrors(error)
    }
  }

  private def handleCommonMessages(connectedPeers: ConnectedPeers): Receive = {
    case GetPeers =>
      // Return cached statuses immediately — no actor asks needed.
      // Cache is updated reactively on connect/disconnect/handshake and via scheduled refresh.
      val cachedPeers = connectedPeers.peers.values.map { peer =>
        val status = peerStatusCache.getOrElse(peer.id, PeerActor.Status.Connecting)
        peer -> status
      }.toMap
      sender() ! Peers(cachedPeers)

    case RefreshPeerStatuses =>
      refreshPeerStatusCache(connectedPeers)

    case PeerStatusCacheUpdated(statuses) =>
      peerStatusCache = statuses.map { case (peer, status) => peer.id -> status }

    case SendMessage(message, peerId) if connectedPeers.getPeer(peerId).isDefined =>
      connectedPeers.getPeer(peerId).foreach(peer => peer.ref ! PeerActor.SendMessage(message))

    case DisconnectPeerById(peerId) =>
      val requester = sender()
      connectedPeers.getPeer(peerId) match {
        case Some(peer) =>
          peer.ref ! PeerActor.DisconnectPeer(Disconnect.Reasons.DisconnectRequested)
          requester ! DisconnectPeerResponse(disconnected = true)
        case None =>
          requester ! DisconnectPeerResponse(disconnected = false)
      }

    case req: AddToBlacklistRequest =>
      val requester = sender()
      try {
        val duration = req.duration.getOrElse(PeerManagerActor.DefaultPermanentBlacklistDuration)
        val reason = Blacklist.BlacklistReason.getP2PBlacklistReasonByDescription(req.reason)
        blacklist.add(PeerAddress(req.address), duration, reason)
        requester ! AddToBlacklistResponse(added = true)
      } catch {
        case e: Exception =>
          log.error(s"Failed to add address ${req.address} to blacklist", e)
          requester ! AddToBlacklistResponse(added = false)
      }

    case req: RemoveFromBlacklistRequest =>
      val requester = sender()
      try {
        blacklist.remove(PeerAddress(req.address))
        requester ! RemoveFromBlacklistResponse(removed = true)
      } catch {
        case e: Exception =>
          log.error(s"Failed to remove address ${req.address} from blacklist", e)
          requester ! RemoveFromBlacklistResponse(removed = false)
      }

    case Terminated(ref) =>
      val (terminatedPeersIds, newConnectedPeers) = connectedPeers.removeTerminatedPeer(ref)
      terminatedPeersIds.foreach { peerId =>
        peerStatusCache = peerStatusCache - peerId
        peerEventBus ! Publish(PeerEvent.PeerDisconnected(peerId))
        // Reconnect maintained peers — mirrors Besu's checkMaintainedConnectionPeers scheduler.
        maintainedPeersByNodeId.get(peerId.value).foreach { uri =>
          log.debug("Maintained peer {} disconnected — scheduling reconnect in 30s", uri)
          context.system.scheduler.scheduleOnce(30.seconds, self, ConnectToPeer(uri))(context.dispatcher)
        }
      }
      // Try to replace a lost connection with another one.
      if (newConnectedPeers.outgoingConnectionDemand > 0) {
        peerDiscoveryManager ! PeerDiscoveryManager.GetRandomNodeInfo
      }
      context.unwatch(ref)
      context.become(listening(newConnectedPeers))

    case PeerEvent.PeerHandshakeSuccessful(handshakedPeer, _) =>
      if (
        handshakedPeer.incomingConnection && connectedPeers.incomingHandshakedPeersCount >= peerConfiguration.maxIncomingPeers
      ) {
        handshakedPeer.ref ! PeerActor.DisconnectPeer(Disconnect.Reasons.TooManyPeers)

        // It looks like all incoming slots are taken; try to make some room.
        self ! SchedulePruneIncomingPeers

        context.become(listening(connectedPeers))

      } else if (handshakedPeer.nodeId.exists(connectedPeers.hasHandshakedWith)) {
        // Even though we do already validations for this, we might have missed it someone tried connecting to us at the
        // same time as we do
        log.debug(s"Disconnecting from ${handshakedPeer.remoteAddress} as we are already connected to them")
        handshakedPeer.ref ! PeerActor.DisconnectPeer(Disconnect.Reasons.AlreadyConnected)
        // Keep the current connectedPeers state; the Terminated message will clean up the peer
        context.become(listening(connectedPeers))
      } else {
        peerStatusCache = peerStatusCache + (handshakedPeer.id -> PeerActor.Status.Handshaked)
        context.become(listening(connectedPeers.promotePeerToHandshaked(handshakedPeer)))
      }
  }

  private def createPeer(
      address: InetSocketAddress,
      incomingConnection: Boolean,
      connectedPeers: ConnectedPeers
  ): (Peer, ConnectedPeers) = {
    val ref = peerFactory(context, address, incomingConnection)
    context.watch(ref)

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
  }

  private def handlePruning(connectedPeers: ConnectedPeers): Receive = {
    case SchedulePruneIncomingPeers =>
      implicit val timeout: Timeout = Timeout(peerConfiguration.updateNodesInterval)

      // Ask for the whole statistics duration, we'll use averages to make it fair.
      val window = peerConfiguration.statSlotCount * peerConfiguration.statSlotDuration

      val task = peerStatistics
        .askFor[PeerStatisticsActor.StatsForAll](PeerStatisticsActor.GetStatsForAll(window))
        .map(PruneIncomingPeers.apply)

      pipeToRecipient(self)(task)

    case PruneIncomingPeers(PeerStatisticsActor.StatsForAll(stats)) =>
      val prunedConnectedPeers = pruneIncomingPeers(connectedPeers, stats)

      context.become(listening(prunedConnectedPeers))

    case Status.Failure(ex) =>
      log.warning("Failed to get peer statistics for pruning: {}", ex.getMessage)
    // Continue with existing peers without pruning
  }

  /** Disconnect some incoming connections so we can free up slots. */
  private def pruneIncomingPeers(
      connectedPeers: ConnectedPeers,
      stats: Map[PeerId, PeerStat]
  ): ConnectedPeers = {
    val pruneCount = PeerManagerActor.numberOfIncomingConnectionsToPrune(connectedPeers, peerConfiguration)
    val now = System.currentTimeMillis
    val (peersToPrune, prunedConnectedPeers) =
      connectedPeers.prunePeers(
        incoming = true,
        minAge = peerConfiguration.minPruneAge,
        numPeers = pruneCount,
        priority = prunePriority(stats, now),
        currentTimeMillis = now
      )

    peersToPrune.foreach { peer =>
      peer.ref ! PeerActor.DisconnectPeer(Disconnect.Reasons.TooManyPeers)
    }

    prunedConnectedPeers
  }

  /** Pipe an IO task to a recipient actor with explicit error handling.
    *
    * Converts IO failures to Status.Failure messages for deterministic error propagation. This prevents race conditions
    * and flaky behavior when IO tasks fail.
    */
  private def pipeToRecipient[T](recipient: ActorRef)(task: IO[T]): Unit = {
    implicit val ec = context.dispatcher
    val attemptedF = task.attempt.unsafeToFuture()
    val mappedF = attemptedF.map {
      case Right(value) => value
      case Left(ex)     => Status.Failure(ex)
    }
    mappedF.pipeTo(recipient)
  }

  /** Background refresh of peer status cache using the existing parTraverse pattern. Results are piped back to self and
    * applied to the cache.
    */
  private def refreshPeerStatusCache(connectedPeers: ConnectedPeers): Unit = {
    val peers = connectedPeers.peers.values.toSet
    val task = peers.toList
      .parTraverse(getPeerStatus)
      .map(_.flatten.toMap)
      .map(PeerStatusCacheUpdated.apply)
    pipeToRecipient(self)(task)
  }

  private def getPeerStatus(peer: Peer): IO[Option[(Peer, PeerActor.Status)]] = {
    implicit val timeout: Timeout = Timeout(2.seconds)
    peer.ref
      .askFor[PeerActor.StatusResponse](PeerActor.GetStatus)
      .map(sr => Some((peer, sr.status)))
      .handleErrorWith {
        case _: java.util.concurrent.TimeoutException =>
          IO.pure(None) // Expected timeout, no logging needed
        case err =>
          IO.delay(log.error(err, s"Failed to get status for peer: ${peer.id}")).as(None)
      }
  }

  private def validateConnection(
      remoteAddress: InetSocketAddress,
      error: ConnectionError,
      stateCondition: Boolean
  ): Either[ConnectionError, InetSocketAddress] =
    Either.cond(stateCondition, remoteAddress, error)

  private def handleConnectionErrors(error: ConnectionError): Unit = error match {
    case MaxIncomingPendingConnections(connection) =>
      log.debug("Maximum number of pending incoming peers reached")
      connection ! PoisonPill

    case IncomingConnectionAlreadyHandled(remoteAddress, connection) =>
      log.debug("Another connection with {} is already opened. Disconnecting", remoteAddress)
      connection ! PoisonPill

    case MaxOutgoingConnections =>
      log.debug("Maximum number of connected peers reached")

    case OutgoingConnectionAlreadyHandled(uri) =>
      log.debug("Another connection with {} is already opened", uri)
  }
}

object PeerManagerActor {
  // scalastyle:off parameter.number
  def props[R <: HandshakeResult](
      peerDiscoveryManager: ActorRef,
      peerConfiguration: PeerConfiguration,
      peerMessageBus: ActorRef,
      knownNodesManager: ActorRef,
      peerStatistics: ActorRef,
      handshaker: Handshaker[R],
      authHandshaker: AuthHandshaker,
      discoveryConfig: DiscoveryConfig,
      blacklist: Blacklist,
      capabilities: List[Capability],
      blockedIPRegistry: BlockedIPRegistry,
      staticNodes: Set[URI] = Set.empty,
      autoBlocker: Option[AutoBlocker] = None
  ): Props = {
    val factory: (ActorContext, InetSocketAddress, Boolean) => ActorRef =
      peerFactory(
        peerConfiguration,
        peerMessageBus,
        knownNodesManager,
        handshaker,
        authHandshaker,
        capabilities
      )

    Props(
      new PeerManagerActor(
        peerMessageBus,
        peerDiscoveryManager,
        peerConfiguration,
        knownNodesManager,
        peerStatistics,
        peerFactory = factory,
        discoveryConfig,
        blacklist,
        blockedIPRegistry,
        staticNodes,
        autoBlocker = autoBlocker
      )
    )
  }
  // scalastyle:on parameter.number

  /** Sanitize an InetSocketAddress string for use as a Pekko actor path element. Pekko only allows ASCII letters/digits
    * and -_.*$+:@&=,!~'; in path elements. IPv6 brackets ([2a01:4f8::2]) and DNS-failure placeholders (<unresolved>)
    * otherwise crash PeerManagerActor with InvalidActorNameException.
    */
  private[network] def sanitizeActorPathElement(raw: String): String = {
    val validSymbols = "-_.*$+:@&=,!~';"
    raw.filterNot(_ == '/').map { c =>
      if (c.isLetterOrDigit || validSymbols.contains(c)) c else '_'
    }
  }

  def peerFactory[R <: HandshakeResult](
      config: PeerConfiguration,
      eventBus: ActorRef,
      knownNodesManager: ActorRef,
      handshaker: Handshaker[R],
      authHandshaker: AuthHandshaker,
      capabilities: List[Capability]
  ): (ActorContext, InetSocketAddress, Boolean) => ActorRef = { (ctx, address, incomingConnection) =>
    val id: String = sanitizeActorPathElement(address.toString)
    val props = PeerActor.props(
      address,
      config,
      eventBus,
      knownNodesManager,
      incomingConnection,
      handshaker,
      authHandshaker,
      capabilities
    )
    ctx.actorOf(props, id)
  }

  trait PeerConfiguration extends PeerConfiguration.ConnectionLimits {
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
  }
  object PeerConfiguration {
    trait ConnectionLimits {
      val minOutgoingPeers: Int
      val maxOutgoingPeers: Int
      val maxIncomingPeers: Int
      val maxPendingPeers: Int
      val pruneIncomingPeers: Int
      val minPruneAge: FiniteDuration
    }
  }

  trait FastSyncHostConfiguration {
    val maxBlocksHeadersPerMessage: Int
    val maxBlocksBodiesPerMessage: Int
    val maxReceiptsPerMessage: Int
    val maxMptComponentsPerMessage: Int
  }

  case object StartConnecting

  case class HandlePeerConnection(connection: ActorRef, remoteAddress: InetSocketAddress)

  case class ConnectToPeer(uri: URI)

  case object GetPeers

  case class Peers(peers: Map[Peer, PeerActor.Status]) {
    def handshaked: Seq[Peer] = peers.collect { case (peer, Handshaked) => peer }.toSeq
  }

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

  sealed abstract class ConnectionError

  case class MaxIncomingPendingConnections(connection: ActorRef) extends ConnectionError

  case class IncomingConnectionAlreadyHandled(address: InetSocketAddress, connection: ActorRef) extends ConnectionError

  case object MaxOutgoingConnections extends ConnectionError

  case class OutgoingConnectionAlreadyHandled(uri: URI) extends ConnectionError

  case class PeerAddress(value: String) extends BlacklistId

  case object SchedulePruneIncomingPeers
  case class PruneIncomingPeers(stats: PeerStatisticsActor.StatsForAll)

  case object RefreshPeerStatuses
  private case class PeerStatusCacheUpdated(statuses: Map[Peer, PeerActor.Status])

  /** Number of new connections the node should try to open at any given time. */
  def outgoingConnectionDemand(
      connectedPeers: ConnectedPeers,
      peerConfiguration: PeerConfiguration.ConnectionLimits
  ): Int =
    if (connectedPeers.outgoingHandshakedPeersCount >= peerConfiguration.minOutgoingPeers)
      // We have established at least the minimum number of working connections.
      0
    else
      // Try to connect to more, up to the maximum, including pending peers.
      peerConfiguration.maxOutgoingPeers - connectedPeers.outgoingPeersCount

  def numberOfIncomingConnectionsToPrune(
      connectedPeers: ConnectedPeers,
      peerConfiguration: PeerConfiguration.ConnectionLimits
  ): Int = {
    val minIncomingPeers = peerConfiguration.maxIncomingPeers - peerConfiguration.pruneIncomingPeers
    math.max(
      0,
      connectedPeers.incomingHandshakedPeersCount - connectedPeers.incomingPruningPeersCount - minIncomingPeers
    )
  }

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

  def lruSet[A](maxEntries: Int): mutable.Set[A] =
    newSetFromMap[A](new java.util.LinkedHashMap[A, java.lang.Boolean]() {
      override def removeEldestEntry(eldest: java.util.Map.Entry[A, java.lang.Boolean]): Boolean = size > maxEntries
    }).asScala
}
