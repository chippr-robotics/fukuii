package com.chipprbots.ethereum.consensus.validators

import com.chipprbots.ethereum.consensus.difficulty.DifficultyCalculator
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.Difficulty
import com.chipprbots.ethereum.domain.Timestamp
import com.chipprbots.ethereum.utils.BlockchainConfig

/** Test-only header validator: PoW mocked AND the difficulty calculator pinned to 0, so difficulty-0 fixture headers
  * pass the difficulty rule and every OTHER header rule (extraFields, baseFee, gas limit, timestamp, number) runs
  * exactly as in production.
  *
  * The ETC fork-boundary specs (OlympiaBlockHeaderValidationSpec, SpiralToOlympiaGasTransitionSpec) used
  * MockedPowBlockHeaderValidator for this, relying on it treating difficulty 0 as "skip difficulty validation" on every
  * chain. That bypass is now confined to chains with a terminal total difficulty — a zero-difficulty PoW header is
  * invalid (core-geth ethash verifyHeader, errInvalidDifficulty) — so the isolation those specs want is stated here.
  */
object DifficultyAgnosticValidator extends BlockHeaderValidatorSkeleton:
  override protected def difficulty: DifficultyCalculator = new DifficultyCalculator:
    def calculateDifficulty(blockNumber: BigInt, blockTimestamp: Timestamp, parent: BlockHeader)(implicit
        blockchainConfig: BlockchainConfig
    ): Difficulty = Difficulty.Zero

  override def validateEvenMore(blockHeader: BlockHeader)(implicit
      blockchainConfig: BlockchainConfig
  ): Either[BlockHeaderError, BlockHeaderValid] = Right(BlockHeaderValid)
