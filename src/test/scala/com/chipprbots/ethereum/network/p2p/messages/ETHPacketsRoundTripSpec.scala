package com.chipprbots.ethereum.network.p2p.messages

import org.apache.pekko.util.ByteString

import org.scalacheck.Gen
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

import com.chipprbots.ethereum.ObjectGenerators
import com.chipprbots.ethereum.forkid.ForkId
import com.chipprbots.ethereum.rlp.RLPList
import com.chipprbots.ethereum.testing.Tags.*

class ETHPacketsRoundTripSpec extends AnyWordSpec with Matchers with ScalaCheckPropertyChecks with ObjectGenerators:

  private val hash32Gen: Gen[ByteString] = byteStringOfLengthNGen(32)
  private val reqIdGen: Gen[BigInt] = Gen.choose(0L, Long.MaxValue).map(BigInt(_))
  private val blockNumGen: Gen[BigInt] = Gen.choose(0L, Long.MaxValue).map(BigInt(_))
  private val netIdGen: Gen[Long] = Gen.choose(1L, 1000L)
  private val protoVerGen: Gen[Int] = Gen.choose(68, 70)
  private val forkIdGen: Gen[ForkId] = for
    hash <- Gen.choose(0L, 0xffffffffL)
    next <- Gen.option(Gen.choose(1_000_000L, 50_000_000L).map(BigInt(_)))
  yield ForkId(BigInt(hash), next)

  "ETHPackets codec" when {

    "Status68" should {
      "round-trip forAll" taggedAs UnitTest in {
        import ETHPackets.Status68.Status68
        import ETHPackets.Status68.Status68.*
        val gen = for
          pv <- protoVerGen
          net <- netIdGen
          td <- bigIntGen
          bh <- hash32Gen
          gh <- hash32Gen
          fi <- forkIdGen
        yield Status68(pv, net, td, bh, gh, fi)
        forAll(gen)(msg => msg.toBytes.toStatus68 shouldBe msg)
      }
    }

    "Status69" should {
      "round-trip forAll" taggedAs UnitTest in {
        import ETHPackets.Status69.Status69
        import ETHPackets.Status69.Status69.*
        val gen = for
          pv <- protoVerGen
          net <- netIdGen
          gh <- hash32Gen
          fi <- forkIdGen
          earliest <- blockNumGen
          latest <- blockNumGen
          lh <- hash32Gen
        yield Status69(pv, net, gh, fi, earliest, latest, lh)
        forAll(gen)(msg => msg.toBytes.toStatus69 shouldBe msg)
      }
    }

    "Status70" should {
      "round-trip forAll" taggedAs UnitTest in {
        import ETHPackets.Status70.Status70
        import ETHPackets.Status70.Status70.*
        val gen = for
          pv <- protoVerGen
          net <- netIdGen
          gh <- hash32Gen
          fi <- forkIdGen
          earliest <- blockNumGen
          latest <- blockNumGen
          lh <- hash32Gen
        yield Status70(pv, net, gh, fi, earliest, latest, lh)
        forAll(gen)(msg => msg.toBytes.toStatus70 shouldBe msg)
      }
    }

    "NewBlockHashes" should {
      "round-trip forAll" taggedAs UnitTest in {
        import ETHPackets.NewBlockHashes.{BlockHash, NewBlockHashes}
        import ETHPackets.NewBlockHashes.NewBlockHashes.*
        val blockHashGen = for
          h <- hash32Gen
          n <- blockNumGen
        yield BlockHash(h, n)
        forAll(Gen.listOf(blockHashGen)) { hashes =>
          NewBlockHashes(hashes).toBytes.toNewBlockHashes shouldBe NewBlockHashes(hashes)
        }
      }
    }

    "GetBlockHeaders by block number" should {
      "round-trip forAll" taggedAs UnitTest in {
        import ETHPackets.GetBlockHeaders.*
        val gen = for
          reqId <- reqIdGen
          blockNum <- blockNumGen
          maxH <- Gen.choose(1L, 1024L).map(BigInt(_))
          skip <- Gen.choose(0L, 100L).map(BigInt(_))
          reverse <- Gen.oneOf(true, false)
        yield ETHPackets.GetBlockHeaders(reqId, Left(blockNum), maxH, skip, reverse)
        forAll(gen)(msg => msg.toBytes.toGetBlockHeaders shouldBe msg)
      }
    }

    "GetBlockHeaders by block hash" should {
      "round-trip forAll" taggedAs UnitTest in {
        import ETHPackets.GetBlockHeaders.*
        val gen = for
          reqId <- reqIdGen
          hash <- hash32Gen
          maxH <- Gen.choose(1L, 1024L).map(BigInt(_))
          skip <- Gen.choose(0L, 100L).map(BigInt(_))
          reverse <- Gen.oneOf(true, false)
        yield ETHPackets.GetBlockHeaders(reqId, Right(hash), maxH, skip, reverse)
        forAll(gen)(msg => msg.toBytes.toGetBlockHeaders shouldBe msg)
      }
    }

    "BlockHeaders" should {
      "round-trip requestId with empty header list forAll" taggedAs UnitTest in {
        import ETHPackets.BlockHeaders.*
        forAll(reqIdGen) { reqId =>
          val msg = ETHPackets.BlockHeaders(reqId, Seq.empty)
          msg.toBytes.toBlockHeaders shouldBe msg
        }
      }
    }

    "GetBlockBodies" should {
      "round-trip forAll" taggedAs UnitTest in {
        import ETHPackets.GetBlockBodies.*
        forAll(reqIdGen, Gen.listOf(hash32Gen)) { (reqId, hashes) =>
          val msg = ETHPackets.GetBlockBodies(reqId, hashes)
          msg.toBytes.toGetBlockBodies shouldBe msg
        }
      }
    }

    "BlockBodies" should {
      "round-trip requestId with empty body list forAll" taggedAs UnitTest in {
        import ETHPackets.BlockBodies.*
        forAll(reqIdGen) { reqId =>
          val msg = ETHPackets.BlockBodies(reqId, Seq.empty)
          msg.toBytes.toBlockBodies shouldBe msg
        }
      }
    }

    "NewPooledTransactionHashes" should {
      "round-trip forAll" taggedAs UnitTest in {
        import ETHPackets.NewPooledTransactionHashes.*
        val gen = for
          n <- Gen.choose(0, 20)
          types <- Gen.listOfN(n, Gen.choose(0, 4).map(_.toByte))
          sizes <- Gen.listOfN(n, Gen.choose(0L, 1_000_000L).map(BigInt(_)))
          hashes <- Gen.listOfN(n, hash32Gen)
        yield (types, sizes, hashes)
        forAll(gen) { case (types, sizes, hashes) =>
          val msg = ETHPackets.NewPooledTransactionHashes(types, sizes, hashes)
          msg.toBytes.toNewPooledTransactionHashes shouldBe msg
        }
      }
    }

    "GetPooledTransactions" should {
      "round-trip forAll" taggedAs UnitTest in {
        import ETHPackets.GetPooledTransactions.*
        forAll(reqIdGen, Gen.listOf(hash32Gen)) { (reqId, hashes) =>
          val msg = ETHPackets.GetPooledTransactions(reqId, hashes)
          msg.toBytes.toGetPooledTransactions shouldBe msg
        }
      }
    }

    "GetReceipts" should {
      "round-trip forAll" taggedAs UnitTest in {
        import ETHPackets.GetReceipts.*
        forAll(reqIdGen, Gen.listOf(hash32Gen)) { (reqId, hashes) =>
          val msg = ETHPackets.GetReceipts(reqId, hashes)
          msg.toBytes.toGetReceipts shouldBe msg
        }
      }
    }

    "GetReceipts69" should {
      "round-trip forAll" taggedAs UnitTest in {
        import ETHPackets.GetReceipts69.*
        forAll(reqIdGen, Gen.listOf(hash32Gen)) { (reqId, hashes) =>
          val msg = ETHPackets.GetReceipts69(reqId, hashes)
          msg.toBytes.toGetReceipts69 shouldBe msg
        }
      }
    }

    "GetReceipts70" should {
      "round-trip forAll" taggedAs UnitTest in {
        import ETHPackets.GetReceipts70.*
        forAll(reqIdGen, Gen.choose(0L, 1000L), Gen.listOf(hash32Gen)) { (reqId, firstIdx, hashes) =>
          val msg = ETHPackets.GetReceipts70(reqId, firstIdx, hashes)
          msg.toBytes.toGetReceipts70 shouldBe msg
        }
      }
    }

    "BlockRangeUpdate" should {
      "round-trip forAll" taggedAs UnitTest in {
        import ETHPackets.BlockRangeUpdate.*
        forAll(blockNumGen, blockNumGen, hash32Gen) { (earliest, latest, hash) =>
          val msg = ETHPackets.BlockRangeUpdate(earliest, latest, hash)
          msg.toBytes.toBlockRangeUpdate shouldBe msg
        }
      }
    }

    "Receipts68" should {
      "round-trip requestId with empty receipts forAll" taggedAs UnitTest in {
        import ETHPackets.Receipts68.*
        forAll(reqIdGen) { reqId =>
          val msg = ETHPackets.Receipts68(reqId, RLPList())
          msg.toBytes.toReceipts68.requestId shouldBe reqId
        }
      }
    }

    "Receipts69" should {
      "round-trip requestId with empty receipts forAll" taggedAs UnitTest in {
        import ETHPackets.Receipts69.*
        forAll(reqIdGen) { reqId =>
          val msg = ETHPackets.Receipts69(reqId, RLPList())
          msg.toBytes.toReceipts69.requestId shouldBe reqId
        }
      }
    }

    "Receipts70" should {
      "round-trip requestId and lastBlockIncomplete with empty receipts forAll" taggedAs UnitTest in {
        import ETHPackets.Receipts70.*
        forAll(reqIdGen, Gen.oneOf(true, false)) { (reqId, incomplete) =>
          val msg = ETHPackets.Receipts70(reqId, incomplete, RLPList())
          val result = msg.toBytes.toReceipts70
          result.requestId shouldBe reqId
          result.lastBlockIncomplete shouldBe incomplete
        }
      }
    }
  }
