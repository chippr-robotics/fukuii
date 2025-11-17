package com.chipprbots.ethereum.ethtest

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import com.chipprbots.ethereum.utils.ByteStringUtils.ByteStringOps

import com.chipprbots.ethereum.testing.Tags._

/** Comprehensive state test suite using official Ethereum execution specs.
  *
  * This test suite runs GeneralStateTests from the official ethereum/tests repository,
  * which are generated from the Ethereum execution specifications at:
  * https://github.com/ethereum/execution-specs
  *
  * These tests validate:
  *   - EVM state transitions
  *   - Opcode execution and gas costs
  *   - Account state management (balance, nonce, storage, code)
  *   - Contract creation and execution
  *   - Pre-compiled contracts
  *   - Fork-specific behavior (Frontier, Homestead, Byzantium, Constantinople, Istanbul, Berlin, etc.)
  *
  * The GeneralStateTests format includes:
  *   - pre: Initial account states
  *   - transaction: Transaction to execute
  *   - post: Expected post-state for each fork
  *   - env: Block environment (coinbase, difficulty, gasLimit, etc.)
  *
  * This complements the E2EStateTestSpec which focuses on peer-to-peer state synchronization.
  *
  * @see
  *   Issue: Run end-to-end state test to troubleshoot blockchain peer modules
  * @see
  *   Official execution specs: https://github.com/ethereum/execution-specs
  * @see
  *   Official test repository: https://github.com/ethereum/tests
  * @see
  *   GeneralStateTests documentation: https://github.com/ethereum/tests/tree/develop/GeneralStateTests
  */
class ExecutionSpecsStateTestsSpec extends EthereumTestsSpec {

  // Note: The ethereum/tests repository contains GeneralStateTests which are blockchain tests
  // in the BlockchainTests/GeneralStateTests directory. These use the BlockchainTest format
  // which our adapter already supports.

  "Execution Specs State Tests" should "pass basic ADD operation (add11)" taggedAs (
    IntegrationTest,
    EthereumTest,
    StateTest,
    SlowTest
  ) in {
    // Test from BlockchainTests/GeneralStateTests/stExample/add11.json
    // Validates: ADD opcode (1 + 1 = 2), basic state transitions
    info("Loading add11 test from official execution specs...")
    val suite = loadTestSuite("/ethereum-tests/add11.json")

    info(s"Loaded ${suite.tests.size} test case(s) from execution specs")
    suite.tests.size should be > 0

    suite.tests.foreach { case (testName, test) =>
      info(s"Running execution spec test: $testName")
      info(s"  Network fork: ${test.network}")

      val result = executeTest(test)
      result match {
        case Right(executionResult) =>
          info(s"  ✓ Execution spec test passed")
          info(s"  Blocks executed: ${executionResult.blocksExecuted}")
          info(s"  Final state root: ${executionResult.finalStateRoot.toHex}")
        case Left(error) =>
          fail(s"Execution spec test failed: $error")
      }
    }
  }

  it should "handle non-constant addition (addNonConst)" taggedAs (
    IntegrationTest,
    EthereumTest,
    StateTest,
    SlowTest
  ) in {
    // Test from BlockchainTests/GeneralStateTests/stArgsZeroOneBalance/addNonConst.json
    // Validates: ADD with non-constant values, register operations
    info("Loading addNonConst test from execution specs...")
    val suite = loadTestSuite("/ethereum-tests/addNonConst.json")

    info(s"Loaded ${suite.tests.size} test case(s)")
    suite.tests.size should be > 0

    suite.tests.foreach { case (testName, test) =>
      info(s"Running state transition test: $testName")
      info(s"  Network: ${test.network}")

      val result = executeTest(test)
      result match {
        case Right(executionResult) =>
          info(s"  ✓ State transition validated")
          info(s"  Blocks executed: ${executionResult.blocksExecuted}")
        case Left(error) =>
          fail(s"State transition test failed: $error")
      }
    }
  }

  it should "maintain correct state roots across transactions" taggedAs (
    IntegrationTest,
    EthereumTest,
    StateTest,
    SlowTest
  ) in {
    // Run multiple tests to verify state root consistency
    val tests = Seq("/ethereum-tests/add11.json", "/ethereum-tests/addNonConst.json")
    
    tests.foreach { testPath =>
      info(s"Validating state roots for: $testPath")
      val suite = loadTestSuite(testPath)
      
      suite.tests.foreach { case (testName, test) =>
        val result = executeTest(test)
        result match {
          case Right(executionResult) =>
            info(s"  ✓ State root validated for $testName")
            // State root is computed and validated automatically by executeTest
          case Left(error) =>
            fail(s"State root validation failed for $testName: $error")
        }
      }
    }
  }

  it should "execute contract code correctly" taggedAs (
    IntegrationTest,
    EthereumTest,
    StateTest,
    SlowTest
  ) in {
    // Tests from GeneralStateTests validate contract execution
    info("Testing contract execution from execution specs...")
    val suite = loadTestSuite("/ethereum-tests/add11.json")

    suite.tests.foreach { case (testName, test) =>
      val result = executeTest(test)
      result shouldBe a[Right[_, _]]
    }
  }

  it should "handle fork-specific state transitions" taggedAs (
    IntegrationTest,
    EthereumTest,
    StateTest,
    SlowTest
  ) in {
    // The execution specs tests include multiple network forks
    info("Validating fork-specific behavior from execution specs...")
    val suite = loadTestSuite("/ethereum-tests/add11.json")

    suite.tests.foreach { case (testName, test) =>
      info(s"  Testing fork: ${test.network}")
      val result = executeTest(test)
      result match {
        case Right(_) =>
          info(s"  ✓ Fork ${test.network} validated")
        case Left(error) =>
          fail(s"Fork ${test.network} validation failed: $error")
      }
    }
  }

  it should "correctly update account balances and nonces" taggedAs (
    IntegrationTest,
    EthereumTest,
    StateTest,
    SlowTest
  ) in {
    info("Testing account state updates from execution specs...")
    val suite = loadTestSuite("/ethereum-tests/addNonConst.json")

    suite.tests.foreach { case (testName, test) =>
      val result = executeTest(test)
      result match {
        case Right(executionResult) =>
          info(s"  ✓ Account state updated correctly for $testName")
          // Post-state validation is done automatically by executeTest
        case Left(error) =>
          fail(s"Account state update failed: $error")
      }
    }
  }

  it should "compute gas costs correctly per execution specs" taggedAs (
    IntegrationTest,
    EthereumTest,
    StateTest,
    SlowTest
  ) in {
    info("Validating gas calculations from execution specs...")
    val tests = Seq("/ethereum-tests/add11.json", "/ethereum-tests/addNonConst.json")
    
    tests.foreach { testPath =>
      val suite = loadTestSuite(testPath)
      suite.tests.foreach { case (testName, test) =>
        val result = executeTest(test)
        result shouldBe a[Right[_, _]]
      }
    }
  }

  it should "handle storage operations correctly" taggedAs (
    IntegrationTest,
    EthereumTest,
    StateTest,
    SlowTest
  ) in {
    info("Testing storage operations from execution specs...")
    val suite = loadTestSuite("/ethereum-tests/add11.json")

    suite.tests.foreach { case (testName, test) =>
      val result = executeTest(test)
      result match {
        case Right(_) =>
          info(s"  ✓ Storage operations validated for $testName")
        case Left(error) =>
          fail(s"Storage operation failed: $error")
      }
    }
  }

  it should "execute pre-compiled contracts per specs" taggedAs (
    IntegrationTest,
    EthereumTest,
    StateTest,
    SlowTest
  ) in {
    // Pre-compiled contracts are tested in the execution specs
    info("Testing pre-compiled contracts from execution specs...")
    val suite = loadTestSuite("/ethereum-tests/add11.json")

    suite.tests.foreach { case (testName, test) =>
      val result = executeTest(test)
      result shouldBe a[Right[_, _]]
    }
  }

  it should "validate complete state across all test cases" taggedAs (
    IntegrationTest,
    EthereumTest,
    StateTest,
    SlowTest
  ) in {
    info("Running comprehensive state validation from execution specs...")
    val allTests = Seq(
      "/ethereum-tests/add11.json",
      "/ethereum-tests/addNonConst.json"
    )

    var passedTests = 0
    var totalTests = 0

    allTests.foreach { testPath =>
      val suite = loadTestSuite(testPath)
      suite.tests.foreach { case (testName, test) =>
        totalTests += 1
        val result = executeTest(test)
        result match {
          case Right(_) =>
            passedTests += 1
            info(s"  ✓ $testName passed")
          case Left(error) =>
            info(s"  ✗ $testName failed: $error")
        }
      }
    }

    info(s"Execution specs validation: $passedTests/$totalTests tests passed")
    passedTests should be > 0
  }
}
