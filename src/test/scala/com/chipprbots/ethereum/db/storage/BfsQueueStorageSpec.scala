package com.chipprbots.ethereum.db.storage

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.db.dataSource.{EphemDataSource, RocksDbConfig, RocksDbDataSource}
import com.chipprbots.ethereum.testing.Tags._

import java.io.File
import java.nio.file.Files

/** BfsQueueStorage over a real RocksDB instance — exercises the native range-tombstone delete path
  * (`DataSource.deleteRange`) that replaced per-key tombstone batches. The per-key implementation wrote ~140M
  * tombstones (~30 min at full CPU, observed live on ETC mainnet 2026-06-12) when clearing the queue after a full-state
  * healing walk; the range tombstone is a single O(1) write.
  *
  * Also covers the crash-restart contract: the write counter is in-memory only, so a fresh storage instance over a
  * column family still holding a dead walk's entries must `clear()` them despite `counter == 0`.
  */
class BfsQueueStorageSpec extends AnyFlatSpec with Matchers {

  private def entry(i: Int): (Array[Byte], Seq[Array[Byte]], Boolean) =
    (Array.fill(32)(i.toByte), Seq(Array(i.toByte, (i + 1).toByte)), i % 2 == 0)

  private def drain(it: Iterator[Seq[BfsEntry]]): Seq[BfsEntry] = it.flatten.toSeq

  private def withRocksDb(test: RocksDbDataSource => Unit): Unit = {
    val dbPath = Files.createTempDirectory("bfs-queue-rocksdb").toAbsolutePath.toString
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
    try test(dataSource)
    finally {
      dataSource.destroy()
      val dir = new File(dbPath)
      !dir.exists() || dir.delete()
    }
  }

  "RocksDbBfsQueueStorage" should "round-trip entries through enqueueBatch/iterateRange" taggedAs UnitTest in
    withRocksDb { ds =>
      val q = new RocksDbBfsQueueStorage(ds, Namespaces.BfsQueueNamespace)
      q.enqueueBatch((0 until 100).map(entry))
      q.counter shouldBe 100L
      val got = drain(q.iterateRange(0L, 100L, chunkSize = 7))
      got.size shouldBe 100
      got.head.hash.toSeq shouldBe Array.fill(32)(0.toByte).toSeq
      got.last.pathset.head.toSeq shouldBe Seq(99.toByte, 100.toByte)
      got.map(_.isStorage).count(identity) shouldBe 50
    }

  it should "delete exactly [from, to) with a native range tombstone" taggedAs UnitTest in
    withRocksDb { ds =>
      val q = new RocksDbBfsQueueStorage(ds, Namespaces.BfsQueueNamespace)
      q.enqueueBatch((0 until 100).map(entry))
      q.deleteRange(0L, 50L)
      drain(q.iterateRange(0L, 50L)) shouldBe empty
      drain(q.iterateRange(50L, 100L)).size shouldBe 50
      // boundary: deleting an empty range is a no-op
      q.deleteRange(70L, 70L)
      drain(q.iterateRange(50L, 100L)).size shouldBe 50
    }

  it should "clear() the keyspace and reset the counter" taggedAs UnitTest in
    withRocksDb { ds =>
      val q = new RocksDbBfsQueueStorage(ds, Namespaces.BfsQueueNamespace)
      q.enqueueBatch((0 until 64).map(entry))
      q.clear()
      q.counter shouldBe 0L
      drain(q.iterateRange(0L, 64L)) shouldBe empty
    }

  it should "clear() a dead walk's entries after a simulated crash-restart (counter == 0)" taggedAs UnitTest in
    withRocksDb { ds =>
      // Walk 1 writes 100 entries, then the process "crashes" (instance dropped, no clear()).
      val before = new RocksDbBfsQueueStorage(ds, Namespaces.BfsQueueNamespace)
      before.enqueueBatch((0 until 100).map(entry))

      // Restart: fresh instance, in-memory counter back at 0 while the CF still holds 100 keys.
      // The old guard (`if (counter > 0) deleteRange`) skipped deletion entirely here.
      val after = new RocksDbBfsQueueStorage(ds, Namespaces.BfsQueueNamespace)
      after.counter shouldBe 0L
      after.clear()

      after.enqueueBatch((200 until 210).map(entry))
      val fresh = drain(after.iterateRange(0L, 10L))
      fresh.size shouldBe 10
      fresh.head.hash.toSeq shouldBe Array.fill(32)(200.toByte).toSeq
      // keys beyond the fresh writes must NOT resurrect the dead walk's entries
      drain(after.iterateRange(10L, 100L)) shouldBe empty
    }

  it should "leave other namespaces untouched by deleteRange" taggedAs UnitTest in
    withRocksDb { ds =>
      val q = new RocksDbBfsQueueStorage(ds, Namespaces.BfsQueueNamespace)
      val frontier = new HealingFrontierStorage(ds)
      import org.apache.pekko.util.ByteString
      val h = ByteString(Array.fill(32)(7.toByte))
      frontier.put(h, Seq(ByteString(1.toByte))).commit()
      q.enqueueBatch((0 until 10).map(entry))
      q.clear()
      frontier.get(h) shouldBe Some(Seq(ByteString(1.toByte)))
    }

  "EphemDataSource.deleteRange" should "honor namespace isolation and range boundaries" taggedAs UnitTest in {
    val ds = EphemDataSource()
    val nsA: IndexedSeq[Byte] = IndexedSeq('a'.toByte)
    val nsB: IndexedSeq[Byte] = IndexedSeq('b'.toByte)
    def key(i: Int): Array[Byte] = BfsQueueStorage.longToBytes(i.toLong)
    (0 until 10).foreach(i => ds.update(keyUpsert(nsA, key(i))))
    (0 until 10).foreach(i => ds.update(keyUpsert(nsB, key(i))))

    ds.deleteRange(nsA, key(3), key(7))

    (0 until 10).map(i => ds.getOptimized(nsA, key(i)).isDefined) shouldBe
      Seq(true, true, true, false, false, false, false, true, true, true)
    (0 until 10).forall(i => ds.getOptimized(nsB, key(i)).isDefined) shouldBe true
  }

  private def keyUpsert(ns: IndexedSeq[Byte], k: Array[Byte]) = {
    import com.chipprbots.ethereum.db.dataSource.DataSourceUpdateOptimized
    Seq(DataSourceUpdateOptimized(ns, toRemove = Seq.empty, toUpsert = Seq((k, Array(1.toByte)))))
  }

  "InMemoryBfsQueueStorage" should "match RocksDb semantics for deleteRange and clear" taggedAs UnitTest in {
    val q = new InMemoryBfsQueueStorage()
    q.enqueueBatch((0 until 20).map(entry))
    q.deleteRange(5L, 15L)
    drain(q.iterateRange(0L, 20L)).size shouldBe 10
    q.clear()
    q.counter shouldBe 0L
    drain(q.iterateRange(0L, 20L)) shouldBe empty
  }
}
