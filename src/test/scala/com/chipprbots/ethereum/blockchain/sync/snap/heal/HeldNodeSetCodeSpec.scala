package com.chipprbots.ethereum.blockchain.sync.snap.heal

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags.UnitTest

/** Spec 022 D4: code as an outstanding dependency of a held account leaf. */
class HeldNodeSetCodeSpec extends AnyFlatSpec with Matchers:

  private def h(n: Int): ByteString = ByteString(Array.fill(32)(n.toByte))
  private def request(n: Int): HealRequest = HealRequest.ofNode(Seq(ByteString(Array[Byte](0))), h(n))
  private val code = h(0x77)
  private val account = h(0x78)
  private val node = ByteString(Array.fill(100)(1.toByte))

  private def dep = Some(HeldNodeSet.CodeDependency(code, account))

  "A held leaf" should "wait on its code even with no child outstanding, and be ready when it arrives" taggedAs UnitTest in {
    val set = new HeldNodeSet
    set.hold(request(1), node, 0, Nil, dep)
    set.hasPendingWrites shouldBe false
    set.awaitingCode shouldBe true
    set.awaitedCode shouldBe Map(code -> Seq(account))
    set.codeArrived(code) shouldBe 1
    set.awaitingCode shouldBe false
    set.hasPendingWrites shouldBe true
    set.readyCount shouldBe 1
  }

  it should "stay held after its code arrives while a child is outstanding" taggedAs UnitTest in {
    val set = new HeldNodeSet
    val parent = set.hold(request(1), node, 1, Nil, dep)
    set.codeArrived(code)
    set.hasPendingWrites shouldBe false
    set.hold(request(2), node, 0, Seq(parent.id))
    set.applyCommit(set.planCommit())
    set.contains(parent.id) shouldBe false
  }

  it should "not commit its parent while it waits: the wait counts like a child" taggedAs UnitTest in {
    val set = new HeldNodeSet
    val parent = set.hold(request(1), node, 1, Nil)
    set.hold(request(2), node, 0, Seq(parent.id), dep)
    set.planCommit().nodes shouldBe empty
    set.codeArrived(code)
    set.planCommit().nodes.map(_.id) shouldBe Seq(request(2).key, parent.id)
  }

  it should "wake every leaf waiting on one code hash, each once" taggedAs UnitTest in {
    val set = new HeldNodeSet
    set.hold(request(1), node, 0, Nil, dep)
    set.hold(request(2), node, 0, Nil, dep)
    set.codeArrived(code) shouldBe 2
    set.readyCount shouldBe 2
    set.codeArrived(code) shouldBe 0
  }

  it should "forget a dropped node's wait, so a later hold of the same node counts one wait, not two" taggedAs UnitTest in {
    val set = new HeldNodeSet
    val first = set.hold(request(1), node, 1, Nil, dep)
    set.drop(first.id)
    set.awaitingCode shouldBe false
    val again = set.hold(request(1), node, 1, Nil, dep)
    set.codeArrived(code) shouldBe 1
    again.outstanding shouldBe 1 // its child is still outstanding
    set.hasPendingWrites shouldBe false
  }

  it should "forget every wait at a clear" taggedAs UnitTest in {
    val set = new HeldNodeSet
    set.hold(request(1), node, 0, Nil, dep)
    set.clear()
    set.awaitingCode shouldBe false
    set.codeArrived(code) shouldBe 0
    set.bytes shouldBe 0L
  }

  it should "count the wait in its memory estimate, and give it back" taggedAs UnitTest in {
    val plain = new HeldNodeSet
    plain.hold(request(1), node, 0, Nil)
    val waiting = new HeldNodeSet
    val e = waiting.hold(request(1), node, 0, Nil, dep)
    waiting.bytes should be > plain.bytes
    waiting.drop(e.id)
    waiting.bytes shouldBe 0L
  }

  it should "ignore code nothing waits on" taggedAs UnitTest in {
    val set = new HeldNodeSet
    set.hold(request(1), node, 1, Nil)
    set.codeArrived(code) shouldBe 0
    set.hasPendingWrites shouldBe false
  }
