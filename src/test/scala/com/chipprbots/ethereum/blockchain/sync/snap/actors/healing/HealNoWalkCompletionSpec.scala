package com.chipprbots.ethereum.blockchain.sync.snap.actors.healing

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.util.ByteString

import scala.concurrent.duration.*

import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.snap.SNAPRequestTracker
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController
import com.chipprbots.ethereum.blockchain.sync.snap.StorageScheme
import com.chipprbots.ethereum.blockchain.sync.snap.actors.HealingStatistics
import com.chipprbots.ethereum.blockchain.sync.snap.actors.HealingTrieFixtures
import com.chipprbots.ethereum.blockchain.sync.snap.actors.PathHealPresenceFixtures
import com.chipprbots.ethereum.blockchain.sync.snap.actors.PathHealPresenceFixtures.Placed
import com.chipprbots.ethereum.blockchain.sync.snap.actors.PathHealPresenceFixtures.Trie
import com.chipprbots.ethereum.blockchain.sync.snap.actors.PathHealPresenceFixtures.readAtPath
import com.chipprbots.ethereum.blockchain.sync.snap.actors.TrieNodeHealingCoordinator
import com.chipprbots.ethereum.blockchain.sync.snap.actors.TrieNodeHealingCoordinator.Command
import com.chipprbots.ethereum.blockchain.sync.snap.actors.TrieNodeHealingCoordinator.HealingEntry
import com.chipprbots.ethereum.blockchain.sync.snap.actors.TrieNodeHealingCoordinatorImpl
import com.chipprbots.ethereum.blockchain.sync.snap.heal.HealInvariant
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.db.storage.InMemoryBfsQueueStorage
import com.chipprbots.ethereum.db.storage.PathNodeStorage
import com.chipprbots.ethereum.network.p2p.messages.SNAP
import com.chipprbots.ethereum.testing.PeerTestHelpers
import com.chipprbots.ethereum.testing.Tags.UnitTest
import com.chipprbots.ethereum.testing.TestMptStorage

/** Spec 022 5d (T050, T051): where the invariant marker stands, healing completes with no verification walk, and the
  * marker is read at the decision. Everywhere else the walk runs as it did. The fixture is `accountTrieOld` (root over
  * an extension and two branches, over account leaves with no storage).
  *
  * A walk clears its queue as it starts and as it ends, so the queue says whether one ran. Walks and Hash flushes run
  * on a manual executor, so nothing happens off the actor thread until the test says so.
  */
class HealNoWalkCompletionSpec extends ScalaTestWithActorTestKit() with AnyFlatSpecLike with Matchers:

  implicit private val classicSystem: org.apache.pekko.actor.ActorSystem = system.classicSystem
  implicit private val actorTestKit: ActorTestKit = testKit

  import HealRequestFixtures.*

  private val Schemes = Seq(StorageScheme.Hash, StorageScheme.Path)
  private val EmptyPath: ByteString = compact(Array.empty[Byte])
  private val NopLog = org.slf4j.helpers.NOPLogger.NOP_LOGGER

  final private class Rig(
      scheme: StorageScheme,
      val trie: Trie = PathHealPresenceFixtures.accountTrieOld(),
      absent: Set[String] = Set.empty,
      marked: Boolean = true,
      mayStand: Boolean = true,
      moving: Boolean = true,
      unreadable: Option[String] = None
  ):
    val ec = new ManualExecutionContext
    val appState = new AppStateStorage(EphemDataSource())
    if marked then appState.stampSnapHealInvariant().commit()
    private val clears = new AtomicInteger(0)
    val queue: InMemoryBfsQueueStorage = new InMemoryBfsQueueStorage:
      override def clear(): Unit =
        clears.incrementAndGet()
        super.clear()
    val hashStore = new NodeReadFailingMptStorage(unreadable.fold(ByteString.empty)(trie(_).hash))
    val pathSource =
      new KeyReadFailingDataSource(AccountNs, unreadable.fold(ByteString.empty)(l => accountKey(trie(l).nibbles)))
    hashStore.failing = unreadable.isDefined
    pathSource.failing = unreadable.isDefined
    val pns = new PathNodeStorage(pathSource)
    seed(trie.nodes.filterNot(n => absent(n.label)))
    val decided = new AtomicReference[ByteString]()
    val cleanRoot = new AtomicReference[ByteString]()
    val peer = new ScriptedPeer(testKit)
    val controller: TestProbe[SNAPSyncController.Command] = createTestProbe[SNAPSyncController.Command]()
    val coordinator: ActorRef[Command] = HealingTrieFixtures.spawnCoordinator(
      stateRoot = trie.root.hash,
      networkPeerManager = peer.ref,
      requestTracker = new SNAPRequestTracker()(classicSystem.scheduler),
      mptStorage = if scheme == StorageScheme.Hash then hashStore else new TestMptStorage(),
      batchSize = 16,
      snapSyncController = controller.ref,
      healingWriterEcOverride = Some(ec),
      bfsQueueStorageOpt = Some(queue),
      storageScheme = scheme,
      pathNodeStorageOpt = Option.when(scheme == StorageScheme.Path)(pns),
      movingRootDeltaHeal = moving,
      cleanWalkRoot = Some(cleanRoot),
      decidedRoot = Some(decided),
      markerStore = Some(appState),
      markerMayStand = mayStand
    )
    peer.coordinator.set(coordinator)

    def seed(nodes: Seq[Placed]): Unit =
      if scheme == StorageScheme.Hash then PathHealPresenceFixtures.seedHash(hashStore, nodes)
      else PathHealPresenceFixtures.seedPath(pns, nodes)

    def start(): Unit = coordinator ! TrieNodeHealingCoordinator.StartTrieNodeHealing(trie.root.hash)

    def available(): Unit =
      coordinator ! TrieNodeHealingCoordinator.HealingPeerAvailable(
        PeerTestHelpers.createTestPeer("no-walk-peer", createTestProbe[Any]().ref.toClassic)
      )

    /** Mailbox barrier, four deep: a message sent before the call, the check it sends, and what those send. */
    def settle(): Unit =
      (1 to 4).foreach { _ =>
        val probe = createTestProbe[HealingStatistics]()
        coordinator ! TrieNodeHealingCoordinator.HealingGetProgress(probe.ref)
        probe.receiveMessage(30.seconds)
      }

    /** Run what waits on the writer executor and what follows from it. */
    def drive(steps: Int = 32): Unit =
      settle()
      var left = steps
      while left > 0 && ec.pending > 0 do
        ec.runNext()
        settle()
        left -= 1

    def walked: Boolean = clears.get() > 0

    /** The walks that have ended: each clears its queue at both ends. */
    def walksEnded: Int = clears.get() / 2

    /** What the controller has received so far. The probe is an actor of its own: a barrier through it, sent after what
      * is being looked for, shows it has all of that.
      */
    def seen(): Seq[SNAPSyncController.Command] =
      val barrier = SNAPSyncController.ProgressNodesHealed(Long.MinValue)
      controller.ref ! barrier
      val out = Seq.newBuilder[SNAPSyncController.Command]
      var next = controller.receiveMessage(30.seconds)
      while next != barrier do
        out += next
        next = controller.receiveMessage(30.seconds)
      out.result()

    def completions(messages: Seq[SNAPSyncController.Command]): Int =
      messages.count(_ == SNAPSyncController.StateHealingComplete)

    def onDisk(n: Placed): Boolean =
      if scheme == StorageScheme.Hash then
        try
          hashStore.get(n.hash.toArray); true
        catch case _: Exception => false
      else readAtPath(pns, n).map(ByteString(_)).contains(n.encoded)

    def asked(n: Placed): Int = peer.requests.flatten.count(_ == n.pathset)

    /** Run what comes, a peer round trip at a time, until `done`. */
    def driveUntil(what: String)(done: => Boolean): Unit =
      val deadline = 30.seconds.fromNow
      val pause = createTestProbe[Any]()
      while !done do
        withClue(s"$what within the deadline: ")(deadline.hasTimeLeft() shouldBe true)
        drive()
        pause.expectNoMessage(5.millis)

    /** The peer's round trips only, with what is on the executor left there: a walk that starts stays at its start. */
    def awaitWalkLaunched(): Unit =
      val deadline = 30.seconds.fromNow
      val pause = createTestProbe[Any]()
      while ec.pending == 0 do
        withClue("a walk launched within the deadline: ")(deadline.hasTimeLeft() shouldBe true)
        settle()
        pause.expectNoMessage(5.millis)

  // ---- the start arm --------------------------------------------------------------------------------------------------

  for scheme <- Schemes do
    "A standing marker" should s"let healing complete from the start arm with no walk over a present root, on $scheme" taggedAs UnitTest in {
      val rig = new Rig(scheme)
      rig.start()
      rig.drive()
      withClue("no walk: ")(rig.walked shouldBe false)
      rig.completions(rig.seen()) shouldBe 1
      withClue("the decision is recorded: ")(rig.decided.get() shouldBe rig.trie.root.hash)
      withClue("and it is no proof, which only a walk gives: ")(rig.cleanRoot.get() shouldBe null)
      testKit.stop(rig.coordinator)
    }

    it should s"leave the walk with no marker, which is what proves the root, on $scheme" taggedAs UnitTest in {
      val rig = new Rig(scheme, marked = false)
      rig.start()
      rig.drive()
      withClue("the walk ran: ")(rig.walked shouldBe true)
      rig.completions(rig.seen()) shouldBe 1
      rig.decided.get() shouldBe rig.trie.root.hash
      rig.cleanRoot.get() shouldBe rig.trie.root.hash
      testKit.stop(rig.coordinator)
    }

    it should s"walk whatever the key says where the marker cannot hold, on $scheme" taggedAs UnitTest in {
      val rig = new Rig(scheme, mayStand = false)
      rig.appState.hasSnapHealInvariant() shouldBe true
      rig.start()
      rig.drive()
      rig.walked shouldBe true
      rig.completions(rig.seen()) shouldBe 1
      rig.cleanRoot.get() shouldBe rig.trie.root.hash
      testKit.stop(rig.coordinator)
    }

    // ---- read at the decision, not cached ---------------------------------------------------------------------------

    it should s"be read at the decision: a run that loses track mid-run walks, on $scheme" taggedAs UnitTest in {
      val rig = new Rig(scheme, absent = Set("root"))
      rig.start()
      rig.available()
      val request = rig.peer.manual.expectMessageType[SNAP.GetTrieNodes](30.seconds)
      request.paths shouldBe Seq(rig.trie.root.pathset)
      HealInvariant.lostTrack(rig.appState, "a task file that cannot be read", NopLog) shouldBe true
      rig.peer.answerFrom(request, rig.trie.served)
      rig.driveUntil("the root on disk")(rig.onDisk(rig.trie.root))
      rig.drive()
      withClue("the marker went mid-run, so the check walks: ")(rig.walked shouldBe true)
      rig.completions(rig.seen()) shouldBe 1
      rig.cleanRoot.get() shouldBe rig.trie.root.hash
      testKit.stop(rig.coordinator)
    }

    it should s"be read at the decision: still standing there, it takes no walk, on $scheme" taggedAs UnitTest in {
      val rig = new Rig(scheme, absent = Set("root"))
      rig.start()
      rig.available()
      val request = rig.peer.manual.expectMessageType[SNAP.GetTrieNodes](30.seconds)
      rig.peer.answerFrom(request, rig.trie.served)
      rig.driveUntil("the root on disk")(rig.onDisk(rig.trie.root))
      rig.drive()
      rig.walked shouldBe false
      rig.completions(rig.seen()) shouldBe 1
      testKit.stop(rig.coordinator)
    }

    // ---- the re-peg arm ---------------------------------------------------------------------------------------------

    it should s"take a check on a re-peg to a root already present, with no walk, on $scheme" taggedAs UnitTest in {
      val rig = new Rig(scheme)
      val next = PathHealPresenceFixtures.accountTrieNew()
      rig.start()
      rig.drive()
      rig.completions(rig.seen()) shouldBe 1
      rig.seed(next.changedFrom(rig.trie))
      rig.coordinator ! TrieNodeHealingCoordinator.HealingPivotRefreshed(next.root.hash)
      rig.drive()
      rig.walked shouldBe false
      rig.completions(rig.seen()) shouldBe 1
      rig.decided.get() shouldBe next.root.hash
      testKit.stop(rig.coordinator)
    }

    it should s"leave the re-peg's walk with no marker, on $scheme" taggedAs UnitTest in {
      val rig = new Rig(scheme, marked = false)
      val next = PathHealPresenceFixtures.accountTrieNew()
      rig.start()
      rig.drive()
      rig.completions(rig.seen()) shouldBe 1
      val walksBefore = rig.walksEnded
      rig.seed(next.changedFrom(rig.trie))
      rig.coordinator ! TrieNodeHealingCoordinator.HealingPivotRefreshed(next.root.hash)
      rig.drive()
      rig.walksEnded shouldBe walksBefore + 1
      rig.completions(rig.seen()) shouldBe 1
      rig.cleanRoot.get() shouldBe next.root.hash
      testKit.stop(rig.coordinator)
    }

    // ---- the dead-pulse watchdog ------------------------------------------------------------------------------------

    it should s"take a check at the dead-pulse watchdog, with no walk, on $scheme" taggedAs UnitTest in {
      val rig = new Rig(scheme) // Never started: nothing but the watchdog looks.
      (1 to 2).foreach(_ => rig.coordinator ! TrieNodeHealingCoordinator.HealingStagnationCheck)
      rig.drive()
      withClue("not before its third pulse: ")(rig.completions(rig.seen()) shouldBe 0)
      rig.coordinator ! TrieNodeHealingCoordinator.HealingStagnationCheck
      rig.drive()
      rig.walked shouldBe false
      rig.completions(rig.seen()) shouldBe 1
      testKit.stop(rig.coordinator)
    }

    it should s"leave the dead-pulse watchdog's walk with no marker, on $scheme" taggedAs UnitTest in {
      val rig = new Rig(scheme, marked = false)
      (1 to 3).foreach(_ => rig.coordinator ! TrieNodeHealingCoordinator.HealingStagnationCheck)
      rig.drive()
      rig.walked shouldBe true
      rig.completions(rig.seen()) shouldBe 1
      testKit.stop(rig.coordinator)
    }

    // ---- the root absent --------------------------------------------------------------------------------------------

    it should s"seed an absent root at the gate under moving-root, with no walk, on $scheme" taggedAs UnitTest in {
      val rig = new Rig(scheme, absent = Set("root"))
      rig.available()
      rig.coordinator ! TrieNodeHealingCoordinator.HealingCheckCompletion
      val request = rig.peer.manual.expectMessageType[SNAP.GetTrieNodes](30.seconds)
      request.paths shouldBe Seq(rig.trie.root.pathset)
      rig.walked shouldBe false
      withClue("nothing said yet: ")(rig.completions(rig.seen()) shouldBe 0)
      rig.peer.answerFrom(request, rig.trie.served)
      rig.driveUntil("the root on disk")(rig.onDisk(rig.trie.root))
      rig.drive()
      rig.walked shouldBe false
      rig.completions(rig.seen()) shouldBe 1
      testKit.stop(rig.coordinator)
    }

    it should s"say the root is unservable at the gate without moving-root, with no walk, on $scheme" taggedAs UnitTest in {
      val rig = new Rig(scheme, absent = Set("root"), moving = false)
      rig.coordinator ! TrieNodeHealingCoordinator.HealingCheckCompletion
      rig.drive()
      rig.walked shouldBe false
      rig.seen() should contain(SNAPSyncController.HealingRootUnservable(rig.trie.root.hash))
      rig.decided.get() shouldBe null
      testKit.stop(rig.coordinator)
    }

    // The loop forge and beacon found: the orphan drop can leave the root absent, a reseed rediscovers the same node, and
    // each lap heals one, so stagnation never fires, at seconds a lap. Here `brX`'s stored child cannot be read: it is
    // given up on, so the held `ext` and `root` above it have nothing that can complete them.
    it should s"fall back to the walk past 3 reseeds of a root, reached by the orphan drop, on $scheme" taggedAs UnitTest in {
      val rig = new Rig(scheme, absent = Set("root", "ext", "brX"), unreadable = Some("leafX2"))
      rig.peer.serveFrom.set(Some(rig.trie.served))
      rig.start()
      rig.available()
      rig.awaitWalkLaunched()
      withClue("the start's seed, and 3 reseeds by the gate: ")(rig.asked(rig.trie.root) shouldBe 4)
      withClue("no walk until then: ")(rig.walked shouldBe false)
      rig.ec.runNext()
      rig.settle()
      rig.walked shouldBe true
      rig.completions(rig.seen()) shouldBe 0
      testKit.stop(rig.coordinator)
    }

    it should s"count reseeds per root, so a re-peg starts the count over, on $scheme" taggedAs UnitTest in {
      val rig = new Rig(scheme, absent = Set("root", "ext", "brX"), unreadable = Some("leafX2"))
      val next = PathHealPresenceFixtures.accountTrieNew()
      // Answered by hand until the first reseed, so the root goes no further than that before the re-peg.
      rig.start()
      rig.available()
      def answerUntilAsked(n: Placed, times: Int): Unit =
        val deadline = 30.seconds.fromNow
        while rig.asked(n) < times do
          withClue("the reseed within the deadline: ")(deadline.hasTimeLeft() shouldBe true)
          rig.peer.answerFrom(rig.peer.manual.expectMessageType[SNAP.GetTrieNodes](30.seconds), rig.trie.served)
          rig.settle()
      answerUntilAsked(rig.trie.root, 2)
      val request = rig.peer.manual.expectMessageType[SNAP.GetTrieNodes](30.seconds)
      request.paths shouldBe Seq(rig.trie.root.pathset)
      rig.peer.serveFrom.set(Some(next.served))
      // Both roots are asked for at the empty path.
      val askedBefore = rig.asked(next.root)
      rig.coordinator ! TrieNodeHealingCoordinator.HealingPivotRefreshed(next.root.hash)
      rig.available()
      rig.awaitWalkLaunched()
      withClue("the re-peg's seed, and 3 reseeds by the gate: ")(rig.asked(next.root) - askedBefore shouldBe 4)
      testKit.stop(rig.coordinator)
    }

    // What `reverifyCurrentRoot` calls runs no gate: a re-peg under the fallback walk ends in that arm, and a second walk
    // from there would never have read the marker.
    it should s"go through the check, not a second walk, when a re-peg ends the fallback walk stale, on $scheme" taggedAs UnitTest in {
      val rig = new Rig(scheme, absent = Set("root", "ext", "brX"), unreadable = Some("leafX2"))
      val next = PathHealPresenceFixtures.accountTrieNew()
      rig.peer.serveFrom.set(Some(rig.trie.served))
      rig.start()
      rig.available()
      rig.awaitWalkLaunched()
      rig.hashStore.failing = false
      rig.pathSource.failing = false
      rig.seed(next.nodes)
      rig.coordinator ! TrieNodeHealingCoordinator.HealingPivotRefreshed(next.root.hash)
      rig.settle()
      rig.ec.runNext()
      rig.driveUntil("the completion of the new root")(rig.decided.get() == next.root.hash)
      withClue("one walk, the one the fallback started: ")(rig.walksEnded shouldBe 1)
      withClue("nothing more to run: ")(rig.ec.pending shouldBe 0)
      rig.completions(rig.seen()) shouldBe 1
      testKit.stop(rig.coordinator)
    }

    // ---- one signal per root -----------------------------------------------------------------------------------------

    it should s"say a root is complete once, whatever asks again, and again after a re-peg, on $scheme" taggedAs UnitTest in {
      val rig = new Rig(scheme)
      val next = PathHealPresenceFixtures.accountTrieNew()
      rig.start()
      rig.drive()
      rig.completions(rig.seen()) shouldBe 1
      (1 to 3).foreach(_ => rig.coordinator ! TrieNodeHealingCoordinator.HealingCheckCompletion)
      // Six pulses is what a watchdog with no latch needs to say it twice over.
      (1 to 6).foreach(_ => rig.coordinator ! TrieNodeHealingCoordinator.HealingStagnationCheck)
      rig.drive()
      rig.completions(rig.seen()) shouldBe 0
      rig.seed(next.changedFrom(rig.trie))
      rig.coordinator ! TrieNodeHealingCoordinator.HealingPivotRefreshed(next.root.hash)
      rig.drive()
      withClue("a re-peg resets it: ")(rig.completions(rig.seen()) shouldBe 1)
      testKit.stop(rig.coordinator)
    }

    it should s"say it again if the record was taken from under it, so a cleared decision cannot strand the run, on $scheme" taggedAs UnitTest in {
      val rig = new Rig(scheme)
      rig.start()
      rig.drive()
      rig.completions(rig.seen()) shouldBe 1
      rig.decided.set(null)
      rig.coordinator ! TrieNodeHealingCoordinator.HealingCheckCompletion
      rig.drive()
      rig.completions(rig.seen()) shouldBe 1
      rig.decided.get() shouldBe rig.trie.root.hash
      testKit.stop(rig.coordinator)
    }

    it should s"set the record before the signal it backs goes out, on $scheme" taggedAs UnitTest in {
      val atSignal = new AtomicReference[ByteString]()
      val decided = new AtomicReference[ByteString]()
      val received = createTestProbe[String]()
      val controller = testKit.spawn(Behaviors.receiveMessage[SNAPSyncController.Command] {
        case SNAPSyncController.StateHealingComplete =>
          atSignal.set(decided.get())
          received.ref ! "signal"
          Behaviors.same
        case _ => Behaviors.same
      })
      val trie = PathHealPresenceFixtures.accountTrieOld()
      val appState = new AppStateStorage(EphemDataSource())
      appState.stampSnapHealInvariant().commit()
      val hashStore = new TestMptStorage()
      val pns = new PathNodeStorage(EphemDataSource())
      if scheme == StorageScheme.Hash then PathHealPresenceFixtures.seedHash(hashStore, trie.nodes)
      else PathHealPresenceFixtures.seedPath(pns, trie.nodes)
      val coordinator = HealingTrieFixtures.spawnCoordinator(
        stateRoot = trie.root.hash,
        networkPeerManager = new ScriptedPeer(testKit).ref,
        requestTracker = new SNAPRequestTracker()(classicSystem.scheduler),
        mptStorage = hashStore,
        batchSize = 16,
        snapSyncController = controller,
        storageScheme = scheme,
        pathNodeStorageOpt = Option.when(scheme == StorageScheme.Path)(pns),
        movingRootDeltaHeal = true,
        decidedRoot = Some(decided),
        markerStore = Some(appState),
        markerMayStand = true
      )
      coordinator ! TrieNodeHealingCoordinator.StartTrieNodeHealing(trie.root.hash)
      received.expectMessage(30.seconds, "signal")
      atSignal.get() shouldBe trie.root.hash
      testKit.stop(coordinator)
    }

    // ---- what a no-walk completion leaves of an earlier walk's proof -------------------------------------------------

    it should s"not let an earlier walk's proof of a root stand beside a completion with no walk of it, on $scheme" taggedAs UnitTest in {
      val rig = new Rig(scheme)
      rig.cleanRoot.set(rig.trie.root.hash)
      rig.start()
      rig.drive()
      rig.completions(rig.seen()) shouldBe 1
      withClue("a lostTrack after this decision has no stamp to undo it: ")(rig.cleanRoot.get() shouldBe null)
      testKit.stop(rig.coordinator)
    }

    // ---- a seed the book refuses --------------------------------------------------------------------------------------

    // A live request for the root's key with nothing queued or in flight behind it. No message sequence is known to reach
    // this, which is why it is put there by hand: `enqueueRequest` would return false and the gate would sit silent.
    it should s"not sit silent when the book holds a request for the root that nothing will answer, on $scheme" taggedAs UnitTest in {
      val trie = PathHealPresenceFixtures.accountTrieOld()
      val appState = new AppStateStorage(EphemDataSource())
      appState.stampSnapHealInvariant().commit()
      val peer = new ScriptedPeer(testKit)
      val controller = createTestProbe[SNAPSyncController.Command]()
      val impl = new AtomicReference[TrieNodeHealingCoordinatorImpl]()
      val pns = new PathNodeStorage(EphemDataSource())
      val coordinator = testKit.spawn(
        Behaviors.setup[Command] { context =>
          Behaviors.withTimers { timers =>
            val coordinatorImpl = new TrieNodeHealingCoordinatorImpl(
              context,
              timers,
              initialStateRoot = trie.root.hash,
              networkPeerManager = peer.ref,
              requestTracker = new SNAPRequestTracker()(classicSystem.scheduler),
              mptStorage = new TestMptStorage(),
              batchSize = 16,
              snapSyncController = controller.ref,
              concurrency = 16,
              stateIngest = HealingTrieFixtures.healingIngest(
                None,
                pathNodeStore = Option.when(scheme == StorageScheme.Path)(pns),
                appStateStore = Some(appState)
              ),
              storageScheme = scheme,
              movingRootDeltaHeal = true,
              markerMayStand = true
            )
            impl.set(coordinatorImpl)
            coordinatorImpl.start()
          }
        }
      )
      peer.coordinator.set(coordinator)
      val barrier = createTestProbe[HealingStatistics]()
      coordinator ! TrieNodeHealingCoordinator.HealingGetProgress(barrier.ref)
      barrier.receiveMessage(30.seconds)
      val entry = HealingEntry(Seq(EmptyPath), trie.root.hash)
      impl.get().healRequests.add(impl.get().healRequestFor(entry)): Unit
      coordinator ! TrieNodeHealingCoordinator.HealingPeerAvailable(
        PeerTestHelpers.createTestPeer("no-walk-peer", createTestProbe[Any]().ref.toClassic)
      )
      coordinator ! TrieNodeHealingCoordinator.HealingCheckCompletion
      val request = peer.manual.expectMessageType[SNAP.GetTrieNodes](30.seconds)
      request.paths shouldBe Seq(Seq(EmptyPath))
      testKit.stop(coordinator)
    }
