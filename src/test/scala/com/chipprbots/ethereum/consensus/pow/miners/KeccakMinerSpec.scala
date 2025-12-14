package com.chipprbots.ethereum.consensus.pow.miners

import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit

import cats.effect.IO

import scala.concurrent.duration._

import org.scalamock.handlers.CallHandler4
import org.scalamock.handlers.CallHandler6
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Timeouts
import com.chipprbots.ethereum.consensus.blocks.PendingBlock
import com.chipprbots.ethereum.consensus.blocks.PendingBlockAndState
import com.chipprbots.ethereum.consensus.mining.CoinbaseProvider
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
import com.chipprbots.ethereum.jsonrpc.EthInfoService
import com.chipprbots.ethereum.jsonrpc.EthMiningService
import com.chipprbots.ethereum.jsonrpc.EthMiningService.SubmitHashRateResponse
import com.chipprbots.ethereum.ledger.InMemoryWorldStateProxy
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.Config

import com.chipprbots.ethereum.testing.Tags._

// SCALA 3 MIGRATION: Fixed by refactoring MinerSpecSetup to use abstract mock members pattern.
class KeccakMinerSpec extends AnyFlatSpec with Matchers with org.scalamock.scalatest.MockFactory {
  "KeccakMiner actor" should "mine valid blocks" taggedAs (UnitTest, ConsensusTest, SlowTest) in new TestSetup {
    val parentBlock: Block = origin
    setBlockForMining(parentBlock)

    executeTest(parentBlock)
  }

  it should "mine valid block on the beginning of the new epoch" taggedAs (
    UnitTest,
    ConsensusTest,
    SlowTest
  ) in new TestSetup {
    val epochLength: Int = EthashUtils.EPOCH_LENGTH_BEFORE_ECIP_1099
    val parentBlockNumber: Int =
      epochLength - 1 // 29999, mined block will be 30000 (first block of the new epoch)
    val parentBlock: Block = getParentBlock(parentBlockNumber)
    setBlockForMining(parentBlock)

    executeTest(parentBlock)
  }

  it should "mine valid blocks on the end of the epoch" taggedAs (UnitTest, ConsensusTest, SlowTest) in new TestSetup {
    val epochLength: Int = EthashUtils.EPOCH_LENGTH_BEFORE_ECIP_1099
    val parentBlockNumber: Int =
      2 * epochLength - 2 // 59998, mined block will be 59999 (last block of the current epoch)
    val parentBlock: Block = getParentBlock(parentBlockNumber)
    setBlockForMining(parentBlock)

    executeTest(parentBlock)
  }

  class TestSetup extends ScalaTestWithActorTestKit with MinerSpecSetup {
    import scala.concurrent.ExecutionContext.Implicits.global

    // Implement abstract mock members - created in test class with MockFactory context
    override lazy val mockBlockchainReader: BlockchainReader = mock[BlockchainReader]
    override lazy val mockBlockchain: BlockchainImpl = mock[BlockchainImpl]
    override lazy val mockBlockCreator: PoWBlockCreator = mock[PoWBlockCreator]
    override lazy val mockBlockGenerator: PoWBlockGenerator = mock[PoWBlockGenerator]
    override lazy val mockEthMiningService: EthMiningService = mock[EthMiningService]
    override lazy val mockEvmCodeStorage: EvmCodeStorage = mock[EvmCodeStorage]
    override lazy val mockMptStorage: MptStorage = mock[MptStorage]

    implicit private val durationTimeout: Duration = Timeouts.miningTimeout

    implicit override lazy val blockchainConfig: BlockchainConfig = Config.blockchains.blockchainConfig
      .withUpdatedForkBlocks(_.copy(ecip1049BlockNumber = Some(0)))

    val ethService: EthInfoService = mock[EthInfoService]
    val getTransactionFromPoolTimeout: FiniteDuration = 5.seconds

    val coinbaseProvider = new CoinbaseProvider(miningConfig.coinbase)

    override lazy val blockCreator = new PoWBlockCreator(
      pendingTransactionsManager = pendingTransactionsManager.ref,
      getTransactionFromPoolTimeout = getTransactionFromPoolTimeout,
      mining = mining,
      ommersPool = ommersPool.ref,
      coinbaseProvider = coinbaseProvider
    )

    val miner = new KeccakMiner(blockCreator, sync.ref, ethMiningService)

    // Implement abstract expectation methods
    override def setBlockForMiningExpectation(
        parentBlock: Block,
        block: Block,
        fakeWorld: InMemoryWorldStateProxy
    ): CallHandler6[Block, Seq[SignedTransaction], Address, Seq[BlockHeader], Option[
      InMemoryWorldStateProxy
    ], BlockchainConfig, PendingBlockAndState] =
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
