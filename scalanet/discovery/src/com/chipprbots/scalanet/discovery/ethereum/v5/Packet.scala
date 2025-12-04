package com.chipprbots.scalanet.discovery.ethereum.v5

import cats.Show
import com.chipprbots.scalanet.discovery.hash.Hash
import scodec.{Attempt, Codec, DecodeResult, Decoder, Encoder, Err}
import scodec.bits.{BitVector, ByteVector}

/** Discovery v5 wire format from https://github.com/ethereum/devp2p/blob/master/discv5/discv5-wire.md
  *
  * Discovery v5 has two packet types:
  * 1. Ordinary packets: contain encrypted messages
  * 2. WHOAREYOU packets: challenge for handshake
  *
  * Packet structure:
  * - protocol-id: "discv5" magic constant (6 bytes)
  * - version: version number (2 bytes)
  * - flag: packet type indicator (1 byte)
  * - nonce: random data for IV (12 bytes)
  * - auth-data-size: size of auth-data (2 bytes)
  * - auth-data: authentication data (variable)
  * - message-cipher-text: encrypted message (variable)
  */
sealed trait Packet {
  def nonce: ByteVector
  def authDataSize: Int
}

object Packet {
  
  // Protocol magic bytes "discv5"
  val ProtocolId: ByteVector = ByteVector(0x64, 0x69, 0x73, 0x63, 0x76, 0x35)
  
  // Version 1
  val Version: ByteVector = ByteVector(0x00, 0x01)
  
  // Packet flags
  object Flag {
    val OrdinaryMessage: Byte = 0x00
    val WhoAreYou: Byte = 0x01
    val HandshakeMessage: Byte = 0x02
  }
  
  // Size constants (in bytes)
  val ProtocolIdSize = 6
  val VersionSize = 2
  val FlagSize = 1
  val NonceSize = 12
  val AuthDataSizeSize = 2
  val MaxPacketSize = 1280
  
  /** Ordinary message packet with encrypted message data */
  case class OrdinaryMessagePacket(
    nonce: ByteVector,
    authDataSize: Int,
    authData: ByteVector,
    messageCipherText: ByteVector
  ) extends Packet {
    require(nonce.size == NonceSize, s"Nonce must be $NonceSize bytes")
  }
  
  /** WHOAREYOU packet sent as challenge during handshake */
  case class WhoAreYouPacket(
    nonce: ByteVector,
    authDataSize: Int,
    whoAreYouData: WhoAreYouData
  ) extends Packet {
    require(nonce.size == NonceSize, s"Nonce must be $NonceSize bytes")
  }
  
  /** HandshakeMessage packet for establishing session */
  case class HandshakeMessagePacket(
    nonce: ByteVector,
    authDataSize: Int,
    handshakeAuthData: HandshakeAuthData,
    messageCipherText: ByteVector
  ) extends Packet {
    require(nonce.size == NonceSize, s"Nonce must be $NonceSize bytes")
  }
  
  /** WhoAreYou authentication data */
  case class WhoAreYouData(
    idNonce: ByteVector,  // 16 bytes
    enrSeq: Long          // 8 bytes
  ) {
    require(idNonce.size == 16, "idNonce must be 16 bytes")
  }
  
  /** Handshake authentication data */
  case class HandshakeAuthData(
    srcId: ByteVector,           // 32 bytes - sender's node ID
    sigSize: Int,                // 1 byte - size of id-signature
    ephemPubkey: ByteVector,     // 64 bytes - ephemeral public key
    idSignature: ByteVector      // variable - signature over id-nonce
  ) {
    require(srcId.size == 32, "srcId must be 32 bytes")
    require(ephemPubkey.size == 64, "ephemPubkey must be 64 bytes")
  }
  
  /** Generate a random nonce for packet */
  def randomNonce: ByteVector = {
    val random = new scala.util.Random()
    ByteVector.view(Array.fill(NonceSize)(random.nextInt(256).toByte))
  }
  
  /** Encode a packet to wire format */
  def encodePacket(packet: Packet, flag: Byte): Attempt[BitVector] = {
    // Build packet bytes manually
    var parts = List[ByteVector]()
    
    // Protocol ID
    parts = parts :+ ProtocolId
    
    // Version
    parts = parts :+ Version
    
    // Flag
    parts = parts :+ ByteVector(flag)
    
    // Nonce
    parts = parts :+ packet.nonce
    
    // Auth-data-size (big-endian)
    val authDataSizeBytes = ByteVector.fromInt(packet.authDataSize, size = 2)
    parts = parts :+ authDataSizeBytes
    
    // Auth-data - encode based on packet type
    val authDataBytes = packet match {
      case ord: OrdinaryMessagePacket =>
        ord.authData
      case way: WhoAreYouPacket =>
        way.whoAreYouData.idNonce ++ ByteVector.fromLong(way.whoAreYouData.enrSeq, size = 8)
      case hs: HandshakeMessagePacket =>
        hs.handshakeAuthData.srcId ++ 
        ByteVector(hs.handshakeAuthData.sigSize.toByte) ++ 
        hs.handshakeAuthData.ephemPubkey ++ 
        hs.handshakeAuthData.idSignature
    }
    parts = parts :+ authDataBytes
    
    // Message ciphertext (if present)
    packet match {
      case ord: OrdinaryMessagePacket =>
        parts = parts :+ ord.messageCipherText
      case hs: HandshakeMessagePacket =>
        parts = parts :+ hs.messageCipherText
      case _: WhoAreYouPacket =>
        // No message ciphertext for WHOAREYOU
    }
    
    val result = parts.foldLeft(ByteVector.empty)(_ ++ _)
    
    if (result.size > MaxPacketSize) {
      Attempt.failure(Err(s"Packet size ${result.size} exceeds maximum $MaxPacketSize"))
    } else {
      Attempt.successful(result.bits)
    }
  }
  
  /** Decode a packet from wire format */
  def decodePacket(bits: BitVector): Attempt[DecodeResult[Packet]] = {
    val bytes = bits.bytes
    
    if (bytes.size < ProtocolIdSize + VersionSize + FlagSize + NonceSize + AuthDataSizeSize) {
      return Attempt.failure(Err("Packet too small"))
    }
    
    var offset = 0
    
    // Check protocol ID
    val protocolId = bytes.slice(offset, offset + ProtocolIdSize)
    offset += ProtocolIdSize
    if (protocolId != ProtocolId) {
      return Attempt.failure(Err(s"Invalid protocol ID: ${protocolId.toHex}"))
    }
    
    // Check version
    val version = bytes.slice(offset, offset + VersionSize)
    offset += VersionSize
    if (version != Version) {
      return Attempt.failure(Err(s"Unsupported version: ${version.toHex}"))
    }
    
    // Get flag
    val flag = bytes(offset)
    offset += FlagSize
    
    // Get nonce
    val nonce = bytes.slice(offset, offset + NonceSize)
    offset += NonceSize
    
    // Get auth-data-size
    val authDataSize = bytes.slice(offset, offset + AuthDataSizeSize).toInt(signed = false)
    offset += AuthDataSizeSize
    
    // Get auth-data
    if (bytes.size < offset + authDataSize) {
      return Attempt.failure(Err(s"Packet too small for auth-data"))
    }
    val authData = bytes.slice(offset, offset + authDataSize)
    offset += authDataSize
    
    // Get message ciphertext (remaining bytes)
    val messageCipherText = bytes.drop(offset)
    
    // Parse based on flag
    flag match {
      case Flag.OrdinaryMessage =>
        val packet = OrdinaryMessagePacket(nonce, authDataSize, authData, messageCipherText)
        Attempt.successful(DecodeResult(packet, BitVector.empty))
        
      case Flag.WhoAreYou =>
        // Parse WhoAreYou data
        if (authData.size < 24) { // 16 bytes idNonce + 8 bytes enrSeq
          Attempt.failure(Err("Invalid WhoAreYou auth-data size"))
        } else {
          val idNonce = authData.take(16)
          val enrSeq = authData.slice(16, 24).toLong(signed = false)
          val whoAreYouData = WhoAreYouData(idNonce, enrSeq)
          val packet = WhoAreYouPacket(nonce, authDataSize, whoAreYouData)
          Attempt.successful(DecodeResult(packet, BitVector.empty))
        }
        
      case Flag.HandshakeMessage =>
        // Parse handshake auth data
        if (authData.size < 97) { // 32 srcId + 1 sigSize + 64 ephemPubkey
          Attempt.failure(Err("Invalid Handshake auth-data size"))
        } else {
          var authOffset = 0
          val srcId = authData.slice(authOffset, authOffset + 32)
          authOffset += 32
          val sigSize = authData(authOffset).toInt & 0xFF
          authOffset += 1
          val ephemPubkey = authData.slice(authOffset, authOffset + 64)
          authOffset += 64
          
          if (authData.size < authOffset + sigSize) {
            Attempt.failure(Err(s"Invalid Handshake auth-data: signature size mismatch"))
          } else {
            val idSignature = authData.slice(authOffset, authOffset + sigSize)
            val handshakeData = HandshakeAuthData(srcId, sigSize, ephemPubkey, idSignature)
            val packet = HandshakeMessagePacket(nonce, authDataSize, handshakeData, messageCipherText)
            Attempt.successful(DecodeResult(packet, BitVector.empty))
          }
        }
        
      case other =>
        Attempt.failure(Err(s"Unknown packet flag: $other"))
    }
  }
  
  implicit val show: Show[Packet] = Show.show[Packet] {
    case p: OrdinaryMessagePacket =>
      s"OrdinaryMessagePacket(nonce = ${p.nonce.toHex})"
    case p: WhoAreYouPacket =>
      s"WhoAreYouPacket(nonce = ${p.nonce.toHex}, idNonce = ${p.whoAreYouData.idNonce.toHex})"
    case p: HandshakeMessagePacket =>
      s"HandshakeMessagePacket(nonce = ${p.nonce.toHex}, srcId = ${p.handshakeAuthData.srcId.toHex})"
  }
}
