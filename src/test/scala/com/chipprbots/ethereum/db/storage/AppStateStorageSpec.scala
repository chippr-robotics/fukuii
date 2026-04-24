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
  }

  trait Fixtures {
    def newAppStateStorage(): AppStateStorage = new AppStateStorage(EphemDataSource())
  }

}
