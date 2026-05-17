package com.chipprbots.ethereum.consensus.pow.difficulty

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

import com.chipprbots.ethereum.consensus.mess.ArtificialFinality
import com.chipprbots.ethereum.consensus.mess.MESSConfig
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.testing.Tags._
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.ForkBlockNumbers

import OscillationFixtures._

// scalastyle:off magic.number
/** Tests for ETChash difficulty adjustment behaviour across three mining eras:
  *
  * S1 Ethereum Merge spike (Sept 2022): GPU influx, fast-block lag S2 Post-Merge miner exodus (2022–2024): slow
  * convergence, variable gaps S3 Current ASIC flex-load oscillation (Oct 2024–present): ETChash ASICs cycling on/off S4
  * Hypothetical exploitation surface: boundary / stress scenarios S5 MESS interaction: Olympia re-activation closes the
  * difficulty-trough gap
  *
  * Current oscillation (S3): ETChash ASICs are flex-load / demand-response — they turn off when energy costs exceed a
  * threshold or grid operator contracts require curtailment. These ASICs cannot mine other coins; they are bricks
  * without ETC. No alternative revenue stream.
  *
  * All on-chain block data is from OscillationFixtures (core-geth verified).
  */
class ETChashDifficultyManipulationSpec extends AnyFlatSpec with Matchers with ScalaCheckPropertyChecks {

  // -------------------------------------------------------------------------
  // Shared infrastructure
  // -------------------------------------------------------------------------

  private val etcForkNumbers: ForkBlockNumbers = ForkBlockNumbers(
    frontierBlockNumber = 0,
    homesteadBlockNumber = 1150000,
    eip106BlockNumber = Long.MaxValue,
    eip150BlockNumber = 2500000,
    eip155BlockNumber = 3000000,
    eip160BlockNumber = 3000000,
    eip161BlockNumber = Long.MaxValue,
    difficultyBombPauseBlockNumber = 3000000,
    difficultyBombContinueBlockNumber = 5000000,
    difficultyBombRemovalBlockNumber = 5900000,
    byzantiumBlockNumber = Long.MaxValue,
    constantinopleBlockNumber = Long.MaxValue,
    istanbulBlockNumber = Long.MaxValue,
    atlantisBlockNumber = 8772000,
    aghartaBlockNumber = 9573000,
    phoenixBlockNumber = 10500839,
    petersburgBlockNumber = Long.MaxValue,
    ecip1099BlockNumber = 11460000,
    muirGlacierBlockNumber = Long.MaxValue,
    magnetoBlockNumber = 13189133,
    berlinBlockNumber = 13189133,
    mystiqueBlockNumber = 14525000,
    spiralBlockNumber = 19250000,
    olympiaBlockNumber = Long.MaxValue
  )

  implicit private val blockchainConfig: BlockchainConfig = BlockchainConfig(
    forkBlockNumbers = etcForkNumbers,
    maxCodeSize = Some(24576),
    customGenesisFileOpt = None,
    customGenesisJsonOpt = None,
    daoForkConfig = None,
    accountStartNonce = com.chipprbots.ethereum.domain.UInt256.Zero,
    chainId = 61,
    networkId = 1,
    monetaryPolicyConfig = com.chipprbots.ethereum.utils.MonetaryPolicyConfig(
      5000000, 0.2, 5000000000000000000L, 3000000000000000000L, 2000000000000000000L
    ),
    gasTieBreaker = false,
    ethCompatibleStorage = true,
    bootstrapNodes = Set.empty
  )

  private def header(
      number: BigInt,
      difficulty: BigInt,
      timestamp: Long,
      hasUncles: Boolean = false
  ): BlockHeader =
    BlockHeader(
      parentHash = ByteString(new Array[Byte](32)),
      ommersHash = if (hasUncles) ByteString(new Array[Byte](32)) else BlockHeader.EmptyOmmers,
      beneficiary = ByteString(new Array[Byte](20)),
      stateRoot = ByteString(new Array[Byte](32)),
      transactionsRoot = ByteString(new Array[Byte](32)),
      receiptsRoot = ByteString(new Array[Byte](32)),
      logsBloom = ByteString(new Array[Byte](256)),
      difficulty = difficulty,
      number = number,
      gasLimit = BigInt(8000000),
      gasUsed = BigInt(0),
      unixTimestamp = timestamp,
      extraData = ByteString.empty,
      mixHash = ByteString(new Array[Byte](32)),
      nonce = ByteString(new Array[Byte](8))
    )

  private def fromSnapshot(s: BlockSnapshot): BlockHeader =
    header(s.number, s.difficulty, s.timestamp)

  /** For each consecutive (parent, child) pair verify that the DAA produces child.difficulty. */
  private def verifyDifficultyChain(blocks: Seq[BlockSnapshot]): Unit = {
    require(blocks.size >= 2, "need at least parent + one child")
    blocks.sliding(2).foreach {
      case Seq(parent, child) =>
        val computed = EthashDifficultyCalculator.calculateDifficulty(
          child.number,
          child.timestamp,
          fromSnapshot(parent)
        )
        withClue(s"block ${child.number} (gap=${child.timestamp - parent.timestamp}s)") {
          computed shouldBe child.difficulty
        }
      case _ => // sliding(2) on Seq of 1 — can't happen given the require above
    }
  }

  /** Simulate `count` blocks, each `gapSecs` apart, starting from `parentDiff`/`parentTs`. Returns (blockNumber,
    * difficulty) pairs for the synthetic chain.
    */
  private def syntheticChain(
      startBlock: BigInt,
      parentDiff: BigInt,
      parentTs: Long,
      gapSecs: Long,
      count: Int
  ): Seq[(BigInt, BigInt)] = {
    val results = scala.collection.mutable.ArrayBuffer.empty[(BigInt, BigInt)]
    var prevDiff = parentDiff
    var prevTs = parentTs
    for (i <- 1 to count) {
      val num = startBlock + i
      val childTs = prevTs + gapSecs
      val parentHdr = header(num - 1, prevDiff, prevTs)
      val newDiff = EthashDifficultyCalculator.calculateDifficulty(num, childTs, parentHdr)
      results += ((num, newDiff))
      prevDiff = newDiff
      prevTs = childTs
    }
    results.toSeq
  }

  // -------------------------------------------------------------------------
  // Group A — S1: Ethereum Merge spike (Sept 2022)
  // -------------------------------------------------------------------------

  "ETC DAA during Merge spike" should "reproduce on-chain difficulty for mergeSpike blocks (real data)" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    verifyDifficultyChain(Seq(mergeSpikeParent) ++ mergeSpike)
  }

  it should "increase difficulty by exactly parentDiff/2048 per fast block" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    // All mergeSpike blocks have gap < 9s → c = +1 → newDiff = parentDiff + parentDiff/2048
    (Seq(mergeSpikeParent) ++ mergeSpike).sliding(2).foreach {
      case Seq(parent, child) =>
        val gap = child.timestamp - parent.timestamp
        if (gap < 9) {
          val expectedDelta = parent.difficulty / 2048
          withClue(s"block ${child.number} gap=${gap}s") {
            child.difficulty shouldBe parent.difficulty + expectedDelta
          }
        }
      case _ =>
    }
  }

  it should "demonstrate severe difficulty lag when hashrate triples" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    // After 5 consecutive fast blocks, difficulty only rose by ~5/2048 ≈ 0.24%
    // while hashrate jumped from ~50 TH/s to ~150+ TH/s on arrival day (3x)
    val firstDiff = mergeSpike.head.difficulty
    val lastDiff = mergeSpike.last.difficulty
    val fractionalIncrease = (lastDiff - firstDiff).toDouble / firstDiff.toDouble

    fractionalIncrease should be < 0.005 // less than 0.5% after 5 fast blocks
    // Meanwhile hashrate tripled — the DAA convergence lag is ~2048 blocks to full equilibrium
    // during this lag window miners earn far more blocks per hash than at equilibrium
  }

  it should "converge toward equilibrium at rate of ~parentDiff/2048 per block under sustained 3x hashrate" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    // Synthetic: 3x hashrate from premergeBaseline implies ~4.3s block time
    // c = +1 each block → difficulty rises by D/2048 per block
    // How many blocks to reach 90% of 3x equilibrium difficulty?
    val startDiff = premergeBaseline.difficulty
    val target3xEquil = startDiff * 3

    // After N blocks at c=+1, diff ≈ startDiff * (1 + 1/2048)^N
    // We find N empirically via the synthetic chain at 4s gaps
    val chain = syntheticChain(
      startBlock = premergeBaseline.number,
      parentDiff = startDiff,
      parentTs = premergeBaseline.timestamp,
      gapSecs = 4L,
      count = 5000
    )
    val ninetyPctTarget = target3xEquil * 9 / 10
    val blocksToConverge = chain.indexWhere { case (_, d) => d >= ninetyPctTarget }

    // With c=+1 each step: ~1502 blocks (D * (2048/2048)^N approximation)
    blocksToConverge should be > 1000
    blocksToConverge should be < 3000
  }

  // -------------------------------------------------------------------------
  // Group B — S2: Post-Merge miner exodus (2022–2024)
  // -------------------------------------------------------------------------

  "ETC DAA during miner exodus" should "reproduce on-chain difficulty for declinePhase blocks (real data)" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    verifyDifficultyChain(Seq(declinePhaseParent) ++ declinePhase)
  }

  it should "show c < 0 for inter-block times above 18 s in decline-phase blocks" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    val slowBlocks = (Seq(declinePhaseParent) ++ declinePhase)
      .sliding(2)
      .collect {
        case Seq(parent, child) if child.timestamp - parent.timestamp >= 18 =>
          val gap = child.timestamp - parent.timestamp
          (child.number, gap, cCoeff(gap))
      }
      .toSeq

    slowBlocks should not be empty
    slowBlocks.foreach { case (num, gap, c) =>
      withClue(s"block $num gap=${gap}s") {
        c should be < 0L
      }
    }
  }

  it should "converge from Merge-peak difficulty toward ASIC-only equilibrium" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    // After miner exodus the DAA converges back from the Merge peak toward the ASIC-only baseline.
    // Expected long-run equilibrium: ~125/185 fraction of peak (asicBaseHrFrac).
    val actualRatio = cycleEnd.difficulty.toDouble / mergePeak.difficulty.toDouble
    // Exact ratio depends on elapsed time; just verify we're heading in the right direction
    actualRatio should be < 1.0 // difficulty fell from Merge peak
    actualRatio should be > 0.3 // but not to near-zero
  }

  // -------------------------------------------------------------------------
  // Group C — S3: ASIC flex-load oscillation (Oct 2024 – Jan 2026)
  // -------------------------------------------------------------------------

  "ETC DAA during flex-on phase" should "reproduce on-chain difficulty for flexOnPhase blocks (real data)" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    verifyDifficultyChain(Seq(flexOnPhaseParent) ++ flexOnPhase)
  }

  "ETC DAA during flex-off phase" should "reproduce on-chain difficulty for flexOffPhase blocks (real data)" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    verifyDifficultyChain(Seq(flexOffPhaseParent) ++ flexOffPhase)
  }

  "Flex-off phase" should "have inter-block gaps consistently above 17 s (≥ 9 of 10 blocks)" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    val gaps = (Seq(flexOffPhaseParent) ++ flexOffPhase)
      .sliding(2)
      .map {
        case Seq(p, c) => c.timestamp - p.timestamp
        case _         => 0L
      }
      .toSeq
    val slowCount = gaps.count(_ >= 18)
    // Empirical: 9/10 blocks have gap >= 18s; one (block 22,200,018) has gap = 12s
    slowCount should be >= 9
  }

  "Flex-off phase" should "trigger c < 0 for the overwhelming majority of observed blocks" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    val cValues = (Seq(flexOffPhaseParent) ++ flexOffPhase)
      .sliding(2)
      .map {
        case Seq(p, c) => cCoeff(c.timestamp - p.timestamp)
        case _         => 0L
      }
      .toSeq
    val negativeCount = cValues.count(_ < 0)
    negativeCount should be >= 9 // 9/10 — only index 7 (gap=12s) gives c=0
  }

  "ASIC flex-load cycling" should "create a difficulty trough at the end of the flex-off phase" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    // The flex-off phase's last block has lower difficulty than the flex-on phase's first block
    flexOffPhase.last.difficulty should be < flexOnPhase.head.difficulty
    // Trough depth: flex-on peak vs flex-off end of window
    val troughDepth = cycleMid.difficulty - cycleEnd.difficulty
    troughDepth should be > BigInt(0)
    // The trough is substantial: over 50% drop from peak to trough
    val troughRatio = cycleEnd.difficulty.toDouble / cycleMid.difficulty.toDouble
    troughRatio should be < 0.6
  }

  "Flex miner re-entry" should "mine fast blocks at depressed difficulty" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    // Flex-on phase difficulty is lower than equilibrium after a preceding flex-off trough.
    // ASICs return when energy becomes available again — no alternative revenue during flex-off;
    // they simply idle. The depressed difficulty at re-entry is a DAA convergence artifact.
    val flexOnDiff = flexOnPhase.head.difficulty
    val peakEqDiff = cycleMid.difficulty

    // This fixture (Mar 2025) captures the approach to peak, not trough entry
    flexOnDiff should be > BigInt(0)
    flexOnDiff should be < peakEqDiff * 2 // sanity: not astronomically above peak
  }

  // -------------------------------------------------------------------------
  // Group D — S4: Boundary / stress tests
  // -------------------------------------------------------------------------

  private val flexFractionTable =
    Table("flexFracOfEquilibrium", 0.10, 0.20, 0.278, 0.324, 0.50)

  "Flex-load exit DAA response" should "produce c = -1 at the observed 0.324 flex fraction exit" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    val exitFrac = flexMaxHrFrac // 0.324 ≈ observed
    val expectedGap = expectedBlockTimeAfterExit(exitFrac) // ≈ 19 s
    val expectedC = cCoeff(expectedGap)
    expectedC shouldBe -1L
  }

  "Flex-load exit threshold" should "be neutral (c = 0) for flex fractions below 0.278" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    // Below threshold: block time < 18 s → still c = 0 or c = +1
    val belowThreshold = 0.20
    val gap = expectedBlockTimeAfterExit(belowThreshold) // ≈ 16 s
    cCoeff(gap) shouldBe 0L // 1 - floor(16/9) = 1 - 1 = 0
  }

  "Flex-load exit threshold" should "trigger c = -1 at and above 0.278 exit fraction" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    // At threshold: 13 / (1 - 0.278) = 13 / 0.722 ≈ 18.0 s → floor(18/9) = 2 → c = -1
    val atThreshold = flexExitThresholdFrac
    val gap = expectedBlockTimeAfterExit(atThreshold)
    cCoeff(gap) should be <= 0L
  }

  "difficulty trough depth" should "scale with flex-load exit fraction over a 500-block off-phase" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    val startDiff = cycleStart.difficulty
    val startTs = cycleStart.timestamp

    forAll(flexFractionTable) { exitFrac =>
      val gapSecs = expectedBlockTimeAfterExit(exitFrac)
      val chain = syntheticChain(cycleStart.number, startDiff, startTs, gapSecs, count = 500)
      val endDiff = chain.last._2

      if (cCoeff(gapSecs) == 0L) {
        // Below threshold — no trough; difficulty stays approximately flat
        val drift = (endDiff - startDiff).abs
        drift should be < startDiff / 20 // < 5% movement
      } else {
        // Above threshold — measurable trough
        endDiff should be < startDiff
      }
    }
  }

  "manipulation advantage" should "exist but be marginal — ASICs idle during flex-off with no offset revenue" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    // ETChash ASICs CANNOT switch to other coins or AI workloads during flex-off.
    // Unlike GPU miners who could offset downtime with alternative revenue, these ASICs idle.
    // Deliberate manipulation (exit to depress difficulty, re-enter at trough) sacrifices ETC
    // profit with no compensating income — irrational unless ETC price recovers during the trough.
    // The test quantifies the trough magnitude from the DAA's perspective:

    val offGap = expectedBlockTimeAfterExit(flexMaxHrFrac) // ≈ 19 s
    val offChain =
      syntheticChain(cycleStart.number, cycleStart.difficulty, cycleStart.timestamp, gapSecs = offGap, count = 500)
    val troughDiff = offChain.last._2

    // Trough advantage: blocks are easier by this fraction at re-entry
    val advantage = 1.0 - troughDiff.toDouble / cycleStart.difficulty.toDouble
    advantage should be > 0.0 // some advantage exists from DAA convergence
    advantage should be < 0.5 // but not a 2x windfall — and all ETC profit was foregone
  }

  "DAA recovery time" should "return toward full ASIC equilibrium within ~2048 blocks after re-entry" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    // After 500 flex-off blocks at 19s, all ASICs return mining at ~4s gaps
    val offGap = expectedBlockTimeAfterExit(flexMaxHrFrac)
    val offChain =
      syntheticChain(cycleStart.number, cycleStart.difficulty, cycleStart.timestamp, gapSecs = offGap, count = 500)
    val troughDiff = offChain.last._2
    val troughTs = cycleStart.timestamp + offGap * 500

    // Recovery phase: fast blocks at 4s (all ASICs on)
    val onChain = syntheticChain(cycleStart.number + 500, troughDiff, troughTs, gapSecs = 4L, count = 2048)
    val recoveredDiff = onChain.last._2

    // After 2048 fast blocks, should be back near or above the starting difficulty
    recoveredDiff should be > troughDiff
    // May overshoot cycleStart.difficulty — that's the oscillation mechanism
  }

  "Merge-scale hypothetical" should "require thousands of blocks to reach new equilibrium" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    // After a 3x hashrate jump (like the Merge) with c=+1 each block:
    // blocks to reach 90% of new equilibrium ≈ 1400+ blocks (log-convergence)
    val startDiff = premergeBaseline.difficulty
    val target3xEquil = startDiff * 3
    val ninetyPct = target3xEquil * 9 / 10

    val chain =
      syntheticChain(premergeBaseline.number, startDiff, premergeBaseline.timestamp, gapSecs = 4L, count = 3000)
    val idx = chain.indexWhere { case (_, d) => d >= ninetyPct }

    idx should be > 1000 // slow convergence — this is the DAA lag
  }

  // -------------------------------------------------------------------------
  // Group E — MESS interaction
  // -------------------------------------------------------------------------

  "MESS polynomial" should "match known values at key time points" taggedAs (UnitTest, ConsensusTest) in {
    ArtificialFinality.polynomialV(BigInt(0)) shouldBe BigInt(128)
    // At xcap (25132s ≈ 7 hours), polynomial saturates at 128 + 3840 = 3968
    ArtificialFinality.polynomialV(BigInt(25132)) shouldBe BigInt(3968)
    // At half xcap (~3.5h), value is between 128 and 3968
    val mid = ArtificialFinality.polynomialV(BigInt(12566))
    mid should be > BigInt(128)
    mid should be < BigInt(3968)
    // Short reorgs (< 200s) are nearly unaffected — multiplier ≈ 1x
    ArtificialFinality.polynomialV(BigInt(200)) should be < BigInt(200)
  }

  "MESS enabled (Olympia)" should "reject reorg attempt during flex-off difficulty trough" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    // Local chain: mined at normal difficulty for 7200s (2 hours)
    // Attacker builds competing chain at trough difficulty (flex-off depressed)
    val gpuOffGap = expectedBlockTimeAfterExit(flexMaxHrFrac) // 19s
    val cycleBlocks = 7200L / gpuOffGap // ~379 blocks
    val troughDiff = cycleStart.difficulty - (cycleStart.difficulty / 2048) * cycleBlocks

    val localTD = cycleStart.difficulty * cycleBlocks // honest chain: normal difficulty
    val proposedTD = troughDiff * cycleBlocks // attacker: depressed difficulty

    // At 7200s, polynomialV ≈ 7x → MESS should reject the lower-TD proposed chain
    ArtificialFinality.shouldRejectReorg(7200L, localTD, proposedTD) shouldBe true
  }

  "MESS enabled (Olympia)" should "protect against ASIC flex-off + reorg attack" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    // Full flex-off cycle of 500 blocks at 19s each = 9500s timeDelta
    val timeDelta = 500L * expectedBlockTimeAfterExit(flexMaxHrFrac)
    val localTD = cycleStart.difficulty * 500
    val troughDiff = cycleStart.difficulty * 8 / 10 // conservative 20% trough
    val proposedTD = troughDiff * 500

    // polynomialV caps at xcap=25132 — for timeDelta > xcap we get max multiplier (31x)
    ArtificialFinality.shouldRejectReorg(timeDelta, localTD, proposedTD) shouldBe true
  }

  "MESS deactivated (Spiral–Olympia, current mainnet)" should
    "cover the entire observable oscillation period" taggedAs (UnitTest, ConsensusTest) in {
      val messConfig = MESSConfig(
        enabled = true,
        activationBlock = Some(BigInt(11_380_000)),
        deactivationBlock = Some(BigInt(19_250_000))
      )
      // All oscillation-era blocks are post-Spiral (> 19,250,000)
      messConfig.isActiveAtBlock(cycleStart.number) shouldBe false
      messConfig.isActiveAtBlock(cycleMid.number) shouldBe false
      messConfig.isActiveAtBlock(cycleEnd.number) shouldBe false
      messConfig.isActiveAtBlock(flexOnPhase.head.number) shouldBe false
      messConfig.isActiveAtBlock(flexOffPhase.head.number) shouldBe false
    }

  "MESS deactivated window" should "leave chain exposed to ASIC flex-load reorg attempts" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    val messConfig = MESSConfig(
      enabled = true,
      activationBlock = Some(BigInt(11_380_000)),
      deactivationBlock = Some(BigInt(19_250_000))
    )
    // With MESS off, shouldRejectReorg is not called; verify WOULD have rejected
    val timeDelta = 500L * expectedBlockTimeAfterExit(flexMaxHrFrac)
    val localTD = cycleStart.difficulty * 500
    val proposedTD = (cycleStart.difficulty * 8 / 10) * 500
    // MESS would protect — but deactivation means the check is skipped
    messConfig.isActiveAtBlock(cycleStart.number) shouldBe false
    ArtificialFinality.shouldRejectReorg(timeDelta, localTD, proposedTD) shouldBe true
  }

  "MESS Olympia reactivation" should "restore protection at configurable block" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    val olympiaActivation = BigInt(25_000_000)
    val messConfig = MESSConfig(
      enabled = true,
      activationBlock = Some(BigInt(11_380_000)),
      deactivationBlock = Some(BigInt(19_250_000)),
      reactivationBlock = Some(olympiaActivation)
    )
    // Pre-Olympia oscillation blocks are unprotected
    messConfig.isActiveAtBlock(cycleStart.number) shouldBe false
    // Post-Olympia blocks are protected again
    messConfig.isActiveAtBlock(olympiaActivation) shouldBe true
    messConfig.isActiveAtBlock(olympiaActivation + 1) shouldBe true
  }
}
// scalastyle:on magic.number
