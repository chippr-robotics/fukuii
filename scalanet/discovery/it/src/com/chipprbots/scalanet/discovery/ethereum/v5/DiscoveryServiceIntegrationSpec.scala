package com.chipprbots.scalanet.discovery.ethereum.v5

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.chipprbots.scalanet.discovery.ethereum.Node
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scodec.bits.ByteVector
import scala.concurrent.duration._
import java.security.SecureRandom

class DiscoveryServiceIntegrationSpec extends AnyFlatSpec with Matchers {
  
  private val random = new SecureRandom()
  
  def randomBytes(n: Int): ByteVector = {
    val bytes = Array.ofDim[Byte](n)
    random.nextBytes(bytes)
    ByteVector.view(bytes)
  }
  
  behavior of "DiscoveryService integration"
  
  it should "create a discovery service with default configuration" in {
    // This is a placeholder test that verifies the configuration works
    val config = DiscoveryConfig.default
    
    config.requestTimeout should be > 0.seconds
    config.kademliaBucketSize should be > 0
    config.maxNodesPerMessage should be > 0
  }
  
  it should "create a minimal configuration for testing" in {
    val config = DiscoveryConfig.minimal()
    
    config.discoveryInterval shouldBe 10.seconds
    config.bucketRefreshInterval shouldBe 10.minutes
    config.bootstrapNodes shouldBe Set.empty
  }
  
  it should "create configuration with bootstrap nodes" in {
    val bootstrapNode = Node(
      id = com.chipprbots.scalanet.discovery.crypto.PublicKey(randomBytes(64).bits),
      address = Node.Address(
        ip = java.net.InetAddress.getByName("127.0.0.1"),
        udpPort = 30303,
        tcpPort = 30303
      )
    )
    
    val config = DiscoveryConfig.minimal(Set(bootstrapNode))
    
    config.bootstrapNodes should contain(bootstrapNode)
  }
  
  behavior of "Session management integration"
  
  it should "create and use a session cache" in {
    val test = for {
      cache <- Session.SessionCache()
      nodeId = randomBytes(32)
      keys = Session.SessionKeys(randomBytes(16), randomBytes(16), randomBytes(16))
      session = Session.ActiveSession(keys, randomBytes(32), nodeId)
      
      _ <- cache.put(nodeId, session)
      retrieved <- cache.get(nodeId)
    } yield retrieved
    
    val result = test.unsafeRunSync()
    result shouldBe defined
    result.get.remoteNodeId.size shouldBe 32
  }
  
  behavior of "Packet wire format integration"
  
  it should "round-trip encode and decode all packet types" in {
    val nonce = Packet.randomNonce
    
    // Test OrdinaryMessage
    val ordinaryPacket = Packet.OrdinaryMessagePacket(
      nonce = nonce,
      authDataSize = 32,
      authData = randomBytes(32),
      messageCipherText = randomBytes(100)
    )
    
    val encodedOrdinary = Packet.encodePacket(ordinaryPacket, Packet.Flag.OrdinaryMessage).require
    val decodedOrdinary = Packet.decodePacket(encodedOrdinary).require.value
    
    decodedOrdinary shouldBe a[Packet.OrdinaryMessagePacket]
    
    // Test WhoAreYou
    val whoAreYouData = Packet.WhoAreYouData(randomBytes(16), 42L)
    val whoAreYouPacket = Packet.WhoAreYouPacket(
      nonce = nonce,
      authDataSize = 24,
      whoAreYouData = whoAreYouData
    )
    
    val encodedWhoAreYou = Packet.encodePacket(whoAreYouPacket, Packet.Flag.WhoAreYou).require
    val decodedWhoAreYou = Packet.decodePacket(encodedWhoAreYou).require.value
    
    decodedWhoAreYou shouldBe a[Packet.WhoAreYouPacket]
    
    // Test Handshake
    val handshakeData = Packet.HandshakeAuthData(
      srcId = randomBytes(32),
      sigSize = 64,
      ephemPubkey = randomBytes(64),
      idSignature = randomBytes(64)
    )
    val handshakePacket = Packet.HandshakeMessagePacket(
      nonce = nonce,
      authDataSize = 161, // 32 + 1 + 64 + 64
      handshakeAuthData = handshakeData,
      messageCipherText = randomBytes(100)
    )
    
    val encodedHandshake = Packet.encodePacket(handshakePacket, Packet.Flag.HandshakeMessage).require
    val decodedHandshake = Packet.decodePacket(encodedHandshake).require.value
    
    decodedHandshake shouldBe a[Packet.HandshakeMessagePacket]
  }
  
  it should "validate protocol constants" in {
    Packet.ProtocolId.toHex shouldBe "646973637635" // "discv5"
    Packet.Version.toHex shouldBe "0001"
    Packet.NonceSize shouldBe 12
    Packet.MaxPacketSize shouldBe 1280
  }
}
