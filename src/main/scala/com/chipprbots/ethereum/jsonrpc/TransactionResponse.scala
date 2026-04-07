package com.chipprbots.ethereum.jsonrpc

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.domain._
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.Config

trait BaseTransactionResponse {
  def hash: ByteString
  def nonce: BigInt
  def blockHash: Option[ByteString]
  def blockNumber: Option[BigInt]
  def transactionIndex: Option[BigInt]
  def from: Option[ByteString]
  def to: Option[ByteString]
  def value: BigInt
  def gasPrice: BigInt
  def gas: BigInt
  def input: ByteString
}

final case class TransactionResponse(
    hash: ByteString,
    nonce: BigInt,
    blockHash: Option[ByteString],
    blockNumber: Option[BigInt],
    transactionIndex: Option[BigInt],
    from: Option[ByteString],
    to: Option[ByteString],
    value: BigInt,
    gasPrice: BigInt,
    gas: BigInt,
    input: ByteString,
    `type`: Option[BigInt],
    chainId: Option[BigInt],
    maxFeePerGas: Option[BigInt],
    maxPriorityFeePerGas: Option[BigInt],
    accessList: Option[Seq[Map[String, Any]]],
    maxFeePerBlobGas: Option[BigInt],
    blobVersionedHashes: Option[Seq[ByteString]],
    yParity: Option[BigInt],
    v: Option[BigInt],
    r: Option[BigInt],
    s: Option[BigInt],
    blockTimestamp: Option[BigInt]
) extends BaseTransactionResponse

final case class TransactionData(
    stx: SignedTransaction,
    blockHeader: Option[BlockHeader] = None,
    transactionIndex: Option[Int] = None
)

object TransactionResponse {

  implicit val blockchainConfig: BlockchainConfig = Config.blockchains.blockchainConfig

  def apply(tx: TransactionData): TransactionResponse =
    TransactionResponse(tx.stx, tx.blockHeader, tx.transactionIndex)

  def apply(
      stx: SignedTransaction,
      blockHeader: Option[BlockHeader] = None,
      transactionIndex: Option[Int] = None
  ): TransactionResponse = {
    val (txType, txChainId, txMaxFee, txMaxPriority, txAccessList, txMaxBlobFee, txBlobHashes) = stx.tx match {
      case _: LegacyTransaction => (BigInt(0), None, None, None, None, None, None)
      case tx: TransactionWithAccessList =>
        (BigInt(1), Some(tx.chainId), None, None, Some(encodeAccessList(tx.accessList)), None, None)
      case tx: TransactionWithDynamicFee =>
        (BigInt(2), Some(tx.chainId), Some(tx.maxFeePerGas), Some(tx.maxPriorityFeePerGas),
          Some(encodeAccessList(tx.accessList)), None, None)
      case tx: BlobTransaction =>
        (BigInt(3), Some(tx.chainId), Some(tx.maxFeePerGas), Some(tx.maxPriorityFeePerGas),
          Some(encodeAccessList(tx.accessList)), Some(tx.maxFeePerBlobGas), Some(tx.blobVersionedHashes))
      case tx: SetCodeTransaction =>
        (BigInt(4), Some(tx.chainId), Some(tx.maxFeePerGas), Some(tx.maxPriorityFeePerGas),
          Some(encodeAccessList(tx.accessList)), None, None)
    }

    val effectiveGasPrice = Transaction.effectiveGasPrice(stx.tx, blockHeader.flatMap(_.baseFee))

    TransactionResponse(
      hash = stx.hash,
      nonce = stx.tx.nonce,
      blockHash = blockHeader.map(_.hash),
      blockNumber = blockHeader.map(_.number),
      transactionIndex = transactionIndex.map(txIndex => BigInt(txIndex)),
      from = SignedTransaction.getSender(stx).map(_.bytes),
      to = stx.tx.receivingAddress.map(_.bytes),
      value = stx.tx.value,
      gasPrice = effectiveGasPrice,
      gas = stx.tx.gasLimit,
      input = stx.tx.payload,
      `type` = Some(txType),
      chainId = txChainId,
      maxFeePerGas = txMaxFee,
      maxPriorityFeePerGas = txMaxPriority,
      accessList = txAccessList,
      maxFeePerBlobGas = txMaxBlobFee,
      blobVersionedHashes = txBlobHashes,
      // yParity only for typed transactions (type >= 1), not legacy
      yParity = if (txType > 0) Some(stx.signature.v) else None,
      v = Some(stx.signature.v),
      r = Some(stx.signature.r),
      s = Some(stx.signature.s),
      blockTimestamp = blockHeader.map(h => BigInt(h.unixTimestamp))
    )
  }

  private def encodeAccessList(accessList: List[AccessListItem]): Seq[Map[String, Any]] =
    accessList.map { item =>
      Map(
        "address" -> item.address,
        "storageKeys" -> item.storageKeys
      )
    }

}
