package com.chipprbots.ethereum.domain

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

import scala.concurrent.duration.*

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

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

  "The node's head lock" should "warn after 2 s, under the Engine API's 8 s timeout of a forkchoiceUpdated" taggedAs UnitTest in {
    CanonicalHeadLock.WarnAfter shouldBe 2.seconds
  }
