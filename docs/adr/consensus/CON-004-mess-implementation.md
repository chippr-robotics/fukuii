# ADR-016: MESS (Modified Exponential Subjective Scoring) Implementation

**Status**: Accepted

**Date**: 2025-11-16

## Context

Ethereum Classic nodes currently use pure objective consensus based on total difficulty to determine the canonical chain. This approach, while mathematically sound, is vulnerable to certain attack vectors, particularly long-range reorganization attacks where an attacker with historical mining power could attempt to create an alternative chain history that honest nodes might accept.

### Problem

The current consensus mechanism in Fukuii uses `ChainWeight` which is calculated based on:
1. Last checkpoint number
2. Total difficulty (sum of all block difficulties in the chain)

This purely objective approach has limitations:
- **Long-Range Attack Vulnerability**: An attacker who controlled significant mining power in the past could secretly mine an alternative chain and later release it, potentially causing a deep reorganization.
- **Eclipse Attack Amplification**: Nodes that are isolated from the network could be fed malicious chains that appear valid based solely on total difficulty.
- **No Time Awareness**: The current system doesn't consider when blocks were first observed, treating all blocks equally regardless of when they arrive.

### Background on MESS

Modified Exponential Subjective Scoring (MESS) is a consensus enhancement proposed for Ethereum Classic in ECIP-1097/ECBP-1100 (https://github.com/ethereumclassic/ECIPs/pull/373) and implemented in core-geth. MESS adds a subjective component to consensus by:

1. **Tracking First-Seen Time**: Recording when each block is first observed by the node
2. **Applying Time-Based Penalty**: Penalizing blocks that arrive late using an exponential decay function
3. **Protecting Against Long-Range Attacks**: Making it extremely difficult for attackers to create alternative histories that would be accepted

The core principle is that honest nodes will have seen the canonical chain blocks first, while attack chains will arrive later and be heavily penalized.

## Decision

We will implement MESS in Fukuii as an optional consensus enhancement with the following design:

### Architecture

```
┌─────────────────────────────┐
│   Block Reception           │
│   (via P2P network)        │
└─────────┬───────────────────┘
          │
          ▼
┌─────────────────────────────┐
│  BlockFirstSeenTracker      │
│  - Record timestamp         │
│  - Store in database        │
└─────────┬───────────────────┘
          │
          ▼
┌─────────────────────────────┐
│   MESSScorer                │
│   - Calculate penalty       │
│   - Apply to chain weight   │
└─────────┬───────────────────┘
          │
          ▼
┌─────────────────────────────┐
│   Consensus Evaluation      │
│   - Compare weighted chains │
│   - Select canonical chain  │
└─────────────────────────────┘
```

### Implementation Components

#### 1. Block First-Seen Storage

**New Storage Layer**: `BlockFirstSeenStorage`
- Stores mapping of block hash → first seen timestamp
- Persists to RocksDB for durability across restarts
- Provides efficient lookup by block hash

```scala
trait BlockFirstSeenStorage {
  def put(blockHash: ByteString, timestamp: Long): Unit
  def get(blockHash: ByteString): Option[Long]
  def remove(blockHash: ByteString): Unit
}
```

#### 2. MESS Scoring Algorithm

**New Component**: `MESSScorer`
- Calculates time-based penalty for blocks
- Applies exponential decay function
- Returns adjusted chain weight

**Formula**:
```
messWeight = difficulty * exp(-lambda * timeDelta)

where:
  lambda = decay constant (configurable, default: 0.0001 per second)
  timeDelta = max(0, currentTime - firstSeenTime)
  
For chains:
  chainMessWeight = sum(messWeight for each block)
```

**Penalty Characteristics**:
- Blocks seen immediately: no penalty (exp(0) = 1.0)
- Blocks delayed by 1 hour: ~30% penalty (exp(-0.36) ≈ 0.70, retains 70%)
- Blocks delayed by 6 hours: ~88.5% penalty (exp(-2.16) ≈ 0.115, retains 11.5%)
- Blocks delayed by 24 hours: ~99.98% penalty (exp(-8.64) ≈ 0.00018, retains 0.02%)

#### 3. Configuration

**Config Path**: `fukuii.consensus.mess`

```hocon
fukuii {
  consensus {
    mess {
      # Enable MESS scoring (default: false for backward compatibility)
      enabled = false
      
      # Decay constant (lambda) in the exponential function
      # Higher values = stronger penalties for delayed blocks
      # Default: 0.0001 per second
      decay-constant = 0.0001
      
      # Maximum time delta to consider (in seconds)
      # Blocks older than this are treated as having this age
      # Default: 30 days (2592000 seconds)
      max-time-delta = 2592000
      
      # Minimum MESS weight multiplier (prevents weights from going to zero)
      # Default: 0.0001 (0.01%)
      min-weight-multiplier = 0.0001
    }
  }
}
```

**CLI Override**:
- `--enable-mess` or `--mess-enabled`: Enable MESS regardless of config
- `--disable-mess` or `--no-mess`: Disable MESS regardless of config
- `--mess-decay-constant <value>`: Override decay constant

#### 4. ChainWeight Enhancement

**Modified**: `ChainWeight` class
- Add optional MESS score field
- Maintain backward compatibility with non-MESS weights
- Update comparison logic to use MESS score when enabled

```scala
case class ChainWeight(
    lastCheckpointNumber: BigInt,
    totalDifficulty: BigInt,
    messScore: Option[BigInt] = None  // New field
) extends Ordered[ChainWeight] {

  override def compare(that: ChainWeight): Int = {
    // If both have MESS scores, use those
    // Otherwise fall back to original comparison
    (this.messScore, that.messScore) match {
      case (Some(thisScore), Some(thatScore)) =>
        (this.lastCheckpointNumber, thisScore)
          .compare((that.lastCheckpointNumber, thatScore))
      case _ =>
        this.asTuple.compare(that.asTuple)
    }
  }
}
```

#### 5. Integration Points

**BlockBroadcast Reception**:
- When new block is received, check if first-seen time exists
- If not, record current timestamp
- Pass to consensus evaluation with MESS scoring if enabled

**Consensus Evaluation**:
- `ConsensusImpl.evaluateBranch`: Apply MESS scoring when comparing branches
- Use `MESSScorer` to calculate adjusted weights
- Compare using enhanced ChainWeight with MESS scores

**Block Import**:
- Record first-seen time for all imported blocks
- Persist to storage before block processing
- Handle edge cases (genesis block, checkpoint blocks)

### Testing Strategy

#### Unit Tests

1. **MESSScorer Tests**:
   - Test exponential decay function with various time deltas
   - Test edge cases (zero time, very large times, negative times)
   - Test configuration parameter effects
   - Test min weight multiplier enforcement

2. **BlockFirstSeenStorage Tests**:
   - Test put/get/remove operations
   - Test persistence across restarts
   - Test concurrent access patterns
   - Test cleanup of old entries

3. **ChainWeight Tests**:
   - Test MESS score comparison logic
   - Test backward compatibility with non-MESS weights
   - Test mixing MESS and non-MESS weights

#### Integration Tests

1. **Consensus Tests**:
   - Test branch selection with MESS enabled
   - Test that recent chain beats old chain with same difficulty
   - Test that sufficiently high difficulty overcomes time penalty
   - Test checkpoint interaction with MESS

2. **Network Sync Tests**:
   - Test fast sync with MESS
   - Test regular sync with MESS
   - Test peer selection based on MESS-weighted chains

3. **Configuration Tests**:
   - Test enabling/disabling MESS via config
   - Test CLI overrides
   - Test parameter adjustments

4. **Attack Scenario Tests**:
   - Simulate long-range attack (old chain revealed late)
   - Simulate eclipse attack (isolated node receives delayed chain)
   - Verify MESS prevents acceptance of attack chains

### Best Practices from core-geth

Based on the core-geth implementation and Ethereum Classic community discussions:

1. **Conservative Default**: MESS is disabled by default to maintain backward compatibility and allow gradual adoption
2. **Configurable Parameters**: Allow node operators to tune decay constant based on network conditions
3. **Persistent Storage**: First-seen times must be persistent to maintain protection across restarts
4. **Genesis Block Handling**: Genesis block always has first-seen time = 0 or its timestamp
5. **Checkpoint Interaction**: MESS scoring respects checkpoint-based chain weight (checkpoints take precedence)
6. **Monitoring**: Expose MESS-related metrics for observability

## Consequences

### Positive

1. **Enhanced Security**: Protection against long-range reorganization attacks
2. **Eclipse Attack Mitigation**: Isolated nodes are more resistant to being fed malicious chains
3. **Subjective Finality**: Nodes develop stronger confidence in blocks they've seen for longer
4. **Configurable**: Can be disabled if issues arise or for testing
5. **Backward Compatible**: Doesn't break existing consensus when disabled
6. **Community Alignment**: Follows ECIP proposal and core-geth implementation
7. **Metrics**: New observability into consensus behavior

### Negative

1. **Subjective Component**: Different nodes may have different views based on when they saw blocks
   - **Mitigation**: Only affects edge cases with competing chains; normal operation unaffected
   - **Mitigation**: Checkpoints provide objective anchors

2. **Storage Overhead**: Need to persist first-seen timestamps for all blocks
   - **Mitigation**: Relatively small data (8 bytes per block)
   - **Mitigation**: Can implement cleanup for very old blocks

3. **Clock Dependency**: Requires reasonably accurate node clocks
   - **Mitigation**: Modern systems have NTP; clock drift is minimal
   - **Mitigation**: Configurable time tolerances

4. **Complexity**: Adds another dimension to consensus logic
   - **Mitigation**: Well-encapsulated in dedicated components
   - **Mitigation**: Comprehensive test coverage

5. **Network Latency Considerations**: Honest nodes with poor connectivity could be disadvantaged
   - **Mitigation**: Decay constant tuned to only penalize very late blocks (hours/days)
   - **Mitigation**: Normal network latency (seconds) has negligible impact

6. **Restart Behavior**: Node restarts don't reset first-seen times (by design)
   - **Mitigation**: This is intentional and correct behavior
   - **Note**: Protects against attacker exploiting node restarts

### Security Considerations

1. **Clock Attacks**: Attacker manipulating node's clock
   - **Mitigation**: Requires system-level compromise; NTP protects against this
   - **Note**: If attacker controls system clock, many other attacks are possible

2. **Storage Exhaustion**: Attacker sending many blocks to fill storage
   - **Mitigation**: Only store for blocks that pass basic validation
   - **Mitigation**: Implement cleanup policy for very old blocks

3. **Parameter Tuning**: Incorrect decay constant could weaken security
   - **Mitigation**: Use well-tested default from core-geth
   - **Mitigation**: Document parameter effects clearly

## Alternatives Considered

### 1. Pure Checkpoint-Based Finality
- **Pros**: Objective, well-understood, no clock dependency
- **Cons**: Requires coordinated checkpoint updates, less flexible
- **Decision**: Use both; checkpoints and MESS complement each other

### 2. Finality Gadget (Casper FFG)
- **Pros**: Strong finality guarantees
- **Cons**: Requires proof-of-stake, major protocol change
- **Decision**: Out of scope; MESS is lighter-weight enhancement

### 3. Time-to-Live for Reorganizations
- **Pros**: Simple to understand and implement
- **Cons**: Hard cutoff is less nuanced than exponential decay
- **Decision**: MESS's exponential function is more flexible

### 4. No Change (Status Quo)
- **Pros**: No implementation cost, no new risks
- **Cons**: Remains vulnerable to long-range attacks
- **Decision**: MESS provides meaningful security improvement

## Implementation Plan

### Phase 1: Core Infrastructure (Week 1-2)
- [x] Create `BlockFirstSeenStorage` trait and RocksDB implementation
- [ ] Add storage initialization in node startup
- [x] Create unit tests for storage layer
- [x] Update `BlockchainConfig` with MESS configuration

### Phase 2: MESS Scoring (Week 2-3)
- [x] Implement `MESSScorer` with exponential decay function
- [x] Create unit tests for scoring algorithm
- [x] Add configuration parsing and validation
- [ ] Implement CLI flag support

### Phase 3: Consensus Integration (Week 3-4)
- [x] Enhance `ChainWeight` with MESS score support
- [ ] Update `ConsensusImpl` to use MESS scoring when enabled
- [ ] Modify block reception to record first-seen times
- [ ] Update chain comparison logic

### Phase 4: Testing (Week 4-5)
- [x] Create integration tests for MESS-enabled consensus
- [x] Test attack scenario simulations
- [ ] Test configuration and CLI overrides
- [x] Test backward compatibility (MESS disabled)

### Phase 5: Documentation and Metrics (Week 5-6)
- [x] Document MESS configuration in runbooks
- [ ] Add MESS metrics (Prometheus/Micrometer)
- [x] Update architecture documentation
- [x] Create user guide for MESS feature

### Phase 6: Validation (Week 6)
- [x] Code review
- [x] Security analysis (CodeQL)
- [ ] Performance testing
- [ ] Testnet deployment and monitoring

## References

- **ECIP-1097/ECBP-1100**: https://github.com/ethereumclassic/ECIPs/pull/373
- **core-geth Implementation**: https://github.com/etclabscore/core-geth
- **Related ADRs**:
  - [ADR-012: Bootstrap Checkpoints](012-bootstrap-checkpoints.md) - Complementary security enhancement
  - [ADR-013: Block Sync Improvements](013-block-sync-improvements.md) - Related sync mechanism work

## Rollout Strategy

1. **Development**: Implement with MESS disabled by default
2. **Testing**: Enable on private testnet for validation
3. **Mordor Testnet**: Deploy and monitor on Mordor with MESS enabled
4. **Community Review**: Share findings and gather feedback
5. **Mainnet Release**: Include in release with MESS disabled by default
6. **Documentation**: Publish operator guide for enabling MESS
7. **Gradual Adoption**: Encourage operators to enable after testing
8. **Future**: Consider enabling by default in future release after adoption

## Success Criteria

1. **Functional**: MESS correctly penalizes late-arriving blocks
2. **Performance**: No significant impact on sync speed or block processing
3. **Compatibility**: Works correctly with MESS enabled and disabled
4. **Security**: Passes attack scenario simulations
5. **Observability**: Metrics allow monitoring of MESS behavior
6. **Documentation**: Clear guides for operators
7. **Testing**: >90% test coverage for MESS components
