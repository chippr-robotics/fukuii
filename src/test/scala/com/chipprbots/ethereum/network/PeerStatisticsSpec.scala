package com.chipprbots.ethereum.network

import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe as TypedTestProbe
import org.apache.pekko.actor.typed.ActorRef as TypedActorRef
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.testkit.TestProbe

import scala.concurrent.duration.*

import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.network.PeerEventBusActor.*
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.NewBlockHashes.NewBlockHashes
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.MockClock

class PeerStatisticsSpec extends ScalaTestWithActorTestKit with AnyFlatSpecLike with Matchers:

  import PeerStatisticsActor.*

  // Classic ActorSystem bridge for the (still Classic) PeerEventBus TestProbe.
  implicit val classicSystem: org.apache.pekko.actor.ActorSystem =
    system.classicSystem

  val TICK: Long = 50
  val mockClock: MockClock = new MockClock(0L):
    override def millis(): Long =
      windByMillis(TICK)
      super.millis()

  behavior.of("PeerStatisticsActor")

  it should "subscribe to peer events" taggedAs (UnitTest, NetworkTest) in new Fixture:
    // Subscriptions are sent to the Classic bus via the message adapter; the payloads are unchanged.
    peerEventBus.expectMsgType[SubscribeCmd].to shouldBe PeerStatisticsActor.MessageSubscriptionClassifier
    peerEventBus.expectMsgType[SubscribeCmd].to shouldBe SubscriptionClassifier.PeerDisconnectedClassifier(
      PeerSelector.AllPeers
    )

  it should "initially return default stats for unknown peers" taggedAs (UnitTest, NetworkTest) in new Fixture:
    val peerId: PeerId = PeerId("Alice")
    peerStatistics ! GetStatsForPeer(1.minute, peerId, statsForPeerProbe.ref)
    statsForPeerProbe.expectMessage(StatsForPeer(peerId, PeerStat.empty))

  it should "initially return default stats when there are no peers" taggedAs (UnitTest, NetworkTest) in new Fixture:
    peerStatistics ! GetStatsForAll(1.minute, statsForAllProbe.ref)
    statsForAllProbe.expectMessage(StatsForAll(Map.empty))

  it should "count received messages" taggedAs (UnitTest, NetworkTest) in new Fixture:
    val alice: PeerId = PeerId("Alice")
    val bob: PeerId = PeerId("Bob")
    peerStatistics ! PeerStatisticsActor.PeerMessageReceived(NewBlockHashes(Seq.empty), alice)
    peerStatistics ! PeerStatisticsActor.PeerMessageReceived(NewBlockHashes(Seq.empty), bob)
    peerStatistics ! PeerStatisticsActor.PeerMessageReceived(NewBlockHashes(Seq.empty), alice)
    peerStatistics ! GetStatsForAll(1.minute, statsForAllProbe.ref)

    val stats: StatsForAll = statsForAllProbe.expectMessageType[StatsForAll]
    stats.stats should not be empty

    val statA: PeerStat = stats.stats(alice)
    statA.responsesReceived shouldBe 2
    val difference: Option[Long] = for
      first <- statA.firstSeenTimeMillis
      last <- statA.lastSeenTimeMillis
    yield last - first
    assert(difference.exists(_ >= TICK))

    val statB: PeerStat = stats.stats(bob)
    statB.responsesReceived shouldBe 1
    statB.lastSeenTimeMillis shouldBe statB.firstSeenTimeMillis

  trait Fixture:
    val statsForAllProbe: TypedTestProbe[StatsForAll] = testKit.createTestProbe[StatsForAll]()
    val statsForPeerProbe: TypedTestProbe[StatsForPeer] = testKit.createTestProbe[StatsForPeer]()

    val peerEventBus: TestProbe = TestProbe()
    val peerStatistics: TypedActorRef[Command] =
      testKit.spawn(
        PeerStatisticsActor(
          peerEventBus.ref.toTyped[PeerEventBusActor.Command],
          slotDuration = 1.minute,
          slotCount = 30
        )(using mockClock)
      )
