package com.chipprbots.ethereum.network.discovery.codecs

import scala.util.Try

import com.chipprbots.scalanet.discovery.ethereum.EthereumNodeRecord
import com.chipprbots.scalanet.discovery.ethereum.v5.Payload

import scodec.{Attempt, Codec, DecodeResult, Err}
import scodec.bits.{BitVector, ByteVector}

import com.chipprbots.ethereum.rlp
import com.chipprbots.ethereum.rlp.RLPCodec
import com.chipprbots.ethereum.rlp.RLPCodec.Ops
import com.chipprbots.ethereum.rlp.RLPEncoder
import com.chipprbots.ethereum.rlp.RLPImplicitConversions.toEncodeable
import com.chipprbots.ethereum.rlp.RLPImplicitDerivations._
import com.chipprbots.ethereum.rlp.RLPImplicits.{*, given}
import com.chipprbots.ethereum.rlp.RLPList
import com.chipprbots.ethereum.rlp.RLPValue

/** RLP codecs for discv5 [[Payload]] types per
  * https://github.com/ethereum/devp2p/blob/master/discv5/discv5-wire.md#protocol-messages.
  *
  * The wire form is `messageType (1 byte) || RLP-encoded fields`. The `messageType` discriminator is one of: 0x01 PING,
  * 0x02 PONG, 0x03 FINDNODE, 0x04 NODES, 0x05 TALKREQ, 0x06 TALKRESP
  *
  * The codec lives in fukuii main rather than scalanet because RLP is part of the fukuii ethereum dependency tree —
  * same split as v4.
  */
object V5RLPCodecs extends V5ContentCodecs with V5PayloadCodecs {

  /** Adapter so we can summon `Codec[Payload]` from any `RLPCodec[Payload]`. */
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

trait V5ContentCodecs {

  /** RLP codec for a `ByteVector`. v5 packs `recipientIp` (raw 4 or 16 bytes) and `requestId` (raw 1–8 bytes) directly
    * as RLP byte strings.
    */
  given byteVectorRLPCodec: RLPCodec[ByteVector] =
    summon[RLPCodec[Array[Byte]]].xmap(ByteVector.view(_), _.toArray)

  /** Re-export the v4 ENR codec — discv5 uses the same RLP form. */
  given enrRLPCodec: RLPCodec[EthereumNodeRecord] =
    RLPCodecs.enrRLPCodec
}

trait V5PayloadCodecs { self: V5ContentCodecs =>

  given payloadDerivationPolicy: DerivationPolicy =
    DerivationPolicy.default.copy(omitTrailingOptionals = true)

  // --- Ping = [requestId, enrSeq] -------------------------------------------

  given pingRLPCodec: RLPCodec[Payload.Ping] = RLPCodec.instance[Payload.Ping](
    { case Payload.Ping(requestId, enrSeq) =>
      RLPList(
        RLPEncoder.encode(requestId),
        RLPEncoder.encode(enrSeq)
      )
    },
    {
      case RLPList(items @ _*) if items.length >= 2 =>
        val requestId = items(0).decodeAs[ByteVector]("requestId")
        // Reject reqId > 8 bytes per spec (geth ErrInvalidReqID).
        // The case-class `require` would also catch this, but failing in the
        // codec gives a cleaner error and lets the caller short-circuit.
        if (requestId.size > Payload.MaxRequestIdSize.toLong)
          throw new RuntimeException(s"requestId too long: ${requestId.size} > ${Payload.MaxRequestIdSize}")
        val enrSeq = items(1).decodeAs[Long]("enrSeq")
        Payload.Ping(requestId, enrSeq)
    }
  )

  // --- Pong = [requestId, enrSeq, recipientIp, recipientPort] ---------------

  given pongRLPCodec: RLPCodec[Payload.Pong] = RLPCodec.instance[Payload.Pong](
    { case Payload.Pong(requestId, enrSeq, recipientIp, recipientPort) =>
      RLPList(
        RLPEncoder.encode(requestId),
        RLPEncoder.encode(enrSeq),
        RLPEncoder.encode(recipientIp),
        RLPEncoder.encode(recipientPort)
      )
    },
    {
      case RLPList(items @ _*) if items.length >= 4 =>
        val requestId = items(0).decodeAs[ByteVector]("requestId")
        if (requestId.size > Payload.MaxRequestIdSize.toLong)
          throw new RuntimeException(s"requestId too long: ${requestId.size} > ${Payload.MaxRequestIdSize}")
        val enrSeq = items(1).decodeAs[Long]("enrSeq")
        val recipientIp = items(2).decodeAs[ByteVector]("recipientIp")
        val recipientPort = items(3).decodeAs[Int]("recipientPort")
        Payload.Pong(requestId, enrSeq, recipientIp, recipientPort)
    }
  )

  // --- FindNode = [requestId, [distance, ...]] -----------------------------

  given findNodeRLPCodec: RLPCodec[Payload.FindNode] = RLPCodec.instance[Payload.FindNode](
    { case Payload.FindNode(requestId, distances) =>
      RLPList(
        RLPEncoder.encode(requestId),
        RLPEncoder.encode(distances)
      )
    },
    {
      case RLPList(items @ _*) if items.length >= 2 =>
        val requestId = items(0).decodeAs[ByteVector]("requestId")
        if (requestId.size > Payload.MaxRequestIdSize.toLong)
          throw new RuntimeException(s"requestId too long: ${requestId.size} > ${Payload.MaxRequestIdSize}")
        val distances = items(1).decodeAs[List[Int]]("distances")
        Payload.FindNode(requestId, distances)
    }
  )

  // --- Nodes = [requestId, total, [enr, ...]] ------------------------------

  given nodesRLPCodec: RLPCodec[Payload.Nodes] = RLPCodec.instance[Payload.Nodes](
    { case Payload.Nodes(requestId, total, enrs) =>
      RLPList(
        RLPEncoder.encode(requestId),
        RLPEncoder.encode(total),
        RLPEncoder.encode(enrs)
      )
    },
    {
      case RLPList(items @ _*) if items.length >= 3 =>
        val requestId = items(0).decodeAs[ByteVector]("requestId")
        if (requestId.size > Payload.MaxRequestIdSize.toLong)
          throw new RuntimeException(s"requestId too long: ${requestId.size} > ${Payload.MaxRequestIdSize}")
        val total = items(1).decodeAs[Int]("total")
        val enrs = items(2).decodeAs[List[EthereumNodeRecord]]("enrs")
        Payload.Nodes(requestId, total, enrs)
    }
  )

  // --- TalkRequest = [requestId, protocol, message] ------------------------

  given talkRequestRLPCodec: RLPCodec[Payload.TalkRequest] = RLPCodec.instance[Payload.TalkRequest](
    { case Payload.TalkRequest(requestId, protocol, message) =>
      RLPList(
        RLPEncoder.encode(requestId),
        RLPEncoder.encode(protocol),
        RLPEncoder.encode(message)
      )
    },
    {
      case RLPList(items @ _*) if items.length >= 3 =>
        val requestId = items(0).decodeAs[ByteVector]("requestId")
        if (requestId.size > Payload.MaxRequestIdSize.toLong)
          throw new RuntimeException(s"requestId too long: ${requestId.size} > ${Payload.MaxRequestIdSize}")
        val protocol = items(1).decodeAs[ByteVector]("protocol")
        val message = items(2).decodeAs[ByteVector]("message")
        Payload.TalkRequest(requestId, protocol, message)
    }
  )

  // --- TalkResponse = [requestId, message] ---------------------------------

  given talkResponseRLPCodec: RLPCodec[Payload.TalkResponse] = RLPCodec.instance[Payload.TalkResponse](
    { case Payload.TalkResponse(requestId, message) =>
      RLPList(
        RLPEncoder.encode(requestId),
        RLPEncoder.encode(message)
      )
    },
    {
      case RLPList(items @ _*) if items.length >= 2 =>
        val requestId = items(0).decodeAs[ByteVector]("requestId")
        if (requestId.size > Payload.MaxRequestIdSize.toLong)
          throw new RuntimeException(s"requestId too long: ${requestId.size} > ${Payload.MaxRequestIdSize}")
        val message = items(1).decodeAs[ByteVector]("message")
        Payload.TalkResponse(requestId, message)
    }
  )

  // --- Top-level discriminator ---------------------------------------------

  /** Encode/decode `Payload` with the leading message-type byte. The codec is the bridge between fukuii main (where
    * this lives) and scalanet (where Payload itself is defined).
    */
  given payloadCodec: Codec[Payload] = Codec[Payload](
    (payload: Payload) => {
      val (msgType, body) = payload match {
        case x: Payload.Ping         => Payload.MessageType.Ping -> rlp.encode(x)
        case x: Payload.Pong         => Payload.MessageType.Pong -> rlp.encode(x)
        case x: Payload.FindNode     => Payload.MessageType.FindNode -> rlp.encode(x)
        case x: Payload.Nodes        => Payload.MessageType.Nodes -> rlp.encode(x)
        case x: Payload.TalkRequest  => Payload.MessageType.TalkReq -> rlp.encode(x)
        case x: Payload.TalkResponse => Payload.MessageType.TalkResp -> rlp.encode(x)
      }
      Attempt.successful(BitVector(msgType +: body))
    },
    (bits: BitVector) =>
      bits.consumeThen(8)(
        err => Attempt.failure(Err(err)),
        (head, tail) => {
          val msgType: Byte = head.toByte()
          val body: Array[Byte] = tail.toByteArray

          val attempt: Try[Payload] = Try {
            msgType match {
              case Payload.MessageType.Ping     => rlp.decode[Payload.Ping](body)
              case Payload.MessageType.Pong     => rlp.decode[Payload.Pong](body)
              case Payload.MessageType.FindNode => rlp.decode[Payload.FindNode](body)
              case Payload.MessageType.Nodes    => rlp.decode[Payload.Nodes](body)
              case Payload.MessageType.TalkReq  => rlp.decode[Payload.TalkRequest](body)
              case Payload.MessageType.TalkResp => rlp.decode[Payload.TalkResponse](body)
              case other =>
                throw new RuntimeException(s"unknown discv5 message type: 0x${(other & 0xff).toHexString}")
            }
          }
          Attempt.fromTry(attempt.map(DecodeResult(_, BitVector.empty)))
        }
      )
  )
}
