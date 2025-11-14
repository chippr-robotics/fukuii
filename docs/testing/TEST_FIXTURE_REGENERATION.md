# Test Fixture Regeneration Guide

## Overview
This guide explains how to regenerate the integration test fixtures for `ForksTest` and `ContractTest` using actual Ethereum Classic blockchain data.

## Prerequisites
- Access to a synced Ethereum Classic node (core-geth or fukuii)
- Node must have RPC enabled
- Sufficient disk space for storing fixture data

## Configuration

### 1. Configure Connection Settings
Edit `src/it/resources/txExecTest/chainDump.conf`:

```hocon
# ETC Node connection details
node = "localhost:8545"  # Your ETC node RPC endpoint
genesisHash = "0xd4e56740f876aef8c010b86a40d5f56745a118d0906a34e69aec8c0db1cb8fa3"  # ETC mainnet genesis
networkId = 61  # ETC mainnet network ID

# Block range to dump
startBlock = 0
maxBlocks = 12  # For forksTest (blocks 0-11)
```

### 2. Run DumpChainApp

For **forksTest** fixtures (blocks 0-11):
```bash
sbt "it:runMain com.chipprbots.ethereum.txExecTest.util.DumpChainApp"
```

For **purchaseContract** fixtures (blocks 0-3):
Update `chainDump.conf` to set `maxBlocks = 4`, then run the same command.

### 3. Fixtures Generated

The app will generate the following files:

**For forksTest:**
- `src/it/resources/txExecTest/forksTest/headers.txt` - Block headers
- `src/it/resources/txExecTest/forksTest/bodies.txt` - Block bodies (transactions)
- `src/it/resources/txExecTest/forksTest/receipts.txt` - Transaction receipts
- `src/it/resources/txExecTest/forksTest/stateTree.txt` - Account state trie nodes
- `src/it/resources/txExecTest/forksTest/contractTrees.txt` - Contract storage trie nodes
- `src/it/resources/txExecTest/forksTest/evmCode.txt` - Deployed contract bytecode

**For purchaseContract:**
Same files under `src/it/resources/txExecTest/purchaseContract/`

## Verification

After regeneration, run the tests to verify:

```bash
# Test ForksTest
sbt "it:testOnly com.chipprbots.ethereum.txExecTest.ForksTest"

# Test ContractTest
sbt "it:testOnly com.chipprbots.ethereum.txExecTest.ContractTest"
```

Both tests should now pass with the regenerated fixtures.

## Troubleshooting

### Node Connection Issues
- Ensure your ETC node is fully synced
- Verify RPC is enabled and accessible
- Check firewall rules allow connection

### Incorrect Block Data
- Verify you're connected to the correct network (mainnet vs testnet)
- Ensure the genesis hash matches your target network
- Check that block numbers align with fork activations

### State Root Mismatches
If tests still fail after regeneration:
1. Verify the ETC node is fully synced
2. Ensure the node is running the correct consensus rules
3. Check that all blocks validate correctly on the node
4. Consider using core-geth as the reference implementation

## Technical Details

### How DumpChainApp Works

1. **Connects to ETC node** via RPC
2. **Fetches blocks** sequentially from `startBlock` to `startBlock + maxBlocks`
3. **Extracts data** from each block:
   - Block header (hash, state root, transactions root, etc.)
   - Block body (transactions, uncles)
   - Transaction receipts
   - State trie nodes (accounts)
   - Contract storage trie nodes
   - Contract bytecode
4. **Encodes to RLP** and writes hex-encoded to fixture files
5. **One line per block** - each line is `blockhash rlp_encoded_data`

### File Format

All fixture files follow the format:
```
<block_hash_hex> <rlp_encoded_data_hex>
```

For example, in `headers.txt`:
```
7ae05f48... f90210a0c2503fa5...
```

### Why Regeneration is Needed

Test fixtures were originally generated with Scala 2. The Scala 3 migration may have introduced subtle differences in:
- RLP encoding/decoding
- BigInt serialization
- State trie construction
- Receipt formatting

Regenerating with the Scala 3 version ensures fixtures match current execution behavior.

## Alternative: Using Existing ETC Test Vectors

If you don't have access to an ETC node, you can use official Ethereum test vectors:

1. Clone the Ethereum tests repository
2. Extract relevant test cases
3. Convert to fukuii's fixture format
4. Validate against core-geth execution

## References

- [Core-Geth](https://github.com/etclabscore/core-geth) - ETC reference implementation
- [Ethereum Tests](https://github.com/ethereum/tests) - Official test suite
- [ETC Specification](https://etclabscore.github.io/core-geth/) - Protocol documentation
