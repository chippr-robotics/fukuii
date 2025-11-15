package com.chipprbots.ethereum.ethtest

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import com.chipprbots.ethereum.utils.Config

/** Base spec for running ethereum/tests blockchain tests
  *
  * Provides infrastructure for loading and executing JSON blockchain tests
  * from the official ethereum/tests repository.
  *
  * Usage:
  * {{{
  * class MyEthereumTest extends EthereumTestsSpec {
  *   it should "pass simple value transfer test" in {
  *     runTestFile("/BlockchainTests/GeneralStateTests/stExample/add11.json")
  *   }
  * }
  * }}}
  *
  * See https://github.com/ethereum/tests for test repository structure.
  */
abstract class EthereumTestsSpec extends AnyFlatSpec with Matchers {

  given IORuntime = IORuntime.global

  // Use blockchain config from Config
  lazy val baseBlockchainConfig = Config.blockchains.blockchainConfig

  /** Run all test cases in a JSON test file
    *
    * @param resourcePath Path to JSON test file in src/it/resources
    */
  def runTestFile(resourcePath: String): Unit = {
    val suiteIO = EthereumTestsAdapter.loadTestSuite(resourcePath)
    val suite = suiteIO.unsafeRunSync()

    suite.tests.foreach { case (testName, test) =>
      info(s"Running test case: $testName")
      runSingleTest(testName, test)
    }
  }

  /** Run a single blockchain test case
    *
    * @param testName Name of the test case
    * @param test Test data
    */
  def runSingleTest(testName: String, test: BlockchainTest): Unit = {
    info(s"  Network: ${test.network}")
    info(s"  Pre-state accounts: ${test.pre.size}")
    info(s"  Blocks to execute: ${test.blocks.size}")
    info(s"  Expected post-state accounts: ${test.postState.size}")

    // TODO: Execute the test using BlockExecution infrastructure
    // For now, just validate we can parse and set up state
    val setupResult = EthereumTestExecutor.setupInitialStateForTest(test)
    
    setupResult match {
      case Right(world) =>
        info(s"  ✓ Initial state setup successful")
        info(s"  State root: ${org.bouncycastle.util.encoders.Hex.toHexString(world.stateRootHash.toArray)}")
        
      case Left(error) =>
        fail(s"Failed to setup initial state: $error")
    }
  }

  /** Load a test suite from a resource path */
  def loadTestSuite(resourcePath: String): BlockchainTestSuite = {
    val suiteIO = EthereumTestsAdapter.loadTestSuite(resourcePath)
    suiteIO.unsafeRunSync()
  }

  /** Set up initial state for a test */
  def setupTestState(test: BlockchainTest) = {
    EthereumTestExecutor.setupInitialStateForTest(test)
  }

  /** Parse address from hex string */
  def parseAddress(hex: String): com.chipprbots.ethereum.domain.Address = {
    import org.apache.pekko.util.ByteString
    val cleaned = if (hex.startsWith("0x")) hex.substring(2) else hex
    val bytes = org.bouncycastle.util.encoders.Hex.decode(cleaned)
    com.chipprbots.ethereum.domain.Address(ByteString(bytes))
  }

  /** Execute a complete test including block execution and post-state validation */
  def executeTest(test: BlockchainTest): Either[String, TestExecutionResult] = {
    EthereumTestExecutor.executeTest(test, baseBlockchainConfig)
  }
}
