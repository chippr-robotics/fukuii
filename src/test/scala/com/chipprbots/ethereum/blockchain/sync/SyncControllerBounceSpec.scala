package com.chipprbots.ethereum.blockchain.sync

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.Props
import org.apache.pekko.testkit.TestActorRef
import org.apache.pekko.testkit.TestProbe

import scala.concurrent.Await
import scala.concurrent.duration._

import org.scalamock.scalatest.MockFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.Mocks
import com.chipprbots.ethereum.consensus.mining.TestMining
import com.chipprbots.ethereum.consensus.validators.Validators
import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.ledger.VMImpl
import com.chipprbots.ethereum.testing.Tags._
import com.chipprbots.ethereum.blockchain.sync.fast.FastSync
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController
import com.chipprbots.ethereum.utils.Config.SyncConfig

/** Actor-level regression tests for the SNAP↔Fast sync bounce cycle and escape hatch.
  *
  * Uses TestActorRef[SyncController] for white-box behavior injection (same technique as
  * SyncControllerRecoverySpec). Tests cover:
  *   - B-1: FallbackToFastSync increments snapFastCycleCount and starts fast sync
  *   - B-2: FallbackToSnapSync increments snapFastCycleCount and starts snap sync
  *   - B-3: Escape hatch fires at threshold: snapSyncDone+fastSyncDone set, counter cleared,
  *           regular sync started, no fast-sync child spawned
  *   - B-4: Below threshold — no escape, counter incremented, fast-sync started
  *   - B-5: Escape hatch purges persisted fast sync state
  *   - B-6: Restart after escape-hatch state jumps directly to regular sync without recovery actors
  */
class SyncControllerBounceSpec extends AnyFlatSpec with Matchers with MockFactory {

  // ─── Test harness ────────────────────────────────────────────────────────

  class BounceTestSetup extends EphemBlockchainTestSetup with TestSyncPeers with TestSyncConfig {

    implicit override lazy val system: ActorSystem =
      ActorSystem("SyncControllerBounceSpec_System")

    override lazy val vm: VMImpl        = new VMImpl
    override lazy val validators: Validators = new Mocks.MockValidatorsAlwaysSucceed
    override lazy val mining: TestMining = buildTestMining().withValidators(validators)

    val networkPeerManagerProbe: TestProbe  = TestProbe()
    val peerEventBus: TestProbe             = TestProbe()
    val pendingTransactionsManager: TestProbe = TestProbe()
    val ommersPool: TestProbe               = TestProbe()
    val blacklist: CacheBasedBlacklist      = CacheBasedBlacklist.empty(100)

    lazy val appState    = storagesInstance.storages.appStateStorage
    lazy val fastSyncSt  = storagesInstance.storages.fastSyncStateStorage

    // doSnapSync=true so start() calls startSnapSync(). maxSnapFastCycleTransitions=3 (default).
    override def defaultSyncConfig: SyncConfig = super.defaultSyncConfig.copy(
      doSnapSync = true,
      doFastSync = false
    )

    lazy val ctrl: TestActorRef[SyncController] = TestActorRef(
      Props(
        new SyncController(
          blockchain,
          blockchainReader,
          blockchainWriter,
          storagesInstance.storages.appStateStorage,
          storagesInstance.storages.blockNumberMappingStorage,
          storagesInstance.storages.evmCodeStorage,
          storagesInstance.storages.stateStorage,
          storagesInstance.storages.nodeStorage,
          storagesInstance.storages.flatSlotStorage,
          storagesInstance.storages.flatAccountStorage,
          storagesInstance.storages.fastSyncStateStorage,
          consensusAdapter,
          validators,
          peerEventBus.ref,
          pendingTransactionsManager.ref,
          ommersPool.ref,
          networkPeerManagerProbe.ref,
          blacklist,
          syncConfig,
          this
        )
      )
    )

    blockchainWriter
      .storeChainWeight(Fixtures.Blocks.Genesis.header.parentHash, ChainWeight.zero)
      .commit()

    /** Inject controller into runningSnapSync with a fake snap-sync actor (no real SNAP logic). */
    def putIntoSnapSync(): TestProbe = {
      val fakeSnap = TestProbe()
      val ua = ctrl.underlyingActor
      ua.context.become(ua.runningSnapSync(fakeSnap.ref))
      fakeSnap
    }

    /** Inject controller into runningFastSync with a fake fast-sync actor (no real fast logic). */
    def putIntoFastSync(): TestProbe = {
      val fakeFast = TestProbe()
      val ua = ctrl.underlyingActor
      ua.context.become(ua.runningFastSync(fakeFast.ref))
      fakeFast
    }

    def cleanup(): Unit = Await.result(system.terminate(), 10.seconds)
  }

  def withSetup(test: BounceTestSetup => Any): Unit = {
    val s = new BounceTestSetup
    try test(s)
    finally s.cleanup()
  }

  // ─── B-1: FallbackToFastSync increments counter ──────────────────────────

  "SyncController snap↔fast bounce" should
    "increment snapFastCycleCount and start fast sync on FallbackToFastSync (B-1)" taggedAs UnitTest in
    withSetup { s =>
      import s._

      putIntoSnapSync()

      ctrl.receive(SNAPSyncController.FallbackToFastSync)

      appState.getSnapFastCycleCount() shouldBe 1
      ctrl.children.exists(_.path.name.startsWith("fast-sync")) shouldBe true
    }

  // ─── B-2: FallbackToSnapSync increments counter ──────────────────────────

  it should "increment snapFastCycleCount and start snap sync on FallbackToSnapSync (B-2)" taggedAs UnitTest in
    withSetup { s =>
      import s._

      putIntoFastSync()

      ctrl.receive(FastSync.FallbackToSnapSync)

      appState.getSnapFastCycleCount() shouldBe 1
      ctrl.children.exists(_.path.name.startsWith("snap-sync")) shouldBe true
    }

  // ─── B-3: Escape hatch fires at threshold ────────────────────────────────

  it should
    "fire escape hatch at maxSnapFastCycleTransitions: write snapSyncDone+fastSyncDone, clear counter, start regular sync (B-3)" taggedAs UnitTest in
    withSetup { s =>
      import s._

      // threshold=3 (defaultSyncConfig); seed at 2 so this bounce pushes count to 3 and fires escape
      appState.putSnapFastCycleCount(2).commit()
      putIntoSnapSync()

      ctrl.receive(SNAPSyncController.FallbackToFastSync)

      appState.isSnapSyncDone() shouldBe true
      appState.isFastSyncDone() shouldBe true
      appState.getSnapFastCycleCount() shouldBe 0
      ctrl.children.exists(_.path.name.startsWith("regular-sync")) shouldBe true
      ctrl.children.exists(_.path.name.startsWith("fast-sync")) shouldBe false
    }

  // ─── B-4: Below threshold — no escape ────────────────────────────────────

  it should "not fire escape hatch when cycle count remains below maxSnapFastCycleTransitions (B-4)" taggedAs UnitTest in
    withSetup { s =>
      import s._

      // count=1 → this bounce increments to 2, still below threshold=3
      appState.putSnapFastCycleCount(1).commit()
      putIntoSnapSync()

      ctrl.receive(SNAPSyncController.FallbackToFastSync)

      appState.getSnapFastCycleCount() shouldBe 2
      appState.isSnapSyncDone() shouldBe false
      ctrl.children.exists(_.path.name.startsWith("fast-sync")) shouldBe true
      ctrl.children.exists(_.path.name.startsWith("regular-sync")) shouldBe false
    }

  // ─── B-5: Escape hatch purges persisted fast sync state ──────────────────

  it should "purge persisted fast sync state when escape hatch fires (B-5)" taggedAs UnitTest in
    withSetup { s =>
      import s._

      fastSyncSt.putSyncState(
        FastSync.SyncState(pivotBlock = Fixtures.Blocks.Genesis.header)
      )
      fastSyncSt.getSyncState() shouldBe defined

      appState.putSnapFastCycleCount(2).commit()
      putIntoSnapSync()

      ctrl.receive(SNAPSyncController.FallbackToFastSync)

      fastSyncSt.getSyncState() shouldBe None
    }

  // ─── B-6: Restart after escape-hatch state → regular sync directly ───────
  //
  // After the escape hatch fires, the persisted state is:
  //   snapSyncDone=true, fastSyncDone=true, recovery flags NOT set, no stateRoot.
  // On the next restart (doSnapSync=true): case (true, _, true, _) → startRecovery →
  // getSnapSyncStateRoot() returns None → marks recovery done → startRegularSync().
  // No recovery actors should be spawned.

  it should
    "start regular sync immediately on restart after escape-hatch state without spawning recovery actors (B-6)" taggedAs UnitTest in
    withSetup { s =>
      import s._

      appState.snapSyncDone().commit()
      appState.fastSyncDone().commit()
      // bytecodeRecoveryDone and storageRecoveryDone intentionally NOT set
      // stateRoot intentionally NOT set

      ctrl.receive(SyncProtocol.Start)

      ctrl.children.exists(_.path.name.startsWith("regular-sync")) shouldBe true
      ctrl.children.exists(_.path.name.startsWith("bytecode-recovery")) shouldBe false
      ctrl.children.exists(_.path.name.startsWith("storage-recovery")) shouldBe false
    }
}
