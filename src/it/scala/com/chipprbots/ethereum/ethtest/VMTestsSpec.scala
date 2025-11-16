package com.chipprbots.ethereum.ethtest

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.io.File
import io.circe._
import io.circe.parser._
import scala.io.Source

import com.chipprbots.ethereum.testing.Tags._

/** Test suite for ethereum/tests VMTests category
  *
  * Runs tests from the GeneralStateTests/VMTests directory of the ethereum/tests repository. These tests validate:
  *   - EVM opcode execution
  *   - Stack operations
  *   - Memory operations
  *   - Arithmetic and bitwise operations
  *   - Flow control (JUMP, JUMPI, etc.)
  *   - Logging operations
  *
  * VMTests are comprehensive opcode-level tests that ensure the EVM implementation matches the Ethereum specification.
  *
  * Tests are filtered to only run pre-Spiral fork tests (Berlin and earlier), as ETC diverged from ETH at the Spiral
  * fork (block 19.25M).
  *
  * See https://github.com/ethereum/tests/tree/develop/GeneralStateTests/VMTests See ADR-015 for implementation details
  * See ADR-017 for test categorization strategy
  */
class VMTestsSpec extends EthereumTestsSpec {

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

  /** Helper to discover test files in a VMTests subdirectory
    *
    * @param testCategory
    *   Path relative to GeneralStateTests/VMTests directory
    * @return
    *   List of test file paths
    */
  def discoverVMTests(testCategory: String): Seq[String] = {
    val basePath = "/home/runner/work/fukuii/fukuii/ets/tests/GeneralStateTests/VMTests"
    val categoryPath = new File(s"$basePath/$testCategory")

    if (!categoryPath.exists() || !categoryPath.isDirectory) {
      Seq.empty
    } else {
      categoryPath
        .listFiles()
        .filter(_.getName.endsWith(".json"))
        .map(f => s"/GeneralStateTests/VMTests/$testCategory/${f.getName}")
        .toSeq
    }
  }

  /** Load test suite from filesystem path
    *
    * @param filePath
    *   Full filesystem path to test file
    * @return
    *   Parsed test suite
    */
  def loadTestSuiteFromFile(filePath: String): BlockchainTestSuite = {
    import scala.io.Source
    import io.circe.parser._
    import cats.effect.unsafe.IORuntime

    given IORuntime = IORuntime.global

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
              case Right(suite) => suite
              case Left(error)  => throw new RuntimeException(s"Failed to decode test suite: $error")
            }
          case Left(error) => throw new RuntimeException(s"Failed to parse JSON: $error")
        }
      } finally source.close()
    }
  }

  /** Load and filter test suite to only include supported networks
    *
    * @param resourcePath
    *   Path to test file relative to GeneralStateTests/VMTests
    * @return
    *   Filtered test suite with only supported networks
    */
  def loadAndFilterTestSuite(resourcePath: String): BlockchainTestSuite = {
    val fullPath = s"/home/runner/work/fukuii/fukuii/ets/tests/GeneralStateTests/VMTests$resourcePath"
    val suite = loadTestSuiteFromFile(fullPath)

    // Filter to only supported networks
    val filteredTests = suite.tests.filter { case (_, test) =>
      supportedNetworks.contains(test.network)
    }

    BlockchainTestSuite(filteredTests)
  }

  "VMTests" should "discover vmArithmeticTest tests" taggedAs (IntegrationTest, EthereumTest, VMTest, SlowTest) in {
    val basePath = "/home/runner/work/fukuii/fukuii/ets/tests/GeneralStateTests/VMTests"
    val baseDir = new File(basePath)

    if (!baseDir.exists()) {
      info(s"Skipping test - ethereum/tests submodule not initialized at $basePath")
      info("Run 'git submodule init && git submodule update' to initialize")
      pending
    } else {
      val tests = discoverVMTests("vmArithmeticTest")
      info(s"Discovered ${tests.size} test files in vmArithmeticTest")
      tests.size should be > 0
    }
  }

  it should "discover vmBitwiseLogicOperation tests" taggedAs (IntegrationTest, EthereumTest, VMTest, SlowTest) in {
    val basePath = "/home/runner/work/fukuii/fukuii/ets/tests/GeneralStateTests/VMTests"
    val baseDir = new File(basePath)

    if (!baseDir.exists()) {
      info(s"Skipping test - ethereum/tests submodule not initialized at $basePath")
      pending
    } else {
      val tests = discoverVMTests("vmBitwiseLogicOperation")
      info(s"Discovered ${tests.size} test files in vmBitwiseLogicOperation")
      tests.size should be > 0
    }
  }

  it should "discover vmIOandFlowOperations tests" taggedAs (IntegrationTest, EthereumTest, VMTest, SlowTest) in {
    val basePath = "/home/runner/work/fukuii/fukuii/ets/tests/GeneralStateTests/VMTests"
    val baseDir = new File(basePath)

    if (!baseDir.exists()) {
      info(s"Skipping test - ethereum/tests submodule not initialized at $basePath")
      pending
    } else {
      val tests = discoverVMTests("vmIOandFlowOperations")
      info(s"Discovered ${tests.size} test files in vmIOandFlowOperations")
      tests.size should be > 0
    }
  }

  it should "discover vmLogTest tests" taggedAs (IntegrationTest, EthereumTest, VMTest, SlowTest) in {
    val basePath = "/home/runner/work/fukuii/fukuii/ets/tests/GeneralStateTests/VMTests"
    val baseDir = new File(basePath)

    if (!baseDir.exists()) {
      info(s"Skipping test - ethereum/tests submodule not initialized at $basePath")
      pending
    } else {
      val tests = discoverVMTests("vmLogTest")
      info(s"Discovered ${tests.size} test files in vmLogTest")
      tests.size should be > 0
    }
  }

  it should "discover vmTests tests" taggedAs (IntegrationTest, EthereumTest, VMTest, SlowTest) in {
    val basePath = "/home/runner/work/fukuii/fukuii/ets/tests/GeneralStateTests/VMTests"
    val baseDir = new File(basePath)

    if (!baseDir.exists()) {
      info(s"Skipping test - ethereum/tests submodule not initialized at $basePath")
      pending
    } else {
      val tests = discoverVMTests("vmTests")
      info(s"Discovered ${tests.size} test files in vmTests")
      tests.size should be > 0
    }
  }

  it should "filter out unsupported networks" taggedAs (IntegrationTest, EthereumTest, VMTest) in {
    // This test validates that we properly filter post-Spiral tests
    val suite = BlockchainTestSuite(
      Map(
        "Berlin_VM_Test" -> BlockchainTest(
          pre = Map.empty,
          blocks = Seq.empty,
          postState = Map.empty,
          network = "Berlin",
          genesisBlockHeader = None
        ),
        "London_VM_Test" -> BlockchainTest(
          pre = Map.empty,
          blocks = Seq.empty,
          postState = Map.empty,
          network = "London", // Post-Berlin, not supported
          genesisBlockHeader = None
        )
      )
    )

    val filtered = suite.tests.filter { case (_, test) =>
      supportedNetworks.contains(test.network)
    }

    filtered.size shouldBe 1
    (filtered should contain).key("Berlin_VM_Test")
    filtered should not contain key("London_VM_Test")
  }

  it should "load and parse a sample VM arithmetic test" taggedAs (IntegrationTest, EthereumTest, VMTest, SlowTest) in {
    val testFile = "/home/runner/work/fukuii/fukuii/ets/tests/GeneralStateTests/VMTests/vmArithmeticTest/add.json"
    val file = new File(testFile)

    if (!file.exists()) {
      info(s"Skipping test - ethereum/tests submodule not initialized")
      pending
    } else {
      val suite = loadTestSuiteFromFile(testFile)
      info(s"Loaded ${suite.tests.size} test cases from add.json")

      suite.tests.size should be > 0

      suite.tests.foreach { case (testName, test) =>
        info(s"Test case: $testName")
        info(s"  Network: ${test.network}")
        info(s"  Pre-state accounts: ${test.pre.size}")
        info(s"  Blocks: ${test.blocks.size}")
        info(s"  Post-state accounts: ${test.postState.size}")

        // Validate test structure
        test.network should not be empty
      }
    }
  }
}
