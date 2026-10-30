package com.chipprbots.ethereum.blockchain.sync.snap.actors.healing

import java.net.InetSocketAddress

import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.testkit.typed.scaladsl.TestInbox
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.util.ByteString

import scala.collection.mutable

import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.snap.SNAPRequestTracker
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController
import com.chipprbots.ethereum.blockchain.sync.snap.StateValidator
import com.chipprbots.ethereum.blockchain.sync.snap.StorageScheme
import com.chipprbots.ethereum.blockchain.sync.snap.actors.TrieNodeHealingCoordinator
import com.chipprbots.ethereum.blockchain.sync.snap.actors.TrieNodeHealingCoordinator.ActiveRequest
import com.chipprbots.ethereum.blockchain.sync.snap.actors.TrieNodeHealingCoordinator.HealingCheckCompletion
import com.chipprbots.ethereum.blockchain.sync.snap.actors.TrieNodeHealingCoordinator.HealingEntry
import com.chipprbots.ethereum.blockchain.sync.snap.actors.healing.HealRequestFixtures.AccountNs
import com.chipprbots.ethereum.blockchain.sync.snap.actors.healing.HealRequestFixtures.Op
import com.chipprbots.ethereum.blockchain.sync.snap.actors.healing.HealRequestFixtures.RecordingDataSource
import com.chipprbots.ethereum.blockchain.sync.snap.actors.healing.HealRequestFixtures.accountKey
import com.chipprbots.ethereum.blockchain.sync.snap.controller.StateIngest
import com.chipprbots.ethereum.blockchain.sync.snap.heal.FlushBuffer
import com.chipprbots.ethereum.blockchain.sync.snap.heal.HealRequest
import com.chipprbots.ethereum.blockchain.sync.snap.heal.HealRequestBook
import com.chipprbots.ethereum.blockchain.sync.snap.heal.HealTaskQueue
import com.chipprbots.ethereum.blockchain.sync.snap.heal.HeldNodeSet
import com.chipprbots.ethereum.blockchain.sync.snap.heal.PathPresence
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.db.storage.BfsQueueStorage
import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.db.storage.FlatSlotStorage
import com.chipprbots.ethereum.db.storage.HealingFrontierStorage
import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.db.storage.PathNodeStorage
import com.chipprbots.ethereum.db.storage.SnapStorageDoneStorage
import com.chipprbots.ethereum.db.storage.SnapSyncProgressStorage
import com.chipprbots.ethereum.db.storage.StateStorage
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.domain.BlockchainWriter
import com.chipprbots.ethereum.domain.CodeHash
import com.chipprbots.ethereum.domain.TrieRoot
import com.chipprbots.ethereum.mpt.BranchNode
import com.chipprbots.ethereum.mpt.HashNode
import com.chipprbots.ethereum.mpt.HexPrefix
import com.chipprbots.ethereum.mpt.LeafNode
import com.chipprbots.ethereum.mpt.MptNode
import com.chipprbots.ethereum.mpt.NullNode
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.PeerActor
import com.chipprbots.ethereum.network.PeerId
import com.chipprbots.ethereum.network.p2p.messages.SNAP
import com.chipprbots.ethereum.testing.Tags.UnitTest
import com.chipprbots.ethereum.testing.TestMptStorage

/** Everything `HealResponseProcessing` needs, as a stub (spec 016 FR-016 (b), C-H5): its `HealResponseProcessingState`,
  * the coordinator's `TrieNodeHealingEnv`, and the interfaces it calls (`FrontierGaugesApi`, `FrontierWriteApi`,
  * `HealDispatchApi`, `PathVerifiedRlpApi`, `PathPresenceApi`). State is plain fields; every interface call is
  * recorded. `self` and the controller are test inboxes; the request tracker is real (a response completes its
  * request). `stateIngest` hands out `pathNodes`, `healingFrontier` and `bytecode` (the last only when a code store is
  * given: with the healed-code check off the module must never read it).
  */
private class StubHealResponseProcessingState(
    val requestTracker: SNAPRequestTracker,
    val snapSyncController: ActorRef[SNAPSyncController.Command],
    val storageScheme: StorageScheme,
    val prunedEnabled: Boolean,
    val healedCodeCheck: Boolean,
    codeStore: Option[EvmCodeStorage],
    pathStore: Option[PathNodeStorage],
    val scopedHealMaxPaths: Int,
    val decoupledHealServeRoot: Boolean
) extends HealResponseProcessingState
    with TrieNodeHealingEnv
    with FrontierGaugesApi
    with FrontierWriteApi
    with HealDispatchApi
    with PathVerifiedRlpApi
    with PathPresenceApi:

  // HealResponseProcessingState
  var stateRoot: ByteString = ByteString(Array.fill[Byte](32)(7))
  var serveRoot: ByteString = stateRoot
  val pendingTasks: HealTaskQueue[HealingEntry] =
    new HealTaskQueue[HealingEntry](e => HealRequest.depthOf(e.pathset), _.hash, _ => false)
  val healRequests: HealRequestBook = new HealRequestBook()
  val heldNodes: HeldNodeSet = new HeldNodeSet()
  val activeRequests: mutable.Map[BigInt, ActiveRequest] = mutable.Map.empty
  val rawNodeBuffer: FlushBuffer = new FlushBuffer()
  var totalNodesHealed: Int = 0
  var crossRootHealCount: Long = 0L
  val healAttempts: mutable.Map[ByteString, Int] = mutable.Map.empty
  val healedPathsThisRound: mutable.LinkedHashMap[ByteString, HealingEntry] = mutable.LinkedHashMap.empty
  var healedPathsRoot: ByteString = ByteString.empty
  var healedPathsOverflowed: Boolean = false
  var totalBytesReceived: Long = 0L
  var completedTaskCount: Int = 0
  val statelessPeers: mutable.Set[String] = mutable.Set.empty
  val emptyResponseStrikes: mutable.Map[String, Int] = mutable.Map.empty
  val knownAvailablePeers: mutable.Set[Peer] = mutable.Set.empty
  var pivotRefreshRequested: Boolean = false
  var pivotRefreshRequestedAt: Long = 0L
  var lastHealedAtMs: Long = 0L
  val pendingSubtreeRecords: mutable.Set[ByteString] = mutable.Set.empty
  var verificationPassComplete: Boolean = false
  var consecutiveCommitFailures: Int = 0
  var runningWalkInvalidated: Int = 0
  def invalidateRunningWalk(): Unit = runningWalkInvalidated += 1
  var giveUpEscalated: Boolean = false

  // TrieNodeHealingEnv (requestTracker, snapSyncController, storageScheme, healedCodeCheck, scopedHealMaxPaths and
  // decoupledHealServeRoot are constructor vals)
  val log: org.slf4j.Logger = org.slf4j.LoggerFactory.getLogger(classOf[HealResponseProcessingSpec])
  val asyncLog: org.slf4j.Logger = log
  val selfInbox: TestInbox[TrieNodeHealingCoordinator.Command] = TestInbox[TrieNodeHealingCoordinator.Command]()
  val self: ActorRef[TrieNodeHealingCoordinator.Command] = selfInbox.ref
  val mptStorage: ReadFailingMptStorage = new ReadFailingMptStorage()
  def networkPeerManager: ActorRef[NetworkPeerManagerActor.Command] = notReached("networkPeerManager")
  val batchSize: Int = 32
  val movingRootDeltaHeal: Boolean = false
  val prunedHealVerification: Boolean = prunedEnabled
  val frontierPersistenceEnabled: Boolean = false
  val decoupledHealMaxAttemptsNoRefresh: Int = 3
  val frontierHighWater: Int = 100
  val frontierLowWater: Int = 10
  val frontierBackpressureMaxWaitMs: Long = 0L

  /** The subtree-complete records the spec 005 seed consults (`isSubtreeComplete`). */
  val frontierStore: HealingFrontierStorage = new HealingFrontierStorage(EphemDataSource())
  val stateIngest: StateIngest = new StateIngest:
    def appState: AppStateStorage = notReached("appState")
    def stateTries: StateStorage = notReached("stateTries")
    def flatSlots: FlatSlotStorage = notReached("flatSlots")
    def pathNodes: Option[PathNodeStorage] = pathStore
    def bytecode: EvmCodeStorage = codeStore.getOrElse(notReached("bytecode"))
    def chainReader: BlockchainReader = notReached("chainReader")
    def chainWriter: BlockchainWriter = notReached("chainWriter")
    def validatorFactory: MptStorage => StateValidator = notReached("validatorFactory")
    def durability: StateIngest.Durability = StateIngest.Durability.Default
    def trieStore(pivot: BigInt): MptStorage = notReached("trieStore")
    def snapProgress: SnapSyncProgressStorage = notReached("snapProgress")
    def storageDone: SnapStorageDoneStorage = notReached("storageDone")
    def healingFrontier: Option[HealingFrontierStorage] = Some(frontierStore)
    def bfsQueue: Option[BfsQueueStorage] = notReached("bfsQueue")

  // The interfaces the module calls, each call recorded in order.
  val calls: mutable.Buffer[String] = mutable.Buffer.empty
  val persisted: mutable.Buffer[Seq[HealingEntry]] = mutable.Buffer.empty
  val unservable: mutable.Buffer[ByteString] = mutable.Buffer.empty
  val pathReads: mutable.Map[(ByteString, Seq[ByteString]), Array[Byte]] = mutable.Map.empty

  // FrontierGaugesApi
  def emitHealingFrontierGauges(): Unit = calls += "gauges"

  // FrontierWriteApi
  def persistFrontier(entries: Seq[HealingEntry]): Unit =
    persisted += entries
    calls += "persist"
  def flushRawNodesAsync(): Unit = calls += "flush"

  // HealDispatchApi
  def noteUnservableAttempt(hash: ByteString): Unit = unservable += hash
  def responseBytesTargetFor(peer: Peer): BigInt = BigInt(512 * 1024)
  def adjustResponseBytesOnSuccess(peer: Peer, requested: BigInt, received: BigInt): Unit =
    calls += s"budget-up:${peer.id.value}:$received"
  def adjustResponseBytesOnFailure(peer: Peer, reason: String): Unit = calls += s"budget-down:${peer.id.value}"
  def recordPeerCooldown(peer: Peer, reason: String): Unit = calls += s"cooldown:${peer.id.value}"
  def dispatchIfPossible(peer: Peer): Unit = calls += s"dispatch:${peer.id.value}"
  def updateHealThrottle(delivered: Int, elapsedMs: Long): Unit = calls += s"throttle:$delivered"

  // PathVerifiedRlpApi
  def readPathVerifiedRlp(hash: ByteString, pathset: Seq[ByteString]): Option[Array[Byte]] =
    pathReads.get((hash, pathset))

  // PathPresenceApi: present exactly where `readPathVerifiedRlp` answers; stale bytes of another hash where
  // `stalePaths` names the pathset.
  val stalePaths: mutable.Set[Seq[ByteString]] = mutable.Set.empty
  // A read at one of these pathsets throws, once (a storage read failure, spec 022 review forge W1).
  val failingReads: mutable.Set[Seq[ByteString]] = mutable.Set.empty
  def pathPresence(hash: ByteString, pathset: Seq[ByteString]): PathPresence =
    if failingReads.remove(pathset) then throw new RuntimeException("injected path read failure")
    else if pathReads.contains((hash, pathset)) then PathPresence.Present
    else if stalePaths.contains(pathset) then PathPresence.Mismatch
    else PathPresence.Absent

  private def notReached(member: String): Nothing =
    throw new UnsupportedOperationException(s"HealResponseProcessing read $member")

/** A test trie store whose next `failReads` batched reads throw (a storage read failure, spec 022 review forge W1). */
final private class ReadFailingMptStorage extends TestMptStorage:
  @volatile var failReads: Int = 0
  override def multiGetNodes(hashes: Seq[Array[Byte]]): Seq[Option[MptNode]] =
    if failReads > 0 then
      failReads -= 1
      throw new RuntimeException("injected node read failure")
    super.multiGetNodes(hashes)

/** Spec 016 C-H5 stub test (FR-016 (b), T075): `HealResponseProcessing` mixed into a stub of its state interface, the
  * coordinator environment and the interfaces it calls. The cases drive a served response through `handleResponse` and
  * assert on the stub's state afterwards: the hash-matched accept and the dropped content mismatch (the spec 004
  * consensus-safety invariant), the re-queue of an unsatisfied task, the Hash-scheme buffer and the Path-scheme write
  * at the task's path and every alias, the spec 003 capture and its overflow latch, the throttle, budget, flush and
  * dispatch feedback, the stateless strikes and the all-peers-stateless pivot refresh, the inline child discovery (a
  * branch's absent children, the account-leaf storage-root seed), the healed-code report on and off, the spec 005
  * subtree-complete seed, and `queueNodes`, `isNodeInStorage`, `isComplete` and `clearHealedPathsSet`.
  */
class HealResponseProcessingSpec extends ScalaTestWithActorTestKit with AnyFlatSpecLike with Matchers:

  private val emptyPath: ByteString = ByteString(HexPrefix.encode(Array.empty[Byte], isLeaf = false))

  private def compact(nibbles: Int*): ByteString = ByteString(HexPrefix.encode(nibbles.map(_.toByte).toArray, false))

  /** The module on its stub and the inbox the controller listens on. */
  final private class Harness(
      scheme: StorageScheme = StorageScheme.Hash,
      pruned: Boolean = false,
      codeCheck: Boolean = false,
      codeStore: Option[EvmCodeStorage] = None,
      pathStore: Option[PathNodeStorage] = None,
      scopedMax: Int = 200_000,
      decoupled: Boolean = false
  ):
    val controller: TestInbox[SNAPSyncController.Command] = TestInbox[SNAPSyncController.Command]()
    val tracker = new SNAPRequestTracker()(system.classicSystem.scheduler)
    val module: StubHealResponseProcessingState & HealResponseProcessing =
      new StubHealResponseProcessingState(
        tracker,
        controller.ref,
        scheme,
        pruned,
        codeCheck,
        codeStore,
        pathStore,
        scopedMax,
        decoupled
      ) with HealResponseProcessing

    /** Put `tasks` in flight to `p` as request `id` and answer it with `nodes`. Each task is first recorded as a live
      * request (a no-op when it already is one), as every dispatched fetch is in production (spec 022 review W2).
      */
    def respond(id: Int, p: Peer, tasks: Seq[HealingEntry], nodes: Seq[ByteString]): Unit =
      tasks.foreach(task => module.healRequests.add(module.healRequestFor(task)))
      module.activeRequests(BigInt(id)) = ActiveRequest(tasks, p, requestedBytes = BigInt(1024))
      module.handleResponse(SNAP.TrieNodes(requestId = BigInt(id), nodes = nodes))

    /** As [[respond]], for tasks the live requests (`healRequests`) do not hold: a dispatched entry nothing vouches
      * for.
      */
    def respondUnbooked(id: Int, p: Peer, tasks: Seq[HealingEntry], nodes: Seq[ByteString]): Unit =
      module.activeRequests(BigInt(id)) = ActiveRequest(tasks, p, requestedBytes = BigInt(1024))
      module.handleResponse(SNAP.TrieNodes(requestId = BigInt(id), nodes = nodes))

  private def peer(id: String): Peer =
    Peer(PeerId(id), new InetSocketAddress("127.0.0.1", 30303), TestInbox[PeerActor.Command]().ref, false)

  private def leaf(key: Int*)(value: String): LeafNode =
    LeafNode(ByteString(key.map(_.toByte).toArray), ByteString(value))

  private def bytes(n: MptNode): ByteString = ByteString(n.encode)

  private def hashOf(n: MptNode): ByteString = ByteString(n.hash)

  "HealResponseProcessing" should "accept a node only when it hashes to a requested task, and re-queue what the response left unsatisfied" taggedAs UnitTest in {
    val h = new Harness()
    val m = h.module
    val p = peer("p1")
    val served = leaf(1, 2, 3)("served")
    val skipped = HealingEntry(Seq(compact(2)), ByteString(Array.fill[Byte](32)(9)))
    val task = HealingEntry(Seq(compact(1)), hashOf(served))
    m.statelessPeers += p.id.value
    m.emptyResponseStrikes(p.id.value) = 3

    // A content mismatch (a node no task asked for) is dropped: never buffered, never counted.
    h.respond(1, p, Seq(task, skipped), Seq(bytes(served), bytes(leaf(4)("other"))))

    m.rawNodeBuffer.map(_._1).toSeq shouldBe Seq(hashOf(served))
    m.rawNodeBuffer.head._2.toSeq shouldBe served.encode.toSeq
    m.totalNodesHealed shouldBe 1
    m.completedTaskCount shouldBe 1
    m.totalBytesReceived shouldBe served.encode.length.toLong
    m.activeRequests shouldBe empty
    m.pendingTasks.toSeq shouldBe Seq(skipped)
    // spec 022 D2: the served request is satisfied; the skipped one stays live (queued again, one fetch).
    m.healRequests.fetchingHashes shouldBe Set(skipped.hash)
    m.unservable.toSeq shouldBe Seq(skipped.hash)
    // A heal from this peer clears its stateless mark and strikes and resets the stagnation timer.
    m.statelessPeers shouldBe empty
    m.emptyResponseStrikes shouldBe empty
    m.lastHealedAtMs should be > 0L
    m.calls.toSeq shouldBe Seq(
      "throttle:1",
      s"budget-up:p1:${served.encode.length}",
      "flush",
      "dispatch:p1"
    )
    h.controller.receiveAll() shouldBe Seq(SNAPSyncController.ProgressNodesHealed(1L))
    m.selfInbox.receiveAll() shouldBe Seq(HealingCheckCompletion)
  }

  it should "ignore a response to a request it does not track" taggedAs UnitTest in {
    val h = new Harness()
    h.module.handleResponse(SNAP.TrieNodes(requestId = BigInt(77), nodes = Seq(bytes(leaf(1)("x")))))
    h.module.totalNodesHealed shouldBe 0
    h.module.calls shouldBe empty
    h.module.selfInbox.hasMessages shouldBe false
  }

  it should "capture healed paths against the walk root, latch the overflow at the cap, and count cross-root heals" taggedAs UnitTest in {
    val h = new Harness(scopedMax = 1, decoupled = true)
    val m = h.module
    m.serveRoot = ByteString(Array.fill[Byte](32)(8))
    val a = leaf(1)("a")
    val b = leaf(2)("b")
    val ta = HealingEntry(Seq(compact(1)), hashOf(a))
    val tb = HealingEntry(Seq(compact(2)), hashOf(b))
    m.healAttempts(ta.hash) = 2

    h.respond(1, peer("p1"), Seq(ta, tb), Seq(bytes(a), bytes(b)))

    m.healedPathsRoot shouldBe m.stateRoot
    m.healedPathsThisRound.values.toSeq shouldBe Seq(ta)
    m.healedPathsOverflowed shouldBe true
    m.crossRootHealCount shouldBe 2L
    m.healAttempts shouldBe empty

    m.clearHealedPathsSet()
    m.healedPathsThisRound shouldBe empty
    m.healedPathsRoot shouldBe ByteString.empty
    m.healedPathsOverflowed shouldBe false
  }

  it should "strike a peer on an empty response, and at the fifth mark it stateless and request a pivot refresh" taggedAs UnitTest in {
    val h = new Harness()
    val m = h.module
    val p = peer("lonely")
    m.knownAvailablePeers += p
    val task = HealingEntry(Seq(compact(3)), ByteString(Array.fill[Byte](32)(3)))

    (1 to 4).foreach(i => h.respond(i, p, Seq(task), Seq.empty))
    m.emptyResponseStrikes(p.id.value) shouldBe 4
    m.statelessPeers shouldBe empty
    m.pivotRefreshRequested shouldBe false
    m.calls.take(4).toSeq shouldBe Seq("throttle:0", "budget-down:lonely", "cooldown:lonely", "dispatch:lonely")
    h.controller.receiveAll() shouldBe empty

    h.respond(5, p, Seq(task), Seq.empty)
    m.statelessPeers shouldBe Set(p.id.value)
    m.knownAvailablePeers shouldBe empty
    m.pivotRefreshRequested shouldBe true
    m.pivotRefreshRequestedAt should be > 0L
    h.controller.receiveAll() shouldBe Seq(SNAPSyncController.HealingAllPeersStateless)
    // The unsatisfied task is back in the queue once per response.
    m.pendingTasks.count(_ == task) shouldBe 5
  }

  it should "not strike, cool or shrink the budget of a peer that answers empty a request with nothing left to fill (beacon N4)" taggedAs UnitTest in {
    val h = new Harness()
    val m = h.module
    val p = peer("innocent")
    m.knownAvailablePeers += p
    // Its one task was retired or never vouched for: nothing could fill it, whatever the peer sent.
    val task = HealingEntry(Seq(compact(5)), ByteString(Array.fill[Byte](32)(5)))
    h.respondUnbooked(1, p, Seq(task), Seq.empty)
    m.emptyResponseStrikes.get(p.id.value) shouldBe None
    m.calls.toSeq should not contain "cooldown:innocent"
    m.calls.toSeq should not contain "budget-down:innocent"
  }

  it should "under the Path scheme write a served node at the task's path and at every alias path" taggedAs UnitTest in {
    val pns = new PathNodeStorage(EphemDataSource())
    val h = new Harness(scheme = StorageScheme.Path, pathStore = Some(pns))
    val m = h.module
    val node = leaf(5, 6)("shared")
    val account = HealingEntry(Seq(compact(1, 2)), hashOf(node))
    val alias = Seq(compact(3, 4))
    val owner = ByteString(Array.fill[Byte](32)(0x11))
    val storageAlias = Seq(owner, compact(7))

    // spec 022 D2: each path is its own request; the second and third join the first one's fetch of the same hash.
    m.queueNodes(Seq((account.pathset, account.hash), (alias, account.hash), (storageAlias, account.hash)))
    m.pendingTasks.toSeq shouldBe Seq(account)
    m.healRequests.waitingOn(account.hash).map(_.pathset) shouldBe Seq(account.pathset, alias, storageAlias)

    m.pendingTasks.clear()
    h.respond(1, peer("p1"), Seq(account), Seq(bytes(node)))

    m.rawNodeBuffer shouldBe empty
    pns.readAccountNode(Array[Byte](1, 2)).map(_.toSeq) shouldBe Some(node.encode.toSeq)
    pns.readAccountNode(Array[Byte](3, 4)).map(_.toSeq) shouldBe Some(node.encode.toSeq)
    pns.readStorageNode(owner, Array[Byte](7)).map(_.toSeq) shouldBe Some(node.encode.toSeq)
    m.healRequests.isEmpty shouldBe true
  }

  it should "queue a healed branch's absent children at their own paths, and persist them as frontier entries" taggedAs UnitTest in {
    val h = new Harness()
    val m = h.module
    val present = leaf(1)("present")
    val absent = leaf(2)("absent")
    val pending = leaf(3)("pending")
    m.mptStorage.putNode(present)
    // A child already requested (queued or in flight): it is not queued again; the branch gains an edge to it.
    m.healRequests.add(m.healRequestFor(HealingEntry(Seq(compact(6, 9)), hashOf(pending))))
    val children = Array.fill[MptNode](16)(NullNode)
    children(0) = HashNode(present.hash)
    children(4) = HashNode(absent.hash)
    children(9) = HashNode(pending.hash)
    val branch = BranchNode(children, None)
    val task = HealingEntry(Seq(compact(6)), hashOf(branch))

    h.respond(1, peer("p1"), Seq(task), Seq(bytes(branch)))

    val child = HealingEntry(Seq(compact(6, 4)), hashOf(absent))
    m.pendingTasks.toSeq shouldBe Seq(child)
    m.healRequests.isFetching(child.hash) shouldBe true
    m.persisted.toSeq shouldBe Seq(Seq(child))
    // spec 022 D2 (Hash): one edge from the branch to each missing child, the pending one included.
    m.heldNodes.get(task.hash).map(_.outstanding) shouldBe Some(2) // held: two children missing
    m.healRequests.parentsOf(hashOf(pending)) shouldBe Seq(task.hash)
  }

  it should "seed a healed account leaf's absent storage root, and report its absent code only with the check on" taggedAs UnitTest in {
    val code = ByteString("contract")
    val codeHash = CodeHash(kec256(code))
    val storageRoot = ByteString(Array.fill[Byte](32)(0x22))
    val accountLeaf = LeafNode(
      ByteString(Array.fill[Byte](64)(1)),
      ByteString(Account(storageRoot = TrieRoot(storageRoot), codeHash = codeHash).toBytes)
    )
    val task = HealingEntry(Seq(emptyPath), hashOf(accountLeaf))
    val accountHash = ByteString(Array.fill[Byte](32)(0x11))

    val on = new Harness(codeCheck = true, codeStore = Some(new EvmCodeStorage(EphemDataSource())))
    on.respond(1, peer("p1"), Seq(task), Seq(bytes(accountLeaf)))
    on.module.pendingTasks.toSeq shouldBe Seq(HealingEntry(Seq(accountHash, emptyPath), storageRoot))
    // The report goes out before the response's progress notification.
    on.controller.receiveAll() shouldBe Seq(
      SNAPSyncController.HealedCodeHashes(Seq(codeHash.value)),
      SNAPSyncController.ProgressNodesHealed(1L)
    )

    // A held code is not reported.
    val held = new EvmCodeStorage(EphemDataSource())
    held.put(codeHash.value, code).commit()
    val heldHarness = new Harness(codeCheck = true, codeStore = Some(held))
    heldHarness.respond(1, peer("p1"), Seq(task), Seq(bytes(accountLeaf)))
    heldHarness.controller.receiveAll() shouldBe Seq(SNAPSyncController.ProgressNodesHealed(1L))

    // With the check off nothing is reported and the code store is never read (the stub's `bytecode` throws).
    val off = new Harness()
    off.respond(1, peer("p1"), Seq(task), Seq(bytes(accountLeaf)))
    off.controller.receiveAll() shouldBe Seq(SNAPSyncController.ProgressNodesHealed(1L))
    off.module.pendingTasks should have size 1
  }

  it should "stage a healed node as subtree-complete only when pruning is on and every child is present and recorded" taggedAs UnitTest in {
    val child = leaf(1)("child")
    val children = Array.fill[MptNode](16)(NullNode)
    children(2) = HashNode(child.hash)
    val branch = BranchNode(children, None)
    val task = HealingEntry(Seq(compact(5)), hashOf(branch))

    // Present but not recorded: not closed.
    val unrecorded = new Harness(pruned = true)
    unrecorded.module.mptStorage.putNode(child)
    unrecorded.respond(1, peer("p1"), Seq(task), Seq(bytes(branch)))
    unrecorded.module.pendingSubtreeRecords shouldBe empty

    // Present and recorded: staged.
    val closed = new Harness(pruned = true)
    closed.module.mptStorage.putNode(child)
    closed.module.frontierStore.markSubtreeComplete(hashOf(child))
    closed.respond(1, peer("p1"), Seq(task), Seq(bytes(branch)))
    closed.module.pendingSubtreeRecords shouldBe Set(hashOf(branch))

    // A leaf closes vacuously; with pruning off nothing is staged.
    val leafTask = HealingEntry(Seq(compact(6)), hashOf(child))
    val on = new Harness(pruned = true)
    on.respond(1, peer("p1"), Seq(leafTask), Seq(bytes(child)))
    on.module.pendingSubtreeRecords shouldBe Set(hashOf(child))
    val off = new Harness(pruned = false)
    off.respond(1, peer("p1"), Seq(leafTask), Seq(bytes(child)))
    off.module.pendingSubtreeRecords shouldBe empty
  }

  it should "queue only hashes not already pending, then report the gauges" taggedAs UnitTest in {
    val h = new Harness()
    val m = h.module
    val a = ByteString(Array.fill[Byte](32)(1))
    val b = ByteString(Array.fill[Byte](32)(2))
    m.healRequests.add(m.healRequestFor(HealingEntry(Seq(compact(9)), a)))

    m.queueNodes(Seq((Seq(compact(1)), a), (Seq(compact(2)), b), (Seq(compact(3)), b)))

    m.pendingTasks.toSeq shouldBe Seq(HealingEntry(Seq(compact(2)), b))
    m.healRequests.fetchingHashes shouldBe Set(a, b)
    m.healRequests.waitingOn(b).map(_.pathset) shouldBe Seq(Seq(compact(2))) // Hash scheme: one request per hash
    m.calls.toSeq shouldBe Seq("persist", "gauges")
    m.isComplete shouldBe false
    m.pendingTasks.clear()
    m.isComplete shouldBe true
    m.activeRequests(BigInt(1)) = ActiveRequest(Seq.empty, peer("p1"), BigInt(1))
    m.isComplete shouldBe false
  }

  it should "check presence by hash under the Hash scheme and by verified path under the Path scheme" taggedAs UnitTest in {
    val node = leaf(1)("node")
    val hashed = new Harness()
    hashed.module.isNodeInStorage(hashOf(node), Seq(compact(1))) shouldBe false
    hashed.module.mptStorage.putNode(node)
    hashed.module.isNodeInStorage(hashOf(node), Seq.empty) shouldBe true

    val pathed = new Harness(scheme = StorageScheme.Path)
    pathed.module.pathReads((hashOf(node), Seq(compact(1)))) = node.encode
    pathed.module.isNodeInStorage(hashOf(node), Seq(compact(1))) shouldBe true
    pathed.module.isNodeInStorage(hashOf(node), Seq(compact(2))) shouldBe false
    pathed.module.mptStorage.putNode(node)
    pathed.module.isNodeInStorage(hashOf(node), Seq.empty) shouldBe false
  }

  // ---- spec 022 slice 1 review (beacon W2, item 6, item 7) -------------------------------------------------------

  it should "fill nothing for a dispatched task no live request vouches for, and not queue it again (W2)" taggedAs UnitTest in {
    val h = new Harness()
    val m = h.module
    val node = leaf(1, 2)("unbooked")
    val task = HealingEntry(Seq(compact(1)), hashOf(node))

    h.respondUnbooked(1, peer("p1"), Seq(task), Seq(bytes(node)))

    m.rawNodeBuffer shouldBe empty
    m.totalNodesHealed shouldBe 0
    m.completedTaskCount shouldBe 0
    m.pendingTasks shouldBe empty
    m.activeRequests shouldBe empty
    m.healRequests.isEmpty shouldBe true
  }

  /** A branch at [6] whose children sit at [6,0], [6,4] and [6,9], healed under the Path scheme into a store whose
    * write batches are recorded. `setUp` marks the children (present, stale, live) before the response.
    */
  private def healBranchUnderPath(
      setUp: (StubHealResponseProcessingState & HealResponseProcessing, Seq[HealingEntry]) => Unit
  ): (RecordingDataSource, StubHealResponseProcessingState & HealResponseProcessing, Seq[HealingEntry], ByteString) =
    val ds = new RecordingDataSource
    val h = new Harness(scheme = StorageScheme.Path, pathStore = Some(new PathNodeStorage(ds)))
    val m = h.module
    val kids = Seq(leaf(1)("c0"), leaf(2)("c4"), leaf(3)("c9"))
    val slots = Seq(0, 4, 9)
    val children = Array.fill[MptNode](16)(NullNode)
    kids.zip(slots).foreach { case (k, i) => children(i) = HashNode(k.hash) }
    val branch = BranchNode(children, None)
    val task = HealingEntry(Seq(compact(6)), hashOf(branch))
    val childEntries = kids.zip(slots).map { case (k, i) => HealingEntry(Seq(compact(6, i)), hashOf(k)) }
    setUp(m, childEntries)
    m.healRequests.add(m.healRequestFor(task))
    h.respond(1, peer("p1"), Seq(task), Seq(bytes(branch)))
    (ds, m, childEntries, accountKey(Array[Byte](6)))

  // spec 022 slice 2 (D3): the branch waits on missing children, so it is held, not written. The stale child's position
  // is deleted in the batch of the response that found it, before the child can be written there (geth's membatch
  // orders a delNode before the later write); deferring it to the branch's own commit would delete the child.
  it should "under the Path scheme delete a stale child in the batch of the response that found it, and leave a present child alone (item 7)" taggedAs UnitTest in {
    val (ds, m, kids, parentKey) = healBranchUnderPath { (m, kids) =>
      m.pathReads((kids(0).hash, kids(0).pathset)) = Array[Byte](1) // [6,0]: present (right bytes)
      m.stalePaths += kids(1).pathset // [6,4]: stale bytes of another hash
      // [6,9]: absent
    }
    ds.batches.map(_.toSet) shouldBe Seq(Set(Op(AccountNs, delete = true, accountKey(Array[Byte](6, 4)))))
    ds.batchesPutting(AccountNs, parentKey) shouldBe empty // held: two children missing
    m.pendingTasks.toSeq shouldBe Seq(kids(1), kids(2)) // the stale and the absent child are requested
  }

  it should "under the Path scheme delete a stale child that is already requested, in the batch of the response that found it (item 6)" taggedAs UnitTest in {
    val (ds, m, kids, parentKey) = healBranchUnderPath { (m, kids) =>
      m.pathReads((kids(0).hash, kids(0).pathset)) = Array[Byte](1)
      m.pathReads((kids(2).hash, kids(2).pathset)) = Array[Byte](1)
      // [6,4] is already requested (queued by a walk, say) and holds stale bytes.
      m.healRequests.add(m.healRequestFor(kids(1)))
      m.stalePaths += kids(1).pathset
    }
    ds.batches.map(_.toSet) shouldBe Seq(Set(Op(AccountNs, delete = true, accountKey(Array[Byte](6, 4)))))
    ds.batchesPutting(AccountNs, parentKey) shouldBe empty // held: [6,4] missing
    m.pendingTasks shouldBe empty // already requested: an edge, no second fetch
    m.healRequests.parentsOf(m.healRequestFor(kids(1)).key) should have size 1
  }

  it should "fill nothing from a fetch whose position now expects another node, and not queue it again (review W1)" taggedAs UnitTest in {
    val pns = new PathNodeStorage(EphemDataSource())
    val h = new Harness(scheme = StorageScheme.Path, pathStore = Some(pns))
    val m = h.module
    val oldNode = leaf(1)("old")
    val newNode = leaf(2)("new")
    val stale = HealingEntry(Seq(compact(4)), hashOf(oldNode))
    val current = HealingEntry(Seq(compact(4)), hashOf(newNode))
    m.enqueueRequest(stale) shouldBe true
    m.pendingTasks.clear() // dispatched
    m.enqueueRequest(current) shouldBe true // the same position, another node: the newer request wins, its fetch queued
    m.healRequests.isRetired(stale.hash) shouldBe true

    h.respondUnbooked(1, peer("p1"), Seq(stale), Seq(bytes(oldNode))) // the old fetch comes back with its node

    pns.readAccountNode(Array[Byte](4)) shouldBe None // never written where the newer node is expected
    m.totalNodesHealed shouldBe 0
    m.pendingTasks.toSeq shouldBe Seq(current) // only the newer fetch
  }

  // ---- spec 022 slice 2 review: a served node whose children cannot be found (eye F2, forge W1, vault note 5) ------

  /** A branch with hash children only, at `slots`. */
  private def branchOf(slots: (Int, ByteString)*): BranchNode =
    val children = Array.fill[MptNode](16)(NullNode)
    slots.foreach { case (i, h) => children(i) = HashNode(h.toArray) }
    BranchNode(children, None)

  /** The task the module queued for `hash`, taken out of the queue as a dispatch takes it. */
  private def dispatched(m: StubHealResponseProcessingState, hash: ByteString): HealingEntry =
    val task = m.pendingTasks.find(_.hash == hash).getOrElse(fail(s"no queued task for ${hash.take(4)}"))
    val rest = m.pendingTasks.toSeq.filterNot(_ eq task)
    m.pendingTasks.clear()
    m.pendingTasks ++= rest
    task

  private def undecodableChild(scheme: StorageScheme): Unit =
    val source = new RecordingDataSource
    val h =
      new Harness(scheme = scheme, pathStore = Option.when(scheme == StorageScheme.Path)(new PathNodeStorage(source)))
    val m = h.module
    val p = peer("p1")
    val undecodable = ByteString(Array.fill[Byte](40)(0x05)) // hashes right, is no node
    val gHash = kec256(undecodable)
    val parent = branchOf(0 -> gHash)
    val parentTask = HealingEntry(Seq(emptyPath), hashOf(parent))
    h.respond(1, p, Seq(parentTask), Seq(bytes(parent)))
    val parentId = HeldNodeSet.idOf(m.healRequestFor(parentTask))
    m.heldNodes.get(parentId).map(_.outstanding) shouldBe Some(1)
    val gTask = dispatched(m, gHash)

    h.respond(2, p, Seq(gTask), Seq(undecodable))

    val gRequest = m.healRequestFor(gTask)
    withClue("the node is not held: ")(m.heldNodes.contains(HeldNodeSet.idOf(gRequest)) shouldBe false)
    withClue("not buffered: ")(m.rawNodeBuffer.contains(gHash) shouldBe false)
    withClue("not written: ")(source.batchesPutting(AccountNs, accountKey(Array[Byte](0))) shouldBe empty)
    withClue("its request is satisfied: ")(m.healRequests.contains(gRequest.key) shouldBe false)
    withClue("and not fetched again (every peer serves the same bytes): ")(m.pendingTasks shouldBe empty)
    withClue("its parent still waits on it: ")(m.heldNodes.get(parentId).map(_.outstanding) shouldBe Some(1))

  it should "never hold, buffer or write a served node that hashes right but does not decode, under the Hash scheme (eye F2)" taggedAs UnitTest in {
    undecodableChild(StorageScheme.Hash)
  }

  it should "never hold, buffer or write a served node that hashes right but does not decode, under the Path scheme (eye F2)" taggedAs UnitTest in {
    undecodableChild(StorageScheme.Path)
  }

  /** P (held) waits on B; B arrives, and a storage read throws while B's children are looked up. B is fine: its request
    * goes back, with P's edge, and is fetched again; once the read works B is held and the chain commits.
    */
  private def readFailsDuringDiscovery(scheme: StorageScheme): Unit =
    val source = new RecordingDataSource
    val h =
      new Harness(scheme = scheme, pathStore = Option.when(scheme == StorageScheme.Path)(new PathNodeStorage(source)))
    val m = h.module
    val p = peer("p1")
    val c = leaf(5)("s022/review/read-failure-leaf")
    val b = branchOf(0 -> hashOf(c))
    val parent = branchOf(0 -> hashOf(b))
    val parentTask = HealingEntry(Seq(emptyPath), hashOf(parent))
    h.respond(1, p, Seq(parentTask), Seq(bytes(parent)))
    val parentId = HeldNodeSet.idOf(m.healRequestFor(parentTask))
    val bTask = dispatched(m, hashOf(b))
    val bRequest = m.healRequestFor(bTask)
    if scheme == StorageScheme.Hash then m.mptStorage.failReads = 1
    else m.failingReads += Seq(compact(0, 0))

    h.respond(2, p, Seq(bTask), Seq(bytes(b)))

    withClue("B is not held: ")(m.heldNodes.contains(HeldNodeSet.idOf(bRequest)) shouldBe false)
    withClue("B's request is back: ")(m.healRequests.contains(bRequest.key) shouldBe true)
    withClue("with P's edge: ")(m.healRequests.parentsOf(bRequest.key) shouldBe Seq(parentId))
    withClue("and B is fetched again: ")(m.pendingTasks.toSeq.map(_.hash) shouldBe Seq(hashOf(b)))
    withClue("P still waits on B: ")(m.heldNodes.get(parentId).map(_.outstanding) shouldBe Some(1))

    // The read works now: B is held, its child fetched, and the chain commits whole.
    h.respond(3, p, Seq(dispatched(m, hashOf(b))), Seq(bytes(b)))
    m.heldNodes.contains(HeldNodeSet.idOf(bRequest)) shouldBe true
    h.respond(4, p, Seq(dispatched(m, hashOf(c))), Seq(bytes(c)))
    m.heldNodes.isEmpty shouldBe true
    if scheme == StorageScheme.Hash then
      m.rawNodeBuffer.map(_._1).toSeq shouldBe Seq(hashOf(c), hashOf(b), hashOf(parent))

  it should "put a served node's request back, with its parent edges, when a storage read fails while its children are looked up, under the Hash scheme (forge W1)" taggedAs UnitTest in {
    readFailsDuringDiscovery(StorageScheme.Hash)
  }

  it should "put a served node's request back, with its parent edges, when a storage read fails while its children are looked up, under the Path scheme (forge W1)" taggedAs UnitTest in {
    readFailsDuringDiscovery(StorageScheme.Path)
  }

  // ---- spec 022 review hunter W1-r2, forge W1 (Path aliases), forge F2 (no retry cap) ------------------------------

  /** Two accounts with one storage trie (`twinStorageTrie`): S waits at two positions, each under its held account
    * leaf, and one fetch of S is in flight. S arrives, and a storage read fails while the children of the alias at
    * `failAt` are looked up. Every alias whose read failed is put back with its parent's edge, and S must be fetched
    * once more: the fetch that came back is spent, and a put-back alias can only join a fetch that exists.
    */
  private def aliasReadFails(failAt: Seq[String]): Unit =
    val source = new RecordingDataSource
    val h = new Harness(scheme = StorageScheme.Path, pathStore = Some(new PathNodeStorage(source)))
    val m = h.module
    val p = peer("p1")
    val trie = HealRequestFixtures.twinStorageTrie(1)
    def task(label: String): HealingEntry = HealingEntry(trie(label).pathset, trie(label).hash)
    def request(label: String): HealRequest = m.healRequestFor(task(label))
    h.respond(1, p, Seq(task("root")), Seq(trie.root.encoded))
    h.respond(
      2,
      p,
      Seq(dispatched(m, trie("acct0").hash), dispatched(m, trie("acct1").hash)),
      Seq(trie("acct0").encoded, trie("acct1").encoded)
    )
    // S is requested at both positions and fetched once.
    m.healRequests.waitingOn(trie("s0").hash).map(_.key) shouldBe Seq(request("s0").key, request("s1").key)
    val sFetch = dispatched(m, trie("s0").hash)
    m.pendingTasks shouldBe empty
    failAt.foreach(alias => m.failingReads += trie(s"${alias}A").pathset)

    h.respond(3, p, Seq(sFetch), Seq(trie("s0").encoded))

    withClue("S is fetched again, once: ")(m.pendingTasks.toSeq.count(_.hash == trie("s0").hash) shouldBe 1)
    failAt.foreach { alias =>
      val acct = if alias == "s0" then "acct0" else "acct1"
      withClue(s"$alias is waiting again, with its account's edge: ") {
        m.healRequests.parentsOf(request(alias).key) shouldBe Seq(HeldNodeSet.idOf(request(acct)))
      }
    }
    // S is served again; then its leaves, once each for both positions: the whole trie commits, both accounts too.
    h.respond(4, p, Seq(dispatched(m, trie("s0").hash)), Seq(trie("s0").encoded))
    h.respond(
      5,
      p,
      Seq(dispatched(m, trie("s0A").hash), dispatched(m, trie("s0B").hash)),
      Seq(trie("s0A").encoded, trie("s0B").encoded)
    )
    withClue("nothing waits on a child any more: ")(m.heldNodes.isEmpty shouldBe true)
    Seq("acct0", "acct1").foreach { acct =>
      withClue(s"$acct written: ")(source.batchesPutting(AccountNs, accountKey(trie(acct).nibbles)) should not be empty)
    }

  it should "fetch a node again when a read fails for one of its Path aliases, so the account waiting there commits (hunter W1-r2, forge W1)" taggedAs UnitTest in {
    aliasReadFails(Seq("s0"))
  }

  it should "fetch a node again, once, when a read fails for every one of its Path aliases (hunter W1-r2, forge W1)" taggedAs UnitTest in {
    aliasReadFails(Seq("s0", "s1"))
  }

  it should "stop fetching a node whose children's read keeps failing after three tries, and never count a put-back node as healed (forge F2, hunter N1)" taggedAs UnitTest in {
    val h = new Harness(scheme = StorageScheme.Hash)
    val m = h.module
    val p = peer("p1")
    val c = leaf(5)("s022/review/unreadable-child")
    val b = branchOf(0 -> hashOf(c))
    val parent = branchOf(0 -> hashOf(b))
    val parentTask = HealingEntry(Seq(emptyPath), hashOf(parent))
    h.respond(1, p, Seq(parentTask), Seq(bytes(parent)))
    val parentId = HeldNodeSet.idOf(m.healRequestFor(parentTask))
    m.totalNodesHealed shouldBe 1
    m.mptStorage.failReads = 100 // every read of B's child fails (a corrupt stored child, say)

    // The cap (TrieNodeHealingCoordinator's three consecutive read failures per node): B is fetched again after the
    // first two failures, and given up on at the third.
    (1 to 3).foreach { attempt =>
      withClue(s"before attempt $attempt, B is queued: ")(m.pendingTasks.toSeq.map(_.hash) shouldBe Seq(hashOf(b)))
      h.respond(1 + attempt, p, Seq(dispatched(m, hashOf(b))), Seq(bytes(b)))
    }

    withClue("B is not fetched a fourth time: ")(m.pendingTasks shouldBe empty)
    val bRequest = m.healRequestFor(HealingEntry(Seq(compact(0)), hashOf(b)))
    withClue("nor waiting: ")(m.healRequests.contains(bRequest.key) shouldBe false)
    withClue("P stays held, for the orphan guard: ")(m.heldNodes.get(parentId).map(_.outstanding) shouldBe Some(1))
    withClue("no put-back node counts as healed, so stagnation can fire: ")(m.totalNodesHealed shouldBe 1)
    withClue("the give-up goes to the controller's stagnation path, once, without stopping dispatch: ") {
      h.controller.receiveAll().collect { case s: SNAPSyncController.HealingStagnated => s } should have size 1
      m.giveUpEscalated shouldBe true
      m.pivotRefreshRequested shouldBe false
    }
    // B found again (a walk entry, say) and given up on again while that escalation is pending: not sent twice.
    h.respond(5, p, Seq(HealingEntry(Seq(compact(0)), hashOf(b))), Seq(bytes(b)))
    m.pendingTasks shouldBe empty
    h.controller.receiveAll().collect { case s: SNAPSyncController.HealingStagnated => s } shouldBe empty
  }

  it should "make the next completion check walk after a give-up, whatever an earlier verification pass found (forge F6)" taggedAs UnitTest in {
    val h = new Harness(scheme = StorageScheme.Hash)
    val m = h.module
    val p = peer("p1")
    val c = leaf(5)("s022/review/unreadable-child-after-clean-pass")
    val b = branchOf(0 -> hashOf(c))
    val bTask = HealingEntry(Seq(compact(0)), hashOf(b))
    // A clean pass ran earlier; B came in afterwards (a controller walk's QueueMissingNodes, say).
    m.verificationPassComplete = true
    m.queueNodes(Seq(bTask.pathset -> bTask.hash))
    m.mptStorage.failReads = 100
    (1 to 3).foreach(attempt => h.respond(attempt, p, Seq(dispatched(m, hashOf(b))), Seq(bytes(b))))
    withClue("B is given up on: ")(m.healRequests.contains(m.healRequestFor(bTask).key) shouldBe false)
    withClue("the earlier pass no longer vouches for completion: ")(m.verificationPassComplete shouldBe false)
  }

  /** B, under a parent, fetched and filled three times while every read of its child fails: given up on at the third.
    */
  private def giveUpB(h: Harness, label: String): ByteString =
    val m = h.module
    val p = peer("p1")
    val c = leaf(5)(s"s022/review/$label")
    val b = branchOf(0 -> hashOf(c))
    val parent = branchOf(0 -> hashOf(b))
    h.respond(1, p, Seq(HealingEntry(Seq(emptyPath), hashOf(parent))), Seq(bytes(parent)))
    m.mptStorage.failReads = 100
    (1 to 3).foreach(attempt => h.respond(1 + attempt, p, Seq(dispatched(m, hashOf(b))), Seq(bytes(b))))
    hashOf(b)

  it should "not escalate a give-up while the pulse's own escalation waits for the controller (forge F2)" taggedAs UnitTest in {
    val h = new Harness(scheme = StorageScheme.Hash)
    val m = h.module
    m.pivotRefreshRequested = true
    giveUpB(h, "give-up-under-pulse-escalation")
    h.controller.receiveAll().collect { case s: SNAPSyncController.HealingStagnated => s } shouldBe empty
    m.giveUpEscalated shouldBe false
  }

  it should "drop the attempt counter of a node it gives up on, and keep it while the node is fetched again (hunter-r3 N2, forge N13)" taggedAs UnitTest in {
    val h = new Harness(scheme = StorageScheme.Hash)
    val m = h.module
    val p = peer("p1")
    val c = leaf(5)("s022/review/attempts-of-given-up")
    val b = branchOf(0 -> hashOf(c))
    val parent = branchOf(0 -> hashOf(b))
    h.respond(1, p, Seq(HealingEntry(Seq(emptyPath), hashOf(parent))), Seq(bytes(parent)))
    m.healAttempts(hashOf(b)) = 2 // B went unserved twice before
    m.mptStorage.failReads = 100
    (1 to 2).foreach(attempt => h.respond(1 + attempt, p, Seq(dispatched(m, hashOf(b))), Seq(bytes(b))))
    withClue("put back, so still live: ")(m.healAttempts.get(hashOf(b)) shouldBe Some(2))
    h.respond(4, p, Seq(dispatched(m, hashOf(b))), Seq(bytes(b)))
    withClue("given up on, so no longer live: ")(m.healAttempts.contains(hashOf(b)) shouldBe false)
  }

  // ---- spec 022 T038c ----------------------------------------------------------------------------------------------

  it should "refuse a parentless request for a position held for another node by a parent's child (beacon N2)" taggedAs UnitTest in {
    val h = new Harness(scheme = StorageScheme.Path, pathStore = Some(new PathNodeStorage(EphemDataSource())))
    val m = h.module
    val pathset = Seq(ByteString(HexPrefix.encode(Array[Byte](1), isLeaf = false)))
    val heldHash = ByteString(Array.fill[Byte](32)(0x41))
    m.heldNodes.hold(HealRequest.atPosition(pathset, heldHash), ByteString("node"), 1, Seq(ByteString("parent")))
    m.enqueueRequest(HealingEntry(pathset, ByteString(Array.fill[Byte](32)(0x42)))) shouldBe false
    m.pendingTasks.isEmpty shouldBe true
    m.healRequests.isEmpty shouldBe true
  }

  it should "take a parentless request for a position held without parents: nothing else waits on that node" taggedAs UnitTest in {
    val h = new Harness(scheme = StorageScheme.Path, pathStore = Some(new PathNodeStorage(EphemDataSource())))
    val m = h.module
    val pathset = Seq(ByteString(HexPrefix.encode(Array[Byte](1), isLeaf = false)))
    m.heldNodes.hold(
      HealRequest.atPosition(pathset, ByteString(Array.fill[Byte](32)(0x41))),
      ByteString("node"),
      1,
      Nil
    )
    m.enqueueRequest(HealingEntry(pathset, ByteString(Array.fill[Byte](32)(0x42)))) shouldBe true
  }

  it should "forget a clean pass when a node that does not decode is dropped (hunter N2)" taggedAs UnitTest in {
    val h = new Harness()
    val m = h.module
    val junk = ByteString(Array.fill[Byte](40)(0xff.toByte))
    val task = HealingEntry(Seq(ByteString(HexPrefix.encode(Array[Byte](1), isLeaf = false))), kec256(junk))
    m.verificationPassComplete = true
    h.respond(1, peer("p1"), Seq(task), Seq(junk))
    m.verificationPassComplete shouldBe false
    m.runningWalkInvalidated shouldBe 1
  }
