package com.chipprbots.ethereum.blockchain.sync.regular

import org.apache.pekko.actor.typed.ActorRef as TypedActorRef
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.PostStop
import org.apache.pekko.actor.typed.pubsub.Topic
import org.apache.pekko.actor.typed.scaladsl.ActorContext
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.scaladsl.TimerScheduler
import org.apache.pekko.event.Logging
import org.apache.pekko.event.LoggingAdapter
import org.apache.pekko.util.ByteString

import cats.data.NonEmptyList
import cats.effect.IO
import cats.effect.unsafe.IORuntime

import scala.compiletime.asMatchable
import scala.concurrent.duration.*
import scala.util.Failure
import scala.util.Success
import scala.util.Try
import scala.util.control.NonFatal

import com.chipprbots.ethereum.blockchain.sync.Blacklist
import com.chipprbots.ethereum.blockchain.sync.Blacklist.BlacklistReason
import com.chipprbots.ethereum.blockchain.sync.PeersClient
import com.chipprbots.ethereum.blockchain.sync.SyncController
import com.chipprbots.ethereum.blockchain.sync.SyncProtocol
import com.chipprbots.ethereum.blockchain.sync.fast.FastSyncBranchResolverActor
import com.chipprbots.ethereum.blockchain.sync.regular.BlockBroadcast.BlockToBroadcast
import com.chipprbots.ethereum.blockchain.sync.regular.BlockBroadcasterActor.BroadcastBlocks
import com.chipprbots.ethereum.blockchain.sync.regular.BlockImporter.Command
import com.chipprbots.ethereum.blockchain.sync.regular.RegularSync.ProgressProtocol
import com.chipprbots.ethereum.blockchain.sync.snap.heal.StorageHolePosition
import com.chipprbots.ethereum.consensus.Consensus
import com.chipprbots.ethereum.consensus.ConsensusAdapter
import com.chipprbots.ethereum.consensus.ReorgStateHandler
import com.chipprbots.ethereum.consensus.engine.InvalidChainReporter
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.db.storage.BadEntryDelete
import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.db.storage.StateStorage
import com.chipprbots.ethereum.db.storage.pathstate.HandedToRecovery
import com.chipprbots.ethereum.db.storage.pathstate.RefusedForThisHead
import com.chipprbots.ethereum.db.storage.pathstate.ReorgRefusal
import com.chipprbots.ethereum.db.storage.pathstate.ReorgTooDeep
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.domain.BlockchainWriter
import com.chipprbots.ethereum.jsonrpc.NewBlockImported
import com.chipprbots.ethereum.ledger.*
import com.chipprbots.ethereum.mpt.*
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.MissingAccountNodeException
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.MissingCodeException
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.MissingNodeException
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.MissingStorageNodeException
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.PeerEventBusActor.Command as PeerEventBusCommand
import com.chipprbots.ethereum.network.PeerId
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.NodeData
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
  // A fetch that runs exhausts after about 160 s (StateNodeFetcher's 30 tries, 5 s apart plus each request's own short
  // backoff), and a 5-minute backoff follows each exhaust: about 18 minutes to the escape valve. An import that asks no
  // peer (a storage hole it cannot place, or a local state fault, spec 022 T062) is exhausted at once, so the escape
  // comes after the two backoffs alone: about 10 minutes. Only exhausts count (see SyncRetryTick below), and a block
  // imported clears them.
  val StuckEscapeThreshold: Int = 3

  // From this many faults of this node's own state in a row on one block, the block's tries are spaced out by
  // exhaustBackoff. Nothing is escalated: what the node does when the fault stays is a later slice's (spec 018, C4b).
  val StateFaultBackoffAfter: Int = 3

  // How often a block waiting on a missing node is imported again. A variable only so a spec can shorten it.
  private[regular] var resolvingRetryInterval: FiniteDuration = 30.seconds

  // How long a block waits after an exhausted fetch before it is tried again. A variable only so a spec can shorten it.
  private[regular] var exhaustBackoff: FiniteDuration = 5.minutes

  // How often an importer still waiting for the import lock to start says so (beacon, #1705). A variable only so a spec
  // can shorten it.
  private[regular] var lockWaitWarnInterval: FiniteDuration = 30.seconds
  private[regular] case object LockWaitTick extends Command
  private[regular] val LockWaitKey = "BlockImporterLockWait"

  // How far back to rewind the canonical chain index during fork recovery (SYNC-FORK path).
  // 128 blocks provides enough depth to cover common shallow forks without resyncing the
  // entire chain; deeper forks fall back to SNAP re-sync via RegularSyncStuck.
  // On the unified state layout nothing is rewound (spec 018 C4e part 6): it is how far back the fetcher is sent when
  // no common block was found.
  val MaxForkAncestryDepth: Int = 128

  /** Consecutive exhausted state-node fetches, towards [[StuckEscapeThreshold]]. One per regular-sync session:
    * RegularSync creates it and hands it to every importer it spawns, so a pipeline started again in place of a stopped
    * one keeps the count (#1705: it was a global zeroed by every importer's setup, a replacement's included). Zeroed by
    * a node delivered, a block imported, and the escalation itself. Atomic: an import resets it off the actor's thread.
    */
  final class ExhaustCount:
    private val count = new java.util.concurrent.atomic.AtomicInteger(0)
    def get: Int = count.get
    def increment(): Int = count.incrementAndGet()
    def reset(): Unit = count.set(0)

  // B-007 (#1683): how many blocks in a row a peer may announce (NewBlock) whose parent never arrives, before it is
  // blacklisted for it. Below this, a block whose parent this node lacks is a gap of ours and nobody is blamed.
  val OrphanAnnounceStrikeThreshold: Int = 5

  // Peers whose orphan announcements are counted at once. Past it the count starts over (see OrphanAnnounceStrikes).
  private val MaxTrackedAnnouncers: Int = 256

  /** Orphan announcements counted per peer, since the last block of that peer that imported. Bounded: past `capacity`
    * peers it starts over, so identities made up to fill it cost a strike count and nothing more. Thread-safe: an
    * import clears it off the actor's thread.
    */
  final private[regular] class OrphanAnnounceStrikes(capacity: Int = MaxTrackedAnnouncers):
    private val strikes = new java.util.concurrent.ConcurrentHashMap[PeerId, Int]()

    /** One more orphan announcement by `peer`: how many it has made in a row. */
    def strike(peer: PeerId): Int =
      if strikes.size >= capacity && !strikes.containsKey(peer) then strikes.clear()
      strikes.merge(peer, 1, (a: Int, b: Int) => a + b)

    def clear(peer: PeerId): Unit = strikes.remove(peer): Unit

    def size: Int = strikes.size

  // forge, B-007 (N1): how many times in a row the pivot floor may send the fetcher back to the same block, because what
  // is fetched above the best block never continues from it, before that is warned about and counted.
  val PivotFloorGapWarnAfter: Int = 3

  /** The block the pivot floor last sent the fetcher back to, and how many times in a row it was that one. */
  final private[regular] class FloorGapRewinds:
    private var last: Option[BigInt] = None
    private var count: Int = 0

    /** One more rewind to `from`: how many in a row have gone to that block. */
    def record(from: BigInt): Int = synchronized {
      count = if last.contains(from) then count + 1 else 1
      last = Some(from)
      count
    }

    def reset(): Unit = synchronized {
      last = None
      count = 0
    }

  // Distinct missing codes in a process before one bulk bytecode recovery is asked for. A miss is fetched alone first, at a
  // round trip and a re-execution of the block, because the bulk scan stops regular sync for as long as it takes to read the
  // whole state: one missing contract on Sepolia, where that is estimated at hours, would hold the head for all of it, where
  // a fetch took 100 ms (#1694, the user's decision of 2026-11-01). Several show there are many to find, as on a datadir from
  // before healing waited on code, and fetching those one by one is what costs more.
  val BulkCodeRecoveryAfterMisses: Int = 8

  // Requested at most once per process: if the scan cannot find the code (an account outside the SNAP trie, or peers that
  // cannot serve it), the next MissingCodeException falls back to fetching that hash alone instead of looping through
  // recovery forever. NOT zeroed in apply(): the importer is re-created when regular sync restarts after the recovery, and
  // zeroing there would be that loop. The same goes for the count of misses, or a restart would start it over.
  private val bulkCodeRecoveryClaimed = new java.util.concurrent.atomic.AtomicBoolean(false)
  private val missingCodes = java.util.concurrent.ConcurrentHashMap.newKeySet[ByteString]()

  /** Counts `codeHash`, and is true for the caller whose miss takes the count to the threshold, once a process. A hash
    * met again counts once. Bounded: past the threshold nothing more is kept.
    */
  private[regular] def claimBulkCodeRecovery(codeHash: ByteString): Boolean =
    if missingCodes.size < BulkCodeRecoveryAfterMisses then missingCodes.add(codeHash): Unit
    missingCodes.size >= BulkCodeRecoveryAfterMisses && bulkCodeRecoveryClaimed.compareAndSet(false, true)

  private[regular] def resetBulkCodeRecoveryForTests(): Unit =
    bulkCodeRecoveryClaimed.set(false)
    missingCodes.clear()

  private[regular] def distinctMissingCodesForTests: Int = missingCodes.size

  /** SYNC-FORK rewind of the canonical index to `target`, plus the reference-count undo of the blocks it
    * un-canonicalises. '''The hash layout only''': on the unified state layout the head moves with the state and a fork
    * is judged by weight, so fork recovery moves nothing there (spec 018 C4e part 6).
    *
    * `setCanonicalChainHead` deletes the index entries target+1..currentBest and moves best, but never touches state
    * bookkeeping: those blocks keep their applied `bd` records while no longer canonical, so under basic pruning the
    * replacement branch's execution can prune nodes it still uses (MissingNode). Same undo `ConsensusImpl.settleHead`
    * performs for a reorg (#1471). The hashes must be read BEFORE the rewind removes the index. A block with no record
    * (SNAP-synced) is a no-op; a block later re-executed rewrites its record as applied, so the undo is safe.
    */
  private[regular] def rewindCanonicalChain(
      reader: BlockchainReader,
      writer: BlockchainWriter,
      reorgState: ReorgStateHandler,
      target: BigInt,
      targetHash: BlockHash,
      currentBest: BigInt
  ): Unit =
    val abandoned: Seq[(BigInt, ByteString)] =
      if currentBest > target then
        ((target + 1) to currentBest).flatMap(n => reader.getCanonicalHashByNumber(n).map(h => (n, h.value)))
      else Nil
    writer.setCanonicalChainHead(target, targetHash, currentBest)
    if abandoned.nonEmpty then reorgState.abandonBlockStates(abandoned)

  private[regular] case object SyncRetryTick extends Command
  private[regular] val RetryKey = "BlockImporterRetry"

  // What holds the import lock while a branch out of reach is told to the escalation's feed (B-300 g).
  private[regular] val BranchOutOfReachHolder: String = "the escalation of a branch out of reach"

  /** The most headers of a branch out of reach fetched for the escalation (B-300 g; forge). The fetch stops as soon as
    * they outweigh the head, so this bounds only a branch that never does, or whose weight cannot be read: enough to
    * outweigh any head this node could have reached by following the chain since the block the branch leaves it at,
    * where that block is far below the head (a refusal for depth), without holding millions of headers, and
    * seal-checking them under the import lock while imports wait.
    */
  val MaxBranchHeadersFetched: Int = 8192

  // The name of the one fetch of a branch's headers an importer has at a time (B-300 g).
  private[regular] val BranchHeadersFetchName: String = "branch-headers-fetch"

  // forge, B-300 g: how many times in a row a branch not taken may be refused beyond the fetch at the same block,
  // before that is said at ERROR. The fork recovery it starts can find its common block elsewhere, or none, and the
  // branch is then remembered, weighed and refused again.
  val BeyondTheFetchRepeatsWarnAt: Int = 3

  /** The block a branch not taken was last refused beyond the fetch at, and how many times in a row it was that one. */
  final private[regular] class BeyondTheFetchRefusals:
    private var last: Option[BigInt] = None
    private var count: Int = 0

    /** One more refusal of a branch that leaves this chain at `commonBlock`: how many in a row have left it there. */
    def record(commonBlock: BigInt): Int = synchronized {
      count = if last.contains(commonBlock) then count + 1 else 1
      last = Some(commonBlock)
      count
    }

  // Above this many batches deferred behind one running import (post-merge only), BlockImporter warns: see the
  // deferral arm in `running`.
  private[regular] val DeferredBatchesWarnThreshold: Int = 5

  /** The block the gas-used arm may report as consensus-invalid, with its proven descendants — or `None` to report
    * nothing.
    *
    * Precondition: the caller is on the gas-used arm AND `findMissingContractCode(notImportedBlocks.head)` was `None`.
    *
    * The pre-existing rule was "the failing block is `notImportedBlocks.head`". That holds whenever `importedBlocks`
    * tells the truth about how much of the batch executed, and it does NOT hold after a failed reorganisation: the
    * consensus result collapses to `BlockImportFailed`, `tryImportBlocks` then reports zero imported blocks, and the
    * head is an honest block that executed fine. Reporting it, its descendants and — worst — the genuinely invalid
    * block with the head's parent as latestValidHash is what this fixes. See [[BlockImportFailedAt]].
    *
    * Three outcomes:
    *   - No failing-block hash, or it IS the head: exactly the pre-existing answer, `head` + its proven descendants.
    *   - It names a later block and reporting is live: that block + its proven descendants, but only after the same
    *     missing-bytecode disambiguation has been run on THAT block — the caller's check ran on the head, which proves
    *     nothing about it.
    *   - Otherwise (reporting not live, hash not in the batch, or that block's contract code is missing): `None`.
    *     Refusing is the safe direction; `ConsensusImpl.reportIfProvenInvalid` has already reported every failure the
    *     typed error proves, including a receipts-contradicted gas-used mismatch.
    *
    * ETC/Mordor/Gorgoroth: `reportingLive` is false there, so the second outcome is unreachable and
    * `findMissingContractCode` is never called from here; the first outcome feeds `reportInvalidChain`, a no-op on
    * those chains, exactly as before.
    */
  private[ethereum] def provenGasUsedFailure(
      err: Any,
      notImportedBlocks: List[Block],
      reportingLive: Boolean,
      findMissingContractCode: Block => Option[ByteString]
  ): Option[(Block, List[Block])] =
    notImportedBlocks match
      case Nil => None
      case head :: tail =>
        err.asMatchable match
          case at: BlockImportFailedAt if at.failingBlockHash != head.hash.value =>
            if !reportingLive then None
            else
              notImportedBlocks.dropWhile(_.hash.value != at.failingBlockHash) match
                case failing :: rest if findMissingContractCode(failing).isEmpty =>
                  Some(failing -> InvalidChainReporter.provenDescendants(failing, rest))
                case _ => None
          case _ => Some(head -> InvalidChainReporter.provenDescendants(head, tail))

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
      configBuilder: BlockchainConfigBuilder,
      peersClient: Option[TypedActorRef[PeersClient.Command]] = None,
      reorgState: ReorgStateHandler = ReorgStateHandler.NoOp,
      exhausts: ExhaustCount,
      importLock: ImportLock
  ): Behavior[Command] =
    Behaviors.setup { ctx =>
      Behaviors.withTimers { timers =>
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
          configBuilder,
          peersClient.map(pc => new BlockAccessListFetcher(pc)(using ctx.system.scheduler)),
          reorgState,
          exhausts,
          importLock
        )
        // Started as it is spawned: a separate start message was what a re-spawned importer never got (#1705).
        logic.starting()
      }
    }

  // Mined blocks an importer keeps while it waits for the import lock to start. More than a few would mean a start held
  // far longer than any import takes.
  private[regular] val StartStashCapacity: Int = 16

  sealed trait Command
  case class MinedBlock(block: Block) extends Command
  case class ImportNewBlock(block: Block, peerId: PeerId) extends Command
  case class ImportDone(newBehavior: NewBehavior, blockImportType: BlockImportType) extends Command
  case object PickBlocks extends Command
  case object PrintStatus extends Command

  /** @param beyondTheFetch
    *   the branch not taken that started the recovery, which has become the heavier and reaches further above the block
    *   it leaves this chain at than the fetcher holds (B-300 g). None: a block that failed, as ever.
    */
  case class StartForkRecovery(failedBlockNumber: BigInt, beyondTheFetch: Option[BranchBeyondTheFetch] = None)
      extends Command

  /** A branch that was not taken, and has become the heavier, whose tip is more blocks above the block it leaves this
    * chain at than the fetcher holds ready (B-300 g). It cannot be fetched whole, and no peer is known for it: the fork
    * recovery it starts finds one, and refuses the branch when the common block it finds is `commonBlock`.
    */
  final case class BranchBeyondTheFetch(commonBlock: BigInt, tip: BigInt)
  final private[regular] case class FetcherResponse(r: BlockFetcher.FetchResponse) extends Command
  final private[regular] case class BranchResolverMsg(r: FastSyncBranchResolverActor.BranchResolverResponse)
      extends Command
  final private[regular] case class BranchResolverStopped(resolver: TypedActorRef[FastSyncBranchResolverActor.Command])
      extends Command
  // The best block, read under the import lock as the importer starts; or the failure to read it.
  final private[regular] case class StartFrom(bestBlock: BigInt) extends Command
  final private[regular] case class StartFailed(error: Throwable) extends Command

  sealed trait NewBehavior
  case object Running extends NewBehavior

  /** [[Running]], but the import already sent the fetcher an `InvalidateBlocksFrom`: every batch picked before that is
    * stale, so batches deferred during the import are dropped instead of handed back. Otherwise identical to
    * [[Running]] — and on ETC/Mordor/Gorgoroth, where nothing is ever deferred, identical outright.
    */
  case object RunningAfterFetcherRewind extends NewBehavior

  /** [[RunningAfterFetcherRewind]], with the next pick of blocks put off by [[BlockImporter.exhaustBackoff]]: the same
    * block could not be executed on this node's own state several times running (spec 018 Track C, C4b).
    */
  case object BackingOffAfterStateFault extends NewBehavior

  /** Waiting for a missing node. `alreadyExhausted`: the wait ends as it starts, as an exhausted fetch, because the
    * import asked no peer (a storage node it could not locate), or because a fetch exhausted while that import ran.
    */
  case class ResolvingMissingNode(blocksToRetry: NonEmptyList[Block], alreadyExhausted: Boolean = false)
      extends NewBehavior

  /** How many branches that were not taken are remembered (B-300 f). */
  val BranchesNotTakenKept: Int = 16

  case class ResolvingBranch(from: BigInt, upTo: Option[BigInt] = None) extends NewBehavior

  sealed trait BlockImportType:
    def recordMetric(nanos: Long): Unit

  case object MinedBlockImport extends BlockImportType:
    override def recordMetric(nanos: Long): Unit = RegularSyncMetrics.recordMinedBlockPropagationTimer(nanos)

  case object NewBlockImport extends BlockImportType:
    override def recordMetric(nanos: Long): Unit = RegularSyncMetrics.recordImportNewBlockPropagationTimer(nanos)

  case object DefaultBlockImport extends BlockImportType:
    override def recordMetric(nanos: Long): Unit = RegularSyncMetrics.recordDefaultBlockPropagationTimer(nanos)

  /** @param deferredBatches
    *   batches the fetcher delivered while an import was still running, oldest first. Only ever non-empty on a chain
    *   that follows a consensus layer (see `BranchResolution.followsConsensusLayer`); on ETC/Mordor/Gorgoroth it stays
    *   `Nil` and every picked batch is handled on arrival, exactly as before.
    */
  case class ImporterState(
      importing: Boolean,
      resolvingBranchFrom: Option[BigInt],
      deferredBatches: List[NonEmptyList[Block]] = Nil,
      // The block the fetched branch is to reach before it is weighed, where that is not this node's head: on the
      // unified layout, the block whose import started the recovery (B-300 W4). None: the head, as ever.
      resolvingBranchTo: Option[BigInt] = None
  ):
    def importingBlocks(): ImporterState = copy(importing = true)

    def notImportingBlocks(): ImporterState = copy(importing = false)

    def resolvingBranch(from: BigInt, upTo: Option[BigInt] = None): ImporterState =
      copy(resolvingBranchFrom = Some(from), resolvingBranchTo = upTo)

    def branchResolved(): ImporterState = copy(resolvingBranchFrom = None, resolvingBranchTo = None)

    def isResolvingBranch: Boolean = resolvingBranchFrom.isDefined

    def deferBatch(blocks: NonEmptyList[Block]): ImporterState = copy(deferredBatches = deferredBatches :+ blocks)

    def withoutDeferredBatches(): ImporterState = copy(deferredBatches = Nil)

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
    configBuilder: BlockchainConfigBuilder,
    balFetcher: Option[BlockAccessListFetcher],
    reorgState: ReorgStateHandler,
    exhausts: BlockImporter.ExhaustCount,
    importLock: ImportLock
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
  // An exhausted fetch that arrived while an import ran, for that import's ImportDone to act on (#1694).
  private var exhaustedDuringImport: Boolean = false
  private var unknownParentStrikes: Map[ByteString, Int] = Map.empty

  // The blocks a fork recovery was already started for, on the unified state layout (spec 018 C4e part 6). Nothing is
  // rewound there, so a block that fails for another reason than a fork (it is invalid, or a chain weight is missing)
  // fails again after the recovery: it is recovered for once, and said as what it is after that. Cleared with the
  // strikes when the block is imported.
  private var forkRecoveredFor: Set[ByteString] = Set.empty

  /** The block the import last stopped at on a fault of this node's own state, and how often in a row (spec 018 Track
    * C, C4b): cleared by any block that is imported.
    */
  private var stateFaultStreak: Option[(ByteString, Int)] = None
  // Orphan announcements per peer, in a row (B-007): a peer is blacklisted for them only after a bounded number.
  private val orphanAnnounces = new OrphanAnnounceStrikes
  // How many times in a row the pivot floor sent the fetcher back to the same block (forge, B-007).
  private val floorGapRewinds = new FloorGapRewinds
  // How many times in a row a branch not taken was refused beyond the fetch at the same block (forge, B-300 g).
  private val beyondTheFetchRefusals = new BeyondTheFetchRefusals
  private val BadBlockEvictionThreshold = 3
  private val ForkDetectThreshold = 5

  def running(state: ImporterState): Behavior[Command] =
    Behaviors.receiveMessage {
      case SyncRetryTick =>
        selfRef ! PickBlocks
        Behaviors.same

      case FetcherResponse(BlockFetcher.PickedBlocks(blocks: NonEmptyList[Block @unchecked]))
          if state.importing && branchResolution.followsConsensusLayer =>
        // The fetcher answers every PickBlocks it receives, and SyncRetryTick sends one every sync-retry-interval
        // while idle, so two answers can be in flight when the first import starts. Resolving the second batch NOW
        // would judge it against the head as it was before the running import commits: its parent is not there yet,
        // so it reads as UnknownBranch and rewinds the fetcher 64 blocks (Platåberget #1432: `201..250` executing,
        // `251..252` arrives 95 ms later → "Unknown branch, going back to block nr 136"). Worse, a batch that IS
        // importable against that stale head would EXECUTE concurrently with the running import. Hold it and hand it
        // back, in order, when that import is done. Gated on BranchResolution's PoS gate: a PoW chain handles every
        // picked batch on arrival, as before.
        val queued = state.deferredBatches.size + 1
        log.debug(
          "Picked batch deferred: from={} to={} reason=import-in-flight queued={}",
          blocks.head.number,
          blocks.last.number,
          queued
        )
        // Batches queue here only when several pick requests were answered while one import runs, so more than a
        // handful means imports are far slower than the pick cadence: a backlog worth seeing without DEBUG.
        if queued > DeferredBatchesWarnThreshold then
          log.warning(
            "Picked batches backing up behind a slow import: queued={} from={} to={}",
            queued,
            blocks.head.number,
            blocks.last.number
          )
        running(state.deferBatch(blocks))

      case FetcherResponse(BlockFetcher.PickedBlocks(blocks: NonEmptyList[Block @unchecked])) =>
        SignedTransaction.retrieveSendersInBackGround(blocks.toList.map(_.body))
        importBlocks(blocks, DefaultBlockImport)(state)

      case MinedBlock(block) if !state.importing =>
        importBlock(
          block,
          new MinedBlockImportMessages(block),
          MinedBlockImport,
          informFetcherOnFail = false,
          announcedBy = None,
          internally = true
        )(state)

      case _: MinedBlock => Behaviors.same

      // On the unified state layout a fork recovery moves nothing: the branch is fetched whole, from the common block,
      // and weighed against this node's own (spec 018 C4e part 6). An announced block imported meanwhile ended the
      // resolution as its import ended, and one whose parent this node lacks sent the fetcher off to the announced
      // tip: a fork longer than a block time was never fetched whole (B-300; forge). The block is not lost: it is of
      // the branch being fetched, or of this node's chain, which the peers serve again.
      case ImportNewBlock(block, _) if blockchainWriter.holdsStateInPlace && state.isResolvingBranch =>
        log.debug(
          "Announced block {} is not imported while the branch from block {} is fetched",
          block.number,
          state.resolvingBranchFrom.getOrElse(BigInt(0))
        )
        Behaviors.same

      case ImportNewBlock(block, peerId) if !state.importing =>
        importBlock(
          block,
          new NewBlockImportMessages(block, peerId),
          NewBlockImport,
          informFetcherOnFail = true,
          announcedBy = Some(peerId),
          internally = false
        )(state)

      case _: ImportNewBlock => Behaviors.same

      case ImportDone(reported, importType) =>
        val newBehavior = reported match
          case r: ResolvingMissingNode if exhaustedDuringImport => r.copy(alreadyExhausted = true)
          case other                                            => other
        exhaustedDuringImport = false
        // Always Nil on a PoW chain (nothing is ever deferred there), which leaves every arm below as it was.
        val deferred = state.deferredBatches
        // On the unified layout only a batch's import ends the resolution of a branch: a block imported on its own
        // (mined here) says nothing of the branch that is being fetched (B-300).
        val keepsTheBranch = blockchainWriter.holdsStateInPlace && importType != DefaultBlockImport
        val ended = state.notImportingBlocks().withoutDeferredBatches()
        val newState = if keepsTheBranch then ended else ended.branchResolved()
        newBehavior match
          case Running =>
            // Oldest first, and ahead of the PickBlocks, so the batches reach importBlocks in fetch order. Each is then
            // resolved against the head this import left behind.
            deferred.foreach(blocks => selfRef ! FetcherResponse(BlockFetcher.PickedBlocks(blocks)))
            selfRef ! PickBlocks
          case RunningAfterFetcherRewind =>
            // The import failed and already sent InvalidateBlocksFrom: the fetcher is re-serving from the failing
            // block, so every deferred batch lies beyond a block that was never imported. Handing them back would
            // only resolve as UnknownBranch and rewind the fetcher a second, deeper time.
            if deferred.nonEmpty then
              log.debug("Deferred batches dropped: count={} reason=import-failed-fetcher-rewound", deferred.size)
            selfRef ! PickBlocks
          case BackingOffAfterStateFault =>
            // As after any fetcher rewind, and no pick now: the timer set below brings the next one.
            if deferred.nonEmpty then
              log.debug("Deferred batches dropped: count={} reason=state-fault-backoff", deferred.size)
          case r: ResolvingBranch =>
            // The fetcher is being rewound (InvalidateBlocksFrom already sent): it re-serves these blocks.
            if deferred.nonEmpty then
              log.debug("Deferred batches dropped: count={} reason=fetcher-rewind from={}", deferred.size, r.from)
            log.info(
              "Branch resolution dispatch: StrictPickBlocks from={} bestKnown={}",
              r.from,
              bestKnownBlockNumber
            )
            selfRef ! PickBlocks
          case _ =>
            // ResolvingMissingNode retries its own blocks; that behaviour ignores picked batches, as it always has.
            if deferred.nonEmpty then
              log.debug("Deferred batches dropped: count={} reason=missing-state-node", deferred.size)
        nextBehavior(newBehavior, importType, newState)

      case PickBlocks if !state.importing =>
        pickBlocks(state)
        Behaviors.same

      case PickBlocks => Behaviors.same

      // An exhausted fetch, landing while an import runs: a 30 s retry of the blocked import, most likely. Dropped, it
      // was lost, and the re-import started the fetch again from its first try. So that import's ImportDone ends its
      // wait with it, if it waits on a node again (forge, #1694).
      case FetcherResponse(BlockFetcher.FetchedStateNode(nodeData)) if nodeData.values.isEmpty && state.importing =>
        exhaustedDuringImport = true
        Behaviors.same

      // Late-arriving state node from a previous resolvingMissingNode phase.
      // ReceiveTimeout may have moved us back to running before the fetch completed.
      // Save the node so the next import attempt finds it in storage.
      case FetcherResponse(BlockFetcher.FetchedStateNode(nodeData)) if nodeData.values.nonEmpty =>
        val node = nodeData.values.head
        val hash = kec256(node)
        log.info("Saving late-arriving fetched state node {}", ByteStringUtils.hash2string(hash))
        // repairNode, not saveNode (#1692): a node already there and valid is left as it is. A blind upsert at one
        // reference could lower the count a block commit or the audit had set, and the node would be pruned while live.
        val outcome = stateStorage.repairNode(hash, node.toArray, blockchainReader.getBestBlockNumber)
        log.debug("Late-arriving state node {}: {}", ByteStringUtils.hash2string(hash), outcome)
        // Also save as contract code in case this was a bytecode fetch
        try evmCodeStorage.put(hash, node).commit()
        catch case _: Exception => ()
        Behaviors.same

      case StartForkRecovery(failedBlockNumber, beyondTheFetch) =>
        handleForkRecovery(failedBlockNumber, beyondTheFetch, state)

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
        val consecutiveExhausts = exhausts.increment()
        val missingHashStr = pendingStateNodeHash.map(ByteStringUtils.hash2string).getOrElse("<unknown>")

        if consecutiveExhausts >= BlockImporter.StuckEscapeThreshold then
          // Multiple consecutive exhausts mean peers genuinely don't have our parent state and
          // never will (we're far behind their snap-serve window). The only recovery is to re-pivot
          // via SNAP (SyncController does so even with do-snap-sync off). Reset our local counter so
          // we don't re-fire if SyncController bounces us back to regular sync. Nothing caps how often
          // this cycle repeats (the SNAP↔fast escape hatch that used to was removed with fast sync);
          // each SNAP re-sync starts from a pivot above the block we were stuck on.
          log.error(
            "Regular sync stuck on block {} after {} consecutive state-node exhausts (missing {}); requesting SNAP re-sync",
            blockNum,
            consecutiveExhausts,
            missingHashStr
          )
          exhausts.reset()
          pendingStateNodeHash = None
          supervisor ! SyncProtocol.RegularSyncStuck(blockNum, missingHashStr)
          // Don't transition further — SyncController will PoisonPill regular sync.
          Behaviors.same
        else
          log.error(
            "State node recovery failed after max retries for block {} (consecutive exhausts: {}/{}) — backing off {}s before retry",
            blockNum,
            consecutiveExhausts,
            BlockImporter.StuckEscapeThreshold,
            BlockImporter.exhaustBackoff.toSeconds
          )
          fetcher ! BlockFetcher.InvalidateBlocksFrom(
            blockNum,
            "state node unrecoverable after max retries",
            shouldBlacklist = false
          )
          // Don't self ! PickBlocks — that would immediately retry the same block.
          timers.startTimerWithFixedDelay(RetryKey, SyncRetryTick, BlockImporter.exhaustBackoff)
          running(state)

      case FetcherResponse(BlockFetcher.FetchedStateNode(nodeData)) =>
        val node = nodeData.values.head
        val hash = kec256(node)
        log.info(
          "Received missing state node {}, saving and retrying block {}",
          ByteStringUtils.hash2string(hash),
          blocksToRetry.head.number
        )
        // repairNode, not saveNode (#1692): see the late-arriving case above.
        val outcome = stateStorage.repairNode(hash, node.toArray, blocksToRetry.head.number.value)
        log.debug("Missing state node {}: {}", ByteStringUtils.hash2string(hash), outcome)
        // Also save as contract code — if this was a code fetch, the hash is the codeHash
        // and the data is the bytecode. EvmCodeStorage is keyed by codeHash, same as the fetch.
        try evmCodeStorage.put(hash, node).commit()
        catch
          case _: Exception => ()
          // Successful state-node delivery — reset stuck-counter so a later transient failure on a
          // different block doesn't escalate to SNAP re-sync prematurely.
        exhausts.reset()
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
        // Not an exhaust, so it does not count towards StuckEscapeThreshold: counting it made the first exhaust the
        // third, and a hole no peer served for about 150 s cost a re-SNAP, days on Sepolia (forge, beacon, #1694; the
        // user's decision of 2026-11-02).
        importBlocks(blocksToRetry, blockImportType)(state)

      case _ => Behaviors.same
    }

  /** The first state. Takes the import lock, so an import that a stopped importer left running has ended, and only then
    * reads the best block to start from (#1705, beacon): read before, it could be a head that import was still moving.
    * Until then a mined block is kept, and everything else dropped: no fetcher answers one it has not started.
    */
  def starting(): Behavior[Command] =
    val waitingSince = System.nanoTime()
    ctx.pipeToSelf(importLock.guard(IO.delay(blockchainReader.getBestBlockNumber)).unsafeToFuture()) {
      case Success(bestBlock) => StartFrom(bestBlock)
      case Failure(error)     => StartFailed(error)
    }
    // The wait has no limit, as go-ethereum's `cancelWg.Wait()` has none: starting without the lock would bring #1432
    // back. An import that never ends would hold every later pipeline here while RegularSync reads as running, so the
    // wait is made visible instead: a WARN each interval, and the gauge (beacon, #1705).
    timers.startTimerWithFixedDelay(LockWaitKey, LockWaitTick, lockWaitWarnInterval)
    Behaviors.withStash(StartStashCapacity) { stash =>
      Behaviors
        .receiveMessage[Command] {
          case StartFrom(bestBlock) =>
            timers.cancel(LockWaitKey)
            RegularSyncMetrics.setImportLockWaitSeconds(0)
            stash.unstashAll(start(bestBlock))
          case StartFailed(error) =>
            // Fail loudly: this importer stops, and RegularSync replaces the pipeline.
            throw error
          case LockWaitTick =>
            val waited = (System.nanoTime() - waitingSince).nanos
            RegularSyncMetrics.setImportLockWaitSeconds(waited.toMillis / 1000.0)
            log.warning(
              "Waiting {}s for the import lock, held by {}: regular sync starts once it ends",
              waited.toSeconds,
              importLock.heldBy.getOrElse("an import a stopped importer left running")
            )
            Behaviors.same
          case mined: MinedBlock =>
            if stash.isFull then
              log.warning("Mined block {} dropped: the importer is still starting", mined.block.number)
            else stash.stash(mined)
            Behaviors.same
          case _ => Behaviors.same
        }
        .receiveSignal { case (_, PostStop) =>
          // Stopped while it waited: no longer waiting, whatever the gauge last said.
          RegularSyncMetrics.setImportLockWaitSeconds(0)
          Behaviors.same
        }
    }

  /** Starts the fetcher from `bestBlock`, the block the chain's head was at. */
  private def start(bestBlock: BigInt): Behavior[Command] =
    log.info("Starting Regular Sync, current best block is {}", bestBlock)
    fetcher ! BlockFetcher.Start(selfRef, bestBlock)
    supervisor ! ProgressProtocol.StartingFrom(bestBlock)
    timers.startTimerWithFixedDelay(RetryKey, SyncRetryTick, syncConfig.syncRetryInterval)
    running(ImporterState.initial)

  private def nextBehavior(
      newBehavior: NewBehavior,
      blockImportType: BlockImportType,
      state: ImporterState
  ): Behavior[Command] =
    newBehavior match
      case Running | RunningAfterFetcherRewind =>
        timers.startTimerWithFixedDelay(RetryKey, SyncRetryTick, syncConfig.syncRetryInterval)
        running(state)
      case BackingOffAfterStateFault =>
        timers.startTimerWithFixedDelay(RetryKey, SyncRetryTick, BlockImporter.exhaustBackoff)
        running(state)
      case ResolvingMissingNode(blocksToRetry, alreadyExhausted) =>
        timers.startTimerWithFixedDelay(RetryKey, SyncRetryTick, BlockImporter.resolvingRetryInterval)
        // Sent here, not from the import: there it would reach `running`, which does not wait on a node.
        if alreadyExhausted then selfRef ! FetcherResponse(BlockFetcher.FetchedStateNode(NodeData(Nil)))
        resolvingMissingNode(blocksToRetry, blockImportType)(state)
      case ResolvingBranch(from, upTo) =>
        timers.startTimerWithFixedDelay(RetryKey, SyncRetryTick, syncConfig.syncRetryInterval)
        running(state.resolvingBranch(from, upTo))

  private def pickBlocks(state: ImporterState): Unit =
    val msg = state.resolvingBranchFrom.fold[BlockFetcher.FetchCommand](
      BlockFetcher.PickBlocks(syncConfig.blocksBatchSize, fetcherResponseAdapter)
    ) { from =>
      // A branch that is the heavier and the shorter never reaches this node's head: asked for up to the head, the
      // pick was never answered (B-300 W4; forge). It is asked for up to the block that was seen of it, which was
      // bounded where it was set.
      val upTo = state.resolvingBranchTo.getOrElse(bestKnownBlockNumber)
      BlockFetcher.StrictPickBlocks(from, upTo, fetcherResponseAdapter)
    }

    fetcher ! msg

  private def importBlocks(blocks: NonEmptyList[Block], blockImportType: BlockImportType)(
      state: ImporterState
  ): Behavior[Command] = importWith(
    // Delayed, not pure: `pure` evaluated the branch resolution at once, on the actor's thread and outside the import
    // lock, against a head another import might still be moving; and anything it threw failed this actor (#1705).
    IO
      .delay {
        log.debug(
          "Attempting to import blocks starting from {} and ending with {}",
          blocks.head.number,
          blocks.last.number
        )
        resolveBranch(blocks)
      }
      .flatMap {
        case Right(blocksToImport) => handleBlocksImport(blocksToImport)
        case Left(resolvingFrom)   => IO.delay(ResolvingBranch(resolvingFrom, takeBranchUpTo()))
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
        // The chain moved, so exhausts counted before were met at a block now behind it, and a hole the audit filled
        // no longer counts towards a re-SNAP (forge, #1694).
        if importedBlocks.nonEmpty then
          exhausts.reset()
          floorGapRewinds.reset()
          stateFaultStreak = None

        errorOpt match
          case None      => Running
          case Some(err) =>
            // An orphan batch is expected on a reorging chain and right after SNAP hand-off: a warning, not an error.
            if err == UnknownParent then log.warning("Block import: the batch hangs from a block this node lacks")
            // a fault of this node's own state is said by its own arm below, once, and is no error of the import
            else if !err.isInstanceOf[BlockImportStateFault] then log.error("Block import error {}", err)
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
                deleteIfCorrupt(e.hash, failedBlock.number.value)
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
                val paths: Option[Seq[Seq[ByteString]]] = StorageHolePosition.paths(
                  e.location,
                  accountHash,
                  e.hash,
                  parentStateRoot.flatMap { root =>
                    Try(blockchainReader.getAccountAtStateRoot(root, Address(e.accountAddress))).toOption.flatten
                      .map(_.storageRoot.value)
                  },
                  hash => Try(stateStorage.getBackingStorage(failedBlock.number.value).get(hash.toArray)).toOption
                )
                log.info(
                  "Missing storage node {} for account {} during import of block {}, {}",
                  ByteStringUtils.hash2string(e.hash),
                  ByteStringUtils.hash2string(e.accountAddress),
                  failedBlock.number,
                  if e.location.isDefined then "position known"
                  else if paths.isDefined then "position found"
                  else "position not found"
                )
                deleteIfCorrupt(e.hash, failedBlock.number.value)
                pendingStateNodeHash = Some(e.hash)
                paths match
                  case Some(_) =>
                    fetcher ! BlockFetcher.FetchStateNode(e.hash, fetcherResponseAdapter, parentStateRoot, paths)
                  case None =>
                    // With no position the fetcher would ask by hash alone (GetNodeData), which no eth/68+ peer serves,
                    // and wait out its retries for nothing (forge, #1694). So nobody is asked: this is an exhausted
                    // fetch at once, with its backoff, and the audit may fill the hole meanwhile.
                    log.warning(
                      "Storage node {} could not be located under its storage root: asking no peer, and backing off",
                      ByteStringUtils.hash2string(e.hash)
                    )
                ResolvingMissingNode(
                  NonEmptyList(notImportedBlocks.head, notImportedBlocks.tail),
                  alreadyExhausted = paths.isEmpty
                )
              case e: MissingCodeException
                  if SyncController.bulkCodeRecoveryAllowed(
                    blockchainReader.isSnapSyncDone,
                    blockchainReader.bulkBytecodeRecoveryFailures
                  ) && BlockImporter.claimBulkCodeRecovery(e.hash) =>
                // Enough distinct missing codes in this process (see BulkCodeRecoveryAfterMisses) to show they are not
                // alone. Hand off to SyncController, which stops regular sync, runs the bytecode recovery scan and fetches
                // every gap in batches, then restarts regular sync. This importer waits to be stopped.
                val failedBlock = notImportedBlocks.head
                log.error(
                  "Missing contract code {} for account {} during import of block {}, with {} distinct misses so far: " +
                    "requesting bulk bytecode recovery (scan of the SNAP state trie, batched GetByteCodes) instead of " +
                    "fetching one hash at a time",
                  ByteStringUtils.hash2string(e.hash),
                  ByteStringUtils.hash2string(e.accountAddress),
                  failedBlock.number,
                  BlockImporter.BulkCodeRecoveryAfterMisses
                )
                supervisor ! SyncProtocol.MissingCodeNeedsBulkRecovery(failedBlock.number.value, e.hash)
                ResolvingMissingNode(NonEmptyList(failedBlock, notImportedBlocks.tail))
              case e: MissingCodeException =>
                // An account in the block's state has a codeHash whose bytecode this node never stored. Missing data,
                // not an invalid block: fetch it over SNAP GetByteCodes (served by every snap-capable peer, ETH68+)
                // and retry the same blocks. `e.hash` is the codeHash — the key GetByteCodes is addressed by.
                val failedBlock = notImportedBlocks.head
                val parentStateRoot =
                  try
                    Option(blockchainReader.getBlockHeaderByHash(failedBlock.header.parentHash)).flatten
                      .map(_.stateRoot.value)
                  catch
                    case ex: Exception =>
                      log.warning("Failed to get parent state root during code recovery: {}", ex.getMessage); None
                log.warning(
                  "Missing contract code {} for account {} during import of block {}. Fetching via SNAP GetByteCodes.",
                  ByteStringUtils.hash2string(e.hash),
                  ByteStringUtils.hash2string(e.accountAddress),
                  failedBlock.number
                )
                pendingStateNodeHash = Some(e.hash)
                fetcher ! BlockFetcher.FetchStateNode(
                  e.hash,
                  fetcherResponseAdapter,
                  parentStateRoot,
                  paths = None,
                  isByteCode = true
                )
                ResolvingMissingNode(NonEmptyList(failedBlock, notImportedBlocks.tail))
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
                deleteIfCorrupt(e.hash, failedBlock.number.value)
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
                    // failure rather than a missing-bytecode artifact. `InMemoryWorldStateProxy.getCode` now throws
                    // MissingCodeException when an account's code is absent, and the arm above fetches the code over
                    // SNAP and retries. (It used to return ByteString.empty, so a partially-synced node under-counted
                    // gas on an honest block and landed in that sibling arm.) Reaching this arm means no missing code
                    // was found. Having positively excluded that reading, tell the
                    // Engine API so newPayload/forkchoiceUpdated can answer INVALID for this block and its
                    // descendants. latestValidHash = the failing block's parent: execution proceeds in order and
                    // stops at the first failure, so the parent is the last block we validated.
                    // No-op unless the Engine API is enabled (ETC/Mordor/Gorgoroth never bind a reporter).
                    //
                    // WHICH block is reported is decided by `provenGasUsedFailure`, not assumed to be `failedBlock`:
                    // after a failed REORG, execution stopped mid-batch and `failedBlock` (the batch head) is an
                    // honest block. See BlockImportFailedAt. The fetcher message below is deliberately unchanged.
                    BlockImporter.provenGasUsedFailure(
                      err,
                      notImportedBlocks,
                      consensus.reportsInvalidChains,
                      findMissingContractCode
                    ) match
                      case Some((failing, descendants)) =>
                        val latestValidHash = failing.header.parentHash.value
                        consensus.reportInvalidChain(failing.hash.value, latestValidHash)
                        // Same reasoning as ConsensusImpl.reportIfProvenInvalid: the blocks queued behind the failing
                        // one were never executed, and the unbroken parentHash-linked prefix of them is provably
                        // invalid-by-descent with the same latestValidHash. Without this the verdict stops at
                        // the failing block and a CL-supplied tip two or more hops above it stays ACCEPTED forever.
                        descendants.foreach { d =>
                          log.warning(
                            "Block {} descends from invalid block {} — reporting as consensus-invalid",
                            d.number,
                            failing.number
                          )
                          consensus.reportInvalidChain(d.hash.value, latestValidHash)
                        }
                      case None =>
                        log.debug(
                          "Gas mismatch in batch starting at block {}: execution stopped at a later block that could " +
                            "not be proven invalid from here — not reporting from the import path",
                          failedBlock.number
                        )
                    val invalidBlockNr = failedBlock.number.value
                    fetcher ! BlockFetcher.InvalidateBlocksFrom(invalidBlockNr, err.toString)
                    RunningAfterFetcherRewind
              case UnknownParent =>
                // The batch hangs from a block this node does not have (B-007, #1683): a gap on our side, found out while
                // importing. The fetcher goes back to the batch's first block and fetches the branch below it, and the peer
                // that served the batch is not blamed: it was asked for these blocks and gave them. A block that keeps
                // failing so is still evicted, with its peer, after BadBlockEvictionThreshold strikes (tryImportBlocks).
                val failedBlock = notImportedBlocks.head
                RegularSyncMetrics.incrementOrphanBlocks("batch", "branch-fetch")
                fetcher ! BlockFetcher.InvalidateBlocksFrom(
                  failedBlock.number.value,
                  "parent of fetched block not found",
                  shouldBlacklist = false
                )
                RunningAfterFetcherRewind
              case BlockImportFailed(error) if Consensus.isLocalStateFault(error) =>
                // This node's trie is bad, and no fetch can name what to fetch. So this is an exhausted fetch at once:
                // the fetcher is rewound without blacklisting the honest provider, the block waits out the exhaust
                // backoff, and the third in a row asks for a SNAP re-sync, the only exit (spec 022 T062).
                val failedBlock = notImportedBlocks.head
                log.error(
                  "[LOCAL-STATE-FAULT] block {} failed on this node's own state, no peer blamed: {}",
                  failedBlock.number,
                  error
                )
                pendingStateNodeHash = None
                ResolvingMissingNode(NonEmptyList(failedBlock, notImportedBlocks.tail), alreadyExhausted = true)
              case BlockImportStateFault(setAside @ (_: RefusedForThisHead | _: HandedToRecovery), _) =>
                // The consensus layer's branch, refused for what no attempt changes while the head is where it is, or
                // handed to the fork choice's recovery, which adopts it (spec 018 C5a, B-297): fetched again it would
                // be refused again, without end, or executed again beside the recovery. The fetcher goes on, and what
                // hangs from the branch is set aside with it (`resolveBranch`) until the head moves. The escalation
                // acts on a chain this node cannot follow; no peer is blamed.
                log.warning(
                  "[STATE-REFUSED-FOR-THIS-HEAD] block {} is not fetched again while the head is where it is: {}",
                  notImportedBlocks.head.number,
                  setAside.getMessage
                )
                stateFaultStreak = None
                Running
              case BlockImportStateFault(fault, _) =>
                // The state these blocks are to be executed on is not the one this node holds, or is no longer: the
                // head moved under the batch, or could not be moved (spec 018 Track C, C4b). The blocks were not
                // judged. They are fetched again from the first that was not executed, and the peer that served them
                // is not blamed: it was asked for these blocks and gave them.
                val failedBlock = notImportedBlocks.head
                val streak = stateFaultStreak
                  .collect {
                    case (hash, count) if hash == failedBlock.hash.value => count
                  }
                  .getOrElse(0) + 1
                stateFaultStreak = Some((failedBlock.hash.value, streak))
                fetcher ! BlockFetcher.InvalidateBlocksFrom(
                  failedBlock.number.value,
                  "this node's state could not take the block",
                  shouldBlacklist = false
                )
                // A head that moved under the batch is gone at the next try. A fault that stays (a branch this layout
                // cannot execute yet, a state that is not held) would otherwise be met again at every round trip of
                // the fetcher, for good: from the third in a row on the same block the tries are spaced out.
                if streak < BlockImporter.StateFaultBackoffAfter then
                  log.warning(
                    "[LOCAL-STATE-FAULT] block {} was not executed on this node's own state, no peer blamed: {}",
                    failedBlock.number,
                    fault.getMessage
                  )
                  RunningAfterFetcherRewind
                else
                  if streak == BlockImporter.StateFaultBackoffAfter then
                    log.error(
                      "[LOCAL-STATE-FAULT] block {} could not be executed on this node's own state {} times " +
                        "running; it is tried again every {}s, and no peer is blamed: {}",
                      failedBlock.number,
                      streak,
                      BlockImporter.exhaustBackoff.toSeconds,
                      fault.getMessage
                    )
                  BackingOffAfterStateFault
              case _ =>
                val invalidBlockNr = notImportedBlocks.head.number.value
                fetcher ! BlockFetcher.InvalidateBlocksFrom(invalidBlockNr, err.toString)
                RunningAfterFetcherRewind
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
        // EIP-7928: for Amsterdam blocks, ask an eth/71 peer for their access lists so execution can prefetch. A
        // hint only: bounded by a short timeout, and any failure imports without it. A batch without a
        // `blockAccessListHash` (ETC, pre-Amsterdam) sends nothing and takes the plain call.
        val evaluated =
          balFetcher.fold(IO.pure(Map.empty[ByteString, BlockAccessList]))(_.fetch(nel.toList)).flatMap { accessLists =>
            timedImport(
              if accessLists.isEmpty then consensus.evaluateBranch(nel)
              else consensus.evaluateBranchWithAccessLists(nel, accessLists)
            )
          }
        evaluated.flatMap {
          case BlockImportedToTop(blockImportData) =>
            val importedNow = blockImportData.map(_.block)
            importedNow.foreach(b => unknownParentStrikes -= b.hash.value)
            importedNow.foreach(b => forkRecoveredFor -= b.hash.value)
            val imported = importedNow.reverse ::: importedBlocks
            imported.headOption
              .foreach(b => supervisor ! ProgressProtocol.ImportedBlock(b.number.value, internally = false))
            IO.pure((imported, None))

          case ChainReorganised(_, newBranch, _) =>
            newBranch.foreach(b => unknownParentStrikes -= b.hash.value)
            newBranch.foreach(b => forkRecoveredFor -= b.hash.value)
            val imported = newBranch.reverse ::: importedBlocks
            imported.headOption
              .foreach(b => supervisor ! ProgressProtocol.ImportedBlock(b.number.value, internally = false))
            IO.pure((imported, None))

          case DuplicateBlock | BlockEnqueued =>
            IO.pure((importedBlocks, None))

          case BlockImportFailedDueToMissingNode(missingNodeException, adopted)
              if syncConfig.redownloadMissingStateNodes =>
            // The validated prefix before the failing block is already adopted. Count it, so the retry starts at the
            // failing block and does not re-apply what is already in the database.
            IO.pure((adoptedPrefix(adopted, importedBlocks), Some(missingNodeException)))

          case BlockImportFailedDueToMissingNode(missingNodeException, adopted) =>
            IO(adoptedPrefix(adopted, importedBlocks)).flatMap(_ => IO.raiseError(missingNodeException))

          // This node's own state could not take a block of the batch (spec 018 Track C, C4b). The prefix that was
          // adopted before it is counted, as above. No strike: the block was not judged, so the peer that sent it is
          // not evicted and no fork is detected.
          case stateFault @ BlockImportStateFault(_, adopted) =>
            IO.pure((adoptedPrefix(adopted, importedBlocks), Some(stateFault)))

          // This node's own state failed, not the block: no strike, so the peer that sent it is not evicted and no fork
          // is detected (spec 022 T062). The caller backs off and escalates instead.
          case err @ BlockImportFailed(error) if Consensus.isLocalStateFault(error) =>
            IO.pure((importedBlocks, Some(err)))

          case err @ (UnknownParent | BlockImportFailed(_)) =>
            val failedBlock = nel.head
            // An orphan batch is a gap of ours, to be fetched: a warning, not an error (B-007).
            val level = if err == UnknownParent then Logging.WarningLevel else Logging.ErrorLevel
            log.log(
              level,
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
            if strikes >= ForkDetectThreshold && forkRecoveredFor.contains(failedBlock.hash.value) then
              // only on the unified state layout: nothing was rewound, so this is the same failure again
              log.error(
                "[SYNC-FORK] Block {} (hash={}) still fails after a fork recovery was made for it: no other is made " +
                  "for it. Its strikes start again",
                failedBlock.number,
                failedBlock.header.hashAsHexString
              )
              // so that the peer that serves it is dropped again at the third, as on the hash layout, where the
              // strikes start again at every recovery (forge)
              unknownParentStrikes -= failedBlock.hash.value
            else if strikes >= ForkDetectThreshold then
              if blockchainWriter.holdsStateInPlace then forkRecoveredFor += failedBlock.hash.value
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

  /** Before a missing node is fetched, the entry under its hash goes if it is not that node (spec 022 T062).
    *
    * A stored entry that does not decode, or fails its hash, reads as a missing node (`CorruptNodeException`), but that
    * type is gone by the time it gets here: the trie and the world state rewrap it with a position and an account. So
    * this runs for every missing node, and it is safe to: `deleteBadNode` reads the entry again itself (under the
    * commit lock, where the store has one) and removes only bytes that fail keccak, and an absent entry is
    * `NoLongerBad`. It runs before any escalation, so a re-SNAP after exhausted fetches never upserts over the junk,
    * which basic pruning's upsert would keep. A store that cannot delete, or a read that fails, leaves the fetch to go
    * ahead as before.
    */
  private def deleteIfCorrupt(hash: ByteString, blockNumber: BigInt): Unit =
    if stateStorage.canRepairNodes then
      try
        stateStorage.deleteBadNode(hash, () => true) match
          case BadEntryDelete.Deleted =>
            log.error(
              "[STATE-CORRUPT] deleted stored trie node {}: its bytes are not that node (met importing block {}); " +
                "fetching it from peers",
              ByteStringUtils.hash2string(hash),
              blockNumber
            )
          case _ => ()
      catch
        case NonFatal(e) =>
          log.error(
            e,
            "[STATE-CORRUPT] could not check stored trie node {} for corruption; fetching it anyway",
            ByteStringUtils.hash2string(hash)
          )

  /** Accounting for the validated prefix of a batch whose later block hit a missing node: those blocks are adopted, so
    * they are imported like any other (strikes cleared, progress reported) and prepended to `importedBlocks` (newest
    * first), which is what makes the caller's `blocks.drop(importedBlocks.size)` start at the failing block.
    */
  private def adoptedPrefix(adopted: List[BlockData], importedBlocks: List[Block]): List[Block] =
    val adoptedBlocks = adopted.map(_.block)
    adoptedBlocks.foreach(b => unknownParentStrikes -= b.hash.value)
    adoptedBlocks.foreach(b => forkRecoveredFor -= b.hash.value)
    val imported = adoptedBlocks.reverse ::: importedBlocks
    if adoptedBlocks.nonEmpty then
      imported.headOption
        .foreach(b => supervisor ! ProgressProtocol.ImportedBlock(b.number.value, internally = false))
    imported

  /** The consensus call of an import, timed: when it put blocks on the chain, how many and how long it took go to
    * `RegularSyncMetrics` (spec 027 C-7, the TUI's last-import duration). Timed here, around the call, so no consensus
    * file changes; the result and any failure pass through unchanged.
    */
  private def timedImport(evaluate: IO[BlockImportResult]): IO[BlockImportResult] =
    evaluate.timed.flatMap { (took, result) =>
      val blocks = result match
        case BlockImportedToTop(imported)      => imported.size
        case ChainReorganised(_, newBranch, _) => newBranch.size
        case _                                 => 0
      IO(if blocks > 0 then RegularSyncMetrics.recordLastImport(blocks, took.toNanos)).as(result)
    }

  private def importBlock(
      block: Block,
      importMessages: ImportMessages,
      blockImportType: BlockImportType,
      informFetcherOnFail: Boolean,
      announcedBy: Option[PeerId],
      internally: Boolean
  )(state: ImporterState): Behavior[Command] =
    def doLog(entry: ImportMessages.LogEntry): Unit = log.log(entry._1, entry._2)
    importWith(
      IO(doLog(importMessages.preImport()))
        .flatMap(_ => timedImport(consensus.evaluateBranchBlock(block)))
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
          case BlockImportFailedDueToMissingNode(missingNodeException, adopted)
              if syncConfig.redownloadMissingStateNodes =>
            announceAdopted(adopted, internally)
            // state node re-download will be handled when downloading headers
            doLog(importMessages.missingStateNode(missingNodeException))
          case BlockImportFailedDueToMissingNode(missingNodeException, adopted) =>
            announceAdopted(adopted, internally)
            IO.raiseError(missingNodeException)
          case BlockImportFailed(error) if informFetcherOnFail && !Consensus.isLocalStateFault(error) =>
            fetcher ! BlockFetcher.BlockImportFailed(block.number.value, BlacklistReason.BlockImportError(error))
          case UnknownParent => announcedBy.foreach(handleOrphanAnnounce(block, _))
          // Not judged, so nothing is said of it to the fetcher or of the peer that announced it; what was adopted
          // before the fault is announced as any imported blocks are.
          case BlockImportStateFault(_, adopted)                     => announceAdopted(adopted, internally)
          case BlockEnqueued | DuplicateBlock | BlockImportFailed(_) => ()
        }
        // A block that shows the announced block's parent was here clears the peer's orphan strikes. A duplicate shows
        // nothing: after an orphan rewind the next expected block is our own best, so a peer could re-announce that to
        // reset its count for free (forge, B-007). A queued block needed a header that passed validation, PoW included.
        .tap {
          case BlockImportedToTop(_) | ChainReorganised(_, _, _) | BlockEnqueued =>
            announcedBy.foreach(orphanAnnounces.clear)
          case _ => ()
        }
        .map(_ => Running),
      blockImportType
    )(state)

  /** A block a peer announced (NewBlock) whose parent this node does not have (B-007, #1683).
    *
    * That is a gap on OUR side, not an offence of the peer. On a reorging PoW chain an honest peer announces a child of
    * a block of a competing branch that this node never saw; right after SNAP hand-off, or after an announce the
    * importer was too busy to take, the fetcher's idea of the head can be ahead of the chain's. core-geth drops such a
    * block with a debug line and drops a peer only for a header that fails verification
    * (`eth/fetcher/block_fetcher.go`, importBlocks). Blacklisting here cost a good peer each time (T062, Mordor, 24 h:
    * 33 peers blacklisted, 5 reorgs resolved normally), and the only peer outright on a net with one.
    *
    * So the fetcher goes back to the block's parent and fetches the branch, as it does when a fetched batch's branch is
    * unknown, and nobody is blamed. Protection stays, bounded: a peer that announces [[OrphanAnnounceStrikeThreshold]]
    * such blocks in a row, with none of its blocks imported in between, is blacklisted as a peer that does not deliver
    * the parent.
    */
  private def handleOrphanAnnounce(block: Block, peer: PeerId): Unit =
    val strikes = orphanAnnounces.strike(peer)
    if strikes >= OrphanAnnounceStrikeThreshold then
      orphanAnnounces.clear(peer)
      log.warning(
        "Orphan announces: peer {} announced {} blocks in a row whose parent never arrived, the last is block {}: blacklisting it",
        peer,
        strikes,
        block.number
      )
      RegularSyncMetrics.incrementOrphanBlocks("announce", "blacklisted")
      // The peer whose strikes were counted, directly (forge, B-007). The fetcher's BlockImportFailed blames whoever it
      // recorded for the block's number, and another peer's announce of the same number can have replaced the one counted
      // here. (PeerListHelper's exemption of maintained peers does not apply on this path.)
      blacklist.add(
        peer,
        syncConfig.blacklistDuration,
        BlacklistReason.BlockImportError(
          s"announced $strikes blocks in a row whose parent never arrived, the last ${block.header.hashAsHexString}"
        )
      )
      // And back to the parent, as below, with no one blamed by the fetcher: the blame is above.
      fetcher ! BlockFetcher.InvalidateBlocksFrom(
        block.number.value,
        "parent of announced block not found",
        shouldBlacklist = false
      )
    else
      log.info(
        "Orphan announce: block {} from peer {} has a parent this node lacks (strike {}/{}): fetching its branch, no one blamed",
        block.number,
        peer,
        strikes,
        OrphanAnnounceStrikeThreshold
      )
      RegularSyncMetrics.incrementOrphanBlocks("announce", "branch-fetch")
      fetcher ! BlockFetcher.InvalidateBlocksFrom(
        block.number.value,
        "parent of announced block not found",
        shouldBlacklist = false
      )

  /** The validated prefix of a branch whose later block hit a missing node is adopted exactly as if the shorter branch
    * had imported: announced to peers, taken out of the tx pool, published, and reported as progress.
    */
  private def announceAdopted(adopted: List[BlockData], internally: Boolean): Unit =
    if adopted.nonEmpty then
      val (blocks, weights) = adopted.map(data => (data.block, data.weight)).unzip
      broadcastBlocks(blocks, weights)
      updateTxPool(blocks, Seq.empty)
      blocks.foreach(b => blockTopic ! Topic.Publish(NewBlockImported(b)))
      supervisor ! ProgressProtocol.ImportedBlock(blocks.last.number.value, internally)

  private def broadcastBlocks(blocks: List[Block], weights: List[ChainWeight]): Unit =
    // each block with its own weight: `mapN` on two lists is every pairing of the two, which sent a NewBlock with another
    // block's total difficulty whenever more than one block was told (forge, B-297, where a reorganisation tells the
    // side blocks it stands on too)
    val newBlocks = blocks.zip(weights).map((block, weight) => BlockToBroadcast(block, weight))
    broadcaster ! BroadcastBlocks(newBlocks)

  private def updateTxPool(blocksAdded: Seq[Block], blocksRemoved: Seq[Block]): Unit =
    blocksRemoved.foreach(block => pendingTransactionsManager ! AddUncheckedTransactions(block.body.transactionList))
    blocksAdded.foreach(block => pendingTransactionsManager ! RemoveTransactions(block.body.transactionList))

  private def importWith(importTask: IO[NewBehavior], blockImportType: BlockImportType)(
      state: ImporterState
  ): Behavior[Command] =
    val ref = selfRef
    // The whole import, branch resolution included, under the node's import lock: stopping this actor would not stop
    // it, and the importer that replaced this one must not import, nor resolve a branch, until it ends (#1705).
    importLock
      .guard(importTask)
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
  // B-300 (f), on the unified layout: the side branches that were weighed and not taken, by the hash of their tip. In
  // memory, the last few: a branch forgotten is fetched again from 64 blocks back, as before.
  private val branchesNotTaken = new BranchesNotTaken(BlockImporter.BranchesNotTakenKept)
  // the block a branch that is to be fetched whole is asked for up to: set by resolveBranch, taken as it is acted on
  private var branchUpTo: Option[BigInt] = None

  private def takeBranchUpTo(): Option[BigInt] =
    val upTo = branchUpTo
    branchUpTo = None
    upTo

  /** The branch not taken that `blocks` stand on, that branch with them, and whether it would be taken now. */
  private def continuesABranchNotTaken(
      blocks: NonEmptyList[Block]
  ): Option[(BranchNotTaken, BranchNotTaken, Boolean)] =
    for
      branch <- branchesNotTaken.get(blocks.head.header.parentHash.value)
      (longer, taken) <- branchResolution.extended(branch, blocks.map(_.header))
    yield (branch, longer, taken)

  private def resolveBranch(blocks: NonEmptyList[Block]): Either[BigInt, List[Block]] =
    val resolution = branchResolution.resolveBranch(blocks.map(_.header))
    lazy val continuation = continuesABranchNotTaken(blocks)
    resolution match
      case NewBetterBranch(oldBranch) =>
        // PoW only (#1592): drop the leading blocks that ARE our canonical chain, as core-geth's insertChain skips
        // known canonical blocks below the head. After a rewind the fetcher re-serves from below the head, so the batch
        // opens with canonical blocks; handed over whole, its parent is not the head and consensus re-executes that
        // prefix through reorganise, against a parent state basic pruning may already have deleted, and then treats
        // the prefix as the old chain it replaced. What is left starts at the head's child (importToTop) or, for a
        // real fork, at the fork point. The fork choice is unchanged: the same blocks are added on the same parent
        // weight. A chain following a consensus layer keeps its own path (ExtendsCanonicalHead below), untouched.
        val alreadyCanonical = if branchResolution.followsConsensusLayer then 0 else canonicalPrefixLength(blocks)
        val toImport = blocks.toList.drop(alreadyCanonical)
        if alreadyCanonical > 0 then
          log.info(
            "Canonical prefix dropped: batch={} alreadyCanonical={} importing={}",
            s"${blocks.head.number}-${blocks.last.number}",
            alreadyCanonical,
            toImport.headOption.zip(toImport.lastOption).fold("-") { case (f, l) => s"${f.number}-${l.number}" }
          )
        val depth = oldBranch.size
        if depth > 0 then
          log.info(
            "Chain reorg: evicting {} minority-fork blocks, importing {} canonical (depth={})",
            depth,
            toImport.size,
            depth
          )
          RegularSyncMetrics.incrementReorgTotal()
          RegularSyncMetrics.setLastReorgDepth(depth)
        val transactionsToAdd = oldBranch.flatMap(_.body.transactionList)
        pendingTransactionsManager ! PendingTransactionsManager.AddUncheckedTransactions(transactionsToAdd)
        // Add first block from branch as an ommer
        oldBranch.headOption.map(_.header).foreach(ommersPool ! AddOmmers(_))
        Right(toImport)
      case ExtendsCanonicalHead(alreadyCanonical) =>
        // Post-merge only (BranchResolution.followsConsensusLayer). The first `alreadyCanonical` blocks ARE our
        // canonical chain and the rest extend its head, so hand consensus only the rest: their parent is the head,
        // which takes ConsensusImpl's importToTop. The whole batch would take importToNewBranch instead, where equal
        // post-merge weight and an out-of-reach CL head answer KeptCurrentBestBranch and nothing is imported (#1432).
        // Nothing is displaced, so there is no old branch to return to the pool and no ommer.
        val extension = blocks.toList.drop(alreadyCanonical)
        val importing = (extension.headOption, extension.lastOption) match
          case (Some(first), Some(last)) => s"${first.number}-${last.number}"
          case _                         => "-"
        log.info(
          "Canonical prefix dropped: batch={} alreadyCanonical={} importing={}",
          s"${blocks.head.number}-${blocks.last.number}",
          alreadyCanonical,
          importing
        )
        Right(extension)
      case NoChainSwitch =>
        // Add first block from branch as an ommer
        ommersPool ! AddOmmers(blocks.head.header)
        // On the unified layout the blocks of a branch that is not taken are not stored, so the batch that follows
        // hangs from a block this node does not know: remembered here, it is weighed with what follows (B-300 f). The
        // one guard of the memo: elsewhere nothing is remembered, so nothing is found.
        if blockchainWriter.holdsStateInPlace && !branchResolution.followsConsensusLayer then
          branchResolution.notTaken(blocks.map(_.header)).foreach(branchesNotTaken.remember)
        Right(Nil)
      // a batch that hangs from the consensus layer's branch refused while the head is where it is: refused with it, and
      // not fetched from below (B-297)
      case UnknownBranch if consensus.refusedWithTheirParent(blocks) =>
        log.debug("Batch {}-{} hangs from a refused branch: dropped", blocks.head.number, blocks.last.number)
        Right(Nil)
      case UnknownBranch if continuation.isDefined =>
        // asked once: its answer counts MESS's verdicts in the metrics (forge)
        val (branch, longer, taken) = continuation.get
        val _ = branchesNotTaken.take(branch.tip.hash.value)
        if taken && longer.tip.number.value - branch.commonBlock > syncConfig.maxReadyBlocksQueueSize then
          // Heavier, and further above the block it leaves this chain at than the fetcher holds ready: a pick of it
          // whole is never answered (B-300 g). Nothing of it is fetched. No peer is known for it, so a fork recovery
          // finds one: one that serves this branch leaves this chain at the same block, and the recovery refuses the
          // branch and escalates it with that peer's headers.
          log.error(
            "[STATE-REORG-TOO-DEEP] SYNC-FORK: the branch that leaves this chain at block {} has become the heavier at " +
              "block {}, {} block(s) above it, more than the {} the fetcher holds ready: it is not fetched, and a fork " +
              "recovery is started to find a peer that serves it",
            branch.commonBlock,
            longer.tip.number,
            longer.tip.number.value - branch.commonBlock,
            syncConfig.maxReadyBlocksQueueSize
          )
          val repeats = beyondTheFetchRefusals.record(branch.commonBlock)
          if repeats == BeyondTheFetchRepeatsWarnAt then
            log.error(
              "[STATE-REORG-TOO-DEEP] SYNC-FORK: the branch that leaves this chain at block {} has been refused beyond " +
                "the fetch {} times in a row: the fork recoveries it starts do not find a peer that serves it",
              branch.commonBlock,
              repeats
            )
          selfRef ! StartForkRecovery(
            blocks.head.number.value,
            Some(BranchBeyondTheFetch(branch.commonBlock, longer.tip.number.value))
          )
          Right(Nil)
        else if taken then
          // The branch has become the heavier: it is fetched whole, once, from the block it leaves this chain at, and
          // judged as any branch is, MESS with it.
          log.warning(
            "[SYNC-FORK] The branch that leaves this chain at block {} has become the heavier at block {}: it is " +
              "fetched from there",
            branch.commonBlock,
            longer.tip.number
          )
          fetcher ! BlockFetcher.InvalidateBlocksFrom(
            branch.commonBlock + 1,
            "SYNC-FORK: a branch that was not taken has become the heavier",
            shouldBlacklist = false
          )
          // up to its tip, above the head where it is: heavier only with its tip, it read as the lighter without it, and
          // was remembered and sent for again (forge)
          branchUpTo = Some(longer.tip.number.value)
          Left(branch.commonBlock + 1)
        else
          // Still not the heavier (or refused by MESS): nothing is imported, nothing fetched again, and the fetcher
          // goes on above it. Going back for it was a loop: the same blocks, the same answer (forge).
          log.debug(
            "Blocks {} to {} continue a branch that is not taken (it leaves this chain at block {})",
            blocks.head.number,
            blocks.last.number,
            branch.commonBlock
          )
          branchesNotTaken.remember(longer)
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
          if validBlocks.nonEmpty && parentInChain(validBlocks.head) then
            log.info(s"Branch resolution at SNAP pivot floor ($floor), importing ${validBlocks.size} blocks directly")
            floorGapRewinds.reset()
            Right(validBlocks)
          else if validBlocks.nonEmpty then
            // The batch starts above the best block: the blocks between are not here (B-007, #1683). Importing it was a
            // failed import and a blacklisting, then a walk back one block at a time (17292670 to 17292665 on Mordor).
            // Fetch the blocks between the best block and the batch instead, and blame no one.
            val from = bestKnownBlockNumber + 1
            log.info(
              s"Branch resolution at SNAP pivot floor ($floor): batch ${validBlocks.head.number}-${validBlocks.last.number} " +
                s"does not continue the chain (parent ${ByteStringUtils.hash2string(validBlocks.head.header.parentHash.value).take(10)} is " +
                s"not here), going back to block $from to fetch the blocks between"
            )
            RegularSyncMetrics.incrementOrphanBlocks("batch", "branch-fetch")
            // The same block again and again: what is fetched above the best block never continues from it, which is how a
            // pivot that is not on the network's chain looks (forge, B-007). Nothing here can repair that; a re-sync can.
            val repeats = floorGapRewinds.record(from)
            if repeats >= PivotFloorGapWarnAfter then
              RegularSyncMetrics.incrementPivotFloorGapRepeats()
              if repeats % PivotFloorGapWarnAfter == 0 then
                log.warning(
                  "Pivot floor: the fetcher has been sent back to block {} {} times in a row, and what it fetches above still " +
                    "does not continue from our best block: this node's pivot may not be on the network's chain, and a re-sync " +
                    "(SNAP) may be needed",
                  from,
                  repeats
                )
            fetcher ! BlockFetcher.InvalidateBlocksFrom(
              from,
              "gap between the best block and the fetched batch",
              shouldBlacklist = false
            )
            Left(from)
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

  /** Is `block`'s parent in this node's chain, so that `block` can be imported as the next block on it. */
  private def parentInChain(block: Block): Boolean =
    val parent = block.header.parentHash
    // The best block itself counts, even where the canonical index does not name it: right after SNAP hand-off it is the
    // pivot, whose header is the only one stored.
    blockchainReader.getBestBlockHeader.exists(_.hash == parent) ||
    blockchainReader.isInChain(blockchainReader.getBestBranch, parent)

  /** How many leading blocks of `blocks` are already our canonical chain: at or below the best block, and the very
    * block the canonical index names at that height. Stops at the first block that is not, so nothing after it is ever
    * skipped.
    */
  private def canonicalPrefixLength(blocks: NonEmptyList[Block]): Int =
    val best = blockchainReader.getBestBlockNumber
    blocks.toList.takeWhile { b =>
      b.number.value <= best && blockchainReader.getCanonicalHashByNumber(b.number.value).contains(b.hash)
    }.size

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

  private def handleForkRecovery(
      failedBlockNumber: BigInt,
      beyondTheFetch: Option[BranchBeyondTheFetch],
      state: ImporterState
  ): Behavior[Command] =
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
        syncConfig = syncConfig,
        // on the unified state layout no block is taken off the chain here: the resolver only finds the common block
        discardDiverged = !blockchainWriter.holdsStateInPlace
      )
    )
    // Watched: one that stopped without an answer left this importer in `resolvingFork` for good, where nothing else
    // moves it on (#1705). It answers before it stops, and the stop is delivered after that answer.
    ctx.watchWith(resolverRef, BranchResolverStopped(resolverRef))
    resolverRef ! FastSyncBranchResolverActor.StartBranchResolver
    resolvingFork(failedBlockNumber, beyondTheFetch, capturedBest, snapPivot, state, resolverRef)

  private def resolvingFork(
      failedBlockNumber: BigInt,
      beyondTheFetch: Option[BranchBeyondTheFetch],
      capturedBest: BigInt,
      snapPivot: BigInt,
      state: ImporterState,
      resolver: TypedActorRef[FastSyncBranchResolverActor.Command],
      // On the unified state layout only: the resolver's answer, held while the import that started the recovery is
      // still running. The common block when it found one, none when it did not. That import sends the fetcher back
      // to the block that failed as it ends, and its end clears the branch being resolved: acted on before it, the
      // answer would be undone by both, and the block that failed would be fetched again and recovered for no more
      // (forge). The hash layout has already rewound by then, and acts at once as it always has. The common block comes
      // with the peer it was found against (B-300 g).
      held: Option[Option[(BigInt, Peer)]] = None
  ): Behavior[Command] =
    def holds: Boolean = blockchainWriter.holdsStateInPlace
    // the answer, acted on: with the common block, or with none
    def actOn(answer: Option[(BigInt, Peer)], state: ImporterState): Behavior[Command] = answer match
      case Some((lca, masterPeer)) =>
        fetchFromTheCommonBlock(lca, masterPeer, failedBlockNumber, beyondTheFetch, capturedBest, snapPivot, state)
      case None =>
        blindRewind(capturedBest, snapPivot)
        running(state.withoutDeferredBatches())
    Behaviors.receiveMessage {
      // an answer is held already: the resolver's stop after it says nothing more
      case BranchResolverStopped(stopped) if stopped == resolver && held.isDefined => Behaviors.same

      // the peer the common block was found against is kept: the branch's headers are that peer's (B-300 g)
      case BranchResolverMsg(FastSyncBranchResolverActor.BranchResolvedSuccessful(lca, masterPeer)) if holds =>
        if state.importing then
          resolvingFork(
            failedBlockNumber,
            beyondTheFetch,
            capturedBest,
            snapPivot,
            state,
            resolver,
            Some(Some((lca, masterPeer)))
          )
        else actOn(Some((lca, masterPeer)), state)

      case BranchResolverMsg(FastSyncBranchResolverActor.BranchResolutionFailed(_)) if holds && state.importing =>
        resolvingFork(failedBlockNumber, beyondTheFetch, capturedBest, snapPivot, state, resolver, Some(None))

      case BranchResolverStopped(stopped) if stopped == resolver && holds && state.importing =>
        resolvingFork(failedBlockNumber, beyondTheFetch, capturedBest, snapPivot, state, resolver, Some(None))

      case BranchResolverMsg(FastSyncBranchResolverActor.BranchResolvedSuccessful(lca, _)) =>
        blockchainReader.getBlockHeaderByNumber(lca) match
          case Some(lcaHeader) =>
            log.info(
              "SYNC-FORK: branch resolver found LCA at {} — rewinding canonical chain from {}",
              lca,
              capturedBest
            )
            rewindCanonicalChain(lca, lcaHeader.hash, capturedBest)
            unknownParentStrikes = Map.empty
            fetcher ! BlockFetcher.InvalidateBlocksFrom(
              lca + 1,
              "SYNC-FORK branch-resolver rollback",
              shouldBlacklist = false
            )
          case None =>
            log.warning("SYNC-FORK: no header at resolver LCA {} — falling back to blind rewind", lca)
            blindRewind(capturedBest, snapPivot)
        // Every exit rewinds the fetcher (or escalates to SNAP), so a batch deferred before recovery is stale.
        running(state.withoutDeferredBatches())

      case BranchResolverMsg(FastSyncBranchResolverActor.BranchResolutionFailed(_)) =>
        log.warning("SYNC-FORK: branch resolver failed — falling back to 128-block blind rewind")
        blindRewind(capturedBest, snapPivot)
        running(state.withoutDeferredBatches())

      case BranchResolverStopped(stopped) if stopped == resolver =>
        // Stopped without an answer (a failure inside it): what a failed resolution does.
        log.warning("SYNC-FORK: branch resolver stopped without an answer — falling back to 128-block blind rewind")
        blindRewind(capturedBest, snapPivot)
        running(state.withoutDeferredBatches())

      case ImportDone(_, _) =>
        // The import that raised StartForkRecovery sent it from inside its own IO (tryImportBlocks, FORK-DETECT), so
        // its ImportDone always arrives AFTER that, i.e. here. It used to fall into the catch-all below and be
        // dropped, leaving `importing = true` for good: after recovery `PickBlocks if !state.importing` never matched
        // again and regular sync was wedged — on every chain, ETC included. Apply exactly the state change that
        // ImportDone makes in `running` (not importing, no branch resolution, no deferred batches — the fetcher is
        // rewound at every exit). Its NewBehavior is moot: recovery supersedes whatever that import asked for.
        // Resetting at the exits instead would be wrong: if the resolver answered first, a new import could start
        // while this one still runs. If it does answer first, this ImportDone arrives in `running` and is handled
        // there as usual.
        exhaustedDuringImport = false
        val ended = state.notImportingBlocks().branchResolved().withoutDeferredBatches()
        held match
          case Some(answer) => actOn(answer, ended)
          case None => resolvingFork(failedBlockNumber, beyondTheFetch, capturedBest, snapPivot, ended, resolver)

      case _ => Behaviors.same
    }

  /** Fork recovery on the unified state layout, with the common block found (spec 018 C4e part 6; the user's decision
    * of 2026-11-15): '''no state is moved'''. The head stays where it is, the fetcher is sent back to the common block,
    * and the peer's branch is judged by weight against this node's own blocks above it when it arrives
    * (`resolveBranch`, then `ConsensusImpl`'s reorganisation): adopted if it is the heavier, left if it is not.
    * core-geth never sets its head back on finding a fork. What the rewind was for is kept: the fetcher is no longer
    * stuck on the block that failed, and no peer is blamed.
    *
    * A branch is refused, and the fetcher left where it is, when the head could not be moved back to the common block
    * (below what the changeset records reach, or below the finalized block), and when the branch reaches further above
    * the common block than the fetcher holds ready: the strict pick that weighs it whole is never answered (B-300 g;
    * the user's decision of 2026-11-16). This node goes on with its own chain, and the refused branch's headers are
    * fetched from `masterPeer`, the peer the common block was found against, for the escalation, which clears the state
    * and syncs it again ([[escalateOutOfReach]]).
    */
  private def fetchFromTheCommonBlock(
      lca: BigInt,
      masterPeer: Peer,
      failedBlockNumber: BigInt,
      beyondTheFetch: Option[BranchBeyondTheFetch],
      capturedBest: BigInt,
      snapPivot: BigInt,
      state: ImporterState
  ): Behavior[Command] =
    unknownParentStrikes = Map.empty
    // how far above the common block the branch reaches: this node's head, or the tip of the branch not taken that
    // started the recovery, where the common block found is the one that branch leaves this chain at
    val reach = beyondTheFetch.filter(_.commonBlock == lca).fold(capturedBest)(_.tip.max(capturedBest))
    blockchainReader.getBlockHeaderByNumber(lca) match
      case None =>
        // nothing to weigh a branch from, and nothing to escalate it with
        log.error(
          "[STATE-REORG-TOO-DEEP] SYNC-FORK: the peer's chain leaves this node's at block {}, {} block(s) below the " +
            "head {}, and this node has no header at that height. Nothing is fetched from there and nothing moved",
          lca,
          capturedBest - lca,
          capturedBest
        )
        running(state.withoutDeferredBatches())
      case Some(lcaHeader) =>
        val refused: Option[ReorgTooDeep] = blockchainWriter
          .refusalOfMoveBackTo(lcaHeader, blockchainReader)
          .orElse(Option.when(reach - lca > syncConfig.maxReadyBlocksQueueSize) {
            new ReorgTooDeep(
              ReorgRefusal.BeyondTheFetch,
              s"the branch reaches ${reach - lca} block(s) above block $lca, more than the " +
                s"${syncConfig.maxReadyBlocksQueueSize} the fetcher holds ready, and is never fetched whole"
            )
          })
        refused match
          case Some(fault) =>
            log.error(
              "[STATE-REORG-TOO-DEEP] SYNC-FORK: the peer's chain leaves this node's at block {}, below the head {}, " +
                "and is refused: {}. Nothing is fetched from there and nothing moved; the branch's headers are asked " +
                "of peer {} for the escalation",
              lca,
              capturedBest,
              fault.getMessage,
              masterPeer.id
            )
            escalateOutOfReach(lcaHeader, reach, masterPeer, fault)
            running(state.withoutDeferredBatches())
          case None =>
            log.warning(
              "[SYNC-FORK] The peer's chain leaves this node's at block {}: the fetcher goes back to it and the branch " +
                "is judged by weight. The head stays at block {} (snapPivot={})",
              lca,
              capturedBest,
              snapPivot
            )
            fetcher ! BlockFetcher.InvalidateBlocksFrom(
              lca + 1,
              "SYNC-FORK: fetch from the common block",
              shouldBlacklist = false
            )
            // the next pick is from the common block up to the block that failed, or to the head where that is lower:
            // what was seen of the peer's branch, which may be the shorter of the two (B-300 W4). What the peer has
            // above it is fetched after, and weighed with it (the branches this node did not take are remembered).
            selfRef ! PickBlocks
            running(
              state.withoutDeferredBatches().resolvingBranch(lca + 1, upTo = Some(failedBlockNumber.min(capturedBest)))
            )

  /** The headers of a branch refused at `lcaHeader`, asked of `peer` (B-300 g): until they outweigh the head, or as
    * many as the branch's reach above the common block and two requests more, and no more than
    * [[MaxBranchHeadersFetched]]. Told to the escalation's feed under the import lock, which counts the branch only if
    * it outweighs the head with valid headers. A request that fails, times out, or is answered with headers that are
    * not the branch's tells it nothing. One fetch at a time: a refusal while one runs starts none.
    */
  private def escalateOutOfReach(lcaHeader: BlockHeader, reach: BigInt, peer: Peer, fault: ReorgTooDeep): Unit =
    if ctx.child(BranchHeadersFetchName).isDefined then
      log.warning(
        "[STATE-ESCALATION] The headers of a branch out of reach are already being fetched: none are asked for the " +
          "branch that leaves this chain at block {}",
        lcaHeader.number
      )
    else
      val wanted =
        ((reach - lcaHeader.number.value) + 2 * syncConfig.blockHeadersPerRequest).min(MaxBranchHeadersFetched)
      ctx.spawn(
        BranchHeadersFetch(
          peer,
          lcaHeader,
          wanted,
          syncConfig.blockHeadersPerRequest,
          syncConfig.peerResponseTimeout,
          networkPeerManager,
          peerEventBus,
          consensus.branchOutweighsTheHead,
          headers => importLock.guardAs(BranchOutOfReachHolder)(IO.delay(consensus.branchOutOfReach(headers, fault)))
        ),
        BranchHeadersFetchName
      ): Unit

  private def blindRewind(capturedBest: BigInt, snapPivot: BigInt): Unit =
    val floor = (capturedBest - MaxForkAncestryDepth).max(snapPivot)
    if blockchainWriter.holdsStateInPlace then
      // No common block was found: the fetcher goes back as far as the rewind would have, and nothing is written
      // (spec 018 C4e part 6). A fork deeper than that is refused by the reorganisation when its blocks arrive.
      unknownParentStrikes = Map.empty
      if blockchainReader.getBlockHeaderByNumber(floor).isDefined then
        log.warning(
          "[SYNC-FORK] No common block was found: the fetcher goes back to block {}. The head stays at block {} " +
            "(snapPivot={})",
          floor,
          capturedBest,
          snapPivot
        )
        fetcher ! BlockFetcher.InvalidateBlocksFrom(
          floor + 1,
          "SYNC-FORK: fetch from below the head",
          shouldBlacklist = false
        )
      else
        log.error(
          "[SYNC-FORK] No common block was found and this node has no header at height {}: nothing is fetched again " +
            "and nothing moved",
          floor
        )
    else blindRewindOnTheHashLayout(capturedBest, snapPivot, floor)

  private def blindRewindOnTheHashLayout(capturedBest: BigInt, snapPivot: BigInt, floor: BigInt): Unit =
    blockchainReader.getBlockHeaderByNumber(floor) match
      case Some(floorHeader) =>
        log.warning(
          "SYNC-FORK: blind rewind from {} to {} (snapPivot={})",
          capturedBest,
          floor,
          snapPivot
        )
        rewindCanonicalChain(floor, floorHeader.hash, capturedBest)
        unknownParentStrikes = Map.empty
        fetcher ! BlockFetcher.InvalidateBlocksFrom(floor + 1, "SYNC-FORK rollback", shouldBlacklist = false)
      case None =>
        log.warning(
          "SYNC-FORK: no header at fork recovery floor bno={} — escalating to SNAP re-sync",
          floor
        )
        supervisor ! SyncProtocol.RegularSyncStuck(floor, s"no header at fork recovery floor $floor")

  private def rewindCanonicalChain(target: BigInt, targetHash: BlockHash, currentBest: BigInt): Unit =
    BlockImporter.rewindCanonicalChain(blockchainReader, blockchainWriter, reorgState, target, targetHash, currentBest)

  private def bestKnownBlockNumber: BigInt = blockchainReader.getBestBlockNumber

// Logger name anchor — never instantiated
final private class BlockImporterImpl
