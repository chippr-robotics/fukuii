package com.chipprbots.ethereum.blockchain.sync

import java.net.InetSocketAddress

import org.apache.pekko.actor.ActorRef
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.Props
import org.apache.pekko.testkit.TestActorRef
import org.apache.pekko.testkit.TestKit
import org.apache.pekko.testkit.TestProbe
import org.apache.pekko.util.ByteString

import scala.concurrent.Await
import scala.concurrent.duration._

import org.scalamock.scalatest.MockFactory
import org.scalatest.concurrent.Eventually
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Mocks
import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.LongPatience
import com.chipprbots.ethereum.consensus.validators.Validators
import com.chipprbots.ethereum.consensus.mining.TestMining
import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.ledger.VMImpl
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.{HandshakedPeers, PeerInfo, RemoteStatus}
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.PeerId
import com.chipprbots.ethereum.network.p2p.messages.Capability
import com.chipprbots.ethereum.testing.Tags._

/** Actor-level regression tests for SyncController.runningRecovery (Bugs 8 and 9).
  *
  * Uses TestActorRef[SyncController] to gain white-box access to the actor's context, allowing
  * direct injection of the runningRecovery behavior with controlled TestProbe recovery actors.
  *
  * Bug 8: Terminated without prior RecoveryComplete must persist the recovery-done flag.
  * Bug 9: RecoveryComplete must call context.unwatch to prevent spurious Terminated forwarding.
  */
class SyncControllerRecoverySpec
    extends AnyFlatSpec
    with Matchers
    with MockFactory
    with Eventually
    with LongPatience {

  // ─── Test harness ─────────────────────────────────────────────────────────

  class RecoveryTestSetup
      extends EphemBlockchainTestSetup
      with TestSyncPeers
      with TestSyncConfig {

    implicit override lazy val system: ActorSystem =
      ActorSystem("SyncControllerRecoverySpec_System")

    override lazy val vm: VMImpl             = new VMImpl
    override lazy val validators: Validators = new Mocks.MockValidatorsAlwaysSucceed
    override lazy val mining: TestMining     = buildTestMining().withValidators(validators)

    val networkPeerManagerProbe: TestProbe    = TestProbe()
    val peerEventBus: TestProbe               = TestProbe()
    val pendingTransactionsManager: TestProbe = TestProbe()
    val ommersPool: TestProbe                 = TestProbe()
    val blacklist: CacheBasedBlacklist        = CacheBasedBlacklist.empty(100)

    lazy val appStateStorage = storagesInstance.storages.appStateStorage

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

    /** Build a SNAP-capable `(Peer, PeerInfo)` pair backed by a TestProbe. */
    def makeSnapPeer(id: String): (Peer, PeerInfo) = {
      val probe  = TestProbe()
      val peer   = Peer(PeerId(id), new InetSocketAddress("127.0.0.99", 30303), probe.ref, false)
      val status = RemoteStatus(
        Capability.ETH66, 1,
        ChainWeight.totalDifficultyOnly(99),
        ByteString(s"$id-best"), ByteString("genesis"),
        supportsSnap = true
      )
      val info = PeerInfo(status, status.chainWeight, forkAccepted = true, 99, status.bestHash)
      (peer, info)
    }

    /** Directly inject the controller into runningRecovery with the given actors watched. */
    def putIntoRecovery(
        bytecodeActor: Option[ActorRef],
        storageActor: Option[ActorRef],
        bytecodeComplete: Boolean = false,
        storageComplete: Boolean = false
    ): Unit = {
      val ua = ctrl.underlyingActor
      bytecodeActor.foreach(ua.context.watch)
      storageActor.foreach(ua.context.watch)
      ua.context.become(ua.runningRecovery(
        bytecodeActor, storageActor, bytecodeComplete, storageComplete
      ))
    }

    def cleanup(): Unit = Await.result(system.terminate(), 10.seconds)
  }

  def withSetup(test: RecoveryTestSetup => Any): Unit = {
    val s = new RecoveryTestSetup
    try test(s)
    finally s.cleanup()
  }

  // ─── Tests ────────────────────────────────────────────────────────────────

  "SyncController.runningRecovery" should
    "transition to wait state after first RecoveryComplete, then start regular sync on second" taggedAs UnitTest in withSetup { s =>
      import s._

      val bytecodeProbe = TestProbe()
      val storageProbe  = TestProbe()
      putIntoRecovery(Some(bytecodeProbe.ref), Some(storageProbe.ref))

      ctrl.receive(BytecodeRecoveryActor.RecoveryComplete, bytecodeProbe.ref)
      ctrl.children.exists(_.path.name.startsWith("regular-sync")) shouldBe false

      ctrl.receive(StorageRecoveryActor.RecoveryComplete, storageProbe.ref)

      eventually(timeout(3.seconds), interval(50.millis)) {
        ctrl.children.exists(_.path.name.startsWith("regular-sync")) shouldBe true
      }
    }

  it should
    "not start regular sync when only one actor has completed" taggedAs UnitTest in withSetup { s =>
      import s._

      val bytecodeProbe = TestProbe()
      val storageProbe  = TestProbe()
      putIntoRecovery(Some(bytecodeProbe.ref), Some(storageProbe.ref))

      ctrl.receive(BytecodeRecoveryActor.RecoveryComplete, bytecodeProbe.ref)

      ctrl.children.exists(_.path.name.startsWith("regular-sync")) shouldBe false
    }

  it should
    "call context.unwatch on BytecodeRecoveryActor.RecoveryComplete so Terminated is not forwarded to storageActor (Bug 9)" taggedAs UnitTest in withSetup { s =>
      import s._

      val bytecodeProbe = TestProbe()
      val storageProbe  = TestProbe()
      putIntoRecovery(Some(bytecodeProbe.ref), Some(storageProbe.ref))

      // RecoveryComplete — controller must unwatch the bytecode actor
      ctrl.receive(BytecodeRecoveryActor.RecoveryComplete, bytecodeProbe.ref)

      // Stop the bytecode probe. If unwatch was called, no Terminated arrives at the controller.
      // If Terminated DID arrive, the runningRecovery catch-all forwards it to storageActor.
      system.stop(bytecodeProbe.ref)

      storageProbe.expectNoMessage(400.millis)
    }

  it should
    "call context.unwatch on StorageRecoveryActor.RecoveryComplete (Bug 9 — storage side)" taggedAs UnitTest in withSetup { s =>
      import s._

      val bytecodeProbe = TestProbe()
      val storageProbe  = TestProbe()
      putIntoRecovery(Some(bytecodeProbe.ref), Some(storageProbe.ref))

      ctrl.receive(StorageRecoveryActor.RecoveryComplete, storageProbe.ref)

      system.stop(storageProbe.ref)

      bytecodeProbe.expectNoMessage(400.millis)
    }

  it should
    "persist bytecodeRecoveryDone flag when bytecode actor terminates without RecoveryComplete (Bug 8)" taggedAs UnitTest in withSetup { s =>
      import s._

      val bytecodeProbe = TestProbe()
      val storageProbe  = TestProbe()
      putIntoRecovery(Some(bytecodeProbe.ref), Some(storageProbe.ref))

      appStateStorage.isBytecodeRecoveryDone() shouldBe false

      system.stop(bytecodeProbe.ref)

      eventually(timeout(2.seconds), interval(50.millis)) {
        appStateStorage.isBytecodeRecoveryDone() shouldBe true
      }
    }

  it should
    "persist storageRecoveryDone flag when storage actor terminates without RecoveryComplete (Bug 8)" taggedAs UnitTest in withSetup { s =>
      import s._

      val bytecodeProbe = TestProbe()
      val storageProbe  = TestProbe()
      putIntoRecovery(Some(bytecodeProbe.ref), Some(storageProbe.ref))

      appStateStorage.isStorageRecoveryDone() shouldBe false

      system.stop(storageProbe.ref)

      eventually(timeout(2.seconds), interval(50.millis)) {
        appStateStorage.isStorageRecoveryDone() shouldBe true
      }
    }

  it should
    "persist both flags and start regular sync when both actors crash unexpectedly (Bug 8)" taggedAs UnitTest in withSetup { s =>
      import s._

      val bytecodeProbe = TestProbe()
      val storageProbe  = TestProbe()
      putIntoRecovery(Some(bytecodeProbe.ref), Some(storageProbe.ref))

      system.stop(bytecodeProbe.ref)
      eventually(timeout(2.seconds), interval(50.millis)) {
        appStateStorage.isBytecodeRecoveryDone() shouldBe true
      }

      system.stop(storageProbe.ref)
      eventually(timeout(2.seconds), interval(50.millis)) {
        appStateStorage.isStorageRecoveryDone() shouldBe true
      }

      eventually(timeout(3.seconds), interval(50.millis)) {
        ctrl.children.exists(_.path.name.startsWith("regular-sync")) shouldBe true
      }
    }

  it should
    "forward snap-capable peers to both active recovery actors via HandshakedPeers" taggedAs UnitTest in withSetup { s =>
      import s._

      val bytecodeProbe = TestProbe()
      val storageProbe  = TestProbe()
      putIntoRecovery(Some(bytecodeProbe.ref), Some(storageProbe.ref))

      val (snapPeer, snapPeerInfo) = makeSnapPeer("snap-recovery-peer")

      ctrl.receive(HandshakedPeers(Map(snapPeer -> snapPeerInfo)))

      bytecodeProbe.expectMsg(1.second, snap.actors.Messages.ByteCodePeerAvailable(snapPeer))
      storageProbe.expectMsg(1.second, snap.actors.Messages.StoragePeerAvailable(snapPeer))
    }
}
