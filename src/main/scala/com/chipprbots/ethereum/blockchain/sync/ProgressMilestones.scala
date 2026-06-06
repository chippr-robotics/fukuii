package com.chipprbots.ethereum.blockchain.sync

object ProgressMilestones {
  val thresholds: Vector[Int] =
    ((0 to 5) ++ (10 to 90 by 5) ++ (95 to 100)).toVector.distinct.sorted

  def crossed(completed: Long, total: Long, lastEmitted: Int): (Int, Seq[Int]) = {
    if (total <= 0) return (lastEmitted, Seq.empty)
    val pct = ((completed.toDouble / total) * 100).toInt.min(100)
    val hits = thresholds.filter(m => m > lastEmitted && m <= pct)
    val newLast = if (hits.isEmpty) lastEmitted else hits.last
    (newLast, hits)
  }
}
