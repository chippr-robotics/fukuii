package com.chipprbots.ethereum.consensus.pow

import java.util.concurrent.atomic.AtomicInteger

import org.apache.pekko.actor.ActorRef
import org.apache.pekko.actor.ActorSystem as ClassicSystem
import org.apache.pekko.actor.testkit.typed.scaladsl.LoggingTestKit
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.testkit.TestActor
import org.apache.pekko.testkit.TestProbe
import org.apache.pekko.util.ByteString

import cats.effect.IO
import cats.effect.unsafe.IORuntime

import scala.concurrent.Future
import scala.concurrent.duration.*

import org.bouncycastle.util.encoders.Hex
import org.scalamock.handlers.CallHandler4
import org.scalamock.handlers.CallHandler6
import org.scalatest.freespec.AnyFreeSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.Timeouts
import com.chipprbots.ethereum.blockchain.sync.SyncController
import com.chipprbots.ethereum.blockchain.sync.SyncProtocol
import com.chipprbots.ethereum.blockchain.sync.SyncProtocol.MinedBlock
import com.chipprbots.ethereum.consensus.blocks.PendingBlock
import com.chipprbots.ethereum.consensus.blocks.PendingBlockAndState
import com.chipprbots.ethereum.consensus.mining.CoinbaseProvider
import com.chipprbots.ethereum.consensus.pow.PoWMiningCoordinator.*
import com.chipprbots.ethereum.consensus.pow.PoWMiningCoordinator.MiningMode.*
import com.chipprbots.ethereum.consensus.pow.blocks.PoWBlockGenerator
import com.chipprbots.ethereum.consensus.pow.miners.Miner
import com.chipprbots.ethereum.consensus.pow.miners.MinerProtocol
import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.jsonrpc.EthMiningService
import com.chipprbots.ethereum.jsonrpc.EthMiningService.SubmitHashRateResponse
import com.chipprbots.ethereum.ledger.InMemoryWorldStateProxy
import com.chipprbots.ethereum.ommers.OmmersPool
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.transactions.PendingTransactionsManager
import com.chipprbots.ethereum.utils.BlockchainConfig

// SCALA 3 MIGRATION: Fixed by refactoring MinerSpecSetup to use abstract mock members pattern.
// ACTOR SYSTEM FIX: TestSetup now overrides classicSystem to use ScalaTestWithActorTestKit's
// actor system (converted to classic), preventing actor system conflicts between the test kit
// and MinerSpecSetup.
class PoWMiningCoordinatorSpec
    extends ScalaTestWithActorTestKit
    with AnyFreeSpecLike
    with Matchers
    with org.scalamock.scalatest.MockFactory:

  "PoWMinerCoordinator actor" - {
    // Spec 029 slice 7: `app_pow_miner_running`, the internal miner's own on/off for the TUI. These tests come first in
    // the suite, so no coordinator another test left stopping can write the process-wide flag while they read it. Each
    // waits for its coordinator to terminate and leaves the flag at 0.
    "the miner-running gauge" - {
      def running: Long = PoWMiningMetrics.minerRunning.value().toLong

      "reads 1 once recurrent mining has started, and 0 after StopMining" taggedAs (
        UnitTest,
        ConsensusTest
      ) in new TestSetup:
        override def coordinatorName: String = s"MinerRunningStop-${System.nanoTime()}"
        val probe: TestProbe = TestProbe()
        val testMiner = new InstantMiner(blockCreator, sync.ref, ethMiningService)
        override val coordinator: org.apache.pekko.actor.typed.ActorRef[CoordinatorProtocol] = testKit.spawn(
          PoWMiningCoordinator(
            sync.ref,
            ethMiningService,
            blockCreator,
            blockchainReader,
            this,
            minerOpt = Some(testMiner)
          ),
          coordinatorName
        )
        probe.watch(coordinator.ref.toClassic)
        (() => blockchainReader.getBestBlock).expects().returns(Some(parentBlock)).anyNumberOfTimes()
        setBlockForMining(parentBlock)
        PoWMiningMetrics.recordMinerRunning(false)

        coordinator ! SetMiningMode(RecurrentMining)
        // A mined block means the coordinator handled SetMiningMode, which writes the flag before its first MineNext.
        sync.expectMsgType[SyncController.WrappedSyncProtocol](Timeouts.veryLongTimeout).msg shouldBe a[MinedBlock]
        running shouldBe 1L

        coordinator ! StopMining
        probe.expectTerminated(coordinator.ref.toClassic)
        running shouldBe 0L

      "reads 0 after the coordinator is stopped from outside while mining" taggedAs (
        UnitTest,
        ConsensusTest
      ) in new TestSetup:
        override def coordinatorName: String = s"MinerRunningKilled-${System.nanoTime()}"
        val testMiner = new InstantMiner(blockCreator, sync.ref, ethMiningService)
        override val coordinator: org.apache.pekko.actor.typed.ActorRef[CoordinatorProtocol] = testKit.spawn(
          PoWMiningCoordinator(
            sync.ref,
            ethMiningService,
            blockCreator,
            blockchainReader,
            this,
            minerOpt = Some(testMiner)
          ),
          coordinatorName
        )
        (() => blockchainReader.getBestBlock).expects().returns(Some(parentBlock)).anyNumberOfTimes()
        setBlockForMining(parentBlock)
        PoWMiningMetrics.recordMinerRunning(false)

        coordinator ! SetMiningMode(RecurrentMining)
        sync.expectMsgType[SyncController.WrappedSyncProtocol](Timeouts.veryLongTimeout).msg shouldBe a[MinedBlock]
        running shouldBe 1L

        // No StopMining: only the PostStop handler of the recurrent behaviour can clear the flag. `stop` returns once
        // the actor has terminated, and PostStop runs before that.
        testKit.stop(coordinator, Timeouts.veryLongTimeout)
        running shouldBe 0L

      "reads 0 after a coordinator that fails before it was told to mine has stopped" taggedAs (
        UnitTest,
        ConsensusTest
      ) in new TestSetup:
        override def coordinatorName: String = s"MinerRunningFailed-${System.nanoTime()}"
        val probe: TestProbe = TestProbe()
        probe.watch(coordinator.ref.toClassic)
        // Set by the test, so that only the initial behaviour's PostStop handler can bring it back to 0.
        PoWMiningMetrics.recordMinerRunning(true)

        LoggingTestKit.error("StopMining").expect {
          coordinator ! StopMining
        }
        probe.expectTerminated(coordinator.ref.toClassic)
        running shouldBe 0L
    }

    "should throw exception when starting with other message than StartMining(mode)" taggedAs (
      UnitTest,
      ConsensusTest,
      SlowTest
    ) in new TestSetup:
      override def coordinatorName = "FailedCoordinator"
      LoggingTestKit.error("StopMining").expect {
        coordinator ! StopMining
      }

    "should start recurrent mining when receiving message StartMining(RecurrentMining)" taggedAs (
      UnitTest,
      ConsensusTest,
      SlowTest
    ) in new TestSetup:
      override def coordinatorName = "RecurrentMiningSetup"
      setBlockForMining(parentBlock)

      coordinator ! SetMiningMode(RecurrentMining)

      // Give the coordinator time to process the message using expectNoMessage instead of Thread.sleep
      sync.expectNoMessage(100.millis)

      coordinator ! StopMining

    "should start on demand mining when receiving message StartMining(OnDemandMining)" taggedAs (
      UnitTest,
      ConsensusTest,
      SlowTest
    ) in new TestSetup:
      override def coordinatorName = "OnDemandMining"

      coordinator ! SetMiningMode(OnDemandMining)

      // Give the coordinator time to process the message using expectNoMessage instead of Thread.sleep
      sync.expectNoMessage(100.millis)

      coordinator ! StopMining

    "in Recurrent Mining" - {
      // DELETED (P10): "MineNext starts EthashMiner"
      // The mock for PoWBlockGenerator.generateBlock was never set up, so EthashMiner fired a
      // TestFailedException inside its processMining Future on every run. The exception was swallowed
      // by EthashMiner's error handler, so the test "passed" while emitting a spurious ERROR log.
      // Full recurrent mining coverage is provided by "Miners mine recurrently" (InstantMiner).

      "Miners mine recurrently" taggedAs (UnitTest, ConsensusTest, SlowTest) in new TestSetup:
        override def coordinatorName: String = s"AutomaticMining-${System.nanoTime()}"
        val probe: TestProbe = TestProbe()
        val testMiner = new InstantMiner(blockCreator, sync.ref, ethMiningService)
        override val coordinator: org.apache.pekko.actor.typed.ActorRef[CoordinatorProtocol] = testKit.spawn(
          PoWMiningCoordinator(
            sync.ref,
            ethMiningService,
            blockCreator,
            blockchainReader,
            this,
            minerOpt = Some(testMiner)
          ),
          coordinatorName
        )
        probe.watch(coordinator.ref.toClassic)

        (() => blockchainReader.getBestBlock).expects().returns(Some(parentBlock)).anyNumberOfTimes()
        setBlockForMining(parentBlock)
        coordinator ! SetMiningMode(RecurrentMining)

        sync.expectMsgType[SyncController.WrappedSyncProtocol](Timeouts.veryLongTimeout).msg shouldBe a[MinedBlock]
        sync.expectMsgType[SyncController.WrappedSyncProtocol](Timeouts.veryLongTimeout).msg shouldBe a[MinedBlock]
        sync.expectMsgType[SyncController.WrappedSyncProtocol](Timeouts.veryLongTimeout).msg shouldBe a[MinedBlock]

        coordinator ! StopMining
        probe.expectTerminated(coordinator.ref.toClassic)

      "Continue to attempt to mine if blockchainReader.getBestBlock return None" taggedAs (
        UnitTest,
        ConsensusTest,
        SlowTest
      ) in new TestSetup:
        override def coordinatorName: String = s"AlwaysAttemptToMine-${System.nanoTime()}"
        val probe: TestProbe = TestProbe()
        val testMiner = new InstantMiner(blockCreator, sync.ref, ethMiningService)
        override val coordinator: org.apache.pekko.actor.typed.ActorRef[CoordinatorProtocol] = testKit.spawn(
          PoWMiningCoordinator(
            sync.ref,
            ethMiningService,
            blockCreator,
            blockchainReader,
            this,
            minerOpt = Some(testMiner),
            notReadyRetryDelay = 100.millis
          ),
          coordinatorName
        )
        probe.watch(coordinator.ref.toClassic)

        (() => blockchainReader.getBestBlock).expects().returns(None).twice()
        (() => blockchainReader.getBestBlock).expects().returns(Some(parentBlock)).anyNumberOfTimes()

        setBlockForMining(parentBlock)
        coordinator ! SetMiningMode(RecurrentMining)

        sync.expectMsgType[SyncController.WrappedSyncProtocol](Timeouts.veryLongTimeout).msg shouldBe a[MinedBlock]
        sync.expectMsgType[SyncController.WrappedSyncProtocol](Timeouts.veryLongTimeout).msg shouldBe a[MinedBlock]
        sync.expectMsgType[SyncController.WrappedSyncProtocol](Timeouts.veryLongTimeout).msg shouldBe a[MinedBlock]

        coordinator ! StopMining
        probe.expectTerminated(coordinator.ref.toClassic)

      // B-424: a node that cannot mine asked again at once, and logged an ERROR each time: 19 k a second, 90 M lines over
      // an 80-minute SNAP sync on Mordor.
      "waits without spinning while there is no best block, and mines once there is one" taggedAs (
        UnitTest,
        ConsensusTest
      ) in new TestSetup:
        override def coordinatorName: String = s"NoBestBlockWaits-${System.nanoTime()}"
        val probe: TestProbe = TestProbe()
        val testMiner = new InstantMiner(blockCreator, sync.ref, ethMiningService)
        override val coordinator: org.apache.pekko.actor.typed.ActorRef[CoordinatorProtocol] =
          spawnWaiting(coordinatorName, testMiner)
        probe.watch(coordinator.ref.toClassic)

        @volatile var best: Option[Block] = None
        val bestBlockReads = new AtomicInteger(0)
        (() => blockchainReader.getBestBlock)
          .expects()
          .onCall { () =>
            bestBlockReads.incrementAndGet()
            best
          }
          .anyNumberOfTimes()
        setBlockForMining(parentBlock)
        coordinator ! SetMiningMode(RecurrentMining)

        sync.expectNoMessage(1.second)
        testMiner.turns.get() shouldBe 0
        // One read every 100 ms: about ten in the second. Asked again at once, it was read hundreds of thousands of times.
        bestBlockReads.get() should ((be >= 2).and(be <= 15))

        best = Some(parentBlock)
        sync.expectMsgType[SyncController.WrappedSyncProtocol](Timeouts.veryLongTimeout).msg shouldBe a[MinedBlock]

        coordinator ! StopMining
        probe.expectTerminated(coordinator.ref.toClassic)

      // B-424: node B mined block 1 on genesis while it imported a checkpoint. The sync controller imports one inside
      // its start, and answers nothing until it is done.
      "does not mine while the sync controller does not answer, nor while the node syncs" taggedAs (
        UnitTest,
        ConsensusTest
      ) in new TestSetup:
        override def coordinatorName: String = s"SyncingWaits-${System.nanoTime()}"
        val probe: TestProbe = TestProbe()
        val testMiner = new InstantMiner(blockCreator, sync.ref, ethMiningService)
        override val coordinator: org.apache.pekko.actor.typed.ActorRef[CoordinatorProtocol] =
          spawnWaiting(coordinatorName, testMiner)
        probe.watch(coordinator.ref.toClassic)

        (() => blockchainReader.getBestBlock).expects().returns(Some(origin)).anyNumberOfTimes()
        setBlockForMining(origin)
        syncStatus = None
        coordinator ! SetMiningMode(RecurrentMining)

        sync.expectNoMessage(600.millis)
        // node B's block 1 on genesis: no turn is given while the controller is silent
        testMiner.turns.get() shouldBe 0
        statusAsks.get() should be >= 2

        syncStatus = Some(SyncProtocol.Status.Syncing(0, SyncProtocol.Status.Progress(0, 1000), None))
        val asksBefore: Int = statusAsks.get()
        sync.expectNoMessage(600.millis)
        statusAsks.get() should be > asksBefore
        testMiner.turns.get() shouldBe 0

        // no peer is higher than this node: a lone miner mines, as go-ethereum's does
        syncStatus = Some(SyncProtocol.Status.NotSyncing)
        sync.expectMsgType[SyncController.WrappedSyncProtocol](Timeouts.veryLongTimeout).msg shouldBe a[MinedBlock]

        coordinator ! StopMining
        probe.expectTerminated(coordinator.ref.toClassic)

      "does not mine on a best block whose state the node does not hold" taggedAs (
        UnitTest,
        ConsensusTest
      ) in new TestSetup:
        override def coordinatorName: String = s"NoStateWaits-${System.nanoTime()}"
        val probe: TestProbe = TestProbe()
        val testMiner = new InstantMiner(blockCreator, sync.ref, ethMiningService)
        override val coordinator: org.apache.pekko.actor.typed.ActorRef[CoordinatorProtocol] =
          spawnWaiting(coordinatorName, testMiner)
        probe.watch(coordinator.ref.toClassic)

        (() => blockchainReader.getBestBlock).expects().returns(Some(parentBlock)).anyNumberOfTimes()
        setBlockForMining(parentBlock)
        stateHeld = false
        coordinator ! SetMiningMode(RecurrentMining)

        sync.expectNoMessage(600.millis)
        testMiner.turns.get() shouldBe 0

        stateHeld = true
        sync.expectMsgType[SyncController.WrappedSyncProtocol](Timeouts.veryLongTimeout).msg shouldBe a[MinedBlock]

        coordinator ! StopMining
        probe.expectTerminated(coordinator.ref.toClassic)

      // go-ethereum's miner stops listening to the downloader after its first DoneEvent: a peer that announces a block
      // it never sends must not stop this node's mining.
      "asks whether the node syncs only until the sync has once reached the head" taggedAs (
        UnitTest,
        ConsensusTest
      ) in new TestSetup:
        override def coordinatorName: String = s"SyncedOnce-${System.nanoTime()}"
        val probe: TestProbe = TestProbe()
        val testMiner = new InstantMiner(blockCreator, sync.ref, ethMiningService)
        override val coordinator: org.apache.pekko.actor.typed.ActorRef[CoordinatorProtocol] =
          spawnWaiting(coordinatorName, testMiner)
        probe.watch(coordinator.ref.toClassic)

        (() => blockchainReader.getBestBlock).expects().returns(Some(parentBlock)).anyNumberOfTimes()
        setBlockForMining(parentBlock)
        coordinator ! SetMiningMode(RecurrentMining)

        sync.expectMsgType[SyncController.WrappedSyncProtocol](Timeouts.veryLongTimeout).msg shouldBe a[MinedBlock]
        syncStatus = Some(SyncProtocol.Status.Syncing(0, SyncProtocol.Status.Progress(0, 1000), None))
        sync.expectMsgType[SyncController.WrappedSyncProtocol](Timeouts.veryLongTimeout).msg shouldBe a[MinedBlock]
        sync.expectMsgType[SyncController.WrappedSyncProtocol](Timeouts.veryLongTimeout).msg shouldBe a[MinedBlock]
        statusAsks.get() shouldBe 1

        coordinator ! StopMining
        probe.expectTerminated(coordinator.ref.toClassic)

      "StopMining stops PoWMinerCoordinator" taggedAs (UnitTest, ConsensusTest, SlowTest) in new TestSetup:
        override def coordinatorName: String = s"StoppingMining-${System.nanoTime()}"
        val probe: TestProbe = TestProbe()
        override val coordinator: org.apache.pekko.actor.typed.ActorRef[CoordinatorProtocol] = testKit.spawn(
          PoWMiningCoordinator(
            sync.ref,
            ethMiningService,
            blockCreator,
            blockchainReader,
            this
          ),
          coordinatorName
        )
        probe.watch(coordinator.ref.toClassic)

        (() => blockchainReader.getBestBlock).expects().returns(Some(parentBlock)).anyNumberOfTimes()
        setBlockForMining(parentBlock)
        coordinator ! SetMiningMode(RecurrentMining)
        coordinator ! StopMining

        probe.expectTerminated(coordinator.ref.toClassic)
    }
  }

  /** Miner that bypasses Ethash DAG generation for fast, reliable testing. Immediately returns a successful mining
    * result without loading the ~1GB DAG file.
    */
  private class InstantMiner(
      blockCreator: PoWBlockCreator,
      syncController: ActorRef,
      ethMiningService: EthMiningService
  )(implicit runtime: IORuntime)
      extends Miner:
    // How many turns the coordinator gave this miner: a turn is given before the block is built, which takes seconds.
    val turns = new AtomicInteger(0)

    def processMining(
        bestBlock: Block
    )(implicit blockchainConfig: BlockchainConfig): Future[CoordinatorProtocol] =
      turns.incrementAndGet()
      blockCreator
        .getBlockForMining(bestBlock)
        .map { case PendingBlockAndState(PendingBlock(block, _), _) =>
          val fakeResult = MinerProtocol.MiningSuccessful(
            1,
            ByteString(new Array[Byte](32)),
            ByteString(new Array[Byte](8))
          )
          submitHashRate(ethMiningService, 1L, fakeResult)
          handleMiningResult(fakeResult, syncController, block)
        }
        .unsafeToFuture()

  class TestSetup extends MinerSpecSetup:
    def coordinatorName: String = "DefaultCoordinator"

    // Override classicSystem to use the ScalaTestWithActorTestKit's actor system (converted to classic)
    // This prevents actor system conflicts between the test kit and MinerSpecSetup.
    implicit override def classicSystem: ClassicSystem = PoWMiningCoordinatorSpec.this.system.toClassic

    // Implement abstract mock members - created in test class with MockFactory context
    override lazy val mockBlockchainReader: BlockchainReader = mock[BlockchainReader]
    override lazy val mockBlockchain: BlockchainImpl = mock[BlockchainImpl]
    override lazy val mockBlockCreator: PoWBlockCreator = mock[PoWBlockCreator]
    override lazy val mockBlockGenerator: PoWBlockGenerator = mock[PoWBlockGenerator]
    override lazy val mockEthMiningService: EthMiningService = mock[EthMiningService]
    override lazy val mockEvmCodeStorage: EvmCodeStorage = mock[EvmCodeStorage]
    // A world asks its storage for the account trie's view of it: a hash-keyed store answers with itself.
    override lazy val mockMptStorage: MptStorage =
      val storage = mock[MptStorage]
      storage.forOwner.expects(*).anyNumberOfTimes().returning(storage)
      storage

    override lazy val mining: PoWMining = buildPoWConsensus().withBlockGenerator(blockGenerator)

    val parentBlockNumber: Int = 23499
    override val origin: Block = Block(
      Fixtures.Blocks.Genesis.header.copy(
        difficulty = Difficulty(UInt256(Hex.decode("0400")).toBigInt),
        number = BlockNumber(0),
        gasUsed = GasAmount.Zero,
        unixTimestamp = Timestamp(0)
      ),
      Fixtures.Blocks.ValidBlock.body
    )

    val parentBlock: Block = origin.copy(header = origin.header.copy(number = BlockNumber(parentBlockNumber)))

    val getTransactionFromPoolTimeout: FiniteDuration = 5.seconds

    val coinbaseProvider = new CoinbaseProvider(miningConfig.coinbase)

    override lazy val blockCreator = new PoWBlockCreator(
      pendingTransactionsManager = pendingTransactionsManager.ref.toTyped[PendingTransactionsManager.Command],
      getTransactionFromPoolTimeout = getTransactionFromPoolTimeout,
      mining = mining,
      ommersPool = ommersPool.ref.toTyped[com.chipprbots.ethereum.ommers.OmmersPool.Command],
      coinbaseProvider = coinbaseProvider,
      system = classicSystem
    )

    val coordinator: typed.ActorRef[CoordinatorProtocol] = testKit.spawn(
      PoWMiningCoordinator(
        sync.ref,
        ethMiningService,
        blockCreator,
        blockchainReader,
        this
      ),
      coordinatorName
    )

    // Implement abstract expectation methods
    // NOTE: Use anyNumberOfTimes() because some tests may crash before actually mining
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
        )(using _: BlockchainConfig))
        .expects(parentBlock, Nil, miningConfig.coinbase, Nil, None, *)
        .returning(PendingBlockAndState(PendingBlock(block, Nil), fakeWorld))
        .anyNumberOfTimes()

    override def blockCreatorBehaviourExpectation(
        parentBlock: Block,
        withTransactions: Boolean,
        resultBlock: Block,
        fakeWorld: InMemoryWorldStateProxy
    ): CallHandler4[Block, Boolean, Option[InMemoryWorldStateProxy], BlockchainConfig, IO[PendingBlockAndState]] =
      (mockBlockCreator
        .getBlockForMining(_: Block, _: Boolean, _: Option[InMemoryWorldStateProxy])(using _: BlockchainConfig))
        .expects(parentBlock, withTransactions, *, *)
        .returning(IO.pure(PendingBlockAndState(PendingBlock(resultBlock, Nil), fakeWorld)))

    override def blockCreatorBehaviourExpectingInitialWorldExpectation(
        parentBlock: Block,
        withTransactions: Boolean,
        resultBlock: Block,
        fakeWorld: InMemoryWorldStateProxy
    ): CallHandler4[Block, Boolean, Option[InMemoryWorldStateProxy], BlockchainConfig, IO[PendingBlockAndState]] =
      (mockBlockCreator
        .getBlockForMining(_: Block, _: Boolean, _: Option[InMemoryWorldStateProxy])(using _: BlockchainConfig))
        .expects(where { (parent: Block, withTxs: Boolean, _: Option[InMemoryWorldStateProxy], _: BlockchainConfig) =>
          parent == parentBlock && withTxs == withTransactions
        })
        .returning(IO.pure(PendingBlockAndState(PendingBlock(resultBlock, Nil), fakeWorld)))

    override def setupMiningServiceExpectation(): Unit =
      ethMiningService.submitHashRate
        .expects(*)
        .returns(IO.pure(Right(SubmitHashRateResponse(true))))
        .anyNumberOfTimes()

    // Allow mining service calls to happen 0 or more times since not all tests actually mine
    ethMiningService.submitHashRate
      .expects(*)
      .returns(IO.pure(Right(SubmitHashRateResponse(true))))
      .anyNumberOfTimes()

    ommersPool.setAutoPilot { (_: ActorRef, msg: Any) =>
      msg match
        case OmmersPool.GetOmmers(_, replyTo) => replyTo ! OmmersPool.Ommers(Nil)
        case _                                => ()
      TestActor.KeepRunning
    }

    pendingTransactionsManager.setAutoPilot { (sender: ActorRef, _: Any) =>
      sender ! PendingTransactionsManager.PendingTransactionsResponse(Nil)
      TestActor.KeepRunning
    }

    // B-424: before it mines, the coordinator asks the sync controller whether the node syncs. The probe answers with
    // `syncStatus` (None: no answer, as while a checkpoint is imported) and counts the asks. The asks are kept out of the
    // probe's queue, so that the tests' expectations on it see the mined blocks only.
    @volatile var syncStatus: Option[SyncProtocol.Status] = Some(SyncProtocol.Status.SyncDone)
    val statusAsks = new AtomicInteger(0)
    sync.setAutoPilot { (_: ActorRef, msg: Any) =>
      msg match
        case SyncController.WrappedSyncProtocol(SyncProtocol.GetStatus(replyTo)) =>
          statusAsks.incrementAndGet()
          syncStatus.foreach(replyTo ! _)
        case _ => ()
      TestActor.KeepRunning
    }
    sync.ignoreMsg { case SyncController.WrappedSyncProtocol(_: SyncProtocol.GetStatus) => true }

    // Whether the node holds the state of its best block.
    @volatile var stateHeld: Boolean = true
    blockchainReader.holdsStateOf.expects(*).onCall((_: BlockHeader) => stateHeld).anyNumberOfTimes()

    /** A coordinator that waits 100 ms for a turn it cannot take, and as long for the sync controller's answer. */
    def spawnWaiting(name: String, miner: Miner): typed.ActorRef[CoordinatorProtocol] =
      testKit.spawn(
        PoWMiningCoordinator(
          sync.ref,
          ethMiningService,
          blockCreator,
          blockchainReader,
          this,
          minerOpt = Some(miner),
          notReadyRetryDelay = 100.millis,
          syncStatusTimeout = 100.millis
        ),
        name
      )
