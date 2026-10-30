package com.chipprbots.ethereum.blockchain.sync.snap.actors.healing

import org.apache.pekko.actor.testkit.typed.scaladsl.FishingOutcomes
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.SupervisorStrategy
import org.apache.pekko.actor.typed.scaladsl.adapter.*
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
  @volatile var enqueued: Int = 0 // how many level batches a walk wrote: a cancelled walk writes none
  override def enqueueBatch(entries: Seq[(Array[Byte], Seq[Array[Byte]], Boolean)]): Unit =
    enqueued += 1
    super.enqueueBatch(entries)
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

/** A frontier store whose `loadAll` throws (a column family that cannot be read at a restart), and whose marker clear
  * throws too while `clearFails` is set.
  */
final private class LoadFailingFrontierStorage(dataSource: EphemDataSource) extends HealingFrontierStorage(dataSource):
  @volatile var clearFails: Boolean = false
  override def loadAll(): Seq[(ByteString, Seq[ByteString])] =
    throw new RuntimeException("injected frontier read failure")
  override def clearComplete(): Unit =
    if clearFails then throw new RuntimeException("injected marker clear failure")
    super.clearComplete()

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
  * And the completeness marker those walks leave (hunter W2, forge W-r4-1, vault-r3 W2 and W3): a walk of a root a
  * re-peg has left must not set it, nor may a restart that could not read the persisted frontier keep it.
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
      persistence: Boolean = false,
      otherOnDisk: Boolean = false,
      sharedQueue: Option[com.chipprbots.ethereum.db.storage.BfsQueueStorage] = None,
      currentRoot: Option[java.util.concurrent.atomic.AtomicReference[ByteString]] = None
  ):
    val ec = new ManualExecutionContext
    val trie: PathHealPresenceFixtures.Trie = PathHealPresenceFixtures.accountTrieOld()
    private val otherTrie = PathHealPresenceFixtures.accountTrieNew()

    /** Another root, on disk only with `otherOnDisk`; otherwise a re-peg to it seeds it and starts no walk. */
    val otherRoot = otherTrie.root.hash
    val hashStore = new TestMptStorage()
    PathHealPresenceFixtures.seedHash(hashStore, trie.nodes.filterNot(n => hole && n.label == "brX"))
    if otherOnDisk then PathHealPresenceFixtures.seedHash(hashStore, otherTrie.nodes)
    val queue = new InterleavingBfsQueue
    val controller: TestProbe[SNAPSyncController.Command] = createTestProbe[SNAPSyncController.Command]()
    val peers: TestProbe[NetworkPeerManagerActor.Command] = createTestProbe[NetworkPeerManagerActor.Command]()

    private def coordinator: Behavior[Command] = TrieNodeHealingCoordinator(
      stateRoot = trie.root.hash,
      networkPeerManager = peers.ref,
      requestTracker = new SNAPRequestTracker()(classicSystem.scheduler),
      mptStorage = hashStore,
      batchSize = 16,
      snapSyncController = controller.ref,
      stateIngest = HealingTrieFixtures.healingIngest(frontier, Some(sharedQueue.getOrElse(queue))),
      healingWriterEcOverride = Some(ec),
      frontierPersistenceEnabled = persistence,
      currentRoot = currentRoot
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

  it should "not mark the frontier complete when a rebuild walk of a root a re-peg has left ends clean (hunter W2, forge W-r4-1, vault-r3 W2)" taggedAs UnitTest in {
    val store = new NoEntriesFrontierStorage(EphemDataSource())
    val rig = new Rig(frontier = Some(store), persistence = true, otherOnDisk = true)
    val c = rig.spawnGeneration()
    c ! StartTrieNodeHealing(rig.trie.root.hash)
    rig.settle(c)
    rig.ec.runNext() // the resume: no persisted frontier, so the full rebuild of the root starts
    rig.settle(c)
    withClue("the rebuild is queued: ")(rig.ec.pending shouldBe 1)
    c ! HealingPivotRefreshed(
      rig.otherRoot
    ) // a re-peg mid-walk, to a root on disk: its walk waits for the rebuild's end
    rig.settle(c)
    withClue("no second walk: ")(rig.ec.pending shouldBe 1)
    rig.ec.runNext() // the rebuild of the root the heal has left ends clean
    rig.settle(c)
    withClue("no completeness marker from a walk of a superseded root: ")(store.isComplete shouldBe false)
    withClue("no completion from it: ")(rig.completions() shouldBe 0)
    c ! HealingCheckCompletion // the check after it: with no marker, a full walk of the current root
    rig.drive(c)
    rig.awaitComplete()
    withClue("marked once a walk of the current root is clean: ")(store.isComplete shouldBe true)
  }

  /** A restart over a persisted frontier it cannot read, with the completeness marker set: the resume falls back to the
    * full rebuild. Returns the store, checked right after the resume, before the rebuild runs.
    */
  private def unreadableFrontierRestart(clearFails: Boolean): (Rig, ActorRef[Command], LoadFailingFrontierStorage) =
    val store = new LoadFailingFrontierStorage(EphemDataSource())
    store.markComplete()
    store.clearFails = clearFails
    val rig = new Rig(frontier = Some(store), persistence = true)
    val c = rig.spawnGeneration()
    c ! StartTrieNodeHealing(rig.trie.root.hash)
    rig.settle(c)
    rig.ec.runNext() // the resume: the frontier load throws
    rig.settle(c)
    withClue("the resume falls back to the full rebuild: ")(rig.ec.pending shouldBe 1)
    (rig, c, store)

  it should "remove the completeness marker when a restart cannot read the persisted frontier and falls back to the full rebuild (vault-r3 W3)" taggedAs UnitTest in {
    val (rig, c, store) = unreadableFrontierRestart(clearFails = false)
    withClue("no marker over a frontier the restart could not read: ")(store.isComplete shouldBe false)
    rig.drive(c)
    rig.awaitComplete()
  }

  it should "still fall back to the full rebuild when that marker cannot be removed either (vault-r3 W3)" taggedAs UnitTest in {
    val (rig, c, _) = unreadableFrontierRestart(clearFails = true)
    rig.drive(c)
    rig.awaitComplete()
  }

  // ---- spec 022 T038a: one walk per column family, process-wide; orphans are cancelled ------------------------------------

  it should "stop a stopped generation's walk at its next check, and wake the generation it refused (T038a)" taggedAs UnitTest in {
    val rig = new Rig()
    val gen1 = rig.spawnGeneration()
    gen1 ! HealingCheckCompletion // generation 1's walk is queued, holding the lease
    rig.settle(gen1)
    withClue("generation 1's walk is queued: ")(rig.ec.pending shouldBe 1)
    val gen2 = rig.spawnGeneration()
    gen2 ! HealingCheckCompletion // refused: the lease is held
    rig.settle(gen2)
    withClue("no second walk: ")(rig.ec.pending shouldBe 1)
    testKit.stop(gen1)
    rig.ec.runNext() // the orphan: cancelled, it stops at once, gives the lease back and wakes generation 2
    rig.settle(gen2)
    withClue("the orphan did not walk: ")(rig.queue.enqueued shouldBe 0)
    withClue("generation 2 walks without any further message: ")(rig.ec.pending shouldBe 1)
    rig.drive(gen2)
    rig.awaitComplete()
  }

  it should "share the lease between controller instances: another queue wrapper over the same column family (T038a)" taggedAs UnitTest in {
    val source = EphemDataSource()
    def wrapper = new com.chipprbots.ethereum.db.storage.RocksDbBfsQueueStorage(
      source,
      com.chipprbots.ethereum.db.storage.Namespaces.BfsQueueNamespace
    )
    val first = new Rig(sharedQueue = Some(wrapper))
    val second = new Rig(sharedQueue = Some(wrapper))
    val gen1 = first.spawnGeneration()
    gen1 ! HealingCheckCompletion
    first.settle(gen1)
    withClue("the first controller's walk: ")(first.ec.pending shouldBe 1)
    val gen2 = second.spawnGeneration()
    gen2 ! HealingCheckCompletion
    second.settle(gen2)
    withClue("the second controller's walk waits for the first: ")(second.ec.pending shouldBe 0)
    testKit.stop(gen1)
    first.ec.runNext()
    second.settle(gen2)
    withClue("and starts once the first is gone: ")(second.ec.pending shouldBe 1)
    second.drive(gen2)
    second.awaitComplete()
  }

  it should "stop a supervisor-restarted incarnation's old walk when the restart happens (T038a)" taggedAs UnitTest in {
    val failing = new SyncWriteFailingDataSource
    val store = new NoEntriesFrontierStorage(failing)
    val rig = new Rig(frontier = Some(store), persistence = true)
    val c = rig.spawnSupervised()
    c ! HealingCheckCompletion // incarnation 1 walks
    rig.settle(c)
    failing.failing = true
    c ! HealingPivotRefreshed(rig.otherRoot) // the marker clear throws: the supervisor restarts the coordinator
    rig.settle(c)
    failing.failing = false
    rig.ec.runNext() // incarnation 1's walk stops at once
    withClue("the replaced walk did not walk: ")(rig.queue.enqueued shouldBe 0)
    rig.settle(c)
  }

  it should "not log 'engaged' nor set a pruned or scoped flag for a walk that was refused (T038a)" taggedAs UnitTest in {
    val rig = new Rig(frontier = Some(new NoEntriesFrontierStorage(EphemDataSource())))
    val gen1 = rig.spawnGeneration()
    gen1 ! HealingCheckCompletion
    rig.settle(gen1)
    val capture = new com.chipprbots.ethereum.blockchain.sync.snap.SnapLogCapture
    try
      val gen2 = rig.spawnGeneration()
      gen2 ! HealingCheckCompletion
      rig.settle(gen2)
      withClue("the refusal is said: ")(capture.count("Frontier walk not started") shouldBe 1)
      withClue("no walk was engaged: ")(capture.count("engaged") shouldBe 0)
      rig.completions() shouldBe 0
      testKit.stop(gen2)
    finally capture.close()
  }

  it should "start the completion check when a rebuild of a root a re-peg has left ends: the deferred verification runs (T038a)" taggedAs UnitTest in {
    val rig = new Rig(otherOnDisk = true)
    val c = rig.spawnGeneration()
    c ! StartTrieNodeHealing(rig.trie.root.hash)
    rig.settle(c)
    rig.ec.runNext() // the resume: no persisted frontier, so the rebuild starts
    rig.settle(c)
    c ! HealingPivotRefreshed(rig.otherRoot) // to a root on disk: its verification is deferred behind the rebuild
    rig.settle(c)
    rig.ec.runNext() // the rebuild of the old root ends
    rig.settle(c)
    withClue("nothing else would start it for minutes: ")(rig.ec.pending shouldBe 1)
    rig.drive(c)
    rig.awaitComplete()
  }

  // ---- spec 022 T038b: a supervisor-restarted coordinator takes the controller's current root ------------------------------

  it should "walk the controller's current root after a supervisor restart, not its spawn-time root (T038b)" taggedAs UnitTest in {
    val failing = new SyncWriteFailingDataSource
    val store = new NoEntriesFrontierStorage(failing)
    val current = new java.util.concurrent.atomic.AtomicReference[ByteString]()
    val rig = new Rig(frontier = Some(store), persistence = true, otherOnDisk = true, currentRoot = Some(current))
    current.set(rig.trie.root.hash)
    val c = rig.spawnSupervised()
    failing.failing = true
    current.set(rig.otherRoot) // as the controller does, before it sends the re-peg
    c ! HealingPivotRefreshed(rig.otherRoot) // the marker clear throws: the re-peg is lost with incarnation 1
    rig.settle(c)
    failing.failing = false
    c ! HealingCheckCompletion // incarnation 2 walks
    rig.drive(c)
    rig.awaitComplete()
    withClue("the marker is of the current root: the walk's batches and its verdict named it: ") {
      // A walk of the spawn-time root ends with `walkRoot` != the new root in incarnation 2 only if it was given the
      // new root; with the old one it would have completed on the old root. So: the frontier store's root record.
      store.isSubtreeComplete(rig.otherRoot) shouldBe true
      store.isSubtreeComplete(rig.trie.root.hash) shouldBe false
    }
  }

  // ---- spec 022 T038c, slice-2 notes ----------------------------------------------------------------------------------

  it should "not count a walk across A -> B -> A as a walk of A: no marker, no clean verdict (T038c, A-B-A)" taggedAs UnitTest in {
    val store = new NoEntriesFrontierStorage(EphemDataSource())
    val rig = new Rig(frontier = Some(store), persistence = true, otherOnDisk = true)
    val c = rig.spawnGeneration()
    c ! StartTrieNodeHealing(rig.trie.root.hash)
    rig.settle(c)
    rig.ec.runNext() // the resume: the rebuild of A starts
    rig.settle(c)
    c ! HealingPivotRefreshed(rig.otherRoot)
    rig.settle(c)
    c ! HealingPivotRefreshed(rig.trie.root.hash) // back to A, with A's outstanding frontier dropped twice
    rig.settle(c)
    rig.ec.runNext() // the rebuild, started before both re-pegs, ends clean
    rig.settle(c)
    withClue("its frontier was dropped under it: no complete snapshot: ")(store.isComplete shouldBe false)
    rig.drive(c) // the check walks A again
    rig.awaitComplete()
    withClue("marked by the walk that started after the last re-peg: ")(store.isComplete shouldBe true)
  }

  it should "not let a scoped walk stand in for the full verification a resume asked for (T038c)" taggedAs UnitTest in {
    val rig = new Rig(hole = true, frontier = Some(completeEmptyFrontier()))
    val c = rig.spawnGeneration()
    val brX = rig.trie("brX")
    c ! StartTrieNodeHealing(rig.trie.root.hash)
    rig.settle(c)
    withClue("the resume is queued: ")(rig.ec.pending shouldBe 1)
    c ! TrieNodeHealingCoordinator.QueueMissingNodes(Seq((brX.pathset, brX.hash)))
    c ! TrieNodeHealingCoordinator.HealingPeerAvailable(
      com.chipprbots.ethereum.testing.PeerTestHelpers
        .createTestPeer("scoped-peer", createTestProbe[Any]().ref.toClassic)
    )
    val request = rig.peers
      .expectMessageType[NetworkPeerManagerActor.SendMessageCmd](5.seconds)
      .message
      .underlyingMsg
      .asInstanceOf[com.chipprbots.ethereum.network.p2p.messages.SNAP.GetTrieNodes]
    c ! TrieNodeHealingCoordinator.TrieNodesResponseMsg(
      com.chipprbots.ethereum.network.p2p.messages.SNAP.TrieNodes(request.requestId, Seq(brX.encoded))
    )
    rig.settle(c)
    rig.ec.runNewest() // brX's flush
    rig.ec.runNewest() // its completion: then the completion check starts its walk, scoped by the marker and the heal
    rig.settle(c)
    withClue("the resume and the scoped walk are queued: ")(rig.ec.pending shouldBe 2)
    rig.ec.runNext() // the resume: its full verification is dropped, a walk is running
    rig.settle(c)
    withClue("the scoped walk, alone: ")(rig.ec.pending shouldBe 1)
    rig.ec.runNext() // the scoped walk ends clean
    rig.settle(c)
    withClue("it does not count: ")(rig.completions() shouldBe 0)
    withClue("a full walk follows: ")(rig.ec.pending shouldBe 1)
    rig.drive(c)
    rig.awaitComplete()
  }
