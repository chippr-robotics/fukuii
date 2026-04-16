package com.chipprbots.ethereum.blockchain.sync.snap

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.testing.Tags._

class ByteCodeTaskSpec extends AnyFlatSpec with Matchers {

  "ByteCodeTask" should "create tasks from contract accounts" taggedAs UnitTest in {
    val contractAccount1 = (ByteString("account1"), kec256(ByteString("code1")))
    val contractAccount2 = (ByteString("account2"), kec256(ByteString("code2")))
    val contractAccounts = Seq(contractAccount1, contractAccount2)

    val tasks = ByteCodeTask.createBytecodeTasksFromAccounts(contractAccounts, batchSize = 2)

    tasks.size shouldBe 1
    tasks.head.codeHashes.size shouldBe 2
    tasks.head.accountHashes.size shouldBe 2
  }

  it should "batch tasks correctly" taggedAs UnitTest in {
    val contractAccounts = (1 to 35).map { i =>
      (ByteString(s"account$i"), kec256(ByteString(s"code$i")))
    }

    val tasks = ByteCodeTask.createBytecodeTasksFromAccounts(contractAccounts, batchSize = 16)

    tasks.size shouldBe 3 // 16 + 16 + 3
    tasks(0).codeHashes.size shouldBe 16
    tasks(1).codeHashes.size shouldBe 16
    tasks(2).codeHashes.size shouldBe 3
  }

  it should "handle empty contract list" taggedAs UnitTest in {
    val tasks = ByteCodeTask.createBytecodeTasksFromAccounts(Seq.empty, batchSize = 16)
    tasks.size shouldBe 0
  }

  it should "track pending and done states" taggedAs UnitTest in {
    val codeHash = kec256(ByteString("code"))
    val task = ByteCodeTask(Seq(codeHash))

    task.isComplete shouldBe false
    task.isPending shouldBe false

    task.pending = true
    task.isPending shouldBe true
    task.isComplete shouldBe false

    task.done = true
    task.isComplete shouldBe true
  }

  it should "calculate progress correctly" taggedAs UnitTest in {
    val codeHashes = (1 to 5).map(i => kec256(ByteString(s"code$i")))
    val task = ByteCodeTask(codeHashes)

    task.progress shouldBe 0.0

    task.bytecodes = Seq(ByteString("code1"), ByteString("code2"))
    task.progress shouldBe 0.4 // 2/5

    task.done = true
    task.progress shouldBe 1.0
  }

  it should "validate account hashes match code hashes size" taggedAs UnitTest in {
    val codeHashes = Seq(kec256(ByteString("code1")), kec256(ByteString("code2")))
    val accountHashes = Seq(ByteString("account1"))

    // This should throw because sizes don't match
    intercept[IllegalArgumentException] {
      ByteCodeTask(codeHashes, accountHashes)
    }
  }

  it should "allow empty account hashes" taggedAs UnitTest in {
    val codeHashes = Seq(kec256(ByteString("code1")), kec256(ByteString("code2")))
    val task = ByteCodeTask(codeHashes, Seq.empty)

    task.codeHashes.size shouldBe 2
    task.accountHashes.size shouldBe 0
  }
}
