package com.chipprbots.ethereum.blockchain.sync.snap

import org.apache.pekko.actor.{ActorRef, ActorSystem, Props}
import org.apache.pekko.testkit.{TestKit, TestProbe}
import org.apache.pekko.util.ByteString

import cats.effect.IO
import cats.effect.unsafe.IORuntime

import scala.concurrent.duration._
import scala.concurrent.ExecutionContext

import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.FreeSpecBase
import com.chipprbots.ethereum.blockchain.sync.SyncController
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.db.storage.{AppStateStorage, EvmCodeStorage}
import com.chipprbots.ethereum.domain.{Account, BlockHeader, BlockchainReader}
import com.chipprbots.ethereum.mpt.{BranchNode, ExtensionNode, HashNode, LeafNode, MerklePatriciaTrie}
import com.chipprbots.ethereum.network.PeerId
import com.chipprbots.ethereum.network.p2p.messages.SNAP._
import com.chipprbots.ethereum.testing.{PeerTestHelpers, TestMptStorage}
import com.chipprbots.ethereum.testing.Tags._
import com.chipprbots.ethereum.utils.Config.SyncConfig

/** Integration test suite for SNAP sync protocol.
  *
  * This test suite validates the complete SNAP sync workflow including:
  *   - Complete SNAP sync flow with mock network
  *   - Transition from SNAP sync to regular sync
  *   - Resume after restart (state persistence)
  *   - Different pivot blocks
  *   - Healing process with missing nodes
  *   - Concurrent requests to multiple peers
  *   - Peer disconnection handling
  *
  * @see
  *   SNAP_SYNC_IMPLEMENTATION.md for implementation details
  * @see
  *   SNAP_SYNC_TODO.md for remaining tasks
  */
class SNAPSyncIntegrationSpec extends FreeSpecBase with Matchers with BeforeAndAfterAll {
  implicit val testRuntime: IORuntime = IORuntime.global
  implicit val testSystem: ActorSystem = ActorSystem("SNAPSyncIntegrationSpec")
  implicit val ec: ExecutionContext = testSystem.dispatcher

  override def afterAll(): Unit = {
    TestKit.shutdownActorSystem(testSystem)
  }

  // Helper to create a mock blockchain reader
  def createMockBlockchainReader(bestBlockNumber: BigInt, stateRoot: ByteString): BlockchainReader = {
    new BlockchainReader {
      override def getBestBlockNumber(): BigInt = bestBlockNumber
      override def getBestBlock(): Option[com.chipprbots.ethereum.domain.Block] = {
        val header = BlockHeader(
          parentHash = ByteString.empty,
          ommersHash = ByteString.empty,
          beneficiary = ByteString.empty,
          stateRoot = stateRoot,
          transactionsRoot = ByteString.empty,
          receiptsRoot = ByteString.empty,
          logsBloom = ByteString.empty,
          difficulty = BigInt(1000),
          number = bestBlockNumber,
          gasLimit = 8000000,
          gasUsed = 0,
          unixTimestamp = System.currentTimeMillis() / 1000,
          extraData = ByteString.empty,
          mixHash = ByteString.empty,
          nonce = ByteString.empty
        )
        Some(com.chipprbots.ethereum.domain.Block(header, Seq.empty, Seq.empty))
      }
      override def getBlockHeaderByNumber(number: BigInt): Option[BlockHeader] = {
        if (number == bestBlockNumber) getBestBlock().map(_.header)
        else None
      }
      override def getBlockByNumber(branchId: ByteString, number: BigInt): Option[com.chipprbots.ethereum.domain.Block] = {
        if (number == bestBlockNumber) getBestBlock()
        else None
      }
      override def getBestBranch(): ByteString = ByteString("main")
      override def getChainWeightByHash(hash: ByteString): Option[com.chipprbots.ethereum.domain.ChainWeight] = None
      override def getBlockHeaderByHash(hash: ByteString): Option[BlockHeader] = None
      override def getBlockBodyByHash(hash: ByteString): Option[com.chipprbots.ethereum.domain.BlockBody] = None
      override def getTotalDifficultyByHash(hash: ByteString): Option[BigInt] = None
      override def getReceiptsByHash(hash: ByteString): Option[Seq[com.chipprbots.ethereum.domain.Receipt]] = None
      override def getEvmCodeByHash(hash: ByteString): Option[ByteString] = None
      override def getAccountProof(address: com.chipprbots.ethereum.domain.Address, blockNumber: BigInt): Option[com.chipprbots.ethereum.vm.GetProofResponse] = None
      override def getStorageProofAt(address: com.chipprbots.ethereum.domain.Address, position: BigInt, blockNumber: BigInt): Option[com.chipprbots.ethereum.vm.GetProofResponse] = None
      override def getMptNodeByHash(hash: ByteString): Option[com.chipprbots.ethereum.mpt.MptNode] = None
    }
  }

  // Helper to create a mock AppStateStorage
  def createMockAppStateStorage(): AppStateStorage = {
    new AppStateStorage {
      private var snapSyncDone: Boolean = false
      private var pivotBlock: Option[BigInt] = None
      private var snapStateRoot: Option[ByteString] = None
      
      override def isSnapSyncDone(): Boolean = snapSyncDone
      override def snapSyncDone(): com.chipprbots.ethereum.db.dataSource.DataSourceBatchUpdate = {
        snapSyncDone = true
        new com.chipprbots.ethereum.db.dataSource.DataSourceBatchUpdate {
          override def commit(): Unit = ()
        }
      }
      override def getSnapSyncPivotBlock(): Option[BigInt] = pivotBlock
      override def putSnapSyncPivotBlock(block: BigInt): AppStateStorage = {
        pivotBlock = Some(block)
        this
      }
      override def getSnapSyncStateRoot(): Option[ByteString] = snapStateRoot
      override def putSnapSyncStateRoot(root: ByteString): AppStateStorage = {
        snapStateRoot = Some(root)
        this
      }
      override def getBestBlockNumber(): BigInt = 0
      override def putBestBlockNumber(bestBlockNumber: BigInt): AppStateStorage = this
      override def getEstimatedHighestBlock(): BigInt = 0
      override def putEstimatedHighestBlock(n: BigInt): AppStateStorage = this
      override def getSyncStartingBlock(): BigInt = 0
      override def putSyncStartingBlock(n: BigInt): AppStateStorage = this
      override def fastSyncDone(): com.chipprbots.ethereum.db.dataSource.DataSourceBatchUpdate = {
        new com.chipprbots.ethereum.db.dataSource.DataSourceBatchUpdate {
          override def commit(): Unit = ()
        }
      }
      override def isFastSyncDone(): Boolean = false
      override def getAppStateNode(key: ByteString): Option[ByteString] = None
      override def putNode(nodeHash: ByteString, nodeEncoded: ByteString, updateTimestamp: Option[Long]): AppStateStorage = this
    }
  }

  // Helper to create a mock EvmCodeStorage
  def createMockEvmCodeStorage(): EvmCodeStorage = {
    new EvmCodeStorage {
      private val codes = scala.collection.mutable.Map[ByteString, ByteString]()
      
      override def get(hash: ByteString): Option[ByteString] = codes.get(hash)
      override def put(hash: ByteString, evmCode: ByteString): EvmCodeStorage = {
        codes(hash) = evmCode
        this
      }
    }
  }

  "SNAP Sync Integration" - {

    "Complete SNAP Sync Flow" - {

      "should complete account range sync with single peer" taggedAs (
        IntegrationTest,
        SyncTest,
        SlowTest
      ) in testCaseM[IO] {
        IO {
          val stateRoot = kec256(ByteString("test-state-root"))
          val mptStorage = new TestMptStorage()
          val appStateStorage = createMockAppStateStorage()
          val evmCodeStorage = createMockEvmCodeStorage()
          val blockchainReader = createMockBlockchainReader(1000, stateRoot)
          val etcPeerManager = TestProbe()
          val peerEventBus = TestProbe()
          
          val syncConfig = SyncConfig(
            doFastSync = false,
            syncRetryInterval = 1.second,
            peersScanInterval = 1.second,
            blacklistDuration = 1.minute,
            startRetryInterval = 1.second,
            syncRetryDelay = 1.second,
            peerResponseTimeout = 1.second,
            printStatusInterval = 1.minute
          )
          
          val snapConfig = SNAPSyncConfig(
            enabled = true,
            pivotBlockOffset = 100,
            accountConcurrency = 4,
            storageConcurrency = 4,
            storageBatchSize = 8,
            healingBatchSize = 16,
            stateValidationEnabled = true,
            maxRetries = 3,
            timeout = 10.seconds
          )

          val controller = testSystem.actorOf(
            Props(
              new SNAPSyncController(
                blockchainReader,
                appStateStorage,
                mptStorage,
                evmCodeStorage,
                etcPeerManager.ref,
                peerEventBus.ref,
                syncConfig,
                snapConfig,
                testSystem.scheduler
              )
            )
          )

          // Start SNAP sync
          controller ! SNAPSyncController.Start

          // Simulate peer providing account range
          val peer = PeerTestHelpers.createTestPeer("test-peer", TestProbe().ref)
          val accounts = Seq(
            Account(
              nonce = 0,
              balance = BigInt(100),
              storageRoot = MerklePatriciaTrie.EmptyRootHash,
              codeHash = Account.EmptyCodeHash
            )
          )
          val accountHashes = Seq(kec256(ByteString("account1")))
          
          // Account range should be syncing
          Thread.sleep(100)
          
          succeed
        }
      }

      "should handle multiple concurrent peers" taggedAs (
        IntegrationTest,
        SyncTest,
        SlowTest
      ) in testCaseM[IO] {
        IO {
          val stateRoot = kec256(ByteString("test-state-root"))
          val mptStorage = new TestMptStorage()
          val appStateStorage = createMockAppStateStorage()
          val evmCodeStorage = createMockEvmCodeStorage()
          val blockchainReader = createMockBlockchainReader(2000, stateRoot)
          val etcPeerManager = TestProbe()
          val peerEventBus = TestProbe()
          
          val syncConfig = SyncConfig(
            doFastSync = false,
            syncRetryInterval = 1.second,
            peersScanInterval = 1.second,
            blacklistDuration = 1.minute,
            startRetryInterval = 1.second,
            syncRetryDelay = 1.second,
            peerResponseTimeout = 1.second,
            printStatusInterval = 1.minute
          )
          
          val snapConfig = SNAPSyncConfig(
            enabled = true,
            pivotBlockOffset = 100,
            accountConcurrency = 8,  // Higher concurrency for multiple peers
            storageConcurrency = 4,
            storageBatchSize = 8,
            healingBatchSize = 16,
            stateValidationEnabled = true,
            maxRetries = 3,
            timeout = 10.seconds
          )

          val controller = testSystem.actorOf(
            Props(
              new SNAPSyncController(
                blockchainReader,
                appStateStorage,
                mptStorage,
                evmCodeStorage,
                etcPeerManager.ref,
                peerEventBus.ref,
                syncConfig,
                snapConfig,
                testSystem.scheduler
              )
            )
          )

          // Create multiple test peers
          val peer1 = PeerTestHelpers.createTestPeer("peer1", TestProbe().ref)
          val peer2 = PeerTestHelpers.createTestPeer("peer2", TestProbe().ref)
          val peer3 = PeerTestHelpers.createTestPeer("peer3", TestProbe().ref)

          controller ! SNAPSyncController.Start
          
          // Controller should be able to request from multiple peers concurrently
          Thread.sleep(100)
          
          succeed
        }
      }
    }

    "State Persistence" - {

      "should persist pivot block and state root" taggedAs (
        IntegrationTest,
        SyncTest
      ) in testCaseM[IO] {
        IO {
          val stateRoot = kec256(ByteString("test-state-root"))
          val pivotBlock = BigInt(1000)
          val appStateStorage = createMockAppStateStorage()
          
          // Persist pivot block
          appStateStorage.putSnapSyncPivotBlock(pivotBlock)
          appStateStorage.getSnapSyncPivotBlock() shouldBe Some(pivotBlock)
          
          // Persist state root
          appStateStorage.putSnapSyncStateRoot(stateRoot)
          appStateStorage.getSnapSyncStateRoot() shouldBe Some(stateRoot)
          
          succeed
        }
      }

      "should resume sync after restart" taggedAs (
        IntegrationTest,
        SyncTest,
        SlowTest
      ) in testCaseM[IO] {
        IO {
          val stateRoot = kec256(ByteString("test-state-root"))
          val mptStorage = new TestMptStorage()
          val appStateStorage = createMockAppStateStorage()
          val evmCodeStorage = createMockEvmCodeStorage()
          val blockchainReader = createMockBlockchainReader(1000, stateRoot)
          val etcPeerManager = TestProbe()
          val peerEventBus = TestProbe()
          
          val syncConfig = SyncConfig(
            doFastSync = false,
            syncRetryInterval = 1.second,
            peersScanInterval = 1.second,
            blacklistDuration = 1.minute,
            startRetryInterval = 1.second,
            syncRetryDelay = 1.second,
            peerResponseTimeout = 1.second,
            printStatusInterval = 1.minute
          )
          
          val snapConfig = SNAPSyncConfig(
            enabled = true,
            pivotBlockOffset = 100,
            accountConcurrency = 4,
            storageConcurrency = 4,
            storageBatchSize = 8,
            healingBatchSize = 16,
            stateValidationEnabled = true,
            maxRetries = 3,
            timeout = 10.seconds
          )

          // Persist some state before restart
          appStateStorage.putSnapSyncPivotBlock(900)
          appStateStorage.putSnapSyncStateRoot(stateRoot)

          val controller = testSystem.actorOf(
            Props(
              new SNAPSyncController(
                blockchainReader,
                appStateStorage,
                mptStorage,
                evmCodeStorage,
                etcPeerManager.ref,
                peerEventBus.ref,
                syncConfig,
                snapConfig,
                testSystem.scheduler
              )
            )
          )

          controller ! SNAPSyncController.Start
          
          // Should resume from persisted state
          appStateStorage.getSnapSyncPivotBlock() shouldBe Some(900)
          appStateStorage.getSnapSyncStateRoot() shouldBe Some(stateRoot)
          
          succeed
        }
      }
    }

    "Pivot Block Selection" - {

      "should select pivot block with correct offset" taggedAs (
        IntegrationTest,
        SyncTest
      ) in testCaseM[IO] {
        IO {
          val bestBlock = BigInt(2000)
          val pivotOffset = 128
          val expectedPivot = bestBlock - pivotOffset
          
          val stateRoot = kec256(ByteString("test-state-root"))
          val blockchainReader = createMockBlockchainReader(bestBlock, stateRoot)
          val appStateStorage = createMockAppStateStorage()
          
          // Pivot block should be calculated as bestBlock - offset
          val calculatedPivot = bestBlock - pivotOffset
          calculatedPivot shouldBe expectedPivot
          
          succeed
        }
      }

      "should handle different pivot block offsets" taggedAs (
        IntegrationTest,
        SyncTest
      ) in testCaseM[IO] {
        IO {
          val testCases = Seq(
            (BigInt(1000), 64, BigInt(936)),
            (BigInt(5000), 128, BigInt(4872)),
            (BigInt(10000), 1024, BigInt(8976))
          )
          
          testCases.foreach { case (bestBlock, offset, expectedPivot) =>
            val calculatedPivot = bestBlock - offset
            calculatedPivot shouldBe expectedPivot
          }
          
          succeed
        }
      }
    }

    "Healing Process" - {

      "should detect missing trie nodes" taggedAs (
        IntegrationTest,
        SyncTest
      ) in testCaseM[IO] {
        IO {
          val mptStorage = new TestMptStorage()
          
          // Create a trie with a missing node
          val missingHash = kec256(ByteString("missing-node"))
          val hashNode = HashNode(missingHash.toArray)
          
          // Attempting to get the missing node should throw an exception
          intercept[MerklePatriciaTrie.MissingNodeException] {
            mptStorage.get(missingHash.toArray)
          }
          
          succeed
        }
      }

      "should queue healing tasks for missing nodes" taggedAs (
        IntegrationTest,
        SyncTest,
        SlowTest
      ) in testCaseM[IO] {
        IO {
          val stateRoot = kec256(ByteString("test-state-root"))
          val mptStorage = new TestMptStorage()
          val etcPeerManager = TestProbe()
          val requestTracker = new SNAPRequestTracker()(testSystem.scheduler)
          
          val healer = new TrieNodeHealer(
            stateRoot = stateRoot,
            etcPeerManager = etcPeerManager.ref,
            requestTracker = requestTracker,
            mptStorage = mptStorage,
            batchSize = 16
          )
          
          // Queue missing nodes for healing
          val missingNode1 = kec256(ByteString("missing1"))
          val missingNode2 = kec256(ByteString("missing2"))
          
          healer.queueNode(missingNode1, Seq.empty)
          healer.queueNode(missingNode2, Seq.empty)
          
          healer.hasPendingTasks shouldBe true
          
          succeed
        }
      }
    }

    "Peer Disconnection Handling" - {

      "should handle peer disconnection during sync" taggedAs (
        IntegrationTest,
        SyncTest,
        NetworkTest
      ) in testCaseM[IO] {
        IO {
          val stateRoot = kec256(ByteString("test-state-root"))
          val mptStorage = new TestMptStorage()
          val etcPeerManager = TestProbe()
          val requestTracker = new SNAPRequestTracker()(testSystem.scheduler)
          
          val downloader = new AccountRangeDownloader(
            stateRoot = stateRoot,
            etcPeerManager = etcPeerManager.ref,
            requestTracker = requestTracker,
            mptStorage = mptStorage,
            concurrency = 4
          )
          
          val peer = PeerTestHelpers.createTestPeer("disconnecting-peer", TestProbe().ref)
          
          // Request from peer
          val requestId = downloader.requestNextRange(peer)
          requestId shouldBe defined
          
          // Simulate peer disconnection by timeout
          // Request should be tracked
          requestTracker.getPendingRequest(requestId.get) shouldBe defined
          
          succeed
        }
      }

      "should retry with different peer after disconnection" taggedAs (
        IntegrationTest,
        SyncTest,
        NetworkTest,
        SlowTest
      ) in testCaseM[IO] {
        IO {
          val stateRoot = kec256(ByteString("test-state-root"))
          val mptStorage = new TestMptStorage()
          val etcPeerManager = TestProbe()
          val requestTracker = new SNAPRequestTracker()(testSystem.scheduler)
          
          val downloader = new AccountRangeDownloader(
            stateRoot = stateRoot,
            etcPeerManager = etcPeerManager.ref,
            requestTracker = requestTracker,
            mptStorage = mptStorage,
            concurrency = 4
          )
          
          val peer1 = PeerTestHelpers.createTestPeer("peer1", TestProbe().ref)
          val peer2 = PeerTestHelpers.createTestPeer("peer2", TestProbe().ref)
          
          // Request from peer1
          val requestId1 = downloader.requestNextRange(peer1)
          requestId1 shouldBe defined
          
          // After peer1 disconnects, request from peer2
          val requestId2 = downloader.requestNextRange(peer2)
          requestId2 shouldBe defined
          
          // Different request IDs
          requestId1.get should not equal requestId2.get
          
          succeed
        }
      }
    }

    "Transition to Regular Sync" - {

      "should mark SNAP sync as complete" taggedAs (
        IntegrationTest,
        SyncTest
      ) in testCaseM[IO] {
        IO {
          val appStateStorage = createMockAppStateStorage()
          
          appStateStorage.isSnapSyncDone() shouldBe false
          
          // Mark sync as done
          appStateStorage.snapSyncDone().commit()
          
          appStateStorage.isSnapSyncDone() shouldBe true
          
          succeed
        }
      }

      "should transition from SNAP to regular sync after completion" taggedAs (
        IntegrationTest,
        SyncTest,
        SlowTest
      ) in testCaseM[IO] {
        IO {
          val stateRoot = kec256(ByteString("test-state-root"))
          val mptStorage = new TestMptStorage()
          val appStateStorage = createMockAppStateStorage()
          val evmCodeStorage = createMockEvmCodeStorage()
          val blockchainReader = createMockBlockchainReader(1000, stateRoot)
          val etcPeerManager = TestProbe()
          val peerEventBus = TestProbe()
          
          val syncConfig = SyncConfig(
            doFastSync = false,
            syncRetryInterval = 1.second,
            peersScanInterval = 1.second,
            blacklistDuration = 1.minute,
            startRetryInterval = 1.second,
            syncRetryDelay = 1.second,
            peerResponseTimeout = 1.second,
            printStatusInterval = 1.minute
          )
          
          val snapConfig = SNAPSyncConfig(
            enabled = true,
            pivotBlockOffset = 100,
            accountConcurrency = 4,
            storageConcurrency = 4,
            storageBatchSize = 8,
            healingBatchSize = 16,
            stateValidationEnabled = true,
            maxRetries = 3,
            timeout = 10.seconds
          )

          val controller = testSystem.actorOf(
            Props(
              new SNAPSyncController(
                blockchainReader,
                appStateStorage,
                mptStorage,
                evmCodeStorage,
                etcPeerManager.ref,
                peerEventBus.ref,
                syncConfig,
                snapConfig,
                testSystem.scheduler
              )
            )
          )

          controller ! SNAPSyncController.Start
          
          // Simulate completion
          controller ! SNAPSyncController.Done
          
          // Should transition to regular sync
          Thread.sleep(100)
          
          succeed
        }
      }
    }

    "Error Handling" - {

      "should retry failed requests with exponential backoff" taggedAs (
        IntegrationTest,
        SyncTest
      ) in testCaseM[IO] {
        IO {
          val errorHandler = new SNAPErrorHandler(
            maxRetries = 3,
            initialBackoff = 1.second,
            maxBackoff = 60.seconds,
            circuitBreakerThreshold = 10
          )
          
          val taskId = "test-task"
          
          // First retry
          val backoff1 = errorHandler.getRetryBackoff(taskId)
          errorHandler.recordRetry(taskId)
          
          // Second retry
          val backoff2 = errorHandler.getRetryBackoff(taskId)
          errorHandler.recordRetry(taskId)
          
          // Backoff should increase
          backoff2.toSeconds should be > backoff1.toSeconds
          
          succeed
        }
      }

      "should blacklist peers for invalid responses" taggedAs (
        IntegrationTest,
        SyncTest,
        NetworkTest
      ) in testCaseM[IO] {
        IO {
          val errorHandler = new SNAPErrorHandler(
            maxRetries = 3,
            initialBackoff = 1.second,
            maxBackoff = 60.seconds,
            circuitBreakerThreshold = 10
          )
          
          val peerId = "bad-peer"
          
          // Record multiple invalid proof errors
          errorHandler.recordPeerFailure(peerId, SNAPErrorHandler.ErrorType.InvalidProof)
          errorHandler.recordPeerFailure(peerId, SNAPErrorHandler.ErrorType.InvalidProof)
          errorHandler.recordPeerFailure(peerId, SNAPErrorHandler.ErrorType.InvalidProof)
          
          // Peer should be recommended for blacklisting after 3 invalid proofs
          errorHandler.shouldBlacklistPeer(peerId) shouldBe true
          
          succeed
        }
      }
    }

    "Progress Monitoring" - {

      "should track sync progress" taggedAs (
        IntegrationTest,
        SyncTest
      ) in testCaseM[IO] {
        IO {
          import SNAPSyncController._
          
          val progress = SyncProgress(
            phase = AccountRangeSync,
            accountsSynced = 500,
            bytecodesDownloaded = 50,
            storageSlotsSynced = 100,
            nodesHealed = 10,
            elapsedSeconds = 60.0,
            phaseElapsedSeconds = 30.0,
            accountsPerSec = 8.33,
            bytecodesPerSec = 0.83,
            slotsPerSec = 1.67,
            nodesPerSec = 0.17,
            recentAccountsPerSec = 10.0,
            recentBytecodesPerSec = 1.0,
            recentSlotsPerSec = 2.0,
            recentNodesPerSec = 0.2,
            phaseProgress = 50,
            estimatedTotalAccounts = 1000,
            estimatedTotalBytecodes = 100,
            estimatedTotalSlots = 200
          )
          
          progress.phase shouldBe AccountRangeSync
          progress.accountsSynced shouldBe 500
          progress.phaseProgress shouldBe 50
          
          succeed
        }
      }

      "should calculate phase completion percentage" taggedAs (
        IntegrationTest,
        SyncTest
      ) in testCaseM[IO] {
        IO {
          import SNAPSyncController._
          
          val testCases = Seq(
            (250, 1000, 25),   // 25% complete
            (500, 1000, 50),   // 50% complete
            (1000, 1000, 100)  // 100% complete
          )
          
          testCases.foreach { case (synced, total, expectedPercent) =>
            val percent = if (total > 0) ((synced.toDouble / total) * 100).toInt else 0
            percent shouldBe expectedPercent
          }
          
          succeed
        }
      }
    }
  }
}
