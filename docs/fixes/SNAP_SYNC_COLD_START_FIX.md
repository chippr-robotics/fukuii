# SNAP Sync Cold Start Fix

## Issue
When a node starts at genesis (block 0), SNAP sync cannot begin because it requires at least 1500 blocks (configurable via `pivot-block-offset`, default 1024). The system should automatically fall back to regular sync for bootstrapping, then transition to SNAP sync once enough blocks are available. However, the transition from bootstrap mode to SNAP sync was not working correctly, leaving the node stuck in an idle state.

## Root Cause
After receiving the `BootstrapComplete` message, SNAP sync calls `startSnapSync()` again to begin the actual SNAP sync process. However, due to asynchronous block import, the pivot block header may not yet be available in `blockchainReader` when this call is made. 

Previously, when `blockchainReader.getBlockHeaderByNumber(pivotBlockNumber)` returned `None`, the system would immediately fall back to fast sync. This was too aggressive and didn't account for the race condition between block import completion and header availability.

## Solution
Implemented a retry mechanism with the following features:

1. **Retry Counter**: Added `bootstrapRetryCount` to track retry attempts
2. **Configurable Retry Parameters**:
   - `MaxBootstrapRetries = 10` attempts
   - `BootstrapRetryDelay = 2.seconds` between attempts
   - Total retry window: up to 20 seconds

3. **New Internal Message**: `RetrySnapSyncStart` to trigger retry attempts

4. **Graceful Degradation**: Only falls back to fast sync after all retry attempts are exhausted

## Changes Made

### SNAPSyncController.scala

#### Added State Variables
```scala
private var bootstrapRetryCount: Int = 0
private val MaxBootstrapRetries = 10
private val BootstrapRetryDelay = 2.seconds
private var bootstrapCheckTask: Option[Cancellable] = None
```

#### Enhanced `bootstrapping` Receive Block
```scala
case BootstrapComplete =>
  log.info("Bootstrap phase complete - transitioning to SNAP sync")
  appStateStorage.clearSnapSyncBootstrapTarget().commit()
  bootstrapRetryCount = 0  // Reset retry counter
  startSnapSync()

case RetrySnapSyncStart =>
  log.info("Retrying SNAP sync start after bootstrap delay")
  startSnapSync()
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

### Manual Testing
1. Start a fresh node from genesis with SNAP sync enabled
2. Monitor logs for:
   - "Starting automatic bootstrap: regular sync to block X"
   - "Bootstrap phase complete - transitioning to SNAP sync"
   - "Cannot get header for pivot block X (attempt Y/10)"
   - "SNAP sync pivot block: X, state root: Y" (success)
   OR
   - "Pivot block header still not available after 10 retries" (fallback)

### Expected Behavior
- Node starts regular sync bootstrap
- After reaching target blocks, attempts SNAP sync transition
- If pivot block header not immediately available, retries up to 10 times with 2-second delays
- Successfully transitions to SNAP sync phases (AccountRange, ByteCode, Storage, Healing)
- OR falls back to fast sync if pivot block remains unavailable

### Log Patterns to Verify

**Successful transition:**
```
Starting automatic bootstrap: regular sync to block 1025
Bootstrap phase complete - transitioning to SNAP sync
SNAP sync pivot block: 1, state root: 0xabcd...
Starting account range sync with concurrency 16
```

**Retry scenario:**
```
Bootstrap phase complete - transitioning to SNAP sync
Cannot get header for pivot block 1 (attempt 1/10)
Scheduling retry in 2 seconds...
Retrying SNAP sync start after bootstrap delay
SNAP sync pivot block: 1, state root: 0xabcd...
```

**Fallback scenario (should be rare):**
```
Cannot get header for pivot block 1 (attempt 10/10)
Pivot block header still not available after 10 retries
Falling back to fast sync
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
