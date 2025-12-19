# Bootnode Management Script

## update-bootnodes.sh

Automatically updates the ETC bootnode configuration by fetching and validating bootnodes from authoritative sources.

### Purpose

The script ensures that Fukuii maintains a healthy list of 30 active bootnodes by:
- Fetching bootnodes from the etcnodes API (live ETC node network)
- Validating current bootnodes against the live node list
- Removing bootnodes that are no longer active (not in API = dead)
- Maintaining exactly 30 active bootnodes (1.5x the default max outgoing connections of 20)
- Normalizing all bootnodes to use standard port 30303
- Prioritizing nodes by last seen timestamp (most recently active first)

### Usage

```bash
# Run manually
bash scripts/update-bootnodes.sh

# The script will:
# 1. Extract current bootnodes from src/main/resources/conf/base/chains/etc-chain.conf
# 2. Fetch live bootnodes from etcnodes API with timestamps
# 3. Normalize all ports to 30303 (standard ETC port)
# 4. Sort by last seen timestamp (most recent first)
# 5. Validate and select 30 bootnodes
# 6. Update the configuration file
# 7. Create a timestamped backup
```

### Automated Execution

The script runs automatically via GitHub Actions on a nightly schedule:
- Workflow: `.github/workflows/nightly.yml`
- Job: `nightly-bootnode-update`
- Schedule: Daily at 00:00 GMT (midnight UTC)

When changes are detected, the workflow automatically creates a pull request with:
- Updated bootnode configuration
- Detailed change summary
- Backup of previous configuration

### Bootnode Sources

**etcnodes API**: https://api.etcnodes.org/peers
- Real-time API of live ETC nodes on the network
- Maintained by the ETC community
- Provides up-to-date list of active nodes with connection information and timestamps
- GitHub: https://github.com/etclabscore/nodes-interface
- All ports are normalized to 30303 (standard ETC port)
- Nodes are sorted by last seen timestamp (contact.last.unix)

### Selection Logic

The script uses a priority-based selection process:

1. **Priority 1**: Keep current bootnodes that exist in the live etcnodes API list (they're still alive)
2. **Priority 2**: Add new bootnodes from the live etcnodes API list (sorted by last seen)

This ensures:
- Stability: Current working bootnodes are preserved when they're still alive
- Freshness: New live bootnodes from the network are added based on activity
- Liveness: Only nodes actively connected to the ETC network are used
- Quality: Nodes are prioritized by most recent activity (last seen timestamp)
- Consistency: All nodes use standard port 30303

### Validation

Each bootnode is validated to ensure:
- Proper enode URL format: `enode://[pubkey]@[ip]:[port]`
- Valid public key (128 hex characters, case-insensitive)
- Valid IP address or hostname
- Valid port number
- Proper discovery port specification (if applicable)
- Presence in authoritative sources (core-geth, Hyperledger Besu)

### Configuration File

Target file: `src/main/resources/conf/base/chains/etc-chain.conf`

The script updates the `bootstrap-nodes` array in the ETC chain configuration, maintaining:
- Header comments indicating automated management
- Timestamp of last update
- Source attribution (core-geth, Besu)
- Proper HOCON array formatting

### Backups

Before each update, the script creates a timestamped backup:
```
src/main/resources/conf/base/chains/etc-chain.conf.backup.YYYYMMDD_HHMMSS
```

Backups are retained locally but are not committed to version control (excluded via .gitignore).

### Manual Intervention

If manual bootnode management is required:
1. Update `src/main/resources/conf/base/chains/etc-chain.conf` directly
2. Commit your changes
3. The script will respect your changes on the next run if they match authoritative sources

To disable automated updates:
1. Comment out or remove the `nightly-bootnode-update` job in `.github/workflows/nightly.yml`
2. Or update the script to skip specific bootnodes

### Troubleshooting

**Issue**: Script reports "Only X bootnodes available (target: 20)"
- **Cause**: Authoritative sources have fewer than 20 bootnodes
- **Resolution**: This is expected; script will use all available bootnodes

**Issue**: Important bootnode is being removed
- **Cause**: Bootnode is not in core-geth or Besu authoritative lists
- **Resolution**: 
  1. Verify the bootnode is still active and reliable
  2. Submit PR to core-geth or Besu to add the bootnode
  3. Or manually maintain the bootnode in configuration and disable automation

**Issue**: Script fails to fetch from external sources
- **Cause**: Network issues or repository changes
- **Resolution**: Check GitHub Actions logs for specific error messages

### Related Files

- `.github/workflows/nightly.yml` - GitHub Actions workflow
- `src/main/resources/conf/base/chains/etc-chain.conf` - Target configuration file
- `ops/cirith-ungol/conf/static-nodes.json` - Additional static nodes configuration