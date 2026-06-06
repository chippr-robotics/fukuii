package com.chipprbots.ethereum.db.storage

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.db.dataSource.{RocksDbConfig, RocksDbDataSource}
import com.chipprbots.ethereum.testing.Tags._

import java.io.File
import java.nio.file.Files

/** Tests for [[HealingFrontierStorage]] — the Layer-2 persisted healing frontier (node hash -> pathset).
  *
  * Backed by a real (temp-dir) RocksDB rather than `EphemDataSource`, because the resume path relies on `loadAll()`
  * (namespace iteration) returning the bare node hash as the key. `EphemDataSource.iterate` returns namespace-prefixed
  * keys, which would corrupt the hash; RocksDB column families return bare keys, matching production. See
  * docs/design/healing-frontier-scale.md (Layer 2).
  */
class HealingFrontierStorageSpec extends AnyFlatSpec with Matchers {

  private def hash(i: Int): ByteString = ByteString(Array.fill(32)(i.toByte))
  private def pathset(parts: Int): Seq[ByteString] =
    (0 until parts).map(p => ByteString(Array.fill(p + 1)((p + 1).toByte)))

  private def withStorage(test: HealingFrontierStorage => Unit): Unit = {
    val dbPath = Files.createTempDirectory("healing-frontier-rocksdb").toAbsolutePath.toString
    val dataSource = RocksDbDataSource(
      new RocksDbConfig {
        override val createIfMissing: Boolean = true
        override val paranoidChecks: Boolean = true
        override val path: String = dbPath
        override val maxThreads: Int = 1
        override val maxOpenFiles: Int = 32
        override val verifyChecksums: Boolean = true
        override val levelCompaction: Boolean = true
        override val blockSize: Long = 16384
        override val blockCacheSize: Long = 33554432
      },
      Namespaces.nsSeq
    )
    try test(new HealingFrontierStorage(dataSource))
    finally {
      dataSource.destroy()
      val dir = new File(dbPath)
      !dir.exists() || dir.delete()
    }
  }

  "HealingFrontierStorage" should "round-trip a (hash -> pathset) entry through put/get" taggedAs UnitTest in
    withStorage { storage =>
      val h = hash(1)
      val ps = pathset(3)
      storage.put(h, ps).commit()
      storage.get(h) shouldBe Some(ps)
    }

  it should "preserve pathset serialization for single- and multi-segment pathsets" taggedAs UnitTest in
    withStorage { storage =>
      val single = Seq(ByteString(Array[Byte](0x20, 0x01)))
      val multi = Seq(ByteString(Array.fill(32)(0xaa.toByte)), ByteString(Array[Byte](0x10, 0x02, 0x03)))
      storage.put(hash(10), single).commit()
      storage.put(hash(11), multi).commit()
      storage.get(hash(10)) shouldBe Some(single)
      storage.get(hash(11)) shouldBe Some(multi)
    }

  it should "remove an entry" taggedAs UnitTest in withStorage { storage =>
    val h = hash(2)
    storage.put(h, pathset(1)).commit()
    storage.get(h) shouldBe defined
    storage.remove(h).commit()
    storage.get(h) shouldBe None
  }

  it should "treat removing an absent key as a no-op" taggedAs UnitTest in withStorage { storage =>
    storage.remove(hash(99)).commit() // must not throw
    storage.get(hash(99)) shouldBe None
  }

  it should "load every persisted entry via loadAll with bare (un-prefixed) hash keys" taggedAs UnitTest in
    withStorage { storage =>
      val entries = (0 until 25).map(i => hash(i) -> pathset((i % 3) + 1))
      storage.update(Nil, entries).commit()

      val loaded = storage.loadAll().toMap
      loaded.size shouldBe entries.size
      entries.foreach { case (h, ps) =>
        h.length shouldBe 32 // bare hash, not namespace-prefixed
        loaded.get(h) shouldBe Some(ps)
      }
    }

  it should "return an empty sequence from loadAll on an empty store" taggedAs UnitTest in withStorage { storage =>
    storage.loadAll() shouldBe empty
  }

  it should "apply batched upserts and removes atomically" taggedAs UnitTest in withStorage { storage =>
    storage.update(Nil, Seq(hash(1) -> pathset(1), hash(2) -> pathset(1), hash(3) -> pathset(1))).commit()
    // Remove one, add one, in a single batch.
    storage.update(Seq(hash(2)), Seq(hash(4) -> pathset(2))).commit()

    val loaded = storage.loadAll().toMap
    loaded.keySet shouldBe Set(hash(1), hash(3), hash(4))
    loaded(hash(4)) shouldBe pathset(2)
  }
}
