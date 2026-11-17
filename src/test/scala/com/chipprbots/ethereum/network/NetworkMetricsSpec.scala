package com.chipprbots.ethereum.network

import java.util.concurrent.atomic.AtomicInteger

import scala.jdk.CollectionConverters.*

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.MockClock

/** SC-013's deterministic export test: what the scorer publishes to [[NetworkMetrics]] follows the scorer, once a
  * second of the scorer's own clock, as aggregates and a snapshot, with no per-peer metric series.
  *
  * [[NetworkMetrics]] is a process-wide object, so only this suite points a scorer at it; every other suite gives its
  * scorer a recording exporter. The cases run one after another and each publishes before it reads.
  */
class NetworkMetricsSpec extends AnyFlatSpec with Matchers:

  private val StartMs = 1_800_000_000_000L
  private val peer = PeerId("p1-" + "aa" * 30)
  private val other = PeerId("p2-" + "bb" * 30)

  private class Fixture(maxPeers: Int = 64):
    val clock = new MockClock(StartMs)
    val publishes = new AtomicInteger(0)
    val manager = new PeerScoringManager(
      clock,
      maxPeers,
      snapshot =>
        publishes.incrementAndGet()
        NetworkMetrics.publishPeerScores(snapshot)
    )

  private def gauge(name: String): Double = NetworkMetrics.metrics.registry.get(name).gauge().value()

  "NetworkMetrics" should "export a strictly lower score for a peer after a recorded timeout" taggedAs
    (UnitTest, NetworkTest) in {
      val f = new Fixture
      f.manager.recordSuccessfulHandshake(peer)
      f.manager.recordResponse(peer, bytes = 0, latencyMs = 100)
      f.manager.publish()
      val before = NetworkMetrics.peerScores.scores(peer)

      f.manager.recordTimeout(peer)
      f.manager.publish()
      val after = NetworkMetrics.peerScores.scores(peer)

      after should be < before
    }

  it should "not export a higher score for a peer after it is blacklisted" taggedAs (UnitTest, NetworkTest) in {
    val f = new Fixture
    f.manager.recordSuccessfulHandshake(peer)
    f.manager.recordResponse(peer, bytes = 0, latencyMs = 100)
    f.clock.windByMillis(59 * 60 * 1000L)
    f.manager.publish()
    val before = NetworkMetrics.peerScores.scores(peer)

    f.manager.recordBlacklist(peer)
    f.manager.publish()

    NetworkMetrics.peerScores.scores(peer) should be < before
  }

  it should "publish on an update at most once per second of the scorer's clock" taggedAs (UnitTest, NetworkTest) in {
    val f = new Fixture
    f.manager.recordSuccessfulHandshake(peer) // the first update publishes
    f.publishes.get shouldBe 1

    f.manager.recordTimeout(peer) // same instant: throttled
    f.manager.recordResponse(peer, bytes = 0, latencyMs = 10)
    f.publishes.get shouldBe 1

    f.clock.windByMillis(999)
    f.manager.recordTimeout(peer) // 999 ms after the last publish: still throttled
    f.publishes.get shouldBe 1

    f.clock.windByMillis(1)
    f.manager.recordTimeout(peer) // 1000 ms: due, and the snapshot carries every update made meanwhile
    f.publishes.get shouldBe 2
    NetworkMetrics.peerScores.scores(peer) shouldBe f.manager.snapshot().scores(peer) +- 1e-12

    f.manager.recordTimeout(peer) // the window restarts at the publish
    f.publishes.get shouldBe 2
  }

  it should "publish on demand whatever the throttle says, and restart the window" taggedAs
    (UnitTest, NetworkTest) in {
      val f = new Fixture
      f.manager.recordSuccessfulHandshake(peer)
      f.publishes.get shouldBe 1

      f.manager.publish() // the 10 s tick of PeerManagerActor: no event needed, no throttle
      f.publishes.get shouldBe 2

      f.manager.recordTimeout(peer) // within a second of the forced publish
      f.publishes.get shouldBe 2
    }

  it should "export the aggregate gauges equal to getStatistics" taggedAs (UnitTest, NetworkTest) in {
    val f = new Fixture
    // high: a handshake and fast responses
    f.manager.recordSuccessfulHandshake(peer)
    (1 to 10).foreach(_ => f.manager.recordResponse(peer, bytes = 0, latencyMs = 50))
    // medium: one handshake in three succeeded, one response and one timeout
    f.manager.recordSuccessfulHandshake(other)
    f.manager.recordFailedHandshake(other)
    f.manager.recordFailedHandshake(other)
    f.manager.recordResponse(other, bytes = 0, latencyMs = 100)
    f.manager.recordTimeout(other)
    // low: failures only
    val third = PeerId("p3-" + "cc" * 30)
    (1 to 3).foreach { _ =>
      f.manager.recordFailedHandshake(third)
      f.manager.recordTimeout(third)
    }
    f.manager.publish()

    val stats = f.manager.getStatistics
    stats.highScoringPeers shouldBe 1
    stats.mediumScoringPeers shouldBe 1
    stats.lowScoringPeers shouldBe 1

    gauge("network.peers.scored.gauge") shouldBe stats.totalPeers.toDouble
    gauge("network.peers.score.average.gauge") shouldBe stats.averageScore +- 1e-12
    gauge("network.peers.score.high.gauge") shouldBe stats.highScoringPeers.toDouble
    gauge("network.peers.score.medium.gauge") shouldBe stats.mediumScoringPeers.toDouble
    gauge("network.peers.score.low.gauge") shouldBe stats.lowScoringPeers.toDouble
    NetworkMetrics.peerScores.statistics shouldBe stats
  }

  it should "keep the registry free of per-peer series however many peers are scored" taggedAs
    (UnitTest, NetworkTest) in {
      val f = new Fixture
      val ids = (1 to 50).map(i => PeerId(f"unique-peer-id-$i%03d-" + "dd" * 20))
      ids.foreach(f.manager.recordSuccessfulHandshake)
      f.manager.publish()

      val meters = NetworkMetrics.metrics.registry.getMeters.asScala.toList
      val scoreMeters = meters.filter(m => m.getId.getName.startsWith("network.peers.score"))
      scoreMeters.map(_.getId.getName).toSet shouldBe Set(
        "network.peers.scored.gauge",
        "network.peers.score.average.gauge",
        "network.peers.score.high.gauge",
        "network.peers.score.medium.gauge",
        "network.peers.score.low.gauge"
      )
      meters.foreach { m =>
        withClue(s"meter ${m.getId}: ") {
          ids.exists(id => m.getId.getName.contains(id.value)) shouldBe false
          m.getId.getTags.asScala.exists(t => ids.exists(id => t.getValue.contains(id.value))) shouldBe false
        }
      }
      // the per-peer scores are in the snapshot, not the registry
      NetworkMetrics.peerScores.scores.keySet shouldBe ids.toSet
    }
