package com.chipprbots.scalanet.discovery.ethereum.v5

import java.security.SecureRandom

import scodec.bits.ByteVector

import com.chipprbots.scalanet.discovery.ethereum.EthereumNodeRecord

/** Discovery v5 application-layer messages per discv5-wire.md.
  *
  * The wire form prepends a one-byte message-type discriminator followed by
  * the RLP-encoded fields. The actual RLP codec lives in fukuii main
  * (`com.chipprbots.ethereum.network.discovery.codecs.V5RLPCodecs`) so that
  * scalanet stays free of the fukuii RLP dependency, mirroring the v4 split.
  *
  * Topic-discovery messages (`regtopic`/`ticket`/`regconfirmation`/
  * `topicquery`) are explicitly OUT of scope: geth removed them from
  * production in v1.16.4 and the discv5 spec marks them "optional, ongoing
  * research". They can be added back when there's a real implementation;
  * stubs would just be dead code.
  */
sealed trait Payload {
  def messageType: Byte
  def requestId: ByteVector
}

object Payload {

  // The request-id is variable-length (1–8 bytes) per spec — *not* fixed 8.
  // Geth's framework rejects requests with reqId > 8 bytes with
  // ErrInvalidReqID; that's what makes hive's `PingLargeRequestID` test pass.
  val MaxRequestIdSize: Int = 8

  sealed trait Request extends Payload
  sealed trait Response extends Payload

  /** PING — liveness check + announce ENR sequence number. */
  final case class Ping(requestId: ByteVector, enrSeq: Long) extends Request {
    require(requestId.size <= MaxRequestIdSize.toLong, s"requestId must be ≤ $MaxRequestIdSize bytes")
    val messageType: Byte = MessageType.Ping
  }

  /** PONG — response to PING. `recipientIp` is raw 4 or 16 bytes (the spec
    * does not RLP-encode an outer length prefix on the IP — it's the IP's
    * own bytes), `recipientPort` is the sender's UDP port as we observed it. */
  final case class Pong(
      requestId: ByteVector,
      enrSeq: Long,
      recipientIp: ByteVector,
      recipientPort: Int
  ) extends Response {
    require(requestId.size <= MaxRequestIdSize.toLong, s"requestId must be ≤ $MaxRequestIdSize bytes")
    require(
      recipientIp.size == 4L || recipientIp.size == 16L,
      s"recipientIp must be 4 (IPv4) or 16 (IPv6) bytes, got ${recipientIp.size}"
    )
    val messageType: Byte = MessageType.Pong
  }

  /** FINDNODE — request ENRs at the given log-distances from our ID. */
  final case class FindNode(requestId: ByteVector, distances: List[Int]) extends Request {
    require(requestId.size <= MaxRequestIdSize.toLong, s"requestId must be ≤ $MaxRequestIdSize bytes")
    require(distances.forall(d => d >= 0 && d <= 256), "distances must be in [0, 256]")
    val messageType: Byte = MessageType.FindNode
  }

  /** NODES — paginated response carrying ENRs for FINDNODE. `total` is the
    * number of NODES messages this response will be split across; clients
    * collect that many before completing the request. */
  final case class Nodes(
      requestId: ByteVector,
      total: Int,
      enrs: List[EthereumNodeRecord]
  ) extends Response {
    require(requestId.size <= MaxRequestIdSize.toLong, s"requestId must be ≤ $MaxRequestIdSize bytes")
    require(total >= 1, "total must be ≥ 1")
    val messageType: Byte = MessageType.Nodes
  }

  /** TALKREQ — application-layer request piggybacked on discv5. */
  final case class TalkRequest(
      requestId: ByteVector,
      protocol: ByteVector,
      message: ByteVector
  ) extends Request {
    require(requestId.size <= MaxRequestIdSize.toLong, s"requestId must be ≤ $MaxRequestIdSize bytes")
    val messageType: Byte = MessageType.TalkReq
  }

  /** TALKRESP — response to TALKREQ. Empty `message` is a valid "I don't
    * support this protocol" reply per spec; that's also what hive's
    * `TalkRequest` test expects. */
  final case class TalkResponse(requestId: ByteVector, message: ByteVector) extends Response {
    require(requestId.size <= MaxRequestIdSize.toLong, s"requestId must be ≤ $MaxRequestIdSize bytes")
    val messageType: Byte = MessageType.TalkResp
  }

  // ---- Type tags ----------------------------------------------------------

  object MessageType {
    val Ping: Byte = 0x01
    val Pong: Byte = 0x02
    val FindNode: Byte = 0x03
    val Nodes: Byte = 0x04
    val TalkReq: Byte = 0x05
    val TalkResp: Byte = 0x06
  }

  // ---- Helpers ------------------------------------------------------------

  /** Generate a random request-id of the given size (1–8 bytes). */
  def randomRequestId(size: Int = MaxRequestIdSize): ByteVector = {
    require(size >= 1 && size <= MaxRequestIdSize, s"requestId size must be in [1, $MaxRequestIdSize]")
    val bytes = Array.ofDim[Byte](size)
    new SecureRandom().nextBytes(bytes)
    ByteVector.view(bytes)
  }
}
