# Fukuii Insomnia Workspace Guide

This guide explains how to use the Fukuii Insomnia workspace to test and interact with the Fukuii JSON-RPC API.

## Overview

The `insomnia_workspace.json` file contains a complete collection of all 77 JSON-RPC endpoints implemented in Fukuii, organized into 11 namespaces for easy navigation and testing.

## Installation

### Prerequisites
- [Insomnia](https://insomnia.rest/) API client (version 2023.5.0 or later recommended)
- Running Fukuii node (see [Quick Start Guide](../../.github/QUICKSTART.md))

### Import Steps

1. **Download the workspace file**
   - Located at: `insomnia_workspace.json` in the repository root

2. **Import into Insomnia**
   - Open Insomnia
   - Click on "Application" → "Preferences" → "Data"
   - Click "Import Data" → "From File"
   - Select `insomnia_workspace.json`
   - Click "Scan" and then "Import"

3. **Configure environment**
   - Select the "Development" environment from the environment dropdown
   - Update the variables as needed:
     - `node_url`: Your Fukuii node RPC endpoint (default: `http://127.0.0.1:8546`)
     - `address`: A test Ethereum address
     - `recipient`: A recipient address for test transactions
     - `contract`: A contract address for testing
     - `tx_hash`: A transaction hash for queries
     - `filter_id`: A filter ID for filter operations

## Workspace Structure

The workspace is organized into the following folders:

### 1. ETH Namespace (46 endpoints)
Standard Ethereum JSON-RPC methods organized into subfolders:

#### Blocks (9 endpoints)
- `eth_blockNumber`
- `eth_getBlockByHash`
- `eth_getBlockByNumber`
- `eth_getBlockTransactionCountByHash`
- `eth_getBlockTransactionCountByNumber`
- `eth_getUncleByBlockHashAndIndex`
- `eth_getUncleByBlockNumberAndIndex`
- `eth_getUncleCountByBlockHash`
- `eth_getUncleCountByBlockNumber`

#### Transactions (12 endpoints)
- `eth_sendTransaction`
- `eth_sendRawTransaction`
- `eth_getTransactionByHash`
- `eth_getTransactionByBlockHashAndIndex`
- `eth_getTransactionByBlockNumberAndIndex`
- `eth_getTransactionReceipt`
- `eth_getTransactionCount`
- `eth_getRawTransactionByHash` (ETC extension)
- `eth_getRawTransactionByBlockHashAndIndex` (ETC extension)
- `eth_getRawTransactionByBlockNumberAndIndex` (ETC extension)
- `eth_pendingTransactions`
- `eth_sign`

#### Accounts & State (8 endpoints)
- `eth_accounts`
- `eth_getBalance`
- `eth_getCode`
- `eth_getStorageAt`
- `eth_getStorageRoot` (ETC extension)
- `eth_call`
- `eth_estimateGas`
- `eth_getProof` (EIP-1186)

#### Filters & Logs (7 endpoints)
- `eth_newFilter`
- `eth_newBlockFilter`
- `eth_newPendingTransactionFilter`
- `eth_uninstallFilter`
- `eth_getFilterChanges`
- `eth_getFilterLogs`
- `eth_getLogs`

#### Mining (6 endpoints)
- `eth_mining`
- `eth_hashrate`
- `eth_getWork`
- `eth_submitWork`
- `eth_submitHashrate`
- `eth_coinbase`

#### Network Info (4 endpoints)
- `eth_protocolVersion`
- `eth_chainId`
- `eth_syncing`
- `eth_gasPrice`

### 2. WEB3 Namespace (2 endpoints)
- `web3_clientVersion`
- `web3_sha3`

### 3. NET Namespace (3 endpoints)
- `net_version`
- `net_listening`
- `net_peerCount`

### 4. PERSONAL Namespace (8 endpoints)
Account management (Geth-compatible):
- `personal_newAccount`
- `personal_importRawKey`
- `personal_listAccounts`
- `personal_unlockAccount`
- `personal_lockAccount`
- `personal_sendTransaction`
- `personal_sign`
- `personal_ecRecover`

### 5. DEBUG Namespace (3 endpoints)
Debug and diagnostics:
- `debug_listPeersInfo`
- `debug_accountRange`
- `debug_storageRangeAt`

### 6. QA Namespace (3 endpoints)
Quality assurance and testing:
- `qa_mineBlocks`
- `qa_generateCheckpoint`
- `qa_getFederationMembersInfo`

### 7. CHECKPOINTING Namespace (2 endpoints)
ETC-specific checkpointing:
- `checkpointing_getLatestBlock`
- `checkpointing_pushCheckpoint`

### 8. FUKUII Namespace (1 endpoint)
Fukuii-specific methods:
- `fukuii_getAccountTransactions`

### 9. TEST Namespace (7 endpoints)
Test harness methods:
- `test_setChainParams`
- `test_mineBlocks`
- `test_modifyTimestamp`
- `test_rewindToBlock`
- `test_importRawBlock`
- `test_getLogHash`
- `miner_setEtherbase`

### 10. IELE Namespace (2 endpoints)
IELE VM support (experimental):
- `iele_call`
- `iele_sendTransaction`

### 11. RPC Namespace (1 endpoint)
Meta information:
- `rpc_modules`

## Usage Tips

### Environment Variables

The workspace uses Insomnia's environment variables system for flexibility. Use the `{{ variable }}` syntax in requests:

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "eth_getBalance",
  "params": ["{{ address }}", "latest"]
}
```

### Common Workflows

#### 1. Check Node Status
```
1. net_version - Get network ID
2. eth_syncing - Check sync status
3. eth_blockNumber - Get latest block
4. net_peerCount - Check peer connections
```

#### 2. Query Account
```
1. eth_getBalance - Check balance
2. eth_getTransactionCount - Get nonce
3. eth_getCode - Check if contract
4. eth_getStorageAt - Read storage
```

#### 3. Send Transaction
```
1. personal_unlockAccount - Unlock account
2. eth_getTransactionCount - Get nonce
3. eth_gasPrice - Get current gas price
4. eth_sendTransaction - Send transaction
5. eth_getTransactionReceipt - Check receipt
```

#### 4. Query Blocks
```
1. eth_blockNumber - Get latest block number
2. eth_getBlockByNumber - Get block details
3. eth_getBlockTransactionCountByNumber - Count transactions
```

#### 5. Filter Events
```
1. eth_newFilter - Create filter
2. eth_getFilterChanges - Poll for changes
3. eth_getFilterLogs - Get all logs
4. eth_uninstallFilter - Clean up
```

### Testing Extensions

#### ETC Extensions
- Use `eth_getRawTransaction*` methods to get raw transaction data
- Use `checkpointing_*` methods for checkpoint operations
- Use `eth_getStorageRoot` for storage root queries

#### Development Tools
- Use `qa_mineBlocks` to mine blocks in development
- Use `test_*` methods for advanced testing scenarios
- Use `debug_*` methods for diagnostics

## Security Notes

⚠️ **Important Security Considerations:**

1. **Never use real private keys or passphrases in Insomnia**
   - Use test accounts only
   - Store sensitive data in environment variables, not in requests

2. **Disable sensitive APIs in production**
   - `personal_*` methods should be disabled in production
   - `test_*` and `qa_*` methods are for development only

3. **Use secure connections**
   - Use HTTPS/WSS in production
   - Never expose RPC endpoints to the public internet without proper authentication

4. **Environment isolation**
   - Create separate environments for testnet and mainnet
   - Use different credentials for each environment

## Troubleshooting

### Connection Errors
```
Error: Failed to connect to http://127.0.0.1:8546
```
**Solution:** Ensure Fukuii is running with RPC enabled:
```bash
fukuii --rpc-enabled --rpc-port 8546 --rpc-address 127.0.0.1
```

### Method Not Found
```
Error: {"code": -32601, "message": "Method not found"}
```
**Solution:** Check that the API is enabled in your Fukuii configuration:
```
fukuii --rpc-enabled --rpc-apis "eth,web3,net,personal"
```

### Invalid Parameters
```
Error: {"code": -32602, "message": "Invalid params"}
```
**Solution:** 
- Check parameter types (hex strings should start with "0x")
- Verify required parameters are present
- Check the request format matches the specification

### Account Locked
```
Error: authentication needed: password or unlock
```
**Solution:** Use `personal_unlockAccount` before sending transactions, or use `personal_sendTransaction` which includes the passphrase.

## Additional Resources

- [Fukuii RPC API Analysis](./INSOMNIA_RPC_ANALYSIS.md) - Detailed comparison with Ethereum execution-apis
- [Ethereum JSON-RPC Specification](https://ethereum.github.io/execution-apis/api-documentation/)
- [Fukuii Documentation](../README.md)
- [Quick Start Guide](../../.github/QUICKSTART.md)

## Version History

| Version | Date | Description |
|---------|------|-------------|
| 2.0 | 2025-11-21 | Complete rewrite with all 77 endpoints, organized namespaces |
| 1.0 | 2021-02-01 | Initial workspace with basic endpoints |

---

**Maintained by:** Chippr Robotics LLC  
**Project:** Fukuii Ethereum Classic Client  
**Repository:** https://github.com/chippr-robotics/fukuii
