# Sync Strategy and Automatic Switching

This document explains how Fukuii handles synchronization and answers questions about automatic sync switching behavior raised in run-002.

## Sync Modes in Fukuii

Fukuii supports three sync modes:

1. **SNAP Sync** (fastest) - Downloads recent state snapshot without intermediate Merkle trie nodes
2. **Fast Sync** (medium) - Validates recent blocks and downloads state
3. **Regular/Full Sync** (slowest but most reliable) - Validates all blocks from genesis

## Sync Mode Selection Logic

The sync mode is determined at startup based on configuration and sync state in `SyncController.scala`:

```scala
(appStateStorage.isSnapSyncDone(), appStateStorage.isFastSyncDone(), doSnapSync, doFastSync) match {
  case (false, _, true, _) =>
    startSnapSync()
  case (true, _, true, _) =>
    log.warning("do-snap-sync is true but SNAP sync already completed")
    startRegularSync()
  case (_, false, false, true) =>
    startFastSync()
  case (_, true, false, true) =>
    log.warning("do-fast-sync is true but fast sync already completed")
    startRegularSync()
  // ... other cases
}
```

## Automatic Sync Switching Behavior

### Current Behavior

**Answer to run-002 question**: No, Fukuii does **not** automatically switch from fast sync to snap sync based on block count.

The sync mode is determined **once at startup** based on:
- Configuration flags (`do-snap-sync`, `do-fast-sync`)
- Persistent sync state (whether sync was previously completed)

### Transitions That Do Occur

1. **SNAP Sync → Regular Sync** (automatic)
   - When: SNAP sync completes successfully
   - Trigger: `SNAPSyncController.Done` message
   - Implementation: `SyncController.runningSnapSync()`

2. **SNAP Sync → Fast Sync** (automatic fallback)
   - When: SNAP sync fails repeatedly (exceeds `max-snap-sync-failures`)
   - Trigger: `SNAPSyncController.FallbackToFastSync` message
   - Implementation: `SyncController.runningSnapSync()`

3. **Fast Sync → Regular Sync** (automatic)
   - When: Fast sync completes successfully
   - Trigger: `FastSync.Done` message
   - Implementation: `SyncController.runningFastSync()`

### What Does NOT Happen

- ❌ Fast Sync does **not** automatically switch to SNAP Sync mid-sync
- ❌ Regular Sync does **not** switch to Fast/SNAP Sync based on peer availability
- ❌ No dynamic mode switching based on:
  - Number of blocks synced
  - Number of peers available
  - Network conditions

## Run-002 Observation

> "Once the node retrieved enough blocks to begin snap sync it did not automatically switch from fast sync to snap"

This is **expected behavior**. Once a sync mode starts, it continues until completion or failure. The node will not switch from fast sync to snap sync mid-operation.

## Recommendations for Run-003

### If You Want SNAP Sync

Ensure the configuration has:
```hocon
fukuii {
  sync {
    do-snap-sync = true
    do-fast-sync = true  # Kept as fallback
  }
}
```

And ensure no persistent sync state exists from a previous fast sync run. You can:
- Start with a fresh data directory, or
- Delete the fast sync state (local installation): `rm -rf ~/.fukuii/etc/fast-sync-state.rlp`
- For Docker deployments: `docker compose down -v` to remove volumes with sync state

### Restart After Initial Sync

If you want to switch sync modes:
1. Stop the node
2. Clear sync state if needed
3. Adjust configuration
4. Restart - the new mode will be selected at startup

## How Geth Handles This

Based on our investigation of Geth's behavior:

### Geth's Approach
- **Snap sync is the default** when syncing from scratch
- Automatically falls back to full sync when snap sync completes or fails
- Like Fukuii, **does not switch mid-sync** from full to snap
- Snap sync from genesis is supported but not recommended (uses pivot block offset)

### Key Differences
- Geth defaults to snap sync; Fukuii requires explicit configuration
- Both use pivot block selection for snap sync
- Both transition to full sync after snap/fast sync completes

## SNAP Sync from Genesis

Regarding the run-002 question: "We should check if geth has any special behavior for snap syncing from genesis"

### Geth's Behavior
- Geth **supports** snap sync from genesis
- Uses a pivot block offset (default: 1024 blocks before chain tip)
- Even at block 0, it will wait for the chain to advance before starting snap sync

### Fukuii's Behavior
- Fukuii **also supports** snap sync from genesis
- Uses configurable `pivot-block-offset` (default: 1024 blocks)
- Has special handling in `BootstrapCheckpointLoader` for known fork blocks
- If starting from genesis (block 0), will use bootstrap checkpoints when available

### Configuration
```hocon
sync {
  snap-sync {
    pivot-block-offset = 1024
  }
}
```

## Future Enhancement: Dynamic Sync Switching

The run-002 feedback suggests this might be a desirable feature. Here's what it would involve:

### Potential Implementation
```scala
class AdaptiveSyncController {
  def shouldSwitchSyncMode(conditions: NetworkConditions): Option[SyncStrategy] = {
    // Monitor conditions and suggest mode switch
    if (conditions.availablePeerCount > 10 && currentMode == FastSync) {
      Some(SnapSync)  // Switch to faster mode
    } else if (conditions.availablePeerCount < 3 && currentMode == SnapSync) {
      Some(FastSync)  // Fall back to more reliable mode
    } else {
      None
    }
  }
}
```

### Benefits
- Better resource utilization
- Automatic optimization based on network conditions
- Faster sync in good conditions, reliability in poor conditions

### Challenges
- State transition complexity
- Risk of sync progress loss during switch
- Additional testing required for all transition paths

### Status
- Not currently implemented
- Would require significant architectural changes
- Consider this a feature request for future development

## Monitoring Sync Progress

To monitor which sync mode is active:

```bash
# Check logs for sync mode
docker compose logs fukuii | grep -i "starting.*sync"

# Example outputs:
# "Starting SNAP sync mode"
# "Trying to start block synchronization (fast mode)"
# "Starting regular sync"
```

## Related Documentation

- [SyncController.scala](../../src/main/scala/com/chipprbots/ethereum/blockchain/sync/SyncController.scala)
- [AdaptiveSyncStrategy.scala](../../src/main/scala/com/chipprbots/ethereum/blockchain/sync/AdaptiveSyncStrategy.scala)
- [SNAP Sync Configuration](../../src/main/resources/conf/base.conf) (lines 366-416)
- [Bootstrap Checkpoints ADR](../../docs/adr/consensus/CON-002-bootstrap-checkpoints.md)
