package com.chipprbots.ethereum.network.p2p.messages

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.PooledTransactions.*
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.SignedTransactions.*
import com.chipprbots.ethereum.rlp
import com.chipprbots.ethereum.rlp.RLPList
import com.chipprbots.ethereum.rlp.RLPValue
import com.chipprbots.ethereum.rlp.rawDecode
import com.chipprbots.ethereum.utils.Hex

/** Regression cover for the EIP-7594 (PeerDAS / Osaka) blob-transaction network wrapper.
  *
  * The decoder previously accepted only the 4-element EIP-4844 wrapper `[tx_payload, blobs, commitments, proofs]`. The
  * 5-element EIP-7594 wrapper `[tx_payload, wrapper_version, blobs, commitments, cell_proofs]` fell through to a branch
  * that tried to read the whole wrapper as a 14-field transaction body and threw, which `eth_sendRawTransaction`
  * reported as `-32600 "The JSON sent is not a valid Request object"`.
  *
  * The vector is the exact 137,725-byte payload sent by the execution-apis `eth_sendRawTransaction/send-blob-tx` test,
  * and the expected hash is that test's expected result. Decoding must key off `items.head` (the transaction body), so
  * the hash is independent of the sidecar shape.
  *
  * Covers three call sites that shared the same `== 4` gate: `toSignedTransactionWithSidecar` (fixed first, see the
  * tests immediately below), `toSignedTransaction` (sidecar-discarding decode, used by `SignedTransactions` list
  * decode), and `toPooledTransactions` (wire decode of a `PooledTransactions` response, where a present-but-Osaka-form
  * sidecar was previously misclassified as ABSENT rather than malformed -- two different protocol facts that must stay
  * distinguishable by their exception messages).
  */
class BlobTxSidecarWrapperSpec extends AnyFlatSpec with Matchers:

  private val wrapperBytes: Array[Byte] =
    val is = getClass.getResourceAsStream("/eip7594-blob-tx-wrapper.rlp")
    try is.readAllBytes()
    finally is.close()

  /** execution-apis tests/eth_sendRawTransaction/send-blob-tx.io expected result. */
  private val ExpectedTxHash = "05d85f6a761cac82cfdf06dd168952838ac452b10641aabccdfdad46e03d2f0b"

  private def outerItems: Seq[rlp.RLPEncodeable] =
    rawDecode(wrapperBytes.tail) match
      case l: RLPList => l.items
      case other      => fail(s"expected an RLP list wrapper, got $other")

  private def reWrap(items: rlp.RLPEncodeable*): Array[Byte] =
    Array(0x03.toByte) ++ rlp.encode(RLPList(items*))

  "the EIP-7594 vector" should "be a 5-element wrapper carrying version 0x01 and 128 cell proofs" in {
    val items = outerItems
    items.size shouldBe 5
    // RLPValue wraps a bare Array, whose equals is reference-based — compare the bytes.
    items(1).asInstanceOf[RLPValue].bytes.toSeq shouldBe Seq(1.toByte)
    items(2).asInstanceOf[RLPList].items.size shouldBe 1 // blobs
    items(3).asInstanceOf[RLPList].items.size shouldBe 1 // commitments
    items(4).asInstanceOf[RLPList].items.size shouldBe 128 // cell proofs
  }

  "toSignedTransactionWithSidecar" should "decode the 5-element EIP-7594 wrapper and preserve the raw sidecar" in {
    val (stx, sidecar) = wrapperBytes.toSignedTransactionWithSidecar
    Hex.toHexString(stx.hash.value.toArray) shouldBe ExpectedTxHash
    sidecar.map(_.toSeq) shouldBe Some(wrapperBytes.toSeq)
  }

  it should "still decode the legacy 4-element EIP-4844 wrapper to the same transaction" in {
    val items = outerItems
    val legacyProofs = RLPList(items(4).asInstanceOf[RLPList].items.head)
    val legacy = reWrap(items.head, items(2), items(3), legacyProofs)

    val (stx, sidecar) = legacy.toSignedTransactionWithSidecar
    Hex.toHexString(stx.hash.value.toArray) shouldBe ExpectedTxHash
    sidecar.map(_.toSeq) shouldBe Some(legacy.toSeq)
  }

  it should "decode a bare (unwrapped) type-3 transaction with no sidecar" in {
    val bare = Array(0x03.toByte) ++ rlp.encode(outerItems.head)
    val (stx, sidecar) = bare.toSignedTransactionWithSidecar
    Hex.toHexString(stx.hash.value.toArray) shouldBe ExpectedTxHash
    sidecar shouldBe None
  }

  it should "reject an unknown 5-element wrapper version rather than silently accepting it" in {
    val items = outerItems
    val badVersion = reWrap(items.head, RLPValue(Array(2.toByte)), items(2), items(3), items(4))

    val thrown = intercept[RuntimeException](badVersion.toSignedTransactionWithSidecar)
    thrown.getMessage should include("wrapper version")
  }

  it should "reject a 5-element wrapper whose version field is a list rather than a scalar" in {
    val items = outerItems
    val badVersion = reWrap(items.head, RLPList(), items(2), items(3), items(4))

    val thrown = intercept[RuntimeException](badVersion.toSignedTransactionWithSidecar)
    thrown.getMessage should include("scalar")
  }

  // ── toSignedTransaction (sidecar-discarding decode) ─────────────────────────────
  // Same `== 4` gate as toSignedTransactionWithSidecar, but on the path that only returns the
  // SignedTransaction (used by SignedTransactions.toSignedTransactions, i.e. the plain
  // `Transactions` broadcast message). Pre-fix, a 5-element EIP-7594 wrapper fell through to
  // `case other => PrefixedRLPEncodable(Transaction.Type03, other)`, handing the WHOLE 5-item
  // wrapper list to the tx-body decoder as if it were the ~14-field transaction body itself.

  "toSignedTransaction" should "decode the 5-element EIP-7594 wrapper to the correct transaction" in {
    Hex.toHexString(wrapperBytes.toSignedTransaction.hash.value.toArray) shouldBe ExpectedTxHash
  }

  it should "still decode the legacy 4-element EIP-4844 wrapper to the same transaction" in {
    val items = outerItems
    val legacyProofs = RLPList(items(4).asInstanceOf[RLPList].items.head)
    val legacy = reWrap(items.head, items(2), items(3), legacyProofs)

    Hex.toHexString(legacy.toSignedTransaction.hash.value.toArray) shouldBe ExpectedTxHash
  }

  it should "decode a bare (unwrapped) type-3 transaction with no sidecar" in {
    val bare = Array(0x03.toByte) ++ rlp.encode(outerItems.head)
    Hex.toHexString(bare.toSignedTransaction.hash.value.toArray) shouldBe ExpectedTxHash
  }

  it should "reject an unknown 5-element wrapper version rather than silently accepting it" in {
    val items = outerItems
    val badVersion = reWrap(items.head, RLPValue(Array(2.toByte)), items(2), items(3), items(4))

    val thrown = intercept[RuntimeException](badVersion.toSignedTransaction)
    thrown.getMessage should include("wrapper version")
  }

  it should "reject a 5-element wrapper whose version field is a list rather than a scalar" in {
    val items = outerItems
    val badVersion = reWrap(items.head, RLPList(), items(2), items(3), items(4))

    val thrown = intercept[RuntimeException](badVersion.toSignedTransaction)
    thrown.getMessage should include("scalar")
  }

  // ── toPooledTransactions (wire decode of a PooledTransactions response) ────────
  // The consequence here is sharper than the other two sites: `isNetworkWrapped` required
  // exactly 4 elements, so a correctly formed EIP-7594 sidecar from a peer was classified as
  // ABSENT, not malformed -- indistinguishable from a genuine protocol violation (a bare tx body
  // with no wrapper at all). These tests pin that the two stay distinguishable after the fix:
  // "no sidecar" and "sidecar present, bad version" throw different, specific messages, and
  // "sidecar present, EIP-7594 form" throws nothing at all.

  /** Wraps one or more already-type-prefixed tx wire byte arrays (e.g. `wrapperBytes`, `legacy`, `bare` below) into a
    * `PooledTransactions` wire payload: `[requestId, [RLPValue(txBytes), ...]]`. Each tx byte array is placed as a
    * single opaque RLP string list item -- the real EIP-2718 wire shape `toTypedRLPEncodables`'s typed-string branch
    * expects, and the same shape a real peer (or fukuii's own encoder) puts on the wire.
    */
  private def poolWrap(txWireBytes: Array[Byte]*): Array[Byte] =
    rlp.encode(RLPList(RLPValue(Array(1.toByte)), RLPList(txWireBytes.map(RLPValue.apply)*)))

  "toPooledTransactions" should "accept a present EIP-7594 (5-element) sidecar, not classify it as missing" in {
    val decoded = poolWrap(wrapperBytes).toPooledTransactions
    decoded.txs should have size 1
    Hex.toHexString(decoded.txs.head.hash.value.toArray) shouldBe ExpectedTxHash
    // The raw wrapped bytes (type byte + full 5-element wrapper) are preserved for re-broadcast,
    // not just the unwrapped tx body -- re-deriving cell proofs is not something we do here.
    decoded.blobTxRawBytes.get(decoded.txs.head.hash.value).map(_.toArray.toSeq) shouldBe Some(wrapperBytes.toSeq)
  }

  it should "still accept the legacy 4-element EIP-4844 sidecar" in {
    val items = outerItems
    val legacyProofs = RLPList(items(4).asInstanceOf[RLPList].items.head)
    val legacy = reWrap(items.head, items(2), items(3), legacyProofs)

    val decoded = poolWrap(legacy).toPooledTransactions
    decoded.txs should have size 1
    Hex.toHexString(decoded.txs.head.hash.value.toArray) shouldBe ExpectedTxHash
    decoded.blobTxRawBytes.get(decoded.txs.head.hash.value).map(_.toArray.toSeq) shouldBe Some(legacy.toSeq)
  }

  it should "reject a genuinely bare (sidecar-absent) blob tx as missing -- not as a bad version" in {
    val bare = Array(0x03.toByte) ++ rlp.encode(outerItems.head)

    val thrown = intercept[RuntimeException](poolWrap(bare).toPooledTransactions)
    thrown.getMessage should include("missing sidecar")
    (thrown.getMessage should not).include("wrapper version")
  }

  it should "reject an unknown 5-element wrapper version as a version defect -- not as missing" in {
    val items = outerItems
    val badVersion = reWrap(items.head, RLPValue(Array(2.toByte)), items(2), items(3), items(4))

    val thrown = intercept[RuntimeException](poolWrap(badVersion).toPooledTransactions)
    thrown.getMessage should include("wrapper version")
    (thrown.getMessage should not).include("missing sidecar")
  }

  it should "reject a 5-element wrapper whose version field is a list rather than a scalar" in {
    val items = outerItems
    val badVersion = reWrap(items.head, RLPList(), items(2), items(3), items(4))

    val thrown = intercept[RuntimeException](poolWrap(badVersion).toPooledTransactions)
    thrown.getMessage should include("scalar")
  }
