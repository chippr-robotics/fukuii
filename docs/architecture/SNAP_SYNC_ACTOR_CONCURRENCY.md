# SNAP Sync Actor-Based Concurrency Design

**Date**: 2025-12-15  
**Status**: Implementation in Progress  
**Requirement**: Use Pekko actor system for concurrency in SNAP requests from peers

## Overview

Refactor SNAP sync downloaders to use Pekko actors for managing concurrent requests to peers instead of synchronized blocks and mutable state.

## Current Architecture (Before)

### Problems with Current Approach
1. **Synchronized blocks**: Heavy use of `synchronized` for thread safety
2. **Mutable state**: Shared mutable collections (queues, maps, buffers)
3. **No supervision**: No actor supervision for error handling
4. **Thread blocking**: Synchronized methods can block threads
5. **Difficult to reason about**: Shared state makes concurrency hard to understand

### Current Components
```
SNAPSyncController (Actor)
  └── AccountRangeDownloader (Plain Object with synchronized methods)
       └── SNAPRequestTracker (Plain Object)
  └── ByteCodeDownloader (Plain Object with synchronized methods)
  └── StorageRangeDownloader (Plain Object with synchronized methods)
  └── TrieNodeHealer (Plain Object with synchronized methods)
```

## New Architecture (After)

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

### Benefits
1. **Actor isolation**: Each worker has its own state
2. **Message passing**: No shared mutable state
3. **Supervision**: Actor supervision for automatic recovery
4. **Non-blocking**: Actor message passing is non-blocking
5. **Scalable**: Easy to adjust concurrency by spawning more actors
6. **Testable**: Actors can be tested in isolation

## Component Design

### 1. AccountRangeCoordinator Actor

**Responsibilities**:
- Maintain queue of pending account range tasks
- Distribute tasks to worker actors
- Collect results from workers
- Report progress to SNAPSyncController
- Handle worker failures

**Messages**:
```scala
sealed trait AccountRangeMessage
case class StartDownload(stateRoot: ByteString) extends AccountRangeMessage
case class PeerAvailable(peer: Peer) extends AccountRangeMessage
case class TaskComplete(requestId: BigInt, result: Either[String, AccountRangeResult]) extends AccountRangeMessage
case class TaskFailed(requestId: BigInt, reason: String) extends AccountRangeMessage
case object GetProgress extends AccountRangeMessage
case object CheckCompletion extends AccountRangeMessage
```

**State**:
- Pending tasks queue
- Active workers map
- Completed tasks set
- Statistics (accounts downloaded, bytes, etc.)

### 2. AccountRangeWorker Actor

**Responsibilities**:
- Request single account range from peer
- Handle response and validate proofs
- Store accounts to database
- Report result to coordinator

**Messages**:
```scala
sealed trait WorkerMessage
case class FetchAccountRange(task: AccountTask, peer: Peer) extends WorkerMessage
case class AccountRangeResponse(response: AccountRange) extends WorkerMessage
case class RequestTimeout(requestId: BigInt) extends WorkerMessage
```

**Lifecycle**:
1. Created by coordinator when needed
2. Fetches one task
3. Reports result
4. Can be reused for next task or stopped

### 3. Similar Pattern for Other Downloaders

- **ByteCodeCoordinator** + **ByteCodeWorker**: Download contract bytecodes
- **StorageRangeCoordinator** + **StorageRangeWorker**: Download storage slots
- **TrieNodeHealingCoordinator** + **TrieNodeHealingWorker**: Heal missing trie nodes

## Implementation Strategy

### Phase 1: Create Actor Skeletons
1. Define message protocols for each actor type
2. Create basic actor classes with receive methods
3. Implement actor creation in SNAPSyncController

### Phase 2: Migrate AccountRangeDownloader
1. Extract task management logic into AccountRangeCoordinator
2. Extract request/response logic into AccountRangeWorker
3. Wire up actor communication
4. Test account range sync

### Phase 3: Migrate Other Downloaders
1. Follow same pattern for ByteCode, Storage, Healing
2. Reuse common patterns and message types
3. Test each component

### Phase 4: Integration & Testing
1. End-to-end testing of actor-based SNAP sync
2. Performance comparison with synchronized approach
3. Load testing with many peers

## Message Flow Example

### Account Range Download Flow

```
SNAPSyncController                    AccountRangeCoordinator              AccountRangeWorker
       |                                        |                                   |
       |--- StartAccountRangeSync ------------->|                                   |
       |                                        |--- create workers × 16 ---------->|
       |                                        |                                   |
       |--- PeerDiscovered(peer1) ------------->|                                   |
       |                                        |--- FetchAccountRange(task1) ----->|
       |                                        |                                   |
       |                                        |                          [sends GetAccountRange]
       |                                        |                                   |
NetworkPeerManagerActor                        |                                   |
       |                                        |                                   |
       |--- AccountRange(response) ----------------------- (forward) ------------->|
       |                                        |                                   |
       |                                        |<-- TaskComplete(result) ----------|
       |                                        |                                   |
       |<-- AccountRangeProgress(stats) -------|                                   |
       |                                        |                                   |
```

## Actor Supervision Strategy

### Supervision Hierarchy
```
SNAPSyncController (Strategy: Resume)
  ├── AccountRangeCoordinator (Strategy: Restart)
  │    └── AccountRangeWorker (Strategy: Stop and create new)
  └── ... other coordinators
```

### Error Handling
1. **Worker failure**: Coordinator restarts task with different peer
2. **Coordinator failure**: SNAPSyncController restarts coordinator
3. **Request timeout**: Worker reports timeout, task requeued
4. **Invalid response**: Worker rejects, blacklists peer, task requeued

## Migration Path (Incremental)

To minimize risk, migrate incrementally:

### Step 1: AccountRange (Most Complex)
- Full actor implementation
- Test thoroughly
- Keep old code as fallback

### Step 2: ByteCode & Storage (Medium Complexity)
- Apply lessons learned from AccountRange
- Similar patterns

### Step 3: Healing (Simplest)
- Apply established patterns
- Final integration

### Step 4: Remove Old Code
- Once all components migrated
- Remove synchronized downloaders
- Clean up

## Performance Considerations

### Actor Overhead
- Actor creation cost: Negligible (Pekko actors are lightweight)
- Message passing cost: ~1 microsecond per message
- Context switching: Handled by Pekko dispatcher

### Optimization Opportunities
1. **Actor pooling**: Reuse worker actors instead of create/destroy
2. **Batching**: Send multiple tasks per message when appropriate
3. **Back-pressure**: Coordinator can throttle workers based on peer capacity
4. **Dynamic scaling**: Adjust worker count based on peer availability

## Testing Strategy

### Unit Tests
- Test each actor in isolation
- Mock peer responses
- Verify state transitions

### Integration Tests
- Test coordinator + workers
- Test full SNAP sync flow
- Test error scenarios

### Property-Based Tests
- Test with random task distributions
- Test with random failures
- Verify invariants hold

## Rollout Plan

1. **Feature flag**: Add config to enable/disable actor-based concurrency
2. **Parallel run**: Run both implementations, compare results
3. **Gradual rollout**: Enable for subset of users
4. **Full rollout**: Enable for all once stable
5. **Remove old code**: After successful rollout

## Open Questions

1. **Should workers be pooled or created on-demand?**
   - Recommendation: Pool for performance, create on-demand for simplicity initially

2. **How to handle peer disconnection mid-request?**
   - Recommendation: Worker receives PeerDisconnected message, reports failure

3. **What's the optimal worker count per coordinator?**
   - Recommendation: Start with config value (16 for accounts), tune based on metrics

4. **Should coordinators share a common trait?**
   - Recommendation: Yes, extract common patterns to `DownloadCoordinator` trait

## References

- Pekko Actors: https://pekko.apache.org/docs/pekko/current/typed/index.html
- Actor Supervision: https://pekko.apache.org/docs/pekko/current/typed/fault-tolerance.html
- Message Delivery: https://pekko.apache.org/docs/pekko/current/general/message-delivery-reliability.html
