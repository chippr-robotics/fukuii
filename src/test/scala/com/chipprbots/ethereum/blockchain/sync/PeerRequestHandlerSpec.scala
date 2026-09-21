package com.chipprbots.ethereum.blockchain.sync

import java.net.InetSocketAddress

import org.apache.pekko.actor.testkit.typed.scaladsl.ManualTime
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed.ActorRef as TypedActorRef
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.testkit.TestProbe

import org.scalatest.concurrent.Eventually
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.Millis
import org.scalatest.time.Seconds
import org.scalatest.time.Span

import scala.concurrent.duration.*

import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.PeerActor
import com.chipprbots.ethereum.network.PeerId
import com.chipprbots.ethereum.network.PeerEventBusActor
import com.chipprbots.ethereum.network.PeerEventBusActor.*
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerEvent.*
import com.chipprbots.ethereum.network.p2p.MessageSerializable
import com.chipprbots.ethereum.network.p2p.messages.WireProtocol.Ping
import com.chipprbots.ethereum.network.p2p.messages.WireProtocol.Ping.PingEnc
import com.chipprbots.ethereum.network.p2p.messages.WireProtocol.Pong
import com.chipprbots.ethereum.testing.Tags.*

class PeerRequestHandlerSpec
    extends ScalaTestWithActorTestKit(ManualTime.config)
    with AnyFlatSpecLike
    with Matchers
    with Eventually:

  val manualTime: ManualTime = ManualTime()

  trait Fixtures:
    val peerId: PeerId = PeerId("test-peer-1")
    val otherPeerId: PeerId = PeerId("other-peer")
    val peerActorProbe: TestProbe = TestProbe()(testKit.system.toClassic)
    val peer: Peer = Peer(
      id = peerId,
      remoteAddress = new InetSocketAddress("127.0.0.1", 9000),
      ref = peerActorProbe.ref.toTyped[PeerActor.Command],
      incomingConnection = false
    )
    val peerEventBus: TypedActorRef[PeerEventBusActor.Command] =
      testKit.spawn(PeerEventBusActor.behavior(), s"peb-${java.util.UUID.randomUUID()}")
    val replyTo: org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe[PeerRequestHandler.Result] =
      testKit.createTestProbe[PeerRequestHandler.Result]()

    given (Ping => MessageSerializable) = PingEnc(_)

    def spawnPRH(npmProbe: TestProbe, timeout: FiniteDuration = 5.seconds) =
      testKit.spawn(
        PeerRequestHandler.behavior[Ping, Pong](
          peer = peer,
          responseTimeout = timeout,
          networkPeerManager = npmProbe.ref,
          peerEventBus = peerEventBus,
          requestMsg = Ping(),
          responseMsgCode = Pong.code,
          replyTo = replyTo.ref,
          requestId = 0
        ),
        s"prh-${java.util.UUID.randomUUID()}"
      )

  "PeerRequestHandler" should "send SendMessageCmd (not SendMessage) to networkPeerManager on startup" taggedAs (
    UnitTest,
    NetworkTest
  ) in new Fixtures:
    val npmProbe = TestProbe()(testKit.system.toClassic)
    spawnPRH(npmProbe)

    val sent = npmProbe.expectMsgType[NetworkPeerManagerActor.SendMessageCmd]
    sent.peerId shouldEqual peerId
    npmProbe.expectNoMessage(100.millis)

  it should "reply ResponseReceived when a matching response arrives via PEB" taggedAs (
    UnitTest,
    NetworkTest
  ) in new Fixtures:
    val npmProbe = TestProbe()(testKit.system.toClassic)
    spawnPRH(npmProbe)
    npmProbe.expectMsgType[NetworkPeerManagerActor.SendMessageCmd]

    // Republish until the subscription is live, rather than publishing once and hoping.
    //
    // PeerRequestHandler sends SendMessageCmd to networkPeerManager BEFORE it sends its two
    // SubscribeCmd messages to the peer event bus (PeerRequestHandler.scala:74-82). Those are
    // DIFFERENT destination actors, so observing SendMessageCmd above establishes only that the
    // subscribes were enqueued — not that the bus has processed them. Pekko guarantees ordering per
    // sender/receiver pair, and the subscribes come from the handler while this publish comes from
    // the test, so there is no ordering between them at all.
    //
    // A single publish landing in that window is DROPPED, and no timeout can recover a message that
    // was never delivered — which is why this failed on a cold JVM (slow subscription processing)
    // and passed on a warm one. Raising the timeout would have hidden the race, not fixed it.
    //
    // The assertion is unchanged; only delivery is retried.
    eventually(timeout(Span(5, Seconds)), interval(Span(50, Millis))) {
      peerEventBus ! PublishCmd(MessageFromPeer(Pong(), peerId))
      replyTo.expectMessageType[PeerRequestHandler.ResponseReceived[Pong]](100.millis)
    }

  it should "reply RequestFailed when the response timer fires" taggedAs (UnitTest, NetworkTest) in new Fixtures:
    val npmProbe = TestProbe()(testKit.system.toClassic)
    spawnPRH(npmProbe, timeout = 2.seconds)
    npmProbe.expectMsgType[NetworkPeerManagerActor.SendMessageCmd]

    manualTime.timePasses(3.seconds)

    replyTo.expectMessage(PeerRequestHandler.RequestFailed(0, peer, "request timeout"))

  it should "reply RequestFailed when the peer disconnects before the response arrives" taggedAs (
    UnitTest,
    NetworkTest
  ) in new Fixtures:
    val npmProbe = TestProbe()(testKit.system.toClassic)
    spawnPRH(npmProbe)
    npmProbe.expectMsgType[NetworkPeerManagerActor.SendMessageCmd]

    peerEventBus ! PublishCmd(PeerDisconnected(peerId))

    replyTo.expectMessage(PeerRequestHandler.RequestFailed(0, peer, "connection closed"))

  it should "ignore a response from a different peer" taggedAs (UnitTest, NetworkTest) in new Fixtures:
    val npmProbe = TestProbe()(testKit.system.toClassic)
    spawnPRH(npmProbe)
    npmProbe.expectMsgType[NetworkPeerManagerActor.SendMessageCmd]

    // PEB subscriber is scoped to PeerSelector.WithId(peerId) — wrong peer not routed to PRH
    peerEventBus ! PublishCmd(MessageFromPeer(Pong(), otherPeerId))
    replyTo.expectNoMessage(200.millis)

    // Correct peer responds
    peerEventBus ! PublishCmd(MessageFromPeer(Pong(), peerId))
    replyTo.expectMessageType[PeerRequestHandler.ResponseReceived[Pong]]

  it should "ignore a disconnect event for a different peer" taggedAs (UnitTest, NetworkTest) in new Fixtures:
    val npmProbe = TestProbe()(testKit.system.toClassic)
    spawnPRH(npmProbe)
    npmProbe.expectMsgType[NetworkPeerManagerActor.SendMessageCmd]

    peerEventBus ! PublishCmd(PeerDisconnected(otherPeerId))
    replyTo.expectNoMessage(200.millis)
