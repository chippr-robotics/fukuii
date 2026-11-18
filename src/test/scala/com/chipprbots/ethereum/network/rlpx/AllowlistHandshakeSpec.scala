package com.chipprbots.ethereum.network.rlpx

import java.net.URI

import org.apache.pekko.actor.ActorRef
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.io.Tcp
import org.apache.pekko.testkit.TestActorRef
import org.apache.pekko.testkit.TestKit
import org.apache.pekko.testkit.TestProbe
import org.apache.pekko.util.ByteString

import scala.concurrent.duration.*

import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.WithActorSystemShutDown
import com.chipprbots.ethereum.network.AllowlistSnapshot
import com.chipprbots.ethereum.network.AllowlistSource
import com.chipprbots.ethereum.network.NodeAllowlist
import com.chipprbots.ethereum.network.NodeAllowlistMetrics
import com.chipprbots.ethereum.network.PeerActor
import com.chipprbots.ethereum.network.p2p.messages.Capability
import com.chipprbots.ethereum.network.p2p.messages.WireProtocol.Hello
import com.chipprbots.ethereum.network.rlpx.RLPxConnectionHandler.HelloCodec
import com.chipprbots.ethereum.network.rlpx.RLPxConnectionHandler.RLPxConfiguration
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.Hex

/** Spec 030 FR-051: the node allowlist is checked on the authenticated remote key right after the RLPx auth handshake,
  * before `ConnectionEstablished` and before any Hello, even one that arrived in the same TCP segment as the auth
  * packet.
  */
class AllowlistHandshakeSpec
    extends TestKit(ActorSystem("AllowlistHandshakeSpec_System"))
    with AnyFlatSpecLike
    with WithActorSystemShutDown
    with Matchers:

  private val listedHex = "aa" * 64
  private val unlistedHex = "bb" * 64
  private def id(hex: String): ByteString = ByteString(Hex.decode(hex))

  private def allowlistOf(hexes: String*): NodeAllowlist =
    val parsed = hexes.map(h => NodeAllowlist.parseEntry(h).toOption.get)
    NodeAllowlist.enabled(AllowlistSnapshot(NodeAllowlist.merge(Seq(AllowlistSource.Config -> parsed), None), 1L))

  /** An AuthHandshaker double that "authenticates" `remoteKey` for both directions. */
  private class FixedAuthHandshaker(remoteKey: ByteString)
      extends AuthHandshaker(
        nodeKey = newKeyPair(),
        nonce = ByteString.empty,
        ephemeralKey = newKeyPair(),
        secureRandom = new java.security.SecureRandom(),
        isInitiator = false,
        initiatePacketOpt = None,
        responsePacketOpt = None,
        remotePubKeyOpt = None
      ):
    private def success: AuthHandshakeResult =
      AuthHandshakeSuccess(
        new Secrets(
          Array.empty[Byte],
          Array.empty[Byte],
          Array.empty[Byte],
          new org.bouncycastle.crypto.digests.KeccakDigest(256),
          new org.bouncycastle.crypto.digests.KeccakDigest(256)
        ),
        remoteKey
      )
    override def handleInitialMessage(data: ByteString): (ByteString, AuthHandshakeResult) =
      (ByteString("auth-ack"), success)
    override def handleResponseMessage(data: ByteString): AuthHandshakeResult = success
    override def initiate(uri: URI): (ByteString, AuthHandshaker) = (ByteString("auth-init"), this)

  private def newKeyPair(): AsymmetricCipherKeyPair =
    com.chipprbots.ethereum.crypto.generateKeyPair(new java.security.SecureRandom())

  private val rlpxConfiguration: RLPxConfiguration = new RLPxConfiguration:
    override val waitForTcpAckTimeout: FiniteDuration = 5.seconds
    override val waitForHandshakeTimeout: FiniteDuration = 30.seconds

  private class Setup(remoteHex: String, allowlist: NodeAllowlist):
    val connection: TestProbe = TestProbe()
    val tcp: TestProbe = TestProbe()
    val parent: TestProbe = TestProbe()
    val helloExtractorCalls = new java.util.concurrent.atomic.AtomicInteger(0)
    private val extractor: HelloCodec = new HelloCodec(null):
      override def readHello(data: ByteString): Option[(Hello, Seq[com.chipprbots.ethereum.network.rlpx.Frame])] =
        helloExtractorCalls.incrementAndGet()
        None

    val handler: TestActorRef[Nothing] = TestActorRef(
      org.apache.pekko.actor.typed.scaladsl.adapter.PropsAdapter(
        RLPxConnectionHandler.apply(
          Capability.ETH68 :: Nil,
          new FixedAuthHandshaker(id(remoteHex)),
          (_, _, _, _, _, _) => throw new IllegalStateException("no message codec before the handshake is accepted"),
          rlpxConfiguration,
          _ => extractor,
          parent.ref.toTyped[PeerActor.Command],
          Some(tcp.ref),
          allowlist = allowlist
        )
      ),
      parent.ref
    )
    parent.watch(handler)

    /** Inbound: the auth packet and, in the same segment, bytes that stand for the remote's Hello. */
    def inboundAuthWithHelloInSameSegment(): Unit =
      handler ! RLPxConnectionHandler.HandleConnection(connection.ref)
      connection.expectMsgClass(classOf[Tcp.Register])
      val bridge: ActorRef = connection.lastSender
      val auth = ByteString(Array.fill[Byte](AuthHandshaker.InitiatePacketLength)(1))
      val helloBytes = ByteString(Array.fill[Byte](64)(2))
      bridge ! Tcp.Received(auth ++ helloBytes)

  behavior.of("RLPxConnectionHandler with a node allowlist")

  it should "close an inbound connection whose key is not listed, before the Hello is read (FR-051)" taggedAs (
    UnitTest,
    NetworkTest
  ) in {
    val before = NodeAllowlistMetrics.InboundRejections.count()
    val s = new Setup(unlistedHex, allowlistOf(listedHex))
    s.inboundAuthWithHelloInSameSegment()

    s.parent.expectMsg(3.seconds, RLPxConnectionHandler.ConnectionRejected(RLPxConnectionHandler.NotOnAllowlist))
    s.parent.expectTerminated(s.handler, 3.seconds)
    s.helloExtractorCalls.get shouldBe 0 // the Hello that came with the auth packet was never decoded
    NodeAllowlistMetrics.InboundRejections.count() - before shouldBe 1.0
  }

  it should "never send ConnectionEstablished for an unlisted key" taggedAs (UnitTest, NetworkTest) in {
    val s = new Setup(unlistedHex, allowlistOf(listedHex))
    s.inboundAuthWithHelloInSameSegment()
    s.parent.fishForMessage(3.seconds) {
      case _: RLPxConnectionHandler.ConnectionEstablished => fail("ConnectionEstablished sent for an unlisted key")
      case RLPxConnectionHandler.ConnectionRejected(_)    => true
      case _                                              => false
    }
  }

  it should "let a listed key through to ConnectionEstablished and the Hello" taggedAs (UnitTest, NetworkTest) in {
    val before = NodeAllowlistMetrics.InboundRejections.count()
    val s = new Setup(listedHex, allowlistOf(listedHex))
    s.inboundAuthWithHelloInSameSegment()

    s.parent.expectMsgType[RLPxConnectionHandler.ConnectionEstablished](3.seconds).nodeId shouldBe id(listedHex)
    awaitAssert(s.helloExtractorCalls.get should be >= 1)
    NodeAllowlistMetrics.InboundRejections.count() - before shouldBe 0.0
  }

  it should "leave every connection alone when the allowlist is off" taggedAs (UnitTest, NetworkTest) in {
    val s = new Setup(unlistedHex, NodeAllowlist.Disabled)
    s.inboundAuthWithHelloInSameSegment()
    s.parent.expectMsgType[RLPxConnectionHandler.ConnectionEstablished](3.seconds).nodeId shouldBe id(unlistedHex)
  }

  it should "close an outbound connection whose authenticated key is not listed, and count it as outbound" taggedAs (
    UnitTest,
    NetworkTest
  ) in {
    val before = NodeAllowlistMetrics.OutboundRejections.count()
    val s = new Setup(unlistedHex, allowlistOf(listedHex))
    val uri = new URI(s"enode://$unlistedHex@10.0.0.9:30303")
    val addr = new java.net.InetSocketAddress("10.0.0.9", 30303)
    s.handler ! RLPxConnectionHandler.ConnectTo(uri)
    s.tcp.expectMsg(3.seconds, Tcp.Connect(addr))
    val bridge = s.tcp.lastSender
    s.tcp.reply(Tcp.Connected(addr, addr))
    s.tcp.expectMsgClass(3.seconds, classOf[Tcp.Register])
    s.tcp.expectMsgClass(3.seconds, classOf[Tcp.Write]) // the auth init
    bridge.tell(Tcp.Received(ByteString(Array.fill[Byte](AuthHandshaker.ResponsePacketLength)(3))), s.tcp.ref)

    s.parent.expectMsg(3.seconds, RLPxConnectionHandler.ConnectionRejected(RLPxConnectionHandler.NotOnAllowlist))
    NodeAllowlistMetrics.OutboundRejections.count() - before shouldBe 1.0
  }
