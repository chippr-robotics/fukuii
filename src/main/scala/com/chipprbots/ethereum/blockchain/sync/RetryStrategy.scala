package com.chipprbots.ethereum.blockchain.sync

import scala.concurrent.duration._
import scala.util.Random

/** Exponential backoff retry strategy with jitter
  *
  * Implements progressive retry delays to reduce network load during sync issues.
  * Based on investigation report recommendations (Priority 3).
  *
  * @param initialDelay Starting delay for first retry
  * @param maxDelay Maximum delay cap
  * @param multiplier Exponential growth factor
  * @param jitterFactor Maximum jitter as fraction of delay (0.0-1.0)
  */
final case class RetryStrategy(
    initialDelay: FiniteDuration = 500.millis,
    maxDelay: FiniteDuration = 30.seconds,
    multiplier: Double = 2.0,
    jitterFactor: Double = 0.2
) {
  require(multiplier >= 1.0, "Multiplier must be >= 1.0")
  require(jitterFactor >= 0.0 && jitterFactor <= 1.0, "Jitter factor must be between 0.0 and 1.0")

  private val random = new Random()

  /** Calculate next delay for given attempt number (0-indexed)
    *
    * Formula: min(initialDelay * multiplier^attempt, maxDelay) + jitter
    * Jitter is random value between 0 and (delay * jitterFactor)
    */
  def nextDelay(attempt: Int): FiniteDuration = {
    require(attempt >= 0, "Attempt must be non-negative")

    val baseDelay = math.min(
      initialDelay.toMillis * math.pow(multiplier, attempt),
      maxDelay.toMillis
    )

    val jitterMs = if (jitterFactor > 0.0) {
      random.nextInt((baseDelay * jitterFactor).toInt + 1)
    } else 0

    (baseDelay.toLong + jitterMs).millis
  }

  /** Calculate total time spent after N attempts */
  def totalTime(attempts: Int): FiniteDuration = {
    (0 until attempts)
      .map(nextDelay)
      .foldLeft(0.millis)(_ + _)
  }

  /** Create a retry strategy with different parameters */
  def withInitialDelay(delay: FiniteDuration): RetryStrategy =
    copy(initialDelay = delay)

  def withMaxDelay(delay: FiniteDuration): RetryStrategy =
    copy(maxDelay = delay)

  def withMultiplier(m: Double): RetryStrategy =
    copy(multiplier = m)

  def withJitterFactor(factor: Double): RetryStrategy =
    copy(jitterFactor = factor)
}

object RetryStrategy {

  /** Default retry strategy for sync operations */
  val default: RetryStrategy = RetryStrategy()

  /** Fast retry strategy for low-latency operations */
  val fast: RetryStrategy = RetryStrategy(
    initialDelay = 100.millis,
    maxDelay = 5.seconds,
    multiplier = 1.5
  )

  /** Slow retry strategy for resource-intensive operations */
  val slow: RetryStrategy = RetryStrategy(
    initialDelay = 1.second,
    maxDelay = 60.seconds,
    multiplier = 2.5
  )

  /** Conservative retry strategy with high jitter for load distribution */
  val conservative: RetryStrategy = RetryStrategy(
    initialDelay = 2.seconds,
    maxDelay = 120.seconds,
    multiplier = 3.0,
    jitterFactor = 0.5
  )
}

/** Retry state tracker for individual operations */
final case class RetryState(
    attempt: Int = 0,
    strategy: RetryStrategy = RetryStrategy.default,
    lastAttemptTime: Option[Long] = None
) {

  /** Get next delay for current attempt */
  def nextDelay: FiniteDuration = strategy.nextDelay(attempt)

  /** Record a retry attempt */
  def recordAttempt: RetryState = copy(
    attempt = attempt + 1,
    lastAttemptTime = Some(System.currentTimeMillis())
  )

  /** Reset retry state (e.g., after success) */
  def reset: RetryState = RetryState(0, strategy, None)

  /** Check if max attempts reached */
  def shouldGiveUp(maxAttempts: Int): Boolean = attempt >= maxAttempts

  /** Get total time spent retrying */
  def totalTimeSpent: FiniteDuration =
    lastAttemptTime match {
      case None => 0.millis
      case Some(startTime) =>
        (System.currentTimeMillis() - startTime).millis
    }
}
