package com.chipprbots.ethereum.console

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.duration.*

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags.*

/** [[TuiUpdater]]: the poll → render → key loop, against an in-memory terminal and a fake status source. */
class TuiUpdaterSpec extends AnyFlatSpec with Matchers:

  /** A tick must never block on keyboard input; bound it so a regression fails instead of hanging the suite. */
  private def tickWithin(updater: TuiUpdater): Boolean =
    Await.result(Future(updater.tick())(using ExecutionContext.global), 10.seconds)

  private val snapshot = NodeStatusSnapshot("Connected", 3, 25, 100, 200, "Syncing")

  private def withUpdater(
      statusSource: () => NodeStatusSnapshot = () => snapshot,
      onQuit: () => Unit = () => ()
  )(test: (TuiUpdater, Tui, TuiTestTerminal) => Unit): Unit =
    val tt = new TuiTestTerminal()
    val tui = new Tui(TuiTestTerminal.config, () => tt.terminal)
    tui.initialize() shouldBe true
    val updater = new TuiUpdater(tui, TuiTestTerminal.config, statusSource, "mordor", onQuit)
    try test(updater, tui, tt)
    finally
      updater.stop()
      tui.shutdown()
      tt.close()

  "TuiUpdater.tick" should "poll the node and draw what it reports" taggedAs (UnitTest) in withUpdater() {
    (updater, tui, tt) =>
      tickWithin(updater) shouldBe true
      tui.getState.peerCount shouldBe 3
      tui.getState.currentBlock shouldBe 100
      tui.getState.bestBlock shouldBe 200
      TuiTestTerminal.visible(tt.lastFrame) should include("3 / 25")
  }

  it should "keep going when the status source fails, and show the failure" taggedAs (UnitTest) in withUpdater(
    statusSource = () => throw new RuntimeException("boom")
  ) { (updater, tui, _) =>
    tickWithin(updater) shouldBe true
    tui.getState.connectionStatus shouldBe "Error: boom"
  }

  it should "run the quit hook off the updater thread on 'q'" taggedAs (UnitTest) in {
    val quitThread = Promise[Thread]()
    @volatile var tickThread: Thread = null
    withUpdater(
      statusSource = () =>
        tickThread = Thread.currentThread(); snapshot
      ,
      onQuit = () => quitThread.success(Thread.currentThread())
    ) { (updater, _, tt) =>
      tt.press("q")
      tickWithin(updater) shouldBe false
      tickThread should not be null
      Await.result(quitThread.future, 5.seconds) should not be tickThread
    }
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

  // Regression for the frozen display: with no key pressed the loop must keep polling and redrawing.
  "TuiUpdater" should "refresh repeatedly with no keyboard input" taggedAs (UnitTest) in {
    val polls = new CountDownLatch(5)
    withUpdater(statusSource = () =>
      polls.countDown(); snapshot
    ) { (updater, _, _) =>
      updater.start()
      updater.isRunning shouldBe true
      polls.await(10, TimeUnit.SECONDS) shouldBe true
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
    val updater = new TuiUpdater(tui, TuiTestTerminal.config, () => snapshot, "etc", () => ())
    updater.start()
    updater.isRunning shouldBe false
  }

  it should "stop promptly when stop() is called from its own thread" taggedAs (UnitTest) in {
    val stopped = new CountDownLatch(1)
    var self: TuiUpdater = null
    withUpdater(statusSource = () =>
      self.stop(); stopped.countDown(); snapshot
    ) { (updater, _, _) =>
      self = updater
      updater.start()
      stopped.await(10, TimeUnit.SECONDS) shouldBe true
      updater.isRunning shouldBe false
    }
  }
