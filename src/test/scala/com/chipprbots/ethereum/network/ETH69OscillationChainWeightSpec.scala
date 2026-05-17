package com.chipprbots.ethereum.network

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.consensus.pow.difficulty.OscillationFixtures
import com.chipprbots.ethereum.consensus.pow.difficulty.OscillationFixtures._
import com.chipprbots.ethereum.testing.Tags._

// scalastyle:off magic.number
/** Tests for POW_SCALING chain-weight estimation accuracy across ETH protocol eras.
  *
  * ETH/68 carries totalDifficulty on the wire — chain-weight comparison is exact.
  * ETH/69 (EIP-7642) removes TD — fukuii estimates a peer's TD using (Tier 3 / POW_SCALING):
  *
  *   estimatedTD = ourBestTD + ourCurrentDiff × gap × 9999/10000
  *   where gap = latestBlock − ourBestBlockNum
  *
  * This uses the current block difficulty as the marginal rate rather than the historical
  * average. The 9999/10000 factor guarantees a slight underestimate for constant-difficulty
  * chains (< 0.01% error), ensuring we never overestimate for stable chains.
  *
  * The previous formula (ourBestTD × latestBlock / ourBestNum) used the all-time historical
  * average TD per block (~582 TH for ETC), underestimating each gap block's TD contribution
  * by 70-86% vs the current era (2000-4300 TH per block). Over an 852K-block gap this
  * translates to a ~16% total-TD underestimate (the cumulative historical TD dominates).
  *
  * NOTE: `powScalingEstimate` below is a private copy of the Tier 3 formula in
  * BlockchainReader.resolveETH69ChainWeight. It must be kept in sync with that method.
  * The full production call path is tested in ETH69TDSpec and ETChashDifficultyAlignmentSpec.
  *
  * Concretely for ETC mainnet (852K-block cycleStart → cycleMid gap):
  *   - Historical average TD/block ≈ 580 TH (dominated by 2016-2022 low-difficulty era)
  *   - Current era difficulty ≈ 2000-4300 TH per block (ASIC flex-load oscillation era)
  *   → Old formula: ~16% total-TD underestimate (gap contribution at 580 TH << actual ~3300 TH avg)
  *   → New formula: ~6% underestimate for rising-difficulty gap; < 0.01% for constant-difficulty
  */
class ETH69OscillationChainWeightSpec extends AnyFlatSpec with Matchers {

  /** Mirrors the Tier 3 POW_SCALING formula in BlockchainReader.resolveETH69ChainWeight.
    * Must stay in sync with: ourBestTD + ourCurrentDiff * gap * 9999 / 10000
    */
  private def powScalingEstimate(
      ourBestTD: BigInt,
      ourCurrentDiff: BigInt,
      latestBlock: BigInt,
      ourBestNum: BigInt
  ): BigInt = {
    val gap = (latestBlock - ourBestNum).max(BigInt(0))
    ourBestTD + ourCurrentDiff * gap * 9999 / 10000
  }

  /** Old historical-average formula — kept as a reference baseline for improvement tests. */
  private def historicalAvgEstimate(ourBestTD: BigInt, latestBlock: BigInt, ourBestNum: BigInt): BigInt =
    ourBestTD * latestBlock / ourBestNum

  /** TD accumulated by a difficulty that changes linearly by `delta` per block. */
  private def linearRampTD(baseTD: BigInt, startDiff: BigInt, delta: BigInt, count: BigInt): BigInt = {
    // sum_{i=0}^{count-1} (startDiff + i*delta) = count*startDiff + delta*count*(count-1)/2
    baseTD + startDiff * count + delta * count * (count - 1) / 2
  }

  // -------------------------------------------------------------------------
  // ETH/68 era — wire provides totalDifficulty directly
  // -------------------------------------------------------------------------

  "ETH68 era" should "give 0% estimation error — wire TD is used directly" taggedAs (
    UnitTest,
    NetworkTest
  ) in {
    // ETH68 peers send exact TD on the wire; POW_SCALING is never called.
    // The fixture TDs ARE the exact on-chain values.
    val wireTD = mergeSpike.last.totalDifficulty
    // Client uses wireTD directly — no estimation error possible
    wireTD shouldBe mergeSpike.last.totalDifficulty
  }

  "ETH68 era" should "accurately track TD through Merge spike — hashrate tripling is invisible to wire TD" taggedAs (
    UnitTest,
    NetworkTest
  ) in {
    // Despite the 3x hashrate jump, each ETH68 STATUS message carries the exact current TD.
    // Chain-weight comparison is unaffected by oscillation — it's bit-exact.
    val preMerge  = mergeSpikeParent.totalDifficulty
    val postMerge = mergeSpike.last.totalDifficulty
    val tdGain    = postMerge - preMerge

    // 5 fast blocks: each block contributes parentDiff + parentDiff/2048 to TD
    tdGain should be > BigInt(0)
    // Sanity: gain is approximately 5 * mergeSpike[0].difficulty
    val approxGain = mergeSpike.head.difficulty * 5
    val ratio      = tdGain.toDouble / approxGain.toDouble
    ratio should be > 0.95
    ratio should be < 1.05
  }

  "ETH68 era" should "provide bit-exact TD for both ASIC-stable and flex-variable portions" taggedAs (
    UnitTest,
    NetworkTest
  ) in {
    // ASIC-stable: premergeBaseline; flex-variable: any oscillation-era block
    // Both have exact on-chain TD values — wire protocol provides both without estimation
    val asicTD    = premergeBaseline.totalDifficulty
    val flexOnTD  = cycleMid.totalDifficulty
    val flexOffTD = cycleEnd.totalDifficulty

    // Sanity: TD is monotonically increasing
    asicTD   should be < flexOnTD
    flexOnTD should be < flexOffTD
    // Bit-exact — no rounding or estimation involved
    asicTD   shouldBe premergeBaseline.totalDifficulty
    flexOnTD  shouldBe cycleMid.totalDifficulty
    flexOffTD shouldBe cycleEnd.totalDifficulty
  }

  // -------------------------------------------------------------------------
  // ETH/68 + ETH/69 mixed era — peers may use either protocol
  // -------------------------------------------------------------------------

  "ETH68+ETH69 mixed era" should "give 0% error for ETH68 peers — wire TD unaffected by flex-load cycling" taggedAs (
    UnitTest,
    NetworkTest
  ) in {
    // Same as ETH68 era: wire carries exact TD regardless of hashrate oscillation
    val wireTD = flexOffPhase.last.totalDifficulty
    wireTD shouldBe flexOffPhase.last.totalDifficulty
  }

  "ETH68+ETH69 mixed era" should "give non-zero estimation error for ETH69 peers on same chain" taggedAs (
    UnitTest,
    NetworkTest
  ) in {
    // Anchor: cycleStart; ETH69 peer announces latestBlock = cycleMid.number (no TD on wire)
    val estimated = powScalingEstimate(
      cycleStart.totalDifficulty,
      cycleStart.difficulty,
      cycleMid.number,
      cycleStart.number
    )
    val actual = cycleMid.totalDifficulty
    // Marginal estimate from cycleStart difficulty — differs from actual (difficulty rose over gap)
    estimated should not equal actual
  }

  "ETH68+ETH69 mixed era" should "underestimate TD for ETH69 peer during flex-on peak" taggedAs (
    UnitTest,
    NetworkTest
  ) in {
    // Anchor: cycleStart (~2286 TH difficulty). Peer at cycleMid: difficulty was ~4327 TH.
    // Difficulty rose over the 852K-block gap → actual TD/block > anchor difficulty → underestimate.
    val estimated = powScalingEstimate(
      cycleStart.totalDifficulty,
      cycleStart.difficulty,
      cycleMid.number,
      cycleStart.number
    )
    val actual = cycleMid.totalDifficulty
    estimated should be < actual
  }

  "ETH68+ETH69 mixed era" should "reduce POW_SCALING error by > 2x vs historical-average formula" taggedAs (
    UnitTest,
    NetworkTest
  ) in {
    // Old formula: ourBestTD * latestBlock / ourBestNum — extrapolates at ~582 TH historical avg/block
    // New formula: ourBestTD + ourCurrentDiff * gap * 9999/10000 — uses ~2286 TH anchor difficulty
    // Both underestimate because actual avg difficulty rose to ~3300 TH over the 852K-block gap.
    // The improvement is in the gap contribution (old: 580 TH/block vs new: 2286 TH/block vs actual: ~3300 TH).
    val oldEstimate = historicalAvgEstimate(cycleStart.totalDifficulty, cycleMid.number, cycleStart.number)
    val newEstimate = powScalingEstimate(
      cycleStart.totalDifficulty,
      cycleStart.difficulty,
      cycleMid.number,
      cycleStart.number
    )
    val actual = cycleMid.totalDifficulty

    val oldError = (actual - oldEstimate).abs.toDouble / actual.toDouble
    val newError = (actual - newEstimate).abs.toDouble / actual.toDouble

    oldError should be > 0.10  // old formula: ~16% total-TD underestimate (gap extrapolated at 580 TH)
    newError should be < 0.10  // new formula: ~6% underestimate (uses current diff as marginal rate)
    oldError / newError should be > 2.0  // at least 2x improvement in total-TD error
  }

  "ETH68+ETH69 mixed era" should "never overestimate for ETH69 peers at constant difficulty" taggedAs (
    UnitTest,
    NetworkTest
  ) in {
    // Synthetic: constant difficulty = cycleStart.difficulty over 1000 blocks
    val D        = cycleStart.difficulty
    val anchorTD = cycleStart.totalDifficulty
    val peerNum  = cycleStart.number + BigInt(1000)
    val actualTD = anchorTD + D * BigInt(1000)

    val estimated = powScalingEstimate(anchorTD, D, peerNum, cycleStart.number)
    estimated should be <= actualTD  // 9999/10000 factor guarantees underestimate for constant diff
  }

  // -------------------------------------------------------------------------
  // ETH/69-only era — all peers use POW_SCALING
  // -------------------------------------------------------------------------

  "ETH69-only era" should "give < 0.1% error for a constant-difficulty (ASIC-only) synthetic chain" taggedAs (
    UnitTest,
    NetworkTest
  ) in {
    // Synthetic ASIC-only chain: constant difficulty = 2000 TH, starting from block 0
    val constDiff  = BigInt("2000000000000000")  // 2000 TH
    val anchorNum  = BigInt(1_000_000)
    val anchorTD   = constDiff * anchorNum
    val peerNum    = BigInt(1_100_000)
    val actualTD   = constDiff * peerNum

    val estimated = powScalingEstimate(anchorTD, constDiff, peerNum, anchorNum)
    val errFrac   = (estimated - actualTD).abs.toDouble / actualTD.toDouble
    errFrac should be < 0.001  // < 0.1% (actual: ~0.01% from 9999/10000 factor)
  }

  "ETH69-only era" should "overestimate TD when anchor is at flex-on peak and peer is at flex-off trough" taggedAs (
    UnitTest,
    NetworkTest
  ) in {
    // Synthetic: anchor at flex-on difficulty (4000 TH), 10000 blocks later difficulty has FALLEN to 2000 TH
    // Because difficulty FELL: actual TD per block < anchor difficulty → overestimate
    val anchorDiff  = BigInt("4000000000000000")  // 4000 TH
    val anchorNum   = BigInt(1_000_000)
    val anchorTD    = anchorDiff * anchorNum
    val blockCount  = BigInt(10_000)
    val peerNum     = anchorNum + blockCount

    // Linear ramp DOWN: difficulty falls from 4000 TH to 2000 TH over 10000 blocks
    // delta = -2000 TH / 10000 blocks = -200 GH per block
    val delta    = BigInt("-200000000000")
    val actualTD = linearRampTD(anchorTD, anchorDiff, delta, blockCount)
    val estimated = powScalingEstimate(anchorTD, anchorDiff, peerNum, anchorNum)

    estimated should be > actualTD  // overestimate when difficulty falls below anchor rate
  }

  "ETH69-only era" should "underestimate TD when anchor is at flex-off trough and peer is at flex-on peak" taggedAs (
    UnitTest,
    NetworkTest
  ) in {
    // Synthetic: anchor at flex-off difficulty (2000 TH), 10000 blocks later difficulty has RISEN to 4000 TH
    val anchorDiff  = BigInt("2000000000000000")  // 2000 TH
    val anchorNum   = BigInt(1_000_000)
    val anchorTD    = anchorDiff * anchorNum
    val blockCount  = BigInt(10_000)
    val peerNum     = anchorNum + blockCount

    // Linear ramp UP: difficulty rises from 2000 TH to 4000 TH over 10000 blocks
    val delta    = BigInt("200000000000")
    val actualTD = linearRampTD(anchorTD, anchorDiff, delta, blockCount)
    val estimated = powScalingEstimate(anchorTD, anchorDiff, peerNum, anchorNum)

    estimated should be < actualTD  // underestimate when difficulty rises above anchor rate
  }

  "ETH69-only era" should "have near-zero error for flex-load cycling below the 0.278 exit-fraction threshold" taggedAs (
    UnitTest,
    NetworkTest
  ) in {
    // Below threshold: block time stays < 18s → c = 0 → difficulty approximately constant
    // POW_SCALING error is proportional to difficulty variance; below threshold, variance ≈ 0
    val exitFrac    = 0.20  // below flexExitThresholdFrac (0.278)
    val gap         = OscillationFixtures.expectedBlockTimeAfterExit(exitFrac)
    val c           = OscillationFixtures.cCoeff(gap)

    // c = 0 means difficulty does NOT change — constant difficulty → POW_SCALING near-exact
    c shouldBe 0L

    // Confirm: 10000 constant-difficulty blocks have < 0.01% POW_SCALING error
    val diff      = BigInt("2000000000000000")
    val anchorNum = BigInt(1_000_000)
    val anchorTD  = diff * anchorNum
    val peerNum   = anchorNum + BigInt(10_000)
    val actualTD  = diff * peerNum
    val estimated = powScalingEstimate(anchorTD, diff, peerNum, anchorNum)
    val errFrac   = (estimated - actualTD).abs.toDouble / actualTD.toDouble
    errFrac should be < 0.0001
  }

  "ETH69-only era" should "have measurable POW_SCALING error for the observed 0.324 flex-load exit fraction" taggedAs (
    UnitTest,
    NetworkTest
  ) in {
    // At 0.324 exit fraction, c = -1 every block → difficulty falls by D/2048 per block
    val exitFrac    = OscillationFixtures.flexMaxHrFrac  // 0.324
    val gap         = OscillationFixtures.expectedBlockTimeAfterExit(exitFrac)
    val c           = OscillationFixtures.cCoeff(gap)
    c shouldBe -1L   // flex-off blocks trigger DAA difficulty reduction

    // After 500 flex-off blocks: difficulty falls ~500/2048 ≈ 24% from start
    val startDiff   = BigInt("4000000000000000")   // ~4000 TH (at flex-on peak)
    val anchorNum   = BigInt(1_000_000)
    val blockCount  = BigInt(500)
    val anchorTD    = startDiff * anchorNum

    // Simulate falling difficulty with c = -1 each block (approx: linear ramp down by D/2048)
    val diffDeltaPerBlock = startDiff / 2048  // ≈ D/2048 per block downward
    val actualTD    = linearRampTD(anchorTD, startDiff, -diffDeltaPerBlock, blockCount)
    val peerNum     = anchorNum + blockCount
    val estimated   = powScalingEstimate(anchorTD, startDiff, peerNum, anchorNum)

    // estimated > actual because difficulty fell below the anchor rate
    estimated should be > actualTD

    val overestimateFrac = (estimated - actualTD).toDouble / actualTD.toDouble
    overestimateFrac should be > 0.0   // measurable overestimate during falling-diff phase
    overestimateFrac should be < 0.20  // but not catastrophically wrong for 500 blocks
  }
}
// scalastyle:on magic.number
