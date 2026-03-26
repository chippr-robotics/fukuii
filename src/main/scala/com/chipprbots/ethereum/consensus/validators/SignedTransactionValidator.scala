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
  case class TransactionNonceError(txNonce: UInt256, senderNonce: UInt256) extends SignedTransactionError {
    override def toString: String =
      s"${getClass.getSimpleName}(Got tx nonce $txNonce but sender in mpt is: $senderNonce)"
  }
  case class TransactionNotEnoughGasForIntrinsicError(txGasLimit: BigInt, txIntrinsicGas: BigInt)
      extends SignedTransactionError {
    override def toString: String =
      s"${getClass.getSimpleName}(Tx gas limit ($txGasLimit) < tx intrinsic gas ($txIntrinsicGas))"
  }
  case class TransactionSenderCantPayUpfrontCostError(upfrontCost: UInt256, senderBalance: UInt256)
      extends SignedTransactionError {
    override def toString: String =
      s"${getClass.getSimpleName}(Upfrontcost ($upfrontCost) > sender balance ($senderBalance))"
  }
  case class TransactionGasLimitTooBigError(txGasLimit: BigInt, accumGasUsed: BigInt, blockGasLimit: BigInt)
      extends SignedTransactionError {
    override def toString: String =
      s"${getClass.getSimpleName}(Tx gas limit ($txGasLimit) + gas accum ($accumGasUsed) > block gas limit ($blockGasLimit))"
  }
  case class TransactionInitCodeSizeError(actualSize: BigInt, maxSize: BigInt) extends SignedTransactionError {
    override def toString: String =
      s"${getClass.getSimpleName}(Transaction initcode size ($actualSize) exceeds maximum ($maxSize))"
  }
  case class TransactionGasLimitExceedsCap(txGasLimit: BigInt, cap: BigInt) extends SignedTransactionError {
    override def toString: String =
      s"${getClass.getSimpleName}(Tx gas limit ($txGasLimit) exceeds per-tx cap ($cap))"
  }
  case class TransactionTypeNotSupported(txType: String, requiredFork: String) extends SignedTransactionError {
    override def toString: String =
      s"${getClass.getSimpleName}($txType transactions not supported before $requiredFork fork)"
  }
}

sealed trait SignedTransactionValid
case object SignedTransactionValid extends SignedTransactionValid
