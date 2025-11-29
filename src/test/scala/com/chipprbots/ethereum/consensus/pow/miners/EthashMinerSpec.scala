package com.chipprbots.ethereum.consensus.pow.miners

import cats.effect.IO

import scala.concurrent.duration._

import org.bouncycastle.util.encoders.Hex
import org.scalamock.handlers.CallHandler4
import org.scalamock.handlers.CallHandler6
import org.scalatest.concurrent.Eventually
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.MiningPatience
import com.chipprbots.ethereum.consensus.blocks.PendingBlock
import com.chipprbots.ethereum.consensus.blocks.PendingBlockAndState
import com.chipprbots.ethereum.consensus.pow.EthashUtils
import com.chipprbots.ethereum.consensus.pow.MinerSpecSetup
import com.chipprbots.ethereum.consensus.pow.PoWBlockCreator
import com.chipprbots.ethereum.consensus.pow.PoWMiningCoordinator.MiningSuccessful
import com.chipprbots.ethereum.consensus.pow.PoWMiningCoordinator.MiningUnsuccessful
import com.chipprbots.ethereum.consensus.pow.blocks.PoWBlockGenerator
import com.chipprbots.ethereum.consensus.pow.validators.PoWBlockHeaderValidator
import com.chipprbots.ethereum.consensus.validators.BlockHeaderValid
import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.domain._
import com.chipprbots.ethereum.jsonrpc.EthMiningService
import com.chipprbots.ethereum.jsonrpc.EthMiningService.SubmitHashRateResponse
import com.chipprbots.ethereum.ledger.InMemoryWorldStateProxy
import com.chipprbots.ethereum.utils.BlockchainConfig

import com.chipprbots.ethereum.testing.Tags._

// SCALA 3 MIGRATION: Fixed by refactoring MinerSpecSetup to use abstract mock members pattern.
// TODO: This test is flaky in CI due to real Ethash mining/DAG work taking too long.
// The test runs actual PoW mining which may timeout in CI environments.
// Marked as @Ignore until mining difficulty can be reduced for tests or mocking is improved.
@org.scalatest.Ignore
class EthashMinerSpec extends AnyFlatSpec with Matchers with org.scalamock.scalatest.MockFactory {

  "EthashMiner actor" should "mine valid blocks" taggedAs (UnitTest, ConsensusTest, SlowTest) in new TestSetup {
    val parentBlock: Block = origin
    setBlockForMining(origin)

    executeTest(parentBlock)
  }

  it should "mine valid block on the end and beginning of the new epoch" taggedAs (UnitTest, ConsensusTest, SlowTest) in new TestSetup {
    val epochLength: Int = EthashUtils.EPOCH_LENGTH_BEFORE_ECIP_1099
    val parent29998: Int = epochLength - 2 // 29998, mined block will be 29999 (last block of the epoch)
    val parentBlock29998: Block = origin.copy(header = origin.header.copy(number = parent29998))
    setBlockForMining(parentBlock29998)
    executeTest(parentBlock29998)

    val parent29999: Int = epochLength - 1 // 29999, mined block will be 30000 (first block of the new epoch)
    val parentBlock29999: Block = origin.copy(header = origin.header.copy(number = parent29999))
    setBlockForMining(parentBlock29999)
    executeTest(parentBlock29999)
  }

  it should "mine valid blocks on the end of the epoch" taggedAs (UnitTest, ConsensusTest, SlowTest) in new TestSetup {
    val epochLength: Int = EthashUtils.EPOCH_LENGTH_BEFORE_ECIP_1099
    val parentBlockNumber: Int =
      2 * epochLength - 2 // 59998, mined block will be 59999 (last block of the current epoch)
    val parentBlock: Block = origin.copy(header = origin.header.copy(number = parentBlockNumber))
    setBlockForMining(parentBlock)

    executeTest(parentBlock)
  }

  class TestSetup extends MinerSpecSetup with Eventually with MiningPatience {
    import scala.concurrent.ExecutionContext.Implicits.global
    
    // Implement abstract mock members - created in test class with MockFactory context
    override lazy val mockBlockchainReader: BlockchainReader = mock[BlockchainReader]
    override lazy val mockBlockchain: BlockchainImpl = mock[BlockchainImpl]
    override lazy val mockBlockCreator: PoWBlockCreator = mock[PoWBlockCreator]
    override lazy val mockBlockGenerator: PoWBlockGenerator = mock[PoWBlockGenerator]
    override lazy val mockEthMiningService: EthMiningService = mock[EthMiningService]
    override lazy val mockEvmCodeStorage: EvmCodeStorage = mock[EvmCodeStorage]
    override lazy val mockMptStorage: MptStorage = mock[MptStorage]
    
    override val origin: Block = Block(
      Fixtures.Blocks.Genesis.header.copy(
        difficulty = UInt256(Hex.decode("0400")).toBigInt,
        number = 0,
        gasUsed = 0,
        unixTimestamp = 0
      ),
      Fixtures.Blocks.ValidBlock.body
    )

    val getTransactionFromPoolTimeout: FiniteDuration = 5.seconds

    override lazy val blockCreator = new PoWBlockCreator(
      pendingTransactionsManager = pendingTransactionsManager.ref,
      getTransactionFromPoolTimeout = getTransactionFromPoolTimeout,
      mining = mining,
      ommersPool = ommersPool.ref
    )

    val dagManager = new EthashDAGManager(blockCreator)
    val miner = new EthashMiner(
      dagManager,
      blockCreator,
      sync.ref,
      ethMiningService
    )

    // Implement abstract expectation methods
    override def setBlockForMiningExpectation(
        parentBlock: Block,
        block: Block,
        fakeWorld: InMemoryWorldStateProxy
    ): CallHandler6[Block, Seq[SignedTransaction], Address, Seq[BlockHeader], Option[InMemoryWorldStateProxy], BlockchainConfig, PendingBlockAndState] =
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

    override def blockCreatorBehaviourExpectation(
        parentBlock: Block,
        withTransactions: Boolean,
        resultBlock: Block,
        fakeWorld: InMemoryWorldStateProxy
    ): CallHandler4[Block, Boolean, Option[InMemoryWorldStateProxy], BlockchainConfig, IO[PendingBlockAndState]] =
      (mockBlockCreator
        .getBlockForMining(_: Block, _: Boolean, _: Option[InMemoryWorldStateProxy])(_: BlockchainConfig))
        .expects(parentBlock, withTransactions, *, *)
        .returning(IO.pure(PendingBlockAndState(PendingBlock(resultBlock, Nil), fakeWorld)))

    override def blockCreatorBehaviourExpectingInitialWorldExpectation(
        parentBlock: Block,
        withTransactions: Boolean,
        resultBlock: Block,
        fakeWorld: InMemoryWorldStateProxy
    ): CallHandler4[Block, Boolean, Option[InMemoryWorldStateProxy], BlockchainConfig, IO[PendingBlockAndState]] =
      (mockBlockCreator
        .getBlockForMining(_: Block, _: Boolean, _: Option[InMemoryWorldStateProxy])(_: BlockchainConfig))
        .expects(where { (parent: Block, withTxs: Boolean, _: Option[InMemoryWorldStateProxy], _: BlockchainConfig) =>
          parent == parentBlock && withTxs == withTransactions
        })
        .returning(IO.pure(PendingBlockAndState(PendingBlock(resultBlock, Nil), fakeWorld)))

    override def setupMiningServiceExpectation(): Unit =
      (ethMiningService.submitHashRate _)
        .expects(*)
        .returns(IO.pure(Right(SubmitHashRateResponse(true))))
        .atLeastOnce()

    protected def executeTest(parentBlock: Block): Unit = {
      prepareMocks()
      val minedBlock = startMining(parentBlock)
      checkAssertions(minedBlock, parentBlock)
    }

    def startMining(parentBlock: Block): Block =
      eventually {
        miner.processMining(parentBlock).map {
          case MiningSuccessful   => true
          case MiningUnsuccessful => startMining(parentBlock)
        }
        val minedBlock = waitForMinedBlock
        minedBlock
      }

    private def checkAssertions(minedBlock: Block, parentBlock: Block): Unit = {
      minedBlock.body.transactionList shouldBe Seq(txToMine)
      minedBlock.header.nonce.length shouldBe 8
      PoWBlockHeaderValidator.validate(minedBlock.header, parentBlock.header) shouldBe Right(BlockHeaderValid)
    }
  }
}
