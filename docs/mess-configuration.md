# MESS (Modified Exponential Subjective Scoring) Configuration Guide

## Overview

MESS (Modified Exponential Subjective Scoring) is a consensus enhancement for Ethereum Classic that provides protection against long-range reorganization attacks by applying time-based penalties to blocks that are received late by the node.

For detailed technical information, see [ADR-016: MESS Implementation](adr/016-mess-implementation.md).

## Quick Start

### Enabling MESS

MESS is **disabled by default** for backward compatibility. To enable it, modify your chain configuration file:

```hocon
# In etc-chain.conf or mordor-chain.conf
mess {
  enabled = true
}
```

Or use the CLI flag (planned):
```bash
./bin/fukuii etc --enable-mess
```

### Basic Configuration

The default configuration is suitable for most use cases:

```hocon
mess {
  enabled = false                    # Enable/disable MESS
  decay-constant = 0.0001           # Penalty strength (per second)
  max-time-delta = 2592000          # 30 days maximum age
  min-weight-multiplier = 0.0001    # 0.01% minimum weight
}
```

## Configuration Parameters

### `enabled` (Boolean)
- **Default**: `false`
- **Description**: Whether MESS scoring is active
- **Recommendation**: Enable after testing on Mordor testnet

### `decay-constant` (Double)
- **Default**: `0.0001` (per second)
- **Range**: `>= 0.0`
- **Description**: Controls how strongly delayed blocks are penalized
- **Effect**: Higher values = stronger penalties for late blocks
- **Examples**:
  - `0.0001`: 1 hour delay = ~30% penalty
  - `0.0002`: 1 hour delay = ~50% penalty
  - `0.00005`: 1 hour delay = ~15% penalty

### `max-time-delta` (Long, seconds)
- **Default**: `2592000` (30 days)
- **Description**: Maximum age difference considered in scoring
- **Effect**: Blocks older than this are treated as this old (prevents numerical overflow)
- **Recommendation**: Keep at default unless you have specific requirements

### `min-weight-multiplier` (Double)
- **Default**: `0.0001` (0.01%)
- **Range**: `0.0 < value <= 1.0`
- **Description**: Minimum weight multiplier to prevent scores going to zero
- **Effect**: Even extremely old blocks retain this percentage of their difficulty
- **Recommendation**: Keep at default for security

## How MESS Works

### Time-Based Penalty Formula

```
adjustedDifficulty = originalDifficulty × exp(-λ × timeDelta)

where:
  λ = decay-constant
  timeDelta = currentTime - firstSeenTime (in seconds)
```

### Penalty Examples (with default λ=0.0001)

| Time Delay | Penalty | Remaining Weight |
|------------|---------|------------------|
| 0 seconds  | 0%      | 100%            |
| 1 hour     | ~30%    | ~70%            |
| 6 hours    | ~78%    | ~22%            |
| 24 hours   | ~99%    | ~1%             |
| 7 days     | ~99.9%  | ~0.1%           |

### Chain Weight Comparison

When comparing chains:
1. **Checkpoint number** is compared first (highest wins)
2. If both chains have MESS scores, **MESS-adjusted total difficulty** is compared
3. If only one chain has MESS scores, **regular total difficulty** is compared (backward compatibility)

## Use Cases

### Protection Against Long-Range Attacks

**Scenario**: Attacker secretly mines alternative chain history

**Without MESS**: If attacker achieves equal or higher total difficulty, nodes might accept the alternative chain

**With MESS**: Alternative chain arrives late, receives heavy penalty, honest chain preferred

### Network Partition Recovery

**Scenario**: Node temporarily isolated from network

**Without MESS**: Node might accept old fork when reconnecting

**With MESS**: Old fork receives penalty, recently-seen canonical chain preferred

## Monitoring

### Recommended Metrics (to be implemented)

- `mess_scorer_block_age_seconds`: Distribution of block ages when first seen
- `mess_scorer_penalty_applied`: Count of blocks with MESS penalty
- `mess_scorer_multiplier_gauge`: Current MESS multiplier for recent blocks
- `chain_weight_mess_score`: MESS-adjusted chain weight

### Log Messages

MESS operations are logged at INFO level:
- Block first-seen time recording
- MESS penalty calculations
- Chain weight comparisons with MESS scores

## Best Practices

### For Node Operators

1. **Test on Mordor first**: Enable MESS on testnet before mainnet
2. **Monitor logs**: Watch for unusual MESS penalties
3. **Keep time synchronized**: Ensure NTP is working (MESS relies on accurate timestamps)
4. **Backup first-seen data**: The block first-seen database is important for MESS

### For Developers

1. **Don't modify decay-constant** without extensive testing
2. **Preserve first-seen times** across restarts (stored in RocksDB)
3. **Handle missing first-seen times**: Use block timestamp as fallback
4. **Test attack scenarios**: Verify MESS protects against long-range attacks

## Troubleshooting

### MESS Not Taking Effect

**Problem**: Chain weights don't show MESS scores

**Solutions**:
- Verify `mess.enabled = true` in config
- Check that blocks have first-seen times recorded
- Ensure node restart didn't lose first-seen data

### Unexpected Chain Reorganizations

**Problem**: Chain reorgs when MESS is enabled

**Possible Causes**:
- First-seen times not preserved across restart
- Clock synchronization issues (check NTP)
- `decay-constant` set too high

**Solutions**:
- Verify RocksDB storage for block first-seen times
- Check system clock accuracy
- Reset `decay-constant` to default (0.0001)

### High MESS Penalties for Valid Blocks

**Problem**: Recent blocks showing high penalties

**Possible Causes**:
- System clock incorrect
- Network latency very high
- Storage corruption

**Solutions**:
- Verify system time with `date` and NTP status
- Check network connectivity to peers
- Rebuild first-seen database if corrupted

## Database Maintenance

### Storage Location

Block first-seen times are stored in RocksDB namespace 'm' (BlockFirstSeenNamespace).

### Cleanup

Very old first-seen times can be cleaned up to save space:

```scala
// Remove first-seen times for blocks older than retention period
// (implementation to be added in future version)
```

### Backup

Include first-seen data in node backups to preserve MESS protection across migrations.

## Security Considerations

1. **Clock Accuracy**: MESS relies on accurate timestamps. Use NTP.
2. **Storage Integrity**: First-seen times must be protected from tampering.
3. **Parameter Tuning**: Only change defaults if you understand the security implications.
4. **Gradual Adoption**: Enable on a few nodes first, monitor behavior before wide deployment.

## Future Enhancements

Planned improvements to MESS:
- CLI flags for runtime control (`--enable-mess`, `--mess-decay-constant`)
- Prometheus metrics for observability
- Automatic cleanup of old first-seen entries
- Multi-node MESS time synchronization
- Advanced attack scenario tests

## References

- [ADR-016: MESS Implementation](adr/016-mess-implementation.md)
- [ECIP-1097/ECBP-1100](https://github.com/ethereumclassic/ECIPs/pull/373)
- [core-geth MESS Implementation](https://github.com/etclabscore/core-geth)

## Support

For issues or questions about MESS:
- Create an issue on GitHub
- Check ADR-016 for detailed technical information
- Review integration test examples in `src/it/scala/com/chipprbots/ethereum/consensus/mess/`
