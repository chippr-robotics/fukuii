package com.chipprbots.ethereum.network.p2p.messages

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.crypto.ECDSASignature
import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.GasAmount
import com.chipprbots.ethereum.domain.GasPrice
import com.chipprbots.ethereum.domain.LegacyTransaction
import com.chipprbots.ethereum.domain.SignedTransaction
import com.chipprbots.ethereum.domain.TransactionWithAccessList
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.PooledTransactions
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.PooledTransactions.*
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.SignedTransactions
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.SignedTransactions.*
import com.chipprbots.ethereum.rlp
import com.chipprbots.ethereum.rlp.RLPEncodeable
import com.chipprbots.ethereum.rlp.RLPList
import com.chipprbots.ethereum.rlp.RLPValue

/** EIP-2718 framing of typed transactions inside the two transaction-list messages, `Transactions` and
  * `PooledTransactions`.
  *
  * Each item of the list is either a legacy tx (an RLP list) or a typed tx as ONE RLP byte string holding `type ||
  * rlp(payload)`. These encoders used to put the bare concatenation in the list instead: a generic RLP reader sees a
  * 1-byte string followed by a stray list, which go-ethereum rejects outright and which fukuii's own reader splits into
  * two items, so the per-item sizes it checks against the announcement no longer line up. BlockBody had the same defect
  * (see BlockBodyWireFormatSpec).
  *
  * A blob tx is served in its network form, and a hive engine test compares that reply byte-for-byte with what it
  * submitted, so the stored network form must come back unchanged.
  */
class TxListWireFormatSpec extends AnyFlatSpec with Matchers:

  private val typedTx = SignedTransaction(
    TransactionWithAccessList(
      chainId = 1,
      nonce = 1,
      gasPrice = GasPrice(1),
      gasLimit = GasAmount(21000),
      receivingAddress = Some(Address(42)),
      value = 0,
      payload = ByteString.empty,
      accessList = Nil
    ),
    ECDSASignature(r = 1, s = 2, v = 1)
  )

  private val legacyTx = SignedTransaction(
    LegacyTransaction(1, GasPrice(1), GasAmount(21000), Some(Address(42)), 10, ByteString.empty),
    ECDSASignature(r = 1, s = 2, v = 27)
  )

  /** The execution-apis `send-blob-tx` payload: `0x03 || rlp([tx, version, blobs, commitments, cell_proofs])`. */
  private val blobNetworkForm: Array[Byte] =
    val is = getClass.getResourceAsStream("/eip7594-blob-tx-wrapper.rlp")
    try is.readAllBytes()
    finally is.close()

  private val (blobTx, _) = blobNetworkForm.toSignedTransactionWithSidecar

  private def pooledItems(bytes: Array[Byte]): Seq[RLPEncodeable] =
    rlp.rawDecode(bytes) match
      case RLPList(_, txs: RLPList) => txs.items
      case other                    => fail(s"expected [requestId, [txs...]], got $other")

  private def canonical(stx: SignedTransaction): Seq[Byte] = SignedTransaction.byteArraySerializable.toBytes(stx).toSeq

  "PooledTransactions" should "frame a typed tx as one RLP byte string holding type || rlp(payload)" in {
    val items = pooledItems(PooledTransactions(7, Seq(typedTx)).toBytes)

    items should have size 1
    withClue(s"typed-tx slot was ${items.head.getClass.getSimpleName}: ") {
      items.head shouldBe a[RLPValue]
    }
    items.head.asInstanceOf[RLPValue].bytes.toSeq shouldBe canonical(typedTx)
  }

  it should "serve a blob tx's stored network form byte-for-byte" in {
    val msg = PooledTransactions(7, Seq(blobTx), blobTxRawBytes = Map(blobTx.hash.value -> ByteString(blobNetworkForm)))
    val items = pooledItems(msg.toBytes)

    items should have size 1
    items.head shouldBe a[RLPValue]
    items.head.asInstanceOf[RLPValue].bytes.toSeq shouldBe blobNetworkForm.toSeq
  }

  it should "leave a legacy tx as an RLP list" in {
    val items = pooledItems(PooledTransactions(7, Seq(legacyTx)).toBytes)

    items should have size 1
    items.head shouldBe a[RLPList]
  }

  it should "decode to one entry per tx, with sizes and the blob sidecar intact" in {
    val msg = PooledTransactions(
      7,
      Seq(typedTx, blobTx, legacyTx),
      blobTxRawBytes = Map(blobTx.hash.value -> ByteString(blobNetworkForm))
    )

    val decoded = msg.toBytes.toPooledTransactions

    decoded.txs.map(_.hash) shouldBe Seq(typedTx.hash, blobTx.hash, legacyTx.hash)
    // One size per tx, each the length a peer announces: the typed envelope, the blob network
    // form, the legacy list. With the unframed encoding the typed tx split into two items here.
    decoded.originalSizes shouldBe Seq(canonical(typedTx).size, blobNetworkForm.length, canonical(legacyTx).size)
    decoded.blobTxRawBytes.get(blobTx.hash.value).map(_.toSeq) shouldBe Some(blobNetworkForm.toSeq)
  }

  "Transactions" should "frame typed txs as RLP byte strings and leave legacy txs as lists" in {
    rlp.rawDecode(SignedTransactions(Seq(typedTx, legacyTx)).toBytes) match
      case RLPList(typed, legacy) =>
        typed shouldBe a[RLPValue]
        typed.asInstanceOf[RLPValue].bytes.toSeq shouldBe canonical(typedTx)
        legacy shouldBe a[RLPList]
      case other => fail(s"expected a 2-item tx list, got $other")
  }

  it should "round-trip through fukuii's own decoder" in {
    val msg = SignedTransactions(Seq(typedTx, legacyTx))
    msg.toBytes.toSignedTransactions shouldBe msg
  }
