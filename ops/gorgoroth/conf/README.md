# Static Nodes Configuration

## Overview

This directory contains template `static-nodes.json` files for each Gorgoroth test network node. These files define which peers each node should connect to when peer discovery is disabled.

## File Format

Each `static-nodes.json` file is a JSON array of enode URLs:

```json
[
  "enode://<node-public-key>@<hostname>:30303",
  "enode://<node-public-key>@<hostname>:30303"
]
```

## Template Files

The template files contain placeholder values:
- `PLACEHOLDER_NODEX_PUBLIC_KEY` - Will be replaced with actual node public keys after first startup
- `fukuii-nodeX` - Docker container hostnames (these remain constant)
- `30303` - P2P port (remains constant)

## First Run Setup

On first startup, each node generates a unique private key and enode ID. The placeholder values in static-nodes.json won't match, so nodes won't connect initially.

### Automated Setup (Recommended)

Use the `fukuii-cli` tool to automatically collect and sync enode IDs:

```bash
# Start the network
fukuii-cli start 3nodes

# Wait for nodes to initialize
sleep 45

# Automatically collect enodes and update static-nodes.json
fukuii-cli sync-static-nodes
```

This will:
1. Extract enode URLs from each running container
2. Update static-nodes.json files with actual enode IDs
3. Restart containers to apply the configuration

### Manual Setup

If you prefer manual configuration:

1. Start the network and collect enode IDs from logs:
   ```bash
   docker logs gorgoroth-fukuii-node1 2>&1 | grep "Node address" | tail -1
   ```

2. Edit the static-nodes.json files, replacing placeholders with actual enode IDs

3. Restart the network:
   ```bash
   fukuii-cli restart 3nodes
   ```

## File Locations

In Docker deployments, static-nodes.json files are mounted from:
```
ops/gorgoroth/conf/node1/static-nodes.json -> /app/data/static-nodes.json
ops/gorgoroth/conf/node2/static-nodes.json -> /app/data/static-nodes.json
ops/gorgoroth/conf/node3/static-nodes.json -> /app/data/static-nodes.json
...
```

## Enterprise Mode

The Gorgoroth network uses `enterprise` mode, which means:
- Only static peers from static-nodes.json are used
- Bootstrap nodes from chain configuration are ignored
- This gives complete control over peer connections in private networks

## Verification

After updating static-nodes.json and restarting, verify connections:

```bash
# Check peer count (should be N-1 where N is total nodes)
curl -X POST --data '{"jsonrpc":"2.0","method":"net_peerCount","params":[],"id":1}' http://localhost:8546

# Expected for 3-node network: {"jsonrpc":"2.0","result":"0x2","id":1}
```

## Important Notes

1. **Each node's static-nodes.json should NOT include itself** - Only list peer nodes
2. **Hostnames must match docker-compose service names** - e.g., `fukuii-node1`, `fukuii-node2`
3. **Port must be P2P port (30303)**, not RPC port
4. **Node keys persist in Docker volumes** - Once configured, static-nodes.json remains valid across restarts

## Troubleshooting

**Nodes not connecting:**
- Verify enode IDs in static-nodes.json match actual node IDs in logs
- Check that hostnames match docker-compose service names
- Ensure port is 30303 (P2P), not 8545/8546 (RPC)
- Verify nodes are on the same Docker network

**Enode format errors:**
- Format: `enode://<128-hex-chars>@<hostname>:30303`
- Public key must be exactly 128 hexadecimal characters
- No extra whitespace or line breaks in JSON

## References

- [Gorgoroth Quickstart Guide](../../QUICKSTART.md)
- [Static Nodes Documentation](../../../docs/for-operators/static-nodes-configuration.md)
- [Fukuii CLI Tool](../../tools/fukuii-cli.sh)
