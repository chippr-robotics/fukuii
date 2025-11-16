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
  * // Run only essential tests (exclude slow and integration tests)
  * sbt "testOnly -- -l SlowTest -l IntegrationTest -l BenchmarkTest"
  *
  * // Run standard tests (exclude only comprehensive tests)
  * sbt "testOnly -- -l BenchmarkTest -l EthereumTest"
  *
  * // Run all tests
  * sbt testAll
  * }}}
  *
  * @see
  *   ADR-017 for detailed test categorization strategy
  * @see
  *   ADR-015 for ethereum/tests integration
  */
object Tags {

  // ===== Tier 1: Essential Tests (Target: < 5 minutes) =====

  /** Tests that execute quickly (< 100ms) and test core business logic.
    *
    * These are the default tests that should run on every commit. Most unit tests should have this tag or no tag at
    * all.
    */
  object UnitTest extends Tag("UnitTest")

  /** Fast-executing tests suitable for rapid feedback.
    *
    * Use this for tests that complete in under 100ms and provide high value-to-time ratio.
    */
  object FastTest extends Tag("FastTest")

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

  /** Long-running stress tests.
    *
    * Tests that may take minutes or hours to complete:
    *   - Large blockchain sync
    *   - Memory leak detection
    *   - Concurrent load testing
    *   - Resource exhaustion scenarios
    */
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
    *   - Fast sync
    *   - Regular sync
    *   - Block download
    *   - State download
    */
  object SyncTest extends Tag("SyncTest")

  // ===== Fork-Specific Tags =====

  /** Tests specific to Homestead fork. */
  object HomesteadTest extends Tag("HomesteadTest")

  /** Tests specific to Tangerine Whistle fork (EIP-150). */
  object TangerineWhistleTest extends Tag("TangerineWhistleTest")

  /** Tests specific to Spurious Dragon fork (EIP-158). */
  object SpuriousDragonTest extends Tag("SpuriousDragonTest")

  /** Tests specific to Byzantium fork. */
  object ByzantiumTest extends Tag("ByzantiumTest")

  /** Tests specific to Constantinople fork. */
  object ConstantinopleTest extends Tag("ConstantinopleTest")

  /** Tests specific to Istanbul fork. */
  object IstanbulTest extends Tag("IstanbulTest")

  /** Tests specific to Berlin fork. */
  object BerlinTest extends Tag("BerlinTest")

  /** Tests specific to Ethereum Classic Atlantis fork. */
  object AtlantisTest extends Tag("AtlantisTest")

  /** Tests specific to Ethereum Classic Agharta fork. */
  object AghartaTest extends Tag("AghartaTest")

  /** Tests specific to Ethereum Classic Phoenix fork. */
  object PhoenixTest extends Tag("PhoenixTest")

  /** Tests specific to Ethereum Classic Magneto fork. */
  object MagnetoTest extends Tag("MagnetoTest")

  /** Tests specific to Ethereum Classic Mystique fork. */
  object MystiqueTest extends Tag("MystiqueTest")

  /** Tests specific to Ethereum Classic Spiral fork. */
  object SpiralTest extends Tag("SpiralTest")

  // ===== Environment-Specific Tags (from RPC tests) =====

  /** Tests that require connection to MainNet. */
  object MainNet extends Tag("MainNet")

  /** Tests that run against private test network. */
  object PrivNet extends Tag("PrivNet")

  /** Tests that run against private network without mining. */
  object PrivNetNoMining extends Tag("PrivNetNoMining")

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

  /** Tests that require manual verification or are non-deterministic. */
  object ManualTest extends Tag("ManualTest")
}
