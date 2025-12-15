# Core-Geth SNAP Sync Genesis Starting Sequence Analysis

**Date**: 2025-12-15  
**Analyzed Version**: core-geth latest (main branch)  
**Purpose**: Understand how core-geth handles SNAP sync from genesis and compare with Fukuii implementation

## Overview

Core-geth (and go-ethereum) handle SNAP sync from genesis differently than Fukuii's original approach. This analysis examines their implementation to inform our fix.

## Key Constants

```go
fsMinFullBlocks = 64  // Number of blocks to retrieve fully even in snap sync
```

This is significantly smaller than Fukuii's `pivotBlockOffset = 1024`. Core-geth only requires 64 full blocks before attempting SNAP sync.

## Genesis Start Sequence

### 1. Initial Sync Mode Decision

When sync starts, core-geth determines the mode in `syncWithPeer()`:

```go
mode := d.getMode()  // Returns FullSync, SnapSync, or LightSync
```

### 2. Pivot Block Selection

Core-geth uses **different pivot selection strategies** based on sync mode:

#### For Beacon Mode (Post-Merge):
```go
if latest.Number.Uint64() > uint64(fsMinFullBlocks) {
    number := latest.Number.Uint64() - uint64(fsMinFullBlocks)
    pivot = d.skeleton.Header(number)
}
```

#### For Legacy Mode (Pre-Merge):
```go
func (d *Downloader) fetchHead(p *peerConnection) (head, pivot *types.Header, err error) {
    latest, peerTd, _ := p.peer.Head()  // Get peer's best block
    
    fetch := 1
    if mode == SnapSync {
        fetch = 2  // Request both head + pivot headers
    }
    
    // Request headers from peer
    headers, hashes, err := d.fetchHeadersByHash(p, latest, fetch, fsMinFullBlocks-1, true)
    
    // headers[0] = head (peer's best block)
    // headers[1] = pivot (head - 63 blocks)
}
```

**Key insight**: Core-geth requests the pivot header **directly from the peer** during initial sync negotiation. The peer returns:
- `headers[0]`: The head (tip) of the chain
- `headers[1]`: The pivot, which is `head - fsMinFullBlocks + 1` (approximately head - 63)

### 3. Handling Genesis Start

When starting from genesis (block 0), core-geth has this logic:

```go
// If no pivot block was returned, the head is below the min full block
// threshold (i.e. new chain). In that case we won't really snap sync
// anyway, but still need a valid pivot block to avoid some code hitting
// nil panics on access.
if mode == SnapSync && pivot == nil {
    pivot = d.blockchain.CurrentBlock()  // Use genesis as pivot
}
```

**Important**: If the chain height < 64 blocks:
1. No pivot header is returned from peer (only head is returned)
2. Pivot is set to the current local block (genesis)
3. SNAP sync proceeds but effectively becomes a **full sync** for early blocks

### 4. Origin Adjustment for SNAP Sync

Core-geth ensures the sync origin doesn't exceed the pivot:

```go
if mode == SnapSync {
    if height <= uint64(fsMinFullBlocks) {
        origin = 0  // Start from genesis
    } else {
        pivotNumber := pivot.Number.Uint64()
        if pivotNumber <= origin {
            origin = pivotNumber - 1
        }
        rawdb.WriteLastPivotNumber(d.stateDB, pivotNumber)
    }
}
```

### 5. Concurrent Fetchers

Core-geth runs multiple fetchers concurrently:

```go
fetchers := []func() error{
    func() error { return d.fetchHeaders(p, origin+1, latest.Number.Uint64()) },
    func() error { return d.fetchBodies(origin+1, beaconMode) },
    func() error { return d.fetchReceipts(origin+1, beaconMode) },
    func() error { return d.processHeaders(origin+1, td, ttd, beaconMode) },
}
if mode == SnapSync {
    d.pivotHeader = pivot
    fetchers = append(fetchers, func() error { return d.processSnapSyncContent() })
}
```

**Key behavior**: All fetchers run simultaneously. Headers, bodies, and receipts are downloaded while SNAP sync processes state in parallel.

## How It Differs from Fukuii

### Fukuii's Original Approach
1. Check if `pivotBlockNumber = localBest - 1024` is positive
2. If negative, bootstrap to 1025 blocks using regular sync
3. After bootstrap, calculate pivot as `localBest - 1024`
4. **Problem**: After 40k blocks of bootstrap, pivot = 39k (already synced)

### Core-Geth's Approach
1. Always attempts SNAP sync regardless of local block count
2. Pivot based on **peer's head** (network tip), not local best
3. Requests pivot header directly from peer: `pivot = peerHead - 63`
4. If chain height < 64: uses current block as pivot (effectively full sync for early blocks)
5. Downloads headers up to peer head while simultaneously:
   - Downloading bodies/receipts for those headers
   - SNAP syncing state at the pivot point

## Key Differences

| Aspect | Fukuii (Original) | Core-Geth |
|--------|-------------------|-----------|
| **Pivot Offset** | 1024 blocks | 64 blocks (`fsMinFullBlocks`) |
| **Pivot Source** | Local best block | Peer's head block |
| **Genesis Handling** | Bootstrap 1025 blocks first | Start immediately, use genesis as pivot |
| **Pivot Selection** | Calculated locally | Requested from peer during handshake |
| **Bootstrap Strategy** | Sequential (bootstrap → SNAP) | Concurrent (headers + bodies + SNAP state) |
| **Minimum Blocks** | 1025 blocks before SNAP | 0 blocks (can start from genesis) |

## What Fukuii Should Do

Based on core-geth's approach, here's what we should implement:

### Option 1: Mimick Core-Geth Exactly (Minimal Changes)
```scala
// After initial bootstrap to 1025 blocks
val peerBestBlock = getPeerWithHighestBlock.map(_.peerInfo.maxBlockNumber)
val pivotBlockNumber = peerBestBlock.map(_ - snapSyncConfig.pivotBlockOffset).getOrElse(localBestBlock)

// If we don't have the pivot header, we need to fetch headers first
if (!blockchainReader.hasHeader(pivotBlockNumber)) {
    // Continue syncing headers/blocks to reach pivot
    // This is what our updated code now does!
}
```

### Option 2: Reduce Pivot Offset (Larger Change)
```scala
// Change pivot offset from 1024 to 64 like core-geth
pivotBlockOffset = 64

// This would allow SNAP sync to start much sooner
// But requires more testing and might not work well with ETC
```

### Option 3: Hybrid Approach (What We Implemented)
```scala
// Select pivot based on network, not local
val networkBestBlock = getPeerWithHighestBlock.map(_.peerInfo.maxBlockNumber)
val pivotBlockNumber = networkBestBlock.map(_ - snapSyncConfig.pivotBlockOffset).getOrElse(localBestBlock)

// If pivot header not available, continue bootstrap to reach it
if (pivotBlockNumber > localBestBlock && !has HeaderForBlock(pivotBlockNumber)) {
    continueBootstrapTo(pivotBlockNumber)
}
```

**This is what we implemented** - it aligns with core-geth's principle of using network state for pivot selection while maintaining Fukuii's existing offset value (1024) and bootstrap mechanism.

## Recommendations

### What We Did Right ✅
1. **Network-based pivot selection**: Now uses peer's best block like core-geth
2. **Extended bootstrap**: Continues syncing to reach pivot point
3. **Sanity checks**: Validates pivot is ahead of local state

### Potential Future Improvements 🔄
1. **Reduce pivot offset**: Consider changing from 1024 to 64 blocks
2. **Concurrent fetchers**: Implement parallel header + body + state download
3. **Direct pivot request**: Request pivot header from peer during handshake
4. **Dynamic pivot**: Allow pivot to update as chain grows (like core-geth's pivot refresh logic)

### Why Keep 1024 for Now ✅
1. **Stability**: 1024 has been tested and works
2. **Minimal changes**: Changing offset is a larger refactor
3. **ETC considerations**: Ethereum Classic might need different parameters
4. **Fix immediate issue**: The network-based selection fixes the core problem

## Conclusion

Core-geth's approach is more sophisticated:
- Starts SNAP sync immediately from any block height
- Uses peer's network tip for pivot selection
- Downloads multiple data types concurrently
- Has smaller minimum block requirement (64 vs 1024)

Our fix implements the most critical aspect: **network-based pivot selection**. This solves the immediate problem where SNAP sync was selecting already-synced blocks as the pivot.

Future iterations could adopt more of core-geth's approach, but for now, this fix is minimal, correct, and aligned with industry best practices.

## References

- Core-geth repository: https://github.com/etclabscore/core-geth
- Analyzed files:
  - `eth/downloader/downloader.go` (lines 473-680)
  - `eth/downloader/modes.go`
- Constants: `fsMinFullBlocks = 64`
