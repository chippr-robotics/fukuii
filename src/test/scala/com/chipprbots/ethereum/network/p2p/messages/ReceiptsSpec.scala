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
import com.chipprbots.ethereum.network.p2p.messages.ETH63.Receipts
import com.chipprbots.ethereum.rlp.RLPImplicitConversions._
import com.chipprbots.ethereum.rlp.RLPImplicits.given
import com.chipprbots.ethereum.rlp._

// ETH68 uses ETH66 Receipts format (requestId + receiptsForBlocks: RLPList). Receipt data encoding
// (LegacyReceipt, Type01Receipt) is tested via ETH63.Receipts.toBytes; protocol decode is via ETH66.
import com.chipprbots.ethereum.testing.Tags._

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

  val legacyReceipts: Receipts = Receipts(Seq(Seq(legacyReceipt)))

  val type01Receipts: Receipts = Receipts(Seq(Seq(type01Receipt)))

  val encodedLogEntry: RLPList = RLPList(
    RLPValue(loggerAddress.bytes.toArray[Byte]),
    RLPList(logTopics.map(t => RLPValue(t.toArray[Byte])): _*),
    RLPValue(logData.toArray[Byte])
  )

  val encodedLegacyReceipts: RLPList =
    RLPList(
      RLPList(
        RLPList(
          RLPValue(exampleHash.toArray[Byte]),
          cumulativeGas,
          RLPValue(exampleLogsBloom.toArray[Byte]),
          RLPList(encodedLogEntry)
        )
      )
    )

  val encodedType01Receipts: RLPList =
    RLPList(
      RLPList(
        PrefixedRLPEncodable(
          Transaction.Type01,
          RLPList(
            RLPValue(exampleHash.toArray[Byte]),
            cumulativeGas,
            RLPValue(exampleLogsBloom.toArray[Byte]),
            RLPList(encodedLogEntry)
          )
        )
      )
    )

  "Legacy Receipts" should "encode legacy receipts" taggedAs (UnitTest, NetworkTest) in {
    (legacyReceipts.toBytes: Array[Byte]) shouldBe encode(encodedLegacyReceipts)
  }

  it should "decode legacy receipts" taggedAs (UnitTest, NetworkTest) in {
    // ETH68 wraps receipts in ETH66 format (requestId + receiptsForBlocks).
    // Compare bytes: RLPValue(Array[Byte]) uses reference equality, so we round-trip via toBytes.
    val receipts66 = ETH66.Receipts(requestId = 1L, receiptsForBlocks = encodedLegacyReceipts)
    val bytes = receipts66.toBytes
    EthereumMessageDecoder.ethMessageDecoder(Capability.ETH68).fromBytes(Codes.ReceiptsCode, bytes).map {
      case r: ETH66.Receipts => r.toBytes.toSeq
    } shouldBe Right(bytes.toSeq)
  }

  it should "decode encoded legacy receipts" taggedAs (UnitTest, NetworkTest) in {
    // Round-trip: ETH66.Receipts encode → ETH68 decode → re-encode = same bytes
    val receipts66 = ETH66.Receipts(requestId = 1L, receiptsForBlocks = encodedLegacyReceipts)
    val bytes = receipts66.toBytes
    EthereumMessageDecoder.ethMessageDecoder(Capability.ETH68).fromBytes(Codes.ReceiptsCode, bytes).map {
      case r: ETH66.Receipts => r.toBytes.toSeq
    } shouldBe Right(bytes.toSeq)
  }

  "Type 01 Receipts" should "encode type 01 receipts" taggedAs (UnitTest, NetworkTest) in {
    (type01Receipts.toBytes: Array[Byte]) shouldBe encode(encodedType01Receipts)
  }

  it should "decode type 01 receipts" taggedAs (UnitTest, NetworkTest) in {
    val receipts66 = ETH66.Receipts(requestId = 1L, receiptsForBlocks = encodedType01Receipts)
    val bytes = receipts66.toBytes
    EthereumMessageDecoder.ethMessageDecoder(Capability.ETH68).fromBytes(Codes.ReceiptsCode, bytes).map {
      case r: ETH66.Receipts => r.toBytes.toSeq
    } shouldBe Right(bytes.toSeq)
  }

  it should "decode encoded type 01 receipts" taggedAs (UnitTest, NetworkTest) in {
    val receipts66 = ETH66.Receipts(requestId = 1L, receiptsForBlocks = encodedType01Receipts)
    val bytes = receipts66.toBytes
    EthereumMessageDecoder.ethMessageDecoder(Capability.ETH68).fromBytes(Codes.ReceiptsCode, bytes).map {
      case r: ETH66.Receipts => r.toBytes.toSeq
    } shouldBe Right(bytes.toSeq)
  }

  it should "decode type 01 receipts from wire format (as RLPValue)" taggedAs (UnitTest, NetworkTest) in {
    // EIP-2718 typed receipts arrive as RLPValue(typeByte || rlp(payload)) inside the receipts list.
    // In ETH68, the outer envelope is ETH66.Receipts(requestId, receiptsForBlocks: RLPList).
    val legacyReceiptRLP = RLPList(
      RLPValue(exampleHash.toArray[Byte]),
      cumulativeGas,
      RLPValue(exampleLogsBloom.toArray[Byte]),
      RLPList(encodedLogEntry)
    )
    val typedReceiptBytes = Transaction.Type01 +: encode(legacyReceiptRLP)
    val receiptsForBlocksRLP = RLPList(
      RLPList(
        RLPValue(typedReceiptBytes)
      )
    )
    val receipts66 = ETH66.Receipts(requestId = 1L, receiptsForBlocks = receiptsForBlocksRLP)
    val bytes = receipts66.toBytes
    EthereumMessageDecoder.ethMessageDecoder(Capability.ETH68).fromBytes(Codes.ReceiptsCode, bytes).map {
      case r: ETH66.Receipts => r.toBytes.toSeq
    } shouldBe Right(bytes.toSeq)
  }

}
