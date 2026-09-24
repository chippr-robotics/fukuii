// §8a-retro batch 5: DEFERRED — TestActorRef used for .children inspection (Classic-only API);
// migrate when SyncController test no longer needs child inspection (Wave 3 network sprint)
package com.chipprbots.ethereum.blockchain.sync

import org.apache.pekko.actor.ActorRef
import org.apache.pekko.actor.ActorSystem

import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.testkit.ExplicitlyTriggeredScheduler
import org.apache.pekko.testkit.TestActor.AutoPilot
import org.apache.pekko.testkit.TestActorRef
import org.apache.pekko.testkit.TestProbe
import org.apache.pekko.util.ByteString

import scala.concurrent.Await
import scala.concurrent.duration.*

import com.typesafe.config.ConfigFactory
import org.bouncycastle.util.encoders.Hex
import org.scalamock.scalatest.MockFactory
import org.scalatest.BeforeAndAfter
import org.scalatest.concurrent.Eventually
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.LongPatience
import com.chipprbots.ethereum.Mocks
import com.chipprbots.ethereum.blockchain.sync.fast.FastSync
import com.chipprbots.ethereum.blockchain.sync.fast.FastSync.SyncState
import com.chipprbots.ethereum.consensus.mining.GetBlockHeaderByHash
import com.chipprbots.ethereum.consensus.mining.TestMining
import com.chipprbots.ethereum.consensus.validators.BlockHeaderError
import com.chipprbots.ethereum.consensus.validators.BlockHeaderError.HeaderParentNotFoundError
import com.chipprbots.ethereum.consensus.validators.BlockHeaderError.HeaderPoWError
import com.chipprbots.ethereum.consensus.validators.BlockHeaderValid
import com.chipprbots.ethereum.consensus.validators.BlockHeaderValidator
import com.chipprbots.ethereum.consensus.validators.Validators
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.ledger.VMImpl
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.HandshakedPeers
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.SendMessageCmd
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerEvent.MessageFromPeer
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.BlockBodies
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.GetBlockBodies
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.GetBlockBodies.GetBlockBodiesEnc
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.GetBlockHeaders as ETH62GetBlockHeaders
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.GetReceipts as ETH63GetReceipts
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.NodeData as ETH63NodeData
import com.chipprbots.ethereum.rlp.RLPList
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.Config.SyncConfig

// scalastyle:off file.size.limit
class SyncControllerSpec
    extends AnyFlatSpec
    with Matchers
    with BeforeAndAfter
    with MockFactory
    with Eventually
    with LongPatience:

  "SyncController" should "download pivot block and request block headers" taggedAs (
    UnitTest,
    SyncTest
  ) in withTestSetup() { testSetup =>
    import testSetup.*
    syncController ! SyncController.WrappedSyncProtocol(SyncProtocol.Start)

    val handshakedPeers = HandshakedPeers(twoAcceptedPeers)

    setupAutoPilot(networkPeerManager, handshakedPeers, defaultPivotBlockHeader, BlockchainData(Seq()))

    eventually {
      someTimePasses()
      val syncState = storagesInstance.storages.fastSyncStateStorage.getSyncState().get
      syncState.bestBlockHeaderNumber shouldBe 0
      syncState.pivotBlock == defaultPivotBlockHeader
    }
  }

  it should "download better pivot block, request state, blocks and finish when downloaded" taggedAs (
    UnitTest,
    SyncTest
  ) in withTestSetup() { testSetup =>
    import testSetup.*
    startWithState(defaultStateBeforeNodeRestart)

    syncController ! SyncController.WrappedSyncProtocol(SyncProtocol.Start)

    val handshakedPeers = HandshakedPeers(singlePeer)

    val newBlocks =
      getHeaders(defaultStateBeforeNodeRestart.bestBlockHeaderNumber + 1, syncConfig.blockHeadersPerRequest)

    setupAutoPilot(networkPeerManager, handshakedPeers, defaultPivotBlockHeader, BlockchainData(newBlocks))

    val watcher = TestProbe()
    watcher.watch(syncController)

    eventually {
      someTimePasses()
      // switch to regular download
      val children = syncController.children
      assert(storagesInstance.storages.appStateStorage.isFastSyncDone())
      assert(children.exists(ref => ref.path.name.startsWith("regular-sync")))
      assert(blockchainReader.getBestBlockNumber == defaultPivotBlockHeader.number.value)
    }
  }

  it should "gracefully handle receiving empty receipts while syncing" taggedAs (
    UnitTest,
    SyncTest
  ) in withTestSetup() { testSetup =>
    import testSetup.*
    startWithState(defaultStateBeforeNodeRestart)

    syncController ! SyncController.WrappedSyncProtocol(SyncProtocol.Start)

    val handshakedPeers = HandshakedPeers(singlePeer)
    val watcher = TestProbe()
    watcher.watch(syncController)

    val newBlocks =
      getHeaders(defaultStateBeforeNodeRestart.bestBlockHeaderNumber + 1, syncConfig.blockHeadersPerRequest)

    setupAutoPilot(
      networkPeerManager,
      handshakedPeers,
      defaultPivotBlockHeader,
      BlockchainData(newBlocks),
      failedReceiptsTries = 1
    )

    eventually {
      someTimePasses()
      assert(storagesInstance.storages.appStateStorage.isFastSyncDone())
      // switch to regular download
      val children = syncController.children
      assert(children.exists(ref => ref.path.name.startsWith("regular-sync")))
      assert(blockchainReader.getBestBlockNumber == defaultPivotBlockHeader.number.value)
    }
  }

  it should "handle blocks that fail validation" taggedAs (UnitTest, SyncTest) in withTestSetup(
    validators = new Mocks.MockValidatorsAlwaysSucceed:
      override val blockHeaderValidator: BlockHeaderValidator = new BlockHeaderValidator:
        override def validate(
            blockHeader: BlockHeader,
            getBlockHeaderByHash: GetBlockHeaderByHash
        )(implicit blockchainConfig: BlockchainConfig): Either[BlockHeaderError, BlockHeaderValid] =
          Left(HeaderPoWError)

        // G5 PivotBlockSelector uses validateHeaderOnly for PoW backlink checks. Returning Left here
        // causes the backlink to fail on every attempt, driving exponential-backoff retries that exhaust
        // the 25-second eventually window before SelectionFailed arrives. Only validate() (full block
        // validation, exercised by FastSync.processHeaders) must fail for this test to work correctly.
        override def validateHeaderOnly(blockHeader: BlockHeader)(implicit
            blockchainConfig: BlockchainConfig
        ): Either[BlockHeaderError, BlockHeaderValid] =
          Right(BlockHeaderValid)
  ) { testSetup =>
    import testSetup.*
    startWithState(
      defaultStateBeforeNodeRestart.copy(
        nextBlockToFullyValidate = defaultStateBeforeNodeRestart.bestBlockHeaderNumber + 1,
        // safeDownloadTarget must exceed bestBlockHeaderNumber so FastSync enqueues headers
        // beyond 399500. The Typed FastSync caps header fetches at safeDownloadTarget via
        // enqueueHeadersIfNeeded; the Classic version did not have this guard.
        safeDownloadTarget = (beforeRestartPivot.number + syncConfig.fastSyncBlockValidationX).value
      )
    )

    syncController ! SyncController.WrappedSyncProtocol(SyncProtocol.Start)

    val handshakedPeers = HandshakedPeers(singlePeer)

    val newBlocks =
      getHeaders(defaultStateBeforeNodeRestart.bestBlockHeaderNumber + 1, syncConfig.blockHeadersPerRequest)

    setupAutoPilot(networkPeerManager, handshakedPeers, defaultPivotBlockHeader, BlockchainData(newBlocks), 0, 0)

    val watcher = TestProbe()
    watcher.watch(syncController)

    eventually {
      someTimePasses()
      val syncState = storagesInstance.storages.fastSyncStateStorage.getSyncState().get
      syncState.bestBlockHeaderNumber shouldBe (defaultStateBeforeNodeRestart.bestBlockHeaderNumber - syncConfig.fastSyncBlockValidationN)
      syncState.nextBlockToFullyValidate shouldBe (defaultStateBeforeNodeRestart.bestBlockHeaderNumber - syncConfig.fastSyncBlockValidationN + 1)
      syncState.blockBodiesQueue.isEmpty shouldBe true
      syncState.receiptsQueue.isEmpty shouldBe true
    }
  }

  it should "rewind fast-sync state if received header have no known parent" taggedAs (
    UnitTest,
    SyncTest
  ) in withTestSetup(
    validators = new Mocks.MockValidatorsAlwaysSucceed:
      override val blockHeaderValidator: BlockHeaderValidator = new BlockHeaderValidator:
        val invalidBlockNNumber = 399510
        override def validate(
            blockHeader: BlockHeader,
            getBlockHeaderByHash: GetBlockHeaderByHash
        )(implicit blockchainConfig: BlockchainConfig): Either[BlockHeaderError, BlockHeaderValid] =
          if blockHeader.number.value == invalidBlockNNumber then Left(HeaderParentNotFoundError)
          else Right(BlockHeaderValid)

        override def validateHeaderOnly(blockHeader: BlockHeader)(implicit
            blockchainConfig: BlockchainConfig
        ): Either[BlockHeaderError, BlockHeaderValid] =
          Right(BlockHeaderValid)
  ) { testSetup =>
    import testSetup.*
    startWithState(defaultStateBeforeNodeRestart)

    syncController ! SyncController.WrappedSyncProtocol(SyncProtocol.Start)

    val handshakedPeers = HandshakedPeers(singlePeer)

    val blockHeaders =
      getHeaders(defaultStateBeforeNodeRestart.bestBlockHeaderNumber + 1, 10)

    setupAutoPilot(networkPeerManager, handshakedPeers, defaultPivotBlockHeader, BlockchainData(blockHeaders))

    val watcher = TestProbe()
    watcher.watch(syncController)

    eventually {
      someTimePasses()
      val syncState = storagesInstance.storages.fastSyncStateStorage.getSyncState().get
      val invalidBlockNumber = defaultStateBeforeNodeRestart.bestBlockHeaderNumber + 9

      // Header validation failed at header number 399510
      // Rewind sync state by configured number of headers.
      syncState.bestBlockHeaderNumber shouldBe (invalidBlockNumber - syncConfig.fastSyncBlockValidationN)
      syncState.nextBlockToFullyValidate shouldBe (invalidBlockNumber - syncConfig.fastSyncBlockValidationN + 1)
      syncState.blockBodiesQueue.isEmpty shouldBe true
      syncState.receiptsQueue.isEmpty shouldBe true
    }
  }

  it should "not change best block after receiving faraway block" taggedAs (UnitTest, SyncTest) in withTestSetup() {
    testSetup =>
      import testSetup.*

      startWithState(defaultStateBeforeNodeRestart)

      syncController ! SyncController.WrappedSyncProtocol(SyncProtocol.Start)

      val handshakedPeers = HandshakedPeers(twoAcceptedPeers)
      val watcher = TestProbe()
      watcher.watch(syncController)

      val newBlocks =
        getHeaders(defaultStateBeforeNodeRestart.bestBlockHeaderNumber + 1, syncConfig.blockHeadersPerRequest)

      setupAutoPilot(networkPeerManager, handshakedPeers, defaultPivotBlockHeader, BlockchainData(newBlocks))
      val fast = syncController.children.find(_.path.name.startsWith("fast-sync")).get

      // Inject far-ahead headers into Typed FastSync via WrappedPrhResult (private[sync] — accessible here).
      // FastSync must ignore them (stale/unassigned delivery) and not change the pivot.
      val futureHeaders = Seq(defaultPivotBlockHeader.copy(number = defaultPivotBlockHeader.number + 20))
      val futureResult =
        PeerRequestHandler.ResponseReceived(0, peer2, ETHPackets.BlockHeaders(BigInt(0), futureHeaders), 2L)
      implicit val ec = system.dispatcher
      val injectionTask = system.scheduler.scheduleAtFixedRate(0.seconds, 0.5.seconds)(() =>
        fast.toTyped[FastSync.Command] ! FastSync.WrappedPrhResult(futureResult)
      )

      try
        eventually {
          someTimePasses()
          storagesInstance.storages.fastSyncStateStorage.getSyncState().get.pivotBlock shouldBe defaultPivotBlockHeader
        }

        // even though we receive this future headers fast sync should finish
        eventually {
          someTimePasses()
          assert(storagesInstance.storages.appStateStorage.isFastSyncDone())
        }
      finally injectionTask.cancel()
  }

  it should "update pivot block if pivot fail" taggedAs (UnitTest, SyncTest) in withTestSetup(
    new Mocks.MockValidatorsAlwaysSucceed:
      override val blockHeaderValidator: BlockHeaderValidator = new BlockHeaderValidator:
        override def validate(
            blockHeader: BlockHeader,
            getBlockHeaderByHash: GetBlockHeaderByHash
        )(implicit blockchainConfig: BlockchainConfig): Either[BlockHeaderError, BlockHeaderValid] =
          if blockHeader.number.value != 399500 + 10 then Right(BlockHeaderValid)
          else Left(HeaderParentNotFoundError)

        override def validateHeaderOnly(blockHeader: BlockHeader)(implicit
            blockchainConfig: BlockchainConfig
        ): Either[BlockHeaderError, BlockHeaderValid] =
          Right(BlockHeaderValid)
  ) { testSetup =>
    import testSetup.*
    startWithState(defaultStateBeforeNodeRestart)

    syncController ! SyncController.WrappedSyncProtocol(SyncProtocol.Start)

    val handshakedPeers = HandshakedPeers(twoAcceptedPeers.filter(_._1 == peer2))

    val newPivot = defaultPivotBlockHeader.copy(number = defaultPivotBlockHeader.number + 20)
    val peerWithNewPivot = defaultPeer1Info.copy(maxBlockNumber = bestBlock + 20)
    val newHandshaked = HandshakedPeers(Map(peer1 -> peerWithNewPivot))

    val newBest = 399500 + 9

    val newBlocks =
      getHeaders(defaultStateBeforeNodeRestart.bestBlockHeaderNumber + 1, syncConfig.blockHeadersPerRequest)

    val autopilot =
      setupAutoPilot(networkPeerManager, handshakedPeers, defaultPivotBlockHeader, BlockchainData(newBlocks))

    eventually {
      littleTimePasses()
      storagesInstance.storages.fastSyncStateStorage.getSyncState().get.pivotBlock shouldBe defaultPivotBlockHeader
      assert(blacklist.isBlacklisted(peer2.id))
    }

    autopilot.updateAutoPilot(newHandshaked, newPivot, BlockchainData(newBlocks))

    val watcher = TestProbe()
    watcher.watch(syncController)

    eventually {
      someTimePasses()
      val syncState = storagesInstance.storages.fastSyncStateStorage.getSyncState().get
      syncState.pivotBlock shouldBe newPivot
      syncState.safeDownloadTarget shouldEqual (newPivot.number + syncConfig.fastSyncBlockValidationX).value
      syncState.blockBodiesQueue.isEmpty shouldBe true
      syncState.receiptsQueue.isEmpty shouldBe true
      syncState.bestBlockHeaderNumber shouldBe (newBest - syncConfig.fastSyncBlockValidationN)
    }
  }

  it should "not process, out of date new pivot block" taggedAs (UnitTest, SyncTest) in withTestSetup() { testSetup =>
    import testSetup.*
    startWithState(defaultStateBeforeNodeRestart)
    syncController ! SyncController.WrappedSyncProtocol(SyncProtocol.Start)

    val staleNewPeer1Info = defaultPeer1Info.copy(maxBlockNumber = bestBlock - 2)
    val staleHeader = defaultPivotBlockHeader.copy(number = defaultPivotBlockHeader.number - 2)
    val staleHandshakedPeers = HandshakedPeers(Map(peer1 -> staleNewPeer1Info))

    val freshHeader = defaultPivotBlockHeader
    val freshPeerInfo1 = defaultPeer1Info
    val freshHandshakedPeers = HandshakedPeers(Map(peer1 -> freshPeerInfo1))

    val watcher = TestProbe()
    watcher.watch(syncController)

    val newBlocks =
      getHeaders(defaultStateBeforeNodeRestart.bestBlockHeaderNumber + 1, syncConfig.blockHeadersPerRequest)

    val pilot =
      setupAutoPilot(
        networkPeerManager,
        staleHandshakedPeers,
        staleHeader,
        BlockchainData(newBlocks),
        onlyPivot = true
      )

    eventually {
      someTimePasses()
      storagesInstance.storages.fastSyncStateStorage.getSyncState().get.pivotBlockUpdateFailures shouldBe 1
    }

    pilot.updateAutoPilot(freshHandshakedPeers, freshHeader, BlockchainData(newBlocks), onlyPivot = true)

    eventually {
      someTimePasses()
      storagesInstance.storages.fastSyncStateStorage.getSyncState().get.pivotBlock shouldBe defaultPivotBlockHeader
    }
  }

  // REWRITTEN (P10): original had delta=10 < threshold(530) — pivot update was impossible.
  // New test covers the stalePivotAfterRestart rejection path: when PivotBlockSelector returns the
  // *same* pivot number as the pre-restart value, FastSync rejects it (stalePivotAfterRestart guard
  // in newPivotIsGoodEnough), increments pivotBlockUpdateFailures, and retries. State download must
  // NOT start until a genuinely fresh pivot (higher number) is accepted.
  it should "start state download only when pivot block is fresh enough" taggedAs (
    UnitTest,
    SyncTest
  ) in withTestSetup() { testSetup =>
    import testSetup.*

    // beforeRestartPivot.number = defaultExpectedPivotBlock - 1 = 399499
    startWithState(defaultStateBeforeNodeRestart)
    syncController ! SyncController.WrappedSyncProtocol(SyncProtocol.Start)

    val newBlocks =
      getHeaders(defaultStateBeforeNodeRestart.bestBlockHeaderNumber + 1, syncConfig.blockHeadersPerRequest)

    // Peers at bestBlock - 1 = 399999: PivotBlockSelector picks 399999 - 500 = 399499 = beforeRestartPivot.
    // SyncRestart rejects same-height pivot (stalePivotAfterRestart guard) → failure increments,
    // state download does NOT start.
    val sameLevelPeerInfo = defaultPeer1Info.copy(maxBlockNumber = bestBlock - 1)
    val sameLevelPeers = HandshakedPeers(Map(peer1 -> sameLevelPeerInfo))

    val pilot = setupAutoPilot(
      networkPeerManager,
      sameLevelPeers,
      beforeRestartPivot,
      BlockchainData(newBlocks),
      onlyPivot = true
    )

    // At least one stalePivotAfterRestart rejection must have occurred; exact count is timing-sensitive.
    eventually {
      someTimePasses()
      storagesInstance.storages.fastSyncStateStorage.getSyncState().get.pivotBlockUpdateFailures should be > 0
    }
    stateDownloadStarted shouldBe false

    // Peers advance to bestBlock = 400000: PivotBlockSelector picks 400000 - 500 = 399500 > 399499.
    // newPivotIsGoodEnough returns true → pivot accepted → state download begins.
    pilot.updateAutoPilot(
      HandshakedPeers(singlePeer),
      defaultPivotBlockHeader,
      BlockchainData(newBlocks)
    )

    eventually {
      someTimePasses()
      stateDownloadStarted shouldBe true
      storagesInstance.storages.fastSyncStateStorage
        .getSyncState()
        .map(_.pivotBlock)
        .getOrElse(defaultPivotBlockHeader) shouldBe defaultPivotBlockHeader
    }
  }

  it should "re-enqueue block bodies when empty response is received" taggedAs (UnitTest, SyncTest) in withTestSetup() {
    testSetup =>
      import testSetup.*

      startWithState(defaultStateBeforeNodeRestart)

      syncController ! SyncController.WrappedSyncProtocol(SyncProtocol.Start)

      val handshakedPeers = HandshakedPeers(singlePeer)
      val watcher = TestProbe()
      watcher.watch(syncController)

      val newBlocks =
        getHeaders(defaultStateBeforeNodeRestart.bestBlockHeaderNumber + 1, syncConfig.blockHeadersPerRequest)

      setupAutoPilot(
        networkPeerManager,
        handshakedPeers,
        defaultPivotBlockHeader,
        BlockchainData(newBlocks),
        failedBodiesTries = 1
      )

      eventually {
        someTimePasses()
        assert(storagesInstance.storages.appStateStorage.isFastSyncDone())
        // switch to regular download
        val children = syncController.children
        assert(children.exists(ref => ref.path.name.startsWith("regular-sync")))
        assert(blockchainReader.getBestBlockNumber == defaultPivotBlockHeader.number.value)
      }
  }

  it should "update pivot block during state sync if it goes stale" taggedAs (
    UnitTest,
    SyncTest
  ) in withTestSetup() { testSetup =>
    import testSetup.*
    startWithState(defaultStateBeforeNodeRestart)

    syncController ! SyncController.WrappedSyncProtocol(SyncProtocol.Start)

    val handshakedPeers = HandshakedPeers(singlePeer)

    val newBlocks =
      getHeaders(defaultStateBeforeNodeRestart.bestBlockHeaderNumber + 1, 50)

    val pilot = setupAutoPilot(
      networkPeerManager,
      handshakedPeers,
      defaultPivotBlockHeader,
      BlockchainData(newBlocks),
      failedNodeRequest = true
    )

    // choose first pivot and as it is fresh enough start state sync
    eventually {
      someTimePasses()
      val syncState = storagesInstance.storages.fastSyncStateStorage.getSyncState().get
      syncState.isBlockchainWorkFinished shouldBe true
      syncState.updatingPivotBlock shouldBe false
      stateDownloadStarted shouldBe true
    }
    val peerWithBetterBlock = defaultPeer1Info.copy(maxBlockNumber = bestBlock + syncConfig.maxPivotBlockAge)
    val newHandshakedPeers = HandshakedPeers(Map(peer1 -> peerWithBetterBlock))
    val newPivot = defaultPivotBlockHeader.copy(number = defaultPivotBlockHeader.number + syncConfig.maxPivotBlockAge)

    pilot.updateAutoPilot(
      newHandshakedPeers,
      newPivot,
      BlockchainData(newBlocks),
      failedNodeRequest = true
    )

    // sync to new pivot
    eventually {
      someTimePasses()
      val syncState = storagesInstance.storages.fastSyncStateStorage.getSyncState().get
      syncState.pivotBlock shouldBe newPivot
    }

    // enable peer to respond with mpt nodes
    pilot.updateAutoPilot(newHandshakedPeers, newPivot, BlockchainData(newBlocks))

    val watcher = TestProbe()
    watcher.watch(syncController)

    eventually {
      someTimePasses()
      // switch to regular download
      val children = syncController.children
      assert(storagesInstance.storages.appStateStorage.isFastSyncDone())
      assert(children.exists(ref => ref.path.name.startsWith("regular-sync")))
      assert(blockchainReader.getBestBlockNumber == newPivot.number.value)
    }
  }

  // ── T6-T9: runningRecovery state machine ──────────────────────────────────────────────────────
  // These tests drive SyncController through the post-SNAP recovery path.
  // Recovery actors scan an empty trie → MissingRootNodeException → ScanResult(Seq.empty) → RecoveryComplete.

  private val recoveryFakeStateRoot: ByteString = ByteString(Array.fill[Byte](32)(0x55.toByte))

  private def seedSnapDoneWithRecovery(
      appState: com.chipprbots.ethereum.db.storage.AppStateStorage,
      needBytecode: Boolean = true,
      needStorage: Boolean = true,
      withStateRoot: Boolean = true
  ): Unit =
    appState.snapSyncDone().commit()
    if !needBytecode then appState.bytecodeRecoveryDone().commit()
    if !needStorage then appState.storageRecoveryDone().commit()
    if withStateRoot then
      appState.putSnapSyncStateRoot(recoveryFakeStateRoot).commit()
      appState.putSnapSyncPivotBlock(BigInt(100)).commit()

  it should "transition to regular sync after both bytecode and storage recovery complete" taggedAs (
    UnitTest,
    SyncTest
  ) in withRecoveryTestSetup() { testSetup =>
    import testSetup.*
    seedSnapDoneWithRecovery(storagesInstance.storages.appStateStorage)

    syncController ! SyncController.WrappedSyncProtocol(SyncProtocol.Start)

    eventually {
      someTimePasses()
      assert(syncController.children.exists(_.path.name.startsWith("regular-sync")))
    }
    storagesInstance.storages.appStateStorage.isBytecodeRecoveryDone() shouldBe true
    storagesInstance.storages.appStateStorage.isStorageRecoveryDone() shouldBe true
  }

  it should "transition to regular sync when only bytecode recovery is needed (storage pre-done)" taggedAs (
    UnitTest,
    SyncTest
  ) in withRecoveryTestSetup() { testSetup =>
    import testSetup.*
    seedSnapDoneWithRecovery(storagesInstance.storages.appStateStorage, needStorage = false)

    syncController ! SyncController.WrappedSyncProtocol(SyncProtocol.Start)

    eventually {
      someTimePasses()
      assert(syncController.children.exists(_.path.name.startsWith("regular-sync")))
    }
    storagesInstance.storages.appStateStorage.isBytecodeRecoveryDone() shouldBe true
  }

  it should "transition to regular sync when only storage recovery is needed (bytecode pre-done)" taggedAs (
    UnitTest,
    SyncTest
  ) in withRecoveryTestSetup() { testSetup =>
    import testSetup.*
    seedSnapDoneWithRecovery(storagesInstance.storages.appStateStorage, needBytecode = false)

    syncController ! SyncController.WrappedSyncProtocol(SyncProtocol.Start)

    eventually {
      someTimePasses()
      assert(syncController.children.exists(_.path.name.startsWith("regular-sync")))
    }
    storagesInstance.storages.appStateStorage.isStorageRecoveryDone() shouldBe true
  }

  it should "skip recovery and start regular sync immediately when stateRoot or pivotBlock is missing" taggedAs (
    UnitTest,
    SyncTest
  ) in withRecoveryTestSetup() { testSetup =>
    import testSetup.*
    // snap done but no stateRoot/pivotBlock stored → startRecovery falls to case _ => and calls startRegularSync
    seedSnapDoneWithRecovery(
      storagesInstance.storages.appStateStorage,
      withStateRoot = false
    )

    syncController ! SyncController.WrappedSyncProtocol(SyncProtocol.Start)

    eventually {
      someTimePasses()
      assert(syncController.children.exists(_.path.name.startsWith("regular-sync")))
    }
    storagesInstance.storages.appStateStorage.isBytecodeRecoveryDone() shouldBe true
    storagesInstance.storages.appStateStorage.isStorageRecoveryDone() shouldBe true
  }

  // ── PoS regular sync: ask peers for the block a CL head is missing ─────────────────────────────────────────────────
  // hive "Invalid Missing Ancestor Syncing ReOrg, StateRoot, EmptyTxs=True, CanonicalReOrg=True, Invalid P9": the only
  // peer handshook at genesis and never re-advertised, so regular sync never asked it for anything. See
  // MissingAncestorProbe.

  it should "probe peers for the unknown parent of a CL head held only by hash (PoS, regular sync)" taggedAs (
    UnitTest,
    SyncTest
  ) in withPosRegularSyncSetup(terminalTotalDifficulty = Some(BigInt(0))) { testSetup =>
    import testSetup.*
    startRegularSyncAndWait()

    val List(missingParent, clHead) = com.chipprbots.ethereum.BlockHelpers
      .generateChain(2, com.chipprbots.ethereum.BlockHelpers.genesis): @unchecked
    blockchainWriter.storeBlockByHashOnly(clHead).commit() // how engine_newPayload stores an ACCEPTED payload
    forkChoiceManager.notifyBeaconHead(
      com.chipprbots.ethereum.consensus.engine.ForkChoiceState(clHead.hash.value, zero32, zero32)
    )

    networkPeerManager.fishForMessage(10.seconds, "ProbeMissingAncestorCmd for the CL head's parent") {
      case NetworkPeerManagerActor.ProbeMissingAncestorCmd(hash, number) =>
        hash shouldBe missingParent.hash.value
        number shouldBe missingParent.number.value
        true
      case _ => false
    }
  }

  it should "never probe on a chain with no terminal total difficulty — the ETC/Mordor shape" taggedAs (
    UnitTest,
    SyncTest
  ) in withPosRegularSyncSetup(terminalTotalDifficulty = None) { testSetup =>
    import testSetup.*
    startRegularSyncAndWait()

    val List(_, clHead) = com.chipprbots.ethereum.BlockHelpers
      .generateChain(2, com.chipprbots.ethereum.BlockHelpers.genesis): @unchecked
    blockchainWriter.storeBlockByHashOnly(clHead).commit()
    // Without a TTD the SyncController never registers as the ForkChoiceManager listener, so this reaches nobody.
    forkChoiceManager.notifyBeaconHead(
      com.chipprbots.ethereum.consensus.engine.ForkChoiceState(clHead.hash.value, zero32, zero32)
    )

    val received = networkPeerManager.receiveWhile(2.seconds) { case m => m }
    received.collect { case p: NetworkPeerManagerActor.ProbeMissingAncestorCmd => p } shouldBe empty
  }

  // ── T10-T13: startup diagnostic + handler tests ───────────────────────────────────────────────
  // RLP encoding of a 32-byte hash = valid HashNode (length==MaxEncodedNodeLength → no MPTException)
  private def validMptNodeRlp(hash: ByteString): Array[Byte] = Array(0xa0.toByte) ++ hash.toArray

  private def seedMptNode(
      testSetup: TestSetup,
      hashes: ByteString*
  ): Unit =
    testSetup.storagesInstance.storages.nodeStorage.update(
      Seq.empty,
      hashes.map(h => (h, validMptNodeRlp(h)))
    )

  it should "update pivot header stateRoot to snapStateRoot when snapRoot differs but both are in MPT (SC-1a)" taggedAs (
    UnitTest,
    SyncTest
  ) in withRecoveryTestSetup() { testSetup =>
    import testSetup.*
    val pivotNum = BigInt(100)
    val rootA = ByteString(Array.fill[Byte](32)(0x11)) // stored in pivot header
    val rootB = ByteString(Array.fill[Byte](32)(0x22)) // snapSyncStateRoot — differs from rootA
    val pivotHeader = baseBlockHeader.copy(number = BlockNumber(pivotNum), stateRoot = TrieRoot(rootA))

    // Both roots present in MPT — triggers SC-1a symmetric case
    seedMptNode(testSetup, rootA, rootB)
    blockchainWriter.storeBlockHeader(pivotHeader).commit()
    storagesInstance.storages.appStateStorage.putBestBlockNumber(pivotNum).commit()

    storagesInstance.storages.appStateStorage.snapSyncDone().commit()
    storagesInstance.storages.appStateStorage.bytecodeRecoveryDone().commit()
    storagesInstance.storages.appStateStorage.storageRecoveryDone().commit()
    storagesInstance.storages.appStateStorage.putSnapSyncStateRoot(rootB).commit()

    syncController ! SyncController.WrappedSyncProtocol(SyncProtocol.Start)

    eventually {
      someTimePasses()
      assert(syncController.children.exists(_.path.name.startsWith("regular-sync")))
    }
    // SyncController must have rewritten the pivot header's stateRoot from rootA to rootB
    blockchainReader.getBlockHeaderByNumber(pivotNum).map(_.stateRoot) shouldBe Some(TrieRoot(rootB))
  }

  it should "substitute finalized root into pivot header when pivot stateRoot is missing from MPT (SC-1b)" taggedAs (
    UnitTest,
    SyncTest
  ) in withRecoveryTestSetup() { testSetup =>
    import testSetup.*
    val pivotNum = BigInt(100)
    val rootA = ByteString(Array.fill[Byte](32)(0x33)) // stored in pivot header, NOT in MPT
    val rootB = ByteString(Array.fill[Byte](32)(0x44)) // finalizedRoot, present in MPT
    val pivotHeader = baseBlockHeader.copy(number = BlockNumber(pivotNum), stateRoot = TrieRoot(rootA))

    // Only rootB in MPT — pivotRootExists=false → finalized substitution path
    seedMptNode(testSetup, rootB)
    blockchainWriter.storeBlockHeader(pivotHeader).commit()
    storagesInstance.storages.appStateStorage.putBestBlockNumber(pivotNum).commit()

    storagesInstance.storages.appStateStorage.snapSyncDone().commit()
    storagesInstance.storages.appStateStorage.bytecodeRecoveryDone().commit()
    storagesInstance.storages.appStateStorage.storageRecoveryDone().commit()
    storagesInstance.storages.appStateStorage.putSnapSyncFinalizedRoot(rootB).commit()

    syncController ! SyncController.WrappedSyncProtocol(SyncProtocol.Start)

    eventually {
      someTimePasses()
      assert(syncController.children.exists(_.path.name.startsWith("regular-sync")))
    }
    blockchainReader.getBlockHeaderByNumber(pivotNum).map(_.stateRoot) shouldBe Some(TrieRoot(rootB))
  }

  it should "clear both done flags and restart SNAP when HealingImpossible is received" taggedAs (
    UnitTest,
    SyncTest
  ) in withRecoveryTestSetup() { testSetup =>
    import testSetup.*
    // No snapSyncDone → start() → case (false, _, true, _) → startSnapSync()
    syncController ! SyncController.WrappedSyncProtocol(SyncProtocol.Start)

    eventually {
      someTimePasses()
      assert(syncController.children.exists(_.path.name.startsWith("snap-sync")))
    }

    // Manually set both flags so we can verify HealingImpossible clears them
    storagesInstance.storages.appStateStorage.snapSyncDone().commit()
    storagesInstance.storages.appStateStorage.fastSyncDone().commit()

    syncController ! SyncController.WrappedSyncProtocol(SyncProtocol.HealingImpossible)

    // HealingImpossible clears both flags synchronously
    storagesInstance.storages.appStateStorage.isSnapSyncDone() shouldBe false
    storagesInstance.storages.appStateStorage.isFastSyncDone() shouldBe false

    // startSnapSync() spawned a new generation; eventually a snap-sync child is visible
    eventually {
      someTimePasses()
      assert(syncController.children.exists(_.path.name.startsWith("snap-sync")))
    }
  }

  it should "clear sync flags and restart SNAP with minPivotBlock when RegularSyncStuck is received (SC-4)" taggedAs (
    UnitTest,
    SyncTest
  ) in withTestSetup() { testSetup =>
    import testSetup.*
    // doFastSync=true, doSnapSync=false; pre-set fastSyncDone → case (_, true, false, true) → startRegularSync()
    storagesInstance.storages.appStateStorage.fastSyncDone().commit()

    syncController ! SyncController.WrappedSyncProtocol(SyncProtocol.Start)

    eventually {
      someTimePasses()
      assert(syncController.children.exists(_.path.name.startsWith("regular-sync")))
    }

    syncController ! SyncController.WrappedSyncProtocol(
      SyncProtocol.RegularSyncStuck(BigInt(24601125), "deadbeefdeadbeef")
    )

    storagesInstance.storages.appStateStorage.isSnapSyncDone() shouldBe false
    storagesInstance.storages.appStateStorage.isFastSyncDone() shouldBe false

    eventually {
      someTimePasses()
      assert(syncController.children.exists(_.path.name.startsWith("snap-sync")))
    }
  }

  class TestSetup(
      _validators: Validators = new Mocks.MockValidatorsAlwaysSucceed
  ) extends EphemBlockchainTestSetup
      with TestSyncPeers
      with TestSyncConfig:

    @volatile
    var stateDownloadStarted = false

    // + cake overrides
    implicit override lazy val system: ActorSystem =
      ActorSystem("SyncControllerSpec_System", ConfigFactory.load("explicit-scheduler"))

    override lazy val vm: VMImpl = new VMImpl

    override lazy val validators: Validators = _validators

    override lazy val mining: TestMining = buildTestMining().withValidators(validators)

    // + cake overrides

    val networkPeerManager: TestProbe = TestProbe()
    val peerMessageBus: TestProbe = TestProbe()
    val pendingTransactionsManager: TestProbe = TestProbe()

    val ommersPool: TestProbe = TestProbe()

    val blacklist: CacheBasedBlacklist = CacheBasedBlacklist.empty(100)

    override def defaultSyncConfig: SyncConfig = super.defaultSyncConfig.copy(
      doFastSync = true,
      branchResolutionRequestSize = 30,
      checkForNewBlockInterval = 1.second,
      blockHeadersPerRequest = 10,
      blockBodiesPerRequest = 10,
      maximumTargetUpdateFailures = 50,
      minPeersToChoosePivotBlock = 1,
      peersScanInterval = 1.second,
      redownloadMissingStateNodes = false,
      fastSyncBlockValidationX = 10,
      blacklistDuration = 1.second,
      peerResponseTimeout = 2.seconds,
      persistStateSnapshotInterval = 0.1.seconds,
      fastSyncThrottle = 10.milliseconds,
      maxPivotBlockAge = 30
    )

    // SyncController is Pekko Typed (Group ROOT) — a Behavior[Any]. Spawn through PropsAdapter so this Classic spec
    // keeps `TestActorRef` child inspection (`syncController.children`). externalSchedulerOpt threads the
    // ExplicitlyTriggeredScheduler so `someTimePasses()` continues to drive the actor's inline scheduler callbacks;
    // its withTimers fire on the system scheduler (also the ExplicitlyTriggeredScheduler via explicit-scheduler.conf).
    lazy val blockTopic: org.apache.pekko.actor.typed.ActorRef[
      org.apache.pekko.actor.typed.pubsub.Topic.Command[com.chipprbots.ethereum.jsonrpc.NewBlockImported]
    ] = system.spawn(
      org.apache.pekko.actor.typed.pubsub.Topic[com.chipprbots.ethereum.jsonrpc.NewBlockImported](
        "block-imported-topic"
      ),
      "block-imported-topic"
    )

    lazy val syncController: TestActorRef[Nothing] = TestActorRef(
      org.apache.pekko.actor.typed.scaladsl.adapter.PropsAdapter(
        SyncController(
          blockchain,
          blockchainReader,
          blockchainWriter,
          storagesInstance.storages.appStateStorage,
          storagesInstance.storages.blockNumberMappingStorage,
          storagesInstance.storages.evmCodeStorage,
          storagesInstance.storages.stateStorage,
          storagesInstance.storages.nodeStorage,
          storagesInstance.storages.flatSlotStorage,
          storagesInstance.storages.fastSyncStateStorage,
          consensusAdapter,
          validators,
          peerMessageBus.ref,
          pendingTransactionsManager.ref
            .toTyped[com.chipprbots.ethereum.transactions.PendingTransactionsManager.Command],
          blockTopic,
          ommersPool.ref,
          networkPeerManager.ref,
          blacklist,
          syncConfig,
          this,
          externalSchedulerOpt = Some(system.scheduler)
        )
      )
    )

    val EmptyTrieRootHash: ByteString = Account.EmptyStorageRootHash.value
    val baseBlockHeader = Fixtures.Blocks.Genesis.header

    blockchainWriter.storeChainWeight(baseBlockHeader.parentHash, ChainWeight.zero).commit()

    case class BlockchainData(
        headers: Map[BigInt, BlockHeader],
        bodies: Map[ByteString, BlockBody],
        receipts: Map[ByteString, Seq[Receipt]]
    )
    object BlockchainData:
      def apply(headers: Seq[BlockHeader]): BlockchainData =
        // assumes headers are correct chain
        headers.foldLeft(new BlockchainData(Map.empty, Map.empty, Map.empty)) { (state, header) =>
          state.copy(
            headers = state.headers + (header.number.value -> header),
            bodies = state.bodies + (header.hash.value -> BlockBody.empty),
            receipts = state.receipts + (header.hash.value -> Seq.empty)
          )
        }
    // scalastyle:off method.length
    case class SyncStateAutoPilot(
        handshakedPeers: HandshakedPeers,
        pivotHeader: BlockHeader,
        blockchainData: BlockchainData,
        failedReceiptsTries: Int,
        failedBodiesTries: Int,
        onlyPivot: Boolean,
        failedNodeRequest: Boolean,
        autoPilotProbeRef: ActorRef
    ) extends AutoPilot:
      override def run(sender: ActorRef, msg: Any): AutoPilot =
        msg match
          case NetworkPeerManagerActor.GetHandshakedPeers =>
            sender ! handshakedPeers
            this

          case NetworkPeerManagerActor.GetHandshakedPeersCmd(replyTo) =>
            replyTo ! handshakedPeers
            this

          case NetworkPeerManagerActor.RegisterChainWeightCalibrationTarget(_) =>
            this

          case NetworkPeerManagerActor.RegisterChainWeightCalibrationTargetCmd(_) =>
            this

          case NetworkPeerManagerActor.CalibrateChainWeightNow =>
            this

          // ETH69 G5 by-hash backlink probe: block = Right(hash). Store pivot header in the
          // canonical chain so PivotBlockSelector's canonical-match check succeeds, then reply
          // with the pivot header as the single-element backlink chain.
          case SendMessageCmd(msg: ETHPackets.GetBlockHeaders.GetBlockHeadersEnc, peer)
              if msg.underlyingMsg.block.isRight =>
            val requestId = msg.underlyingMsg.requestId
            blockchainWriter.storeBlockHeader(pivotHeader).commit()
            storagesInstance.storages.blockNumberMappingStorage
              .put(pivotHeader.number.value, pivotHeader.hash.value)
              .commit()
            sender ! MessageFromPeer(ETHPackets.BlockHeaders(requestId, Seq(pivotHeader)), peer)
            this

          // Handle ETH66 GetBlockHeaders by block number (with requestId)
          case SendMessageCmd(msg: ETHPackets.GetBlockHeaders.GetBlockHeadersEnc, peer) =>
            val underlyingMessage = msg.underlyingMsg
            val requestId = underlyingMessage.requestId
            val requestedBlockNumber = underlyingMessage.block.swap.toOption.get
            if requestedBlockNumber == pivotHeader.number.value then
              // pivot block
              sender ! MessageFromPeer(ETHPackets.BlockHeaders(requestId, Seq(pivotHeader)), peer)
            else
              val headers = generateBlockHeaders66(underlyingMessage, blockchainData)
              sender ! MessageFromPeer(ETHPackets.BlockHeaders(requestId, headers), peer)
            this

          // Handle ETH68/69 GetReceipts (with requestId)
          case SendMessageCmd(msg: ETHPackets.GetReceipts.GetReceiptsEnc, peer) if !onlyPivot =>
            val requestId = msg.underlyingMsg.requestId
            if failedReceiptsTries > 0 then
              sender ! MessageFromPeer(ETHPackets.Receipts68(requestId, RLPList()), peer)
              this.copy(failedReceiptsTries = failedReceiptsTries - 1)
            else
              val rec = msg.underlyingMsg.blockHashes.flatMap(h => blockchainData.receipts.get(h))
              // For empty receipts, create an RLPList with empty receipt sequences
              val receiptsRlp = RLPList(rec.map(_ => RLPList())*)
              sender ! MessageFromPeer(ETHPackets.Receipts68(requestId, receiptsRlp), peer)
              this

          case SendMessageCmd(msg: ETHPackets.GetBlockBodies.GetBlockBodiesEnc, peer) if !onlyPivot =>
            val requestId = msg.underlyingMsg.requestId
            if failedBodiesTries > 0 then
              sender ! MessageFromPeer(ETHPackets.BlockBodies(requestId, Seq.empty), peer)
              this.copy(failedBodiesTries = failedBodiesTries - 1)
            else
              val bod = msg.underlyingMsg.hashes.flatMap(h => blockchainData.bodies.get(h))
              sender ! MessageFromPeer(ETHPackets.BlockBodies(requestId, bod), peer)
              this

          case SendMessageCmd(msg: GetBlockBodiesEnc, peer) if !onlyPivot =>
            val requestId = msg.underlyingMsg.requestId
            if failedBodiesTries > 0 then
              sender ! MessageFromPeer(BlockBodies(requestId, Seq.empty), peer)
              this.copy(failedBodiesTries = failedBodiesTries - 1)
            else
              val bod = msg.underlyingMsg.hashes.flatMap(h => blockchainData.bodies.get(h))
              sender ! MessageFromPeer(BlockBodies(requestId, bod), peer)
              this

          // Handle GetNodeData (EIP-4938: rejected in ETH68, but still handled for legacy)
          case SendMessageCmd(_: ETHPackets.GetNodeData.GetNodeDataEnc, peer) if !onlyPivot =>
            stateDownloadStarted = true
            if !failedNodeRequest then
              sender ! MessageFromPeer(
                ETHPackets.NodeData(Seq(ByteString(defaultStateMptLeafWithAccount.toArray))),
                peer
              )
            if !failedNodeRequest then
              sender ! MessageFromPeer(ETH63NodeData(Seq(defaultStateMptLeafWithAccount)), peer)
            this

          case SendMessageCmd(_, _) =>
            this

          case AutoPilotUpdateData(peers, pivot, data, failedReceipts, failedBodies, onlyPivot, failedNode) =>
            sender ! DataUpdated
            this.copy(peers, pivot, data, failedReceipts, failedBodies, onlyPivot, failedNode)

      def updateAutoPilot(
          handshakedPeers: HandshakedPeers,
          pivotHeader: BlockHeader,
          blockchainData: BlockchainData,
          failedReceiptsTries: Int = 0,
          failedBodiesTries: Int = 0,
          onlyPivot: Boolean = false,
          failedNodeRequest: Boolean = false
      ): Unit =
        val sender = TestProbe()
        autoPilotProbeRef.tell(
          AutoPilotUpdateData(
            handshakedPeers,
            pivotHeader,
            blockchainData,
            failedReceiptsTries,
            failedBodiesTries,
            onlyPivot,
            failedNodeRequest
          ),
          sender.ref
        )
        sender.expectMsg(DataUpdated)

    private def generateBlockHeaders66(
        underlyingMessage: ETHPackets.GetBlockHeaders,
        blockchainData: BlockchainData
    ): Seq[BlockHeader] =
      val start = underlyingMessage.block.swap.toOption.get
      val stop = start + underlyingMessage.maxHeaders * (underlyingMessage.skip + 1)

      (start until stop)
        .flatMap(i => blockchainData.headers.get(i))
        .zipWithIndex
        .collect { case (header, index) if index % (underlyingMessage.skip + 1) == 0 => header }

    // scalastyle:off method.length parameter.number
    def setupAutoPilot(
        testProbe: TestProbe,
        handshakedPeers: HandshakedPeers,
        pivotHeader: BlockHeader,
        blockchainData: BlockchainData,
        failedReceiptsTries: Int = 0,
        failedBodiesTries: Int = 0,
        onlyPivot: Boolean = false,
        failedNodeRequest: Boolean = false
    ): SyncStateAutoPilot =
      val autopilot = SyncStateAutoPilot(
        handshakedPeers,
        pivotHeader,
        blockchainData,
        failedReceiptsTries,
        failedBodiesTries,
        onlyPivot,
        failedNodeRequest,
        testProbe.ref
      )
      testProbe.setAutoPilot(autopilot)
      autopilot

    case class AutoPilotUpdateData(
        handshakedPeers: HandshakedPeers,
        pivotHeader: BlockHeader,
        blockchainData: BlockchainData,
        failedReceiptsTries: Int = 0,
        failedBodiesTries: Int = 0,
        onlyPivot: Boolean = false,
        failedNodeRequest: Boolean = false
    )
    case object DataUpdated

    val defaultExpectedPivotBlock = 399500

    val defaultSafeDownloadTarget = defaultExpectedPivotBlock

    val defaultBestBlock: Int = defaultExpectedPivotBlock - 1

    val defaultStateRoot = "deae1dfad5ec8dcef15915811e1f044d2543674fd648f94345231da9fc2646cc"

    val defaultPivotBlockHeader: BlockHeader =
      baseBlockHeader.copy(
        number = BlockNumber(defaultExpectedPivotBlock),
        stateRoot = TrieRoot(ByteString(Hex.decode(defaultStateRoot)))
      )

    val defaultState: SyncState =
      SyncState(
        defaultPivotBlockHeader,
        safeDownloadTarget = defaultSafeDownloadTarget,
        bestBlockHeaderNumber = defaultBestBlock
      )

    val defaultStateMptLeafWithAccount: ByteString =
      ByteString(
        Hex.decode(
          "f86d9e328415c225a782bb339b22acad1c739e42277bc7ef34de3623114997ce78b84cf84a0186cb7d8738d800a056e81f171bcc55a6ff8345e692c0f86e5b48e01b996cadc001622fb5e363b421a0c5d2460186f7233c927e7db2dcc703c0e500b653ca82273b7bfad8045d85a470"
        )
      )

    val beforeRestartPivot: BlockHeader =
      defaultPivotBlockHeader.copy(number = BlockNumber(defaultExpectedPivotBlock - 1))
    val defaultStateBeforeNodeRestart: SyncState = defaultState.copy(
      pivotBlock = beforeRestartPivot,
      bestBlockHeaderNumber = defaultExpectedPivotBlock,
      nextBlockToFullyValidate = (beforeRestartPivot.number + syncConfig.fastSyncBlockValidationX).value
    )

    def getHeaders(from: BigInt, number: BigInt): Seq[BlockHeader] =
      val headers = (from until from + number).toSeq.map { nr =>
        defaultPivotBlockHeader.copy(number = BlockNumber(nr))
      }

      def genChain(
          parenthash: ByteString,
          headers: Seq[BlockHeader],
          result: Seq[BlockHeader] = Seq.empty
      ): Seq[BlockHeader] =
        if headers.isEmpty then result
        else
          val header = headers.head
          val newHeader = header.copy(parentHash = BlockHash(parenthash))
          val newHash = newHeader.hash.value
          genChain(newHash, headers.tail, result :+ newHeader)

      val first = headers.head

      first +: genChain(first.hash.value, headers.tail)

    def startWithState(state: SyncState): Unit =
      storagesInstance.storages.fastSyncStateStorage.putSyncState(state)

    private def testScheduler = system.scheduler.asInstanceOf[ExplicitlyTriggeredScheduler]

    def littleTimePasses(): Unit =
      testScheduler.timePasses(300.millis)

    def someTimePasses(): Unit =
      testScheduler.timePasses(3000.millis)

    def cleanup(): Unit =
      Await.result(system.terminate(), 10.seconds)

  def withTestSetup(validators: Validators = new Mocks.MockValidatorsAlwaysSucceed)(test: TestSetup => Any): Unit =
    val testSetup = new TestSetup(validators)
    try test(testSetup)
    finally testSetup.cleanup()

  /** A SyncController that starts straight into regular sync with a real ForkChoiceManager, on a chain whose
    * terminal-total-difficulty is `terminalTotalDifficulty`. `Some` is the ETH/Sepolia shape (clPivotEnabled), `None`
    * the ETC/Mordor one.
    */
  class PosRegularSyncSetup(terminalTotalDifficulty: Option[BigInt]) extends TestSetup():
    override def defaultSyncConfig: SyncConfig = super.defaultSyncConfig.copy(doFastSync = false, doSnapSync = false)

    lazy val forkChoiceManager: com.chipprbots.ethereum.consensus.engine.ForkChoiceManager =
      new com.chipprbots.ethereum.consensus.engine.ForkChoiceManager(blockchainReader, blockchainWriter)

    val zero32: ByteString = ByteString(new Array[Byte](32))

    private val baseChainConfig: BlockchainConfig = blockchainConfig
    private lazy val chainConfigBuilder: com.chipprbots.ethereum.nodebuilder.BlockchainConfigBuilder =
      new com.chipprbots.ethereum.nodebuilder.BlockchainConfigBuilder
        with com.chipprbots.ethereum.TestInstanceConfigProvider:
        implicit override def blockchainConfig: BlockchainConfig =
          baseChainConfig.copy(terminalTotalDifficulty = terminalTotalDifficulty)

    override lazy val syncController: TestActorRef[Nothing] = TestActorRef(
      org.apache.pekko.actor.typed.scaladsl.adapter.PropsAdapter(
        SyncController(
          blockchain,
          blockchainReader,
          blockchainWriter,
          storagesInstance.storages.appStateStorage,
          storagesInstance.storages.blockNumberMappingStorage,
          storagesInstance.storages.evmCodeStorage,
          storagesInstance.storages.stateStorage,
          storagesInstance.storages.nodeStorage,
          storagesInstance.storages.flatSlotStorage,
          storagesInstance.storages.fastSyncStateStorage,
          consensusAdapter,
          validators,
          peerMessageBus.ref,
          pendingTransactionsManager.ref
            .toTyped[com.chipprbots.ethereum.transactions.PendingTransactionsManager.Command],
          blockTopic,
          ommersPool.ref,
          networkPeerManager.ref,
          blacklist,
          syncConfig,
          chainConfigBuilder,
          forkChoiceManagerOpt = Some(forkChoiceManager),
          externalSchedulerOpt = Some(system.scheduler)
        )
      )
    )

    def startRegularSyncAndWait(): Unit =
      syncController ! SyncController.WrappedSyncProtocol(SyncProtocol.Start)
      eventually {
        someTimePasses()
        assert(syncController.children.exists(_.path.name.startsWith("regular-sync")))
      }

  def withPosRegularSyncSetup(terminalTotalDifficulty: Option[BigInt])(test: PosRegularSyncSetup => Any): Unit =
    val testSetup = new PosRegularSyncSetup(terminalTotalDifficulty)
    try test(testSetup)
    finally testSetup.cleanup()

  def withRecoveryTestSetup()(test: TestSetup => Any): Unit =
    val testSetup = new TestSetup():
      override def defaultSyncConfig: SyncConfig = super.defaultSyncConfig.copy(
        doSnapSync = true,
        doFastSync = false
      )
    try test(testSetup)
    finally testSetup.cleanup()
