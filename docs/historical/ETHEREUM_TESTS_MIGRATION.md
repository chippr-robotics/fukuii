# Ethereum/Tests Migration Guide

## Overview

This guide documents the migration from custom test fixtures to the official ethereum/tests repository. This aligns with TEST-001 and provides better EVM validation coverage.

## Current Status

> **Note:** This document is historical. The gas-calculation blockers it references have been resolved (see [Gas Calculation Reference](../troubleshooting/GAS_CALCULATION_ISSUES.md)). The ethereum/tests integration is wired up via `src/it/scala/com/chipprbots/ethereum/ethtest/` and runs nightly through `.github/workflows/ethereum-tests-nightly.yml`. Retained for context on the migration approach.

### Phase 1-2: Complete
- JSON parsing infrastructure implemented
- Test execution framework working
- Initial validation tests passing

### Phase 3: Implemented
- GeneralStateTests, BlockchainTests, TransactionTests, VMTests, ExecutionSpecsStateTests, and ComprehensiveBlockchainTests spec files exist under `src/it/scala/com/chipprbots/ethereum/ethtest/`
- Nightly CI workflow exercises the suite
- Gas-calculation discrepancies that previously blocked Phase 3 have been resolved (EIP-2929 compliant)

## Test Categories

### Working Tests (84 passing)

**ValidBlocks/bcValidBlockTest** (24/29 passing)
- SimpleTx (Berlin, Istanbul) ✅
- ExtraData32 (Berlin, Istanbul) ✅
- dataTx (Berlin, Istanbul) ✅  
- RecallSuicidedContract
- And 18 more...

**ValidBlocks/bcStateTests** (60/80 passing)
- Various state transition tests
- Transaction execution tests
- Contract deployment tests

**ValidBlocks/bcUncleTest** (10/10 passing) ✨
- All uncle validation tests passing

### Failing Tests (35 failing)

**Gas Calculation Issues** (multiple tests)
- add11 tests - See [Gas Calculation Reference](../troubleshooting/GAS_CALCULATION_ISSUES.md)
- addNonConst tests - EIP-2929 related
- Various wallet tests - Gas calculation and state root issues

**State Root Mismatches** (some tests)
- May be related to gas calculation issues
- Requires investigation after gas fixes

## Mapping Old Tests to New Tests

### ForksTest.scala → BlockchainTests

**Old Test:** `ForksTest.scala`
- Custom test fixtures for fork validation
- Tests Homestead, EIP150, EIP160, EIP155 transitions

**New Tests:** `BlockchainTests/ValidBlocks/*`
- More comprehensive fork coverage
- Community-maintained and validated
- Covers same functionality plus more edge cases

**Recommendation:**
1. Keep ForksTest.scala temporarily for comparison
2. Validate that ethereum/tests covers all ForksTest scenarios
3. Mark ForksTest as deprecated
4. Remove after validation period (1-2 releases)

### ContractTest.scala → GeneralStateTests

**Old Test:** `ContractTest.scala`
- Tests contract deployment and execution
- Purchase contract example

**New Tests:** `BlockchainTests/GeneralStateTests/*`
- Thousands of contract tests
- Various opcodes and scenarios
- State transition validation

**Recommendation:**
1. Identify specific contract test scenarios in ContractTest
2. Find equivalent ethereum/tests (likely in stCreateTest, stCallCodes, etc.)
3. Mark ContractTest as deprecated
4. Document mapping in comments

### ECIP1017Test.scala → Keep (ETC-specific)

**Status:** KEEP - No migration needed

**Reason:**
- ETC-specific monetary policy (ECIP-1017)
- Not covered by ethereum/tests (ETH-only)
- Critical for ETC consensus
- No equivalent in ethereum/tests

## Migration Strategy

### Phase 1: Validation (Current)
- ✅ Run ethereum/tests alongside existing tests
- ✅ Verify coverage of existing scenarios
- ✅ Identify gaps or missing tests
- 🔴 **Fix gas calculation issues** - BLOCKING

### Phase 2: Deprecation (After gas fixes)
- Mark old tests as deprecated
- Add comments referencing ethereum/tests equivalents
- Update documentation

### Phase 3: Removal (Future - 1-2 releases)
- Remove deprecated tests
- Keep ECIP1017Test
- Full migration to ethereum/tests

## Test Execution

### Running Individual Tests

```bash
# Run simple validation tests
sbt "it:testOnly com.chipprbots.ethereum.ethtest.SimpleEthereumTest"

# Run blockchain tests
sbt "it:testOnly com.chipprbots.ethereum.ethtest.BlockchainTestsSpec"

# Run comprehensive test suite (84 tests)
sbt "it:testOnly com.chipprbots.ethereum.ethtest.ComprehensiveBlockchainTestsSpec"

# Run gas calculation analysis
sbt "it:testOnly com.chipprbots.ethereum.ethtest.GasCalculationIssuesSpec"
```

### Running All Integration Tests

```bash
sbt "it:test"
```

## Test File Organization

### Resources Directory
```
src/it/resources/ethereum-tests/
├── SimpleTx.json           # Basic value transfer (Berlin, Istanbul)
├── ExtraData32.json        # Extra data validation
├── dataTx.json             # Transaction with data
├── add11.json              # ⚠️ Failing - gas issue
└── addNonConst.json        # ⚠️ Failing - gas issue
```

### Test Specs
```
src/it/scala/com/chipprbots/ethereum/ethtest/
├── EthereumTestsSpec.scala              # Base class
├── SimpleEthereumTest.scala             # 4 validation tests ✅
├── BlockchainTestsSpec.scala            # 6 focused tests ✅
├── GeneralStateTestsSpec.scala          # ⚠️ 2 failing (gas issues)
├── ComprehensiveBlockchainTestsSpec.scala # 84 passing tests ✅
└── GasCalculationIssuesSpec.scala       # Analysis tool
```

## Network Support

### Supported Networks (Pre-Spiral)
All tests filtered to only run on supported networks:
- Frontier
- Homestead
- EIP150 (Tangerine Whistle)
- EIP158 (Spurious Dragon)
- Byzantium
- Constantinople
- Istanbul
- Berlin

### Unsupported Networks (Post-Spiral)
Tests for these networks are automatically filtered out:
- London
- Paris
- Shanghai
- Cancun
- Any future forks

## Known Issues

### Gas Calculation
**Status:** RESOLVED

See [Gas Calculation Reference](../troubleshooting/GAS_CALCULATION_ISSUES.md) for full details.

**Summary:**
- EIP-2929 gas costs verified correct
- Fork configuration fixed for Berlin tests
- Gas metering now Ethereum-compliant

### State Root Mismatches
**Status:** Under investigation

Some tests show state root mismatches. May be related to:
- Gas calculation issues (affects state)
- Storage handling
- Account state updates

## Test Coverage Goals

### Minimum
- 50+ tests passing — Achieved
- No gas calculation errors — Resolved (EIP-2929 compliant)
- Multiple test categories — Achieved

### Target
- 100+ tests passing
- < 5% failure rate
- All critical path scenarios covered

### Stretch (Future)
- 500+ tests passing
- < 1% failure rate
- Full ethereum/tests coverage for supported networks

## CI Integration

**Status:** Live. See `.github/workflows/ethereum-tests-nightly.yml`.

**Planned:**
```yaml
# .github/workflows/ethereum-tests.yml
name: Ethereum Tests

on: [pull_request, push]

jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
        with:
          submodules: recursive
      
      - name: Run Ethereum Tests
        run: sbt "it:test"
      
      - name: Check Gas Calculation
        run: sbt "it:testOnly com.chipprbots.ethereum.ethtest.GasCalculationIssuesSpec"
        # Should fail if gas issues exist
```

## References

- [ethereum/tests Repository](https://github.com/ethereum/tests)
- [TEST-001: Ethereum/Tests Adapter](../adr/testing/TEST-001-ethereum-tests-adapter.md)
- [Gas Calculation Reference](../troubleshooting/GAS_CALCULATION_ISSUES.md)
- [EIP-2929 Specification](https://eips.ethereum.org/EIPS/eip-2929)

## Contributing

### Adding New Test Files

1. Copy test from `ets/tests/` to `src/it/resources/ethereum-tests/`
2. Add test case to appropriate spec file
3. Run test and verify it passes
4. Update this migration guide

### Debugging Test Failures

1. Run specific test with detailed logging
2. Check gas calculations using GasCalculationIssuesSpec
3. Compare with reference implementation (geth)
4. Document findings

---

**Last Updated:** November 15, 2025 (status notes added during 2026 docs cleanup)
**Status:** Phase 3 implemented; nightly CI live; gas-calculation blockers resolved.
