package com.chipprbots.ethereum.blockchain.sync

import org.apache.pekko.actor.Actor
import org.apache.pekko.actor.ActorLogging
import org.apache.pekko.actor.ActorRef
import org.apache.pekko.actor.Cancellable
import org.apache.pekko.actor.Props
import org.apache.pekko.actor.Scheduler

import scala.collection.mutable
import scala.concurrent.ExecutionContext
import scala.reflect.ClassTag

import com.chipprbots.ethereum.blockchain.sync.Blacklist.BlacklistReason
import com.chipprbots.ethereum.blockchain.sync.PeerListSupportNg.PeerWithInfo
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerEvent.MaintainedPeersChanged
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerEvent.PeerDisconnected
import com.chipprbots.ethereum.network.PeerEventBusActor.Subscribe
import com.chipprbots.ethereum.network.PeerEventBusActor.SubscriptionClassifier.MaintainedPeersClassifier
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.PeerInfo
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.PeerId
import com.chipprbots.ethereum.network.p2p.Message
import com.chipprbots.ethereum.network.p2p.MessageSerializable
import com.chipprbots.ethereum.network.p2p.messages.Capability
import com.chipprbots.ethereum.network.p2p.messages.Codes
import com.chipprbots.ethereum.network.p2p.messages.ETH62
import com.chipprbots.ethereum.network.p2p.messages.ETH62._
import com.chipprbots.ethereum.network.p2p.messages.ETH63
import com.chipprbots.ethereum.network.p2p.messages.ETH63.GetNodeData
import com.chipprbots.ethereum.network.p2p.messages.ETH63.NodeData
import com.chipprbots.ethereum.network.p2p.messages.ETH66
import com.chipprbots.ethereum.network.p2p.messages.ETH66.{BlockBodies => ETH66BlockBodies}
import com.chipprbots.ethereum.network.p2p.messages.ETH66.{BlockHeaders => ETH66BlockHeaders}
import com.chipprbots.ethereum.network.p2p.messages.ETH66.{GetBlockBodies => ETH66GetBlockBodies}
import com.chipprbots.ethereum.network.p2p.messages.ETH66.{GetBlockHeaders => ETH66GetBlockHeaders}
import com.chipprbots.ethereum.network.p2p.messages.ETH66.{GetNodeData => ETH66GetNodeData}
import com.chipprbots.ethereum.network.p2p.messages.ETH66.{GetReceipts => ETH66GetReceipts}
import com.chipprbots.ethereum.network.p2p.messages.ETH66.{NodeData => ETH66NodeData}
import com.chipprbots.ethereum.network.p2p.messages.ETH66.{Receipts => ETH66Receipts}
import com.chipprbots.ethereum.network.p2p.messages.SNAP
import com.chipprbots.ethereum.network.p2p.messages.SNAP.ByteCodes
import com.chipprbots.ethereum.network.p2p.messages.SNAP.GetByteCodes
import com.chipprbots.ethereum.network.p2p.messages.SNAP.GetTrieNodes
import com.chipprbots.ethereum.network.p2p.messages.SNAP.TrieNodes
import com.chipprbots.ethereum.utils.Config.SyncConfig

class PeersClient(
    val networkPeerManager: ActorRef,
    val peerEventBus: ActorRef,
    val blacklist: Blacklist,
    val syncConfig: SyncConfig,
    implicit val scheduler: Scheduler
) extends Actor
    with ActorLogging
    with PeerListSupportNg {
  import PeersClient._

  implicit val ec: ExecutionContext = context.dispatcher

  // Besu alignment: PeerDenylistManager.java:56 skips maintained peers at add() call site.
  // Subscribe at startup so updates are received before any BlacklistPeer message can arrive.
  peerEventBus ! Subscribe(MaintainedPeersClassifier)
  private var _maintainedNodeIdHexes: Set[String] = Set.empty
  override protected def maintainedNodeIdHexes: Set[String] = _maintainedNodeIdHexes

  // Tracks GetNodeData capability per peer via observed behavior (not advertised capability).
  // Shared across all StateNodeFetcher actors so the first failure protects all concurrent requests.
  private val nodeDataCooldownUntilMs = mutable.Map.empty[PeerId, Long]
  private val nodeDataConsecutiveFailures = mutable.Map.empty[PeerId, Int]

  override def handlePeerListMessages: Receive = ({ case PeerDisconnected(peerId) =>
    // Intentionally do NOT clear nodeData cooldown on disconnect. A peer that closes
    // the connection when asked for GetNodeData (e.g. BONSAI Besu) will immediately
    // reconnect and repeat the same failure if we reset its state here. The time-based
    // cooldown must be allowed to expire naturally so the peer stays suppressed.
    super.handlePeerListMessages(PeerDisconnected(peerId))
  }: Receive).orElse(super.handlePeerListMessages)

  val statusSchedule: Cancellable =
    scheduler.scheduleWithFixedDelay(syncConfig.printStatusInterval, syncConfig.printStatusInterval, self, PrintStatus)

  def receive: Receive = running(Map())

  override def postStop(): Unit = {
    super.postStop()
    statusSchedule.cancel()
  }

  def running(requesters: Requesters): Receive =
    handlePeerListMessages.orElse {
      case MaintainedPeersChanged(nodeIds) =>
        _maintainedNodeIdHexes = nodeIds
        log.debug("Updated maintained peer node IDs: {} peers", nodeIds.size)
      case PrintStatus                   => printStatus(requesters: Requesters)
      case BlacklistPeer(peerId, reason) => blacklistIfHandshaked(peerId, syncConfig.blacklistDuration, reason)
      case RecordNodeDataFailure(peerId) =>
        val count = nodeDataConsecutiveFailures.getOrElse(peerId, 0) + 1
        nodeDataConsecutiveFailures(peerId) = count
        val cooldownMs = if (count >= 3) 3_600_000L else count * 30_000L
        nodeDataCooldownUntilMs(peerId) = System.currentTimeMillis() + cooldownMs
        log.debug("Peer {} GetNodeData failure #{} — cooldown {}ms", peerId, count, cooldownMs)
      case Request(message, peerSelector, toSerializable) =>
        val requester = sender()
        log.debug(
          "Received request for message type {} using selector {}",
          message.getClass.getSimpleName,
          peerSelector
        )
        log.debug(
          "Total handshaked peers: {}, Available peers (not blacklisted): {}",
          handshakedPeers.size,
          peersToDownloadFrom.size
        )

        if (peersToDownloadFrom.isEmpty && handshakedPeers.nonEmpty) {
          log.debug("All {} handshaked peers are blacklisted", handshakedPeers.size)
          handshakedPeers.foreach { case (peerId, peerInfo) =>
            log.debug(
              "Peer {} ({}): blacklisted={}",
              peerId,
              peerInfo.peer.remoteAddress,
              blacklist.isBlacklisted(peerId)
            )
          }
        }

        selectPeer(peerSelector) match {
          case Some(peer) =>
            log.debug("Selected peer {} with address {} for request", peer.id, peer.remoteAddress.getHostString)
            // Adapt message format based on peer's negotiated capability
            val adaptedMessage = adaptMessageForPeer(peer, message)
            // Create a type-safe conversion function for the adapted message
            val adaptedToSerializable: Message => MessageSerializable = (msg: Message) =>
              msg match {
                case s: MessageSerializable => s
                case _                      => toSerializable(message) // fallback to original
              }
            val handler =
              makeRequest(peer, adaptedMessage, responseMsgCode(adaptedMessage), adaptedToSerializable)(
                scheduler,
                responseClassTag(adaptedMessage)
              )
            val newRequesters = requesters + (handler -> requester)
            context.become(running(newRequesters))
          case None =>
            log.debug(
              "No suitable peer found to issue a request (handshaked: {}, available: {})",
              handshakedPeers.size,
              peersToDownloadFrom.size
            )
            requester ! NoSuitablePeer
        }
      case PeerRequestHandler.ResponseReceived(peer, message, _) =>
        handleResponse(requesters, Response(peer, message.asInstanceOf[Message]))
      case PeerRequestHandler.RequestFailed(peer, reason) =>
        log.warning(s"Request to peer ${peer.remoteAddress} failed - reason: $reason")
        handleResponse(requesters, RequestFailed(peer, BlacklistReason.RegularSyncRequestFailed(reason)))
    }

  private def makeRequest[RequestMsg <: Message, ResponseMsg <: Message](
      peer: Peer,
      requestMsg: RequestMsg,
      responseMsgCode: Int,
      toSerializable: RequestMsg => MessageSerializable
  )(implicit scheduler: Scheduler, classTag: ClassTag[ResponseMsg]): ActorRef =
    context.actorOf(
      PeerRequestHandler.props[RequestMsg, ResponseMsg](
        peer = peer,
        responseTimeout = syncConfig.peerResponseTimeout,
        networkPeerManager = networkPeerManager,
        peerEventBus = peerEventBus,
        requestMsg = requestMsg,
        responseMsgCode = responseMsgCode
      )(classTag, scheduler, toSerializable)
    )

  private def handleResponse[ResponseMsg <: ResponseMessage](requesters: Requesters, responseMsg: ResponseMsg): Unit = {
    val requestHandler = sender()
    requesters.get(requestHandler).foreach(_ ! responseMsg)
    context.become(running(requesters - requestHandler))
  }

  private def selectPeer(peerSelector: PeerSelector): Option[Peer] =
    peerSelector match {
      case BestPeer =>
        log.debug("Selecting best peer from {} available peers", peersToDownloadFrom.size)
        bestPeer(peersToDownloadFrom, log)

      case BestSnapPeer =>
        val snapPeers = peersToDownloadFrom.filter { case (_, peerWithInfo) =>
          peerWithInfo.peerInfo.remoteStatus.supportsSnap
        }
        log.debug(
          "Selecting best SNAP-capable peer from {} available peers ({} SNAP-capable)",
          peersToDownloadFrom.size,
          snapPeers.size
        )
        bestPeer(snapPeers, log)

      case BestNodeDataPeer =>
        val now = System.currentTimeMillis()
        val nodeDataPeers = peersToDownloadFrom.filter { case (peerId, _) =>
          !nodeDataCooldownUntilMs.get(peerId).exists(_ > now)
        }
        log.debug(
          "Selecting best GetNodeData-capable peer from {} available peers ({} capable, {} on cooldown)",
          peersToDownloadFrom.size,
          nodeDataPeers.size,
          nodeDataCooldownUntilMs.count { case (_, exp) => exp > now }
        )
        bestPeer(nodeDataPeers, log)

      case ExcludingPeers(exclude) =>
        val filteredPeers = peersToDownloadFrom.filterNot { case (peerId, _) => exclude.contains(peerId) }
        log.debug(
          "Selecting best peer excluding {} peers from {} available ({} remaining)",
          exclude.size,
          peersToDownloadFrom.size,
          filteredPeers.size
        )
        bestPeer(filteredPeers, log)

      case BestPeerWithMinBlock(minBlock) =>
        // Two-tier selection: peers with known maxBlockNumber >= minBlock are
        // strictly better than peers with maxBlockNumber == 0 (unknown chain
        // state). Try the known-good tier first; if empty, fall back to the
        // unknown tier — which is correct behaviour for ETH/64-68 peers whose
        // maxBlockNumber stays at 0 because their STATUS doesn't carry a
        // block number and we don't receive block messages from them post-merge.
        val knownAheadPeers = peersToDownloadFrom.filter { case (_, peerWithInfo) =>
          peerWithInfo.peerInfo.maxBlockNumber >= minBlock
        }
        if (knownAheadPeers.nonEmpty) {
          log.debug(
            "BestPeerWithMinBlock({}): {} peers have known maxBlockNumber >= target",
            minBlock,
            knownAheadPeers.size
          )
          bestPeer(knownAheadPeers, log)
        } else {
          val unknownChainHeadPeers = peersToDownloadFrom.filter { case (_, peerWithInfo) =>
            peerWithInfo.peerInfo.maxBlockNumber == 0
          }
          log.debug(
            s"BestPeerWithMinBlock($minBlock): no peer with known maxBlockNumber >= target; " +
              s"falling back to ${unknownChainHeadPeers.size} peer(s) with maxBlockNumber=0 (chain state unknown)"
          )
          bestPeer(unknownChainHeadPeers, log)
        }

      case BestPeerWithMinBlockExcluding(minBlock, exclude) =>
        val eligible = peersToDownloadFrom.filterNot { case (peerId, _) => exclude.contains(peerId) }
        val knownAheadPeers = eligible.filter { case (_, peerWithInfo) =>
          peerWithInfo.peerInfo.maxBlockNumber >= minBlock
        }
        if (knownAheadPeers.nonEmpty) {
          log.debug(
            "BestPeerWithMinBlockExcluding({}): {} eligible after excluding {} tried peer(s)",
            minBlock,
            knownAheadPeers.size,
            exclude.size
          )
          bestPeer(knownAheadPeers, log)
        } else {
          val unknownHeadPeers = eligible.filter { case (_, peerWithInfo) =>
            peerWithInfo.peerInfo.maxBlockNumber == 0
          }
          log.debug(
            "BestPeerWithMinBlockExcluding({}): no known-ahead peers after exclusion; {} unknown-chain-state remain",
            minBlock,
            unknownHeadPeers.size
          )
          bestPeer(unknownHeadPeers, log)
        }

      case BestSnapPeerExcluding(exclude) =>
        val snapPeers = peersToDownloadFrom.filter { case (peerId, peerWithInfo) =>
          !exclude.contains(peerId) && peerWithInfo.peerInfo.remoteStatus.supportsSnap
        }
        log.debug(
          "Selecting best SNAP peer excluding {} tried peers ({} SNAP remaining)",
          exclude.size,
          snapPeers.size
        )
        bestPeer(snapPeers, log)

      case BestNodeDataPeerExcluding(exclude) =>
        val now = System.currentTimeMillis()
        val nodeDataPeers = peersToDownloadFrom.filter { case (peerId, _) =>
          !exclude.contains(peerId) &&
          !nodeDataCooldownUntilMs.get(peerId).exists(_ > now)
        }
        log.debug(
          "Selecting best GetNodeData peer excluding {} tried peers ({} capable remaining)",
          exclude.size,
          nodeDataPeers.size
        )
        bestPeer(nodeDataPeers, log)

    }

  /** Adapts message format based on peer's negotiated capability. ETH66+ peers use RequestId wrapper, earlier versions
    * use ETH62/ETH63 format.
    */
  private def adaptMessageForPeer[RequestMsg <: Message](peer: Peer, message: RequestMsg): Message =
    handshakedPeers.get(peer.id) match {
      case Some(peerWithInfo) =>
        val usesRequestId = Capability.usesRequestId(peerWithInfo.peerInfo.remoteStatus.capability)
        message match {
          // GetBlockHeaders adaptation
          case eth66: ETH66GetBlockHeaders if !usesRequestId =>
            // Convert ETH66 format to ETH62 format for older peers
            ETH62.GetBlockHeaders(eth66.block, eth66.maxHeaders, eth66.skip, eth66.reverse)
          case eth62: ETH62.GetBlockHeaders if usesRequestId =>
            // Convert ETH62 format to ETH66 format for newer peers
            ETH66GetBlockHeaders(ETH66.nextRequestId, eth62.block, eth62.maxHeaders, eth62.skip, eth62.reverse)
          // GetBlockBodies adaptation
          case eth66: ETH66GetBlockBodies if !usesRequestId =>
            // Convert ETH66 format to ETH62 format for older peers
            ETH62.GetBlockBodies(eth66.hashes)
          case eth62: ETH62.GetBlockBodies if usesRequestId =>
            // Convert ETH62 format to ETH66 format for newer peers
            ETH66GetBlockBodies(ETH66.nextRequestId, eth62.hashes)
          // GetReceipts adaptation
          case eth66: ETH66GetReceipts if !usesRequestId =>
            // Convert ETH66 format to ETH63 format for older peers
            ETH63.GetReceipts(eth66.blockHashes)
          case eth63: ETH63.GetReceipts if usesRequestId =>
            // Convert ETH63 format to ETH66 format for newer peers
            ETH66GetReceipts(ETH66.nextRequestId, eth63.blockHashes)
          // GetNodeData adaptation
          case eth66: ETH66GetNodeData if !usesRequestId =>
            // Convert ETH66 format to ETH63 format for older peers
            GetNodeData(eth66.mptElementsHashes)
          case eth63: GetNodeData if usesRequestId =>
            // Convert ETH63 format to ETH66 format for newer peers
            ETH66GetNodeData(ETH66.nextRequestId, eth63.mptElementsHashes)
          case _ => message // Already in correct format
        }
      case None =>
        log.warning("Peer {} not found in handshaked peers, using message as-is", peer.id)
        message
    }

  private def responseClassTag[RequestMsg <: Message](requestMsg: RequestMsg): ClassTag[_ <: Message] =
    requestMsg match {
      case _: ETH66GetBlockHeaders  => implicitly[ClassTag[ETH66BlockHeaders]]
      case _: ETH62.GetBlockHeaders => implicitly[ClassTag[ETH62.BlockHeaders]]
      case _: ETH66GetBlockBodies   => implicitly[ClassTag[ETH66BlockBodies]]
      case _: ETH62.GetBlockBodies  => implicitly[ClassTag[ETH62.BlockBodies]]
      case _: ETH66GetReceipts      => implicitly[ClassTag[ETH66Receipts]]
      case _: ETH63.GetReceipts     => implicitly[ClassTag[ETH63.Receipts]]
      case _: GetNodeData           => implicitly[ClassTag[NodeData]]
      case _: ETH66GetNodeData      => implicitly[ClassTag[ETH66NodeData]]
      case _: GetTrieNodes          => implicitly[ClassTag[TrieNodes]]
      case _: GetByteCodes          => implicitly[ClassTag[ByteCodes]]
    }

  private def responseMsgCode[RequestMsg <: Message](requestMsg: RequestMsg): Int =
    requestMsg match {
      case _: ETH66GetBlockHeaders | _: ETH62.GetBlockHeaders => Codes.BlockHeadersCode
      case _: ETH66GetBlockBodies | _: ETH62.GetBlockBodies   => Codes.BlockBodiesCode
      case _: ETH66GetReceipts | _: ETH63.GetReceipts         => Codes.ReceiptsCode
      case _: GetNodeData                                     => Codes.NodeDataCode
      case _: ETH66GetNodeData                                => Codes.NodeDataCode
      case _: GetTrieNodes                                    => SNAP.Codes.TrieNodesCode
      case _: GetByteCodes                                    => SNAP.Codes.ByteCodesCode
    }

  private def printStatus(requesters: Requesters): Unit = {
    log.debug(
      "Request status: requests in progress: {}, available peers: {}",
      requesters.size,
      peersToDownloadFrom.size
    )

    lazy val handshakedPeersStatus = handshakedPeers.map { case (peerId, peerWithInfo) =>
      val peerNetworkStatus = PeerNetworkStatus(
        peerWithInfo.peer,
        isBlacklisted = blacklist.isBlacklisted(peerId)
      )
      (peerNetworkStatus, peerWithInfo.peerInfo)
    }

    log.debug(s"Handshaked peers status (number of peers: ${handshakedPeersStatus.size}): $handshakedPeersStatus")
  }
}

object PeersClient {

  def props(
      networkPeerManager: ActorRef,
      peerEventBus: ActorRef,
      blacklist: Blacklist,
      syncConfig: SyncConfig,
      scheduler: Scheduler
  ): Props =
    Props(new PeersClient(networkPeerManager, peerEventBus, blacklist, syncConfig, scheduler))

  type Requesters = Map[ActorRef, ActorRef]

  sealed trait PeersClientMessage
  case class BlacklistPeer(peerId: PeerId, reason: BlacklistReason) extends PeersClientMessage
  case class RecordNodeDataFailure(peerId: PeerId) extends PeersClientMessage
  case class Request[RequestMsg <: Message](
      message: RequestMsg,
      peerSelector: PeerSelector,
      toSerializable: RequestMsg => MessageSerializable
  ) extends PeersClientMessage

  object Request {

    def create[RequestMsg <: Message](message: RequestMsg, peerSelector: PeerSelector)(implicit
        toSerializable: RequestMsg => MessageSerializable
    ): Request[RequestMsg] =
      Request(message, peerSelector, toSerializable)
  }

  case class PeerNetworkStatus(peer: Peer, isBlacklisted: Boolean) {
    override def toString: String =
      s"PeerNetworkStatus {" +
        s" RemotePeerAddress: ${peer.remoteAddress}," +
        s" ConnectionDirection: ${if (peer.incomingConnection) "Incoming" else "Outgoing"}," +
        s" Is blacklisted?: $isBlacklisted" +
        s" }"
  }
  case object PrintStatus extends PeersClientMessage

  sealed trait ResponseMessage
  case object NoSuitablePeer extends ResponseMessage
  case class RequestFailed(peer: Peer, reason: BlacklistReason) extends ResponseMessage
  case class Response[T <: Message](peer: Peer, message: T) extends ResponseMessage

  sealed trait PeerSelector
  case object BestPeer extends PeerSelector
  case object BestSnapPeer extends PeerSelector
  case object BestNodeDataPeer extends PeerSelector
  case class ExcludingPeers(exclude: Set[PeerId]) extends PeerSelector
  case class BestSnapPeerExcluding(exclude: Set[PeerId]) extends PeerSelector
  case class BestNodeDataPeerExcluding(exclude: Set[PeerId]) extends PeerSelector

  /** Pick a peer whose advertised chain head is at least `minBlock`. Use this for absolute-block-number requests (e.g.
    * PivotHeaderBootstrap targeting a specific pivot) where peers behind that height literally have nothing to return.
    *
    * ETH/69 peers report `latestBlock` in STATUS, so `maxBlockNumber` reflects their true chain head. ETH/64-68 peers
    * don't carry a block number in STATUS and their `maxBlockNumber` stays at `0` post-merge (no incoming block
    * messages to update it via `peerHasUpdatedBestBlock`). We therefore include `maxBlockNumber == 0` peers as a
    * fallback — they MAY have the block but we can't tell.
    */
  case class BestPeerWithMinBlock(minBlock: BigInt) extends PeerSelector

  /** Like BestPeerWithMinBlock but skips peers in `exclude`. Used by PivotHeaderBootstrap to rotate through the peer
    * pool across attempts, modelling Besu's `waitForPeer((p) -> !peersUsed.contains(p))` predicate and go-ethereum's
    * idle-pool exclusion in `skeleton.assignTasks()`.
    */
  case class BestPeerWithMinBlockExcluding(minBlock: BigInt, exclude: Set[PeerId]) extends PeerSelector

  def bestPeer(
      peersToDownloadFrom: Map[PeerId, PeerWithInfo],
      log: org.apache.pekko.event.LoggingAdapter
  ): Option[Peer] = {
    log.debug("Evaluating {} peers to find best peer", peersToDownloadFrom.size)

    // Filter out peers whose bestHash == genesisHash. These peers have nothing to
    // serve and silently return empty responses to GetBlockHeaders, GetBlockBodies,
    // GetReceipts etc. — masking sync wedges as transient timeouts.
    //
    // Bug #1201 (Sepolia): half the post-fork-fix peer pool was Sepolia bootnodes
    // sitting at genesis (`bestHash == genesisHash`, TD=131072). PivotHeaderBootstrap's
    // BestPeer selection round-robined into them and reported "no header returned"
    // for blocks they literally don't have. forkAccepted=true is necessary but not
    // sufficient — the peer must also have advanced past genesis.
    val peersToUse = peersToDownloadFrom.values
      .map { case PeerWithInfo(peer, peerInfo) =>
        val isReady = peerInfo.forkAccepted && !peerInfo.isAtGenesis
        log.debug(
          s"Peer ${peer.id} (${peer.remoteAddress}) - ready: $isReady, " +
            s"maxBlock: ${peerInfo.maxBlockNumber}, atGenesis: ${peerInfo.isAtGenesis}"
        )
        log.debug("Peer {} chainWeight: {}", peer.id, peerInfo.chainWeight)
        (peer, peerInfo, isReady)
      }
      .collect { case (peer, peerInfo, true) =>
        log.debug("Peer {} is ready and eligible for selection", peer.id)
        log.debug("Peer {} chainWeight: {}", peer.id, peerInfo.chainWeight)
        (peer, peerInfo.chainWeight)
      }

    if (peersToUse.nonEmpty) {
      val (peer, chainWeight) = peersToUse.maxBy(_._2)
      log.debug("Selected best peer {} with chainWeight {}", peer.id, chainWeight)
      Some(peer)
    } else {
      log.debug("No ready peers available for selection from {} total peers", peersToDownloadFrom.size)
      None
    }
  }

  // Legacy method for backward compatibility — kept in sync with the logger-aware
  // overload above: skip forkRejected peers AND skip peers stuck at genesis.
  def bestPeer(peersToDownloadFrom: Map[PeerId, PeerWithInfo]): Option[Peer] = {
    val peersToUse = peersToDownloadFrom.values
      .collect {
        case PeerWithInfo(peer, peerInfo) if peerInfo.forkAccepted && !peerInfo.isAtGenesis =>
          (peer, peerInfo.chainWeight)
      }

    if (peersToUse.nonEmpty) {
      val (peer, _) = peersToUse.maxBy(_._2)
      Some(peer)
    } else {
      None
    }
  }

  /** Static helper mirroring the BestPeerWithMinBlock selector for unit testing. */
  def bestPeerWithMinBlock(
      peersToDownloadFrom: Map[PeerId, PeerWithInfo],
      minBlock: BigInt
  ): Option[Peer] = {
    val knownAheadPeers = peersToDownloadFrom.filter { case (_, peerWithInfo) =>
      peerWithInfo.peerInfo.maxBlockNumber >= minBlock
    }
    if (knownAheadPeers.nonEmpty) bestPeer(knownAheadPeers)
    else
      bestPeer(peersToDownloadFrom.filter { case (_, peerWithInfo) =>
        peerWithInfo.peerInfo.maxBlockNumber == 0
      })
  }

  /** Static helper mirroring the BestPeerWithMinBlockExcluding selector for unit testing. */
  def bestPeerWithMinBlockExcluding(
      peersToDownloadFrom: Map[PeerId, PeerWithInfo],
      minBlock: BigInt,
      exclude: Set[PeerId]
  ): Option[Peer] = {
    val eligible = peersToDownloadFrom.filterNot { case (peerId, _) => exclude.contains(peerId) }
    val knownAheadPeers = eligible.filter { case (_, peerWithInfo) =>
      peerWithInfo.peerInfo.maxBlockNumber >= minBlock
    }
    if (knownAheadPeers.nonEmpty) bestPeer(knownAheadPeers)
    else bestPeer(eligible.filter { case (_, peerWithInfo) => peerWithInfo.peerInfo.maxBlockNumber == 0 })
  }
}
