package com.chipprbots.ethereum.consensus.validators
package std

import com.chipprbots.ethereum.consensus.engine.BlobGasUtils
import com.chipprbots.ethereum.consensus.validators.SignedTransactionError.*
import com.chipprbots.ethereum.crypto.ECDSASignature
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.vm.AmsterdamGas
import com.chipprbots.ethereum.vm.EvmConfig

object StdSignedTransactionValidator extends SignedTransactionValidator:

  val secp256k1n: BigInt = BigInt("115792089237316195423570985008687907852837564279074904382605163141518161494337")

  /** EIP-7825: Maximum per-transaction gas limit (2^24 = 16,777,216) */
  val TxGasLimitCap: BigInt = BigInt(1 << 24)

  /** Initial tests of intrinsic validity stated in Section 6 of YP
    *
    * @param stx
    *   Transaction to validate
    * @param senderAccount
    *   Account of the sender of the tx
    * @param blockHeader
    *   Container block
    * @param upfrontGasCost
    *   The upfront gas cost of the tx
    * @param accumGasUsed
    *   Total amount of gas spent prior this transaction within the container block
    * @return
    *   Transaction if valid, error otherwise
    */
  def validate(
      stx: SignedTransaction,
      senderAccount: Account,
      blockHeader: BlockHeader,
      upfrontGasCost: UInt256,
      accumGasUsed: BigInt
  )(implicit blockchainConfig: BlockchainConfig): Either[SignedTransactionError, SignedTransactionValid] =
    // One counter stands for all three. That is exact before Amsterdam — the execution dimension IS the receipt sum and
    // the state dimension is empty — and for a block's first transaction at Amsterdam. Block execution passes the real
    // EIP-8037 counters through the overload below.
    validate(stx, senderAccount, blockHeader, upfrontGasCost, accumGasUsed, accumGasUsed, BigInt(0))

  /** [[validate]] with the block's gas so far in all three counters: `accumGasUsed` (the receipt sum), and EIP-8037's
    * `accumExecutionGas` and `accumStateGas`, which Amsterdam's block-capacity check reads instead
    * ([[blockGasCapacityError]]).
    */
  override def validate(
      stx: SignedTransaction,
      senderAccount: Account,
      blockHeader: BlockHeader,
      upfrontGasCost: UInt256,
      accumGasUsed: BigInt,
      accumExecutionGas: BigInt,
      accumStateGas: BigInt
  )(implicit blockchainConfig: BlockchainConfig): Either[SignedTransactionError, SignedTransactionValid] =
    for
      _ <- validateOlympiaTxTypes(stx, blockHeader)
      _ <- validateBlobTransactionSupport(stx, blockHeader)
      _ <- checkSyntacticValidity(stx)
      _ <- validateInitCodeSize(stx, blockHeader.number.value, blockHeader.unixTimestamp)
      _ <- validateSignature(stx, blockHeader.number.value)
      _ <- validateNonce(stx, senderAccount.nonce)
      _ <- validateGasLimitEnoughForIntrinsicGas(stx, blockHeader.number.value, blockHeader.unixTimestamp)
      _ <- validateTxGasLimitCap(stx, blockHeader.number.value, blockHeader.unixTimestamp)
      _ <- validateMaxFeeAgainstBaseFee(stx, blockHeader)
      _ <- validateMaxFeePerBlobGas(stx, blockHeader)
      _ <- validateAccountHasEnoughGasToPayUpfrontCost(senderAccount.balance, upfrontGasCost)
      _ <- validateBlockHasEnoughGasLimitForTx(stx, blockHeader, accumGasUsed, accumExecutionGas, accumStateGas)
    yield SignedTransactionValid

  /** EIP-4844 Type-3 (blob) transactions require Cancun activation. ETC never activates Cancun, so blob transactions
    * are always rejected on ETC networks.
    */
  /** EIP-1559 Type-2 and EIP-7702 Type-4 transactions are only valid on ETC from Olympia onwards. Pre-Olympia, the fee
    * market is not active on ETC and these transaction formats must be rejected. ETH is exempted — it gates these types
    * via London (Type-2) and Prague (Type-4) activation.
    */
  private def validateOlympiaTxTypes(
      stx: SignedTransaction,
      blockHeader: BlockHeader
  )(implicit blockchainConfig: BlockchainConfig): Either[SignedTransactionError, SignedTransactionValid] =
    // ETH gates these tx types via London/Prague, not Olympia; from Olympia onwards ETC accepts them.
    if blockchainConfig.networkType == com.chipprbots.ethereum.utils.NetworkType.ETH then Right(SignedTransactionValid)
    else if blockHeader.number.value >= blockchainConfig.forkBlockNumbers.olympiaBlockNumber then
      Right(SignedTransactionValid)
    else
      stx.tx match
        case _: TransactionWithDynamicFee =>
          Left(
            SignedTransactionError.TransactionSyntaxError(
              "TYPE_2_TX_NOT_SUPPORTED: EIP-1559 dynamic-fee transactions require Olympia activation"
            )
          )
        case _: SetCodeTransaction =>
          Left(
            SignedTransactionError.TransactionSyntaxError(
              "TYPE_4_TX_NOT_SUPPORTED: EIP-7702 set-code transactions require Olympia activation"
            )
          )
        case _ => Right(SignedTransactionValid)

  private def validateBlobTransactionSupport(
      stx: SignedTransaction,
      blockHeader: BlockHeader
  )(implicit blockchainConfig: BlockchainConfig): Either[SignedTransactionError, SignedTransactionValid] =
    stx.tx match
      case _: BlobTransaction if !blockchainConfig.isCancunTimestamp(blockHeader.unixTimestamp) =>
        Left(
          SignedTransactionError.TransactionSyntaxError(
            "TYPE_3_TX_NOT_SUPPORTED: blob transactions require Cancun activation (not enabled on this network)"
          )
        )
      case _ => Right(SignedTransactionValid)

  /** EIP-1559: reject txs whose maxFeePerGas cannot cover the block's baseFee, and reject txs where
    * maxPriorityFeePerGas > maxFeePerGas. Applies to all dynamic-fee transaction variants (type 2 / 3 / 4). Legacy and
    * Type-1 txs are post-paid at tx.gasPrice.
    */
  private def validateMaxFeeAgainstBaseFee(
      stx: SignedTransaction,
      blockHeader: BlockHeader
  ): Either[SignedTransactionError, SignedTransactionValid] =
    val feeFields: Option[(BigInt, BigInt)] = stx.tx match
      case dyn: com.chipprbots.ethereum.domain.TransactionWithDynamicFee =>
        Some((dyn.maxFeePerGas, dyn.maxPriorityFeePerGas))
      case bt: com.chipprbots.ethereum.domain.BlobTransaction =>
        Some((bt.maxFeePerGas, bt.maxPriorityFeePerGas))
      case sct: com.chipprbots.ethereum.domain.SetCodeTransaction =>
        Some((sct.maxFeePerGas, sct.maxPriorityFeePerGas))
      case _ => None
    feeFields match
      case None => Right(SignedTransactionValid)
      case Some((maxFee, prio)) =>
        val baseFee = blockHeader.baseFee.getOrElse(BigInt(0))
        if prio > maxFee then Left(TransactionSyntaxError(s"maxPriorityFeePerGas ($prio) > maxFeePerGas ($maxFee)"))
        else if maxFee < baseFee then
          Left(
            TransactionSyntaxError(
              s"INSUFFICIENT_MAX_FEE_PER_GAS: maxFeePerGas ($maxFee) < baseFee ($baseFee)"
            )
          )
        else Right(SignedTransactionValid)

  /** EIP-4844: reject blob transactions whose maxFeePerBlobGas < blobBaseFee(block.excessBlobGas). go-ethereum rejects
    * with ErrMaxFeePerBlobGas. Only runs when Cancun is active (blob txs are already rejected pre-Cancun by
    * validateBlobTransactionSupport, but the Cancun gate here defends against future call-site reordering).
    */
  private def validateMaxFeePerBlobGas(
      stx: SignedTransaction,
      blockHeader: BlockHeader
  )(implicit blockchainConfig: BlockchainConfig): Either[SignedTransactionError, SignedTransactionValid] =
    stx.tx match
      case bt: BlobTransaction if blockchainConfig.isCancunTimestamp(blockHeader.unixTimestamp) =>
        val excessBlobGas = blockHeader.excessBlobGas.getOrElse(BigInt(0))
        val blobBaseFee = BlobGasUtils.getBlobGasPrice(excessBlobGas, blockHeader.unixTimestamp, blockchainConfig)
        if bt.maxFeePerBlobGas < blobBaseFee then
          Left(TransactionMaxFeePerBlobGasTooLow(bt.maxFeePerBlobGas, blobBaseFee))
        else Right(SignedTransactionValid)
      case _ => Right(SignedTransactionValid)

  /** Validates if the transaction is syntactically valid (lengths of the transaction fields are correct)
    *
    * @param stx
    *   Transaction to validate
    * @return
    *   Either the validated transaction or TransactionSyntaxError if an error was detected
    */
  private def checkSyntacticValidity(stx: SignedTransaction): Either[SignedTransactionError, SignedTransactionValid] =
    import LegacyTransaction.*
    import stx.*
    import stx.tx.*

    val maxNonceValue = BigInt(2).pow(8 * NonceLength) - 1
    val maxGasValue = BigInt(2).pow(8 * GasLength) - 1
    val maxValue = BigInt(2).pow(8 * ValueLength) - 1
    val maxR = BigInt(2).pow(8 * ECDSASignature.RLength) - 1
    val maxS = BigInt(2).pow(8 * ECDSASignature.SLength) - 1
    // EIP-2681: nonces >= 2^64-1 are invalid (incrementing would overflow uint64)
    val eip2681NonceCap = BigInt(2).pow(64) - 2

    if nonce > maxNonceValue then Left(TransactionSyntaxError(s"Invalid nonce: $nonce > $maxNonceValue"))
    else if nonce > eip2681NonceCap then Left(TransactionSyntaxError(s"EIP-2681: nonce $nonce >= 2^64-1"))
    else if gasLimit > GasAmount(maxGasValue) then
      Left(TransactionSyntaxError(s"Invalid gasLimit: $gasLimit > $maxGasValue"))
    else if gasPrice.value > maxGasValue then
      Left(TransactionSyntaxError(s"Invalid gasPrice: $gasPrice > $maxGasValue"))
    else if value > maxValue then Left(TransactionSyntaxError(s"Invalid value: $value > $maxValue"))
    else if signature.r > maxR then Left(TransactionSyntaxError(s"Invalid signatureRandom: ${signature.r} > $maxR"))
    else if signature.s > maxS then Left(TransactionSyntaxError(s"Invalid signature: ${signature.s} > $maxS"))
    else Right(SignedTransactionValid)

  /** Validates if the transaction signature is valid as stated in appendix F in YP
    *
    * @param stx
    *   Transaction to validate
    * @param blockNumber
    *   Number of the block for this transaction
    * @return
    *   Either the validated transaction or TransactionSignatureError if an error was detected
    */
  private def validateSignature(
      stx: SignedTransaction,
      blockNumber: BigInt
  )(implicit blockchainConfig: BlockchainConfig): Either[SignedTransactionError, SignedTransactionValid] =
    val r = stx.signature.r
    val s = stx.signature.s

    val beforeHomestead = blockNumber < blockchainConfig.forkBlockNumbers.homesteadBlockNumber
    val beforeEIP155 = blockNumber < blockchainConfig.forkBlockNumbers.eip155BlockNumber

    val validR = r > 0 && r < secp256k1n
    val validS = s > 0 && s < (if beforeHomestead then secp256k1n else secp256k1n / 2)

    // Validate signing schema based on transaction type
    val validSigningSchema = stx.tx match
      case _: SetCodeTransaction =>
        // EIP-7702 Type-4 transactions use y-parity (0 or 1) for v
        stx.signature.v == ECDSASignature.negativeYParity || stx.signature.v == ECDSASignature.positiveYParity
      case _: TransactionWithDynamicFee =>
        // EIP-1559 Type-2 transactions use y-parity (0 or 1) for v, same as Type-1
        stx.signature.v == ECDSASignature.negativeYParity || stx.signature.v == ECDSASignature.positiveYParity
      case _: BlobTransaction =>
        // EIP-4844 Type-3 transactions use y-parity (0 or 1) for v, same as Type-1/Type-2
        stx.signature.v == ECDSASignature.negativeYParity || stx.signature.v == ECDSASignature.positiveYParity
      case _: TransactionWithAccessList =>
        // EIP-2930+ transactions use y-parity (0 or 1) for v
        stx.signature.v == ECDSASignature.negativeYParity || stx.signature.v == ECDSASignature.positiveYParity
      case _: LegacyTransaction =>
        val v = stx.signature.v
        // Legacy transactions can use:
        // 1. Unprotected signatures (v = 27 or 28)
        // 2. EIP-155 protected signatures (v = chainId * 2 + 35 or chainId * 2 + 36)
        val isUnprotected = v == ECDSASignature.negativePointSign || v == ECDSASignature.positivePointSign
        val isEIP155Protected = if v >= 35 then
          // Check if v corresponds to valid EIP-155 format: v = chainId * 2 + 35 + {0,1}
          val chainIdFromV = (v - 35) / 2
          v == chainIdFromV * 2 + 35 || v == chainIdFromV * 2 + 36
        else false

        if beforeEIP155 then isUnprotected
        else isUnprotected || isEIP155Protected

    if validR && validS && validSigningSchema then Right(SignedTransactionValid)
    else Left(TransactionSignatureError)

  /** Validates if the transaction nonce matches current sender account's nonce
    *
    * @param stx
    *   Transaction to validate
    * @param senderNonce
    *   Nonce of the sender of the transaction
    * @return
    *   Either the validated transaction or a TransactionNonceError
    */
  private def validateNonce(
      stx: SignedTransaction,
      senderNonce: UInt256
  ): Either[SignedTransactionError, SignedTransactionValid] =
    if senderNonce == UInt256(stx.tx.nonce) then Right(SignedTransactionValid)
    else Left(TransactionNonceError(UInt256(stx.tx.nonce), senderNonce))

  /** Validates the initcode size for contract creation transactions (EIP-3860)
    *
    * @param stx
    *   Transaction to validate
    * @param blockHeaderNumber
    *   Number of the block where the stx transaction was included
    * @return
    *   Either the validated transaction or a TransactionInitCodeSizeError
    */
  private def validateInitCodeSize(
      stx: SignedTransaction,
      blockHeaderNumber: BigInt,
      blockHeaderTimestamp: Timestamp
  )(implicit blockchainConfig: BlockchainConfig): Either[SignedTransactionError, SignedTransactionValid] =
    import stx.tx
    if tx.isContractInit then
      val config = EvmConfig.forBlock(blockHeaderNumber, blockHeaderTimestamp, blockchainConfig)
      config.maxInitCodeSize match
        case Some(maxSize) if config.eip3860Enabled && tx.payload.size > maxSize =>
          Left(TransactionInitCodeSizeError(tx.payload.size, maxSize))
        case _ =>
          Right(SignedTransactionValid)
    else Right(SignedTransactionValid)

  /** Validates the gas limit is no smaller than the intrinsic gas used by the transaction — and, at Amsterdam, no
    * smaller than its calldata floor either.
    *
    * The Amsterdam floor check is execution-specs `validate_transaction`'s second gas check, straight after the
    * intrinsic one: `intrinsic.calldata_floor > tx.gas` makes the transaction invalid. Only at Amsterdam: EIP-7623
    * states the same rule for its own floor, but fukuii has never enforced it on ETH Prague/Osaka or ETC Olympia, and
    * adding it there is a separate, separately reviewed change.
    *
    * @param stx
    *   Transaction to validate
    * @param blockHeaderNumber
    *   Number of the block where the stx transaction was included
    * @return
    *   Either the validated transaction, a TransactionNotEnoughGasForIntrinsicError or (Amsterdam) a
    *   TransactionNotEnoughGasForFloorError
    */
  private def validateGasLimitEnoughForIntrinsicGas(
      stx: SignedTransaction,
      blockHeaderNumber: BigInt,
      blockHeaderTimestamp: Timestamp
  )(implicit blockchainConfig: BlockchainConfig): Either[SignedTransactionError, SignedTransactionValid] =
    import stx.tx
    val config = EvmConfig.forBlock(blockHeaderNumber, blockHeaderTimestamp, blockchainConfig)
    val authListSize = tx match
      case sct: SetCodeTransaction => sct.authorizationList.size
      case _                       => 0
    // EIP-2780 needs the destination, the value and the sender to decompose the base cost. `getSender` is
    // memoised and the caller recovered this sender a line earlier, so this is a cache hit rather than a
    // second ECDSA recovery. A transaction whose sender cannot be recovered fails signature validation
    // before this figure is consulted, so the fallback only has to be harmless, not meaningful.
    val sender = SignedTransaction.getSender(stx).getOrElse(Address(0))
    val txIntrinsicGas =
      config.calcTransactionIntrinsicGas(
        tx.payload,
        tx.isContractInit,
        Transaction.accessList(tx),
        authListSize,
        tx.receivingAddress,
        UInt256(tx.value),
        sender
      )
    if stx.tx.gasLimit < GasAmount(txIntrinsicGas) then
      Left(TransactionNotEnoughGasForIntrinsicError(stx.tx.gasLimit.value, txIntrinsicGas))
    else if config.amsterdamEnabled then
      val floor = config.calcAmsterdamCalldataFloorGas(
        tx.payload,
        Transaction.accessList(tx),
        tx.receivingAddress,
        UInt256(tx.value),
        sender
      )
      if stx.tx.gasLimit < GasAmount(floor) then
        Left(TransactionNotEnoughGasForFloorError(stx.tx.gasLimit.value, floor))
      else Right(SignedTransactionValid)
    else Right(SignedTransactionValid)

  /** Validates the sender account balance contains at least the cost required in up-front payment.
    *
    * @param senderBalance
    *   Balance of the sender of the tx
    * @param upfrontCost
    *   Upfront cost of the transaction tx
    * @return
    *   Either the validated transaction or a TransactionSenderCantPayUpfrontCostError
    */
  private def validateAccountHasEnoughGasToPayUpfrontCost(
      senderBalance: UInt256,
      upfrontCost: UInt256
  ): Either[SignedTransactionError, SignedTransactionValid] =
    if senderBalance >= upfrontCost then Right(SignedTransactionValid)
    else Left(TransactionSenderCantPayUpfrontCostError(upfrontCost, senderBalance))

  /** EIP-7825: Validates that the transaction gas limit does not exceed the per-tx cap (2^24 = 16.77M). Active on ETC
    * post-Olympia block OR on ETH post-Osaka timestamp.
    */
  private def validateTxGasLimitCap(
      stx: SignedTransaction,
      blockHeaderNumber: BigInt,
      blockHeaderTimestamp: Timestamp
  )(implicit blockchainConfig: BlockchainConfig): Either[SignedTransactionError, SignedTransactionValid] =
    val isEth = blockchainConfig.networkType == com.chipprbots.ethereum.utils.NetworkType.ETH
    // EIP-7825 gas cap: ETC enables at Olympia (ECIP-1121 block-based). ETH enables at Osaka
    // timestamp (per execution-specs — Prague does NOT include EIP-7825). On ETH chains hive
    // maps London→olympiaBlockNumber, so we must NOT trip the Olympia gate there.
    val isOlympiaActivated = !isEth && blockHeaderNumber >= blockchainConfig.forkBlockNumbers.olympiaBlockNumber
    val isOsakaActivated = blockchainConfig.isOsakaTimestamp(blockHeaderTimestamp)

    if blockchainConfig.isAmsterdamTimestamp(blockHeaderTimestamp) then
      // EIP-8037 REDEFINES this bound, and getting it wrong rejects every transaction the reservoir
      // exists to serve. TX_MAX_GAS_LIMIT now caps EXECUTION gas only, so `tx.gas` above it is legal —
      // the excess seeds `state_gas_reservoir`. What is capped against 2^24 is the intrinsic cost, and
      // `tx.gas` as a whole is capped against the new TX_MAX_TOTAL_GAS_LIMIT = 2^32 - 1.
      //
      // execution-specs checks the intrinsic cost and the calldata floor against 2^24 separately, after
      // both sufficiency checks (validateGasLimitEnoughForIntrinsicGas), and raises the same error for
      // either — so one comparison against their maximum is the same rule. Its TX_MAX_TOTAL_GAS_LIMIT
      // check comes before the sufficiency checks rather than after; both failing at once needs an
      // intrinsic cost above 2^32 - 1, i.e. hundreds of megabytes of calldata.
      import stx.tx
      val config = EvmConfig.forBlock(blockHeaderNumber, blockHeaderTimestamp, blockchainConfig)
      val authListSize = tx match
        case sct: SetCodeTransaction => sct.authorizationList.size
        case _                       => 0
      val sender = SignedTransaction.getSender(stx).getOrElse(Address(0))
      val intrinsic = config.calcTransactionIntrinsicGas(
        tx.payload,
        tx.isContractInit,
        Transaction.accessList(tx),
        authListSize,
        tx.receivingAddress,
        UInt256(tx.value),
        sender
      )
      val floor = config.calcAmsterdamCalldataFloorGas(
        tx.payload,
        Transaction.accessList(tx),
        tx.receivingAddress,
        UInt256(tx.value),
        sender
      )
      if tx.gasLimit.value > AmsterdamGas.TxMaxTotalGasLimit then
        Left(TransactionGasLimitExceedsCap(tx.gasLimit.value, AmsterdamGas.TxMaxTotalGasLimit))
      else if intrinsic.max(floor) > AmsterdamGas.TxMaxGasLimit then
        Left(TransactionIntrinsicCostExceedsCap(intrinsic, floor, AmsterdamGas.TxMaxGasLimit))
      else Right(SignedTransactionValid)
    else if (isOlympiaActivated || isOsakaActivated) && stx.tx.gasLimit > GasAmount(TxGasLimitCap) then
      Left(TransactionGasLimitExceedsCap(stx.tx.gasLimit.value, TxGasLimitCap))
    else Right(SignedTransactionValid)

  /** The transaction must fit what is left of the block's gas; see [[blockGasCapacityError]].
    *
    * @param stx
    *   Transaction to validate
    * @param blockHeader
    *   Container block: its gas limit, and its timestamp for the fork
    * @return
    *   Either the validated transaction, a TransactionGasLimitTooBigError or (Amsterdam) a
    *   TransactionExecutionGasExceedsBlockCapacity / TransactionStateGasExceedsBlockCapacity
    */
  private def validateBlockHasEnoughGasLimitForTx(
      stx: SignedTransaction,
      blockHeader: BlockHeader,
      accumGasUsed: BigInt,
      accumExecutionGas: BigInt,
      accumStateGas: BigInt
  )(implicit blockchainConfig: BlockchainConfig): Either[SignedTransactionError, SignedTransactionValid] =
    blockGasCapacityError(
      stx.tx.gasLimit.value,
      blockHeader.gasLimit.value,
      blockHeader.unixTimestamp,
      accumGasUsed,
      accumExecutionGas,
      accumStateGas
    ).toLeft(SignedTransactionValid)

  /** Whether a transaction with gas limit `txGasLimit` still fits a block, given what the block's earlier transactions
    * used: `None` if it fits. The one statement of the rule — block validation applies it, and the payload builder uses
    * it to leave out transactions that can no longer fit.
    *
    * Before Amsterdam, and on every ETC fork, one counter: `txGasLimit + accumGasUsed <= blockGasLimit`, where
    * `accumGasUsed` is the receipt sum.
    *
    * Amsterdam (EIP-8037; execution-specs `check_block_gas_capacity`, go-ethereum `GasPool.CheckGasAmsterdam`) checks
    * each dimension against its own remaining budget:
    * {{{
    * min(TX_MAX_GAS_LIMIT, txGasLimit) <= blockGasLimit - accumExecutionGas   // at most 2^24 of execution per tx
    * txGasLimit                       <= blockGasLimit - accumStateGas
    * }}}
    * The receipt sum plays no part. Receipts add both dimensions while the header takes their maximum, so the sum can
    * legitimately approach twice the gas limit: the single-counter rule would reject valid Amsterdam blocks — and,
    * since execution is counted before refunds (EIP-7778) while receipts are after them, accept invalid ones.
    */
  def blockGasCapacityError(
      txGasLimit: BigInt,
      blockGasLimit: BigInt,
      blockTimestamp: Timestamp,
      accumGasUsed: BigInt,
      accumExecutionGas: BigInt,
      accumStateGas: BigInt
  )(implicit blockchainConfig: BlockchainConfig): Option[SignedTransactionError] =
    if !blockchainConfig.isAmsterdamTimestamp(blockTimestamp) then
      Option.when(txGasLimit + accumGasUsed > blockGasLimit)(
        TransactionGasLimitTooBigError(txGasLimit, accumGasUsed, blockGasLimit)
      )
    else
      val executionReservation = txGasLimit.min(AmsterdamGas.TxMaxGasLimit)
      if executionReservation > blockGasLimit - accumExecutionGas then
        Some(TransactionExecutionGasExceedsBlockCapacity(executionReservation, accumExecutionGas, blockGasLimit))
      else if txGasLimit > blockGasLimit - accumStateGas then
        Some(TransactionStateGasExceedsBlockCapacity(txGasLimit, accumStateGas, blockGasLimit))
      else None
