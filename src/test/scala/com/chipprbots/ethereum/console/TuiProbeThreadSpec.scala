package com.chipprbots.ethereum.console

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.Behaviors

import scala.concurrent.duration.*

import org.scalatest.concurrent.Eventually
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.SyncController
import com.chipprbots.ethereum.blockchain.sync.SyncProtocol
import com.chipprbots.ethereum.blockchain.sync.SyncProtocol.Status.Progress
import com.chipprbots.ethereum.network.PeerManagerActor
import com.chipprbots.ethereum.testing.Tags.*

/** [[TuiProbeThread]] and the probe's one bounded wait (spec 027 FR-011, T037, SC-005). */
class TuiProbeThreadSpec extends ScalaTestWithActorTestKit with AnyFlatSpecLike with Matchers with Eventually:

  private val syncing = SyncProtocol.Status.Syncing(0, Progress(1000, 5000), None)

  private def syncController(status: SyncProtocol.Status): Behavior[SyncController.Command] =
    Behaviors.receiveMessage {
      case SyncController.WrappedSyncProtocol(SyncProtocol.GetStatus(replyTo)) =>
        replyTo ! status
        Behaviors.same
      case _ => Behaviors.same
    }

  private val emptyPeers: Behavior[PeerManagerActor.Command] = Behaviors.receiveMessage {
    case PeerManagerActor.GetPeersCmd(replyTo) =>
      replyTo ! PeerManagerActor.Peers(Map.empty)
      Behaviors.same
    case _ => Behaviors.same
  }

  /** Records when each ask arrives, and never replies. */
  private def silentRecording[A](arrived: AtomicReference[Option[Long]]): Behavior[A] =
    Behaviors.receiveMessage { _ =>
      arrived.compareAndSet(None, Some(System.nanoTime()))
      Behaviors.same
    }

  private def probe(
      pm: Behavior[PeerManagerActor.Command],
      sc: Behavior[SyncController.Command],
      wait: FiniteDuration = 500.millis
  ): TuiStatusProbe =
    new TuiStatusProbe(spawn(pm), spawn(sc), () => 42, 50, () => None, wait)(using system.scheduler)

  private def withThread(probe: () => NodeStatusSnapshot, intervalMs: Long = 1000)(
      test: AtomicReference[Option[NodeStatusSnapshot]] => Unit
  ): Unit =
    val published = new AtomicReference[Option[NodeStatusSnapshot]](None)
    val thread = new TuiProbeThread(probe, intervalMs, published)
    thread.start()
    try test(published)
    finally thread.stop()

  "TuiStatusProbe" should "start both asks together" taggedAs (UnitTest) in {
    val peersAt = new AtomicReference[Option[Long]](None)
    val syncAt = new AtomicReference[Option[Long]](None)
    val p = probe(silentRecording(peersAt), silentRecording(syncAt))
    p()
    val (a, b) = (peersAt.get.get, syncAt.get.get)
    // Both arrive before the first wait could have ended.
    Math.abs(a - b) / 1_000_000L should be < 250L
  }

  it should "wait once, for all its asks, not once per ask" taggedAs (UnitTest) in {
    val p = probe(Behaviors.ignore, Behaviors.ignore, 500.millis)
    val started = System.nanoTime()
    val s = p()
    val tookMs = (System.nanoTime() - started) / 1_000_000L
    tookMs should be >= 450L
    tookMs should be < 900L // two waits in a row would take 1,000 ms
    s.peers shouldBe Sourced.Unresponsive("no reply within 500ms")
    s.sync shouldBe Sourced.Unresponsive("no reply within 500ms")
  }

  it should "keep a source's last answer as Stale when it misses a wait, then call it Unresponsive" taggedAs (
    UnitTest
  ) in {
    val answer = new AtomicInteger(1)
    // Replies while `answer` is 1, then goes silent.
    val flaky: Behavior[PeerManagerActor.Command] = Behaviors.receiveMessage {
      case PeerManagerActor.GetPeersCmd(replyTo) if answer.get == 1 =>
        replyTo ! PeerManagerActor.Peers(Map.empty)
        Behaviors.same
      case _ => Behaviors.same
    }
    val p = probe(flaky, syncController(syncing), 100.millis)
    p().peers shouldBe a[Sourced.Fresh[?]]
    answer.set(0)
    p().peers shouldBe a[Sourced.Stale[?]]
    p().sync shouldBe a[Sourced.Fresh[?]]
  }

  "TuiProbeThread" should "publish snapshots on its own thread" taggedAs (UnitTest) in {
    val polls = new CountDownLatch(3)
    val p = probe(emptyPeers, syncController(syncing), 100.millis)
    withThread(
      () =>
        polls.countDown(); p()
      ,
      intervalMs = 20
    ) { published =>
      polls.await(10, TimeUnit.SECONDS) shouldBe true
      eventually(timeout(5.seconds)) {
        published.get.map(_.currentBlock) shouldBe Some(1000L)
      }
    }
  }

  // SC-005: a peer manager that never replies degrades the peers source only.
  it should "show the peers Unresponsive within 1.5 s while sync stays Fresh" taggedAs (UnitTest) in {
    val p = probe(Behaviors.ignore, syncController(syncing))
    val started = System.nanoTime()
    withThread(p) { published =>
      eventually(timeout(1500.millis), interval(20.millis)) {
        val s = published.get.get
        s.peers shouldBe a[Sourced.Unresponsive]
        s.sync shouldBe a[Sourced.Fresh[?]]
        s.connectionStatus shouldBe "Unresponsive (peer manager)"
        s.syncStatus shouldBe "Syncing"
      }
      (System.nanoTime() - started) / 1_000_000L should be < 1500L
    }
  }

  it should "keep polling after a poll throws" taggedAs (UnitTest) in {
    val calls = new AtomicInteger(0)
    val p = probe(emptyPeers, syncController(syncing), 100.millis)
    withThread(
      () =>
        if calls.incrementAndGet() == 1 then throw new RuntimeException("boom") else p()
      ,
      intervalMs = 20
    ) { published =>
      eventually(timeout(5.seconds))(published.get should not be empty)
      calls.get should be >= 2
    }
  }

  // A blocked probe holds only its own thread: stop() still returns, and the latch is released in finally.
  it should "stop while a poll is blocked" taggedAs (UnitTest) in {
    val release = new CountDownLatch(1)
    val entered = new CountDownLatch(1)
    val published = new AtomicReference[Option[NodeStatusSnapshot]](None)
    val thread = new TuiProbeThread(
      () =>
        entered.countDown(); release.await(); throw new IllegalStateException("released")
      ,
      1000,
      published
    )
    try
      thread.start()
      entered.await(5, TimeUnit.SECONDS) shouldBe true
      thread.stop(timeoutMs = 50)
      published.get shouldBe None
    finally release.countDown()
  }
