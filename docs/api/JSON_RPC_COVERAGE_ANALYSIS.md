# JSON-RPC Coverage Analysis

This document provides a comprehensive analysis of fukuii's JSON-RPC implementation compared to the Ethereum JSON-RPC specification.

> **Note:** This analysis was written before the MCP namespace was added (Nov 2025). See [RPC Endpoint Inventory](./RPC_ENDPOINT_INVENTORY.md) for the current 97-endpoint catalog.

**Date**: 2025-11-24
**Purpose**: Identify gaps in JSON-RPC endpoint coverage and plan for MCP server integration

## Executive Summary

Fukuii implements **78 JSON-RPC endpoints** across **10 namespaces**. The implementation covers all core Ethereum JSON-RPC methods plus several extensions specific to Ethereum Classic and testing/development needs.

**Coverage Status**:
- ✅ **Complete**: All standard Ethereum JSON-RPC methods are implemented
- ✅ **Extended**: Additional ETC-specific and development endpoints
- ⚠️ **Partial**: Some newer EIP methods may need verification
- 📋 **Custom**: Fukuii-specific extensions for enhanced functionality

## Current Implementation (77 Endpoints)

### 1. ETH Namespace (40 endpoints)

#### Blocks (9 endpoints)
- ✅ `eth_blockNumber` - Returns the number of most recent block
- ✅ `eth_getBlockByHash` - Returns block by hash
- ✅ `eth_getBlockByNumber` - Returns block by number
- ✅ `eth_getBlockTransactionCountByHash` - Returns transaction count in block by hash
- ✅ `eth_getBlockTransactionCountByNumber` - Returns transaction count in block by number
- ✅ `eth_getUncleByBlockHashAndIndex` - Returns uncle by block hash and index
- ✅ `eth_getUncleByBlockNumberAndIndex` - Returns uncle by block number and index
- ✅ `eth_getUncleCountByBlockHash` - Returns uncle count by block hash
- ✅ `eth_getUncleCountByBlockNumber` - Returns uncle count by block number

#### Transactions (13 endpoints)
- ✅ `eth_sendTransaction` - Send a transaction
- ✅ `eth_sendRawTransaction` - Send a raw signed transaction
- ✅ `eth_getTransactionByHash` - Get transaction by hash
- ✅ `eth_getTransactionByBlockHashAndIndex` - Get transaction by block hash and index
- ✅ `eth_getTransactionByBlockNumberAndIndex` - Get transaction by block number and index
- ✅ `eth_getTransactionReceipt` - Get transaction receipt
- ✅ `eth_getTransactionCount` - Get nonce for address
- ✅ `eth_getRawTransactionByHash` - Get raw transaction by hash (ETC extension)
- ✅ `eth_getRawTransactionByBlockHashAndIndex` - Get raw transaction by block hash and index (ETC extension)
- ✅ `eth_getRawTransactionByBlockNumberAndIndex` - Get raw transaction by block number and index (ETC extension)
- ✅ `eth_pendingTransactions` - Get pending transactions
- ✅ `eth_sign` - Sign data with address
- ⚠️ `eth_signTransaction` - **MISSING** - Sign transaction without sending

#### Accounts & State (9 endpoints)
- ✅ `eth_accounts` - List accounts
- ✅ `eth_getBalance` - Get balance of address
- ✅ `eth_getCode` - Get code at address
- ✅ `eth_getStorageAt` - Get storage at position
- ✅ `eth_getStorageRoot` - Get storage root (ETC extension)
- ✅ `eth_call` - Execute call without transaction
- ✅ `eth_estimateGas` - Estimate gas for transaction
- ✅ `eth_getProof` - Get Merkle proof for account (EIP-1186)
- ⚠️ `eth_createAccessList` - **MISSING** - Create access list for transaction (EIP-2930)

#### Filters & Logs (6 endpoints)
- ✅ `eth_newFilter` - Create new log filter
- ✅ `eth_newBlockFilter` - Create new block filter
- ✅ `eth_newPendingTransactionFilter` - Create new pending transaction filter
- ✅ `eth_uninstallFilter` - Uninstall filter
- ✅ `eth_getFilterChanges` - Get filter changes
- ✅ `eth_getFilterLogs` - Get filter logs
- ✅ `eth_getLogs` - Get logs matching filter

#### Mining (6 endpoints)
- ✅ `eth_mining` - Check if mining
- ✅ `eth_hashrate` - Get current hashrate
- ✅ `eth_getWork` - Get work for mining
- ✅ `eth_submitWork` - Submit proof-of-work
- ✅ `eth_submitHashrate` - Submit hashrate
- ✅ `eth_coinbase` - Get coinbase address

#### Network Info (4 endpoints)
- ✅ `eth_protocolVersion` - Get protocol version
- ✅ `eth_chainId` - Get chain ID
- ✅ `eth_syncing` - Get sync status
- ✅ `eth_gasPrice` - Get current gas price
- ⚠️ `eth_maxPriorityFeePerGas` - **MISSING** - Get max priority fee (EIP-1559)
- ⚠️ `eth_feeHistory` - **MISSING** - Get fee history (EIP-1559)

### 2. WEB3 Namespace (2 endpoints)
- ✅ `web3_clientVersion` - Get client version
- ✅ `web3_sha3` - Keccak-256 hash

### 3. NET Namespace (3 endpoints)
- ✅ `net_version` - Get network ID
- ✅ `net_listening` - Check if listening for connections
- ✅ `net_peerCount` - Get peer count

### 4. PERSONAL Namespace (8 endpoints)
- ✅ `personal_newAccount` - Create new account
- ✅ `personal_importRawKey` - Import raw private key
- ✅ `personal_listAccounts` - List all accounts
- ✅ `personal_unlockAccount` - Unlock account
- ✅ `personal_lockAccount` - Lock account
- ✅ `personal_sendTransaction` - Send transaction with passphrase
- ✅ `personal_sign` - Sign data with passphrase
- ✅ `personal_ecRecover` - Recover address from signature

**Note**: Personal namespace is deprecated in standard Ethereum but remains useful for development and private networks.

### 5. DEBUG Namespace (3 endpoints)
- ✅ `debug_listPeersInfo` - List connected peers information
- ✅ `debug_accountRange` - Get account range
- ✅ `debug_storageRangeAt` - Get storage range

**Additional Debug Methods** (from Geth/other clients - not currently implemented):
- ⚠️ `debug_traceTransaction` - **MISSING** - Trace transaction execution
- ⚠️ `debug_traceBlockByNumber` - **MISSING** - Trace block execution
- ⚠️ `debug_traceBlockByHash` - **MISSING** - Trace block execution by hash
- ⚠️ `debug_traceCall` - **MISSING** - Trace call execution

### 6. QA Namespace (3 endpoints - Testing)
- ✅ `qa_mineBlocks` - Mine blocks (QA)
- ✅ `qa_generateCheckpoint` - Generate checkpoint
- ✅ `qa_getFederationMembersInfo` - Get federation members info

### 7. FUKUII Namespace (1 endpoint - Custom)
- ✅ `fukuii_getAccountTransactions` - Get account transactions in block range

### 9. TEST Namespace (7 endpoints - Testing)
- ✅ `test_setChainParams` - Set chain parameters
- ✅ `test_mineBlocks` - Mine blocks (test)
- ✅ `test_modifyTimestamp` - Modify timestamp
- ✅ `test_rewindToBlock` - Rewind to block
- ✅ `test_importRawBlock` - Import raw block
- ✅ `test_getLogHash` - Get log hash
- ✅ `miner_setEtherbase` - Set etherbase address

### 10. IELE Namespace (2 endpoints - IELE VM)
- ✅ `iele_call` - Execute IELE call
- ✅ `iele_sendTransaction` - Send IELE transaction

**Note**: IELE is a register-based VM extension specific to some blockchain implementations.

### 11. RPC Namespace (1 endpoint)
- ✅ `rpc_modules` - List enabled RPC modules

## Missing Standard Methods

Based on the latest Ethereum JSON-RPC specification, the following standard methods are **missing**:

### High Priority (Commonly Used)

1. **`eth_signTransaction`** - Sign transaction without sending
   - **Status**: ⚠️ Missing
   - **Priority**: Medium
   - **Use Case**: Offline transaction signing
   - **Note**: Omitted for security reasons; use `personal_sendTransaction` with passphrase or sign offline

2. **`eth_createAccessList`** (EIP-2930)
   - **Status**: ⚠️ Missing
   - **Priority**: Medium
   - **Use Case**: Create access list for Berlin/London transactions

3. **`eth_maxPriorityFeePerGas`** (EIP-1559)
   - **Status**: ⚠️ Missing
   - **Priority**: Medium
   - **Use Case**: Get suggested priority fee for EIP-1559 transactions

4. **`eth_feeHistory`** (EIP-1559)
   - **Status**: ⚠️ Missing
   - **Priority**: Medium
   - **Use Case**: Historical fee data for EIP-1559 fee estimation

### Medium Priority (Debug/Development)

5. **`debug_traceTransaction`**
   - **Status**: ⚠️ Missing
   - **Priority**: Low-Medium
   - **Use Case**: Transaction execution tracing

6. **`debug_traceBlockByNumber`**
   - **Status**: ⚠️ Missing
   - **Priority**: Low
   - **Use Case**: Block execution tracing

7. **`debug_traceBlockByHash`**
   - **Status**: ⚠️ Missing
   - **Priority**: Low
   - **Use Case**: Block execution tracing by hash

8. **`debug_traceCall`**
   - **Status**: ⚠️ Missing
   - **Priority**: Low
   - **Use Case**: Call execution tracing

### Low Priority (Less Common)

9. **`eth_getBlockReceipts`**
   - **Status**: ⚠️ Missing
   - **Priority**: Low
   - **Use Case**: Get all receipts for a block in one call

10. **`eth_submitHashRate`** (duplicate of eth_submitHashrate)
    - **Status**: ✅ Implemented as `eth_submitHashrate`
    - **Priority**: N/A

## EIP Support Status

### Supported EIPs
- ✅ **EIP-1186**: `eth_getProof` - Account proof
- ✅ **EIP-155**: Chain ID in transactions
- ✅ **EIP-2718**: Typed transaction envelope (via RLP encoding)

### Partially Supported EIPs
- ⚠️ **EIP-1559**: Missing `eth_maxPriorityFeePerGas` and `eth_feeHistory`
- ⚠️ **EIP-2930**: Missing `eth_createAccessList`

### Ethereum Classic Specific
- ✅ **ECIP-1109**: Spiral hard fork
- ✅ **ECIP-1104**: Mystique hard fork
- ✅ **ECIP-1103**: Magneto hard fork
- ✅ Custom extensions: `eth_getRawTransaction*`, `eth_getStorageRoot`

## Implementation Quality

### Strengths
1. **Complete Core Coverage**: All essential Ethereum JSON-RPC methods implemented
2. **ETC Extensions**: Additional methods for Ethereum Classic specific features
3. **Testing Support**: Comprehensive test/QA endpoints for development
4. **Well-Structured**: Clean separation of concerns across service files

### Areas for Improvement
1. **EIP-1559 Support**: Add fee market methods
2. **EIP-2930 Support**: Add access list creation
3. **Debug Tracing**: Add transaction/block tracing capabilities
4. **Documentation**: Create comprehensive API documentation

## Recommendations for MCP Server

For Model Context Protocol (MCP) server integration, we recommend:

### 1. Core Endpoints to Expose
All standard `eth_*`, `web3_*`, and `net_*` methods should be available through MCP.

### 2. Optional/Admin Endpoints
The following should be gated or configurable:
- `personal_*` methods (security sensitive)
- `debug_*` methods (performance impact)
- `test_*` methods (development only)
- `qa_*` methods (testing only)

### 3. Documentation Requirements
For MCP integration, we need:
- OpenAPI/Swagger specification
- JSON-RPC schema definitions
- Example requests/responses
- Error code documentation
- Rate limiting guidance

### 4. Missing Methods to Implement
**Priority for MCP**:
1. `eth_signTransaction` - Offline signing support
2. `eth_maxPriorityFeePerGas` - Fee estimation
3. `eth_feeHistory` - Historical fee data
4. `eth_createAccessList` - Access list support

## Next Steps

1. ✅ **Gap Analysis Complete**: This document
2. 📋 **Update Insomnia Workspace**: Add missing methods (placeholders)
3. 📋 **Create API Documentation**: Comprehensive endpoint reference
4. 📋 **MCP Server Design**: Define MCP server architecture
5. 📋 **Implementation Plan**: Prioritize and implement missing methods

## References

- [Ethereum JSON-RPC Specification](https://ethereum.org/en/developers/docs/apis/json-rpc/)
- [EIP-1186: eth_getProof](https://eips.ethereum.org/EIPS/eip-1186)
- [EIP-1559: Fee Market](https://eips.ethereum.org/EIPS/eip-1559)
- [EIP-2930: Access Lists](https://eips.ethereum.org/EIPS/eip-2930)
- [Model Context Protocol Specification](https://modelcontextprotocol.io/specification/2025-03-26)

---

**Maintained by**: Chippr Robotics LLC  
**Last Updated**: 2025-11-24
