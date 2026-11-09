package com.chipprbots.ethereum.console

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.Behaviors

import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger as LogbackLogger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.scalatest.concurrent.Eventually
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.slf4j.LoggerFactory

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
        s.peers should matchPattern { case Sourced.Unresponsive(_) => }
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
      () => if calls.incrementAndGet() == 1 then throw new RuntimeException("boom") else p(),
      intervalMs = 20
    ) { published =>
      eventually(timeout(5.seconds))(published.get should not be empty)
      calls.get should be >= 2
    }
  }

  // A blocked probe holds only its own thread: stop() returns while it waits, and the poll that was in flight when stop()
  // was called publishes nothing once it returns. The latch is released in finally.
  it should "stop while a poll is blocked, and publish nothing after the stop" taggedAs (UnitTest) in {
    val release = new CountDownLatch(1)
    val entered = new CountDownLatch(1)
    val pollThread = new AtomicReference[Thread]()
    val published = new AtomicReference[Option[NodeStatusSnapshot]](None)
    val polled = TuiFixtures.snapshot(current = 1, best = 2)
    val thread = new TuiProbeThread(
      () =>
        pollThread.set(Thread.currentThread()); entered.countDown(); release.await(); polled
      ,
      1000,
      published
    )
    try
      thread.start()
      entered.await(5, TimeUnit.SECONDS) shouldBe true
      thread.stop(timeoutMs = 50)
    finally release.countDown()
    pollThread.get.join(5000)
    pollThread.get.isAlive shouldBe false
    published.get shouldBe None
  }

  // prism, slice 2 review: after `d`, a lost terminal or a failed key read the frame loop ends, but the probe went on
  // asking the actors every second for nobody.
  it should "stop polling once the TUI it feeds is no longer enabled" taggedAs (UnitTest) in {
    val enabled = new AtomicBoolean(true)
    val polls = new AtomicInteger(0)
    val published = new AtomicReference[Option[NodeStatusSnapshot]](None)
    val p = probe(emptyPeers, syncController(syncing), 100.millis)
    val thread = new TuiProbeThread(
      () =>
        polls.incrementAndGet(); p()
      ,
      20,
      published,
      () => enabled.get
    )
    try
      thread.start()
      eventually(timeout(5.seconds))(polls.get should be >= 2)
      enabled.set(false)
      eventually(timeout(5.seconds))(thread.isRunning shouldBe false)
    finally thread.stop()
  }

  // prism, slice 2 review: a fatal error escaped the probe loop to the thread's default handler, which can print over
  // the TUI's screen. It is logged, and polling stops.
  it should "log a fatal error from a poll, and stop polling" taggedAs (UnitTest) in {
    val logger = LoggerFactory.getLogger(classOf[TuiProbeThread]).asInstanceOf[LogbackLogger]
    val appender = new ListAppender[ILoggingEvent]
    appender.start()
    logger.addAppender(appender)
    val published = new AtomicReference[Option[NodeStatusSnapshot]](None)
    val thread = new TuiProbeThread(() => throw new LinkageError("fatal poll"), 20, published)
    try
      thread.start()
      eventually(timeout(5.seconds))(thread.isRunning shouldBe false)
      appender.list.asScala.map(_.getLevel) should contain(Level.ERROR)
    finally
      thread.stop()
      logger.detachAppender(appender)
  }
