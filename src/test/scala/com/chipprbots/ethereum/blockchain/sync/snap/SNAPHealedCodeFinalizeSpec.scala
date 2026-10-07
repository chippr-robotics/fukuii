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
import com.chipprbots.ethereum.crypto.kec256
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

/** SNAP must not finalise while the bytecode of healed accounts is still missing, and must not claim bytecode recovery
  * is done when it is. (Devnet-8 block 318074: contracts created after the pivot arrived through healing, their code
  * was never requested, and `bytecodeRecoveryDone` was set regardless.)
  *
  * Drives the same entry the lazy-heal handoff uses (`HealingRootUnservable` -> `completeSnapSync()`), as
  * `SNAPLazyHealAnchorSpec` does.
  */
class SNAPHealedCodeFinalizeSpec extends ScalaTestWithActorTestKit() with AnyFlatSpecLike with Matchers with Eventually:

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = scaled(Span(10, Seconds)), interval = scaled(Span(50, Millis)))

  private val code = ByteString(Array[Byte](0x60, 0x00, 0x60, 0x00, 0xf3.toByte))
  private val codeHash = kec256(code)

  class Fixture extends EphemBlockchainTestSetup with TestSyncConfig:
    implicit override lazy val classicSystem: ActorSystem = SNAPHealedCodeFinalizeSpec.this.system.classicSystem

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

    def start(): TypedActorRef[SNAPSyncController.Command] =
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
          SNAPSyncConfig(deferredMerkleization = false, movingRootDeltaHeal = true),
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

  "SNAPSyncController" should
    "hold finalisation while the bytecode of a healed account is missing, then finalise with bytecodeRecoveryDone " +
    "set once it is stored" taggedAs UnitTest in new Fixture:
      val snap = start()
      snap ! SNAPSyncController.HealedCodeHashes(Seq(ByteString(codeHash)))
      snap ! SNAPSyncController.HealingRootUnservable(root) // lazy-heal handoff -> completeSnapSync()
      awaitProcessed(snap)

      parent.expectNoMessage(1.second) // held: nothing is finalised, nothing aborted
      appStateStorage.isSnapSyncDone() shouldBe false

      evmCodeStorage.put(ByteString(codeHash), code).commit() // the bytecode coordinator stored it
      snap ! SNAPSyncController.ByteCodeSyncComplete // ...and reports its queue drained

      parent.fishForMessage(10.seconds) {
        case SNAPSyncController.SnapSyncFinalized(p) if p == pivot => FishingOutcomes.complete
        case SyncProtocol.HealingImpossible                        => FishingOutcomes.fail("finalisation aborted")
        case _                                                     => FishingOutcomes.continueAndIgnore
      }
      appStateStorage.isSnapSyncDone() shouldBe true
      appStateStorage.isBytecodeRecoveryDone() shouldBe true

  it should
    "finalise when the wait runs out, but leave bytecodeRecoveryDone UNSET so the next start's recovery scan fetches " +
    "the missing code" taggedAs UnitTest in new Fixture:
      val snap = start()
      snap ! SNAPSyncController.HealedCodeHashes(Seq(ByteString(codeHash)))
      snap ! SNAPSyncController.HealingRootUnservable(root)
      awaitProcessed(snap)
      parent.expectNoMessage(500.millis)

      snap ! SNAPSyncController.HealedCodeWaitTimeout

      parent.fishForMessage(10.seconds) {
        case SNAPSyncController.SnapSyncFinalized(p) if p == pivot => FishingOutcomes.complete
        case _                                                     => FishingOutcomes.continueAndIgnore
      }
      appStateStorage.isSnapSyncDone() shouldBe true
      appStateStorage.isBytecodeRecoveryDone() shouldBe false
      appStateStorage.isStorageRecoveryDone() shouldBe true

  it should "finalise immediately, with bytecodeRecoveryDone set, when healing reported no missing bytecode" taggedAs UnitTest in new Fixture:
    val snap = start()
    snap ! SNAPSyncController.HealingRootUnservable(root)

    parent.fishForMessage(10.seconds) {
      case SNAPSyncController.SnapSyncFinalized(p) if p == pivot => FishingOutcomes.complete
      case _                                                     => FishingOutcomes.continueAndIgnore
    }
    appStateStorage.isBytecodeRecoveryDone() shouldBe true

  it should
    "not let a HealedCodeWaitTimeout from before a restart finalise the fresh sync" taggedAs UnitTest in new Fixture:
      val snap = start()
      snap ! SNAPSyncController.HealedCodeHashes(Seq(ByteString(codeHash)))
      snap ! SNAPSyncController.HealingRootUnservable(root) // enters the hold
      awaitProcessed(snap)
      parent.expectNoMessage(500.millis)

      snap ! SNAPSyncController.AccountTrieFinalizationFailed("test") // restartSnapSync: a fresh sync
      awaitProcessed(snap)

      snap ! SNAPSyncController.HealedCodeWaitTimeout // the old hold's timer firing late
      awaitProcessed(snap)
      val deadline = 1.second.fromNow
      var seen = List.empty[SyncProtocol.SyncControllerReply]
      while deadline.hasTimeLeft() do
        scala.util.Try(parent.receiveMessage(deadline.timeLeft.max(10.millis))).foreach(m => seen = m :: seen)
      seen.collect { case f: SNAPSyncController.SnapSyncFinalized => f } shouldBe empty
      appStateStorage.isSnapSyncDone() shouldBe false

  "SNAPSyncController.bytecodeRecoveryComplete" should
    "be false after a force-completed bytecode phase, or while healed-account code is missing" taggedAs UnitTest in {
      SNAPSyncController.bytecodeRecoveryComplete(0, bytecodePhaseForceCompleted = false) shouldBe true
      SNAPSyncController.bytecodeRecoveryComplete(0, bytecodePhaseForceCompleted = true) shouldBe false
      SNAPSyncController.bytecodeRecoveryComplete(3, bytecodePhaseForceCompleted = false) shouldBe false
    }
