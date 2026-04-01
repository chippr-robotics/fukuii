package com.chipprbots.ethereum.blockchain.sync.snap

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.testkit.{TestKit, TestProbe}
import org.apache.pekko.util.ByteString

import org.scalatest.BeforeAndAfterEach
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.network.PeerActor
import com.chipprbots.ethereum.network.PeerId
import com.chipprbots.ethereum.network.p2p.messages.SNAP.AccountRange
import com.chipprbots.ethereum.testing.Tags._

class SnapServerCheckerSpec
    extends TestKit(ActorSystem("SnapServerCheckerSpec"))
    with AnyFlatSpecLike
    with Matchers
    with BeforeAndAfterEach
    with BeforeAndAfterAll {

  override def beforeEach(): Unit =
    SnapServerChecker.reset()

  override def afterAll(): Unit =
    TestKit.shutdownActorSystem(system)

  val testStateRoot: ByteString = ByteString(Array.fill(32)(0x42.toByte))

  "SnapServerChecker" should "create a valid GetAccountRange probe" taggedAs UnitTest in {
    val (requestId, msg) = SnapServerChecker.createProbe(testStateRoot)

    msg.rootHash shouldBe testStateRoot
    msg.startingHash shouldBe SnapServerChecker.ZeroHash
    msg.limitHash shouldBe SnapServerChecker.ZeroHash
    msg.responseBytes shouldBe 4096
    msg.requestId shouldBe requestId
  }

  it should "generate unique sequential request IDs" taggedAs UnitTest in {
    val id1 = SnapServerChecker.nextProbeRequestId
    val id2 = SnapServerChecker.nextProbeRequestId
    val id3 = SnapServerChecker.nextProbeRequestId

    id1 should be < id2
    id2 should be < id3
    (id1 should not).equal(id2)
  }

  it should "start request IDs from Long.MaxValue / 2" taggedAs UnitTest in {
    val id = SnapServerChecker.nextProbeRequestId
    id shouldBe BigInt(Long.MaxValue / 2)
  }

  it should "round-trip registerProbe and completeProbe" taggedAs UnitTest in {
    val requestId = BigInt(999)
    val peerId = PeerId("test-peer-1")

    SnapServerChecker.registerProbe(requestId, peerId)
    SnapServerChecker.pendingCount shouldBe 1

    val result = SnapServerChecker.completeProbe(requestId)
    result shouldBe Some(peerId)
    SnapServerChecker.pendingCount shouldBe 0
  }

  it should "return None for completeProbe with unknown requestId" taggedAs UnitTest in {
    val result = SnapServerChecker.completeProbe(BigInt(12345))
    result shouldBe None
  }

  it should "cancel a pending probe and return peerId" taggedAs UnitTest in {
    val requestId = BigInt(888)
    val peerId = PeerId("timeout-peer")

    SnapServerChecker.registerProbe(requestId, peerId)
    val result = SnapServerChecker.cancelProbe(requestId)
    result shouldBe Some(peerId)
    SnapServerChecker.pendingCount shouldBe 0
  }

  it should "return true for isServingSnap when accounts are non-empty" taggedAs UnitTest in {
    val response = AccountRange(
      requestId = BigInt(1),
      accounts = Seq((ByteString(Array.fill(32)(1.toByte)), Account())),
      proof = Seq.empty
    )
    SnapServerChecker.isServingSnap(response) shouldBe true
  }

  it should "return true for isServingSnap when only proofs are non-empty" taggedAs UnitTest in {
    val response = AccountRange(
      requestId = BigInt(1),
      accounts = Seq.empty,
      proof = Seq(ByteString(Array.fill(32)(0xAA.toByte)))
    )
    SnapServerChecker.isServingSnap(response) shouldBe true
  }

  it should "return false for isServingSnap when accounts and proofs are both empty" taggedAs UnitTest in {
    val response = AccountRange(
      requestId = BigInt(1),
      accounts = Seq.empty,
      proof = Seq.empty
    )
    SnapServerChecker.isServingSnap(response) shouldBe false
  }

  it should "track probed peers via hasBeenProbed" taggedAs UnitTest in {
    val testProbe = TestProbe()
    val peerId = PeerId("snap-peer")

    SnapServerChecker.hasBeenProbed(peerId) shouldBe false
    SnapServerChecker.sendProbe(testProbe.ref, testStateRoot, peerId)
    SnapServerChecker.hasBeenProbed(peerId) shouldBe true
  }

  it should "clearProbedState removes peer from probed set" taggedAs UnitTest in {
    val testProbe = TestProbe()
    val peerId = PeerId("test-clear")
    SnapServerChecker.sendProbe(testProbe.ref, testStateRoot, peerId)
    SnapServerChecker.hasBeenProbed(peerId) shouldBe true

    SnapServerChecker.clearProbedState(peerId)
    SnapServerChecker.hasBeenProbed(peerId) shouldBe false
  }

  it should "send PeerActor.SendMessage when sendProbe is called" taggedAs UnitTest in {
    val testProbe = TestProbe()
    val peerId = PeerId("msg-peer")

    SnapServerChecker.sendProbe(testProbe.ref, testStateRoot, peerId)

    val sent = testProbe.expectMsgType[PeerActor.SendMessage]
    sent.message should not be null
  }

  it should "reflect pending count correctly" taggedAs UnitTest in {
    SnapServerChecker.pendingCount shouldBe 0

    SnapServerChecker.registerProbe(BigInt(1), PeerId("p1"))
    SnapServerChecker.registerProbe(BigInt(2), PeerId("p2"))
    SnapServerChecker.pendingCount shouldBe 2

    SnapServerChecker.completeProbe(BigInt(1))
    SnapServerChecker.pendingCount shouldBe 1
  }

  it should "clear all state on reset" taggedAs UnitTest in {
    val testProbe = TestProbe()
    SnapServerChecker.sendProbe(testProbe.ref, testStateRoot, PeerId("reset-peer"))
    testProbe.expectMsgType[PeerActor.SendMessage]

    SnapServerChecker.pendingCount should be > 0
    SnapServerChecker.hasBeenProbed(PeerId("reset-peer")) shouldBe true

    SnapServerChecker.reset()

    SnapServerChecker.pendingCount shouldBe 0
    SnapServerChecker.hasBeenProbed(PeerId("reset-peer")) shouldBe false
    SnapServerChecker.nextProbeRequestId shouldBe BigInt(Long.MaxValue / 2)
  }
}
