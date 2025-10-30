package com.chipprbots.scalanet.discovery.ethereum.codecs

import com.chipprbots.scalanet.discovery.hash.Hash
import com.chipprbots.scalanet.discovery.crypto.{PublicKey, Signature}
import com.chipprbots.scalanet.discovery.ethereum.{Node, EthereumNodeRecord}
import com.chipprbots.scalanet.discovery.ethereum.v4.Payload
import com.chipprbots.scalanet.discovery.ethereum.v4.Payload._
import scodec.Codec
import scodec.codecs.{discriminated, uint4, bits, list}
import scodec.bits.{BitVector, ByteVector}
import scala.collection.SortedMap
import scala.math.Ordering.Implicits._
import java.net.InetAddress

object DefaultCodecs {

  implicit val publicKeyCodec: Codec[PublicKey] =
    bits.xmap(PublicKey(_), (pk: PublicKey) => pk: BitVector)

  implicit val signatureCodec: Codec[Signature] =
    bits.xmap(Signature(_), (sig: Signature) => sig: BitVector)

  implicit val hashCodec: Codec[Hash] =
    bits.xmap(Hash(_), (h: Hash) => h: BitVector)

  implicit val inetAddressCodec: Codec[InetAddress] =
    bits.xmap(
      bv => InetAddress.getByAddress(bv.toByteArray),
      ip => BitVector(ip.getAddress)
    )

  // Note: deriveLabelledGeneric doesn't exist in scodec 2.x for Scala 3
  // These will need manual implementation or use of shapeless3-based derivation
  implicit val addressCodec: Codec[Node.Address] =
    ??? // TODO: Needs proper implementation for Scala 3

  implicit val nodeCodec: Codec[Node] =
    ??? // TODO: Needs proper implementation for Scala 3

  implicit def sortedMapCodec[K: Codec: Ordering, V: Codec]: Codec[SortedMap[K, V]] =
    list(Codec[(K, V)]).xmap(
      (kvs: List[(K, V)]) => SortedMap(kvs: _*),
      (sm: SortedMap[K, V]) => sm.toList
    )

  implicit val byteVectorOrdering: Ordering[ByteVector] =
    Ordering.by[ByteVector, Seq[Byte]](_.toSeq)

  implicit val attrCodec: Codec[SortedMap[ByteVector, ByteVector]] =
    sortedMapCodec[ByteVector, ByteVector]

  implicit val enrContentCodec: Codec[EthereumNodeRecord.Content] =
    ??? // TODO: Needs proper implementation for Scala 3

  implicit val enrCodec: Codec[EthereumNodeRecord] =
    ??? // TODO: Needs proper implementation for Scala 3

  implicit val pingCodec: Codec[Ping] =
    ??? // TODO: Needs proper implementation for Scala 3

  implicit val pongCodec: Codec[Pong] =
    ??? // TODO: Needs proper implementation for Scala 3

  implicit val findNodeCodec: Codec[FindNode] =
    ??? // TODO: Needs proper implementation for Scala 3

  implicit val neighborsCodec: Codec[Neighbors] =
    ??? // TODO: Needs proper implementation for Scala 3

  implicit val enrRequestCodec: Codec[ENRRequest] =
    ??? // TODO: Needs proper implementation for Scala 3

  implicit val enrResponseCodec: Codec[ENRResponse] =
    ??? // TODO: Needs proper implementation for Scala 3

  // Use discriminated builder pattern for Scala 3 scodec 2.x
  implicit val payloadCodec: Codec[Payload] =
    discriminated[Payload].by(uint4)
      .subcaseP(1) { case p: Ping => p }(pingCodec)
      .subcaseP(2) { case p: Pong => p }(pongCodec)
      .subcaseP(3) { case f: FindNode => f }(findNodeCodec)
      .subcaseP(4) { case n: Neighbors => n }(neighborsCodec)
      .subcaseP(5) { case e: ENRRequest => e }(enrRequestCodec)
      .subcaseP(6) { case e: ENRResponse => e }(enrResponseCodec)
}
