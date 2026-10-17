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
import com.chipprbots.ethereum.blockchain.sync.snap.controller.StateIngest
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
  * `HealDispatchApi`, `PathVerifiedRlpApi`). State is plain fields; every interface call is recorded. `self` and the
  * controller are test inboxes; the request tracker is real (a response completes its request). `stateIngest` hands out
  * `pathNodes`, `healingFrontier` and `bytecode` (the last only when a code store is given: with the healed-code check
  * off the module must never read it).
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
    with PathVerifiedRlpApi:

  // HealResponseProcessingState
  var stateRoot: ByteString = ByteString(Array.fill[Byte](32)(7))
  var serveRoot: ByteString = stateRoot
  val pendingTasks: mutable.ArrayDeque[HealingEntry] = mutable.ArrayDeque.empty
  val pendingHashSet: mutable.Set[ByteString] = mutable.Set.empty
  val pathAliases: mutable.Map[ByteString, mutable.LinkedHashMap[Seq[ByteString], HealingEntry]] = mutable.Map.empty
  val activeRequests: mutable.Map[BigInt, ActiveRequest] = mutable.Map.empty
  val rawNodeBuffer: mutable.ArrayBuffer[(ByteString, Array[Byte])] = mutable.ArrayBuffer.empty
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

  // TrieNodeHealingEnv (requestTracker, snapSyncController, storageScheme, healedCodeCheck, scopedHealMaxPaths and
  // decoupledHealServeRoot are constructor vals)
  val log: org.slf4j.Logger = org.slf4j.LoggerFactory.getLogger(classOf[HealResponseProcessingSpec])
  val asyncLog: org.slf4j.Logger = log
  val selfInbox: TestInbox[TrieNodeHealingCoordinator.Command] = TestInbox[TrieNodeHealingCoordinator.Command]()
  val self: ActorRef[TrieNodeHealingCoordinator.Command] = selfInbox.ref
  val mptStorage: TestMptStorage = new TestMptStorage()
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

  private def notReached(member: String): Nothing =
    throw new UnsupportedOperationException(s"HealResponseProcessing read $member")

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

    /** Put `tasks` in flight to `p` as request `id` and answer it with `nodes`. */
    def respond(id: Int, p: Peer, tasks: Seq[HealingEntry], nodes: Seq[ByteString]): Unit =
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

    m.rawNodeBuffer.map(_._1) shouldBe Seq(hashOf(served))
    m.rawNodeBuffer.head._2.toSeq shouldBe served.encode.toSeq
    m.totalNodesHealed shouldBe 1
    m.completedTaskCount shouldBe 1
    m.totalBytesReceived shouldBe served.encode.length.toLong
    m.activeRequests shouldBe empty
    m.pendingTasks.toSeq shouldBe Seq(skipped)
    m.pendingHashSet shouldBe Set(skipped.hash)
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

  it should "under the Path scheme write a served node at the task's path and at every alias path" taggedAs UnitTest in {
    val pns = new PathNodeStorage(EphemDataSource())
    val h = new Harness(scheme = StorageScheme.Path, pathStore = Some(pns))
    val m = h.module
    val node = leaf(5, 6)("shared")
    val account = HealingEntry(Seq(compact(1, 2)), hashOf(node))
    val alias = Seq(compact(3, 4))
    val owner = ByteString(Array.fill[Byte](32)(0x11))
    val storageAlias = Seq(owner, compact(7))

    // The second and third paths of the same hash become aliases of the queued task.
    m.queueNodes(Seq((account.pathset, account.hash), (alias, account.hash), (storageAlias, account.hash)))
    m.pendingTasks.toSeq shouldBe Seq(account)
    m.pathAliases(account.hash).keys.toSeq shouldBe Seq(alias, storageAlias)

    m.pendingTasks.clear()
    h.respond(1, peer("p1"), Seq(account), Seq(bytes(node)))

    m.rawNodeBuffer shouldBe empty
    pns.readAccountNode(Array[Byte](1, 2)).map(_.toSeq) shouldBe Some(node.encode.toSeq)
    pns.readAccountNode(Array[Byte](3, 4)).map(_.toSeq) shouldBe Some(node.encode.toSeq)
    pns.readStorageNode(owner, Array[Byte](7)).map(_.toSeq) shouldBe Some(node.encode.toSeq)
    m.pathAliases shouldBe empty
  }

  it should "queue a healed branch's absent children at their own paths, and persist them as frontier entries" taggedAs UnitTest in {
    val h = new Harness()
    val m = h.module
    val present = leaf(1)("present")
    val absent = leaf(2)("absent")
    val pending = leaf(3)("pending")
    m.mptStorage.putNode(present)
    m.pendingHashSet += hashOf(pending)
    val children = Array.fill[MptNode](16)(NullNode)
    children(0) = HashNode(present.hash)
    children(4) = HashNode(absent.hash)
    children(9) = HashNode(pending.hash)
    val branch = BranchNode(children, None)
    val task = HealingEntry(Seq(compact(6)), hashOf(branch))

    h.respond(1, peer("p1"), Seq(task), Seq(bytes(branch)))

    val child = HealingEntry(Seq(compact(6, 4)), hashOf(absent))
    m.pendingTasks.toSeq shouldBe Seq(child)
    m.pendingHashSet should contain(child.hash)
    m.persisted.toSeq shouldBe Seq(Seq(child))
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
    m.pendingHashSet += a

    m.queueNodes(Seq((Seq(compact(1)), a), (Seq(compact(2)), b), (Seq(compact(3)), b)))

    m.pendingTasks.toSeq shouldBe Seq(HealingEntry(Seq(compact(2)), b))
    m.pendingHashSet shouldBe Set(a, b)
    m.pathAliases shouldBe empty // Hash scheme: no alias bookkeeping
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
