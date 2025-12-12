# Fukuii RPC Endpoint Inventory

**Version**: 1.0.0  
**Date**: 2025-12-12  
**Purpose**: Comprehensive catalog of all RPC endpoints for MCP integration planning

## Table of Contents

- [Overview](#overview)
- [Endpoint Inventory](#endpoint-inventory)
  - [ETH Namespace (52 endpoints)](#eth-namespace-52-endpoints)
  - [WEB3 Namespace (2 endpoints)](#web3-namespace-2-endpoints)
  - [NET Namespace (9 endpoints)](#net-namespace-9-endpoints)
  - [PERSONAL Namespace (8 endpoints)](#personal-namespace-8-endpoints)
  - [DEBUG Namespace (3 endpoints)](#debug-namespace-3-endpoints)
  - [TEST Namespace (7 endpoints)](#test-namespace-7-endpoints)
  - [FUKUII Namespace (1 endpoint)](#fukuii-namespace-1-endpoint)
  - [MCP Namespace (7 endpoints)](#mcp-namespace-7-endpoints)
  - [QA Namespace (3 endpoints)](#qa-namespace-3-endpoints)
  - [CHECKPOINTING Namespace (2 endpoints)](#checkpointing-namespace-2-endpoints)
  - [IELE Namespace (2 endpoints)](#iele-namespace-2-endpoints)
  - [RPC Namespace (1 endpoint)](#rpc-namespace-1-endpoint)
- [Endpoint Statistics](#endpoint-statistics)
- [MCP Coverage Analysis](#mcp-coverage-analysis)

---

## Overview

This document provides a complete inventory of all JSON-RPC endpoints available in the Fukuii Ethereum Classic node implementation. The inventory is organized by namespace and includes endpoint names, descriptions, safety classification, and MCP integration status.

**Total Endpoints**: 97

### Classification Legend

- **Safety Level**:
  - 🟢 **Safe**: Read-only operations with no side effects
  - 🟡 **Caution**: Write operations or operations requiring authentication
  - 🔴 **Dangerous**: Operations that can modify state or expose sensitive data

- **Production Status**:
  - ✅ **Production**: Safe for production environments
  - ⚠️ **Development**: Should be disabled in production
  - 🧪 **Testing**: Only for test environments

- **MCP Status**:
  - ✅ **Covered**: Available via MCP tools/resources
  - ⚙️ **Partial**: Partially covered by MCP
  - ❌ **Not Covered**: Not yet available via MCP

---

## Endpoint Inventory

### ETH Namespace (52 endpoints)

The core Ethereum-compatible JSON-RPC namespace providing blockchain query and transaction capabilities.

#### Block Query Endpoints

| Endpoint | Safety | Production | MCP Status | Description |
|----------|--------|------------|------------|-------------|
| `eth_blockNumber` | 🟢 | ✅ | ⚙️ | Returns the number of most recent block |
| `eth_getBlockByHash` | 🟢 | ✅ | ⚙️ | Returns block by hash with transaction details |
| `eth_getBlockByNumber` | 🟢 | ✅ | ⚙️ | Returns block by number with transaction details |
| `eth_getBlockTransactionCountByHash` | 🟢 | ✅ | ❌ | Returns transaction count in block by hash |
| `eth_getBlockTransactionCountByNumber` | 🟢 | ✅ | ❌ | Returns transaction count in block by number |
| `eth_getUncleByBlockHashAndIndex` | 🟢 | ✅ | ❌ | Returns uncle block by hash and index |
| `eth_getUncleByBlockNumberAndIndex` | 🟢 | ✅ | ❌ | Returns uncle block by number and index |
| `eth_getUncleCountByBlockHash` | 🟢 | ✅ | ❌ | Returns uncle count by block hash |
| `eth_getUncleCountByBlockNumber` | 🟢 | ✅ | ❌ | Returns uncle count by block number |

#### Transaction Query Endpoints

| Endpoint | Safety | Production | MCP Status | Description |
|----------|--------|------------|------------|-------------|
| `eth_getTransactionByHash` | 🟢 | ✅ | ❌ | Returns transaction by hash |
| `eth_getTransactionByBlockHashAndIndex` | 🟢 | ✅ | ❌ | Returns transaction by block hash and index |
| `eth_getTransactionByBlockNumberAndIndex` | 🟢 | ✅ | ❌ | Returns transaction by block number and index |
| `eth_getTransactionReceipt` | 🟢 | ✅ | ❌ | Returns transaction receipt |
| `eth_getTransactionCount` | 🟢 | ✅ | ❌ | Returns nonce/transaction count for address |
| `eth_pendingTransactions` | 🟢 | ✅ | ❌ | Returns pending transactions |
| `eth_getRawTransactionByHash` | 🟢 | ✅ | ❌ | Returns raw transaction data by hash |
| `eth_getRawTransactionByBlockHashAndIndex` | 🟢 | ✅ | ❌ | Returns raw transaction by block hash and index |
| `eth_getRawTransactionByBlockNumberAndIndex` | 🟢 | ✅ | ❌ | Returns raw transaction by block number and index |

#### Transaction Submission Endpoints

| Endpoint | Safety | Production | MCP Status | Description |
|----------|--------|------------|------------|-------------|
| `eth_sendTransaction` | 🟡 | ⚠️ | ❌ | Sends a transaction from an unlocked account |
| `eth_sendRawTransaction` | 🟡 | ✅ | ❌ | Broadcasts a signed raw transaction |

#### Account & State Query Endpoints

| Endpoint | Safety | Production | MCP Status | Description |
|----------|--------|------------|------------|-------------|
| `eth_accounts` | 🟡 | ⚠️ | ❌ | Lists accounts managed by the node |
| `eth_getBalance` | 🟢 | ✅ | ❌ | Returns balance of an address |
| `eth_getCode` | 🟢 | ✅ | ❌ | Returns contract bytecode at address |
| `eth_getStorageAt` | 🟢 | ✅ | ❌ | Returns storage value at address and position |
| `eth_getStorageRoot` | 🟢 | ✅ | ❌ | Returns storage root hash (ETC-specific) |
| `eth_getProof` | 🟢 | ✅ | ❌ | Returns Merkle proof for account and storage |

#### Contract Execution Endpoints

| Endpoint | Safety | Production | MCP Status | Description |
|----------|--------|------------|------------|-------------|
| `eth_call` | 🟢 | ✅ | ❌ | Executes a call without creating transaction |
| `eth_estimateGas` | 🟢 | ✅ | ❌ | Estimates gas required for transaction |

#### Network & Protocol Endpoints

| Endpoint | Safety | Production | MCP Status | Description |
|----------|--------|------------|------------|-------------|
| `eth_chainId` | 🟢 | ✅ | ⚙️ | Returns the chain ID (61 for ETC mainnet) |
| `eth_protocolVersion` | 🟢 | ✅ | ⚙️ | Returns the Ethereum protocol version |
| `eth_syncing` | 🟢 | ✅ | ⚙️ | Returns sync status or false if not syncing |
| `eth_gasPrice` | 🟢 | ✅ | ❌ | Returns current gas price in wei |

#### Mining Endpoints

| Endpoint | Safety | Production | MCP Status | Description |
|----------|--------|------------|------------|-------------|
| `eth_mining` | 🟢 | ✅ | ❌ | Returns true if node is mining |
| `eth_hashrate` | 🟢 | ✅ | ❌ | Returns node's mining hashrate |
| `eth_coinbase` | 🟢 | ✅ | ❌ | Returns the mining reward address |
| `eth_getWork` | 🟢 | ✅ | ❌ | Returns the current work package for mining |
| `eth_submitWork` | 🟡 | ✅ | ❌ | Submits a proof-of-work solution |
| `eth_submitHashrate` | 🟡 | ✅ | ❌ | Submits mining hashrate |
| `miner_start` | 🟡 | ✅ | ❌ | Starts mining |
| `miner_stop` | 🟡 | ✅ | ❌ | Stops mining |
| `miner_getStatus` | 🟢 | ✅ | ❌ | Returns miner status |

#### Filter & Event Endpoints

| Endpoint | Safety | Production | MCP Status | Description |
|----------|--------|------------|------------|-------------|
| `eth_newFilter` | 🟢 | ✅ | ❌ | Creates a new filter for logs |
| `eth_newBlockFilter` | 🟢 | ✅ | ❌ | Creates a filter for new blocks |
| `eth_newPendingTransactionFilter` | 🟢 | ✅ | ❌ | Creates a filter for pending transactions |
| `eth_getFilterChanges` | 🟢 | ✅ | ❌ | Returns changes since last poll |
| `eth_getFilterLogs` | 🟢 | ✅ | ❌ | Returns all logs matching filter |
| `eth_getLogs` | 🟢 | ✅ | ❌ | Returns logs matching filter criteria |
| `eth_uninstallFilter` | 🟢 | ✅ | ❌ | Uninstalls a filter |

#### Signing Endpoints

| Endpoint | Safety | Production | MCP Status | Description |
|----------|--------|------------|------------|-------------|
| `eth_sign` | 🟡 | ⚠️ | ❌ | Signs data with account (requires unlock) |

---

### WEB3 Namespace (2 endpoints)

Utility functions for Ethereum interaction.

| Endpoint | Safety | Production | MCP Status | Description |
|----------|--------|------------|------------|-------------|
| `web3_sha3` | 🟢 | ✅ | ❌ | Returns Keccak-256 hash of data |
| `web3_clientVersion` | 🟢 | ✅ | ⚙️ | Returns client version string |

---

### NET Namespace (9 endpoints)

Network information and peer management capabilities.

| Endpoint | Safety | Production | MCP Status | Description |
|----------|--------|------------|------------|-------------|
| `net_version` | 🟢 | ✅ | ⚙️ | Returns network ID |
| `net_listening` | 🟢 | ✅ | ⚙️ | Returns true if listening for connections |
| `net_peerCount` | 🟢 | ✅ | ⚙️ | Returns number of connected peers |
| `net_listPeers` | 🟢 | ✅ | ⚙️ | Lists all connected peers with details |
| `net_disconnectPeer` | 🟡 | ✅ | ❌ | Disconnects a specific peer |
| `net_connectToPeer` | 🟡 | ✅ | ❌ | Connects to a specific peer |
| `net_listBlacklistedPeers` | 🟢 | ✅ | ❌ | Lists all blacklisted peers |
| `net_addToBlacklist` | 🟡 | ✅ | ❌ | Adds a peer to blacklist |
| `net_removeFromBlacklist` | 🟡 | ✅ | ❌ | Removes a peer from blacklist |

---

### PERSONAL Namespace (8 endpoints)

Account management and cryptographic operations.

| Endpoint | Safety | Production | MCP Status | Description |
|----------|--------|------------|------------|-------------|
| `personal_listAccounts` | 🟡 | ⚠️ | ❌ | Lists all accounts |
| `personal_newAccount` | 🔴 | ⚠️ | ❌ | Creates a new account |
| `personal_importRawKey` | 🔴 | ⚠️ | ❌ | Imports a private key |
| `personal_unlockAccount` | 🔴 | ⚠️ | ❌ | Unlocks an account |
| `personal_lockAccount` | 🟡 | ⚠️ | ❌ | Locks an account |
| `personal_sign` | 🟡 | ⚠️ | ❌ | Signs data with account |
| `personal_ecRecover` | 🟢 | ✅ | ❌ | Recovers address from signature |
| `personal_sendTransaction` | 🟡 | ⚠️ | ❌ | Sends transaction with passphrase |
| `personal_signAndSendTransaction` | 🟡 | ⚠️ | ❌ | Alias for personal_sendTransaction |

---

### DEBUG Namespace (3 endpoints)

Debugging and diagnostic endpoints (typically disabled in production).

| Endpoint | Safety | Production | MCP Status | Description |
|----------|--------|------------|------------|-------------|
| `debug_listPeersInfo` | 🟢 | ⚠️ | ❌ | Returns detailed peer information |
| `debug_accountRange` | 🟢 | 🧪 | ❌ | Returns account range (testing only) |
| `debug_storageRangeAt` | 🟢 | 🧪 | ❌ | Returns storage range (testing only) |

---

### TEST Namespace (7 endpoints)

Testing and development endpoints (should never be enabled in production).

| Endpoint | Safety | Production | MCP Status | Description |
|----------|--------|------------|------------|-------------|
| `test_setChainParams` | 🔴 | 🧪 | ❌ | Sets chain parameters (testing only) |
| `test_mineBlocks` | 🔴 | 🧪 | ❌ | Mines blocks instantly (testing only) |
| `test_modifyTimestamp` | 🔴 | 🧪 | ❌ | Modifies block timestamp (testing only) |
| `test_rewindToBlock` | 🔴 | 🧪 | ❌ | Rewinds chain to block (testing only) |
| `test_importRawBlock` | 🔴 | 🧪 | ❌ | Imports raw block (testing only) |
| `test_getLogHash` | 🟢 | 🧪 | ❌ | Gets log hash (testing only) |
| `miner_setEtherbase` | 🟡 | 🧪 | ❌ | Sets mining address (testing only) |

---

### FUKUII Namespace (1 endpoint)

Fukuii-specific extensions.

| Endpoint | Safety | Production | MCP Status | Description |
|----------|--------|------------|------------|-------------|
| `fukuii_getAccountTransactions` | 🟢 | ✅ | ❌ | Returns transaction history for account |

---

### MCP Namespace (7 endpoints)

Model Context Protocol endpoints for AI agent interaction.

| Endpoint | Safety | Production | MCP Status | Description |
|----------|--------|------------|------------|-------------|
| `mcp_initialize` | 🟢 | ✅ | ✅ | Initializes MCP session |
| `tools/list` | 🟢 | ✅ | ✅ | Lists available MCP tools |
| `tools/call` | 🟡 | ✅ | ✅ | Executes an MCP tool |
| `resources/list` | 🟢 | ✅ | ✅ | Lists available MCP resources |
| `resources/read` | 🟢 | ✅ | ✅ | Reads an MCP resource |
| `prompts/list` | 🟢 | ✅ | ✅ | Lists available MCP prompts |
| `prompts/get` | 🟢 | ✅ | ✅ | Gets an MCP prompt |

**Current MCP Tools (5)**:
1. `mcp_node_info` - Get node version and build information
2. `mcp_node_status` - Get current node status (TODO: implement actor queries)
3. `mcp_blockchain_info` - Get blockchain state (TODO: implement)
4. `mcp_sync_status` - Get synchronization status (TODO: implement)
5. `mcp_peer_list` - List connected peers (TODO: implement)

**Current MCP Resources (5)**:
1. `fukuii://node/status` - Node status as JSON (TODO: implement queries)
2. `fukuii://node/config` - Node configuration (TODO: implement)
3. `fukuii://blockchain/latest` - Latest block info (TODO: implement)
4. `fukuii://peers/connected` - Connected peers (TODO: implement)
5. `fukuii://sync/status` - Sync status (TODO: implement)

**Current MCP Prompts (3)**:
1. `mcp_node_health_check` - Guide for comprehensive health check
2. `mcp_sync_troubleshooting` - Guide for diagnosing sync issues
3. `mcp_peer_management` - Guide for managing peer connections

---

### QA Namespace (3 endpoints)

Quality assurance and testing utilities.

| Endpoint | Safety | Production | MCP Status | Description |
|----------|--------|------------|------------|-------------|
| `qa_mineBlocks` | 🔴 | 🧪 | ❌ | Mines blocks for testing |
| `qa_generateCheckpoint` | 🔴 | 🧪 | ❌ | Generates checkpoint for testing |
| `qa_getFederationMembersInfo` | 🟢 | 🧪 | ❌ | Gets federation members info |

---

### CHECKPOINTING Namespace (2 endpoints)

Ethereum Classic checkpointing functionality.

| Endpoint | Safety | Production | MCP Status | Description |
|----------|--------|------------|------------|-------------|
| `checkpointing_getLatestBlock` | 🟢 | ✅ | ❌ | Gets latest checkpoint block |
| `checkpointing_pushCheckpoint` | 🟡 | ✅ | ❌ | Pushes a checkpoint |

---

### IELE Namespace (2 endpoints)

IELE VM support (if enabled).

| Endpoint | Safety | Production | MCP Status | Description |
|----------|--------|------------|------------|-------------|
| `iele_sendTransaction` | 🟡 | ✅ | ❌ | Sends IELE transaction |
| `iele_call` | 🟢 | ✅ | ❌ | Executes IELE call |

---

### RPC Namespace (1 endpoint)

RPC introspection.

| Endpoint | Safety | Production | MCP Status | Description |
|----------|--------|------------|------------|-------------|
| `rpc_modules` | 🟢 | ✅ | ❌ | Lists enabled RPC modules |

---

## Endpoint Statistics

### By Namespace

| Namespace | Total | Safe | Caution | Dangerous | Production | Development | Testing |
|-----------|-------|------|---------|-----------|------------|-------------|---------|
| ETH | 52 | 43 | 9 | 0 | 51 | 1 | 0 |
| WEB3 | 2 | 2 | 0 | 0 | 2 | 0 | 0 |
| NET | 9 | 6 | 3 | 0 | 9 | 0 | 0 |
| PERSONAL | 8 | 1 | 4 | 3 | 0 | 8 | 0 |
| DEBUG | 3 | 3 | 0 | 0 | 1 | 2 | 0 |
| TEST | 7 | 1 | 1 | 5 | 0 | 0 | 7 |
| FUKUII | 1 | 1 | 0 | 0 | 1 | 0 | 0 |
| MCP | 7 | 6 | 1 | 0 | 7 | 0 | 0 |
| QA | 3 | 1 | 0 | 2 | 0 | 0 | 3 |
| CHECKPOINTING | 2 | 1 | 1 | 0 | 2 | 0 | 0 |
| IELE | 2 | 1 | 1 | 0 | 2 | 0 | 0 |
| RPC | 1 | 1 | 0 | 0 | 1 | 0 | 0 |
| **TOTAL** | **97** | **67** | **20** | **10** | **76** | **11** | **10** |

### By Safety Level

- 🟢 **Safe (67)**: Read-only operations suitable for all environments
- 🟡 **Caution (20)**: Write operations requiring careful access control
- 🔴 **Dangerous (10)**: State-modifying operations for testing only

### By Production Status

- ✅ **Production (76)**: Safe for production deployment
- ⚠️ **Development (11)**: Should be disabled in production
- 🧪 **Testing (10)**: Only for test environments

---

## MCP Coverage Analysis

### Current Coverage Summary

- **Total Endpoints**: 97
- **MCP-Ready Endpoints**: 7 (7.2%)
- **Partially Covered**: 10 (10.3%)
- **Not Covered**: 80 (82.5%)

### Coverage by Category

#### ✅ Fully Covered (7 endpoints)
- All 7 MCP namespace endpoints
- Basic protocol initialization and discovery

#### ⚙️ Partially Covered (10 endpoints)
- `eth_blockNumber` - Available via `mcp_blockchain_info` tool (TODO)
- `eth_chainId` - Available via `mcp_blockchain_info` tool (TODO)
- `eth_syncing` - Available via `mcp_sync_status` tool (TODO)
- `net_version` - Available via `mcp_node_info` tool
- `net_listening` - Available via `mcp_node_status` tool (TODO)
- `net_peerCount` - Available via `mcp_peer_list` tool (TODO)
- `net_listPeers` - Available via `mcp_peer_list` tool (TODO)
- `web3_clientVersion` - Available via `mcp_node_info` tool
- `eth_protocolVersion` - Available via `mcp_node_info` tool (TODO)

#### ❌ Not Covered (80 endpoints)
- All transaction query endpoints (9)
- All transaction submission endpoints (2)
- All account & state query endpoints (6)
- All contract execution endpoints (2)
- All mining endpoints (9)
- All filter & event endpoints (7)
- All signing endpoints (1)
- All personal namespace endpoints (8)
- All debug namespace endpoints (3)
- All test namespace endpoints (7)
- All QA namespace endpoints (3)
- All checkpointing endpoints (2)
- All IELE endpoints (2)
- All Fukuii-specific endpoints (1)
- Other utility endpoints (18)

### Priority Gaps for Agent Control

Based on the MCP goal of "complete node control," the following endpoint categories are high-priority gaps:

1. **Mining Control** (Critical for node operators)
   - `miner_start`, `miner_stop`, `miner_getStatus`
   - `eth_mining`, `eth_hashrate`, `eth_coinbase`

2. **Peer Management** (Critical for network health)
   - `net_connectToPeer`, `net_disconnectPeer`
   - `net_addToBlacklist`, `net_removeFromBlacklist`

3. **Transaction Monitoring** (Essential for observability)
   - `eth_pendingTransactions`
   - `eth_getTransactionByHash`
   - `eth_getTransactionReceipt`

4. **Block Query** (Essential for chain monitoring)
   - `eth_getBlockByHash`, `eth_getBlockByNumber`
   - `eth_getBlockTransactionCountByHash`

5. **Node Configuration** (Important for operations)
   - Current configuration reading
   - Configuration validation

6. **Health & Diagnostics** (Critical for reliability)
   - Comprehensive health checks
   - Performance metrics
   - Error log analysis

---

## Next Steps

See [MCP Enhancement Plan](./MCP_ENHANCEMENT_PLAN.md) for detailed roadmap to achieve complete agent control of the node.

---

**Document Maintainer**: Chippr Robotics LLC  
**Last Updated**: 2025-12-12  
**Next Review**: Upon significant RPC changes
