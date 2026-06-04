package com.chipprbots.ethereum.db.storage

import org.apache.pekko.util.ByteString

import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

import com.chipprbots.ethereum.ObjectGenerators
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.testing.Tags._

class AppStateStorageSpec extends AnyWordSpec with ScalaCheckPropertyChecks with ObjectGenerators {

  "AppStateStorage" should {

    "insert and get best block number properly" taggedAs (UnitTest, DatabaseTest) in new Fixtures {
      forAll(ObjectGenerators.bigIntGen) { bestBlockNumber =>
        val storage = newAppStateStorage()
        storage.putBestBlockNumber(bestBlockNumber).commit()

        assert(storage.getBestBlockNumber() == bestBlockNumber)
      }
    }

    "get zero as best block number when storage is empty" taggedAs (UnitTest, DatabaseTest) in new Fixtures {
      assert(newAppStateStorage().getBestBlockNumber() == 0)
    }

    "insert and get fast sync done properly" taggedAs (UnitTest, DatabaseTest) in new Fixtures {
      val storage: AppStateStorage = newAppStateStorage()
      storage.fastSyncDone().commit()

      assert(storage.isFastSyncDone())
    }

    "get fast sync done false when storage is empty" taggedAs (UnitTest, DatabaseTest) in new Fixtures {
      assert(!newAppStateStorage().isFastSyncDone())
    }

    // Bug 30 escape valve: regular sync stuck on missing state nodes triggers a re-pivot via SNAP.
    // SyncController clears both *SyncDone flags so start() re-evaluates and enters SNAP rather
    // than landing in `do-fast-sync is true but fast sync already completed` → regular sync loop.
    "round-trip clearFastSyncDone — flag is unset after clear" taggedAs (UnitTest, DatabaseTest) in new Fixtures {
      val storage: AppStateStorage = newAppStateStorage()
      storage.fastSyncDone().commit()
      assert(storage.isFastSyncDone())

      storage.clearFastSyncDone().commit()
      assert(!storage.isFastSyncDone())
    }

    "clearFastSyncDone is idempotent on empty storage" taggedAs (UnitTest, DatabaseTest) in new Fixtures {
      val storage: AppStateStorage = newAppStateStorage()
      assert(!storage.isFastSyncDone())

      storage.clearFastSyncDone().commit()
      assert(!storage.isFastSyncDone())
    }

    "round-trip clearSnapSyncDone — flag is unset after clear" taggedAs (UnitTest, DatabaseTest) in new Fixtures {
      val storage: AppStateStorage = newAppStateStorage()
      storage.snapSyncDone().commit()
      assert(storage.isSnapSyncDone())

      storage.clearSnapSyncDone().commit()
      assert(!storage.isSnapSyncDone())
    }

    "clearSnapSyncDone is idempotent on empty storage" taggedAs (UnitTest, DatabaseTest) in new Fixtures {
      val storage: AppStateStorage = newAppStateStorage()
      assert(!storage.isSnapSyncDone())

      storage.clearSnapSyncDone().commit()
      assert(!storage.isSnapSyncDone())
    }

    // Bug 30 escape valve clears BOTH flags so SyncController.start() re-enters SNAP. Verify
    // the two clears are independent — clearing one does not affect the other.
    "clearSnapSyncDone leaves FastSyncDone untouched" taggedAs (UnitTest, DatabaseTest) in new Fixtures {
      val storage: AppStateStorage = newAppStateStorage()
      storage.snapSyncDone().commit()
      storage.fastSyncDone().commit()

      storage.clearSnapSyncDone().commit()
      assert(!storage.isSnapSyncDone())
      assert(storage.isFastSyncDone())
    }

    "clearFastSyncDone leaves SnapSyncDone untouched" taggedAs (UnitTest, DatabaseTest) in new Fixtures {
      val storage: AppStateStorage = newAppStateStorage()
      storage.snapSyncDone().commit()
      storage.fastSyncDone().commit()

      storage.clearFastSyncDone().commit()
      assert(storage.isSnapSyncDone())
      assert(!storage.isFastSyncDone())
    }

    "insert and get estimated highest block properly" taggedAs (UnitTest, DatabaseTest) in new Fixtures {
      forAll(ObjectGenerators.bigIntGen) { estimatedHighestBlock =>
        val storage = newAppStateStorage()
        storage.putEstimatedHighestBlock(estimatedHighestBlock).commit()

        assert(storage.getEstimatedHighestBlock() == estimatedHighestBlock)
      }
    }

    "get zero as estimated highest block when storage is empty" taggedAs (UnitTest, DatabaseTest) in new Fixtures {
      assert(newAppStateStorage().getEstimatedHighestBlock() == 0)
    }

    "insert and get sync starting block properly" taggedAs (UnitTest, DatabaseTest) in new Fixtures {
      forAll(ObjectGenerators.bigIntGen) { syncStartingBlock =>
        val storage = newAppStateStorage()
        storage.putSyncStartingBlock(syncStartingBlock).commit()

        assert(storage.getSyncStartingBlock() == syncStartingBlock)
      }
    }

    "get zero as sync starting block when storage is empty" taggedAs (UnitTest, DatabaseTest) in new Fixtures {
      assert(newAppStateStorage().getSyncStartingBlock() == 0)
    }

    "insert and get bootstrap pivot block properly" taggedAs (UnitTest, DatabaseTest) in new Fixtures {
      val storage = newAppStateStorage()
      val pivotNumber = BigInt(10500000)
      val pivotHash = ByteString(Array.fill[Byte](32)(0xab.toByte))

      storage.putBootstrapPivotBlock(pivotNumber, pivotHash).commit()

      assert(storage.getBootstrapPivotBlock() == pivotNumber)
      assert(storage.getBootstrapPivotBlockHash() == pivotHash)
    }

    "get zero and empty hash for bootstrap pivot when storage is empty" taggedAs (
      UnitTest,
      DatabaseTest
    ) in new Fixtures {
      val storage = newAppStateStorage()
      assert(storage.getBootstrapPivotBlock() == 0)
      assert(storage.getBootstrapPivotBlockHash() == ByteString.empty)
    }

    "insert and get SNAP sync progress properly" taggedAs (UnitTest, DatabaseTest) in new Fixtures {
      val storage = newAppStateStorage()
      val progressJson = """{"phase":"AccountRangeSync","accountsSynced":1000,"bytecodes":0}"""

      storage.putSnapSyncProgress(progressJson).commit()

      val retrieved = storage.getSnapSyncProgress()
      assert(retrieved.isDefined)
      assert(retrieved.get == progressJson)
    }

    "get None for SNAP sync progress when storage is empty" taggedAs (UnitTest, DatabaseTest) in new Fixtures {
      val storage = newAppStateStorage()
      assert(storage.getSnapSyncProgress().isEmpty)
    }

    // Bug 28: the DB consistency check in StdNode.runDBConsistencyCheck depends on this
    // helper. Locking its semantics so a future refactor can't silently revert.
    "report SNAP sync in-progress only when progress is persisted but not yet done" taggedAs (
      UnitTest,
      DatabaseTest
    ) in new Fixtures {
      val storage = newAppStateStorage()

      // Empty storage — neither in-progress nor done.
      assert(!storage.isSnapSyncInProgress())
      assert(!storage.isSnapSyncDone())

      // Progress persisted but not done — in-progress.
      storage.putSnapSyncProgress("""{"phase":"AccountRangeSync","accountsSynced":500}""").commit()
      assert(storage.isSnapSyncInProgress())
      assert(!storage.isSnapSyncDone())

      // Marked done (progress may still be present; done-ness wins).
      storage.snapSyncDone().commit()
      assert(!storage.isSnapSyncInProgress())
      assert(storage.isSnapSyncDone())
    }

    "not report in-progress when SNAP has finished (progress absent, done set)" taggedAs (
      UnitTest,
      DatabaseTest
    ) in new Fixtures {
      val storage = newAppStateStorage()
      storage.snapSyncDone().commit()
      assert(!storage.isSnapSyncInProgress())
      assert(storage.isSnapSyncDone())
    }

    // Bug 28 early-window scenario (Copilot feedback): between pivot selection and the first
    // `AccountRangeProgress` write, only SnapSyncPivotBlock / SnapSyncStateRoot are set. If a
    // restart lands in this window, the consistency check must still skip — otherwise the
    // mis-shutdown returns.
    "report SNAP sync in-progress as soon as the pivot block is persisted (pre-progress window)" taggedAs (
      UnitTest,
      DatabaseTest
    ) in new Fixtures {
      val storage = newAppStateStorage()
      storage.putSnapSyncPivotBlock(BigInt(123456)).commit()
      assert(storage.isSnapSyncInProgress())
      assert(!storage.isSnapSyncDone())
    }

    "report SNAP sync in-progress as soon as the pivot state-root is persisted" taggedAs (
      UnitTest,
      DatabaseTest
    ) in new Fixtures {
      val storage = newAppStateStorage()
      storage.putSnapSyncStateRoot(ByteString(Array.fill[Byte](32)(0xab.toByte))).commit()
      assert(storage.isSnapSyncInProgress())
      assert(!storage.isSnapSyncDone())
    }

    // ---- J10: SNAP phase completion flag round-trips ----------------------------

    "round-trip isSnapSyncAccountsComplete flag" taggedAs (UnitTest, DatabaseTest) in new Fixtures {
      val storage = newAppStateStorage()
      assert(!storage.isSnapSyncAccountsComplete())
      storage.putSnapSyncAccountsComplete(true).commit()
      assert(storage.isSnapSyncAccountsComplete())
      storage.putSnapSyncAccountsComplete(false).commit()
      assert(!storage.isSnapSyncAccountsComplete())
    }

    "round-trip isSnapSyncStorageComplete flag" taggedAs (UnitTest, DatabaseTest) in new Fixtures {
      val storage = newAppStateStorage()
      assert(!storage.isSnapSyncStorageComplete())
      storage.putSnapSyncStorageComplete(true).commit()
      assert(storage.isSnapSyncStorageComplete())
    }

    "round-trip isSnapSyncBytecodeComplete flag" taggedAs (UnitTest, DatabaseTest) in new Fixtures {
      val storage = newAppStateStorage()
      assert(!storage.isSnapSyncBytecodeComplete())
      storage.putSnapSyncBytecodeComplete(true).commit()
      assert(storage.isSnapSyncBytecodeComplete())
    }

    "phase completion flags are independent — setting one does not affect the others" taggedAs (
      UnitTest,
      DatabaseTest
    ) in new Fixtures {
      val storage = newAppStateStorage()
      storage.putSnapSyncAccountsComplete(true).commit()
      assert(storage.isSnapSyncAccountsComplete())
      assert(!storage.isSnapSyncStorageComplete())
      assert(!storage.isSnapSyncBytecodeComplete())

      storage.putSnapSyncStorageComplete(true).commit()
      assert(storage.isSnapSyncAccountsComplete())
      assert(storage.isSnapSyncStorageComplete())
      assert(!storage.isSnapSyncBytecodeComplete())
    }

    "persist and retrieve codeHashes file path" taggedAs (UnitTest, DatabaseTest) in new Fixtures {
      val storage = newAppStateStorage()
      assert(storage.getSnapSyncCodeHashesPath().isEmpty)
      storage.putSnapSyncCodeHashesPath("/data/tmp/snap-code-hashes-abc123.bin").commit()
      assert(storage.getSnapSyncCodeHashesPath() == Some("/data/tmp/snap-code-hashes-abc123.bin"))
    }

    "persist and retrieve storage file path" taggedAs (UnitTest, DatabaseTest) in new Fixtures {
      val storage = newAppStateStorage()
      assert(storage.getSnapSyncStorageFilePath().isEmpty)
      storage.putSnapSyncStorageFilePath("/data/tmp/contract-storage.bin").commit()
      assert(storage.getSnapSyncStorageFilePath() == Some("/data/tmp/contract-storage.bin"))
    }

    "persist and retrieve finalized state root" taggedAs (UnitTest, DatabaseTest) in new Fixtures {
      val storage = newAppStateStorage()
      val root = ByteString(Array.fill[Byte](32)(0xde.toByte))
      assert(storage.getSnapSyncFinalizedRoot().isEmpty)
      storage.putSnapSyncFinalizedRoot(root).commit()
      assert(storage.getSnapSyncFinalizedRoot() == Some(root))
    }

    // J10: full lifecycle regression — models crash-recovery restart detection.
    // SNAPSyncController uses isSnapSyncInProgress() at startup to determine whether
    // to resume (resume path) or start fresh (clean path). This test locks the lifecycle
    // so a storage refactor cannot silently break the recovery guard.
    "SNAP lifecycle: fresh → pivot → progress → done → cleared" taggedAs (UnitTest, DatabaseTest) in new Fixtures {
      val storage = newAppStateStorage()
      val root = ByteString(Array.fill[Byte](32)(0xca.toByte))

      // Fresh: nothing persisted → not in-progress, not done
      assert(!storage.isSnapSyncInProgress())
      assert(!storage.isSnapSyncDone())

      // Pivot chosen (before first AccountRangeProgress message) → in-progress
      storage.putSnapSyncPivotBlock(BigInt(18_000_000)).commit()
      assert(storage.isSnapSyncInProgress())
      assert(!storage.isSnapSyncDone())

      // Progress accumulated — pivot + state-root + progress all present
      storage
        .putSnapSyncStateRoot(root)
        .and(
          storage.putSnapSyncProgress("""{"phase":"AccountRangeSync","accountsSynced":500000}""")
        )
        .commit()
      assert(storage.isSnapSyncInProgress())
      assert(!storage.isSnapSyncDone())

      // Phase completions written
      storage
        .putSnapSyncAccountsComplete(true)
        .and(storage.putSnapSyncStorageComplete(true))
        .and(storage.putSnapSyncBytecodeComplete(true))
        .commit()
      assert(storage.isSnapSyncInProgress()) // still in-progress until Done flag

      // Healing finishes → SnapSyncDone flag set
      storage.snapSyncDone().commit()
      assert(!storage.isSnapSyncInProgress()) // Done wins
      assert(storage.isSnapSyncDone())

      // Bug-30 escape: clear both Done flags so SyncController re-enters SNAP from scratch
      storage.clearSnapSyncDone().and(storage.clearFastSyncDone()).commit()
      assert(!storage.isSnapSyncDone())
      // pivotBlock + stateRoot still present → still in-progress (resume path, not fresh path)
      assert(storage.isSnapSyncInProgress())
    }

    // ========================================
    // Backfill cursors (#1169)
    // ========================================

    "round-trip backfill target / header / body / receipt cursors" taggedAs (UnitTest, DatabaseTest) in new Fixtures {
      val storage = newAppStateStorage()

      // Empty storage — every getter returns 0.
      assert(storage.getBackfillTarget() == 0)
      assert(storage.getBackfillBestHeader() == 0)
      assert(storage.getBackfillBestBody() == 0)
      assert(storage.getBackfillBestReceipt() == 0)

      storage
        .putBackfillTarget(BigInt(19_250_000))
        .and(storage.putBackfillBestHeader(BigInt(8_000_000)))
        .and(storage.putBackfillBestBody(BigInt(7_500_000)))
        .and(storage.putBackfillBestReceipt(BigInt(7_000_000)))
        .commit()

      assert(storage.getBackfillTarget() == BigInt(19_250_000))
      assert(storage.getBackfillBestHeader() == BigInt(8_000_000))
      assert(storage.getBackfillBestBody() == BigInt(7_500_000))
      assert(storage.getBackfillBestReceipt() == BigInt(7_000_000))
    }

    "clearBackfillCursors removes all four backfill keys" taggedAs (UnitTest, DatabaseTest) in new Fixtures {
      val storage = newAppStateStorage()
      storage
        .putBackfillTarget(BigInt(100))
        .and(storage.putBackfillBestHeader(BigInt(50)))
        .and(storage.putBackfillBestBody(BigInt(40)))
        .and(storage.putBackfillBestReceipt(BigInt(30)))
        .commit()

      storage.clearBackfillCursors().commit()

      assert(storage.getBackfillTarget() == 0)
      assert(storage.getBackfillBestHeader() == 0)
      assert(storage.getBackfillBestBody() == 0)
      assert(storage.getBackfillBestReceipt() == 0)
    }

    "needsBackfillResume — false when SNAP is not done" taggedAs (UnitTest, DatabaseTest) in new Fixtures {
      val storage = newAppStateStorage()
      // Even with cursors set, no SNAP-done flag means no resume.
      storage.putBackfillTarget(BigInt(100)).commit()
      assert(!storage.needsBackfillResume())
    }

    "needsBackfillResume — false when no target was persisted" taggedAs (UnitTest, DatabaseTest) in new Fixtures {
      val storage = newAppStateStorage()
      storage.snapSyncDone().commit()
      assert(!storage.needsBackfillResume())
    }

    "needsBackfillResume — false when all cursors have reached target" taggedAs (
      UnitTest,
      DatabaseTest
    ) in new Fixtures {
      val storage = newAppStateStorage()
      storage.snapSyncDone().commit()
      val target = BigInt(1000)
      storage
        .putBackfillTarget(target)
        .and(storage.putBackfillBestHeader(target))
        .and(storage.putBackfillBestBody(target))
        .and(storage.putBackfillBestReceipt(target))
        .commit()
      assert(!storage.needsBackfillResume())
    }

    "needsBackfillResume — true when any cursor lags target" taggedAs (UnitTest, DatabaseTest) in new Fixtures {
      val storage = newAppStateStorage()
      storage.snapSyncDone().commit()
      val target = BigInt(1000)

      // Header behind.
      storage
        .putBackfillTarget(target)
        .and(storage.putBackfillBestHeader(BigInt(500)))
        .and(storage.putBackfillBestBody(target))
        .and(storage.putBackfillBestReceipt(target))
        .commit()
      assert(storage.needsBackfillResume())

      // Now header caught up but body behind.
      storage
        .putBackfillBestHeader(target)
        .and(storage.putBackfillBestBody(BigInt(800)))
        .commit()
      assert(storage.needsBackfillResume())

      // Body caught up but receipt behind.
      storage
        .putBackfillBestBody(target)
        .and(storage.putBackfillBestReceipt(BigInt(600)))
        .commit()
      assert(storage.needsBackfillResume())

      // All three caught up.
      storage.putBackfillBestReceipt(target).commit()
      assert(!storage.needsBackfillResume())
    }

    // ========================================
    // Resumable recovery scan progress
    // ========================================

    "get None for recovery progress when storage is empty" taggedAs (UnitTest, DatabaseTest) in new Fixtures {
      assert(newAppStateStorage().getRecoveryProgress().isEmpty)
    }

    "round-trip recovery progress (completed shards + gaps)" taggedAs (UnitTest, DatabaseTest) in new Fixtures {
      val storage = newAppStateStorage()
      val root = ByteString(Array.fill[Byte](32)(0xab.toByte))
      val progress = RecoveryProgress(
        scanRoot = root,
        shardCount = 16,
        completedShards = Set(0, 5, 9),
        missingBytecodes = Vector(ByteString(Array.fill[Byte](32)(1)), ByteString(Array.fill[Byte](32)(2))),
        missingStorageTries = Vector(
          ByteString(Array.fill[Byte](32)(3)) -> ByteString(Array.fill[Byte](32)(4))
        )
      )
      storage.putRecoveryProgress(progress).commit()
      assert(storage.getRecoveryProgress().contains(progress))
    }

    // The resume tag (scanRoot + shardCount) must invalidate stale checkpoints: after a pivot
    // refresh (new root) or a partition reconfig (new shardCount), prior shard indices are
    // meaningless, so the tag-validated read returns None to force a correct fresh scan.
    "getRecoveryProgressFor returns Some only on a matching scanRoot + shardCount tag" taggedAs (
      UnitTest,
      DatabaseTest
    ) in new Fixtures {
      val storage = newAppStateStorage()
      val root = ByteString(Array.fill[Byte](32)(0xcd.toByte))
      val other = ByteString(Array.fill[Byte](32)(0xef.toByte))
      val progress = RecoveryProgress(root, 16, Set(1, 2), Vector.empty, Vector.empty)
      storage.putRecoveryProgress(progress).commit()

      assert(storage.getRecoveryProgressFor(root, 16).contains(progress)) // matching tag
      assert(storage.getRecoveryProgressFor(other, 16).isEmpty) // wrong root (pivot refreshed)
      assert(storage.getRecoveryProgressFor(root, 32).isEmpty) // wrong shard count (reconfig)
    }

    "clearRecoveryProgress removes the persisted progress" taggedAs (UnitTest, DatabaseTest) in new Fixtures {
      val storage = newAppStateStorage()
      val root = ByteString(Array.fill[Byte](32)(0x11.toByte))
      storage.putRecoveryProgress(RecoveryProgress(root, 16, Set(0), Vector.empty, Vector.empty)).commit()
      assert(storage.getRecoveryProgress().isDefined)

      storage.clearRecoveryProgress().commit()
      assert(storage.getRecoveryProgress().isEmpty)
    }

    // Flaky-system resilience: a torn/corrupt write must NOT surface as a wrong (partial) gap set —
    // it must read back as None so the caller falls through to a correct fresh scan.
    "getRecoveryProgress returns None for a corrupt stored value" taggedAs (UnitTest, DatabaseTest) in new Fixtures {
      val storage = newAppStateStorage()
      storage.put(AppStateStorage.Keys.RecoveryProgress, "garbage-not-a-real-progress-blob").commit()
      assert(storage.getRecoveryProgress().isEmpty)
    }
  }

  trait Fixtures {
    def newAppStateStorage(): AppStateStorage = new AppStateStorage(EphemDataSource())
  }

}
