package com.chipprbots.ethereum.blockchain.sync.snap

import org.apache.pekko.actor.testkit.typed.scaladsl.FishingOutcomes
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.util.ByteString

import scala.concurrent.duration.*
import scala.util.Using

import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.snap.actors.AccountRangeCoordinator
import com.chipprbots.ethereum.blockchain.sync.snap.actors.ByteCodeCoordinator
import com.chipprbots.ethereum.blockchain.sync.snap.actors.StorageRangeCoordinator
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.testing.Tags.*

/** Spec 016 lift slice before M11b (T053a): characterization of the M11 arms of `commonSyncingArms` that the slice
  * lifts into class members, as they behave today. It adds only what the existing suites leave unasserted:
  *   - `CheckSnapCapability` with a snap peer present: the account phase is launched at the configured concurrency on
  *     the current root (here from `ByteCodeAndStorageSync`, the one phase where the launch does not collide with a
  *     running account child);
  *   - the three peer-availability ticks: each offers the snap peer to its own coordinator;
  *   - the progress reports: the counters, estimates and storage-contract figures they feed into the progress monitor,
  *     the trie-finalizing flag, the persisted finalized root, and the restart on a finalization failure;
  *   - the two backpressure signals: forwarded to the account coordinator;
  *   - `IncrementalContractData` with both coordinators: the bytecode tasks (with the `replayed` flag as `skipPresent`)
  *     and the storage tasks are forwarded, the running bytecode estimate accumulates, and no credit is released;
  *   - `AccountRangeSyncComplete`: the persisted flag, the retired resume checkpoint, the file-info asks, the NoMore
  *     signals and budget changes in their order, the phase change, and the duplicate being ignored;
  *   - the phase-completion arms in `AccountRangeSync`: bytecode completion persists and boosts the storage budget
  *     (only while storage is incomplete); storage completion persists; a force-complete outside the two late phases is
  *     ignored; and a second completion of a phase falls through to the catch-all.
  *
  * Already pinned elsewhere and not repeated: the response routing of all four response types
  * (`SNAPFanOutCharacterizationSpec`, T026), the readiness-probe branch of `AccountRangeResponse`
  * (`SNAPPivotRefreshArmsCharacterizationSpec`, `SNAPPivotRefreshCharacterizationSpec`), `CheckSnapCapability` going
  * dormant without a snap peer (`SNAPDormantRecoverySpec`), the `IncrementalContractData` credit release
  * (`SNAPBudgetWatchdogPinSpec`), `StorageRangeSyncForceCompleted` in `ByteCodeAndStorageSync`
  * (`SNAPSyncControllerPinSpec`, `SNAPBudgetWatchdogPinSpec`), the stub-level tick and launch bodies
  * (`DownloadSupervisorSpec`), and the arm order for every command and phase (`SNAPSyncingDispatchTableSpec`, T027).
  *
  * Not pinned (no deterministic observation point): the stagnation clocks the progress arms write
  * (`lastAccountProgressMs`, `lastBytecodeProgressMs`, `lastStorageProgressMs`, `storageStagnationRefreshAttempted`;
  * wall-clock values read only by the stagnation tick), the `consecutivePivotRefreshes` and critical-failure resets of
  * `ProgressAccountsSynced` (observable only past the wall-clock `MinPivotRestartInterval` debounce), and the
  * `TrieNodesResponse` drop in `StateValidation`.
  */
class SNAPDownloadArmsCharacterizationSpec
    extends ScalaTestWithActorTestKit(SnapControllerFixture.config)
    with AnyFlatSpecLike
    with Matchers:
  import SnapControllerFixture.*

  private val Offset = SNAPSyncConfig().pivotBlockOffset // 64

  private def progress(snap: ActorRef[SNAPSyncController.Command]): SyncProgress =
    val probe = testKit.createTestProbe[SyncProgress]()
    snap ! SNAPSyncController.GetProgress(probe.ref)
    probe.receiveMessage(10.seconds)

  private def hashes(n: Int, tag: Int = 0): Seq[ByteString] =
    (1 to n).map(i => ByteString(Array.fill(32)((i + tag).toByte)))

  private def tasks(n: Int): Seq[StorageTask] =
    (1 to n).map(i =>
      StorageTask.createStorageTask(ByteString(Array.fill(32)(i.toByte)), ByteString(Array.fill(32)(9.toByte)))
    )

  /** The only peer of a genesis account-sync entry (set by `enterGenesisAccountSync`). */
  private def onlyPeer(f: SnapControllerFixture): Peer = f.peers.get().keys.head

  /** Drain what the three download children received so far. */
  private def drainDownloadChildren(f: SnapControllerFixture, snap: ActorRef[SNAPSyncController.Command]): Unit =
    val id = f.sendFence(snap)
    f.accountBefore(id)
    f.storageBefore(id)
    f.byteCodeBefore(id)
    ()

  // ── CheckSnapCapability ───────────────────────────────────────────────────────────────────────

  "SNAPSyncController's CheckSnapCapability arm" should
    "launch the account phase at the configured concurrency on the current root once a snap peer is present" taggedAs UnitTest in {
      val f = new SnapControllerFixture(testKit)
      val snap = f.enterStorageResume() // no peer at entry, no account child
      f.pollWith(snap, f.peersAt(height = (Pivot + 100).toInt))
      Using.resource(new SnapLogCapture) { log =>
        snap ! SNAPSyncController.CheckSnapCapability
        val account = f.expectSpawn[ChildSpawn.AccountRange]
        account.stateRoot shouldBe Root
        account.concurrency shouldBe SNAPSyncConfig().accountConcurrency.max(1)
        f.awaitProcessed(snap)
        log.contains(
          s"Found 1 snap-capable peer(s) during grace period, starting account range sync (concurrency=" +
            s"${SNAPSyncConfig().accountConcurrency.max(1)}, peers=1)"
        ) shouldBe true
        log.contains("Entering dormant mode") shouldBe false
      }
      f.fish[AccountRangeCoordinator.Command, AccountRangeCoordinator.StartAccountRangeSync](f.accountInbox) shouldBe
        AccountRangeCoordinator.StartAccountRangeSync(Root)
    }

  // ── Peer-availability ticks ───────────────────────────────────────────────────────────────────

  "SNAPSyncController's peer-availability ticks" should
    "offer the snap peer to the account, bytecode and storage coordinators, each on its own tick" taggedAs UnitTest in {
      val f = new SnapControllerFixture(testKit)
      val snap = f.enterGenesisAccountSync()
      val peer = onlyPeer(f)
      drainDownloadChildren(f, snap)

      snap ! SNAPSyncController.RequestAccountRanges
      val afterAccountTick = f.sendFence(snap)
      f.accountBefore(afterAccountTick) shouldBe Seq(AccountRangeCoordinator.PeerAvailable(peer))
      f.storageBefore(afterAccountTick) shouldBe empty
      f.byteCodeBefore(afterAccountTick) shouldBe empty

      snap ! SNAPSyncController.RequestByteCodes
      val afterByteCodeTick = f.sendFence(snap)
      f.accountBefore(afterByteCodeTick) shouldBe empty
      f.storageBefore(afterByteCodeTick) shouldBe empty
      f.byteCodeBefore(afterByteCodeTick) shouldBe Seq(ByteCodeCoordinator.ByteCodePeerAvailable(peer))

      snap ! SNAPSyncController.RequestStorageRanges
      val afterStorageTick = f.sendFence(snap)
      f.accountBefore(afterStorageTick) shouldBe empty
      f.storageBefore(afterStorageTick) shouldBe Seq(StorageRangeCoordinator.StoragePeerAvailable(peer))
      f.byteCodeBefore(afterStorageTick) shouldBe empty
    }

  // ── Progress reports ──────────────────────────────────────────────────────────────────────────

  "SNAPSyncController's progress arms" should
    "feed the counters, the estimates and the storage-contract figures into the progress monitor" taggedAs UnitTest in {
      val f = new SnapControllerFixture(testKit)
      val snap = f.enterGenesisAccountSync()
      val before = progress(snap)

      snap ! SNAPSyncController.ProgressAccountsSynced(7L)
      snap ! SNAPSyncController.ProgressAccountsSynced(0L)
      snap ! SNAPSyncController.ProgressBytecodesDownloaded(3L)
      snap ! SNAPSyncController.ProgressStorageSlotsSynced(11L)
      snap ! SNAPSyncController.ProgressNodesHealed(4L)
      snap ! SNAPSyncController.ProgressAccountEstimate(1_000L)
      snap ! SNAPSyncController.ProgressStorageContracts(2, 8)
      val after = progress(snap)

      after.accountsSynced shouldBe before.accountsSynced + 7L
      after.bytecodesDownloaded shouldBe before.bytecodesDownloaded + 3L
      after.storageSlotsSynced shouldBe before.storageSlotsSynced + 11L
      after.nodesHealed shouldBe before.nodesHealed + 4L
      after.estimatedTotalAccounts shouldBe 1_000L
      after.storageContractsCompleted shouldBe 2
      after.storageContractsTotal shouldBe 8
      // The slot estimate is extrapolated from the slots synced so far: slots / completed * total.
      after.estimatedTotalSlots shouldBe (after.storageSlotsSynced.toDouble / 2 * 8).toLong
      after.phase shouldBe SNAPSyncController.SyncPhase.AccountRangeSync
    }

  it should "not extrapolate the slot estimate from a storage-contract report with no completed contract" taggedAs UnitTest in {
    val f = new SnapControllerFixture(testKit)
    val snap = f.enterGenesisAccountSync()
    snap ! SNAPSyncController.ProgressStorageSlotsSynced(50L)
    snap ! SNAPSyncController.ProgressStorageContracts(0, 8)
    val after = progress(snap)
    after.storageContractsCompleted shouldBe 0
    after.storageContractsTotal shouldBe 8
    after.estimatedTotalSlots shouldBe 0L
  }

  it should "set and clear the trie-finalizing flag, and persist the finalized account trie root" taggedAs UnitTest in {
    val f = new SnapControllerFixture(testKit)
    val snap = f.enterGenesisAccountSync()
    val finalized = root(0x77)

    snap ! SNAPSyncController.ProgressAccountsFinalizingTrie
    progress(snap).isFinalizingTrie shouldBe true

    Using.resource(new SnapLogCapture) { log =>
      snap ! SNAPSyncController.AccountTrieFinalized(finalized)
      f.awaitProcessed(snap)
      log.contains("Persisting finalized account trie root: 7777777777777777") shouldBe true
      f.appStateStorage.getSnapSyncFinalizedRoot() shouldBe Some(finalized)
      progress(snap).isFinalizingTrie shouldBe true // only ProgressAccountsTrieFinalized clears it

      snap ! SNAPSyncController.ProgressAccountsTrieFinalized
      progress(snap).isFinalizingTrie shouldBe false
      log.contains("Account range trie finalization complete") shouldBe true
    }
    f.stops.expectNoMessage(300.millis)
  }

  it should "restart SNAP sync on a fresh pivot when the account trie finalization fails" taggedAs UnitTest in {
    val f = new SnapControllerFixture(testKit)
    val entry = f.enterAccountSync(height = 1_000)
    val snap = entry.snap
    // The network head moves on, so the restart's fresh pivot is a new block whose header is fetched from the parent.
    f.pollWith(snap, f.peersAt(height = 1_200))
    val pivot = BigInt(1_200) - Offset
    snap ! SNAPSyncController.ProgressAccountsFinalizingTrie
    progress(snap).isFinalizingTrie shouldBe true

    Using.resource(new SnapLogCapture) { log =>
      snap ! SNAPSyncController.AccountTrieFinalizationFailed("lift-m11")
      f.parent.fishForMessage(10.seconds) {
        case SNAPSyncController.StartRegularSyncBootstrap(`pivot`) => FishingOutcomes.complete
        case _                                                     => FishingOutcomes.continueAndIgnore
      }
      f.awaitProcessed(snap)
      log.contains("Account trie finalization failed (lift-m11) — restarting SNAP sync with fresh pivot") shouldBe true
      log.contains("Restarting SNAP sync with a fresher pivot: account trie finalization failed: lift-m11") shouldBe
        true
    }
    f.stops.receiveMessages(3, 10.seconds).map(_.kind).toSet shouldBe
      Set(ChildStopped.Account, ChildStopped.ByteCode, ChildStopped.Storage)
    progress(snap).isFinalizingTrie shouldBe false
    f.appStateStorage.isSnapSyncAccountsComplete() shouldBe false
  }

  // ── Backpressure ──────────────────────────────────────────────────────────────────────────────

  "SNAPSyncController's backpressure arms" should
    "forward the storage and bytecode queue pressure to the account coordinator, in order" taggedAs UnitTest in {
      val f = new SnapControllerFixture(testKit)
      val snap = f.enterGenesisAccountSync()
      drainDownloadChildren(f, snap)

      snap ! SNAPSyncController.StorageBackpressureChanged(true)
      snap ! SNAPSyncController.ByteCodeBackpressureChanged(true)
      snap ! SNAPSyncController.StorageBackpressureChanged(false)
      snap ! SNAPSyncController.ByteCodeBackpressureChanged(false)
      val id = f.sendFence(snap)
      f.accountBefore(id) shouldBe Seq(
        AccountRangeCoordinator.StorageQueuePressure(true),
        AccountRangeCoordinator.ByteCodeQueuePressure(true),
        AccountRangeCoordinator.StorageQueuePressure(false),
        AccountRangeCoordinator.ByteCodeQueuePressure(false)
      )
      f.storageBefore(id) shouldBe empty
      f.byteCodeBefore(id) shouldBe empty
    }

  // ── IncrementalContractData ───────────────────────────────────────────────────────────────────

  "SNAPSyncController's IncrementalContractData arm" should
    "forward bytecode and storage tasks to their coordinators, accumulate the bytecode estimate, and release no " +
    "credit" taggedAs UnitTest in {
      val f = new SnapControllerFixture(testKit)
      val snap = f.enterGenesisAccountSync()
      drainDownloadChildren(f, snap)
      f.budget.reserve(2, 5)

      snap ! SNAPSyncController.IncrementalContractData(hashes(3), tasks(2), replayed = true)
      f.awaitProcessed(snap)
      progress(snap).estimatedTotalBytecodes shouldBe 3L

      snap ! SNAPSyncController.IncrementalContractData(hashes(2, tag = 0x40), Seq.empty)
      f.awaitProcessed(snap)
      progress(snap).estimatedTotalBytecodes shouldBe 5L

      val id = f.sendFence(snap)
      f.byteCodeBefore(id) shouldBe Seq(
        ByteCodeCoordinator.AddByteCodeTasks(hashes(3), skipPresent = true),
        ByteCodeCoordinator.AddByteCodeTasks(hashes(2, tag = 0x40), skipPresent = false)
      )
      f.storageBefore(id) shouldBe Seq(StorageRangeCoordinator.AddStorageTasks(tasks(2)))
      f.accountBefore(id) shouldBe empty
      f.budget.pendingStorageTasks shouldBe 2L
      f.budget.pendingByteCodeHashes shouldBe 5L
    }

  // ── AccountRangeSyncComplete ──────────────────────────────────────────────────────────────────

  "SNAPSyncController's AccountRangeSyncComplete arm" should
    "persist accounts-complete, retire the resume checkpoint, signal NoMore and rebalance the budgets, then move to " +
    "ByteCodeAndStorageSync" taggedAs UnitTest in {
      val f = new SnapControllerFixture(testKit)
      val snap = f.enterGenesisAccountSync()
      val peer = onlyPeer(f)
      drainDownloadChildren(f, snap)
      f.appStateStorage.putSnapAccountResumeCheckpoint("lift-m11").commit()
      f.appStateStorage.isSnapSyncAccountsComplete() shouldBe false

      Using.resource(new SnapLogCapture) { log =>
        snap ! SNAPSyncController.AccountRangeSyncComplete
        f.awaitProcessed(snap)
        log.contains("Account range sync complete. Signaling NoMore to bytecode/storage coordinators.") shouldBe true

        snap ! SNAPSyncController.AccountRangeSyncComplete
        f.awaitProcessed(snap)
        log.contains("Ignoring duplicate AccountRangeSyncComplete") shouldBe true
        log.count("Account range sync complete. Signaling NoMore") shouldBe 1
      }

      f.appStateStorage.isSnapSyncAccountsComplete() shouldBe true
      f.appStateStorage.getSnapAccountResumeCheckpoint() shouldBe None
      progress(snap).phase shouldBe SNAPSyncController.SyncPhase.ByteCodeAndStorageSync

      val id = f.sendFence(snap)
      f.byteCodeBefore(id) shouldBe Seq(
        ByteCodeCoordinator.NoMoreByteCodeTasks,
        ByteCodeCoordinator.UpdateMaxInFlightPerPeer(8),
        ByteCodeCoordinator.ByteCodePivotRefreshed,
        ByteCodeCoordinator.ByteCodePeerAvailable(peer)
      )
      f.storageBefore(id) shouldBe Seq(
        StorageRangeCoordinator.NoMoreStorageTasks,
        StorageRangeCoordinator.UpdateMaxInFlightPerPeer(3)
      )
      // The two file-info asks for crash recovery, in order (the stub child does not answer them).
      f.accountBefore(id).map {
        case _: AccountRangeCoordinator.AccountGetStorageFileInfo    => "storage-file-info"
        case _: AccountRangeCoordinator.AccountGetCodeHashesFileInfo => "code-hashes-file-info"
        case other                                                   => other.toString
      } shouldBe Seq("storage-file-info", "code-hashes-file-info")
      f.stops.expectNoMessage(300.millis)
    }

  it should "persist the storage and codeHashes handoff paths and counts once the coordinator answers the file-info " +
    "asks (#1603)" taggedAs UnitTest in {
      val f = new SnapControllerFixture(testKit)
      val snap = f.enterGenesisAccountSync()
      drainDownloadChildren(f, snap)
      val storageFile = java.nio.file.Paths.get("fukuii-contract-storage-1603.bin")
      val codeHashesFile = java.nio.file.Paths.get("fukuii-unique-codehashes-1603.bin")

      Using.resource(new SnapLogCapture) { log =>
        snap ! SNAPSyncController.AccountRangeSyncComplete
        val storageAsk =
          f.fish[AccountRangeCoordinator.Command, AccountRangeCoordinator.AccountGetStorageFileInfo](f.accountInbox)
        val codeHashesAsk =
          f.fish[AccountRangeCoordinator.Command, AccountRangeCoordinator.AccountGetCodeHashesFileInfo](f.accountInbox)

        // Answer one ask at a time and wait for its log line, so the two replies cannot race each other.
        storageAsk.replyTo ! AccountRangeCoordinator.StorageFileInfoResponse(storageFile, 42L)
        log.awaitLine(s"Persisted storage file path for recovery: $storageFile (42 entries)")
        codeHashesAsk.replyTo ! AccountRangeCoordinator.CodeHashesFileInfoResponse(codeHashesFile, 7L)
        log.awaitLine(s"Persisted codeHashes file path for recovery: $codeHashesFile (7 entries)")
        f.awaitProcessed(snap)
        log.contains("NOT persisted for recovery") shouldBe false
      }

      f.appStateStorage.getSnapSyncStorageFilePath() shouldBe Some(storageFile.toString)
      f.appStateStorage.getSnapSyncStorageFileCount() shouldBe Some(42L)
      f.appStateStorage.getSnapSyncCodeHashesPath() shouldBe Some(codeHashesFile.toString)
      f.appStateStorage.getSnapSyncCodeHashesCount() shouldBe Some(7L)
      f.appStateStorage.getSnapAccountResumeCheckpoint() shouldBe None
      f.stops.expectNoMessage(300.millis)
    }

  // ── Phase completion (AccountRangeSync) ───────────────────────────────────────────────────────

  "SNAPSyncController's phase-completion arms in AccountRangeSync" should
    "persist bytecode completion and boost the storage budget to the full per-peer budget, once" taggedAs UnitTest in {
      val f = new SnapControllerFixture(testKit)
      val snap = f.enterGenesisAccountSync()
      drainDownloadChildren(f, snap)
      val full = SNAPSyncConfig().maxInFlightPerPeer

      Using.resource(new SnapLogCapture) { log =>
        snap ! SNAPSyncController.ByteCodeSyncComplete
        f.awaitProcessed(snap)
        log.contains(s"Storage per-peer budget boosted to $full (bytecode complete, full budget)") shouldBe true
        log.contains("Unhandled message in syncing state: ByteCodeSyncComplete") shouldBe false

        // A second completion: the guard is false, so it falls through to the catch-all.
        snap ! SNAPSyncController.ByteCodeSyncComplete
        f.awaitProcessed(snap)
        log.contains("Unhandled message in syncing state: ByteCodeSyncComplete") shouldBe true
      }
      f.appStateStorage.isSnapSyncBytecodeComplete() shouldBe true
      progress(snap).bytecodeComplete shouldBe true
      progress(snap).phase shouldBe SNAPSyncController.SyncPhase.AccountRangeSync
      val id = f.sendFence(snap)
      f.storageBefore(id) shouldBe Seq(StorageRangeCoordinator.UpdateMaxInFlightPerPeer(full))
      f.byteCodeBefore(id) shouldBe empty
      f.accountBefore(id) shouldBe empty
    }

  it should "persist storage completion, then not boost the storage budget on bytecode completion" taggedAs UnitTest in {
    val f = new SnapControllerFixture(testKit)
    val snap = f.enterGenesisAccountSync()
    drainDownloadChildren(f, snap)

    Using.resource(new SnapLogCapture) { log =>
      snap ! SNAPSyncController.StorageRangeSyncComplete
      f.awaitProcessed(snap)
      log.contains("Storage range sync complete. ByteCode: false, Accounts: false") shouldBe true

      snap ! SNAPSyncController.StorageRangeSyncComplete
      f.awaitProcessed(snap)
      log.contains("Unhandled message in syncing state: StorageRangeSyncComplete") shouldBe true

      snap ! SNAPSyncController.ByteCodeSyncComplete
      f.awaitProcessed(snap)
      log.contains("Storage per-peer budget boosted") shouldBe false
    }
    f.appStateStorage.isSnapSyncStorageComplete() shouldBe true
    f.appStateStorage.isSnapSyncBytecodeComplete() shouldBe true
    progress(snap).phase shouldBe SNAPSyncController.SyncPhase.AccountRangeSync
    val id = f.sendFence(snap)
    f.storageBefore(id) shouldBe empty
    f.byteCodeBefore(id) shouldBe empty
  }

  it should "ignore a storage force-complete outside ByteCodeAndStorageSync and StateHealing" taggedAs UnitTest in {
    val f = new SnapControllerFixture(testKit)
    val snap = f.enterGenesisAccountSync()

    Using.resource(new SnapLogCapture) { log =>
      snap ! SNAPSyncController.StorageRangeSyncForceCompleted
      f.awaitProcessed(snap)
      log.contains(
        "StorageRangeSyncForceCompleted received during phase AccountRangeSync (consecutive-failures transient path)"
      ) shouldBe true

      // storagePhaseComplete stayed false, so the real completion is still handled.
      snap ! SNAPSyncController.StorageRangeSyncComplete
      f.awaitProcessed(snap)
      log.contains("Storage range sync complete. ByteCode: false, Accounts: false") shouldBe true
    }
    f.appStateStorage.isSnapSyncStorageComplete() shouldBe true
  }
