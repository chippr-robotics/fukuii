package com.chipprbots.ethereum.blockchain.sync

import org.scalatest.ParallelTestExecution
import org.scalatest.funsuite.AnyFunSuite

import com.chipprbots.ethereum.testing.Tags.*

/** Tests the pure [[SyncController.selectSyncMode]] startup function: SNAP when `do-snap-sync` is set, otherwise
  * regular sync.
  *
  * Fast sync was removed, and with it the SNAP→Fast downgrade when fewer than 3 snap-capable peers were seen. The
  * function no longer takes peer counts: SNAP handles small pools itself, and when it cannot proceed it goes dormant
  * and retries on a fresh pivot (see `SNAPDormantRecoverySpec`).
  */
class SyncStartupStrategySpec extends AnyFunSuite with ParallelTestExecution with TestSyncConfig:

  import SyncController.SyncMode
  import SyncController.selectSyncMode

  test("returns Snap when do-snap-sync is set", UnitTest) {
    assert(selectSyncMode(defaultSyncConfig.copy(doSnapSync = true)) == SyncMode.Snap)
  }

  test("returns Regular when do-snap-sync is off", UnitTest) {
    assert(selectSyncMode(defaultSyncConfig.copy(doSnapSync = false)) == SyncMode.Regular)
  }
