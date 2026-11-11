package com.chipprbots.ethereum.db.storage.pathstate

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

import scala.collection.mutable

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags.*

/** Spec 018 Track C, C3b (T087): the one lock of the in-place writers of the state. */
class StateLockSpec extends AnyFlatSpec with Matchers:

  "The state lock" should "run a body with the lock held, name its holder meanwhile, and be free after" taggedAs UnitTest in {
    val lock = new StateLock
    lock.holder shouldBe None
    val result = lock.locked("a commit") {
      lock.holder shouldBe Some("a commit")
      lock.isHeldByCurrentThread shouldBe true
      // the proof of holding it is there to be asked for
      summon[StateLockHeld] should not be null
      42
    }
    result shouldBe 42
    lock.holder shouldBe None
    lock.isHeldByCurrentThread shouldBe false
  }

  it should "be free again when the body throws" taggedAs UnitTest in {
    val lock = new StateLock
    an[IllegalArgumentException] should be thrownBy lock.locked("a commit that fails")(
      throw new IllegalArgumentException("no")
    )
    lock.holder shouldBe None
    lock.locked("the next commit")(1) shouldBe 1
  }

  // An in-place write that starts inside another is a defect. A reentrant lock would let it through.
  it should "refuse its own holder, and say who was inside whom" taggedAs UnitTest in {
    val lock = new StateLock
    val refused = the[IllegalStateException] thrownBy lock.locked("a commit") {
      lock.locked("a repair")(())
    }
    refused.getMessage should (include("'a repair' inside 'a commit'"))
    // the outer holder released it on the way out
    lock.holder shouldBe None
    lock.locked("after")(()) shouldBe (())
  }

  it should "let one body run at a time, and serve those that wait in the order they asked" taggedAs UnitTest in {
    val lock = new StateLock
    val pool = Executors.newFixedThreadPool(4)
    try
      val inside = new AtomicInteger(0)
      val most = new AtomicInteger(0)
      val order = mutable.ArrayBuffer.empty[Int]
      val firstIsIn = new CountDownLatch(1)
      val release = new CountDownLatch(1)

      // the first holder waits inside, while three more ask one after another
      val first = pool.submit[Unit] { () =>
        lock.locked("holder 0") {
          firstIsIn.countDown()
          release.await(10, TimeUnit.SECONDS): Unit
          order.synchronized(order += 0): Unit
        }
      }
      firstIsIn.await(10, TimeUnit.SECONDS) shouldBe true
      val waiters = (1 to 3).map { i =>
        val queued = new CountDownLatch(1)
        val task = pool.submit[Unit] { () =>
          queued.countDown()
          lock.locked(s"holder $i") {
            val now = inside.incrementAndGet()
            most.accumulateAndGet(now, math.max): Unit
            order.synchronized(order += i): Unit
            inside.decrementAndGet(): Unit
          }
        }
        queued.await(10, TimeUnit.SECONDS) shouldBe true
        // it has asked: it is in the lock's queue before the next one asks
        eventuallyQueued(lock, i)
        task
      }
      release.countDown()
      (first +: waiters).foreach(_.get(10, TimeUnit.SECONDS))

      most.get shouldBe 1
      order.toSeq shouldBe Seq(0, 1, 2, 3)
    finally pool.shutdownNow(): Unit
  }

  it should "tell a caller how long it waited, once it holds the lock" taggedAs UnitTest in {
    val lock = new StateLock
    val waited = new AtomicLong(-1)
    lock.locked("a commit", waited.set) {
      waited.get should be >= 0L
    }
  }

  /** Waits, without sleeping, until `count` threads are queued on the lock. */
  private def eventuallyQueued(lock: StateLock, count: Int): Unit =
    val field = classOf[StateLock].getDeclaredField("lock")
    field.setAccessible(true)
    val inner = field.get(lock).asInstanceOf[java.util.concurrent.locks.ReentrantLock]
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
    while inner.getQueueLength < count && System.nanoTime() < deadline do Thread.onSpinWait()
    inner.getQueueLength should be >= count
