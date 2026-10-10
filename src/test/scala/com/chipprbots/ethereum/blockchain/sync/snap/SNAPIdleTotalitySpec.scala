package com.chipprbots.ethereum.blockchain.sync.snap

import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.util.ByteString

import scala.compiletime.constValue
import scala.concurrent.duration.*
import scala.deriving.Mirror

import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.SyncProtocol
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController.*
import com.chipprbots.ethereum.blockchain.sync.snap.actors.AccountRangeStats
import com.chipprbots.ethereum.blockchain.sync.snap.actors.ByteCodeCoordinator
import com.chipprbots.ethereum.blockchain.sync.snap.actors.StorageRangeCoordinator
import com.chipprbots.ethereum.network.PeerId
import com.chipprbots.ethereum.network.p2p.messages.SNAP
import com.chipprbots.ethereum.testing.Tags.*

/** Spec 016 follow-up to P3: `idle` must take every `SNAPSyncController.Command` without stopping the controller.
  *
  * Since P3, `idle` is `peerEventArms.orElse { … }`, so the compiler no longer checks that its match is exhaustive. A
  * Command that no `idle` arm names throws a `MatchError`, and the controller stops. This suite is the replacement for
  * that check:
  *   - the subtypes are enumerated from the sealed hierarchy with `Mirror.SumOf[Command]` (count and `ordinal`), so a
  *     NEW subtype without a sample below fails "have a sample for every Command subtype" until one is added;
  *   - each sample is sent to a fresh controller in `idle`, followed by a `GetProgress` barrier. A reply means the
  *     controller is still running (`Alive`: the message was handled or explicitly dropped); no reply and a termination
  *     means `Crashed`.
  *
  * `idle` has no catch-all, so there is no third outcome. `Start` leaves `idle`; the barrier is then answered by the
  * behaviour `Start` switched to, which is still `Alive`.
  *
  * Today's exceptions are pinned as `Crashed` (characterization: today's behaviour, not a fix). `idle` has no arm for
  * `HeaderHoldTick` (#1466) or the three `PathPublish*` messages (236d54278); `idle` is entered only from `start()`,
  * and those four are sent only after `Start`, so they cannot reach it in practice (CHASE-QUEUE CQ-SNAP-016-14). A fix
  * PR that adds the arms flips these four entries on purpose.
  *
  * The subtype count comes from `Tuple.Size` rather than `constValueTuple` of the labels, because the labels tuple (75
  * entries) is deeper than the build's `-Xmax-inlines:64`.
  */
class SNAPIdleTotalitySpec
    extends ScalaTestWithActorTestKit(SnapControllerFixture.config)
    with AnyFlatSpecLike
    with Matchers:

  enum Outcome:
    case Alive, Crashed
  import Outcome.*

  private inline def subtypeCount[T](using m: Mirror.SumOf[T]): Int = constValue[Tuple.Size[m.MirroredElemTypes]]

  private val mirror: Mirror.SumOf[Command] = summon[Mirror.SumOf[Command]]
  private val commandSubtypes: Int = subtypeCount[Command]

  private val statusProbe = testKit.createTestProbe[SyncProtocol.Status]()
  private val progressProbe = testKit.createTestProbe[SyncProgress]()

  private val hash = ByteString(Array.fill(32)(0x5a.toByte))
  private val root = SnapControllerFixture.Root

  /** One sample per Command subtype (the same values as `SNAPSyncingDispatchTableSpec`). */
  private val samples: Seq[Command] = Seq(
    AccountRangeResponse(SNAP.AccountRange(BigInt(9101), Seq.empty, Seq.empty)),
    ByteCodesResponse(SNAP.ByteCodes(BigInt(9102), Seq.empty)),
    StorageRangesResponse(SNAP.StorageRanges(BigInt(9103), Seq.empty, Seq.empty)),
    TrieNodesResponse(SNAP.TrieNodes(BigInt(9104), Seq.empty)),
    ChainDownloaderProgress(BigInt(1), BigInt(1), BigInt(1), BigInt(2)),
    ChainDownloaderDone,
    HeaderHoldTick,
    WrappedHandshakedPeers(Map.empty),
    WrappedPeerDisconnected(PeerId("idle-totality")),
    Start,
    CLPivotHint(hash, None),
    MinPivotBlock(BigInt(5)),
    BootstrapComplete(None),
    PivotBootstrapFailed("idle totality"),
    RetrySnapSyncStart,
    FlushPeerDisconnects,
    RetryPivotRefresh,
    RetryBootstrapAtBlock(BigInt(5)),
    CheckSnapCapability,
    TuneRateTracker,
    EvictNonSnapPeers,
    PivotProbeTimeout(BigInt(9105)),
    DormantWakeUp,
    DelayedRestart("idle totality"),
    AccountRangeProgressCmd(Map.empty),
    CheckDownloadStagnation,
    AccountCoordinatorProgress(AccountRangeStats(0L, 0L, 0, 0, 0, 0.0, 0L, 0L)),
    StorageCoordinatorProgress(StorageRangeCoordinator.SyncStatistics(0L, 0L, 0, 0, 0, 0L, 0.0)),
    ByteCodeCoordinatorProgress(ByteCodeCoordinator.ByteCodeProgress(0.0, 0L, 0L)),
    RequestAccountRanges,
    RequestByteCodes,
    RequestStorageRanges,
    RequestTrieNodeHealing,
    EnsureSnapServerPeersConnected,
    TrieWalkResult(Seq.empty),
    TrieWalkBatch(Seq.empty),
    TrieWalkComplete(0),
    TrieWalkFailed("idle totality"),
    ValidateAccountTrieResult(0L, Right(Seq.empty), 0L),
    ValidateStorageTriesResult(0L, Right(Seq.empty), 0L),
    ValidationRetry(0L),
    ScheduledTrieWalk,
    PollHandshakedPeers,
    AccountRangeSyncComplete,
    ByteCodeSyncComplete,
    StorageRangeSyncComplete,
    StorageRangeSyncForceCompleted,
    IncrementalContractData(Seq.empty, Seq.empty),
    StateHealingComplete,
    StateHealingAbandoned,
    HealedCodeHashes(Seq(hash)),
    HealedCodeWaitTimeout,
    HealingAllPeersStateless,
    HealingRootUnservable(root),
    StateValidationComplete,
    GetStatus(statusProbe.ref),
    GetProgress(progressProbe.ref),
    PathPublishProgress(1L, 1L),
    PathPublishDone(PathToHashExporter.Result(1L, 1L), 1L),
    PathPublishFailed(new RuntimeException("idle totality")),
    HealingServeRoot(BigInt(5), None),
    PivotStateUnservable(root, "idle totality", 1),
    ProgressAccountsSynced(0L),
    ProgressAccountsFinalizingTrie,
    ProgressAccountsTrieFinalized,
    AccountTrieFinalized(root),
    AccountTrieFinalizationFailed("idle totality"),
    ProgressBytecodesDownloaded(0L),
    ProgressStorageSlotsSynced(0L),
    ProgressNodesHealed(0L),
    ProgressAccountEstimate(0L),
    ProgressStorageContracts(0, 0),
    StorageBackpressureChanged(false),
    ByteCodeBackpressureChanged(false),
    HealingStagnated(0L, 0L)
  )

  private def nameOf(command: Command): String = command.getClass.getSimpleName.stripSuffix("$")

  /** Today's outcome in `idle`; anything not listed is `Alive`, including any future subtype. */
  private val expected: Map[String, Outcome] = Map(
    // No `idle` arm (CQ-SNAP-016-14); unreachable in practice, see the class comment.
    "HeaderHoldTick" -> Crashed,
    "PathPublishProgress" -> Crashed,
    "PathPublishDone" -> Crashed,
    "PathPublishFailed" -> Crashed
  ).withDefaultValue(Alive)

  private def run(command: Command): Outcome =
    val f = new SnapControllerFixture(testKit)
    f.storeGenesis()
    val snap = f.spawnController(SNAPSyncConfig())
    // The controller is in `idle`: only `idle` answers GetStatus with NotSyncing.
    f.status(snap) shouldBe SyncProtocol.Status.NotSyncing
    val barrier = testKit.createTestProbe[SyncProgress]()
    snap ! command
    snap ! GetProgress(barrier.ref)
    val outcome =
      try
        barrier.receiveMessage(5.seconds)
        Alive
      catch
        case _: AssertionError =>
          testKit.createTestProbe[Any]().expectTerminated(snap, 5.seconds)
          Crashed
    testKit.stop(snap)
    outcome

  "SNAPSyncController `idle`" should "have a sample for every Command subtype (Mirror.SumOf[Command])" taggedAs UnitTest in {
    val byOrdinal = samples.groupBy(mirror.ordinal)
    val duplicates = byOrdinal.collect { case (o, s) if s.size > 1 => s"ordinal $o: ${s.map(nameOf).mkString(", ")}" }
    withClue("two samples of the same Command subtype: ") {
      duplicates shouldBe empty
    }
    val sampled = samples.map(c => mirror.ordinal(c) -> nameOf(c)).toMap
    val missing = (0 until commandSubtypes).filterNot(sampled.contains).map { o =>
      val before = sampled.get(o - 1).getOrElse("(start)")
      val after = sampled.get(o + 1).getOrElse("(end)")
      s"ordinal $o (declared between $before and $after in object SNAPSyncController)"
    }
    withClue(
      "Command subtype(s) without a sample here; add one to `samples`, and an `idle` arm if it has none: "
    ) {
      missing shouldBe empty
    }
    samples.size shouldBe commandSubtypes
  }

  samples.foreach { command =>
    val name = nameOf(command)
    val title = expected(name) match
      case Alive   => s"take $name without stopping the controller"
      case Crashed => s"stop on $name, which has no idle arm today (CQ-SNAP-016-14)"
    it should title taggedAs UnitTest in {
      run(command) shouldBe expected(name)
    }
  }
