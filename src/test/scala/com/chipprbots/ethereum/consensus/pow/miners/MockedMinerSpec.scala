package com.chipprbots.ethereum.consensus.pow.miners

import org.apache.pekko.actor.{ActorSystem => ClassicSystem}
import org.apache.pekko.testkit.TestActorRef
import org.apache.pekko.testkit.TestKit

import cats.effect.IO

import scala.concurrent.duration._

import org.scalamock.handlers.CallHandler4
import org.scalamock.handlers.CallHandler6
import org.scalatest._
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import com.chipprbots.ethereum.WithActorSystemShutDown
import com.chipprbots.ethereum.consensus.blocks.PendingBlock
import com.chipprbots.ethereum.consensus.blocks.PendingBlockAndState
import com.chipprbots.ethereum.consensus.pow.MinerSpecSetup
import com.chipprbots.ethereum.consensus.pow.PoWBlockCreator
import com.chipprbots.ethereum.consensus.pow.blocks.PoWBlockGenerator
import com.chipprbots.ethereum.consensus.pow.miners.MockedMiner.MineBlocks
import com.chipprbots.ethereum.consensus.pow.miners.MockedMiner.MockedMinerResponses._
import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.BlockchainImpl
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.domain.SignedTransaction
import com.chipprbots.ethereum.domain._
import com.chipprbots.ethereum.jsonrpc.EthMiningService
import com.chipprbots.ethereum.jsonrpc.EthMiningService.SubmitHashRateResponse
import com.chipprbots.ethereum.ledger.InMemoryWorldStateProxy
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.ByteStringUtils

import com.chipprbots.ethereum.testing.Tags._

// SCALA 3 MIGRATION: Fixed by refactoring MinerSpecSetup to use abstract mock members pattern.
// ACTOR SYSTEM FIX: TestSetup now overrides classicSystem to use TestKit's actor system,
// preventing actor system conflicts between TestKit and MinerSpecSetup.
class MockedMinerSpec
    extends TestKit(ClassicSystem("MockedPowMinerSpec_System"))
    with AnyWordSpecLike
    with Matchers
    with WithActorSystemShutDown
    with org.scalamock.scalatest.MockFactory {

  implicit private val timeout: Duration = 1.minute

  "MockedPowMiner actor" should {
    "not mine blocks" when {
      "there is no request" taggedAs (UnitTest, ConsensusTest) in new TestSetup {
        expectNoNewBlockMsg(noMessageTimeOut)
      }
    }

    "not mine block and return MinerNotSupport msg" when {
      "the request comes before miner started" taggedAs (UnitTest, ConsensusTest) in new TestSetup {
        val msg = MineBlocks(1, false, None)
        sendToMiner(msg)
        expectNoNewBlockMsg(noMessageTimeOut)
        parentActor.expectMsg(MinerNotSupported(msg))
      }
    }

    "stop mining in case of error" when {
      "Unable to get block for mining" taggedAs (UnitTest, ConsensusTest) in new TestSetup {
        val parent = origin
        val bfm1 = createBlockForMining(parent, Seq.empty)

        blockCreatorBehaviour(parent, withTransactions = false, bfm1)

        (mockBlockCreator
          .getBlockForMining(_: Block, _: Boolean, _: Option[InMemoryWorldStateProxy])(_: BlockchainConfig))
          .expects(bfm1, false, *, *)
          .returning(
            IO.raiseError(new RuntimeException("error"))
          )
          .atLeastOnce()

        withStartedMiner {
          sendToMiner(MineBlocks(2, withTransactions = false, None))

          parentActor.expectMsg(MiningOrdered)

          val block1 = waitForMinedBlock

          expectNoNewBlockMsg(noMessageTimeOut)

          parentActor.expectNoMessage(noMessageTimeOut)

          validateBlock(block1, parent)
        }
      }

      "Unable to get parent block for mining" taggedAs (UnitTest, ConsensusTest) in new TestSetup {
        val parentHash = origin.hash

        val errorMsg = s"Unable to get parent block with hash ${ByteStringUtils.hash2string(parentHash)} for mining"

        (blockchainReader.getBlockByHash _).expects(parentHash).returns(None)

        withStartedMiner {
          sendToMiner(MineBlocks(2, withTransactions = false, Some(parentHash)))

          expectNoNewBlockMsg(noMessageTimeOut)

          parentActor.expectMsg(MiningError(errorMsg))
        }
      }
    }

    "return MinerIsWorking to requester" when {
      "miner is working during next mine request" taggedAs (UnitTest, ConsensusTest) in new TestSetup {
        val parent = origin
        val bfm = createBlockForMining(parent, Seq.empty)

        blockCreatorBehaviour(parent, withTransactions = false, bfm)

        withStartedMiner {
          sendToMiner(MineBlocks(1, withTransactions = false, None))
          parentActor.expectMsg(MiningOrdered)
          sendToMiner(MineBlocks(1, withTransactions = false, None))
          parentActor.expectMsg(MinerIsWorking)

          val block = waitForMinedBlock

          expectNoNewBlockMsg(noMessageTimeOut)

          validateBlock(block, parent)
        }
      }
    }

    "mine valid blocks" when {
      "there is request for block with other parent than best block" taggedAs (UnitTest, ConsensusTest) in new TestSetup {
        val parent = origin
        val parentHash = origin.hash
        val bfm = createBlockForMining(parent, Seq.empty)

        (blockchainReader.getBlockByHash _).expects(parentHash).returns(Some(parent))

        blockCreatorBehaviour(parent, withTransactions = false, bfm)

        withStartedMiner {
          sendToMiner(MineBlocks(1, withTransactions = false, Some(parentHash)))

          parentActor.expectMsg(MiningOrdered)

          val block = waitForMinedBlock

          validateBlock(block, parent)
        }
      }

      "there is request for one block without transactions" taggedAs (UnitTest, ConsensusTest) in new TestSetup {
        val parent = origin
        val bfm = createBlockForMining(parent, Seq.empty)

        blockCreatorBehaviour(parent, withTransactions = false, bfm)

        withStartedMiner {
          sendToMiner(MineBlocks(1, withTransactions = false, None))

          val block = waitForMinedBlock

          parentActor.expectMsg(MiningOrdered)

          validateBlock(block, parent)
        }
      }

      "there is request for one block with transactions" taggedAs (UnitTest, ConsensusTest) in new TestSetup {
        val parent = origin
        val bfm = createBlockForMining(parent)

        blockCreatorBehaviour(parent, withTransactions = true, bfm)

        withStartedMiner {
          sendToMiner(MineBlocks(1, withTransactions = true, None))

          val block = waitForMinedBlock

          parentActor.expectMsg(MiningOrdered)

          validateBlock(block, parent, Seq(txToMine))
        }
      }

      "there is request for few blocks without transactions" taggedAs (UnitTest, ConsensusTest) in new TestSetup {
        val parent = origin
        val bfm1 = createBlockForMining(parent, Seq.empty)
        val bfm2 = createBlockForMining(bfm1, Seq.empty)

        blockCreatorBehaviour(parent, withTransactions = false, bfm1)

        blockCreatorBehaviourExpectingInitialWorld(bfm1, withTransactions = false, bfm2)

        withStartedMiner {
          sendToMiner(MineBlocks(2, withTransactions = false, None))

          val block1 = waitForMinedBlock
          val block2 = waitForMinedBlock

          parentActor.expectMsg(MiningOrdered)

          validateBlock(block1, parent)
          validateBlock(block2, block1)
        }
      }

      "there is request for few blocks with transactions" taggedAs (UnitTest, ConsensusTest) in new TestSetup {
        val parent = origin
        val bfm1 = createBlockForMining(parent)
        val bfm2 = createBlockForMining(bfm1, Seq.empty)

        blockCreatorBehaviour(parent, withTransactions = true, bfm1)

        blockCreatorBehaviourExpectingInitialWorld(bfm1, withTransactions = true, bfm2)

        withStartedMiner {
          sendToMiner(MineBlocks(2, withTransactions = true, None))

          val block1 = waitForMinedBlock

          val block2 = waitForMinedBlock

          parentActor.expectMsg(MiningOrdered)

          validateBlock(block1, parent, Seq(txToMine))
          validateBlock(block2, block1)
        }
      }
    }
  }

  class TestSetup extends MinerSpecSetup {
    // Override classicSystem to use the TestKit's actor system instead of creating a new one
    override implicit def classicSystem: ClassicSystem = MockedMinerSpec.this.system
    val noMessageTimeOut: FiniteDuration = 3.seconds

    // Implement abstract mock members - created in test class with MockFactory context
    override lazy val mockBlockchainReader: BlockchainReader = mock[BlockchainReader]
    override lazy val mockBlockchain: BlockchainImpl = mock[BlockchainImpl]
    override lazy val mockBlockCreator: PoWBlockCreator = mock[PoWBlockCreator]
    override lazy val mockBlockGenerator: PoWBlockGenerator = mock[PoWBlockGenerator]
    override lazy val mockEthMiningService: EthMiningService = mock[EthMiningService]
    override lazy val mockEvmCodeStorage: EvmCodeStorage = mock[EvmCodeStorage]
    override lazy val mockMptStorage: MptStorage = mock[MptStorage]

    val miner: TestActorRef[Nothing] = TestActorRef(
      MockedMiner.props(
        blockchainReader,
        blockCreator,
        sync.ref,
        this
      )
    )

    // Allow getBestBlock to be called 0 or more times since some tests use getBlockByHash instead
    (blockchainReader.getBestBlock _).expects().returns(Some(origin)).anyNumberOfTimes()

    // Implement abstract expectation methods
    // NOTE: MockedMiner tests use createBlockForMining() which doesn't call this method,
    // because MockedMiner uses the mocked blockCreator directly without going through blockGenerator.
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

    def validateBlock(block: Block, parent: Block, txs: Seq[SignedTransaction] = Seq.empty): Assertion = {
      block.body.transactionList shouldBe txs
      block.header.nonce.length shouldBe 0
      block.header.parentHash shouldBe parent.hash
    }

    protected def withStartedMiner(behaviour: => Unit): Unit = {
      miner ! MinerProtocol.StartMining
      behaviour
      miner ! MinerProtocol.StopMining
    }

    protected def sendToMiner(msg: MinerProtocol): Unit =
      miner.tell(msg, parentActor.ref)
  }
}
