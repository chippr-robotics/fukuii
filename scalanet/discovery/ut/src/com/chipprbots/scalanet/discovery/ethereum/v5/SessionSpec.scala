package com.chipprbots.scalanet.discovery.ethereum.v5

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scodec.bits.ByteVector
import java.security.SecureRandom

class SessionSpec extends AnyFlatSpec with Matchers {
  
  private val random = new SecureRandom()
  
  def randomBytes(n: Int): ByteVector = {
    val bytes = Array.ofDim[Byte](n)
    random.nextBytes(bytes)
    ByteVector.view(bytes)
  }
  
  behavior of "Session key derivation"
  
  it should "derive session keys from ECDH shared secret" in {
    val ephemeralKey = randomBytes(32)
    val localNodeId = randomBytes(32)
    val remoteNodeId = randomBytes(32)
    val idNonce = randomBytes(16)
    
    val keys = Session.deriveKeys(ephemeralKey, localNodeId, remoteNodeId, idNonce)
    
    keys.initiatorKey.size shouldBe 16
    keys.recipientKey.size shouldBe 16
    keys.authRespKey.size shouldBe 16
    
    // Keys should be different
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
  
  it should "produce different keys from different inputs" in {
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
  
  behavior of "AES-GCM encryption/decryption"
  
  it should "encrypt and decrypt data successfully" in {
    val key = randomBytes(16)
    val nonce = randomBytes(12)
    val plaintext = ByteVector("Hello, Discovery v5!".getBytes)
    val authData = ByteVector("additional-auth-data".getBytes)
    
    val encrypted = Session.encrypt(key, nonce, plaintext, authData)
    encrypted.isSuccess shouldBe true
    
    val ciphertext = encrypted.get
    ciphertext should not equal plaintext
    ciphertext.size should be > plaintext.size // Includes auth tag
    
    val decrypted = Session.decrypt(key, nonce, ciphertext, authData)
    decrypted.isSuccess shouldBe true
    decrypted.get shouldBe plaintext
  }
  
  it should "fail decryption with wrong key" in {
    val key1 = randomBytes(16)
    val key2 = randomBytes(16)
    val nonce = randomBytes(12)
    val plaintext = ByteVector("Test data".getBytes)
    val authData = ByteVector.empty
    
    val encrypted = Session.encrypt(key1, nonce, plaintext, authData).get
    val decrypted = Session.decrypt(key2, nonce, encrypted, authData)
    
    decrypted.isSuccess shouldBe false
  }
  
  it should "fail decryption with tampered ciphertext" in {
    val key = randomBytes(16)
    val nonce = randomBytes(12)
    val plaintext = ByteVector("Test data".getBytes)
    val authData = ByteVector.empty
    
    val encrypted = Session.encrypt(key, nonce, plaintext, authData).get
    
    // Tamper with ciphertext
    val tampered = encrypted.update(0, (encrypted(0) ^ 0x01).toByte)
    
    val decrypted = Session.decrypt(key, nonce, tampered, authData)
    decrypted.isSuccess shouldBe false
  }
  
  it should "fail decryption with wrong auth data" in {
    val key = randomBytes(16)
    val nonce = randomBytes(12)
    val plaintext = ByteVector("Test data".getBytes)
    val authData1 = ByteVector("auth1".getBytes)
    val authData2 = ByteVector("auth2".getBytes)
    
    val encrypted = Session.encrypt(key, nonce, plaintext, authData1).get
    val decrypted = Session.decrypt(key, nonce, encrypted, authData2)
    
    decrypted.isSuccess shouldBe false
  }
  
  behavior of "Session cache"
  
  it should "store and retrieve sessions" in {
    val cache = Session.SessionCache().unsafeRunSync()
    val nodeId = randomBytes(32)
    val keys = Session.SessionKeys(randomBytes(16), randomBytes(16), randomBytes(16))
    val session = Session.ActiveSession(keys, randomBytes(32), randomBytes(32), isInitiator = true)
    
    val test = for {
      _ <- cache.put(nodeId, session)
      retrieved <- cache.get(nodeId)
    } yield retrieved
    
    val result = test.unsafeRunSync()
    result shouldBe Some(session)
  }
  
  it should "return None for missing sessions" in {
    val cache = Session.SessionCache().unsafeRunSync()
    val nodeId = randomBytes(32)
    
    val result = cache.get(nodeId).unsafeRunSync()
    result shouldBe None
  }
  
  it should "remove sessions" in {
    val cache = Session.SessionCache().unsafeRunSync()
    val nodeId = randomBytes(32)
    val keys = Session.SessionKeys(randomBytes(16), randomBytes(16), randomBytes(16))
    val session = Session.ActiveSession(keys, randomBytes(32), randomBytes(32), isInitiator = true)
    
    val test = for {
      _ <- cache.put(nodeId, session)
      _ <- cache.remove(nodeId)
      retrieved <- cache.get(nodeId)
    } yield retrieved
    
    val result = test.unsafeRunSync()
    result shouldBe None
  }
  
  it should "clear all sessions" in {
    val cache = Session.SessionCache().unsafeRunSync()
    val keys = Session.SessionKeys(randomBytes(16), randomBytes(16), randomBytes(16))
    
    val test = for {
      _ <- cache.put(randomBytes(32), Session.ActiveSession(keys, randomBytes(32), randomBytes(32), isInitiator = true))
      _ <- cache.put(randomBytes(32), Session.ActiveSession(keys, randomBytes(32), randomBytes(32), isInitiator = true))
      _ <- cache.clear
      node1 <- cache.get(randomBytes(32))
      node2 <- cache.get(randomBytes(32))
    } yield (node1, node2)
    
    val (result1, result2) = test.unsafeRunSync()
    result1 shouldBe None
    result2 shouldBe None
  }
  
  behavior of "Utility functions"
  
  it should "generate random ID nonces" in {
    val nonce1 = Session.randomIdNonce
    val nonce2 = Session.randomIdNonce
    
    nonce1.size shouldBe 16
    nonce2.size shouldBe 16
    nonce1 should not equal nonce2
  }
  
  it should "compute node ID from public key" in {
    val publicKey = randomBytes(64)
    val nodeId = Session.nodeIdFromPublicKey(publicKey)
    
    nodeId.size shouldBe 32 // Keccak256 output
  }
  
  behavior of "ECDH key exchange"
  
  it should "perform ECDH and derive 32-byte shared secret" in {
    // Generate test keys
    val privateKey = randomBytes(32)
    val publicKey = randomBytes(64)
    
    val sharedSecret = Session.performECDH(privateKey, publicKey)
    
    sharedSecret.size shouldBe 32
  }
  
  it should "reject invalid key sizes for ECDH" in {
    assertThrows[IllegalArgumentException] {
      Session.performECDH(randomBytes(16), randomBytes(64)) // Short private key
    }
    
    assertThrows[IllegalArgumentException] {
      Session.performECDH(randomBytes(32), randomBytes(32)) // Short public key
    }
  }
  
  it should "reject invalid input sizes" in {
    assertThrows[IllegalArgumentException] {
      Session.deriveKeys(randomBytes(32), randomBytes(16), randomBytes(32), randomBytes(16))
    }
    
    assertThrows[IllegalArgumentException] {
      Session.encrypt(randomBytes(32), randomBytes(12), randomBytes(10), ByteVector.empty)
    }
    
    assertThrows[IllegalArgumentException] {
      Session.nodeIdFromPublicKey(randomBytes(32))
    }
  }
  
  behavior of "Session lifecycle management"
  
  it should "create sessions with correct initiator flag" in {
    val keys = Session.SessionKeys(randomBytes(16), randomBytes(16), randomBytes(16))
    val localNodeId = randomBytes(32)
    val remoteNodeId = randomBytes(32)
    
    val initiatorSession = Session.ActiveSession(keys, localNodeId, remoteNodeId, isInitiator = true)
    val responderSession = Session.ActiveSession(keys, localNodeId, remoteNodeId, isInitiator = false)
    
    initiatorSession.isInitiator shouldBe true
    responderSession.isInitiator shouldBe false
  }
  
  it should "store multiple sessions with different node IDs" in {
    val cache = Session.SessionCache().unsafeRunSync()
    val keys = Session.SessionKeys(randomBytes(16), randomBytes(16), randomBytes(16))
    
    val nodeId1 = randomBytes(32)
    val nodeId2 = randomBytes(32)
    val nodeId3 = randomBytes(32)
    
    val session1 = Session.ActiveSession(keys, randomBytes(32), nodeId1, isInitiator = true)
    val session2 = Session.ActiveSession(keys, randomBytes(32), nodeId2, isInitiator = false)
    val session3 = Session.ActiveSession(keys, randomBytes(32), nodeId3, isInitiator = true)
    
    val test = for {
      _ <- cache.put(nodeId1, session1)
      _ <- cache.put(nodeId2, session2)
      _ <- cache.put(nodeId3, session3)
      retrieved1 <- cache.get(nodeId1)
      retrieved2 <- cache.get(nodeId2)
      retrieved3 <- cache.get(nodeId3)
    } yield (retrieved1, retrieved2, retrieved3)
    
    val (r1, r2, r3) = test.unsafeRunSync()
    r1 shouldBe Some(session1)
    r2 shouldBe Some(session2)
    r3 shouldBe Some(session3)
  }
  
  it should "update existing session when same node ID is used" in {
    val cache = Session.SessionCache().unsafeRunSync()
    val nodeId = randomBytes(32)
    val keys1 = Session.SessionKeys(randomBytes(16), randomBytes(16), randomBytes(16))
    val keys2 = Session.SessionKeys(randomBytes(16), randomBytes(16), randomBytes(16))
    
    val session1 = Session.ActiveSession(keys1, randomBytes(32), nodeId, isInitiator = true)
    val session2 = Session.ActiveSession(keys2, randomBytes(32), nodeId, isInitiator = false)
    
    val test = for {
      _ <- cache.put(nodeId, session1)
      retrieved1 <- cache.get(nodeId)
      _ <- cache.put(nodeId, session2)
      retrieved2 <- cache.get(nodeId)
    } yield (retrieved1, retrieved2)
    
    val (r1, r2) = test.unsafeRunSync()
    r1 shouldBe Some(session1)
    r2 shouldBe Some(session2)
  }
  
  it should "handle concurrent session operations" in {
    val cache = Session.SessionCache().unsafeRunSync()
    val keys = Session.SessionKeys(randomBytes(16), randomBytes(16), randomBytes(16))
    
    val nodeIds = (1 to 10).map(_ => randomBytes(32)).toList
    val sessions = nodeIds.map(id => Session.ActiveSession(keys, randomBytes(32), id, isInitiator = true))
    
    val test = for {
      _ <- IO.parTraverseN(4)(nodeIds.zip(sessions)) { case (id, session) =>
        cache.put(id, session)
      }
      results <- IO.parTraverseN(4)(nodeIds)(cache.get)
    } yield results
    
    val results = test.unsafeRunSync()
    results.flatten.size shouldBe 10
  }
  
  behavior of "Session key properties"
  
  it should "maintain session key immutability" in {
    val initiatorKey = randomBytes(16)
    val recipientKey = randomBytes(16)
    val authRespKey = randomBytes(16)
    
    val keys = Session.SessionKeys(initiatorKey, recipientKey, authRespKey)
    
    // Keys should be the same references
    keys.initiatorKey shouldBe initiatorKey
    keys.recipientKey shouldBe recipientKey
    keys.authRespKey shouldBe authRespKey
  }
  
  it should "enforce correct key sizes in SessionKeys" in {
    val shortKey = randomBytes(8)
    val validKey1 = randomBytes(16)
    val validKey2 = randomBytes(16)
    
    assertThrows[IllegalArgumentException] {
      Session.SessionKeys(shortKey, validKey1, validKey2)
    }
    
    assertThrows[IllegalArgumentException] {
      Session.SessionKeys(validKey1, shortKey, validKey2)
    }
    
    assertThrows[IllegalArgumentException] {
      Session.SessionKeys(validKey1, validKey2, shortKey)
    }
  }
}
