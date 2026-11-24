package com.chipprbots.ethereum.consensus

import org.apache.pekko.util.ByteString

import cats.data.NonEmptyList
import cats.effect.IO
import cats.effect.unsafe.IORuntime

import scala.annotation.tailrec
import scala.util.control.NonFatal

import com.chipprbots.ethereum.consensus.Consensus.*
import com.chipprbots.ethereum.consensus.ConsensusImpl.ForkPoint
import com.chipprbots.ethereum.consensus.engine.BranchHandOff
import com.chipprbots.ethereum.consensus.engine.DesignatedHead
import com.chipprbots.ethereum.consensus.engine.InvalidChainReporter
import com.chipprbots.ethereum.db.storage.pathstate.BranchPin
import com.chipprbots.ethereum.db.storage.pathstate.BranchTooLarge
import com.chipprbots.ethereum.db.storage.pathstate.HandedToRecovery
import com.chipprbots.ethereum.db.storage.pathstate.ReexecutionDiverged
import com.chipprbots.ethereum.db.storage.pathstate.RefusedForThisHead
import com.chipprbots.ethereum.db.storage.pathstate.ReorgRefusal
import com.chipprbots.ethereum.db.storage.pathstate.ReorgTooDeep
import com.chipprbots.ethereum.db.storage.pathstate.StateFault
import com.chipprbots.ethereum.db.storage.pathstate.StateUnavailable
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.BlockAccessList
import com.chipprbots.ethereum.domain.BlockHash
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.domain.BlockchainWriter
import com.chipprbots.ethereum.domain.ChainWeight
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
    reorgChunkBlocks: Int = ConsensusImpl.ReorgChunkBlocks
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
      if currentBestHeader.hash == branch.head.header.parentHash then
        IO.delay(importToTop(branch, currentBestBlockWeight, accessLists)).evalOn(blockExecutionScheduler.compute)
      else if blockchainReader.holdsStateInPlace then
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
        IO
          .delay(
            importToNewBranch(
              branch,
              currentBestHeader.number.value,
              currentBestBlockWeight,
              accessLists,
              currentBestHeader.hash
            )
          )
          .evalOn(blockExecutionScheduler.compute)

    consensusResult.flatTap(result => IO(measureBlockMetrics(result)))

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
    // PoS only: an Engine API payload that extended the head wrote its index entry ahead of the best block
    // (`engine_newPayload` does, the best pointer moving only on forkchoiceUpdated). A different block imported at that
    // height replaces the entry, and the payload is then non-canonical with its reference counts still applied. Read
    // before execution overwrites the entries; on a PoW chain `followsConsensusLayer` is false and nothing is read.
    val displaced: List[(BigInt, BlockHash)] =
      if followsConsensusLayer then
        branch.toList.flatMap(b =>
          blockchainReader.getCanonicalHashByNumber(b.number.value).filterNot(_ == b.hash).map(b.number.value -> _)
        )
      else Nil
    val outcome = executeBranch(branch.toList, currentBestBlockWeight, adoptEachBlock = true, accessLists)
    if displaced.nonEmpty then
      val executed = outcome.executed.map(_.block.number.value).toSet
      reorgState.abandonBlockStates(displaced.collect { case (n, h) if executed(n) => (n, h.value) })
    outcome.stateFault match
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
      "[STATE-FAULT] the import stopped at block {} on this node's own state, after {} block(s) of the batch: {}",
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
        // NB: for a block's own error this arm maps to `BlockImportedToTop` in ConsensusAdapter, which DISCARDS
        // `error`. Reporting here is the only signal that escapes a partially-successful batch at all.
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
    * consensus layer names. An invalid block of it leaves the head where it was (the hash layout makes the valid prefix
    * the head). It is never committed in place here: a branch longer than the executed blocks kept, or one that forks
    * below the views, is the fork choice's recovery's (`StateRecovery`), and is not judged.
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
      log.warn(
        "[STATE-FAULT] REORG blocks [{}-{}] not judged, this node's own state could not take them: {}",
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
                        // and what hangs from it is set aside with it while the head stays: the recovery moves the
                        // head when it has adopted it, and a branch fetched and executed again meanwhile would only
                        // pin the layers the recovery needs (beacon, after code)
                        rememberRefused(newBranch.toList, bestBlockHash)
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
          // only when that is what was answered: a fault after it (the pin's release) queues the branch again instead
          val handedOff = result match
            case ConsensusStateFault(_: HandedToRecovery, _, _) => handOffTip
            case _                                              => None
          handedOff.foreach { tip =>
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
    // the head as it is now, which must be the one the branch was weighed against: nothing else moved it meanwhile
    val headNow = blockchainReader.getBestBlockHeader.filter { head =>
      head.number.value == bestBlockNumber &&
      blockchainReader.getBlockHeaderByNumber(bestBlockNumber).exists(_.hash == head.hash)
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
          val where = head.fold("is not known")(header =>
            if onTheHeadsChain.exists(branchHashes) then
              s"is at block ${header.number}, on the branch [${newBranch.head.number}-${newBranch.last.number}]"
            else if header.number.value > fork.number then s"is at block ${header.number}, on the chain it left"
            else s"is at block ${header.number}, the block the branch leaves the chain at"
          )
          log.error(
            "[STATE-FAULT] REORG-AT-THE-CAP: after {}, the head could not be moved back to block {} and {}: {} " +
              "block(s) of the branch are its head, the other blocks of both chains above block {} are queued again",
            after,
            oldHead.number,
            where,
            imported.size,
            fork.number
          )
          ConsensusStateFault(
            new StateUnavailable(
              s"after $after, the head could not be moved back to block ${oldHead.number} and $where"
            ),
            imported,
            oldBranchTold(oldCanonical.filterNot((_, hash) => onTheHeadsChain(hash))) ++
              newBranch.toList.filterNot(block => onTheHeadsChain(block.header.hash))
          )
        try
          blockchainWriter.withRecordsHeldAbove(forkBlock) {
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
            var headAlreadyHoldsIt = false
            val moved =
              try
                Some(
                  blockchainWriter.moveHeadInPlace(
                    tip.block.header,
                    blockchainReader,
                    admit = plan =>
                      val headMoved = !selectedByWeight && plan.anchor.hash != bestBlockHash.value
                      headAlreadyHoldsIt = headMoved && plan.forward.isEmpty
                      val another = !selectedByWeight && (headMoved ||
                        designatedHead.flatMap(_.headBlockHash) != namedAt && !leadsToDesignatedHead(newBranch))
                      refusedAsSuperseded = another
                      Option
                        .when(headMoved)(s"the head moved to block ${plan.anchor.show} meanwhile")
                        .orElse(Option.when(another)("the consensus layer names another head"))
                  )
                )
              catch
                // refused as it was admitted: nothing was written
                case _: StateFault if refusedAsSuperseded => None
                case failed: StateFault                   =>
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
              // the head is already on the branch, past its tip: a fork choice moved it there meanwhile, and the
              // blocks of the head's chain it left are that move's to tell
              case None if headAlreadyHoldsIt =>
                log.info(
                  "REORG-SUPERSEDED: the head moved to the consensus layer's branch [{}-{}] meanwhile, past its tip",
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

    // Execute new branch against the unchanged parent canonical state
    val outcome = executeBranch(newBranch.toList, parentWeight, adoptEachBlock = false, accessLists)
    val (executedBlocks, maybeError) = (outcome.executed, outcome.error)

    val adopted =
      settleHead(executedBlocks, parentWeight, bestBlockNumber, bestBlockWeight, fork, oldCanonical, selectedByWeight)

    outcome.stateFault match
      case Some(fault) =>
        // This node's state could not take a block of the branch: the branch was not judged. What of it was executed
        // is settled above as any executed prefix is; the rest is handed back, and nothing is reported invalid.
        log.warn(
          "[STATE-FAULT] REORG blocks [{}-{}] stopped on this node's own state after {} block(s): {}",
          newBranch.head.number,
          newBranch.last.number,
          executedBlocks.length,
          fault.getMessage
        )
        // A prefix that outweighed the head became it before the fault (B-337): it is told as imported, and the blocks
        // it moved the head off are queued again with the blocks that were not judged, as a reorganisation that went
        // through tells them (and as `whereTheHeadIsLeft` tells a head left on a branch). A prefix that did not
        // outweigh the head left it as it was, and only the rest is handed back.
        val notJudged = newBranch.toList.drop(executedBlocks.length)
        if adopted && executedBlocks.nonEmpty then
          ConsensusStateFault(fault, executedBlocks, oldBranchTold(oldCanonical) ++ notJudged)
        else ConsensusStateFault(fault, Nil, notJudged)
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
    log.error(
      "Getting total difficulty for current best block with hash: {} failed",
      bestHeader.hashAsHexString
    )
    IO.pure(
      ConsensusError(
        Nil,
        s"Couldn't get total difficulty for current best block with hash: ${bestHeader.hashAsHexString}"
      )
    )

  private def returnNoBestBlock(): IO[ConsensusError] =
    log.error("Getting current best block failed")
    IO.pure(ConsensusError(Nil, "Couldn't find the current best block"))

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
    * PoW chain): best moves to the last executed block, exactly as before this method existed. That arm belongs to the
    * ETH fork choice and is deliberately left as it was.
    *
    * Branch chosen by weight (every ETC reorg):
    *   - the executed blocks outweigh the old head (always, when the whole branch executed): they become the head.
    *     Heights between the fork point and the branch parent are pointed at the parent's own ancestry (non-empty only
    *     when the parent was itself a non-canonical block), and every entry above the new head is deleted — so a
    *     shorter new chain leaves no old-chain hashes above it.
    *   - they do not (execution stopped early, on a lighter prefix): the old head stays, as core-geth's `ReorgNeeded`
    *     would keep it, and the index is put back exactly as it was captured before execution; heights the old chain
    *     never reached are deleted.
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
      selectedByWeight: Boolean
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
      if !selectedByWeight then
        adopted = true
        blockchainWriter.saveBestKnownBlocks(tip.hash, tip.number.value)
        // The head moved to the new branch, so the old head's blocks above the fork point left the canonical chain with
        // their reference-count changes applied: take them back out, and put back an already-executed side parent the
        // branch builds on (undone when it was executed as a side payload or dropped). Counts only; the index is
        // deliberately left as this arm always left it. Idempotent through the record's applied flag.
        reorgState.abandonBlockStates(displaced.map { case (n, h) => (n, h.value) })
        reorgState.readoptBlockStates(fork.parentSideChain.map { case (n, h) => (n, h.value) })
      else
        // Recomputed from the headers rather than read from BlockData: it is exactly what BlockExecution computes, and
        // it does not depend on the executor having filled the weight in.
        val executedWeight = executedBlocks.foldLeft(parentWeight)((w, b) => w.increase(b.block.header))
        if executedWeight > oldBestWeight then
          adopted = true
          blockchainWriter.rewriteCanonicalIndex(
            put = fork.parentSideChain,
            remove = ((tip.number.value + 1) to oldBestNumber).toList,
            newBest = Some((tip.hash, tip.number.value)),
            reader = blockchainReader
          )
          // The old head's blocks left the canonical chain with their state changes applied: take them back out, or a
          // node only they replaced stays at zero references while the new chain uses it, and is pruned. The side
          // blocks the new branch builds on are canonical again (their changes were taken out when they were dropped).
          reorgState.abandonBlockStates(displaced.map { case (n, h) => (n, h.value) })
          reorgState.readoptBlockStates(fork.parentSideChain.map { case (n, h) => (n, h.value) })
        else
          val old = oldCanonical.toMap
          val executedHeights = executedBlocks.map(_.block.number.value)
          log.warn(
            "REORG-PARTIAL: executed blocks {}-{} do not outweigh the current head {} — keeping it and restoring the " +
              "canonical index",
            executedHeights.head,
            executedHeights.last,
            oldBestNumber
          )
          // All of the captured segment, not only the executed heights: that also re-points the transaction
          // locations of every old block, which a side block carrying the same transaction may have overwritten.
          blockchainWriter.rewriteCanonicalIndex(
            put = oldCanonical,
            remove = executedHeights.filterNot(old.contains),
            newBest = None,
            reader = blockchainReader
          )
          // The executed side blocks are committed but not canonical. A re-presented canonical block among them is put
          // back in the index just above, so it stays applied.
          val sideBlocks =
            executedBlocks.map(b => (b.block.number.value, b.block.hash)).filterNot(id => oldCanonical.contains(id))
          reorgState.abandonBlockStates(sideBlocks.map { case (n, h) => (n, h.value) })
    }
    adopted

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
