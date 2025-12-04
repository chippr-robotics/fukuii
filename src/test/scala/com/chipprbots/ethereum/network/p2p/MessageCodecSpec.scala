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
    val remoteHello: ByteString = remoteMessageCodec.encodeMessage(helloV4)
    messageCodec.readMessages(remoteHello)

    val localNextMessageAfterHello: ByteString = messageCodec.encodeMessage(status)
    val remoteReadNotCompressedStatus: Seq[Either[Throwable, Message]] =
      remoteMessageCodec.readMessages(localNextMessageAfterHello)

    // remote peer did not receive local status so it treats all remote messages as uncompressed
    assert(remoteReadNotCompressedStatus.size == 1)
    assert(remoteReadNotCompressedStatus.head == Right(status))
  }

  it should "compress messages when remote side advertises p2p version larger or equal 5" taggedAs (
    UnitTest,
    NetworkTest
  ) in new TestSetup {
    override lazy val negotiatedRemoteP2PVersion: Long = 5L
    override lazy val negotiatedLocalP2PVersion: Long = 3L

    val remoteHello: ByteString = remoteMessageCodec.encodeMessage(helloV5)
    messageCodec.readMessages(remoteHello)

    val localNextMessageAfterHello: ByteString = messageCodec.encodeMessage(status)
    val remoteReadNotCompressedStatus: Seq[Either[Throwable, Message]] =
      remoteMessageCodec.readMessages(localNextMessageAfterHello)

    // remote peer did not receive local hello so it treats all remote messages as uncompressed,
    // but local peer compresses messages when remote advertises p2p version >= 5
    assert(remoteReadNotCompressedStatus.size == 1)
    assert(remoteReadNotCompressedStatus.head.isLeft)
  }

  it should "compress messages when both sides advertises p2p version larger or equal 5" in new TestSetup {
    val remoteHello: ByteString = remoteMessageCodec.encodeMessage(helloV5)
    messageCodec.readMessages(remoteHello)

    val localHello: ByteString = messageCodec.encodeMessage(helloV5)
    remoteMessageCodec.readMessages(localHello)

    val localNextMessageAfterHello: ByteString = messageCodec.encodeMessage(status)
    val remoteReadNextMessageAfterHello: Seq[Either[Throwable, Message]] =
      remoteMessageCodec.readMessages(localNextMessageAfterHello)

    // both peers exchanged v5 hellos, so they should send compressed messages
    assert(remoteReadNextMessageAfterHello.size == 1)
    assert(remoteReadNextMessageAfterHello.head == Right(status))
  }

  it should "compress and decompress messages correctly when both sides use p2p v5" in new TestSetup {
    val remoteHello: ByteString = remoteMessageCodec.encodeMessage(helloV5)
    messageCodec.readMessages(remoteHello)

    // Exchange hellos to establish connection
    val localHello: ByteString = messageCodec.encodeMessage(helloV5)
    remoteMessageCodec.readMessages(localHello)

    // After hello exchange, subsequent messages should be compressed/decompressed correctly
    // Hello is never compressed per spec, but Status and other messages will be compressed when both peers are v5+
    val localStatus: ByteString = messageCodec.encodeMessage(status)
    val remoteReadStatus: Seq[Either[Throwable, Message]] =
      remoteMessageCodec.readMessages(localStatus)

    // Verify status message was correctly compressed and decompressed
    assert(remoteReadStatus.size == 1)
    assert(remoteReadStatus.head == Right(status))
  }

  it should "correctly decompress messages even when compressed data starts with RLP-like bytes" taggedAs (
    UnitTest,
    NetworkTest
  ) in new TestSetup {
    // This test verifies the fix for the issue where compressed data starting with bytes
    // in the range 0x80-0xff (like 0x94) was incorrectly treated as uncompressed RLP
    
    val remoteHello: ByteString = remoteMessageCodec.encodeMessage(helloV5)
    messageCodec.readMessages(remoteHello)

    val localHello: ByteString = messageCodec.encodeMessage(helloV5)
    remoteMessageCodec.readMessages(localHello)

    // Send a status message which may compress to data starting with 0x80-0xff range bytes
    val localStatus: ByteString = messageCodec.encodeMessage(status)
    val remoteReadStatus: Seq[Either[Throwable, Message]] =
      remoteMessageCodec.readMessages(localStatus)

    // The message should be correctly decompressed regardless of what byte the compressed data starts with
    assert(remoteReadStatus.size == 1)
    assert(remoteReadStatus.head == Right(status))
  }

  it should "accept uncompressed messages from peers that advertise compression support (core-geth compatibility)" taggedAs (
    UnitTest,
    NetworkTest
  ) in new TestSetup {
    // This test simulates core-geth's protocol deviation where it advertises p2pVersion=5
    // (compression enabled) but sends uncompressed messages
    
    // Both peers exchange v5 hellos, agreeing on compression
    val remoteHello: ByteString = remoteMessageCodec.encodeMessage(helloV5)
    messageCodec.readMessages(remoteHello)

    val localHello: ByteString = messageCodec.encodeMessage(helloV5)
    remoteMessageCodec.readMessages(localHello)

    // Now simulate core-geth sending an UNCOMPRESSED status message
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
    val messageCodec = new MessageCodec(frameCodec, decoder, negotiatedRemoteP2PVersion)
    val remoteMessageCodec = new MessageCodec(remoteFrameCodec, decoder, negotiatedLocalP2PVersion)

  }

}
