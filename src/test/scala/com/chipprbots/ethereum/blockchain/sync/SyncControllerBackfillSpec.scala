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

/** Actor-level regression tests for SyncController.maybeStartBackfillResume() (SyncController.scala:1040).
  *
  * `maybeStartBackfillResume()` is guarded by `needsBackfillResume()` (AppStateStorage.scala:387):
  *   - `isSnapSyncDone()` must be true
  *   - `getBackfillTarget() > 0`
  *   - at least one cursor (header / body / receipt) < target
  *
  * Tests cover:
  *   R-1: Header cursor lags → backfill-resumer-* spawned
  *   R-2: Receipt cursor lags → backfill-resumer-* spawned
  *   R-3: All cursors at target → no backfill-resumer spawned
  *   R-4: snapSyncDone=false → needsBackfillResume()=false → no backfill-resumer
  *   R-5: clearBackfillCursors() clears target → no re-spawn on next restart
  */
class SyncControllerBackfillSpec extends AnyFlatSpec with Matchers with MockFactory {

  // ─── Test harness ────────────────────────────────────────────────────────
  // Config: neither doSnapSync nor doFastSync (regular mode).
  // Startup → case (_, false, false, false), no fastSyncState → startRegularSync() → maybeStartBackfillResume().

  class BackfillTestSetup extends EphemBlockchainTestSetup with TestSyncPeers with TestSyncConfig {

    implicit override lazy val system: ActorSystem =
      ActorSystem("SyncControllerBackfillSpec_System")

    override lazy val vm: VMImpl             = new VMImpl
    override lazy val validators: Validators = new Mocks.MockValidatorsAlwaysSucceed
    override lazy val mining: TestMining     = buildTestMining().withValidators(validators)

    val networkPeerManagerProbe: TestProbe    = TestProbe()
    val peerEventBus: TestProbe               = TestProbe()
    val pendingTransactionsManager: TestProbe = TestProbe()
    val ommersPool: TestProbe                 = TestProbe()
    val blacklist: CacheBasedBlacklist        = CacheBasedBlacklist.empty(100)

    lazy val appState = storagesInstance.storages.appStateStorage

    // doSnapSync=false, doFastSync=false → startup falls through to case (_, false, false, false)
    // → no fastSyncState → startRegularSync() → maybeStartBackfillResume()

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

    def cleanup(): Unit = Await.result(system.terminate(), 10.seconds)
  }

  def withSetup(test: BackfillTestSetup => Any): Unit = {
    val s = new BackfillTestSetup
    try test(s)
    finally s.cleanup()
  }

  // ─── R-1: Header cursor lags → backfill-resumer spawned ──────────────────

  "SyncController backfill resume on restart" should
    "spawn backfill-resumer when header cursor lags behind BackfillTarget (R-1)" taggedAs UnitTest in
    withSetup { s =>
      import s._
      // snapSyncDone required; BackfillTarget=1000; header cursor at 500 (lagging)
      appState.snapSyncDone().commit()
      appState.putBackfillTarget(1000).commit()
      appState.putBackfillBestHeader(500).commit()
      appState.putBackfillBestBody(1000).commit()
      appState.putBackfillBestReceipt(1000).commit()

      ctrl.receive(SyncProtocol.Start)

      ctrl.children.exists(_.path.name.startsWith("regular-sync")) shouldBe true
      ctrl.children.exists(_.path.name.startsWith("backfill-resumer")) shouldBe true
    }

  // ─── R-2: Receipt cursor lags → backfill-resumer spawned ─────────────────

  it should "spawn backfill-resumer when receipt cursor lags behind BackfillTarget (R-2)" taggedAs UnitTest in
    withSetup { s =>
      import s._
      appState.snapSyncDone().commit()
      appState.putBackfillTarget(1000).commit()
      appState.putBackfillBestHeader(1000).commit()
      appState.putBackfillBestBody(1000).commit()
      appState.putBackfillBestReceipt(500).commit()

      ctrl.receive(SyncProtocol.Start)

      ctrl.children.exists(_.path.name.startsWith("regular-sync")) shouldBe true
      ctrl.children.exists(_.path.name.startsWith("backfill-resumer")) shouldBe true
    }

  // ─── R-3: All cursors at target → no backfill actor ──────────────────────

  it should "not spawn backfill-resumer when all cursors are already at BackfillTarget (R-3)" taggedAs UnitTest in
    withSetup { s =>
      import s._
      appState.snapSyncDone().commit()
      appState.putBackfillTarget(1000).commit()
      appState.putBackfillBestHeader(1000).commit()
      appState.putBackfillBestBody(1000).commit()
      appState.putBackfillBestReceipt(1000).commit()

      ctrl.receive(SyncProtocol.Start)

      ctrl.children.exists(_.path.name.startsWith("regular-sync")) shouldBe true
      ctrl.children.exists(_.path.name.startsWith("backfill-resumer")) shouldBe false
    }

  // ─── R-4: snapSyncDone=false → needsBackfillResume()=false ───────────────

  it should
    "not spawn backfill-resumer when snapSyncDone is false even if cursors lag (R-4)" taggedAs UnitTest in
    withSetup { s =>
      import s._
      // snapSyncDone NOT set — needsBackfillResume() returns false immediately
      appState.putBackfillTarget(1000).commit()
      appState.putBackfillBestHeader(500).commit()
      appState.putBackfillBestBody(1000).commit()
      appState.putBackfillBestReceipt(1000).commit()

      ctrl.receive(SyncProtocol.Start)

      ctrl.children.exists(_.path.name.startsWith("backfill-resumer")) shouldBe false
    }

  // ─── R-5: clearBackfillCursors() prevents re-spawn on next restart ────────

  it should
    "not spawn backfill-resumer after clearBackfillCursors() clears the target (R-5)" taggedAs UnitTest in
    withSetup { s =>
      import s._
      // Simulate completed backfill: cursors were at target, ChainDownloader called clearBackfillCursors().
      appState.snapSyncDone().commit()
      appState.putBackfillTarget(1000).commit()
      appState.putBackfillBestHeader(500).commit()
      appState.clearBackfillCursors().commit()
      // After clear, target=0 and cursors removed → needsBackfillResume() returns false

      ctrl.receive(SyncProtocol.Start)

      ctrl.children.exists(_.path.name.startsWith("backfill-resumer")) shouldBe false
    }
}
