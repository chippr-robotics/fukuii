package com.chipprbots.ethereum.blockchain.data

import scala.concurrent.ExecutionContext

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.testkit.TestKit
import org.apache.pekko.util.ByteString

import org.bouncycastle.util.encoders.Hex
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.NormalPatience
import com.chipprbots.ethereum.testing.Tags._

class CheckpointUpdateServiceSpec
    extends TestKit(ActorSystem("CheckpointUpdateServiceSpec_System"))
    with AnyFlatSpecLike
    with BeforeAndAfterAll
    with Matchers
    with ScalaFutures
    with NormalPatience {

  implicit val ec: ExecutionContext = system.dispatcher

  override def afterAll(): Unit = {
    TestKit.shutdownActorSystem(system)
    super.afterAll()
  }

  /** Helper method to invoke private parseCheckpointsFromJson method */
  private def parseCheckpoints(service: CheckpointUpdateService, json: String): Either[String, Seq[BootstrapCheckpoint]] = {
    val parseMethod = service.getClass.getDeclaredMethod("parseCheckpointsFromJson", classOf[String])
    parseMethod.setAccessible(true)
    parseMethod.invoke(service, json).asInstanceOf[Either[String, Seq[BootstrapCheckpoint]]]
  }

  /** Helper method to invoke private verifyWithQuorum method */
  private def verifyWithQuorum(
      service: CheckpointUpdateService,
      checkpointSets: Seq[Seq[BootstrapCheckpoint]],
      quorum: Int
  ): Seq[VerifiedCheckpoint] = {
    val verifyMethod =
      service.getClass.getDeclaredMethod("verifyWithQuorum", classOf[Seq[Seq[BootstrapCheckpoint]]], classOf[Int])
    verifyMethod.setAccessible(true)
    verifyMethod.invoke(service, checkpointSets, quorum).asInstanceOf[Seq[VerifiedCheckpoint]]
  }

  "CheckpointUpdateService" should "parse valid JSON checkpoint response" taggedAs (UnitTest, SyncTest) in {
    val service = new CheckpointUpdateService()

    val validJson = """{
      "network": "etc-mainnet",
      "checkpoints": [
        {"blockNumber": "19250000", "blockHash": "0x1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef"},
        {"blockNumber": "14525000", "blockHash": "0xabcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890"}
      ]
    }"""

    val result = parseCheckpoints(service, validJson)

    result shouldBe a[Right[_, _]]
    result.getOrElse(Seq.empty) should have size 2

    val checkpoints = result.getOrElse(Seq.empty)
    checkpoints(0).blockNumber shouldEqual BigInt(19250000)
    checkpoints(0).blockHash shouldEqual ByteString(
      Hex.decode("1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef")
    )
    checkpoints(1).blockNumber shouldEqual BigInt(14525000)
    checkpoints(1).blockHash shouldEqual ByteString(
      Hex.decode("abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890")
    )
  }

  it should "parse JSON with 0x prefixed block hashes" taggedAs (UnitTest, SyncTest) in {
    val service = new CheckpointUpdateService()

    val jsonWithPrefix = """{
      "network": "mordor",
      "checkpoints": [
        {"blockNumber": "9957000", "blockHash": "0xfedcba0987654321fedcba0987654321fedcba0987654321fedcba0987654321"}
      ]
    }"""

    val result = parseCheckpoints(service, jsonWithPrefix)

    result shouldBe a[Right[_, _]]
    val checkpoints = result.getOrElse(Seq.empty)
    checkpoints should have size 1
    checkpoints(0).blockNumber shouldEqual BigInt(9957000)
  }

  it should "parse JSON without 0x prefixed block hashes" taggedAs (UnitTest, SyncTest) in {
    val service = new CheckpointUpdateService()

    val jsonWithoutPrefix = """{
      "network": "mordor",
      "checkpoints": [
        {"blockNumber": "9957000", "blockHash": "fedcba0987654321fedcba0987654321fedcba0987654321fedcba0987654321"}
      ]
    }"""

    val result = parseCheckpoints(service, jsonWithoutPrefix)

    result shouldBe a[Right[_, _]]
    val checkpoints = result.getOrElse(Seq.empty)
    checkpoints should have size 1
    checkpoints(0).blockNumber shouldEqual BigInt(9957000)
  }

  it should "handle very large block numbers" taggedAs (UnitTest, SyncTest) in {
    val service = new CheckpointUpdateService()

    val largeBlockJson = """{
      "network": "test",
      "checkpoints": [
        {"blockNumber": "999999999999999999", "blockHash": "0x1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef"}
      ]
    }"""

    val result = parseCheckpoints(service, largeBlockJson)

    result shouldBe a[Right[_, _]]
    val checkpoints = result.getOrElse(Seq.empty)
    checkpoints should have size 1
    checkpoints(0).blockNumber shouldEqual BigInt("999999999999999999")
  }

  it should "return error for invalid JSON" taggedAs (UnitTest, SyncTest) in {
    val service = new CheckpointUpdateService()

    val invalidJson = """{"invalid": "json without required fields"}"""

    val result = parseCheckpoints(service, invalidJson)

    result shouldBe a[Left[_, _]]
    result.left.getOrElse("") should include("JSON parsing error")
  }

  it should "return error for malformed JSON" taggedAs (UnitTest, SyncTest) in {
    val service = new CheckpointUpdateService()

    val malformedJson = """{"network": "etc-mainnet", "checkpoints": ["""

    val result = parseCheckpoints(service, malformedJson)

    result shouldBe a[Left[_, _]]
    result.left.getOrElse("") should include("JSON parsing error")
  }

  it should "return error for invalid hex hash" taggedAs (UnitTest, SyncTest) in {
    val service = new CheckpointUpdateService()

    val invalidHexJson = """{
      "network": "etc-mainnet",
      "checkpoints": [
        {"blockNumber": "19250000", "blockHash": "0xINVALIDHEXSTRING"}
      ]
    }"""

    val result = parseCheckpoints(service, invalidHexJson)

    result shouldBe a[Left[_, _]]
    result.left.getOrElse("") should include("Validation error")
  }

  it should "return error for wrong length block hash" taggedAs (UnitTest, SyncTest) in {
    val service = new CheckpointUpdateService()

    val wrongLengthJson = """{
      "network": "etc-mainnet",
      "checkpoints": [
        {"blockNumber": "19250000", "blockHash": "0x1234"}
      ]
    }"""

    val result = parseCheckpoints(service, wrongLengthJson)

    result shouldBe a[Left[_, _]]
    result.left.getOrElse("") should include("expected 64 hex characters")
  }

  it should "return error for invalid block number" taggedAs (UnitTest, SyncTest) in {
    val service = new CheckpointUpdateService()

    val invalidBlockNumberJson = """{
      "network": "etc-mainnet",
      "checkpoints": [
        {"blockNumber": "not-a-number", "blockHash": "0x1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef"}
      ]
    }"""

    val result = parseCheckpoints(service, invalidBlockNumberJson)

    result shouldBe a[Left[_, _]]
    result.left.getOrElse("") should include("Invalid block number")
  }

  it should "handle empty checkpoints array" taggedAs (UnitTest, SyncTest) in {
    val service = new CheckpointUpdateService()

    val emptyCheckpointsJson = """{
      "network": "etc-mainnet",
      "checkpoints": []
    }"""

    val result = parseCheckpoints(service, emptyCheckpointsJson)

    result shouldBe a[Right[_, _]]
    result.getOrElse(Seq.empty) should have size 0
  }

  "verifyWithQuorum" should "verify checkpoints with quorum consensus" taggedAs (UnitTest, SyncTest) in {
    val service = new CheckpointUpdateService()

    val checkpoint1 = BootstrapCheckpoint(
      BigInt(19250000),
      ByteString(Hex.decode("1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef"))
    )

    val checkpoint2 = BootstrapCheckpoint(
      BigInt(19250000),
      ByteString(Hex.decode("1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef"))
    )

    val checkpoint3 = BootstrapCheckpoint(
      BigInt(19250000),
      ByteString(Hex.decode("fedcba0987654321fedcba0987654321fedcba0987654321fedcba0987654321"))
    )

    val checkpointSets = Seq(Seq(checkpoint1), Seq(checkpoint2), Seq(checkpoint3))

    val result = verifyWithQuorum(service, checkpointSets, 2)

    result should have size 1
    result(0).blockNumber shouldEqual BigInt(19250000)
    result(0).blockHash shouldEqual checkpoint1.blockHash
    result(0).sourceCount shouldEqual 2
  }

  it should "not verify checkpoints without quorum" taggedAs (UnitTest, SyncTest) in {
    val service = new CheckpointUpdateService()

    val checkpoint1 = BootstrapCheckpoint(
      BigInt(19250000),
      ByteString(Hex.decode("1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef"))
    )

    val checkpoint2 = BootstrapCheckpoint(
      BigInt(19250000),
      ByteString(Hex.decode("fedcba0987654321fedcba0987654321fedcba0987654321fedcba0987654321"))
    )

    val checkpoint3 = BootstrapCheckpoint(
      BigInt(19250000),
      ByteString(Hex.decode("abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890"))
    )

    val checkpointSets = Seq(Seq(checkpoint1), Seq(checkpoint2), Seq(checkpoint3))

    val result = verifyWithQuorum(service, checkpointSets, 2)

    result should have size 0
  }
}
