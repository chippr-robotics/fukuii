package com.chipprbots.ethereum.blockchain.sync

import org.apache.pekko.util.ByteString

import org.scalactic.anyvals.PosInt
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.SuperSlow
import com.chipprbots.ethereum.testing.Tags._
import com.chipprbots.ethereum.blockchain.sync.StateSyncUtils.MptNodeData
import com.chipprbots.ethereum.blockchain.sync.StateSyncUtils.TrieProvider
import com.chipprbots.ethereum.blockchain.sync.StateSyncUtils.checkAllDataExists
import com.chipprbots.ethereum.blockchain.sync.fast.SyncStateScheduler
import com.chipprbots.ethereum.blockchain.sync.fast.SyncStateScheduler.AlreadyProcessedItem
import com.chipprbots.ethereum.blockchain.sync.fast.SyncStateScheduler.CannotDecodeMptNode
import com.chipprbots.ethereum.blockchain.sync.fast.SyncStateScheduler.NotRequestedItem
import com.chipprbots.ethereum.blockchain.sync.fast.SyncStateScheduler.SchedulerState
import com.chipprbots.ethereum.blockchain.sync.fast.SyncStateScheduler.SyncResponse
import com.chipprbots.ethereum.db.components.EphemDataSourceComponent
import com.chipprbots.ethereum.db.components.Storages
import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.BlockchainImpl
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.domain.BlockchainWriter
import com.chipprbots.ethereum.vm.Generators.genMultipleNodeData
import com.chipprbots.ethereum.blockchain.sync.fast.SyncStateScheduler.CriticalError
import com.chipprbots.ethereum.blockchain.sync.fast.SyncStateScheduler.ProcessingStatistics
import com.chipprbots.ethereum.blockchain.sync.fast.SyncStateScheduler.ResponseProcessingError
import com.chipprbots.ethereum.blockchain.sync.fast.SyncStateScheduler.ResponseProcessingError
import com.chipprbots.ethereum.blockchain.sync.fast.SyncStateScheduler.ResponseProcessingError
import com.chipprbots.ethereum.blockchain.sync.fast.SyncStateScheduler.ResponseProcessingError
import org.scalacheck.Gen

class SyncStateSchedulerSpec
    extends AnyFlatSpec
    with Matchers
    with EitherValues
    with ScalaCheckPropertyChecks
    with SuperSlow {
  "SyncStateScheduler" should "sync with mptTrie with one account (1 leaf node)" taggedAs(UnitTest, SyncTest) in new TestSetup {
    val prov = getTrieProvider
    val worldHash: ByteString = prov.buildWorld(Seq(MptNodeData(Address(1), None, Seq(), 20)))
    val (syncStateScheduler, _, _, _, schedulerDb) = buildScheduler()
    val initialState: SchedulerState = syncStateScheduler.initState(worldHash).get
    val (missingNodes, newState) = syncStateScheduler.getMissingNodes(initialState, 1)
    val responses: List[SyncResponse] = prov.getNodes(missingNodes)
    val result: Either[CriticalError, (SchedulerState, ProcessingStatistics)] =
      syncStateScheduler.processResponses(newState, responses)
    val (newRequests, state) = syncStateScheduler.getMissingNodes(result.value._1, 1)
    syncStateScheduler.persistBatch(state, 1)

    assert(missingNodes.size == 1)
    assert(responses.size == 1)
    assert(result.isRight)
    assert(newRequests.isEmpty)
    assert(state.numberOfPendingRequests == 0)
    assert(schedulerDb.storages.nodeStorage.get(missingNodes.head).isDefined)
  }

  it should "sync with mptTrie with one account with code and storage" taggedAs(UnitTest, SyncTest) in new TestSetup {
    val prov = getTrieProvider
    val worldHash: ByteString = prov.buildWorld(
      Seq(MptNodeData(Address(1), Some(ByteString(1, 2, 3)), Seq((1, 1)), 20))
    )
    val (syncStateScheduler, _, _, _, schedulerDb) = buildScheduler()
    val initState: SchedulerState = syncStateScheduler.initState(worldHash).get
    val state1: SchedulerState = exchangeSingleNode(initState, syncStateScheduler, prov).value
    val state2: SchedulerState = exchangeSingleNode(state1, syncStateScheduler, prov).value
    val state3: SchedulerState = exchangeSingleNode(state2, syncStateScheduler, prov).value
    syncStateScheduler.persistBatch(state3, 1)

    assert(state1.numberOfPendingRequests > 0)
    assert(state2.numberOfPendingRequests > 0)
    // only after processing third result request is finalized as code and storage of account has been retrieved
    assert(state3.numberOfPendingRequests == 0)
    // 1 leaf node + 1 code + 1 storage
    assert(schedulerDb.dataSource.storage.size == 3)
  }

  it should "not request already known lead nodes" taggedAs(UnitTest, SyncTest) in new TestSetup {
    val prov = getTrieProvider
    val worldHash: ByteString = prov.buildWorld(
      Seq(
        MptNodeData(Address(1), Some(ByteString(1, 2, 3)), Seq((1, 1)), 20),
        MptNodeData(Address(2), Some(ByteString(1, 2, 3)), Seq((1, 1)), 20)
      )
    )
    val (syncStateScheduler, _, _, _, _) = buildScheduler()
    val initState: SchedulerState = syncStateScheduler.initState(worldHash).get
    val stateAfterExchange: SchedulerState = exchangeAllNodes(initState, syncStateScheduler, prov)
    assert(stateAfterExchange.numberOfPendingRequests == 0)
    // 1 branch - 2 Leaf - 1 code - 1 storage (storage and code are shared between 2 leafs)
    assert(stateAfterExchange.memBatch.size == 5)
    val stateAfterPersist: SchedulerState = syncStateScheduler.persistBatch(stateAfterExchange, 1)
    assert(stateAfterPersist.memBatch.isEmpty)

    val worldHash1: ByteString = prov.buildWorld(
      Seq(MptNodeData(Address(3), Some(ByteString(1, 2, 3)), Seq((1, 1)), 20)),
      Some(worldHash)
    )

    val initState1: SchedulerState = syncStateScheduler.initState(worldHash1).get

    // received root branch node with 3 leaf nodes
    val state1a: SchedulerState = exchangeSingleNode(initState1, syncStateScheduler, prov).value

    // branch got 3 leaf nodes, but we already known 2 of them, so there are pending requests only for: 1 branch + 1 unknown leaf
    assert(state1a.numberOfPendingRequests == 2)
  }

  it should "sync with mptTrie with 2 accounts with different code and storage" taggedAs(UnitTest, SyncTest) in new TestSetup {
    val prov = getTrieProvider
    // root is branch with 2 leaf nodes
    val worldHash: ByteString = prov.buildWorld(
      Seq(
        MptNodeData(Address(1), Some(ByteString(1, 2, 3)), Seq((1, 1)), 20),
        MptNodeData(Address(2), Some(ByteString(1, 2, 3, 4)), Seq((2, 2)), 20)
      )
    )
    val (syncStateScheduler, _, _, _, schedulerDb) = buildScheduler()
    val initState: SchedulerState = syncStateScheduler.initState(worldHash).get
    assert(schedulerDb.dataSource.storage.isEmpty)
    val state1: SchedulerState = exchangeSingleNode(initState, syncStateScheduler, prov).value
    val state2: SchedulerState = exchangeSingleNode(state1, syncStateScheduler, prov).value
    val state3: SchedulerState = exchangeSingleNode(state2, syncStateScheduler, prov).value
    val state4: SchedulerState = exchangeSingleNode(state3, syncStateScheduler, prov).value
    val state5: SchedulerState = syncStateScheduler.persistBatch(state4, 1)
    // finalized leaf node i.e state node + storage node + code
    assert(schedulerDb.dataSource.storage.size == 3)
    val state6: SchedulerState = exchangeSingleNode(state5, syncStateScheduler, prov).value
    val state7: SchedulerState = exchangeSingleNode(state6, syncStateScheduler, prov).value
    val state8: SchedulerState = exchangeSingleNode(state7, syncStateScheduler, prov).value
    val state9: SchedulerState = syncStateScheduler.persistBatch(state8, 1)

    // 1 non finalized request for branch node + 2 non finalized request for leaf nodes
    assert(state1.numberOfPendingRequests == 3)

    // 1 non finalized request for branch node + 2 non finalized requests for leaf nodes + 2 non finalized requests for code and
    // storage
    assert(state2.numberOfPendingRequests == 5)

    // 1 non finalized request for branch node + 1 non finalized request for leaf node
    assert(state5.numberOfPendingRequests == 2)

    // 1 non finalized request for branch node + 1 non finalized request for leaf node + 2 non finalized request for code and storage
    assert(state6.numberOfPendingRequests == 4)

    // received code and storage finalized remaining leaf node, and branch node
    assert(state8.numberOfPendingRequests == 0)
    // 1 branch node + 2 leaf nodes + 4 code and storage data
    assert(state9.numberOfPendingRequests == 0)
    assert(schedulerDb.dataSource.storage.size == 7)
  }

  it should "should not request already known code or storage" taggedAs(UnitTest, SyncTest) in new TestSetup {
    val prov = getTrieProvider
    // root is branch with 2 leaf nodes, two different account with same code and same storage
    val worldHash: ByteString = prov.buildWorld(
      Seq(
        MptNodeData(Address(1), Some(ByteString(1, 2, 3)), Seq((1, 1)), 20),
        MptNodeData(Address(2), Some(ByteString(1, 2, 3)), Seq((1, 1)), 20)
      )
    )
    val (syncStateScheduler, _, _, _, schedulerDb) = buildScheduler()
    val initState: SchedulerState = syncStateScheduler.initState(worldHash).get
    val state1: SchedulerState = exchangeSingleNode(initState, syncStateScheduler, prov).value
    val (allMissingNodes1, state2) = syncStateScheduler.getAllMissingNodes(state1)
    val allMissingNodes1Response: List[SyncResponse] = prov.getNodes(allMissingNodes1)
    val state3: SchedulerState = syncStateScheduler.processResponses(state2, allMissingNodes1Response).value._1
    val (allMissingNodes2, state4) = syncStateScheduler.getAllMissingNodes(state3)
    val allMissingNodes2Response: List[SyncResponse] = prov.getNodes(allMissingNodes2)
    val state5: SchedulerState = syncStateScheduler.processResponses(state4, allMissingNodes2Response).value._1
    val remaingNodes = state5.numberOfPendingRequests
    syncStateScheduler.persistBatch(state5, 1)

    // 1 non finalized request for branch node + 2 non finalized request for leaf nodes
    assert(state1.numberOfPendingRequests == 3)
    assert(allMissingNodes1.size == 2)

    assert(allMissingNodes2.size == 2)

    assert(remaingNodes == 0)
    // 1 branch node + 2 leaf node + 1 code + 1 storage (code and storage are shared by 2 leaf nodes)
    assert(schedulerDb.dataSource.storage.size == 5)
  }

  it should "should return error when processing unrequested response" taggedAs(UnitTest, SyncTest) in new TestSetup {
    val prov = getTrieProvider
    // root is branch with 2 leaf nodes, two different account with same code and same storage
    val worldHash: ByteString = prov.buildWorld(
      Seq(
        MptNodeData(Address(1), Some(ByteString(1, 2, 3)), Seq((1, 1)), 20),
        MptNodeData(Address(2), Some(ByteString(1, 2, 3)), Seq((1, 1)), 20)
      )
    )
    val (syncStateScheduler, _, _, _, _) = buildScheduler()
    val initState: SchedulerState = syncStateScheduler.initState(worldHash).get
    val (_, state1) = syncStateScheduler.getMissingNodes(initState, 1)
    val result1: Either[ResponseProcessingError, SchedulerState] =
      syncStateScheduler.processResponse(state1, SyncResponse(ByteString(1), ByteString(2)))
    assert(result1.isLeft)
    assert(result1.left.value == NotRequestedItem)
  }

  it should "should return error when processing already processed response" taggedAs(UnitTest, SyncTest) in new TestSetup {
    val prov = getTrieProvider
    // root is branch with 2 leaf nodes, two different account with same code and same storage
    val worldHash: ByteString = prov.buildWorld(
      Seq(
        MptNodeData(Address(1), Some(ByteString(1, 2, 3)), Seq((1, 1)), 20),
        MptNodeData(Address(2), Some(ByteString(1, 2, 3)), Seq((1, 1)), 20)
      )
    )
    val (syncStateScheduler, _, _, _, _) = buildScheduler()
    val initState: SchedulerState = syncStateScheduler.initState(worldHash).get
    val (firstMissing, state1) = syncStateScheduler.getMissingNodes(initState, 1)
    val firstMissingResponse: List[SyncResponse] = prov.getNodes(firstMissing)
    val result1: Either[ResponseProcessingError, SchedulerState] =
      syncStateScheduler.processResponse(state1, firstMissingResponse.head)
    val stateAfterReceived = result1.value
    val result2: Either[ResponseProcessingError, SchedulerState] =
      syncStateScheduler.processResponse(stateAfterReceived, firstMissingResponse.head)

    assert(result1.isRight)
    assert(result2.isLeft)
    assert(result2.left.value == AlreadyProcessedItem)
  }

  it should "should return critical error when node is malformed" taggedAs(UnitTest, SyncTest) in new TestSetup {
    val prov = getTrieProvider
    // root is branch with 2 leaf nodes, two different account with same code and same storage
    val worldHash: ByteString = prov.buildWorld(
      Seq(
        MptNodeData(Address(1), Some(ByteString(1, 2, 3)), Seq((1, 1)), 20),
        MptNodeData(Address(2), Some(ByteString(1, 2, 3)), Seq((1, 1)), 20)
      )
    )
    val (syncStateScheduler, _, _, _, _) = buildScheduler()
    val initState: SchedulerState = syncStateScheduler.initState(worldHash).get
    val (firstMissing, state1) = syncStateScheduler.getMissingNodes(initState, 1)
    val firstMissingResponse: List[SyncResponse] = prov.getNodes(firstMissing)
    val result1: Either[ResponseProcessingError, SchedulerState] =
      syncStateScheduler.processResponse(state1, firstMissingResponse.head.copy(data = ByteString(1, 2, 3)))
    assert(result1.isLeft)
    assert(result1.left.value == CannotDecodeMptNode)
  }

  implicit override val generatorDrivenConfig: PropertyCheckConfiguration =
    PropertyCheckConfiguration(minSuccessful = PosInt(3))

  // Long running test generating random mpt tries and checking that scheduler is able to correctly
  // traverse them
  it should "sync whole trie when receiving all nodes from remote side" taggedAs(UnitTest, SyncTest) in new TestSetup {
    val nodeDataGen: Gen[List[MptNodeData]] = genMultipleNodeData(
      superSlow(2000).getOrElse(20) // use smaller test set for CI as it is super slow there
    )
    forAll(nodeDataGen) { nodeData =>
      val prov = getTrieProvider
      val worldHash = prov.buildWorld(nodeData)
      val (scheduler, schedulerBlockchain, schedulerBlockchainWriter, schedulerBlockchainReader, allStorages) =
        buildScheduler()
      val header = Fixtures.Blocks.ValidBlock.header.copy(stateRoot = worldHash, number = 1)
      schedulerBlockchainWriter.storeBlockHeader(header).commit()
      schedulerBlockchainWriter.saveBestKnownBlocks(header.hash, 1)
      var state = scheduler.initState(worldHash).get
      while (state.activeRequest.nonEmpty) {
        val (allMissingNodes1, state2) = scheduler.getAllMissingNodes(state)
        val allMissingNodes1Response = prov.getNodes(allMissingNodes1)
        val state3 = scheduler.processResponses(state2, allMissingNodes1Response).value._1
        state = state3
      }
      assert(state.memBatch.nonEmpty)
      val finalState = scheduler.persistBatch(state, 1)
      assert(finalState.memBatch.isEmpty)
      assert(finalState.activeRequest.isEmpty)
      assert(finalState.queue.isEmpty)
      assert(
        checkAllDataExists(
          nodeData,
          schedulerBlockchain,
          schedulerBlockchainReader,
          allStorages.storages.evmCodeStorage,
          1
        )
      )
    }
  }

  trait TestSetup extends EphemBlockchainTestSetup {
    def getTrieProvider: TrieProvider = {
      val freshStorage = getNewStorages
      val freshBlockchainReader = BlockchainReader(freshStorage.storages)
      val freshBlockchain = BlockchainImpl(freshStorage.storages, freshBlockchainReader)
      new TrieProvider(freshBlockchain, freshBlockchainReader, freshStorage.storages.evmCodeStorage, blockchainConfig)
    }
    val bloomFilterSize = 1000

    def exchangeAllNodes(
        initState: SchedulerState,
        scheduler: SyncStateScheduler,
        provider: TrieProvider
    ): SchedulerState = {
      var state = initState
      while (state.activeRequest.nonEmpty) {
        val (allMissingNodes1, state2) = scheduler.getAllMissingNodes(state)
        val allMissingNodes1Response = provider.getNodes(allMissingNodes1)
        val state3 = scheduler.processResponses(state2, allMissingNodes1Response).value._1
        state = state3
      }
      state
    }

    def buildScheduler(): (
        SyncStateScheduler,
        BlockchainImpl,
        BlockchainWriter,
        BlockchainReader,
        EphemDataSourceComponent with LocalPruningConfigBuilder with Storages.DefaultStorages
    ) = {
      val freshStorage = getNewStorages
      val freshBlockchainReader = BlockchainReader(freshStorage.storages)
      val freshBlockchain = BlockchainImpl(freshStorage.storages, freshBlockchainReader)
      val freshBlockchainWriter = BlockchainWriter(freshStorage.storages)
      (
        SyncStateScheduler(
          freshBlockchainReader,
          freshStorage.storages.evmCodeStorage,
          freshStorage.storages.stateStorage,
          freshStorage.storages.nodeStorage,
          bloomFilterSize
        ),
        freshBlockchain,
        freshBlockchainWriter,
        freshBlockchainReader,
        freshStorage
      )
    }

    def exchangeSingleNode(
        initState: SchedulerState,
        scheduler: SyncStateScheduler,
        provider: TrieProvider
    ): Either[SyncStateScheduler.ResponseProcessingError, SchedulerState] = {
      val (missingNodes, newState) = scheduler.getMissingNodes(initState, 1)
      val providedResponse = provider.getNodes(missingNodes)
      scheduler.processResponses(newState, providedResponse).map(_._1)
    }

  }

}
