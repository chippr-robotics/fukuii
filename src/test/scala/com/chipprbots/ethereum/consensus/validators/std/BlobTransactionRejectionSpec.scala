package com.chipprbots.ethereum.consensus.validators.std

import org.apache.pekko.util.ByteString

import org.bouncycastle.util.encoders.Hex
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.consensus.validators.SignedTransactionError.TransactionSyntaxError
import com.chipprbots.ethereum.crypto.ECDSASignature
import com.chipprbots.ethereum.domain._
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.HefPostOlympia
import com.chipprbots.ethereum.testing.Tags._
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.Config
import com.chipprbots.ethereum.utils.ForkTimestamps

/** L1 — EIP-4844 blob (Type-3) transaction rejection on ETC.
  *
  * ETC does not support EIP-4844 (no Cancun timestamp configured). Blob transactions must be rejected at the validator
  * layer before any other checks, regardless of block height or the validity of the transaction's other fields.
  */
class BlobTransactionRejectionSpec extends AnyFlatSpec with Matchers {

  implicit val blockchainConfig: BlockchainConfig = Config.blockchains.blockchainConfig

  // Reuse real r/s values from a known ETC tx so checkSyntacticValidity passes in the
  // Cancun-active path (where the blob check passes through to other validation stages).
  private val validR = ByteString(Hex.decode("f337e8ca3306c131eabb756aa3701ec7b00bef0d6cc21fbf6a6f291463d58baf"))
  private val validS = ByteString(Hex.decode("72216654137b4b58a4ece0a6df87aa1a4faf18ec4091839dd1c722fa9604fd09"))

  private val blobTx = BlobTransaction(
    chainId = blockchainConfig.chainId,
    nonce = 0,
    maxPriorityFeePerGas = BigInt("1000000000"),
    maxFeePerGas = BigInt("2000000000"),
    gasLimit = 21000,
    receivingAddress = Some(Address(Hex.decode("32be343b94f860124dc4fee278fdcbd38c102d88"))),
    value = BigInt(0),
    payload = ByteString.empty,
    accessList = Nil,
    maxFeePerBlobGas = BigInt("1000000000"),
    blobVersionedHashes = List(ByteString(Array.fill(32)(0.toByte)))
  )

  private val signedBlobTx = SignedTransaction(
    blobTx,
    pointSign = ECDSASignature.negativeYParity,
    signatureRandom = validR,
    signature = validS
  )

  // Block header well into post-Spiral ETC — unixTimestamp has no Cancun in ETC config.
  private val etcBlockHeader = Fixtures.Blocks.Block3125369.header.copy(
    number = BigInt(21_000_000),
    gasLimit = 8_000_000,
    extraFields = HefPostOlympia(BigInt("1000000000"))
  )

  private val senderAccount = Account.empty(UInt256(0)).copy(balance = UInt256(BigInt("1000000000000000000")))

  private def validate(stx: SignedTransaction, header: BlockHeader = etcBlockHeader)(implicit
      cfg: BlockchainConfig
  ) = StdSignedTransactionValidator.validate(
    stx = stx,
    senderAccount = senderAccount,
    blockHeader = header,
    upfrontGasCost = UInt256(0),
    accumGasUsed = BigInt(0)
  )

  // ── ETC mainnet (no Cancun configured) ─────────────────────────────────────

  it should "reject a blob transaction on ETC mainnet at any block height" taggedAs (UnitTest, ConsensusTest) in {
    validate(signedBlobTx) shouldBe a[Left[?, ?]]
  }

  it should "return TYPE_3_TX_NOT_SUPPORTED error for blob tx on ETC mainnet" taggedAs (UnitTest, ConsensusTest) in {
    validate(signedBlobTx) match {
      case Left(TransactionSyntaxError(msg)) => msg should include("TYPE_3_TX_NOT_SUPPORTED")
      case other                             => fail(s"Expected TransactionSyntaxError, got: $other")
    }
  }

  it should "reject blob tx at low block numbers on ETC (pre-Spiral)" taggedAs (UnitTest, ConsensusTest) in {
    val earlyHeader = etcBlockHeader.copy(number = BigInt(1_000_000))
    validate(signedBlobTx, earlyHeader) match {
      case Left(TransactionSyntaxError(msg)) => msg should include("TYPE_3_TX_NOT_SUPPORTED")
      case other                             => fail(s"Expected TransactionSyntaxError, got: $other")
    }
  }

  it should "reject blob tx at block 0 (genesis) on ETC" taggedAs (UnitTest, ConsensusTest) in {
    val genesisHeader = etcBlockHeader.copy(number = BigInt(0))
    validate(signedBlobTx, genesisHeader) match {
      case Left(TransactionSyntaxError(msg)) => msg should include("TYPE_3_TX_NOT_SUPPORTED")
      case other                             => fail(s"Expected TransactionSyntaxError, got: $other")
    }
  }

  // ── Cancun-active config (ETH post-Cancun analogue) ────────────────────────
  // With cancunTimestamp = 0 the blob check passes; the tx then fails for other
  // reasons (invalid signature — fake bytes), but NOT with the blob-unsupported error.

  it should "pass blob type check when Cancun is active (error is NOT TYPE_3_TX_NOT_SUPPORTED)" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    implicit val cancunCfg: BlockchainConfig = blockchainConfig.copy(
      forkTimestamps = blockchainConfig.forkTimestamps.copy(cancunTimestamp = Some(0L))
    )
    val cancunHeader = etcBlockHeader.copy(unixTimestamp = 1_000_000_000L)
    validate(signedBlobTx, cancunHeader)(cancunCfg) match {
      case Left(TransactionSyntaxError(msg)) if msg.contains("TYPE_3_TX_NOT_SUPPORTED") =>
        fail("Blob tx should not be rejected for type when Cancun is active")
      case _ => succeed // Any other result (including other errors) is acceptable
    }
  }

  // ── Legacy and Type-2 txs are unaffected ───────────────────────────────────

  it should "not affect validation of legacy transactions" taggedAs (UnitTest, ConsensusTest) in {
    val legacyTx = LegacyTransaction(
      nonce = 0,
      gasPrice = BigInt("2000000000"),
      gasLimit = 21000,
      receivingAddress = Address(Hex.decode("32be343b94f860124dc4fee278fdcbd38c102d88")),
      value = BigInt(0),
      payload = ByteString.empty
    )
    val signedLegacy = SignedTransaction(
      legacyTx,
      pointSign = ECDSASignature.negativeYParity,
      signatureRandom = validR,
      signature = validS
    )
    validate(signedLegacy) match {
      case Left(TransactionSyntaxError(msg)) if msg.contains("TYPE_3_TX_NOT_SUPPORTED") =>
        fail("Legacy tx incorrectly rejected as blob")
      case _ => succeed
    }
  }
}
