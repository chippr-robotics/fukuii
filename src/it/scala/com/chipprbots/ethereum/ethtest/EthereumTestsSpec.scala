package com.chipprbots.ethereum.ethtest

import cats.effect.unsafe.IORuntime

import scala.io.Source

import io.circe.parser.parse
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.ledger.InMemoryWorldStateProxy
import com.chipprbots.ethereum.utils.Config

/** Base spec for running ethereum/tests blockchain tests
  *
  * Provides infrastructure for loading and executing JSON blockchain tests from the official ethereum/tests repository.
  *
  * Usage:
  * {{{
  * class MyEthereumTest extends EthereumTestsSpec {
  *   it should "pass simple value transfer test" in {
  *     val suite = loadTestSuite("/ethereum-tests/add11.json")
  *     suite.tests.foreach { case (_, test) => executeTest(test) should be a Symbol("right") }
  *   }
  * }
  * }}}
  *
  * Concrete specs drive execution through `executeTest`, which runs the test via the ETH execution path
  * (`EthereumTestExecutor.executeTest` -> `EthereumTestHelper` -> `BlockExecution.executeAndValidateBlock`).
  *
  * See https://github.com/ethereum/tests for test repository structure.
  */
abstract class EthereumTestsSpec extends AnyFlatSpec with Matchers:

  given IORuntime = IORuntime.global

  // Use blockchain config from Config
  lazy val baseBlockchainConfig = Config.blockchains.blockchainConfig

  /** Debugging utility: run a single named test from a resource suite.
    *
    * Loads the suite at `resourcePath`, finds the single entry whose name matches `testName`, and drives it through the
    * same ETH execution path used by `executeTest` (chainId=1, `forTimestamp` dispatch via `networkToConfig`,
    * Berlin→Prague fork filter applied by the concrete spec). Fails clearly if `testName` is not present in the suite.
    *
    * Use when you want to reproduce one targeted vector instead of running the whole batch.
    *
    * @param resourcePath
    *   Resource path of the JSON suite (e.g. "/ethereum-tests/SimpleTx.json")
    * @param testName
    *   Exact key of the test case within the suite
    * @return
    *   Either an error message or the execution result
    */
  protected def runSingleTest(resourcePath: String, testName: String): Either[String, TestExecutionResult] =
    val suite = loadTestSuite(resourcePath)
    suite.tests.get(testName) match
      case Some(test) => executeTest(test)
      case None =>
        Left(
          s"Test '$testName' not found in $resourcePath. " +
            s"Available tests: ${suite.tests.keys.toList.sorted.mkString(", ")}"
        )

  /** Debugging utility: run every test in a suite loaded from an arbitrary filesystem path.
    *
    * Reads and decodes the JSON suite at `filePath` (a real file, not a classpath resource), then drives each test case
    * through the same ETH execution path as the batch `executeTest` (chainId=1, `forTimestamp` dispatch via
    * `networkToConfig`, Berlin→Prague fork filter applied by the concrete spec).
    *
    * Use when iterating on a vector file outside the test resources (e.g. a checked-out ethereum/tests submodule).
    *
    * @param filePath
    *   Absolute or working-directory-relative path to a JSON blockchain test file
    * @return
    *   One (testName, result) pair per test case in the file, in suite order
    */
  protected def runTestFile(filePath: String): Seq[(String, Either[String, TestExecutionResult])] =
    val source = Source.fromFile(filePath)
    val jsonString =
      try source.mkString
      finally source.close()

    parse(jsonString) match
      case Left(error) =>
        Seq(filePath -> Left(s"Failed to parse JSON at $filePath: ${error.getMessage}"))
      case Right(json) =>
        json.as[BlockchainTestSuite] match
          case Left(error) =>
            Seq(filePath -> Left(s"Failed to decode test suite at $filePath: ${error.getMessage}"))
          case Right(suite) =>
            suite.tests.toSeq.map { case (testName, test) => testName -> executeTest(test) }

  /** Load a test suite from a resource path */
  def loadTestSuite(resourcePath: String): BlockchainTestSuite =
    val suiteIO = EthereumTestsAdapter.loadTestSuite(resourcePath)
    suiteIO.unsafeRunSync()

  /** Set up initial state for a test */
  def setupTestState(test: BlockchainTest): Either[String, InMemoryWorldStateProxy] =
    EthereumTestExecutor.setupInitialStateForTest(test)

  /** Parse address from hex string */
  def parseAddress(hex: String): com.chipprbots.ethereum.domain.Address =
    import org.apache.pekko.util.ByteString
    val cleaned = if hex.startsWith("0x") then hex.substring(2) else hex
    val bytes = org.bouncycastle.util.encoders.Hex.decode(cleaned)
    com.chipprbots.ethereum.domain.Address(ByteString(bytes))

  /** Execute a complete test including block execution and post-state validation */
  def executeTest(test: BlockchainTest): Either[String, TestExecutionResult] =
    EthereumTestExecutor.executeTest(test, baseBlockchainConfig, unifiedLayout)

  /** Whether this spec's fixtures run on the unified state layout, the guard on (spec 018 T117):
    * `FUKUII_ETHTEST_LAYOUT=unified`, or a spec that says so.
    */
  protected def unifiedLayout: Boolean = EthereumTestHelper.unifiedByDefault
