package com.chipprbots.ethereum.blockchain.sync.regular

import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.ActorContext
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.scaladsl.TimerScheduler
import org.apache.pekko.actor.typed.ActorRef as TypedActorRef
import org.apache.pekko.actor.typed.pubsub.Topic
import org.apache.pekko.event.Logging
import org.apache.pekko.event.LoggingAdapter
import org.apache.pekko.util.ByteString

import cats.data.NonEmptyList
import cats.effect.IO
import cats.effect.unsafe.IORuntime
import cats.implicits.*

import scala.concurrent.duration.*

import com.chipprbots.ethereum.blockchain.sync.Blacklist
import com.chipprbots.ethereum.blockchain.sync.Blacklist.BlacklistReason
import com.chipprbots.ethereum.blockchain.sync.SyncProtocol
import com.chipprbots.ethereum.blockchain.sync.fast.FastSyncBranchResolverActor
import com.chipprbots.ethereum.blockchain.sync.regular.BlockBroadcast.BlockToBroadcast
import com.chipprbots.ethereum.blockchain.sync.regular.BlockBroadcasterActor.BroadcastBlocks
import com.chipprbots.ethereum.blockchain.sync.regular.BlockImporter.Command
import com.chipprbots.ethereum.blockchain.sync.regular.RegularSync.ProgressProtocol
import com.chipprbots.ethereum.consensus.ConsensusAdapter
import com.chipprbots.ethereum.consensus.engine.InvalidChainReporter
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.db.storage.StateStorage
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.domain.BlockchainWriter
import com.chipprbots.ethereum.jsonrpc.NewBlockImported
import com.chipprbots.ethereum.ledger.*
import com.chipprbots.ethereum.mpt.*
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.MissingAccountNodeException
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.MissingNodeException
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.MissingStorageNodeException
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.PeerEventBusActor.Command as PeerEventBusCommand
import com.chipprbots.ethereum.network.PeerId
import com.chipprbots.ethereum.nodebuilder.BlockchainConfigBuilder
import com.chipprbots.ethereum.ommers.OmmersPool
import com.chipprbots.ethereum.ommers.OmmersPool.AddOmmers
import com.chipprbots.ethereum.transactions.PendingTransactionsManager
import com.chipprbots.ethereum.transactions.PendingTransactionsManager.AddUncheckedTransactions
import com.chipprbots.ethereum.transactions.PendingTransactionsManager.RemoveTransactions
import com.chipprbots.ethereum.utils.ByteStringUtils
import com.chipprbots.ethereum.utils.Config.SyncConfig
import com.chipprbots.ethereum.utils.FunctorOps.*

object BlockImporter:
  // After this many consecutive state-node-fetch exhausts on the same block, regular sync
  // is deemed terminally stuck and we escalate to SNAP re-sync via SyncProtocol.RegularSyncStuck.
  // 3 × 5-min backoff = ~15 minutes of bounded retry before invoking the escape valve.
  val StuckEscapeThreshold: Int = 3

  // How far back to rewind the canonical chain index during fork recovery (SYNC-FORK path).
  // 128 blocks provides enough depth to cover common shallow forks without resyncing the
  // entire chain; deeper forks fall back to SNAP re-sync via RegularSyncStuck.
  val MaxForkAncestryDepth: Int = 128

  // Exhaust counter that outlives individual actor instances so Pekko Restarts don't reset
  // the progress toward StuckEscapeThreshold. Zeroed in apply() (fresh regular-sync session).
  private[regular] var survivedExhausts: Int = 0

  private[regular] case object SyncRetryTick extends Command
  private[regular] val RetryKey = "BlockImporterRetry"

  // scalastyle:off parameter.number
  def apply(
      fetcher: TypedActorRef[BlockFetcher.FetchCommand],
      consensus: ConsensusAdapter,
      blockchainReader: BlockchainReader,
      blockchainWriter: BlockchainWriter,
      stateStorage: StateStorage,
      evmCodeStorage: EvmCodeStorage,
      branchResolution: BranchResolution,
      syncConfig: SyncConfig,
      ommersPool: TypedActorRef[OmmersPool.Command],
      broadcaster: TypedActorRef[BlockBroadcasterActor.BroadcasterMsg],
      pendingTransactionsManager: TypedActorRef[PendingTransactionsManager.Command],
      blockTopic: TypedActorRef[Topic.Command[NewBlockImported]],
      supervisor: TypedActorRef[RegularSync.Command],
      peerEventBus: TypedActorRef[PeerEventBusCommand],
      networkPeerManager: TypedActorRef[NetworkPeerManagerActor.Command],
      blockchain: Blockchain,
      blacklist: Blacklist,
      configBuilder: BlockchainConfigBuilder
  ): Behavior[Command] =
    Behaviors.setup { ctx =>
      Behaviors.withTimers { timers =>
        BlockImporter.survivedExhausts = 0
        val logic = new BlockImporterLogic(
          ctx,
          timers,
          fetcher,
          consensus,
          blockchainReader,
          blockchainWriter,
          stateStorage,
          evmCodeStorage,
          branchResolution,
          syncConfig,
          ommersPool,
          broadcaster,
          pendingTransactionsManager,
          blockTopic,
          supervisor,
          peerEventBus,
          networkPeerManager,
          blockchain,
          blacklist,
          configBuilder
        )
        timers.startTimerWithFixedDelay(RetryKey, SyncRetryTick, syncConfig.syncRetryInterval)
        logic.idle
      }
    }

  sealed trait Command
  case object Start extends Command
  case class MinedBlock(block: Block) extends Command
  case class ImportNewBlock(block: Block, peerId: PeerId) extends Command
  case class ImportDone(newBehavior: NewBehavior, blockImportType: BlockImportType) extends Command
  case object PickBlocks extends Command
  case object PrintStatus extends Command
  case class StartForkRecovery(failedBlockNumber: BigInt) extends Command
  final private[regular] case class FetcherResponse(r: BlockFetcher.FetchResponse) extends Command
  final private[regular] case class BranchResolverMsg(r: FastSyncBranchResolverActor.BranchResolverResponse)
      extends Command

  sealed trait NewBehavior
  case object Running extends NewBehavior
  case class ResolvingMissingNode(blocksToRetry: NonEmptyList[Block]) extends NewBehavior
  case class ResolvingBranch(from: BigInt) extends NewBehavior

  sealed trait BlockImportType:
    def recordMetric(nanos: Long): Unit

  case object MinedBlockImport extends BlockImportType:
    override def recordMetric(nanos: Long): Unit = RegularSyncMetrics.recordMinedBlockPropagationTimer(nanos)

  case object NewBlockImport extends BlockImportType:
    override def recordMetric(nanos: Long): Unit = RegularSyncMetrics.recordImportNewBlockPropagationTimer(nanos)

  case object DefaultBlockImport extends BlockImportType:
    override def recordMetric(nanos: Long): Unit = RegularSyncMetrics.recordDefaultBlockPropagationTimer(nanos)

  case class ImporterState(
      importing: Boolean,
      resolvingBranchFrom: Option[BigInt]
  ):
    def importingBlocks(): ImporterState = copy(importing = true)

    def notImportingBlocks(): ImporterState = copy(importing = false)

    def resolvingBranch(from: BigInt): ImporterState = copy(resolvingBranchFrom = Some(from))

    def branchResolved(): ImporterState = copy(resolvingBranchFrom = None)

    def isResolvingBranch: Boolean = resolvingBranchFrom.isDefined

  object ImporterState:
    def initial: ImporterState = ImporterState(
      importing = false,
      resolvingBranchFrom = None
    )

final private class BlockImporterLogic(
    ctx: ActorContext[Command],
    timers: TimerScheduler[Command],
    fetcher: TypedActorRef[BlockFetcher.FetchCommand],
    consensus: ConsensusAdapter,
    blockchainReader: BlockchainReader,
    blockchainWriter: BlockchainWriter,
    stateStorage: StateStorage,
    evmCodeStorage: EvmCodeStorage,
    branchResolution: BranchResolution,
    syncConfig: SyncConfig,
    ommersPool: TypedActorRef[OmmersPool.Command],
    broadcaster: TypedActorRef[BlockBroadcasterActor.BroadcasterMsg],
    pendingTransactionsManager: TypedActorRef[PendingTransactionsManager.Command],
    blockTopic: TypedActorRef[Topic.Command[NewBlockImported]],
    supervisor: TypedActorRef[RegularSync.Command],
    peerEventBus: TypedActorRef[PeerEventBusCommand],
    networkPeerManager: TypedActorRef[NetworkPeerManagerActor.Command],
    blockchain: Blockchain,
    blacklist: Blacklist,
    configBuilder: BlockchainConfigBuilder
):
  import BlockImporter.*
  import configBuilder.*

  given runtime: IORuntime = IORuntime.global

  private val log: LoggingAdapter = Logging(ctx.system.classicSystem, classOf[BlockImporterImpl])
  private val selfRef = ctx.self

  private val branchResolverAdapter: TypedActorRef[FastSyncBranchResolverActor.BranchResolverResponse] =
    ctx.messageAdapter[FastSyncBranchResolverActor.BranchResolverResponse](BranchResolverMsg(_))

  private val fetcherResponseAdapter: TypedActorRef[BlockFetcher.FetchResponse] =
    ctx.messageAdapter[BlockFetcher.FetchResponse](FetcherResponse(_))

  private var pendingStateNodeHash: Option[ByteString] = None
  private var unknownParentStrikes: Map[ByteString, Int] = Map.empty
  private val BadBlockEvictionThreshold = 3
  private val ForkDetectThreshold = 5

  def idle: Behavior[Command] =
    Behaviors.receiveMessage {
      case Start => start()
      case _     => Behaviors.same
    }

  def running(state: ImporterState): Behavior[Command] =
    Behaviors.receiveMessage {
      case SyncRetryTick =>
        selfRef ! PickBlocks
        Behaviors.same

      case FetcherResponse(BlockFetcher.PickedBlocks(blocks: NonEmptyList[Block @unchecked])) =>
        SignedTransaction.retrieveSendersInBackGround(blocks.toList.map(_.body))
        importBlocks(blocks, DefaultBlockImport)(state)

      case MinedBlock(block) if !state.importing =>
        importBlock(
          block,
          new MinedBlockImportMessages(block),
          MinedBlockImport,
          informFetcherOnFail = false,
          internally = true
        )(state)

      case _: MinedBlock => Behaviors.same

      case ImportNewBlock(block, peerId) if !state.importing =>
        importBlock(
          block,
          new NewBlockImportMessages(block, peerId),
          NewBlockImport,
          informFetcherOnFail = true,
          internally = false
        )(state)

      case _: ImportNewBlock => Behaviors.same

      case ImportDone(newBehavior, importType) =>
        val newState = state.notImportingBlocks().branchResolved()
        newBehavior match
          case Running =>
            selfRef ! PickBlocks
          case r: ResolvingBranch =>
            log.info(
              "Branch resolution dispatch: StrictPickBlocks from={} bestKnown={}",
              r.from,
              bestKnownBlockNumber
            )
            selfRef ! PickBlocks
          case _ =>
        nextBehavior(newBehavior, importType, newState)

      case PickBlocks if !state.importing =>
        pickBlocks(state)
        Behaviors.same

      case PickBlocks => Behaviors.same

      // Late-arriving state node from a previous resolvingMissingNode phase.
      // ReceiveTimeout may have moved us back to running before the fetch completed.
      // Save the node so the next import attempt finds it in storage.
      case FetcherResponse(BlockFetcher.FetchedStateNode(nodeData)) if nodeData.values.nonEmpty =>
        val node = nodeData.values.head
        val hash = kec256(node)
        log.info("Saving late-arriving fetched state node {}", ByteStringUtils.hash2string(hash))
        stateStorage.saveNode(hash, node.toArray, blockchainReader.getBestBlockNumber)
        // Also save as contract code in case this was a bytecode fetch
        try evmCodeStorage.put(hash, node).commit()
        catch case _: Exception => ()
        Behaviors.same

      case StartForkRecovery(failedBlockNumber: BigInt) =>
        handleForkRecovery(failedBlockNumber, state)

      case _ => Behaviors.same
    }

  private def resolvingMissingNode(blocksToRetry: NonEmptyList[Block], blockImportType: BlockImportType)(
      state: ImporterState
  ): Behavior[Command] =
    Behaviors.receiveMessage {
      case FetcherResponse(BlockFetcher.FetchedStateNode(nodeData)) if nodeData.values.isEmpty =>
        // StateNodeFetcher exhausted MaxStateNodeFetchRetries on this missing node — no current peer
        // can serve it via SNAP GetTrieNodes (typical: pivot fell out of the 128-block serve window
        // on every connected peer).
        val blockNum = blocksToRetry.head.number.value
        BlockImporter.survivedExhausts += 1
        val missingHashStr = pendingStateNodeHash.map(ByteStringUtils.hash2string).getOrElse("<unknown>")

        if BlockImporter.survivedExhausts >= BlockImporter.StuckEscapeThreshold then
          // Multiple consecutive exhausts mean peers genuinely don't have our parent state and
          // never will (we're far behind their snap-serve window). The only recovery is to re-pivot
          // via SNAP. Reset our local counter so we don't re-fire if SyncController bounces us back
          // to regular sync; the SnapFastEscapeHatch handles cycle limits.
          log.error(
            "Regular sync stuck on block {} after {} consecutive state-node exhausts (missing {}); requesting SNAP re-sync",
            blockNum,
            BlockImporter.survivedExhausts,
            missingHashStr
          )
          BlockImporter.survivedExhausts = 0
          pendingStateNodeHash = None
          supervisor ! SyncProtocol.RegularSyncStuck(blockNum, missingHashStr)
          // Don't transition further — SyncController will PoisonPill regular sync.
          Behaviors.same
        else
          log.error(
            "State node recovery failed after max retries for block {} (consecutive exhausts: {}/{}) — backing off {}s before retry",
            blockNum,
            BlockImporter.survivedExhausts,
            BlockImporter.StuckEscapeThreshold,
            5.minutes.toSeconds
          )
          fetcher ! BlockFetcher.InvalidateBlocksFrom(
            blockNum,
            "state node unrecoverable after max retries",
            shouldBlacklist = false
          )
          // Don't self ! PickBlocks — that would immediately retry the same block.
          timers.startTimerWithFixedDelay(RetryKey, SyncRetryTick, 5.minutes)
          running(state)

      case FetcherResponse(BlockFetcher.FetchedStateNode(nodeData)) =>
        val node = nodeData.values.head
        val hash = kec256(node)
        log.info(
          "Received missing state node {}, saving and retrying block {}",
          ByteStringUtils.hash2string(hash),
          blocksToRetry.head.number
        )
        stateStorage.saveNode(hash, node.toArray, blocksToRetry.head.number.value)
        // Also save as contract code — if this was a code fetch, the hash is the codeHash
        // and the data is the bytecode. EvmCodeStorage is keyed by codeHash, same as the fetch.
        try evmCodeStorage.put(hash, node).commit()
        catch
          case _: Exception => ()
          // Successful state-node delivery — reset stuck-counter so a later transient failure on a
          // different block doesn't escalate to SNAP re-sync prematurely.
        BlockImporter.survivedExhausts = 0
        pendingStateNodeHash = None
        importBlocks(blocksToRetry, blockImportType)(state)

      case SyncRetryTick =>
        log.warning(
          "Timed out waiting for missing state node for block {}, retrying import",
          blocksToRetry.head.number
        )
        // Retry the same blocks directly — don't PickBlocks, which would fetch from wherever the
        // fetcher is now (potentially far beyond the pivot). After SNAP sync, only the pivot header
        // has a number→hash mapping, so branch resolution would fail for any other starting point.
        BlockImporter.survivedExhausts += 1
        importBlocks(blocksToRetry, blockImportType)(state)

      case _ => Behaviors.same
    }

  private def start(): Behavior[Command] =
    log.info("Starting Regular Sync, current best block is {}", bestKnownBlockNumber)
    fetcher ! BlockFetcher.Start(selfRef, bestKnownBlockNumber)
    supervisor ! ProgressProtocol.StartingFrom(bestKnownBlockNumber)
    running(ImporterState.initial)

  private def nextBehavior(
      newBehavior: NewBehavior,
      blockImportType: BlockImportType,
      state: ImporterState
  ): Behavior[Command] =
    newBehavior match
      case Running =>
        timers.startTimerWithFixedDelay(RetryKey, SyncRetryTick, syncConfig.syncRetryInterval)
        running(state)
      case ResolvingMissingNode(blocksToRetry) =>
        timers.startTimerWithFixedDelay(RetryKey, SyncRetryTick, 30.seconds)
        resolvingMissingNode(blocksToRetry, blockImportType)(state)
      case ResolvingBranch(from) =>
        timers.startTimerWithFixedDelay(RetryKey, SyncRetryTick, syncConfig.syncRetryInterval)
        running(state.resolvingBranch(from))

  private def pickBlocks(state: ImporterState): Unit =
    val msg = state.resolvingBranchFrom.fold[BlockFetcher.FetchCommand](
      BlockFetcher.PickBlocks(syncConfig.blocksBatchSize, fetcherResponseAdapter)
    )(from => BlockFetcher.StrictPickBlocks(from, bestKnownBlockNumber, fetcherResponseAdapter))

    fetcher ! msg

  private def importBlocks(blocks: NonEmptyList[Block], blockImportType: BlockImportType)(
      state: ImporterState
  ): Behavior[Command] = importWith(
    IO
      .pure {
        log.debug(
          "Attempting to import blocks starting from {} and ending with {}",
          blocks.head.number,
          blocks.last.number
        )
        resolveBranch(blocks)
      }
      .flatMap {
        case Right(blocksToImport) => handleBlocksImport(blocksToImport)
        case Left(resolvingFrom)   => IO.pure(ResolvingBranch(resolvingFrom))
      },
    blockImportType
  )(state)

  private def handleBlocksImport(blocks: List[Block]): IO[NewBehavior] =
    tryImportBlocks(blocks)
      .map { value =>
        val (importedBlocks, errorOpt) = value
        importedBlocks.size match
          case 0 => log.debug("Imported no blocks")
          case 1 =>
            val b = importedBlocks.head
            log.info(
              "Imported block {} ({}) txs={} gas={}",
              b.number,
              b.header.hashAsHexString.take(10),
              b.body.transactionList.size,
              b.header.gasUsed
            )
          case _ => log.info("Imported blocks {} - {}", importedBlocks.last.number, importedBlocks.head.number)

        errorOpt match
          case None => Running
          case Some(err) =>
            log.error("Block import error {}", err)
            val notImportedBlocks = blocks.drop(importedBlocks.size)

            err match
              case e: MissingAccountNodeException =>
                val failedBlock = notImportedBlocks.head
                val parentStateRoot =
                  try
                    Option(blockchainReader.getBlockHeaderByHash(failedBlock.header.parentHash)).flatten
                      .map(_.stateRoot.value)
                  catch
                    case ex: Exception =>
                      log.warning("Failed to get parent state root during node recovery: {}", ex.getMessage); None
                val accountHash = kec256(e.accountAddress)
                val paths: Option[Seq[Seq[ByteString]]] = e.location
                  .map { loc =>
                    Seq(Seq(loc))
                  }
                  .orElse {
                    val nibbles = accountHash.toArray.flatMap(b => Array(((b >> 4) & 0xf).toByte, (b & 0xf).toByte))
                    Some((1 to 16).map { depth =>
                      Seq(ByteString(HexPrefix.encode(nibbles.take(depth), isLeaf = false)))
                    })
                  }
                log.info(
                  "Missing account trie node {} for account {} during import of block {}, locationKnown={}",
                  ByteStringUtils.hash2string(e.hash),
                  ByteStringUtils.hash2string(e.accountAddress),
                  failedBlock.number,
                  e.location.isDefined
                )
                pendingStateNodeHash = Some(e.hash)
                fetcher ! BlockFetcher.FetchStateNode(e.hash, fetcherResponseAdapter, parentStateRoot, paths)
                ResolvingMissingNode(NonEmptyList(notImportedBlocks.head, notImportedBlocks.tail))
              case e: MissingStorageNodeException =>
                val failedBlock = notImportedBlocks.head
                val parentStateRoot =
                  try
                    Option(blockchainReader.getBlockHeaderByHash(failedBlock.header.parentHash)).flatten
                      .map(_.stateRoot.value)
                  catch
                    case ex: Exception =>
                      log.warning("Failed to get parent state root during node recovery: {}", ex.getMessage); None
                val accountHash = kec256(e.accountAddress)
                val paths: Option[Seq[Seq[ByteString]]] = e.location
                  .map { loc =>
                    Seq(Seq(accountHash, loc))
                  }
                  .orElse {
                    val emptyStoragePath = ByteString(HexPrefix.encode(Array.empty[Byte], isLeaf = false))
                    Some(Seq(Seq(accountHash, emptyStoragePath)))
                  }
                log.info(
                  "Missing storage node {} for account {} during import of block {}, locationKnown={}",
                  ByteStringUtils.hash2string(e.hash),
                  ByteStringUtils.hash2string(e.accountAddress),
                  failedBlock.number,
                  e.location.isDefined
                )
                pendingStateNodeHash = Some(e.hash)
                fetcher ! BlockFetcher.FetchStateNode(e.hash, fetcherResponseAdapter, parentStateRoot, paths)
                ResolvingMissingNode(NonEmptyList(notImportedBlocks.head, notImportedBlocks.tail))
              case e: MissingNodeException =>
                val failedBlock = notImportedBlocks.head
                val parentStateRoot =
                  try
                    Option(blockchainReader.getBlockHeaderByHash(failedBlock.header.parentHash)).flatten
                      .map(_.stateRoot.value)
                  catch
                    case ex: Exception =>
                      log.warning("Failed to get parent state root during node recovery: {}", ex.getMessage); None
                val paths: Option[Seq[Seq[ByteString]]] = e.location.map(loc => Seq(Seq(loc)))
                log.info(
                  "Missing state node {} during import of block {}, locationKnown={}",
                  ByteStringUtils.hash2string(e.hash),
                  failedBlock.number,
                  e.location.isDefined
                )
                pendingStateNodeHash = Some(e.hash)
                fetcher ! BlockFetcher.FetchStateNode(e.hash, fetcherResponseAdapter, parentStateRoot, paths)
                ResolvingMissingNode(NonEmptyList(notImportedBlocks.head, notImportedBlocks.tail))
              case _ if err.toString.contains("Block has invalid gas used") =>
                // Gas mismatch after execution — likely missing contract code from
                // incomplete fast sync state. The EVM treated a contract as an EOA
                // because its code wasn't in EvmCodeStorage.
                val failedBlock = notImportedBlocks.head
                findMissingContractCode(failedBlock) match
                  case Some(codeHash) =>
                    log.warning(
                      "Gas mismatch on block {} — missing contract code {}. Fetching via SNAP GetByteCodes.",
                      failedBlock.number,
                      ByteStringUtils.hash2string(codeHash)
                    )
                    val parentStateRoot =
                      try
                        Option(blockchainReader.getBlockHeaderByHash(failedBlock.header.parentHash)).flatten
                          .map(_.stateRoot.value)
                      catch case _: Exception => None
                    pendingStateNodeHash = Some(codeHash)
                    // isByteCode=true routes the fetch through SNAP GetByteCodes (works on ETH68+)
                    // instead of the legacy GetNodeData path (which has no peers on modern networks).
                    fetcher ! BlockFetcher.FetchStateNode(
                      codeHash,
                      fetcherResponseAdapter,
                      parentStateRoot,
                      paths = None,
                      isByteCode = true
                    )
                    ResolvingMissingNode(NonEmptyList(failedBlock, notImportedBlocks.tail))
                  case None =>
                    log.error("Gas mismatch on block {} but no missing contract code found", failedBlock.number)
                    // This arm — and ONLY this arm — is where a gas-used mismatch is proven to be a real consensus
                    // failure rather than a missing-bytecode artifact. `InMemoryWorldStateProxy.getCode` returns
                    // ByteString.empty instead of throwing when code is absent, so a partially-synced node
                    // under-counts gas on an honest block and lands in the sibling `Some(codeHash)` arm above, which
                    // fetches the code over SNAP and retries. Having positively excluded that reading, tell the
                    // Engine API so newPayload/forkchoiceUpdated can answer INVALID for this block and its
                    // descendants. latestValidHash = the failing block's parent: execution proceeds in order and
                    // stops at the first failure, so the parent is the last block we validated.
                    // No-op unless the Engine API is enabled (ETC/Mordor/Gorgoroth never bind a reporter).
                    val latestValidHash = failedBlock.header.parentHash.value
                    consensus.reportInvalidChain(failedBlock.hash.value, latestValidHash)
                    // Same reasoning as ConsensusImpl.reportIfProvenInvalid: the blocks queued behind the failing
                    // one were never executed, and the unbroken parentHash-linked prefix of them is provably
                    // invalid-by-descent with the same latestValidHash. Without this the verdict stops at
                    // `failedBlock` and a CL-supplied tip two or more hops above it stays ACCEPTED forever.
                    InvalidChainReporter.provenDescendants(failedBlock, notImportedBlocks.tail).foreach { d =>
                      log.warning(
                        "Block {} descends from invalid block {} — reporting as consensus-invalid",
                        d.number,
                        failedBlock.number
                      )
                      consensus.reportInvalidChain(d.hash.value, latestValidHash)
                    }
                    val invalidBlockNr = failedBlock.number.value
                    fetcher ! BlockFetcher.InvalidateBlocksFrom(invalidBlockNr, err.toString)
                    Running
              case _ =>
                val invalidBlockNr = notImportedBlocks.head.number.value
                fetcher ! BlockFetcher.InvalidateBlocksFrom(invalidBlockNr, err.toString)
                Running
      }

  private def tryImportBlocks(
      blocks: List[Block],
      importedBlocks: List[Block] = Nil
  ): IO[
    (List[Block], Option[Any])
  ] = // Any: union of MissingNodeException subtypes | BlockImportFailed | UnknownParent — no shared supertype
    NonEmptyList.fromList(blocks) match
      case None =>
        importedBlocks.headOption.foreach(block =>
          supervisor ! ProgressProtocol.ImportedBlock(block.number.value, internally = false)
        )
        IO.pure((importedBlocks, None))
      case Some(nel) =>
        consensus.evaluateBranch(nel).flatMap {
          case BlockImportedToTop(blockImportData) =>
            val importedNow = blockImportData.map(_.block)
            importedNow.foreach(b => unknownParentStrikes -= b.hash.value)
            val imported = importedNow.reverse ::: importedBlocks
            imported.headOption
              .foreach(b => supervisor ! ProgressProtocol.ImportedBlock(b.number.value, internally = false))
            IO.pure((imported, None))

          case ChainReorganised(_, newBranch, _) =>
            newBranch.foreach(b => unknownParentStrikes -= b.hash.value)
            val imported = newBranch.reverse ::: importedBlocks
            imported.headOption
              .foreach(b => supervisor ! ProgressProtocol.ImportedBlock(b.number.value, internally = false))
            IO.pure((imported, None))

          case DuplicateBlock | BlockEnqueued =>
            IO.pure((importedBlocks, None))

          case BlockImportFailedDueToMissingNode(missingNodeException) if syncConfig.redownloadMissingStateNodes =>
            IO.pure((importedBlocks, Some(missingNodeException)))

          case BlockImportFailedDueToMissingNode(missingNodeException) =>
            IO.raiseError(missingNodeException)

          case err @ (UnknownParent | BlockImportFailed(_)) =>
            val failedBlock = nel.head
            log.error(
              "Block {} batch import failed, hash {} parent {}",
              failedBlock.number,
              failedBlock.header.hashAsHexString,
              ByteStringUtils.hash2string(failedBlock.header.parentHash.value)
            )
            val strikes = unknownParentStrikes.getOrElse(failedBlock.hash.value, 0) + 1
            unknownParentStrikes = unknownParentStrikes + (failedBlock.hash.value -> strikes)
            if strikes == BadBlockEvictionThreshold then
              log.warning(
                "BAD-BLOCK-EVICT: block {} (hash={}) import failure x{} — evicting peer",
                failedBlock.number,
                failedBlock.header.hashAsHexString,
                strikes
              )
              fetcher ! BlockFetcher.BlockImportFailed(
                failedBlock.number.value,
                BlacklistReason
                  .BlockImportError(s"import failure x$strikes on block ${failedBlock.header.hashAsHexString}")
              )
            if strikes >= ForkDetectThreshold then
              val ourHashAtHeight = blockchainReader
                .getBlockHeaderByNumber(failedBlock.number.value)
                .map(h => ByteStringUtils.hash2string(h.hash.value))
                .getOrElse("<not found>")
              log.warning(
                s"FORK-DETECT: block ${failedBlock.number} (hash=${failedBlock.header.hashAsHexString}) " +
                  s"has failed $strikes consecutive times. " +
                  s"Our canonical hash at height ${failedBlock.number}: $ourHashAtHeight. " +
                  s"Received parent hash: ${ByteStringUtils.hash2string(failedBlock.header.parentHash.value)}. " +
                  "Triggering chain rollback and header re-sync."
              )
              selfRef ! StartForkRecovery(failedBlock.number.value)
            IO.pure((importedBlocks, Some(err)))
        }

  private def importBlock(
      block: Block,
      importMessages: ImportMessages,
      blockImportType: BlockImportType,
      informFetcherOnFail: Boolean,
      internally: Boolean
  )(state: ImporterState): Behavior[Command] =
    def doLog(entry: ImportMessages.LogEntry): Unit = log.log(entry._1, entry._2)
    importWith(
      IO(doLog(importMessages.preImport()))
        .flatMap(_ => consensus.evaluateBranchBlock(block))
        .tap(importMessages.messageForImportResult.andThen(doLog))
        .tap {
          case BlockImportedToTop(importedBlocksData) =>
            val (blocks, weights) = importedBlocksData.map(data => (data.block, data.weight)).unzip
            broadcastBlocks(blocks, weights)
            updateTxPool(importedBlocksData.map(_.block), Seq.empty)
            blocks.foreach(b => blockTopic ! Topic.Publish(NewBlockImported(b)))
            supervisor ! ProgressProtocol.ImportedBlock(block.number.value, internally)
          case ChainReorganised(oldBranch, newBranch, weights) =>
            updateTxPool(newBranch, oldBranch)
            broadcastBlocks(newBranch, weights)
            newBranch.foreach(b => blockTopic ! Topic.Publish(NewBlockImported(b)))
            newBranch.lastOption.foreach(block =>
              supervisor ! ProgressProtocol.ImportedBlock(block.number.value, internally)
            )
          case BlockImportFailedDueToMissingNode(missingNodeException) if syncConfig.redownloadMissingStateNodes =>
            // state node re-download will be handled when downloading headers
            doLog(importMessages.missingStateNode(missingNodeException))
          case BlockImportFailedDueToMissingNode(missingNodeException) =>
            IO.raiseError(missingNodeException)
          case BlockImportFailed(error) if informFetcherOnFail =>
            fetcher ! BlockFetcher.BlockImportFailed(block.number.value, BlacklistReason.BlockImportError(error))
          case BlockEnqueued | DuplicateBlock | UnknownParent | BlockImportFailed(_) => ()
        }
        .map(_ => Running),
      blockImportType
    )(state)

  private def broadcastBlocks(blocks: List[Block], weights: List[ChainWeight]): Unit =
    val newBlocks = (blocks, weights).mapN(BlockToBroadcast.apply)
    broadcaster ! BroadcastBlocks(newBlocks)

  private def updateTxPool(blocksAdded: Seq[Block], blocksRemoved: Seq[Block]): Unit =
    blocksRemoved.foreach(block => pendingTransactionsManager ! AddUncheckedTransactions(block.body.transactionList))
    blocksAdded.foreach(block => pendingTransactionsManager ! RemoveTransactions(block.body.transactionList))

  private def importWith(importTask: IO[NewBehavior], blockImportType: BlockImportType)(
      state: ImporterState
  ): Behavior[Command] =
    val ref = selfRef
    importTask
      .map(nb => ref ! ImportDone(nb, blockImportType))
      .handleError { ex =>
        log.error(ex, "Block import failed unexpectedly: {}", ex.getMessage)
        ref ! ImportDone(Running, blockImportType)
      }
      .timed
      .map { case (timeTaken, _) => blockImportType.recordMetric(timeTaken.toNanos) }
      .unsafeRunAndForget()
    running(state.importingBlocks())

  // Either block from which we try resolve branch or list of blocks to be imported
  private def resolveBranch(blocks: NonEmptyList[Block]): Either[BigInt, List[Block]] =
    branchResolution.resolveBranch(blocks.map(_.header)) match
      case NewBetterBranch(oldBranch) =>
        val depth = oldBranch.size
        if depth > 0 then
          log.info(
            "Chain reorg: evicting {} minority-fork blocks, importing {} canonical (depth={})",
            depth,
            blocks.size,
            depth
          )
          RegularSyncMetrics.incrementReorgTotal()
          RegularSyncMetrics.setLastReorgDepth(depth)
        val transactionsToAdd = oldBranch.flatMap(_.body.transactionList)
        pendingTransactionsManager ! PendingTransactionsManager.AddUncheckedTransactions(transactionsToAdd)
        // Add first block from branch as an ommer
        oldBranch.headOption.map(_.header).foreach(ommersPool ! AddOmmers(_))
        Right(blocks.toList)
      case NoChainSwitch =>
        // Add first block from branch as an ommer
        ommersPool ! AddOmmers(blocks.head.header)
        Right(Nil)
      case UnknownBranch =>
        val currentBlock = blocks.head.number.value.min(bestKnownBlockNumber)
        // Use the stored SNAP sync pivot as the reorg floor, matching go-ethereum's
        // ReadLastPivotNumber() pattern (core/rawdb/accessors_chain.go). The pivot is
        // written once during SNAP sync and never cleared; reorgs above the pivot are
        // safe (state exists for all blocks above it). getOrElse(0) covers non-SNAP nodes,
        // giving genesis as the floor — the same fallback geth uses.
        val floor = blockchainReader.getSnapSyncPivotBlock.getOrElse(BigInt(0))
        val goingBackTo = (currentBlock - syncConfig.branchResolutionRequestSize).max(floor)
        if goingBackTo >= currentBlock then
          // At the pivot floor after SNAP sync — skip branch resolution and import directly.
          // After SNAP sync only the pivot header exists, so branch resolution can never
          // find a known parent below the pivot. The blocks ARE valid (they continue from
          // the SNAP-validated pivot). Filter blocks to only those at or above the pivot.
          val validBlocks = blocks.filter(_.number.value > floor)
          if validBlocks.nonEmpty then
            log.info(s"Branch resolution at SNAP pivot floor ($floor), importing ${validBlocks.size} blocks directly")
            Right(validBlocks)
          else
            log.warning(s"Branch resolution hit floor at block $floor, no importable blocks in batch")
            fetcher ! BlockFetcher.InvalidateBlocksFrom(floor + 1, "branch resolution floor", shouldBlacklist = false)
            Left(floor + 1)
        else
          val msg = s"Unknown branch, going back to block nr $goingBackTo in order to resolve branches"
          log.warning(msg)
          RegularSyncMetrics.incrementBranchResolutionRounds()
          fetcher ! BlockFetcher.InvalidateBlocksFrom(goingBackTo, msg, shouldBlacklist = false)
          Left(goingBackTo)
      case InvalidBranch =>
        val goingBackTo = blocks.head.number.value
        val msg = s"Invalid branch, going back to $goingBackTo"
        log.warning(msg)
        fetcher ! BlockFetcher.InvalidateBlocksFrom(goingBackTo, msg)
        Right(Nil)

  /** Check if a block's transactions touch any contract whose code is missing from local storage. Returns the codeHash
    * of the first missing contract found.
    */
  private def findMissingContractCode(block: Block): Option[ByteString] =
    // Use the parent block number — execution starts from the parent's state root.
    // The failing block hasn't been imported yet, so its state root doesn't exist locally.
    val parentBlockNumber = block.header.number - 1
    block.body.transactionList.iterator
      .flatMap { stx =>
        stx.tx.receivingAddress.flatMap { address =>
          try
            // Look up the account directly via blockchainReader
            blockchainReader
              .getAccount(blockchainReader.getBestBranch, address, parentBlockNumber.value)
              .flatMap { account =>
                if account.codeHash != Account.EmptyCodeHash then
                  evmCodeStorage.get(account.codeHash.value) match
                    case None =>
                      log.info(
                        "Found missing code for contract {} (codeHash={})",
                        address,
                        ByteStringUtils.hash2string(account.codeHash.value)
                      )
                      Some(account.codeHash.value)
                    case Some(_) => None
                else None
              }
          catch
            case ex: Exception =>
              log.warning(
                "Failed to check contract code for {} at block {}: {}",
                address,
                parentBlockNumber,
                ex.getMessage
              )
              None
        }
      }
      .nextOption()

  private def handleForkRecovery(failedBlockNumber: BigInt, state: ImporterState): Behavior[Command] =
    val capturedBest = bestKnownBlockNumber
    val snapPivot = blockchainReader.getSnapSyncPivotBlock.getOrElse(BigInt(0))
    log.warning(
      "SYNC-FORK: repeated UnknownParent on block {} — spawning branch resolver (best={}, snapPivot={})",
      failedBlockNumber,
      capturedBest,
      snapPivot
    )
    val resolverRef = ctx.spawnAnonymous(
      FastSyncBranchResolverActor(
        replyTo = branchResolverAdapter,
        peerEventBus = peerEventBus,
        networkPeerManager = networkPeerManager,
        blockchain = blockchain,
        blockchainReader = blockchainReader,
        blacklist = blacklist,
        syncConfig = syncConfig
      )
    )
    resolverRef ! FastSyncBranchResolverActor.StartBranchResolver
    resolvingFork(capturedBest, snapPivot, state)

  private def resolvingFork(capturedBest: BigInt, snapPivot: BigInt, state: ImporterState): Behavior[Command] =
    Behaviors.receiveMessage {
      case BranchResolverMsg(FastSyncBranchResolverActor.BranchResolvedSuccessful(lca, _)) =>
        blockchainReader.getBlockHeaderByNumber(lca) match
          case Some(lcaHeader) =>
            log.info(
              "SYNC-FORK: branch resolver found LCA at {} — rewinding canonical chain from {}",
              lca,
              capturedBest
            )
            blockchainWriter.setCanonicalChainHead(lca, lcaHeader.hash, capturedBest)
            unknownParentStrikes = Map.empty
            fetcher ! BlockFetcher.InvalidateBlocksFrom(
              lca + 1,
              "SYNC-FORK branch-resolver rollback",
              shouldBlacklist = false
            )
          case None =>
            log.warning("SYNC-FORK: no header at resolver LCA {} — falling back to blind rewind", lca)
            blindRewind(capturedBest, snapPivot)
        running(state)

      case BranchResolverMsg(FastSyncBranchResolverActor.BranchResolutionFailed(_)) =>
        log.warning("SYNC-FORK: branch resolver failed — falling back to 128-block blind rewind")
        blindRewind(capturedBest, snapPivot)
        running(state)

      case _ => Behaviors.same
    }

  private def blindRewind(capturedBest: BigInt, snapPivot: BigInt): Unit =
    val floor = (capturedBest - MaxForkAncestryDepth).max(snapPivot)
    blockchainReader.getBlockHeaderByNumber(floor) match
      case Some(floorHeader) =>
        log.warning(
          "SYNC-FORK: blind rewind from {} to {} (snapPivot={})",
          capturedBest,
          floor,
          snapPivot
        )
        blockchainWriter.setCanonicalChainHead(floor, floorHeader.hash, capturedBest)
        unknownParentStrikes = Map.empty
        fetcher ! BlockFetcher.InvalidateBlocksFrom(floor + 1, "SYNC-FORK rollback", shouldBlacklist = false)
      case None =>
        log.warning(
          "SYNC-FORK: no header at fork recovery floor bno={} — escalating to SNAP re-sync",
          floor
        )
        supervisor ! SyncProtocol.RegularSyncStuck(floor, s"no header at fork recovery floor $floor")

  private def bestKnownBlockNumber: BigInt = blockchainReader.getBestBlockNumber

// Logger name anchor — never instantiated
final private class BlockImporterImpl
