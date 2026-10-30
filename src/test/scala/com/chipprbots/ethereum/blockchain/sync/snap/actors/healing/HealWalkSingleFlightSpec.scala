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
import com.chipprbots.ethereum.blockchain.sync.snap.actors.TrieNodeHealingCoordinator.QueueMissingNodes
import com.chipprbots.ethereum.blockchain.sync.snap.actors.TrieNodeHealingCoordinator.RestartFullRebuild
import com.chipprbots.ethereum.blockchain.sync.snap.actors.TrieNodeHealingCoordinator.StartTrieNodeHealing
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
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

/** Spec 022 slice 2, fix round 4 (hunter W1, forge W-r4-2, vault-r3 W1, beacon W1-r4): one frontier walk at a time on
  * the shared BFS queue, whoever starts it.
  *
  * Every walk clears the queue when it starts and when it ends, and reads its next level's end from the queue's
  * counter. A second walk on the same queue therefore cuts the first short, and the cut walk reports the missing nodes
  * it found so far, possibly none: a clean pass over a trie no walk covered. The starts that did not check for a
  * running walk are the restart's full rebuild (between its start and its message, a completion check or a re-peg can
  * start a walk), and any start in a new coordinator incarnation (a supervisor restart, or a replacement the controller
  * spawns) while the old one's walk still runs on the same queue.
  *
  * And what a clean walk may vouch for (hunter W2, forge W-r4-1): a walk of a root a re-peg has left vouches for
  * nothing.
  *
  * Fixture: `accountTrieOld` on a Hash store, complete or without `brX` (whose parent `ext` is on disk). Every
  * coordinator here shares one BFS queue and one manual writer executor, so walks and flushes run only when the test
  * runs them, one at a time and in the order it chooses. A crash is a malformed message: its arm throws, as a failed
  * write could, and the supervisor restarts the coordinator.
  */
class HealWalkSingleFlightSpec extends ScalaTestWithActorTestKit() with AnyFlatSpecLike with Matchers:

  implicit private val classicSystem: org.apache.pekko.actor.ActorSystem = system.classicSystem

  final private class Rig(
      hole: Boolean = false,
      otherOnDisk: Boolean = false,
      sharedQueue: Option[com.chipprbots.ethereum.db.storage.BfsQueueStorage] = None,
      currentRoot: Option[java.util.concurrent.atomic.AtomicReference[ByteString]] = None,
      writerEc: Option[scala.concurrent.ExecutionContext] = None
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
      stateIngest = HealingTrieFixtures.healingIngest(None, Some(sharedQueue.getOrElse(queue))),
      healingWriterEcOverride = Some(writerEc.getOrElse(ec)),
      currentRoot = currentRoot,
      cleanWalkRoot = Some(cleanWalkRoot)
    )

    /** Which root the coordinator last said a clean full walk covered (spec 022 T043). */
    val cleanWalkRoot = new java.util.concurrent.atomic.AtomicReference[ByteString]()

    val emptyPath: ByteString = ByteString(
      com.chipprbots.ethereum.mpt.HexPrefix.encode(Array.empty[Byte], isLeaf = false)
    )

    /** The coordinator's arm throws: its supervisor restarts it. */
    def crash(c: ActorRef[Command]): Unit =
      c ! QueueMissingNodes(null)
      settle(c)

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

  "A healing coordinator" should "not start the restart's full rebuild while a walk runs (hunter W1, vault-r3 W1)" taggedAs UnitTest in {
    val rig = new Rig()
    val c = rig.spawnGeneration()
    c ! HealingCheckCompletion // nothing pending and no clean pass yet, so the check walks
    rig.settle(c)
    withClue("the check's walk is queued: ")(rig.ec.pending shouldBe 1)
    c ! RestartFullRebuild(rig.trie.root.hash, rig.emptyPath) // and the restart's own walk start arrives
    rig.settle(c)
    withClue("one walk at a time on the shared queue: ")(rig.ec.pending shouldBe 1)
    rig.drive(c)
    rig.awaitComplete()
  }

  it should "drop the restart's full rebuild of a root a re-peg has superseded (beacon W1-r4)" taggedAs UnitTest in {
    val rig = new Rig()
    val c = rig.spawnGeneration()
    c ! HealingPivotRefreshed(rig.otherRoot)
    rig.settle(c)
    withClue("the re-peg to a root not on disk seeds it and starts no walk: ")(rig.ec.pending shouldBe 0)
    c ! RestartFullRebuild(rig.trie.root.hash, rig.emptyPath) // for the root the coordinator has left
    rig.settle(c)
    withClue("no walk of the superseded root: ")(rig.ec.pending shouldBe 0)
  }

  /** A supervisor restart while incarnation 1's walk is queued: incarnation 2 must not start a walk of its own on the
    * same queue until that walk has ended, and that walk's end (a clean pass here) must not count for incarnation 2.
    * `startWalk` starts incarnation 1's walk.
    */
  private def walkAcrossSupervisorRestart(startWalk: (Rig, ActorRef[Command]) => Unit): Unit =
    val rig = new Rig()
    val c = rig.spawnSupervised()
    startWalk(rig, c)
    withClue("incarnation 1's walk is queued: ")(rig.ec.pending shouldBe 1)
    rig.crash(c)
    c ! HealingCheckCompletion // incarnation 2: nothing pending, no clean pass of its own, so it would walk
    rig.settle(c)
    withClue("one walk at a time across incarnations: ")(rig.ec.pending shouldBe 1)
    rig.ec.runNext() // incarnation 1's walk ends clean
    rig.settle(c)
    withClue("no completion from a replaced incarnation's walk: ")(rig.completions() shouldBe 0)
    withClue("nothing vouched for by a replaced incarnation's walk: ")(rig.cleanWalkRoot.get() shouldBe null)
    rig.drive(c) // incarnation 2's own walk
    rig.awaitComplete()

  it should "ignore the clean verification of a replaced incarnation's walk, and walk itself after it (beacon W1-r4)" taggedAs UnitTest in {
    val startWalk: (Rig, ActorRef[Command]) => Unit = (rig, c) =>
      c ! HealingCheckCompletion // incarnation 1 walks
      rig.settle(c)
    walkAcrossSupervisorRestart(startWalk)
  }

  it should "ignore the clean full rebuild of a replaced incarnation's walk: no completion (beacon W1-r4)" taggedAs UnitTest in {
    val startWalk: (Rig, ActorRef[Command]) => Unit = (rig, c) =>
      c ! StartTrieNodeHealing(rig.trie.root.hash) // the root is on disk: the full rebuild starts
      rig.settle(c)
    walkAcrossSupervisorRestart(startWalk)
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

  it should "not complete, nor vouch, when a rebuild walk of a root a re-peg has left ends clean (hunter W2, forge W-r4-1, vault-r3 W2)" taggedAs UnitTest in {
    val rig = new Rig(otherOnDisk = true)
    val c = rig.spawnGeneration()
    c ! StartTrieNodeHealing(rig.trie.root.hash)
    rig.settle(c)
    withClue("the rebuild is queued: ")(rig.ec.pending shouldBe 1)
    c ! HealingPivotRefreshed(
      rig.otherRoot
    ) // a re-peg mid-walk, to a root on disk: its walk waits for the rebuild's end
    rig.settle(c)
    withClue("no second walk: ")(rig.ec.pending shouldBe 1)
    rig.ec.runNext() // the rebuild of the root the heal has left ends clean
    rig.settle(c)
    withClue("nothing vouched for by a walk of a superseded root: ")(rig.cleanWalkRoot.get() shouldBe null)
    withClue("no completion from it: ")(rig.completions() shouldBe 0)
    c ! HealingCheckCompletion // the check after it: a full walk of the current root
    rig.drive(c)
    rig.awaitComplete()
    withClue("vouched for once a walk of the current root is clean: ")(rig.cleanWalkRoot.get() shouldBe rig.otherRoot)
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

  // Spec 022 review vault: the walk's executor is shutting down, so its body never runs and could not give the lease back.
  it should "give the lease back when the executor refuses the walk" taggedAs UnitTest in {
    val refusing = new scala.concurrent.ExecutionContext:
      def execute(runnable: Runnable): Unit =
        throw new java.util.concurrent.RejectedExecutionException("shutting down")
      def reportFailure(cause: Throwable): Unit = ()
    val rig = new Rig(writerEc = Some(refusing))
    val c = rig.spawnGeneration()
    c ! HealingCheckCompletion
    rig.settle(c)
    com.chipprbots.ethereum.blockchain.sync.snap.heal.WalkLease.of(rig.queue).held shouldBe false
    withClue("the walk is not left looking as if it runs: a later check can start one: ") {
      c ! HealingCheckCompletion
      rig.settle(c)
      com.chipprbots.ethereum.blockchain.sync.snap.heal.WalkLease.of(rig.queue).held shouldBe false
    }
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
    val rig = new Rig()
    val c = rig.spawnSupervised()
    c ! HealingCheckCompletion // incarnation 1 walks
    rig.settle(c)
    rig.crash(c)
    rig.ec.runNext() // incarnation 1's walk stops at once
    withClue("the replaced walk did not walk: ")(rig.queue.enqueued shouldBe 0)
    rig.settle(c)
  }

  it should "say a walk was refused, and start none (T038a)" taggedAs UnitTest in {
    val rig = new Rig()
    val gen1 = rig.spawnGeneration()
    gen1 ! HealingCheckCompletion
    rig.settle(gen1)
    val capture = new com.chipprbots.ethereum.blockchain.sync.snap.SnapLogCapture
    try
      val gen2 = rig.spawnGeneration()
      gen2 ! HealingCheckCompletion
      rig.settle(gen2)
      withClue("the refusal is said: ")(capture.count("Frontier walk not started") shouldBe 1)
      rig.completions() shouldBe 0
      testKit.stop(gen2)
    finally capture.close()
  }

  it should "start the completion check when a rebuild of a root a re-peg has left ends: the deferred verification runs (T038a)" taggedAs UnitTest in {
    val rig = new Rig(otherOnDisk = true)
    val c = rig.spawnGeneration()
    c ! StartTrieNodeHealing(rig.trie.root.hash) // the rebuild starts
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
    val current = new java.util.concurrent.atomic.AtomicReference[ByteString]()
    val rig = new Rig(otherOnDisk = true, currentRoot = Some(current))
    current.set(rig.trie.root.hash)
    val c = rig.spawnSupervised()
    current.set(rig.otherRoot) // as the controller does, before it sends the re-peg
    rig.crash(c) // the re-peg never reaches incarnation 1: it is lost with it
    c ! HealingCheckCompletion // incarnation 2 walks
    rig.drive(c)
    rig.awaitComplete()
    withClue("what the clean walk covered is the current root: ")(rig.cleanWalkRoot.get() shouldBe rig.otherRoot)
  }

  // ---- spec 022 T038c, slice-2 notes ----------------------------------------------------------------------------------

  it should "not count a walk across A -> B -> A as a walk of A: no clean verdict (T038c, A-B-A)" taggedAs UnitTest in {
    val rig = new Rig(otherOnDisk = true)
    val c = rig.spawnGeneration()
    c ! StartTrieNodeHealing(rig.trie.root.hash) // the rebuild of A starts
    rig.settle(c)
    c ! HealingPivotRefreshed(rig.otherRoot)
    rig.settle(c)
    c ! HealingPivotRefreshed(rig.trie.root.hash) // back to A, with A's outstanding frontier dropped twice
    rig.settle(c)
    rig.ec.runNext() // the rebuild, started before both re-pegs, ends clean
    rig.settle(c)
    withClue("its frontier was dropped under it: it vouches for nothing: ") {
      rig.cleanWalkRoot.get() shouldBe null
      rig.completions() shouldBe 0
    }
    rig.drive(c) // the check walks A again
    rig.awaitComplete()
    withClue("the walk that started after the last re-peg does: ")(rig.cleanWalkRoot.get() shouldBe rig.trie.root.hash)
  }
