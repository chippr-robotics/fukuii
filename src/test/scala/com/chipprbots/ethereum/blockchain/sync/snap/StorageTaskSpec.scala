package com.chipprbots.ethereum.blockchain.sync.snap

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.testing.Tags._

class StorageTaskSpec extends AnyFlatSpec with Matchers {

  // ========================================
  // Task Creation
  // ========================================

  "StorageTask.createStorageTask" should "create task for full storage range" taggedAs UnitTest in {
    val accountHash = kec256(ByteString("account1"))
    val storageRoot = kec256(ByteString("storage-root"))

    val task = StorageTask.createStorageTask(accountHash, storageRoot)

    task.accountHash shouldBe accountHash
    task.storageRoot shouldBe storageRoot
    task.next shouldBe ByteString(Array.fill(32)(0.toByte))
    task.last shouldBe ByteString(Array.fill(32)(0xff.toByte))
    task.isPending shouldBe false
    task.isComplete shouldBe false
  }

  "StorageTask.createStorageTasks" should "create tasks for multiple accounts" taggedAs UnitTest in {
    val accounts = (1 to 5).map { i =>
      (kec256(ByteString(s"account$i")), kec256(ByteString(s"storage-root-$i")))
    }

    val tasks = StorageTask.createStorageTasks(accounts)

    tasks.size shouldBe 5
    tasks.zip(accounts).foreach { case (task, (accHash, storRoot)) =>
      task.accountHash shouldBe accHash
      task.storageRoot shouldBe storRoot
    }
  }

  it should "create empty list for empty accounts" taggedAs UnitTest in {
    val tasks = StorageTask.createStorageTasks(Seq.empty)
    tasks shouldBe empty
  }

  // ========================================
  // Continuation Tasks
  // ========================================

  "StorageTask.createContinuation" should "create continuation from last slot" taggedAs UnitTest in {
    val accountHash = kec256(ByteString("account1"))
    val storageRoot = kec256(ByteString("storage-root"))

    val original = StorageTask.createStorageTask(accountHash, storageRoot)
    val lastSlotHash = kec256(ByteString("last-slot"))
    val continuation = StorageTask.createContinuation(original, lastSlotHash)

    continuation.accountHash shouldBe original.accountHash
    continuation.storageRoot shouldBe original.storageRoot
    continuation.last shouldBe original.last
    continuation.isPending shouldBe false
    continuation.isComplete shouldBe false
  }

  it should "increment the last slot hash by 1" taggedAs UnitTest in {
    val accountHash = kec256(ByteString("account1"))
    val storageRoot = kec256(ByteString("storage-root"))

    val original = StorageTask.createStorageTask(accountHash, storageRoot)
    val lastSlot = ByteString(Array.fill(31)(0.toByte) ++ Array(5.toByte))
    val continuation = StorageTask.createContinuation(original, lastSlot)

    // next should be lastSlot + 1
    continuation.next shouldBe ByteString(Array.fill(31)(0.toByte) ++ Array(6.toByte))
  }

  it should "handle carry propagation in hash increment" taggedAs UnitTest in {
    val accountHash = kec256(ByteString("account1"))
    val storageRoot = kec256(ByteString("storage-root"))

    val original = StorageTask.createStorageTask(accountHash, storageRoot)
    val lastSlot = ByteString(Array.fill(31)(0.toByte) ++ Array(0xff.toByte))
    val continuation = StorageTask.createContinuation(original, lastSlot)

    // 0x00...00FF + 1 = 0x00...0100
    val expected = Array.fill(30)(0.toByte) ++ Array(1.toByte, 0.toByte)
    continuation.next shouldBe ByteString(expected)
  }

  it should "require 32-byte hash for continuation" taggedAs UnitTest in {
    val original = StorageTask.createStorageTask(
      kec256(ByteString("account")),
      kec256(ByteString("root"))
    )

    intercept[IllegalArgumentException] {
      StorageTask.createContinuation(original, ByteString("short"))
    }
  }

  // ========================================
  // Task State
  // ========================================

  "StorageTask" should "track pending and done states" taggedAs UnitTest in {
    val task = StorageTask.createStorageTask(
      kec256(ByteString("account")),
      kec256(ByteString("root"))
    )

    task.isPending shouldBe false
    task.isComplete shouldBe false

    task.pending = true
    task.isPending shouldBe true

    task.done = true
    task.isComplete shouldBe true
  }

  // ========================================
  // Progress Calculation
  // ========================================

  it should "report 0.0 progress with no slots" taggedAs UnitTest in {
    val task = StorageTask.createStorageTask(
      kec256(ByteString("account")),
      kec256(ByteString("root"))
    )
    task.progress shouldBe 0.0
  }

  it should "report 1.0 progress when done" taggedAs UnitTest in {
    val task = StorageTask.createStorageTask(
      kec256(ByteString("account")),
      kec256(ByteString("root"))
    )
    task.done = true
    task.progress shouldBe 1.0
  }

  it should "report partial progress based on slots" taggedAs UnitTest in {
    val task = StorageTask.createStorageTask(
      kec256(ByteString("account")),
      kec256(ByteString("root"))
    )

    task.slots = (1 to 50).map(i => (ByteString(s"slot$i"), ByteString(s"value$i")))
    task.progress should be > 0.0
    task.progress should be < 1.0
  }

  it should "cap progress at 0.9" taggedAs UnitTest in {
    val task = StorageTask.createStorageTask(
      kec256(ByteString("account")),
      kec256(ByteString("root"))
    )

    task.slots = (1 to 500).map(i => (ByteString(s"slot$i"), ByteString(s"value$i")))
    task.progress should be <= 0.9
  }

  // ========================================
  // Range String
  // ========================================

  it should "format range string correctly" taggedAs UnitTest in {
    val task = StorageTask.createStorageTask(
      kec256(ByteString("account")),
      kec256(ByteString("root"))
    )

    val range = task.rangeString
    range should include("[")
    range should include("]")
    range should include("...")
  }

  // ========================================
  // Account String
  // ========================================

  it should "format account string as first 4 bytes hex" taggedAs UnitTest in {
    val accountHash = kec256(ByteString("test-account"))
    val task = StorageTask.createStorageTask(accountHash, kec256(ByteString("root")))

    val accStr = task.accountString
    accStr.length shouldBe 8 // 4 bytes = 8 hex chars
    accStr should fullyMatch regex "[0-9a-f]{8}"
  }
}
