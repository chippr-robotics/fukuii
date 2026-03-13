package com.chipprbots.ethereum.consensus.pow

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

import com.chipprbots.ethereum.testing.Tags._

class EthashEpochBoundarySpec extends AnyFlatSpec with Matchers with ScalaCheckPropertyChecks {

  import com.chipprbots.ethereum.consensus.pow.EthashUtils._

  // ECIP-1099 doubles epoch length from 30K to 60K blocks at this block number
  val ecip1099ForkBlock: Long = 11460000

  // ===== Epoch Length Transition at ECIP-1099 Fork =====

  "EthashUtils epoch" should "use 30K epoch length before ECIP-1099" taggedAs (UnitTest, ConsensusTest) in {
    // Before fork: epoch = blockNumber / 30,000
    epoch(0, ecip1099ForkBlock) shouldBe 0
    epoch(29999, ecip1099ForkBlock) shouldBe 0
    epoch(30000, ecip1099ForkBlock) shouldBe 1
    epoch(59999, ecip1099ForkBlock) shouldBe 1
    epoch(60000, ecip1099ForkBlock) shouldBe 2
  }

  it should "use 60K epoch length after ECIP-1099" taggedAs (UnitTest, ConsensusTest) in {
    // After fork: epoch = blockNumber / 60,000
    epoch(ecip1099ForkBlock, ecip1099ForkBlock) shouldBe 191 // 11460000 / 60000 = 191
    epoch(ecip1099ForkBlock + 59999, ecip1099ForkBlock) shouldBe 191
    epoch(ecip1099ForkBlock + 60000, ecip1099ForkBlock) shouldBe 192
  }

  it should "halve epoch number at ECIP-1099 fork block" taggedAs (UnitTest, ConsensusTest) in {
    // The block just before the fork is in epoch 381 (11459999 / 30000 = 381)
    val preForkEpoch = epoch(ecip1099ForkBlock - 1, ecip1099ForkBlock)
    preForkEpoch shouldBe 381

    // The fork block itself is in epoch 191 (11460000 / 60000 = 191)
    val postForkEpoch = epoch(ecip1099ForkBlock, ecip1099ForkBlock)
    postForkEpoch shouldBe 191

    // The epoch number approximately halves. With 30K epochs:
    // pre-fork epoch 381 at block 11,459,999. With 60K epochs: post-fork epoch 191 at
    // block 11,460,000. Integer division: 381/2 = 190, but the actual post-fork epoch is
    // 191 because 11,460,000 / 60,000 = 191 exactly. The key property is that the DAG
    // doesn't grow unexpectedly — epoch 191 produces a much smaller DAG than epoch 382 would.
    postForkEpoch should be < preForkEpoch
  }

  it should "handle ECIP-1099 disabled (Long.MaxValue)" taggedAs (UnitTest, ConsensusTest) in {
    // When ECIP-1099 is not activated, always use 30K epoch length
    epoch(11460000, Long.MaxValue) shouldBe 382 // 11460000 / 30000
    epoch(11520000, Long.MaxValue) shouldBe 384 // 11520000 / 30000
  }

  // ===== Seed Consistency Across Fork =====

  "EthashUtils seed" should "produce consistent seeds within a post-ECIP-1099 epoch" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    // All blocks in the same post-fork epoch should share the same seed
    val seedAtFork = seed(ecip1099ForkBlock, ecip1099ForkBlock)
    val seedMidEpoch = seed(ecip1099ForkBlock + 30000, ecip1099ForkBlock)
    val seedEndEpoch = seed(ecip1099ForkBlock + 59999, ecip1099ForkBlock)

    seedAtFork shouldBe seedMidEpoch
    seedMidEpoch shouldBe seedEndEpoch
  }

  // ===== Cache and Dataset Size at Boundaries =====

  "EthashUtils cacheSize" should "increase with epoch number" taggedAs (UnitTest, ConsensusTest) in {
    val sizes = (0 to 6).map(e => cacheSize(e.toLong))

    // Cache sizes should be strictly increasing
    sizes.sliding(2).foreach { case Seq(a, b) =>
      b should be > a
    }

    // Known reference values from ethash spec
    sizes(0) shouldBe 16776896
    sizes(1) shouldBe 16907456
  }

  "EthashUtils dagSize" should "increase with epoch number" taggedAs (UnitTest, ConsensusTest) in {
    val size0 = dagSize(0)
    val size1 = dagSize(1)
    val size2 = dagSize(2)

    size1 should be > size0
    size2 should be > size1

    // Epoch 0 DAG is ~1GB
    size0 shouldBe 1073739904L
  }

  it should "produce same DAG size for pre/post fork blocks in equivalent epochs" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    // Pre-fork epoch 191 and post-fork epoch 191 should use the same DAG
    val preForkEpoch191Block = 191L * 30000 // Block 5,730,000 (pre-fork)
    val postForkEpoch191Block = ecip1099ForkBlock // Block 11,460,000 (post-fork)

    val preForkEpoch = epoch(preForkEpoch191Block, ecip1099ForkBlock)
    val postForkEpoch = epoch(postForkEpoch191Block, ecip1099ForkBlock)

    preForkEpoch shouldBe postForkEpoch
    dagSize(preForkEpoch) shouldBe dagSize(postForkEpoch)
  }

  // ===== Mordor-Specific Epoch Values =====

  "EthashUtils" should "calculate correct epochs for Mordor ECIP-1099 boundary" taggedAs (UnitTest, ConsensusTest) in {
    // Mordor activates ECIP-1099 at block 2,520,000
    val mordorEcip1099Block = 2520000L

    // Just before fork: epoch = 2519999 / 30000 = 83
    epoch(mordorEcip1099Block - 1, mordorEcip1099Block) shouldBe 83

    // At fork: epoch = 2520000 / 60000 = 42
    epoch(mordorEcip1099Block, mordorEcip1099Block) shouldBe 42

    // Epoch approximately halves (83 → 42), keeping DAG manageable
    epoch(mordorEcip1099Block, mordorEcip1099Block) should be < epoch(mordorEcip1099Block - 1, mordorEcip1099Block)
  }
}
