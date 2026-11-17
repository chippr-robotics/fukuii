package com.chipprbots.ethereum.consensus.validators.std

import org.apache.pekko.util.ByteString

import org.bouncycastle.util.encoders.Hex
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.consensus.engine.BlobGasUtils
import com.chipprbots.ethereum.consensus.validators.SignedTransactionError.TransactionInitCodeSizeError
import com.chipprbots.ethereum.consensus.validators.SignedTransactionError.TransactionMaxFeePerBlobGasTooLow
import com.chipprbots.ethereum.consensus.validators.SignedTransactionError.TransactionNotEnoughGasForIntrinsicError
import com.chipprbots.ethereum.consensus.validators.SignedTransactionError.TransactionSyntaxError
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.HefPostCancun
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.Config
import com.chipprbots.ethereum.utils.ForkTimestamps
import com.chipprbots.ethereum.utils.NetworkType

/** Unit coverage for EIP-3860 initcode size enforcement in [[StdSignedTransactionValidator]].
  *
  * EIP-3860 caps CREATE initcode at 2 * MAX_CODE_SIZE (49152 bytes). It activates at Shanghai on ETH/Sepolia
  * (timestamp-gated) and is NOT active on ETC (no shanghaiTimestamp in the ETC fork config).
  *
  * The test matrix verifies three cases:
  *   - ETH/Sepolia post-Shanghai: oversized initcode → TransactionInitCodeSizeError
  *   - ETH/Sepolia pre-Shanghai: oversized initcode → accepted (any other error is fine)
  *   - ETC at any block: oversized initcode → accepted (EIP-3860 never activates)
  */
class StdSignedTransactionValidatorSpec extends AnyFlatSpec with Matchers:

  private val etcConfig: BlockchainConfig = Config.blockchains.blockchainConfig

  private val ShanghaiTs: Long = 1_000L

  // Minimal ETH/Sepolia-like config: Shanghai at ts=1000, ETH network type so
  // validateOlympiaTxTypes passes immediately (ETH gates those types via London/Prague).
  private val sepoliaConfig: BlockchainConfig = etcConfig.copy(
    networkType = NetworkType.ETH,
    forkTimestamps = ForkTimestamps(shanghaiTimestamp = Some(ShanghaiTs))
  )

  // §ETH-T1-B: The default test config has all ETC-specific forks (Atlantis through Olympia)
  // at 1e18, and byzantium at 4370000. The forBlock selector uses maxBy((blockNum, priority)),
  // so block 4370000 beats any ETC fork at 0 — resulting in ByzantiumFeeSchedule with
  // G_txdatanonzero=68 and G_initcode_word=0. To get MystiqueFeeSchedule (G_txdatanonzero=16,
  // G_initcode_word=2) active at block 21M, we place mystiqueBlockNumber at 5000000 (above
  // byzantium's 4370000). Spiral stays at 1e18 so EIP-3860 does NOT activate via the
  // block-based fork on ETC — only the timestamp path enables it on ETH/Sepolia.
  private val etcMystiqueConfig: BlockchainConfig = etcConfig.withUpdatedForkBlocks(
    _.copy(mystiqueBlockNumber = BigInt(5_000_000))
  )

  private val sepoliaLondonConfig: BlockchainConfig = etcMystiqueConfig.copy(
    networkType = NetworkType.ETH,
    forkTimestamps = ForkTimestamps(shanghaiTimestamp = Some(ShanghaiTs))
  )

  // EIP-3860: max initcode = 2 * MAX_CODE_SIZE = 2 * 24576 = 49152 bytes.
  // One byte over the limit to trigger the rejection.
  private val overLimitPayload: ByteString = ByteString(Array.fill(49153)(0.toByte))

  // Real r/s from a known ETC tx — syntactically valid, so checkSyntacticValidity passes.
  private val realR = ByteString(Hex.decode("f337e8ca3306c131eabb756aa3701ec7b00bef0d6cc21fbf6a6f291463d58baf"))
  private val realS = ByteString(Hex.decode("72216654137b4b58a4ece0a6df87aa1a4faf18ec4091839dd1c722fa9604fd09"))

  // Contract creation (receivingAddress = None) with oversized initcode.
  private val initcodeTx: LegacyTransaction = LegacyTransaction(
    nonce = 0,
    gasPrice = GasPrice(BigInt("1000000000")),
    gasLimit = GasAmount(BigInt("1000000")),
    receivingAddress = None,
    value = BigInt(0),
    payload = overLimitPayload
  )

  private val signedInitcodeTx: SignedTransaction = SignedTransaction(
    initcodeTx,
    pointSign = 0x1b.toByte,
    signatureRandom = realR,
    signature = realS
  )

  private val senderAccount: Account =
    Account.empty(UInt256(0)).copy(balance = UInt256(BigInt("1000000000000000000")))

  private val baseHeader: BlockHeader =
    Fixtures.Blocks.Block3125369.header.copy(gasLimit = GasAmount(BigInt("10000000")))

  private def validate(stx: SignedTransaction, blockHeader: BlockHeader)(implicit cfg: BlockchainConfig) =
    StdSignedTransactionValidator.validate(
      stx = stx,
      senderAccount = senderAccount,
      blockHeader = blockHeader,
      upfrontGasCost = BigInt(0),
      accumGasUsed = BigInt(0)
    )

  // ── ETH/Sepolia post-Shanghai ───────────────────────────────────────────────

  it should "reject CREATE with initcode > 49152 bytes on ETH/Sepolia post-Shanghai" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    implicit val cfg: BlockchainConfig = sepoliaConfig
    val postShanghaiHeader = baseHeader.copy(unixTimestamp = Timestamp(ShanghaiTs + 1))
    validate(signedInitcodeTx, postShanghaiHeader) match
      case Left(_: TransactionInitCodeSizeError) => succeed
      case other                                 => fail(s"Expected TransactionInitCodeSizeError, got: $other")
  }

  // ── ETH/Sepolia pre-Shanghai ────────────────────────────────────────────────

  it should "accept CREATE with large initcode on ETH/Sepolia pre-Shanghai (EIP-3860 not yet active)" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    implicit val cfg: BlockchainConfig = sepoliaConfig
    val preShanghaiHeader = baseHeader.copy(unixTimestamp = Timestamp(ShanghaiTs - 1))
    validate(signedInitcodeTx, preShanghaiHeader) match
      case Left(_: TransactionInitCodeSizeError) => fail("Large initcode must be accepted pre-Shanghai")
      case _                                     => succeed
  }

  // ── ETC (no Shanghai timestamp configured) ──────────────────────────────────

  it should "accept CREATE with large initcode on ETC (EIP-3860 never activates)" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    implicit val cfg: BlockchainConfig = etcConfig
    val etcHeader = baseHeader.copy(number = BlockNumber(BigInt(21_000_000)), unixTimestamp = Timestamp(ShanghaiTs + 1))
    validate(signedInitcodeTx, etcHeader) match
      case Left(_: TransactionInitCodeSizeError) => fail("EIP-3860 must not be active on ETC")
      case _                                     => succeed
  }

  // ── §ETH-T1-B: EIP-3860 initcode word cost in validateGasLimitEnoughForIntrinsicGas ──
  //
  // EIP-3860 activates at Shanghai and adds a word cost of 2 gas per 32-byte word of initcode.
  // Pre-Shanghai the 2-arg EvmConfig.forBlock returned London config with eip3860Enabled=false,
  // so the word cost was never included in intrinsic gas at the validator boundary.
  //
  // Test transaction: 200 non-zero bytes of initcode, 7 words (ceil(200/32) = 7).
  // Pre-Shanghai intrinsic = 21000 + 32000 + 200*16 + 0      = 56200
  // Post-Shanghai intrinsic = 21000 + 32000 + 200*16 + 2*7   = 56214
  // gasLimit = 56213 → accepted pre-Shanghai, rejected post-Shanghai.

  // 200 non-zero bytes, well under the 49152-byte size limit; 7 words for EIP-3860 word cost.
  private val wordCostPayload: ByteString = ByteString(Array.fill(200)(1.toByte))

  private val wordCostTx: LegacyTransaction = LegacyTransaction(
    nonce = 0,
    gasPrice = GasPrice(BigInt("1000000000")),
    gasLimit = GasAmount(BigInt(56213)), // 56214 - 1: below post-Shanghai intrinsic, above pre-Shanghai
    receivingAddress = None,
    value = BigInt(0),
    payload = wordCostPayload
  )

  private val signedWordCostTx: SignedTransaction = SignedTransaction(
    wordCostTx,
    pointSign = 0x1b.toByte,
    signatureRandom = realR,
    signature = realS
  )

  it should "reject CREATE with gas limit below EIP-3860 word cost on ETH/Sepolia post-Shanghai" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    // sepoliaLondonConfig has Mystique fee schedule active at block 0 (G_txdatanonzero=16,
    // G_initcode_word=2). Post-Shanghai: eip3860Enabled=true → intrinsic = 56214 > gasLimit 56213.
    implicit val cfg: BlockchainConfig = sepoliaLondonConfig
    val postShanghaiHeader = baseHeader.copy(unixTimestamp = Timestamp(ShanghaiTs + 1))
    validate(signedWordCostTx, postShanghaiHeader) match
      case Left(_: TransactionNotEnoughGasForIntrinsicError) => succeed
      case other => fail(s"Expected TransactionNotEnoughGasForIntrinsicError, got: $other")
  }

  it should "accept CREATE with same gas limit on ETC (EIP-3860 word cost not active pre-Olympia)" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    // etcMystiqueConfig has Mystique fee schedule active at block 0 but no shanghaiTimestamp,
    // so eip3860Enabled stays false. Intrinsic = 21000+32000+200*16+0 = 56200 ≤ 56213 → accepted.
    implicit val cfg: BlockchainConfig = etcMystiqueConfig
    val etcHeader = baseHeader.copy(number = BlockNumber(BigInt(21_000_000)), unixTimestamp = Timestamp(ShanghaiTs + 1))
    validate(signedWordCostTx, etcHeader) match
      case Left(_: TransactionNotEnoughGasForIntrinsicError) =>
        fail("EIP-3860 word cost must not apply on ETC pre-Olympia")
      case _ => succeed
  }

  // ── §ETH-T4-B: EIP-4844 maxFeePerBlobGas >= blobBaseFee validation ─────────
  //
  // EIP-4844: a blob tx is only valid when tx.maxFeePerBlobGas >= blobBaseFee(block.excessBlobGas).
  // go-ethereum rejects with ErrMaxFeePerBlobGas.
  //
  // Test setup:
  //   excessBlobGas = 0 → blobBaseFee = BlobGasUtils.getBlobGasPrice(0) = 1 (MIN_BLOB_BASE_FEE)
  //   Blob tx with maxFeePerBlobGas = 1  → accepted (equal to blobBaseFee)
  //   Blob tx with maxFeePerBlobGas = 0  → rejected (below blobBaseFee)
  //   Non-blob tx                        → accepted (check does not apply)
  //   ETC chain (no cancunTimestamp)     → blob tx rejected by validateBlobTransactionSupport,
  //                                        not by the blob-gas check

  private val CancunTs: Long = 2_000L

  private val sepoliaCancunConfig: BlockchainConfig = etcConfig.copy(
    networkType = NetworkType.ETH,
    forkTimestamps = ForkTimestamps(shanghaiTimestamp = Some(ShanghaiTs), cancunTimestamp = Some(CancunTs))
  )

  // Cancun block header: excessBlobGas = 0 → blobBaseFee = 1
  private val cancunHeader: BlockHeader = baseHeader.copy(
    unixTimestamp = Timestamp(CancunTs + 1),
    gasLimit = GasAmount(BigInt("30000000")),
    extraFields = HefPostCancun(
      baseFee = BigInt(1_000_000_000L),
      withdrawalsRoot = ByteString(new Array[Byte](32)),
      blobGasUsed = BigInt(0),
      excessBlobGas = BigInt(0),
      parentBeaconBlockRoot = ByteString(new Array[Byte](32))
    )
  )

  private def signedBlobTx(maxFeePerBlobGas: BigInt): SignedTransaction = SignedTransaction(
    BlobTransaction(
      chainId = BigInt(1),
      nonce = 0,
      maxPriorityFeePerGas = BigInt(0),
      maxFeePerGas = BigInt(2_000_000_000L),
      gasLimit = GasAmount(BigInt(1_000_000)),
      receivingAddress = Some(Address(0L)),
      value = BigInt(0),
      payload = ByteString.empty,
      accessList = Nil,
      maxFeePerBlobGas = maxFeePerBlobGas,
      blobVersionedHashes = List(BlobVersionedHash(ByteString(new Array[Byte](32))))
    ),
    pointSign = 0x00.toByte,
    signatureRandom = realR,
    signature = realS
  )

  it should "accept blob tx with maxFeePerBlobGas equal to blobBaseFee (EIP-4844)" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    implicit val cfg: BlockchainConfig = sepoliaCancunConfig
    // excessBlobGas = 0 → blobBaseFee = 1; maxFeePerBlobGas = 1 → accepted
    validate(signedBlobTx(BigInt(1)), cancunHeader) match
      case Left(_: TransactionMaxFeePerBlobGasTooLow) => fail("maxFeePerBlobGas == blobBaseFee must be accepted")
      case _                                          => succeed
  }

  it should "reject blob tx with maxFeePerBlobGas below blobBaseFee with TransactionMaxFeePerBlobGasTooLow" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    implicit val cfg: BlockchainConfig = sepoliaCancunConfig
    // excessBlobGas = 0 → blobBaseFee = 1; maxFeePerBlobGas = 0 → rejected
    validate(signedBlobTx(BigInt(0)), cancunHeader) match
      case Left(err: TransactionMaxFeePerBlobGasTooLow) =>
        err.maxFeePerBlobGas shouldBe BigInt(0)
        err.blobBaseFee shouldBe BlobGasUtils.getBlobGasPrice(BigInt(0), Timestamp(CancunTs + 1), sepoliaCancunConfig)
      case other => fail(s"Expected TransactionMaxFeePerBlobGasTooLow, got: $other")
  }

  it should "not apply blob-gas check to non-blob transactions (no regression)" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    implicit val cfg: BlockchainConfig = sepoliaCancunConfig
    // Legacy tx in a Cancun block — validateMaxFeePerBlobGas must be a no-op
    validate(signedInitcodeTx, cancunHeader) match
      case Left(_: TransactionMaxFeePerBlobGasTooLow) => fail("blob-gas check must not fire for non-blob tx")
      case _                                          => succeed
  }

  it should "reject blob tx on ETC via validateBlobTransactionSupport, not blob-gas check" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    implicit val cfg: BlockchainConfig = etcConfig
    // ETC has no cancunTimestamp → validateBlobTransactionSupport fires first
    val etcHeader = baseHeader.copy(unixTimestamp = Timestamp(CancunTs + 1))
    validate(signedBlobTx(BigInt(0)), etcHeader) match
      case Left(TransactionSyntaxError(msg)) if msg.contains("TYPE_3_TX_NOT_SUPPORTED") => succeed
      case Left(_: TransactionMaxFeePerBlobGasTooLow) =>
        fail("blob-gas check must not fire on ETC (validateBlobTransactionSupport fires first)")
      case other => fail(s"Expected TYPE_3_TX_NOT_SUPPORTED TransactionSyntaxError, got: $other")
  }

  // ── §ETH-T6-B: EIP-2681 nonce overflow enforcement ──────────────────────────
  //
  // EIP-2681: nonces >= 2^64-1 must be rejected (incrementing would overflow uint64).
  // go-ethereum enforces this with ErrNonceMax in state_transition.go.
  // Applies to both ETC and ETH — nonce semantics are identical on both chains.

  private val Eip2681MaxValidNonce: BigInt = BigInt(2).pow(64) - 2 // max accepted nonce
  private val Eip2681OverflowNonce: BigInt = BigInt(2).pow(64) - 1 // first rejected nonce
  private val Eip2681AboveNonce: BigInt = BigInt(2).pow(64) // one above

  private def signedTxWithNonce(n: BigInt): SignedTransaction = SignedTransaction(
    LegacyTransaction(
      nonce = n,
      gasPrice = GasPrice(BigInt("1000000000")),
      gasLimit = GasAmount(BigInt("100000")),
      receivingAddress = Some(Address(0xcafe)),
      value = BigInt(0),
      payload = ByteString.empty
    ),
    pointSign = 0x1b.toByte,
    signatureRandom = realR,
    signature = realS
  )

  it should "accept tx with nonce == 2^64-2 (max valid nonce per EIP-2681)" taggedAs (UnitTest, ConsensusTest) in {
    implicit val cfg: BlockchainConfig = etcConfig
    validate(signedTxWithNonce(Eip2681MaxValidNonce), baseHeader) match
      case Left(TransactionSyntaxError(msg)) if msg.contains("EIP-2681") =>
        fail(s"nonce 2^64-2 must not be rejected by EIP-2681: $msg")
      case _ => succeed
  }

  it should "reject tx with nonce == 2^64-1 (overflow boundary) with EIP-2681 TransactionSyntaxError" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    implicit val cfg: BlockchainConfig = etcConfig
    validate(signedTxWithNonce(Eip2681OverflowNonce), baseHeader) match
      case Left(TransactionSyntaxError(msg)) if msg.contains("EIP-2681") => succeed
      case other => fail(s"Expected EIP-2681 TransactionSyntaxError, got: $other")
  }

  it should "reject tx with nonce == 2^64 (above overflow boundary) with EIP-2681 TransactionSyntaxError" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    implicit val cfg: BlockchainConfig = etcConfig
    validate(signedTxWithNonce(Eip2681AboveNonce), baseHeader) match
      case Left(TransactionSyntaxError(msg)) if msg.contains("EIP-2681") => succeed
      case other => fail(s"Expected EIP-2681 TransactionSyntaxError, got: $other")
  }

  it should "enforce EIP-2681 nonce cap on ETH/Sepolia (same nonce semantics as ETC)" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    implicit val cfg: BlockchainConfig = sepoliaConfig
    val postShanghaiHeader = baseHeader.copy(unixTimestamp = Timestamp(ShanghaiTs + 1))
    validate(signedTxWithNonce(Eip2681OverflowNonce), postShanghaiHeader) match
      case Left(TransactionSyntaxError(msg)) if msg.contains("EIP-2681") => succeed
      case other => fail(s"Expected EIP-2681 TransactionSyntaxError on ETH, got: $other")
  }
