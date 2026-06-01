package com.chipprbots.ethereum.blockchain.sync

import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.domain.BlockchainWriter
import com.chipprbots.ethereum.utils.Logger

/** Repairs inflated chain weights caused by the ETH68 bootstrap TD seeding.
  *
  * During SNAP sync, `updateBestBlockForPivot` seeds the pivot block's chain weight from the peer's advertised
  * best-block TD (STATUS message). On a PoW chain the peer's TD at their head exceeds the pivot's canonical TD by
  * roughly `(peerHead - pivot) × avgDifficulty`. This inflated value is stored for the pivot and inherited by every
  * subsequent block import.
  *
  * This class corrects the inflation with a forward walk: starting from the last pre-pivot block whose chain weight was
  * computed correctly (by block processing), it recomputes each block's TD as `TD[n-1] + difficulty[n]` and overwrites
  * the stored value. The walk stops at `endBlock`.
  *
  * The repair is O(n) in the number of blocks from pivot to head. On ETC mainnet (~50K blocks per day, pivot typically
  * 50–200 blocks behind head) this completes in well under a second.
  *
  * If the pre-pivot header or its chain weight is not yet available (e.g. backfill hasn't reached that point), the
  * repair is deferred. It will be attempted again on the next restart.
  */
class ChainWeightRepair(
    blockchainReader: BlockchainReader,
    blockchainWriter: BlockchainWriter
) extends Logger {

  /** Attempt to repair chain weights from `pivotBlock` to `endBlock`.
    *
    * @return
    *   Right(count) — number of blocks corrected; Left(msg) — deferred with reason.
    */
  def repairFrom(pivotBlock: BigInt, endBlock: BigInt): Either[String, Int] = {
    if (pivotBlock <= 0) {
      log.debug("Chain weight repair: pivot is genesis, no ETH68 inflation possible")
      return Right(0)
    }

    val prePivotNum = pivotBlock - 1
    val prePivotHeader =
      blockchainReader.getBlockHeaderByNumber(prePivotNum)
    val prePivotWeight =
      prePivotHeader.flatMap(h => blockchainReader.getChainWeightByHash(h.hash))

    (prePivotHeader, prePivotWeight) match {
      case (Some(_), Some(weight)) =>
        log.info(
          "Chain weight repair: walking forward from block {} (anchorTD={}) to {}",
          prePivotNum,
          weight.totalDifficulty,
          endBlock
        )
        walkForward(pivotBlock, weight.totalDifficulty, endBlock)

      case (None, _) =>
        Left(
          s"Chain weight repair deferred: block $prePivotNum header not available " +
            s"(backfill may not have reached pivot yet)"
        )

      case (Some(_), None) =>
        Left(
          s"Chain weight repair deferred: block $prePivotNum chain weight not stored " +
            s"(block may not have been fully imported yet)"
        )
    }
  }

  private def walkForward(fromBlock: BigInt, anchorTD: BigInt, toBlock: BigInt): Either[String, Int] = {
    var prevTD = anchorTD
    var block = fromBlock
    var corrected = 0
    var missingHeader = false

    while (block <= toBlock && !missingHeader) {
      blockchainReader.getBlockHeaderByNumber(block) match {
        case None =>
          log.warn(
            "Chain weight repair: block {} header missing; stopping at {} blocks corrected",
            block,
            corrected
          )
          missingHeader = true

        case Some(header) =>
          val correctTD = prevTD + header.difficulty
          blockchainWriter.storeChainWeight(header.hash, ChainWeight.totalDifficultyOnly(correctTD)).commit()
          prevTD = correctTD
          corrected += 1
      }
      block += 1
    }

    if (missingHeader)
      Left(
        s"Chain weight repair partial: corrected $corrected blocks up to ${block - 1}, " +
          s"stopped at first missing header"
      )
    else
      Right(corrected)
  }
}
