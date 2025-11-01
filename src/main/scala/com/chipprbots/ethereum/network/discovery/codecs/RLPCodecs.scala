package com.chipprbots.ethereum.network.discovery.codecs

import java.net.InetAddress

import scala.util.Try

import com.chipprbots.scalanet.discovery.crypto.PublicKey
import com.chipprbots.scalanet.discovery.crypto.Signature
import com.chipprbots.scalanet.discovery.ethereum.EthereumNodeRecord
import com.chipprbots.scalanet.discovery.ethereum.Node
import com.chipprbots.scalanet.discovery.ethereum.v4.Payload
import com.chipprbots.scalanet.discovery.hash.Hash
import scodec.Attempt
import scodec.Codec
import scodec.DecodeResult
import scodec.Err
import scodec.bits.BitVector
import scodec.bits.ByteVector

import com.chipprbots.ethereum.rlp
import com.chipprbots.ethereum.rlp.RLPCodec
import com.chipprbots.ethereum.rlp.RLPCodec.Ops
import com.chipprbots.ethereum.rlp.RLPEncodeable
import com.chipprbots.ethereum.rlp.RLPEncoder
import com.chipprbots.ethereum.rlp.RLPImplicitConversions.toEncodeable
import com.chipprbots.ethereum.rlp.RLPImplicitDerivations.{given, *}
import com.chipprbots.ethereum.rlp.RLPImplicits.{given, *}
import com.chipprbots.ethereum.rlp.RLPList

/** RLP codecs based on https://github.com/ethereum/devp2p/blob/master/discv4.md */
object RLPCodecs extends ContentCodecs with PayloadCodecs {
  given codecFromRLPCodec[T: RLPCodec]: Codec[T] =
    Codec[T](
      (value: T) => {
        val bytes = rlp.encode(value)
        Attempt.successful(BitVector(bytes))
      },
      (bits: BitVector) => {
        val tryDecode = Try(rlp.decode[T](bits.toByteArray))
        Attempt.fromTry(tryDecode.map(DecodeResult(_, BitVector.empty)))
      }
    )
}

trait ContentCodecs {
  given inetAddressRLPCodec: RLPCodec[InetAddress] =
    summon[RLPCodec[Array[Byte]]].xmap(InetAddress.getByAddress(_), _.getAddress)

  given bitVectorRLPCodec: RLPCodec[BitVector] =
    summon[RLPCodec[Array[Byte]]].xmap(BitVector(_), _.toByteArray)

  given byteVectorRLPCodec: RLPCodec[ByteVector] =
    summon[RLPCodec[Array[Byte]]].xmap(ByteVector(_), _.toArray)

  given hashRLPCodec: RLPCodec[Hash] =
    summon[RLPCodec[BitVector]].xmap(Hash(_), _.value)

  given publicKeyRLPCodec: RLPCodec[PublicKey] =
    summon[RLPCodec[BitVector]].xmap(PublicKey(_), _.value)

  given signatureRLPCodec: RLPCodec[Signature] =
    summon[RLPCodec[BitVector]].xmap(Signature(_), _.value)

  given nodeAddressRLPCodec: RLPCodec[Node.Address] = RLPCodec.instance[Node.Address](
    { case Node.Address(ip, udpPort, tcpPort) =>
      RLPList(
        RLPEncoder.encode(ip.getAddress),
        RLPEncoder.encode(udpPort),
        RLPEncoder.encode(tcpPort)
      )
    },
    { case RLPList(ipBytes, udpPort, tcpPort) =>
      Node.Address(
        InetAddress.getByAddress(ipBytes.decodeAs[Array[Byte]]("ip")),
        udpPort.decodeAs[Int]("udpPort"),
        tcpPort.decodeAs[Int]("tcpPort")
      )
    }
  )

  given nodeRLPCodec: RLPCodec[Node] =
    RLPCodec.instance[Node](
      { case Node(id, address) =>
        RLPEncoder.encode(address).asInstanceOf[RLPList] :+ id
      },
      {
        case RLPList(items @ _*) if items.length == 4 =>
          val address = RLPList(items.take(3): _*).decodeAs[Node.Address]("address")
          val id = items(3).decodeAs[PublicKey]("id")
          Node(id, address)
      }
    )

  // https://github.com/ethereum/devp2p/blob/master/enr.md#rlp-encoding
  // content = [seq, k, v, ...]
  implicit val enrContentRLPCodec: RLPCodec[EthereumNodeRecord.Content] = {
    // Differentiating by predefined keys is a workaround for the situation that
    // EthereumNodeRecord holds ByteVectors, not RLPEncodeable instances in its map,
    // but as per the spec the content can be anything (up to a total of 300 bytes).
    // We need to be able to preserve the fidelity of the encoding over a roundtrip
    // so that we can verify signatures, so we have to be able to put things in the
    // map as bytes and later be able to tell whether they were originally an
    // RLPValue on an RLPList.
    // For now treat all predefined keys as bytes and everything else as RLP.
    import EthereumNodeRecord.Keys.Predefined

    RLPCodec.instance(
      { case EthereumNodeRecord.Content(seq, attrs) =>
        val kvs = attrs
          .foldRight(RLPList()) { case ((key, value), kvs) =>
            val k: RLPEncodeable = key
            val v: RLPEncodeable = if (Predefined(key)) value else rlp.rawDecode(value.toArray)
            k +: v +: kvs
          }
        seq +: kvs
      },
      { case RLPList(seq, kvs @ _*) =>
        val attrs = kvs
          .grouped(2)
          .collect { case Seq(k, v) =>
            val key = k.decodeAs[ByteVector]("key")
            val keyString = Try(new String(key.toArray)).getOrElse(key.toString)
            val value =
              if (Predefined(key)) {
                v.decodeAs[ByteVector](s"value of key '${keyString}'")
              } else {
                ByteVector(rlp.encode(v))
              }
            key -> value
          }
          .toSeq

        EthereumNodeRecord.Content(
          seq.decodeAs[Long]("seq"),
          attrs: _*
        )
      }
    )
  }

  // record = [signature, seq, k, v, ...]
  implicit val enrRLPCodec: RLPCodec[EthereumNodeRecord] =
    RLPCodec.instance(
      { case EthereumNodeRecord(signature, content) =>
        val contentList = RLPEncoder.encode(content).asInstanceOf[RLPList]
        signature +: contentList
      },
      { case RLPList(signature, content @ _*) =>
        EthereumNodeRecord(
          signature.decodeAs[Signature]("signature"),
          RLPList(content: _*).decodeAs[EthereumNodeRecord.Content]("content")
        )
      }
    )
}

trait PayloadCodecs { self: ContentCodecs =>

  given payloadDerivationPolicy: DerivationPolicy =
    DerivationPolicy.default.copy(omitTrailingOptionals = true)

  given pingRLPCodec: RLPCodec[Payload.Ping] = RLPCodec.instance[Payload.Ping](
    { case Payload.Ping(version, from, to, expiration, enrSeq) =>
      val items = List(
        RLPEncoder.encode(version),
        RLPEncoder.encode(from),
        RLPEncoder.encode(to),
        RLPEncoder.encode(expiration)
      ) ++ enrSeq.toList.map(RLPEncoder.encode(_))
      RLPList(items: _*)
    },
    {
      case RLPList(version, from, to, expiration) =>
        Payload.Ping(
          version.decodeAs[Int]("version"),
          from.decodeAs[Node.Address]("from"),
          to.decodeAs[Node.Address]("to"),
          expiration.decodeAs[Long]("expiration"),
          None
        )
      case RLPList(version, from, to, expiration, enrSeq) =>
        Payload.Ping(
          version.decodeAs[Int]("version"),
          from.decodeAs[Node.Address]("from"),
          to.decodeAs[Node.Address]("to"),
          expiration.decodeAs[Long]("expiration"),
          Some(enrSeq.decodeAs[Long]("enrSeq"))
        )
    }
  )

  given pongRLPCodec: RLPCodec[Payload.Pong] = RLPCodec.instance[Payload.Pong](
    { case Payload.Pong(to, pingHash, expiration, enrSeq) =>
      val items = List(
        RLPEncoder.encode(to),
        RLPEncoder.encode(pingHash),
        RLPEncoder.encode(expiration)
      ) ++ enrSeq.toList.map(RLPEncoder.encode(_))
      RLPList(items: _*)
    },
    {
      case RLPList(to, pingHash, expiration) =>
        Payload.Pong(
          to.decodeAs[Node.Address]("to"),
          pingHash.decodeAs[Hash]("pingHash"),
          expiration.decodeAs[Long]("expiration"),
          None
        )
      case RLPList(to, pingHash, expiration, enrSeq) =>
        Payload.Pong(
          to.decodeAs[Node.Address]("to"),
          pingHash.decodeAs[Hash]("pingHash"),
          expiration.decodeAs[Long]("expiration"),
          Some(enrSeq.decodeAs[Long]("enrSeq"))
        )
    }
  )

  given findNodeRLPCodec: RLPCodec[Payload.FindNode] = RLPCodec.instance[Payload.FindNode](
    { case Payload.FindNode(target, expiration) =>
      RLPList(
        RLPEncoder.encode(target),
        RLPEncoder.encode(expiration)
      )
    },
    { case RLPList(target, expiration) =>
      Payload.FindNode(
        target.decodeAs[PublicKey]("target"),
        expiration.decodeAs[Long]("expiration")
      )
    }
  )

  given neighborsRLPCodec: RLPCodec[Payload.Neighbors] = RLPCodec.instance[Payload.Neighbors](
    { case Payload.Neighbors(nodes, expiration) =>
      RLPList(
        RLPEncoder.encode(nodes),
        RLPEncoder.encode(expiration)
      )
    },
    { case RLPList(nodes, expiration) =>
      Payload.Neighbors(
        nodes.decodeAs[List[Node]]("nodes"),
        expiration.decodeAs[Long]("expiration")
      )
    }
  )

  given enrRequestRLPCodec: RLPCodec[Payload.ENRRequest] = RLPCodec.instance[Payload.ENRRequest](
    { case Payload.ENRRequest(expiration) =>
      RLPList(RLPEncoder.encode(expiration))
    },
    { case RLPList(expiration) =>
      Payload.ENRRequest(expiration.decodeAs[Long]("expiration"))
    }
  )

  given enrResponseRLPCodec: RLPCodec[Payload.ENRResponse] = RLPCodec.instance[Payload.ENRResponse](
    { case Payload.ENRResponse(requestHash, enr) =>
      RLPList(
        RLPEncoder.encode(requestHash),
        RLPEncoder.encode(enr)
      )
    },
    { case RLPList(requestHash, enr) =>
      Payload.ENRResponse(
        requestHash.decodeAs[Hash]("requestHash"),
        enr.decodeAs[EthereumNodeRecord]("enr")
      )
    }
  )

  private object PacketType {
    val Ping: Byte = 0x01
    val Pong: Byte = 0x02
    val FindNode: Byte = 0x03
    val Neighbors: Byte = 0x04
    val ENRRequest: Byte = 0x05
    val ENRResponse: Byte = 0x06
  }

  given payloadCodec: Codec[Payload] =
    Codec[Payload](
      (payload: Payload) => {
        val (packetType, packetData) =
          payload match {
            case x: Payload.Ping        => PacketType.Ping -> rlp.encode(x)
            case x: Payload.Pong        => PacketType.Pong -> rlp.encode(x)
            case x: Payload.FindNode    => PacketType.FindNode -> rlp.encode(x)
            case x: Payload.Neighbors   => PacketType.Neighbors -> rlp.encode(x)
            case x: Payload.ENRRequest  => PacketType.ENRRequest -> rlp.encode(x)
            case x: Payload.ENRResponse => PacketType.ENRResponse -> rlp.encode(x)
          }

        Attempt.successful(BitVector(packetType.toByte +: packetData))
      },
      (bits: BitVector) =>
        bits.consumeThen(8)(
          err => Attempt.failure(Err(err)),
          (head, tail) => {
            val packetType: Byte = head.toByte()
            val packetData: Array[Byte] = tail.toByteArray

            val tryPayload: Try[Payload] = Try {
              packetType match {
                case PacketType.Ping        => rlp.decode[Payload.Ping](packetData)
                case PacketType.Pong        => rlp.decode[Payload.Pong](packetData)
                case PacketType.FindNode    => rlp.decode[Payload.FindNode](packetData)
                case PacketType.Neighbors   => rlp.decode[Payload.Neighbors](packetData)
                case PacketType.ENRRequest  => rlp.decode[Payload.ENRRequest](packetData)
                case PacketType.ENRResponse => rlp.decode[Payload.ENRResponse](packetData)
                case other                  => throw new RuntimeException(s"Unknown packet type: ${other}")
              }
            }

            Attempt.fromTry(tryPayload.map(DecodeResult(_, BitVector.empty)))
          }
        )
    )
}
