package com.chipprbots.ethereum.blockchain.sync.snap.controller

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

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.Blacklist
import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.blockchain.sync.PeerListSupportNg
import com.chipprbots.ethereum.blockchain.sync.SyncProtocol
import com.chipprbots.ethereum.blockchain.sync.TestSyncConfig
import com.chipprbots.ethereum.blockchain.sync.snap.ChainDownloader
import com.chipprbots.ethereum.blockchain.sync.snap.PathToHashExporter
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
import com.chipprbots.ethereum.network.PeerId
import com.chipprbots.ethereum.testing.Tags.UnitTest
import com.chipprbots.ethereum.utils.Config.SyncConfig

/** The ChainDownloader factory call `launchChainDownloader` makes, as recorded by [[RecordingChainDownloaderFactory]].
  */
final private[snap] case class ChainDownloaderCall(
    replyTo: ActorRef[ChainDownloader.Done.type],
    maxConcurrentRequests: Int,
    requestTimeout: FiniteDuration,
    deferBodiesAndReceipts: Boolean,
    emptyHeaderBackoff: Option[FiniteDuration]
)

/** Records each ChainDownloader the module builds and returns a behaviour that ignores everything. */
final private[snap] class RecordingChainDownloaderFactory extends ChildFactories:
  val calls: mutable.ArrayBuffer[ChainDownloaderCall] = mutable.ArrayBuffer.empty

  override def chainDownloader(
      blockchainReader: BlockchainReader,
      blockchainWriter: BlockchainWriter,
      appStateStorage: AppStateStorage,
      networkPeerManager: ActorRef[NetworkPeerManagerActor.Command],
      peerEventBus: ActorRef[PeerEventBusActor.Command],
      syncConfig: SyncConfig,
      replyTo: ActorRef[ChainDownloader.Done.type],
      maxConcurrentRequests: Int,
      requestTimeout: FiniteDuration,
      snapServerPeerNodeIds: Set[ByteString],
      blacklist: Blacklist,
      cursorScanCap: Long,
      deferBodiesAndReceipts: Boolean,
      emptyHeaderBackoff: Option[FiniteDuration],
      nowMs: () => Long
  ): Behavior[ChainDownloader.Command] =
    calls += ChainDownloaderCall(
      replyTo,
      maxConcurrentRequests,
      requestTimeout,
      deferBodiesAndReceipts,
      emptyHeaderBackoff
    )
    Behaviors.ignore

/** Everything `SnapFinalization` needs, as a stub (spec 016 FR-016 (b)): its `SnapFinalizationState`, the hubs, the
  * environment, the coordinator handles, the phase flags and the five callee Api traits. State is plain vars; the
  * parent and the ChainDownloader reply adapter are test inboxes; the chain and app-state stores are ephemeral; the Api
  * calls the tests reach are recorded; the members these tests never reach throw.
  */
private[snap] class StubSnapFinalizationState(
    val ctx: ActorContext[SNAPSyncController.Command],
    val timers: TimerScheduler[SNAPSyncController.Command],
    val snapSyncConfig: SNAPSyncConfig,
    val syncController: ActorRef[SyncProtocol.SyncControllerReply],
    val chainDownloaderReplyAdapter: ActorRef[ChainDownloader.Done.type],
    val childFactories: ChildFactories,
    val networkPeerManager: ActorRef[NetworkPeerManagerActor.Command],
    val peerEventBus: ActorRef[PeerEventBusActor.Command],
    chain: EphemBlockchainTestSetup & TestSyncConfig
) extends SnapFinalizationState
    with SnapSharedState
    with SnapControllerEnv
    with CoordinatorHandles
    with PhaseFlags
    with ResumeApi
    with TaskFileSweepApi
    with HealedCodeApi
    with PeerPoolApi
    with ShutdownApi:

  // SnapFinalizationState (chainDownloaderReplyAdapter is a constructor val)
  var chainDownloadComplete: Boolean = false
  var headerHold: Option[ByteString] = None
  var pathPublish: Option[(BigInt, ByteString)] = None
  var coordinatorGeneration: Long = 0
  val healedCodeHashes: mutable.LinkedHashSet[ByteString] = mutable.LinkedHashSet.empty

  // SnapSharedState
  var pivotBlock: Option[BigInt] = None
  var stateRoot: Option[TrieRoot] = None
  var currentPhase: SyncPhase = SyncPhase.StateValidation
  def progressMonitor: SyncProgressMonitor = notUsed("progressMonitor")
  def requestTracker: SNAPRequestTracker = notUsed("requestTracker")

  // SnapControllerEnv (ctx, timers, snapSyncConfig, syncController, childFactories, networkPeerManager and peerEventBus
  // are constructor vals)
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
  def pathNodeStorageOpt: Option[PathNodeStorage] = None

  // CoordinatorHandles (no coordinator exists; the ChainDownloader handle records what it stops)
  val stoppedChildren: mutable.ArrayBuffer[ActorRef[Nothing]] = mutable.ArrayBuffer.empty
  def intakeBudget: SnapIntakeBudget = notUsed("intakeBudget")
  protected def stopChild(child: ActorRef[Nothing]): Unit = stoppedChildren += child

  // Callee Api traits
  val mptStorageRequests: mutable.ArrayBuffer[BigInt] = mutable.ArrayBuffer.empty
  // The pivot root is unreadable: what `getOrCreateMptStorage(...).get(root)` does when the publish lost the root.
  def getOrCreateMptStorage(pivotBlockNumber: BigInt): MptStorage =
    mptStorageRequests += pivotBlockNumber
    throw new IllegalStateException(s"no state for pivot $pivotBlockNumber")
  def accountsCompleteTaskFilePaths: Set[String] = notUsed("accountsCompleteTaskFilePaths")
  def sweepSupersededTaskFiles(keep: Set[String], reason: String): Unit = notUsed("sweepSupersededTaskFiles")
  var healedCodeDrops: Int = 0
  val healedCodeQueued: mutable.ArrayBuffer[Seq[ByteString]] = mutable.ArrayBuffer.empty
  def dropHealedCodeNowPresent(): Unit = healedCodeDrops += 1
  def queueHealedCode(codeHashes: Seq[ByteString]): Unit = healedCodeQueued += codeHashes
  def peersToDownloadFrom: Map[PeerId, PeerListSupportNg.PeerWithInfo] = Map.empty
  def calibratePivotTD(pivotBlockNumber: BigInt): Option[BigInt] = None
  var stops: Int = 0
  def onStop(): Unit = stops += 1
  def stopSnapOnlySchedules(): Unit = notUsed("stopSnapOnlySchedules")
  def stopStateSyncChildren(): Unit = notUsed("stopStateSyncChildren")

  private def notUsed(what: String): Nothing =
    throw new UnsupportedOperationException(s"$what is not reached by SnapFinalizationSpec")

/** Spec 016 M4 stub test (FR-016 (b), T045): `SnapFinalization` mixed into a stub of its state interface, capabilities
  * and callee Api traits, inside a `BehaviorTestKit` so that `ctx.spawn`, `ctx.log` and the timers are real (spawns and
  * timers are recorded as effects). Each module call runs while the kit processes a message, as it would in the
  * controller; the backfill case runs `completedWithBackfill()` as the kit's own behaviour. The ChainDownloader launch
  * reads `pivotBlock` and the reply adapter and writes `coordinatorGeneration` and `chainDownloadComplete`; the
  * path-publish case reads and clears `pathPublish`; the backfill case writes `chainDownloadComplete`; the healed-code
  * hold reads `healedCodeHashes`.
  */
class SnapFinalizationSpec extends AnyFlatSpec with Matchers:

  private val pivot = BigInt(1_000)

  /** The module on its stub, the kit that runs it, and the inboxes it talks to. */
  final private class Harness(
      config: SNAPSyncConfig = SNAPSyncConfig(),
      behaviour: Option[StubSnapFinalizationState & SnapFinalization => Behavior[Command]] = None
  ):
    val parent: TestInbox[SyncProtocol.SyncControllerReply] = TestInbox[SyncProtocol.SyncControllerReply]()
    val adapter: TestInbox[ChainDownloader.Done.type] = TestInbox[ChainDownloader.Done.type]()
    val factories: RecordingChainDownloaderFactory = new RecordingChainDownloaderFactory
    private val chain = new EphemBlockchainTestSetup with TestSyncConfig {}
    private var action: () => Unit = () => ()
    private var captured: StubSnapFinalizationState & SnapFinalization = null
    val kit: BehaviorTestKit[Command] = BehaviorTestKit(Behaviors.setup[Command] { ctx =>
      Behaviors.withTimers { timers =>
        captured = new StubSnapFinalizationState(
          ctx,
          timers,
          config,
          parent.ref,
          adapter.ref,
          factories,
          TestInbox[NetworkPeerManagerActor.Command]().ref,
          TestInbox[PeerEventBusActor.Command]().ref,
          chain
        ) with SnapFinalization
        behaviour match
          case Some(initial) => initial(captured)
          case None =>
            Behaviors.receiveMessage { _ =>
              action()
              Behaviors.same
            }
      }
    })
    val module: StubSnapFinalizationState & SnapFinalization = captured

    /** Run `f` on the module while the kit processes a message. */
    def call(f: StubSnapFinalizationState & SnapFinalization => Unit): Unit =
      action = () => f(module)
      kit.run(PollHandshakedPeers)

    /** The children spawned since the last look, by name. */
    def spawned(): Seq[String] =
      kit.retrieveAllEffects().collect { case s: Effect.Spawned[?] => s.childName }

  "SnapFinalization" should "launch the ChainDownloader for the pivot and record it in its state" taggedAs UnitTest in {
    val config = SNAPSyncConfig()
    val h = new Harness(config)
    h.module.pivotBlock = Some(pivot)
    h.module.chainDownloadComplete = true

    h.call(_.startChainDownloader())

    h.spawned() shouldBe Seq("chain-downloader-1")
    h.module.coordinatorGeneration shouldBe 1L
    h.module.chainDownloadComplete shouldBe false
    h.module.chainDownloader.isDefined shouldBe true
    h.factories.calls.toSeq shouldBe Seq(
      ChainDownloaderCall(
        replyTo = h.adapter.ref,
        maxConcurrentRequests = config.chainDownloadMaxConcurrentRequests,
        requestTimeout = config.chainDownloadTimeout,
        deferBodiesAndReceipts = SNAPSyncController.chainBackfillDeferredToFinalization(config),
        emptyHeaderBackoff = None
      )
    )
    h.kit.childInbox[ChainDownloader.Command]("chain-downloader-1").receiveAll() shouldBe Seq(
      ChainDownloader.Start(pivot)
    )
  }

  it should "launch nothing without a positive pivot, or with the chain download switched off" taggedAs UnitTest in {
    val noPivot = new Harness()
    noPivot.module.pivotBlock = Some(BigInt(0))
    noPivot.module.chainDownloadComplete = true

    noPivot.call(_.startChainDownloader())

    noPivot.spawned() shouldBe empty
    noPivot.module.coordinatorGeneration shouldBe 0L
    noPivot.module.chainDownloadComplete shouldBe true
    noPivot.module.chainDownloader.isDefined shouldBe false

    val off = new Harness(SNAPSyncConfig(chainDownloadEnabled = false))
    off.module.pivotBlock = Some(pivot)

    off.call(_.startChainDownloader())

    off.spawned() shouldBe empty
    off.module.coordinatorGeneration shouldBe 0L
    off.factories.calls shouldBe empty
  }

  it should "not launch a second ChainDownloader while one is attached" taggedAs UnitTest in {
    val h = new Harness()
    h.module.pivotBlock = Some(pivot)
    h.call(_.startChainDownloader())
    h.spawned() shouldBe Seq("chain-downloader-1")

    h.call(_.startChainDownloader())

    h.spawned() shouldBe empty
    h.module.coordinatorGeneration shouldBe 1L
    h.factories.calls should have size 1
  }

  it should "ignore a path-publish result when no publish is in flight" taggedAs UnitTest in {
    val h = new Harness()

    h.call(_.onPathPublishDone(PathToHashExporter.Result(accountNodes = 1L, storageNodes = 2L), 10L))

    h.module.pathPublish shouldBe None
    h.module.mptStorageRequests shouldBe empty
    h.parent.hasMessages shouldBe false
  }

  it should "clear the publish in flight and escalate when the published pivot root is unreadable" taggedAs UnitTest in {
    val h = new Harness()
    val root = ByteString(Array.fill[Byte](32)(7))
    h.module.pathPublish = Some((pivot, root))

    h.call(_.onPathPublishDone(PathToHashExporter.Result(accountNodes = 1L, storageNodes = 2L), 10L))

    h.module.pathPublish shouldBe None
    h.module.mptStorageRequests.toSeq shouldBe Seq(pivot)
    h.parent.receiveAll() shouldBe Seq(SyncProtocol.HealingImpossible)
  }

  it should "record the end of the background backfill, stop the downloader and answer Done" taggedAs UnitTest in {
    val h = new Harness(behaviour = Some(_.completedWithBackfill()))
    val downloader = TestInbox[ChainDownloader.Command]()
    h.module.chainDownloader.attach(downloader.ref)
    h.module.chainDownloadComplete shouldBe false

    h.kit.run(ChainDownloaderDone)

    h.module.chainDownloadComplete shouldBe true
    h.module.stoppedChildren.toSeq shouldBe Seq(downloader.ref)
    h.module.chainDownloader.isDefined shouldBe false
    h.parent.receiveAll() shouldBe Seq(Done)

    // The next behaviour is `completed()`: status says the sync is done.
    val status = TestInbox[SyncProtocol.Status]()
    h.kit.run(GetStatus(status.ref))
    status.receiveAll() shouldBe Seq(SyncProtocol.Status.SyncDone)
  }

  it should "hold finalisation while healed-account bytecode is missing" taggedAs UnitTest in {
    val h = new Harness()
    val codeHash = ByteString(Array.fill[Byte](32)(9))
    h.module.pivotBlock = Some(pivot)
    h.module.healedCodeHashes += codeHash

    h.call(_.completeSnapSync())

    h.module.healedCodeDrops shouldBe 1
    h.module.awaitingHealedCode shouldBe true
    h.module.healedCodeQueued.toSeq shouldBe Seq(Seq(codeHash))
    h.kit.retrieveAllEffects().collect { case t: Effect.TimerScheduled[?] => (t.key, t.msg, t.delay) } shouldBe Seq(
      (HealedCodeWaitTimerKey, HealedCodeWaitTimeout, HealedCodeWaitMs.millis)
    )
    h.module.currentPhase shouldBe SyncPhase.StateValidation
    h.parent.hasMessages shouldBe false
  }
