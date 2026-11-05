package com.chipprbots.ethereum.network

import java.net.InetSocketAddress

import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.stream.scaladsl.Sink
import org.apache.pekko.testkit.TestProbe
import org.apache.pekko.util.ByteString

import org.scalatest.concurrent.ScalaFutures
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.PeerInfo
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.RemoteStatus
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerEvent
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerEvent.MessageFromPeer
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerEvent.PeerDisconnected
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerEvent.PeerHandshakeSuccessful
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerSelector
import com.chipprbots.ethereum.network.PeerEventBusActor.PublishCmd
import com.chipprbots.ethereum.network.PeerEventBusActor.SubscribeCmd
import com.chipprbots.ethereum.network.PeerEventBusActor.SubscriptionClassifier.*
import com.chipprbots.ethereum.network.PeerEventBusActor.UnsubscribeAllCmd
import com.chipprbots.ethereum.network.PeerEventBusActor.UnsubscribeCmd
import com.chipprbots.ethereum.network.p2p.messages.Capability
import com.chipprbots.ethereum.network.p2p.messages.WireProtocol.Ping
import com.chipprbots.ethereum.network.p2p.messages.WireProtocol.Pong
import com.chipprbots.ethereum.testing.Tags.*

class PeerEventBusActorSpec extends ScalaTestWithActorTestKit with AnyFlatSpecLike with Matchers with ScalaFutures:

  implicit private val classicSystem: org.apache.pekko.actor.ActorSystem = system.classicSystem

  "PeerEventBusActor" should "relay messages received to subscribers" taggedAs (
    UnitTest,
    NetworkTest
  ) in new TestSetup:

    val probe1: TestProbe = TestProbe()(using classicSystem)
    val probe2: TestProbe = TestProbe()(using classicSystem)
    val classifier1: MessageClassifier = MessageClassifier(Set(Ping.code), PeerSelector.WithId(PeerId("1")))
    val classifier2: MessageClassifier = MessageClassifier(Set(Ping.code), PeerSelector.AllPeers)
    peerEventBusActor ! SubscribeCmd(classifier1, probe1.ref.toTyped[PeerEvent])

    peerEventBusActor ! SubscribeCmd(classifier2, probe2.ref.toTyped[PeerEvent])

    val msgFromPeer: MessageFromPeer = MessageFromPeer(Ping(), PeerId("1"))
    peerEventBusActor ! PublishCmd(msgFromPeer)

    probe1.expectMsg(msgFromPeer)
    probe2.expectMsg(msgFromPeer)

    peerEventBusActor ! UnsubscribeCmd(classifier1, probe1.ref.toTyped[PeerEvent])

    val msgFromPeer2: MessageFromPeer = MessageFromPeer(Ping(), PeerId("99"))
    peerEventBusActor ! PublishCmd(msgFromPeer2)
    probe1.expectNoMessage()
    probe2.expectMsg(msgFromPeer2)

  it should "relay messages via streams" taggedAs (UnitTest, NetworkTest) in new TestSetup:
    val classifier1: MessageClassifier = MessageClassifier(Set(Ping.code), PeerSelector.WithId(PeerId("1")))
    val classifier2: MessageClassifier = MessageClassifier(Set(Ping.code), PeerSelector.AllPeers)

    // take(N) makes each stream demand-driven and self-terminating — no PoisonPill needed.
    // The stream completes as soon as N elements have been delivered, so Sink.seq's Future
    // resolves without any termination race against Pekko system-message priority.
    val stream1 = PeerEventBusActor.messageSource(peerEventBusActor, classifier1).take(1).runWith(Sink.seq)
    val stream2 = PeerEventBusActor.messageSource(peerEventBusActor, classifier2).take(2).runWith(Sink.seq)

    // Sync: syncProbe subscribed after both stream SubscribeCmds (same test thread → FIFO).
    // Once syncProbe confirms both publishes, both stream actors have their elements buffered.
    val syncProbe: TestProbe = TestProbe()(using classicSystem)
    peerEventBusActor ! SubscribeCmd(classifier2, syncProbe.ref.toTyped[PeerEvent])

    val msgFromPeer: MessageFromPeer = MessageFromPeer(Ping(), PeerId("1"))
    peerEventBusActor ! PublishCmd(msgFromPeer)
    syncProbe.expectMsg(msgFromPeer)

    val msgFromPeer2: MessageFromPeer = MessageFromPeer(Ping(), PeerId("99"))
    peerEventBusActor ! PublishCmd(msgFromPeer2)
    syncProbe.expectMsg(msgFromPeer2)

    // Elements are already buffered; Futures complete as soon as take(N) demand is satisfied.
    stream1.futureValue shouldEqual Seq(msgFromPeer)
    stream2.futureValue shouldEqual Seq(msgFromPeer, msgFromPeer2)

  it should "only relay matching message codes" taggedAs (UnitTest, NetworkTest) in new TestSetup:

    val probe1: TestProbe = TestProbe()
    val classifier1: MessageClassifier = MessageClassifier(Set(Ping.code), PeerSelector.WithId(PeerId("1")))
    peerEventBusActor ! SubscribeCmd(classifier1, probe1.ref.toTyped[PeerEvent])

    val msgFromPeer: MessageFromPeer = MessageFromPeer(Ping(), PeerId("1"))
    peerEventBusActor ! PublishCmd(msgFromPeer)

    probe1.expectMsg(msgFromPeer)

    val msgFromPeer2: MessageFromPeer = MessageFromPeer(Pong(), PeerId("1"))
    peerEventBusActor ! PublishCmd(msgFromPeer2)
    probe1.expectNoMessage()

  it should "relay peers disconnecting to its subscribers" taggedAs (UnitTest, NetworkTest) in new TestSetup:

    val probe1: TestProbe = TestProbe()
    val probe2: TestProbe = TestProbe()
    peerEventBusActor ! SubscribeCmd(
      PeerDisconnectedClassifier(PeerSelector.WithId(PeerId("1"))),
      probe1.ref.toTyped[PeerEvent]
    )
    peerEventBusActor ! SubscribeCmd(
      PeerDisconnectedClassifier(PeerSelector.WithId(PeerId("2"))),
      probe1.ref.toTyped[PeerEvent]
    )
    peerEventBusActor ! SubscribeCmd(
      PeerDisconnectedClassifier(PeerSelector.WithId(PeerId("2"))),
      probe2.ref.toTyped[PeerEvent]
    )

    val msgPeerDisconnected: PeerDisconnected = PeerDisconnected(PeerId("2"))
    peerEventBusActor ! PublishCmd(msgPeerDisconnected)

    probe1.expectMsg(msgPeerDisconnected)
    probe2.expectMsg(msgPeerDisconnected)

    peerEventBusActor ! UnsubscribeCmd(
      PeerDisconnectedClassifier(PeerSelector.WithId(PeerId("2"))),
      probe1.ref.toTyped[PeerEvent]
    )

    peerEventBusActor ! PublishCmd(msgPeerDisconnected)
    probe1.expectNoMessage()
    probe2.expectMsg(msgPeerDisconnected)

  it should "relay peers handshaked to its subscribers" taggedAs (UnitTest, NetworkTest) in new TestSetup:

    val probe1: TestProbe = TestProbe()
    val probe2: TestProbe = TestProbe()
    peerEventBusActor ! SubscribeCmd(PeerHandshaked, probe1.ref.toTyped[PeerEvent])
    peerEventBusActor ! SubscribeCmd(PeerHandshaked, probe2.ref.toTyped[PeerEvent])

    val peerHandshaked =
      new Peer(
        PeerId("peer1"),
        new InetSocketAddress("127.0.0.1", 0),
        TestProbe().ref.toTyped[PeerActor.Command],
        false,
        nodeId = Some(ByteString())
      )
    val msgPeerHandshaked: PeerHandshakeSuccessful[PeerInfo] = PeerHandshakeSuccessful(peerHandshaked, initialPeerInfo)
    peerEventBusActor ! PublishCmd(msgPeerHandshaked)

    probe1.expectMsg(msgPeerHandshaked)
    probe2.expectMsg(msgPeerHandshaked)

    peerEventBusActor ! UnsubscribeCmd(PeerHandshaked, probe1.ref.toTyped[PeerEvent])

    peerEventBusActor ! PublishCmd(msgPeerHandshaked)
    probe1.expectNoMessage()
    probe2.expectMsg(msgPeerHandshaked)

  it should "relay a single notification when subscribed twice to the same message code" taggedAs (
    UnitTest,
    NetworkTest
  ) in new TestSetup:

    val probe1: TestProbe = TestProbe()
    peerEventBusActor ! SubscribeCmd(
      MessageClassifier(Set(Ping.code, Ping.code), PeerSelector.WithId(PeerId("1"))),
      probe1.ref.toTyped[PeerEvent]
    )
    peerEventBusActor ! SubscribeCmd(
      MessageClassifier(Set(Ping.code, Pong.code), PeerSelector.WithId(PeerId("1"))),
      probe1.ref.toTyped[PeerEvent]
    )

    val msgFromPeer: MessageFromPeer = MessageFromPeer(Ping(), PeerId("1"))
    peerEventBusActor ! PublishCmd(msgFromPeer)

    probe1.expectMsg(msgFromPeer)
    probe1.expectNoMessage()

  it should "allow to handle subscriptions using AllPeers and WithId PeerSelector at the same time" taggedAs (
    UnitTest,
    NetworkTest
  ) in new TestSetup:

    val probe1: TestProbe = TestProbe()
    peerEventBusActor ! SubscribeCmd(
      MessageClassifier(Set(Ping.code), PeerSelector.WithId(PeerId("1"))),
      probe1.ref.toTyped[PeerEvent]
    )
    peerEventBusActor ! SubscribeCmd(
      MessageClassifier(Set(Ping.code), PeerSelector.AllPeers),
      probe1.ref.toTyped[PeerEvent]
    )

    val msgFromPeer: MessageFromPeer = MessageFromPeer(Ping(), PeerId("1"))
    peerEventBusActor ! PublishCmd(msgFromPeer)

    // Receive a single notification
    probe1.expectMsg(msgFromPeer)
    probe1.expectNoMessage()

    val msgFromPeer2: MessageFromPeer = MessageFromPeer(Ping(), PeerId("2"))
    peerEventBusActor ! PublishCmd(msgFromPeer2)

    // Receive based on AllPeers subscription
    probe1.expectMsg(msgFromPeer2)

    peerEventBusActor ! UnsubscribeCmd(
      MessageClassifier(Set(Ping.code), PeerSelector.AllPeers),
      probe1.ref.toTyped[PeerEvent]
    )
    peerEventBusActor ! PublishCmd(msgFromPeer)

    // Still received after unsubscribing from AllPeers
    probe1.expectMsg(msgFromPeer)

  it should "allow to subscribe to new messages" taggedAs (UnitTest, NetworkTest) in new TestSetup:

    val probe1: TestProbe = TestProbe()
    peerEventBusActor ! SubscribeCmd(
      MessageClassifier(Set(Ping.code), PeerSelector.WithId(PeerId("1"))),
      probe1.ref.toTyped[PeerEvent]
    )
    peerEventBusActor ! SubscribeCmd(
      MessageClassifier(Set(Ping.code, Pong.code), PeerSelector.WithId(PeerId("1"))),
      probe1.ref.toTyped[PeerEvent]
    )

    val msgFromPeer: MessageFromPeer = MessageFromPeer(Pong(), PeerId("1"))
    peerEventBusActor ! PublishCmd(msgFromPeer)

    probe1.expectMsg(msgFromPeer)

  it should "not change subscriptions when subscribing to empty set" taggedAs (UnitTest, NetworkTest) in new TestSetup:

    val probe1: TestProbe = TestProbe()
    peerEventBusActor ! SubscribeCmd(
      MessageClassifier(Set(Ping.code), PeerSelector.WithId(PeerId("1"))),
      probe1.ref.toTyped[PeerEvent]
    )
    peerEventBusActor ! SubscribeCmd(
      MessageClassifier(Set(), PeerSelector.WithId(PeerId("1"))),
      probe1.ref.toTyped[PeerEvent]
    )

    val msgFromPeer: MessageFromPeer = MessageFromPeer(Ping(), PeerId("1"))
    peerEventBusActor ! PublishCmd(msgFromPeer)

    probe1.expectMsg(msgFromPeer)

  it should "allow to unsubscribe from messages" taggedAs (UnitTest, NetworkTest) in new TestSetup:

    val probe1: TestProbe = TestProbe()
    peerEventBusActor ! SubscribeCmd(
      MessageClassifier(Set(Ping.code, Pong.code), PeerSelector.WithId(PeerId("1"))),
      probe1.ref.toTyped[PeerEvent]
    )

    val msgFromPeer1: MessageFromPeer = MessageFromPeer(Ping(), PeerId("1"))
    val msgFromPeer2: MessageFromPeer = MessageFromPeer(Pong(), PeerId("1"))
    peerEventBusActor ! PublishCmd(msgFromPeer1)
    peerEventBusActor ! PublishCmd(msgFromPeer2)

    probe1.expectMsg(msgFromPeer1)
    probe1.expectMsg(msgFromPeer2)

    peerEventBusActor ! UnsubscribeCmd(
      MessageClassifier(Set(Pong.code), PeerSelector.WithId(PeerId("1"))),
      probe1.ref.toTyped[PeerEvent]
    )

    peerEventBusActor ! PublishCmd(msgFromPeer1)
    peerEventBusActor ! PublishCmd(msgFromPeer2)

    probe1.expectMsg(msgFromPeer1)
    probe1.expectNoMessage()

    peerEventBusActor ! UnsubscribeAllCmd(probe1.ref.toTyped[PeerEvent])

    peerEventBusActor ! PublishCmd(msgFromPeer1)
    peerEventBusActor ! PublishCmd(msgFromPeer2)

    probe1.expectNoMessage()

  trait TestSetup:
    val peerEventBusActor: typed.ActorRef[PeerEventBusActor.Command] =
      testKit.spawn(PeerEventBusActor.behavior(), s"pea-${java.util.UUID.randomUUID()}")

    val peerStatus: RemoteStatus = RemoteStatus(
      capability = Capability.ETH63,
      networkId = 1,
      chainWeight = ChainWeight.totalDifficultyOnly(10000),
      bestHash = Fixtures.Blocks.Block3125369.header.hash.value,
      genesisHash = Fixtures.Blocks.Genesis.header.hash.value
    )
    val initialPeerInfo: PeerInfo = PeerInfo(
      remoteStatus = peerStatus,
      chainWeight = peerStatus.chainWeight,
      forkAccepted = false,
      maxBlockNumber = Fixtures.Blocks.Block3125369.header.number.value,
      bestBlockHash = peerStatus.bestHash
    )
