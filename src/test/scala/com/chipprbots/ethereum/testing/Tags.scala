package com.chipprbots.ethereum.testing

import org.scalatest.Tag

/** ScalaTest tags for categorizing tests based on ADR-017 test suite strategy.
  *
  * These tags enable selective test execution for different CI/CD scenarios:
  *   - Essential tests (Tier 1): Fast feedback on core functionality (< 5 minutes)
  *   - Standard tests (Tier 2): Comprehensive validation (< 30 minutes)
  *   - Comprehensive tests (Tier 3): Full ethereum/tests compliance (< 3 hours)
  *
  * Usage:
  * {{{
  * // Tag a single test
  * test("should validate block header") {
  *   taggedAs(UnitTest)
  *   ...
  * }
  *
  * // Tag with multiple tags
  * test("should sync 1000 blocks") {
  *   taggedAs(IntegrationTest, SlowTest)
  *   ...
  * }
  *
  * // In behavior tests (FlatSpec, WordSpec, etc.)
  * "BlockValidator" should "validate headers" taggedAs(UnitTest) in { ... }
  * }}}
  *
  * SBT Usage:
  * {{{
  * sbt testEssential      // Tier 1 — fast unit tests (< 5 min), excludes SlowTest + IntegrationTest
  * sbt testStandard       // Tier 2 — all unit/integration tests (< 30 min), excludes BenchmarkTest + EthereumTest
  * sbt testComprehensive  // Tier 3 — full compliance suite (< 3 h), no exclusions
  * sbt testConsensus      // Run only ConsensusTest-tagged tests
  * sbt testRPC            // Run only RPCTest-tagged tests
  * sbt testSync           // Run only SyncTest-tagged tests
  * sbt testEthereum       // Run only EthereumTest-tagged tests
  * }}}
  *
  * @see
  *   ADR-017 for detailed test categorization strategy
  * @see
  *   ADR-015 for ethereum/tests integration
  */
object Tags:

  // ===== Tier 1: Essential Tests (Target: < 5 minutes) =====

  /** Tests that execute quickly (< 100ms) and test core business logic.
    *
    * These are the default tests that should run on every commit. Most unit tests should have this tag or no tag at
    * all.
    */
  object UnitTest extends Tag("UnitTest")

  // ===== Tier 2: Standard Tests (Target: < 30 minutes) =====

  /** Integration tests that validate interaction between components.
    *
    * These tests may involve:
    *   - Database operations
    *   - Network protocols
    *   - Actor system choreography
    *   - External system integration
    *
    * Execution time: < 5 seconds per test
    */
  object IntegrationTest extends Tag("IntegrationTest")

  /** Tests that take longer to execute (> 100ms, < 5 seconds).
    *
    * Use this tag for tests that are necessary but too slow for essential test suite:
    *   - Large data processing
    *   - Multiple block validation
    *   - Complex state transitions
    */
  object SlowTest extends Tag("SlowTest")

  // ===== Tier 3: Comprehensive Tests (Target: < 3 hours) =====

  /** Tests from the official ethereum/tests repository.
    *
    * These validate compliance with Ethereum specification:
    *   - BlockchainTests
    *   - GeneralStateTests
    *   - VMTests
    *   - TransactionTests
    *
    * @see
    *   ADR-015 for ethereum/tests adapter implementation
    */
  object EthereumTest extends Tag("EthereumTest")

  /** Performance benchmark tests.
    *
    * These tests measure and validate performance metrics:
    *   - Block validation speed
    *   - Transaction execution time
    *   - State root calculation
    *   - RLP encoding/decoding
    *   - Network throughput
    */
  object BenchmarkTest extends Tag("BenchmarkTest")

  /** Long-running stress tests. Reserved for future use — no tests currently use this tag. */
  object StressTest extends Tag("StressTest")

  // ===== Module-Specific Tags =====

  /** Tests for cryptographic operations.
    *
    * Includes:
    *   - ECDSA signatures
    *   - Hashing algorithms (Keccak256, SHA256, RIPEMD160)
    *   - ECIES encryption
    *   - Key derivation (scrypt, PBKDF2)
    *   - ZK-SNARK operations
    */
  object CryptoTest extends Tag("CryptoTest")

  /** Tests for RLP encoding/decoding.
    *
    * Validates Recursive Length Prefix serialization for:
    *   - Transactions
    *   - Blocks
    *   - Receipts
    *   - State trie nodes
    */
  object RLPTest extends Tag("RLPTest")

  /** Tests for Ethereum Virtual Machine.
    *
    * Includes:
    *   - Opcode execution
    *   - Gas calculation
    *   - Precompiled contracts
    *   - Stack/memory operations
    *   - Jump validation
    */
  object VMTest extends Tag("VMTest")

  /** Tests for network protocols and P2P communication.
    *
    * Validates:
    *   - RLPx handshake
    *   - ETH protocol messages
    *   - Peer discovery
    *   - Message encoding/decoding
    *   - Network synchronization
    */
  object NetworkTest extends Tag("NetworkTest")

  /** Tests for Merkle Patricia Trie operations.
    *
    * Includes:
    *   - Node insertion/deletion
    *   - Root hash calculation
    *   - Proof generation/verification
    *   - Persistence
    */
  object MPTTest extends Tag("MPTTest")

  /** Tests for blockchain state management.
    *
    * Validates:
    *   - Account state updates
    *   - Storage operations
    *   - State root calculation
    *   - World state snapshots
    */
  object StateTest extends Tag("StateTest")

  /** Tests for consensus mechanisms.
    *
    * Includes:
    *   - PoW mining (Ethash)
    *   - Block validation
    *   - Difficulty calculation
    *   - Uncle validation
    *   - Fork choice rules
    */
  object ConsensusTest extends Tag("ConsensusTest")

  /** Tests for JSON-RPC API.
    *
    * Validates RPC endpoints:
    *   - eth_* methods
    *   - net_* methods
    *   - debug_* methods
    *   - personal_* methods
    */
  object RPCTest extends Tag("RPCTest")

  /** Tests for database operations.
    *
    * Includes:
    *   - RocksDB operations
    *   - Storage backends
    *   - Caching layers
    *   - Data migration
    */
  object DatabaseTest extends Tag("DatabaseTest")

  /** Tests for blockchain synchronization.
    *
    * Validates:
    *   - SNAP sync
    *   - Regular sync
    *   - Block download
    *   - State download
    */
  object SyncTest extends Tag("SyncTest")

  // ===== Fork-Specific Tags =====

  /** Tests specific to Ethereum Classic Olympia fork (ECIP-1111/1112/1121). */
  object OlympiaTest extends Tag("OlympiaTest")

  // ===== Special Tags =====

  /** Tests that are flaky or have known intermittent failures.
    *
    * These tests should be investigated and fixed but are temporarily marked to avoid blocking CI.
    */
  object FlakyTest extends Tag("FlakyTest")

  /** Tests that are currently ignored but should be re-enabled.
    *
    * Use this when a test is temporarily disabled due to known issues.
    */
  object DisabledTest extends Tag("DisabledTest")

  /** Tests that require manual verification or are non-deterministic. Reserved for future use — no tests currently use
    * this tag.
    */
  object ManualTest extends Tag("ManualTest")

  /** Fast ETH-path smoke tests (Target: < 60 seconds).
    *
    * A lightweight subset that exercises the ETH execution path (chainId=1, `forTimestamp` dispatch) using a handful of
    * named ethereum/tests vectors. Reachable below `testComprehensive` so the ETH path gets quick CI/local coverage.
    *
    * @see
    *   `EthSmokeSpec` and the `testEthSmoke` sbt target
    */
  object EthSmoke extends Tag("EthSmoke")
