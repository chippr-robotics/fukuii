package com.chipprbots.ethereum.blockchain.sync.snap.actors

import org.apache.pekko.actor.testkit.typed.scaladsl.BehaviorTestKit
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.testkit.typed.scaladsl.TestInbox
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.util.ByteString

import scala.concurrent.ExecutionContext

import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.snap.SNAPRequestTracker
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController
import com.chipprbots.ethereum.blockchain.sync.snap.actors.TrieNodeHealingCoordinator.*
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.p2p.messages.SNAP
import com.chipprbots.ethereum.testing.PeerTestHelpers
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.testing.TestMptStorage

/** Spec 016 C-H0 (T070): the arm-order table of `TrieNodeHealingCoordinator`'s `activeBehavior` (research.md R4c), as a
  * test. The TNHC counterpart of T036's R4b table and T027's `SNAPSyncingDispatchTableSpec`.
  *
  * `activeBehavior` chains seven partial functions with `orElse`. Every arm except the three `VerificationBFSComplete`
  * arms and the catch-all matches one Command type and no other, so the first arm that matches a message does not
  * depend on the order of the groups, only on (a) each Command being defined in exactly one group, (b) the three
  * `VerificationBFSComplete` arms keeping their order inside their group, and (c) the catch-all coming last. This spec
  * pins all three:
  *   - structure: for one sample of every Command (four of `VerificationBFSComplete`, one per guard outcome), exactly
  *     the expected group is defined at it (none for `HealingTaskFailed`, which has no arm), and the catch-all is
  *     defined at every one;
  *   - dispatch: run on a fresh coordinator, every sample is handled except `HealingTaskFailed`, which reaches the
  *     catch-all and returns `Behaviors.unhandled`;
  *   - guard order: each `VerificationBFSComplete` sample runs the arm its guards select (#17 stale root first, then
  *     #18 missing nodes, then #19 clean), read from the arm's own log line.
  *
  * The coordinator runs in a `BehaviorTestKit`, with an execution context that never runs a task, so the walk and flush
  * Futures some arms launch never start: only the arm itself runs.
  */
class TrieNodeHealingDispatchTableSpec extends ScalaTestWithActorTestKit() with AnyFlatSpecLike with Matchers:

  private val Root = kec256(ByteString("c-h0-dispatch-root"))
  private val OtherRoot = kec256(ByteString("c-h0-dispatch-other-root"))
  private val EmptyPath =
    ByteString(com.chipprbots.ethereum.mpt.HexPrefix.encode(Array.empty[Byte], isLeaf = false))

  // Drops every task: the BFS walk and flush Futures that arms launch never run.
  private val neverRuns: ExecutionContext = new ExecutionContext:
    def execute(runnable: Runnable): Unit = ()
    def reportFailure(cause: Throwable): Unit = ()

  private val peer = PeerTestHelpers.createTestPeer("c-h0-dispatch-peer", testKit.createTestProbe[Any]().ref.toClassic)

  final private case class Harness(impl: TrieNodeHealingCoordinatorImpl, kit: BehaviorTestKit[Command])

  private def harness(): Harness =
    var captured: TrieNodeHealingCoordinatorImpl = null
    val behavior = Behaviors.setup[Command] { ctx =>
      Behaviors.withTimers { timers =>
        captured = new TrieNodeHealingCoordinatorImpl(
          ctx,
          timers,
          initialStateRoot = Root,
          networkPeerManager = TestInbox[NetworkPeerManagerActor.Command]().ref,
          requestTracker = new SNAPRequestTracker()(system.classicSystem.scheduler),
          mptStorage = new TestMptStorage(),
          batchSize = 16,
          snapSyncController = TestInbox[SNAPSyncController.Command]().ref,
          concurrency = 4,
          stateIngest = HealingTrieFixtures.healingIngest(None),
          healingWriterEcOverride = Some(neverRuns),
          healingReaderEcOverride = Some(neverRuns),
          evmCodeStorage = HealingTrieFixtures.unreadCodeStore
        )
        captured.start()
      }
    }
    val kit = BehaviorTestKit(behavior)
    Harness(captured, kit)

  private val Lifecycle = "healLifecycleArms"
  private val Rebuild = "frontierRebuildArms"
  private val Response = "healResponseArms"
  private val Dispatch = "healDispatchArms"
  private val Persistence = "frontierPersistenceArms"
  private val Verification = "healVerificationArms"

  /** The concern groups, in `activeBehavior`'s chain order (the catch-all, `unhandledCommandArms`, follows them). */
  private def groups(impl: TrieNodeHealingCoordinatorImpl): Seq[(String, PartialFunction[Command, Behavior[Command]])] =
    Seq(
      Lifecycle -> impl.healLifecycleArms,
      Rebuild -> impl.frontierRebuildArms,
      Response -> impl.healResponseArms,
      Dispatch -> impl.healDispatchArms,
      Persistence -> impl.frontierPersistenceArms,
      Verification -> impl.healVerificationArms
    )

  /** One row per sample: the arm number in research.md R4c (26 = the catch-all) and the group that holds it. */
  final private case class Row(label: String, msg: Command, arm: Int, group: Option[String])

  private def rows: Seq[Row] = Seq(
    Row("StartTrieNodeHealing", StartTrieNodeHealing(Root), 1, Some(Lifecycle)),
    Row("FrontierRebuilt", FrontierRebuilt(Seq.empty), 2, Some(Rebuild)),
    Row("FrontierRebuildComplete", FrontierRebuildComplete(1L, Root), 3, Some(Rebuild)),
    Row("FrontierWalkFailed", FrontierWalkFailed, 4, Some(Rebuild)),
    Row("QueueMissingNodes", QueueMissingNodes(Seq.empty), 5, Some(Response)),
    Row("HealingPeerAvailable", HealingPeerAvailable(peer), 6, Some(Dispatch)),
    Row("HealingPeerUnavailable", HealingPeerUnavailable("c-h0-gone"), 7, Some(Dispatch)),
    Row("UpdateMaxInFlightPerPeer", UpdateMaxInFlightPerPeer(3), 8, Some(Dispatch)),
    Row("HealingForceComplete", HealingForceComplete, 9, Some(Lifecycle)),
    Row("HealingPivotRefreshed", HealingPivotRefreshed(Root), 10, Some(Lifecycle)),
    Row("HealingServeRootRefresh", HealingServeRootRefresh(OtherRoot), 11, Some(Dispatch)),
    Row("HealingResumeDispatch", HealingResumeDispatch, 12, Some(Dispatch)),
    Row("TrieNodesResponseMsg", TrieNodesResponseMsg(SNAP.TrieNodes(BigInt(1), Seq.empty)), 13, Some(Response)),
    Row("FlushComplete", FlushComplete(0), 14, Some(Persistence)),
    Row("HealingTaskComplete", HealingTaskComplete(BigInt(1), Right(1)), 15, Some(Response)),
    Row("HealingCheckCompletion", HealingCheckCompletion, 16, Some(Verification)),
    Row("VerificationBFSComplete(stale root, missing)", VerificationBFSComplete(OtherRoot, 5L), 17, Some(Verification)),
    Row("VerificationBFSComplete(stale root, clean)", VerificationBFSComplete(OtherRoot, 0L), 17, Some(Verification)),
    Row("VerificationBFSComplete(current root, missing)", VerificationBFSComplete(Root, 5L), 18, Some(Verification)),
    Row("VerificationBFSComplete(current root, clean)", VerificationBFSComplete(Root, 0L), 19, Some(Verification)),
    Row("WalkStateChanged", WalkStateChanged(false), 20, Some(Verification)),
    Row("HealingStagnationCheck", HealingStagnationCheck, 21, Some(Lifecycle)),
    Row("HealingGetProgress", HealingGetProgress(TestInbox[HealingStatistics]().ref), 22, Some(Lifecycle)),
    Row("HealingRequestTimeout", HealingRequestTimeout(BigInt(1)), 23, Some(Dispatch)),
    Row("RestartResumeVerification", RestartResumeVerification(Root, EmptyPath), 24, Some(Verification)),
    Row("RestartFullRebuild", RestartFullRebuild(Root, EmptyPath), 25, Some(Rebuild)),
    // No arm: falls through every group to the catch-all.
    Row("HealingTaskFailed", HealingTaskFailed(BigInt(1), "c-h0 table"), 26, None)
  )

  "TrieNodeHealingCoordinator activeBehavior" should "define each Command in exactly its own arm group" taggedAs
    UnitTest in {
      val h = harness()
      val observed =
        rows.map(r => r.label -> groups(h.impl).collect { case (name, pf) if pf.isDefinedAt(r.msg) => name })
      val expected = rows.map(r => r.label -> r.group.toSeq)
      withClue(observed.map { case (l, gs) => s"$l -> ${gs.mkString(", ")}" }.mkString("\n", "\n", "\n")) {
        observed shouldBe expected
      }
      rows.filterNot(r => h.impl.unhandledCommandArms.isDefinedAt(r.msg)).map(_.label) shouldBe empty
    }

  it should "handle every Command except HealingTaskFailed, which reaches the catch-all" taggedAs UnitTest in {
    val observed = rows.map { r =>
      val h = harness()
      h.kit.run(r.msg)
      r.label -> Behavior.isUnhandled(h.kit.returnedBehavior)
    }
    observed shouldBe rows.map(r => r.label -> r.group.isEmpty)
  }

  it should "run the VerificationBFSComplete arm its guards select: stale root, then missing nodes, then clean" taggedAs
    UnitTest in {
      val markers = Seq(
        17 -> "[HEAL-VERIFY] Discarding stale verification result",
        18 -> "[HEAL-VERIFY] Verification pass found",
        19 -> "[HEAL-VERIFY] Verification BFS complete"
      )
      val verificationRows = rows.filter(_.label.startsWith("VerificationBFSComplete"))
      verificationRows should have size 4
      val observed = verificationRows.map { r =>
        val h = harness()
        h.kit.clearLog()
        h.kit.run(r.msg)
        val messages = h.kit.logEntries().map(_.message)
        r.label -> markers.collect { case (arm, marker) if messages.exists(_.startsWith(marker)) => arm }
      }
      observed shouldBe verificationRows.map(r => r.label -> Seq(r.arm))
    }
