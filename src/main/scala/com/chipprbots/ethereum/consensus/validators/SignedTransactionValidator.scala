package com.chipprbots.ethereum.consensus.validators

import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.utils.BlockchainConfig

trait SignedTransactionValidator:
  def validate(
      stx: SignedTransaction,
      senderAccount: Account,
      blockHeader: BlockHeader,
      upfrontGasCost: UInt256,
      accumGasUsed: BigInt
  )(implicit blockchainConfig: BlockchainConfig): Either[SignedTransactionError, SignedTransactionValid]

sealed trait SignedTransactionError

object SignedTransactionError:
  case object TransactionSignatureError extends SignedTransactionError
  case class TransactionSyntaxError(reason: String) extends SignedTransactionError
  // toString values include canonical EEST exception names (e.g. NONCE_MISMATCH_TOO_LOW)
  // so the hive consume-engine test framework's exception mapper can match them.
  case class TransactionNonceError(txNonce: UInt256, senderNonce: UInt256) extends SignedTransactionError:
    override def toString: String =
      val canonical = if txNonce < senderNonce then "NONCE_MISMATCH_TOO_LOW" else "NONCE_MISMATCH_TOO_HIGH"
      s"$canonical: Got tx nonce $txNonce but sender in mpt is: $senderNonce"
  case class TransactionNotEnoughGasForIntrinsicError(txGasLimit: BigInt, txIntrinsicGas: BigInt)
      extends SignedTransactionError:
    override def toString: String =
      s"INTRINSIC_GAS_TOO_LOW: Tx gas limit ($txGasLimit) < tx intrinsic gas ($txIntrinsicGas)"

  /** Amsterdam: `tx.gas` below the EIP-7976/EIP-7981 calldata floor (execution-specs `validate_transaction`,
    * "Insufficient calldata floor").
    */
  case class TransactionNotEnoughGasForFloorError(txGasLimit: BigInt, floorGas: BigInt) extends SignedTransactionError:
    override def toString: String =
      s"INTRINSIC_GAS_BELOW_FLOOR_GAS_COST: Tx gas limit ($txGasLimit) < calldata floor gas ($floorGas)"

  /** Amsterdam (EIP-8037): the intrinsic execution gas or the calldata floor exceeds TX_MAX_GAS_LIMIT. `tx.gas` itself
    * may exceed it — the excess seeds the state-gas reservoir — but these two may not. execution-specs raises
    * `InsufficientTransactionGasError` for both, which the fixtures expect as INTRINSIC_GAS_TOO_LOW.
    */
  case class TransactionIntrinsicCostExceedsCap(intrinsicGas: BigInt, floorGas: BigInt, cap: BigInt)
      extends SignedTransactionError:
    override def toString: String =
      s"INTRINSIC_GAS_TOO_LOW: intrinsic execution gas ($intrinsicGas) or calldata floor ($floorGas) " +
        s"exceeds TX_MAX_GAS_LIMIT ($cap)"
  case class TransactionSenderCantPayUpfrontCostError(upfrontCost: UInt256, senderBalance: UInt256)
      extends SignedTransactionError:
    override def toString: String =
      s"INSUFFICIENT_ACCOUNT_FUNDS: upfrontcost ($upfrontCost) > sender balance ($senderBalance)"
  case class TransactionGasLimitTooBigError(txGasLimit: BigInt, accumGasUsed: BigInt, blockGasLimit: BigInt)
      extends SignedTransactionError:
    override def toString: String =
      s"GAS_LIMIT_EXCEEDS_BLOCK_GAS_LIMIT: Tx gas limit ($txGasLimit) + gas accum ($accumGasUsed) > block gas limit ($blockGasLimit)"
  case class TransactionInitCodeSizeError(actualSize: BigInt, maxSize: BigInt) extends SignedTransactionError:
    override def toString: String =
      s"INITCODE_SIZE_EXCEEDED: initcode size ($actualSize) exceeds maximum ($maxSize)"
  case class TransactionGasLimitExceedsCap(txGasLimit: BigInt, cap: BigInt) extends SignedTransactionError:
    override def toString: String =
      s"GAS_LIMIT_EXCEEDS_MAXIMUM_GAS_LIMIT: Tx gas limit ($txGasLimit) exceeds per-tx cap ($cap)"
  case class TransactionMaxFeePerBlobGasTooLow(maxFeePerBlobGas: BigInt, blobBaseFee: BigInt)
      extends SignedTransactionError:
    override def toString: String =
      s"FEE_CAP_LESS_THAN_BLOB_BASE_FEE: maxFeePerBlobGas ($maxFeePerBlobGas) < blobBaseFee ($blobBaseFee)"

sealed trait SignedTransactionValid
case object SignedTransactionValid extends SignedTransactionValid
