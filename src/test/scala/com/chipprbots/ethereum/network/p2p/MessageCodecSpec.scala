package com.chipprbots.ethereum.network.p2p

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.network.handshaker.EtcHelloExchangeState
import com.chipprbots.ethereum.network.p2p.messages.BaseETH6XMessages.Status
import com.chipprbots.ethereum.network.p2p.messages.Capability
import com.chipprbots.ethereum.network.p2p.messages.WireProtocol.Hello
import com.chipprbots.ethereum.network.rlpx.FrameCodec
import com.chipprbots.ethereum.network.rlpx.MessageCodec
import com.chipprbots.ethereum.testing.Tags._
import com.chipprbots.ethereum.utils.Config

class MessageCodecSpec extends AnyFlatSpec with Matchers {

  it should "not compress messages when remote side advertises p2p version less than 5" taggedAs (UnitTest, NetworkTest) in new TestSetup {
    val remoteHello: ByteString = remoteMessageCodec.encodeMessage(helloV4)
    messageCodec.readMessages(remoteHello)

    val localNextMessageAfterHello: ByteString = messageCodec.encodeMessage(status)
    val remoteReadNotCompressedStatus: Seq[Either[Throwable, Message]] =
      remoteMessageCodec.readMessages(localNextMessageAfterHello)

    // remote peer did not receive local status so it treats all remote messages as uncompressed
    assert(remoteReadNotCompressedStatus.size == 1)
    assert(remoteReadNotCompressedStatus.head == Right(status))
  }

  it should "compress messages when remote side advertises p2p version larger or equal 5" taggedAs (UnitTest, NetworkTest) in new TestSetup {
    override lazy val negotiatedRemoteP2PVersion: Long = 5L
    override lazy val negotiatedLocalP2PVersion: Long = 3L

    val remoteHello: ByteString = remoteMessageCodec.encodeMessage(helloV5)
    messageCodec.readMessages(remoteHello)

    val localNextMessageAfterHello: ByteString = messageCodec.encodeMessage(status)
    val remoteReadNotCompressedStatus: Seq[Either[Throwable, Message]] =
      remoteMessageCodec.readMessages(localNextMessageAfterHello)

    // remote peer did not receive local hello so it treats all remote messages as uncompressed,
    // but local peer compresses messages when remote advertises p2p version >= 4
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
    // Hello is never compressed per spec, but Status will be compressed when both peers are v5+
    val localStatus: ByteString = messageCodec.encodeMessage(status)
    val remoteReadStatus: Seq[Either[Throwable, Message]] =
      remoteMessageCodec.readMessages(localStatus)

    // Verify status message was correctly compressed and decompressed
    assert(remoteReadStatus.size == 1)
    assert(remoteReadStatus.head == Right(status))
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
