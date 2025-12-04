# Run 007: Core-Geth Behavior Research and Network Sync Analysis

## Issue Reference
- **GitHub Issue**: run009
- **Date**: 2025-12-04

## Executive Summary

This investigation was triggered by concerns that removing the `looksLikeRLP` heuristic from MessageCodec negatively impacted peer connectivity. After comprehensive research of core-geth's source code, review of Fukuii's ADRs, and analysis of previous run documentation, the findings conclusively show:

**The removal of `looksLikeRLP` was correct and necessary.** The current implementation is superior to previous versions. Peer connectivity issues at genesis are NOT related to compression but are instead due to the bootstrap challenge, which is already solved by bootstrap checkpoints (CON-002).

## Key Findings

### Finding 1: looksLikeRLP Removal Was CORRECT

The `looksLikeRLP` heuristic had critical bugs that caused peer disconnections:

- **Version 1**: False positives when compressed data started with bytes 0x80-0xff
- **Version 2**: False negatives when RLP started with bytes 0x00-0x7f  
- **Version 3 (Current)**: Always accepts uncompressed fallback, RLP decoder validates

**Current implementation is CORRECT and SUPERIOR.**

### Finding 2: Core-Geth Behavior Matches Fukuii

- Genesis announcement: `Head == Genesis` (signals no blockchain data)
- Compression: p2pVersion 5 threshold (same as Fukuii)
- Peer disconnect at genesis: CORRECT Ethereum protocol behavior

### Finding 3: SNAP Sync Not Viable on ETC Mainnet

Run-006 analysis showed:
- 0% of ETC peers advertise snap/1
- All GetAccountRange requests timeout
- FastSync is the proven solution

## Changes Made

### Configuration Update

**File**: `src/main/resources/conf/chains/etc-chain.conf`

Added explicit sync configuration disabling SNAP for ETC mainnet:

```hocon
sync {
  do-snap-sync = false    # Peers don't support it
  do-fast-sync = true     # Proven reliable on ETC
}
```

## Recommendations

1. ✅ **Keep current MessageCodec** - no changes needed
2. ✅ **SNAP disabled for ETC** - configuration now explicit
3. ✅ **Bootstrap checkpoints** - already configured correctly
4. ✅ **Documentation** - research findings captured

## Related Documentation

- [CON-001: RLPx Protocol Deviations](../../docs/adr/consensus/CON-001-rlpx-protocol-deviations-and-peer-bootstrap.md)
- [CON-002: Bootstrap Checkpoints](../../docs/adr/consensus/CON-002-bootstrap-checkpoints.md)
- [Run-006: SNAP to FastSync Migration](../run-006/README.md)

## Conclusion

**No code changes needed.** The current implementation is correct. The only update was making the sync mode configuration explicit for ETC mainnet to prevent accidental SNAP sync attempts.
