// §8a-retro batch 5: DEFERRED — TestActorRef used for .children inspection (Classic-only API);
// migrate when SyncController test no longer needs child inspection (Wave 3 network sprint)
package com.chipprbots.ethereum.blockchain.sync

import java.nio.charset.StandardCharsets

import org.apache.pekko.actor.ActorRef
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.typed.ActorRef as TypedActorRef
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.testkit.ExplicitlyTriggeredScheduler
import org.apache.pekko.testkit.TestActor.AutoPilot
import org.apache.pekko.testkit.TestActorRef
import org.apache.pekko.testkit.TestProbe
import org.apache.pekko.util.ByteString

import scala.collection.immutable.ArraySeq
import scala.compiletime.asMatchable
import scala.concurrent.Await
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

import com.typesafe.config.ConfigFactory
import org.scalamock.scalatest.MockFactory
import org.scalatest.BeforeAndAfter
import org.scalatest.concurrent.Eventually
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.LongPatience
import com.chipprbots.ethereum.Mocks
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncConfig
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController
import com.chipprbots.ethereum.consensus.mining.TestMining
import com.chipprbots.ethereum.consensus.validators.Validators
import com.chipprbots.ethereum.db.dataSource.DataSourceUpdate
import com.chipprbots.ethereum.db.storage.Namespaces
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.ledger.VMImpl
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.HandshakedPeers
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.PeerInfo
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.PeerEventBusActor
import com.chipprbots.ethereum.network.p2p.messages.Capability
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

  "SyncController" should "transition to regular sync after both bytecode and storage recovery complete" taggedAs (
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
    // doSnapSync=false; pre-set fastSyncDone → case (_, _, Regular) → startRegularSync()
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

  // ── Startup sync mode, and databases from before fast sync was removed ───────────────────────────────────────
  // start() picks SNAP when do-snap-sync is set and SNAP is not done, and regular sync otherwise, with one exception:
  // a node where fast sync finished (legacy FastSyncDone) and SNAP has no stake (accounts not complete, saved pivot not
  // the best block) continues in regular sync. A node upgraded mid-fast-sync starts SNAP with a pivot above the best
  // block fast sync downloaded without state. Fast sync's leftover progress record (namespace `f`) is never decoded.

  private val StrandedBest: BigInt = 1000

  /** Leave the database the way an upgrade mid-fast-sync finds it: fast sync's progress record in namespace `f`, and a
    * best block (header stored) that fast sync downloaded but never executed. The record's bytes are arbitrary: they
    * are never decoded.
    */
  private def seedStrandedFastSync(testSetup: TestSetup): Unit =
    import testSetup.*
    val recordKey = ArraySeq.unsafeWrapArray("fast-sync-state".getBytes(StandardCharsets.UTF_8))
    storagesInstance.dataSource.update(
      Seq(DataSourceUpdate(Namespaces.FastSyncStateNamespace, Nil, Seq(recordKey -> ArraySeq[Byte](1, 2, 3))))
    )
    blockchainWriter.storeBlockHeader(baseBlockHeader.copy(number = BlockNumber(StrandedBest))).commit()
    storagesInstance.storages.appStateStorage.putBestBlockNumber(StrandedBest).commit()

  private def childNamed(testSetup: TestSetup, prefix: String): Boolean =
    testSetup.syncController.children.exists(_.path.name.startsWith(prefix))

  /** The ERROR messages SyncController logs while `f` runs (TestActorRef handles the message on the calling thread). */
  private def syncControllerErrorsDuring(f: => Unit): List[String] =
    val logger = org.slf4j.LoggerFactory
      .getLogger("com.chipprbots.ethereum.blockchain.sync.SyncController$Impl")
      .asInstanceOf[ch.qos.logback.classic.Logger]
    val appender = new ch.qos.logback.core.read.ListAppender[ch.qos.logback.classic.spi.ILoggingEvent]
    appender.start()
    logger.addAppender(appender)
    try f
    finally logger.detachAppender(appender)
    appender.list.asScala.toList
      .filter(_.getLevel == ch.qos.logback.classic.Level.ERROR)
      .map(_.getFormattedMessage)

  /** SNAP part-way through healing: pivot saved, every data phase done, best moved on by a healing pivot roll. */
  private def seedMidHeal(testSetup: TestSetup, pivot: BigInt, best: BigInt): Unit =
    import testSetup.*
    val appState = storagesInstance.storages.appStateStorage
    blockchainWriter.storeBlockHeader(baseBlockHeader.copy(number = BlockNumber(best))).commit()
    appState
      .putSnapSyncPivotBlock(pivot)
      .and(appState.putSnapSyncStateRoot(ByteString(Array.fill[Byte](32)(0x66))))
      .and(appState.putSnapSyncAccountsComplete(true))
      .and(appState.putSnapSyncStorageComplete(true))
      .and(appState.putSnapSyncBytecodeComplete(true))
      .and(appState.putBestBlockNumber(best))
      .commit()

  /** Wait until the SNAP child of `controller` has handled the MinPivotBlock / Start its parent sent at spawn: SNAP
    * answers GetProgress only after them.
    */
  private def awaitSnapStarted(controller: TestActorRef[Nothing]): Unit =
    val snapSync = eventually(controller.children.find(_.path.name.startsWith("snap-sync")).get)
    val progress = TestProbe()(controller.underlying.system)
    snapSync.toTyped[SNAPSyncController.Command] ! SNAPSyncController.GetProgress(progress.ref.toTyped)
    progress.expectMsgType[com.chipprbots.ethereum.blockchain.sync.snap.SyncProgress]

  it should "start regular sync when do-snap-sync is off" taggedAs (UnitTest, SyncTest) in withTestSetup() {
    testSetup =>
      import testSetup.*
      syncController ! SyncController.WrappedSyncProtocol(SyncProtocol.Start)

      eventually {
        someTimePasses()
        assert(childNamed(testSetup, "regular-sync"))
      }
      assert(!childNamed(testSetup, "fast-sync"))
  }

  it should "continue in regular sync on a node where fast sync finished and SNAP has no stake" taggedAs (
    UnitTest,
    SyncTest
  ) in withRecoveryTestSetup() { testSetup =>
    import testSetup.*
    // Fast sync finished here long ago; SNAP never committed a pivot. Starting SNAP would leave the node dormant, importing
    // nothing, until a snap peer appeared, and a node more than 64 blocks behind would download the whole state again.
    blockchainWriter.storeBlockHeader(baseBlockHeader.copy(number = BlockNumber(StrandedBest))).commit()
    storagesInstance.storages.appStateStorage
      .fastSyncDone()
      .and(storagesInstance.storages.appStateStorage.putBestBlockNumber(StrandedBest))
      .commit()

    syncController ! SyncController.WrappedSyncProtocol(SyncProtocol.Start)

    eventually {
      someTimePasses()
      assert(childNamed(testSetup, "regular-sync"))
    }
    assert(!childNamed(testSetup, "snap-sync"))
  }

  it should "resume SNAP on a healing node that still carries a FastSyncDone flag" taggedAs (
    UnitTest,
    SyncTest
  ) in withRecoveryTestSetup() { testSetup =>
    import testSetup.*
    // SNAP's accounts are complete and a healing pivot roll moved best past the saved pivot: SNAP has a stake.
    storagesInstance.storages.appStateStorage.fastSyncDone().commit()
    seedMidHeal(testSetup, pivot = StrandedBest + 1, best = StrandedBest + 100)

    syncController ! SyncController.WrappedSyncProtocol(SyncProtocol.Start)
    awaitSnapStarted(syncController)

    assert(!childNamed(testSetup, "regular-sync"))
    storagesInstance.storages.appStateStorage.isSnapSyncAccountsComplete() shouldBe true
  }

  it should "resume SNAP, not regular sync, on a mid-SNAP node that still carries a FastSyncDone flag" taggedAs (
    UnitTest,
    SyncTest
  ) in withRecoveryTestSetup() { testSetup =>
    import testSetup.*
    // Starting SNAP never cleared FastSyncDone, so a node that once finished fast sync can be part-way through SNAP:
    // best = SNAP's pivot, whose state is still being downloaded. Regular sync there would execute on missing state.
    val appState = storagesInstance.storages.appStateStorage
    blockchainWriter.storeBlockHeader(baseBlockHeader.copy(number = BlockNumber(StrandedBest))).commit()
    appState
      .fastSyncDone()
      .and(appState.putBestBlockNumber(StrandedBest))
      .and(appState.putSnapSyncPivotBlock(StrandedBest))
      .and(appState.putSnapSyncStateRoot(ByteString(Array.fill[Byte](32)(0x66))))
      .commit()

    syncController ! SyncController.WrappedSyncProtocol(SyncProtocol.Start)

    eventually {
      someTimePasses()
      assert(childNamed(testSetup, "snap-sync"))
    }
    assert(!childNamed(testSetup, "regular-sync"))
  }

  it should "start SNAP with a pivot above the best block of a node upgraded mid-fast-sync" taggedAs (
    UnitTest,
    SyncTest
  ) in withRecoveryTestSetup() { testSetup =>
    import testSetup.*
    seedStrandedFastSync(testSetup)
    // A snap peer only 10 blocks ahead: SNAP's own pivot (head - 64) is below the stranded best block. Without the
    // floor SNAP reads that as "already synced" and hands the node to regular sync with no state.
    answerPeerPollsWith(snapPeerAt(StrandedBest + 10))

    syncController ! SyncController.WrappedSyncProtocol(SyncProtocol.Start)

    eventually {
      someTimePasses()
      assert(childNamed(testSetup, "pivot-header-bootstrap"))
    }
    storagesInstance.storages.appStateStorage.getSnapSyncBootstrapTarget() shouldBe Some(StrandedBest + 1)
    assert(!childNamed(testSetup, "regular-sync"))
    // Handled once: fast sync's record is gone, so no later start can take a SNAP-moved best block for a stranded one.
    // The floor lives on in SNAP's state until SNAP holds a pivot at or above it.
    storagesInstance.storages.fastSyncStateStorage.hasSyncState shouldBe false
    storagesInstance.storages.appStateStorage.getSnapSyncMinPivotBlock() shouldBe Some(StrandedBest + 1)
  }

  it should "keep applying a persisted pivot floor after a restart, until SNAP holds a pivot above it" taggedAs (
    UnitTest,
    SyncTest
  ) in withRecoveryTestSetup() { testSetup =>
    import testSetup.*
    // The node restarted after the first start recorded the floor and deleted fast sync's record, but before SNAP
    // chose a pivot. Only the persisted floor now says the best block has no state.
    blockchainWriter.storeBlockHeader(baseBlockHeader.copy(number = BlockNumber(StrandedBest))).commit()
    storagesInstance.storages.appStateStorage
      .putBestBlockNumber(StrandedBest)
      .and(storagesInstance.storages.appStateStorage.putSnapSyncMinPivotBlock(StrandedBest + 1))
      .commit()
    answerPeerPollsWith(snapPeerAt(StrandedBest + 10))

    syncController ! SyncController.WrappedSyncProtocol(SyncProtocol.Start)

    eventually {
      someTimePasses()
      assert(childNamed(testSetup, "pivot-header-bootstrap"))
    }
    storagesInstance.storages.appStateStorage.getSnapSyncBootstrapTarget() shouldBe Some(StrandedBest + 1)
    assert(!childNamed(testSetup, "regular-sync"))
  }

  it should "clear the pivot floor when SNAP finalizes" taggedAs (UnitTest, SyncTest) in withRecoveryTestSetup() {
    testSetup =>
      import testSetup.*
      val appState = storagesInstance.storages.appStateStorage
      blockchainWriter.storeBlockHeader(baseBlockHeader.copy(number = BlockNumber(StrandedBest))).commit()
      appState.putBestBlockNumber(StrandedBest).and(appState.putSnapSyncMinPivotBlock(StrandedBest + 1)).commit()

      syncController ! SyncController.WrappedSyncProtocol(SyncProtocol.Start)
      eventually(assert(childNamed(testSetup, "snap-sync")))
      appState.getSnapSyncMinPivotBlock() shouldBe Some(StrandedBest + 1)

      syncController ! SyncController.WrappedExternal(SNAPSyncController.SnapSyncFinalized(StrandedBest + 1))

      appState.getSnapSyncMinPivotBlock() shouldBe None
  }

  it should "not re-apply the floor when the node restarts mid-heal, after SNAP took over" taggedAs (
    UnitTest,
    SyncTest
  ) in withRecoveryTestSetup() { testSetup =>
    import testSetup.*
    val appState = storagesInstance.storages.appStateStorage
    seedStrandedFastSync(testSetup)
    answerPeerPollsWith(snapPeerAt(StrandedBest + 10))

    // First start: SNAP takes the stranded node over with a pivot above its best block.
    syncController ! SyncController.WrappedSyncProtocol(SyncProtocol.Start)
    eventually {
      someTimePasses()
      assert(childNamed(testSetup, "pivot-header-bootstrap"))
    }
    stopNode(syncController)

    // SNAP then committed that pivot, downloaded every range and started healing. A healing pivot roll moved best
    // without saving the pivot (SNAP saves it only when healing succeeds).
    seedMidHeal(testSetup, pivot = StrandedBest + 1, best = StrandedBest + 100)

    val restarted = restartedSyncController()
    restarted ! SyncController.WrappedSyncProtocol(SyncProtocol.Start)
    awaitSnapStarted(restarted)

    // A floor above SNAP's saved pivot would trip belowEscalationHint and wipe these, restarting SNAP from scratch.
    appState.isSnapSyncAccountsComplete() shouldBe true
    appState.isSnapSyncStorageComplete() shouldBe true
    appState.isSnapSyncBytecodeComplete() shouldBe true
    // SNAP's saved pivot reached the floor, so the floor was spent: cleared, not applied again.
    appState.getSnapSyncMinPivotBlock() shouldBe None
  }

  it should "treat the pivot floor as spent once SNAP's accounts are complete, even below it" taggedAs (
    UnitTest,
    SyncTest
  ) in withRecoveryTestSetup() { testSetup =>
    import testSetup.*
    val appState = storagesInstance.storages.appStateStorage
    // The floor was SNAP's first pivot. A failed pivot refresh backtracked by the pivot offset and SNAP committed, and
    // finished its accounts at, a pivot below the floor. Applying the floor again would trip belowEscalationHint.
    val floor = StrandedBest + 1
    val backtracked = floor - SNAPSyncConfig().pivotBlockOffset
    appState.putSnapSyncMinPivotBlock(floor).commit()
    seedMidHeal(testSetup, pivot = backtracked, best = backtracked)

    syncController ! SyncController.WrappedSyncProtocol(SyncProtocol.Start)
    awaitSnapStarted(syncController)

    appState.isSnapSyncAccountsComplete() shouldBe true
    appState.isSnapSyncStorageComplete() shouldBe true
    appState.isSnapSyncBytecodeComplete() shouldBe true
    appState.getSnapSyncMinPivotBlock() shouldBe None
  }

  it should "leave a heal alone on a database that once fell back to fast sync" taggedAs (
    UnitTest,
    SyncTest
  ) in withRecoveryTestSetup() { testSetup =>
    import testSetup.*
    val appState = storagesInstance.storages.appStateStorage
    // SNAP fell back to fast sync once (fast sync wrote its record), later resumed and is now healing: best was moved
    // by a healing pivot roll, not by fast sync.
    seedStrandedFastSync(testSetup)
    seedMidHeal(testSetup, pivot = StrandedBest + 1, best = StrandedBest + 100)

    syncController ! SyncController.WrappedSyncProtocol(SyncProtocol.Start)
    awaitSnapStarted(syncController)

    appState.isSnapSyncAccountsComplete() shouldBe true
    appState.isSnapSyncStorageComplete() shouldBe true
    appState.isSnapSyncBytecodeComplete() shouldBe true
    storagesInstance.storages.fastSyncStateStorage.hasSyncState shouldBe false
    appState.getSnapSyncMinPivotBlock() shouldBe None
  }

  it should "leave SNAP's resume alone once SNAP has taken over the best block of an upgraded node" taggedAs (
    UnitTest,
    SyncTest
  ) in withRecoveryTestSetup() { testSetup =>
    import testSetup.*
    seedStrandedFastSync(testSetup)
    // SNAP already picked the stranded block as its pivot and finished its accounts there (updateBestBlockForPivot sets
    // best = pivot). A floor of best + 1 would reject that pivot and discard the account download.
    storagesInstance.storages.appStateStorage
      .putSnapSyncPivotBlock(StrandedBest)
      .and(storagesInstance.storages.appStateStorage.putSnapSyncStateRoot(ByteString(Array.fill[Byte](32)(0x66))))
      .and(storagesInstance.storages.appStateStorage.putSnapSyncAccountsComplete(true))
      .commit()

    syncController ! SyncController.WrappedSyncProtocol(SyncProtocol.Start)
    awaitSnapStarted(syncController)

    storagesInstance.storages.appStateStorage.isSnapSyncAccountsComplete() shouldBe true
  }

  it should "start regular sync on a node upgraded mid-fast-sync when do-snap-sync is off" taggedAs (
    UnitTest,
    SyncTest
  ) in withTestSetup() { testSetup =>
    import testSetup.*
    seedStrandedFastSync(testSetup)

    val errors = syncControllerErrorsDuring(syncController ! SyncController.WrappedSyncProtocol(SyncProtocol.Start))

    // The ERROR has to say what the node will do: regular sync tries the missing state node by node and, when peers
    // cannot serve it, RegularSyncStuck re-runs SNAP whatever do-snap-sync says.
    errors.exists(_.contains("re-syncs with SNAP from a newer pivot, even with do-snap-sync off")) shouldBe true

    eventually {
      someTimePasses()
      assert(childNamed(testSetup, "regular-sync"))
    }
    assert(!childNamed(testSetup, "fast-sync"))
    // The floor is kept, so switching do-snap-sync on later still starts SNAP above the stateless block.
    storagesInstance.storages.fastSyncStateStorage.hasSyncState shouldBe false
    storagesInstance.storages.appStateStorage.getSnapSyncMinPivotBlock() shouldBe Some(StrandedBest + 1)
  }

  it should "log the stranded-node ERROR once per floor, not on every restart" taggedAs (
    UnitTest,
    SyncTest
  ) in withTestSetup() { testSetup =>
    import testSetup.*
    seedStrandedFastSync(testSetup)
    val stranded = "has no state behind it, and do-snap-sync is off"

    val first = syncControllerErrorsDuring(syncController ! SyncController.WrappedSyncProtocol(SyncProtocol.Start))
    first.exists(_.contains(stranded)) shouldBe true
    stopNode(syncController)

    val restarted = restartedSyncController()
    val second = syncControllerErrorsDuring(restarted ! SyncController.WrappedSyncProtocol(SyncProtocol.Start))
    second.exists(_.contains(stranded)) shouldBe false
  }

  class TestSetup(
      _validators: Validators = new Mocks.MockValidatorsAlwaysSucceed
  ) extends EphemBlockchainTestSetup
      with TestSyncPeers
      with TestSyncConfig:

    // + cake overrides
    implicit override lazy val system: ActorSystem =
      ActorSystem("SyncControllerSpec_System", ConfigFactory.load("explicit-scheduler"))

    override lazy val vm: VMImpl = new VMImpl

    override lazy val validators: Validators = _validators

    override lazy val mining: TestMining = buildTestMining().withValidators(validators)

    // + cake overrides

    val networkPeerManager: TestProbe = TestProbe()
    val peerMessageBus: TypedActorRef[PeerEventBusActor.Command] =
      system.spawn(PeerEventBusActor.behavior(), "peer-event-bus")
    val pendingTransactionsManager: TestProbe = TestProbe()

    val ommersPool: TestProbe = TestProbe()

    val blacklist: CacheBasedBlacklist = CacheBasedBlacklist.empty(100)

    override def defaultSyncConfig: SyncConfig = super.defaultSyncConfig.copy(
      branchResolutionRequestSize = 30,
      checkForNewBlockInterval = 1.second,
      blockHeadersPerRequest = 10,
      blockBodiesPerRequest = 10,
      peersScanInterval = 1.second,
      redownloadMissingStateNodes = false,
      blacklistDuration = 1.second,
      peerResponseTimeout = 2.seconds
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
          storagesInstance.storages.evmCodeStorage,
          storagesInstance.storages.stateStorage,
          storagesInstance.storages.flatSlotStorage,
          storagesInstance.storages.fastSyncStateStorage,
          consensusAdapter,
          validators,
          peerMessageBus,
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

    /** A second SyncController on the same storages and peers: the node after a restart. */
    def restartedSyncController(): TestActorRef[Nothing] = TestActorRef(
      org.apache.pekko.actor.typed.scaladsl.adapter.PropsAdapter(
        SyncController(
          blockchain,
          blockchainReader,
          blockchainWriter,
          storagesInstance.storages.appStateStorage,
          storagesInstance.storages.evmCodeStorage,
          storagesInstance.storages.stateStorage,
          storagesInstance.storages.flatSlotStorage,
          storagesInstance.storages.fastSyncStateStorage,
          consensusAdapter,
          validators,
          peerMessageBus,
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

    /** Stop `controller` and its children, as a node shutdown would. */
    def stopNode(controller: TestActorRef[Nothing]): Unit =
      val watcher = TestProbe()
      watcher.watch(controller)
      system.stop(controller)
      watcher.expectTerminated(controller)

    val baseBlockHeader = Fixtures.Blocks.Genesis.header

    blockchainWriter.storeChainWeight(baseBlockHeader.parentHash, ChainWeight.zero).commit()

    private def testScheduler = system.scheduler.asInstanceOf[ExplicitlyTriggeredScheduler]

    def someTimePasses(): Unit =
      testScheduler.timePasses(3000.millis)

    /** Answer every peer poll SyncController's children make with `peers`. */
    def answerPeerPollsWith(peers: Map[Peer, PeerInfo]): Unit =
      networkPeerManager.setAutoPilot(
        new AutoPilot:
          override def run(sender: ActorRef, msg: Any): AutoPilot =
            msg.asMatchable match
              case NetworkPeerManagerActor.GetHandshakedPeersCmd(replyTo) => replyTo ! HandshakedPeers(peers)
              case _                                                      => ()
            this
      )

    def snapPeerAt(height: BigInt): Map[Peer, PeerInfo] =
      val status = peer1Status.copy(capability = Capability.ETH68, supportsSnap = true)
      Map(
        peer1 -> PeerInfo(
          status,
          forkAccepted = true,
          chainWeight = status.chainWeight,
          maxBlockNumber = height,
          bestBlockHash = status.bestHash
        )
      )

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
    override def defaultSyncConfig: SyncConfig = super.defaultSyncConfig.copy(doSnapSync = false)

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
          storagesInstance.storages.evmCodeStorage,
          storagesInstance.storages.stateStorage,
          storagesInstance.storages.flatSlotStorage,
          storagesInstance.storages.fastSyncStateStorage,
          consensusAdapter,
          validators,
          peerMessageBus,
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
      override def defaultSyncConfig: SyncConfig = super.defaultSyncConfig.copy(doSnapSync = true)
    try test(testSetup)
    finally testSetup.cleanup()
