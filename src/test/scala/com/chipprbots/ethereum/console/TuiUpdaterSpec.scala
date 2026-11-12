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
import scala.jdk.CollectionConverters.*

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger as LogbackLogger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.scalatest.concurrent.Eventually
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.slf4j.LoggerFactory

import com.chipprbots.ethereum.testing.Tags.*

/** [[TuiUpdater]]: the frame loop (tick → latest snapshot → render → key wait at the adaptive pace), against an
  * in-memory terminal (spec 027 FR-010, FR-011, T038, SC-004, SC-005).
  */
class TuiUpdaterSpec extends AnyFlatSpec with Matchers with Eventually:

  /** A frame must never block on keyboard input; bound it so a regression fails instead of hanging the suite. */
  private def tickWithin(updater: TuiUpdater): Boolean =
    Await.result(Future(updater.tick())(using ExecutionContext.global), 10.seconds)

  private val snapshot = TuiFixtures.snapshot(current = 100, best = 200, peers = 3)

  /** For a test that presses a key BEFORE it runs one frame. The frame waits for a key only as long as its pace, and at
    * the test pace (20 ms) that wait can expire before JLine's input pump hands the key over on a loaded CI runner: the
    * frame then reads no key and keeps going (PR #1809's CI, B-224). With a long pace the read still returns the moment
    * the key arrives (within one 10 ms read slice). Only for tests that press first: with no key, the frame waits the
    * whole 5 s.
    */
  private val keyConfig = TuiTestTerminal.config.copy(updateIntervalMs = 5_000, animationIntervalMs = 5_000)

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
    TuiTestTerminal.visible(tt.lastFrame) should include("3/50")
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
      onQuit = () => quitThread.success(Thread.currentThread()),
      config = keyConfig
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
    val updater = new TuiUpdater(tui, keyConfig, () => Some(snapshot), "mordor", quit)
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
    withUpdater(onQuit = () => quits.incrementAndGet(), config = keyConfig) { (updater, tui, tt) =>
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

  // SC-005: a probe that blocks holds its own thread only, and frames keep coming at 4 fps ("at least 8 frames in
  // 2.5 s"). Checked on the pace itself, each key wait's timeout, not on a frame count in wall time, which a loaded
  // runner can stretch.
  it should "keep the 250 ms pace while the probe is blocked" taggedAs (UnitTest) in {
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
    withUpdater(snapshots = () => published.get, config = paced) { (updater, tui, tt) =>
      try
        probe.start()
        updater.start()
        eventually(timeout(5.seconds), interval(10.millis))(tt.readTimeouts.size should be >= 3)
        tt.readTimeouts.take(3) shouldBe Seq(250L, 250L, 250L)
        tui.getState.tick should be >= 3L
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
      paced.copy(reducedMotion = true).paceMs(animated = false) shouldBe 1000L
      updater.start()
      eventually(timeout(5.seconds), interval(10.millis))(reads.get should be >= 1)
      eventually(timeout(5.seconds), interval(10.millis))(tt.readTimeouts should contain(1000L))
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

  /** The WARN and ERROR events the given classes log, on any thread, while `f` runs. */
  private def warningsDuring(classes: Class[?]*)(f: => Unit): List[(Level, String)] =
    val appender = new ListAppender[ILoggingEvent]
    appender.start()
    val loggers = classes.map(c => LoggerFactory.getLogger(c).asInstanceOf[LogbackLogger])
    val levels = loggers.map(_.getLevel)
    loggers.foreach { l =>
      l.addAppender(appender); l.setLevel(Level.WARN)
    }
    try f
    finally
      loggers.zip(levels).foreach { (l, level) =>
        l.setLevel(level); l.detachAppender(appender)
      }
    appender.list.asScala.toList.map(e => (e.getLevel, e.getFormattedMessage))

  // B-045, prism's review of slice 2: Ctrl-C and SIGTERM stop the updater while its thread waits for a key. JLine throws
  // InterruptedIOException there, which checkInput logged at ERROR as a failed read and answered with a shutdown.
  "Stopping the updater" should "log no ERROR and leave the TUI up while the loop waits for a key" taggedAs (
    UnitTest
  ) in withUpdater(config = TuiTestTerminal.config.copy(updateIntervalMs = 30_000, reducedMotion = true)) {
    (updater, tui, tt) =>
      updater.start()
      eventually(timeout(5.seconds), interval(10.millis))(tt.readTimeouts should contain(30_000L))
      val events = warningsDuring(classOf[Tui], classOf[TuiUpdater])(updater.stop())
      events.map(_._1) should not contain Level.ERROR
      updater.isRunning shouldBe false
      tui.isEnabled shouldBe true // the node's shutdown hook restores it; a stop is not a failed read
  }

  // prism, slice 2 review: a frame that failed before its key wait failed again at once, logging every time, for as long
  // as it kept failing.
  it should "give up, and give the terminal back, after repeated frame failures" taggedAs (UnitTest) in {
    val calls = new AtomicInteger(0)
    // Fails 50 times, then recovers: a loop that never gives up is still running at the end.
    withUpdater(snapshots =
      () => if calls.incrementAndGet() <= 50 then throw new IllegalStateException("boom") else Some(snapshot)
    ) { (updater, tui, _) =>
      updater.start()
      eventually(timeout(5.seconds), interval(10.millis))(updater.isRunning shouldBe false)
      tui.isEnabled shouldBe false
      calls.get should be < 50
    }
  }

  // prism, slice 2 re-review: an interrupt from anywhere but stop() ended the loop with `running` still set, leaving the
  // terminal in raw mode under a frozen frame, and nothing in the log. stop(), q and d all clear `running` first.
  it should "give the terminal back, with a WARN, when the loop ends without being asked to" taggedAs (UnitTest) in {
    val interrupted = new java.util.concurrent.atomic.AtomicBoolean(false)
    withUpdater(snapshots = () =>
      // Once, on the loop's own thread: an interrupt that is not stop()'s.
      if interrupted.compareAndSet(false, true) then Thread.currentThread().interrupt()
      Some(snapshot)
    ) { (updater, tui, tt) =>
      val events = warningsDuring(classOf[TuiUpdater]) {
        updater.start()
        eventually(timeout(5.seconds), interval(10.millis))(updater.isRunning shouldBe false)
      }
      tui.isEnabled shouldBe false
      tt.output should include(Tui.ExitAltScreen)
      events.map(_._1) shouldBe List(Level.WARN)
    }
  }

  // prism, slice 2 final review: a frame that threw InterruptedException ended the loop by clearing `running`, so the
  // unasked-exit rule never saw it, and the TUI stayed up in raw mode. Only stop(), q, d and the give-up clear it now.
  it should "give the terminal back, with a WARN, when an InterruptedException not from stop() ends the loop" taggedAs (
    UnitTest
  ) in {
    val thrown = new java.util.concurrent.atomic.AtomicBoolean(false)
    withUpdater(snapshots =
      () => if thrown.compareAndSet(false, true) then throw new InterruptedException("not stop()") else Some(snapshot)
    ) { (updater, tui, _) =>
      val events = warningsDuring(classOf[TuiUpdater]) {
        updater.start()
        eventually(timeout(5.seconds), interval(10.millis))(updater.isRunning shouldBe false)
      }
      tui.isEnabled shouldBe false
      events.map(_._1) shouldBe List(Level.WARN)
    }
  }

  // prism, slice 2 final review: when a throwable ends the loop, the WARN names it.
  it should "name the throwable that ended the loop, and give the terminal back" taggedAs (UnitTest) in {
    withUpdater(snapshots = () => throw new LinkageError("fatal frame")) { (updater, tui, _) =>
      val events = warningsDuring(classOf[TuiUpdater]) {
        updater.start()
        eventually(timeout(5.seconds), interval(10.millis))(updater.isRunning shouldBe false)
      }
      tui.isEnabled shouldBe false
      events.map(_._1) shouldBe List(Level.WARN)
      events.head._2 should include("LinkageError: fatal frame")
    }
  }

  // prism, slice 2 final review: when JLine's hook has closed the terminal, a key read can block in native code that
  // ignores the interrupt, and stop() waited out its join (1 s on every Ctrl-C). Once the TUI is down there is nothing
  // to wait for. The join here would be 60 s, so the 10 s await tells them apart.
  it should "not wait for the loop's thread once the TUI is down" taggedAs (UnitTest) in {
    val held = new CountDownLatch(1)
    val config =
      TuiTestTerminal.config.copy(shutdownTimeoutMs = 60_000, updateIntervalMs = 30_000, reducedMotion = true)
    withUpdater(config = config) { (updater, tui, tt) =>
      try
        tt.holdReads = Some(held)
        updater.start()
        eventually(timeout(5.seconds), interval(10.millis))(tt.readTimeouts should not be empty)
        tui.shutdown() // the TuiRestore hook got there first
        Await.result(Future(updater.stop())(using ExecutionContext.global), 10.seconds)
        updater.isRunning shouldBe false
      finally held.countDown()
    }
  }
