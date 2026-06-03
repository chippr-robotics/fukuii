package com.chipprbots.ethereum.network.p2p.messages

import org.apache.pekko.util.ByteString

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.forkid.ForkId
import com.chipprbots.ethereum.network.p2p.EthereumMessageDecoder
import com.chipprbots.ethereum.network.p2p.NetworkMessageDecoder
import com.chipprbots.ethereum.rlp.RLPList
import com.chipprbots.ethereum.rlp.RLPValue
import com.chipprbots.ethereum.testing.Tags._

/** Wire-format compliance tests for ETH68.
  *
  * Verifies that our ETH68 packet encoding/decoding is spec-correct per the devp2p eth/68
  * specification and matches reference client implementations (go-ethereum, Besu, Nethermind).
  *
  * Run before every JAR build targeted at live peer testing.
  */
class ETH68ComplianceSpec extends AnyWordSpec with Matchers {

  private val eth68Decoder =
    NetworkMessageDecoder.orElse(EthereumMessageDecoder.ethMessageDecoder(Capability.ETH68))

  // ── Status68 — 6-field wire format ──────────────────────────────────────────

  "ETH68 Status68" when {
    import ETHPackets.Status68.Status68._

    "encoding" should {

      "round-trip with all 6 fields preserved" taggedAs UnitTest in {
        val msg = ETHPackets.Status68.Status68(
          protocolVersion = 68,
          networkId = 61L,
          totalDifficulty = BigInt("24547334712889338862945"),
          bestHash = ByteString(Array.fill(32)(0xab.toByte)),
          genesisHash = ByteString(Array.fill(32)(0xcd.toByte)),
          forkId = ForkId(0xbe46d57cL, None)
        )
        eth68Decoder.fromBytes(Codes.StatusCode, msg.toBytes) shouldEqual Right(msg)
      }

      "include a non-zero TD field for ETC PoW (TD must never be zero)" taggedAs UnitTest in {
        // Regression: Status68.totalDifficulty = 0 would indicate a bug in chain weight tracking.
        val td = BigInt("34359738368")  // > genesis TD (17179869184) — any synced ETC node
        val msg = ETHPackets.Status68.Status68(68, 61L, td,
          Fixtures.Blocks.Block3125369.header.hash,
          Fixtures.Blocks.Genesis.header.hash,
          ForkId(0xbe46d57cL, None)
        )
        val encoded = msg.toBytes
        import com.chipprbots.ethereum.rlp._
        import com.chipprbots.ethereum.utils.ByteUtils
        rawDecode(encoded) match {
          case RLPList(_, _, RLPValue(tdBytes), _, _, _) =>
            ByteUtils.bytesToBigInt(tdBytes) shouldEqual td
            ByteUtils.bytesToBigInt(tdBytes) should be > BigInt(0)
          case _ => fail("Expected 6-field RLPList for Status68")
        }
      }
    }
  }

  // ── GetBlockHeaders / BlockHeaders — requestId round-trip ──────────────────

  "ETH68 GetBlockHeaders" when {
    import ETHPackets.GetBlockHeaders._

    "encoding and decoding" should {

      "preserve requestId through encode/decode" taggedAs UnitTest in {
        val requestId = BigInt(42)
        val msg = ETHPackets.GetBlockHeaders(requestId,
          Right(Fixtures.Blocks.Block3125369.header.hash), 1, 0, reverse = false)
        eth68Decoder.fromBytes(Codes.GetBlockHeadersCode, msg.toBytes) match {
          case Right(decoded: ETHPackets.GetBlockHeaders) =>
            decoded.requestId shouldEqual requestId
          case other => fail(s"Expected GetBlockHeaders, got $other")
        }
      }
    }
  }

  "ETH68 BlockHeaders" when {
    import ETHPackets.BlockHeaders._

    "encoding and decoding" should {

      "preserve requestId matching the GetBlockHeaders request" taggedAs UnitTest in {
        val requestId = BigInt(42)
        val response = ETHPackets.BlockHeaders(requestId, Seq(Fixtures.Blocks.Block3125369.header))
        eth68Decoder.fromBytes(Codes.BlockHeadersCode, response.toBytes) match {
          case Right(decoded: ETHPackets.BlockHeaders) =>
            decoded.requestId shouldEqual requestId
          case other => fail(s"Expected BlockHeaders, got $other")
        }
      }
    }
  }

  // ── GetBlockBodies / BlockBodies — requestId round-trip ────────────────────

  "ETH68 GetBlockBodies" when {
    import ETHPackets.GetBlockBodies._

    "encoding and decoding" should {

      "preserve requestId through encode/decode" taggedAs UnitTest in {
        val requestId = BigInt(99)
        val msg = ETHPackets.GetBlockBodies(requestId, Seq(Fixtures.Blocks.Block3125369.header.hash))
        eth68Decoder.fromBytes(Codes.GetBlockBodiesCode, msg.toBytes) match {
          case Right(decoded: ETHPackets.GetBlockBodies) =>
            decoded.requestId shouldEqual requestId
          case other => fail(s"Expected GetBlockBodies, got $other")
        }
      }
    }
  }

  // ── GetReceipts / Receipts68 — requestId round-trip ────────────────────────

  "ETH68 GetReceipts" when {
    import ETHPackets.GetReceipts._

    "encoding and decoding" should {

      "preserve requestId through encode/decode" taggedAs UnitTest in {
        val requestId = BigInt(7)
        val msg = ETHPackets.GetReceipts(requestId, Seq(Fixtures.Blocks.Block3125369.header.hash))
        eth68Decoder.fromBytes(Codes.GetReceiptsCode, msg.toBytes) match {
          case Right(decoded: ETHPackets.GetReceipts) =>
            decoded.requestId shouldEqual requestId
          case other => fail(s"Expected GetReceipts, got $other")
        }
      }
    }
  }

  // ── Receipts68 — bloom PRESENT in wire format ───────────────────────────────

  "ETH68 Receipts68" when {
    import ETHPackets.Receipts68._
    import com.chipprbots.ethereum.rlp._

    "encoding" should {

      "include bloom (4 fields per receipt: stateHash, gasUsed, bloom, logs)" taggedAs UnitTest in {
        val bloom256 = Array.fill(256)(0xff.toByte)
        val receiptRLP = RLPList(
          RLPValue(Array.fill(32)(0xaa.toByte)), // stateHash
          RLPValue(Array(0x01.toByte)),           // gasUsed
          RLPValue(bloom256),                     // logsBloom (256 bytes) ← MUST be present in ETH68
          RLPList()                               // logs
        )
        val receiptsForBlocks = RLPList(RLPList(receiptRLP))
        val msg = ETHPackets.Receipts68(BigInt(1), receiptsForBlocks)
        val encoded = msg.toBytes

        eth68Decoder.fromBytes(Codes.ReceiptsCode, encoded) match {
          case Right(r: ETHPackets.Receipts68) =>
            val blockReceiptList = r.receiptsForBlocks.items.head.asInstanceOf[RLPList]
            val receipt = blockReceiptList.items.head.asInstanceOf[RLPList]
            receipt.items.size shouldEqual 4  // stateHash, gasUsed, bloom, logs
          case other => fail(s"Expected Receipts68, got $other")
        }
      }
    }
  }

  // ── GetNodeData / NodeData — EIP-4938 rejection ─────────────────────────────

  "ETH68 GetNodeData" when {

    "decoded by ETH68MessageDecoder" should {

      "be rejected (EIP-4938 removes GetNodeData from ETH68)" taggedAs UnitTest in {
        val payload = Array[Byte](0x01, 0x02, 0x03)
        val result = eth68Decoder.fromBytes(Codes.GetNodeDataCode, payload)
        result.isLeft shouldBe true
        result.left.map(_.getMessage) shouldBe Left(
          "GetNodeData (0x0d) not supported in eth/68 (EIP-4938)"
        )
      }
    }
  }

  "ETH68 NodeData" when {

    "decoded by ETH68MessageDecoder" should {

      "be rejected (EIP-4938 removes NodeData from ETH68)" taggedAs UnitTest in {
        val payload = Array[Byte](0x01, 0x02, 0x03)
        val result = eth68Decoder.fromBytes(Codes.NodeDataCode, payload)
        result.isLeft shouldBe true
        result.left.map(_.getMessage) shouldBe Left(
          "NodeData (0x0e) not supported in eth/68 (EIP-4938)"
        )
      }
    }
  }

  // ── NewBlock — TD field present in wire bytes ────────────────────────────────

  "ETH68 NewBlock" when {
    import ETHPackets.NewBlock._
    import com.chipprbots.ethereum.rlp._
    import com.chipprbots.ethereum.utils.ByteUtils

    "encoding" should {

      "include a non-zero TD field (PoW ETC — TD is permanent, not removed by EIP-7642)" taggedAs UnitTest in {
        val td = BigInt("24547334712889338862945")
        val block = Fixtures.Blocks.Block3125369.block
        val msg = ETHPackets.NewBlock(block, td)
        val encoded = msg.toBytes
        // Wire: RLPList([blockHeader, txList, uncleList], TD)
        rawDecode(encoded) match {
          case RLPList(_, RLPValue(tdBytes)) =>
            val decodedTd = ByteUtils.bytesToBigInt(tdBytes)
            decodedTd shouldEqual td
            decodedTd should be > BigInt(0)
          case _ => fail("Expected RLPList([blockData, td]) for NewBlock")
        }
      }

      "round-trip correctly via ETH68 decoder" taggedAs UnitTest in {
        val td = BigInt("34359738368")
        val block = Fixtures.Blocks.Block3125369.block
        val msg = ETHPackets.NewBlock(block, td)
        eth68Decoder.fromBytes(Codes.NewBlockCode, msg.toBytes) match {
          case Right(decoded: ETHPackets.NewBlock) =>
            decoded.totalDifficulty shouldEqual td
          case other => fail(s"Expected NewBlock, got $other")
        }
      }
    }
  }
}
