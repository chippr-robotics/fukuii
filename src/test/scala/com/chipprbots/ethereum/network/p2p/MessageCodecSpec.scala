package com.chipprbots.ethereum.network.p2p

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.network.p2p.messages.BaseETH6XMessages.Status
import com.chipprbots.ethereum.network.p2p.messages.Capability
import com.chipprbots.ethereum.network.p2p.messages.Codes
import com.chipprbots.ethereum.network.rlpx.Frame
import com.chipprbots.ethereum.network.rlpx.FrameCodec
import com.chipprbots.ethereum.network.rlpx.Header
import com.chipprbots.ethereum.network.rlpx.MessageCodec
import com.chipprbots.ethereum.network.rlpx.MessageCodec.CompressionPolicy
import com.chipprbots.ethereum.testing.Tags._
import com.chipprbots.ethereum.utils.Config

class MessageCodecSpec extends AnyFlatSpec with Matchers {

  behavior of "MessageCodec Snappy negotiation"

  it should "compress messages when both peers advertise compression support" taggedAs (UnitTest, NetworkTest) in
    new TestSetup {
      enableInboundCompressionOnAllCodecs()
      val encodedStatus: ByteString = messageCodec.encodeMessage(status)

      val decoded = remoteMessageCodec.readMessages(encodedStatus)
      decoded should have size 1
      decoded.head shouldBe Right(status)
    }

  it should "skip compression when the remote peer is below the Snappy threshold" taggedAs (UnitTest, NetworkTest) in
    new TestSetup {
      override lazy val negotiatedRemoteP2PVersion: Long = 4L
      override lazy val remoteAdvertisedVersion: Int = 4

      val encodedStatus: ByteString = messageCodec.encodeMessage(status)
      val decoded = remoteMessageCodec.readMessages(encodedStatus)
      decoded should have size 1
      decoded.head shouldBe Right(status)
    }

  it should "skip compression when the local node advertises p2p v4 even if the peer supports Snappy" taggedAs
    (UnitTest, NetworkTest) in new TestSetup {
      override lazy val localAdvertisedVersion: Int = 4

      val encodedStatus: ByteString = messageCodec.encodeMessage(status)
      val decoded = remoteMessageCodec.readMessages(encodedStatus)
      decoded should have size 1
      decoded.head shouldBe Right(status)
    }

  it should "fall back to uncompressed frames when peers misbehave under compression" taggedAs
    (UnitTest, NetworkTest) in new TestSetup {
      enableInboundCompressionOnAllCodecs()
      val statusBytes = status.toBytes
      val uncompressedFrame = Frame(Header(statusBytes.length, 0, None, None), Codes.StatusCode, ByteString(statusBytes))
      val bytes = remoteFrameCodec.writeFrames(Seq(uncompressedFrame))

      val decoded = messageCodec.readMessages(bytes)
      decoded should have size 1
      decoded.head shouldBe Right(status)
    }

  trait TestSetup extends SecureChannelSetup {
    val frameCodec = new FrameCodec(secrets)
    val remoteFrameCodec = new FrameCodec(remoteSecrets)

    lazy val negotiatedRemoteP2PVersion: Long = 5L
    lazy val negotiatedLocalP2PVersion: Long = 5L
    lazy val localAdvertisedVersion: Int = 5
    lazy val remoteAdvertisedVersion: Int = 5

    val status: Status = Status(
      protocolVersion = Capability.ETH63.version,
      networkId = Config.Network.peer.networkId,
      totalDifficulty = 1,
      bestHash = ByteString(1),
      genesisHash = ByteString(1)
    )

    val decoder: MessageDecoder =
      NetworkMessageDecoder.orElse(EthereumMessageDecoder.ethMessageDecoder(Capability.ETH63))

    lazy val remoteClientId: String = "TestClient/v1.0.0"
    lazy val localClientId: String = Config.clientId

    protected def mkCodec(
        codec: FrameCodec,
        decoder: MessageDecoder,
        peerP2pVersion: Long,
        peerClientId: String,
        localAdvertisedP2pVersion: Int
    ): MessageCodec = {
      val policy = CompressionPolicy.fromHandshake(localAdvertisedP2pVersion, peerP2pVersion)
      new MessageCodec(codec, decoder, peerP2pVersion, peerClientId, policy)
    }

    lazy val messageCodec: MessageCodec =
      mkCodec(frameCodec, decoder, negotiatedRemoteP2PVersion, remoteClientId, localAdvertisedVersion)
    lazy val remoteMessageCodec: MessageCodec =
      mkCodec(remoteFrameCodec, decoder, negotiatedLocalP2PVersion, localClientId, remoteAdvertisedVersion)

    def enableInboundCompressionOnAllCodecs(): Unit = {
      messageCodec.enableInboundCompression("test-setup")
      remoteMessageCodec.enableInboundCompression("test-setup")
    }
  }

}
