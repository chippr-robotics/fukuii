package com.chipprbots.scalanet.discovery.ethereum.v5

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.{IvParameterSpec, SecretKeySpec}

import scodec.{Attempt, Err}
import scodec.bits.ByteVector

/** Discovery v5 wire format per https://github.com/ethereum/devp2p/blob/master/discv5/discv5-wire.md
  *
  * Packet layout on the wire:
  * {{{
  *     +-----------------+        plaintext
  *     |   masking IV    |        16 bytes
  *     +-----------------+
  *     | static-header   |  \
  *     +-----------------+   |--- AES-CTR-masked using key = keccak256(destPubKey)[:16]
  *     |    auth-data    |  /     and IV = the masking IV above
  *     +-----------------+
  *     | message-data    |        plaintext (itself AES-GCM ciphertext for non-WHOAREYOU)
  *     +-----------------+
  * }}}
  *
  * Static header (23 bytes, big-endian):
  * {{{
  *     protocol-id     6 bytes  ASCII "discv5"
  *     version         2 bytes  0x0001
  *     flag            1 byte   0=Message, 1=Whoareyou, 2=Handshake
  *     nonce          12 bytes  AES-GCM nonce for the message-data (or random for WHOAREYOU random replies)
  *     auth-size       2 bytes  length of auth-data in bytes
  * }}}
  *
  * Auth-data layout depends on flag — see [[Packet.Header]] subtypes.
  *
  * Mask: AES-128-CTR. The destination node ID's first 16 bytes are the AES key.
  * The 16-byte IV at the front of the packet is the CTR start. Both encode and
  * decode call the same mask function (XOR is symmetric).
  */
sealed trait Packet {
  def header: Packet.Header
  def messageCiphertext: ByteVector
}

object Packet {

  // ---- Constants ----------------------------------------------------------

  val ProtocolId: ByteVector = ByteVector.view("discv5".getBytes("US-ASCII"))
  val Version: Int = 1
  val MaskingIVSize: Int = 16
  val ProtocolIdSize: Int = 6
  val VersionSize: Int = 2
  val FlagSize: Int = 1
  val NonceSize: Int = 12
  val AuthSizeSize: Int = 2
  val StaticHeaderSize: Int =
    ProtocolIdSize + VersionSize + FlagSize + NonceSize + AuthSizeSize // 23

  /** Smallest legal packet (per spec): 16 IV + 23 static + smallest auth +
    * smallest message-ct. Used to short-circuit decode of obvious junk. */
  val MinPacketSize: Int = 63

  val MaxPacketSize: Int = 1280

  object Flag {
    val Message: Byte = 0
    val Whoareyou: Byte = 1
    val Handshake: Byte = 2
  }

  /** Auth-data size for the [[Header.Message]] flag. */
  val MessageAuthSize: Int = 32 // srcId

  /** Auth-data size for the [[Header.Whoareyou]] flag. */
  val WhoareyouAuthSize: Int = 16 + 8 // idNonce + recordSeq

  // Indices into the static header (offsets in bytes).
  private val ProtocolIdEnd = ProtocolIdSize
  private val VersionEnd = ProtocolIdEnd + VersionSize
  private val FlagEnd = VersionEnd + FlagSize
  private val NonceEnd = FlagEnd + NonceSize
  private val AuthSizeEnd = NonceEnd + AuthSizeSize

  // ---- Header ADT ---------------------------------------------------------

  sealed trait Header {
    def iv: ByteVector
    def flag: Byte
    def nonce: ByteVector
    def authData: ByteVector
  }

  object Header {

    /** Ordinary message packet header. The message ciphertext (the AES-GCM
      * encrypted payload) lives outside the masked region. */
    final case class Message(
        iv: ByteVector,
        nonce: ByteVector,
        srcId: ByteVector
    ) extends Header {
      val flag: Byte = Flag.Message
      lazy val authData: ByteVector = srcId
    }

    /** WHOAREYOU challenge sent in response to an unknown-sender message.
      * Has no message ciphertext — the auth-data is the entire payload. */
    final case class Whoareyou(
        iv: ByteVector,
        nonce: ByteVector,
        idNonce: ByteVector,
        recordSeq: Long
    ) extends Header {
      val flag: Byte = Flag.Whoareyou
      lazy val authData: ByteVector =
        idNonce ++ ByteVector.fromLong(recordSeq, size = 8)
    }

    /** Handshake packet — first message after WHOAREYOU. Carries the ID-nonce
      * signature and the ephemeral public key the recipient needs to derive
      * the session keys. May optionally include an updated ENR record. */
    final case class Handshake(
        iv: ByteVector,
        nonce: ByteVector,
        srcId: ByteVector,
        idSignature: ByteVector,
        ephemeralPubkey: ByteVector,
        record: Option[ByteVector]
    ) extends Header {
      val flag: Byte = Flag.Handshake
      lazy val authData: ByteVector = {
        // Layout: srcId(32) + sigSize(1) + pubkeySize(1) + signature + pubkey + optional ENR
        val sigSize = idSignature.size.toByte
        val pubSize = ephemeralPubkey.size.toByte
        srcId ++
          ByteVector(sigSize, pubSize) ++
          idSignature ++
          ephemeralPubkey ++
          record.getOrElse(ByteVector.empty)
      }
    }
  }

  // ---- Concrete Packet types ---------------------------------------------

  final case class MessagePacket(
      header: Header.Message,
      messageCiphertext: ByteVector
  ) extends Packet

  final case class WhoareyouPacket(header: Header.Whoareyou) extends Packet {
    val messageCiphertext: ByteVector = ByteVector.empty
  }

  final case class HandshakePacket(
      header: Header.Handshake,
      messageCiphertext: ByteVector
  ) extends Packet

  // ---- Crypto helpers -----------------------------------------------------

  /** Apply the AES-128-CTR mask. Symmetric: same operation encodes and decodes.
    *
    * @param destNodeId the recipient's node ID (≥16 bytes; first 16 used as key)
    * @param iv         the 16-byte IV from the front of the packet
    * @param data       the bytes to mask/unmask
    */
  def aesCtrMask(destNodeId: ByteVector, iv: ByteVector, data: ByteVector): ByteVector = {
    require(destNodeId.size >= 16, s"destNodeId must be ≥16 bytes, got ${destNodeId.size}")
    require(iv.size == MaskingIVSize.toLong, s"iv must be $MaskingIVSize bytes, got ${iv.size}")
    val cipher = Cipher.getInstance("AES/CTR/NoPadding")
    val keySpec = new SecretKeySpec(destNodeId.take(MaskingIVSize.toLong).toArray, "AES")
    val ivSpec = new IvParameterSpec(iv.toArray)
    cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec)
    ByteVector.view(cipher.doFinal(data.toArray))
  }

  /** Generate a fresh random masking IV. */
  def randomIv: ByteVector = {
    val bytes = Array.ofDim[Byte](MaskingIVSize)
    new SecureRandom().nextBytes(bytes)
    ByteVector.view(bytes)
  }

  /** Generate a fresh random GCM nonce. */
  def randomNonce: ByteVector = {
    val bytes = Array.ofDim[Byte](NonceSize)
    new SecureRandom().nextBytes(bytes)
    ByteVector.view(bytes)
  }

  // ---- Encode -------------------------------------------------------------

  /** Encode a packet to wire bytes, applying the AES-CTR mask using the
    * destination's node ID. Caller is responsible for setting `header.iv` and
    * `header.nonce` as appropriate (use [[randomIv]] / [[randomNonce]] for
    * fresh values).
    *
    * For Message/Handshake packets, `messageCiphertext` should already be the
    * AES-GCM-encrypted payload — this function does NOT encrypt the message;
    * it only handles the outer mask. */
  def encode(packet: Packet, destNodeId: ByteVector): Attempt[ByteVector] = {
    val h = packet.header
    val authData = h.authData
    val authSize = authData.size

    if (authSize > 0xffff)
      return Attempt.failure(Err(s"auth-data size $authSize overflows uint16"))

    val staticHeader =
      ProtocolId ++
        ByteVector.fromInt(Version, size = VersionSize) ++
        ByteVector(h.flag) ++
        h.nonce ++
        ByteVector.fromInt(authSize.toInt, size = AuthSizeSize)

    if (staticHeader.size != StaticHeaderSize.toLong)
      return Attempt.failure(Err(s"BUG: static header size mismatch ${staticHeader.size}"))

    val masked = aesCtrMask(destNodeId, h.iv, staticHeader ++ authData)
    val full = h.iv ++ masked ++ packet.messageCiphertext

    if (full.size > MaxPacketSize)
      Attempt.failure(Err(s"packet exceeds max size: ${full.size} > $MaxPacketSize"))
    else
      Attempt.successful(full)
  }

  // ---- Decode -------------------------------------------------------------

  /** Decode a packet from wire bytes, unmasking using the local node ID.
    *
    * Note: success here means the static header parsed cleanly under the
    * mask. Validating the message ciphertext (AES-GCM auth-tag check) is the
    * caller's responsibility — they hold the session key. */
  def decode(bytes: ByteVector, localNodeId: ByteVector): Attempt[Packet] = {
    if (bytes.size < MinPacketSize.toLong)
      return Attempt.failure(Err(s"packet too short: ${bytes.size} < $MinPacketSize"))
    if (bytes.size > MaxPacketSize.toLong)
      return Attempt.failure(Err(s"packet too long: ${bytes.size} > $MaxPacketSize"))

    val iv = bytes.take(MaskingIVSize.toLong)
    val maskedRest = bytes.drop(MaskingIVSize.toLong)

    if (maskedRest.size < StaticHeaderSize.toLong)
      return Attempt.failure(Err(s"truncated before static header"))

    // First unmask just the static header (23 bytes) so we can read auth-size.
    val unmaskedStatic = aesCtrMask(localNodeId, iv, maskedRest.take(StaticHeaderSize.toLong))

    if (unmaskedStatic.take(ProtocolIdSize.toLong) != ProtocolId)
      return Attempt.failure(Err("invalid protocol id (mask mismatch or non-discv5 packet)"))

    val version =
      unmaskedStatic.slice(ProtocolIdEnd.toLong, VersionEnd.toLong).toInt(signed = false)
    if (version != Version)
      return Attempt.failure(Err(s"unsupported version: $version"))

    val flag = unmaskedStatic(VersionEnd.toLong)
    val nonce = unmaskedStatic.slice(FlagEnd.toLong, NonceEnd.toLong)
    val authSize =
      unmaskedStatic.slice(NonceEnd.toLong, AuthSizeEnd.toLong).toInt(signed = false)

    if (maskedRest.size < (StaticHeaderSize + authSize).toLong)
      return Attempt.failure(Err(s"truncated before auth-data: have ${maskedRest.size}, need ${StaticHeaderSize + authSize}"))

    // Unmask the full (static header + auth-data) region in one shot. CTR
    // keystream is deterministic per (key, iv), so the first 23 bytes here
    // match `unmaskedStatic` above — the redundancy keeps the code simple.
    val unmaskedHeaderAndAuth =
      aesCtrMask(localNodeId, iv, maskedRest.take((StaticHeaderSize + authSize).toLong))
    val authData = unmaskedHeaderAndAuth.drop(StaticHeaderSize.toLong)

    val messageCiphertext = maskedRest.drop((StaticHeaderSize + authSize).toLong)

    flag match {
      case Flag.Message =>
        if (authSize != MessageAuthSize)
          Attempt.failure(Err(s"message auth-size mismatch: $authSize != $MessageAuthSize"))
        else
          Attempt.successful(MessagePacket(Header.Message(iv, nonce, authData), messageCiphertext))

      case Flag.Whoareyou =>
        if (authSize != WhoareyouAuthSize)
          Attempt.failure(Err(s"whoareyou auth-size mismatch: $authSize != $WhoareyouAuthSize"))
        else
          Attempt.successful(
            WhoareyouPacket(
              Header.Whoareyou(
                iv = iv,
                nonce = nonce,
                idNonce = authData.take(16),
                recordSeq = authData.drop(16).toLong(signed = false)
              )
            )
          )

      case Flag.Handshake =>
        // Layout: srcId(32) + sigSize(1) + pubkeySize(1) + signature + pubkey + optional ENR
        val minHandshakeAuthSize = 32 + 1 + 1
        if (authSize < minHandshakeAuthSize)
          return Attempt.failure(Err(s"handshake auth-size too small: $authSize < $minHandshakeAuthSize"))

        val srcId = authData.take(32)
        val sigSize = authData(32).toInt & 0xff
        val pubkeySize = authData(33).toInt & 0xff
        val sigStart = 34L
        val pubStart = sigStart + sigSize
        val recordStart = pubStart + pubkeySize

        if (authSize < recordStart)
          Attempt.failure(Err(s"handshake auth-size $authSize < required ${recordStart}"))
        else {
          val signature = authData.slice(sigStart, pubStart)
          val pubkey = authData.slice(pubStart, recordStart)
          val record =
            if (recordStart < authSize.toLong) Some(authData.drop(recordStart)) else None
          Attempt.successful(
            HandshakePacket(
              Header.Handshake(iv, nonce, srcId, signature, pubkey, record),
              messageCiphertext
            )
          )
        }

      case other =>
        Attempt.failure(Err(s"invalid flag: ${other & 0xff}"))
    }
  }
}
