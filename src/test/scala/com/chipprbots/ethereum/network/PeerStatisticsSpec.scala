package com.chipprbots.ethereum.network

import org.apache.pekko.actor._
import org.apache.pekko.testkit.TestKit
import org.apache.pekko.testkit.TestProbe

import scala.concurrent.duration._

import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.WithActorSystemShutDown
import com.chipprbots.ethereum.network.PeerEventBusActor._
import com.chipprbots.ethereum.network.p2p.messages.ETH61.NewBlockHashes
import com.chipprbots.ethereum.utils.MockClock

class PeerStatisticsSpec
    extends TestKit(ActorSystem("PeerStatisticsSpec_System"))
    with AnyFlatSpecLike
    with WithActorSystemShutDown
    with Matchers {

  import PeerStatisticsActor._

  val TICK: Long = 50
  val mockClock: MockClock = new MockClock(0L) {
    override def millis(): Long = {
      windByMillis(TICK)
      super.millis()
    }
  }

  behavior.of("PeerStatisticsActor")

  it should "subscribe to peer events" in new Fixture {
    peerEventBus.expectMsg(Subscribe(PeerStatisticsActor.MessageSubscriptionClassifier))
    peerEventBus.expectMsg(Subscribe(SubscriptionClassifier.PeerDisconnectedClassifier(PeerSelector.AllPeers)))
  }

  it should "initially return default stats for unknown peers" in new Fixture {
    val peerId: PeerId = PeerId("Alice")
    peerStatistics ! GetStatsForPeer(1.minute, peerId)
    sender.expectMsg(StatsForPeer(peerId, PeerStat.empty))
  }

  it should "initially return default stats when there are no peers" in new Fixture {
    peerStatistics ! GetStatsForAll(1.minute)
    sender.expectMsg(StatsForAll(Map.empty))
  }

  it should "count received messages" in new Fixture {
    val alice: PeerId = PeerId("Alice")
    val bob: PeerId = PeerId("Bob")
    peerStatistics ! PeerEvent.MessageFromPeer(NewBlockHashes(Seq.empty), alice)
    peerStatistics ! PeerEvent.MessageFromPeer(NewBlockHashes(Seq.empty), bob)
    peerStatistics ! PeerEvent.MessageFromPeer(NewBlockHashes(Seq.empty), alice)
    peerStatistics ! GetStatsForAll(1.minute)

    val stats: StatsForAll = sender.expectMsgType[StatsForAll]
    stats.stats should not be empty

    val statA: PeerStat = stats.stats(alice)
    statA.responsesReceived shouldBe 2
    val difference: Option[Long] = for {
      first <- statA.firstSeenTimeMillis
      last <- statA.lastSeenTimeMillis
    } yield last - first
    assert(difference.exists(_ >= TICK))

    val statB: PeerStat = stats.stats(bob)
    statB.responsesReceived shouldBe 1
    statB.lastSeenTimeMillis shouldBe statB.firstSeenTimeMillis
  }

  trait Fixture {
    val sender: TestProbe = TestProbe()
    implicit val senderRef: ActorRef = sender.ref

    val peerEventBus: TestProbe = TestProbe()
    val peerStatistics: ActorRef =
      system.actorOf(PeerStatisticsActor.props(peerEventBus.ref, slotDuration = 1.minute, slotCount = 30)(mockClock))
  }
}
