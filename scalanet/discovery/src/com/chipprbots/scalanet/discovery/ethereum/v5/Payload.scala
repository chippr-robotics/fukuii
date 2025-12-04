package com.chipprbots.scalanet.discovery.ethereum.v5

import com.chipprbots.scalanet.discovery.ethereum.EthereumNodeRecord
import scodec.bits.ByteVector
import java.security.SecureRandom

/** Discovery v5 protocol messages from https://github.com/ethereum/devp2p/blob/master/discv5/discv5-wire.md
  *
  * Message types in Discovery v5:
  * - PING (0x01): Liveness check
  * - PONG (0x02): Response to PING
  * - FINDNODE (0x03): Request for nodes
  * - NODES (0x04): Response containing node records
  * - TALKREQ (0x05): Application-level request
  * - TALKRESP (0x06): Response to TALKREQ
  * - REGTOPIC (0x07): Register interest in topic (optional)
  * - TICKET (0x08): Ticket for topic registration (optional)
  * - REGCONFIRMATION (0x09): Confirmation of topic registration (optional)
  * - TOPICQUERY (0x0A): Query for topic (optional)
  */
sealed trait Payload {
  def messageType: Byte
}

object Payload {
  sealed trait Request extends Payload
  sealed trait Response extends Payload
  
  /** PING message (0x01) - liveness check and ENR sequence update */
  case class Ping(
    requestId: ByteVector,  // 8 bytes - random request ID
    enrSeq: Long            // Sender's current ENR sequence number
  ) extends Request {
    override def messageType: Byte = MessageType.Ping
    require(requestId.size == 8, "requestId must be 8 bytes")
  }
  
  /** PONG message (0x02) - response to PING */
  case class Pong(
    requestId: ByteVector,  // 8 bytes - echo of request ID from PING
    enrSeq: Long,           // Sender's current ENR sequence number
    recipientIP: ByteVector, // Recipient's IP address as seen by sender
    recipientPort: Int      // Recipient's UDP port
  ) extends Response {
    override def messageType: Byte = MessageType.Pong
    require(requestId.size == 8, "requestId must be 8 bytes")
  }
  
  /** FINDNODE message (0x03) - request nodes at given distances */
  case class FindNode(
    requestId: ByteVector,  // 8 bytes - random request ID
    distances: List[Int]    // List of distances (0-256) to query
  ) extends Request {
    override def messageType: Byte = MessageType.FindNode
    require(requestId.size == 8, "requestId must be 8 bytes")
    require(distances.forall(d => d >= 0 && d <= 256), "distances must be in range [0, 256]")
  }
  
  /** NODES message (0x04) - response containing ENRs */
  case class Nodes(
    requestId: ByteVector,  // 8 bytes - echo of request ID from FINDNODE
    total: Int,             // Total number of NODES messages in response
    enrs: List[EthereumNodeRecord] // Node records
  ) extends Response {
    override def messageType: Byte = MessageType.Nodes
    require(requestId.size == 8, "requestId must be 8 bytes")
    require(total > 0, "total must be positive")
  }
  
  /** TALKREQ message (0x05) - application-level request */
  case class TalkRequest(
    requestId: ByteVector,  // 8 bytes - random request ID
    protocol: ByteVector,   // Protocol identifier
    request: ByteVector     // Protocol-specific request data
  ) extends Request {
    override def messageType: Byte = MessageType.TalkReq
    require(requestId.size == 8, "requestId must be 8 bytes")
  }
  
  /** TALKRESP message (0x06) - response to TALKREQ */
  case class TalkResponse(
    requestId: ByteVector,  // 8 bytes - echo of request ID from TALKREQ
    response: ByteVector    // Protocol-specific response data
  ) extends Response {
    override def messageType: Byte = MessageType.TalkResp
    require(requestId.size == 8, "requestId must be 8 bytes")
  }
  
  /** REGTOPIC message (0x07) - register interest in a topic (optional) */
  case class RegTopic(
    requestId: ByteVector,  // 8 bytes - random request ID
    topic: ByteVector,      // 32 bytes - topic hash
    enr: EthereumNodeRecord, // Sender's ENR
    ticket: ByteVector      // Registration ticket
  ) extends Request {
    override def messageType: Byte = MessageType.RegTopic
    require(requestId.size == 8, "requestId must be 8 bytes")
    require(topic.size == 32, "topic must be 32 bytes")
  }
  
  /** TICKET message (0x08) - ticket for topic registration (optional) */
  case class Ticket(
    requestId: ByteVector,  // 8 bytes - echo of request ID
    ticket: ByteVector,     // Ticket data
    waitTime: Int           // Wait time in seconds
  ) extends Response {
    override def messageType: Byte = MessageType.Ticket
    require(requestId.size == 8, "requestId must be 8 bytes")
  }
  
  /** REGCONFIRMATION message (0x09) - confirmation of topic registration (optional) */
  case class RegConfirmation(
    requestId: ByteVector,  // 8 bytes - echo of request ID
    topic: ByteVector       // 32 bytes - confirmed topic
  ) extends Response {
    override def messageType: Byte = MessageType.RegConfirmation
    require(requestId.size == 8, "requestId must be 8 bytes")
    require(topic.size == 32, "topic must be 32 bytes")
  }
  
  /** TOPICQUERY message (0x0A) - query for nodes registered to topic (optional) */
  case class TopicQuery(
    requestId: ByteVector,  // 8 bytes - random request ID
    topic: ByteVector       // 32 bytes - topic to query
  ) extends Request {
    override def messageType: Byte = MessageType.TopicQuery
    require(requestId.size == 8, "requestId must be 8 bytes")
    require(topic.size == 32, "topic must be 32 bytes")
  }
  
  /** Message type identifiers */
  object MessageType {
    val Ping: Byte = 0x01
    val Pong: Byte = 0x02
    val FindNode: Byte = 0x03
    val Nodes: Byte = 0x04
    val TalkReq: Byte = 0x05
    val TalkResp: Byte = 0x06
    val RegTopic: Byte = 0x07
    val Ticket: Byte = 0x08
    val RegConfirmation: Byte = 0x09
    val TopicQuery: Byte = 0x0A
    
    def isValid(msgType: Byte): Boolean = 
      msgType >= 0x01 && msgType <= 0x0A
  }
  
  /** Generate a random 8-byte request ID */
  def randomRequestId: ByteVector = {
    val bytes = Array.ofDim[Byte](8)
    val random = new SecureRandom()
    random.nextBytes(bytes)
    ByteVector.view(bytes)
  }
}
