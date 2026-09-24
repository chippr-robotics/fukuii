package com.chipprbots.ethereum.network.p2p.messages

import org.apache.pekko.util.ByteString

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.chipprbots.ethereum.forkid.ForkId
import com.chipprbots.ethereum.network.p2p.EthereumMessageDecoder
import com.chipprbots.ethereum.network.p2p.NetworkMessageDecoder
import com.chipprbots.ethereum.rlp.RLPList
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.domain.BloomFilter

/** Wire-format compliance tests for ETH68 and ETH69.
  *
  * Verifies that our packet encoding/decoding matches the EIP-7642 spec and the reference client implementations
  * (go-ethereum, Besu, Nethermind, Reth, Erigon).
  *
  * Run before every JAR build targeted at live peer testing.
  */
class ETH69ComplianceSpec extends AnyWordSpec with Matchers:

  private def decoder(cap: Capability) =
    NetworkMessageDecoder.orElse(EthereumMessageDecoder.ethMessageDecoder(cap))

  // ── Status68 — ETH68 wire format: 6-field [v, netId, td, best, genesis, forkId] ──

  "ETH68 Status68" when {
    import ETHPackets.Status68.Status68.*

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
        import com.chipprbots.ethereum.rlp.*
        val rlpDecoded = rawDecode(encoded)
        rlpDecoded match
          case RLPList(
                RLPValue(_),
                RLPValue(_),
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

  // ── Status69 — ETH69 wire format: 7-field [v, netId, genesis, forkId, earliest, latest, bestHash] ──

  "ETH69 Status69" when {
    import ETHPackets.Status69.Status69.*

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
        import com.chipprbots.ethereum.rlp.*
        val rlpDecoded = rawDecode(encoded)
        rlpDecoded match
          case rlpList: RLPList =>
            rlpList.items.size shouldEqual 7
            // Field 3 (index 2) should be genesisHash (32-byte ByteString), NOT a BigInt TD
            rlpList.items(2) match
              case RLPValue(bytes) => bytes.length shouldEqual 7 // "genesis".getBytes
              case _               => fail("Field 3 should be genesisHash RLPValue")
          case _ => fail("Expected RLPList for Status69")
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
        decoded match
          case Right(s: ETHPackets.Status69.Status69) =>
            s.genesisHash shouldEqual genesisHash
            s.latestBlock shouldEqual latestBlock
            s.latestBlockHash shouldEqual latestHash
          case other => fail(s"Expected Status69, got $other")
      }
    }
  }

  // ── BlockRangeUpdate — ETH69 new message: [earliest, latest, latestHash] ──

  "ETH69 BlockRangeUpdate" when {
    import ETHPackets.BlockRangeUpdate.*

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
    import ETHPackets.Receipts68.*

    "encoding and decoding" should {
      "round-trip with raw RLPList (bloom-inclusive blocks)" in {
        import com.chipprbots.ethereum.rlp.*
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
        decoder(Capability.ETH68).fromBytes(Codes.ReceiptsCode, encoded) match
          case Right(r: ETHPackets.Receipts68) =>
            r.requestId shouldEqual BigInt(42)
            // Verify bloom field is present (4 fields per receipt)
            val firstReceipt = r.receiptsForBlocks.items.head.asInstanceOf[RLPList].items.head.asInstanceOf[RLPList]
            firstReceipt.items.size shouldEqual 4 // stateHash, gasUsed, bloom, logs
          case other => fail(s"Expected Receipts68, got $other")
      }

      "decode from ETH68 decoder (not ETH69)" in {
        import com.chipprbots.ethereum.rlp.*
        val msg = ETHPackets.Receipts68(BigInt(1), RLPList())
        val encoded = msg.toBytes
        decoder(Capability.ETH68).fromBytes(Codes.ReceiptsCode, encoded) match
          case Right(_: ETHPackets.Receipts68) => succeed
          case other                           => fail(s"Expected Receipts68, got $other")
      }
    }
  }

  // ── Receipts69 — bloom ABSENT from wire format (EIP-7642) ──

  "ETH69 Receipts69" when {
    import ETHPackets.Receipts69.*

    "encoding and decoding" should {
      "round-trip with raw RLPList (bloom-absent blocks)" in {
        import com.chipprbots.ethereum.rlp.*
        // ETH69 receipt (EIP-7642): [txType, postStateOrStatus, cumulativeGasUsed, logs] — NO bloom
        val receiptRLP = RLPList(
          RLPValue(Array.emptyByteArray), // txType 0 (legacy)
          RLPValue(Array.fill(32)(0xaa.toByte)), // postStateOrStatus
          RLPValue(Array(0x01.toByte)), // cumulativeGasUsed
          RLPList() // logs — NO logsBloomFilter field
        )
        val receiptsForBlocks = RLPList(RLPList(receiptRLP))
        val msg = ETHPackets.Receipts69(requestId = BigInt(42), receiptsForBlocks)
        val encoded = msg.toBytes
        decoder(Capability.ETH69).fromBytes(Codes.ReceiptsCode, encoded) match
          case Right(r: ETHPackets.Receipts69) =>
            r.requestId shouldEqual BigInt(42)
            val firstReceipt = r.receiptsForBlocks.items.head.asInstanceOf[RLPList].items.head.asInstanceOf[RLPList]
            firstReceipt.items.size shouldEqual 4 // txType, postStateOrStatus, cumulativeGasUsed, logs — NO bloom
          case other => fail(s"Expected Receipts69, got $other")
      }

      "decode from ETH69 decoder (not ETH68)" in {
        import com.chipprbots.ethereum.rlp.*
        val msg = ETHPackets.Receipts69(BigInt(1), RLPList())
        val encoded = msg.toBytes
        decoder(Capability.ETH69).fromBytes(Codes.ReceiptsCode, encoded) match
          case Right(_: ETHPackets.Receipts69) => succeed
          case other                           => fail(s"Expected Receipts69, got $other")
      }

      "carry the tx type where eth/68 carries the bloom — [txType, status, gas, logs], not [status, gas, bloom, logs]" in {
        import com.chipprbots.ethereum.rlp.*
        import com.chipprbots.ethereum.domain.*
        // Both shapes have four items; what EIP-7642 changes is their meaning. A typed receipt is a plain list here,
        // not the EIP-2718 prefixed form used on eth/68.
        val typed = Type02Receipt(LegacyReceipt(SuccessOutcome, BigInt(100), BloomFilter.Empty, Seq.empty))
        ETHPackets.ReceiptBloomFreeEnc(typed).toRLPEncodable match
          case RLPList(RLPValue(txType), RLPValue(status), RLPValue(gas), _: RLPList) =>
            txType.toSeq shouldEqual Seq(2.toByte)
            status.toSeq shouldEqual Seq(1.toByte)
            gas.toSeq shouldEqual Seq(100.toByte)
          case other => fail(s"Expected [txType, postStateOrStatus, cumulativeGasUsed, logs], got $other")
      }
    }
  }

  // ── ETH68 supports GetReceipts (ETHPackets type) ──

  "ETH68 GetReceipts" when {
    import ETHPackets.GetReceipts.*

    "encoding and decoding" should {
      "round-trip via ETH68 decoder" in {
        val msg = ETHPackets.GetReceipts(BigInt(7), Seq(ByteString(Array.fill(32)(0xde.toByte))))
        val encoded = msg.toBytes
        decoder(Capability.ETH68).fromBytes(Codes.GetReceiptsCode, encoded) match
          case Right(_: ETHPackets.GetReceipts) => succeed
          case other                            => fail(s"Expected GetReceipts, got $other")
      }
    }
  }

  // ── ETH69 returns GetReceipts69 (distinct type triggering bloom-absent response) ──

  "ETH69 GetReceipts69" when {
    import ETHPackets.GetReceipts69.*

    "encoding and decoding" should {
      "return GetReceipts69 (not GetReceipts) from ETH69 decoder" in {
        val msg = ETHPackets.GetReceipts69(BigInt(9), Seq(ByteString(Array.fill(32)(0xde.toByte))))
        val encoded = msg.toBytes
        decoder(Capability.ETH69).fromBytes(Codes.GetReceiptsCode, encoded) match
          case Right(_: ETHPackets.GetReceipts69) => succeed
          case other                              => fail(s"Expected GetReceipts69, got $other")
      }
    }
  }

  // ── Receipts69 bloom ABSENT — regression test for the EIP-7642 bloom bug ────
  //
  // The bug: ETHPackets.Receipts69 was encoded using bloom-inclusive ReceiptEnc
  // (from ReceiptCodecs). The fix uses ReceiptBloomFreeEnc, which produces the EIP-7642
  // receipt [txType, postStateOrStatus, cumulativeGasUsed, logs] — no logsBloomFilter.
  // ReceiptWireFormatSpec pins those bytes against go-ethereum's.
  //
  // This test uses the production encoding path (ReceiptBloomFreeEnc) to prove that
  // a LegacyReceipt with a real non-zero bloom does NOT include that bloom on the wire.

  "ETH69 Receipts69 bloom regression" when {
    import ETHPackets.Receipts69.*
    import ETHPackets.ReceiptBloomFreeEnc
    import com.chipprbots.ethereum.rlp.*
    import com.chipprbots.ethereum.domain.*

    "encoding a LegacyReceipt via ReceiptBloomFreeEnc" should {

      "produce the four-item [txType, postStateOrStatus, cumulativeGasUsed, logs] receipt (no bloom field)" taggedAs UnitTest in {
        val bloom256 = ByteString(Array.fill(256)(0xff.toByte))
        val receipt = LegacyReceipt(
          SuccessOutcome,
          cumulativeGasUsed = BigInt(21000),
          logsBloomFilter = BloomFilter(bloom256),
          logs = Seq.empty
        )
        val receiptRLP = new ReceiptBloomFreeEnc(receipt).toRLPEncodable
        receiptRLP match
          case r: RLPList =>
            r.items.size shouldEqual 4 // txType, postStateOrStatus, cumulativeGasUsed, logs
            r.items.exists {
              case RLPValue(bytes) => bytes.length == 256
              case _               => false
            } shouldBe false // NO bloom
          case _ => fail("Expected RLPList for receipt")
      }

      "not include the 256-byte bloom in Receipts69 wire bytes" taggedAs UnitTest in {
        val bloom256 = ByteString(Array.fill(256)(0xff.toByte))
        val receipt = LegacyReceipt(
          SuccessOutcome,
          cumulativeGasUsed = BigInt(21000),
          logsBloomFilter = BloomFilter(bloom256),
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

      "round-trip via ETH69 decoder and preserve field count = 4" taggedAs UnitTest in {
        val bloom256 = ByteString(Array.fill(256)(0xab.toByte))
        val receipt = LegacyReceipt(
          HashOutcome(ByteString(Array.fill(32)(0x11.toByte))),
          cumulativeGasUsed = BigInt(50000),
          logsBloomFilter = BloomFilter(bloom256),
          logs = Seq.empty
        )
        val receiptRLP = new ReceiptBloomFreeEnc(receipt).toRLPEncodable
        val receiptsForBlocks = RLPList(RLPList(receiptRLP))
        val msg = ETHPackets.Receipts69(BigInt(5), receiptsForBlocks)
        val wireBytes = msg.toBytes

        decoder(Capability.ETH69).fromBytes(Codes.ReceiptsCode, wireBytes) match
          case Right(r: ETHPackets.Receipts69) =>
            r.requestId shouldEqual BigInt(5)
            val blockReceipts = r.receiptsForBlocks.items.head.asInstanceOf[RLPList]
            val innerReceipt = blockReceipts.items.head.asInstanceOf[RLPList]
            innerReceipt.items.size shouldEqual 4 // txType, postStateOrStatus, cumulativeGasUsed, logs — no bloom
          case other => fail(s"Expected Receipts69, got $other")
      }
    }
  }

  // ── Status69 tolerant decode — live peers announce eth/69 but emit non-canonical STATUS shapes.
  // The codec uses a two-arm match: canonical 7-field arm, then a catch-all stub that extracts
  // version+networkId and zeros the rest so the genesis/networkId check can reject as UselessPeer
  // without a codec crash. All non-canonical inputs decode to the stub regardless of shape.

  "ETH69 Status69 tolerant decode" when {

    "receiving an ETH/68-shaped 6-field STATUS on the eth/69 channel" should {
      "decode as stub (empty genesis) for clean UselessPeer rejection" taggedAs UnitTest in {
        import ETHPackets.Status68.Status68.*
        val eth68Shaped = ETHPackets.Status68.Status68(
          protocolVersion = 69,
          networkId = 7L,
          totalDifficulty = BigInt("167378406679735"),
          bestHash = ByteString(Array.fill(32)(0xab.toByte)),
          genesisHash = ByteString(Array.fill(32)(0xcd.toByte)),
          forkId = ForkId(0xbe46d57cL, None)
        )
        val decoded = decoder(Capability.ETH69).fromBytes(Codes.StatusCode, eth68Shaped.toBytes)
        decoded match
          case Right(s: ETHPackets.Status69.Status69) =>
            s.protocolVersion shouldEqual 69
            s.networkId shouldEqual 7L
            s.genesisHash shouldEqual ByteString.empty
            s.forkId shouldEqual ForkId(0, None)
            s.earliestBlock shouldEqual BigInt(0)
            s.latestBlock shouldEqual BigInt(0)
            s.latestBlockHash shouldEqual ByteString.empty
          case other => fail(s"Expected stub Status69 decode, got $other")
      }
    }

    "receiving the legacy 6-field shape (forkId at index 3, no earliestBlock)" should {
      "decode as stub (empty genesis) for clean UselessPeer rejection" taggedAs UnitTest in {
        import com.chipprbots.ethereum.rlp.*
        import ETHPackets.Status69.Status69.*
        val canonical = ETHPackets.Status69.Status69(
          protocolVersion = 69,
          networkId = 7L,
          genesisHash = ByteString(Array.fill(32)(0x11.toByte)),
          forkId = ForkId(0xfc64ec04L, Some(1150000L)),
          earliestBlock = BigInt(0),
          latestBlock = BigInt(424242),
          latestBlockHash = ByteString(Array.fill(32)(0x22.toByte))
        )
        val i = rawDecode(canonical.toBytes).asInstanceOf[RLPList].items
        val legacyBytes = encode(RLPList(i(0), i(1), i(2), i(3), i(5), i(6)))
        val decoded = decoder(Capability.ETH69).fromBytes(Codes.StatusCode, legacyBytes)
        decoded match
          case Right(s: ETHPackets.Status69.Status69) =>
            s.networkId shouldEqual 7L
            s.genesisHash shouldEqual ByteString.empty
            s.forkId shouldEqual ForkId(0, None)
            s.earliestBlock shouldEqual BigInt(0)
            s.latestBlock shouldEqual BigInt(0)
            s.latestBlockHash shouldEqual ByteString.empty
          case other => fail(s"Expected stub Status69 decode, got $other")
      }
    }

    "receiving an 8-field STATUS (canonical 7 + 1 trailing extension field)" should {
      "decode as stub (empty genesis) for clean UselessPeer rejection" taggedAs UnitTest in {
        import com.chipprbots.ethereum.rlp.*
        import ETHPackets.Status69.Status69.*
        val canonical = ETHPackets.Status69.Status69(
          protocolVersion = 69,
          networkId = 1L,
          genesisHash = ByteString(Array.fill(32)(0x33.toByte)),
          forkId = ForkId(0xbe46d57cL, None),
          earliestBlock = BigInt(0),
          latestBlock = BigInt(20000000),
          latestBlockHash = ByteString(Array.fill(32)(0x44.toByte))
        )
        val i = rawDecode(canonical.toBytes).asInstanceOf[RLPList].items
        val eightFieldBytes =
          encode(RLPList(i(0), i(1), i(2), i(3), i(4), i(5), i(6), RLPValue(BigInt(99).toByteArray)))
        val decoded = decoder(Capability.ETH69).fromBytes(Codes.StatusCode, eightFieldBytes)
        decoded match
          case Right(s: ETHPackets.Status69.Status69) =>
            s.networkId shouldEqual 1L
            s.genesisHash shouldEqual ByteString.empty
            s.forkId shouldEqual ForkId(0, None)
            s.earliestBlock shouldEqual BigInt(0)
            s.latestBlock shouldEqual BigInt(0)
            s.latestBlockHash shouldEqual ByteString.empty
          case other => fail(s"Expected stub Status69 decode, got $other")
      }
    }

    "receiving an 8-field all-RLPValue STATUS (no forkId RLPList — live observed variant)" should {
      "decode as stub (empty genesis) for clean UselessPeer rejection" taggedAs UnitTest in {
        import com.chipprbots.ethereum.rlp.*
        // Mirrors the live DECODE_ERROR observed on 5.161.72.73:30303:
        // RLPList(Queue(RLPValue(45), RLPValue(89), ...)) — 8 RLPValues, no embedded RLPList.
        val eightAllValueBytes = encode(
          RLPList(
            RLPValue(Array[Byte](45)),
            RLPValue(Array[Byte](89)),
            RLPValue(Array.fill(32)(0x00.toByte)),
            RLPValue(Array.fill(32)(0x00.toByte)),
            RLPValue(Array[Byte](0)),
            RLPValue(Array.fill(8)(0x00.toByte)),
            RLPValue(Array.fill(32)(0x00.toByte)),
            RLPValue(Array[Byte](0))
          )
        )
        val decoded = decoder(Capability.ETH69).fromBytes(Codes.StatusCode, eightAllValueBytes)
        decoded match
          case Right(s: ETHPackets.Status69.Status69) =>
            s.protocolVersion shouldEqual 45
            s.networkId shouldEqual 89L
            s.genesisHash shouldEqual ByteString.empty
          case other => fail(s"Expected stub Status69 decode for all-RLPValue input, got $other")
      }
    }

    "receiving a STATUS that matches no known shape" should {
      "still be rejected" taggedAs UnitTest in {
        import com.chipprbots.ethereum.rlp.*
        // Single-field input — does not satisfy the 2-field minimum of the catch-all arm.
        val garbage = encode(RLPList(RLPValue(Array[Byte](69))))
        val decoded = decoder(Capability.ETH69).fromBytes(Codes.StatusCode, garbage)
        decoded.isLeft shouldBe true
      }
    }
  }
