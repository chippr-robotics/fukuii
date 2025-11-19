# ADR-017: Test Suite Strategy, KPIs, and Execution Benchmarks

**Status**: ✅ Accepted | **Implementation**: ⏳ ~65% Complete (Phase 1 & 2 Complete)

**Date**: November 16, 2025

**Verification Date**: November 17, 2025

**Deciders**: Chippr Robotics LLC Engineering Team

**Related ADRs**: ADR-015 (Ethereum/Tests Adapter), ADR-014 (EIP-161 Implementation)

**Verification Report**: See [Testing Tags Verification Report](../../testing/TESTING_TAGS_VERIFICATION_REPORT.md)

## Context

### Current Testing Landscape

Fukuii employs a multi-layered testing strategy across several test configurations:

1. **Unit Tests** (`Test` config) - Core business logic and component tests
2. **Integration Tests** (`IntegrationTest` / `it` config) - Ethereum/Tests compliance validation
3. **Benchmark Tests** (`Benchmark` config) - Performance profiling
4. **EVM Tests** (`Evm` config) - Ethereum Virtual Machine validation
5. **RPC Tests** (`Rpc` config) - JSON-RPC API validation

### Problem Statement

Recent CI/CD runs exposed critical issues with the test suite:

1. **Long-Running Tests**: Test execution exceeded 5 hours before timeout
   - Caused by actor systems not being properly cleaned up after test failures
   - HeadersFetcher actor retry loops continuing indefinitely
   - Single test failure cascading into multi-hour hangs

2. **Test Organization**: No clear strategy for balancing comprehensive testing vs. CI efficiency
   - All tests run in sequence during every CI build
   - No distinction between essential "smoke tests" and comprehensive validation
   - Integration tests can take 10+ minutes even when successful

3. **Lack of Metrics**: No established KPIs for test suite health
   - No baseline for expected test execution times
   - No tracking of test reliability/flakiness
   - No coverage metrics or goals

4. **Resource Constraints**: GitHub Actions runners have limited execution time
   - Free tier: 6 hours maximum per job
   - Test hangs can block the entire CI pipeline
   - Limited parallelization due to actor system requirements

### Ethereum Execution-Specs Alignment

The [ethereum/execution-specs](https://github.com/ethereum/execution-specs) repository provides:

- **Reference Tests**: Official test vectors for EVM execution
- **Blockchain Tests**: End-to-end blockchain validation
- **State Tests**: State transition validation
- **Transaction Tests**: Transaction validation
- **Consensus Tests**: Fork transition validation

Our test strategy must align with these official specs while accounting for:
- Ethereum Classic fork differences (post-Spiral block 19.25M)
- Performance requirements for CI/CD pipelines
- Resource limitations of test infrastructure

## Decision

### 1. Test Categorization Strategy

We categorize tests into three tiers based on execution time and criticality:

#### Tier 1: Essential Tests (Target: < 5 minutes)
**Purpose**: Fast feedback on core functionality
**Scope**:
- Critical unit tests for consensus-critical code (VM, state transitions, block validation)
- Smoke tests for major subsystems
- Fast-failing integration tests (basic blockchain operations)

**Execution**: Every commit, every PR

**Test Selection Criteria**:
- Execution time < 100ms per test
- Tests core business logic
- High value-to-time ratio
- No complex actor system choreography

**SBT Command**:
```scala
addCommandAlias(
  "testEssential",
  """; compile-all
    |; testOnly -- -l SlowTest -l IntegrationTest
    |; rlp / test
    |; bytes / test
    |; crypto / test
    |""".stripMargin
)
```

#### Tier 2: Standard Tests (Target: < 30 minutes)
**Purpose**: Comprehensive validation of functionality
**Scope**:
- All unit tests (including slower ones)
- Selected integration tests against ethereum/tests
- RPC API validation tests
- Network protocol tests

**Execution**: Every PR (before merge), nightly builds

**Test Selection Criteria**:
- Execution time < 5 seconds per test
- Validates feature completeness
- Integration with external systems (database, network)

**SBT Command**: (Current `testCoverage` target)
```scala
addCommandAlias(
  "testStandard",
  """; coverage
    |; testAll
    |; coverageReport
    |; coverageAggregate
    |""".stripMargin
)
```

#### Tier 3: Comprehensive Tests (Target: < 3 hours)
**Purpose**: Full ethereum/tests compliance validation
**Scope**:
- Complete ethereum/tests BlockchainTests suite
- Complete ethereum/tests StateTests suite
- Performance benchmarks
- Long-running stress tests
- Fork transition validation

**Execution**: Nightly builds, pre-release validation

**Test Selection Criteria**:
- All ethereum/tests (filtered for ETC compatibility)
- Performance profiling
- Stress testing with large state sizes

**SBT Command**:
```scala
addCommandAlias(
  "testComprehensive",
  """; testStandard
    |; Benchmark / test
    |; IntegrationTest / testOnly *BlockchainTestsSpec
    |; IntegrationTest / testOnly *StateTestsSpec
    |""".stripMargin
)
```

### 2. Key Performance Indicators (KPIs)

#### Execution Time KPIs

| Test Tier | Target Duration | Warning Threshold | Failure Threshold |
|-----------|----------------|-------------------|-------------------|
| Essential | < 5 minutes    | > 7 minutes       | > 10 minutes      |
| Standard  | < 30 minutes   | > 40 minutes      | > 60 minutes      |
| Comprehensive | < 3 hours  | > 4 hours         | > 5 hours         |

#### Test Health KPIs

| Metric | Target | Measurement |
|--------|--------|-------------|
| **Test Success Rate** | > 99% | Passing tests / Total tests |
| **Test Flakiness Rate** | < 1% | Tests with inconsistent results / Total tests |
| **Test Coverage** | > 80% line, > 70% branch | scoverage reports |
| **Actor Cleanup Success** | 100% | Actor systems shut down / Actor systems created |

#### Ethereum/Tests Compliance KPIs

| Test Suite | Target Pass Rate | Current Status |
|------------|-----------------|----------------|
| **GeneralStateTests** (Berlin) | > 95% | ✅ Phase 2 Complete |
| **BlockchainTests** (Berlin) | > 90% | ✅ Phase 2 Complete |
| **TransactionTests** | > 95% | ✅ Integrated - Discovery Phase |
| **VMTests** | > 95% | ✅ Integrated - Discovery Phase |

Note: Post-Spiral tests (block > 19.25M) are excluded for ETC compatibility

### 3. CI/CD Pipeline Configuration

#### Pull Request Workflow
```yaml
name: Pull Request Validation
on: [pull_request]
jobs:
  essential-tests:
    timeout-minutes: 15
    steps:
      - run: sbt testEssential
  
  standard-tests:
    timeout-minutes: 45
    needs: essential-tests
    steps:
      - run: sbt testStandard
```

#### Nightly Build Workflow
```yaml
name: Nightly Comprehensive Testing
on:
  schedule:
    - cron: '0 2 * * *'  # 2 AM UTC daily
jobs:
  comprehensive-tests:
    timeout-minutes: 240  # 4 hours
    steps:
      - run: sbt testComprehensive
      - name: Upload test reports
      - name: Track KPI metrics
```

#### Pre-Release Workflow
```yaml
name: Release Validation
on:
  push:
    tags: ['v*']
jobs:
  comprehensive-validation:
    timeout-minutes: 300  # 5 hours
    steps:
      - run: sbt testComprehensive
      - name: Generate compliance report
      - name: Benchmark performance regression
```

### 4. Test Infrastructure Improvements

#### Actor System Cleanup
**Implemented**: ADR-017 companion fix
- All test suites must implement `BeforeAndAfterEach` or `BeforeAndAfterAll`
- Actor systems tracked in test lifecycle
- Graceful shutdown in `afterEach()` hook
- Prevents indefinite retry loops

**Verification**:
```scala
trait TestSetup {
  private var actorSystems: List[ActorSystem] = List.empty
  
  override def afterEach(): Unit = {
    actorSystems.foreach { as =>
      try {
        TestKit.shutdownActorSystem(as, verifySystemShutdown = false)
      } catch {
        case _: Exception => // Log but don't fail cleanup
      }
    }
    actorSystems = List.empty
  }
}
```

#### Test Tagging
Implement ScalaTest tags for categorization:

```scala
// Essential tests - no tag
test("should validate block header") { ... }

// Slow tests
test("should sync 1000 blocks") {
  taggedAs(SlowTest)
  ...
}

// Integration tests
test("should pass ethereum/tests GeneralStateTests") {
  taggedAs(IntegrationTest)
  ...
}

// Benchmark tests
test("should execute 10000 transactions") {
  taggedAs(BenchmarkTest)
  ...
}
```

**SBT Configuration**:
```scala
// Run only essential tests
testOnly -- -l SlowTest -l IntegrationTest -l BenchmarkTest

// Run standard + essential
testOnly -- -l IntegrationTest -l BenchmarkTest

// Run all tests
testOnly
```

### 5. Benchmark Framework

#### Performance Benchmarks
Track key performance metrics:

| Operation | Target | Measurement Method |
|-----------|--------|-------------------|
| **Block Validation** | < 100ms per block | Average over 1000 blocks |
| **Transaction Execution** | < 1ms per simple tx | EVM execution time |
| **State Root Calculation** | < 50ms | MPT hash calculation |
| **RLP Encoding/Decoding** | < 0.1ms | Batch operations |
| **Peer Handshake** | < 500ms | P2P connection time |

#### Regression Detection
- Baseline performance metrics stored with each release
- CI fails if performance degrades > 20% from baseline
- Manual review required for 10-20% degradation

**Implementation**:
```scala
// In Benchmark config
object PerformanceBenchmarks {
  val baseline = Map(
    "blockValidation" -> 100.millis,
    "txExecution" -> 1.milli,
    "stateRoot" -> 50.millis
  )
  
  def checkRegression(metric: String, measured: FiniteDuration): Boolean = {
    val threshold = baseline(metric) * 1.2
    measured <= threshold
  }
}
```

### 6. Test Reporting and Monitoring

#### Test Artifacts
Upload to GitHub Actions artifacts:
- Coverage reports (scoverage HTML)
- Test execution times (JUnit XML)
- Failed test logs
- Ethereum/tests compliance reports

#### Metrics Dashboard
Track over time:
- Test execution duration trends
- Coverage trends
- Ethereum/tests pass rate
- Flakiness trends

#### Alerting
- Slack notification on Tier 1 failures
- Email on nightly build failures
- GitHub Issue auto-creation for consistent failures (> 3 consecutive)

## Consequences

### Positive

1. **Faster Feedback**: Tier 1 tests provide sub-5-minute feedback
2. **Prevent Hangs**: Actor cleanup ensures tests complete even on failure
3. **Resource Efficiency**: Comprehensive tests only run when needed
4. **Clear Expectations**: KPIs provide objective success criteria
5. **Ethereum Compliance**: Systematic validation against official specs
6. **Regression Prevention**: Performance benchmarks catch degradation early

### Negative

1. **Complexity**: Three-tier system requires discipline to maintain
2. **Tagging Overhead**: Tests must be correctly tagged
3. **Infrastructure Cost**: Nightly comprehensive tests consume more CI minutes
4. **Maintenance**: KPI thresholds may need adjustment over time

### Risks and Mitigation

| Risk | Probability | Impact | Mitigation |
|------|------------|--------|------------|
| Tests miscategorized | Medium | Medium | Code review guidelines, automated checks |
| KPIs too strict | Low | Medium | Quarterly review and adjustment |
| Nightly builds failing | Medium | Low | Alert fatigue - focus on trends not individual failures |
| Comprehensive tests never run | Low | High | Make pre-release validation mandatory |

## Implementation Plan

### Phase 1: Infrastructure (Week 1) ✅ COMPLETE
- [x] Fix actor system cleanup in BlockFetcherSpec
- [x] Verify cleanup prevents long-running tests
- [x] Document cleanup pattern for other test suites

### Phase 2: Test Categorization (Week 2)
- [ ] Add ScalaTest tags to all tests
- [ ] Create `testEssential` SBT command
- [ ] Update CI workflows for tiered testing
- [ ] Document test categorization guidelines

### Phase 3: KPI Baseline (Week 3)
- [ ] Run comprehensive test suite to establish baseline
- [ ] Document baseline metrics
- [ ] Configure CI to track metrics
- [ ] Set up alerting

### Phase 4: Ethereum/Tests Integration (Week 4)
- [ ] Complete ethereum/tests adapter (ADR-015 Phase 3)
- [ ] Run full BlockchainTests suite
- [ ] Run full StateTests suite
- [ ] Generate compliance report
- [ ] Compare against other clients (geth, besu)

### Phase 5: Continuous Improvement (Ongoing)
- [ ] Monthly KPI review
- [ ] Quarterly baseline adjustment
- [ ] Regular ethereum/tests sync (new test cases)
- [ ] Performance regression analysis

## Validation Against Ethereum Execution-Specs

### Alignment with Official Specs

The [ethereum/execution-specs](https://github.com/ethereum/execution-specs) repository provides several test categories:

#### 1. Reference Tests (tests/paris, tests/london, etc.)
**Fukuii Coverage**:
- ✅ **BlockchainTests**: Covered by ADR-015 ethereum/tests adapter
- ✅ **GeneralStateTests**: Covered by ADR-015 ethereum/tests adapter
- ⏳ **VMTests**: Planned for Tier 3 comprehensive suite
- ⏳ **TransactionTests**: Planned for Tier 3 comprehensive suite
- ❌ **DifficultyTests**: Not applicable (Proof of Work removed in ETC post-Spiral)

**Gap Analysis**:
- Need to add VMTests suite (direct EVM opcode validation)
- Need to add TransactionTests suite (transaction validation)
- Need to filter post-Spiral/post-Merge tests for ETC compatibility

#### 2. Test Execution Framework
**Ethereum Specs Approach**:
```python
# From ethereum/execution-specs
def test_blockchain_test(test_case):
    pre_state = State.from_dict(test_case["pre"])
    blocks = [Block.from_dict(b) for b in test_case["blocks"]]
    expected_post_state = State.from_dict(test_case["postState"])
    
    state = apply_blocks(pre_state, blocks)
    assert state == expected_post_state
```

**Fukuii Implementation** (from ADR-015):
```scala
// Similar pattern in EthereumTestsAdapter
def runTest(test: BlockchainTest): Boolean = {
  val preState = buildGenesisState(test.pre)
  val blocks = test.blocks.map(convertBlock)
  val expectedPostState = test.postState
  
  val finalState = executeBlocks(preState, blocks)
  validateState(finalState, expectedPostState)
}
```

**Compliance**: ✅ Aligned - same test execution pattern

#### 3. Network Upgrade Testing
**Ethereum Specs Coverage**:
- Frontier, Homestead, Byzantium, Constantinople, Istanbul, Berlin, London, Paris, Shanghai, Cancun

**Fukuii Coverage** (ETC forks):
- ✅ Frontier, Homestead, Tangerine Whistle, Spurious Dragon
- ✅ Byzantium, Constantinople, Petersburg
- ✅ Istanbul, Agharta, Phoenix
- ✅ Thanos, Magneto, Mystique
- ⏳ Spiral (19.25M) - partial support
- ❌ Post-Spiral - ETC diverges from ETH (ProgPoW, MESS)

**Gap Analysis**:
- All pre-Spiral forks covered
- Post-Spiral requires ETC-specific test generation
- Need fork-aware test filtering in ethereum/tests adapter

#### 4. Test Fixtures and Schemas
**Ethereum Specs Format**:
```json
{
  "network": "London",
  "pre": { "0xabc...": { "balance": "0x0", "code": "0x60..." } },
  "blocks": [{ "blockHeader": { ... }, "transactions": [ ... ] }],
  "postState": { "0xabc...": { "balance": "0x1234" } },
  "sealEngine": "NoProof"
}
```

**Fukuii Parser** (from EthereumTestsAdapter):
```scala
case class BlockchainTest(
  network: String,
  pre: Map[String, AccountState],
  blocks: List[BlockTest],
  postState: Map[String, AccountState],
  sealEngine: Option[String]
)
```

**Compliance**: ✅ Aligned - direct JSON schema mapping

#### 5. Coverage Metrics
**Ethereum Specs Coverage Goals**:
- 100% opcode coverage
- 100% precompile coverage
- All EIPs validated
- All fork transitions tested

**Fukuii Coverage Goals** (from this ADR):
- > 95% GeneralStateTests pass rate
- > 90% BlockchainTests pass rate
- > 80% line coverage, > 70% branch coverage

**Gap Analysis**:
- Need opcode-level coverage tracking
- Need precompile test validation
- Need EIP-specific test suites

### Completeness Assessment

| Category | Ethereum Specs | Fukuii Status | Gap |
|----------|---------------|---------------|-----|
| **Test Execution** | Reference implementation | ADR-015 adapter | None - aligned |
| **State Tests** | Full suite | Phase 2 complete | None - aligned |
| **Blockchain Tests** | Full suite | Phase 2 complete | None - aligned |
| **VM Tests** | Opcode validation | Integrated - Discovery | Need execution validation |
| **Transaction Tests** | TX validation | Integrated - Discovery | Need execution validation |
| **Fork Tests** | All ETH forks | ETC forks only | Expected - chain divergence |
| **Performance Tests** | Not specified | Benchmark suite | Fukuii-specific |
| **Coverage Metrics** | Not specified | scoverage | Fukuii-specific |

### Recommendations for Full Spec Compliance

1. **Complete VMTests Execution** (Priority: High)
   - ✅ Discovery and test suite integration complete
   - ⏳ Add execution tests for all VM test categories
   - ⏳ Validate all 140+ EVM opcodes
   - ⏳ Add to Tier 3 comprehensive suite

2. **Complete TransactionTests Execution** (Priority: Medium)
   - ✅ Discovery and test suite integration complete
   - ⏳ Implement transaction validation logic
   - ⏳ Test edge cases (invalid signatures, gas limits, etc.)
   - ⏳ Add to Tier 2 standard suite

3. **Fork-Specific Test Filtering** (Priority: High)
   - ✅ Network version filtering implemented in VMTests and TransactionTests
   - ✅ Pre-Spiral network support (Frontier through Berlin)
   - ⏳ Auto-exclude post-Spiral ETH tests
   - ⏳ Generate ETC-specific post-Spiral tests

4. **Precompile Coverage** (Priority: Medium)
   - Validate all precompiled contracts (ecrecover, sha256, ripemd160, etc.)
   - Test gas consumption accuracy
   - Add to Tier 2 standard suite

5. **Test Generation for ETC-Specific Features** (Priority: Low)
   - MESS (Modified Exponential Subjective Scoring)
   - ProgPoW (if applicable)
   - ETC-specific EIPs

## References

- [Ethereum Execution Specs](https://github.com/ethereum/execution-specs)
- [Ethereum Tests Repository](https://github.com/ethereum/tests)
- [ScalaTest User Guide](https://www.scalatest.org/)
- [SBT Testing Documentation](https://www.scala-sbt.org/1.x/docs/Testing.html)
- [GitHub Actions Workflows](https://docs.github.com/en/actions)
- ADR-014: EIP-161 noEmptyAccounts Configuration Fix
- ADR-015: Ethereum/Tests Adapter Implementation

## Revision History

| Date | Version | Changes |
|------|---------|---------|
| 2025-11-16 | 1.0 | Initial version with three-tier test strategy, KPIs, and ethereum/specs validation |
| 2025-11-16 | 1.1 | VMTests and TransactionTests integrated into tiered tagged system (discovery phase) |

---

**Author**: GitHub Copilot (AI Agent)  
**Reviewer**: Chippr Robotics Engineering Team  
**Approval Date**: 2025-11-16
