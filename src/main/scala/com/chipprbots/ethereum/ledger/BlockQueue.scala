package com.chipprbots.ethereum.ledger

import org.apache.pekko.util.ByteString

import scala.annotation.tailrec
import scala.jdk.CollectionConverters.*

import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.BlockHash
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.ledger.BlockQueue.Leaf
import com.chipprbots.ethereum.ledger.BlockQueue.QueuedBlock
import com.chipprbots.ethereum.utils.Config.SyncConfig
import com.chipprbots.ethereum.utils.Logger
object BlockQueue:
  case class QueuedBlock(block: Block, weight: Option[ChainWeight])
  case class Leaf(hash: ByteString, weight: ChainWeight)

  def apply(
      blockchainReader: BlockchainReader,
      syncConfig: SyncConfig
  ): BlockQueue =
    new BlockQueue(blockchainReader, syncConfig.maxQueuedBlockNumberAhead, syncConfig.maxQueuedBlockNumberBehind)

class BlockQueue(
    blockchainReader: BlockchainReader,
    val maxQueuedBlockNumberAhead: Int,
    val maxQueuedBlockNumberBehind: Int
) extends Logger:

  // note these two maps make this class thread-unsafe
  private val blocks = new java.util.concurrent.ConcurrentHashMap[BlockHash, QueuedBlock].asScala
  private val parentToChildren = new java.util.concurrent.ConcurrentHashMap[BlockHash, Set[BlockHash]].asScala

  /** Enqueue a block for optional later inclusion into the blockchain. Queued blocks are stored as trees with
    * bi-directional relations. Therefore when a younger blocks arrives, for which the total difficulty is known, we can
    * update total difficulties of all its descendants.
    *
    * The queue is bounded by configured limits in relation to current best block number - i.e. if the block to be
    * enqueued is too far behind or too far ahead the current best block number it will not be added. Also other such
    * blocks, that are already enqueued, will be removed.
    *
    * @param block
    *   the block to be enqueued
    * @return
    *   if the newly enqueued block is part of a known branch (rooted somewhere on the main chain), return the leaf hash
    *   and its total difficulty, otherwise None
    */
  def enqueueBlock(block: Block, bestBlockNumber: BigInt = blockchainReader.getBestBlockNumber): Option[Leaf] =
    import block.header.*

    cleanUp(bestBlockNumber)

    blocks.get(hash) match

      case Some(_) =>
        log.debug(s"Block (${block.idTag}) already in queue. ")
        None

      case None if isNumberOutOfRange(number.value, bestBlockNumber) =>
        log.debug(s"Block (${block.idTag} is outside accepted range. Current best block number is: $bestBlockNumber")
        None

      case None =>
        val parentWeight = blockchainReader.getChainWeightByHash(parentHash)

        parentWeight match

          case Some(_) =>
            addBlock(block, parentWeight)
            log.debug(s"Enqueued new block (${block.idTag}) with parent on the main chain")
            updateChainWeights(hash)

          case None =>
            addBlock(block, parentWeight)
            findClosestChainedAncestor(block) match
              case Some(ancestor) =>
                log.debug(s"Enqueued new block (${block.idTag}) to a rooted sidechain")
                updateChainWeights(ancestor)

              case None =>
                log.debug(s"Enqueued new block (${block.idTag}) with unknown relation to the main chain")
                None

  def getBlockByHash(hash: BlockHash): Option[Block] =
    blocks.get(hash).map(_.block)

  def isQueued(hash: BlockHash): Boolean =
    blocks.contains(hash)

  /** Returns the weight of the block corresponding to the hash, or None if not found
    * @param hash
    *   the block's hash to get the weight from
    * @return
    *   the weight of the block corresponding to the hash, or None if not found
    */
  def getChainWeightByHash(hash: BlockHash): Option[ChainWeight] =
    blocks.get(hash).flatMap(_.weight)

  /** Takes a branch going from descendant block upwards to the oldest ancestor
    * @param descendant
    *   the youngest block to be removed
    * @param dequeue
    *   should the branch be removed from the queue. Shared part of branch won't be removed
    * @return
    *   full branch from oldest ancestor to descendant, even if not all of it is removed
    */
  def getBranch(descendant: BlockHash, dequeue: Boolean): List[Block] =

    def recur(hash: BlockHash, childShared: Boolean): List[Block] =
      blocks.get(hash) match
        case Some(QueuedBlock(block, _)) =>
          import block.header.parentHash

          val isShared = childShared || parentToChildren.get(hash).exists(_.nonEmpty)
          if !isShared && dequeue then
            val siblings = parentToChildren.get(parentHash)
            siblings.foreach(sbls => parentToChildren += parentHash -> (sbls - hash))
            blocks -= hash

          block :: recur(parentHash, isShared)

        case _ =>
          Nil

    recur(hash = descendant, childShared = false).reverse

  /** Removes a whole subtree begining with the ancestor. To be used when ancestor fails to execute
    * @param ancestor
    *   hash of the ancestor block
    */
  def removeSubtree(ancestor: BlockHash): Unit =
    blocks.get(ancestor).foreach { case QueuedBlock(block, _) =>
      val children = parentToChildren.getOrElse(ancestor, Set.empty)
      children.foreach(removeSubtree)
      blocks -= block.header.hash
      parentToChildren -= block.header.hash
    }

  /** Takes out of the queue the blocks that became the head's (B-503), as [[getBranch]] takes out a branch it resolves
    * for evaluation. Only those blocks: what hangs from them stays queued, with its weight, and is rooted on the chain
    * now, as a block whose parent is stored is. A block that was not queued is passed over.
    *
    * A block of the head's chain left queued is resolved as part of the branch of the next block that hangs from it,
    * and that branch then leaves the chain below the head: it is executed again as a reorganisation onto the node's own
    * chain.
    *
    * A queued block that hangs from one of them, queued or not, and was queued with no weight (its parent had none, or
    * was not known: the branch was not rooted then) is weighed now from its parent's stored weight, and what hangs from
    * it with it (B-509), as [[enqueueBlock]] weighs a block whose parent is stored. Left with none, it is rooted on
    * nothing: [[findClosestChainedAncestor]] stops at a parent that is not queued, so no block that hangs from it is
    * ever resolved as a branch.
    *
    * @param imported
    *   the hashes of the blocks that became the head's
    */
  def removeImported(imported: Iterable[BlockHash]): Unit =
    val heads = imported.toList
    heads.foreach { hash =>
      blocks.remove(hash).foreach { case QueuedBlock(block, _) =>
        val parentHash = block.header.parentHash
        parentToChildren.get(parentHash).foreach(siblings => parentToChildren += parentHash -> (siblings - hash))
      }
    }
    heads.foreach(weighChildrenFromTheStore)

  /** The weightless queued children of `parent`, a block that became the head's, weighed from its stored weight, and
    * their subtrees with them. None when the store holds no weight for it.
    */
  private def weighChildrenFromTheStore(parent: BlockHash): Unit =
    val weightless =
      parentToChildren.getOrElse(parent, Set.empty).filter(child => blocks.get(child).exists(_.weight.isEmpty))
    if weightless.nonEmpty then
      blockchainReader.getChainWeightByHash(parent).foreach { parentWeight =>
        weightless.foreach { child =>
          blocks.get(child).foreach { queued =>
            blocks += child -> queued.copy(weight = Some(parentWeight.increase(queued.block.header)))
            updateChainWeights(child)
          }
        }
      }

  /** Clear the BlockQueue
    */
  def clear(): Unit =
    blocks.clear()
    parentToChildren.clear()

  /** Removes stale blocks - too old or too young in relation the current best block number
    * @param bestBlockNumber
    *   \- best block number of the main chain
    */
  private def cleanUp(bestBlockNumber: BigInt): Unit =
    val staleHashes = blocks.values.collect {
      case QueuedBlock(b, _) if isNumberOutOfRange(b.header.number.value, bestBlockNumber) =>
        b.header.hash
    }

    blocks --= staleHashes
    parentToChildren --= staleHashes

  /** Updates chain weights for a subtree.
    * @param ancestor
    *   An ancestor's hash that determines the subtree
    * @return
    *   Best leaf from the affected subtree
    */
  private def updateChainWeights(ancestor: BlockHash): Option[Leaf] =
    blocks.get(ancestor).flatMap(_.weight).flatMap { weight =>
      parentToChildren.get(ancestor) match

        case Some(children) if children.nonEmpty =>
          val updatedChildren = children
            .flatMap(blocks.get)
            .map { qb =>
              qb.copy(weight = Some(weight.increase(qb.block.header)))
            }
          updatedChildren.foreach(qb => blocks += qb.block.header.hash -> qb)
          updatedChildren.flatMap(qb => updateChainWeights(qb.block.header.hash)).maxByOption(_.weight)

        case _ =>
          Some(Leaf(ancestor.value, weight))
    }

  /** Find a closest (youngest) chained ancestor. Chained means being part of a known chain, thus having total
    * difficulty defined
    *
    * @param descendant
    *   the block we start the search from
    * @return
    *   hash of the ancestor, if found
    */
  @tailrec
  private def findClosestChainedAncestor(descendant: Block): Option[BlockHash] =
    blocks.get(descendant.header.parentHash) match
      case Some(QueuedBlock(block, Some(_))) =>
        Some(block.header.hash)

      case Some(QueuedBlock(block, None)) =>
        findClosestChainedAncestor(block)

      case None =>
        None

  private def addBlock(block: Block, parentWeight: Option[ChainWeight]): Unit =
    import block.header.*

    val weight = parentWeight.map(_.increase(block.header))
    blocks += hash -> QueuedBlock(block, weight)

    val siblings = parentToChildren.getOrElse(parentHash, Set.empty)
    parentToChildren += parentHash -> (siblings + hash)

  private def isNumberOutOfRange(blockNumber: BigInt, bestBlockNumber: BigInt): Boolean =
    blockNumber - bestBlockNumber > maxQueuedBlockNumberAhead ||
      bestBlockNumber - blockNumber > maxQueuedBlockNumberBehind
