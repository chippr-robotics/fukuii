# 3-Node Log Review Test - Quick Reference

## Quick Start

```bash
cd /ops/gorgoroth/test-scripts
./test-3node-log-review.sh
```

## What It Does

1. ✅ Starts 3-node network (node1 mining, node2/3 non-mining)
2. ✅ Syncs static peer connections
3. ✅ Mines blocks for 120 seconds
4. ✅ Collects logs from all nodes
5. ✅ Analyzes for RLPx errors
6. ✅ Analyzes block propagation
7. ✅ Generates reports

## Expected Output

```
logs-3node-review-YYYYMMDD-HHMMSS/
├── gorgoroth-fukuii-node1.log          # Node logs
├── gorgoroth-fukuii-node2.log
├── gorgoroth-fukuii-node3.log
├── gorgoroth-fukuii-node*-inspect.json # Container metadata
├── containers-status.txt               # Status snapshot
├── test-metadata.txt                   # Test config
└── analysis-report.txt                 # Results summary
```

## Key Metrics

### RLPx Errors Checked
- Handshake errors
- RLP encoding/decoding errors
- Connection issues
- Snappy compression errors

### Block Propagation Checked
- Block imports
- Block broadcasts
- Validation errors
- NewBlockHashes messages
- NewBlock messages

## Success Criteria

✅ All 3 nodes running
✅ Node1 mines ≥1 block
✅ No critical RLPx errors
✅ No block validation errors
✅ All nodes maintain peer connections

## Common Issues

### No blocks mined
- Check node1 mining config: `mining-enabled = true`
- Verify logs for mining errors

### Peer connection failures
- Run: `fukuii-cli sync-static-nodes`
- Check static-nodes.json files

### RLPx errors
- Check for version mismatches
- Verify network configurations
- Review specific error messages

## Manual Investigation

```bash
# View specific logs
cat logs-*/gorgoroth-fukuii-node1.log

# Search for errors
grep -i "error" logs-*/gorgoroth-fukuii-node*.log

# Check handshake
grep -i "handshake" logs-*/gorgoroth-fukuii-node1.log

# Check block propagation
grep -i "NewBlock" logs-*/gorgoroth-fukuii-node*.log

# Check mining
grep -i "mining\|mined" logs-*/gorgoroth-fukuii-node1.log
```

## Node Configuration

| Node | Mining | Coinbase | HTTP | WS | P2P |
|------|--------|----------|------|-----|-----|
| node1 | ✅ true | 0x1000...0001 | 8545 | 8546 | 30303 |
| node2 | ❌ false | 0x2000...0002 | 8547 | 8548 | 30304 |
| node3 | ❌ false | 0x3000...0003 | 8549 | 8550 | 30305 |

## Test Duration

- Network startup: ~30 seconds
- Peer sync: ~30 seconds
- Mining period: 120 seconds
- Total: ~3-4 minutes

## Related Commands

```bash
# Start network manually
fukuii-cli start 3nodes

# Sync peers manually
fukuii-cli sync-static-nodes

# Check status
fukuii-cli status 3nodes

# View live logs
fukuii-cli logs 3nodes

# Stop network
fukuii-cli stop 3nodes
```

## Documentation

- Full guide: [README-3node-log-review.md](./README-3node-log-review.md)
- Main README: [../README.md](../README.md)
- Gorgoroth Quick Start: [../QUICKSTART.md](../QUICKSTART.md)
