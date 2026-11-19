package com.chipprbots.ethereum.ledger

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.consensus.mining.Mining
import com.chipprbots.ethereum.consensus.mining.OmmerAncestor
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
    firstInvalidHeaderOf(branch.map(_.header))

  /** [[firstInvalidHeader]], of a branch known by its headers alone (B-300 g): the same rules, as only the headers are
    * read.
    */
  def firstInvalidHeaderOf(
      headers: List[BlockHeader]
  )(implicit blockchainConfig: BlockchainConfig): Option[(BlockHeader, String)] =
    firstInvalid(headers, overOwnHole = false)

  /** [[firstInvalidHeader]], for the escalation's feed only (B-391): a branch this node's state could not take, that
    * was not judged ([[com.chipprbots.ethereum.blockchain.sync.PowEscalationFeed.notJudged]]). As
    * [[firstInvalidHeaderOfNotJudged]], of its headers.
    */
  def firstInvalidHeaderNotJudged(
      branch: List[Block]
  )(implicit blockchainConfig: BlockchainConfig): Option[(BlockHeader, String)] =
    firstInvalidHeaderOfNotJudged(branch.map(_.header))

  /** [[firstInvalidHeaderOf]], for the escalation's feed only (B-391; the user's decision of 2026-11-19). Where the
    * header of the block the branch stands on is not stored, and the hash index names that block as this node's at its
    * height, the parent rules of the first header cannot be checked: the hole is this node's. That header alone is then
    * checked by what it says of itself, the Ethash seal with it
    * ([[com.chipprbots.ethereum.consensus.validators.BlockHeaderValidator.validateHeaderOnly]]): the seal proves the
    * difficulty it claims was mined. Every header above it is checked against its parent in the branch, as an import
    * checks it. Anywhere else, a parent not found is [[HeaderParentNotFoundError]], as in [[firstInvalidHeaderOf]].
    *
    * Never for an import, nor for a fork choice: what this answers only says whether the feed may count the branch as a
    * fault of this node. Proof of work only: a first header of difficulty 0 is not relaxed.
    */
  def firstInvalidHeaderOfNotJudged(
      headers: List[BlockHeader]
  )(implicit blockchainConfig: BlockchainConfig): Option[(BlockHeader, String)] =
    firstInvalid(headers, overOwnHole = true)

  private def firstInvalid(
      headers: List[BlockHeader],
      overOwnHole: Boolean
  )(implicit blockchainConfig: BlockchainConfig): Option[(BlockHeader, String)] =
    val inBranch = headers.iterator.map(header => header.hash.value -> header).toMap
    def parentOf(hash: ByteString): Option[BlockHeader] =
      inBranch.get(hash).orElse(getBlockHeaderFromChainOrQueue(hash))
    def failedBy(of: List[BlockHeader])(
        check: BlockHeader => Either[Any, Any]
    ): Option[(BlockHeader, String)] =
      of.iterator.map(header => header -> check(header)).collectFirst { case (header, Left(error)) =>
        header -> error.toString
      }
    // B-391: the first header stands on this node's own block, by the hash index, whose header is not stored
    val sealOnly = headers.take(1).filter { first =>
      overOwnHole && first.difficulty.value > 0 && first.number.value > 0 &&
      parentOf(first.parentHash.value).isEmpty &&
      blockchainReader.getCanonicalHashByNumber(first.number.value - 1).contains(first.parentHash)
    }
    val byParent = headers.drop(sealOnly.size)
    val validator = mining.validators.blockHeaderValidator
    // The rules that cost nothing are checked on every header before the seal of any (forge, B-306): what a branch
    // weighs is in its headers' difficulties, and a header is cheap to write with any difficulty and a seal no one
    // mined. With the difficulties checked first, a seal is verified only on a branch whose weight is what it says.
    val cheapRules = validator match
      case rules: BlockHeaderValidatorSkeleton =>
        failedBy(byParent)(header =>
          parentOf(header.parentHash.value)
            .toRight(HeaderParentNotFoundError)
            .flatMap(rules.validateParentRules(header, _))
        )
      case _ => None
    cheapRules
      .orElse(failedBy(sealOnly)(validator.validateHeaderOnly))
      .orElse(failedBy(byParent)(header => validator.validate(header, parentOf)))

  private def getBlockHeaderFromChainOrQueue(hash: ByteString): Option[BlockHeader] =
    blockchainReader
      .getBlockHeaderByHash(BlockHash(hash))
      .orElse(blockQueue.getBlockByHash(BlockHash(hash)).map(_.header))

  /** The queued blocks the branch ends with, and below them the stored chain by its headers: a stored ancestor's body
    * is read only where its header names ommers (B-302).
    */
  private def getNBlocksBackFromChainOrQueue(hash: ByteString, n: Int): List[OmmerAncestor] =
    val queuedBlocks = blockQueue.getBranch(BlockHash(hash), dequeue = false).takeRight(n).map(OmmerAncestor.of)
    if queuedBlocks.length == n then queuedBlocks
    else
      val chainedBlockHash = queuedBlocks.headOption.map(_.header.parentHash).getOrElse(BlockHash(hash))
      def stored(header: BlockHeader): OmmerAncestor =
        OmmerAncestor.of(header, blockchainReader.getBlockBodyByHash(header.hash))
      blockchainReader.getBlockHeaderByHash(chainedBlockHash) match
        case None =>
          // The in memory blocks aren't connected to the db ones, we don't have n blocks to return so we return none
          Nil

        case Some(highestInStorage) =>
          // We already have |block +: queuedBlocks|
          val remaining = n - queuedBlocks.length - 1
          val remainingBlocks = Iterator
            .iterate(blockchainReader.getBlockHeaderByHash(highestInStorage.parentHash))(
              _.filter(_.number != BlockNumber.Zero) // avoid trying to fetch parent of genesis
                .flatMap(p => blockchainReader.getBlockHeaderByHash(p.parentHash))
            )
            .take(remaining)
            .collect { case Some(header) => stored(header) }
            .toList
          (remainingBlocks :+ stored(highestInStorage)) ::: queuedBlocks

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
