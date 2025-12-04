package com.chipprbots.scalanet.discovery.ethereum.v5

import javax.crypto.Cipher
import javax.crypto.spec.{GCMParameterSpec, SecretKeySpec}
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import scodec.bits.ByteVector
import scala.util.Try
import cats.effect.{IO, Ref}
import cats.implicits._
import com.chipprbots.scalanet.discovery.hash.Keccak256

/** Session management for Discovery v5
  * 
  * Sessions provide encrypted communication between peers using:
  * - ECDH for key exchange
  * - HKDF for key derivation
  * - AES-GCM for encryption/decryption
  */
object Session {
  
  /** Session keys derived from handshake */
  case class SessionKeys(
    initiatorKey: ByteVector,
    recipientKey: ByteVector,
    authRespKey: ByteVector
  ) {
    require(initiatorKey.size == 16, "initiatorKey must be 16 bytes")
    require(recipientKey.size == 16, "recipientKey must be 16 bytes")
    require(authRespKey.size == 16, "authRespKey must be 16 bytes")
  }
  
  /** Active session with a peer */
  case class ActiveSession(
    keys: SessionKeys,
    localNodeId: ByteVector,
    remoteNodeId: ByteVector
  )
  
  /** Derive session keys using HKDF
    * 
    * @param ephemeralKey The shared secret from ECDH
    * @param localNodeId Local node ID (32 bytes)
    * @param remoteNodeId Remote node ID (32 bytes)
    * @param idNonce The challenge nonce from WHOAREYOU (16 bytes)
    * @return Session keys
    */
  def deriveKeys(
    ephemeralKey: ByteVector,
    localNodeId: ByteVector,
    remoteNodeId: ByteVector,
    idNonce: ByteVector
  ): SessionKeys = {
    require(localNodeId.size == 32, "localNodeId must be 32 bytes")
    require(remoteNodeId.size == 32, "remoteNodeId must be 32 bytes")
    require(idNonce.size == 16, "idNonce must be 16 bytes")
    
    // Combine IDs for key material
    val info = (localNodeId ++ remoteNodeId ++ idNonce).toArray
    
    // Use HKDF to derive keys
    val hkdf = new HKDFBytesGenerator(new SHA256Digest())
    val params = new HKDFParameters(ephemeralKey.toArray, null, info)
    hkdf.init(params)
    
    // Derive three 16-byte keys
    val initiatorKey = new Array[Byte](16)
    val recipientKey = new Array[Byte](16)
    val authRespKey = new Array[Byte](16)
    
    hkdf.generateBytes(initiatorKey, 0, 16)
    hkdf.generateBytes(recipientKey, 0, 16)
    hkdf.generateBytes(authRespKey, 0, 16)
    
    SessionKeys(
      ByteVector.view(initiatorKey),
      ByteVector.view(recipientKey),
      ByteVector.view(authRespKey)
    )
  }
  
  /** Encrypt message data using AES-128-GCM
    * 
    * @param key The encryption key (16 bytes)
    * @param nonce The nonce/IV (12 bytes)
    * @param plaintext The data to encrypt
    * @param authData Additional authenticated data
    * @return Encrypted ciphertext with authentication tag
    */
  def encrypt(
    key: ByteVector,
    nonce: ByteVector,
    plaintext: ByteVector,
    authData: ByteVector
  ): Try[ByteVector] = Try {
    require(key.size == 16, "key must be 16 bytes for AES-128")
    require(nonce.size == 12, "nonce must be 12 bytes for GCM")
    
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    val keySpec = new SecretKeySpec(key.toArray, "AES")
    val gcmSpec = new GCMParameterSpec(128, nonce.toArray) // 128-bit auth tag
    
    cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec)
    
    // Add additional authenticated data
    if (authData.nonEmpty) {
      cipher.updateAAD(authData.toArray)
    }
    
    val ciphertext = cipher.doFinal(plaintext.toArray)
    ByteVector.view(ciphertext)
  }
  
  /** Decrypt message data using AES-128-GCM
    * 
    * @param key The decryption key (16 bytes)
    * @param nonce The nonce/IV (12 bytes)
    * @param ciphertext The encrypted data (includes auth tag)
    * @param authData Additional authenticated data
    * @return Decrypted plaintext
    */
  def decrypt(
    key: ByteVector,
    nonce: ByteVector,
    ciphertext: ByteVector,
    authData: ByteVector
  ): Try[ByteVector] = Try {
    require(key.size == 16, "key must be 16 bytes for AES-128")
    require(nonce.size == 12, "nonce must be 12 bytes for GCM")
    
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    val keySpec = new SecretKeySpec(key.toArray, "AES")
    val gcmSpec = new GCMParameterSpec(128, nonce.toArray) // 128-bit auth tag
    
    cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)
    
    // Add additional authenticated data
    if (authData.nonEmpty) {
      cipher.updateAAD(authData.toArray)
    }
    
    val plaintext = cipher.doFinal(ciphertext.toArray)
    ByteVector.view(plaintext)
  }
  
  /** Generate a random ID nonce for WHOAREYOU challenge */
  def randomIdNonce: ByteVector = {
    val random = new scala.util.Random()
    ByteVector.view(Array.fill(16)(random.nextInt(256).toByte))
  }
  
  /** Compute node ID from public key using keccak256 */
  def nodeIdFromPublicKey(publicKey: ByteVector): ByteVector = {
    require(publicKey.size == 64, "publicKey must be 64 bytes (uncompressed)")
    val hash = Keccak256(publicKey.bits)
    hash.value.bytes
  }
  
  /** Session cache to store active sessions with peers */
  trait SessionCache {
    def get(nodeId: ByteVector): IO[Option[ActiveSession]]
    def put(nodeId: ByteVector, session: ActiveSession): IO[Unit]
    def remove(nodeId: ByteVector): IO[Unit]
    def clear: IO[Unit]
  }
  
  /** In-memory session cache implementation */
  object SessionCache {
    def apply(): IO[SessionCache] = 
      Ref[IO].of(Map.empty[ByteVector, ActiveSession]).map { ref =>
        new SessionCache {
          override def get(nodeId: ByteVector): IO[Option[ActiveSession]] =
            ref.get.map(_.get(nodeId))
            
          override def put(nodeId: ByteVector, session: ActiveSession): IO[Unit] =
            ref.update(_ + (nodeId -> session))
            
          override def remove(nodeId: ByteVector): IO[Unit] =
            ref.update(_ - nodeId)
            
          override def clear: IO[Unit] =
            ref.set(Map.empty)
        }
      }
  }
}
