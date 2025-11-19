# Test Tagging Action Plan

**Repository:** chippr-robotics/fukuii  
**Generated:** 2025-11-18  
**Purpose:** Systematic plan to complete test tagging and configure isolated logging

---

## Overview

This document provides a step-by-step action plan to complete the test tagging exercise for the Fukuii Ethereum Classic client. It complements the comprehensive test inventory (TEST_INVENTORY.md) and categorization spreadsheet (TEST_CATEGORIZATION.csv).

### Current Status
- ✅ **328 total test files** identified and categorized
- ✅ **519 tests already tagged** with ScalaTest tags
- ✅ **Comprehensive tag infrastructure** exists (Tags.scala with ADR-017)
- ⏳ **Remaining work:** Apply tags to untagged tests and configure isolated logging

---

## Phase 1: Complete Test Tagging

### Priority 1: Core Functionality Tests (High Impact)

#### 1.1 Virtual Machine Tests (~25 files)
**Location:** `src/test/scala/com/chipprbots/ethereum/vm/`  
**Tags to apply:** `VMTest`, `UnitTest`

**Files to tag:**
```
vm/BlakeCompressionSpec.scala
vm/CallOpcodesSpec.scala
vm/CallOpcodesSpecPostEip161.scala
vm/CallOpcodesPostEip2929Spec.scala
vm/CreateOpcodeSpec.scala
vm/Eip3529Spec.scala
vm/Eip3541Spec.scala
vm/Eip3651Spec.scala
vm/Eip3860Spec.scala
vm/Eip6049Spec.scala
vm/MemorySpec.scala
vm/OpCodeFunSpec.scala
vm/OpCodeGasSpec.scala
vm/OpCodeGasSpecPostEip161.scala
vm/OpCodeGasSpecPostEip2929Spec.scala
vm/PrecompiledContractsSpec.scala
vm/ProgramSpec.scala
vm/Push0Spec.scala
vm/SSTOREOpCodeGasPostConstantinopleSpec.scala
vm/ShiftingOpCodeSpec.scala
vm/StackSpec.scala
vm/StaticCallOpcodeSpec.scala
vm/VMSpec.scala
```

**Example tagging:**
```scala
"VM" should "execute PUSH0 opcode correctly" taggedAs(VMTest, UnitTest) in {
  // test implementation
}
```

#### 1.2 Cryptography Tests (12 files)
**Location:** `crypto/src/test/scala/com/chipprbots/ethereum/crypto/`  
**Tags to apply:** `CryptoTest`, `UnitTest`

**Files to tag:**
```
crypto/ECIESCoderSpec.scala
crypto/ECDSASignatureSpec.scala
crypto/ScryptSpec.scala
crypto/AesCtrSpec.scala
crypto/Ripemd160Spec.scala
crypto/AesCbcSpec.scala
crypto/Pbkdf2HMacSha256Spec.scala
crypto/zksnarks/FpFieldSpec.scala
crypto/zksnarks/BN128FpSpec.scala
```

#### 1.3 RLP Encoding Tests (2-4 files)
**Location:** `rlp/src/test/`, `src/test/scala/com/chipprbots/ethereum/rlp/`  
**Tags to apply:** `RLPTest`, `UnitTest`

### Priority 2: Infrastructure Tests (Medium Impact)

#### 2.1 Network & P2P Tests (~35 files)
**Location:** `src/test/scala/com/chipprbots/ethereum/network/`  
**Tags to apply:** `NetworkTest`, `UnitTest` (or `IntegrationTest` for complex scenarios)

**Key files:**
```
network/AuthHandshakerSpec.scala
network/EtcPeerManagerSpec.scala
network/KnownNodesManagerSpec.scala
network/PeerActorHandshakingSpec.scala
network/PeerManagerSpec.scala
network/discovery/PeerDiscoveryManagerSpec.scala
network/p2p/FrameCodecSpec.scala
network/p2p/MessageCodecSpec.scala
network/p2p/PeerActorSpec.scala
network/rlpx/RLPxConnectionHandlerSpec.scala
```

#### 2.2 Database & Storage Tests (~15 files)
**Location:** `src/test/scala/com/chipprbots/ethereum/db/`  
**Tags to apply:** `DatabaseTest`, `UnitTest` or `IntegrationTest`

**Key files:**
```
db/storage/AppStateStorageSpec.scala
db/storage/BlockBodiesStorageSpec.scala
db/storage/CachedNodeStorageSpec.scala
db/storage/ReadOnlyNodeStorageSpec.scala
db/storage/StateStorageSpec.scala
```

#### 2.3 JSON-RPC Tests (~30 files)
**Location:** `src/test/scala/com/chipprbots/ethereum/jsonrpc/`  
**Tags to apply:** `RPCTest`, `UnitTest`

**Key files:**
```
jsonrpc/EthBlocksServiceSpec.scala
jsonrpc/EthFilterServiceSpec.scala
jsonrpc/EthMiningServiceSpec.scala
jsonrpc/EthTxServiceSpec.scala
jsonrpc/NetServiceSpec.scala
jsonrpc/PersonalServiceSpec.scala
jsonrpc/DebugServiceSpec.scala
```

### Priority 3: Specialized Tests (Lower Impact)

#### 3.1 Ledger & State Tests (~15 files)
**Location:** `src/test/scala/com/chipprbots/ethereum/ledger/`  
**Tags to apply:** `StateTest`, `UnitTest`

#### 3.2 Consensus & Mining Tests (~25 files)
**Location:** `src/test/scala/com/chipprbots/ethereum/consensus/`  
**Tags to apply:** `ConsensusTest`, `UnitTest`, possibly `SlowTest`

#### 3.3 Synchronization Tests (~20 files)
**Location:** `src/test/scala/com/chipprbots/ethereum/blockchain/sync/`  
**Tags to apply:** `SyncTest`, `IntegrationTest`, `SlowTest`

**Note:** These tests are complex and involve actor choreography, so they are excluded from `testEssential` by design (see ADR-017).

#### 3.4 Integration Tests (~37 files)
**Location:** `src/it/scala/com/chipprbots/ethereum/`  
**Tags to apply:** `IntegrationTest` + appropriate system tag

**Ethereum compliance tests:**
- `EthereumTest`, `IntegrationTest`

**Files:**
```
ethtest/BlockchainTestsSpec.scala -> EthereumTest, IntegrationTest
ethtest/GeneralStateTestsSpec.scala -> EthereumTest, IntegrationTest
ethtest/VMTestsSpec.scala -> EthereumTest, IntegrationTest, VMTest
ethtest/TransactionTestsSpec.scala -> EthereumTest, IntegrationTest
db/RockDbIteratorSpec.scala -> DatabaseTest, IntegrationTest
network/E2EHandshakeSpec.scala -> NetworkTest, IntegrationTest
```

#### 3.5 Benchmark & Performance Tests (2 files)
**Location:** `src/benchmark/`  
**Tags to apply:** `BenchmarkTest`

---

## Phase 2: Configure Isolated Logging

### 2.1 Create Test Logging Configuration

**File:** `src/test/resources/logback-test.xml`

**Structure:**
1. Create separate file appenders for each functional system
2. Configure loggers with appropriate levels
3. Use rolling file policy for log management
4. Separate console output for test execution feedback

**Functional Systems & Log Files:**
- VM tests → `target/test-logs/vm-tests.log`
- Network tests → `target/test-logs/network-tests.log`
- Database tests → `target/test-logs/database-tests.log`
- RPC tests → `target/test-logs/rpc-tests.log`
- Consensus tests → `target/test-logs/consensus-tests.log`
- Ledger tests → `target/test-logs/ledger-tests.log`
- Crypto tests → `target/test-logs/crypto-tests.log`
- Data structures → `target/test-logs/datastructure-tests.log`
- Sync tests → `target/test-logs/sync-tests.log`
- Ethereum compliance → `target/test-logs/ethereum-tests.log`

### 2.2 Add .gitignore Entry

Ensure test logs are not committed:
```
target/test-logs/
```

### 2.3 Benefits of Isolated Logging
1. **Easy debugging** - Find logs for specific test failures quickly
2. **Performance analysis** - Identify slow operations per system
3. **CI/CD integration** - Archive logs by system for historical analysis
4. **Parallel execution** - No log interleaving between systems
5. **Troubleshooting** - Quickly locate issues in specific subsystems

---

## Phase 3: Validation & Testing

### 3.1 Validate Essential Tests (Tier 1)
```bash
time sbt testEssential
```
**Target:** < 5 minutes
**Excludes:** SlowTest, IntegrationTest, SyncTest

**Expected results:**
- Fast unit tests only
- No database operations
- No network I/O
- No actor system tests

### 3.2 Validate Standard Tests (Tier 2)
```bash
time sbt testStandard
```
**Target:** < 30 minutes
**Excludes:** BenchmarkTest, EthereumTest

**Expected results:**
- Unit + integration tests
- Database operations allowed
- Network tests included
- Sync tests included

### 3.3 Validate Module-Specific Tests
```bash
sbt testCrypto       # Should run only CryptoTest tagged tests
sbt testVM           # Should run only VMTest tagged tests
sbt testNetwork      # Should run only NetworkTest tagged tests
sbt testDatabase     # Should run only DatabaseTest tagged tests
sbt testRLP          # Should run only RLPTest tagged tests
sbt testMPT          # Should run only MPTTest tagged tests
```

### 3.4 Validate Comprehensive Tests (Tier 3)
```bash
time sbt testComprehensive
```
**Target:** < 3 hours
**Includes:** All tests

---

## Phase 4: Documentation Updates

### 4.1 Update README.md
Add section on test execution:
```markdown
## Testing

### Quick Testing
- **Essential tests** (< 5 min): `sbt testEssential`
- **Standard tests** (< 30 min): `sbt testStandard`
- **All tests** (< 3 hours): `sbt testComprehensive`

### Module-Specific Testing
- Crypto: `sbt testCrypto`
- VM: `sbt testVM`
- Network: `sbt testNetwork`
- Database: `sbt testDatabase`

### Test Logs
Test logs are written to `target/test-logs/` organized by functional system.
```

### 4.2 Update CONTRIBUTING.md
Add guidelines for test tagging:
```markdown
## Test Tagging Guidelines

All new tests must be tagged appropriately:

### Required Tags
- **Type tag**: UnitTest, IntegrationTest, BenchmarkTest
- **System tag**: VMTest, NetworkTest, CryptoTest, etc.

### Optional Tags
- **Performance tag**: SlowTest (> 100ms), FastTest (< 10ms)
- **Fork tag**: ByzantiumTest, IstanbulTest, etc.

### Example
```scala
"Block validator" should "reject invalid blocks" taggedAs(UnitTest, ConsensusTest) in {
  // test implementation
}
```
```

### 4.3 Create ADR (if needed)
If not already documented, create an ADR for the test tagging strategy and isolated logging configuration.

---

## Execution Timeline

### Week 1: High-Priority Tagging
- [ ] Day 1-2: Tag all VM tests (VMTest, UnitTest)
- [ ] Day 3: Tag all crypto tests (CryptoTest, UnitTest)
- [ ] Day 4: Tag all RLP tests (RLPTest, UnitTest)
- [ ] Day 5: Validate with `sbt testVM testCrypto testRLP`

### Week 2: Infrastructure Tagging
- [ ] Day 1-2: Tag network tests (NetworkTest)
- [ ] Day 3: Tag database tests (DatabaseTest)
- [ ] Day 4-5: Tag RPC tests (RPCTest)
- [ ] Validate with module-specific commands

### Week 3: Specialized Tests
- [ ] Day 1-2: Tag ledger/state tests (StateTest)
- [ ] Day 3: Tag consensus tests (ConsensusTest)
- [ ] Day 4: Tag sync tests (SyncTest)
- [ ] Day 5: Tag integration tests

### Week 4: Logging & Validation
- [ ] Day 1-2: Implement logback-test.xml
- [ ] Day 3: Run full test suite and validate timing
- [ ] Day 4: Fix any misclassified tests
- [ ] Day 5: Update documentation

---

## Tracking Progress

### Use TEST_CATEGORIZATION.csv
The CSV file contains all tests with:
- Current tags (extracted from code)
- Recommended tags (based on analysis)
- Notes for special cases

**Workflow:**
1. Open CSV in spreadsheet application
2. Add a "Status" column (TODO/IN_PROGRESS/DONE)
3. Mark tests as you complete tagging
4. Use filters to focus on priority areas

### Verification Script
Create a script to verify tagging completeness:
```bash
#!/bin/bash
# verify_tags.sh

echo "=== Test Tagging Verification ==="

# Count tests without tags
untagged=$(grep -r "it should\|test(" src/test --include="*.scala" | \
           grep -v "taggedAs" | wc -l)

echo "Tests without tags: $untagged"

# Count tests by tag type
echo ""
echo "Tests by tag:"
for tag in UnitTest IntegrationTest VMTest NetworkTest CryptoTest \
           DatabaseTest RPCTest StateTest ConsensusTest SyncTest; do
    count=$(grep -r "taggedAs.*$tag" src/test src/it --include="*.scala" | wc -l)
    echo "  $tag: $count"
done
```

---

## Quick Reference Commands

### Test Execution
```bash
# Essential (fast feedback)
sbt testEssential

# Standard (comprehensive)
sbt testStandard

# All tests
sbt testAll

# Module-specific
sbt "bytes / test"
sbt "crypto / test"
sbt "rlp / test"

# Tag-based filtering
sbt "testOnly -- -n VMTest"              # Include only VMTest
sbt "testOnly -- -l SlowTest"            # Exclude SlowTest
sbt "testOnly -- -n VMTest -l SlowTest"  # VM tests, exclude slow
```

### Log Access
```bash
# View VM test logs
tail -f target/test-logs/vm-tests.log

# View network test logs
tail -f target/test-logs/network-tests.log

# View all recent test logs
ls -lt target/test-logs/
```

---

## Success Criteria

### Test Tagging Complete When:
- ✅ All 328 test files have at least one type tag (UnitTest/IntegrationTest/BenchmarkTest)
- ✅ All test files have at least one system tag (VMTest/NetworkTest/etc.)
- ✅ `testEssential` completes in < 5 minutes
- ✅ `testStandard` completes in < 30 minutes
- ✅ `testComprehensive` completes in < 3 hours
- ✅ Module-specific commands run only relevant tests
- ✅ TEST_CATEGORIZATION.csv shows 100% tagged

### Logging Configuration Complete When:
- ✅ logback-test.xml exists with all functional system appenders
- ✅ Test runs produce isolated log files in target/test-logs/
- ✅ Logs are properly rotated (max 7 days retention)
- ✅ Console output remains clean and readable
- ✅ .gitignore excludes test-logs directory

---

## Next Steps

1. Review this action plan with the team
2. Assign ownership for each priority area
3. Set up the TEST_CATEGORIZATION.csv tracking spreadsheet
4. Begin with Priority 1 (Core Functionality Tests)
5. Run validation after each priority level
6. Configure isolated logging once tagging is 80%+ complete
7. Update documentation throughout the process

---

## Resources

- **Test Inventory:** TEST_INVENTORY.md (comprehensive overview)
- **Categorization:** TEST_CATEGORIZATION.csv (tracking spreadsheet)
- **Tag Definitions:** src/test/scala/com/chipprbots/ethereum/testing/Tags.scala
- **Build Config:** build.sbt (test configurations and commands)
- **ADR-017:** Test suite strategy and tier definitions
- **ADR-015:** Ethereum/tests integration strategy
