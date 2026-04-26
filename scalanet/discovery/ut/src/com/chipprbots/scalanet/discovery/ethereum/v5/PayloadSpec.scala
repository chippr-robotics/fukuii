package com.chipprbots.scalanet.discovery.ethereum.v5

import scodec.bits.ByteVector

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Tests for [[Payload]] data classes — request-id length validation and
  * field shape. The actual RLP codec for these lives in fukuii main
  * (`com.chipprbots.ethereum.network.discovery.codecs.V5RLPCodecs`) and is
  * tested separately. */
class PayloadSpec extends AnyFlatSpec with Matchers {

  behavior.of("Payload.Ping")

  it should "accept request-ids of 1 to 8 bytes" in {
    for (size <- 1 to 8) {
      noException should be thrownBy Payload.Ping(ByteVector.low(size), 1L)
    }
  }

  it should "reject request-ids longer than 8 bytes" in {
    an[IllegalArgumentException] should be thrownBy Payload.Ping(ByteVector.low(9), 1L)
  }

  behavior.of("Payload.Pong")

  it should "accept IPv4 (4-byte) recipientIp" in {
    noException should be thrownBy Payload.Pong(
      requestId = ByteVector.low(4),
      enrSeq = 1L,
      recipientIp = ByteVector.fromValidHex("c0a80101"),
      recipientPort = 30303
    )
  }

  it should "accept IPv6 (16-byte) recipientIp" in {
    noException should be thrownBy Payload.Pong(
      requestId = ByteVector.low(4),
      enrSeq = 1L,
      recipientIp = ByteVector.low(16),
      recipientPort = 30303
    )
  }

  it should "reject other recipientIp lengths" in {
    an[IllegalArgumentException] should be thrownBy Payload.Pong(
      requestId = ByteVector.low(4),
      enrSeq = 1L,
      recipientIp = ByteVector.low(5),
      recipientPort = 30303
    )
  }

  behavior.of("Payload.FindNode")

  it should "accept distances in [0, 256]" in {
    noException should be thrownBy Payload.FindNode(ByteVector.low(4), List(0, 1, 256))
  }

  it should "reject negative or >256 distances" in {
    an[IllegalArgumentException] should be thrownBy Payload.FindNode(ByteVector.low(4), List(-1))
    an[IllegalArgumentException] should be thrownBy Payload.FindNode(ByteVector.low(4), List(257))
  }

  behavior.of("Payload.Nodes")

  it should "require total >= 1" in {
    an[IllegalArgumentException] should be thrownBy Payload.Nodes(ByteVector.low(4), 0, Nil)
  }

  behavior.of("Payload.MessageType")

  it should "have spec-correct discriminator bytes" in {
    Payload.MessageType.Ping shouldBe 0x01.toByte
    Payload.MessageType.Pong shouldBe 0x02.toByte
    Payload.MessageType.FindNode shouldBe 0x03.toByte
    Payload.MessageType.Nodes shouldBe 0x04.toByte
    Payload.MessageType.TalkReq shouldBe 0x05.toByte
    Payload.MessageType.TalkResp shouldBe 0x06.toByte
  }

  behavior.of("Payload.randomRequestId")

  it should "produce a request-id of the given size" in {
    Payload.randomRequestId(1).size shouldBe 1L
    Payload.randomRequestId(4).size shouldBe 4L
    Payload.randomRequestId(8).size shouldBe 8L
  }

  it should "reject sizes outside [1, 8]" in {
    an[IllegalArgumentException] should be thrownBy Payload.randomRequestId(0)
    an[IllegalArgumentException] should be thrownBy Payload.randomRequestId(9)
  }
}
