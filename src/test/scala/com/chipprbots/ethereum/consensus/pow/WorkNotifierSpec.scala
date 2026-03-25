package com.chipprbots.ethereum.consensus.pow

import org.apache.pekko.util.ByteString

import scala.concurrent.ExecutionContext

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.testing.Tags._

/** Tests for WorkNotifier — fire-and-forget HTTP POST work notifications.
  */
class WorkNotifierSpec extends AnyFlatSpec with Matchers {

  implicit val ec: ExecutionContext = ExecutionContext.global

  "WorkNotifier" should "report hasTargets=true when URLs are configured" taggedAs (UnitTest, ConsensusTest) in {
    val notifier = new WorkNotifier(Seq("http://localhost:9999/work"), notifyFull = false)
    notifier.hasTargets shouldBe true
  }

  it should "report hasTargets=false for empty URL list" taggedAs (UnitTest, ConsensusTest) in {
    val notifier = new WorkNotifier(Seq.empty, notifyFull = false)
    notifier.hasTargets shouldBe false
  }

  it should "not throw when notifying unreachable URLs" taggedAs (UnitTest, ConsensusTest) in {
    // Fire-and-forget: should not propagate exceptions
    val notifier = new WorkNotifier(Seq("http://192.0.2.1:1/work"), notifyFull = false)
    noException should be thrownBy {
      notifier.notifyWork(
        powHeaderHash = ByteString(Array.fill(32)(0xAA.toByte)),
        dagSeed = ByteString(Array.fill(32)(0xBB.toByte)),
        target = ByteString(Array.fill(32)(0xCC.toByte)),
        blockNumber = BigInt(12345)
      )
    }
    // Give async futures time to fail silently
    Thread.sleep(100)
  }

  it should "be a no-op when URL list is empty" taggedAs (UnitTest, ConsensusTest) in {
    val notifier = new WorkNotifier(Seq.empty, notifyFull = false)
    noException should be thrownBy {
      notifier.notifyWork(
        powHeaderHash = ByteString(Array.fill(32)(0xAA.toByte)),
        dagSeed = ByteString(Array.fill(32)(0xBB.toByte)),
        target = ByteString(Array.fill(32)(0xCC.toByte)),
        blockNumber = BigInt(12345)
      )
    }
  }

  it should "not throw when notifying with full header" taggedAs (UnitTest, ConsensusTest) in {
    val notifier = new WorkNotifier(Seq("http://192.0.2.1:1/work"), notifyFull = true)
    val header = Fixtures.Blocks.Genesis.header
    noException should be thrownBy {
      notifier.notifyWork(
        powHeaderHash = ByteString(Array.fill(32)(0xAA.toByte)),
        dagSeed = ByteString(Array.fill(32)(0xBB.toByte)),
        target = ByteString(Array.fill(32)(0xCC.toByte)),
        blockNumber = BigInt(0),
        header = Some(header)
      )
    }
    Thread.sleep(100)
  }

  it should "handle multiple configured URLs" taggedAs (UnitTest, ConsensusTest) in {
    val notifier = new WorkNotifier(
      Seq("http://192.0.2.1:1/work", "http://192.0.2.2:2/work", "http://192.0.2.3:3/work"),
      notifyFull = false
    )
    notifier.hasTargets shouldBe true
    noException should be thrownBy {
      notifier.notifyWork(
        powHeaderHash = ByteString(Array.fill(32)(0xAA.toByte)),
        dagSeed = ByteString(Array.fill(32)(0xBB.toByte)),
        target = ByteString(Array.fill(32)(0xCC.toByte)),
        blockNumber = BigInt(99999)
      )
    }
    Thread.sleep(100)
  }
}
