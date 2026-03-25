package com.chipprbots.ethereum.consensus.pow.miners

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.testkit.TestProbe
import org.apache.pekko.util.ByteString

import cats.effect.IO
import cats.effect.unsafe.IORuntime

import scala.concurrent.Await
import scala.concurrent.duration._

import org.bouncycastle.util.encoders.Hex
import org.scalamock.scalatest.MockFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.blockchain.sync.SyncProtocol
import com.chipprbots.ethereum.consensus.blocks.PendingBlockAndState
import com.chipprbots.ethereum.consensus.pow.EthashUtils
import com.chipprbots.ethereum.consensus.pow.PoWBlockCreator
import com.chipprbots.ethereum.consensus.pow.PoWMiningCoordinator
import com.chipprbots.ethereum.consensus.pow.miners.MinerProtocol._
import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.domain._
import com.chipprbots.ethereum.jsonrpc.EthMiningService
import com.chipprbots.ethereum.jsonrpc.EthMiningService.SubmitHashRateRequest
import com.chipprbots.ethereum.jsonrpc.EthMiningService.SubmitHashRateResponse
import com.chipprbots.ethereum.ledger.InMemoryWorldStateProxy
import com.chipprbots.ethereum.testing.Tags._
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.Config

/** Fast unit tests for EthashMiner control flow — no real DAG generation or PoW mining.
  * Verifies the same logic paths as EthashMinerSpec without CPU-intensive Ethash computation.
  */
class EthashMinerUnitSpec extends AnyFlatSpec with Matchers with MockFactory {

  implicit val runtime: IORuntime = IORuntime.global
  implicit val blockchainConfig: BlockchainConfig = Config.blockchains.blockchainConfig

  // ===== processMining error handling =====

  "EthashMiner" should "return MiningUnsuccessful when blockCreator throws" taggedAs (UnitTest, ConsensusTest) in new TestSetup {
    (mockBlockCreator
      .getBlockForMining(_: Block, _: Boolean, _: Option[InMemoryWorldStateProxy])(_: BlockchainConfig))
      .expects(*, *, *, *)
      .returning(IO.raiseError(new RuntimeException("No pending block")))

    val result = Await.result(miner.processMining(parentBlock), 5.seconds)
    result shouldBe PoWMiningCoordinator.MiningUnsuccessful
  }

  // ===== handleMiningResult =====

  it should "send MinedBlock to syncController on MiningSuccessful" taggedAs (UnitTest, ConsensusTest) in new TestSetup {
    val mixHash = ByteString(Hex.decode("ab" * 32))
    val nonce = ByteString(Hex.decode("cd" * 8))
    val miningResult = MiningSuccessful(100, mixHash, nonce)

    miner.handleMiningResult(miningResult, sync.ref, testBlock)

    val msg = sync.expectMsgClass(classOf[SyncProtocol.MinedBlock])
    msg.block.header.mixHash shouldBe mixHash
    msg.block.header.nonce shouldBe nonce
  }

  it should "return MiningUnsuccessful for unsuccessful mining result" taggedAs (UnitTest, ConsensusTest) in new TestSetup {
    val miningResult = MiningUnsuccessful(1000)
    val result = miner.handleMiningResult(miningResult, sync.ref, testBlock)
    result shouldBe PoWMiningCoordinator.MiningUnsuccessful
    sync.expectNoMessage(100.millis)
  }

  // ===== submitHashRate =====

  it should "report hash rate via ethMiningService" taggedAs (UnitTest, ConsensusTest) in new TestSetup {
    (mockEthMiningService.submitHashRate _)
      .expects(*)
      .returns(IO.pure(Right(SubmitHashRateResponse(true))))
      .once()

    miner.submitHashRate(mockEthMiningService, 1000000000L, MiningSuccessful(500, ByteString.empty, ByteString.empty))
  }

  // ===== Epoch boundary logic =====

  it should "calculate correct epoch for pre-ECIP-1099 blocks" taggedAs (UnitTest, ConsensusTest) in {
    val epochLength = EthashUtils.EPOCH_LENGTH_BEFORE_ECIP_1099
    EthashUtils.epoch(0, Long.MaxValue) shouldBe 0
    EthashUtils.epoch(epochLength - 1, Long.MaxValue) shouldBe 0
    EthashUtils.epoch(epochLength, Long.MaxValue) shouldBe 1
    EthashUtils.epoch(2 * epochLength, Long.MaxValue) shouldBe 2
  }

  // ===== Test Setup =====

  class TestSetup {
    implicit val system: ActorSystem = ActorSystem("EthashMinerUnitSpec")

    val sync: TestProbe = TestProbe()
    val mockBlockCreator: PoWBlockCreator = mock[PoWBlockCreator]
    val mockEthMiningService: EthMiningService = mock[EthMiningService]
    val mockEvmCodeStorage: EvmCodeStorage = mock[EvmCodeStorage]
    val mockMptStorage: MptStorage = mock[MptStorage]

    val fakeWorld: InMemoryWorldStateProxy = InMemoryWorldStateProxy(
      mockEvmCodeStorage,
      mockMptStorage,
      _ => None,
      UInt256.Zero,
      ByteString.empty,
      noEmptyAccounts = false,
      ethCompatibleStorage = true
    )

    val dagManager = new EthashDAGManager(mockBlockCreator)

    val miner = new EthashMiner(
      dagManager,
      mockBlockCreator,
      sync.ref,
      mockEthMiningService
    )

    val parentBlock: Block = Block(
      Fixtures.Blocks.Genesis.header.copy(difficulty = 1000, number = 0),
      BlockBody.empty
    )

    val testBlock: Block = Block(
      parentBlock.header.copy(
        parentHash = parentBlock.header.hash,
        number = 1,
        mixHash = ByteString.empty,
        nonce = ByteString.empty
      ),
      BlockBody.empty
    )
  }
}
