# Ethereum/Tests CI Integration Guide

## Overview

This guide documents the integration of ethereum/tests into the Fukuii CI pipeline, providing automated validation of EVM compliance.

## CI Integration

### Standard CI Pipeline (ci.yml)

The standard CI pipeline runs on every push and pull request to main/master/develop branches.

#### Ethereum/Tests Execution

**Step:** "Run Ethereum/Tests Integration Tests"
- **Runs:** `SimpleEthereumTest` and `BlockchainTestsSpec`
- **Timeout:** 10 minutes
- **Execution Mode:** Non-blocking (continues even if tests fail)
- **When:** After standard test coverage, before build assembly

```yaml
sbt "IntegrationTest / testOnly *SimpleEthereumTest *BlockchainTestsSpec"
```

#### Test Coverage

The standard CI pipeline runs:
- **SimpleEthereumTest**: 4 basic validation tests (SimpleTx Berlin/Istanbul variants)
- **BlockchainTestsSpec**: ~10 focused blockchain tests
- **Total:** ~14 integration tests validating core EVM functionality

**Expected Runtime:** 5-10 minutes (within timeout)

#### Artifacts

1. **ethereum-tests-results-jdk21-scala-3.3.4**
   - Test execution logs
   - Integration test class outputs
   - Application logs from `/tmp/fukuii-it-test/`
   - **Retention:** 7 days

2. **test-results-jdk21-scala-3.3.4**
   - Standard test reports
   - Test class outputs
   - **Retention:** 7 days

### Nightly Comprehensive Tests (ethereum-tests-nightly.yml)

A comprehensive nightly workflow runs all ethereum/tests integration tests.

#### Schedule

- **Time:** 02:00 GMT (2 AM UTC) daily
- **Manual Trigger:** Available via workflow_dispatch

#### Comprehensive Test Suite

Runs all ethereum/tests integration test classes:
```bash
sbt "IntegrationTest / testOnly com.chipprbots.ethereum.ethtest.*"
```

**Test Classes:**
- `SimpleEthereumTest`: Basic validation (4 tests)
- `BlockchainTestsSpec`: Focused blockchain tests (10 tests)
- `ComprehensiveBlockchainTestsSpec`: Extended tests (98+ passing tests)
- `GeneralStateTestsSpec`: State transition tests
- `GasCalculationIssuesSpec`: Gas calculation validation (flagged tests)

**Expected Runtime:** 20-30 minutes
**Timeout:** 60 minutes

#### Artifacts

1. **ethereum-tests-nightly-logs**
   - Full test execution output (`ethereum-tests-output.log`)
   - Test summary report (`ethereum-tests-summary.md`)
   - Application logs from test execution
   - **Retention:** 30 days

2. **ethereum-tests-nightly-reports**
   - Detailed test reports
   - Test class outputs
   - **Retention:** 30 days

## Running Tests Locally

### Prerequisites

1. **Initialize ethereum/tests submodule:**
   ```bash
   git submodule init
   git submodule update
   ```

2. **Verify submodule is populated:**
   ```bash
   ls -la ets/tests/BlockchainTests/
   # Should show: GeneralStateTests, InvalidBlocks, TransitionTests, ValidBlocks
   ```

### Quick Smoke Tests (< 1 minute)

Run the basic validation tests:
```bash
sbt "IntegrationTest / testOnly *SimpleEthereumTest"
```

**Output:**
```
[info] Total number of tests run: 4
[info] Tests: succeeded 4, failed 0, canceled 0, ignored 0, pending 0
[info] All tests passed.
```

### Standard Test Suite (< 10 minutes)

Run the standard CI test suite:
```bash
sbt "IntegrationTest / testOnly *SimpleEthereumTest *BlockchainTestsSpec"
```

**Output:**
```
[info] Total number of tests run: 14
[info] Tests: succeeded 14, failed 0, canceled 0, ignored 0, pending 0
[info] All tests passed.
```

### Comprehensive Test Suite (20-30 minutes)

Run all ethereum/tests integration tests:
```bash
sbt "IntegrationTest / testOnly com.chipprbots.ethereum.ethtest.*"
```

**Expected Results:**
- **Passing:** 98+ tests across multiple categories
- **Failing:** Some tests may fail (documented in GAS_CALCULATION_ISSUES.md)
- **Categories:** ValidBlocks, StateTests, UncleTests, etc.

### Run Specific Test Categories

**BlockchainTests only:**
```bash
sbt "IntegrationTest / testOnly *BlockchainTestsSpec"
```

**Comprehensive BlockchainTests:**
```bash
sbt "IntegrationTest / testOnly *ComprehensiveBlockchainTestsSpec"
```

**GeneralStateTests only:**
```bash
sbt "IntegrationTest / testOnly *GeneralStateTestsSpec"
```

### Parallel Execution

Integration tests are configured to run in separate subprocesses:
- Each test suite runs in isolation
- Configured in `build.sbt` under `Integration` configuration
- Uses subprocess forking with unique test IDs

## Test Results and Reporting

### Understanding Test Output

**Successful Test:**
```
[info] - should pass SimpleTx test (1 second, 730 milliseconds)
[info]   + Running SimpleTx test from ValidBlocks/bcValidBlockTest...
[info]   + Loaded 2 test case(s)
[info]   + Running test: SimpleTx_Berlin
[info]   +   Network: Berlin
[info]   +   ✓ Test passed
[info]   +   Blocks executed: 1
```

**Failed Test:**
```
[info] - should pass add11 test *** FAILED ***
[info]   Gas calculation error: expected 43112 but got 41012 (difference: 2100)
```

### Viewing CI Results

1. **Navigate to GitHub Actions**
   - Go to repository → Actions tab
   - Select workflow run

2. **Check Test Summary**
   - View job logs for "Run Ethereum/Tests Integration Tests"
   - Look for test summary at end of output

3. **Download Artifacts**
   - Click on artifact name (e.g., `ethereum-tests-results-jdk21-scala-3.3.4`)
   - Download and extract to view logs

4. **Review Failures**
   - Check `fukuii.log` for detailed execution traces
   - Review test output logs for specific failure details

### Nightly Test Results

**Accessing Nightly Results:**
1. Go to Actions → Ethereum/Tests Nightly workflow
2. View latest run
3. Download artifacts (30-day retention)
4. Review `ethereum-tests-summary.md` for overview

## Performance Optimization

### Current Optimizations

1. **Caching**
   - SBT dependencies cached via `actions/cache`
   - Coursier cache persisted across runs
   - Ivy2 cache persisted across runs

2. **Parallel Execution**
   - Integration tests run in separate subprocesses
   - Each test suite isolated with unique test ID
   - Configured via `testGrouping` in build.sbt

3. **Selective Execution**
   - Standard CI runs focused test suite (~14 tests)
   - Nightly runs comprehensive suite (all tests)
   - Tests can be filtered by class pattern

### Future Optimizations

1. **Test Result Caching**
   - Cache test results for unchanged code
   - Skip tests for unchanged modules
   - Requires impact analysis implementation

2. **Test Sharding**
   - Split comprehensive tests across multiple jobs
   - Parallel execution across runners
   - Reduce total runtime

3. **Smart Test Selection**
   - Run only tests affected by code changes
   - Requires dependency analysis
   - More complex to implement

## Troubleshooting

### Submodule Not Initialized

**Error:**
```
Directory not found: /path/to/ets/tests/BlockchainTests
```

**Solution:**
```bash
git submodule init
git submodule update --recursive
```

### Tests Timeout

**Error:**
```
The job running on runner has exceeded the maximum execution time of 10 minutes.
```

**Solution:**
- Increase timeout in workflow file
- Run fewer tests in standard CI
- Move comprehensive tests to nightly

### Test Failures

**Known Issues:**
- See `docs/GAS_CALCULATION_ISSUES.md` for documented gas calculation issues
- Some tests may fail due to EIP support differences
- Check if failure is in documented issues before investigating

**Investigating New Failures:**
1. Download test artifacts
2. Review execution logs
3. Check state root differences
4. Analyze gas calculation differences
5. Compare with ethereum/tests expected values

## Integration Status

### Current State

✅ **Completed:**
- Ethereum/tests submodule integrated
- Test infrastructure implemented
- Standard CI integration complete
- Nightly comprehensive tests configured
- Artifact collection and retention set up
- Documentation complete

✅ **Test Coverage:**
- 98+ tests passing from official ethereum/tests
- Multiple test categories validated
- No regressions in existing tests

### Success Criteria Met

✅ **All Phase 3 Step 4 requirements met:**
- ✅ Automated test execution in CI
- ✅ Fast feedback (< 10 minutes for standard CI)
- ✅ Clear failure reports via artifacts
- ✅ Test results stored as artifacts
- ✅ Nightly comprehensive testing
- ✅ Manual trigger capability

## References

- **Issue:** Phase 3 Plan: Complete Test Suite Implementation
- **ADR:** ADR-015 Ethereum/Tests Integration
- **Documentation:**
  - `ETHEREUM_TESTS_MIGRATION.md` - Migration guide
  - `GAS_CALCULATION_ISSUES.md` - Known gas calculation issues
  - `PHASE_3_SUMMARY.md` - Phase 3 completion summary
- **Workflows:**
  - `.github/workflows/ci.yml` - Standard CI pipeline
  - `.github/workflows/ethereum-tests-nightly.yml` - Nightly comprehensive tests

---

**Last Updated:** 2025-11-15
**Status:** ✅ Complete - Phase 3 Step 4 CI Integration
