package com.chipprbots.ethereum.blockchain.sync.snap.actors.healing

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.testkit.typed.scaladsl.FishingOutcomes
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.util.ByteString

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*

import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.snap.SNAPRequestTracker
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController
import com.chipprbots.ethereum.blockchain.sync.snap.StorageScheme
import com.chipprbots.ethereum.blockchain.sync.snap.actors.HealingTrieFixtures
import com.chipprbots.ethereum.blockchain.sync.snap.actors.PathHealPresenceFixtures
import com.chipprbots.ethereum.blockchain.sync.snap.actors.PathHealPresenceFixtures.Placed
import com.chipprbots.ethereum.blockchain.sync.snap.actors.PathHealPresenceFixtures.Trie
import com.chipprbots.ethereum.blockchain.sync.snap.actors.PathHealPresenceFixtures.readAtPath
import com.chipprbots.ethereum.blockchain.sync.snap.actors.TrieNodeHealingCoordinator
import com.chipprbots.ethereum.blockchain.sync.snap.heal.HealRequest
import com.chipprbots.ethereum.blockchain.sync.snap.heal.HeldNodeSet
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.PathNodeStorage
import com.chipprbots.ethereum.network.p2p.messages.SNAP
import com.chipprbots.ethereum.testing.PeerTestHelpers
import com.chipprbots.ethereum.testing.Tags.UnitTest
import com.chipprbots.ethereum.testing.TestMptStorage

/** Spec 022 slice 2 (FR-001 to FR-004, plan D1 and D3): a healed node reaches disk only after its whole subtree is
  * present.
  *
  * T017. The bug: a served node was written (Path) or buffered for the next flush (Hash) as soon as it arrived, and its
  * children were requested after. A re-peg, a force-complete or a crash in between left a node on disk whose subtree
  * was not, so "present" stopped meaning "complete" and only a full-trie walk could find the hole. Method: a real
  * coordinator heals `PathHealPresenceFixtures.accountTrieOld` against a peer the test answers level by level. While a
  * node waits on a child, it must not be on disk, before or after a re-peg, and the coordinator must not report
  * completion; once the new root's nodes arrive, every one of them is stored.
  *
  * T019. The held-memory bound (plan D1): at the cap no new subtree is opened, while the fetches below held nodes go
  * on, so a trie deeper than the cap allows still heals. Then the held-set model itself: commit only with the whole
  * subtree, one edge per reference, nothing lost on a failed write, a position filled again.
  */
class HeldNodeSetSpec extends ScalaTestWithActorTestKit() with AnyFlatSpecLike with Matchers:

  import HealRequestFixtures.ScriptedPeer

  implicit private val classicSystem: org.apache.pekko.actor.ActorSystem = system.classicSystem
  implicit private val actorTestKit: ActorTestKit = testKit

  /** Where a heal stores nodes, under one scheme: whether a node of a trie is on disk. */
  private trait Disk:
    def holds(n: Placed): Boolean

  private def pathDisk(pns: PathNodeStorage): Disk = (n: Placed) =>
    readAtPath(pns, n).map(ByteString(_)).contains(n.encoded)

  private def hashDisk(mpt: TestMptStorage): Disk = (n: Placed) => scala.util.Try(mpt.get(n.hash.toArray)).isSuccess

  private def withCoordinator[A](scheme: StorageScheme, root: ByteString, peer: ScriptedPeer)(
      body: (ActorRef[TrieNodeHealingCoordinator.Command], TestProbe[SNAPSyncController.Command], Disk) => A
  ): A =
    val pool = Executors.newSingleThreadExecutor()
    val controller = createTestProbe[SNAPSyncController.Command]()
    val mpt = new TestMptStorage()
    val pns = new PathNodeStorage(EphemDataSource())
    val coordinator = HealingTrieFixtures.spawnCoordinator(
      stateRoot = root,
      networkPeerManager = peer.ref,
      requestTracker = new SNAPRequestTracker()(classicSystem.scheduler),
      mptStorage = mpt,
      batchSize = 16,
      snapSyncController = controller.ref,
      healingWriterEcOverride = Some(ExecutionContext.fromExecutorService(pool)),
      storageScheme = scheme,
      pathNodeStorageOpt = Option.when(scheme == StorageScheme.Path)(pns),
      movingRootDeltaHeal = true
    )
    peer.coordinator.set(coordinator)
    val disk = if scheme == StorageScheme.Path then pathDisk(pns) else hashDisk(mpt)
    try body(coordinator, controller, disk)
    finally
      testKit.stop(coordinator)
      testKit.stop(peer.ref)
      pool.shutdown()
      pool.awaitTermination(5, TimeUnit.SECONDS)

  private def peerAvailable(coordinator: ActorRef[TrieNodeHealingCoordinator.Command]): Unit =
    coordinator ! TrieNodeHealingCoordinator.HealingPeerAvailable(
      PeerTestHelpers.createTestPeer("held-peer", createTestProbe[Any]().ref.toClassic)
    )

  /** Answer `req` with the nodes of `trie` at the paths it names whose label is in `labels`, omitting the rest. */
  private def answerOnly(peer: ScriptedPeer, req: SNAP.GetTrieNodes, trie: Trie, labels: Set[String]): Unit =
    val served = trie.nodes.filter(n => labels(n.label)).map(n => n.pathset -> n.encoded).toMap
    peer.answerFrom(req, served)

  private def heldAcrossRepeg(scheme: StorageScheme): Unit =
    val before = PathHealPresenceFixtures.accountTrieOld()
    val after = PathHealPresenceFixtures.accountTrieNew()
    val peer = new ScriptedPeer(testKit)
    withCoordinator(scheme, before.root.hash, peer) { (coordinator, controller, disk) =>
      coordinator ! TrieNodeHealingCoordinator.StartTrieNodeHealing(before.root.hash)
      peerAvailable(coordinator)

      // The root arrives: both its children are missing.
      peer.answerFrom(peer.manual.expectMessageType[SNAP.GetTrieNodes], before.served)
      // ext and brY arrive: brX (below ext), leafY0 and leafY1 (below brY) are missing.
      val level1 = peer.manual.expectMessageType[SNAP.GetTrieNodes]
      level1.paths.toSet shouldBe Set(before("ext").pathset, before("brY").pathset)
      peer.answerFrom(level1, before.served)
      // brY's leaves arrive, brX does not: the peer omits it, and it is requested again (and never answered).
      val level2 = peer.manual.expectMessageType[SNAP.GetTrieNodes]
      level2.paths.toSet shouldBe Set(before("brX").pathset, before("leafY0").pathset, before("leafY1").pathset)
      answerOnly(peer, level2, before, Set("leafY0", "leafY1"))
      peer.manual.expectMessageType[SNAP.GetTrieNodes].paths shouldBe Seq(before("brX").pathset)

      // root and ext wait on brX: neither may be on disk, and healing is not complete.
      withClue("root before the re-peg: ")(disk.holds(before("root")) shouldBe false)
      withClue("ext before the re-peg: ")(disk.holds(before("ext")) shouldBe false)
      coordinator ! TrieNodeHealingCoordinator.HealingCheckCompletion
      handled(coordinator)
      completionsWithin(controller, Duration.Zero) shouldBe empty

      // The pivot moves. What was held is dropped, never written.
      peer.serveFrom.set(Some(after.served))
      coordinator ! TrieNodeHealingCoordinator.HealingPivotRefreshed(after.root.hash)
      handled(coordinator) // the re-peg, its commit and its flush are done
      withClue("old root after the re-peg: ")(disk.holds(before("root")) shouldBe false)
      withClue("old ext after the re-peg: ")(disk.holds(before("ext")) shouldBe false)

      // The new root's nodes arrive: healing completes with all of them stored.
      peerAvailable(coordinator)
      controller.fishForMessage(30.seconds) {
        case SNAPSyncController.StateHealingComplete => FishingOutcomes.complete
        case _                                       => FishingOutcomes.continueAndIgnore
      }
      after.nodes.foreach(n => withClue(s"${n.label} after healing: ")(disk.holds(n) shouldBe true))
      // Hash: content addressed, nothing overwrote them, so the old root and ext were never written at all.
      if scheme == StorageScheme.Hash then
        withClue("old root at the end: ")(disk.holds(before("root")) shouldBe false)
        withClue("old ext at the end: ")(disk.holds(before("ext")) shouldBe false)
    }

  /** Mailbox barrier: every message sent to `coordinator` before this call has been handled once it returns. */
  private def handled(coordinator: ActorRef[TrieNodeHealingCoordinator.Command]): Unit =
    val probe = createTestProbe[com.chipprbots.ethereum.blockchain.sync.snap.actors.HealingStatistics]()
    coordinator ! TrieNodeHealingCoordinator.HealingGetProgress(probe.ref)
    probe.receiveMessage(30.seconds)
    ()

  /** The `StateHealingComplete`s `controller` receives within `d` (other messages, such as progress, are skipped). */
  private def completionsWithin(
      controller: TestProbe[SNAPSyncController.Command],
      d: FiniteDuration
  ): Seq[SNAPSyncController.Command] =
    val deadline = d.fromNow
    val seen = scala.collection.mutable.ArrayBuffer.empty[SNAPSyncController.Command]
    var open = true
    while open do
      val left = deadline.timeLeft
      if left <= Duration.Zero then open = false
      else
        try seen += controller.receiveMessage(left)
        catch case _: AssertionError => open = false
    seen.filter(_ == SNAPSyncController.StateHealingComplete).toSeq

  "Healing" should "keep a node off disk while it waits on a child, across a re-peg, under the Path scheme (T017)" taggedAs UnitTest in {
    heldAcrossRepeg(StorageScheme.Path)
  }

  it should "keep a node off disk while it waits on a child, across a re-peg, under the Hash scheme (T017)" taggedAs UnitTest in {
    heldAcrossRepeg(StorageScheme.Hash)
  }

  // ---- T019: the held-memory bound and the pause policy (plan D1) --------------------------------------------------

  /** A cap smaller than any node: as soon as anything is held, opening a new subtree is paused. A walk entry queued
    * then must wait, while every fetch below the held root goes on, deepest first, down a trie deeper than the cap
    * allows; once the last chain commits, nothing is held and the walk entry goes out.
    */
  private def pausedAtCap(scheme: StorageScheme): Unit =
    val trie = PathHealPresenceFixtures.accountTrieOld()
    val peer = new ScriptedPeer(testKit)
    // A node at depth 30, off the trie: what a walk or the controller would queue as a new subtree.
    val walkNode = HealRequestFixtures.plainLeaf(Array[Byte](1), "s022/t019/walk-entry")
    val walkEntry = Seq(
      ByteString(com.chipprbots.ethereum.mpt.HexPrefix.encode(Array.fill[Byte](30)(7), isLeaf = false))
    )
    val walkHash = ByteString(walkNode.hash)
    val pool = Executors.newSingleThreadExecutor()
    val controller = createTestProbe[SNAPSyncController.Command]()
    val mpt = new TestMptStorage()
    val pns = new PathNodeStorage(EphemDataSource())
    val coordinator = HealingTrieFixtures.spawnCoordinator(
      stateRoot = trie.root.hash,
      networkPeerManager = peer.ref,
      requestTracker = new SNAPRequestTracker()(classicSystem.scheduler),
      mptStorage = mpt,
      batchSize = 16,
      snapSyncController = controller.ref,
      healingWriterEcOverride = Some(ExecutionContext.fromExecutorService(pool)),
      storageScheme = scheme,
      pathNodeStorageOpt = Option.when(scheme == StorageScheme.Path)(pns),
      movingRootDeltaHeal = true,
      heldMemoryLimit = 1L
    )
    peer.coordinator.set(coordinator)
    val disk = if scheme == StorageScheme.Path then pathDisk(pns) else hashDisk(mpt)
    try
      coordinator ! TrieNodeHealingCoordinator.StartTrieNodeHealing(trie.root.hash)
      peerAvailable(coordinator)
      // The root arrives and is held: from here on the cap is reached.
      val rootReq = peer.manual.expectMessageType[SNAP.GetTrieNodes]
      rootReq.paths shouldBe Seq(trie.root.pathset)
      peer.answerFrom(rootReq, trie.served)
      val level1 = peer.manual.expectMessageType[SNAP.GetTrieNodes]
      // A new subtree (a walk entry, no parent) is queued while paused.
      coordinator ! TrieNodeHealingCoordinator.QueueMissingNodes(Seq(walkEntry -> walkHash))

      val belowRoot = scala.collection.mutable.ArrayBuffer(level1)
      var next = level1
      // Answer everything below the root, level by level, until the walk entry goes out.
      while !next.paths.contains(walkEntry) do
        peer.answerFrom(next, trie.served)
        next = peer.manual.expectMessageType[SNAP.GetTrieNodes]
        if !next.paths.contains(walkEntry) then belowRoot += next
      withClue("the walk entry went out only once nothing was held: ") {
        next.paths shouldBe Seq(walkEntry)
      }
      peer.answer(next, Seq(ByteString(walkNode.encode)))
      controller.fishForMessage(30.seconds) {
        case SNAPSyncController.StateHealingComplete => FishingOutcomes.complete
        case _                                       => FishingOutcomes.continueAndIgnore
      }
      // Every fetch that went out while paused was below the held root, deepest first within each request.
      belowRoot.flatMap(_.paths).toSet shouldBe trie.nodes.filterNot(_.label == "root").map(_.pathset).toSet
      belowRoot.foreach { r =>
        val depths = r.paths.map(HealRequest.depthOf)
        withClue(s"request ${r.requestId}: ")(depths shouldBe depths.sorted(using Ordering[Int].reverse))
      }
      // No deadlock: the whole trie is on disk although the cap is smaller than one node.
      trie.nodes.foreach(n => withClue(s"${n.label}: ")(disk.holds(n) shouldBe true))
    finally
      testKit.stop(coordinator)
      testKit.stop(peer.ref)
      pool.shutdown()
      pool.awaitTermination(5, TimeUnit.SECONDS)

  it should "at the held-memory cap fetch only the children of held nodes, and not deadlock on a deep trie, under the Path scheme (T019)" taggedAs UnitTest in {
    pausedAtCap(StorageScheme.Path)
  }

  it should "at the held-memory cap fetch only the children of held nodes, and not deadlock on a deep trie, under the Hash scheme (T019)" taggedAs UnitTest in {
    pausedAtCap(StorageScheme.Hash)
  }

  // ---- HeldNodeSet itself (T023, T024; forge N5 and N1, beacon N7) --------------------------------------------------

  private def h(seed: String): ByteString = com.chipprbots.ethereum.crypto.kec256(ByteString(seed))
  private def node(seed: String): ByteString = ByteString(s"node-bytes-$seed")
  private def hashReq(seed: String, depth: Int = 1): HealRequest =
    HealRequest.ofNode(HealRequestFixtures.accountPathset(Seq.fill(depth)(1)*), h(seed))
  private def labels(plan: HeldNodeSet.CommitPlan): Seq[ByteString] = plan.nodes.map(_.request.hash)

  "HeldNodeSet" should "commit a node only with its whole subtree: a parent waits on a held child until the child's subtree commits (forge N5)" taggedAs UnitTest in {
    val held = new HeldNodeSet()
    val (p, c, g) = (hashReq("p", 1), hashReq("c", 2), hashReq("g", 3))
    held.hold(p, node("p"), outstanding = 1, parents = Nil)
    held.hold(c, node("c"), outstanding = 1, parents = Seq(HeldNodeSet.idOf(p))) // C arrived; G still missing
    held.planCommit().isEmpty shouldBe true // C's arrival does not release P
    held.hold(g, node("g"), outstanding = 0, parents = Seq(HeldNodeSet.idOf(c)))
    val plan = held.planCommit()
    labels(plan) shouldBe Seq(g.hash, c.hash, p.hash) // one write, children first
    held.size shouldBe 3 // nothing released before the write returns
    held.applyCommit(plan)
    held.isEmpty shouldBe true
    held.bytes shouldBe 0L
  }

  it should "release a parent of a shared child only when its own other children are committed too" taggedAs UnitTest in {
    val held = new HeldNodeSet()
    val (p1, p2, c, l1) = (hashReq("p1"), hashReq("p2"), hashReq("shared", 2), hashReq("l1", 2))
    held.hold(p1, node("p1"), outstanding = 2, parents = Nil) // C and L1
    held.hold(p2, node("p2"), outstanding = 1, parents = Nil) // C
    held.hold(c, node("c"), outstanding = 0, parents = Seq(p1.hash, p2.hash))
    val first = held.planCommit()
    labels(first) shouldBe Seq(c.hash, p2.hash)
    held.applyCommit(first)
    held.get(p1.hash).map(_.outstanding) shouldBe Some(1)
    held.hold(l1, node("l1"), outstanding = 0, parents = Seq(p1.hash))
    val second = held.planCommit()
    labels(second) shouldBe Seq(l1.hash, p1.hash)
    held.applyCommit(second)
    held.isEmpty shouldBe true
  }

  it should "close one edge per reference: a child referenced twice releases its parent once both are closed" taggedAs UnitTest in {
    val held = new HeldNodeSet()
    val (p, c) = (hashReq("p"), hashReq("twice", 2))
    held.hold(p, node("p"), outstanding = 2, parents = Nil)
    held.hold(c, node("c"), outstanding = 0, parents = Seq(p.hash, p.hash))
    labels(held.planCommit()) shouldBe Seq(c.hash, p.hash)
  }

  it should "count a parent's edges to a held child per parent when it attaches (forge N1)" taggedAs UnitTest in {
    val held = new HeldNodeSet()
    val (q, c) = (hashReq("q"), hashReq("c", 2))
    held.hold(c, node("c"), outstanding = 1, parents = Seq(h("p")))
    held.attachParent(c.hash, q.hash, multiplicity = 2) shouldBe 2
    held.attachParent(c.hash, q.hash, multiplicity = 2) shouldBe 0 // processed again: no second set
    held.attachParent(c.hash, h("p"), multiplicity = 1) shouldBe 0 // p's own edge is not q's
    held.get(c.hash).map(_.parents.count(_ == q.hash)) shouldBe Some(2)
  }

  it should "leave everything as it was when the commit write throws, and commit it on the next try (#1549)" taggedAs UnitTest in {
    val held = new HeldNodeSet()
    val (p, c) = (hashReq("p"), hashReq("c", 2))
    held.hold(p, node("p"), outstanding = 1, parents = Nil)
    held.hold(c, node("c"), outstanding = 0, parents = Seq(p.hash))
    held.addDeletes(Seq((None, Array[Byte](1, 2))))
    val bytesBefore = held.bytes
    val attempt = held.planCommit()
    // The write throws: applyCommit is never called.
    held.size shouldBe 2
    held.bytes shouldBe bytesBefore
    held.readyCount shouldBe 1
    val retry = held.planCommit()
    labels(retry) shouldBe labels(attempt)
    retry.deletes.map(_._2.toSeq) shouldBe Seq(Seq[Byte](1, 2))
    held.applyCommit(retry)
    held.isEmpty shouldBe true
    held.hasPendingWrites shouldBe false
    held.bytes shouldBe 0L
  }

  it should "under the Path scheme drop a node held at a position that is filled with another node, and let no edge to the old node close the new one (beacon N7)" taggedAs UnitTest in {
    val held = new HeldNodeSet()
    val pathset = HealRequestFixtures.accountPathset(4, 2)
    val (oldNode, newNode) = (HealRequest.atPosition(pathset, h("old")), HealRequest.atPosition(pathset, h("new")))
    HeldNodeSet.idOf(oldNode) should not be HeldNodeSet.idOf(newNode)
    held.hold(oldNode, node("old"), outstanding = 1, parents = Nil)
    // A child of the old node, held, with its edge to the old node.
    val child = HealRequest.atPosition(HealRequestFixtures.accountPathset(4, 2, 0), h("old-child"))
    held.hold(child, node("old-child"), outstanding = 0, parents = Seq(HeldNodeSet.idOf(oldNode)))
    held.hold(newNode, node("new"), outstanding = 1, parents = Nil) // the position is filled again
    held.heldAt(newNode.key).map(_.request.hash) shouldBe Some(newNode.hash)
    held.contains(HeldNodeSet.idOf(oldNode)) shouldBe false
    // The old node's child commits; it closes nothing on the new node.
    val plan = held.planCommit()
    labels(plan) shouldBe Seq(child.hash)
    held.applyCommit(plan)
    held.get(HeldNodeSet.idOf(newNode)).map(_.outstanding) shouldBe Some(1)
  }

  it should "on a re-peg keep only what is ready, and count its memory back to zero" taggedAs UnitTest in {
    val held = new HeldNodeSet()
    val (p, c) = (hashReq("p"), hashReq("c", 2))
    held.hold(p, node("p"), outstanding = 2, parents = Nil)
    held.hold(c, node("c"), outstanding = 0, parents = Seq(p.hash))
    held.bytes should be > 0L
    held.dropIncomplete() shouldBe 1
    held.contains(p.hash) shouldBe false
    held.contains(c.hash) shouldBe true
    held.clear()
    held.bytes shouldBe 0L
  }

  // ---- spec 022 slice 2 review: orphans, the pause, promote, the frontier mirror, force-complete --------------------

  /** A coordinator over fresh stores, with the settings these cases vary; `body` gets it, the controller and the disk.
    */
  private def withHealer[A](
      scheme: StorageScheme,
      root: ByteString,
      peer: ScriptedPeer,
      heldMemoryLimit: Long = TrieNodeHealingCoordinator.DefaultHeldMemoryLimit,
      concurrency: Int = 16,
      batchSize: Int = 16,
      writerEc: Option[ExecutionContext] = None,
      frontier: Option[com.chipprbots.ethereum.db.storage.HealingFrontierStorage] = None,
      pathSource: com.chipprbots.ethereum.db.dataSource.DataSource = EphemDataSource()
  )(
      body: (ActorRef[TrieNodeHealingCoordinator.Command], TestProbe[SNAPSyncController.Command], Disk) => A
  ): A =
    val pool = Executors.newSingleThreadExecutor()
    val controller = createTestProbe[SNAPSyncController.Command]()
    val mpt = new TestMptStorage()
    val pns = new PathNodeStorage(pathSource)
    val coordinator = HealingTrieFixtures.spawnCoordinator(
      stateRoot = root,
      networkPeerManager = peer.ref,
      requestTracker = new SNAPRequestTracker()(classicSystem.scheduler),
      mptStorage = mpt,
      batchSize = batchSize,
      concurrency = concurrency,
      snapSyncController = controller.ref,
      healingFrontierStorage = frontier,
      frontierPersistenceEnabled = frontier.isDefined,
      healingWriterEcOverride = Some(writerEc.getOrElse(ExecutionContext.fromExecutorService(pool))),
      storageScheme = scheme,
      pathNodeStorageOpt = Option.when(scheme == StorageScheme.Path)(pns),
      movingRootDeltaHeal = true,
      heldMemoryLimit = heldMemoryLimit
    )
    peer.coordinator.set(coordinator)
    val disk = if scheme == StorageScheme.Path then pathDisk(pns) else hashDisk(mpt)
    try body(coordinator, controller, disk)
    finally
      testKit.stop(coordinator)
      testKit.stop(peer.ref)
      pool.shutdown()
      pool.awaitTermination(5, TimeUnit.SECONDS)

  // Bytes that are no trie node (one byte below 0x80 repeated: an RLP single byte, then junk). A parent may still
  // reference them by hash: a peer serving them passes the keccak check and fails to decode (vault note 5).
  private val undecodable: ByteString = ByteString(Array.fill[Byte](40)(0x05))

  /** root (branch) [] -> [0] the undecodable node, [1] a leaf. */
  private def orphaningTrie(): Trie =
    val leaf = HealRequestFixtures.plainLeaf(Array[Byte](0x0c), "s022/review/orphan-leaf")
    val slots = Array.fill[com.chipprbots.ethereum.mpt.MptNode](16)(com.chipprbots.ethereum.mpt.NullNode)
    slots(0) = com.chipprbots.ethereum.mpt.HashNode(com.chipprbots.ethereum.crypto.kec256(undecodable).toArray)
    slots(1) = com.chipprbots.ethereum.mpt.HashNode(leaf.hash)
    val root = com.chipprbots.ethereum.mpt.BranchNode(slots, None)
    Trie(
      Seq(
        Placed("root", None, Array.empty[Byte], root),
        Placed("leaf", None, Array[Byte](1), leaf)
      )
    )

  private def walkEntry(seed: String): (Seq[ByteString], ByteString, ByteString) =
    val node = HealRequestFixtures.plainLeaf(Array[Byte](1), seed)
    val pathset = Seq(ByteString(com.chipprbots.ethereum.mpt.HexPrefix.encode(Array.fill[Byte](30)(7), isLeaf = false)))
    (pathset, ByteString(node.hash), ByteString(node.encode))

  /** beacon W1, hunter W2: a held node whose child can never commit (it does not decode) must not stop new subtrees
    * from being opened at the held-memory cap. Before the fix the walk entry waited forever: the pause held it, and the
    * orphan guard needed an empty queue.
    */
  private def orphanAtCap(scheme: StorageScheme): Unit =
    val trie = orphaningTrie()
    val peer = new ScriptedPeer(testKit)
    val (walkPath, walkHash, _) = walkEntry("s022/review/orphan-walk-entry")
    withHealer(scheme, trie.root.hash, peer, heldMemoryLimit = 1L) { (coordinator, _, disk) =>
      coordinator ! TrieNodeHealingCoordinator.StartTrieNodeHealing(trie.root.hash)
      peerAvailable(coordinator)
      peer.answerFrom(peer.manual.expectMessageType[SNAP.GetTrieNodes], trie.served)
      // The root is held, its two children in flight: the cap is reached. A new subtree is queued now.
      val level1 = peer.manual.expectMessageType[SNAP.GetTrieNodes]
      coordinator ! TrieNodeHealingCoordinator.QueueMissingNodes(Seq(walkPath -> walkHash))
      handled(coordinator)
      // The leaf commits; the other child does not decode, so the root can never commit.
      val undecodablePath =
        Seq(ByteString(com.chipprbots.ethereum.mpt.HexPrefix.encode(Array[Byte](0), isLeaf = false)))
      peer.answerFrom(level1, trie.served + (undecodablePath -> undecodable))
      withClue("the new subtree is opened although a held node waits on a child that never commits: ") {
        peer.manual.expectMessageType[SNAP.GetTrieNodes](10.seconds).paths shouldBe Seq(walkPath)
      }
      withClue("the root is never written: ")(disk.holds(trie.root) shouldBe false)
    }

  it should "open new subtrees at the held-memory cap when a held node waits on a child that never commits, under the Path scheme (beacon W1, hunter W2)" taggedAs UnitTest in {
    orphanAtCap(StorageScheme.Path)
  }

  it should "open new subtrees at the held-memory cap when a held node waits on a child that never commits, under the Hash scheme (beacon W1, hunter W2)" taggedAs UnitTest in {
    orphanAtCap(StorageScheme.Hash)
  }

  it should "fetch a queued walk entry first once a held parent finds it is its child, at the cap (promote, eye F5)" taggedAs UnitTest in {
    val x = HealRequestFixtures.plainLeaf(Array[Byte](0x0a), "s022/review/promote-x")
    val y = HealRequestFixtures.plainLeaf(Array[Byte](0x0b), "s022/review/promote-y")
    val root = HealRequestFixtures.branch(0 -> x, 1 -> y)
    val trie = Trie(
      Seq(
        Placed("root", None, Array.empty[Byte], root),
        Placed("x", None, Array[Byte](0), x),
        Placed("y", None, Array[Byte](1), y)
      )
    )
    val peer = new ScriptedPeer(testKit)
    // One request at a time, one path per request: the dispatch order is the order requests go out.
    withHealer(StorageScheme.Hash, trie.root.hash, peer, heldMemoryLimit = 1L, concurrency = 1, batchSize = 1) {
      (coordinator, controller, disk) =>
        coordinator ! TrieNodeHealingCoordinator.StartTrieNodeHealing(trie.root.hash)
        peerAvailable(coordinator)
        val rootReq = peer.manual.expectMessageType[SNAP.GetTrieNodes]
        // While the root is in flight, a walk queues x: a new subtree, as far as it knows.
        coordinator ! TrieNodeHealingCoordinator.QueueMissingNodes(Seq(trie("x").pathset -> trie("x").hash))
        handled(coordinator)
        // The root arrives and is held (the cap is reached); x and y are its missing children. x was queued first, so
        // once x frees held memory it goes before y; left as a new subtree it would be held back behind y.
        peer.answerFrom(rootReq, trie.served)
        val first = peer.manual.expectMessageType[SNAP.GetTrieNodes]
        first.paths shouldBe Seq(trie("x").pathset)
        peer.answerFrom(first, trie.served)
        val second = peer.manual.expectMessageType[SNAP.GetTrieNodes]
        second.paths shouldBe Seq(trie("y").pathset)
        peer.answerFrom(second, trie.served)
        controller.fishForMessage(30.seconds) {
          case SNAPSyncController.StateHealingComplete => FishingOutcomes.complete
          case _                                       => FishingOutcomes.continueAndIgnore
        }
        trie.nodes.foreach(n => withClue(s"${n.label}: ")(disk.holds(n) shouldBe true))
    }
  }

  it should "drop the persisted frontier entries of the held nodes a re-peg drops, under the Hash scheme (vault V5)" taggedAs UnitTest in {
    val before = PathHealPresenceFixtures.accountTrieOld()
    val after = PathHealPresenceFixtures.accountTrieNew()
    val peer = new ScriptedPeer(testKit)
    val frontier = new com.chipprbots.ethereum.db.storage.HealingFrontierStorage(EphemDataSource())
    withHealer(StorageScheme.Hash, before.root.hash, peer, frontier = Some(frontier)) { (coordinator, _, _) =>
      coordinator ! TrieNodeHealingCoordinator.StartTrieNodeHealing(before.root.hash)
      peerAvailable(coordinator)
      peer.answerFrom(peer.manual.expectMessageType[SNAP.GetTrieNodes], before.served)
      peer.answerFrom(peer.manual.expectMessageType[SNAP.GetTrieNodes], before.served)
      answerOnly(peer, peer.manual.expectMessageType[SNAP.GetTrieNodes], before, Set("leafY0", "leafY1"))
      peer.manual.expectMessageType[SNAP.GetTrieNodes].paths shouldBe Seq(before("brX").pathset)
      handled(coordinator)
      withClue("held, so still a frontier entry: ")(frontier.get(before.root.hash).isDefined shouldBe true)
      coordinator ! TrieNodeHealingCoordinator.HealingPivotRefreshed(after.root.hash)
      handled(coordinator)
      withClue("the old root, dropped by the re-peg: ")(frontier.get(before.root.hash) shouldBe None)
      withClue("the old ext, dropped by the re-peg: ")(frontier.get(before("ext").hash) shouldBe None)
      withClue("brX, in flight at the re-peg: ")(frontier.get(before("brX").hash) shouldBe None)
    }
  }

  it should "on force-complete write what was committed and not flushed, and never the held nodes, under the Hash scheme (T025, eye F7)" taggedAs UnitTest in {
    val trie = PathHealPresenceFixtures.accountTrieOld()
    val peer = new ScriptedPeer(testKit)
    // The asynchronous flushes never run: what is committed stays in the flush buffer until force-complete.
    val never = new ExecutionContext:
      def execute(runnable: Runnable): Unit = ()
      def reportFailure(cause: Throwable): Unit = ()
    withHealer(StorageScheme.Hash, trie.root.hash, peer, writerEc = Some(never)) { (coordinator, controller, disk) =>
      coordinator ! TrieNodeHealingCoordinator.StartTrieNodeHealing(trie.root.hash)
      peerAvailable(coordinator)
      peer.answerFrom(peer.manual.expectMessageType[SNAP.GetTrieNodes], trie.served)
      peer.answerFrom(peer.manual.expectMessageType[SNAP.GetTrieNodes], trie.served)
      // brY's leaves arrive and brY's subtree is committed (buffered); brX never arrives, so root and ext stay held.
      answerOnly(peer, peer.manual.expectMessageType[SNAP.GetTrieNodes], trie, Set("leafY0", "leafY1"))
      peer.manual.expectMessageType[SNAP.GetTrieNodes].paths shouldBe Seq(trie("brX").pathset)
      Seq("leafY0", "leafY1", "brY").foreach(l => withClue(s"$l, buffered: ")(disk.holds(trie(l)) shouldBe false))
      coordinator ! TrieNodeHealingCoordinator.HealingForceComplete
      controller.fishForMessage(30.seconds) {
        case SNAPSyncController.StateHealingComplete => FishingOutcomes.complete
        case _                                       => FishingOutcomes.continueAndIgnore
      }
      Seq("leafY0", "leafY1", "brY").foreach(l => withClue(s"$l, written: ")(disk.holds(trie(l)) shouldBe true))
      Seq("root", "ext").foreach(l => withClue(s"$l, held: ")(disk.holds(trie(l)) shouldBe false))
    }
  }

  it should "on a re-peg whose commit write throws, drop the ready nodes unwritten and heal the new root, under the Path scheme (eye F7)" taggedAs UnitTest in {
    val before = PathHealPresenceFixtures.accountTrieOld()
    val after = PathHealPresenceFixtures.accountTrieNew()
    val peer = new ScriptedPeer(testKit)
    // Every batch that writes brY's position fails until the test lets it through.
    val failing = new java.util.concurrent.atomic.AtomicBoolean(true)
    val brYKey = HealRequestFixtures.accountKey(Array[Byte](1))
    val source = new EphemDataSource(Map.empty):
      override def update(updates: Seq[com.chipprbots.ethereum.db.dataSource.DataUpdate]): Unit =
        val writesBrY = updates
          .flatMap(HealRequestFixtures.Op.of)
          .exists(op => !op.delete && op.namespace == HealRequestFixtures.AccountNs && op.key == brYKey)
        if failing.get() && writesBrY then throw new RuntimeException("injected write batch failure")
        super.update(updates)
    withHealer(StorageScheme.Path, before.root.hash, peer, pathSource = source) { (coordinator, controller, disk) =>
      coordinator ! TrieNodeHealingCoordinator.StartTrieNodeHealing(before.root.hash)
      peerAvailable(coordinator)
      peer.answerFrom(peer.manual.expectMessageType[SNAP.GetTrieNodes], before.served)
      peer.answerFrom(peer.manual.expectMessageType[SNAP.GetTrieNodes], before.served)
      answerOnly(peer, peer.manual.expectMessageType[SNAP.GetTrieNodes], before, Set("leafY0", "leafY1"))
      peer.manual.expectMessageType[SNAP.GetTrieNodes].paths shouldBe Seq(before("brX").pathset)
      handled(coordinator)
      // brY's subtree is ready but its write keeps failing: nothing of it is on disk.
      Seq("leafY0", "leafY1", "brY").foreach(l =>
        withClue(s"$l before the re-peg: ")(disk.holds(before(l)) shouldBe false)
      )
      coordinator ! TrieNodeHealingCoordinator.HealingPivotRefreshed(after.root.hash)
      handled(coordinator)
      Seq("leafY0", "leafY1", "brY", "root", "ext").foreach { l =>
        withClue(s"$l after the re-peg: ")(disk.holds(before(l)) shouldBe false)
      }
      // The writes work again: the new root heals completely, brY's subtree fetched again.
      failing.set(false)
      peer.serveFrom.set(Some(after.served))
      peerAvailable(coordinator)
      controller.fishForMessage(30.seconds) {
        case SNAPSyncController.StateHealingComplete => FishingOutcomes.complete
        case _                                       => FishingOutcomes.continueAndIgnore
      }
      after.nodes.foreach(n => withClue(s"${n.label} after healing: ")(disk.holds(n) shouldBe true))
    }
  }
