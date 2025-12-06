# Testing Documentation

This directory contains comprehensive testing documentation for the Fukuii Ethereum Classic client.

## Test Strategy and KPIs

### Architecture Decision Records (ADRs)
- **[TEST-001](../adr/testing/TEST-001-ethereum-tests-adapter.md)** - Ethereum/Tests Adapter Implementation
- **[TEST-002](../adr/testing/TEST-002-test-suite-strategy-and-kpis.md)** - Test Suite Strategy, KPIs, and Execution Benchmarks

### Implementation Verification
- **[TESTING_TAGS_VERIFICATION_REPORT.md](TESTING_TAGS_VERIFICATION_REPORT.md)** - Comprehensive verification report for testing tags ADR implementation (November 17, 2025)
- **[NEXT_STEPS.md](NEXT_STEPS.md)** - Action plan for completing remaining testing tags work (35% remaining)

### KPI Baseline Documentation
- **[KPI_BASELINES.md](KPI_BASELINES.md)** - Comprehensive KPI baseline definitions and targets
- **[PERFORMANCE_BASELINES.md](PERFORMANCE_BASELINES.md)** - Performance benchmark baselines for critical operations
- **[KPI_MONITORING_GUIDE.md](KPI_MONITORING_GUIDE.md)** - Practical guide for monitoring and maintaining KPIs

### Programmatic KPI Access
- **KPIBaselines.scala** - Scala object with baseline values (`src/test/scala/com/chipprbots/ethereum/testing/KPIBaselines.scala`)
- **KPIBaselinesSpec.scala** - Test suite validating baseline definitions (`src/test/scala/com/chipprbots/ethereum/testing/KPIBaselinesSpec.scala`)

### Launcher Integration Tests
- **[LAUNCHER_INTEGRATION_TESTS.md](LAUNCHER_INTEGRATION_TESTS.md)** - Comprehensive guide for launcher configuration validation tests
- **LauncherIntegrationSpec.scala** - Test suite validating all supported launch configurations (`src/test/scala/com/chipprbots/ethereum/LauncherIntegrationSpec.scala`)
- Replaces standalone `test-launcher-integration.sh` bash script with automated CI/CD integration

## Test Tier Classification

Based on TEST-002, tests are organized into three tiers:

### Tier 1: Essential Tests (< 5 minutes)
**Purpose**: Fast feedback on core functionality

**SBT Command**:
```bash
sbt testEssential
```

**Includes**:
- Core unit tests (bytes, crypto, rlp)
- Critical consensus logic tests
- Fast-running component tests

**Excludes**:
- Integration tests
- Slow tests
- Benchmark tests

### Tier 2: Standard Tests (< 30 minutes)
**Purpose**: Comprehensive validation before merge

**SBT Command**:
```bash
sbt testCoverage
```

**Includes**:
- All unit tests
- Selected integration tests
- RPC API tests
- Coverage reporting

**Excludes**:
- Comprehensive ethereum/tests
- Benchmark suites
- Long-running stress tests

### Tier 3: Comprehensive Tests (< 3 hours)
**Purpose**: Full validation before release

**SBT Command**:
```bash
sbt testComprehensive
```

**Includes**:
- All standard tests
- Full ethereum/tests suite
- Performance benchmarks
- Stress tests

## KPI Categories

### 1. Test Execution Time
Tracks how long test suites take to complete.

**Targets**:
- Essential: < 5 minutes
- Standard: < 30 minutes
- Comprehensive: < 3 hours

**Monitoring**: GitHub Actions workflow timing

### 2. Test Health
Measures quality and reliability of tests.

**Metrics**:
- Success Rate: > 99%
- Flakiness Rate: < 1%
- Line Coverage: > 80%
- Branch Coverage: > 70%

**Monitoring**: scoverage reports, test result tracking

### 3. Ethereum/Tests Compliance
Validates EVM compliance against official test suites.

**Targets**:
- GeneralStateTests: > 95%
- BlockchainTests: > 90%
- TransactionTests: > 95%
- VMTests: > 95%

**Current Status** (Phase 2):
- SimpleTx tests: 100% (4/4 passing)
- Full suite: Pending Phase 3

**Monitoring**: Nightly ethereum/tests runs

### 4. Performance Benchmarks
Measures execution speed for critical operations.

**Key Targets**:
- Block Validation: < 100ms
- Transaction Execution: < 1ms (simple transfer)
- State Root Calculation: < 50ms
- RLP Operations: < 0.1ms

**Monitoring**: Benchmark test suite

### 5. Memory Usage
Tracks heap consumption and GC overhead.

**Targets**:
- Peak Heap: < 2 GB
- GC Overhead: < 5%

**Monitoring**: JVM metrics, profiling tools

## CI/CD Integration

### Pull Request Workflow
**File**: `.github/workflows/ci.yml`

**Runs**:
- Essential tests
- KPI baseline validation
- Code coverage

**Timeout**: 15 minutes

### Nightly Workflow
**File**: `.github/workflows/ethereum-tests-nightly.yml`

**Runs**:
- Comprehensive ethereum/tests
- KPI baseline validation
- Performance benchmarks

**Timeout**: 60 minutes (expandable to 240 minutes)

### Release Validation
**Triggered by**: Version tags (v*)

**Runs**:
- Full comprehensive test suite
- Compliance reports
- Performance regression checks

**Timeout**: 300 minutes (5 hours)

## Quick Reference

### Running Tests Locally

```bash
# Essential tests (< 5 min)
sbt testEssential

# Standard tests with coverage (< 30 min)
sbt testCoverage

# Comprehensive tests (< 3 hours)
sbt testComprehensive

# Specific test
sbt "testOnly *KPIBaselinesSpec"

# Benchmarks
sbt "Benchmark / test"

# Integration tests
sbt "IntegrationTest / test"
```

### Viewing KPI Baselines

```scala
// In Scala code
import com.chipprbots.ethereum.testing.KPIBaselines

// Print summary
println(KPIBaselines.summary)

// Access specific baselines
val essentialTarget = KPIBaselines.TestExecutionTime.Essential.target
val blockValidationTarget = KPIBaselines.PerformanceBenchmarks.BlockValidation.target

// Validate against baseline
val actual = measureOperation()
val baseline = KPIBaselines.PerformanceBenchmarks.BlockValidation.simpleTxBlock.p50
val isRegression = KPIBaselines.Validation.isRegression(actual, baseline)
```

### Coverage Reports

```bash
# Generate coverage report
sbt testCoverage

# View HTML report
open target/scala-3.3.4/scoverage-report/index.html
```

## Monitoring and Alerting

### Daily Monitoring
1. Check PR build status
2. Review nightly test results
3. Monitor KPI trends

### Weekly Review
1. Analyze test execution time trends
2. Review coverage changes
3. Investigate flaky tests
4. Check performance regressions

### Quarterly Review
1. Update KPI baselines
2. Review and adjust thresholds
3. Document baseline changes
4. Plan optimization efforts

See [KPI_MONITORING_GUIDE.md](KPI_MONITORING_GUIDE.md) for detailed procedures.

## Baseline Maintenance

### When to Update Baselines

**Minor Updates** (document in git commit):
- After performance optimizations
- Bug fixes affecting metrics
- Test infrastructure improvements

**Major Updates** (update documentation):
- Quarterly reviews
- Significant architecture changes
- Major feature additions

### Update Process

1. Run comprehensive test suite (3+ iterations)
2. Calculate new P50/P95/P99 values
3. Compare with existing baselines
4. Document changes with justification
5. Update `KPI_BASELINES.md` and `KPIBaselines.scala`
6. Get engineering team approval
7. Commit with detailed changelog

## Troubleshooting

### Common Issues

**"Tests exceed timeout"**
- Check for hanging actor systems
- Review cleanup in `afterEach()` hooks
- Look for infinite loops or deadlocks

**"Coverage below target"**
- Add unit tests for new code
- Review coverage report for gaps
- Consider edge cases

**"Performance regression detected"**
- Profile affected operation
- Compare with baseline
- Optimize or justify change

**"Flaky test detected"**
- Run test multiple times
- Check for race conditions
- Use proper synchronization

See [KPI_MONITORING_GUIDE.md](KPI_MONITORING_GUIDE.md) for detailed troubleshooting.

## Additional Resources

### Ethereum Test Specifications
- [ethereum/tests Repository](https://github.com/ethereum/tests)
- [Ethereum Execution Specs](https://github.com/ethereum/execution-specs)
- [Test Format Documentation](https://ethereum-tests.readthedocs.io/)

### Testing Tools
- [ScalaTest Documentation](https://www.scalatest.org/)
- [scoverage Documentation](https://github.com/scoverage/scalac-scoverage-plugin)
- [SBT Testing Documentation](https://www.scala-sbt.org/1.x/docs/Testing.html)

### Performance Tools
- [Java Microbenchmark Harness (JMH)](https://github.com/openjdk/jmh)
- [Async-profiler](https://github.com/async-profiler/async-profiler)
- [VisualVM](https://visualvm.github.io/)

## Contributing

When adding new tests:

1. **Tag appropriately**: Use ScalaTest tags (SlowTest, IntegrationTest, etc.)
2. **Clean up resources**: Implement proper cleanup in lifecycle hooks
3. **Avoid flakiness**: No arbitrary sleeps, use proper synchronization
4. **Document performance**: Note if test is performance-sensitive
5. **Update baselines**: If test affects KPIs, update baseline documentation

When modifying baselines:

1. **Justify changes**: Document reason for baseline update
2. **Run multiple iterations**: Ensure new baseline is stable
3. **Get approval**: Engineering team reviews baseline changes
4. **Update all files**: KPI_BASELINES.md, KPIBaselines.scala, and related docs

## Revision History

| Date | Version | Changes | Author |
|------|---------|---------|--------|
| 2025-11-16 | 1.0 | Initial documentation with KPI baselines | GitHub Copilot |
| 2025-11-17 | 1.1 | Added testing tags verification report | GitHub Copilot |

---

**Maintained by**: Chippr Robotics Engineering Team  
**Last Updated**: November 17, 2025  
**Next Review**: February 16, 2026 (Quarterly)
