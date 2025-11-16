package com.chipprbots.ethereum.consensus.mess

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable

import com.chipprbots.ethereum.db.storage.BlockFirstSeenStorage
import com.chipprbots.ethereum.domain.BlockHeader

class MESSConfigSpec extends AnyFlatSpec with Matchers {

  "MESSConfig" should "have valid default values" in {
    val config = MESSConfig()
    
    config.enabled shouldBe false
    config.decayConstant shouldBe 0.0001
    config.maxTimeDelta shouldBe 2592000L
    config.minWeightMultiplier shouldBe 0.0001
  }

  it should "validate decayConstant is non-negative" in {
    assertThrows[IllegalArgumentException] {
      MESSConfig(decayConstant = -0.1)
    }
  }

  it should "validate maxTimeDelta is positive" in {
    assertThrows[IllegalArgumentException] {
      MESSConfig(maxTimeDelta = 0)
    }
    
    assertThrows[IllegalArgumentException] {
      MESSConfig(maxTimeDelta = -100)
    }
  }

  it should "validate minWeightMultiplier is in range (0, 1]" in {
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
  ): MESSScorer = {
    new MESSScorer(config, storage, () => currentTime)
  }

  "MESSScorer" should "return original difficulty when MESS is disabled" in {
    val config = MESSConfig(enabled = false)
    val scorer = createScorer(config = config)
    
    val blockHash = ByteString("test")
    val difficulty = BigInt(1000000)
    val blockTimestamp = 900000L
    
    val result = scorer.calculateMessDifficulty(blockHash, difficulty, blockTimestamp)
    result shouldBe difficulty
  }

  it should "return original difficulty for blocks seen immediately" in {
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

  it should "apply penalty for blocks seen 1 hour late" in {
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

  it should "apply strong penalty for blocks seen 24 hours late" in {
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

  it should "enforce minimum weight multiplier" in {
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

  it should "cap time delta at maxTimeDelta" in {
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

  it should "use block timestamp when no first-seen time exists" in {
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

  it should "record first-seen time correctly" in {
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

  it should "calculate multiplier correctly" in {
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

  it should "return multiplier of 1.0 when MESS is disabled" in {
    val config = MESSConfig(enabled = false)
    val scorer = createScorer(config = config)
    
    val blockHash = ByteString("test")
    val blockTimestamp = 0L
    
    val multiplier = scorer.calculateMultiplier(blockHash, blockTimestamp)
    multiplier shouldBe 1.0
  }
}
