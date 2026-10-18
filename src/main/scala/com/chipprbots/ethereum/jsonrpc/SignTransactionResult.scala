package com.chipprbots.ethereum.jsonrpc

import org.apache.pekko.util.ByteString

import org.bouncycastle.util.encoders.Hex
import org.json4s.JsonAST.*

import com.chipprbots.ethereum.crypto.ECDSASignature
import com.chipprbots.ethereum.domain.BlobTransaction
import com.chipprbots.ethereum.domain.LegacyTransaction
import com.chipprbots.ethereum.domain.SetCodeTransaction
import com.chipprbots.ethereum.domain.SignedTransaction
import com.chipprbots.ethereum.domain.Transaction
import com.chipprbots.ethereum.domain.TransactionWithAccessList
import com.chipprbots.ethereum.domain.TransactionWithDynamicFee

/** go-ethereum `SignTransactionResult` (internal/ethapi/api.go:1895-1899): the RLP of a transaction and the transaction
  * itself. eth_signTransaction / personal_signTransaction return it signed, eth_fillTransaction unsigned (zero `v`,
  * `r`, `s`, as geth's `FillTransaction` marshals the unsigned `types.Transaction`).
  *
  * `raw` is go-ethereum `Transaction.MarshalBinary`: the EIP-2718 envelope, in network form (blobs, commitments and
  * proofs wrapped around the payload) when a blob sidecar is attached.
  */
final case class SignTransactionResult(stx: SignedTransaction, sidecar: Option[BlobTxSidecar]):
  def raw: ByteString = sidecar.fold(RawTransactionCodec.asRawTransaction(stx))(_.networkEncoding(stx))

object SignTransactionResult:

  /** An unsigned transaction, as eth_fillTransaction returns it. */
  def unsigned(tx: Transaction, sidecar: Option[BlobTxSidecar]): SignTransactionResult =
    SignTransactionResult(SignedTransaction(tx, ECDSASignature(0, 0, 0)), sidecar)

  def encode(r: SignTransactionResult): JObject =
    JObject("raw" -> bytes(r.raw), "tx" -> encodeTransaction(r.stx, r.sidecar))

  /** go-ethereum `Transaction.MarshalJSON` (core/types/transaction_marshalling.go:80-174), field for field and in its
    * order. Fields without `omitempty` there (`nonce`, `to`, `gas`, `gasPrice`, `maxPriorityFeePerGas`, `maxFeePerGas`,
    * `value`, `input`, `v`, `r`, `s`) are always present and `null` where the type has none; `chainId` appears on a
    * legacy transaction only when it is EIP-155 protected; `yParity` only on typed transactions; `accessList` on every
    * typed transaction, `[]` when empty; `blobVersionedHashes` / `authorizationList` / the sidecar only when non-empty.
    * No `from`: geth's marshalled transaction has none.
    */
  def encodeTransaction(stx: SignedTransaction, sidecar: Option[BlobTxSidecar]): JObject =
    val tx = stx.tx
    val sig = stx.signature
    val (txType, chainId, gasPrice, tip, maxFee) = tx match
      case t: LegacyTransaction =>
        // geth isProtectedV: v other than 0, 1, 27, 28 is EIP-155; chain id = (v - 35) / 2
        val protectedChainId = Option.when(!Set(BigInt(0), BigInt(1), BigInt(27), BigInt(28)).contains(sig.v))(
          (sig.v - 35) / 2
        )
        (0, protectedChainId, Some(t.gasPrice.value), None, None)
      case t: TransactionWithAccessList => (1, Some(t.chainId), Some(t.gasPrice.value), None, None)
      case t: TransactionWithDynamicFee =>
        (2, Some(t.chainId), None, Some(t.maxPriorityFeePerGas), Some(t.maxFeePerGas))
      case t: BlobTransaction    => (3, Some(t.chainId), None, Some(t.maxPriorityFeePerGas), Some(t.maxFeePerGas))
      case t: SetCodeTransaction => (4, Some(t.chainId), None, Some(t.maxPriorityFeePerGas), Some(t.maxFeePerGas))
    val accessList = tx match
      case _: LegacyTransaction => None
      case t                    => Some(Transaction.accessList(t))
    val blobFields = tx match
      case t: BlobTransaction => (Some(t.maxFeePerBlobGas), t.blobVersionedHashes.map(_.value))
      case _                  => (None, Nil)
    val authList = tx match
      case t: SetCodeTransaction => t.authorizationList
      case _                     => Nil
    def nonEmpty[A](xs: List[A]): Option[List[A]] = Option.when(xs.nonEmpty)(xs)
    val fields: List[(String, Option[JValue])] = List(
      "type" -> Some(q(BigInt(txType))),
      "chainId" -> chainId.map(q),
      "nonce" -> Some(q(tx.nonce)),
      "to" -> Some(tx.receivingAddress.fold[JValue](JNull)(a => bytes(a.bytes))),
      "gas" -> Some(q(tx.gasLimit.value)),
      "gasPrice" -> Some(gasPrice.fold[JValue](JNull)(q)),
      "maxPriorityFeePerGas" -> Some(tip.fold[JValue](JNull)(q)),
      "maxFeePerGas" -> Some(maxFee.fold[JValue](JNull)(q)),
      "maxFeePerBlobGas" -> blobFields._1.map(q),
      "value" -> Some(q(tx.value)),
      "input" -> Some(bytes(tx.payload)),
      "accessList" -> accessList.map(TxArgsJson.encodeAccessList),
      "blobVersionedHashes" -> nonEmpty(blobFields._2).map(hs => JArray(hs.map(bytes))),
      "authorizationList" -> nonEmpty(authList).map(TxArgsJson.encodeAuthorizationList),
      "v" -> Some(q(sig.v)),
      "r" -> Some(q(sig.r)),
      "s" -> Some(q(sig.s)),
      "yParity" -> Option.when(txType != 0)(q(sig.v)),
      "blobs" -> sidecar.flatMap(sc => nonEmpty(sc.blobs)).map(xs => JArray(xs.map(bytes))),
      "commitments" -> sidecar.flatMap(sc => nonEmpty(sc.commitments)).map(xs => JArray(xs.map(bytes))),
      "proofs" -> sidecar.flatMap(sc => nonEmpty(sc.proofs)).map(xs => JArray(xs.map(bytes))),
      "hash" -> Some(bytes(stx.hash.value))
    )
    JObject(fields.collect { case (k, Some(v)) => k -> v })

  private def q(v: BigInt): JValue = JString(s"0x${v.toString(16)}")
  private def bytes(bs: ByteString): JValue = JString(s"0x${Hex.toHexString(bs.toArray[Byte])}")
