package com.chipprbots.ethereum.domain

object ChainWeight {
  // FIXME: a shorter name?
  def totalDifficultyOnly(td: BigInt): ChainWeight =
    ChainWeight(0, td)

  val zero: ChainWeight =
    ChainWeight(0, 0)
}

/** Represents the weight of a blockchain chain.
  *
  * ChainWeight is used to compare competing chains and determine the canonical chain.
  * It supports two modes:
  * - Standard: Based on checkpoint number and total difficulty
  * - MESS-enhanced: Additionally includes MESS (Modified Exponential Subjective Scoring)
  *
  * @param lastCheckpointNumber
  *   The number of the last checkpoint block in the chain
  * @param totalDifficulty
  *   Sum of all block difficulties in the chain
  * @param messScore
  *   Optional MESS-adjusted total difficulty (when MESS is enabled)
  */
case class ChainWeight(
    lastCheckpointNumber: BigInt,
    totalDifficulty: BigInt,
    messScore: Option[BigInt] = None
) extends Ordered[ChainWeight] {

  /** Compare this chain weight with another.
    *
    * Comparison priority:
    * 1. Last checkpoint number (higher is better)
    * 2. MESS score if both chains have it, otherwise total difficulty
    *
    * @param that
    *   The other chain weight to compare with
    * @return
    *   negative if this < that, 0 if equal, positive if this > that
    */
  override def compare(that: ChainWeight): Int = {
    // First compare checkpoint numbers (higher checkpoint wins)
    val checkpointComparison = this.lastCheckpointNumber.compare(that.lastCheckpointNumber)
    if (checkpointComparison != 0) {
      return checkpointComparison
    }

    // If both have MESS scores, compare those
    // Otherwise fall back to total difficulty
    (this.messScore, that.messScore) match {
      case (Some(thisScore), Some(thatScore)) =>
        thisScore.compare(thatScore)
      case _ =>
        this.totalDifficulty.compare(that.totalDifficulty)
    }
  }

  /** Increase the chain weight by adding a new block header.
    *
    * @param header
    *   The block header to add
    * @param messAdjustedDifficulty
    *   Optional MESS-adjusted difficulty for this block (when MESS is enabled)
    * @return
    *   New ChainWeight with the block incorporated
    */
  def increase(header: BlockHeader, messAdjustedDifficulty: Option[BigInt] = None): ChainWeight = {
    val isNewerCheckpoint = header.hasCheckpoint && header.number > lastCheckpointNumber
    val checkpointNum = if (isNewerCheckpoint) header.number else lastCheckpointNumber
    
    val newTotalDifficulty = totalDifficulty + header.difficulty
    val newMessScore = (messScore, messAdjustedDifficulty) match {
      case (Some(score), Some(messDiff)) => 
        // Note: BigInt addition is assumed not to overflow within reasonable chain lengths
        // (i.e., total accumulated difficulty fits within BigInt's capacity)
        Some(score + messDiff)
      case (None, Some(messDiff)) => Some(messDiff)
      case _ => None
    }
    
    ChainWeight(checkpointNum, newTotalDifficulty, newMessScore)
  }

  def asTuple: (BigInt, BigInt) =
    (lastCheckpointNumber, totalDifficulty)

  /** Get the effective score used for comparison.
    * Returns MESS score if available, otherwise total difficulty.
    */
  def effectiveScore: BigInt =
    messScore.getOrElse(totalDifficulty)

  // Test API

  def increaseTotalDifficulty(td: BigInt): ChainWeight =
    copy(totalDifficulty = totalDifficulty + td)

  /** Test API: Increase MESS score */
  def increaseMessScore(messDiff: BigInt): ChainWeight =
    copy(messScore = Some(messScore.getOrElse(BigInt(0)) + messDiff))
}
