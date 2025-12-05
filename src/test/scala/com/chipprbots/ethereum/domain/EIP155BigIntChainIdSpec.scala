package com.chipprbots.ethereum.domain

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.crypto
import com.chipprbots.ethereum.crypto.ECDSASignature
import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.LegacyTransaction
import com.chipprbots.ethereum.domain.SignedTransaction
import com.chipprbots.ethereum.network.p2p.messages.BaseETH6XMessages.SignedTransactions._
import com.chipprbots.ethereum.testing.Tags._
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.Config
import com.chipprbots.ethereum.utils.Hex

/** Tests for EIP-155 BigInt chain ID support
  *
  * Validates that:
  * 1. Chain IDs are BigInt, not Byte
  * 2. V value calculation: v = chainId * 2 + 35 + {0,1}
  * 3. Transaction hash includes chainId
  * 4. Signature verification works for all chain IDs
  * 5. No silent truncation in the pipeline
  * 6. RLP encoding/decoding handles BigInt chain IDs correctly
  * 7. Pre-EIP-155 transactions (v=27/28) still work
  */
class EIP155BigIntChainIdSpec extends AnyFlatSpec with Matchers {

  "EIP-155 BigInt chain ID" should "handle ETC mainnet (chain ID 61)" taggedAs (UnitTest) in {
    implicit val blockchainConfig: BlockchainConfig = Config.blockchains.blockchainConfig.copy(chainId = 61)
    
    val tx = LegacyTransaction(
      nonce = 0,
      gasPrice = BigInt(20000000000L),
      gasLimit = 21000,
      receivingAddress = Address("0x3535353535353535353535353535353535353535"),
      value = BigInt("1000000000000000000"),
      payload = ByteString.empty
    )
    
    val keyPair = crypto.keyPairFromPrvKey(Hex.decode("4646464646464646464646464646464646464646464646464646464646464646"))
    val signedTx = SignedTransaction.sign(tx, keyPair, Some(BigInt(61)))
    
    // Verify v value calculation: chainId * 2 + 35/36
    val expectedV1 = 61 * 2 + 35  // 157
    val expectedV2 = 61 * 2 + 36  // 158
    assert(signedTx.signature.v == expectedV1 || signedTx.signature.v == expectedV2,
      s"v should be $expectedV1 or $expectedV2 for chain ID 61, got ${signedTx.signature.v}")
    
    // Verify round-trip through RLP encoding/decoding
    val encoded = signedTx.toBytes
    val decoded = encoded.toSignedTransaction
    
    decoded.signature.v shouldEqual signedTx.signature.v
    decoded.signature.r shouldEqual signedTx.signature.r
    decoded.signature.s shouldEqual signedTx.signature.s
    
    // Verify sender recovery still works
    val sender = SignedTransaction.getSender(decoded)
    sender shouldBe defined
  }

  it should "handle Gorgoroth testnet (chain ID 1337)" taggedAs (UnitTest) in {
    implicit val blockchainConfig: BlockchainConfig = Config.blockchains.blockchainConfig.copy(chainId = 1337)
    
    val tx = LegacyTransaction(
      nonce = 5,
      gasPrice = BigInt(30000000000L),
      gasLimit = 21000,
      receivingAddress = Address("0x1234567890123456789012345678901234567890"),
      value = BigInt("2000000000000000000"),
      payload = ByteString.empty
    )
    
    val keyPair = crypto.keyPairFromPrvKey(Hex.decode("1234567890123456789012345678901234567890123456789012345678901234"))
    val signedTx = SignedTransaction.sign(tx, keyPair, Some(BigInt(1337)))
    
    // Verify v value calculation: chainId * 2 + 35/36
    val expectedV1 = 1337 * 2 + 35  // 2709
    val expectedV2 = 1337 * 2 + 36  // 2710
    assert(signedTx.signature.v == expectedV1 || signedTx.signature.v == expectedV2,
      s"v should be $expectedV1 or $expectedV2 for chain ID 1337, got ${signedTx.signature.v}")
    
    // Verify v is a BigInt and doesn't overflow
    signedTx.signature.v should be > BigInt(127)  // Would overflow Byte
    signedTx.signature.v should be > BigInt(255)  // Would overflow unsigned Byte
    
    // Verify round-trip through RLP encoding/decoding (CRITICAL TEST)
    val encoded = signedTx.toBytes
    val decoded = encoded.toSignedTransaction
    
    // This should NOT truncate! Before fix, this would fail because
    // 2710.toInt.toByte = -106 (overflow)
    decoded.signature.v shouldEqual signedTx.signature.v
    decoded.signature.r shouldEqual signedTx.signature.r
    decoded.signature.s shouldEqual signedTx.signature.s
    
    // Verify sender recovery still works
    val sender = SignedTransaction.getSender(decoded)
    sender shouldBe defined
  }

  it should "handle Arbitrum One (chain ID 42161)" taggedAs (UnitTest) in {
    implicit val blockchainConfig: BlockchainConfig = Config.blockchains.blockchainConfig.copy(chainId = 42161)
    
    val tx = LegacyTransaction(
      nonce = 10,
      gasPrice = BigInt(100000000),
      gasLimit = 21000,
      receivingAddress = Address("0xabcdefabcdefabcdefabcdefabcdefabcdefabcd"),
      value = BigInt("5000000000000000"),
      payload = ByteString.empty
    )
    
    val keyPair = crypto.keyPairFromPrvKey(Hex.decode("9876543210987654321098765432109876543210987654321098765432109876"))
    val signedTx = SignedTransaction.sign(tx, keyPair, Some(BigInt(42161)))
    
    // Verify v value calculation: chainId * 2 + 35/36
    val expectedV1 = 42161 * 2 + 35  // 84357
    val expectedV2 = 42161 * 2 + 36  // 84358
    assert(signedTx.signature.v == expectedV1 || signedTx.signature.v == expectedV2,
      s"v should be $expectedV1 or $expectedV2 for chain ID 42161, got ${signedTx.signature.v}")
    
    // Verify v is way larger than Byte can hold
    signedTx.signature.v should be > BigInt(65535)  // Beyond unsigned short
    
    // Verify round-trip through RLP encoding/decoding
    val encoded = signedTx.toBytes
    val decoded = encoded.toSignedTransaction
    
    decoded.signature.v shouldEqual signedTx.signature.v
    decoded.signature.r shouldEqual signedTx.signature.r
    decoded.signature.s shouldEqual signedTx.signature.s
    
    // Verify sender recovery still works
    val sender = SignedTransaction.getSender(decoded)
    sender shouldBe defined
  }

  it should "still support pre-EIP-155 transactions (v=27/28)" taggedAs (UnitTest) in {
    implicit val blockchainConfig: BlockchainConfig = Config.blockchains.blockchainConfig.copy(chainId = 61)
    
    val tx = LegacyTransaction(
      nonce = 0,
      gasPrice = BigInt(20000000000L),
      gasLimit = 21000,
      receivingAddress = Address("0x3535353535353535353535353535353535353535"),
      value = BigInt("1000000000000000000"),
      payload = ByteString.empty
    )
    
    val keyPair = crypto.keyPairFromPrvKey(Hex.decode("4646464646464646464646464646464646464646464646464646464646464646"))
    
    // Sign without chainId (pre-EIP-155)
    val signedTx = SignedTransaction.sign(tx, keyPair, None)
    
    // Verify v value is 27 or 28 (pre-EIP-155)
    val v = signedTx.signature.v
    assert(v == 27 || v == 28, s"Pre-EIP-155 v should be 27 or 28, got $v")
    
    // Verify round-trip through RLP encoding/decoding
    val encoded = signedTx.toBytes
    val decoded = encoded.toSignedTransaction
    
    decoded.signature.v shouldEqual signedTx.signature.v
    decoded.signature.r shouldEqual signedTx.signature.r
    decoded.signature.s shouldEqual signedTx.signature.s
    
    // Verify sender recovery still works
    val sender = SignedTransaction.getSender(decoded)
    sender shouldBe defined
  }

  it should "correctly construct ECDSASignature with BigInt v" taggedAs (UnitTest) in {
    // Test that ECDSASignature properly stores BigInt v values
    val r = BigInt("12345678901234567890")
    val s = BigInt("98765432109876543210")
    val v = BigInt(2710)  // Gorgoroth chain ID 1337 -> v = 2710
    
    val sig = ECDSASignature(r, s, v)
    
    sig.v shouldEqual v
    sig.v should be > BigInt(127)  // Would overflow signed Byte
    sig.v should be > BigInt(255)  // Would overflow unsigned Byte
  }

  it should "handle transaction hash calculation with large chain IDs" taggedAs (UnitTest) in {
    val tx = LegacyTransaction(
      nonce = 9,
      gasPrice = BigInt(20000000000L),
      gasLimit = 21000,
      receivingAddress = Address("0x3535353535353535353535353535353535353535"),
      value = BigInt("1000000000000000000"),
      payload = ByteString.empty
    )
    
    // Test with different chain IDs
    val chainIds = Seq(
      BigInt(1),      // Ethereum mainnet
      BigInt(61),     // ETC mainnet
      BigInt(1337),   // Gorgoroth testnet
      BigInt(42161)   // Arbitrum One
    )
    
    val hashes = chainIds.map { chainId =>
      SignedTransaction.bytesToSign(tx, Some(chainId))
    }
    
    // Each chain ID should produce a different hash
    hashes.distinct.length shouldEqual chainIds.length
  }
}
