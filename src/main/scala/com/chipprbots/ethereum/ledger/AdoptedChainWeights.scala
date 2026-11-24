package com.chipprbots.ethereum.ledger

import com.chipprbots.ethereum.domain.BlockHash
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.domain.BlockchainWriter
import com.chipprbots.ethereum.utils.Logger

/** '''The weights of the blocks a move of the head, or a commit in place, makes canonical''' (B-126, B-490): each its
  * parent's weight plus its difficulty, re-derived from the weight of the block below the lowest, the value import
  * writes.
  *
  * A block executed apart from the head keeps the weight derived from its parent's when it was executed: a payload
  * (`EngineApiService.storeChainWeightFor`), a block of the import path's branch kept as a layer, a block a recovery
  * executed again. A block a recovery commits in place (`StateRecovery`) takes the weight its parent had when the
  * recovery read it, before the commit's lock. `ChainWeightReconcile` repairs the weights of canonical blocks only: its
  * final step checks the heights the index names, and sets the marker that says every canonical weight follows from
  * genesis. So a block whose parent it repaired after the block took its weight, and that is made canonical after that
  * final step, would keep the parent's old weight under the marker. On a PoS chain only the total difficulty this node
  * advertises reads it; fork choice follows the consensus layer.
  *
  * Callers: the fork choice (`ForkChoiceManager`, B-126), and the recovery's adoption of a branch and its catch-up
  * after a hand-off (`StateRecovery`, B-490), which the p2p import path's hand-off (`BranchHandOff`) reaches too. Each
  * calls [[rederive]] after its move or commit has returned, never inside the state's lock.
  */
object AdoptedChainWeights extends Logger:

  /** How far below a head [[notCanonicalBelow]] looks for the blocks a move to it makes canonical. */
  val MaxWalk: Int = 8192

  /** `head` and its ancestors down to the highest block the index names, by their hashes, highest first: what a move of
    * the head to `head` makes canonical. Read before the move, while the index is still the old head's ancestry. Stops
    * where a header is not stored, and after [[MaxWalk]] blocks: a branch that long, or one whose base the index does
    * not name (a datadir with no index below its pivot), is not walked to the end, and none of it is returned: its
    * weights are left as they were written.
    */
  def notCanonicalBelow(head: BlockHeader, reader: BlockchainReader): Seq[BlockHash] =
    @scala.annotation.tailrec
    def down(at: BlockHeader, acc: List[BlockHash], left: Int): List[BlockHash] =
      if reader.getCanonicalHashByNumber(at.number.value).contains(at.hash) then acc
      else if at.number.value == 0 || left <= 1 then at.hash :: acc
      else
        reader.getBlockHeaderByHash(at.parentHash) match
          case Some(parent) => down(parent, at.hash :: acc, left - 1)
          case None         => at.hash :: acc
    val branch = down(head, Nil, MaxWalk)
    if branch.size >= MaxWalk then Nil else branch

  /** Re-derives the weights of `adopted`, from the weight of the parent of the lowest; writes those that differ, in one
    * batch.
    *
    * Under the chain insert lock (shared), as `storeChainWeightFor` reads and writes: the repair's final step holds it
    * exclusively, so the base's weight is never read before it rewrites it, nor the branch written between its check
    * and its marker. A block's exact weight depends only on its ancestry, so the value is right whether or not the
    * block stays canonical. Nothing is written for a branch whose base has no weight: one is not invented (as
    * `storeChainWeightFor`), nor above a link that is not stored.
    *
    * @param adopted
    *   the blocks made canonical, in any order of height; one branch
    * @param by
    *   what made them canonical, for the log line
    */
  def rederive(adopted: Seq[BlockHash], reader: BlockchainReader, writer: BlockchainWriter, by: String): Unit =
    if adopted.nonEmpty then
      ChainInsertLock.shared {
        val branch = adopted.flatMap(reader.getBlockHeaderByHash).sortBy(_.number.value)
        branch.headOption.flatMap(lowest => reader.getChainWeightByHash(lowest.parentHash)).foreach { base =>
          // each block on the one below it; a branch that does not link is taken up to the break
          val linked = branch
            .zip(branch.head.parentHash +: branch.map(_.hash))
            .takeWhile((header, below) => header.parentHash == below)
            .map(_._1)
          val writes = linked
            .scanLeft((Option.empty[BlockHeader], base))((acc, header) => (Some(header), acc._2.increase(header)))
            .collect { case (Some(header), weight) => header -> weight }
            .filterNot((header, weight) => reader.getChainWeightByHash(header.hash).contains(weight))
          if writes.nonEmpty then
            writer.storeChainWeights(writes.map((header, weight) => header.hash -> weight)).commit()
            log.info(
              s"$by re-derived the weight of ${writes.size} block(s) it made canonical, " +
                s"#${writes.head._1.number.value} to #${writes.last._1.number.value}, from their parents'"
            )
        }
      }
