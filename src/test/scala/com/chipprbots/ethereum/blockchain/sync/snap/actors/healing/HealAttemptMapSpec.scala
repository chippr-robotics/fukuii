package com.chipprbots.ethereum.blockchain.sync.snap.actors.healing

import org.apache.pekko.util.ByteString

import org.scalacheck.Gen
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

import com.chipprbots.ethereum.testing.Tags.UnitTest

/** #1675: the healing gauge pulse read the number of unservable tasks by recounting every attempt counter. The map
  * keeps that number as it changes; it must always equal the recount.
  */
class HealAttemptMapSpec extends AnyFlatSpec with Matchers with ScalaCheckPropertyChecks:

  private val threshold = 2

  private def recount(m: HealAttemptMap): Int = m.count { case (_, n) => n > threshold }

  private def hash(i: Int): ByteString = ByteString(Array.fill[Byte](32)(i.toByte))

  "HealAttemptMap" should "count the tasks past the threshold through the coordinator's own operations" taggedAs UnitTest in {
    val m = new HealAttemptMap(() => threshold)
    def attempt(i: Int): Unit = m.update(hash(i), m.getOrElse(hash(i), 0) + 1) // HealDispatch.noteUnservableAttempt

    (1 to 3).foreach(_ => attempt(1)) // 1 crosses (3 > 2)
    (1 to 2).foreach(_ => attempt(2)) // at the threshold, not past it
    m.unservableCount shouldBe 1
    attempt(2) // 2 crosses
    attempt(1) // 1 stays past it: counted once
    m.unservableCount shouldBe 2
    m.unservableCount shouldBe recount(m)

    m.remove(hash(1)) // HealResponseProcessing: the task was satisfied
    m.unservableCount shouldBe 1
    m.remove(hash(9)) // nothing to remove
    m.unservableCount shouldBe 1

    m.clear() // a serve-root advance
    m.unservableCount shouldBe 0
    m shouldBe empty
  }

  it should "equal the recount after any sequence of updates, removals and clears" taggedAs UnitTest in {
    val op: Gen[(Int, Int, Int)] =
      for
        kind <- Gen.frequency(8 -> 0, 3 -> 1, 1 -> 2) // update, remove, clear
        key <- Gen.choose(0, 7)
        value <- Gen.choose(0, 5)
      yield (kind, key, value)
    forAll(Gen.listOf(op)) { ops =>
      val m = new HealAttemptMap(() => threshold)
      ops.foreach {
        case (0, key, value) => m.update(hash(key), value)
        case (1, key, _)     => m.remove(hash(key))
        case _               => m.clear()
      }
      m.unservableCount shouldBe recount(m)
    }
  }
