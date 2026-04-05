package com.chipprbots.ethereum.consensus.validators.std

import java.security.SecureRandom

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.consensus.validators.SignedTransactionError._
import com.chipprbots.ethereum.crypto
import com.chipprbots.ethereum.domain._
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields._
import com.chipprbots.ethereum.nodebuilder.BlockchainConfigBuilder
import com.chipprbots.ethereum.testing.Tags._
import com.chipprbots.ethereum.utils.BlockchainConfig

/** EIP-7825: Validate per-transaction gas limit cap of 2^24 (16,777,216) post-Olympia. */
class EIP7825GasCapSpec extends AnyFlatSpec with Matchers with BlockchainConfigBuilder with com.chipprbots.ethereum.TestInstanceConfigProvider {

  val olympiaBlock: BigInt = 10

  implicit val config: BlockchainConfig = blockchainConfig.withUpdatedForkBlocks(
    _.copy(
      olympiaBlockNumber = olympiaBlock,
      homesteadBlockNumber = 0,
      eip155BlockNumber = 0
    )
  )

  val secureRandom = new SecureRandom()
  val senderKeys = crypto.generateKeyPair(secureRandom)
  val senderAddress = Address(senderKeys)
  val senderAccount: Account = Account(nonce = 0, balance = UInt256(BigInt("1000000000000000000000")))

  def makeTx(gasLimit: BigInt): SignedTransaction = {
    val tx = LegacyTransaction(
      nonce = 0,
      gasPrice = BigInt(1),
      gasLimit = gasLimit,
      receivingAddress = Address(1),
      value = 0,
      payload = ByteString.empty
    )
    SignedTransaction.sign(tx, senderKeys, Some(config.chainId))
  }

  def makeHeader(number: BigInt): BlockHeader = {
    val extraFields = if (number >= olympiaBlock) HefPostOlympia(BigInt(1000000000)) else HefEmpty
    Fixtures.Blocks.ValidBlock.header.copy(
      number = number,
      gasLimit = BigInt(100_000_000),
      gasUsed = 0,
      extraFields = extraFields
    )
  }

  "EIP-7825" should "reject tx with gas > 2^24 post-Olympia" taggedAs (OlympiaTest, ConsensusTest) in {
    val stx = makeTx(BigInt(16_777_217))
    val header = makeHeader(olympiaBlock)
    val upfrontCost = UInt256(stx.tx.gasLimit * stx.tx.gasPrice)

    val result = StdSignedTransactionValidator.validate(stx, senderAccount, header, upfrontCost, 0)
    result shouldBe a[Left[_, _]]
    result.left.toOption.get shouldBe a[TransactionGasLimitExceedsCap]
  }

  it should "accept tx at exactly 2^24 (16,777,216) post-Olympia" taggedAs (OlympiaTest, ConsensusTest) in {
    val stx = makeTx(BigInt(16_777_216))
    val header = makeHeader(olympiaBlock)
    val upfrontCost = UInt256(stx.tx.gasLimit * stx.tx.gasPrice)

    val result = StdSignedTransactionValidator.validate(stx, senderAccount, header, upfrontCost, 0)
    result shouldBe a[Right[_, _]]
  }

  it should "accept tx > 2^24 pre-Olympia" taggedAs (OlympiaTest, ConsensusTest) in {
    val stx = makeTx(BigInt(50_000_000))
    val header = makeHeader(olympiaBlock - 1)
    val upfrontCost = UInt256(stx.tx.gasLimit * stx.tx.gasPrice)

    val result = StdSignedTransactionValidator.validate(stx, senderAccount, header, upfrontCost, 0)
    result shouldBe a[Right[_, _]]
  }

  "TxGasLimitCap constant" should "be 2^24 (16,777,216)" taggedAs (OlympiaTest, ConsensusTest) in {
    StdSignedTransactionValidator.TxGasLimitCap shouldBe BigInt(16_777_216)
  }
}
