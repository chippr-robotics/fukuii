package com.chipprbots.scalanet.discovery.ethereum.v5

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scodec.bits.ByteVector
import java.security.SecureRandom
import scala.util.{Failure, Success}

class EncryptionSpec extends AnyFlatSpec with Matchers {
  
  private val random = new SecureRandom()
  
  def randomBytes(n: Int): ByteVector = {
    val bytes = Array.ofDim[Byte](n)
    random.nextBytes(bytes)
    ByteVector.view(bytes)
  }
  
  behavior of "AES-GCM encryption"
  
  it should "successfully encrypt and decrypt data" in {
    val key = randomBytes(16)
    val nonce = randomBytes(12)
    val plaintext = ByteVector("Hello, Discovery v5!".getBytes)
    val authData = ByteVector.empty
    
    val encrypted = Session.encrypt(key, nonce, plaintext, authData)
    encrypted.isSuccess shouldBe true
    
    val ciphertext = encrypted.get
    ciphertext should not equal plaintext
    
    val decrypted = Session.decrypt(key, nonce, ciphertext, authData)
    decrypted.isSuccess shouldBe true
    decrypted.get shouldBe plaintext
  }
  
  it should "encrypt different plaintexts to different ciphertexts with same key" in {
    val key = randomBytes(16)
    val nonce1 = randomBytes(12)
    val nonce2 = randomBytes(12)
    val plaintext = ByteVector("Test data".getBytes)
    val authData = ByteVector.empty
    
    val encrypted1 = Session.encrypt(key, nonce1, plaintext, authData).get
    val encrypted2 = Session.encrypt(key, nonce2, plaintext, authData).get
    
    encrypted1 should not equal encrypted2
  }
  
  it should "produce ciphertext larger than plaintext (includes auth tag)" in {
    val key = randomBytes(16)
    val nonce = randomBytes(12)
    val plaintext = ByteVector("Short".getBytes)
    val authData = ByteVector.empty
    
    val encrypted = Session.encrypt(key, nonce, plaintext, authData).get
    
    // AES-GCM adds a 16-byte authentication tag
    encrypted.size shouldBe (plaintext.size + 16)
  }
  
  it should "handle empty plaintext" in {
    val key = randomBytes(16)
    val nonce = randomBytes(12)
    val plaintext = ByteVector.empty
    val authData = ByteVector.empty
    
    val encrypted = Session.encrypt(key, nonce, plaintext, authData)
    encrypted.isSuccess shouldBe true
    
    val decrypted = Session.decrypt(key, nonce, encrypted.get, authData)
    decrypted.isSuccess shouldBe true
    decrypted.get shouldBe ByteVector.empty
  }
  
  it should "handle large plaintext" in {
    val key = randomBytes(16)
    val nonce = randomBytes(12)
    val plaintext = randomBytes(1024 * 10) // 10 KB
    val authData = ByteVector.empty
    
    val encrypted = Session.encrypt(key, nonce, plaintext, authData)
    encrypted.isSuccess shouldBe true
    
    val decrypted = Session.decrypt(key, nonce, encrypted.get, authData)
    decrypted.isSuccess shouldBe true
    decrypted.get shouldBe plaintext
  }
  
  behavior of "AES-GCM decryption failures"
  
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
  
  it should "fail decryption with wrong nonce" in {
    val key = randomBytes(16)
    val nonce1 = randomBytes(12)
    val nonce2 = randomBytes(12)
    val plaintext = ByteVector("Test data".getBytes)
    val authData = ByteVector.empty
    
    val encrypted = Session.encrypt(key, nonce1, plaintext, authData).get
    val decrypted = Session.decrypt(key, nonce2, encrypted, authData)
    
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
  
  it should "fail decryption when ciphertext is tampered with" in {
    val key = randomBytes(16)
    val nonce = randomBytes(12)
    val plaintext = ByteVector("Test data".getBytes)
    val authData = ByteVector.empty
    
    val encrypted = Session.encrypt(key, nonce, plaintext, authData).get
    
    // Tamper with the ciphertext
    val tampered = encrypted.update(0, (encrypted(0) ^ 0x01).toByte)
    
    val decrypted = Session.decrypt(key, nonce, tampered, authData)
    decrypted.isSuccess shouldBe false
  }
  
  it should "fail decryption when auth tag is tampered with" in {
    val key = randomBytes(16)
    val nonce = randomBytes(12)
    val plaintext = ByteVector("Test data".getBytes)
    val authData = ByteVector.empty
    
    val encrypted = Session.encrypt(key, nonce, plaintext, authData).get
    
    // Tamper with the last byte (part of auth tag)
    val lastIndex = encrypted.size - 1
    val tampered = encrypted.update(lastIndex, (encrypted(lastIndex) ^ 0xFF).toByte)
    
    val decrypted = Session.decrypt(key, nonce, tampered, authData)
    decrypted.isSuccess shouldBe false
  }
  
  it should "fail decryption when ciphertext is truncated" in {
    val key = randomBytes(16)
    val nonce = randomBytes(12)
    val plaintext = ByteVector("Test data".getBytes)
    val authData = ByteVector.empty
    
    val encrypted = Session.encrypt(key, nonce, plaintext, authData).get
    
    // Truncate ciphertext
    val truncated = encrypted.dropRight(1)
    
    val decrypted = Session.decrypt(key, nonce, truncated, authData)
    decrypted.isSuccess shouldBe false
  }
  
  behavior of "AES-GCM with additional authenticated data"
  
  it should "encrypt and decrypt with non-empty auth data" in {
    val key = randomBytes(16)
    val nonce = randomBytes(12)
    val plaintext = ByteVector("Secret message".getBytes)
    val authData = ByteVector("additional-authenticated-data".getBytes)
    
    val encrypted = Session.encrypt(key, nonce, plaintext, authData)
    encrypted.isSuccess shouldBe true
    
    val decrypted = Session.decrypt(key, nonce, encrypted.get, authData)
    decrypted.isSuccess shouldBe true
    decrypted.get shouldBe plaintext
  }
  
  it should "protect auth data integrity" in {
    val key = randomBytes(16)
    val nonce = randomBytes(12)
    val plaintext = ByteVector("Message".getBytes)
    val authData = ByteVector("metadata".getBytes)
    
    val encrypted = Session.encrypt(key, nonce, plaintext, authData).get
    
    // Try to decrypt with modified auth data
    val modifiedAuthData = ByteVector("modified".getBytes)
    val decrypted = Session.decrypt(key, nonce, encrypted, modifiedAuthData)
    
    decrypted.isSuccess shouldBe false
  }
  
  it should "handle auth data of varying sizes" in {
    val key = randomBytes(16)
    val nonce = randomBytes(12)
    val plaintext = ByteVector("Test".getBytes)
    
    val authDataSizes = List(0, 1, 16, 32, 100, 1000)
    
    authDataSizes.foreach { size =>
      val authData = randomBytes(size)
      
      val encrypted = Session.encrypt(key, nonce, plaintext, authData)
      encrypted.isSuccess shouldBe true
      
      val decrypted = Session.decrypt(key, nonce, encrypted.get, authData)
      decrypted.isSuccess shouldBe true
      decrypted.get shouldBe plaintext
    }
  }
  
  behavior of "AES-GCM input validation"
  
  it should "reject invalid key size" in {
    val shortKey = randomBytes(8) // Should be 16 bytes
    val nonce = randomBytes(12)
    val plaintext = ByteVector("Test".getBytes)
    val authData = ByteVector.empty
    
    assertThrows[IllegalArgumentException] {
      Session.encrypt(shortKey, nonce, plaintext, authData).get
    }
  }
  
  it should "reject invalid nonce size" in {
    val key = randomBytes(16)
    val shortNonce = randomBytes(8) // Should be 12 bytes
    val plaintext = ByteVector("Test".getBytes)
    val authData = ByteVector.empty
    
    assertThrows[IllegalArgumentException] {
      Session.encrypt(key, shortNonce, plaintext, authData).get
    }
  }
  
  it should "reject oversized key" in {
    val longKey = randomBytes(32) // Should be 16 bytes
    val nonce = randomBytes(12)
    val plaintext = ByteVector("Test".getBytes)
    val authData = ByteVector.empty
    
    assertThrows[IllegalArgumentException] {
      Session.encrypt(longKey, nonce, plaintext, authData).get
    }
  }
  
  behavior of "AES-GCM determinism"
  
  it should "produce deterministic output with same inputs" in {
    val key = randomBytes(16)
    val nonce = randomBytes(12)
    val plaintext = ByteVector("Deterministic test".getBytes)
    val authData = ByteVector("metadata".getBytes)
    
    val encrypted1 = Session.encrypt(key, nonce, plaintext, authData).get
    val encrypted2 = Session.encrypt(key, nonce, plaintext, authData).get
    
    encrypted1 shouldBe encrypted2
  }
  
  it should "produce different output when nonce changes" in {
    val key = randomBytes(16)
    val plaintext = ByteVector("Test".getBytes)
    val authData = ByteVector.empty
    
    val encrypted1 = Session.encrypt(key, randomBytes(12), plaintext, authData).get
    val encrypted2 = Session.encrypt(key, randomBytes(12), plaintext, authData).get
    
    encrypted1 should not equal encrypted2
  }
  
  behavior of "AES-GCM edge cases"
  
  it should "handle all-zero plaintext" in {
    val key = randomBytes(16)
    val nonce = randomBytes(12)
    val plaintext = ByteVector.fill(100)(0)
    val authData = ByteVector.empty
    
    val encrypted = Session.encrypt(key, nonce, plaintext, authData)
    encrypted.isSuccess shouldBe true
    
    val decrypted = Session.decrypt(key, nonce, encrypted.get, authData)
    decrypted.isSuccess shouldBe true
    decrypted.get shouldBe plaintext
  }
  
  it should "handle all-zero key and nonce" in {
    val key = ByteVector.fill(16)(0)
    val nonce = ByteVector.fill(12)(0)
    val plaintext = ByteVector("Test".getBytes)
    val authData = ByteVector.empty
    
    val encrypted = Session.encrypt(key, nonce, plaintext, authData)
    encrypted.isSuccess shouldBe true
    
    val decrypted = Session.decrypt(key, nonce, encrypted.get, authData)
    decrypted.isSuccess shouldBe true
    decrypted.get shouldBe plaintext
  }
  
  it should "handle maximum size plaintext (within packet limit)" in {
    val key = randomBytes(16)
    val nonce = randomBytes(12)
    // Maximum Discovery v5 packet size is 1280 bytes
    val plaintext = randomBytes(1200)
    val authData = ByteVector.empty
    
    val encrypted = Session.encrypt(key, nonce, plaintext, authData)
    encrypted.isSuccess shouldBe true
    
    val decrypted = Session.decrypt(key, nonce, encrypted.get, authData)
    decrypted.isSuccess shouldBe true
    decrypted.get shouldBe plaintext
  }
}
