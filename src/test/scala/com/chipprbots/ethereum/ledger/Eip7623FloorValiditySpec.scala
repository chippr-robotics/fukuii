package com.chipprbots.ethereum.ledger

import org.apache.pekko.util.ByteString

import org.bouncycastle.util.encoders.Hex
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.consensus.validators.SignedTransactionError
import com.chipprbots.ethereum.consensus.validators.SignedTransactionError.*
import com.chipprbots.ethereum.consensus.validators.SignedTransactionValid
import com.chipprbots.ethereum.consensus.validators.std.StdSignedTransactionValidator
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.*
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.ForkTimestamps
import com.chipprbots.ethereum.utils.NetworkType

/** #1438: EIP-7623's validity rule, `tx.gas >= 21,000 + 10 * tokens`, wherever EIP-7623 is active before Amsterdam —
  * ETH Prague and Osaka, ETC Olympia.
  *
  * execution-specs prague/osaka `validate_transaction`: `max(intrinsic.regular, intrinsic.calldata_floor) > tx.gas`
  * raises; go-ethereum `ErrFloorDataGas` from `IsPrague`, in the state transition and the txpool. fukuii charged the
  * floor but never checked it: a transaction below its floor executed, was charged more than its gas limit, and the
  * negative refund wrapped in UInt256.
  *
  * The floor and its activation are BlockPreparator's own (`calcFloorDataGas`, `eip7623Active`), so what the validator
  * admits is exactly what the preparator can charge without going over the limit.
  */
// scalastyle:off magic.number
class Eip7623FloorValiditySpec extends AnyFlatSpec with Matchers with AmsterdamFixtureVectors:

  private val recipient: Address = Address(Hex.decode("83c7e323d189f18725ac510004fdc2941f8c4a78"))
  private val senderAccount: Account = Account(nonce = UInt256(0), balance = UInt256(BigInt(10).pow(30)))

  private def nonZeros(n: Int): ByteString = ByteString(Array.fill[Byte](n)(0x01))

  private def validate(
      stx: SignedTransaction,
      header: BlockHeader,
      config: BlockchainConfig
  ): Either[SignedTransactionError, SignedTransactionValid] =
    val upfront = UInt256(stx.tx.gasLimit.value * BigInt(1_000_000_000))
    StdSignedTransactionValidator.validate(stx, senderAccount, header, upfront, 0)(config)

  /** 1,000 non-zero bytes to an EOA: intrinsic 21,000 + 16,000 = 37,000; EIP-7623 floor 21,000 + 4,000 x 10 = 61,000.
    */
  private def heavyCall(gasLimit: BigInt, config: BlockchainConfig): SignedTransaction =
    dynamicFeeTx(Some(recipient), value = 0, gasLimit = gasLimit, payload = nonZeros(1000), config = config)

  // preAmsterdamConfig: Shanghai 0 / Cancun 60 / Prague 120 / Osaka 180, olympiaBlockNumber 0 (hive's London mapping).
  private val atCancun = preAmsterdamHeader(46, 60)
  private val atPrague = preAmsterdamHeader(46, 120)
  private val atOsaka = preAmsterdamHeader(46, 180)

  // ── ETH: the block validator ─────────────────────────────────────────────────────────────────────────────────────

  Seq("Prague" -> atPrague, "Osaka" -> atOsaka).foreach { case (fork, header) =>
    s"ETH $fork: a transaction below its EIP-7623 floor" should "be invalid, reported as the floor error" taggedAs (
      UnitTest,
      ConsensusTest
    ) in {
      val result = validate(heavyCall(60999, preAmsterdamConfig), header, preAmsterdamConfig)
      result shouldBe Left(TransactionNotEnoughGasForFloorError(60999, 61000))
      result.left.toOption.get.toString should startWith("INTRINSIC_GAS_BELOW_FLOOR_GAS_COST")
    }

    it should "be valid with exactly the floor" taggedAs (UnitTest, ConsensusTest) in {
      validate(heavyCall(61000, preAmsterdamConfig), header, preAmsterdamConfig) shouldBe Right(SignedTransactionValid)
    }

    it should "report the intrinsic error first when it is below both" taggedAs (UnitTest, ConsensusTest) in {
      validate(heavyCall(36999, preAmsterdamConfig), header, preAmsterdamConfig) shouldBe
        Left(TransactionNotEnoughGasForIntrinsicError(36999, 37000))
    }

    it should "anchor a creation's floor at 21,000, without the 32,000 creation cost" taggedAs (
      UnitTest,
      ConsensusTest
    ) in {
      // 2,000 non-zero bytes of initcode: intrinsic 21,000 + 32,000 + 32,000 + 2 x 63 words = 85,126; floor 21,000 +
      // 8,000 x 10 = 101,000 (133,000 if the creation cost were folded in).
      def create(gasLimit: BigInt) =
        dynamicFeeTx(None, value = 0, gasLimit = gasLimit, payload = nonZeros(2000), config = preAmsterdamConfig)
      validate(create(100999), header, preAmsterdamConfig) shouldBe
        Left(TransactionNotEnoughGasForFloorError(100999, 101000))
      validate(create(101000), header, preAmsterdamConfig) shouldBe Right(SignedTransactionValid)
    }
  }

  "ETH Cancun (before EIP-7623)" should "accept the transaction at its intrinsic cost, below the floor, as before" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    // olympiaBlockNumber is 0 on this ETH chain (London), so this also shows the ETC arm of the predicate stays off.
    validate(heavyCall(37000, preAmsterdamConfig), atCancun, preAmsterdamConfig) shouldBe Right(SignedTransactionValid)
  }

  // ── ETC: Olympia (ECIP-1121) ─────────────────────────────────────────────────────────────────────────────────────

  private val OlympiaBlock = BigInt(1000)

  private val etcConfig: BlockchainConfig =
    setup.blockchainConfig
      .copy(networkType = NetworkType.ETC, forkTimestamps = ForkTimestamps())
      .withUpdatedForkBlocks(
        _.copy(
          frontierBlockNumber = 0,
          homesteadBlockNumber = 0,
          eip150BlockNumber = 0,
          eip155BlockNumber = 0,
          eip160BlockNumber = 0,
          eip161BlockNumber = 0,
          byzantiumBlockNumber = 0,
          constantinopleBlockNumber = 0,
          petersburgBlockNumber = 0,
          istanbulBlockNumber = 0,
          atlantisBlockNumber = 0,
          aghartaBlockNumber = 0,
          phoenixBlockNumber = 0,
          magnetoBlockNumber = 0,
          berlinBlockNumber = 0,
          mystiqueBlockNumber = 0,
          spiralBlockNumber = 900,
          olympiaBlockNumber = OlympiaBlock
        )
      )

  private def etcHeader(number: BigInt): BlockHeader =
    Fixtures.Blocks.ValidBlock.header.copy(
      number = BlockNumber(number),
      gasLimit = GasAmount(30_000_000),
      gasUsed = GasAmount.Zero,
      extraFields = if number >= OlympiaBlock then HefPostOlympia(BigInt(1)) else HefEmpty
    )

  private def etcHeavyCall(gasLimit: BigInt): SignedTransaction =
    legacyTx(Some(recipient), value = 0, gasLimit = gasLimit, payload = nonZeros(1000), config = etcConfig)

  "ETC Olympia: a legacy transaction below its EIP-7623 floor" should "be invalid, and valid at the floor" taggedAs (
    UnitTest,
    ConsensusTest,
    OlympiaTest
  ) in {
    validate(etcHeavyCall(60999), etcHeader(OlympiaBlock), etcConfig) shouldBe
      Left(TransactionNotEnoughGasForFloorError(60999, 61000))
    validate(etcHeavyCall(61000), etcHeader(OlympiaBlock), etcConfig) shouldBe Right(SignedTransactionValid)
  }

  "ETC before Olympia (Spiral)" should "accept the transaction at its intrinsic cost, below the floor, as before" taggedAs (
    UnitTest,
    ConsensusTest,
    OlympiaTest
  ) in {
    validate(etcHeavyCall(37000), etcHeader(OlympiaBlock - 1), etcConfig) shouldBe Right(SignedTransactionValid)
  }

  // ── The txpool's stateless pre-filter ────────────────────────────────────────────────────────────────────────────

  private val osakaAtGenesis: BlockchainConfig = preAmsterdamConfig.copy(forkTimestamps =
    ForkTimestamps(
      shanghaiTimestamp = Some(0L),
      cancunTimestamp = Some(0L),
      pragueTimestamp = Some(0L),
      osakaTimestamp = Some(0L)
    )
  )
  private val cancunAtGenesis: BlockchainConfig =
    preAmsterdamConfig.copy(forkTimestamps = ForkTimestamps(shanghaiTimestamp = Some(0L), cancunTimestamp = Some(0L)))

  "The txpool pre-filter on an ETH chain past Prague" should "drop a transaction below its floor and keep one at it" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    SignedTransactionWithSender.getStatelessValidTransactions(Seq(heavyCall(60999, osakaAtGenesis)))(
      osakaAtGenesis
    ) shouldBe empty
    SignedTransactionWithSender.getStatelessValidTransactions(Seq(heavyCall(61000, osakaAtGenesis)))(
      osakaAtGenesis
    ) should have size 1
  }

  it should "keep applying the intrinsic rule alone on an ETH chain that has no Prague" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    SignedTransactionWithSender.getStatelessValidTransactions(Seq(heavyCall(37000, cancunAtGenesis)))(
      cancunAtGenesis
    ) should have size 1
  }

  it should "leave ETC admission unchanged: its Olympia-by-construction config cannot tell whether Olympia is active" taggedAs (
    UnitTest,
    ConsensusTest,
    OlympiaTest
  ) in {
    SignedTransactionWithSender.getStatelessValidTransactions(Seq(etcHeavyCall(37000)))(etcConfig) should have size 1
  }

  it should "leave admission unchanged where Amsterdam is scheduled: its latest-fork proxy cannot name the floor" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    // amsterdamConfig: Osaka at 180, Amsterdam at 360. The proxy picks Osaka, whose floor Amsterdam replaces.
    SignedTransactionWithSender.getStatelessValidTransactions(Seq(heavyCall(37000, amsterdamConfig)))(
      amsterdamConfig
    ) should have size 1
  }

  // ── Execution: the block is rejected, and the refund can no longer wrap ──────────────────────────────────────────

  /** The test setup's own preparator validates nothing (MockValidatorsAlwaysSucceed); this one uses the real rule. */
  private lazy val validatingPreparator =
    new BlockPreparator(new VMImpl, StdSignedTransactionValidator, setup.blockchain, setup.blockchainReader)

  "A Prague block holding a transaction below its floor" should "be rejected, the transaction reported as the floor error" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    val result =
      validatingPreparator.executeTransactions(Seq(heavyCall(60999, preAmsterdamConfig)), block45World, atPrague)(
        preAmsterdamConfig
      )
    result.left.map(_.reason).left.toOption.getOrElse("") should startWith("INTRINSIC_GAS_BELOW_FLOOR_GAS_COST")
  }

  it should "execute when the transaction carries exactly its floor" taggedAs (UnitTest, ConsensusTest) in {
    val result =
      validatingPreparator.executeTransactions(Seq(heavyCall(61000, preAmsterdamConfig)), block45World, atPrague)(
        preAmsterdamConfig
      )
    result.map(_.receipts.map(_.cumulativeGasUsed)) shouldBe Right(Seq(BigInt(61000)))
  }

  "Executing a transaction below its floor without validating it" should "fail loudly instead of wrapping the refund" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    // Only a path that skips the validator can get here. Before, it returned gasUsed 61,000 for a 60,999 gas limit and
    // refunded the sender -1 x gasPrice, wrapped modulo 2^256.
    val thrown = intercept[IllegalStateException] {
      execute(heavyCall(60999, preAmsterdamConfig), atPrague, block45World, preAmsterdamConfig)
    }
    thrown.getMessage should include("calldata floor 61000")
  }

  it should "charge exactly the floor, and no more than the gas limit, at the floor" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    execute(heavyCall(61000, preAmsterdamConfig), atPrague, block45World, preAmsterdamConfig).gasUsed shouldBe
      BigInt(61000)
  }
