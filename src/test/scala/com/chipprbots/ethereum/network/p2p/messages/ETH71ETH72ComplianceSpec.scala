package com.chipprbots.ethereum.network.p2p.messages

import org.apache.pekko.util.ByteString

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.chipprbots.ethereum.forkid.ForkId
import com.chipprbots.ethereum.network.p2p.ETH71MessageDecoder
import com.chipprbots.ethereum.network.p2p.ETH72MessageDecoder
import com.chipprbots.ethereum.network.p2p.EthereumMessageDecoder
import com.chipprbots.ethereum.network.p2p.NetworkMessageDecoder
import com.chipprbots.ethereum.rlp.RLPValue
import com.chipprbots.ethereum.testing.Tags.*

/** Wire-format compliance tests for ETH71 (EIP-8159 block access lists) and ETH72 (EIP-8070 cell exchange), matching
  * go-ethereum master's `eth/protocols/eth/protocol.go` message tables:
  *   - protocolLengths: {69: 18, 70: 18, 71: 20, 72: 22}
  *   - ETH71 adds GetBlockAccessLists (0x12) / BlockAccessLists (0x13) on top of ETH70's set.
  *   - ETH72 adds GetCells (0x14) / Cells (0x15) AND switches NewPooledTransactionHashes to the 4-field form (adds a
  *     PeerDAS custody Mask) — same wire code, version-gated shape.
  *
  * Run before every JAR build targeted at live peer testing.
  */
class ETH71ETH72ComplianceSpec extends AnyWordSpec with Matchers:

  private def decoder(cap: Capability) =
    NetworkMessageDecoder.orElse(EthereumMessageDecoder.ethMessageDecoder(cap))

  private def hash(b: Int): ByteString = ByteString(Array.fill(32)(b.toByte))

  // RLPValue wraps a raw Array[Byte] with no custom equals/hashCode (package.scala:42), so two
  // content-equal RLPValues are never `==` to each other (Array uses reference equality) — compare
  // by RLP-encoded bytes instead, which DOES have structural Seq equality.
  private def rlpBytes(e: com.chipprbots.ethereum.rlp.RLPEncodeable): Seq[Byte] =
    com.chipprbots.ethereum.rlp.encode(e).toSeq

  // ── Status reuse — ETH71/ETH72 do not change the Status wire format vs ETH70 ──

  "ETH71 and ETH72 Status" should {
    "decode as Status70 (go-ethereum defines exactly one StatusPacket for 69-72)" taggedAs UnitTest in {
      import ETHPackets.Status70.Status70.*
      val msg = ETHPackets.Status70.Status70(
        protocolVersion = 71,
        networkId = 1L,
        genesisHash = ByteString(Array.fill(32)(0xcd.toByte)),
        forkId = ForkId(0xbe46d57cL, None),
        earliestBlock = BigInt(0),
        latestBlock = BigInt(22000000),
        latestBlockHash = ByteString(Array.fill(32)(0xab.toByte))
      )
      val encoded = msg.toBytes
      decoder(Capability.ETH71).fromBytes(Codes.StatusCode, encoded) shouldEqual Right(msg)
      decoder(Capability.ETH72).fromBytes(Codes.StatusCode, encoded.clone()) match
        case Right(s: ETHPackets.Status70.Status70) => s.protocolVersion shouldEqual 71 // wire value, not renegotiated
        case other                                  => fail(s"Expected Status70, got $other")
    }
  }

  // ── ETH71 message set = ETH70 (14) + GetBlockAccessLists + BlockAccessLists = 16 ──

  "ETH71MessageDecoder" should {
    "support exactly 16 message codes" taggedAs UnitTest in {
      ETH71MessageDecoder.supportedMessages.size shouldEqual 16
      ETH71MessageDecoder.supportedMessages should contain(Codes.GetBlockAccessListsCode)
      ETH71MessageDecoder.supportedMessages should contain(Codes.BlockAccessListsCode)
    }

    "still decode GetReceipts as GetReceipts70 (unchanged from ETH70)" taggedAs UnitTest in {
      val msg = ETHPackets.GetReceipts70(BigInt(1), 0L, Seq(hash(0xde)))
      decoder(Capability.ETH71).fromBytes(Codes.GetReceiptsCode, msg.toBytes) match
        case Right(_: ETHPackets.GetReceipts70) => succeed
        case other                              => fail(s"Expected GetReceipts70, got $other")
    }
  }

  "ETH71 GetBlockAccessLists / BlockAccessLists" should {
    "round-trip a request for multiple hashes, order preserved" taggedAs UnitTest in {
      val hashes = Seq(hash(1), hash(2), hash(3))
      val msg = ETHPackets.GetBlockAccessLists(BigInt(7), hashes)
      decoder(Capability.ETH71).fromBytes(Codes.GetBlockAccessListsCode, msg.toBytes) match
        case Right(r: ETHPackets.GetBlockAccessLists) =>
          r.requestId shouldEqual BigInt(7)
          r.blockHashes shouldEqual hashes
        case other => fail(s"Expected GetBlockAccessLists, got $other")
    }

    "encode an unavailable entry as the RLP empty string (0x80), not a skipped position" taggedAs UnitTest in {
      val response = ETHPackets.BlockAccessLists(BigInt(9), Seq(RLPValue(Array.emptyByteArray)))
      val encoded = response.toBytes
      decoder(Capability.ETH71).fromBytes(Codes.BlockAccessListsCode, encoded) match
        case Right(r: ETHPackets.BlockAccessLists) =>
          r.requestId shouldEqual BigInt(9)
          r.entries.size shouldEqual 1
          rlpBytes(r.entries.head) shouldEqual rlpBytes(RLPValue(Array.emptyByteArray))
        case other => fail(s"Expected BlockAccessLists, got $other")
    }

    "preserve entry count and order across three positions, two unavailable" taggedAs UnitTest in {
      val entries = Seq(
        RLPValue(Array.emptyByteArray),
        RLPValue(Array[Byte](1, 2, 3)),
        RLPValue(Array.emptyByteArray)
      )
      val response = ETHPackets.BlockAccessLists(BigInt(1), entries)
      decoder(Capability.ETH71).fromBytes(Codes.BlockAccessListsCode, response.toBytes) match
        case Right(r: ETHPackets.BlockAccessLists) => r.entries.map(rlpBytes) shouldEqual entries.map(rlpBytes)
        case other                                 => fail(s"Expected BlockAccessLists, got $other")
    }
  }

  // ── ETH72 message set = ETH71 (16) + GetCells + Cells = 18 ──

  "ETH72MessageDecoder" should {
    "support exactly 18 message codes" taggedAs UnitTest in {
      ETH72MessageDecoder.supportedMessages.size shouldEqual 18
      ETH72MessageDecoder.supportedMessages should contain(Codes.GetCellsCode)
      ETH72MessageDecoder.supportedMessages should contain(Codes.CellsCode)
    }

    "still serve GetBlockAccessLists (unchanged from ETH71)" taggedAs UnitTest in {
      val msg = ETHPackets.GetBlockAccessLists(BigInt(1), Seq(hash(1)))
      decoder(Capability.ETH72).fromBytes(Codes.GetBlockAccessListsCode, msg.toBytes) match
        case Right(_: ETHPackets.GetBlockAccessLists) => succeed
        case other                                    => fail(s"Expected GetBlockAccessLists, got $other")
    }
  }

  "ETH72 GetCells / Cells" should {
    "round-trip a request with hashes and a 16-byte custody mask" taggedAs UnitTest in {
      val mask = ByteString(Array.tabulate(16)(_.toByte))
      val msg = ETHPackets.GetCells(BigInt(3), Seq(hash(1), hash(2)), mask)
      decoder(Capability.ETH72).fromBytes(Codes.GetCellsCode, msg.toBytes) match
        case Right(r: ETHPackets.GetCells) =>
          r.requestId shouldEqual BigInt(3)
          r.hashes shouldEqual Seq(hash(1), hash(2))
          r.mask shouldEqual mask
        case other => fail(s"Expected GetCells, got $other")
    }

    "round-trip an empty response (fukuii has no cell storage) with the mask echoed back" taggedAs UnitTest in {
      val mask = ByteString(new Array[Byte](16))
      val msg = ETHPackets.Cells(BigInt(4), Seq.empty, Seq.empty, mask)
      decoder(Capability.ETH72).fromBytes(Codes.CellsCode, msg.toBytes) match
        case Right(r: ETHPackets.Cells) =>
          r.requestId shouldEqual BigInt(4)
          r.hashes shouldBe empty
          r.cells shouldBe empty
          r.mask shouldEqual mask
        case other => fail(s"Expected Cells, got $other")
    }

    "round-trip a non-empty response with per-hash cell lists" taggedAs UnitTest in {
      val mask = ByteString(Array.fill(16)(0xff.toByte))
      val cellA = ByteString(Array.fill(2048)(0x11.toByte))
      val cellB = ByteString(Array.fill(2048)(0x22.toByte))
      val msg = ETHPackets.Cells(BigInt(5), Seq(hash(1)), Seq(Seq(cellA, cellB)), mask)
      decoder(Capability.ETH72).fromBytes(Codes.CellsCode, msg.toBytes) match
        case Right(r: ETHPackets.Cells) =>
          r.hashes shouldEqual Seq(hash(1))
          r.cells shouldEqual Seq(Seq(cellA, cellB))
          r.mask shouldEqual mask
        case other => fail(s"Expected Cells, got $other")
    }
  }

  // ── ETH72 NewPooledTransactionHashes — strict 4-field form, no legacy fallback ──

  "ETH72 NewPooledTransactionHashes (4-field)" should {
    "round-trip types, sizes, hashes, and a custody mask" taggedAs UnitTest in {
      val mask = ByteString(Array.tabulate(16)(i => (i * 2).toByte))
      val msg = ETHPackets.NewPooledTransactionHashes72(
        types = Seq(0.toByte, 3.toByte),
        sizes = Seq(BigInt(100), BigInt(200)),
        hashes = Seq(hash(1), hash(2)),
        mask = mask
      )
      decoder(Capability.ETH72).fromBytes(Codes.NewPooledTransactionHashesCode, msg.toBytes) match
        case Right(r: ETHPackets.NewPooledTransactionHashes72) =>
          r.types shouldEqual Seq(0.toByte, 3.toByte)
          r.sizes shouldEqual Seq(BigInt(100), BigInt(200))
          r.hashes shouldEqual Seq(hash(1), hash(2))
          r.mask shouldEqual mask
        case other => fail(s"Expected NewPooledTransactionHashes72, got $other")
    }

    "reject a 3-field (ETH68-71 shaped) announcement — hard failure, not silent coercion" taggedAs UnitTest in {
      // Exactly the shape geth's testBadBlobTx/TestBlobViolations send unconditionally per
      // hive-failure-inventory.md's "eth/72 announcement shape" finding — negotiating ETH72 must
      // mean only the 4-field form is accepted on that connection.
      val legacy = ETHPackets.NewPooledTransactionHashes(
        types = Seq(0.toByte),
        sizes = Seq(BigInt(50)),
        hashes = Seq(hash(9))
      )
      val decoded = decoder(Capability.ETH72).fromBytes(Codes.NewPooledTransactionHashesCode, legacy.toBytes)
      decoded.isLeft shouldBe true
    }

    "reject a 4-field announcement sent to the plain (3-field) decoder used by ETH68-71" taggedAs UnitTest in {
      val mask = ByteString(new Array[Byte](16))
      val modern = ETHPackets.NewPooledTransactionHashes72(Seq(0.toByte), Seq(BigInt(1)), Seq(hash(1)), mask)
      val decoded = decoder(Capability.ETH71).fromBytes(Codes.NewPooledTransactionHashesCode, modern.toBytes)
      decoded.isLeft shouldBe true
    }
  }

  "NewPooledTransactionHashes72.NoCustody" should {
    "be an all-zero 16-byte bitmap (fukuii advertises no PeerDAS custody, never fabricated custody)" taggedAs UnitTest in {
      ETHPackets.NewPooledTransactionHashes72.NoCustody.size shouldEqual 16
      ETHPackets.NewPooledTransactionHashes72.NoCustody.forall(_ == 0) shouldBe true
    }
  }

  // ── EthereumMessageDecoder dispatch ──

  "EthereumMessageDecoder.ethMessageDecoder" should {
    "route ETH71 to ETH71MessageDecoder and ETH72 to ETH72MessageDecoder" taggedAs UnitTest in {
      EthereumMessageDecoder.ethMessageDecoder(Capability.ETH71) shouldBe ETH71MessageDecoder
      EthereumMessageDecoder.ethMessageDecoder(Capability.ETH72) shouldBe ETH72MessageDecoder
    }
  }
