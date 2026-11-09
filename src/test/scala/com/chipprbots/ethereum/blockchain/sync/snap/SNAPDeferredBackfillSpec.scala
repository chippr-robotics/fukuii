package com.chipprbots.ethereum.blockchain.sync.snap

import java.util.concurrent.ConcurrentHashMap.KeySetView
import java.util.concurrent.atomic.AtomicInteger

import org.apache.pekko.actor.ActorRef
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.testkit.typed.scaladsl.FishingOutcomes
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe as TypedTestProbe
import org.apache.pekko.actor.typed.ActorRef as TypedActorRef
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.testkit.TestActor.AutoPilot
import org.apache.pekko.testkit.TestProbe
import org.apache.pekko.util.ByteString

import scala.compiletime.asMatchable
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*

import org.scalatest.concurrent.Eventually
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.Millis
import org.scalatest.time.Seconds
import org.scalatest.time.Span

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.blockchain.sync.Blacklist
import com.chipprbots.ethereum.blockchain.sync.CacheBasedBlacklist
import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.blockchain.sync.SyncProtocol
import com.chipprbots.ethereum.blockchain.sync.TestSyncConfig
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController.Command
import com.chipprbots.ethereum.blockchain.sync.snap.controller.ChildFactories
import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.BlockBody
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockNumber
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.domain.BlockchainWriter
import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.domain.Difficulty
import com.chipprbots.ethereum.domain.TrieRoot
import com.chipprbots.ethereum.ledger.AncestorBlockHashes
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.GetHandshakedPeersCmd
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.HandshakedPeers
import com.chipprbots.ethereum.network.PeerEventBusActor
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.Config.SyncConfig

/** With `defer-chain-backfill-until-state-complete` on, the chain downloader (bodies and receipts) must not run while
  * SNAP state sync does, must start once the state is finalised, and must survive a restart (its target is persisted in
  * the same commit as SnapSyncDone). Finalisation holds until the header download reaches the pivot, with the boosted
  * request budget, and the pivot's total difficulty is labelled REAL_DB_TD only when it is exact (#1654). With the
  * switch off, finalisation does not wait.
  */
class SNAPDeferredBackfillSpec extends ScalaTestWithActorTestKit() with AnyFlatSpecLike with Matchers with Eventually:

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = scaled(Span(10, Seconds)), interval = scaled(Span(50, Millis)))

  class Fixture extends EphemBlockchainTestSetup with TestSyncConfig:
    implicit override lazy val classicSystem: ActorSystem = SNAPDeferredBackfillSpec.this.system.classicSystem

    val appStateStorage = storagesInstance.storages.appStateStorage
    val evmCodeStorage = storagesInstance.storages.evmCodeStorage
    private val pollsAnswered = new AtomicInteger(0)
    // Each ChainDownloader instance polls with its own reply adapter, so a respawn shows up as a new reply ref.
    val pollers: KeySetView[Any, java.lang.Boolean] = java.util.concurrent.ConcurrentHashMap.newKeySet[Any]()
    private val networkPeerManager: TestProbe = TestProbe()
    networkPeerManager.setAutoPilot(
      new AutoPilot:
        override def run(sender: ActorRef, msg: Any): AutoPilot =
          msg.asMatchable match
            case GetHandshakedPeersCmd(replyTo) =>
              pollers.add(replyTo)
              replyTo ! HandshakedPeers(Map.empty)
              pollsAnswered.incrementAndGet()
            case _ => ()
          this
    )
    val parent: TypedTestProbe[SyncProtocol.SyncControllerReply] =
      testKit.createTestProbe[SyncProtocol.SyncControllerReply]()
    private val progressProbe = testKit.createTestProbe[SyncProgress]()

    // A copy of every command any chain-downloader incarnation receives (Behaviors.monitor); the real downloader runs.
    val chainMessages: TypedTestProbe[ChainDownloader.Command] = testKit.createTestProbe[ChainDownloader.Command]()
    private val monitoredChildren: ChildFactories = new ChildFactories:
      override def chainDownloader(
          blockchainReader: BlockchainReader,
          blockchainWriter: BlockchainWriter,
          appStateStorage: AppStateStorage,
          networkPeerManager: TypedActorRef[NetworkPeerManagerActor.Command],
          peerEventBus: TypedActorRef[PeerEventBusActor.Command],
          syncConfig: SyncConfig,
          replyTo: TypedActorRef[ChainDownloader.Done.type],
          maxConcurrentRequests: Int,
          requestTimeout: FiniteDuration,
          snapServerPeerNodeIds: Set[ByteString],
          blacklist: Blacklist,
          cursorScanCap: Long,
          deferBodiesAndReceipts: Boolean,
          emptyHeaderBackoff: Option[FiniteDuration],
          nowMs: () => Long
      ): Behavior[ChainDownloader.Command] =
        Behaviors.monitor(
          chainMessages.ref,
          super.chainDownloader(
            blockchainReader,
            blockchainWriter,
            appStateStorage,
            networkPeerManager,
            peerEventBus,
            syncConfig,
            replyTo,
            maxConcurrentRequests,
            requestTimeout,
            snapServerPeerNodeIds,
            blacklist,
            cursorScanCap,
            deferBodiesAndReceipts,
            emptyHeaderBackoff,
            nowMs
          )
        )

    def fishChain(max: FiniteDuration)(pf: PartialFunction[ChainDownloader.Command, Unit]): Unit =
      chainMessages.fishForMessage(max) { msg =>
        if pf.isDefinedAt(msg) then FishingOutcomes.complete else FishingOutcomes.continueAndIgnore
      }
      ()

    // The pivot's weight as the header download stores it: the parent's weight plus the pivot's difficulty.
    def storeExactPivotWeight(pivotHeader: BlockHeader, pivotTd: BigInt): Unit =
      blockchainWriter
        .storeChainWeight(
          pivotHeader.parentHash,
          ChainWeight.totalDifficultyOnly(pivotTd - pivotHeader.difficulty.value)
        )
        .and(blockchainWriter.storeChainWeight(pivotHeader.hash, ChainWeight.totalDifficultyOnly(pivotTd)))
        .commit()

    def awaitFinalized(): Unit =
      parent.fishForMessage(15.seconds) {
        case SNAPSyncController.SnapSyncFinalized(p) if p == pivot => FishingOutcomes.complete
        case _                                                     => FishingOutcomes.continueAndIgnore
      }
      ()

    val pivot: BigInt = BigInt(10_000)
    val root: ByteString = ByteString(Array.fill(32)(0x11.toByte))

    /** `genesis` and `terminalTotalDifficulty` default to the ETC genesis and no terminal TD; every header below the
      * pivot copies the genesis difficulty.
      */
    def start(
        cfg: SNAPSyncConfig,
        withAncestors: Boolean = false,
        genesis: BlockHeader = Fixtures.Blocks.Genesis.header,
        terminalTotalDifficulty: Option[BigInt] = None
    ): TypedActorRef[SNAPSyncController.Command] =
      blockchainWriter.save(
        Block(genesis, BlockBody.empty),
        Nil,
        ChainWeight.totalDifficultyOnly(genesis.difficulty.value),
        saveAsBestBlock = true
      )
      if withAncestors then
        // pivot-256 .. pivot, linked by parentHash; the last one is the pivot header.
        val chain = (pivot - 256 to pivot)
          .foldLeft((genesis.hash, Vector.empty[BlockHeader])) { case ((parentHash, acc), n) =>
            val h = genesis.copy(number = BlockNumber(n), parentHash = parentHash, stateRoot = TrieRoot(root))
            (h.hash, acc :+ h)
          }
          ._2
        chain.init.foreach(h => blockchainWriter.storeBlockHeader(h).commit())
        blockchainWriter.storeBlock(Block(chain.last, BlockBody.empty)).commit()
      else
        val header = genesis.copy(number = BlockNumber(pivot), stateRoot = TrieRoot(root))
        blockchainWriter.storeBlock(Block(header, BlockBody.empty)).commit()
      appStateStorage
        .putSnapSyncAccountsComplete(true)
        .and(appStateStorage.putSnapSyncStorageComplete(true))
        .and(appStateStorage.putSnapSyncBytecodeComplete(true))
        .and(appStateStorage.putSnapSyncPivotBlock(pivot))
        .and(appStateStorage.putSnapSyncStateRoot(root))
        .commit()
      given ExecutionContext = system.executionContext
      val snap = testKit.spawn(
        SNAPSyncController(
          blockchainReader,
          blockchainWriter,
          appStateStorage,
          storagesInstance.storages.stateStorage,
          evmCodeStorage,
          storagesInstance.storages.flatSlotStorage,
          networkPeerManager.ref.toTyped[NetworkPeerManagerActor.Command],
          testKit.spawn(PeerEventBusActor.behavior()),
          syncConfig,
          cfg,
          system.classicSystem.scheduler,
          CacheBasedBlacklist.empty(100),
          parent.ref,
          childFactories = monitoredChildren,
          terminalTotalDifficultyOverride = terminalTotalDifficulty
        )
      )
      eventually(pollsAnswered.get() should be >= 1)
      snap ! SNAPSyncController.Start
      snap

    def awaitProcessed(snap: TypedActorRef[SNAPSyncController.Command]): Unit =
      snap ! SNAPSyncController.GetProgress(progressProbe.ref)
      progressProbe.receiveMessage(5.seconds)
      ()

  private def withSnapLogs[A](body: SnapLogCapture => A): A =
    val logs = new SnapLogCapture
    try body(logs)
    finally logs.close()

  private def cfg(defer: Boolean) =
    SNAPSyncConfig(
      deferredMerkleization = false,
      movingRootDeltaHeal = true,
      deferChainBackfillUntilStateComplete = defer
    )

  "SNAPSyncController" should
    "spawn the header-only downloader during state sync, hold finalisation until the header cursor reaches the pivot, " +
    "then finalise with the REAL pivot TD and a resolvable BLOCKHASH window" taggedAs UnitTest in (new Fixture:
      val snap: TypedActorRef[Command] = start(cfg(defer = true), withAncestors = true)
      awaitProcessed(snap)
      // The downloader exists while state sync is not done (ChainDownloader.Start persists its target)...
      eventually(appStateStorage.getBackfillTarget() shouldBe pivot)
      appStateStorage.isSnapSyncDone() shouldBe false

      // ...state completes but the forward header download is nowhere near the pivot: finalisation holds.
      snap ! SNAPSyncController.HealingRootUnservable(root)
      awaitProcessed(snap)
      snap ! SNAPSyncController.HeaderHoldTick // re-check now instead of waiting for the 2 s timer
      awaitProcessed(snap)
      parent.expectNoMessage(0.millis) // still holding: nothing finalised, nothing aborted
      appStateStorage.isSnapSyncDone() shouldBe false

      // The header download reaches the pivot: the cursor advances and the real accumulated TD is stored (the parent's
      // weight plus the pivot's difficulty, as the downloader stores every header).
      val pivotHeader: BlockHeader = blockchainReader.getBlockHeaderByNumber(pivot).get
      val realTd: BigInt = Fixtures.Blocks.Genesis.header.difficulty.value * 5000
      storeExactPivotWeight(pivotHeader, realTd)
      appStateStorage.putBackfillBestHeader(pivot).commit()
      snap ! SNAPSyncController.HeaderHoldTick

      parent.fishForMessage(15.seconds) {
        case SNAPSyncController.SnapSyncFinalized(p) if p == pivot => FishingOutcomes.complete
        case _                                                     => FishingOutcomes.continueAndIgnore
      }
      appStateStorage.isSnapSyncDone() shouldBe true
      appStateStorage.getBackfillTarget() shouldBe pivot
      appStateStorage.needsBackfillResume() shouldBe true // a restart now resumes the backfill
      blockchainReader.getChainWeightByHash(pivotHeader.hash).map(_.totalDifficulty.value) shouldBe Some(realTd)

      // BLOCKHASH for the block after the pivot walks parentHash through the stored headers.
      val executing: BlockHeader = pivotHeader.copy(number = BlockNumber(pivot + 1), parentHash = pivotHeader.hash)
      val blockHashes: AncestorBlockHashes = AncestorBlockHashes.forBlock(executing, blockchainReader)
      val byNumber: Map[BigInt, BlockHeader] =
        (pivot - 256 to pivot).map(n => n -> blockchainReader.getBlockHeaderByNumber(n).get).toMap
      blockHashes(pivot - 1) shouldBe Some(byNumber(pivot - 1).hash.value)
      blockHashes(pivot - 256) shouldBe Some(byNumber(pivot - 256).hash.value)
    )

  it should "restart the chain downloader, and keep holding, when the header cursor stalls during the hold" taggedAs UnitTest in new Fixture:
    val snap: TypedActorRef[Command] =
      start(cfg(defer = true).copy(headerHoldStallTimeout = 0.seconds), withAncestors = true)
    awaitProcessed(snap)
    eventually(appStateStorage.getBackfillTarget() shouldBe pivot)
    snap ! SNAPSyncController.HealingRootUnservable(root)
    awaitProcessed(snap)
    val before = pollers.size

    // The cursor never advances: with a zero stall timeout the first tick respawns the downloader (a new poll adapter).
    eventually {
      snap ! SNAPSyncController.HeaderHoldTick
      awaitProcessed(snap)
      pollers.size should be > before
    }
    parent.expectNoMessage(0.millis) // never finalised without the headers
    appStateStorage.isSnapSyncDone() shouldBe false

  // #1654: the hold is the window where state is complete and regular sync has not started, so every peer is free.
  it should "boost the header download when the hold starts, then finalise with REAL_DB_TD and the backfill budget" taggedAs UnitTest in new Fixture:
    withSnapLogs { logs =>
      val config = cfg(defer = true)
      val snap: TypedActorRef[Command] = start(config, withAncestors = true)
      awaitProcessed(snap)
      eventually(appStateStorage.getBackfillTarget() shouldBe pivot)

      snap ! SNAPSyncController.HealingRootUnservable(root)
      awaitProcessed(snap)
      fishChain(5.seconds) {
        case ChainDownloader.BoostConcurrency(n) if n == config.chainDownloadBoostedConcurrentRequests => ()
      }
      appStateStorage.isSnapSyncDone() shouldBe false

      val pivotHeader: BlockHeader = blockchainReader.getBlockHeaderByNumber(pivot).get
      val realTd: BigInt = Fixtures.Blocks.Genesis.header.difficulty.value * 5000
      storeExactPivotWeight(pivotHeader, realTd)
      appStateStorage.putBackfillBestHeader(pivot).commit()
      snap ! SNAPSyncController.HeaderHoldTick
      awaitFinalized()

      fishChain(5.seconds) {
        case ChainDownloader.YieldToRegularSync(n) if n == config.chainBackfillConcurrentRequests => ()
      }
      logs.awaitLine("SNAP finalize pivot TD") should include("source=REAL_DB_TD")
      blockchainReader.getChainWeightByHash(pivotHeader.hash).map(_.totalDifficulty.value) shouldBe Some(realTd)
    }

  it should "boost a downloader the hold's stall watchdog respawned" taggedAs UnitTest in new Fixture:
    val config: SNAPSyncConfig = cfg(defer = true).copy(headerHoldStallTimeout = 0.seconds)
    val snap: TypedActorRef[Command] = start(config, withAncestors = true)
    awaitProcessed(snap)
    eventually(appStateStorage.getBackfillTarget() shouldBe pivot)
    val before = pollers.size

    // With a zero stall timeout, entering the hold boosts the running downloader, then respawns it at once.
    snap ! SNAPSyncController.HealingRootUnservable(root)
    awaitProcessed(snap)
    eventually(pollers.size should be > before) // the replacement polled for peers
    // One boost for the downloader the hold found, one for its replacement (before #1654, none).
    (1 to 2).foreach { _ =>
      fishChain(5.seconds) {
        case ChainDownloader.BoostConcurrency(n) if n == config.chainDownloadBoostedConcurrentRequests => ()
      }
    }
    appStateStorage.isSnapSyncDone() shouldBe false

  // Without the hold (switch off), the pivot's stored weight can be PivotSelector's selection-time estimate. Before
  // #1654 any stored weight above 1000x the genesis TD was kept and logged as REAL_DB_TD.
  it should "replace a selection-time pivot estimate with parent weight + difficulty when the parent's weight is exact" taggedAs UnitTest in new Fixture:
    val snap: TypedActorRef[Command] = start(cfg(defer = false), withAncestors = true)
    awaitProcessed(snap)
    val pivotHeader: BlockHeader = blockchainReader.getBlockHeaderByNumber(pivot).get
    val parentTd: BigInt = Fixtures.Blocks.Genesis.header.difficulty.value * 4999
    val selectionEstimate: BigInt = parentTd * 3
    blockchainWriter
      .storeChainWeight(pivotHeader.parentHash, ChainWeight.totalDifficultyOnly(parentTd))
      .and(blockchainWriter.storeChainWeight(pivotHeader.hash, ChainWeight.totalDifficultyOnly(selectionEstimate)))
      .commit()

    snap ! SNAPSyncController.HealingRootUnservable(root)
    awaitFinalized()
    blockchainReader.getChainWeightByHash(pivotHeader.hash).map(_.totalDifficulty.value) shouldBe
      Some(parentTd + pivotHeader.difficulty.value)

  it should "not label a selection-time pivot estimate REAL_DB_TD when the pivot's parent has no weight" taggedAs UnitTest in new Fixture:
    withSnapLogs { logs =>
      val snap: TypedActorRef[Command] = start(cfg(defer = false), withAncestors = true)
      awaitProcessed(snap)
      val pivotHeader: BlockHeader = blockchainReader.getBlockHeaderByNumber(pivot).get
      val selectionEstimate: BigInt = Fixtures.Blocks.Genesis.header.difficulty.value * 3000
      blockchainWriter.storeChainWeight(pivotHeader.hash, ChainWeight.totalDifficultyOnly(selectionEstimate)).commit()

      snap ! SNAPSyncController.HealingRootUnservable(root)
      awaitFinalized()
      val line = logs.awaitLine("SNAP finalize pivot TD")
      line should include("source=PIVOT_SELECTION_ESTIMATE")
      line.contains("REAL_DB_TD") shouldBe false
      // Only the label changes: the estimate is still the best value available without the headers.
      blockchainReader.getChainWeightByHash(pivotHeader.hash).map(_.totalDifficulty.value) shouldBe Some(
        selectionEstimate
      )
    }

  // #1654: Platåberget (terminal TD 0, genesis difficulty 0). The header download stores the exact weight, 0, at every
  // header; the floor rejects 0 at every height above 1, and the selection-time check "above 1000x the genesis TD" is
  // `> 0`. So the hold reached the pivot and finalisation still wrote the block-number proxy (`td = pivot`) over 0.
  it should "finalise with the genesis weight, not the block-number proxy, on a chain that was PoS from genesis" taggedAs UnitTest in new Fixture:
    withSnapLogs { logs =>
      val posGenesis: BlockHeader = Fixtures.Blocks.Genesis.header.copy(difficulty = Difficulty.Zero)
      val snap: TypedActorRef[Command] =
        start(cfg(defer = true), withAncestors = true, genesis = posGenesis, terminalTotalDifficulty = Some(BigInt(0)))
      awaitProcessed(snap)
      eventually(appStateStorage.getBackfillTarget() shouldBe pivot)
      snap ! SNAPSyncController.HealingRootUnservable(root)
      awaitProcessed(snap)
      appStateStorage.isSnapSyncDone() shouldBe false // holding for the headers

      val pivotHeader: BlockHeader = blockchainReader.getBlockHeaderByNumber(pivot).get
      pivotHeader.difficulty shouldBe Difficulty.Zero
      storeExactPivotWeight(pivotHeader, BigInt(0))
      appStateStorage.putBackfillBestHeader(pivot).commit()
      snap ! SNAPSyncController.HeaderHoldTick
      awaitFinalized()

      logs.awaitLine("SNAP finalize pivot TD") should include("source=GENESIS_WEIGHT")
      blockchainReader.getChainWeightByHash(pivotHeader.hash).map(_.totalDifficulty.value) shouldBe Some(BigInt(0))
    }

  it should "be unchanged with the switch off: the downloader starts during state sync, before finalisation" taggedAs UnitTest in new Fixture:
    val snap: TypedActorRef[Command] = start(cfg(defer = false))
    awaitProcessed(snap)
    // ChainDownloader.Start persists its target: with the switch off it is already running while state sync is not done.
    eventually(appStateStorage.getBackfillTarget() shouldBe pivot)
    appStateStorage.isSnapSyncDone() shouldBe false

    snap ! SNAPSyncController.HealingRootUnservable(root)
    parent.fishForMessage(10.seconds) {
      case SNAPSyncController.SnapSyncFinalized(p) if p == pivot => FishingOutcomes.complete
      case _                                                     => FishingOutcomes.continueAndIgnore
    }
    appStateStorage.getBackfillTarget() shouldBe pivot

  "SNAPSyncController.chainBackfillDeferredToFinalization" should
    "be true only when download is enabled and deferred" taggedAs UnitTest in {
      SNAPSyncController.chainBackfillDeferredToFinalization(cfg(defer = true)) shouldBe true
      SNAPSyncController.chainBackfillDeferredToFinalization(cfg(defer = false)) shouldBe false
      SNAPSyncController.chainBackfillDeferredToFinalization(
        cfg(defer = true).copy(chainDownloadEnabled = false)
      ) shouldBe false
    }

  // #1654: on for every chain, one value. Before, only the post-merge ETH configs set it, so an ETC/Mordor SNAP pivot
  // got an estimated total difficulty that every block above it inherits.
  "SNAPSyncConfig.fromConfig" should "read the switch, on in the base config, when absent and in every shipped chain config" taggedAs UnitTest in {
    import com.typesafe.config.ConfigFactory
    import com.typesafe.config.ConfigValueFactory
    val key = "snap-sync.defer-chain-backfill-until-state-complete"
    val sync = ConfigFactory.load().getConfig("fukuii.sync")
    SNAPSyncConfig.fromConfig(sync).deferChainBackfillUntilStateComplete shouldBe true
    SNAPSyncConfig.fromConfig(sync.withoutPath(key)).deferChainBackfillUntilStateComplete shouldBe true
    val off = sync.withValue(key, ConfigValueFactory.fromAnyRef(false))
    SNAPSyncConfig.fromConfig(off).deferChainBackfillUntilStateComplete shouldBe false

    for chain <- Seq("etc", "mordor", "eth", "sepolia", "plataberget", "ephemery") do
      val shipped = ConfigFactory.load(ConfigFactory.parseResources(s"conf/$chain.conf")).getConfig("fukuii.sync")
      withClue(s"conf/$chain.conf: ") {
        SNAPSyncConfig.fromConfig(shipped).deferChainBackfillUntilStateComplete shouldBe true
      }
  }
