package com.chipprbots.ethereum.blockchain.sync.regular

import java.net.InetSocketAddress

import org.apache.pekko.actor.testkit.typed.FishingOutcome
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe
import org.apache.pekko.actor.typed.ActorRef as TypedActorRef
import org.apache.pekko.util.ByteString

import cats.effect.IO

import scala.concurrent.Await
import scala.concurrent.Promise
import scala.concurrent.duration.*

import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockNumber
import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.domain.Difficulty
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.PeerActor
import com.chipprbots.ethereum.network.PeerEventBusActor
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerEvent
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerEvent.MessageFromPeer
import com.chipprbots.ethereum.network.PeerEventBusActor.SubscribeCmd
import com.chipprbots.ethereum.network.PeerEventBusActor.SubscriptionClassifier.MessageClassifier
import com.chipprbots.ethereum.network.PeerId
import com.chipprbots.ethereum.network.p2p.messages.Codes
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.BlockHeaders
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.GetBlockHeaders
import com.chipprbots.ethereum.testing.Tags.*

/** The fetch of a refused branch's headers for the escalation (B-300 g; forge): it ends on an answer with none, once
  * the headers outweigh the head, or at the most it is to fetch, and never on an answer with fewer than were asked.
  */
class BranchHeadersFetchSpec extends ScalaTestWithActorTestKit with AnyFlatSpecLike with Matchers:

  private val commonBlock: BlockHeader = Fixtures.Blocks.ValidBlock.header.copy(number = BlockNumber(10))

  /** `count` headers of a branch on the common block, of difficulty 100 each. */
  private def branch(count: Int): Vector[BlockHeader] =
    (1 to count).foldLeft(Vector.empty[BlockHeader]) { (made, i) =>
      val parent = made.lastOption.getOrElse(commonBlock)
      made :+ commonBlock.copy(
        parentHash = parent.hash,
        number = BlockNumber(commonBlock.number.value + i),
        difficulty = Difficulty(100),
        extraData = ByteString(s"of the branch $i")
      )
    }

  private class Rig(wanted: Int, perRequest: Int, headWeight: Option[BigInt] = None):
    val peer: Peer = Peer(
      PeerId("branch-peer"),
      new InetSocketAddress("127.0.0.1", 0),
      testKit.createTestProbe[PeerActor.Command]().ref,
      incomingConnection = false
    )
    val networkPeerManager: TestProbe[NetworkPeerManagerActor.Command] =
      testKit.createTestProbe[NetworkPeerManagerActor.Command]()
    val peerEventBus: TestProbe[PeerEventBusActor.Command] = testKit.createTestProbe[PeerEventBusActor.Command]()
    val told: Promise[List[BlockHeader]] = Promise()
    // the common block weighs 1000; the branch outweighs the head when its weight above that is more than the head's
    private def outweighs(headers: List[BlockHeader]): Boolean =
      headWeight.exists { head =>
        headers.foldLeft(ChainWeight.totalDifficultyOnly(1000))(_.increase(_)) > ChainWeight.totalDifficultyOnly(head)
      }
    val fetch: TypedActorRef[BranchHeadersFetch.Command] = testKit.spawn(
      BranchHeadersFetch(
        peer,
        commonBlock,
        wanted,
        perRequest,
        5.seconds,
        networkPeerManager.ref,
        peerEventBus.ref,
        outweighs,
        headers => IO(told.success(headers)).void
      )
    )

    /** The next request, answered with `headers`: the request. */
    def answer(headers: Seq[BlockHeader]): GetBlockHeaders =
      val request = networkPeerManager
        .expectMessageType[NetworkPeerManagerActor.SendMessageCmd]
        .message
        .underlyingMsg
        .asInstanceOf[GetBlockHeaders]
      val answerTo: TypedActorRef[PeerEvent] = peerEventBus.fishForMessage(3.seconds) {
        case SubscribeCmd(MessageClassifier(codes, _), _) if codes.contains(Codes.BlockHeadersCode) =>
          FishingOutcome.Complete
        case _ => FishingOutcome.ContinueAndIgnore
      } match
        case Seq(SubscribeCmd(_, subscriber)) => subscriber
        case other                            => fail(s"no subscription for the answer: $other")
      answerTo ! MessageFromPeer(BlockHeaders(request.requestId, headers), peer.id)
      request

    def toldHeaders(): List[BlockHeader] = Await.result(told.future, 5.seconds)

    def stopped(): Unit = testKit.createTestProbe[Any]().expectTerminated(fetch, 5.seconds)

  "The fetch of a branch's headers" should "go on after an answer with fewer headers than were asked for" taggedAs UnitTest in {
    val rig = new Rig(wanted = 20, perRequest = 4)
    val headers = branch(6)
    rig.answer(headers.take(1)).maxHeaders shouldBe BigInt(4)
    // a peer's limit, not the branch's end: the next are asked for
    val second = rig.answer(headers.slice(1, 5))
    second.block shouldBe Left(BigInt(12))
    rig.answer(headers.slice(5, 6)).block shouldBe Left(BigInt(16))
    // an answer with none ends it, and what was fetched is told
    rig.answer(Nil).block shouldBe Left(BigInt(17))
    rig.toldHeaders() shouldBe headers.toList
    rig.stopped()
  }

  it should "stop once the headers fetched outweigh the head" taggedAs UnitTest in {
    // 1000 + 2 x 100 is not more than 1250; 1000 + 4 x 100 is
    val rig = new Rig(wanted = 100, perRequest = 2, headWeight = Some(1250))
    val headers = branch(10)
    rig.answer(headers.take(2))
    rig.answer(headers.slice(2, 4))
    rig.toldHeaders() shouldBe headers.take(4).toList
    rig.networkPeerManager.expectNoMessage(300.millis)
    rig.stopped()
  }

  it should "stop at the most it is to fetch, and ask for no more than that" taggedAs UnitTest in {
    val rig = new Rig(wanted = 5, perRequest = 2)
    val headers = branch(10)
    rig.answer(headers.take(2))
    rig.answer(headers.slice(2, 4))
    rig.answer(headers.slice(4, 5)).maxHeaders shouldBe BigInt(1)
    rig.toldHeaders() shouldBe headers.take(5).toList
    rig.networkPeerManager.expectNoMessage(300.millis)
    rig.stopped()
  }

  it should "tell nothing for an answer that is not the branch's next headers, nor for a first answer with none" taggedAs UnitTest in {
    val unlinked = new Rig(wanted = 20, perRequest = 2)
    val headers = branch(6)
    unlinked.answer(headers.take(2))
    unlinked.answer(headers.slice(3, 5))
    unlinked.stopped()
    unlinked.told.isCompleted shouldBe false

    val none = new Rig(wanted = 20, perRequest = 2)
    none.answer(Nil)
    none.stopped()
    none.told.isCompleted shouldBe false
  }

  // each rule of a link on its own: a header that is the next by number but not the child of the one before, and one
  // that names the one before but is not the next by number
  it should "tell nothing for a header of the next number whose parent is not the header before it" taggedAs UnitTest in {
    val rig = new Rig(wanted = 2, perRequest = 2)
    val first = branch(1).head
    val notItsChild = first.copy(
      parentHash = commonBlock.hash,
      number = BlockNumber(first.number.value + 1),
      extraData = ByteString("of another branch")
    )
    rig.answer(Seq(first, notItsChild))
    rig.stopped()
    rig.told.isCompleted shouldBe false
  }

  it should "tell nothing for a header that names the header before it as its parent, at another number" taggedAs UnitTest in {
    val rig = new Rig(wanted = 2, perRequest = 2)
    val first = branch(1).head
    val skipping = first.copy(
      parentHash = first.hash,
      number = BlockNumber(first.number.value + 2),
      extraData = ByteString("a number skipped")
    )
    rig.answer(Seq(first, skipping))
    rig.stopped()
    rig.told.isCompleted shouldBe false
  }

  "The refusals of a branch not taken beyond the fetch" should "be counted in a row at the same block, and again at another" taggedAs UnitTest in {
    val refusals = new BlockImporter.BeyondTheFetchRefusals
    (1 to 3).map(_ => refusals.record(14)) shouldBe Seq(1, 2, 3)
    refusals.record(15) shouldBe 1
    refusals.record(14) shouldBe 1
    BlockImporter.BeyondTheFetchRepeatsWarnAt shouldBe 3
  }
