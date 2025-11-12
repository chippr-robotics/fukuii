# Sync Improvements Integration Guide

This document provides integration instructions for the sync improvements implemented based on the block sync investigation report.

## Overview

Five priority improvements have been implemented to achieve 99%+ sync success rate and <6 hour sync times:

1. **Enhanced Peer Selection** - Peer scoring system
2. **Adaptive Sync Strategy** - Fallback chain (SnapSync → FastSync → FullSync)
3. **Exponential Backoff Retry** - Progressive retry delays
4. **Checkpoint Update Mechanism** - Multi-source checkpoint verification
5. **Bootstrap Node Mode** - Dedicated mode for serving genesis nodes

## Priority 1: Enhanced Peer Selection

### Components

- `PeerScore.scala` - Scoring algorithm (0.0-1.0 scale)
- `PeerScoringManager.scala` - Thread-safe score management

### Integration Points

#### 1. In `PeersClient.scala`

Add peer scoring manager:

```scala
class PeersClient(
    // ... existing parameters
    scoringManager: PeerScoringManager  // Add this
) {
  
  // Update selectPeer to use scores
  private def selectPeer(selector: PeerSelector): Option[Peer] = {
    val candidates = peersToDownloadFrom
    if (candidates.isEmpty) None
    else {
      // Use scoring to select best peer
      val bestPeers = scoringManager.getBestPeersExcluding(
        count = 5,
        blacklisted = blacklist.keys
      )
      
      val scored = candidates.filter(p => bestPeers.contains(p.id))
      if (scored.nonEmpty) scored.headOption
      else candidates.headOption // Fallback to any available peer
    }
  }
  
  // Record events
  private def handleResponse(...) = {
    // ... existing code
    scoringManager.recordResponse(peer.id, bytesReceived, latencyMs)
  }
  
  override def blacklistIfHandshaked(...) = {
    // ... existing code
    scoringManager.recordBlacklist(peerId)
  }
}
```

#### 2. In `PeerActor.scala`

Track handshake results:

```scala
class PeerActor(
    // ... existing parameters
    scoringManager: PeerScoringManager  // Add this
) {
  
  private def handleHandshakeSuccess(...) = {
    // ... existing code
    scoringManager.recordSuccessfulHandshake(peer.id)
  }
  
  private def handleHandshakeFailure(...) = {
    // ... existing code
    scoringManager.recordFailedHandshake(peer.id)
  }
}
```

### Configuration

No configuration changes required. Scoring is automatic based on peer behavior.

### Testing

```bash
# Run tests to verify scoring logic
sbt "testOnly *PeerScoringManagerSpec"
sbt "testOnly *PeerScoreSpec"

# Monitor scores in logs (set log level to DEBUG)
grep "Peer .* score" logs/fukuii.log | tail -20
```

## Priority 2: Adaptive Sync Strategy

### Components

- `AdaptiveSyncStrategy.scala` - Strategy selection and fallback logic

### Integration Points

#### In `SyncController.scala`

Replace fixed sync strategy with adaptive approach:

```scala
class SyncController(
    // ... existing parameters
) {
  
  private val adaptiveController = new AdaptiveSyncController()
  
  override def start(): Unit = {
    // Evaluate network conditions
    val conditions = NetworkConditions(
      availablePeerCount = countAvailablePeers(),
      checkpointsAvailable = hasBootstrapCheckpoints(),
      previousSyncFailures = getSyncFailureCount(),
      averagePeerLatencyMs = calculateAveragePeerLatency()
    )
    
    // Select strategy
    val strategy = adaptiveController.selectStrategy(conditions)
    
    strategy match {
      case SyncStrategy.SnapSync => startSnapSync()  // Use bootstrap checkpoints
      case SyncStrategy.FastSync => startFastSync()  // Existing fast sync
      case SyncStrategy.FullSync => startRegularSync() // Existing full sync
    }
  }
  
  private def handleSyncResult(result: SyncResult): Unit = {
    adaptiveController.recordResult(currentStrategy, result) match {
      case Some(fallbackStrategy) =>
        log.info(s"Falling back to ${fallbackStrategy.name}")
        startSyncWithStrategy(fallbackStrategy)
      case None if result.isFailure =>
        log.info("Retrying same strategy")
        scheduleRetry()
      case None =>
        // Success or in progress, continue
    }
  }
}
```

### Configuration

Add to `application.conf`:

```hocon
fukuii.sync {
  # Enable adaptive sync strategy
  adaptive-sync {
    enabled = true
    
    # Maximum attempts per strategy
    max-snap-sync-attempts = 2
    max-fast-sync-attempts = 3
    max-full-sync-attempts = 5
    
    # Minimum peer requirements (override defaults)
    snap-sync-min-peers = 3
    fast-sync-min-peers = 3
    full-sync-min-peers = 1
  }
}
```

### Testing

```bash
# Test adaptive strategy selection
sbt "testOnly *AdaptiveSyncControllerSpec"

# Simulate network conditions
# Test with 0 peers (should select FullSync if any peer appears)
# Test with 1-2 peers (should try FastSync but may fallback to FullSync)
# Test with 3+ peers and checkpoints (should try SnapSync first)
```

## Priority 3: Exponential Backoff Retry

### Components

- `RetryStrategy.scala` - Configurable backoff calculator
- `RetryState` - Per-operation retry tracker

### Integration Points

#### In `PeersClient.scala`

Replace fixed retry delays:

```scala
class PeersClient(
    // ... existing parameters
) {
  
  private val retryStrategy = RetryStrategy.default
  private val retryStates = mutable.Map[String, RetryState]()
  
  private def scheduleRetry(operationId: String): Unit = {
    val state = retryStates.getOrElseUpdate(operationId, RetryState())
    val delay = state.nextDelay
    
    log.debug(s"Scheduling retry for $operationId after $delay (attempt ${state.attempt})")
    
    scheduler.scheduleOnce(delay, self, RetryOperation(operationId))
    retryStates(operationId) = state.recordAttempt
  }
  
  private def handleSuccess(operationId: String): Unit = {
    // Reset retry state on success
    retryStates.remove(operationId)
  }
}
```

#### In `FastSync.scala` and `RegularSync.scala`

Update retry logic:

```scala
// Replace:
scheduler.scheduleOnce(500.millis, self, RetryFetch)

// With:
val delay = retryStrategy.nextDelay(retryAttempt)
scheduler.scheduleOnce(delay, self, RetryFetch)
```

### Configuration

Add to `application.conf`:

```hocon
fukuii.sync {
  retry-strategy {
    # Initial delay for first retry
    initial-delay = 500 milliseconds
    
    # Maximum delay cap
    max-delay = 30 seconds
    
    # Exponential multiplier
    multiplier = 2.0
    
    # Jitter factor (0.0-1.0)
    jitter-factor = 0.2
  }
}
```

### Presets

```scala
// Fast retry for low-latency operations
RetryStrategy.fast  // 100ms initial, 5s max, 1.5x

// Default for most operations
RetryStrategy.default  // 500ms initial, 30s max, 2.0x

// Slow retry for resource-intensive operations
RetryStrategy.slow  // 1s initial, 60s max, 2.5x

// Conservative with high jitter for load distribution
RetryStrategy.conservative  // 2s initial, 120s max, 3.0x, 50% jitter
```

### Testing

```bash
# Test retry strategy calculation
sbt "testOnly *RetryStrategySpec"

# Monitor retry delays in logs
grep "Scheduling retry" logs/fukuii.log | tail -20

# Verify exponential growth
# Attempt 0: ~500ms
# Attempt 1: ~1000ms
# Attempt 2: ~2000ms
# Attempt 3: ~4000ms
# ...
```

## Priority 4: Checkpoint Update Mechanism

### Components

- `CheckpointUpdateService.scala` - Multi-source fetching and verification

### Integration Points

#### In `BootstrapCheckpointLoader.scala`

Add checkpoint update capability:

```scala
class BootstrapCheckpointLoader(
    updateService: CheckpointUpdateService  // Add this
) {
  
  def loadAndUpdate(): Unit = {
    // Load existing checkpoints
    val existing = loadBootstrapCheckpoints()
    
    // Check configuration flag before auto-updating
    val autoUpdate = config.getBoolean("fukuii.checkpoints.auto-update")
    if (autoUpdate) {
      log.info("Auto-update enabled, fetching latest checkpoints")
      updateService.fetchLatestCheckpoints(
        sources = CheckpointUpdateService.defaultEtcSources,
        quorumSize = 2
      ).onComplete {
        case Success(verified) =>
          log.info(s"Fetched ${verified.size} verified checkpoints")
          if (verified.nonEmpty) {
            updateService.updateConfiguration(verified)
          } else {
            log.warn("No verified checkpoints received, using existing checkpoints")
          }
        case Failure(ex) =>
          log.warn(s"Failed to fetch latest checkpoints: ${ex.getMessage}")
      }
    } else {
      log.debug("Auto-update disabled, using static checkpoints")
    }
  }
}
```

### Configuration

Add to `application.conf`:

```hocon
fukuii.checkpoints {
  # Automatically fetch latest checkpoints on startup
  auto-update = false  # Disabled by default for security
  
  # Checkpoint sources
  sources = [
    {
      name = "Official ETC"
      url = "https://checkpoints.ethereumclassic.org/mainnet.json"
      priority = 1
    },
    {
      name = "BlockScout"
      url = "https://blockscout.com/etc/mainnet/api/checkpoints"
      priority = 2
    }
  ]
  
  # Quorum configuration
  min-sources-for-quorum = 2
  update-interval = 24 hours
}
```

### Security Considerations

1. **Disabled by default**: Auto-update is disabled by default (see configuration above)
2. **Configuration check required**: Implementers MUST check the `auto-update` flag before calling `fetchLatestCheckpoints`
3. **Quorum required**: At least 2 sources must agree on checkpoint hash
4. **HTTPS only**: All checkpoint sources must use HTTPS
5. **Timeouts configured**: HTTP requests have 10s connect timeout, 30s idle timeout
6. **Placeholder JSON parsing**: Current implementation returns empty sequences - integrate proper JSON library before production use
7. **Cryptographic verification**: Consider adding signature verification in production
8. **Manual override**: Operators can always manually specify checkpoints

### Testing

```bash
# Test checkpoint fetching
sbt "testOnly *CheckpointUpdateServiceSpec"

# Test with mock sources
# Test quorum logic (2/3 agree, 1/3 disagrees)
# Test network failures
# Test malformed responses
```

## Priority 5: Bootstrap Node Mode

### Configuration

Use the provided `bootstrap-node.conf` configuration file.

### Deployment

#### 1. Standalone Bootstrap Node

```bash
# Start with bootstrap configuration
./bin/fukuii -Dconfig.file=bootstrap-node.conf etc

# Or use environment variable
export FUKUII_OPTS="-Dconfig.file=bootstrap-node.conf"
./bin/fukuii etc
```

#### 2. Docker Deployment

```dockerfile
FROM ghcr.io/chippr-robotics/chordodes_fukuii:latest

# Copy bootstrap configuration
COPY bootstrap-node.conf /app/conf/

# Set configuration
ENV FUKUII_OPTS="-Dconfig.file=/app/conf/bootstrap-node.conf"

EXPOSE 9076 30303/udp 8546

CMD ["bin/fukuii", "etc"]
```

#### 3. Systemd Service

```ini
[Unit]
Description=Fukuii Bootstrap Node
After=network.target

[Service]
Type=simple
User=fukuii
Group=fukuii
Environment="FUKUII_OPTS=-Dconfig.file=/etc/fukuii/bootstrap-node.conf"
ExecStart=/opt/fukuii/bin/fukuii etc
Restart=always
RestartSec=10

[Install]
WantedBy=multi-user.target
```

### Monitoring

Bootstrap nodes should be monitored for:

- **Connection count**: `grep "genesis node" logs/fukuii.log | wc -l`
- **Blocks served**: Check metrics endpoint
- **Bandwidth usage**: Monitor network I/O
- **Peer churn**: Track connection/disconnection rate

### Resource Requirements

- **CPU**: 2-4 cores
- **RAM**: 4-8 GB
- **Disk**: 500 GB (full blockchain)
- **Network**: Stable connection, >10 Mbps upload

## Testing Strategy

### Unit Tests

Create test files for each component:

```bash
src/test/scala/com/chipprbots/ethereum/network/PeerScoreSpec.scala
src/test/scala/com/chipprbots/ethereum/network/PeerScoringManagerSpec.scala
src/test/scala/com/chipprbots/ethereum/blockchain/sync/RetryStrategySpec.scala
src/test/scala/com/chipprbots/ethereum/blockchain/sync/AdaptiveSyncStrategySpec.scala
src/test/scala/com/chipprbots/ethereum/blockchain/data/CheckpointUpdateServiceSpec.scala
```

### Integration Tests

Test complete sync workflows:

```bash
# Test sync with good network conditions (many peers)
sbt "IntegrationTest / testOnly *SyncWithManyPeersSpec"

# Test sync with poor network conditions (few peers)
sbt "IntegrationTest / testOnly *SyncWithFewPeersSpec"

# Test sync with peer failures
sbt "IntegrationTest / testOnly *SyncWithPeerFailuresSpec"

# Test adaptive fallback
sbt "IntegrationTest / testOnly *AdaptiveSyncFallbackSpec"
```

### Network Tests

Test on real networks:

```bash
# Test on Mordor testnet (faster)
./bin/fukuii mordor --log-level=DEBUG

# Monitor sync progress
watch -n 5 'curl -s -X POST --data "{\"jsonrpc\":\"2.0\",\"method\":\"eth_syncing\",\"params\":[],\"id\":1}" http://localhost:8546 | jq .'

# Check peer scores
grep "Peer .* score" logs/fukuii.log | tail -20

# Verify adaptive strategy selection
grep "Selected sync strategy" logs/fukuii.log
```

## Rollout Plan

### Phase 1: Testing (1-2 weeks)
1. Deploy to Mordor testnet
2. Monitor for 48 hours
3. Collect metrics and logs
4. Fix any issues

### Phase 2: Limited Rollout (1 week)
1. Deploy to 10% of mainnet nodes
2. Monitor sync success rate
3. Compare with control group
4. Adjust parameters as needed

### Phase 3: Full Rollout (1 week)
1. Deploy to all mainnet nodes
2. Monitor overall network health
3. Document lessons learned
4. Update operational runbooks

## Metrics and Success Criteria

### Key Metrics

- **Sync Success Rate**: Target 99%+ (currently ~95%)
- **Average Sync Time**: Target <6 hours (currently 8-12 hours)
- **Peer Connection Stability**: Target 95%+ (currently ~80%)
- **Network Bandwidth**: Target 20% reduction
- **Failed Handshake Rate**: Target <5% (currently ~15%)

### Monitoring Queries

```bash
# Sync success rate
grep "Sync completed successfully" logs/*.log | wc -l

# Average sync time
grep -A 1 "Starting sync" logs/*.log | grep "duration" | awk '{sum+=$NF; count++} END {print sum/count}'

# Peer score distribution
grep "Peer .* score" logs/fukuii.log | awk '{print $NF}' | sort -n | uniq -c

# Retry strategy usage
grep "Scheduling retry.*attempt" logs/fukuii.log | awk '{print $NF}' | sort | uniq -c
```

## Troubleshooting

### Issue: Peer scores not updating

**Solution**: Verify scoring manager is properly integrated in PeersClient and PeerActor. Check for score update log messages.

### Issue: Adaptive sync not falling back

**Solution**: Check network condition evaluation. Verify peer count and checkpoint availability. Review adaptive controller state in logs.

### Issue: Retry delays too long/short

**Solution**: Adjust RetryStrategy parameters in configuration. Use appropriate preset (fast/default/slow/conservative) for the operation type.

### Issue: Checkpoint update fails

**Solution**: Verify network connectivity to checkpoint sources. Check quorum configuration. Ensure HTTPS certificates are valid.

### Issue: Bootstrap node not accepting genesis nodes

**Solution**: Verify `serve-genesis-nodes = true` in configuration. Check firewall rules for ports 9076 and 30303.

## References

- [Block Sync Investigation Report](BLOCK_SYNC_INVESTIGATION_REPORT.md)
- [ADR-011: RLPx Protocol Deviations](adr/011-rlpx-protocol-deviations-and-peer-bootstrap.md)
- [ADR-012: Bootstrap Checkpoints](adr/012-bootstrap-checkpoints.md)
- [Known Issues Runbook](runbooks/known-issues.md)
- [Peering Runbook](runbooks/peering.md)
