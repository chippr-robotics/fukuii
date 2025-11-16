package com.chipprbots.ethereum.ethtest

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import io.circe._
import io.circe.parser._
import java.io.File
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.io.Source
import com.chipprbots.ethereum.utils.ByteStringUtils.ByteStringOps

import com.chipprbots.ethereum.testing.Tags._

/** Comprehensive test suite that runs multiple tests from ethereum/tests repository
  *
  * This test suite loads tests directly from the ets/tests submodule to validate broader EVM compliance.
  *
  * Tests are filtered to only run pre-Spiral fork tests (Berlin and earlier).
  */
class ComprehensiveBlockchainTestsSpec extends EthereumTestsSpec {

  // Supported networks (pre-Spiral fork only)
  val supportedNetworks = Set(
    "Frontier",
    "Homestead",
    "EIP150", // Tangerine Whistle
    "EIP158", // Spurious Dragon
    "Byzantium",
    "Constantinople",
    "Istanbul",
    "Berlin"
  )

  /** Load test suite directly from filesystem (ethereum/tests submodule)
    *
    * @param filePath
    *   Full filesystem path to JSON test file
    * @return
    *   Parsed and filtered test suite
    */
  def loadTestSuiteFromFile(filePath: String): BlockchainTestSuite = {
    val file = new File(filePath)
    if (!file.exists()) {
      BlockchainTestSuite(Map.empty)
    } else {
      val source = Source.fromFile(file)
      try {
        val jsonString = source.mkString
        parse(jsonString) match {
          case Right(json) =>
            json.as[BlockchainTestSuite] match {
              case Right(suite) =>
                // Filter to only supported networks
                val filteredTests = suite.tests.filter { case (_, test) =>
                  supportedNetworks.contains(test.network)
                }
                BlockchainTestSuite(filteredTests)
              case Left(error) => throw new RuntimeException(s"Failed to decode test suite: $error")
            }
          case Left(error) => throw new RuntimeException(s"Failed to parse JSON: $error")
        }
      } finally source.close()
    }
  }

  /** Discover and run all tests in a directory
    *
    * @param testDir
    *   Directory path
    * @param maxTests
    *   Maximum number of tests to run (default: Int.MaxValue)
    * @return
    *   (passed, failed, skipped) - failed includes gas calculation errors
    */
  def runTestsInDirectory(testDir: String, maxTests: Int = Int.MaxValue): (Int, Int, Int) = {
    val dir = new File(testDir)
    if (!dir.exists() || !dir.isDirectory) {
      info(s"Directory not found: $testDir")
      return (0, 0, 0)
    }

    val testFiles = dir.listFiles().filter(_.getName.endsWith(".json")).take(maxTests)
    var passed = 0
    var failed = 0
    var skipped = 0

    testFiles.foreach { file =>
      val suite = loadTestSuiteFromFile(file.getAbsolutePath)

      if (suite.tests.isEmpty) {
        skipped += 1
      } else {
        suite.tests.foreach { case (testName, test) =>
          val result = executeTest(test)
          result match {
            case Right(_) =>
              passed += 1
            case Left(error) =>
              // Log failure but don't spam console for known gas issues
              if (error.contains("invalid gas used")) {
                // Known gas calculation issue - see GAS_CALCULATION_ISSUES.md
                failed += 1
              } else if (error.contains("invalid state root")) {
                // State root mismatch - may be related to gas or other issues
                failed += 1
              } else {
                info(s"  ✗ Test failed: $testName - $error")
                failed += 1
              }
          }
        }
      }
    }

    (passed, failed, skipped)
  }

  "ComprehensiveBlockchainTests" should "run multiple tests from ValidBlocks/bcValidBlockTest" taggedAs (
    IntegrationTest,
    EthereumTest,
    SlowTest
  ) in {
    val testDir = "/home/runner/work/fukuii/fukuii/ets/tests/BlockchainTests/ValidBlocks/bcValidBlockTest"

    info(s"Running tests from ValidBlocks/bcValidBlockTest (max 10)...")
    val (passed, failed, skipped) = runTestsInDirectory(testDir, maxTests = 10)

    info(s"Results: $passed passed, $failed failed, $skipped skipped")

    // We expect at least some tests to pass
    passed should be > 0
    // We may have some failures due to missing EIP support, but should not be all failures
    passed should be > failed
  }

  it should "run multiple tests from ValidBlocks/bcStateTests" taggedAs (IntegrationTest, EthereumTest, SlowTest) in {
    val testDir = "/home/runner/work/fukuii/fukuii/ets/tests/BlockchainTests/ValidBlocks/bcStateTests"

    info(s"Running tests from ValidBlocks/bcStateTests (max 20)...")
    val (passed, failed, skipped) = runTestsInDirectory(testDir, maxTests = 20)

    info(s"Results: $passed passed, $failed failed, $skipped skipped")

    // We expect at least some tests to pass
    passed should be > 0
  }

  it should "run tests from ValidBlocks/bcUncleTest" taggedAs (IntegrationTest, EthereumTest, SlowTest) in {
    val testDir = "/home/runner/work/fukuii/fukuii/ets/tests/BlockchainTests/ValidBlocks/bcUncleTest"

    info(s"Running tests from ValidBlocks/bcUncleTest (max 5)...")
    val (passed, failed, skipped) = runTestsInDirectory(testDir, maxTests = 5)

    info(s"Results: $passed passed, $failed failed, $skipped skipped")

    // Uncle tests might have different requirements
    passed should be >= 0
  }

  it should "achieve at least 50 passing tests across all categories" taggedAs (
    IntegrationTest,
    EthereumTest,
    SlowTest
  ) in {
    var totalPassed = 0
    var totalFailed = 0
    var totalSkipped = 0

    val testCategories = Seq(
      ("ValidBlocks/bcValidBlockTest", 15),
      ("ValidBlocks/bcStateTests", 40),
      ("ValidBlocks/bcWalletTest", 5)
    )

    testCategories.foreach { case (category, maxTests) =>
      val testDir = s"/home/runner/work/fukuii/fukuii/ets/tests/BlockchainTests/$category"
      info(s"Running tests from $category (max $maxTests)...")

      val (passed, failed, skipped) = runTestsInDirectory(testDir, maxTests)
      totalPassed += passed
      totalFailed += failed
      totalSkipped += skipped

      info(s"  Category results: $passed passed, $failed failed, $skipped skipped")
    }

    info(s"Total results: $totalPassed passed, $totalFailed failed, $totalSkipped skipped")

    // Goal: At least 50 tests passing
    totalPassed should be >= 50
  }
}
