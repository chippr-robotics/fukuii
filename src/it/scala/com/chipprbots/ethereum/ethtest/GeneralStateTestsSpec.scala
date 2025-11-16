package com.chipprbots.ethereum.ethtest

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import com.chipprbots.ethereum.utils.ByteStringUtils.ByteStringOps

import com.chipprbots.ethereum.testing.Tags._

/** Test suite for ethereum/tests GeneralStateTests category
  *
  * Runs tests from the GeneralStateTests directory of the ethereum/tests repository. These tests validate EVM state
  * transitions, opcodes, and gas costs.
  *
  * Note: GeneralStateTests use a different format than BlockchainTests (with env, post, pre, transaction fields). We
  * focus on BlockchainTests for now, which have the same semantic coverage but use the BlockchainTest format.
  *
  * See https://github.com/ethereum/tests/tree/develop/GeneralStateTests
  */
class GeneralStateTestsSpec extends EthereumTestsSpec {

  // Note: The ethereum/tests repository contains GeneralStateTests in a different format
  // than BlockchainTests. The BlockchainTests/GeneralStateTests directory contains
  // the same tests but in BlockchainTest format, which our adapter already supports.
  // Therefore, we run tests from BlockchainTests/GeneralStateTests instead.

  "GeneralStateTests" should "pass basic arithmetic tests (add11)" taggedAs (IntegrationTest, EthereumTest, SlowTest) in {
    // Test from BlockchainTests/GeneralStateTests/stExample/add11.json
    // Tests basic ADD opcode: (add 1 1) = 2
    info("Loading add11 test...")
    val suite = loadTestSuite("/ethereum-tests/add11.json")

    info(s"Loaded ${suite.tests.size} test case(s)")
    suite.tests.size should be > 0

    suite.tests.foreach { case (testName, test) =>
      info(s"Running test: $testName")
      info(s"  Network: ${test.network}")

      val result = executeTest(test)
      result match {
        case Right(executionResult) =>
          info(s"  ✓ Test passed")
          info(s"  Blocks executed: ${executionResult.blocksExecuted}")
          info(s"  Final state root: ${executionResult.finalStateRoot.toHex}")
        case Left(error) =>
          fail(s"Test failed: $error")
      }
    }
  }

  it should "pass addNonConst test from stArgsZeroOneBalance category" taggedAs (IntegrationTest, EthereumTest, SlowTest) in {
    // Test from BlockchainTests/GeneralStateTests/stArgsZeroOneBalance/addNonConst.json
    // Tests ADD with non-constant values
    info("Loading addNonConst test...")
    val suite = loadTestSuite("/ethereum-tests/addNonConst.json")

    info(s"Loaded ${suite.tests.size} test case(s)")
    suite.tests.size should be > 0

    suite.tests.foreach { case (testName, test) =>
      info(s"Running test: $testName")
      info(s"  Network: ${test.network}")

      val result = executeTest(test)
      result match {
        case Right(executionResult) =>
          info(s"  ✓ Test passed")
          info(s"  Blocks executed: ${executionResult.blocksExecuted}")
        case Left(error) =>
          fail(s"Test failed: $error")
      }
    }
  }
}
