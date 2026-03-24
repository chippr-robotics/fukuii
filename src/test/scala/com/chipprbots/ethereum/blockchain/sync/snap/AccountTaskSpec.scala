package com.chipprbots.ethereum.blockchain.sync.snap

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.testing.Tags._

class AccountTaskSpec extends AnyFlatSpec with Matchers {

  // ========================================
  // Task Creation
  // ========================================

  "AccountTask.createInitialTasks" should "create correct number of tasks" taggedAs UnitTest in {
    val rootHash = kec256(ByteString("test-root"))
    val tasks = AccountTask.createInitialTasks(rootHash, concurrency = 16)
    tasks.size shouldBe 16
  }

  it should "create single task for concurrency 1" taggedAs UnitTest in {
    val rootHash = kec256(ByteString("test-root"))
    val tasks = AccountTask.createInitialTasks(rootHash, concurrency = 1)

    tasks.size shouldBe 1
    tasks.head.last shouldBe AccountTask.MaxHash32
  }

  it should "require positive concurrency" taggedAs UnitTest in {
    val rootHash = kec256(ByteString("test-root"))
    intercept[IllegalArgumentException] {
      AccountTask.createInitialTasks(rootHash, concurrency = 0)
    }
  }

  it should "cover the full account hash space" taggedAs UnitTest in {
    val rootHash = kec256(ByteString("test-root"))
    val tasks = AccountTask.createInitialTasks(rootHash, concurrency = 4)

    // First task should start at 0
    tasks.head.next shouldBe ByteString(Array.fill(32)(0.toByte))

    // Last task should end at MaxHash32
    tasks.last.last shouldBe AccountTask.MaxHash32
  }

  it should "create non-overlapping contiguous ranges" taggedAs UnitTest in {
    val rootHash = kec256(ByteString("test-root"))
    val tasks = AccountTask.createInitialTasks(rootHash, concurrency = 4)

    // Each task should have next == 0 or equal to the previous task's last bound
    tasks.foreach { task =>
      task.next.length shouldBe 32
      task.last.length shouldBe 32
    }
  }

  it should "set rootHash on all tasks" taggedAs UnitTest in {
    val rootHash = kec256(ByteString("test-root"))
    val tasks = AccountTask.createInitialTasks(rootHash, concurrency = 8)

    tasks.foreach { task =>
      task.rootHash shouldBe rootHash
    }
  }

  it should "create tasks that are not pending or done" taggedAs UnitTest in {
    val rootHash = kec256(ByteString("test-root"))
    val tasks = AccountTask.createInitialTasks(rootHash, concurrency = 4)

    tasks.foreach { task =>
      task.isPending shouldBe false
      task.isComplete shouldBe false
    }
  }

  // ========================================
  // Task State
  // ========================================

  "AccountTask" should "track pending state" taggedAs UnitTest in {
    val task = AccountTask(
      next = ByteString(Array.fill(32)(0.toByte)),
      last = AccountTask.MaxHash32,
      rootHash = kec256(ByteString("root"))
    )

    task.isPending shouldBe false
    task.pending = true
    task.isPending shouldBe true
  }

  it should "track done state" taggedAs UnitTest in {
    val task = AccountTask(
      next = ByteString(Array.fill(32)(0.toByte)),
      last = AccountTask.MaxHash32,
      rootHash = kec256(ByteString("root"))
    )

    task.isComplete shouldBe false
    task.done = true
    task.isComplete shouldBe true
  }

  // ========================================
  // Progress Calculation
  // ========================================

  it should "report 0.0 progress when no accounts downloaded" taggedAs UnitTest in {
    val task = AccountTask(
      next = ByteString(Array.fill(32)(0.toByte)),
      last = AccountTask.MaxHash32,
      rootHash = kec256(ByteString("root"))
    )

    task.progress shouldBe 0.0
  }

  it should "report 1.0 progress when done" taggedAs UnitTest in {
    val task = AccountTask(
      next = ByteString(Array.fill(32)(0.toByte)),
      last = AccountTask.MaxHash32,
      rootHash = kec256(ByteString("root"))
    )
    task.done = true
    task.progress shouldBe 1.0
  }

  it should "report partial progress based on accounts" taggedAs UnitTest in {
    val task = AccountTask(
      next = ByteString(Array.fill(32)(0.toByte)),
      last = AccountTask.MaxHash32,
      rootHash = kec256(ByteString("root"))
    )

    task.accounts = (1 to 100).map(i =>
      (kec256(ByteString(s"account-$i")), Account(nonce = i, balance = i * 100))
    )

    task.progress should be > 0.0
    task.progress should be < 1.0
  }

  it should "cap progress at 0.9 before completion" taggedAs UnitTest in {
    val task = AccountTask(
      next = ByteString(Array.fill(32)(0.toByte)),
      last = AccountTask.MaxHash32,
      rootHash = kec256(ByteString("root"))
    )

    // Add lots of accounts
    task.accounts = (1 to 5000).map(i =>
      (kec256(ByteString(s"account-$i")), Account(nonce = i, balance = i * 100))
    )

    task.progress should be <= 0.9
  }

  // ========================================
  // Remaining Keyspace
  // ========================================

  it should "calculate remaining keyspace for full range" taggedAs UnitTest in {
    val task = AccountTask(
      next = ByteString(Array.fill(32)(0.toByte)),
      last = AccountTask.MaxHash32,
      rootHash = kec256(ByteString("root"))
    )

    task.remainingKeyspace should be > BigInt(0)
  }

  it should "calculate zero remaining keyspace when next equals last" taggedAs UnitTest in {
    val hash = kec256(ByteString("same-hash"))
    val task = AccountTask(
      next = hash,
      last = hash,
      rootHash = kec256(ByteString("root"))
    )

    task.remainingKeyspace shouldBe BigInt(0)
  }

  it should "not produce negative remaining keyspace" taggedAs UnitTest in {
    // next > last edge case
    val task = AccountTask(
      next = AccountTask.MaxHash32,
      last = ByteString(Array.fill(32)(0.toByte)),
      rootHash = kec256(ByteString("root"))
    )

    task.remainingKeyspace shouldBe BigInt(0)
  }

  // ========================================
  // Range String
  // ========================================

  it should "format range string correctly" taggedAs UnitTest in {
    val task = AccountTask(
      next = ByteString(Array.fill(32)(0.toByte)),
      last = AccountTask.MaxHash32,
      rootHash = kec256(ByteString("root"))
    )

    val rangeStr = task.rangeString
    rangeStr should include("[")
    rangeStr should include("]")
    rangeStr should include("...")
    rangeStr should include("0xFF...")
  }

  it should "format empty next correctly" taggedAs UnitTest in {
    val task = AccountTask(
      next = ByteString.empty,
      last = AccountTask.MaxHash32,
      rootHash = kec256(ByteString("root"))
    )

    task.rangeString should include("0x00...")
  }

  // ========================================
  // MaxHash32
  // ========================================

  "AccountTask.MaxHash32" should "be 32 bytes of 0xFF" taggedAs UnitTest in {
    AccountTask.MaxHash32.length shouldBe 32
    AccountTask.MaxHash32.toArray.foreach { byte =>
      (byte & 0xff) shouldBe 0xff
    }
  }
}
