package com.chipprbots.ethereum.consensus.validators

import org.apache.pekko.util.ByteString

import com.typesafe.config.ConfigFactory
import org.bouncycastle.util.encoders.Hex
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.consensus.difficulty.DifficultyCalculator
import com.chipprbots.ethereum.consensus.eip1559.BaseFeeCalculator
import com.chipprbots.ethereum.consensus.validators.BlockHeaderError.*
import com.chipprbots.ethereum.domain.BlockHash
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.HefPostOlympia
import com.chipprbots.ethereum.domain.BlockNumber
import com.chipprbots.ethereum.domain.BloomFilter
import com.chipprbots.ethereum.domain.ChainId
import com.chipprbots.ethereum.domain.Difficulty
import com.chipprbots.ethereum.domain.GasAmount
import com.chipprbots.ethereum.domain.Timestamp
import com.chipprbots.ethereum.domain.TrieRoot
import com.chipprbots.ethereum.domain.UInt256
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.ForkBlockNumbers
import com.chipprbots.ethereum.utils.MonetaryPolicyConfig

/** EIP-1559 one-shot gas-limit elasticity scaling at the fork-activation block.
  *
  * go-ethereum (consensus/misc/eip1559.go, `VerifyEip1559Header`) validates the FIRST post-London block's gas limit
  * against `parent.gasLimit * ElasticityMultiplier`, then applies the ordinary ±1/1024 window AROUND that scaled value.
  * It is NOT an exact-2× equality check: with parent P the activation block may sit anywhere in the open interval (2P −
  * 2P/1024, 2P + 2P/1024).
  *
  * Whether the scaling applies is a per-chain fork-schedule property — `ForkBlockNumbers.olympiaGasLimitElasticity`:
  *   - `Some(2)` on ETH / Sepolia / hive (London semantics)
  *   - `None` on ETC / Mordor / Gorgoroth — the plain ±1/1024 window applies at every block including the Olympia
  *     activation block, because ETC converges 8M → 60M gradually. What is established for ETC is the 60M target
  *     (ECIP-1121 / EIP-7935); whether ETC suppresses the London one-shot scaling is NOT documented in ECIP-1111 or
  *     ECIP-1121 and is pending ECIP-1122. `None` preserves current ETC behaviour byte-for-byte.
  *
  * The two windows are DISJOINT, so the config value is not a nuance — it decides chain membership.
  */
// scalastyle:off magic.number
class OlympiaGasLimitElasticitySpec extends AnyFlatSpec with Matchers:

  // Mocks PoW and difficulty so gas-limit behaviour can be observed in isolation.
  private object TestValidator extends BlockHeaderValidatorSkeleton():
    override protected def difficulty: DifficultyCalculator = new DifficultyCalculator:
      def calculateDifficulty(blockNumber: BigInt, blockTimestamp: Timestamp, parent: BlockHeader)(implicit
          blockchainConfig: BlockchainConfig
      ): Difficulty = parent.difficulty

    override protected def validateEvenMore(blockHeader: BlockHeader)(implicit
        blockchainConfig: BlockchainConfig
    ): Either[BlockHeaderError, BlockHeaderValid] = Right(BlockHeaderValid)

  // ── Numbers ────────────────────────────────────────────────────────────────
  // Parent gas limit chosen so both windows are exactly representable.
  private val OlympiaBlock: BigInt = 500
  private val P: BigInt = 100_000_000 // parent gas limit at the last pre-fork block
  private val RawBound: BigInt = P / 1024 // 97_656
  private val ScaledP: BigInt = P * 2 // 200_000_000
  private val ScaledBound: BigInt = ScaledP / 1024 // 195_312

  // ETC accept window at the activation block: [99_902_345, 100_097_655]
  // ETH accept window at the activation block: [199_804_689, 200_195_311]
  // These are disjoint.

  private val baseConfig: BlockchainConfig = BlockchainConfig(
    forkBlockNumbers = ForkBlockNumbers.Empty.copy(
      frontierBlockNumber = 0,
      homesteadBlockNumber = 0,
      eip106BlockNumber = 0,
      difficultyBombRemovalBlockNumber = 0,
      olympiaBlockNumber = OlympiaBlock
    ),
    daoForkConfig = None,
    maxCodeSize = None,
    chainId = ChainId(0x3d),
    networkId = 1,
    monetaryPolicyConfig = MonetaryPolicyConfig(5000000, 0.2, 5000000000000000000L, 3000000000000000000L),
    customGenesisFileOpt = None,
    customGenesisJsonOpt = None,
    accountStartNonce = UInt256.Zero,
    bootstrapNodes = Set(),
    gasTieBreaker = false,
    ethCompatibleStorage = true
  )

  /** ETC regime: no one-shot scaling at the fork block. */
  private val etcConfig: BlockchainConfig =
    baseConfig.withUpdatedForkBlocks(_.copy(olympiaGasLimitElasticity = None))

  /** ETH / London regime: parent gas limit doubles at the fork block. */
  private val ethConfig: BlockchainConfig =
    baseConfig.withUpdatedForkBlocks(
      _.copy(olympiaGasLimitElasticity = Some(BaseFeeCalculator.ElasticityMultiplier))
    )

  // ── Fixture ────────────────────────────────────────────────────────────────

  private def header(
      number: BigInt,
      gasLimit: BigInt,
      gasUsed: BigInt,
      timestamp: Long,
      baseFee: Option[BigInt]
  ): BlockHeader =
    BlockHeader(
      parentHash = BlockHash(ByteString(Hex.decode("00" * 32))),
      ommersHash = BlockHash(BlockHeader.EmptyOmmers),
      beneficiary = ByteString(Hex.decode("00" * 20)),
      stateRoot = TrieRoot(ByteString(Hex.decode("00" * 32))),
      transactionsRoot = TrieRoot(BlockHeader.EmptyMpt),
      receiptsRoot = TrieRoot(BlockHeader.EmptyMpt),
      logsBloom = BloomFilter(ByteString(Hex.decode("00" * 256))),
      difficulty = Difficulty(1000),
      number = BlockNumber(number),
      gasLimit = GasAmount(gasLimit),
      gasUsed = GasAmount(gasUsed),
      unixTimestamp = Timestamp(timestamp),
      extraData = ByteString.empty,
      mixHash = BlockHash(ByteString(Hex.decode("00" * 32))),
      nonce = ByteString(Hex.decode("00" * 8)),
      extraFields = baseFee.map(HefPostOlympia(_)).getOrElse(BlockHeader.HeaderExtraFields.HefEmpty)
    )

  /** The last pre-fork block (499), gas limit P. gasUsed = gasLimit/2 keeps the EIP-1559 base fee flat. */
  private val preForkParent: BlockHeader =
    header(OlympiaBlock - 1, P, P / 2, 1_000_000, None)

  /** Child at the activation block (500) with the given gas limit. The base fee is taken from the calculator so
    * validateBaseFee (which runs AFTER validateGasLimit) cannot mask a gas-limit verdict.
    */
  private def activationChild(gasLimit: BigInt, cfg: BlockchainConfig, number: BigInt = OlympiaBlock): BlockHeader =
    header(
      number = number,
      gasLimit = gasLimit,
      gasUsed = 0,
      timestamp = preForkParent.unixTimestamp.toLong + 13,
      baseFee = Some(BaseFeeCalculator.calcBaseFee(preForkParent, cfg))
    ).copy(parentHash = preForkParent.hash)

  private def validate(child: BlockHeader, parent: BlockHeader, cfg: BlockchainConfig) =
    TestValidator.validate(child, parent)(using cfg)

  /** True when the header passes the gas-limit rule. Uses the full validator, so an accept is only reported when no
    * HeaderGasLimitError was raised — validateGasLimit runs 5th, before number/extraFields/baseFee.
    */
  private def gasLimitAccepted(child: BlockHeader, parent: BlockHeader, cfg: BlockchainConfig): Boolean =
    validate(child, parent, cfg) != Left(HeaderGasLimitError)

  // ── 1. Activation block: ETH window is centred on the SCALED parent ────────

  "olympiaGasLimitElasticity = Some(2)" should
    "accept exactly 2x the parent gas limit at the activation block" taggedAs (UnitTest, ConsensusTest) in {
      validate(activationChild(ScaledP, ethConfig), preForkParent, ethConfig) shouldBe Right(BlockHeaderValid)
    }

  it should "accept the upper edge of the scaled window (2P + bound - 1)" taggedAs (UnitTest, ConsensusTest) in {
    validate(activationChild(ScaledP + ScaledBound - 1, ethConfig), preForkParent, ethConfig) shouldBe
      Right(BlockHeaderValid)
  }

  it should "reject one past the upper edge of the scaled window (2P + bound)" taggedAs (UnitTest, ConsensusTest) in {
    validate(activationChild(ScaledP + ScaledBound, ethConfig), preForkParent, ethConfig) shouldBe
      Left(HeaderGasLimitError)
  }

  it should "accept the lower edge of the scaled window (2P - bound + 1)" taggedAs (UnitTest, ConsensusTest) in {
    validate(activationChild(ScaledP - ScaledBound + 1, ethConfig), preForkParent, ethConfig) shouldBe
      Right(BlockHeaderValid)
  }

  it should "reject one past the lower edge of the scaled window (2P - bound)" taggedAs (UnitTest, ConsensusTest) in {
    validate(activationChild(ScaledP - ScaledBound, ethConfig), preForkParent, ethConfig) shouldBe
      Left(HeaderGasLimitError)
  }

  // The discriminating case for "scaled diff, unscaled divisor". 200_100_000 is 100_000 away
  // from the scaled parent: inside the scaled bound (195_312) but OUTSIDE the raw-parent
  // bound (97_656). An implementation that scales the diff but keeps parent/1024 as the
  // divisor rejects this header — a 2x too-tight window at exactly the block that matters.
  it should "accept 2P + 100_000 — inside the scaled bound but outside the raw-parent bound" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    val h = activationChild(ScaledP + 100_000, ethConfig)
    (ScaledP + 100_000 - ScaledP) should be < ScaledBound // 100_000 < 195_312
    (ScaledP + 100_000 - ScaledP) should be > RawBound // 100_000 > 97_656
    validate(h, preForkParent, ethConfig) shouldBe Right(BlockHeaderValid)
  }

  it should "reject an unchanged gas limit at the activation block" taggedAs (UnitTest, ConsensusTest) in {
    // Under London the activation block MUST move: staying at P is 100_000_000 away from 2P.
    validate(activationChild(P, ethConfig), preForkParent, ethConfig) shouldBe Left(HeaderGasLimitError)
  }

  // ── 2. Activation block: ETC keeps the plain +/-1/1024 window ──────────────

  "olympiaGasLimitElasticity = None (ETC)" should
    "accept an unchanged gas limit at the Olympia activation block" taggedAs (UnitTest, ConsensusTest) in {
      validate(activationChild(P, etcConfig), preForkParent, etcConfig) shouldBe Right(BlockHeaderValid)
    }

  it should "accept the upper edge of the raw window (P + P/1024 - 1)" taggedAs (UnitTest, ConsensusTest) in {
    validate(activationChild(P + RawBound - 1, etcConfig), preForkParent, etcConfig) shouldBe Right(BlockHeaderValid)
  }

  it should "reject 2x the parent gas limit at the Olympia activation block" taggedAs (UnitTest, ConsensusTest) in {
    validate(activationChild(ScaledP, etcConfig), preForkParent, etcConfig) shouldBe Left(HeaderGasLimitError)
  }

  // ── 3. The twin: one fixture, two configs, DISJOINT accept windows ─────────

  "the two elasticity regimes" should
    "produce disjoint accept windows at the activation block from one identical fixture" taggedAs (
      UnitTest,
      ConsensusTest
    ) in {
      // ETC accepts, ETH rejects
      val etcOnly = List(P, P + RawBound - 1, P - RawBound + 1)
      // ETH accepts, ETC rejects
      val ethOnly = List(ScaledP, ScaledP + ScaledBound - 1, ScaledP - ScaledBound + 1, ScaledP + 100_000)

      etcOnly.foreach { gl =>
        withClue(s"gasLimit=$gl under ETC (None): ") {
          gasLimitAccepted(activationChild(gl, etcConfig), preForkParent, etcConfig) shouldBe true
        }
        withClue(s"gasLimit=$gl under ETH (Some(2)): ") {
          gasLimitAccepted(activationChild(gl, ethConfig), preForkParent, ethConfig) shouldBe false
        }
      }

      ethOnly.foreach { gl =>
        withClue(s"gasLimit=$gl under ETH (Some(2)): ") {
          gasLimitAccepted(activationChild(gl, ethConfig), preForkParent, ethConfig) shouldBe true
        }
        withClue(s"gasLimit=$gl under ETC (None): ") {
          gasLimitAccepted(activationChild(gl, etcConfig), preForkParent, etcConfig) shouldBe false
        }
      }
    }

  // ── 4. The scaling fires EXACTLY ONCE ──────────────────────────────────────
  // At block N+1 the elasticity multiplier must not apply again: both regimes must agree,
  // because the parent no longer crosses the fork.

  private val postForkParentDoubled: BlockHeader =
    header(OlympiaBlock, ScaledP, ScaledP / 2, 1_000_100, Some(BaseFeeCalculator.InitialBaseFee))

  private def nextChild(gasLimit: BigInt, parent: BlockHeader, cfg: BlockchainConfig): BlockHeader =
    header(
      number = parent.number.value + 1,
      gasLimit = gasLimit,
      gasUsed = 0,
      timestamp = parent.unixTimestamp.toLong + 13,
      baseFee = Some(BaseFeeCalculator.calcBaseFee(parent, cfg))
    ).copy(parentHash = parent.hash)

  "the elasticity scaling" should
    "NOT fire again at block N+1 — 4x the pre-fork limit is rejected under ETH" taggedAs (
      UnitTest,
      ConsensusTest
    ) in {
      // If the multiplier applied a second time, the effective parent would be 400_000_000
      // and this header (diff 0 from it) would be accepted. It must be rejected.
      validate(nextChild(ScaledP * 2, postForkParentDoubled, ethConfig), postForkParentDoubled, ethConfig) shouldBe
        Left(HeaderGasLimitError)
    }

  it should "accept an unchanged gas limit at block N+1 under ETH" taggedAs (UnitTest, ConsensusTest) in {
    validate(nextChild(ScaledP, postForkParentDoubled, ethConfig), postForkParentDoubled, ethConfig) shouldBe
      Right(BlockHeaderValid)
  }

  it should "give IDENTICAL verdicts under both regimes at block N+1" taggedAs (UnitTest, ConsensusTest) in {
    // A post-fork parent that never doubled (gas limit still P) isolates the window width:
    // at N+1 the bound must be P/1024 = 97_656 under BOTH configs.
    val postForkParentFlat =
      header(OlympiaBlock, P, P / 2, 1_000_100, Some(BaseFeeCalculator.InitialBaseFee))

    val probes = List(
      P, // unchanged
      P + RawBound - 1, // raw upper edge
      P + RawBound, // one past raw upper edge
      P - RawBound + 1, // raw lower edge
      P - RawBound, // one past raw lower edge
      P + 100_000, // inside the SCALED bound, outside the raw bound
      ScaledP // a doubling attempt one block late
    )

    probes.foreach { gl =>
      val etcVerdict = gasLimitAccepted(nextChild(gl, postForkParentFlat, etcConfig), postForkParentFlat, etcConfig)
      val ethVerdict = gasLimitAccepted(nextChild(gl, postForkParentFlat, ethConfig), postForkParentFlat, ethConfig)
      withClue(s"gasLimit=$gl at block N+1 — ETC=$etcVerdict ETH=$ethVerdict: ") {
        ethVerdict shouldBe etcVerdict
      }
    }

    // ...and pin the shared verdicts so the agreement is not vacuous.
    gasLimitAccepted(nextChild(P, postForkParentFlat, ethConfig), postForkParentFlat, ethConfig) shouldBe true
    gasLimitAccepted(
      nextChild(P + 100_000, postForkParentFlat, ethConfig),
      postForkParentFlat,
      ethConfig
    ) shouldBe false
    gasLimitAccepted(nextChild(ScaledP, postForkParentFlat, ethConfig), postForkParentFlat, ethConfig) shouldBe false
  }

  // ── 5. Predicate keys on the PARENT crossing, not on child.number == fork ──

  it should "key on two independent height comparisons, not on validateNumber having run" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    // validateGasLimit runs BEFORE validateNumber, so it must not assume child == parent + 1.
    // Parent 499 (pre-fork) with a child at 501 (post-fork) still crosses the fork: the gas
    // limit check must scale, and the header must fail on the NUMBER rule, not the gas rule.
    val child = activationChild(ScaledP, ethConfig, number = OlympiaBlock + 1)
    validate(child, preForkParent, ethConfig) shouldBe Left(HeaderNumberError)
  }

  it should "not scale when the parent is already post-fork" taggedAs (UnitTest, ConsensusTest) in {
    // Both parent and child post-fork → raw window, even under Some(2).
    validate(nextChild(ScaledP * 2, postForkParentDoubled, ethConfig), postForkParentDoubled, ethConfig) shouldBe
      Left(HeaderGasLimitError)
  }

  // ── 6. Config matrix over the real shipped chain confs ─────────────────────

  "the shipped chain configurations" should
    "set olympiaGasLimitElasticity only on the ETH-family chains" taggedAs (UnitTest, ConsensusTest) in {
      val full = ConfigFactory.load()
      def elasticityOf(chain: String): Option[Int] =
        BlockchainConfig
          .fromRawConfig(full.getConfig(s"fukuii.blockchains.$chain"))
          .forkBlockNumbers
          .olympiaGasLimitElasticity

      // ETC family: NO one-shot scaling at Olympia. Changing any of these is a chain split.
      withClue("etc: ")(elasticityOf("etc") shouldBe None)
      withClue("mordor: ")(elasticityOf("mordor") shouldBe None)
      withClue("gorgoroth: ")(elasticityOf("gorgoroth") shouldBe None)
      withClue("test: ")(elasticityOf("test") shouldBe None)

      // ETH family: London semantics.
      withClue("eth: ")(elasticityOf("eth") shouldBe Some(2))
      withClue("sepolia: ")(elasticityOf("sepolia") shouldBe Some(2))
      withClue("hive: ")(elasticityOf("hive") shouldBe Some(2))
    }
// scalastyle:on magic.number
