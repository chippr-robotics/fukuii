package com.chipprbots.ethereum.blockchain.sync.fast

import scala.concurrent.duration.FiniteDuration

/** Exponential backoff utility for fast sync retry intervals.
  *
  * Formula: base * 2^min(attempt, 10), capped at max. Reused from the SNAP ByteCodeCoordinator pattern.
  */
object BackoffRetry {

  def delay(attempt: Int, base: FiniteDuration, max: FiniteDuration): FiniteDuration = {
    val factor = math.pow(2, math.min(attempt, 10)).toLong
    (base * factor).min(max)
  }
}
