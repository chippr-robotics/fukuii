package com.chipprbots.ethereum.network.p2p.messages

import org.apache.pekko.util.ByteString

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.chipprbots.ethereum.forkid.ForkId
import com.chipprbots.ethereum.network.p2p.EthereumMessageDecoder
import com.chipprbots.ethereum.network.p2p.NetworkMessageDecoder
import com.chipprbots.ethereum.rlp.RLPList
import com.chipprbots.ethereum.testing.Tags._
import com.chipprbots.ethereum.utils.ByteUtils

/** Wire-format compliance tests for ETH70 (EIP-7706 partial receipt delivery).
  *
  * Verifies that ETH70 decoders are self-contained (no dependency on ETH69 decoder types), that
  * GetReceipts70/Receipts70 encode/decode correctly, and that Status70 has the same tolerant 3-arm decode as Status69
  * (aligned with PR #1324).
  *
  * Run before every JAR build targeted at live peer testing.
  */
class ETH70ComplianceSpec extends AnyWordSpec with Matchers {

  private def decoder(cap: Capability) =
    NetworkMessageDecoder.orElse(EthereumMessageDecoder.ethMessageDecoder(cap))

  // ── Status70 — wire format: 7-field [v, netId, genesis, forkId, earliest, latest, latestHash] ──
  // Same wire format as Status69 (EIP-7706 does not change the STATUS format from EIP-7642).

  "ETH70 Status70" when {
    import ETHPackets.Status70.Status70._

    "encoding and decoding" should {
      "round-trip correctly with all 7 fields" taggedAs UnitTest in {
        val msg = ETHPackets.Status70.Status70(
          protocolVersion = 70,
          networkId = 1L, // Sepolia (ETH70 defaults off on ETC)
          genesisHash = ByteString(Array.fill(32)(0xcd.toByte)),
          forkId = ForkId(0xbe46d57cL, None),
          earliestBlock = BigInt(0),
          latestBlock = BigInt(22000000),
          latestBlockHash = ByteString(Array.fill(32)(0xab.toByte))
        )
        val encoded = msg.toBytes
        val decoded = decoder(Capability.ETH70).fromBytes(Codes.StatusCode, encoded)
        decoded shouldEqual Right(msg)
      }

      "have NO totalDifficulty field (same as Status69 — TD-free)" taggedAs UnitTest in {
        val msg = ETHPackets.Status70.Status70(
          70,
          1L,
          ByteString("genesis"),
          ForkId(1L, None),
          BigInt(0),
          BigInt(100),
          ByteString("hash")
        )
        val encoded = msg.toBytes
        import com.chipprbots.ethereum.rlp._
        rawDecode(encoded) match {
          case rlpList: RLPList =>
            rlpList.items.size shouldEqual 7
            // Field index 2 should be genesisHash, NOT a BigInt TD
            rlpList.items(2) match {
              case RLPValue(bytes) => bytes.length shouldEqual 7 // "genesis".getBytes
              case _               => fail("Field at index 2 should be genesisHash RLPValue, not TD")
            }
          case _ => fail("Expected 7-field RLPList for Status70")
        }
      }

      "field ordering matches EIP-7706: [v, netId, genesis, forkId, earliest, latest, latestHash]" taggedAs UnitTest in {
        val genesisHash = ByteString(Array.fill(32)(0x11.toByte))
        val latestHash = ByteString(Array.fill(32)(0x22.toByte))
        val latestBlock = BigInt(22345678)
        val msg = ETHPackets.Status70.Status70(
          70,
          1L,
          genesisHash,
          ForkId(0xbe46d57cL, None),
          BigInt(0),
          latestBlock,
          latestHash
        )
        val encoded = msg.toBytes
        decoder(Capability.ETH70).fromBytes(Codes.StatusCode, encoded) match {
          case Right(s: ETHPackets.Status70.Status70) =>
            s.genesisHash shouldEqual genesisHash
            s.latestBlock shouldEqual latestBlock
            s.latestBlockHash shouldEqual latestHash
          case other => fail(s"Expected Status70, got $other")
        }
      }
    }
  }

  // ── Status70 tolerant decode — mirrors PR #1324 pattern applied to ETH69.
  // Some peers may announce eth/70 but emit an older STATUS shape; decode tolerantly
  // so the networkId/genesis check can reject them as UselessPeer instead of DECODE_ERROR.

  "ETH70 Status70 tolerant decode" when {

    "receiving an ETH/68-shaped 6-field STATUS on the eth/70 channel" should {
      "decode it (forkId at index 5, bestHash mapped to latestBlockHash)" taggedAs UnitTest in {
        import ETHPackets.Status68.Status68._
        val genesisHash = ByteString(Array.fill(32)(0xcd.toByte))
        val bestHash = ByteString(Array.fill(32)(0xab.toByte))
        // A peer that announces eth/70 but sends an ETH/68-shaped STATUS (with TD)
        val eth68Shaped = ETHPackets.Status68.Status68(
          protocolVersion = 70, // eth/70 version number, but the shape is ETH/68
          networkId = 7L,
          totalDifficulty = BigInt("167378406679735"),
          bestHash = bestHash,
          genesisHash = genesisHash,
          forkId = ForkId(0xbe46d57cL, None)
        )
        val encoded = eth68Shaped.toBytes
        val decoded = decoder(Capability.ETH70).fromBytes(Codes.StatusCode, encoded)
        decoded match {
          case Right(s: ETHPackets.Status70.Status70) =>
            s.protocolVersion shouldEqual 70
            s.networkId shouldEqual 7L
            s.genesisHash shouldEqual genesisHash
            s.forkId shouldEqual ForkId(0xbe46d57cL, None)
            s.earliestBlock shouldEqual BigInt(0)
            s.latestBlock shouldEqual BigInt(0)
            s.latestBlockHash shouldEqual bestHash
          case other => fail(s"Expected tolerant Status70 decode, got $other")
        }
      }
    }

    "receiving the legacy 6-field shape (forkId at index 3, no earliestBlock)" should {
      "decode it with earliestBlock defaulted to 0" taggedAs UnitTest in {
        import com.chipprbots.ethereum.rlp._
        import ETHPackets.Status70.Status70._
        val genesisHash = ByteString(Array.fill(32)(0x11.toByte))
        val latestHash = ByteString(Array.fill(32)(0x22.toByte))
        val canonical = ETHPackets.Status70.Status70(
          protocolVersion = 70,
          networkId = 7L,
          genesisHash = genesisHash,
          forkId = ForkId(0xfc64ec04L, Some(1150000L)),
          earliestBlock = BigInt(0),
          latestBlock = BigInt(424242),
          latestBlockHash = latestHash
        )
        // Drop earliestBlock (index 4) to get the legacy 6-field shape
        val canonicalRlp = rawDecode(canonical.toBytes).asInstanceOf[RLPList]
        val i = canonicalRlp.items
        val legacyBytes = encode(RLPList(i(0), i(1), i(2), i(3), i(5), i(6)))
        val decoded = decoder(Capability.ETH70).fromBytes(Codes.StatusCode, legacyBytes)
        decoded match {
          case Right(s: ETHPackets.Status70.Status70) =>
            s.networkId shouldEqual 7L
            s.genesisHash shouldEqual genesisHash
            s.forkId shouldEqual ForkId(0xfc64ec04L, Some(1150000L))
            s.earliestBlock shouldEqual BigInt(0)
            s.latestBlock shouldEqual BigInt(424242)
            s.latestBlockHash shouldEqual latestHash
          case other => fail(s"Expected tolerant legacy Status70 decode, got $other")
        }
      }
    }

    "receiving a STATUS that matches no known shape" should {
      "still be rejected" taggedAs UnitTest in {
        import com.chipprbots.ethereum.rlp._
        val garbage = encode(RLPList(RLPValue(Array[Byte](70)), RLPValue(Array[Byte](1))))
        val decoded = decoder(Capability.ETH70).fromBytes(Codes.StatusCode, garbage)
        decoded.isLeft shouldBe true
      }
    }
  }

  // ── GetReceipts70 — EIP-7706: adds firstBlockReceiptIndex for partial resume ──

  "ETH70 GetReceipts70" when {
    import ETHPackets.GetReceipts70._

    "encoding and decoding" should {
      "round-trip with firstBlockReceiptIndex=0 (fresh request)" taggedAs UnitTest in {
        val msg = ETHPackets.GetReceipts70(
          requestId = BigInt(10),
          firstBlockReceiptIndex = 0L,
          blockHashes = Seq(ByteString(Array.fill(32)(0xde.toByte)))
        )
        val encoded = msg.toBytes
        decoder(Capability.ETH70).fromBytes(Codes.GetReceiptsCode, encoded) match {
          case Right(r: ETHPackets.GetReceipts70) =>
            r.requestId shouldEqual BigInt(10)
            r.firstBlockReceiptIndex shouldEqual 0L
            r.blockHashes.size shouldEqual 1
          case other => fail(s"Expected GetReceipts70, got $other")
        }
      }

      "round-trip with firstBlockReceiptIndex > 0 (resume after partial)" taggedAs UnitTest in {
        val msg = ETHPackets.GetReceipts70(
          requestId = BigInt(11),
          firstBlockReceiptIndex = 42L,
          blockHashes = Seq(
            ByteString(Array.fill(32)(0xaa.toByte)),
            ByteString(Array.fill(32)(0xbb.toByte))
          )
        )
        val encoded = msg.toBytes
        decoder(Capability.ETH70).fromBytes(Codes.GetReceiptsCode, encoded) match {
          case Right(r: ETHPackets.GetReceipts70) =>
            r.requestId shouldEqual BigInt(11)
            r.firstBlockReceiptIndex shouldEqual 42L
            r.blockHashes.size shouldEqual 2
          case other => fail(s"Expected GetReceipts70, got $other")
        }
      }

      "include firstBlockReceiptIndex as second RLP field (before hashes)" taggedAs UnitTest in {
        import com.chipprbots.ethereum.rlp._
        val msg = ETHPackets.GetReceipts70(BigInt(5), 7L, Seq(ByteString(Array.fill(32)(0xff.toByte))))
        val encoded = msg.toBytes
        rawDecode(encoded) match {
          case RLPList(
                RLPValue(_), // requestId
                RLPValue(resumeBytes), // firstBlockReceiptIndex
                _ // hashes list
              ) =>
            ByteUtils.bytesToBigInt(resumeBytes).toLong shouldEqual 7L
          case other => fail(s"Expected 3-field RLPList [reqId, firstIdx, hashes], got $other")
        }
      }
    }
  }

  // ── Receipts70 — EIP-7706: adds lastBlockIncomplete for 2 MiB soft limit signalling ──

  "ETH70 Receipts70" when {
    import ETHPackets.Receipts70._

    "encoding and decoding" should {
      "round-trip with lastBlockIncomplete=false (complete response)" taggedAs UnitTest in {
        import com.chipprbots.ethereum.rlp._
        val msg = ETHPackets.Receipts70(
          requestId = BigInt(20),
          lastBlockIncomplete = false,
          receiptsForBlocks = RLPList()
        )
        val encoded = msg.toBytes
        decoder(Capability.ETH70).fromBytes(Codes.ReceiptsCode, encoded) match {
          case Right(r: ETHPackets.Receipts70) =>
            r.requestId shouldEqual BigInt(20)
            r.lastBlockIncomplete shouldBe false
          case other => fail(s"Expected Receipts70, got $other")
        }
      }

      "round-trip with lastBlockIncomplete=true (server hit 2 MiB limit)" taggedAs UnitTest in {
        import com.chipprbots.ethereum.rlp._
        val msg = ETHPackets.Receipts70(
          requestId = BigInt(21),
          lastBlockIncomplete = true,
          receiptsForBlocks = RLPList()
        )
        val encoded = msg.toBytes
        decoder(Capability.ETH70).fromBytes(Codes.ReceiptsCode, encoded) match {
          case Right(r: ETHPackets.Receipts70) =>
            r.requestId shouldEqual BigInt(21)
            r.lastBlockIncomplete shouldBe true
          case other => fail(s"Expected Receipts70, got $other")
        }
      }

      "include lastBlockIncomplete as second RLP field (before receipts list)" taggedAs UnitTest in {
        import com.chipprbots.ethereum.rlp._
        val msg = ETHPackets.Receipts70(BigInt(9), lastBlockIncomplete = true, RLPList())
        val encoded = msg.toBytes
        rawDecode(encoded) match {
          case RLPList(
                RLPValue(_), // requestId
                RLPValue(flagBytes), // lastBlockIncomplete
                _ // receipts list
              ) =>
            // lastBlockIncomplete=true encodes as 0x01
            flagBytes.headOption.map(_ & 0xff) shouldEqual Some(1)
          case other => fail(s"Expected 3-field RLPList [reqId, incomplete, blocks], got $other")
        }
      }

      "carry bloom-absent 3-field receipts (EIP-7642 — same as ETH69)" taggedAs UnitTest in {
        import com.chipprbots.ethereum.rlp._
        import ETHPackets.ReceiptBloomFreeEnc
        import com.chipprbots.ethereum.domain._
        val receipt = LegacyReceipt(
          SuccessOutcome,
          cumulativeGasUsed = BigInt(21000),
          logsBloomFilter = ByteString(Array.fill(256)(0xff.toByte)),
          logs = Seq.empty
        )
        val receiptRLP = new ReceiptBloomFreeEnc(receipt).toRLPEncodable
        val receiptsForBlocks = RLPList(RLPList(receiptRLP))
        val msg = ETHPackets.Receipts70(BigInt(1), lastBlockIncomplete = false, receiptsForBlocks)
        val wireBytes = msg.toBytes

        // The 256-byte all-0xff bloom must NOT appear in the wire encoding
        val bloomBytes = Array.fill(256)(0xff.toByte)
        val hasBloom = wireBytes.sliding(256).exists(window => window.sameElements(bloomBytes))
        hasBloom shouldBe false

        // And the receipt inside should have 3 fields (no bloom field)
        decoder(Capability.ETH70).fromBytes(Codes.ReceiptsCode, wireBytes) match {
          case Right(r: ETHPackets.Receipts70) =>
            val blockReceipts = r.receiptsForBlocks.items.head.asInstanceOf[RLPList]
            val innerReceipt = blockReceipts.items.head.asInstanceOf[RLPList]
            innerReceipt.items.size shouldEqual 3
          case other => fail(s"Expected Receipts70, got $other")
        }
      }
    }
  }

  // ── Self-containment — ETH70 decoder must not delegate to ETH69 decoder types ──
  //
  // If ETH69 is retired, ETH70 must still compile and function. This test proves the
  // ETH70 decoder dispatches all ETH70 message codes without referencing ETH69 types.

  "ETH70 decoder self-containment" when {

    "the ETH70 decoder handles GetReceipts70" should {
      "return ETH70's own GetReceipts70 type (not GetReceipts69)" taggedAs UnitTest in {
        import ETHPackets.GetReceipts70._
        val msg = ETHPackets.GetReceipts70(BigInt(1), 0L, Seq(ByteString(Array.fill(32)(0xde.toByte))))
        val encoded = msg.toBytes
        decoder(Capability.ETH70).fromBytes(Codes.GetReceiptsCode, encoded) match {
          case Right(_: ETHPackets.GetReceipts70) => succeed
          case Right(_: ETHPackets.GetReceipts69) =>
            fail("ETH70 decoder returned a GetReceipts69 — self-containment violation")
          case other => fail(s"Expected GetReceipts70, got $other")
        }
      }
    }

    "the ETH70 decoder handles Receipts70" should {
      "return ETH70's own Receipts70 type (not Receipts69)" taggedAs UnitTest in {
        import com.chipprbots.ethereum.rlp._
        val msg = ETHPackets.Receipts70(BigInt(1), lastBlockIncomplete = false, RLPList())
        val encoded = msg.toBytes
        decoder(Capability.ETH70).fromBytes(Codes.ReceiptsCode, encoded) match {
          case Right(_: ETHPackets.Receipts70) => succeed
          case Right(_: ETHPackets.Receipts69) =>
            fail("ETH70 decoder returned a Receipts69 — self-containment violation")
          case other => fail(s"Expected Receipts70, got $other")
        }
      }
    }

    "the ETH70 decoder handles Status70" should {
      "return ETH70's own Status70 type (not Status69)" taggedAs UnitTest in {
        import ETHPackets.Status70.Status70._
        val msg = ETHPackets.Status70.Status70(
          70,
          1L,
          ByteString("genesis"),
          ForkId(1L, None),
          BigInt(0),
          BigInt(100),
          ByteString("hash")
        )
        val encoded = msg.toBytes
        decoder(Capability.ETH70).fromBytes(Codes.StatusCode, encoded) match {
          case Right(_: ETHPackets.Status70.Status70) => succeed
          case Right(_: ETHPackets.Status69.Status69) =>
            fail("ETH70 decoder returned a Status69 — self-containment violation")
          case other => fail(s"Expected Status70, got $other")
        }
      }
    }
  }
}
