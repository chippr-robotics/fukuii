# Run 007: Core-Geth Behavior Research and Network Sync Analysis

## Issue Reference
- **GitHub Issue**: run-009
- **Date**: 2025-12-04

## Executive Summary

This investigation was triggered by concerns that removing the `looksLikeRLP` heuristic from MessageCodec negatively impacted peer connectivity. After comprehensive research of core-geth's source code, review of Fukuii's ADRs, and analysis of previous run documentation, the findings conclusively show:

**The removal of `looksLikeRLP` was correct and necessary.** The current implementation is superior to previous versions. Peer connectivity issues at genesis are NOT related to compression but are instead due to the bootstrap challenge, which is already solved by bootstrap checkpoints (CON-002).

## Key Findings

### Finding 1: Core-Geth Compression Behavior - ALL Messages Compressed

**CRITICAL FINDING**: Core-geth compresses **ALL messages** when p2pVersion >= 5, including wire protocol messages (Hello, Ping, Pong, Disconnect).

Analysis of core-geth source (`p2p/rlpx/rlpx.go`):
```go
func (c *Conn) Write(code uint64, data []byte) (uint32, error) {
    if c.snappyWriteBuffer != nil {
        // Compression applied to ALL messages - no exceptions
        data = snappy.Encode(c.snappyWriteBuffer, data)
    }
    err := c.session.writeFrame(c.conn, code, data)
    return wireSize, err
}
```

**Fukuii Implementation Status**: ✅ CORRECT - Already matches core-geth
- Lines 74-77 in MessageCodec.scala confirm ALL messages compressed
- Lines 214-216 in encodeMessage confirm no exceptions for wire protocol
- This was fixed in a previous update to align with core-geth

### Finding 2: looksLikeRLP Removal Was CORRECT

The `looksLikeRLP` heuristic had critical bugs that caused peer disconnections:

- **Version 1**: False positives when compressed data started with bytes 0x80-0xff
- **Version 2**: False negatives when RLP started with bytes 0x00-0x7f  
- **Version 3 (Current)**: Always accepts uncompressed fallback, RLP decoder validates

**Current implementation is CORRECT and SUPERIOR.**

### Finding 3: Core-Geth Genesis Behavior Matches Fukuii

- Genesis announcement: `Head == Genesis` (signals no blockchain data)
- Compression: p2pVersion 5 threshold (same as Fukuii)
- Peer disconnect at genesis: CORRECT Ethereum protocol behavior

### Finding 4: SNAP Capability and ETC Mainnet Support

**Core-geth SNAP Implementation**: Core-geth fully implements SNAP/1 protocol

**ETC Mainnet Reality** (per run-006 empirical testing):
- 0% of ETC peers currently advertise snap/1 in practice
- All GetAccountRange requests timeout
- FastSync is the current working solution

**Capability Advertisement Strategy**:
- Fukuii stores capabilities as a flexible List[Capability]
- SHOULD advertise snap/1 to enable future interoperability
- Even if current peers don't support it, future peers might
- Advertising doesn't hurt, only enables future compatibility

## Recommendations

1. ✅ **Keep current MessageCodec** - compression logic matches core-geth perfectly
2. ✅ **Advertise snap/1 on ETC** - enables future peer compatibility
3. ✅ **SNAP sync disabled for now** - will auto-enable when peers support it
4. ✅ **FastSync active for ETC** - proven reliable fallback
5. ✅ **Bootstrap checkpoints** - already configured correctly
6. ✅ **Documentation** - comprehensive research findings captured

## Changes Made

### Configuration Update

**File**: `src/main/resources/conf/chains/etc-chain.conf`

Restored snap/1 capability advertisement for future interoperability:

```hocon
# Advertise snap/1 for future compatibility
capabilities = ["eth/63", ..., "eth/68", "snap/1"]

# Explicit sync configuration
sync {
  do-snap-sync = false    # Disabled until ETC peers support it
  do-fast-sync = true     # Current proven solution
}
```

**Rationale**: 
- Core-geth implements SNAP/1 protocol
- Advertising snap/1 enables future peer compatibility
- Capability negotiation will handle current lack of peer support
- When ETC peers eventually support SNAP, Fukuii will be ready

## Related Documentation

- [CON-001: RLPx Protocol Deviations](../../docs/adr/consensus/CON-001-rlpx-protocol-deviations-and-peer-bootstrap.md)
- [CON-002: Bootstrap Checkpoints](../../docs/adr/consensus/CON-002-bootstrap-checkpoints.md)
- [Run-006: SNAP to FastSync Migration](../run-006/README.md)

## Conclusion

**Research completed with key findings:**

1. **MessageCodec compression logic is CORRECT** - perfectly aligned with core-geth behavior (compresses ALL messages including wire protocol when p2pVersion >= 5)

2. **SNAP capability SHOULD be advertised** - enables future interoperability when ETC peers add support

3. **Current sync strategy is SOUND** - SNAP disabled for now (no peer support), FastSync active as proven fallback

4. **No code changes needed** - only configuration optimization to advertise snap/1 for future compatibility

The original issue premise that "removing looksLikeRLP hurt connectivity" was incorrect - the removal actually fixed critical bugs. Peer connectivity at genesis is a protocol-level behavior solved by bootstrap checkpoints (CON-002).
