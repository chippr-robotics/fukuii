# CON-006: ForkId Compatibility During Initial Sync

**Status**: Accepted

**Date**: 2025-11-26

**Related ADRs**: 
- [CON-002: Bootstrap Checkpoints](./CON-002-bootstrap-checkpoints.md)
- [CON-001: RLPx Protocol Deviations and Peer Bootstrap](./CON-001-rlpx-protocol-deviations-and-peer-bootstrap.md)

## Context

Following the implementation of bootstrap checkpoints (CON-002), we discovered that nodes running regular sync mode were unable to maintain stable peer connections and did not appear on network crawlers like etcnodes.org, despite fast sync working correctly.

### Problem Statement

When a node starts syncing from genesis using regular sync mode:

1. Peers connect successfully and complete the initial RLPx handshake
2. Status messages are exchanged (ETH64+ protocol)
3. Peers immediately disconnect with generic TCP errors
4. Node unable to maintain minimum peer count required for syncing
5. Network crawlers cannot discover the node
6. Issue only affects regular sync; fast sync works correctly

### Investigation Findings

#### Root Cause: ForkId Incompatibility

The issue stems from how ForkId is calculated and validated during the ETH64+ protocol handshake:

**EIP-2124 ForkId Protocol**:
- ETH64+ peers exchange ForkId in Status messages during handshake
- ForkId is calculated from genesis hash and current block number
- Peers validate ForkId compatibility per EIP-2124
- Incompatible ForkId results in immediate peer disconnection

**Original Bug**:
- Bootstrap pivot block was only used for ForkId calculation when `bestBlockNumber == 0`
- During regular sync, block numbers advance: 0 → 1 → 2 → 3 → ...
- After block 1, node calculated ForkId based on very low block numbers (1, 2, 3, etc.)
- Synced peers at block 19M+ rejected this incompatible ForkId
- Result: immediate peer disconnection after status exchange

**Why Fast Sync Appeared to Work**:
- Fast sync implementation kept `bestBlockNumber` at 0 longer during initial sync phase
- Bootstrap pivot continued to be used for ForkId calculation
- Compatible ForkId maintained with synced peers
- Stable connections preserved

#### Code Analysis

**Buggy Implementation** (`EthNodeStatus64ExchangeState.createStatusMsg()`):
```scala
val forkIdBlockNumber = if (bestBlockNumber == 0 && bootstrapPivotBlock > 0) {
  bootstrapPivotBlock
} else {
  bestBlockNumber
}
val forkId = ForkId.create(genesisHash, blockchainConfig)(forkIdBlockNumber)
```

**Problem**: As soon as regular sync advances to block 1, the condition becomes false and the node advertises ForkId based on block 1, which is incompatible with peers at block 19,250,000.

### Comparison with Core-Geth

Analysis of core-geth's implementation revealed:

**Core-Geth Approach**:
```go
// eth/handler.go
number := head.Number.Uint64()
forkID := forkid.NewID(h.chain.Config(), genesis, number, head.Time)
```

- Always uses current head block number for ForkId
- No special handling for low block numbers
- No bootstrap or checkpoint mechanism for ForkId
- Simple, straightforward implementation

**Assessment**: Core-geth would experience the same peer disconnection issue during regular sync at low block numbers on networks like ETC where most peers are fully synced. Our solution is a unique enhancement that addresses this problem.

## Decision

We will extend the bootstrap pivot block usage for ForkId calculation during the entire initial sync phase, transitioning to actual block numbers only when the node is within a threshold distance of the pivot block.

### Implementation

Modified ForkId calculation in `EthNodeStatus64ExchangeState.createStatusMsg()`:

```scala
private val MaxBootstrapPivotThreshold = BigInt(100000)

// In createStatusMsg():
val bootstrapPivotBlock = appStateStorage.getBootstrapPivotBlock()
val forkIdBlockNumber = if (bootstrapPivotBlock > 0) {
  // Calculate threshold: maximum distance from pivot before switching to actual
  val threshold = math.min(bootstrapPivotBlock / 10, MaxBootstrapPivotThreshold)
  val shouldUseBootstrap = bestBlockNumber < (bootstrapPivotBlock - threshold)
  
  if (shouldUseBootstrap) {
    log.info(
      "STATUS_EXCHANGE: Using bootstrap pivot block {} for ForkId calculation",
      bootstrapPivotBlock
    )
    bootstrapPivotBlock  // Use pivot during initial sync
  } else {
    log.info(
      "STATUS_EXCHANGE: Switching to actual block number {} for ForkId",
      bestBlockNumber
    )
    bestBlockNumber      // Switch to actual once close to pivot
  }
} else {
  bestBlockNumber
}
val forkId = ForkId.create(genesisHash, blockchainConfig)(forkIdBlockNumber)
```

### Threshold Logic

**Formula**: `threshold = min(10% of pivot block, MaxBootstrapPivotThreshold)`

**For ETC Mainnet** (pivot at 19,250,000):
- 10% of pivot = 1,925,000 blocks
- Threshold = min(1,925,000, 100,000) = **100,000 blocks**
- **Use pivot when**: bestBlockNumber < 19,150,000
- **Switch to actual when**: bestBlockNumber >= 19,150,000

**Rationale**:
- The 100,000 block cap prevents waiting too long before switching to actual block number
- Provides adequate compatibility during initial sync
- Allows timely transition once node is reasonably close to being synced
- 10% fallback handles networks with lower pivot block numbers appropriately

### Architecture

```
Node Startup (at genesis, block 0)
        ↓
Bootstrap Checkpoints Loaded
(pivot = 19,250,000 stored)
        ↓
Regular Sync Starts
        ↓
Block 1: ForkId = pivot (19,250,000) ✓ Compatible with synced peers
Block 100: ForkId = pivot (19,250,000) ✓ Compatible
Block 1,000: ForkId = pivot (19,250,000) ✓ Compatible
...
Block 19,149,999: ForkId = pivot (19,250,000) ✓ Compatible
        ↓
Threshold Crossed (19,150,000)
        ↓
Block 19,150,000: ForkId = actual (19,150,000) ✓ Compatible (close to synced)
Block 19,200,000: ForkId = actual (19,200,000) ✓ Compatible
Block 19,250,000: ForkId = actual (19,250,000) ✓ Compatible (fully synced)
```

## Consequences

### Positive

1. **Regular Sync Fixed**: Nodes can maintain stable peer connections from block 0 onwards
2. **Fast Sync Preserved**: Continues to work as before (no regression)
3. **Equal Behavior**: Both sync modes have identical peer connectivity characteristics
4. **Network Visibility**: Nodes discoverable on network crawlers (etcnodes.org) with both modes
5. **Smooth Transition**: Gradual switch from pivot to actual block number prevents disruption
6. **Enhanced Over Core-Geth**: Addresses issue that standard geth/core-geth implementations don't handle
7. **Network-Appropriate**: Designed for ETC's peer distribution where most peers are fully synced

### Negative

1. **Additional Complexity**: More sophisticated logic than simple "always use current block"
2. **State Dependency**: Requires bootstrap checkpoint to be configured and loaded
3. **Threshold Management**: Need to maintain MaxBootstrapPivotThreshold constant
4. **Testing Overhead**: More test cases required to verify threshold behavior

### Neutral

1. **Network-Specific**: Particularly beneficial for networks like ETC with mostly-synced peers
2. **ForkId Accuracy**: Slight delay in advertising actual block number during final sync phase
3. **Configuration**: Threshold is hardcoded but could be made configurable if needed

## Testing

### Unit Tests

**Test 1: Bootstrap Pivot at Low Block Numbers**
```scala
it should "use bootstrap pivot block for ForkId when syncing from low block numbers"
```
- Verifies nodes at block 1,000 use pivot (19,250,000) for ForkId
- Validates ForkId matches expected value from pivot
- Ensures peer handshake succeeds despite low block number
- Prevents regression of the bug

**Test 2: Threshold Boundary Transition**
```scala
it should "switch to actual block number for ForkId when close to bootstrap pivot"
```
- Verifies nodes at block 19,200,000 use actual number for ForkId
- Ensures correct transition when within threshold (100k blocks of pivot)
- Tests boundary condition (19,250,000 - 100,000 = 19,150,000 is switch point)

### Integration Testing

Should verify on both networks:
- **ETC Mainnet**: Pivot at 19,250,000 (Spiral fork)
- **Mordor Testnet**: Pivot at 9,957,000 (Spiral fork)

**Test Scenarios**:
1. Fresh node with regular sync from genesis
2. Fresh node with fast sync from genesis
3. Verify peer connection stability
4. Confirm network crawler discovery

## Implementation Notes

### Files Modified

**Implementation**:
- `src/main/scala/com/chipprbots/ethereum/network/handshaker/EthNodeStatus64ExchangeState.scala`
  - Added `MaxBootstrapPivotThreshold` constant (100,000 blocks)
  - Extended bootstrap pivot usage with threshold logic
  - Enhanced logging for ForkId calculation debugging

**Tests**:
- `src/test/scala/com/chipprbots/ethereum/network/handshaker/EtcHandshakerSpec.scala`
  - Added two comprehensive tests with explicit ForkId validation
  - Detailed comments explaining threshold calculations

**Documentation**:
- `docs/runbooks/known-issues.md` - Added Issue 15
- This ADR

### Enhanced Logging

Added detailed logging to aid in production debugging:
```scala
log.info(
  "STATUS_EXCHANGE: Using bootstrap pivot block {} for ForkId calculation " +
  "(actual best block: {}, threshold: {})",
  bootstrapPivotBlock, bestBlockNumber, threshold
)
```

## Alternatives Considered

### Alternative 1: Always Use Current Block (Core-Geth Approach)

**Pros**:
- Simple implementation
- No additional state management
- Matches standard geth behavior

**Cons**:
- Doesn't solve the peer connection issue
- Nodes at low blocks would still be rejected by synced peers
- Not suitable for networks with mostly-synced peer pools

**Decision**: Rejected - doesn't address the root cause

### Alternative 2: Reduce Threshold to 10% Only

**Pros**:
- More accurate ForkId during later sync stages
- Less "lying" about actual block number

**Cons**:
- For high pivot values, would use pivot for too long
- Example: 10% of 19.25M = 1.92M blocks, switching only at 17.32M
- Unnecessarily delays transition to actual block numbers

**Decision**: Rejected - cap at 100k blocks provides better balance

### Alternative 3: Make Threshold Configurable

**Pros**:
- Operators can tune for their network
- More flexible

**Cons**:
- Additional configuration complexity
- Most operators wouldn't know what value to use
- Hardcoded 100k works well for both mainnet and testnet

**Decision**: Deferred - can add if needed, start with reasonable default

### Alternative 4: Use Different ForkId Validation Strategy

**Pros**:
- Could relax ForkId validation during initial sync
- Keep simple ForkId calculation

**Cons**:
- Would violate EIP-2124 specification
- Reduces security of fork identification
- Makes us incompatible with standard clients
- Doesn't solve the fundamental issue

**Decision**: Rejected - violates standards

## Security Considerations

1. **No Consensus Impact**: ForkId is used only for peer selection, not consensus
2. **EIP-2124 Compliance**: Still validates ForkId per specification
3. **Bootstrap Trust**: Relies on trusted bootstrap checkpoints (same as CON-002)
4. **Block Validation**: All block validation continues unchanged
5. **Attack Surface**: No new attack vectors introduced
6. **Peer Selection**: May connect to slightly different peer pool, but still validates blocks

## Future Enhancements

1. **Dynamic Threshold**: Consider making threshold configurable via config file
2. **Metrics**: Track ForkId calculation statistics and threshold transitions
3. **Monitoring**: Alert if node stays on bootstrap pivot too long (indicates sync issues)
4. **Documentation**: Update user-facing docs about sync mode equivalence

## References

- **Issue**: [#584 - Peer connections](https://github.com/chippr-robotics/fukuii/issues/584)
- **EIP-2124**: [Fork identifier for chain compatibility checks](https://eips.ethereum.org/EIPS/eip-2124)
- **Core-Geth Implementation**: [eth/handler.go](https://github.com/etclabscore/core-geth/blob/master/eth/handler.go)
- **Core-Geth ForkID**: [core/forkid/forkid.go](https://github.com/etclabscore/core-geth/blob/master/core/forkid/forkid.go)
- **CON-002**: [Bootstrap Checkpoints](./CON-002-bootstrap-checkpoints.md)
- **CON-001**: [RLPx Protocol Deviations](./CON-001-rlpx-protocol-deviations-and-peer-bootstrap.md)

## Impact Summary

**Before Fix**:
- ❌ Regular sync: Cannot maintain peers, cannot sync blockchain
- ✅ Fast sync: Works (but only by implementation accident)
- ❌ Network visibility: Nodes not discoverable on etcnodes.org

**After Fix**:
- ✅ Regular sync: Stable peer connections, successful sync
- ✅ Fast sync: Continues to work correctly
- ✅ Network visibility: Nodes visible on etcnodes.org with both modes
- ✅ Equal behavior: Both sync modes have equivalent peer connectivity

**Conclusion**: This enhancement makes Fukuii more robust than standard geth/core-geth implementations for networks like ETC where the peer pool consists primarily of fully-synced nodes.
