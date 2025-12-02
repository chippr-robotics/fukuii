# SNAP Sync Implementation TODO

## Executive Summary

This document provides a comprehensive inventory of remaining implementation and testing steps for SNAP sync in Fukuii based on:
- Review of existing implementation (Phases 1-7 infrastructure)
- Analysis of core-geth snap sync implementation
- Analysis of besu snap sync implementation
- Identification of gaps in current Fukuii implementation

## Current Implementation Status

### ✅ Complete Components

1. **Protocol Infrastructure** (Phase 1)
   - SNAP protocol family and SNAP1 capability defined
   - Capability negotiation integrated
   - All chain configs updated with snap/1 capability

2. **Message Definitions** (Phase 1-2)
   - All 8 SNAP/1 messages defined (GetAccountRange, AccountRange, GetStorageRanges, StorageRanges, GetByteCodes, ByteCodes, GetTrieNodes, TrieNodes)
   - Complete RLP encoding/decoding for all messages
   - SNAPMessageDecoder implemented and integrated

3. **Request/Response Infrastructure** (Phase 3)
   - SNAPRequestTracker for request lifecycle management
   - Request ID generation and tracking
   - Timeout handling with configurable durations
   - Response validation (monotonic ordering, type matching)

4. **Account Range Sync** (Phase 4)
   - AccountTask for managing account range state
   - AccountRangeDownloader with parallel downloads
   - MerkleProofVerifier for account proof verification
   - Progress tracking and statistics
   - Task continuation for partial responses
   - Basic MptStorage integration (accounts stored as nodes)

5. **Storage Range Sync** (Phase 5)
   - StorageTask for managing storage range state
   - StorageRangeDownloader with batched requests
   - Storage proof verification in MerkleProofVerifier
   - Progress tracking and statistics
   - Basic MptStorage integration (slots stored as nodes)

6. **State Healing** (Phase 6)
   - HealingTask for missing trie nodes
   - TrieNodeHealer with batched requests
   - Node hash validation
   - Basic MptStorage integration (nodes stored by hash)

7. **Sync Controller** (Phase 7)
   - SNAPSyncController orchestrating all phases
   - Phase transitions and state management
   - StateValidator for completeness checking
   - SyncProgressMonitor for tracking
   - SNAPSyncConfig for configuration management

### ⚠️ Incomplete/TODO Components

## Critical TODOs (Required for Basic Functionality)

### 1. Message Handling Integration

**Current State:** Message decoder exists but messages aren't routed to SNAPSyncController

**Required Work:**
- [ ] Create SNAP message handler in EtcPeerManagerActor
- [ ] Route AccountRange responses to AccountRangeDownloader
- [ ] Route StorageRanges responses to StorageRangeDownloader
- [ ] Route TrieNodes responses to TrieNodeHealer
- [ ] Route ByteCodes responses to (new) ByteCodeDownloader
- [ ] Handle GetAccountRange requests from peers (optional - we're primarily a client)
- [ ] Handle GetStorageRanges requests from peers (optional)
- [ ] Handle GetTrieNodes requests from peers (optional)
- [ ] Handle GetByteCodes requests from peers (optional)

**Files to Modify:**
- `src/main/scala/com/chipprbots/ethereum/network/EtcPeerManagerActor.scala`
- `src/main/scala/com/chipprbots/ethereum/blockchain/sync/snap/SNAPSyncController.scala`

**Implementation Notes:**
- Add pattern matching for SNAP messages in EtcPeerManagerActor.receive
- Forward responses to SNAPSyncController actor
- SNAPSyncController should forward to appropriate downloader based on current phase

### 2. Peer Communication Integration

**Current State:** Downloaders create message objects but don't actually send them

**Required Work:**
- [ ] Connect AccountRangeDownloader to actual peer selection
- [ ] Implement peer selection strategy (prefer fast, reliable peers)
- [ ] Connect StorageRangeDownloader to peer selection
- [ ] Connect TrieNodeHealer to peer selection
- [ ] Handle peer disconnection during active requests
- [ ] Implement request retry with different peers
- [ ] Add peer reputation tracking for SNAP requests

**Files to Modify:**
- `src/main/scala/com/chipprbots/ethereum/blockchain/sync/snap/AccountRangeDownloader.scala`
- `src/main/scala/com/chipprbots/ethereum/blockchain/sync/snap/StorageRangeDownloader.scala`
- `src/main/scala/com/chipprbots/ethereum/blockchain/sync/snap/TrieNodeHealer.scala`
- `src/main/scala/com/chipprbots/ethereum/network/EtcPeerManagerActor.scala`

**Implementation Notes:**
- Remove simulation timeouts (scheduler.scheduleOnce) from SNAPSyncController
- Implement actual peer request loop in each downloader
- Use EtcPeerManager.GetHandshakedPeers to find SNAP-capable peers
- Maintain active request count per peer to avoid overwhelming
- Implement exponential backoff for failed requests

### 3. Storage Persistence (AppStateStorage)

**Current State:** TODO comments in SNAPSyncController for missing AppStateStorage methods

**Required Work:**
- [ ] Add `isSnapSyncDone(): Boolean` method to AppStateStorage
- [ ] Add `putSnapSyncDone(done: Boolean): AppStateStorage` method
- [ ] Add `getSnapSyncPivotBlock(): Option[BigInt]` method
- [ ] Add `putSnapSyncPivotBlock(block: BigInt): AppStateStorage` method
- [ ] Add `getSnapSyncStateRoot(): Option[ByteString]` method
- [ ] Add `putSnapSyncStateRoot(root: ByteString): AppStateStorage` method
- [ ] Add `getSnapSyncProgress(): Option[SyncProgress]` method (optional)
- [ ] Add `putSnapSyncProgress(progress: SyncProgress): AppStateStorage` method (optional)

**Files to Modify:**
- `src/main/scala/com/chipprbots/ethereum/db/storage/AppStateStorage.scala`
- `src/main/scala/com/chipprbots/ethereum/db/storage/AppStateStorage Impl classes`

**Implementation Notes:**
- Use existing key-value store patterns in AppStateStorage
- Keys: "snap-sync-done", "snap-sync-pivot-block", "snap-sync-state-root"
- Store as RLP-encoded values or use existing serialization patterns
- Ensure atomic commits for state consistency

### 4. Sync Mode Selection Integration

**Current State:** SyncController doesn't know about SNAP sync

**Required Work:**
- [ ] Add SNAP sync mode to SyncController
- [ ] Implement sync mode priority (SNAP > Fast > Regular)
- [ ] Add do-snap-sync configuration flag
- [ ] Load SNAPSyncConfig from Typesafe config
- [ ] Create SNAPSyncController actor in SyncController
- [ ] Handle SNAP sync completion and transition to regular sync
- [ ] Persist SNAP sync state for resume after restart

**Files to Modify:**
- `src/main/scala/com/chipprbots/ethereum/blockchain/sync/SyncController.scala`
- `src/main/resources/conf/reference.conf` (or chain-specific configs)

**Implementation Notes:**
- Check isSnapSyncDone() before starting SNAP sync
- Instantiate SNAPSyncController with proper dependencies
- Forward sync messages to SNAPSyncController when in SNAP mode
- Transition to RegularSyncController after SNAP completion
- Store pivot block in AppStateStorage for checkpoint

### 5. State Storage Integration

**Current State:** Accounts/storage/nodes stored as simplified MPT nodes, not complete tries

**Required Work:**
- [ ] Review MptStorage usage in downloaders
- [ ] Ensure accounts are properly inserted into state trie
- [ ] Ensure storage slots are properly inserted into account storage tries
- [ ] Ensure healed nodes correctly reconstruct trie structure
- [ ] Implement proper state root verification after sync
- [ ] Handle account with empty storage correctly
- [ ] Handle account with bytecode correctly

**Files to Modify:**
- `src/main/scala/com/chipprbots/ethereum/blockchain/sync/snap/AccountRangeDownloader.scala`
- `src/main/scala/com/chipprbots/ethereum/blockchain/sync/snap/StorageRangeDownloader.scala`
- `src/main/scala/com/chipprbots/ethereum/blockchain/sync/snap/TrieNodeHealer.scala`

**Implementation Notes:**
- Current implementation stores nodes individually
- May need to build tries incrementally or use snapshot storage
- Verify state root matches pivot block's state root
- Consider using a dedicated snapshot storage layer (like core-geth)

### 6. ByteCodes Download Implementation

**Current State:** GetByteCodes/ByteCodes messages defined but not used

**Required Work:**
- [ ] Create ByteCodeDownloader similar to AccountRangeDownloader
- [ ] Identify contract accounts during account sync (codeHash != emptyCodeHash)
- [ ] Queue bytecode download tasks for contract accounts
- [ ] Verify bytecode hash matches account codeHash
- [ ] Store bytecodes in appropriate storage
- [ ] Integrate ByteCodeDownloader into SNAPSyncController workflow

**New Files to Create:**
- `src/main/scala/com/chipprbots/ethereum/blockchain/sync/snap/ByteCodeDownloader.scala`
- `src/main/scala/com/chipprbots/ethereum/blockchain/sync/snap/ByteCodeTask.scala`

**Files to Modify:**
- `src/main/scala/com/chipprbots/ethereum/blockchain/sync/snap/SNAPSyncController.scala`
- `src/main/scala/com/chipprbots/ethereum/blockchain/sync/snap/AccountRangeDownloader.scala`

**Implementation Notes:**
- Extract contract accounts during account range processing
- Batch bytecode requests (multiple code hashes per request)
- Verify downloaded bytecode against keccak256 hash
- Store in evmcode storage or appropriate location

## Important TODOs (Required for Production)

### 7. State Validation Enhancement

**Current State:** StateValidator has TODO implementations

**Required Work:**
- [ ] Implement actual account trie traversal in validateAccountTrie
- [ ] Detect missing nodes during traversal
- [ ] Implement storage trie validation for all accounts
- [ ] Return detailed missing node information
- [ ] Trigger additional healing iterations for missing nodes
- [ ] Verify final state root matches pivot block

**Files to Modify:**
- `src/main/scala/com/chipprbots/ethereum/blockchain/sync/snap/SNAPSyncController.scala` (StateValidator class)

**Implementation Notes:**
- Walk the entire account trie from state root
- For each account, walk its storage trie if it has storage
- Collect missing node hashes for healing
- Multiple healing iterations may be needed
- Final validation must pass before transitioning to regular sync

### 8. Configuration Management

**Current State:** SNAPSyncConfig defined but not loaded from config files

**Required Work:**
- [ ] Add snap-sync section to reference.conf
- [ ] Add snap-sync section to chain-specific configs (etc-chain.conf, etc.)
- [ ] Set sensible defaults for all parameters
- [ ] Document configuration options
- [ ] Add validation for configuration values

**Files to Modify:**
- `src/main/resources/conf/reference.conf`
- `src/main/resources/conf/chains/etc-chain.conf`
- `src/main/resources/conf/chains/mordor-chain.conf`
- `src/main/resources/conf/chains/eth-chain.conf`
- Other chain configs as needed

**Configuration Structure:**
```hocon
sync {
  do-snap-sync = true
  
  snap-sync {
    enabled = true
    pivot-block-offset = 1024
    account-concurrency = 16
    storage-concurrency = 8
    storage-batch-size = 8
    healing-batch-size = 16
    state-validation-enabled = true
    max-retries = 3
    timeout = 30 seconds
  }
}
```

### 9. Progress Monitoring and Logging

**Current State:** SyncProgressMonitor defined but not fully integrated

**Required Work:**
- [ ] Implement progress update callbacks from downloaders
- [ ] Add periodic progress logging in SNAPSyncController
- [ ] Expose progress via JSON-RPC API (optional)
- [ ] Add metrics for monitoring (accounts/sec, slots/sec, etc.)
- [ ] Log phase transitions clearly
- [ ] Add ETA calculations

**Files to Modify:**
- `src/main/scala/com/chipprbots/ethereum/blockchain/sync/snap/SNAPSyncController.scala`
- `src/main/scala/com/chipprbots/ethereum/blockchain/sync/snap/AccountRangeDownloader.scala`
- `src/main/scala/com/chipprbots/ethereum/blockchain/sync/snap/StorageRangeDownloader.scala`
- `src/main/scala/com/chipprbots/ethereum/blockchain/sync/snap/TrieNodeHealer.scala`

**Implementation Notes:**
- Update progress monitor from each downloader
- Log progress every N seconds or N accounts
- Include throughput metrics in logs
- Calculate ETA based on current progress and throughput

### 10. Error Handling and Recovery

**Current State:** Basic error handling exists but needs enhancement

**Required Work:**
- [ ] Handle malformed responses gracefully
- [ ] Implement retry logic with exponential backoff
- [ ] Handle peer bans for bad behavior (invalid proofs, etc.)
- [ ] Recover from interrupted sync (resume from last state)
- [ ] Handle pivot block reorg during sync
- [ ] Add circuit breaker for repeatedly failing tasks
- [ ] Implement fallback to fast sync if SNAP fails repeatedly

**Files to Modify:**
- All snap sync files may need error handling improvements

**Implementation Notes:**
- Log errors with context (peer, request ID, etc.)
- Blacklist peers that send invalid data
- Persist progress frequently for resumability
- Consider pivot block staleness (may need to re-select)

## Testing TODOs

### 11. Unit Tests

**Required Work:**
- [ ] Test SNAPSyncController phase transitions
- [ ] Test AccountRangeDownloader with mock peers
- [ ] Test StorageRangeDownloader with mock peers
- [ ] Test TrieNodeHealer with mock peers
- [ ] Test MerkleProofVerifier with real Merkle proofs
- [ ] Test SNAPRequestTracker timeout handling
- [ ] Test message encoding/decoding for all 8 messages
- [ ] Test configuration loading and validation
- [ ] Test AppStateStorage SNAP sync methods

**New Test Files:**
- `src/test/scala/com/chipprbots/ethereum/blockchain/sync/snap/SNAPSyncControllerSpec.scala`
- `src/test/scala/com/chipprbots/ethereum/blockchain/sync/snap/AccountRangeDownloaderSpec.scala`
- `src/test/scala/com/chipprbots/ethereum/blockchain/sync/snap/StorageRangeDownloaderSpec.scala`
- `src/test/scala/com/chipprbots/ethereum/blockchain/sync/snap/TrieNodeHealerSpec.scala`
- `src/test/scala/com/chipprbots/ethereum/blockchain/sync/snap/MerkleProofVerifierSpec.scala`
- `src/test/scala/com/chipprbots/ethereum/blockchain/sync/snap/SNAPRequestTrackerSpec.scala`

### 12. Integration Tests

**Required Work:**
- [ ] Test complete SNAP sync flow with mock network
- [ ] Test transition from SNAP sync to regular sync
- [ ] Test resume after restart (state persistence)
- [ ] Test with different pivot blocks
- [ ] Test healing process with missing nodes
- [ ] Test concurrent requests to multiple peers
- [ ] Test peer disconnection handling

**New Test Files:**
- `src/it/scala/com/chipprbots/ethereum/blockchain/sync/snap/SNAPSyncIntegrationSpec.scala`

### 13. End-to-End Tests

**Required Work:**
- [ ] Test SNAP sync on Mordor testnet
- [ ] Test SNAP sync on ETC mainnet (small sync from recent pivot)
- [ ] Verify state consistency after sync
- [ ] Verify block processing works after sync
- [ ] Compare performance vs fast sync
- [ ] Test interoperability with core-geth peers
- [ ] Test interoperability with geth peers
- [ ] Measure sync time, bandwidth, disk I/O

**Documentation:**
- Document E2E test results in ADR or separate report
- Include performance benchmarks
- Note any compatibility issues discovered

## Research TODOs

### 14. Core-Geth Implementation Study

**Required Work:**
- [ ] Study core-geth snap sync coordinator (eth/syncer.go)
- [ ] Study core-geth snap protocol handler (eth/protocols/snap/handler.go)
- [ ] Study core-geth snapshot storage layer
- [ ] Identify optimizations we can adopt
- [ ] Identify potential pitfalls to avoid

**Key Insights from Core-Geth:**
- Uses dedicated snapshot storage layer for fast access
- Implements dynamic pivot block selection
- Has sophisticated peer selection and load balancing
- Implements state healing with multiple iterations
- Has fallback mechanisms for various failure scenarios

### 15. Besu Implementation Study

**Required Work:**
- [ ] Study Besu SnapWorldStateDownloader
- [ ] Study Besu SnapSyncState persistence
- [ ] Study Besu snap sync metrics and monitoring
- [ ] Identify Java/Scala-friendly patterns
- [ ] Compare with core-geth approach

**Key Insights from Besu:**
- Task-based parallelism using Java concurrency
- Dedicated world state storage abstraction
- Comprehensive metrics collection
- Integration with health check system

## Documentation TODOs

### 16. User Documentation

**Required Work:**
- [ ] Update README with SNAP sync information
- [ ] Create SNAP sync user guide
- [ ] Document configuration options
- [ ] Add troubleshooting guide
- [ ] Document performance characteristics
- [ ] Add FAQ for common issues

**Files to Create/Modify:**
- `docs/user-guide/snap-sync.md`
- `docs/troubleshooting/snap-sync.md`
- `README.md` (update features section)

### 17. Developer Documentation

**Required Work:**
- [ ] Update architecture documentation
- [ ] Document sync flow diagram
- [ ] Document state storage format
- [ ] Add code examples for extending SNAP sync
- [ ] Document testing strategy

**Files to Create/Modify:**
- `docs/architecture/SNAP_SYNC_IMPLEMENTATION.md` (update with actual impl details)
- `docs/architecture/diagrams/snap-sync-flow.md`
- `docs/development/testing-snap-sync.md`

### 18. ADR Updates

**Required Work:**
- [ ] Update ADR-SNAP-001 with final implementation status
- [ ] Update ADR-SNAP-002 with production deployment results
- [ ] Create ADR-SNAP-003 for any significant design decisions made during completion

**Files to Modify:**
- `docs/adr/protocols/ADR-SNAP-001-protocol-infrastructure.md`
- `docs/adr/protocols/ADR-SNAP-002-integration-architecture.md`

## Performance TODOs

### 19. Optimization

**Required Work:**
- [ ] Profile sync performance (CPU, memory, disk I/O, network)
- [ ] Optimize Merkle proof verification
- [ ] Optimize RLP encoding/decoding
- [ ] Tune concurrency parameters
- [ ] Implement connection pooling for peer requests
- [ ] Consider async I/O for storage operations
- [ ] Benchmark against core-geth and besu

**Tools:**
- VisualVM, YourKit, or async-profiler for profiling
- Benchmark suite for reproducible measurements

### 20. Monitoring

**Required Work:**
- [ ] Add Prometheus metrics for SNAP sync
- [ ] Add Kamon instrumentation
- [ ] Create Grafana dashboard for SNAP sync
- [ ] Add alerting for sync failures
- [ ] Monitor peer performance metrics

**New Files:**
- `docs/operations/monitoring-snap-sync.md`
- Dashboard JSON for Grafana

## Timeline Estimate

### Phase 1: Critical Implementation (4-6 weeks)
- Message handling integration (1 week)
- Peer communication integration (2 weeks)
- Storage persistence (1 week)
- Sync mode selection (1 week)
- ByteCodes download (1 week)

### Phase 2: Production Readiness (3-4 weeks)
- State validation enhancement (1 week)
- Configuration management (1 week)
- Error handling and recovery (1 week)
- Progress monitoring (1 week)

### Phase 3: Testing (3-4 weeks)
- Unit tests (1 week)
- Integration tests (1 week)
- End-to-end tests (1-2 weeks)

### Phase 4: Documentation & Optimization (2-3 weeks)
- User and developer documentation (1 week)
- Performance optimization (1 week)
- Monitoring and metrics (1 week)

**Total Estimated Time: 12-17 weeks (3-4 months)**

## Priority Order

### P0 - Critical (Must Have for Basic Functionality)
1. Message handling integration (#1)
2. Peer communication integration (#2)
3. Storage persistence (#3)
4. Sync mode selection (#4)

### P1 - Important (Must Have for Production)
5. State storage integration (#5)
6. ByteCodes download (#6)
7. State validation enhancement (#7)
8. Configuration management (#8)
9. Error handling and recovery (#10)

### P2 - Nice to Have (Enhances Quality)
10. Progress monitoring (#9)
11. Unit tests (#11)
12. Integration tests (#12)

### P3 - Can Be Done Later
13. End-to-end tests (#13)
14. Research studies (#14, #15)
15. Documentation (#16, #17, #18)
16. Optimization (#19)
17. Monitoring (#20)

## Success Criteria

SNAP sync implementation is considered complete when:

1. ✅ All P0 and P1 tasks are complete
2. ✅ SNAP sync successfully syncs from a recent pivot on Mordor testnet
3. ✅ State validation passes after SNAP sync
4. ✅ Transition to regular sync works correctly
5. ✅ Sync completes 50%+ faster than fast sync
6. ✅ Unit test coverage >80% for SNAP sync code
7. ✅ Integration tests pass consistently
8. ✅ Documentation is complete and accurate
9. ✅ No critical bugs in production after 1 month

## Notes

- This TODO is based on code review as of 2025-12-02
- Some tasks may be discovered during implementation
- Timeline assumes one full-time developer
- Multiple developers can parallelize some tasks
- Estimates are conservative to account for unknowns

## References

- [SNAP Protocol Specification](https://github.com/ethereum/devp2p/blob/master/caps/snap.md)
- [Core-Geth Syncer](https://github.com/etclabscore/core-geth/blob/master/eth/syncer.go)
- [Core-Geth SNAP Handler](https://github.com/etclabscore/core-geth/blob/master/eth/protocols/snap/handler.go)
- [Besu SNAP Sync](https://github.com/hyperledger/besu/tree/main/ethereum/eth/src/main/java/org/hyperledger/besu/ethereum/eth/sync/snapsync)
- [ADR-SNAP-001](../../adr/protocols/ADR-SNAP-001-protocol-infrastructure.md)
- [ADR-SNAP-002](../../adr/protocols/ADR-SNAP-002-integration-architecture.md)
- [SNAP Sync Implementation Guide](./SNAP_SYNC_IMPLEMENTATION.md)

---

*Created: 2025-12-02*
*Author: GitHub Copilot Workspace Agent*
*Status: Active Development Plan*
