package com.chipprbots.ethereum.consensus.pow
package validators

import akka.util.ByteString

import java.util.concurrent.atomic.AtomicReference

import com.chipprbots.ethereum.consensus.validators.BlockHeaderError
import com.chipprbots.ethereum.consensus.validators.BlockHeaderError.HeaderPoWError
import com.chipprbots.ethereum.consensus.validators.BlockHeaderValid
import com.chipprbots.ethereum.crypto
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.utils.BlockchainConfig

/** A block header validator for Ethash.
  */
object EthashBlockHeaderValidator {
  final val MaxPowCaches: Int = 2 // maximum number of epochs for which PoW cache is stored in memory

  case class PowCacheData(epoch: Long, cache: Array[Int], dagSize: Long)

  // NOTE the below comment is from before PoW decoupling
  // we need atomic since validators can be used from multiple places
  protected val powCaches: AtomicReference[List[PowCacheData]] = new AtomicReference(List.empty[PowCacheData])

  /** Validates [[com.chipprbots.ethereum.domain.BlockHeader.nonce]] and
    * [[com.chipprbots.ethereum.domain.BlockHeader.mixHash]] are correct based on validations stated in section 4.4.4 of
    * http://paper.gavwood.com/
    *
    * @param blockHeader
    *   BlockHeader to validate.
    * @return
    *   BlockHeaderValid if valid or an BlockHeaderError.HeaderPoWError otherwise
    */
  def validateHeader(
      blockHeader: BlockHeader
  )(implicit blockchainConfig: BlockchainConfig): Either[BlockHeaderError, BlockHeaderValid] = {
    import EthashUtils._

    def getPowCacheData(epoch: Long, seed: ByteString): PowCacheData = {
      var result: PowCacheData = null
      powCaches.updateAndGet { cache =>
        cache.find(_.epoch == epoch) match {
          case Some(pcd) =>
            result = pcd
            cache
          case None =>
            val data =
              PowCacheData(epoch, cache = EthashUtils.makeCache(epoch, seed), dagSize = EthashUtils.dagSize(epoch))
            result = data
            (data :: cache).take(MaxPowCaches)
        }
      }
      result
    }

    val epoch =
      EthashUtils.epoch(blockHeader.number.toLong, blockchainConfig.forkBlockNumbers.ecip1099BlockNumber.toLong)
    val seed = EthashUtils.seed(blockHeader.number.toLong, blockchainConfig.forkBlockNumbers.ecip1099BlockNumber.toLong)
    val powCacheData = getPowCacheData(epoch, seed)

    val proofOfWork = hashimotoLight(
      crypto.kec256(BlockHeader.getEncodedWithoutNonce(blockHeader)),
      blockHeader.nonce.toArray[Byte],
      powCacheData.dagSize,
      powCacheData.cache
    )

    if (proofOfWork.mixHash == blockHeader.mixHash && checkDifficulty(blockHeader.difficulty.toLong, proofOfWork))
      Right(BlockHeaderValid)
    else Left(HeaderPoWError)
  }
}
