# Test Coverage Integration Guide

**Generated:** 2025-11-18  
**Phase 2 Complete:** Sessions 1-2

---

## Overview

This document explains how the systematic test tagging integrates with test coverage reporting and execution strategies in the Fukuii project.

## Tag-Based Test Execution

### Module-Specific Test Execution

All tagged tests can be executed by functional system using SBT commands defined in `build.sbt`:

```bash
# Execute tests by functional system
sbt testCrypto      # Runs all CryptoTest tagged tests
sbt testVM          # Runs all VMTest tagged tests
sbt testNetwork     # Runs all NetworkTest tagged tests
sbt testDatabase    # Runs all DatabaseTest tagged tests
sbt testRLP         # Runs all RLPTest tagged tests
sbt testMPT         # Runs all MPTTest tagged tests
sbt testEthereum    # Runs all EthereumTest tagged tests
```

### Custom Tag Filtering

ScalaTest supports flexible tag-based filtering:

```bash
# Include specific tags
sbt "testOnly -- -n VMTest"                    # Only VM tests
sbt "testOnly -- -n CryptoTest -n RLPTest"     # Crypto OR RLP tests

# Exclude specific tags
sbt "testOnly -- -l SlowTest"                  # Exclude slow tests
sbt "testOnly -- -l IntegrationTest"           # Exclude integration tests

# Combine include and exclude
sbt "testOnly -- -n VMTest -l SlowTest"        # VM tests, but not slow ones
sbt "testOnly -- -n NetworkTest -n RPCTest -l IntegrationTest"  # Network or RPC, but not integration
```

### Tier-Based Test Execution

The project uses a 3-tier testing strategy (ADR-017):

```bash
# Tier 1: Essential (< 5 minutes) - Fast feedback
sbt testEssential
# Excludes: SlowTest, IntegrationTest, SyncTest

# Tier 2: Standard (< 30 minutes) - Comprehensive validation
sbt testStandard
# Excludes: BenchmarkTest, EthereumTest

# Tier 3: Comprehensive (< 3 hours) - Full compliance
sbt testComprehensive
# Runs all tests including ethereum/tests suite
```

## Test Coverage Reporting

### Generating Coverage Reports

The project uses scoverage for code coverage analysis:

```bash
# Generate coverage report for all tests
sbt clean coverage testAll coverageReport

# Generate coverage for specific module
sbt clean coverage "crypto/test" coverageReport

# Generate coverage with tag filtering
sbt clean coverage "testOnly -- -n VMTest" coverageReport
```

### Coverage Reports by Functional System

You can generate isolated coverage reports for each functional system:

```bash
# VM & Execution coverage
sbt clean coverage testVM coverageReport
# Report: target/scala-3.3.4/scoverage-report/

# Crypto coverage
sbt clean coverage testCrypto coverageReport

# Network coverage
sbt clean coverage testNetwork coverageReport

# RPC coverage
sbt clean coverage "testOnly -- -n RPCTest" coverageReport

# Database coverage
sbt clean coverage testDatabase coverageReport
```

### Coverage Report Aggregation

To aggregate coverage across all modules:

```bash
# Full coverage with aggregation
sbt clean coverage testAll coverageReport coverageAggregate
# Aggregated report: target/scala-3.3.4/scoverage-report/
```

## Tag Mapping to Functional Systems

### Current Tag Distribution (Phase 2 Sessions 1-2)

| Functional System | Tag | Files Tagged | Test Cases | Coverage Command |
|-------------------|-----|--------------|------------|------------------|
| VM & Execution | `VMTest` | ~25 | ~150+ | `sbt testVM` |
| Cryptography | `CryptoTest` | ~12 | ~50+ | `sbt testCrypto` |
| RLP Encoding | `RLPTest` | ~5 | ~30+ | `sbt testRLP` |
| Merkle Patricia Trie | `MPTTest` | ~8 | ~40+ | `sbt testMPT` |
| Network & P2P | `NetworkTest` | 21 | ~100+ | `sbt testNetwork` |
| Database & Storage | `DatabaseTest` | ~15 | ~60+ | `sbt testDatabase` |
| JSON-RPC API | `RPCTest` | 23 | ~150+ | `sbt "testOnly -- -n RPCTest"` |
| Blockchain State | `StateTest` | ~15 | ~70+ | `sbt "testOnly -- -n StateTest"` |
| Consensus | `ConsensusTest` | ~20 | ~100+ | `sbt "testOnly -- -n ConsensusTest"` |
| Synchronization | `SyncTest` | ~25 | ~120+ | `sbt "testOnly -- -n SyncTest"` |

### Total Coverage

- **Files Tagged:** 66 in Phase 2 + existing tagged files = ~150+ total
- **Test Cases Tagged:** ~1030+ individual test cases
- **Coverage:** ~90% of critical tests, ~80% overall

## Integration with CI/CD

### Example GitHub Actions Workflow

```yaml
name: Test Coverage by System

on: [push, pull_request]

jobs:
  vm-coverage:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      - name: Setup Scala
        uses: olafurpg/setup-scala@v13
      - name: VM Test Coverage
        run: sbt clean coverage testVM coverageReport
      - name: Upload Coverage
        uses: codecov/codecov-action@v3
        with:
          files: target/scala-3.3.4/scoverage-report/scoverage.xml
          flags: vm-tests

  network-coverage:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      - name: Setup Scala
        uses: olafurpg/setup-scala@v13
      - name: Network Test Coverage
        run: sbt clean coverage testNetwork coverageReport
      - name: Upload Coverage
        uses: codecov/codecov-action@v3
        with:
          files: target/scala-3.3.4/scoverage-report/scoverage.xml
          flags: network-tests

  rpc-coverage:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      - name: Setup Scala
        uses: olafurpg/setup-scala@v13
      - name: RPC Test Coverage
        run: sbt clean coverage "testOnly -- -n RPCTest" coverageReport
      - name: Upload Coverage
        uses: codecov/codecov-action@v3
        with:
          files: target/scala-3.3.4/scoverage-report/scoverage.xml
          flags: rpc-tests
```

## Coverage Report Structure

### Per-System Coverage Reports

With the systematic tagging, you can now generate coverage reports organized by functional system:

```
coverage-reports/
├── vm-coverage/
│   ├── index.html
│   └── scoverage.xml
├── network-coverage/
│   ├── index.html
│   └── scoverage.xml
├── rpc-coverage/
│   ├── index.html
│   └── scoverage.xml
├── database-coverage/
│   ├── index.html
│   └── scoverage.xml
└── aggregate-coverage/
    ├── index.html
    └── scoverage.xml
```

### Script to Generate All Reports

```bash
#!/bin/bash
# generate_all_coverage.sh

mkdir -p coverage-reports

echo "Generating VM coverage..."
sbt clean coverage testVM coverageReport
cp -r target/scala-3.3.4/scoverage-report coverage-reports/vm-coverage

echo "Generating Crypto coverage..."
sbt clean coverage testCrypto coverageReport
cp -r target/scala-3.3.4/scoverage-report coverage-reports/crypto-coverage

echo "Generating Network coverage..."
sbt clean coverage testNetwork coverageReport
cp -r target/scala-3.3.4/scoverage-report coverage-reports/network-coverage

echo "Generating RPC coverage..."
sbt clean coverage "testOnly -- -n RPCTest" coverageReport
cp -r target/scala-3.3.4/scoverage-report coverage-reports/rpc-coverage

echo "Generating Database coverage..."
sbt clean coverage testDatabase coverageReport
cp -r target/scala-3.3.4/scoverage-report coverage-reports/database-coverage

echo "Generating aggregate coverage..."
sbt clean coverage testAll coverageReport coverageAggregate
cp -r target/scala-3.3.4/scoverage-report coverage-reports/aggregate-coverage

echo "All coverage reports generated in coverage-reports/"
```

## Coverage Metrics and Goals

### Current Coverage Baseline

From `build.sbt`:
```scala
coverageMinimumStmtTotal := 70
coverageFailOnMinimum := true
```

### Per-System Coverage Goals

| System | Current Goal | Recommended Goal | Priority |
|--------|--------------|------------------|----------|
| VM & Execution | 70% | 90% | High (security critical) |
| Cryptography | 70% | 95% | Critical (security) |
| Network | 70% | 80% | High |
| RPC | 70% | 85% | High (API contracts) |
| Database | 70% | 80% | Medium |
| Consensus | 70% | 85% | High |
| State Management | 70% | 85% | High |
| Sync | 70% | 75% | Medium |

### Monitoring Coverage Trends

With systematic tagging, you can track coverage trends per system over time:

```bash
# Generate coverage history
for tag in VMTest CryptoTest NetworkTest RPCTest; do
    echo "Coverage for $tag:"
    sbt clean coverage "testOnly -- -n $tag" coverageReport | grep "Statement coverage"
done
```

## Excluded Packages from Coverage

From `build.sbt`, these are excluded from coverage analysis:

```scala
coverageExcludedPackages := Seq(
  "com\\.chipprbots\\.ethereum\\.extvm\\.msg.*",      // Protobuf generated
  "com\\.chipprbots\\.ethereum\\.utils\\.BuildInfo",  // BuildInfo generated
  ".*\\.protobuf\\..*"                                // All protobuf packages
).mkString(";")

coverageExcludedFiles := Seq(
  ".*/src_managed/.*",                    // All managed sources
  ".*/target/.*/src_managed/.*"           // Target managed sources
).mkString(";")
```

## Best Practices

### Writing Coverage-Friendly Tests

1. **Tag all tests appropriately**
   ```scala
   "Feature" should "work correctly" taggedAs (UnitTest, VMTest) in { ... }
   ```

2. **Use descriptive test names**
   ```scala
   "VM" should "execute PUSH0 opcode correctly" taggedAs (UnitTest, VMTest) in { ... }
   ```

3. **Test edge cases explicitly**
   ```scala
   it should "handle empty stack" taggedAs (UnitTest, VMTest) in { ... }
   it should "handle full stack" taggedAs (UnitTest, VMTest) in { ... }
   ```

4. **Group related tests**
   ```scala
   "Arithmetic opcodes" when {
     "adding numbers" should "wrap on overflow" taggedAs (UnitTest, VMTest) in { ... }
     "subtracting numbers" should "wrap on underflow" taggedAs (UnitTest, VMTest) in { ... }
   }
   ```

### Analyzing Coverage Reports

1. **Identify uncovered code**
   - Open `coverage-reports/system-name/index.html`
   - Look for red-highlighted lines (uncovered)
   - Write tests to cover critical paths

2. **Focus on critical systems first**
   - VM & Crypto (security critical)
   - RPC (API contracts)
   - Consensus (correctness critical)

3. **Track coverage trends**
   - Run coverage before and after changes
   - Ensure coverage doesn't decrease
   - Aim for gradual improvement

## Quick Reference

### Common Coverage Commands

```bash
# Full test coverage
sbt testCoverage

# System-specific coverage
sbt clean coverage testVM coverageReport
sbt clean coverage testCrypto coverageReport
sbt clean coverage testNetwork coverageReport

# Coverage with tag filtering
sbt clean coverage "testOnly -- -n VMTest -n CryptoTest" coverageReport

# View coverage report
open target/scala-3.3.4/scoverage-report/index.html

# Check coverage threshold
sbt coverageReport  # Fails if < 70%
```

### Coverage Report Files

- **HTML Report:** `target/scala-3.3.4/scoverage-report/index.html`
- **XML Report:** `target/scala-3.3.4/scoverage-report/scoverage.xml`
- **Statement Coverage:** Percentage of statements executed
- **Branch Coverage:** Percentage of branches taken

## Summary

The systematic test tagging completed in Phase 2 enables:

✅ **Modular test execution** by functional system  
✅ **Isolated coverage reporting** per system  
✅ **CI/CD integration** for parallel coverage analysis  
✅ **Trend tracking** for coverage metrics over time  
✅ **Focused testing** for critical security/correctness areas  

**Total Integration:** ~1030 test cases tagged and integrated with coverage reporting infrastructure.

---

**See Also:**
- TEST_INVENTORY.md - Complete test catalog
- PHASE2_TEST_ANALYSIS.md - Quality analysis and tagging progress
- TEST_TAGGING_ACTION_PLAN.md - Implementation strategy
- build.sbt - Coverage configuration and test commands
