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
      val storage   = newAppStateStorage()
      val root      = ByteString(Array.fill[Byte](32)(0xde.toByte))
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
      val root    = ByteString(Array.fill[Byte](32)(0xca.toByte))

      // Fresh: nothing persisted → not in-progress, not done
      assert(!storage.isSnapSyncInProgress())
      assert(!storage.isSnapSyncDone())

      // Pivot chosen (before first AccountRangeProgress message) → in-progress
      storage.putSnapSyncPivotBlock(BigInt(18_000_000)).commit()
      assert(storage.isSnapSyncInProgress())
      assert(!storage.isSnapSyncDone())

      // Progress accumulated — pivot + state-root + progress all present
      storage.putSnapSyncStateRoot(root).and(
        storage.putSnapSyncProgress("""{"phase":"AccountRangeSync","accountsSynced":500000}""")
      ).commit()
      assert(storage.isSnapSyncInProgress())
      assert(!storage.isSnapSyncDone())

      // Phase completions written
      storage.putSnapSyncAccountsComplete(true)
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
  }

  trait Fixtures {
    def newAppStateStorage(): AppStateStorage = new AppStateStorage(EphemDataSource())
  }

}
