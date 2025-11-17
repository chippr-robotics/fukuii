
# End-to-End State Test Suite

This document describes the state test suites created to troubleshoot blockchain peer and sync modules, leveraging official Ethereum execution specifications.

## Overview

Two complementary test suites have been implemented to comprehensively validate state-related operations:

### 1. E2EStateTestSpec
**Location**: `src/it/scala/com/chipprbots/ethereum/sync/E2EStateTestSpec.scala`

**Purpose**: End-to-end peer-to-peer state synchronization testing

**Coverage**:
- State trie synchronization between peers
- State root validation and consistency
- Account state propagation across peers
- Contract storage synchronization
- State healing and recovery mechanisms
- State integrity during blockchain operations

**Test Categories**:
- State Trie Synchronization (3 tests)
- State Root Validation (4 tests)
- Account State Propagation (2 tests)
- Contract Storage Synchronization (2 tests)
- State Healing and Recovery (3 tests)
- State Integrity (2 tests)

**Total**: 16 comprehensive test cases

### 2. ExecutionSpecsStateTestsSpec
**Location**: `src/it/scala/com/chipprbots/ethereum/ethtest/ExecutionSpecsStateTestsSpec.scala`

**Purpose**: Single-node state validation using official Ethereum execution specs

**Coverage**:
- EVM state transitions
- Opcode execution and gas costs
- Account state management (balance, nonce, storage, code)
- Contract creation and execution
- Pre-compiled contracts
- Fork-specific behavior

**Test Cases**:
- Basic arithmetic operations (ADD opcode)
- Account state transitions
- State root validation
- Contract execution
- Fork compatibility
- Account balance and nonce updates
- Gas calculations
- Storage operations
- Pre-compiled contracts
- Complete state validation

**Total**: 10 test cases

## Relationship to Ethereum Execution Specs

Both test suites leverage the official Ethereum test repository at https://github.com/ethereum/tests, which contains test cases generated from the Ethereum execution specifications at https://github.com/ethereum/execution-specs.

### Test Generation Flow
```
ethereum/execution-specs (Python specs)
    ↓
    Test generator
    ↓
ethereum/tests (JSON test files) ← Used by our tests
    ↓
    Fukuii test adapter
    ↓
    Test execution in Scala
```

The `ethereum/tests` repository is included as a git submodule at `ets/tests/`.

## Running the Tests

### Prerequisites

Ensure the ethereum/tests submodule is initialized:

```bash
git submodule init
git submodule update
```

Verify the submodule is populated:
```bash
ls -la ets/tests/BlockchainTests/
# Should show: GeneralStateTests, InvalidBlocks, TransitionTests, ValidBlocks
```

### Running All State Tests

```bash
# Run both state test suites
sbt "IntegrationTest / testOnly *StateTest*"
```

### Running Individual Test Suites

```bash
# Run E2E peer-to-peer state tests
sbt "IntegrationTest / testOnly com.chipprbots.ethereum.sync.E2EStateTestSpec"

# Run execution specs state tests
sbt "IntegrationTest / testOnly com.chipprbots.ethereum.ethtest.ExecutionSpecsStateTestsSpec"
```

### Running Specific Test Categories

```bash
# Run tests tagged with StateTest
sbt "IntegrationTest / testOnly * -- -n StateTest"

# Run all state tests except slow ones
sbt "IntegrationTest / testOnly *StateTest* -- -l SlowTest"
```

### Integration with CI

These tests are integrated into the CI pipeline:

- **Standard CI** (Every Push/PR): Runs essential state validation tests
- **Nightly Comprehensive Tests**: Runs all state tests including slow ones

See `.github/workflows/ci.yml` and `.github/workflows/ethereum-tests-nightly.yml`.

## Test Tags

All tests use appropriate tags for filtering:

- `IntegrationTest`: Mark as integration tests
- `StateTest`: State-related tests (for E2EStateTestSpec)
- `EthereumTest`: Tests using ethereum/tests (for ExecutionSpecsStateTestsSpec)
- `SlowTest`: Tests that may take longer to run
- `DatabaseTest`: Tests that involve database operations
- `NetworkTest`: Tests that involve network operations
- `SyncTest`: Tests related to synchronization

## Test Files Required

The tests require the following JSON test files in `src/it/resources/ethereum-tests/`:

- `add11.json` - Basic ADD operation test
- `addNonConst.json` - Non-constant addition test

These should be extracted from the ethereum/tests repository:
```bash
# From BlockchainTests/GeneralStateTests/stExample/add11.json
# From BlockchainTests/GeneralStateTests/stArgsZeroOneBalance/addNonConst.json
```

## Expected Behavior

### E2EStateTestSpec

Tests simulate real-world scenarios where:
1. One peer (peer1) has blockchain state
2. Another peer (peer2) syncs from peer1
3. State is validated for consistency across peers

Each test verifies that:
- State roots match between peers
- Account states are identical
- Storage is synchronized correctly
- State integrity is maintained during sync operations

### ExecutionSpecsStateTestsSpec

Tests validate that Fukuii's EVM implementation matches the official Ethereum execution specifications by:
1. Loading test cases from ethereum/tests
2. Executing transactions according to test parameters
3. Validating resulting state matches expected post-state
4. Verifying gas costs, storage updates, and account changes

## Troubleshooting

### Submodule Not Initialized

If tests fail with "resource not found" errors:
```bash
git submodule update --init --recursive
```

### Test File Not Found

If specific JSON test files are missing, extract them from the ethereum/tests submodule:
```bash
# Check available tests
find ets/tests/BlockchainTests/GeneralStateTests -name "*.json" | head -20

# Copy required test files
cp ets/tests/BlockchainTests/GeneralStateTests/stExample/add11.json src/it/resources/ethereum-tests/
cp ets/tests/BlockchainTests/GeneralStateTests/stArgsZeroOneBalance/addNonConst.json src/it/resources/ethereum-tests/
```

### Compilation Errors

If tests fail to compile:
```bash
# Ensure you're using the correct Scala version
sbt "show scalaVersion"
# Should output: 3.3.4

# Clean and recompile
sbt clean
sbt "IntegrationTest / compile"
```

## Future Enhancements

Potential expansions of the state test suite:

1. **More execution spec tests**: Add tests from other GeneralStateTests categories
2. **State snapshot testing**: Test state at specific block heights
3. **State pruning tests**: Validate state pruning operations
4. **Cross-fork state tests**: Test state across different Ethereum forks
5. **Performance benchmarks**: Measure state sync performance
6. **Stress tests**: Test with large state tries and many accounts

## References

- **Ethereum Execution Specs**: https://github.com/ethereum/execution-specs
- **Ethereum Tests Repository**: https://github.com/ethereum/tests
- **GeneralStateTests Documentation**: https://github.com/ethereum/tests/tree/develop/GeneralStateTests
- **Fukuii E2E Sync Tests**: `src/it/scala/com/chipprbots/ethereum/sync/E2ESyncSpec.scala`
- **Fukuii E2E Fast Sync Tests**: `src/it/scala/com/chipprbots/ethereum/sync/E2EFastSyncSpec.scala`

## Related Documentation

- [ETS README](../ets/README.md) - Ethereum Test Suite integration
- [Ethereum Tests CI Integration](docs/ETHEREUM_TESTS_CI_INTEGRATION.md)
- [Repository Structure](REPOSITORY_STRUCTURE.md)
- [Contributing Guide](CONTRIBUTING.md)
