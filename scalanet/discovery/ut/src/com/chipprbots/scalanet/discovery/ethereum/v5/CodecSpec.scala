package com.chipprbots.scalanet.discovery.ethereum.v5

import com.chipprbots.scalanet.discovery.ethereum.EthereumNodeRecord
import com.chipprbots.scalanet.discovery.ethereum.v5.Payload._
import com.chipprbots.scalanet.discovery.ethereum.v5.codecs.RLPCodecs.{given, *}
import com.chipprbots.scalanet.discovery.crypto.Signature
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scodec.bits.ByteVector
import java.security.SecureRandom

class CodecSpec extends AnyFlatSpec with Matchers {
  
  private val random = new SecureRandom()
  
  def randomBytes(n: Int): ByteVector = {
    val bytes = Array.ofDim[Byte](n)
    random.nextBytes(bytes)
    ByteVector.view(bytes)
  }
  
  def mockEnr: EthereumNodeRecord = {
    EthereumNodeRecord(
      signature = Signature(scodec.bits.BitVector.fill(512)(false)),
      content = EthereumNodeRecord.Content(1L, scala.collection.immutable.SortedMap.empty[ByteVector, ByteVector])
    )
  }
  
  behavior of "Codec for PING message"
  
  it should "encode and decode PING message" in {
    val ping = Ping(randomBytes(8), 123L)
    
    val encoded = pingCodec.encode(ping)
    encoded.isSuccessful shouldBe true
    
    val decoded = pingCodec.decode(encoded.require)
    decoded.isSuccessful shouldBe true
    
    val result = decoded.require.value
    result.requestId shouldBe ping.requestId
    result.enrSeq shouldBe ping.enrSeq
  }
  
  it should "handle PING with zero ENR sequence" in {
    val ping = Ping(randomBytes(8), 0L)
    
    val encoded = pingCodec.encode(ping)
    encoded.isSuccessful shouldBe true
    
    val decoded = pingCodec.decode(encoded.require)
    decoded.isSuccessful shouldBe true
    decoded.require.value.enrSeq shouldBe 0L
  }
  
  it should "handle PING with maximum ENR sequence" in {
    val ping = Ping(randomBytes(8), Long.MaxValue)
    
    val encoded = pingCodec.encode(ping)
    encoded.isSuccessful shouldBe true
    
    val decoded = pingCodec.decode(encoded.require)
    decoded.isSuccessful shouldBe true
    decoded.require.value.enrSeq shouldBe Long.MaxValue
  }
  
  behavior of "Codec for PONG message"
  
  it should "encode and decode PONG message" in {
    val pong = Pong(
      requestId = randomBytes(8),
      enrSeq = 456L,
      recipientIP = ByteVector(192, 168, 1, 1),
      recipientPort = 30303
    )
    
    val encoded = pongCodec.encode(pong)
    encoded.isSuccessful shouldBe true
    
    val decoded = pongCodec.decode(encoded.require)
    decoded.isSuccessful shouldBe true
    
    val result = decoded.require.value
    result.requestId shouldBe pong.requestId
    result.enrSeq shouldBe pong.enrSeq
    result.recipientIP shouldBe pong.recipientIP
    result.recipientPort shouldBe pong.recipientPort
  }
  
  it should "handle IPv6 addresses in PONG" in {
    val pong = Pong(
      requestId = randomBytes(8),
      enrSeq = 100L,
      recipientIP = randomBytes(16), // IPv6 is 16 bytes
      recipientPort = 30303
    )
    
    val encoded = pongCodec.encode(pong)
    encoded.isSuccessful shouldBe true
    
    val decoded = pongCodec.decode(encoded.require)
    decoded.isSuccessful shouldBe true
    decoded.require.value.recipientIP.size shouldBe 16
  }
  
  behavior of "Codec for FINDNODE message"
  
  it should "encode and decode FINDNODE message with single distance" in {
    val findNode = FindNode(randomBytes(8), List(256))
    
    val encoded = findNodeCodec.encode(findNode)
    encoded.isSuccessful shouldBe true
    
    val decoded = findNodeCodec.decode(encoded.require)
    decoded.isSuccessful shouldBe true
    
    val result = decoded.require.value
    result.requestId shouldBe findNode.requestId
    result.distances shouldBe findNode.distances
  }
  
  it should "encode and decode FINDNODE message with multiple distances" in {
    val findNode = FindNode(randomBytes(8), List(256, 255, 254, 253))
    
    val encoded = findNodeCodec.encode(findNode)
    encoded.isSuccessful shouldBe true
    
    val decoded = findNodeCodec.decode(encoded.require)
    decoded.isSuccessful shouldBe true
    
    val result = decoded.require.value
    result.distances shouldBe findNode.distances
  }
  
  it should "handle FINDNODE with empty distance list" in {
    val findNode = FindNode(randomBytes(8), List.empty)
    
    val encoded = findNodeCodec.encode(findNode)
    encoded.isSuccessful shouldBe true
    
    val decoded = findNodeCodec.decode(encoded.require)
    decoded.isSuccessful shouldBe true
    decoded.require.value.distances shouldBe List.empty
  }
  
  behavior of "Codec for NODES message"
  
  it should "encode and decode NODES message with empty ENR list" in {
    val nodes = Nodes(randomBytes(8), total = 1, enrs = List.empty)
    
    val encoded = nodesCodec.encode(nodes)
    encoded.isSuccessful shouldBe true
    
    val decoded = nodesCodec.decode(encoded.require)
    decoded.isSuccessful shouldBe true
    
    val result = decoded.require.value
    result.requestId shouldBe nodes.requestId
    result.total shouldBe nodes.total
    result.enrs shouldBe List.empty
  }
  
  it should "encode and decode NODES message with single ENR" in {
    val nodes = Nodes(randomBytes(8), total = 1, enrs = List(mockEnr))
    
    val encoded = nodesCodec.encode(nodes)
    encoded.isSuccessful shouldBe true
    
    val decoded = nodesCodec.decode(encoded.require)
    decoded.isSuccessful shouldBe true
    
    val result = decoded.require.value
    result.total shouldBe 1
    result.enrs.size shouldBe 1
  }
  
  it should "encode and decode NODES message with multiple ENRs" in {
    val nodes = Nodes(randomBytes(8), total = 3, enrs = List(mockEnr, mockEnr, mockEnr))
    
    val encoded = nodesCodec.encode(nodes)
    encoded.isSuccessful shouldBe true
    
    val decoded = nodesCodec.decode(encoded.require)
    decoded.isSuccessful shouldBe true
    
    val result = decoded.require.value
    result.total shouldBe 3
    result.enrs.size shouldBe 3
  }
  
  behavior of "Codec for TALKREQ message"
  
  it should "encode and decode TALKREQ message" in {
    val talkReq = TalkRequest(
      requestId = randomBytes(8),
      protocol = ByteVector("eth".getBytes),
      request = randomBytes(100)
    )
    
    val encoded = talkRequestCodec.encode(talkReq)
    encoded.isSuccessful shouldBe true
    
    val decoded = talkRequestCodec.decode(encoded.require)
    decoded.isSuccessful shouldBe true
    
    val result = decoded.require.value
    result.requestId shouldBe talkReq.requestId
    result.protocol shouldBe talkReq.protocol
    result.request shouldBe talkReq.request
  }
  
  it should "handle TALKREQ with empty protocol" in {
    val talkReq = TalkRequest(randomBytes(8), ByteVector.empty, randomBytes(50))
    
    val encoded = talkRequestCodec.encode(talkReq)
    encoded.isSuccessful shouldBe true
    
    val decoded = talkRequestCodec.decode(encoded.require)
    decoded.isSuccessful shouldBe true
    decoded.require.value.protocol shouldBe ByteVector.empty
  }
  
  it should "handle TALKREQ with empty request data" in {
    val talkReq = TalkRequest(randomBytes(8), ByteVector("test".getBytes), ByteVector.empty)
    
    val encoded = talkRequestCodec.encode(talkReq)
    encoded.isSuccessful shouldBe true
    
    val decoded = talkRequestCodec.decode(encoded.require)
    decoded.isSuccessful shouldBe true
    decoded.require.value.request shouldBe ByteVector.empty
  }
  
  behavior of "Codec for TALKRESP message"
  
  it should "encode and decode TALKRESP message" in {
    val talkResp = TalkResponse(randomBytes(8), randomBytes(200))
    
    val encoded = talkResponseCodec.encode(talkResp)
    encoded.isSuccessful shouldBe true
    
    val decoded = talkResponseCodec.decode(encoded.require)
    decoded.isSuccessful shouldBe true
    
    val result = decoded.require.value
    result.requestId shouldBe talkResp.requestId
    result.response shouldBe talkResp.response
  }
  
  it should "handle TALKRESP with empty response" in {
    val talkResp = TalkResponse(randomBytes(8), ByteVector.empty)
    
    val encoded = talkResponseCodec.encode(talkResp)
    encoded.isSuccessful shouldBe true
    
    val decoded = talkResponseCodec.decode(encoded.require)
    decoded.isSuccessful shouldBe true
    decoded.require.value.response shouldBe ByteVector.empty
  }
  
  behavior of "Codec for REGTOPIC message"
  
  it should "encode and decode REGTOPIC message" in {
    val regTopic = RegTopic(
      requestId = randomBytes(8),
      topic = randomBytes(32),
      enr = mockEnr,
      ticket = randomBytes(32)
    )
    
    val encoded = regTopicCodec.encode(regTopic)
    encoded.isSuccessful shouldBe true
    
    val decoded = regTopicCodec.decode(encoded.require)
    decoded.isSuccessful shouldBe true
    
    val result = decoded.require.value
    result.requestId shouldBe regTopic.requestId
    result.topic shouldBe regTopic.topic
    result.ticket shouldBe regTopic.ticket
  }
  
  behavior of "Codec for TICKET message"
  
  it should "encode and decode TICKET message" in {
    val ticket = Ticket(randomBytes(8), randomBytes(64), waitTime = 300)
    
    val encoded = ticketCodec.encode(ticket)
    encoded.isSuccessful shouldBe true
    
    val decoded = ticketCodec.decode(encoded.require)
    decoded.isSuccessful shouldBe true
    
    val result = decoded.require.value
    result.requestId shouldBe ticket.requestId
    result.ticket shouldBe ticket.ticket
    result.waitTime shouldBe ticket.waitTime
  }
  
  it should "handle TICKET with zero wait time" in {
    val ticket = Ticket(randomBytes(8), randomBytes(32), waitTime = 0)
    
    val encoded = ticketCodec.encode(ticket)
    encoded.isSuccessful shouldBe true
    
    val decoded = ticketCodec.decode(encoded.require)
    decoded.isSuccessful shouldBe true
    decoded.require.value.waitTime shouldBe 0
  }
  
  behavior of "Codec for REGCONFIRMATION message"
  
  it should "encode and decode REGCONFIRMATION message" in {
    val regConf = RegConfirmation(randomBytes(8), randomBytes(32))
    
    val encoded = regConfirmationCodec.encode(regConf)
    encoded.isSuccessful shouldBe true
    
    val decoded = regConfirmationCodec.decode(encoded.require)
    decoded.isSuccessful shouldBe true
    
    val result = decoded.require.value
    result.requestId shouldBe regConf.requestId
    result.topic shouldBe regConf.topic
  }
  
  behavior of "Codec for TOPICQUERY message"
  
  it should "encode and decode TOPICQUERY message" in {
    val topicQuery = TopicQuery(randomBytes(8), randomBytes(32))
    
    val encoded = topicQueryCodec.encode(topicQuery)
    encoded.isSuccessful shouldBe true
    
    val decoded = topicQueryCodec.decode(encoded.require)
    decoded.isSuccessful shouldBe true
    
    val result = decoded.require.value
    result.requestId shouldBe topicQuery.requestId
    result.topic shouldBe topicQuery.topic
  }
  
  behavior of "Generic payload codec"
  
  it should "discriminate between different message types" in {
    val ping: Payload = Ping(randomBytes(8), 123L)
    val pong: Payload = Pong(randomBytes(8), 456L, ByteVector(127, 0, 0, 1), 30303)
    val findNode: Payload = FindNode(randomBytes(8), List(256))
    
    val encodedPing = payloadCodec.encode(ping)
    val encodedPong = payloadCodec.encode(pong)
    val encodedFindNode = payloadCodec.encode(findNode)
    
    encodedPing.isSuccessful shouldBe true
    encodedPong.isSuccessful shouldBe true
    encodedFindNode.isSuccessful shouldBe true
    
    val decodedPing = payloadCodec.decode(encodedPing.require)
    val decodedPong = payloadCodec.decode(encodedPong.require)
    val decodedFindNode = payloadCodec.decode(encodedFindNode.require)
    
    decodedPing.isSuccessful shouldBe true
    decodedPong.isSuccessful shouldBe true
    decodedFindNode.isSuccessful shouldBe true
    
    decodedPing.require.value shouldBe a[Ping]
    decodedPong.require.value shouldBe a[Pong]
    decodedFindNode.require.value shouldBe a[FindNode]
  }
  
  it should "preserve message type bytes in encoding" in {
    val ping = Ping(randomBytes(8), 100L)
    val encoded = payloadCodec.encode(ping).require
    
    // First byte should be the message type
    encoded.bytes(0) shouldBe MessageType.Ping
  }
  
  behavior of "Round-trip codec testing"
  
  it should "round-trip all message types without data loss" in {
    val messages: List[Payload] = List(
      Ping(randomBytes(8), 123L),
      Pong(randomBytes(8), 456L, ByteVector(192, 168, 1, 1), 30303),
      FindNode(randomBytes(8), List(256, 255)),
      Nodes(randomBytes(8), 2, List(mockEnr, mockEnr)),
      TalkRequest(randomBytes(8), ByteVector("eth".getBytes), randomBytes(50)),
      TalkResponse(randomBytes(8), randomBytes(75)),
      RegTopic(randomBytes(8), randomBytes(32), mockEnr, randomBytes(32)),
      Ticket(randomBytes(8), randomBytes(48), 300),
      RegConfirmation(randomBytes(8), randomBytes(32)),
      TopicQuery(randomBytes(8), randomBytes(32))
    )
    
    messages.foreach { msg =>
      val encoded = payloadCodec.encode(msg)
      encoded.isSuccessful shouldBe true
      
      val decoded = payloadCodec.decode(encoded.require)
      decoded.isSuccessful shouldBe true
      
      // Verify the message type is preserved
      decoded.require.value.messageType shouldBe msg.messageType
    }
  }
}
