package com.chipprbots.ethereum.db.storage

import java.io.File
import java.nio.file.Files

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.dataSource.RocksDbConfig
import com.chipprbots.ethereum.db.dataSource.RocksDbDataSource
import com.chipprbots.ethereum.testing.Tags.*

/** [[SnapStorageDoneStorage]] — SNAP storage-task completion markers. Backed by a real (temp-dir) RocksDB so the
  * multi-get, the prefix-bounded range-tombstone `clear` and the shared-WriteBatch commit are exercised as in
  * production.
  */
class SnapStorageDoneStorageSpec extends AnyFlatSpec with Matchers:

  private def acct(i: Int): ByteString = kec256(ByteString(s"done-acct-$i"))
  private def root(i: Int): ByteString = kec256(ByteString(s"done-root-$i"))

  private def withDataSource(test: RocksDbDataSource => Unit): Unit =
    val dbPath = Files.createTempDirectory("snap-storage-done-rocksdb").toAbsolutePath.toString
    val dataSource = RocksDbDataSource(
      new RocksDbConfig:
        override val createIfMissing: Boolean = true
        override val paranoidChecks: Boolean = true
        override val path: String = dbPath
        override val maxThreads: Int = 1
        override val maxOpenFiles: Int = 32
        override val verifyChecksums: Boolean = true
        override val levelCompaction: Boolean = true
        override val blockSize: Long = 16384
        override val blockCacheSize: Long = 33554432
      ,
      Namespaces.nsSeq
    )
    try test(dataSource)
    finally
      dataSource.destroy()
      val dir = new File(dbPath)
      if dir.exists() then dir.delete()

  "SnapStorageDoneStorage" should "mark (account, root) pairs done and match only the exact pair" taggedAs UnitTest in
    withDataSource { ds =>
      val done = new SnapStorageDoneStorage(ds)
      done.isDone(acct(1), root(1)) shouldBe false
      done.markDone(Seq(acct(1) -> root(1), acct(2) -> root(2))).commit()
      done.isDone(acct(1), root(1)) shouldBe true
      done.isDone(acct(2), root(2)) shouldBe true
      // Same account, other storage root (e.g. re-identified at a later pivot): not finished.
      done.isDone(acct(1), root(2)) shouldBe false
      done.isDone(acct(3), root(3)) shouldBe false
    }

  it should "return the unfinished items in their original order" taggedAs UnitTest in withDataSource { ds =>
    val done = new SnapStorageDoneStorage(ds)
    done.markDone(Seq(acct(0) -> root(0), acct(2) -> root(2), acct(4) -> root(4))).commit()
    val items = (0 until 6).map(i => (i, acct(i), root(i)))
    done.unfinished(items)(t => (t._2, t._3)).map(_._1) shouldBe Seq(1, 3, 5)
    done.unfinished(Seq.empty[(Int, ByteString, ByteString)])(t => (t._2, t._3)) shouldBe empty
  }

  it should "commit atomically with the flat slots it rides with, and require the same data source" taggedAs UnitTest in
    withDataSource { ds =>
      val flat = new FlatSlotStorage(ds)
      val done = new SnapStorageDoneStorage(ds)
      val slot = kec256(ByteString("slot")) -> ByteString("value")
      val batch = flat.putSlotsBatch(acct(1), Seq(slot)).and(done.markDone(Seq(acct(1) -> root(1))))
      // Built but not committed (a crash before the write): neither is visible.
      flat.getSlot(acct(1), slot._1) shouldBe None
      done.isDone(acct(1), root(1)) shouldBe false
      batch.commit()
      flat.getSlot(acct(1), slot._1) shouldBe Some(slot._2)
      done.isDone(acct(1), root(1)) shouldBe true
      // A marker store on another data source cannot join the flat-slot batch.
      an[IllegalArgumentException] should be thrownBy
        flat.putSlotsBatch(acct(2), Seq(slot)).and(new SnapStorageDoneStorage(EphemDataSource()).markDone(Seq.empty))
    }

  it should "clear every marker and leave the other app-state keys and namespaces alone" taggedAs UnitTest in
    withDataSource { ds =>
      val flat = new FlatSlotStorage(ds)
      val appState = new AppStateStorage(ds)
      val done = new SnapStorageDoneStorage(ds)
      val slot = kec256(ByteString("kept-slot")) -> ByteString("kept")
      flat.putSlotsBatch(acct(9), Seq(slot)).commit()
      // App-state keys sorting just before and after the marker prefix, plus a real one, survive a clear.
      appState.putSnapAccountResumeCheckpoint("{}").commit()
      appState.put("SnapStorageDone", "before").commit()
      appState.put("SnapStorageDone0", "after").commit()
      // Extreme keys: the all-zero and all-0xff pairs must be inside the cleared range.
      val zero = ByteString(Array.fill(32)(0.toByte))
      val ff = ByteString(Array.fill(32)(0xff.toByte))
      done.markDone(Seq(acct(1) -> root(1), zero -> zero, ff -> ff)).commit()
      done.clear()
      done.isDone(acct(1), root(1)) shouldBe false
      done.isDone(zero, zero) shouldBe false
      done.isDone(ff, ff) shouldBe false
      flat.getSlot(acct(9), slot._1) shouldBe Some(slot._2)
      appState.getSnapAccountResumeCheckpoint() shouldBe Some("{}")
      appState.get("SnapStorageDone") shouldBe Some("before")
      appState.get("SnapStorageDone0") shouldBe Some("after")
      // Usable again after a clear.
      done.markDone(Seq(acct(1) -> root(1))).commit()
      done.isDone(acct(1), root(1)) shouldBe true
    }
