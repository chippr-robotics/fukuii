# SNAP Sync Cold Start Fix

## Issue
When a node starts at genesis (block 0), SNAP sync cannot begin because it requires at least 1500 blocks (configurable via `pivot-block-offset`, default 1024). The system should automatically fall back to regular sync for bootstrapping, then transition to SNAP sync once enough blocks are available. 

**Critical Problem Discovered**: After bootstrap completes at ~40k blocks, SNAP sync was selecting the pivot based on the **local best block** instead of the **network's best block**. This caused:
- Pivot selection of block ~39k when the chain is at 23M+ blocks
- SNAP sync completing instantly with "zero work" (state already exists from bootstrap)
- Node never catching up to the actual chain tip

## Root Causes
1. **Pivot Selection Bug**: Used `localBestBlock - pivotOffset` instead of `networkBestBlock - pivotOffset`
2. **Header Availability Race**: Pivot block header may not be available immediately after bootstrap due to asynchronous block import
3. **No Sanity Checks**: System didn't validate that the pivot was meaningfully ahead of local state

## Solution
Implemented a comprehensive fix with the following features:

### 1. Network-Based Pivot Selection
- **Query peers** to find the highest block in the network using `getPeerWithHighestBlock`
- **Select pivot** as `networkBestBlock - pivotBlockOffset` instead of `localBestBlock - pivotBlockOffset`
- **Fallback logic** if no peers are available (retries or uses local block with warnings)

### 2. Sanity Checks and Validation
- **Verify pivot is ahead** of local state before proceeding
- **Detect zero-work scenarios** where pivot ≤ local best block
- **Automatic transitions** to regular sync if already caught up

### 3. Retry Mechanism for Header Availability
- **Retry Counter**: Track retry attempts with `bootstrapRetryCount`
- **Configurable Parameters**:
  - `MaxBootstrapRetries = 10` attempts
  - `BootstrapRetryDelay = 2.seconds` between attempts
  - Total retry window: up to 20 seconds
- **Graceful Degradation**: Falls back to fast sync only after all retries exhausted

### 4. Enhanced Logging
- **Clear visibility** into pivot selection decision-making
- **Show both** local and network block numbers
- **Indicate source** of pivot selection (network vs local)
- **Visual indicators** (🚀 🎯 ✅ ⏳ 🔄 ⚠️ ❌) for different states

## Changes Made

### SNAPSyncController.scala - `startSnapSync()` Method

#### Pivot Selection Logic
```scala
// Get local and network state for pivot selection
val localBestBlock = appStateStorage.getBestBlockNumber()

// Query peers to find the highest block in the network
val networkBestBlockOpt = getPeerWithHighestBlock.map(_.peerInfo.maxBlockNumber)

// Determine which block number to use as the base for pivot calculation
val (baseBlockForPivot, pivotSelectionSource) = networkBestBlockOpt match {
  case Some(networkBestBlock) if networkBestBlock > localBestBlock + snapSyncConfig.pivotBlockOffset =>
    // Network is significantly ahead - use network best block for pivot
    (networkBestBlock, "network")
  case Some(networkBestBlock) =>
    // Network is not far ahead
    (networkBestBlock, "network")
  case None =>
    // No peers available yet - fall back to local best block
    (localBestBlock, "local")
}

val pivotBlockNumber = baseBlockForPivot - snapSyncConfig.pivotBlockOffset
```

#### Sanity Checks
```scala
if (pivotBlockNumber <= localBestBlock) {
  // Pivot must be ahead of local state
  if (pivotSelectionSource == "local" && networkBestBlockOpt.isEmpty) {
    // No peers - retry
    if (bootstrapRetryCount < MaxBootstrapRetries) {
      // Schedule retry
    } else {
      // Fall back to fast sync
    }
  } else {
    // Already synced - transition to regular sync
    context.parent ! Done
  }
}
```

#### Enhanced Logging
```scala
log.info("🎯 SNAP Sync Ready")
log.info(s"Local best block: $localBestBlock")
networkBestBlockOpt.foreach(netBest => log.info(s"Network best block: $netBest"))
log.info(s"Selected pivot block: $pivotBlockNumber (source: $pivotSelectionSource)")
log.info(s"Pivot offset: ${snapSyncConfig.pivotBlockOffset} blocks")
```

#### Modified `startSnapSync()` Method
When pivot block header is not available:
- Log warning with retry attempt number
- If retries remaining:
  - Increment retry counter
  - Schedule `RetrySnapSyncStart` message after delay
  - Transition to `bootstrapping` state to handle retry
- If max retries exceeded:
  - Log error
  - Reset retry counter
  - Fall back to fast sync

#### Enhanced Logging
- Added retry attempt tracking in log messages
- Clear distinction between temporary unavailability and permanent failure
- Better diagnostic information for troubleshooting

## Testing Recommendations

### Scenario 1: Cold Start from Genesis (Primary Use Case)
1. Start a fresh node from genesis with SNAP sync enabled
2. Monitor logs for bootstrap process
3. **Expected**: After bootstrap completes, SNAP sync should select pivot near network tip (e.g., 23M - 1024 = ~23M)
4. **Expected**: SNAP sync should begin downloading accounts, bytecodes, storage for this high pivot block
5. **Expected**: Logs should show both local block count (~40k) and network block count (~23M)

### Scenario 2: No Peers Available During Pivot Selection
1. Start node in isolated environment or with limited connectivity
2. **Expected**: System retries up to 10 times waiting for peers
3. **Expected**: Clear warning messages about missing peers
4. **Expected**: Falls back gracefully after max retries

### Scenario 3: Already Synced State
1. Start node that already has most blocks synced
2. **Expected**: Pivot calculation detects already-synced state
3. **Expected**: Transitions to regular sync for final catch-up
4. **Expected**: No wasted SNAP sync attempt

### Expected Log Patterns

**Successful cold start with network-based pivot:**
```
================================================================================
🚀 SNAP Sync Initialization
================================================================================
Current blockchain state: 0 blocks
SNAP sync requires at least 1025 blocks to begin
System will gather 1025 initial blocks via regular sync
Once complete, node will automatically transition to SNAP sync mode
================================================================================
⏳ Gathering initial blocks... (target: 1025)

[Regular sync bootstrap progress...]

================================================================================
✅ Bootstrap phase complete - transitioning to SNAP sync
================================================================================

================================================================================
🎯 SNAP Sync Ready
================================================================================
Local best block: 40700
Network best block: 23456789
Selected pivot block: 23455765 (source: network)
Pivot offset: 1024 blocks
State root: 0xabcd1234...
Beginning fast state sync with 16 concurrent workers
================================================================================
```

**Warning case - no peers available:**
```
⚠️  SNAP Sync Pivot Issue Detected
================================================================================
Calculated pivot (-1024) is not ahead of local state (0)
Pivot source: local, base block: 0, offset: 1024
No peers available for pivot selection. Retrying in 2 seconds... (attempt 1/10)
```

**Optimal case - already synced:**
```
⚠️  SNAP Sync Pivot Issue Detected
================================================================================
Calculated pivot (23455765) is not ahead of local state (23456000)
Pivot block is not ahead of local state - likely already synced
Transitioning to regular sync for final block catch-up
```

## Configuration

No configuration changes required. The retry mechanism uses hardcoded values that should work for most scenarios:
- 10 retries × 2 seconds = 20 seconds total retry window
- This should be sufficient for asynchronous block import to complete

If needed in future, these could be made configurable via `snap-sync` config section.

## Related Files
- `src/main/scala/com/chipprbots/ethereum/blockchain/sync/snap/SNAPSyncController.scala`
- `src/main/scala/com/chipprbots/ethereum/blockchain/sync/SyncController.scala`

## References
- Original issue: SNAP sync cold sync
- User logs showing the problem were found in issue comments
- SNAP sync documentation: `docs/architecture/SNAP_SYNC_IMPLEMENTATION.md`
