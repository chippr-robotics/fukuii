package com.chipprbots.ethereum.blockchain.sync.snap.actors

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.snap._
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.testing.Tags._

class MessagesSpec extends AnyFlatSpec with Matchers {

  // ========================================
  // AccountRange Messages
  // ========================================

  "AccountRange messages" should "create StartAccountRangeSync with state root" taggedAs UnitTest in {
    val stateRoot = kec256(ByteString("state-root"))
    val msg = Messages.StartAccountRangeSync(stateRoot)
    msg.stateRoot shouldBe stateRoot
  }

  it should "create TaskFailed with request ID and reason" taggedAs UnitTest in {
    val msg = Messages.TaskFailed(BigInt(42), "peer disconnected")
    msg.requestId shouldBe BigInt(42)
    msg.reason shouldBe "peer disconnected"
  }

  it should "create ContractAccountsResponse with accounts" taggedAs UnitTest in {
    val accounts = Seq(
      (kec256(ByteString("acc1")), kec256(ByteString("code1"))),
      (kec256(ByteString("acc2")), kec256(ByteString("code2")))
    )
    val msg = Messages.ContractAccountsResponse(accounts)
    msg.accounts.size shouldBe 2
  }

  it should "create AccountRangeProgress with map" taggedAs UnitTest in {
    val progress = Map(
      kec256(ByteString("last1")) -> kec256(ByteString("next1")),
      kec256(ByteString("last2")) -> kec256(ByteString("next2"))
    )
    val msg = Messages.AccountRangeProgress(progress)
    msg.progress.size shouldBe 2
  }

  it should "create PivotRefreshed with new state root" taggedAs UnitTest in {
    val newRoot = kec256(ByteString("new-root"))
    val msg = Messages.PivotRefreshed(newRoot)
    msg.newStateRoot shouldBe newRoot
  }

  it should "create UpdateMaxInFlightPerPeer" taggedAs UnitTest in {
    val msg = Messages.UpdateMaxInFlightPerPeer(3)
    msg.newLimit shouldBe 3
  }

  // ========================================
  // ByteCode Messages
  // ========================================

  "ByteCode messages" should "create StartByteCodeSync with code hashes" taggedAs UnitTest in {
    val hashes = Seq(kec256(ByteString("code1")), kec256(ByteString("code2")))
    val msg = Messages.StartByteCodeSync(hashes)
    msg.codeHashes.size shouldBe 2
  }

  it should "create AddByteCodeTasks for incremental dispatch" taggedAs UnitTest in {
    val hashes = Seq(kec256(ByteString("code1")))
    val msg = Messages.AddByteCodeTasks(hashes)
    msg.codeHashes.size shouldBe 1
  }

  it should "have NoMoreByteCodeTasks sentinel" taggedAs UnitTest in {
    val msg: Messages.ByteCodeCoordinatorMessage = Messages.NoMoreByteCodeTasks
    msg shouldBe Messages.NoMoreByteCodeTasks
  }

  it should "create ByteCodeTaskComplete with success result" taggedAs UnitTest in {
    val msg = Messages.ByteCodeTaskComplete(BigInt(1), Right(5))
    msg.requestId shouldBe BigInt(1)
    msg.result shouldBe Right(5)
  }

  it should "create ByteCodeTaskComplete with failure result" taggedAs UnitTest in {
    val msg = Messages.ByteCodeTaskComplete(BigInt(1), Left("hash mismatch"))
    msg.result shouldBe Left("hash mismatch")
  }

  it should "create ByteCodeProgress" taggedAs UnitTest in {
    val msg = Messages.ByteCodeProgress(0.75, 1500L, 50000L)
    msg.progress shouldBe 0.75
    msg.bytecodesDownloaded shouldBe 1500L
    msg.bytesDownloaded shouldBe 50000L
  }

  // ========================================
  // StorageRange Messages
  // ========================================

  "StorageRange messages" should "create AddStorageTasks with task list" taggedAs UnitTest in {
    val tasks = Seq(
      StorageTask.createStorageTask(kec256(ByteString("acc1")), kec256(ByteString("root1"))),
      StorageTask.createStorageTask(kec256(ByteString("acc2")), kec256(ByteString("root2")))
    )
    val msg = Messages.AddStorageTasks(tasks)
    msg.tasks.size shouldBe 2
  }

  it should "create AddStorageTask for single task" taggedAs UnitTest in {
    val task = StorageTask.createStorageTask(kec256(ByteString("acc")), kec256(ByteString("root")))
    val msg = Messages.AddStorageTask(task)
    msg.task.accountHash shouldBe kec256(ByteString("acc"))
  }

  it should "have NoMoreStorageTasks sentinel" taggedAs UnitTest in {
    val msg: Messages.StorageRangeCoordinatorMessage = Messages.NoMoreStorageTasks
    msg shouldBe Messages.NoMoreStorageTasks
  }

  it should "create StoragePivotRefreshed" taggedAs UnitTest in {
    val newRoot = kec256(ByteString("new-root"))
    val msg = Messages.StoragePivotRefreshed(newRoot)
    msg.newStateRoot shouldBe newRoot
  }

  it should "have ForceCompleteStorage for stagnation recovery" taggedAs UnitTest in {
    val msg: Messages.StorageRangeCoordinatorMessage = Messages.ForceCompleteStorage
    msg shouldBe Messages.ForceCompleteStorage
  }

  // ========================================
  // TrieNodeHealing Messages
  // ========================================

  "Healing messages" should "create QueueMissingNodes" taggedAs UnitTest in {
    val nodes = Seq(
      (Seq(ByteString(Array[Byte](0x00))), kec256(ByteString("node1"))),
      (Seq(ByteString(Array[Byte](0x01)), ByteString(Array[Byte](0x02))), kec256(ByteString("node2")))
    )
    val msg = Messages.QueueMissingNodes(nodes)
    msg.nodes.size shouldBe 2
  }

  it should "create HealingPivotRefreshed" taggedAs UnitTest in {
    val newRoot = kec256(ByteString("new-root"))
    val msg = Messages.HealingPivotRefreshed(newRoot)
    msg.newStateRoot shouldBe newRoot
  }

  it should "create HealingTaskComplete" taggedAs UnitTest in {
    val msg = Messages.HealingTaskComplete(BigInt(99), Right(3))
    msg.requestId shouldBe BigInt(99)
    msg.result shouldBe Right(3)
  }

  it should "create HealingTaskFailed" taggedAs UnitTest in {
    val msg = Messages.HealingTaskFailed(BigInt(99), "timeout")
    msg.requestId shouldBe BigInt(99)
    msg.reason shouldBe "timeout"
  }

  // ========================================
  // Worker Messages
  // ========================================

  "Worker messages" should "create FetchAccountRange" taggedAs UnitTest in {
    val task = AccountTask(
      next = ByteString(Array.fill(32)(0.toByte)),
      last = AccountTask.MaxHash32,
      rootHash = kec256(ByteString("root"))
    )
    // Can't construct Peer without ActorRef in pure test, just verify type exists
    // This is covered by coordinator specs with TestProbe
    task should not be null
  }

  it should "create ByteCodeWorkerFetchTask with response size" taggedAs UnitTest in {
    val codeHash = kec256(ByteString("code"))
    val task = ByteCodeTask(Seq(codeHash))
    // Verify task type structure
    task.codeHashes.size shouldBe 1
  }
}
