package com.chipprbots.ethereum.blockchain.sync.snap

import java.util.concurrent.atomic.AtomicInteger

import org.apache.pekko.actor.ActorRef
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.testkit.typed.scaladsl.FishingOutcomes
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe as TypedTestProbe
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
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.blockchain.sync.StoredBodyCheckSpec
import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.domain.TrieRoot
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.GetHandshakedPeersCmd
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.HandshakedPeers
import com.chipprbots.ethereum.network.PeerEventBusActor
import com.chipprbots.ethereum.testing.Tags.*

/** CQ-020-07 regression: SNAP finalization wrote `Block(pivotHeader, BlockBody.empty)`, overwriting the body a
  * `newPayload … ACCEPTED (parent unknown)` had stored for the CL-chosen pivot (Ephemery 137,594: 16 withdrawals lost,
  * 639 bytes served instead of 1,154). Finalization must keep a valid stored body and never write a placeholder.
  *
  * Drives the same entry `SNAPHealedCodeFinalizeSpec` uses (`HealingRootUnservable` -> `completeSnapSync()`).
  */
class SNAPPivotBodyFinalizeSpec extends ScalaTestWithActorTestKit() with AnyFlatSpecLike with Matchers with Eventually:

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = scaled(Span(10, Seconds)), interval = scaled(Span(50, Millis)))

  class Fixture extends EphemBlockchainTestSetup with TestSyncConfig:
    implicit override lazy val classicSystem: ActorSystem = SNAPPivotBodyFinalizeSpec.this.system.classicSystem

    val appStateStorage = storagesInstance.storages.appStateStorage
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

    val root = ByteString(Array.fill(32)(0x11.toByte))

    /** Ephemery 137,594's shape: no transactions, 16 withdrawals. */
    val pivotHeader: BlockHeader = StoredBodyCheckSpec.withdrawalsPivot.copy(stateRoot = TrieRoot(root))
    val pivot: BigInt = pivotHeader.number.value
    val pivotBody: BlockBody = StoredBodyCheckSpec.withdrawalsBody

    /** `storedBody`: what is under the pivot hash before finalization. */
    def finalizeWith(storedBody: Option[BlockBody]): Unit =
      val genesis = Fixtures.Blocks.Genesis.header
      blockchainWriter.save(
        Block(genesis, BlockBody.empty),
        Nil,
        ChainWeight.totalDifficultyOnly(genesis.difficulty.value),
        saveAsBestBlock = true
      )
      blockchainWriter.storeBlockHeader(pivotHeader).commit() // PivotHeaderBootstrap
      storedBody.foreach(b => blockchainWriter.storeBlockBody(pivotHeader.hash, b).commit())
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
          storagesInstance.storages.evmCodeStorage,
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
      snap ! SNAPSyncController.HealingRootUnservable(root) // lazy-heal handoff -> completeSnapSync()
      parent.fishForMessage(10.seconds) {
        case SNAPSyncController.SnapSyncFinalized(p) if p == pivot => FishingOutcomes.complete
        case SyncProtocol.HealingImpossible                        => FishingOutcomes.fail("finalisation aborted")
        case _                                                     => FishingOutcomes.continueAndIgnore
      }
      appStateStorage.isSnapSyncDone() shouldBe true
      // The number->hash mapping finalization exists to write is still written.
      blockchainReader.getBlockHeaderByNumber(pivot).map(_.hash) shouldBe Some(pivotHeader.hash)

  "SNAP finalization" should "keep the pivot body a newPayload stored (withdrawals intact)" taggedAs UnitTest in new Fixture:
    finalizeWith(Some(pivotBody))
    blockchainReader.getBlockBodyByHash(pivotHeader.hash) shouldBe Some(pivotBody)
    blockchainReader.getBlockByNumber(blockchainReader.getBestBranch, pivot).map(_.body) shouldBe Some(pivotBody)

  it should "leave an unknown pivot body absent for the backfill, never write a placeholder" taggedAs UnitTest in new Fixture:
    finalizeWith(None)
    blockchainReader.getBlockBodyByHash(pivotHeader.hash) shouldBe None

  it should "remove a placeholder body an earlier run left under the pivot" taggedAs UnitTest in new Fixture:
    finalizeWith(Some(BlockBody.empty))
    blockchainReader.getBlockBodyByHash(pivotHeader.hash) shouldBe None
