package com.chipprbots.scalanet.discovery.ethereum.v5

import java.net.InetSocketAddress
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.{GCMParameterSpec, SecretKeySpec}

import cats.effect.{IO, Ref}
import cats.implicits._
import org.bouncycastle.asn1.sec.SECNamedCurves
import org.bouncycastle.crypto.agreement.ECDHBasicAgreement
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.{ECDomainParameters, ECPrivateKeyParameters, ECPublicKeyParameters, HKDFParameters}
import org.bouncycastle.jcajce.provider.digest.SHA256
import scodec.bits.ByteVector

import scala.util.Try

import com.chipprbots.scalanet.discovery.hash.Keccak256

/** discv5 session crypto per discv5-wire.md.
  *
  * Three core primitives:
  *   - [[ecdh]]: 33-byte compressed shared secret over secp256k1
  *   - [[deriveKeys]]: HKDF-SHA256 with `IKM = ecdh`, `salt = challenge bytes`,
  *     `info = "discovery v5 key agreement" ++ initiatorId ++ recipientId`,
  *     output 32 bytes split into `(writeKey, readKey)`. The recipient flips
  *     the pair on its side via [[Session.flip]].
  *   - [[idNonceHash]]: `sha256("discovery v5 identity proof" || challenge ||
  *     ephPubkey || destID)`. The hash is the input to ECDSA when proving
  *     the initiator's identity in the handshake.
  *
  * Plus AES-128-GCM helpers used to encrypt/decrypt the per-message ciphertext.
  *
  * Session state is keyed on `(NodeId, InetSocketAddress)` so that the same
  * peer probing from a new IP correctly hits a session miss and triggers a
  * fresh WHOAREYOU — required for hive's `PingMultiIP` test.
  */
object Session {

  // ---- Constants ----------------------------------------------------------

  val AesKeySize: Int = 16
  val GcmNonceSize: Int = 12
  val GcmTagBits: Int = 128
  val SharedSecretSize: Int = 33 // compressed point: prefix byte + 32-byte X
  val IdNonceSize: Int = 16
  val NodeIdSize: Int = 32

  private val KeyAgreementInfo: ByteVector =
    ByteVector.view("discovery v5 key agreement".getBytes("US-ASCII"))

  private val IdProofTag: ByteVector =
    ByteVector.view("discovery v5 identity proof".getBytes("US-ASCII"))

  // ---- Session keys + cache key types ------------------------------------

  /** Session keys derived from a completed handshake. The initiator's
    * `writeKey` equals the recipient's `readKey` and vice versa, so [[flip]]
    * gives the recipient-side view. */
  final case class Keys(writeKey: ByteVector, readKey: ByteVector) {
    require(writeKey.size == AesKeySize.toLong, s"writeKey must be $AesKeySize bytes")
    require(readKey.size == AesKeySize.toLong, s"readKey must be $AesKeySize bytes")
    def flip: Keys = Keys(writeKey = readKey, readKey = writeKey)
  }

  /** A live session with a peer. The peer is identified by `(nodeId, addr)`
    * — same nodeId from a new addr is treated as a session miss per spec. */
  final case class Session(
      keys: Keys,
      lastSeenMillis: Long
  )

  /** Map key for [[SessionCache]]. */
  final case class SessionId(nodeId: ByteVector, addr: InetSocketAddress)

  // ---- HKDF key derivation ------------------------------------------------

  /** Derive (writeKey, readKey) for the *initiator*. The recipient should
    * call this same function and then [[Keys.flip]] the result.
    *
    * @param ephemeralPrivate the initiator's freshly-generated private key (32 bytes)
    * @param peerPublic       the recipient's public key (64 bytes uncompressed)
    * @param initiatorNodeId  the initiator's node id (32 bytes)
    * @param recipientNodeId  the recipient's node id (32 bytes)
    * @param challengeBytes   the entire WHOAREYOU packet bytes (the
    *                         "challenge data") — used as the HKDF salt.
    */
  def deriveKeys(
      ephemeralPrivate: ByteVector,
      peerPublic: ByteVector,
      initiatorNodeId: ByteVector,
      recipientNodeId: ByteVector,
      challengeBytes: ByteVector
  ): Keys = {
    require(initiatorNodeId.size == NodeIdSize.toLong, s"initiatorNodeId must be $NodeIdSize bytes")
    require(recipientNodeId.size == NodeIdSize.toLong, s"recipientNodeId must be $NodeIdSize bytes")

    val sharedSecret = ecdh(ephemeralPrivate, peerPublic)
    val info = KeyAgreementInfo ++ initiatorNodeId ++ recipientNodeId

    val hkdf = new HKDFBytesGenerator(new SHA256Digest())
    hkdf.init(new HKDFParameters(sharedSecret.toArray, challengeBytes.toArray, info.toArray))

    val out = new Array[Byte](AesKeySize * 2)
    hkdf.generateBytes(out, 0, out.length)

    Keys(
      writeKey = ByteVector.view(out, 0, AesKeySize),
      readKey = ByteVector.view(out, AesKeySize, AesKeySize)
    )
  }

  // ---- ID-nonce hash + signature ------------------------------------------

  /** The hash whose ECDSA signature appears in the handshake's auth-data,
    * proving the initiator owns the public key that derived their nodeID. */
  def idNonceHash(
      challengeBytes: ByteVector,
      ephemeralPubkey: ByteVector,
      destNodeId: ByteVector
  ): ByteVector = {
    require(destNodeId.size == NodeIdSize.toLong, s"destNodeId must be $NodeIdSize bytes")
    val md = new SHA256.Digest()
    md.update(IdProofTag.toArray)
    md.update(challengeBytes.toArray)
    md.update(ephemeralPubkey.toArray)
    md.update(destNodeId.toArray)
    ByteVector.view(md.digest())
  }

  // ---- ECDH (compressed shared secret) ------------------------------------

  /** secp256k1 ECDH agreement returning a 33-byte compressed point.
    *
    * geth's reference implementation: scalar-multiply the peer's pubkey by
    * our private scalar, then encode the resulting point as `prefix || X`
    * where `prefix = 0x02` if Y is even, `0x03` if Y is odd.
    *
    * Accepts either compressed (33 bytes, prefix `0x02`/`0x03` + X) or
    * uncompressed (64 bytes, X || Y) peer pubkey — discv5 wire form is
    * compressed but v4 callers may still pass 64-byte uncompressed keys.
    *
    * @param privateKey our 32-byte secp256k1 private scalar
    * @param peerPublic peer's pubkey, 33-byte compressed or 64-byte uncompressed
    */
  def ecdh(privateKey: ByteVector, peerPublic: ByteVector): ByteVector = {
    require(privateKey.size == 32L, s"privateKey must be 32 bytes, got ${privateKey.size}")
    require(
      peerPublic.size == 33L || peerPublic.size == 64L,
      s"peerPublic must be 33 (compressed) or 64 (uncompressed) bytes, got ${peerPublic.size}"
    )

    val curveParams = SECNamedCurves.getByName("secp256k1")
    val domain = new ECDomainParameters(
      curveParams.getCurve,
      curveParams.getG,
      curveParams.getN,
      curveParams.getH
    )

    val pubBytes =
      if (peerPublic.size == 33L) peerPublic.toArray
      else (0x04.toByte +: peerPublic.toArray)
    val pubPoint = domain.getCurve.decodePoint(pubBytes)

    val privScalar = BigInt(1, privateKey.toArray).bigInteger
    val sharedPoint = pubPoint.multiply(privScalar).normalize()

    val xBytes = sharedPoint.getAffineXCoord.getEncoded
    require(xBytes.length == 32, s"shared X coord should be 32 bytes, got ${xBytes.length}")
    val yIsOdd = sharedPoint.getAffineYCoord.toBigInteger.testBit(0)
    val prefix: Byte = if (yIsOdd) 0x03.toByte else 0x02.toByte
    ByteVector(prefix +: xBytes)
  }

  // ---- AES-GCM encrypt / decrypt ------------------------------------------

  def encrypt(
      key: ByteVector,
      nonce: ByteVector,
      plaintext: ByteVector,
      aad: ByteVector
  ): Try[ByteVector] = Try {
    require(key.size == AesKeySize.toLong, s"key must be $AesKeySize bytes")
    require(nonce.size == GcmNonceSize.toLong, s"nonce must be $GcmNonceSize bytes")
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(
      Cipher.ENCRYPT_MODE,
      new SecretKeySpec(key.toArray, "AES"),
      new GCMParameterSpec(GcmTagBits, nonce.toArray)
    )
    if (aad.nonEmpty) cipher.updateAAD(aad.toArray)
    ByteVector.view(cipher.doFinal(plaintext.toArray))
  }

  def decrypt(
      key: ByteVector,
      nonce: ByteVector,
      ciphertext: ByteVector,
      aad: ByteVector
  ): Try[ByteVector] = Try {
    require(key.size == AesKeySize.toLong, s"key must be $AesKeySize bytes")
    require(nonce.size == GcmNonceSize.toLong, s"nonce must be $GcmNonceSize bytes")
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(
      Cipher.DECRYPT_MODE,
      new SecretKeySpec(key.toArray, "AES"),
      new GCMParameterSpec(GcmTagBits, nonce.toArray)
    )
    if (aad.nonEmpty) cipher.updateAAD(aad.toArray)
    ByteVector.view(cipher.doFinal(ciphertext.toArray))
  }

  // ---- Random helpers ------------------------------------------------------

  def randomIdNonce: ByteVector = {
    val bytes = Array.ofDim[Byte](IdNonceSize)
    new SecureRandom().nextBytes(bytes)
    ByteVector.view(bytes)
  }

  /** Compute the discv5 node ID for a public key — keccak256(pubkey64). */
  def nodeIdFromPublicKey(publicKey: ByteVector): ByteVector = {
    require(publicKey.size == 64L, s"publicKey must be 64 bytes uncompressed, got ${publicKey.size}")
    Keccak256(publicKey.bits).value.bytes
  }

  /** Derive a secp256k1 public key from a private scalar.
    *
    * @param privateKey 32-byte secp256k1 private scalar
    * @param compressed if true, return 33-byte compressed form; else 64-byte
    *                   uncompressed (X || Y, no `0x04` prefix)
    */
  def pubFromPriv(privateKey: ByteVector, compressed: Boolean = true): ByteVector = {
    require(privateKey.size == 32L, s"privateKey must be 32 bytes, got ${privateKey.size}")
    val curveParams = SECNamedCurves.getByName("secp256k1")
    val privScalar = BigInt(1, privateKey.toArray).bigInteger
    val pubPoint = curveParams.getG.multiply(privScalar).normalize()
    if (compressed) {
      val xBytes = pubPoint.getAffineXCoord.getEncoded
      val yIsOdd = pubPoint.getAffineYCoord.toBigInteger.testBit(0)
      val prefix: Byte = if (yIsOdd) 0x03.toByte else 0x02.toByte
      ByteVector(prefix +: xBytes)
    } else {
      val xBytes = pubPoint.getAffineXCoord.getEncoded
      val yBytes = pubPoint.getAffineYCoord.getEncoded
      ByteVector.view(xBytes ++ yBytes)
    }
  }

  // ---- Session cache ------------------------------------------------------

  trait SessionCache {
    def get(id: SessionId): IO[Option[Session]]
    def put(id: SessionId, session: Session): IO[Unit]
    def remove(id: SessionId): IO[Unit]
    def size: IO[Int]
  }

  object SessionCache {

    /** A simple in-memory cache backed by a [[Ref]]. Eviction policy is
      * "drop entries older than `maxAgeMillis`" applied lazily on insert. */
    def apply(maxAgeMillis: Long = 60L * 60 * 1000): IO[SessionCache] =
      Ref[IO].of(Map.empty[SessionId, Session]).map { ref =>
        new SessionCache {
          override def get(id: SessionId): IO[Option[Session]] = ref.get.map(_.get(id))

          override def put(id: SessionId, session: Session): IO[Unit] =
            ref.update { current =>
              val now = System.currentTimeMillis()
              val pruned = current.filter { case (_, s) => now - s.lastSeenMillis < maxAgeMillis }
              pruned + (id -> session)
            }

          override def remove(id: SessionId): IO[Unit] = ref.update(_ - id)

          override def size: IO[Int] = ref.get.map(_.size)
        }
      }
  }
}
