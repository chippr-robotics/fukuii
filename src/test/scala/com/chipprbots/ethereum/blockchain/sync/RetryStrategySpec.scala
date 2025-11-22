package com.chipprbots.ethereum.blockchain.sync

import scala.concurrent.duration._

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.concurrent.Eventually

import com.chipprbots.ethereum.testing.Tags._
import com.chipprbots.ethereum.NormalPatience

class RetryStrategySpec extends AnyFlatSpec with Matchers with Eventually with NormalPatience {

  "RetryStrategy" should "calculate exponential backoff correctly" taggedAs (UnitTest, SyncTest) in {
    val strategy = RetryStrategy(
      initialDelay = 100.millis,
      maxDelay = 10.seconds,
      multiplier = 2.0,
      jitterFactor = 0.0 // No jitter for predictable testing
    )

    val delay0 = strategy.nextDelay(0).toMillis
    val delay1 = strategy.nextDelay(1).toMillis
    val delay2 = strategy.nextDelay(2).toMillis
    val delay3 = strategy.nextDelay(3).toMillis

    delay0 shouldBe 100L +- 1L
    delay1 shouldBe 200L +- 1L
    delay2 shouldBe 400L +- 1L
    delay3 shouldBe 800L +- 1L
  }

  it should "respect maximum delay cap" taggedAs (UnitTest, SyncTest) in {
    val strategy = RetryStrategy(
      initialDelay = 100.millis,
      maxDelay = 500.millis,
      multiplier = 2.0,
      jitterFactor = 0.0
    )

    val delay10 = strategy.nextDelay(10) // Would be 102,400ms without cap
    delay10.toMillis shouldBe 500L +- 1L
  }

  it should "add jitter to delay" taggedAs (UnitTest, SyncTest) in {
    val strategy = RetryStrategy(
      initialDelay = 1000.millis,
      maxDelay = 10.seconds,
      multiplier = 2.0,
      jitterFactor = 0.2 // 20% jitter
    )

    // With 20% jitter, delay should be between base and base*1.2
    val delay0 = strategy.nextDelay(0).toMillis
    delay0 should ((be >= 1000L).and(be <= 1200L))

    // Test multiple times to ensure randomness
    val delays = (1 to 100).map(_ => strategy.nextDelay(0).toMillis)
    delays.toSet.size should be > 1 // Should have variance
    delays.foreach(_ should ((be >= 1000L).and(be <= 1200L)))
  }

  it should "require non-negative attempt number" taggedAs (UnitTest, SyncTest) in {
    val strategy = RetryStrategy.default
    assertThrows[IllegalArgumentException] {
      strategy.nextDelay(-1)
    }
  }

  it should "calculate total time correctly" taggedAs (UnitTest, SyncTest) in {
    val strategy = RetryStrategy(
      initialDelay = 100.millis,
      maxDelay = 10.seconds,
      multiplier = 2.0,
      jitterFactor = 0.0
    )

    // 0: 100ms, 1: 200ms, 2: 400ms, total: 700ms
    val total = strategy.totalTime(3).toMillis
    total shouldBe 700L +- 50L // Allow some variance
  }

  "RetryStrategy.default" should "have reasonable parameters" taggedAs (UnitTest, SyncTest) in {
    val strategy = RetryStrategy.default

    strategy.initialDelay shouldBe 500.millis
    strategy.maxDelay shouldBe 30.seconds
    strategy.multiplier shouldBe 2.0 +- 0.01
    strategy.jitterFactor shouldBe 0.2 +- 0.01
  }

  "RetryStrategy.fast" should "have faster parameters" taggedAs (UnitTest, SyncTest) in {
    val strategy = RetryStrategy.fast

    strategy.initialDelay shouldBe 100.millis
    strategy.maxDelay shouldBe 5.seconds
    strategy.multiplier shouldBe 1.5 +- 0.01
  }

  "RetryStrategy.slow" should "have slower parameters" taggedAs (UnitTest, SyncTest) in {
    val strategy = RetryStrategy.slow

    strategy.initialDelay shouldBe 1.second
    strategy.maxDelay shouldBe 60.seconds
    strategy.multiplier shouldBe 2.5 +- 0.01
  }

  "RetryStrategy.conservative" should "have high jitter" taggedAs (UnitTest, SyncTest) in {
    val strategy = RetryStrategy.conservative

    strategy.initialDelay shouldBe 2.seconds
    strategy.maxDelay shouldBe 120.seconds
    strategy.multiplier shouldBe 3.0 +- 0.01
    strategy.jitterFactor shouldBe 0.5 +- 0.01
  }

  "RetryStrategy" should "support fluent configuration" taggedAs (UnitTest, SyncTest) in {
    val strategy = RetryStrategy.default
      .withInitialDelay(200.millis)
      .withMaxDelay(20.seconds)
      .withMultiplier(1.5)
      .withJitterFactor(0.1)

    strategy.initialDelay shouldBe 200.millis
    strategy.maxDelay shouldBe 20.seconds
    strategy.multiplier shouldBe 1.5 +- 0.01
    strategy.jitterFactor shouldBe 0.1 +- 0.01
  }

  "RetryState" should "start at attempt 0" taggedAs (UnitTest, SyncTest) in {
    val state = RetryState()
    state.attempt shouldBe 0
  }

  it should "increment attempt on record" taggedAs (UnitTest, SyncTest) in {
    val state = RetryState()
    val updated = state.recordAttempt

    updated.attempt shouldBe 1
    updated.firstAttemptTime should be(defined)
    updated.lastAttemptTime should be(defined)
    updated.firstAttemptTime shouldBe updated.lastAttemptTime
  }

  it should "reset to initial state" taggedAs (UnitTest, SyncTest) in {
    val state = RetryState(
      attempt = 5,
      firstAttemptTime = Some(System.currentTimeMillis()),
      lastAttemptTime = Some(System.currentTimeMillis())
    )
    val reset = state.reset

    reset.attempt shouldBe 0
    reset.firstAttemptTime shouldBe None
    reset.lastAttemptTime shouldBe None
  }

  it should "calculate next delay based on current attempt" taggedAs (UnitTest, SyncTest) in {
    val strategy = RetryStrategy(
      initialDelay = 100.millis,
      maxDelay = 10.seconds,
      multiplier = 2.0,
      jitterFactor = 0.0
    )
    val state = RetryState(attempt = 2, strategy = strategy)

    val delay = state.nextDelay.toMillis
    delay shouldBe 400L +- 1L // 100 * 2^2
  }

  it should "detect when max attempts reached" taggedAs (UnitTest, SyncTest) in {
    val state = RetryState(attempt = 5)

    state.shouldGiveUp(maxAttempts = 3) shouldBe true
    state.shouldGiveUp(maxAttempts = 5) shouldBe true // At attempt index 5, we've exceeded maxAttempts=5 (since 5 >= 5)
    state.shouldGiveUp(maxAttempts = 6) shouldBe false
    state.shouldGiveUp(maxAttempts = 10) shouldBe false
  }

  it should "track total time spent from first attempt" taggedAs (UnitTest, SyncTest) in {
    val initialTime = System.currentTimeMillis()
    val state = RetryState(firstAttemptTime = Some(initialTime))

    eventually {
      val elapsed = state.totalTimeSpent.toMillis
      elapsed should be >= 100L
    }
  }
}
