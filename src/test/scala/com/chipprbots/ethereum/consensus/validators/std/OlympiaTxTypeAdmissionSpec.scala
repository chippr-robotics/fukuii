package com.chipprbots.ethereum.consensus.validators.std

import java.security.SecureRandom

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.consensus.validators.SignedTransactionError.TransactionSyntaxError
import com.chipprbots.ethereum.crypto
import com.chipprbots.ethereum.domain._
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.HefPostOlympia
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.HefEmpty
import com.chipprbots.ethereum.nodebuilder.BlockchainConfigBuilder
import com.chipprbots.ethereum.testing.Tags._
import com.chipprbots.ethereum.utils.BlockchainConfig

/** Verifies that Type-2 (EIP-1559 dynamic-fee) and Type-4 (EIP-7702 set-code) transactions are rejected on ETC before
  * Olympia activation, mirroring the blob tx gate for Type-3.
  */
class OlympiaTxTypeAdmissionSpec
    extends AnyFlatSpec
    with Matchers
    with BlockchainConfigBuilder
    with com.chipprbots.ethereum.TestInstanceConfigProvider {

  private val olympiaBlock: BigInt = BigInt(100)

  implicit val config: BlockchainConfig = blockchainConfig.withUpdatedForkBlocks(
    _.copy(
      olympiaBlockNumber = olympiaBlock,
      homesteadBlockNumber = 0,
      eip155BlockNumber = 0
    )
  )

  private val secureRandom = new SecureRandom()
  private val senderKeys = crypto.generateKeyPair(secureRandom)
  private val senderAccount: Account = Account(nonce = 0, balance = UInt256(BigInt("1000000000000000000000")))

  private def preOlympiaHeader: BlockHeader =
    Fixtures.Blocks.ValidBlock.header.copy(
      number = olympiaBlock - 1,
      gasLimit = BigInt(8_000_000),
      gasUsed = 0,
      extraFields = HefEmpty
    )

  private def olympiaHeader: BlockHeader =
    Fixtures.Blocks.ValidBlock.header.copy(
      number = olympiaBlock,
      gasLimit = BigInt(30_000_000),
      gasUsed = 0,
      extraFields = HefPostOlympia(BigInt(1_000_000_000))
    )

  private def signType2(): SignedTransaction = {
    val tx = TransactionWithDynamicFee(
      chainId = config.chainId,
      nonce = 0,
      maxPriorityFeePerGas = BigInt(1_000_000_000),
      maxFeePerGas = BigInt(2_000_000_000),
      gasLimit = 21000,
      receivingAddress = Some(Address(1)),
      value = BigInt(0),
      payload = ByteString.empty,
      accessList = Nil
    )
    SignedTransaction.sign(tx, senderKeys, Some(config.chainId))
  }

  private def signType4(): SignedTransaction = {
    val auth = SetCodeAuthorization(
      chainId = config.chainId,
      address = Address(1),
      nonce = BigInt(0),
      v = BigInt(0),
      r = BigInt(123456),
      s = BigInt(789012)
    )
    val tx = SetCodeTransaction(
      chainId = config.chainId,
      nonce = BigInt(0),
      maxPriorityFeePerGas = BigInt(1_000_000_000),
      maxFeePerGas = BigInt(2_000_000_000),
      gasLimit = BigInt(50000),
      receivingAddress = Some(Address(1)),
      value = BigInt(0),
      payload = ByteString.empty,
      accessList = Nil,
      authorizationList = List(auth)
    )
    SignedTransaction.sign(tx, senderKeys, Some(config.chainId))
  }

  private def signLegacy(): SignedTransaction = {
    val tx = LegacyTransaction(
      nonce = 0,
      gasPrice = BigInt(1_000_000_000),
      gasLimit = 21000,
      receivingAddress = Address(1),
      value = BigInt(0),
      payload = ByteString.empty
    )
    SignedTransaction.sign(tx, senderKeys, Some(config.chainId))
  }

  private def validate(stx: SignedTransaction, header: BlockHeader) =
    StdSignedTransactionValidator.validate(
      stx = stx,
      senderAccount = senderAccount,
      blockHeader = header,
      upfrontGasCost = UInt256(0),
      accumGasUsed = BigInt(0)
    )

  "OlympiaTxTypeAdmission" should "reject Type-2 (TransactionWithDynamicFee) pre-Olympia on ETC" taggedAs (
    OlympiaTest,
    ConsensusTest
  ) in {
    val result = validate(signType2(), preOlympiaHeader)
    result shouldBe a[Left[_, _]]
    result.left.toOption.get shouldBe a[TransactionSyntaxError]
    result.left.toOption.get.asInstanceOf[TransactionSyntaxError].reason should include("TYPE_2_TX_NOT_SUPPORTED")
  }

  it should "reject Type-4 (SetCodeTransaction) pre-Olympia on ETC" taggedAs (OlympiaTest, ConsensusTest) in {
    val result = validate(signType4(), preOlympiaHeader)
    result shouldBe a[Left[_, _]]
    result.left.toOption.get shouldBe a[TransactionSyntaxError]
    result.left.toOption.get.asInstanceOf[TransactionSyntaxError].reason should include("TYPE_4_TX_NOT_SUPPORTED")
  }

  it should "accept Type-2 at Olympia on ETC" taggedAs (OlympiaTest, ConsensusTest) in {
    val result = validate(signType2(), olympiaHeader)
    result shouldBe a[Right[_, _]]
  }

  it should "accept Type-4 at Olympia on ETC" taggedAs (OlympiaTest, ConsensusTest) in {
    val result = validate(signType4(), olympiaHeader)
    result shouldBe a[Right[_, _]]
  }

  it should "not reject legacy tx pre-Olympia" taggedAs (OlympiaTest, ConsensusTest) in {
    val result = validate(signLegacy(), preOlympiaHeader)
    result shouldBe a[Right[_, _]]
  }

  private def signLegacyContractCreate(): SignedTransaction = {
    val tx = LegacyTransaction(
      nonce = 0,
      gasPrice = BigInt(1_000_000_000),
      gasLimit = 100000,
      receivingAddress = None,
      value = BigInt(0),
      payload = ByteString(0x60, 0x60) // minimal initcode
    )
    SignedTransaction.sign(tx, senderKeys, Some(config.chainId))
  }

  private def signType1(): SignedTransaction = {
    val tx = TransactionWithAccessList(
      chainId = config.chainId,
      nonce = BigInt(0),
      gasPrice = BigInt(1_000_000_000),
      gasLimit = BigInt(21000),
      receivingAddress = Some(Address(1)),
      value = BigInt(0),
      payload = ByteString.empty,
      accessList = Nil
    )
    SignedTransaction.sign(tx, senderKeys, Some(config.chainId))
  }

  "OlympiaAllTxTypes" should "accept Type 0 (legacy) pre-Olympia" taggedAs (OlympiaTest, ConsensusTest) in {
    validate(signLegacy(), preOlympiaHeader) shouldBe a[Right[_, _]]
  }

  it should "accept Type 0 (legacy) post-Olympia (backward compat after EIP-1559)" taggedAs (
    OlympiaTest,
    ConsensusTest
  ) in {
    validate(signLegacy(), olympiaHeader) shouldBe a[Right[_, _]]
  }

  it should "accept Type 0 contract deployment (to=None) pre-Olympia" taggedAs (OlympiaTest, ConsensusTest) in {
    validate(signLegacyContractCreate(), preOlympiaHeader) shouldBe a[Right[_, _]]
  }

  it should "accept Type 0 contract deployment (to=None) post-Olympia" taggedAs (OlympiaTest, ConsensusTest) in {
    validate(signLegacyContractCreate(), olympiaHeader) shouldBe a[Right[_, _]]
  }

  it should "accept Type 1 (EIP-2930 access list) pre-Olympia" taggedAs (OlympiaTest, ConsensusTest) in {
    validate(signType1(), preOlympiaHeader) shouldBe a[Right[_, _]]
  }

  it should "accept Type 1 (EIP-2930 access list) post-Olympia (backward compat after EIP-1559)" taggedAs (
    OlympiaTest,
    ConsensusTest
  ) in {
    validate(signType1(), olympiaHeader) shouldBe a[Right[_, _]]
  }
}
