# SNAP Sync Error Handling and Progress Monitoring

## Overview

This document describes the error handling and progress monitoring implementation for Fukuii's SNAP sync protocol. These systems ensure robust, resilient synchronization with comprehensive observability.

## Error Handling Architecture

### Components

#### 1. SNAPErrorHandler

**Location:** `src/main/scala/com/chipprbots/ethereum/blockchain/sync/snap/SNAPErrorHandler.scala`

The error handler provides:
- Retry logic with exponential backoff
- Circuit breaker pattern for failing tasks
- Peer performance tracking and blacklisting
- Comprehensive error statistics

**Configuration:**
```scala
val errorHandler = new SNAPErrorHandler(
  maxRetries = 3,              // Maximum retry attempts per task
  initialBackoff = 1.second,   // Initial backoff duration
  maxBackoff = 60.seconds,     // Maximum backoff duration
  circuitBreakerThreshold = 10 // Failures before circuit opens
)
```

### Retry Logic

**Exponential Backoff:**
```
Attempt 1: 1 second
Attempt 2: 2 seconds
Attempt 3: 4 seconds
Attempt 4: 8 seconds
...
Max: 60 seconds
```

**Usage Example:**
```scala
val taskId = s"account_range_${requestId}"
val retryState = errorHandler.recordRetry(taskId, errorMessage)

if (errorHandler.shouldRetry(taskId)) {
  // Schedule retry
  if (errorHandler.isRetryReady(taskId)) {
    retryTask(taskId)
  }
} else {
  // Max retries exceeded
  log.error(s"Task $taskId failed after ${maxRetries} attempts")
}
```

### Circuit Breaker Pattern

Prevents repeatedly attempting operations that consistently fail:

**States:**
- **Closed**: Normal operation (default)
- **Open**: Circuit tripped, blocking operations

**Behavior:**
```scala
errorHandler.recordCircuitBreakerFailure("account_range_download")

if (errorHandler.isCircuitOpen("account_range_download")) {
  log.error("Circuit breaker is OPEN for account range downloads")
  // Skip this operation type until circuit closes
} else {
  // Proceed with operation
  downloadAccountRange()
}
```

**Recovery:**
```scala
// On successful operation
errorHandler.recordCircuitBreakerSuccess("account_range_download")
// Circuit resets to Closed state
```

### Peer Failure Tracking

Tracks peer behavior to identify and blacklist problematic peers:

**Error Types:**
- `timeout` - Request timed out
- `invalid_proof` - Merkle proof verification failed
- `malformed_response` - Response doesn't match expected format
- `storage_error` - Database/storage operation failed
- `network_error` - Network communication error
- `proof_verification_failed` - Proof validation error
- `state_root_mismatch` - State root doesn't match expected
- `peer_disconnected` - Peer disconnected during operation

**Recording Failures:**
```scala
errorHandler.recordPeerFailure(
  peerId = "peer-123",
  errorType = SNAPErrorHandler.ErrorType.InvalidProof,
  context = "Failed to verify account range proof"
)
```

**Blacklist Criteria:**
- **10+ total failures** from any peer
- **3+ invalid proof errors** (indicates malicious/broken peer)
- **5+ malformed response errors** (indicates incompatible peer)

**Checking for Blacklist:**
```scala
if (errorHandler.shouldBlacklistPeer(peerId)) {
  log.error(s"Blacklisting peer $peerId due to repeated failures")
  blacklist.add(peerId)
}
```

**Peer Forgiveness:**
On successful responses, peer failure count is reduced:
```scala
errorHandler.recordPeerSuccess(peerId)
// Reduces total failure count by 1 (exponential forgiveness)
```

### Error Context

Creates formatted error context for logging:

```scala
val context = errorHandler.createErrorContext(
  phase = "AccountRangeSync",
  peerId = Some("peer-123"),
  requestId = Some(BigInt(42)),
  taskId = Some("account_range_42")
)
// Output: "phase=AccountRangeSync, peer=peer-123, requestId=42, taskId=account_range_42"
```

### Statistics

**Retry Statistics:**
```scala
val stats = errorHandler.getRetryStatistics
// RetryStatistics(
//   totalTasksTracked = 150,
//   tasksWithRetries = 45,
//   totalRetryAttempts = 78,
//   tasksAtMaxRetries = 5
// )
```

**Peer Statistics:**
```scala
val peerStats = errorHandler.getPeerStatistics
// PeerStatistics(
//   totalPeersTracked = 20,
//   totalFailuresRecorded = 100,
//   peersRecommendedForBlacklist = 3
// )
```

## Progress Monitoring Architecture

### Components

#### 1. SyncProgressMonitor

**Location:** `src/main/scala/com/chipprbots/ethereum/blockchain/sync/snap/SNAPSyncController.scala` (lines 933+)

Enhanced progress monitor with:
- Periodic logging (configurable interval)
- ETA calculations based on recent throughput
- Dual throughput metrics (overall vs recent)
- Phase progress tracking
- Metrics history for rate calculations

**Features:**

**Periodic Logging:**
```scala
progressMonitor.startPeriodicLogging()
// Logs progress every 30 seconds

progressMonitor.stopPeriodicLogging()
// Stops periodic logging
```

**Phase Transitions:**
```scala
progressMonitor.startPhase(ByteCodeSync)
// Output: "📊 SNAP Sync phase transition: AccountRangeSync → ByteCodeSync"
```

**Progress Updates:**
```scala
progressMonitor.incrementAccountsSynced(1000)
progressMonitor.incrementBytecodesDownloaded(500)
progressMonitor.incrementStorageSlotsSynced(10000)
progressMonitor.incrementNodesHealed(250)
```

**ETA Calculation:**
```scala
val eta = progressMonitor.calculateETA
// Some(3600) - 3600 seconds remaining (1 hour)

// ETA is based on:
// - Current phase
// - Estimated total items
// - Recent throughput (60-second window)
```

**Manual Progress Logging:**
```scala
progressMonitor.logProgress()
// Output: "📈 SNAP Sync Progress: phase=AccountRange (45%), accounts=450000@7500/s, ETA: 1h 30m"
```

**Progress Retrieval:**
```scala
val progress = progressMonitor.currentProgress
// SyncProgress(
//   phase = AccountRangeSync,
//   accountsSynced = 450000,
//   recentAccountsPerSec = 7500.0,
//   phaseProgress = 45,
//   estimatedTotalAccounts = 1000000,
//   ...
// )
```

### Metrics History

Progress monitor maintains rolling window (60 seconds) of metrics for accurate rate calculations:

```scala
// Internal structure (simplified)
private val accountsHistory = Queue[(timestamp, count)]()
// Keeps last 60 seconds of data points

// Calculate recent throughput
val recentRate = calculateRecentThroughput(accountsHistory)
// Returns items/second based on recent data
```

**Benefits:**
- Accurate real-time rate calculations
- Smooth out temporary spikes/drops
- Better ETA estimates
- Adaptive to changing network conditions

### Progress Data Structure

```scala
case class SyncProgress(
  phase: SyncPhase,
  accountsSynced: Long,
  bytecodesDownloaded: Long,
  storageSlotsSynced: Long,
  nodesHealed: Long,
  elapsedSeconds: Double,
  phaseElapsedSeconds: Double,
  accountsPerSec: Double,           // Overall rate
  bytecodesPerSec: Double,
  slotsPerSec: Double,
  nodesPerSec: Double,
  recentAccountsPerSec: Double,     // Recent 60s rate
  recentBytecodesPerSec: Double,
  recentSlotsPerSec: Double,
  recentNodesPerSec: Double,
  phaseProgress: Int,               // 0-100 percentage
  estimatedTotalAccounts: Long,
  estimatedTotalBytecodes: Long,
  estimatedTotalSlots: Long
)
```

### Formatted Output

Progress has intelligent formatting based on current phase:

```scala
progress.formattedString
// AccountRange: "phase=AccountRange (45%), accounts=450000@7500/s, elapsed=60s"
// ByteCode: "phase=ByteCode (30%), codes=15000@250/s, elapsed=120s"
// Storage: "phase=Storage (60%), slots=6000000@100000/s, elapsed=180s"
// Healing: "phase=Healing, nodes=5000@833/s, elapsed=240s"
```

## Integration Examples

### SNAPSyncController Integration

**Error Handling in Response Handlers:**
```scala
case msg: AccountRange =>
  val taskId = s"account_range_${msg.requestId}"
  val peerId = requestTracker.getPendingRequest(msg.requestId)
    .map(_.peer.id).getOrElse("unknown")
  
  accountRangeDownloader.foreach { downloader =>
    downloader.handleResponse(msg) match {
      case Right(count) =>
        // Success path
        progressMonitor.incrementAccountsSynced(count)
        errorHandler.resetRetries(taskId)
        errorHandler.recordPeerSuccess(peerId)
        errorHandler.recordCircuitBreakerSuccess("account_range_download")
        
      case Left(error) =>
        // Error path
        val context = errorHandler.createErrorContext(
          phase = "AccountRangeSync",
          peerId = Some(peerId),
          requestId = Some(msg.requestId),
          taskId = Some(taskId)
        )
        log.warning(s"Failed to process AccountRange: $error ($context)")
        
        // Classify error
        val errorType = if (error.contains("proof")) {
          SNAPErrorHandler.ErrorType.InvalidProof
        } else if (error.contains("malformed")) {
          SNAPErrorHandler.ErrorType.MalformedResponse
        } else {
          "processing_error"
        }
        
        // Record failure
        errorHandler.recordPeerFailure(peerId, errorType, error)
        errorHandler.recordRetry(taskId, error)
        errorHandler.recordCircuitBreakerFailure("account_range_download")
        
        // Check for blacklist
        if (errorHandler.shouldBlacklistPeer(peerId)) {
          blacklist.add(peerId)
        }
    }
  }
```

**Progress Monitoring Setup:**
```scala
override def preStart(): Unit = {
  progressMonitor.startPeriodicLogging()
}

override def postStop(): Unit = {
  progressMonitor.stopPeriodicLogging()
  
  // Log final statistics
  val retryStats = errorHandler.getRetryStatistics
  val peerStats = errorHandler.getPeerStatistics
  log.info(s"SNAP Sync error statistics: " +
    s"retries=${retryStats.totalRetryAttempts}, " +
    s"failed_tasks=${retryStats.tasksAtMaxRetries}, " +
    s"peer_failures=${peerStats.totalFailuresRecorded}")
}
```

## Observability

### Terminal UI

SNAP sync progress is displayed in the terminal UI when active:

```
┌─────────────────────────────────────────────┐
│              SNAP SYNC                      │
├─────────────────────────────────────────────┤
│ Phase: 📦 Downloading accounts              │
│ AccountRange Progress: [████████░░] 45%    │
│ Overall Progress: [███░░░░░░░] 25%         │
│ Accounts: 450,000 @ 7,500/s                │
│ Current Rate: 7500 Accounts/sec            │
│ ETA: 1h 30m                                 │
│ Elapsed: 60s                                │
└─────────────────────────────────────────────┘
```

### Grafana Dashboard

Comprehensive dashboard available at `ops/grafana/fukuii-snap-sync-dashboard.json`

**Sections:**
1. **Overview**: Phase, progress, ETA, elapsed time
2. **Account Range Sync**: Accounts synced, sync rates
3. **ByteCode & Storage**: Downloads and rates over time
4. **State Healing**: Nodes healed, healing rates
5. **Error Handling**: Retries, failures, peer performance

**Access:** Import the JSON file into Grafana or access via the ops dashboard.

### Metrics for Prometheus

Required metrics to expose (example with Kamon/Micrometer):

```scala
// Phase gauge (0=Idle, 1=AccountRange, 2=ByteCode, 3=Storage, 4=Healing, 5=Validation, 6=Completed)
metrics.gauge("snapsync.current.phase", () => currentPhaseValue)

// Progress gauges
metrics.gauge("snapsync.overall.progress.percent", () => overallProgress)
metrics.gauge("snapsync.eta.seconds", () => etaSeconds)
metrics.gauge("snapsync.elapsed.seconds", () => elapsedSeconds)

// Counters
metrics.counter("snapsync.accounts.synced.total").increment(count)
metrics.counter("snapsync.bytecodes.downloaded.total").increment(count)
metrics.counter("snapsync.storage.slots.synced.total").increment(count)
metrics.counter("snapsync.nodes.healed.total").increment(count)

// Rate gauges
metrics.gauge("snapsync.accounts.per.sec", () => recentAccountsPerSec)

// Error metrics
metrics.counter("snapsync.retries.total").increment()
metrics.counter("snapsync.failures.total").increment()
metrics.counter("snapsync.peer.failures.total").increment()
metrics.counter("snapsync.peers.blacklisted.total").increment()
```

## Best Practices

### Error Handling

1. **Always provide context**: Use `createErrorContext` for consistent logging
2. **Classify errors**: Use standardized error types from `SNAPErrorHandler.ErrorType`
3. **Record all failures**: Track both task retries and peer failures
4. **Reset on success**: Call `resetRetries` and `recordPeerSuccess` for successful operations
5. **Check circuit breakers**: Before starting expensive operations, check if circuit is open

### Progress Monitoring

1. **Update frequently**: Increment counters immediately when work completes
2. **Provide estimates**: Update `estimatedTotal*` values when known
3. **Log phase transitions**: Always call `startPhase` when changing phases
4. **Use periodic logging**: Let the monitor handle regular progress updates
5. **Format consistently**: Use `formattedString` for human-readable output

### Performance

1. **Batch updates**: Don't call increment for every single item (batch 100-1000)
2. **Cleanup history**: Monitor automatically cleans old metrics data
3. **Avoid synchronization overhead**: Keep synchronized blocks minimal
4. **Circuit breaker optimization**: Check once before batch operations, not per-item

## Troubleshooting

### High Retry Rates

**Symptom:** Many tasks require retries

**Diagnosis:**
```scala
val stats = errorHandler.getRetryStatistics
if (stats.tasksWithRetries.toDouble / stats.totalTasksTracked > 0.5) {
  // More than 50% of tasks are retrying
}
```

**Possible Causes:**
- Network instability
- Peer quality issues
- Timeout values too aggressive
- Circuit breaker threshold too low

### Peer Blacklisting Issues

**Symptom:** Too many peers blacklisted

**Diagnosis:**
```scala
val peerStats = errorHandler.getPeerStatistics
if (peerStats.peersRecommendedForBlacklist > peerStats.totalPeersTracked * 0.3) {
  // More than 30% of peers recommended for blacklist
}
```

**Possible Causes:**
- Overly strict blacklist criteria
- Network-wide issue (not peer-specific)
- Incompatible peer software versions
- Aggressive timeout settings

### Slow Progress

**Symptom:** Low throughput rates

**Diagnosis:**
```scala
val progress = progressMonitor.currentProgress
if (progress.recentAccountsPerSec < 100) {
  // Account sync slower than 100/s
}
```

**Possible Causes:**
- Insufficient SNAP-capable peers
- Network bandwidth limitations
- Database I/O bottleneck
- CPU constraints during proof verification

## Future Enhancements

Potential improvements for consideration:

1. **Adaptive backoff**: Adjust backoff based on error type
2. **Peer reputation scoring**: More sophisticated than simple failure count
3. **Circuit breaker auto-recovery**: Automatic circuit reset after timeout
4. **Progress prediction**: Machine learning for better ETA estimates
5. **Anomaly detection**: Alert on unusual patterns in errors/progress
6. **Distributed tracing**: Integration with OpenTelemetry for request tracing

## References

- [SNAP Sync TODO](./SNAP_SYNC_TODO.md)
- [SNAP Sync Status](./SNAP_SYNC_STATUS.md)
- [SNAP Sync Implementation](./SNAP_SYNC_IMPLEMENTATION.md)
- [Circuit Breaker Pattern](https://martinfowler.com/bliki/CircuitBreaker.html)
- [Exponential Backoff](https://en.wikipedia.org/wiki/Exponential_backoff)

---

**Last Updated:** 2025-12-02  
**Status:** Production Ready ✅  
**Version:** 1.0
