# Static Nodes Configuration

## Overview

This directory contains pre-populated `static-nodes.json` files for each Gorgoroth test network node. These files define which peers each node should connect to when peer discovery is disabled.

## File Format

Each `static-nodes.json` file is a JSON array of enode URLs:

```json
[
  "enode://<node-public-key>@<hostname>:30303",
  "enode://<node-public-key>@<hostname>:30303"
]
```

## Pre-populated Files (December 2025)

**Important Change**: As of version 0.1.147, the static-nodes.json files are pre-populated with actual enode IDs that correspond to persistent node keys stored in Docker volumes.

**How it works:**
- Each node has a persistent private key stored in its Docker volume
- The enode ID is deterministically generated from this private key
- Static-nodes.json files contain the correct enode IDs from the start
- Nodes connect automatically on first run without manual intervention

### Node Enode IDs (Persistent)

These enode IDs correspond to the persistent node keys in Docker volumes:

- **Node 1**: `enode://896acf67a7166e6af8361a4494f574d99c713bc0d0328ddbf6c33a1db51152c9fac3601b08c7c204d0d867b3f7e689bf1e8d976a65dda189a74f8afc4bab33c9@fukuii-node1:30303`
- **Node 2**: `enode://0037d4884abf8f9abd8ee0a815ee156a6e1ce51eca7bf999e8775d552ce488da1e24fdfdcf933b9a944138629a1dd67663c3ef1fe76730cfc57bbb13e960d995@fukuii-node2:30303`
- **Node 3**: `enode://284c0b9f9e8b2791d00e08450d5510f22781aa8261fdf84f0793e5eb350c4535ce8d927dd2d48fa4d2685c47eb3b7e49796d4f5a598ce214e28fc632f8df57a6@fukuii-node3:30303`

## First Run Setup

### Automated Setup (Recommended)

Use the initialization script to pre-populate Docker volumes with the static-nodes.json files:

```bash
# Initialize volumes (first run only)
cd ops/gorgoroth
./init-volumes.sh 3nodes

# Start the network
fukuii-cli start 3nodes

# Wait for nodes to initialize and connect
sleep 45

# Verify peer connections
curl -X POST -H "Content-Type: application/json" \
  --data '{"jsonrpc":"2.0","method":"net_peerCount","params":[],"id":1}' \
  http://localhost:8546
```

**Expected result**: `{"jsonrpc":"2.0","result":"0x2","id":1}` (2 peers for 3-node network)

### Alternative: Sync After Startup

If you started the network without initializing volumes, use the sync command:

```bash
# Start the network
fukuii-cli start 3nodes

# Wait for nodes to initialize
sleep 45

# Sync static nodes configuration
fukuii-cli sync-static-nodes
```

This will:
1. Extract enode URLs from each running container
2. Update static-nodes.json files in volumes
3. Restart containers to apply the configuration

## Docker Volume Management

### Volume Shadowing Fix (v0.1.147)

Previous versions had a Docker Compose configuration issue where bind mounts for static-nodes.json were shadowed by named volume mounts. This prevented nodes from loading peer configuration.

**What was fixed:**
- Removed conflicting bind mounts from all docker-compose files
- Static-nodes.json files are now copied into volumes using the init-volumes.sh script
- Nodes can now read peer configuration from /app/data/static-nodes.json inside the container

### File Locations

**In Repository:**
```
ops/gorgoroth/conf/node1/static-nodes.json  (pre-populated)
ops/gorgoroth/conf/node2/static-nodes.json  (pre-populated)
ops/gorgoroth/conf/node3/static-nodes.json  (pre-populated)
...
```

**In Docker Volumes (after initialization):**
```
gorgoroth_fukuii-node1-data volume -> /app/data/static-nodes.json
gorgoroth_fukuii-node2-data volume -> /app/data/static-nodes.json
gorgoroth_fukuii-node3-data volume -> /app/data/static-nodes.json
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

# Expected for 3-node network with all nodes connected: {"jsonrpc":"2.0","result":"0x2","id":1}
# Note: On first run BEFORE sync-static-nodes, this will return 0x0 (no peers)
```

## Important Notes

1. **Each node's static-nodes.json should NOT include itself** - Only list peer nodes
2. **Hostnames must match docker-compose service names** - e.g., `fukuii-node1`, `fukuii-node2`
3. **Port must be P2P port (30303)**, not RPC port
4. **Node keys persist in Docker volumes** - Once configured, static-nodes.json remains valid across restarts

## Troubleshooting

**Nodes not connecting:**

1. **Volumes not initialized**: Run `./init-volumes.sh 3nodes` before starting the network
2. **Old volumes from previous versions**: Clean and reinitialize:
   ```bash
   fukuii-cli clean 3nodes
   ./init-volumes.sh 3nodes
   fukuii-cli start 3nodes
   ```
3. **Verify enode IDs** match between static-nodes.json and actual node IDs:
   ```bash
   # Check file in volume
   docker run --rm -v gorgoroth_fukuii-node1-data:/data busybox cat /data/static-nodes.json
   
   # Check actual node enode
   docker logs gorgoroth-fukuii-node1 2>&1 | grep "Node address" | tail -1
   ```
4. **Check hostnames** match docker-compose service names (fukuii-node1, fukuii-node2, etc.)
5. **Verify port** is 30303 (P2P), not 8545/8546 (RPC)
6. **Confirm nodes** are on the same Docker network

**Enode format errors:**
- Format: `enode://<128-hex-chars>@<hostname>:30303`
- Public key must be exactly 128 hexadecimal characters
- No extra whitespace or line breaks in JSON

**File size is only 3 bytes (empty array):**
- This indicates the volume wasn't properly initialized
- Run `./init-volumes.sh 3nodes` to populate the volumes
- Expected file size: ~320-350 bytes for 2-peer configuration

## References

- [Gorgoroth Quickstart Guide](../../QUICKSTART.md)
- [Static Nodes Documentation](../../../docs/for-operators/static-nodes-configuration.md)
- [Fukuii CLI Tool](../../tools/fukuii-cli.sh)
