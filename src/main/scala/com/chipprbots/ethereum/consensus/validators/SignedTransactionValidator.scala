package com.chipprbots.ethereum.consensus.validators

import com.chipprbots.ethereum.domain._
import com.chipprbots.ethereum.utils.BlockchainConfig

trait SignedTransactionValidator {
  def validate(
      stx: SignedTransaction,
      senderAccount: Account,
      blockHeader: BlockHeader,
      upfrontGasCost: UInt256,
      accumGasUsed: BigInt
  )(implicit blockchainConfig: BlockchainConfig): Either[SignedTransactionError, SignedTransactionValid]
}

sealed trait SignedTransactionError

object SignedTransactionError {
  case object TransactionSignatureError extends SignedTransactionError
  case class TransactionSyntaxError(reason: String) extends SignedTransactionError
  // toString values include canonical EEST exception names (e.g. NONCE_MISMATCH_TOO_LOW)
  // so the hive consume-engine test framework's exception mapper can match them.
  case class TransactionNonceError(txNonce: UInt256, senderNonce: UInt256) extends SignedTransactionError {
    override def toString: String = {
      val canonical = if (txNonce < senderNonce) "NONCE_MISMATCH_TOO_LOW" else "NONCE_MISMATCH_TOO_HIGH"
      s"$canonical: Got tx nonce $txNonce but sender in mpt is: $senderNonce"
    }
  }
  case class TransactionNotEnoughGasForIntrinsicError(txGasLimit: BigInt, txIntrinsicGas: BigInt)
      extends SignedTransactionError {
    override def toString: String =
      s"INTRINSIC_GAS_TOO_LOW: Tx gas limit ($txGasLimit) < tx intrinsic gas ($txIntrinsicGas)"
  }
  case class TransactionSenderCantPayUpfrontCostError(upfrontCost: UInt256, senderBalance: UInt256)
      extends SignedTransactionError {
    override def toString: String =
      s"INSUFFICIENT_ACCOUNT_FUNDS: upfrontcost ($upfrontCost) > sender balance ($senderBalance)"
  }
  case class TransactionGasLimitTooBigError(txGasLimit: BigInt, accumGasUsed: BigInt, blockGasLimit: BigInt)
      extends SignedTransactionError {
    override def toString: String =
      s"GAS_LIMIT_EXCEEDS_BLOCK_GAS_LIMIT: Tx gas limit ($txGasLimit) + gas accum ($accumGasUsed) > block gas limit ($blockGasLimit)"
  }
  case class TransactionInitCodeSizeError(actualSize: BigInt, maxSize: BigInt) extends SignedTransactionError {
    override def toString: String =
      s"INITCODE_SIZE_EXCEEDED: initcode size ($actualSize) exceeds maximum ($maxSize)"
  }
  case class TransactionGasLimitExceedsCap(txGasLimit: BigInt, cap: BigInt) extends SignedTransactionError {
    override def toString: String =
      s"GAS_LIMIT_EXCEEDS_MAXIMUM_GAS_LIMIT: Tx gas limit ($txGasLimit) exceeds per-tx cap ($cap)"
  }
}

sealed trait SignedTransactionValid
case object SignedTransactionValid extends SignedTransactionValid
