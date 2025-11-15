# ADR-015: Ethereum/Tests Adapter Implementation

**Status**: Accepted

**Date**: November 2025

**Deciders**: Chippr Robotics LLC Engineering Team

## Context

Following ADR-014's recommendation to adopt the ethereum/tests repository for comprehensive EVM validation, we need to implement a test adapter that can:

1. Parse JSON blockchain tests from the official ethereum/tests repository
2. Execute these tests against our EVM implementation
3. Replace brittle custom test fixtures with community-maintained tests
4. Ensure compliance with other execution clients (geth, nethermind, besu)

### Problem Discovery

**Current State:**
- Custom test fixtures require manual regeneration after EVM changes
- Test fixtures from PR #421 are incomplete/incorrect, blocking 93% of integration tests
- No systematic way to validate EVM compliance with Ethereum specification
- Maintenance burden of custom fixtures is high

**Requirements:**
1. Support for ethereum/tests JSON format
2. Execution of blockchain tests with pre/post state validation
3. Fork configuration mapping (Frontier → Berlin)
4. State root validation
5. Compatible with ETC blocks < 19.25M (pre-Spiral fork)

### ETC/ETH Compatibility Analysis

Per ADR-014, Ethereum Classic maintains **100% EVM compatibility** with Ethereum through block 19.25M (Spiral fork). This means:

- All opcodes, gas costs, and state transitions are identical
- Official ethereum/tests can be used directly for validation
- No ETC-specific modifications needed for pre-Spiral tests
- Post-Spiral tests must be filtered out

## Decision

We decided to implement a **multi-phase ethereum/tests adapter**:

### Phase 1: Infrastructure (Initial Implementation)

**Components:**
1. **JSON Parser** (`EthereumTestsAdapter.scala`)
   - Parse BlockchainTests JSON format
   - Support for pre-state, blocks, post-state, and network fields
   - Circe-based JSON decoding with strongly-typed case classes

2. **Domain Converter** (`TestConverter.scala`)
   - Convert JSON hex strings to internal domain objects
   - Map network names (e.g., "Byzantium") to fork configurations
   - Handle account state, transactions, and block headers

3. **Test Runner** (`EthereumTestsSpec.scala`)
   - Base class for running ethereum/tests
   - Test discovery and execution framework
   - Integration with ScalaTest

**Design Decisions:**

**A. JSON Parsing with Circe**
- Chose Circe for type-safe JSON parsing
- Explicit decoders for each test component
- Better error messages than reflection-based approaches

**B. Separation of Concerns**
- Parser (EthereumTestsAdapter) → Converter (TestConverter) → Executor (future)
- Each component has single responsibility
- Easy to test and maintain independently

**C. Hex String Handling**
- All ethereum/tests use hex strings with "0x" prefix
- Centralized parsing in TestConverter
- Validation of hex format before conversion

**D. Fork Configuration Mapping**
- Network names map to ForkBlockNumbers configuration
- All forks activated at block 0 for test scenarios
- Supports Frontier through Berlin (pre-Spiral only)

### Phase 2: Execution (Next Sprint)

**TODO:**
1. Implement `EthereumTestExecutor`
2. Use existing BlockExecution infrastructure
3. Set up state from JSON pre-state
4. Execute blocks and validate post-state
5. Compare state roots

### Phase 3: Integration (Future)

**TODO:**
1. Add ethereum/tests as git submodule or CI download
2. Create test suites for relevant categories
3. Replace ForksTest with ethereum/tests
4. Replace ContractTest with ethereum/tests
5. Remove custom fixture files

## Implementation Details

### File Structure

```
src/it/scala/com/chipprbots/ethereum/ethtest/
  ├── EthereumTestsAdapter.scala   - JSON parsing and loading
  ├── TestConverter.scala           - JSON to domain conversion
  └── EthereumTestsSpec.scala       - Test runner base class

docs/testing/
  └── ETHEREUM_TESTS_ADAPTER.md     - Comprehensive documentation
```

### Data Model

**BlockchainTestSuite** → Map[String, BlockchainTest]
- Each file contains multiple test cases
- Test name is the key

**BlockchainTest:**
```scala
case class BlockchainTest(
    pre: Map[String, AccountState],      // Initial state
    blocks: Seq[TestBlock],               // Blocks to execute
    postState: Map[String, AccountState], // Expected final state
    network: String                       // Fork configuration
)
```

**Conversion Flow:**
```
JSON String
  ↓ (parse)
BlockchainTestSuite
  ↓ (map)
List[(String, BlockchainTest)]
  ↓ (convert)
Internal Domain Objects
  ↓ (execute)
State Validation
```

### Network Mapping

| ethereum/tests | ETC Fork | Block Number |
|----------------|----------|--------------|
| Frontier | Frontier | 0 |
| Homestead | Homestead | 1.15M |
| EIP150 | Tangerine Whistle | 2.46M |
| EIP158 | Spurious Dragon | 3M |
| Byzantium | Atlantis | 8.77M |
| Constantinople | Agharta | 9.57M |
| Istanbul | Phoenix | 10.5M |
| Berlin | Magneto | 13.2M |

For test execution, all forks are activated at block 0 to match test expectations.

## Consequences

### Positive

**Test Quality:**
- ✅ Thousands of validated test cases from ethereum/tests
- ✅ Community-maintained and continuously updated
- ✅ Used by all major Ethereum clients (standard compliance)
- ✅ Comprehensive coverage of edge cases

**Maintenance:**
- ✅ No need to regenerate fixtures after EVM changes
- ✅ Automatic updates when ethereum/tests updated
- ✅ Reduced maintenance burden vs custom fixtures

**Validation:**
- ✅ Ensures EVM execution matches Ethereum specification
- ✅ Validates state root calculations
- ✅ Tests all opcodes and gas costs
- ✅ Covers fork transitions

**Development:**
- ✅ Clear separation of parser, converter, and executor
- ✅ Type-safe JSON parsing with Circe
- ✅ Easy to add new test categories
- ✅ Scalable architecture

### Negative

**Implementation Effort:**
- ⚠️ Multi-phase implementation (3 phases)
- ⚠️ Initial phase doesn't execute tests yet (parser only)
- ⚠️ Requires additional work to replace existing tests

**Dependencies:**
- ⚠️ Adds Circe JSON library dependency
- ⚠️ Requires ethereum/tests repository (git submodule or download)
- ⚠️ Test files are large (100s of MB)

**Test Execution Time:**
- ⚠️ Thousands of tests will take longer than current 14 tests
- ⚠️ May need test filtering/parallelization
- ⚠️ CI time may increase significantly

### Neutral

**Compatibility:**
- ℹ️ Only supports pre-Spiral forks (blocks < 19.25M)
- ℹ️ Post-Spiral tests must be filtered out
- ℹ️ Network names must be mapped to ETC fork names

## Alternative Approaches Considered

### 1. Manual Fixture Regeneration

**Approach:** Regenerate existing fixtures with corrected EVM config

**Pros:**
- Minimal code changes
- Fast implementation (1-2 days)

**Cons:**
- ❌ Doesn't solve long-term maintenance problem
- ❌ Still requires manual work after EVM changes
- ❌ Limited coverage (14 tests vs thousands)
- ❌ No validation against Ethereum spec

**Decision:** Rejected - doesn't meet long-term goals

### 2. Custom Test Generator

**Approach:** Build our own test generator from ETC node

**Pros:**
- Full control over test scenarios
- ETC-specific tests possible

**Cons:**
- ❌ High maintenance burden
- ❌ Requires synced ETC node
- ❌ Doesn't provide standard compliance validation
- ❌ Duplication of effort vs ethereum/tests

**Decision:** Rejected - reinventing the wheel

### 3. External Test Runner

**Approach:** Use existing ethereum/tests runner (e.g., retesteth)

**Pros:**
- No implementation needed
- Already mature and tested

**Cons:**
- ❌ Doesn't integrate with our test suite
- ❌ Different programming language (C++/Go)
- ❌ Harder to debug failures
- ❌ Not part of sbt test workflow

**Decision:** Rejected - poor integration

## Implementation Plan

### Phase 1: Infrastructure ✅ (Completed)

**Deliverables:**
- [x] EthereumTestsAdapter.scala - JSON parsing
- [x] TestConverter.scala - Domain conversion
- [x] EthereumTestsSpec.scala - Test runner
- [x] ETHEREUM_TESTS_ADAPTER.md - Documentation
- [x] ADR-015 - This document

**Validation:**
- [x] Code follows CONTRIBUTING.md guidelines
- [x] Scalafmt formatting applied
- [x] Proper package structure
- [x] Comprehensive documentation

### Phase 2: Execution (Next)

**Tasks:**
1. Implement EthereumTestExecutor
2. State setup from pre-state
3. Block execution loop
4. Post-state validation
5. State root comparison
6. Error reporting

**Timeline:** 1-2 weeks

### Phase 3: Integration (Future)

**Tasks:**
1. Download ethereum/tests repository
2. Filter tests by network (pre-Spiral only)
3. Create test suites by category
4. Replace ForksTest
5. Replace ContractTest  
6. Remove old fixtures

**Timeline:** 2-3 weeks

## Validation

### Code Quality

**Standards Met:**
- ✅ Follows Scala 3 best practices
- ✅ Type-safe JSON parsing with Circe decoders
- ✅ Comprehensive Scaladoc comments
- ✅ Clear separation of concerns
- ✅ Proper error handling

**Formatting:**
- ✅ Scalafmt configuration (.scalafmt.conf)
- ✅ 120 character max line length
- ✅ Consistent spacing and indentation
- ✅ Scala 3 dialect

**Testing:**
- ✅ Base test class provided
- ✅ Example usage documented
- ✅ Integration test location (src/it/scala)

### Documentation

**Comprehensive Docs:**
- ✅ ADR-015 (this document)
- ✅ ETHEREUM_TESTS_ADAPTER.md (user guide)
- ✅ Inline Scaladoc comments
- ✅ Architecture diagrams
- ✅ Usage examples

## Success Metrics

**Short-term (Phase 1):**
- ✅ JSON parser successfully decodes ethereum/tests format
- ✅ Converter produces valid domain objects
- ✅ Documentation is clear and comprehensive

**Medium-term (Phase 2):**
- [ ] Test executor runs simple value transfer tests
- [ ] State root validation passes
- [ ] At least 10 ethereum/tests pass successfully

**Long-term (Phase 3):**
- [ ] All relevant ethereum/tests categories passing
- [ ] ForksTest replaced with ethereum/tests
- [ ] ContractTest replaced with ethereum/tests
- [ ] CI runs ethereum/tests automatically
- [ ] 100+ tests passing from official test suite

## References

- [ethereum/tests Repository](https://github.com/ethereum/tests)
- [Test Format Documentation](https://ethereum-tests.readthedocs.io/)
- [ADR-014: EIP-161 noEmptyAccounts Fix](014-eip-161-noemptyaccounts-fix.md)
- [ETC Fork Timeline](https://etclabs.org/etc-forks)
- [CONTRIBUTING.md](../../CONTRIBUTING.md)

## Related Files

- `src/it/scala/com/chipprbots/ethereum/ethtest/EthereumTestsAdapter.scala`
- `src/it/scala/com/chipprbots/ethereum/ethtest/TestConverter.scala`
- `src/it/scala/com/chipprbots/ethereum/ethtest/EthereumTestsSpec.scala`
- `docs/testing/ETHEREUM_TESTS_ADAPTER.md`

## Lessons Learned

1. **JSON Parsing**: Circe provides excellent type safety for complex JSON structures
2. **Hex Handling**: Centralized hex string parsing prevents errors throughout codebase
3. **Fork Mapping**: Network names in ethereum/tests don't exactly match ETC fork names - requires translation layer
4. **Phased Approach**: Breaking implementation into phases allows incremental progress and validation
5. **Documentation First**: Writing comprehensive docs helps clarify design before implementation

## Future Considerations

1. **Performance**: May need test parallelization for large test suites
2. **Test Selection**: Implement filtering to run subset of tests (e.g., by category, fork, or difficulty)
3. **Continuous Updates**: Automate ethereum/tests repository updates in CI
4. **Custom Tests**: May still need some ETC-specific tests for post-Spiral behavior
5. **Test Result Database**: Track test results over time to detect regressions
