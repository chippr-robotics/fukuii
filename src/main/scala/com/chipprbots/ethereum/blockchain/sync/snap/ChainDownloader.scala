package com.chipprbots.ethereum.blockchain.sync.snap

import org.apache.pekko.actor.Actor
import org.apache.pekko.actor.ActorLogging
import org.apache.pekko.actor.ActorRef
import org.apache.pekko.actor.Props
import org.apache.pekko.actor.Scheduler
import org.apache.pekko.util.ByteString

import scala.collection.mutable
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

import com.chipprbots.ethereum.blockchain.sync.Blacklist
import com.chipprbots.ethereum.blockchain.sync.Blacklist._
import com.chipprbots.ethereum.blockchain.sync.Blacklist.BlacklistReason._
import com.chipprbots.ethereum.blockchain.sync.CacheBasedBlacklist
import com.chipprbots.ethereum.blockchain.sync.PeerListSupportNg
import com.chipprbots.ethereum.blockchain.sync.PeerRequestHandler
import com.chipprbots.ethereum.blockchain.sync.PeerRequestHandler.RequestFailed
import com.chipprbots.ethereum.blockchain.sync.PeerRequestHandler.ResponseReceived
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.domain.BlockchainWriter
import com.chipprbots.ethereum.domain.BlockBody
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.PeerId
import com.chipprbots.ethereum.network.p2p.messages.Codes
import com.chipprbots.ethereum.network.p2p.messages.ETH66
import com.chipprbots.ethereum.network.p2p.messages.ETH66.GetBlockHeaders.GetBlockHeadersEnc
import com.chipprbots.ethereum.network.p2p.messages.ETH66.GetBlockBodies.GetBlockBodiesEnc
import com.chipprbots.ethereum.network.p2p.messages.ETH66.GetReceipts.GetReceiptsEnc
import com.chipprbots.ethereum.rlp._
import com.chipprbots.ethereum.domain.Receipt
import com.chipprbots.ethereum.network.p2p.messages.ETH63.ReceiptImplicits._
import com.chipprbots.ethereum.utils.Config.SyncConfig

/** Downloads block headers, bodies, and receipts from genesis to a target block in parallel with SNAP state sync.
  *
  * This follows the Geth/Nethermind pattern of overlapping chain download with state download. Chain data (headers,
  * bodies, receipts) is canonical and valid regardless of which pivot block is used for state sync. By downloading
  * chain data during SNAP sync, the node is ready for regular sync immediately after state download completes.
  *
  * Uses a simple pipeline: headers first, then bodies and receipts for downloaded headers. Each peer gets at most one
  * outstanding request to avoid contention with SNAP state sync (which has higher priority).
  */
class ChainDownloader(
    blockchainReader: BlockchainReader,
    blockchainWriter: BlockchainWriter,
    val networkPeerManager: ActorRef,
    val peerEventBus: ActorRef,
    val blacklist: Blacklist,
    val syncConfig: SyncConfig,
    val scheduler: Scheduler,
    initialMaxConcurrentRequests: Int,
    requestTimeout: FiniteDuration = 10.seconds
)(implicit ec: ExecutionContext)
    extends Actor
    with ActorLogging
    with PeerListSupportNg {

  import ChainDownloader._

  // PeerRequestHandler.props requires an implicit Scheduler
  implicit val implicitScheduler: Scheduler = scheduler

  private var targetBlock: BigInt = 0
  private var bestHeaderNumber: BigInt = 0
  private var started = false
  private var paused = false

  // Queues of block hashes needing bodies/receipts
  private val bodiesQueue: mutable.ArrayDeque[ByteString] = mutable.ArrayDeque.empty
  private val receiptsQueue: mutable.ArrayDeque[ByteString] = mutable.ArrayDeque.empty

  // Track in-flight requests to limit concurrency
  private var headerRequestPeers: Set[PeerId] = Set.empty
  private var bodyRequestPeers: Map[ActorRef, (Peer, Seq[ByteString])] = Map.empty
  private var receiptRequestPeers: Map[ActorRef, (Peer, Seq[ByteString])] = Map.empty

  // Stats
  private var headersDownloaded: BigInt = 0
  private var bodiesDownloaded: BigInt = 0
  private var receiptsDownloaded: BigInt = 0
  private var lastLogTime: Long = 0

  // Besu-aligned D14: single concurrency throughout (no boost mode after state sync).
  private var maxConcurrentRequests: Int = initialMaxConcurrentRequests

  // Dispatch timer
  private var dispatchTask: Option[org.apache.pekko.actor.Cancellable] = None

  override def receive: Receive = idle

  def idle: Receive = handlePeerListMessages.orElse {
    case Start(target) =>
      targetBlock = target
      started = true
      // Find where we left off (check what's already stored)
      bestHeaderNumber = findBestStoredHeader()
      log.info(
        "Chain download started: target={}, resuming from header {}, bodies queue={}, receipts queue={}",
        targetBlock,
        bestHeaderNumber,
        bodiesQueue.size,
        receiptsQueue.size
      )
      scheduleDispatch()
      context.become(downloading)

    case UpdateTarget(newTarget) =>
      // Re-start downloading if target was updated after completion (e.g. pivot refreshed from 0 to real block)
      if (newTarget > targetBlock && newTarget > bestHeaderNumber) {
        targetBlock = newTarget
        started = true
        bestHeaderNumber = findBestStoredHeader()
        log.info("Chain download restarted with new target: {}, resuming from header {}", newTarget, bestHeaderNumber)
        scheduleDispatch()
        context.become(downloading)
      }

    case GetProgress =>
      sender() ! Progress(headersDownloaded, bodiesDownloaded, receiptsDownloaded, targetBlock)
  }

  def downloading: Receive = handlePeerListMessages.orElse {
    case Dispatch =>
      if (!paused) dispatchRequests()

    case Pause =>
      if (!paused) {
        paused = true
        log.info("Chain download paused (yielding peers for pivot bootstrap)")
      }

    case Resume =>
      if (paused) {
        paused = false
        log.info("Chain download resumed")
        dispatchRequests()
      }

    case UpdateTarget(newTarget) =>
      if (newTarget > targetBlock) {
        log.info("Chain download target updated: {} -> {}", targetBlock, newTarget)
        targetBlock = newTarget
      }

    case Stop =>
      log.info(
        "Chain download stopped. Headers: {}, Bodies: {}, Receipts: {}",
        headersDownloaded,
        bodiesDownloaded,
        receiptsDownloaded
      )
      dispatchTask.foreach(_.cancel())
      context.become(idle)

    case GetProgress =>
      sender() ! Progress(headersDownloaded, bodiesDownloaded, receiptsDownloaded, targetBlock)

    // --- Header responses ---
    case ResponseReceived(peer, ETH66.BlockHeaders(_, headers), _) =>
      headerRequestPeers -= peer.id
      if (headers.nonEmpty) {
        handleHeaders(peer, headers)
      }
      dispatchRequests()

    case RequestFailed(peer, reason) =>
      headerRequestPeers -= peer.id
      bodyRequestPeers.find(_._2._1.id == peer.id).foreach { case (ref, _) =>
        bodyRequestPeers -= ref
      }
      receiptRequestPeers.find(_._2._1.id == peer.id).foreach { case (ref, _) =>
        receiptRequestPeers -= ref
      }
      log.debug("Chain download request failed for peer {}: {}", peer.id, reason)
      blacklist.add(peer.id, syncConfig.blacklistDuration, FastSyncRequestFailed(reason))
      dispatchRequests()

    // --- Body responses ---
    case ResponseReceived(peer, ETH66.BlockBodies(_, bodies), _) =>
      bodyRequestPeers.get(sender()).foreach { case (_, requestedHashes) =>
        bodyRequestPeers -= sender()
        handleBodies(peer, requestedHashes, bodies)
      }
      dispatchRequests()

    // --- Receipt responses ---
    case ResponseReceived(peer, eth66Receipts: ETH66.Receipts, _) =>
      receiptRequestPeers.get(sender()).foreach { case (_, requestedHashes) =>
        receiptRequestPeers -= sender()
        handleReceipts(peer, requestedHashes, eth66Receipts)
      }
      dispatchRequests()
  }

  private def dispatchRequests(): Unit = {
    val inFlightCount = headerRequestPeers.size + bodyRequestPeers.size + receiptRequestPeers.size
    if (inFlightCount >= maxConcurrentRequests) return

    val available = peersToDownloadFrom.filterNot { case (peerId, _) =>
      headerRequestPeers.contains(peerId) ||
      bodyRequestPeers.values.exists(_._1.id == peerId) ||
      receiptRequestPeers.values.exists(_._1.id == peerId)
    }

    if (available.isEmpty) return

    val peers = available.values.toList
    var slotsLeft = maxConcurrentRequests - inFlightCount
    var peerIdx = 0

    // Priority 1: Headers (if we haven't reached target yet)
    while (slotsLeft > 0 && peerIdx < peers.size && bestHeaderNumber < targetBlock) {
      val peerWithInfo = peers(peerIdx)
      if (!headerRequestPeers.contains(peerWithInfo.peer.id)) {
        requestHeaders(peerWithInfo.peer)
        slotsLeft -= 1
        // Only one header request at a time to maintain sequential ordering
        peerIdx = peers.size // break
      }
      peerIdx += 1
    }

    peerIdx = 0

    // Priority 2: Bodies
    while (slotsLeft > 0 && peerIdx < peers.size && bodiesQueue.nonEmpty) {
      val peerWithInfo = peers(peerIdx)
      val peerId = peerWithInfo.peer.id
      if (
        !headerRequestPeers.contains(peerId) &&
        !bodyRequestPeers.values.exists(_._1.id == peerId)
      ) {
        requestBodies(peerWithInfo.peer)
        slotsLeft -= 1
      }
      peerIdx += 1
    }

    peerIdx = 0

    // Priority 3: Receipts
    while (slotsLeft > 0 && peerIdx < peers.size && receiptsQueue.nonEmpty) {
      val peerWithInfo = peers(peerIdx)
      val peerId = peerWithInfo.peer.id
      if (
        !headerRequestPeers.contains(peerId) &&
        !bodyRequestPeers.values.exists(_._1.id == peerId) &&
        !receiptRequestPeers.values.exists(_._1.id == peerId)
      ) {
        requestReceipts(peerWithInfo.peer)
        slotsLeft -= 1
      }
      peerIdx += 1
    }

    // Check if we're done
    checkCompletion()

    // Log progress periodically
    val now = System.currentTimeMillis()
    if (now - lastLogTime > 30000) {
      lastLogTime = now
      val pct = if (targetBlock > 0) (bestHeaderNumber * 100 / targetBlock).toInt else 0
      log.info(
        s"Chain download: headers=$bestHeaderNumber/$targetBlock(${pct}%), bodies=$bodiesDownloaded, receipts=$receiptsDownloaded, peers=${available.size}, inflight=$inFlightCount"
      )
    }
  }

  private def requestHeaders(peer: Peer): Unit = {
    val remaining = targetBlock - bestHeaderNumber
    val limit = remaining.min(syncConfig.blockHeadersPerRequest)

    if (limit <= 0) return

    headerRequestPeers += peer.id

    val requestMsg = ETH66.GetBlockHeaders(
      ETH66.nextRequestId,
      Left(bestHeaderNumber + 1),
      limit,
      skip = 0,
      reverse = false
    )

    context.actorOf(
      PeerRequestHandler
        .props[ETH66.GetBlockHeaders, ETH66.BlockHeaders](
          peer,
          requestTimeout,
          networkPeerManager,
          peerEventBus,
          requestMsg,
          Codes.BlockHeadersCode
        ),
      s"chain-headers-${bestHeaderNumber + 1}-${System.nanoTime()}"
    )
  }

  private def requestBodies(peer: Peer): Unit = {
    val batch = bodiesQueue.take(syncConfig.blockBodiesPerRequest).toVector
    if (batch.isEmpty) return

    bodiesQueue.dropInPlace(batch.size)

    val requestMsg = ETH66.GetBlockBodies(ETH66.nextRequestId, batch)

    val handler = context.actorOf(
      PeerRequestHandler
        .props[ETH66.GetBlockBodies, ETH66.BlockBodies](
          peer,
          requestTimeout,
          networkPeerManager,
          peerEventBus,
          requestMsg,
          Codes.BlockBodiesCode
        ),
      s"chain-bodies-${System.nanoTime()}"
    )

    bodyRequestPeers += (handler -> (peer, batch))
  }

  private def requestReceipts(peer: Peer): Unit = {
    val batch = receiptsQueue.take(syncConfig.receiptsPerRequest).toVector
    if (batch.isEmpty) return

    receiptsQueue.dropInPlace(batch.size)

    val requestMsg = ETH66.GetReceipts(ETH66.nextRequestId, batch)

    val handler = context.actorOf(
      PeerRequestHandler
        .props[ETH66.GetReceipts, ETH66.Receipts](
          peer,
          requestTimeout,
          networkPeerManager,
          peerEventBus,
          requestMsg,
          Codes.ReceiptsCode
        ),
      s"chain-receipts-${System.nanoTime()}"
    )

    receiptRequestPeers += (handler -> (peer, batch))
  }

  private def handleHeaders(peer: Peer, headers: Seq[BlockHeader]): Unit = {
    val expectedStart = bestHeaderNumber + 1

    // Find usable headers: skip any before our expected start, use what extends our chain
    val usable = if (headers.head.number == expectedStart) {
      headers
    } else if (headers.head.number < expectedStart && headers.last.number >= expectedStart) {
      // Response overlaps — trim to the portion we need
      val trimmed = headers.dropWhile(_.number < expectedStart)
      log.debug(
        "Chain download: trimmed overlapping headers {}-{} to start at {} ({} usable)",
        headers.head.number,
        headers.last.number,
        expectedStart,
        trimmed.size
      )
      trimmed
    } else if (headers.head.number > expectedStart) {
      // Gap — can't use without the intervening headers
      log.debug(
        "Chain download: peer {} sent headers starting at {} but we need {} (gap)",
        peer.id,
        headers.head.number,
        expectedStart
      )
      return
    } else {
      // All stale (before our cursor)
      log.debug(
        "Chain download: peer {} sent stale headers {}-{}, already past {}",
        peer.id,
        headers.head.number,
        headers.last.number,
        expectedStart
      )
      return
    }

    // Validate parent hash chaining
    var prevHash = blockchainReader.getBlockHeaderByNumber(bestHeaderNumber).map(_.hash)
    var validCount = 0

    for (header <- usable)
      if (prevHash.exists(_ == header.parentHash)) {
        // Store header + chain weight
        val parentWeight = blockchainReader
          .getChainWeightByHash(header.parentHash)
          .getOrElse(ChainWeight.totalDifficultyOnly(0))

        blockchainWriter
          .storeBlockHeader(header)
          .and(blockchainWriter.storeChainWeight(header.hash, parentWeight.increase(header)))
          .commit()

        bodiesQueue += header.hash
        receiptsQueue += header.hash
        prevHash = Some(header.hash)
        validCount += 1
      } else {
        log.warning(
          "Chain download: header {} parent hash mismatch from peer {}",
          header.number,
          peer.id
        )
        blacklist.add(peer.id, syncConfig.blacklistDuration, ErrorInBlockHeaders)
        // Keep what we validated so far
        if (validCount > 0) {
          bestHeaderNumber += validCount
          headersDownloaded += validCount
        }
        return
      }

    bestHeaderNumber += validCount
    headersDownloaded += validCount
  }

  private def handleBodies(peer: Peer, requestedHashes: Seq[ByteString], bodies: Seq[BlockBody]): Unit = {
    if (bodies.isEmpty) {
      // Re-queue the hashes
      bodiesQueue.prependAll(requestedHashes)
      blacklist.add(
        peer.id,
        syncConfig.blacklistDuration,
        EmptyBlockBodies(requestedHashes.map(h => s"0x${h.toArray.map("%02x".format(_)).mkString}"))
      )
      return
    }

    // Store received bodies
    val received = requestedHashes.zip(bodies)
    received
      .map { case (hash, body) => blockchainWriter.storeBlockBody(hash, body) }
      .reduce(_.and(_))
      .commit()

    bodiesDownloaded += received.size

    // Re-queue any remaining hashes that weren't served
    val remaining = requestedHashes.drop(bodies.size)
    if (remaining.nonEmpty) {
      bodiesQueue.prependAll(remaining)
    }
  }

  private def handleReceipts(
      peer: Peer,
      requestedHashes: Seq[ByteString],
      eth66Receipts: ETH66.Receipts
  ): Unit = {
    import com.chipprbots.ethereum.network.p2p.messages.BaseETH6XMessages.TypedTransaction._

    val hashStrings = requestedHashes.map(h => s"0x${h.toArray.map("%02x".format(_)).mkString}")
    val receiptsRlp = eth66Receipts.receiptsForBlocks
    if (receiptsRlp.items.isEmpty) {
      receiptsQueue.prependAll(requestedHashes)
      blacklist.add(peer.id, syncConfig.blacklistDuration, EmptyReceipts(hashStrings))
      return
    }

    try {
      // Decode using the same approach as ETH63.Receipts.ReceiptsDec
      val receiptsByBlock: Seq[Seq[Receipt]] = receiptsRlp.items.collect { case blockReceipts: RLPList =>
        blockReceipts.items
          .flatMap {
            case v: RLPValue =>
              val receiptBytes = v.bytes
              if (receiptBytes.nonEmpty && (receiptBytes(0) & 0xff) < 0x7f && receiptBytes.length > 1) {
                try Seq(RLPValue(Array(receiptBytes(0))), rawDecode(receiptBytes.tail))
                catch { case _: Exception => Seq(v) }
              } else Seq(v)
            case other => Seq(other)
          }
          .toTypedRLPEncodables
          .map(_.toReceipt)
      }

      // Store receipts
      requestedHashes.zip(receiptsByBlock).foreach { case (hash, receipts) =>
        blockchainWriter.storeReceipts(hash, receipts).commit()
      }

      receiptsDownloaded += receiptsByBlock.size

      // Re-queue remaining
      val remaining = requestedHashes.drop(receiptsByBlock.size)
      if (remaining.nonEmpty) {
        receiptsQueue.prependAll(remaining)
      }
    } catch {
      case ex: Exception =>
        log.warning("Chain download: failed to decode receipts from peer {}: {}", peer.id, ex.getMessage)
        receiptsQueue.prependAll(requestedHashes)
        blacklist.add(
          peer.id,
          syncConfig.blacklistDuration,
          FastSyncRequestFailed(s"Invalid receipts: ${ex.getMessage}")
        )
    }
  }

  private def checkCompletion(): Unit =
    if (
      bestHeaderNumber >= targetBlock &&
      bodiesQueue.isEmpty &&
      receiptsQueue.isEmpty &&
      bodyRequestPeers.isEmpty &&
      receiptRequestPeers.isEmpty
    ) {
      log.info(
        "Chain download COMPLETE: {} headers, {} bodies, {} receipts downloaded to block {}",
        headersDownloaded,
        bodiesDownloaded,
        receiptsDownloaded,
        targetBlock
      )
      dispatchTask.foreach(_.cancel())
      context.parent ! Done
      context.become(idle)
    }

  private def findBestStoredHeader(): BigInt = {
    // Binary search for the highest stored header starting from genesis
    // The chain may have been partially downloaded in a previous run
    var low: BigInt = 0
    var high: BigInt = targetBlock
    var best: BigInt = 0

    // Quick check: if genesis+1 doesn't exist, start from 0
    if (blockchainReader.getBlockHeaderByNumber(1).isEmpty) return 0

    while (low <= high) {
      val mid = (low + high) / 2
      if (blockchainReader.getBlockHeaderByNumber(mid).isDefined) {
        best = mid
        low = mid + 1
      } else {
        high = mid - 1
      }
    }

    // Also rebuild the body/receipt queues for headers we have but bodies/receipts we don't
    var i = best
    while (i >= 1) {
      blockchainReader.getBlockHeaderByNumber(i) match {
        case Some(header) =>
          if (blockchainReader.getBlockBodyByHash(header.hash).isEmpty) {
            bodiesQueue += header.hash
          }
          if (blockchainReader.getReceiptsByHash(header.hash).isEmpty) {
            receiptsQueue += header.hash
          }
        case None => // shouldn't happen
      }
      i -= 1
    }

    best
  }

  // boostConcurrency() removed: Besu-aligned D14 (no boost mode).

  private def scheduleDispatch(interval: FiniteDuration = 2.seconds): Unit = {
    dispatchTask.foreach(_.cancel())
    dispatchTask = Some(
      scheduler.scheduleWithFixedDelay(1.second, interval, self, Dispatch)(ec)
    )
  }

  override def postStop(): Unit = {
    dispatchTask.foreach(_.cancel())
    super.postStop()
  }
}

object ChainDownloader {
  case class Start(targetBlock: BigInt)
  case class UpdateTarget(newTarget: BigInt)
  case object Pause
  case object Resume
  case object Stop
  case object Done
  // BoostConcurrency removed: Besu-aligned D14 (no boost mode, single concurrency throughout).
  case object GetProgress
  case class Progress(
      headersDownloaded: BigInt,
      bodiesDownloaded: BigInt,
      receiptsDownloaded: BigInt,
      targetBlock: BigInt
  )
  private case object Dispatch

  def props(
      blockchainReader: BlockchainReader,
      blockchainWriter: BlockchainWriter,
      networkPeerManager: ActorRef,
      peerEventBus: ActorRef,
      syncConfig: SyncConfig,
      scheduler: Scheduler,
      maxConcurrentRequests: Int = 4,
      requestTimeout: FiniteDuration = 10.seconds
  )(implicit ec: ExecutionContext): Props =
    Props(
      new ChainDownloader(
        blockchainReader,
        blockchainWriter,
        networkPeerManager,
        peerEventBus,
        CacheBasedBlacklist.empty(1000),
        syncConfig,
        scheduler,
        maxConcurrentRequests,
        requestTimeout
      )
    )
}
