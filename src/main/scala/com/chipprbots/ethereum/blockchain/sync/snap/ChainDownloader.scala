package com.chipprbots.ethereum.blockchain.sync.snap

import org.apache.pekko.actor.typed.ActorRef as TypedActorRef
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.ActorContext
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.scaladsl.TimerScheduler
import org.apache.pekko.util.ByteString

import scala.concurrent.duration.*

import com.chipprbots.ethereum.blockchain.sync.Blacklist
import com.chipprbots.ethereum.blockchain.sync.Blacklist.BlacklistReason.*
import com.chipprbots.ethereum.blockchain.sync.CacheBasedBlacklist
import com.chipprbots.ethereum.blockchain.sync.PeerListHelper
import com.chipprbots.ethereum.blockchain.sync.PeerListSupportNg.PeerWithInfo
import com.chipprbots.ethereum.blockchain.sync.PeerRequestHandler
import com.chipprbots.ethereum.blockchain.sync.PeerRequestHandler.RequestFailed
import com.chipprbots.ethereum.blockchain.sync.PeerRequestHandler.ResponseReceived
import com.chipprbots.ethereum.blockchain.sync.codec.ReceiptCodecs.*
import com.chipprbots.ethereum.consensus.validators.std.MptListValidator
import com.chipprbots.ethereum.consensus.validators.std.StdBlockValidator
import com.chipprbots.ethereum.consensus.validators.std.StdBlockValidator.BlockReceiptsHashError
import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.domain.BlockBody
import com.chipprbots.ethereum.domain.BlockHash
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.domain.BlockchainWriter
import com.chipprbots.ethereum.domain.Receipt
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.PeerInfo
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.PeerEventBusActor
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerEvent
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerEvent.PeerDisconnected
import com.chipprbots.ethereum.network.PeerId
import com.chipprbots.ethereum.network.p2p.messages.Capability
import com.chipprbots.ethereum.network.p2p.messages.Codes
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.GetBlockBodies.GetBlockBodiesEnc
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.GetBlockHeaders.GetBlockHeadersEnc
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.GetReceipts.GetReceiptsEnc
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.GetReceipts70.GetReceipts70Enc
import com.chipprbots.ethereum.rlp.*
import com.chipprbots.ethereum.utils.Config.SyncConfig

/** Downloads block headers, bodies, and receipts from genesis to a target block in parallel with SNAP state sync.
  *
  * This follows the Geth/Nethermind pattern of overlapping chain download with state download. Chain data (headers,
  * bodies, receipts) is canonical and valid regardless of which pivot block is used for state sync. By downloading
  * chain data during SNAP sync, the node is ready for regular sync immediately after state download completes.
  *
  * Uses a simple pipeline: headers first, then bodies and receipts for downloaded headers. Each peer gets at most one
  * outstanding request to avoid contention with SNAP state sync (which has higher priority).
  *
  * Pekko Typed migration (Group S6): the Classic `context.become` state machine becomes named `Behavior` factories
  * (`idle` / `downloading`). Peer-list responsibilities move to the composed [[PeerListHelper]] (replacing the
  * self-typed `PeerListSupportNg` trait); the periodic `GetHandshakedPeers` poll is owned here via
  * `Behaviors.withTimers`. The dispatch tick that was a `scheduler.scheduleWithFixedDelay(self, Dispatch)` is now a
  * Typed timer keyed `DispatchKey`.
  *
  * The behavior type is `Behavior[Command]` with a sealed ADT. `PeerRequestHandler` (already Typed[Cmd]) sends
  * `ResponseReceived` / `RequestFailed` replies to `prhResultAdapter`; the adapter wraps them into `PeerResult` before
  * delivery. `PeerDisconnected` events arrive via `peerDisconnectedAdapter` wrapped as `PeerGone`. `HandshakedPeers`
  * from `NetworkPeerManagerActor` arrive via `handshakedPeersAdapter` wrapped as `HandshakedPeersMsg`. Because replies
  * no longer arrive via `sender()`, the body/receipt in-flight maps are keyed by `PeerId` (carried on every response)
  * instead of the handler `ActorRef`.
  */
class ChainDownloader private (
    context: ActorContext[ChainDownloader.Command],
    timers: TimerScheduler[ChainDownloader.Command],
    blockchainReader: BlockchainReader,
    blockchainWriter: BlockchainWriter,
    appStateStorage: AppStateStorage,
    networkPeerManager: TypedActorRef[NetworkPeerManagerActor.Command],
    peerEventBus: TypedActorRef[PeerEventBusActor.Command],
    blacklist: Blacklist,
    syncConfig: SyncConfig,
    peerListHelper: PeerListHelper,
    initialMaxConcurrentRequests: Int,
    requestTimeout: FiniteDuration,
    snapServerPeerNodeIds: Set[ByteString],
    replyTo: TypedActorRef[ChainDownloader.Done.type],
    cursorScanCap: Long
):

  import ChainDownloader.*

  private val prhResultAdapter: TypedActorRef[PeerRequestHandler.Result] =
    context.messageAdapter[PeerRequestHandler.Result](PeerResult(_))

  private val handshakedPeersAdapter: TypedActorRef[NetworkPeerManagerActor.HandshakedPeers] =
    context.messageAdapter[NetworkPeerManagerActor.HandshakedPeers](hp => HandshakedPeersMsg(hp.peers))

  private def log = context.log

  private var targetBlock: BigInt = 0
  private var bestHeaderNumber: BigInt = 0
  private var paused = false

  // Queues of block hashes needing bodies/receipts
  private var bodiesQueue: Vector[ByteString] = Vector.empty
  private var receiptsQueue: Vector[ByteString] = Vector.empty

  // Track in-flight requests to limit concurrency. Body/receipt maps are keyed by PeerId (carried on
  // every PeerRequestHandler reply) because the Classic handler now replies to `context.parent` (= this
  // Typed actor) rather than as its own `sender()`. Each peer has at most one outstanding request per
  // category, so PeerId is a unique correlation key.
  private var headerRequestPeers: Set[PeerId] = Set.empty
  private var bodyRequestPeers: Map[PeerId, (Peer, Seq[ByteString])] = Map.empty
  private var receiptRequestPeers: Map[PeerId, (Peer, Seq[ByteString])] = Map.empty

  // go-ethereum behavioural backoff: peers that returned empty headers are excluded from
  // header dispatch (capacity-to-zero equivalent). Bodies/receipts/SNAP unaffected.
  private var emptyHeaderPeers: Set[PeerId] = Set.empty

  // ETH70 partial receipt tracking: hash → next resume index (receipts already received)
  private var partialReceiptState: Map[ByteString, Long] = Map.empty
  // ETH70 partial receipt buffer: hash → receipts accumulated so far
  private var partialReceiptBuffer: Map[ByteString, Seq[Receipt]] = Map.empty

  // Stats
  private var headersDownloaded: BigInt = 0
  private var bodiesDownloaded: BigInt = 0
  private var receiptsDownloaded: BigInt = 0
  private var lastLogTime: Long = 0

  // Concurrency — starts conservative during SNAP state sync, boosted after state completes
  private var maxConcurrentRequests: Int = initialMaxConcurrentRequests

  private def peersToDownloadFrom: Map[PeerId, PeerWithInfo] = peerListHelper.peersToDownloadFrom

  /** Shared peer-list / scan handling for every state. Returns `Some(next)` if the message was handled. */
  private def handleCommon(message: Command): Option[Behavior[Command]] = message match
    case ScanPeers =>
      networkPeerManager ! NetworkPeerManagerActor.GetHandshakedPeersCmd(handshakedPeersAdapter)
      Some(Behaviors.same)
    case HandshakedPeersMsg(peers) =>
      peerListHelper.handleHandshakedPeers(peers)
      Some(Behaviors.same)
    case PeerGone(peerId) =>
      peerListHelper.handlePeerDisconnected(peerId)
      Some(Behaviors.same)
    case _ => None

  def idle(): Behavior[Command] =
    Behaviors.receiveMessage { message =>
      handleCommon(message).getOrElse {
        message match
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
            downloading()

          case UpdateTarget(newTarget) =>
            // Re-start downloading if target was updated after completion (e.g. pivot refreshed from 0 to real block)
            if newTarget > targetBlock && newTarget > bestHeaderNumber then
              targetBlock = newTarget
              appStateStorage.putBackfillTarget(newTarget).commit()
              bestHeaderNumber = findBestStoredHeader()
              log.info(
                "Chain download restarted with new target: {}, resuming from header {}",
                newTarget,
                bestHeaderNumber
              )
              scheduleDispatch()
              downloading()
            else Behaviors.same

          case BoostConcurrency(n) =>
            boostConcurrency(n)
            Behaviors.same

          case YieldToRegularSync(n) =>
            yieldToRegularSync(n)
            Behaviors.same

          case GetProgress(replyTo) =>
            replyTo ! Progress(headersDownloaded, bodiesDownloaded, receiptsDownloaded, targetBlock)
            Behaviors.same

          case _ => Behaviors.same
      }
    }

  def downloading(): Behavior[Command] =
    Behaviors.receiveMessage { message =>
      handleCommon(message).getOrElse {
        message match
          case Dispatch =>
            if !paused then dispatchRequests()
            Behaviors.same

          case Pause =>
            if !paused then
              paused = true
              log.info("Chain download paused (yielding peers for pivot bootstrap)")
            Behaviors.same

          case Resume =>
            if paused then
              paused = false
              log.info("Chain download resumed")
              dispatchRequests()
            Behaviors.same

          case UpdateTarget(newTarget) =>
            if newTarget > targetBlock then
              log.info("Chain download target updated: {} -> {}", targetBlock, newTarget)
              targetBlock = newTarget
              appStateStorage.putBackfillTarget(newTarget).commit()
            Behaviors.same

          case Stop =>
            log.info(
              "Chain download stopped. Headers: {}, Bodies: {}, Receipts: {}",
              headersDownloaded,
              bodiesDownloaded,
              receiptsDownloaded
            )
            timers.cancel(DispatchKey)
            idle()

          case BoostConcurrency(n) =>
            boostConcurrency(n)
            dispatchRequests() // Immediately use the new slots
            Behaviors.same

          case YieldToRegularSync(n) =>
            yieldToRegularSync(n)
            Behaviors.same

          case GetProgress(replyTo) =>
            replyTo ! Progress(headersDownloaded, bodiesDownloaded, receiptsDownloaded, targetBlock)
            Behaviors.same

          // --- Header responses ---
          case PeerResult(ResponseReceived(_, peer, ETHPackets.BlockHeaders(_, headers), _)) =>
            headerRequestPeers -= peer.id
            if headers.nonEmpty then
              emptyHeaderPeers -= peer.id
              handleHeaders(peer, headers)
            else
              emptyHeaderPeers += peer.id
              log.debug("Empty headers from {} — excluding from header dispatch", peer.id)
            dispatchRequests()

          case PeerResult(RequestFailed(_, peer, reason)) =>
            headerRequestPeers -= peer.id
            // Re-queue this peer's in-flight hashes before dropping it. Each peer has at most one
            // outstanding request per category, so `.get` is unambiguous. Without this, a timed-out or
            // disconnected request's bodies/receipts simply vanish — bodiesQueue/receiptsQueue lose the
            // hashes, checkCompletion() then finds every queue empty, and the backfill declares COMPLETE
            // (and clears its cursors) while those blocks were never actually fetched. Blacklisting below
            // is unaffected. For a truncated eth/70 block the re-queued hash resumes correctly: this path
            // never touches partialReceiptState/partialReceiptBuffer, so the next request for that hash
            // still finds its buffered receipts and resume index and continues rather than restarting.
            bodyRequestPeers.get(peer.id).foreach { case (_, hashes) =>
              bodiesQueue = hashes.toVector ++ bodiesQueue
            }
            bodyRequestPeers -= peer.id
            receiptRequestPeers.get(peer.id).foreach { case (_, hashes) =>
              receiptsQueue = hashes.toVector ++ receiptsQueue
            }
            receiptRequestPeers -= peer.id
            log.debug("Chain download request failed for peer {}: {}", peer.id, reason)
            blacklist.add(peer.id, syncConfig.blacklistDuration, FastSyncRequestFailed(reason))
            dispatchRequests()

          // --- Body responses ---
          case PeerResult(ResponseReceived(_, peer, ETHPackets.BlockBodies(_, bodies), _)) =>
            bodyRequestPeers.get(peer.id).foreach { case (_, requestedHashes) =>
              bodyRequestPeers -= peer.id
              handleBodies(peer, requestedHashes, bodies)
            }
            dispatchRequests()

          // --- Receipt responses ---
          case PeerResult(ResponseReceived(_, peer, eth66Receipts: ETHPackets.Receipts68, _)) =>
            receiptRequestPeers.get(peer.id).foreach { case (_, requestedHashes) =>
              receiptRequestPeers -= peer.id
              handleReceipts(peer, requestedHashes, eth66Receipts)
            }
            dispatchRequests()

          // ETH69 (EIP-7642): bloom-absent receipts, no partial delivery.
          case PeerResult(ResponseReceived(_, peer, receipts69: ETHPackets.Receipts69, _)) =>
            receiptRequestPeers.get(peer.id).foreach { case (_, requestedHashes) =>
              receiptRequestPeers -= peer.id
              handleReceipts69(peer, requestedHashes, receipts69)
            }
            dispatchRequests()

          // ETH70 partial receipt delivery
          case PeerResult(ResponseReceived(_, peer, receipts70: ETHPackets.Receipts70, _)) =>
            receiptRequestPeers.get(peer.id).foreach { case (_, requestedHashes) =>
              receiptRequestPeers -= peer.id
              handleReceipts70(peer, requestedHashes, receipts70)
            }
            dispatchRequests()

          case _ => Behaviors.same
      }
    }

  private def dispatchRequests(): Behavior[Command] =
    val inFlightCount = headerRequestPeers.size + bodyRequestPeers.size + receiptRequestPeers.size
    if inFlightCount >= maxConcurrentRequests then Behaviors.same
    else
      val available = peersToDownloadFrom.filterNot { case (peerId, p) =>
        headerRequestPeers.contains(peerId) ||
        bodyRequestPeers.contains(peerId) ||
        receiptRequestPeers.contains(peerId) ||
        p.peer.nodeId.exists(snapServerPeerNodeIds.contains) ||
        emptyHeaderPeers.contains(peerId)
      }

      if available.isEmpty then Behaviors.same
      else
        val peers = available.values.toList
        var slotsLeft = maxConcurrentRequests - inFlightCount
        var peerIdx = 0

        // Priority 1: Headers (if we haven't reached target yet)
        while slotsLeft > 0 && peerIdx < peers.size && bestHeaderNumber < targetBlock do
          val peerWithInfo = peers(peerIdx)
          if !headerRequestPeers.contains(peerWithInfo.peer.id) then
            requestHeaders(peerWithInfo.peer)
            slotsLeft -= 1
            // Only one header request at a time to maintain sequential ordering
            peerIdx = peers.size // break
          peerIdx += 1

        peerIdx = 0

        // Priority 2: Bodies
        while slotsLeft > 0 && peerIdx < peers.size && bodiesQueue.nonEmpty do
          val peerWithInfo = peers(peerIdx)
          val peerId = peerWithInfo.peer.id
          if !headerRequestPeers.contains(peerId) &&
            !bodyRequestPeers.contains(peerId)
          then
            requestBodies(peerWithInfo.peer)
            slotsLeft -= 1
          peerIdx += 1

        peerIdx = 0

        // Priority 3: Receipts
        while slotsLeft > 0 && peerIdx < peers.size && receiptsQueue.nonEmpty do
          val peerWithInfo = peers(peerIdx)
          val peerId = peerWithInfo.peer.id
          if !headerRequestPeers.contains(peerId) &&
            !bodyRequestPeers.contains(peerId) &&
            !receiptRequestPeers.contains(peerId)
          then
            requestReceipts(peerWithInfo)
            slotsLeft -= 1
          peerIdx += 1

        // Log progress periodically
        val now = System.currentTimeMillis()
        if now - lastLogTime > 30000 then
          lastLogTime = now
          val pct = if targetBlock > 0 then (bestHeaderNumber * 100 / targetBlock).toInt else 0
          log.info(
            s"Chain download: headers=$bestHeaderNumber/$targetBlock(${pct}%), bodies=$bodiesDownloaded, receipts=$receiptsDownloaded, peers=${available.size}, inflight=$inFlightCount"
          )

        // Check if we're done — returns `idle()` on completion, else `Behaviors.same`.
        checkCompletion()

  private def requestHeaders(peer: Peer): Unit =
    val remaining = targetBlock - bestHeaderNumber
    val limit = remaining.min(syncConfig.blockHeadersPerRequest)

    if limit > 0 then
      headerRequestPeers += peer.id

      val requestMsg = ETHPackets.GetBlockHeaders(
        ETHPackets.nextRequestId,
        Left(bestHeaderNumber + 1),
        limit,
        skip = 0,
        reverse = false
      )

      context.spawn(
        PeerRequestHandler
          .behavior[ETHPackets.GetBlockHeaders, ETHPackets.BlockHeaders](
            peer,
            requestTimeout,
            networkPeerManager,
            peerEventBus,
            requestMsg,
            Codes.BlockHeadersCode,
            replyTo = prhResultAdapter,
            requestId = 0
          ),
        s"chain-headers-${bestHeaderNumber + 1}-${System.nanoTime()}"
      )

  private def requestBodies(peer: Peer): Unit =
    val batch = bodiesQueue.take(syncConfig.blockBodiesPerRequest)
    if batch.nonEmpty then
      bodiesQueue = bodiesQueue.drop(batch.size)

      val requestMsg = ETHPackets.GetBlockBodies(ETHPackets.nextRequestId, batch)

      context.spawn(
        PeerRequestHandler
          .behavior[ETHPackets.GetBlockBodies, ETHPackets.BlockBodies](
            peer,
            requestTimeout,
            networkPeerManager,
            peerEventBus,
            requestMsg,
            Codes.BlockBodiesCode,
            replyTo = prhResultAdapter,
            requestId = 0
          ),
        s"chain-bodies-${System.nanoTime()}"
      )

      bodyRequestPeers += (peer.id -> (peer, batch))

  private def requestReceipts(peerWithInfo: PeerWithInfo): Unit =
    val batch = receiptsQueue.take(syncConfig.receiptsPerRequest)
    if batch.nonEmpty then
      receiptsQueue = receiptsQueue.drop(batch.size)

      val peer = peerWithInfo.peer
      // ETH71/72 keep ETH70's GetReceipts70/Receipts70 wire shape unchanged (go-ethereum's eth71/eth72
      // handler maps both still route GetReceiptsMsg/ReceiptsMsg to handleGetReceipts70/handleReceipts70)
      // — same partial-delivery resume logic applies to all three.
      val isEth70OrNewer = peerWithInfo.peerInfo.remoteStatus.capability match
        case Capability.ETH70 | Capability.ETH71 | Capability.ETH72 => true
        case _                                                      => false

      if isEth70OrNewer then
        // ETH70's GetReceipts70 can only resume ONE block per request — firstBlockResumeIdx applies solely to
        // batch.head on the wire (go-ethereum's serviceGetReceiptsQuery70 only reads index 0). If a hash with a
        // buffered partial (from an earlier truncation) is anywhere else in this batch — the resume push in
        // handleReceipts70 lands it at the front, but a subsequent re-queue (RequestFailed, or another
        // truncation's own resume-push) can land in front of IT, displacing it to batch.tail — that buffer can
        // no longer be resumed: the peer will send the block fresh from receipt 0, and handleReceipts70 would
        // otherwise prepend the stale buffered prefix to the fresh full receipts, duplicating the first k
        // receipts, failing the receiptsRoot check, and blacklisting the (blameless) peer that answered
        // correctly (#32). Drop it instead — the peer's full response covers those receipts anyway, so nothing
        // is lost, only the resume optimisation for this one block.
        batch.tail.foreach { hash =>
          if partialReceiptBuffer.contains(hash) then
            log.debug(
              "Chain download ETH70: dropping stale partial-receipt buffer for {} — displaced from batch head",
              s"0x${hash.toArray.take(4).map("%02x".format(_)).mkString}"
            )
            partialReceiptState -= hash
            partialReceiptBuffer -= hash
        }
        // Resume partial delivery from the buffered index for the first block in batch
        val firstBlockResumeIdx = partialReceiptState.getOrElse(batch.head, 0L)
        val requestMsg = ETHPackets.GetReceipts70(ETHPackets.nextRequestId, firstBlockResumeIdx, batch)
        context.spawn(
          PeerRequestHandler
            .behavior[ETHPackets.GetReceipts70, ETHPackets.Receipts70](
              peer,
              requestTimeout,
              networkPeerManager,
              peerEventBus,
              requestMsg,
              Codes.ReceiptsCode,
              replyTo = prhResultAdapter,
              requestId = 0
            ),
          s"chain-receipts-eth70-${System.nanoTime()}"
        )
      else if peerWithInfo.peerInfo.remoteStatus.capability == Capability.ETH69 then
        // ETH69 (EIP-7642): the wire request is byte-identical to ETH68's GetReceipts — only the response
        // shape changes (bloom-absent Receipts69, decoded by ETH69MessageDecoder). PeerRequestHandler
        // filters a reply by the caller-supplied ResponseMsg type via TypeTest (`case responseMsg: ResponseMsg`
        // / `case _ => Behaviors.same`); a peer negotiated at ETH69 sends back a Receipts69, so asking with
        // Receipts68 as the expected type makes every reply fall through the wildcard and the request always
        // times out.
        val requestMsg = ETHPackets.GetReceipts(ETHPackets.nextRequestId, batch)
        context.spawn(
          PeerRequestHandler
            .behavior[ETHPackets.GetReceipts, ETHPackets.Receipts69](
              peer,
              requestTimeout,
              networkPeerManager,
              peerEventBus,
              requestMsg,
              Codes.ReceiptsCode,
              replyTo = prhResultAdapter,
              requestId = 0
            ),
          s"chain-receipts-eth69-${System.nanoTime()}"
        )
      else
        val requestMsg = ETHPackets.GetReceipts(ETHPackets.nextRequestId, batch)
        context.spawn(
          PeerRequestHandler
            .behavior[ETHPackets.GetReceipts, ETHPackets.Receipts68](
              peer,
              requestTimeout,
              networkPeerManager,
              peerEventBus,
              requestMsg,
              Codes.ReceiptsCode,
              replyTo = prhResultAdapter,
              requestId = 0
            ),
          s"chain-receipts-${System.nanoTime()}"
        )

      receiptRequestPeers += (peer.id -> (peer, batch))
    // else batch.isEmpty — nothing to dispatch

  private def handleHeaders(peer: Peer, headers: Seq[BlockHeader]): Unit =
    val expectedStart = bestHeaderNumber + 1

    // Find usable headers: skip any before our expected start, use what extends our chain
    val usableOpt: Option[Seq[BlockHeader]] =
      if headers.head.number.value == expectedStart then Some(headers)
      else if headers.head.number.value < expectedStart && headers.last.number.value >= expectedStart then
        // Response overlaps — trim to the portion we need
        val trimmed = headers.dropWhile(_.number.value < expectedStart)
        log.debug(
          "Chain download: trimmed overlapping headers {}-{} to start at {} ({} usable)",
          headers.head.number,
          headers.last.number,
          expectedStart,
          trimmed.size
        )
        Some(trimmed)
      else if headers.head.number.value > expectedStart then
        // Gap — can't use without the intervening headers
        log.debug(
          "Chain download: peer {} sent headers starting at {} but we need {} (gap)",
          peer.id,
          headers.head.number,
          expectedStart
        )
        None
      else
        // All stale (before our cursor)
        log.debug(
          "Chain download: peer {} sent stale headers {}-{}, already past {}",
          peer.id,
          headers.head.number,
          headers.last.number,
          expectedStart
        )
        None

    usableOpt.foreach { usable =>
      // Validate parent hash chaining
      var prevHash = blockchainReader.getBlockHeaderByNumber(bestHeaderNumber).map(_.hash)
      var validCount = 0
      var aborted = false
      val it = usable.iterator
      while !aborted && it.hasNext do
        val header = it.next()
        if prevHash.exists(_ == header.parentHash) then
          // Store header + chain weight, atomically advancing the backfill cursor in the same
          // RocksDB write batch (#1169) so a crash mid-write never leaves the cursor ahead of
          // the data on disk.
          blockchainReader.getChainWeightByHash(header.parentHash) match
            case None =>
              // Parent weight missing means the pivot TD was not seeded for this hash.
              // Storing TD=0 here would corrupt every subsequent header's accumulated weight.
              // Abort this batch; the backfill cursor stays at bestHeaderNumber so the next
              // request will retry from the last committed position.
              log.warn(
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
                .and(appStateStorage.putBackfillBestHeader(header.number.value))
                .commit()

              bodiesQueue :+= header.hash.value
              receiptsQueue :+= header.hash.value
              prevHash = Some(header.hash)
              validCount += 1
        else
          log.warn(
            "Chain download: header {} parent hash mismatch from peer {}",
            header.number,
            peer.id
          )
          blacklist.add(peer.id, syncConfig.blacklistDuration, ErrorInBlockHeaders)
          aborted = true

      bestHeaderNumber += validCount
      headersDownloaded += validCount
    }

  private def handleBodies(peer: Peer, requestedHashes: Seq[ByteString], bodies: Seq[BlockBody]): Unit =
    if bodies.isEmpty then
      // Re-queue the hashes
      bodiesQueue = requestedHashes.toVector ++ bodiesQueue
      blacklist.add(
        peer.id,
        syncConfig.blacklistDuration,
        EmptyBlockBodies(requestedHashes.map(h => s"0x${h.toArray.map("%02x".format(_)).mkString}"))
      )
    else
      val delivered = requestedHashes.zip(bodies)
      val (stored, firstError) = storeBodiesAndAdvanceCursor(delivered)
      if stored < delivered.size then
        rejectMismatchedBodies(
          peer,
          requestedHashes.drop(stored),
          firstError.getOrElse(StdBlockValidator.BlockTransactionsHashError)
        )
      else
        // Re-queue any remaining hashes that weren't served
        val remaining = requestedHashes.drop(bodies.size)
        if remaining.nonEmpty then bodiesQueue = remaining.toVector ++ bodiesQueue
  // else bodies.isEmpty

  /** Advance the body backfill cursor only over the prefix that is actually contiguous on disk from `cursor+1` onward
    * (#33). Bodies are fetched by several concurrent peers and a failed batch is re-queued (7b7a61708), so batches
    * complete out of order: a batch that fills blocks far above the cursor can commit while a lower block's fetch is
    * still in flight or retrying. The old code advanced the cursor to `max(cursor, highest block number JUST stored)`,
    * so the cursor could sit above a gap — and findBestStoredHeader's restart rescan trusts everything at or below the
    * cursor as stored (`i > bodyFloor` is the only condition that triggers a presence check), so a gap below the cursor
    * was never re-queued after a crash and that body was lost for good.
    *
    * Scanning forward from `cursor+1` and stopping at the first missing body makes the cursor mean what the rescan
    * assumes: every block at or below it is stored. A crash between the store above and this call simply leaves the
    * cursor at its old (safe, possibly stale) value — never past a gap — so an old cursor written by a pre-#33 build,
    * or one left behind mid-crash, costs at most a few redundant re-fetches on restart, never data loss. In the common
    * case (single peer, in-order delivery) this scans exactly one block per call — but is NOT unbounded in general:
    * after a restart, findBestStoredHeader's rebuild scan now queues the lowest missing block first (ascending, see
    * below), so a long-already-stored run can become newly contiguous all at once. Bounded to `cursorScanCap` blocks
    * (and never past `targetBlock`) per call for that case (#33 follow-up) — a single actor message can only ever read
    * up to the cap, not the whole remaining chain; a later store call continues the scan from wherever this one
    * stopped.
    */
  private def advanceBodyCursor(): Unit =
    val current = appStateStorage.getBackfillBestBody()
    val limit = (current + cursorScanCap).min(targetBlock)
    var n = current + 1
    while n <= limit && blockchainReader
        .getBlockHeaderByNumber(n)
        .exists(h => blockchainReader.getBlockBodyByHash(h.hash).isDefined)
    do n += 1
    if n - 1 > current then appStateStorage.putBackfillBestBody(n - 1).commit()

  /** Store received bodies whose transactions/ommers hash to their header (and pass validateHeaderAndBody's other
    * consensus checks — RLP size, withdrawals presence/root, blob gas), then advance the body cursor over the
    * contiguous run now on disk (#18, #33). Reuses the SAME check block import makes
    * (StdBlockValidator.validateHeaderAndBody) rather than re-implementing it — the same call regular sync's
    * BlockFetcherState.validateBodies makes via blockValidator.validateHeaderAndBody (BlockFetcherState.scala:226).
    *
    * A reply is matched to the request by position, so a peer that sends a body for the wrong block (or a corrupted
    * one) would otherwise be stored under the requested hash unconditionally, and the cursor would then move past a
    * block whose body is wrong. Storing stops at the first block that fails; returns how many leading blocks were
    * stored, and the validation error at the first failure (if any).
    */
  private def storeBodiesAndAdvanceCursor(
      bodiesByHash: Seq[(ByteString, BlockBody)]
  ): (Int, Option[StdBlockValidator.BlockError]) =
    val checked = bodiesByHash.map { case (hash, body) =>
      val result = blockchainReader.getBlockHeaderByHash(BlockHash(hash)) match
        case Some(header) => StdBlockValidator.validateHeaderAndBody(header, body)
        // No stored header for a hash we requested a body for shouldn't happen — the backfill queue is only ever
        // seeded from headers already validated and stored (handleHeaders / findBestStoredHeader). Stop the
        // prefix here rather than store against a header we can't even look up; BlockTransactionsHashError is the
        // closest umbrella label (validateHeaderAndBody checks it first), not a claim about which check failed.
        case None => Left(StdBlockValidator.BlockTransactionsHashError)
      (hash, body, result)
    }
    val verified = checked.takeWhile { case (_, _, result) => result.isRight }
    val firstError = checked.drop(verified.size).headOption.flatMap { case (_, _, result) => result.left.toOption }
    if verified.nonEmpty then
      verified
        .map { case (hash, body, _) => blockchainWriter.storeBlockBody(BlockHash(hash), body) }
        .reduce(_.and(_))
        .commit()
      bodiesDownloaded += verified.size
      advanceBodyCursor()
    (verified.size, firstError)

  /** A block's body in a peer's reply does not hash to its header (StdBlockValidator.validateHeaderAndBody failed —
    * transactionsRoot, ommersHash, or one of its other consensus checks). Re-queue it and everything after it, and
    * blacklist the peer. Mirrors rejectMismatchedReceipts.
    */
  private def rejectMismatchedBodies(
      peer: Peer,
      fromMismatch: Seq[ByteString],
      error: StdBlockValidator.BlockError
  ): Unit =
    val hashStrings = fromMismatch.map(h => s"0x${h.toArray.map("%02x".format(_)).mkString}")
    log.warn(
      "Chain download: body from peer {} does not match the header of block {}: {}; re-queuing {} block(s)",
      peer.id,
      hashStrings.headOption.getOrElse("?"),
      error,
      fromMismatch.size
    )
    bodiesQueue = fromMismatch.toVector ++ bodiesQueue
    val firstAndCount = hashStrings.headOption.map(h => s"$h (+${hashStrings.size - 1} re-queued after it)").toSeq
    blacklist.add(peer.id, syncConfig.blacklistDuration, InvalidBodies(firstAndCount, error))

  /** Store per-block receipts, then advance the backfill receipt cursor over the contiguous run now on disk (#1169,
    * #33). Shared by handleReceipts (eth/68), handleReceipts69 (eth/69), and handleReceipts70's complete-block path
    * (eth/70-72) — the three receipt decoders differ only in wire shape, not in how a decoded batch is persisted.
    *
    * A reply is matched to the request by position, so a peer that skips a block it lacks shifts every later block's
    * receipts onto the wrong hash, and the cursor would then move past blocks whose receipts are wrong. Each block's
    * receipts are therefore checked against its header's receiptsRoot, the check block validation makes
    * (StdBlockValidator.validateReceipts), and storing stops at the first block that fails. Returns how many leading
    * blocks were stored.
    */
  private def storeReceiptsAndAdvanceCursor(receiptsByHash: Seq[(ByteString, Seq[Receipt])]): Int =
    val verified = receiptsByHash.iterator
      .map { case (hash, receipts) => (hash, receipts, blockchainReader.getBlockHeaderByHash(BlockHash(hash))) }
      .takeWhile { case (_, receipts, header) =>
        header.exists(h =>
          MptListValidator.isValid[Receipt](h.receiptsRoot.toArray, receipts, Receipt.byteArraySerializable)
        )
      }
      .toVector
    if verified.nonEmpty then
      verified.foreach { case (hash, receipts, _) =>
        blockchainWriter.storeReceipts(BlockHash(hash), receipts).commit()
      }
      receiptsDownloaded += verified.size
      advanceReceiptCursor()
    verified.size

  /** See advanceBodyCursor — same contiguous-prefix reasoning, same `cursorScanCap`/`targetBlock` bound, applied to the
    * receipt cursor (#33, #33 follow-up).
    */
  private def advanceReceiptCursor(): Unit =
    val current = appStateStorage.getBackfillBestReceipt()
    val limit = (current + cursorScanCap).min(targetBlock)
    var n = current + 1
    while n <= limit && blockchainReader
        .getBlockHeaderByNumber(n)
        .exists(h => blockchainReader.getReceiptsByHash(h.hash).isDefined)
    do n += 1
    if n - 1 > current then appStateStorage.putBackfillBestReceipt(n - 1).commit()

  /** A block's receipts in a peer's reply do not hash to its header's receiptsRoot. Re-queue it and everything after
    * it, drop any eth/70 partial state for those hashes (it may hold the wrong receipts), and blacklist the peer.
    */
  private def rejectMismatchedReceipts(peer: Peer, fromMismatch: Seq[ByteString]): Unit =
    val hashStrings = fromMismatch.map(h => s"0x${h.toArray.map("%02x".format(_)).mkString}")
    log.warn(
      "Chain download: receipts from peer {} do not match the receiptsRoot of block {}; re-queuing {} block(s)",
      peer.id,
      hashStrings.headOption.getOrElse("?"),
      fromMismatch.size
    )
    fromMismatch.foreach { h =>
      partialReceiptState -= h; partialReceiptBuffer -= h
    }
    receiptsQueue = fromMismatch.toVector ++ receiptsQueue
    val firstAndCount = hashStrings.headOption.map(h => s"$h (+${hashStrings.size - 1} re-queued after it)").toSeq
    blacklist.add(peer.id, syncConfig.blacklistDuration, InvalidReceipts(firstAndCount, BlockReceiptsHashError))

  private def handleReceipts(
      peer: Peer,
      requestedHashes: Seq[ByteString],
      eth66Receipts: ETHPackets.Receipts68
  ): Unit =
    import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.TypedTransaction.*

    val hashStrings = requestedHashes.map(h => s"0x${h.toArray.map("%02x".format(_)).mkString}")
    val receiptsRlp = eth66Receipts.receiptsForBlocks
    if receiptsRlp.items.isEmpty then
      receiptsQueue = requestedHashes.toVector ++ receiptsQueue
      blacklist.add(peer.id, syncConfig.blacklistDuration, EmptyReceipts(hashStrings))
    else
      try
        // Decode using the same approach as ETH63.Receipts.ReceiptsDec
        val receiptsByBlock: Seq[Seq[Receipt]] = receiptsRlp.items.collect { case blockReceipts: RLPList =>
          blockReceipts.items
            .flatMap {
              case v: RLPValue =>
                val receiptBytes = v.bytes
                if receiptBytes.nonEmpty && (receiptBytes(0) & 0xff) < 0x7f && receiptBytes.length > 1 then
                  try Seq(RLPValue(Array(receiptBytes(0))), rawDecode(receiptBytes.tail))
                  catch case _: Exception => Seq(v)
                else Seq(v)
              case other => Seq(other)
            }
            .toTypedRLPEncodables
            .map(_.toReceipt)
        }

        // Store receipts, then advance the backfill receipt cursor over the contiguous run now on disk (#1169, #33).
        val delivered = requestedHashes.zip(receiptsByBlock)
        val stored = storeReceiptsAndAdvanceCursor(delivered)
        if stored < delivered.size then rejectMismatchedReceipts(peer, requestedHashes.drop(stored))
        else
          // Re-queue remaining
          val remaining = requestedHashes.drop(receiptsByBlock.size)
          if remaining.nonEmpty then receiptsQueue = remaining.toVector ++ receiptsQueue
      catch
        case ex: Exception =>
          log.warn("Chain download: failed to decode receipts from peer {}: {}", peer.id, ex.getMessage)
          receiptsQueue = requestedHashes.toVector ++ receiptsQueue
          blacklist.add(
            peer.id,
            syncConfig.blacklistDuration,
            FastSyncRequestFailed(s"Invalid receipts: ${ex.getMessage}")
          )

  /** ETH69 (EIP-7642) receipt handler. Unlike eth/70+, eth/69 has no partial-delivery flag — a response is either the
    * full batch of usable blocks or nothing — so this needs none of handleReceipts70's resume bookkeeping. Each
    * per-block RLP list decodes through `toEth69Receipt`, the strict `[txType, postStateOrStatus, cumulativeGasUsed,
    * logs]` decoder (no bloom on the wire, recomputed from the logs); a malformed receipt fails the whole response and
    * blacklists the peer that sent it, same as handleReceipts and handleReceipts70.
    */
  private def handleReceipts69(
      peer: Peer,
      requestedHashes: Seq[ByteString],
      receipts69: ETHPackets.Receipts69
  ): Unit =
    val hashStrings = requestedHashes.map(h => s"0x${h.toArray.map("%02x".format(_)).mkString}")
    val receiptsRlp = receipts69.receiptsForBlocks
    if receiptsRlp.items.isEmpty then
      receiptsQueue = requestedHashes.toVector ++ receiptsQueue
      blacklist.add(peer.id, syncConfig.blacklistDuration, EmptyReceipts(hashStrings))
    else
      try
        // Every item must be a block's receipt list. Skipping one would zip each later block's receipts onto the
        // wrong hash, and nothing downstream checks them against the header's receiptsRoot.
        val receiptsByBlock: Seq[Seq[Receipt]] = receiptsRlp.items.map {
          case blockReceipts: RLPList => blockReceipts.items.map(_.toEth69Receipt)
          case other                  => throw new RuntimeException(s"block receipts are not a list: $other")
        }

        // Store receipts, then advance the backfill receipt cursor over the contiguous run now on disk (#1169, #33).
        val delivered = requestedHashes.zip(receiptsByBlock)
        val stored = storeReceiptsAndAdvanceCursor(delivered)
        if stored < delivered.size then rejectMismatchedReceipts(peer, requestedHashes.drop(stored))
        else
          // Re-queue remaining
          val remaining = requestedHashes.drop(receiptsByBlock.size)
          if remaining.nonEmpty then receiptsQueue = remaining.toVector ++ receiptsQueue
      catch
        case ex: Exception =>
          log.warn("Chain download ETH69: failed to decode receipts from peer {}: {}", peer.id, ex.getMessage)
          receiptsQueue = requestedHashes.toVector ++ receiptsQueue
          blacklist.add(
            peer.id,
            syncConfig.blacklistDuration,
            FastSyncRequestFailed(s"Invalid receipts (ETH69): ${ex.getMessage}")
          )

  // Self-contained ETH70 receipt handler — does NOT delegate to handleReceipts.
  // Handles partial delivery (lastBlockIncomplete=true) via partialReceiptState/Buffer.
  private def handleReceipts70(
      peer: Peer,
      requestedHashes: Seq[ByteString],
      receipts70: ETHPackets.Receipts70
  ): Unit =
    val hashStrings = requestedHashes.map(h => s"0x${h.toArray.map("%02x".format(_)).mkString}")
    val receiptsRlp = receipts70.receiptsForBlocks
    val lastBlockIncomplete = receipts70.lastBlockIncomplete

    if receiptsRlp.items.isEmpty && !lastBlockIncomplete then
      receiptsQueue = requestedHashes.toVector ++ receiptsQueue
      blacklist.add(peer.id, syncConfig.blacklistDuration, EmptyReceipts(hashStrings))
      // Peer can no longer serve these — clear partial state so we don't re-request with a stale index
      requestedHashes.foreach { h =>
        partialReceiptState -= h; partialReceiptBuffer -= h
      }
    else
      try
        val responseItems: Seq[RLPList] = receiptsRlp.items.collect { case rl: RLPList => rl }
        val responseCount = responseItems.size

        // eth/70+ receipts are the eth/69 network form (EIP-7642): one four-item list per receipt, typed or not, so
        // the item count of a block's list is its receipt count — the resume index for a truncated block. Decoding
        // each chunk as it arrives charges a malformed one to the peer that sent it.
        def decodeBlock(blockRlp: RLPList): Seq[Receipt] = blockRlp.items.map(_.toEth69Receipt)

        // Split: complete blocks vs. the possibly-truncated last block
        val (completeItems, incompleteItemOpt) =
          if lastBlockIncomplete && responseItems.nonEmpty then (responseItems.init, Some(responseItems.last))
          else (responseItems, None)

        // Build (hash, receipts) pairs for all complete blocks, merging any buffered partial data
        val completeByHash: Seq[(ByteString, Seq[Receipt])] =
          completeItems.zipWithIndex.map { case (blockRlp, idx) =>
            val hash = requestedHashes(idx)
            val existing = partialReceiptBuffer.getOrElse(hash, Seq.empty)
            val decoded = existing ++ decodeBlock(blockRlp)
            partialReceiptState -= hash
            partialReceiptBuffer -= hash
            (hash, decoded)
          }

        // Store complete receipts + advance backfill cursor (#1169 pattern)
        val stored = storeReceiptsAndAdvanceCursor(completeByHash)
        if stored < completeByHash.size then rejectMismatchedReceipts(peer, requestedHashes.drop(stored))
        else
          // Accumulate partial receipts for the truncated last block and re-queue it
          incompleteItemOpt.foreach { blockRlp =>
            val incompleteHashIdx = completeItems.size
            val hash = requestedHashes(incompleteHashIdx)
            val existing = partialReceiptBuffer.getOrElse(hash, Seq.empty)
            val accumulated = existing ++ decodeBlock(blockRlp)
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
          if remaining.nonEmpty then receiptsQueue = remaining.toVector ++ receiptsQueue

      catch
        case ex: Exception =>
          log.warn("Chain download ETH70: failed to decode receipts from peer {}: {}", peer.id, ex.getMessage)
          receiptsQueue = requestedHashes.toVector ++ receiptsQueue
          blacklist.add(
            peer.id,
            syncConfig.blacklistDuration,
            FastSyncRequestFailed(s"Invalid receipts (ETH70): ${ex.getMessage}")
          )

  private def checkCompletion(): Behavior[Command] =
    if bestHeaderNumber >= targetBlock &&
      bodiesQueue.isEmpty &&
      receiptsQueue.isEmpty &&
      bodyRequestPeers.isEmpty &&
      receiptRequestPeers.isEmpty
    then
      log.info(
        "Chain download COMPLETE: {} headers, {} bodies, {} receipts downloaded to block {}",
        headersDownloaded,
        bodiesDownloaded,
        receiptsDownloaded,
        targetBlock
      )
      // Clear backfill cursors so the next startup doesn't try to resume a finished backfill (#1169).
      appStateStorage.clearBackfillCursors().commit()
      timers.cancel(DispatchKey)
      replyTo ! Done
      idle()
    else Behaviors.same

  private def findBestStoredHeader(): BigInt =
    // Fast skip: trust the persisted cursor when its header is on disk (#1169).
    // The cursor is updated atomically with each storeBlockHeader commit so it never
    // overstates progress. We still validate by reading the header at the cursor —
    // if the cursor was somehow corrupted (manual DB intervention, downgrade), fall
    // back to the binary search.
    val cursorHeader = appStateStorage.getBackfillBestHeader()
    val (low0, best0) =
      if cursorHeader > 0 && blockchainReader.getBlockHeaderByNumber(cursorHeader).isDefined then
        (cursorHeader + 1, cursorHeader)
      else (BigInt(0), BigInt(0))

    // Quick check: if genesis+1 doesn't exist, start from 0 (only meaningful for fresh runs).
    if best0 == 0 && blockchainReader.getBlockHeaderByNumber(1).isEmpty then 0
    else
      // Binary search above the cursor for the highest stored header. With cursor-fast-skip
      // this almost always finds `best == cursorHeader` after one probe.
      var low: BigInt = low0
      var high: BigInt = targetBlock
      var best: BigInt = best0

      while low <= high do
        val mid = (low + high) / 2
        if blockchainReader.getBlockHeaderByNumber(mid).isDefined then
          best = mid
          low = mid + 1
        else high = mid - 1

      // Rebuild the body/receipt queues for headers we have but bodies/receipts we don't. Use the body/receipt
      // cursors as the floor so we don't re-walk every header — #33 made each cursor mean "every block at or below
      // it is stored" (a verified contiguous prefix advanced by ChainDownloader.advanceBodyCursor /
      // advanceReceiptCursor's own scan), not "committed atomically together with its write" — so anything at or
      // below those cursors is still safe to skip re-checking, just via a different guarantee than before.
      //
      // Ascending, not descending (#33 follow-up, per Forge's ETC review of b4b2a50b8): this walk seeds
      // bodiesQueue/receiptsQueue, and requestBodies/requestReceipts always take from the FRONT of the queue —
      // appending low-to-high means the LOWEST missing block is dispatched FIRST. That matters because
      // advanceBodyCursor/advanceReceiptCursor can only advance past a gap, never over one: with the old
      // descending order the lowest missing block was queued LAST (fetched last), so nothing could advance the
      // cursor until it landed — and once it did, everything above it could already be contiguously present,
      // so a single advanceBodyCursor call had an effectively unbounded range to walk (on ETC mainnet, close to
      // the whole chain after a restart deep into a backfill: Forge's estimate was ~75M reads in one actor
      // message, 5-10 min warm / 1-2h cold). Ascending order — combined with cursorScanCap bounding each
      // individual scan call regardless — lets the cursor advance incrementally as each low block lands, the same
      // way it does during normal (non-restart) operation.
      val bodyFloor = appStateStorage.getBackfillBestBody()
      val receiptFloor = appStateStorage.getBackfillBestReceipt()
      val lowestFloor = bodyFloor.min(receiptFloor)

      var i = lowestFloor + 1
      while i <= best do
        val needsBodyCheck = i > bodyFloor
        val needsReceiptCheck = i > receiptFloor
        if needsBodyCheck || needsReceiptCheck then
          blockchainReader.getBlockHeaderByNumber(i) match
            case Some(header) =>
              if needsBodyCheck && blockchainReader.getBlockBodyByHash(header.hash).isEmpty then
                bodiesQueue :+= header.hash.value
              if needsReceiptCheck && blockchainReader.getReceiptsByHash(header.hash).isEmpty then
                receiptsQueue :+= header.hash.value
            case None => // shouldn't happen
        i += 1

      best

  private def boostConcurrency(n: Int): Unit =
    val prev = maxConcurrentRequests
    maxConcurrentRequests = n
    log.info("Chain download concurrency boosted: {} -> {} (state sync complete, all peers available)", prev, n)
    // Reschedule dispatch at faster interval — 2s was conservative to avoid SNAP contention
    scheduleDispatch(200.millis)

  private def yieldToRegularSync(n: Int): Unit =
    // Clamp to >=1: zero would wedge dispatch (inFlightCount >= maxConcurrentRequests is immediately
    // true), preventing any new work from starting and stranding the parent waiting for ChainDownloader.Done
    // forever. If the operator wants no backfill, they should disable it via chain-download-enabled=false.
    val clamped = math.max(n, 1)
    val prev = maxConcurrentRequests
    maxConcurrentRequests = clamped
    if clamped != n then log.warn("YieldToRegularSync({}) clamped to {} to prevent dispatch wedge", n, clamped)
    log.info(
      "Chain download yielding to regular sync: {} -> {} concurrent requests (background backfill)",
      prev,
      clamped
    )
    // Slow the dispatch tick so backfill doesn't fight regular sync for the actor mailbox or peers.
    scheduleDispatch(2.seconds)

  // Typed timer replacing the Classic `scheduler.scheduleWithFixedDelay(self, Dispatch)`. `startTimerWithFixedDelay`
  // replaces any prior timer under the same key, so changing the interval (boost/yield) just re-arms it.
  private def scheduleDispatch(interval: FiniteDuration = 2.seconds): Unit =
    timers.startTimerWithFixedDelay(DispatchKey, Dispatch, interval)

object ChainDownloader:

  /** Sealed inbound protocol. Classic parents (SyncController / SNAPSyncController) send the public cases via the
    * Classic co-existence bridge; co-existence mode delivers them to the typed mailbox and all arrive as `Command`.
    */
  sealed trait Command

  // Public protocol
  case class Start(targetBlock: BigInt) extends Command
  case class UpdateTarget(newTarget: BigInt) extends Command
  case object Pause extends Command
  case object Resume extends Command
  case object Stop extends Command
  case object Done // outbound to parent — NOT a Command
  case class BoostConcurrency(maxConcurrent: Int) extends Command
  // Sent when SNAP state is finalised and regular sync is taking over. Backfill drops to a smaller
  // concurrency budget so it competes politely for peer slots. Mirrors `BoostConcurrency` but downward.
  case class YieldToRegularSync(maxConcurrent: Int) extends Command
  case class GetProgress(replyTo: TypedActorRef[Progress]) extends Command
  case class Progress( // outbound reply type — NOT a Command
      headersDownloaded: BigInt,
      bodiesDownloaded: BigInt,
      receiptsDownloaded: BigInt,
      targetBlock: BigInt
  )

  // Internal — timer ticks
  private case object Dispatch extends Command
  private case object ScanPeers extends Command

  // Adapter wrappers — bridge external message types into the sealed Command domain
  private case class PeerResult(result: PeerRequestHandler.Result) extends Command
  private case class HandshakedPeersMsg(peers: Map[Peer, PeerInfo]) extends Command
  private case class PeerGone(peerId: PeerId) extends Command

  // Timer keys
  private val ScanKey: String = "ScanPeers"
  private val DispatchKey: String = "Dispatch"

  // scalastyle:off parameter.number
  def apply(
      blockchainReader: BlockchainReader,
      blockchainWriter: BlockchainWriter,
      appStateStorage: AppStateStorage,
      networkPeerManager: TypedActorRef[NetworkPeerManagerActor.Command],
      peerEventBus: TypedActorRef[PeerEventBusActor.Command],
      syncConfig: SyncConfig,
      replyTo: TypedActorRef[Done.type],
      maxConcurrentRequests: Int = 4,
      requestTimeout: FiniteDuration = 10.seconds,
      snapServerPeerNodeIds: Set[ByteString] = Set.empty,
      blacklist: Blacklist = CacheBasedBlacklist.empty(1000),
      // Bounds how many blocks a single advanceBodyCursor/advanceReceiptCursor call scans (#33 follow-up). Without
      // a cap, a long contiguous run becoming available at once (e.g. after a restart whose rescan queues fill in
      // a large stretch) forces one actor message to read potentially the whole chain synchronously — on ETC
      // mainnet (~25M blocks) that was estimated at ~75M reads in one message, minutes warm / 1-2h cold. A cap
      // means the scan yields after cursorScanCap blocks; the next successful store continues it from the new
      // cursor. "A few thousand" per forge's review — 5000 is a few dispatch cycles' worth of throughput, not a
      // noticeable latency hit, while bounding worst-case single-message cost to a few thousand DB reads.
      cursorScanCap: Long = 5000L
  ): Behavior[Command] =
    Behaviors.setup { context =>
      Behaviors.withTimers { timers =>
        val peerDisconnectedAdapter: TypedActorRef[PeerEvent] =
          context.messageAdapter[PeerEvent] {
            case PeerDisconnected(peerId) => PeerGone(peerId)
            case e                        => throw new MatchError(s"unexpected PeerEvent from bus: $e")
          }

        val peerListHelper = new PeerListHelper(
          peerEventBus,
          blacklist,
          peerDisconnectedAdapter,
          context.log
        )

        val downloader = new ChainDownloader(
          context,
          timers,
          blockchainReader,
          blockchainWriter,
          appStateStorage,
          networkPeerManager,
          peerEventBus,
          blacklist,
          syncConfig,
          peerListHelper,
          maxConcurrentRequests,
          requestTimeout,
          snapServerPeerNodeIds,
          replyTo,
          cursorScanCap
        )

        // Immediate poll, then periodic poll for handshaked peers (replaces PeerListSupportNg's scheduleWithFixedDelay).
        networkPeerManager ! NetworkPeerManagerActor.GetHandshakedPeersCmd(downloader.handshakedPeersAdapter)
        timers.startTimerWithFixedDelay(ScanKey, ScanPeers, syncConfig.peersScanInterval)

        downloader.idle()
      }
    }
  // scalastyle:on parameter.number
