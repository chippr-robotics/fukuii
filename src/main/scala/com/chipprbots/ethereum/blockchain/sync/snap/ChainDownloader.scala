package com.chipprbots.ethereum.blockchain.sync.snap

import org.apache.pekko.actor.Actor
import org.apache.pekko.actor.ActorLogging
import org.apache.pekko.actor.ActorRef
import org.apache.pekko.actor.Props
import org.apache.pekko.actor.Scheduler
import org.apache.pekko.util.ByteString

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

import com.chipprbots.ethereum.blockchain.sync.Blacklist
import com.chipprbots.ethereum.blockchain.sync.Blacklist.BlacklistReason._
import com.chipprbots.ethereum.blockchain.sync.CacheBasedBlacklist
import com.chipprbots.ethereum.blockchain.sync.PeerListSupportNg
import com.chipprbots.ethereum.blockchain.sync.PeerRequestHandler
import com.chipprbots.ethereum.blockchain.sync.PeerRequestHandler.RequestFailed
import com.chipprbots.ethereum.blockchain.sync.PeerRequestHandler.ResponseReceived
import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.domain.BlockchainWriter
import com.chipprbots.ethereum.domain.BlockBody
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.PeerId
import com.chipprbots.ethereum.network.p2p.messages.Codes
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.GetBlockHeaders.GetBlockHeadersEnc
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.GetBlockBodies.GetBlockBodiesEnc
import com.chipprbots.ethereum.network.p2p.messages.Capability
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.GetReceipts.GetReceiptsEnc
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.GetReceipts70.GetReceipts70Enc
import com.chipprbots.ethereum.blockchain.sync.PeerListSupportNg.PeerWithInfo
import com.chipprbots.ethereum.rlp._
import com.chipprbots.ethereum.domain.Receipt
import com.chipprbots.ethereum.blockchain.sync.codec.ReceiptCodecs._
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
    appStateStorage: AppStateStorage,
    val networkPeerManager: ActorRef,
    val peerEventBus: ActorRef,
    val blacklist: Blacklist,
    val syncConfig: SyncConfig,
    val scheduler: Scheduler,
    initialMaxConcurrentRequests: Int,
    requestTimeout: FiniteDuration = 10.seconds,
    snapServerPeerNodeIds: Set[ByteString] = Set.empty
)(implicit ec: ExecutionContext)
    extends Actor
    with ActorLogging
    with PeerListSupportNg {

  import ChainDownloader._

  // PeerRequestHandler.props requires an implicit Scheduler
  implicit val implicitScheduler: Scheduler = scheduler

  private var targetBlock: BigInt = 0
  private var bestHeaderNumber: BigInt = 0
  private var paused = false

  // Queues of block hashes needing bodies/receipts
  private var bodiesQueue: Vector[ByteString] = Vector.empty
  private var receiptsQueue: Vector[ByteString] = Vector.empty

  // Track in-flight requests to limit concurrency
  private var headerRequestPeers: Set[PeerId] = Set.empty
  private var bodyRequestPeers: Map[ActorRef, (Peer, Seq[ByteString])] = Map.empty
  private var receiptRequestPeers: Map[ActorRef, (Peer, Seq[ByteString])] = Map.empty

  // go-ethereum behavioural backoff: peers that returned empty headers are excluded from
  // header dispatch (capacity-to-zero equivalent). Bodies/receipts/SNAP unaffected.
  private var emptyHeaderPeers: Set[PeerId] = Set.empty

  // ETH70 partial receipt tracking: hash → next resume index (receipts already received)
  private var partialReceiptState: Map[ByteString, Long] = Map.empty
  // ETH70 partial receipt buffer: hash → RLP-encoded receipts accumulated so far
  private var partialReceiptBuffer: Map[ByteString, Seq[RLPEncodeable]] = Map.empty

  // Stats
  private var headersDownloaded: BigInt = 0
  private var bodiesDownloaded: BigInt = 0
  private var receiptsDownloaded: BigInt = 0
  private var lastLogTime: Long = 0

  // Concurrency — starts conservative during SNAP state sync, boosted after state completes
  private var maxConcurrentRequests: Int = initialMaxConcurrentRequests

  // Visible to tests in the same package: lets ChainDownloaderSpec assert YieldToRegularSync clamping
  // without relying on log-output scraping or reflection.
  private[snap] def currentMaxConcurrentRequests: Int = maxConcurrentRequests
  private[snap] def isHeaderExcluded(peerId: PeerId): Boolean = emptyHeaderPeers.contains(peerId)

  // Dispatch timer
  private var dispatchTask: Option[org.apache.pekko.actor.Cancellable] = None

  override def receive: Receive = idle

  def idle: Receive = handlePeerListMessages.orElse {
    case Start(target) =>
      targetBlock = target
      // Persist the target so a node restart mid-backfill can resume standalone (#1169).
      appStateStorage.putBackfillTarget(target).commit()
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
        appStateStorage.putBackfillTarget(newTarget).commit()
        bestHeaderNumber = findBestStoredHeader()
        log.info("Chain download restarted with new target: {}, resuming from header {}", newTarget, bestHeaderNumber)
        scheduleDispatch()
        context.become(downloading)
      }

    case BoostConcurrency(n) =>
      boostConcurrency(n)

    case YieldToRegularSync(n) =>
      yieldToRegularSync(n)

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
        appStateStorage.putBackfillTarget(newTarget).commit()
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

    case BoostConcurrency(n) =>
      boostConcurrency(n)
      dispatchRequests() // Immediately use the new slots

    case YieldToRegularSync(n) =>
      yieldToRegularSync(n)

    case GetProgress =>
      sender() ! Progress(headersDownloaded, bodiesDownloaded, receiptsDownloaded, targetBlock)

    // --- Header responses ---
    case ResponseReceived(peer, ETHPackets.BlockHeaders(_, headers), _) =>
      headerRequestPeers -= peer.id
      if (headers.nonEmpty) {
        emptyHeaderPeers -= peer.id
        handleHeaders(peer, headers)
      } else {
        emptyHeaderPeers += peer.id
        log.debug("Empty headers from {} — excluding from header dispatch", peer.id)
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
    case ResponseReceived(peer, ETHPackets.BlockBodies(_, bodies), _) =>
      bodyRequestPeers.get(sender()).foreach { case (_, requestedHashes) =>
        bodyRequestPeers -= sender()
        handleBodies(peer, requestedHashes, bodies)
      }
      dispatchRequests()

    // --- Receipt responses ---
    case ResponseReceived(peer, eth66Receipts: ETHPackets.Receipts68, _) =>
      receiptRequestPeers.get(sender()).foreach { case (_, requestedHashes) =>
        receiptRequestPeers -= sender()
        handleReceipts(peer, requestedHashes, eth66Receipts)
      }
      dispatchRequests()

    // ETH70 partial receipt delivery
    case ResponseReceived(peer, receipts70: ETHPackets.Receipts70, _) =>
      receiptRequestPeers.get(sender()).foreach { case (_, requestedHashes) =>
        receiptRequestPeers -= sender()
        handleReceipts70(peer, requestedHashes, receipts70)
      }
      dispatchRequests()
  }

  private def dispatchRequests(): Unit = {
    val inFlightCount = headerRequestPeers.size + bodyRequestPeers.size + receiptRequestPeers.size
    if (inFlightCount >= maxConcurrentRequests) return

    val available = peersToDownloadFrom.filterNot { case (peerId, p) =>
      headerRequestPeers.contains(peerId) ||
      bodyRequestPeers.values.exists(_._1.id == peerId) ||
      receiptRequestPeers.values.exists(_._1.id == peerId) ||
      p.peer.nodeId.exists(snapServerPeerNodeIds.contains) ||
      emptyHeaderPeers.contains(peerId)
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
        requestReceipts(peerWithInfo)
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

    val requestMsg = ETHPackets.GetBlockHeaders(
      ETHPackets.nextRequestId,
      Left(bestHeaderNumber + 1),
      limit,
      skip = 0,
      reverse = false
    )

    context.actorOf(
      PeerRequestHandler
        .props[ETHPackets.GetBlockHeaders, ETHPackets.BlockHeaders](
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
    val batch = bodiesQueue.take(syncConfig.blockBodiesPerRequest)
    if (batch.isEmpty) return

    bodiesQueue = bodiesQueue.drop(batch.size)

    val requestMsg = ETHPackets.GetBlockBodies(ETHPackets.nextRequestId, batch)

    val handler = context.actorOf(
      PeerRequestHandler
        .props[ETHPackets.GetBlockBodies, ETHPackets.BlockBodies](
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

  private def requestReceipts(peerWithInfo: PeerWithInfo): Unit = {
    val batch = receiptsQueue.take(syncConfig.receiptsPerRequest)
    if (batch.isEmpty) return

    receiptsQueue = receiptsQueue.drop(batch.size)

    val peer = peerWithInfo.peer
    val isEth70 = peerWithInfo.peerInfo.remoteStatus.capability == Capability.ETH70

    val handler = if (isEth70) {
      // ETH70: resume partial delivery from the buffered index for the first block in batch
      val firstBlockResumeIdx = partialReceiptState.getOrElse(batch.head, 0L)
      val requestMsg = ETHPackets.GetReceipts70(ETHPackets.nextRequestId, firstBlockResumeIdx, batch)
      context.actorOf(
        PeerRequestHandler
          .props[ETHPackets.GetReceipts70, ETHPackets.Receipts70](
            peer,
            requestTimeout,
            networkPeerManager,
            peerEventBus,
            requestMsg,
            Codes.ReceiptsCode
          ),
        s"chain-receipts-eth70-${System.nanoTime()}"
      )
    } else {
      val requestMsg = ETHPackets.GetReceipts(ETHPackets.nextRequestId, batch)
      context.actorOf(
        PeerRequestHandler
          .props[ETHPackets.GetReceipts, ETHPackets.Receipts68](
            peer,
            requestTimeout,
            networkPeerManager,
            peerEventBus,
            requestMsg,
            Codes.ReceiptsCode
          ),
        s"chain-receipts-${System.nanoTime()}"
      )
    }

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
    var aborted = false
    val it = usable.iterator
    while (!aborted && it.hasNext) {
      val header = it.next()
      if (prevHash.exists(_ == header.parentHash)) {
        // Store header + chain weight, atomically advancing the backfill cursor in the same
        // RocksDB write batch (#1169) so a crash mid-write never leaves the cursor ahead of
        // the data on disk.
        blockchainReader.getChainWeightByHash(header.parentHash) match {
          case None =>
            // Parent weight missing means the pivot TD was not seeded for this hash.
            // Storing TD=0 here would corrupt every subsequent header's accumulated weight.
            // Abort this batch; the backfill cursor stays at bestHeaderNumber so the next
            // request will retry from the last committed position.
            log.warning(
              "Chain download: parent chain weight missing for block {} parentHash={} — aborting batch (cursor stays at {})",
              header.number,
              header.parentHash,
              bestHeaderNumber
            )
            aborted = true
          case Some(parentWeight) =>
            blockchainWriter
              .storeBlockHeader(header)
              .and(blockchainWriter.storeChainWeight(header.hash, parentWeight.increase(header)))
              .and(appStateStorage.putBackfillBestHeader(header.number))
              .commit()

            bodiesQueue :+= header.hash
            receiptsQueue :+= header.hash
            prevHash = Some(header.hash)
            validCount += 1
        }
      } else {
        log.warning(
          "Chain download: header {} parent hash mismatch from peer {}",
          header.number,
          peer.id
        )
        blacklist.add(peer.id, syncConfig.blacklistDuration, ErrorInBlockHeaders)
        aborted = true
      }
    }

    bestHeaderNumber += validCount
    headersDownloaded += validCount
  }

  private def handleBodies(peer: Peer, requestedHashes: Seq[ByteString], bodies: Seq[BlockBody]): Unit = {
    if (bodies.isEmpty) {
      // Re-queue the hashes
      bodiesQueue = requestedHashes.toVector ++ bodiesQueue
      blacklist.add(
        peer.id,
        syncConfig.blacklistDuration,
        EmptyBlockBodies(requestedHashes.map(h => s"0x${h.toArray.map("%02x".format(_)).mkString}"))
      )
      return
    }

    // Store received bodies + atomically advance the body cursor (#1169).
    val received = requestedHashes.zip(bodies)
    val highestBodyNumber = received
      .flatMap { case (hash, _) => blockchainReader.getBlockHeaderByHash(hash).map(_.number) }
      .maxOption
      .getOrElse(BigInt(0))
    val cursorUpdate =
      if (highestBodyNumber > appStateStorage.getBackfillBestBody())
        appStateStorage.putBackfillBestBody(highestBodyNumber)
      else
        appStateStorage.emptyBatchUpdate

    received
      .map { case (hash, body) => blockchainWriter.storeBlockBody(hash, body) }
      .reduce(_.and(_))
      .and(cursorUpdate)
      .commit()

    bodiesDownloaded += received.size

    // Re-queue any remaining hashes that weren't served
    val remaining = requestedHashes.drop(bodies.size)
    if (remaining.nonEmpty) {
      bodiesQueue = remaining.toVector ++ bodiesQueue
    }
  }

  private def handleReceipts(
      peer: Peer,
      requestedHashes: Seq[ByteString],
      eth66Receipts: ETHPackets.Receipts68
  ): Unit = {
    import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.TypedTransaction._

    val hashStrings = requestedHashes.map(h => s"0x${h.toArray.map("%02x".format(_)).mkString}")
    val receiptsRlp = eth66Receipts.receiptsForBlocks
    if (receiptsRlp.items.isEmpty) {
      receiptsQueue = requestedHashes.toVector ++ receiptsQueue
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

      // Store receipts. Atomically advance the receipt cursor (#1169) in the same batch as
      // the highest-numbered receipt write, so a crash mid-write doesn't leave the cursor
      // ahead of disk.
      val receiptsByHash = requestedHashes.zip(receiptsByBlock)
      val highestReceiptNumber = receiptsByHash
        .flatMap { case (hash, _) => blockchainReader.getBlockHeaderByHash(hash).map(_.number) }
        .maxOption
        .getOrElse(BigInt(0))

      receiptsByHash.zipWithIndex.foreach { case ((hash, receipts), idx) =>
        val storeUpdate = blockchainWriter.storeReceipts(hash, receipts)
        val withCursor =
          if (idx == receiptsByHash.size - 1 && highestReceiptNumber > appStateStorage.getBackfillBestReceipt())
            storeUpdate.and(appStateStorage.putBackfillBestReceipt(highestReceiptNumber))
          else
            storeUpdate
        withCursor.commit()
      }

      receiptsDownloaded += receiptsByBlock.size

      // Re-queue remaining
      val remaining = requestedHashes.drop(receiptsByBlock.size)
      if (remaining.nonEmpty) {
        receiptsQueue = remaining.toVector ++ receiptsQueue
      }
    } catch {
      case ex: Exception =>
        log.warning("Chain download: failed to decode receipts from peer {}: {}", peer.id, ex.getMessage)
        receiptsQueue = requestedHashes.toVector ++ receiptsQueue
        blacklist.add(
          peer.id,
          syncConfig.blacklistDuration,
          FastSyncRequestFailed(s"Invalid receipts: ${ex.getMessage}")
        )
    }
  }

  // Self-contained ETH70 receipt handler — does NOT delegate to handleReceipts.
  // Handles partial delivery (lastBlockIncomplete=true) via partialReceiptState/Buffer.
  private def handleReceipts70(
      peer: Peer,
      requestedHashes: Seq[ByteString],
      receipts70: ETHPackets.Receipts70
  ): Unit = {
    import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.TypedTransaction._

    val hashStrings = requestedHashes.map(h => s"0x${h.toArray.map("%02x".format(_)).mkString}")
    val receiptsRlp = receipts70.receiptsForBlocks
    val lastBlockIncomplete = receipts70.lastBlockIncomplete

    if (receiptsRlp.items.isEmpty && !lastBlockIncomplete) {
      receiptsQueue = requestedHashes.toVector ++ receiptsQueue
      blacklist.add(peer.id, syncConfig.blacklistDuration, EmptyReceipts(hashStrings))
      // Peer can no longer serve these — clear partial state so we don't re-request with a stale index
      requestedHashes.foreach { h =>
        partialReceiptState -= h; partialReceiptBuffer -= h
      }
      return
    }

    try {
      val responseItems: Seq[RLPList] = receiptsRlp.items.collect { case rl: RLPList => rl }
      val responseCount = responseItems.size

      // Decode typed-tx prefixes from a bloom-absent block RLP (same logic as handleReceipts)
      def decodeEncs(blockRlp: RLPList): Seq[RLPEncodeable] =
        blockRlp.items.flatMap {
          case v: RLPValue =>
            val receiptBytes = v.bytes
            if (receiptBytes.nonEmpty && (receiptBytes(0) & 0xff) < 0x7f && receiptBytes.length > 1) {
              try Seq(RLPValue(Array(receiptBytes(0))), rawDecode(receiptBytes.tail))
              catch { case _: Exception => Seq(v) }
            } else Seq(v)
          case other => Seq(other)
        }

      // Split: complete blocks vs. the possibly-truncated last block
      val (completeItems, incompleteItemOpt) =
        if (lastBlockIncomplete && responseItems.nonEmpty)
          (responseItems.init, Some(responseItems.last))
        else
          (responseItems, None)

      // Build (hash, receipts) pairs for all complete blocks, merging any buffered partial data
      val completeByHash: Seq[(ByteString, Seq[Receipt])] =
        completeItems.zipWithIndex.map { case (blockRlp, idx) =>
          val hash = requestedHashes(idx)
          val existing = partialReceiptBuffer.getOrElse(hash, Seq.empty)
          val decoded = (existing ++ decodeEncs(blockRlp)).toTypedRLPEncodables.map(_.toReceipt)
          partialReceiptState -= hash
          partialReceiptBuffer -= hash
          (hash, decoded)
        }

      // Store complete receipts + advance backfill cursor (#1169 pattern)
      if (completeByHash.nonEmpty) {
        val highestReceiptNumber = completeByHash
          .flatMap { case (h, _) => blockchainReader.getBlockHeaderByHash(h).map(_.number) }
          .maxOption
          .getOrElse(BigInt(0))

        completeByHash.zipWithIndex.foreach { case ((hash, receipts), idx) =>
          val storeUpdate = blockchainWriter.storeReceipts(hash, receipts)
          val withCursor =
            if (idx == completeByHash.size - 1 && highestReceiptNumber > appStateStorage.getBackfillBestReceipt())
              storeUpdate.and(appStateStorage.putBackfillBestReceipt(highestReceiptNumber))
            else
              storeUpdate
          withCursor.commit()
        }
        receiptsDownloaded += completeByHash.size
      }

      // Accumulate partial receipts for the truncated last block and re-queue it
      incompleteItemOpt.foreach { blockRlp =>
        val incompleteHashIdx = completeItems.size
        val hash = requestedHashes(incompleteHashIdx)
        val existing = partialReceiptBuffer.getOrElse(hash, Seq.empty)
        val accumulated = existing ++ decodeEncs(blockRlp)
        partialReceiptBuffer = partialReceiptBuffer.updated(hash, accumulated)
        partialReceiptState = partialReceiptState.updated(hash, accumulated.size.toLong)
        // Push back at front of queue so the next dispatch resumes this block first
        receiptsQueue = hash +: receiptsQueue
        log.debug(
          "RECEIPTS_ETH70_PARTIAL: hash={} buffered={} receipts, resumeIdx={}",
          s"0x${hash.toArray.take(4).map("%02x".format(_)).mkString}",
          accumulated.size,
          accumulated.size
        )
      }

      // Re-queue any hashes the server didn't return at all (beyond responseCount)
      val remaining = requestedHashes.drop(responseCount)
      if (remaining.nonEmpty) {
        receiptsQueue = remaining.toVector ++ receiptsQueue
      }

    } catch {
      case ex: Exception =>
        log.warning("Chain download ETH70: failed to decode receipts from peer {}: {}", peer.id, ex.getMessage)
        receiptsQueue = requestedHashes.toVector ++ receiptsQueue
        blacklist.add(
          peer.id,
          syncConfig.blacklistDuration,
          FastSyncRequestFailed(s"Invalid receipts (ETH70): ${ex.getMessage}")
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
      // Clear backfill cursors so the next startup doesn't try to resume a finished backfill (#1169).
      appStateStorage.clearBackfillCursors().commit()
      dispatchTask.foreach(_.cancel())
      context.parent ! Done
      context.become(idle)
    }

  private def findBestStoredHeader(): BigInt = {
    // Fast skip: trust the persisted cursor when its header is on disk (#1169).
    // The cursor is updated atomically with each storeBlockHeader commit so it never
    // overstates progress. We still validate by reading the header at the cursor —
    // if the cursor was somehow corrupted (manual DB intervention, downgrade), fall
    // back to the binary search.
    val cursorHeader = appStateStorage.getBackfillBestHeader()
    val (low0, best0) =
      if (cursorHeader > 0 && blockchainReader.getBlockHeaderByNumber(cursorHeader).isDefined)
        (cursorHeader + 1, cursorHeader)
      else
        (BigInt(0), BigInt(0))

    // Quick check: if genesis+1 doesn't exist, start from 0 (only meaningful for fresh runs).
    if (best0 == 0 && blockchainReader.getBlockHeaderByNumber(1).isEmpty) return 0

    // Binary search above the cursor for the highest stored header. With cursor-fast-skip
    // this almost always finds `best == cursorHeader` after one probe.
    var low: BigInt = low0
    var high: BigInt = targetBlock
    var best: BigInt = best0

    while (low <= high) {
      val mid = (low + high) / 2
      if (blockchainReader.getBlockHeaderByNumber(mid).isDefined) {
        best = mid
        low = mid + 1
      } else {
        high = mid - 1
      }
    }

    // Rebuild the body/receipt queues for headers we have but bodies/receipts we don't.
    // Use the body/receipt cursors as the floor so we don't re-walk every header — anything
    // ≤ those cursors was committed atomically with its body/receipt write and is on disk.
    val bodyFloor = appStateStorage.getBackfillBestBody()
    val receiptFloor = appStateStorage.getBackfillBestReceipt()

    var i = best
    while (i >= 1) {
      val needsBodyCheck = i > bodyFloor
      val needsReceiptCheck = i > receiptFloor
      if (needsBodyCheck || needsReceiptCheck) {
        blockchainReader.getBlockHeaderByNumber(i) match {
          case Some(header) =>
            if (needsBodyCheck && blockchainReader.getBlockBodyByHash(header.hash).isEmpty) {
              bodiesQueue :+= header.hash
            }
            if (needsReceiptCheck && blockchainReader.getReceiptsByHash(header.hash).isEmpty) {
              receiptsQueue :+= header.hash
            }
          case None => // shouldn't happen
        }
      }
      i -= 1
    }

    best
  }

  private def boostConcurrency(n: Int): Unit = {
    val prev = maxConcurrentRequests
    maxConcurrentRequests = n
    log.info("Chain download concurrency boosted: {} -> {} (state sync complete, all peers available)", prev, n)
    // Reschedule dispatch at faster interval — 2s was conservative to avoid SNAP contention
    scheduleDispatch(200.millis)
  }

  private def yieldToRegularSync(n: Int): Unit = {
    // Clamp to >=1: zero would wedge dispatch (inFlightCount >= maxConcurrentRequests is immediately
    // true), preventing any new work from starting and stranding the parent waiting for ChainDownloader.Done
    // forever. If the operator wants no backfill, they should disable it via chain-download-enabled=false.
    val clamped = math.max(n, 1)
    val prev = maxConcurrentRequests
    maxConcurrentRequests = clamped
    if (clamped != n) {
      log.warning("YieldToRegularSync({}) clamped to {} to prevent dispatch wedge", n, clamped)
    }
    log.info(
      "Chain download yielding to regular sync: {} -> {} concurrent requests (background backfill)",
      prev,
      clamped
    )
    // Slow the dispatch tick so backfill doesn't fight regular sync for the actor mailbox or peers.
    scheduleDispatch(2.seconds)
  }

  private def scheduleDispatch(interval: FiniteDuration = 2.seconds): Unit = {
    dispatchTask.foreach(_.cancel())
    dispatchTask = Some(
      scheduler.scheduleWithFixedDelay(1.second, interval, self, Dispatch)(ec, self)
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
  case class BoostConcurrency(maxConcurrent: Int)
  // Sent when SNAP state is finalised and regular sync is taking over. Backfill drops to a smaller
  // concurrency budget so it competes politely for peer slots. Mirrors `BoostConcurrency` but downward.
  case class YieldToRegularSync(maxConcurrent: Int)
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
      appStateStorage: AppStateStorage,
      networkPeerManager: ActorRef,
      peerEventBus: ActorRef,
      syncConfig: SyncConfig,
      scheduler: Scheduler,
      maxConcurrentRequests: Int = 4,
      requestTimeout: FiniteDuration = 10.seconds,
      snapServerPeerNodeIds: Set[ByteString] = Set.empty
  )(implicit ec: ExecutionContext): Props =
    Props(
      new ChainDownloader(
        blockchainReader,
        blockchainWriter,
        appStateStorage,
        networkPeerManager,
        peerEventBus,
        CacheBasedBlacklist.empty(1000),
        syncConfig,
        scheduler,
        maxConcurrentRequests,
        requestTimeout,
        snapServerPeerNodeIds
      )
    )
}
