package com.chipprbots.scalanet.discovery.ethereum

import java.net.Inet6Address
import java.nio.charset.StandardCharsets.UTF_8

import scala.collection.SortedMap
import scala.math.Ordering.Implicits._

import com.chipprbots.scalanet.discovery.crypto.PrivateKey
import com.chipprbots.scalanet.discovery.crypto.PublicKey
import com.chipprbots.scalanet.discovery.crypto.SigAlg
import com.chipprbots.scalanet.discovery.crypto.Signature
import scodec.Attempt
import scodec.Codec
import scodec.bits.ByteVector

/** ENR corresponding to https://github.com/ethereum/devp2p/blob/master/enr.md */
case class EthereumNodeRecord(
    // Signature over the record contents: [seq, k0, v0, k1, v1, ...]
    signature: Signature,
    content: EthereumNodeRecord.Content
)

object EthereumNodeRecord {

  implicit val byteVectorOrdering: Ordering[ByteVector] =
    Ordering.by[ByteVector, Seq[Byte]](_.toSeq)

  case class Content(
      // Nodes should increment this number whenever their properties change, like their address, and re-publish.
      seq: Long,
      // Normally clients treat the values as RLP, however we don't have access to the RLP types here, hence it's just bytes.
      attrs: SortedMap[ByteVector, ByteVector]
  )
  object Content {
    def apply(seq: Long, attrs: (ByteVector, ByteVector)*): Content =
      Content(seq, SortedMap(attrs: _*))
  }

  object Keys {
    def key(k: String): ByteVector =
      ByteVector(k.getBytes(UTF_8))

    /** name of identity scheme, e.g. "v4" */
    val id: ByteVector = key("id")

    /** compressed secp256k1 public key, 33 bytes */
    val secp256k1: ByteVector = key("secp256k1")

    /** IPv4 address, 4 bytes */
    val ip: ByteVector = key("ip")

    /** TCP port, big endian integer */
    val tcp: ByteVector = key("tcp")

    /** UDP port, big endian integer */
    val udp: ByteVector = key("udp")

    /** IPv6 address, 16 bytes */
    val ip6: ByteVector = key("ip6")

    /** IPv6-specific TCP port, big endian integer */
    val tcp6: ByteVector = key("tcp6")

    /** IPv6-specific UDP port, big endian integer */
    val udp6: ByteVector = key("udp6")

    /** The keys above have pre-defined meaning, but there can be arbitrary entries in the map. */
    val Predefined: Set[ByteVector] = Set(id, secp256k1, ip, tcp, udp, ip6, tcp6, udp6)
  }

  def apply(signature: Signature, seq: Long, attrs: (ByteVector, ByteVector)*): EthereumNodeRecord =
    EthereumNodeRecord(
      signature,
      EthereumNodeRecord.Content(seq, attrs: _*)
    )

  def apply(privateKey: PrivateKey, seq: Long, attrs: (ByteVector, ByteVector)*)(
      implicit sigalg: SigAlg,
      codec: Codec[Content]
  ): Attempt[EthereumNodeRecord] = {
    val content = EthereumNodeRecord.Content(seq, attrs: _*)
    codec.encode(content).map { data =>
      val sig = sigalg.removeRecoveryId(sigalg.sign(privateKey, data))
      EthereumNodeRecord(sig, content)
    }
  }

  /** Encode an unsigned 16-bit port as a canonical big-endian byte vector with no leading
    * zeros, per EIP-778's RLP rules (matches go-ethereum's `uint16` enr entry).
    *   - port == 0   → empty byte vector (RLP-canonical zero)
    *   - port < 256  → single byte
    *   - else        → exactly 2 bytes (big-endian)
    *
    * The legacy `ByteVector.fromInt(port)` form emitted 4 bytes with leading zeros, which
    * geth's strict RLP decoder rejects → falls back to default 0 → triggers errLowPort.
    */
  private def encodePort(port: Int): ByteVector =
    if (port == 0) ByteVector.empty
    else if (port < 256) ByteVector(port.toByte)
    else ByteVector.fromShort(port.toShort)

  def fromNode(node: Node, privateKey: PrivateKey, seq: Long, customAttrs: (ByteVector, ByteVector)*)(
      implicit sigalg: SigAlg,
      codec: Codec[Content]
  ): Attempt[EthereumNodeRecord] = {
    val (ipKey, tcpKey, udpKey) =
      if (node.address.ip.isInstanceOf[Inet6Address])
        (Keys.ip6, Keys.tcp6, Keys.udp6)
      else
        (Keys.ip, Keys.tcp, Keys.udp)

    val standardAttrs = List(
      Keys.id -> ByteVector("v4".getBytes(UTF_8)),
      Keys.secp256k1 -> sigalg.compressPublicKey(sigalg.toPublicKey(privateKey)).value.toByteVector,
      ipKey -> ByteVector(node.address.ip.getAddress),
      // Ports are RLP-canonical big-endian integers (EIP-778) — no leading zeros.
      // go-ethereum's `enr.UDP` / `enr.TCP` decode to `uint16` with strict RLP and
      // reject the 4-byte `ByteVector.fromInt` form (it sees leading zeros, fails
      // canonical-RLP, falls back to default 0, then `Node.UDP() <= 1024` triggers
      // `errLowPort` which makes geth/besu drop fukuii's NODES responses.
      // Closes #1221).
      tcpKey -> encodePort(node.address.tcpPort),
      udpKey -> encodePort(node.address.udpPort)
    )

    // Make sure a custom attribute doesn't overwrite a pre-defined one.
    val attrs = standardAttrs ++ customAttrs.filterNot(kv => Keys.Predefined(kv._1))

    apply(privateKey, seq, attrs: _*)
  }

  def validateSignature(
      enr: EthereumNodeRecord,
      publicKey: PublicKey
  )(implicit sigalg: SigAlg, codec: Codec[Content]): Attempt[Boolean] = {
    codec.encode(enr.content).map { data =>
      sigalg.verify(publicKey, enr.signature, data)
    }
  }
}
