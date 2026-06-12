package com.chipprbots.ethereum.db.storage

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.db.dataSource.{RocksDbConfig, RocksDbDataSource}
import com.chipprbots.ethereum.testing.Tags._

import java.io.File
import java.nio.file.Files

/** Tests for [[SnapSyncProgressStorage]] — SNAP download cursor persistence (account + storage cursors). */
class SnapSyncProgressStorageSpec extends AnyFlatSpec with Matchers {

  private def root(i: Int): ByteString = ByteString(Array.fill(32)(i.toByte))
  private def cursor(i: Int): String = "0" * (64 - i.toString.length) + i.toString

  private def withStorage(test: SnapSyncProgressStorage => Unit): Unit = {
    val dbPath = Files.createTempDirectory("snap-progress-rocksdb").toAbsolutePath.toString
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
    try test(new SnapSyncProgressStorage(dataSource))
    finally {
      dataSource.destroy()
      val dir = new File(dbPath)
      if (dir.exists()) dir.delete()
    }
  }

  "SnapSyncProgressStorage" should "round-trip a progress entry through writeProgress/readProgress" taggedAs UnitTest in
    withStorage { storage =>
      val stateRoot = root(1)
      val progress = SnapSyncProgress(100L, Map(cursor(1) -> cursor(2)), Map(cursor(3) -> cursor(4)))
      storage.writeProgress(stateRoot, progress)
      storage.readProgress(stateRoot) shouldBe Some(progress)
    }

  it should "return None for an absent stateRoot" taggedAs UnitTest in withStorage { storage =>
    storage.readProgress(root(99)) shouldBe None
  }

  it should "remove a progress entry via clearProgress" taggedAs UnitTest in withStorage { storage =>
    val stateRoot = root(2)
    storage.writeProgress(stateRoot, SnapSyncProgress(200L, Map(cursor(1) -> cursor(2)), Map.empty))
    storage.readProgress(stateRoot) shouldBe defined
    storage.clearProgress(stateRoot)
    storage.readProgress(stateRoot) shouldBe None
  }

  it should "preserve storageCursors when writeAccountCursors is called" taggedAs UnitTest in withStorage { storage =>
    val stateRoot = root(3)
    // Pre-populate with storage cursors written by StorageRangeCoordinator
    storage.writeProgress(
      stateRoot,
      SnapSyncProgress(0L, Map.empty, Map(cursor(10) -> cursor(11), cursor(12) -> cursor(13)))
    )
    // Controller writes account cursors — must not wipe the pre-existing storage cursors
    storage.writeAccountCursors(stateRoot, 300L, Map(cursor(1) -> cursor(2)))
    val result = storage.readProgress(stateRoot).get
    result.pivotBlock shouldBe 300L
    result.accountCursors shouldBe Map(cursor(1) -> cursor(2))
    // Storage cursors written by the coordinator must survive the account-cursor update
    result.storageCursors shouldBe Map(cursor(10) -> cursor(11), cursor(12) -> cursor(13))
  }

  it should "store independent progress entries keyed by different stateRoots" taggedAs UnitTest in
    withStorage { storage =>
      val root1 = root(4)
      val root2 = root(5)
      val p1 = SnapSyncProgress(400L, Map(cursor(1) -> cursor(2)), Map.empty)
      val p2 = SnapSyncProgress(500L, Map(cursor(3) -> cursor(4)), Map(cursor(5) -> cursor(6)))
      storage.writeProgress(root1, p1)
      storage.writeProgress(root2, p2)
      storage.readProgress(root1) shouldBe Some(p1)
      storage.readProgress(root2) shouldBe Some(p2)
      // Removing one must not affect the other
      storage.clearProgress(root1)
      storage.readProgress(root1) shouldBe None
      storage.readProgress(root2) shouldBe Some(p2)
    }
}
