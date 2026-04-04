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

    "report unclean shutdown when flag was never set" taggedAs (UnitTest, DatabaseTest) in new Fixtures {
      val storage = newAppStateStorage()
      assert(!storage.isCleanShutdown())
    }

    "report clean shutdown after marking it" taggedAs (UnitTest, DatabaseTest) in new Fixtures {
      val storage = newAppStateStorage()
      storage.putCleanShutdown(true).commit()
      assert(storage.isCleanShutdown())
    }

    "write safe-block marker every 64 blocks via putBestBlockNumber" taggedAs (UnitTest, DatabaseTest) in new Fixtures {
      val storage = newAppStateStorage()

      // Block 63: no marker
      storage.putBestBlockNumber(63).commit()
      assert(storage.getLastSafeBlock() == 0)

      // Block 64: marker written
      storage.putBestBlockNumber(64).commit()
      assert(storage.getLastSafeBlock() == 64)

      // Block 100: marker not updated (not a multiple of 64)
      storage.putBestBlockNumber(100).commit()
      assert(storage.getLastSafeBlock() == 64)

      // Block 128: marker updated
      storage.putBestBlockNumber(128).commit()
      assert(storage.getLastSafeBlock() == 128)
    }

    "get zero as last safe block when storage is empty" taggedAs (UnitTest, DatabaseTest) in new Fixtures {
      assert(newAppStateStorage().getLastSafeBlock() == 0)
    }

    "store and retrieve healing pending nodes" taggedAs (UnitTest, DatabaseTest) in new Fixtures {
      val storage = newAppStateStorage()
      assert(storage.getSnapSyncHealingPendingNodes().isEmpty)
      storage.putSnapSyncHealingPendingNodes("aabbccdd:0102,0304\neeff0011:").commit()
      assert(storage.getSnapSyncHealingPendingNodes().contains("aabbccdd:0102,0304\neeff0011:"))
    }

    "return None for healing pending nodes when key is absent" taggedAs (UnitTest, DatabaseTest) in new Fixtures {
      assert(newAppStateStorage().getSnapSyncHealingPendingNodes().isEmpty)
    }

    "return None for healing pending nodes when value is empty string" taggedAs (UnitTest, DatabaseTest) in new Fixtures {
      val storage = newAppStateStorage()
      storage.putSnapSyncHealingPendingNodes("").commit()
      assert(storage.getSnapSyncHealingPendingNodes().isEmpty)
    }

    "store and retrieve healing round counter" taggedAs (UnitTest, DatabaseTest) in new Fixtures {
      val storage = newAppStateStorage()
      assert(storage.getSnapSyncHealingRound() == 0)
      storage.putSnapSyncHealingRound(3).commit()
      assert(storage.getSnapSyncHealingRound() == 3)
    }

    "reset healing round counter to zero" taggedAs (UnitTest, DatabaseTest) in new Fixtures {
      val storage = newAppStateStorage()
      storage.putSnapSyncHealingRound(5).commit()
      storage.putSnapSyncHealingRound(0).commit()
      assert(storage.getSnapSyncHealingRound() == 0)
    }
  }

  trait Fixtures {
    def newAppStateStorage(): AppStateStorage = new AppStateStorage(EphemDataSource())
  }

}
