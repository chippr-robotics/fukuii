package com.chipprbots.ethereum.blockchain.sync

import com.chipprbots.ethereum.utils.Logger

/** Adaptive sync strategy with fallback chain
  *
  * Implements progressive fallback from fastest to most reliable sync method. Based on investigation report
  * recommendations (Priority 2).
  */
sealed trait SyncStrategy {
  def name: String
  def requiresMinPeers: Int
  def description: String
}

object SyncStrategy {

  /** Snap sync - fastest, downloads recent state snapshot */
  case object SnapSync extends SyncStrategy {
    val name = "snap"
    val requiresMinPeers = 3
    val description = "Snap sync with bootstrap checkpoint (fastest)"
  }

  /** Fast sync - medium speed, validates recent blocks */
  case object FastSync extends SyncStrategy {
    val name = "fast"
    val requiresMinPeers = 3
    val description = "Fast sync with peer-selected pivot"
  }

  /** Full sync - slowest but most reliable, validates all blocks */
  case object FullSync extends SyncStrategy {
    val name = "full"
    val requiresMinPeers = 1
    val description = "Full sync from genesis (most reliable)"
  }

  /** Default fallback chain */
  val defaultFallbackChain: List[SyncStrategy] = List(SnapSync, FastSync, FullSync)
}

/** Network conditions affecting sync strategy selection */
final case class NetworkConditions(
    availablePeerCount: Int,
    checkpointsAvailable: Boolean,
    previousSyncFailures: Int = 0,
    averagePeerLatencyMs: Option[Double] = None
) {
  def hasGoodConnectivity: Boolean =
    availablePeerCount >= 5 && averagePeerLatencyMs.exists(_ < 500)

  def hasPoorConnectivity: Boolean =
    availablePeerCount < 3 || averagePeerLatencyMs.exists(_ > 2000)
}

/** Result of sync attempt */
sealed trait SyncResult

object SyncResult {
  final case class Success(blocksSynced: Long, durationMs: Long) extends SyncResult
  final case class Failure(reason: String, canRetry: Boolean) extends SyncResult
  case object InProgress extends SyncResult
}

/** Adaptive sync controller with fallback logic
  *
  * Note: This class is NOT thread-safe. It should be used from a single actor or protected by external synchronization.
  */
class AdaptiveSyncController extends Logger {
  import SyncStrategy._

  @volatile private var currentStrategy: Option[SyncStrategy] = None
  @volatile private var failedStrategies: Set[SyncStrategy] = Set.empty
  @volatile private var attemptCount: Map[SyncStrategy, Int] = Map.empty.withDefaultValue(0)

  // format: off
  /** Select best sync strategy based on network conditions
    *
    * Selection logic:
    *   1. If checkpoints available and good connectivity → SnapSync
    *   2. If 3+ peers and medium connectivity → FastSync
    *   3. If 1+ peers → FullSync
    *   4. If previous failures → try next in fallback chain
    */
  // format: on
  def selectStrategy(conditions: NetworkConditions): SyncStrategy = {
    val candidates = getRemainingStrategies

    if (candidates.isEmpty) {
      log.warn("All sync strategies exhausted, resetting and trying FullSync")
      reset()
      FullSync
    } else {
      val selected = candidates.find(canAttempt(_, conditions)).getOrElse(candidates.last)
      currentStrategy = Some(selected)
      log.info(
        s"Selected sync strategy: ${selected.name} (${selected.description}) " +
          s"based on ${conditions.availablePeerCount} peers, " +
          s"checkpoints=${conditions.checkpointsAvailable}, " +
          s"failures=${conditions.previousSyncFailures}"
      )
      selected
    }
  }

  /** Check if strategy can be attempted given current conditions */
  private def canAttempt(strategy: SyncStrategy, conditions: NetworkConditions): Boolean =
    strategy match {
      case SnapSync =>
        conditions.checkpointsAvailable &&
        conditions.availablePeerCount >= strategy.requiresMinPeers &&
        !failedStrategies.contains(SnapSync) &&
        attemptCount(SnapSync) < 2

      case FastSync =>
        conditions.availablePeerCount >= strategy.requiresMinPeers &&
        !failedStrategies.contains(FastSync) &&
        attemptCount(FastSync) < 3

      case FullSync =>
        conditions.availablePeerCount >= strategy.requiresMinPeers &&
        attemptCount(FullSync) < 5 // More retries for full sync
    }

  /** Record sync result and update strategy */
  def recordResult(strategy: SyncStrategy, result: SyncResult): Option[SyncStrategy] =
    result match {
      case SyncResult.Success(blocks, duration) =>
        log.info(s"Sync strategy ${strategy.name} succeeded: $blocks blocks in ${duration}ms")
        reset()
        None

      case SyncResult.Failure(reason, canRetry) =>
        attemptCount = attemptCount.updated(strategy, attemptCount(strategy) + 1)

        if (!canRetry || attemptCount(strategy) >= getMaxAttempts(strategy)) {
          log.warn(s"Sync strategy ${strategy.name} failed (attempt ${attemptCount(strategy)}): $reason")
          failedStrategies = failedStrategies + strategy
          Some(selectFallback(strategy))
        } else {
          log.debug(s"Sync strategy ${strategy.name} failed but can retry (attempt ${attemptCount(strategy)}): $reason")
          None // Retry same strategy
        }

      case SyncResult.InProgress =>
        None // Continue with current strategy
    }

  /** Select fallback strategy after failure */
  private def selectFallback(failed: SyncStrategy): SyncStrategy = {
    val remaining = getRemainingStrategies
    if (remaining.isEmpty) {
      log.warn(s"No fallback strategy available after ${failed.name} failed, resetting to FullSync")
      reset()
      FullSync
    } else {
      val fallback = remaining.head
      log.info(s"Falling back from ${failed.name} to ${fallback.name}")
      currentStrategy = Some(fallback)
      fallback
    }
  }

  /** Get strategies that haven't been exhausted */
  private def getRemainingStrategies: List[SyncStrategy] =
    defaultFallbackChain.filterNot(failedStrategies.contains)

  /** Get maximum attempts for strategy */
  private def getMaxAttempts(strategy: SyncStrategy): Int = strategy match {
    case SnapSync => 2
    case FastSync => 3
    case FullSync => 5
  }

  /** Reset controller state (e.g., after success or timeout) */
  def reset(): Unit = {
    currentStrategy = None
    failedStrategies = Set.empty
    attemptCount = Map.empty.withDefaultValue(0)
    log.info("Adaptive sync controller reset")
  }

  /** Get current strategy */
  def getCurrentStrategy: Option[SyncStrategy] = currentStrategy

  /** Get statistics */
  def getStatistics: String =
    s"Current: ${currentStrategy.map(_.name).getOrElse("none")}, " +
      s"Failed: ${failedStrategies.map(_.name).mkString(", ")}, " +
      s"Attempts: ${attemptCount.map { case (s, a) => s"${s.name}=$a" }.mkString(", ")}"
}
