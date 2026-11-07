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

  // The bulk and in-place operations are inherited from mutable.MapOps and must reach addOne / subtractOne / clear,
  // which keep the count. One that wrote the map another way would leave the count stale.
  it should "equal the recount after any sequence of map operations, bulk and in-place ones included" taggedAs UnitTest in {
    val key = Gen.choose(0, 7)
    val value = Gen.choose(0, 5)
    val op: Gen[(Int, Int, Int, List[(Int, Int)])] =
      for
        // update, remove, clear, ++=, --=, filterInPlace, getOrElseUpdate, mapValuesInPlace
        kind <- Gen.frequency(8 -> 0, 3 -> 1, 1 -> 2, 2 -> 3, 2 -> 4, 2 -> 5, 3 -> 6, 2 -> 7)
        k <- key
        v <- value
        many <- Gen.listOf(Gen.zip(key, value))
      yield (kind, k, v, many)
    forAll(Gen.listOf(op), minSuccessful(500)) { ops =>
      val m = new HealAttemptMap(() => threshold)
      ops.foreach { op =>
        op match
          case (0, k, v, _)    => m.update(hash(k), v)
          case (1, k, _, _)    => m.remove(hash(k))
          case (2, _, _, _)    => m.clear()
          case (3, _, _, many) => m ++= many.map((k, v) => hash(k) -> v)
          case (4, _, _, many) => m --= many.map((k, _) => hash(k))
          case (5, _, v, _)    => m.filterInPlace((_, n) => n <= v)
          case (6, k, v, _)    => m.getOrElseUpdate(hash(k), v)
          case (_, _, v, _)    => m.mapValuesInPlace((_, n) => (n + v) % 6)
        m.unservableCount shouldBe recount(m)
      }
    }
  }
