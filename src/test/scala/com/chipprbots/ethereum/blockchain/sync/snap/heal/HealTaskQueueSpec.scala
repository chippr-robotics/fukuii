package com.chipprbots.ethereum.blockchain.sync.snap.heal

import org.apache.pekko.util.ByteString

import scala.collection.mutable

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags.UnitTest

/** Spec 022 slice 2 (T020, T022; plan D1): the heal fetch queue's order and its pause policy, on plain values. */
class HealTaskQueueSpec extends AnyFlatSpec with Matchers:

  /** A fetch: a name, its depth, and its hash (one byte is enough here). */
  final case class F(name: String, depth: Int, h: Byte):
    def hash: ByteString = ByteString(Array(h))

  private def queue(freesHeld: mutable.Set[ByteString]): HealTaskQueue[F] =
    new HealTaskQueue[F](_.depth, _.hash, f => freesHeld(f.hash))

  "HealTaskQueue" should "dispatch the deepest fetch first, and fetches of one depth in the order queued" taggedAs UnitTest in {
    val q = queue(mutable.Set.empty)
    q ++= Seq(F("a", 2, 1), F("b", 9, 2), F("c", 2, 3), F("d", 0, 4), F("e", 9, 5), F("f", 70, 6))
    q.size shouldBe 6
    q.iterator.map(_.name).toSeq shouldBe Seq("f", "b", "e", "a", "c", "d")
    q.dequeue(4, paused = false).map(_.name) shouldBe Seq("f", "b", "e", "a")
    q.dequeue(4, paused = false).map(_.name) shouldBe Seq("c", "d")
    q shouldBe empty
  }

  it should "put a fetch queued again behind the fetches of its depth already queued" taggedAs UnitTest in {
    val q = queue(mutable.Set.empty)
    q ++= Seq(F("a", 3, 1), F("b", 3, 2))
    val first = q.dequeue(1, paused = false)
    first.map(_.name) shouldBe Seq("a")
    q ++= first // a timeout puts it back
    q.dequeue(2, paused = false).map(_.name) shouldBe Seq("b", "a")
  }

  it should "when paused, dispatch only the fetches that free held memory, deepest first, and resume the rest after" taggedAs UnitTest in {
    val freeing = mutable.Set(ByteString(Array[Byte](2)), ByteString(Array[Byte](4)))
    val q = queue(freeing)
    // A deep walk entry outranks a held node's shallow child when not paused, never when paused.
    q ++= Seq(F("walk", 60, 1), F("child", 5, 2), F("root", 0, 3), F("grandchild", 6, 4))
    q.openingCount shouldBe 2
    q.hasDispatchable(paused = true) shouldBe true
    q.dequeue(10, paused = true).map(_.name) shouldBe Seq("grandchild", "child")
    q.hasDispatchable(paused = true) shouldBe false
    q.dequeue(10, paused = true) shouldBe empty
    q.size shouldBe 2
    q.dequeue(10, paused = false).map(_.name) shouldBe Seq("walk", "root")
  }

  it should "move a queued new-subtree fetch that gains a held parent to the paused dispatch, keeping its place" taggedAs UnitTest in {
    val q = queue(mutable.Set.empty)
    q ++= Seq(F("x", 7, 1), F("y", 7, 2), F("z", 3, 3))
    q.dequeue(10, paused = true) shouldBe empty
    q.promote(ByteString(Array[Byte](2)))
    q.openingCount shouldBe 2
    q.size shouldBe 3
    q.dequeue(10, paused = true).map(_.name) shouldBe Seq("y")
    q.dequeue(10, paused = false).map(_.name) shouldBe Seq("x", "z")
    q shouldBe empty
  }

  it should "count, list and clear every queued fetch, a fetch queued twice included" taggedAs UnitTest in {
    val q = queue(mutable.Set.empty)
    q ++= Seq(F("a", 1, 1), F("a", 1, 1), F("b", 2, 2))
    q.size shouldBe 3
    q.iterator.count(_.name == "a") shouldBe 2
    q.clear()
    q shouldBe empty
    q.dequeue(5, paused = false) shouldBe empty
  }

  it should "when not paused, take both kinds of fetch strictly deepest first, ties in the order queued (eye F5)" taggedAs UnitTest in {
    val freeing = mutable.Set(ByteString(Array[Byte](2)), ByteString(Array[Byte](4)), ByteString(Array[Byte](5)))
    val q = queue(freeing)
    q ++= Seq(
      F("walk", 9, 1),
      F("child", 9, 2),
      F("shallow-child", 3, 4),
      F("root", 0, 3),
      F("deep-child", 12, 5),
      F("mid-walk", 3, 6)
    )
    q.openingCount shouldBe 3
    q.dequeue(10, paused = false).map(_.name) shouldBe
      Seq("deep-child", "walk", "child", "shallow-child", "mid-walk", "root")
  }
