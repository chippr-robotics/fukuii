package com.chipprbots.ethereum.consensus.mess

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable

import com.chipprbots.ethereum.db.storage.BlockFirstSeenStorage
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.testing.Tags._

class MESSConfigSpec extends AnyFlatSpec with Matchers {

  "MESSConfig" should "have valid default values" taggedAs (UnitTest, ConsensusTest) in {
    val config = MESSConfig()

    config.enabled shouldBe false
    config.decayConstant shouldBe 0.0001
    config.maxTimeDelta shouldBe 2592000L
    config.minWeightMultiplier shouldBe 0.0001
  }

  it should "validate decayConstant is non-negative" taggedAs (UnitTest, ConsensusTest) in {
    assertThrows[IllegalArgumentException] {
      MESSConfig(decayConstant = -0.1)
    }
  }

  it should "validate maxTimeDelta is positive" taggedAs (UnitTest, ConsensusTest) in {
    assertThrows[IllegalArgumentException] {
      MESSConfig(maxTimeDelta = 0)
    }

    assertThrows[IllegalArgumentException] {
      MESSConfig(maxTimeDelta = -100)
    }
  }

  it should "validate minWeightMultiplier is taggedAs (UnitTest, ConsensusTest) in range (0, 1]" in {
    assertThrows[IllegalArgumentException] {
      MESSConfig(minWeightMultiplier = 0.0)
    }

    assertThrows[IllegalArgumentException] {
      MESSConfig(minWeightMultiplier = -0.1)
    }

    assertThrows[IllegalArgumentException] {
      MESSConfig(minWeightMultiplier = 1.1)
    }

    // These should be valid
    MESSConfig(minWeightMultiplier = 0.0001)
    MESSConfig(minWeightMultiplier = 1.0)
    MESSConfig(minWeightMultiplier = 0.5)
  }

  it should "have None activation/deactivation blocks by default" taggedAs (UnitTest, ConsensusTest) in {
    val config = MESSConfig()
    config.activationBlock shouldBe None
    config.deactivationBlock shouldBe None
  }

  // ECBP-1100 block-based activation window tests

  it should "report inactive when enabled=false regardless of block number" taggedAs (UnitTest, ConsensusTest) in {
    val config = MESSConfig(enabled = false, activationBlock = Some(100), deactivationBlock = Some(200))
    config.isActiveAtBlock(150) shouldBe false
  }

  it should "report active when enabled=true and within activation window" taggedAs (UnitTest, ConsensusTest) in {
    val config = MESSConfig(enabled = true, activationBlock = Some(100), deactivationBlock = Some(200))
    config.isActiveAtBlock(100) shouldBe true  // activation is inclusive
    config.isActiveAtBlock(150) shouldBe true
    config.isActiveAtBlock(199) shouldBe true
  }

  it should "report inactive before activation block" taggedAs (UnitTest, ConsensusTest) in {
    val config = MESSConfig(enabled = true, activationBlock = Some(100), deactivationBlock = Some(200))
    config.isActiveAtBlock(99) shouldBe false
    config.isActiveAtBlock(0) shouldBe false
  }

  it should "report inactive at and after deactivation block" taggedAs (UnitTest, ConsensusTest) in {
    val config = MESSConfig(enabled = true, activationBlock = Some(100), deactivationBlock = Some(200))
    config.isActiveAtBlock(200) shouldBe false  // deactivation is exclusive
    config.isActiveAtBlock(201) shouldBe false
  }

  it should "report active with no deactivation block (never deactivates)" taggedAs (UnitTest, ConsensusTest) in {
    val config = MESSConfig(enabled = true, activationBlock = Some(100))
    config.isActiveAtBlock(100) shouldBe true
    config.isActiveAtBlock(BigInt("99999999999")) shouldBe true
  }

  it should "report active with no activation block (always active)" taggedAs (UnitTest, ConsensusTest) in {
    val config = MESSConfig(enabled = true, deactivationBlock = Some(200))
    config.isActiveAtBlock(0) shouldBe true
    config.isActiveAtBlock(199) shouldBe true
    config.isActiveAtBlock(200) shouldBe false
  }

  it should "match Mordor ECBP-1100 activation window" taggedAs (UnitTest, ConsensusTest) in {
    val config = MESSConfig(enabled = true, activationBlock = Some(2380000), deactivationBlock = Some(10400000))
    config.isActiveAtBlock(2379999) shouldBe false
    config.isActiveAtBlock(2380000) shouldBe true
    config.isActiveAtBlock(5000000) shouldBe true
    config.isActiveAtBlock(10399999) shouldBe true
    config.isActiveAtBlock(10400000) shouldBe false
  }

  it should "match ETC mainnet ECBP-1100 activation window" taggedAs (UnitTest, ConsensusTest) in {
    val config = MESSConfig(enabled = true, activationBlock = Some(11380000), deactivationBlock = Some(19250000))
    config.isActiveAtBlock(11379999) shouldBe false
    config.isActiveAtBlock(11380000) shouldBe true
    config.isActiveAtBlock(15000000) shouldBe true
    config.isActiveAtBlock(19249999) shouldBe true
    config.isActiveAtBlock(19250000) shouldBe false
  }
}

class MESScorerSpec extends AnyFlatSpec with Matchers {

  // Time conversion constants for readability
  private val OneHourMillis = 3600 * 1000L
  private val OneDayMillis = 24 * 3600 * 1000L

  // In-memory storage for testing
  class InMemoryBlockFirstSeenStorage extends BlockFirstSeenStorage {
    private val storage = mutable.Map[ByteString, Long]()

    override def put(blockHash: ByteString, timestamp: Long): Unit =
      storage(blockHash) = timestamp

    override def get(blockHash: ByteString): Option[Long] =
      storage.get(blockHash)

    override def remove(blockHash: ByteString): Unit =
      storage.remove(blockHash)
  }

  def createScorer(
      config: MESSConfig = MESSConfig(enabled = true),
      storage: BlockFirstSeenStorage = new InMemoryBlockFirstSeenStorage(),
      currentTime: Long = 1000000L
  ): MESSScorer =
    new MESSScorer(config, storage, () => currentTime)

  "MESSScorer" should "return original difficulty when MESS is disabled" taggedAs (UnitTest, ConsensusTest) in {
    val config = MESSConfig(enabled = false)
    val scorer = createScorer(config = config)

    val blockHash = ByteString("test")
    val difficulty = BigInt(1000000)
    val blockTimestamp = 900000L

    val result = scorer.calculateMessDifficulty(blockHash, difficulty, blockTimestamp)
    result shouldBe difficulty
  }

  it should "return original difficulty for blocks seen immediately" taggedAs (UnitTest, ConsensusTest) in {
    val scorer = createScorer(currentTime = 1000000L)
    val storage = new InMemoryBlockFirstSeenStorage()
    val scorerWithStorage = createScorer(storage = storage, currentTime = 1000000L)

    val blockHash = ByteString("test")
    val difficulty = BigInt(1000000)
    val blockTimestamp = 1000000L // Same as current time

    // Record first seen at current time
    storage.put(blockHash, 1000000L)

    val result = scorerWithStorage.calculateMessDifficulty(blockHash, difficulty, blockTimestamp)

    // Should be very close to original (exp(0) = 1.0)
    result shouldBe >=(BigInt(999900))
    result shouldBe <=(BigInt(1000100))
  }

  it should "apply penalty for blocks seen 1 hour late" taggedAs (UnitTest, ConsensusTest) in {
    val config = MESSConfig(enabled = true, decayConstant = 0.0001)
    val storage = new InMemoryBlockFirstSeenStorage()
    val currentTime = 1000000L
    val scorer = createScorer(config = config, storage = storage, currentTime = currentTime)

    val blockHash = ByteString("test")
    val difficulty = BigInt(1000000)
    val firstSeenTime = currentTime - OneHourMillis // 1 hour ago

    storage.put(blockHash, firstSeenTime)

    val result = scorer.calculateMessDifficulty(blockHash, difficulty, 0L)

    // After 1 hour (3600 seconds) with lambda=0.0001:
    // exp(-0.0001 * 3600) = exp(-0.36) ≈ 0.70
    // Expected: ~700,000
    result should be > BigInt(650000)
    result should be < BigInt(750000)
  }

  it should "apply strong penalty for blocks seen 24 hours late" taggedAs (UnitTest, ConsensusTest) in {
    val config = MESSConfig(enabled = true, decayConstant = 0.0001)
    val storage = new InMemoryBlockFirstSeenStorage()
    val currentTime = 1000000L
    val scorer = createScorer(config = config, storage = storage, currentTime = currentTime)

    val blockHash = ByteString("test")
    val difficulty = BigInt(1000000)
    val firstSeenTime = currentTime - OneDayMillis // 24 hours ago

    storage.put(blockHash, firstSeenTime)

    val result = scorer.calculateMessDifficulty(blockHash, difficulty, 0L)

    // After 24 hours (86400 seconds) with lambda=0.0001:
    // exp(-0.0001 * 86400) = exp(-8.64) ≈ 0.0002
    // Expected: very small, close to minWeightMultiplier
    result should be < BigInt(1000) // Less than 0.1% of original
  }

  it should "enforce minimum weight multiplier" taggedAs (UnitTest, ConsensusTest) in {
    val config = MESSConfig(enabled = true, decayConstant = 0.0001, minWeightMultiplier = 0.01)
    val storage = new InMemoryBlockFirstSeenStorage()
    val currentTime = 10000000L
    val scorer = createScorer(config = config, storage = storage, currentTime = currentTime)

    val blockHash = ByteString("test")
    val difficulty = BigInt(1000000)
    val firstSeenTime = 0L // Very old block

    storage.put(blockHash, firstSeenTime)

    val result = scorer.calculateMessDifficulty(blockHash, difficulty, 0L)

    // Should be at least minWeightMultiplier (1%) of original
    result should be >= BigInt(10000) // At least 1%
  }

  it should "cap time delta at maxTimeDelta" taggedAs (UnitTest, ConsensusTest) in {
    val maxTimeDelta = 1000L // 1000 seconds
    val config = MESSConfig(
      enabled = true,
      decayConstant = 0.0001,
      maxTimeDelta = maxTimeDelta
    )
    val storage = new InMemoryBlockFirstSeenStorage()
    val currentTime = 10000000L
    val scorer = createScorer(config = config, storage = storage, currentTime = currentTime)

    val blockHash1 = ByteString("block1")
    val blockHash2 = ByteString("block2")
    val difficulty = BigInt(1000000)

    // Block1: exactly maxTimeDelta old
    storage.put(blockHash1, currentTime - (maxTimeDelta * 1000))

    // Block2: much older than maxTimeDelta
    storage.put(blockHash2, currentTime - (maxTimeDelta * 10000))

    val result1 = scorer.calculateMessDifficulty(blockHash1, difficulty, 0L)
    val result2 = scorer.calculateMessDifficulty(blockHash2, difficulty, 0L)

    // Both should get the same penalty (capped at maxTimeDelta)
    result1 shouldBe result2
  }

  it should "use block timestamp when no first-seen time exists" taggedAs (UnitTest, ConsensusTest) in {
    val storage = new InMemoryBlockFirstSeenStorage()
    val currentTime = 1000000L
    val scorer = createScorer(storage = storage, currentTime = currentTime)

    val blockHash = ByteString("unseen")
    val difficulty = BigInt(1000000)
    val blockTimestamp = 900000L

    // Don't record first-seen time

    val result = scorer.calculateMessDifficulty(blockHash, difficulty, blockTimestamp)

    // Should use blockTimestamp as first-seen, so 100 second penalty
    // exp(-0.0001 * 100) = exp(-0.01) ≈ 0.99
    result should be > BigInt(980000)
    result should be < BigInt(1000000)
  }

  it should "record first-seen time correctly" taggedAs (UnitTest, ConsensusTest) in {
    val storage = new InMemoryBlockFirstSeenStorage()
    val currentTime = 1000000L
    val scorer = createScorer(storage = storage, currentTime = currentTime)

    val blockHash = ByteString("new")

    storage.contains(blockHash) shouldBe false

    val isFirst = scorer.recordFirstSeen(blockHash)
    isFirst shouldBe true

    storage.contains(blockHash) shouldBe true
    storage.get(blockHash) shouldBe Some(currentTime)

    // Recording again should return false
    val isFirstAgain = scorer.recordFirstSeen(blockHash)
    isFirstAgain shouldBe false
  }

  it should "calculate multiplier correctly" taggedAs (UnitTest, ConsensusTest) in {
    val storage = new InMemoryBlockFirstSeenStorage()
    val currentTime = 1000000L
    val scorer = createScorer(storage = storage, currentTime = currentTime)

    val blockHash = ByteString("test")
    val blockTimestamp = 900000L // 100 seconds ago

    storage.put(blockHash, blockTimestamp)

    val multiplier = scorer.calculateMultiplier(blockHash, blockTimestamp)

    // exp(-0.0001 * 100) = exp(-0.01) ≈ 0.99
    multiplier should be > 0.98
    multiplier should be < 1.0
  }

  it should "return multiplier of 1.0 when MESS is disabled" taggedAs (UnitTest, ConsensusTest) in {
    val config = MESSConfig(enabled = false)
    val scorer = createScorer(config = config)

    val blockHash = ByteString("test")
    val blockTimestamp = 0L

    val multiplier = scorer.calculateMultiplier(blockHash, blockTimestamp)
    multiplier shouldBe 1.0
  }

  // Block-number-aware scoring tests (ECBP-1100)

  it should "return original difficulty for blocks outside activation window" taggedAs (UnitTest, ConsensusTest) in {
    val config = MESSConfig(enabled = true, activationBlock = Some(100), deactivationBlock = Some(200))
    val storage = new InMemoryBlockFirstSeenStorage()
    val scorer = new MESSScorer(config, storage, () => 1000000L)

    val blockHash = ByteString("test-outside")
    storage.put(blockHash, 0L) // seen 1000 seconds ago

    val difficulty = BigInt(1000000)
    // Block 50 is before activation — should return original difficulty
    scorer.calculateMessDifficulty(blockHash, difficulty, 0L, Some(BigInt(50))) shouldBe difficulty
    // Block 250 is after deactivation — should return original difficulty
    scorer.calculateMessDifficulty(blockHash, difficulty, 0L, Some(BigInt(250))) shouldBe difficulty
  }

  it should "apply MESS penalty for blocks within activation window" taggedAs (UnitTest, ConsensusTest) in {
    val config = MESSConfig(enabled = true, activationBlock = Some(100), deactivationBlock = Some(200))
    val storage = new InMemoryBlockFirstSeenStorage()
    val scorer = new MESSScorer(config, storage, () => 1000000L)

    val blockHash = ByteString("test-inside")
    storage.put(blockHash, 0L) // seen 1000 seconds ago

    val difficulty = BigInt(1000000)
    // Block 150 is within activation window — should apply decay
    val adjusted = scorer.calculateMessDifficulty(blockHash, difficulty, 0L, Some(BigInt(150)))
    adjusted should be < difficulty
  }
}
