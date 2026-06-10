package com.chipprbots.ethereum.blockchain.sync.regular

import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.AbstractBehavior
import org.apache.pekko.actor.typed.scaladsl.ActorContext
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.scaladsl.TimerScheduler
import org.apache.pekko.actor.typed.scaladsl.adapter._
import org.apache.pekko.actor.{ActorRef => ClassicActorRef}
import org.apache.pekko.util.ByteString
import org.apache.pekko.util.Timeout

import cats.data.NonEmptyList
import cats.effect.unsafe.IORuntime
import cats.instances.option._

import scala.concurrent.duration._

import mouse.all._

import com.chipprbots.ethereum.blockchain.sync.Blacklist.BlacklistReason
import com.chipprbots.ethereum.blockchain.sync.PeersClient._
import com.chipprbots.ethereum.blockchain.sync.regular.BlockFetcherState.AwaitingBodiesToBeIgnored
import com.chipprbots.ethereum.blockchain.sync.regular.BlockFetcherState.HeadersNotFormingSeq
import com.chipprbots.ethereum.blockchain.sync.regular.BlockFetcherState.HeadersNotMatchingReadyBlocks
import com.chipprbots.ethereum.blockchain.sync.regular.BlockFetcherState.HeadersNotMatchingWaitingHeaders
import com.chipprbots.ethereum.blockchain.sync.regular.BlockFetcherState.MaxConcurrentHeaderSlots
import com.chipprbots.ethereum.blockchain.sync.regular.BlockImporter.ImportNewBlock
import com.chipprbots.ethereum.blockchain.sync.regular.RegularSync.ProgressProtocol
import com.chipprbots.ethereum.consensus.validators.BlockValidator
import com.chipprbots.ethereum.domain._
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.PeerEventBusActor
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerEvent.MessageFromPeer
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerSelector
import com.chipprbots.ethereum.network.PeerEventBusActor.Subscribe
import com.chipprbots.ethereum.network.PeerEventBusActor.SubscriptionClassifier.MessageClassifier
import com.chipprbots.ethereum.network.PeerId
import com.chipprbots.ethereum.network.p2p.Message
import com.chipprbots.ethereum.network.p2p.messages.Codes
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.NewBlockHashes.NewBlockHashes
import com.chipprbots.ethereum.network.p2p.messages.ETH69
import com.chipprbots.ethereum.utils.ByteStringUtils
import com.chipprbots.ethereum.utils.Config.SyncConfig
import com.chipprbots.ethereum.utils.FunctorOps._

class BlockFetcher(
    val peersClient: ClassicActorRef,
    val peerEventBus: ClassicActorRef,
    val supervisor: ClassicActorRef,
    val syncConfig: SyncConfig,
    val blockValidator: BlockValidator,
    context: ActorContext[BlockFetcher.FetchCommand],
    timers: TimerScheduler[BlockFetcher.FetchCommand]
) extends AbstractBehavior[BlockFetcher.FetchCommand](context) {

  import BlockFetcher._

  implicit val runtime: IORuntime = IORuntime.global
  implicit val timeout: Timeout = syncConfig.peerResponseTimeout + 2.second // some margin for actor communication
  private val log = context.log

  val headersFetcher: ActorRef[HeadersFetcher.HeadersFetcherCommand] =
    context.spawn(
      HeadersFetcher(peersClient, syncConfig, context.self),
      "headers-fetcher"
    )
  context.watch(headersFetcher)

  val bodiesFetcher: ActorRef[BodiesFetcher.BodiesFetcherCommand] =
    context.spawn(
      BodiesFetcher(peersClient, syncConfig, context.self),
      "bodies-fetcher"
    )
  context.watch(bodiesFetcher)

  val stateNodeFetcher: ActorRef[StateNodeFetcher.StateNodeFetcherCommand] =
    context.spawn(
      StateNodeFetcher(peersClient, syncConfig, context.self),
      "state-node-fetcher"
    )
  context.watch(stateNodeFetcher)

  private def subscribeAdapter(
      fetcher: ActorRef[BlockFetcher.AdaptedMessageFromEventBus]
  ): Behaviors.Receive[MessageFromPeer] =
    Behaviors.receiveMessage[PeerEventBusActor.PeerEvent.MessageFromPeer] { case MessageFromPeer(message, peerId) =>
      fetcher ! AdaptedMessageFromEventBus(message, peerId)
      Behaviors.same
    }

  override def onMessage(message: FetchCommand): Behavior[FetchCommand] =
    message match {
      case Start(importer, fromBlock) =>
        log.debug("BlockFetcher starting from block {} with importer {}", fromBlock, importer)
        val sa = context.spawn(subscribeAdapter(context.self), "fetcher-subscribe-adapter")
        peerEventBus.tell(
          Subscribe(
            MessageClassifier(
              Set(Codes.NewBlockCode, Codes.NewBlockHashesCode, Codes.BlockHeadersCode, Codes.BlockRangeUpdateCode),
              PeerSelector.AllPeers
            )
          ),
          sa.toClassic
        )
        log.debug("BlockFetcher subscribed to peer events")
        // 500ms stall-recovery heartbeat: fills open header slots if the primary reactive
        // triggers (BRU, response received) were missed or headersToIgnore just drained.
        timers.startTimerWithFixedDelay("tick-fetch", TickFetch, 500.millis)
        BlockFetcherState.initial(importer, blockValidator, fromBlock) |> fetchBlocks
      case msg =>
        log.debug("Fetcher subscribe adapter received unhandled message {}", msg)
        Behaviors.unhandled
    }

  // scalastyle:off cyclomatic.complexity method.length
  private def processFetchCommands(state: BlockFetcherState): Behavior[FetchCommand] =
    Behaviors.receiveMessage {
      case PrintStatus =>
        val now = System.currentTimeMillis()
        val dt = if (state.lastPrintTimeMs > 0) (now - state.lastPrintTimeMs) / 1000.0 else 0.0
        val delta = state.lastBlock - state.lastPrintBlock
        val rate = if (dt > 0 && state.lastPrintTimeMs > 0) delta.toDouble / dt else 0.0
        val behind = (state.knownTop - state.lastBlock).max(0)
        val etaStr =
          if (rate > 0 && behind > 0) f"eta=${behind.toDouble / rate / 60.0}%.1fmin"
          else if (behind == 0) "caught-up"
          else "eta=?"
        log.info(
          "[RegularSync] block={} top={} behind={} rate={}/s {} ready={} waiting={}",
          state.lastBlock,
          state.knownTop,
          behind,
          f"$rate%.1f",
          etaStr,
          state.readyBlocks.size,
          state.waitingHeaders.size
        )
        log.debug("[RegularSync] detailed: {}", state.statusDetailed)
        val updatedState = state.copy(lastPrintBlock = state.lastBlock, lastPrintTimeMs = now)
        // Defense-in-depth: if stuck at chain head with no peer gossip (e.g., ETH/69 peers that
        // sent BlockRangeUpdate before we subscribed, or a quiet period), probe speculatively.
        // withPossibleNewTopAt(knownTop + 1) exits isOnTop and triggers tryFetchHeaders.
        if (state.isOnTop) {
          log.debug("BlockFetcher: isOnTop at knownTop={}, probing for next block", state.knownTop)
          fetchBlocks(updatedState.withPossibleNewTopAt(state.knownTop + 1))
        } else fetchBlocks(updatedState)

      case PickBlocks(amount, replyTo) =>
        log.debug("PickBlocks request for {} blocks (ready blocks: {})", amount, state.readyBlocks.size)
        state.pickBlocks(amount) |> handlePickedBlocks(state, replyTo) |> fetchBlocks

      case StrictPickBlocks(from, atLeastWith, replyTo) =>
        // from parameter could be negative or 0 so we should cap it to 1 if that's the case
        val fromCapped = from.max(1)
        val minBlock = fromCapped.min(atLeastWith).max(1)
        log.debug("Strict Pick blocks from {} to {}", fromCapped, atLeastWith)
        log.debug("Lowest available block is {}", state.lowestBlock)

        val newState = if (minBlock < state.lowestBlock) {
          state.invalidateBlocksFrom(minBlock, None)._2
        } else {
          state.strictPickBlocks(fromCapped, atLeastWith) |> handlePickedBlocks(state, replyTo)
        }

        fetchBlocks(newState)

      case InvalidateBlocksFrom(blockNr, reason, withBlacklist) =>
        log.debug("Invalidate blocks from {} requested. Reason: {}, Blacklist: {}", blockNr, reason, withBlacklist)
        val (blockProvider, newState) = state.invalidateBlocksFrom(blockNr, withBlacklist)
        log.debug(
          "Invalidate blocks from {}. Provider to blacklist: {}, New last block: {}",
          blockNr,
          blockProvider,
          newState.lastBlock
        )
        blockProvider.foreach { peerId =>
          log.debug("Blacklisting peer {} due to block import error: {}", peerId, reason)
          peersClient ! BlacklistPeer(peerId, BlacklistReason.BlockImportError(reason))
        }
        fetchBlocks(newState)

      case ReceivedHeaders(peer, headers) if state.isFetchingHeaders =>
        // First successful fetch
        if (state.waitingHeaders.isEmpty && state.headersToIgnore == 0) {
          log.debug("First successful header fetch, notifying supervisor to start fetching")
          supervisor ! ProgressProtocol.StartedFetching
        }
        val newState =
          if (state.headersToIgnore > 0) {
            log.debug(
              "Received {} headers starting from block {} that will be ignored (headersToIgnore: {})",
              headers.size,
              headers.headOption.map(_.number),
              state.headersToIgnore
            )
            state.withHeaderFetchReceived
          } else {
            log.debug(
              "Fetched {} headers starting from block {} (peer: {})",
              headers.size,
              headers.headOption.map(_.number),
              peer.id
            )
            if (headers.isEmpty) {
              log.debug("Received empty headers response from peer {}", peer.id)
            } else {
              log.debug(
                "Header range: {} to {} (last block in state: {})",
                headers.headOption.map(_.number),
                headers.lastOption.map(_.number),
                state.lastBlock
              )
            }
            // Decrement the in-flight counter, then route through the buffer when multiple
            // slots are in flight so concurrent responses are processed in ascending order.
            // With a single slot (current MaxConcurrentHeaderSlots=1) pass headers directly
            // to appendHeaders — no gap is possible, buffering would break rejection handling.
            val afterReceive = state.withHeaderFetchReceived
            val (orderedHeaders, baseState) =
              if (state.inFlightHeaders > 1) {
                afterReceive.bufferHeaders(headers).drainOrderedHeaders
              } else {
                (headers, afterReceive)
              }

            val afterAppend: BlockFetcherState =
              if (orderedHeaders.isEmpty && headers.nonEmpty) {
                // Multiple slots in flight; a gap exists — waiting for earlier slot's response.
                baseState
              } else {
                baseState.appendHeaders(orderedHeaders) match {
                  case Left(HeadersNotFormingSeq) =>
                    log.info(
                      "Dismissed received headers: {} (peer={}, first={}, last={}, count={})",
                      HeadersNotFormingSeq.description,
                      peer.id,
                      headers.headOption.map(_.number),
                      headers.lastOption.map(_.number),
                      headers.size
                    )
                    // No blacklist: valid peers on a different fork branch can trigger this during reorgs.
                    // Only blacklist on cryptographic invalidity (PoW failure, bad signature) — Besu AbstractPeerTask pattern.
                    // Reset nextDispatchBlock: it was advanced at dispatch time but nothing was appended to the queue,
                    // so the next dispatch must retry from the current queue tail (nextBlockToFetch), not the window end.
                    baseState
                      .recordHeaderRejection()
                      .copy(nextDispatchBlock = baseState.nextBlockToFetch)
                  case Left(HeadersNotMatchingReadyBlocks) =>
                    log.info(
                      "Dismissed received headers: {} (peer={}, readyTip={}, respFirst={})",
                      HeadersNotMatchingReadyBlocks.description,
                      peer.id,
                      state.readyBlocks.lastOption.map(_.number),
                      headers.headOption.map(_.number)
                    )
                    // No blacklist: during a fork reorg, honest peers on the reorg chain will not extend our ready blocks.
                    baseState
                      .recordHeaderRejection()
                      .copy(nextDispatchBlock = baseState.nextBlockToFetch)
                  case Left(HeadersNotMatchingWaitingHeaders) =>
                    log.debug(
                      "Dismissed received headers due to: {} (peer: {})",
                      HeadersNotMatchingWaitingHeaders.description,
                      peer.id
                    )
                    log.debug(
                      "Header validation failed: headers do not chain to waiting headers. Last waiting: {}, Headers first: {}",
                      state.waitingHeaders.lastOption.map(_.number),
                      headers.headOption.map(_.number)
                    )
                    // No blacklist: honest peers on an alternative chain head trigger this during reorgs.
                    baseState
                      .copy(nextDispatchBlock = baseState.nextBlockToFetch)
                  case Right(updatedState) =>
                    log.debug(
                      "Successfully validated and appended {} headers. New waiting headers count: {}",
                      orderedHeaders.size,
                      updatedState.waitingHeaders.size
                    )
                    // Full batch: peer likely has more blocks — eagerly advance knownTop.
                    // Partial batch (chain tip): reset nextDispatchBlock so the next fetch
                    // resumes from lastHeader+1, not from the pre-dispatch window. For
                    // concurrent slots, also drain any other in-flight windows (they'd fail
                    // chain validation anyway since the chain ends at lastHeader).
                    val finalState = if (orderedHeaders.size.toLong >= syncConfig.blockHeadersPerRequest) {
                      val lastHeader = orderedHeaders.maxBy(_.number)
                      updatedState.withPossibleNewTopAt(lastHeader.number + 1)
                    } else {
                      val lastHeader = orderedHeaders.lastOption.map(_.number).getOrElse(updatedState.lastBlock)
                      updatedState.copy(
                        nextDispatchBlock = lastHeader + 1,
                        headersToIgnore = updatedState.headersToIgnore + (updatedState.inFlightHeaders - 1).max(0),
                        inFlightHeaders = math.min(updatedState.inFlightHeaders, 1)
                      )
                    }
                    log.info(
                      "[RegularSync] headers={} from={} range=[{}-{}] waiting={}",
                      orderedHeaders.size,
                      peer.id,
                      orderedHeaders.headOption.map(_.number).getOrElse("-"),
                      orderedHeaders.lastOption.map(_.number).getOrElse("-"),
                      finalState.waitingHeaders.size
                    )
                    finalState
                }
              }

            // Stale-tip recovery (Bug 31): when enough independent peers reject our queue
            // state in a row, assume the waitingHeaders/readyBlocks tip is orphaned and
            // rewind. Without this, the fetcher loops forever on a poisoned seed state —
            // notably observed on the fast-sync → regular-sync handoff.
            if (afterAppend.shouldRewindOnRejections(BlockFetcher.HeaderRejectionRewindThreshold)) {
              val rewindTarget = (afterAppend.lastBlock - BlockFetcher.HeaderRejectionRewindBlocks).max(0)
              log.warn(
                "Stale chain tip detected: {} consecutive header rejections across peers. " +
                  "Rewinding from block {} to block {} to recover.",
                afterAppend.consecutiveHeaderRejections,
                afterAppend.lastBlock,
                rewindTarget
              )
              val (_, rewoundState) = afterAppend.invalidateBlocksFrom(rewindTarget, None)
              rewoundState.copy(consecutiveHeaderRejections = 0)
            } else afterAppend
          }
        fetchBlocks(newState)

      case ReceivedHeaders(peer, _) if !state.isFetchingHeaders =>
        log.warn("Received late/duplicate headers from peer {} (not fetching). Clearing state.", peer.id)
        // Don't blacklist - this could be a late response from a previous request
        // Just ensure we're not stuck by attempting to fetch if needed
        fetchBlocks(state)

      case RetryHeadersRequest if state.isFetchingHeaders =>
        log.debug(
          "Retrying headers request (likely no suitable peer): inFlight={} toIgnore={}",
          state.inFlightHeaders,
          state.headersToIgnore
        )
        val baseRetry = state.withHeaderFetchReceived
        // When draining the ignore bucket (headersToIgnore > 0), nextDispatchBlock was already
        // correctly set by clearQueues() + invalidateBlocksFrom fix. Otherwise, reset it to the
        // queue tail so the retry dispatches from the right position.
        val retryState =
          if (state.headersToIgnore > 0) baseRetry
          else baseRetry.copy(nextDispatchBlock = state.nextBlockToFetch)
        fetchBlocks(retryState)

      case RetryHeadersRequest if !state.isFetchingHeaders =>
        log.warn("Received late RetryHeadersRequest (no in-flight headers). Triggering fetch.")
        fetchBlocks(state)

      case TickFetch =>
        // 500ms stall-recovery: re-evaluate dispatch in case state advanced without a reactive trigger
        log.debug(
          "[RegularSync] TickFetch: inFlight={} toIgnore={} nextDispatch={} top={}",
          state.inFlightHeaders,
          state.headersToIgnore,
          state.nextDispatchBlock,
          state.knownTop
        )
        fetchBlocks(state)

      case ReceivedBodies(peer, bodies) if state.isFetchingBodies =>
        log.debug("Received {} block bodies from peer {}", bodies.size, peer.id)
        if (state.fetchingBodiesState == AwaitingBodiesToBeIgnored) {
          log.debug("Block bodies will be ignored due to an invalidation was requested for them")
          fetchBlocks(state.withBodiesFetchReceived)
        } else {
          if (bodies.isEmpty) {
            log.debug(
              "Received empty bodies response from peer {} (expected up to {} bodies)",
              peer.id,
              state.waitingHeaders.size.min(syncConfig.blockBodiesPerRequest)
            )
          } else {
            log.debug("Processing {} bodies. Waiting headers: {}", bodies.size, state.waitingHeaders.size)
          }
          val newState =
            state.validateBodies(bodies) match {
              case Left(err) =>
                log.debug("Body validation failed from peer {}: {}", peer.id, err)
                log.debug("Blacklisting peer {} due to validation error", peer.id)
                peersClient ! BlacklistPeer(peer.id, err)
                state.withBodiesFetchReceived
              case Right(newBlocks) =>
                log.debug("Successfully validated {} blocks from received bodies", newBlocks.size)
                if (newBlocks.nonEmpty) {
                  log.debug(
                    "Block range validated: {} to {}",
                    newBlocks.headOption.map(_.number),
                    newBlocks.lastOption.map(_.number)
                  )
                }
                state.withBodiesFetchReceived.handleRequestedBlocks(newBlocks, peer.id)
            }
          val waitingHeadersDequeued = state.waitingHeaders.size - newState.waitingHeaders.size
          log.debug(
            "Processed {} new blocks from received block bodies (remaining waiting headers: {})",
            waitingHeadersDequeued,
            newState.waitingHeaders.size
          )
          fetchBlocks(newState)
        }

      case ReceivedBodies(peer, bodies) if !state.isFetchingBodies =>
        log.warn(
          "Received late/duplicate block bodies ({} bodies) from peer {} (not fetching). Clearing state.",
          bodies.size,
          peer.id
        )
        // Don't blacklist - this could be a late response from a previous request
        // Just ensure we're not stuck by attempting to fetch if needed
        fetchBlocks(state)

      case retry: RetryBodiesRequest if state.isFetchingBodies =>
        val updatedTriedPeers = retry.failedPeerId.fold(retry.triedPeers)(retry.triedPeers + _)
        val newRetryCount = retry.retryCount + 1
        val clearedState = state.withBodiesFetchReceived
        if (newRetryCount > syncConfig.maxBodyFetchRetries) {
          log.warn(
            "Body fetch exceeded max retries ({}), clearing tried peers and resetting",
            syncConfig.maxBodyFetchRetries
          )
          // Reset tried peers — previously failed peers may have recovered after blacklist expiry
          fetchBodiesWithRetryState(clearedState, Set.empty, 0)
          processFetchCommands(clearedState.withNewBodiesFetch)
        } else {
          log.debug(
            "Retrying bodies request (tried: {}, retry: {}/{})",
            updatedTriedPeers.size,
            newRetryCount,
            syncConfig.maxBodyFetchRetries
          )
          fetchBodiesWithRetryState(clearedState, updatedTriedPeers, newRetryCount)
          processFetchCommands(clearedState.withNewBodiesFetch)
        }

      case _: RetryBodiesRequest if !state.isFetchingBodies =>
        log.warn("Received late/duplicate RetryBodiesRequest (not fetching). Clearing state and retrying fetch.")
        fetchBlocks(state)

      case FetchStateNode(hash, replyTo, stateRoot, paths, networkHead, isByteCode) =>
        val head = if (networkHead > 0) networkHead else state.knownTop
        log.debug(
          "Fetching state node for hash {}, networkHead={}, isByteCode={}",
          ByteStringUtils.hash2string(hash),
          head,
          isByteCode
        )
        // Forward the latest header stateRoot we've seen as a fallback. SNAP peers prune to ~128
        // blocks; if the requested parent stateRoot is older than that, every peer returns empty
        // TrieNodes and StateNodeFetcher exhausts. The recent canonical root IS servable, and
        // the same nibble path usually still leads to the same content-addressed node.
        val fallbackRoot = state.recentCanonicalStateRoot.filter(r => !stateRoot.contains(r))
        stateNodeFetcher ! StateNodeFetcher.FetchStateNode(
          hash,
          replyTo,
          stateRoot,
          paths,
          head,
          isByteCode,
          fallbackRoot
        )
        Behaviors.same

      case AdaptedMessageFromEventBus(NewBlockHashes(hashes), _) =>
        log.debug("Received NewBlockHashes numbers {}", hashes.map(_.number).mkString(", "))
        val newState = state.validateNewBlockHashes(hashes) match {
          case Left(err) =>
            log.debug("NewBlockHashes validation failed: {}", err)
            state
          case Right(validHashes) =>
            log.debug(
              "Validated {} new block hashes. Setting possible new top to {}",
              validHashes.size,
              validHashes.lastOption.map(_.number)
            )
            state.withPossibleNewTopAt(validHashes.lastOption.map(_.number))
        }
        supervisor ! ProgressProtocol.GotNewBlock(newState.knownTop)
        fetchBlocks(newState)

      case AdaptedMessageFromEventBus(msg: ETH69.BlockRangeUpdate, _) =>
        log.debug("Received BlockRangeUpdate earliest={} latest={}", msg.earliestBlock, msg.latestBlock)
        val newState = state.withPossibleNewTopAt(msg.latestBlock)
        supervisor ! ProgressProtocol.GotNewBlock(newState.knownTop)
        fetchBlocks(newState)

      case AdaptedMessageFromEventBus(ETHPackets.NewBlock(block, _), peerId) =>
        handleNewBlock(block, peerId, state)

      case BlockImportFailed(blockNr, reason) =>
        log.debug("Block import failed for block {}: {}", blockNr, reason)
        val (peerId, newState) = state.invalidateBlocksFrom(blockNr)
        peerId.foreach { id =>
          log.debug("Blacklisting peer {} due to block import failure", id)
          peersClient ! BlacklistPeer(id, reason)
        }
        fetchBlocks(newState)

      case AdaptedMessageFromEventBus(ETHPackets.BlockHeaders(_, headers), _) =>
        headers.lastOption
          .map { bh =>
            log.debug("Candidate for new top at block {}, current known top {}", bh.number, state.knownTop)
            val newState = state.withPossibleNewTopAt(bh.number)
            fetchBlocks(newState)
          }
          .getOrElse(processFetchCommands(state))
      // keep fetcher state updated in case new mined block was imported
      case InternalLastBlockImport(blockNr) =>
        log.debug("New mined block {} imported from the inside", blockNr)
        val newState = state.withLastBlock(blockNr).withPossibleNewTopAt(blockNr)
        fetchBlocks(newState)

      case msg =>
        log.debug("Block fetcher received unhandled message {}", msg)
        Behaviors.unhandled
    }

  private def handleNewBlock(block: Block, peerId: PeerId, state: BlockFetcherState): Behavior[FetchCommand] = {
    log.debug("Received NewBlock {} from peer {}", block.idTag, peerId)
    val newBlockNr = block.number
    val nextExpectedBlock = state.lastBlock + 1

    log.debug(
      "NewBlock analysis: block number {}, next expected {}, is on top: {}",
      newBlockNr,
      nextExpectedBlock,
      state.isOnTop
    )

    if (state.isOnTop && newBlockNr == nextExpectedBlock) {
      log.debug("Passing block {} directly to importer (on top and sequential)", newBlockNr)
      val newState = state
        .withPeerForBlocks(peerId, Seq(newBlockNr))
        .withLastBlock(newBlockNr)
        .withKnownTopAt(newBlockNr)
      state.importer ! ImportNewBlock(block, peerId)
      supervisor ! ProgressProtocol.GotNewBlock(newState.knownTop)
      processFetchCommands(newState)
    } else {
      log.debug(
        "Handling block {} as future block (on top: {}, expected: {}, received: {})",
        block.idTag,
        state.isOnTop,
        nextExpectedBlock,
        newBlockNr
      )
      handleFutureBlock(block, state)
    }
  }

  private def handleFutureBlock(block: Block, state: BlockFetcherState): Behavior[FetchCommand] = {
    log.debug("Ignoring received block {} as it doesn't match local state or fetch side is not on top", block.idTag)
    log.debug(
      "Block number: {}, last block: {}, known top: {}, is on top: {}",
      block.number,
      state.lastBlock,
      state.knownTop,
      state.isOnTop
    )
    val newState = state.withPossibleNewTopAt(block.number)
    supervisor ! ProgressProtocol.GotNewBlock(newState.knownTop)
    fetchBlocks(newState)
  }

  private def handlePickedBlocks(
      state: BlockFetcherState,
      replyTo: ClassicActorRef
  )(pickResult: Option[(NonEmptyList[Block], BlockFetcherState)]): BlockFetcherState =
    pickResult
      .tap { case (blocks, _) =>
        replyTo ! PickedBlocks(blocks)
      }
      .fold(state)(_._2)

  private def fetchBlocks(state: BlockFetcherState): Behavior[FetchCommand] = {
    log.debug(
      "[RegularSync] state=ready:{} waiting:{} inFlight:{} toIgnore:{} fetchingBodies:{} nextDispatch:{}",
      state.readyBlocks.size,
      state.waitingHeaders.size,
      state.inFlightHeaders,
      state.headersToIgnore,
      state.isFetchingBodies,
      state.nextDispatchBlock
    )
    // Fill all available concurrent header slots, then dispatch bodies once.
    var s = state |> tryFetchBodies
    val slotsAvailable = MaxConcurrentHeaderSlots - s.inFlightHeaders
    var dispatched = 0
    while (dispatched < slotsAvailable) {
      val before = s
      s = s |> tryFetchHeaders
      if (s eq before) dispatched = slotsAvailable // no progress — stop trying
      else dispatched += 1
    }
    processFetchCommands(s)
  }

  private def tryFetchHeaders(fetcherState: BlockFetcherState): BlockFetcherState =
    Some(fetcherState)
      .filter { state =>
        val canDispatch = state.canDispatchHeaders
        if (!canDispatch)
          log.debug(
            "Skipping header fetch: inFlight={} toIgnore={} maxSlots={}",
            state.inFlightHeaders,
            state.headersToIgnore,
            MaxConcurrentHeaderSlots
          )
        canDispatch
      }
      .filter { state =>
        val notAtTop = state.nextDispatchBlock <= state.knownTop
        if (!notAtTop)
          log.debug(
            "Skipping header fetch: dispatch window at top (nextDispatch: {}, known top: {})",
            state.nextDispatchBlock,
            state.knownTop
          )
        notAtTop
      }
      .filter { state =>
        val backpressure = state.hasImporterBackpressure(syncConfig.maxReadyBlocksQueueSize)
        if (backpressure)
          log.warn(
            "[RegularSync] import backpressure: readyBlocks={} >= threshold={}, pausing header fetch",
            state.readyBlocks.size,
            syncConfig.maxReadyBlocksQueueSize
          )
        !backpressure
      }
      .filter { state =>
        val headersQueueFull = state.hasEnoughWaitingHeaders(syncConfig.maxFetcherQueueSize)
        if (headersQueueFull)
          log.debug(
            "Skipping header fetch: waiting headers queue at depth {} (max: {})",
            state.waitingHeaders.size,
            syncConfig.maxFetcherQueueSize
          )
        !headersQueueFull
      }
      .tap(fetchHeaders)
      .map(s => s.withNewHeadersFetch(syncConfig.blockHeadersPerRequest))
      .getOrElse(fetcherState)

  private def fetchHeaders(state: BlockFetcherState): Unit = {
    val blockNr = state.nextDispatchBlock
    val amount = syncConfig.blockHeadersPerRequest
    log.debug(
      "Initiating header fetch: block number {} for {} headers (inFlight: {}, known top: {})",
      blockNr,
      amount,
      state.inFlightHeaders,
      state.knownTop
    )
    headersFetcher ! HeadersFetcher.FetchHeadersByNumber(blockNr, amount)
  }

  private def tryFetchBodies(fetcherState: BlockFetcherState): BlockFetcherState =
    Some(fetcherState)
      .filter { state =>
        val canFetch = !state.isFetchingBodies
        if (!canFetch) log.debug("Skipping body fetch: already fetching bodies")
        canFetch
      }
      .filter { state =>
        val hasHeaders = state.waitingHeaders.nonEmpty
        if (!hasHeaders) log.debug("Skipping body fetch: no waiting headers")
        else log.debug("Ready to fetch bodies for {} waiting headers", state.waitingHeaders.size)
        hasHeaders
      }
      .tap(fetchBodies)
      .map(_.withNewBodiesFetch)
      .getOrElse(fetcherState)

  private def fetchBodies(state: BlockFetcherState): Unit =
    fetchBodiesWithRetryState(state, triedPeers = Set.empty, retryCount = 0)

  private def fetchBodiesWithRetryState(
      state: BlockFetcherState,
      triedPeers: Set[PeerId],
      retryCount: Int
  ): Unit = {
    val hashes = state.takeHashes(syncConfig.blockBodiesPerRequest)
    log.debug(
      "Initiating body fetch: {} hashes (max per request: {}, total waiting: {}, tried peers: {}, retry: {})",
      hashes.size,
      syncConfig.blockBodiesPerRequest,
      state.waitingHeaders.size,
      triedPeers.size,
      retryCount
    )
    log.debug(
      "First hash: {}, Last hash: {}",
      hashes.headOption.map(ByteStringUtils.hash2string),
      hashes.lastOption.map(ByteStringUtils.hash2string)
    )
    bodiesFetcher ! BodiesFetcher.FetchBodies(hashes, triedPeers, retryCount)
  }
}

object BlockFetcher {

  /** Number of consecutive header-rejection peers required before we conclude the queue state is stale and trigger an
    * InvalidateBlocksFrom rewind (Bug 31 recovery). With 3, we avoid churning on a single buggy peer but still catch
    * every "all peers reject" scenario well before the 60s blacklist cooldown completes on the first peer.
    */
  val HeaderRejectionRewindThreshold: Int = 3

  /** How far back from `lastBlock` to rewind when stale-tip recovery fires. One batch of headers is enough to land us
    * at a block that ALL peers still have cached, even in the presence of short reorgs.
    */
  val HeaderRejectionRewindBlocks: Int = 128

  def apply(
      peersClient: ClassicActorRef,
      peerEventBus: ClassicActorRef,
      supervisor: ClassicActorRef,
      syncConfig: SyncConfig,
      blockValidator: BlockValidator
  ): Behavior[FetchCommand] =
    Behaviors.setup(context =>
      Behaviors.withTimers(timers =>
        new BlockFetcher(peersClient, peerEventBus, supervisor, syncConfig, blockValidator, context, timers)
      )
    )

  sealed trait FetchCommand
  final case class Start(importer: ClassicActorRef, fromBlock: BigInt) extends FetchCommand
  final case class FetchStateNode(
      hash: ByteString,
      replyTo: ClassicActorRef,
      stateRoot: Option[ByteString] = None,
      paths: Option[Seq[Seq[ByteString]]] = None,
      networkHead: BigInt = BigInt(0),
      // True when `hash` is a contract codeHash and the recovery should use SNAP GetByteCodes.
      // False (default) means it's a trie-node hash and should use GetTrieNodes / GetNodeData.
      // Discriminating here avoids an infinite GetNodeData loop on ETH68-only peer sets, where
      // GetNodeData was removed but GetByteCodes is still served by every snap-capable peer.
      isByteCode: Boolean = false
  ) extends FetchCommand
  case object RetryFetchStateNode extends FetchCommand
  final case class PickBlocks(amount: Int, replyTo: ClassicActorRef) extends FetchCommand
  final case class StrictPickBlocks(from: BigInt, atLEastWith: BigInt, replyTo: ClassicActorRef) extends FetchCommand
  case object PrintStatus extends FetchCommand
  final case class InvalidateBlocksFrom(fromBlock: BigInt, reason: String, toBlacklist: Option[BigInt])
      extends FetchCommand

  object InvalidateBlocksFrom {

    def apply(from: BigInt, reason: String, shouldBlacklist: Boolean = true): InvalidateBlocksFrom =
      new InvalidateBlocksFrom(from, reason, if (shouldBlacklist) Some(from) else None)

    def apply(from: BigInt, reason: String, toBlacklist: Option[BigInt]): InvalidateBlocksFrom =
      new InvalidateBlocksFrom(from, reason, toBlacklist)
  }
  final case class BlockImportFailed(blockNr: BigInt, reason: BlacklistReason) extends FetchCommand
  final case class InternalLastBlockImport(blockNr: BigInt) extends FetchCommand
  final case class RetryBodiesRequest(
      failedPeerId: Option[PeerId] = None,
      triedPeers: Set[PeerId] = Set.empty,
      retryCount: Int = 0
  ) extends FetchCommand
  case object RetryHeadersRequest extends FetchCommand
  // 500ms stall-recovery heartbeat — re-evaluates dispatch slots in case a state
  // transition (BRU, invalidation, peer change) happened without a reactive trigger.
  case object TickFetch extends FetchCommand
  final case class AdaptedMessageFromEventBus(message: Message, peerId: PeerId) extends FetchCommand
  final case class ReceivedHeaders(peer: Peer, headers: Seq[BlockHeader]) extends FetchCommand
  final case class ReceivedBodies(peer: Peer, bodies: Seq[BlockBody]) extends FetchCommand

  sealed trait FetchResponse
  final case class PickedBlocks(blocks: NonEmptyList[Block]) extends FetchResponse
  final case class FetchedStateNode(stateNode: ETHPackets.NodeData) extends FetchResponse
}
