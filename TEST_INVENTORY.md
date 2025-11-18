# Fukuii Test Inventory and Categorization Action Plan

**Generated:** $(date)
**Repository:** chippr-robotics/fukuii
**Purpose:** Comprehensive inventory of all tests with categorization and tagging plan

---

## Executive Summary

This document provides a complete inventory of all tests in the Fukuii Ethereum Classic client, categorized by type and functional system. It serves as the foundation for systematic test tagging and isolated logging configuration.

### Key Metrics

- **Total Test Files:** 328
- **Tests with Tags:** ~519 (based on taggedAs usage)
- **Test Configurations:** 5 (Test, Integration, Benchmark, Evm, Rpc)
- **SBT Test Commands:** 16 pre-configured commands

---

## Test Organization by Type

### 1. Unit Tests (src/test)
**Location:** `./src/test/scala/com/chipprbots/ethereum/`
**Purpose:** Fast-executing tests for core business logic
**Execution Time:** < 100ms per test
**Count:** 234 test files

#### Key Test Suites:
- **Blockchain & Ledger:** Block validation, execution, rewards, state management
- **Virtual Machine:** Opcode execution, gas calculation, precompiled contracts
- **Network & P2P:** Peer management, handshakes, message encoding/decoding
- **Database & Storage:** Storage backends, caching, data persistence
- **JSON-RPC API:** All RPC endpoints (eth_*, net_*, debug_*, personal_*)
- **Cryptography:** ECDSA, hashing, encryption, key derivation
- **Data Structures:** RLP encoding/decoding, Merkle Patricia Trie

### 2. Integration Tests (src/it)
**Location:** `./src/it/scala/com/chipprbots/ethereum/`
**Purpose:** Component interaction validation
**Execution Time:** < 5 seconds per test
**Count:** 37 test files

#### Integration Test Categories:
- **Ethereum Compliance Tests:** ethereum/tests repository integration
  - BlockchainTestsSpec
  - GeneralStateTestsSpec
  - VMTestsSpec
  - TransactionTestsSpec
  - ExecutionSpecsStateTestsSpec
- **Database Integration:** RocksDB operations, iterator behavior
- **Network Integration:** E2E handshake, MESS protocol
- **Block Import:** Full block import pipeline testing

### 3. Benchmark Tests (src/benchmark)
**Location:** `./src/benchmark/scala/com/chipprbots/ethereum/`
**Purpose:** Performance measurement and validation
**Count:** 2 test files

#### Benchmarks:
- Merkle Patricia Tree speed tests

### 4. RPC Tests (src/rpcTest)
**Location:** `./src/rpcTest/scala/com/chipprbots/ethereum/rpcTest/`
**Purpose:** RPC endpoint integration testing
**Count:** 5 test files

#### RPC Test Components:
- RpcApiTests
- TestData
- TestContracts
- RpcTestConfig

### 5. EVM Tests (src/evmTest)
**Location:** `./src/evmTest/`
**Purpose:** EVM-specific validation
**Count:** 11 test files

### 6. Module Tests

#### bytes Module
**Location:** `./bytes/src/test/`
**Count:** 3 test files
- ByteUtilsSpec
- ByteStringUtilsTest

#### crypto Module
**Location:** `./crypto/src/test/`
**Count:** 12 test files
**Focus:** Cryptographic operations
- ECDSA signatures (ECDSASignatureSpec)
- ECIES encryption (ECIESCoderSpec)
- Hashing (Ripemd160Spec)
- Key derivation (ScryptSpec, Pbkdf2HMacSha256Spec)
- AES encryption (AesCtrSpec, AesCbcSpec)
- ZK-SNARKs (FpFieldSpec, BN128FpSpec)

#### rlp Module
**Location:** `./rlp/src/test/`
**Count:** 2 test files
- RLP encoding/decoding tests

#### scalanet Module
**Location:** `./scalanet/ut/src/` and `./scalanet/discovery/`
**Unit Tests:** 18 files
**Integration Tests:** 3 files
**Focus:** Network protocols, peer discovery, Kademlia DHT

---

## Tests by Functional System

This section categorizes tests by the functional system they validate, enabling targeted test execution and isolated logging configuration.

### 1. Virtual Machine (VM) & Execution
**Tag:** `VMTest`
**Test Count:** ~25 files

#### Test Files:
- db/dataSource/RocksDbDataSourceTest.scala
- vm/BlakeCompressionSpec.scala
- vm/CallOpcodesPostEip2929Spec.scala
- vm/CallOpcodesSpec.scala
- vm/CreateOpcodeSpec.scala
- vm/Eip3529Spec.scala
- vm/Eip3541Spec.scala
- vm/Eip3651Spec.scala
- vm/Eip3860Spec.scala
- vm/Eip6049Spec.scala
- vm/MemorySpec.scala
- vm/OpCodeFunSpec.scala
- vm/OpCodeGasSpec.scala
- vm/PrecompiledContractsSpec.scala
- vm/ProgramSpec.scala
- vm/Push0Spec.scala
- vm/SSTOREOpCodeGasPostConstantinopleSpec.scala
- vm/ShiftingOpCodeSpec.scala
- vm/StackSpec.scala
- vm/StaticCallOpcodeSpec.scala
- vm/VMSpec.scala

**Logging Configuration:**
```
logger.vm.name = "com.chipprbots.ethereum.vm"
logger.vm.level = DEBUG
logger.vm.appenderRef.vm.ref = VMAppender
```

### 2. Network & P2P Communication
**Tags:** `NetworkTest`, `IntegrationTest`
**Test Count:** ~35 files

#### Test Files:
- network/AsymmetricCipherKeyPairLoaderSpec.scala
- network/AuthHandshakerSpec.scala
- network/AuthInitiateMessageSpec.scala
- network/E2EHandshakeSpec.scala
- network/EtcPeerManagerSpec.scala
- network/KnownNodesManagerSpec.scala
- network/NodeParserSpec.scala
- network/PeerActorHandshakingSpec.scala
- network/PeerEventBusActorSpec.scala
- network/PeerManagerSpec.scala
- network/PeerScoreSpec.scala
- network/PeerStatisticsSpec.scala
- network/TimeSlotStatsSpec.scala
- network/discovery/PeerDiscoveryManagerSpec.scala
- network/discovery/Secp256k1SigAlgSpec.scala
- network/discovery/codecs/EIP8CodecsSpec.scala
- network/discovery/codecs/ENRCodecsSpec.scala
- network/discovery/codecs/RLPCodecsSpec.scala
- network/handshaker/EtcHandshakerSpec.scala
- network/p2p/FrameCodecSpec.scala
- network/p2p/MessageCodecSpec.scala
- network/p2p/MessageDecodersSpec.scala
- network/p2p/PeerActorSpec.scala
- network/p2p/messages/ETH65PlusMessagesSpec.scala
- network/p2p/messages/LegacyTransactionSpec.scala
- network/p2p/messages/MessagesSerializationSpec.scala
- network/p2p/messages/NodeDataSpec.scala
- network/p2p/messages/ReceiptsSpec.scala
- network/rlpx/MessageCompressionSpec.scala
- network/rlpx/RLPxConnectionHandlerSpec.scala

**Logging Configuration:**
```
logger.network.name = "com.chipprbots.ethereum.network"
logger.network.level = DEBUG
logger.network.appenderRef.network.ref = NetworkAppender

logger.scalanet.name = "com.chipprbots.scalanet"
logger.scalanet.level = DEBUG
logger.scalanet.appenderRef.scalanet.ref = NetworkAppender
```

### 3. Database & Storage
**Tags:** `DatabaseTest`, `IntegrationTest`
**Test Count:** ~15 files

#### Test Files:
- db/RockDbIteratorSpec.scala
- db/storage/AppStateStorageSpec.scala
- db/storage/BlockBodiesStorageSpec.scala
- db/storage/BlockFirstSeenStorageSpec.scala
- db/storage/BlockHeadersStorageSpec.scala
- db/storage/CachedNodeStorageSpec.scala
- db/storage/CachedReferenceCountedStorageSpec.scala
- db/storage/ReadOnlyNodeStorageSpec.scala
- db/storage/ReferenceCountNodeStorageSpec.scala
- db/storage/StateStorageSpec.scala

**Logging Configuration:**
```
logger.database.name = "com.chipprbots.ethereum.db"
logger.database.level = DEBUG
logger.database.appenderRef.database.ref = DatabaseAppender
```

### 4. JSON-RPC API
**Tags:** `RPCTest`, `UnitTest`
**Test Count:** ~30 files

#### Test Files:
- faucet/jsonrpc/FaucetRpcServiceSpec.scala
- faucet/jsonrpc/WalletServiceSpec.scala
- jsonrpc/CheckpointingJRCSpec.scala
- jsonrpc/CheckpointingServiceSpec.scala
- jsonrpc/DebugServiceSpec.scala
- jsonrpc/EthBlocksServiceSpec.scala
- jsonrpc/EthFilterServiceSpec.scala
- jsonrpc/EthInfoServiceSpec.scala
- jsonrpc/EthMiningServiceSpec.scala
- jsonrpc/EthProofServiceSpec.scala
- jsonrpc/EthTxServiceSpec.scala
- jsonrpc/EthUserServiceSpec.scala
- jsonrpc/ExpiringMapSpec.scala
- jsonrpc/FilterManagerSpec.scala
- jsonrpc/FukuiiJRCSpec.scala
- jsonrpc/FukuiiServiceSpec.scala
- jsonrpc/JRCMatchers.scala
- jsonrpc/JsonRpcControllerEthLegacyTransactionSpec.scala
- jsonrpc/JsonRpcControllerEthSpec.scala
- jsonrpc/JsonRpcControllerFixture.scala
- jsonrpc/JsonRpcControllerPersonalSpec.scala
- jsonrpc/JsonRpcControllerSpec.scala
- jsonrpc/JsonRpcControllerTestSupport.scala
- jsonrpc/NetServiceSpec.scala
- jsonrpc/PersonalServiceSpec.scala
- jsonrpc/ProofServiceDummy.scala
- jsonrpc/QAServiceSpec.scala
- jsonrpc/QaJRCSpec.scala
- jsonrpc/server/http/JsonRpcHttpServerSpec.scala

**Logging Configuration:**
```
logger.rpc.name = "com.chipprbots.ethereum.jsonrpc"
logger.rpc.level = DEBUG
logger.rpc.appenderRef.rpc.ref = RPCAppender
```

### 5. Blockchain & Consensus
**Tags:** `ConsensusTest`, `UnitTest`, `SlowTest`
**Test Count:** ~20 files

#### Test Files:
- consensus/ConsensusAdapterSpec.scala
- consensus/ConsensusImplSpec.scala
- consensus/blocks/BlockGeneratorSpec.scala
- consensus/blocks/CheckpointBlockGeneratorSpec.scala
- consensus/mess/MESSIntegrationSpec.scala
- consensus/mess/MESScorerSpec.scala
- consensus/mining/MiningConfigs.scala
- consensus/mining/MiningSpec.scala
- consensus/pow/EthashUtilsSpec.scala
- consensus/pow/KeccakCalculationSpec.scala
- consensus/pow/KeccakDataUtils.scala
- consensus/pow/MinerSpecSetup.scala
- consensus/pow/PoWMiningCoordinatorSpec.scala
- consensus/pow/PoWMiningSpec.scala
- consensus/pow/RestrictedEthashSignerSpec.scala
- consensus/pow/miners/EthashMinerSpec.scala
- consensus/pow/miners/KeccakMinerSpec.scala
- consensus/pow/miners/MockedMinerSpec.scala
- consensus/pow/validators/EthashBlockHeaderValidatorSpec.scala
- consensus/pow/validators/KeccakBlockHeaderValidatorSpec.scala
- consensus/pow/validators/PoWBlockHeaderValidatorSpec.scala
- consensus/pow/validators/RestrictedEthashBlockHeaderValidatorSpec.scala
- consensus/pow/validators/StdOmmersValidatorSpec.scala
- consensus/validators/BlockWithCheckpointHeaderValidatorSpec.scala
- consensus/validators/std/StdBlockValidatorSpec.scala
- consensus/validators/std/StdSignedLegacyTransactionValidatorSpec.scala

**Logging Configuration:**
```
logger.consensus.name = "com.chipprbots.ethereum.consensus"
logger.consensus.level = DEBUG
logger.consensus.appenderRef.consensus.ref = ConsensusAppender

logger.mining.name = "com.chipprbots.ethereum.mining"
logger.mining.level = DEBUG
logger.mining.appenderRef.mining.ref = ConsensusAppender
```

### 6. Ledger & State Management
**Tags:** `StateTest`, `UnitTest`
**Test Count:** ~15 files

#### Test Files:
- ledger/BlockExecutionSpec.scala
- ledger/BlockPreparatorSpec.scala
- ledger/BlockQueueSpec.scala
- ledger/BlockRewardCalculatorSpec.scala
- ledger/BlockRewardSpec.scala
- ledger/BlockValidationSpec.scala
- ledger/BloomFilterSpec.scala
- ledger/BranchResolutionSpec.scala
- ledger/DeleteAccountsSpec.scala
- ledger/DeleteTouchedAccountsSpec.scala
- ledger/InMemorySimpleMapProxySpec.scala
- ledger/InMemoryWorldStateProxySpec.scala
- ledger/StxLedgerSpec.scala

**Logging Configuration:**
```
logger.ledger.name = "com.chipprbots.ethereum.ledger"
logger.ledger.level = DEBUG
logger.ledger.appenderRef.ledger.ref = LedgerAppender
```

### 7. Cryptography
**Tags:** `CryptoTest`, `UnitTest`
**Test Count:** 12 files

#### Test Files:
- ./crypto/src/test/scala/com/chipprbots/ethereum/testing/Tags.scala
- AesCbcSpec.scala
- AesCtrSpec.scala
- ECDSASignatureSpec.scala
- ECIESCoderSpec.scala
- Generators.scala
- Pbkdf2HMacSha256Spec.scala
- Ripemd160Spec.scala
- ScryptSpec.scala
- SecureRandomBuilder.scala
- zksnarks/BN128FpSpec.scala
- zksnarks/FpFieldSpec.scala

**Logging Configuration:**
```
logger.crypto.name = "com.chipprbots.ethereum.crypto"
logger.crypto.level = DEBUG
logger.crypto.appenderRef.crypto.ref = CryptoAppender
```

### 8. Data Structures (RLP, MPT)
**Tags:** `RLPTest`, `MPTTest`, `UnitTest`
**Test Count:** ~5 files

#### Test Files:
- RLP encoding/decoding (rlp module)
- Merkle Patricia Trie operations (mpt)
- HexPrefix encoding

**Logging Configuration:**
```
logger.rlp.name = "com.chipprbots.ethereum.rlp"
logger.rlp.level = DEBUG
logger.rlp.appenderRef.rlp.ref = DataStructureAppender

logger.mpt.name = "com.chipprbots.ethereum.mpt"
logger.mpt.level = DEBUG
logger.mpt.appenderRef.mpt.ref = DataStructureAppender
```

### 9. Synchronization
**Tags:** `SyncTest`, `IntegrationTest`, `SlowTest`
**Test Count:** ~10 files

**Logging Configuration:**
```
logger.sync.name = "com.chipprbots.ethereum.blockchain.sync"
logger.sync.level = DEBUG
logger.sync.appenderRef.sync.ref = SyncAppender
```

### 10. Ethereum Compliance Tests
**Tags:** `EthereumTest`, `IntegrationTest`
**Test Count:** 8 files in src/it

#### Test Files:
- BlockchainTestsSpec
- ComprehensiveBlockchainTestsSpec
- GeneralStateTestsSpec
- VMTestsSpec
- TransactionTestsSpec
- ExecutionSpecsStateTestsSpec
- EthereumTestsSpec
- EthereumTestExecutor/Adapter

**Logging Configuration:**
```
logger.ethtest.name = "com.chipprbots.ethereum.ethtest"
logger.ethtest.level = INFO
logger.ethtest.appenderRef.ethtest.ref = EthereumTestAppender
```

---

## Existing Tag Definitions

The repository already has a comprehensive tagging system defined in `src/test/scala/com/chipprbots/ethereum/testing/Tags.scala`:

### Tier-Based Tags (ADR-017)
- **UnitTest** - Fast tests (< 100ms) for core logic
- **FastTest** - High value-to-time ratio tests
- **IntegrationTest** - Component interaction tests (< 5s)
- **SlowTest** - Necessary but slow tests (> 100ms, < 5s)
- **EthereumTest** - ethereum/tests compliance validation
- **BenchmarkTest** - Performance measurement
- **StressTest** - Long-running load tests

### Module-Specific Tags
- **CryptoTest** - Cryptographic operations
- **RLPTest** - RLP encoding/decoding
- **VMTest** - Virtual machine operations
- **NetworkTest** - P2P communication
- **MPTTest** - Merkle Patricia Trie
- **StateTest** - Blockchain state
- **ConsensusTest** - Consensus mechanisms
- **RPCTest** - JSON-RPC API
- **DatabaseTest** - Database operations
- **SyncTest** - Synchronization

### Fork-Specific Tags
- HomesteadTest, TangerineWhistleTest, SpuriousDragonTest
- ByzantiumTest, ConstantinopleTest, IstanbulTest, BerlinTest
- AtlantisTest, AghartaTest, PhoenixTest, MagnetoTest, MystiqueTest, SpiralTest

### Environment-Specific Tags
- **MainNet** - Tests requiring MainNet connection
- **PrivNet** - Private test network tests
- **PrivNetNoMining** - Private network without mining

### Special Tags
- **FlakyTest** - Known intermittent failures
- **DisabledTest** - Temporarily disabled
- **ManualTest** - Requires manual verification

---

## Test Execution Strategy

### Pre-configured SBT Commands

#### Comprehensive Testing
```bash
# Run all tests (Tier 3: < 3 hours)
sbt testAll
sbt testComprehensive

# Run with coverage
sbt testCoverage
```

#### Targeted Testing by Tier
```bash
# Tier 1: Essential tests (< 5 minutes)
sbt testEssential
# Excludes: SlowTest, IntegrationTest, SyncTest

# Tier 2: Standard tests (< 30 minutes)
sbt testStandard
# Excludes: BenchmarkTest, EthereumTest
```

#### Module-Specific Testing
```bash
sbt testCrypto      # CryptoTest tagged tests
sbt testVM          # VMTest tagged tests
sbt testNetwork     # NetworkTest tagged tests
sbt testDatabase    # DatabaseTest tagged tests
sbt testRLP         # RLPTest tagged tests
sbt testMPT         # MPTTest tagged tests
sbt testEthereum    # EthereumTest tagged tests
```

#### Module-Level Testing
```bash
sbt "bytes / test"
sbt "crypto / test"
sbt "rlp / test"
sbt "test"                    # Main project unit tests
sbt "IntegrationTest / test"  # Integration tests
sbt "Benchmark / test"        # Benchmarks
sbt "Evm / test"              # EVM tests
sbt "Rpc / test"              # RPC tests
```

#### Custom Tag Filtering
```bash
# Include only specific tags
sbt "testOnly -- -n VMTest"
sbt "testOnly -- -n CryptoTest -n RLPTest"

# Exclude specific tags
sbt "testOnly -- -l SlowTest"
sbt "testOnly -- -l IntegrationTest -l SlowTest"
```

---

## Action Plan for Complete Test Tagging

### Phase 1: Assessment (Current Status)
- [x] Inventory all test files (328 total)
- [x] Document existing tag definitions
- [x] Identify tagged tests (~519 uses of taggedAs)
- [ ] Create detailed spreadsheet of all tests with current tags

### Phase 2: Systematic Tagging
Priority order for untagged tests:

1. **High Priority - Core Functionality**
   - [ ] All VM tests → `VMTest`, `UnitTest`
   - [ ] All crypto tests → `CryptoTest`, `UnitTest`
   - [ ] All RLP tests → `RLPTest`, `UnitTest`
   - [ ] All network tests → `NetworkTest`, appropriate tier tag

2. **Medium Priority - Infrastructure**
   - [ ] Database tests → `DatabaseTest`, appropriate tier tag
   - [ ] RPC tests → `RPCTest`, `UnitTest`
   - [ ] Ledger/state tests → `StateTest`, `UnitTest`

3. **Lower Priority - Specialized**
   - [ ] Benchmark tests → `BenchmarkTest`
   - [ ] Integration tests → `IntegrationTest`, system tag
   - [ ] Ethereum compliance tests → `EthereumTest`, `IntegrationTest`

### Phase 3: Validation
- [ ] Run `testEssential` and verify completion time < 5 minutes
- [ ] Run `testStandard` and verify completion time < 30 minutes
- [ ] Run module-specific commands and verify correct test selection
- [ ] Document any tests that need reclassification

### Phase 4: Logging Configuration
- [ ] Create logback-test.xml with isolated appenders per functional system
- [ ] Configure log levels for each system
- [ ] Add file-based logging with rotation
- [ ] Document logging strategy

---

## Isolated Logging Recommendations

### Logback Configuration Structure

Create `src/test/resources/logback-test.xml`:

```xml
<configuration>
  <!-- Console appender for general output -->
  <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
    <encoder>
      <pattern>%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n</pattern>
    </encoder>
  </appender>

  <!-- VM tests isolated logging -->
  <appender name="VMAppender" class="ch.qos.logback.core.rolling.RollingFileAppender">
    <file>target/test-logs/vm-tests.log</file>
    <encoder>
      <pattern>%d{HH:mm:ss.SSS} %-5level %logger{36} - %msg%n</pattern>
    </encoder>
    <rollingPolicy class="ch.qos.logback.core.rolling.TimeBasedRollingPolicy">
      <fileNamePattern>target/test-logs/vm-tests.%d{yyyy-MM-dd}.log</fileNamePattern>
      <maxHistory>7</maxHistory>
    </rollingPolicy>
  </appender>

  <!-- Network tests isolated logging -->
  <appender name="NetworkAppender" class="ch.qos.logback.core.rolling.RollingFileAppender">
    <file>target/test-logs/network-tests.log</file>
    <encoder>
      <pattern>%d{HH:mm:ss.SSS} %-5level %logger{36} - %msg%n</pattern>
    </encoder>
    <rollingPolicy class="ch.qos.logback.core.rolling.TimeBasedRollingPolicy">
      <fileNamePattern>target/test-logs/network-tests.%d{yyyy-MM-dd}.log</fileNamePattern>
      <maxHistory>7</maxHistory>
    </rollingPolicy>
  </appender>

  <!-- Database tests isolated logging -->
  <appender name="DatabaseAppender" class="ch.qos.logback.core.rolling.RollingFileAppender">
    <file>target/test-logs/database-tests.log</file>
    <encoder>
      <pattern>%d{HH:mm:ss.SSS} %-5level %logger{36} - %msg%n</pattern>
    </encoder>
    <rollingPolicy class="ch.qos.logback.core.rolling.TimeBasedRollingPolicy">
      <fileNamePattern>target/test-logs/database-tests.%d{yyyy-MM-dd}.log</fileNamePattern>
      <maxHistory>7</maxHistory>
    </rollingPolicy>
  </appender>

  <!-- RPC tests isolated logging -->
  <appender name="RPCAppender" class="ch.qos.logback.core.rolling.RollingFileAppender">
    <file>target/test-logs/rpc-tests.log</file>
    <encoder>
      <pattern>%d{HH:mm:ss.SSS} %-5level %logger{36} - %msg%n</pattern>
    </encoder>
    <rollingPolicy class="ch.qos.logback.core.rolling.TimeBasedRollingPolicy">
      <fileNamePattern>target/test-logs/rpc-tests.%d{yyyy-MM-dd}.log</fileNamePattern>
      <maxHistory>7</maxHistory>
    </rollingPolicy>
  </appender>

  <!-- Consensus tests isolated logging -->
  <appender name="ConsensusAppender" class="ch.qos.logback.core.rolling.RollingFileAppender">
    <file>target/test-logs/consensus-tests.log</file>
    <encoder>
      <pattern>%d{HH:mm:ss.SSS} %-5level %logger{36} - %msg%n</pattern>
    </encoder>
    <rollingPolicy class="ch.qos.logback.core.rolling.TimeBasedRollingPolicy">
      <fileNamePattern>target/test-logs/consensus-tests.%d{yyyy-MM-dd}.log</fileNamePattern>
      <maxHistory>7</maxHistory>
    </rollingPolicy>
  </appender>

  <!-- Ledger tests isolated logging -->
  <appender name="LedgerAppender" class="ch.qos.logback.core.rolling.RollingFileAppender">
    <file>target/test-logs/ledger-tests.log</file>
    <encoder>
      <pattern>%d{HH:mm:ss.SSS} %-5level %logger{36} - %msg%n</pattern>
    </encoder>
    <rollingPolicy class="ch.qos.logback.core.rolling.TimeBasedRollingPolicy">
      <fileNamePattern>target/test-logs/ledger-tests.%d{yyyy-MM-dd}.log</fileNamePattern>
      <maxHistory>7</maxHistory>
    </rollingPolicy>
  </appender>

  <!-- Crypto tests isolated logging -->
  <appender name="CryptoAppender" class="ch.qos.logback.core.rolling.RollingFileAppender">
    <file>target/test-logs/crypto-tests.log</file>
    <encoder>
      <pattern>%d{HH:mm:ss.SSS} %-5level %logger{36} - %msg%n</pattern>
    </encoder>
    <rollingPolicy class="ch.qos.logback.core.rolling.TimeBasedRollingPolicy">
      <fileNamePattern>target/test-logs/crypto-tests.%d{yyyy-MM-dd}.log</fileNamePattern>
      <maxHistory>7</maxHistory>
    </rollingPolicy>
  </appender>

  <!-- Data structure tests isolated logging -->
  <appender name="DataStructureAppender" class="ch.qos.logback.core.rolling.RollingFileAppender">
    <file>target/test-logs/datastructure-tests.log</file>
    <encoder>
      <pattern>%d{HH:mm:ss.SSS} %-5level %logger{36} - %msg%n</pattern>
    </encoder>
    <rollingPolicy class="ch.qos.logback.core.rolling.TimeBasedRollingPolicy">
      <fileNamePattern>target/test-logs/datastructure-tests.%d{yyyy-MM-dd}.log</fileNamePattern>
      <maxHistory>7</maxHistory>
    </rollingPolicy>
  </appender>

  <!-- Sync tests isolated logging -->
  <appender name="SyncAppender" class="ch.qos.logback.core.rolling.RollingFileAppender">
    <file>target/test-logs/sync-tests.log</file>
    <encoder>
      <pattern>%d{HH:mm:ss.SSS} %-5level %logger{36} - %msg%n</pattern>
    </encoder>
    <rollingPolicy class="ch.qos.logback.core.rolling.TimeBasedRollingPolicy">
      <fileNamePattern>target/test-logs/sync-tests.%d{yyyy-MM-dd}.log</fileNamePattern>
      <maxHistory>7</maxHistory>
    </rollingPolicy>
  </appender>

  <!-- Ethereum compliance tests isolated logging -->
  <appender name="EthereumTestAppender" class="ch.qos.logback.core.rolling.RollingFileAppender">
    <file>target/test-logs/ethereum-tests.log</file>
    <encoder>
      <pattern>%d{HH:mm:ss.SSS} %-5level %logger{36} - %msg%n</pattern>
    </encoder>
    <rollingPolicy class="ch.qos.logback.core.rolling.TimeBasedRollingPolicy">
      <fileNamePattern>target/test-logs/ethereum-tests.%d{yyyy-MM-dd}.log</fileNamePattern>
      <maxHistory>7</maxHistory>
    </rollingPolicy>
  </appender>

  <!-- Logger configurations per functional system -->
  <logger name="com.chipprbots.ethereum.vm" level="DEBUG" additivity="false">
    <appender-ref ref="VMAppender" />
    <appender-ref ref="CONSOLE" />
  </logger>

  <logger name="com.chipprbots.ethereum.network" level="DEBUG" additivity="false">
    <appender-ref ref="NetworkAppender" />
    <appender-ref ref="CONSOLE" />
  </logger>

  <logger name="com.chipprbots.scalanet" level="DEBUG" additivity="false">
    <appender-ref ref="NetworkAppender" />
    <appender-ref ref="CONSOLE" />
  </logger>

  <logger name="com.chipprbots.ethereum.db" level="DEBUG" additivity="false">
    <appender-ref ref="DatabaseAppender" />
    <appender-ref ref="CONSOLE" />
  </logger>

  <logger name="com.chipprbots.ethereum.jsonrpc" level="DEBUG" additivity="false">
    <appender-ref ref="RPCAppender" />
    <appender-ref ref="CONSOLE" />
  </logger>

  <logger name="com.chipprbots.ethereum.consensus" level="DEBUG" additivity="false">
    <appender-ref ref="ConsensusAppender" />
    <appender-ref ref="CONSOLE" />
  </logger>

  <logger name="com.chipprbots.ethereum.mining" level="DEBUG" additivity="false">
    <appender-ref ref="ConsensusAppender" />
    <appender-ref ref="CONSOLE" />
  </logger>

  <logger name="com.chipprbots.ethereum.ledger" level="DEBUG" additivity="false">
    <appender-ref ref="LedgerAppender" />
    <appender-ref ref="CONSOLE" />
  </logger>

  <logger name="com.chipprbots.ethereum.crypto" level="DEBUG" additivity="false">
    <appender-ref ref="CryptoAppender" />
    <appender-ref ref="CONSOLE" />
  </logger>

  <logger name="com.chipprbots.ethereum.rlp" level="DEBUG" additivity="false">
    <appender-ref ref="DataStructureAppender" />
    <appender-ref ref="CONSOLE" />
  </logger>

  <logger name="com.chipprbots.ethereum.mpt" level="DEBUG" additivity="false">
    <appender-ref ref="DataStructureAppender" />
    <appender-ref ref="CONSOLE" />
  </logger>

  <logger name="com.chipprbots.ethereum.blockchain.sync" level="DEBUG" additivity="false">
    <appender-ref ref="SyncAppender" />
    <appender-ref ref="CONSOLE" />
  </logger>

  <logger name="com.chipprbots.ethereum.ethtest" level="INFO" additivity="false">
    <appender-ref ref="EthereumTestAppender" />
    <appender-ref ref="CONSOLE" />
  </logger>

  <!-- Root logger -->
  <root level="INFO">
    <appender-ref ref="CONSOLE" />
  </root>
</configuration>
```

### Benefits of Isolated Logging
1. **Easy Debugging:** Logs separated by functional system
2. **Performance Analysis:** Identify slow tests by examining system-specific logs
3. **CI/CD Integration:** Can archive logs per system for historical analysis
4. **Parallel Test Execution:** No log interleaving between functional systems
5. **Troubleshooting:** Quickly locate issues in specific subsystems

---

## Quick Reference

### Test a Specific Functional System
```bash
# VM tests only
sbt "testOnly -- -n VMTest"

# Network tests only
sbt "testOnly -- -n NetworkTest"

# Database tests only
sbt "testOnly -- -n DatabaseTest"

# RPC tests only
sbt "testOnly -- -n RPCTest"

# Crypto tests only (module)
sbt "crypto / test"
```

### Test by Execution Time
```bash
# Fast tests only (< 5 minutes)
sbt testEssential

# Standard tests (< 30 minutes)
sbt testStandard

# All tests (< 3 hours)
sbt testComprehensive
```

### Test Specific Module
```bash
sbt "bytes / test"
sbt "crypto / test"
sbt "rlp / test"
sbt "scalanet / test"
sbt "scalanetDiscovery / test"
```

---

## Appendix: Full Test File Listing

### Unit Tests (src/test)
./src/test/scala/com/chipprbots/scalanet/peergroup/NettyFutureUtilsSpec.scala
BootstrapDownloadSpec.scala
blockchain/data/BootstrapCheckpointLoaderSpec.scala
blockchain/data/BootstrapCheckpointSpec.scala
blockchain/sync/BlockBroadcastSpec.scala
blockchain/sync/BlockchainHostActorSpec.scala
blockchain/sync/CacheBasedBlacklistSpec.scala
blockchain/sync/FastSyncSpec.scala
blockchain/sync/LoadableBloomFilterSpec.scala
blockchain/sync/PeersClientSpec.scala
blockchain/sync/PivotBlockSelectorSpec.scala
blockchain/sync/RetryStrategySpec.scala
blockchain/sync/SchedulerStateSpec.scala
blockchain/sync/StateStorageActorSpec.scala
blockchain/sync/StateSyncSpec.scala
blockchain/sync/SyncControllerSpec.scala
blockchain/sync/SyncStateDownloaderStateSpec.scala
blockchain/sync/SyncStateSchedulerSpec.scala
blockchain/sync/fast/FastSyncBranchResolverActorSpec.scala
blockchain/sync/fast/FastSyncBranchResolverSpec.scala
blockchain/sync/fast/HeaderSkeletonSpec.scala
blockchain/sync/regular/BlockFetcherSpec.scala
blockchain/sync/regular/BlockFetcherStateSpec.scala
blockchain/sync/regular/RegularSyncSpec.scala
cli/CliCommandsSpec.scala
consensus/ConsensusAdapterSpec.scala
consensus/ConsensusImplSpec.scala
consensus/blocks/BlockGeneratorSpec.scala
consensus/blocks/CheckpointBlockGeneratorSpec.scala
consensus/mess/MESScorerSpec.scala
consensus/mining/MiningSpec.scala
consensus/pow/EthashUtilsSpec.scala
consensus/pow/KeccakCalculationSpec.scala
consensus/pow/PoWMiningCoordinatorSpec.scala
consensus/pow/PoWMiningSpec.scala
consensus/pow/RestrictedEthashSignerSpec.scala
consensus/pow/miners/EthashMinerSpec.scala
consensus/pow/miners/KeccakMinerSpec.scala
consensus/pow/miners/MockedMinerSpec.scala
consensus/pow/validators/EthashBlockHeaderValidatorSpec.scala
consensus/pow/validators/KeccakBlockHeaderValidatorSpec.scala
consensus/pow/validators/PoWBlockHeaderValidatorSpec.scala
consensus/pow/validators/RestrictedEthashBlockHeaderValidatorSpec.scala
consensus/pow/validators/StdOmmersValidatorSpec.scala
consensus/validators/BlockWithCheckpointHeaderValidatorSpec.scala
consensus/validators/std/StdBlockValidatorSpec.scala
consensus/validators/std/StdSignedLegacyTransactionValidatorSpec.scala
db/dataSource/RocksDbDataSourceTest.scala
db/storage/AppStateStorageSpec.scala
db/storage/BlockBodiesStorageSpec.scala
db/storage/BlockFirstSeenStorageSpec.scala
db/storage/BlockHeadersStorageSpec.scala
db/storage/CachedNodeStorageSpec.scala
db/storage/CachedReferenceCountedStorageSpec.scala
db/storage/ReadOnlyNodeStorageSpec.scala
db/storage/ReferenceCountNodeStorageSpec.scala
db/storage/StateStorageSpec.scala
domain/ArbitraryIntegerMptSpec.scala
domain/BigIntSerializationSpec.scala
domain/BlockHeaderSpec.scala
domain/BlockSpec.scala
domain/BlockchainReaderSpec.scala
domain/BlockchainSpec.scala
domain/ChainWeightSpec.scala
domain/SignedLegacyTransactionSpec.scala
domain/SignedTransactionWithAccessListSpec.scala
domain/TransactionSpec.scala
domain/UInt256Spec.scala
extvm/MessageHandlerSpec.scala
extvm/VMClientSpec.scala
extvm/VMServerSpec.scala
extvm/WorldSpec.scala
faucet/FaucetHandlerSpec.scala
faucet/jsonrpc/FaucetRpcServiceSpec.scala
faucet/jsonrpc/WalletServiceSpec.scala
forkid/ForkIdSpec.scala
forkid/ForkIdValidatorSpec.scala
jsonrpc/CheckpointingJRCSpec.scala
jsonrpc/CheckpointingServiceSpec.scala
jsonrpc/DebugServiceSpec.scala
jsonrpc/EthBlocksServiceSpec.scala
jsonrpc/EthFilterServiceSpec.scala
jsonrpc/EthInfoServiceSpec.scala
jsonrpc/EthMiningServiceSpec.scala
jsonrpc/EthProofServiceSpec.scala
jsonrpc/EthTxServiceSpec.scala
jsonrpc/EthUserServiceSpec.scala
jsonrpc/ExpiringMapSpec.scala
jsonrpc/FilterManagerSpec.scala
jsonrpc/FukuiiJRCSpec.scala
jsonrpc/FukuiiServiceSpec.scala
jsonrpc/JsonRpcControllerEthLegacyTransactionSpec.scala
jsonrpc/JsonRpcControllerEthSpec.scala
jsonrpc/JsonRpcControllerPersonalSpec.scala
jsonrpc/JsonRpcControllerSpec.scala
jsonrpc/NetServiceSpec.scala
jsonrpc/PersonalServiceSpec.scala
jsonrpc/QAServiceSpec.scala
jsonrpc/QaJRCSpec.scala
jsonrpc/server/http/JsonRpcHttpServerSpec.scala
keystore/EncryptedKeySpec.scala
keystore/KeyStoreImplSpec.scala
ledger/BlockExecutionSpec.scala
ledger/BlockPreparatorSpec.scala
ledger/BlockQueueSpec.scala
ledger/BlockRewardCalculatorSpec.scala
ledger/BlockRewardSpec.scala
ledger/BlockValidationSpec.scala
ledger/BloomFilterSpec.scala
ledger/BranchResolutionSpec.scala
ledger/DeleteAccountsSpec.scala
ledger/DeleteTouchedAccountsSpec.scala
ledger/InMemorySimpleMapProxySpec.scala
ledger/InMemoryWorldStateProxySpec.scala
ledger/StxLedgerSpec.scala
network/AsymmetricCipherKeyPairLoaderSpec.scala
network/AuthHandshakerSpec.scala
network/AuthInitiateMessageSpec.scala
network/EtcPeerManagerSpec.scala
network/KnownNodesManagerSpec.scala
network/NodeParserSpec.scala
network/PeerActorHandshakingSpec.scala
network/PeerEventBusActorSpec.scala
network/PeerManagerSpec.scala
network/PeerScoreSpec.scala
network/PeerStatisticsSpec.scala
network/TimeSlotStatsSpec.scala
network/discovery/PeerDiscoveryManagerSpec.scala
network/discovery/Secp256k1SigAlgSpec.scala
network/discovery/codecs/EIP8CodecsSpec.scala
network/discovery/codecs/ENRCodecsSpec.scala
network/discovery/codecs/RLPCodecsSpec.scala
network/handshaker/EtcHandshakerSpec.scala
network/p2p/FrameCodecSpec.scala
network/p2p/MessageCodecSpec.scala
network/p2p/MessageDecodersSpec.scala
network/p2p/PeerActorSpec.scala
network/p2p/messages/ETH65PlusMessagesSpec.scala
network/p2p/messages/LegacyTransactionSpec.scala
network/p2p/messages/MessagesSerializationSpec.scala
network/p2p/messages/NodeDataSpec.scala
network/p2p/messages/ReceiptsSpec.scala
network/rlpx/MessageCompressionSpec.scala
network/rlpx/RLPxConnectionHandlerSpec.scala
nodebuilder/IORuntimeInitializationSpec.scala
nodebuilder/PortForwardingBuilderSpec.scala
ommers/OmmersPoolSpec.scala
rlp/RLPSpec.scala
security/SSLContextFactorySpec.scala
testing/KPIBaselinesSpec.scala
transactions/LegacyTransactionHistoryServiceSpec.scala
transactions/PendingTransactionsManagerSpec.scala
utils/ConfigSpec.scala
utils/ConfigUtilsSpec.scala
utils/VersionInfoSpec.scala
vm/BlakeCompressionSpec.scala
vm/CallOpcodesPostEip2929Spec.scala
vm/CallOpcodesSpec.scala
vm/CreateOpcodeSpec.scala
vm/Eip3529Spec.scala
vm/Eip3541Spec.scala
vm/Eip3651Spec.scala
vm/Eip3860Spec.scala
vm/Eip6049Spec.scala
vm/MemorySpec.scala
vm/OpCodeFunSpec.scala
vm/OpCodeGasSpec.scala
vm/PrecompiledContractsSpec.scala
vm/ProgramSpec.scala
vm/Push0Spec.scala
vm/SSTOREOpCodeGasPostConstantinopleSpec.scala
vm/ShiftingOpCodeSpec.scala
vm/StackSpec.scala
vm/StaticCallOpcodeSpec.scala
vm/VMSpec.scala

### Integration Tests (src/it)
consensus/mess/MESSIntegrationSpec.scala
db/RockDbIteratorSpec.scala
ethtest/BlockchainTestsSpec.scala
ethtest/ComprehensiveBlockchainTestsSpec.scala
ethtest/EthereumTestsSpec.scala
ethtest/ExecutionSpecsStateTestsSpec.scala
ethtest/GasCalculationIssuesSpec.scala
ethtest/GeneralStateTestsSpec.scala
ethtest/SimpleEthereumTest.scala
ethtest/TransactionTestsSpec.scala
ethtest/VMTestsSpec.scala
ledger/BlockImporterItSpec.scala
network/E2EHandshakeSpec.scala
sync/E2EFastSyncSpec.scala
sync/E2EStateTestSpec.scala
sync/E2ESyncSpec.scala
sync/FastSyncItSpec.scala
sync/RegularSyncItSpec.scala
sync/util/SyncCommonItSpec.scala
txExecTest/ContractTest.scala
txExecTest/ECIP1017Test.scala
txExecTest/ForksTest.scala

### Module Tests

#### bytes Module
testing/Tags.scala
utils/ByteStringUtilsTest.scala
utils/ByteUtilsSpec.scala

#### crypto Module
crypto/AesCbcSpec.scala
crypto/AesCtrSpec.scala
crypto/ECDSASignatureSpec.scala
crypto/ECIESCoderSpec.scala
crypto/Generators.scala
crypto/Pbkdf2HMacSha256Spec.scala
crypto/Ripemd160Spec.scala
crypto/ScryptSpec.scala
crypto/SecureRandomBuilder.scala
crypto/zksnarks/BN128FpSpec.scala
crypto/zksnarks/FpFieldSpec.scala
testing/Tags.scala

#### rlp Module
rlp/RLPSuite.scala
testing/Tags.scala

#### scalanet Module
com/chipprbots/scalanet/peergroup/udp/StaticUDPPeerGroupSpec.scala
com/chipprbots/scalanet/discovery/crypto/SigAlgSpec.scala
com/chipprbots/scalanet/discovery/ethereum/EthereumNodeRecordSpec.scala
com/chipprbots/scalanet/discovery/ethereum/NodeSpec.scala
com/chipprbots/scalanet/discovery/ethereum/v4/DiscoveryNetworkSpec.scala
com/chipprbots/scalanet/discovery/ethereum/v4/DiscoveryServiceSpec.scala
com/chipprbots/scalanet/discovery/ethereum/v4/KBucketsWithSubnetLimitsSpec.scala
com/chipprbots/scalanet/discovery/ethereum/v4/PacketSpec.scala
com/chipprbots/scalanet/discovery/ethereum/v4/mocks/MockPeerGroup.scala
com/chipprbots/scalanet/discovery/ethereum/v4/mocks/MockSigAlg.scala
com/chipprbots/scalanet/discovery/hash/Keccak256Spec.scala
com/chipprbots/scalanet/kademlia/Generators.scala
com/chipprbots/scalanet/kademlia/KBucketsSpec.scala
com/chipprbots/scalanet/kademlia/KNetworkRequestProcessing.scala
com/chipprbots/scalanet/kademlia/KNetworkSpec.scala
com/chipprbots/scalanet/kademlia/KRouterSpec.scala
com/chipprbots/scalanet/kademlia/TimeSetSpec.scala
com/chipprbots/scalanet/kademlia/XorOrderingSpec.scala
com/chipprbots/scalanet/kademlia/XorSpec.scala
com/chipprbots/scalanet/discovery/ethereum/v4/DiscoveryKademliaIntegrationSpec.scala
com/chipprbots/scalanet/kademlia/KRouterKademliaIntegrationSpec.scala
com/chipprbots/scalanet/kademlia/KademliaIntegrationSpec.scala

---

## Conclusion

This inventory provides a comprehensive view of the Fukuii test suite. The existing tagging infrastructure (ADR-017) is robust and well-designed. The primary task is to ensure all tests are properly tagged according to their type and functional system, enabling:

1. **Selective test execution** based on time constraints
2. **Module-specific testing** for focused development
3. **Isolated logging** for easier debugging and analysis
4. **CI/CD optimization** through targeted test runs

### Next Steps
1. Apply tags systematically to untagged tests
2. Implement isolated logging configuration
3. Validate test execution times for each tier
4. Document any tests requiring reclassification
5. Create automated verification for tag consistency

