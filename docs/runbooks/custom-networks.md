# Custom Network Configuration Runbook

**Audience**: Operators deploying Fukuii on private or custom Ethereum networks  
**Estimated Time**: 30-45 minutes  
**Prerequisites**: 
- Basic understanding of Ethereum network parameters
- Familiarity with HOCON configuration format
- Knowledge of your network's genesis block and fork schedule

## Overview

This runbook explains how to configure Fukuii to connect to custom or private Ethereum networks without modifying the codebase. This is particularly useful for:

- Private consortium networks
- Development and testing environments
- Custom testnets
- Forked networks with modified parameters

## Table of Contents

1. [Quick Start](#quick-start)
2. [Understanding Chain Configuration](#understanding-chain-configuration)
3. [Creating a Custom Chain Configuration](#creating-a-custom-chain-configuration)
4. [Deploying Custom Configurations](#deploying-custom-configurations)
5. [Configuration Reference](#configuration-reference)
6. [Examples](#examples)
7. [Troubleshooting](#troubleshooting)

## Quick Start

### Step 1: Create Chain Configuration Directory

```bash
# Create directory for custom chain configurations
mkdir -p /etc/fukuii/chains
```

### Step 2: Create Chain Configuration File

Create a file named `<network-name>-chain.conf` in your chains directory. For example, `/etc/fukuii/chains/mynetwork-chain.conf`:

```hocon
{
  # Network identifier for peer discovery
  network-id = 12345
  
  # Chain ID for transaction signing (EIP-155)
  # IMPORTANT: Chain IDs are stored as signed bytes (-128 to 127)
  # Choose a value in this range to avoid overflow issues
  # Use hex format: "0x7B" = 123 in decimal
  chain-id = "0x7B"
  
  # Protocol capabilities
  # Per DevP2P spec: Only advertise the highest version of each protocol family
  # ETH versions are backward compatible - eth/68 includes all previous versions
  # SNAP is a separate protocol and should be advertised if supported
  capabilities = ["eth/68", "snap/1"]
  
  # Fork block numbers - set to 0 or appropriate values for your network
  frontier-block-number = "0"
  homestead-block-number = "0"
  eip150-block-number = "0"
  eip155-block-number = "0"
  eip160-block-number = "0"
  eip161-block-number = "1000000000000000000"
  byzantium-block-number = "0"
  constantinople-block-number = "1000000000000000000"
  petersburg-block-number = "1000000000000000000"
  istanbul-block-number = "1000000000000000000"
  
  # ETC-specific forks (set to far future if not applicable)
  atlantis-block-number = "1000000000000000000"
  agharta-block-number = "1000000000000000000"
  phoenix-block-number = "1000000000000000000"
  magneto-block-number = "1000000000000000000"
  mystique-block-number = "1000000000000000000"
  spiral-block-number = "1000000000000000000"
  
  # Treasury and checkpointing (ETC-specific, usually disabled for custom networks)
  treasury-address = "0011223344556677889900112233445566778899"
  ecip1098-block-number = "1000000000000000000"
  ecip1097-block-number = "1000000000000000000"
  ecip1099-block-number = "1000000000000000000"
  ecip1049-block-number = "1000000000000000000"
  
  # Difficulty bomb (usually disabled for custom networks)
  difficulty-bomb-pause-block-number = "0"
  difficulty-bomb-continue-block-number = "0"
  difficulty-bomb-removal-block-number = "0"
  
  # ETH-specific forks (set to far future if not applicable)
  muir-glacier-block-number = "1000000000000000000"
  berlin-block-number = "1000000000000000000"
  
  # Max code size (EIP-170)
  max-code-size = "24576"
  
  # DAO fork (usually disabled for custom networks)
  dao = null
  
  # Account starting nonce
  account-start-nonce = "0"
  
  # Custom genesis file
  custom-genesis-file = { include required("mynetwork-genesis.json") }
  
  # Monetary policy
  monetary-policy {
    first-era-block-reward = "5000000000000000000"
    first-era-reduced-block-reward = "3000000000000000000"
    first-era-constantinople-reduced-block-reward = "2000000000000000000"
    era-duration = 5000000
    reward-reduction-rate = 0.2
  }
  
  # Gas tie breaker (usually false)
  gas-tie-breaker = false
  
  # Storage format
  eth-compatible-storage = true
  
  # Bootstrap nodes for your network
  bootstrap-nodes = [
    "enode://PUBKEY@IP:PORT",
    "enode://PUBKEY@IP:PORT"
  ]
}
```

### Step 3: Create Genesis File (Optional)

If your network uses a custom genesis, create `mynetwork-genesis.json` in the same directory:

```json
{
  "difficulty": "0x20000",
  "extraData": "0x",
  "gasLimit": "0x2fefd8",
  "alloc": {
    "0x1234567890123456789012345678901234567890": {
      "balance": "0x200000000000000000000000000000000000000000000000000000000000000"
    }
  }
}
```

### Step 4: Configure Fukuii to Use Custom Chains

Create a custom configuration file `custom-network.conf`:

```hocon
# Include base configuration
include "app.conf"

fukuii {
  blockchains {
    # Set the network name to match your chain config file name
    network = "mynetwork"
    
    # Point to the directory containing your custom chain configs
    custom-chains-dir = "/etc/fukuii/chains"
  }
}
```

### Step 5: Launch Fukuii

```bash
# Launch with custom configuration
./bin/fukuii -Dconfig.file=/path/to/custom-network.conf

# Or use system property directly
./bin/fukuii \
  -Dfukuii.blockchains.network=mynetwork \
  -Dfukuii.blockchains.custom-chains-dir=/etc/fukuii/chains \
  etc
```

## Understanding Chain Configuration

Chain configurations define the fundamental parameters and rules for a blockchain network. These parameters include:

### Network Identity

- **network-id**: Used for peer discovery and handshaking. Each network should have a unique ID.
- **chain-id**: Used for transaction signing (EIP-155). Prevents replay attacks across different chains.
  - **Important**: Chain IDs are stored as signed bytes in Fukuii (range: -128 to 127)
  - Choose values within this range to avoid overflow issues
  - Common values: 1 (ETH), 61/0x3d (ETC), 63/0x3f (Mordor)
  - For custom networks, use values like 77/0x4D, 80/0x50, 100/0x64, 123/0x7B

### Protocol Capabilities

Protocol capabilities define which Ethereum subprotocols and versions your node supports. This is a critical configuration for proper peer communication.

**Important**: According to the [DevP2P specification](https://github.com/ethereum/devp2p/blob/master/caps/eth.md):
- ETH protocol versions are **backward compatible**
- You should **only advertise the highest version** of each protocol family
- When you advertise `eth/68`, peers understand you support all previous ETH versions (eth/63 through eth/67)
- SNAP is a **separate protocol** from ETH and should be advertised independently

**Correct configuration**:
```hocon
capabilities = ["eth/68", "snap/1"]
```

**Incorrect configuration** (listing all versions explicitly):
```hocon
# Don't do this - it's redundant and non-standard
capabilities = ["eth/63", "eth/64", "eth/65", "eth/66", "eth/67", "eth/68"]
```

**Supported capabilities in Fukuii**:
- `eth/68`: Latest ETH protocol (includes eth/63-67)
- `snap/1`: SNAP sync protocol for faster state synchronization
- `etc/64`: ETC-specific protocol variant

This aligns with how core-geth and go-ethereum handle capability advertisement.

### Fork Activation Blocks

Fork block numbers determine when specific protocol upgrades activate. Common forks include:

- **frontier**: Genesis block features
- **homestead**: First major upgrade (EIP-2)
- **eip150**: Gas cost changes
- **eip155**: Replay protection
- **byzantium**: Multiple improvements (EIP-609)
- **constantinople**: Various optimizations
- **istanbul**: Latest Ethereum improvements

For custom networks, you typically:
- Set forks you want to `"0"` (active from genesis)
- Set forks you don't want to `"1000000000000000000"` (far future, effectively disabled)

### Monetary Policy

Defines block rewards and how they change over time:

```hocon
monetary-policy {
  # Initial reward (in wei)
  first-era-block-reward = "5000000000000000000"  # 5 ETH
  
  # Era duration in blocks
  era-duration = 5000000
  
  # Reduction rate per era (0.0 = no reduction, 1.0 = full reduction)
  reward-reduction-rate = 0.2  # 20% reduction per era
}
```

### Bootstrap Nodes

Enode URLs of nodes that help new nodes discover peers:

```hocon
bootstrap-nodes = [
  "enode://PUBLIC_KEY@IP:PORT",
  "enode://PUBLIC_KEY@IP:PORT"
]
```

To get the enode URL of a node, query its admin API:
```bash
curl -X POST --data '{"jsonrpc":"2.0","method":"admin_nodeInfo","params":[],"id":1}' http://localhost:8546
```

## Creating a Custom Chain Configuration

### Minimal Configuration Template

For a simple private network, this minimal configuration is sufficient:

```hocon
{
  network-id = YOUR_NETWORK_ID
  chain-id = "YOUR_CHAIN_ID_HEX"
  # Per DevP2P spec: Only advertise highest version of each protocol family
  # ETH versions are backward compatible - eth/68 includes eth/63-67
  capabilities = ["eth/68", "snap/1"]
  
  # Enable all forks from genesis
  frontier-block-number = "0"
  homestead-block-number = "0"
  eip150-block-number = "0"
  eip155-block-number = "0"
  eip160-block-number = "0"
  byzantium-block-number = "0"
  constantinople-block-number = "0"
  petersburg-block-number = "0"
  istanbul-block-number = "0"
  
  # Disable ETC-specific forks
  eip161-block-number = "1000000000000000000"
  atlantis-block-number = "1000000000000000000"
  agharta-block-number = "1000000000000000000"
  phoenix-block-number = "1000000000000000000"
  magneto-block-number = "1000000000000000000"
  mystique-block-number = "1000000000000000000"
  spiral-block-number = "1000000000000000000"
  ecip1098-block-number = "1000000000000000000"
  ecip1097-block-number = "1000000000000000000"
  ecip1099-block-number = "1000000000000000000"
  muir-glacier-block-number = "1000000000000000000"
  berlin-block-number = "1000000000000000000"
  
  # Disable difficulty bomb
  difficulty-bomb-pause-block-number = "0"
  difficulty-bomb-continue-block-number = "0"
  difficulty-bomb-removal-block-number = "0"
  
  max-code-size = "24576"
  dao = null
  account-start-nonce = "0"
  custom-genesis-file = null
  treasury-address = "0011223344556677889900112233445566778899"
  
  monetary-policy {
    first-era-block-reward = "5000000000000000000"
    first-era-reduced-block-reward = "5000000000000000000"
    first-era-constantinople-reduced-block-reward = "5000000000000000000"
    era-duration = 500000000
    reward-reduction-rate = 0
  }
  
  gas-tie-breaker = false
  eth-compatible-storage = true
  bootstrap-nodes = []
}
```

### Configuration Checklist

When creating a custom chain configuration, ensure you:

- [ ] Choose a unique `network-id` not used by existing networks
- [ ] Choose a unique `chain-id` for replay protection
- [ ] Define fork activation blocks appropriate for your network
- [ ] Configure monetary policy (block rewards, era duration)
- [ ] Set bootstrap nodes for peer discovery
- [ ] Create genesis file if needed (or set `custom-genesis-file = null`)
- [ ] Verify all ETC-specific parameters are disabled if not needed
- [ ] Test configuration on a single node before deploying to network

## Deploying Custom Configurations

### Method 1: Using Custom Chains Directory (Recommended)

This method keeps chain configurations separate from the main config file and allows easy management of multiple custom networks.

**Step 1**: Create chains directory structure:
```bash
mkdir -p /opt/fukuii/chains
```

**Step 2**: Place chain config file:
```bash
# Create mynetwork-chain.conf
cat > /opt/fukuii/chains/mynetwork-chain.conf << 'EOF'
{
  network-id = 12345
  # Chain ID 0x7B (123 in decimal) - fits within byte range (-128 to 127)
  chain-id = "0x7B"
  # ... rest of configuration
}
EOF
```

**Step 3**: Create node configuration:
```bash
cat > /opt/fukuii/mynetwork.conf << 'EOF'
include "app.conf"

fukuii {
  blockchains {
    network = "mynetwork"
    custom-chains-dir = "/opt/fukuii/chains"
  }
}
EOF
```

**Step 4**: Launch:
```bash
./bin/fukuii -Dconfig.file=/opt/fukuii/mynetwork.conf
```

### Method 2: Using System Properties

For quick testing or scripted deployments:

```bash
./bin/fukuii \
  -Dfukuii.blockchains.network=mynetwork \
  -Dfukuii.blockchains.custom-chains-dir=/opt/fukuii/chains
```

### Method 3: Inline Configuration

For development or Docker deployments where you want everything in one file:

```hocon
include "app.conf"

fukuii {
  blockchains {
    network = "mynetwork"
    
    mynetwork {
      network-id = 12345
      # Chain ID 0x7B (123 in decimal) - fits within byte range
      chain-id = "0x7B"
      # ... rest of chain configuration
    }
  }
}
```

### Docker Deployment

When deploying with Docker, mount your custom chains directory:

```bash
docker run -d \
  --name fukuii-custom \
  -v /opt/fukuii/chains:/app/chains:ro \
  -e FUKUII_BLOCKCHAINS_NETWORK=mynetwork \
  -e FUKUII_BLOCKCHAINS_CUSTOM_CHAINS_DIR=/app/chains \
  chipprbots/fukuii:latest
```

Or using docker-compose:

```yaml
version: '3.8'
services:
  fukuii:
    image: chipprbots/fukuii:latest
    volumes:
      - ./chains:/app/chains:ro
      - fukuii-data:/app/data
    environment:
      - FUKUII_BLOCKCHAINS_NETWORK=mynetwork
      - FUKUII_BLOCKCHAINS_CUSTOM_CHAINS_DIR=/app/chains
    ports:
      - "8546:8546"
      - "30303:30303"
volumes:
  fukuii-data:
```

## Configuration Reference

### Network Identity Parameters

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `network-id` | Integer | Yes | Network identifier for peer discovery |
| `chain-id` | String (hex) | Yes | Chain ID for EIP-155 transaction signing |
| `capabilities` | Array | Yes | Protocol capabilities - advertise highest version only (e.g., ["eth/68", "snap/1"]) |

### Fork Block Numbers

All fork parameters are strings (decimal numbers):

| Parameter | Default | Description |
|-----------|---------|-------------|
| `frontier-block-number` | "0" | Genesis features |
| `homestead-block-number` | varies | Homestead fork (EIP-2) |
| `eip150-block-number` | varies | Gas cost changes |
| `eip155-block-number` | varies | Replay protection |
| `eip160-block-number` | varies | EXP cost increase |
| `byzantium-block-number` | varies | Byzantium fork (EIP-609) |
| `constantinople-block-number` | varies | Constantinople fork |
| `petersburg-block-number` | varies | Petersburg fork |
| `istanbul-block-number` | varies | Istanbul fork |

### Monetary Policy Parameters

| Parameter | Type | Description |
|-----------|------|-------------|
| `first-era-block-reward` | String | Initial block reward in wei |
| `era-duration` | Integer | Number of blocks per era |
| `reward-reduction-rate` | Double | Reduction rate per era (0.0-1.0) |

### Network Parameters

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `bootstrap-nodes` | Array | [] | List of enode URLs for peer discovery |
| `account-start-nonce` | String | "0" | Starting nonce for new accounts |
| `max-code-size` | String | "24576" | Maximum contract code size (EIP-170) |
| `eth-compatible-storage` | Boolean | true | Use Ethereum storage format |

## Examples

### Example 1: Simple Development Network

Perfect for local testing with all modern features enabled:

**File**: `/opt/fukuii/chains/devnet-chain.conf`
```hocon
{
  network-id = 9999
  # Chain ID 0x64 (100 in decimal) - fits within byte range
  chain-id = "0x64"
  # Per DevP2P spec: Only advertise highest version of each protocol family
  capabilities = ["eth/68", "snap/1"]
  
  # All forks enabled from genesis
  frontier-block-number = "0"
  homestead-block-number = "0"
  eip150-block-number = "0"
  eip155-block-number = "0"
  eip160-block-number = "0"
  eip161-block-number = "0"
  byzantium-block-number = "0"
  constantinople-block-number = "0"
  petersburg-block-number = "0"
  istanbul-block-number = "0"
  
  # Disable ETC-specific features
  atlantis-block-number = "1000000000000000000"
  agharta-block-number = "1000000000000000000"
  phoenix-block-number = "1000000000000000000"
  magneto-block-number = "1000000000000000000"
  mystique-block-number = "1000000000000000000"
  spiral-block-number = "1000000000000000000"
  ecip1098-block-number = "1000000000000000000"
  ecip1097-block-number = "1000000000000000000"
  ecip1099-block-number = "1000000000000000000"
  muir-glacier-block-number = "1000000000000000000"
  berlin-block-number = "1000000000000000000"
  
  difficulty-bomb-pause-block-number = "0"
  difficulty-bomb-continue-block-number = "0"
  difficulty-bomb-removal-block-number = "0"
  
  max-code-size = "24576"
  dao = null
  account-start-nonce = "0"
  custom-genesis-file = null
  treasury-address = "0011223344556677889900112233445566778899"
  
  # Fixed 5 ETH reward, no reduction
  monetary-policy {
    first-era-block-reward = "5000000000000000000"
    first-era-reduced-block-reward = "5000000000000000000"
    first-era-constantinople-reduced-block-reward = "5000000000000000000"
    era-duration = 500000000
    reward-reduction-rate = 0
  }
  
  gas-tie-breaker = false
  eth-compatible-storage = true
  
  # Single local node for development
  bootstrap-nodes = []
}
```

**Launch**:
```bash
./bin/fukuii \
  -Dfukuii.blockchains.network=devnet \
  -Dfukuii.blockchains.custom-chains-dir=/opt/fukuii/chains \
  -Dfukuii.mining.mining-enabled=true \
  -Dfukuii.mining.coinbase=0x1234567890123456789012345678901234567890
```

### Example 2: Multi-Node Consortium Network

Configuration for a permissioned consortium network:

**File**: `/opt/fukuii/chains/consortium-chain.conf`
```hocon
{
  network-id = 8888
  # Chain ID 0x50 (80 in decimal) - fits within byte range
  chain-id = "0x50"
  # Per DevP2P spec: Only advertise highest version of each protocol family
  capabilities = ["eth/68", "snap/1"]
  
  frontier-block-number = "0"
  homestead-block-number = "0"
  eip150-block-number = "0"
  eip155-block-number = "0"
  eip160-block-number = "0"
  byzantium-block-number = "0"
  constantinople-block-number = "0"
  petersburg-block-number = "0"
  istanbul-block-number = "100000"  # Scheduled upgrade at block 100,000
  
  # Disable all other forks
  eip161-block-number = "1000000000000000000"
  atlantis-block-number = "1000000000000000000"
  agharta-block-number = "1000000000000000000"
  phoenix-block-number = "1000000000000000000"
  magneto-block-number = "1000000000000000000"
  mystique-block-number = "1000000000000000000"
  spiral-block-number = "1000000000000000000"
  ecip1098-block-number = "1000000000000000000"
  ecip1097-block-number = "1000000000000000000"
  ecip1099-block-number = "1000000000000000000"
  muir-glacier-block-number = "1000000000000000000"
  berlin-block-number = "1000000000000000000"
  
  difficulty-bomb-pause-block-number = "0"
  difficulty-bomb-continue-block-number = "0"
  difficulty-bomb-removal-block-number = "0"
  
  max-code-size = "24576"
  dao = null
  account-start-nonce = "0"
  
  # Reference genesis file with pre-allocated accounts
  custom-genesis-file = { include required("consortium-genesis.json") }
  
  treasury-address = "0011223344556677889900112233445566778899"
  
  monetary-policy {
    first-era-block-reward = "2000000000000000000"  # 2 ETH
    first-era-reduced-block-reward = "2000000000000000000"
    first-era-constantinople-reduced-block-reward = "2000000000000000000"
    era-duration = 500000000
    reward-reduction-rate = 0
  }
  
  gas-tie-breaker = false
  eth-compatible-storage = true
  
  # Bootstrap nodes for consortium members
  bootstrap-nodes = [
    "enode://NODE1_PUBKEY@192.168.1.10:30303",
    "enode://NODE2_PUBKEY@192.168.1.11:30303",
    "enode://NODE3_PUBKEY@192.168.1.12:30303"
  ]
}
```

**Genesis file** `/opt/fukuii/chains/consortium-genesis.json`:
```json
{
  "difficulty": "0x20000",
  "extraData": "0x",
  "gasLimit": "0x47b760",
  "alloc": {
    "0x1234567890123456789012345678901234567890": {
      "balance": "0x200000000000000000000000000000000000000000000000000000000000000"
    },
    "0x0987654321098765432109876543210987654321": {
      "balance": "0x200000000000000000000000000000000000000000000000000000000000000"
    }
  }
}
```

### Example 3: Testnet with Scheduled Forks

Configuration for a test network with planned fork activations:

**File**: `/opt/fukuii/chains/testnet-chain.conf`
```hocon
{
  network-id = 7777
  # Chain ID 0x4D (77 in decimal) - fits within byte range
  chain-id = "0x4D"
  # Per DevP2P spec: Only advertise highest version of each protocol family
  capabilities = ["eth/68", "snap/1"]
  
  # Progressive fork activation
  frontier-block-number = "0"
  homestead-block-number = "100"
  eip150-block-number = "500"
  eip155-block-number = "1000"
  eip160-block-number = "1000"
  byzantium-block-number = "5000"
  constantinople-block-number = "10000"
  petersburg-block-number = "10000"
  istanbul-block-number = "50000"
  
  # Future planned forks
  berlin-block-number = "100000"
  
  # Disable ETC-specific
  eip161-block-number = "1000000000000000000"
  atlantis-block-number = "1000000000000000000"
  agharta-block-number = "1000000000000000000"
  phoenix-block-number = "1000000000000000000"
  magneto-block-number = "1000000000000000000"
  mystique-block-number = "1000000000000000000"
  spiral-block-number = "1000000000000000000"
  ecip1098-block-number = "1000000000000000000"
  ecip1097-block-number = "1000000000000000000"
  ecip1099-block-number = "1000000000000000000"
  muir-glacier-block-number = "1000000000000000000"
  
  difficulty-bomb-pause-block-number = "0"
  difficulty-bomb-continue-block-number = "0"
  difficulty-bomb-removal-block-number = "0"
  
  max-code-size = "24576"
  dao = null
  account-start-nonce = "0"
  custom-genesis-file = null
  treasury-address = "0011223344556677889900112233445566778899"
  
  monetary-policy {
    first-era-block-reward = "5000000000000000000"
    first-era-reduced-block-reward = "3000000000000000000"
    first-era-constantinople-reduced-block-reward = "2000000000000000000"
    era-duration = 25000
    reward-reduction-rate = 0.2
  }
  
  gas-tie-breaker = false
  eth-compatible-storage = true
  
  bootstrap-nodes = [
    "enode://TESTNET_NODE1@testnet1.example.com:30303",
    "enode://TESTNET_NODE2@testnet2.example.com:30303"
  ]
}
```

## Troubleshooting

### Configuration Not Loaded

**Problem**: Custom chain configuration is not being used.

**Solutions**:

1. Verify the chain config file name matches the pattern `<network>-chain.conf`:
   ```bash
   ls -la /opt/fukuii/chains/
   # Should show: mynetwork-chain.conf
   ```

2. Check that `custom-chains-dir` property is set correctly:
   ```bash
   ./bin/fukuii \
     -Dfukuii.blockchains.custom-chains-dir=/opt/fukuii/chains \
     -Dfukuii.blockchains.network=mynetwork
   ```

3. Verify directory permissions:
   ```bash
   ls -ld /opt/fukuii/chains
   # Should be readable by the user running Fukuii
   ```

4. Check logs for configuration loading errors:
   ```bash
   tail -f ~/.fukuii/mynetwork/logs/fukuii.log
   ```

### Network ID Mismatch

**Problem**: Node cannot connect to peers, shows network ID mismatch.

**Solution**: Ensure all nodes in your network use the same `network-id`:

```bash
# Check your node's network ID via RPC
curl -X POST --data '{"jsonrpc":"2.0","method":"net_version","params":[],"id":1}' \
  http://localhost:8546
```

### Genesis Hash Mismatch

**Problem**: Nodes reject each other due to different genesis hashes.

**Solution**: Ensure all nodes use the exact same genesis file:

1. Generate genesis on one node
2. Distribute the same genesis file to all nodes
3. Verify genesis hash matches:
   ```bash
   curl -X POST --data '{"jsonrpc":"2.0","method":"eth_getBlockByNumber","params":["0x0",false],"id":1}' \
     http://localhost:8546 | jq '.result.hash'
   ```

### Fork Configuration Errors

**Problem**: Node fails to validate blocks after a fork activation.

**Solution**:

1. Verify fork block numbers are in ascending order
2. Ensure all nodes upgrade before fork activation
3. Check that fork blocks align with network consensus:
   ```bash
   # Verify current block number
   curl -X POST --data '{"jsonrpc":"2.0","method":"eth_blockNumber","params":[],"id":1}' \
     http://localhost:8546
   ```

### Missing Bootstrap Nodes

**Problem**: Node cannot discover peers.

**Solutions**:

1. Add at least one bootstrap node to the configuration
2. Verify bootstrap nodes are reachable:
   ```bash
   # Test connection to bootstrap node
   nc -zv 192.168.1.10 30303
   ```

3. Temporarily add peer manually via admin API:
   ```bash
   curl -X POST --data '{
     "jsonrpc":"2.0",
     "method":"admin_addPeer",
     "params":["enode://PUBKEY@IP:PORT"],
     "id":1
   }' http://localhost:8546
   ```

4. Check firewall rules allow P2P traffic:
   ```bash
   # Ensure ports 9076 (TCP) and 30303 (UDP/TCP) are open
   sudo ufw allow 9076/tcp
   sudo ufw allow 30303/tcp
   sudo ufw allow 30303/udp
   ```

## Related Documentation

- [Node Configuration Runbook](node-configuration.md) - General configuration options
- [First Start Runbook](first-start.md) - Initial node setup
- [Peering Runbook](peering.md) - Network connectivity troubleshooting

## Additional Resources

- [EIP-155: Simple replay attack protection](https://eips.ethereum.org/EIPS/eip-155)
- [EIP-170: Contract code size limit](https://eips.ethereum.org/EIPS/eip-170)
- [Ethereum Fork History](https://ethereum.org/en/history/)
- [Private Networks Guide](https://geth.ethereum.org/docs/fundamentals/private-network)

---

**Document Version**: 1.0  
**Last Updated**: 2025-12-05  
**Maintainer**: Chippr Robotics LLC
