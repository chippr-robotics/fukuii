package com.chipprbots.ethereum.network

import java.net.InetSocketAddress

import org.apache.pekko.actor.ActorRef
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.PoisonPill
import org.apache.pekko.stream.WatchedActorTerminatedException
import org.apache.pekko.stream.scaladsl.Flow
import org.apache.pekko.stream.scaladsl.Keep
import org.apache.pekko.stream.scaladsl.Sink
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.testkit.TestActor
import org.apache.pekko.testkit.TestProbe
import org.apache.pekko.util.ByteString

import scala.concurrent.Await
import scala.concurrent.duration._

import org.scalatest.concurrent.ScalaFutures
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.NormalPatience
import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.network.EtcPeerManagerActor.PeerInfo
import com.chipprbots.ethereum.network.EtcPeerManagerActor.RemoteStatus
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerEvent.MessageFromPeer
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerEvent.PeerDisconnected
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerEvent.PeerHandshakeSuccessful
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerSelector
import com.chipprbots.ethereum.network.PeerEventBusActor.SubscriptionClassifier._
import com.chipprbots.ethereum.network.p2p.messages.Capability
import com.chipprbots.ethereum.network.p2p.messages.WireProtocol.Ping
import com.chipprbots.ethereum.network.p2p.messages.WireProtocol.Pong
import scala.concurrent.Future
import scala.concurrent.Future
import scala.concurrent.Future

class PeerEventBusActorSpec extends AnyFlatSpec with Matchers with ScalaFutures with NormalPatience {

  "PeerEventBusActor" should "relay messages received to subscribers" in new TestSetup {

    val probe1: TestProbe = TestProbe()(system)
    val probe2: TestProbe = TestProbe()(system)
    val classifier1: MessageClassifier = MessageClassifier(Set(Ping.code), PeerSelector.WithId(PeerId("1")))
    val classifier2: MessageClassifier = MessageClassifier(Set(Ping.code), PeerSelector.AllPeers)
    peerEventBusActor.tell(PeerEventBusActor.Subscribe(classifier1), probe1.ref)

    peerEventBusActor.tell(PeerEventBusActor.Subscribe(classifier2), probe2.ref)

    val msgFromPeer: MessageFromPeer = MessageFromPeer(Ping(), PeerId("1"))
    peerEventBusActor ! PeerEventBusActor.Publish(msgFromPeer)

    probe1.expectMsg(msgFromPeer)
    probe2.expectMsg(msgFromPeer)

    peerEventBusActor.tell(PeerEventBusActor.Unsubscribe(classifier1), probe1.ref)

    val msgFromPeer2: MessageFromPeer = MessageFromPeer(Ping(), PeerId("99"))
    peerEventBusActor ! PeerEventBusActor.Publish(msgFromPeer2)
    probe1.expectNoMessage()
    probe2.expectMsg(msgFromPeer2)

  }

  it should "relay messages via streams" in new TestSetup {
    val classifier1: MessageClassifier = MessageClassifier(Set(Ping.code), PeerSelector.WithId(PeerId("1")))
    val classifier2: MessageClassifier = MessageClassifier(Set(Ping.code), PeerSelector.AllPeers)

    val peerEventBusProbe: TestProbe = TestProbe()(system)
    peerEventBusProbe.setAutoPilot { (sender: ActorRef, msg: Any) =>
      peerEventBusActor.tell(msg, sender)
      TestActor.KeepRunning
    }

    val seqOnTermination: Sink[MessageFromPeer, Future[Seq[MessageFromPeer]]] = Flow[MessageFromPeer]
      .recoverWithRetries(1, { case _: WatchedActorTerminatedException => Source.empty })
      .toMat(Sink.seq)(Keep.right)

    val stream1: Future[Seq[MessageFromPeer]] =
      PeerEventBusActor.messageSource(peerEventBusProbe.ref, classifier1).runWith(seqOnTermination)
    val stream2: Future[Seq[MessageFromPeer]] =
      PeerEventBusActor.messageSource(peerEventBusProbe.ref, classifier2).runWith(seqOnTermination)

    // wait for subscriptions to be done
    peerEventBusProbe.expectMsgType[PeerEventBusActor.Subscribe]
    peerEventBusProbe.expectMsgType[PeerEventBusActor.Subscribe]

    val syncProbe: TestProbe = TestProbe()(system)
    peerEventBusActor.tell(PeerEventBusActor.Subscribe(classifier2), syncProbe.ref)

    val msgFromPeer: MessageFromPeer = MessageFromPeer(Ping(), PeerId("1"))
    peerEventBusActor ! PeerEventBusActor.Publish(msgFromPeer)

    val msgFromPeer2: MessageFromPeer = MessageFromPeer(Ping(), PeerId("99"))
    peerEventBusActor ! PeerEventBusActor.Publish(msgFromPeer2)

    // wait for publications to be done
    syncProbe.expectMsg(msgFromPeer)
    syncProbe.expectMsg(msgFromPeer2)

    peerEventBusProbe.ref ! PoisonPill

    // make the stream checks a bit more robust to fork/timing differences by waiting
    // deterministically for a short timeout instead of relying on the default whenReady
    val res1: Seq[MessageFromPeer] = Await.result(stream1, 5.seconds)
    res1 shouldEqual Seq(msgFromPeer)

    val res2: Seq[MessageFromPeer] = Await.result(stream2, 5.seconds)
    res2 shouldEqual Seq(msgFromPeer, msgFromPeer2)
  }

  it should "only relay matching message codes" in new TestSetup {

    val probe1: TestProbe = TestProbe()
    val classifier1: MessageClassifier = MessageClassifier(Set(Ping.code), PeerSelector.WithId(PeerId("1")))
    peerEventBusActor.tell(PeerEventBusActor.Subscribe(classifier1), probe1.ref)

    val msgFromPeer: MessageFromPeer = MessageFromPeer(Ping(), PeerId("1"))
    peerEventBusActor ! PeerEventBusActor.Publish(msgFromPeer)

    probe1.expectMsg(msgFromPeer)

    val msgFromPeer2: MessageFromPeer = MessageFromPeer(Pong(), PeerId("1"))
    peerEventBusActor ! PeerEventBusActor.Publish(msgFromPeer2)
    probe1.expectNoMessage()
  }

  it should "relay peers disconnecting to its subscribers" in new TestSetup {

    val probe1: TestProbe = TestProbe()
    val probe2: TestProbe = TestProbe()
    peerEventBusActor.tell(
      PeerEventBusActor.Subscribe(PeerDisconnectedClassifier(PeerSelector.WithId(PeerId("1")))),
      probe1.ref
    )
    peerEventBusActor.tell(
      PeerEventBusActor.Subscribe(PeerDisconnectedClassifier(PeerSelector.WithId(PeerId("2")))),
      probe1.ref
    )
    peerEventBusActor.tell(
      PeerEventBusActor.Subscribe(PeerDisconnectedClassifier(PeerSelector.WithId(PeerId("2")))),
      probe2.ref
    )

    val msgPeerDisconnected: PeerDisconnected = PeerDisconnected(PeerId("2"))
    peerEventBusActor ! PeerEventBusActor.Publish(msgPeerDisconnected)

    probe1.expectMsg(msgPeerDisconnected)
    probe2.expectMsg(msgPeerDisconnected)

    peerEventBusActor.tell(
      PeerEventBusActor.Unsubscribe(PeerDisconnectedClassifier(PeerSelector.WithId(PeerId("2")))),
      probe1.ref
    )

    peerEventBusActor ! PeerEventBusActor.Publish(msgPeerDisconnected)
    probe1.expectNoMessage()
    probe2.expectMsg(msgPeerDisconnected)
  }

  it should "relay peers handshaked to its subscribers" in new TestSetup {

    val probe1: TestProbe = TestProbe()
    val probe2: TestProbe = TestProbe()
    peerEventBusActor.tell(PeerEventBusActor.Subscribe(PeerHandshaked), probe1.ref)
    peerEventBusActor.tell(PeerEventBusActor.Subscribe(PeerHandshaked), probe2.ref)

    val peerHandshaked =
      new Peer(
        PeerId("peer1"),
        new InetSocketAddress("127.0.0.1", 0),
        TestProbe().ref,
        false,
        nodeId = Some(ByteString())
      )
    val msgPeerHandshaked: PeerHandshakeSuccessful[PeerInfo] = PeerHandshakeSuccessful(peerHandshaked, initialPeerInfo)
    peerEventBusActor ! PeerEventBusActor.Publish(msgPeerHandshaked)

    probe1.expectMsg(msgPeerHandshaked)
    probe2.expectMsg(msgPeerHandshaked)

    peerEventBusActor.tell(PeerEventBusActor.Unsubscribe(PeerHandshaked), probe1.ref)

    peerEventBusActor ! PeerEventBusActor.Publish(msgPeerHandshaked)
    probe1.expectNoMessage()
    probe2.expectMsg(msgPeerHandshaked)
  }

  it should "relay a single notification when subscribed twice to the same message code" in new TestSetup {

    val probe1: TestProbe = TestProbe()
    peerEventBusActor.tell(
      PeerEventBusActor.Subscribe(MessageClassifier(Set(Ping.code, Ping.code), PeerSelector.WithId(PeerId("1")))),
      probe1.ref
    )
    peerEventBusActor.tell(
      PeerEventBusActor.Subscribe(MessageClassifier(Set(Ping.code, Pong.code), PeerSelector.WithId(PeerId("1")))),
      probe1.ref
    )

    val msgFromPeer: MessageFromPeer = MessageFromPeer(Ping(), PeerId("1"))
    peerEventBusActor ! PeerEventBusActor.Publish(msgFromPeer)

    probe1.expectMsg(msgFromPeer)
    probe1.expectNoMessage()
  }

  it should "allow to handle subscriptions using AllPeers and WithId PeerSelector at the same time" in new TestSetup {

    val probe1: TestProbe = TestProbe()
    peerEventBusActor.tell(
      PeerEventBusActor.Subscribe(MessageClassifier(Set(Ping.code), PeerSelector.WithId(PeerId("1")))),
      probe1.ref
    )
    peerEventBusActor.tell(
      PeerEventBusActor.Subscribe(MessageClassifier(Set(Ping.code), PeerSelector.AllPeers)),
      probe1.ref
    )

    val msgFromPeer: MessageFromPeer = MessageFromPeer(Ping(), PeerId("1"))
    peerEventBusActor ! PeerEventBusActor.Publish(msgFromPeer)

    // Receive a single notification
    probe1.expectMsg(msgFromPeer)
    probe1.expectNoMessage()

    val msgFromPeer2: MessageFromPeer = MessageFromPeer(Ping(), PeerId("2"))
    peerEventBusActor ! PeerEventBusActor.Publish(msgFromPeer2)

    // Receive based on AllPeers subscription
    probe1.expectMsg(msgFromPeer2)

    peerEventBusActor.tell(
      PeerEventBusActor.Unsubscribe(MessageClassifier(Set(Ping.code), PeerSelector.AllPeers)),
      probe1.ref
    )
    peerEventBusActor ! PeerEventBusActor.Publish(msgFromPeer)

    // Still received after unsubscribing from AllPeers
    probe1.expectMsg(msgFromPeer)
  }

  it should "allow to subscribe to new messages" in new TestSetup {

    val probe1: TestProbe = TestProbe()
    peerEventBusActor.tell(
      PeerEventBusActor.Subscribe(MessageClassifier(Set(Ping.code), PeerSelector.WithId(PeerId("1")))),
      probe1.ref
    )
    peerEventBusActor.tell(
      PeerEventBusActor.Subscribe(MessageClassifier(Set(Ping.code, Pong.code), PeerSelector.WithId(PeerId("1")))),
      probe1.ref
    )

    val msgFromPeer: MessageFromPeer = MessageFromPeer(Pong(), PeerId("1"))
    peerEventBusActor ! PeerEventBusActor.Publish(msgFromPeer)

    probe1.expectMsg(msgFromPeer)
  }

  it should "not change subscriptions when subscribing to empty set" in new TestSetup {

    val probe1: TestProbe = TestProbe()
    peerEventBusActor.tell(
      PeerEventBusActor.Subscribe(MessageClassifier(Set(Ping.code), PeerSelector.WithId(PeerId("1")))),
      probe1.ref
    )
    peerEventBusActor.tell(
      PeerEventBusActor.Subscribe(MessageClassifier(Set(), PeerSelector.WithId(PeerId("1")))),
      probe1.ref
    )

    val msgFromPeer: MessageFromPeer = MessageFromPeer(Ping(), PeerId("1"))
    peerEventBusActor ! PeerEventBusActor.Publish(msgFromPeer)

    probe1.expectMsg(msgFromPeer)
  }

  it should "allow to unsubscribe from messages" in new TestSetup {

    val probe1: TestProbe = TestProbe()
    peerEventBusActor.tell(
      PeerEventBusActor.Subscribe(MessageClassifier(Set(Ping.code, Pong.code), PeerSelector.WithId(PeerId("1")))),
      probe1.ref
    )

    val msgFromPeer1: MessageFromPeer = MessageFromPeer(Ping(), PeerId("1"))
    val msgFromPeer2: MessageFromPeer = MessageFromPeer(Pong(), PeerId("1"))
    peerEventBusActor ! PeerEventBusActor.Publish(msgFromPeer1)
    peerEventBusActor ! PeerEventBusActor.Publish(msgFromPeer2)

    probe1.expectMsg(msgFromPeer1)
    probe1.expectMsg(msgFromPeer2)

    peerEventBusActor.tell(
      PeerEventBusActor.Unsubscribe(MessageClassifier(Set(Pong.code), PeerSelector.WithId(PeerId("1")))),
      probe1.ref
    )

    peerEventBusActor ! PeerEventBusActor.Publish(msgFromPeer1)
    peerEventBusActor ! PeerEventBusActor.Publish(msgFromPeer2)

    probe1.expectMsg(msgFromPeer1)
    probe1.expectNoMessage()

    peerEventBusActor.tell(PeerEventBusActor.Unsubscribe(), probe1.ref)

    peerEventBusActor ! PeerEventBusActor.Publish(msgFromPeer1)
    peerEventBusActor ! PeerEventBusActor.Publish(msgFromPeer2)

    probe1.expectNoMessage()
  }

  trait TestSetup {
    implicit val system: ActorSystem = ActorSystem("test-system")

    val peerEventBusActor: ActorRef = system.actorOf(PeerEventBusActor.props)

    val peerStatus: RemoteStatus = RemoteStatus(
      capability = Capability.ETH63,
      networkId = 1,
      chainWeight = ChainWeight.totalDifficultyOnly(10000),
      bestHash = Fixtures.Blocks.Block3125369.header.hash,
      genesisHash = Fixtures.Blocks.Genesis.header.hash
    )
    val initialPeerInfo: PeerInfo = PeerInfo(
      remoteStatus = peerStatus,
      chainWeight = peerStatus.chainWeight,
      forkAccepted = false,
      maxBlockNumber = Fixtures.Blocks.Block3125369.header.number,
      bestBlockHash = peerStatus.bestHash
    )

  }

}
