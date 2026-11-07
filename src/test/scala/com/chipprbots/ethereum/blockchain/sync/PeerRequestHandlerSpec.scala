package com.chipprbots.ethereum.blockchain.sync

import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

import org.apache.pekko.actor.testkit.typed.scaladsl.ManualTime
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed.ActorRef as TypedActorRef
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.testkit.TestProbe
import org.apache.pekko.util.ByteString

import scala.concurrent.duration.*

import org.scalatest.concurrent.Eventually
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.Millis
import org.scalatest.time.Seconds
import org.scalatest.time.Span

import com.chipprbots.ethereum.blockchain.sync.PeerRequestHandler.Result
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.SendMessageCmd
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.PeerActor
import com.chipprbots.ethereum.network.PeerEventBusActor
import com.chipprbots.ethereum.network.PeerEventBusActor.*
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerEvent.*
import com.chipprbots.ethereum.network.PeerId
import com.chipprbots.ethereum.network.p2p.MessageSerializable
import com.chipprbots.ethereum.network.p2p.messages.SNAP
import com.chipprbots.ethereum.network.p2p.messages.SNAP.ByteCodes
import com.chipprbots.ethereum.network.p2p.messages.SNAP.GetByteCodes
import com.chipprbots.ethereum.network.p2p.messages.SNAP.GetTrieNodes
import com.chipprbots.ethereum.network.p2p.messages.SNAP.TrieNodes
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
    val peerActorProbe: TestProbe = TestProbe()(using testKit.system.toClassic)
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

    def spawnPRH(
        npmProbe: TestProbe,
        timeout: FiniteDuration = 5.seconds
    ): TypedActorRef[com.chipprbots.ethereum.blockchain.sync.PeerRequestHandler.Command] =
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
    val npmProbe: TestProbe = TestProbe()(using testKit.system.toClassic)
    spawnPRH(npmProbe)

    val sent: SendMessageCmd = npmProbe.expectMsgType[NetworkPeerManagerActor.SendMessageCmd]
    sent.peerId shouldEqual peerId
    npmProbe.expectNoMessage(100.millis)

  it should "send both SubscribeCmd messages to the peer event bus before sending the request" taggedAs (
    UnitTest,
    NetworkTest
  ) in new Fixtures:
    // A reply is published to the bus by PeerActor, a different sender than the handler, so the handler's
    // subscriptions are only guaranteed to be live first if they are SENT first. One recorder stands in for both the
    // network peer manager and the bus, so both sends come from one sender to one receiver and their recorded order is
    // exactly the order the handler issued them in.
    val orderLog = new CopyOnWriteArrayList[String]()
    val recorder: TypedActorRef[Any] = testKit.spawn(Behaviors.receiveMessage[Any] { msg =>
      orderLog.add(msg.getClass.getSimpleName)
      Behaviors.same
    })
    val npmRef: TypedActorRef[NetworkPeerManagerActor.Command] = recorder
    val pebRef: TypedActorRef[PeerEventBusActor.Command] = recorder

    testKit.spawn(
      PeerRequestHandler.behavior[Ping, Pong](
        peer = peer,
        responseTimeout = 5.seconds,
        networkPeerManager = npmRef,
        peerEventBus = pebRef,
        requestMsg = Ping(),
        responseMsgCode = Pong.code,
        replyTo = replyTo.ref,
        requestId = 0
      ),
      s"prh-${java.util.UUID.randomUUID()}"
    )

    eventually(timeout(Span(5, Seconds)), interval(Span(20, Millis))) {
      orderLog.size shouldBe 3
    }

    import scala.jdk.CollectionConverters.*
    val order = orderLog.asScala.toList
    val subscribeIndices: List[Int] = order.zipWithIndex.collect { case ("SubscribeCmd", i) => i }
    val sendIndex: Int = order.indexOf("SendMessageCmd")
    subscribeIndices should have size 2
    all(subscribeIndices) should be < sendIndex

  it should "reply ResponseReceived when a matching response arrives via PEB" taggedAs (
    UnitTest,
    NetworkTest
  ) in new Fixtures:
    val npmProbe: TestProbe = TestProbe()(using testKit.system.toClassic)
    spawnPRH(npmProbe)
    npmProbe.expectMsgType[NetworkPeerManagerActor.SendMessageCmd]

    // Republish until the subscription is live, rather than publishing once and hoping.
    //
    // The handler subscribes before it sends, but this publish comes from the test, a different sender than the
    // handler, so observing SendMessageCmd does not prove the bus has PROCESSED the subscriptions yet. A single
    // publish landing before that is dropped; retry delivery instead of hoping. The assertion is unchanged.
    eventually(timeout(Span(5, Seconds)), interval(Span(50, Millis))) {
      peerEventBus ! PublishCmd(MessageFromPeer(Pong(), peerId))
      replyTo.expectMessageType[PeerRequestHandler.ResponseReceived[Pong]](100.millis)
    }

  it should "reply RequestFailed when the response timer fires" taggedAs (UnitTest, NetworkTest) in new Fixtures:
    val npmProbe: TestProbe = TestProbe()(using testKit.system.toClassic)
    spawnPRH(npmProbe, timeout = 2.seconds)
    npmProbe.expectMsgType[NetworkPeerManagerActor.SendMessageCmd]

    manualTime.timePasses(3.seconds)

    replyTo.expectMessage(PeerRequestHandler.RequestFailed(0, peer, "request timeout"))

  it should "reply RequestFailed when the peer disconnects before the response arrives" taggedAs (
    UnitTest,
    NetworkTest
  ) in new Fixtures:
    val npmProbe: TestProbe = TestProbe()(using testKit.system.toClassic)
    spawnPRH(npmProbe)
    npmProbe.expectMsgType[NetworkPeerManagerActor.SendMessageCmd]

    peerEventBus ! PublishCmd(PeerDisconnected(peerId))

    replyTo.expectMessage(PeerRequestHandler.RequestFailed(0, peer, "connection closed"))

  it should "ignore a response from a different peer" taggedAs (UnitTest, NetworkTest) in new Fixtures:
    val npmProbe: TestProbe = TestProbe()(using testKit.system.toClassic)
    spawnPRH(npmProbe)
    npmProbe.expectMsgType[NetworkPeerManagerActor.SendMessageCmd]

    // PEB subscriber is scoped to PeerSelector.WithId(peerId) — wrong peer not routed to PRH
    peerEventBus ! PublishCmd(MessageFromPeer(Pong(), otherPeerId))
    replyTo.expectNoMessage(200.millis)

    // Correct peer responds
    peerEventBus ! PublishCmd(MessageFromPeer(Pong(), peerId))
    replyTo.expectMessageType[PeerRequestHandler.ResponseReceived[Pong]]

  // spec 022 T053: import's fetcher and the background audit's each have their own PeersClient, and can each have a SNAP
  // request in flight to one peer. A reply to one, taken by the other, reads there as a wrong hash, and blacklists an
  // honest peer in import's blacklist. Every peer echoes the id (snap/1).
  it should "take a SNAP reply only for its own request id, when two requests are in flight to one peer" taggedAs (
    UnitTest,
    NetworkTest
  ) in new Fixtures:
    val npmProbe: TestProbe = TestProbe()(using testKit.system.toClassic)
    given (GetTrieNodes => MessageSerializable) = new GetTrieNodes.GetTrieNodesEnc(_)
    def request(
        id: Int,
        to: TypedActorRef[PeerRequestHandler.Result]
    ): TypedActorRef[com.chipprbots.ethereum.blockchain.sync.PeerRequestHandler.Command] =
      testKit.spawn(
        PeerRequestHandler.behavior[GetTrieNodes, TrieNodes](
          peer = peer,
          responseTimeout = 5.seconds,
          networkPeerManager = npmProbe.ref,
          peerEventBus = peerEventBus,
          requestMsg = GetTrieNodes(id, ByteString(Array.fill[Byte](32)(1)), Seq(Seq(ByteString(0))), 1024),
          responseMsgCode = SNAP.Codes.TrieNodesCode,
          replyTo = to,
          requestId = id
        ),
        s"prh-${java.util.UUID.randomUUID()}"
      )
    val other: org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe[Result] =
      testKit.createTestProbe[PeerRequestHandler.Result]()
    request(11, replyTo.ref)
    request(22, other.ref)
    npmProbe.expectMsgType[NetworkPeerManagerActor.SendMessageCmd]
    npmProbe.expectMsgType[NetworkPeerManagerActor.SendMessageCmd]

    // Each handler enqueued its subscriptions before its send, and both sends are seen above, so this publish is after
    // them in the bus's mailbox.
    peerEventBus ! PublishCmd(MessageFromPeer(TrieNodes(22, Seq(ByteString(1))), peerId))
    other.expectMessageType[PeerRequestHandler.ResponseReceived[TrieNodes]].response.requestId shouldBe BigInt(22)
    replyTo.expectNoMessage(200.millis)

    peerEventBus ! PublishCmd(MessageFromPeer(TrieNodes(11, Seq(ByteString(2))), peerId))
    replyTo.expectMessageType[PeerRequestHandler.ResponseReceived[TrieNodes]].response.requestId shouldBe BigInt(11)

  it should "take a ByteCodes reply only for its own request id, as it does a TrieNodes reply" taggedAs (
    UnitTest,
    NetworkTest
  ) in new Fixtures:
    val npmProbe: TestProbe = TestProbe()(using testKit.system.toClassic)
    given (GetByteCodes => MessageSerializable) = new GetByteCodes.GetByteCodesEnc(_)
    def request(
        id: Int,
        to: TypedActorRef[PeerRequestHandler.Result]
    ): TypedActorRef[com.chipprbots.ethereum.blockchain.sync.PeerRequestHandler.Command] =
      testKit.spawn(
        PeerRequestHandler.behavior[GetByteCodes, ByteCodes](
          peer = peer,
          responseTimeout = 5.seconds,
          networkPeerManager = npmProbe.ref,
          peerEventBus = peerEventBus,
          requestMsg = GetByteCodes(id, Seq(ByteString(Array.fill[Byte](32)(1))), 1024),
          responseMsgCode = SNAP.Codes.ByteCodesCode,
          replyTo = to,
          requestId = id
        ),
        s"prh-${java.util.UUID.randomUUID()}"
      )
    val other: org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe[Result] =
      testKit.createTestProbe[PeerRequestHandler.Result]()
    request(11, replyTo.ref)
    request(22, other.ref)
    npmProbe.expectMsgType[NetworkPeerManagerActor.SendMessageCmd]
    npmProbe.expectMsgType[NetworkPeerManagerActor.SendMessageCmd]

    peerEventBus ! PublishCmd(MessageFromPeer(ByteCodes(22, Seq(ByteString(1))), peerId))
    other.expectMessageType[PeerRequestHandler.ResponseReceived[ByteCodes]].response.requestId shouldBe BigInt(22)
    replyTo.expectNoMessage(200.millis)

    peerEventBus ! PublishCmd(MessageFromPeer(ByteCodes(11, Seq(ByteString(2))), peerId))
    replyTo.expectMessageType[PeerRequestHandler.ResponseReceived[ByteCodes]].response.requestId shouldBe BigInt(11)

  it should "time out when the only reply has no matching id" taggedAs (UnitTest, NetworkTest) in new Fixtures:
    val npmProbe: TestProbe = TestProbe()(using testKit.system.toClassic)
    given (GetTrieNodes => MessageSerializable) = new GetTrieNodes.GetTrieNodesEnc(_)
    testKit.spawn(
      PeerRequestHandler.behavior[GetTrieNodes, TrieNodes](
        peer = peer,
        responseTimeout = 2.seconds,
        networkPeerManager = npmProbe.ref,
        peerEventBus = peerEventBus,
        requestMsg = GetTrieNodes(11, ByteString(Array.fill[Byte](32)(1)), Seq(Seq(ByteString(0))), 1024),
        responseMsgCode = SNAP.Codes.TrieNodesCode,
        replyTo = replyTo.ref,
        requestId = 11
      ),
      s"prh-${java.util.UUID.randomUUID()}"
    )
    npmProbe.expectMsgType[NetworkPeerManagerActor.SendMessageCmd]

    peerEventBus ! PublishCmd(MessageFromPeer(TrieNodes(99, Seq(ByteString(1))), peerId))
    replyTo.expectNoMessage(200.millis)

    manualTime.timePasses(3.seconds)
    replyTo.expectMessage(PeerRequestHandler.RequestFailed(11, peer, "request timeout"))

  it should "ignore a disconnect event for a different peer" taggedAs (UnitTest, NetworkTest) in new Fixtures:
    val npmProbe: TestProbe = TestProbe()(using testKit.system.toClassic)
    spawnPRH(npmProbe)
    npmProbe.expectMsgType[NetworkPeerManagerActor.SendMessageCmd]

    peerEventBus ! PublishCmd(PeerDisconnected(otherPeerId))
    replyTo.expectNoMessage(200.millis)
