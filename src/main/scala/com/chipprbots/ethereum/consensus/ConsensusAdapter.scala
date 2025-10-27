package com.chipprbots.ethereum.consensus

import cats.data.NonEmptyList

import cats.effect.IO
import cats.effect.unsafe.IORuntime

import com.chipprbots.ethereum.blockchain.sync.regular.BlockEnqueued
import com.chipprbots.ethereum.blockchain.sync.regular.BlockImportFailed
import com.chipprbots.ethereum.blockchain.sync.regular.BlockImportFailedDueToMissingNode
import com.chipprbots.ethereum.blockchain.sync.regular.BlockImportResult
import com.chipprbots.ethereum.blockchain.sync.regular.BlockImportedToTop
import com.chipprbots.ethereum.blockchain.sync.regular.ChainReorganised
import com.chipprbots.ethereum.blockchain.sync.regular.DuplicateBlock
import com.chipprbots.ethereum.consensus.Consensus.BranchExecutionFailure
import com.chipprbots.ethereum.consensus.Consensus.ConsensusError
import com.chipprbots.ethereum.consensus.Consensus.ConsensusErrorDueToMissingNode
import com.chipprbots.ethereum.consensus.Consensus.ExtendedCurrentBestBranch
import com.chipprbots.ethereum.consensus.Consensus.ExtendedCurrentBestBranchPartially
import com.chipprbots.ethereum.consensus.Consensus.KeptCurrentBestBranch
import com.chipprbots.ethereum.consensus.Consensus.SelectedNewBestBranch
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.ledger.BlockExecutionError.ValidationBeforeExecError
import com.chipprbots.ethereum.ledger.BlockExecutionSuccess
import com.chipprbots.ethereum.ledger.BlockQueue
import com.chipprbots.ethereum.ledger.BlockValidation
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.FunctorOps._
import com.chipprbots.ethereum.utils.Hex
import com.chipprbots.ethereum.utils.Logger

/** This is a temporary class to isolate the real Consensus and extract responsibilities which should not be part of the
  * consensus in the final design, but are currently needed.
  */
class ConsensusAdapter(
    consensus: Consensus,
    blockchainReader: BlockchainReader,
    blockQueue: BlockQueue,
    blockValidation: BlockValidation,
    validationScheduler: IORuntime
) extends Logger {
  def evaluateBranchBlock(
      block: Block
  )(implicit blockExecutionScheduler: IORuntime, blockchainConfig: BlockchainConfig): IO[BlockImportResult] =
    blockchainReader.getBestBlock() match {
      case Some(bestBlock) =>
        if (isBlockADuplicate(block.header, bestBlock.header.number)) {
          log.debug("Ignoring duplicated block: {}", block.idTag)
          IO.pure(DuplicateBlock)
        } else if (blockchainReader.getChainWeightByHash(bestBlock.header.hash).isEmpty) {
          // This part is not really needed except for compatibility as a missing chain weight
          // would indicate an inconsistent database
          returnNoTotalDifficulty(bestBlock)
        } else {
          doBlockPreValidation(block).flatMap {
            case Left(error) =>
              IO.pure(BlockImportFailed(error.reason.toString))
            case Right(BlockExecutionSuccess) =>
              enqueueAndGetBranch(block, bestBlock.number)
                .map(forwardAndTranslateConsensusResult) // a new branch was created so we give it to consensus
                .getOrElse(IO.pure(BlockEnqueued)) // the block was not rooted so it was simply enqueued
          }
        }
      case None =>
        log.error("Couldn't find the current best block")
        IO.pure(BlockImportFailed("Couldn't find the current best block"))
    }

  private def forwardAndTranslateConsensusResult(
      newBranch: NonEmptyList[Block]
  )(implicit blockExecutionScheduler: IORuntime, blockchainConfig: BlockchainConfig) =
    consensus
      .evaluateBranch(newBranch)
      .map {
        case SelectedNewBestBranch(oldBranch, newBranch, weights) =>
          oldBranch.foreach(blockQueue.enqueueBlock(_))
          ChainReorganised(oldBranch, newBranch, weights)
        case ExtendedCurrentBestBranch(blockImportData) =>
          BlockImportedToTop(blockImportData)
        case ExtendedCurrentBestBranchPartially(
              blockImportData,
              BranchExecutionFailure(blocksToEnqueue, failingBlockHash, error)
            ) =>
          blocksToEnqueue.foreach(blockQueue.enqueueBlock(_))
          blockQueue.removeSubtree(failingBlockHash)
          log.warn("extended best branch partially because of error: {}", error)
          BlockImportedToTop(blockImportData)
        case KeptCurrentBestBranch =>
          newBranch.toList.foreach(blockQueue.enqueueBlock(_))
          BlockEnqueued
        case BranchExecutionFailure(blocksToEnqueue, failingBlockHash, error) =>
          blocksToEnqueue.foreach(blockQueue.enqueueBlock(_))
          blockQueue.removeSubtree(failingBlockHash)
          BlockImportFailed(error)
        case ConsensusError(blocksToEnqueue, error) =>
          blocksToEnqueue.foreach(blockQueue.enqueueBlock(_))
          BlockImportFailed(error)
        case ConsensusErrorDueToMissingNode(blocksToEnqueue, reason) =>
          blocksToEnqueue.foreach(blockQueue.enqueueBlock(_))
          BlockImportFailedDueToMissingNode(reason)
      }

  private def doBlockPreValidation(block: Block)(implicit
      blockchainConfig: BlockchainConfig
  ): IO[Either[ValidationBeforeExecError, BlockExecutionSuccess]] =
    IO
      .delay(blockValidation.validateBlockBeforeExecution(block))
      .flatTap {
        case Left(error) =>
          IO(log.error(
            "Error while validating block with hash {} before execution: {}",
            Hex.toHexString(block.hash.toArray),
            error.reason.toString
          ))
        case Right(_) => IO(log.debug("Block with hash {} validated successfully", Hex.toHexString(block.hash.toArray)))
      }
      .evalOn(validationScheduler)

  private def isBlockADuplicate(block: BlockHeader, currentBestBlockNumber: BigInt): Boolean = {
    val hash = block.hash
    blockchainReader.getBlockByHash(hash).isDefined &&
    block.number <= currentBestBlockNumber ||
    blockQueue.isQueued(hash)
  }

  private def enqueueAndGetBranch(block: Block, bestBlockNumber: BigInt): Option[NonEmptyList[Block]] =
    blockQueue
      .enqueueBlock(block, bestBlockNumber)
      .map(topBlock => blockQueue.getBranch(topBlock.hash, dequeue = true))
      .flatMap(NonEmptyList.fromList)

  private def returnNoTotalDifficulty(bestBlock: Block): IO[BlockImportFailed] = {
    log.error(
      "Getting total difficulty for current best block with hash: {} failed",
      bestBlock.header.hashAsHexString
    )
    IO.pure(
      BlockImportFailed(
        s"Couldn't get total difficulty for current best block with hash: ${bestBlock.header.hashAsHexString}"
      )
    )
  }
}
