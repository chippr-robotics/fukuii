package com.chipprbots.ethereum.consensus

import org.apache.pekko.util.ByteString

import cats.data.NonEmptyList
import cats.effect.IO
import cats.effect.unsafe.IORuntime

import scala.annotation.tailrec

import com.chipprbots.ethereum.consensus.Consensus._
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.domain.BlockchainWriter
import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.ledger.BlockData
import com.chipprbots.ethereum.ledger.BlockExecution
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
    blockExecution: BlockExecution
) extends Consensus
    with Logger {

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
    blockchainReader.getBestBlock().map(_.header).orElse(blockchainReader.getBestBlockHeader()) match {
      case Some(bestHeader) =>
        blockchainReader.getChainWeightByHash(bestHeader.hash) match {
          case Some(weight) => handleBranchImport(branch, bestHeader, weight)
          case None         => returnNoTotalDifficultyForHeader(bestHeader)
        }
      case None => returnNoBestBlock()
    }

  private def handleBranchImport(
      branch: NonEmptyList[Block],
      currentBestHeader: BlockHeader,
      currentBestBlockWeight: ChainWeight
  )(implicit
      blockExecutionScheduler: IORuntime,
      blockchainConfig: BlockchainConfig
  ): IO[ConsensusResult] = {

    val consensusResult: IO[ConsensusResult] =
      if (currentBestHeader.hash == branch.head.header.parentHash) {
        IO.delay(importToTop(branch, currentBestBlockWeight)).evalOn(blockExecutionScheduler.compute)
      } else {
        IO
          .delay(importToNewBranch(branch, currentBestHeader.number, currentBestBlockWeight))
          .evalOn(blockExecutionScheduler.compute)
      }

    consensusResult.flatTap(result => IO(measureBlockMetrics(result)))
  }

  private def importToNewBranch(
      branch: NonEmptyList[Block],
      currentBestBlockNumber: BigInt,
      currentBestBlockWeight: ChainWeight
  )(implicit
      blockchainConfig: BlockchainConfig
  ) = {
    val parentHash = branch.head.header.parentHash

    blockchainReader.getChainWeightByHash(parentHash) match {
      case Some(parentWeight) =>
        if (newBranchWeight(branch, parentWeight) > currentBestBlockWeight) {
          reorganise(currentBestBlockNumber, branch, parentWeight, parentHash)
        } else {
          KeptCurrentBestBranch
        }
      case None =>
        ConsensusError(
          branch.toList,
          s"Could not get weight for parent block ${Hex.toHexString(parentHash.toArray)} (number ${branch.head.number - 1})"
        )
    }
  }

  private def importToTop(branch: NonEmptyList[Block], currentBestBlockWeight: ChainWeight)(implicit
      blockchainConfig: BlockchainConfig
  ): ConsensusResult =
    blockExecution.executeAndValidateBlocks(branch.toList, currentBestBlockWeight) match {
      case (importedBlocks, None) =>
        saveLastBlock(importedBlocks)
        ExtendedCurrentBestBranch(importedBlocks)

      case (_, Some(MPTError(reason))) if reason.isInstanceOf[MissingNodeException] =>
        ConsensusErrorDueToMissingNode(Nil, reason.asInstanceOf[MissingNodeException])

      case (Nil, Some(error)) =>
        BranchExecutionFailure(Nil, branch.head.header.hash, error.toString)

      case (importedBlocks, Some(error)) =>
        saveLastBlock(importedBlocks)
        val failingBlock = branch.toList.drop(importedBlocks.length).head
        ExtendedCurrentBestBranchPartially(
          importedBlocks,
          BranchExecutionFailure(Nil, failingBlock.hash, error.toString)
        )
    }

  private def saveLastBlock(blocks: List[BlockData]): Unit = blocks.lastOption.foreach(b =>
    blockchainWriter.saveBestKnownBlocks(
      b.block.hash,
      b.block.number
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
      parentHash: ByteString
  )(implicit
      blockchainConfig: BlockchainConfig
  ): ConsensusResult = {
    log.debug(
      "Reorganise: collecting old block(s) from parent {} up to {}",
      ByteStringUtils.hash2string(parentHash),
      bestBlockNumber
    )

    // Read old branch data without modifying DB — populate SelectedNewBestBranch return value
    val oldBlocksData = collectOldBranch(parentHash, bestBlockNumber)

    // Execute new branch against the unchanged parent canonical state
    val (executedBlocks, maybeError) = blockExecution.executeAndValidateBlocks(newBranch.toList, parentWeight)

    // Advance bestKnown to furthest successfully executed block (even on partial failure)
    executedBlocks.lastOption.foreach(b => blockchainWriter.saveBestKnownBlocks(b.block.hash, b.block.number))

    maybeError match {
      case None =>
        SelectedNewBestBranch(oldBlocksData.map(_.block), executedBlocks.map(_.block), executedBlocks.map(_.weight))

      case Some(MPTError(reason)) if reason.isInstanceOf[MissingNodeException] =>
        log.error(
          "REORG-EXEC-FAIL blocks [{}-{}]: MissingNode({})",
          newBranch.head.number,
          newBranch.last.number,
          reason.getMessage
        )
        ConsensusErrorDueToMissingNode(executedBlocks.map(_.block), reason.asInstanceOf[MissingNodeException])

      case Some(error) =>
        log.error(
          "REORG-EXEC-FAIL blocks [{}-{}]: {}",
          newBranch.head.number,
          newBranch.last.number,
          error
        )
        BranchExecutionFailure(
          executedBlocks.map(_.block),
          newBranch.toList.drop(executedBlocks.length).head.hash,
          s"Error while trying to reorganise chain: $error"
        )
    }
  }

  private def newBranchWeight(newBranch: NonEmptyList[Block], parentWeight: ChainWeight) =
    newBranch.foldLeft(parentWeight)((w, b) => w.increase(b.header))

  private def returnNoTotalDifficultyForHeader(bestHeader: BlockHeader): IO[ConsensusError] = {
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
  }

  private def returnNoBestBlock(): IO[ConsensusError] = {
    log.error("Getting current best block failed")
    IO.pure(ConsensusError(Nil, "Couldn't find the current best block"))
  }

  private def measureBlockMetrics(importResult: ConsensusResult): Unit =
    importResult match {
      case ExtendedCurrentBestBranch(blockImportData) =>
        blockImportData.foreach(blockData => BlockMetrics.measure(blockData.block, blockchainReader.getBlockByHash))
      case SelectedNewBestBranch(_, newBranch, _) =>
        newBranch.foreach(block => BlockMetrics.measure(block, blockchainReader.getBlockByHash))
      case _ => ()
    }

  // Read-only traversal of the current canonical chain from fromNumber down to (exclusive) parent.
  // Does NOT delete or modify any DB state — used solely to populate SelectedNewBestBranch.
  private def collectOldBranch(parent: ByteString, fromNumber: BigInt): List[BlockData] = {
    @tailrec
    def go(parent: ByteString, fromNumber: BigInt, acc: List[BlockData]): List[BlockData] =
      blockchainReader.getBlockByNumber(blockchainReader.getBestBranch(), fromNumber) match {
        case Some(block) if block.header.hash == parent || fromNumber == 0 =>
          acc

        case Some(block) =>
          val hash = block.header.hash
          val blockDataOpt = for {
            receipts <- blockchainReader.getReceiptsByHash(hash)
            weight <- blockchainReader.getChainWeightByHash(hash)
          } yield BlockData(block, receipts, weight)
          go(parent, fromNumber - 1, blockDataOpt.map(_ :: acc).getOrElse(acc))

        case None =>
          log.error(s"collectOldBranch: unexpected missing block at number $fromNumber")
          acc
      }
    go(parent, fromNumber, Nil)
  }
}
