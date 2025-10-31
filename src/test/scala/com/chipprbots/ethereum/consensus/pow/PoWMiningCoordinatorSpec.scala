package com.chipprbots.ethereum.consensus.pow

import org.apache.pekko.actor.ActorRef
import org.apache.pekko.actor.testkit.typed.LoggingEvent
import org.apache.pekko.actor.testkit.typed.scaladsl.LoggingTestKit
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed
import org.apache.pekko.actor.typed.scaladsl.adapter._
import org.apache.pekko.testkit.TestActor
import org.apache.pekko.testkit.TestProbe

import cats.effect.IO

import scala.concurrent.duration._

import org.bouncycastle.util.encoders.Hex
import org.scalatest.freespec.AnyFreeSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.blockchain.sync.SyncProtocol.MinedBlock
import com.chipprbots.ethereum.consensus.pow.PoWMiningCoordinator._
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.UInt256
import com.chipprbots.ethereum.jsonrpc.EthMiningService.SubmitHashRateResponse
import com.chipprbots.ethereum.ommers.OmmersPool
import com.chipprbots.ethereum.transactions.PendingTransactionsManager

import org.scalatest.Ignore

// SCALA 3 MIGRATION: Disabled due to scalamock limitation with complex parameterized types (InMemoryWorldStateProxy in MinerSpecSetup)
// This test requires either scalamock library updates for Scala 3 or test refactoring to avoid mocking InMemoryWorldStateProxy
@Ignore
class PoWMiningCoordinatorSpec extends ScalaTestWithActorTestKit with AnyFreeSpecLike with Matchers {

  "PoWMinerCoordinator actor" - {
    "should throw exception when starting with other message than StartMining(mode)" in new TestSetup(
      "FailedCoordinator"
    ) {
      LoggingTestKit.error("StopMining").expect {
        coordinator ! StopMining
      }
    }

    "should start recurrent mining when receiving message StartMining(RecurrentMining)" in new TestSetup(
      "RecurrentMining"
    ) {
      setBlockForMining(parentBlock)
      LoggingTestKit.info("Received message SetMiningMode(RecurrentMining)").expect {
        coordinator ! SetMiningMode(RecurrentMining)
      }
      coordinator ! StopMining
    }

    "should start on demand mining when receiving message StartMining(OnDemandMining)" in new TestSetup(
      "OnDemandMining"
    ) {
      LoggingTestKit.info("Received message SetMiningMode(OnDemandMining)").expect {
        coordinator ! SetMiningMode(OnDemandMining)
      }
      coordinator ! StopMining
    }

    "in Recurrent Mining" - {
      "MineNext starts EthashMiner if mineWithKeccak is false" in new TestSetup(
        "EthashMining"
      ) {
        (blockchainReader.getBestBlock _).expects().returns(Some(parentBlock)).anyNumberOfTimes()
        setBlockForMining(parentBlock)
        LoggingTestKit.debug("Mining with Ethash").expect {
          coordinator ! SetMiningMode(RecurrentMining)
        }

        coordinator ! StopMining
      }

      "MineNext starts KeccakMiner if mineWithKeccak is true" in new TestSetup(
        "KeccakMining"
      ) {
        override val coordinator = system.systemActorOf(
          PoWMiningCoordinator(
            sync.ref,
            ethMiningService,
            blockCreator,
            blockchainReader,
            Some(0),
            this
          ),
          "KeccakMining"
        )
        (blockchainReader.getBestBlock _).expects().returns(Some(parentBlock)).anyNumberOfTimes()
        setBlockForMining(parentBlock)

        LoggingTestKit
          .debug("Mining with Keccak")
          .withCustom { (_: LoggingEvent) =>
            coordinator ! StopMining
            true
          }
          .expect {
            coordinator ! SetMiningMode(RecurrentMining)
          }
      }

      "Miners mine recurrently" in new TestSetup(
        "RecurrentMining"
      ) {
        override val coordinator = testKit.spawn(
          PoWMiningCoordinator(
            sync.ref,
            ethMiningService,
            blockCreator,
            blockchainReader,
            Some(0),
            this
          ),
          "AutomaticMining"
        )

        (blockchainReader.getBestBlock _).expects().returns(Some(parentBlock)).anyNumberOfTimes()
        setBlockForMining(parentBlock)
        coordinator ! SetMiningMode(RecurrentMining)

        sync.expectMsgType[MinedBlock]
        sync.expectMsgType[MinedBlock]
        sync.expectMsgType[MinedBlock]

        coordinator ! StopMining
      }

      "Continue to attempt to mine if blockchainReader.getBestBlock() return None" in new TestSetup(
        "AlwaysMine"
      ) {
        override val coordinator = testKit.spawn(
          PoWMiningCoordinator(
            sync.ref,
            ethMiningService,
            blockCreator,
            blockchainReader,
            Some(0),
            this
          ),
          "AlwaysAttemptToMine"
        )

        (blockchainReader.getBestBlock _).expects().returns(None).twice()
        (blockchainReader.getBestBlock _).expects().returns(Some(parentBlock)).anyNumberOfTimes()

        setBlockForMining(parentBlock)
        coordinator ! SetMiningMode(RecurrentMining)

        sync.expectMsgType[MinedBlock]
        sync.expectMsgType[MinedBlock]
        sync.expectMsgType[MinedBlock]

        coordinator ! StopMining
      }

      "StopMining stops PoWMinerCoordinator" in new TestSetup("StoppingMining") {
        val probe = TestProbe()
        override val coordinator = testKit.spawn(
          PoWMiningCoordinator(
            sync.ref,
            ethMiningService,
            blockCreator,
            blockchainReader,
            Some(0),
            this
          ),
          "StoppingMining"
        )
        probe.watch(coordinator.ref.toClassic)

        (blockchainReader.getBestBlock _).expects().returns(Some(parentBlock)).anyNumberOfTimes()
        setBlockForMining(parentBlock)
        coordinator ! SetMiningMode(RecurrentMining)
        coordinator ! StopMining

        probe.expectTerminated(coordinator.ref.toClassic)
      }
    }
  }

  class TestSetup(coordinatorName: String) extends MinerSpecSetup {
    override lazy val mining: PoWMining = buildPoWConsensus().withBlockGenerator(blockGenerator)

    val parentBlockNumber: Int = 23499
    override val origin: Block = Block(
      Fixtures.Blocks.Genesis.header.copy(
        difficulty = UInt256(Hex.decode("0400")).toBigInt,
        number = 0,
        gasUsed = 0,
        unixTimestamp = 0
      ),
      Fixtures.Blocks.ValidBlock.body
    )

    val parentBlock: Block = origin.copy(header = origin.header.copy(number = parentBlockNumber))

    val getTransactionFromPoolTimeout: FiniteDuration = 5.seconds

    override val blockCreator = new PoWBlockCreator(
      pendingTransactionsManager = pendingTransactionsManager.ref,
      getTransactionFromPoolTimeout = getTransactionFromPoolTimeout,
      mining = mining,
      ommersPool = ommersPool.ref
    )

    val coordinator: typed.ActorRef[CoordinatorProtocol] = testKit.spawn(
      PoWMiningCoordinator(
        sync.ref,
        ethMiningService,
        blockCreator,
        blockchainReader,
        None,
        this
      ),
      coordinatorName
    )

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
}
