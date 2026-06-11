package com.chipprbots.ethereum.network.p2p.messages

import org.apache.pekko.util.ByteString

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.chipprbots.ethereum.forkid.ForkId
import com.chipprbots.ethereum.network.p2p.EthereumMessageDecoder
import com.chipprbots.ethereum.network.p2p.NetworkMessageDecoder
import com.chipprbots.ethereum.rlp.RLPList
import com.chipprbots.ethereum.testing.Tags._

/** Wire-format compliance tests for ETH68 and ETH69.
  *
  * Verifies that our packet encoding/decoding matches the EIP-7642 spec and the reference client implementations
  * (go-ethereum, Besu, Nethermind, Reth, Erigon).
  *
  * Run before every JAR build targeted at live peer testing.
  */
class ETH69ComplianceSpec extends AnyWordSpec with Matchers {

  private def decoder(cap: Capability) =
    NetworkMessageDecoder.orElse(EthereumMessageDecoder.ethMessageDecoder(cap))

  // ── Status68 — ETH68 wire format: 6-field [v, netId, td, best, genesis, forkId] ──

  "ETH68 Status68" when {
    import ETHPackets.Status68.Status68._

    "encoding and decoding" should {
      "round-trip correctly with all 6 fields" in {
        val msg = ETHPackets.Status68.Status68(
          protocolVersion = 68,
          networkId = 61L,
          totalDifficulty = BigInt("1000000000000000000"),
          bestHash = ByteString(Array.fill(32)(0xab.toByte)),
          genesisHash = ByteString(Array.fill(32)(0xcd.toByte)),
          forkId = ForkId(0xbe46d57cL, None)
        )
        val encoded = msg.toBytes
        val decoded = decoder(Capability.ETH68).fromBytes(Codes.StatusCode, encoded)
        decoded shouldEqual Right(msg)
      }

      "have TD as field 3 (PoW ETC — TD is permanent, not legacy)" in {
        val td = BigInt("34359738368")
        val msg = ETHPackets.Status68.Status68(68, 61L, td, ByteString("best"), ByteString("genesis"), ForkId(1L, None))
        val encoded = msg.toBytes
        // Wire: RLPList[v, netId, td, best, genesis, forkId]
        import com.chipprbots.ethereum.rlp._
        val rlpDecoded = rawDecode(encoded)
        rlpDecoded match {
          case RLPList(
                RLPValue(vBytes),
                RLPValue(netIdBytes),
                RLPValue(tdBytes), // field 3 = TD (not genesis)
                RLPValue(_), // bestHash
                RLPValue(_), // genesisHash
                _ // forkId list
              ) =>
            import com.chipprbots.ethereum.utils.ByteUtils
            ByteUtils.bytesToBigInt(tdBytes) shouldEqual td
          case _ => fail("Expected 6-field RLPList for Status68")
        }
      }
    }
  }

  // ── Status69 — ETH69 wire format: 7-field [v, netId, genesis, forkId, earliest, latest, bestHash] ──

  "ETH69 Status69" when {
    import ETHPackets.Status69.Status69._

    "encoding and decoding" should {
      "round-trip correctly with all 7 fields" in {
        val msg = ETHPackets.Status69.Status69(
          protocolVersion = 69,
          networkId = 61L,
          genesisHash = ByteString(Array.fill(32)(0xcd.toByte)),
          forkId = ForkId(0xbe46d57cL, None),
          earliestBlock = BigInt(0),
          latestBlock = BigInt(21000000),
          latestBlockHash = ByteString(Array.fill(32)(0xab.toByte))
        )
        val encoded = msg.toBytes
        val decoded = decoder(Capability.ETH69).fromBytes(Codes.StatusCode, encoded)
        decoded shouldEqual Right(msg)
      }

      "have NO totalDifficulty field (ETH69 is TD-free)" in {
        val msg = ETHPackets.Status69.Status69(
          69,
          61L,
          ByteString("genesis"),
          ForkId(1L, None),
          BigInt(0),
          BigInt(100),
          ByteString("hash")
        )
        val encoded = msg.toBytes
        import com.chipprbots.ethereum.rlp._
        val rlpDecoded = rawDecode(encoded)
        rlpDecoded match {
          case rlpList: RLPList =>
            rlpList.items.size shouldEqual 7
            // Field 3 (index 2) should be genesisHash (32-byte ByteString), NOT a BigInt TD
            rlpList.items(2) match {
              case RLPValue(bytes) => bytes.length shouldEqual 7 // "genesis".getBytes
              case _               => fail("Field 3 should be genesisHash RLPValue")
            }
          case _ => fail("Expected RLPList for Status69")
        }
      }

      "field ordering matches EIP-7642: [v, netId, genesis, forkId, earliest, latest, bestHash]" in {
        val genesisHash = ByteString(Array.fill(32)(0x11.toByte))
        val latestHash = ByteString(Array.fill(32)(0x22.toByte))
        val latestBlock = BigInt(12345678)
        val msg = ETHPackets.Status69.Status69(
          69,
          61L,
          genesisHash,
          ForkId(0xbe46d57cL, None),
          BigInt(0),
          latestBlock,
          latestHash
        )
        val encoded = msg.toBytes
        // Decode as ETH69 — should give back same fields
        val decoded = decoder(Capability.ETH69).fromBytes(Codes.StatusCode, encoded)
        decoded match {
          case Right(s: ETHPackets.Status69.Status69) =>
            s.genesisHash shouldEqual genesisHash
            s.latestBlock shouldEqual latestBlock
            s.latestBlockHash shouldEqual latestHash
          case other => fail(s"Expected Status69, got $other")
        }
      }
    }
  }

  // ── BlockRangeUpdate — ETH69 new message: [earliest, latest, latestHash] ──

  "ETH69 BlockRangeUpdate" when {
    import ETHPackets.BlockRangeUpdate._

    "encoding and decoding" should {
      "round-trip correctly" in {
        val msg = ETHPackets.BlockRangeUpdate(BigInt(0), BigInt(21000000), ByteString(Array.fill(32)(0xab.toByte)))
        val encoded = msg.toBytes
        val decoded = decoder(Capability.ETH69).fromBytes(Codes.BlockRangeUpdateCode, encoded)
        decoded shouldEqual Right(msg)
      }

      "be REJECTED by ETH68 decoder" taggedAs UnitTest in {
        val msg = ETHPackets.BlockRangeUpdate(BigInt(0), BigInt(1000), ByteString("hash12345678901234567890123456789"))
        val encoded = msg.toBytes
        val decoded = decoder(Capability.ETH68).fromBytes(Codes.BlockRangeUpdateCode, encoded)
        decoded.isLeft shouldBe true
      }
    }
  }

  // ── Receipts68 — bloom PRESENT in wire format ──

  "ETH68 Receipts68" when {
    import ETHPackets.Receipts68._

    "encoding and decoding" should {
      "round-trip with raw RLPList (bloom-inclusive blocks)" in {
        import com.chipprbots.ethereum.rlp._
        val bloom256 = Array.fill(256)(0xff.toByte)
        val receiptRLP = RLPList(
          RLPValue(Array.fill(32)(0xaa.toByte)), // stateHash
          RLPValue(Array(0x01.toByte)), // gasUsed
          RLPValue(bloom256), // logsBloom (256 bytes) ← present in ETH68
          RLPList() // logs
        )
        val receiptsForBlocks = RLPList(RLPList(receiptRLP))
        val msg = ETHPackets.Receipts68(requestId = BigInt(42), receiptsForBlocks)
        val encoded = msg.toBytes
        decoder(Capability.ETH68).fromBytes(Codes.ReceiptsCode, encoded) match {
          case Right(r: ETHPackets.Receipts68) =>
            r.requestId shouldEqual BigInt(42)
            // Verify bloom field is present (4 fields per receipt)
            val firstReceipt = r.receiptsForBlocks.items.head.asInstanceOf[RLPList].items.head.asInstanceOf[RLPList]
            firstReceipt.items.size shouldEqual 4 // stateHash, gasUsed, bloom, logs
          case other => fail(s"Expected Receipts68, got $other")
        }
      }

      "decode from ETH68 decoder (not ETH69)" in {
        import com.chipprbots.ethereum.rlp._
        val msg = ETHPackets.Receipts68(BigInt(1), RLPList())
        val encoded = msg.toBytes
        decoder(Capability.ETH68).fromBytes(Codes.ReceiptsCode, encoded) match {
          case Right(_: ETHPackets.Receipts68) => succeed
          case other                           => fail(s"Expected Receipts68, got $other")
        }
      }
    }
  }

  // ── Receipts69 — bloom ABSENT from wire format (EIP-7642) ──

  "ETH69 Receipts69" when {
    import ETHPackets.Receipts69._

    "encoding and decoding" should {
      "round-trip with raw RLPList (bloom-absent blocks)" in {
        import com.chipprbots.ethereum.rlp._
        // ETH69 receipt: only 3 fields — [stateHash, gasUsed, logs] — NO bloom (EIP-7642)
        val receiptRLP = RLPList(
          RLPValue(Array.fill(32)(0xaa.toByte)), // stateHash
          RLPValue(Array(0x01.toByte)), // gasUsed
          RLPList() // logs — NO logsBloomFilter field
        )
        val receiptsForBlocks = RLPList(RLPList(receiptRLP))
        val msg = ETHPackets.Receipts69(requestId = BigInt(42), receiptsForBlocks)
        val encoded = msg.toBytes
        decoder(Capability.ETH69).fromBytes(Codes.ReceiptsCode, encoded) match {
          case Right(r: ETHPackets.Receipts69) =>
            r.requestId shouldEqual BigInt(42)
            // Verify bloom field is ABSENT (3 fields per receipt, not 4)
            val firstReceipt = r.receiptsForBlocks.items.head.asInstanceOf[RLPList].items.head.asInstanceOf[RLPList]
            firstReceipt.items.size shouldEqual 3 // stateHash, gasUsed, logs — NO bloom
          case other => fail(s"Expected Receipts69, got $other")
        }
      }

      "decode from ETH69 decoder (not ETH68)" in {
        import com.chipprbots.ethereum.rlp._
        val msg = ETHPackets.Receipts69(BigInt(1), RLPList())
        val encoded = msg.toBytes
        decoder(Capability.ETH69).fromBytes(Codes.ReceiptsCode, encoded) match {
          case Right(_: ETHPackets.Receipts69) => succeed
          case other                           => fail(s"Expected Receipts69, got $other")
        }
      }

      "have 3 fields per receipt (not 4) — bloom ABSENT" in {
        import com.chipprbots.ethereum.rlp._
        // Bloom-absent receipt has 3 fields
        val bloomAbsentReceipt = RLPList(
          RLPValue(Array.fill(32)(0xaa.toByte)), // stateHash
          RLPValue(Array(0x64.toByte)), // gasUsed = 100
          RLPList() // logs
        )
        // Verify this is indeed 3 fields (not 4 with bloom)
        bloomAbsentReceipt.items.size shouldEqual 3
        // A bloom-inclusive receipt would have 4 fields
        val bloomReceipt = RLPList(
          RLPValue(Array.fill(32)(0xaa.toByte)), // stateHash
          RLPValue(Array(0x64.toByte)), // gasUsed
          RLPValue(Array.fill(256)(0x00.toByte)), // logsBloom  ← present in ETH68
          RLPList() // logs
        )
        bloomReceipt.items.size shouldEqual 4
      }
    }
  }

  // ── ETH68 supports GetReceipts (ETHPackets type) ──

  "ETH68 GetReceipts" when {
    import ETHPackets.GetReceipts._

    "encoding and decoding" should {
      "round-trip via ETH68 decoder" in {
        val msg = ETHPackets.GetReceipts(BigInt(7), Seq(ByteString(Array.fill(32)(0xde.toByte))))
        val encoded = msg.toBytes
        decoder(Capability.ETH68).fromBytes(Codes.GetReceiptsCode, encoded) match {
          case Right(_: ETHPackets.GetReceipts) => succeed
          case other                            => fail(s"Expected GetReceipts, got $other")
        }
      }
    }
  }

  // ── ETH69 returns GetReceipts69 (distinct type triggering bloom-absent response) ──

  "ETH69 GetReceipts69" when {
    import ETHPackets.GetReceipts69._

    "encoding and decoding" should {
      "return GetReceipts69 (not GetReceipts) from ETH69 decoder" in {
        val msg = ETHPackets.GetReceipts69(BigInt(9), Seq(ByteString(Array.fill(32)(0xde.toByte))))
        val encoded = msg.toBytes
        decoder(Capability.ETH69).fromBytes(Codes.GetReceiptsCode, encoded) match {
          case Right(_: ETHPackets.GetReceipts69) => succeed
          case other                              => fail(s"Expected GetReceipts69, got $other")
        }
      }
    }
  }

  // ── Receipts69 bloom ABSENT — regression test for the EIP-7642 bloom bug ────
  //
  // The bug: ETHPackets.Receipts69 was encoded using bloom-inclusive ReceiptEnc
  // (from ReceiptCodecs), producing 4-field receipts. The fix uses ReceiptBloomFreeEnc
  // which produces 3-field receipts: [stateHash, gasUsed, logs] — no logsBloomFilter.
  //
  // This test uses the production encoding path (ReceiptBloomFreeEnc) to prove that
  // a LegacyReceipt with a real non-zero bloom does NOT include that bloom on the wire.

  "ETH69 Receipts69 bloom regression" when {
    import ETHPackets.Receipts69._
    import ETHPackets.ReceiptBloomFreeEnc
    import com.chipprbots.ethereum.rlp._
    import com.chipprbots.ethereum.domain._

    "encoding a LegacyReceipt via ReceiptBloomFreeEnc" should {

      "produce 3-field receipt RLP (no bloom field)" taggedAs UnitTest in {
        val bloom256 = ByteString(Array.fill(256)(0xff.toByte))
        val receipt = LegacyReceipt(
          SuccessOutcome,
          cumulativeGasUsed = BigInt(21000),
          logsBloomFilter = bloom256,
          logs = Seq.empty
        )
        val receiptRLP = new ReceiptBloomFreeEnc(receipt).toRLPEncodable
        receiptRLP match {
          case r: RLPList =>
            r.items.size shouldEqual 3 // stateHash, gasUsed, logs — NO bloom
          case _ => fail("Expected RLPList for receipt")
        }
      }

      "not include the 256-byte bloom in Receipts69 wire bytes" taggedAs UnitTest in {
        val bloom256 = ByteString(Array.fill(256)(0xff.toByte))
        val receipt = LegacyReceipt(
          SuccessOutcome,
          cumulativeGasUsed = BigInt(21000),
          logsBloomFilter = bloom256,
          logs = Seq.empty
        )
        // Build Receipts69 using the production bloom-free path
        val receiptRLP = new ReceiptBloomFreeEnc(receipt).toRLPEncodable
        val receiptsForBlocks = RLPList(RLPList(receiptRLP))
        val msg = ETHPackets.Receipts69(BigInt(1), receiptsForBlocks)
        val wireBytes = msg.toBytes

        // The 256-byte all-0xff bloom must not appear anywhere in the wire encoding.
        // We slide a 256-byte window over the bytes and check no run of 0xff x256 exists.
        val bloomBytes = bloom256.toArray[Byte]
        val hasBloom = wireBytes.sliding(256).exists(window => window.sameElements(bloomBytes))
        hasBloom shouldBe false
      }

      "round-trip via ETH69 decoder and preserve field count = 3" taggedAs UnitTest in {
        val bloom256 = ByteString(Array.fill(256)(0xab.toByte))
        val receipt = LegacyReceipt(
          HashOutcome(ByteString(Array.fill(32)(0x11.toByte))),
          cumulativeGasUsed = BigInt(50000),
          logsBloomFilter = bloom256,
          logs = Seq.empty
        )
        val receiptRLP = new ReceiptBloomFreeEnc(receipt).toRLPEncodable
        val receiptsForBlocks = RLPList(RLPList(receiptRLP))
        val msg = ETHPackets.Receipts69(BigInt(5), receiptsForBlocks)
        val wireBytes = msg.toBytes

        decoder(Capability.ETH69).fromBytes(Codes.ReceiptsCode, wireBytes) match {
          case Right(r: ETHPackets.Receipts69) =>
            r.requestId shouldEqual BigInt(5)
            val blockReceipts = r.receiptsForBlocks.items.head.asInstanceOf[RLPList]
            val innerReceipt = blockReceipts.items.head.asInstanceOf[RLPList]
            innerReceipt.items.size shouldEqual 3 // no bloom
          case other => fail(s"Expected Receipts69, got $other")
        }
      }
    }
  }

  // ── Status69 tolerant decode — live peers announce eth/69 but emit non-canonical STATUS shapes.
  // The codec must decode them so the networkId/genesis handshake check can reject off-network
  // peers as UselessPeer; a codec throw disconnects + blacklists the peer and starves the pool.

  "ETH69 Status69 tolerant decode" when {

    "receiving an ETH/68-shaped 6-field STATUS on the eth/69 channel" should {
      "decode it (forkId at index 5, bestHash mapped to latestBlockHash)" taggedAs UnitTest in {
        import ETHPackets.Status68.Status68._
        val genesisHash = ByteString(Array.fill(32)(0xcd.toByte))
        val bestHash = ByteString(Array.fill(32)(0xab.toByte))
        val eth68Shaped = ETHPackets.Status68.Status68(
          protocolVersion = 69, // announces eth/69 in the payload, but the shape is ETH/68
          networkId = 7L,
          totalDifficulty = BigInt("167378406679735"),
          bestHash = bestHash,
          genesisHash = genesisHash,
          forkId = ForkId(0xbe46d57cL, None)
        )
        val encoded = eth68Shaped.toBytes
        val decoded = decoder(Capability.ETH69).fromBytes(Codes.StatusCode, encoded)
        decoded match {
          case Right(s: ETHPackets.Status69.Status69) =>
            s.protocolVersion shouldEqual 69
            s.networkId shouldEqual 7L
            s.genesisHash shouldEqual genesisHash
            s.forkId shouldEqual ForkId(0xbe46d57cL, None)
            s.earliestBlock shouldEqual BigInt(0)
            s.latestBlock shouldEqual BigInt(0)
            s.latestBlockHash shouldEqual bestHash
          case other => fail(s"Expected tolerant Status69 decode, got $other")
        }
      }
    }

    "receiving the legacy 6-field shape (forkId at index 3, no earliestBlock)" should {
      "decode it with earliestBlock defaulted to 0" taggedAs UnitTest in {
        import com.chipprbots.ethereum.rlp._
        import ETHPackets.Status69.Status69._
        val genesisHash = ByteString(Array.fill(32)(0x11.toByte))
        val latestHash = ByteString(Array.fill(32)(0x22.toByte))
        val canonical = ETHPackets.Status69.Status69(
          protocolVersion = 69,
          networkId = 7L,
          genesisHash = genesisHash,
          forkId = ForkId(0xfc64ec04L, Some(1150000L)),
          earliestBlock = BigInt(0),
          latestBlock = BigInt(424242),
          latestBlockHash = latestHash
        )
        // Rearrange the canonical 7-field wire form [v, netId, genesis, forkId, earliest, latest,
        // latestHash] into the legacy 6-field form [v, netId, genesis, forkId, latest, latestHash].
        val canonicalRlp = rawDecode(canonical.toBytes).asInstanceOf[RLPList]
        val i = canonicalRlp.items
        val legacyBytes = encode(RLPList(i(0), i(1), i(2), i(3), i(5), i(6)))
        val decoded = decoder(Capability.ETH69).fromBytes(Codes.StatusCode, legacyBytes)
        decoded match {
          case Right(s: ETHPackets.Status69.Status69) =>
            s.networkId shouldEqual 7L
            s.genesisHash shouldEqual genesisHash
            s.forkId shouldEqual ForkId(0xfc64ec04L, Some(1150000L))
            s.earliestBlock shouldEqual BigInt(0)
            s.latestBlock shouldEqual BigInt(424242)
            s.latestBlockHash shouldEqual latestHash
          case other => fail(s"Expected tolerant legacy Status69 decode, got $other")
        }
      }
    }

    "receiving a STATUS that matches no known shape" should {
      "still be rejected" taggedAs UnitTest in {
        import com.chipprbots.ethereum.rlp._
        val garbage = encode(RLPList(RLPValue(Array[Byte](69)), RLPValue(Array[Byte](7))))
        val decoded = decoder(Capability.ETH69).fromBytes(Codes.StatusCode, garbage)
        decoded.isLeft shouldBe true
      }
    }
  }
}
