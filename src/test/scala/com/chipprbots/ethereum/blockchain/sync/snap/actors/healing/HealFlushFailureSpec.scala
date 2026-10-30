package com.chipprbots.ethereum.blockchain.sync.snap.actors.healing

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.testkit.typed.scaladsl.FishingOutcomes
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe
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
import com.chipprbots.ethereum.blockchain.sync.snap.actors.PathHealPresenceFixtures.Trie
import com.chipprbots.ethereum.blockchain.sync.snap.actors.PathHealPresenceFixtures.readAtPath
import com.chipprbots.ethereum.blockchain.sync.snap.actors.TrieNodeHealingCoordinator
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.db.dataSource.DataUpdate
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.HealingFrontierStorage
import com.chipprbots.ethereum.db.storage.PathNodeStorage
import com.chipprbots.ethereum.network.p2p.messages.SNAP
import com.chipprbots.ethereum.testing.PeerTestHelpers
import com.chipprbots.ethereum.testing.Tags.UnitTest
import com.chipprbots.ethereum.testing.TestMptStorage

/** A trie store whose next `failures` node writes throw before storing anything. */
final private class FailingWritesMptStorage(failures: Int) extends TestMptStorage:
  val remaining: AtomicInteger = new AtomicInteger(failures)
  override def storeRawNodes(rawNodes: Seq[(ByteString, Array[Byte])]): Unit =
    if remaining.getAndDecrement() > 0 then throw new RuntimeException("injected node write failure")
    super.storeRawNodes(rawNodes)

/** A data source whose next `failures` write batches throw before applying anything. */
final private class FailingWritesDataSource(failures: Int) extends EphemDataSource(Map.empty):
  val remaining: AtomicInteger = new AtomicInteger(failures)
  override def update(dataSourceUpdates: Seq[DataUpdate]): Unit =
    if remaining.getAndDecrement() > 0 then throw new RuntimeException("injected write batch failure")
    super.update(dataSourceUpdates)

/** A trie store whose node writes that include `hash` throw, the first `failures` times; `failed` counts them down. */
final private class FailingWritesOfMptStorage(hash: ByteString, failures: Int) extends TestMptStorage:
  val failed: CountDownLatch = new CountDownLatch(failures)
  override def storeRawNodes(rawNodes: Seq[(ByteString, Array[Byte])]): Unit =
    if failed.getCount > 0 && rawNodes.exists(_._1 == hash) then
      failed.countDown()
      throw new RuntimeException("injected node write failure")
    super.storeRawNodes(rawNodes)

/** A trie store whose node writes that include `hash` throw while `failing` is set; `attempts` counts those writes. */
final private class GatedWritesMptStorage(hash: ByteString) extends TestMptStorage:
  @volatile var failing: Boolean = false
  private val tries = new AtomicInteger(0)
  def attempts: Int = tries.get()
  override def storeRawNodes(rawNodes: Seq[(ByteString, Array[Byte])]): Unit =
    if rawNodes.exists(_._1 == hash) then
      tries.incrementAndGet()
      if failing then throw new RuntimeException("injected node write failure")
    super.storeRawNodes(rawNodes)

/** A data source whose write batches that put `key` in `namespace` throw, the first `failures` times; `failed` counts
  * them down.
  */
final private class FailingWritesOfDataSource(namespace: IndexedSeq[Byte], key: ByteString, failures: Int)
    extends EphemDataSource(Map.empty):
  val failed: CountDownLatch = new CountDownLatch(failures)
  override def update(dataSourceUpdates: Seq[DataUpdate]): Unit =
    val puts = dataSourceUpdates
      .flatMap(HealRequestFixtures.Op.of)
      .exists(op => !op.delete && op.namespace == namespace && op.key == key)
    if failed.getCount > 0 && puts then
      failed.countDown()
      throw new RuntimeException("injected write batch failure")
    super.update(dataSourceUpdates)

/** Spec 022 slice 2, T018 (#1549 on the heal path, plan D3): a node write that throws loses nothing.
  *
  * The bug: `FrontierPersistence.flushRawNodesAsync` cleared the raw-node buffer BEFORE its Future wrote it. A throw in
  * the write therefore dropped the nodes, and since `FlushComplete` was never sent, `flushing` stayed true forever: no
  * later flush ran, and the completion gate (`!flushing`) never opened again. Under the Path scheme the node write ran
  * on the actor thread, so a throw stopped the coordinator.
  *
  * Method: the module test drives `flushRawNodesAsync` directly over a store that throws once. The coordinator tests
  * heal a small trie (`PathHealPresenceFixtures.accountTrieOld`, eight nodes, an extension included) through a real
  * coordinator whose store throws on its first write; the oracle is that healing still completes and every node ends up
  * stored.
  */
class HealFlushFailureSpec extends ScalaTestWithActorTestKit() with AnyFlatSpecLike with Matchers:

  implicit private val classicSystem: org.apache.pekko.actor.ActorSystem = system.classicSystem
  implicit private val actorTestKit: ActorTestKit = testKit

  import HealRequestFixtures.*

  private def node(i: Int): (ByteString, Array[Byte]) =
    val bytes = Array.fill[Byte](8)(i.toByte)
    kec256(ByteString(bytes)) -> bytes

  "The asynchronous healed-node flush" should "keep every buffered node and staged record when the write throws (T018)" taggedAs UnitTest in {
    val frontier = Some(new HealingFrontierStorage(EphemDataSource()))
    val s = new StubFrontierPersistenceState(
      frontier,
      frontierPersistenceEnabled = false,
      prunedEnabled = true,
      healingWriterEc = ExecutionContext.parasitic
    ) with FrontierPersistence
    val nodes = (1 to 3).map(node)
    s.rawNodeBuffer ++= nodes
    s.pendingSubtreeRecords += nodes.head._1
    s.mptStorage.failWrites = 1

    s.flushRawNodesAsync() // the Future runs inline (parasitic) and fails

    s.mptStorage.stored shouldBe empty
    // Nothing was written, so nothing may be dropped: the nodes stay buffered for the next flush and the staged spec
    // 005 record stays staged (it is written only after its node is durable).
    s.rawNodeBuffer.map(_._1).toSeq shouldBe nodes.map(_._1)
    s.pendingSubtreeRecords should contain(nodes.head._1)
    s.selfInbox.receiveAll().collect { case m: TrieNodeHealingCoordinator.FlushComplete => m } shouldBe empty
  }

  private def awaitHealingComplete(controller: TestProbe[SNAPSyncController.Command]): Unit =
    controller.fishForMessage(30.seconds) {
      case SNAPSyncController.StateHealingComplete => FishingOutcomes.complete
      case _                                       => FishingOutcomes.continueAndIgnore
    }

  private def heal(scheme: StorageScheme, mpt: TestMptStorage, pns: Option[PathNodeStorage]): Unit =
    val trie = PathHealPresenceFixtures.accountTrieOld()
    val peer = new ScriptedPeer(testKit)
    peer.serveFrom.set(Some(trie.served))
    val pool = Executors.newSingleThreadExecutor()
    val controller = createTestProbe[SNAPSyncController.Command]()
    val coordinator = HealingTrieFixtures.spawnCoordinator(
      stateRoot = trie.root.hash,
      networkPeerManager = peer.ref,
      requestTracker = new SNAPRequestTracker()(classicSystem.scheduler),
      mptStorage = mpt,
      batchSize = 16,
      snapSyncController = controller.ref,
      healingWriterEcOverride = Some(ExecutionContext.fromExecutorService(pool)),
      storageScheme = scheme,
      pathNodeStorageOpt = pns,
      movingRootDeltaHeal = true
    )
    peer.coordinator.set(coordinator)
    try
      coordinator ! TrieNodeHealingCoordinator.StartTrieNodeHealing(trie.root.hash)
      coordinator ! TrieNodeHealingCoordinator.HealingPeerAvailable(
        PeerTestHelpers.createTestPeer("flush-failure-peer", createTestProbe[Any]().ref.toClassic)
      )
      awaitHealingComplete(controller)
    finally
      testKit.stop(coordinator)
      testKit.stop(peer.ref)
      pool.shutdown()
      pool.awaitTermination(5, TimeUnit.SECONDS)

  "A healing coordinator" should "complete and store every node after a node write throws, under the Hash scheme (T018)" taggedAs UnitTest in {
    val trie = PathHealPresenceFixtures.accountTrieOld()
    val mpt = new FailingWritesMptStorage(failures = 1)
    heal(StorageScheme.Hash, mpt, None)
    mpt.remaining.get() should be <= 0
    trie.nodes.foreach { n =>
      withClue(s"${n.label}: ") {
        noException should be thrownBy mpt.get(n.hash.toArray)
      }
    }
  }

  it should "complete and place every node after a write batch throws, under the Path scheme (T018)" taggedAs UnitTest in {
    val trie = PathHealPresenceFixtures.accountTrieOld()
    val source = new FailingWritesDataSource(failures = 1)
    val pns = new PathNodeStorage(source)
    heal(StorageScheme.Path, new TestMptStorage(), Some(pns))
    source.remaining.get() should be <= 0
    trie.nodes.foreach { n =>
      withClue(s"${n.label}: ") {
        readAtPath(pns, n).map(ByteString(_)) shouldBe Some(n.encoded)
      }
    }
  }

  // ---- spec 022 review hunter W1, eye F1: the last write fails, and nothing pending or in flight retries it --------

  /** Heal `accountTrieOld` with every node served at once, over a store whose writes of the root (the last commit,
    * which completes the trie) fail twice: once at the response that completes it, once at the completion check's
    * retry. Then nothing is pending or in flight; only the 2-minute pulse comes. It must retry the write.
    */
  private def lastWriteFailsTwice(scheme: StorageScheme): Unit =
    val trie = PathHealPresenceFixtures.accountTrieOld()
    val hashStore = new FailingWritesOfMptStorage(trie.root.hash, failures = 2)
    val pathSource = new FailingWritesOfDataSource(
      HealRequestFixtures.AccountNs,
      HealRequestFixtures.accountKey(Array.empty),
      failures = 2
    )
    val pns = new PathNodeStorage(pathSource)
    val failed = if scheme == StorageScheme.Hash then hashStore.failed else pathSource.failed
    val peer = new ScriptedPeer(testKit)
    peer.serveFrom.set(Some(trie.served))
    val pool = Executors.newSingleThreadExecutor()
    val controller = createTestProbe[SNAPSyncController.Command]()
    val coordinator = HealingTrieFixtures.spawnCoordinator(
      stateRoot = trie.root.hash,
      networkPeerManager = peer.ref,
      requestTracker = new SNAPRequestTracker()(classicSystem.scheduler),
      mptStorage = if scheme == StorageScheme.Hash then hashStore else new TestMptStorage(),
      batchSize = 16,
      snapSyncController = controller.ref,
      healingWriterEcOverride = Some(ExecutionContext.fromExecutorService(pool)),
      storageScheme = scheme,
      pathNodeStorageOpt = Option.when(scheme == StorageScheme.Path)(pns),
      movingRootDeltaHeal = true
    )
    peer.coordinator.set(coordinator)
    try
      coordinator ! TrieNodeHealingCoordinator.StartTrieNodeHealing(trie.root.hash)
      coordinator ! TrieNodeHealingCoordinator.HealingPeerAvailable(
        PeerTestHelpers.createTestPeer("last-write-peer", createTestProbe[Any]().ref.toClassic)
      )
      failed.await(30, TimeUnit.SECONDS) shouldBe true
      // Both writes failed. The pulse is the only thing still to come.
      coordinator ! TrieNodeHealingCoordinator.HealingStagnationCheck
      awaitHealingComplete(controller)
      trie.nodes.foreach { n =>
        withClue(s"${n.label}: ") {
          if scheme == StorageScheme.Hash then noException should be thrownBy hashStore.get(n.hash.toArray)
          else readAtPath(pns, n).map(ByteString(_)) shouldBe Some(n.encoded)
        }
      }
    finally
      testKit.stop(coordinator)
      testKit.stop(peer.ref)
      pool.shutdown()
      pool.awaitTermination(5, TimeUnit.SECONDS)

  it should "retry a failed last write on the next pulse when nothing is pending or in flight, under the Path scheme (hunter W1)" taggedAs UnitTest in {
    lastWriteFailsTwice(StorageScheme.Path)
  }

  it should "retry a failed last flush on the next pulse when nothing is pending or in flight, under the Hash scheme (hunter W1)" taggedAs UnitTest in {
    lastWriteFailsTwice(StorageScheme.Hash)
  }

  // ---- spec 022 review forge F1, hunter W1 (Hash): no verification walk over an unwritten flush buffer --------------

  /** A Hash coordinator healing `trie` over `store`, with a writer executor that runs only when the test says (flushes
    * and walks both run on it) and a peer the test answers by hand, so every step is ordered by the test.
    */
  final private class FlushGateRig(val trie: Trie, val store: GatedWritesMptStorage):
    val ec = new ManualExecutionContext
    val peer = new ScriptedPeer(testKit)
    val controller: TestProbe[SNAPSyncController.Command] = createTestProbe[SNAPSyncController.Command]()
    val coordinator: org.apache.pekko.actor.typed.ActorRef[TrieNodeHealingCoordinator.Command] =
      HealingTrieFixtures.spawnCoordinator(
        stateRoot = trie.root.hash,
        networkPeerManager = peer.ref,
        requestTracker = new SNAPRequestTracker()(classicSystem.scheduler),
        mptStorage = store,
        batchSize = 16,
        snapSyncController = controller.ref,
        healingWriterEcOverride = Some(ec),
        storageScheme = StorageScheme.Hash,
        movingRootDeltaHeal = true
      )
    peer.coordinator.set(coordinator)

    def start(): Unit =
      coordinator ! TrieNodeHealingCoordinator.StartTrieNodeHealing(trie.root.hash)
      coordinator ! TrieNodeHealingCoordinator.HealingPeerAvailable(
        PeerTestHelpers.createTestPeer("flush-gate-peer", createTestProbe[Any]().ref.toClassic)
      )

    /** Answer the next request with the nodes of `labels` it names, the others omitted. */
    def answerNext(labels: String*): Unit =
      val served = trie.nodes.filter(n => labels.contains(n.label)).map(n => n.pathset -> n.encoded).toMap
      peer.answerFrom(peer.manual.expectMessageType[SNAP.GetTrieNodes], served)

    /** Mailbox barrier, three deep: a message sent before the call, the completion check it sends, and what that check
      * sends have all been handled.
      */
    def settle(): Unit =
      (1 to 3).foreach { _ =>
        val probe = createTestProbe[com.chipprbots.ethereum.blockchain.sync.snap.actors.HealingStatistics]()
        coordinator ! TrieNodeHealingCoordinator.HealingGetProgress(probe.ref)
        probe.receiveMessage(30.seconds)
      }

    /** Run the writer executor's next task (a flush, its completion callback, or a walk), then let the coordinator
      * handle what it sent.
      */
    def step(): Unit =
      ec.runNext()
      settle()

    /** Run what waits on the writer executor and what follows from it, one task at a time, at most `steps` tasks (a
      * walk that restarts itself forever ends here, not in a hang).
      */
    def drive(steps: Int = 24): Unit =
      settle()
      var left = steps
      while left > 0 && ec.pending > 0 do
        step()
        left -= 1

    def pulse(): Unit =
      coordinator ! TrieNodeHealingCoordinator.HealingStagnationCheck
      drive()

    /** Healing completes, and the controller is never asked to move the pivot on the way. */
    def awaitCompleteWithoutRepeg(): Unit =
      controller.fishForMessage(30.seconds) {
        case SNAPSyncController.StateHealingComplete => FishingOutcomes.complete
        case m @ (_: SNAPSyncController.HealingStagnated | SNAPSyncController.HealingAllPeersStateless) =>
          FishingOutcomes.fail(s"asked the controller to move the pivot: $m")
        case _ => FishingOutcomes.continueAndIgnore
      }

    def everyNodeStored(): Unit =
      trie.nodes.foreach(n => withClue(s"${n.label}: ")(noException should be thrownBy store.get(n.hash.toArray)))

    def stop(): Unit =
      testKit.stop(coordinator)
      testKit.stop(peer.ref)

  /** Put every node of `trie` except `except` in `store` directly, as earlier download phases would have. */
  private def seed(store: TestMptStorage, trie: Trie, except: Set[String]): Unit =
    trie.nodes.filterNot(n => except(n.label)).foreach(n => store.storeRawNodes(Seq(n.hash -> n.encoded.toArray)))

  it should "write a buffer whose flush failed while a verification walk ran before walking again, under the Hash scheme (forge F1, trigger A)" taggedAs UnitTest in {
    val trie = PathHealPresenceFixtures.accountTrieOld()
    val store = new GatedWritesMptStorage(trie("leafY0").hash)
    seed(store, trie, Set("root", "leafY0")) // brY is on disk over an absent leafY0: a hole only a walk finds
    val rig = new FlushGateRig(trie, store)
    try
      rig.start()
      rig.answerNext("root") // its children are on disk, so it commits at once
      rig.settle()
      withClue("the root's flush waits: ")(rig.ec.pending shouldBe 1)
      rig.step() // its flush writes it
      rig.step() // ... its completion arrives, and the completion check starts a verification walk
      withClue("the verification walk waits: ")(rig.ec.pending shouldBe 1)
      // While the walk runs, the controller's own walk reports leafY0 missing. It is healed, and its flush fails.
      store.failing = true
      rig.coordinator ! TrieNodeHealingCoordinator.QueueMissingNodes(Seq(trie("leafY0").pathset -> trie("leafY0").hash))
      rig.answerNext("leafY0")
      rig.settle()
      withClue("the walk, then leafY0's flush: ")(rig.ec.pending shouldBe 2)
      rig.ec.runNewest() // the flush throws ...
      rig.ec.runNewest() // ... and its FlushFailed reaches the coordinator while the walk is still running
      rig.settle()
      // The walk now reads the store: leafY0 is not on it, so the walk ends dirty, with nothing pending.
      withClue("the walk is all that waits: ")(rig.ec.pending shouldBe 1)
      rig.step()
      // The disk works again. The next pulse must write the buffer; a clean walk then completes healing.
      store.failing = false
      val attemptsBefore = store.attempts
      rig.pulse()
      withClue("the failed flush is attempted again: ")(store.attempts should be > attemptsBefore)
      rig.awaitCompleteWithoutRepeg()
      rig.everyNodeStored()
    finally rig.stop()
  }

  it should "not let the dead-pulse watchdog walk over a buffer whose flushes keep failing, under the Hash scheme (forge F1 trigger B, hunter W1)" taggedAs UnitTest in {
    val trie = PathHealPresenceFixtures.accountTrieOld()
    val store = new GatedWritesMptStorage(trie.root.hash)
    seed(store, trie, Set("root"))
    val rig = new FlushGateRig(trie, store)
    try
      rig.start()
      store.failing = true
      rig.answerNext("root") // committed, and every write of it fails
      rig.drive() // its flush fails, and so does the completion check's synchronous flush
      // Nothing is pending or in flight. Four pulses: the first counts the heal, the next three are dead, and the
      // third dead one is the watchdog's.
      (1 to 4).foreach(_ => rig.pulse())
      store.failing = false
      val attemptsBefore = store.attempts
      rig.pulse()
      withClue("the failed flush is attempted again: ")(store.attempts should be > attemptsBefore)
      rig.awaitCompleteWithoutRepeg()
      rig.everyNodeStored()
    finally rig.stop()
  }

  it should "not walk a re-pegged root that is on disk while the flush buffer cannot be written, under the Hash scheme (forge F1, trigger C)" taggedAs UnitTest in {
    val before = PathHealPresenceFixtures.accountTrieOld()
    val after = PathHealPresenceFixtures.accountTrieNew()
    // brY and leafY0 are in both tries. The new trie is on disk without them.
    val store = new GatedWritesMptStorage(after("leafY0").hash)
    seed(store, after, Set("brY", "leafY0"))
    val rig = new FlushGateRig(before, store)
    try
      rig.start()
      rig.answerNext("root")
      rig.answerNext("brY") // ext is left unanswered: the old root waits on it
      store.failing = true
      rig.answerNext("leafY0") // brY's subtree commits; its flush will fail
      rig.drive()
      // The pivot moves to the new root, which is on disk. The re-peg's synchronous flush fails too.
      rig.coordinator ! TrieNodeHealingCoordinator.HealingPivotRefreshed(after.root.hash)
      rig.drive()
      store.failing = false
      val attemptsBefore = store.attempts
      rig.pulse()
      withClue("the failed flush is attempted again: ")(store.attempts should be > attemptsBefore)
      rig.awaitCompleteWithoutRepeg()
      after.nodes.foreach(n => withClue(s"${n.label}: ")(noException should be thrownBy store.get(n.hash.toArray)))
    finally rig.stop()
  }

  // ---- spec 022 review vault V6: a commit write that keeps failing is said to the controller -----------------------

  it should "abandon, loudly and never as a completion, after MaxConsecutiveCommitFailures commit writes in a row fail, under the Path scheme (vault V6)" taggedAs UnitTest in {
    val trie = PathHealPresenceFixtures.accountTrieOld()
    val source = new FailingWritesDataSource(failures = Int.MaxValue)
    val pns = new PathNodeStorage(source)
    val peer = new ScriptedPeer(testKit)
    peer.serveFrom.set(Some(trie.served))
    val controller = createTestProbe[SNAPSyncController.Command]()
    val coordinator = HealingTrieFixtures.spawnCoordinator(
      stateRoot = trie.root.hash,
      networkPeerManager = peer.ref,
      requestTracker = new SNAPRequestTracker()(classicSystem.scheduler),
      mptStorage = new TestMptStorage(),
      batchSize = 16,
      snapSyncController = controller.ref,
      healingWriterEcOverride = Some(new ManualExecutionContext),
      storageScheme = StorageScheme.Path,
      pathNodeStorageOpt = Some(pns),
      movingRootDeltaHeal = true
    )
    peer.coordinator.set(coordinator)
    coordinator ! TrieNodeHealingCoordinator.StartTrieNodeHealing(trie.root.hash)
    coordinator ! TrieNodeHealingCoordinator.HealingPeerAvailable(
      PeerTestHelpers.createTestPeer("commit-failing-peer", createTestProbe[Any]().ref.toClassic)
    )
    val deadline = 30.seconds.fromNow
    var abandoned = false
    while !abandoned do
      withClue("no abandonment within the deadline: ")(deadline.hasTimeLeft() shouldBe true)
      coordinator ! TrieNodeHealingCoordinator.HealingCheckCompletion // each retries the write
      try
        controller.receiveMessage(50.millis) match
          case SNAPSyncController.StateHealingAbandoned => abandoned = true
          case SNAPSyncController.StateHealingComplete  => fail("completed over writes that all failed")
          case _                                        => ()
      catch case _: AssertionError => ()
    testKit.stop(coordinator)
  }
