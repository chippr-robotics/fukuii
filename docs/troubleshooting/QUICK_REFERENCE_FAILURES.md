# Quick Reference: Nightly Test Failures - Dec 19, 2025

## TL;DR

**37 tests failed** in the nightly comprehensive test suite. **76% are timeout-related** state synchronization issues.

## Priority Actions

### 🔥 Critical (Do First)
1. **Fix State Sync Timeouts** (28 failures)
   - File: `PeerRequestHandler.scala`
   - Issue: 5-second timeout too short, missing state nodes
   - Tests: E2EStateTestSpec (15), FastSyncItSpec (8), others (5)

### ⚠️ High Priority
2. **Fix MESS Consensus Logic** (3 failures)
   - File: `consensus/mess/` package
   - Issue: Chain selection with first-seen timestamps
   - Tests: MESSIntegrationSpec (3)

3. **Fix Sync State Checks** (3 failures)
   - File: `E2EFastSyncSpec.scala`
   - Issue: Expected state not defined (likely cascades from #1)

### 📌 Medium Priority
4. **Fix VM Test Data** (1 failure)
   - Issue: JSON decoding error, missing fields
   - Tests: VMTestsSpec (1)

5. **Fix Fork Validation** (1 failure)
   - Issue: Missing MPT root node (likely cascades from #1)
   - Tests: ForksTest (1)

## Failure Breakdown

| Issue Type | Count | % |
|------------|-------|---|
| Timeouts (State Sync) | 28 | 76% |
| MESS Consensus | 3 | 8% |
| Undefined State | 3 | 8% |
| Data Decoding | 1 | 3% |
| Fork Validation | 1 | 3% |
| Handshake Timeouts | 2 | 5% |

## Key Error Patterns

### 1. Timeout
```
java.util.concurrent.TimeoutException: Task time out after all retries
PEER_REQUEST_TIMEOUT: reqType=GetNodeData, elapsed=5019ms (timeout=5000ms)
```

### 2. Missing Nodes
```
MerklePatriciaTrie$MissingNodeException: Node not found <hash>
MerklePatriciaTrie$MissingRootNodeException: Root node not found <hash>
```

### 3. Undefined State
```
None was not defined (E2EFastSyncSpec.scala:106)
```

## Files to Investigate

### Critical Path
```
src/main/scala/com/chipprbots/ethereum/blockchain/sync/
  ├── PeerRequestHandler.scala          ⭐ START HERE
  └── regular/BlockImporter.scala

src/main/scala/com/chipprbots/ethereum/mpt/
  └── MerklePatriciaTrie.scala          ⭐ STATE ISSUES

src/test/scala/com/chipprbots/ethereum/sync/util/
  └── SyncCommonItSpecUtils.scala       ⭐ TIMEOUT LOGIC
```

### Test Specs
```
src/it/scala/com/chipprbots/ethereum/sync/
  ├── E2EStateTestSpec.scala            (15 failures)
  ├── FastSyncItSpec.scala              (8 failures)
  └── E2EFastSyncSpec.scala             (3 failures)

src/it/scala/com/chipprbots/ethereum/consensus/mess/
  └── MESSIntegrationSpec.scala         (3 failures)
```

## Quick Commands

### Run Failed Tests Locally
```bash
# Run specific test suite
sbt "testOnly com.chipprbots.ethereum.sync.E2EStateTestSpec"

# Run all integration tests
sbt IntegrationTest/test

# Run comprehensive suite (same as nightly)
sbt testComprehensive
```

### Check Timeout Configuration
```bash
grep -r "timeout.*5000" src/
grep -r "retryUntilWithDelay" src/
```

### Find MPT Node Issues
```bash
grep -r "MissingNodeException" src/
grep -r "Node not found" target/test-reports/
```

## Investigation Checklist

- [ ] Check PeerRequestHandler timeout (currently 5000ms)
- [ ] Verify test database initialization
- [ ] Review mock peer response setup in tests
- [ ] Profile test execution time (CI vs local)
- [ ] Check state trie node population in test fixtures
- [ ] Review MESS chain selection algorithm
- [ ] Update ethereum/tests submodule if needed
- [ ] Add diagnostic logging for peer requests

## Related Documentation

- **Full Analysis**: `docs/troubleshooting/NIGHTLY_RUN_ANALYSIS_2025-12-19.md`
- **Tracking Doc**: `docs/troubleshooting/FAILURE_TRACKING.md`
- **Workflow**: `.github/workflows/nightly.yml`

## Next Steps

1. Create issues for each category in GitHub
2. Assign to appropriate team members:
   - State Sync → Blockchain team
   - MESS Consensus → Consensus team
   - Test Infrastructure → QA team
3. Start with Priority #1 (State Sync Timeouts)
4. Re-run nightly tests after fixes

---

**Created**: 2025-12-19  
**Status**: Analysis Complete, Remediation Pending  
**Log Source**: Nightly workflow run #47
