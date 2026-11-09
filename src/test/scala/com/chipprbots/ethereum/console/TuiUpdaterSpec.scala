package com.chipprbots.ethereum.console

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.duration.*

import org.scalatest.concurrent.Eventually
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags.*

/** [[TuiUpdater]]: the frame loop (tick → latest snapshot → render → key wait at the adaptive pace), against an
  * in-memory terminal (spec 027 FR-010, FR-011, T038, SC-004, SC-005).
  */
class TuiUpdaterSpec extends AnyFlatSpec with Matchers with Eventually:

  /** A frame must never block on keyboard input; bound it so a regression fails instead of hanging the suite. */
  private def tickWithin(updater: TuiUpdater): Boolean =
    Await.result(Future(updater.tick())(using ExecutionContext.global), 10.seconds)

  private val snapshot = TuiFixtures.snapshot(current = 100, best = 200, peers = 3)

  private def withUpdater(
      snapshots: () => Option[NodeStatusSnapshot] = () => Some(snapshot),
      onQuit: () => Unit = () => (),
      config: TuiConfig = TuiTestTerminal.config
  )(test: (TuiUpdater, Tui, TuiTestTerminal) => Unit): Unit =
    val tt = new TuiTestTerminal()
    val tui = tt.tui(config)
    tui.initialize() shouldBe true
    val updater = new TuiUpdater(tui, config, snapshots, "mordor", onQuit)
    try test(updater, tui, tt)
    finally
      updater.stop()
      tui.shutdown()
      tt.close()

  "TuiUpdater.tick" should "draw the latest snapshot" taggedAs (UnitTest) in withUpdater() { (updater, tui, tt) =>
    tickWithin(updater) shouldBe true
    tui.getState.peerCount shouldBe 3
    tui.getState.currentBlock shouldBe 100
    tui.getState.bestBlock shouldBe 200
    TuiTestTerminal.visible(tt.lastFrame) should include("3 / 50")
  }

  it should "advance the frame counter every frame" taggedAs (UnitTest) in withUpdater() { (updater, tui, _) =>
    val before = tui.getState.tick
    tickWithin(updater)
    tickWithin(updater)
    tui.getState.tick shouldBe before + 2
  }

  it should "keep drawing before the first snapshot" taggedAs (UnitTest) in withUpdater(snapshots = () => None) {
    (updater, tui, tt) =>
      tickWithin(updater) shouldBe true
      tui.getState.connectionStatus shouldBe "Initializing"
      TuiTestTerminal.visible(tt.lastFrame) should include("q Quit")
  }

  it should "run the quit hook off the updater thread on 'q'" taggedAs (UnitTest) in {
    val quitThread = Promise[Thread]()
    @volatile var tickThread: Thread = null
    withUpdater(
      snapshots = () =>
        tickThread = Thread.currentThread(); Some(snapshot)
      ,
      onQuit = () => quitThread.success(Thread.currentThread())
    ) { (updater, _, tt) =>
      tt.press("q")
      tickWithin(updater) shouldBe false
      tickThread should not be null
      Await.result(quitThread.future, 5.seconds) should not be tickThread
    }
  }

  // Regression: `q` left the restore to the TuiRestore shutdown hook, which lost the race with JLine's own hook and
  // left the user's shell in the alternate screen with the cursor hidden.
  it should "restore the terminal on 'q' before the quit hook exits" taggedAs (UnitTest) in {
    val tt = new TuiTestTerminal()
    val tui = tt.tui()
    tui.initialize() shouldBe true
    // What the exit finds when it runs: the screen it would leave behind, and whether the TUI is still up.
    val atExit = Promise[(String, Boolean)]()
    val quit = TuiUpdater.restoreThenQuit(tui, () => atExit.success((tt.output, tui.isEnabled)))
    val updater = new TuiUpdater(tui, TuiTestTerminal.config, () => Some(snapshot), "mordor", quit)
    try
      tt.press("q")
      tickWithin(updater) shouldBe false
      val (screen, enabled) = Await.result(atExit.future, 5.seconds)
      screen should include(Tui.ExitAltScreen)
      screen should include(Tui.ShowCursor)
      enabled shouldBe false
      tt.processText shouldBe empty // restored through the open terminal, not around it
    finally
      updater.stop()
      tui.shutdown()
      tt.close()
  }

  it should "stop looping on 'd' without quitting the node" taggedAs (UnitTest) in {
    val quits = new AtomicInteger(0)
    withUpdater(onQuit = () => quits.incrementAndGet()) { (updater, tui, tt) =>
      tt.press("d")
      tickWithin(updater) shouldBe false
      tui.isEnabled shouldBe false
      quits.get shouldBe 0
    }
  }

  // Regression for the frozen display: with no key pressed the loop must keep reading and redrawing.
  "TuiUpdater" should "refresh repeatedly with no keyboard input" taggedAs (UnitTest) in {
    val reads = new CountDownLatch(5)
    withUpdater(snapshots = () =>
      reads.countDown(); Some(snapshot)
    ) { (updater, _, _) =>
      updater.start()
      updater.isRunning shouldBe true
      reads.await(10, TimeUnit.SECONDS) shouldBe true
      updater.stop()
      updater.isRunning shouldBe false
    }
  }

  it should "set the network name on start" taggedAs (UnitTest) in withUpdater() { (updater, tui, _) =>
    updater.start()
    tui.getState.networkName shouldBe "mordor"
  }

  it should "not start when the TUI is disabled" taggedAs (UnitTest) in {
    val tui = new Tui(TuiTestTerminal.config, () => throw new IllegalStateException("not a tty"))
    tui.initialize() shouldBe false
    val updater = new TuiUpdater(tui, TuiTestTerminal.config, () => Some(snapshot), "etc", () => ())
    updater.start()
    updater.isRunning shouldBe false
  }

  it should "stop promptly when stop() is called from its own thread" taggedAs (UnitTest) in {
    val stopped = new CountDownLatch(1)
    var self: TuiUpdater = null
    withUpdater(snapshots = () =>
      self.stop(); stopped.countDown(); Some(snapshot)
    ) { (updater, _, _) =>
      self = updater
      updater.start()
      stopped.await(10, TimeUnit.SECONDS) shouldBe true
      updater.isRunning shouldBe false
    }
  }

  // ---- T038: frames never wait on a probe; the pace; `p` -----------------------------------------------------------

  /** Spec values: 250 ms while something moves, 1000 ms otherwise (Q1). */
  private val paced = TuiTestTerminal.config.copy(animationIntervalMs = 250, updateIntervalMs = 1000)

  // SC-005: a probe that blocks holds its own thread only.
  it should "draw at least 8 frames in 2.5 s at the 250 ms pace while the probe is blocked" taggedAs (UnitTest) in {
    val release = new CountDownLatch(1)
    val published = new AtomicReference[Option[NodeStatusSnapshot]](None)
    val probe = new TuiProbeThread(
      () =>
        release.await(); snapshot
      ,
      1000,
      published
    )
    // Nothing published yet: the connection is "Initializing", whose spinner keeps the 250 ms pace.
    withUpdater(snapshots = () => published.get, config = paced) { (updater, tui, _) =>
      try
        probe.start()
        updater.start()
        eventually(timeout(5.seconds), interval(10.millis))(tui.getState.tick should be >= 1L)
        val first = tui.getState.tick
        eventually(timeout(2500.millis), interval(20.millis))(tui.getState.tick should be >= first + 8)
        published.get shouldBe None // the probe never returned
      finally
        release.countDown()
        probe.stop()
    }
  }

  // SC-004: at the 1000 ms pace a key is handled when it arrives, not at the next frame.
  it should "dispatch 'q' within 500 ms at the 1000 ms pace" taggedAs (UnitTest) in {
    val quitAt = Promise[Long]()
    val reads = new AtomicInteger(0)
    withUpdater(
      snapshots = () =>
        reads.incrementAndGet(); Some(snapshot)
      ,
      onQuit = () => quitAt.success(System.nanoTime()),
      config = paced.copy(reducedMotion = true)
    ) { (updater, tui, tt) =>
      updater.start()
      eventually(timeout(5.seconds), interval(10.millis))(reads.get should be >= 1)
      tui.getState.motion shouldBe Motion.Reduced
      val pressed = System.nanoTime()
      tt.press("q")
      val dispatched = Await.result(quitAt.future, 5.seconds)
      (dispatched - pressed) / 1_000_000L should be < 500L
    }
  }

  it should "pause the frame counter on 'p', keep reading snapshots, and resume on 'p'" taggedAs (UnitTest) in {
    val reads = new AtomicInteger(0)
    withUpdater(snapshots = () =>
      reads.incrementAndGet(); Some(snapshot)
    ) { (updater, tui, tt) =>
      updater.start()
      eventually(timeout(5.seconds), interval(10.millis))(tui.getState.tick should be >= 2L)
      tt.press("p")
      eventually(timeout(5.seconds), interval(10.millis))(tui.getState.motion shouldBe Motion.Paused)
      val pausedAt = tui.getState.tick
      val readsAt = reads.get
      eventually(timeout(5.seconds), interval(10.millis))(reads.get should be >= readsAt + 3)
      tui.getState.tick shouldBe pausedAt
      tt.press("p")
      eventually(timeout(5.seconds), interval(10.millis))(tui.getState.tick should be > pausedAt)
      tui.getState.motion shouldBe Motion.Full
    }
  }
