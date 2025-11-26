# Core-Geth ForkId Strategy Comparison

**Date**: 2025-11-26  
**Context**: Analysis of core-geth's ForkId calculation during sync vs. Fukuii's bootstrap pivot approach  
**Purpose**: Assess effectiveness of Fukuii's bootstrap pivot strategy for peer connections

## Core-Geth Implementation

### ForkId Calculation During Handshake

In core-geth, the ForkId is always calculated using the current head block number, with no special handling for low block numbers or initial sync:

```go
// eth/handler.go - runEthPeer function
var (
    genesis = h.chain.Genesis()
    head    = h.chain.CurrentHeader()
    hash    = head.Hash()
    number  = head.Number.Uint64()  // Always uses current block
    td      = h.chain.GetTd(hash, number)
)
forkID := forkid.NewID(h.chain.Config(), genesis, number, head.Time)
if err := peer.Handshake(h.networkID, td, hash, genesis.Hash(), forkID, h.forkFilter); err != nil {
    // Handle error
}
```

**Key Points**:
- `number` is always the current head block number
- No bootstrap or checkpoint mechanism for ForkId
- No special handling during initial sync
- Simple, straightforward implementation

### ForkId Creation

```go
// core/forkid/forkid.go
func NewID(config ctypes.ChainConfigurator, genesis *types.Block, head, time uint64) ID {
    // Calculate the starting checksum from the genesis hash
    hash := crc32.ChecksumIEEE(genesis.Hash().Bytes())

    // Calculate the current fork checksum and the next fork block
    forksByBlock, forksByTime := gatherForks(config, genesis.Time())
    for _, fork := range forksByBlock {
        if fork <= head {
            // Fork already passed, checksum the previous hash and the fork number
            hash = checksumUpdate(hash, fork)
            continue
        }
        return ID{Hash: checksumToBytes(hash), Next: fork}
    }
    // ... (time-based forks)
    return ID{Hash: checksumToBytes(hash), Next: 0}
}
```

**Observation**: ForkId calculation is purely based on the provided `head` parameter with no adjustments.

## Fukuii Implementation

### ForkId Calculation with Bootstrap Pivot

Fukuii uses a more sophisticated approach during initial sync:

```scala
// EthNodeStatus64ExchangeState.scala
val bootstrapPivotBlock = appStateStorage.getBootstrapPivotBlock()
val forkIdBlockNumber = if (bootstrapPivotBlock > 0) {
  val threshold = math.min(bootstrapPivotBlock / 10, MaxBootstrapPivotThreshold)
  val shouldUseBootstrap = bestBlockNumber < (bootstrapPivotBlock - threshold)
  
  if (shouldUseBootstrap) {
    bootstrapPivotBlock  // Use pivot during initial sync
  } else {
    bestBlockNumber      // Switch to actual once close
  }
} else {
  bestBlockNumber
}
val forkId = ForkId.create(genesisHash, blockchainConfig)(forkIdBlockNumber)
```

**Key Points**:
- Uses bootstrap pivot (e.g., 19,250,000) during initial sync
- Transitions to actual block number when within threshold
- Prevents incompatible ForkId at low block numbers
- More complex but addresses specific peer connection issue

## Comparison Analysis

### Issue: Peer Disconnections at Low Block Numbers

**Scenario**: Node at block 1,000 trying to connect to peers at block 19,250,000

| Aspect | Core-Geth | Fukuii (Our Fix) |
|--------|-----------|------------------|
| **ForkId Calculation** | Uses block 1,000 | Uses bootstrap pivot (19,250,000) |
| **ForkId Compatibility** | ❌ Incompatible with synced peers | ✅ Compatible with synced peers |
| **Peer Connection** | ❌ Rejected by EIP-2124 validation | ✅ Accepted by peers |
| **Sync Ability** | ❌ Cannot maintain peers | ✅ Stable peer connections |
| **Network Visibility** | ❌ Not discoverable | ✅ Discoverable on crawlers |

### Why Core-Geth Might Not Face This Issue

Possible reasons core-geth doesn't experience this problem as severely:

1. **Fast Sync Default**: Core-geth may default to fast sync, which keeps block number at 0 longer
2. **Network Diversity**: Ethereum mainnet may have more peers at various sync stages
3. **Peer Pool Size**: Larger peer pool increases chance of finding compatible peers
4. **Bootstrap Node Strategy**: May connect to trusted bootstrap nodes that accept any ForkId

### Why Fukuii Needed This Fix

ETC network characteristics:

1. **Smaller Peer Pool**: Fewer total peers on ETC network
2. **Most Peers Synced**: Majority of peers are at current block height
3. **Regular Sync Default**: Regular sync was default, exposing the issue immediately
4. **Strict ForkId Validation**: Peers enforce EIP-2124 strictly

## Strategy Assessment

### Core-Geth Strategy
**Pros**:
- Simple and straightforward
- No additional state management
- Works on networks with diverse peer heights

**Cons**:
- Vulnerable to peer rejection at low blocks on networks with mostly-synced peers
- No mechanism to work around ForkId incompatibility during initial sync
- Relies on finding compatible peers or lenient validation

### Fukuii Strategy (Our Fix)
**Pros**:
- ✅ Guarantees ForkId compatibility during initial sync
- ✅ Works on networks with mostly-synced peers
- ✅ Enables both regular and fast sync equally
- ✅ Smooth transition to actual block numbers

**Cons**:
- More complex implementation
- Requires bootstrap checkpoint configuration
- Additional state management

## Conclusion

**Effectiveness Assessment**: ✅ **Our strategy is MORE effective** for the ETC network

1. **Addresses Root Cause**: Directly solves ForkId incompatibility at low blocks
2. **Superior to Core-Geth**: Handles case that core-geth doesn't address
3. **Network-Appropriate**: Designed for ETC's peer distribution characteristics
4. **Proven Solution**: Resolves the documented peer connection issue

**Recommendation**: **Keep our implementation** as it provides:
- Better reliability on ETC network
- Equal behavior between sync modes
- Guaranteed peer connectivity during initial sync
- Unique enhancement over standard geth/core-geth approach

## References

- [Core-Geth Source](https://github.com/etclabscore/core-geth)
- [Core-Geth Handshake](https://github.com/etclabscore/core-geth/blob/master/eth/handler.go)
- [Core-Geth ForkID](https://github.com/etclabscore/core-geth/blob/master/core/forkid/forkid.go)
- [EIP-2124: Fork identifier for chain compatibility checks](https://eips.ethereum.org/EIPS/eip-2124)
- [Fukuii Fix Documentation](./peer-connection-regular-sync-fix.md)
