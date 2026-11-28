package com.chipprbots.ethereum.forkid

import org.apache.pekko.util.ByteString

import cats.effect.IO
import cats.effect.unsafe.IORuntime

import ch.qos.logback.classic.Level
import org.bouncycastle.util.encoders.Hex
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.forkid.ForkIdValidator.ioLogger
import com.chipprbots.ethereum.testing.LogCapture
import com.chipprbots.ethereum.testing.Tags.*

/** B-415: the fork-id check runs once per handshake and once per discovered ENR or DNS record, so on a busy node it ran
  * thousands of times a minute and its result line was most of a 12.5 GB log. It is DEBUG; a rejected handshake is
  * counted in the `Network [60s]` summary instead.
  */
class ForkIdValidatorLogLevelSpec extends AnyFlatSpec with Matchers:

  implicit val runtime: IORuntime = IORuntime.global

  private val ethGenesisHash = ByteString(
    Hex.decode("d4e56740f876aef8c010b86a40d5f56745a118d0906a34e69aec8c0db1cb8fa3")
  )
  private val forks: List[BigInt] = List(1150000, 1920000, 2463000, 2675000, 4370000, 7280000)

  private def validate(remote: ForkId): ForkIdValidationResult =
    ForkIdValidator.validatePeer[IO](ethGenesisHash, forks)(BigInt(7987396), remote).unsafeRunSync()

  "ForkIdValidator" should "log neither an accepted nor a rejected peer at INFO or above" taggedAs (
    UnitTest,
    NetworkTest
  ) in {
    val (results, events) = LogCapture.capture("com.chipprbots.ethereum.forkid") {
      (validate(ForkId(0x668db0afL, None)), validate(ForkId(0xdeadbeefL, None)))
    }
    results._1 shouldBe ForkIdValidationResult.Connect
    results._2 should not be ForkIdValidationResult.Connect
    LogCapture.atInfoOrAbove(events) shouldBe empty
    // The result is still there for a diagnosis at DEBUG.
    events.filter(_.getLevel == Level.DEBUG).count(_.getFormattedMessage.contains("Validation result")) shouldBe 2
  }
