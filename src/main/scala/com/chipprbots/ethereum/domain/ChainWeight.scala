package com.chipprbots.ethereum.domain

object ChainWeight {
  def totalDifficultyOnly(td: BigInt): ChainWeight =
    ChainWeight(td)

  val zero: ChainWeight =
    ChainWeight(0)
}

/** Represents the weight of a blockchain chain.
  *
  * ChainWeight is used to compare competing chains and determine the canonical chain. It supports two modes:
  *   - Standard: Based on total difficulty
  *   - MESS-enhanced: Additionally includes MESS (Modified Exponential Subjective Scoring)
  *
  * @param totalDifficulty
  *   Sum of all block difficulties in the chain
  * @param messScore
  *   Optional MESS-adjusted total difficulty (when MESS is enabled)
  */
case class ChainWeight(
    totalDifficulty: BigInt,
    messScore: Option[BigInt] = None
) extends Ordered[ChainWeight] {

  /** Compare this chain weight with another.
    *
    * Comparison priority: MESS score if both chains have it, otherwise total difficulty
    *
    * @param that
    *   The other chain weight to compare with
    * @return
    *   negative if this < that, 0 if equal, positive if this > that
    */
  override def compare(that: ChainWeight): Int = {
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
    val newTotalDifficulty = totalDifficulty + header.difficulty
    val newMessScore = (messScore, messAdjustedDifficulty) match {
      case (Some(score), Some(messDiff)) =>
        Some(score + messDiff)
      case (None, Some(messDiff)) => Some(messDiff)
      case _                      => None
    }

    ChainWeight(newTotalDifficulty, newMessScore)
  }

  /** Get the effective score used for comparison. Returns MESS score if available, otherwise total difficulty.
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
