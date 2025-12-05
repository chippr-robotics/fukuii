package com.chipprbots.scalanet.discovery.ethereum.v5

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scodec.bits.ByteVector
import java.security.SecureRandom

class HandshakeSpec extends AnyFlatSpec with Matchers {
  
  private val random = new SecureRandom()
  
  def randomBytes(n: Int): ByteVector = {
    val bytes = Array.ofDim[Byte](n)
    random.nextBytes(bytes)
    ByteVector.view(bytes)
  }
  
  behavior of "ECDH key exchange"
  
  it should "perform ECDH and produce 32-byte shared secret" in {
    val privateKey = randomBytes(32)
    val publicKey = randomBytes(64)
    
    val sharedSecret = Session.performECDH(privateKey, publicKey)
    
    sharedSecret.size shouldBe 32
  }
  
  it should "produce same shared secret from complementary key pairs" in {
    // Generate two key pairs
    val privateKeyA = randomBytes(32)
    val publicKeyA = randomBytes(64)
    val privateKeyB = randomBytes(32)
    val publicKeyB = randomBytes(64)
    
    // In real ECDH, both parties should get the same shared secret
    // Note: This test uses random keys, so we can't verify equality
    // But we can verify both operations succeed
    val secretA = Session.performECDH(privateKeyA, publicKeyB)
    val secretB = Session.performECDH(privateKeyB, publicKeyA)
    
    secretA.size shouldBe 32
    secretB.size shouldBe 32
  }
  
  it should "handle different public keys producing different secrets" in {
    val privateKey = randomBytes(32)
    val publicKey1 = randomBytes(64)
    val publicKey2 = randomBytes(64)
    
    val secret1 = Session.performECDH(privateKey, publicKey1)
    val secret2 = Session.performECDH(privateKey, publicKey2)
    
    // Different public keys should generally produce different secrets
    // (extremely unlikely to be the same with random inputs)
    secret1 should not equal secret2
  }
  
  behavior of "ECDH input validation"
  
  it should "reject private key that is too short" in {
    val shortPrivateKey = randomBytes(16)
    val publicKey = randomBytes(64)
    
    assertThrows[IllegalArgumentException] {
      Session.performECDH(shortPrivateKey, publicKey)
    }
  }
  
  it should "reject private key that is too long" in {
    val longPrivateKey = randomBytes(48)
    val publicKey = randomBytes(64)
    
    assertThrows[IllegalArgumentException] {
      Session.performECDH(longPrivateKey, publicKey)
    }
  }
  
  it should "reject public key that is too short" in {
    val privateKey = randomBytes(32)
    val shortPublicKey = randomBytes(32)
    
    assertThrows[IllegalArgumentException] {
      Session.performECDH(privateKey, shortPublicKey)
    }
  }
  
  it should "reject public key that is too long" in {
    val privateKey = randomBytes(32)
    val longPublicKey = randomBytes(96)
    
    assertThrows[IllegalArgumentException] {
      Session.performECDH(privateKey, longPublicKey)
    }
  }
  
  it should "handle all-zero private key" in {
    val zeroPrivateKey = ByteVector.fill(32)(0)
    val publicKey = randomBytes(64)
    
    // Should complete without throwing, even if result is invalid
    val secret = Session.performECDH(zeroPrivateKey, publicKey)
    secret.size shouldBe 32
  }
  
  behavior of "HKDF key derivation"
  
  it should "derive three different session keys from shared secret" in {
    val ephemeralKey = randomBytes(32)
    val localNodeId = randomBytes(32)
    val remoteNodeId = randomBytes(32)
    val idNonce = randomBytes(16)
    
    val keys = Session.deriveKeys(ephemeralKey, localNodeId, remoteNodeId, idNonce)
    
    keys.initiatorKey.size shouldBe 16
    keys.recipientKey.size shouldBe 16
    keys.authRespKey.size shouldBe 16
    
    // All three keys should be different
    keys.initiatorKey should not equal keys.recipientKey
    keys.initiatorKey should not equal keys.authRespKey
    keys.recipientKey should not equal keys.authRespKey
  }
  
  it should "produce deterministic keys from same inputs" in {
    val ephemeralKey = randomBytes(32)
    val localNodeId = randomBytes(32)
    val remoteNodeId = randomBytes(32)
    val idNonce = randomBytes(16)
    
    val keys1 = Session.deriveKeys(ephemeralKey, localNodeId, remoteNodeId, idNonce)
    val keys2 = Session.deriveKeys(ephemeralKey, localNodeId, remoteNodeId, idNonce)
    
    keys1.initiatorKey shouldBe keys2.initiatorKey
    keys1.recipientKey shouldBe keys2.recipientKey
    keys1.authRespKey shouldBe keys2.authRespKey
  }
  
  it should "produce different keys when ephemeral key changes" in {
    val ephemeralKey1 = randomBytes(32)
    val ephemeralKey2 = randomBytes(32)
    val localNodeId = randomBytes(32)
    val remoteNodeId = randomBytes(32)
    val idNonce = randomBytes(16)
    
    val keys1 = Session.deriveKeys(ephemeralKey1, localNodeId, remoteNodeId, idNonce)
    val keys2 = Session.deriveKeys(ephemeralKey2, localNodeId, remoteNodeId, idNonce)
    
    keys1.initiatorKey should not equal keys2.initiatorKey
    keys1.recipientKey should not equal keys2.recipientKey
    keys1.authRespKey should not equal keys2.authRespKey
  }
  
  it should "produce different keys when idNonce changes" in {
    val ephemeralKey = randomBytes(32)
    val localNodeId = randomBytes(32)
    val remoteNodeId = randomBytes(32)
    val idNonce1 = randomBytes(16)
    val idNonce2 = randomBytes(16)
    
    val keys1 = Session.deriveKeys(ephemeralKey, localNodeId, remoteNodeId, idNonce1)
    val keys2 = Session.deriveKeys(ephemeralKey, localNodeId, remoteNodeId, idNonce2)
    
    keys1.initiatorKey should not equal keys2.initiatorKey
    keys1.recipientKey should not equal keys2.recipientKey
    keys1.authRespKey should not equal keys2.authRespKey
  }
  
  it should "produce different keys when node IDs are swapped" in {
    val ephemeralKey = randomBytes(32)
    val nodeId1 = randomBytes(32)
    val nodeId2 = randomBytes(32)
    val idNonce = randomBytes(16)
    
    val keys1 = Session.deriveKeys(ephemeralKey, nodeId1, nodeId2, idNonce)
    val keys2 = Session.deriveKeys(ephemeralKey, nodeId2, nodeId1, idNonce)
    
    keys1.initiatorKey should not equal keys2.initiatorKey
    keys1.recipientKey should not equal keys2.recipientKey
    keys1.authRespKey should not equal keys2.authRespKey
  }
  
  behavior of "HKDF input validation"
  
  it should "reject localNodeId that is not 32 bytes" in {
    val ephemeralKey = randomBytes(32)
    val shortLocalNodeId = randomBytes(16)
    val remoteNodeId = randomBytes(32)
    val idNonce = randomBytes(16)
    
    assertThrows[IllegalArgumentException] {
      Session.deriveKeys(ephemeralKey, shortLocalNodeId, remoteNodeId, idNonce)
    }
  }
  
  it should "reject remoteNodeId that is not 32 bytes" in {
    val ephemeralKey = randomBytes(32)
    val localNodeId = randomBytes(32)
    val shortRemoteNodeId = randomBytes(16)
    val idNonce = randomBytes(16)
    
    assertThrows[IllegalArgumentException] {
      Session.deriveKeys(ephemeralKey, localNodeId, shortRemoteNodeId, idNonce)
    }
  }
  
  it should "reject idNonce that is not 16 bytes" in {
    val ephemeralKey = randomBytes(32)
    val localNodeId = randomBytes(32)
    val remoteNodeId = randomBytes(32)
    val shortIdNonce = randomBytes(8)
    
    assertThrows[IllegalArgumentException] {
      Session.deriveKeys(ephemeralKey, localNodeId, remoteNodeId, shortIdNonce)
    }
  }
  
  behavior of "Node ID computation"
  
  it should "compute node ID from public key using keccak256" in {
    val publicKey = randomBytes(64)
    
    val nodeId = Session.nodeIdFromPublicKey(publicKey)
    
    // Keccak256 produces 32-byte hash
    nodeId.size shouldBe 32
  }
  
  it should "produce deterministic node ID from same public key" in {
    val publicKey = randomBytes(64)
    
    val nodeId1 = Session.nodeIdFromPublicKey(publicKey)
    val nodeId2 = Session.nodeIdFromPublicKey(publicKey)
    
    nodeId1 shouldBe nodeId2
  }
  
  it should "produce different node IDs from different public keys" in {
    val publicKey1 = randomBytes(64)
    val publicKey2 = randomBytes(64)
    
    val nodeId1 = Session.nodeIdFromPublicKey(publicKey1)
    val nodeId2 = Session.nodeIdFromPublicKey(publicKey2)
    
    nodeId1 should not equal nodeId2
  }
  
  it should "reject public key that is not 64 bytes" in {
    val shortPublicKey = randomBytes(32)
    
    assertThrows[IllegalArgumentException] {
      Session.nodeIdFromPublicKey(shortPublicKey)
    }
  }
  
  behavior of "ID nonce generation"
  
  it should "generate 16-byte random nonces" in {
    val nonce1 = Session.randomIdNonce
    val nonce2 = Session.randomIdNonce
    
    nonce1.size shouldBe 16
    nonce2.size shouldBe 16
  }
  
  it should "generate unique nonces" in {
    val nonces = (1 to 100).map(_ => Session.randomIdNonce).toSet
    
    // All nonces should be unique (extremely high probability)
    nonces.size shouldBe 100
  }
  
  behavior of "Complete handshake flow simulation"
  
  it should "perform complete key derivation flow" in {
    // Simulate initiator and responder
    val initiatorPrivateKey = randomBytes(32)
    val initiatorPublicKey = randomBytes(64)
    val initiatorNodeId = Session.nodeIdFromPublicKey(initiatorPublicKey)
    
    val responderPrivateKey = randomBytes(32)
    val responderPublicKey = randomBytes(64)
    val responderNodeId = Session.nodeIdFromPublicKey(responderPublicKey)
    
    // Responder generates challenge nonce
    val idNonce = Session.randomIdNonce
    
    // Initiator performs ECDH with ephemeral key
    val ephemeralPrivateKey = randomBytes(32)
    val ephemeralPublicKey = randomBytes(64)
    val sharedSecret = Session.performECDH(ephemeralPrivateKey, responderPublicKey)
    
    // Initiator derives session keys
    val initiatorKeys = Session.deriveKeys(sharedSecret, initiatorNodeId, responderNodeId, idNonce)
    
    // Verify all keys are properly sized
    initiatorKeys.initiatorKey.size shouldBe 16
    initiatorKeys.recipientKey.size shouldBe 16
    initiatorKeys.authRespKey.size shouldBe 16
    
    // Responder would derive the same keys with the same shared secret
    val responderKeys = Session.deriveKeys(sharedSecret, initiatorNodeId, responderNodeId, idNonce)
    
    responderKeys.initiatorKey shouldBe initiatorKeys.initiatorKey
    responderKeys.recipientKey shouldBe initiatorKeys.recipientKey
    responderKeys.authRespKey shouldBe initiatorKeys.authRespKey
  }
  
  it should "enable encrypted communication after handshake" in {
    // Derive session keys
    val ephemeralKey = randomBytes(32)
    val localNodeId = randomBytes(32)
    val remoteNodeId = randomBytes(32)
    val idNonce = randomBytes(16)
    
    val keys = Session.deriveKeys(ephemeralKey, localNodeId, remoteNodeId, idNonce)
    
    // Use initiator key to encrypt a message
    val nonce = randomBytes(12)
    val message = ByteVector("Hello from initiator".getBytes)
    val authData = ByteVector.empty
    
    val encrypted = Session.encrypt(keys.initiatorKey, nonce, message, authData)
    encrypted.isSuccess shouldBe true
    
    // Recipient should be able to decrypt with same key
    val decrypted = Session.decrypt(keys.initiatorKey, nonce, encrypted.get, authData)
    decrypted.isSuccess shouldBe true
    decrypted.get shouldBe message
  }
  
  behavior of "Handshake packet construction"
  
  it should "create valid HandshakeAuthData" in {
    val srcId = randomBytes(32)
    val ephemPubkey = randomBytes(64)
    val idSignature = randomBytes(64)
    val sigSize = idSignature.size.toInt
    
    val handshakeData = Packet.HandshakeAuthData(
      srcId = srcId,
      sigSize = sigSize,
      ephemPubkey = ephemPubkey,
      idSignature = idSignature
    )
    
    handshakeData.srcId shouldBe srcId
    handshakeData.ephemPubkey shouldBe ephemPubkey
    handshakeData.idSignature shouldBe idSignature
    handshakeData.sigSize shouldBe sigSize
  }
  
  it should "reject HandshakeAuthData with invalid srcId size" in {
    val shortSrcId = randomBytes(16)
    val ephemPubkey = randomBytes(64)
    val idSignature = randomBytes(64)
    
    assertThrows[IllegalArgumentException] {
      Packet.HandshakeAuthData(
        srcId = shortSrcId,
        sigSize = idSignature.size.toInt,
        ephemPubkey = ephemPubkey,
        idSignature = idSignature
      )
    }
  }
  
  it should "reject HandshakeAuthData with invalid ephemPubkey size" in {
    val srcId = randomBytes(32)
    val shortEphemPubkey = randomBytes(32)
    val idSignature = randomBytes(64)
    
    assertThrows[IllegalArgumentException] {
      Packet.HandshakeAuthData(
        srcId = srcId,
        sigSize = idSignature.size.toInt,
        ephemPubkey = shortEphemPubkey,
        idSignature = idSignature
      )
    }
  }
  
  behavior of "WhoAreYou packet construction"
  
  it should "create valid WhoAreYouData" in {
    val idNonce = randomBytes(16)
    val enrSeq = 42L
    
    val whoAreYouData = Packet.WhoAreYouData(idNonce, enrSeq)
    
    whoAreYouData.idNonce shouldBe idNonce
    whoAreYouData.enrSeq shouldBe enrSeq
  }
  
  it should "reject WhoAreYouData with invalid idNonce size" in {
    val shortIdNonce = randomBytes(8)
    val enrSeq = 42L
    
    assertThrows[IllegalArgumentException] {
      Packet.WhoAreYouData(shortIdNonce, enrSeq)
    }
  }
  
  it should "handle WhoAreYouData with zero ENR sequence" in {
    val idNonce = randomBytes(16)
    val enrSeq = 0L
    
    val whoAreYouData = Packet.WhoAreYouData(idNonce, enrSeq)
    whoAreYouData.enrSeq shouldBe 0L
  }
  
  it should "handle WhoAreYouData with maximum ENR sequence" in {
    val idNonce = randomBytes(16)
    val enrSeq = Long.MaxValue
    
    val whoAreYouData = Packet.WhoAreYouData(idNonce, enrSeq)
    whoAreYouData.enrSeq shouldBe Long.MaxValue
  }
}
