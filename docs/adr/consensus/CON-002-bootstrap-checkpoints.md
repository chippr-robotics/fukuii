# ADR-012: Bootstrap Checkpoints for Improved Initial Sync

**Status**: Accepted

**Date**: 2025-11-06

## Context

The Fukuii node currently requires waiting for at least 3 peers to perform a "pivot sync" (fast sync) when starting with an empty blockchain database. This is implemented in `PivotBlockSelector` which requires `minPeersToChoosePivotBlock` (default 3) peers to be available before it can select a pivot block and begin syncing.

### Problem

When a node starts for the first time with no blockchain data:

1. The node must wait for peer discovery to find and connect to at least 3 peers
2. Only after establishing 3 peer connections can the pivot block selection process begin
3. The pivot block selector must query these peers to determine the best block to use as a sync starting point
4. This process results in suboptimal initial sync times, especially when:
   - Network connectivity is poor
   - Bootstrap nodes are slow to respond
   - The node is behind a restrictive firewall
   - There are few peers available on the network

The current implementation in `PivotBlockSelector.scala` shows this logic:

```scala
if (election.hasEnoughVoters(minPeersToChoosePivotBlock)) {
  // Can proceed with pivot block selection
} else {
  log.info(
    "Cannot pick pivot block. Need at least {} peers, but there are only {} which meet the criteria",
    minPeersToChoosePivotBlock,
    correctPeers.size
  )
  retryPivotBlockSelection(currentBestBlockNumber)
}
```

### Network Impact

This affects both:
- **ETC Mainnet**: Production network where reliable initial sync is critical
- **Mordor Testnet**: Development network where quick setup is important for testing

## Decision

We will implement a bootstrap checkpoint system that provides trusted block references at known heights, allowing nodes to begin syncing immediately without waiting for peer consensus.

### Implementation Details

1. **Bootstrap Checkpoint Structure**
   - Create `BootstrapCheckpoint` case class containing:
     - `blockNumber: BigInt` - The height of the trusted checkpoint
     - `blockHash: ByteString` - The hash of the block at that height
   - Checkpoints are configured in network chain configuration files

2. **Configuration**
   - Add `bootstrap-checkpoints` list to chain configuration files (`etc-chain.conf`, `mordor-chain.conf`)
   - Add `use-bootstrap-checkpoints` boolean flag (default: `true`)
   - Format: `"blockNumber:blockHash"` strings that are parsed at startup

3. **Checkpoint Selection Strategy**
   - Use major fork activation blocks as checkpoints:
     - **ETC Mainnet**: Spiral (19,250,000), Mystique (14,525,000), Magneto (13,189,133), Phoenix (10,500,839)
     - **Mordor**: Spiral (9,957,000), Mystique (5,520,000), Magneto (3,985,893), ECIP-1099 (2,520,000)
   - These blocks are well-known, widely accepted, and unlikely to be reorganized

4. **Loading Process**
   - Create `BootstrapCheckpointLoader` that runs after genesis data loading
   - Only loads checkpoints if:
     - Feature is enabled (`use-bootstrap-checkpoints = true`)
     - Database only contains genesis block (best block number = 0)
     - Network has configured checkpoints
   - Checkpoints serve as trusted reference points for sync logic

5. **CLI Override**
   - Add `--force-pivot-sync` command-line flag
   - When specified, sets `use-bootstrap-checkpoints = false`
   - Allows operators to opt into traditional pivot sync behavior if needed

6. **Integration Points**
   - `BlockchainConfig` extended with checkpoint fields
   - `NodeBuilder` includes new `BootstrapCheckpointLoaderBuilder` trait
   - `StdNode` calls checkpoint loader during initialization
   - Sync controller can reference checkpoints when available

### Architecture

```
┌─────────────────┐
│  Node Startup   │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ Load Genesis    │
└────────┬────────┘
         │
         ▼
┌─────────────────────────────┐
│ Load Bootstrap Checkpoints  │
│ (if enabled & DB empty)     │
└────────┬────────────────────┘
         │
         ▼
┌─────────────────┐
│  Start Sync     │
│  - Use checkpoints as │
│    reference points   │
│  - No peer wait needed│
└─────────────────┘
```

## Consequences

### Positive

1. **Faster Initial Sync**: Nodes can begin syncing immediately without waiting for 3 peers
2. **Improved Reliability**: Less dependent on network conditions and peer availability
3. **Better User Experience**: New node operators see sync progress much sooner
4. **Reduced Network Load**: Fewer unnecessary peer connection attempts during startup
5. **Testnet Efficiency**: Developers can set up test environments faster
6. **Configurable**: Can be disabled if traditional behavior is preferred
7. **Safe**: Uses well-known fork blocks that are universally accepted
8. **Backward Compatible**: Existing nodes continue to work; feature is opt-in via config

### Negative

1. **Trust Assumption**: Relies on hardcoded block hashes being correct
   - Mitigated by: Using widely-known fork activation blocks
   - Mitigated by: Block hashes can be verified against multiple sources
   - Mitigated by: Blocks are still validated during sync

2. **Configuration Maintenance**: Checkpoint hashes must be updated as network progresses
   - Mitigated by: Using fork blocks which don't change
   - Mitigated by: New checkpoints added in major releases
   - Future: Could fetch from trusted checkpoint service

3. **Storage**: Minimal - only stores checkpoint metadata in memory during startup

4. **Complexity**: Adds another initialization step and configuration options
   - Mitigated by: Clean separation of concerns with dedicated loader class
   - Mitigated by: Comprehensive logging for observability

### Security Considerations

1. **Checkpoint Verification**: Block hashes must be obtained from trusted sources
2. **Fork Protection**: Using major fork blocks ensures network-wide consensus
3. **Validation**: Sync process still validates all blocks; checkpoints are just starting hints
4. **Override Available**: `--force-pivot-sync` allows operators to bypass if suspicious

## Alternatives Considered

### 1. Reduce `minPeersToChoosePivotBlock` to 1
- **Pros**: Simple configuration change
- **Cons**: Less reliable, more prone to malicious peers, still requires peer wait

### 2. Implement Checkpoint Sync Service
- **Pros**: Dynamic, always up-to-date
- **Cons**: Adds external dependency, network failure point, more complex

### 3. Bundle Recent Blockchain Snapshot
- **Pros**: Even faster initial sync
- **Cons**: Large file size, requires frequent updates, security risks

### 4. DNS-Based Checkpoint Discovery
- **Pros**: Automatic updates
- **Cons**: DNS dependency, potential for DNS attacks, complexity

## References

- **Issue**: Bootstrap problem - network unable to sync due to peer wait requirement
- **Related**: [ADR-011: RLPx Protocol Deviations and Peer Bootstrap Challenge](011-rlpx-protocol-deviations-and-peer-bootstrap.md)
- **Ethereum Classic ECIPs**:
  - [ECIP-1088: Phoenix](https://ecips.ethereumclassic.org/ECIPs/ecip-1088)
  - [ECIP-1103: Magneto](https://ecips.ethereumclassic.org/ECIPs/ecip-1103)
  - [ECIP-1104: Mystique](https://ecips.ethereumclassic.org/ECIPs/ecip-1104)
  - [ECIP-1109: Spiral](https://ecips.ethereumclassic.org/ECIPs/ecip-1109)

## Implementation Notes

### Block Hash Verification

The checkpoint block hashes must be verified before being added to the configuration. This can be done by:

1. Querying multiple trusted ETC block explorers
2. Running a fully-synced node and extracting the hashes
3. Comparing with other ETC client implementations (core-geth, besu)
4. Verifying against the ETC community resources

### Future Enhancements

1. **Automatic Checkpoint Updates**: Periodically fetch latest trusted checkpoints from a service
2. **Multiple Checkpoint Sources**: Support fetching from multiple sources for redundancy
3. **Checkpoint Validation**: Add cryptographic signatures from trusted authorities
4. **Progress Tracking**: Show sync progress relative to checkpoints in UI/logs
5. **Smart Checkpoint Selection**: Choose checkpoint based on network conditions and age

## Testing Strategy

1. **Unit Tests**: Test checkpoint parsing, loading, and configuration
2. **Integration Tests**: Verify checkpoint loading doesn't break existing sync
3. **Manual Testing**: 
   - Fresh node startup with checkpoints enabled
   - Fresh node startup with `--force-pivot-sync`
   - Verify sync begins immediately without peer wait
4. **Network Testing**: Test on both ETC mainnet and Mordor testnet

## Migration Path

This is a backward-compatible addition:
- Existing nodes: Continue working as before (checkpoints empty by default initially)
- New nodes: Benefit from checkpoints automatically once hashes are added
- Operators: Can opt-out with `--force-pivot-sync` flag

## Rollout Plan

1. **Phase 1**: Implement infrastructure (this ADR)
   - Add data structures and configuration
   - Add loader and CLI flag
   - Document architecture

2. **Phase 2**: Obtain and verify block hashes
   - Query block explorers for fork block hashes
   - Verify against multiple sources
   - Add to configuration files

3. **Phase 3**: Testing and validation
   - Test on Mordor testnet first
   - Monitor sync behavior and logs
   - Gather community feedback

4. **Phase 4**: Production rollout
   - Enable on mainnet in release
   - Document in user guides
   - Monitor adoption and metrics
