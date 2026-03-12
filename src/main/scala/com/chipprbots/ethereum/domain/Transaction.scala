package com.chipprbots.ethereum.domain

import org.apache.pekko.util.ByteString

import org.bouncycastle.util.encoders.Hex

sealed trait Transaction extends Product with Serializable {
  def nonce: BigInt
  def gasPrice: BigInt
  def gasLimit: BigInt
  def receivingAddress: Option[Address]
  def value: BigInt
  def payload: ByteString

  def isContractInit: Boolean = receivingAddress.isEmpty

  protected def receivingAddressString: String =
    receivingAddress.map(_.toString).getOrElse("[Contract creation]")

  protected def payloadString: String =
    s"${if (isContractInit) "ContractInit: " else "TransactionData: "}${Hex.toHexString(payload.toArray[Byte])}"
}

object Transaction {
  val Type01: Byte = 1.toByte
  val Type02: Byte = 2.toByte
  val Type04: Byte = 4.toByte

  val MinAllowedType: Byte = 0
  val MaxAllowedType: Byte = 0x7f

  val LegacyThresholdLowerBound: Int = 0xc0
  val LegacyThresholdUpperBound: Int = 0xfe

  def withGasLimit(gl: BigInt): Transaction => Transaction = {
    case tx: LegacyTransaction          => tx.copy(gasLimit = gl)
    case tx: TransactionWithAccessList  => tx.copy(gasLimit = gl)
    case tx: TransactionWithDynamicFee  => tx.copy(gasLimit = gl)
    case tx: SetCodeTransaction         => tx.copy(gasLimit = gl)
  }

  def accessList(tx: Transaction): List[AccessListItem] =
    tx match {
      case tx: TransactionWithDynamicFee  => tx.accessList
      case tx: TransactionWithAccessList  => tx.accessList
      case tx: SetCodeTransaction         => tx.accessList
      case _: LegacyTransaction           => Nil
    }

  /** Compute the effective gas price for a transaction given the block's baseFee.
    * For Type-2 (EIP-1559): min(maxFeePerGas, baseFee + maxPriorityFeePerGas)
    * For Legacy and Type-1: gasPrice (baseFee is ignored)
    */
  def effectiveGasPrice(tx: Transaction, baseFee: Option[BigInt]): BigInt =
    tx match {
      case tx: TransactionWithDynamicFee =>
        val base = baseFee.getOrElse(BigInt(0))
        tx.maxFeePerGas.min(base + tx.maxPriorityFeePerGas)
      case tx: SetCodeTransaction =>
        val base = baseFee.getOrElse(BigInt(0))
        tx.maxFeePerGas.min(base + tx.maxPriorityFeePerGas)
      case _ => tx.gasPrice
    }

  implicit class TransactionTypeValidator(val transactionType: Byte) extends AnyVal {
    def isValidTransactionType: Boolean = transactionType >= MinAllowedType && transactionType <= MaxAllowedType
  }

  implicit class ByteArrayTransactionTypeValidator(val binaryData: Array[Byte]) extends AnyVal {
    def isValidTransactionType: Boolean = binaryData.length == 1 && binaryData.head.isValidTransactionType
  }
}

sealed trait TypedTransaction extends Transaction

object LegacyTransaction {
  val NonceLength = 32
  val GasLength = 32
  val ValueLength = 32

  def apply(
      nonce: BigInt,
      gasPrice: BigInt,
      gasLimit: BigInt,
      receivingAddress: Address,
      value: BigInt,
      payload: ByteString
  ): LegacyTransaction =
    LegacyTransaction(nonce, gasPrice, gasLimit, Some(receivingAddress), value, payload)
}

case class LegacyTransaction(
    nonce: BigInt,
    gasPrice: BigInt,
    gasLimit: BigInt,
    receivingAddress: Option[Address],
    value: BigInt,
    payload: ByteString
) extends Transaction {

  override def toString: String =
    s"LegacyTransaction {" +
      s"nonce: $nonce " +
      s"gasPrice: $gasPrice " +
      s"gasLimit: $gasLimit " +
      s"receivingAddress: $receivingAddressString " +
      s"value: $value wei " +
      s"payload: $payloadString " +
      s"}"
}

object TransactionWithAccessList {
  def apply(
      chainId: BigInt,
      nonce: BigInt,
      gasPrice: BigInt,
      gasLimit: BigInt,
      receivingAddress: Address,
      value: BigInt,
      payload: ByteString,
      accessList: List[AccessListItem]
  ): TransactionWithAccessList =
    TransactionWithAccessList(chainId, nonce, gasPrice, gasLimit, Some(receivingAddress), value, payload, accessList)
}

case class TransactionWithAccessList(
    chainId: BigInt,
    nonce: BigInt,
    gasPrice: BigInt,
    gasLimit: BigInt,
    receivingAddress: Option[Address],
    value: BigInt,
    payload: ByteString,
    accessList: List[AccessListItem]
) extends TypedTransaction {
  override def toString: String =
    s"TransactionWithAccessList {" +
      s"nonce: $nonce " +
      s"gasPrice: $gasPrice " +
      s"gasLimit: $gasLimit " +
      s"receivingAddress: $receivingAddressString " +
      s"value: $value wei " +
      s"payload: $payloadString " +
      s"accessList: $accessList" +
      s"}"
}

object TransactionWithDynamicFee {
  def apply(
      chainId: BigInt,
      nonce: BigInt,
      maxPriorityFeePerGas: BigInt,
      maxFeePerGas: BigInt,
      gasLimit: BigInt,
      receivingAddress: Address,
      value: BigInt,
      payload: ByteString,
      accessList: List[AccessListItem]
  ): TransactionWithDynamicFee =
    TransactionWithDynamicFee(
      chainId, nonce, maxPriorityFeePerGas, maxFeePerGas, gasLimit,
      Some(receivingAddress), value, payload, accessList
    )
}

/** EIP-1559 Type-2 transaction with dynamic fee market.
  * gasPrice is defined as maxFeePerGas for upfront cost calculation compatibility.
  */
case class TransactionWithDynamicFee(
    chainId: BigInt,
    nonce: BigInt,
    maxPriorityFeePerGas: BigInt,
    maxFeePerGas: BigInt,
    gasLimit: BigInt,
    receivingAddress: Option[Address],
    value: BigInt,
    payload: ByteString,
    accessList: List[AccessListItem]
) extends TypedTransaction {
  /** For upfront cost calculation, use maxFeePerGas as the worst-case gas price */
  override def gasPrice: BigInt = maxFeePerGas

  override def toString: String =
    s"TransactionWithDynamicFee {" +
      s"nonce: $nonce " +
      s"maxPriorityFeePerGas: $maxPriorityFeePerGas " +
      s"maxFeePerGas: $maxFeePerGas " +
      s"gasLimit: $gasLimit " +
      s"receivingAddress: $receivingAddressString " +
      s"value: $value wei " +
      s"payload: $payloadString " +
      s"accessList: $accessList" +
      s"}"
}

case class AccessListItem(address: Address, storageKeys: List[BigInt]) // bytes32

/** EIP-7702 authorization tuple signed by the authority (account being delegated). */
case class SetCodeAuthorization(
    chainId: BigInt,
    address: Address,
    nonce: BigInt,
    v: BigInt,
    r: BigInt,
    s: BigInt
)

/** EIP-7702 Type-4 transaction: Set EOA Account Code.
  * Similar to EIP-1559 but with an additional authorization list.
  * Must have a To address (no contract creation).
  * gasPrice is defined as maxFeePerGas for upfront cost calculation compatibility.
  */
case class SetCodeTransaction(
    chainId: BigInt,
    nonce: BigInt,
    maxPriorityFeePerGas: BigInt,
    maxFeePerGas: BigInt,
    gasLimit: BigInt,
    receivingAddress: Option[Address],
    value: BigInt,
    payload: ByteString,
    accessList: List[AccessListItem],
    authorizationList: List[SetCodeAuthorization]
) extends TypedTransaction {
  override def gasPrice: BigInt = maxFeePerGas

  override def toString: String =
    s"SetCodeTransaction {" +
      s"nonce: $nonce " +
      s"maxPriorityFeePerGas: $maxPriorityFeePerGas " +
      s"maxFeePerGas: $maxFeePerGas " +
      s"gasLimit: $gasLimit " +
      s"receivingAddress: $receivingAddressString " +
      s"value: $value wei " +
      s"payload: $payloadString " +
      s"accessList: $accessList " +
      s"authorizationList: ${authorizationList.size} auths" +
      s"}"
}

object SetCodeTransaction {
  /** EIP-7702 delegation prefix: 0xef0100 */
  val DelegationPrefix: Array[Byte] = Array(0xef.toByte, 0x01.toByte, 0x00.toByte)
  val DelegationCodeLength: Int = 23 // 3 prefix + 20 address

  def isDelegation(code: ByteString): Boolean =
    code.length == DelegationCodeLength && code.startsWith(ByteString(DelegationPrefix))

  def parseDelegation(code: ByteString): Option[Address] =
    if (isDelegation(code)) Some(Address(code.drop(3)))
    else None

  def addressToDelegation(addr: Address): ByteString =
    ByteString(DelegationPrefix) ++ addr.bytes
}
