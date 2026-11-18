package com.chipprbots.ethereum.blockchain.checkpoint

import org.apache.pekko.util.ByteString

import org.scalatest.EitherValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.chipprbots.ethereum.blockchain.checkpoint.CheckpointImporter.Trust
import com.chipprbots.ethereum.crypto
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.domain.BlockBody
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.CodeHash
import com.chipprbots.ethereum.domain.TrieRoot
import com.chipprbots.ethereum.domain.UInt256
import com.chipprbots.ethereum.mpt.HexPrefix
import com.chipprbots.ethereum.testing.Tags.UnitTest

/** Export then import (spec 018 T222): the imported state is the exported one, root for root, and the importer places a
  * node at every position the state uses it: one row per (owner, path), with no dedup by hash. A storage trie two
  * contracts hold is placed under each, and a slot-0 leaf that many storage tries share is placed in each. The owner of
  * a storage row is its account leaf's 64-nibble key, keccak of the address, and not keccak of that.
  *
  * Both chain shapes: Mordor's (proof of work, chain id 63, ommer bodies in the window) and Sepolia's (a TTD, proof of
  * work below the merge and proof of stake above it, chain id 11155111, a window of headers only). And goldens A and B.
  */
class CheckpointV2RoundTripSpec extends AnyWordSpec with Matchers with EitherValues:
  import CheckpointV2Fixtures.*
  import CheckpointV2ImportKit.*

  private val CodeA = ByteString(Array[Byte](0x60, 0x0a, 0x00))
  private val CodeB = ByteString(Array[Byte](0x60, 0x0b, 0x00))

  private def firstNibble(slot: BigInt): Int = (slotKey(slot)(0) >> 4) & 0xf

  /** A seeded state, its contracts' account keys by group: four contracts sharing one storage trie, six whose tries
    * differ but share slot 0's leaf, four with storage of their own, and plain accounts.
    */
  final private case class Seeded(root: ByteString, twins: Seq[ByteString], slotZeroSharers: Seq[ByteString])

  private def seed(chain: Chain, seed: Long): Seeded =
    val rnd = new scala.util.Random(seed)
    def address(): Array[Byte] = Array.fill[Byte](20)(rnd.nextInt().toByte)
    val shared = chain.storageTrie(Seq(BigInt(0), BigInt(1), BigInt(2)))
    // Slot 0 and one slot whose key starts with another nibble: a branch with two leaves, slot 0's the same in each.
    val partners = Iterator.from(3).map(BigInt(_)).filter(s => firstNibble(s) != firstNibble(0)).take(6).toSeq
    def contract(storage: ByteString, code: ByteString) =
      Account(UInt256(1), UInt256(rnd.nextInt(1000)), TrieRoot(storage), CodeHash(crypto.kec256(code)))
    val twins = Seq.fill(4)(address() -> contract(shared, CodeA))
    val sharers = partners.map(p => address() -> contract(chain.storageTrie(Seq(BigInt(0), p)), CodeB))
    val own = Seq.fill(4)(address() -> contract(chain.storageTrie(Seq.fill(5)(BigInt(rnd.nextInt(1 << 20)))), CodeA))
    val plain = Seq.fill(12)(address() -> Account(UInt256(rnd.nextInt(9)), UInt256(rnd.nextInt(1 << 30))))
    val root = chain.accountTrie(twins ++ sharers ++ own ++ plain, Seq(CodeA, CodeB))
    def keys(as: Seq[(Array[Byte], Account)]) = as.map(a => ByteString(crypto.kec256(a._1)))
    Seeded(root, keys(twins), keys(sharers))

  /** Exports `tip` from `source`, imports it into a fresh datadir through a recording sink, and checks the round trip:
    * every position placed once, the state the source's, and a second export from the target equal byte for byte.
    */
  private def roundTrip(
      source: Chain,
      tip: BlockHeader,
      exporter: Chain => CheckpointExporter,
      trust: Trust
  ): Map[Option[ByteString], com.chipprbots.ethereum.mpt.PositionTestKit.Rows] =
    val bytes = source.exportBytes(exporter(source), tip.number.value).value
    val target = new Chain
    var sink: Option[RecordingSink] = None
    val result =
      importInto(
        target,
        bytes,
        trust,
        sink = Some { n =>
          val s = new RecordingSink(target, n); sink = Some(s); s
        }
      ).value
    val placed = sink.get
    val root = tip.stateRoot.value
    val expected = positionsOf(source, root)
    withClue("every stored position of the state, each placed once: ") {
      placed.rows shouldBe expected
      placed.placed.size shouldBe expected.values.map(_.size).sum
      result.nodesImported shouldBe placed.placed.size
    }
    withClue("the target holds the same state, root for root: ")(positionsOf(target, root) shouldBe expected)
    withClue("and exports it again, byte for byte: ")(
      target.exportBytes(exporter(target), tip.number.value).value shouldBe bytes
    )
    target.storages.storages.appStateStorage.getBestBlockNumber() shouldBe tip.number.value
    placed.rows

  private def checkSharing(rows: Map[Option[ByteString], com.chipprbots.ethereum.mpt.PositionTestKit.Rows], s: Seeded) =
    withClue("the owners are the account keys, keccak of each address, not keccak of that: ") {
      rows.keySet.flatten should contain allElementsOf (s.twins ++ s.slotZeroSharers)
      rows.keySet.flatten.map(o => ByteString(crypto.kec256(o.toArray))).intersect(rows.keySet.flatten) shouldBe empty
    }
    withClue("the storage trie four contracts share is placed under each of them, whole: ") {
      s.twins.map(t => rows(Some(t))).distinct.size shouldBe 1
      rows(Some(s.twins.head)).size should be > 1
    }
    withClue("slot 0's leaf, shared across six different tries, is placed in each: ") {
      val at = ByteString(HexPrefix.encode(Array(firstNibble(0).toByte), isLeaf = false))
      val leaves = s.slotZeroSharers.map(o => rows(Some(o))(at))
      leaves.distinct.size shouldBe 1
      s.slotZeroSharers
        .map(o => rows(Some(o))(ByteString(HexPrefix.encode(Array.emptyByteArray, isLeaf = false))))
        .distinct
        .size shouldBe s.slotZeroSharers.size
    }

  "Export then import" should {

    "give the same state on a Mordor-shaped chain, with ommer bodies in its window" taggedAs UnitTest in {
      val source = new Chain
      val s = seed(source, 63L)
      val ommers = Map(7 -> ommerAt(7), 10 -> ommerAt(10))
      val headers = (0 to 10).map(n => powHeader(n, ommers.get(n).toSeq, if n == 10 then s.root else EmptyTrieRoot))
      val tip = source.store(headers, ommers.map((n, o) => BigInt(n) -> BlockBody(Nil, Seq(o)))).last
      val rows = roundTrip(source, tip, _.exporter(63), trustOf(source, 63, tip))
      checkSharing(rows, s)
    }

    "give the same state on a Sepolia-shaped chain, merged above its genesis, with a window of headers only" taggedAs UnitTest in {
      val source = new Chain
      val s = seed(source, 11155111L)
      val pow = (0 to 5).map(n => powHeader(n, if n == 4 then Seq(ommerAt(4)) else Nil))
      val pos = (6 to 8).map(n => posHeader(n, if n == 8 then s.root else EmptyTrieRoot))
      val ttd = pow.map(_.difficulty.value).sum
      val tip = source.store(pow ++ pos, Map(BigInt(4) -> BlockBody(Nil, Seq(ommerAt(4))))).last
      ttd should be > source.reader.genesisHeader.difficulty.value
      val exporter = (c: Chain) => c.exporter(11155111, terminalTotalDifficulty = Some(ttd), finalized = Some(8))
      val rows = roundTrip(source, tip, exporter, trustOf(source, 11155111, tip, Some(ttd)))
      checkSharing(rows, s)
    }

    "import golden A and golden B to the states they were exported from" taggedAs UnitTest in {
      val (chainA, tipA) = streamAChain()
      val (chainB, tipB) = streamBChain()
      Seq(
        ("a", chainA, tipA, trustOf(chainA, 61, tipA), (c: Chain) => c.exporter(61)),
        (
          "b",
          chainB,
          tipB,
          trustOf(chainB, StreamBChainId, tipB, Some(0)),
          (c: Chain) => c.exporter(StreamBChainId, terminalTotalDifficulty = Some(0), finalized = Some(2))
        )
      ).foreach { (name, source, tip, trust, exporter) =>
        withClue(s"golden $name: ") {
          val golden = CheckpointV2GoldenStreamSpec.golden(name)
          val target = new Chain
          importInto(target, golden, trust).value
          positionsOf(target, tip.stateRoot.value) shouldBe positionsOf(source, tip.stateRoot.value)
          target.exportBytes(exporter(target), tip.number.value).value shouldBe golden
        }
      }
    }
  }
