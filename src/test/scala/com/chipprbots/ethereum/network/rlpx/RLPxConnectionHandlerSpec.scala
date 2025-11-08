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
import com.chipprbots.ethereum.network.rlpx.RLPxConnectionHandler.HelloCodec
import com.chipprbots.ethereum.network.rlpx.RLPxConnectionHandler.InitialHelloReceived
import com.chipprbots.ethereum.network.rlpx.RLPxConnectionHandler.RLPxConfiguration
import com.chipprbots.ethereum.security.SecureRandomBuilder

import org.scalatest.Ignore

// SCALA 3 MIGRATION: Fixed by creating manual stub implementation for AuthHandshaker
// @Ignore - Un-ignored per issue to identify test failures
class RLPxConnectionHandlerSpec
    extends TestKit(ActorSystem("RLPxConnectionHandlerSpec_System"))
    with AnyFlatSpecLike
    with WithActorSystemShutDown
    with Matchers
    with MockFactory {

  it should "write messages send to TCP connection" in new TestSetup {

    setupIncomingRLPxConnection()

    (mockMessageCodec.encodeMessage _).expects(Ping(): MessageSerializable).returning(ByteString("ping encoded"))
    rlpxConnection ! RLPxConnectionHandler.SendMessage(Ping())
    connection.expectMsg(Tcp.Write(ByteString("ping encoded"), RLPxConnectionHandler.Ack))

  }

  it should "write messages to TCP connection once all previous ACK were received" in new TestSetup {

    (mockMessageCodec.encodeMessage _)
      .expects(Ping(): MessageSerializable)
      .returning(ByteString("ping encoded"))
      .anyNumberOfTimes()

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

  it should "accummulate messages and write them when receiving ACKs" in new TestSetup {

    (mockMessageCodec.encodeMessage _)
      .expects(Ping(): MessageSerializable)
      .returning(ByteString("ping encoded"))
      .anyNumberOfTimes()

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

  it should "close the connection when Ack timeout happens" in new TestSetup {
    (mockMessageCodec.encodeMessage _)
      .expects(Ping(): MessageSerializable)
      .returning(ByteString("ping encoded"))
      .anyNumberOfTimes()

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

  it should "ignore timeout of old messages" in new TestSetup {
    (mockMessageCodec.encodeMessage _)
      .expects(Ping(): MessageSerializable)
      .returning(ByteString("ping encoded"))
      .anyNumberOfTimes()

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

  it should "close the connection if the AuthHandshake init message's MAC is invalid" in new TestSetup {
    // Incomming connection arrives
    rlpxConnection ! RLPxConnectionHandler.HandleConnection(connection.ref)
    connection.expectMsgClass(classOf[Tcp.Register])

    // AuthHandshaker throws exception on initial message
    (mockHandshaker.handleInitialMessage _).expects(*).onCall((_: ByteString) => throw new Exception("MAC invalid"))
    (mockHandshaker.handleInitialMessageV4 _).expects(*).onCall { (_: ByteString) =>
      throw new Exception("MAC invalid")
    }

    val data = ByteString((0 until AuthHandshaker.InitiatePacketLength).map(_.toByte).toArray)
    rlpxConnection ! Tcp.Received(data)
    rlpxConnectionParent.expectMsg(RLPxConnectionHandler.ConnectionFailed)
    rlpxConnectionParent.expectTerminated(rlpxConnection)
  }

  it should "close the connection if the AuthHandshake response message's MAC is invalid" in new TestSetup {
    // Outgoing connection request arrives
    rlpxConnection ! RLPxConnectionHandler.ConnectTo(uri)
    tcpActorProbe.expectMsg(Tcp.Connect(inetAddress))

    // The TCP connection results are handled
    val initPacket = ByteString("Init packet")
    (mockHandshaker.initiate _).expects(uri).returning(initPacket -> mockHandshaker)

    tcpActorProbe.reply(Tcp.Connected(inetAddress, inetAddress))
    tcpActorProbe.expectMsg(Tcp.Register(rlpxConnection))
    tcpActorProbe.expectMsg(Tcp.Write(initPacket))

    // AuthHandshaker handles the response message (that throws an invalid MAC)
    (mockHandshaker.handleResponseMessage _).expects(*).onCall((_: ByteString) => throw new Exception("MAC invalid"))
    (mockHandshaker.handleResponseMessageV4 _).expects(*).onCall { (_: ByteString) =>
      throw new Exception("MAC invalid")
    }

    val data = ByteString((0 until AuthHandshaker.ResponsePacketLength).map(_.toByte).toArray)
    rlpxConnection ! Tcp.Received(data)
    rlpxConnectionParent.expectMsg(RLPxConnectionHandler.ConnectionFailed)
    rlpxConnectionParent.expectTerminated(rlpxConnection)
  }

  trait TestSetup extends SecureRandomBuilder {

    // Mock parameters for RLPxConnectionHandler
    val mockMessageDecoder: MessageDecoder = new MessageDecoder {
      override def fromBytes(`type`: Int, payload: Array[Byte]) =
        throw new Exception("Mock message decoder fails to decode all messages")
    }
    val protocolVersion = Capability.ETH63
    val mockHandshaker: AuthHandshaker = createStubAuthHandshaker()
    val connection: TestProbe = TestProbe()
    val mockMessageCodec: MessageCodec = mock[MessageCodec]
    val mockHelloExtractor: HelloCodec = mock[HelloCodec]
    
    private def createStubAuthHandshaker(): AuthHandshaker = {
      import java.security.SecureRandom
      import com.chipprbots.ethereum.crypto.generateKeyPair
      
      val sr = new SecureRandom()
      
      AuthHandshaker(
        nodeKey = generateKeyPair(sr),
        nonce = ByteString.empty,
        ephemeralKey = generateKeyPair(sr),
        secureRandom = sr,
        isInitiator = false,
        initiatePacketOpt = None,
        responsePacketOpt = None,
        remotePubKeyOpt = None
      )
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

    val tcpActorProbe: TestProbe = TestProbe()
    val rlpxConnectionParent: TestProbe = TestProbe()
    val rlpxConnection: TestActorRef[Nothing] = TestActorRef(
      Props(
        new RLPxConnectionHandler(
          protocolVersion :: Nil,
          mockHandshaker,
          (_, _, _) => mockMessageCodec,
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

      // AuthHandshaker handles initial message
      val data = ByteString((0 until AuthHandshaker.InitiatePacketLength).map(_.toByte).toArray)
      val hello = ByteString((1 until AuthHandshaker.InitiatePacketLength).map(_.toByte).toArray)
      val response = ByteString("response data")
      (mockHandshaker.handleInitialMessage _)
        .expects(data)
        // MIGRATION: Scala 3 requires explicit type ascription for mock with complex parameterized types
        // Create a minimal Secrets instance for test purposes
        .returning((response, AuthHandshakeSuccess(
          new Secrets(Array.emptyByteArray, Array.emptyByteArray, Array.emptyByteArray, 
            new org.bouncycastle.crypto.digests.KeccakDigest(256), 
            new org.bouncycastle.crypto.digests.KeccakDigest(256)), 
          ByteString())))
      (mockHelloExtractor.readHello _)
        .expects(ByteString.empty)
        .returning(Some((Hello(5, "", Capability.ETH63 :: Nil, 30303, ByteString("abc")), Seq.empty)))
      (mockMessageCodec.readMessages _)
        .expects(hello)
        .returning(Nil) // For processing of messages after handshaking finishes

      rlpxConnection ! Tcp.Received(data)
      connection.expectMsg(Tcp.Write(response))

      rlpxConnection ! Tcp.Received(hello)

      // Connection fully established
      rlpxConnectionParent.expectMsgClass(classOf[RLPxConnectionHandler.ConnectionEstablished])
    }
  }
}
