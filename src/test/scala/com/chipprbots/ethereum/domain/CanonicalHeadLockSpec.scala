package com.chipprbots.ethereum.domain

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

import scala.concurrent.duration.*

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.ledger.ChainInsertLock
import com.chipprbots.ethereum.testing.Tags.*

/** B-511: a writer that waits longer than the bound for the head's lock says so at WARN, with what holds the lock, and
  * again once it has it. The node's bound is [[CanonicalHeadLock.WarnAfter]], 2 s, against the Engine API's 8 s timeout
  * of a forkchoiceUpdated; here a short one, with the holder let go only once the first WARN is said.
  */
class CanonicalHeadLockSpec extends AnyFlatSpec with Matchers:

  "The head's lock" should "say at WARN who holds it when a writer waits longer than the bound for it" taggedAs UnitTest in {
    val warned = new CountDownLatch(1)
    @volatile var warnings: List[String] = Nil
    val lock = new HeadLock(
      50.millis,
      message =>
        warnings :+= message
        warned.countDown()
    )
    val holding = new CountDownLatch(1)
    val holder = new Thread(() =>
      lock.held("the p2p import of blocks [3-26]") {
        holding.countDown()
        // the holder lets go once the waiter has said it waits, and not before
        warned.await(30, TimeUnit.SECONDS): Unit
      }
    )
    holder.start()
    holding.await(30, TimeUnit.SECONDS) shouldBe true
    lock.held("the fork choice to block 2")(()) shouldBe ()
    holder.join(30_000)
    warnings.size shouldBe 2
    warnings.head should include("the fork choice to block 2 has waited")
    warnings.head should include("held by the p2p import of blocks [3-26], for")
    warnings(1) should include("the fork choice to block 2 had the head's lock after")
  }

  it should "say nothing of a wait within the bound, nor of a hold taken again by its holder" taggedAs UnitTest in {
    @volatile var warnings: List[String] = Nil
    val lock = new HeadLock(2.seconds, message => warnings :+= message)
    lock.held("the import")(lock.held("its settle")(lock.isHeldByCurrentThread)) shouldBe true
    lock.isHeldByCurrentThread shouldBe false
    warnings shouldBe Nil
  }

  // B-511 (fairness): a batch onto the head lets the lock go between its holds and asks for it again at once. A writer
  // that waits has it first, so a fork choice waits one hold, not the whole batch.
  it should "be had by a writer that waits before the holder that lets it go can take it again" taggedAs UnitTest in {
    val lock = new HeadLock(30.seconds, _ => ())
    lock.isFair shouldBe true
    CanonicalHeadLock.isFair shouldBe true
    @volatile var order: List[String] = Nil
    val holding = new CountDownLatch(1)
    val goOn = new CountDownLatch(1)
    val batch = new Thread(() =>
      lock.held("the batch's first hold") {
        holding.countDown()
        goOn.await(30, TimeUnit.SECONDS): Unit
      }
      lock.held("the batch's next hold")(order :+= "batch")
    )
    batch.start()
    holding.await(30, TimeUnit.SECONDS) shouldBe true
    val forkChoice = new Thread(() => lock.held("the fork choice")(order :+= "fork choice"))
    forkChoice.start()
    val deadline = System.nanoTime() + 30_000_000_000L
    while !lock.isWaiting(forkChoice) && System.nanoTime() < deadline do Thread.onSpinWait()
    lock.isWaiting(forkChoice) shouldBe true
    goOn.countDown()
    batch.join(30_000)
    forkChoice.join(30_000)
    order shouldBe List("fork choice", "batch")
  }

  // B-511: every wait for the lock is measured (`consensus.head.lock.wait.seconds`), the outermost hold's only
  it should "tell how long each outermost hold waited for it" taggedAs UnitTest in {
    @volatile var waits: List[Long] = Nil
    val lock = new HeadLock(2.seconds, _ => (), waited = nanos => waits :+= nanos)
    lock.held("the import")(lock.held("its settle")(()))
    lock.held("a fork choice")(())
    waits.size shouldBe 2
    all(waits) should be >= 0L
  }

  // B-511 (the order guard): the head's lock is taken before the chain insert lock and the state's commit lock. A thread
  // that holds one of those and asks for the head's lock first is a deadlock with one that holds the head's lock and
  // asks for that one; it is told at once, before it waits, and only when the head's lock is not held already.
  it should "tell a thread that asks for it while it holds a lock taken inside it" taggedAs UnitTest in {
    @volatile var told: List[String] = Nil
    @volatile var inside: Option[String] = None
    val lock = new HeadLock(2.seconds, _ => (), heldInside = () => inside, wrongOrder = message => told :+= message)
    inside = Some("the chain insert lock")
    lock.held("the payload's entry")(())
    told.size shouldBe 1
    told.head should include("the payload's entry asks for the head's lock while it holds the chain insert lock")
    // the head's lock first, the other inside it, and the head's lock again: the right order, nothing told
    told = Nil
    inside = None
    lock.held("the import") {
      inside = Some("the chain insert lock")
      lock.held("its settle")(())
    }
    told shouldBe Nil
  }

  "The node's head lock" should "throw in the tests when it is asked for inside the chain insert lock" taggedAs UnitTest in {
    System.getProperty(CanonicalHeadLock.OrderCheckProperty) shouldBe "fail"
    val thrown = intercept[IllegalStateException](
      ChainInsertLock.shared(CanonicalHeadLock.held("a writer in the wrong order")(()))
    )
    thrown.getMessage should include(
      "a writer in the wrong order asks for the head's lock while it holds the chain insert lock"
    )
    intercept[IllegalStateException](
      ChainInsertLock.exclusive(CanonicalHeadLock.held("a writer in the wrong order")(()))
    ): Unit
    // in the right order it is had, and taken again inside the chain insert lock
    CanonicalHeadLock.held("the import")(ChainInsertLock.shared(CanonicalHeadLock.held("its settle")(1))) shouldBe 1
    CanonicalHeadLock.isHeldByCurrentThread shouldBe false
  }

  it should "let a batch onto the head hold it for less than the wait after which a writer is said to wait long" taggedAs UnitTest in {
    CanonicalHeadLock.ImportHoldBudget should be < CanonicalHeadLock.WarnAfter
  }

  it should "warn after 2 s, under the Engine API's 8 s timeout of a forkchoiceUpdated" taggedAs UnitTest in {
    CanonicalHeadLock.WarnAfter shouldBe 2.seconds
  }
