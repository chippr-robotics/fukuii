package com.chipprbots.ethereum.blockchain.sync.snap

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.testing.Tags._

class HealingTaskSpec extends AnyFlatSpec with Matchers {

  private val rootHash = kec256(ByteString("trie-root"))
  private val nodeHash1 = kec256(ByteString("node1"))
  private val nodeHash2 = kec256(ByteString("node2"))

  // ---- default state after construction ---------------------------------

  "HealingTask" should "start as pending and not done" taggedAs UnitTest in {
    val task = HealingTask(path = Seq(nodeHash1), hash = nodeHash2, rootHash = rootHash)
    task.pending shouldBe true
    task.done shouldBe false
    task.nodeData shouldBe None
  }

  // ---- progress transitions ---------------------------------------------

  it should "report progress 0.0 when pending and no nodeData" taggedAs UnitTest in {
    val task = HealingTask(Seq(nodeHash1), nodeHash2, rootHash, pending = true, done = false, nodeData = None)
    task.progress shouldBe 0.0
  }

  it should "report progress 0.5 when active (pending=false) and no nodeData" taggedAs UnitTest in {
    val task = HealingTask(Seq(nodeHash1), nodeHash2, rootHash, pending = false, done = false, nodeData = None)
    task.progress shouldBe 0.5
  }

  it should "report progress 0.9 when nodeData is present but not done" taggedAs UnitTest in {
    val task = HealingTask(
      Seq(nodeHash1),
      nodeHash2,
      rootHash,
      pending = false,
      done = false,
      nodeData = Some(ByteString("node-rlp"))
    )
    task.progress shouldBe 0.9
  }

  it should "report progress 1.0 when done" taggedAs UnitTest in {
    val task = HealingTask(
      Seq(nodeHash1),
      nodeHash2,
      rootHash,
      pending = false,
      done = true,
      nodeData = Some(ByteString("node-rlp"))
    )
    task.progress shouldBe 1.0
  }

  // ---- createTasksFromMissingNodes --------------------------------------

  "HealingTask.createTasksFromMissingNodes" should "create one task per missing node" taggedAs UnitTest in {
    val missingNodes = Seq(
      Seq(nodeHash1) -> kec256(ByteString("a")),
      Seq(nodeHash1, nodeHash2) -> kec256(ByteString("b"))
    )
    val tasks = HealingTask.createTasksFromMissingNodes(missingNodes, rootHash)
    tasks.size shouldBe 2
  }

  it should "assign path, hash and rootHash from each missing-node entry" taggedAs UnitTest in {
    val path1 = Seq(nodeHash1)
    val hash1 = kec256(ByteString("target"))
    val tasks = HealingTask.createTasksFromMissingNodes(Seq(path1 -> hash1), rootHash)
    tasks.head.path shouldBe path1
    tasks.head.hash shouldBe hash1
    tasks.head.rootHash shouldBe rootHash
  }

  it should "return empty Seq for empty input" taggedAs UnitTest in {
    HealingTask.createTasksFromMissingNodes(Seq.empty, rootHash) shouldBe Seq.empty
  }

  // ---- toShortString ----------------------------------------------------

  "HealingTask.toShortString" should "contain status and depth" taggedAs UnitTest in {
    val task = HealingTask(Seq(nodeHash1, nodeHash2), nodeHash1, rootHash, pending = true, done = false)
    val s = task.toShortString
    s should include("pending")
    s should include("depth=2")
  }

  it should "label root-level task as 'root'" taggedAs UnitTest in {
    val task = HealingTask(Seq.empty, nodeHash1, rootHash)
    task.toShortString should include("root")
  }

  it should "label done task as 'done'" taggedAs UnitTest in {
    val task = HealingTask(Seq(nodeHash1), nodeHash1, rootHash, pending = false, done = true)
    task.toShortString should include("done")
  }
}
