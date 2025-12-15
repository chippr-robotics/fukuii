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

5. **User Experience**: Clear, informative log messages with visual indicators (🚀 🎯 ✅ ⏳ 🔄 ❌) guide users through the bootstrap and transition process

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
2. Monitor logs for user-friendly status messages with visual indicators (🚀 🎯 ✅ ⏳ 🔄 ❌)

### Expected Behavior
- Node displays clear initialization message explaining the bootstrap process
- Regular sync bootstrap gathers required initial blocks
- After reaching target blocks, attempts SNAP sync transition with progress indicators
- If pivot block header not immediately available, retries up to 10 times with 2-second delays
- Successfully transitions to SNAP sync phases with clear confirmation
- OR falls back to fast sync with clear error message if pivot block remains unavailable

### Log Patterns to Verify

**Successful cold start (no retries needed):**
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

[Regular sync progress...]

================================================================================
✅ Bootstrap phase complete - transitioning to SNAP sync
================================================================================

================================================================================
🎯 SNAP Sync Ready
================================================================================
Pivot block: 1
State root: 0xabcd1234...
Beginning fast state sync with 16 concurrent workers
================================================================================
Starting account range sync with concurrency 16
```

**Retry scenario (pivot block not immediately available):**
```
================================================================================
✅ Bootstrap phase complete - transitioning to SNAP sync
================================================================================

⏳ Waiting for pivot block header to become available...
   Pivot block 1 not ready yet (attempt 1/10)
   Retrying in 2 seconds...

🔄 Retrying SNAP sync start after bootstrap delay...

================================================================================
🎯 SNAP Sync Ready
================================================================================
Pivot block: 1
State root: 0xabcd1234...
Beginning fast state sync with 16 concurrent workers
================================================================================
```

**Fallback scenario (should be extremely rare):**
```
⏳ Waiting for pivot block header to become available...
   Pivot block 1 not ready yet (attempt 10/10)
   Retrying in 2 seconds...

================================================================================
❌ Pivot block header not available after 10 retries
   SNAP sync cannot proceed - falling back to fast sync
================================================================================
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
