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
import com.chipprbots.ethereum.utils.Config.SyncConfig

/** Unit tests for SyncController.start() startup decision tree (SyncController.scala lines 802-910).
  *
  * Each test exercises one leaf of the 6-case match on (isSnapSyncDone, isFastSyncDone, doSnapSync, doFastSync).
  * Uses TestActorRef white-box injection (CallingThreadDispatcher — synchronous). Child names are asserted
  * immediately after ctrl.receive(Start) without eventually-polling.
  *
  * Coverage matrix:
  *   M-1: (false, _, true, _)     → startSnapSync()   — fresh start, SNAP mode
  *   M-2: (_, false, false, true) → startFastSync()   — fresh start, fast mode
  *   M-3: (_, true, false, true)  → startRegularSync()— fast done, fast mode
  *   M-4: (_, true, false, false) → startRegularSync()— fast done, regular mode
  *   M-5: (_, false, false, false), no fastSyncState → startRegularSync()
  *   M-6: (_, false, false, false), fastSyncState present → startFastSync()
  *   M-8: escape-hatch restart path — covered by SyncControllerBounceSpec B-6
  *   M-9: (false, _, true, _), mid-SNAP crash (phase flags set, snapSyncDone not set) → startSnapSync()
  */
class SyncControllerStartupMatrixSpec extends AnyFlatSpec with Matchers with MockFactory {

  // ─── Test harness ────────────────────────────────────────────────────────

  class MatrixTestSetup(doSnapSync: Boolean = false, doFastSync: Boolean = false)
      extends EphemBlockchainTestSetup
      with TestSyncPeers
      with TestSyncConfig {

    implicit override lazy val system: ActorSystem =
      ActorSystem("SyncControllerStartupMatrixSpec_System")

    override lazy val vm: VMImpl             = new VMImpl
    override lazy val validators: Validators = new Mocks.MockValidatorsAlwaysSucceed
    override lazy val mining: TestMining     = buildTestMining().withValidators(validators)

    val networkPeerManagerProbe: TestProbe      = TestProbe()
    val peerEventBus: TestProbe                 = TestProbe()
    val pendingTransactionsManager: TestProbe   = TestProbe()
    val ommersPool: TestProbe                   = TestProbe()
    val blacklist: CacheBasedBlacklist          = CacheBasedBlacklist.empty(100)

    lazy val appState   = storagesInstance.storages.appStateStorage
    lazy val fastSyncSt = storagesInstance.storages.fastSyncStateStorage

    override def defaultSyncConfig: SyncConfig = super.defaultSyncConfig.copy(
      doSnapSync = doSnapSync,
      doFastSync = doFastSync
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

    def cleanup(): Unit = Await.result(system.terminate(), 10.seconds)
  }

  def withSetup(doSnapSync: Boolean = false, doFastSync: Boolean = false)(
      test: MatrixTestSetup => Any
  ): Unit = {
    val s = new MatrixTestSetup(doSnapSync, doFastSync)
    try test(s)
    finally s.cleanup()
  }

  // ─── M-1: fresh start, SNAP mode → snap-sync-* ───────────────────────────

  "SyncController.start() startup matrix" should
    "start snap sync when doSnapSync=true and no prior sync state (M-1)" taggedAs UnitTest in
    withSetup(doSnapSync = true) { s =>
      import s._
      ctrl.receive(SyncProtocol.Start)
      ctrl.children.exists(_.path.name.startsWith("snap-sync")) shouldBe true
      ctrl.children.exists(_.path.name.startsWith("fast-sync")) shouldBe false
      ctrl.children.exists(_.path.name.startsWith("regular-sync")) shouldBe false
    }

  // ─── M-2: fresh start, fast mode → fast-sync-* ───────────────────────────

  it should "start fast sync when doFastSync=true and no prior sync state (M-2)" taggedAs UnitTest in
    withSetup(doFastSync = true) { s =>
      import s._
      ctrl.receive(SyncProtocol.Start)
      ctrl.children.exists(_.path.name.startsWith("fast-sync")) shouldBe true
      ctrl.children.exists(_.path.name.startsWith("snap-sync")) shouldBe false
      ctrl.children.exists(_.path.name.startsWith("regular-sync")) shouldBe false
    }

  // ─── M-3: fast done, fast mode → regular-sync-* ──────────────────────────

  it should
    "start regular sync when doFastSync=true and fastSyncDone=true (M-3)" taggedAs UnitTest in
    withSetup(doFastSync = true) { s =>
      import s._
      appState.fastSyncDone().commit()
      ctrl.receive(SyncProtocol.Start)
      ctrl.children.exists(_.path.name.startsWith("regular-sync")) shouldBe true
      ctrl.children.exists(_.path.name.startsWith("fast-sync")) shouldBe false
    }

  // ─── M-4: fast done, neither mode → regular-sync-* ───────────────────────

  it should
    "start regular sync when fastSyncDone=true and neither doFastSync nor doSnapSync (M-4)" taggedAs UnitTest in
    withSetup() { s =>
      import s._
      appState.fastSyncDone().commit()
      ctrl.receive(SyncProtocol.Start)
      ctrl.children.exists(_.path.name.startsWith("regular-sync")) shouldBe true
      ctrl.children.exists(_.path.name.startsWith("fast-sync")) shouldBe false
    }

  // ─── M-5: nothing, neither mode, no orphaned fast state → regular-sync-* ─

  it should
    "start regular sync when neither mode requested, no prior sync state, no fastSyncState (M-5)" taggedAs UnitTest in
    withSetup() { s =>
      import s._
      ctrl.receive(SyncProtocol.Start)
      ctrl.children.exists(_.path.name.startsWith("regular-sync")) shouldBe true
      ctrl.children.exists(_.path.name.startsWith("fast-sync")) shouldBe false
    }

  // ─── M-6: orphaned fast sync state → fast-sync-* ─────────────────────────
  // SyncController.scala:906 — doFastSync=false but getSyncState().isDefined → startFastSync()

  it should
    "resume fast sync when doFastSync=false but an orphaned fastSyncState is present (M-6)" taggedAs UnitTest in
    withSetup() { s =>
      import s._
      fastSyncSt.putSyncState(FastSync.SyncState(pivotBlock = Fixtures.Blocks.Genesis.header))
      ctrl.receive(SyncProtocol.Start)
      ctrl.children.exists(_.path.name.startsWith("fast-sync")) shouldBe true
      ctrl.children.exists(_.path.name.startsWith("regular-sync")) shouldBe false
    }

  // ─── M-9: mid-SNAP crash → snap-sync-* restarted ─────────────────────────
  // Intermediate phase-completion flags (AccountsComplete etc.) must NOT prevent restarting SNAP
  // when snapSyncDone is not set. Validates case (false, _, true, _) fires despite partial progress.

  it should
    "restart snap sync after mid-SNAP crash regardless of partial phase flags when snapSyncDone is not set (M-9)" taggedAs UnitTest in
    withSetup(doSnapSync = true) { s =>
      import s._
      // Simulate crash mid-SNAP: accounts phase completed but sync not finalised.
      appState.putSnapSyncAccountsComplete(true).commit()
      ctrl.receive(SyncProtocol.Start)
      // case (false, _, true, _) fires → startSnapSync()
      ctrl.children.exists(_.path.name.startsWith("snap-sync")) shouldBe true
      ctrl.children.exists(_.path.name.startsWith("regular-sync")) shouldBe false
    }
}
