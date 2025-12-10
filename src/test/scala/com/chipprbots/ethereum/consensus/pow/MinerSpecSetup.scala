package com.chipprbots.ethereum.consensus.pow

import org.apache.pekko.actor.ActorRef
import org.apache.pekko.actor.{ActorSystem => ClassicSystem}
import org.apache.pekko.testkit.TestActor
import org.apache.pekko.testkit.TestProbe
import org.apache.pekko.util.ByteString

import cats.effect.IO
import cats.effect.unsafe.IORuntime

import scala.concurrent.duration.Duration
import scala.concurrent.duration.FiniteDuration

import org.bouncycastle.util.encoders.Hex
import org.scalamock.handlers.CallHandler4
import org.scalamock.handlers.CallHandler6

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.blockchain.sync.SyncProtocol
import com.chipprbots.ethereum.consensus.blocks.PendingBlockAndState
import com.chipprbots.ethereum.consensus.mining.FullMiningConfig
import com.chipprbots.ethereum.consensus.mining.MiningConfigBuilder
import com.chipprbots.ethereum.consensus.mining.Protocol.NoAdditionalPoWData
import com.chipprbots.ethereum.consensus.pow.blocks.PoWBlockGenerator
import com.chipprbots.ethereum.consensus.pow.difficulty.EthashDifficultyCalculator
import com.chipprbots.ethereum.consensus.pow.validators.ValidatorsExecutor
import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.domain._
import com.chipprbots.ethereum.jsonrpc.EthMiningService
import com.chipprbots.ethereum.ledger.InMemoryWorldStateProxy
import com.chipprbots.ethereum.ledger.VMImpl
import com.chipprbots.ethereum.nodebuilder.BlockchainConfigBuilder
import com.chipprbots.ethereum.ommers.OmmersPool
import com.chipprbots.ethereum.transactions.PendingTransactionsManager
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.Config

// SCALA 3 MIGRATION: Refactored to use abstract mock members pattern.
// The test class (which extends MockFactory) provides the mock implementations.
// This avoids the self-type constraint issue where inner classes cannot satisfy MockFactory.
trait MinerSpecSetup extends MiningConfigBuilder with BlockchainConfigBuilder {
  // Abstract mock members - must be implemented by test class that has MockFactory
  def mockBlockchainReader: BlockchainReader
  def mockBlockchain: BlockchainImpl
  def mockBlockCreator: PoWBlockCreator
  def mockBlockGenerator: PoWBlockGenerator
  def mockEthMiningService: EthMiningService
  def mockEvmCodeStorage: EvmCodeStorage
  def mockMptStorage: MptStorage

  // Concrete lazy vals that use the abstract mocks
  lazy val blockchainReader: BlockchainReader = mockBlockchainReader
  lazy val blockchain: BlockchainImpl = mockBlockchain
  lazy val blockCreator: PoWBlockCreator = mockBlockCreator
  lazy val blockGenerator: PoWBlockGenerator = mockBlockGenerator
  lazy val ethMiningService: EthMiningService = mockEthMiningService
  lazy val evmCodeStorage: EvmCodeStorage = mockEvmCodeStorage

  // ACTOR SYSTEM HANDLING:
  // Subclasses that extend TestKit or ScalaTestWithActorTestKit should override this
  // to provide their test kit's actor system instead of creating a new one.
  // This prevents actor system conflicts between the test kit and MinerSpecSetup.
  implicit def classicSystem: ClassicSystem = _defaultClassicSystem

  // Lazy val to avoid creating an actor system until actually needed.
  // Tests that override classicSystem won't trigger this.
  private lazy val _defaultClassicSystem: ClassicSystem = ClassicSystem("MinerSpecSetup-DefaultSystem")

  implicit val runtime: IORuntime = IORuntime.global
  lazy val parentActor: TestProbe = TestProbe()(classicSystem)
  lazy val sync: TestProbe = TestProbe()(classicSystem)
  lazy val ommersPool: TestProbe = TestProbe()(classicSystem)
  lazy val pendingTransactionsManager: TestProbe = TestProbe()(classicSystem)

  val origin: Block = Block(Fixtures.Blocks.Genesis.header, Fixtures.Blocks.Genesis.body)

  lazy val fakeWorld: InMemoryWorldStateProxy = createStubWorldStateProxy()

  private def createStubWorldStateProxy(): InMemoryWorldStateProxy =
    // Create a minimal stub instance for tests where the WorldStateProxy is just a placeholder
    InMemoryWorldStateProxy(
      mockEvmCodeStorage,
      mockMptStorage,
      _ => None,
      UInt256.Zero,
      ByteString.empty,
      noEmptyAccounts = false,
      ethCompatibleStorage = true
    )

  lazy val vm: VMImpl = new VMImpl

  val txToMine: SignedTransaction = SignedTransaction(
    tx = LegacyTransaction(
      nonce = BigInt("438553"),
      gasPrice = BigInt("20000000000"),
      gasLimit = BigInt("50000"),
      receivingAddress = Address(ByteString(Hex.decode("3435be928d783b7c48a2c3109cba0d97d680747a"))),
      value = BigInt("108516826677274384"),
      payload = ByteString.empty
    ),
    pointSign = 0x9d.toByte,
    signatureRandom = ByteString(Hex.decode("beb8226bdb90216ca29967871a6663b56bdd7b86cf3788796b52fd1ea3606698")),
    signature = ByteString(Hex.decode("2446994156bc1780cb5806e730b171b38307d5de5b9b0d9ad1f9de82e00316b5"))
  )

  lazy val mining: PoWMining = buildPoWConsensus().withBlockGenerator(blockGenerator)
  implicit override lazy val blockchainConfig: BlockchainConfig = Config.blockchains.blockchainConfig
  lazy val difficultyCalc = EthashDifficultyCalculator
  val blockForMiningTimestamp: Long = System.currentTimeMillis()

  protected def getParentBlock(parentBlockNumber: Int): Block =
    origin.copy(header = origin.header.copy(number = parentBlockNumber))

  def buildPoWConsensus(): PoWMining = {
    val fukuiiConfig = Config.config
    val specificConfig = EthashConfig(fukuiiConfig)

    val fullConfig = FullMiningConfig(miningConfig, specificConfig)

    val validators = ValidatorsExecutor(miningConfig.protocol)

    val additionalPoWData = NoAdditionalPoWData
    PoWMining(
      vm,
      evmCodeStorage,
      blockchain,
      blockchainReader,
      fullConfig,
      validators,
      additionalPoWData
    )
  }

  // Abstract method for setting up block generation expectations
  // Must be implemented by the test class with MockFactory context
  def setBlockForMiningExpectation(
      parentBlock: Block,
      block: Block,
      fakeWorld: InMemoryWorldStateProxy
  ): CallHandler6[Block, Seq[SignedTransaction], Address, Seq[BlockHeader], Option[
    InMemoryWorldStateProxy
  ], BlockchainConfig, PendingBlockAndState]

  /** Creates a block for mining and optionally sets up expectations. This is the main helper for tests that need mocked
    * block generation. Tests that use MockedMiner with a fully mocked blockCreator should use `createBlockForMining`
    * instead to avoid unsatisfied expectations on blockGenerator.
    *
    * NOTE: The expectation is set to anyNumberOfTimes() to avoid failures when tests crash before actually mining
    * (e.g., actor initialization failures).
    */
  protected def setBlockForMining(parentBlock: Block, transactions: Seq[SignedTransaction] = Seq(txToMine)): Block = {
    val block = createBlockForMining(parentBlock, transactions)
    setBlockForMiningExpectation(parentBlock, block, fakeWorld)
      .anyNumberOfTimes()
    block
  }

  /** Creates a block for mining WITHOUT setting up any mock expectations. Use this when the test will set up its own
    * mocks (e.g., MockedMiner tests where blockCreator is mocked and blockGenerator is never called).
    */
  protected def createBlockForMining(
      parentBlock: Block,
      transactions: Seq[SignedTransaction] = Seq(txToMine)
  ): Block = {
    val parentHeader: BlockHeader = parentBlock.header

    Block(
      BlockHeader(
        parentHash = parentHeader.hash,
        ommersHash = ByteString(Hex.decode("1dcc4de8dec75d7aab85b567b6ccd41ad312451b948a7413f0a142fd40d49347")),
        beneficiary = miningConfig.coinbase.bytes,
        stateRoot = parentHeader.stateRoot,
        transactionsRoot = parentHeader.transactionsRoot,
        receiptsRoot = parentHeader.receiptsRoot,
        logsBloom = parentHeader.logsBloom,
        difficulty = difficultyCalc.calculateDifficulty(1, blockForMiningTimestamp, parentHeader),
        number = parentHeader.number + 1,
        gasLimit = calculateGasLimit(UInt256(parentHeader.gasLimit)),
        gasUsed = BigInt(0),
        unixTimestamp = blockForMiningTimestamp,
        extraData = miningConfig.headerExtraData,
        mixHash = ByteString.empty,
        nonce = ByteString.empty
      ),
      BlockBody(transactions, Nil)
    )
  }

  private def calculateGasLimit(parentGas: UInt256): UInt256 = {
    val GasLimitBoundDivisor: Int = 1024

    val gasLimitDifference = parentGas / GasLimitBoundDivisor
    parentGas + gasLimitDifference - 1
  }

  // Abstract method for block creator behavior expectations
  def blockCreatorBehaviourExpectation(
      parentBlock: Block,
      withTransactions: Boolean,
      resultBlock: Block,
      fakeWorld: InMemoryWorldStateProxy
  ): CallHandler4[Block, Boolean, Option[InMemoryWorldStateProxy], BlockchainConfig, IO[PendingBlockAndState]]

  protected def blockCreatorBehaviour(
      parentBlock: Block,
      withTransactions: Boolean,
      resultBlock: Block
  ): CallHandler4[Block, Boolean, Option[InMemoryWorldStateProxy], BlockchainConfig, IO[PendingBlockAndState]] =
    blockCreatorBehaviourExpectation(parentBlock, withTransactions, resultBlock, fakeWorld)
      .atLeastOnce()

  // Abstract method for block creator behavior with initial world expectations
  def blockCreatorBehaviourExpectingInitialWorldExpectation(
      parentBlock: Block,
      withTransactions: Boolean,
      resultBlock: Block,
      fakeWorld: InMemoryWorldStateProxy
  ): CallHandler4[Block, Boolean, Option[InMemoryWorldStateProxy], BlockchainConfig, IO[PendingBlockAndState]]

  protected def blockCreatorBehaviourExpectingInitialWorld(
      parentBlock: Block,
      withTransactions: Boolean,
      resultBlock: Block
  ): CallHandler4[Block, Boolean, Option[InMemoryWorldStateProxy], BlockchainConfig, IO[PendingBlockAndState]] =
    blockCreatorBehaviourExpectingInitialWorldExpectation(parentBlock, withTransactions, resultBlock, fakeWorld)
      .atLeastOnce()

  // Abstract method for prepareMocks expectations
  def setupMiningServiceExpectation(): Unit

  protected def prepareMocks(): Unit = {
    setupMiningServiceExpectation()

    ommersPool.setAutoPilot { (sender: ActorRef, _: Any) =>
      sender ! OmmersPool.Ommers(Nil)
      TestActor.KeepRunning
    }

    pendingTransactionsManager.setAutoPilot { (sender: ActorRef, _: Any) =>
      sender ! PendingTransactionsManager.PendingTransactionsResponse(Nil)
      TestActor.KeepRunning
    }
  }

  protected def waitForMinedBlock(implicit timeout: Duration): Block =
    sync.expectMsgPF[Block](timeout) { case m: SyncProtocol.MinedBlock =>
      m.block
    }

  protected def expectNoNewBlockMsg(timeout: FiniteDuration): Unit =
    sync.expectNoMessage(timeout)
}
