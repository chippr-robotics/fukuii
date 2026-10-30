package com.chipprbots.ethereum.blockchain.sync.snap.actors.healing

import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.testkit.typed.scaladsl.FishingOutcomes
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe
import org.apache.pekko.actor.typed.ActorRef
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
import com.chipprbots.ethereum.blockchain.sync.snap.actors.PathHealPresenceFixtures.readAtPath
import com.chipprbots.ethereum.blockchain.sync.snap.actors.TrieNodeHealingCoordinator
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.PathNodeStorage
import com.chipprbots.ethereum.mpt.MptNode
import com.chipprbots.ethereum.network.p2p.messages.SNAP
import com.chipprbots.ethereum.testing.PeerTestHelpers
import com.chipprbots.ethereum.testing.Tags.UnitTest
import com.chipprbots.ethereum.testing.TestMptStorage

/** A trie store whose batched reads that include `hash` throw while `failing` is set: a stored node that cannot be read
  * (a RocksDB Corruption, say). The walks never read it while its parent is absent, so only the parent's child lookup
  * fails.
  */
final private class NodeReadFailingMptStorage(hash: ByteString) extends TestMptStorage:
  @volatile var failing: Boolean = true
  override def multiGetNodes(hashes: Seq[Array[Byte]]): Seq[Option[MptNode]] =
    if failing && hashes.exists(h => ByteString(h) == hash) then
      throw new RuntimeException("injected node read failure")
    super.multiGetNodes(hashes)

/** The Path-scheme counterpart: reads of `key` in `namespace` throw while `failing` is set. */
final private class KeyReadFailingDataSource(namespace: IndexedSeq[Byte], key: ByteString)
    extends EphemDataSource(Map.empty):
  @volatile var failing: Boolean = true
  override def getOptimized(ns: IndexedSeq[Byte], k: Array[Byte]): Option[Array[Byte]] =
    if failing && ns == namespace && ByteString(k) == key then throw new RuntimeException("injected path read failure")
    super.getOptimized(ns, k)

/** Spec 022 slice 2, fix round 3 (hunter-r3 C1, forge F6, beacon W1-r3 and N3-r3): no `StateHealingComplete` without a
  * clean verification walk of the current root in this coordinator incarnation.
  *
  * The completion gate used to complete with no walk when `totalNodesHealed == 0` ("never given work"). Since a fill
  * whose children cannot be read is not counted as healed and is given up on at the third such fill, a fresh
  * coordinator whose only work was given up reached that arm with the node still missing; so did a coordinator
  * incarnation built by a supervisor restart (`Behaviors.setup` runs again: a new instance, `totalNodesHealed = 0`, no
  * `StartTrieNodeHealing`) on a completion check left in its mailbox.
  *
  * Fixture: `accountTrieOld` on disk except `brX`, whose parent `ext` is on disk, so the walk queues `brX` with no
  * parent (forge: "the given-up node has no parents"). `leafX1`, one of `brX`'s stored children, cannot be read while
  * the fault is on.
  */
class HealGiveUpCompletionSpec extends ScalaTestWithActorTestKit() with AnyFlatSpecLike with Matchers:

  implicit private val classicSystem: org.apache.pekko.actor.ActorSystem = system.classicSystem
  implicit private val actorTestKit: ActorTestKit = testKit

  import HealRequestFixtures.*

  /** A coordinator over the trie on disk minus `brX`, with `leafX1` unreadable while the fault is on. Its walks and
    * flushes run on `ec` only when the test runs them, so every step is ordered by the test.
    */
  final private class Rig(scheme: StorageScheme, hole: Boolean = true):
    val ec = new ManualExecutionContext
    val trie: PathHealPresenceFixtures.Trie = PathHealPresenceFixtures.accountTrieOld()
    private val stored: Seq[Placed] = trie.nodes.filterNot(n => hole && n.label == "brX")
    val hashStore = new NodeReadFailingMptStorage(trie("leafX1").hash)
    val pathSource = new KeyReadFailingDataSource(AccountNs, accountKey(trie("leafX1").nibbles))
    val pns = new PathNodeStorage(pathSource)
    if scheme == StorageScheme.Hash then PathHealPresenceFixtures.seedHash(hashStore, stored)
    else PathHealPresenceFixtures.seedPath(pns, stored)
    val peer = new ScriptedPeer(testKit)
    val controller: TestProbe[SNAPSyncController.Command] = createTestProbe[SNAPSyncController.Command]()
    val coordinator: ActorRef[TrieNodeHealingCoordinator.Command] = HealingTrieFixtures.spawnCoordinator(
      stateRoot = trie.root.hash,
      networkPeerManager = peer.ref,
      requestTracker = new SNAPRequestTracker()(classicSystem.scheduler),
      mptStorage = if scheme == StorageScheme.Hash then hashStore else new TestMptStorage(),
      batchSize = 16,
      snapSyncController = controller.ref,
      healingWriterEcOverride = Some(ec),
      storageScheme = scheme,
      pathNodeStorageOpt = Option.when(scheme == StorageScheme.Path)(pns),
      movingRootDeltaHeal = true
    )
    peer.coordinator.set(coordinator)

    def fault(on: Boolean): Unit =
      hashStore.failing = on
      pathSource.failing = on

    def peerAvailable(): Unit =
      coordinator ! TrieNodeHealingCoordinator.HealingPeerAvailable(
        PeerTestHelpers.createTestPeer("give-up-peer", createTestProbe[Any]().ref.toClassic)
      )

    def onDisk(label: String): Boolean =
      val n = trie(label)
      if scheme == StorageScheme.Hash then
        try
          hashStore.get(n.hash.toArray); true
        catch case _: Exception => false
      else readAtPath(pns, n).map(ByteString(_)).contains(n.encoded)

    /** The next request, which must ask for `brX` only; answered with `brX`. */
    def serveBrX(attempt: Int): Unit =
      val req = peer.manual.expectMessageType[SNAP.GetTrieNodes](30.seconds)
      withClue(s"request $attempt asks for brX: ")(req.paths shouldBe Seq(trie("brX").pathset))
      peer.answerFrom(req, trie.served)

    /** Mailbox barrier, three deep: a message sent before the call, the completion check it sends, and what that check
      * sends have all been handled.
      */
    def settle(): Unit =
      (1 to 3).foreach { _ =>
        val probe = createTestProbe[HealingStatistics]()
        coordinator ! TrieNodeHealingCoordinator.HealingGetProgress(probe.ref)
        probe.receiveMessage(30.seconds)
      }

    /** Run what waits on the writer executor (walks, flushes) and what follows from it, one task at a time, at most
      * `steps` tasks.
      */
    def drive(steps: Int = 24): Unit =
      settle()
      var left = steps
      while left > 0 && ec.pending > 0 do
        ec.runNext()
        settle()
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

    /** Send `message` and wait until it has been handled. */
    def seen(message: TrieNodeHealingCoordinator.Command): Unit =
      coordinator ! message
      settle()

    def stop(): Unit =
      testKit.stop(coordinator)
      testKit.stop(peer.ref)

  private def withRig[A](rig: Rig)(body: Rig => A): A =
    try body(rig)
    finally rig.stop()

  /** A fresh coordinator heals nothing: its only node, brX, is given up on at the third fill whose child read fails. */
  private def giveUpWithNothingHealed(scheme: StorageScheme): Unit =
    withRig(new Rig(scheme)) { rig =>
      rig.coordinator ! TrieNodeHealingCoordinator.StartTrieNodeHealing(rig.trie.root.hash)
      rig.peerAvailable()
      rig.drive() // the rebuild walk runs to its end and finds brX missing
      // The first two fills fail the child read and fetch brX again; the third gives up on it
      // (TrieNodeHealingCoordinator.MaxConsecutiveReadFailures).
      (1 to 3).foreach(rig.serveBrX)
      rig.controller.fishForMessage(30.seconds) {
        case _: SNAPSyncController.HealingStagnated => FishingOutcomes.complete
        case SNAPSyncController.StateHealingComplete =>
          FishingOutcomes.fail("healing declared complete with brX given up and no walk since")
        case _ => FishingOutcomes.continueAndIgnore
      }
      // The controller's answer under the default hold policy.
      rig.coordinator ! TrieNodeHealingCoordinator.HealingResumeDispatch
      rig.settle()
      withClue("no completion with brX given up and missing: ")(rig.completions() shouldBe 0)
      // Only a walk can find brX again: the give-up left nothing queued, in flight or held.
      rig.fault(on = false)
      rig.drive()
      rig.serveBrX(4)
      rig.settle()
      withClue("no completion before brX's fill is written and walked: ")(rig.completions() shouldBe 0)
      rig.drive()
      rig.awaitComplete()
      withClue("brX is on disk when healing completes: ")(rig.onDisk("brX") shouldBe true)
    }

  "A healing coordinator" should "walk, not complete, after a give-up with nothing else healed, and complete once the node is on disk, under the Hash scheme (hunter-r3 C1, forge F6)" taggedAs UnitTest in {
    giveUpWithNothingHealed(StorageScheme.Hash)
  }

  it should "walk, not complete, after a give-up with nothing else healed, and complete once the node is on disk, under the Path scheme (beacon W1-r3)" taggedAs UnitTest in {
    giveUpWithNothingHealed(StorageScheme.Path)
  }

  it should "walk, not complete, when an incarnation that never started gets a completion check, as after a supervisor restart (beacon N3-r3)" taggedAs UnitTest in {
    // What a supervisor restart leaves: a new instance (totalNodesHealed = 0, nothing queued) built from the spawn
    // arguments, no StartTrieNodeHealing replayed, and a completion check the old instance queued.
    withRig(new Rig(StorageScheme.Hash)) { rig =>
      rig.fault(on = false)
      rig.peerAvailable()
      rig.coordinator ! TrieNodeHealingCoordinator.HealingCheckCompletion
      rig.settle()
      withClue("no completion without a walk: ")(rig.completions() shouldBe 0)
      rig.drive()
      rig.serveBrX(1) // the walk found brX missing
      rig.drive()
      rig.awaitComplete()
      withClue("brX is on disk when healing completes: ")(rig.onDisk("brX") shouldBe true)
    }
  }

  it should "complete a coordinator that healed nothing only after a clean walk of its root (forge F6)" taggedAs UnitTest in {
    withRig(new Rig(StorageScheme.Hash, hole = false)) { rig =>
      rig.fault(on = false)
      rig.coordinator ! TrieNodeHealingCoordinator.HealingCheckCompletion
      rig.settle()
      withClue("no completion before the walk has run: ")(rig.completions() shouldBe 0)
      withClue("the check started a walk: ")(rig.ec.pending should be > 0)
      rig.drive()
      rig.awaitComplete()
    }
  }

  /** The give-up escalates to the controller once, without stopping dispatch, and again only after the controller has
    * answered (spec 022 review beacon N2-r3, hunter-r3 N1, forge N13): the pulse's dispatch latch is not used, so an
    * answer that never comes (a controller outside StateHealing) or one that leaves it set (a same-root refresh) cannot
    * freeze every fetch for the 15-minute watchdog.
    */
  private def escalationDoesNotFreeze(scheme: StorageScheme, answer: Rig => Unit): Unit =
    withRig(new Rig(scheme)) { rig =>
      def stagnations(): Int =
        var seen = 0
        var open = true
        while open do
          try
            rig.controller.receiveMessage(Duration.Zero) match
              case _: SNAPSyncController.HealingStagnated => seen += 1
              case _                                      => ()
          catch case _: AssertionError => open = false
        seen
      rig.coordinator ! TrieNodeHealingCoordinator.StartTrieNodeHealing(rig.trie.root.hash)
      rig.peerAvailable()
      rig.drive()
      (1 to 3).foreach(rig.serveBrX)
      rig.settle()
      withClue("the give-up is escalated: ")(stagnations() shouldBe 1)
      // No answer yet. The walk the completion check started finds brX again, and its fetch goes out.
      rig.drive()
      val fourth = rig.peer.manual.expectMessageType[SNAP.GetTrieNodes](5.seconds)
      withClue("dispatch goes on while the escalation waits: ")(fourth.paths shouldBe Seq(rig.trie("brX").pathset))
      rig.peer.answerFrom(fourth, rig.trie.served) // given up on at once: three failures already
      rig.settle()
      withClue("not escalated again before the controller answers: ")(stagnations() shouldBe 0)
      answer(rig)
      rig.drive()
      rig.serveBrX(5)
      rig.settle()
      withClue("escalated again once the controller has answered: ")(stagnations() shouldBe 1)
      rig.fault(on = false)
      rig.drive()
      rig.serveBrX(6)
      rig.drive()
      rig.awaitComplete()
    }

  it should "keep dispatching while a give-up's escalation waits, and escalate again after the hold policy's answer, under the Hash scheme (beacon N2-r3, hunter-r3 N1)" taggedAs UnitTest in {
    escalationDoesNotFreeze(StorageScheme.Hash, _.coordinator ! TrieNodeHealingCoordinator.HealingResumeDispatch)
  }

  it should "keep dispatching while a give-up's escalation waits, and escalate again after a same-root refresh, under the Path scheme (beacon N2-r3)" taggedAs UnitTest in {
    escalationDoesNotFreeze(
      StorageScheme.Path,
      rig => rig.coordinator ! TrieNodeHealingCoordinator.HealingPivotRefreshed(rig.trie.root.hash)
    )
  }

  // ---- spec 022 T038c ----------------------------------------------------------------------------------------------

  private def queueBrX(rig: Rig): Unit =
    rig.coordinator ! TrieNodeHealingCoordinator.QueueMissingNodes(
      Seq((rig.trie("brX").pathset, rig.trie("brX").hash))
    )

  /** The give-ups escalated so far. The probe is an actor of its own: a barrier through it, sent after them, shows it
    * has them all.
    */
  private def stagnated(rig: Rig): Int =
    val barrier = SNAPSyncController.ProgressNodesHealed(Long.MinValue)
    rig.controller.ref ! barrier
    var seen = 0
    var next = rig.controller.receiveMessage(30.seconds)
    while next != barrier do
      if next.isInstanceOf[SNAPSyncController.HealingStagnated] then seen += 1
      next = rig.controller.receiveMessage(30.seconds)
    seen

  /** forge N-r4-1: a walk running when a node is given up on may not cover it, so its clean end vouches for nothing. */
  private def giveUpDuringAWalk(scheme: StorageScheme): Unit =
    withRig(new Rig(scheme, hole = false)) { rig =>
      rig.fault(on = false)
      rig.peerAvailable()
      rig.coordinator ! TrieNodeHealingCoordinator.HealingCheckCompletion
      rig.settle()
      withClue("the walk is queued: ")(rig.ec.pending shouldBe 1)
      rig.fault(on = true)
      queueBrX(rig)
      (1 to 3).foreach(rig.serveBrX) // the third fill gives it up
      rig.settle()
      rig.fault(on = false)
      rig.ec.runNext() // the walk ends clean: brX is on disk in this fixture, so it found nothing
      rig.settle()
      withClue("its clean end does not count: ")(rig.completions() shouldBe 0)
      withClue("it walks again: ")(rig.ec.pending shouldBe 1)
      rig.drive()
      rig.awaitComplete()
    }

  it should "not count a walk during which a node was given up on, under the Hash scheme (forge N-r4-1)" taggedAs UnitTest in {
    giveUpDuringAWalk(StorageScheme.Hash)
  }

  it should "not count a walk during which a node was given up on, under the Path scheme (forge N-r4-1)" taggedAs UnitTest in {
    giveUpDuringAWalk(StorageScheme.Path)
  }

  it should "walk again after a clean pass followed by a give-up (hunter-r3 C1 (5))" taggedAs UnitTest in {
    withRig(new Rig(StorageScheme.Hash, hole = false)) { rig =>
      rig.fault(on = false)
      rig.peerAvailable()
      rig.coordinator ! TrieNodeHealingCoordinator.HealingCheckCompletion
      rig.drive()
      rig.awaitComplete()
      rig.fault(on = true)
      queueBrX(rig)
      (1 to 3).foreach(rig.serveBrX)
      rig.settle()
      withClue("the earlier clean pass vouches for nothing now: ")(rig.completions() shouldBe 0)
      withClue("the check walks: ")(rig.ec.pending should be > 0)
      rig.fault(on = false)
      rig.drive()
      rig.awaitComplete()
    }
  }

  it should "forget a read-failure count below the cap at a re-peg: the requests it was for have gone (hunter-r3 N2)" taggedAs UnitTest in {
    withRig(new Rig(StorageScheme.Hash)) { rig =>
      rig.coordinator ! TrieNodeHealingCoordinator.StartTrieNodeHealing(rig.trie.root.hash)
      rig.peerAvailable()
      rig.drive()
      (1 to 2).foreach(rig.serveBrX) // two failures: one short of the give-up
      // The put-back fetch of brX is out; the re-peg cancels it.
      rig.peer.manual.expectMessageType[SNAP.GetTrieNodes](5.seconds)
      rig.seen(TrieNodeHealingCoordinator.HealingPivotRefreshed(PathHealPresenceFixtures.accountTrieNew().root.hash))
      rig.peerAvailable() // the controller's 1 s tick
      rig.peer.manual.expectMessageType[SNAP.GetTrieNodes](5.seconds) // the new root's seed, unanswered
      queueBrX(rig)
      rig.serveBrX(3) // the third fill overall, the first since the re-peg
      rig.settle()
      withClue("not given up on: ")(stagnated(rig) shouldBe 0)
      withClue("fetched again: ") {
        rig.peer.manual.expectMessageType[SNAP.GetTrieNodes](5.seconds).paths shouldBe Seq(rig.trie("brX").pathset)
      }
    }
  }

  it should "keep a read-failure count at the cap across a re-peg: a child that never reads is not fetched in a loop (hunter-r3 N2)" taggedAs UnitTest in {
    withRig(new Rig(StorageScheme.Hash)) { rig =>
      rig.coordinator ! TrieNodeHealingCoordinator.StartTrieNodeHealing(rig.trie.root.hash)
      rig.peerAvailable()
      rig.drive()
      (1 to 3).foreach(rig.serveBrX)
      rig.settle()
      stagnated(rig) shouldBe 1
      rig.coordinator ! TrieNodeHealingCoordinator.HealingResumeDispatch // the controller's answer
      rig.seen(TrieNodeHealingCoordinator.HealingPivotRefreshed(PathHealPresenceFixtures.accountTrieNew().root.hash))
      rig.peerAvailable() // the controller's 1 s tick
      rig.peer.manual.expectMessageType[SNAP.GetTrieNodes](5.seconds) // the new root's seed, unanswered
      queueBrX(rig)
      rig.serveBrX(4)
      rig.settle()
      withClue("given up on at once: ")(stagnated(rig) shouldBe 1)
    }
  }

  it should "let a later give-up escalate again once the latch has waited as long as the pivot-refresh watchdog (hunter-r3 N1)" taggedAs UnitTest in {
    withRig(new Rig(StorageScheme.Hash)) { rig =>
      rig.coordinator ! TrieNodeHealingCoordinator.StartTrieNodeHealing(rig.trie.root.hash)
      rig.peerAvailable()
      rig.drive()
      (1 to 3).foreach(rig.serveBrX)
      rig.settle()
      stagnated(rig) shouldBe 1 // no answer ever comes
      (1 to TrieNodeHealingCoordinator.GiveUpLatchMaxPulses).foreach { _ =>
        rig.coordinator ! TrieNodeHealingCoordinator.HealingStagnationCheck
      }
      rig.drive() // the check's walk finds brX again
      rig.serveBrX(4) // given up on at once: three failures already
      rig.settle()
      withClue("escalated again: ")(stagnated(rig) shouldBe 1)
    }
  }
