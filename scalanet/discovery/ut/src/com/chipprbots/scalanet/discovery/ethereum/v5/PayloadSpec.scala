package com.chipprbots.scalanet.discovery.ethereum.v5

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scodec.bits.ByteVector
import java.security.SecureRandom

class PayloadSpec extends AnyFlatSpec with Matchers {
  import Payload._
  
  private val random = new SecureRandom()
  
  def randomBytes(n: Int): ByteVector = {
    val bytes = Array.ofDim[Byte](n)
    random.nextBytes(bytes)
    ByteVector.view(bytes)
  }
  
  behavior of "Payload messages"
  
  it should "create a valid Ping message" in {
    val requestId = randomRequestId
    val ping = Ping(requestId, enrSeq = 123)
    
    ping.requestId.size shouldBe 8
    ping.enrSeq shouldBe 123
    ping.messageType shouldBe MessageType.Ping
  }
  
  it should "create a valid Pong message" in {
    val requestId = randomRequestId
    val pong = Pong(
      requestId = requestId,
      enrSeq = 456,
      recipientIP = ByteVector(192, 168, 1, 1),
      recipientPort = 30303
    )
    
    pong.requestId shouldBe requestId
    pong.enrSeq shouldBe 456
    pong.recipientPort shouldBe 30303
    pong.messageType shouldBe MessageType.Pong
  }
  
  it should "create a valid FindNode message" in {
    val requestId = randomRequestId
    val findNode = FindNode(requestId, distances = List(256, 255, 254))
    
    findNode.requestId shouldBe requestId
    findNode.distances shouldBe List(256, 255, 254)
    findNode.messageType shouldBe MessageType.FindNode
  }
  
  it should "reject FindNode with invalid distances" in {
    val requestId = randomRequestId
    
    assertThrows[IllegalArgumentException] {
      FindNode(requestId, distances = List(257)) // Out of range
    }
    
    assertThrows[IllegalArgumentException] {
      FindNode(requestId, distances = List(-1)) // Negative
    }
  }
  
  it should "create a valid TalkRequest message" in {
    val requestId = randomRequestId
    val protocol = ByteVector("eth".getBytes)
    val request = randomBytes(100)
    
    val talkReq = TalkRequest(requestId, protocol, request)
    
    talkReq.requestId shouldBe requestId
    talkReq.protocol shouldBe protocol
    talkReq.request shouldBe request
    talkReq.messageType shouldBe MessageType.TalkReq
  }
  
  it should "create a valid TalkResponse message" in {
    val requestId = randomRequestId
    val response = randomBytes(100)
    
    val talkResp = TalkResponse(requestId, response)
    
    talkResp.requestId shouldBe requestId
    talkResp.response shouldBe response
    talkResp.messageType shouldBe MessageType.TalkResp
  }
  
  it should "generate unique random request IDs" in {
    val id1 = randomRequestId
    val id2 = randomRequestId
    
    id1.size shouldBe 8
    id2.size shouldBe 8
    id1 should not equal id2 // Very unlikely to be equal
  }
  
  it should "validate message type constants" in {
    MessageType.isValid(MessageType.Ping) shouldBe true
    MessageType.isValid(MessageType.Pong) shouldBe true
    MessageType.isValid(MessageType.FindNode) shouldBe true
    MessageType.isValid(MessageType.Nodes) shouldBe true
    MessageType.isValid(MessageType.TalkReq) shouldBe true
    MessageType.isValid(MessageType.TalkResp) shouldBe true
    MessageType.isValid(MessageType.RegTopic) shouldBe true
    MessageType.isValid(MessageType.Ticket) shouldBe true
    MessageType.isValid(MessageType.RegConfirmation) shouldBe true
    MessageType.isValid(MessageType.TopicQuery) shouldBe true
    
    MessageType.isValid(0x00.toByte) shouldBe false
    MessageType.isValid(0x0B.toByte) shouldBe false
    MessageType.isValid(0xFF.toByte) shouldBe false
  }
  
  it should "reject RegTopic with invalid topic size" in {
    val requestId = randomRequestId
    val invalidTopic = randomBytes(16) // Should be 32 bytes
    val ticket = randomBytes(32)
    
    // Create a mock ENR for testing
    import com.chipprbots.scalanet.discovery.ethereum.EthereumNodeRecord
    import com.chipprbots.scalanet.discovery.crypto.Signature
    import scodec.bits.ByteVector
    val mockEnr = EthereumNodeRecord(
      signature = Signature(scodec.bits.BitVector.fill(512)(false)),
      content = EthereumNodeRecord.Content(1L, scala.collection.immutable.SortedMap.empty[ByteVector, ByteVector])
    )
    
    assertThrows[IllegalArgumentException] {
      RegTopic(requestId, invalidTopic, mockEnr, ticket)
    }
  }
  
  it should "reject TopicQuery with invalid topic size" in {
    val requestId = randomRequestId
    val invalidTopic = randomBytes(24) // Should be 32 bytes
    
    assertThrows[IllegalArgumentException] {
      TopicQuery(requestId, invalidTopic)
    }
  }
}
