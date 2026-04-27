package com.chipprbots.ethereum.domain

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.crypto.ECDSASignature
import com.chipprbots.ethereum.rlp
import com.chipprbots.ethereum.rlp.RLPList
import com.chipprbots.ethereum.rlp.RLPValue

/** Verifies BlockBody wire encoding follows EIP-2718 framing for typed transactions.
  *
  * Regression: typed-tx items previously serialized as raw `typeByte || rlp(payload)` (a PrefixedRLPEncodable in the tx
  * list). go-ethereum and besu re-request bodies forever when they see this — the items don't decode as a list of byte
  * strings. The correct framing wraps the typed envelope in an RLP byte string: `RLPValue(typeByte || rlp(payload))`.
  */
class BlockBodyWireFormatSpec extends AnyFlatSpec with Matchers {

  import BlockBody._

  "BlockBody wire encoding" should "frame typed transactions as RLP byte strings (EIP-2718)" in {
    val typedTx = TransactionWithAccessList(
      chainId = 1,
      nonce = 1,
      gasPrice = 1,
      gasLimit = 21000,
      receivingAddress = None,
      value = 0,
      payload = ByteString.empty,
      accessList = Nil
    )
    val signedTypedTx = SignedTransaction(typedTx, ECDSASignature(r = 1, s = 2, v = 1))
    val body = BlockBody(transactionList = Seq(signedTypedTx), uncleNodesList = Nil, withdrawals = None)

    val bytes = BlockBodyEnc(body).toBytes
    val decoded = rlp.rawDecode(bytes)

    decoded match {
      case RLPList(txs: RLPList, _: RLPList) =>
        txs.items.size shouldBe 1
        // Generic RLP parsers (geth, besu) read the typed-tx envelope as a single byte string.
        // If the encoder forgets to wrap in RLPValue, this slot would parse as the bare type
        // byte (RLPValue of length 1) followed by a stray RLPList — causing cross-client decode
        // failures and indefinite re-requests.
        withClue(s"typed-tx slot was ${txs.items.head.getClass.getSimpleName}: ") {
          txs.items.head shouldBe a[RLPValue]
        }
        val payload = txs.items.head.asInstanceOf[RLPValue].bytes
        payload.head shouldBe 0x01.toByte
      case other =>
        fail(s"Expected 2-item RLPList(txs, uncles), got: $other")
    }
  }

  it should "round-trip post-Shanghai bodies with withdrawals through fukuii's own decoder" in {
    val withdrawal = Withdrawal(
      index = 1,
      validatorIndex = 2,
      address = Address(ByteString(Array.fill[Byte](20)(0x42))),
      amount = 1000
    )
    val body = BlockBody(transactionList = Nil, uncleNodesList = Nil, withdrawals = Some(Seq(withdrawal)))

    val bytes = BlockBodyEnc(body).toBytes
    val roundTripped = bytes.toBlockBody

    roundTripped shouldBe body
  }

  it should "frame post-Shanghai bodies as a 3-item RLP list" in {
    val body = BlockBody(transactionList = Nil, uncleNodesList = Nil, withdrawals = Some(Seq.empty))
    val bytes = BlockBodyEnc(body).toBytes

    rlp.rawDecode(bytes) match {
      case rlpList: RLPList => rlpList.items.size shouldBe 3
      case other            => fail(s"Expected RLPList, got: $other")
    }
  }

  it should "frame pre-Shanghai bodies as a 2-item RLP list" in {
    val body = BlockBody(transactionList = Nil, uncleNodesList = Nil, withdrawals = None)
    val bytes = BlockBodyEnc(body).toBytes

    rlp.rawDecode(bytes) match {
      case rlpList: RLPList => rlpList.items.size shouldBe 2
      case other            => fail(s"Expected RLPList, got: $other")
    }
  }
}
