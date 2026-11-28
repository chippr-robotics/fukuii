package com.chipprbots.scalanet.discovery.ethereum.codecs

import java.net.InetAddress

import scala.collection.SortedMap
import scala.math.Ordering.Implicits.*

import com.chipprbots.scalanet.discovery.crypto.PublicKey
import com.chipprbots.scalanet.discovery.crypto.Signature
import com.chipprbots.scalanet.discovery.ethereum.EthereumNodeRecord
import com.chipprbots.scalanet.discovery.ethereum.Node
import com.chipprbots.scalanet.discovery.ethereum.v4.Payload
import com.chipprbots.scalanet.discovery.ethereum.v4.Payload.*
import com.chipprbots.scalanet.discovery.hash.Hash
import scodec.Codec
import scodec.bits.BitVector
import scodec.bits.ByteVector
import scodec.codecs.bits
import scodec.codecs.discriminated
import scodec.codecs.fixedSizeBits
import scodec.codecs.int32
import scodec.codecs.list
import scodec.codecs.listOfN
import scodec.codecs.uint16
import scodec.codecs.uint4
import scodec.codecs.uint8
import scodec.codecs.uint64
import scodec.codecs.variableSizeBytes

object DefaultCodecs:

  // Every codec below is used in a position that is followed by more fields, so each one must be self-delimiting:
  // a bare `bits` is greedy on decode and swallows the rest of the buffer, leaving the next field with 0 bits.

  /** A discv4 node ID: the 64 byte uncompressed secp256k1 public key without the 0x04 prefix. */
  given publicKeyCodec: Codec[PublicKey] =
    fixedSizeBits(512L, bits).xmap(PublicKey(_), (pk: PublicKey) => pk.value)

  // Length-prefixed, since the size depends on the SigAlg (64 bytes in an ENR, 65 with the recovery ID in a packet).
  given signatureCodec: Codec[Signature] =
    variableSizeBytes(uint8, bits).xmap(Signature(_), (sig: Signature) => sig.value)

  /** Keccak256 hash, 32 bytes. */
  given hashCodec: Codec[Hash] =
    fixedSizeBits(256L, bits).xmap(Hash(_), (h: Hash) => h.value)

  // Length-prefixed: 4 bytes for IPv4, 16 for IPv6.
  given inetAddressCodec: Codec[InetAddress] =
    variableSizeBytes(uint8, bits).xmap(
      bv => InetAddress.getByAddress(bv.toByteArray),
      ip => BitVector(ip.getAddress)
    )

  // Manual implementation for Node.Address
  given addressCodec: Codec[Node.Address] =
    (inetAddressCodec :: int32 :: int32).xmap(
      { case (ip, udpPort, tcpPort) => Node.Address(ip, udpPort, tcpPort) },
      (addr: Node.Address) => (addr.ip, addr.udpPort, addr.tcpPort)
    )

  // Manual implementation for Node
  given nodeCodec: Codec[Node] =
    (publicKeyCodec :: addressCodec).xmap(
      { case (id, address) => Node(id, address) },
      (node: Node) => (node.id, node.address)
    )

  given sortedMapCodec[K: Codec: Ordering, V: Codec]: Codec[SortedMap[K, V]] =
    list(Codec[(K, V)]).xmap(
      (kvs: List[(K, V)]) => SortedMap(kvs*),
      (sm: SortedMap[K, V]) => sm.toList
    )

  given byteVectorOrdering: Ordering[ByteVector] =
    Ordering.by[ByteVector, Seq[Byte]](_.toSeq)

  given attrCodec: Codec[SortedMap[ByteVector, ByteVector]] =
    sortedMapCodec[ByteVector, ByteVector]

  // ENR Content codec
  given enrContentCodec: Codec[EthereumNodeRecord.Content] =
    given byteVectorCodec: Codec[ByteVector] = variableSizeBytes(uint16, bits).xmap(
      (bv: BitVector) => ByteVector(bv.toByteArray),
      (bv: ByteVector) => BitVector(bv.toArray)
    )
    (uint64 :: sortedMapCodec[ByteVector, ByteVector]).xmap(
      { case (seq, attrs) => EthereumNodeRecord.Content(seq.toLong, attrs) },
      (content: EthereumNodeRecord.Content) => (BigInt(content.seq), content.attrs)
    )

  // ENR codec
  given enrCodec: Codec[EthereumNodeRecord] =
    (signatureCodec :: enrContentCodec).xmap(
      { case (signature, content) => EthereumNodeRecord(signature, content) },
      (enr: EthereumNodeRecord) => (enr.signature, enr.content)
    )

  // Ping codec
  given pingCodec: Codec[Ping] =
    val optionalLong = scodec.codecs.optional(scodec.codecs.bool, uint64)
    (int32 :: addressCodec :: addressCodec :: uint64 :: optionalLong).xmap(
      { case (version, from, to, expiration, enrSeq) =>
        Ping(version, from, to, expiration.toLong, enrSeq.map(_.toLong))
      },
      (ping: Ping) => (ping.version, ping.from, ping.to, BigInt(ping.expiration), ping.enrSeq.map(BigInt(_)))
    )

  // Pong codec
  given pongCodec: Codec[Pong] =
    val optionalLong = scodec.codecs.optional(scodec.codecs.bool, uint64)
    (addressCodec :: hashCodec :: uint64 :: optionalLong).xmap(
      { case (to, pingHash, expiration, enrSeq) =>
        Pong(to, pingHash, expiration.toLong, enrSeq.map(_.toLong))
      },
      (pong: Pong) => (pong.to, pong.pingHash, BigInt(pong.expiration), pong.enrSeq.map(BigInt(_)))
    )

  // FindNode codec
  given findNodeCodec: Codec[FindNode] =
    (publicKeyCodec :: uint64).xmap(
      { case (target, expiration) => FindNode(target, expiration.toLong) },
      (fn: FindNode) => (fn.target, BigInt(fn.expiration))
    )

  // Neighbors codec
  given neighborsCodec: Codec[Neighbors] =
    (listOfN(uint8, nodeCodec) :: uint64).xmap(
      { case (nodes, expiration) => Neighbors(nodes, expiration.toLong) },
      (n: Neighbors) => (n.nodes, BigInt(n.expiration))
    )

  // ENRRequest codec
  given enrRequestCodec: Codec[ENRRequest] =
    uint64.xmap(
      (expiration: BigInt) => ENRRequest(expiration.toLong),
      (req: ENRRequest) => BigInt(req.expiration)
    )

  // ENRResponse codec
  given enrResponseCodec: Codec[ENRResponse] =
    (hashCodec :: enrCodec).xmap(
      { case (requestHash, enr) => ENRResponse(requestHash, enr) },
      (resp: ENRResponse) => (resp.requestHash, resp.enr)
    )

  // Payload codec with discriminated union
  given payloadCodec: Codec[Payload] =
    discriminated[Payload]
      .by(uint4)
      .subcaseP(1) { case p: Ping => p }(pingCodec)
      .subcaseP(2) { case p: Pong => p }(pongCodec)
      .subcaseP(3) { case f: FindNode => f }(findNodeCodec)
      .subcaseP(4) { case n: Neighbors => n }(neighborsCodec)
      .subcaseP(5) { case e: ENRRequest => e }(enrRequestCodec)
      .subcaseP(6) { case e: ENRResponse => e }(enrResponseCodec)
