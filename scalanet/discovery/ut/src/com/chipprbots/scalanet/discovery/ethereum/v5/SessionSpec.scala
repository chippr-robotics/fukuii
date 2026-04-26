package com.chipprbots.scalanet.discovery.ethereum.v5

import scodec.bits.ByteVector

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Crypto tests for [[Session]] — gates wire-compat by including known-answer
  * vectors lifted directly from go-ethereum v1.16.4's
  * `p2p/discover/v5wire/crypto_test.go`:
  *
  *   - TestVector_ECDH        — expected 33-byte compressed shared secret
  *   - TestVector_KDF         — expected initiator/recipient keys after HKDF
  *
  * Plus structural round-trip tests:
  *   - HKDF derivation: initiator-side keys vs recipient-side keys (after flip) match
  *   - AES-GCM encrypt → decrypt round-trip
  *   - idNonceHash domain-separation tag is included
  */
class SessionSpec extends AnyFlatSpec with Matchers {

  // ---- TestVector_ECDH (geth) --------------------------------------------

  behavior.of("Session.ecdh")

  it should "match the geth TestVector_ECDH known answer (compressed pubkey input)" in {
    val privateKey = ByteVector.fromValidHex(
      "fb757dc581730490a1d7a00deea65e9b1936924caaea8f44d476014856b68736"
    )
    val peerPublicCompressed = ByteVector.fromValidHex(
      "039961e4c2356d61bedb83052c115d311acb3a96f5777296dcf297351130266231"
    )
    val expectedShared = ByteVector.fromValidHex(
      "033b11a2a1f214567e1537ce5e509ffd9b21373247f2a3ff6841f4976f53165e7e"
    )

    Session.ecdh(privateKey, peerPublicCompressed) shouldBe expectedShared
  }

  it should "produce a symmetric shared secret regardless of which side computes it" in {
    // Use the test keys from geth's encoding_test.go.
    val privA = ByteVector.fromValidHex("eef77acb6c6a6eebc5b363a475ac583ec7eccdb42b6481424c60f59aa326547f")
    val privB = ByteVector.fromValidHex("66fb62bfbd66b9177a138c1e5cddbe4f7c30c343e94e68df8769459cb1cde628")
    val pubA = Session.pubFromPriv(privA, compressed = true)
    val pubB = Session.pubFromPriv(privB, compressed = true)

    val sharedAB = Session.ecdh(privA, pubB)
    val sharedBA = Session.ecdh(privB, pubA)
    sharedAB shouldBe sharedBA
    sharedAB.size shouldBe Session.SharedSecretSize.toLong
  }

  // ---- TestVector_KDF (geth) ---------------------------------------------

  behavior.of("Session.deriveKeys")

  it should "match the geth TestVector_KDF known-answer initiator/recipient keys" in {
    val ephPriv = ByteVector.fromValidHex(
      "fb757dc581730490a1d7a00deea65e9b1936924caaea8f44d476014856b68736"
    )
    // testKeyB from geth encoding_test.go
    val destPriv = ByteVector.fromValidHex(
      "66fb62bfbd66b9177a138c1e5cddbe4f7c30c343e94e68df8769459cb1cde628"
    )
    val destPubUncompressed = Session.pubFromPriv(destPriv, compressed = false)

    // testKeyA from geth encoding_test.go
    val nodeAPriv = ByteVector.fromValidHex(
      "eef77acb6c6a6eebc5b363a475ac583ec7eccdb42b6481424c60f59aa326547f"
    )
    val nodeAPubUncompressed = Session.pubFromPriv(nodeAPriv, compressed = false)
    val nodeAId = Session.nodeIdFromPublicKey(nodeAPubUncompressed)
    val nodeBId = Session.nodeIdFromPublicKey(destPubUncompressed)

    val challenge = ByteVector.fromValidHex(
      "00000000000000000000000000000000" +
        "6469736376350001010102030405060708090a0b0c00180102030405060708090a0b0c0d0e0f100000000000000000"
    )

    val keys = Session.deriveKeys(ephPriv, destPubUncompressed, nodeAId, nodeBId, challenge)
    keys.writeKey shouldBe ByteVector.fromValidHex("dccc82d81bd610f4f76d3ebe97a40571")
    keys.readKey shouldBe ByteVector.fromValidHex("ac74bb8773749920b0d3a8881c173ec5")
  }

  it should "produce matching keys on both sides — initiator's writeKey == recipient's readKey after flip" in {
    val ephPriv = ByteVector.fromValidHex(
      "fb757dc581730490a1d7a00deea65e9b1936924caaea8f44d476014856b68736"
    )
    val destPriv = ByteVector.fromValidHex(
      "66fb62bfbd66b9177a138c1e5cddbe4f7c30c343e94e68df8769459cb1cde628"
    )
    val destPubUncompressed = Session.pubFromPriv(destPriv, compressed = false)
    val ephPubUncompressed = Session.pubFromPriv(ephPriv, compressed = false)

    val initiatorId = ByteVector.fromValidHex("aa" * 32)
    val recipientId = ByteVector.fromValidHex("bb" * 32)
    val challenge = ByteVector.fromValidHex("0123456789abcdef" * 4)

    // Initiator side: ecdh(ephPriv, recipientPub) → keys, no flip.
    val initiatorKeys =
      Session.deriveKeys(ephPriv, destPubUncompressed, initiatorId, recipientId, challenge)

    // Recipient side: ecdh(recipientPriv, ephPub) yields the same shared
    // secret (commutative); same HKDF inputs => same (writeKey, readKey).
    // The recipient flips its pair so its readKey == initiator's writeKey
    // and vice versa — the right invariant for a directional channel.
    val recipientKeys =
      Session.deriveKeys(destPriv, ephPubUncompressed, initiatorId, recipientId, challenge).flip

    initiatorKeys.writeKey shouldBe recipientKeys.readKey
    initiatorKeys.readKey shouldBe recipientKeys.writeKey
  }

  it should "accept compressed peer pubkey input identically to uncompressed" in {
    val ephPriv = ByteVector.fromValidHex(
      "fb757dc581730490a1d7a00deea65e9b1936924caaea8f44d476014856b68736"
    )
    val destPriv = ByteVector.fromValidHex(
      "66fb62bfbd66b9177a138c1e5cddbe4f7c30c343e94e68df8769459cb1cde628"
    )
    val destPubCompressed = Session.pubFromPriv(destPriv, compressed = true)
    val destPubUncompressed = Session.pubFromPriv(destPriv, compressed = false)

    Session.ecdh(ephPriv, destPubCompressed) shouldBe Session.ecdh(ephPriv, destPubUncompressed)
  }

  // ---- idNonceHash --------------------------------------------------------

  behavior.of("Session.idNonceHash")

  it should "include the 'discovery v5 identity proof' domain tag" in {
    val challenge = ByteVector.fromValidHex("0123456789abcdef" * 4)
    val ephPubkey = ByteVector.fromValidHex("02" + "ab" * 32)
    val destId = ByteVector.fromValidHex("bb" * 32)

    // Sanity: changing any input should change the hash.
    val h1 = Session.idNonceHash(challenge, ephPubkey, destId)
    val h2 = Session.idNonceHash(challenge ++ ByteVector.fromByte(0), ephPubkey, destId)
    val h3 = Session.idNonceHash(challenge, ephPubkey ++ ByteVector.fromByte(0), destId)
    val h4 = Session.idNonceHash(challenge, ephPubkey, ByteVector.fromValidHex("cc" * 32))
    h1 should have size 32L
    h1 should not equal h2
    h1 should not equal h3
    h1 should not equal h4
  }

  // ---- AES-GCM ------------------------------------------------------------

  behavior.of("Session.encrypt / decrypt")

  it should "round-trip a payload with associated data" in {
    val key = ByteVector.fromValidHex("00112233445566778899aabbccddeeff")
    val nonce = ByteVector.fromValidHex("ffffffffffffffffffffffff")
    val plaintext = ByteVector.fromValidHex("01020304" * 4)
    val aad = ByteVector.fromValidHex("aabbccdd" * 4)

    val ct = Session.encrypt(key, nonce, plaintext, aad).get
    Session.decrypt(key, nonce, ct, aad).get shouldBe plaintext
  }

  it should "fail decrypt with wrong AAD (auth-tag mismatch)" in {
    val key = ByteVector.fromValidHex("00112233445566778899aabbccddeeff")
    val nonce = ByteVector.fromValidHex("ffffffffffffffffffffffff")
    val plaintext = ByteVector.fromValidHex("01020304" * 4)
    val aad = ByteVector.fromValidHex("aabbccdd" * 4)
    val ct = Session.encrypt(key, nonce, plaintext, aad).get

    val differentAad = ByteVector.fromValidHex("99887766" * 4)
    Session.decrypt(key, nonce, ct, differentAad).isFailure shouldBe true
  }

  it should "fail decrypt with tampered ciphertext (auth-tag mismatch)" in {
    val key = ByteVector.fromValidHex("00112233445566778899aabbccddeeff")
    val nonce = ByteVector.fromValidHex("ffffffffffffffffffffffff")
    val plaintext = ByteVector.fromValidHex("01020304" * 4)
    val ct = Session.encrypt(key, nonce, plaintext, ByteVector.empty).get

    val tampered = ct.update(0, ((ct(0) ^ 0xff).toByte))
    Session.decrypt(key, nonce, tampered, ByteVector.empty).isFailure shouldBe true
  }
}
