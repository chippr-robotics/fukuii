package com.chipprbots.ethereum.jsonrpc

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.utils.BlockchainConfig

trait BaseTransactionResponse:
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
    authorizationList: Option[Seq[Map[String, Any]]],
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

/** Builds the JSON-RPC transaction object.
  *
  * The chain config is the serving node's, taken as a `using` parameter. It decides the sender of an EIP-155 legacy
  * transaction: `SignedTransaction.getSender` recovers a sender only when the `v` value names this chain's id. This
  * object used to supply the global `Config.blockchains.blockchainConfig` itself, which is the first configured
  * chain's, so a second chain instance in the same JVM rendered its own legacy transactions with `from: null`.
  */
object TransactionResponse:

  def apply(tx: TransactionData)(using BlockchainConfig): TransactionResponse =
    TransactionResponse(tx.stx, tx.blockHeader, tx.transactionIndex)

  def apply(
      stx: SignedTransaction,
      blockHeader: Option[BlockHeader] = None,
      transactionIndex: Option[Int] = None
  )(using BlockchainConfig): TransactionResponse =
    val (txType, txChainId, txMaxFee, txMaxPriority, txAccessList, txMaxBlobFee, txBlobHashes, txAuthList) =
      stx.tx match
        case _: LegacyTransaction =>
          // EIP-155: extract chainId from v value for replay-protected legacy txs
          val legacyChainId = if stx.signature.v > 35 then Some((stx.signature.v - 35) / 2) else None
          (BigInt(0), legacyChainId, None, None, None, None, None, None)
        case tx: TransactionWithAccessList =>
          (BigInt(1), Some(tx.chainId), None, None, Some(encodeAccessList(tx.accessList)), None, None, None)
        case tx: TransactionWithDynamicFee =>
          (
            BigInt(2),
            Some(tx.chainId),
            Some(tx.maxFeePerGas),
            Some(tx.maxPriorityFeePerGas),
            Some(encodeAccessList(tx.accessList)),
            None,
            None,
            None
          )
        case tx: BlobTransaction =>
          (
            BigInt(3),
            Some(tx.chainId),
            Some(tx.maxFeePerGas),
            Some(tx.maxPriorityFeePerGas),
            Some(encodeAccessList(tx.accessList)),
            Some(tx.maxFeePerBlobGas),
            Some(tx.blobVersionedHashes.map(_.value)),
            None
          )
        case tx: SetCodeTransaction =>
          (
            BigInt(4),
            Some(tx.chainId),
            Some(tx.maxFeePerGas),
            Some(tx.maxPriorityFeePerGas),
            Some(encodeAccessList(tx.accessList)),
            None,
            None,
            Some(encodeAuthorizationList(tx.authorizationList))
          )

    // A mined tx reports the price it paid: min(maxFeePerGas, baseFee + tip) for the fee-market types. A pending tx has
    // paid nothing yet, so it reports its fee cap (maxFeePerGas; gasPrice for legacy and access-list txs), as
    // go-ethereum's newRPCTransaction does. Before this, a pending typed tx reported min(maxFee, 0 + tip) = its tip.
    // A block without a base fee (pre-London/pre-Olympia) cannot carry a fee-market tx; it falls back to the fee cap.
    val effectiveGasPrice = blockHeader.flatMap(_.baseFee) match
      case Some(baseFee) => Transaction.effectiveGasPrice(stx.tx, Some(baseFee))
      case None          => stx.tx.gasPrice.value

    TransactionResponse(
      hash = stx.hash.value,
      nonce = stx.tx.nonce,
      blockHash = blockHeader.map(_.hash.value),
      blockNumber = blockHeader.map(_.number.value),
      transactionIndex = transactionIndex.map(txIndex => BigInt(txIndex)),
      from = SignedTransaction.getSender(stx).map(_.bytes),
      to = stx.tx.receivingAddress.map(_.bytes),
      value = stx.tx.value,
      gasPrice = effectiveGasPrice,
      gas = stx.tx.gasLimit.value,
      input = stx.tx.payload,
      `type` = Some(txType),
      chainId = txChainId,
      maxFeePerGas = txMaxFee,
      maxPriorityFeePerGas = txMaxPriority,
      accessList = txAccessList,
      maxFeePerBlobGas = txMaxBlobFee,
      blobVersionedHashes = txBlobHashes,
      authorizationList = txAuthList,
      // yParity only for typed transactions (type >= 1), not legacy
      yParity = if txType > 0 then Some(stx.signature.v) else None,
      v = Some(stx.signature.v),
      r = Some(stx.signature.r),
      s = Some(stx.signature.s),
      // uint64 bit pattern -> unsigned BigInt; see BlockResponse.scala for the sign-extension bug this avoids.
      blockTimestamp = blockHeader.map(h => h.unixTimestamp.toUnsignedBigInt)
    )

  private def encodeAccessList(accessList: List[AccessListItem]): Seq[Map[String, Any]] =
    accessList.map { item =>
      Map(
        "address" -> item.address,
        "storageKeys" -> item.storageKeys
      )
    }

  private def encodeAuthorizationList(authList: List[SetCodeAuthorization]): Seq[Map[String, Any]] =
    authList.map { auth =>
      Map(
        "chainId" -> auth.chainId,
        "address" -> auth.address,
        "nonce" -> auth.nonce,
        "yParity" -> auth.v,
        "r" -> auth.r,
        "s" -> auth.s
      )
    }
