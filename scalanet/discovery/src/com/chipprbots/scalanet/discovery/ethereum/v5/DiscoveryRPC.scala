package com.chipprbots.scalanet.discovery.ethereum.v5

import cats.effect.IO
import com.chipprbots.scalanet.discovery.ethereum.{EthereumNodeRecord, Node}
import scodec.bits.ByteVector

/** RPC interface for Discovery v5 protocol operations
  * 
  * This trait defines the core RPC methods used in the Discovery v5 protocol.
  * Implementations handle the message exchange patterns for each operation.
  */
trait DiscoveryRPC[A] {
  
  /** Send a PING request and wait for PONG response
    * 
    * @param peer The peer to ping
    * @param localEnrSeq Our current ENR sequence number
    * @return The peer's ENR sequence number and observed address, or None if timeout/error
    */
  def ping(peer: A, localEnrSeq: Long): IO[Option[DiscoveryRPC.PingResult]]
  
  /** Send FINDNODE request and collect NODES responses
    * 
    * @param peer The peer to query
    * @param distances List of distances to query (0-256)
    * @return List of discovered node records, or None if timeout/error
    */
  def findNode(peer: A, distances: List[Int]): IO[Option[List[EthereumNodeRecord]]]
  
  /** Send TALKREQ and wait for TALKRESP
    * 
    * @param peer The peer to send to
    * @param protocol Protocol identifier
    * @param request Request payload
    * @return Response payload, or None if timeout/error
    */
  def talkRequest(peer: A, protocol: ByteVector, request: ByteVector): IO[Option[ByteVector]]
  
  /** Send REGTOPIC to register interest in a topic (optional feature)
    * 
    * @param peer The peer to register with
    * @param topic Topic hash
    * @param enr Our ENR
    * @param ticket Registration ticket
    * @return Ticket or confirmation, or None if timeout/error
    */
  def regTopic(
    peer: A, 
    topic: ByteVector, 
    enr: EthereumNodeRecord, 
    ticket: ByteVector
  ): IO[Option[DiscoveryRPC.RegTopicResult]]
  
  /** Query for nodes advertising a topic (optional feature)
    * 
    * @param peer The peer to query
    * @param topic Topic hash
    * @return List of nodes advertising the topic, or None if timeout/error
    */
  def topicQuery(peer: A, topic: ByteVector): IO[Option[List[EthereumNodeRecord]]]
}

object DiscoveryRPC {
  
  /** Result of a PING request */
  case class PingResult(
    enrSeq: Long,
    recipientIP: ByteVector,
    recipientPort: Int
  )
  
  /** Result of topic registration */
  sealed trait RegTopicResult
  
  /** Received a ticket for later use */
  case class TicketReceived(ticket: ByteVector, waitTime: Int) extends RegTopicResult
  
  /** Registration was confirmed */
  case class RegistrationConfirmed(topic: ByteVector) extends RegTopicResult
}
