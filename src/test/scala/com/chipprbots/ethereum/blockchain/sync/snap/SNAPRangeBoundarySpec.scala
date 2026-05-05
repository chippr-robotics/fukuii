package com.chipprbots.ethereum.blockchain.sync.snap

import org.apache.pekko.util.ByteString

import org.scalacheck.Gen
import org.scalatest.funsuite.AnyFunSuite
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

import com.chipprbots.ethereum.testing.Tags._

/** Property-based range boundary tests for AccountTask and StorageTask.
  *
  * AccountTaskSpec and StorageTaskSpec cover individual methods at fixed inputs. This file adds property-based coverage
  * for the invariants that must hold across all valid inputs: partition completeness, keyspace clamping, and
  * createContinuation arithmetic at boundary values.
  */
class SNAPRangeBoundarySpec extends AnyFunSuite with ScalaCheckPropertyChecks {

  private val dummyRoot: ByteString = ByteString(Array.fill(32)(0xab.toByte))
  private val zeroHash: ByteString = ByteString(Array.fill(32)(0x00.toByte))
  private val maxHash: ByteString = AccountTask.MaxHash32

  // -----------------------------------------------------------------------
  // AccountTask — property: partition invariants for any concurrency 1-64
  // -----------------------------------------------------------------------

  test("createInitialTasks: first task starts at 0x00..00 for any concurrency", UnitTest, SyncTest) {
    forAll(Gen.choose(1, 64)) { (n: Int) =>
      val tasks = AccountTask.createInitialTasks(dummyRoot, n)
      assert(tasks.head.next == zeroHash, s"First task at concurrency=$n starts at ${tasks.head.next} not zero")
    }
  }

  test("createInitialTasks: last task ends at MaxHash for any concurrency", UnitTest, SyncTest) {
    forAll(Gen.choose(1, 64)) { (n: Int) =>
      val tasks = AccountTask.createInitialTasks(dummyRoot, n)
      assert(tasks.last.last == maxHash, s"Last task at concurrency=$n ends at ${tasks.last.last} not MaxHash")
    }
  }

  test("createInitialTasks: ranges are perfectly contiguous for any concurrency", UnitTest, SyncTest) {
    forAll(Gen.choose(2, 64)) { (n: Int) =>
      val tasks = AccountTask.createInitialTasks(dummyRoot, n)
      tasks.sliding(2).foreach { pair =>
        val a = pair.head
        val b = pair(1)
        assert(a.last == b.next, s"At concurrency=$n: gap between ${a.rangeString} and ${b.rangeString}")
      }
    }
  }

  test("createInitialTasks: all tasks have strictly positive remainingKeyspace", UnitTest, SyncTest) {
    forAll(Gen.choose(1, 64)) { (n: Int) =>
      val tasks = AccountTask.createInitialTasks(dummyRoot, n)
      tasks.foreach { t =>
        assert(t.remainingKeyspace > BigInt(0), s"Task ${t.rangeString} at concurrency=$n has zero remaining keyspace")
      }
    }
  }

  test("createInitialTasks: all tasks initially not done, not pending, requeueCount=0", UnitTest, SyncTest) {
    forAll(Gen.choose(1, 32)) { (n: Int) =>
      val tasks = AccountTask.createInitialTasks(dummyRoot, n)
      tasks.foreach { t =>
        assert(
          !t.done && !t.pending && t.requeueCount == 0,
          s"Task ${t.rangeString} has unexpected initial state at concurrency=$n"
        )
      }
    }
  }

  // -----------------------------------------------------------------------
  // AccountTask — remainingKeyspace clamping when next >= last
  // -----------------------------------------------------------------------

  test("remainingKeyspace is 0 when next == last (empty range)", UnitTest, SyncTest) {
    val h = ByteString(Array.fill(32)(0x42.toByte))
    val t = AccountTask(next = h, last = h, rootHash = dummyRoot)
    assert(t.remainingKeyspace == BigInt(0))
  }

  test("remainingKeyspace clamps to 0 when next > last (inverted range)", UnitTest, SyncTest) {
    // next=MaxHash, last=ZeroHash is an inverted range; clamping must return 0, not negative
    val t = AccountTask(next = maxHash, last = zeroHash, rootHash = dummyRoot)
    assert(t.remainingKeyspace == BigInt(0), s"Expected 0 for inverted range, got ${t.remainingKeyspace}")
  }

  // -----------------------------------------------------------------------
  // StorageTask — createContinuation: increment arithmetic at mid-range values
  // -----------------------------------------------------------------------

  test("createContinuation: next advances by exactly 1 from the last-slot hash", UnitTest, SyncTest) {
    val original = StorageTask.createStorageTask(
      accountHash = ByteString(Array.fill(32)(0xaa.toByte)),
      storageRoot = dummyRoot
    )
    // Use a mid-range value where the increment is unambiguous
    val lastSlot = ByteString(Array.fill(31)(0x00.toByte) :+ 0x10.toByte)
    val cont = StorageTask.createContinuation(original, lastSlot)
    val expected = ByteString(Array.fill(31)(0x00.toByte) :+ 0x11.toByte)
    assert(cont.next == expected, s"Expected next=${expected}, got ${cont.next}")
  }

  test("createContinuation: preserves last, accountHash, storageRoot from original", UnitTest, SyncTest) {
    val acct = ByteString(Array.fill(32)(0xcc.toByte))
    val sroot = ByteString(Array.fill(32)(0xdd.toByte))
    val original = StorageTask.createStorageTask(acct, sroot)
    val cont = StorageTask.createContinuation(original, zeroHash)
    assert(cont.last == original.last)
    assert(cont.accountHash == acct)
    assert(cont.storageRoot == sroot)
  }
}
