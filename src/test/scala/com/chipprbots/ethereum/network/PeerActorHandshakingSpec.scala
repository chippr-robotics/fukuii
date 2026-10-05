// §8a-retro batch 5: DEFERRED — TestActorRef requires Classic-only API;
// migrate when PeerActor is Typed (Wave 3 network sprint)
package com.chipprbots.ethereum.network

import java.net.InetSocketAddress
import java.net.URI

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.testkit.ExplicitlyTriggeredScheduler
import org.apache.pekko.testkit.TestActorRef
import org.apache.pekko.testkit.TestProbe
import org.apache.pekko.util.ByteString

import scala.concurrent.duration.*

import com.typesafe.config.ConfigFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.domain.Difficulty
import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.Mocks.MockHandshakerAlwaysFails
import com.chipprbots.ethereum.Mocks.MockHandshakerAlwaysSucceeds
import com.chipprbots.ethereum.Timeouts
import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.forkid.ForkId
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.PeerInfo
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.RemoteStatus
import com.chipprbots.ethereum.network.PeerActor.ConnectTo
import com.chipprbots.ethereum.network.PeerActor.GetStatus
import com.chipprbots.ethereum.network.PeerActor.Status.Handshaked
import com.chipprbots.ethereum.network.PeerActor.StatusResponse
import com.chipprbots.ethereum.network.handshaker.*
import com.chipprbots.ethereum.network.handshaker.Handshaker.NextMessage
import com.chipprbots.ethereum.network.p2p.Message
import com.chipprbots.ethereum.network.p2p.messages.Capability
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.Status68.Status68 as Status
import com.chipprbots.ethereum.network.p2p.messages.WireProtocol.Disconnect
import com.chipprbots.ethereum.network.p2p.messages.WireProtocol.Hello
import com.chipprbots.ethereum.network.p2p.messages.WireProtocol.Pong
import com.chipprbots.ethereum.network.rlpx.RLPxConnectionHandler
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.Config

class PeerActorHandshakingSpec extends AnyFlatSpec with Matchers:

  it should "succeed in establishing connection if the handshake is always successful" taggedAs (
    UnitTest,
    NetworkTest
  ) in new TestSetup:

    import DefaultValues.*

    val peerActorHandshakeSucceeds: TestActorRef[Nothing] =
      peerActor(MockHandshakerAlwaysSucceeds(defaultStatus, defaultBlockNumber, defaultForkAccepted))

    // Establish probe rlpxconnection
    peerActorHandshakeSucceeds ! ConnectTo(uri)
    rlpxConnectionProbe.expectMsg(RLPxConnectionHandler.ConnectTo(uri))
    rlpxConnectionProbe.send(peerActorHandshakeSucceeds, RLPxConnectionHandler.ConnectionEstablished(ByteString()))

    // Test that the handshake succeeded
    expectStatus(peerActorHandshakeSucceeds, StatusResponse(Handshaked))

  it should "fail in establishing connection if the handshake always fails" taggedAs (
    UnitTest,
    NetworkTest
  ) in new TestSetup:

    import DefaultValues.*

    val peerActorHandshakeFails: TestActorRef[Nothing] =
      peerActor(MockHandshakerAlwaysFails(defaultReasonDisconnect))

    // Establish probe rlpxconnection
    peerActorHandshakeFails ! ConnectTo(uri)
    rlpxConnectionProbe.expectMsg(RLPxConnectionHandler.ConnectTo(uri))
    rlpxConnectionProbe.send(peerActorHandshakeFails, RLPxConnectionHandler.ConnectionEstablished(ByteString()))

    // Test that the handshake failed
    rlpxConnectionProbe.expectMsg(RLPxConnectionHandler.SendMessage(Disconnect(defaultReasonDisconnect)))

  it should "succeed in establishing connection in simple Hello exchange" taggedAs (
    UnitTest,
    NetworkTest
  ) in new TestSetup:

    import DefaultValues.*

    val peerActorHandshakeRequiresHello: TestActorRef[Nothing] = peerActor(MockHandshakerRequiresHello())

    // Establish probe rlpxconnection
    peerActorHandshakeRequiresHello ! ConnectTo(uri)
    rlpxConnectionProbe.expectMsg(RLPxConnectionHandler.ConnectTo(uri))
    rlpxConnectionProbe.send(peerActorHandshakeRequiresHello, RLPxConnectionHandler.ConnectionEstablished(ByteString()))

    rlpxConnectionProbe.expectMsg(RLPxConnectionHandler.SendMessage(defaultHello))
    peerActorHandshakeRequiresHello ! RLPxConnectionHandler.MessageReceived(defaultHello)

    // Test that the handshake succeeded
    expectStatus(peerActorHandshakeRequiresHello, StatusResponse(Handshaked))

  it should "fail in establishing connection in simple Hello exchange if timeout happened" taggedAs (
    UnitTest,
    NetworkTest
  ) in new TestSetup:

    import DefaultValues.*

    val peerActorHandshakeRequiresHello: TestActorRef[Nothing] = peerActor(MockHandshakerRequiresHello())

    // Establish probe rlpxconnection
    peerActorHandshakeRequiresHello ! ConnectTo(uri)
    rlpxConnectionProbe.expectMsg(RLPxConnectionHandler.ConnectTo(uri))
    rlpxConnectionProbe.send(peerActorHandshakeRequiresHello, RLPxConnectionHandler.ConnectionEstablished(ByteString()))

    rlpxConnectionProbe.expectMsg(RLPxConnectionHandler.SendMessage(defaultHello))
    testScheduler.timePasses(defaultTimeout * 2)

    // Test that the handshake failed
    rlpxConnectionProbe.expectMsg(RLPxConnectionHandler.SendMessage(Disconnect(defaultReasonDisconnect)))

  it should "fail in establishing connection in simple Hello exchange if a Status message was received" taggedAs (
    UnitTest,
    NetworkTest
  ) in new TestSetup:

    import DefaultValues.*

    val peerActorHandshakeRequiresHello: TestActorRef[Nothing] = peerActor(MockHandshakerRequiresHello())

    // Establish probe rlpxconnection
    peerActorHandshakeRequiresHello ! ConnectTo(uri)
    rlpxConnectionProbe.expectMsg(RLPxConnectionHandler.ConnectTo(uri))
    rlpxConnectionProbe.send(peerActorHandshakeRequiresHello, RLPxConnectionHandler.ConnectionEstablished(ByteString()))

    rlpxConnectionProbe.expectMsg(RLPxConnectionHandler.SendMessage(defaultHello))
    peerActorHandshakeRequiresHello ! RLPxConnectionHandler.MessageReceived(defaultStatusMsg)

    // Test that the handshake failed
    rlpxConnectionProbe.expectMsg(RLPxConnectionHandler.SendMessage(Disconnect(defaultReasonDisconnect)))

  it should "ignore unhandled message while establishing connection" taggedAs (UnitTest, NetworkTest) in new TestSetup:

    import DefaultValues.*

    val peerActorHandshakeRequiresHello: TestActorRef[Nothing] = peerActor(MockHandshakerRequiresHello())

    // Establish probe rlpxconnection
    peerActorHandshakeRequiresHello ! ConnectTo(uri)
    rlpxConnectionProbe.expectMsg(RLPxConnectionHandler.ConnectTo(uri))
    rlpxConnectionProbe.send(peerActorHandshakeRequiresHello, RLPxConnectionHandler.ConnectionEstablished(ByteString()))

    rlpxConnectionProbe.expectMsg(RLPxConnectionHandler.SendMessage(defaultHello))
    peerActorHandshakeRequiresHello ! RLPxConnectionHandler.MessageReceived(Pong()) // Ignored
    peerActorHandshakeRequiresHello ! RLPxConnectionHandler.MessageReceived(Pong()) // Ignored
    peerActorHandshakeRequiresHello ! RLPxConnectionHandler.MessageReceived(Pong()) // Ignored
    peerActorHandshakeRequiresHello ! RLPxConnectionHandler.MessageReceived(defaultHello)

    // Test that the handshake succeeded
    expectStatus(peerActorHandshakeRequiresHello, StatusResponse(Handshaked))

  it should "retry a TooManyPeers pre-Hello refusal after at least geth's 30s inbound throttle" taggedAs (
    UnitTest,
    NetworkTest
  ) in new TestSetup:

    import DefaultValues.*

    val peer: TestActorRef[Nothing] = peerActor(MockHandshakerRequiresHello())
    peer ! ConnectTo(uri)
    rlpxConnectionProbe.expectMsg(RLPxConnectionHandler.ConnectTo(uri))
    rlpxConnectionProbe.send(peer, RLPxConnectionHandler.ConnectionEstablished(ByteString()))
    rlpxConnectionProbe.expectMsg(RLPxConnectionHandler.SendMessage(defaultHello))

    rlpxConnectionProbe.send(peer, RLPxConnectionHandler.ConnectionRejected(Disconnect.Reasons.TooManyPeers))

    testScheduler.timePasses(29.seconds) // inside geth's inbound throttle: no redial yet
    rlpxConnectionProbe.expectNoMessage(200.millis)
    testScheduler.timePasses(2.seconds)
    rlpxConnectionProbe.expectMsgClass(classOf[RLPxConnectionHandler.ConnectTo]) // uri is rewritten after Established

  it should "never remove a node from the known-nodes store because of transient pre-Hello refusals" taggedAs (
    UnitTest,
    NetworkTest
  ) in new TestSetup:

    val peer: TestActorRef[Nothing] = peerActor(MockHandshakerRequiresHello())
    peer ! ConnectTo(uri)
    rlpxConnectionProbe.expectMsg(RLPxConnectionHandler.ConnectTo(uri))

    // Exhaust every retry with AlreadyConnected refusals (answered in waitingForConnectionResult).
    (1 to Config.Network.peer.connectMaxRetries).foreach { _ =>
      rlpxConnectionProbe.send(peer, RLPxConnectionHandler.ConnectionRejected(Disconnect.Reasons.AlreadyConnected))
      testScheduler.timePasses(31.seconds)
      rlpxConnectionProbe.expectMsgClass(classOf[RLPxConnectionHandler.ConnectTo])
    }
    rlpxConnectionProbe.send(peer, RLPxConnectionHandler.ConnectionRejected(Disconnect.Reasons.AlreadyConnected))

    knownNodesManager.expectNoMessage(300.millis) // a permanent failure would send RemoveKnownNode here

  "PeerActor.rejectionRetryDelay" should "treat only transient reasons as retryable" taggedAs (
    UnitTest,
    NetworkTest
  ) in {
    val base = 15.seconds
    PeerActor.rejectionRetryDelay(Disconnect.Reasons.TooManyPeers, base) shouldBe Some(30.seconds)
    PeerActor.rejectionRetryDelay(Disconnect.Reasons.TooManyPeers, 45.seconds) shouldBe Some(45.seconds)
    PeerActor.rejectionRetryDelay(Disconnect.Reasons.AlreadyConnected, base) shouldBe Some(base)
    PeerActor.rejectionRetryDelay(Disconnect.Reasons.ClientQuitting, base) shouldBe Some(base)
    PeerActor.rejectionRetryDelay(Disconnect.Reasons.UselessPeer, base) shouldBe None
    PeerActor.rejectionRetryDelay(Disconnect.Reasons.IncompatibleP2pProtocolVersion, base) shouldBe None
    PeerActor.rejectionRetryDelay(Disconnect.Reasons.Other, base) shouldBe None
  }

  trait TestSetup extends EphemBlockchainTestSetup:
    implicit override lazy val classicSystem: ActorSystem =
      ActorSystem("PeerActorSpec_System", ConfigFactory.load("explicit-scheduler"))

    def testScheduler: ExplicitlyTriggeredScheduler = classicSystem.scheduler.asInstanceOf[ExplicitlyTriggeredScheduler]

    val uri = new URI(
      "enode://18a551bee469c2e02de660ab01dede06503c986f6b8520cb5a65ad122df88b17b285e3fef09a40a0d44f99e014f8616cf1ebc2e094f96c6e09e2f390f5d34857@47.90.36.129:30303"
    )
    val rlpxConnectionProbe: TestProbe = TestProbe()
    val peerMessageBus: TestProbe = TestProbe()
    val knownNodesManager: TestProbe = TestProbe()

    def peerActor(handshaker: Handshaker[PeerInfo]): TestActorRef[Nothing] = TestActorRef(
      PropsAdapter(
        PeerActor.apply(
          new InetSocketAddress("127.0.0.1", 0),
          rlpxConnectionFactory = _ => rlpxConnectionProbe.ref.toTyped[RLPxConnectionHandler.Command],
          peerConfiguration = Config.Network.peer,
          peerEventBus = peerMessageBus.ref,
          knownNodesManager = knownNodesManager.ref,
          incomingConnection = false,
          initHandshaker = handshaker
        )
      )
    )

    def expectStatus(peer: TestActorRef[Nothing], expected: StatusResponse): Unit =
      val statusProbe: TestProbe = TestProbe()(classicSystem)
      peer ! GetStatus(statusProbe.ref.toTyped[StatusResponse])
      statusProbe.expectMsg(expected)

  object DefaultValues:
    val defaultStatusMsg: Status = Status(
      protocolVersion = Capability.ETH63.version,
      networkId = 1,
      totalDifficulty = Fixtures.Blocks.Genesis.header.difficulty.value,
      bestHash = Fixtures.Blocks.Genesis.header.hash.value,
      genesisHash = Fixtures.Blocks.Genesis.header.hash.value,
      forkId = ForkId(0, None)
    )
    val defaultStatus: RemoteStatus = RemoteStatus(defaultStatusMsg)
    val defaultBlockNumber = 1000
    val defaultForkAccepted = true

    val defaultPeerInfo: PeerInfo = PeerInfo(
      defaultStatus,
      defaultStatus.chainWeight,
      defaultForkAccepted,
      defaultBlockNumber,
      defaultStatus.bestHash
    )

    val defaultReasonDisconnect = Disconnect.Reasons.Other

    val defaultHello: Hello = Hello(
      p2pVersion = 0,
      clientId = "notused",
      capabilities = Seq(Capability.ETH63),
      listenPort = 0,
      nodeId = ByteString.empty
    )
    val defaultTimeout = Timeouts.normalTimeout

  case class MockHandshakerRequiresHello private (handshakerState: HandshakerState[PeerInfo])
      extends Handshaker[PeerInfo]:
    override def copy(newState: HandshakerState[PeerInfo]): Handshaker[PeerInfo] = new MockHandshakerRequiresHello(
      newState
    )

  object MockHandshakerRequiresHello:
    def apply(): MockHandshakerRequiresHello =
      new MockHandshakerRequiresHello(MockHelloExchangeState)

  case object MockHelloExchangeState extends InProgressState[PeerInfo]:

    import DefaultValues.*

    def nextMessage: NextMessage = NextMessage(defaultHello, defaultTimeout)

    def applyResponseMessage: PartialFunction[Message, HandshakerState[PeerInfo]] = {
      case _: Hello  => ConnectedState(defaultPeerInfo)
      case _: Status => DisconnectedState(defaultReasonDisconnect)
    }

    def processTimeout: HandshakerState[PeerInfo] = DisconnectedState(defaultReasonDisconnect)
