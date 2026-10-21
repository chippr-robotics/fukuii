package com.chipprbots.ethereum.blockchain.sync.snap.actors.storage

import java.net.InetSocketAddress

import org.apache.pekko.actor.testkit.typed.scaladsl.TestInbox
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.scaladsl.ActorContext
import org.apache.pekko.util.ByteString

import scala.collection.mutable
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.snap.SNAPRequestTracker
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController
import com.chipprbots.ethereum.blockchain.sync.snap.SnapHashTrie
import com.chipprbots.ethereum.blockchain.sync.snap.SnapIntakeBudget
import com.chipprbots.ethereum.blockchain.sync.snap.SnapTrie
import com.chipprbots.ethereum.blockchain.sync.snap.StorageScheme
import com.chipprbots.ethereum.blockchain.sync.snap.StorageTask
import com.chipprbots.ethereum.blockchain.sync.snap.actors.StorageRangeCoordinator
import com.chipprbots.ethereum.blockchain.sync.snap.actors.StorageRangeCoordinator.PendingResponse
import com.chipprbots.ethereum.blockchain.sync.snap.actors.StorageRangeCoordinator.PrebuiltStorageTrie
import com.chipprbots.ethereum.blockchain.sync.snap.actors.StorageRangeCoordinator.StaleRootFailure
import com.chipprbots.ethereum.blockchain.sync.snap.actors.StorageRangeCoordinator.StorageCheckCompletion
import com.chipprbots.ethereum.blockchain.sync.snap.actors.StorageRangeCoordinator.StorageResponseProcessed
import com.chipprbots.ethereum.blockchain.sync.snap.actors.StorageRangeCoordinator.StorageTaskKey
import com.chipprbots.ethereum.blockchain.sync.snap.actors.StorageRangeCoordinator.VerifiedStorageResponse
import com.chipprbots.ethereum.blockchain.sync.snap.controller.StateIngest
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.db.storage.SnapSyncProgressStorage
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.PeerActor
import com.chipprbots.ethereum.network.PeerId
import com.chipprbots.ethereum.network.p2p.messages.SNAP.StorageRanges
import com.chipprbots.ethereum.testing.Tags.UnitTest

/** Everything `StorageResponseProcessing` needs, as a stub (spec 016 FR-016 (b), C-S4): its
  * `StorageResponseProcessingState`, the coordinator's `StorageRangeEnv` and the five Apis it calls in the peer
  * dispatch, the ordered apply and the done markers. State is plain fields; every Api call is recorded in `calls`, in
  * order, and does nothing else. The verification jobs run on `processingEc` (`ExecutionContext.parasitic` by default),
  * so a job's `StorageResponseProcessed` reaches `self` (a `TestInbox`) before `submitProcessingJobs` returns. The
  * core's `StorageResponseProcessed` arm is not here: a case that needs a job's result applied sets it on the job, as
  * that arm does.
  */
private[actors] class StubStorageResponseProcessingState(
    val self: ActorRef[StorageRangeCoordinator.Command],
    val processing: StorageRangeCoordinator.ProcessingSettings,
    val processingEc: ExecutionContext,
    val processingPool: Option[java.util.concurrent.ThreadPoolExecutor]
) extends StorageResponseProcessingState
    with StorageRangeEnv
    with StorageResponsePeerApi
    with StoragePeerFeedbackApi
    with StorageChunkApplyApi
    with StorageTrieFlushApi
    with StorageCompletedTaskApi:

  // StorageResponseProcessingState
  var stateRoot: ByteString = ByteString(Array.fill[Byte](32)(7))
  val tasks: mutable.Queue[StorageTask] = mutable.Queue.empty
  val pendingTaskKeys: mutable.Set[(ByteString, ByteString)] = mutable.Set.empty
  val activeTasks: mutable.Map[BigInt, (Peer, Seq[StorageTask], BigInt)] = mutable.Map.empty
  val accountSubtaskCounters: mutable.Map[ByteString, (Int, Int)] = mutable.Map.empty
  var completedAccountCount: Long = 0L
  val statelessPeers: mutable.Set[String] = mutable.Set.empty
  var consecutiveUnproductiveRefreshes: Int = 0
  var lastDispatchOrResponseMs: Long = 0L
  val emptyResponsesByTask: mutable.HashMap[StorageTaskKey, Int] = mutable.HashMap.empty
  val staleRootFailuresByAccount: mutable.Map[ByteString, Vector[StaleRootFailure]] = mutable.Map.empty
  val abandonedAccounts: mutable.Set[ByteString] = mutable.Set.empty
  var staleRootFailureEvents: Long = 0L
  val pendingAccountTries: mutable.Map[ByteString, SnapTrie] = mutable.Map.empty
  val pendingResponses: mutable.Queue[PendingResponse] = mutable.Queue.empty
  var pendingResponseBytes: Long = 0L
  var runningProcessingJobs: Int = 0
  var processingGeneration: Long = 0L
  var trieWriteNanos: Long = 0L

  // StorageRangeEnv (self and processing are constructor vals)
  val log: org.slf4j.Logger = org.slf4j.LoggerFactory.getLogger(classOf[StubStorageResponseProcessingState])
  def context: ActorContext[StorageRangeCoordinator.Command] = notReached("context")
  def networkPeerManager: ActorRef[NetworkPeerManagerActor.Command] = notReached("networkPeerManager")
  def requestTracker: SNAPRequestTracker = notReached("requestTracker")
  def snapSyncController: ActorRef[SNAPSyncController.Command] = notReached("snapSyncController")
  val maxAccountsPerBatch: Int = 4
  val maxInFlightRequests: Int = 10
  val requestTimeout: FiniteDuration = 30.seconds
  val configInitialResponseBytes: Int = 1048576
  val configMinResponseBytes: Int = 131072
  var deferredMerkleization: Boolean = true
  val maxConcurrentStorageAccounts: Int = 256
  val intakeBudget: Option[SnapIntakeBudget] = None
  def mptStorage: MptStorage = notReached("mptStorage")
  val snapProgressStorage: Option[SnapSyncProgressStorage] = None
  val storageScheme: StorageScheme = StorageScheme.Hash
  def stateIngest: StateIngest = notReached("stateIngest")
  val flatBatchEntryThreshold: Int = 1000

  // The Apis (recorded)
  val calls: mutable.ArrayBuffer[String] = mutable.ArrayBuffer.empty
  val applied: mutable.ArrayBuffer[
    (StorageTask, Seq[(ByteString, ByteString)], Seq[ByteString], Option[PrebuiltStorageTrie])
  ] = mutable.ArrayBuffer.empty

  def recordPeerAnswered(peerId: String): Unit = calls += s"recordPeerAnswered($peerId)"
  def recordPeerSuccess(peerId: String): Unit = calls += s"recordPeerSuccess($peerId)"
  def markPeerStateless(peer: Peer): Unit = calls += s"markPeerStateless(${peer.id.value})"
  def batchSizeFor(peer: Peer): Int = 1
  def reduceBatchSize(peer: Peer): Unit = calls += s"reduceBatchSize(${peer.id.value})"
  def maybeIncreaseBatchSize(peer: Peer, servedCount: Int, requestedCount: Int): Unit =
    calls += s"maybeIncreaseBatchSize(${peer.id.value},$servedCount,$requestedCount)"
  def adjustResponseBytesOnSuccess(peer: Peer, requested: BigInt, received: BigInt): Unit =
    calls += s"adjustResponseBytesOnSuccess(${peer.id.value})"
  def dispatchIfPossible(peer: Peer): Unit = calls += s"dispatchIfPossible(${peer.id.value})"
  def recordPeerCooldown(peer: Peer, reason: String): Unit = calls += s"recordPeerCooldown(${peer.id.value})"
  def adjustResponseBytesOnFailure(peer: Peer, reason: String): Unit =
    calls += s"adjustResponseBytesOnFailure(${peer.id.value})"
  def applyOrderedStorageChunk(
      peer: Peer,
      task: StorageTask,
      accountSlots: Seq[(ByteString, ByteString)],
      proofForThisTask: Seq[ByteString],
      prebuilt: Option[PrebuiltStorageTrie] = None
  ): Unit =
    calls += s"applyOrderedStorageChunk(${task.accountString})"
    applied += ((task, accountSlots, proofForThisTask, prebuilt))
  def resetAccountTrie(accountHash: ByteString): Unit =
    calls += s"resetAccountTrie(${accountHash.take(4).toArray.map("%02x".format(_)).mkString})"
  def flushTrieNodeWrites(): Unit = calls += "flushTrieNodeWrites"
  def recordCompletedTask(task: StorageTask): Unit = calls += s"recordCompletedTask(${task.accountString})"

  private def notReached(member: String): Nothing =
    throw new UnsupportedOperationException(s"StorageResponseProcessing read $member")

/** Spec 016 C-S4 stub test (FR-016 (b), T069): `StorageResponseProcessing` mixed into a stub of its state interface,
  * the coordinator environment and the five Apis. The cases cover #1533's pipeline (job submission and its cap, the
  * head-only drain in arrival order, the generation bump), the response branches (served with the worker's result or
  * verified inline, the stale-local-root give-up, empty strikes and the skip, proof-of-absence), the subtask split and
  * the per-account subtask count, and the pool shutdown.
  */
class StorageResponseProcessingSpec extends AnyFlatSpec with Matchers:

  private val Min: ByteString = ByteString(Array.fill[Byte](32)(0))
  private val Max: ByteString = ByteString(Array.fill[Byte](32)(-1))

  private val offActor = StorageRangeCoordinator.ProcessingSettings(
    offActor = true,
    threads = 1,
    maxInFlightJobs = 1,
    maxPendingBytes = Long.MaxValue
  )

  private def peer(id: String): Peer =
    Peer(PeerId(id), new InetSocketAddress("127.0.0.1", 30303), TestInbox[PeerActor.Command]().ref, false)

  /** `n` slots in ascending key order. */
  private def slotsFor(seed: String, n: Int): Seq[(ByteString, ByteString)] =
    (0 until n)
      .map(i => kec256(ByteString(s"$seed-$i")) -> ByteString(s"value-$seed-$i"))
      .sortWith((a, b) => java.util.Arrays.compareUnsigned(a._1.toArray, b._1.toArray) < 0)

  private def rootOf(slots: Seq[(ByteString, ByteString)]): ByteString =
    val trie = new SnapHashTrie(_ => ())
    slots.foreach { case (k, v) => trie.update(k.toArray, v.toArray) }
    trie.commit()

  private def wholeTask(seed: String, slots: Seq[(ByteString, ByteString)]): StorageTask =
    StorageTask(kec256(ByteString(seed)), rootOf(slots), Min, Max)

  private def module(
      self: TestInbox[StorageRangeCoordinator.Command],
      processing: StorageRangeCoordinator.ProcessingSettings = StorageRangeCoordinator.ProcessingSettings.Inline,
      pool: Option[java.util.concurrent.ThreadPoolExecutor] = None
  ): StubStorageResponseProcessingState & StorageResponseProcessing =
    new StubStorageResponseProcessingState(self.ref, processing, ExecutionContext.parasitic, pool)
      with StorageResponseProcessing

  private def job(
      seq: Long,
      from: Peer,
      tasks: Seq[StorageTask],
      response: StorageRanges,
      generation: Long = 0L,
      ready: Boolean = false
  ): PendingResponse =
    val j = new PendingResponse(seq, generation, from, tasks, BigInt(1024), response, 100L, System.nanoTime())
    j.ready = ready
    j

  "StorageResponseProcessing" should "submit at most maxInFlightJobs jobs, oldest first, and report each through self" taggedAs UnitTest in {
    val self = TestInbox[StorageRangeCoordinator.Command]()
    val m = module(self, processing = offActor)
    val slotsA = slotsFor("a", 4)
    val slotsB = slotsFor("b", 4)
    m.pendingResponses.enqueue(
      job(0, peer("p1"), Seq(wholeTask("a", slotsA)), StorageRanges(BigInt(1), Seq(slotsA), Nil))
    )
    m.pendingResponses.enqueue(
      job(1, peer("p1"), Seq(wholeTask("b", slotsB)), StorageRanges(BigInt(2), Seq(slotsB), Nil))
    )

    m.submitProcessingJobs()

    m.runningProcessingJobs shouldBe 1
    m.pendingResponses.map(_.submitted).toList shouldBe List(true, false)
    val messages = self.receiveAll()
    messages should have size 1
    messages.head match
      case StorageResponseProcessed(seq, generation, outcome) =>
        seq shouldBe 0L
        generation shouldBe 0L
        outcome.get.verifications shouldBe Vector(Some(Right(())))
        outcome.get.prebuilt shouldBe empty // deferredMerkleization: no prebuild
      case other => fail(s"unexpected $other")
  }

  it should "skip abandoned accounts in the worker and prebuild whole accounts when merkleization is not deferred" taggedAs UnitTest in {
    val self = TestInbox[StorageRangeCoordinator.Command]()
    val m = module(self, processing = offActor.copy(maxInFlightJobs = 4))
    m.deferredMerkleization = false
    val slotsA = slotsFor("a", 3)
    val slotsB = slotsFor("b", 3)
    val taskA = wholeTask("a", slotsA)
    val taskB = wholeTask("b", slotsB)
    m.abandonedAccounts += taskB.accountHash
    m.pendingResponses.enqueue(
      job(0, peer("p1"), Seq(taskA, taskB), StorageRanges(BigInt(1), Seq(slotsA, slotsB), Nil))
    )

    m.submitProcessingJobs()

    self.receiveAll() match
      case Seq(StorageResponseProcessed(_, _, outcome)) =>
        outcome.get.verifications shouldBe Vector(Some(Right(())), None)
        outcome.get.prebuilt.keySet shouldBe Set(0)
        outcome.get.prebuilt(0).root shouldBe taskA.storageRoot
      case other => fail(s"unexpected $other")
  }

  it should "apply ready responses strictly from the head, in arrival order" taggedAs UnitTest in {
    val self = TestInbox[StorageRangeCoordinator.Command]()
    val m = module(self, processing = offActor)
    val slotsA = slotsFor("a", 2)
    val slotsB = slotsFor("b", 2)
    val taskA = wholeTask("a", slotsA)
    val taskB = wholeTask("b", slotsB)
    val head = job(0, peer("p1"), Seq(taskA), StorageRanges(BigInt(1), Seq(slotsA), Nil))
    val second = job(1, peer("p2"), Seq(taskB), StorageRanges(BigInt(2), Seq(slotsB), Nil), ready = true)
    m.pendingResponses.enqueueAll(Seq(head, second))
    m.pendingResponseBytes = 200L

    m.drainProcessedResponses()
    m.applied shouldBe empty // the ready second response waits behind the unready head
    m.pendingResponses should have size 2

    head.verified = Some(VerifiedStorageResponse(Vector(Some(Right(()))), Map.empty, 0L))
    head.ready = true
    m.drainProcessedResponses()

    m.applied.map(_._1.accountHash).toList shouldBe List(taskA.accountHash, taskB.accountHash)
    m.pendingResponses shouldBe empty
    m.pendingResponseBytes shouldBe 0L
    m.calls.count(_ == "flushTrieNodeWrites") shouldBe 2 // one flush per applied response
  }

  it should "not dispatch again to a peer that disconnected after answering" taggedAs UnitTest in {
    val self = TestInbox[StorageRangeCoordinator.Command]()
    val m = module(self)
    val slots = slotsFor("a", 2)
    val gone = job(0, peer("gone"), Seq(wholeTask("a", slots)), StorageRanges(BigInt(1), Seq(slots), Nil), ready = true)
    gone.peerGone = true
    m.pendingResponses.enqueue(gone)

    m.drainProcessedResponses()

    m.applied should have size 1
    m.calls should not contain "dispatchIfPossible(gone)"
  }

  it should "drop every unapplied response and start a new generation" taggedAs UnitTest in {
    val self = TestInbox[StorageRangeCoordinator.Command]()
    val m = module(self, processing = offActor)
    val slots = slotsFor("a", 2)
    val j0 = job(0, peer("p1"), Seq(wholeTask("a", slots)), StorageRanges(BigInt(1), Seq(slots), Nil))
    val j1 = job(1, peer("p1"), Seq(wholeTask("b", slots)), StorageRanges(BigInt(2), Seq(slots), Nil))
    m.pendingResponses.enqueueAll(Seq(j0, j1))
    m.pendingResponseBytes = 200L

    val dropped = m.abandonPendingResponses()

    dropped.map(_.seq) shouldBe Seq(0L, 1L)
    m.pendingResponses shouldBe empty
    m.pendingResponseBytes shouldBe 0L
    m.processingGeneration shouldBe 1L
  }

  it should "use the worker's verification and prebuilt trie, or verify inline when there is none" taggedAs UnitTest in {
    val self = TestInbox[StorageRangeCoordinator.Command]()
    val m = module(self)
    val slots = slotsFor("a", 3)
    val task = wholeTask("a", slots)
    val prebuilt = PrebuiltStorageTrie.Hash(Vector.empty, task.storageRoot)
    val response = StorageRanges(BigInt(1), Seq(slots), Nil)

    m.processServedTasks(
      peer("p1"),
      Seq(task),
      BigInt(1024),
      response,
      servedCount = 1,
      verified = Some(VerifiedStorageResponse(Vector(Some(Right(()))), Map(0 -> prebuilt), 0L))
    )
    m.applied.last._4 shouldBe Some(prebuilt)

    m.processServedTasks(peer("p1"), Seq(task), BigInt(1024), response, servedCount = 1)
    m.applied.last._4 shouldBe None
    m.applied should have size 2
    m.calls should contain("recordPeerSuccess(p1)")
    m.calls should contain("adjustResponseBytesOnSuccess(p1)")
    m.calls.last shouldBe "dispatchIfPossible(p1)"
    self.receiveAll() shouldBe Seq(StorageCheckCompletion, StorageCheckCompletion)
  }

  it should "give an account up to healing after 3 stale-local-root failures from 2 peers, dropping its queued siblings" taggedAs UnitTest in {
    val self = TestInbox[StorageRangeCoordinator.Command]()
    val m = module(self)
    val slots = slotsFor("a", 3)
    val staleTask = StorageTask(kec256(ByteString("a")), kec256(ByteString("wrong-root")), Min, Max)
    val sibling = staleTask.copy(next = ByteString(Array.fill[Byte](32)(0x10)))
    val other = wholeTask("other", slots)
    m.tasks.enqueueAll(Seq(sibling, other))
    m.pendingTaskKeys += ((sibling.accountHash, sibling.next))
    val response = StorageRanges(BigInt(1), Seq(slots), Nil)

    m.processServedTasks(peer("p1"), Seq(staleTask), BigInt(1024), response, servedCount = 1)
    m.processServedTasks(peer("p2"), Seq(staleTask), BigInt(1024), response, servedCount = 1)
    m.staleRootFailuresByAccount(staleTask.accountHash) should have size 2
    m.tasks.count(_ == staleTask) shouldBe 2 // re-queued, no peer penalty
    m.calls.exists(_.startsWith("recordPeerCooldown")) shouldBe false

    m.processServedTasks(peer("p1"), Seq(staleTask), BigInt(1024), response, servedCount = 1)

    m.staleRootFailureEvents shouldBe 3L
    m.staleRootFailuresByAccount.get(staleTask.accountHash) shouldBe None
    m.abandonedAccounts should contain(staleTask.accountHash)
    m.tasks.toList shouldBe List(other) // the sibling and the re-queued copies are gone
    m.pendingTaskKeys shouldBe empty
    m.calls should contain(s"recordCompletedTask(${staleTask.accountString})")
    m.calls should contain(s"resetAccountTrie(${staleTask.accountString})")
    m.applied shouldBe empty
  }

  it should "re-queue an empty response's task with a strike, then skip it after 5" taggedAs UnitTest in {
    val self = TestInbox[StorageRangeCoordinator.Command]()
    val m = module(self)
    val task = StorageTask(kec256(ByteString("empty")), kec256(ByteString("root")), Min, Max)
    def deliverEmpty(seq: Long): Unit =
      m.pendingResponses.enqueue(job(seq, peer("p1"), Seq(task), StorageRanges(BigInt(seq), Nil, Nil), ready = true))
      m.drainProcessedResponses()

    (0L until 4L).foreach(deliverEmpty)
    m.tasks should have size 4
    m.emptyResponsesByTask(StorageTaskKey(task.accountHash, task.next, task.last)) shouldBe 4
    m.calls.count(_ == "markPeerStateless(p1)") shouldBe 4
    m.calls.count(_ == "adjustResponseBytesOnFailure(p1)") shouldBe 4

    deliverEmpty(4L)
    m.tasks should have size 4 // the fifth is skipped, not re-queued
    m.calls should contain(s"recordCompletedTask(${task.accountString})")
    m.calls should contain(s"resetAccountTrie(${task.accountString})")
    self.receiveAll() shouldBe Seq(StorageCheckCompletion)
  }

  it should "route a proof-of-absence through the ordering gate with no slots" taggedAs UnitTest in {
    val self = TestInbox[StorageRangeCoordinator.Command]()
    val m = module(self)
    val task = StorageTask(kec256(ByteString("absent")), kec256(ByteString("root")), Min, Max)
    val proof = Seq(ByteString("proof-node"))
    m.statelessPeers += "p1"
    m.pendingResponses.enqueue(job(0, peer("p1"), Seq(task), StorageRanges(BigInt(1), Nil, proof), ready = true))

    m.drainProcessedResponses()

    m.applied.toList shouldBe List((task, Seq.empty, proof, None))
    m.statelessPeers shouldBe empty
    m.calls should contain("recordPeerAnswered(p1)")
    m.calls should contain("dispatchIfPossible(p1)")
    self.receiveAll() shouldBe Seq(StorageCheckCompletion)
  }

  it should "split the remaining range into 16 subtasks and count an account done once every subtask is" taggedAs UnitTest in {
    val self = TestInbox[StorageRangeCoordinator.Command]()
    val m = module(self)
    val task = StorageTask(kec256(ByteString("big")), kec256(ByteString("root")), Min, Max)
    val lastSlot = ByteString(Array.fill[Byte](31)(0) :+ 0x0f.toByte)

    val subTasks = m.createStorageSubTasks(task, lastSlot)

    subTasks should have size 16
    subTasks.head.next shouldBe ByteString(Array.fill[Byte](31)(0) :+ 0x10.toByte)
    subTasks.last.last shouldBe Max
    subTasks.map(_.accountHash).distinct shouldBe Seq(task.accountHash)

    m.recordSubtaskCompletion(ByteString("untracked")) shouldBe true
    m.completedAccountCount shouldBe 1L
    m.accountSubtaskCounters(task.accountHash) = (2, 0)
    m.recordSubtaskCompletion(task.accountHash) shouldBe false
    m.recordSubtaskCompletion(task.accountHash) shouldBe true
    m.completedAccountCount shouldBe 2L
    m.accountSubtaskCounters.get(task.accountHash) shouldBe None
  }

  it should "shut the owned worker pool down" taggedAs UnitTest in {
    val self = TestInbox[StorageRangeCoordinator.Command]()
    val pool = StorageRangeCoordinator.newProcessingPool(1)
    val m = module(self, processing = offActor, pool = Some(pool))
    m.processingPoolShutDown shouldBe Some(false)
    m.shutdownProcessingPool()
    m.processingPoolShutDown shouldBe Some(true)
    module(self).processingPoolShutDown shouldBe None
  }
