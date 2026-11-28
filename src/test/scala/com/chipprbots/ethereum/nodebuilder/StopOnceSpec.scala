package com.chipprbots.ethereum.nodebuilder

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

import scala.jdk.CollectionConverters.*
import scala.util.Success
import scala.util.Try

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags.*

/** A node's stop runs once, and a second caller waits for its outcome (B-426, vault): the SIGTERM handler that loses
  * the race to a stop already under way must not exit while the database still closes. No sleeps: the stop is held on a
  * latch the test opens, and the waiting thread is observed parked on the stop's latch.
  */
class StopOnceSpec extends AnyFlatSpec with Matchers:

  /** Waits until `t` is blocked (WAITING) or has ended: the deterministic sign that it is parked on the stop's latch.
    */
  private def parked(t: Thread): Unit =
    while t.getState != Thread.State.WAITING && t.isAlive do Thread.onSpinWait()

  private def daemon(name: String)(body: => Unit): Thread =
    val t = new Thread(() => body, name)
    t.setDaemon(true)
    t

  "StopOnce" should "make a second caller wait for the first stop, then see its real outcome" taggedAs UnitTest in {
    val inStop = new CountDownLatch(1)
    val release = new CountDownLatch(1)
    val runs = new AtomicInteger(0)
    val stop = new StopOnce[String](() =>
      runs.incrementAndGet()
      inStop.countDown()
      release.await()
      "database closed"
    )
    val first = daemon("b426-first-stop")(stop.run(): Unit)
    first.start()
    inStop.await()

    val seen = new AtomicReference[Option[Try[String]]](null)
    val second = daemon("b426-signal")(seen.set(stop.run()))
    second.start()
    parked(second)
    // the first stop has not ended: the second caller has returned nothing yet
    second.isAlive shouldBe true
    seen.get shouldBe null

    release.countDown()
    second.join(10000)
    first.join(10000)
    seen.get shouldBe Some(Success("database closed"))
    runs.get shouldBe 1
  }

  it should "give a waiting caller the failure of a stop that threw" taggedAs UnitTest in {
    val inStop = new CountDownLatch(1)
    val release = new CountDownLatch(1)
    val stop = new StopOnce[Boolean](() =>
      inStop.countDown()
      release.await()
      throw new IllegalStateException("close failed")
    )
    val thrown = new AtomicReference[Throwable](null)
    val first = daemon("b426-failing-stop")(Try(stop.run()).failed.foreach(thrown.set))
    first.start()
    inStop.await()
    val seen = new AtomicReference[Option[Try[Boolean]]](null)
    val second = daemon("b426-signal-2")(seen.set(stop.run()))
    second.start()
    parked(second)
    release.countDown()
    second.join(10000)
    seen.get.flatMap(_.failed.toOption).map(_.getMessage) shouldBe Some("close failed")
    // the caller that ran it gets the error thrown, as from a direct call
    first.join(10000)
    thrown.get.getMessage shouldBe "close failed"
  }

  it should "return at once when re-entered from the thread that runs the stop" taggedAs UnitTest in {
    val inner = new ConcurrentLinkedQueue[Option[Try[Int]]]()
    lazy val stop: StopOnce[Int] = new StopOnce[Int](() =>
      // e.g. a hook that runs on the stopping thread and calls the stop again
      inner.add(stop.run())
      7
    )
    val done = new AtomicBoolean(false)
    val runner = daemon("b426-reentrant") {
      stop.run()
      done.set(true)
    }
    runner.start()
    runner.join(10000)
    done.get shouldBe true
    inner.asScala.toList shouldBe List(None)
    stop.outcome shouldBe Some(Success(7))
  }

  it should "leave without waiting from the JVM's hook, so a failed start's exit is not held up (B-274)" taggedAs UnitTest in {
    val inStop = new CountDownLatch(1)
    val release = new CountDownLatch(1)
    val stop = new StopOnce[Unit](() =>
      inStop.countDown()
      release.await()
    )
    val first = daemon("b426-overrunning-stop")(stop.run(): Unit)
    first.start()
    inStop.await()
    val hook = daemon("b426-hook")(stop.runOrLeave())
    hook.start()
    hook.join(10000)
    hook.isAlive shouldBe false
    stop.outcome shouldBe None
    release.countDown()
    first.join(10000)
    stop.outcome shouldBe Some(Success(()))
  }

  it should "run the stop from the JVM's hook when nothing else has" taggedAs UnitTest in {
    val runs = new AtomicInteger(0)
    val stop = new StopOnce[Unit](() => runs.incrementAndGet(): Unit)
    stop.runOrLeave()
    stop.run() shouldBe Some(Success(()))
    runs.get shouldBe 1
  }

  "StopSignal with a stop that started elsewhere" should "exit only after that stop ends, with its outcome" taggedAs UnitTest in {
    val inStop = new CountDownLatch(1)
    val release = new CountDownLatch(1)
    val stop = new StopOnce[Boolean](() =>
      inStop.countDown()
      release.await()
      true
    )
    val first = daemon("b426-error-stop")(stop.run(): Unit)
    first.start()
    inStop.await()

    val exits = new ConcurrentLinkedQueue[Int]()
    val signal = daemon("b426-sigterm") {
      StopSignal.onSignal(
        new AtomicBoolean(false),
        () => stop.run().exists(_.getOrElse(false)),
        code => exits.add(code): Unit
      )
    }
    signal.start()
    parked(signal)
    exits.isEmpty shouldBe true
    release.countDown()
    signal.join(10000)
    exits.asScala.toList shouldBe List(StopSignal.CleanExit)
  }
