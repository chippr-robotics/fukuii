package com.chipprbots.ethereum.network.p2p

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.network.handshaker.EtcHelloExchangeState
import com.chipprbots.ethereum.network.p2p.messages.BaseETH6XMessages.Status
import com.chipprbots.ethereum.network.p2p.messages.Capability
import com.chipprbots.ethereum.network.p2p.messages.Codes
import com.chipprbots.ethereum.network.p2p.messages.WireProtocol.Hello
import com.chipprbots.ethereum.network.rlpx.Frame
import com.chipprbots.ethereum.network.rlpx.FrameCodec
import com.chipprbots.ethereum.network.rlpx.Header
import com.chipprbots.ethereum.network.rlpx.MessageCodec
import com.chipprbots.ethereum.testing.Tags._
import com.chipprbots.ethereum.utils.Config

class MessageCodecSpec extends AnyFlatSpec with Matchers {

  it should "not compress messages when remote side advertises p2p version less than 5" taggedAs (
    UnitTest,
    NetworkTest
  ) in new TestSetup {
    // NOTE: Compression is currently DISABLED globally (emergency fix for FUKUII-COMPRESSION-001)
    // This test validates that messages are sent uncompressed regardless of p2p version
    val remoteHello: ByteString = remoteMessageCodec.encodeMessage(helloV4)
    messageCodec.readMessages(remoteHello)

    val localNextMessageAfterHello: ByteString = messageCodec.encodeMessage(status)
    val remoteReadNotCompressedStatus: Seq[Either[Throwable, Message]] =
      remoteMessageCodec.readMessages(localNextMessageAfterHello)

    // Messages should not be compressed
    assert(remoteReadNotCompressedStatus.size == 1)
    assert(remoteReadNotCompressedStatus.head == Right(status))
  }

  it should "not compress messages even when remote side advertises p2p version larger or equal 5 (emergency fix)" taggedAs (
    UnitTest,
    NetworkTest
  ) in new TestSetup {
    // NOTE: Compression is currently DISABLED globally (emergency fix for FUKUII-COMPRESSION-001)
    override lazy val negotiatedRemoteP2PVersion: Long = 5L
    override lazy val negotiatedLocalP2PVersion: Long = 3L

    val remoteHello: ByteString = remoteMessageCodec.encodeMessage(helloV5)
    messageCodec.readMessages(remoteHello)

    val localNextMessageAfterHello: ByteString = messageCodec.encodeMessage(status)
    val remoteReadNotCompressedStatus: Seq[Either[Throwable, Message]] =
      remoteMessageCodec.readMessages(localNextMessageAfterHello)

    // With compression disabled, remote peer should be able to read the uncompressed message
    assert(remoteReadNotCompressedStatus.size == 1)
    assert(remoteReadNotCompressedStatus.head == Right(status))
  }

  it should "not compress messages even when both sides advertise p2p version larger or equal 5 (emergency fix)" in new TestSetup {
    // NOTE: Compression is currently DISABLED globally (emergency fix for FUKUII-COMPRESSION-001)
    val remoteHello: ByteString = remoteMessageCodec.encodeMessage(helloV5)
    messageCodec.readMessages(remoteHello)

    val localHello: ByteString = messageCodec.encodeMessage(helloV5)
    remoteMessageCodec.readMessages(localHello)

    val localNextMessageAfterHello: ByteString = messageCodec.encodeMessage(status)
    val remoteReadNextMessageAfterHello: Seq[Either[Throwable, Message]] =
      remoteMessageCodec.readMessages(localNextMessageAfterHello)

    // With compression disabled, both peers should communicate successfully
    assert(remoteReadNextMessageAfterHello.size == 1)
    assert(remoteReadNextMessageAfterHello.head == Right(status))
  }

  it should "send and receive uncompressed messages correctly when compression is disabled" in new TestSetup {
    // NOTE: Compression is currently DISABLED globally (emergency fix for FUKUII-COMPRESSION-001)
    val remoteHello: ByteString = remoteMessageCodec.encodeMessage(helloV5)
    messageCodec.readMessages(remoteHello)

    // Exchange hellos to establish connection
    val localHello: ByteString = messageCodec.encodeMessage(helloV5)
    remoteMessageCodec.readMessages(localHello)

    // After hello exchange, messages should be uncompressed
    val localStatus: ByteString = messageCodec.encodeMessage(status)
    val remoteReadStatus: Seq[Either[Throwable, Message]] =
      remoteMessageCodec.readMessages(localStatus)

    // Verify status message was sent uncompressed and decoded correctly
    assert(remoteReadStatus.size == 1)
    assert(remoteReadStatus.head == Right(status))
  }

  it should "correctly decompress messages even when compressed data starts with RLP-like bytes" taggedAs (
    UnitTest,
    NetworkTest
  ) in new TestSetup {
    // NOTE: This test is currently not applicable since compression is DISABLED globally
    // When compression is re-enabled, this test should verify the fix for the issue where
    // compressed data starting with bytes in the range 0x80-0xff was incorrectly treated as uncompressed RLP
    
    val remoteHello: ByteString = remoteMessageCodec.encodeMessage(helloV5)
    messageCodec.readMessages(remoteHello)

    val localHello: ByteString = messageCodec.encodeMessage(helloV5)
    remoteMessageCodec.readMessages(localHello)

    // Send a status message (will be uncompressed due to global disable)
    val localStatus: ByteString = messageCodec.encodeMessage(status)
    val remoteReadStatus: Seq[Either[Throwable, Message]] =
      remoteMessageCodec.readMessages(localStatus)

    // The message should be correctly decoded as uncompressed
    assert(remoteReadStatus.size == 1)
    assert(remoteReadStatus.head == Right(status))
  }

  it should "accept uncompressed messages from peers that advertise compression support (core-geth compatibility)" taggedAs (
    UnitTest,
    NetworkTest
  ) in new TestSetup {
    // This test simulates CoreGeth's unreliable compression behavior where it advertises p2pVersion=5
    // (compression enabled) but sometimes sends messages that fail decompression
    // See RUN008 fix and docs/reviews/COMPRESSION_FIX_WIRE_PROTOCOL.md
    // CoreGeth DOES compress (confirmed from source code) but the compressed data sometimes
    // fails to decompress correctly - likely due to Snappy library incompatibility
    
    // Both peers exchange v5 hellos, agreeing on compression
    val remoteHello: ByteString = remoteMessageCodec.encodeMessage(helloV5)
    messageCodec.readMessages(remoteHello)

    val localHello: ByteString = messageCodec.encodeMessage(helloV5)
    remoteMessageCodec.readMessages(localHello)

    // Now simulate CoreGeth sending an UNCOMPRESSED status message
    // even though compression was agreed upon
    // We manually create an uncompressed frame
    val statusBytes = status.toBytes
    val uncompressedFrame = Frame(
      Header(statusBytes.length, 0, None, None),
      Codes.StatusCode,
      ByteString(statusBytes)
    )
    val uncompressedFrameBytes = remoteFrameCodec.writeFrames(Seq(uncompressedFrame))
    
    // The messageCodec should accept this uncompressed message despite compression being enabled
    val decodedMessages = messageCodec.readMessages(uncompressedFrameBytes)
    
    // Should successfully decode the uncompressed status message
    assert(decodedMessages.size == 1)
    assert(decodedMessages.head == Right(status))
  }

  it should "NOT compress messages when sending to CoreGeth (Geth/) peer" taggedAs (
    UnitTest,
    NetworkTest
  ) in new TestSetup {
    // NOTE: Compression is currently DISABLED globally (emergency fix for FUKUII-COMPRESSION-001)
    // This test validates that messages are sent uncompressed to CoreGeth
    override lazy val remoteClientId: String = "Geth/v1.12.20-stable/linux-amd64/go1.21.10"
    override lazy val negotiatedRemoteP2PVersion: Long = 5L
    override lazy val negotiatedLocalP2PVersion: Long = 5L

    // Exchange hellos
    val remoteHello: ByteString = remoteMessageCodec.encodeMessage(helloV5.copy(clientId = remoteClientId))
    messageCodec.readMessages(remoteHello)

    val localHello: ByteString = messageCodec.encodeMessage(helloV5)
    remoteMessageCodec.readMessages(localHello)

    // Send status from local to remote (Geth peer)
    val localStatus: ByteString = messageCodec.encodeMessage(status)
    
    // The remote codec (simulating Geth) should be able to read the message without decompression
    // Create a temporary codec that doesn't expect compression
    val gethCodec = new MessageCodec(remoteFrameCodec, decoder, 4L, "TestClient/v1.0.0")
    val decodedMessages = gethCodec.readMessages(localStatus)
    
    // Should successfully decode - proves message was sent uncompressed
    assert(decodedMessages.size == 1)
    assert(decodedMessages.head == Right(status))
  }

  it should "NOT compress messages when sending to core-geth peer" taggedAs (
    UnitTest,
    NetworkTest
  ) in new TestSetup {
    // NOTE: Compression is currently DISABLED globally (emergency fix for FUKUII-COMPRESSION-001)
    override lazy val remoteClientId: String = "core-geth/v1.12.20/linux-amd64/go1.21"
    override lazy val negotiatedRemoteP2PVersion: Long = 5L
    
    // Create codec for core-geth peer
    val coreGethCodec = new MessageCodec(frameCodec, decoder, negotiatedRemoteP2PVersion, remoteClientId)
    
    // Send a message from local to core-geth
    val localStatus: ByteString = coreGethCodec.encodeMessage(status)
    
    // Verify message was sent uncompressed by trying to read it with a v4 codec
    val v4Codec = new MessageCodec(remoteFrameCodec, decoder, 4L, "TestClient/v1.0.0")
    val decodedMessages = v4Codec.readMessages(localStatus)
    
    assert(decodedMessages.size == 1)
    assert(decodedMessages.head == Right(status))
  }

  it should "NOT compress messages when sending to non-Geth peer with p2p v5 (global disable)" taggedAs (
    UnitTest,
    NetworkTest
  ) in new TestSetup {
    // NOTE: Compression is currently DISABLED globally (emergency fix for FUKUII-COMPRESSION-001)
    // Even non-Geth peers should receive uncompressed messages
    override lazy val remoteClientId: String = "fukuii/v1.0.0"
    override lazy val negotiatedRemoteP2PVersion: Long = 5L
    override lazy val negotiatedLocalP2PVersion: Long = 5L

    // Exchange hellos
    val remoteHello: ByteString = remoteMessageCodec.encodeMessage(helloV5.copy(clientId = remoteClientId))
    messageCodec.readMessages(remoteHello)

    val localHello: ByteString = messageCodec.encodeMessage(helloV5)
    remoteMessageCodec.readMessages(localHello)

    // Send status from local to remote (non-Geth peer)
    val localStatus: ByteString = messageCodec.encodeMessage(status)
    
    // Should work with v5 codec that the remote peer uses
    val decodedMessagesV5 = remoteMessageCodec.readMessages(localStatus)
    assert(decodedMessagesV5.size == 1)
    assert(decodedMessagesV5.head == Right(status))
  }

  it should "NOT compress messages for any client variant when compression is globally disabled" taggedAs (
    UnitTest,
    NetworkTest
  ) in new TestSetup {
    // NOTE: Compression is currently DISABLED globally (emergency fix for FUKUII-COMPRESSION-001)
    // This test validates that messages are sent uncompressed regardless of client ID
    // When compression is re-enabled, this test will validate case-insensitive client ID matching
    val clientVariants = Seq(
      "GETH/v1.0.0",
      "geth/v1.0.0", 
      "Geth/v1.0.0",
      "CORE-GETH/v1.0.0",
      "Core-Geth/v1.0.0",
      "CoreGeth/v1.0.0"
    )
    
    clientVariants.foreach { clientId =>
      val codec = new MessageCodec(frameCodec, decoder, 5L, clientId)
      val encodedStatus = codec.encodeMessage(status)
      
      // Should be readable by v4 codec since compression is globally disabled
      val v4Codec = new MessageCodec(remoteFrameCodec, decoder, 4L, "TestClient/v1.0.0")
      val decoded = v4Codec.readMessages(encodedStatus)
      
      assert(decoded.size == 1, s"Failed for client: $clientId")
      assert(decoded.head == Right(status), s"Failed for client: $clientId")
    }
  }

  trait TestSetup extends SecureChannelSetup {
    val frameCodec = new FrameCodec(secrets)
    val remoteFrameCodec = new FrameCodec(remoteSecrets)
    lazy val negotiatedRemoteP2PVersion: Long = 5L
    lazy val negotiatedLocalP2PVersion: Long = 5L

    val helloV5: Hello = Hello(
      p2pVersion = EtcHelloExchangeState.P2pVersion,
      clientId = Config.clientId,
      capabilities = Seq(Capability.ETH63),
      listenPort = 0, // Local node not listening
      nodeId = ByteString(1)
    )

    val helloV4: Hello = helloV5.copy(p2pVersion = 4)

    val status: Status = Status(
      protocolVersion = Capability.ETH63.version,
      networkId = Config.Network.peer.networkId,
      totalDifficulty = 1,
      bestHash = ByteString(1),
      genesisHash = ByteString(1)
    )

    val decoder: MessageDecoder =
      NetworkMessageDecoder.orElse(EthereumMessageDecoder.ethMessageDecoder(Capability.ETH63))

    // Each codec should be instantiated with the peer's p2p version (i.e. the version of the remote peer)
    // and the remote client ID
    lazy val remoteClientId: String = "TestClient/v1.0.0"
    lazy val localClientId: String = Config.clientId
    val messageCodec = new MessageCodec(frameCodec, decoder, negotiatedRemoteP2PVersion, remoteClientId)
    val remoteMessageCodec = new MessageCodec(remoteFrameCodec, decoder, negotiatedLocalP2PVersion, localClientId)

  }

}
