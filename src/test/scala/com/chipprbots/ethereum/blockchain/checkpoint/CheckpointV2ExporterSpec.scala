package com.chipprbots.ethereum.blockchain.checkpoint

import java.nio.file.Files

import org.apache.pekko.util.ByteString

import org.bouncycastle.util.encoders.Hex
import org.scalatest.EitherValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.chipprbots.ethereum.crypto
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.domain.BlockBody
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.CodeHash
import com.chipprbots.ethereum.domain.TrieRoot
import com.chipprbots.ethereum.domain.UInt256
import com.chipprbots.ethereum.mpt.PositionTestKit.RawTrieWalk
import com.chipprbots.ethereum.testing.Tags.UnitTest

/** The exporter's rules for format version 2 (spec 018 T289, data-model E.1). */
class CheckpointV2ExporterSpec extends AnyWordSpec with Matchers with EitherValues:
  import CheckpointV2Fixtures.*

  "The exporter" should {

    "write one state the same, byte for byte, every time, with its codes ascending by hash and each once" taggedAs UnitTest in {
      val chain = new Chain
      val codes = (1 to 6).map(i => ByteString(s"code-$i".getBytes("UTF-8")))
      val storage = chain.storageTrie(Seq(1, 2, 3).map(BigInt(_)))
      // eight accounts over six codes: two of them held twice
      val accounts = (0 until 8).map { i =>
        Hex.decode(f"$i%02x" * 20) ->
          Account(UInt256(i), UInt256(i), TrieRoot(storage), CodeHash(crypto.kec256(codes(i % codes.size))))
      }
      val root = chain.accountTrie(accounts, codes)
      val tip =
        chain.store((0 to 2).map(n => powHeader(n, stateRoot = if n == 2 then root else EmptyTrieRoot)), Map.empty).last
      val first = chain.exportBytes(chain.exporter(61), 2).value
      val second = chain.exportBytes(chain.exporter(61), 2).value
      first shouldBe second
      val (_, entries) = decode(first)
      val written = entries.collect { case c: CheckpointArchive.CodeEntry => ByteString(crypto.kec256(c.code)) }
      written shouldBe codes.map(crypto.kec256).sorted(using CheckpointArchive.unsignedOrder)
      withClue("every occurrence: the storage trie eight accounts hold is written eight times: ") {
        val nodes = entries.collect { case n: CheckpointArchive.NodeEntry => n.rlp }
        nodes.count(n => ByteString(crypto.kec256(n)) == storage) shouldBe 8
        val load: Array[Byte] => Array[Byte] = h => nodes.find(n => crypto.kec256(n).sameElements(h)).get
        replay(tip.stateRoot.value, nodes) shouldBe RawTrieWalk.statePositions(tip.stateRoot.value.toArray, load)
      }
    }

    "round-trip seeded states: what it writes replays to every position of the state, trie by trie" taggedAs UnitTest in {
      val rng = new scala.util.Random(1806)
      (1 to 5).foreach { seed =>
        val chain = new Chain
        val storages =
          (0 until 3).map(_ => chain.storageTrie((0 until rng.between(1, 40)).map(_ => BigInt(rng.nextInt(5000)))))
        val accounts = (0 until rng.between(1, 60)).map { _ =>
          val address = Array.fill[Byte](20)(0)
          rng.nextBytes(address)
          val storage = if rng.nextBoolean() then TrieRoot(storages(rng.nextInt(3))) else Account.EmptyStorageRootHash
          address -> Account(UInt256(rng.nextInt(9)), UInt256(rng.nextInt(1000)), storage)
        }
        val root = chain.accountTrie(accounts)
        val tip = chain.store(Seq(powHeader(0), powHeader(1, stateRoot = root)), Map.empty).last
        val (header, entries) = decode(chain.exportBytes(chain.exporter(61), 1).value)
        header.blockHeader shouldBe tip
        val nodes = entries.collect { case n: CheckpointArchive.NodeEntry => n.rlp }
        val load: Array[Byte] => Array[Byte] = h => nodes.find(n => crypto.kec256(n).sameElements(h)).get
        withClue(s"seed $seed: ")(replay(root, nodes) shouldBe RawTrieWalk.statePositions(root.toArray, load))
      }
    }

    "write no node for an empty state" taggedAs UnitTest in {
      val chain = new Chain
      chain.store(Seq(powHeader(0), powHeader(1)), Map.empty)
      val (_, entries) = decode(chain.exportBytes(chain.exporter(61), 1).value)
      entries.collect { case n: CheckpointArchive.NodeEntry => n } shouldBe empty
    }

    "refuse a chain id wider than 64 bits, and a negative one, before it opens a file" taggedAs UnitTest in {
      val (chain, tip) = streamAChain()
      val file = Files.createTempFile("checkpoint-v2", ".checkpoint")
      Files.delete(file)
      chain.exporter(BigInt(2).pow(64)).exportArchive(tip.number.value, file).left.value shouldBe
        CheckpointExporter.ChainIdOutOfRange(BigInt(2).pow(64))
      chain.exporter(-1).exportArchive(tip.number.value, file).left.value shouldBe
        CheckpointExporter.ChainIdOutOfRange(-1)
      Files.exists(file) shouldBe false
      chain.exportBytes(chain.exporter(BigInt(2).pow(64) - 1), tip.number.value).value should not be empty
    }

    "refuse the genesis" taggedAs UnitTest in {
      val (chain, _) = streamAChain()
      chain.exportBytes(chain.exporter(61), 0).left.value shouldBe CheckpointExporter.GenesisNotExportable
    }

    "on a proof-of-stake chain, refuse a block above the finalized block, or with no finalized block known" taggedAs UnitTest in {
      val (chain, tip) = streamBChain()
      def at(finalized: Option[BigInt]) =
        chain.exportBytes(chain.exporter(StreamBChainId, Some(0), finalized), tip.number.value)
      at(Some(1)).left.value shouldBe CheckpointExporter.AboveFinalized(2, 1)
      at(None).left.value shouldBe CheckpointExporter.FinalizedUnknown(2)
      at(Some(2)).value should not be empty
      at(Some(5)).value should not be empty
    }

    "on a proof-of-stake chain, write no body, even of a block whose header names ommers" taggedAs UnitTest in {
      val chain = new Chain
      val ommer = ommerAt(1)
      val tip = chain
        .store(Seq(posHeader(0), posHeader(1).copy(ommersHash = powHeader(1, Seq(ommer)).ommersHash)), Map.empty)
        .last
      val (_, entries) = decode(chain.exportBytes(chain.exporter(1, Some(0), Some(1)), tip.number.value).value)
      entries.collect { case b: CheckpointArchive.WindowBodyEntry => b } shouldBe empty
    }

    // E.1's open item, put to the PR1 sign-off: below the merge, the block above can carry ommers and the window no body.
    "on a proof-of-stake chain not merged at genesis, refuse a block before the merge" taggedAs UnitTest in {
      val chain = new Chain
      val tip = chain.store((0 to 3).map(powHeader(_)), Map.empty).last
      val ttd = BigInt(10).pow(18)
      chain.exportBytes(chain.exporter(11155111, Some(ttd), Some(3)), 3).left.value shouldBe
        CheckpointExporter.BeforeTheMerge(3)
      withClue("merged at genesis, the rule does not apply: ") {
        chain.exportBytes(chain.exporter(11155111, Some(0), Some(3)), tip.number.value).value should not be empty
      }
    }

    "on a proof-of-work chain, write exactly B-302's bodies: C down to C-4, where the header names ommers" taggedAs UnitTest in {
      val chain = new Chain
      val withOmmers = Set(8, 7, 4, 3) // 3 is below C-4
      val headers = (0 to 8).map(n => if withOmmers(n) then powHeader(n, Seq(ommerAt(n))) else powHeader(n))
      val bodies = withOmmers.map(n => BigInt(n) -> BlockBody(Nil, Seq(ommerAt(n)))).toMap
      val stored = chain.store(headers, bodies)
      val (_, entries) = decode(chain.exportBytes(chain.exporter(61), 8).value)
      entries.collect { case b: CheckpointArchive.WindowBodyEntry => b.blockHash } shouldBe
        Seq(8, 7, 4).map(n => stored(n).hash.value)
      entries.collect { case w: CheckpointArchive.WindowHeaderEntry => w.header } shouldBe stored.init.reverse
    }

    "refuse, with no file, a window whose body or header is not stored" taggedAs UnitTest in {
      val chain = new Chain
      val stored =
        chain.store((0 to 4).map(n => if n == 3 then powHeader(n, Seq(ommerAt(n))) else powHeader(n)), Map.empty)
      chain.exportBytes(chain.exporter(61), 4).left.value shouldBe
        CheckpointExporter.MissingWindowBody(3, stored(3).hash.value)

      val gap = new Chain
      val headers = gap.store((0 to 4).map(powHeader(_)), Map.empty)
      // a block 5 whose parent is not stored
      val orphan: BlockHeader = powHeader(5).copy(parentHash = powHeader(9).hash)
      gap.writer.storeBlockHeader(orphan).commit()
      gap.writer
        .storeChainWeight(orphan.hash, com.chipprbots.ethereum.domain.ChainWeight.totalDifficultyOnly(1))
        .commit()
      gap.holdChainWeightInvariant()
      gap.exportBytes(gap.exporter(61), 5).left.value shouldBe
        CheckpointExporter.MissingWindowHeader(4, orphan.parentHash.value)
      headers should have size 5 // blocks 0 to 4 are stored, and none is the orphan's parent
    }

    "set the TD-exact flag from the node's chain-weight invariant" taggedAs UnitTest in {
      val chain = new Chain
      chain.store(Seq(powHeader(0), powHeader(1)), Map.empty)
      decode(chain.exportBytes(chain.exporter(61), 1).value)._1.tdExact shouldBe false
      chain.holdChainWeightInvariant()
      decode(chain.exportBytes(chain.exporter(61), 1).value)._1.tdExact shouldBe true
    }

    "write the stored weight where the headers below the window are not stored only while the invariant is held" taggedAs UnitTest in {
      val chain = new Chain
      // block 10 is missing: the window of block 300 (300 down to 44) is whole, the walk to genesis is not
      val tip = chain.store((0 to 300).map(powHeader(_)), Map.empty, skip = Set(10)).last
      chain.writer
        .storeChainWeight(tip.hash, com.chipprbots.ethereum.domain.ChainWeight.totalDifficultyOnly(777))
        .commit()
      chain.exportBytes(chain.exporter(61), 300).left.value shouldBe a[CheckpointExporter.IncompleteHeaderChain]
      chain.holdChainWeightInvariant()
      val (header, entries) = decode(chain.exportBytes(chain.exporter(61), 300).value)
      header.totalDifficulty shouldBe 777
      header.tdExact shouldBe true
      entries.collect { case w: CheckpointArchive.WindowHeaderEntry =>
        w.header.number.value
      } shouldBe (299 to 44 by -1)
    }
  }
