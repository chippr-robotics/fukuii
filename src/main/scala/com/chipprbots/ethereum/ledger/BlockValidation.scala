package com.chipprbots.ethereum.ledger

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.consensus.mining.Mining
import com.chipprbots.ethereum.consensus.validators.BlockHeaderError.HeaderParentNotFoundError
import com.chipprbots.ethereum.consensus.validators.BlockHeaderValidatorSkeleton
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.BlockHash
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockNumber
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.domain.Receipt
import com.chipprbots.ethereum.ledger.BlockExecutionError.ValidationBeforeExecError
import com.chipprbots.ethereum.utils.BlockchainConfig

class BlockValidation(
    mining: Mining,
    blockchainReader: BlockchainReader,
    blockQueue: BlockQueue
):

  def validateBlockBeforeExecution(
      block: Block
  )(implicit blockchainConfig: BlockchainConfig): Either[ValidationBeforeExecError, BlockExecutionSuccess] =
    mining.validators.validateBlockBeforeExecution(
      block = block,
      getBlockHeaderByHash = getBlockHeaderFromChainOrQueue,
      getNBlocksBack = getNBlocksBackFromChainOrQueue
    )

  /** The headers of `branch`, each validated against its parent as an import validates it, the seal with it: the first
    * that fails, with why. A parent is looked for in the branch, then in what is stored and queued. Nothing is executed
    * and nothing is written.
    */
  def firstInvalidHeader(
      branch: List[Block]
  )(implicit blockchainConfig: BlockchainConfig): Option[(BlockHeader, String)] =
    val inBranch = branch.iterator.map(block => block.header.hash.value -> block.header).toMap
    def parentOf(hash: ByteString): Option[BlockHeader] =
      inBranch.get(hash).orElse(getBlockHeaderFromChainOrQueue(hash))
    def failedBy(
        check: BlockHeader => Either[Any, Any]
    ): Option[(BlockHeader, String)] =
      branch.iterator.map(block => block.header -> check(block.header)).collectFirst { case (header, Left(error)) =>
        header -> error.toString
      }
    // The rules that cost nothing are checked on every header before the seal of any (forge, B-306): what a branch
    // weighs is in its headers' difficulties, and a header is cheap to write with any difficulty and a seal no one
    // mined. With the difficulties checked first, a seal is verified only on a branch whose weight is what it says.
    val cheapRules = mining.validators.blockHeaderValidator match
      case rules: BlockHeaderValidatorSkeleton =>
        failedBy(header =>
          parentOf(header.parentHash.value)
            .toRight(HeaderParentNotFoundError)
            .flatMap(rules.validateParentRules(header, _))
        )
      case _ => None
    cheapRules.orElse(failedBy(header => mining.validators.blockHeaderValidator.validate(header, parentOf)))

  private def getBlockHeaderFromChainOrQueue(hash: ByteString): Option[BlockHeader] =
    blockchainReader
      .getBlockHeaderByHash(BlockHash(hash))
      .orElse(blockQueue.getBlockByHash(BlockHash(hash)).map(_.header))

  private def getNBlocksBackFromChainOrQueue(hash: ByteString, n: Int): List[Block] =
    val queuedBlocks = blockQueue.getBranch(BlockHash(hash), dequeue = false).takeRight(n)
    if queuedBlocks.length == n then queuedBlocks
    else
      val chainedBlockHash = queuedBlocks.headOption.map(_.header.parentHash).getOrElse(BlockHash(hash))
      blockchainReader.getBlockByHash(chainedBlockHash) match
        case None =>
          // The in memory blocks aren't connected to the db ones, we don't have n blocks to return so we return none
          Nil

        case Some(highestBlockInStorage) =>
          // We already have |block +: queuedBlocks|
          val remaining = n - queuedBlocks.length - 1
          val remainingBlocks = Iterator
            .iterate(blockchainReader.getBlockByHash(highestBlockInStorage.header.parentHash))(
              _.filter(_.number != BlockNumber.Zero) // avoid trying to fetch parent of genesis
                .flatMap(p => blockchainReader.getBlockByHash(p.header.parentHash))
            )
            .take(remaining)
            .collect { case Some(block) => block }
            .toList
          (remainingBlocks :+ highestBlockInStorage) ::: queuedBlocks

  def validateBlockAfterExecution(
      block: Block,
      stateRootHash: ByteString,
      receipts: Seq[Receipt],
      gasUsed: BigInt
  )(implicit blockchainConfig: BlockchainConfig): Either[BlockExecutionError, BlockExecutionSuccess] =
    mining.validators.validateBlockAfterExecution(
      block = block,
      stateRootHash = stateRootHash,
      receipts = receipts,
      gasUsed = gasUsed
    )
