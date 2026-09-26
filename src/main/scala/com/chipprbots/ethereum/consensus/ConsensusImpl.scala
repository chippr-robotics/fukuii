package com.chipprbots.ethereum.consensus

import cats.data.NonEmptyList
import cats.effect.IO
import cats.effect.unsafe.IORuntime

import scala.annotation.tailrec

import com.chipprbots.ethereum.consensus.Consensus.*
import com.chipprbots.ethereum.consensus.ConsensusImpl.ForkPoint
import com.chipprbots.ethereum.consensus.engine.DesignatedHead
import com.chipprbots.ethereum.consensus.engine.InvalidChainReporter
import com.chipprbots.ethereum.domain.Block
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
    designatedHead: Option[DesignatedHead] = None
) extends Consensus
    with Logger:

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
    */
  override def evaluateBranch(
      branch: NonEmptyList[Block]
  )(implicit blockExecutionScheduler: IORuntime, blockchainConfig: BlockchainConfig): IO[ConsensusResult] =
    // Try the full-block lookup first (existing mock-based tests rely on it),
    // then fall back to header-only — that's the state right after PivotHeaderBootstrap
    // completes. handleBranchImport only consumes header.hash and header.number,
    // so a header is sufficient. Closes #1201's post-bootstrap follow-up.
    blockchainReader.getBestBlock.map(_.header).orElse(blockchainReader.getBestBlockHeader) match
      case Some(bestHeader) =>
        blockchainReader.getChainWeightByHash(bestHeader.hash) match
          case Some(weight) => handleBranchImport(branch, bestHeader, weight)
          case None         => returnNoTotalDifficultyForHeader(bestHeader)
      case None => returnNoBestBlock()

  private def handleBranchImport(
      branch: NonEmptyList[Block],
      currentBestHeader: BlockHeader,
      currentBestBlockWeight: ChainWeight
  )(implicit
      blockExecutionScheduler: IORuntime,
      blockchainConfig: BlockchainConfig
  ): IO[ConsensusResult] =

    val consensusResult: IO[ConsensusResult] =
      if currentBestHeader.hash == branch.head.header.parentHash then
        IO.delay(importToTop(branch, currentBestBlockWeight)).evalOn(blockExecutionScheduler.compute)
      else
        IO
          .delay(importToNewBranch(branch, currentBestHeader.number.value, currentBestBlockWeight))
          .evalOn(blockExecutionScheduler.compute)

    consensusResult.flatTap(result => IO(measureBlockMetrics(result)))

  private def importToNewBranch(
      branch: NonEmptyList[Block],
      currentBestBlockNumber: BigInt,
      currentBestBlockWeight: ChainWeight
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
          reorganise(currentBestBlockNumber, currentBestBlockWeight, branch, parentWeight, parentHash, selectedByWeight)
        else KeptCurrentBestBranch
      case None =>
        ConsensusError(
          branch.toList,
          s"Could not get weight for parent block ${Hex.toHexString(parentHash.toArray)} (number ${branch.head.number - 1})"
        )

  private def importToTop(branch: NonEmptyList[Block], currentBestBlockWeight: ChainWeight)(implicit
      blockchainConfig: BlockchainConfig
  ): ConsensusResult =
    blockExecution.executeAndValidateBlocks(branch.toList, currentBestBlockWeight) match
      case (importedBlocks, None) =>
        saveLastBlock(importedBlocks)
        ExtendedCurrentBestBranch(importedBlocks)

      case (_, Some(MPTError(reason: MissingNodeException))) =>
        ConsensusErrorDueToMissingNode(Nil, reason)

      case (Nil, Some(error)) =>
        // Nothing executed, so the failing block is the branch head and its parent is the current best block
        // (guaranteed by handleBranchImport's `currentBestHeader.hash == branch.head.header.parentHash` guard).
        reportIfProvenInvalid(branch.head, error, branch.tail)
        BranchExecutionFailure(Nil, branch.head.header.hash.value, error.toString)

      case (importedBlocks, Some(error)) =>
        saveLastBlock(importedBlocks)
        val unexecuted = branch.toList.drop(importedBlocks.length)
        val failingBlock = unexecuted.head
        // NB: this arm maps to `BlockImportedToTop` in ConsensusAdapter, which DISCARDS `error`. Reporting here is
        // the only signal that escapes a partially-successful batch at all.
        reportIfProvenInvalid(failingBlock, error, unexecuted.tail)
        ExtendedCurrentBestBranchPartially(
          importedBlocks,
          BranchExecutionFailure(Nil, failingBlock.hash.value, error.toString)
        )

  private def saveLastBlock(blocks: List[BlockData]): Unit = blocks.lastOption.foreach(b =>
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
      selectedByWeight: Boolean
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
    val oldBlocksData = collectOldBranch(oldCanonical)

    // Execute new branch against the unchanged parent canonical state
    val (executedBlocks, maybeError) = blockExecution.executeAndValidateBlocks(newBranch.toList, parentWeight)

    settleHead(executedBlocks, parentWeight, bestBlockNumber, bestBlockWeight, fork, oldCanonical, selectedByWeight)

    maybeError match
      case None =>
        SelectedNewBestBranch(oldBlocksData.map(_.block), executedBlocks.map(_.block), executedBlocks.map(_.weight))

      case Some(MPTError(reason: MissingNodeException)) =>
        log.error(
          "REORG-EXEC-FAIL blocks [{}-{}]: MissingNode({})",
          newBranch.head.number,
          newBranch.last.number,
          reason.getMessage
        )
        ConsensusErrorDueToMissingNode(executedBlocks.map(_.block), reason)

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
        BranchExecutionFailure(
          executedBlocks.map(_.block),
          failingBlock.hash.value,
          s"Error while trying to reorganise chain: $error"
        )

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
      case SelectedNewBestBranch(_, newBranch, _) =>
        newBranch.foreach(block => BlockMetrics.measure(block, blockchainReader.getBlockByHash))
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
  ): Unit =
    executedBlocks.lastOption.foreach { last =>
      val tip = last.block
      if !selectedByWeight then blockchainWriter.saveBestKnownBlocks(tip.hash, tip.number.value)
      else
        // Recomputed from the headers rather than read from BlockData: it is exactly what BlockExecution computes, and
        // it does not depend on the executor having filled the weight in.
        val executedWeight = executedBlocks.foldLeft(parentWeight)((w, b) => w.increase(b.block.header))
        if executedWeight > oldBestWeight then
          blockchainWriter.rewriteCanonicalIndex(
            put = fork.parentSideChain,
            remove = ((tip.number.value + 1) to oldBestNumber).toList,
            newBest = Some((tip.hash, tip.number.value)),
            reader = blockchainReader
          )
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
    }

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

  /** The blocks leaving the canonical chain, in ascending order, from the index entries captured above the fork point.
    * Bounded by the reorganisation depth. Read-only; used solely to populate SelectedNewBestBranch.
    *
    * It used to walk the index down from the best block until it met the branch parent, and stopped only at genesis
    * when it never did — i.e. whenever the parent was not the canonical block at its height.
    */
  private def collectOldBranch(oldCanonical: List[(BigInt, BlockHash)]): List[BlockData] =
    oldCanonical.flatMap { case (_, hash) =>
      for
        block <- blockchainReader.getBlockByHash(hash)
        receipts <- blockchainReader.getReceiptsByHash(hash)
        weight <- blockchainReader.getChainWeightByHash(hash)
      yield BlockData(block, receipts, weight)
    }

object ConsensusImpl:

  /** @param number
    *   height of the last block the branch shares with the canonical chain
    * @param parentSideChain
    *   the branch parent's ancestors above `number` that the canonical index does NOT name (ascending), the parent
    *   included when it is one of them; empty when the parent is canonical
    */
  final private[consensus] case class ForkPoint(number: BigInt, parentSideChain: List[(BigInt, BlockHash)])
