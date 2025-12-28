package com.chipprbots.ethereum.network.rlpx

import java.net.InetSocketAddress
import java.net.URI

import org.apache.pekko.actor.ActorRef
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.Props
import org.apache.pekko.io.Tcp
import org.apache.pekko.testkit.TestActorRef
import org.apache.pekko.testkit.TestKit
import org.apache.pekko.testkit.TestProbe
import org.apache.pekko.util.ByteString

import scala.concurrent.duration.FiniteDuration

import org.scalamock.scalatest.MockFactory
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Timeouts
import com.chipprbots.ethereum.WithActorSystemShutDown
import com.chipprbots.ethereum.network.p2p.MessageDecoder
import com.chipprbots.ethereum.network.p2p.MessageSerializable
import com.chipprbots.ethereum.network.p2p.messages.Capability
import com.chipprbots.ethereum.network.p2p.messages.WireProtocol.Hello
import com.chipprbots.ethereum.network.p2p.messages.WireProtocol.Ping
import com.chipprbots.ethereum.network.rlpx.MessageCodec.CompressionPolicy
import com.chipprbots.ethereum.network.rlpx.RLPxConnectionHandler.HelloCodec
import com.chipprbots.ethereum.network.rlpx.RLPxConnectionHandler.InitialHelloReceived
import com.chipprbots.ethereum.network.rlpx.RLPxConnectionHandler.RLPxConfiguration
import com.chipprbots.ethereum.security.SecureRandomBuilder

import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import com.chipprbots.ethereum.testing.Tags._

// SCALA 3 MIGRATION: Fixed by creating manual stub implementation for AuthHandshaker
// @Ignore - Un-ignored per issue to identify test failures
class RLPxConnectionHandlerSpec
    extends TestKit(ActorSystem("RLPxConnectionHandlerSpec_System"))
    with AnyFlatSpecLike
    with WithActorSystemShutDown
    with Matchers
    with MockFactory {

  it should "write messages send to TCP connection" taggedAs (UnitTest, NetworkTest) in new TestSetup {
    mockMessageCodec.encodeMessageHandler = Some(_ => ByteString("ping encoded"))

    setupIncomingRLPxConnection()

    rlpxConnection ! RLPxConnectionHandler.SendMessage(Ping())
    connection.expectMsg(Tcp.Write(ByteString("ping encoded"), RLPxConnectionHandler.Ack))

  }

  it should "write messages to TCP connection once all previous ACK were received" taggedAs (
    UnitTest,
    NetworkTest
  ) in new TestSetup {
    mockMessageCodec.encodeMessageHandler = Some(_ => ByteString("ping encoded"))

    setupIncomingRLPxConnection()

    // Send first message
    rlpxConnection ! RLPxConnectionHandler.SendMessage(Ping())
    connection.expectMsg(Tcp.Write(ByteString("ping encoded"), RLPxConnectionHandler.Ack))
    rlpxConnection ! RLPxConnectionHandler.Ack
    connection.expectNoMessage()

    // Send second message
    rlpxConnection ! RLPxConnectionHandler.SendMessage(Ping())
    connection.expectMsg(Tcp.Write(ByteString("ping encoded"), RLPxConnectionHandler.Ack))
    rlpxConnection ! RLPxConnectionHandler.Ack
    connection.expectNoMessage()
  }

  it should "accummulate messages and write them when receiving ACKs" taggedAs (
    UnitTest,
    NetworkTest
  ) in new TestSetup {
    mockMessageCodec.encodeMessageHandler = Some(_ => ByteString("ping encoded"))

    setupIncomingRLPxConnection()

    // Send several messages
    rlpxConnection ! RLPxConnectionHandler.SendMessage(Ping())
    rlpxConnection ! RLPxConnectionHandler.SendMessage(Ping())
    rlpxConnection ! RLPxConnectionHandler.SendMessage(Ping())

    // Only first message is sent
    connection.expectMsg(Tcp.Write(ByteString("ping encoded"), RLPxConnectionHandler.Ack))
    connection.expectNoMessage()

    // Send Ack, second message should now be sent through TCP connection
    rlpxConnection ! RLPxConnectionHandler.Ack
    connection.expectMsg(Tcp.Write(ByteString("ping encoded"), RLPxConnectionHandler.Ack))
    connection.expectNoMessage()

    // Send Ack, third message should now be sent through TCP connection
    rlpxConnection ! RLPxConnectionHandler.Ack
    connection.expectMsg(Tcp.Write(ByteString("ping encoded"), RLPxConnectionHandler.Ack))
    connection.expectNoMessage()
  }

  it should "close the connection when Ack timeout happens" taggedAs (UnitTest, NetworkTest) in new TestSetup {
    mockMessageCodec.encodeMessageHandler = Some(_ => ByteString("ping encoded"))

    setupIncomingRLPxConnection()

    rlpxConnection ! RLPxConnectionHandler.SendMessage(Ping())
    connection.expectMsg(Tcp.Write(ByteString("ping encoded"), RLPxConnectionHandler.Ack))

    val expectedHello = rlpxConnectionParent.expectMsgType[InitialHelloReceived]
    expectedHello.message shouldBe a[Hello]

    // The rlpx connection is closed after a timeout happens (after rlpxConfiguration.waitForTcpAckTimeout) and it is processed
    rlpxConnectionParent.expectTerminated(
      rlpxConnection,
      max = rlpxConfiguration.waitForTcpAckTimeout + Timeouts.normalTimeout
    )
  }

  it should "ignore timeout of old messages" taggedAs (UnitTest, NetworkTest) in new TestSetup {
    mockMessageCodec.encodeMessageHandler = Some(_ => ByteString("ping encoded"))

    setupIncomingRLPxConnection()

    rlpxConnection ! RLPxConnectionHandler.SendMessage(Ping()) // With SEQ number 0
    rlpxConnection ! RLPxConnectionHandler.SendMessage(Ping()) // With SEQ number 1

    // Only first Ping is sent
    connection.expectMsg(Tcp.Write(ByteString("ping encoded"), RLPxConnectionHandler.Ack))

    // Upon Ack, the next message is sent
    rlpxConnection ! RLPxConnectionHandler.Ack
    connection.expectMsg(Tcp.Write(ByteString("ping encoded"), RLPxConnectionHandler.Ack))

    // AckTimeout for the first Ping is received
    rlpxConnection ! RLPxConnectionHandler.AckTimeout(0) // AckTimeout for first Ping message

    // Connection should continue to work perfectly
    rlpxConnection ! RLPxConnectionHandler.SendMessage(Ping())
    rlpxConnection ! RLPxConnectionHandler.Ack
    connection.expectMsg(Tcp.Write(ByteString("ping encoded"), RLPxConnectionHandler.Ack))
  }

  it should "close the connection if the AuthHandshake init message's MAC is invalid" taggedAs (
    UnitTest,
    NetworkTest
  ) in new TestSetup {
    // Incomming connection arrives
    rlpxConnection ! RLPxConnectionHandler.HandleConnection(connection.ref)
    connection.expectMsgClass(classOf[Tcp.Register])

    // AuthHandshaker throws exception on initial message
    mockHandshaker.handleInitialMessageHandler = Some(_ => throw new Exception("MAC invalid"))
    mockHandshaker.handleInitialMessageV4Handler = Some(_ => throw new Exception("MAC invalid"))

    val data = ByteString((0 until AuthHandshaker.InitiatePacketLength).map(_.toByte).toArray)
    rlpxConnection ! Tcp.Received(data)
    rlpxConnectionParent.expectMsg(RLPxConnectionHandler.ConnectionFailed)
    rlpxConnectionParent.expectTerminated(rlpxConnection)
  }

  it should "close the connection if the AuthHandshake response message's MAC is invalid" taggedAs (
    UnitTest,
    NetworkTest
  ) in new TestSetup {
    // Outgoing connection request arrives
    rlpxConnection ! RLPxConnectionHandler.ConnectTo(uri)
    tcpActorProbe.expectMsg(Tcp.Connect(inetAddress))

    // The TCP connection results are handled
    val initPacket = ByteString("Init packet")
    mockHandshaker.initiateHandler = Some(_ => initPacket -> mockHandshaker)

    tcpActorProbe.reply(Tcp.Connected(inetAddress, inetAddress))
    tcpActorProbe.expectMsg(Tcp.Register(rlpxConnection))
    tcpActorProbe.expectMsg(Tcp.Write(initPacket, RLPxConnectionHandler.Ack))

    // AuthHandshaker handles the response message (that throws an invalid MAC)
    mockHandshaker.handleResponseMessageHandler = Some(_ => throw new Exception("MAC invalid"))
    mockHandshaker.handleResponseMessageV4Handler = Some(_ => throw new Exception("MAC invalid"))

    val data = ByteString((0 until AuthHandshaker.ResponsePacketLength).map(_.toByte).toArray)
    rlpxConnection ! Tcp.Received(data)
    rlpxConnectionParent.expectMsg(RLPxConnectionHandler.ConnectionFailed)
    rlpxConnectionParent.expectTerminated(rlpxConnection)
  }

  it should "handle SendMessage gracefully during shutdown without dead letters" in new TestSetup {
    // Start setting up connection
    rlpxConnection ! RLPxConnectionHandler.HandleConnection(connection.ref)
    connection.expectMsgClass(classOf[Tcp.Register])

    // AuthHandshaker handles initial message and fails (simulating auth failure scenario)
    val data = ByteString((0 until AuthHandshaker.InitiatePacketLength).map(_.toByte).toArray)

    // Configure the test double to fail authentication
    mockHandshaker.handleInitialMessageHandler = Some(_ => throw new Exception("Auth failed"))
    mockHandshaker.handleInitialMessageV4Handler = Some(_ => throw new Exception("Auth failed"))

    // Send the auth data which will trigger shutdown
    rlpxConnection ! Tcp.Received(data)

    // Immediately send a SendMessage during the shutdown window
    rlpxConnection ! RLPxConnectionHandler.SendMessage(Ping())

    // The actor should gracefully handle the message and terminate without dead letters
    rlpxConnectionParent.expectMsg(RLPxConnectionHandler.ConnectionFailed)
    rlpxConnectionParent.expectTerminated(rlpxConnection, max = Timeouts.normalTimeout)
  }

  it should "handle late Hello message after handshake without compression" taggedAs (
    UnitTest,
    NetworkTest
  ) in new TestSetup {
    // Setup a mock that will capture what gets encoded
    var encodedMessages: List[ByteString] = Nil
    mockMessageCodec.encodeMessageHandler = Some { msg =>
      val encoded = ByteString(s"encoded:${msg.underlyingMsg.getClass.getSimpleName}")
      encodedMessages = encodedMessages :+ encoded
      encoded
    }

    setupIncomingRLPxConnection()

    // Clear any messages from setup
    encodedMessages = Nil

    // Send a late Hello message - this should NOT go through MessageCodec.encodeMessage
    // Instead, it should be written directly using frameCodec to avoid compression
    val lateHello = Hello(
      p2pVersion = 5,
      clientId = "test-client",
      capabilities = Seq(Capability.ETH63),
      listenPort = 30303,
      nodeId = ByteString(Array.fill[Byte](64)(0))
    )

    rlpxConnection ! RLPxConnectionHandler.SendMessage(lateHello)

    // The connection should write the Hello without going through MessageCodec.encodeMessage
    // (which would compress it). Instead, it should use frameCodec directly.
    // We can verify this by checking that encodeMessage was NOT called
    connection.expectMsgClass(classOf[Tcp.Write])

    // The encodeMessage handler should NOT have been called for Hello
    encodedMessages should be(empty)

    // Now send a regular message (non-Hello) and verify it goes through MessageCodec
    rlpxConnection ! RLPxConnectionHandler.Ack
    rlpxConnection ! RLPxConnectionHandler.SendMessage(Ping())
    connection.expectMsgClass(classOf[Tcp.Write])

    // This time encodeMessage should have been called
    encodedMessages should not be empty
    encodedMessages.head.utf8String should include("Ping")
  }

  // SCALA 3 MIGRATION: Cannot use self-type constraint with `new TestSetup` in Scala 3.
  // Using lazy val for mocks ensures they're created when accessed within MockFactory context.
  trait TestSetup extends SecureRandomBuilder {

    // Mock parameters for RLPxConnectionHandler
    val mockMessageDecoder: MessageDecoder = new MessageDecoder {
      override def fromBytes(`type`: Int, payload: Array[Byte]) =
        throw new Exception("Mock message decoder fails to decode all messages")
    }
    val protocolVersion = Capability.ETH63

    // SCALA 3 MIGRATION: Using configurable test double instead of mock because
    // AuthHandshaker with Selectable cannot be properly mocked in Scala 3
    lazy val mockHandshaker: ConfigurableAuthHandshaker = new ConfigurableAuthHandshaker()
    lazy val connection: TestProbe = TestProbe()

    // SCALA 3 MIGRATION: Cannot mock MessageCodec with constructor parameters in Scala 3
    // Using configurable test double pattern similar to ConfigurableAuthHandshaker
    private lazy val stubFrameCodec: FrameCodec = stub[FrameCodec]
    private val defaultCompressionPolicy = CompressionPolicy(
      compressOutbound = false,
      expectInboundCompressed = false
    )

    class ConfigurableMessageCodec
        extends MessageCodec(
          stubFrameCodec,
          mockMessageDecoder,
          5L, // remotePeer2PeerVersion
          "test-client",
          defaultCompressionPolicy
        ) {
      var encodeMessageHandler: Option[MessageSerializable => ByteString] = None
      var readMessagesHandler: Option[
        ByteString => Seq[Either[MessageDecoder.DecodingError, com.chipprbots.ethereum.network.p2p.Message]]
      ] = None

      override def encodeMessage(message: MessageSerializable): ByteString =
        encodeMessageHandler.getOrElse(super.encodeMessage)(message)

      override def readMessages(
          data: ByteString
      ): Seq[Either[MessageDecoder.DecodingError, com.chipprbots.ethereum.network.p2p.Message]] =
        readMessagesHandler.getOrElse(super.readMessages)(data)
    }

    lazy val mockMessageCodec: ConfigurableMessageCodec = new ConfigurableMessageCodec()

    lazy val mockHelloExtractor: HelloCodec = mock[HelloCodec]

    // Configurable test double for AuthHandshaker that can be set up for different test scenarios
    class ConfigurableAuthHandshaker
        extends AuthHandshaker(
          nodeKey = ConfigurableAuthHandshaker.generateKeyPairHelper(),
          nonce = ByteString.empty,
          ephemeralKey = ConfigurableAuthHandshaker.generateKeyPairHelper(),
          secureRandom = new java.security.SecureRandom(),
          isInitiator = false,
          initiatePacketOpt = None,
          responsePacketOpt = None,
          remotePubKeyOpt = None
        ) {
      var initiateHandler: Option[URI => (ByteString, AuthHandshaker)] = None
      var handleInitialMessageHandler: Option[ByteString => (ByteString, AuthHandshakeResult)] = None
      var handleInitialMessageV4Handler: Option[ByteString => (ByteString, AuthHandshakeResult)] = None
      var handleResponseMessageHandler: Option[ByteString => AuthHandshakeResult] = None
      var handleResponseMessageV4Handler: Option[ByteString => AuthHandshakeResult] = None

      override def initiate(uri: URI): (ByteString, AuthHandshaker) =
        initiateHandler.map(_(uri)).getOrElse(super.initiate(uri))

      override def handleInitialMessage(data: ByteString): (ByteString, AuthHandshakeResult) =
        handleInitialMessageHandler.map(_(data)).getOrElse(super.handleInitialMessage(data))

      override def handleInitialMessageV4(
          data: ByteString,
          peerLabel: => String = "unknown"
      ): (ByteString, AuthHandshakeResult) =
        handleInitialMessageV4Handler
          .map(_(data))
          .getOrElse(super.handleInitialMessageV4(data, peerLabel))

      override def handleResponseMessage(data: ByteString): AuthHandshakeResult =
        handleResponseMessageHandler.map(_(data)).getOrElse(super.handleResponseMessage(data))

      override def handleResponseMessageV4(
          data: ByteString,
          peerLabel: => String = "unknown"
      ): AuthHandshakeResult =
        handleResponseMessageV4Handler
          .map(_(data))
          .getOrElse(super.handleResponseMessageV4(data, peerLabel))
    }

    object ConfigurableAuthHandshaker {
      private def generateKeyPairHelper(): AsymmetricCipherKeyPair = {
        import java.security.SecureRandom
        import com.chipprbots.ethereum.crypto.generateKeyPair
        generateKeyPair(new SecureRandom())
      }
    }

    val uri = new URI(
      "enode://18a551bee469c2e02de660ab01dede06503c986f6b8520cb5a65ad122df88b17b285e3fef09a40a0d44f99e014f8616cf1ebc2e094f96c6e09e2f390f5d34857@47.90.36.129:30303"
    )
    val inetAddress = new InetSocketAddress(uri.getHost, uri.getPort)

    val rlpxConfiguration: RLPxConfiguration = new RLPxConfiguration {
      override val waitForTcpAckTimeout: FiniteDuration = Timeouts.normalTimeout

      // unused
      override val waitForHandshakeTimeout: FiniteDuration = Timeouts.veryLongTimeout
    }

    lazy val tcpActorProbe: TestProbe = TestProbe()
    lazy val rlpxConnectionParent: TestProbe = TestProbe()
    lazy val rlpxConnection: TestActorRef[Nothing] = TestActorRef(
      Props(
        new RLPxConnectionHandler(
          protocolVersion :: Nil,
          mockHandshaker,
          (
              frameCodec: FrameCodec,
              capability: Capability,
              p2pVersion: Long,
              clientId: String,
        compressionPolicy: MessageCodec.CompressionPolicy,
        supportsSnap: Boolean
          ) => mockMessageCodec,
          rlpxConfiguration,
          _ => mockHelloExtractor
        ) {
          override def tcpActor: ActorRef = tcpActorProbe.ref
        }
      ),
      rlpxConnectionParent.ref
    )
    rlpxConnectionParent.watch(rlpxConnection)

    // Setup for RLPxConnection, after it the RLPxConnectionHandler is in a handshaked state
    def setupIncomingRLPxConnection(): Unit = {
      // Start setting up connection
      rlpxConnection ! RLPxConnectionHandler.HandleConnection(connection.ref)
      connection.expectMsgClass(classOf[Tcp.Register])

      // Configure stubFrameCodec to return empty Seq instead of null
      (stubFrameCodec.readFrames _).when(*).returns(Seq.empty)
      (stubFrameCodec.writeFrames _).when(*).returns(ByteString.empty)

      // AuthHandshaker handles initial message
      val data = ByteString((0 until AuthHandshaker.InitiatePacketLength).map(_.toByte).toArray)
      val hello = ByteString((1 until AuthHandshaker.InitiatePacketLength).map(_.toByte).toArray)
      val response = ByteString("response data")

      // Configure the test double to return specific responses
      mockHandshaker.handleInitialMessageHandler = Some { _ =>
        (
          response,
          AuthHandshakeSuccess(
            new Secrets(
              Array.empty[Byte],
              Array.empty[Byte],
              Array.empty[Byte],
              new org.bouncycastle.crypto.digests.KeccakDigest(256),
              new org.bouncycastle.crypto.digests.KeccakDigest(256)
            ),
            ByteString()
          )
        )
      }

      (mockHelloExtractor.readHello _)
        .expects(ByteString.empty)
        .returning(Some((Hello(5, "", Capability.ETH63 :: Nil, 30303, ByteString("abc")), Seq.empty)))
      mockMessageCodec.readMessagesHandler = Some(_ => Nil) // For processing of messages after handshaking finishes

      rlpxConnection ! Tcp.Received(data)
      connection.expectMsg(Tcp.Write(response, RLPxConnectionHandler.Ack))

      rlpxConnection ! Tcp.Received(hello)

      // Connection fully established
      rlpxConnectionParent.expectMsgClass(classOf[RLPxConnectionHandler.ConnectionEstablished])
    }
  }
}
