package com.chipprbots.ethereum.network.discovery

import scala.util.Random

import com.chipprbots.scalanet.discovery.crypto.PublicKey
import com.chipprbots.scalanet.discovery.crypto.Signature
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scodec.bits.BitVector

import com.chipprbots.ethereum.testing.Tags.*

class Secp256k1SigAlgSpec extends AnyFlatSpec with Matchers:
  behavior.of("Secp256k1SigAlg")

  val sigalg = new Secp256k1SigAlg

  def randomData: BitVector =
    val size = 1 + Random.nextInt(999)
    val bytes = Array.ofDim[Byte](size)
    Random.nextBytes(bytes)
    BitVector(bytes)

  trait SignatureFixture:
    val (publicKey, privateKey) = sigalg.newKeyPair
    val data = randomData

  it should "generate new keypairs" taggedAs (UnitTest, NetworkTest) in new SignatureFixture:
    publicKey.value.toByteVector should have size 64
    privateKey.value.toByteVector should have size 32

  it should "compress a public key" taggedAs (UnitTest, NetworkTest) in new SignatureFixture:
    val compressedPublicKey: PublicKey.Tagged = sigalg.compressPublicKey(publicKey)
    compressedPublicKey.value.toByteVector should have size 33

  it should "not compress an alredy compressed public key" taggedAs (UnitTest, NetworkTest) in new SignatureFixture:
    val compressedPublicKey: PublicKey.Tagged = sigalg.compressPublicKey(publicKey)
    sigalg.compressPublicKey(compressedPublicKey) shouldBe compressedPublicKey

  it should "decompress a compressed public key" taggedAs (UnitTest, NetworkTest) in new SignatureFixture:
    val compressedPublicKey: PublicKey.Tagged = sigalg.compressPublicKey(publicKey)
    sigalg.decompressPublicKey(compressedPublicKey) shouldBe publicKey

  it should "not decompress a uncompressed public key" taggedAs (UnitTest, NetworkTest) in new SignatureFixture:
    sigalg.decompressPublicKey(publicKey) shouldBe publicKey

  it should "turn a private key into a public key" taggedAs (UnitTest, NetworkTest) in new SignatureFixture:
    sigalg.toPublicKey(privateKey) shouldBe publicKey

  it should "sign some data" taggedAs (UnitTest, NetworkTest) in new SignatureFixture:
    val signature: Signature.Tagged = sigalg.sign(privateKey, data)
    signature.value.toByteVector should have size 65

  it should "verify a full signature" taggedAs (UnitTest, NetworkTest) in new SignatureFixture:
    val signature: Signature.Tagged = sigalg.sign(privateKey, data)
    sigalg.verify(publicKey, signature, data) shouldBe true

  it should "not verify a signature on altered data" taggedAs (UnitTest, NetworkTest) in new SignatureFixture:
    val signature: Signature.Tagged = sigalg.sign(privateKey, data)
    sigalg.verify(publicKey, signature, data.reverse) shouldBe false

  it should "verify a signature without the recovery ID" taggedAs (UnitTest, NetworkTest) in new SignatureFixture:
    val signature: Signature.Tagged = sigalg.sign(privateKey, data)
    val sigWithoutV: Signature.Tagged = sigalg.removeRecoveryId(signature)
    // This is a situation when we recovered the public key from the packet,
    // and we want to use it to verify the signature in the ENR.
    sigalg.verify(publicKey, sigWithoutV, data) shouldBe true

  it should "verify a signature without the recovery ID based on a compressed public key" taggedAs (
    UnitTest,
    NetworkTest
  ) in new SignatureFixture:
    val signature: Signature.Tagged = sigalg.sign(privateKey, data)
    val compressedPublicKey: PublicKey.Tagged = sigalg.compressPublicKey(publicKey)
    val sigWithoutV: Signature.Tagged = sigalg.removeRecoveryId(signature)
    // This is a situation when we want to verify the signature in an ENR
    // based on the compressed public key coming in the ENR itself.
    sigalg.verify(compressedPublicKey, sigWithoutV, data) shouldBe true

  it should "recover the public key from a full signature" taggedAs (UnitTest, NetworkTest) in new SignatureFixture:
    val signature: Signature.Tagged = sigalg.sign(privateKey, data)
    sigalg.recoverPublicKey(signature, data).require shouldBe publicKey

  // Task #52: one inbound datagram was recovered up to three times (netty-thread responder, server channel, client
  // channel). Recovery is a pure function of (signature, hash), so repeats are served from a bounded cache.
  it should "serve a repeated recovery from its cache with the same key" taggedAs (UnitTest, NetworkTest) in
    new SignatureFixture:
      val fresh = new Secp256k1SigAlg
      val signature: Signature.Tagged = fresh.sign(privateKey, data)
      fresh.recoverPublicKey(signature, data).require shouldBe publicKey
      fresh.recoveredKeyCacheSize shouldBe 1
      fresh.recoverPublicKey(signature, data).require shouldBe publicKey
      fresh
        .recoverPublicKeyFromHash(signature, BitVector(com.chipprbots.ethereum.crypto.kec256(data.toByteArray)))
        .require shouldBe publicKey
      fresh.recoveredKeyCacheSize shouldBe 1

  it should "not let a cached recovery answer for different data" taggedAs (UnitTest, NetworkTest) in
    new SignatureFixture:
      val fresh = new Secp256k1SigAlg
      val signature: Signature.Tagged = fresh.sign(privateKey, data)
      fresh.recoverPublicKey(signature, data).require shouldBe publicKey
      val other = data ++ BitVector.fromByte(1)
      fresh.recoverPublicKey(signature, other).toOption should not contain publicKey

  it should "evict the least recently used entry once full" taggedAs (UnitTest, NetworkTest) in {
    val lru = new Secp256k1SigAlg.BoundedLru[Int, String](2)
    lru.put(1, "a")
    lru.put(2, "b")
    lru.get(1) shouldBe Some("a") // 1 is now the most recently used
    lru.put(3, "c")
    lru.get(2) shouldBe None
    lru.get(1) shouldBe Some("a")
    lru.get(3) shouldBe Some("c")
    lru.entryCount shouldBe 2
  }
