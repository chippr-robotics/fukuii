package com.chipprbots.scalanet.discovery.ethereum.v4

import cats.Show

import com.chipprbots.scalanet.discovery.crypto.PrivateKey
import com.chipprbots.scalanet.discovery.crypto.PublicKey
import com.chipprbots.scalanet.discovery.crypto.SigAlg
import com.chipprbots.scalanet.discovery.crypto.Signature
import com.chipprbots.scalanet.discovery.hash.Hash
import com.chipprbots.scalanet.discovery.hash.Keccak256
import scodec.Attempt
import scodec.Codec
import scodec.DecodeResult
import scodec.Decoder
import scodec.Encoder
import scodec.Err
import scodec.bits.BitVector

/** Wire format from https://github.com/ethereum/devp2p/blob/master/discv4.md
  *
  * The packet type is included in the data.
  */
case class Packet(
    hash: Hash,
    signature: Signature,
    data: BitVector
)

object Packet:
  val MacBitsSize: Int = 32 * 8 // Keccak256
  val SigBitsSize: Int = 65 * 8 // Secp256k1
  val MaxPacketBitsSize: Int = 1280 * 8

  private def consumeNBits(context: String, size: Int) =
    Decoder[BitVector] { (bits: BitVector) =>
      bits.consumeThen(size)(
        _ => Attempt.failure(Err.InsufficientBits(size, bits.size, List(context))),
        (range, remainder) => Attempt.successful(DecodeResult(range, remainder))
      )
    }

  private val consumeRemainingBits =
    Decoder[BitVector] { (bits: BitVector) =>
      Attempt.successful(DecodeResult(bits, BitVector.empty))
    }

  private def packetDecoder(allowDecodeOverMaxPacketSize: Boolean): Decoder[Packet] =
    for
      _ <- Decoder { bits =>
        Attempt
          .guard(
            allowDecodeOverMaxPacketSize || bits.size <= MaxPacketBitsSize,
            "Packet to decode exceeds maximum size."
          )
          .map(_ => DecodeResult((), bits))
      }
      hash <- consumeNBits("Hash", MacBitsSize).map(Hash(_))
      signature <- consumeNBits("Signature", SigBitsSize).map(Signature(_))
      data <- consumeRemainingBits
    yield Packet(hash, signature, data)

  private val packetEncoder: Encoder[Packet] =
    Encoder[Packet] { (packet: Packet) =>
      for
        _ <- Attempt.guard(packet.hash.value.size == MacBitsSize, "Unexpected hash size.")
        _ <- Attempt.guard(packet.signature.value.size == SigBitsSize, "Unexpected signature size.")
        bits <- Attempt.successful {
          packet.hash.value ++ packet.signature.value ++ packet.data
        }
        _ <- Attempt.guard(bits.size <= MaxPacketBitsSize, "Encoded packet exceeded maximum size.")
      yield bits
    }

  /** discv4 packet-type bytes: the first byte of `data` (devp2p discv4.md, "Wire Protocol"). */
  object PacketType:
    val Ping: Byte = 0x01
    val Pong: Byte = 0x02
    val FindNode: Byte = 0x03
    val Neighbors: Byte = 0x04
    val ENRRequest: Byte = 0x05
    val ENRResponse: Byte = 0x06

    def isResponse(packetType: Byte): Boolean =
      packetType == Pong || packetType == Neighbors || packetType == ENRResponse

    def isRequest(packetType: Byte): Boolean =
      packetType == Ping || packetType == FindNode || packetType == ENRRequest

  /** The packet type, read without checking the hash or the signature. Only a pre-filter: it lets a receiver drop a
    * packet it would discard anyway before paying for key recovery (task #52). Anything it lets through is still fully
    * verified by [[unpack]].
    */
  def peekType(packet: Packet): Option[Byte] =
    if packet.data.size >= 8 then Some(packet.data.take(8).toByte()) else None

  /** [[peekType]] on the raw datagram (hash ‖ signature ‖ type ‖ RLP payload). */
  def peekType(datagram: BitVector): Option[Byte] =
    val offset = MacBitsSize.toLong + SigBitsSize
    if datagram.size >= offset + 8 then Some(datagram.slice(offset, offset + 8).toByte()) else None

  /** Create a codec for packets. Some Ethereum clients don't respect the size limits; for compatibility with them the
    * check during decode can be turned off.
    */
  def packetCodec(allowDecodeOverMaxPacketSize: Boolean): Codec[Packet] =
    Codec[Packet](packetEncoder, packetDecoder(allowDecodeOverMaxPacketSize))

  /** Serialize the payload, sign the data and compute the hash. */
  def pack(
      payload: Payload,
      privateKey: PrivateKey
  )(implicit codec: Codec[Payload], sigalg: SigAlg): Attempt[Packet] =
    for
      data <- codec.encode(payload)
      signature = sigalg.sign(privateKey, data)
      hash = Keccak256(signature.value ++ data)
    yield Packet(hash, signature, data)

  /** Validate the hash, recover the public key by validating the signature, and deserialize the payload. */
  def unpack(packet: Packet)(implicit codec: Codec[Payload], sigalg: SigAlg): Attempt[(Payload, PublicKey)] =
    for
      hash <- Attempt.successful(Keccak256(packet.signature.value ++ packet.data))
      _ <- Attempt.guard(hash == packet.hash, "Invalid hash.")
      publicKey <- sigalg.recoverPublicKey(packet.signature, packet.data)
      payload <- codec.decodeValue(packet.data)
    yield (payload, publicKey)

  implicit val show: Show[Packet] = Show.show[Packet] { p =>
    s"""Packet(hash = hex"${p.hash.value.toHex}", signature = hex"${p.signature.value.toHex}", data = hex"${p.data.toHex}")"""
  }
