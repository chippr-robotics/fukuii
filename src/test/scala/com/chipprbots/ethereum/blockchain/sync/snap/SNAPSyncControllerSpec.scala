package com.chipprbots.ethereum.blockchain.sync.snap

import org.apache.pekko.actor.{ActorSystem, Props}
import org.apache.pekko.testkit.{TestKit, TestProbe}
import org.apache.pekko.util.ByteString

import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._
import scala.collection.mutable

import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.db.storage.{AppStateStorage, EvmCodeStorage, MptStorage}
import com.chipprbots.ethereum.domain.{BlockchainReader, BlockHeader, UInt256}
import com.chipprbots.ethereum.mpt._
import com.chipprbots.ethereum.utils.Config.SyncConfig
import com.chipprbots.ethereum.testing.Tags._

class SNAPSyncControllerSpec
    extends TestKit(ActorSystem("SNAPSyncControllerSpec"))
    with AnyFlatSpecLike
    with Matchers
    with BeforeAndAfterAll {

  override def afterAll(): Unit = {
    TestKit.shutdownActorSystem(system)
  }

  "SNAPSyncController" should "initialize in Idle state" taggedAs UnitTest in {
    val mockBlockchainReader = new MockBlockchainReader()
    val mockAppStateStorage = new MockAppStateStorage()
    val mockMptStorage = new TestMptStorage()
    val mockEvmCodeStorage = new MockEvmCodeStorage()
    val etcPeerManager = TestProbe()
    val peerEventBus = TestProbe()
    
    val syncConfig = SyncConfig(
      printStatusInterval = 30.seconds,
      persistStateSnapshotInterval = 5.minutes,
      targetBlockOffset = 500,
      branchResolutionBatchSize = 20,
      blocksBatchSize = 50,
      blockHeadersPerRequest = 200,
      blockBodiesPerRequest = 50,
      receiptsPerRequest = 60,
      nodesPerRequest = 200,
      minPeersToChooseTargetBlock = 2,
      peersScanInterval = 500.millis,
      peerResponseTimeout = 3.seconds,
      syncRetryInterval = 5.seconds,
      blacklistDuration = 5.minutes,
      startRetryInterval = 500.millis,
      syncRetryDelay = 5.seconds,
      checkForNewBlockInterval = 10.seconds,
      fastSyncBlockValidationX = 100,
      fastSyncBlockValidationN = 2048,
      fastSyncBlockValidationK = 50,
      maxConcurrentRequests = 10,
      maxQueuedBlockNumbersPerPeer = 10,
      maxQueuedBlockNumbersTotal = 1000,
      maxNewBlockHashAge = 20,
      maxNewBlockAge = 20,
      redownloadMissingStateNodes = true,
      downloadReceiptsWithBodies = true,
      doFastSync = true,
      fastSyncPivotBlockOffset = 0
    )
    
    val snapSyncConfig = SNAPSyncConfig(
      enabled = true,
      pivotBlockOffset = 1024,
      accountConcurrency = 16,
      storageConcurrency = 8,
      storageBatchSize = 8,
      healingBatchSize = 16,
      stateValidationEnabled = true,
      maxRetries = 3,
      timeout = 30.seconds
    )

    val controller = system.actorOf(
      SNAPSyncController.props(
        mockBlockchainReader,
        mockAppStateStorage,
        mockMptStorage,
        mockEvmCodeStorage,
        etcPeerManager.ref,
        peerEventBus.ref,
        syncConfig,
        snapSyncConfig,
        system.scheduler
      )(system.dispatcher)
    )

    // Initially should be in Idle state
    // Can't directly check internal state, but we can verify behavior
    controller ! SNAPSyncController.GetProgress
    expectNoMessage(100.millis) // No response because not started
  }

  it should "start account range sync when started" taggedAs UnitTest in {
    val mockBlockchainReader = new MockBlockchainReader()
    val mockAppStateStorage = new MockAppStateStorage()
    val mockMptStorage = new TestMptStorage()
    val mockEvmCodeStorage = new MockEvmCodeStorage()
    val etcPeerManager = TestProbe()
    val peerEventBus = TestProbe()
    
    // Set up mock to return a block header
    val stateRoot = kec256(ByteString("test-state-root"))
    mockBlockchainReader.setBlockHeader(
      1000,
      BlockHeader(
        parentHash = ByteString.empty,
        ommersHash = ByteString.empty,
        beneficiary = ByteString.empty,
        stateRoot = stateRoot,
        transactionsRoot = ByteString.empty,
        receiptsRoot = ByteString.empty,
        logsBloom = ByteString.empty,
        difficulty = UInt256.Zero,
        number = 1000,
        gasLimit = 0,
        gasUsed = 0,
        unixTimestamp = 0,
        extraData = ByteString.empty,
        mixHash = ByteString.empty,
        nonce = ByteString.empty
      )
    )
    
    mockAppStateStorage.putBestBlockNumber(2024)
    
    val syncConfig = SyncConfig(
      printStatusInterval = 30.seconds,
      persistStateSnapshotInterval = 5.minutes,
      targetBlockOffset = 500,
      branchResolutionBatchSize = 20,
      blocksBatchSize = 50,
      blockHeadersPerRequest = 200,
      blockBodiesPerRequest = 50,
      receiptsPerRequest = 60,
      nodesPerRequest = 200,
      minPeersToChooseTargetBlock = 2,
      peersScanInterval = 500.millis,
      peerResponseTimeout = 3.seconds,
      syncRetryInterval = 5.seconds,
      blacklistDuration = 5.minutes,
      startRetryInterval = 500.millis,
      syncRetryDelay = 5.seconds,
      checkForNewBlockInterval = 10.seconds,
      fastSyncBlockValidationX = 100,
      fastSyncBlockValidationN = 2048,
      fastSyncBlockValidationK = 50,
      maxConcurrentRequests = 10,
      maxQueuedBlockNumbersPerPeer = 10,
      maxQueuedBlockNumbersTotal = 1000,
      maxNewBlockHashAge = 20,
      maxNewBlockAge = 20,
      redownloadMissingStateNodes = true,
      downloadReceiptsWithBodies = true,
      doFastSync = true,
      fastSyncPivotBlockOffset = 0
    )
    
    val snapSyncConfig = SNAPSyncConfig(
      enabled = true,
      pivotBlockOffset = 1024,
      accountConcurrency = 16,
      storageConcurrency = 8,
      storageBatchSize = 8,
      healingBatchSize = 16,
      stateValidationEnabled = false, // Disable for simple test
      maxRetries = 3,
      timeout = 30.seconds
    )

    val controller = system.actorOf(
      SNAPSyncController.props(
        mockBlockchainReader,
        mockAppStateStorage,
        mockMptStorage,
        mockEvmCodeStorage,
        etcPeerManager.ref,
        peerEventBus.ref,
        syncConfig,
        snapSyncConfig,
        system.scheduler
      )(system.dispatcher)
    )

    controller ! SNAPSyncController.Start
    
    // Give it time to process
    Thread.sleep(500)
    
    // Should have stored pivot block and state root
    mockAppStateStorage.getSnapSyncPivotBlock() shouldBe defined
    mockAppStateStorage.getSnapSyncStateRoot() shouldBe defined
  }

  it should "handle GetProgress message" taggedAs UnitTest in {
    val mockBlockchainReader = new MockBlockchainReader()
    val mockAppStateStorage = new MockAppStateStorage()
    val mockMptStorage = new TestMptStorage()
    val mockEvmCodeStorage = new MockEvmCodeStorage()
    val etcPeerManager = TestProbe()
    val peerEventBus = TestProbe()
    
    // Set up mock
    val stateRoot = kec256(ByteString("test-state-root"))
    mockBlockchainReader.setBlockHeader(
      1000,
      BlockHeader(
        parentHash = ByteString.empty,
        ommersHash = ByteString.empty,
        beneficiary = ByteString.empty,
        stateRoot = stateRoot,
        transactionsRoot = ByteString.empty,
        receiptsRoot = ByteString.empty,
        logsBloom = ByteString.empty,
        difficulty = UInt256.Zero,
        number = 1000,
        gasLimit = 0,
        gasUsed = 0,
        unixTimestamp = 0,
        extraData = ByteString.empty,
        mixHash = ByteString.empty,
        nonce = ByteString.empty
      )
    )
    
    mockAppStateStorage.putBestBlockNumber(2024)
    
    val syncConfig = SyncConfig(
      printStatusInterval = 30.seconds,
      persistStateSnapshotInterval = 5.minutes,
      targetBlockOffset = 500,
      branchResolutionBatchSize = 20,
      blocksBatchSize = 50,
      blockHeadersPerRequest = 200,
      blockBodiesPerRequest = 50,
      receiptsPerRequest = 60,
      nodesPerRequest = 200,
      minPeersToChooseTargetBlock = 2,
      peersScanInterval = 500.millis,
      peerResponseTimeout = 3.seconds,
      syncRetryInterval = 5.seconds,
      blacklistDuration = 5.minutes,
      startRetryInterval = 500.millis,
      syncRetryDelay = 5.seconds,
      checkForNewBlockInterval = 10.seconds,
      fastSyncBlockValidationX = 100,
      fastSyncBlockValidationN = 2048,
      fastSyncBlockValidationK = 50,
      maxConcurrentRequests = 10,
      maxQueuedBlockNumbersPerPeer = 10,
      maxQueuedBlockNumbersTotal = 1000,
      maxNewBlockHashAge = 20,
      maxNewBlockAge = 20,
      redownloadMissingStateNodes = true,
      downloadReceiptsWithBodies = true,
      doFastSync = true,
      fastSyncPivotBlockOffset = 0
    )
    
    val snapSyncConfig = SNAPSyncConfig(
      enabled = true,
      pivotBlockOffset = 1024,
      accountConcurrency = 16,
      storageConcurrency = 8,
      storageBatchSize = 8,
      healingBatchSize = 16,
      stateValidationEnabled = false,
      maxRetries = 3,
      timeout = 30.seconds
    )

    val controller = system.actorOf(
      SNAPSyncController.props(
        mockBlockchainReader,
        mockAppStateStorage,
        mockMptStorage,
        mockEvmCodeStorage,
        etcPeerManager.ref,
        peerEventBus.ref,
        syncConfig,
        snapSyncConfig,
        system.scheduler
      )(system.dispatcher)
    )

    controller ! SNAPSyncController.Start
    Thread.sleep(200)
    
    val sender = TestProbe()
    sender.send(controller, SNAPSyncController.GetProgress)
    sender.expectMsgType[SyncProgress](1.second)
  }

  /** Mock BlockchainReader for testing */
  class MockBlockchainReader extends BlockchainReader {
    private var headers = mutable.Map[BigInt, BlockHeader]()
    
    def setBlockHeader(number: BigInt, header: BlockHeader): Unit = {
      headers(number) = header
    }
    
    override def getBlockHeaderByNumber(number: BigInt): Option[BlockHeader] = {
      headers.get(number)
    }
    
    // Stub other methods - not used in these tests
    override def getBestBlock(): Option[com.chipprbots.ethereum.domain.Block] = None
    override def getBestBlockNumber(): BigInt = 0
    override def getBlockByNumber(number: BigInt): Option[com.chipprbots.ethereum.domain.Block] = None
    override def getBlockHeaderByHash(hash: ByteString): Option[BlockHeader] = None
    override def getBlockBodyByHash(hash: ByteString): Option[com.chipprbots.ethereum.domain.BlockBody] = None
    override def getTotalDifficultyByHash(hash: ByteString): Option[BigInt] = None
    override def getAccount(address: com.chipprbots.ethereum.domain.Address, blockNumber: BigInt): Option[com.chipprbots.ethereum.domain.Account] = None
    override def getAccountStorageAt(address: com.chipprbots.ethereum.domain.Address, position: BigInt, blockNumber: BigInt): ByteString = ByteString.empty
    override def getEvmCodeByHash(hash: ByteString): Option[ByteString] = None
    override def getTransactionLocation(txHash: ByteString): Option[com.chipprbots.ethereum.domain.TransactionLocation] = None
  }
  
  /** Mock AppStateStorage for testing */
  class MockAppStateStorage extends AppStateStorage {
    private var bestBlockNumber: BigInt = 0
    private var snapSyncPivotBlock: Option[BigInt] = None
    private var snapSyncStateRoot: Option[ByteString] = None
    private var snapSyncDoneFlag: Boolean = false
    
    override def getBestBlockNumber(): BigInt = bestBlockNumber
    override def putBestBlockNumber(number: BigInt): AppStateStorage = {
      bestBlockNumber = number
      this
    }
    
    override def getSnapSyncPivotBlock(): Option[BigInt] = snapSyncPivotBlock
    override def putSnapSyncPivotBlock(block: BigInt): AppStateStorage = {
      snapSyncPivotBlock = Some(block)
      this
    }
    
    override def getSnapSyncStateRoot(): Option[ByteString] = snapSyncStateRoot
    override def putSnapSyncStateRoot(root: ByteString): AppStateStorage = {
      snapSyncStateRoot = Some(root)
      this
    }
    
    override def isSnapSyncDone(): Boolean = snapSyncDoneFlag
    override def snapSyncDone(): com.chipprbots.ethereum.db.datatype.DataSourceBatchUpdate = {
      snapSyncDoneFlag = true
      new com.chipprbots.ethereum.db.datatype.DataSourceBatchUpdate {
        override def commit(): Unit = ()
      }
    }
    
    override def commit(): Unit = ()
    
    // Stub other methods - not used in these tests
    override def getFastSyncDone(): Option[Boolean] = None
    override def fastSyncDone(): com.chipprbots.ethereum.db.datatype.DataSourceBatchUpdate = ???
    override def getEstimatedHighestBlock(): BigInt = 0
    override def putEstimatedHighestBlock(n: BigInt): AppStateStorage = this
    override def getSyncStartingBlock(): BigInt = 0
    override def putSyncStartingBlock(n: BigInt): AppStateStorage = this
    override def getStateRoot(): Option[ByteString] = None
    override def putStateRoot(root: ByteString): AppStateStorage = this
    override def getLastPrunedBlock(): Option[BigInt] = None
    override def putLastPrunedBlock(n: BigInt): AppStateStorage = this
  }
  
  /** Mock EvmCodeStorage for testing */
  class MockEvmCodeStorage extends EvmCodeStorage {
    private val codes = mutable.Map[ByteString, ByteString]()
    
    override def get(hash: ByteString): Option[ByteString] = codes.get(hash)
    override def put(hash: ByteString, code: ByteString): EvmCodeStorage = {
      codes(hash) = code
      this
    }
    override def remove(hash: ByteString): EvmCodeStorage = {
      codes.remove(hash)
      this
    }
  }
  
  /** Test MPT Storage */
  class TestMptStorage extends MptStorage {
    private val nodes = mutable.Map[ByteString, MptNode]()
    
    override def get(key: Array[Byte]): MptNode = {
      val keyStr = ByteString(key)
      nodes.get(keyStr)
        .getOrElse {
          throw new MerklePatriciaTrie.MissingNodeException(keyStr)
        }
    }
    
    def putNode(node: MptNode): Unit = {
      val hash = ByteString(node.hash)
      nodes(hash) = node
    }
    
    override def updateNodesInStorage(
        newRoot: Option[MptNode],
        toRemove: Seq[MptNode]
    ): Option[MptNode] = {
      newRoot.foreach { root =>
        storeNodeRecursively(root)
      }
      newRoot
    }
    
    private def storeNodeRecursively(node: MptNode): Unit = {
      node match {
        case leaf: LeafNode =>
          putNode(leaf)
        case ext: ExtensionNode =>
          putNode(ext)
          storeNodeRecursively(ext.next)
        case branch: BranchNode =>
          putNode(branch)
          branch.children.foreach(storeNodeRecursively)
        case hash: HashNode =>
          putNode(hash)
        case NullNode =>
          // Nothing to store
      }
    }
    
    override def persist(): Unit = {
      // No-op for in-memory storage
    }
  }
}
