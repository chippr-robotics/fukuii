package com.chipprbots.ethereum.blockchain.sync.fast

import org.apache.pekko.util.ByteString

import cats.data.NonEmptyList

import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.Blockchain
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.utils.Logger

trait FastSyncBranchResolver:

  import FastSyncBranchResolver.*

  protected def blockchain: Blockchain
  protected def blockchainReader: BlockchainReader

  /** Removes the blocks above `lastValidBlock`, from the best down. Returns each block it removed, by number and hash,
    * read before its entry of the index by number is removed (B-248): the import's rewind undoes their reference
    * counts, and could not find them in the index after this.
    */
  def discardBlocksAfter(lastValidBlock: BigInt): Seq[(BigInt, ByteString)] =
    discardBlocks(lastValidBlock, blockchainReader.getBestBlockNumber)

  private def discardBlocks(fromBlock: BigInt, toBlock: BigInt): Seq[(BigInt, ByteString)] =
    val blocksToBeRemoved = childOf(fromBlock).to(toBlock).reverse.toList
    blocksToBeRemoved.flatMap { toBeRemoved =>
      blockchainReader.getBlockHeaderByNumber(toBeRemoved).map { header =>
        blockchain.removeBlock(header.hash)
        (header.number.value, header.hash.value)
      }
    }

object FastSyncBranchResolver:

  /** Stores the current search state for binary search. Meaning we know the first common block lies between
    * minBlockNumber and maxBlockNumber.
    */
  final case class SearchState(minBlockNumber: BigInt, maxBlockNumber: BigInt, masterPeer: Peer)

  def parentOf(blockHeaderNumber: BigInt): BigInt = blockHeaderNumber - 1
  def childOf(blockHeaderNumber: BigInt): BigInt = blockHeaderNumber + 1

/** Attempt to find last common block within recent blocks by looking for a parent/child relationship between our block
  * headers and remote peer's block headers.
  */
class RecentBlocksSearch(blockchainReader: BlockchainReader):

  /** Find the highest common block by trying to find a block so that our block n is the parent of remote candidate
    * block n + 1
    */
  def getHighestCommonBlock(
      candidateHeaders: Seq[BlockHeader],
      bestBlockNumber: BigInt
  ): Option[BigInt] =
    def isParent(potentialParent: BigInt, childCandidate: BlockHeader): Boolean =
      blockchainReader.getBlockHeaderByNumber(potentialParent).exists(_.isParentOf(childCandidate))
    NonEmptyList.fromList(candidateHeaders.reverse.toList).flatMap { remoteHeaders =>
      val blocksToBeCompared = bestBlockNumber.until(bestBlockNumber - remoteHeaders.size).by(-1).toList
      remoteHeaders.toList
        .zip(blocksToBeCompared)
        .collectFirst {
          case (childCandidate, parent) if isParent(parent, childCandidate) => parent
        }
    }

object BinarySearchSupport extends Logger:
  import FastSyncBranchResolver.*

  sealed trait BinarySearchResult
  final case class BinarySearchCompleted(highestCommonBlockNumber: BigInt) extends BinarySearchResult
  final case class ContinueBinarySearch(searchState: SearchState) extends BinarySearchResult
  case object NoCommonBlock extends BinarySearchResult

  /** Returns the block number in the middle between min and max. If there is no middle, it will return the lower value.
    *
    * E.g. calling this method with min = 3 and max = 6 will return 4
    */
  def middleBlockNumber(min: BigInt, max: BigInt): BigInt = (min + max) / 2

  def blockHeaderNumberToRequest(min: BigInt, max: BigInt): BigInt =
    childOf(middleBlockNumber(min, max))

  def validateBlockHeaders(
      parentBlockHeader: BlockHeader,
      childBlockHeader: BlockHeader,
      searchState: SearchState
  ): BinarySearchResult =
    val childNum = childBlockHeader.number
    val parentNum = parentBlockHeader.number
    val min = searchState.minBlockNumber
    val max = searchState.maxBlockNumber

    log.debug(
      "Validating block headers (binary search) for parentBlockHeader {}, remote childBlockHeader {} and search state {}",
      parentBlockHeader.number,
      childBlockHeader.number,
      searchState
    )

    if parentBlockHeader
        .isParentOf(childBlockHeader)
    then // chains are still aligned but there might be an even better block
      if parentNum.value == max then BinarySearchCompleted(parentNum.value)
      else if parentNum.value == min && childNum.value == max then
        ContinueBinarySearch(searchState.copy(minBlockNumber = childNum.value))
      else ContinueBinarySearch(searchState.copy(minBlockNumber = parentNum.value))
    else // no parent/child -> chains have diverged before parent block
    if min == 1 && max <= 2 then NoCommonBlock
    else if min == max then BinarySearchCompleted(parentOf(parentNum.value))
    else ContinueBinarySearch(searchState.copy(maxBlockNumber = parentOf(parentNum.value).max(1)))
