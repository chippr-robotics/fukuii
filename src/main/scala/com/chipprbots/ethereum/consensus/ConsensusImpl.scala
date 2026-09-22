package com.chipprbots.ethereum.consensus

import cats.data.NonEmptyList
import cats.effect.IO
import cats.effect.unsafe.IORuntime

import scala.annotation.tailrec

import com.chipprbots.ethereum.consensus.Consensus.*
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
        if newBranchWeight(branch, parentWeight) > currentBestBlockWeight ||
          leadsToDesignatedHead(branch)
        then reorganise(currentBestBlockNumber, branch, parentWeight, parentHash)
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
  // Old canonical blocks are NOT deleted before execution. On failure, the old canonical
  // state is completely untouched — no revertChainReorganisation needed or possible.
  // executeAndValidateBlocks writes blockNumberMappingStorage[N] = new_hash for each
  // successfully executed block, so canonical pointers are updated atomically by execution.
  // On partial failure the chain advances to the last successful block; old stale entries
  // remain in DB (same as reference clients — GC'd by RocksDB compaction).
  private def reorganise(
      bestBlockNumber: BigInt,
      newBranch: NonEmptyList[Block],
      parentWeight: ChainWeight,
      parentHash: BlockHash
  )(implicit
      blockchainConfig: BlockchainConfig
  ): ConsensusResult =
    log.debug(
      "Reorganise: collecting old block(s) from parent {} up to {}",
      ByteStringUtils.hash2string(parentHash.value),
      bestBlockNumber
    )

    // Read old branch data without modifying DB — populate SelectedNewBestBranch return value
    val oldBlocksData = collectOldBranch(parentHash, bestBlockNumber)

    // Execute new branch against the unchanged parent canonical state
    val (executedBlocks, maybeError) = blockExecution.executeAndValidateBlocks(newBranch.toList, parentWeight)

    // Advance bestKnown to furthest successfully executed block (even on partial failure)
    executedBlocks.lastOption.foreach(b => blockchainWriter.saveBestKnownBlocks(b.block.hash, b.block.number.value))

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

  // Read-only traversal of the current canonical chain from fromNumber down to (exclusive) parent.
  // Does NOT delete or modify any DB state — used solely to populate SelectedNewBestBranch.
  private def collectOldBranch(parent: BlockHash, fromNumber: BigInt): List[BlockData] =
    @tailrec
    def go(parent: BlockHash, fromNumber: BigInt, acc: List[BlockData]): List[BlockData] =
      blockchainReader.getBlockByNumber(blockchainReader.getBestBranch, fromNumber) match
        case Some(block) if block.header.hash == parent || fromNumber == 0 =>
          acc

        case Some(block) =>
          val hash = block.header.hash
          val blockDataOpt = for
            receipts <- blockchainReader.getReceiptsByHash(hash)
            weight <- blockchainReader.getChainWeightByHash(hash)
          yield BlockData(block, receipts, weight)
          go(parent, fromNumber - 1, blockDataOpt.map(_ :: acc).getOrElse(acc))

        case None =>
          log.error(s"collectOldBranch: unexpected missing block at number $fromNumber")
          acc
    go(parent, fromNumber, Nil)
