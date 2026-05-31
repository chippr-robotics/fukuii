package com.chipprbots.ethereum.blockchain.sync.snap

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.testing.Tags._

class AccountTaskSpec extends AnyFlatSpec with Matchers {

  private val dummyRoot = kec256(ByteString("state-root"))

  "AccountTask.createInitialTasks" should "produce one task covering the full keyspace when concurrency=1" taggedAs UnitTest in {
    val tasks = AccountTask.createInitialTasks(dummyRoot, concurrency = 1)
    tasks.size shouldBe 1
    tasks.head.next shouldBe ByteString(Array.fill(32)(0x00.toByte))
    tasks.head.last shouldBe AccountTask.MaxHash32
  }

  it should "produce N tasks when concurrency=N" taggedAs UnitTest in {
    for (n <- Seq(4, 16, 256)) {
      val tasks = AccountTask.createInitialTasks(dummyRoot, concurrency = n)
      tasks.size shouldBe n
    }
  }

  it should "start the first task at 0x00...00" taggedAs UnitTest in {
    val tasks = AccountTask.createInitialTasks(dummyRoot, concurrency = 16)
    tasks.head.next shouldBe ByteString(Array.fill(32)(0x00.toByte))
  }

  it should "end the last task at 0xFF...FF" taggedAs UnitTest in {
    val tasks = AccountTask.createInitialTasks(dummyRoot, concurrency = 16)
    tasks.last.last shouldBe AccountTask.MaxHash32
  }

  it should "produce non-overlapping contiguous ranges" taggedAs UnitTest in {
    val tasks = AccountTask.createInitialTasks(dummyRoot, concurrency = 16)
    // Each task's `last` equals the next task's `next` (contiguous partition)
    tasks.sliding(2).foreach {
      case Seq(prev, next) =>
        prev.last shouldBe next.next
      case _ => // single-element window — shouldn't happen with sliding(2) on size > 1
    }
  }

  it should "assign rootHash to every task" taggedAs UnitTest in {
    val tasks = AccountTask.createInitialTasks(dummyRoot, concurrency = 8)
    tasks.foreach(_.rootHash shouldBe dummyRoot)
  }

  it should "reject non-positive concurrency" taggedAs UnitTest in {
    a[IllegalArgumentException] should be thrownBy AccountTask.createInitialTasks(dummyRoot, concurrency = 0)
    a[IllegalArgumentException] should be thrownBy AccountTask.createInitialTasks(dummyRoot, concurrency = -1)
  }

  "AccountTask.remainingKeyspace" should "return 0 for a completed task (next == last)" taggedAs UnitTest in {
    val hash = ByteString(Array.fill(32)(0x42.toByte))
    val task = AccountTask(next = hash, last = hash, rootHash = dummyRoot)
    task.remainingKeyspace shouldBe BigInt(0)
  }

  it should "return a positive value for an incomplete task" taggedAs UnitTest in {
    val tasks = AccountTask.createInitialTasks(dummyRoot, concurrency = 16)
    tasks.foreach(t => t.remainingKeyspace should be >= BigInt(0))
    tasks.head.remainingKeyspace should be > BigInt(0)
  }

  "AccountTask.rangeString" should "not throw for any task" taggedAs UnitTest in {
    val tasks = AccountTask.createInitialTasks(dummyRoot, concurrency = 16)
    tasks.foreach(t => noException should be thrownBy t.rangeString)
  }

  "AccountTask.progress" should "return 0.0 for an empty task" taggedAs UnitTest in {
    val task = AccountTask.createInitialTasks(dummyRoot, 1).head
    task.progress shouldBe 0.0
  }

  it should "return 1.0 for a done task" taggedAs UnitTest in {
    val task = AccountTask(
      next = ByteString(Array.fill(32)(0.toByte)),
      last = AccountTask.MaxHash32,
      rootHash = dummyRoot,
      done = true
    )
    task.progress shouldBe 1.0
  }

  it should "return a value between 0 and 1 for a partially-complete task" taggedAs UnitTest in {
    import com.chipprbots.ethereum.domain.Account
    val accounts = (1 to 100).map(i => ByteString(s"acct$i") -> Account(nonce = i, balance = i * 100))
    val task = AccountTask(
      next = ByteString(Array.fill(32)(0.toByte)),
      last = AccountTask.MaxHash32,
      rootHash = dummyRoot,
      accounts = accounts
    )
    task.progress should be > 0.0
    task.progress should be <= 1.0
  }
}
