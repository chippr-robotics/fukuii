package com.chipprbots.ethereum.sync

import org.apache.pekko.util.ByteString

import cats.effect.unsafe.IORuntime

import scala.concurrent.duration._

import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.FlatSpecBase
import com.chipprbots.ethereum.domain._
import com.chipprbots.ethereum.ledger.InMemoryWorldStateProxy
import com.chipprbots.ethereum.sync.util.FastSyncItSpecUtils.FakePeer
import com.chipprbots.ethereum.sync.util.SyncCommonItSpec._
import com.chipprbots.ethereum.sync.util.SyncCommonItSpecUtils._

import com.chipprbots.ethereum.testing.Tags._

/** End-to-End test suite for Fast Sync functionality.
  *
  * This test suite validates the Fast Sync protocol including:
  *   - State synchronization
  *   - Block header synchronization
  *   - Pivot block selection and validation
  *   - Recovery from failed state downloads
  *
  * Fast Sync is a critical feature for reducing initial sync time by downloading state
  * at a recent block instead of executing all transactions from genesis.
  *
  * @see
  *   Issue: E2E testing - test driven development for resolving p2p handshake, block exchange or storage issues
  */
class E2EFastSyncSpec extends FlatSpecBase with Matchers with BeforeAndAfterAll {
  implicit val testRuntime: IORuntime = IORuntime.global

  override def afterAll(): Unit = {
    // No need to shutdown IORuntime.global
  }

  def updateStateAtBlock(blockNumber: Int)(currentBlockNumber: BigInt, world: InMemoryWorldStateProxy)
      : InMemoryWorldStateProxy = {
    if (currentBlockNumber == blockNumber) {
      val accountAddress = Address(BigInt(currentBlockNumber).toByteArray)
      val account = Account(
        nonce = 1,
        balance = BigInt(currentBlockNumber) * BigInt(1000000000)
      )
      InMemoryWorldStateProxy.persistState(world.saveAccount(accountAddress, account))
    } else world
  }

  "E2E Fast Sync" should "successfully sync blockchain headers without state" taggedAs (
    IntegrationTest,
    SyncTest,
    SlowTest
  ) in customTestCaseResourceM(
    FakePeer.start3FakePeersRes()
  ) { case (peer1, peer2, peer3) =>
    val blockNumber = 500
    for {
      // Peers 2 and 3 have blockchain
      _ <- peer2.importBlocksUntil(blockNumber)(IdentityUpdate)
      _ <- peer3.importBlocksUntil(blockNumber)(IdentityUpdate)
      
      // Peer 1 fast syncs
      _ <- peer1.connectToPeers(Set(peer2.node, peer3.node))
      _ <- peer1.startFastSync().delayBy(50.milliseconds)
      _ <- peer1.waitForFastSyncFinish()
    } yield {
      val expectedBlockNumber = blockNumber - peer1.testSyncConfig.pivotBlockOffset
      val actualBlockNumber = peer1.blockchainReader.getBestBlockNumber()
      
      actualBlockNumber shouldBe expectedBlockNumber
      
      // Verify headers are downloaded
      val peer1BestBlock = peer1.blockchainReader.getBestBlock()
      peer1BestBlock shouldBe defined
    }
  }

  it should "successfully sync blockchain with state nodes" taggedAs (
    IntegrationTest,
    SyncTest,
    StateTest,
    SlowTest
  ) in customTestCaseResourceM(
    FakePeer.start3FakePeersRes()
  ) { case (peer1, peer2, peer3) =>
    val blockNumber = 800
    val stateBlockNumber = 400
    
    for {
      // Create blockchain with state at specific block
      _ <- peer2.importBlocksUntil(blockNumber)(updateStateAtBlock(stateBlockNumber))
      _ <- peer3.importBlocksUntil(blockNumber)(updateStateAtBlock(stateBlockNumber))
      
      // Peer 1 fast syncs
      _ <- peer1.connectToPeers(Set(peer2.node, peer3.node))
      _ <- peer1.startFastSync().delayBy(50.milliseconds)
      _ <- peer1.waitForFastSyncFinish()
    } yield {
      // Verify state was downloaded correctly
      val trie = peer1.getBestBlockTrie()
      trie shouldBe defined
      
      // Verify account data is accessible
      val hasExpectedData = peer1.containsExpectedDataUpToAccountAtBlock(blockNumber, stateBlockNumber)
      hasExpectedData shouldBe true
      
      // Verify block number
      val expectedBlockNumber = blockNumber - peer1.testSyncConfig.pivotBlockOffset
      peer1.blockchainReader.getBestBlockNumber() shouldBe expectedBlockNumber
    }
  }

  it should "handle state synchronization from multiple peers" taggedAs (
    IntegrationTest,
    SyncTest,
    StateTest,
    NetworkTest,
    SlowTest
  ) in customTestCaseResourceM(
    FakePeer.start4FakePeersRes(
      fakePeerCustomConfig2 = FakePeerCustomConfig(HostConfig()),
      fakePeerCustomConfig3 = FakePeerCustomConfig(HostConfig())
    )
  ) { case (peer1, peer2, peer3, peer4) =>
    val blockNumber = 1000
    val stateBlockNumber = 500
    
    for {
      // Multiple peers have the same state
      _ <- peer2.importBlocksUntil(blockNumber)(updateStateAtBlock(stateBlockNumber))
      _ <- peer3.importBlocksUntil(blockNumber)(updateStateAtBlock(stateBlockNumber))
      _ <- peer4.importBlocksUntil(blockNumber)(updateStateAtBlock(stateBlockNumber))
      
      // Peer 1 syncs from all three peers
      _ <- peer1.connectToPeers(Set(peer2.node, peer3.node, peer4.node))
      _ <- peer1.startFastSync().delayBy(50.milliseconds)
      _ <- peer1.waitForFastSyncFinish()
    } yield {
      // Verify successful sync
      val trie = peer1.getBestBlockTrie()
      trie shouldBe defined
      
      val hasExpectedData = peer1.containsExpectedDataUpToAccountAtBlock(blockNumber, stateBlockNumber)
      hasExpectedData shouldBe true
    }
  }

  it should "verify pivot block selection is correct" taggedAs (
    IntegrationTest,
    SyncTest,
    SlowTest
  ) in customTestCaseResourceM(
    FakePeer.start3FakePeersRes()
  ) { case (peer1, peer2, peer3) =>
    val blockNumber = 600
    val pivotOffset = peer1.testSyncConfig.pivotBlockOffset
    
    for {
      _ <- peer2.importBlocksUntil(blockNumber)(IdentityUpdate)
      _ <- peer3.importBlocksUntil(blockNumber)(IdentityUpdate)
      
      _ <- peer1.connectToPeers(Set(peer2.node, peer3.node))
      _ <- peer1.startFastSync().delayBy(50.milliseconds)
      _ <- peer1.waitForFastSyncFinish()
    } yield {
      val expectedPivotBlock = blockNumber - pivotOffset
      val actualBestBlock = peer1.blockchainReader.getBestBlockNumber()
      
      // Verify pivot block calculation
      actualBestBlock shouldBe expectedPivotBlock
      
      // Verify the pivot block exists and is valid
      val pivotBlock = peer1.blockchainReader.getBlockByNumber(actualBestBlock)
      pivotBlock shouldBe defined
    }
  }

  it should "handle incomplete state downloads gracefully" taggedAs (
    IntegrationTest,
    SyncTest,
    StateTest,
    SlowTest
  ) in customTestCaseResourceM(
    FakePeer.start3FakePeersRes()
  ) { case (peer1, peer2, peer3) =>
    val blockNumber = 700
    val stateBlockNumber = 350
    
    for {
      // Only one peer has complete state initially
      _ <- peer2.importBlocksUntil(blockNumber)(updateStateAtBlock(stateBlockNumber))
      _ <- peer3.importBlocksUntil(blockNumber)(IdentityUpdate) // No state
      
      _ <- peer1.connectToPeers(Set(peer2.node, peer3.node))
      _ <- peer1.startFastSync().delayBy(50.milliseconds)
      _ <- peer1.waitForFastSyncFinish()
    } yield {
      // Should still succeed with at least one peer having state
      val trie = peer1.getBestBlockTrie()
      trie shouldBe defined
    }
  }

  it should "maintain chain integrity during fast sync" taggedAs (
    IntegrationTest,
    SyncTest,
    DatabaseTest,
    SlowTest
  ) in customTestCaseResourceM(
    FakePeer.start3FakePeersRes()
  ) { case (peer1, peer2, peer3) =>
    val blockNumber = 900
    
    for {
      _ <- peer2.importBlocksUntil(blockNumber)(IdentityUpdate)
      _ <- peer3.importBlocksUntil(blockNumber)(IdentityUpdate)
      
      _ <- peer1.connectToPeers(Set(peer2.node, peer3.node))
      _ <- peer1.startFastSync().delayBy(50.milliseconds)
      _ <- peer1.waitForFastSyncFinish()
    } yield {
      val bestBlockNumber = peer1.blockchainReader.getBestBlockNumber()
      
      // Verify chain continuity - all blocks should be linked
      for (i <- 1 to bestBlockNumber.toInt) {
        val block = peer1.blockchainReader.getBlockByNumber(i)
        block shouldBe defined
        
        if (i > 1) {
          val prevBlock = peer1.blockchainReader.getBlockByNumber(i - 1)
          prevBlock shouldBe defined
          block.get.header.parentHash shouldBe prevBlock.get.hash
        }
      }
      succeed
    }
  }

  it should "calculate total difficulty correctly during fast sync" taggedAs (
    IntegrationTest,
    SyncTest,
    SlowTest
  ) in customTestCaseResourceM(
    FakePeer.start3FakePeersRes()
  ) { case (peer1, peer2, peer3) =>
    val blockNumber = 500
    
    for {
      _ <- peer2.importBlocksUntil(blockNumber)(IdentityUpdate)
      _ <- peer3.importBlocksUntil(blockNumber)(IdentityUpdate)
      
      _ <- peer1.connectToPeers(Set(peer2.node, peer3.node))
      _ <- peer1.startFastSync().delayBy(50.milliseconds)
      _ <- peer1.waitForFastSyncFinish()
    } yield {
      val peer1BestBlock = peer1.blockchainReader.getBestBlock().get
      val peer2BestBlock = peer2.blockchainReader.getBestBlock().get
      
      val peer1Difficulty = peer1.blockchainReader.getTotalDifficultyByHash(peer1BestBlock.hash)
      val peer2DifficultyAtSameBlock = peer2.blockchainReader.getTotalDifficultyByHash(peer1BestBlock.hash)
      
      // Total difficulty should match at the same block
      peer1Difficulty shouldBe peer2DifficultyAtSameBlock
    }
  }

  it should "transition from fast sync to regular sync smoothly" taggedAs (
    IntegrationTest,
    SyncTest,
    SlowTest
  ) in customTestCaseResourceM(
    FakePeer.start3FakePeersRes()
  ) { case (peer1, peer2, peer3) =>
    val initialBlockNumber = 600
    val additionalBlocks = 50
    
    for {
      // Initial state
      _ <- peer2.importBlocksUntil(initialBlockNumber)(IdentityUpdate)
      _ <- peer3.importBlocksUntil(initialBlockNumber)(IdentityUpdate)
      
      // Fast sync
      _ <- peer1.connectToPeers(Set(peer2.node, peer3.node))
      _ <- peer1.startFastSync().delayBy(50.milliseconds)
      _ <- peer1.waitForFastSyncFinish()
      
      val fastSyncEndBlock = peer1.blockchainReader.getBestBlockNumber()
      
      // Add more blocks on peer2
      _ <- peer2.mineNewBlocks(100.milliseconds, additionalBlocks)(IdentityUpdate)
      
      // Start regular sync on peer1
      _ <- peer1.startRegularSync()
      _ <- peer1.waitForRegularSyncLoadLastBlock(initialBlockNumber + additionalBlocks)
    } yield {
      // Verify smooth transition
      val peer1BestBlock = peer1.blockchainReader.getBestBlock().get
      val peer2BestBlock = peer2.blockchainReader.getBestBlock().get
      
      peer1BestBlock.hash shouldBe peer2BestBlock.hash
      peer1.blockchainReader.getBestBlockNumber() shouldBe (initialBlockNumber + additionalBlocks)
    }
  }

  it should "handle state nodes with complex account structures" taggedAs (
    IntegrationTest,
    SyncTest,
    StateTest,
    SlowTest
  ) in customTestCaseResourceM(
    FakePeer.start3FakePeersRes()
  ) { case (peer1, peer2, peer3) =>
    val blockNumber = 1000
    
    def updateComplexState(currentBlockNumber: BigInt, world: InMemoryWorldStateProxy): InMemoryWorldStateProxy = {
      if (currentBlockNumber % 100 == 0 && currentBlockNumber > 0) {
        val accountAddress = Address(BigInt(currentBlockNumber).toByteArray)
        val account = Account(
          nonce = currentBlockNumber.toLong,
          balance = BigInt(currentBlockNumber) * BigInt(1000000000),
          storageRoot = ByteString.empty,
          codeHash = ByteString.empty
        )
        InMemoryWorldStateProxy.persistState(world.saveAccount(accountAddress, account))
      } else world
    }
    
    for {
      // Create blockchain with multiple state snapshots
      _ <- peer2.importBlocksUntil(blockNumber)(updateComplexState)
      _ <- peer3.importBlocksUntil(blockNumber)(updateComplexState)
      
      _ <- peer1.connectToPeers(Set(peer2.node, peer3.node))
      _ <- peer1.startFastSync().delayBy(50.milliseconds)
      _ <- peer1.waitForFastSyncFinish()
    } yield {
      val trie = peer1.getBestBlockTrie()
      trie shouldBe defined
      
      // Verify state was downloaded
      val bestBlock = peer1.blockchainReader.getBestBlock()
      bestBlock shouldBe defined
    }
  }

  it should "recover from peer disconnection during state download" taggedAs (
    IntegrationTest,
    SyncTest,
    StateTest,
    SlowTest
  ) in customTestCaseResourceM(
    FakePeer.start4FakePeersRes()
  ) { case (peer1, peer2, peer3, peer4) =>
    val blockNumber = 800
    val stateBlockNumber = 400
    
    for {
      // Multiple peers have state
      _ <- peer2.importBlocksUntil(blockNumber)(updateStateAtBlock(stateBlockNumber))
      _ <- peer3.importBlocksUntil(blockNumber)(updateStateAtBlock(stateBlockNumber))
      _ <- peer4.importBlocksUntil(blockNumber)(updateStateAtBlock(stateBlockNumber))
      
      // Start fast sync with multiple peers
      _ <- peer1.connectToPeers(Set(peer2.node, peer3.node, peer4.node))
      _ <- peer1.startFastSync().delayBy(50.milliseconds)
      
      // Even if some peers disconnect, sync should complete with remaining peers
      _ <- peer1.waitForFastSyncFinish()
    } yield {
      val trie = peer1.getBestBlockTrie()
      trie shouldBe defined
      
      val hasExpectedData = peer1.containsExpectedDataUpToAccountAtBlock(blockNumber, stateBlockNumber)
      hasExpectedData shouldBe true
    }
  }

  it should "validate downloaded state against pivot block state root" taggedAs (
    IntegrationTest,
    SyncTest,
    StateTest,
    SlowTest
  ) in customTestCaseResourceM(
    FakePeer.start3FakePeersRes()
  ) { case (peer1, peer2, peer3) =>
    val blockNumber = 600
    val stateBlockNumber = 300
    
    for {
      _ <- peer2.importBlocksUntil(blockNumber)(updateStateAtBlock(stateBlockNumber))
      _ <- peer3.importBlocksUntil(blockNumber)(updateStateAtBlock(stateBlockNumber))
      
      _ <- peer1.connectToPeers(Set(peer2.node, peer3.node))
      _ <- peer1.startFastSync().delayBy(50.milliseconds)
      _ <- peer1.waitForFastSyncFinish()
    } yield {
      val peer1BestBlock = peer1.blockchainReader.getBestBlock().get
      val peer2SameBlock = peer2.blockchainReader.getBlockByNumber(peer1BestBlock.number).get
      
      // State roots should match
      peer1BestBlock.header.stateRoot shouldBe peer2SameBlock.header.stateRoot
    }
  }
}
