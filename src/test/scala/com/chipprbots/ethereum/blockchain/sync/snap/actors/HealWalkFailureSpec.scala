package com.chipprbots.ethereum.blockchain.sync.snap.actors

import java.util.concurrent.atomic.AtomicLong

import org.apache.pekko.actor.testkit.typed.scaladsl.BehaviorTestKit
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.testkit.typed.scaladsl.TestInbox
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.util.ByteString

import scala.concurrent.ExecutionContext

import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.snap.SNAPRequestTracker
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController
import com.chipprbots.ethereum.blockchain.sync.snap.StorageScheme
import com.chipprbots.ethereum.blockchain.sync.snap.actors.TrieNodeHealingCoordinator.*
import com.chipprbots.ethereum.blockchain.sync.snap.controller.StateIngest
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.BadEntryDelete
import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.db.storage.NodeStorage
import com.chipprbots.ethereum.db.storage.ReferenceCountedStateStorage
import com.chipprbots.ethereum.db.storage.StoredNodeTestAccess
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.domain.UInt256
import com.chipprbots.ethereum.mpt.BranchNode
import com.chipprbots.ethereum.mpt.HashNode
import com.chipprbots.ethereum.mpt.HexPrefix
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
import com.chipprbots.ethereum.mpt.byteStringSerializer
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.testing.TestMptStorage

/** Basic pruning's state storage, watching its `deleteBadNode`: whether the caller held `lock` (the heal store's
  * monitor, review vault M2), and a delete that throws on demand (review vault M1).
  */
final private class WatchedState(nodes: NodeStorage) extends ReferenceCountedStateStorage(nodes, 64, 0L):
  @volatile var lock: AnyRef = null
  @volatile var heldLockAtDelete: Option[Boolean] = None
  @volatile var throwOnDelete: Boolean = false
  override def deleteBadNode(nodeHash: ByteString, stillReached: () => Boolean): BadEntryDelete =
    heldLockAtDelete = Some(lock != null && Thread.holdsLock(lock))
    if throwOnDelete then throw new RuntimeException("s1: injected delete failure")
    super.deleteBadNode(nodeHash, stillReached)

/** S-1: a frontier walk that cannot finish no longer holds healing up for good, and a corrupt entry a walk finds is
  * deleted and healed.
  *
  * Before: a walk that threw ended in `FrontierWalkFailed`, which nothing counted, and the dead-pulse watchdog and
  * every re-peg started it again. Now, on the Hash scheme, `MaxConsecutiveWalkFailures` walks in a row that throw,
  * counted across re-pegs and started over only by a walk that reaches its end, give up loudly with
  * `StateHealingAbandoned` (the lazy hand-off), once the first of them is `WalkFailureMinSpan` old. A cancelled walk, a
  * replaced incarnation's walk and any Path walk are not counted.
  *
  * A corrupt entry (`FrontierCorruptFound`) is deleted under the heal store's monitor through the store's own
  * `deleteBadNode` before it is queued, since basic pruning's upsert would keep it. One the store keeps for good is
  * remembered, and a later walk counts it as a skip; one whose delete threw is tried again by the next walk.
  */
class HealWalkFailureSpec extends ScalaTestWithActorTestKit() with AnyFlatSpecLike with Matchers:

  private val Root = kec256(ByteString("s1-heal-walk-root"))
  private val OtherRoot = kec256(ByteString("s1-heal-walk-other-root"))
  private val EmptyPath = ByteString(HexPrefix.encode(Array.empty[Byte], isLeaf = false))
  private val boom = new IllegalStateException("s1: the walk threw")
  private val minute = 60L * 1000L

  // Drops every task: the walk and flush Futures some arms launch never run.
  private val neverRuns: ExecutionContext = new ExecutionContext:
    def execute(runnable: Runnable): Unit = ()
    def reportFailure(cause: Throwable): Unit = ()

  final private case class Harness(
      impl: TrieNodeHealingCoordinatorImpl,
      kit: BehaviorTestKit[Command],
      controller: TestInbox[SNAPSyncController.Command],
      clock: AtomicLong
  )

  private def harness(
      root: ByteString = Root,
      mpt: MptStorage = new TestMptStorage(),
      ingest: StateIngest = HealingTrieFixtures.healingIngest(),
      ec: ExecutionContext = neverRuns,
      scheme: StorageScheme = StorageScheme.Hash
  ): Harness =
    val controller = TestInbox[SNAPSyncController.Command]()
    val clock = new AtomicLong(0L)
    var captured: TrieNodeHealingCoordinatorImpl = null
    val behavior = Behaviors.setup[Command] { ctx =>
      Behaviors.withTimers { timers =>
        captured = new TrieNodeHealingCoordinatorImpl(
          ctx,
          timers,
          initialStateRoot = root,
          networkPeerManager = TestInbox[NetworkPeerManagerActor.Command]().ref,
          requestTracker = new SNAPRequestTracker()(using system.classicSystem.scheduler),
          mptStorage = mpt,
          batchSize = 16,
          snapSyncController = controller.ref,
          concurrency = 4,
          stateIngest = ingest,
          healingWriterEcOverride = Some(ec),
          healingReaderEcOverride = Some(ec),
          storageScheme = scheme,
          nowMillis = () => clock.get()
        )
        captured.start()
      }
    }
    val kit = BehaviorTestKit(behavior) // runs the setup, which builds `captured`
    Harness(captured, kit, controller, clock)

  /** Walk `walk` of this incarnation, as the launcher sets it up, ends with `cause`, `atMinute` minutes in. */
  private def walkFails(h: Harness, walk: Long, atMinute: Long, cause: Option[Throwable] = Some(boom)): Unit =
    h.clock.set(atMinute * minute)
    h.impl.currentWalk = Some(walk)
    h.impl.verificationBFSRunning = true
    h.kit.run(FrontierWalkFailed(walk, cause))

  private def handOffs(h: Harness): Int =
    h.controller.receiveAll().count(_ == SNAPSyncController.StateHealingAbandoned)

  "The healing coordinator" should "hand off lazily once 3 walks in a row fail over an hour, re-pegs between them included (S-1)" taggedAs UnitTest in {
    val h = harness()
    walkFails(h, 1L, atMinute = 0)
    h.kit.run(HealingPivotRefreshed(OtherRoot))
    walkFails(h, 2L, atMinute = 30)
    h.kit.run(HealingPivotRefreshed(Root))
    walkFails(h, 3L, atMinute = 59)
    withClue("three in a row, but not an hour of them (review beacon W2): ")(handOffs(h) shouldBe 0)
    walkFails(h, 4L, atMinute = 60)

    handOffs(h) shouldBe 1
    h.impl.verificationBFSRunning shouldBe false
    withClue("the count starts over after a hand-off: ") {
      walkFails(h, 5L, atMinute = 200)
      walkFails(h, 6L, atMinute = 300)
      handOffs(h) shouldBe 0
    }
  }

  it should "not give up within the hour, however many walks fail in it (S-1, review beacon W2)" taggedAs UnitTest in {
    val h = harness()
    (0L until 12L).foreach(i => walkFails(h, 1L + i, atMinute = i * 5))
    handOffs(h) shouldBe 0
    walkFails(h, 13L, atMinute = 60)
    handOffs(h) shouldBe 1
  }

  it should "start the count over when a walk reaches its end, dirty or clean, rebuild or verification (S-1)" taggedAs UnitTest in {
    val ends: Seq[(String, Long => Command)] = Seq(
      "a dirty verification pass" -> (walk => VerificationBFSComplete(Root, 1L, walk)),
      "a clean verification pass" -> (walk => VerificationBFSComplete(Root, 0L, walk)),
      "a stale verification pass" -> (walk => VerificationBFSComplete(OtherRoot, 0L, walk)),
      "a rebuild" -> (walk => FrontierRebuildComplete(1L, Root, walk))
    )
    ends.foreach { case (name, end) =>
      withClue(s"$name: ") {
        val h = harness()
        walkFails(h, 1L, atMinute = 0)
        walkFails(h, 2L, atMinute = 0)
        h.impl.currentWalk = Some(3L)
        h.impl.verificationBFSRunning = true
        h.kit.run(end(3L))
        // Without the reset these would be failures 3 and 4 of a run two hours old.
        walkFails(h, 4L, atMinute = 120)
        walkFails(h, 5L, atMinute = 120)
        handOffs(h) shouldBe 0
        walkFails(h, 6L, atMinute = 180)
        handOffs(h) shouldBe 1
      }
    }
  }

  it should "not count a cancelled walk, nor the failure of a walk a replaced incarnation launched (S-1)" taggedAs UnitTest in {
    val h = harness()
    (1L to 3L).foreach(walk => walkFails(h, walk, atMinute = walk * 60, cause = None))
    handOffs(h) shouldBe 0
    h.impl.verificationBFSRunning shouldBe false

    h.impl.currentWalk = Some(10L)
    h.impl.verificationBFSRunning = true
    (1 to 3).foreach(_ => h.kit.run(FrontierWalkFailed(99L, Some(boom))))
    handOffs(h) shouldBe 0
    h.impl.verificationBFSRunning shouldBe true // the replaced walk's end changes nothing here

    walkFails(h, 11L, atMinute = 300)
    walkFails(h, 12L, atMinute = 330)
    walkFails(h, 13L, atMinute = 360)
    handOffs(h) shouldBe 1
  }

  it should "never give up on the Path scheme, where a hand-off would publish a trie no walk verified (S-1, review beacon W1)" taggedAs UnitTest in {
    val h = harness(scheme = StorageScheme.Path)
    (1L to 5L).foreach(walk => walkFails(h, walk, atMinute = walk * 60))
    handOffs(h) shouldBe 0
    h.impl.verificationBFSRunning shouldBe false
  }

  // ---- corrupt entries ---------------------------------------------------------------------------------------------

  /** Basic pruning, the ETC default, over 32 accounts: the root is a branch over hash children; `child` is the first,
    * at nibble `slot`.
    */
  private class BasicPruned:
    val nodes: NodeStorage = new NodeStorage(EphemDataSource())
    val state: WatchedState = new WatchedState(nodes)
    val mpt: MptStorage = state.getBackingStorage(0)
    state.lock = mpt
    val root: ByteString = ByteString(
      (0 until 32)
        .foldLeft(MerklePatriciaTrie[ByteString, Account](mpt)) { (trie, i) =>
          trie.put(ByteString(kec256(BigInt(1000 + i).toByteArray)), Account(nonce = UInt256(1L)))
        }
        .getRootHash
    )
    val (slot, child) = mpt.get(root.toArray) match
      case branch: BranchNode =>
        branch.children.zipWithIndex.collectFirst { case (HashNode(h), i) => i -> ByteString(h) }.get
      case other => fail(s"not a branch: $other")
    val childBytes: Array[Byte] = mpt.get(child.toArray).encode
    val childEntry: HealingEntry =
      HealingEntry(Seq(ByteString(HexPrefix.encode(Array(slot.toByte), isLeaf = false))), child)

    def coordinator(ec: ExecutionContext = neverRuns, scheme: StorageScheme = StorageScheme.Hash): Harness =
      harness(root, mpt, HealingTrieFixtures.healingIngest(stateTriesStore = Some(state)), ec, scheme)

    def stored: Option[Seq[Byte]] = nodes.get(child).map(_.toSeq)

  it should "delete a corrupt entry under the heal store's monitor before it queues it for healing (S-1, review vault M2)" taggedAs UnitTest in {
    val b = new BasicPruned
    b.nodes.put(b.child, Array[Byte](1, 2, 3)) // a wrapper that does not unwrap
    val h = b.coordinator()

    h.kit.run(FrontierCorruptFound(Seq(b.childEntry), b.root))

    withClue("the flush's one-writer lock was held for the delete: ")(b.state.heldLockAtDelete shouldBe Some(true))
    b.stored shouldBe None
    h.impl.healRequests.contains(b.child) shouldBe true
    h.impl.pendingTasks.size shouldBe 1
    h.impl.unrepairableNodes.isEmpty shouldBe true
  }

  it should "leave a corrupt entry the store keeps, queue nothing for it, and have the next walk skip it (S-1)" taggedAs UnitTest in {
    val b = new BasicPruned
    // The node's own bytes, out of basic pruning's wrapper: this store cannot read them, and they pass keccak, so they
    // are never deleted.
    b.nodes.put(b.child, b.childBytes)
    val h = b.coordinator(ExecutionContext.parasitic)

    h.kit.run(FrontierCorruptFound(Seq(b.childEntry), b.root))

    b.stored shouldBe Some(b.childBytes.toSeq)
    h.impl.healRequests.contains(b.child) shouldBe false
    h.impl.unrepairableNodes.contains(b.child) shouldBe true
    h.impl.startVerificationBFS(b.root, EmptyPath) // runs inline
    val ends = h.kit.selfInbox().receiveAll()
    ends.collect { case m @ (_: FrontierCorruptFound | _: FrontierRebuilt) => m } shouldBe empty
    ends.collect { case VerificationBFSComplete(_, missing, _) => missing } shouldBe Seq(0L)
    h.impl.walkSkips.get() shouldBe 1L
  }

  it should "try a delete that threw again on the next find, and keep the entry only after the last try (S-1, review vault M1)" taggedAs UnitTest in {
    val b = new BasicPruned
    b.nodes.put(b.child, Array[Byte](1, 2, 3))
    b.state.throwOnDelete = true
    val h = b.coordinator()

    (1 until MaxCorruptDeleteAttempts).foreach { attempt =>
      h.kit.run(FrontierCorruptFound(Seq(b.childEntry), b.root))
      withClue(s"attempt $attempt: not kept for good, not queued, still there: ") {
        h.impl.unrepairableNodes.isEmpty shouldBe true
        h.impl.healRequests.contains(b.child) shouldBe false
        b.stored shouldBe defined
      }
    }
    h.kit.run(FrontierCorruptFound(Seq(b.childEntry), b.root))
    h.impl.unrepairableNodes.contains(b.child) shouldBe true
    h.impl.healRequests.contains(b.child) shouldBe false
  }

  it should "delete and queue an entry whose earlier delete threw, once the store reads again (S-1, review vault M1)" taggedAs UnitTest in {
    val b = new BasicPruned
    b.nodes.put(b.child, Array[Byte](1, 2, 3))
    b.state.throwOnDelete = true
    val h = b.coordinator()

    h.kit.run(FrontierCorruptFound(Seq(b.childEntry), b.root))
    h.impl.healRequests.contains(b.child) shouldBe false
    b.state.throwOnDelete = false
    h.kit.run(FrontierCorruptFound(Seq(b.childEntry), b.root))

    b.stored shouldBe None
    h.impl.healRequests.contains(b.child) shouldBe true
    h.impl.unrepairableNodes.isEmpty shouldBe true
  }

  it should "drop corrupt entries sent under the Path scheme, which never sends any" taggedAs UnitTest in {
    val b = new BasicPruned
    b.nodes.put(b.child, Array[Byte](1, 2, 3))
    val h = b.coordinator(scheme = StorageScheme.Path)

    h.kit.run(FrontierCorruptFound(Seq(b.childEntry), b.root))

    b.state.heldLockAtDelete shouldBe None
    b.stored shouldBe defined
    h.impl.healRequests.contains(b.child) shouldBe false
  }

  it should "take a corrupt entry from a walk's find, through its delete and heal, to a clean pass (S-1)" taggedAs UnitTest in {
    val b = new BasicPruned
    b.nodes.put(b.child, StoredNodeTestAccess.wrapped(Array(0x80.toByte), 1)) // decodes, to an empty value
    val h = b.coordinator(ExecutionContext.parasitic)

    h.impl.startVerificationBFS(b.root, EmptyPath) // runs inline
    val first = h.kit.selfInbox().receiveAll()
    first.collect { case FrontierCorruptFound(entries, _) => entries }.flatten shouldBe Seq(b.childEntry)
    first.collect { case VerificationBFSComplete(_, missing, _) => missing } shouldBe Seq(1L)
    first.foreach(h.kit.run)
    b.stored shouldBe None
    h.impl.healRequests.contains(b.child) shouldBe true
    h.impl.verificationPassComplete shouldBe false

    b.mpt.storeRawNodes(Seq(b.child -> b.childBytes)) // what healing writes once a peer serves the node
    h.kit.selfInbox().receiveAll()
    h.impl.startVerificationBFS(b.root, EmptyPath)
    val second = h.kit.selfInbox().receiveAll()
    second.collect { case m @ (_: FrontierCorruptFound | _: FrontierRebuilt) => m } shouldBe empty
    second.collect { case VerificationBFSComplete(_, missing, _) => missing } shouldBe Seq(0L)
    h.impl.walkSkips.get() shouldBe 0L
  }
