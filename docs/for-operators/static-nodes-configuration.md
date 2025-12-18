# Static Nodes Configuration Feature

## Overview

This feature enables Fukuii nodes to load peer configuration from a `static-nodes.json` file in the data directory. This provides a flexible way to manage peer connections, especially useful for private networks, test environments, and enterprise deployments.

## Node Selection Modes

Fukuii supports different modes for controlling which peers to connect to, controlled by the `public` and `enterprise` modifiers:

### Default/Public Mode
1. On startup, Fukuii loads bootstrap nodes from the chain configuration file (e.g., `etc-chain.conf`)
2. If a `static-nodes.json` file exists in the data directory, those nodes are also loaded
3. Static nodes are **merged** with bootstrap nodes from the configuration
4. All nodes (bootstrap + static) are used for peer discovery and connection
5. **Best for**: Public networks, testnets, and scenarios where you want both your custom peers AND standard bootstrap nodes

### Enterprise Mode
1. On startup with `enterprise` modifier, Fukuii **ignores** bootstrap nodes from chain configuration
2. If a `static-nodes.json` file exists, **only** those nodes are used
3. Bootstrap nodes are skipped to avoid unintentional connections to public infrastructure
4. **Best for**: Private/permissioned networks where you want complete control over peer connections

**Usage:**
```bash
# Public mode - uses both bootstrap nodes and static-nodes.json
fukuii public etc

# Enterprise mode - uses ONLY static-nodes.json (ignores bootstrap nodes)
fukuii enterprise gorgoroth

# Default (no modifier) - uses both bootstrap nodes and static-nodes.json
fukuii etc
```

## File Location

The `static-nodes.json` file should be placed in your node's data directory:

- **Mainnet (ETC)**: `~/.fukuii/etc/static-nodes.json`
- **Mordor Testnet**: `~/.fukuii/mordor/static-nodes.json`
- **Gorgoroth Testnet**: `~/.fukuii/gorgoroth/static-nodes.json`
- **Custom network**: `~/.fukuii/<network-name>/static-nodes.json`

## File Format

The file should contain a JSON array of enode URLs:

```json
[
  "enode://6eecbdcc74c0b672ce505b9c639c3ef2e8ee8cddd8447ca7ab82c65041932db64a9cd4d7e723ba180b0c3d88d1f0b2913fda48972cdd6742fea59f900af084af@192.168.1.1:9076",
  "enode://a335a7e86eab05929266de232bec201a49fdcfc1115e8f8b861656e8afb3a6e5d3ffd172d153ae6c080401a56e3d620db2ac0695038a19e9b0c5220212651493@192.168.1.2:9076"
]
```

## Use Cases

### Private/Permissioned Networks

In private networks, the peer list may change frequently. Instead of modifying configuration files, you can simply update `static-nodes.json`:

```bash
# Update static nodes
cat > ~/.fukuii/gorgoroth/static-nodes.json << EOF
[
  "enode://<node-id-1>@192.168.1.10:9076",
  "enode://<node-id-2>@192.168.1.11:9076",
  "enode://<node-id-3>@192.168.1.12:9076"
]
EOF

# Restart node to pick up changes
systemctl restart fukuii
```

### Gorgoroth Test Network

For the Gorgoroth test network, use the `fukuii-cli` tool to automatically collect and synchronize static nodes across all client types (Fukuii, Core-Geth, and Besu):

```bash
# Start a 3-node Fukuii network
fukuii-cli start 3nodes

# Or start a mixed-client network
fukuii-cli start fukuii-geth    # 3 Fukuii + 3 Core-Geth
fukuii-cli start fukuii-besu    # 3 Fukuii + 3 Besu
fukuii-cli start mixed          # 3 Fukuii + 3 Core-Geth + 3 Besu

# Collect enode IDs and update static-nodes.json on all nodes
# Works with all client types - enodes are extracted from logs or RPC
fukuii-cli sync-static-nodes fukuii-geth

# Verify nodes are connected
fukuii-cli logs fukuii-geth
```

**Multi-Client Support:**
- The CLI automatically detects container types (Fukuii, Geth, Besu)
- Extracts enodes from logs (primary) or via admin_nodeInfo RPC (fallback)
- Updates static-nodes.json in the appropriate location for each client:
  - **Fukuii**: `conf/nodeN/static-nodes.json` (host-mounted config)
  - **Core-Geth**: `/root/.ethereum/static-nodes.json` (container volume)
  - **Besu**: `/opt/besu/data/static-nodes.json` (container volume)

### Automated Deployments

In automated deployments, you can generate `static-nodes.json` programmatically:

```bash
# Example: Generate static-nodes.json from Terraform outputs
terraform output -json node_enodes | jq -r '.[]' > ~/.fukuii/etc/static-nodes.json
```

## Features

- **Optional**: If the file doesn't exist, only bootstrap nodes from config are used
- **Validation**: Invalid enode URLs are logged and skipped
- **Error Handling**: JSON parsing errors are handled gracefully
- **Logging**: Informative messages about loaded static nodes

## Implementation Details

### StaticNodesLoader

The `StaticNodesLoader` utility class handles:
- Reading the JSON file from the data directory
- Parsing and validating enode URLs
- Error handling for missing or malformed files
- Logging of loaded nodes and any issues

### DiscoveryConfig

The `DiscoveryConfig` has been enhanced to:
- Load static nodes from the datadir on initialization
- Merge static nodes with bootstrap nodes from config
- Log information about the merged node set

## Testing

Comprehensive unit tests are included:
- Valid JSON file loading
- Non-existent file handling
- Invalid JSON handling
- Invalid enode URL filtering
- Empty array handling
- Datadir-based loading

All tests pass successfully.

## Backward Compatibility

This feature is fully backward compatible:
- Nodes without `static-nodes.json` work as before
- Existing bootstrap node configuration is unaffected
- No breaking changes to the API or configuration format

## Future Enhancements

Potential improvements for future versions:
- Hot-reload: Update static nodes without restarting the node
- API endpoint: Add/remove static nodes via JSON-RPC
- Persistence: Save discovered peers to static-nodes.json
- Templates: Pre-configured static-nodes.json for popular networks
