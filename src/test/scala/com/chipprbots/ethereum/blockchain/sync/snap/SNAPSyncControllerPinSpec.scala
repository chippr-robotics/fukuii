package com.chipprbots.ethereum.blockchain.sync.snap

import java.nio.file.Files

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.testkit.typed.scaladsl.FishingOutcomes
import org.apache.pekko.actor.testkit.typed.scaladsl.ManualTime
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.util.ByteString

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*

import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.blockchain.sync.CacheBasedBlacklist
import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.blockchain.sync.SyncProtocol
import com.chipprbots.ethereum.blockchain.sync.TestSyncConfig
import com.chipprbots.ethereum.blockchain.sync.snap.actors.TrieNodeHealingCoordinator
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.BlockBody
import com.chipprbots.ethereum.domain.BlockNumber
import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.domain.TrieRoot
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.GetHandshakedPeersCmd
import com.chipprbots.ethereum.network.PeerEventBusActor
import com.chipprbots.ethereum.testing.Tags.*

/** Spec 016 S0b pin tests (T013–T016): each test fails if the named #1385 fix is reverted. They drive the real
  * controller and observe its children through the `ChildFactories` seam (T012), so no real coordinator runs.
  *
  * Time is manual: no timer fires unless a test advances the clock, so the only messages the controller handles are the
  * ones the test sends and the immediate peer poll from `start()`. Ordering is by mailbox: a `GetProgress` round trip
  * after a send proves the send was handled. Healing is reached by the accounts-complete recovery branch of
  * `startSnapSync` (no peers connected, so the saved pivot is taken without a freshness check).
  */
class SNAPSyncControllerPinSpec extends ScalaTestWithActorTestKit(ManualTime.config) with AnyFlatSpecLike with Matchers:

  private val manualTime: ManualTime = ManualTime()(using system)

  private val Pivot = BigInt(10_000)
  private val Root = ByteString(Array.fill(32)(0x11.toByte))

  /** A healing config whose forwarded values all differ from `TrieNodeHealingCoordinator.apply`'s defaults, so a spawn
    * site that dropped a forward (and fell back to the coordinator default) is caught field by field.
    */
  private def healingConfig(frontierPersistence: Boolean, prunedHeal: Boolean = true): SNAPSyncConfig =
    SNAPSyncConfig(
      deferredMerkleization = false,
      healingBatchSize = 11,
      healingConcurrency = 7,
      healingVisitedCap = 12_345,
      healingFrontierPersistence = frontierPersistence,
      prunedHealVerification = prunedHeal,
      healingTraversalParallelism = 3,
      healingMinParallelism = 1,
      healingReservedCores = 5,
      healingFrontierHighWater = 4_321,
      healingFrontierLowWater = 1_234,
      scopedHealVerification = false,
      scopedHealMaxPaths = 999,
      decoupledHealServeRoot = true,
      decoupledHealMaxAttemptsNoRefresh = 5,
      movingRootDeltaHeal = true
    )

  // ── #1378 ──────────────────────────────────────────────────────────────────────────────────────

  "SNAPSyncController.apply" should
    "#1378: arm the 5 s handshaked-peer poll: GetHandshakedPeersCmd at once and after every 5 s, unprompted" taggedAs UnitTest in new Fixture:
      spawnController(SNAPSyncConfig())
      // start()'s immediate poll. No PollHandshakedPeers is ever sent by this test.
      npm.expectMessageType[GetHandshakedPeersCmd]
      (1 to 3).foreach { _ =>
        manualTime.expectNoMessageFor(4.seconds, npm)
        manualTime.timePasses(1.second)
        npm.expectMessageType[GetHandshakedPeersCmd]
      }

  // ── #1319 / spec-002 ───────────────────────────────────────────────────────────────────────────

  "SNAPSyncController healing spawn" should
    "#1319/spec-002: pass frontierPersistenceEnabled = true via startStateHealing when healing-frontier-persistence is on" taggedAs UnitTest in new Fixture:
      val cfg = healingConfig(frontierPersistence = true)
      val snap = startHealingViaStartStateHealing(cfg)
      val spawn = expectHealingSpawn()
      spawn.frontierPersistenceEnabled shouldBe true
      assertFullHealingTuple(spawn, cfg, snap)

  it should
    "#1319/spec-002: pass frontierPersistenceEnabled = false via startStateHealing when healing-frontier-persistence is off" taggedAs UnitTest in new Fixture:
      val cfg = healingConfig(frontierPersistence = false)
      val snap = startHealingViaStartStateHealing(cfg)
      val spawn = expectHealingSpawn()
      spawn.frontierPersistenceEnabled shouldBe false
      assertFullHealingTuple(spawn, cfg, snap)

  it should
    "#1319/spec-002: pass frontierPersistenceEnabled = true via startStateHealingWithInterleave when healing-frontier-persistence is on" taggedAs UnitTest in new Fixture:
      val cfg = healingConfig(frontierPersistence = true)
      val snap = startHealingViaInterleave(cfg)
      val spawn = expectHealingSpawn()
      spawn.frontierPersistenceEnabled shouldBe true
      assertFullHealingTuple(spawn, cfg, snap)

  it should
    "#1319/spec-002: pass frontierPersistenceEnabled = false via startStateHealingWithInterleave when healing-frontier-persistence is off" taggedAs UnitTest in new Fixture:
      val cfg = healingConfig(frontierPersistence = false)
      val snap = startHealingViaInterleave(cfg)
      val spawn = expectHealingSpawn()
      spawn.frontierPersistenceEnabled shouldBe false
      assertFullHealingTuple(spawn, cfg, snap)

  // M6a merges the two spawn blocks; both routes must keep building the same coordinator.
  it should
    "#1319/spec-002: build the same healing coordinator via startStateHealing and startStateHealingWithInterleave" taggedAs UnitTest in {
      Seq(true, false).foreach { persistence =>
        val cfg = healingConfig(frontierPersistence = persistence)
        val viaStart = new Fixture:
          startHealingViaStartStateHealing(cfg)
        val viaInterleave = new Fixture:
          startHealingViaInterleave(cfg)
        comparable(viaStart.expectHealingSpawn(), viaStart) shouldBe
          comparable(viaInterleave.expectHealingSpawn(), viaInterleave)
      }
    }

  // ── spec-005 (store gate and forwarding; the config default and parsing are in SNAPSyncControllerSpec) ──────────

  it should
    "spec-005: create the healing frontier store when pruned-heal-verification is on, even with frontier persistence off" taggedAs UnitTest in new Fixture:
      startHealingViaStartStateHealing(healingConfig(frontierPersistence = false, prunedHeal = true))
      expectHealingSpawn().healingFrontierStorage shouldBe defined

  it should
    "spec-005: create no healing frontier store when frontier persistence and pruned-heal-verification are both off" taggedAs UnitTest in new Fixture:
      startHealingViaStartStateHealing(healingConfig(frontierPersistence = false, prunedHeal = false))
      expectHealingSpawn().healingFrontierStorage shouldBe empty

  it should
    "spec-005: create the healing frontier store when frontier persistence is on, even with pruned-heal-verification off" taggedAs UnitTest in new Fixture:
      startHealingViaStartStateHealing(healingConfig(frontierPersistence = true, prunedHeal = false))
      expectHealingSpawn().healingFrontierStorage shouldBe defined

  it should
    "spec-005: forward pruned-heal-verification to the healing coordinator as today (not forwarded, #1502)" taggedAs UnitTest in {
      Seq(true, false).foreach { pruned =>
        val viaStart = new Fixture:
          startHealingViaStartStateHealing(healingConfig(frontierPersistence = false, prunedHeal = pruned))
        val viaInterleave = new Fixture:
          startHealingViaInterleave(healingConfig(frontierPersistence = false, prunedHeal = pruned))
        // #1502: the controller does not forward prunedHealVerification, so the coordinator always gets its own default
        // (true), even when pruned-heal-verification = false. The #1502 fix flips these two assertions on purpose.
        viaStart.expectHealingSpawn().prunedHealVerification shouldBe true // #1502
        viaInterleave.expectHealingSpawn().prunedHealVerification shouldBe true // #1502
      }
    }

  // ── #1371 ──────────────────────────────────────────────────────────────────────────────────────

  "SNAPSyncController download completion" should
    "#1371: skip healing (no healing coordinator) with deferred merkleization, a fresh cursor and storage force-completed" taggedAs UnitTest in new Fixture:
      val snap = enterStorageRecovery(SNAPSyncConfig(deferredMerkleization = true))
      snap ! SNAPSyncController.StorageRangeSyncForceCompleted
      awaitProcessed(snap)
      factories.healingSpawns.get() shouldBe 0
      // It took the lazy-healing handoff instead: completeSnapSync finalised at the pivot.
      parent.expectMessage(SNAPSyncController.SnapSyncFinalized(Pivot))

  it should
    "#1371 (control): start healing on the same force-completed path when deferred merkleization is off" taggedAs UnitTest in new Fixture:
      val snap = enterStorageRecovery(SNAPSyncConfig(deferredMerkleization = false))
      snap ! SNAPSyncController.StorageRangeSyncForceCompleted
      awaitProcessed(snap)
      factories.healingSpawns.get() shouldBe 1

  // ── Fixture ────────────────────────────────────────────────────────────────────────────────────

  class Fixture extends EphemBlockchainTestSetup with TestSyncConfig:
    implicit override lazy val classicSystem: ActorSystem = SNAPSyncControllerPinSpec.this.system.classicSystem

    val appStateStorage = storagesInstance.storages.appStateStorage

    // Stands in for NetworkPeerManagerActor. It never answers a peer poll, so the controller sees no peers.
    val npm: TestProbe[NetworkPeerManagerActor.Command] = testKit.createTestProbe[NetworkPeerManagerActor.Command]()
    val parent: TestProbe[SyncProtocol.SyncControllerReply] =
      testKit.createTestProbe[SyncProtocol.SyncControllerReply]()
    val spawns: TestProbe[ChildSpawn] = testKit.createTestProbe[ChildSpawn]()
    val factories = new RecordingChildFactories(spawns.ref)
    private val progressProbe = testKit.createTestProbe[SyncProgress]()

    def spawnController(config: SNAPSyncConfig): ActorRef[SNAPSyncController.Command] =
      given ExecutionContext = system.executionContext
      testKit.spawn(
        SNAPSyncController(
          blockchainReader,
          blockchainWriter,
          appStateStorage,
          storagesInstance.storages.stateStorage,
          storagesInstance.storages.evmCodeStorage,
          storagesInstance.storages.flatSlotStorage,
          npm.ref,
          testKit.spawn(PeerEventBusActor.behavior()),
          syncConfig,
          config,
          system.classicSystem.scheduler,
          CacheBasedBlacklist.empty(100),
          parent.ref,
          childFactories = factories
        )
      )

    /** Mailbox barrier: every message this test sent to `snap` before it has been handled once the reply arrives. */
    def awaitProcessed(snap: ActorRef[SNAPSyncController.Command]): Unit =
      snap ! SNAPSyncController.GetProgress(progressProbe.ref)
      progressProbe.receiveMessage()
      ()

    def expectHealingSpawn(): ChildSpawn.Healing =
      spawns
        .fishForMessage(3.seconds) {
          case _: ChildSpawn.Healing => FishingOutcomes.complete
          case _                     => FishingOutcomes.continueAndIgnore
        }
        .collect { case h: ChildSpawn.Healing => h }
        .last

    def storeGenesisAndPivotHeader(): Unit =
      val genesis = Fixtures.Blocks.Genesis.header
      blockchainWriter.save(
        Block(genesis, BlockBody.empty),
        Nil,
        ChainWeight.totalDifficultyOnly(genesis.difficulty.value),
        saveAsBestBlock = true
      )
      val header = genesis.copy(number = BlockNumber(Pivot), stateRoot = TrieRoot(Root))
      blockchainWriter.storeBlock(Block(header, BlockBody.empty)).commit()

    /** Route 1: a prior run finished accounts, bytecode and storage. `Start` takes the accounts-complete recovery
      * branch, `checkAllDownloadsComplete` runs healing (deferred merkleization is off), and `startStateHealing` spawns
      * it.
      */
    def startHealingViaStartStateHealing(config: SNAPSyncConfig): ActorRef[SNAPSyncController.Command] =
      storeGenesisAndPivotHeader()
      appStateStorage
        .putSnapSyncAccountsComplete(true)
        .and(appStateStorage.putSnapSyncStorageComplete(true))
        .and(appStateStorage.putSnapSyncBytecodeComplete(true))
        .and(appStateStorage.putSnapSyncPivotBlock(Pivot))
        .and(appStateStorage.putSnapSyncStateRoot(Root))
        .commit()
      val snap = spawnController(config)
      snap ! SNAPSyncController.Start
      snap

    /** Accounts and bytecode done, storage still to download from a persisted task file: `Start` resumes storage in
      * `ByteCodeAndStorageSync` with `syncing` as the behaviour and no healing coordinator.
      */
    def enterStorageRecovery(config: SNAPSyncConfig): ActorRef[SNAPSyncController.Command] =
      storeGenesisAndPivotHeader()
      val taskFile = Files.createTempFile("s0b-storage-tasks", ".bin")
      taskFile.toFile.deleteOnExit()
      // One 64-byte entry: account hash, then a non-empty storage root.
      Files.write(taskFile, Array.fill[Byte](32)(0x01) ++ Array.fill[Byte](32)(0x02))
      appStateStorage
        .putSnapSyncAccountsComplete(true)
        .and(appStateStorage.putSnapSyncStorageComplete(false))
        .and(appStateStorage.putSnapSyncBytecodeComplete(true))
        .and(appStateStorage.putSnapSyncPivotBlock(Pivot))
        .and(appStateStorage.putSnapSyncStateRoot(Root))
        .and(appStateStorage.putSnapSyncStorageFilePath(taskFile.toString))
        .and(appStateStorage.putSnapSyncStorageFileCount(Some(1L)))
        .commit()
      val snap = spawnController(config)
      snap ! SNAPSyncController.Start
      awaitProcessed(snap)
      // The resume really is in the storage phase: the storage coordinator was spawned, healing was not.
      factories.storageSpawns.get() shouldBe 1
      factories.healingSpawns.get() shouldBe 0
      snap

    /** Route 2: from the storage resume, `StateHealingComplete` reaches `syncing`'s arm, which (Hash scheme, no trie
      * walk running) calls `startStateHealingWithInterleave`; there is no healing coordinator yet, so it spawns one.
      */
    def startHealingViaInterleave(config: SNAPSyncConfig): ActorRef[SNAPSyncController.Command] =
      val snap = enterStorageRecovery(config)
      snap ! SNAPSyncController.StateHealingComplete
      snap

    /** Every argument of the healing spawn, field by field, against the config and the fixture. */
    def assertFullHealingTuple(
        spawn: ChildSpawn.Healing,
        cfg: SNAPSyncConfig,
        snap: ActorRef[SNAPSyncController.Command]
    ): Unit =
      spawn.stateRoot shouldBe Root
      spawn.networkPeerManager shouldBe npm.ref
      spawn.requestTracker should not be null // the controller's own tracker; not observable from outside
      spawn.mptStorage should not be null // getOrCreateMptStorage(pivot); not observable from outside
      spawn.batchSize shouldBe cfg.healingBatchSize
      spawn.snapSyncController shouldBe snap
      spawn.concurrency shouldBe cfg.healingConcurrency
      spawn.visitedCap shouldBe cfg.healingVisitedCap
      spawn.healingFrontierStorage.isDefined shouldBe (cfg.healingFrontierPersistence || cfg.prunedHealVerification)
      spawn.healingWriterEcOverride shouldBe None
      spawn.healingReaderEcOverride shouldBe None
      spawn.traversalParallelism shouldBe cfg.healingTraversalParallelism
      spawn.healingMinParallelism shouldBe cfg.healingMinParallelism
      spawn.healingReservedCores shouldBe cfg.healingReservedCores
      spawn.bfsQueueStorageOpt shouldBe defined
      spawn.storageScheme shouldBe cfg.storageScheme
      spawn.pathNodeStorageOpt shouldBe None // Hash scheme
      spawn.frontierHighWater shouldBe cfg.healingFrontierHighWater
      spawn.frontierLowWater shouldBe cfg.healingFrontierLowWater
      spawn.frontierBackpressureMaxWaitMs shouldBe TrieNodeHealingCoordinator.FrontierBackpressureMaxWaitMs
      spawn.scopedHealVerification shouldBe cfg.scopedHealVerification
      spawn.scopedHealMaxPaths shouldBe cfg.scopedHealMaxPaths
      spawn.prunedHealVerification shouldBe true // #1502: not forwarded; the coordinator default
      spawn.frontierPersistenceEnabled shouldBe cfg.healingFrontierPersistence
      spawn.decoupledHealServeRoot shouldBe cfg.decoupledHealServeRoot
      spawn.decoupledHealMaxAttemptsNoRefresh shouldBe cfg.decoupledHealMaxAttemptsNoRefresh
      spawn.movingRootDeltaHeal shouldBe cfg.movingRootDeltaHeal
      spawn.evmCodeStorage shouldBe Some(storagesInstance.storages.evmCodeStorage)
      spawn.walkLocalOnly shouldBe defined
      spawn.walkLocalOnly.get.get() shouldBe false

  /** The healing spawn with every per-instance reference replaced by what identifies it in its own fixture. */
  private def comparable(spawn: ChildSpawn.Healing, f: Fixture): ChildSpawn.Healing =
    spawn.copy(
      networkPeerManager = if spawn.networkPeerManager == f.npm.ref then null else spawn.networkPeerManager,
      requestTracker = null,
      mptStorage = null,
      snapSyncController = null,
      healingFrontierStorage = spawn.healingFrontierStorage.map(_ => null),
      bfsQueueStorageOpt = spawn.bfsQueueStorageOpt.map(_ => null),
      evmCodeStorage =
        spawn.evmCodeStorage.map(s => if s eq f.storagesInstance.storages.evmCodeStorage then null else s),
      walkLocalOnly = spawn.walkLocalOnly.map(b => if b.get() then b else null)
    )
