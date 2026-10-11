package com.chipprbots.ethereum.blockchain.sync.snap.controller

import java.net.InetSocketAddress

import org.apache.pekko.actor.testkit.typed.Effect
import org.apache.pekko.actor.testkit.typed.scaladsl.BehaviorTestKit
import org.apache.pekko.actor.testkit.typed.scaladsl.TestInbox
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.ActorContext
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.scaladsl.TimerScheduler
import org.apache.pekko.util.ByteString

import scala.collection.mutable
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*
import scala.util.boundary

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.blockchain.sync.PeerListSupportNg
import com.chipprbots.ethereum.blockchain.sync.SyncProtocol
import com.chipprbots.ethereum.blockchain.sync.TestSyncConfig
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
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.BlockBody
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.domain.BlockchainWriter
import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.domain.TrieRoot
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.PeerInfo
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.RemoteStatus
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.PeerActor
import com.chipprbots.ethereum.network.PeerEventBusActor
import com.chipprbots.ethereum.network.PeerId
import com.chipprbots.ethereum.network.p2p.messages.Capability
import com.chipprbots.ethereum.testing.Tags.UnitTest
import com.chipprbots.ethereum.utils.Config.SyncConfig

/** Everything `PivotSelector` needs, as a stub (spec 016 FR-016 (b)): its `PivotSelectorState`, the hubs, the
  * environment, the phase flags and the callee Api traits. State is plain vars; the SNAP peers are a settable map; the
  * peer manager is a test inbox; the chain and app-state stores are ephemeral; the Api calls are recorded; the members
  * these tests never reach throw.
  */
private[snap] class StubPivotSelectorState(
    val ctx: ActorContext[SNAPSyncController.Command],
    val timers: TimerScheduler[SNAPSyncController.Command],
    val snapSyncConfig: SNAPSyncConfig,
    val networkPeerManager: ActorRef[NetworkPeerManagerActor.Command],
    chain: EphemBlockchainTestSetup & TestSyncConfig
) extends PivotSelectorState
    with SnapSharedState
    with SnapControllerEnv
    with PhaseFlags
    with PeerPoolApi
    with HandshakedPeersApi
    with BootstrapPeersApi
    with HealingApi
    with LifecycleApi
    with ShutdownApi
    with SnapStartApi
    with SyncingApi
    with AccountRangeStartApi
    with CoordinatorHandles
    with StagnationResetApi
    with PivotRefreshCommitApi:

  // PivotSelectorState
  var clPivotHint: Option[CLPivotHint] = None
  var clHintArrivedAtMs: Option[Long] = None
  var bootstrapRetryCount: Int = 0
  var posChain: Boolean = false
  def isPoSChain: Boolean = posChain
  val bigIntReverseOrdering: Ordering[BigInt] = Ordering[BigInt].reverse
  def BootstrapCheckKey: String = "bootstrap-check"
  val forwardedPeerEvents: mutable.ArrayBuffer[Command] = mutable.ArrayBuffer.empty
  lazy val peerEventArms: PartialFunction[Command, Behavior[Command]] = { case FlushPeerDisconnects =>
    forwardedPeerEvents += FlushPeerDisconnects
    Behaviors.same
  }
  var minPivotHint: BigInt = BigInt(0)
  var pendingPivotRefresh: Option[(BigInt, String)] = None

  // SnapSharedState
  var pivotBlock: Option[BigInt] = None
  var stateRoot: Option[TrieRoot] = None
  var currentPhase: SyncPhase = SyncPhase.Idle
  def progressMonitor: SyncProgressMonitor = notUsed("progressMonitor")
  def requestTracker: SNAPRequestTracker = notUsed("requestTracker")

  // SnapControllerEnv (ctx, timers, snapSyncConfig and networkPeerManager are constructor vals)
  def syncConfig: SyncConfig = chain.syncConfig
  def asyncLog: org.slf4j.Logger = notUsed("asyncLog")
  val appStateStorage: AppStateStorage = chain.storagesInstance.storages.appStateStorage
  def stateStorage: StateStorage = notUsed("stateStorage")
  def evmCodeStorage: EvmCodeStorage = notUsed("evmCodeStorage")
  def flatSlotStorage: FlatSlotStorage = notUsed("flatSlotStorage")
  def validatorFactory: MptStorage => StateValidator = notUsed("validatorFactory")
  def snapValidationEc: ExecutionContext = notUsed("snapValidationEc")
  def blockchainReader: BlockchainReader = chain.blockchainReader
  def blockchainWriter: BlockchainWriter = chain.blockchainWriter
  def peerEventBus: ActorRef[PeerEventBusActor.Command] = notUsed("peerEventBus")
  var syncControllerRef: Option[ActorRef[SyncProtocol.SyncControllerReply]] = None
  def syncController: ActorRef[SyncProtocol.SyncControllerReply] =
    syncControllerRef.getOrElse(notUsed("syncController"))
  def childFactories: ChildFactories = notUsed("childFactories")
  def pathNodeStorageOpt: Option[PathNodeStorage] = None

  // Callee Api traits
  var peers: Map[PeerId, PeerListSupportNg.PeerWithInfo] = Map.empty
  def peersToDownloadFrom: Map[PeerId, PeerListSupportNg.PeerWithInfo] = peers
  def calibratePivotTD(pivotBlockNumber: BigInt): Option[BigInt] = None
  def handshakedPeers: Map[PeerId, PeerListSupportNg.PeerWithInfo] = peers
  def handleHandshakedPeersBootstrapReactivity(peers: Map[Peer, PeerInfo]): Unit =
    notUsed("handleHandshakedPeersBootstrapReactivity")
  def evictNonSnapPeers(): Unit = notUsed("evictNonSnapPeers")
  def triggerHealingForMissingNodes(missingNodes: Seq[ByteString]): Unit = notUsed("triggerHealingForMissingNodes")
  var healingStarts: Int = 0
  def startStateHealing(): Unit = healingStarts += 1
  def recordCriticalFailure(reason: String): Boolean = notUsed("recordCriticalFailure")
  val dormantEntries: mutable.ArrayBuffer[String] = mutable.ArrayBuffer.empty
  def enterDormantMode(reason: String): Behavior[Command] =
    dormantEntries += reason
    Behaviors.same
  def restartSnapSync(reason: String): Behavior[Command] = notUsed("restartSnapSync")
  def onStop(): Unit = notUsed("onStop")
  var snapSyncStarts: Int = 0
  def startSnapSync(): Behavior[Command] =
    snapSyncStarts += 1
    Behaviors.same
  var syncingEntries: Int = 0
  def syncing(): Behavior[Command] =
    syncingEntries += 1
    Behaviors.same
  val accountRangeStarts: mutable.ArrayBuffer[TrieRoot] = mutable.ArrayBuffer.empty
  def startAccountRangeSync(rootHash: TrieRoot): Unit = accountRangeStarts += rootHash
  val stagnationResets: mutable.ArrayBuffer[StagnationWatchdog.ResetReason] = mutable.ArrayBuffer.empty
  def resetStagnationClocks(reason: StagnationWatchdog.ResetReason): Unit = stagnationResets += reason
  val pivotRefreshCommits: mutable.ArrayBuffer[(BigInt, BlockHeader, String)] = mutable.ArrayBuffer.empty
  def completePivotRefreshWithStateRoot(newPivotBlock: BigInt, newPivotHeader: BlockHeader, reason: String): Unit =
    pivotRefreshCommits += ((newPivotBlock, newPivotHeader, reason))

  // CoordinatorHandles (the ChainDownloader handle is real and empty, so `tell` is a no-op)
  def intakeBudget: SnapIntakeBudget = notUsed("intakeBudget")
  protected def stopChild(child: ActorRef[Nothing]): Unit = notUsed("stopChild")

  private def notUsed(what: String): Nothing =
    throw new UnsupportedOperationException(s"$what is not reached by PivotSelectorSpec")

/** Spec 016 M8 stub test (FR-016 (b), T050): `PivotSelector` mixed into a stub of its state interface, capabilities and
  * callee Api traits, inside a `BehaviorTestKit` so that `ctx.log` and the timers are real (timers are recorded as
  * effects). The bootstrap cases run `bootstrapping()` as the kit's own behaviour; the others run a module call while
  * the kit processes a message, as it would in the controller. Nothing waits on a clock: the bootstrap backoff is a
  * recorded timer, and the one wall-clock read (the CL hint's arrival time) is only checked against a fixed value the
  * test sets. The bootstrap completion reads and writes `bootstrapRetryCount`, `pivotBlock`, `stateRoot` and
  * `currentPhase`; the failure path reads and writes `bootstrapRetryCount`; the CL hint writes `clPivotHint` and
  * `clHintArrivedAtMs`.
  */
class PivotSelectorSpec extends AnyFlatSpec with Matchers:

  private val header: BlockHeader = Fixtures.Blocks.ValidBlock.header
  private val pivot: BigInt = header.number.value

  /** The module on its stub, the kit that runs it, and the peer manager inbox. */
  final private class Harness(bootstrapping: Boolean = false):
    val peerManager: TestInbox[NetworkPeerManagerActor.Command] = TestInbox[NetworkPeerManagerActor.Command]()
    val parent: TestInbox[SyncProtocol.SyncControllerReply] = TestInbox[SyncProtocol.SyncControllerReply]()
    private val chain = new EphemBlockchainTestSetup with TestSyncConfig {}
    private var action: () => Unit = () => ()
    private var captured: StubPivotSelectorState & PivotSelector = null
    val kit: BehaviorTestKit[Command] = BehaviorTestKit(Behaviors.setup[Command] { ctx =>
      Behaviors.withTimers { timers =>
        captured = new StubPivotSelectorState(ctx, timers, SNAPSyncConfig(), peerManager.ref, chain) with PivotSelector
        captured.syncControllerRef = Some(parent.ref)
        if bootstrapping then captured.bootstrapping()
        else
          Behaviors.receiveMessage { _ =>
            action()
            Behaviors.same
          }
      }
    })
    val module: StubPivotSelectorState & PivotSelector = captured

    /** Run `f` on the module while the kit processes a message. */
    def call(f: StubPivotSelectorState & PivotSelector => Unit): Unit =
      action = () => f(module)
      kit.run(PollHandshakedPeers)

    /** Run `startSnapSync`'s two pivot steps under a boundary of the core's type, as `startSnapSync` does: the Label
      * reaches the module's members as their `using` parameter. Yields true when both steps fell through.
      */
    def startSteps(): Boolean =
      var fellThrough = false
      call { m =>
        boundary[Behavior[Command]] {
          m.resumeInterruptedBootstrap()
          m.selectPivotAndStart()
          fellThrough = true
          Behaviors.same
        }
      }
      fellThrough

    def scheduledTimers(): Seq[(Any, Command, FiniteDuration)] =
      kit.retrieveAllEffects().collect { case t: Effect.TimerScheduled[?] =>
        (t.key, t.msg.asInstanceOf[Command], t.delay)
      }

  private def peer(id: String, best: BigInt, snap: Boolean = true): (PeerId, PeerListSupportNg.PeerWithInfo) =
    val p = Peer(PeerId(id), new InetSocketAddress("127.0.0.1", 1), TestInbox[PeerActor.Command]().ref, false)
    val status = RemoteStatus(
      capability = Capability.ETH68,
      networkId = 1,
      chainWeight = ChainWeight.zero,
      bestHash = ByteString("head"),
      genesisHash = ByteString("genesis"),
      supportsSnap = snap
    )
    val info =
      PeerInfo(status, ChainWeight.zero, forkAccepted = true, maxBlockNumber = best, bestBlockHash = status.bestHash)
    p.id -> PeerListSupportNg.PeerWithInfo(p, info)

  "PivotSelector" should "commit a fresh bootstrapped pivot, record it as the best block and start account sync" taggedAs UnitTest in {
    val h = new Harness(bootstrapping = true)
    h.module.bootstrapRetryCount = 3
    h.module.appStateStorage.putSnapSyncBootstrapTarget(pivot).commit()
    h.module.blockchainWriter.storeChainWeight(header.hash, ChainWeight.totalDifficultyOnly(BigInt(7))).commit()

    h.kit.run(BootstrapComplete(Some(header)))

    h.module.bootstrapRetryCount shouldBe 0
    h.module.pivotBlock shouldBe Some(pivot)
    h.module.stateRoot shouldBe Some(header.stateRoot)
    h.module.appStateStorage.getSnapSyncPivotBlock() shouldBe Some(pivot)
    h.module.appStateStorage.getSnapSyncStateRoot() shouldBe Some(header.stateRoot.value)
    h.module.appStateStorage.getSnapSyncBootstrapTarget() shouldBe None
    h.module.appStateStorage.getBestBlockNumber() shouldBe pivot
    h.module.blockchainReader.getBlockHeaderByHash(header.hash) shouldBe Some(header)
    h.module.currentPhase shouldBe SyncPhase.AccountRangeSync
    h.module.accountRangeStarts shouldBe Seq(header.stateRoot)
    h.module.healingStarts shouldBe 0
    h.module.syncingEntries shouldBe 1
    h.module.snapSyncStarts shouldBe 0
  }

  it should "go straight to healing on the fresh pivot when every download phase is complete" taggedAs UnitTest in {
    val h = new Harness(bootstrapping = true)
    h.module.accountsComplete = true
    h.module.storagePhaseComplete = true
    h.module.bytecodePhaseComplete = true
    h.module.blockchainWriter.storeChainWeight(header.hash, ChainWeight.totalDifficultyOnly(BigInt(7))).commit()

    h.kit.run(BootstrapComplete(Some(header)))

    h.module.pivotBlock shouldBe Some(pivot)
    h.module.currentPhase shouldBe SyncPhase.StateHealing
    h.module.healingStarts shouldBe 1
    h.module.accountRangeStarts shouldBe empty
    h.module.syncingEntries shouldBe 1
  }

  it should "re-select the pivot rather than commit one the SNAP peers have moved far beyond" taggedAs UnitTest in {
    val h = new Harness(bootstrapping = true)
    h.module.peers = Map(peer("ahead", pivot + 10_000))

    h.kit.run(BootstrapComplete(Some(header)))

    h.module.snapSyncStarts shouldBe 1
    h.module.pivotBlock shouldBe None
    h.module.stateRoot shouldBe None
    h.module.appStateStorage.getSnapSyncPivotBlock() shouldBe None
    h.module.syncingEntries shouldBe 0
  }

  it should "back off after a failed initial bootstrap and retry the start on RetrySnapSyncStart" taggedAs UnitTest in {
    val h = new Harness(bootstrapping = true)

    h.kit.run(PivotBootstrapFailed("no header"))

    h.module.bootstrapRetryCount shouldBe 1
    h.scheduledTimers() shouldBe Seq(("bootstrap-check", RetrySnapSyncStart, 4.seconds))
    h.module.dormantEntries shouldBe empty

    h.kit.run(RetrySnapSyncStart)

    h.module.snapSyncStarts shouldBe 1
  }

  it should "give up into dormant mode on the tenth failed bootstrap, without another retry" taggedAs UnitTest in {
    val h = new Harness(bootstrapping = true)
    h.module.bootstrapRetryCount = 9

    h.kit.run(PivotBootstrapFailed("no header"))

    h.module.bootstrapRetryCount shouldBe 10
    h.module.dormantEntries.size shouldBe 1
    h.module.dormantEntries.head should include("10 bootstrap retries")
    h.scheduledTimers() shouldBe empty
  }

  it should "buffer a CL head hint, stamp its arrival once and forward a known head number" taggedAs UnitTest in {
    val h = new Harness()
    val known = CLPivotHint(header.hash.value, Some(header))

    h.call(_.handleCLPivotHint(known))

    h.module.clPivotHint shouldBe Some(known)
    h.module.clHintArrivedAtMs shouldBe defined
    h.peerManager.receiveAll() shouldBe Seq(NetworkPeerManagerActor.UpdateClHeadCmd(pivot))

    h.module.clHintArrivedAtMs = Some(1L)
    h.call(_.handleCLPivotHint(known))

    h.module.clHintArrivedAtMs shouldBe Some(1L)
    h.peerManager.receiveAll() shouldBe Seq(NetworkPeerManagerActor.UpdateClHeadCmd(pivot))

    val byHash = CLPivotHint(ByteString("newer-head"), None)
    h.call(_.handleCLPivotHint(byHash))

    h.module.clPivotHint shouldBe Some(byHash)
    h.module.clHintArrivedAtMs should not be Some(1L)
    h.peerManager.hasMessages shouldBe false
  }

  it should "report the best block of the SNAP peers that have sent their status" taggedAs UnitTest in {
    val h = new Harness()
    h.module.peers = Map(peer("snap", 100), peer("no-status", 0), peer("eth-only", 500, snap = false))

    var best: Option[BigInt] = None
    h.call(m => best = m.currentNetworkBestFromSnapPeers())

    best shouldBe Some(BigInt(100))
  }

  it should "pass shared peer events to peerEventArms and report bootstrap progress as status" taggedAs UnitTest in {
    val h = new Harness(bootstrapping = true)
    h.module.appStateStorage.putSnapSyncBootstrapTarget(pivot).commit()
    val replies = TestInbox[SyncProtocol.Status]()

    h.kit.run(FlushPeerDisconnects)
    h.kit.run(GetStatus(replies.ref))

    h.module.forwardedPeerEvents shouldBe Seq(FlushPeerDisconnects)
    replies.receiveAll() shouldBe Seq(
      SyncProtocol.Status.Syncing(0, SyncProtocol.Status.Progress(0, pivot), stateNodesProgress = None)
    )
  }

  // ── spec 016 M8c: startSnapSync's pivot steps and the RetryBootstrapAtBlock arm ────────────────────────────────

  it should "resume an interrupted header bootstrap and stop before pivot selection" taggedAs UnitTest in {
    val h = new Harness()
    h.module.appStateStorage.putSnapSyncBootstrapTarget(BigInt(500)).commit()

    h.startSteps() shouldBe false

    h.parent.receiveAll() shouldBe Seq(StartRegularSyncBootstrap(BigInt(500)))
    h.module.appStateStorage.getSnapSyncBootstrapTarget() shouldBe Some(BigInt(500))
    h.module.bootstrapRetryCount shouldBe 0
    h.scheduledTimers() shouldBe empty
  }

  it should "clear a bootstrap target whose header is stored, then back off while no peer is visible" taggedAs UnitTest in {
    val h = new Harness()
    h.module.blockchainWriter.storeBlockHeader(header).commit()
    h.module.appStateStorage.putSnapSyncBootstrapTarget(pivot).commit()

    h.startSteps() shouldBe false

    h.module.appStateStorage.getSnapSyncBootstrapTarget() shouldBe None
    h.parent.hasMessages shouldBe false
    h.module.bootstrapRetryCount shouldBe 1
    h.scheduledTimers() shouldBe Seq(("bootstrap-check", RetrySnapSyncStart, 4.seconds))
    h.module.pivotBlock shouldBe None
  }

  it should "take the SNAP peers' best block less the offset as the pivot and request its header" taggedAs UnitTest in {
    val h = new Harness()
    h.module.bootstrapRetryCount = 3
    h.module.peers = Map(peer("snap", 10_000), peer("eth-only", 20_000, snap = false))

    h.startSteps() shouldBe false

    h.parent.receiveAll() shouldBe Seq(StartRegularSyncBootstrap(BigInt(10_000 - 64)))
    h.module.appStateStorage.getSnapSyncBootstrapTarget() shouldBe Some(BigInt(10_000 - 64))
    h.module.bootstrapRetryCount shouldBe 0
    h.module.pivotBlock shouldBe None
    h.module.syncingEntries shouldBe 0
  }

  it should "raise the network pivot to the MinPivotBlock hint" taggedAs UnitTest in {
    val h = new Harness()
    h.module.minPivotHint = BigInt(9_990)
    h.module.peers = Map(peer("snap", 10_000))

    h.startSteps() shouldBe false

    h.parent.receiveAll() shouldBe Seq(StartRegularSyncBootstrap(BigInt(9_990)))
    h.module.appStateStorage.getSnapSyncBootstrapTarget() shouldBe Some(BigInt(9_990))
  }

  it should "start account sync from genesis when the SNAP peers report a chain below the offset" taggedAs UnitTest in {
    val h = new Harness()
    val genesis = Fixtures.Blocks.Genesis.header
    h.module.blockchainWriter.save(
      Block(genesis, BlockBody.empty),
      Nil,
      ChainWeight.totalDifficultyOnly(genesis.difficulty.value),
      saveAsBestBlock = true
    )
    h.module.peers = Map(peer("snap", 50))

    h.startSteps() shouldBe false

    h.module.pivotBlock shouldBe Some(BigInt(0))
    h.module.stateRoot shouldBe Some(genesis.stateRoot)
    h.module.appStateStorage.getSnapSyncPivotBlock() shouldBe Some(BigInt(0))
    h.module.currentPhase shouldBe SyncPhase.AccountRangeSync
    h.module.accountRangeStarts shouldBe Seq(genesis.stateRoot)
    h.module.syncingEntries shouldBe 1
    h.parent.hasMessages shouldBe false
  }

  it should "take a known CL head as the pivot on a post-merge chain" taggedAs UnitTest in {
    val h = new Harness()
    h.module.posChain = true
    h.module.bootstrapRetryCount = 2
    h.module.clPivotHint = Some(CLPivotHint(header.hash.value, Some(header)))
    h.module.blockchainWriter.storeChainWeight(header.hash, ChainWeight.totalDifficultyOnly(BigInt(7))).commit()
    h.module.peers = Map(peer("snap", pivot + 10_000))

    h.startSteps() shouldBe false

    h.module.pivotBlock shouldBe Some(pivot)
    h.module.stateRoot shouldBe Some(header.stateRoot)
    h.module.appStateStorage.getSnapSyncPivotBlock() shouldBe Some(pivot)
    h.module.bootstrapRetryCount shouldBe 0
    h.module.currentPhase shouldBe SyncPhase.AccountRangeSync
    h.module.accountRangeStarts shouldBe Seq(header.stateRoot)
    h.module.syncingEntries shouldBe 1
    h.parent.hasMessages shouldBe false
  }

  it should "wait for the CL on a post-merge chain with no hint, without selecting a pivot" taggedAs UnitTest in {
    val h = new Harness()
    h.module.posChain = true
    h.module.peers = Map(peer("snap", 10_000))

    h.startSteps() shouldBe false

    h.module.clHintArrivedAtMs shouldBe defined
    h.module.bootstrapRetryCount shouldBe 1
    h.scheduledTimers() shouldBe Seq(("bootstrap-check", RetrySnapSyncStart, 5.seconds))
    h.parent.hasMessages shouldBe false
    h.module.pivotBlock shouldBe None
  }

  it should "retry a backtracked bootstrap: header fetch when it is not stored, in-place commit when it is" taggedAs UnitTest in {
    val h = new Harness()
    h.module.currentPhase = SyncPhase.AccountRangeSync

    h.call(m => m.retryBootstrapAtBlockArms(RetryBootstrapAtBlock(BigInt(777))))

    h.module.pendingPivotRefresh shouldBe Some((BigInt(777), "backtracked pivot"))
    h.parent.receiveAll() shouldBe Seq(StartRegularSyncBootstrap(BigInt(777)))
    h.module.stagnationResets shouldBe Seq(StagnationWatchdog.ResetReason.BootstrapRetryAtBlock)
    h.module.pivotRefreshCommits shouldBe empty

    h.module.blockchainWriter.storeBlockHeader(header).commit()
    h.call(m => m.retryBootstrapAtBlockArms(RetryBootstrapAtBlock(pivot)))

    h.module.pivotRefreshCommits shouldBe Seq((pivot, header, "backtracked pivot (local header)"))
    h.parent.hasMessages shouldBe false
    h.module.stagnationResets.size shouldBe 1
  }

  it should "skip a backtracked bootstrap outside the download and healing phases" taggedAs UnitTest in {
    val h = new Harness()

    h.module.retryBootstrapAtBlockArms.isDefinedAt(RetryBootstrapAtBlock(BigInt(777))) shouldBe true
    h.module.retryBootstrapAtBlockArms.isDefinedAt(RetrySnapSyncStart) shouldBe false
    h.call(m => m.retryBootstrapAtBlockArms(RetryBootstrapAtBlock(BigInt(777))))

    h.module.pendingPivotRefresh shouldBe None
    h.parent.hasMessages shouldBe false
    h.module.stagnationResets shouldBe empty
    h.module.pivotRefreshCommits shouldBe empty
  }
