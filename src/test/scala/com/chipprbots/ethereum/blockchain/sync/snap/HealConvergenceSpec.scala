package com.chipprbots.ethereum.blockchain.sync.snap

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.testkit.typed.scaladsl.FishingOutcomes
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.util.ByteString

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*
import scala.util.Using

import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController.*
import com.chipprbots.ethereum.blockchain.sync.snap.actors.HealingStatistics
import com.chipprbots.ethereum.blockchain.sync.snap.actors.HealingTrieFixtures
import com.chipprbots.ethereum.blockchain.sync.snap.actors.PathHealPresenceFixtures
import com.chipprbots.ethereum.blockchain.sync.snap.actors.PathHealPresenceFixtures.Placed
import com.chipprbots.ethereum.blockchain.sync.snap.actors.PathHealPresenceFixtures.Trie
import com.chipprbots.ethereum.blockchain.sync.snap.actors.PathHealPresenceFixtures.readAtPath
import com.chipprbots.ethereum.blockchain.sync.snap.actors.TrieNodeHealingCoordinator
import com.chipprbots.ethereum.blockchain.sync.snap.actors.healing.HealRequestFixtures
import com.chipprbots.ethereum.blockchain.sync.snap.actors.healing.HealRequestFixtures.ScriptedPeer
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.db.storage.InMemoryBfsQueueStorage
import com.chipprbots.ethereum.db.storage.PathNodeStorage
import com.chipprbots.ethereum.metrics.Metrics
import com.chipprbots.ethereum.network.p2p.messages.SNAP
import com.chipprbots.ethereum.testing.PeerTestHelpers
import com.chipprbots.ethereum.testing.Tags.UnitTest
import com.chipprbots.ethereum.testing.TestMptStorage

/** Spec 022 T046, T047 (FR-009 to FR-012, SC-005): healing converges on a root that moves, with no full-trie walk
  * before it completes, and what a re-peg costs is what changed.
  *
  * The chain is simulated: a two-level trie of 256 leaves, of which each block changes one, so each block's diff is the
  * three nodes on that leaf's path. The peer serves by position from the root a request names, as a SNAP peer does, and
  * the test re-pegs every N responses, as the controller does when a root goes stale.
  */
class HealConvergenceSpec extends ScalaTestWithActorTestKit() with AnyFlatSpecLike with Matchers:

  implicit private val classicSystem: org.apache.pekko.actor.ActorSystem = system.classicSystem
  implicit private val actorTestKit: ActorTestKit = testKit

  private val Schemes = Seq(StorageScheme.Hash, StorageScheme.Path)

  /** The state at block `block`: leaf `(i, j)` carries the last block that wrote it, and block `k` writes one. */
  private def chainAt(block: Int): Trie =
    def written(k: Int): (Int, Int) = (k % 16, (k * 7) % 16)
    val versions = (1 to block).map(k => written(k) -> k).toMap
    val leaves =
      for
        i <- 0 until 16
        j <- 0 until 16
      yield (i, j) -> HealRequestFixtures.plainLeaf(
        Array[Byte](0x0a),
        s"convergence/$i/$j/${versions.getOrElse((i, j), 0)}"
      )
    val branches =
      (0 until 16).map(i => i -> HealRequestFixtures.branch((0 until 16).map(j => j -> leaves(j + i * 16)._2)*))
    val root = HealRequestFixtures.branch(branches*)
    Trie(
      Seq(Placed("root", None, Array.empty[Byte], root)) ++
        branches.map { case (i, br) => Placed(s"br$i", None, Array(i.toByte), br) } ++
        leaves.map { case ((i, j), leaf) => Placed(s"leaf$i/$j", None, Array(i.toByte, j.toByte), leaf) }
    )

  final private class Sim(scheme: StorageScheme, startBlock: Int, stored: Set[String] => Set[String] = identity):
    private val clears = new AtomicInteger(0)
    private val queue: InMemoryBfsQueueStorage = new InMemoryBfsQueueStorage:
      override def clear(): Unit =
        clears.incrementAndGet()
        super.clear()
    var block: Int = startBlock
    def current: Trie = chainAt(block)
    private val first: Trie = current
    val hashStore = new TestMptStorage()
    val pns = new PathNodeStorage(EphemDataSource())
    private val keep = stored(first.nodes.map(_.label).toSet)
    private val seeded = first.nodes.filter(n => keep(n.label))
    if scheme == StorageScheme.Hash then PathHealPresenceFixtures.seedHash(hashStore, seeded)
    else PathHealPresenceFixtures.seedPath(pns, seeded)
    val appState = new AppStateStorage(EphemDataSource())
    appState.stampSnapHealInvariant().commit()
    val decided = new AtomicReference[ByteString]()
    val peer = new ScriptedPeer(testKit)
    val controller: TestProbe[Command] = createTestProbe[Command]()
    val coordinator: ActorRef[TrieNodeHealingCoordinator.Command] = HealingTrieFixtures.spawnCoordinator(
      stateRoot = first.root.hash,
      networkPeerManager = peer.ref,
      requestTracker = new SNAPRequestTracker()(classicSystem.scheduler),
      mptStorage = if scheme == StorageScheme.Hash then hashStore else new TestMptStorage(),
      batchSize = 16,
      snapSyncController = controller.ref,
      healingWriterEcOverride = Some(ExecutionContext.parasitic),
      bfsQueueStorageOpt = Some(queue),
      storageScheme = scheme,
      pathNodeStorageOpt = Option.when(scheme == StorageScheme.Path)(pns),
      movingRootDeltaHeal = true,
      decidedRoot = Some(decided),
      markerStore = Some(appState),
      markerMayStand = true
    )
    peer.coordinator.set(coordinator)
    private val peerRef = PeerTestHelpers.createTestPeer("convergence-peer", createTestProbe[Any]().ref.toClassic)

    def available(): Unit = coordinator ! TrieNodeHealingCoordinator.HealingPeerAvailable(peerRef)
    def walked: Boolean = clears.get() > 0

    /** The next block lands, and the controller re-pegs to its root. */
    def repeg(): Unit =
      block += 1
      coordinator ! TrieNodeHealingCoordinator.HealingPivotRefreshed(current.root.hash)
      available()

    def settle(): Unit =
      (1 to 4).foreach { _ =>
        val probe = createTestProbe[HealingStatistics]()
        coordinator ! TrieNodeHealingCoordinator.HealingGetProgress(probe.ref)
        probe.receiveMessage(30.seconds)
      }

    /** Whether the controller has been told the current root is complete. */
    def completedNow(): Boolean =
      settle()
      val barrier = ProgressNodesHealed(Long.MinValue)
      controller.ref ! barrier
      var complete = false
      var next = controller.receiveMessage(30.seconds)
      while next != barrier do
        if next == StateHealingComplete then complete = true
        next = controller.receiveMessage(30.seconds)
      complete && decided.get() == current.root.hash

    /** The next request for the current root, answered by position in that trie. What a re-peg left in flight is not
      * answered: the coordinator has forgotten it, so an answer would spend a response on nothing.
      */
    @annotation.tailrec
    def answerNext(): Seq[Seq[ByteString]] =
      val req = peer.manual.expectMessageType[SNAP.GetTrieNodes](30.seconds)
      if req.rootHash != current.root.hash then answerNext()
      else
        peer.answerFrom(req, current.served)
        req.paths

    def onDisk(n: Placed): Boolean =
      if scheme == StorageScheme.Hash then
        try
          hashStore.get(n.hash.toArray); true
        catch case _: Exception => false
      else readAtPath(pns, n).map(ByteString(_)).contains(n.encoded)

    /** What the audit would say: every node of the root is there. */
    def missing(t: Trie): Seq[String] = t.nodes.filterNot(onDisk).map(_.label)

  private def nodesPerRepeg: Long =
    Metrics.get().registry.find("snap.heal.nodes.per.repeg").summary().count()

  for scheme <- Schemes do
    "Healing under a root that moves" should s"converge with no full-trie walk, re-pegged every 4 responses, on $scheme" taggedAs UnitTest in {
      // Half the leaves are there from the download. No branch is, so everything above them has to come.
      val sim =
        new Sim(scheme, startBlock = 0, labels => labels.filter(l => l.startsWith("leaf") && l.hashCode % 2 == 0))
      sim.coordinator ! TrieNodeHealingCoordinator.StartTrieNodeHealing(sim.current.root.hash)
      sim.available()
      var responses = 0
      var repegs = 0
      while !sim.completedNow() do
        withClue("no more responses than a trie of 273 nodes could need, re-pegs and all: ")(responses should be < 400)
        sim.answerNext()
        responses += 1
        if responses % 4 == 0 then
          sim.repeg()
          repegs += 1
      withClue("the walk is what this test says is gone: ")(sim.walked shouldBe false)
      withClue("the root moved under it: ")(repegs should be > 0)
      withClue("complete: every node of the root it completed on is on disk: ") {
        sim.missing(sim.current) shouldBe empty
      }
      testKit.stop(sim.coordinator)
    }

    it should s"then pay for each re-peg with what changed, not with the state, on $scheme" taggedAs UnitTest in {
      val sim = new Sim(scheme, startBlock = 0)
      sim.coordinator ! TrieNodeHealingCoordinator.StartTrieNodeHealing(sim.current.root.hash)
      sim.completedNow() shouldBe true
      val before = nodesPerRepeg
      (1 to 8).foreach { block =>
        val previous = sim.current
        sim.repeg()
        val changed = sim.current.nodes.filter(n => previous.nodes.find(_.label == n.label).forall(_.hash != n.hash))
        withClue(s"block $block changes one leaf, its branch and the root: ")(changed.map(_.label).size shouldBe 3)
        var asked = Seq.empty[Seq[ByteString]]
        while !sim.completedNow() do asked ++= sim.answerNext()
        withClue(s"block $block fetches its diff and nothing else: ") {
          asked.toSet shouldBe changed.map(_.pathset).toSet
          asked.size shouldBe changed.size
        }
        withClue(s"block $block: ")(sim.missing(sim.current) shouldBe empty)
      }
      sim.walked shouldBe false
      withClue("each re-peg is recorded: ")(nodesPerRepeg - before shouldBe 8L)
      testKit.stop(sim.coordinator)
    }

  // ---- T047: what the controller does with the signal ---------------------------------------------------------------

  "HealingForceComplete" should "send StateHealingAbandoned on Hash too: the walk it relied on is gone (T047)" taggedAs UnitTest in {
    Schemes.foreach { scheme =>
      val trie = PathHealPresenceFixtures.accountTrieOld()
      val controller = createTestProbe[Command]()
      val coordinator = HealingTrieFixtures.spawnCoordinator(
        stateRoot = trie.root.hash,
        networkPeerManager = new ScriptedPeer(testKit).ref,
        requestTracker = new SNAPRequestTracker()(classicSystem.scheduler),
        mptStorage = new TestMptStorage(),
        batchSize = 16,
        snapSyncController = controller.ref,
        storageScheme = scheme,
        pathNodeStorageOpt = Option.when(scheme == StorageScheme.Path)(new PathNodeStorage(EphemDataSource()))
      )
      coordinator ! TrieNodeHealingCoordinator.HealingForceComplete
      withClue(s"$scheme: ") {
        controller.fishForMessage(30.seconds) {
          case StateHealingAbandoned => FishingOutcomes.complete
          case StateHealingComplete  => FishingOutcomes.fail("a force-complete has verified nothing")
          case _                     => FishingOutcomes.continueAndIgnore
        }
      }
    }
  }

/** T047 and the record `healingDecidedRoot`, on a real controller over a stub coordinator: what it does when the signal
  * arrives.
  */
class HealConvergenceControllerSpec
    extends ScalaTestWithActorTestKit(SnapControllerFixture.config)
    with AnyFlatSpecLike
    with Matchers:

  import SnapControllerFixture.*

  private val Schemes = Seq(StorageScheme.Hash, StorageScheme.Path)
  private def config(scheme: StorageScheme) = SNAPSyncConfig(deferredMerkleization = false, storageScheme = scheme)

  /** The controller in `StateHealing` on `Root`. */
  private def healing(f: SnapControllerFixture, scheme: StorageScheme) =
    val snap = f.enterStateHealing(config(scheme))
    f.awaitProcessed(snap)
    (snap, f.healingInbox)

  private def decide(f: SnapControllerFixture, root: ByteString): Unit =
    f.factories.lastHealingDecidedRoot.get().get.set(root)

  for scheme <- Schemes do
    "A completion signal" should s"route to completeHealingWalkClean with no controller walk, on $scheme (T047)" taggedAs UnitTest in {
      val f = new SnapControllerFixture(testKit)
      val (snap, _) = healing(f, scheme)
      decide(f, Root)
      Using.resource(new SnapLogCapture) { log =>
        snap ! StateHealingComplete
        log.awaitLine("healing complete (no controller walk)")
        f.awaitProcessed(snap)
        withClue("no trie walk starts, and no second coordinator: ") {
          log.contains("Starting trie walk") shouldBe false
          f.factories.healingSpawns.get() shouldBe 1
        }
      }
      f.appStateStorage.getSnapSyncStateRoot() shouldBe Some(Root)
    }

    it should s"be ignored for another root, with the coordinator asked again: a re-peg passed it, on $scheme" taggedAs UnitTest in {
      val f = new SnapControllerFixture(testKit)
      val (snap, inbox) = healing(f, scheme)
      decide(f, root(0x55))
      Using.resource(new SnapLogCapture) { log =>
        snap ! StateHealingComplete
        inbox.fishForMessage(30.seconds) {
          case TrieNodeHealingCoordinator.HealingCheckCompletion => FishingOutcomes.complete
          case _                                                 => FishingOutcomes.continueAndIgnore
        }
        f.awaitProcessed(snap)
        log.contains("healing complete") shouldBe false
      }
      withClue("and it takes one for the root it is on: ")(decide(f, Root))
      Using.resource(new SnapLogCapture) { log =>
        snap ! StateHealingComplete
        log.awaitLine("healing complete (no controller walk)")
      }
    }

    it should s"be ignored with no decision behind it at all, on $scheme" taggedAs UnitTest in {
      val f = new SnapControllerFixture(testKit)
      val (snap, inbox) = healing(f, scheme)
      Using.resource(new SnapLogCapture) { log =>
        snap ! StateHealingComplete
        inbox.fishForMessage(30.seconds) {
          case TrieNodeHealingCoordinator.HealingCheckCompletion => FishingOutcomes.complete
          case _                                                 => FishingOutcomes.continueAndIgnore
        }
        f.awaitProcessed(snap)
        log.contains("healing complete") shouldBe false
      }
    }

    it should s"be ignored outside StateHealing: nothing spawns and no phase timer restarts, on $scheme" taggedAs UnitTest in {
      val f = new SnapControllerFixture(testKit)
      val snap = f.enterStorageResume(config(scheme))
      f.factories.healingSpawns.get() shouldBe 0
      snap ! StateHealingComplete
      f.awaitProcessed(snap)
      f.factories.healingSpawns.get() shouldBe 0
    }
