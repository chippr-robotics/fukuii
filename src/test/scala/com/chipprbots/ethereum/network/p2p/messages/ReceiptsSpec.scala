package com.chipprbots.ethereum.network.p2p.messages

import org.apache.pekko.util.ByteString

import org.bouncycastle.util.encoders.Hex
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.crypto._
import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.LegacyReceipt
import com.chipprbots.ethereum.domain.Receipt
import com.chipprbots.ethereum.domain.Transaction
import com.chipprbots.ethereum.domain.TxLogEntry
import com.chipprbots.ethereum.domain.Type01Receipt
import com.chipprbots.ethereum.network.p2p.EthereumMessageDecoder
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.Receipts68
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.Receipts68._
import com.chipprbots.ethereum.rlp.RLPImplicitConversions._
import com.chipprbots.ethereum.rlp.RLPImplicits.given
import com.chipprbots.ethereum.rlp._
import com.chipprbots.ethereum.testing.Tags._

/** Receipt encoding/decoding tests.
  *
  * ETH68 uses Receipts68 (requestId + raw RLP) — decoded lazily by FastSync.
  * ETH63.Receipts (domain-decoded) was removed with ETH63 deletion.
  */
class ReceiptsSpec extends AnyFlatSpec with Matchers {

  val exampleHash: ByteString = ByteString(kec256((0 until 32).map(_ => 1: Byte).toArray))
  val exampleLogsBloom: ByteString = ByteString((0 until 256).map(_ => 1: Byte).toArray)

  val loggerAddress: Address = Address(0xff)
  val logData: ByteString = ByteString(Hex.decode("bb"))
  val logTopics: Seq[ByteString] = Seq(ByteString(Hex.decode("dd")), ByteString(Hex.decode("aa")))

  val exampleLog: TxLogEntry = TxLogEntry(loggerAddress, logTopics, logData)
  val cumulativeGas: BigInt = 0

  val legacyReceipt: Receipt = LegacyReceipt.withHashOutcome(
    postTransactionStateHash = exampleHash,
    cumulativeGasUsed = cumulativeGas,
    logsBloomFilter = exampleLogsBloom,
    logs = Seq(exampleLog)
  )

  val type01Receipt: Receipt = Type01Receipt(legacyReceipt.asInstanceOf[LegacyReceipt])

  val encodedLogEntry: RLPList = RLPList(
    RLPValue(loggerAddress.bytes.toArray[Byte]),
    RLPList(logTopics.map(t => RLPValue(t.toArray[Byte])): _*),
    RLPValue(logData.toArray[Byte])
  )

  val encodedLegacyReceiptsList: RLPList =
    RLPList(RLPList(RLPList(
      RLPValue(exampleHash.toArray[Byte]),
      cumulativeGas,
      RLPValue(exampleLogsBloom.toArray[Byte]),
      RLPList(encodedLogEntry)
    )))

  val encodedType01ReceiptsList: RLPList =
    RLPList(RLPList(PrefixedRLPEncodable(
      Transaction.Type01,
      RLPList(
        RLPValue(exampleHash.toArray[Byte]),
        cumulativeGas,
        RLPValue(exampleLogsBloom.toArray[Byte]),
        RLPList(encodedLogEntry)
      )
    )))

  // ── ETH68 Receipts68 round-trip (requestId + raw RLP) ──────────────────────────
  // Use bytes comparison to avoid Queue vs ArraySeq collection type mismatch in RLPList.items.

  "ETH68 Receipts68" should "round-trip encode/decode with requestId" taggedAs (UnitTest, NetworkTest) in {
    val msg = Receipts68(BigInt(42), encodedType01ReceiptsList)
    val roundTripped = EthereumMessageDecoder
      .ethMessageDecoder(Capability.ETH68)
      .fromBytes(Codes.ReceiptsCode, msg.toBytes)
    roundTripped.map(_.asInstanceOf[Receipts68].toBytes.toSeq) shouldBe Right(msg.toBytes.toSeq)
  }

  it should "decode from ETH66-format wire bytes (requestId + blocks RLP)" taggedAs (UnitTest, NetworkTest) in {
    val msg = Receipts68(BigInt(1), encodedLegacyReceiptsList)
    val roundTripped = EthereumMessageDecoder
      .ethMessageDecoder(Capability.ETH68)
      .fromBytes(Codes.ReceiptsCode, msg.toBytes)
    roundTripped.map(_.asInstanceOf[Receipts68].toBytes.toSeq) shouldBe Right(msg.toBytes.toSeq)
  }

  it should "decode type 01 receipts in wire format (RLPValue with type prefix)" taggedAs (UnitTest, NetworkTest) in {
    // EIP-2718 typed receipts arrive as RLPValue(typeByte || rlp(payload)).
    // Receipts68 stores raw RLP for lazy decoding in FastSync.
    val legacyReceiptRLP = RLPList(
      RLPValue(exampleHash.toArray[Byte]),
      cumulativeGas,
      RLPValue(exampleLogsBloom.toArray[Byte]),
      RLPList(encodedLogEntry)
    )
    val typedReceiptBytes = Transaction.Type01 +: encode(legacyReceiptRLP)
    val blockReceiptsAsRLPValue = RLPList(RLPList(RLPValue(typedReceiptBytes)))
    val msg = Receipts68(BigInt(1), blockReceiptsAsRLPValue)

    val roundTripped = EthereumMessageDecoder
      .ethMessageDecoder(Capability.ETH68)
      .fromBytes(Codes.ReceiptsCode, msg.toBytes)
    roundTripped.map(_.asInstanceOf[Receipts68].toBytes.toSeq) shouldBe Right(msg.toBytes.toSeq)
  }
}
