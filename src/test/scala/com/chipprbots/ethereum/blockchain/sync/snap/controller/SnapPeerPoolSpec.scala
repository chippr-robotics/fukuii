package com.chipprbots.ethereum.blockchain.sync.snap.controller

import org.apache.pekko.actor.testkit.typed.Effect
import org.apache.pekko.actor.testkit.typed.scaladsl.BehaviorTestKit
import org.apache.pekko.actor.testkit.typed.scaladsl.TestInbox
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.scaladsl.ActorContext
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.scaladsl.TimerScheduler

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.CacheBasedBlacklist
import com.chipprbots.ethereum.blockchain.sync.PeerListHelper
import com.chipprbots.ethereum.blockchain.sync.SyncProtocol
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPRequestTracker
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncConfig
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController.*
import com.chipprbots.ethereum.blockchain.sync.snap.SnapIntakeBudget
import com.chipprbots.ethereum.blockchain.sync.snap.StateValidator
import com.chipprbots.ethereum.blockchain.sync.snap.SyncProgressMonitor
import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.db.storage.FlatSlotStorage
import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.db.storage.PathNodeStorage
import com.chipprbots.ethereum.db.storage.StateStorage
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.domain.BlockchainWriter
import com.chipprbots.ethereum.domain.TrieRoot
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.PeerEventBusActor
import com.chipprbots.ethereum.testing.Tags.UnitTest
import com.chipprbots.ethereum.utils.Config.SyncConfig

/** Everything `SnapPeerPool` needs, as a stub (spec 016 FR-016 (b)): its `SnapPeerPoolState`, the hubs, the environment
  * and the coordinator handles. State is plain vars; the peer list is a real, empty `PeerListHelper`; the peer manager
  * and the handshaked-peers adapter are test inboxes; the members these tests never reach throw.
  */
private[snap] class StubSnapPeerPoolState(
    val ctx: ActorContext[SNAPSyncController.Command],
    val timers: TimerScheduler[SNAPSyncController.Command],
    val snapSyncConfig: SNAPSyncConfig,
    val networkPeerManager: ActorRef[NetworkPeerManagerActor.Command],
    val handshakedPeersAdapter: ActorRef[NetworkPeerManagerActor.HandshakedPeers],
    val peerListHelper: PeerListHelper
) extends SnapPeerPoolState
    with SnapSharedState
    with SnapControllerEnv
    with CoordinatorHandles:

  // SnapPeerPoolState (peerListHelper and handshakedPeersAdapter are constructor vals)
  var bestEth68PeerForCalibration: Option[(BigInt, BigInt)] = None
  var snapPeerEvictionStarted: Boolean = false
  var snapServerPeersSchedulerStarted: Boolean = false
  val BootstrapCheckKey: String = "bootstrap-check"

  // SnapSharedState
  var pivotBlock: Option[BigInt] = None
  var stateRoot: Option[TrieRoot] = None
  var currentPhase: SyncPhase = SyncPhase.AccountRangeSync
  def progressMonitor: SyncProgressMonitor = notUsed("progressMonitor")
  def requestTracker: SNAPRequestTracker = notUsed("requestTracker")

  // SnapControllerEnv (ctx, timers, snapSyncConfig and networkPeerManager are constructor vals)
  def syncConfig: SyncConfig = notUsed("syncConfig")
  def asyncLog: org.slf4j.Logger = notUsed("asyncLog")
  def appStateStorage: AppStateStorage = notUsed("appStateStorage")
  def stateStorage: StateStorage = notUsed("stateStorage")
  def evmCodeStorage: EvmCodeStorage = notUsed("evmCodeStorage")
  def flatSlotStorage: FlatSlotStorage = notUsed("flatSlotStorage")
  def validatorFactory: MptStorage => StateValidator = notUsed("validatorFactory")
  def snapValidationEc: ExecutionContext = notUsed("snapValidationEc")
  def blockchainReader: BlockchainReader = notUsed("blockchainReader")
  def blockchainWriter: BlockchainWriter = notUsed("blockchainWriter")
  def peerEventBus: ActorRef[PeerEventBusActor.Command] = notUsed("peerEventBus")
  def syncController: ActorRef[SyncProtocol.SyncControllerReply] = notUsed("syncController")
  def childFactories: ChildFactories = notUsed("childFactories")
  def pathNodeStorageOpt: Option[PathNodeStorage] = notUsed("pathNodeStorageOpt")

  // CoordinatorHandles (no coordinator exists, so the fan-outs send nothing)
  def intakeBudget: SnapIntakeBudget = notUsed("intakeBudget")
  protected def stopChild(child: ActorRef[Nothing]): Unit = notUsed("stopChild")

  private def notUsed(what: String): Nothing =
    throw new UnsupportedOperationException(s"$what is not reached by SnapPeerPoolSpec")

/** Spec 016 M3 stub test (FR-016 (b), T044): `SnapPeerPool` mixed into a stub of its state interface and capabilities,
  * inside a `BehaviorTestKit` so that `ctx.log` and the timers are real (timers are recorded as effects). Each module
  * call runs while the kit processes a message, as it would in the controller. The scheduler cases read and write
  * `snapPeerEvictionStarted` / `snapServerPeersSchedulerStarted`; the dialling and poll cases read the peer list and
  * the adapter from `SnapPeerPoolState`.
  */
class SnapPeerPoolSpec extends AnyFlatSpec with Matchers:

  private val snapServerPeer = new java.net.URI("enode://" + ("ab" * 64) + "@127.0.0.1:30303")

  /** The module on its stub, the kit that runs it, and the inboxes it talks to. */
  final private class Harness(config: SNAPSyncConfig):
    val peerManager: TestInbox[NetworkPeerManagerActor.Command] = TestInbox[NetworkPeerManagerActor.Command]()
    val adapter: TestInbox[NetworkPeerManagerActor.HandshakedPeers] =
      TestInbox[NetworkPeerManagerActor.HandshakedPeers]()
    private val peerListHelper = new PeerListHelper(
      TestInbox[PeerEventBusActor.Command]().ref,
      CacheBasedBlacklist.empty(100),
      TestInbox[PeerEventBusActor.PeerEvent]().ref,
      org.slf4j.LoggerFactory.getLogger(getClass)
    )
    private var action: () => Unit = () => ()
    private var captured: StubSnapPeerPoolState & SnapPeerPool = null
    val kit: BehaviorTestKit[Command] = BehaviorTestKit(Behaviors.setup[Command] { ctx =>
      Behaviors.withTimers { timers =>
        captured = new StubSnapPeerPoolState(ctx, timers, config, peerManager.ref, adapter.ref, peerListHelper)
          with SnapPeerPool
        Behaviors.receiveMessage { _ =>
          action()
          Behaviors.same
        }
      }
    })
    val module: StubSnapPeerPoolState & SnapPeerPool = captured

    /** Run `f` on the module while the kit processes a message. */
    def call(f: StubSnapPeerPoolState & SnapPeerPool => Unit): Unit =
      action = () => f(module)
      kit.run(PollHandshakedPeers)

    /** The timers started since the last look, as (key, message, interval). */
    def timersStarted(): Seq[(Any, Any, FiniteDuration)] =
      kit.retrieveAllEffects().collect { case t: Effect.TimerScheduled[?] => (t.key, t.msg, t.delay) }

  "SnapPeerPool" should "start the eviction timer once and record it in snapPeerEvictionStarted" taggedAs UnitTest in {
    val config = SNAPSyncConfig()
    val h = new Harness(config)

    h.call(_.startSnapPeerEviction())

    h.module.snapPeerEvictionStarted shouldBe true
    h.timersStarted() shouldBe Seq((EvictNonSnapPeers, EvictNonSnapPeers, config.snapPeerEvictionInterval))

    h.call(_.startSnapPeerEviction())

    h.module.snapPeerEvictionStarted shouldBe true
    h.timersStarted() shouldBe empty
  }

  it should "start the eviction timer again only after snapPeerEvictionStarted is cleared" taggedAs UnitTest in {
    val config = SNAPSyncConfig()
    val h = new Harness(config)
    h.module.snapPeerEvictionStarted = true

    h.call(_.startSnapPeerEviction())
    h.timersStarted() shouldBe empty

    // What stopSnapOnlySchedules does in the core.
    h.module.snapPeerEvictionStarted = false
    h.call(_.startSnapPeerEviction())

    h.module.snapPeerEvictionStarted shouldBe true
    h.timersStarted() shouldBe Seq((EvictNonSnapPeers, EvictNonSnapPeers, config.snapPeerEvictionInterval))
  }

  it should "start the snap-server reconnect timer once, and only when snap-server peers are configured" taggedAs UnitTest in {
    val none = new Harness(SNAPSyncConfig())
    none.call(_.startSnapServerPeersScheduler())

    none.module.snapServerPeersSchedulerStarted shouldBe false
    none.timersStarted() shouldBe empty

    val h = new Harness(SNAPSyncConfig(snapServerPeers = List(snapServerPeer)))
    h.call(_.startSnapServerPeersScheduler())

    h.module.snapServerPeersSchedulerStarted shouldBe true
    h.timersStarted() shouldBe Seq((EnsureSnapServerPeersConnected, EnsureSnapServerPeersConnected, 30.seconds))

    h.call(_.startSnapServerPeersScheduler())
    h.timersStarted() shouldBe empty
  }

  it should "dial a configured snap-server peer missing from the peer list once, then hold off" taggedAs UnitTest in {
    val h = new Harness(SNAPSyncConfig(snapServerPeers = List(snapServerPeer)))

    h.call(_.ensureSnapServerPeersConnected())
    h.peerManager.receiveAll() shouldBe Seq(NetworkPeerManagerActor.ConnectToPeerForwardCmd(snapServerPeer))

    // Within the 60 s suppression window the same peer is not dialled again.
    h.call(_.ensureSnapServerPeersConnected())
    h.peerManager.hasMessages shouldBe false
  }

  it should "poll the peer manager for handshaked peers with the adapter from its state" taggedAs UnitTest in {
    val h = new Harness(SNAPSyncConfig())

    h.call(_.pollHandshakedPeers())

    h.peerManager.receiveAll() shouldBe Seq(NetworkPeerManagerActor.GetHandshakedPeersCmd(h.adapter.ref))
  }
