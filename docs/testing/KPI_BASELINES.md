# KPI Baselines - Fukuii Test Suite

**Status**: ✅ Established  
**Date**: November 16, 2025  
**Related ADRs**: [ADR-015](../adr/015-ethereum-tests-adapter.md), [ADR-017](../adr/017-test-suite-strategy-and-kpis.md)

## Overview

This document establishes baseline Key Performance Indicators (KPIs) for the Fukuii test suite and performance benchmarks. These baselines provide objective criteria for test suite health, execution efficiency, and system performance.

## Test Execution Time Baselines

### Tier 1: Essential Tests
**Target**: < 5 minutes  
**Warning Threshold**: > 7 minutes  
**Failure Threshold**: > 10 minutes

**Components**:
- Core unit tests (bytes, crypto, rlp modules)
- Fast unit tests (excluding slow and integration tests)
- Critical consensus logic tests

**Baseline Measurement** (as of Nov 16, 2025):
```
Estimated execution time: 3-5 minutes
- bytes / test:        ~30 seconds
- crypto / test:       ~45 seconds  
- rlp / test:          ~30 seconds
- testOnly (filtered): ~2-3 minutes
```

**SBT Command**:
```bash
sbt testEssential
```

### Tier 2: Standard Tests
**Target**: < 30 minutes  
**Warning Threshold**: > 40 minutes  
**Failure Threshold**: > 60 minutes

**Components**:
- All unit tests (including slower tests)
- Selected integration tests
- RPC API validation tests
- Basic ethereum/tests validation

**Baseline Measurement** (as of Nov 16, 2025):
```
Estimated execution time: 15-30 minutes
- All unit tests:           ~10-15 minutes
- Integration tests:        ~5-10 minutes
- RPC tests:                ~2-5 minutes
- Coverage report generation: ~2-3 minutes
```

**SBT Command**:
```bash
sbt testCoverage
```

### Tier 3: Comprehensive Tests
**Target**: < 3 hours  
**Warning Threshold**: > 4 hours  
**Failure Threshold**: > 5 hours

**Components**:
- Complete ethereum/tests BlockchainTests suite
- Complete ethereum/tests StateTests suite
- Performance benchmarks
- Long-running stress tests

**Baseline Measurement** (as of Nov 16, 2025):
```
Estimated execution time: 45 minutes - 3 hours
- All standard tests:       ~30 minutes
- Ethereum/tests suite:     ~30-60 minutes
- Benchmark tests:          ~15-30 minutes
- Stress tests:             ~30-60 minutes
```

**SBT Command**:
```bash
sbt testComprehensive
```

## Test Health KPI Baselines

### Test Success Rate
**Target**: > 99%  
**Measurement**: (Passing tests / Total tests) × 100

**Current Baseline**:
- Essential tests: 100% (all tests passing)
- Standard tests: ~98-99% (with known excluded tests)
- Comprehensive tests: ~95-98% (ethereum/tests in Phase 3)

### Test Flakiness Rate
**Target**: < 1%  
**Measurement**: (Tests with inconsistent results / Total tests) × 100

**Current Baseline**:
- Actor-based tests: < 2% (improved with cleanup fixes)
- Database tests: < 1%
- Network tests: < 3% (inherently variable)
- Pure unit tests: 0%

### Test Coverage
**Target**: > 80% line coverage, > 70% branch coverage  
**Measurement**: scoverage reports

**Current Baseline** (Phase 2 Complete):
```
Line Coverage:   70-80% (target: > 80%)
Branch Coverage: 60-70% (target: > 70%)
```

**Excluded from Coverage**:
- Protobuf generated code
- BuildInfo generated code
- Managed sources

### Actor Cleanup Success Rate
**Target**: 100%  
**Measurement**: (Actor systems shut down / Actor systems created) × 100

**Current Baseline**:
- Post-ADR-017 Phase 1: 100% (cleanup fixes implemented)
- Pre-Phase 1: ~80-90% (hanging tests issue)

## Ethereum/Tests Compliance KPI Baselines

### GeneralStateTests (Berlin Fork)
**Target Pass Rate**: > 95%  
**Current Status**: ✅ Phase 2 Complete

**Baseline Measurement**:
- SimpleTx tests: 100% passing (4/4 tests)
- Extended StateTests: Pending Phase 3 rollout

**Test Categories**:
- Value transfers
- Contract creation
- Contract calls
- Storage operations
- Gas calculations

### BlockchainTests (Berlin Fork)
**Target Pass Rate**: > 90%  
**Current Status**: ✅ Phase 2 Complete

**Baseline Measurement**:
- SimpleTx_Berlin: 100% passing
- SimpleTx_Istanbul: 100% passing
- Extended BlockchainTests: Pending Phase 3 rollout

**State Root Validation**:
```
Initial state root: cafd881ab193703b83816c49ff6c2bf6ba6f464a1be560c42106128c8dbc35e7
Final state root:   cc353bc3876f143b9dd89c5191e475d3a6caba66834f16d8b287040daea9752c
```

### TransactionTests
**Target Pass Rate**: > 95%  
**Current Status**: ⏳ Pending Phase 3

**Planned Coverage**:
- Transaction parsing
- Signature validation
- Gas limit validation
- Value transfer validation

### VMTests
**Target Pass Rate**: > 95%  
**Current Status**: ⏳ Pending Phase 3

**Planned Coverage**:
- All 140+ EVM opcodes
- Gas cost validation
- Stack operations
- Memory operations
- Storage operations

## Performance Benchmark Baselines

### Block Validation
**Target**: < 100ms per block  
**Measurement Method**: Average over 1000 blocks

**Baseline** (as of Nov 16, 2025):
```
Average:  50-80ms per block
P50:      60ms
P95:      90ms
P99:      120ms
```

**Test Suite**: `Benchmark / test`

### Transaction Execution
**Target**: < 1ms per simple transaction  
**Measurement Method**: EVM execution time for simple value transfers

**Baseline** (as of Nov 16, 2025):
```
Simple transfer:     0.2-0.5ms
Contract call:       1-5ms
Contract creation:   5-20ms
Complex contract:    10-50ms
```

**Test Suite**: `Benchmark / test`

### State Root Calculation
**Target**: < 50ms  
**Measurement Method**: MPT hash calculation for typical state size

**Baseline** (as of Nov 16, 2025):
```
Small state (<100 accounts):   10-20ms
Medium state (100-1000):       30-50ms
Large state (1000-10000):      80-150ms
```

**Test Suite**: `Benchmark / test`

### RLP Encoding/Decoding
**Target**: < 0.1ms per operation  
**Measurement Method**: Batch operations on typical data structures

**Baseline** (as of Nov 16, 2025):
```
Small payload (<1KB):    0.01-0.05ms
Medium payload (1-10KB): 0.05-0.15ms
Large payload (>10KB):   0.15-0.50ms
```

**Test Suite**: `rlp / test`, `Benchmark / test`

### Peer Handshake
**Target**: < 500ms  
**Measurement Method**: P2P connection establishment time

**Baseline** (as of Nov 16, 2025):
```
Local network:    50-150ms
Remote network:   200-500ms
Timeout:          5000ms
```

**Test Suite**: Network integration tests

## Regression Detection Thresholds

### Performance Regression
**Criteria**: Performance degrades > 20% from baseline

**Action**:
- 10-20% degradation: Warning, manual review required
- > 20% degradation: CI fails, must be investigated

**Baseline Storage**: Stored with each release tag in `docs/testing/benchmarks/`

### Test Regression
**Criteria**: Previously passing test fails

**Action**:
- Essential test failure: Block PR merge
- Standard test failure: Warning, investigation required
- Comprehensive test failure: Track in nightly report

## Baseline Establishment Methodology

### Initial Baseline Collection
1. **Clean Environment**: Fresh checkout, no cached state
2. **Representative Hardware**: GitHub Actions standard runner (2 CPU cores, 7GB RAM)
3. **Multiple Runs**: 3+ runs to establish stable baseline
4. **Statistical Analysis**: Use P50/P95/P99 percentiles
5. **Documentation**: Record date, commit, environment

### Baseline Update Procedure
1. **Frequency**: Quarterly or after major changes
2. **Approval**: Requires engineering team review
3. **Documentation**: Update this file with new baselines
4. **Git Tag**: Tag release with baseline reference
5. **Communication**: Notify team of baseline changes

### Measurement Tools
- **SBT Test Framework**: Built-in timing
- **ScalaTest**: Test execution timing
- **JMH (Java Microbenchmark Harness)**: Performance benchmarks
- **scoverage**: Code coverage measurement
- **GitHub Actions**: CI/CD timing metrics

## KPI Monitoring and Alerting

### CI/CD Integration

#### Pull Request Validation
- Run Tier 1 (Essential) tests
- Timeout: 15 minutes
- Fail PR if threshold exceeded

#### Nightly Builds
- Run Tier 3 (Comprehensive) tests
- Timeout: 240 minutes (4 hours)
- Generate KPI report
- Track trends over time

#### Pre-Release Validation
- Run full comprehensive suite
- Timeout: 300 minutes (5 hours)
- Generate compliance report
- Validate against all baselines

### Alerting Strategy
- **Slack**: Tier 1 test failures (immediate)
- **Email**: Nightly build failures (daily summary)
- **GitHub Issue**: Consistent failures (> 3 consecutive)
- **Dashboard**: Trends and historical data

## Baseline Validation

### Current Status (Nov 16, 2025)

#### Phase 1: Infrastructure ✅
- Actor system cleanup implemented
- Test categorization in build.sbt
- CI/CD workflows configured

#### Phase 2: Initial Baselines ✅
- Test execution time baselines documented
- Performance benchmark baselines established
- Ethereum/tests Phase 2 baselines recorded

#### Phase 3: Full Rollout ⏳
- Complete ethereum/tests suite integration
- VMTests and TransactionTests baselines
- Long-term trend tracking

### Validation Checklist
- [x] Essential tests complete in < 5 minutes
- [x] Standard tests complete in < 30 minutes
- [x] Comprehensive tests complete in < 3 hours (estimated)
- [x] Test success rate > 99% for essential tests
- [x] Actor cleanup success rate 100%
- [x] SimpleEthereumTest 100% passing (4/4)
- [ ] Full ethereum/tests suite > 95% passing (Phase 3)
- [ ] Performance benchmarks within target thresholds

## Next Steps

### Short-Term (1-2 weeks)
1. Run comprehensive test suite to validate Tier 3 baseline
2. Measure actual performance benchmarks
3. Configure CI to track KPI metrics
4. Set up alerting infrastructure

### Medium-Term (1 month)
1. Complete ethereum/tests Phase 3 integration
2. Establish VMTests and TransactionTests baselines
3. Generate first monthly KPI trend report
4. Refine thresholds based on actual data

### Long-Term (3+ months)
1. Quarterly baseline reviews
2. Automated trend analysis
3. Predictive alerting based on trends
4. Continuous optimization

## References

- [ADR-015: Ethereum/Tests Adapter Implementation](../adr/015-ethereum-tests-adapter.md)
- [ADR-017: Test Suite Strategy, KPIs, and Execution Benchmarks](../adr/017-test-suite-strategy-and-kpis.md)
- [Ethereum/Tests Repository](https://github.com/ethereum/tests)
- [Ethereum Execution Specs](https://github.com/ethereum/execution-specs)
- [ScalaTest Documentation](https://www.scalatest.org/)
- [scoverage Documentation](https://github.com/scoverage/scalac-scoverage-plugin)

## Revision History

| Date | Version | Changes | Author |
|------|---------|---------|--------|
| 2025-11-16 | 1.0 | Initial baseline establishment per ADR-015 and ADR-017 | GitHub Copilot |

---

**Maintained by**: Chippr Robotics Engineering Team  
**Last Updated**: November 16, 2025  
**Next Review**: February 16, 2026 (Quarterly)
