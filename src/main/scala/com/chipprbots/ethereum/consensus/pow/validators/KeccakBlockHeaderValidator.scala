package com.chipprbots.ethereum.consensus.pow.validators

import com.chipprbots.ethereum.consensus.pow.KeccakCalculation
import com.chipprbots.ethereum.consensus.validators.BlockHeaderError
import com.chipprbots.ethereum.consensus.validators.BlockHeaderError.HeaderPoWError
import com.chipprbots.ethereum.consensus.validators.BlockHeaderValid
import com.chipprbots.ethereum.domain.BlockHeader

object KeccakBlockHeaderValidator {

  /** Validates [[com.chipprbots.ethereum.domain.BlockHeader.nonce]] and
    * [[com.chipprbots.ethereum.domain.BlockHeader.mixHash]] are correct
    * @param blockHeader
    * @return
    *   BlockHeaderValid if valid or an BlockHeaderError.HeaderPoWError otherwise
    */
  def validateHeader(blockHeader: BlockHeader): Either[BlockHeaderError, BlockHeaderValid] = {
    val rlpEncodedHeader = BlockHeader.getEncodedWithoutNonce(blockHeader)
    val expectedHash = KeccakCalculation.hash(rlpEncodedHeader, BigInt(blockHeader.nonce.toArray))

    lazy val isDifficultyValid = KeccakCalculation.isMixHashValid(blockHeader.mixHash, blockHeader.difficulty)

    if (expectedHash.mixHash == blockHeader.mixHash && isDifficultyValid) Right(BlockHeaderValid)
    else Left(HeaderPoWError)
  }
}
