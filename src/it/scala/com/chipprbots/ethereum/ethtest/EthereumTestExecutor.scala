package com.chipprbots.ethereum.ethtest

import org.apache.pekko.util.ByteString
import com.chipprbots.ethereum.db.components.EphemDataSourceComponent
import com.chipprbots.ethereum.db.storage._
import com.chipprbots.ethereum.domain._
import com.chipprbots.ethereum.ledger.InMemoryWorldStateProxy
import com.chipprbots.ethereum.utils.{BlockchainConfig, Config}

/** Executes ethereum/tests blockchain tests
  *
  * Provides test execution infrastructure for running JSON blockchain tests from the official ethereum/tests
  * repository.
  */
object EthereumTestExecutor {

  /** Setup initial state for a test (simplified version for testing)
    *
    * @param test
    *   The blockchain test
    * @return
    *   Either error message or initial world state
    */
  def setupInitialStateForTest(
      test: BlockchainTest
  ): Either[String, InMemoryWorldStateProxy] = {
    given BlockchainConfig = TestConverter.networkToConfig(test.network, Config.blockchains.blockchainConfig)
    setupInitialState(test.pre)
  }

  /** Execute a single blockchain test
    *
    * @param test
    *   The blockchain test to execute
    * @param baseConfig
    *   Base blockchain configuration
    * @return
    *   Either error message or success
    */
  def executeTest(
      test: BlockchainTest,
      baseConfig: BlockchainConfig
  ): Either[String, TestExecutionResult] = {
    given BlockchainConfig = TestConverter.networkToConfig(test.network, baseConfig)

    // Use the helper which will handle both state setup and block execution
    // using the same storage instance
    val helper = new EthereumTestHelper(using summon[BlockchainConfig])

    for {
      finalWorld <- helper.setupAndExecuteTest(test.pre, test.blocks, test.genesisBlockHeader)
      _ <- validatePostState(test.postState, finalWorld)
    } yield TestExecutionResult(
      network = test.network,
      blocksExecuted = test.blocks.size,
      finalStateRoot = finalWorld.stateRootHash
    )
  }

  /** Set up initial state from pre-state in test */
  private def setupInitialState(
      preState: Map[String, AccountState]
  )(using blockchainConfig: BlockchainConfig): Either[String, InMemoryWorldStateProxy] =
    try {
      // Create in-memory storage for the test
      val dataSource = new EphemDataSourceComponent {}.dataSource
      val mptStorage: MptStorage = new SerializingMptStorage(new ArchiveNodeStorage(new NodeStorage(dataSource)))
      val evmCodeStorage = new EvmCodeStorage(dataSource)

      // Create initial world state
      var world = InMemoryWorldStateProxy(
        evmCodeStorage = evmCodeStorage,
        mptStorage = mptStorage,
        getBlockHashByNumber = (_: BigInt) => None,
        accountStartNonce = blockchainConfig.accountStartNonce,
        stateRootHash = Account.EmptyStorageRootHash,
        noEmptyAccounts = false,
        ethCompatibleStorage = blockchainConfig.ethCompatibleStorage
      )

      // Set up each account
      preState.foreach { case (addressHex, accountState) =>
        val address = Address(ByteString(parseHex(addressHex)))
        val balance = UInt256(parseBigInt(accountState.balance))
        val nonce = UInt256(parseBigInt(accountState.nonce))
        val code = ByteString(parseHex(accountState.code))

        // Create account
        val account = Account(
          nonce = nonce,
          balance = balance,
          storageRoot = Account.EmptyStorageRootHash,
          codeHash = Account.EmptyCodeHash
        )

        // Save account
        world = world.saveAccount(address, account)

        // Save code if present
        if (code.nonEmpty) {
          world = world.saveCode(address, code)
        }

        // Save storage if present
        accountState.storage.foreach { case (keyHex, valueHex) =>
          val key = parseBigInt(keyHex)
          val value = parseBigInt(valueHex)
          val storage = world.getStorage(address)
          val newStorage = storage.store(key, value)
          world = world.saveStorage(address, newStorage)
        }
      }

      // Persist the initial state
      val persistedWorld = InMemoryWorldStateProxy.persistState(world)
      Right(persistedWorld)
    } catch {
      case e: Exception => Left(s"Failed to setup initial state: ${e.getMessage}")
    }

  /** Validate final state matches expected post-state */
  private def validatePostState(
      expectedPostState: Map[String, AccountState],
      finalWorld: InMemoryWorldStateProxy
  ): Either[String, Unit] = {
    import scala.util.boundary, boundary.break

    try
      boundary {
        expectedPostState.foreach { case (addressHex, expectedAccount) =>
          val address = Address(ByteString(parseHex(addressHex)))
          val account = finalWorld.getAccount(address).getOrElse(Account.empty())

          val expectedBalance = UInt256(parseBigInt(expectedAccount.balance))
          val expectedNonce = UInt256(parseBigInt(expectedAccount.nonce))

          if (account.balance != expectedBalance) {
            break(Left(s"Balance mismatch for $addressHex: expected $expectedBalance, got ${account.balance}"))
          }

          if (account.nonce != expectedNonce) {
            break(Left(s"Nonce mismatch for $addressHex: expected $expectedNonce, got ${account.nonce}"))
          }

          // Validate storage
          expectedAccount.storage.foreach { case (keyHex, valueHex) =>
            val key = parseBigInt(keyHex)
            val expectedValue = parseBigInt(valueHex)
            val storage = finalWorld.getStorage(address)
            val actualValue = storage.load(key)

            if (actualValue != expectedValue) {
              break(Left(s"Storage mismatch for $addressHex at $key: expected $expectedValue, got $actualValue"))
            }
          }
        }

        Right(())
      }
    catch {
      case e: Exception => Left(s"Failed to validate post-state: ${e.getMessage}")
    }
  }

  /** Parse hex string to byte array */
  private def parseHex(hex: String): Array[Byte] = {
    val cleaned = if (hex.startsWith("0x")) hex.substring(2) else hex
    if (cleaned.isEmpty) Array.empty[Byte]
    else org.bouncycastle.util.encoders.Hex.decode(cleaned)
  }

  /** Parse hex or decimal string to BigInt */
  private def parseBigInt(value: String): BigInt =
    if (value.startsWith("0x")) {
      BigInt(value.substring(2), 16)
    } else {
      BigInt(value)
    }
}

/** Result of test execution */
case class TestExecutionResult(
    network: String,
    blocksExecuted: Int,
    finalStateRoot: ByteString
)
