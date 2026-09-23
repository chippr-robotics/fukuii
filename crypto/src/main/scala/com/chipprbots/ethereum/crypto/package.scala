package com.chipprbots.ethereum

import java.nio.charset.StandardCharsets
import java.security.SecureRandom

import org.apache.pekko.util.ByteString

import org.bouncycastle.asn1.sec.SECNamedCurves
import org.bouncycastle.asn1.x9.X9ECParameters
import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.crypto.digests.KeccakDigest
import org.bouncycastle.crypto.digests.RIPEMD160Digest
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.ECKeyPairGenerator
import org.bouncycastle.crypto.generators.PKCS5S2ParametersGenerator
import org.bouncycastle.crypto.generators.SCrypt
import org.bouncycastle.crypto.params.*
import org.bouncycastle.math.ec.ECPoint
import org.bouncycastle.util.encoders.Hex

import com.chipprbots.ethereum.utils.ByteUtils

package object crypto {

  val curveParams: X9ECParameters = SECNamedCurves.getByName("secp256k1")
  val curve: ECDomainParameters =
    new ECDomainParameters(curveParams.getCurve, curveParams.getG, curveParams.getN, curveParams.getH)

  private val keccakSize = 512
  val kec512 = new KeccakDigest(keccakSize)

  // Spec 007 US1 (FR-001/FR-002): reuse one KeccakDigest(256) per thread instead of allocating
  // one per hash (keccak is the single hottest op — once per trie node across millions of nodes).
  //
  // Byte-for-byte safety (verified via `javap -c` on the pinned bcprov-jdk18on 1.84 jar,
  // project/Dependencies.scala:139): `reset()` calls `init(fixedOutputLength)`, the SAME method
  // the constructor calls with the bit-length; for fixed length 256 this runs the identical
  // `init(256) -> initSponge(1600 - 512)` path which zeroes state[25], fills dataQueue[192] with 0,
  // sets rate, bitsInQueue=0, squeezing=false, fixedOutputLength. A reset digest is therefore
  // observably identical to `new KeccakDigest(256)` for output purposes ⇒ byte-identical hashes.
  //
  // Thread-confinement guard (research §2.c / R4/R5): hashing runs on PLATFORM-THREAD Pekko
  // dispatchers ONLY. Do NOT call kec256 on a per-task virtual-thread executor (Thread.ofVirtual /
  // newVirtualThreadPerTaskExecutor) without revisiting this design — millions of vthreads would
  // each lazily allocate an un-pooled digest. The digest reference MUST NEVER be returned, stored
  // in a field, or captured by a Future/closure (INV-2): it must not leave its kec256 method body.
  private val kec256Digest: ThreadLocal[KeccakDigest] =
    ThreadLocal.withInitial(() => new KeccakDigest(256))

  def kec256(input: Array[Byte], start: Int, length: Int): Array[Byte] = {
    val d = kec256Digest.get()
    d.reset() // reset-on-entry (INV-1/FR-002): clears any state left by a prior aborted hash
    val output = Array.ofDim[Byte](d.getDigestSize)
    d.update(input, start, length)
    d.doFinal(output, 0)
    output
  }

  // Spec 007 US3 / T019 (FR-010-gated): dedicated single-Array overload.
  //
  // Without this, every single-arg `kec256(x: Array[Byte])` bound to the varargs overload below,
  // allocating an `ArraySeq`/`Seq` wrapper per call on top of the 32-byte output. This fixed-arity
  // overload removes that wrapper for all single-Array-arg sites at once.
  //
  // Overload resolution (Scala 3): a fixed-arity method is strictly more specific than a varargs
  // one, so every existing `kec256(x: Array[Byte])` re-binds here WITHOUT ambiguity. Output is
  // byte-identical — the same single argument is delegated to the 3-arg form which hashes the same
  // bytes. Genuine multi-arg callers (e.g. `kec256(a, b)`) still bind to the varargs overload.
  def kec256(input: Array[Byte]): Array[Byte] =
    kec256(input, 0, input.length)

  /** keccak-256 of `prefix` followed by `zeroCount` zero bytes, without materialising the zeros. Byte-identical to
    * `kec256(prefix ++ new Array[Byte](zeroCount))`; exists so the EVM can hash a paid-for, never-written memory region
    * of gigabyte scale (SHA3) in constant heap.
    */
  def kec256ZeroPadded(prefix: Array[Byte], zeroCount: Int): Array[Byte] = {
    require(zeroCount >= 0, s"negative zero padding: $zeroCount")
    val d = kec256Digest.get()
    d.reset() // reset-on-entry (INV-1/FR-002)
    val output = Array.ofDim[Byte](d.getDigestSize)
    d.update(prefix, 0, prefix.length)
    var left = zeroCount
    while (left > 0) {
      val n = math.min(left, ZeroChunk.length)
      d.update(ZeroChunk, 0, n)
      left -= n
    }
    d.doFinal(output, 0)
    output
  }

  // Read-only source of zero bytes for kec256ZeroPadded. Never written, never handed out.
  private val ZeroChunk: Array[Byte] = new Array[Byte](64 * 1024)

  def kec256(input: Array[Byte]*): Array[Byte] = {
    val d = kec256Digest.get()
    d.reset() // reset-on-entry (INV-1/FR-002): clears any state left by a prior aborted hash
    val output = Array.ofDim[Byte](d.getDigestSize)
    input.foreach(i => d.update(i, 0, i.length))
    d.doFinal(output, 0)
    output
  }

  def kec256(input: ByteString): ByteString =
    ByteString(kec256(input.toArray))

  /** Test-only hook for the spec-007 bounded-footprint invariant (FR-009/SC-006, INV-2): returns the
    * identity hash of THIS thread's reused KeccakDigest WITHOUT exposing the digest reference itself,
    * so a test can assert exactly one digest instance per thread and none shared across threads. Do not
    * use in production code; it must never be widened to return the digest (that would violate INV-2).
    */
  private[crypto] def kec256DigestIdentityForCurrentThread(): Int =
    System.identityHashCode(kec256Digest.get())

  /** Test-only hook for the spec-007 reset-after-abort parity check (INV-1/FR-002): dirties THIS
    * thread's reused digest with `update(...)` and then throws BEFORE `doFinal` — exactly the
    * aborted-mid-update window that `doFinal`'s success-path-only reset never covers. The digest
    * reference never escapes (INV-2). The test calls this, catches the throw, then asserts the NEXT
    * `kec256` on the SAME thread is correct (proving reset-on-entry discarded the stale bytes).
    */
  private[crypto] def dirtyThreadDigestThenThrow(): Unit = {
    val d = kec256Digest.get()
    d.update(Array[Byte](1, 2, 3, 4, 5), 0, 5)
    throw new RuntimeException("aborted between update and doFinal (test hook)")
  }

  def kec256PoW(header: Array[Byte], nonce: Array[Byte]): Array[Byte] = {
    val d = kec256Digest.get()
    d.reset() // reset-on-entry (INV-1/FR-002): clears any state left by a prior aborted hash
    d.update(header, 0, header.length)
    d.update(nonce, 0, nonce.length)
    val output = Array.ofDim[Byte](32)
    d.doFinal(output, 0)
    output
  }

  def kec512(input: Array[Byte]): Array[Byte] = kec512.synchronized {
    val out = Array.ofDim[Byte](kec512.getDigestSize)
    kec512.update(input, 0, input.length)
    kec512.doFinal(out, 0)
    out
  }

  def generateKeyPair(secureRandom: SecureRandom): AsymmetricCipherKeyPair = {
    val generator = new ECKeyPairGenerator
    generator.init(new ECKeyGenerationParameters(curve, secureRandom))
    generator.generateKeyPair()
  }

  def secureRandomByteString(secureRandom: SecureRandom, length: Int): ByteString =
    ByteString(secureRandomByteArray(secureRandom, length))

  def secureRandomByteArray(secureRandom: SecureRandom, length: Int): Array[Byte] = {
    val bytes = Array.ofDim[Byte](length)
    secureRandom.nextBytes(bytes)
    bytes
  }

  /** @return
    *   (privateKey, publicKey) pair. The public key will be uncompressed and have its prefix dropped.
    */
  def keyPairToByteArrays(keyPair: AsymmetricCipherKeyPair): (Array[Byte], Array[Byte]) = {
    val prvKey = ByteUtils.bigIntegerToBytes(keyPair.getPrivate.asInstanceOf[ECPrivateKeyParameters].getD, 32)
    val pubKey = keyPair.getPublic.asInstanceOf[ECPublicKeyParameters].getQ.getEncoded(false).tail
    (prvKey, pubKey)
  }

  def keyPairToByteStrings(keyPair: AsymmetricCipherKeyPair): (ByteString, ByteString) = {
    val (prv, pub) = keyPairToByteArrays(keyPair)
    (ByteString(prv), ByteString(pub))
  }

  /** Decode an EC point from its encoded representation and validate it is on the curve. Defense-in-depth for
    * CVE-2025-24883 / CVE-2026-26314 / CVE-2026-26315. BouncyCastle's decodePoint already validates the curve equation,
    * but isValid() additionally checks point order (not a small subgroup point) and rejects the point at infinity.
    */
  def decodeAndValidatePoint(encoded: Array[Byte]): ECPoint = {
    val point = curve.getCurve.decodePoint(encoded)
    if (!point.isValid || point.isInfinity)
      throw new IllegalArgumentException("Invalid EC point: not on curve or point at infinity")
    point
  }

  def keyPairFromPrvKey(prvKeyBytes: Array[Byte]): AsymmetricCipherKeyPair = {
    val privateKey = BigInt(1, prvKeyBytes)
    keyPairFromPrvKey(privateKey)
  }

  def keyPairFromPrvKey(prvKeyBytes: ByteString): AsymmetricCipherKeyPair = {
    val privateKey = BigInt(1, prvKeyBytes.toArray)
    keyPairFromPrvKey(privateKey)
  }

  def keyPairFromPrvKey(prvKey: BigInt): AsymmetricCipherKeyPair = {
    val publicKey = curve.getG.multiply(prvKey.bigInteger).normalize()
    new AsymmetricCipherKeyPair(
      new ECPublicKeyParameters(publicKey, curve),
      new ECPrivateKeyParameters(prvKey.bigInteger, curve)
    )
  }

  def pubKeyFromKeyPair(keypair: AsymmetricCipherKeyPair): Array[Byte] =
    keyPairToByteArrays(keypair)._2

  def pubKeyFromPrvKey(prvKey: Array[Byte]): Array[Byte] =
    keyPairToByteArrays(keyPairFromPrvKey(prvKey))._2

  def pubKeyFromPrvKey(prvKey: ByteString): ByteString =
    ByteString(pubKeyFromPrvKey(prvKey.toArray))

  def newRandomKeyPairAsStrings(secureRandom: SecureRandom = new SecureRandom): (String, String) = {
    val keyPair = generateKeyPair(secureRandom)
    val (prv, pub) = keyPairToByteArrays(keyPair)
    (Hex.toHexString(prv), Hex.toHexString(pub))
  }

  def ripemd160(input: Array[Byte]): Array[Byte] = {
    val digest = new RIPEMD160Digest
    digest.update(input, 0, input.length)
    val out = Array.ofDim[Byte](digest.getDigestSize)
    digest.doFinal(out, 0)
    out
  }

  def ripemd160(input: ByteString): ByteString =
    ByteString(ripemd160(input.toArray))

  def sha256(input: Array[Byte]): Array[Byte] = {
    val digest = new SHA256Digest()
    val out = Array.ofDim[Byte](digest.getDigestSize)
    digest.update(input, 0, input.size)
    digest.doFinal(out, 0)
    out
  }

  def sha256(input: ByteString): ByteString =
    ByteString(sha256(input.toArray))

  def pbkdf2HMacSha256(passphrase: String, salt: ByteString, c: Int, dklen: Int): ByteString = {
    val generator = new PKCS5S2ParametersGenerator(new SHA256Digest())
    generator.init(passphrase.getBytes(StandardCharsets.UTF_8), salt.toArray, c)
    val key = generator.generateDerivedMacParameters(dklen * 8).asInstanceOf[KeyParameter]
    ByteString(key.getKey)
  }

  def scrypt(passphrase: String, salt: ByteString, n: Int, r: Int, p: Int, dklen: Int): ByteString = {
    val key = SCrypt.generate(passphrase.getBytes(StandardCharsets.UTF_8), salt.toArray, n, r, p, dklen)
    ByteString(key)
  }
}
