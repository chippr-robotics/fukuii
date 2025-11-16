# KPI Monitoring Guide - Fukuii Test Suite

**Status**: ✅ Active  
**Date**: November 16, 2025  
**Related Documents**: [KPI_BASELINES.md](KPI_BASELINES.md), [PERFORMANCE_BASELINES.md](PERFORMANCE_BASELINES.md), [ADR-017](../adr/017-test-suite-strategy-and-kpis.md)

## Overview

This guide provides practical instructions for monitoring Key Performance Indicators (KPIs) in the Fukuii Ethereum Classic client. It covers daily monitoring workflows, threshold interpretation, regression detection, and escalation procedures.

## Quick Reference

### KPI Categories
1. **Test Execution Time** - How long test suites take to complete
2. **Test Health** - Success rates, flakiness, and coverage
3. **Ethereum/Tests Compliance** - Pass rates for official test suites
4. **Performance Benchmarks** - Execution speed for critical operations
5. **Memory Usage** - Heap consumption and GC overhead

### Critical Thresholds
- **Essential Tests**: > 7 minutes = Warning, > 10 minutes = Failure
- **Test Success Rate**: < 99% = Investigation required
- **Performance Regression**: > 20% slower = CI fails
- **Memory**: > 2.4 GB peak = Warning
- **GC Overhead**: > 6% = Warning

## Monitoring Workflows

### 1. Pull Request Monitoring

**Frequency**: Every PR  
**Duration**: ~15 minutes  
**Scope**: Tier 1 (Essential) tests + KPI validation

#### What Gets Checked
- Essential tests complete in < 10 minutes
- KPI baseline definitions are valid
- No test regressions introduced
- Code formatting compliance

#### How to Monitor

**Via GitHub Actions UI**:
1. Navigate to PR → "Checks" tab
2. Look for "Test and Build (JDK 21, Scala 3.3.4)" workflow
3. Check "Validate KPI Baselines" step (should be ✅)
4. Review test timing in "Run tests with coverage" step

**Via Command Line** (local):
```bash
# Validate KPI baselines
sbt "testOnly *KPIBaselinesSpec"

# Run essential tests
sbt testEssential
```

#### Warning Signs
- ⚠️ "Validate KPI Baselines" step fails
- ⚠️ Test execution exceeds 7 minutes
- ⚠️ New test failures appear
- ⚠️ Coverage drops significantly

#### Actions
- **Green**: PR can proceed to review
- **Yellow**: Investigate warnings, may need optimization
- **Red**: Block merge, investigate and fix issues

### 2. Nightly Build Monitoring

**Frequency**: Daily at 02:00 UTC  
**Duration**: ~1-3 hours  
**Scope**: Tier 3 (Comprehensive) tests

#### What Gets Checked
- Complete ethereum/tests suite
- Performance benchmarks
- Long-running stress tests
- Trend analysis over time

#### How to Monitor

**Via GitHub Actions**:
1. Navigate to Actions → "Ethereum/Tests Nightly"
2. Check latest run status
3. Download and review artifacts:
   - `ethereum-tests-nightly-logs-*` - Execution logs
   - `ethereum-tests-nightly-reports-*` - Test reports

**Automated Notifications**:
- Slack alerts on failures (if configured)
- Email summaries (daily)
- GitHub Issues for persistent failures

#### Key Metrics to Track
```
Metric                          | Baseline | Warning | Failure
--------------------------------|----------|---------|--------
Total execution time            | 90 min   | 240 min | 300 min
Ethereum/tests pass rate        | 100%     | 95%     | 90%
Performance regression count    | 0        | 3       | 5
Memory peak                     | 1.5 GB   | 2.4 GB  | 3.0 GB
```

#### Actions
- **All Green**: Archive report, continue monitoring
- **Warnings**: Create tracking issue, investigate trends
- **Failures**: Immediate investigation, may block next release

### 3. Release Validation Monitoring

**Frequency**: Before each release  
**Duration**: ~3-5 hours  
**Scope**: Full comprehensive suite + compliance validation

#### What Gets Checked
- All test tiers (Essential, Standard, Comprehensive)
- Full ethereum/tests compliance report
- Performance benchmark comparison vs. baseline
- No performance regressions
- Coverage targets met

#### How to Monitor

**Manual Trigger**:
```bash
# Run comprehensive test suite
sbt testComprehensive

# Generate coverage report
sbt testCoverage

# Run benchmarks
sbt "Benchmark / test"
```

**Via GitHub Actions**:
1. Tag release candidate: `git tag -a v1.x.x-rc1`
2. Push tag: `git push origin v1.x.x-rc1`
3. Monitor "Release Validation" workflow
4. Review all artifacts and reports

#### Validation Checklist
- [ ] Essential tests < 5 minutes
- [ ] Standard tests < 30 minutes
- [ ] Comprehensive tests < 3 hours
- [ ] Test success rate > 99%
- [ ] Coverage > 80% line, > 70% branch
- [ ] No performance regressions > 10%
- [ ] Ethereum/tests compliance > 95%
- [ ] Memory usage < 2 GB peak
- [ ] GC overhead < 5%

#### Actions
- **Pass All**: Approve release
- **Minor Issues**: Document known issues, approve with caveats
- **Major Issues**: Block release, fix critical problems

## Interpreting KPI Metrics

### Test Execution Time

**What It Measures**: Wall-clock time to complete test suites

**Baseline Values**:
```
Essential:      4 minutes (target: < 5 minutes)
Standard:       22 minutes (target: < 30 minutes)
Comprehensive:  90 minutes (target: < 3 hours)
```

**How to Interpret**:
- **Within target**: Normal operation
- **Warning threshold**: Possible inefficiency, investigate trends
- **Failure threshold**: Critical issue, may indicate:
  - Test hangs (actor cleanup failure)
  - Database locks
  - Network timeouts
  - Infinite loops

**Common Causes of Degradation**:
1. Actor systems not being cleaned up (see ADR-017 Phase 1 fix)
2. Database connections leaking
3. Network tests with long timeouts
4. Excessive compilation time

**How to Fix**:
```scala
// Example: Add actor cleanup
override def afterEach(): Unit = {
  TestKit.shutdownActorSystem(system, verifySystemShutdown = false)
  super.afterEach()
}
```

### Test Health

**What It Measures**: Quality and reliability of test suite

**Baseline Values**:
```
Success Rate:    99.5% (target: > 99%)
Flakiness Rate:  0.5%  (target: < 1%)
Line Coverage:   75%   (target: > 80%)
Branch Coverage: 65%   (target: > 70%)
```

**How to Interpret**:
- **Success Rate < 99%**: Tests are failing consistently
- **Flakiness > 1%**: Tests have intermittent failures
- **Coverage < targets**: Insufficient test coverage

**Common Issues**:
1. **Flaky Network Tests**: Use mocks or increase timeouts
2. **Race Conditions**: Add proper synchronization
3. **Environment-Dependent Tests**: Use test fixtures
4. **Low Coverage**: Add tests for uncovered code paths

**How to Identify Flaky Tests**:
```bash
# Run same test multiple times
for i in {1..10}; do
  sbt "testOnly *SuspectedFlakyTest"
done
```

### Ethereum/Tests Compliance

**What It Measures**: Pass rate for official Ethereum test suites

**Baseline Values**:
```
GeneralStateTests:  100% (Phase 2: SimpleTx only)
BlockchainTests:    100% (Phase 2: SimpleTx only)
TransactionTests:   N/A  (Pending Phase 3)
VMTests:            N/A  (Pending Phase 3)
```

**How to Interpret**:
- **100% passing**: Full compliance for tested categories
- **95-99% passing**: Minor edge cases failing
- **< 95% passing**: Significant compliance issues

**Expected Evolution**:
- **Phase 2** (Current): SimpleTx tests at 100%
- **Phase 3** (Q1 2026): Full suite at > 95%
- **Ongoing**: Maintain > 95% as tests are added

**When Tests Fail**:
1. Check if test is ETC-compatible (pre-Spiral only)
2. Verify test expectations match ETC consensus rules
3. Investigate EVM implementation for bugs
4. Compare results with reference implementations (geth, besu)

### Performance Benchmarks

**What It Measures**: Execution speed for critical operations

**Key Baselines**:
```
Block Validation:      60ms P50 (target: < 100ms)
Tx Execution:          0.3ms P50 (target: < 1ms)
State Root Calc:       40ms P50 (target: < 50ms)
RLP Operations:        30μs P50 (target: < 100μs)
```

**How to Interpret**:
- **Within target**: Good performance
- **10-20% regression**: Warning, monitor trends
- **> 20% regression**: CI fails, must investigate

**Regression Detection**:
```scala
// Programmatic check
val actual = measureOperation()
val baseline = KPIBaselines.PerformanceBenchmarks.BlockValidation.simpleTxBlock.p50
val isRegression = KPIBaselines.Validation.isRegression(actual, baseline)
```

**Common Performance Issues**:
1. **Inefficient algorithms**: Review computational complexity
2. **Memory allocations**: Use object pooling
3. **Database access**: Batch operations, use caching
4. **Serialization overhead**: Optimize RLP encoding

### Memory Usage

**What It Measures**: Heap consumption and GC behavior

**Baseline Values**:
```
Node Startup:    200 MB stable (300 MB peak)
Fast Sync:       800 MB stable (1.5 GB peak)
Full Sync:       1.2 GB stable (2.0 GB peak)
GC Overhead:     2.5% (target: < 5%)
```

**How to Interpret**:
- **Peak < 2 GB**: Normal operation
- **Peak 2-2.4 GB**: Warning, may need tuning
- **Peak > 2.4 GB**: Memory leak suspected
- **GC > 5%**: GC pressure, heap too small or memory leak

**How to Investigate Memory Issues**:
```bash
# Enable GC logging
export JAVA_OPTS="-Xlog:gc*:file=gc.log -XX:+HeapDumpOnOutOfMemoryError"

# Analyze heap dump
jhat heap.dump
# Or use VisualVM, Eclipse MAT
```

**Common Memory Issues**:
1. **Caches not bounded**: Implement LRU eviction
2. **Large collections held in memory**: Stream processing
3. **Listeners not removed**: Proper cleanup in tests
4. **MPT nodes not released**: Ensure proper trie pruning

## Alerting and Escalation

### Alert Levels

#### Level 1: Info
**Trigger**: Metric approaches warning threshold  
**Action**: Document in daily summary, continue monitoring  
**Notification**: None  
**Example**: Test execution time increases from 4 to 5 minutes

#### Level 2: Warning
**Trigger**: Metric exceeds warning threshold  
**Action**: Create GitHub issue, investigate within 2 business days  
**Notification**: Slack (optional)  
**Example**: Test execution time exceeds 7 minutes

#### Level 3: Error
**Trigger**: Metric exceeds failure threshold or critical test fails  
**Action**: Immediate investigation, block merges/releases  
**Notification**: Slack + Email  
**Example**: Essential tests exceed 10 minutes, or test success rate < 99%

#### Level 4: Critical
**Trigger**: System-wide failure or data integrity issue  
**Action**: Incident response, all-hands investigation  
**Notification**: Slack + Email + On-call  
**Example**: Ethereum/tests compliance drops to 0%, memory leak crashes CI

### Escalation Paths

**Level 1 → Level 2**: Metric remains above warning for 3 consecutive days

**Level 2 → Level 3**: Metric exceeds failure threshold or no progress in 5 days

**Level 3 → Level 4**: Issue persists for 24 hours or affects production

### Resolution Process

1. **Investigate**: Collect logs, artifacts, and metrics
2. **Reproduce**: Recreate issue locally if possible
3. **Isolate**: Identify root cause through testing
4. **Fix**: Implement minimal change to resolve issue
5. **Validate**: Verify fix resolves issue without new regressions
6. **Document**: Update runbooks and baselines if needed
7. **Close**: Mark issue as resolved and verify in next build

## Trend Analysis

### Monthly KPI Review

**Purpose**: Identify long-term trends and preventive actions

**Metrics to Track**:
- Test execution time trends (increasing/decreasing)
- Flakiness rate over time
- Coverage trends
- Performance benchmark trends
- Memory usage trends

**Analysis**:
```
Month    | Essential | Standard | Coverage | Flakiness
---------|-----------|----------|----------|----------
2025-11  | 4.0 min   | 22 min   | 75%      | 0.5%
2025-12  | 4.2 min   | 24 min   | 76%      | 0.6%
2026-01  | 4.5 min   | 26 min   | 78%      | 0.4%
Trend    | +12%      | +18%     | +4%      | Stable
Action   | Monitor   | Optimize | On track | Good
```

**Actions**:
- **Increasing Times**: Review test efficiency, consider parallelization
- **Decreasing Coverage**: Add tests for new code
- **Increasing Flakiness**: Stabilize or remove flaky tests

### Quarterly Baseline Review

**Purpose**: Update baselines to reflect improvements or changes

**Process**:
1. Collect 90 days of metrics
2. Calculate P50/P95/P99 values
3. Compare with current baselines
4. Propose updated baselines if significant change
5. Document rationale in `KPI_BASELINES.md`
6. Get engineering team approval
7. Update `KPIBaselines.scala` with new values

**Criteria for Baseline Updates**:
- Sustained improvement > 10% for 3+ months
- Architectural change requires new baseline
- Test suite scope change (new categories added)

## Tools and Automation

### KPI Dashboard (Future)

**Planned Features**:
- Real-time KPI metrics from CI/CD
- Historical trend charts
- Automated regression detection
- Alert configuration UI

**Tech Stack**:
- Grafana for visualization
- Prometheus for metrics storage
- Custom exporters for test results

### Automated Reports

**Daily Summary Email**:
```
Subject: Fukuii KPI Summary - 2025-11-16

Essential Tests:     ✅ 4.2 min (target: < 5 min)
Standard Tests:      ✅ 23 min (target: < 30 min)
Nightly Build:       ✅ 95 min (target: < 180 min)
Test Success Rate:   ✅ 99.8%
Ethereum/Tests:      ✅ 100% (4/4 SimpleTx tests)
Performance:         ✅ No regressions
Memory:              ✅ 1.8 GB peak

No action required.
```

**Weekly Trend Report**:
```
Subject: Fukuii KPI Trends - Week of 2025-11-11

Test Execution Time:  📈 Increasing (+8% vs last week)
  Essential:          4.0 → 4.3 min
  Standard:           22 → 24 min
  Action:             Investigate slow tests

Coverage:             📊 Stable
  Line:               75.2% (target: 80%)
  Branch:             65.8% (target: 70%)
  Action:             Add tests for uncovered code

Performance:          ✅ Stable
  No regressions detected
  
Recommendations:
1. Profile slow tests in standard suite
2. Add unit tests to improve coverage
```

## Best Practices

### For Developers

1. **Run Essential Tests Locally**: Before pushing commits
2. **Check KPI Baselines**: When adding new tests or features
3. **Monitor CI Feedback**: Address failures promptly
4. **Use Benchmarks**: Profile performance-critical code

### For Test Authors

1. **Tag Tests Appropriately**: SlowTest, IntegrationTest, etc.
2. **Clean Up Resources**: Actor systems, databases, file handles
3. **Avoid Flakiness**: No sleeps, use proper synchronization
4. **Document Performance**: Note if test is performance-sensitive

### For Reviewers

1. **Check Test Execution Times**: Ensure PRs don't add slow tests
2. **Verify Coverage Changes**: Coverage should not decrease
3. **Review Benchmark Impact**: Performance regressions should be justified
4. **Validate KPI Impact**: Check if changes affect baselines

## Troubleshooting

### "KPI Baselines validation failed"

**Cause**: KPIBaselinesSpec test failed  
**Check**: Review test output for which assertion failed  
**Fix**: Update KPIBaselines.scala if values are incorrect

### "Test execution exceeded timeout"

**Cause**: Tests running longer than expected  
**Check**: Look for hanging tests or actor cleanup issues  
**Fix**: Add proper cleanup, increase timeout if justified

### "Performance regression detected"

**Cause**: Operation slower than baseline by > 20%  
**Check**: Profile the operation to find bottleneck  
**Fix**: Optimize code or update baseline if change is intentional

### "Coverage below target"

**Cause**: Code added without sufficient tests  
**Check**: Review coverage report for uncovered lines  
**Fix**: Add unit tests to cover new code

## References

- [KPI Baselines](KPI_BASELINES.md)
- [Performance Baselines](PERFORMANCE_BASELINES.md)
- [ADR-017: Test Suite Strategy and KPIs](../adr/017-test-suite-strategy-and-kpis.md)
- [Metrics and Monitoring](../operations/metrics-and-monitoring.md)
- [GitHub Actions Documentation](https://docs.github.com/en/actions)

---

**Maintained by**: Chippr Robotics Engineering Team  
**Last Updated**: November 16, 2025  
**Next Review**: February 16, 2026 (Quarterly)
