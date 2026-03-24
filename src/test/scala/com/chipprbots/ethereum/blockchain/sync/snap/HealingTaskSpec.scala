package com.chipprbots.ethereum.blockchain.sync.snap

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.testing.Tags._

class HealingTaskSpec extends AnyFlatSpec with Matchers {

  // ========================================
  // Task Creation
  // ========================================

  "HealingTask" should "create task with path and hash" taggedAs UnitTest in {
    val path = Seq(ByteString(Array[Byte](0x00)))
    val hash = kec256(ByteString("node1"))
    val rootHash = kec256(ByteString("root"))

    val task = HealingTask(path, hash, rootHash)

    task.path shouldBe path
    task.hash shouldBe hash
    task.rootHash shouldBe rootHash
    task.pending shouldBe true
    task.done shouldBe false
    task.nodeData shouldBe None
  }

  "HealingTask.createTasksFromMissingNodes" should "create tasks from missing node list" taggedAs UnitTest in {
    val rootHash = kec256(ByteString("root"))
    val missingNodes = Seq(
      (Seq(ByteString(Array[Byte](0x00))), kec256(ByteString("node1"))),
      (Seq(ByteString(Array[Byte](0x01))), kec256(ByteString("node2"))),
      (Seq(ByteString(Array[Byte](0x02))), kec256(ByteString("node3")))
    )

    val tasks = HealingTask.createTasksFromMissingNodes(missingNodes, rootHash)

    tasks.size shouldBe 3
    tasks.foreach(_.rootHash shouldBe rootHash)
    tasks(0).hash shouldBe kec256(ByteString("node1"))
    tasks(1).hash shouldBe kec256(ByteString("node2"))
    tasks(2).hash shouldBe kec256(ByteString("node3"))
  }

  it should "create empty list from empty input" taggedAs UnitTest in {
    val tasks = HealingTask.createTasksFromMissingNodes(Seq.empty, kec256(ByteString("root")))
    tasks shouldBe empty
  }

  // ========================================
  // Progress
  // ========================================

  it should "report 0.0 progress when pending" taggedAs UnitTest in {
    val task = HealingTask(
      Seq(ByteString(Array[Byte](0x00))),
      kec256(ByteString("node")),
      kec256(ByteString("root"))
    )

    task.progress shouldBe 0.0
  }

  it should "report 0.5 progress when active (not pending)" taggedAs UnitTest in {
    val task = HealingTask(
      Seq(ByteString(Array[Byte](0x00))),
      kec256(ByteString("node")),
      kec256(ByteString("root"))
    )
    task.pending = false

    task.progress shouldBe 0.5
  }

  it should "report 0.9 progress when node data received but not done" taggedAs UnitTest in {
    val task = HealingTask(
      Seq(ByteString(Array[Byte](0x00))),
      kec256(ByteString("node")),
      kec256(ByteString("root"))
    )
    task.pending = false
    task.nodeData = Some(ByteString("node-data"))

    task.progress shouldBe 0.9
  }

  it should "report 1.0 progress when done" taggedAs UnitTest in {
    val task = HealingTask(
      Seq(ByteString(Array[Byte](0x00))),
      kec256(ByteString("node")),
      kec256(ByteString("root"))
    )
    task.done = true

    task.progress shouldBe 1.0
  }

  // ========================================
  // Short String
  // ========================================

  it should "format short string for root path" taggedAs UnitTest in {
    val task = HealingTask(
      Seq.empty,
      kec256(ByteString("node")),
      kec256(ByteString("root"))
    )

    task.toShortString should include("root")
    task.toShortString should include("pending")
  }

  it should "format short string with depth" taggedAs UnitTest in {
    val task = HealingTask(
      Seq(ByteString(Array[Byte](0x00)), ByteString(Array[Byte](0x01))),
      kec256(ByteString("node")),
      kec256(ByteString("root"))
    )

    task.toShortString should include("depth=2")
  }

  it should "show correct status in short string" taggedAs UnitTest in {
    val task = HealingTask(
      Seq(ByteString(Array[Byte](0x00))),
      kec256(ByteString("node")),
      kec256(ByteString("root"))
    )

    task.toShortString should include("pending")

    task.pending = false
    task.toShortString should include("active")

    task.done = true
    task.toShortString should include("done")
  }

  // ========================================
  // Constants
  // ========================================

  "HealingTask constants" should "have correct default values" taggedAs UnitTest in {
    HealingTask.DEFAULT_BATCH_SIZE shouldBe 16
    HealingTask.MAX_HEALING_ITERATIONS shouldBe 10
  }
}
