package com.chipprbots.ethereum.blockchain.sync.snap

import java.net.InetSocketAddress

import org.apache.pekko.actor.testkit.typed.scaladsl.TestInbox

import scala.collection.mutable
import scala.jdk.CollectionConverters.*

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger as LogbackLogger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.slf4j.LoggerFactory

import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.PeerActor
import com.chipprbots.ethereum.network.PeerId
import com.chipprbots.ethereum.testing.Tags.UnitTest

/** Unit cases of the shared response-size window (spec 016 C-X, T076). `ResponseWindowGoldenSpec` pins the window as
  * each coordinator uses it; these cases pin the shared class on its own: the seeding, the 90% boundary, the ceiling,
  * the rounding, both clamps and the DEBUG line with its label, on the logger it is given.
  */
class AdaptiveResponseBytesSpec extends AnyFlatSpec with Matchers:

  private val Max = BigInt(2 * 1024 * 1024)

  private def peer(id: String): Peer =
    Peer(PeerId(id), new InetSocketAddress("127.0.0.1", 30303), TestInbox[PeerActor.Command]().ref, false)

  /** A window on a fresh map and a private DEBUG logger whose events are kept. */
  final private class Fixture(label: String, initial: BigInt, min: BigInt):
    val targets: mutable.Map[String, BigInt] = mutable.Map.empty
    private val logger =
      LoggerFactory.getLogger(s"test.AdaptiveResponseBytesSpec.$label.${System.nanoTime()}").asInstanceOf[LogbackLogger]
    private val appender = new ListAppender[ILoggingEvent]()
    appender.start()
    logger.addAppender(appender)
    logger.setLevel(Level.DEBUG)
    logger.setAdditive(false)
    val window = new AdaptiveResponseBytes(label, initial, min, targets, logger)
    def lines: List[(Level, String, String)] =
      appender.list.asScala.toList.map(e => (e.getLevel, e.getLoggerName, e.getFormattedMessage))
    def loggerName: String = logger.getName

  "AdaptiveResponseBytes" should "seed an unseen peer with the initial target and store it" taggedAs UnitTest in {
    val f = Fixture("account", BigInt(524288), BigInt(102400))
    val p = peer("seed")
    f.window.targetFor(p) shouldBe BigInt(524288)
    f.targets(p.id.value) shouldBe BigInt(524288)
  }

  it should "clamp a stored target to [min, 2 MiB] on read without rewriting it" taggedAs UnitTest in {
    val f = Fixture("storage", BigInt(1048576), BigInt(131072))
    val low = peer("low")
    val high = peer("high")
    f.targets(low.id.value) = BigInt(1000)
    f.targets(high.id.value) = Max * 2
    f.window.targetFor(low) shouldBe BigInt(131072)
    f.window.targetFor(high) shouldBe Max
    f.targets(low.id.value) shouldBe BigInt(1000)
    f.targets(high.id.value) shouldBe Max * 2
  }

  it should "grow from the requested size when the response fills exactly 90%" taggedAs UnitTest in {
    val f = Fixture("healing", BigInt(524288), BigInt(51200))
    val p = peer("ninety")
    f.window.onSuccess(p, BigInt(1000), BigInt(900))
    f.targets(p.id.value) shouldBe BigInt(1250)
  }

  it should "not grow just below 90%, for a zero request, or at the ceiling" taggedAs UnitTest in {
    val f = Fixture("healing", BigInt(524288), BigInt(51200))
    val p = peer("nogrow")
    f.window.onSuccess(p, BigInt(1000), BigInt(899))
    f.window.onSuccess(p, BigInt(0), BigInt(10))
    f.window.onSuccess(p, Max, Max)
    f.targets.get(p.id.value) shouldBe None
  }

  it should "round growth down after scaling and cap it at 2 MiB" taggedAs UnitTest in {
    val f = Fixture("account", BigInt(524288), BigInt(102400))
    val odd = peer("odd")
    val near = peer("near")
    f.window.onSuccess(odd, BigInt(1001), BigInt(1001))
    f.targets(odd.id.value) shouldBe BigInt(1251) // 1251.25 -> 1251
    f.window.onSuccess(near, Max - 1, Max - 1)
    f.targets(near.id.value) shouldBe Max
  }

  it should "halve on failure, rounding down, and not below min" taggedAs UnitTest in {
    val f = Fixture("storage", BigInt(1048576), BigInt(131072))
    val p = peer("halve")
    f.targets(p.id.value) = BigInt(300001)
    f.window.onFailure(p, "x")
    f.targets(p.id.value) shouldBe BigInt(150000)
    f.window.onFailure(p, "x")
    f.targets(p.id.value) shouldBe BigInt(131072)
  }

  it should "seed an unseen peer before halving it on failure" taggedAs UnitTest in {
    val f = Fixture("account", BigInt(524288), BigInt(102400))
    val p = peer("unseen")
    f.window.onFailure(p, "request timeout")
    f.targets(p.id.value) shouldBe BigInt(262144)
  }

  it should "log the reduction at DEBUG with its label, on the logger it was given" taggedAs UnitTest in {
    val f = Fixture("healing", BigInt(524288), BigInt(51200))
    val p = peer("logged")
    f.window.onFailure(p, "empty healing response")
    f.lines shouldBe List(
      (
        Level.DEBUG,
        f.loggerName,
        "Reducing healing responseBytes target for peer logged: 524288 -> 262144 (empty healing response)"
      )
    )
  }

  it should "not log on success" taggedAs UnitTest in {
    val f = Fixture("storage", BigInt(1048576), BigInt(131072))
    f.window.onSuccess(peer("quiet"), BigInt(1000), BigInt(1000))
    f.lines shouldBe Nil
  }
