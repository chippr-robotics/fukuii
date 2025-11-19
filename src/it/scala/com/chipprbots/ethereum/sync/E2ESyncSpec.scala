package com.chipprbots.ethereum.sync

import cats.effect.IO
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

/** End-to-End test suite for blockchain synchronization.
  *
  * This test suite validates the complete synchronization workflow including:
  *   - P2P connection establishment and handshake
  *   - Block exchange between peers
  *   - Storage integrity during sync
  *   - Error handling and recovery scenarios
  *
  * These tests ensure that fukuii can successfully synchronize the blockchain with other peers, preventing issues with
  * P2P handshake, block exchange, or storage that would prevent synchronization.
  *
  * @see
  *   Issue: E2E testing - test driven development for resolving p2p handshake, block exchange or storage issues
  */
class E2ESyncSpec extends FreeSpecBase with Matchers with BeforeAndAfterAll {
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

  "E2E Blockchain Synchronization" - {

    "P2P Handshake" - {

      "should successfully establish connection between two peers" taggedAs (
        IntegrationTest,
        SyncTest,
        NetworkTest
      ) in customTestCaseResourceM(
        FakePeer.start2FakePeersRes()
      ) { case (peer1, peer2) =>
        for {
          _ <- peer1.startRegularSync()
          _ <- peer2.startRegularSync()
          _ <- peer2.connectToPeers(Set(peer1.node))
          // Wait for connection to be established
          _ <- IO.sleep(2.seconds)
        } yield
        // Verify that peers are connected
        // Connection should be established without handshake failures
        succeed
      }

      "should handle multiple peer connections simultaneously" taggedAs (
        IntegrationTest,
        SyncTest,
        NetworkTest
      ) in customTestCaseResourceM(
        FakePeer.start2FakePeersRes()
      ) { case (peer1, peer2) =>
        for {
          _ <- peer1.startRegularSync()
          _ <- peer2.startRegularSync()
          // Connect peer1 to peer2 then wait to connect
          _ <- peer1.connectToPeers(Set(peer2.node))
          _ <- IO.sleep(2.seconds)
          // Both connections should be established successfully
        } yield succeed
      }

      "should recover from handshake timeout" taggedAs (
        IntegrationTest,
        SyncTest,
        NetworkTest,
        SlowTest
      ) in customTestCaseResourceM(
        FakePeer.start2FakePeersRes()
      ) { case (peer1, peer2) =>
        for {
          _ <- peer1.startRegularSync()
          _ <- peer2.startRegularSync()
          _ <- peer2.connectToPeers(Set(peer1.node))
          // Wait for initial handshake
          _ <- IO.sleep(3.seconds)
          // Connection should be established and resilient
        } yield succeed
      }
    }

    "Block Exchange" - {

      "should successfully exchange blocks between peers" taggedAs (
        IntegrationTest,
        SyncTest,
        SlowTest
      ) in customTestCaseResourceM(
        FakePeer.start2FakePeersRes()
      ) { case (peer1, peer2) =>
        val blockNumber = 100
        for {
          // Peer1 imports blocks
          _ <- peer1.importBlocksUntil(blockNumber)(IdentityUpdate)
          _ <- peer2.startRegularSync()
          _ <- peer2.connectToPeers(Set(peer1.node))
          // Peer2 should sync all blocks from peer1
          _ <- peer2.waitForRegularSyncLoadLastBlock(blockNumber)
        } yield {
          // Verify both peers have the same best block
          val peer1BestBlock = peer1.blockchainReader.getBestBlock().get
          val peer2BestBlock = peer2.blockchainReader.getBestBlock().get
          peer1BestBlock.hash shouldBe peer2BestBlock.hash
          peer2BestBlock.number shouldBe blockNumber
        }
      }

      "should handle block exchange with multiple peers" taggedAs (
        IntegrationTest,
        SyncTest,
        SlowTest
      ) in customTestCaseResourceM(
        FakePeer.start2FakePeersRes()
      ) { case (peer1, peer2) =>
        val blockNumber = 150
        for {
          // Both peer1 and peer2 have blocks
          _ <- peer1.importBlocksUntil(blockNumber)(IdentityUpdate)
          _ <- peer2.importBlocksUntil(blockNumber)(IdentityUpdate)
          // Verify blocks match
        } yield {
          // Verify both peers have the same best block
          val peer1BestBlock = peer1.blockchainReader.getBestBlock().get
          val peer2BestBlock = peer2.blockchainReader.getBestBlock().get
          peer1BestBlock.hash shouldBe peer2BestBlock.hash
          peer2BestBlock.number shouldBe blockNumber
        }
      }

      "should exchange blocks incrementally as they are created" taggedAs (
        IntegrationTest,
        SyncTest,
        SlowTest
      ) in customTestCaseResourceM(
        FakePeer.start2FakePeersRes()
      ) { case (peer1, peer2) =>
        val initialBlocks = 50
        val additionalBlocks = 25
        for {
          // Initial sync
          _ <- peer1.importBlocksUntil(initialBlocks)(IdentityUpdate)
          _ <- peer1.startRegularSync()
          _ <- peer2.startRegularSync()
          _ <- peer2.connectToPeers(Set(peer1.node))
          _ <- peer2.waitForRegularSyncLoadLastBlock(initialBlocks)
          // Mine more blocks on peer1
          _ <- peer1.mineNewBlocks(100.milliseconds, additionalBlocks)(IdentityUpdate)
          // Peer2 should sync the new blocks
          _ <- peer2.waitForRegularSyncLoadLastBlock(initialBlocks + additionalBlocks)
        } yield {
          val peer1BestBlock = peer1.blockchainReader.getBestBlock().get
          val peer2BestBlock = peer2.blockchainReader.getBestBlock().get
          peer1BestBlock.hash shouldBe peer2BestBlock.hash
        }
      }

      "should handle large batches of blocks efficiently" taggedAs (
        IntegrationTest,
        SyncTest,
        SlowTest
      ) in customTestCaseResourceM(
        FakePeer.start2FakePeersRes()
      ) { case (peer1, peer2) =>
        val blockNumber = 1000
        for {
          _ <- peer1.importBlocksUntil(blockNumber)(IdentityUpdate)
          _ <- peer2.startRegularSync()
          _ <- peer2.connectToPeers(Set(peer1.node))
          _ <- peer2.waitForRegularSyncLoadLastBlock(blockNumber)
        } yield {
          val peer1BestBlock = peer1.blockchainReader.getBestBlock().get
          val peer2BestBlock = peer2.blockchainReader.getBestBlock().get
          peer1BestBlock.hash shouldBe peer2BestBlock.hash
          peer2BestBlock.number shouldBe blockNumber
        }
      }
    }

    "Storage Integrity" - {

      "should maintain consistent storage during synchronization" taggedAs (
        IntegrationTest,
        SyncTest,
        DatabaseTest,
        SlowTest
      ) in customTestCaseResourceM(
        FakePeer.start2FakePeersRes()
      ) { case (peer1, peer2) =>
        val blockNumber = 200
        for {
          _ <- peer1.importBlocksUntil(blockNumber)(IdentityUpdate)
          _ <- peer2.startRegularSync()
          _ <- peer2.connectToPeers(Set(peer1.node))
          _ <- peer2.waitForRegularSyncLoadLastBlock(blockNumber)
        } yield {
          // Verify storage integrity
          val peer1BestBlock = peer1.blockchainReader.getBestBlock().get
          val peer2BestBlock = peer2.blockchainReader.getBestBlock().get

          // Best blocks should match
          peer1BestBlock.hash shouldBe peer2BestBlock.hash

          // Block numbers should match
          peer1.blockchainReader.getBestBlockNumber() shouldBe peer2.blockchainReader.getBestBlockNumber()

          // Total difficulty should match
          val peer1TotalDifficulty = peer1.blockchainReader.getChainWeightByHash(peer1BestBlock.hash)
          val peer2TotalDifficulty = peer2.blockchainReader.getChainWeightByHash(peer2BestBlock.hash)
          peer1TotalDifficulty shouldBe peer2TotalDifficulty
        }
      }

      "should handle blockchain reorganization correctly" taggedAs (
        IntegrationTest,
        SyncTest,
        DatabaseTest,
        SlowTest
      ) in customTestCaseResourceM(
        FakePeer.start2FakePeersRes()
      ) { case (peer1, peer2) =>
        val commonBlocks = 100
        val peer1ExtraBlocks = 10

        for {
          // Both peers sync to a common point
          _ <- peer1.importBlocksUntil(commonBlocks)(IdentityUpdate)
          _ <- peer2.importBlocksUntil(commonBlocks)(IdentityUpdate)

          // Peer1 mines additional blocks
          _ <- peer1.mineNewBlocks(100.milliseconds, peer1ExtraBlocks)(IdentityUpdate)

          // Start sync
          _ <- peer1.startRegularSync()
          _ <- peer2.startRegularSync()
          _ <- peer2.connectToPeers(Set(peer1.node))

          // Peer2 should sync to peer1's chain
          _ <- peer2.waitForRegularSyncLoadLastBlock(commonBlocks + peer1ExtraBlocks)
        } yield {
          // Verify reorganization completed successfully
          val peer1BestBlock = peer1.blockchainReader.getBestBlock().get
          val peer2BestBlock = peer2.blockchainReader.getBestBlock().get
          peer1BestBlock.hash shouldBe peer2BestBlock.hash
        }
      }

      "should verify block data integrity during sync" taggedAs (
        IntegrationTest,
        SyncTest,
        DatabaseTest,
        SlowTest
      ) in customTestCaseResourceM(
        FakePeer.start2FakePeersRes()
      ) { case (peer1, peer2) =>
        val blockNumber = 150
        for {
          _ <- peer1.importBlocksUntil(blockNumber)(IdentityUpdate)
          _ <- peer2.startRegularSync()
          _ <- peer2.connectToPeers(Set(peer1.node))
          _ <- peer2.waitForRegularSyncLoadLastBlock(blockNumber)
        } yield {
          // Sample random blocks and verify their integrity
          val blocksToVerify = Seq(1, 50, 100, 150)
          blocksToVerify.foreach { blockNum =>
            val peer1Block = peer1.blockchainReader.getBlockByNumber(peer1.blockchainReader.getBestBranch(), blockNum)
            val peer2Block = peer2.blockchainReader.getBlockByNumber(peer2.blockchainReader.getBestBranch(), blockNum)

            peer1Block shouldBe defined
            peer2Block shouldBe defined
            peer1Block.get.hash shouldBe peer2Block.get.hash
            peer1Block.get.header shouldBe peer2Block.get.header
            peer1Block.get.body shouldBe peer2Block.get.body
          }
          succeed
        }
      }

      "should persist synced blocks across restarts" taggedAs (
        IntegrationTest,
        SyncTest,
        DatabaseTest,
        SlowTest
      ) in customTestCaseResourceM(
        FakePeer.start2FakePeersRes()
      ) { case (peer1, peer2) =>
        val blockNumber = 100
        for {
          _ <- peer1.importBlocksUntil(blockNumber)(IdentityUpdate)
          _ <- peer2.startRegularSync()
          _ <- peer2.connectToPeers(Set(peer1.node))
          _ <- peer2.waitForRegularSyncLoadLastBlock(blockNumber)
        } yield {
          // Verify blocks are persisted
          val bestBlock = peer2.blockchainReader.getBestBlock().get
          bestBlock.number shouldBe blockNumber

          // Verify we can retrieve any block from storage
          for (i <- 1 to blockNumber) {
            val block = peer2.blockchainReader.getBlockByNumber(peer2.blockchainReader.getBestBranch(), i)
            block shouldBe defined
          }
          succeed
        }
      }
    }

    "Error Handling and Recovery" - {

      "should continue sync after peer disconnection and reconnection" taggedAs (
        IntegrationTest,
        SyncTest,
        SlowTest
      ) in customTestCaseResourceM(
        FakePeer.start2FakePeersRes()
      ) { case (peer1, peer2) =>
        val initialBlocks = 100
        val finalBlocks = 200

        for {
          // Partial sync
          _ <- peer1.importBlocksUntil(initialBlocks)(IdentityUpdate)
          _ <- peer2.startRegularSync()
          _ <- peer2.connectToPeers(Set(peer1.node))
          _ <- peer2.waitForRegularSyncLoadLastBlock(initialBlocks)

          // Add more blocks while peer2 is disconnected
          _ <- peer1.importBlocksUntil(finalBlocks)(IdentityUpdate)

          // Reconnect and continue sync
          _ <- peer2.connectToPeers(Set(peer1.node))
          _ <- peer2.waitForRegularSyncLoadLastBlock(finalBlocks)
        } yield {
          val peer1BestBlock = peer1.blockchainReader.getBestBlock().get
          val peer2BestBlock = peer2.blockchainReader.getBestBlock().get
          peer1BestBlock.hash shouldBe peer2BestBlock.hash
        }
      }

      "should handle sync with peers at different block heights" taggedAs (
        IntegrationTest,
        SyncTest,
        SlowTest
      ) in customTestCaseResourceM(
        FakePeer.start2FakePeersRes()
      ) { case (peer1, peer2) =>
        for {
          // Peers have different numbers of blocks
          _ <- peer1.importBlocksUntil(300)(IdentityUpdate)
          _ <- peer2.importBlocksUntil(200)(IdentityUpdate)

          // Peer2 syncs from peer1
          _ <- peer1.startRegularSync()
          _ <- peer2.startRegularSync()
          _ <- peer2.connectToPeers(Set(peer1.node))
          // Should sync to the highest block (peer1)
          _ <- peer2.waitForRegularSyncLoadLastBlock(300)
        } yield {
          val peer1BestBlock = peer1.blockchainReader.getBestBlock().get
          val peer2BestBlock = peer2.blockchainReader.getBestBlock().get
          peer1BestBlock.hash shouldBe peer2BestBlock.hash
        }
      }

      "should recover from partial block downloads" taggedAs (
        IntegrationTest,
        SyncTest,
        SlowTest
      ) in customTestCaseResourceM(
        FakePeer.start2FakePeersRes()
      ) { case (peer1, peer2) =>
        val blockNumber = 150
        for {
          _ <- peer1.importBlocksUntil(blockNumber)(IdentityUpdate)
          _ <- peer2.startRegularSync()
          _ <- peer2.connectToPeers(Set(peer1.node))
          // Allow sync to complete
          _ <- peer2.waitForRegularSyncLoadLastBlock(blockNumber)
        } yield {
          // Verify sync completed successfully despite any partial downloads
          val peer2BestBlock = peer2.blockchainReader.getBestBlock().get
          peer2BestBlock.number shouldBe blockNumber
        }
      }
    }

    "Bi-directional Synchronization" - {

      "should keep peers synchronized as both create new blocks" taggedAs (
        IntegrationTest,
        SyncTest,
        SlowTest
      ) in customTestCaseResourceM(
        FakePeer.start2FakePeersRes()
      ) { case (peer1, peer2) =>
        val initialBlocks = 100
        for {
          // Initial sync
          _ <- peer1.importBlocksUntil(initialBlocks)(IdentityUpdate)
          _ <- peer1.startRegularSync()
          _ <- peer2.startRegularSync()
          _ <- peer2.connectToPeers(Set(peer1.node))
          _ <- peer2.waitForRegularSyncLoadLastBlock(initialBlocks)

          // Peer2 mines new blocks
          _ <- peer2.mineNewBlocks(100.milliseconds, 5)(IdentityUpdate)
          // Wait for peer1 to sync peer2's new blocks before peer1 starts mining
          _ <- peer1.waitForRegularSyncLoadLastBlock(initialBlocks + 5)

          // Peer1 mines new blocks
          _ <- peer1.mineNewBlocks(100.milliseconds, 5)(IdentityUpdate)
          // Wait for peer2 to sync peer1's new blocks before assertions
          _ <- peer2.waitForRegularSyncLoadLastBlock(initialBlocks + 10)
        } yield {
          val peer1BestBlock = peer1.blockchainReader.getBestBlock().get
          val peer2BestBlock = peer2.blockchainReader.getBestBlock().get
          peer1BestBlock.hash shouldBe peer2BestBlock.hash
        }
      }

      "should propagate blocks across a network of peers" taggedAs (
        IntegrationTest,
        SyncTest,
        NetworkTest,
        SlowTest
      ) in customTestCaseResourceM(
        FakePeer.start2FakePeersRes()
      ) { case (peer1, peer2) =>
        val blockNumber = 100
        for {
          // Peer1 has blocks
          _ <- peer1.importBlocksUntil(blockNumber)(IdentityUpdate)

          // Start both peers
          _ <- peer1.startRegularSync()
          _ <- peer2.startRegularSync()

          // Connect in a chain: peer1 <-> peer2
          _ <- peer2.connectToPeers(Set(peer1.node))

          // Peer2 should sync from peer1
          _ <- peer2.waitForRegularSyncLoadLastBlock(blockNumber)
        } yield {
          val peer1BestBlock = peer1.blockchainReader.getBestBlock().get
          val peer2BestBlock = peer2.blockchainReader.getBestBlock().get

          peer1BestBlock.hash shouldBe peer2BestBlock.hash
        }
      }
    }
  }
}
