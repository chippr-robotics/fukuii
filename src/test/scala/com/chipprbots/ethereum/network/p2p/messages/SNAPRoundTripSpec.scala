package com.chipprbots.ethereum.network.p2p.messages

import org.apache.pekko.util.ByteString

import org.scalacheck.Gen
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

import com.chipprbots.ethereum.ObjectGenerators
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.domain.UInt256
import com.chipprbots.ethereum.network.p2p.messages.SNAP.AccountRange.*
import com.chipprbots.ethereum.network.p2p.messages.SNAP.ByteCodes.*
import com.chipprbots.ethereum.network.p2p.messages.SNAP.GetAccountRange.*
import com.chipprbots.ethereum.network.p2p.messages.SNAP.GetByteCodes.*
import com.chipprbots.ethereum.network.p2p.messages.SNAP.GetStorageRanges.*
import com.chipprbots.ethereum.network.p2p.messages.SNAP.GetTrieNodes.*
import com.chipprbots.ethereum.network.p2p.messages.SNAP.StorageRanges.*
import com.chipprbots.ethereum.network.p2p.messages.SNAP.TrieNodes.*
import com.chipprbots.ethereum.network.p2p.messages.SNAP.*
import com.chipprbots.ethereum.testing.Tags.*

class SNAPRoundTripSpec extends AnyWordSpec with Matchers with ScalaCheckPropertyChecks with ObjectGenerators:

  private val hash32Gen: Gen[ByteString] = byteStringOfLengthNGen(32)
  private val reqIdGen: Gen[BigInt] = Gen.choose(0L, Long.MaxValue).map(BigInt(_))
  private val bytesLimitGen: Gen[BigInt] = Gen.choose(0L, 10_000_000L).map(BigInt(_))
  private val boundaryHashGen: Gen[ByteString] = Gen.frequency(
    1 -> Gen.const(ByteString(Array.fill[Byte](32)(0x00.toByte))),
    1 -> Gen.const(ByteString(Array.fill[Byte](32)(0xff.toByte))),
    3 -> byteStringOfLengthNGen(32)
  )
  private val accountGen: Gen[Account] = for
    nonce <- Gen.choose(0L, 1_000_000L).map(n => UInt256(BigInt(n)))
    balance <- Gen.choose(0L, Long.MaxValue).map(n => UInt256(BigInt(n)))
  yield Account(nonce = nonce, balance = balance)

  "SNAP codec" when {

    "GetAccountRange" should {
      "round-trip forAll including boundary starting/limit hashes" taggedAs UnitTest in {
        val gen = for
          reqId <- reqIdGen
          root <- hash32Gen
          startingHash <- boundaryHashGen
          limitHash <- boundaryHashGen
          responseBytes <- bytesLimitGen
        yield GetAccountRange(reqId, root, startingHash, limitHash, responseBytes)
        forAll(gen)(msg => msg.toBytes.toGetAccountRange shouldBe msg)
      }
    }

    "AccountRange" should {
      "round-trip forAll with varied account count and proof nodes" taggedAs UnitTest in {
        val gen = for
          reqId <- reqIdGen
          n <- Gen.choose(0, 10)
          hashes <- Gen.listOfN(n, hash32Gen)
          accounts <- Gen.listOfN(n, accountGen)
          proofNodes <- Gen.listOf(randomSizeByteStringGen(1, 64))
        yield AccountRange(reqId, hashes.zip(accounts), proofNodes)
        forAll(gen)(msg => msg.toBytes.toAccountRange shouldBe msg)
      }
    }

    "GetStorageRanges" should {
      "round-trip forAll including empty and multi-account hash lists" taggedAs UnitTest in {
        val gen = for
          reqId <- reqIdGen
          root <- hash32Gen
          n <- Gen.choose(0, 10)
          accountHashes <- Gen.listOfN(n, hash32Gen)
          startingHash <- boundaryHashGen
          limitHash <- boundaryHashGen
          responseBytes <- bytesLimitGen
        yield GetStorageRanges(reqId, root, accountHashes, startingHash, limitHash, responseBytes)
        forAll(gen)(msg => msg.toBytes.toGetStorageRanges shouldBe msg)
      }
    }

    "StorageRanges" should {
      "round-trip forAll with varied slot sets and proof nodes" taggedAs UnitTest in {
        val slotGen = for
          h <- hash32Gen
          v <- byteStringOfLengthNGen(32)
        yield (h, v)
        val gen = for
          reqId <- reqIdGen
          n <- Gen.choose(0, 5)
          slotSets <- Gen.listOfN(n, Gen.listOf(slotGen))
          proofNodes <- Gen.listOf(randomSizeByteStringGen(1, 64))
        yield StorageRanges(reqId, slotSets, proofNodes)
        forAll(gen)(msg => msg.toBytes.toStorageRanges shouldBe msg)
      }
    }

    "GetByteCodes" should {
      "round-trip forAll with empty and non-empty hash lists" taggedAs UnitTest in {
        val gen = for
          reqId <- reqIdGen
          hashes <- Gen.listOf(hash32Gen)
          responseBytes <- bytesLimitGen
        yield GetByteCodes(reqId, hashes, responseBytes)
        forAll(gen)(msg => msg.toBytes.toGetByteCodes shouldBe msg)
      }
    }

    "ByteCodes" should {
      "round-trip forAll with varied bytecode lengths" taggedAs UnitTest in {
        val gen = for
          reqId <- reqIdGen
          codes <- Gen.listOf(randomSizeByteStringGen(0, 512))
        yield ByteCodes(reqId, codes)
        forAll(gen)(msg => msg.toBytes.toByteCodes shouldBe msg)
      }
    }

    "GetTrieNodes" should {
      "round-trip forAll with varied path depth" taggedAs UnitTest in {
        val nodeHashGen = randomSizeByteStringGen(1, 32)
        val gen = for
          reqId <- reqIdGen
          root <- hash32Gen
          n <- Gen.choose(0, 5)
          paths <- Gen.listOfN(n, Gen.listOf(nodeHashGen))
          responseBytes <- bytesLimitGen
        yield GetTrieNodes(reqId, root, paths, responseBytes)
        forAll(gen)(msg => msg.toBytes.toGetTrieNodes shouldBe msg)
      }
    }

    "decoders" should {
      "produce Vectors, so indexed access into a response is not O(i) (#1672)" taggedAs UnitTest in {
        val hash = ByteString(Array.fill(32)(1.toByte))
        val accountRange = AccountRange(1, Seq(hash -> Account(nonce = 1)), Seq(ByteString("p"))).toBytes.toAccountRange
        accountRange.accounts shouldBe a[Vector[?]]
        accountRange.proof shouldBe a[Vector[?]]
        val storage = StorageRanges(2, Seq(Seq(hash -> ByteString("v"))), Seq(ByteString("p"))).toBytes.toStorageRanges
        storage.slots shouldBe a[Vector[?]]
        storage.slots.head shouldBe a[Vector[?]]
        storage.proof shouldBe a[Vector[?]]
        ByteCodes(3, Seq(ByteString("c"))).toBytes.toByteCodes.codes shouldBe a[Vector[?]]
        TrieNodes(4, Seq(ByteString("n"))).toBytes.toTrieNodes.nodes shouldBe a[Vector[?]]
        val getTrieNodes = GetTrieNodes(5, hash, Seq(Seq(ByteString("a"))), 100).toBytes.toGetTrieNodes
        getTrieNodes.paths shouldBe a[Vector[?]]
        getTrieNodes.paths.head shouldBe a[Vector[?]]
      }
    }

    "TrieNodes" should {
      "round-trip forAll with varied node sizes" taggedAs UnitTest in {
        val gen = for
          reqId <- reqIdGen
          nodes <- Gen.listOf(randomSizeByteStringGen(1, 128))
        yield TrieNodes(reqId, nodes)
        forAll(gen)(msg => msg.toBytes.toTrieNodes shouldBe msg)
      }
    }
  }
