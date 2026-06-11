package com.chipprbots.ethereum.blockchain.sync.snap

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.testing.Tags._

class StorageTaskSpec extends AnyFlatSpec with Matchers {

  private val accountHash = kec256(ByteString("account"))
  private val storageRoot = kec256(ByteString("storage-root"))
  private val zeros32 = ByteString(Array.fill(32)(0x00.toByte))
  private val maxHash32 = ByteString(Array.fill(32)(0xff.toByte))

  // ---- createStorageTask ------------------------------------------------

  "StorageTask.createStorageTask" should "cover the full storage keyspace" taggedAs UnitTest in {
    val task = StorageTask.createStorageTask(accountHash, storageRoot)
    task.next shouldBe zeros32
    task.last shouldBe maxHash32
  }

  it should "bind accountHash and storageRoot correctly" taggedAs UnitTest in {
    val task = StorageTask.createStorageTask(accountHash, storageRoot)
    task.accountHash shouldBe accountHash
    task.storageRoot shouldBe storageRoot
  }

  // ---- createStorageTasks -----------------------------------------------

  "StorageTask.createStorageTasks" should "produce one task per account" taggedAs UnitTest in {
    val pairs = (1 to 5).map(i => kec256(ByteString(s"acct$i")) -> kec256(ByteString(s"root$i")))
    val tasks = StorageTask.createStorageTasks(pairs)
    tasks.size shouldBe 5
    tasks.zip(pairs).foreach { case (task, (ah, sr)) =>
      task.accountHash shouldBe ah
      task.storageRoot shouldBe sr
    }
  }

  // ---- createContinuation -----------------------------------------------

  "StorageTask.createContinuation" should "set next = incrementHash32(lastSlotHash)" taggedAs UnitTest in {
    val lastSlot = ByteString(Array.fill(31)(0x00.toByte) :+ 0x41.toByte) // 0x00...041
    val original = StorageTask.createStorageTask(accountHash, storageRoot)
    val cont = StorageTask.createContinuation(original, lastSlot)

    // next should be lastSlot + 1
    val expected = ByteString(Array.fill(31)(0x00.toByte) :+ 0x42.toByte)
    cont.next shouldBe expected
  }

  it should "preserve the original task's last hash" taggedAs UnitTest in {
    val original = StorageTask.createStorageTask(accountHash, storageRoot)
    val cont = StorageTask.createContinuation(original, zeros32)
    cont.last shouldBe original.last
  }

  it should "preserve accountHash and storageRoot from the original" taggedAs UnitTest in {
    val original = StorageTask.createStorageTask(accountHash, storageRoot)
    val cont = StorageTask.createContinuation(original, zeros32)
    cont.accountHash shouldBe accountHash
    cont.storageRoot shouldBe storageRoot
  }

  // ---- incrementHash32 carry propagation ---------------------------------

  "incrementHash32 (via createContinuation)" should "carry across byte boundary" taggedAs UnitTest in {
    // 0x00...00FF -> 0x00...0100
    val lastSlot = ByteString(Array.fill(30)(0x00.toByte) ++ Array(0x00.toByte, 0xff.toByte))
    val original = StorageTask.createStorageTask(accountHash, storageRoot)
    val cont = StorageTask.createContinuation(original, lastSlot)

    val expected = ByteString(Array.fill(30)(0x00.toByte) ++ Array(0x01.toByte, 0x00.toByte))
    cont.next shouldBe expected
  }

  it should "wrap to all-zeros on max hash" taggedAs UnitTest in {
    // 0xFF...FF + 1 wraps to 0x00...00
    val original = StorageTask.createStorageTask(accountHash, storageRoot)
    val cont = StorageTask.createContinuation(original, maxHash32)
    cont.next shouldBe zeros32
  }

  it should "handle multi-byte carry correctly" taggedAs UnitTest in {
    // 0x00...FFFF -> 0x00...010000
    val lastSlot = ByteString(Array.fill(29)(0x00.toByte) ++ Array(0x00.toByte, 0xff.toByte, 0xff.toByte))
    val original = StorageTask.createStorageTask(accountHash, storageRoot)
    val cont = StorageTask.createContinuation(original, lastSlot)

    val expected = ByteString(Array.fill(29)(0x00.toByte) ++ Array(0x01.toByte, 0x00.toByte, 0x00.toByte))
    cont.next shouldBe expected
  }

  // ---- createSubTasks ---------------------------------------------------

  "StorageTask.createSubTasks" should "return a single task when numChunks=1" taggedAs UnitTest in {
    val subtasks = StorageTask.createSubTasks(accountHash, storageRoot, zeros32, maxHash32, 1)
    subtasks.size shouldBe 1
    subtasks.head.next shouldBe zeros32
    subtasks.head.last shouldBe maxHash32
  }

  it should "produce 16 non-overlapping consecutive ranges covering [from, to]" taggedAs UnitTest in {
    val subtasks = StorageTask.createSubTasks(accountHash, storageRoot, zeros32, maxHash32, 16)
    subtasks.size shouldBe 16

    // First task starts at from
    subtasks.head.next shouldBe zeros32
    // Last task ends at to
    subtasks.last.last shouldBe maxHash32

    // Each consecutive pair: previous.last + 1 == next.next
    subtasks.sliding(2).foreach {
      case Seq(a, b) =>
        val aLastPlusOne = StorageTask.incrementHash32(a.last)
        aLastPlusOne shouldBe b.next
      case _ => // single element
    }
  }

  it should "bind accountHash and storageRoot on every subtask" taggedAs UnitTest in {
    val subtasks = StorageTask.createSubTasks(accountHash, storageRoot, zeros32, maxHash32, 4)
    subtasks.foreach { st =>
      st.accountHash shouldBe accountHash
      st.storageRoot shouldBe storageRoot
    }
  }

  it should "cover full keyspace with no gaps and no overlap on boundary values" taggedAs UnitTest in {
    // Partial range: from=0x00...01, to=0x00...10
    val from = ByteString(Array.fill(31)(0x00.toByte) :+ 0x01.toByte)
    val to   = ByteString(Array.fill(31)(0x00.toByte) :+ 0x10.toByte)
    val subtasks = StorageTask.createSubTasks(accountHash, storageRoot, from, to, 4)
    subtasks.size shouldBe 4
    subtasks.head.next shouldBe from
    subtasks.last.last shouldBe to
    subtasks.sliding(2).foreach {
      case Seq(a, b) => StorageTask.incrementHash32(a.last) shouldBe b.next
      case _         =>
    }
  }

  it should "require numChunks > 0" taggedAs UnitTest in {
    an[IllegalArgumentException] should be thrownBy
      StorageTask.createSubTasks(accountHash, storageRoot, zeros32, maxHash32, 0)
  }

  // ---- progress / status ------------------------------------------------

  "StorageTask.progress" should "return 0.0 initially" taggedAs UnitTest in {
    val task = StorageTask.createStorageTask(accountHash, storageRoot)
    task.progress shouldBe 0.0
  }

  it should "return 1.0 when done" taggedAs UnitTest in {
    val task = StorageTask.createStorageTask(accountHash, storageRoot).copy(done = true)
    task.progress shouldBe 1.0
  }

  // ---- rangeString / accountString don't crash --------------------------

  "StorageTask.rangeString" should "not throw" taggedAs UnitTest in {
    val task = StorageTask.createStorageTask(accountHash, storageRoot)
    noException should be thrownBy task.rangeString
  }

  "StorageTask.accountString" should "not throw" taggedAs UnitTest in {
    val task = StorageTask.createStorageTask(accountHash, storageRoot)
    noException should be thrownBy task.accountString
  }
}
