package com.chipprbots.scalanet.discovery.ethereum.codecs

import com.chipprbots.scalanet.discovery.hash.Hash
import com.chipprbots.scalanet.discovery.crypto.{PublicKey, Signature}
import com.chipprbots.scalanet.discovery.ethereum.{Node, EthereumNodeRecord}
import com.chipprbots.scalanet.discovery.ethereum.v4.Payload
import com.chipprbots.scalanet.discovery.ethereum.v4.Payload._
import scodec.Codec
import scodec.codecs.{discriminated, uint4, bits, list, uint16, uint64, int32, variableSizeBytes}
import scodec.bits.{BitVector, ByteVector}
import scala.collection.SortedMap
import scala.math.Ordering.Implicits._
import java.net.InetAddress

object DefaultCodecs {

  given publicKeyCodec: Codec[PublicKey] =
    bits.xmap(PublicKey(_), (pk: PublicKey) => pk.value)

  given signatureCodec: Codec[Signature] =
    bits.xmap(Signature(_), (sig: Signature) => sig.value)

  given hashCodec: Codec[Hash] =
    bits.xmap(Hash(_), (h: Hash) => h.value)

  given inetAddressCodec: Codec[InetAddress] =
    bits.xmap(
      bv => InetAddress.getByAddress(bv.toByteArray),
      ip => BitVector(ip.getAddress)
    )

  // Manual implementation for Node.Address
  given addressCodec: Codec[Node.Address] = {
    (inetAddressCodec :: int32 :: int32).xmap(
      { case (ip, udpPort, tcpPort) => Node.Address(ip, udpPort, tcpPort) },
      (addr: Node.Address) => (addr.ip, addr.udpPort, addr.tcpPort)
    )
  }

  // Manual implementation for Node
  given nodeCodec: Codec[Node] = {
    (publicKeyCodec :: addressCodec).xmap(
      { case (id, address) => Node(id, address) },
      (node: Node) => (node.id, node.address)
    )
  }

  given sortedMapCodec[K: Codec: Ordering, V: Codec]: Codec[SortedMap[K, V]] =
    list(Codec[(K, V)]).xmap(
      (kvs: List[(K, V)]) => SortedMap(kvs: _*),
      (sm: SortedMap[K, V]) => sm.toList
    )

  given byteVectorOrdering: Ordering[ByteVector] =
    Ordering.by[ByteVector, Seq[Byte]](_.toSeq)

  given attrCodec: Codec[SortedMap[ByteVector, ByteVector]] =
    sortedMapCodec[ByteVector, ByteVector]

  // ENR Content codec
  given enrContentCodec: Codec[EthereumNodeRecord.Content] = {
    val byteVectorCodec = variableSizeBytes(uint16, bits).xmap(
      (bv: BitVector) => ByteVector(bv.toByteArray),
      (bv: ByteVector) => BitVector(bv.toArray)
    )
    (uint64 :: sortedMapCodec[ByteVector, ByteVector](byteVectorCodec, byteVectorCodec, byteVectorOrdering)).xmap(
      { case (seq, attrs) => EthereumNodeRecord.Content(seq, attrs.toSeq: _*) },
      (content: EthereumNodeRecord.Content) => (content.seq, SortedMap(content.attrs: _*))
    )
  }

  // ENR codec
  given enrCodec: Codec[EthereumNodeRecord] = {
    (signatureCodec :: enrContentCodec).xmap(
      { case (signature, content) => EthereumNodeRecord(signature, content) },
      (enr: EthereumNodeRecord) => (enr.signature, enr.content)
    )
  }

  // Ping codec
  given pingCodec: Codec[Ping] = {
    val optionalLong = scodec.codecs.optional(scodec.codecs.provide(true), uint64)
    (int32 :: addressCodec :: addressCodec :: uint64 :: optionalLong).xmap(
      { case (version, from, to, expiration, enrSeq) => Ping(version, from, to, expiration, enrSeq) },
      (ping: Ping) => (ping.version, ping.from, ping.to, ping.expiration, ping.enrSeq)
    )
  }

  // Pong codec
  given pongCodec: Codec[Pong] = {
    val optionalLong = scodec.codecs.optional(scodec.codecs.provide(true), uint64)
    (addressCodec :: hashCodec :: uint64 :: optionalLong).xmap(
      { case (to, pingHash, expiration, enrSeq) => Pong(to, pingHash, expiration, enrSeq) },
      (pong: Pong) => (pong.to, pong.pingHash, pong.expiration, pong.enrSeq)
    )
  }

  // FindNode codec
  given findNodeCodec: Codec[FindNode] = {
    (publicKeyCodec :: uint64).xmap(
      { case (target, expiration) => FindNode(target, expiration) },
      (fn: FindNode) => (fn.target, fn.expiration)
    )
  }

  // Neighbors codec
  given neighborsCodec: Codec[Neighbors] = {
    (list(nodeCodec) :: uint64).xmap(
      { case (nodes, expiration) => Neighbors(nodes, expiration) },
      (n: Neighbors) => (n.nodes, n.expiration)
    )
  }

  // ENRRequest codec
  given enrRequestCodec: Codec[ENRRequest] = {
    uint64.xmap(
      (expiration: Long) => ENRRequest(expiration),
      (req: ENRRequest) => req.expiration
    )
  }

  // ENRResponse codec
  given enrResponseCodec: Codec[ENRResponse] = {
    (hashCodec :: enrCodec).xmap(
      { case (requestHash, enr) => ENRResponse(requestHash, enr) },
      (resp: ENRResponse) => (resp.requestHash, resp.enr)
    )
  }

  // Payload codec with discriminated union
  given payloadCodec: Codec[Payload] =
    discriminated[Payload].by(uint4)
      .subcaseP(1) { case p: Ping => p }(pingCodec)
      .subcaseP(2) { case p: Pong => p }(pongCodec)
      .subcaseP(3) { case f: FindNode => f }(findNodeCodec)
      .subcaseP(4) { case n: Neighbors => n }(neighborsCodec)
      .subcaseP(5) { case e: ENRRequest => e }(enrRequestCodec)
      .subcaseP(6) { case e: ENRResponse => e }(enrResponseCodec)
}
