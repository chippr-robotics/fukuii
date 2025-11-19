package com.chipprbots.ethereum.consensus.mess

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable

import com.chipprbots.ethereum.db.storage.BlockFirstSeenStorage
import com.chipprbots.ethereum.domain.{BlockHeader, ChainWeight}

/** Integration test for MESS (Modified Exponential Subjective Scoring).
  *
  * Tests the complete MESS workflow including:
  *   - Recording block first-seen times
  *   - Calculating MESS-adjusted difficulties
  *   - Using MESS scores in chain weight comparisons
  *   - Protection against late-arriving chains
  */
class MESSIntegrationSpec extends AnyFlatSpec with Matchers {

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

  def createHeader(
      number: BigInt,
      difficulty: BigInt,
      timestamp: Long,
      hash: ByteString = ByteString.empty
  ): BlockHeader =
    BlockHeader(
      parentHash = ByteString.empty,
      ommersHash = ByteString.empty,
      beneficiary = ByteString.empty,
      stateRoot = ByteString.empty,
      transactionsRoot = ByteString.empty,
      receiptsRoot = ByteString.empty,
      logsBloom = ByteString.empty,
      difficulty = difficulty,
      number = number,
      gasLimit = 0,
      gasUsed = 0,
      unixTimestamp = timestamp,
      extraData = ByteString.empty,
      mixHash = ByteString.empty,
      nonce = ByteString.empty
    )

  "MESS Integration" should "prefer recently seen chain over old chain with same difficulty" in {
    val config = MESSConfig(enabled = true, decayConstant = 0.0001)
    val storage = new InMemoryBlockFirstSeenStorage()
    val currentTime = 1000000L
    val scorer = new MESSScorer(config, storage, () => currentTime)

    // Chain A: Seen immediately
    val chainA = List(
      createHeader(1, 1000, currentTime - 1000, ByteString("a1")),
      createHeader(2, 1000, currentTime - 500, ByteString("a2"))
    )

    // Chain B: Same difficulty but seen 6 hours late
    val chainB = List(
      createHeader(1, 1000, currentTime - 1000, ByteString("b1")),
      createHeader(2, 1000, currentTime - 500, ByteString("b2"))
    )

    // Record Chain A as seen immediately
    chainA.foreach { header =>
      storage.put(header.hash, currentTime)
    }

    // Record Chain B as seen 6 hours ago (old chain revealed late)
    val sixHoursAgo = currentTime - (6 * 3600 * 1000)
    chainB.foreach { header =>
      storage.put(header.hash, sixHoursAgo)
    }

    // Calculate chain weights
    val weightA = chainA.foldLeft(ChainWeight.zero) { (weight, header) =>
      val messAdjusted = scorer.calculateMessDifficulty(header)
      weight.increase(header, Some(messAdjusted))
    }

    val weightB = chainB.foldLeft(ChainWeight.zero) { (weight, header) =>
      val messAdjusted = scorer.calculateMessDifficulty(header)
      weight.increase(header, Some(messAdjusted))
    }

    // Chain A should be heavier despite same total difficulty
    weightA should be > weightB
    weightA.totalDifficulty shouldBe weightB.totalDifficulty
    weightA.messScore.get should be > weightB.messScore.get
  }

  it should "allow higher difficulty to overcome time penalty" in {
    val config = MESSConfig(enabled = true, decayConstant = 0.0001)
    val storage = new InMemoryBlockFirstSeenStorage()
    val currentTime = 1000000L
    val scorer = new MESSScorer(config, storage, () => currentTime)

    // Chain A: Lower difficulty, seen immediately
    val chainA = createHeader(1, 1000, currentTime, ByteString("a1"))
    storage.put(chainA.hash, currentTime)

    // Chain B: Much higher difficulty, seen 1 hour late
    val chainB = createHeader(1, 2000, currentTime, ByteString("b1"))
    val oneHourAgo = currentTime - (3600 * 1000)
    storage.put(chainB.hash, oneHourAgo)

    val messA = scorer.calculateMessDifficulty(chainA)
    val messB = scorer.calculateMessDifficulty(chainB)

    val weightA = ChainWeight.zero.increase(chainA, Some(messA))
    val weightB = ChainWeight.zero.increase(chainB, Some(messB))

    // Chain B should still be heavier if difficulty advantage overcomes time penalty
    // With 1 hour delay: exp(-0.0001 * 3600) ≈ 0.70, so 2000 * 0.70 = 1400 > 1000
    weightB should be > weightA
  }

  it should "apply minimum weight multiplier for very old blocks" in {
    val config = MESSConfig(
      enabled = true,
      decayConstant = 0.0001,
      minWeightMultiplier = 0.01 // 1% minimum
    )
    val storage = new InMemoryBlockFirstSeenStorage()
    val currentTime = 10000000L
    val scorer = new MESSScorer(config, storage, () => currentTime)

    val veryOldBlock = createHeader(1, 1000000, 0, ByteString("old"))
    storage.put(veryOldBlock.hash, 0L) // Extremely old

    val messAdjusted = scorer.calculateMessDifficulty(veryOldBlock)

    // Should be at least 1% of original difficulty
    messAdjusted should be >= BigInt(10000) // 1% of 1,000,000
    messAdjusted should be < BigInt(1000000) // But less than full difficulty
  }

  it should "handle blocks without first-seen time using block timestamp" in {
    val config = MESSConfig(enabled = true, decayConstant = 0.0001)
    val storage = new InMemoryBlockFirstSeenStorage()
    val currentTime = 1000000L
    val scorer = new MESSScorer(config, storage, () => currentTime)

    // Block without recorded first-seen time
    val block = createHeader(1, 1000, 900000L, ByteString("unknown"))
    // Don't record first-seen time

    val messAdjusted = scorer.calculateMessDifficulty(block)

    // Should use block timestamp (900000), resulting in 100 second penalty
    // exp(-0.0001 * 100) ≈ 0.99
    messAdjusted should be > BigInt(980)
    messAdjusted should be < BigInt(1000)
  }

  it should "work correctly when MESS is disabled" in {
    val config = MESSConfig(enabled = false)
    val storage = new InMemoryBlockFirstSeenStorage()
    val scorer = new MESSScorer(config, storage)

    val header = createHeader(1, 1000, 0, ByteString("test"))
    storage.put(header.hash, 0L) // Very old

    val messAdjusted = scorer.calculateMessDifficulty(header)

    // When MESS is disabled, should return original difficulty
    messAdjusted shouldBe BigInt(1000)
  }

  it should "correctly handle chain reorganization scenario" in {
    val config = MESSConfig(enabled = true, decayConstant = 0.0001)
    val storage = new InMemoryBlockFirstSeenStorage()
    val currentTime = 1000000L
    val scorer = new MESSScorer(config, storage, () => currentTime)

    // Canonical chain: blocks 1-3 seen over time
    val canonical1 = createHeader(1, 1000, currentTime - 3000, ByteString("c1"))
    val canonical2 = createHeader(2, 1000, currentTime - 2000, ByteString("c2"))
    val canonical3 = createHeader(3, 1000, currentTime - 1000, ByteString("c3"))

    storage.put(canonical1.hash, currentTime - 3000)
    storage.put(canonical2.hash, currentTime - 2000)
    storage.put(canonical3.hash, currentTime - 1000)

    // Attack chain: slightly higher difficulty but revealed all at once (now)
    val attack1 = createHeader(1, 1050, currentTime - 3000, ByteString("a1"))
    val attack2 = createHeader(2, 1050, currentTime - 2000, ByteString("a2"))
    val attack3 = createHeader(3, 1050, currentTime - 1000, ByteString("a3"))

    // Attack blocks all seen now (late)
    storage.put(attack1.hash, currentTime)
    storage.put(attack2.hash, currentTime)
    storage.put(attack3.hash, currentTime)

    // Calculate weights
    val canonicalWeight = List(canonical1, canonical2, canonical3).foldLeft(ChainWeight.zero) { (weight, header) =>
      val messAdjusted = scorer.calculateMessDifficulty(header)
      weight.increase(header, Some(messAdjusted))
    }

    val attackWeight = List(attack1, attack2, attack3).foldLeft(ChainWeight.zero) { (weight, header) =>
      val messAdjusted = scorer.calculateMessDifficulty(header)
      weight.increase(header, Some(messAdjusted))
    }

    // Canonical chain should win despite lower total difficulty
    // because its blocks were seen progressively over time
    canonicalWeight should be > attackWeight
    canonicalWeight.totalDifficulty should be < attackWeight.totalDifficulty
  }

  it should "record first-seen time only once" in {
    val config = MESSConfig(enabled = true)
    val storage = new InMemoryBlockFirstSeenStorage()
    val currentTime = 1000000L
    val scorer = new MESSScorer(config, storage, () => currentTime)

    val blockHash = ByteString("test")

    val isFirst1 = scorer.recordFirstSeen(blockHash)
    val firstSeenTime = storage.get(blockHash)

    isFirst1 shouldBe true
    firstSeenTime shouldBe Some(currentTime)

    // Try to record again
    val isFirst2 = scorer.recordFirstSeen(blockHash)
    val secondSeenTime = storage.get(blockHash)

    isFirst2 shouldBe false
    secondSeenTime shouldBe firstSeenTime // Should not change
  }
}
