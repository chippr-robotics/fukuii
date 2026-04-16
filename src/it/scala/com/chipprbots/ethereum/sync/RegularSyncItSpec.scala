package com.chipprbots.ethereum.sync

import cats.effect.unsafe.IORuntime

import scala.concurrent.duration._

import com.typesafe.config.ConfigValueFactory
import io.prometheus.client.CollectorRegistry
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.FreeSpecBase
import com.chipprbots.ethereum.metrics.Metrics
import com.chipprbots.ethereum.metrics.MetricsConfig
import com.chipprbots.ethereum.sync.util.RegularSyncItSpecUtils.FakePeer
import com.chipprbots.ethereum.sync.util.SyncCommonItSpec._
import com.chipprbots.ethereum.utils.Config

import com.chipprbots.ethereum.testing.Tags._

class RegularSyncItSpec extends FreeSpecBase with Matchers with BeforeAndAfterAll {
  implicit val testRuntime: IORuntime = IORuntime.global

  override def beforeAll(): Unit = {
    // Clear metrics registry to prevent pollution from previous test runs
    CollectorRegistry.defaultRegistry.clear()
    Metrics.configure(
      MetricsConfig(Config.config.withValue("metrics.enabled", ConfigValueFactory.fromAnyRef(true)))
    )
  }

  override def afterAll(): Unit = {
    // No need to shutdown IORuntime.global
  }

  "peer 2 should sync to the top of peer1 blockchain" - {
    "given a previously imported blockchain" taggedAs (IntegrationTest, SyncTest, SlowTest) in customTestCaseResourceM(
      FakePeer.start2FakePeersRes()
    ) { case (peer1, peer2) =>
      val blockNumber: Int = 2000
      for {
        _ <- peer1.importBlocksUntil(blockNumber)(IdentityUpdate)
        _ <- peer2.startRegularSync()
        _ <- peer2.connectToPeers(Set(peer1.node))
        _ <- peer2.waitForRegularSyncLoadLastBlock(blockNumber)
      } yield assert(peer1.blockchainReader.getBestBlock().get.hash == peer2.blockchainReader.getBestBlock().get.hash)
    }

    "given a previously mined blockchain" taggedAs (IntegrationTest, SyncTest, SlowTest) in customTestCaseResourceM(
      FakePeer.start2FakePeersRes()
    ) { case (peer1, peer2) =>
      val blockHeadersPerRequest = peer2.syncConfig.blockHeadersPerRequest
      for {
        _ <- peer1.startRegularSync()
        _ <- peer1.mineNewBlocks(500.milliseconds, blockHeadersPerRequest + 1)(IdentityUpdate)
        _ <- peer1.waitForRegularSyncLoadLastBlock(blockHeadersPerRequest + 1)
        _ <- peer2.startRegularSync()
        _ <- peer2.connectToPeers(Set(peer1.node))
        _ <- peer2.waitForRegularSyncLoadLastBlock(blockHeadersPerRequest + 1)
      } yield assert(peer1.blockchainReader.getBestBlock().get.hash == peer2.blockchainReader.getBestBlock().get.hash)
    }
  }

  "peers should keep synced the same blockchain while their progressing forward" taggedAs (
    IntegrationTest,
    SyncTest,
    SlowTest
  ) in customTestCaseResourceM(
    FakePeer.start2FakePeersRes()
  ) { case (peer1, peer2) =>
    val blockNumer: Int = 2000
    for {
      _ <- peer1.importBlocksUntil(blockNumer)(IdentityUpdate)
      _ <- peer1.startRegularSync()
      _ <- peer2.startRegularSync()
      _ <- peer2.connectToPeers(Set(peer1.node))
      _ <- peer2.waitForRegularSyncLoadLastBlock(blockNumer)
      _ <- peer2.mineNewBlocks(100.milliseconds, 2)(IdentityUpdate)
      _ <- peer1.waitForRegularSyncLoadLastBlock(blockNumer + 2)
      _ <- peer1.mineNewBlocks(100.milliseconds, 2)(IdentityUpdate)
      _ <- peer2.waitForRegularSyncLoadLastBlock(blockNumer + 4)
    } yield assert(peer1.blockchainReader.getBestBlock().get.hash == peer2.blockchainReader.getBestBlock().get.hash)
  }

  "peers with divergent chains will be forced to resolve branches" taggedAs (
    IntegrationTest,
    SyncTest,
    SlowTest
  ) in customTestCaseResourceM(
    FakePeer.start2FakePeersRes()
  ) { case (peer1, peer2) =>
    val blockNumer: Int = 2000
    for {
      _ <- peer1.importBlocksUntil(blockNumer)(IdentityUpdate)
      _ <- peer2.importBlocksUntil(blockNumer)(IdentityUpdate)
      _ <- peer1.startRegularSync()
      _ <- peer2.startRegularSync()
      _ <- peer1.mineNewBlock()(IdentityUpdate)
      _ <- peer2.mineNewBlocks(100.milliseconds, 3)(IdentityUpdate)
      _ <- peer2.waitForRegularSyncLoadLastBlock(blockNumer + 3)
      _ <- peer1.waitForRegularSyncLoadLastBlock(blockNumer + 1)
      _ <- peer2.connectToPeers(Set(peer1.node))
      _ <- peer1.waitForRegularSyncLoadLastBlock(blockNumer + 3)
      _ <- peer2.waitForRegularSyncLoadLastBlock(blockNumer + 3)
    } yield {
      assert(
        peer1.blockchainReader.getChainWeightByHash(
          peer1.blockchainReader.getBestBlock().get.hash
        ) == peer2.blockchainReader
          .getChainWeightByHash(
            peer2.blockchainReader.getBestBlock().get.hash
          )
      )
      (
        peer1.blockchainReader.getBlockByNumber(peer1.blockchainReader.getBestBranch(), blockNumer + 1),
        peer2.blockchainReader.getBlockByNumber(peer2.blockchainReader.getBestBranch(), blockNumer + 1)
      ) match {
        case (Some(blockP1), Some(blockP2)) =>
          assert(
            peer1.blockchainReader.getChainWeightByHash(blockP1.hash) == peer2.blockchainReader.getChainWeightByHash(
              blockP2.hash
            )
          )
        case (_, _) => fail("invalid difficulty validation")
      }
    }
  }

  "A metric about mining a new block should be available" taggedAs (
    IntegrationTest,
    SyncTest,
    SlowTest
  ) in customTestCaseResourceM(
    FakePeer.start2FakePeersRes()
  ) { case (peer1, peer2) =>
    import MetricsHelper._

    val minedMetricBefore = sampleMetric(TimerCountMetric, MinedBlockPropagation)
    val defaultMetricBefore = sampleMetric(TimerCountMetric, DefaultBlockPropagation)

    for {
      _ <- peer1.startRegularSync()
      _ <- peer1.mineNewBlocks(10.milliseconds, 1)(IdentityUpdate)
      _ <- peer1.waitForRegularSyncLoadLastBlock(1)
      _ <- peer2.startRegularSync()
      _ <- peer2.connectToPeers(Set(peer1.node))
      _ <- peer2.waitForRegularSyncLoadLastBlock(1)
    } yield {

      val minedMetricAfter = sampleMetric(TimerCountMetric, MinedBlockPropagation).doubleValue()
      val defaultMetricAfter = sampleMetric(TimerCountMetric, DefaultBlockPropagation).doubleValue()

      minedMetricAfter shouldBe minedMetricBefore + 1.0d
      defaultMetricAfter shouldBe defaultMetricBefore + 1.0d
    }
  }

  object MetricsHelper {
    val TimerCountMetric = "app_regularsync_blocks_propagation_timer_seconds_count"
    val DefaultBlockPropagation = "DefaultBlockPropagation"
    val MinedBlockPropagation = "MinedBlockPropagation"
    def sampleMetric(metricName: String, blockType: String): Double = {
      val value = CollectorRegistry.defaultRegistry.getSampleValue(
        metricName,
        Array("blocktype"),
        Array(blockType)
      )
      if (value == null) 0.0 else value
    }
  }
}
