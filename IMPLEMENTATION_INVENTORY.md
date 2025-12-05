# Implementation Inventory - TODOs, FIXMEs, and Incomplete Features

**Generated:** 2025-12-05  
**Last Updated:** 2025-12-05 (reflects PR #725 Discovery v5 testing completion)  
**Purpose:** Comprehensive inventory of incomplete implementations, TODOs, and areas marked for future work

---

## Recent Updates

### ✅ Discovery v5 Testing Complete (PR #725 - Merged)
**Status:** Major testing milestone achieved

**Completed Work:**
- Added 1,605 lines of comprehensive test code
- Created 160+ test cases across 5 test files
- Full protocol coverage: Codec, Encryption, Handshake, Session, Integration
- All Discovery v5 message types tested (PING, PONG, FINDNODE, NODES, etc.)
- Security testing: AES-128-GCM encryption, authentication tag integrity, ECDH validation
- Integration testing: Full protocol flows, session establishment, encrypted communication

**Impact on Inventory:**
- Discovery v5 testing TODOs **resolved** ✅
- Test coverage significantly improved for scalanet/discovery module
- See [DISCV5_TESTING_SUMMARY.md](scalanet/discovery/DISCV5_TESTING_SUMMARY.md) for complete details

**Remaining Discovery v5 Work:**
- 3 TODOs in `DiscoveryNetwork.scala` for topic table and ENR parsing (implementation, not testing)
- 5 TODOs in `KRouter.scala` for Kademlia optimizations (low priority)

---

## Executive Summary

This document provides a comprehensive inventory of code areas in the Fukuii Ethereum Classic client that contain:
- TODO markers indicating planned future work
- FIXME markers indicating known issues requiring attention
- Stubbed or incomplete implementations
- NotImplemented code blocks (`???`)

The inventory is categorized by priority and functional area to support sprint planning and production readiness assessment.

---

## Summary Statistics

- **TODO markers**: 91
- **FIXME markers**: 24
- **XXX markers**: 0
- **NotImplemented (`???`)**: 17
- **Stub implementations**: ~50 (mostly in tests)
- **Total items**: 132

---

## Priority Classification

### P0 - Critical for Production (0 items)
**No critical blockers identified.** All consensus-critical and core functionality is implemented.

### P1 - Important for Production Quality (15 items)

#### 1. SNAP Sync Testing & Optimization
- **Status**: Core implementation complete (P0, P1), testing needed
- **Location**: `src/main/scala/com/chipprbots/ethereum/blockchain/sync/snap/`
- **Details**: See [SNAP_SYNC_TODO.md](docs/architecture/SNAP_SYNC_TODO.md)
- **Remaining Work**:
  - Unit tests for all SNAP sync components
  - Integration tests with mock network
  - End-to-end testing on testnet/mainnet
  - Performance profiling and optimization

#### 2. Fast Sync Atomicity Issues
- **Location**: `src/main/scala/com/chipprbots/ethereum/blockchain/sync/fast/`
- **TODOs**:
  - `FastSyncBranchResolver.scala:72` - Move blockchain operations to be atomic (ETCM-676)
  - `FastSyncBranchResolver.scala:96` - Move blockchain operations to be atomic (ETCM-676)
  - `FastSync.scala:212` - Move blockchain operations to be atomic (ETCM-676)
  - `FastSync.scala:250` - Move direct storage calls to blockchain writer (ETCM-1089)
- **Priority**: High - Atomicity is important for data consistency

#### 3. State Storage Cleanup
- **Location**: `src/main/scala/com/chipprbots/ethereum/ledger/BlockPreparator.scala:182`
- **FIXME**: Should we delete storage associated with deleted accounts? [EC-242]
- **Priority**: Medium - Affects disk space usage over time

#### 4. VM Performance Optimizations
- **Location**: `src/main/scala/com/chipprbots/ethereum/vm/OpCode.scala`
- **FIXMEs**:
  - Line 977: Gas calculation done twice, could optimize
  - Line 1187: Account existence checked twice [EC-243]
- **Priority**: Medium - Performance optimization for block execution

#### 5. Fork Management Architecture
- **Location**: `src/main/scala/com/chipprbots/ethereum/vm/EvmConfig.scala:33`
- **FIXME**: Manage ETC/ETH forks in a more sophisticated way [ETCM-249]
- **Priority**: Medium - Tech debt, current implementation works but not scalable

### P2 - Nice to Have (40 items)

#### Network Layer Improvements
1. **Capability Negotiation** (`network/PeerActor.scala:136`)
   - TODO: Pass capability to EtcHelloExchangeState
   
2. **Protocol Version Handling** (`network/rlpx/MessageCodec.scala:52`)
   - TODO: messageDecoder should use negotiated protocol version [ETCM-402]

3. **SNAP Server-Side Implementation** (`network/EtcPeerManagerActor.scala`)
   - TODOs at lines 394, 426, 458 for server-side SNAP request handling
   - Priority: Low - We function as a client, server support is optional

4. **Unknown Capability Logging** (`network/p2p/messages/Capability.scala:48`)
   - TODO: Log unknown capability warnings

#### Consensus and Mining
1. **MockedMiner Removal** (`consensus/pow/`)
   - Multiple TODOs (ETCM-773) to remove MockedMiner and refactor mining architecture
   - Priority: Medium - Tech debt cleanup

2. **Mining Configuration** (`consensus/mining/MiningBuilder.scala:78`)
   - TODO: Refactor configs to avoid running mocked or real miner incorrectly [ETCM-397]

#### Database and Storage
1. **Storage Optimization** (`db/storage/CachedKeyValueStorage.scala:33`)
   - TODO: Consider sliding window persist strategy [EC-491]

2. **Read Cache Investigation** (`db/cache/MapCache.scala:11`)
   - TODO: Investigate read cache in front of DB [EC-492]

3. **Storage Key Reuse** (`db/storage/BlockHeadersStorage.scala:33`)
   - TODO: Consider reusing key formula in other storages [ETCM-322]

4. **Genesis Hash Default** (`db/storage/AppStateStorage.scala:33`)
   - TODO: Provide genesis hash as default [ETCM-1090]

#### JSON-RPC API
1. **Configuration Cleanup** (`jsonrpc/server/controllers/JsonRpcBaseController.scala:39`)
   - FIXME: Making config mandatory in all controllers when not all need it

2. **Rate Limiting Config** (`jsonrpc/server/http/JsonRpcHttpServer.scala:202-210`)
   - TODOs for better rate limiting configuration structure

3. **Debug API Alignment** (`jsonrpc/JsonRpcController.scala:256`)
   - FIXME: Align debug_ API handling with other namespaces [ETCM-806]

4. **Test Service Storage** (`jsonrpc/TestService.scala:192`)
   - TODO: Clear storage between test runs

### P3 - Future Enhancements (77 items)

#### External VM Integration
- Multiple TODOs in `extvm/VMServer.scala` (lines 234-241)
- Need to include fork block numbers in protobuf messages
- Priority: Low - Current hardcoded values work for ETC mainnet

#### Test Utilities
- Multiple `???` (NotImplemented) blocks in test files
- These are intentional stubs for test fixtures
- Located in:
  - `txExecTest/util/DumpChainApp.scala` (6 items)
  - `extvm/MessageHandlerSpec.scala` (4 items)
  - `jsonrpc/JsonRpcControllerSpec.scala` (4 items)
- Priority: Very Low - Test utilities, not production code

#### Commented-Out Code
- `consensus/Consensus.scala` has 3 commented-out functions (lines 58, 65, 73)
- These appear to be dead code that can be removed
- Priority: Very Low - Code cleanup

---

## Analysis by Functional Area

### 1. Blockchain Sync (Priority: High)

#### SNAP Sync
- **Status**: ✅ All critical features complete (P0, P1)
- **Remaining**: Testing, optimization, monitoring (P2, P3)
- **Details**: See [SNAP_SYNC_TODO.md](docs/architecture/SNAP_SYNC_TODO.md)

**Summary from SNAP_SYNC_TODO.md:**
- ✅ P0 Complete: Message routing, Peer communication, Storage persistence, Sync mode selection
- ✅ P1 Complete: State storage, ByteCode download, State validation, Configuration, Progress monitoring, Error handling
- ⏳ P2 In Progress: Unit tests (ByteCodeTask ✅, StateValidator ✅, more needed), Integration tests
- ⏳ P3 Planned: End-to-end tests, Performance optimization, Grafana dashboards

**Success Criteria:**
- 7/12 criteria fully met
- 5/12 ready for network testing
- Overall progress: ~95%

#### Fast Sync
- **TODOs**: 5 items related to atomicity (ETCM-676, ETCM-1089)
- **Priority**: High - Data consistency issues
- **Recommendation**: Address before production use

#### Regular Sync
- **TODOs**: 2 items in integration tests
- **Priority**: Low - Test improvements only

#### Discovery v5 (Scalanet)
- **Status**: ✅ **Testing Complete** (PR #725 merged)
- **Test Coverage**: Comprehensive test suite (see Recent Updates section for details)
- **Remaining TODOs**: 8 items in implementation (verified 2025-12-05)
  - 3 in `DiscoveryNetwork.scala`: Topic table and ENR parsing
  - 5 in `KRouter.scala`: Kademlia optimizations
- **Priority**: Low - Core protocol and testing complete, remaining are enhancements
- **Details**: See [DISCV5_TESTING_SUMMARY.md](scalanet/discovery/DISCV5_TESTING_SUMMARY.md)

### 2. Consensus and Mining (Priority: Medium)

**TODOs**: 5 items (ETCM-773 - MockedMiner removal)
- Remove MockedMiner implementation
- Refactor to separate miner/non-miner configurations
- Make PoWMiningCoordinator trait sealed

**Priority**: Medium - Tech debt, doesn't affect functionality

### 3. Network Layer (Priority: Medium)

**TODOs**: 15 items
- SNAP server-side implementations (optional - we're primarily a client)
- Protocol negotiation improvements
- Capability handling enhancements
- Message codec improvements

**Priority**: Medium - Mostly enhancements, core functionality works

### 4. VM and Ledger (Priority: High)

**FIXMEs**: 5 items
- Gas calculation optimizations (done twice)
- Account existence check optimizations
- Fork management architecture
- Storage cleanup for deleted accounts
- Dependency on blockchain implementation vs interface

**Priority**: Medium-High - Performance and architecture improvements

### 5. Database and Storage (Priority: Medium)

**TODOs**: 5 items
- Cache strategies
- Key formula reuse
- ByteString conversions
- Read cache investigation

**Priority**: Low-Medium - Optimizations, current implementation works

### 6. JSON-RPC API (Priority: Low)

**TODOs**: 8 items
- Configuration cleanup
- Rate limiting improvements
- Debug API alignment
- Test service improvements

**Priority**: Low - API works, these are quality improvements

### 7. External VM (Priority: Low)

**TODOs**: 8 items
- Fork block numbers in protobuf
- Hello response validation

**Priority**: Very Low - Current implementation works for ETC

---

## Test Coverage Analysis

### Current Status

✅ **Test coverage is fully enabled and operational.** See [TEST_COVERAGE_STATUS.md](TEST_COVERAGE_STATUS.md) for complete details.

**Coverage Plugin**: ✅ Installed (scoverage 2.2.2)

**CI Integration**: ✅ Active
- Coverage runs on push to main/develop branches
- Reports uploaded to Codecov (https://codecov.io/gh/chippr-robotics/fukuii)
- Coverage artifacts stored in CI
- Codecov badge visible in README

**Configuration** (from `build.sbt`):
```scala
coverageEnabled := false           // Disabled by default, enable with `sbt coverage`
coverageMinimumStmtTotal := 70     // 70% minimum target
coverageFailOnMinimum := true      // Fail build if below minimum
coverageHighlighting := true       // Enable highlighting
```

**Excluded from Coverage**:
- Protobuf generated code: `com.chipprbots.ethereum.extvm.msg.*`
- BuildInfo generated code: `com.chipprbots.ethereum.utils.BuildInfo`
- All managed sources in `src_managed/`

### Test Tiers (ADR-017)

1. **Tier 1 - Essential** (`testEssential`)
   - Fast unit tests (< 5 minutes)
   - Excludes: SlowTest, IntegrationTest, SyncTest, DisabledTest
   - Runs on every PR

2. **Tier 2 - Standard** (`testStandard`)
   - Unit + Integration tests (< 30 minutes)
   - Excludes: BenchmarkTest, EthereumTest, DisabledTest
   - Runs with coverage on push events

3. **Tier 3 - Comprehensive** (`testComprehensive`)
   - All tests including ethereum/tests suite (< 3 hours)
   - Runs in nightly builds

### Coverage Commands

```bash
# Enable and run tests with coverage
sbt testCoverage

# Run tests without coverage (cleanup)
sbt testCoverageOff

# Manual coverage workflow
sbt coverage              # Enable coverage
sbt testEssential         # Run tests
sbt coverageReport        # Generate report
sbt coverageAggregate     # Aggregate all modules

# View coverage report
# Reports generated in: target/scala-*/scoverage-report/index.html
```

See [TEST_COVERAGE_STATUS.md](TEST_COVERAGE_STATUS.md) for complete coverage documentation.

### Baseline Coverage Metrics

**To be measured**: Current baseline needs to be established by running:
```bash
sbt "clean; coverage; testEssential; testStandard; coverageReport; coverageAggregate"
```

**Codecov Dashboard**: https://codecov.io/gh/chippr-robotics/fukuii

---

## Recommendations

### Immediate Actions (Sprint 1)

1. **Establish Coverage Baseline**
   - Run full test coverage analysis
   - Document current coverage percentages by module
   - Identify low-coverage areas

2. **Address Fast Sync Atomicity**
   - Fix ETCM-676 issues in FastSyncBranchResolver and FastSync
   - Ensure atomic blockchain operations
   - Add integration tests for edge cases

3. **SNAP Sync Testing**
   - Complete unit test suite for all SNAP components
   - Run integration tests with mock network
   - Document test results

### Short-term Actions (Sprints 2-3)

4. **VM Performance Optimizations**
   - Fix duplicate gas calculations (OpCode.scala:977, 1187)
   - Profile performance impact
   - Add performance regression tests

5. **Mining Architecture Cleanup**
   - Complete ETCM-773 (MockedMiner removal)
   - Refactor mining configuration
   - Improve type safety

6. **Coverage Improvements**
   - Target 80% statement coverage
   - Focus on consensus-critical code
   - Add missing test cases

### Long-term Actions (Future Sprints)

7. **Fork Management Refactoring**
   - Implement ETCM-249 (better fork management)
   - Make fork configurations more maintainable
   - Document fork activation logic

8. **Network Layer Enhancements**
   - Implement SNAP server-side support (optional)
   - Improve protocol negotiation
   - Add better capability handling

9. **Database Optimizations**
   - Investigate read caching (EC-492)
   - Implement sliding window persistence (EC-491)
   - Profile storage performance

### ✅ Recently Completed

- **Discovery v5 Testing** (PR #725) - Comprehensive test suite with 160+ test cases ✅

---

## Testing Coverage Goals

### Current CI Configuration

**From `.github/workflows/ci.yml`:**

```yaml
# Tier 1: Essential tests (every PR, < 5 min)
- Run Essential Tests

# Tier 2: Standard tests with coverage (push events, < 30 min)
- Run Standard Tests with Coverage
- Upload coverage to Codecov
- Upload coverage artifacts

# KPI Validation
- Validate KPI Baselines

# Ethereum/Tests Integration
- Run ethereum/tests integration suite
```

### Coverage Targets

| Module | Current | Target | Priority |
|--------|---------|--------|----------|
| Consensus | TBD | 90% | Critical |
| VM | TBD | 90% | Critical |
| Blockchain Sync | TBD | 80% | High |
| Network | TBD | 75% | Medium |
| Storage | TBD | 80% | High |
| JSON-RPC | TBD | 70% | Medium |
| Overall | TBD | 80% | - |

**Note:** TBD values to be measured by running coverage analysis.

---

## Monitoring and Tracking

### Issue Tracking

Each TODO/FIXME category should have corresponding GitHub issues:

- [ ] Create issue for Fast Sync atomicity (ETCM-676, ETCM-1089)
- [ ] Create issue for VM performance optimizations (EC-243)
- [ ] Create issue for Mining architecture cleanup (ETCM-773)
- [ ] Create issue for Fork management refactoring (ETCM-249)
- [ ] Create issue for SNAP Sync testing completion
- [ ] Create issue for Coverage baseline and improvements

### Regular Reviews

- **Weekly**: Review new TODOs/FIXMEs in PRs
- **Monthly**: Update this inventory document
- **Quarterly**: Review and prioritize tech debt

---

## Conclusion

The Fukuii codebase is in **good health** with:
- ✅ No critical P0 blockers for production
- ✅ Core functionality complete and working
- ✅ Test coverage infrastructure in place
- ✅ **Discovery v5 testing complete** (see Recent Updates section)
- ⏳ SNAP Sync 95% complete (testing phase)
- ⏳ Some P1 items requiring attention (Fast Sync atomicity, VM optimizations)
- ⏳ Significant P2/P3 tech debt to be addressed over time

**Production Readiness**: The client is production-ready for ETC mainnet with standard fast/regular sync. SNAP Sync requires network testing before production use.

**Recent Progress**: Discovery v5 testing comprehensively addressed, demonstrating strong commitment to test quality and protocol compliance.

**Next Steps**: 
1. Establish coverage baseline
2. Complete SNAP Sync testing
3. Address P1 items in priority order
4. Incrementally tackle P2/P3 tech debt

---

**Document Version**: 1.0  
**Last Updated**: 2025-12-05  
**Maintainer**: Fukuii Development Team
