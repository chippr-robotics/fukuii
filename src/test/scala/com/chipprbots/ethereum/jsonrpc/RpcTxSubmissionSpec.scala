package com.chipprbots.ethereum.jsonrpc

import org.apache.pekko.util.ByteString

import com.typesafe.config.ConfigFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.crypto
import com.chipprbots.ethereum.crypto.ECDSASignature
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.security.SecureRandomBuilder
import com.chipprbots.ethereum.testing.Tags.*

/** The RPC-layer submission checks (#1629 S0b): go-ethereum `checkTxFee` and the replay-protection rule of
  * `SubmitTransaction`, and their config keys.
  */
class RpcTxSubmissionSpec extends AnyFlatSpec with Matchers with SecureRandomBuilder:

  private val OneEther = BigInt(10).pow(18)

  "the shipped config" should "refuse unprotected txs and cap the fee at 1 ether, as go-ethereum and core-geth do" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    val shipped = RpcTxSubmission.fromConfig(ConfigFactory.load().getConfig("fukuii"))
    shipped shouldBe RpcTxSubmission(allowUnprotectedTxs = false, txFeeCap = 1.0)
    shipped shouldBe RpcTxSubmission.Default
  }

  it should "read the opt-in and a different or no cap" taggedAs (UnitTest, RPCTest) in {
    val cfg = ConfigFactory.parseString("""
      network.rpc.allow-unprotected-txs = true
      network.rpc.tx-fee-cap = 0.5
    """)
    RpcTxSubmission.fromConfig(cfg) shouldBe RpcTxSubmission(allowUnprotectedTxs = true, txFeeCap = 0.5)
    RpcTxSubmission.fromConfig(ConfigFactory.parseString("network.rpc.tx-fee-cap = 0")).txFeeCap shouldBe 0.0
    RpcTxSubmission.fromConfig(ConfigFactory.empty()) shouldBe RpcTxSubmission.Default
  }

  "checkTxFee" should "refuse gas * fee cap above the cap with go-ethereum's message" taggedAs (UnitTest, RPCTest) in {
    // 21000 gas at 100 000 gwei: 2.1 ether
    RpcTxSubmission.feeCapRejection(BigInt(10).pow(14), 21_000, 1.0) shouldBe
      Some("tx fee (2.10 ether) exceeds the configured cap (1.00 ether)")
    RpcTxSubmission.feeCapRejection(BigInt(10).pow(14), 21_000, 0.5) shouldBe
      Some("tx fee (2.10 ether) exceeds the configured cap (0.50 ether)")
  }

  it should "admit a fee exactly at the cap, and anything with no cap (0)" taggedAs (UnitTest, RPCTest) in {
    RpcTxSubmission.feeCapRejection(OneEther / 21_000 + 1, 21_000, 1.0) shouldBe defined
    RpcTxSubmission.feeCapRejection(OneEther, 1, 1.0) shouldBe None
    RpcTxSubmission.feeCapRejection(BigInt(10).pow(30), 30_000_000, 0) shouldBe None
  }

  it should "print the amounts as go's %.2f does (exact value, half to even)" taggedAs (UnitTest, RPCTest) in {
    // 1.125 is exact in binary: go rounds the tie to even, 1.12
    RpcTxSubmission.feeCapRejection(OneEther * 1125 / 1000, 1, 1.0) shouldBe
      Some("tx fee (1.12 ether) exceeds the configured cap (1.00 ether)")
    // 1.005 is 1.00499999... in binary: 1.00, not 1.01
    RpcTxSubmission.feeCapRejection(OneEther * 1005 / 1000, 1, 1.0) shouldBe
      Some("tx fee (1.00 ether) exceeds the configured cap (1.00 ether)")
  }

  it should "price a typed tx at its fee cap, not its tip, and not count blob gas" taggedAs (UnitTest, RPCTest) in {
    val dynamic =
      TransactionWithDynamicFee(1, 0, 1, OneEther / 10_000, GasAmount(21_000), None, 0, ByteString.empty, Nil)
    RpcTxSubmission.Default.feeCapRejection(dynamic) shouldBe
      Some("tx fee (2.10 ether) exceeds the configured cap (1.00 ether)")
    val blob = BlobTransaction(
      1,
      0,
      1,
      OneEther / 100_000,
      GasAmount(21_000),
      Some(Address(1)),
      0,
      ByteString.empty,
      Nil,
      maxFeePerBlobGas = OneEther,
      blobVersionedHashes = List(BlobVersionedHash(ByteString(Array.fill[Byte](32)(1))))
    )
    RpcTxSubmission.Default.feeCapRejection(blob) shouldBe None
  }

  "replay protection" should "hold for EIP-155 legacy txs and every typed tx, not for v = 27/28" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    val key = crypto.generateKeyPair(secureRandom)
    val legacy = LegacyTransaction(0, GasPrice(1), GasAmount(21_000), Some(Address(1)), 0, ByteString.empty)
    val unprotected = SignedTransaction.sign(legacy, key, None)
    Set(BigInt(27), BigInt(28)) should contain(unprotected.signature.v)
    RpcTxSubmission.isProtected(unprotected) shouldBe false

    val mainnet = SignedTransaction.sign(legacy, key, Some(1))
    Set(BigInt(37), BigInt(38)) should contain(mainnet.signature.v)
    RpcTxSubmission.isProtected(mainnet) shouldBe true
    val mordor = SignedTransaction.sign(legacy, key, Some(63))
    Set(BigInt(161), BigInt(162)) should contain(mordor.signature.v)
    RpcTxSubmission.isProtected(mordor) shouldBe true

    val accessList =
      TransactionWithAccessList(1, 0, GasPrice(1), GasAmount(21_000), Some(Address(1)), 0, ByteString.empty, Nil)
    RpcTxSubmission.isProtected(SignedTransaction.sign(accessList, key, Some(1))) shouldBe true

    RpcTxSubmission.Default.submissionRejection(unprotected) shouldBe
      Some("only replay-protected (EIP-155) transactions allowed over RPC")
    RpcTxSubmission(allowUnprotectedTxs = true, txFeeCap = 1.0).submissionRejection(unprotected) shouldBe None
    RpcTxSubmission.Default.submissionRejection(mainnet) shouldBe None
  }

  it should "be checked after the fee cap, as in go-ethereum's SubmitTransaction" taggedAs (UnitTest, RPCTest) in {
    val key = crypto.generateKeyPair(secureRandom)
    val costly =
      LegacyTransaction(0, GasPrice(BigInt(10).pow(14)), GasAmount(21_000), Some(Address(1)), 0, ByteString.empty)
    RpcTxSubmission.Default.submissionRejection(SignedTransaction.sign(costly, key, None)) shouldBe
      Some("tx fee (2.10 ether) exceeds the configured cap (1.00 ether)")
  }

  it should "read a v stored as a negative byte as go-ethereum's unsigned v" taggedAs (UnitTest, RPCTest) in {
    val legacy = LegacyTransaction(0, GasPrice(1), GasAmount(21_000), Some(Address(1)), 0, ByteString.empty)
    RpcTxSubmission.isProtected(SignedTransaction(legacy, ECDSASignature(1, 1, BigInt(-229)))) shouldBe false // 27
    RpcTxSubmission.isProtected(SignedTransaction(legacy, ECDSASignature(1, 1, BigInt(-219)))) shouldBe true // 37
  }
