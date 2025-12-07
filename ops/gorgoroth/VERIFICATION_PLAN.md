# Implementation Verification Plan

This document outlines the verification steps for the gorgoroth 3-node network fixes.

## Changes Made

### 1. Configuration Loading Fix (App.scala)
**File**: `src/main/scala/com/chipprbots/ethereum/App.scala`

**Change**: Enhanced `setNetworkConfig()` to check classpath resources when filesystem file doesn't exist.

**Before**:
```scala
val file = new File(configFile)
if (file.exists()) {
  System.setProperty("config.file", configFile)
}
```

**After**:
```scala
val file = new File(configFile)
if (file.exists()) {
  System.setProperty("config.file", configFile)
  log.info(s"Loading network configuration from filesystem: $configFile")
} else {
  val resourceExists = Option(getClass.getClassLoader.getResource(configFile)).isDefined
  if (resourceExists) {
    System.setProperty("config.file", configFile)
    log.info(s"Loading network configuration from classpath: $configFile")
  } else {
    log.warn(s"Config file '$configFile' not found in filesystem or classpath, using default config")
  }
}
```

**Expected Impact**: When running `fukuii enterprise gorgoroth` in Docker, the code will now:
1. Check filesystem for `conf/gorgoroth.conf` (not found)
2. Check classpath for `conf/gorgoroth.conf` (found in resources)
3. Load `conf/gorgoroth.conf` from classpath with correct port 30303

### 2. Static Nodes Port Fix
**Files**: 
- `ops/gorgoroth/conf/node1/static-nodes.json`
- `ops/gorgoroth/conf/node2/static-nodes.json`
- `ops/gorgoroth/conf/node3/static-nodes.json`

**Change**: Updated all enode URLs from port 9076 to 30303.

**Before**: `enode://...@fukuii-node2:9076`
**After**: `enode://...@fukuii-node2:30303`

**Expected Impact**: Nodes will attempt to connect to peers on the correct P2P port.

### 3. Persistent Node Keys
**File**: `ops/gorgoroth/docker-compose-3nodes.yml`

**Change**: Added `JAVA_OPTS=-Dfukuii.datadir=/app/data` to all node environments.

**Expected Impact**: 
- Node keys will be stored in `/app/data/node.key`
- `/app/data` is mounted as a Docker volume
- Node keys will persist across container restarts
- Enode IDs will remain stable

### 4. Insomnia Workspace Integration
**File**: `insomnia_workspace.json`

**Change**: Added "Gorgoroth 3-Node Test Network" environment with:
- All node HTTP/WS endpoints
- Pre-funded genesis account addresses
- Default variables for testing

**Expected Impact**: Users can test the gorgoroth network directly from Insomnia.

### 5. Quick Start Guide
**File**: `ops/gorgoroth/QUICKSTART.md`

**Change**: Created comprehensive guide with:
- Step-by-step setup instructions
- API testing examples
- Troubleshooting tips
- Insomnia integration guide

## Verification Steps

### Step 1: Build New Docker Image (CI/CD)
The changes require rebuilding the Docker image to include the App.scala fix.

**Command**:
```bash
# This will be done by CI/CD when changes are merged
docker build -t fukuii:test .
```

### Step 2: Test Configuration Loading

Start a single node and verify it loads the correct configuration:

```bash
docker run --rm fukuii:test fukuii enterprise gorgoroth --help
```

**Expected log output**:
```
Loading network configuration from classpath: conf/gorgoroth.conf
```

### Step 3: Start 3-Node Network

```bash
cd ops/gorgoroth
fukuii-cli start 3nodes
sleep 45
```

### Step 4: Verify Port Configuration

Check that nodes are listening on correct ports:

```bash
docker logs gorgoroth-fukuii-node1 2>&1 | grep "Listening on"
```

**Expected output**:
```
Listening on /[0:0:0:0:0:0:0:0]:30303
```

**NOT**:
```
Listening on /[0:0:0:0:0:0:0:0]:9076  # OLD BROKEN BEHAVIOR
```

### Step 5: Verify Peer Connections

```bash
curl -X POST -H "Content-Type: application/json" \
  --data '{"jsonrpc":"2.0","method":"net_peerCount","params":[],"id":1}' \
  http://localhost:8546
```

**Expected result**:
```json
{"jsonrpc":"2.0","result":"0x2","id":1}
```

This indicates node1 is connected to 2 peers (node2 and node3).

### Step 6: Verify Node Key Persistence

```bash
# Check node1's enode (search in container logs)
docker logs gorgoroth-fukuii-node1 2>&1 | grep "Node address" | tail -1

# Restart the network
fukuii-cli restart 3nodes
sleep 45

# Check node1's enode again - should be identical
docker logs gorgoroth-fukuii-node1 2>&1 | grep "Node address" | tail -1
```

**Expected**: The enode ID should be the same before and after restart.

### Step 7: Verify Block Production

```bash
# Wait for some blocks to be mined
sleep 120

# Check block number
curl -X POST -H "Content-Type: application/json" \
  --data '{"jsonrpc":"2.0","method":"eth_blockNumber","params":[],"id":1}' \
  http://localhost:8546
```

**Expected**: Block number should be > 0 (e.g., "0x5" or higher)

### Step 8: Test Insomnia Integration

1. Import `insomnia_workspace.json` into Insomnia
2. Select "Gorgoroth 3-Node Test Network" environment
3. Test a request (e.g., ETH/Blocks/eth_blockNumber)
4. Verify response is received

### Step 9: Test Multi-Node Sync

Check that all nodes are at the same block height:

```bash
for port in 8546 8548 8550; do
  echo "Node on port $port:"
  curl -s -X POST -H "Content-Type: application/json" \
    --data '{"jsonrpc":"2.0","method":"eth_blockNumber","params":[],"id":1}' \
    http://localhost:$port | jq -r '.result'
done
```

**Expected**: All three nodes should report the same (or very close) block numbers.

## Success Criteria

All of the following must be true:

- ✅ Configuration loads from classpath (log message confirms)
- ✅ Nodes listen on port 30303 (not 9076)
- ✅ Each node connects to 2 peers
- ✅ Node keys persist across restarts (same enode IDs)
- ✅ Blocks are being mined
- ✅ All nodes stay in sync
- ✅ Insomnia workspace can connect to all nodes

## Rollback Plan

If issues are found:

1. Revert changes to App.scala
2. Revert static-nodes.json port changes
3. Revert docker-compose JAVA_OPTS changes
4. Document issues in TROUBLESHOOTING_REPORT.md

## Notes

- The enode IDs in static-nodes.json are placeholders that will be replaced once nodes generate their actual keys
- With persistent node keys enabled via JAVA_OPTS, the generated keys will be stored in `/app/data/node.key`
- For deterministic production deployments, you can pre-generate node keys:
  ```bash
  # Generate a node key pair using fukuii CLI
  fukuii cli generate-key-pairs
  
  # Save the private key to node.key file
  echo "GENERATED_PRIVATE_KEY_HEX" > /path/to/volume/node.key
  
  # Use the public key to construct the enode URL
  # enode://<public_key>@hostname:port
  ```
- The `fukuii-cli sync-static-nodes` command still requires admin RPC to be enabled

## Follow-up Tasks

1. Enable admin RPC namespace for `fukuii-cli sync-static-nodes` automation
2. Generate and document proper node keys for deterministic setup
3. Add automated integration test for 3-node network
4. Update main README.md to reference QUICKSTART.md
