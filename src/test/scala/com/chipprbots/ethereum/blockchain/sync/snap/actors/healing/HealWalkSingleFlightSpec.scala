package com.chipprbots.ethereum.blockchain.sync.snap.actors.healing

import org.apache.pekko.actor.testkit.typed.scaladsl.FishingOutcomes
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.SupervisorStrategy
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.util.ByteString

import scala.concurrent.duration.*

import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.snap.SNAPRequestTracker
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController
import com.chipprbots.ethereum.blockchain.sync.snap.actors.HealingStatistics
import com.chipprbots.ethereum.blockchain.sync.snap.actors.HealingTrieFixtures
import com.chipprbots.ethereum.blockchain.sync.snap.actors.PathHealPresenceFixtures
import com.chipprbots.ethereum.blockchain.sync.snap.actors.TrieNodeHealingCoordinator
import com.chipprbots.ethereum.blockchain.sync.snap.actors.TrieNodeHealingCoordinator.Command
import com.chipprbots.ethereum.blockchain.sync.snap.actors.TrieNodeHealingCoordinator.HealingCheckCompletion
import com.chipprbots.ethereum.blockchain.sync.snap.actors.TrieNodeHealingCoordinator.HealingGetProgress
import com.chipprbots.ethereum.blockchain.sync.snap.actors.TrieNodeHealingCoordinator.HealingPivotRefreshed
import com.chipprbots.ethereum.blockchain.sync.snap.actors.TrieNodeHealingCoordinator.StartTrieNodeHealing
import com.chipprbots.ethereum.db.dataSource.DataUpdate
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.HealingFrontierStorage
import com.chipprbots.ethereum.db.storage.InMemoryBfsQueueStorage
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.testing.Tags.UnitTest
import com.chipprbots.ethereum.testing.TestMptStorage

/** A BFS queue that runs `afterFirstLevel` once, right after a walk frees its first level (its first `deleteRange`). A
  * test puts another walk there, so two walks on one queue interleave at a fixed point, as two writer threads can.
  */
final private class InterleavingBfsQueue extends InMemoryBfsQueueStorage:
  @volatile var afterFirstLevel: Option[() => Unit] = None
  override def deleteRange(from: Long, to: Long): Unit =
    super.deleteRange(from, to)
    val hook = afterFirstLevel
    afterFirstLevel = None
    hook.foreach(_())

/** A data source whose synced writes throw while `failing` is set: a frontier store whose marker write or clear fails,
  * which throws out of the coordinator's handler and has its supervisor restart it.
  */
final private class SyncWriteFailingDataSource extends EphemDataSource(Map.empty):
  @volatile var failing: Boolean = false
  override def updateSync(dataSourceUpdates: Seq[DataUpdate]): Unit =
    if failing then throw new RuntimeException("injected synced-write failure")
    super.updateSync(dataSourceUpdates)

/** A frontier store with no entries. `loadAll` is overridden because the in-memory data source returns its keys with
  * the namespace prefix, so the real one would take the completeness marker for an entry; RocksDB returns bare keys
  * (see `HealingFrontierStorageSpec`).
  */
final private class NoEntriesFrontierStorage(dataSource: EphemDataSource) extends HealingFrontierStorage(dataSource):
  override def loadAll(): Seq[(ByteString, Seq[ByteString])] = Seq.empty

/** Spec 022 slice 2, fix round 4 (hunter W1, forge W-r4-2, vault-r3 W1, beacon W1-r4): one frontier walk at a time on
  * the shared BFS queue, whoever starts it.
  *
  * Every walk clears the queue when it starts and when it ends, and reads its next level's end from the queue's
  * counter. A second walk on the same queue therefore cuts the first short, and the cut walk reports the missing nodes
  * it found so far, possibly none: a clean pass over a trie no walk covered. The starts that did not check for a
  * running walk are the restart's full rebuild and resume verification (in the window before the resume's message, a
  * completion check or a re-peg can start a walk), and any start in a new coordinator incarnation (a supervisor
  * restart, or a replacement the controller spawns) while the old one's walk still runs on the same queue.
  *
  * Fixture: `accountTrieOld` on a Hash store, complete or without `brX` (whose parent `ext` is on disk). Every
  * coordinator here shares one BFS queue and one manual writer executor, so walks, flushes and the resume run only when
  * the test runs them, one at a time and in the order it chooses.
  */
class HealWalkSingleFlightSpec extends ScalaTestWithActorTestKit() with AnyFlatSpecLike with Matchers:

  implicit private val classicSystem: org.apache.pekko.actor.ActorSystem = system.classicSystem

  final private class Rig(
      hole: Boolean = false,
      frontier: Option[HealingFrontierStorage] = None,
      persistence: Boolean = false
  ):
    val ec = new ManualExecutionContext
    val trie: PathHealPresenceFixtures.Trie = PathHealPresenceFixtures.accountTrieOld()

    /** Another root, not on disk: a re-peg to it seeds it and starts no walk. */
    val otherRoot = PathHealPresenceFixtures.accountTrieNew().root.hash
    val hashStore = new TestMptStorage()
    PathHealPresenceFixtures.seedHash(hashStore, trie.nodes.filterNot(n => hole && n.label == "brX"))
    val queue = new InterleavingBfsQueue
    val controller: TestProbe[SNAPSyncController.Command] = createTestProbe[SNAPSyncController.Command]()
    private val peers = createTestProbe[NetworkPeerManagerActor.Command]()

    private def coordinator: Behavior[Command] = TrieNodeHealingCoordinator(
      stateRoot = trie.root.hash,
      networkPeerManager = peers.ref,
      requestTracker = new SNAPRequestTracker()(classicSystem.scheduler),
      mptStorage = hashStore,
      batchSize = 16,
      snapSyncController = controller.ref,
      stateIngest = HealingTrieFixtures.healingIngest(frontier, Some(queue)),
      healingWriterEcOverride = Some(ec),
      frontierPersistenceEnabled = persistence
    )

    /** A coordinator generation, as the controller spawns one. */
    def spawnGeneration(): ActorRef[Command] = spawn(coordinator)

    /** A coordinator its supervisor restarts on a failure: a new incarnation behind the same ActorRef and mailbox. */
    def spawnSupervised(): ActorRef[Command] =
      spawn(Behaviors.supervise(coordinator).onFailure[Throwable](SupervisorStrategy.restart))

    /** Mailbox barrier, three deep: a message sent before the call, the check it sends, and what that sends. */
    def settle(c: ActorRef[Command]): Unit = (1 to 3).foreach(_ => progress(c))

    def progress(c: ActorRef[Command]): HealingStatistics =
      val probe = createTestProbe[HealingStatistics]()
      c ! HealingGetProgress(probe.ref)
      probe.receiveMessage(30.seconds)

    /** Run what waits on the writer executor and what follows from it, one task at a time, at most `steps` tasks. */
    def drive(c: ActorRef[Command], steps: Int = 24): Unit =
      settle(c)
      var left = steps
      while left > 0 && ec.pending > 0 do
        ec.runNext()
        settle(c)
        left -= 1

    /** The `StateHealingComplete`s the controller has received so far (drains what is queued, without waiting). */
    def completions(): Int =
      var seen = 0
      var open = true
      while open do
        try if controller.receiveMessage(Duration.Zero) == SNAPSyncController.StateHealingComplete then seen += 1
        catch case _: AssertionError => open = false
      seen

    def awaitComplete(): Unit =
      controller.fishForMessage(30.seconds) {
        case SNAPSyncController.StateHealingComplete => FishingOutcomes.complete
        case _                                       => FishingOutcomes.continueAndIgnore
      }

  /** A frontier store holding only the completeness marker: the resume skips the rebuild and verifies. */
  private def completeEmptyFrontier(): HealingFrontierStorage =
    val store = new NoEntriesFrontierStorage(EphemDataSource())
    store.markComplete()
    store

  /** The restart's own walk (a full rebuild, or a verification after a complete persisted frontier) arrives while a
    * walk a completion check started in the resume window runs: it must not start a second one.
    */
  private def restartWalkWhileWalking(frontier: Option[HealingFrontierStorage]): Unit =
    val rig = new Rig(frontier = frontier, persistence = frontier.isDefined)
    val c = rig.spawnGeneration()
    c ! StartTrieNodeHealing(rig.trie.root.hash) // the root is on disk: the resume runs on the writer executor
    rig.settle(c)
    withClue("the resume is queued: ")(rig.ec.pending shouldBe 1)
    c ! HealingCheckCompletion // in the window: nothing pending and no clean pass yet, so the check walks
    rig.settle(c)
    withClue("the resume and the check's walk are queued: ")(rig.ec.pending shouldBe 2)
    rig.ec.runNext() // the resume: it sends the restart's own walk start
    rig.settle(c)
    withClue("one walk at a time on the shared queue: ")(rig.ec.pending shouldBe 1)
    rig.drive(c)
    rig.awaitComplete()

  "A healing coordinator" should "not start the restart's full rebuild while a walk runs (hunter W1, vault-r3 W1)" taggedAs UnitTest in {
    restartWalkWhileWalking(frontier = None)
  }

  it should "not start the restart's resume verification while a walk runs (forge W-r4-2)" taggedAs UnitTest in {
    restartWalkWhileWalking(frontier = Some(completeEmptyFrontier()))
  }

  /** A re-peg inside the resume window, to a root not on disk, seeds the new root; the restart's walk of the old root,
    * arriving after it, would walk a superseded root (and hold the queue for it).
    */
  private def restartWalkOfSupersededRoot(frontier: Option[HealingFrontierStorage]): Unit =
    // Persistence off, so the re-peg leaves a persisted marker in place and the resume still sees it.
    val rig = new Rig(frontier = frontier)
    val c = rig.spawnGeneration()
    c ! StartTrieNodeHealing(rig.trie.root.hash)
    rig.settle(c)
    c ! HealingPivotRefreshed(rig.otherRoot)
    rig.settle(c)
    withClue("the re-peg to a root not on disk seeds it and starts no walk: ")(rig.ec.pending shouldBe 1)
    rig.ec.runNext() // the resume, for the root the coordinator has left
    rig.settle(c)
    withClue("no walk of the superseded root: ")(rig.ec.pending shouldBe 0)

  it should "drop the restart's full rebuild of a root a re-peg has superseded (beacon W1-r4)" taggedAs UnitTest in {
    restartWalkOfSupersededRoot(frontier = None)
  }

  it should "drop the restart's resume verification of a root a re-peg has superseded (beacon W1-r4)" taggedAs UnitTest in {
    restartWalkOfSupersededRoot(frontier = Some(completeEmptyFrontier()))
  }

  /** A supervisor restart while incarnation 1's walk is queued: incarnation 2 must not start a walk of its own on the
    * same queue until that walk has ended, and that walk's end (a clean pass here) must not count for incarnation 2.
    * `startWalk` starts incarnation 1's walk; the crash is a failed synced marker clear at a re-peg.
    */
  private def walkAcrossSupervisorRestart(
      startWalk: (Rig, ActorRef[Command]) => Unit,
      store: HealingFrontierStorage,
      failing: SyncWriteFailingDataSource
  ): Unit =
    val rig = new Rig(frontier = Some(store), persistence = true)
    val c = rig.spawnSupervised()
    startWalk(rig, c)
    withClue("incarnation 1's walk is queued: ")(rig.ec.pending shouldBe 1)
    failing.failing = true
    c ! HealingPivotRefreshed(rig.otherRoot) // the marker clear throws: the supervisor restarts the coordinator
    rig.settle(c)
    failing.failing = false
    c ! HealingCheckCompletion // incarnation 2: nothing pending, no clean pass of its own, so it would walk
    rig.settle(c)
    withClue("one walk at a time across incarnations: ")(rig.ec.pending shouldBe 1)
    rig.ec.runNext() // incarnation 1's walk ends clean
    rig.settle(c)
    withClue("no completion from a replaced incarnation's walk: ")(rig.completions() shouldBe 0)
    withClue("no completeness marker from a replaced incarnation's walk: ")(store.isComplete shouldBe false)
    rig.drive(c) // incarnation 2's own walk
    rig.awaitComplete()

  it should "ignore the clean verification of a replaced incarnation's walk, and walk itself after it (beacon W1-r4)" taggedAs UnitTest in {
    val failing = new SyncWriteFailingDataSource
    val startWalk: (Rig, ActorRef[Command]) => Unit = (rig, c) =>
      c ! HealingCheckCompletion // incarnation 1 walks
      rig.settle(c)
    walkAcrossSupervisorRestart(startWalk, new NoEntriesFrontierStorage(failing), failing)
  }

  it should "ignore the clean full rebuild of a replaced incarnation's walk: no marker, no completion (beacon W1-r4)" taggedAs UnitTest in {
    val failing = new SyncWriteFailingDataSource
    val startWalk: (Rig, ActorRef[Command]) => Unit = (rig, c) =>
      c ! StartTrieNodeHealing(rig.trie.root.hash)
      rig.settle(c)
      rig.ec.runNext() // the resume: no persisted frontier, so the full rebuild starts
      rig.settle(c)
    walkAcrossSupervisorRestart(startWalk, new NoEntriesFrontierStorage(failing), failing)
  }

  it should "not let a replaced generation's walk cut short the new generation's walk (hunter W1, beacon W1-r4)" taggedAs UnitTest in {
    val rig = new Rig(hole = true)
    val gen1 = rig.spawnGeneration()
    gen1 ! HealingCheckCompletion // generation 1 walks
    rig.settle(gen1)
    testKit.stop(gen1) // the controller replaces it; its walk is still queued on the writer executor
    val gen2 = rig.spawnGeneration()
    gen2 ! HealingCheckCompletion
    rig.settle(gen2)
    // Run the newest walk; the moment it frees its first level, any other queued walk runs to its end on the same queue.
    rig.queue.afterFirstLevel = Some(() => rig.ec.runNext())
    rig.ec.runNewest()
    rig.settle(gen2)
    withClue("no completion over the hole at brX: ")(rig.completions() shouldBe 0)
    gen2 ! HealingCheckCompletion // a later check (a heal's, or the watchdog's)
    rig.drive(gen2)
    withClue("generation 2's own walk found brX: ")(rig.progress(gen2).pendingTasks shouldBe 1)
    rig.completions() shouldBe 0
  }
