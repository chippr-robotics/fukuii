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
    externalSchedulerOpt: Option[Scheduler] = None
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

  /** Precomputed node IDs for static peers (from enode URIs). Used to identify static peers
    * in handshake/disconnect handlers without re-parsing URIs each time.
    */
  private val staticNodeIds: Set[ByteString] = staticNodes.map { uri =>
    ByteString(Hex.decode(uri.getUserInfo))
  }

  /** Tracks reconnection backoff state per static peer: (lastAttemptMs, currentBackoffMs).
    * Exponential backoff: 15s → 30s → 60s → 120s → 300s cap. Cleared on successful handshake.
    */
  private var staticPeerBackoff: Map[URI, (Long, Long)] = Map.empty

  /** In-process cache of peer statuses, updated reactively and via scheduled refresh. This allows GetPeers to return
    * immediately without querying individual peer actors.
    */
  private var peerStatusCache: Map[PeerId, PeerActor.Status] = Map.empty

  implicit class ConnectedPeersOps(connectedPeers: ConnectedPeers) {

    /** Number of new connections the node should try to open at any given time. */
    def outgoingConnectionDemand: Int =
      PeerManagerActor.outgoingConnectionDemand(connectedPeers, peerConfiguration)

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
      schedulePeerSummary()
      knownNodesManager ! KnownNodesManager.GetKnownNodes
      // Also request discovered/bootstrap nodes immediately. On a fresh node, KnownNodes is empty
      // but bootstrap/static nodes are available as alreadyDiscoveredNodes in PeerDiscoveryManager.
      // Without this, the first connection attempt waits for updateNodesInitialDelay.
      // Core-geth dials bootstrap nodes at t+0; we should too.
      peerDiscoveryManager ! PeerDiscoveryManager.GetDiscoveredNodesInfo
      // Direct-dial static nodes immediately (matches geth/core-geth behavior).
      // Static nodes are treated as direct TCP dial targets, not just discovery seeds.
      if (staticNodes.nonEmpty) {
        log.info("Direct-dialing {} static node(s)", staticNodes.size)
        staticNodes.foreach(uri => self ! ConnectToPeer(uri))
        scheduleStaticPeerCheck()
      }
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

  /** Schedule periodic reconnection attempts to static nodes (every 15s, matching geth). */
  private def scheduleStaticPeerCheck(): Unit = {
    implicit val ec = context.dispatcher
    scheduler.scheduleWithFixedDelay(
      StaticPeerCheckInterval,
      StaticPeerCheckInterval,
      self,
      CheckStaticPeers
    )
  }

  /** Schedule periodic peer summary log (every 60s). */
  private def schedulePeerSummary(): Unit = {
    implicit val ec = context.dispatcher
    scheduler.scheduleWithFixedDelay(
      60.seconds,
      60.seconds,
      self,
      PeerSummaryTick
    )
  }

  private def listening(connectedPeers: ConnectedPeers): Receive =
    handleCommonMessages(connectedPeers)
      .orElse(handleConnections(connectedPeers))
      .orElse(handleNewNodesToConnectMessages(connectedPeers))
      .orElse(handlePruning(connectedPeers))

  private def handleNewNodesToConnectMessages(connectedPeers: ConnectedPeers): Receive = {
    case CheckStaticPeers =>
      val now = System.currentTimeMillis()
      staticNodes.foreach { uri =>
        val nodeId = ByteString(Hex.decode(uri.getUserInfo))
        val addr = new InetSocketAddress(uri.getHost, uri.getPort)
        val handshaked = connectedPeers.hasHandshakedWith(nodeId)
        val addrHandled = connectedPeers.isConnectionHandled(addr)
        val nodeIdPending = connectedPeers.hasNodeIdPending(nodeId)
        if (handshaked) {
          // Peer is healthy — clear backoff so next disconnect retries quickly
          staticPeerBackoff -= uri
        } else if (!addrHandled && !nodeIdPending) {
          val (lastAttempt, backoff) =
            staticPeerBackoff.getOrElse(uri, (0L, StaticPeerInitialBackoffMs))
          val coolingDown = now - lastAttempt < backoff
          if (!coolingDown) {
            log.info("Reconnecting to static peer {}:{} (backoff={}s)", uri.getHost, uri.getPort, backoff / 1000)
            val nextBackoff = (backoff * StaticPeerBackoffFactor).min(StaticPeerMaxBackoffMs)
            staticPeerBackoff += (uri -> (now, nextBackoff))
            self ! ConnectToPeer(uri)
          } else if (log.isDebugEnabled) {
            log.debug(
              s"Static peer ${uri.getHost}:${uri.getPort} cooling down (${(backoff - (now - lastAttempt)) / 1000}s remaining)"
            )
          }
        }
      }

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
      .filterNot(n => blockedIPRegistry.isBlocked(n.addr.getHostAddress))
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

    case HandlePeerConnection(connection, remoteAddress) =>
      handleConnection(connection, remoteAddress, connectedPeers)

    case ConnectToPeer(uri) =>
      connectWith(uri, connectedPeers)
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
      case _ => peerConfiguration.longBlacklistDuration
    }
  }

  private def handleConnection(
      connection: ActorRef,
      remoteAddress: InetSocketAddress,
      connectedPeers: ConnectedPeers
  ): Unit = {
    val ip = remoteAddress.getAddress.getHostAddress
    if (blockedIPRegistry.isBlocked(ip)) {
      log.debug("Rejecting inbound connection from blocked IP {}", ip)
      connection ! PoisonPill
      return
    }

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
        val (peer, newConnectedPeers) = createPeer(address, incomingConnection = true, connectedPeers)
        peer.ref ! PeerActor.HandleConnection(connection, remoteAddress)
        context.become(listening(newConnectedPeers))

      case Left(error) =>
        handleConnectionErrors(error)
    }
  }

  private def isStaticNode(uri: URI): Boolean = staticNodes.contains(uri)

  private def connectWith(uri: URI, connectedPeers: ConnectedPeers): Unit = {
    val ip = uri.getHost
    if (blockedIPRegistry.isBlocked(ip)) {
      log.debug("Skipping outbound connection to blocked IP {}", ip)
      return
    }

    val nodeId = ByteString(Hex.decode(uri.getUserInfo))
    val remoteAddress = new InetSocketAddress(uri.getHost, uri.getPort)

    val alreadyConnectedToPeer =
      connectedPeers.hasHandshakedWith(nodeId) || connectedPeers.isConnectionHandled(remoteAddress)
    // Static nodes are exempt from MaxOutgoingConnections (matches geth behavior)
    val isOutgoingPeersNotMaxValue =
      isStaticNode(uri) || connectedPeers.outgoingPeersCount < peerConfiguration.maxOutgoingPeers

    val validConnection = for {
      validHandler <- validateConnection(remoteAddress, OutgoingConnectionAlreadyHandled(uri), !alreadyConnectedToPeer)
      validNumber <- validateConnection(validHandler, MaxOutgoingConnections, isOutgoingPeersNotMaxValue)
    } yield validNumber

    validConnection match {
      case Right(address) =>
        val (peer, newConnectedPeers) = createPeer(address, incomingConnection = false, connectedPeers, isStatic = isStaticNode(uri), targetNodeId = Some(nodeId))
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

    case PeerSummaryTick =>
      val total = connectedPeers.handshakedPeersCount
      if (total > 0) {
        val inbound = connectedPeers.incomingHandshakedPeersCount
        val outbound = connectedPeers.outgoingHandshakedPeersCount
        val pending = connectedPeers.peers.size - total
        log.info("Peers: {} connected ({} in, {} out, {} pending)", total, inbound, outbound, pending)
      } else if (log.isDebugEnabled) {
        log.debug("Peers: 0 connected ({} pending)", connectedPeers.peers.size)
      }

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
      // Look up static peer info before removal (for lifecycle logging)
      connectedPeers.findByRef(ref).foreach { peer =>
        if (peer.nodeId.exists(staticNodeIds.contains)) {
          val dir = if (peer.incomingConnection) "inbound" else "outbound"
          log.info(s"Static peer disconnected: ${peer.remoteAddress} ($dir)")
        }
      }
      val (terminatedPeersIds, newConnectedPeers) = connectedPeers.removeTerminatedPeer(ref)
      terminatedPeersIds.foreach { peerId =>
        peerStatusCache = peerStatusCache - peerId
        peerEventBus ! Publish(PeerEvent.PeerDisconnected(peerId))
      }
      // Try to replace a lost connection with another one.
      if (newConnectedPeers.outgoingConnectionDemand > 0) {
        peerDiscoveryManager ! PeerDiscoveryManager.GetRandomNodeInfo
      }
      context.unwatch(ref)
      context.become(listening(newConnectedPeers))

    case PeerEvent.PeerHandshakeSuccessful(handshakedPeer, _) =>
      val isStaticPeer = handshakedPeer.nodeId.exists(staticNodeIds.contains)
      if (
        handshakedPeer.incomingConnection &&
        connectedPeers.incomingHandshakedPeersCount >= peerConfiguration.maxIncomingPeers &&
        !isStaticPeer // Static peers bypass incoming slot limits (matches geth behavior)
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
        if (isStaticPeer) {
          val dir = if (handshakedPeer.incomingConnection) "inbound" else "outbound"
          log.info(s"Static peer handshaked: ${handshakedPeer.remoteAddress} ($dir)")
        }
        peerStatusCache = peerStatusCache + (handshakedPeer.id -> PeerActor.Status.Handshaked)
        context.become(listening(connectedPeers.promotePeerToHandshaked(handshakedPeer)))
      }
  }

  private def createPeer(
      address: InetSocketAddress,
      incomingConnection: Boolean,
      connectedPeers: ConnectedPeers,
      isStatic: Boolean = false,
      targetNodeId: Option[ByteString] = None
  ): (Peer, ConnectedPeers) = {
    val ref = peerFactory(context, address, incomingConnection)
    context.watch(ref)

    // The peerId is unknown for a pending peer, hence it is created from the PeerActor's path.
    // Upon successful handshake, the pending peer is updated with the actual peerId derived from
    // the Node's public key. See: ConnectedPeers#promotePeerToHandshaked
    // For outgoing peers, targetNodeId is set from the enode URI so that CheckStaticPeers
    // can detect duplicate connections even when the remote address differs (ephemeral ports).
    val pendingPeer =
      Peer(
        PeerId.fromRef(ref),
        address,
        ref,
        incomingConnection,
        isStatic = isStatic,
        nodeId = targetNodeId
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
  /** Reconnect interval for static nodes (matches geth's staticPeerCheckInterval). */
  val StaticPeerCheckInterval: FiniteDuration = 15.seconds

  /** Exponential backoff for static peer reconnection.
    * Prevents log flooding when a static peer is offline or building state (e.g., core-geth syncing).
    * Backoff: 15s → 30s → 60s → 120s → 300s (cap). Resets on successful handshake.
    */
  val StaticPeerInitialBackoffMs: Long = 15000  // 15s — same as CheckStaticPeers interval
  val StaticPeerMaxBackoffMs: Long = 300000     // 5 minutes
  val StaticPeerBackoffFactor: Int = 2

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
      staticNodes: Set[URI] = Set.empty
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
        staticNodes
      )
    )
  }
  // scalastyle:on parameter.number

  def peerFactory[R <: HandshakeResult](
      config: PeerConfiguration,
      eventBus: ActorRef,
      knownNodesManager: ActorRef,
      handshaker: Handshaker[R],
      authHandshaker: AuthHandshaker,
      capabilities: List[Capability]
  ): (ActorContext, InetSocketAddress, Boolean) => ActorRef = { (ctx, address, incomingConnection) =>
    // Sanitize address for use as Pekko actor path element.
    // IPv6 addresses contain brackets (e.g. [2a01:4f8::2]:30303) which are illegal
    // in actor paths. Replace all non-allowed characters to prevent
    // InvalidActorNameException from crashing PeerManagerActor.
    val id: String = address.toString.filterNot(_ == '/').map {
      case '[' | ']' => '_'
      case c         => c
    }
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
    val networkId: Int
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

  case object CheckStaticPeers

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
  case object PeerSummaryTick
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
