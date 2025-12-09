# Fukuii JSON-RPC API Reference

This document provides a comprehensive reference for all JSON-RPC endpoints supported by Fukuii.

**Version**: 1.1.0  
**Last Updated**: 2025-12-09  
**MCP Ready**: This documentation is structured for Model Context Protocol (MCP) server integration

## Table of Contents

- [Overview](#overview)
- [Endpoint Categories](#endpoint-categories)
- [ETH Namespace](#eth-namespace)
- [WEB3 Namespace](#web3-namespace)
- [NET Namespace](#net-namespace)
- [PERSONAL Namespace](#personal-namespace)
- [DEBUG Namespace](#debug-namespace)
- [Custom Namespaces](#custom-namespaces)
  - [FUKUII Namespace](#fukuii-namespace)
  - [CHECKPOINTING Namespace](#checkpointing-namespace-etc-specific)
  - [QA Namespace](#qa-namespace-testing)
  - [TEST Namespace](#test-namespace-testing)
  - [IELE Namespace](#iele-namespace)
  - [RPC Namespace](#rpc-namespace)
- [Error Codes](#error-codes)
- [Best Practices](#best-practices)

## Overview

Fukuii implements a complete JSON-RPC API compatible with the Ethereum JSON-RPC specification, with additional extensions for Ethereum Classic and development/testing purposes.

### Connection Endpoints

- **HTTP RPC**: `http://localhost:8546`
- **WebSocket**: `ws://localhost:8546/ws` (if enabled)

### Request Format

All requests follow the JSON-RPC 2.0 specification:

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "method_name",
  "params": [...]
}
```

### Response Format

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "result": ...
}
```

Or on error:

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "error": {
    "code": -32000,
    "message": "error message"
  }
}
```

## Endpoint Categories

### Production-Ready (Core)
Standard Ethereum methods suitable for production use:
- ETH namespace (blocks, transactions, accounts, state)
- WEB3 namespace (utilities)
- NET namespace (network info)

### Development/Testing Only
Methods that should be disabled in production:
- PERSONAL namespace (account management)
- TEST namespace (chain manipulation)
- QA namespace (testing utilities)
- DEBUG namespace (performance impact)

### ETC-Specific
Ethereum Classic extensions:
- CHECKPOINTING namespace
- Custom ETH methods (getRawTransaction*, getStorageRoot)

### Custom Extensions
Fukuii-specific enhancements:
- FUKUII namespace
- IELE namespace (if IELE VM enabled)

## ETH Namespace

### Blocks

#### eth_blockNumber

Returns the number of the most recent block.

**Parameters**: None

**Returns**: `QUANTITY` - Integer of the current block number the client is on

**Example**:
```bash
curl -X POST http://localhost:8546 \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "eth_blockNumber",
    "params": []
  }'
```

**Response**:
```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "result": "0xbc614e"
}
```

**MCP Context**: Essential for determining current chain state and sync status.

---

#### eth_getBlockByHash

Returns information about a block by hash.

**Parameters**:
1. `DATA`, 32 Bytes - Hash of a block
2. `Boolean` - If `true` it returns the full transaction objects, if `false` only the hashes of the transactions

**Returns**: `Object` - A block object, or `null` when no block was found

**Example**:
```bash
curl -X POST http://localhost:8546 \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "eth_getBlockByHash",
    "params": [
      "0xb495a1d7e6663152ae92708da4843337b958146015a2802f4193a410044698c9",
      true
    ]
  }'
```

**MCP Context**: Core method for retrieving block data for analysis and verification.

---

#### eth_getBlockByNumber

Returns information about a block by number.

**Parameters**:
1. `QUANTITY|TAG` - Integer block number, or the string "earliest", "latest" or "pending"
2. `Boolean` - If `true` it returns the full transaction objects, if `false` only the hashes of the transactions

**Returns**: `Object` - A block object, or `null` when no block was found

**Example**:
```bash
curl -X POST http://localhost:8546 \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "eth_getBlockByNumber",
    "params": ["latest", true]
  }'
```

**Block Tags**:
- `"earliest"` - Genesis block
- `"latest"` - Latest mined block
- `"pending"` - Pending state/transactions
- `"0x..."` - Specific block number in hex

**MCP Context**: Primary method for retrieving block data, supports special tags for convenience.

---

#### eth_getBlockTransactionCountByHash

Returns the number of transactions in a block from a block matching the given block hash.

**Parameters**:
1. `DATA`, 32 Bytes - Hash of a block

**Returns**: `QUANTITY` - Integer of the number of transactions in this block

**Example**:
```bash
curl -X POST http://localhost:8546 \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "eth_getBlockTransactionCountByHash",
    "params": ["0xb495a1d7e6663152ae92708da4843337b958146015a2802f4193a410044698c9"]
  }'
```

**MCP Context**: Useful for pagination and determining block activity.

---

#### eth_getBlockTransactionCountByNumber

Returns the number of transactions in a block matching the given block number.

**Parameters**:
1. `QUANTITY|TAG` - Integer of a block number, or the string "earliest", "latest" or "pending"

**Returns**: `QUANTITY` - Integer of the number of transactions in this block

**MCP Context**: Quick check for block activity without fetching full block data.

---

#### eth_getUncleByBlockHashAndIndex

Returns information about an uncle of a block by hash and uncle index position.

**Parameters**:
1. `DATA`, 32 Bytes - Hash of a block
2. `QUANTITY` - The uncle's index position

**Returns**: `Object` - An uncle block object, or `null`

**Note**: Ethereum Classic uses "ommers" but maintains "uncle" in API for compatibility.

**MCP Context**: Required for complete blockchain analysis including uncle blocks.

---

#### eth_getUncleByBlockNumberAndIndex

Returns information about an uncle of a block by number and uncle index position.

**Parameters**:
1. `QUANTITY|TAG` - Block number or tag
2. `QUANTITY` - The uncle's index position

**Returns**: `Object` - An uncle block object, or `null`

---

#### eth_getUncleCountByBlockHash

Returns the number of uncles in a block from a block matching the given block hash.

**Parameters**:
1. `DATA`, 32 Bytes - Hash of a block

**Returns**: `QUANTITY` - Integer of the number of uncles in this block

---

#### eth_getUncleCountByBlockNumber

Returns the number of uncles in a block from a block matching the given block number.

**Parameters**:
1. `QUANTITY|TAG` - Block number or tag

**Returns**: `QUANTITY` - Integer of the number of uncles in this block

---

### Transactions

#### eth_sendTransaction

Creates new message call transaction or a contract creation, if the data field contains code.

**Parameters**:
1. `Object` - The transaction object
   - `from`: `DATA`, 20 Bytes - The address the transaction is sent from
   - `to`: `DATA`, 20 Bytes - (optional) The address the transaction is directed to
   - `gas`: `QUANTITY` - (optional) Integer of the gas provided for the transaction execution
   - `gasPrice`: `QUANTITY` - (optional) Integer of the gasPrice used for each paid gas
   - `value`: `QUANTITY` - (optional) Integer of the value sent with this transaction
   - `data`: `DATA` - (optional) The compiled code of a contract OR the hash of the invoked method signature and encoded parameters
   - `nonce`: `QUANTITY` - (optional) Integer of a nonce

**Returns**: `DATA`, 32 Bytes - The transaction hash, or the zero hash if the transaction is not yet available

**Example**:
```bash
curl -X POST http://localhost:8546 \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "eth_sendTransaction",
    "params": [{
      "from": "0x...",
      "to": "0x...",
      "value": "0x0",
      "gasLimit": "0x5208",
      "gasPrice": "0x0"
    }]
  }'
```

**Security Note**: Requires unlocked account. Use `personal_sendTransaction` for production.

**MCP Context**: Primary method for submitting transactions. Requires account management.

---

#### eth_sendRawTransaction

Creates new message call transaction or a contract creation for signed transactions.

**Parameters**:
1. `DATA` - The signed transaction data

**Returns**: `DATA`, 32 Bytes - The transaction hash, or the zero hash if the transaction is not yet available

**Example**:
```bash
curl -X POST http://localhost:8546 \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "eth_sendRawTransaction",
    "params": ["0x..."]
  }'
```

**MCP Context**: Preferred method for transaction submission with offline signing.

---

#### eth_getTransactionByHash

Returns the information about a transaction requested by transaction hash.

**Parameters**:
1. `DATA`, 32 Bytes - Hash of a transaction

**Returns**: `Object` - A transaction object, or `null` when no transaction was found

**Example**:
```bash
curl -X POST http://localhost:8546 \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "eth_getTransactionByHash",
    "params": ["0x..."]
  }'
```

**MCP Context**: Essential for transaction tracking and verification.

---

#### eth_getTransactionByBlockHashAndIndex

Returns information about a transaction by block hash and transaction index position.

**Parameters**:
1. `DATA`, 32 Bytes - Hash of a block
2. `QUANTITY` - Integer of the transaction index position

**Returns**: `Object` - A transaction object, or `null`

**MCP Context**: Useful for iterating through transactions in a block.

---

#### eth_getTransactionByBlockNumberAndIndex

Returns information about a transaction by block number and transaction index position.

**Parameters**:
1. `QUANTITY|TAG` - Block number or tag
2. `QUANTITY` - The transaction index position

**Returns**: `Object` - A transaction object, or `null`

---

#### eth_getTransactionReceipt

Returns the receipt of a transaction by transaction hash.

**Parameters**:
1. `DATA`, 32 Bytes - Hash of a transaction

**Returns**: `Object` - A transaction receipt object, or `null` when no receipt was found

**Receipt Object Fields**:
- `transactionHash`: `DATA`, 32 Bytes - Hash of the transaction
- `transactionIndex`: `QUANTITY` - Integer of the transaction's index position in the block
- `blockHash`: `DATA`, 32 Bytes - Hash of the block where this transaction was in
- `blockNumber`: `QUANTITY` - Block number where this transaction was in
- `from`: `DATA`, 20 Bytes - Address of the sender
- `to`: `DATA`, 20 Bytes - Address of the receiver (null for contract creation)
- `cumulativeGasUsed`: `QUANTITY` - Total gas used when this transaction was executed
- `gasUsed`: `QUANTITY` - Gas used by this specific transaction
- `contractAddress`: `DATA`, 20 Bytes - Contract address created (null if not a contract creation)
- `logs`: `Array` - Array of log objects
- `logsBloom`: `DATA`, 256 Bytes - Bloom filter for logs
- `status`: `QUANTITY` - Either 1 (success) or 0 (failure)

**Example**:
```bash
curl -X POST http://localhost:8546 \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "eth_getTransactionReceipt",
    "params": ["0x..."]
  }'
```

**MCP Context**: Critical for determining transaction success and extracting events/logs.

---

#### eth_getTransactionCount

Returns the number of transactions sent from an address.

**Parameters**:
1. `DATA`, 20 Bytes - Address
2. `QUANTITY|TAG` - Integer block number, or the string "latest", "earliest" or "pending"

**Returns**: `QUANTITY` - Integer of the number of transactions sent from this address

**Example**:
```bash
curl -X POST http://localhost:8546 \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "eth_getTransactionCount",
    "params": ["0x...", "latest"]
  }'
```

**MCP Context**: Essential for determining nonce when creating transactions.

---

#### eth_pendingTransactions

Returns a list of pending transactions.

**Parameters**: None

**Returns**: `Array` - Array of pending transaction objects

**Example**:
```bash
curl -X POST http://localhost:8546 \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "eth_pendingTransactions",
    "params": []
  }'
```

**MCP Context**: Useful for mempool monitoring and transaction prediction.

---

#### eth_sign

Signs data with a given address.

**Parameters**:
1. `DATA`, 20 Bytes - Address
2. `DATA` - Data to sign

**Returns**: `DATA` - Signature

**Security Note**: Requires unlocked account. Deprecated in favor of personal_sign.

**Example**:
```bash
curl -X POST http://localhost:8546 \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "eth_sign",
    "params": ["0x...", "0xdeadbeaf"]
  }'
```

---

### Accounts & State

#### eth_accounts

Returns a list of addresses owned by client.

**Parameters**: None

**Returns**: `Array` - Array of 20 Bytes addresses owned by the client

**Example**:
```bash
curl -X POST http://localhost:8546 \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "eth_accounts",
    "params": []
  }'
```

**MCP Context**: Account discovery for transaction operations.

---

#### eth_getBalance

Returns the balance of the account of given address.

**Parameters**:
1. `DATA`, 20 Bytes - Address to check for balance
2. `QUANTITY|TAG` - Integer block number, or the string "latest", "earliest" or "pending"

**Returns**: `QUANTITY` - Integer of the current balance in wei

**Example**:
```bash
curl -X POST http://localhost:8546 \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "eth_getBalance",
    "params": ["0x...", "latest"]
  }'
```

**MCP Context**: Essential for checking account balances and preparing transactions.

---

#### eth_getCode

Returns code at a given address.

**Parameters**:
1. `DATA`, 20 Bytes - Address
2. `QUANTITY|TAG` - Integer block number, or the string "latest", "earliest" or "pending"

**Returns**: `DATA` - The code from the given address

**Example**:
```bash
curl -X POST http://localhost:8546 \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "eth_getCode",
    "params": ["0x...", "latest"]
  }'
```

**MCP Context**: Used to verify contract deployment and retrieve bytecode.

---

#### eth_getStorageAt

Returns the value from a storage position at a given address.

**Parameters**:
1. `DATA`, 20 Bytes - Address of the storage
2. `QUANTITY` - Integer of the position in the storage
3. `QUANTITY|TAG` - Integer block number, or the string "latest", "earliest" or "pending"

**Returns**: `DATA` - The value at this storage position

**Example**:
```bash
curl -X POST http://localhost:8546 \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "eth_getStorageAt",
    "params": ["0x...", "0x0", "latest"]
  }'
```

**MCP Context**: Direct storage access for contract state inspection.

---

#### eth_call

Executes a new message call immediately without creating a transaction on the blockchain.

**Parameters**:
1. `Object` - The transaction call object
   - `from`: `DATA`, 20 Bytes - (optional) The address the transaction is sent from
   - `to`: `DATA`, 20 Bytes - The address the transaction is directed to
   - `gas`: `QUANTITY` - (optional) Integer of the gas provided for the transaction execution
   - `gasPrice`: `QUANTITY` - (optional) Integer of the gasPrice used for each paid gas
   - `value`: `QUANTITY` - (optional) Integer of the value sent with this transaction
   - `data`: `DATA` - (optional) Hash of the method signature and encoded parameters
2. `QUANTITY|TAG` - Integer block number, or the string "latest", "earliest" or "pending"

**Returns**: `DATA` - The return value of executed contract

**Example**:
```bash
curl -X POST http://localhost:8546 \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "eth_call",
    "params": [{
      "from": "0x...",
      "to": "0x...",
      "data": "0x..."
    }, "latest"]
  }'
```

**MCP Context**: Primary method for read-only contract interactions. No gas cost.

---

#### eth_estimateGas

Generates and returns an estimate of how much gas is necessary to allow the transaction to complete.

**Parameters**:
1. `Object` - The transaction call object (same as eth_call)
2. `QUANTITY|TAG` - (optional) Integer block number, or the string "latest", "earliest" or "pending"

**Returns**: `QUANTITY` - The amount of gas used

**Example**:
```bash
curl -X POST http://localhost:8546 \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "eth_estimateGas",
    "params": [{
      "from": "0x...",
      "to": "0x...",
      "value": "0x0"
    }]
  }'
```

**MCP Context**: Essential for determining gas limits before sending transactions.

---

#### eth_getProof (EIP-1186)

Returns the Merkle proof for a given account and optionally some storage keys.

**Parameters**:
1. `DATA`, 20 Bytes - Address of the account
2. `Array` - Array of storage keys
3. `QUANTITY|TAG` - Integer block number, or the string "latest"

**Returns**: `Object` - Account proof object containing:
- `accountProof`: Array of RLP-serialized MPT nodes
- `balance`: Account balance
- `codeHash`: Hash of the code
- `nonce`: Account nonce
- `storageHash`: Storage root
- `storageProof`: Array of storage proofs

**Example**:
```bash
curl -X POST http://localhost:8546 \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "eth_getProof",
    "params": ["0x...", ["0x0"], "latest"]
  }'
```

**MCP Context**: Used for light client verification and trustless state queries.

---

### ETC Extension: eth_getStorageRoot

Returns the storage root of an account.

**Parameters**:
1. `DATA`, 20 Bytes - Address
2. `QUANTITY|TAG` - Integer block number, or the string "latest", "earliest" or "pending"

**Returns**: `DATA`, 32 Bytes - Storage root hash

**Example**:
```bash
curl -X POST http://localhost:8546 \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "eth_getStorageRoot",
    "params": ["0x...", "latest"]
  }'
```

**Note**: This is an Ethereum Classic extension not part of standard Ethereum.

---

### ETC Extensions: Raw Transaction Methods

#### eth_getRawTransactionByHash

Returns the raw transaction data by transaction hash.

**Parameters**:
1. `DATA`, 32 Bytes - Hash of a transaction

**Returns**: `DATA` - Raw RLP-encoded transaction data

**Note**: ETC extension not in standard Ethereum.

---

#### eth_getRawTransactionByBlockHashAndIndex

Returns raw transaction data by block hash and index.

**Parameters**:
1. `DATA`, 32 Bytes - Hash of a block
2. `QUANTITY` - Transaction index

**Returns**: `DATA` - Raw RLP-encoded transaction data

---

#### eth_getRawTransactionByBlockNumberAndIndex

Returns raw transaction data by block number and index.

**Parameters**:
1. `QUANTITY|TAG` - Block number or tag
2. `QUANTITY` - Transaction index

**Returns**: `DATA` - Raw RLP-encoded transaction data

---

### Filters & Logs

#### eth_newFilter

Creates a filter object based on filter options to notify when the state changes (logs).

**Parameters**:
1. `Object` - The filter options
   - `fromBlock`: `QUANTITY|TAG` - (optional) Block number or "latest"/"pending"/"earliest"
   - `toBlock`: `QUANTITY|TAG` - (optional) Block number or "latest"/"pending"/"earliest"
   - `address`: `DATA|Array` - (optional) Contract address or array of addresses
   - `topics`: `Array` - (optional) Array of DATA topics

**Returns**: `QUANTITY` - A filter id

**Example**:
```bash
curl -X POST http://localhost:8546 \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "eth_newFilter",
    "params": [{
      "fromBlock": "earliest",
      "toBlock": "latest",
      "address": "0x..."
    }]
  }'
```

**MCP Context**: Foundation for event monitoring and log filtering.

---

#### eth_newBlockFilter

Creates a filter in the node to notify when a new block arrives.

**Parameters**: None

**Returns**: `QUANTITY` - A filter id

**Example**:
```bash
curl -X POST http://localhost:8546 \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "eth_newBlockFilter",
    "params": []
  }'
```

**MCP Context**: Simple block arrival notifications.

---

#### eth_newPendingTransactionFilter

Creates a filter in the node to notify when new pending transactions arrive.

**Parameters**: None

**Returns**: `QUANTITY` - A filter id

**Example**:
```bash
curl -X POST http://localhost:8546 \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "eth_newPendingTransactionFilter",
    "params": []
  }'
```

**MCP Context**: Mempool monitoring for pending transactions.

---

#### eth_uninstallFilter

Uninstalls a filter with given id.

**Parameters**:
1. `QUANTITY` - The filter id

**Returns**: `Boolean` - `true` if the filter was successfully uninstalled, otherwise `false`

**Example**:
```bash
curl -X POST http://localhost:8546 \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "eth_uninstallFilter",
    "params": ["0x1"]
  }'
```

---

#### eth_getFilterChanges

Polling method for a filter, which returns an array of logs which occurred since last poll.

**Parameters**:
1. `QUANTITY` - The filter id

**Returns**: `Array` - Array of log objects, or an empty array if nothing has changed since last poll

**Example**:
```bash
curl -X POST http://localhost:8546 \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "eth_getFilterChanges",
    "params": ["0x1"]
  }'
```

**MCP Context**: Polling-based event monitoring.

---

#### eth_getFilterLogs

Returns an array of all logs matching filter with given id.

**Parameters**:
1. `QUANTITY` - The filter id

**Returns**: `Array` - Array of log objects

**Example**:
```bash
curl -X POST http://localhost:8546 \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "eth_getFilterLogs",
    "params": ["0x1"]
  }'
```

**MCP Context**: Retrieve all matching logs for a filter.

---

#### eth_getLogs

Returns an array of all logs matching a given filter object.

**Parameters**:
1. `Object` - The filter options (same as eth_newFilter)

**Returns**: `Array` - Array of log objects

**Example**:
```bash
curl -X POST http://localhost:8546 \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "eth_getLogs",
    "params": [{
      "fromBlock": "0x0",
      "toBlock": "latest",
      "address": "0x...",
      "topics": []
    }]
  }'
```

**MCP Context**: Direct log querying without filter creation. Preferred for one-off queries.

---

### Mining

#### eth_mining

Returns `true` if client is actively mining new blocks.

**Parameters**: None

**Returns**: `Boolean` - `true` if the client is mining, otherwise `false`

**Example**:
```bash
curl -X POST http://localhost:8546 \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "eth_mining",
    "params": []
  }'
```

---

#### eth_hashrate

Returns the number of hashes per second that the node is mining with.

**Parameters**: None

**Returns**: `QUANTITY` - Number of hashes per second

**Example**:
```bash
curl -X POST http://localhost:8546 \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "eth_hashrate",
    "params": []
  }'
```

---

#### eth_getWork

Returns the hash of the current block, the seedHash, and the boundary condition to be met ("target").

**Parameters**: None

**Returns**: `Array` - Array with the following properties:
1. `DATA`, 32 Bytes - Current block header pow-hash
2. `DATA`, 32 Bytes - Seed hash used for the DAG
3. `DATA`, 32 Bytes - Boundary condition ("target"), 2^256 / difficulty

**Example**:
```bash
curl -X POST http://localhost:8546 \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "eth_getWork",
    "params": []
  }'
```

**Note**: Only applicable for Ethash (PoW) mining.

---

#### eth_submitWork

Used for submitting a proof-of-work solution.

**Parameters**:
1. `DATA`, 8 Bytes - The nonce found
2. `DATA`, 32 Bytes - The header's pow-hash
3. `DATA`, 32 Bytes - The mix digest

**Returns**: `Boolean` - `true` if the provided solution is valid, otherwise `false`

**Example**:
```bash
curl -X POST http://localhost:8546 \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "eth_submitWork",
    "params": [
      "0x0000000000000001",
      "0x1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef",
      "0xD1FE5700000000000000000000000000D1FE5700000000000000000000000001"
    ]
  }'
```

---

#### eth_submitHashrate

Used for submitting mining hashrate.

**Parameters**:
1. `DATA`, 32 Bytes - A hexadecimal string representation of the hash rate
2. `DATA`, 32 Bytes - A random hexadecimal ID identifying the client

**Returns**: `Boolean` - `true` if submitting went through successfully, otherwise `false`

**Example**:
```bash
curl -X POST http://localhost:8546 \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "eth_submitHashrate",
    "params": [
      "0x500000",
      "0x59daa26581d0acd1fce254fb7e85952f4c09d0915afd33d3886cd914bc7d283c"
    ]
  }'
```

---

### Enhanced Mining Control (Fukuii Extension)

Since Ethereum mainnet no longer supports mining, Fukuii has enhanced the mining RPC API to provide better control for Ethereum Classic mining operations.

#### miner_start

Starts the mining process on the node.

**Parameters**: None

**Returns**: `Boolean` - `true` if mining started successfully, otherwise `false`

**Example**:
```bash
curl -X POST http://localhost:8546 \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "miner_start",
    "params": []
  }'
```

**Response**:
```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "result": true
}
```

**Note**: Only applicable when running Ethash (PoW) consensus. Returns an error for other consensus types.

---

#### miner_stop

Stops the mining process on the node.

**Parameters**: None

**Returns**: `Boolean` - `true` if mining stopped successfully, otherwise `false`

**Example**:
```bash
curl -X POST http://localhost:8546 \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "miner_stop",
    "params": []
  }'
```

**Response**:
```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "result": true
}
```

**Note**: Only applicable when running Ethash (PoW) consensus.

---

#### miner_getStatus

Returns comprehensive mining status information.

**Parameters**: None

**Returns**: `Object` - Mining status with the following fields:
- `isMining`: `Boolean` - `true` if the client is actively mining
- `coinbase`: `DATA`, 20 Bytes - The address receiving mining rewards
- `hashRate`: `QUANTITY` - Current aggregate hash rate from all connected miners
- `blocksMinedCount`: `QUANTITY` or `null` - Number of blocks mined (always `null` in current version, reserved for future implementation)

**Example**:
```bash
curl -X POST http://localhost:8546 \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "miner_getStatus",
    "params": []
  }'
```

**Response**:
```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "result": {
    "isMining": true,
    "coinbase": "0x742d35Cc6634C0532925a3b844Bc454e4438f44e",
    "hashRate": "0x64",
    "blocksMinedCount": null
  }
}
```

**Note**: Only applicable when running Ethash (PoW) consensus. Provides a consolidated view of mining status instead of querying multiple endpoints.

---

#### eth_coinbase

Returns the client coinbase address.

**Parameters**: None

**Returns**: `DATA`, 20 Bytes - The current coinbase address

**Example**:
```bash
curl -X POST http://localhost:8546 \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "eth_coinbase",
    "params": []
  }'
```

---

### Network Info

#### eth_protocolVersion

Returns the current ethereum protocol version.

**Parameters**: None

**Returns**: `String` - The current ethereum protocol version

**Example**:
```bash
curl -X POST http://localhost:8546 \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "eth_protocolVersion",
    "params": []
  }'
```

---

#### eth_chainId

Returns the chain ID of the current network.

**Parameters**: None

**Returns**: `QUANTITY` - Chain ID (e.g., `0x3d` for ETC mainnet, `0x3f` for Mordor testnet)

**Example**:
```bash
curl -X POST http://localhost:8546 \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "eth_chainId",
    "params": []
  }'
```

**MCP Context**: Critical for multi-chain support and transaction replay protection.

---

#### eth_syncing

Returns an object with data about the sync status or `false`.

**Parameters**: None

**Returns**: `Object|Boolean` - An object with sync status data, or `FALSE` when not syncing
- `startingBlock`: `QUANTITY` - The block at which the import started
- `currentBlock`: `QUANTITY` - The current block, same as eth_blockNumber
- `highestBlock`: `QUANTITY` - The estimated highest block

**Example**:
```bash
curl -X POST http://localhost:8546 \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "eth_syncing",
    "params": []
  }'
```

**Response (syncing)**:
```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "result": {
    "startingBlock": "0x0",
    "currentBlock": "0xbc614e",
    "highestBlock": "0xbc7150"
  }
}
```

**Response (not syncing)**:
```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "result": false
}
```

**MCP Context**: Essential for determining node readiness before operations.

---

#### eth_gasPrice

Returns the current price per gas in wei.

**Parameters**: None

**Returns**: `QUANTITY` - Integer of the current gas price in wei

**Example**:
```bash
curl -X POST http://localhost:8546 \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "eth_gasPrice",
    "params": []
  }'
```

**MCP Context**: Used for gas price estimation when creating transactions.

---

## WEB3 Namespace

### web3_clientVersion

Returns the current client version.

**Parameters**: None

**Returns**: `String` - The current client version

**Example**:
```bash
curl -X POST http://localhost:8546 \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "web3_clientVersion",
    "params": []
  }'
```

**Response**:
```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "result": "Fukuii/v1.1.0/linux-amd64/scala3.3.4"
}
```

---

### web3_sha3

Returns Keccak-256 (not the standardized SHA3-256) of the given data.

**Parameters**:
1. `DATA` - The data to convert into a SHA3 hash

**Returns**: `DATA` - The SHA3 result of the given string

**Example**:
```bash
curl -X POST http://localhost:8546 \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "web3_sha3",
    "params": ["0x68656c6c6f20776f726c64"]
  }'
```

**MCP Context**: Useful for computing hashes, though clients should compute locally.

---

## NET Namespace

### net_version

Returns the current network id.

**Parameters**: None

**Returns**: `String` - The current network id
- `"61"`: ETC Mainnet
- `"63"`: Mordor Testnet
- `"1"`: Ethereum Mainnet (if configured)

**Example**:
```bash
curl -X POST http://localhost:8546 \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "net_version",
    "params": []
  }'
```

---

### net_listening

Returns `true` if client is actively listening for network connections.

**Parameters**: None

**Returns**: `Boolean` - `true` when listening, otherwise `false`

**Example**:
```bash
curl -X POST http://localhost:8546 \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "net_listening",
    "params": []
  }'
```

---

### net_peerCount

Returns number of peers currently connected to the client.

**Parameters**: None

**Returns**: `QUANTITY` - Integer of the number of connected peers

**Example**:
```bash
curl -X POST http://localhost:8546 \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "net_peerCount",
    "params": []
  }'
```

**MCP Context**: Health indicator for node connectivity.

---

### net_listPeers

Returns detailed information about all connected peers.

**Parameters**: None

**Returns**: `Array` - Array of peer objects with the following fields:
- `id`: `String` - Unique peer identifier
- `remoteAddress`: `String` - Remote address of the peer
- `nodeId`: `String` or `null` - Ethereum node ID (public key) if available
- `incomingConnection`: `Boolean` - Whether this is an incoming connection
- `status`: `String` - Current peer status (e.g., "Handshaked", "Connecting", "Idle")

**Example**:
```bash
curl -X POST http://localhost:8546 \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "net_listPeers",
    "params": []
  }'
```

**Response**:
```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "result": [
    {
      "id": "peer1",
      "remoteAddress": "192.168.1.100:30303",
      "nodeId": "abcd1234...",
      "incomingConnection": false,
      "status": "Handshaked"
    }
  ]
}
```

**MCP Context**: Detailed peer information for network monitoring and diagnostics.

---

### net_disconnectPeer

Disconnects a specific peer by ID.

**Parameters**:
1. `String` - Peer ID to disconnect

**Returns**: `Boolean` - `true` if peer was disconnected, `false` if peer not found

**Example**:
```bash
curl -X POST http://localhost:8546 \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "net_disconnectPeer",
    "params": ["peer1"]
  }'
```

**MCP Context**: Network hygiene - remove problematic peers.

---

### net_connectToPeer

Attempts to connect to a new peer using an enode URI.

**Parameters**:
1. `String` - Enode URI (format: `enode://nodeId@host:port`)

**Returns**: `Boolean` - `true` if connection attempt was initiated

**Example**:
```bash
curl -X POST http://localhost:8546 \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "net_connectToPeer",
    "params": ["enode://abcd1234...@192.168.1.100:30303"]
  }'
```

**MCP Context**: Manual peer management for network configuration.

---

### net_listBlacklistedPeers

Returns list of currently blacklisted peers.

**Parameters**: None

**Returns**: `Array` - Array of blacklist entry objects with the following fields:
- `id`: `String` - Blacklisted address or peer ID
- `reason`: `String` - Reason for blacklisting
- `addedAt`: `Number` - Timestamp when added to blacklist (milliseconds since epoch)

**Example**:
```bash
curl -X POST http://localhost:8546 \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "net_listBlacklistedPeers",
    "params": []
  }'
```

**MCP Context**: Security monitoring - track banned peers.

---

### net_addToBlacklist

Adds a peer address to the blacklist with optional duration.

**Parameters**:
1. `String` - Peer address to blacklist
2. `Number` or `null` - Duration in seconds (optional, null for permanent)
3. `String` - Reason for blacklisting

**Returns**: `Boolean` - `true` if successfully added to blacklist

**Example (temporary blacklist for 1 hour)**:
```bash
curl -X POST http://localhost:8546 \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "net_addToBlacklist",
    "params": ["192.168.1.100", 3600, "Malicious behavior"]
  }'
```

**Example (permanent blacklist)**:
```bash
curl -X POST http://localhost:8546 \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "net_addToBlacklist",
    "params": ["192.168.1.100", null, "Permanent ban"]
  }'
```

**MCP Context**: Security control - prevent connections from malicious peers.

---

### net_removeFromBlacklist

Removes a peer address from the blacklist.

**Parameters**:
1. `String` - Peer address to remove from blacklist

**Returns**: `Boolean` - `true` if successfully removed

**Example**:
```bash
curl -X POST http://localhost:8546 \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "net_removeFromBlacklist",
    "params": ["192.168.1.100"]
  }'
```

**MCP Context**: Network management - unban previously blacklisted peers.

---

## PERSONAL Namespace

**⚠️ Security Warning**: The personal namespace should be **disabled in production**. These methods expose private key operations and should only be used in development/testing environments.

### personal_newAccount

Creates a new account.

**Parameters**:
1. `String` - Password for the new account

**Returns**: `DATA`, 20 Bytes - The address of the new account

**Example**:
```bash
curl -X POST http://localhost:8546 \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "personal_newAccount",
    "params": ["password123"]
  }'
```

---

### personal_importRawKey

Imports the given unencrypted private key (hex string) into the key store, encrypting it with the passphrase.

**Parameters**:
1. `DATA` - Private key (hex string)
2. `String` - Password to encrypt the private key

**Returns**: `DATA`, 20 Bytes - The address of the account

**Example**:
```bash
curl -X POST http://localhost:8546 \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "personal_importRawKey",
    "params": ["0x...", "password123"]
  }'
```

---

### personal_listAccounts

Returns all the Ethereum account addresses of all keys in the key store.

**Parameters**: None

**Returns**: `Array` - Array of 20 Bytes addresses

**Example**:
```bash
curl -X POST http://localhost:8546 \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "personal_listAccounts",
    "params": []
  }'
```

---

### personal_unlockAccount

Decrypts the key with the given address from the key store.

**Parameters**:
1. `DATA`, 20 Bytes - Address of the account to unlock
2. `String` - Password of the account
3. `QUANTITY` - (optional) Duration in seconds to keep the account unlocked (default: 300)

**Returns**: `Boolean` - `true` if the account was unlocked, otherwise `false`

**Example**:
```bash
curl -X POST http://localhost:8546 \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "personal_unlockAccount",
    "params": ["0x...", "password123", null]
  }'
```

---

### personal_lockAccount

Locks the given account.

**Parameters**:
1. `DATA`, 20 Bytes - Address of the account to lock

**Returns**: `Boolean` - `true` if the account was locked, otherwise `false`

**Example**:
```bash
curl -X POST http://localhost:8546 \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "personal_lockAccount",
    "params": ["0x..."]
  }'
```

---

### personal_sendTransaction

Sends transaction from an account with passphrase.

**Parameters**:
1. `Object` - Transaction object (same as eth_sendTransaction)
2. `String` - Passphrase to decrypt the account

**Returns**: `DATA`, 32 Bytes - The transaction hash

**Example**:
```bash
curl -X POST http://localhost:8546 \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "personal_sendTransaction",
    "params": [{
      "from": "0x...",
      "to": "0x...",
      "value": "0x0"
    }, "password123"]
  }'
```

---

### personal_sign

Signs data with a given account's private key.

**Parameters**:
1. `DATA` - Data to sign
2. `DATA`, 20 Bytes - Address of the account
3. `String` - Passphrase to decrypt the account

**Returns**: `DATA` - Signature

**Example**:
```bash
curl -X POST http://localhost:8546 \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "personal_sign",
    "params": ["0xdeadbeaf", "0x...", "password123"]
  }'
```

---

### personal_ecRecover

Returns the address associated with the private key that was used to calculate the signature.

**Parameters**:
1. `DATA` - Data that was signed
2. `DATA` - Signature

**Returns**: `DATA`, 20 Bytes - The address that signed the data

**Example**:
```bash
curl -X POST http://localhost:8546 \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "personal_ecRecover",
    "params": ["0xdeadbeaf", "0x...signature..."]
  }'
```

---

## DEBUG Namespace

**⚠️ Performance Warning**: Debug methods can be resource-intensive and should be used carefully in production.

### debug_listPeersInfo

Returns information about connected peers.

**Parameters**: None

**Returns**: `Array` - Array of peer information objects

**Example**:
```bash
curl -X POST http://localhost:8546 \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "debug_listPeersInfo",
    "params": []
  }'
```

---

### debug_accountRange

Returns a range of accounts from the state trie.

**Parameters**:
1. `Object` - Account range parameters
   - `blockHash`: `DATA`, 32 Bytes - Block hash
   - `start`: `DATA` - Starting key
   - `maxResults`: `QUANTITY` - Maximum number of results
   - `noCode`: `Boolean` - Exclude code
   - `noStorage`: `Boolean` - Exclude storage
   - `incompletes`: `Boolean` - Include incomplete accounts

**Returns**: `Object` - Account range data

**Example**:
```bash
curl -X POST http://localhost:8546 \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "debug_accountRange",
    "params": [{
      "blockHash": "0x...",
      "start": "0x0",
      "maxResults": 256,
      "noCode": true,
      "noStorage": true,
      "incompletes": false
    }]
  }'
```

---

### debug_storageRangeAt

Returns a range of storage values.

**Parameters**:
1. `Object` - Storage range parameters
   - `blockHash`: `DATA`, 32 Bytes - Block hash
   - `txIndex`: `QUANTITY` - Transaction index
   - `address`: `DATA`, 20 Bytes - Contract address
   - `begin`: `DATA` - Starting storage key
   - `maxResults`: `QUANTITY` - Maximum number of results

**Returns**: `Object` - Storage range data

**Example**:
```bash
curl -X POST http://localhost:8546 \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "debug_storageRangeAt",
    "params": [{
      "blockHash": "0x...",
      "txIndex": 0,
      "address": "0x...",
      "begin": "0x0",
      "maxResults": 256
    }]
  }'
```

---

## Custom Namespaces

### FUKUII Namespace

#### fukuii_getAccountTransactions

Returns transactions for an account within a block range.

**Parameters**:
1. `DATA`, 20 Bytes - Account address
2. `QUANTITY` - Starting block number
3. `QUANTITY` - Ending block number

**Returns**: `Array` - Array of transaction objects

**Example**:
```bash
curl -X POST http://localhost:8546 \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "fukuii_getAccountTransactions",
    "params": ["0x...", 0, 1000]
  }'
```

**MCP Context**: Fukuii-specific extension for efficient account history retrieval.

---

### CHECKPOINTING Namespace (ETC-specific)

#### checkpointing_getLatestBlock

Returns the latest checkpoint block.

**Parameters**:
1. `QUANTITY` - Number of confirmations required

**Returns**: `Object` - Checkpoint block information

**Example**:
```bash
curl -X POST http://localhost:8546 \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "checkpointing_getLatestBlock",
    "params": [5]
  }'
```

---

#### checkpointing_pushCheckpoint

Pushes a checkpoint with signatures.

**Parameters**:
1. `DATA`, 32 Bytes - Block hash
2. `Array` - Array of signatures

**Returns**: `Boolean` - Success status

**Example**:
```bash
curl -X POST http://localhost:8546 \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "checkpointing_pushCheckpoint",
    "params": ["0x...", ["0x...signature1...", "0x...signature2..."]]
  }'
```

---

### QA Namespace: Testing

**⚠️ Development/Testing Only**: The QA namespace should be **disabled in production**. These methods are for testing and quality assurance purposes only.

#### qa_mineBlocks

Mines a specified number of blocks for testing purposes.

**Parameters**:
1. `QUANTITY` - Number of blocks to mine

**Returns**: `Boolean` - `true` if blocks were mined successfully

**Example**:
```bash
curl -X POST http://localhost:8546 \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "qa_mineBlocks",
    "params": [10]
  }'
```

**Note**: Only available in development/testing mode.

---

#### qa_generateCheckpoint

Generates a checkpoint for testing checkpointing functionality.

**Parameters**: None

**Returns**: `Object` - Checkpoint information

**Example**:
```bash
curl -X POST http://localhost:8546 \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "qa_generateCheckpoint",
    "params": []
  }'
```

---

#### qa_getFederationMembersInfo

Returns information about federation members for checkpoint testing.

**Parameters**: None

**Returns**: `Array` - Array of federation member information objects

**Example**:
```bash
curl -X POST http://localhost:8546 \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "qa_getFederationMembersInfo",
    "params": []
  }'
```

---

### TEST Namespace: Testing

**⚠️ Development/Testing Only**: The TEST namespace should be **disabled in production**. These methods allow chain manipulation for testing purposes and can compromise blockchain integrity.

#### test_setChainParams

Sets chain parameters for testing.

**Parameters**:
1. `Object` - Chain parameters configuration

**Returns**: `Boolean` - `true` if parameters were set successfully

**Example**:
```bash
curl -X POST http://localhost:8546 \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "test_setChainParams",
    "params": [{"chainId": "0x3f"}]
  }'
```

**Security Warning**: This method allows modification of chain parameters and should never be exposed in production.

---

#### test_mineBlocks

Mines a specified number of blocks for testing.

**Parameters**:
1. `QUANTITY` - Number of blocks to mine

**Returns**: `Boolean` - `true` if blocks were mined successfully

**Example**:
```bash
curl -X POST http://localhost:8546 \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "test_mineBlocks",
    "params": [5]
  }'
```

---

#### test_modifyTimestamp

Modifies the timestamp for testing time-dependent contract behavior.

**Parameters**:
1. `QUANTITY` - New timestamp value

**Returns**: `Boolean` - `true` if timestamp was modified successfully

**Example**:
```bash
curl -X POST http://localhost:8546 \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "test_modifyTimestamp",
    "params": [1700000000]
  }'
```

---

#### test_rewindToBlock

Rewinds the blockchain to a specific block number for testing reorganizations.

**Parameters**:
1. `QUANTITY` - Block number to rewind to

**Returns**: `Boolean` - `true` if rewind was successful

**Example**:
```bash
curl -X POST http://localhost:8546 \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "test_rewindToBlock",
    "params": [100]
  }'
```

**Security Warning**: This method modifies blockchain state and should never be exposed in production.

---

#### test_importRawBlock

Imports a raw block for testing purposes.

**Parameters**:
1. `DATA` - RLP-encoded block data

**Returns**: `Boolean` - `true` if block was imported successfully

**Example**:
```bash
curl -X POST http://localhost:8546 \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "test_importRawBlock",
    "params": ["0x..."]
  }'
```

---

#### test_getLogHash

Returns the hash of logs for verification in testing.

**Parameters**:
1. `QUANTITY` - Block number

**Returns**: `DATA`, 32 Bytes - Hash of the logs

**Example**:
```bash
curl -X POST http://localhost:8546 \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "test_getLogHash",
    "params": [1000]
  }'
```

---

#### miner_setEtherbase

Sets the etherbase (coinbase) address for mining rewards.

**Parameters**:
1. `DATA`, 20 Bytes - Address to receive mining rewards

**Returns**: `Boolean` - `true` if etherbase was set successfully

**Example**:
```bash
curl -X POST http://localhost:8546 \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "miner_setEtherbase",
    "params": ["0x..."]
  }'
```

**Note**: While this method is in the TEST namespace category, it uses the `miner_` prefix for compatibility.

---

### IELE Namespace

The IELE namespace provides methods for interacting with the IELE Virtual Machine, an alternative VM for smart contract execution. IELE is a register-based virtual machine designed with formal verification in mind.

**Note**: The IELE namespace is only available when Fukuii is configured with IELE VM support. This is an experimental feature not commonly used in production.

#### iele_call

Executes an IELE smart contract call.

**Parameters**:
1. `Object` - The call object (similar to eth_call)
2. `QUANTITY|TAG` - Block number or tag

**Returns**: `DATA` - The return value of the executed IELE contract

**Note**: This method is only available when IELE VM is enabled in configuration.

---

### RPC Namespace

#### rpc_modules

Returns a list of enabled RPC modules and their versions.

**Parameters**: None

**Returns**: `Object` - Object with module names as keys and versions as values

**Example**:
```bash
curl -X POST http://localhost:8546 \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "rpc_modules",
    "params": []
  }'
```

**Response**:
```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "result": {
    "eth": "1.0",
    "net": "1.0",
    "web3": "1.0",
    "personal": "1.0",
    "debug": "1.0",
    "fukuii": "1.0"
  }
}
```

---

## Error Codes

Fukuii uses standard JSON-RPC error codes plus Ethereum-specific codes:

### Standard JSON-RPC Errors
- `-32700`: Parse error
- `-32600`: Invalid Request
- `-32601`: Method not found
- `-32602`: Invalid params
- `-32603`: Internal error

### Ethereum-Specific Errors
- `-32000`: Server error (generic)
- `-32001`: Resource not found
- `-32002`: Resource unavailable
- `-32003`: Transaction rejected
- `-32004`: Method not supported
- `-32005`: Limit exceeded
- `-32006`: JSON-RPC version not supported

### Example Error Response
```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "error": {
    "code": -32602,
    "message": "Invalid params: expected 2 params, got 1"
  }
}
```

---

## Best Practices

### For MCP Server Integration

1. **Authentication & Authorization**
   - Implement API key authentication for MCP access
   - Rate limit based on API key
   - Disable sensitive methods (personal_*) in production

2. **Caching Strategy**
   - Cache immutable data (old blocks, receipts)
   - Use ETags for conditional requests
   - Implement TTL for mutable data (latest block, pending txs)

3. **Error Handling**
   - Always check `error` field in responses
   - Implement exponential backoff for retries
   - Log errors with context for debugging

4. **Performance**
   - Use batch requests when fetching multiple items
   - Prefer `eth_getLogs` over filter polling for one-time queries
   - Use `eth_getBlockReceipts` when fetching all receipts for a block

5. **Security**
   - Never expose personal_* methods publicly
   - Validate all user inputs
   - Use HTTPS/TLS for all connections
   - Implement IP whitelisting for admin methods

### Configuration for Production

```hocon
# Recommended RPC configuration for production
fukuii.network.rpc {
  http {
    mode = "http"
    enabled = true
    interface = "127.0.0.1"  # Only localhost
    port = 8546
    
    # Disable personal namespace
    apis = "eth,web3,net"
  }
  
  # Rate limiting
  rate-limit {
    enabled = true
    min-request-interval = 100.milliseconds
    latest-timestamp-cache-size = 1024
  }
}
```

### Batch Requests

Fukuii supports JSON-RPC batch requests for efficiency:

```bash
curl -X POST http://localhost:8546 \
  -H "Content-Type: application/json" \
  -d '[
    {"jsonrpc":"2.0","id":1,"method":"eth_blockNumber","params":[]},
    {"jsonrpc":"2.0","id":2,"method":"eth_gasPrice","params":[]},
    {"jsonrpc":"2.0","id":3,"method":"net_peerCount","params":[]}
  ]'
```

**Response**:
```json
[
  {"jsonrpc":"2.0","id":1,"result":"0xbc614e"},
  {"jsonrpc":"2.0","id":2,"result":"0x0"},
  {"jsonrpc":"2.0","id":3,"result":"0x5"}
]
```

---

## Additional Resources

- [Ethereum JSON-RPC Specification](https://ethereum.org/en/developers/docs/apis/json-rpc/)
- [Model Context Protocol Specification](https://modelcontextprotocol.io/specification/2025-03-26)
- [Fukuii GitHub Repository](https://github.com/chippr-robotics/fukuii)
- [Insomnia Workspace Guide](INSOMNIA_WORKSPACE_GUIDE.md) - How to use the API collection

---

**Maintained by**: Chippr Robotics LLC  
**Last Updated**: 2025-11-24  
**License**: Apache 2.0
