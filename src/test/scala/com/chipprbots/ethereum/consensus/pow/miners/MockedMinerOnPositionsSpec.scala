package com.chipprbots.ethereum.consensus.pow.miners

import org.apache.pekko.actor.testkit.typed.scaladsl.ManualTime
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.util.ByteString

import cats.effect.IO

import scala.concurrent.duration.*

import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.TestInstanceConfigProvider
import com.chipprbots.ethereum.blockchain.sync.SyncController
import com.chipprbots.ethereum.blockchain.sync.SyncProtocol
import com.chipprbots.ethereum.consensus.blocks.PendingBlock
import com.chipprbots.ethereum.consensus.blocks.PendingBlockAndState
import com.chipprbots.ethereum.consensus.pow.PoWBlockCreator
import com.chipprbots.ethereum.consensus.pow.miners.MockedMiner.MineBlocks
import com.chipprbots.ethereum.consensus.pow.miners.MockedMiner.MockedMinerResponse
import com.chipprbots.ethereum.consensus.pow.miners.MockedMiner.MockedMinerResponses.MinerIsWorking
import com.chipprbots.ethereum.consensus.pow.miners.MockedMiner.MockedMinerResponses.MiningOrdered
import com.chipprbots.ethereum.consensus.pow.miners.MockedMiner.Send
import com.chipprbots.ethereum.consensus.pow.miners.MockedMiner.StartMining
import com.chipprbots.ethereum.db.storage.pathstate.StateUnavailable
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.BlockHash
import com.chipprbots.ethereum.domain.BlockNumber
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.ledger.InMemoryWorldStateProxy
import com.chipprbots.ethereum.nodebuilder.BlockchainConfigBuilder
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.BlockchainConfig

/** Spec 018 Track C, C3c (T104, T113): on the unified state layout the mocked miner builds each block on the state its
  * parent's import left on disk, and waits for that import.
  *
  * With the hash store the miner chains the worlds of its builds, one second apart, whether or not the block before
  * was imported ([[MockedMinerSpec]]). Here a block's state is the one on disk or it is nowhere, so the next block is
  * built with no inherited world, once the block before is the block whose state is held.
  *
  * Time is the test's own: a tick of the miner happens when the test says a second has passed.
  */
class MockedMinerOnPositionsSpec
    extends ScalaTestWithActorTestKit(ManualTime.config)
    with AnyFlatSpecLike
    with Matchers
    with org.scalamock.scalatest.MockFactory:

  private val manualTime: ManualTime = ManualTime()

  private val origin: Block = Block(Fixtures.Blocks.Genesis.header, Fixtures.Blocks.Genesis.body)
  private def childOf(parent: Block, salt: Int = 0): Block =
    parent.copy(header =
      parent.header.copy(
        number = BlockNumber(parent.header.number.value + 1),
        parentHash = parent.header.hash,
        gasUsed = parent.header.gasUsed,
        extraData = ByteString(salt.toByte)
      )
    )

  private class Rig:
    /** The block whose state the disk holds, as the test moves it. */
    @volatile var held: Block = origin
    @volatile var builds: Vector[(BlockHash, Option[InMemoryWorldStateProxy])] = Vector.empty

    /** What a build of a child of the block with this hash answers; a block, or the fault it meets. */
    @volatile var answers: Map[BlockHash, List[Either[Throwable, Block]]] = Map.empty

    val reader: BlockchainReader = mock[BlockchainReader]
    (() => reader.holdsStateInPlace).expects().returns(true).anyNumberOfTimes()
    (() => reader.getBestBlock).expects().onCall(() => Some(origin)).anyNumberOfTimes()
    reader.isHeldInPlace.expects(*).onCall((hash: ByteString) => held.header.hash.value == hash).anyNumberOfTimes()
    (() => reader.heldInPlace)
      .expects()
      .onCall(() => Some((held.header.number.value, held.header.hash.value)))
      .anyNumberOfTimes()

    val creator: PoWBlockCreator = mock[PoWBlockCreator]
    (creator
      .getBlockForMining(_: Block, _: Boolean, _: Option[InMemoryWorldStateProxy])(using _: BlockchainConfig))
      .expects(*, *, *, *)
      .onCall { (parent: Block, _: Boolean, inherited: Option[InMemoryWorldStateProxy], _: BlockchainConfig) =>
        builds :+= (parent.header.hash -> inherited)
        answers.getOrElse(parent.header.hash, Nil) match
          case Left(fault) :: rest =>
            answers += parent.header.hash -> rest
            IO.raiseError(fault)
          case Right(block) :: rest =>
            answers += parent.header.hash -> (if rest.isEmpty then List(Right(block)) else rest)
            // the world a build leaves is not looked at here: nothing is built on it
            IO.pure(PendingBlockAndState(PendingBlock(block, Nil), null))
          case Nil => IO.raiseError(new IllegalStateException(s"no block was planned on ${parent.idTag}"))
      }
      .anyNumberOfTimes()

    val sync: TestProbe[SyncController.Command] = createTestProbe[SyncController.Command]()
    val asker: TestProbe[MockedMinerResponse] = createTestProbe[MockedMinerResponse]()
    val miner: ActorRef[MockedMiner.Command] = spawn(
      MockedMiner(reader, creator, sync.ref, new BlockchainConfigBuilder with TestInstanceConfigProvider {})
    )
    miner ! Send(StartMining, asker.ref)

    def order(blocks: Int): Unit =
      miner ! Send(MineBlocks(blocks, withTransactions = false, None), asker.ref)
      asker.expectMessage(MiningOrdered)

    def mined(block: Block): Unit =
      sync.expectMessage(SyncController.WrappedSyncProtocol(SyncProtocol.MinedBlock(block)))

    /** One second of the miner's clock, and the miner seen to have dealt with it: it answers what it is doing. */
    def tick(): MockedMinerResponse =
      manualTime.timePasses(1.second)
      miner ! Send(MineBlocks(1, withTransactions = false, None), asker.ref)
      asker.receiveMessage()

  "The mocked miner on the unified layout" should "build the next block on the state its parent's import left, and wait for it" taggedAs
    (UnitTest, ConsensusTest) in {
      val rig = new Rig
      val first = childOf(origin)
      val second = childOf(first)
      rig.answers = Map(origin.header.hash -> List(Right(first)), first.header.hash -> List(Right(second)))

      rig.order(2)
      rig.mined(first)
      // a second, then another: the first block is not imported yet, and nothing is built on it
      rig.tick() shouldBe MinerIsWorking
      rig.tick() shouldBe MinerIsWorking
      rig.builds.map(_._1) shouldBe Vector(origin.header.hash)
      rig.sync.expectNoMessage(50.millis)

      // its import commits: the state on disk is the first block's
      rig.held = first
      manualTime.timePasses(1.second)
      rig.mined(second)
      // each on its parent, and neither on the world of a build
      rig.builds shouldBe Vector(origin.header.hash -> None, first.header.hash -> None)
    }

  it should "give the run up when the import does not come within its bound" taggedAs (UnitTest, ConsensusTest) in {
    val rig = new Rig
    val first = childOf(origin)
    rig.answers = Map(origin.header.hash -> List(Right(first)))
    rig.order(2)
    rig.mined(first)
    // ten ticks waited through
    (1 to MockedMiner.MaxImportWaits).foreach(_ => rig.tick() shouldBe MinerIsWorking)
    // the eleventh ends the run: the miner takes an order again
    rig.tick() shouldBe MiningOrdered
    rig.builds.count(_._1 == first.header.hash) shouldBe 0
  }

  it should "stop at once when the state on disk is another block's at that height" taggedAs (UnitTest, ConsensusTest) in {
    val rig = new Rig
    val first = childOf(origin)
    rig.answers = Map(origin.header.hash -> List(Right(first)))
    rig.order(2)
    rig.mined(first)
    // a block beside the one that was mined is the one that was imported
    rig.held = childOf(origin, salt = 7)
    // no wait: the first tick ends the run, and the miner takes an order again
    rig.tick() shouldBe MiningOrdered
    rig.builds.count(_._1 == first.header.hash) shouldBe 0
  }

  it should "wait the same way when the state moves between its look and its build" taggedAs (UnitTest, ConsensusTest) in {
    val rig = new Rig
    val first = childOf(origin)
    val second = childOf(first)
    rig.answers = Map(
      origin.header.hash -> List(Right(first)),
      // the first build on the first block meets a state that is not held; the next one is given its block
      first.header.hash -> List(Left(new StateUnavailable("the disk is between two blocks")), Right(second))
    )
    rig.order(2)
    rig.mined(first)
    rig.held = first
    rig.tick() shouldBe MinerIsWorking
    manualTime.timePasses(1.second)
    rig.mined(second)
    rig.builds.map(_._1) shouldBe Vector(origin.header.hash, first.header.hash, first.header.hash)
  }

  it should "end the run on a fault that is not of the state, as it always did" taggedAs (UnitTest, ConsensusTest) in {
    val rig = new Rig
    rig.answers = Map(origin.header.hash -> List(Left(new RuntimeException("no block"))))
    rig.order(1)
    rig.tick() shouldBe MiningOrdered
    rig.sync.expectNoMessage(50.millis)
  }
