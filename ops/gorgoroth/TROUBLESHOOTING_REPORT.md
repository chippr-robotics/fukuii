# Gorgoroth 3-Node Battlenet Troubleshooting Report

**Date:** 2025-12-07  
**Status:** Issues Identified - Workaround Available  
**Network:** Gorgoroth 3-Node Test Network  

## Executive Summary

The Gorgoroth 3-node battlenet successfully starts all containers and passes health checks, but nodes fail to establish peer connections. Root cause analysis reveals three critical issues:

1. **Configuration Loading Bug**: Nodes advertise port 9076 instead of the configured 30303
2. **Placeholder Enode IDs**: Static-nodes.json files contain invalid placeholder enode identifiers
3. **Ephemeral Node Keys**: No persisted `nodekey` files exist inside the containers, so every restart generates new enode IDs and instantly invalidates any static-nodes settings or collected peer metadata

## Latest Troubleshooting Session – 2025-12-07 19:17 UTC

### Steps Performed

- Started the stack with `fukuii-cli start 3nodes`, waited ~45 seconds, and immediately observed zero peers.
- Ran `fukuii-cli restart 3nodes` to apply freshly edited host-side `static-nodes.json` files (node1 ↔ node2 ↔ node3) and allow containers to remount them.
- Extracted the new enode IDs from container logs via `docker logs gorgoroth-fukuii-node{1,2,3}` and updated `ops/gorgoroth/conf/node*/static-nodes.json` twice.
- Verified container state with `docker exec gorgoroth-fukuii-node1 cat /app/data/static-nodes.json` and directory listings under `/app/data`.
- Collected artifacts with `fukuii-cli collect-logs gorgoroth logs-3nodes-20251207-132004` (now archived under `ops/gorgoroth/logs-3nodes-20251207-132004`).
- Queried RPC health via `net_peerCount` on node1 and confirmed the value is still `0x0` after several minutes.
- Attempted to automate enode collection with `./fukuii-cli.sh sync-static-nodes gorgoroth`, which consistently fails because the admin RPC namespace (specifically `admin_nodeInfo`) is disabled in this build.
- Searched for persisted node keys using `docker exec gorgoroth-fukuii-node1 sh -c 'find /home/fukuii -maxdepth 3 -name nodekey -print'` and found none, explaining why every restart produces brand new enode IDs.

### Key Observations

- **Still advertising TCP 9076**: All three nodes log `Listening on ...:9076` despite Docker exposing 30303; the config-loading bug persists.
- **Enode IDs rotate each restart**: Latest run produced:
  - node1 – `enode://b83b6fb5bd82f3f65c4b0e29fcedbf34457121a03d0cb1f2307adb97b25ac04a4fc6951735c2616e9da21c9740200714c63d0d40ade7eba3b80c20da8497da89@[0:0:0:0:0:0:0:0]:9076`
  - node2 – `enode://5131bfd7f9d7a35f20cafa60460525d84d351ca1e7b33c750c277392900e3d0c2dd7a3829226dcf5cdfc20b836edfbff89f81260692aa20fc235130497b85c3f@[0:0:0:0:0:0:0:0]:9076`
  - node3 – `enode://2c5cc07365e069b3477bd8eedf7d1edd296175c9ff7ede3b6029c9a2587af8f72e87ad5dbbc6cc419aba428f55dc1d6aa37e7eabbaa25ab79801eca354da0fa4@[0:0:0:0:0:0:0:0]:9076`
- **Static nodes stay stale**: Host and container `static-nodes.json` files match, but they inevitably contain yesterday's enodes because the underlying keys rotate—preventing any persistent peering even after manual edits.
- **Automation blocked**: `fukuii-cli sync-static-nodes gorgoroth` cannot call `admin_nodeInfo`, so it retries five times per node and exits with code 1. Manual log scraping is the only option right now.
- **No persisted nodekey**: Neither `/app/data` nor `/home/fukuii/.fukuii/etc` contain a `nodekey` file, confirming keys live in ephemeral locations (likely `/tmp`) and vanish on restart.
- **External peers still blacklisted**: Logs show repeated `CacheBasedBlacklist` events for 64.225.0.245, 164.90.144.106, and 157.245.77.211 exactly as in prior runs.

### Evidence Excerpts

```bash
$ docker exec gorgoroth-fukuii-node1 curl -s -X POST -H 'Content-Type: application/json' \
  --data '{"jsonrpc":"2.0","method":"net_peerCount","params":[],"id":1}' http://localhost:8546
{"jsonrpc":"2.0","result":"0x0","id":1}
```

```text
$ ./fukuii-cli.sh sync-static-nodes gorgoroth
Collecting enode URLs from containers...
  gorgoroth-fukuii-node1: Retry 1/5 ...
  ...
Failed to get enode from gorgoroth-fukuii-node1 after 5 retries
```

```text
$ docker exec gorgoroth-fukuii-node1 ls -al /app/data
total 13
drwxr-xr-x 2 fukuii fukuii 4096 Dec  5 04:34 .
drwxr-xr-x 1 fukuii fukuii 4096 Dec  7 19:17 ..
-rw-rw-r-- 1 fukuii fukuii  323 Dec  7 19:22 static-nodes.json
```

All supporting logs are stored in `ops/gorgoroth/logs-3nodes-20251207-132004/`.

## Test Execution

### Commands Executed
```bash
cd /chipprbots/blockchain/fukuii/ops/tools
./fukuii-cli.sh start 3nodes
sleep 45
./fukuii-cli.sh restart 3nodes
./fukuii-cli.sh collect-logs gorgoroth logs-3nodes-20251207-132004
./fukuii-cli.sh sync-static-nodes gorgoroth   # fails (admin RPC disabled)
cd ../gorgoroth
docker logs gorgoroth-fukuii-node{1,2,3} | grep -n "Node address"
docker exec gorgoroth-fukuii-node1 curl -s -X POST -H 'Content-Type: application/json' \
  --data '{"jsonrpc":"2.0","method":"net_peerCount","params":[],"id":1}' http://localhost:8546
```

### Results
- ✅ All 3 containers started successfully
- ✅ All containers healthy (health checks passing)  
- ❌ 0 peer connections established
- ❌ Nodes advertising wrong P2P port (9076 vs 30303)
- ❌ `fukuii-cli sync-static-nodes` fails (admin RPC namespace not exposed, cannot call `admin_nodeInfo`)
- ⚠️ Host/container `static-nodes.json` files now contain the most recent enodes *at the time of editing*, but the IDs change every restart because node keys are ephemeral
- 📁 Logs archived under `ops/gorgoroth/logs-3nodes-20251207-132004/`

## Network Status

| Node | Status | Peers | Advertised Port | Expected Port |
|------|--------|-------|----------------|---------------|
| node1 | HEALTHY | 0 | 9076 | 30303 |
| node2 | HEALTHY | 0 | 9076 | 30303 |
| node3 | HEALTHY | 0 | 9076 | 30303 |

### Actual Enode Addresses (from logs)

> ⚠️ **These values are volatile.** With no persisted `nodekey` the IDs below change every restart; see the “Latest Troubleshooting Session” section for the most recent set captured on 2025-12-07 19:17 UTC.

Historical snapshot (2025-12-07 05:50 UTC):

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

### Issue #3: Ephemeral Node Keys (No Persisted `nodekey` Files)

**Problem:**
- Neither `/app/data` nor `/home/fukuii/.fukuii/etc` inside the containers contains a `nodekey` file. Running `docker exec gorgoroth-fukuii-node1 sh -c 'find /home/fukuii -maxdepth 3 -name nodekey -print'` returns no results.
- As a result, every container restart generates a brand new Secp256k1 identity and therefore a brand new enode string.

**Impact:**
- Any static-nodes.json edits (manual or automated) are invalidated immediately after the next restart because the peer IDs no longer match.
- The new enodes still advertise port 9076, compounding the mismatch.
- Tooling that caches or distributes enodes cannot succeed until node keys are persisted on disk or injected deterministically.

**Evidence:**
```
$ docker exec gorgoroth-fukuii-node1 ls -al /app/data
total 13
drwxr-xr-x 2 fukuii fukuii 4096 Dec  5 04:34 .
drwxr-xr-x 1 fukuii fukuii 4096 Dec  7 19:17 ..
-rw-rw-r-- 1 fukuii fukuii  323 Dec  7 19:22 static-nodes.json

$ docker exec gorgoroth-fukuii-node1 sh -c 'find /home/fukuii -maxdepth 3 -name nodekey -print'
# (no output)
```

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

### Option 3: Update static-nodes.json with Actual Enode IDs (Manual Only — CLI Blocked)

The `fukuii-cli sync-static-nodes` path documented previously now fails because the enterprise build used for Gorgoroth disables the `admin` RPC namespace. The tool retries `admin_nodeInfo` five times against each container and exits with code 1, so operators must fall back to manual log scraping.

**Manual steps currently required:**

1. `./fukuii-cli.sh start 3nodes` (or `restart`) from `ops/tools`.
2. Wait for each node to emit `ServerActor - Node address: enode://...` in `docker logs`.
3. Copy the fresh enodes into every `ops/gorgoroth/conf/node*/static-nodes.json` file so each node references the other two.
4. Confirm the mounted file was refreshed via `docker exec gorgoroth-fukuii-nodeX cat /app/data/static-nodes.json`.
5. Restart the network again to force Besu to reload static peers.

**Limitations that remain:**

- The TCP port is still wrong (9076), so even correct static peers cannot handshake.
- There is no persisted `nodekey`, so the enodes change on every restart and the above procedure must be repeated constantly.
- `net_peerCount` continues to return `0x0`; no internal peering occurs.
- Admin RPC (needed for automation) is disabled, so tooling cannot be fixed without enabling that namespace or adding another data source for enodes.

Until the admin RPC is exposed or node keys are persisted, automation is not possible and manual edits only provide momentary alignment before the next restart.

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
3. ⚠️ Expose the admin RPC namespace or persist node keys so `fukuii-cli sync-static-nodes` can function (currently fails retrieving enodes)
4. ✅ Documented the manual workaround and troubleshooting procedures in this report

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
- `/home/runner/work/fukuii/fukuii/ops/tools/fukuii-cli.sh`

### Log Files
- `ops/gorgoroth/logs-3nodes-20251207-132004/gorgoroth-fukuii-node1.log`
- `ops/gorgoroth/logs-3nodes-20251207-132004/gorgoroth-fukuii-node2.log`
- `ops/gorgoroth/logs-3nodes-20251207-132004/gorgoroth-fukuii-node3.log`
- Previous run artifacts under `/tmp/3nodes-logs/` (kept for historical comparison)

## Conclusion

The Gorgoroth 3-node battlenet nodes cannot connect to each other due to three compounding issues:

1. **Primary Issue**: Configuration file loading bug causes nodes to use default port 9076 instead of the configured 30303.
2. **Secondary Issue**: Static-nodes.json files contain placeholder (or quickly outdated) enode IDs that don't match actual node identities.
3. **Tertiary Issue**: Node identities are regenerated every restart because no persistent `nodekey` file exists inside the containers, invalidating any manual or automated static-node updates.

The configuration loading bug is the critical blocker. Even with correct enode IDs in static-nodes.json, nodes would still fail to connect because they're advertising the wrong port. The bug is in `App.scala`'s `setNetworkConfig()` method which checks for filesystem files instead of classpath resources.

For troubleshooting purposes, this analysis successfully identifies the root causes of the connectivity issues. A proper fix requires code changes to the configuration loading logic and generation of valid static-nodes.json files.

## References

- Original task: Troubleshoot 3nodes battlenet network configuration and RLPx handshake
- README: `/home/runner/work/fukuii/fukuii/ops/gorgoroth/README.md`
- Detailed analysis: `/tmp/3nodes-battlenet-analysis.md`
- Collected logs: `/tmp/3nodes-logs/`
