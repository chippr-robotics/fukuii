package com.chipprbots.ethereum.blockchain.sync.snap

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.db.dataSource.{RocksDbConfig, RocksDbDataSource}
import com.chipprbots.ethereum.db.storage.{Namespaces, SnapSyncProgress, SnapSyncProgressStorage}
import com.chipprbots.ethereum.testing.Tags._
import com.chipprbots.ethereum.utils.Hex

import java.io.File
import java.nio.file.Files

/** Tests for the persistence contract that [[SNAPSyncController]] relies on:
  *
  *   - (6) Account cursors written by the controller are retrievable on next startup.
  *   - (7) readProgress + conversion into the resumeMap used by AccountRangeCoordinator.
  *   - (8) Progress stored at a stale pivot (drift > MaxPreservedPivotDistance) must be ignored and cleared.
  *
  * These are pure storage-layer tests (no actor system / Thread.sleep). The actor-level orchestration is intentionally
  * not re-tested here because the controller's logic is covered by SNAPSyncControllerSpec and the storage round-trip is
  * the only new contract introduced by spec-004.
  */
class SNAPSyncControllerResumeSpec extends AnyFlatSpec with Matchers {

  private val MaxPreservedPivotDistance: BigInt = BigInt(50_000)

  private def stateRoot(i: Int): ByteString = ByteString(Array.fill(32)(i.toByte))
  private def hexStr(i: Int): String = "%064x".format(i)

  private def withStorage(test: SnapSyncProgressStorage => Unit): Unit = {
    val dbPath = Files.createTempDirectory("snap-resume-rocksdb").toAbsolutePath.toString
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

  // Test 6: account cursors written by the controller are retrievable on next startup
  "SNAPSyncControllerResume" should "persist account cursors so they survive a crash and restart" taggedAs UnitTest in
    withStorage { storage =>
      val root = stateRoot(1)
      val pivot = 100_000L
      // Simulate what the controller writes in the AccountRangeProgress handler
      val cursors = Map(hexStr(1) -> hexStr(2), hexStr(3) -> hexStr(4))
      storage.writeAccountCursors(root, pivot, cursors)

      // Simulate what the controller reads on the next startup via readProgress
      val recovered = storage.readProgress(root)
      recovered shouldBe defined
      recovered.get.pivotBlock shouldBe pivot
      recovered.get.accountCursors shouldBe cursors
    }

  // Test 7: readProgress → resumeMap conversion is correct (ByteString round-trip via Hex)
  it should "convert persisted hex cursors back to ByteString resumeMap without data loss" taggedAs UnitTest in
    withStorage { storage =>
      val root = stateRoot(2)
      // Write account cursors the same way the controller does (hex-encoded ByteString)
      val lastBs = ByteString(Hex.decode("aa" * 32))
      val nextBs = ByteString(Hex.decode("bb" * 32))
      import com.chipprbots.ethereum.utils.ByteStringUtils.ByteStringOps
      storage.writeAccountCursors(root, 200_000L, Map(lastBs.toHex -> nextBs.toHex))

      // Simulate the controller's startup conversion: hex → ByteString resumeMap
      val saved = storage.readProgress(root).get
      val resumeMap: Map[ByteString, ByteString] = saved.accountCursors.flatMap { case (lastHex, nextHex) =>
        import scala.util.Try
        for {
          last <- Try(ByteString(Hex.decode(lastHex))).toOption
          next <- Try(ByteString(Hex.decode(nextHex))).toOption
        } yield last -> next
      }
      resumeMap should have size 1
      resumeMap(lastBs) shouldBe nextBs
    }

  // Test 8: progress stored at pivot with drift > MaxPreservedPivotDistance is cleared and ignored
  it should "clear and ignore progress when pivot drift exceeds MaxPreservedPivotDistance" taggedAs UnitTest in
    withStorage { storage =>
      val root = stateRoot(3)
      val savedPivot = 100_000L
      // Simulate progress stored at an old pivot
      storage.writeAccountCursors(root, savedPivot, Map(hexStr(1) -> hexStr(2)))

      // Simulate the controller's drift check logic at startup
      val currentPivot = BigInt(savedPivot) + MaxPreservedPivotDistance + 1
      val saved = storage.readProgress(root)
      saved shouldBe defined

      val drift = (currentPivot - BigInt(saved.get.pivotBlock)).abs
      val shouldResume = saved.exists(s => s.accountCursors.nonEmpty && drift <= MaxPreservedPivotDistance)
      shouldResume shouldBe false // drift too large — must NOT resume

      // Controller calls clearProgress when drift is exceeded
      storage.clearProgress(root)
      storage.readProgress(root) shouldBe None // confirmed cleared
    }
}
