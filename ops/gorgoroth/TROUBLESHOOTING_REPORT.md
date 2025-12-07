# Gorgoroth 3-Node Battlenet Troubleshooting Report

**Date:** 2025-12-07  
**Status:** Issues Identified - Workaround Available  
**Network:** Gorgoroth 3-Node Test Network  

## Executive Summary

The Gorgoroth 3-node battlenet successfully starts all containers and passes health checks, but nodes fail to establish peer connections. Root cause analysis reveals two critical issues:

1. **Configuration Loading Bug**: Nodes advertise port 9076 instead of the configured 30303
2. **Placeholder Enode IDs**: Static-nodes.json files contain invalid placeholder enode identifiers

## Test Execution

### Commands Executed
```bash
cd /home/runner/work/fukuii/fukuii/ops/gorgoroth
./deploy.sh start 3nodes
# Waited 45 seconds for initialization
./collect-logs.sh 3nodes /tmp/3nodes-logs
```

### Results
- ✅ All 3 containers started successfully
- ✅ All containers healthy (health checks passing)  
- ❌ 0 peer connections established
- ❌ Nodes advertising wrong P2P port (9076 vs 30303)

## Network Status

| Node | Status | Peers | Advertised Port | Expected Port |
|------|--------|-------|----------------|---------------|
| node1 | HEALTHY | 0 | 9076 | 30303 |
| node2 | HEALTHY | 0 | 9076 | 30303 |
| node3 | HEALTHY | 0 | 9076 | 30303 |

### Actual Enode Addresses (from logs)

```
node1: enode://a02031c44d9f129132ff6d0c4f88c0f59882abdb9be9c8f744b2f2d371dfc7c6cc694d76bf2fb8af5716fa11cd2e85ef4c11d526adc4aec70665ab98195e06b0@[0:0:0:0:0:0:0:0]:9076

node2: enode://f59209ee49572cdc2e6bd031efcfa562b52431051781bd7ce8b89c18bf9d5719ee4204f65486b3762a7feae06a269d4da3c251067b0908aae3a3cefbcc4fa620@[0:0:0:0:0:0:0:0]:9076

node3: enode://f70aac176ffb9aaeb13ec3f9f32773e7bbf8d040d98476bfec7a1bd1b85ff304dc72713fdf9970a8b7f8b071e29d91f24322059de0de62fa4470fff051b0727d@[0:0:0:0:0:0:0:0]:9076
```

## Root Cause Analysis

### Issue #1: Configuration File Loading Bug

**Problem:**  
The `App.scala` code checks for network configuration files as filesystem files rather than classpath resources:

```scala
// From src/main/scala/com/chipprbots/ethereum/App.scala:
private def setNetworkConfig(network: String): Unit = {
  val configFile = s"conf/$network.conf"
  val file = new File(configFile)  // ❌ Checks filesystem, not classpath
  if (file.exists()) {
    System.setProperty("config.file", configFile)
  } else {
    log.warn(s"Config file '$configFile' not found, using default config")
  }
}
```

**Impact:**
- When running `fukuii enterprise gorgoroth` in Docker, the code looks for `conf/gorgoroth.conf` as a file
- The file doesn't exist in the container's working directory
- Falls back to default configuration from `base.conf` which has `port = 9076`
- The correct `gorgoroth.conf` exists as a classpath resource with `port = 30303` but is never loaded

**Evidence:**
```
2025-12-07 05:50:02,448 INFO [ServerActor] - Listening on /[0:0:0:0:0:0:0:0]:9076
2025-12-07 05:50:03,487 INFO [StaticUDPPeerGroup] - Server bound to /0.0.0.0:30303
```
- TCP server (RLPx) listens on wrong port: 9076
- UDP server (discovery) correctly binds to: 30303

**Configuration Hierarchy:**
1. `base.conf` - Defines `port = 9076`
2. `app.conf` - Includes base.conf
3. `base-testnet.conf` - Includes app.conf
4. `gorgoroth.conf` - Includes base-testnet.conf, SHOULD override to `port = 30303`
5. **But gorgoroth.conf is never loaded due to the file existence check bug**

### Issue #2: Placeholder Enode IDs in static-nodes.json

**Current Configuration:**
```json
// ops/gorgoroth/conf/node1/static-nodes.json
[
  "enode://0000000000000000000000000000000000000000000000000000000000000002@fukuii-node2:30303",
  "enode://0000000000000000000000000000000000000000000000000000000000000003@fukuii-node3:30303"
]
```

**Problem:**
- Enode IDs are all zeros (placeholder values)
- They don't match actual node public keys
- Even if port was correct, nodes would reject connections with mismatched enode IDs

**Documentation Note:**
From `ops/gorgoroth/README.md` (lines 159-161):
> The static-nodes.json files in the 3-node configuration contain placeholder enode IDs and are for reference only. For production use, you should:
> 1. Generate proper node keys for each node
> 2. Update static-nodes.json with actual enode URLs

## Network Configuration

**Docker Networking:**
- Network: `gorgoroth_gorgoroth` (172.25.0.0/16)
- node1: 172.25.0.11
- node2: 172.25.0.12  
- node3: 172.25.0.13

**Discovery Mode:**
- Public discovery: DISABLED
- Bootstrap nodes: EMPTY
- Connection method: Static nodes only

**Port Mapping:**
| Node | Container P2P | Host P2P | RPC HTTP | RPC WS |
|------|---------------|----------|----------|---------|
| node1 | 30303 | 30303 | 8545 | 8546 |
| node2 | 30303 | 30304 | 8547 | 8548 |
| node3 | 30303 | 30305 | 8549 | 8550 |

## Solutions

### Option 1: Fix Configuration Loading (Recommended for Long-term)

**Change Required:**
Modify `src/main/scala/com/chipprbots/ethereum/App.scala` to check classpath resources:

```scala
private def setNetworkConfig(network: String): Unit = {
  val configFile = s"conf/$network.conf"
  // Check if resource exists in classpath
  val resourceExists = Option(getClass.getClassLoader.getResource(configFile)).isDefined
  if (resourceExists) {
    System.setProperty("config.file", configFile)
    log.info(s"Loading network configuration from: $configFile")
  } else {
    log.warn(s"Config file '$configFile' not found in classpath, using default config")
  }
}
```

**Benefits:**
- Fixes root cause permanently
- Network-specific configs work as designed
- Proper port configuration (30303) used

**Drawbacks:**
- Requires code change
- Requires rebuilding and publishing Docker image
- Not a minimal change for immediate troubleshooting

### Option 2: Override Port via System Property (Quick Workaround)

**Change Required:**
Modify docker-compose-3nodes.yml to override the port:

```yaml
environment:
  - FUKUII_NODE_ID=node1
  - FUKUII_ENV=gorgoroth  
  - JAVA_OPTS=-Dfukuii.network.server-address.port=30303
```

**Benefits:**
- No code changes needed
- Quick to implement

**Drawbacks:**
- Workaround, not a proper fix
- JAVA_OPTS may not be properly passed through startup script
- Tested and failed - startup script doesn't use JAVA_OPTS

### Option 3: Update static-nodes.json with Actual Enode IDs (Recommended Workaround)

**✅ IMPLEMENTED** - A unified `fukuii-cli` toolkit is now available to automate deployment and configuration.

The `fukuii-cli` tool is a comprehensive command-line toolkit that includes:
- Network deployment commands (start, stop, restart, status, logs, clean)
- Node configuration commands (sync-static-nodes, collect-logs)
- All functionality previously in separate scripts now unified in one tool

**Sync-Static-Nodes Functionality:**
1. Collects enode URLs from all running containers via RPC
2. Generates a consolidated static-nodes.json file
3. Distributes the file to all containers
4. Restarts containers to apply the configuration

**Usage:**

```bash
# Using fukuii-cli directly (recommended)
fukuii-cli sync-static-nodes

# Or using backward-compatible wrappers
./sync-static-nodes.sh
./deploy.sh sync-static-nodes
```

**All Available Commands:**

```bash
fukuii-cli start [config]          # Start network
fukuii-cli stop [config]           # Stop network
fukuii-cli restart [config]        # Restart network
fukuii-cli status [config]         # Show container status
fukuii-cli logs [config]           # Follow logs
fukuii-cli clean [config]          # Remove containers and volumes
fukuii-cli sync-static-nodes       # Synchronize peer connections
fukuii-cli collect-logs [config]   # Collect logs for debugging
fukuii-cli help                    # Show help
fukuii-cli version                 # Show version
```

**Benefits:**
- Unified command structure across all operations
- Automatically extracts actual enode IDs from running nodes
- Handles all nodes in the network
- Properly formats JSON output
- Includes retry logic for reliability
- Restarts containers to apply changes
- Backward compatible with existing scripts

**Limitations:**
- Still doesn't fix the underlying port configuration issue (9076 vs 30303)
- Nodes will advertise wrong port in enode URLs until configuration bug is fixed
- Works around the problem rather than solving root cause

**First-Time 3-Node Setup Process:**

1. Start the network (nodes will start but won't connect):
   ```bash
   fukuii-cli start 3nodes
   ```

2. Wait for nodes to fully initialize (~30-45 seconds):
   ```bash
   sleep 45
   ```

3. Synchronize static nodes (collects enodes and establishes connections):
   ```bash
   fukuii-cli sync-static-nodes
   ```

4. Verify peer connections:
   ```bash
   curl -X POST --data '{"jsonrpc":"2.0","method":"net_peerCount","params":[],"id":1}' \
     http://localhost:8546
   ```

For detailed CLI usage and installation instructions, see `docs/runbooks/node-configuration.md`.

### Option 4: Manual Peering via RPC (Testing Workaround)

Remove static-nodes.json, manually connect peers after startup:

```bash
# After nodes start, manually add peers
docker exec gorgoroth-fukuii-node1 curl -X POST -H "Content-Type: application/json" \
  --data '{"jsonrpc":"2.0","method":"admin_addPeer","params":["enode://NODE2_ID@fukuii-node2:9076"],"id":1}' \
  http://localhost:8546
```

**Problems:**
- admin API not enabled by default
- Manual intervention required
- Not suitable for automated testing

## Recommended Action Plan

Since this is a troubleshooting task to identify issues, not necessarily fix them:

### Immediate (Documentation)
1. ✅ Document configuration loading bug in App.scala
2. ✅ Document placeholder enode ID issue  
3. ✅ Create this troubleshooting report
4. ⏸️ File issues for proper fixes

### Future (Proper Fix)
1. Fix `setNetworkConfig()` in App.scala to check classpath resources
2. Add integration test to verify network configs load correctly
3. ✅ **COMPLETED**: Created `fukuii-cli` tool to generate and synchronize static-nodes.json files
4. ✅ **COMPLETED**: Updated documentation with working examples and first-time setup process

## Test Results Summary

**What Works:**
- ✅ Docker Compose deployment
- ✅ Container health checks
- ✅ RPC endpoints accessible
- ✅ UDP discovery server binds correctly (30303)

**What Fails:**
- ❌ TCP P2P server uses wrong port (9076 instead of 30303)
- ❌ Network configuration file not loaded
- ❌ Static peer connections (invalid enode IDs)
- ❌ Peer discovery (0 peers connected)

## Log Evidence

**Key Log Entries:**

```
# Port mismatch evidence:
2025-12-07 05:50:02,277 INFO [PortForwarder$] - Attempting port forwarding for TCP ports List(9076) and UDP ports List(30303)
2025-12-07 05:50:02,448 INFO [ServerActor] - Listening on /[0:0:0:0:0:0:0:0]:9076
2025-12-07 05:50:03,487 INFO [StaticUDPPeerGroup] - Server bound to address /0.0.0.0:30303

# Zero peers evidence:
$ docker exec gorgoroth-fukuii-node1 curl -s -X POST -H "Content-Type: application/json" \
  --data '{"jsonrpc":"2.0","method":"net_peerCount","params":[],"id":1}' \
  http://localhost:8546
{"jsonrpc":"2.0","result":"0x0","id":1}

# Discovery attempts to external nodes (not local peers):
2025-12-07 05:50:17,074 INFO [PeerManagerActor] - Total number of discovered nodes 29. Handshaked 0/80, pending 0.
2025-12-07 05:50:32,276 INFO [Blacklist] - Blacklisting peer [PeerAddress(164.90.144.106)] for 180000 milliseconds.
```

## Files Analyzed

### Configuration Files
- `/home/runner/work/fukuii/fukuii/src/main/resources/conf/base.conf` - Default port 9076
- `/home/runner/work/fukuii/fukuii/src/main/resources/conf/gorgoroth.conf` - Correct port 30303 (not loaded)
- `/home/runner/work/fukuii/fukuii/ops/gorgoroth/conf/base-gorgoroth.conf` - Port 30303 (not used)
- `/home/runner/work/fukuii/fukuii/ops/gorgoroth/conf/node*/gorgoroth.conf` - Port 30303 (not used)
- `/home/runner/work/fukuii/fukuii/ops/gorgoroth/conf/node*/static-nodes.json` - Placeholder enodes

### Source Code
- `/home/runner/work/fukuii/fukuii/src/main/scala/com/chipprbots/ethereum/App.scala` - Config loading bug
- `/home/runner/work/fukuii/fukuii/src/main/scala/com/chipprbots/ethereum/network/ServerActor.scala` - Port binding
- `/home/runner/work/fukuii/fukuii/src/main/scala/com/chipprbots/ethereum/utils/Config.scala` - Config reading

### Deployment Files
- `/home/runner/work/fukuii/fukuii/ops/gorgoroth/docker-compose-3nodes.yml`
- `/home/runner/work/fukuii/fukuii/ops/gorgoroth/deploy.sh`
- `/home/runner/work/fukuii/fukuii/ops/gorgoroth/collect-logs.sh`

### Log Files
- `/tmp/3nodes-logs/gorgoroth-fukuii-node1.log`
- `/tmp/3nodes-logs/gorgoroth-fukuii-node2.log`
- `/tmp/3nodes-logs/gorgoroth-fukuii-node3.log`

## Conclusion

The Gorgoroth 3-node battlenet nodes cannot connect to each other due to two compounding issues:

1. **Primary Issue**: Configuration file loading bug causes nodes to use default port 9076 instead of the configured 30303
2. **Secondary Issue**: Static-nodes.json files contain placeholder enode IDs that don't match actual node identities

The configuration loading bug is the critical blocker. Even with correct enode IDs in static-nodes.json, nodes would still fail to connect because they're advertising the wrong port. The bug is in `App.scala`'s `setNetworkConfig()` method which checks for filesystem files instead of classpath resources.

For troubleshooting purposes, this analysis successfully identifies the root causes of the connectivity issues. A proper fix requires code changes to the configuration loading logic and generation of valid static-nodes.json files.

## References

- Original task: Troubleshoot 3nodes battlenet network configuration and RLPx handshake
- README: `/home/runner/work/fukuii/fukuii/ops/gorgoroth/README.md`
- Detailed analysis: `/tmp/3nodes-battlenet-analysis.md`
- Collected logs: `/tmp/3nodes-logs/`
