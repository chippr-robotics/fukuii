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

  it should "keep waiting through an interrupt, then exit with the other stop's outcome and the interrupt set" taggedAs UnitTest in {
    val stop = new HeldStop(clean = true)
    val first = daemon("b452-other-stop")(stop.once.run(): Unit)
    first.start()
    stop.inStop.await(10, TimeUnit.SECONDS) shouldBe true

    // (status, interrupted, whether the other stop had ended) at the exit
    val exits = new ConcurrentLinkedQueue[(Int, Boolean, Boolean)]()
    val waiter = daemon("b452-waiter") {
      StopSignal.stopThenExit(
        "test",
        stop.stopForExit,
        code => exits.add((code, Thread.currentThread().isInterrupted, stop.once.outcome.isDefined)): Unit
      )
    }
    waiter.start()
    parked(waiter)
    waiter.interrupt()
    // whether or not it has woken yet, it does not exit while the other stop still runs
    parked(waiter)
    waiter.isAlive shouldBe true
    exits.isEmpty shouldBe true
    stop.release.countDown()
    waiter.join(10000)
    first.join(10000)
    // the clean status is the other stop's outcome: only a wait that lasted to its end could see it
    exits.asScala.toList shouldBe List((StopSignal.CleanExit, true, true))
  }

  // B-520: every exit path asked from a daemon thread (the TUI's quitter, the JVM's SIGTERM handler, the Ephemery reset
  // watchdog), and the stop ran on it, so the JVM exited mid-stop once the actor system's threads ended, before the
  // database closed. The stop runs on a thread that is no daemon, whoever asks.
  "StopSignal.stopThenExit" should "run the stop on a thread that is no daemon, though asked from a daemon (B-520)" taggedAs UnitTest in {
    val stopRanOnDaemon = new java.util.concurrent.atomic.AtomicReference[Option[Boolean]](None)
    val exits = new ConcurrentLinkedQueue[Int]()
    val asker = daemon("b520-asker") {
      StopSignal.stopThenExit(
        "test",
        () =>
          stopRanOnDaemon.set(Some(Thread.currentThread().isDaemon))
          true
        ,
        code => exits.add(code): Unit
      )
    }
    asker.isDaemon shouldBe true
    asker.start()
    asker.join(10000)
    stopRanOnDaemon.get shouldBe Some(false)
    exits.asScala.toList shouldBe List(StopSignal.CleanExit)
  }

  it should "start the stop aside on a thread that is no daemon, though asked from a daemon (B-520)" taggedAs UnitTest in {
    val stopRanOnDaemon = new java.util.concurrent.atomic.AtomicReference[Option[Boolean]](None)
    val done = new java.util.concurrent.CountDownLatch(1)
    val asker = daemon("b520-aside-asker") {
      StopSignal.stopThenExitAside(
        "test",
        () =>
          stopRanOnDaemon.set(Some(Thread.currentThread().isDaemon))
          true
        ,
        _ => done.countDown()
      )()
    }
    asker.start()
    done.await(10, TimeUnit.SECONDS) shouldBe true
    stopRanOnDaemon.get shouldBe Some(false)
  }

  // B-440: the store failing during an import stops the node. The import holds what the database's close
  // waits for, so the stop runs on a thread of its own and the call returns at once; the exit is unclean whatever the
  // stop ends in, and a second failure starts no second stop
  "A failure that ends the node" should "stop it aside, once, and exit with the unclean status even after a clean stop" taggedAs UnitTest in {
    val started = new ConcurrentLinkedQueue[Runnable]()
    val stops = new java.util.concurrent.atomic.AtomicInteger(0)
    val exits = new ConcurrentLinkedQueue[Int]()
    val fail = StopSignal.stopThenExitAside(
      "the store failed",
      () =>
        stops.incrementAndGet()
        true
      ,
      code => exits.add(code): Unit,
      started.add(_): Unit
    )
    fail()
    // nothing ran on the caller's thread: the stop is the started thread's
    (stops.get, exits.isEmpty) shouldBe (0, true)
    fail()
    started.size shouldBe 1
    started.peek().run()
    stops.get shouldBe 1
    exits.asScala.toList shouldBe List(StopSignal.UncleanExit)
  }
