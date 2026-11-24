package com.chipprbots.ethereum.nodebuilder

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

import scala.jdk.CollectionConverters.*

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.console.Tui
import com.chipprbots.ethereum.console.TuiTestTerminal
import com.chipprbots.ethereum.testing.Tags.*

/** The exit paths that start once the node is up exit only after the node's stop has ended (B-452): the TUI's `q` and
  * the Ephemery reset, as SIGTERM already did (B-426). An exit made first would run the JVM's hook, which does not wait
  * for a stop under way (B-274), and the process would end while the database still closes. No sleeps: the stop is held
  * on a latch the test opens, and the exiting thread is observed parked on the stop's latch.
  */
class StopThenExitSpec extends AnyFlatSpec with Matchers:

  /** Waits until `t` is blocked (WAITING) or has ended, failing after 10 s rather than spinning for ever. */
  private def parked(t: Thread): Unit =
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
    while t.getState != Thread.State.WAITING && t.isAlive do
      if System.nanoTime() - deadline > 0 then fail(s"${t.getName} did not park within 10 s: ${t.getState}")
      Thread.onSpinWait()

  private def daemon(name: String)(body: => Unit): Thread =
    val t = new Thread(() => body, name)
    t.setDaemon(true)
    t

  /** A stop run once, held until `release` opens; `inStop` opens when it begins. `clean` is its outcome. */
  final private class HeldStop(clean: Boolean, onEnter: () => Unit = () => ()):
    val inStop = new CountDownLatch(1)
    val release = new CountDownLatch(1)
    val once = new StopOnce[Boolean](() =>
      onEnter()
      inStop.countDown()
      release.await()
      clean
    )
    // What the node's stopForExit does: run the stop or wait for it, then say whether it was clean.
    val stopForExit: () => Boolean = () => once.run().exists(_.getOrElse(false))

  private def withTui(test: Tui => Unit): Unit =
    val tt = new TuiTestTerminal()
    val tui = tt.tui()
    tui.initialize() shouldBe true
    try test(tui)
    finally
      tui.shutdown()
      tt.close()

  "The TUI's quit" should "restore the terminal, then run the stop, and exit only once the stop has ended" taggedAs UnitTest in
    withTui { tui =>
      val tuiUpAtStop = new AtomicReference[Option[Boolean]](None)
      val stop = new HeldStop(clean = true, onEnter = () => tuiUpAtStop.set(Some(tui.isEnabled)))
      val exits = new ConcurrentLinkedQueue[Int]()
      val quitter = daemon("b452-tui-quit")(StdNode.tuiQuit(tui, stop.stopForExit, code => exits.add(code): Unit)())
      quitter.start()
      stop.inStop.await(10, TimeUnit.SECONDS) shouldBe true
      // raw mode and the alternate screen are already given back when the stop starts
      tuiUpAtStop.get shouldBe Some(false)
      parked(quitter)
      exits.isEmpty shouldBe true
      stop.release.countDown()
      quitter.join(10000)
      exits.asScala.toList shouldBe List(StopSignal.CleanExit)
    }

  it should "wait for a stop that began elsewhere, then exit with its outcome" taggedAs UnitTest in withTui { tui =>
    val stop = new HeldStop(clean = true)
    // e.g. SIGTERM, a failed start or the periodic consistency check began the stop first
    val first = daemon("b452-other-stop")(stop.once.run(): Unit)
    first.start()
    stop.inStop.await(10, TimeUnit.SECONDS) shouldBe true

    val exits = new ConcurrentLinkedQueue[Int]()
    val quitter = daemon("b452-tui-quit-2")(StdNode.tuiQuit(tui, stop.stopForExit, code => exits.add(code): Unit)())
    quitter.start()
    parked(quitter)
    exits.isEmpty shouldBe true
    tui.isEnabled shouldBe false
    stop.release.countDown()
    quitter.join(10000)
    first.join(10000)
    exits.asScala.toList shouldBe List(StopSignal.CleanExit)
  }

  it should "exit 1 when the stop did not close the database" taggedAs UnitTest in withTui { tui =>
    val exits = new ConcurrentLinkedQueue[Int]()
    StdNode.tuiQuit(tui, () => false, code => exits.add(code): Unit)()
    exits.asScala.toList shouldBe List(StopSignal.UncleanExit)
  }

  it should "still exit, with 1, when the stop throws" taggedAs UnitTest in withTui { tui =>
    val exits = new ConcurrentLinkedQueue[Int]()
    StdNode.tuiQuit(tui, () => throw new IllegalStateException("close failed"), code => exits.add(code): Unit)()
    exits.asScala.toList shouldBe List(StopSignal.UncleanExit)
    tui.isEnabled shouldBe false
  }

  "StopSignal.stopThenExit with a fixed status (the Ephemery reset)" should "exit with that status only after the stop" taggedAs UnitTest in {
    val stop = new HeldStop(clean = false)
    val exits = new ConcurrentLinkedQueue[Int]()
    val resetter = daemon("b452-ephemery-reset") {
      StopSignal.stopThenExit("exit 75", stop.stopForExit, code => exits.add(code): Unit, _ => 75)
    }
    resetter.start()
    stop.inStop.await(10, TimeUnit.SECONDS) shouldBe true
    parked(resetter)
    exits.isEmpty shouldBe true
    stop.release.countDown()
    resetter.join(10000)
    // the supervisor's contract (spec 028 exit-codes.md) holds whatever the close did; the log says it was unclean
    exits.asScala.toList shouldBe List(75)
  }

  "StopSignal.stopThenExit" should "still exit with the unclean status when the stop dies of a fatal error (B-467)" taggedAs UnitTest in {
    val exits = new ConcurrentLinkedQueue[Int]()
    val halts = new ConcurrentLinkedQueue[Int]()
    // fatal (not NonFatal), but not a VirtualMachineError
    StopSignal.stopThenExit(
      "test",
      () => throw new LinkageError("test-only fatal error"),
      code => exits.add(code): Unit,
      halt = code => halts.add(code): Unit
    )
    exits.asScala.toList shouldBe List(StopSignal.UncleanExit)
    halts.isEmpty shouldBe true
  }

  it should "halt, not exit, when the stop dies of a VirtualMachineError (B-467)" taggedAs UnitTest in withTui { tui =>
    val exits = new ConcurrentLinkedQueue[Int]()
    val halts = new ConcurrentLinkedQueue[Int]()
    // constructed, not provoked: no stack overflows here
    val quit = StdNode.tuiQuit(
      tui,
      () => throw new StackOverflowError("test-only"),
      code => exits.add(code): Unit,
      halt = code => halts.add(code): Unit
    )
    quit()
    halts.asScala.toList shouldBe List(StopSignal.UncleanExit)
    exits.isEmpty shouldBe true
    tui.isEnabled shouldBe false
  }

  it should "still exit when its wait for a stop under way is interrupted, keeping the interrupt (B-467)" taggedAs UnitTest in {
    val stop = new HeldStop(clean = true)
    val first = daemon("b467-other-stop")(stop.once.run(): Unit)
    first.start()
    stop.inStop.await(10, TimeUnit.SECONDS) shouldBe true

    val exits = new ConcurrentLinkedQueue[(Int, Boolean)]()
    val waiter = daemon("b467-waiter") {
      StopSignal.stopThenExit(
        "test",
        stop.stopForExit,
        code => exits.add((code, Thread.currentThread().isInterrupted)): Unit
      )
    }
    waiter.start()
    parked(waiter)
    exits.isEmpty shouldBe true
    waiter.interrupt()
    waiter.join(10000)
    exits.asScala.toList shouldBe List((StopSignal.UncleanExit, true))
    stop.release.countDown()
    first.join(10000)
  }
