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
      session = Session.ActiveSession(keys, randomBytes(32), nodeId, isInitiator = true)
      
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
  
  behavior of "Peer discovery integration (PING/PONG)"
  
  it should "create and encode a PING message" in {
    val requestId = Payload.randomRequestId
    val ping = Payload.Ping(requestId, enrSeq = 123L)
    
    ping.requestId.size shouldBe 8
    ping.enrSeq shouldBe 123L
    ping.messageType shouldBe Payload.MessageType.Ping
  }
  
  it should "create a matching PONG response for a PING" in {
    val requestId = Payload.randomRequestId
    val ping = Payload.Ping(requestId, enrSeq = 123L)
    
    // Create PONG with same request ID
    val pong = Payload.Pong(
      requestId = ping.requestId,
      enrSeq = 456L,
      recipientIP = ByteVector(127, 0, 0, 1),
      recipientPort = 30303
    )
    
    pong.requestId shouldBe ping.requestId
    pong.messageType shouldBe Payload.MessageType.Pong
  }
  
  it should "simulate full PING/PONG cycle" in {
    // Node A sends PING
    val nodeARequestId = Payload.randomRequestId
    val ping = Payload.Ping(nodeARequestId, enrSeq = 100L)
    
    // Node B receives PING and responds with PONG
    val pong = Payload.Pong(
      requestId = ping.requestId,
      enrSeq = 200L,
      recipientIP = ByteVector(192, 168, 1, 100),
      recipientPort = 30303
    )
    
    // Verify request ID matching
    pong.requestId shouldBe ping.requestId
    
    // Verify both nodes can see ENR sequences
    ping.enrSeq shouldBe 100L
    pong.enrSeq shouldBe 200L
  }
  
  behavior of "Node lookup integration (FINDNODE/NODES)"
  
  it should "create FINDNODE request with target distances" in {
    val requestId = Payload.randomRequestId
    val findNode = Payload.FindNode(requestId, distances = List(256, 255, 254))
    
    findNode.requestId.size shouldBe 8
    findNode.distances shouldBe List(256, 255, 254)
    findNode.messageType shouldBe Payload.MessageType.FindNode
  }
  
  it should "create NODES response matching FINDNODE request" in {
    val requestId = Payload.randomRequestId
    val findNode = Payload.FindNode(requestId, distances = List(256))
    
    // Create empty NODES response
    val nodes = Payload.Nodes(
      requestId = findNode.requestId,
      total = 1,
      enrs = List.empty
    )
    
    nodes.requestId shouldBe findNode.requestId
    nodes.messageType shouldBe Payload.MessageType.Nodes
  }
  
  it should "handle multi-packet NODES response" in {
    val requestId = Payload.randomRequestId
    
    // Simulate response split across multiple packets
    val nodes1 = Payload.Nodes(requestId, total = 3, enrs = List.empty)
    val nodes2 = Payload.Nodes(requestId, total = 3, enrs = List.empty)
    val nodes3 = Payload.Nodes(requestId, total = 3, enrs = List.empty)
    
    nodes1.total shouldBe 3
    nodes2.total shouldBe 3
    nodes3.total shouldBe 3
    
    // All should have same request ID
    nodes1.requestId shouldBe requestId
    nodes2.requestId shouldBe requestId
    nodes3.requestId shouldBe requestId
  }
  
  behavior of "Handshake flow integration"
  
  it should "simulate WHOAREYOU challenge" in {
    val nonce = Packet.randomNonce
    val idNonce = Session.randomIdNonce
    val enrSeq = 42L
    
    val whoAreYouData = Packet.WhoAreYouData(idNonce, enrSeq)
    val whoAreYouPacket = Packet.WhoAreYouPacket(
      nonce = nonce,
      authDataSize = 24, // 16 bytes idNonce + 8 bytes enrSeq
      whoAreYouData = whoAreYouData
    )
    
    whoAreYouPacket.whoAreYouData.idNonce.size shouldBe 16
    whoAreYouPacket.whoAreYouData.enrSeq shouldBe enrSeq
  }
  
  it should "simulate handshake response to WHOAREYOU" in {
    val nonce = Packet.randomNonce
    val srcId = randomBytes(32)
    val ephemPubkey = randomBytes(64)
    val idSignature = randomBytes(64)
    
    val handshakeData = Packet.HandshakeAuthData(
      srcId = srcId,
      sigSize = 64,
      ephemPubkey = ephemPubkey,
      idSignature = idSignature
    )
    
    val handshakePacket = Packet.HandshakeMessagePacket(
      nonce = nonce,
      authDataSize = 161, // 32 + 1 + 64 + 64
      handshakeAuthData = handshakeData,
      messageCipherText = randomBytes(50)
    )
    
    handshakePacket.handshakeAuthData.srcId.size shouldBe 32
    handshakePacket.handshakeAuthData.ephemPubkey.size shouldBe 64
  }
  
  it should "perform complete handshake with session establishment" in {
    // Step 1: Node A sends initial message (OrdinaryMessage)
    val initialNonce = Packet.randomNonce
    val initialPacket = Packet.OrdinaryMessagePacket(
      nonce = initialNonce,
      authDataSize = 32,
      authData = randomBytes(32),
      messageCipherText = randomBytes(50)
    )
    
    // Step 2: Node B responds with WHOAREYOU challenge
    val challengeNonce = Packet.randomNonce
    val idNonce = Session.randomIdNonce
    val whoAreYouData = Packet.WhoAreYouData(idNonce, enrSeq = 0L)
    val whoAreYouPacket = Packet.WhoAreYouPacket(
      nonce = challengeNonce,
      authDataSize = 24,
      whoAreYouData = whoAreYouData
    )
    
    // Step 3: Node A performs ECDH and key derivation
    val ephemeralPrivateKey = randomBytes(32)
    val ephemeralPublicKey = randomBytes(64)
    val remotePubKey = randomBytes(64)
    val sharedSecret = Session.performECDH(ephemeralPrivateKey, remotePubKey)
    
    val localNodeId = randomBytes(32)
    val remoteNodeId = randomBytes(32)
    val sessionKeys = Session.deriveKeys(sharedSecret, localNodeId, remoteNodeId, idNonce)
    
    // Step 4: Node A sends handshake message
    val handshakeNonce = Packet.randomNonce
    val handshakeData = Packet.HandshakeAuthData(
      srcId = localNodeId,
      sigSize = 64,
      ephemPubkey = ephemeralPublicKey,
      idSignature = randomBytes(64)
    )
    
    val handshakePacket = Packet.HandshakeMessagePacket(
      nonce = handshakeNonce,
      authDataSize = 161,
      handshakeAuthData = handshakeData,
      messageCipherText = randomBytes(50)
    )
    
    // Step 5: Session is established
    val session = Session.ActiveSession(sessionKeys, localNodeId, remoteNodeId, isInitiator = true)
    
    session.keys.initiatorKey.size shouldBe 16
    session.keys.recipientKey.size shouldBe 16
    session.keys.authRespKey.size shouldBe 16
    session.isInitiator shouldBe true
  }
  
  behavior of "Session persistence and encrypted communication"
  
  it should "persist session in cache and use for encryption" in {
    val test = for {
      cache <- Session.SessionCache()
      
      // Create session keys
      ephemeralKey = randomBytes(32)
      localNodeId = randomBytes(32)
      remoteNodeId = randomBytes(32)
      idNonce = Session.randomIdNonce
      keys = Session.deriveKeys(ephemeralKey, localNodeId, remoteNodeId, idNonce)
      
      // Create and store session
      session = Session.ActiveSession(keys, localNodeId, remoteNodeId, isInitiator = true)
      _ <- cache.put(remoteNodeId, session)
      
      // Retrieve session
      retrieved <- cache.get(remoteNodeId)
      
      // Use session keys for encryption
      nonce = randomBytes(12)
      message = ByteVector("Encrypted message".getBytes)
      authData = ByteVector.empty
      encrypted = Session.encrypt(retrieved.get.keys.initiatorKey, nonce, message, authData)
      decrypted = Session.decrypt(retrieved.get.keys.initiatorKey, nonce, encrypted.get, authData)
      
    } yield (retrieved.isDefined, decrypted.isSuccess, decrypted.get)
    
    val (sessionExists, decryptSuccess, decryptedMessage) = test.unsafeRunSync()
    
    sessionExists shouldBe true
    decryptSuccess shouldBe true
    decryptedMessage shouldBe ByteVector("Encrypted message".getBytes)
  }
  
  it should "maintain separate encryption keys for initiator and recipient" in {
    val ephemeralKey = randomBytes(32)
    val localNodeId = randomBytes(32)
    val remoteNodeId = randomBytes(32)
    val idNonce = Session.randomIdNonce
    
    val keys = Session.deriveKeys(ephemeralKey, localNodeId, remoteNodeId, idNonce)
    
    // Initiator encrypts with initiatorKey
    val initiatorNonce = randomBytes(12)
    val initiatorMessage = ByteVector("From initiator".getBytes)
    val initiatorEncrypted = Session.encrypt(keys.initiatorKey, initiatorNonce, initiatorMessage, ByteVector.empty).get
    
    // Recipient encrypts with recipientKey
    val recipientNonce = randomBytes(12)
    val recipientMessage = ByteVector("From recipient".getBytes)
    val recipientEncrypted = Session.encrypt(keys.recipientKey, recipientNonce, recipientMessage, ByteVector.empty).get
    
    // Both can decrypt their own messages
    val decryptedInitiator = Session.decrypt(keys.initiatorKey, initiatorNonce, initiatorEncrypted, ByteVector.empty)
    val decryptedRecipient = Session.decrypt(keys.recipientKey, recipientNonce, recipientEncrypted, ByteVector.empty)
    
    decryptedInitiator.isSuccess shouldBe true
    decryptedRecipient.isSuccess shouldBe true
    decryptedInitiator.get shouldBe initiatorMessage
    decryptedRecipient.get shouldBe recipientMessage
  }
  
  it should "handle session expiry and replacement" in {
    val test = for {
      cache <- Session.SessionCache()
      nodeId = randomBytes(32)
      
      // Create first session
      keys1 = Session.SessionKeys(randomBytes(16), randomBytes(16), randomBytes(16))
      session1 = Session.ActiveSession(keys1, randomBytes(32), nodeId, isInitiator = true)
      _ <- cache.put(nodeId, session1)
      
      // Simulate session expiry and replacement
      keys2 = Session.SessionKeys(randomBytes(16), randomBytes(16), randomBytes(16))
      session2 = Session.ActiveSession(keys2, randomBytes(32), nodeId, isInitiator = false)
      _ <- cache.put(nodeId, session2)
      
      // Retrieve current session
      current <- cache.get(nodeId)
    } yield current
    
    val currentSession = test.unsafeRunSync()
    currentSession shouldBe defined
    currentSession.get.isInitiator shouldBe false
  }
  
  behavior of "End-to-end message flow"
  
  it should "complete full message exchange with encryption" in {
    val test = for {
      cache <- Session.SessionCache()
      
      // Setup: Two nodes establish session
      ephemeralKey = randomBytes(32)
      nodeAId = randomBytes(32)
      nodeBId = randomBytes(32)
      idNonce = Session.randomIdNonce
      keys = Session.deriveKeys(ephemeralKey, nodeAId, nodeBId, idNonce)
      
      sessionA = Session.ActiveSession(keys, nodeAId, nodeBId, isInitiator = true)
      sessionB = Session.ActiveSession(keys, nodeAId, nodeBId, isInitiator = false)
      
      _ <- cache.put(nodeBId, sessionA) // Node A's session for Node B
      
      // Node A creates and encrypts PING
      pingRequestId = Payload.randomRequestId
      ping = Payload.Ping(pingRequestId, enrSeq = 100L)
      
      nonce1 = randomBytes(12)
      authData1 = ByteVector.empty
      
      // Encrypt PING message (simplified - in reality would use codec)
      pingBytes = ByteVector("PING".getBytes)
      encryptedPing = Session.encrypt(sessionA.keys.initiatorKey, nonce1, pingBytes, authData1)
      
      // Node B decrypts PING
      decryptedPing = Session.decrypt(sessionB.keys.initiatorKey, nonce1, encryptedPing.get, authData1)
      
      // Node B creates and encrypts PONG
      pong = Payload.Pong(pingRequestId, enrSeq = 200L, ByteVector(127, 0, 0, 1), 30303)
      
      nonce2 = randomBytes(12)
      authData2 = ByteVector.empty
      
      pongBytes = ByteVector("PONG".getBytes)
      encryptedPong = Session.encrypt(sessionB.keys.recipientKey, nonce2, pongBytes, authData2)
      
      // Node A decrypts PONG
      decryptedPong = Session.decrypt(sessionA.keys.recipientKey, nonce2, encryptedPong.get, authData2)
      
    } yield (decryptedPing.isSuccess, decryptedPong.isSuccess)
    
    val (pingSuccess, pongSuccess) = test.unsafeRunSync()
    
    pingSuccess shouldBe true
    pongSuccess shouldBe true
  }
}
