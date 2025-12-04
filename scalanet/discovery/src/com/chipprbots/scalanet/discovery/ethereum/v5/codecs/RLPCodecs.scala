package com.chipprbots.scalanet.discovery.ethereum.v5.codecs

import com.chipprbots.scalanet.discovery.ethereum.v5.Payload
import com.chipprbots.scalanet.discovery.ethereum.v5.Payload._
import com.chipprbots.scalanet.discovery.ethereum.EthereumNodeRecord
import scodec.Codec
import scodec.bits.ByteVector
import scodec.codecs._

/** Simple binary codecs for Discovery v5 messages
  * 
  * Note: Full RLP encoding will be handled at network integration layer
  * in the main fukuii module which has access to RLP libraries.
  * These codecs provide basic binary serialization for the scalanet module.
  * 
  * Based on https://github.com/ethereum/devp2p/blob/master/discv5/discv5-wire.md
  */
object RLPCodecs extends PayloadCodecs {}

trait PayloadCodecs {
  
  // Helper: variable-length ByteVector
  private def byteVectorCodec: Codec[ByteVector] = 
    variableSizeBytes(uint16, bytes)
  
  // Helper: fixed 8-byte requestId
  private def requestIdCodec: Codec[ByteVector] = 
    bytes(8)
  
  /** Codec for PING message (0x01) */
  given pingCodec: Codec[Ping] = {
    (requestIdCodec :: uint64).xmap(
      { case (reqId, enrSeq) => Ping(reqId, enrSeq.toLong) },
      (ping: Ping) => (ping.requestId, ping.enrSeq)
    )
  }
  
  /** Codec for PONG message (0x02) */
  given pongCodec: Codec[Pong] = {
    (requestIdCodec :: uint64 :: byteVectorCodec :: uint16).xmap(
      { case (reqId, enrSeq, ip, port) => Pong(reqId, enrSeq.toLong, ip, port) },
      (pong: Pong) => (pong.requestId, pong.enrSeq, pong.recipientIP, pong.recipientPort)
    )
  }
  
  /** Codec for FINDNODE message (0x03) */
  given findNodeCodec: Codec[FindNode] = {
    (requestIdCodec :: listOfN(uint8, uint16)).xmap(
      { case (reqId, distances) => FindNode(reqId, distances) },
      (fn: FindNode) => (fn.requestId, fn.distances)
    )
  }
  
  /** Codec for TALKREQ message (0x05) */
  given talkRequestCodec: Codec[TalkRequest] = {
    (requestIdCodec :: byteVectorCodec :: byteVectorCodec).xmap(
      { case (reqId, proto, req) => TalkRequest(reqId, proto, req) },
      (tr: TalkRequest) => (tr.requestId, tr.protocol, tr.request)
    )
  }
  
  /** Codec for TALKRESP message (0x06) */
  given talkResponseCodec: Codec[TalkResponse] = {
    (requestIdCodec :: byteVectorCodec).xmap(
      { case (reqId, resp) => TalkResponse(reqId, resp) },
      (tr: TalkResponse) => (tr.requestId, tr.response)
    )
  }
  
  /** Codec for TOPICQUERY message (0x0A) */
  given topicQueryCodec: Codec[TopicQuery] = {
    (requestIdCodec :: bytes(32)).xmap(
      { case (reqId, topic) => TopicQuery(reqId, topic) },
      (tq: TopicQuery) => (tq.requestId, tq.topic)
    )
  }
  
  /** Generic payload codec with message type discrimination */
  given payloadCodec: Codec[Payload] = discriminated[Payload]
    .by(uint8)
    .typecase(MessageType.Ping, pingCodec)
    .typecase(MessageType.Pong, pongCodec)
    .typecase(MessageType.FindNode, findNodeCodec)
    .typecase(MessageType.TalkReq, talkRequestCodec)
    .typecase(MessageType.TalkResp, talkResponseCodec)
    .typecase(MessageType.TopicQuery, topicQueryCodec)
    // Note: NODES, REGTOPIC, TICKET, REGCONFIRMATION require ENR codec
    // which depends on full RLP support in main module
}
