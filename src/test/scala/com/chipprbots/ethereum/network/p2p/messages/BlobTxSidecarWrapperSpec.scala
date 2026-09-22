package com.chipprbots.ethereum.network.p2p.messages

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

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
