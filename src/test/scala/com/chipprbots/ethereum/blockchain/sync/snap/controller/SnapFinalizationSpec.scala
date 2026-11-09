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
import com.chipprbots.ethereum.blockchain.sync.CacheBasedBlacklist
import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.blockchain.sync.PeerListSupportNg
import com.chipprbots.ethereum.blockchain.sync.SyncProtocol
import com.chipprbots.ethereum.blockchain.sync.TestSyncConfig
import com.chipprbots.ethereum.blockchain.sync.snap.ChainDownloader
import com.chipprbots.ethereum.blockchain.sync.snap.HeaderChainCheck
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
import com.chipprbots.ethereum.domain.BlockHeader
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
    emptyHeaderBackoff: Option[FiniteDuration],
    headerChainCheck: HeaderChainCheck = HeaderChainCheck.LinkOnly
)

/** Records each ChainDownloader the module builds and returns a behaviour that ignores everything. */
final private[snap] class RecordingChainDownloaderFactory extends ChildFactories:
  val calls: mutable.ArrayBuffer[ChainDownloaderCall] = mutable.ArrayBuffer.empty
  val sharedBlacklists: mutable.ArrayBuffer[Option[Blacklist]] = mutable.ArrayBuffer.empty

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
      nowMs: () => Long,
      headerChainCheck: HeaderChainCheck,
      sharedBlacklist: Option[Blacklist]
  ): Behavior[ChainDownloader.Command] =
    sharedBlacklists += sharedBlacklist
    calls += ChainDownloaderCall(
      replyTo,
      maxConcurrentRequests,
      requestTimeout,
      deferBodiesAndReceipts,
      emptyHeaderBackoff,
      headerChainCheck
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
    with ShutdownApi
    with SnapStopApi:

  // SnapFinalizationState (chainDownloaderReplyAdapter is a constructor val)
  var chainDownloadComplete: Boolean = false
  var headerHold: Option[ByteString] = None
  var pathPublish: Option[(BigInt, ByteString)] = None
  var coordinatorGeneration: Long = 0
  val healedCodeHashes: mutable.LinkedHashSet[ByteString] = mutable.LinkedHashSet.empty
  def terminalTotalDifficulty: Option[BigInt] = None
  var headerChainCheck: HeaderChainCheck = HeaderChainCheck.LinkOnly
  val sharedBlacklist: Blacklist = CacheBasedBlacklist.empty(100)

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

  // StateIngest (spec 016 T055): forwards to the stores above, read on each call.
  val stateIngest: StateIngest = new ForwardingStateIngest(
    appStateStorage,
    stateStorage,
    flatSlotStorage,
    pathNodeStorageOpt,
    evmCodeStorage,
    blockchainReader,
    blockchainWriter,
    validatorFactory
  )

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
  var heldResumeReleases: Int = 0
  def releaseHeldResumeCheckpoint(): Unit = heldResumeReleases += 1
  var healedCodeDrops: Int = 0
  val healedCodeQueued: mutable.ArrayBuffer[Seq[ByteString]] = mutable.ArrayBuffer.empty
  def dropHealedCodeNowPresent(): Unit = healedCodeDrops += 1
  def queueHealedCode(codeHashes: Seq[ByteString]): Unit = healedCodeQueued += codeHashes
  def peersToDownloadFrom: Map[PeerId, PeerListSupportNg.PeerWithInfo] = Map.empty
  def calibratePivotTD(pivot: BlockHeader): Option[BigInt] = None
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

  /** A stamped datadir whose hand-off is at `root`, with the invariant marker standing or not. */
  private def stampedHandOff(h: Harness, root: ByteString, markerStands: Boolean): Unit =
    h.module.stateRoot = Some(TrieRoot(root))
    h.module.appStateStorage.markDatadirCreated(countsExact = true).commitSync()
    if markerStands then h.module.appStateStorage.stampSnapHealInvariant().commitSync()

  it should "take the reference-count stamp away before a Path publish of a lazy hand-off writes (#1692)" taggedAs UnitTest in {
    val h = new Harness()
    val root = ByteString(Array.fill[Byte](32)(3))
    stampedHandOff(h, root, markerStands = true)
    // The marker stands, but no walk and no decision vouches for this root: the path store may have holes.
    h.module.healingCleanWalkRoot.get() shouldBe null
    h.module.healingDecidedRoot.get() shouldBe null

    h.call(_.withdrawRefCountStampForPublish())

    h.module.appStateStorage.hasRefCountsExact() shouldBe false
  }

  it should "take the stamp away when the root was decided but the invariant marker is gone (#1692)" taggedAs UnitTest in {
    val h = new Harness()
    val root = ByteString(Array.fill[Byte](32)(4))
    stampedHandOff(h, root, markerStands = false)
    h.module.healingDecidedRoot.set(root)

    h.call(_.withdrawRefCountStampForPublish())

    h.module.appStateStorage.hasRefCountsExact() shouldBe false
  }

  it should "keep the stamp for a Path publish of a clean hand-off: a decision on this root and the marker standing (#1692)" taggedAs UnitTest in {
    val h = new Harness()
    val root = ByteString(Array.fill[Byte](32)(5))
    stampedHandOff(h, root, markerStands = true)
    h.module.healingDecidedRoot.set(root)

    h.call(_.withdrawRefCountStampForPublish())

    h.module.appStateStorage.hasRefCountsExact() shouldBe true
  }

  it should "keep it for a clean walk of this root too, and take it for a clean walk of another (#1692)" taggedAs UnitTest in {
    val walked = new Harness()
    val root = ByteString(Array.fill[Byte](32)(6))
    stampedHandOff(walked, root, markerStands = true)
    walked.module.healingCleanWalkRoot.set(root)
    walked.call(_.withdrawRefCountStampForPublish())
    walked.module.appStateStorage.hasRefCountsExact() shouldBe true

    val other = new Harness()
    stampedHandOff(other, root, markerStands = true)
    other.module.healingCleanWalkRoot.set(ByteString(Array.fill[Byte](32)(7)))
    other.call(_.withdrawRefCountStampForPublish())
    other.module.appStateStorage.hasRefCountsExact() shouldBe false
  }

  it should "leave a datadir with no stamp as it is: an old Path datadir keeps every node and writes nothing (#1692)" taggedAs UnitTest in {
    val h = new Harness()
    h.module.stateRoot = Some(TrieRoot(ByteString(Array.fill[Byte](32)(8))))

    h.call(_.withdrawRefCountStampForPublish())

    h.module.appStateStorage.hasRefCountsExact() shouldBe false
    h.module.appStateStorage.isDatadirCreatedUnderInvariant() shouldBe false
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

  it should "not hold finalisation for healed-account bytecode: leaves wait on it, and a lazy hand-off claims nothing (T037)" taggedAs UnitTest in {
    val h = new Harness()
    h.module.healedCodeHashes += ByteString(Array.fill[Byte](32)(9))

    h.call(_.completeSnapSync()) // no pivot here: it goes on to finalisation, which needs one, and returns

    h.module.healedCodeQueued shouldBe empty
    h.kit.retrieveAllEffects().collect { case t: Effect.TimerScheduled[?] => t.key } shouldBe empty
  }

  it should "give the ChainDownloader the header check and the shared sync blacklist" taggedAs UnitTest in {
    val h = new Harness(SNAPSyncConfig())
    h.module.pivotBlock = Some(pivot)
    val check = new HeaderChainCheck.Validated(
      com.chipprbots.ethereum.consensus.pow.validators.PoWBlockHeaderValidator,
      com.chipprbots.ethereum.blockchain.sync.snap.PowHeaderFixtures.mordorConfig
    )
    h.module.headerChainCheck = check

    h.call(_.startChainDownloader())

    h.factories.calls.map(_.headerChainCheck).toSeq shouldBe Seq(check)
    h.factories.sharedBlacklists.toSeq shouldBe Seq(Some(h.module.sharedBlacklist))
  }

  // ── A pivot the consensus layer chose (post-merge: HeaderChainCheck.trustsPinnedPivot) ─────────────────────────────

  private val chainHeaders: IndexedSeq[BlockHeader] =
    com.chipprbots.ethereum.blockchain.sync.snap.PowHeaderFixtures.fromGenesis.take(21) // blocks 0..20
  private val PinnedAt = BigInt(15)
  private val otherRoot = TrieRoot(ByteString(Array.fill[Byte](32)(0x5a)))

  /** The download holds blocks 0..20 with its tip and its target (block 15) recorded; the consensus layer's pivot at 15
    * is on a chain that parts from it above block 12 (stored by hash, as the CL's blocks are), and is pinned. Returns
    * that pinned header.
    */
  private def postMergeDivergence(h: Harness, pinTheFork: Boolean = true): BlockHeader =
    val writer = h.module.blockchainWriter
    val appState = h.module.appStateStorage
    chainHeaders.foreach(header => writer.storeBlockHeader(header).commit())
    appState
      .putBackfillBestHeader(20)
      .and(appState.putBackfillBestHeaderHash(chainHeaders(20).hash.value))
      .and(appState.putBackfillTargetHash(chainHeaders(15).hash.value))
      .commit()
    val fork = (13 to 15)
      .scanLeft(chainHeaders(12)) { (parent, n) =>
        chainHeaders(n).copy(parentHash = parent.hash, stateRoot = otherRoot)
      }
      .tail
    fork.foreach(header =>
      writer
        .storeBlockByHashOnly(
          com.chipprbots.ethereum.domain.Block(header, com.chipprbots.ethereum.domain.BlockBody.empty)
        )
        .commit()
    )
    val pinned = fork.last
    val commit = appState.putSnapSyncPivotBlock(PinnedAt).and(appState.putSnapSyncStateRoot(otherRoot.value))
    (if pinTheFork then commit.and(appState.putSnapSyncPivotHash(pinned.hash.value)) else commit).commit()
    h.module.pivotBlock = Some(PinnedAt)
    h.module.stateRoot = Some(otherRoot)
    h.module.headerChainCheck = new HeaderChainCheck.ProofOfStake(
      com.chipprbots.ethereum.blockchain.sync.snap.PosHeaderFixtures.sepoliaConfig,
      BigInt(0)
    )
    pinned

  private def rewindsSent(h: Harness): Seq[BlockHeader] =
    h.kit.childInbox[ChainDownloader.Command]("chain-downloader-1").receiveAll().collect {
      case ChainDownloader.RewindTo(forkPoint, _) => forkPoint
    }

  "SnapFinalization of a pivot the consensus layer chose" should "rewind the header download to the fork point, not re-anchor" taggedAs (
    UnitTest,
    com.chipprbots.ethereum.testing.Tags.ConsensusTest
  ) in {
    val h = new Harness()
    val pinned = postMergeDivergence(h)

    h.call(_.finalizeSnapSync(PinnedAt))

    rewindsSent(h) shouldBe Seq(chainHeaders(12))
    h.module.headerHold shouldBe defined
    h.parent.hasMessages shouldBe false
    // The pinned pivot stays the pivot: the downloaded chain's header is never made the pivot.
    h.module.appStateStorage.getSnapSyncPivotHash() shouldBe Some(pinned.hash.value)
    h.module.appStateStorage.getSnapSyncStateRoot() shouldBe Some(otherRoot.value)
  }

  it should "hold without checking again while the rewind is not applied, and keep holding once it is" taggedAs UnitTest in {
    val h = new Harness()
    postMergeDivergence(h)
    h.call(_.finalizeSnapSync(PinnedAt))
    rewindsSent(h) shouldBe Seq(chainHeaders(12))

    h.call(_.finalizeSnapSync(PinnedAt))
    rewindsSent(h) shouldBe empty

    h.call(_.onHeaderDownloadRewound(BigInt(12)))
    h.parent.hasMessages shouldBe false
    h.module.headerHold shouldBe defined
  }

  it should "restart SNAP, still without re-anchoring, when the same pinned pivot diverges again after its rewind" taggedAs (
    UnitTest,
    com.chipprbots.ethereum.testing.Tags.ConsensusTest
  ) in {
    val h = new Harness()
    val pinned = postMergeDivergence(h)
    h.call(_.finalizeSnapSync(PinnedAt))
    h.call(_.onHeaderDownloadRewound(BigInt(12)))

    // The download came back with the same chain (this stub's downloader stores nothing, so the records are as before).
    h.call(_.finalizeSnapSync(PinnedAt))
    rewindsSent(h) shouldBe Seq(chainHeaders(12), chainHeaders(12))
    h.parent.hasMessages shouldBe false // not before the rewind is applied

    h.call(_.onHeaderDownloadRewound(BigInt(12)))
    h.parent.receiveAll() shouldBe Seq(SyncProtocol.HealingImpossible)
    h.module.headerHold shouldBe None
    h.module.appStateStorage.getSnapSyncPivotHash() shouldBe Some(pinned.hash.value)
  }

  it should "rewind to genesis when the pinned pivot's stored ancestry has no header in common with the download" taggedAs UnitTest in {
    val h = new Harness()
    val pinned = postMergeDivergence(h)
    // The fork's block 14 is not stored: the walk down from the pinned pivot stops above the fork point.
    h.module.blockchainWriter.removeBlockByHash(pinned.parentHash).commit()

    h.call(_.finalizeSnapSync(PinnedAt))

    rewindsSent(h).map(_.number.value) shouldBe Seq(BigInt(0))
  }

  it should "restart SNAP, keeping the download, when the pinned header itself is not stored" taggedAs UnitTest in {
    val h = new Harness()
    val pinned = postMergeDivergence(h)
    h.module.blockchainWriter.removeBlockByHash(pinned.hash).commit()

    h.call(_.finalizeSnapSync(PinnedAt))

    h.parent.receiveAll() shouldBe Seq(SyncProtocol.HealingImpossible)
    h.factories.calls shouldBe empty // no hold, no rewind
    h.module.appStateStorage.getBackfillBestHeader() shouldBe BigInt(20)
  }

  // A rewind sent to a downloader that the hold's stall watchdog then restarts may never be applied: it does not count
  // toward the second divergence that restarts SNAP.
  it should "not count a rewind that was never reported applied" taggedAs UnitTest in {
    // A zero stall timeout: every hold tick restarts the downloader, dropping the rewind in flight.
    val h = new Harness(SNAPSyncConfig(headerHoldStallTimeout = Duration.Zero))
    postMergeDivergence(h)

    h.call(_.finalizeSnapSync(PinnedAt)) // diverged: rewind sent
    h.call(_.finalizeSnapSync(PinnedAt)) // the stall restart drops it
    h.call(_.finalizeSnapSync(PinnedAt)) // diverged again: a first rewind, as far as anyone knows
    h.call(_.onHeaderDownloadRewound(BigInt(12)))

    h.parent.hasMessages shouldBe false
    h.module.headerHold shouldBe defined
  }

  it should "restart SNAP when no pinned hash can name the pivot" taggedAs UnitTest in {
    val h = new Harness()
    postMergeDivergence(h, pinTheFork = false)

    h.call(_.finalizeSnapSync(PinnedAt))

    h.parent.receiveAll() shouldBe Seq(SyncProtocol.HealingImpossible)
    h.factories.calls shouldBe empty // no rewind, no hold
  }

  it should "restart SNAP when the download reached the pinned pivot but the persisted state root is another" taggedAs UnitTest in {
    val h = new Harness()
    postMergeDivergence(h)
    h.module.appStateStorage.putSnapSyncPivotHash(chainHeaders(15).hash.value).commit() // the downloaded header

    h.call(_.finalizeSnapSync(PinnedAt))

    h.parent.receiveAll() shouldBe Seq(SyncProtocol.HealingImpossible)
    h.factories.calls shouldBe empty
  }
