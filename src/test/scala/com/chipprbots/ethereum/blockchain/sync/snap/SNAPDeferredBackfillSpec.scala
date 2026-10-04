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
import com.chipprbots.ethereum.domain.BlockBody
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
    private val networkPeerManager: TestProbe = TestProbe()
    networkPeerManager.setAutoPilot(
      new AutoPilot:
        override def run(sender: ActorRef, msg: Any): AutoPilot =
          msg.asMatchable match
            case GetHandshakedPeersCmd(replyTo) =>
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

    def start(cfg: SNAPSyncConfig): TypedActorRef[SNAPSyncController.Command] =
      val genesis = Fixtures.Blocks.Genesis.header
      blockchainWriter.save(
        Block(genesis, BlockBody.empty),
        Nil,
        ChainWeight.totalDifficultyOnly(genesis.difficulty.value),
        saveAsBestBlock = true
      )
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
    "persist the backfill target with SnapSyncDone and keep backfilling after finalisation when deferred" taggedAs UnitTest in new Fixture:
      val snap = start(cfg(defer = true))
      awaitProcessed(snap)
      appStateStorage.getBackfillTarget() shouldBe BigInt(0) // nothing started while state sync is incomplete

      snap ! SNAPSyncController.HealingRootUnservable(root)
      parent.fishForMessage(10.seconds) {
        case SNAPSyncController.SnapSyncFinalized(p) if p == pivot => FishingOutcomes.complete
        case _                                                     => FishingOutcomes.continueAndIgnore
      }
      appStateStorage.isSnapSyncDone() shouldBe true
      appStateStorage.getBackfillTarget() shouldBe pivot
      appStateStorage.needsBackfillResume() shouldBe true // a restart now resumes the backfill
      parent.expectNoMessage(500.millis) // backfill is running in the background: no Done yet

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

  "SNAPSyncController.chainDownloadRunsDuringStateSync" should
    "be true only when download is enabled and not deferred" taggedAs UnitTest in {
      SNAPSyncController.chainDownloadRunsDuringStateSync(cfg(defer = false)) shouldBe true
      SNAPSyncController.chainDownloadRunsDuringStateSync(cfg(defer = true)) shouldBe false
      SNAPSyncController.chainDownloadRunsDuringStateSync(
        cfg(defer = false).copy(chainDownloadEnabled = false)
      ) shouldBe false
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
