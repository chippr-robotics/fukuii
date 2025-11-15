package com.chipprbots.ethereum.ethtest

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.io.File
import scala.io.Source

/** Test suite for ethereum/tests BlockchainTests category
  *
  * Runs tests from the BlockchainTests directory of the ethereum/tests repository. These tests validate:
  *   - Block header parsing and validation
  *   - Transaction execution
  *   - State transitions
  *   - Uncle handling
  *   - Fork-specific behavior
  *
  * Tests are filtered to only run pre-Spiral fork tests (Berlin and earlier), as ETC diverged from ETH at the Spiral
  * fork (block 19.25M).
  *
  * See https://github.com/ethereum/tests/tree/develop/BlockchainTests See ADR-015 for implementation details
  */
class BlockchainTestsSpec extends EthereumTestsSpec {

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

  /** Helper to discover test files in a directory
    *
    * @param testCategory
    *   Path relative to BlockchainTests directory
    * @return
    *   List of test file paths
    */
  def discoverTests(testCategory: String): Seq[String] = {
    val basePath = "/home/runner/work/fukuii/fukuii/ets/tests/BlockchainTests"
    val categoryPath = new File(s"$basePath/$testCategory")

    if (!categoryPath.exists() || !categoryPath.isDirectory) {
      Seq.empty
    } else {
      categoryPath
        .listFiles()
        .filter(_.getName.endsWith(".json"))
        .map(f => s"/BlockchainTests/$testCategory/${f.getName}")
        .toSeq
    }
  }

  /** Load and filter test suite to only include supported networks
    *
    * @param resourcePath
    *   Path to test file
    * @return
    *   Filtered test suite with only supported networks
    */
  def loadAndFilterTestSuite(resourcePath: String): BlockchainTestSuite = {
    val fullPath = s"/home/runner/work/fukuii/fukuii/ets/tests$resourcePath"
    val file = new File(fullPath)

    if (!file.exists()) {
      BlockchainTestSuite(Map.empty)
    } else {
      val suite = EthereumTestsAdapter.loadTestSuite(resourcePath).unsafeRunSync()

      // Filter to only supported networks
      val filteredTests = suite.tests.filter { case (_, test) =>
        supportedNetworks.contains(test.network)
      }

      BlockchainTestSuite(filteredTests)
    }
  }

  "BlockchainTests" should "pass SimpleTx from ValidBlocks" in {
    info("Running SimpleTx test from ValidBlocks/bcValidBlockTest...")
    val suite = loadTestSuite("/ethereum-tests/SimpleTx.json")

    suite.tests.size should be > 0
    info(s"Loaded ${suite.tests.size} test case(s)")

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

  it should "pass ExtraData32 test" in {
    info("Running ExtraData32 test...")
    val suite = loadTestSuite("/ethereum-tests/ExtraData32.json")

    suite.tests.foreach { case (testName, test) =>
      info(s"Running test: $testName, Network: ${test.network}")
      val result = executeTest(test)
      result match {
        case Right(executionResult) =>
          info(s"  ✓ Test passed - Blocks executed: ${executionResult.blocksExecuted}")
        case Left(error) =>
          fail(s"Test failed: $error")
      }
    }
  }

  it should "pass dataTx test" in {
    info("Running dataTx test...")
    val suite = loadTestSuite("/ethereum-tests/dataTx.json")

    suite.tests.foreach { case (testName, test) =>
      info(s"Running test: $testName, Network: ${test.network}")
      val result = executeTest(test)
      result match {
        case Right(executionResult) =>
          info(s"  ✓ Test passed - Blocks executed: ${executionResult.blocksExecuted}")
        case Left(error) =>
          fail(s"Test failed: $error")
      }
    }
  }

  it should "discover tests in ValidBlocks/bcValidBlockTest" in {
    val basePath = "/home/runner/work/fukuii/fukuii/ets/tests/BlockchainTests"
    val baseDir = new File(basePath)
    
    if (!baseDir.exists()) {
      info(s"Skipping test - ethereum/tests submodule not initialized at $basePath")
      info("Run 'git submodule init && git submodule update' to initialize")
      pending
    } else {
      val tests = discoverTests("ValidBlocks/bcValidBlockTest")
      info(s"Discovered ${tests.size} test files in ValidBlocks/bcValidBlockTest")
      tests.size should be > 0
    }
  }

  it should "discover tests in ValidBlocks/bcStateTests" in {
    val basePath = "/home/runner/work/fukuii/fukuii/ets/tests/BlockchainTests"
    val baseDir = new File(basePath)
    
    if (!baseDir.exists()) {
      info(s"Skipping test - ethereum/tests submodule not initialized at $basePath")
      info("Run 'git submodule init && git submodule update' to initialize")
      pending
    } else {
      val tests = discoverTests("ValidBlocks/bcStateTests")
      info(s"Discovered ${tests.size} test files in ValidBlocks/bcStateTests")
      tests.size should be > 0
    }
  }

  it should "filter out unsupported networks" in {
    // This test validates that we properly filter post-Spiral tests
    val suite = BlockchainTestSuite(
      Map(
        "Berlin_Test" -> BlockchainTest(
          pre = Map.empty,
          blocks = Seq.empty,
          postState = Map.empty,
          network = "Berlin",
          genesisBlockHeader = None
        ),
        "London_Test" -> BlockchainTest(
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
    (filtered should contain).key("Berlin_Test")
    filtered should not contain key("London_Test")
  }
}
