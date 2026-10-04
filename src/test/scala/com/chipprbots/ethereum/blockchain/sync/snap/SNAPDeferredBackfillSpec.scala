package com.chipprbots.ethereum.blockchain.sync.snap

import java.util.concurrent.atomic.AtomicInteger

import org.apache.pekko.actor.ActorRef
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.testkit.typed.scaladsl.FishingOutcomes
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe as TypedTestProbe
import org.apache.pekko.actor.typed.ActorRef as TypedActorRef
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
import com.chipprbots.ethereum.blockchain.sync.CacheBasedBlacklist
import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.blockchain.sync.SyncProtocol
import com.chipprbots.ethereum.blockchain.sync.TestSyncConfig
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.ledger.AncestorBlockHashes
import com.chipprbots.ethereum.domain.BlockBody
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockNumber
import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.domain.TrieRoot
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.GetHandshakedPeersCmd
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.HandshakedPeers
import com.chipprbots.ethereum.network.PeerEventBusActor
import com.chipprbots.ethereum.testing.Tags.*

/** With `defer-chain-backfill-until-state-complete` on, the chain downloader (bodies and receipts) must not run while
  * SNAP state sync does, must start once the state is finalised, and must survive a restart (its target is persisted in
  * the same commit as SnapSyncDone). With it off, behaviour is unchanged.
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
    val pollers = java.util.concurrent.ConcurrentHashMap.newKeySet[Any]()
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

    val pivot = BigInt(10_000)
    val root = ByteString(Array.fill(32)(0x11.toByte))

    def start(cfg: SNAPSyncConfig, withAncestors: Boolean = false): TypedActorRef[SNAPSyncController.Command] =
      val genesis = Fixtures.Blocks.Genesis.header
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
          parent.ref
        )
      )
      eventually(pollsAnswered.get() should be >= 1)
      snap ! SNAPSyncController.Start
      snap

    def awaitProcessed(snap: TypedActorRef[SNAPSyncController.Command]): Unit =
      snap ! SNAPSyncController.GetProgress(progressProbe.ref)
      progressProbe.receiveMessage(5.seconds)
      ()

  private def cfg(defer: Boolean) =
    SNAPSyncConfig(
      deferredMerkleization = false,
      movingRootDeltaHeal = true,
      deferChainBackfillUntilStateComplete = defer
    )

  "SNAPSyncController" should
    "spawn the header-only downloader during state sync, hold finalisation until the header cursor reaches the pivot, " +
    "then finalise with the REAL pivot TD and a resolvable BLOCKHASH window" taggedAs UnitTest in new Fixture:
      val snap = start(cfg(defer = true), withAncestors = true)
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

      // The header download reaches the pivot: the cursor advances and the real accumulated TD is stored.
      val pivotHeader = blockchainReader.getBlockHeaderByNumber(pivot).get
      val realTd = Fixtures.Blocks.Genesis.header.difficulty.value * 5000
      blockchainWriter.storeChainWeight(pivotHeader.hash, ChainWeight.totalDifficultyOnly(realTd)).commit()
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
      val executing = pivotHeader.copy(number = BlockNumber(pivot + 1), parentHash = pivotHeader.hash)
      val blockHashes = AncestorBlockHashes.forBlock(executing, blockchainReader)
      val byNumber = (pivot - 256 to pivot).map(n => n -> blockchainReader.getBlockHeaderByNumber(n).get).toMap
      blockHashes(pivot - 1) shouldBe Some(byNumber(pivot - 1).hash.value)
      blockHashes(pivot - 256) shouldBe Some(byNumber(pivot - 256).hash.value)

  it should "restart the chain downloader, and keep holding, when the header cursor stalls during the hold" taggedAs UnitTest in new Fixture:
    val snap = start(cfg(defer = true).copy(headerHoldStallTimeout = 0.seconds), withAncestors = true)
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

  it should "be unchanged with the switch off: the downloader starts during state sync, before finalisation" taggedAs UnitTest in new Fixture:
    val snap = start(cfg(defer = false))
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

  "SNAPSyncConfig.fromConfig" should "read the switch, defaulting to off in the base config" taggedAs UnitTest in {
    import com.typesafe.config.ConfigFactory
    val sync = ConfigFactory.load().getConfig("fukuii.sync")
    SNAPSyncConfig.fromConfig(sync).deferChainBackfillUntilStateComplete shouldBe false
    val on = sync.withValue(
      "snap-sync.defer-chain-backfill-until-state-complete",
      com.typesafe.config.ConfigValueFactory.fromAnyRef(true)
    )
    SNAPSyncConfig.fromConfig(on).deferChainBackfillUntilStateComplete shouldBe true
  }
