package com.chipprbots.ethereum.network

import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit

import scala.collection.mutable.ListBuffer

import org.scalacheck.Gen
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.MockClock

/** SC-013 (spec 027 slice 4a): the scorer is observational, atomic, bounded and deterministic. Every time is read from
  * an injected clock; the concurrency tests use a barrier and latches, never a sleep.
  */
class PeerScoringManagerSpec extends AnyFlatSpec with Matchers with ScalaCheckPropertyChecks:

  private val StartMs = 1_800_000_000_000L
  private val Now: Instant = Instant.ofEpochMilli(StartMs)

  private val peer = PeerId("aa" * 64)
  private val other = PeerId("bb" * 64)

  private class Fixture(maxPeers: Int = 64):
    val clock = new MockClock(StartMs)
    val exported = new ListBuffer[PeerScoreSnapshot]
    private def record(snapshot: PeerScoreSnapshot): Unit = exported.synchronized { exported += snapshot; () }
    val manager = new PeerScoringManager(clock, maxPeers, record)
    def score(id: PeerId): Double = manager.getScore(id).score(clock.instant())

  /** Runs `body(threadIndex)` on `threads` threads released together by a barrier; waits on a latch, fails on any
    * worker exception.
    */
  private def runConcurrently(threads: Int)(body: Int => Unit): Unit =
    val start = new CyclicBarrier(threads)
    val done = new CountDownLatch(threads)
    val failures = new ConcurrentLinkedQueue[Throwable]()
    (0 until threads).foreach { i =>
      val worker = new Thread(
        () =>
          try
            start.await(30, TimeUnit.SECONDS)
            body(i)
          catch
            case e: Throwable =>
              failures.add(e)
              ()
          finally done.countDown(),
        s"peer-scoring-test-$i"
      )
      worker.setDaemon(true)
      worker.start()
    }
    withClue("the worker threads did not finish: ")(done.await(60, TimeUnit.SECONDS) shouldBe true)
    Option(failures.peek()).foreach(e => fail("a worker thread failed", e))

  // ---- each record* call moves the score the expected way ------------------------------------------------------

  "PeerScoringManager" should "start a peer at the neutral score once it has handshaked" taggedAs
    (UnitTest, NetworkTest) in {
      val f = new Fixture
      f.manager.recordSuccessfulHandshake(peer)
      val s = f.manager.getScore(peer)
      s.successfulHandshakes shouldBe 1
      s.firstSeen shouldBe Some(Now)
      s.lastSeen shouldBe Some(Now)
      // handshake 1.0, no responses 0.5, no latency 0.5, compliance 1.0, recency 1.0
      f.score(peer) shouldBe (0.3 + 0.125 + 0.1 + 0.15 + 0.1) +- 1e-9
    }

  it should "lower the score on a failed handshake, a timeout, a protocol violation and a blacklist" taggedAs
    (UnitTest, NetworkTest) in {
      val f = new Fixture
      f.manager.recordSuccessfulHandshake(peer)
      (1 to 10).foreach(_ => f.manager.recordResponse(peer, bytes = 0, latencyMs = 100))
      val baseline = f.score(peer)

      f.manager.recordFailedHandshake(peer)
      val afterFailedHandshake = f.score(peer)
      afterFailedHandshake should be < baseline

      f.manager.recordTimeout(peer)
      val afterTimeout = f.score(peer)
      afterTimeout should be < afterFailedHandshake

      f.manager.recordProtocolViolation(peer)
      val afterViolation = f.score(peer)
      afterViolation should be < afterTimeout

      f.manager.recordBlacklist(peer)
      f.score(peer) should be < afterViolation
    }

  it should "raise the score of a peer with failures on a fast response" taggedAs (UnitTest, NetworkTest) in {
    val f = new Fixture
    f.manager.recordSuccessfulHandshake(peer)
    (1 to 5).foreach(_ => f.manager.recordTimeout(peer))
    val degraded = f.score(peer)
    f.manager.recordResponse(peer, bytes = 0, latencyMs = 20)
    f.score(peer) should be > degraded
    f.manager.getScore(peer).responsesReceived shouldBe 1
    f.manager.getScore(peer).averageLatencyMs shouldBe Some(20.0)
  }

  it should "refresh lastSeen only on events that prove the peer is alive and well" taggedAs
    (UnitTest, NetworkTest) in {
      val f = new Fixture
      f.manager.recordSuccessfulHandshake(peer)
      f.clock.windByMillis(10 * 60 * 1000L)
      val later = f.clock.instant()

      f.manager.recordTimeout(peer)
      f.manager.recordProtocolViolation(peer)
      f.manager.recordFailedHandshake(peer)
      f.manager.recordBlacklist(peer)
      f.manager.getScore(peer).lastSeen shouldBe Some(Now)

      f.manager.recordResponse(peer, bytes = 0, latencyMs = 50)
      f.manager.getScore(peer).lastSeen shouldBe Some(later)
    }

  it should "create the entry of a peer first heard of in a failure with that failure counted" taggedAs
    (UnitTest, NetworkTest) in {
      val f = new Fixture
      f.manager.recordFailedHandshake(other)
      f.manager.recordBlacklist(PeerId("cc" * 64))
      f.manager.getScore(other).failedHandshakes shouldBe 1
      f.manager.getScore(PeerId("cc" * 64)).blacklistCount shouldBe 1
      f.manager.getAllScores.keySet shouldBe Set(other, PeerId("cc" * 64))
    }

  // ---- a negative event never raises a score -------------------------------------------------------------------

  it should "not raise the score of a peer last seen 59 minutes ago when it is blacklisted" taggedAs
    (UnitTest, NetworkTest) in {
      // The blacklist used to refresh lastSeen, which lifted the recency term (10 % of the score) from nearly 0 to 1.
      val f = new Fixture
      f.manager.recordSuccessfulHandshake(peer)
      (1 to 10).foreach(_ => f.manager.recordResponse(peer, bytes = 0, latencyMs = 100))
      f.clock.windByMillis(59 * 60 * 1000L)
      val before = f.score(peer)
      f.manager.recordBlacklist(peer)
      f.score(peer) should be < before
    }

  private val genScore: Gen[PeerScore] =
    for
      successes <- Gen.choose(0, 50)
      failures <- Gen.choose(0, 50)
      responses <- Gen.choose(0, 500)
      timeouts <- Gen.choose(0, 500)
      latency <- Gen.option(Gen.choose(0.0, 4000.0))
      violations <- Gen.choose(0, 50)
      blacklists <- Gen.choose(0, 20)
      ageMinutes <- Gen.option(Gen.choose(0, 120))
    yield PeerScore(
      successfulHandshakes = successes,
      failedHandshakes = failures,
      responsesReceived = responses,
      requestsTimedOut = timeouts,
      averageLatencyMs = latency,
      protocolViolations = violations,
      blacklistCount = blacklists,
      lastSeen = ageMinutes.map(m => Now.minusSeconds(m * 60L))
    )

  private val negativeEvents: List[(String, PeerScore => PeerScore)] = List(
    "failed handshake" -> (_.recordFailedHandshake(Now)),
    "timeout" -> (_.recordTimeout(Now)),
    "protocol violation" -> (_.recordProtocolViolation(Now)),
    "blacklist" -> (_.recordBlacklist(Now))
  )

  negativeEvents.foreach { case (name, event) =>
    it should s"never raise a score on a $name, whatever the peer's history" taggedAs (UnitTest, NetworkTest) in {
      forAll(genScore, minSuccessful(500)) { s =>
        event(s).score(Now) should be <= (s.score(Now) + 1e-12)
      }
    }
  }

  it should "lower the score of a blacklisted peer that has any compliance left to lose" taggedAs
    (UnitTest, NetworkTest) in {
      forAll(
        genScore.suchThat(s => s.protocolViolations + s.blacklistCount < s.responsesReceived / 2),
        minSuccessful(500)
      ) { s =>
        s.recordBlacklist(Now).score(Now) should be < s.score(Now)
      }
    }

  // ---- atomic updates ------------------------------------------------------------------------------------------

  it should "lose no event when four threads record events for one peer" taggedAs (UnitTest, NetworkTest) in {
    val f = new Fixture
    val perThread = 2000
    runConcurrently(4) { _ =>
      (1 to perThread).foreach { _ =>
        f.manager.recordTimeout(peer)
        f.manager.recordResponse(peer, bytes = 3, latencyMs = 10)
        f.manager.recordProtocolViolation(peer)
        f.manager.recordFailedHandshake(peer)
        f.manager.recordSuccessfulHandshake(peer)
        f.manager.recordBlacklist(peer)
      }
    }
    val s = f.manager.getScore(peer)
    val expected = 4 * perThread
    s.requestsTimedOut shouldBe expected
    s.responsesReceived shouldBe expected
    s.bytesDownloaded shouldBe 3L * expected
    s.protocolViolations shouldBe expected
    s.failedHandshakes shouldBe expected
    s.successfulHandshakes shouldBe expected
    s.blacklistCount shouldBe expected
    f.manager.getAllScores.keySet shouldBe Set(peer)
  }

  it should "lose no event when four threads record events for four different peers that share the map" taggedAs
    (UnitTest, NetworkTest) in {
      val f = new Fixture
      val perThread = 2000
      val ids = (0 until 4).map(i => PeerId(s"peer-$i"))
      runConcurrently(4) { i =>
        (1 to perThread).foreach { _ =>
          f.manager.recordTimeout(ids(i))
          f.manager.recordTimeout(ids((i + 1) % 4))
        }
      }
      ids.foreach(id => f.manager.getScore(id).requestsTimedOut shouldBe 2 * perThread)
    }

  // ---- the map is bounded --------------------------------------------------------------------------------------

  it should "never hold more than its cap, and evict the entry with the oldest lastSeen" taggedAs
    (UnitTest, NetworkTest) in {
      val f = new Fixture(maxPeers = 3)
      val ids = (1 to 3).map(i => PeerId(s"p$i"))
      ids.foreach { id =>
        f.manager.recordSuccessfulHandshake(id)
        f.clock.windByMillis(1000)
      }
      f.manager.getAllScores.keySet shouldBe ids.toSet

      // p1 has the oldest lastSeen, so the next new peer takes its place.
      val p4 = PeerId("p4")
      f.manager.recordSuccessfulHandshake(p4)
      f.manager.getAllScores.keySet shouldBe Set(ids(1), ids(2), p4)

      // Recency, not arrival order: a response refreshes p2, so the oldest is now p3.
      f.clock.windByMillis(1000)
      f.manager.recordResponse(ids(1), bytes = 0, latencyMs = 10)
      val p5 = PeerId("p5")
      f.manager.recordSuccessfulHandshake(p5)
      f.manager.getAllScores.keySet shouldBe Set(ids(1), p4, p5)
    }

  it should "keep a connected peer that went idle while more failed-handshake ids than the cap pass through" taggedAs
    (UnitTest, NetworkTest) in {
      // A public node's map fills with crawlers that failed a handshake a minute ago (wrong-network nodes, #88). Their
      // firstSeen is newer than the idle peer's lastSeen, but they were never seen alive, so they go first.
      val f = new Fixture(maxPeers = 8)
      val idle = PeerId("idle-connected")
      f.manager.recordSuccessfulHandshake(idle)
      f.clock.windByMillis(1000)

      (1 to 40).foreach { i =>
        f.manager.recordFailedHandshake(PeerId(s"crawler-$i"))
        f.clock.windByMillis(60_000L)
      }

      f.manager.getAllScores.size shouldBe 8
      f.manager.getAllScores.keySet should contain(idle)
      f.manager.getScore(idle).successfulHandshakes shouldBe 1
    }

  it should "evict an entry never seen alive before one that was, whatever their ages" taggedAs
    (UnitTest, NetworkTest) in {
      val f = new Fixture(maxPeers = 2)
      val alive = PeerId("alive")
      val crawler = PeerId("crawler")
      f.manager.recordSuccessfulHandshake(alive)
      f.clock.windByMillis(3_600_000L)
      f.manager.recordFailedHandshake(crawler)
      f.clock.windByMillis(1000)

      f.manager.recordFailedHandshake(PeerId("newcomer"))

      f.manager.getAllScores.keySet shouldBe Set(alive, PeerId("newcomer"))
    }

  it should "stay within its cap over many distinct peers" taggedAs (UnitTest, NetworkTest) in {
    val f = new Fixture(maxPeers = 16)
    (1 to 500).foreach { i =>
      f.manager.recordFailedHandshake(PeerId(s"crawler-$i"))
      f.clock.windByMillis(10)
      f.manager.getAllScores.size should be <= 16
    }
    f.manager.getAllScores.size shouldBe 16
  }

  it should "stay within its cap when four threads add new peers at once" taggedAs (UnitTest, NetworkTest) in {
    val f = new Fixture(maxPeers = 32)
    val maxSeen = new java.util.concurrent.atomic.AtomicInteger(0)
    runConcurrently(4) { t =>
      (1 to 500).foreach { i =>
        f.manager.recordFailedHandshake(PeerId(s"t$t-$i"))
        maxSeen.accumulateAndGet(f.manager.getAllScores.size, math.max)
      }
    }
    maxSeen.get should be <= 32
    f.manager.getAllScores.size shouldBe 32
  }

  it should "report every recorded peer in getAllScores and the statistics" taggedAs (UnitTest, NetworkTest) in {
    val f = new Fixture
    val ids = (1 to 10).map(i => PeerId(s"peer-$i"))
    ids.foreach(f.manager.recordSuccessfulHandshake)
    f.manager.getAllScores.keySet shouldBe ids.toSet
    f.manager.getStatistics.totalPeers shouldBe 10
    f.manager.removePeer(ids.head)
    f.manager.getAllScores.keySet shouldBe ids.tail.toSet
  }

  it should "score the statistics from one instant and agree with the exported snapshot" taggedAs
    (UnitTest, NetworkTest) in {
      val f = new Fixture
      f.manager.recordSuccessfulHandshake(peer)
      f.manager.recordFailedHandshake(other)
      f.manager.publish()
      val snapshot = f.exported.synchronized(f.exported.last)
      snapshot.takenAt shouldBe Now
      snapshot.scores.keySet shouldBe Set(peer, other)
      snapshot.scores(peer) shouldBe f.score(peer) +- 1e-12
      snapshot.statistics shouldBe f.manager.getStatistics
    }

  it should "report the neutral score of a peer it has not heard of without recording it" taggedAs
    (UnitTest, NetworkTest) in {
      val f = new Fixture
      f.score(peer) should be > 0.0
      f.manager.getAllScores shouldBe empty
    }

  it should "let the decay show without an event: the same peer scores lower an hour later" taggedAs
    (UnitTest, NetworkTest) in {
      val f = new Fixture
      f.manager.recordSuccessfulHandshake(peer)
      val fresh = f.score(peer)
      f.clock.windByMillis(60 * 60 * 1000L)
      f.score(peer) should be < fresh
    }
