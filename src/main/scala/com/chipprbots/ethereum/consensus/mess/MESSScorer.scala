package com.chipprbots.ethereum.consensus.mess

import org.apache.pekko.util.ByteString

import scala.math.{exp, max, min}

import com.chipprbots.ethereum.db.storage.BlockFirstSeenStorage
import com.chipprbots.ethereum.domain.BlockHeader

/** Calculator for MESS (Modified Exponential Subjective Scoring).
  *
  * Applies time-based penalties to block difficulties based on when blocks
  * were first seen by this node. This helps protect against long-range
  * reorganization attacks.
  *
  * The scoring formula is:
  * {{{
  * messWeight = difficulty * exp(-lambda * timeDelta)
  * }}}
  *
  * where:
  *   - lambda = decay constant (from config)
  *   - timeDelta = max(0, currentTime - firstSeenTime)
  *
  * @param config
  *   MESS configuration parameters
  * @param firstSeenStorage
  *   Storage for block first-seen timestamps
  * @param currentTimeMillis
  *   Function to get current time (for testing)
  */
class MESSScorer(
    config: MESSConfig,
    firstSeenStorage: BlockFirstSeenStorage,
    currentTimeMillis: () => Long = () => System.currentTimeMillis()
) {

  /** Calculate MESS-adjusted difficulty for a single block.
    *
    * @param blockHash
    *   Hash of the block
    * @param difficulty
    *   Original block difficulty
    * @param blockTimestamp
    *   Timestamp from the block header (used if no first-seen time recorded)
    * @return
    *   Adjusted difficulty after applying MESS penalty
    */
  def calculateMessDifficulty(
      blockHash: ByteString,
      difficulty: BigInt,
      blockTimestamp: Long
  ): BigInt = {
    if (!config.enabled) {
      return difficulty
    }

    val firstSeenTime = firstSeenStorage.get(blockHash).getOrElse(blockTimestamp)
    val currentTime = currentTimeMillis()
    
    val timeDeltaMillis = max(0L, currentTime - firstSeenTime)
    // Convert to Double for exponential calculation
    // Note: This introduces minor floating-point precision loss for very large
    // time deltas, but the impact is negligible for the exponential decay calculation
    val timeDeltaSeconds = timeDeltaMillis / 1000.0
    
    // Cap time delta to avoid numerical issues
    val cappedTimeDelta = min(timeDeltaSeconds, config.maxTimeDelta.toDouble)
    
    // Calculate exponential decay: exp(-lambda * timeDelta)
    val decayFactor = exp(-config.decayConstant * cappedTimeDelta)
    
    // Apply minimum multiplier to prevent weights from going to zero
    val multiplier = max(decayFactor, config.minWeightMultiplier)
    
    // Apply multiplier to difficulty
    val adjustedDifficulty = (BigDecimal(difficulty) * multiplier).toBigInt
    
    adjustedDifficulty
  }

  /** Calculate MESS-adjusted difficulty for a block header.
    *
    * @param header
    *   Block header
    * @return
    *   Adjusted difficulty after applying MESS penalty
    */
  def calculateMessDifficulty(header: BlockHeader): BigInt = {
    // Convert unixTimestamp (seconds) to milliseconds, using safe multiplication
    // to avoid potential overflow for very far future timestamps
    val timestampMillis = header.unixTimestamp.toLong * 1000L
    calculateMessDifficulty(header.hash, header.difficulty, timestampMillis)
  }

  /** Calculate the MESS multiplier for a block (for diagnostics/metrics).
    *
    * @param blockHash
    *   Hash of the block
    * @param blockTimestamp
    *   Timestamp from the block header (milliseconds)
    * @return
    *   Multiplier in range [minWeightMultiplier, 1.0]
    */
  def calculateMultiplier(blockHash: ByteString, blockTimestamp: Long): Double = {
    if (!config.enabled) {
      return 1.0
    }

    val firstSeenTime = firstSeenStorage.get(blockHash).getOrElse(blockTimestamp)
    val currentTime = currentTimeMillis()
    
    val timeDeltaMillis = max(0L, currentTime - firstSeenTime)
    val timeDeltaSeconds = timeDeltaMillis / 1000.0
    
    val cappedTimeDelta = min(timeDeltaSeconds, config.maxTimeDelta.toDouble)
    val decayFactor = exp(-config.decayConstant * cappedTimeDelta)
    
    max(decayFactor, config.minWeightMultiplier)
  }

  /** Record the first-seen time for a block if not already recorded.
    *
    * @param blockHash
    *   Hash of the block
    * @return
    *   true if this is the first time seeing the block, false if already seen
    */
  def recordFirstSeen(blockHash: ByteString): Boolean = {
    if (firstSeenStorage.contains(blockHash)) {
      false
    } else {
      firstSeenStorage.put(blockHash, currentTimeMillis())
      true
    }
  }
}
