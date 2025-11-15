# FastSync Timeout Test Investigation

**Date:** November 15, 2025  
**Issue:** FastSyncSpec - "returns Syncing with block progress once both header and body is fetched" timeout  
**Status:** ✅ Cannot Reproduce - Test Currently Passing

## Executive Summary

Investigated the FastSyncSpec test timeout issue mentioned in ADR-014 and issue #422. The test is **currently passing consistently** in both local testing and CI runs. The failure cannot be reproduced despite multiple attempts.

## Background

According to ADR-014, after fixing the noEmptyAccounts configuration bug (PR #421), one test remained failing:

- **Test:** FastSyncSpec - "returns Syncing with block progress once both header and body is fetched"
- **Error:** TimeoutException after 30 seconds
- **Root Cause (suspected):** "Parent chain weight not found for block 1" causing peer blacklisting loop
- **Classification:** Pre-existing async/timing issue unrelated to EIP-161 configuration

## Investigation Process

### 1. Test Execution
- **Local runs:** 3/3 successful (completed in ~1.8 seconds each)
- **CI run #1902 (develop):** PASSED (completed in 1.8 seconds)
- **Conclusion:** Test is stable and passing

### 2. Code Analysis

#### How the Error Would Occur

The error would happen in this sequence:

1. `FastSync.handleBlockHeaders()` processes incoming block headers
2. For each header, it validates and looks up parent chain weight:
   ```scala
   def getParentChainWeight(header: BlockHeader) =
     blockchainReader.getChainWeightByHash(header.parentHash)
       .toRight(ParentChainWeightNotFound(header))
   ```
3. If parent not found → blacklist peer and rewind
4. If all peers blacklisted → timeout waiting for sync progress

#### Test Setup

```scala
for {
  _ <- saveGenesis                    // Save block 0 with ChainWeight=1
  _ <- startSync                      // Start FastSync actor
  _ <- etcPeerManager.onPeersConnected  // Wait for peers
  _ <- etcPeerManager.pivotBlockSelected.head.compile.lastOrError  // Pivot selected
  blocksBatch <- etcPeerManager.fetchedBlocks.head.compile.lastOrError  // Blocks fetched
  status <- getSyncStatus             // Get sync status
  ...
```

The test properly sequences operations using Cats Effect IO, ensuring genesis is saved before sync starts.

### 3. Potential Race Conditions

Theoretically, a race could occur if:
- Genesis save didn't commit fully before FastSync queried it
- Storage initialization had ordering issues  
- Peer manager sent blocks faster than genesis could be stored

However, the code uses `.commit()` which is synchronous, and the test uses proper sequencing with `for-comprehension`.

## Findings

### Why the Test is Passing

1. **Proper Sequencing:** The test uses `for-comprehension` with IO, ensuring each step completes before the next
2. **Synchronous Storage:** `blockchainWriter.save()` calls `.commit()` which is synchronous
3. **Shared Storage:** Test fixture uses same storage instance for reader/writer
4. **No Recent Changes:** No code changes to FastSync or test since PR #421

### Why It Might Have Failed Before

The failure was likely:
1. **Extremely rare race condition** that cannot be easily reproduced
2. **Already fixed inadvertently** by other changes (possibly in test framework or dependencies)
3. **Environmental factors** in specific CI runs (CPU timing, resource contention)

## Conclusion

**The test is currently stable and passing.** Investigation found no code issues requiring fixes.

## Recommendations

1. **Close Issue:** Mark as "Cannot Reproduce" with note to monitor
2. **CI Monitoring:** Watch for future failures in this test
3. **If Recurs:** Add logging around genesis save and parent weight lookups
4. **Future Enhancement:** Consider adding explicit synchronization barrier after genesis save if failures recur

## Test Execution Evidence

```
Local Test Run #1:
[info]   - returns Syncing with block progress once both header and body is fetched (1 second, 847 milliseconds)
[info] All tests passed.

Local Test Run #2:
[info]   - returns Syncing with block progress once both header and body is fetched (1 second, 664 milliseconds)
[info] All tests passed.

Local Test Run #3:
[info]   - returns Syncing with block progress once both header and body is fetched (1 second, 772 milliseconds)
[info] All tests passed.
```

## Related Documentation

- **ADR-014:** `docs/adr/014-eip-161-noemptyaccounts-fix.md`
- **PR #421:** Fix noEmptyAccounts EVM config and update test fixtures
- **FastSync Test:** `src/test/scala/com/chipprbots/ethereum/blockchain/sync/FastSyncSpec.scala`
- **FastSync Implementation:** `src/main/scala/com/chipprbots/ethereum/blockchain/sync/fast/FastSync.scala`
