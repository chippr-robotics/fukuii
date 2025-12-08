# Node Key Persistence Fix for Gorgoroth Network

## Problem Summary

The Gorgoroth 3-node and 6-node test networks were experiencing persistent peer connection failures. Nodes could not discover or connect to each other, despite being in a controlled private network environment.

## Root Cause Analysis

According to the troubleshooting report, there were three compounding issues:

### 1. Configuration Loading Bug ✅ ALREADY FIXED
**Status:** Resolved in App.scala (lines 69-88)

The original code checked for network configuration files on the filesystem instead of classpath resources, causing nodes to use default port 9076 instead of the configured 30303.

**Fix:** App.scala now properly checks both filesystem and classpath resources:
```scala
private def setNetworkConfig(network: String): Unit = {
  val currentConfigFile = Option(System.getProperty("config.file"))
  findFilesystemConfig(network, currentConfigFile) match {
    case Some(file) =>
      // Use filesystem config if found
    case None =>
      val resourcePath = s"conf/$network.conf"
      val resourceExists = Option(getClass.getClassLoader.getResource(resourcePath)).isDefined
      if (resourceExists) {
        System.setProperty("config.resource", resourcePath)
        // Now uses classpath resource correctly
      }
  }
}
```

### 2. Placeholder Enode IDs in static-nodes.json ✅ FIXED
**Status:** Resolved by generating real keys

The static-nodes.json files contained placeholder enode IDs (all zeros) that didn't match actual node identities.

**Fix:** Pre-generated real node keys and updated static-nodes.json with actual enode URLs.

### 3. Ephemeral Node Keys ✅ FIXED
**Status:** Resolved by mounting persistent node.key files

**Problem:**
- No `nodekey` files existed inside containers at `/app/data/node.key`
- Every container restart generated brand new Secp256k1 identities
- Any static-nodes.json edits became invalid immediately after restart
- The `loadAsymmetricCipherKeyPair` function generates a new key if the file doesn't exist

**Evidence from loadAsymmetricCipherKeyPair (network/package.scala:36-56):**
```scala
def loadAsymmetricCipherKeyPair(filePath: String, secureRandom: SecureRandom): AsymmetricCipherKeyPair = {
  val file = new File(filePath)
  if (!file.exists()) {
    // Generates NEW key pair on every restart!
    val keysValuePair = generateKeyPair(secureRandom)
    // Tries to write to file, but volume mount prevents persistence
    ...
  } else {
    // Loads existing key from file
    ...
  }
}
```

**Impact:**
- Enode IDs changed on every restart
- Peer connections could never be established
- Manual static-nodes.json updates were immediately invalidated

## Solution

### Pre-generate Persistent Node Keys

Created `generate-node-keys.py` script that:
1. Generates secp256k1 key pairs for each node
2. Creates `node.key` files in node configuration directories
3. Calculates enode URLs from public keys
4. Updates `static-nodes.json` with correct peer enodes

### Node Key Format

Each `node.key` file contains:
```
<private_key_hex_64_chars>
<public_key_hex_128_chars>
```

Example:
```
40bce6bb00f1aede617b51ec52eb138e0afd2e0e17c77b49fd1d890907c4ccdf
c2a9c04d4bb9e33d5945aea50fb38fdb18da3a989166bc9312006654fcfa446ba78af5e589a8e61aeb593f0ca7e27d0a3d576b53caf82efdfd775a4b7f65cf6d
```

The public key becomes the node ID in the enode URL:
```
enode://c2a9c04d4bb9e33d5945aea50fb38fdb18da3a989166bc9312006654fcfa446ba78af5e589a8e61aeb593f0ca7e27d0a3d576b53caf82efdfd775a4b7f65cf6d@fukuii-node1:30303
```

### Docker Volume Mounts

Updated `docker-compose-3nodes.yml` and `docker-compose-6nodes.yml` to mount node.key files:

```yaml
volumes:
  - ./conf/app-gorgoroth-override.conf:/app/fukuii/conf/app.conf:ro
  - ./conf/base-gorgoroth.conf:/app/fukuii/conf/base-gorgoroth.conf:ro
  - ./conf/node1/gorgoroth.conf:/app/fukuii/conf/gorgoroth.conf:ro
  - ./conf/node1/static-nodes.json:/app/data/static-nodes.json:ro
  - ./conf/node1/node.key:/app/data/node.key:ro  # ← NEW: Persistent key
  - fukuii-node1-data:/app/data
  - fukuii-node1-logs:/app/logs
```

**Key Points:**
- Mounted as read-only (`:ro`) for security
- Located at `/app/data/node.key` (matches `fukuii.datadir` config)
- Persists across container restarts
- Each node has its own unique key

## Files Changed

### New Files:
1. `ops/gorgoroth/generate-node-keys.py` - Key generation script
2. `ops/gorgoroth/conf/node1/node.key` - Node 1 persistent key
3. `ops/gorgoroth/conf/node2/node.key` - Node 2 persistent key
4. `ops/gorgoroth/conf/node3/node.key` - Node 3 persistent key
5. `ops/gorgoroth/conf/node4/node.key` - Node 4 persistent key
6. `ops/gorgoroth/conf/node5/node.key` - Node 5 persistent key
7. `ops/gorgoroth/conf/node6/node.key` - Node 6 persistent key

### Modified Files:
1. `ops/gorgoroth/conf/node1/static-nodes.json` - Updated with real enodes
2. `ops/gorgoroth/conf/node2/static-nodes.json` - Updated with real enodes
3. `ops/gorgoroth/conf/node3/static-nodes.json` - Updated with real enodes
4. `ops/gorgoroth/conf/node4/static-nodes.json` - Updated with real enodes
5. `ops/gorgoroth/conf/node5/static-nodes.json` - Updated with real enodes
6. `ops/gorgoroth/conf/node6/static-nodes.json` - Updated with real enodes
7. `ops/gorgoroth/docker-compose-3nodes.yml` - Added node.key volume mounts
8. `ops/gorgoroth/docker-compose-6nodes.yml` - Added node.key volume mounts

## Testing Instructions

### Start the Network

```bash
cd ops/tools
./fukuii-cli.sh start 3nodes
```

Wait ~30 seconds for nodes to initialize and connect.

### Verify Peer Connections

```bash
# Check peer count on node1 (should be 2 for 3-node network)
curl -X POST -H "Content-Type: application/json" \
  --data '{"jsonrpc":"2.0","method":"net_peerCount","params":[],"id":1}' \
  http://localhost:8545

# Expected response: {"jsonrpc":"2.0","result":"0x2","id":1}
```

### Verify Persistence

```bash
# Restart the network
cd ops/tools
./fukuii-cli.sh restart 3nodes

# Wait 30 seconds and check peer count again
curl -X POST -H "Content-Type: application/json" \
  --data '{"jsonrpc":"2.0","method":"net_peerCount","params":[],"id":1}' \
  http://localhost:8545

# Should still be 0x2 - peers reconnect using same identities
```

### Verify Node Keys are Loaded

Check container logs to confirm node keys are being read:

```bash
docker logs gorgoroth-fukuii-node1 2>&1 | grep "Node address"
```

Expected output showing the enode with the correct public key:
```
Node address: enode://c2a9c04d4bb9e33d5945aea50fb38fdb18da3a989166bc9312006654fcfa446ba78af5e589a8e61aeb593f0ca7e27d0a3d576b53caf82efdfd775a4b7f65cf6d@[0:0:0:0:0:0:0:0]:30303
```

## Network Topology

### 3-Node Network

```
fukuii-node1 (172.25.0.11)
    ↕
fukuii-node2 (172.25.0.12)
    ↕
fukuii-node3 (172.25.0.13)
    ↕
fukuii-node1 (full mesh)
```

Each node connects to the other two nodes via static peer configuration.

### 6-Node Network

```
        node1 ← → node2
          ↕  ×  ×  ↕
        node3 ← → node4
          ↕  ×  ×  ↕
        node5 ← → node6
```

Full mesh topology - each node connects to all 5 other nodes.

## Regenerating Keys (if needed)

⚠️ **Warning:** Regenerating keys will create new node identities and break existing peer connections until all nodes are restarted.

```bash
cd ops/gorgoroth
./generate-node-keys.py

# Restart the network to apply new keys
cd ../tools
./fukuii-cli.sh restart 3nodes
```

## Security Considerations

1. **Private Keys in Version Control:** The node.key files contain private keys and are committed to git. This is acceptable for test networks but **DO NOT** do this for production networks.

2. **Read-only Mounts:** Keys are mounted as read-only to prevent accidental modification inside containers.

3. **Test Network Only:** These keys are for the Gorgoroth internal test network. Never use these keys on mainnet or public testnets.

## Why This Works

1. **Persistent Identity:** Each node now has a stable identity that survives restarts
2. **Correct Peer Information:** Static-nodes.json contains the actual public keys
3. **Proper Port Configuration:** App.scala fix ensures nodes use port 30303
4. **No Dependency on Admin RPC:** Pre-generated keys eliminate the need for `admin_nodeInfo` API

## Alternative Approaches Considered

### ❌ Using fukuii-cli sync-static-nodes
- Requires admin RPC namespace to be enabled
- Currently fails because admin API is disabled in enterprise mode
- Would still need persistent keys to avoid regeneration on restart

### ❌ Manual Enode Collection from Logs
- Labor intensive and error prone
- Requires manual updates after every restart
- Documented in troubleshooting report but not sustainable

### ✅ Pre-generated Persistent Keys (IMPLEMENTED)
- Simple and deterministic
- No runtime dependencies
- Works with current enterprise mode configuration
- Survives container restarts

## References

- Troubleshooting Report: `ops/gorgoroth/TROUBLESHOOTING_REPORT.md`
- Node Key Loading Code: `src/main/scala/com/chipprbots/ethereum/network/package.scala:36-56`
- Configuration Loading Fix: `src/main/scala/com/chipprbots/ethereum/App.scala:69-88`
- Static Nodes Loader: `src/main/scala/com/chipprbots/ethereum/network/discovery/StaticNodesLoader.scala`
