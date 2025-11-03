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

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.blockchain.sync.SyncProtocol
import com.chipprbots.ethereum.consensus.blocks.PendingBlock
import com.chipprbots.ethereum.consensus.blocks.PendingBlockAndState
import com.chipprbots.ethereum.consensus.mining.FullMiningConfig
import com.chipprbots.ethereum.consensus.mining.MiningConfigBuilder
import com.chipprbots.ethereum.consensus.mining.Protocol.NoAdditionalPoWData
import com.chipprbots.ethereum.consensus.pow.blocks.PoWBlockGenerator
import com.chipprbots.ethereum.consensus.pow.difficulty.EthashDifficultyCalculator
import com.chipprbots.ethereum.consensus.pow.validators.ValidatorsExecutor
import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.domain._
import com.chipprbots.ethereum.jsonrpc.EthMiningService
import com.chipprbots.ethereum.jsonrpc.EthMiningService.SubmitHashRateResponse
import com.chipprbots.ethereum.ledger.InMemoryWorldStateProxy
import com.chipprbots.ethereum.ledger.VMImpl
import com.chipprbots.ethereum.nodebuilder.BlockchainConfigBuilder
import com.chipprbots.ethereum.ommers.OmmersPool
import com.chipprbots.ethereum.transactions.PendingTransactionsManager
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.Config

trait MinerSpecSetup extends MiningConfigBuilder with BlockchainConfigBuilder {
  this: org.scalamock.scalatest.MockFactory =>
  implicit val classicSystem: ClassicSystem = ClassicSystem()
  implicit val runtime: IORuntime = IORuntime.global
  val parentActor: TestProbe = TestProbe()
  val sync: TestProbe = TestProbe()
  val ommersPool: TestProbe = TestProbe()
  val pendingTransactionsManager: TestProbe = TestProbe()

  val origin: Block = Block(Fixtures.Blocks.Genesis.header, Fixtures.Blocks.Genesis.body)

  val blockchainReader: BlockchainReader = mock[BlockchainReader]
  val blockchain: BlockchainImpl = mock[BlockchainImpl]
  val blockCreator: PoWBlockCreator = mock[PoWBlockCreator]
  val fakeWorld: InMemoryWorldStateProxy = createStubWorldStateProxy()
  val blockGenerator: PoWBlockGenerator = mock[PoWBlockGenerator]
  val ethMiningService: EthMiningService = mock[EthMiningService]
  val evmCodeStorage: EvmCodeStorage = mock[EvmCodeStorage]

  private def createStubWorldStateProxy(): InMemoryWorldStateProxy = {
    // Create a minimal stub instance for tests where the WorldStateProxy is just a placeholder
    val stubEvmCodeStorage = mock[EvmCodeStorage]
    val stubMptStorage = mock[com.chipprbots.ethereum.db.storage.MptStorage]
    InMemoryWorldStateProxy(
      stubEvmCodeStorage,
      stubMptStorage,
      _ => None,
      UInt256.Zero,
      ByteString.empty,
      noEmptyAccounts = false,
      ethCompatibleStorage = true
    )
  }

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
    val mantisConfig = Config.config
    val specificConfig = EthashConfig(mantisConfig)

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

  protected def setBlockForMining(parentBlock: Block, transactions: Seq[SignedTransaction] = Seq(txToMine)): Block = {
    val parentHeader: BlockHeader = parentBlock.header

    val block = Block(
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

    (blockGenerator
      .generateBlock(
        _: Block,
        _: Seq[SignedTransaction],
        _: Address,
        _: Seq[BlockHeader],
        _: Option[InMemoryWorldStateProxy]
      )(_: BlockchainConfig))
      .expects(parentBlock, Nil, miningConfig.coinbase, Nil, None, *)
      .returning(PendingBlockAndState(PendingBlock(block, Nil), fakeWorld))
      .atLeastOnce()

    block
  }

  private def calculateGasLimit(parentGas: UInt256): UInt256 = {
    val GasLimitBoundDivisor: Int = 1024

    val gasLimitDifference = parentGas / GasLimitBoundDivisor
    parentGas + gasLimitDifference - 1
  }

  protected def blockCreatorBehaviour(
      parentBlock: Block,
      withTransactions: Boolean,
      resultBlock: Block
  ): CallHandler4[Block, Boolean, Option[InMemoryWorldStateProxy], BlockchainConfig, IO[PendingBlockAndState]] =
    (blockCreator
      .getBlockForMining(_: Block, _: Boolean, _: Option[InMemoryWorldStateProxy])(_: BlockchainConfig))
      .expects(parentBlock, withTransactions, *, *)
      .returning(
        IO.pure(PendingBlockAndState(PendingBlock(resultBlock, Nil), fakeWorld))
      )
      .atLeastOnce()

  protected def blockCreatorBehaviourExpectingInitialWorld(
      parentBlock: Block,
      withTransactions: Boolean,
      resultBlock: Block
  ): CallHandler4[Block, Boolean, Option[InMemoryWorldStateProxy], BlockchainConfig, IO[PendingBlockAndState]] =
    (blockCreator
      .getBlockForMining(_: Block, _: Boolean, _: Option[InMemoryWorldStateProxy])(_: BlockchainConfig))
      .expects(where { (parent, withTxs, _, _) =>
        parent == parentBlock && withTxs == withTransactions
      })
      .returning(
        IO.pure(PendingBlockAndState(PendingBlock(resultBlock, Nil), fakeWorld))
      )
      .atLeastOnce()

  protected def prepareMocks(): Unit = {
    (ethMiningService.submitHashRate _)
      .expects(*)
      .returns(IO.pure(Right(SubmitHashRateResponse(true))))
      .atLeastOnce()

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
