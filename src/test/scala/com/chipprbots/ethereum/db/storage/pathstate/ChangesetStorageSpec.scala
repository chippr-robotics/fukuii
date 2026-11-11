package com.chipprbots.ethereum.db.storage.pathstate

import org.apache.pekko.util.ByteString

import org.bouncycastle.util.encoders.Hex
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.db.dataSource.DataSource
import com.chipprbots.ethereum.db.dataSource.DataSourceUpdateOptimized
import com.chipprbots.ethereum.db.storage.Namespaces
import com.chipprbots.ethereum.db.storage.pathstate.BlockDiffCodec.Rejection
import com.chipprbots.ethereum.mpt.NibblePath
import com.chipprbots.ethereum.testing.Tags.*

/** Spec 018 Track C, T067: the changeset records of CF `v` (data-model 4.1, 4.5): a record under its block's hash, an
  * index of the hashes at each height, and pruning below a floor, on `EphemDataSource` and on RocksDB.
  */
class ChangesetStorageSpec extends AnyFlatSpec with Matchers:
  import PathStateTestKit.dataSources

  private val namespace = Namespaces.StateChangesetNamespace

  // What the state lock hands its holder (C3b). A test of this package may make one: nothing here runs beside it.
  private given StateLockHeld = new StateLockHeld()

  private def fill(byte: Int, length: Int = 32): ByteString = ByteString(Array.fill[Byte](length)(byte.toByte))

  /** A block's diff: one node written in the account trie. `tag` tells two blocks of one height apart. */
  private def diff(number: Int, tag: Int = 0): BlockDiff =
    BlockDiff(
      blockHash = (ByteString(tag.toByte) ++ fill(number)).take(32),
      number = number,
      parentHash = fill(number - 1),
      preStateRoot = fill(0x33),
      postStateRoot = fill(0x44),
      changes = Map(PositionKey.at(None, NibblePath(number % 16)) -> Change(None, Some(fill(number, 40))))
    )

  private def allKeys(dataSource: DataSource): Seq[ByteString] =
    dataSource
      .scanRange(namespace, Array.emptyByteArray, Array.fill[Byte](34)(0xff.toByte))
      .map((k, _) => ByteString(k))
      .toSeq

  "The changeset keys" should "be the block hash then the kind, and a zero byte then the height in 8 bytes" taggedAs
    UnitTest in {
      Hex.toHexString(ChangesetStorage.recordKey(fill(0xab))) shouldBe "ab" * 32 + "01"
      ChangesetStorage.recordKey(fill(0xab)).length shouldBe 33
      Hex.toHexString(ChangesetStorage.heightKey(BigInt("0102030405060708", 16))) shouldBe "000102030405060708"
      Hex.toHexString(ChangesetStorage.heightKey(0)) shouldBe "000000000000000000"
      Hex.toHexString(ChangesetStorage.heightKey(BlockDiff.MaxNumber)) shouldBe "00" + "ff" * 8
      an[IllegalArgumentException] should be thrownBy ChangesetStorage.heightKey(BlockDiff.MaxNumber + 1)
      an[IllegalArgumentException] should be thrownBy ChangesetStorage.recordKey(ByteString(1, 2, 3))
    }

  dataSources.foreach { case (name, onDataSource) =>
    s"The changeset storage on $name" should "give back the record it was given, and none for a block it was not" taggedAs
      UnitTest in {
        onDataSource { dataSource =>
          val storage = new ChangesetStorage(dataSource)
          val block = diff(7)
          storage.get(block.blockHash) shouldBe None
          storage.put(block)
          storage.get(block.blockHash) shouldBe Some(Right(block))
          storage.get(fill(0xee)) shouldBe None
          storage.hashesAt(7) shouldBe Seq(block.blockHash)
          storage.hashesAt(8) shouldBe empty

          // the bytes under the key are the codec's, and a tombstone is a record like any other
          ByteString(dataSource.getOptimized(namespace, ChangesetStorage.recordKey(block.blockHash)).get) shouldBe
            ByteString(BlockDiffCodec.encode(block))
          val tombstone = diff(8).copy(changes = Map.empty, incomplete = true)
          storage.put(tombstone)
          storage.get(tombstone.blockHash) shouldBe Some(Right(tombstone))
        }
      }

    it should "list every block that has a record at a height, once each, in the order they were recorded" taggedAs
      UnitTest in {
        onDataSource { dataSource =>
          val storage = new ChangesetStorage(dataSource)
          val first = diff(7, tag = 1)
          val sibling = diff(7, tag = 2)
          storage.put(first)
          storage.put(sibling)
          storage.hashesAt(7) shouldBe Seq(first.blockHash, sibling.blockHash)
          // the index value is the two hashes, one after the other
          ByteString(dataSource.getOptimized(namespace, ChangesetStorage.heightKey(7)).get) shouldBe
            first.blockHash ++ sibling.blockHash

          // recording a block again changes nothing
          val before = allKeys(dataSource)
          storage.put(first)
          storage.hashesAt(7) shouldBe Seq(first.blockHash, sibling.blockHash)
          allKeys(dataSource) shouldBe before
          storage.recordUpdates(first).flatMap(_.toUpsert).map((k, _) => ByteString(k)) shouldBe
            Seq(ByteString(ChangesetStorage.recordKey(first.blockHash)))
        }
      }

    it should "write the local kind only: nothing under the reserved kind 2" taggedAs UnitTest in {
      onDataSource { dataSource =>
        val storage = new ChangesetStorage(dataSource)
        (1 to 5).foreach(n => storage.put(diff(n)))
        val keys = allKeys(dataSource)
        keys.size shouldBe 10
        keys.filter(_.length == 33).map(_.last).toSet shouldBe Set(0x01.toByte)
        keys.filter(_.length == 9).map(_.head).toSet shouldBe Set(0x00.toByte)
        keys.forall(key => key.length == 33 || key.length == 9) shouldBe true
        dataSource.getOptimized(namespace, (diff(1).blockHash :+ 0x02.toByte).toArray) shouldBe None
      }
    }

    it should "prune the heights below a floor, a bounded number at a time, and say where to go on" taggedAs
      UnitTest in {
        onDataSource { dataSource =>
          val storage = new ChangesetStorage(dataSource)
          (1 to 10).foreach(n => storage.put(diff(n)))
          storage.put(diff(3, tag = 9))

          // at most three heights a batch
          storage.prune(from = 1, floor = 8, maxHeights = 3) shouldBe 4
          (1 to 3).foreach(n => storage.get(diff(n).blockHash) shouldBe None)
          storage.get(diff(3, tag = 9).blockHash) shouldBe None
          (1 to 3).foreach(n => storage.hashesAt(n) shouldBe empty)
          storage.get(diff(4).blockHash) shouldBe Some(Right(diff(4)))

          // the rest, up to the floor and not past it
          storage.prune(from = 4, floor = 8, maxHeights = 16) shouldBe 8
          (4 to 7).foreach(n => storage.get(diff(n).blockHash) shouldBe None)
          (8 to 10).foreach(n => storage.get(diff(n).blockHash) shouldBe Some(Right(diff(n))))
          storage.hashesAt(8) shouldBe Seq(diff(8).blockHash)
          allKeys(dataSource).size shouldBe 6
        }
      }

    it should "prune again and change nothing: below the floor, at it, and with nothing to do" taggedAs UnitTest in {
      onDataSource { dataSource =>
        val storage = new ChangesetStorage(dataSource)
        (1 to 6).foreach(n => storage.put(diff(n)))
        storage.prune(1, 4) shouldBe 4
        val after = allKeys(dataSource)

        // the same heights again: nothing is left there, and no write is made
        storage.pruneUpdates(1, 4) shouldBe (Nil, BigInt(4))
        storage.prune(1, 4) shouldBe 4
        // from the floor, and from above it
        storage.pruneUpdates(4, 4) shouldBe (Nil, BigInt(4))
        storage.pruneUpdates(9, 4) shouldBe (Nil, BigInt(9))
        allKeys(dataSource) shouldBe after

        // a batch that was built and lost is built again the same
        val built = storage.pruneUpdates(4, 6)
        storage.pruneUpdates(4, 6)._1.flatMap(_.toRemove).map(ByteString(_)) shouldBe
          built._1.flatMap(_.toRemove).map(ByteString(_))
        built._2 shouldBe 6
      }
    }

    it should "keep a record whose hash begins with zero bytes, which a scan of the height index would meet" taggedAs
      UnitTest in {
        onDataSource { dataSource =>
          // A proof-of-work block hash begins with zero bytes, and so does every key of the height index. At the
          // heights a real chain is at, such a record's key sorts before the index keys: here, six zero bytes (an
          // ETC mainnet hash) against heights around 25 million.
          val storage = new ChangesetStorage(dataSource)
          val base = 25000000
          val mined = diff(base + 9).copy(blockHash = ByteString(new Array[Byte](6)) ++ fill(0x5a, 26))
          (1 to 9).foreach(n => storage.put(if n == 9 then mined else diff(base + n)))

          val keys = allKeys(dataSource)
          val minedKey = ByteString(ChangesetStorage.recordKey(mined.blockHash))
          keys.head shouldBe minedKey
          keys.indexOf(minedKey) should be < keys.indexOf(ByteString(ChangesetStorage.heightKey(base + 1)))

          // pruning reads heights by their own keys, so the record of the height it keeps is untouched
          storage.prune(base + 1, base + 9) shouldBe BigInt(base + 9)
          storage.get(mined.blockHash) shouldBe Some(Right(mined))
          storage.hashesAt(base + 9) shouldBe Seq(mined.blockHash)
          allKeys(dataSource).size shouldBe 2
        }
      }

    it should "say why a stored value is not a record, and not call it absent" taggedAs UnitTest in {
      onDataSource { dataSource =>
        val storage = new ChangesetStorage(dataSource)
        val block = diff(7)
        val key = ChangesetStorage.recordKey(block.blockHash)
        val damaged = BlockDiffCodec.encode(block)
        damaged(150) = (damaged(150) ^ 0x10).toByte
        dataSource.update(Seq(DataSourceUpdateOptimized(namespace, Nil, Seq(key -> damaged))))
        storage.get(block.blockHash).map(_.left.map(_.reason)) shouldBe Some(Left(Rejection.BadCrc))

        // another block's record under this block's key
        dataSource.update(Seq(DataSourceUpdateOptimized(namespace, Nil, Seq(key -> BlockDiffCodec.encode(diff(8))))))
        storage.get(block.blockHash).map(_.left.map(_.reason)) shouldBe Some(Left(Rejection.KeyMismatch))
      }
    }
  }
