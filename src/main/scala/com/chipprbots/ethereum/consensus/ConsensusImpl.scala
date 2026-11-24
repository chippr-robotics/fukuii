package com.chipprbots.ethereum.consensus

import org.apache.pekko.util.ByteString

import cats.data.NonEmptyList
import cats.effect.IO
import cats.effect.unsafe.IORuntime

import scala.annotation.tailrec
import scala.util.control.NonFatal

import com.chipprbots.ethereum.consensus.Consensus.*
import com.chipprbots.ethereum.consensus.ConsensusImpl.ForkPoint
import com.chipprbots.ethereum.consensus.ConsensusImpl.tagOf
import com.chipprbots.ethereum.consensus.engine.BranchHandOff
import com.chipprbots.ethereum.consensus.engine.DesignatedHead
import com.chipprbots.ethereum.consensus.engine.InvalidChainReporter
import com.chipprbots.ethereum.db.storage.pathstate.BlockRef
import com.chipprbots.ethereum.db.storage.pathstate.BranchPin
import com.chipprbots.ethereum.db.storage.pathstate.BranchTooLarge
import com.chipprbots.ethereum.db.storage.pathstate.HandedToRecovery
import com.chipprbots.ethereum.db.storage.pathstate.ReexecutionDiverged
import com.chipprbots.ethereum.db.storage.pathstate.RefusedForThisHead
import com.chipprbots.ethereum.db.storage.pathstate.ReorgRefusal
import com.chipprbots.ethereum.db.storage.pathstate.ReorgTooDeep
import com.chipprbots.ethereum.db.storage.pathstate.StateBusy
import com.chipprbots.ethereum.db.storage.pathstate.StateFault
import com.chipprbots.ethereum.db.storage.pathstate.StateUnavailable
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.BlockAccessList
import com.chipprbots.ethereum.domain.BlockHash
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.domain.BlockchainWriter
import com.chipprbots.ethereum.domain.CanonicalHeadLock
import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.domain.IndexRewrite
import com.chipprbots.ethereum.ledger.BlockData
import com.chipprbots.ethereum.ledger.BlockExecution
import com.chipprbots.ethereum.ledger.BlockExecutionError
import com.chipprbots.ethereum.ledger.BlockExecutionError.MPTError
import com.chipprbots.ethereum.ledger.BlockMetrics
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.MissingNodeException
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.ByteStringUtils
import com.chipprbots.ethereum.utils.Hex
import com.chipprbots.ethereum.utils.Logger

class ConsensusImpl(
    blockchainReader: BlockchainReader,
    blockchainWriter: BlockchainWriter,
    blockExecution: BlockExecution,
    // Narrow, one-way channel into the Engine API's invalid-block registry. In production this is ALWAYS a `Some`:
    // NodeBuilder passes `Some(invalidChainReporter)`, a LateBound holder, on every network including ETC/Mordor/
    // Gorgoroth. What makes every call below a no-op there is that the holder is never BOUND (only
    // EngineApiBuilder.bindInvalidChainReporter binds it, gated on network.engine-api.enabled). See
    // [[com.chipprbots.ethereum.consensus.engine.InvalidChainReporter]].
    invalidChainReporter: Option[InvalidChainReporter] = None,
    // PoS fork choice. In production this is ALWAYS a `Some`: NodeBuilder passes `Some(designatedHead)`, a
    // DesignatedHead.LateBound holder, on every network including ETC/Mordor/Gorgoroth. What keeps the PoS arm inert
    // on a PoW chain is that the holder is never BOUND there — EngineApiBuilder.bindDesignatedHead is the only binder
    // and requires network.engine-api.enabled AND a configured terminal-total-difficulty — so `headBlockHash` is
    // `None` and `leadsToDesignatedHead` is false. The `None` default only serves direct test construction.
    // See [[com.chipprbots.ethereum.consensus.engine.DesignatedHead]].
    designatedHead: Option[DesignatedHead] = None,
    // Takes the reference-count changes of an abandoned block back out (and puts a re-adopted side block's back). In
    // production this is the BlockchainImpl; the NoOp default only serves test doubles that have no state storage.
    reorgState: ReorgStateHandler = ReorgStateHandler.NoOp,
    // The fork choice's recovery, for the consensus layer's branch that is not adopted here (spec 018 C5a, B-297). In
    // production a LateBound holder, bound only on a post-merge chain with the Engine API on and the unified layout:
    // unbound (every ETC network, the hash layout, and the `None` of tests) such a branch is answered as before.
    branchHandOff: Option[BranchHandOff] = None,
    // How many of the blocks a reorganisation moved the head off are told, the nearest the old head (B-296): queued
    // again and handed to the pool. A shallower reorganisation tells them all. The same bound holds for the blocks it
    // moved the head onto, the nearest the new head, at least that head (B-344). A node passes its
    // `sync.max-queued-block-number-behind` (B-345).
    oldBranchToldAtMost: Int = ConsensusImpl.OldBranchToldAtMost,
    // How many of the side blocks a reorganisation executes again are read and executed at a time (B-343): the blocks
    // in memory at once, however deep the side chain. Tests give it to compare a run in chunks with one in one piece.
    reorgChunkBlocks: Int = ConsensusImpl.ReorgChunkBlocks,
    // How long a batch onto the head holds the head's lock on the hash layout before it lets it go between two blocks
    // (B-511): what a fork choice waits behind it, besides one block. Tests give a zero, a hold per block.
    importHoldBudget: scala.concurrent.duration.FiniteDuration = CanonicalHeadLock.ImportHoldBudget
) extends Consensus
    with Logger:

  require(reorgChunkBlocks > 0, s"reorgChunkBlocks must be positive, was $reorgChunkBlocks")

  /** Tell the consensus layer that `failingBlock` is consensus-invalid, but ONLY when the execution error proves it.
    *
    * This is the only place on the bulk p2p import path where the TYPED `BlockExecutionError` and the branch are both
    * in scope; `ConsensusAdapter` already reduced the error to a `String` one frame up, and `BlockImporter` sees only
    * that string. Classification therefore has to happen here.
    *
    * latestValidHash = `failingBlock.header.parentHash`. That is exactly "the most recent valid block in the branch
    * defined by payload and its ancestors" required by the Engine API spec, and it is correct in all three arrival
    * shapes because `BlockExecution.executeAndValidateBlocks` executes in order and stops at the first failure, so the
    * executed set is always a prefix of the branch:
    *   - some blocks of this batch executed => the parent is the last of them;
    *   - none executed => the parent is the branch's parent, which we either just had as canonical head
    *     (`importToTop`'s guard) or had previously executed (`importToNewBranch` resolved a stored chain weight for it,
    *     and weights are written only after a successful execution).
    * In the side-chain (reorg) case that parent is a validated but non-canonical block, which is what hive's
    * invalid-ancestor tests expect — not the canonical head.
    */
  private def reportIfProvenInvalid(
      failingBlock: Block,
      error: BlockExecutionError,
      unexecutedSuffix: List[Block]
  ): Unit =
    invalidChainReporter.foreach { reporter =>
      if InvalidChainReporter.provesConsensusInvalid(error) then
        val latestValidHash = failingBlock.header.parentHash.value
        log.warn(
          "Reporting block {} ({}) as consensus-invalid to the Engine API: {}",
          failingBlock.number,
          failingBlock.header.hashAsHexString,
          error.describe
        )
        reporter.reportInvalid(failingBlock.hash.value, latestValidHash)
        // Carry the verdict down the rest of this branch. `unexecutedSuffix` holds the blocks that came after the
        // failing one in the SAME batch and were therefore never executed; `provenDescendants` keeps only the
        // unbroken parentHash-linked prefix of them, so every block reported here is a proven descendant of a proven
        // invalid block. Without this the Engine API's verdict stops at the failing block and any CL-supplied tip
        // more than one hop above it stays ACCEPTED forever — hive's "Invalid Missing Ancestor Syncing ReOrg ...
        // Invalid P8" family, 16/16 failing on 6c8bc97. See InvalidChainReporter.provenDescendants.
        InvalidChainReporter.provenDescendants(failingBlock, unexecutedSuffix).foreach { descendant =>
          log.warn(
            "Reporting block {} ({}) as consensus-invalid to the Engine API: descends from invalid block {}",
            descendant.number,
            descendant.header.hashAsHexString,
            failingBlock.header.hashAsHexString
          )
          reporter.reportInvalid(descendant.hash.value, latestValidHash)
        }
      else
        log.debug(
          "Not reporting block {} as invalid — error does not prove invalidity: {}",
          failingBlock.number,
          error.describe
        )
    }

  /** Try to set the given branch as the new best branch if it is better than the current best branch.
    * @param branch
    *   the new branch as a sorted list of blocks. Its parent must be in the current best branch
    * @param blockExecutionScheduler
    *   threadPool on which the execution should be run
    * @param blockchainConfig
    *   blockchain configuration
    * @return
    *   One of:
    *   - [[Consensus.ExtendedCurrentBestBranch]] - if the branch was added on top of the current branch
    *   - [[Consensus.SelectedNewBestBranch]] - if the chain was reorganized.
    *   - [[Consensus.KeptCurrentBestBranch]] - if the branch was not considered as better than the current branch
    *   - [[Consensus.ConsensusError]] - block failed to execute (when importing to top or reorganising the chain)
    *   - [[Consensus.ConsensusErrorDueToMissingNode]] - block failed to execute (when importing to top or reorganising
    *     the chain)
    *   - [[Consensus.ConsensusStateFault]] - this node's own state could not take a block: no block was judged
    */
  override def evaluateBranch(
      branch: NonEmptyList[Block]
  )(implicit blockExecutionScheduler: IORuntime, blockchainConfig: BlockchainConfig): IO[ConsensusResult] =
    evaluateBranchWithAccessLists(branch, Map.empty)

  /** [[evaluateBranch]] for a branch that arrived with peer-supplied EIP-7928 lists (eth/71 `BlockAccessLists`), keyed
    * by block hash. They are prefetch hints only: execution re-checks each against its header and still builds and
    * validates its own list, so an empty or wrong map changes no outcome.
    */
  override def evaluateBranchWithAccessLists(
      branch: NonEmptyList[Block],
      accessLists: Map[ByteString, BlockAccessList]
  )(implicit blockExecutionScheduler: IORuntime, blockchainConfig: BlockchainConfig): IO[ConsensusResult] =
    // Try the full-block lookup first (existing mock-based tests rely on it),
    // then fall back to header-only — that's the state right after PivotHeaderBootstrap
    // completes. handleBranchImport only consumes header.hash and header.number,
    // so a header is sufficient. Closes #1201's post-bootstrap follow-up.
    blockchainReader.getBestBlock.map(_.header).orElse(blockchainReader.getBestBlockHeader) match
      case Some(bestHeader) =>
        blockchainReader.getChainWeightByHash(bestHeader.hash) match
          case Some(weight) => handleBranchImport(branch, bestHeader, weight, accessLists)
          case None         => returnNoTotalDifficultyForHeader(bestHeader)
      case None => returnNoBestBlock()

  private def handleBranchImport(
      branch: NonEmptyList[Block],
      currentBestHeader: BlockHeader,
      currentBestBlockWeight: ChainWeight,
      accessLists: Map[ByteString, BlockAccessList]
  )(implicit
      blockExecutionScheduler: IORuntime,
      blockchainConfig: BlockchainConfig
  ): IO[ConsensusResult] =

    val consensusResult: IO[ConsensusResult] =
      if blockchainReader.holdsStateInPlace then
        if currentBestHeader.hash == branch.head.header.parentHash then
          IO.delay(importToTop(branch, currentBestBlockWeight, accessLists)).evalOn(blockExecutionScheduler.compute)
        else
          // on the unified layout a branch that is adopted moves the head with the state, under the state lock and
          // behind any move under way: on a thread that may block (spec 018 C4e; forge)
          IO.blocking(
            importToNewBranch(
              branch,
              currentBestHeader.number.value,
              currentBestBlockWeight,
              accessLists,
              currentBestHeader.hash
            )
          )
      else
        // On the hash layout a branch is executed and settled under the head's lock, as go-ethereum's insertChain holds
        // `chainmu`, and a batch onto the head is executed under it a hold at a time (B-511): a fork choice or a
        // payload's entry waits for it, and the head a batch is weighed against is the head as it is once the lock is
        // held. On a thread that may block: the wait for the lock is no work of the compute pool's.
        IO.blocking(onTheHashLayout(branch, currentBestHeader, currentBestBlockWeight, accessLists))

    consensusResult.flatTap(result => IO(measureBlockMetrics(result)))

  /** A step of a batch on the hash layout, taken under [[CanonicalHeadLock]]: the batch's result, or the blocks still
    * to execute onto the head, the blocks executed so far and the weight of the last of them.
    */
  private enum HashStep:
    case Done(result: ConsensusResult)
    case More(remaining: NonEmptyList[Block], executed: List[BlockData], weight: ChainWeight)

  /** A batch on the hash layout, against the head as it is (B-511).
    *
    * The first hold of [[CanonicalHeadLock]] reads the head: the head the batch was weighed against, or, when a fork
    * choice moved it since, the head it moved to, which the batch is weighed against again. A batch that does not stand
    * on it is a branch: executed and settled in that one hold, as `insertChain` holds `chainmu`. Nothing else moves the
    * head while the lock is held, so the head the branch is executed and settled against stays the head it was read as.
    *
    * A batch onto the head is executed a hold at a time: each hold executes blocks for at most `importHoldBudget`
    * ([[CanonicalHeadLock.ImportHoldBudget]] on a node; one block at least), then lets the lock go, so that a fork
    * choice that waits has it before the next hold (the lock is fair). Each hold after the first asks again that the
    * next block stands on the head, as the strict import's `stillOnHead` asks before each commit. A batch block commits
    * the best block and its index entry with its state, so the best block and the index name one chain after every
    * hold. When a fork choice moved the head elsewhere between two holds, the batch stops there: the blocks executed
    * stay as they were committed, the fork choice's head stays the head, and the rest are handed back, not judged, as a
    * head that moved under a batch is on the unified layout ([[StateBusy]], which no escalation counts).
    */
  private def onTheHashLayout(
      branch: NonEmptyList[Block],
      weighedHeader: BlockHeader,
      weighedWeight: ChainWeight,
      accessLists: Map[ByteString, BlockAccessList]
  )(implicit blockchainConfig: BlockchainConfig): ConsensusResult =
    def holding(remaining: NonEmptyList[Block]): String =
      s"the import of blocks [${remaining.head.number}-${remaining.last.number}]"

    def first: HashStep =
      // the header alone: the old head is read whole only if its blocks are told (B-296)
      blockchainReader.getBestBlockHeader match
        case None => HashStep.Done(noBestBlock())
        case Some(now) =>
          val weight =
            if now.hash == weighedHeader.hash then Some(weighedWeight)
            else
              val stored = blockchainReader.getChainWeightByHash(now.hash)
              if stored.isDefined then
                log.info(
                  "REORG: the head moved to {} {} since the blocks [{}-{}] were weighed against {}: they are weighed " +
                    "against the head as it is",
                  now.number,
                  now.hashAsHexString,
                  branch.head.number,
                  branch.last.number,
                  weighedHeader.number
                )
              stored
          weight match
            case None => HashStep.Done(noTotalDifficultyForHeader(now))
            case Some(weight) if now.hash == branch.head.header.parentHash =>
              ontoTheHeadForAHold(branch, branch, Nil, weight, accessLists)
            case Some(weight) =>
              HashStep.Done(importToNewBranch(branch, now.number.value, weight, accessLists, now.hash))

    def next(remaining: NonEmptyList[Block], executed: List[BlockData], weight: ChainWeight): HashStep =
      blockchainReader.getBestBlockHeader match
        case Some(now) if now.hash == remaining.head.header.parentHash =>
          ontoTheHeadForAHold(branch, remaining, executed, weight, accessLists)
        case now =>
          val where = now.fold("no block")(h => s"block ${h.number} (${h.hashAsHexString})")
          log.info(
            "[STATE-BUSY] the head moved to {} under the import of blocks [{}-{}], after {} block(s) of it: the rest are " +
              "handed back, not judged",
            where,
            branch.head.number,
            branch.last.number,
            executed.size
          )
          HashStep.Done(
            ConsensusStateFault(
              new StateBusy(
                "a fork choice",
                s"the head moved to $where under the import of blocks [${branch.head.number}-${branch.last.number}], " +
                  s"after ${executed.size} block(s) of it"
              ),
              executed,
              remaining.toList
            )
          )

    @tailrec
    def go(step: HashStep): ConsensusResult = step match
      case HashStep.Done(result) => result
      case HashStep.More(remaining, executed, weight) =>
        go(CanonicalHeadLock.held(holding(remaining))(next(remaining, executed, weight)))

    go(CanonicalHeadLock.held(holding(branch))(first))

  /** One hold's worth of a batch onto the head, on the hash layout, under [[CanonicalHeadLock]]: blocks executed one at
    * a time, each committed as the head, while the hold is within `importHoldBudget`; then the rest, if any, for the
    * next hold. A batch that ends in this hold is answered in it, as [[importToTop]] answers it, so that its write of
    * the best block is under the lock too.
    */
  private def ontoTheHeadForAHold(
      branch: NonEmptyList[Block],
      remaining: NonEmptyList[Block],
      executedBefore: List[BlockData],
      weight: ChainWeight,
      accessLists: Map[ByteString, BlockAccessList]
  )(implicit blockchainConfig: BlockchainConfig): HashStep =
    val until = System.nanoTime() + importHoldBudget.toNanos
    @tailrec
    def run(blocks: NonEmptyList[Block], executed: List[BlockData], weight: ChainWeight): HashStep =
      val displaced = displacedBy(List(blocks.head))
      val outcome = executeBranch(List(blocks.head), weight, adoptEachBlock = true, accessLists)
      abandonDisplaced(displaced, outcome.executed)
      val executedNow = executed ++ outcome.executed
      NonEmptyList.fromList(blocks.tail) match
        case Some(rest) if outcome.error.isEmpty && outcome.stateFault.isEmpty =>
          val carried = outcome.executed.last.weight
          if System.nanoTime() < until then run(rest, executedNow, carried)
          else
            // the best block as the batch leaves it, under this hold: each block's commit wrote it already, so this
            // writes the same block again, as the end of a batch does (`saveLastBlock`)
            saveLastBlock(executedNow)
            HashStep.More(rest, executedNow, carried)
        case _ =>
          HashStep.Done(answered(branch, BlockExecution.BatchOutcome(executedNow, outcome.error, outcome.stateFault)))
    run(remaining, executedBefore, weight)

  private def importToNewBranch(
      branch: NonEmptyList[Block],
      currentBestBlockNumber: BigInt,
      currentBestBlockWeight: ChainWeight,
      accessLists: Map[ByteString, BlockAccessList],
      currentBestBlockHash: BlockHash
  )(implicit
      blockchainConfig: BlockchainConfig
  ) =
    val parentHash = branch.head.header.parentHash

    blockchainReader.getChainWeightByHash(parentHash) match
      case Some(parentWeight) =>
        // Two ways in. The weight comparison is the pre-merge rule and is untouched.
        //
        // The second disjunct is the PoS arm, and it is needed because the first is unsatisfiable post-merge:
        // `ChainWeight.increase` adds `header.difficulty`, every post-merge header carries 0, so
        // `newBranchWeight(branch, parentWeight) == parentWeight <= currentBestBlockWeight` for EVERY branch and this
        // method could never once reorganise a PoS chain. On ETC/Mordor/Gorgoroth the right-hand side is ALWAYS false,
        // but NOT because `designatedHead` is `None` — in production it is `Some(LateBound)` on every network. It is
        // false because that holder is never BOUND on a PoW chain (EngineApiBuilder.bindDesignatedHead requires the
        // Engine API enabled AND a terminal-total-difficulty, which no PoW config sets), so `headBlockHash` is `None`
        // and `leadsToDesignatedHead` returns false without walking a single header. `||` therefore cannot change an
        // ETC decision. ConsensusImplSpec pins exactly this production shape: an unbound LateBound, PoW fork choice
        // unchanged.
        //
        // What it asks is the correct PoS fork-choice question: does this branch lead to the head the consensus layer
        // named? If so we follow it, because on PoS the EL does not choose — it follows the CL. If the branch turns
        // out to contain an invalid block, `reorganise` stops at it and reports it, which is exactly how the Engine
        // API learns to answer INVALID for a CL head it cannot reach.
        //
        // `selectedByWeight` is carried into `reorganise`: only a branch chosen by the PoW rule gets the core-geth
        // head/index handling there. A branch chosen solely by the designated head (post-merge, where no branch is
        // ever heavier) keeps its pre-existing handling exactly.
        val selectedByWeight = newBranchWeight(branch, parentWeight) > currentBestBlockWeight
        if selectedByWeight || leadsToDesignatedHead(branch)
        then
          reorganise(
            currentBestBlockNumber,
            currentBestBlockWeight,
            branch,
            parentWeight,
            parentHash,
            selectedByWeight,
            accessLists,
            currentBestBlockHash
          )
        else KeptCurrentBestBranch
      // a branch that hangs from the consensus layer's branch refused while the head is where it is: refused with it
      case None if refusedWithTheirParent(branch) =>
        ConsensusStateFault(
          new RefusedForThisHead(
            new StateUnavailable(s"block ${branch.head.number - 1}, which the branch stands on, was refused")
          ),
          Nil,
          Nil
        )
      case None =>
        ConsensusError(
          branch.toList,
          s"$UnknownParentErrorPrefix${Hex.toHexString(parentHash.toArray)} (number ${branch.head.number - 1})"
        )

  private def followsConsensusLayer: Boolean = designatedHead.exists(_.headBlockHash.isDefined)

  private def importToTop(
      branch: NonEmptyList[Block],
      currentBestBlockWeight: ChainWeight,
      accessLists: Map[ByteString, BlockAccessList]
  )(implicit
      blockchainConfig: BlockchainConfig
  ): ConsensusResult =
    val displaced = displacedBy(branch.toList)
    val outcome = executeBranch(branch.toList, currentBestBlockWeight, adoptEachBlock = true, accessLists)
    abandonDisplaced(displaced, outcome.executed)
    answered(branch, outcome)

  /** PoS only: an Engine API payload that extended the head wrote its index entry ahead of the best block
    * (`engine_newPayload` does, the best pointer moving only on forkchoiceUpdated). A different block imported at that
    * height replaces the entry, and the payload is then non-canonical with its reference counts still applied. Read
    * before execution overwrites the entries; on a PoW chain `followsConsensusLayer` is false and nothing is read.
    */
  private def displacedBy(blocks: List[Block]): List[(BigInt, BlockHash)] =
    if followsConsensusLayer then
      blocks.flatMap(b =>
        blockchainReader.getCanonicalHashByNumber(b.number.value).filterNot(_ == b.hash).map(b.number.value -> _)
      )
    else Nil

  /** The [[displacedBy]] entries at the heights a block was executed at: their blocks leave the chain. */
  private def abandonDisplaced(displaced: List[(BigInt, BlockHash)], executed: List[BlockData]): Unit =
    if displaced.nonEmpty then
      val heights = executed.map(_.block.number.value).toSet
      reorgState.abandonBlockStates(displaced.collect { case (n, h) if heights(n) => (n, h.value) })

  /** What a batch onto the head (`branch`) is answered with, once it ran as `outcome`. */
  private def answered(branch: NonEmptyList[Block], outcome: BlockExecution.BatchOutcome): ConsensusResult =
    // On the unified layout a trie error that is not a missing node is this node's own state's, never the block's, as
    // the adoption of a branch (`settleOnLayers`) and the commit in place at the cap read it: a fault like any other,
    // told to the escalation's feed as a branch not judged (B-450). A missing node is left to the adapter, which fetches
    // one that names its position and answers the others as a fault (C5a part 10, C5b). On the hash layout the text of
    // the error is what the importer escalates by (spec 022 T062), as before.
    val fault: Option[StateFault] = outcome.stateFault.orElse(
      outcome.error.collect {
        case MPTError(reason) if !reason.isInstanceOf[MissingNodeException] && blockchainReader.holdsStateInPlace =>
          new StateUnavailable(s"a trie read failed during the execution of the batch: $reason")
      }
    )
    fault match
      case Some(fault) => stateFaulted(fault, outcome.executed, branch.toList)
      case None        => extendedBy(branch, (outcome.executed, outcome.error))

  /** A batch that stopped on a fault of this node's own state (spec 018 Track C, C4b): the blocks before it are
    * adopted, as a shorter batch would have left them, and the rest are handed back to be tried again. No block is
    * reported invalid and no peer is blamed: the block the fault was met at was not judged.
    */
  private def stateFaulted(fault: StateFault, imported: List[BlockData], branch: List[Block]): ConsensusResult =
    saveLastBlock(imported)
    val notExecuted = branch.drop(imported.length)
    log.warn(
      s"${tagOf(fault)} the import stopped at block {} on this node's own state, after {} block(s) of the batch: {}",
      notExecuted.headOption.fold("?")(_.number.value.toString),
      imported.length,
      fault.getMessage
    )
    ConsensusStateFault(fault, imported, notExecuted)

  private def extendedBy(
      branch: NonEmptyList[Block],
      result: (List[BlockData], Option[BlockExecutionError])
  ): ConsensusResult =
    result match
      case (importedBlocks, None) =>
        saveLastBlock(importedBlocks)
        ExtendedCurrentBestBranch(importedBlocks)

      case (importedBlocks, Some(MPTError(reason: MissingNodeException))) =>
        // The blocks before the failing one executed and validated: adopt them, as a successful shorter batch would
        // have, and return the error for the failing block alone. The retry then starts there and nothing is applied
        // twice. (The failing block itself staged nothing; see BlockExecution.executeAndValidateBlocks.)
        saveLastBlock(importedBlocks)
        ConsensusErrorDueToMissingNode(Nil, reason, importedBlocks)

      case (Nil, Some(error)) =>
        // Nothing executed, so the failing block is the branch head and its parent is the current best block
        // (guaranteed by handleBranchImport's `currentBestHeader.hash == branch.head.header.parentHash` guard).
        reportIfProvenInvalid(branch.head, error, branch.tail)
        // A fault of this node's own state (spec 022 T062) judged no block: the branch waits in the queue to be tried
        // again (B-336). A block's own error drops it, as before.
        val toEnqueue = if Consensus.isLocalStateFault(error.toString) then branch.toList else Nil
        BranchExecutionFailure(toEnqueue, branch.head.header.hash.value, error.toString)

      case (importedBlocks, Some(error)) =>
        saveLastBlock(importedBlocks)
        val unexecuted = branch.toList.drop(importedBlocks.length)
        val failingBlock = unexecuted.head
        // NB: for a block's own error this arm maps to `ImportedPartially` in ConsensusAdapter (B-338; before it,
        // `BlockImportedToTop`, which discarded `error`). Reporting here is what every typed error is reported by.
        reportIfProvenInvalid(failingBlock, error, unexecuted.tail)
        // A fault of this node's own state (spec 022 T062): the prefix stays the head, and the failing block and those
        // after it were not judged. They wait in the queue to be tried again, and the adapter answers the fault, with
        // the prefix, so that the importer escalates it and blames no peer (B-336).
        val toEnqueue = if Consensus.isLocalStateFault(error.toString) then unexecuted else Nil
        ExtendedCurrentBestBranchPartially(
          importedBlocks,
          BranchExecutionFailure(toEnqueue, failingBlock.hash.value, error.toString)
        )

  /** A branch without peer-supplied lists (every ETC and pre-Amsterdam branch) takes the unchanged call. */
  private def executeBranch(
      blocks: List[Block],
      parentWeight: ChainWeight,
      adoptEachBlock: Boolean,
      accessLists: Map[ByteString, BlockAccessList],
      pin: Option[BranchPin] = None
  )(implicit blockchainConfig: BlockchainConfig): BlockExecution.BatchOutcome =
    val (executed, error) =
      if pin.isDefined then blockExecution.executeAndValidateBranch(blocks, parentWeight, accessLists, pin)
      else if accessLists.isEmpty then blockExecution.executeAndValidateBlocks(blocks, parentWeight, adoptEachBlock)
      else blockExecution.executeAndValidateBlocksWithAccessLists(blocks, parentWeight, adoptEachBlock, accessLists)
    // A fault of this node's state is taken out of the errors here, once, so that no arm below that judges a block
    // by its error (and reports it invalid) can be handed one.
    BlockExecution.BatchOutcome.of(executed, error)

  /** The side blocks a branch stands on whose state is not held, from the lowest such one up (`chain`, by number and
    * hash), with the header of that lowest one (`first`); `None` when every one is held ([[lostSideParents]]).
    */
  final private case class LostSideParents(first: BlockHeader, chain: List[(BigInt, BlockHash)])

  /** Every side block the branch stands on (`fork.parentSideChain`) is checked stored whole, as it always was, and
    * those from the lowest one whose state is not held up are the ones executed again: what stands on such a block is
    * not held either. Read one at a time and not kept (B-343): of the blocks, only the lowest one's header is.
    *
    * The answers are those of reading them all whole first: a block not stored whole is told by the highest such one,
    * and the state is asked of no block once one is found not stored whole.
    */
  private def lostSideParents(fork: ForkPoint): Either[StateFault, Option[LostSideParents]] =
    var notStoredWhole: Option[BigInt] = None
    var firstLost: Option[(Int, BlockHeader)] = None
    fork.parentSideChain.iterator.zipWithIndex.foreach { case ((number, hash), index) =>
      blockchainReader.getBlockByHash(hash) match
        case None => notStoredWhole = Some(number)
        case Some(block) =>
          if notStoredWhole.isEmpty && firstLost.isEmpty && !blockchainWriter.stateHeldInPlaceOf(block.header) then
            firstLost = Some(index -> block.header)
    }
    notStoredWhole match
      case Some(number) =>
        Left(new StateUnavailable(s"block $number, which the branch stands on, is not stored whole"))
      case None =>
        Right(firstLost.map((index, header) => LostSideParents(header, fork.parentSideChain.drop(index))))

  /** Blocks still to run at a reorganisation, in order: side blocks the branch stands on, by number and hash, read from
    * the store a chunk at a time as they are executed, then blocks of the branch itself, which are in memory already.
    * `standsOn` is the hash of the block the first of them stands on, known where it is made: the commit in place
    * starts from it without reading the first side block again, which another writer may have taken meanwhile.
    */
  final private case class ToRun(sideParents: List[(BigInt, BlockHash)], branch: List[Block], standsOn: BlockHash):
    def size: Int = sideParents.size + branch.size
    def nonEmpty: Boolean = sideParents.nonEmpty || branch.nonEmpty

  /** What [[executeInChunks]] ran: how many blocks were executed, what is kept of them, and how the run ended.
    *
    * `kept` is, of the side blocks executed, the last `newBranchToldAtMost` only and without their receipts, then every
    * block of the branch executed, as it was executed: no more of the side blocks is ever read by an answer
    * ([[sideParentsTold]] reads the ones nearest the branch, the commit in place the last block's weight), and no
    * answer carries a side block's receipts (what is told of the blocks that became canonical is blocks and weights).
    */
  final private case class ChunkedRun(executedCount: Int, kept: List[BlockData], ended: ChunkedRun.Ended):
    def error: Option[BlockExecutionError] = ended match
      case ChunkedRun.Ended.Failed(_, error) => Some(error)
      case _                                 => None
    def stateFault: Option[StateFault] = ended match
      case ChunkedRun.Ended.Faulted(fault) => Some(fault)
      case _                               => None

  private object ChunkedRun:
    enum Ended:
      /** Every block ran. */
      case Whole

      /** `block`'s own execution or validation failed with `error`; it and what follows were not run. */
      case Failed(block: Block, error: BlockExecutionError)

      /** This node's state could not take the next block, or a side block to run was not read whole. */
      case Faulted(fault: StateFault)

  /** `toRun` executed as one [[executeBranch]] over its blocks would execute it (B-343): the same blocks, in the same
    * order, each on its parent's state and its parent's weight, stopping at the same block for the same reason, with
    * the same `adoptEachBlock`, access lists and pin. Only how many are in memory at once changes: the side blocks are
    * read from the store `reorgChunkBlocks` at a time, each chunk executed by one call, and the next chunk carries on
    * from the weight of the last block executed. Nothing else passes from one block to the next: each block is staged
    * on its parent's state as the store, or the pin, holds it (`BlockExecution.executeAndCommit`).
    *
    * A side block that is not read whole here was read whole a moment before ([[lostSideParents]]): another writer took
    * it meanwhile. That ends the run as a fault of this node, with nothing of that chunk executed.
    */
  private def executeInChunks(
      toRun: ToRun,
      parentWeight: ChainWeight,
      adoptEachBlock: Boolean,
      accessLists: Map[ByteString, BlockAccessList],
      pin: Option[BranchPin] = None
  )(implicit blockchainConfig: BlockchainConfig): ChunkedRun =
    val sideKept = scala.collection.mutable.ArrayDeque.empty[BlockData]
    def keep(executed: List[BlockData]): Unit =
      executed.foreach { data =>
        sideKept.append(data.copy(receipts = Nil))
        if sideKept.size > newBranchToldAtMost then sideKept.removeHead()
      }
    def ended(outcome: BlockExecution.BatchOutcome, chunk: List[Block]): Option[ChunkedRun.Ended] =
      outcome.stateFault
        .map(ChunkedRun.Ended.Faulted(_))
        .orElse(outcome.error.map(error => ChunkedRun.Ended.Failed(chunk.drop(outcome.executed.size).head, error)))

    @tailrec
    def sides(
        remaining: List[(BigInt, BlockHash)],
        weight: ChainWeight,
        executed: Int
    ): (Int, ChainWeight, Option[ChunkedRun.Ended]) =
      if remaining.isEmpty then (executed, weight, None)
      else
        val (chunkKeys, rest) = remaining.splitAt(reorgChunkBlocks)
        val read = chunkKeys.map((number, hash) => blockchainReader.getBlockByHash(hash).toRight(number))
        read.collectFirst { case Left(number) => number } match
          case Some(number) =>
            val fault = new StateUnavailable(s"block $number, which the branch stands on, is not stored whole")
            (executed, weight, Some(ChunkedRun.Ended.Faulted(fault)))
          case None =>
            val chunk = read.collect { case Right(block) => block }
            val outcome = executeBranch(chunk, weight, adoptEachBlock, accessLists, pin)
            keep(outcome.executed)
            val carried = outcome.executed.lastOption.fold(weight)(_.weight)
            ended(outcome, chunk) match
              case Some(stop) => (executed + outcome.executed.size, carried, Some(stop))
              case None       => sides(rest, carried, executed + chunk.size)

    sides(toRun.sideParents, parentWeight, 0) match
      case (executed, _, Some(stop)) => ChunkedRun(executed, sideKept.toList, stop)
      case (executed, _, None) if toRun.branch.isEmpty =>
        ChunkedRun(executed, sideKept.toList, ChunkedRun.Ended.Whole)
      case (executed, weight, None) =>
        val outcome = executeBranch(toRun.branch, weight, adoptEachBlock, accessLists, pin)
        ChunkedRun(
          executed + outcome.executed.size,
          sideKept.toList ++ outcome.executed,
          ended(outcome, toRun.branch).getOrElse(ChunkedRun.Ended.Whole)
        )

  /** The best-block pointer, set to the last block of a batch that was adopted.
    *
    * '''On the unified state layout it writes nothing''' (spec 018 data-model 7.1): there the pointer moves with the
    * state, in the batch that commits each block, so it is at the last adopted block already. Written again here,
    * outside that batch and under no lock, it could only be wrong: after a batch that stopped because another writer
    * moved the head, it would put the pointer back on a block whose state the disk no longer holds.
    */
  private def saveLastBlock(blocks: List[BlockData]): Unit =
    if !blockchainReader.holdsStateInPlace then
      blocks.lastOption.foreach(b =>
        blockchainWriter.saveBestKnownBlocks(
          b.block.hash,
          b.block.number.value
        )
      )

  // Execute-first reorganise — reference client pattern (go-ethereum/Besu/Nethermind).
  // Old canonical blocks are NOT deleted before execution, and their state is never touched.
  // executeAndValidateBlocks writes blockNumberMappingStorage[N] = new_hash for each successfully executed block;
  // `settleHead` then decides, after execution, which head the node keeps and makes the canonical index exactly that
  // head's ancestry (core-geth writeBlockAndSetHead + reorg()). Old blocks stay in the DB either way.
  private def reorganise(
      bestBlockNumber: BigInt,
      bestBlockWeight: ChainWeight,
      newBranch: NonEmptyList[Block],
      parentWeight: ChainWeight,
      parentHash: BlockHash,
      selectedByWeight: Boolean,
      accessLists: Map[ByteString, BlockAccessList],
      bestBlockHash: BlockHash
  )(implicit
      blockchainConfig: BlockchainConfig
  ): ConsensusResult =
    if blockchainReader.holdsStateInPlace then
      reorganiseOnLayers(
        bestBlockNumber,
        bestBlockWeight,
        newBranch,
        parentWeight,
        parentHash,
        selectedByWeight,
        accessLists,
        bestBlockHash
      )
    else
      reorganiseOnTheHashLayout(
        bestBlockNumber,
        bestBlockWeight,
        newBranch,
        parentWeight,
        parentHash,
        selectedByWeight,
        accessLists
      )

  /** The reorganisation on the unified state layout (spec 018 C4e; the user's decision of 2026-11-12, plan.md "C4a part
    * 4"): execute first, on layers.
    *
    * The branch's blocks are executed apart, each kept as a forward layer beside the state the disk holds and stored by
    * its hash only, with the layers held by one pin until the branch is settled. Nothing of the head moves meanwhile:
    * its state, the best block and the index of blocks by number stay the old head's, as core-geth keeps its head until
    * a side chain's total difficulty exceeds it. Then, when what was executed outweighs the old head, '''one move of
    * the head''' takes the state, the best block and the index across (back along the records to the fork, forward over
    * the layers; `BlockchainWriter.moveHeadInPlace`). An executed prefix that does not outweigh it (a block of the
    * branch failed) leaves the old head exactly as it was, with the prefix's layers kept for the next attempt.
    *
    * '''Refused before anything is executed''' when the move back to the fork is not one this node can make: deeper
    * than its changeset records reach, past the finalized block or the block its state began at (the reverse depth,
    * never the branch's length). That is this node's own condition: the branch is not judged, and no block is reported
    * invalid. So is every fault of the state met on the way.
    *
    * The branch a consensus layer designates (`!selectedByWeight`, never on ETC; C4e part 5) is executed on layers as a
    * heavier one is, and becomes the head by one move when the whole of it is valid and still leads to the head the
    * consensus layer names. An invalid block of it leaves the head where it was, as on the hash layout since B-505. It
    * is never committed in place here: a branch longer than the executed blocks kept, or one that forks below the
    * views, is the fork choice's recovery's (`StateRecovery`), and is not judged.
    */
  private def reorganiseOnLayers(
      bestBlockNumber: BigInt,
      bestBlockWeight: ChainWeight,
      newBranch: NonEmptyList[Block],
      parentWeight: ChainWeight,
      parentHash: BlockHash,
      selectedByWeight: Boolean,
      accessLists: Map[ByteString, BlockAccessList],
      bestBlockHash: BlockHash
  )(implicit blockchainConfig: BlockchainConfig): ConsensusResult =
    def notJudged(fault: StateFault): ConsensusResult =
      fault match
        // no fault of this node's state: said here, once, with where the head is (the refusals of the move say nothing
        // of their own)
        case busy: StateBusy =>
          log.warn(
            "[STATE-BUSY] REORG blocks [{}-{}] not judged, and queued again; the best block is {}: {}",
            newBranch.head.number,
            newBranch.last.number,
            blockchainReader.getBestBlockNumber,
            busy.getMessage
          )
        case _ =>
          log.warn(
            s"${tagOf(fault)} REORG blocks [{}-{}] not judged, this node's own state could not take them: {}",
            newBranch.head.number,
            newBranch.last.number,
            fault.getMessage
          )
      // the whole branch waits to be tried again: what of it was executed is kept, by its layers, and is not executed
      // twice
      ConsensusStateFault(fault, Nil, newBranch.toList)

    val fork = locateFork(parentHash, newBranch.head.number.value - 1)
    val forkHeader =
      blockchainReader.getCanonicalHashByNumber(fork.number).flatMap(blockchainReader.getBlockHeaderByHash)
    // which arm this is, for what is logged: the proof-of-work rule's, or the consensus layer's head (part 5)
    val arm = if selectedByWeight then "A heavier branch" else "The consensus layer's branch"
    // Both arms: a move back to the fork that would be refused is refused before anything is executed, and that
    // holds the consensus layer's branch to the finalized block as it holds a heavier one to the records.
    val refusedUpFront: Option[StateFault] =
      forkHeader match
        case None =>
          Some(
            new StateUnavailable(s"the block the branch leaves the chain at, at height ${fork.number}, is not stored")
          )
        case Some(header) => blockchainWriter.refusalOfMoveBackTo(header, blockchainReader)
    refusedUpFront match
      case Some(refused) =>
        refused match
          case corrupt: ReorgTooDeep if corrupt.reason == ReorgRefusal.Corrupt =>
            // this datadir's own records, not the depth of the fork: said as that (forge)
            log.error(
              "[STATE-RECORDS-CORRUPT] {} [{}-{}] leaves the chain at block {}, and the changeset records " +
                "that lead back to it are not what they should be: {}. Nothing was executed and nothing moved",
              arm,
              newBranch.head.number,
              newBranch.last.number,
              fork.number,
              corrupt.getMessage
            )
          case tooDeep: ReorgTooDeep =>
            log.error(
              "[STATE-REORG-TOO-DEEP] {} [{}-{}] leaves the chain at block {}, {} block(s) below the " +
                "head {}: {}. Nothing was executed and nothing moved",
              arm,
              newBranch.head.number,
              newBranch.last.number,
              fork.number,
              bestBlockNumber - fork.number,
              bestBlockNumber,
              tooDeep.getMessage
            )
          case _ => ()
        refused match
          // The consensus layer's branch, refused for the finalized block or for a depth the records do not reach: no
          // attempt changes that while the head is where it is. It is not queued again and not fetched again (B-297);
          // its blocks are stored by hash, so that the next fork choice that names it walks down to its fork and is
          // answered as a head that cannot be reached, or -38002.
          case permanent: ReorgTooDeep if !selectedByWeight && ConsensusImpl.holdsWhileTheHeadStays(permanent.reason) =>
            refusedForThisHead(newBranch.toList, permanent, bestBlockHash)
          case _ => notJudged(refused)
      case None =>
        // the old chain above the fork by number and hash only: its blocks are read when the head has left it, and
        // only those that are told (B-296)
        val oldCanonical: List[(BigInt, BlockHash)] =
          ((fork.number + 1) to bestBlockNumber).toList.flatMap(n =>
            blockchainReader.getCanonicalHashByNumber(n).map(n -> _)
          )
        // A block the branch stands on that failed when it was executed again: refused at once, and not queued again
        // (below).
        val diverged = fork.parentSideChain.collectFirst {
          case (number, hash) if divergedParents.contains(hash) => number
        }
        if diverged.isDefined then
          log.warn(
            "[STATE-REEXEC-DIVERGED] REORG blocks [{}-{}] not judged: block {}, which the branch stands on, failed when " +
              "it was executed again. The branch is dropped until the node is started again",
            newBranch.head.number,
            newBranch.last.number,
            diverged.get
          )
          val divergence = new ReexecutionDiverged(
            s"block ${diverged.get}, which the branch stands on, failed when it was executed again"
          )
          if selectedByWeight then ConsensusStateFault(divergence, Nil, Nil)
          else refusedForThisHead(newBranch.toList, divergence, bestBlockHash)
        else
          // the consensus layer's branch handed to the fork choice's recovery at the cap: asked for once the pin is
          // released, so that the recovery does not find the pinned layers held and lose them as the pin goes (beacon)
          var handOffTip: Option[ByteString] = None
          val result =
            try
              blockchainWriter.withPinnedBranch(s"the reorganisation to block ${newBranch.last.number}") { pin =>
                // The blocks the branch stands on that are not of the head's chain (executed earlier, as a side branch)
                // and whose state is not held any more: a layer lives in memory, so a restart loses every one, and one
                // that did not fit was never kept. They are stored, and are executed again here, ahead of the branch and
                // under its pin, as core-geth executes a side chain's ancestors again when their state is gone (T180,
                // T187). From the lowest one whose state is not held up: what stands on it is not held either. Looked for
                // under the pin, so that nothing found held is evicted before it is used (forge).
                // Each is checked stored whole, as before; those whose state is not held are kept by number and hash
                // only, and read again a chunk at a time as they are executed (B-296, B-343): a side chain can be
                // thousands of blocks long.
                val lost: Either[StateFault, Option[LostSideParents]] = lostSideParents(fork)
                // what the cap is met with: the blocks already kept under the pin (of the side blocks, the last
                // `newBranchToldAtMost` only, which is all that is read of them: `sideParentsTold`), and those still to
                // run (C4e part 3)
                def atTheCap(why: StateFault, pinned: List[BlockData], remaining: ToRun): ConsensusResult =
                  forkHeader match
                    case Some(forkBlock) if selectedByWeight =>
                      commitInPlaceAtTheCap(
                        why,
                        pinned,
                        remaining,
                        newBranch,
                        fork,
                        forkBlock,
                        bestBlockNumber,
                        bestBlockWeight,
                        bestBlockHash,
                        oldCanonical,
                        accessLists,
                        notJudged
                      )
                    // The consensus layer's branch is not committed in place here: a branch longer than the executed
                    // blocks kept is adopted by the fork choice's recovery (`StateRecovery`), which owns that for proof
                    // of stake. A second adopter would write the same head beside it (beacon).
                    case Some(_) if !selectedByWeight =>
                      val detail =
                        s"the consensus layer's branch [${newBranch.head.number}-${newBranch.last.number}] is not " +
                          s"adopted from executed blocks kept beside the disk (${why.getMessage}): it is adopted by " +
                          "the fork choice's recovery"
                      // Only a branch longer than the pinned layers may hold. One that forks below what a view reaches
                      // is not handed off: the recovery's walk stops only at a state a view reaches, ends it as too
                      // long, and would leave that answer cached for the consensus client's own fork choice to its
                      // head (beacon, after code)
                      if why.isInstanceOf[BranchTooLarge] && branchHandOff.exists(_.isBound) then
                        // its blocks stored by hash, for the recovery to read; not a weight, which only a block
                        // executed has (beacon). The side blocks among them were read from the store: stored already
                        storeByHashOnly(remaining.branch)
                        handOffTip = Some(newBranch.last.hash.value)
                        log.info(
                          "REORG-HAND-OFF: {}; {} block(s) of it are stored by hash for the recovery",
                          detail,
                          remaining.size
                        )
                        ConsensusStateFault(new HandedToRecovery(detail), Nil, Nil)
                      else notJudged(new StateUnavailable(detail))
                    case _ => notJudged(why)
                // the lost side parents, executed again: what was kept of them, or how it ended
                // The block the first one to run stands on, when no block can be executed on its state: it is behind
                // what a view reaches (in the archive mode the records lead further back than views do; C4e part 4).
                // A move goes there by the records with no view, so the head is moved first and all is committed in
                // place: a heavier chain that forks that deep is otherwise never adopted.
                //
                // Only when that block is the fork, which the refusal up front found the records lead to. A side block
                // whose state was held a moment ago and is not now (its layer was evicted, or the answer could not be
                // had) is no reason to move the head: the branch waits, and the side block is executed again (forge).
                val notHeldUnder: Option[(BlockHeader, StateFault)] =
                  for
                    toRun <- lost.toOption
                    first = toRun.fold(newBranch.head.header)(_.first)
                    on <- blockchainReader.getBlockHeaderByHash(first.parentHash)
                    if !blockchainWriter.stateHeldInPlaceOf(on)
                  yield (
                    on,
                    new StateUnavailable(
                      s"no block can be executed on the state of block ${on.number}, which block ${first.number} " +
                        "stands on: it is not held, or is behind what a view of the state reaches"
                    )
                  )
                def lostParentsRun: Either[ConsensusResult, List[BlockData]] = lost match
                  case Left(missing) => Left(notJudged(missing))
                  case Right(None)   => Right(Nil)
                  case Right(Some(lostParents)) =>
                    log.info(
                      "REORG: {} block(s) the branch stands on, {} to {}, are executed again: their state is not held",
                      lostParents.chain.size,
                      lostParents.first.number,
                      lostParents.chain.last._1
                    )
                    blockchainReader.getChainWeightByHash(lostParents.first.parentHash) match
                      case None =>
                        Left(
                          notJudged(
                            new StateUnavailable(
                              s"the chain weight under block ${lostParents.first.number}, which the branch stands on, is not stored"
                            )
                          )
                        )
                      case Some(weight) =>
                        val parents =
                          executeInChunks(
                            ToRun(lostParents.chain, Nil, lostParents.first.parentHash),
                            weight,
                            adoptEachBlock = false,
                            Map.empty,
                            pin
                          )
                        parents.ended match
                          case ChunkedRun.Ended.Faulted(tooLarge: BranchTooLarge) =>
                            Left(
                              atTheCap(
                                tooLarge,
                                parents.kept,
                                ToRun(
                                  lostParents.chain.drop(parents.executedCount),
                                  newBranch.toList,
                                  // the last side block executed: the kept ones end with it
                                  parents.kept.lastOption.fold(lostParents.first.parentHash)(_.block.header.hash)
                                )
                              )
                            )
                          case ChunkedRun.Ended.Faulted(fault) => Left(notJudged(fault))
                          // A block that was executed and accepted before and fails now says nothing of the branch: this
                          // node's own fault, or a changed configuration. It is not reported invalid and the branch is
                          // not judged; it is remembered, and the branch not queued again, so that it is not executed
                          // again and again to the same end (until the escalation of C5a; forge).
                          case ChunkedRun.Ended.Failed(failing, error) =>
                            val diverged = divergedParent(failing, error)
                            Left(diverged match
                              case ConsensusStateFault(divergence, _, _) if !selectedByWeight =>
                                refusedForThisHead(newBranch.toList, divergence, bestBlockHash)
                              case other => other
                            )
                          // their receipts are not held while the branch runs: what is told of them is their blocks and
                          // weights (`sideParentsTold`, `commitInPlaceAtTheCap`; B-296)
                          case ChunkedRun.Ended.Whole => Right(parents.kept)
                val parentsRun: Either[ConsensusResult, List[BlockData]] = notHeldUnder match
                  case Some((on, why)) if forkHeader.exists(_.hash == on.hash) =>
                    Left(atTheCap(why, Nil, ToRun(lost.toOption.flatten.fold(Nil)(_.chain), newBranch.toList, on.hash)))
                  case Some((_, why)) => Left(notJudged(why))
                  case None           => lostParentsRun
                parentsRun match
                  case Left(ended) => ended
                  case Right(parentsKept) =>
                    val outcome =
                      executeBranch(newBranch.toList, parentWeight, adoptEachBlock = false, accessLists, pin)
                    outcome.stateFault match
                      case Some(tooLarge: BranchTooLarge) =>
                        atTheCap(
                          tooLarge,
                          parentsKept ++ outcome.executed,
                          ToRun(
                            Nil,
                            newBranch.toList.drop(outcome.executed.size),
                            outcome.executed.lastOption.fold(newBranch.head.header.parentHash)(_.block.header.hash)
                          )
                        )
                      case _ =>
                        settleOnLayers(
                          outcome,
                          newBranch,
                          parentWeight,
                          bestBlockNumber,
                          bestBlockWeight,
                          bestBlockHash,
                          fork,
                          oldCanonical,
                          sideParentsTold(fork, parentsKept),
                          selectedByWeight,
                          notJudged
                        )
              }
            catch case fault: StateFault => notJudged(fault)
          // only when that is what was answered: a fault after it (the pin's release) queues the branch again instead, or
          // is thrown, and the branch is then neither handed off nor set aside (B-318)
          val handedOff = result match
            case ConsensusStateFault(_: HandedToRecovery, _, _) => handOffTip
            case _                                              => None
          handedOff.foreach { tip =>
            // what hangs from it is set aside with it while the head stays: the recovery moves the head when it has
            // adopted it, and a branch fetched and executed again meanwhile would only pin the layers the recovery
            // needs (beacon, after code)
            rememberRefused(newBranch.toList, bestBlockHash)
            val why = s"the consensus layer's branch [${newBranch.head.number}-${newBranch.last.number}] at the cap"
            if !branchHandOff.exists(_.handOff(tip, why)) then
              log.warn(
                "REORG-HAND-OFF: no recovery took the branch [{}-{}]",
                newBranch.head.number,
                newBranch.last.number
              )
          }
          result

  /** A block the branch stands on, executed and accepted before, that fails now: remembered, logged, and answered as a
    * fault of this node with nothing to queue again.
    */
  private def divergedParent(failing: Block, error: BlockExecutionError): ConsensusResult =
    divergedParents.add(failing.header.hash)
    log.error(
      "[STATE-REEXEC-DIVERGED] Block {} ({}), which this node executed and accepted before, fails now that it is " +
        "executed again: {}. It is not reported invalid: this node's state or its configuration is not what it was",
      failing.number,
      failing.header.hash.toHexString,
      error.describe
    )
    ConsensusStateFault(
      new ReexecutionDiverged(
        s"block ${failing.number}, which the branch stands on and was executed before, fails now: ${error.describe}"
      ),
      Nil,
      Nil
    )

  /** A heavier branch that cannot be adopted from executed blocks kept beside the disk: **reverse-then-commit** (the
    * user's decision 1 of 2026-11-12; spec 018 C4e parts 3 and 4). Either its pinned layers would hold more than they
    * may (`BranchTooLarge`), or the block it is to be executed on is behind what a view of the state reaches (`why`
    * says which). Each is the same answer however often the branch is tried, and a node that cannot adopt the heaviest
    * chain has left it.
    *
    * With the changeset records above the fork held from retention, the head is moved with the state to the last block
    * kept under the pin (or to the block the first one stands on, when none was kept), and the blocks still to run are
    * executed, validated and committed in place, each as the head, as an import commits a block. So the head leaves the
    * old chain before the whole branch is known to be valid: it is on a chain of valid blocks at every step.
    *
    * What is left as the head (forge W4; the user's decision of 2026-11-14): when a block fails, the blocks already
    * committed stay the head if they outweigh the old head, as `settleOnLayers` keeps an executed prefix; otherwise the
    * head is moved back to the old head along the records held. On a fault of this node's state it is moved back
    * whatever was committed: nothing is left moved by a fault.
    *
    * The side blocks among `remaining` are read and committed a chunk at a time (B-343), and of `pinned` the side
    * blocks are the last few only ([[executeInChunks]]): what is answered reads no other.
    */
  private def commitInPlaceAtTheCap(
      why: StateFault,
      pinned: List[BlockData],
      remaining: ToRun,
      newBranch: NonEmptyList[Block],
      fork: ForkPoint,
      forkBlock: BlockHeader,
      bestBlockNumber: BigInt,
      bestBlockWeight: ChainWeight,
      bestBlockHash: BlockHash,
      oldCanonical: List[(BigInt, BlockHash)],
      accessLists: Map[ByteString, BlockAccessList],
      notJudged: StateFault => ConsensusResult
  )(implicit blockchainConfig: BlockchainConfig): ConsensusResult =
    val branchHashes = newBranch.toList.map(_.header.hash).toSet
    val start: Option[(BlockHeader, ChainWeight)] =
      for
        _ <- Option.when(remaining.nonEmpty)(())
        on <- blockchainReader.getBlockHeaderByHash(remaining.standsOn)
        weight <- blockchainReader.getChainWeightByHash(on.hash)
      yield (on, weight)
    // The head as it is now, which must be the one the branch was weighed against: that block, by its hash (B-315). A
    // head at the same height that is another block (moved back and a sibling committed) has another weight, and
    // `weight > bestBlockWeight` below would compare the branch with a head the node no longer holds. A block committed
    // on it, or a move of it, from here to the hold is refused by the hold itself (B-463).
    // Its number is that block's by its hash; the index must name it at that height.
    val headNow = blockchainReader.getBestBlockHeader.filter { head =>
      head.hash == bestBlockHash && blockchainReader.getBlockHeaderByNumber(bestBlockNumber).exists(_.hash == head.hash)
    }
    (headNow, start) match
      case (Some(oldHead), Some((on, weightOn))) =>
        log.warn(
          "REORG-AT-THE-CAP: the branch [{}-{}] is not adopted from executed blocks kept beside the disk ({}): the " +
            "head is moved to block {} and its {} remaining block(s) are committed in place. The fork is block {}",
          newBranch.head.number,
          newBranch.last.number,
          why.getMessage,
          on.number,
          remaining.size,
          forkBlock.number
        )
        // whether the head is the old head again: a move back that fails leaves it on a block of the branch
        def backToTheOldHead(): Boolean =
          try
            blockchainWriter.moveHeadInPlace(oldHead, blockchainReader)
            log.info("REORG-AT-THE-CAP: the head is back at block {}", oldHead.number)
            true
          catch
            case failed: StateFault =>
              log.error(
                "[STATE-FAULT] REORG-AT-THE-CAP: the head could not be moved back to block {} and is left at block {}, " +
                  "on a chain of valid blocks that does not outweigh the one it left: {}",
                oldHead.number,
                blockchainReader.getBestBlockNumber,
                failed.getMessage
              )
              false
        // What is answered when the head could not be moved back (the design's "a move back that fails is said",
        // B-439): where the head is, as a fault of this node, read from the head itself and not from where the move
        // was going. A move back can stop on the branch, at the fork, or on the old chain part of the way up. The blocks
        // of the branch the head stands on are imported (announced, the pool told, the escalation's feed told of the
        // head); every other block of either chain above the fork is queued again, so that the next offer adopts the
        // heavier one. `committed` is what this attempt kept or committed: a block of the head's chain it does not
        // carry (a commit that threw) is read from the store.
        def whereTheHeadIsLeft(committed: List[BlockData], after: String): ConsensusResult =
          val head = blockchainReader.getBestBlockHeader
          // the head's chain above the fork, by ancestry (`ancestry-not-index.md`): the index is not read
          val onTheHeadsChain: Set[BlockHash] =
            Iterator
              .iterate(head)(_.flatMap(header => blockchainReader.getBlockHeaderByHash(header.parentHash)))
              .takeWhile(_.exists(_.number.value > fork.number))
              .flatten
              .map(_.hash)
              .toSet
          val kept = committed.iterator.map(data => data.block.header.hash -> data).toMap
          val imported = newBranch.toList.filter(block => onTheHeadsChain(block.header.hash)).flatMap { block =>
            kept
              .get(block.header.hash)
              .orElse(
                for
                  receipts <- blockchainReader.getReceiptsByHash(block.header.hash)
                  weight <- blockchainReader.getChainWeightByHash(block.header.hash)
                yield BlockData(block, receipts, weight)
              )
          }
          // the side blocks the branch stands on that the head's chain holds (B-461): a commit of them that met the
          // fault, then a move back that failed, leaves the head there, on neither the branch nor the chain it left
          val sideBlocksOfTheHead: List[(BigInt, BlockHash)] =
            if onTheHeadsChain.exists(branchHashes) then Nil
            else fork.parentSideChain.filter((_, hash) => onTheHeadsChain(hash))
          val where = head.fold("is not known")(header =>
            if onTheHeadsChain.exists(branchHashes) then
              s"is at block ${header.number}, on the branch [${newBranch.head.number}-${newBranch.last.number}]"
            else if sideBlocksOfTheHead.nonEmpty then
              s"is at block ${header.number}, on the side blocks the branch " +
                s"[${newBranch.head.number}-${newBranch.last.number}] stands on"
            else if header.number.value > fork.number then s"is at block ${header.number}, on the chain it left"
            else s"is at block ${header.number}, the block the branch leaves the chain at"
          )
          // told as the head, as a reorganisation tells the side blocks it stands on: as many as it tells at most, each
          // with its weight, from what this attempt committed or else from the store
          val headOn: List[BlockData] =
            sideBlocksOfTheHead.takeRight(newBranchToldAtMost).flatMap { (_, hash) =>
              kept
                .get(hash)
                .orElse(
                  for
                    block <- blockchainReader.getBlockByHash(hash)
                    weight <- blockchainReader.getChainWeightByHash(hash)
                  yield BlockData(block, Nil, weight)
                )
            }
          log.error(
            "[STATE-FAULT] REORG-AT-THE-CAP: after {}, the head could not be moved back to block {} and {}: {} " +
              "block(s) of the branch are its head, the other blocks of both chains above block {} are queued again",
            after,
            oldHead.number,
            where,
            imported.size,
            fork.number
          )
          val fault = new StateUnavailable(
            s"after $after, the head could not be moved back to block ${oldHead.number} and $where"
          )
          val queued = oldBranchTold(oldCanonical.filterNot((_, hash) => onTheHeadsChain(hash))) ++
            newBranch.toList.filterNot(block => onTheHeadsChain(block.header.hash))
          if headOn.nonEmpty then new Consensus.HeadLeftOnSideBlocks(fault, queued, headOn)
          else ConsensusStateFault(fault, imported, queued)
        try
          // on the head read above, which the branch was weighed against and the move back leads to: a block committed
          // on it, or a move of it, since refuses the hold as busy, nothing written, and the branch is weighed again
          // (B-463)
          blockchainWriter.withRecordsHeldAbove(forkBlock, expectedHead = oldHead) {
            try
              // the layers of what was kept are still the pin's: the move applies them
              blockchainWriter.moveHeadInPlace(on, blockchainReader)
              val committed = executeInChunks(remaining, weightOn, adoptEachBlock = true, accessLists)
              val all = pinned ++ committed.kept
              val ofTheBranch = all.filter(data => branchHashes(data.block.header.hash))
              val fault: Option[StateFault] =
                committed.stateFault.orElse(committed.error.collect { case MPTError(reason) =>
                  new StateUnavailable(s"a trie read failed during the execution of the branch: $reason")
                })
              val weight = all.lastOption.map(_.weight).getOrElse(weightOn)
              // a block the branch stands on that fails now: as under the pin, this node's fault and not the branch's
              val failedParent: Option[(Block, BlockExecutionError)] =
                committed.ended match
                  case ChunkedRun.Ended.Failed(failing, error) if fault.isEmpty && !branchHashes(failing.header.hash) =>
                    Some((failing, error))
                  case _ => None
              (fault, failedParent) match
                case (Some(fault), _) =>
                  if backToTheOldHead() then notJudged(fault) else whereTheHeadIsLeft(all, fault.getMessage)
                case (None, Some((failing, error))) =>
                  val diverged = divergedParent(failing, error)
                  if backToTheOldHead() then diverged
                  else whereTheHeadIsLeft(all, s"block ${failing.number}, which the branch stands on, failed")
                case _ if weight > bestBlockWeight =>
                  log.info(
                    "REORG-SETTLE: fork={} oldHead={} tip={} committedInPlace={}",
                    forkBlock.number,
                    bestBlockNumber,
                    all.lastOption.fold(on.number.value)(_.block.number),
                    committed.executedCount
                  )
                  reorganised(
                    newBranch,
                    oldCanonical,
                    ofTheBranch,
                    committed.error,
                    sideParentsTold(fork, all),
                    adopted = true
                  )
                case _ if committed.error.isEmpty =>
                  // the whole branch ran and does not outweigh the head it was selected over: the weights stored
                  // disagree with the selection, which is no verdict on the branch
                  log.error(
                    "[STATE-FAULT] REORG-AT-THE-CAP: the branch [{}-{}] was selected by its weight and, executed, does " +
                      "not outweigh the old head {}: the chain weights stored do not agree",
                    newBranch.head.number,
                    newBranch.last.number,
                    bestBlockNumber
                  )
                  val disagrees = "the weight of the branch, executed, disagrees with its selection"
                  if backToTheOldHead() then notJudged(new StateUnavailable(disagrees))
                  else whereTheHeadIsLeft(all, disagrees)
                case _ =>
                  log.warn(
                    "REORG-PARTIAL: what of the branch [{}-{}] is valid does not outweigh the old head {}: the head is " +
                      "moved back to it",
                    newBranch.head.number,
                    newBranch.last.number,
                    bestBlockNumber
                  )
                  if backToTheOldHead() then reorganised(newBranch, Nil, ofTheBranch, committed.error)
                  else whereTheHeadIsLeft(all, "a block of the branch failed")
            catch
              // anything thrown here, a first move that stopped part of the way among it, would leave the head off
              // the old chain with the way back released: it goes back while the records are still held (forge)
              case NonFatal(thrown) =>
                // not when the first move was refused before it wrote: the head is the old head still. Back there,
                // what was thrown is the answer; a move back that fails is said, with where the head is (B-439), and
                // what was thrown is logged with it
                if blockchainReader.getBestBlockHeader.exists(_.hash == oldHead.hash) || backToTheOldHead() then
                  throw thrown
                log.error(
                  "[STATE-FAULT] REORG-AT-THE-CAP: the branch [{}-{}] was not adopted, and the head is not back",
                  newBranch.head.number,
                  newBranch.last.number,
                  thrown
                )
                whereTheHeadIsLeft(Nil, thrown.getMessage)
          }
        catch
          // the records above the fork left retention between the refusal up front and the hold
          case deep: ReorgTooDeep =>
            log.error(
              "[STATE-REORG-TOO-DEEP] The branch [{}-{}] leaves the chain at block {}, below what the changeset records " +
                "reach: {}",
              newBranch.head.number,
              newBranch.last.number,
              forkBlock.number,
              deep.getMessage
            )
            notJudged(deep)
      // Another head than the one the branch was weighed against: a block committed on it, or a move of it, since the
      // weight comparison (B-315). No fault of this node and no verdict on the branch: busy, as the hold refuses it
      // (B-463), and the branch is weighed again against the head as it is
      case (None, _) if blockchainReader.getBestBlockHeader.exists(_.hash != bestBlockHash) =>
        val now = blockchainReader.getBestBlockHeader.filter(_.hash != bestBlockHash)
        val where = now.fold("no block")(head => s"block ${head.number}")
        notJudged(
          new StateBusy(
            s"the writer that moved the head to $where",
            s"the branch [${newBranch.head.number}-${newBranch.last.number}] was not committed in place: the head is " +
              s"$where, not block $bestBlockNumber, which the branch was weighed against. Weigh it again against the " +
              "head as it is",
            // the head this refusal found: regular sync tries the branch again at once (BlockImporter)
            StateBusy.Cause.HeadMovedSinceWeighed(now.fold(bestBlockNumber)(_.number.value))
          )
        )
      case (None, _) =>
        notJudged(
          new StateUnavailable(s"the head is no longer block $bestBlockNumber, which the branch was weighed against")
        )
      case _ => notJudged(why)

  /** The blocks a branch stands on that are not of the head's chain (`fork.parentSideChain`) and become canonical with
    * it, oldest first, as go-ethereum's new chain runs from the fork up (B-297; beacon): those executed again here from
    * what was kept of them, the others as they are stored. Each with its own weight, so that blocks and weights told
    * stay one to one.
    *
    * '''Bounded''' (B-344): only the `oldBranchToldAtMost` nearest the branch are read, as no more of them can be told
    * ([[newBranchTold]] keeps that many of the new branch, the branch's own blocks included).
    */
  private def sideParentsTold(fork: ForkPoint, executedHere: List[BlockData]): List[BlockData] =
    val byHash = executedHere.iterator.map(data => data.block.header.hash -> data).toMap
    fork.parentSideChain.takeRight(newBranchToldAtMost).flatMap { case (number, hash) =>
      val told = byHash
        .get(hash)
        .orElse(
          for
            block <- blockchainReader.getBlockByHash(hash)
            weight <- blockchainReader.getChainWeightByHash(hash)
          // no receipts: what is told of the new branch is its blocks and weights (B-296)
          yield BlockData(block, Nil, weight)
        )
      // not met after a move that went through it: what is told then has a gap, said here (forge)
      if told.isEmpty then
        log.error(
          "REORG: block {} ({}), which the branch stands on and is canonical now, is not stored whole with its weight, " +
            "and is not told as of the new branch",
          number,
          hash.toHexString
        )
      told
    }

  /** The consensus layer's branch, refused for what no attempt changes while the head is where it is (B-297): its
    * blocks are stored by hash, remembered against the head, and nothing is queued again. The importer does not fetch
    * it again, and the batches that hang from it are refused with it ([[refusedWithTheirParent]]).
    */
  private def refusedForThisHead(branch: List[Block], cause: StateFault, head: BlockHash): ConsensusResult =
    storeByHashOnly(branch)
    rememberRefused(branch, head)
    log.warn(
      "[STATE-REFUSED-FOR-THIS-HEAD] The consensus layer's branch [{}-{}] is refused while the head is {}: {}. It is " +
        "not queued or fetched again",
      branch.headOption.fold("?")(_.number.value.toString),
      branch.lastOption.fold("?")(_.number.value.toString),
      head.toHexString,
      cause.getMessage
    )
    ConsensusStateFault(new RefusedForThisHead(cause), Nil, Nil)

  /** The blocks of `blocks` not stored yet, stored by hash only: header and body, no weight, no receipts, no index. */
  private def storeByHashOnly(blocks: List[Block]): Unit =
    blocks
      .filter(block => blockchainReader.getBlockHeaderByHash(block.header.hash).isEmpty)
      .foreach(block => blockchainWriter.storeBlockByHashOnly(block).commit())

  // the consensus layer's blocks refused while the head was the one each is mapped to (B-297); in memory only, bounded
  private val refusedBlocks =
    new java.util.LinkedHashMap[BlockHash, BlockHash](64, 0.75f, false):
      override def removeEldestEntry(eldest: java.util.Map.Entry[BlockHash, BlockHash]): Boolean =
        size() > ConsensusImpl.RefusedRemembered

  private def rememberRefused(blocks: Iterable[Block], head: BlockHash): Unit =
    refusedBlocks.synchronized(blocks.foreach(block => refusedBlocks.put(block.header.hash, head)))

  /** A refusal holds only while the head is the one it was made against: any move of the head, the clear of an
    * escalation among them, ends it.
    */
  override def refusedWithTheirParent(blocks: NonEmptyList[Block]): Boolean =
    blockchainReader.getBestBlockHeader.map(_.hash).exists { head =>
      refusedBlocks.synchronized {
        val refused = Option(refusedBlocks.get(blocks.head.header.parentHash)).contains(head)
        if refused then blocks.toList.foreach(block => refusedBlocks.put(block.header.hash, head))
        refused
      }
    }

  // the blocks a branch stood on that failed when they were executed again (reorganiseOnLayers); in memory only
  private val divergedParents = java.util.concurrent.ConcurrentHashMap.newKeySet[BlockHash]()

  /** `anchor`, the block whose state the disk holds as a move of the head to `target` is planned, when it is not the
    * head the weight arm's branch was weighed against: that head moved, and the best block with it (data-model 5.1,
    * I1), so the move is refused as busy and the branch weighed again (B-315). Asked as the move is admitted, with the
    * move under way: no commit lands meanwhile, and the best block is read as the plan's anchor was.
    *
    * A best block that is not `anchor` is no head that moved: a writer moved the best block or the state without the
    * other, as `PathStateStorage.bestIsTheAnchor` finds before a batch is written. Refused as busy, it would be refused
    * again at every offer and never be said; so it fails as that check fails, at ERROR, with nothing written.
    *
    * @throws IllegalStateException
    *   when the best block is not `anchor`
    */
  private def movedWithTheBestBlock(anchor: BlockRef, target: BigInt): BlockRef =
    val best = blockchainReader.getBestBlockHeader
    if !best.exists(head => head.hash.value == anchor.hash && head.number.value == anchor.number) then
      val bestIs = best.fold(s"${blockchainReader.getBestBlockNumber} (its header is not stored)")(head =>
        s"${head.number} (${head.hash.toHexString.drop(2).take(16)})"
      )
      log.error(
        "[STATE-MOVE] The best block is {} and the state is held at {}: a writer moved the one without the other. The " +
          "move of the head to block {} was not written",
        bestIs,
        anchor.show,
        target
      )
      throw new IllegalStateException(
        s"the move of the head to block $target was not written: the best block ($bestIs) is not the block the state " +
          s"is held at (${anchor.show})"
      )
    anchor

  /** What a branch executed on layers comes to: the head moved to it, kept, or a fault ([[reorganiseOnLayers]]). */
  private def settleOnLayers(
      outcome: BlockExecution.BatchOutcome,
      newBranch: NonEmptyList[Block],
      parentWeight: ChainWeight,
      bestBlockNumber: BigInt,
      bestBlockWeight: ChainWeight,
      bestBlockHash: BlockHash,
      fork: ForkPoint,
      oldCanonical: List[(BigInt, BlockHash)],
      sideParents: List[BlockData],
      selectedByWeight: Boolean,
      notJudged: StateFault => ConsensusResult
  ): ConsensusResult =
    val executed = outcome.executed
    // a trie error on this layout is the node's own state's, never the block's: a fault like any other
    val fault: Option[StateFault] = outcome.stateFault.orElse(outcome.error.collect { case MPTError(reason) =>
      new StateUnavailable(s"a trie read failed during the execution of the branch: $reason")
    })
    fault match
      // nothing is moved on a fault, whatever was executed before it: the old head stands, and the layers wait
      // for the next attempt (so the importer is never told of a fault with the head moved; forge)
      case Some(fault) => notJudged(fault)
      case None =>
        val executedWeight = executed.foldLeft(parentWeight)((w, b) => w.increase(b.block.header))
        // Whether the head goes to what was executed. By weight (proof of work): when it outweighs the head, a valid
        // prefix before an invalid block included (W4). By the consensus layer's head (part 5): only when the whole
        // branch is valid and still leads to the head it names; an invalid block leaves the head where the consensus
        // layer last had it (the user's decision of 2026-11-14), as nothing has moved yet.
        // the head the consensus layer names now: what the move is admitted against, with no walk when it is the same
        val namedAt = if selectedByWeight then None else designatedHead.flatMap(_.headBlockHash)
        val stillNamed = selectedByWeight || leadsToDesignatedHead(newBranch)
        val adopted =
          if selectedByWeight then executedWeight > bestBlockWeight
          else outcome.error.isEmpty && stillNamed
        // The consensus layer has named another head while its branch was executed: nothing is wrong with this node
        // or with the branch, whose blocks are valid and stay beside the head. Not a fault (beacon).
        def superseded(): ConsensusResult =
          log.info(
            "REORG-SUPERSEDED: the consensus layer names another head; its branch [{}-{}] was executed and is kept " +
              "beside the head {}",
            newBranch.head.number,
            newBranch.last.number,
            bestBlockNumber
          )
          KeptCurrentBestBranch
        executed.lastOption match
          // an invalid block is told whatever head is named now: what it is does not depend on that
          case Some(_) if !stillNamed && outcome.error.isEmpty => superseded()
          case Some(tip) if adopted                            =>
            // the move, before the pin is released: the layers it applies are still held. For the consensus layer's
            // branch it is refused before anything is written if another head was named meanwhile.
            var refusedAsSuperseded = false
            // the consensus layer's branch only: the head moved meanwhile, by a fork choice, past the branch's tip
            // (its chain holds the tip) or elsewhere. Without this the move would take the head back down, below a head
            // the consensus client had confirmed (beacon, B-297)
            // The head's chain holds the tip: a move with nothing forward, which would only take the head back down to
            // the tip. Refused whether the head got there meanwhile or before the branch was weighed (B-516). Both are
            // answered alike: the head never moves back and nothing is written. Where the head was past the tip before
            // the branch was weighed, its blocks were told when they became canonical, and the importer tells them once
            // more (the pool, new-head subscribers): harmless, and accepted so that no other answer is needed (B-516)
            var headAlreadyHoldsIt = false
            // The proof-of-work rule's branch only (B-315): the head it was weighed against is not the head the move
            // would leave. A block was committed on it, or it was moved, after the weight comparison. Moved anyway, the
            // node could take a lighter chain than the one it holds (core-geth weighs a side chain against the head it
            // holds under `chainmu`, and `reorg` follows from that comparison). Asked as the move is planned: a plan is
            // made from the head as it is, and its first batch is written only if the head is still that block, under
            // the state lock, or the move is planned and asked again. Refused as busy, with nothing written: no fault of
            // this node and no verdict on the branch, which is queued again and weighed against the head as it is. Only
            // when the best block moved with the state: a best block that did not is a defect, and fails at ERROR
            // ([[movedWithTheBestBlock]]).
            var weighedAgainstAnotherHead: Option[BlockRef] = None
            val moved =
              try
                Some(
                  blockchainWriter.moveHeadInPlace(
                    tip.block.header,
                    blockchainReader,
                    admit = plan =>
                      val headMoved = !selectedByWeight && plan.anchor.hash != bestBlockHash.value
                      weighedAgainstAnotherHead =
                        if selectedByWeight && plan.anchor.hash != bestBlockHash.value then
                          Some(movedWithTheBestBlock(plan.anchor, tip.block.number.value))
                        else None
                      headAlreadyHoldsIt = !selectedByWeight && plan.forward.isEmpty
                      val another = !selectedByWeight && (headMoved ||
                        designatedHead.flatMap(_.headBlockHash) != namedAt && !leadsToDesignatedHead(newBranch))
                      refusedAsSuperseded = another || headAlreadyHoldsIt
                      Option
                        .when(headMoved)(s"the head moved to block ${plan.anchor.show} meanwhile")
                        .orElse(
                          Option.when(headAlreadyHoldsIt)(s"the head ${plan.anchor.show} is past the branch's tip")
                        )
                        .orElse(Option.when(another)("the consensus layer names another head"))
                        .orElse(weighedAgainstAnotherHead.map(anchor => s"the head moved to block ${anchor.show}"))
                  )
                )
              catch
                // refused as it was admitted: nothing was written
                case _: StateFault if refusedAsSuperseded => None
                // said once, as busy, where the branch is queued again (`notJudged`)
                case _: StateFault if weighedAgainstAnotherHead.isDefined =>
                  val anchor = weighedAgainstAnotherHead.get
                  throw new StateBusy(
                    s"the writer that moved the head to block ${anchor.show}",
                    s"the move of the head to block ${tip.block.number} was not made: the head is at block " +
                      s"${anchor.show}, not at block $bestBlockNumber (${bestBlockHash.toHexString.drop(2).take(16)}), " +
                      "which the branch was weighed against. Weigh it again against the head as it is",
                    StateBusy.Cause.HeadMovedSinceWeighed(anchor.number)
                  )
                // refused before its first batch, with nothing written, because another thread's adoption holds the
                // head (B-294): no fault of this node, and the branch is asked for again once it ends (B-462). Said
                // once, as busy, where the branch is queued again (`notJudged`)
                case busy: StateBusy    => throw busy
                case failed: StateFault =>
                  // a move of several batches may have written some: where the head is now is said, and the
                  // next attempt moves on from it
                  log.error(
                    "[STATE-FAULT] REORG: the move of the head to block {} stopped, and the best block is now {}: {}",
                    tip.block.number,
                    blockchainReader.getBestBlockNumber,
                    failed.getMessage
                  )
                  throw failed
            moved match
              // the head is already on the branch, at or past its tip: a fork choice took it there, meanwhile or before
              // the branch was weighed (B-516), and the blocks of the head's chain it left are that move's to tell
              case None if headAlreadyHoldsIt =>
                log.info(
                  "REORG-SUPERSEDED: the head is on the consensus layer's branch [{}-{}] already, at or past its tip",
                  newBranch.head.number,
                  newBranch.last.number
                )
                SelectedNewBestBranch(Nil, executed.map(_.block), executed.map(_.weight), executed.size)
              case None => superseded()
              case Some(moved) =>
                log.info(
                  "REORG-SETTLE: fork={} oldHead={} tip={} executed={} ({})",
                  fork.number,
                  bestBlockNumber,
                  tip.block.number,
                  executed.size,
                  moved
                )
                reorganised(newBranch, oldCanonical, executed, outcome.error, sideParents, adopted = true)
          case Some(tip) =>
            log.warn(
              "REORG-PARTIAL: executed blocks {}-{} {} the current head {}: it is kept, and their state stays beside it",
              executed.head.block.number,
              tip.block.number,
              if selectedByWeight then "do not outweigh" else "end before an invalid block and do not replace",
              bestBlockNumber
            )
            reorganised(newBranch, Nil, executed, outcome.error)
          case None => reorganised(newBranch, Nil, executed, outcome.error)

  // Execute-first reorganise — reference client pattern (go-ethereum/Besu/Nethermind).
  private def reorganiseOnTheHashLayout(
      bestBlockNumber: BigInt,
      bestBlockWeight: ChainWeight,
      newBranch: NonEmptyList[Block],
      parentWeight: ChainWeight,
      parentHash: BlockHash,
      selectedByWeight: Boolean,
      accessLists: Map[ByteString, BlockAccessList]
  )(implicit
      blockchainConfig: BlockchainConfig
  ): ConsensusResult =
    log.debug(
      "Reorganise: collecting old block(s) from parent {} up to {}",
      ByteStringUtils.hash2string(parentHash.value),
      bestBlockNumber
    )

    // Read-only, and BEFORE execution overwrites any of it: where the branch leaves the canonical chain, and what the
    // canonical index holds above that point.
    val fork = locateFork(parentHash, newBranch.head.number.value - 1)
    val oldCanonical: List[(BigInt, BlockHash)] =
      ((fork.number + 1) to bestBlockNumber).toList.flatMap(n =>
        blockchainReader.getCanonicalHashByNumber(n).map(n -> _)
      )
    // And what it holds ABOVE the head (B-506): the entries `engine_newPayload` writes for a payload that extends the
    // index, ahead of the best block, or that a branch's commits left when a stop came before its settle. The branch's
    // blocks write over those at the heights they reach, so they are read now too.
    val aheadOfTheHead: List[(BigInt, BlockHash)] = blockchainWriter.indexAbove(bestBlockNumber, blockchainReader)

    // Execute new branch against the unchanged parent canonical state
    val outcome = executeBranch(newBranch.toList, parentWeight, adoptEachBlock = false, accessLists)
    val (executedBlocks, maybeError) = (outcome.executed, outcome.error)

    // The consensus layer's branch becomes the head only when the whole of it is valid and it still leads to the head
    // the consensus layer names, as on the unified layout (`settleOnLayers`; the user's decision of 2026-11-14) and in
    // go-ethereum, which sets a head only once it was inserted whole. A prefix executed before an invalid block or a
    // fault of this node's state, or a branch whose head is no longer named, is kept beside the head (B-505).
    val designatedAdopts =
      !selectedByWeight && maybeError.isEmpty && outcome.stateFault.isEmpty && leadsToDesignatedHead(newBranch)
    val adopted =
      settleHead(
        executedBlocks,
        parentWeight,
        bestBlockNumber,
        bestBlockWeight,
        fork,
        oldCanonical,
        aheadOfTheHead,
        selectedByWeight,
        designatedAdopts,
        stillNamed = () => leadsToDesignatedHead(newBranch)
      )

    outcome.stateFault match
      case Some(fault) =>
        // This node's state could not take a block of the branch: the branch was not judged. What of it was executed
        // is settled above as any executed prefix is; the rest is handed back, and nothing is reported invalid.
        log.warn(
          s"${tagOf(fault)} REORG blocks [{}-{}] stopped on this node's own state after {} block(s): {}",
          newBranch.head.number,
          newBranch.last.number,
          executedBlocks.length,
          fault.getMessage
        )
        // A prefix that outweighed the head became it before the fault (B-337): it is told as imported, and the blocks
        // it moved the head off are queued again with the blocks that were not judged, as a reorganisation that went
        // through tells them (and as `whereTheHeadIsLeft` tells a head left on a branch). A prefix that did not
        // outweigh the head, or of the consensus layer's branch (B-505), left it as it was, and only the rest is
        // handed back.
        val notJudged = newBranch.toList.drop(executedBlocks.length)
        if adopted && executedBlocks.nonEmpty then
          ConsensusStateFault(fault, executedBlocks, oldBranchTold(oldCanonical) ++ notJudged)
        else ConsensusStateFault(fault, Nil, notJudged)
      // The consensus layer named another head while its branch was executed: the branch is valid and stays beside the
      // head. Not a fault and not a verdict, as on the unified layout (beacon)
      case None if !selectedByWeight && maybeError.isEmpty && !adopted =>
        log.info(
          "REORG-SUPERSEDED: the consensus layer names another head; its branch [{}-{}] was executed and is kept " +
            "beside the head {}",
          newBranch.head.number,
          newBranch.last.number,
          bestBlockNumber
        )
        KeptCurrentBestBranch
      case None => reorganised(newBranch, oldCanonical, executedBlocks, maybeError, adopted = adopted)

  /** @param oldCanonical
    *   the old chain above the fork, by number and hash, as the index held it before the branch was executed: its
    *   blocks are read here, and only when the head has left it ([[oldBranchTold]])
    * @param sideParents
    *   the blocks the branch stands on that became canonical with it (on the unified layout; B-297), oldest first: told
    *   as of the new branch, ahead of it, when the whole branch was adopted
    * @param adopted
    *   whether the executed blocks became the head: a prefix that outweighed it, before a block of the branch was found
    *   invalid, is told as the reorganisation it is (B-291)
    */
  private def reorganised(
      newBranch: NonEmptyList[Block],
      oldCanonical: List[(BigInt, BlockHash)],
      executedBlocks: List[BlockData],
      maybeError: Option[BlockExecutionError],
      sideParents: List[BlockData] = Nil,
      adopted: Boolean = false
  ): ConsensusResult =
    maybeError match
      case None =>
        val told = newBranchTold(sideParents ++ executedBlocks)
        // the branch's own blocks are the last told: the side blocks it stands on come first, and are cut first
        SelectedNewBestBranch(
          oldBranchTold(oldCanonical),
          told.map(_.block),
          told.map(_.weight),
          math.min(executedBlocks.size, told.size)
        )

      case Some(MPTError(reason: MissingNodeException)) =>
        log.error(
          "REORG-EXEC-FAIL blocks [{}-{}]: MissingNode({}). The branch executes against the state of its parent " +
            "block {}; if that parent is (or was) canonical, its state may have been pruned: check pruning history vs " +
            "reorg depth and earlier failed reorg attempts",
          newBranch.head.number,
          newBranch.last.number,
          reason.getMessage,
          newBranch.head.number - 1
        )
        // B-337: a prefix that outweighed the head became it before the missing node. It is told as imported, so that
        // the retry starts at the block the node is missing under and whoever follows the head is told of it; the
        // blocks it moved the head off are queued again, as a reorganisation's are. The missing node is fetched as
        // ever. A prefix that did not outweigh the head left it as it was: its executed blocks are queued again.
        if adopted && executedBlocks.nonEmpty then
          ConsensusErrorDueToMissingNode(oldBranchTold(oldCanonical), reason, executedBlocks)
        else ConsensusErrorDueToMissingNode(executedBlocks.map(_.block), reason)

      case Some(error) =>
        log.error(
          "REORG-EXEC-FAIL blocks [{}-{}]: {}",
          newBranch.head.number,
          newBranch.last.number,
          error
        )
        val unexecuted = newBranch.toList.drop(executedBlocks.length)
        val failingBlock = unexecuted.head
        // Side-chain case. `failingBlock.header.parentHash` is a validated but (until this batch) non-canonical
        // block — precisely the latestValidHash hive's invalid-ancestor tests assert on.
        reportIfProvenInvalid(failingBlock, error, unexecuted.tail)
        val told = newBranchTold(sideParents ++ executedBlocks)
        val failure = s"$ReorganiseFailurePrefix$error"
        // B-291 (forge): the valid prefix outweighed the head and is the head now. The blocks it replaced left the
        // canonical chain, and are queued again as a reorganisation's are; whoever follows the head is told of it.
        // B-337: so too when what stopped it is this node's state fault (T062). The adapter answers that as the fault
        // the importer escalates it by, with the prefix as imported, and the failing block and those after it, which
        // were not judged, wait in the queue: none is dropped as an invalid block would be.
        val localFault = Consensus.isLocalStateFault(failure)
        if adopted && told.nonEmpty then
          SelectedNewBestBranchPartially(
            oldBranchTold(oldCanonical),
            told.map(_.block),
            told.map(_.weight),
            BranchExecutionFailure(if localFault then unexecuted else Nil, failingBlock.hash.value, failure)
          )
        else
          // the head stayed: the executed blocks are queued again, and on this node's state fault the blocks that were
          // not judged with them (B-336)
          val toEnqueue = if localFault then newBranch.toList else executedBlocks.map(_.block)
          BranchExecutionFailure(toEnqueue, failingBlock.hash.value, failure)

  /** Is the tip of this candidate branch on the path to the head the consensus layer designated?
    *
    * Delegates to the shared [[DesignatedHead.leadsToDesignatedHead]] so this and `BranchResolution.compareBranch` —
    * the two sites that must agree, because a branch has to pass both to be executed — cannot drift apart. False
    * whenever `designatedHead` is `None`, i.e. always on a PoW chain.
    */
  private def leadsToDesignatedHead(branch: NonEmptyList[Block]): Boolean =
    DesignatedHead.leadsToDesignatedHead(designatedHead, blockchainReader, branch.last.hash.value)

  private def newBranchWeight(newBranch: NonEmptyList[Block], parentWeight: ChainWeight) =
    newBranch.foldLeft(parentWeight)((w, b) => w.increase(b.header))

  private def returnNoTotalDifficultyForHeader(bestHeader: BlockHeader): IO[ConsensusError] =
    IO.pure(noTotalDifficultyForHeader(bestHeader))

  private def noTotalDifficultyForHeader(bestHeader: BlockHeader): ConsensusError =
    log.error(
      "Getting total difficulty for current best block with hash: {} failed",
      bestHeader.hashAsHexString
    )
    ConsensusError(
      Nil,
      s"Couldn't get total difficulty for current best block with hash: ${bestHeader.hashAsHexString}"
    )

  private def returnNoBestBlock(): IO[ConsensusError] = IO.pure(noBestBlock())

  private def noBestBlock(): ConsensusError =
    log.error("Getting current best block failed")
    ConsensusError(Nil, "Couldn't find the current best block")

  private def measureBlockMetrics(importResult: ConsensusResult): Unit =
    importResult match
      case ExtendedCurrentBestBranch(blockImportData) =>
        blockImportData.foreach(blockData => BlockMetrics.measure(blockData.block, blockchainReader.getBlockByHash))
      case SelectedNewBestBranch(_, newBranch, _, _) =>
        newBranch.foreach(block => BlockMetrics.measure(block, blockchainReader.getBlockByHash))
      case SelectedNewBestBranchPartially(_, newBranch, _, _) =>
        newBranch.foreach(block => BlockMetrics.measure(block, blockchainReader.getBlockByHash))
      case ConsensusErrorDueToMissingNode(_, _, adopted) =>
        adopted.foreach(blockData => BlockMetrics.measure(blockData.block, blockchainReader.getBlockByHash))
      case ConsensusStateFault(_, adopted, _) =>
        adopted.foreach(blockData => BlockMetrics.measure(blockData.block, blockchainReader.getBlockByHash))
      case _ => ()

  /** Decide which head the node keeps after executing (a prefix of) a new branch, and make the canonical index exactly
    * that head's ancestry. The index half of core-geth's `writeBlockAndSetHead` + `reorg()` (core/blockchain.go).
    *
    * Nothing executed: nothing was written, nothing changes.
    *
    * Branch chosen by the designated head alone (`selectedByWeight` false — the post-merge PoS arm, never taken on a
    * PoW chain): the last executed block becomes the head, written as the weight arm below writes an adoption (B-088),
    * only when `designatedAdopts`: the whole branch executed, valid, and still leading to the head the consensus layer
    * names, asked once more by `stillNamed` with the write, under the head's lock (B-511). Otherwise the old head stays
    * and the index is put back, as for a lighter prefix below (B-505; the unified layout and go-ethereum's SetCanonical
    * alike). It used to move only the best block, as before this method existed, and then (B-088 to B-505) adopted
    * whatever prefix was executed.
    *
    * Every arm that writes the index deletes, in the same batch, the lookups of the transactions that only the blocks
    * leaving the chain include (core-geth `reorg`: `types.HashDifference(deletedTxs, addedTxs)`; B-088).
    *
    * Branch chosen by weight (every ETC reorg):
    *   - the executed blocks outweigh the old head (always, when the whole branch executed): they become the head.
    *     Heights between the fork point and the branch parent are pointed at the parent's own ancestry (non-empty only
    *     when the parent was itself a non-canonical block), and every entry above the new head is deleted, up to the
    *     first height with none, not only up to the old head (B-506): so a shorter new chain leaves no old-chain hashes
    *     above it, nor the entries `aheadOfTheHead` held. Those are `engine_newPayload`'s on a post-merge chain; on ETC
    *     a branch's own commits leave them when the node stops between them and this settle, and a datadir written
    *     before this method existed may hold an old chain's.
    *   - they do not (execution stopped early, on a lighter prefix): the old head stays, as core-geth's `ReorgNeeded`
    *     would keep it, and the index is put back exactly as it was captured before execution, `aheadOfTheHead`
    *     included; heights it did not hold are deleted.
    *
    * Before this, best always moved to the last executed block and nothing else was rewritten, which left the node on a
    * lighter head with an index mixing two chains; see ReorgBlockhashParitySpec.
    */
  private def settleHead(
      executedBlocks: List[BlockData],
      parentWeight: ChainWeight,
      oldBestNumber: BigInt,
      oldBestWeight: ChainWeight,
      fork: ForkPoint,
      oldCanonical: List[(BigInt, BlockHash)],
      aheadOfTheHead: List[(BigInt, BlockHash)],
      selectedByWeight: Boolean,
      designatedAdopts: Boolean,
      stillNamed: () => Boolean
  ): Boolean =
    var adopted = false
    executedBlocks.lastOption.foreach { last =>
      val tip = last.block
      // A block this batch executed that the index ALSO named at its height before execution is not a displaced block:
      // it is the same canonical block, re-presented (a rewound fetcher re-serves the canonical prefix below the head,
      // and a branch that leaves the chain above that prefix carries it here). Its undo record was applied when it
      // first became canonical, its second commit was dropped as a duplicate, and it is canonical again in every arm
      // below. Undoing it would take out reference counts the head's own state still holds: under basic pruning its
      // nodes reach zero, sit on its death row, and are deleted `history` blocks later (#1592). So it is never
      // abandoned, whatever else the arm decides. Inert in archive and in-memory pruning, which ignore abandons.
      val executedIds = executedBlocks.iterator.map(b => (b.block.number.value, b.block.hash)).toSet
      val (reExecuted, displaced) = oldCanonical.partition(executedIds)
      log.info(
        "REORG-SETTLE: fork={} oldHead={} tip={} oldCanonical={} executed={} overlap={} selectedByWeight={}",
        fork.number,
        oldBestNumber,
        tip.number,
        oldCanonical.size,
        executedBlocks.size,
        reExecuted.size,
        selectedByWeight
      )
      // The entries that stood above the old head before execution and are no part of the tip's chain (B-506): those
      // above the fork point that the branch did not execute. At the heights the branch reached, its blocks wrote over
      // them, so they are known only from what was read before; above, the rewrite deletes them with whatever else is
      // there. Their blocks leave with the old chain's: their transactions lose their lookups, and their state changes
      // are taken out, as a fork choice's move takes out those of every entry it deletes above its head.
      val aheadLeft = aheadOfTheHead.filter((n, _) => n > fork.number).filterNot(executedIds)
      // The executed blocks become the head: the index is made the tip's ancestry (the side blocks the branch stands on
      // put back, every entry above the tip deleted, up to the first height with none: B-506), the best block is the
      // tip, and the transactions only the blocks the head left include lose their lookups, all in one batch (B-088).
      // The executed blocks wrote their own entries and lookups as they were committed, over the old chain's at the
      // heights the two share, so which blocks left there is `displaced` and `aheadLeft`, captured before execution;
      // at every other height the rewrite reads what it deletes or replaces with the write, and says it. For the
      // consensus layer's branch the write is refused if the head it names no longer descends from the tip, asked under
      // the head's lock with the write (B-511): a fork choice that came after the caller asked waits for that lock, and
      // its head is not adopted over.
      //
      // The side blocks the new branch builds on are canonical again (their changes were taken out when they were
      // executed as a side payload or dropped): their changes are put back once the write is admitted and before it is
      // written, so that a stop between the two leaves them counted and not indexed, which only keeps nodes longer,
      // never indexed and not counted, whose nodes the prune would delete under the head (B-511, vault). Idempotent
      // through the record's applied flag, so the branch made the head again after a stop counts nothing twice.
      def adoptTip(): Option[IndexRewrite] =
        blockchainWriter.rewriteCanonicalIndex(
          put = fork.parentSideChain,
          remove = ((tip.number.value + 1) to oldBestNumber).toList,
          newBest = Some((tip.hash, tip.number.value)),
          left = (displaced ++ aheadLeft).map(_._2),
          chain = fork.parentSideChain.map(_._2) ++ executedBlocks.map(_.block.hash),
          reader = blockchainReader,
          clearAbove = Some(tip.number.value.max(oldBestNumber)),
          admit = () =>
            Option.when(!selectedByWeight && !stillNamed())(
              "the consensus layer names another head, which does not descend from the tip"
            ),
          beforeTheWrite = () => reorgState.readoptBlockStates(fork.parentSideChain.map { case (n, h) => (n, h.value) })
        ) match
          case Right(rewrite) =>
            if rewrite.droppedTxLookups > 0 then
              log.info(
                "REORG-SETTLE: head=#{} {}: {} transaction lookup(s) of the {} block(s) it left dropped",
                tip.number,
                tip.hash.toHexString,
                rewrite.droppedTxLookups,
                displaced.size + aheadLeft.size
              )
            Some(rewrite)
          case Left(refused) =>
            log.info("REORG-SUPERSEDED: the head is not moved to block {}: {}", tip.number, refused)
            None
      // Whether the executed blocks become the head. The post-merge arm: when the caller found the whole branch valid
      // and still leading to the head the consensus layer names (B-505), and it still does as the index is written
      // (B-511). The weight arm: when they outweigh the old head, recomputed from the headers rather than read from
      // BlockData: it is exactly what BlockExecution computes, and it does not depend on the executor having filled
      // the weight in.
      val adopt =
        if selectedByWeight then
          executedBlocks.foldLeft(parentWeight)((w, b) => w.increase(b.block.header)) > oldBestWeight
        else designatedAdopts
      // The post-merge arm writes what the weight arm writes when it adopts (B-088): it used to move only the best
      // block, leaving the old chain's entries above the tip and below it at the side blocks' heights, and the
      // lookups of the transactions only the old chain held. A forkchoiceUpdated to the tip then found the index
      // naming the tip and rewrote nothing below it.
      (if adopt then adoptTip() else None) match
        case Some(rewrite) =>
          adopted = true
          // The old head's blocks left the canonical chain with their state changes applied: take them back out, or a
          // node only they replaced stays at zero references while the new chain uses it, and is pruned. Exactly the
          // blocks that left the index: those the executed blocks wrote over, known from before execution, and those
          // the rewrite deleted or replaced, as it read them with its write. Idempotent through the record's applied
          // flag.
          val executedHeights = executedBlocks.iterator.map(_.block.number.value).toSet
          val overwritten = (displaced ++ aheadLeft).filter((n, _) => executedHeights(n))
          reorgState.abandonBlockStates((overwritten ++ rewrite.removed).distinct.map { case (n, h) => (n, h.value) })
        case None =>
          keepTheOldHead(executedBlocks, oldBestNumber, fork, oldCanonical, aheadOfTheHead, selectedByWeight)
    }
    adopted

  /** The old head stays ([[settleHead]]'s arm for executed blocks that do not become the head): the index is put back
    * to the old head's ancestry, as it was captured before execution, and the executed side blocks leave it.
    *
    * Above the old head the index keeps only what the head's own chain may hold there (B-506):
    *   - the weight arm (every ETC reorg, and proof of work before the merge): nothing. core-geth's `reorg` deletes
    *     every hash marker that is not the head's ancestry; there an entry above the head is one a branch's commits
    *     left when the node stopped before its settle, or one a datadir from before the settle existed holds, of a side
    *     chain;
    *   - the consensus layer's arm: the entries `engine_newPayload` wrote for payloads that extend the index, the chain
    *     of them that links by parent hash up from the old head, which a forkchoiceUpdated may yet name. An entry that
    *     does not link (a stop left it, or a payload of another chain) ends that chain.
    *
    * Every entry above the old head that is not kept is deleted in the same batch as the rest, with the lookups of the
    * transactions only its block includes, and its block's state changes are taken out after, as for the executed side
    * blocks: the block left the chain the index names.
    */
  private def keepTheOldHead(
      executedBlocks: List[BlockData],
      oldBestNumber: BigInt,
      fork: ForkPoint,
      oldCanonical: List[(BigInt, BlockHash)],
      aheadOfTheHead: List[(BigInt, BlockHash)],
      selectedByWeight: Boolean
  ): Unit =
    val keptAbove: List[(BigInt, BlockHash)] =
      if selectedByWeight then Nil
      else
        blockchainReader.getBestBlockHeader.filter(_.number.value == oldBestNumber) match
          case None       => Nil
          case Some(head) => linkedUpFrom(head, aheadOfTheHead)
    // The index as it was captured before execution: the old head's chain above the fork, and what is kept of what
    // stood above the old head, which the branch's blocks wrote over at the heights they reached.
    val restored = oldCanonical ++ keptAbove
    val restoredIds = restored.toSet
    val restoredHeights = restored.map(_._1).toSet
    val droppedAhead = aheadOfTheHead.filterNot(restoredIds)
    val executedHeights = executedBlocks.map(_.block.number.value)
    log.warn(
      "REORG-PARTIAL: executed blocks {}-{} {} the current head {} — keeping it and restoring the canonical index{}",
      executedHeights.head,
      executedHeights.last,
      if selectedByWeight then "do not outweigh"
      else "are not the whole valid branch to the head the consensus layer names, and do not replace",
      oldBestNumber,
      if droppedAhead.isEmpty then ""
      else s"; ${droppedAhead.size} entr(ies) above it that are not its chain are deleted (B-506)"
    )
    // The executed side blocks are committed but not canonical. A re-presented canonical block among them, or a payload
    // the index named above the head that links up from it (one `engine_newPayload` executed and indexed first), is put
    // back in the index below, so it stays applied.
    val sideBlocks =
      executedBlocks.map(b => (b.block.number.value, b.block.hash)).filterNot(restoredIds)
    // All of the captured segment, not only the executed heights: that also re-points the transaction locations of
    // every old block, which a side block carrying the same transaction may have overwritten. The transactions only the
    // side blocks (and the side blocks the branch stands on, and the entries above the head that are not kept) include
    // lose the lookups their commits wrote (B-088): none of those blocks is canonical. Everything above the kept chain
    // goes, up to the first height with none, as core-geth's `reorg` deletes it.
    val rewrite = blockchainWriter.rewriteCanonicalIndex(
      put = restored,
      remove = (executedHeights ++ droppedAhead.map(_._1)).distinct.filterNot(restoredHeights),
      newBest = None,
      left = (fork.parentSideChain ++ sideBlocks ++ droppedAhead).map(_._2),
      chain = restored.map(_._2),
      reader = blockchainReader,
      clearAbove = Some(keptAbove.lastOption.fold(oldBestNumber)(_._1))
    )
    // the side blocks, the entries above the head that are not kept, and whatever else the rewrite took off the index;
    // never a block of the chain it put back
    val removed = rewrite.toOption.fold(Nil)(_.removed)
    reorgState.abandonBlockStates(
      (sideBlocks ++ droppedAhead ++ removed).distinct.filterNot(restoredIds).map { case (n, h) => (n, h.value) }
    )

  /** Of `ahead`, the entries above `head` in order of height, the chain that links by parent hash up from `head`: from
    * the entry at the height above it, while each names the child of the one below.
    */
  private def linkedUpFrom(head: BlockHeader, ahead: List[(BigInt, BlockHash)]): List[(BigInt, BlockHash)] =
    @tailrec
    def go(
        parent: BlockHash,
        number: BigInt,
        rest: List[(BigInt, BlockHash)],
        kept: List[(BigInt, BlockHash)]
    ): List[(BigInt, BlockHash)] =
      rest match
        case (n, hash) :: tail
            if n == number + 1 && blockchainReader.getBlockHeaderByHash(hash).exists(_.parentHash == parent) =>
          go(hash, n, tail, (n, hash) :: kept)
        case _ => kept.reverse
    go(head.hash, head.number.value, ahead.sortBy(_._1), Nil)

  /** Where a branch whose parent is `parentHash` leaves the canonical chain.
    *
    * Walks back from the parent by header until it reaches a block the canonical index names at its own height. When
    * the parent is canonical — the usual case — that is the parent itself and no header is read beyond it. When the
    * parent is a block that was executed earlier but is not canonical now (the node reorganised away from it), the walk
    * passes over exactly those non-canonical ancestors, so its length is bounded by that side chain.
    */
  private def locateFork(parentHash: BlockHash, parentNumber: BigInt): ForkPoint =
    @tailrec
    def go(hash: BlockHash, number: BigInt, sideChain: List[(BigInt, BlockHash)]): ForkPoint =
      if number < 0 || blockchainReader.getCanonicalHashByNumber(number).contains(hash) then
        ForkPoint(number, sideChain)
      else
        blockchainReader.getBlockHeaderByHash(hash) match
          case Some(header) if header.number.value == number =>
            go(header.parentHash, number - 1, (number, hash) :: sideChain)
          case _ =>
            log.error(
              "Reorganise: ancestry of the new branch is broken at height {} ({}); treating it as the fork point",
              number,
              hash.toHexString
            )
            ForkPoint(number, sideChain)
    go(parentHash, parentNumber, Nil)

  /** The blocks that left the canonical chain and are told (queued again, and their transactions handed back to the
    * pool), in ascending order, from the index entries captured above the fork point. Read-only; read only once the
    * head has left them, and used solely to populate the old branch of [[Consensus.SelectedNewBestBranch]] and its
    * partial form, and the blocks queued again when the head is left on the branch.
    *
    * '''Bounded''' (B-296): at most the `oldBranchToldAtMost` nearest the old head, so that a reorganisation thousands
    * of blocks deep reads and holds that many, not the whole old chain. Of a deeper old chain, the blocks below are not
    * queued again (the block queue drops blocks further than `max-queued-block-number-behind` from the head anyway) and
    * their transactions are not handed back to the pool (go-ethereum's pool skips its re-injection altogether when the
    * old and the new head are more than 64 blocks apart: `reset`, "Skipping deep transaction reorg"). They stay stored
    * by hash. Which head is chosen, and what is executed, is not touched by this.
    *
    * A block is told when its body, its receipts and its weight are stored, as it always was; the receipts are not read
    * (nothing reads an old block's receipts), only their presence is checked.
    *
    * It used to walk the index down from the best block until it met the branch parent, and stopped only at genesis
    * when it never did — i.e. whenever the parent was not the canonical block at its height.
    */
  private def oldBranchTold(oldCanonical: List[(BigInt, BlockHash)]): List[Block] =
    val told = oldCanonical.takeRight(oldBranchToldAtMost)
    if told.size < oldCanonical.size then
      log.warn(
        "REORG: {} block(s) left the canonical chain, {} to {}; the {} nearest the old head are queued again and " +
          "their transactions handed back to the pool, the {} below are not",
        oldCanonical.size,
        oldCanonical.head._1,
        oldCanonical.last._1,
        told.size,
        oldCanonical.size - told.size
      )
    told.flatMap { case (_, hash) =>
      for
        block <- blockchainReader.getBlockByHash(hash)
        if blockchainReader.hasReceipts(hash)
        _ <- blockchainReader.getChainWeightByHash(hash)
      yield block
    }

  // At least the new head: it is what the importer announces to peers and tells the escalation's feed of, whatever the
  // bound is configured to.
  private def newBranchToldAtMost: Int = math.max(1, oldBranchToldAtMost)

  /** What of the blocks that became canonical with a reorganisation is told (oldest first, the new head last): to the
    * pool, whose copies of their transactions are dropped, and, for those of the branch executed in this call only
    * (`branchBlocks`), to the subscribers of new heads, one each; a stored side block made canonical gets no new-head
    * event, as core-geth sends a chain event only for a block it inserts (B-344).
    *
    * '''Bounded''' (B-344), as the old branch is ([[oldBranchTold]]): at most the `oldBranchToldAtMost` nearest the new
    * head, at least the new head itself. A branch that stands on thousands of side blocks is not told block by block;
    * the blocks below stay canonical and are not touched by this. Peers are told of the new head only (the importer;
    * core-geth announces only the block it imported). Which head is chosen, and what is executed, is not touched by
    * this.
    */
  private def newBranchTold(newCanonical: List[BlockData]): List[BlockData] =
    val told = newCanonical.takeRight(newBranchToldAtMost)
    if told.size < newCanonical.size then
      log.warn(
        "REORG: {} block(s) became canonical, {} to {}; the {} nearest the new head are told to the pool (and those " +
          "executed now to new-head subscribers), the {} below are not",
        newCanonical.size,
        newCanonical.head.block.number,
        newCanonical.last.block.number,
        told.size,
        newCanonical.size - told.size
      )
    told

object ConsensusImpl:

  /** The tag a refusal is logged with: another thread's adoption holding the head is no fault of this node's state
    * (B-462). Shared with [[ConsensusAdapter]], whose answer to a fault thrown out of an evaluation says it too
    * (B-496).
    */
  private[consensus] def tagOf(fault: StateFault): String =
    if fault.isInstanceOf[StateBusy] then "[STATE-BUSY]" else "[STATE-FAULT]"

  /** @param number
    *   height of the last block the branch shares with the canonical chain
    * @param parentSideChain
    *   the branch parent's ancestors above `number` that the canonical index does NOT name (ascending), the parent
    *   included when it is one of them; empty when the parent is canonical
    */
  final private[consensus] case class ForkPoint(number: BigInt, parentSideChain: List[(BigInt, BlockHash)])

  /** The refusals of a move back that hold for as long as the head is where it is (B-297): below the finalized block,
    * or deeper than the records reach. `Corrupt` is this datadir's fault and waits.
    */
  private[consensus] def holdsWhileTheHeadStays(reason: ReorgRefusal): Boolean =
    reason == ReorgRefusal.Finalized || reason == ReorgRefusal.Deep || reason == ReorgRefusal.Incomplete ||
      reason == ReorgRefusal.Pivot

  /** How many refused blocks are remembered, at most. */
  private val RefusedRemembered: Int = 8192

  /** How many of the blocks a reorganisation moved the head off, and of those it moved the head onto, are told, at most
    * (B-296, B-344), when no bound is given: the default of `max-queued-block-number-behind`, past which the block
    * queue would not keep them when the new head is at the old one's height. A node is given that setting (B-345).
    */
  val OldBranchToldAtMost: Int = 1000

  /** How many of the side blocks a reorganisation executes again are read from the store and executed at a time
    * (B-343), when no other number is given. The default of `sync.blocks-batch-size`: no more blocks than one import
    * already holds in memory. What a deep side chain (a long private chain, after a restart) holds in memory at once is
    * then one chunk, with its receipts while it runs, and at most `newBranchToldAtMost` side blocks already executed,
    * kept without their receipts for what is told; not its depth. Which blocks are executed, in what order, and on what
    * state is not touched by it.
    */
  val ReorgChunkBlocks: Int = 50
