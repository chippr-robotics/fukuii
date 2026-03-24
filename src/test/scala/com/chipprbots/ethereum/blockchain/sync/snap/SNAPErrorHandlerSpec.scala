package com.chipprbots.ethereum.blockchain.sync.snap

import scala.concurrent.duration._

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags._

class SNAPErrorHandlerSpec extends AnyFlatSpec with Matchers {

  // ========================================
  // Exponential Backoff
  // ========================================

  "SNAPErrorHandler" should "calculate exponential backoff correctly" taggedAs UnitTest in {
    val handler = new SNAPErrorHandler(
      maxRetries = 5,
      initialBackoff = 1.second,
      maxBackoff = 60.seconds
    )

    handler.calculateBackoff(1) shouldBe 1.second
    handler.calculateBackoff(2) shouldBe 2.seconds
    handler.calculateBackoff(3) shouldBe 4.seconds
    handler.calculateBackoff(4) shouldBe 8.seconds
    handler.calculateBackoff(5) shouldBe 16.seconds
  }

  it should "cap backoff at maxBackoff" taggedAs UnitTest in {
    val handler = new SNAPErrorHandler(
      maxRetries = 20,
      initialBackoff = 1.second,
      maxBackoff = 60.seconds
    )

    // 2^19 seconds >> 60 seconds
    handler.calculateBackoff(20) shouldBe 60.seconds
  }

  it should "handle first attempt backoff" taggedAs UnitTest in {
    val handler = new SNAPErrorHandler(initialBackoff = 500.millis)
    handler.calculateBackoff(1) shouldBe 500.millis
  }

  // ========================================
  // Task Retry Tracking
  // ========================================

  it should "track task retries" taggedAs UnitTest in {
    val handler = new SNAPErrorHandler(maxRetries = 3)

    val state1 = handler.recordRetry("task-1", "timeout")
    state1.attempts shouldBe 1
    state1.lastError shouldBe Some("timeout")
    state1.nextRetryTime shouldBe defined

    val state2 = handler.recordRetry("task-1", "network error")
    state2.attempts shouldBe 2
    state2.lastError shouldBe Some("network error")
  }

  it should "report shouldRetry correctly" taggedAs UnitTest in {
    val handler = new SNAPErrorHandler(maxRetries = 2)

    handler.shouldRetry("task-1") shouldBe true // First attempt

    handler.recordRetry("task-1", "error1")
    handler.shouldRetry("task-1") shouldBe true // 1 attempt < 2 max

    handler.recordRetry("task-1", "error2")
    handler.shouldRetry("task-1") shouldBe false // 2 attempts >= 2 max
  }

  it should "report isRetryReady for unknown tasks" taggedAs UnitTest in {
    val handler = new SNAPErrorHandler()
    handler.isRetryReady("unknown-task") shouldBe true
  }

  it should "reset retries on success" taggedAs UnitTest in {
    val handler = new SNAPErrorHandler(maxRetries = 3)

    handler.recordRetry("task-1", "error")
    handler.shouldRetry("task-1") shouldBe true

    handler.resetRetries("task-1")
    handler.shouldRetry("task-1") shouldBe true // Reset, so first attempt again
  }

  it should "handle reset of untracked task" taggedAs UnitTest in {
    val handler = new SNAPErrorHandler()
    // Should not throw
    handler.resetRetries("nonexistent-task")
  }

  // ========================================
  // Peer Failure Tracking
  // ========================================

  it should "track peer failures" taggedAs UnitTest in {
    val handler = new SNAPErrorHandler()

    val state = handler.recordPeerFailure("peer-1", "timeout", "account range request")
    state.totalFailures shouldBe 1
    state.errorsByType shouldBe Map("timeout" -> 1)
  }

  it should "accumulate peer failures by type" taggedAs UnitTest in {
    val handler = new SNAPErrorHandler()

    handler.recordPeerFailure("peer-1", "timeout")
    handler.recordPeerFailure("peer-1", "timeout")
    handler.recordPeerFailure("peer-1", "invalid_proof")

    val state = handler.getPeerFailureState("peer-1")
    state shouldBe defined
    state.get.totalFailures shouldBe 3
    state.get.errorsByType("timeout") shouldBe 2
    state.get.errorsByType("invalid_proof") shouldBe 1
  }

  it should "not blacklist peer with few failures" taggedAs UnitTest in {
    val handler = new SNAPErrorHandler()

    handler.recordPeerFailure("peer-1", "timeout")
    handler.recordPeerFailure("peer-1", "timeout")
    handler.shouldBlacklistPeer("peer-1") shouldBe false
  }

  it should "blacklist peer after 10 total failures" taggedAs UnitTest in {
    val handler = new SNAPErrorHandler()

    (1 to 10).foreach(_ => handler.recordPeerFailure("peer-1", "timeout"))
    handler.shouldBlacklistPeer("peer-1") shouldBe true
  }

  it should "blacklist peer after 3 invalid proofs" taggedAs UnitTest in {
    val handler = new SNAPErrorHandler()

    handler.recordPeerFailure("peer-1", "invalid_proof")
    handler.recordPeerFailure("peer-1", "invalid_proof")
    handler.shouldBlacklistPeer("peer-1") shouldBe false

    handler.recordPeerFailure("peer-1", "invalid_proof")
    handler.shouldBlacklistPeer("peer-1") shouldBe true
  }

  it should "blacklist peer after 5 malformed responses" taggedAs UnitTest in {
    val handler = new SNAPErrorHandler()

    (1 to 4).foreach(_ => handler.recordPeerFailure("peer-1", "malformed_response"))
    handler.shouldBlacklistPeer("peer-1") shouldBe false

    handler.recordPeerFailure("peer-1", "malformed_response")
    handler.shouldBlacklistPeer("peer-1") shouldBe true
  }

  it should "not blacklist unknown peer" taggedAs UnitTest in {
    val handler = new SNAPErrorHandler()
    handler.shouldBlacklistPeer("unknown-peer") shouldBe false
  }

  it should "reduce failure count on peer success (exponential forgiveness)" taggedAs UnitTest in {
    val handler = new SNAPErrorHandler()

    handler.recordPeerFailure("peer-1", "timeout")
    handler.recordPeerFailure("peer-1", "timeout")
    handler.recordPeerFailure("peer-1", "timeout")

    handler.getPeerFailureState("peer-1").get.totalFailures shouldBe 3

    handler.recordPeerSuccess("peer-1")
    handler.getPeerFailureState("peer-1").get.totalFailures shouldBe 2

    handler.recordPeerSuccess("peer-1")
    handler.getPeerFailureState("peer-1").get.totalFailures shouldBe 1

    handler.recordPeerSuccess("peer-1")
    handler.getPeerFailureState("peer-1") shouldBe None // Fully redeemed
  }

  it should "handle success for untracked peer" taggedAs UnitTest in {
    val handler = new SNAPErrorHandler()
    // Should not throw
    handler.recordPeerSuccess("unknown-peer")
  }

  // ========================================
  // Circuit Breaker
  // ========================================

  it should "start with circuit closed" taggedAs UnitTest in {
    val handler = new SNAPErrorHandler(circuitBreakerThreshold = 5)
    handler.isCircuitOpen("account_range") shouldBe false
  }

  it should "open circuit after threshold consecutive failures" taggedAs UnitTest in {
    val handler = new SNAPErrorHandler(circuitBreakerThreshold = 5)

    (1 to 4).foreach(_ => handler.recordCircuitBreakerFailure("account_range"))
    handler.isCircuitOpen("account_range") shouldBe false

    handler.recordCircuitBreakerFailure("account_range")
    handler.isCircuitOpen("account_range") shouldBe true
  }

  it should "reset circuit on success" taggedAs UnitTest in {
    val handler = new SNAPErrorHandler(circuitBreakerThreshold = 3)

    (1 to 3).foreach(_ => handler.recordCircuitBreakerFailure("storage_download"))
    handler.isCircuitOpen("storage_download") shouldBe true

    handler.recordCircuitBreakerSuccess("storage_download")
    handler.isCircuitOpen("storage_download") shouldBe false
  }

  it should "track circuit breaker state" taggedAs UnitTest in {
    val handler = new SNAPErrorHandler(circuitBreakerThreshold = 10)

    handler.getCircuitBreakerState("unknown") shouldBe None

    handler.recordCircuitBreakerFailure("test_task")
    val state = handler.getCircuitBreakerState("test_task")
    state shouldBe defined
    state.get.consecutiveFailures shouldBe 1
    state.get.state shouldBe SNAPErrorHandler.CircuitState.Closed
  }

  // ========================================
  // Error Context
  // ========================================

  it should "create error context with all fields" taggedAs UnitTest in {
    val handler = new SNAPErrorHandler()

    val ctx = handler.createErrorContext(
      phase = "AccountRangeSync",
      peerId = Some("peer-1"),
      requestId = Some(BigInt(42)),
      taskId = Some("task-1")
    )

    ctx should include("phase=AccountRangeSync")
    ctx should include("peer=peer-1")
    ctx should include("requestId=42")
    ctx should include("taskId=task-1")
  }

  it should "create error context with minimal fields" taggedAs UnitTest in {
    val handler = new SNAPErrorHandler()

    val ctx = handler.createErrorContext(phase = "Healing")
    ctx shouldBe "phase=Healing"
  }

  // ========================================
  // Statistics
  // ========================================

  it should "provide retry statistics" taggedAs UnitTest in {
    val handler = new SNAPErrorHandler(maxRetries = 3)

    handler.recordRetry("task-1", "error")
    handler.recordRetry("task-1", "error")
    handler.recordRetry("task-2", "error")
    handler.recordRetry("task-2", "error")
    handler.recordRetry("task-2", "error") // At max

    val stats = handler.getRetryStatistics
    stats.totalTasksTracked shouldBe 2
    stats.tasksWithRetries shouldBe 2
    stats.totalRetryAttempts shouldBe 5
    stats.tasksAtMaxRetries shouldBe 1
  }

  it should "provide peer statistics" taggedAs UnitTest in {
    val handler = new SNAPErrorHandler()

    (1 to 10).foreach(_ => handler.recordPeerFailure("bad-peer", "timeout"))
    handler.recordPeerFailure("ok-peer", "timeout")

    val stats = handler.getPeerStatistics
    stats.totalPeersTracked shouldBe 2
    stats.totalFailuresRecorded shouldBe 11
    stats.peersRecommendedForBlacklist shouldBe 1
  }

  // ========================================
  // Clear
  // ========================================

  it should "clear all state" taggedAs UnitTest in {
    val handler = new SNAPErrorHandler(maxRetries = 3, circuitBreakerThreshold = 3)

    handler.recordRetry("task-1", "error")
    handler.recordPeerFailure("peer-1", "timeout")
    (1 to 3).foreach(_ => handler.recordCircuitBreakerFailure("cb-task"))

    handler.clear()

    handler.shouldRetry("task-1") shouldBe true
    handler.getPeerFailureState("peer-1") shouldBe None
    handler.isCircuitOpen("cb-task") shouldBe false
    handler.getRetryStatistics.totalTasksTracked shouldBe 0
  }

  // ========================================
  // Error Types
  // ========================================

  "ErrorType" should "have standard error type constants" taggedAs UnitTest in {
    SNAPErrorHandler.ErrorType.Timeout shouldBe "timeout"
    SNAPErrorHandler.ErrorType.InvalidProof shouldBe "invalid_proof"
    SNAPErrorHandler.ErrorType.MalformedResponse shouldBe "malformed_response"
    SNAPErrorHandler.ErrorType.StorageError shouldBe "storage_error"
    SNAPErrorHandler.ErrorType.NetworkError shouldBe "network_error"
    SNAPErrorHandler.ErrorType.ProofVerificationFailed shouldBe "proof_verification_failed"
    SNAPErrorHandler.ErrorType.StateRootMismatch shouldBe "state_root_mismatch"
    SNAPErrorHandler.ErrorType.PeerDisconnected shouldBe "peer_disconnected"
    SNAPErrorHandler.ErrorType.HashMismatch shouldBe "hash_mismatch"
    SNAPErrorHandler.ErrorType.ProcessingError shouldBe "processing_error"
  }

  // ========================================
  // Thread Safety
  // ========================================

  it should "handle concurrent access safely" taggedAs UnitTest in {
    val handler = new SNAPErrorHandler(maxRetries = 100, circuitBreakerThreshold = 100)

    import scala.concurrent.{Await, Future}
    import scala.concurrent.ExecutionContext.Implicits.global

    val futures = (1 to 50).map { i =>
      Future {
        handler.recordRetry(s"task-$i", "error")
        handler.recordPeerFailure(s"peer-$i", "timeout")
        handler.recordCircuitBreakerFailure(s"cb-$i")
        handler.shouldRetry(s"task-$i")
        handler.shouldBlacklistPeer(s"peer-$i")
        handler.isCircuitOpen(s"cb-$i")
      }
    }

    Await.result(Future.sequence(futures), 5.seconds)

    handler.getRetryStatistics.totalTasksTracked shouldBe 50
    handler.getPeerStatistics.totalPeersTracked shouldBe 50
  }
}
