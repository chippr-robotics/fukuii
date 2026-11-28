package com.chipprbots.ethereum.jsonrpc

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

import cats.effect.IO
import cats.effect.kernel.Outcome
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*

import scala.concurrent.duration.*

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.db.storage.pathstate.ReadScope
import com.chipprbots.ethereum.testing.Tags.*

/** Spec 018, B-240: [[StateRead.reading]] runs the body of a trace or a simulation off the compute pool, all of it on
  * one blocking thread inside one scope of reads, and no more than [[StateRead.ReadingPermits]] of them at once.
  */
class StateReadReadingSpec extends AnyFlatSpec with Matchers:

  private val blockingThread = "(.*-blocker-.*)|(.*-blocking-.*)".r

  "A reading body" should "run inside a scope of reads, on a thread of the blocking pool" taggedAs UnitTest in {
    val computeThread = IO(Thread.currentThread.getName).unsafeRunSync()
    val (thread, open, sameThreadAtEnd) = StateRead
      .reading {
        val start = Thread.currentThread
        val open = ReadScope.isOpen
        (start.getName, open, Thread.currentThread eq start)
      }
      .unsafeRunSync()

    open shouldBe true
    sameThreadAtEnd shouldBe true
    withClue(s"body on $thread, compute on $computeThread: ") {
      blockingThread.matches(thread) shouldBe true
      blockingThread.matches(computeThread) shouldBe false
    }
    // and the scope is the body's: it is closed once the body returns
    ReadScope.isOpen shouldBe false
  }

  it should "wait for a permit when every permit is held, and then run" taggedAs UnitTest in {
    val permits = StateRead.ReadingPermits
    val entered = new CountDownLatch(permits)
    val release = new CountDownLatch(1)
    val extraRan = new AtomicBoolean(false)

    def holding(i: Int): IO[Int] = StateRead.reading {
      entered.countDown()
      // bounded, so that bodies run on the compute pool (as they did) starve it for a while and not for good
      if !release.await(30, TimeUnit.SECONDS) then throw new IllegalStateException("never released")
      i
    }

    val run = for
      holders <- (0 until permits).toList.traverse(i => holding(i).start)
      _ <- IO
        .blocking(entered.await(30, TimeUnit.SECONDS))
        .flatMap(IO.raiseUnless(_)(new IllegalStateException("holders never entered")))
      extra <- StateRead.reading { extraRan.set(true); "extra" }.start
      // the extra body is queued on the permits: none is left while the holders run
      _ <- StateRead.readingPermitsCount.flatTap(n => IO.cede.whenA(n >= 0)).iterateUntil(_ < 0)
      startedWhileHeld = extraRan.get
      _ <- IO(release.countDown())
      held <- holders.traverse(_.joinWithNever)
      outcome <- extra.join
      answer <- outcome.embedNever
    yield (startedWhileHeld, held, outcome, answer)

    // a fail-safe: without the permits the extra body never queues, and the wait for it would spin
    val (startedWhileHeld, held, outcome, answer) = run.timeout(30.seconds).unsafeRunSync()
    startedWhileHeld shouldBe false
    held shouldBe (0 until permits).toList
    // it waited, and was not refused
    outcome shouldBe a[Outcome.Succeeded[?, ?, ?]]
    answer shouldBe "extra"
    extraRan.get shouldBe true
  }
