package com.chipprbots.ethereum.blockchain.sync.regular

import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.AbstractBehavior
import org.apache.pekko.actor.typed.scaladsl.ActorContext
import org.apache.pekko.actor.typed.scaladsl.Behaviors
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
import com.chipprbots.ethereum.blockchain.sync.regular.BlockFetcherState.AwaitingHeadersToBeIgnored
import com.chipprbots.ethereum.blockchain.sync.regular.BlockFetcherState.HeadersNotFormingSeq
import com.chipprbots.ethereum.blockchain.sync.regular.BlockFetcherState.HeadersNotMatchingReadyBlocks
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
import com.chipprbots.ethereum.network.p2p.messages.BaseETH6XMessages
import com.chipprbots.ethereum.network.p2p.messages.Codes
import com.chipprbots.ethereum.network.p2p.messages.ETC64
import com.chipprbots.ethereum.network.p2p.messages.ETH62._
import com.chipprbots.ethereum.network.p2p.messages.ETH62.{BlockHeaders => ETH62BlockHeaders}
import com.chipprbots.ethereum.network.p2p.messages.ETH63.NodeData
import com.chipprbots.ethereum.network.p2p.messages.ETH66.{BlockHeaders => ETH66BlockHeaders}
import com.chipprbots.ethereum.utils.ByteStringUtils
import com.chipprbots.ethereum.utils.Config.SyncConfig
import com.chipprbots.ethereum.utils.FunctorOps._

class BlockFetcher(
    val peersClient: ClassicActorRef,
    val peerEventBus: ClassicActorRef,
    val supervisor: ClassicActorRef,
    val syncConfig: SyncConfig,
    val blockValidator: BlockValidator,
    context: ActorContext[BlockFetcher.FetchCommand]
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
              Set(Codes.NewBlockCode, Codes.NewBlockHashesCode, Codes.BlockHeadersCode),
              PeerSelector.AllPeers
            )
          ),
          sa.toClassic
        )
        log.debug("BlockFetcher subscribed to peer events")
        BlockFetcherState.initial(importer, blockValidator, fromBlock) |> fetchBlocks
      case msg =>
        log.debug("Fetcher subscribe adapter received unhandled message {}", msg)
        Behaviors.unhandled
    }

  // scalastyle:off cyclomatic.complexity method.length
  private def processFetchCommands(state: BlockFetcherState): Behavior[FetchCommand] =
    Behaviors.receiveMessage {
      case PrintStatus =>
        log.info("BlockFetcher status: {}", state.status)
        log.debug("BlockFetcher detailed status: {}", state.statusDetailed)
        log.debug(
          "Current state - last block: {}, known top: {}, is on top: {}, ready blocks: {}, waiting headers: {}",
          state.lastBlock,
          state.knownTop,
          state.isOnTop,
          state.readyBlocks.size,
          state.waitingHeaders.size
        )
        Behaviors.same

      case PickBlocks(amount, replyTo) =>
        log.debug("PickBlocks request for {} blocks (ready blocks: {})", amount, state.readyBlocks.size)
        state.pickBlocks(amount) |> handlePickedBlocks(state, replyTo) |> fetchBlocks

      case StrictPickBlocks(from, atLeastWith, replyTo) =>
        // FIXME: Consider having StrictPickBlocks calls guaranteeing this
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
        if (state.waitingHeaders.isEmpty) {
          log.debug("First successful header fetch, notifying supervisor to start fetching")
          supervisor ! ProgressProtocol.StartedFetching
        }
        val newState =
          if (state.fetchingHeadersState == AwaitingHeadersToBeIgnored) {
            log.debug(
              "Received {} headers starting from block {} that will be ignored",
              headers.size,
              headers.headOption.map(_.number)
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
            state.appendHeaders(headers) match {
              case Left(HeadersNotFormingSeq) =>
                log.debug("Dismissed received headers due to: {} (peer: {})", HeadersNotFormingSeq.description, peer.id)
                log.debug(
                  "Header validation failed: headers do not form a sequence. First: {}, Last: {}, Count: {}",
                  headers.headOption.map(_.number),
                  headers.lastOption.map(_.number),
                  headers.size
                )
                peersClient ! BlacklistPeer(peer.id, BlacklistReason.UnrequestedHeaders)
                state.withHeaderFetchReceived
              case Left(HeadersNotMatchingReadyBlocks) =>
                log.debug(
                  "Dismissed received headers due to: {} (peer: {})",
                  HeadersNotMatchingReadyBlocks.description,
                  peer.id
                )
                log.debug(
                  "Header validation failed: headers do not match ready blocks. Ready blocks: {}, Headers first: {}",
                  state.readyBlocks.lastOption.map(_.number),
                  headers.headOption.map(_.number)
                )
                peersClient ! BlacklistPeer(peer.id, BlacklistReason.UnrequestedHeaders)
                state.withHeaderFetchReceived
              case Left(err) =>
                log.debug("Dismissed received headers due to: {} (peer: {})", err, peer.id)
                log.debug("Header validation error details: {}", err.description)
                state.withHeaderFetchReceived
              case Right(updatedState) =>
                log.debug(
                  "Successfully validated and appended {} headers. New waiting headers count: {}",
                  headers.size,
                  updatedState.waitingHeaders.size
                )
                updatedState.withHeaderFetchReceived
            }
          }
        fetchBlocks(newState)

      case ReceivedHeaders(peer, _) if !state.isFetchingHeaders =>
        peersClient ! BlacklistPeer(peer.id, BlacklistReason.UnrequestedHeaders)
        Behaviors.same

      case RetryHeadersRequest if state.isFetchingHeaders =>
        log.debug("Retrying headers request (likely due to no suitable peer available)")
        fetchBlocks(state.withHeaderFetchReceived)

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

      case ReceivedBodies(peer, _) if !state.isFetchingBodies =>
        peersClient ! BlacklistPeer(peer.id, BlacklistReason.UnrequestedBodies)
        Behaviors.same

      case RetryBodiesRequest if state.isFetchingBodies =>
        log.debug("Retrying bodies request (likely due to no suitable peer available)")
        fetchBlocks(state.withBodiesFetchReceived)

      case FetchStateNode(hash, replyTo) =>
        log.debug("Fetching state node for hash {}", ByteStringUtils.hash2string(hash))
        stateNodeFetcher ! StateNodeFetcher.FetchStateNode(hash, replyTo)
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

      case AdaptedMessageFromEventBus(BaseETH6XMessages.NewBlock(block, _), peerId) =>
        handleNewBlock(block, peerId, state)

      case AdaptedMessageFromEventBus(ETC64.NewBlock(block, _), peerId) =>
        handleNewBlock(block, peerId, state)

      case BlockImportFailed(blockNr, reason) =>
        log.debug("Block import failed for block {}: {}", blockNr, reason)
        val (peerId, newState) = state.invalidateBlocksFrom(blockNr)
        peerId.foreach { id =>
          log.debug("Blacklisting peer {} due to block import failure", id)
          peersClient ! BlacklistPeer(id, reason)
        }
        fetchBlocks(newState)

      case AdaptedMessageFromEventBus(ETH62BlockHeaders(headers), _) =>
        headers.lastOption
          .map { bh =>
            log.debug("Candidate for new top at block {}, current known top {}", bh.number, state.knownTop)
            val newState = state.withPossibleNewTopAt(bh.number)
            fetchBlocks(newState)
          }
          .getOrElse(processFetchCommands(state))
      case AdaptedMessageFromEventBus(ETH66BlockHeaders(_, headers), _) =>
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
    val newState = state |> tryFetchHeaders |> tryFetchBodies
    processFetchCommands(newState)
  }

  private def tryFetchHeaders(fetcherState: BlockFetcherState): BlockFetcherState =
    Some(fetcherState)
      .filter { state =>
        val canFetch = !state.isFetchingHeaders
        if (!canFetch) log.debug("Skipping header fetch: already fetching headers")
        canFetch
      }
      .filter { state =>
        val notAtTop = !state.hasFetchedTopHeader
        if (!notAtTop)
          log.debug(
            "Skipping header fetch: already at top (next block: {}, known top: {})",
            state.nextBlockToFetch,
            state.knownTop
          )
        notAtTop
      }
      .filter { state =>
        val hasSpace = !state.hasReachedSize(syncConfig.maxFetcherQueueSize)
        if (!hasSpace)
          log.debug(
            "Skipping header fetch: queue full (size: {}, max: {})",
            state.readyBlocks.size + state.waitingHeaders.size,
            syncConfig.maxFetcherQueueSize
          )
        hasSpace
      }
      .tap(fetchHeaders)
      .map(_.withNewHeadersFetch)
      .getOrElse(fetcherState)

  private def fetchHeaders(state: BlockFetcherState): Unit = {
    val blockNr = state.nextBlockToFetch
    val amount = syncConfig.blockHeadersPerRequest
    log.debug(
      "Initiating header fetch: block number {} for {} headers (last block: {}, known top: {})",
      blockNr,
      amount,
      state.lastBlock,
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

  private def fetchBodies(state: BlockFetcherState): Unit = {
    val hashes = state.takeHashes(syncConfig.blockBodiesPerRequest)
    log.debug(
      "Initiating body fetch: {} hashes (max per request: {}, total waiting: {})",
      hashes.size,
      syncConfig.blockBodiesPerRequest,
      state.waitingHeaders.size
    )
    log.debug(
      "First hash: {}, Last hash: {}",
      hashes.headOption.map(ByteStringUtils.hash2string),
      hashes.lastOption.map(ByteStringUtils.hash2string)
    )
    bodiesFetcher ! BodiesFetcher.FetchBodies(hashes)
  }
}

object BlockFetcher {
  def apply(
      peersClient: ClassicActorRef,
      peerEventBus: ClassicActorRef,
      supervisor: ClassicActorRef,
      syncConfig: SyncConfig,
      blockValidator: BlockValidator
  ): Behavior[FetchCommand] =
    Behaviors.setup(context =>
      new BlockFetcher(peersClient, peerEventBus, supervisor, syncConfig, blockValidator, context)
    )

  sealed trait FetchCommand
  final case class Start(importer: ClassicActorRef, fromBlock: BigInt) extends FetchCommand
  final case class FetchStateNode(hash: ByteString, replyTo: ClassicActorRef) extends FetchCommand
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
  case object RetryBodiesRequest extends FetchCommand
  case object RetryHeadersRequest extends FetchCommand
  final case class AdaptedMessageFromEventBus(message: Message, peerId: PeerId) extends FetchCommand
  final case class ReceivedHeaders(peer: Peer, headers: Seq[BlockHeader]) extends FetchCommand
  final case class ReceivedBodies(peer: Peer, bodies: Seq[BlockBody]) extends FetchCommand

  sealed trait FetchResponse
  final case class PickedBlocks(blocks: NonEmptyList[Block]) extends FetchResponse
  final case class FetchedStateNode(stateNode: NodeData) extends FetchResponse
}
