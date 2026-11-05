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

import com.typesafe.config.ConfigFactory
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.blockchain.sync.CacheBasedBlacklist
import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.blockchain.sync.SyncProtocol
import com.chipprbots.ethereum.blockchain.sync.TestSyncConfig
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController.Command
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
// ManualTime on top of the test application.conf, which declares the dispatchers the controller uses.
class SNAPSyncControllerPinSpec
    extends ScalaTestWithActorTestKit(ManualTime.config.withFallback(ConfigFactory.load()))
    with AnyFlatSpecLike
    with Matchers:

  private val manualTime: ManualTime = ManualTime()(using system)

  private val Pivot = BigInt(10_000)
  private val Root = ByteString(Array.fill(32)(0x11.toByte))

  /** A healing config whose forwarded values all differ from `TrieNodeHealingCoordinator.apply`'s defaults, so a spawn
    * site that dropped a forward (and fell back to the coordinator default) is caught field by field.
    */
  private def healingConfig(): SNAPSyncConfig =
    SNAPSyncConfig(
      deferredMerkleization = false,
      healingBatchSize = 11,
      healingConcurrency = 7,
      healingVisitedCap = 12_345,
      healingTraversalParallelism = 3,
      healingMinParallelism = 1,
      healingReservedCores = 5,
      healingFrontierHighWater = 4_321,
      healingFrontierLowWater = 1_234,
      decoupledHealServeRoot = true,
      decoupledHealMaxAttemptsNoRefresh = 5,
      movingRootDeltaHeal = true
    )

  // ── #1378 ──────────────────────────────────────────────────────────────────────────────────────

  "SNAPSyncController.apply" should
    "#1378: arm the 5 s handshaked-peer poll: GetHandshakedPeersCmd at once and after every 5 s, unprompted" taggedAs UnitTest in (new Fixture:
      spawnController(SNAPSyncConfig())
      // start()'s immediate poll. No PollHandshakedPeers is ever sent by this test.
      npm.expectMessageType[GetHandshakedPeersCmd]
      (1 to 3).foreach { _ =>
        manualTime.expectNoMessageFor(4.seconds, npm)
        manualTime.timePasses(1.second)
        npm.expectMessageType[GetHandshakedPeersCmd]
      }
    )

  // ── the healing spawn's arguments ─────────────────────────────────────────────────────────────

  "SNAPSyncController healing spawn" should
    "spec 016 S0b: forward every healing setting via startStateHealing" taggedAs UnitTest in (new Fixture:
      val cfg: SNAPSyncConfig = healingConfig()
      val snap: ActorRef[Command] = startHealingViaStartStateHealing(cfg)
      assertFullHealingTuple(expectHealingSpawn(), cfg, snap)
    )

  // spec 022 T052: `startStateHealingWithInterleave` had one way in, this signal, on Hash. Outside healing the arm now takes
  // nothing, so `startStateHealing` is the one route to the spawn, and the pins of the second route went with it.
  it should
    "spec 022 T052: spawn nothing on a StateHealingComplete from outside healing: the interleave route is gone" taggedAs UnitTest in (new Fixture:
      val snap: ActorRef[Command] = enterStorageRecovery(healingConfig())
      snap ! SNAPSyncController.StateHealingComplete
      awaitProcessed(snap)
      factories.healingSpawns.get() shouldBe 0
    )

  // spec 022 5d: the spawn says whether the marker can hold, and that is `canHold` with what this run is on.
  it should
    "spec 022 5d: tell the coordinator the marker may stand with the trie built and the trie store on disk" taggedAs UnitTest in (new Fixture:
      startHealingViaStartStateHealing(healingConfig())
      expectHealingSpawn().markerMayStand shouldBe true
    )

  it should
    "spec 022 5d: hand the coordinator the record it says its decision in" taggedAs UnitTest in (new Fixture:
      startHealingViaStartStateHealing(healingConfig())
      expectHealingSpawn()
      factories.lastHealingDecidedRoot.get() shouldBe defined
    )

  it should
    "spec 022 5d (vault V3): tell the coordinator no marker may stand under in-memory pruning" taggedAs UnitTest in {
      // In-memory pruning keeps healed nodes in its node cache and writes them as the cache evicts them, one by one, so
      // a present node can stand over a child a crash lost with the cache.
      def inMemory(f: Fixture): Unit =
        f.stateStorageOverride = Some(
          new com.chipprbots.ethereum.db.storage.CachedReferenceCountedStateStorage(
            f.storagesInstance.storages.nodeStorage,
            10,
            new com.chipprbots.ethereum.db.cache.LruCache[ByteString, com.chipprbots.ethereum.db.storage.HeapEntry](
              new com.chipprbots.ethereum.utils.NodeCacheConfig:
                override val maxSize: Long = 1000
                override val maxHoldTime: FiniteDuration = 60.minutes
            )
          )
        )
      val viaStart = new Fixture:
        inMemory(this)
        startHealingViaStartStateHealing(healingConfig())
      viaStart.expectHealingSpawn().markerMayStand shouldBe false
    }

  it should
    "spec 022 T044 (vault R1): leave an older binary's completeness marker in CF `g` alone at a spawn under in-memory pruning" taggedAs UnitTest in {
      // A completeness marker was written after flushes whose persist does nothing under in-memory pruning. Nothing
      // reads CF `g` now, and nothing writes to it at the spawn.
      def inMemoryWithStaleMarker(f: Fixture): com.chipprbots.ethereum.db.storage.HealingFrontierStorage =
        f.stateStorageOverride = Some(
          new com.chipprbots.ethereum.db.storage.CachedReferenceCountedStateStorage(
            f.storagesInstance.storages.nodeStorage,
            10,
            new com.chipprbots.ethereum.db.cache.LruCache[ByteString, com.chipprbots.ethereum.db.storage.HeapEntry](
              new com.chipprbots.ethereum.utils.NodeCacheConfig:
                override val maxSize: Long = 1000
                override val maxHoldTime: FiniteDuration = 60.minutes
            )
          )
        )
        val store = new com.chipprbots.ethereum.db.storage.HealingFrontierStorage(
          f.storagesInstance.storages.flatSlotStorage.dataSource
        )
        store.markComplete()
        store
      val f = new Fixture
      val store = inMemoryWithStaleMarker(f)
      f.startHealingViaStartStateHealing(healingConfig())
      f.expectHealingSpawn()
      // spec 022 T044: the spawn no longer clears it: nothing reads CF `g` now, and nothing writes to it. The
      // one-time clear at node start takes it, and `HealInvariant` keeps the invariant marker off this mode.
      store.isComplete shouldBe true
    }

  // ── #1371 ──────────────────────────────────────────────────────────────────────────────────────

  "SNAPSyncController download completion" should
    "#1371: skip healing (no healing coordinator) with deferred merkleization, a fresh cursor and storage force-completed" taggedAs UnitTest in (new Fixture:
      val snap: ActorRef[Command] = enterStorageRecovery(SNAPSyncConfig(deferredMerkleization = true))
      snap ! SNAPSyncController.StorageRangeSyncForceCompleted
      awaitProcessed(snap)
      factories.healingSpawns.get() shouldBe 0
      // It took the lazy-healing handoff instead: completeSnapSync finalised at the pivot.
      parent.expectMessage(SNAPSyncController.SnapSyncFinalized(Pivot))
    )

  it should
    "#1371 (control): start healing on the same force-completed path when deferred merkleization is off" taggedAs UnitTest in (new Fixture:
      val snap: ActorRef[Command] = enterStorageRecovery(SNAPSyncConfig(deferredMerkleization = false))
      snap ! SNAPSyncController.StorageRangeSyncForceCompleted
      awaitProcessed(snap)
      factories.healingSpawns.get() shouldBe 1
    )

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

    /** The state storage the controller gets; the node's own (archive pruning) unless a case sets another. */
    var stateStorageOverride: Option[com.chipprbots.ethereum.db.storage.StateStorage] = None

    def spawnController(config: SNAPSyncConfig): ActorRef[SNAPSyncController.Command] =
      given ExecutionContext = system.executionContext
      testKit.spawn(
        SNAPSyncController(
          blockchainReader,
          blockchainWriter,
          appStateStorage,
          stateStorageOverride.getOrElse(storagesInstance.storages.stateStorage),
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
        .fishForMessage(10.seconds) {
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
      spawn.decoupledHealServeRoot shouldBe cfg.decoupledHealServeRoot
      spawn.decoupledHealMaxAttemptsNoRefresh shouldBe cfg.decoupledHealMaxAttemptsNoRefresh
      spawn.movingRootDeltaHeal shouldBe cfg.movingRootDeltaHeal
      spawn.evmCodeStorage shouldBe Some(storagesInstance.storages.evmCodeStorage)
      spawn.walkLocalOnly shouldBe defined
      spawn.walkLocalOnly.get.get() shouldBe false
      spawn.markerMayStand shouldBe true
