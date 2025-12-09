# 3-Node Log Review Test Harness

## Overview

This test harness provides an automated framework for testing and analyzing a 3-node Fukuii network environment with specific mining configurations. It's designed to validate network behavior, detect RLPx protocol errors, and analyze block header propagation patterns.

## Test Configuration

The test harness configures the 3-node network as follows:

- **Node 1**: mining-enabled=true (actively mines blocks)
- **Node 2**: mining-enabled=false (non-mining peer)
- **Node 3**: mining-enabled=false (non-mining peer)

This configuration tests the scenario where only one node is responsible for block production while other nodes act as validators and propagate blocks across the network.

## What the Test Does

The test harness performs the following steps:

1. **Network Startup**: Starts the 3-node network using docker-compose
2. **Enode Generation**: Waits for nodes to generate their enode URLs
3. **Peer Synchronization**: Uses `fukuii-cli.sh sync-static-nodes` to configure static peers
4. **Block Generation**: Allows node1 to mine blocks for 120 seconds
5. **Log Collection**: Captures logs from all containers
6. **RLPx Analysis**: Analyzes logs for RLPx protocol errors including:
   - Handshake errors
   - RLP encoding/decoding errors
   - Connection issues
   - Snappy compression errors
7. **Block Propagation Analysis**: Examines block header propagation including:
   - Block import events
   - Block broadcast events
   - Block validation errors
   - NewBlockHashes messages
   - NewBlock messages

## Usage

### Prerequisites

- Docker and docker-compose installed
- Fukuii docker images available
- Network configuration files in `/ops/gorgoroth/conf/`

### Running the Test

```bash
cd /ops/gorgoroth/test-scripts
./test-3node-log-review.sh
```

The test will:
- Start the network (or use existing if already running)
- Guide you through the process with colored output
- Collect and analyze logs automatically
- Generate comprehensive reports

### Output

The test creates a timestamped directory containing:

```
logs-3node-review-YYYYMMDD-HHMMSS/
├── gorgoroth-fukuii-node1.log          # Node 1 logs
├── gorgoroth-fukuii-node2.log          # Node 2 logs
├── gorgoroth-fukuii-node3.log          # Node 3 logs
├── gorgoroth-fukuii-node1-inspect.json # Node 1 container metadata
├── gorgoroth-fukuii-node2-inspect.json # Node 2 container metadata
├── gorgoroth-fukuii-node3-inspect.json # Node 3 container metadata
├── containers-status.txt               # Container status snapshot
├── test-metadata.txt                   # Test configuration details
└── analysis-report.txt                 # Automated analysis results
```

## Interpreting Results

### RLPx Error Analysis

The test checks for several types of RLPx errors:

- **Handshake Errors**: Indicate problems with the RLPx protocol handshake between peers
- **RLP Encoding/Decoding Errors**: Suggest data serialization issues
- **Connection Errors**: May indicate network connectivity problems (some are normal during startup)
- **Snappy Compression Errors**: Point to issues with message compression

### Block Propagation Analysis

The test examines block propagation patterns:

- **Imported Blocks**: Number of blocks successfully imported by each node
- **Received Blocks**: Blocks received from peers
- **Broadcast Blocks**: Blocks sent to peers
- **Validation Errors**: Blocks rejected due to validation failures
- **NewBlockHashes/NewBlock Messages**: ETH protocol messages for block propagation

### Success Criteria

The test is considered successful if:
- All 3 nodes start and stay running
- Static nodes are synchronized successfully
- Node1 mines at least 1 block
- No critical RLPx errors are detected
- No block validation errors occur
- All nodes maintain peer connections

## Manual Log Review

For deeper analysis, you can manually review the logs:

```bash
# View specific node logs
cat logs-3node-review-*/gorgoroth-fukuii-node1.log

# Search for specific patterns
grep -i "error" logs-3node-review-*/gorgoroth-fukuii-node*.log

# Check for handshake messages
grep -i "handshake" logs-3node-review-*/gorgoroth-fukuii-node1.log

# Look for block propagation
grep -i "NewBlock" logs-3node-review-*/gorgoroth-fukuii-node*.log
```

## Common Issues and Troubleshooting

### No Blocks Mined

If node1 doesn't mine any blocks:
- Check that mining is enabled in node1's configuration
- Verify node1 logs for mining-related errors
- Ensure the network difficulty is appropriate

### Peer Connection Issues

If nodes aren't connecting:
- Verify static-nodes.json files are correctly generated
- Check for firewall or network issues
- Review handshake errors in logs

### RLPx Errors

If RLPx errors are detected:
- Check for version mismatches between nodes
- Verify network configurations are consistent
- Review specific error messages for root cause

## Integration with CI/CD

The test harness can be integrated into CI/CD pipelines:

```bash
# Run as part of automated testing
./test-3node-log-review.sh

# Exit code 0 = success, 1 = issues detected
if [ $? -eq 0 ]; then
    echo "Test passed"
else
    echo "Test failed - review logs"
fi
```

## Related Documentation

- [Gorgoroth Quick Start Guide](../QUICKSTART.md)
- [Fukuii CLI Documentation](../README.md)
- [Test Infrastructure Validation](./validate-test-infrastructure.sh)
- [Block Propagation Test](./test-block-propagation.sh)

## Customization

To modify the test configuration:

1. **Change Mining Duration**: Edit the sleep duration in Step 4
2. **Add Custom Analysis**: Extend the `analyze_*` functions
3. **Modify Node Configuration**: Update the node config files in `/ops/gorgoroth/conf/`

## Support

For issues or questions:
- Check existing test logs in the logs directory
- Review the main Gorgoroth README
- Consult the troubleshooting guide
