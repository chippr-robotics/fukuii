# SNAP Sync Actor-Based Concurrency Implementation Guide

## Overview

This document describes the actor-based concurrency implementation for SNAP sync in Fukuii. The implementation provides an alternative to the synchronized block approach, using Pekko actors for better concurrency and scalability.

## Architecture

### Actor Hierarchy

```
SNAPSyncController (Actor)
  ├── AccountRangeCoordinator (Actor)
  │    └── AccountRangeWorker (Actor) × concurrency
  ├── ByteCodeCoordinator (Actor)
  │    └── ByteCodeWorker (Actor) × concurrency
  ├── StorageRangeCoordinator (Actor)
  │    └── StorageRangeWorker (Actor) × concurrency
  └── TrieNodeHealingCoordinator (Actor)
       └── TrieNodeHealingWorker (Actor) × concurrency
```

### Components

#### Coordinators
Each coordinator actor manages:
- Queue of pending tasks
- Pool of worker actors
- Task distribution to workers
- Result collection
- Progress tracking
- Completion detection

#### Workers
Each worker actor:
- Receives task assignment from coordinator
- Delegates to existing downloader for request/response handling
- Reports results back to coordinator
- Can be reused for multiple tasks

### Message Flow

1. **Task Distribution**:
   ```
   SNAPSyncController --PeerAvailable--> Coordinator
   Coordinator --FetchTask--> Worker
   Worker --NetworkRequest--> Peer
   ```

2. **Response Handling**:
   ```
   Peer --Response--> SNAPSyncController
   SNAPSyncController --Broadcast--> Coordinator
   Coordinator --Broadcast--> Workers
   Worker (filters by requestId) --TaskComplete--> Coordinator
   Coordinator --PhaseComplete--> SNAPSyncController
   ```

## Configuration

### Enabling Actor-Based Concurrency

Add to your configuration file:

```hocon
fukuii.sync.snap-sync {
  use-actor-concurrency = true  # Enable actor-based mode
  account-concurrency = 16       # Number of account range workers
  storage-concurrency = 8        # Number of storage workers
  # ... other settings
}
```

### Feature Flag

The `use-actor-concurrency` flag controls which implementation is used:
- `false` (default): Uses synchronized blocks and mutable state
- `true`: Uses actor-based concurrency

## Implementation Details

### Supervision Strategy

All coordinators use `OneForOneStrategy`:
- **MaxNrOfRetries**: 3
- **WithinTimeRange**: 1 minute
- **Directive**: Restart worker on failure

This ensures fault tolerance - workers are automatically restarted on failure.

### Delegation Pattern

Workers **delegate** to existing downloader classes:
- `AccountRangeDownloader` - Account range logic, proof verification, storage
- `ByteCodeDownloader` - Bytecode download logic, hash verification
- `StorageRangeDownloader` - Storage range logic, proof verification
- `TrieNodeHealer` - Trie healing logic, node validation

This preserves all existing logic while adding actor-based coordination.

### Contract Accounts Query

At the end of account range sync, the controller queries the coordinator for contract accounts:

```scala
import org.apache.pekko.pattern.ask
import scala.concurrent.duration._

implicit val timeout = Timeout(5.seconds)
val future = accountRangeCoordinator ? GetContractAccounts
future.map {
  case ContractAccountsResponse(accounts) =>
    // Use accounts for bytecode sync
}
```

## Testing

### Unit Tests

Test each actor in isolation:
```scala
val coordinator = system.actorOf(AccountRangeCoordinator.props(...))
coordinator ! StartAccountRangeSync(stateRoot)
coordinator ! PeerAvailable(peer)
expectMsgType[TaskComplete]
```

### Integration Tests

Test full sync flow with actors enabled:
```scala
val config = SNAPSyncConfig(useActorConcurrency = true)
val controller = system.actorOf(SNAPSyncController.props(...))
controller ! Start
// Verify completion
```

## Performance Considerations

### Actor Overhead

- Actor creation: Negligible (Pekko actors are lightweight)
- Message passing: ~1 microsecond per message
- Context switching: Handled by Pekko dispatcher

### Optimization

1. **Worker pooling**: Workers are reused instead of created/destroyed
2. **Message broadcasting**: Responses are broadcast to all workers who filter by requestId
3. **Back-pressure**: Coordinators can throttle workers based on peer capacity

## Troubleshooting

### Actor System Not Starting

Check logs for:
```
AccountRangeCoordinator starting with X workers
```

If missing, verify `use-actor-concurrency = true` in config.

### Workers Not Processing Tasks

Check for:
1. Peers available: `PeerAvailable` messages being sent
2. Tasks in queue: Check coordinator logs
3. Worker failures: Look for supervision restart messages

### Performance Issues

1. Increase worker count: `account-concurrency`, `storage-concurrency`
2. Check peer latency: High latency affects all implementations
3. Monitor actor mailbox sizes: Large mailboxes indicate bottleneck

## Migration Path

### Phase 1: Initial Deployment (Current)
- Actor implementation complete
- Feature flag default: `false`
- Safe fallback to synchronized mode

### Phase 2: Testing
- Enable for select nodes
- Compare performance metrics
- Monitor for issues

### Phase 3: Gradual Rollout
- Enable for 25%, 50%, 75% of fleet
- Validate stability at each stage

### Phase 4: Full Rollout
- Enable by default: `use-actor-concurrency = true`
- Keep synchronized mode as fallback

### Phase 5: Cleanup (Future)
- Remove synchronized downloader code
- Simplify to actor-only implementation

## References

- Pekko Actors: https://pekko.apache.org/docs/pekko/current/typed/index.html
- Actor Supervision: https://pekko.apache.org/docs/pekko/current/typed/fault-tolerance.html
- Architecture Doc: `docs/architecture/SNAP_SYNC_ACTOR_CONCURRENCY.md`
