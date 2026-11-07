package com.chipprbots.ethereum.blockchain.sync.snap.heal

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags.UnitTest

/** Spec 022 plan D4, round 2 review A: each blocker's own wait. */
class BlockerClockSpec extends AnyFlatSpec with Matchers:

  private def h(n: Int): ByteString = ByteString(Array.fill(32)(n.toByte))
  private def minutes(clock: BlockerClock, n: Int): Unit = (1 to n).foreach(_ => clock.idleMinutePassed())

  "A code hash" should "have waited from when it was first seen, and not before" taggedAs UnitTest in {
    val clock = new BlockerClock
    minutes(clock, 100)
    clock.observeCode(Set(h(1)))
    clock.codeHasWaited(Set(h(1)), 30) shouldBe false
    minutes(clock, 29)
    clock.codeHasWaited(Set(h(1)), 30) shouldBe false
    minutes(clock, 1)
    clock.codeHasWaited(Set(h(1)), 30) shouldBe true
  }

  it should "keep its age while nothing waits on it, and when it is seen again" taggedAs UnitTest in {
    val clock = new BlockerClock
    clock.observeCode(Set(h(1)))
    minutes(clock, 10)
    clock.observeCode(Set.empty) // a re-peg dropped the leaf that waited
    minutes(clock, 10)
    clock.observeCode(Set(h(1))) // and the same leaf is back
    minutes(clock, 10)
    clock.codeHasWaited(Set(h(1)), 30) shouldBe true
  }

  it should "not be aged by a minute that was not idle" taggedAs UnitTest in {
    val clock = new BlockerClock
    (1 to 40).foreach(_ => clock.observeCode(Set(h(1))))
    clock.codeHasWaited(Set(h(1)), 1) shouldBe false
  }

  it should "start again if it is stored and asked for again" taggedAs UnitTest in {
    val clock = new BlockerClock
    clock.observeCode(Set(h(1)))
    minutes(clock, 40)
    clock.codeStored(h(1))
    clock.trackedCode shouldBe empty
    clock.observeCode(Set(h(1)))
    clock.codeHasWaited(Set(h(1)), 30) shouldBe false
  }

  "All blockers together" should "have waited only when every one has, and there is one" taggedAs UnitTest in {
    val clock = new BlockerClock
    clock.codeHasWaited(Set.empty, 0) shouldBe false
    clock.observeCode(Set(h(1)))
    minutes(clock, 29)
    clock.observeCode(Set(h(1), h(2)))
    minutes(clock, 1)
    clock.codeHasWaited(Set(h(1), h(2)), 30) shouldBe false
    withClue("the other one gone, the stuck one is enough: ") {
      clock.codeStored(h(2))
      clock.codeHasWaited(Set(h(1)), 30) shouldBe true
    }
  }

  it should "not count a hash the clock has not seen" taggedAs UnitTest in {
    val clock = new BlockerClock
    minutes(clock, 40)
    clock.codeHasWaited(Set(h(1)), 1) shouldBe false
  }

  "The map" should "hold the most that have blocked at once and a bounded number more, eldest going first" taggedAs UnitTest in {
    val clock = new BlockerClock(maxOrphanedCode = 2)
    clock.observeCode(Set(h(1), h(2), h(3)))
    minutes(clock, 30)
    clock.observeCode(Set(h(7)))
    clock.observeCode(Set(h(8)))
    clock.trackedCode should have size 5
    clock.observeCode(Set(h(9)))
    clock.trackedCode shouldBe Set(h(2), h(3), h(7), h(8), h(9))
    withClue("what is blocking is never the one dropped: ") {
      clock.observeCode(Set(h(1), h(2), h(3), h(4), h(5), h(9)))
      (clock.trackedCode should contain).allOf(h(1), h(2), h(3), h(4), h(5), h(9))
    }
    withClue("a dropped hash only starts again: ")(clock.codeHasWaited(Set(h(1)), 1) shouldBe false)
  }

  // Review hunter BC-1: a re-peg starts the pass over the set from nothing, so a tick part way through it sees only a
  // few of the stuck hashes blocking. The rest are not orphans: a re-peg comes sooner than 30 idle minutes, so a stuck
  // set over the cap, its eldest dropped at each one, would never reach the hand-off.
  it should "not take a stuck set for orphans while a pass is part way through it" taggedAs UnitTest in {
    val clock = new BlockerClock(maxOrphanedCode = 2)
    val stuck = (1 to 10).map(h).toSet
    clock.observeCode(stuck)
    (1 to 3).foreach { _ =>
      minutes(clock, 9)
      clock.observeCode(Set(h(1))) // a re-peg, and a tick with one page of the pass done
      minutes(clock, 1)
      clock.observeCode(stuck)
    }
    clock.codeHasWaited(stuck, 30) shouldBe true
  }

  private val sigA = BlockerClock.SetSignature.Empty.and(h(1))
  private val sigB = sigA.and(h(2))

  "The other clock" should "run from the first minute something other than code blocks" taggedAs UnitTest in {
    val clock = new BlockerClock
    minutes(clock, 100)
    clock.otherHasWaited(1) shouldBe false
    clock.otherBlocking(sigA)
    minutes(clock, 29)
    clock.otherBlocking(sigA)
    clock.otherHasWaited(30) shouldBe false
    minutes(clock, 1)
    clock.otherHasWaited(30) shouldBe true
  }

  it should "start again when the set's accounts change, and end when nothing but code blocks" taggedAs UnitTest in {
    val clock = new BlockerClock
    clock.otherBlocking(sigA)
    minutes(clock, 40)
    clock.otherBlocking(sigB)
    clock.otherHasWaited(1) shouldBe false
    minutes(clock, 40)
    clock.otherGone()
    clock.otherHasWaited(1) shouldBe false
  }

  "A signature" should "not depend on the order, and change with an entry" taggedAs UnitTest in {
    BlockerClock.SetSignature.Empty.and(h(1)).and(h(2)) shouldBe BlockerClock.SetSignature.Empty.and(h(2)).and(h(1))
    sigA should not be sigB
    withClue("the count tells apart what XOR alone would not: ") {
      BlockerClock.SetSignature.Empty.and(h(1)).and(h(1)) should not be BlockerClock.SetSignature.Empty
    }
  }
