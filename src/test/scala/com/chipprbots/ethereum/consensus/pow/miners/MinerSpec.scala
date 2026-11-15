package com.chipprbots.ethereum.consensus.pow.miners

import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe
import org.apache.pekko.util.ByteString

import scala.annotation.unused
import scala.concurrent.Future

import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.blockchain.sync.SyncController
import com.chipprbots.ethereum.blockchain.sync.SyncProtocol
import com.chipprbots.ethereum.consensus.pow.PoWMiningCoordinator
import com.chipprbots.ethereum.consensus.pow.PoWMiningCoordinator.CoordinatorProtocol
import com.chipprbots.ethereum.consensus.pow.PoWMiningMetrics
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.BlockHash
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.BlockchainConfig

/** B-275: a block the internal miner seals is counted in `pow_blocks_mined_total`, as one handed in by `eth_submitWork`
  * is. Before, only `EthMiningService.submitWork` recorded, so a node sealing with its own Ethash miner showed 0 blocks
  * mined.
  *
  * `PoWMiningMetrics` is one object for the whole JVM, so the assertions are differences between two readings (as in
  * `PoWMiningMetricsSpec`). Nothing in this suite mines in the background: `handleMiningResult` is called directly.
  */
class MinerSpec extends ScalaTestWithActorTestKit with AnyFlatSpecLike with Matchers:

  private object TestMiner extends Miner:
    def processMining(@unused bestBlock: Block)(implicit
        @unused blockchainConfig: BlockchainConfig
    ): Future[CoordinatorProtocol] =
      Future.failed(new UnsupportedOperationException("not used by this spec"))

  private val block: Block = Fixtures.Blocks.ValidBlock.block
  private val mixHash: ByteString = ByteString(Array.fill[Byte](32)(0x5a))
  private val nonce: ByteString = ByteString(Array.fill[Byte](8)(0x3c))

  private def minedTotal: Long = PoWMiningMetrics.blocksMinedTotal.value().toLong
  private def lastDurationMs: Long = PoWMiningMetrics.lastMiningDurationMs.value().toLong

  "Miner.handleMiningResult" should "count a successful result once, with the duration the miner gives" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    val sync: TestProbe[SyncController.Command] = createTestProbe[SyncController.Command]()
    val before = minedTotal

    val result = TestMiner.handleMiningResult(
      MinerProtocol.MiningSuccessful(1, mixHash, nonce),
      sync.ref,
      block,
      miningDurationMs = 4_321L
    )

    result shouldBe PoWMiningCoordinator.MiningSuccessful
    (minedTotal - before) shouldBe 1L
    lastDurationMs shouldBe 4_321L
    // The block handed on is unchanged by the count: the sealed header, the one message.
    sync.expectMessage(
      SyncController.WrappedSyncProtocol(
        SyncProtocol.MinedBlock(block.copy(header = block.header.copy(nonce = nonce, mixHash = BlockHash(mixHash))))
      )
    )
  }

  it should "record a duration of 0 when the miner does not give one, as eth_submitWork does" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    val sync: TestProbe[SyncController.Command] = createTestProbe[SyncController.Command]()
    val before = minedTotal

    TestMiner.handleMiningResult(MinerProtocol.MiningSuccessful(1, mixHash, nonce), sync.ref, block)

    (minedTotal - before) shouldBe 1L
    lastDurationMs shouldBe 0L
    sync.expectMessageType[SyncController.WrappedSyncProtocol]
  }

  it should "not count an unsuccessful result" taggedAs (UnitTest, ConsensusTest) in {
    val sync: TestProbe[SyncController.Command] = createTestProbe[SyncController.Command]()
    val before = minedTotal

    val result = TestMiner.handleMiningResult(MinerProtocol.MiningUnsuccessful(100), sync.ref, block, 9_999L)

    result shouldBe PoWMiningCoordinator.MiningUnsuccessful
    (minedTotal - before) shouldBe 0L
  }
