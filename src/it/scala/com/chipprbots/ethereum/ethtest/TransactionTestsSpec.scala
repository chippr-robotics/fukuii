package com.chipprbots.ethereum.ethtest

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.io.File
import io.circe._
import io.circe.parser._
import scala.io.Source

import com.chipprbots.ethereum.testing.Tags._

/** Test suite for ethereum/tests TransactionTests category
  *
  * Runs tests from the TransactionTests directory of the ethereum/tests repository. These tests validate:
  *   - Transaction RLP encoding/decoding
  *   - Transaction signature verification
  *   - Transaction sender recovery
  *   - Transaction hash calculation
  *   - Transaction validation (nonce, gas, value, etc.)
  *
  * TransactionTests focus on transaction parsing and validation, ensuring that transactions are properly:
  *   - Decoded from RLP format
  *   - Validated for signature correctness
  *   - Sender addresses correctly recovered from signatures
  *   - Transaction hashes correctly calculated
  *
  * Tests are filtered to only run pre-Spiral fork tests (Berlin and earlier), as ETC diverged from ETH at the Spiral
  * fork (block 19.25M).
  *
  * See https://github.com/ethereum/tests/tree/develop/TransactionTests See ADR-015 for implementation details See
  * ADR-017 for test categorization strategy
  */
class TransactionTestsSpec extends AnyFlatSpec with Matchers {

  // Supported networks (pre-Spiral fork only)
  val supportedNetworks = Set(
    "Frontier",
    "Homestead",
    "EIP150", // Tangerine Whistle
    "EIP158", // Spurious Dragon
    "Byzantium",
    "Constantinople",
    "ConstantinopleFix",
    "Istanbul",
    "Berlin"
  )

  /** Helper to discover test files in a TransactionTests subdirectory
    *
    * @param testCategory
    *   Path relative to TransactionTests directory (e.g., "ttNonce", "ttData")
    * @return
    *   List of test file paths
    */
  def discoverTransactionTests(testCategory: String): Seq[String] = {
    val basePath = "/home/runner/work/fukuii/fukuii/ets/tests/TransactionTests"
    val categoryPath = new File(s"$basePath/$testCategory")

    if (!categoryPath.exists() || !categoryPath.isDirectory) {
      Seq.empty
    } else {
      categoryPath
        .listFiles()
        .filter(_.getName.endsWith(".json"))
        .map(f => s"$basePath/$testCategory/${f.getName}")
        .toSeq
    }
  }

  /** Load a transaction test file
    *
    * @param filePath
    *   Full file path to test file
    * @return
    *   Parsed JSON object
    */
  def loadTransactionTest(filePath: String): Json = {
    val source = Source.fromFile(filePath)
    try {
      val jsonString = source.mkString
      parse(jsonString) match {
        case Right(json) => json
        case Left(error) => throw new RuntimeException(s"Failed to parse JSON: $error")
      }
    } finally source.close()
  }

  /** Validate transaction test structure
    *
    * Checks that the test has the expected format with network-specific results and RLP data.
    *
    * @param testJson
    *   Parsed test JSON
    * @return
    *   True if test has valid structure
    */
  def validateTestStructure(testJson: Json): Boolean = {
    testJson.asObject match {
      case Some(testObject) =>
        // Get the first (and usually only) test case
        testObject.toMap.headOption match {
          case Some((testName, testData)) =>
            val hasRlp = testData.hcursor.downField("rlp").succeeded
            val hasNetworkData = supportedNetworks.exists { network =>
              testData.hcursor.downField(network).succeeded
            }
            hasRlp && hasNetworkData
          case None => false
        }
      case None => false
    }
  }

  "TransactionTests" should "discover ttNonce tests" taggedAs (IntegrationTest, EthereumTest, SlowTest) in {
    val basePath = "/home/runner/work/fukuii/fukuii/ets/tests/TransactionTests"
    val baseDir = new File(basePath)

    if (!baseDir.exists()) {
      info(s"Skipping test - ethereum/tests submodule not initialized at $basePath")
      info("Run 'git submodule init && git submodule update' to initialize")
      pending
    } else {
      val tests = discoverTransactionTests("ttNonce")
      info(s"Discovered ${tests.size} test files in ttNonce")
      tests.size should be > 0
    }
  }

  it should "discover ttData tests" taggedAs (IntegrationTest, EthereumTest, SlowTest) in {
    val basePath = "/home/runner/work/fukuii/fukuii/ets/tests/TransactionTests"
    val baseDir = new File(basePath)

    if (!baseDir.exists()) {
      info(s"Skipping test - ethereum/tests submodule not initialized at $basePath")
      pending
    } else {
      val tests = discoverTransactionTests("ttData")
      info(s"Discovered ${tests.size} test files in ttData")
      tests.size should be > 0
    }
  }

  it should "discover ttGasLimit tests" taggedAs (IntegrationTest, EthereumTest, SlowTest) in {
    val basePath = "/home/runner/work/fukuii/fukuii/ets/tests/TransactionTests"
    val baseDir = new File(basePath)

    if (!baseDir.exists()) {
      info(s"Skipping test - ethereum/tests submodule not initialized at $basePath")
      pending
    } else {
      val tests = discoverTransactionTests("ttGasLimit")
      info(s"Discovered ${tests.size} test files in ttGasLimit")
      tests.size should be > 0
    }
  }

  it should "discover ttGasPrice tests" taggedAs (IntegrationTest, EthereumTest, SlowTest) in {
    val basePath = "/home/runner/work/fukuii/fukuii/ets/tests/TransactionTests"
    val baseDir = new File(basePath)

    if (!baseDir.exists()) {
      info(s"Skipping test - ethereum/tests submodule not initialized at $basePath")
      pending
    } else {
      val tests = discoverTransactionTests("ttGasPrice")
      info(s"Discovered ${tests.size} test files in ttGasPrice")
      tests.size should be > 0
    }
  }

  it should "discover ttValue tests" taggedAs (IntegrationTest, EthereumTest, SlowTest) in {
    val basePath = "/home/runner/work/fukuii/fukuii/ets/tests/TransactionTests"
    val baseDir = new File(basePath)

    if (!baseDir.exists()) {
      info(s"Skipping test - ethereum/tests submodule not initialized at $basePath")
      pending
    } else {
      val tests = discoverTransactionTests("ttValue")
      info(s"Discovered ${tests.size} test files in ttValue")
      tests.size should be > 0
    }
  }

  it should "discover ttSignature tests" taggedAs (IntegrationTest, EthereumTest, SlowTest) in {
    val basePath = "/home/runner/work/fukuii/fukuii/ets/tests/TransactionTests"
    val baseDir = new File(basePath)

    if (!baseDir.exists()) {
      info(s"Skipping test - ethereum/tests submodule not initialized at $basePath")
      pending
    } else {
      val tests = discoverTransactionTests("ttSignature")
      info(s"Discovered ${tests.size} test files in ttSignature")
      tests.size should be > 0
    }
  }

  it should "discover ttVValue tests" taggedAs (IntegrationTest, EthereumTest, SlowTest) in {
    val basePath = "/home/runner/work/fukuii/fukuii/ets/tests/TransactionTests"
    val baseDir = new File(basePath)

    if (!baseDir.exists()) {
      info(s"Skipping test - ethereum/tests submodule not initialized at $basePath")
      pending
    } else {
      val tests = discoverTransactionTests("ttVValue")
      info(s"Discovered ${tests.size} test files in ttVValue")
      tests.size should be > 0
    }
  }

  it should "discover ttRSValue tests" taggedAs (IntegrationTest, EthereumTest, SlowTest) in {
    val basePath = "/home/runner/work/fukuii/fukuii/ets/tests/TransactionTests"
    val baseDir = new File(basePath)

    if (!baseDir.exists()) {
      info(s"Skipping test - ethereum/tests submodule not initialized at $basePath")
      pending
    } else {
      val tests = discoverTransactionTests("ttRSValue")
      info(s"Discovered ${tests.size} test files in ttRSValue")
      tests.size should be > 0
    }
  }

  it should "discover ttWrongRLP tests" taggedAs (IntegrationTest, EthereumTest, SlowTest) in {
    val basePath = "/home/runner/work/fukuii/fukuii/ets/tests/TransactionTests"
    val baseDir = new File(basePath)

    if (!baseDir.exists()) {
      info(s"Skipping test - ethereum/tests submodule not initialized at $basePath")
      pending
    } else {
      val tests = discoverTransactionTests("ttWrongRLP")
      info(s"Discovered ${tests.size} test files in ttWrongRLP")
      tests.size should be > 0
    }
  }

  it should "load and validate a sample transaction test" taggedAs (IntegrationTest, EthereumTest, SlowTest) in {
    val testFile = "/home/runner/work/fukuii/fukuii/ets/tests/TransactionTests/ttNonce/TransactionWithHighNonce256.json"
    val file = new File(testFile)

    if (!file.exists()) {
      info(s"Skipping test - ethereum/tests submodule not initialized")
      pending
    } else {
      val testJson = loadTransactionTest(testFile)
      info("Loaded transaction test file")

      val isValid = validateTestStructure(testJson)
      isValid shouldBe true

      // Verify it has expected fields
      val testObject = testJson.asObject.get
      testObject.toMap.headOption match {
        case Some((testName, testData)) =>
          info(s"Test name: $testName")

          // Check for RLP field
          val hasRlp = testData.hcursor.downField("rlp").as[String].isRight
          hasRlp shouldBe true
          info("✓ Test has RLP field")

          // Check for network-specific results
          val hasFrontier = testData.hcursor.downField("Frontier").succeeded
          hasFrontier shouldBe true
          info("✓ Test has Frontier network results")

        case None => fail("Test file has no test cases")
      }
    }
  }
}
