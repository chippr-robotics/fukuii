package com.chipprbots.scalanet.discovery.ethereum.v5

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scodec.bits.ByteVector
import scala.util.Random

class PacketSpec extends AnyFlatSpec with Matchers {
  
  def randomBytes(n: Int): ByteVector = {
    val bytes = Array.ofDim[Byte](n)
    Random.nextBytes(bytes)
    ByteVector.view(bytes)
  }
  
  behavior of "Packet encoding/decoding"
  
  it should "encode and decode an OrdinaryMessagePacket" in {
    val nonce = randomBytes(Packet.NonceSize)
    val authData = randomBytes(32)
    val messageCipherText = randomBytes(100)
    
    val packet = Packet.OrdinaryMessagePacket(
      nonce = nonce,
      authDataSize = authData.size.toInt,
      authData = authData,
      messageCipherText = messageCipherText
    )
    
    val encoded = Packet.encodePacket(packet, Packet.Flag.OrdinaryMessage)
    encoded.isSuccessful shouldBe true
    
    val decoded = Packet.decodePacket(encoded.require)
    decoded.isSuccessful shouldBe true
    
    decoded.require.value match {
      case ord: Packet.OrdinaryMessagePacket =>
        ord.nonce shouldBe nonce
        ord.authData shouldBe authData
        ord.messageCipherText shouldBe messageCipherText
      case other =>
        fail(s"Expected OrdinaryMessagePacket, got $other")
    }
  }
  
  it should "encode and decode a WhoAreYouPacket" in {
    val nonce = randomBytes(Packet.NonceSize)
    val idNonce = randomBytes(16)
    val enrSeq = 42L
    
    val whoAreYouData = Packet.WhoAreYouData(idNonce, enrSeq)
    val authDataBytes = idNonce ++ ByteVector.fromLong(enrSeq, size = 8)
    
    val packet = Packet.WhoAreYouPacket(
      nonce = nonce,
      authDataSize = authDataBytes.size.toInt,
      whoAreYouData = whoAreYouData
    )
    
    val encoded = Packet.encodePacket(packet, Packet.Flag.WhoAreYou)
    encoded.isSuccessful shouldBe true
    
    val decoded = Packet.decodePacket(encoded.require)
    decoded.isSuccessful shouldBe true
    
    decoded.require.value match {
      case way: Packet.WhoAreYouPacket =>
        way.nonce shouldBe nonce
        way.whoAreYouData.idNonce shouldBe idNonce
        way.whoAreYouData.enrSeq shouldBe enrSeq
      case other =>
        fail(s"Expected WhoAreYouPacket, got $other")
    }
  }
  
  it should "encode and decode a HandshakeMessagePacket" in {
    val nonce = randomBytes(Packet.NonceSize)
    val srcId = randomBytes(32)
    val ephemPubkey = randomBytes(64)
    val idSignature = randomBytes(64)
    val sigSize = idSignature.size.toInt
    val messageCipherText = randomBytes(100)
    
    val handshakeData = Packet.HandshakeAuthData(
      srcId = srcId,
      sigSize = sigSize,
      ephemPubkey = ephemPubkey,
      idSignature = idSignature
    )
    
    val authDataBytes = srcId ++ ByteVector(sigSize.toByte) ++ ephemPubkey ++ idSignature
    
    val packet = Packet.HandshakeMessagePacket(
      nonce = nonce,
      authDataSize = authDataBytes.size.toInt,
      handshakeAuthData = handshakeData,
      messageCipherText = messageCipherText
    )
    
    val encoded = Packet.encodePacket(packet, Packet.Flag.HandshakeMessage)
    encoded.isSuccessful shouldBe true
    
    val decoded = Packet.decodePacket(encoded.require)
    decoded.isSuccessful shouldBe true
    
    decoded.require.value match {
      case hs: Packet.HandshakeMessagePacket =>
        hs.nonce shouldBe nonce
        hs.handshakeAuthData.srcId shouldBe srcId
        hs.handshakeAuthData.ephemPubkey shouldBe ephemPubkey
        hs.handshakeAuthData.idSignature shouldBe idSignature
        hs.messageCipherText shouldBe messageCipherText
      case other =>
        fail(s"Expected HandshakeMessagePacket, got $other")
    }
  }
  
  it should "fail to decode packet with invalid protocol ID" in {
    val invalidPacket = ByteVector(0x00, 0x00, 0x00, 0x00, 0x00, 0x00) ++ // Wrong protocol ID
                        Packet.Version ++
                        ByteVector(Packet.Flag.OrdinaryMessage) ++
                        randomBytes(Packet.NonceSize) ++
                        ByteVector.fromInt(0, size = 2)
    
    val decoded = Packet.decodePacket(invalidPacket.bits)
    decoded.isSuccessful shouldBe false
    decoded.toEither.left.get.message should include("Invalid protocol ID")
  }
  
  it should "fail to decode packet with unsupported version" in {
    val invalidPacket = Packet.ProtocolId ++
                        ByteVector(0xFF, 0xFF) ++ // Wrong version
                        ByteVector(Packet.Flag.OrdinaryMessage) ++
                        randomBytes(Packet.NonceSize) ++
                        ByteVector.fromInt(0, size = 2)
    
    val decoded = Packet.decodePacket(invalidPacket.bits)
    decoded.isSuccessful shouldBe false
    decoded.toEither.left.get.message should include("Unsupported version")
  }
  
  it should "reject packets exceeding maximum size" in {
    val nonce = randomBytes(Packet.NonceSize)
    val authData = randomBytes(32)
    val messageCipherText = randomBytes(Packet.MaxPacketSize) // Too large
    
    val packet = Packet.OrdinaryMessagePacket(
      nonce = nonce,
      authDataSize = authData.size.toInt,
      authData = authData,
      messageCipherText = messageCipherText
    )
    
    val encoded = Packet.encodePacket(packet, Packet.Flag.OrdinaryMessage)
    encoded.isSuccessful shouldBe false
    encoded.toEither.left.get.message should include("exceeds maximum")
  }
  
  it should "generate valid random nonces" in {
    val nonce1 = Packet.randomNonce
    val nonce2 = Packet.randomNonce
    
    nonce1.size shouldBe Packet.NonceSize
    nonce2.size shouldBe Packet.NonceSize
    nonce1 should not equal nonce2 // Very unlikely to be equal
  }
}
