package com.chipprbots.ethereum.consensus.validators

import com.chipprbots.ethereum.consensus.difficulty.DifficultyCalculator
import com.chipprbots.ethereum.consensus.eip1559.BaseFeeCalculator
import com.chipprbots.ethereum.consensus.mining.GetBlockHeaderByHash
import com.chipprbots.ethereum.consensus.validators.BlockHeaderError.*
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.HefEmpty
import com.chipprbots.ethereum.domain.Difficulty
import com.chipprbots.ethereum.domain.GasAmount
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.HefPostAmsterdam
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.HefPostCancun
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.HefPostOlympia
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.HefPostPrague
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.HefPostShanghai
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.DaoForkConfig

/** A block header validator that does everything Ethereum prescribes except from:
  *   - PoW validation
  *   - Difficulty validation.
  *
  * The former is a characteristic of standard ethereum with Ethash, so it is not even known to this implementation.
  *
  * The latter is treated polymorphically by directly using a difficulty
  * [[com.chipprbots.ethereum.consensus.difficulty.DifficultyCalculator calculator]].
  */
trait BlockHeaderValidatorSkeleton extends BlockHeaderValidator:

  import BlockHeaderValidator.*

  /** The difficulty calculator. This is specific to the consensus protocol.
    */

  protected def difficulty: DifficultyCalculator = DifficultyCalculator

  /** A hook where even more consensus-specific validation can take place. For example, PoW validation is done here.
    */
  protected def validateEvenMore(blockHeader: BlockHeader)(implicit
      blockchainConfig: BlockchainConfig
  ): Either[BlockHeaderError, BlockHeaderValid]

  /** This method allows validate a BlockHeader (stated on section 4.4.4 of http://paper.gavwood.com/).
    *
    * @param blockHeader
    *   BlockHeader to validate.
    * @param parentHeader
    *   BlockHeader of the parent of the block to validate.
    */
  def validate(blockHeader: BlockHeader, parentHeader: BlockHeader)(implicit
      blockchainConfig: BlockchainConfig
  ): Either[BlockHeaderError, BlockHeaderValid] =
    for
      // NOTE how we include everything except PoW (which is deferred to `validateEvenMore`),
      //      and that difficulty validation is in effect abstract (due to `difficulty`).
      _ <- validateExtraData(blockHeader)
      _ <- validateTimestamp(blockHeader, parentHeader)
      _ <- validateDifficulty(blockHeader, parentHeader)
      _ <- validateGasUsed(blockHeader)
      _ <- validateGasLimit(blockHeader, parentHeader)
      _ <- validateNumber(blockHeader, parentHeader)
      _ <- validateExtraFields(blockHeader)
      _ <- validateBaseFee(blockHeader, parentHeader)
      _ <- validateBlobGasAgainstParent(blockHeader, parentHeader)
      _ <- validateEvenMore(blockHeader)
    yield BlockHeaderValid

  /** EIP-4844 / EIP-7691: validate blobGasUsed ≤ MAX_BLOB_GAS_PER_BLOCK, is a multiple of GAS_PER_BLOB, and
    * excessBlobGas equals calcExcessBlobGas(parent). Runs only when the header declares blob fields.
    */
  private def validateBlobGasAgainstParent(
      blockHeader: BlockHeader,
      parentHeader: BlockHeader
  )(implicit blockchainConfig: BlockchainConfig): Either[BlockHeaderError, BlockHeaderValid] =
    import com.chipprbots.ethereum.consensus.engine.BlobGasUtils
    (blockHeader.blobGasUsed, blockHeader.excessBlobGas) match
      case (Some(used), Some(excess)) =>
        val maxBlobGas = BlobGasUtils.maxBlobGasPerBlock(blockHeader.unixTimestamp, blockchainConfig)
        val parentExcess = parentHeader.excessBlobGas.getOrElse(BigInt(0))
        val parentUsed = parentHeader.blobGasUsed.getOrElse(BigInt(0))
        val parentBaseFee = parentHeader.baseFee.getOrElse(BigInt(0))
        val expectedExcess = BlobGasUtils.expectedExcessBlobGas(
          parentExcess,
          parentUsed,
          parentBaseFee,
          blockHeader.unixTimestamp,
          blockchainConfig
        )
        if used > maxBlobGas then Left(HeaderBlobGasError(s"blobGasUsed $used exceeds max $maxBlobGas"))
        else if used % BlobGasUtils.GAS_PER_BLOB != 0 then
          Left(HeaderBlobGasError(s"blobGasUsed $used is not a multiple of GAS_PER_BLOB"))
        else if excess != expectedExcess then
          Left(HeaderBlobGasError(s"INCORRECT_EXCESS_BLOB_GAS: expected $expectedExcess got $excess"))
        else Right(BlockHeaderValid)
      case _ => Right(BlockHeaderValid)

  /** This method allows validate a BlockHeader (stated on section 4.4.4 of http://paper.gavwood.com/).
    *
    * @param blockHeader
    *   BlockHeader to validate.
    * @param getBlockHeaderByHash
    *   function to obtain the parent.
    */
  override def validate(
      blockHeader: BlockHeader,
      getBlockHeaderByHash: GetBlockHeaderByHash
  )(implicit blockchainConfig: BlockchainConfig): Either[BlockHeaderError, BlockHeaderValid] =
    for
      blockHeaderParent <- getBlockHeaderByHash(blockHeader.parentHash.value)
        .map(Right(_))
        .getOrElse(Left(HeaderParentNotFoundError))
      _ <- validate(blockHeader, blockHeaderParent)
    yield BlockHeaderValid

  /** Validates [[com.chipprbots.ethereum.domain.BlockHeader.extraData]] length based on validations stated in section
    * 4.4.4 of http://paper.gavwood.com/
    *
    * @param blockHeader
    *   BlockHeader to validate.
    * @return
    *   BlockHeader if valid, an [[com.chipprbots.ethereum.consensus.validators.BlockHeaderError.HeaderExtraDataError]]
    *   otherwise
    */
  protected def validateExtraData(
      blockHeader: BlockHeader
  )(implicit blockchainConfig: BlockchainConfig): Either[BlockHeaderError, BlockHeaderValid] =

    def validateDaoForkExtraData(
        blockHeader: BlockHeader,
        daoForkConfig: DaoForkConfig
    ): Either[BlockHeaderError, BlockHeaderValid] =
      (daoForkConfig.requiresExtraData(blockHeader.number.value), daoForkConfig.blockExtraData) match
        case (false, _) =>
          Right(BlockHeaderValid)
        case (true, Some(forkExtraData)) if blockHeader.extraData == forkExtraData =>
          Right(BlockHeaderValid)
        case _ =>
          Left(DaoHeaderExtraDataError)

    if blockHeader.extraData.length <= MaxExtraDataSize then
      import blockchainConfig.*
      daoForkConfig.map(c => validateDaoForkExtraData(blockHeader, c)).getOrElse(Right(BlockHeaderValid))
    else Left(HeaderExtraDataError)

  /** Validates [[com.chipprbots.ethereum.domain.BlockHeader.unixTimestamp]] is greater than the one of its parent based
    * on validations stated in section 4.4.4 of http://paper.gavwood.com/
    *
    * @param blockHeader
    *   BlockHeader to validate.
    * @param parentHeader
    *   BlockHeader of the parent of the block to validate.
    * @return
    *   BlockHeader if valid, an [[HeaderTimestampError]] otherwise
    */
  private def validateTimestamp(
      blockHeader: BlockHeader,
      parentHeader: BlockHeader
  ): Either[BlockHeaderError, BlockHeaderValid] =
    if blockHeader.unixTimestamp > parentHeader.unixTimestamp then Right(BlockHeaderValid)
    else Left(HeaderTimestampError)

  /** Validates [[com.chipprbots.ethereum.domain.BlockHeader.difficulty]] is correct based on validations stated in
    * section 4.4.4 of http://paper.gavwood.com/
    *
    * @param blockHeader
    *   BlockHeader to validate.
    * @param parent
    *   Block of the parent of the block to validate.
    * @return
    *   BlockHeader if valid, an [[HeaderDifficultyError]] otherwise
    */
  private def validateDifficulty(
      blockHeader: BlockHeader,
      parent: BlockHeader
  )(implicit blockchainConfig: BlockchainConfig): Either[BlockHeaderError, BlockHeaderValid] =
    if blockHeader.difficulty == Difficulty.Zero then
      // Post-merge: difficulty is always 0 (EIP-3675). Pre-merge blocks never have difficulty=0
      // because the Ethash difficulty algorithm always produces a positive value.
      Right(BlockHeaderValid)
    else if difficulty.calculateDifficulty(
        blockHeader.number.value,
        blockHeader.unixTimestamp,
        parent
      ) == blockHeader.difficulty
    then Right(BlockHeaderValid)
    else Left(HeaderDifficultyError)

  /** Validates [[com.chipprbots.ethereum.domain.BlockHeader.gasUsed]] is not greater than
    * [[com.chipprbots.ethereum.domain.BlockHeader.gasLimit]] based on validations stated in section 4.4.4 of
    * http://paper.gavwood.com/
    *
    * @param blockHeader
    *   BlockHeader to validate.
    * @return
    *   BlockHeader if valid, an [[HeaderGasUsedError]] otherwise
    */
  private def validateGasUsed(blockHeader: BlockHeader): Either[BlockHeaderError, BlockHeaderValid] =
    if blockHeader.gasUsed <= blockHeader.gasLimit && blockHeader.gasUsed >= GasAmount.Zero then Right(BlockHeaderValid)
    else Left(HeaderGasUsedError)

  /** Validates [[com.chipprbots.ethereum.domain.BlockHeader.gasLimit]] follows the restrictions based on its parent
    * gasLimit based on validations stated in section 4.4.4 of http://paper.gavwood.com/
    *
    * EIP106(https://github.com/ethereum/EIPs/issues/106) adds additional validation of maximum value for gasLimit.
    *
    * @param blockHeader
    *   BlockHeader to validate.
    * @param parentHeader
    *   BlockHeader of the parent of the block to validate.
    * @return
    *   BlockHeader if valid, an [[HeaderGasLimitError]] otherwise
    */
  private def validateGasLimit(
      blockHeader: BlockHeader,
      parentHeader: BlockHeader
  )(implicit blockchainConfig: BlockchainConfig): Either[BlockHeaderError, BlockHeaderValid] =
    // 2^63 - 1 is the protocol-wide gasLimit cap (cannot fit in an int64). It applies
    // regardless of EIP-106 activation — any block with gasLimit >= 2^63 is malformed.
    if blockHeader.gasLimit.value > MaxGasLimit then Left(HeaderGasLimitError)
    else
      // EIP-1559 one-shot elasticity scaling at the fork-activation block.
      //
      // go-ethereum (consensus/misc/eip1559.go, VerifyEip1559Header) scales the parent's
      // gas limit by the elasticity multiplier when the parent is the last pre-fork block,
      // then applies the ordinary ±1/1024 window AROUND that scaled value. It is not an
      // exact-2× equality check: with parent P the activation block may sit anywhere in
      // (2P - 2P/1024, 2P + 2P/1024).
      //
      // Whether this scaling applies is a per-chain fork-schedule property, not a network
      // type test: `olympiaGasLimitElasticity` is Some(2) for ETH/Sepolia/hive (London) and
      // absent — None — for ETC/Mordor/Gorgoroth. See the field comment in BlockchainConfig
      // for what is and is not settled on the ETC side (ECIP-1122 pending).
      //
      // The predicate keys on the PARENT crossing the fork, matching go-ethereum, and uses
      // two independent height comparisons rather than `blockHeader.number == olympiaBlock`.
      // validateGasLimit runs BEFORE validateNumber in `validate`, so at this point the
      // parent/child height relationship is still unverified and must not be assumed.
      val olympiaBlockNumber = blockchainConfig.forkBlockNumbers.olympiaBlockNumber
      val parentIsPreOlympia = parentHeader.number.value < olympiaBlockNumber
      val headerIsPostOlympia = blockHeader.number.value >= olympiaBlockNumber
      val effectiveParentGasLimit: GasAmount =
        blockchainConfig.forkBlockNumbers.olympiaGasLimitElasticity match
          case Some(multiplier) if parentIsPreOlympia && headerIsPostOlympia =>
            // GasAmount has *(Long) and *(BigInt) but no *(Int) — widen explicitly.
            parentHeader.gasLimit * BigInt(multiplier)
          case _ => parentHeader.gasLimit

      // BOTH the diff and the bound are taken from the scaled parent. Computing the divisor
      // from the raw parent would leave a 2× too-tight window at exactly the activation block.
      val gasLimitDiff = (blockHeader.gasLimit - effectiveParentGasLimit).abs
      val gasLimitDiffLimit = effectiveParentGasLimit / GasLimitBoundDivisor
      if gasLimitDiff < gasLimitDiffLimit && blockHeader.gasLimit >= GasAmount(MinGasLimit) then Right(BlockHeaderValid)
      else Left(HeaderGasLimitError)

  /** Validates [[com.chipprbots.ethereum.domain.BlockHeader.number]] is the next one after its parents number based on
    * validations stated in section 4.4.4 of http://paper.gavwood.com/
    *
    * @param blockHeader
    *   BlockHeader to validate.
    * @param parentHeader
    *   BlockHeader of the parent of the block to validate.
    * @return
    *   BlockHeader if valid, an [[HeaderNumberError]] otherwise
    */
  private def validateNumber(
      blockHeader: BlockHeader,
      parentHeader: BlockHeader
  ): Either[BlockHeaderError, BlockHeaderValid] =
    if blockHeader.number == parentHeader.number + 1 then Right(BlockHeaderValid)
    else Left(HeaderNumberError)

  /** Validates [[com.chipprbots.ethereum.domain.BlockHeader.extraFields]] match the Olympia fork activation.
    */
  private def validateExtraFields(
      blockHeader: BlockHeader
  )(implicit blockchainConfig: BlockchainConfig): Either[BlockHeaderError, BlockHeaderValid] =
    val isOlympiaActivated = blockHeader.number.value >= blockchainConfig.forkBlockNumbers.olympiaBlockNumber

    blockHeader.extraFields match
      // Amsterdam (23 items). Slice A taught the decoder to produce this variant; without a case here it
      // falls to the catch-all below and every Amsterdam block is rejected with HeaderExtraFieldsError
      // before it reaches execution at all. `PoSBlockHeaderValidator` inherits this method without
      // overriding `validate`, so it runs on the live PoS import path.
      case HefPostAmsterdam(_, _, _, _, _, _, _, _) if isOlympiaActivated => Right(BlockHeaderValid)
      case HefPostPrague(_, _, _, _, _, _) if isOlympiaActivated          => Right(BlockHeaderValid)
      case HefPostCancun(_, _, _, _, _) if isOlympiaActivated             => Right(BlockHeaderValid)
      case HefPostShanghai(_, _) if isOlympiaActivated                    => Right(BlockHeaderValid)
      case HefPostOlympia(_) if isOlympiaActivated                        => Right(BlockHeaderValid)
      case HefEmpty if !isOlympiaActivated                                => Right(BlockHeaderValid)
      case _ =>
        Left(HeaderExtraFieldsError(blockHeader.extraFields))

  /** Validates that the baseFee in the block header matches the expected value calculated from the parent header using
    * the EIP-1559 algorithm.
    */
  private def validateBaseFee(
      blockHeader: BlockHeader,
      parentHeader: BlockHeader
  )(implicit blockchainConfig: BlockchainConfig): Either[BlockHeaderError, BlockHeaderValid] =
    val isOlympiaActivated = blockHeader.number.value >= blockchainConfig.forkBlockNumbers.olympiaBlockNumber
    if !isOlympiaActivated then Right(BlockHeaderValid)
    else
      blockHeader.baseFee match
        case None =>
          Left(HeaderBaseFeeError("missing baseFee after Olympia activation"))
        case Some(actualBaseFee) =>
          val expectedBaseFee = BaseFeeCalculator.calcBaseFee(parentHeader, blockchainConfig)
          if actualBaseFee == expectedBaseFee then Right(BlockHeaderValid)
          else
            Left(
              HeaderBaseFeeError(
                s"invalid baseFee: have $actualBaseFee, want $expectedBaseFee, " +
                  s"parentBaseFee ${parentHeader.baseFee}, parentGasUsed ${parentHeader.gasUsed}"
              )
            )

  override def validateHeaderOnly(
      blockHeader: BlockHeader
  )(implicit blockchainConfig: BlockchainConfig): Either[BlockHeaderError, BlockHeaderValid] =
    for
      _ <- validateExtraData(blockHeader)
      _ <- validateGasUsed(blockHeader)
      _ <- validateEvenMore(blockHeader)
    yield BlockHeaderValid
