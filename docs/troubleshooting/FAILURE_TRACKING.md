# Nightly Test Failure Tracking

## Current Status

**Last Analysis**: 2025-12-19  
**Total Failures**: 37 out of 184 tests  
**Success Rate**: 79.9%  

## Quick Summary by Category

| Category | Count | % of Failures | Status |
|----------|-------|---------------|--------|
| Timeout Issues (State Sync) | 28 | 76% | 🔴 Not Started |
| MESS Consensus Issues | 3 | 8% | 🔴 Not Started |
| Sync State Undefined | 3 | 8% | 🔴 Not Started |
| Data Decoding Issues | 1 | 3% | 🔴 Not Started |
| Fork Validation Issues | 1 | 3% | 🔴 Not Started |
| Handshake Timeouts | 2 | 5% | 🔴 Not Started |

**Legend:**
- 🔴 Not Started
- 🟡 In Progress
- 🟢 Resolved
- ⚪ Blocked/On Hold

## Detailed Failure Tracking

### Category 1: State Synchronization Timeout Issues

**Status**: 🔴 Not Started  
**Priority**: P0 (Critical - affects 76% of failures)  
**Assigned To**: TBD  
**Related Issue**: #TBD

#### Root Cause
- `PeerRequestHandler` timeout of 5000ms consistently exceeded
- `GetNodeData` requests not completing in time
- State trie nodes missing from database during sync
- Test retry mechanism exhausting attempts

#### Affected Test Suites
- `E2EStateTestSpec` - 15 failures
- `FastSyncItSpec` - 8 failures
- `E2ESyncSpec` - 2 failures
- `SNAPSyncIntegrationSpec` - 2 failures
- `E2EFastSyncSpec` - 1 failure

#### Action Items
- [ ] Profile `PeerRequestHandler` timeout behavior in CI environment
- [ ] Review `SyncCommonItSpecUtils.retryUntilWithDelay` retry logic
- [ ] Investigate test fixture setup for state data
- [ ] Check database initialization in integration tests
- [ ] Consider environment-specific timeout configuration
- [ ] Add diagnostic logging for peer request lifecycle

#### Files to Review
- `src/main/scala/com/chipprbots/ethereum/blockchain/sync/PeerRequestHandler.scala`
- `src/main/scala/com/chipprbots/ethereum/mpt/MerklePatriciaTrie.scala`
- `src/test/scala/com/chipprbots/ethereum/sync/util/SyncCommonItSpecUtils.scala`
- `src/it/scala/com/chipprbots/ethereum/sync/E2EStateTestSpec.scala`
- `src/it/scala/com/chipprbots/ethereum/sync/FastSyncItSpec.scala`

---

### Category 2: MESS Consensus Chain Selection Issues

**Status**: 🔴 Not Started  
**Priority**: P1 (High - affects consensus reliability)  
**Assigned To**: TBD  
**Related Issue**: #TBD

#### Root Cause
- Chain selection logic not properly handling first-seen timestamps
- Edge cases with equal difficulty chains
- Reorg scenario handling issues

#### Affected Tests
- `should prefer recently seen chain over old chain with same difficulty` (243ms)
- `should handle blocks without first-seen time using block timestamp` (1ms)
- `should correctly handle chain reorganization scenario` (3ms)

#### Action Items
- [ ] Review MESS chain selection algorithm implementation
- [ ] Add test cases for edge conditions (missing timestamps, equal difficulty)
- [ ] Validate first-seen time tracking logic
- [ ] Check chain reorganization handling in MESS consensus

#### Files to Review
- `src/main/scala/com/chipprbots/ethereum/consensus/mess/` (chain selection components)
- `src/it/scala/com/chipprbots/ethereum/consensus/mess/MESSIntegrationSpec.scala`

---

### Category 3: Sync State Undefined Issues

**Status**: 🔴 Not Started  
**Priority**: P1 (High - cascading from timeouts)  
**Assigned To**: TBD  
**Related Issue**: #TBD

#### Root Cause
- Test expects `Some` or `Right` but gets `None` or `Left`
- Sync process not completing successfully
- Likely secondary issue caused by timeout problems

#### Affected Tests
- `E2EFastSyncSpec: should successfully sync blockchain with state nodes` (6s 719ms)
- `E2EFastSyncSpec: should handle incomplete state downloads gracefully` (6s 394ms)
- `E2EFastSyncSpec: should recover from peer disconnection during state download` (7s 446ms)

#### Action Items
- [ ] Wait for Category 1 (Timeout Issues) resolution
- [ ] Review assertion logic in E2EFastSyncSpec
- [ ] Verify expected sync completion criteria
- [ ] Add better error messages for undefined state

#### Files to Review
- `src/it/scala/com/chipprbots/ethereum/sync/E2EFastSyncSpec.scala`

---

### Category 4: VM Test Data Decoding Issues

**Status**: 🔴 Not Started  
**Priority**: P2 (Medium - isolated issue)  
**Assigned To**: TBD  
**Related Issue**: #TBD

#### Root Cause
- Test data JSON format incompatible or missing required fields
- Error: `DecodingFailure at .add.blocks: Missing required field`

#### Affected Tests
- `VMTestsSpec: should load and parse a sample VM arithmetic test` (83ms)

#### Action Items
- [ ] Check ethereum/tests submodule version
- [ ] Review JSON schema for VM test data
- [ ] Update test data files if needed
- [ ] Verify decoder implementation matches expected format

#### Files to Review
- `src/it/scala/com/chipprbots/ethereum/ethtest/VMTestsSpec.scala`
- VM test data files (JSON format)

---

### Category 5: Fork Execution Validation Issues

**Status**: 🔴 Not Started  
**Priority**: P1 (High - consensus critical)  
**Assigned To**: TBD  
**Related Issue**: #TBD

#### Root Cause
- Missing MPT root node during fork execution
- Related to state sync/availability issues
- Error: `Left(MPTError(MissingRootNodeException: Root node not found...))`

#### Affected Tests
- `ForksTest: should execute blocks with respect to forks` (1s 329ms)

#### Action Items
- [ ] Investigate fork test fixture setup
- [ ] Ensure all required state nodes are available
- [ ] May be resolved by fixing Category 1 (State Sync)

#### Files to Review
- `src/it/scala/com/chipprbots/ethereum/txExecTest/ForksTest.scala`
- Fork execution logic

---

### Category 6: Handshake Timeout Issues

**Status**: 🔴 Not Started  
**Priority**: P2 (Medium - may be related to Category 1)  
**Assigned To**: TBD  
**Related Issue**: #TBD

#### Root Cause
- Handshake operations timing out
- May be related to general peer communication issues

#### Affected Tests
- `E2EHandshakeSpec: should handle bidirectional connection attempts` (9s 126ms)
- `E2EHandshakeSpec: should handshake with peers at genesis` (7s 155ms)

#### Action Items
- [ ] Review handshake timeout configuration
- [ ] Check if related to general timeout issues (Category 1)
- [ ] Investigate test peer setup and mock responses

#### Files to Review
- `src/it/scala/com/chipprbots/ethereum/network/E2EHandshakeSpec.scala`

---

## Investigation Notes

### Common Patterns Observed

1. **Peer Request Timeouts** - Consistent 5000ms timeout exceeded:
   ```
   PEER_REQUEST_TIMEOUT: peer=PeerId(...), reqType=GetNodeData, elapsed=5019ms (timeout=5000ms)
   ```

2. **Missing MPT Nodes** - Multiple instances:
   ```
   com.chipprbots.ethereum.mpt.MerklePatriciaTrie$MissingNodeException: Node not found <hash>
   com.chipprbots.ethereum.mpt.MerklePatriciaTrie$MissingRootNodeException: Root node not found <hash>
   ```

3. **SNAP Protocol Warnings**:
   ```
   RECV_MSG: peer=127.0.0.1:46506, msg[0] DECODE_ERROR: Unknown snap/1 message type: 29
   ```

### Dependency Graph

```
Category 1 (Timeouts) ─┬─> Category 3 (Undefined State)
                       ├─> Category 5 (Fork Validation)
                       └─> Category 6 (Handshake Timeouts)

Category 2 (MESS) ────────> Independent

Category 4 (VM Data) ─────> Independent
```

**Resolution Strategy**: Fix Category 1 first, as it likely cascades to Categories 3, 5, and possibly 6.

---

## Historical Tracking

| Date | Total Failures | Change | Notes |
|------|----------------|--------|-------|
| 2025-12-19 | 37 | - | Initial analysis |

---

## Resources

- **Full Analysis**: [NIGHTLY_RUN_ANALYSIS_2025-12-19.md](./NIGHTLY_RUN_ANALYSIS_2025-12-19.md)
- **Workflow**: `.github/workflows/nightly.yml`
- **Test Command**: `sbt testComprehensive`
- **Log Archive**: Stored in GitHub Actions artifacts (30-day retention)

---

## Success Criteria

The nightly test failures will be considered resolved when:

1. ✅ All 37 failures have been analyzed (COMPLETE)
2. ⬜ Root causes identified for each category (IN PROGRESS)
3. ⬜ Fixes implemented and tested locally
4. ⬜ All 37 tests pass in CI environment
5. ⬜ No new test failures introduced
6. ⬜ Test suite completes in reasonable time (< 2 hours)
7. ⬜ Documentation updated with any configuration changes

---

**Last Updated**: 2025-12-19  
**Document Owner**: QA/Testing Team
