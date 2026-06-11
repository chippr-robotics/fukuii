package com.chipprbots.ethereum.blockchain.sync

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.ParallelTestExecution

import com.chipprbots.ethereum.testing.Tags._

/** The pure target-selection used by the recovery recent-root roll: roll `margin` blocks back from the highest known
  * SNAP-capable peer head, so the target is inside peers' snapshot serve window; decline when no height is known.
  */
class SyncControllerRecentRootSpec extends AnyFunSuite with ParallelTestExecution {

  test("picks margin blocks back from the highest peer head", UnitTest, SyncTest) {
    assert(
      SyncController
        .recentRootTarget(Seq(BigInt(100), BigInt(200), BigInt(150)), margin = BigInt(64))
        .contains(BigInt(136))
    )
  }

  test("no peer height known → None (caller declines the roll)", UnitTest, SyncTest) {
    assert(SyncController.recentRootTarget(Seq.empty[BigInt], margin = BigInt(64)).isEmpty)
    assert(SyncController.recentRootTarget(Seq(BigInt(0), BigInt(0)), margin = BigInt(64)).isEmpty)
  }

  test("ignores zero-height (status-not-yet-arrived) peers", UnitTest, SyncTest) {
    assert(
      SyncController.recentRootTarget(Seq(BigInt(0), BigInt(500), BigInt(0)), margin = BigInt(64)).contains(BigInt(436))
    )
  }

  test("clamps to block 1 when the head is below the margin", UnitTest, SyncTest) {
    assert(SyncController.recentRootTarget(Seq(BigInt(10)), margin = BigInt(64)).contains(BigInt(1)))
  }
}
