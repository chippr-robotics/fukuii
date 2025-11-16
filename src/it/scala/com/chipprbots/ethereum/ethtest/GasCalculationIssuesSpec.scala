package com.chipprbots.ethereum.ethtest

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import com.chipprbots.ethereum.utils.ByteStringUtils.ByteStringOps

import com.chipprbots.ethereum.testing.Tags._

/** Test suite to identify and flag gas calculation discrepancies
  *
  * This spec runs tests that are failing due to gas calculation differences and provides detailed analysis to help
  * identify the root cause. These issues must be resolved before moving forward as gas calculation should be identical
  * to ethereum/tests expectations.
  *
  * CRITICAL: All tests in this spec are expected to fail with gas calculation errors. These failures indicate potential
  * bugs or missing EIP implementations that need to be fixed.
  */
class GasCalculationIssuesSpec extends EthereumTestsSpec {

  /** Execute test and capture detailed gas calculation error information
    *
    * @param test
    *   Test to execute
    * @return
    *   Either error message with gas details, or success
    */
  def executeAndAnalyzeGasError(test: BlockchainTest): Either[String, TestExecutionResult] =
    executeTest(test) match {
      case Left(error) if error.contains("invalid gas used") =>
        // Extract expected vs actual gas from error message
        val expectedGasRegex = """expected (\d+)""".r
        val actualGasRegex = """but got (\d+)""".r

        val expectedGas = expectedGasRegex.findFirstMatchIn(error).map(_.group(1).toLong)
        val actualGas = actualGasRegex.findFirstMatchIn(error).map(_.group(1).toLong)

        val diff = (expectedGas, actualGas) match {
          case (Some(exp), Some(act)) => s" (difference: ${exp - act})"
          case _                      => ""
        }

        Left(s"Gas calculation error$diff: $error")

      case result => result
    }

  "GasCalculationIssues" should "flag add11 test gas calculation discrepancy" taggedAs (IntegrationTest, EthereumTest, SlowTest) in {
    info("Testing add11 (basic ADD opcode) - should use identical gas")
    val suite = loadTestSuite("/ethereum-tests/add11.json")

    suite.tests.foreach { case (testName, test) =>
      info(s"Test: $testName, Network: ${test.network}")

      val result = executeAndAnalyzeGasError(test)
      result match {
        case Left(error) =>
          info(s"  ✗ FLAGGED: $error")
          info(s"  ACTION REQUIRED: Review gas calculation for ADD opcode in ${test.network}")
          fail(s"Gas calculation mismatch detected - requires code review before proceeding")

        case Right(executionResult) =>
          info(s"  ✓ Test passed unexpectedly - gas calculation may have been fixed")
          info(s"  Gas used: ${executionResult.blocksExecuted} blocks executed successfully")
      }
    }
  }

  it should "flag addNonConst test gas calculation discrepancy" taggedAs (IntegrationTest, EthereumTest, SlowTest) in {
    info("Testing addNonConst (ADD with non-constant values) - should use identical gas")
    val suite = loadTestSuite("/ethereum-tests/addNonConst.json")

    suite.tests.foreach { case (testName, test) =>
      info(s"Test: $testName, Network: ${test.network}")

      val result = executeAndAnalyzeGasError(test)
      result match {
        case Left(error) =>
          info(s"  ✗ FLAGGED: $error")
          info(s"  ACTION REQUIRED: Review gas calculation for PUSH and ADD opcodes in ${test.network}")
          fail(s"Gas calculation mismatch detected - requires code review before proceeding")

        case Right(executionResult) =>
          info(s"  ✓ Test passed unexpectedly - gas calculation may have been fixed")
      }
    }
  }

  it should "provide detailed analysis of gas calculation patterns" taggedAs (IntegrationTest, EthereumTest, SlowTest) in {
    info("Analyzing gas calculation patterns across multiple tests...")

    val testFiles = Seq(
      ("/ethereum-tests/add11.json", "Basic ADD opcode"),
      ("/ethereum-tests/addNonConst.json", "ADD with non-constant values")
    )

    var totalGasDiscrepancies = 0
    val discrepancyDetails = scala.collection.mutable.ListBuffer[String]()

    testFiles.foreach { case (testPath, description) =>
      info(s"Analyzing: $description")
      val suite = loadTestSuite(testPath)

      suite.tests.foreach { case (testName, test) =>
        executeAndAnalyzeGasError(test) match {
          case Left(error) if error.contains("difference:") =>
            totalGasDiscrepancies += 1
            discrepancyDetails += s"  - $testName: $error"

          case _ => // Test passed or different error
        }
      }
    }

    if (totalGasDiscrepancies > 0) {
      info(s"Found $totalGasDiscrepancies gas calculation discrepancies:")
      discrepancyDetails.foreach(d => info(d))

      info("")
      info("INVESTIGATION REQUIRED:")
      info("1. Review EIP implementations for Berlin and Istanbul networks")
      info("2. Check opcode gas costs in VM implementation")
      info("3. Verify gas refund calculations")
      info("4. Compare against geth/nethermind gas calculations")
      info("")

      fail(s"$totalGasDiscrepancies gas calculation discrepancies detected - code review required")
    } else {
      info("✓ No gas calculation discrepancies detected")
    }
  }

  it should "document known gas calculation issues for follow-up" taggedAs (IntegrationTest, EthereumTest, SlowTest) in {
    info("Documenting known gas calculation issues...")
    info("")
    info("KNOWN ISSUES:")
    info("1. add11 test - Gas calculation difference in Berlin network")
    info("   Expected: 43112, Actual: 41012")
    info("   Difference: 2100 gas")
    info("   Likely cause: Missing or incorrect EIP gas cost implementation")
    info("")
    info("2. addNonConst test - Gas calculation difference in Berlin network")
    info("   Expected: 23412, Actual: 22512")
    info("   Difference: 900 gas")
    info("   Likely cause: PUSH opcode gas cost or similar")
    info("")
    info("ACTION ITEMS:")
    info("1. Review Berlin fork EIP implementations")
    info("2. Check EIP-2929 (Gas cost increases for state access opcodes)")
    info("3. Verify EIP-2930 (Optional access lists) implementation")
    info("4. Compare with reference implementation (geth)")
    info("")

    // This test documents the issues but doesn't fail - it's for information
    succeed
  }
}
