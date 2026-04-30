package com.chipprbots.ethereum.network.p2p.messages

import org.apache.pekko.util.ByteString

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.network.p2p.messages.SNAP._
import com.chipprbots.ethereum.network.p2p.messages.SNAP.AccountRange.AccountRangeEnc
import com.chipprbots.ethereum.network.p2p.messages.SNAP.AccountRange.AccountRangeDec
import com.chipprbots.ethereum.network.p2p.messages.SNAP.ByteCodes.ByteCodesEnc
import com.chipprbots.ethereum.network.p2p.messages.SNAP.ByteCodes.ByteCodesDec
import com.chipprbots.ethereum.network.p2p.messages.SNAP.GetAccountRange.GetAccountRangeEnc
import com.chipprbots.ethereum.network.p2p.messages.SNAP.GetAccountRange.GetAccountRangeDec
import com.chipprbots.ethereum.network.p2p.messages.SNAP.GetByteCodes.GetByteCodesEnc
import com.chipprbots.ethereum.network.p2p.messages.SNAP.GetByteCodes.GetByteCodesDec
import com.chipprbots.ethereum.network.p2p.messages.SNAP.GetStorageRanges.GetStorageRangesEnc
import com.chipprbots.ethereum.network.p2p.messages.SNAP.GetStorageRanges.GetStorageRangesDec
import com.chipprbots.ethereum.network.p2p.messages.SNAP.GetTrieNodes.GetTrieNodesEnc
import com.chipprbots.ethereum.network.p2p.messages.SNAP.GetTrieNodes.GetTrieNodesDec
import com.chipprbots.ethereum.network.p2p.messages.SNAP.StorageRanges.StorageRangesEnc
import com.chipprbots.ethereum.network.p2p.messages.SNAP.StorageRanges.StorageRangesDec
import com.chipprbots.ethereum.network.p2p.messages.SNAP.TrieNodes.TrieNodesEnc
import com.chipprbots.ethereum.network.p2p.messages.SNAP.TrieNodes.TrieNodesDec
import com.chipprbots.ethereum.rlp.{RLPList, RLPValue, rawDecode}

class SNAPMessagesSpec extends AnyWordSpec with Matchers {

  private val zeroHash = ByteString(new Array[Byte](32))
  private val maxHash  = ByteString(Array.fill(32)(0xff.toByte))
  private val rootHash = ByteString(Array.fill(32)(0xca.toByte))

  "SNAP/1" should {

    "encode GetAccountRange requestId canonically (no leading zeros)" in {
      val msg = GetAccountRange(
        requestId = BigInt(128),
        rootHash = ByteString(Array.fill(32)(0x11.toByte)),
        startingHash = ByteString(Array.fill(32)(0x22.toByte)),
        limitHash = ByteString(Array.fill(32)(0x33.toByte)),
        responseBytes = BigInt(1024)
      )

      val encoded = msg.toBytes

      rawDecode(encoded) match {
        case RLPList(RLPValue(requestIdBytes), _*) =>
          requestIdBytes shouldBe Array(0x80.toByte)
        case _ => fail("Expected RLPList with request-id as first element")
      }
    }

    "encode GetAccountRange requestId=0 as empty bytes per RLP spec" in {
      val msg = GetAccountRange(
        requestId = BigInt(0),
        rootHash = ByteString(Array.fill(32)(0x11.toByte)),
        startingHash = ByteString(Array.fill(32)(0x22.toByte)),
        limitHash = ByteString(Array.fill(32)(0x33.toByte)),
        responseBytes = BigInt(1024)
      )

      val encoded = msg.toBytes

      rawDecode(encoded) match {
        case RLPList(RLPValue(requestIdBytes), _*) =>
          requestIdBytes shouldBe empty
        case _ => fail("Expected RLPList with request-id as first element")
      }
    }

    "round-trip GetAccountRange with all fields preserved" in {
      val orig = GetAccountRange(
        requestId = BigInt(42),
        rootHash = rootHash,
        startingHash = zeroHash,
        limitHash = maxHash,
        responseBytes = BigInt(512 * 1024)
      )

      val decoded = orig.toBytes.toGetAccountRange

      decoded.requestId    shouldBe orig.requestId
      decoded.rootHash     shouldBe orig.rootHash
      decoded.startingHash shouldBe orig.startingHash
      decoded.limitHash    shouldBe orig.limitHash
      decoded.responseBytes shouldBe orig.responseBytes
    }

    "round-trip AccountRange with empty accounts and empty proof" in {
      val orig = AccountRange(requestId = BigInt(1), accounts = Seq.empty, proof = Seq.empty)

      val decoded = orig.toBytes.toAccountRange

      decoded.requestId shouldBe orig.requestId
      decoded.accounts  shouldBe empty
      decoded.proof     shouldBe empty
    }

    "round-trip AccountRange with one EOA account" in {
      val accountHash = ByteString(Array.fill(32)(0xab.toByte))
      val eoa         = Account.empty()
      val proofNode   = ByteString(Array.fill(32)(0xde.toByte))
      val orig = AccountRange(
        requestId = BigInt(7),
        accounts  = Seq((accountHash, eoa)),
        proof     = Seq(proofNode)
      )

      val decoded = orig.toBytes.toAccountRange

      decoded.requestId        shouldBe orig.requestId
      decoded.accounts.size    shouldBe 1
      decoded.accounts.head._1 shouldBe accountHash
      decoded.accounts.head._2 shouldBe eoa
      decoded.proof            shouldBe Seq(proofNode)
    }

    "round-trip GetStorageRanges with multiple account hashes" in {
      val hash1 = ByteString(Array.fill(32)(0x01.toByte))
      val hash2 = ByteString(Array.fill(32)(0x02.toByte))
      val orig = GetStorageRanges(
        requestId    = BigInt(99),
        rootHash     = rootHash,
        accountHashes = Seq(hash1, hash2),
        startingHash = zeroHash,
        limitHash    = maxHash,
        responseBytes = BigInt(2 * 1024 * 1024)
      )

      val decoded = orig.toBytes.toGetStorageRanges

      decoded.requestId     shouldBe orig.requestId
      decoded.rootHash      shouldBe orig.rootHash
      decoded.accountHashes shouldBe Seq(hash1, hash2)
      decoded.startingHash  shouldBe orig.startingHash
      decoded.limitHash     shouldBe orig.limitHash
      decoded.responseBytes shouldBe orig.responseBytes
    }

    "round-trip StorageRanges with empty slots and empty proof" in {
      val orig = StorageRanges(requestId = BigInt(3), slots = Seq.empty, proof = Seq.empty)

      val decoded = orig.toBytes.toStorageRanges

      decoded.requestId shouldBe orig.requestId
      decoded.slots     shouldBe empty
      decoded.proof     shouldBe empty
    }

    "round-trip StorageRanges with one account having two storage slots" in {
      val slotHash1  = ByteString(Array.fill(32)(0x10.toByte))
      val slotValue1 = ByteString(Array.fill(32)(0x11.toByte))
      val slotHash2  = ByteString(Array.fill(32)(0x20.toByte))
      val slotValue2 = ByteString(Array.fill(32)(0x21.toByte))
      val proofNode  = ByteString(Array.fill(32)(0xff.toByte))
      val orig = StorageRanges(
        requestId = BigInt(5),
        slots     = Seq(Seq((slotHash1, slotValue1), (slotHash2, slotValue2))),
        proof     = Seq(proofNode)
      )

      val decoded = orig.toBytes.toStorageRanges

      decoded.requestId    shouldBe orig.requestId
      decoded.slots.size   shouldBe 1
      decoded.slots.head   shouldBe Seq((slotHash1, slotValue1), (slotHash2, slotValue2))
      decoded.proof        shouldBe Seq(proofNode)
    }

    "round-trip GetByteCodes with multiple code hashes" in {
      val h1 = ByteString(Array.fill(32)(0xc1.toByte))
      val h2 = ByteString(Array.fill(32)(0xc2.toByte))
      val orig = GetByteCodes(requestId = BigInt(11), hashes = Seq(h1, h2), responseBytes = BigInt(1024 * 1024))

      val decoded = orig.toBytes.toGetByteCodes

      decoded.requestId     shouldBe orig.requestId
      decoded.hashes        shouldBe Seq(h1, h2)
      decoded.responseBytes shouldBe orig.responseBytes
    }

    "round-trip ByteCodes with actual bytecodes" in {
      val code1 = ByteString("PUSH1 0x60 PUSH1 0x40 MSTORE")
      val code2 = ByteString("RETURN")
      val orig  = ByteCodes(requestId = BigInt(22), codes = Seq(code1, code2))

      val decoded = orig.toBytes.toByteCodes

      decoded.requestId shouldBe orig.requestId
      decoded.codes     shouldBe Seq(code1, code2)
    }

    "round-trip ByteCodes with empty codes list" in {
      val orig = ByteCodes(requestId = BigInt(0), codes = Seq.empty)

      val decoded = orig.toBytes.toByteCodes

      decoded.requestId shouldBe orig.requestId
      decoded.codes     shouldBe empty
    }

    "round-trip GetTrieNodes with multi-level paths" in {
      val path1Level1 = ByteString(Array[Byte](0x0a, 0x0b))
      val path1Level2 = ByteString(Array[Byte](0x0c, 0x0d))
      val path2Level1 = ByteString(Array[Byte](0x0e))
      val orig = GetTrieNodes(
        requestId     = BigInt(33),
        rootHash      = rootHash,
        paths         = Seq(Seq(path1Level1, path1Level2), Seq(path2Level1)),
        responseBytes = BigInt(512 * 1024)
      )

      val decoded = orig.toBytes.toGetTrieNodes

      decoded.requestId     shouldBe orig.requestId
      decoded.rootHash      shouldBe orig.rootHash
      decoded.paths.size    shouldBe 2
      decoded.paths(0)      shouldBe Seq(path1Level1, path1Level2)
      decoded.paths(1)      shouldBe Seq(path2Level1)
      decoded.responseBytes shouldBe orig.responseBytes
    }

    "round-trip TrieNodes with actual node data" in {
      val node1 = ByteString(Array.fill(32)(0xaa.toByte))
      val node2 = ByteString(Array.fill(32)(0xbb.toByte))
      val orig  = TrieNodes(requestId = BigInt(44), nodes = Seq(node1, node2))

      val decoded = orig.toBytes.toTrieNodes

      decoded.requestId shouldBe orig.requestId
      decoded.nodes     shouldBe Seq(node1, node2)
    }

    "round-trip TrieNodes with empty nodes" in {
      val orig = TrieNodes(requestId = BigInt(55), nodes = Seq.empty)

      val decoded = orig.toBytes.toTrieNodes

      decoded.requestId shouldBe orig.requestId
      decoded.nodes     shouldBe empty
    }

    "assign message codes in canonical 0x30-0x37 range" in {
      Codes.GetAccountRangeCode shouldBe 0x30
      Codes.AccountRangeCode    shouldBe 0x31
      Codes.GetStorageRangesCode shouldBe 0x32
      Codes.StorageRangesCode   shouldBe 0x33
      Codes.GetByteCodesCode    shouldBe 0x34
      Codes.ByteCodesCode       shouldBe 0x35
      Codes.GetTrieNodesCode    shouldBe 0x36
      Codes.TrieNodesCode       shouldBe 0x37
    }

    "match message.code to protocol code for all 8 message types" in {
      GetAccountRange(BigInt(0), zeroHash, zeroHash, maxHash, BigInt(0)).code shouldBe Codes.GetAccountRangeCode
      AccountRange(BigInt(0), Seq.empty, Seq.empty).code                      shouldBe Codes.AccountRangeCode
      GetStorageRanges(BigInt(0), zeroHash, Seq.empty, zeroHash, maxHash, BigInt(0)).code shouldBe Codes.GetStorageRangesCode
      StorageRanges(BigInt(0), Seq.empty, Seq.empty).code                     shouldBe Codes.StorageRangesCode
      GetByteCodes(BigInt(0), Seq.empty, BigInt(0)).code                      shouldBe Codes.GetByteCodesCode
      ByteCodes(BigInt(0), Seq.empty).code                                    shouldBe Codes.ByteCodesCode
      GetTrieNodes(BigInt(0), zeroHash, Seq.empty, BigInt(0)).code            shouldBe Codes.GetTrieNodesCode
      TrieNodes(BigInt(0), Seq.empty).code                                    shouldBe Codes.TrieNodesCode
    }

    "throw on malformed RLP — GetAccountRange with wrong field count" in {
      val malformed = com.chipprbots.ethereum.rlp.encode(RLPList(RLPValue(Array[Byte](0x01)), RLPValue(Array[Byte](0x02))))
      an[Exception] should be thrownBy malformed.toGetAccountRange
    }

    "throw on malformed RLP — GetStorageRanges with wrong field count" in {
      val malformed = com.chipprbots.ethereum.rlp.encode(RLPList(RLPValue(Array[Byte](0x01))))
      an[Exception] should be thrownBy malformed.toGetStorageRanges
    }
  }
}
