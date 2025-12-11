package com.chipprbots.ethereum.blockchain.sync.snap

import scala.concurrent.duration._
import scala.collection.mutable
import com.chipprbots.ethereum.utils.Logger

/** Error handling and recovery for SNAP sync
  *
  * Provides:
  *   - Exponential backoff for retry logic
  *   - Circuit breaker for repeatedly failing tasks
  *   - Peer performance tracking and blacklisting
  *   - Error context and logging
  *
  * Based on patterns from core-geth snap sync error handling.
  */
class SNAPErrorHandler(
    maxRetries: Int = 3,
    initialBackoff: FiniteDuration = 1.second,
    maxBackoff: FiniteDuration = 60.seconds,
    circuitBreakerThreshold: Int = 10
) extends Logger {

  import SNAPErrorHandler._

  /** Task retry state tracking */
  private val taskRetries = mutable.Map[String, RetryState]()

  /** Peer failure tracking for blacklisting */
  private val peerFailures = mutable.Map[String, PeerFailureState]()

  /** Circuit breaker state for tasks */
  private val circuitBreakers = mutable.Map[String, CircuitBreakerState]()

  /** Calculate backoff duration for a retry attempt
    *
    * Uses exponential backoff: initialBackoff * 2^(attempt - 1) Capped at maxBackoff to prevent unreasonably long
    * delays.
    *
    * @param attempt
    *   The retry attempt number (1-indexed)
    * @return
    *   Backoff duration for this attempt
    */
  def calculateBackoff(attempt: Int): FiniteDuration = {
    val backoffMs = math.min(
      initialBackoff.toMillis * math.pow(2, attempt - 1).toLong,
      maxBackoff.toMillis
    )
    backoffMs.millis
  }

  /** Record a task retry
    *
    * @param taskId
    *   Unique identifier for the task
    * @param error
    *   Error that caused the retry
    * @return
    *   Retry state with updated attempt count and backoff
    */
  def recordRetry(taskId: String, error: String): RetryState = synchronized {
    val currentState = taskRetries.getOrElse(taskId, RetryState(taskId, 0, System.currentTimeMillis(), None))
    val newAttempt = currentState.attempts + 1
    val backoff = calculateBackoff(newAttempt)

    val newState = currentState.copy(
      attempts = newAttempt,
      lastAttemptTime = System.currentTimeMillis(),
      lastError = Some(error),
      nextRetryTime = Some(System.currentTimeMillis() + backoff.toMillis)
    )

    taskRetries.put(taskId, newState)

    if (newAttempt >= maxRetries) {
      log.warn(s"Task $taskId exceeded max retries ($maxRetries). Last error: $error")
    } else {
      log.info(s"Task $taskId retry $newAttempt/$maxRetries scheduled in ${backoff.toSeconds}s. Error: $error")
    }

    newState
  }

  /** Check if a task should be retried
    *
    * @param taskId
    *   Unique identifier for the task
    * @return
    *   true if task should be retried, false if max retries exceeded
    */
  def shouldRetry(taskId: String): Boolean = synchronized {
    taskRetries.get(taskId) match {
      case Some(state) => state.attempts < maxRetries
      case None        => true // First attempt
    }
  }

  /** Check if a task is ready for retry (backoff period elapsed)
    *
    * @param taskId
    *   Unique identifier for the task
    * @return
    *   true if backoff period has elapsed
    */
  def isRetryReady(taskId: String): Boolean = synchronized {
    taskRetries.get(taskId) match {
      case Some(state) =>
        state.nextRetryTime match {
          case Some(nextTime) => System.currentTimeMillis() >= nextTime
          case None           => true
        }
      case None => true
    }
  }

  /** Reset retry state for a task (called on success)
    *
    * @param taskId
    *   Unique identifier for the task
    */
  def resetRetries(taskId: String): Unit = synchronized {
    taskRetries.remove(taskId).foreach { state =>
      if (state.attempts > 0) {
        log.debug(s"Task $taskId succeeded after ${state.attempts} retries")
      }
    }
  }

  /** Record a peer failure
    *
    * @param peerId
    *   Peer identifier
    * @param errorType
    *   Type of error (e.g., "timeout", "invalid_proof", "malformed_response")
    * @param context
    *   Additional context about the failure
    * @return
    *   Updated peer failure state
    */
  def recordPeerFailure(peerId: String, errorType: String, context: String = ""): PeerFailureState = synchronized {
    val currentState =
      peerFailures.getOrElse(peerId, PeerFailureState(peerId, 0, Map.empty, System.currentTimeMillis()))
    val errorCounts = currentState.errorsByType
    val newCount = errorCounts.getOrElse(errorType, 0) + 1

    val newState = currentState.copy(
      totalFailures = currentState.totalFailures + 1,
      errorsByType = errorCounts + (errorType -> newCount),
      lastFailureTime = System.currentTimeMillis()
    )

    peerFailures.put(peerId, newState)

    val contextStr = if (context.nonEmpty) s" ($context)" else ""
    log.warn(s"Peer $peerId failure: $errorType$contextStr. Total failures: ${newState.totalFailures}")

    newState
  }

  /** Check if a peer should be blacklisted
    *
    * Blacklist criteria:
    *   - Too many total failures
    *   - Too many failures of a specific severe type (e.g., invalid_proof)
    *
    * @param peerId
    *   Peer identifier
    * @return
    *   true if peer should be blacklisted
    */
  def shouldBlacklistPeer(peerId: String): Boolean = synchronized {
    peerFailures.get(peerId) match {
      case Some(state) =>
        // Blacklist if total failures exceed threshold
        if (state.totalFailures >= 10) {
          log.error(s"Peer $peerId exceeded failure threshold (${state.totalFailures} failures). Recommend blacklist.")
          return true
        }

        // Blacklist for severe errors (invalid proofs indicate malicious/broken peer)
        val invalidProofCount = state.errorsByType.getOrElse("invalid_proof", 0)
        if (invalidProofCount >= 3) {
          log.error(s"Peer $peerId sent $invalidProofCount invalid proofs. Recommend blacklist.")
          return true
        }

        val malformedCount = state.errorsByType.getOrElse("malformed_response", 0)
        if (malformedCount >= 5) {
          log.error(s"Peer $peerId sent $malformedCount malformed responses. Recommend blacklist.")
          return true
        }

        false

      case None => false
    }
  }

  /** Record a peer success (good behavior)
    *
    * Reduces failure count to allow recovery of temporarily problematic peers.
    *
    * @param peerId
    *   Peer identifier
    */
  def recordPeerSuccess(peerId: String): Unit = synchronized {
    peerFailures.get(peerId).foreach { state =>
      // Reduce failure count by 1 on success (exponential forgiveness)
      val newCount = math.max(0, state.totalFailures - 1)
      val newState = state.copy(totalFailures = newCount)

      if (newCount == 0) {
        // Peer fully redeemed
        peerFailures.remove(peerId)
        log.debug(s"Peer $peerId failure count cleared")
      } else {
        peerFailures.put(peerId, newState)
        log.debug(s"Peer $peerId failure count reduced to $newCount")
      }
    }
  }

  /** Get peer failure statistics
    *
    * @param peerId
    *   Peer identifier
    * @return
    *   Peer failure state if tracked
    */
  def getPeerFailureState(peerId: String): Option[PeerFailureState] = synchronized {
    peerFailures.get(peerId)
  }

  /** Circuit breaker: record a failure for a task type
    *
    * Circuit breaker prevents repeatedly attempting tasks that consistently fail. After threshold failures, the circuit
    * "opens" and blocks further attempts.
    *
    * @param taskType
    *   Type of task (e.g., "account_range_download")
    * @return
    *   Updated circuit breaker state
    */
  def recordCircuitBreakerFailure(taskType: String): CircuitBreakerState = synchronized {
    val currentState = circuitBreakers.getOrElse(
      taskType,
      CircuitBreakerState(taskType, 0, CircuitState.Closed, System.currentTimeMillis())
    )
    val newFailures = currentState.consecutiveFailures + 1

    val newCircuitState = if (newFailures >= circuitBreakerThreshold) {
      log.error(s"Circuit breaker OPEN for $taskType after $newFailures consecutive failures")
      CircuitState.Open
    } else {
      CircuitState.Closed
    }

    val newState = currentState.copy(
      consecutiveFailures = newFailures,
      state = newCircuitState,
      lastFailureTime = System.currentTimeMillis()
    )

    circuitBreakers.put(taskType, newState)
    newState
  }

  /** Circuit breaker: record a success for a task type
    *
    * Resets consecutive failure count and closes the circuit if open.
    *
    * @param taskType
    *   Type of task
    */
  def recordCircuitBreakerSuccess(taskType: String): Unit = synchronized {
    circuitBreakers.get(taskType).foreach { state =>
      if (state.consecutiveFailures > 0) {
        log.info(s"Circuit breaker for $taskType reset after success (was ${state.consecutiveFailures} failures)")
      }
    }
    circuitBreakers.remove(taskType)
  }

  /** Check if circuit breaker is open for a task type
    *
    * @param taskType
    *   Type of task
    * @return
    *   true if circuit is open (task should not be attempted)
    */
  def isCircuitOpen(taskType: String): Boolean = synchronized {
    circuitBreakers.get(taskType) match {
      case Some(state) => state.state == CircuitState.Open
      case None        => false
    }
  }

  /** Get circuit breaker state
    *
    * @param taskType
    *   Type of task
    * @return
    *   Circuit breaker state if tracked
    */
  def getCircuitBreakerState(taskType: String): Option[CircuitBreakerState] = synchronized {
    circuitBreakers.get(taskType)
  }

  /** Create error context for logging
    *
    * @param phase
    *   Current sync phase
    * @param peerId
    *   Peer identifier
    * @param requestId
    *   Request ID if applicable
    * @param taskId
    *   Task identifier if applicable
    * @return
    *   Formatted error context string
    */
  def createErrorContext(
      phase: String,
      peerId: Option[String] = None,
      requestId: Option[BigInt] = None,
      taskId: Option[String] = None
  ): String = {
    val parts = mutable.ArrayBuffer(s"phase=$phase")
    peerId.foreach(p => parts += s"peer=$p")
    requestId.foreach(r => parts += s"requestId=$r")
    taskId.foreach(t => parts += s"taskId=$t")
    parts.mkString(", ")
  }

  /** Get retry statistics summary */
  def getRetryStatistics: RetryStatistics = synchronized {
    val totalTasks = taskRetries.size
    val tasksWithRetries = taskRetries.count(_._2.attempts > 0)
    val totalRetries = taskRetries.values.map(_.attempts).sum
    val maxRetriesReached = taskRetries.count(_._2.attempts >= maxRetries)

    RetryStatistics(
      totalTasksTracked = totalTasks,
      tasksWithRetries = tasksWithRetries,
      totalRetryAttempts = totalRetries,
      tasksAtMaxRetries = maxRetriesReached
    )
  }

  /** Get peer failure statistics summary */
  def getPeerStatistics: PeerStatistics = synchronized {
    val totalPeersTracked = peerFailures.size
    val totalFailures = peerFailures.values.map(_.totalFailures).sum
    val peersToBlacklist = peerFailures.count(p => shouldBlacklistPeer(p._1))

    PeerStatistics(
      totalPeersTracked = totalPeersTracked,
      totalFailuresRecorded = totalFailures,
      peersRecommendedForBlacklist = peersToBlacklist
    )
  }

  /** Clear all error tracking (use with caution) */
  def clear(): Unit = synchronized {
    taskRetries.clear()
    peerFailures.clear()
    circuitBreakers.clear()
    log.info("Error handler state cleared")
  }
}

object SNAPErrorHandler {

  /** Retry state for a task */
  case class RetryState(
      taskId: String,
      attempts: Int,
      lastAttemptTime: Long,
      lastError: Option[String],
      nextRetryTime: Option[Long] = None
  )

  /** Peer failure tracking state */
  case class PeerFailureState(
      peerId: String,
      totalFailures: Int,
      errorsByType: Map[String, Int],
      lastFailureTime: Long
  )

  /** Circuit breaker state */
  sealed trait CircuitState
  object CircuitState {
    case object Closed extends CircuitState // Normal operation
    case object Open extends CircuitState // Circuit tripped, blocking operations
  }

  case class CircuitBreakerState(
      taskType: String,
      consecutiveFailures: Int,
      state: CircuitState,
      lastFailureTime: Long
  )

  /** Retry statistics summary */
  case class RetryStatistics(
      totalTasksTracked: Int,
      tasksWithRetries: Int,
      totalRetryAttempts: Int,
      tasksAtMaxRetries: Int
  )

  /** Peer statistics summary */
  case class PeerStatistics(
      totalPeersTracked: Int,
      totalFailuresRecorded: Int,
      peersRecommendedForBlacklist: Int
  )

  /** Standard error types for consistent tracking */
  object ErrorType {
    val Timeout = "timeout"
    val InvalidProof = "invalid_proof"
    val MalformedResponse = "malformed_response"
    val StorageError = "storage_error"
    val NetworkError = "network_error"
    val ProofVerificationFailed = "proof_verification_failed"
    val StateRootMismatch = "state_root_mismatch"
    val PeerDisconnected = "peer_disconnected"
    val HashMismatch = "hash_mismatch"
    val ProcessingError = "processing_error"
  }
}
