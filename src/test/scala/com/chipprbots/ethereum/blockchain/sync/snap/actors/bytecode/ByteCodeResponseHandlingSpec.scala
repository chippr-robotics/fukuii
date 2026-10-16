package com.chipprbots.ethereum.blockchain.sync.snap.actors.bytecode

import java.net.InetSocketAddress

import org.apache.pekko.actor.testkit.typed.scaladsl.TestInbox
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.scaladsl.ActorContext
import org.apache.pekko.util.ByteString

import scala.collection.mutable
import scala.concurrent.duration.FiniteDuration

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.snap.ByteCodeTask
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPRequestTracker
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController
import com.chipprbots.ethereum.blockchain.sync.snap.SnapIntakeBudget
import com.chipprbots.ethereum.blockchain.sync.snap.StateValidator
import com.chipprbots.ethereum.blockchain.sync.snap.actors.ByteCodeCoordinator
import com.chipprbots.ethereum.blockchain.sync.snap.actors.ByteCodeCoordinator.ActiveByteCodeRequest
import com.chipprbots.ethereum.blockchain.sync.snap.actors.ByteCodeCoordinator.ByteCodeWorkerRelease
import com.chipprbots.ethereum.blockchain.sync.snap.actors.ByteCodeCoordinator.WorkerRef
import com.chipprbots.ethereum.blockchain.sync.snap.actors.ByteCodeWorker
import com.chipprbots.ethereum.blockchain.sync.snap.controller.StateIngest
import com.chipprbots.ethereum.crypto.kec256
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
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.PeerActor
import com.chipprbots.ethereum.network.PeerId
import com.chipprbots.ethereum.network.p2p.messages.SNAP.ByteCodes
import com.chipprbots.ethereum.testing.Tags.UnitTest
import com.chipprbots.ethereum.testing.TestEvmCodeStorage

/** Everything `ByteCodeResponseHandling` needs, as a stub (spec 016 FR-016 (b), C-B1): its
  * `ByteCodeResponseHandlingState`, the coordinator's `ByteCodeEnv` (a real bytecode store behind `StateIngest`, a
  * `TestInbox` for the controller), and the `ByteCodeDispatchApi` and `ByteCodeCompletionApi` calls, recorded. State is
  * plain fields; the members these tests never reach throw.
  */
private[actors] class StubByteCodeResponseHandlingState(
    val bytecodeStore: EvmCodeStorage,
    val snapSyncController: ActorRef[SNAPSyncController.Command]
) extends ByteCodeResponseHandlingState
    with ByteCodeEnv
    with ByteCodeDispatchApi
    with ByteCodeCompletionApi:

  // ByteCodeResponseHandlingState
  val activeTasks: mutable.Map[BigInt, ActiveByteCodeRequest] = mutable.Map.empty
  var bytecodesDownloaded: Long = 0L
  var bytesDownloaded: Long = 0L
  var completedTaskCount: Long = 0L
  var consecutiveTaskFailures: Int = 0
  val hashFailureCounts: mutable.Map[ByteString, Int] = mutable.Map.empty
  val pendingTasks: mutable.Queue[ByteCodeTask] = mutable.Queue.empty

  // ByteCodeEnv
  def context: ActorContext[ByteCodeCoordinator.Command] = notUsed("context")
  val log: org.slf4j.Logger = org.slf4j.LoggerFactory.getLogger(classOf[StubByteCodeResponseHandlingState])
  def networkPeerManager: ActorRef[NetworkPeerManagerActor.Command] = notUsed("networkPeerManager")
  def requestTracker: SNAPRequestTracker = notUsed("requestTracker")
  val cooldownConfig: ByteCodeCoordinator.ByteCodePeerCooldownConfig =
    ByteCodeCoordinator.ByteCodePeerCooldownConfig.default
  def intakeBudget: Option[SnapIntakeBudget] = None
  def maxWorkers: Int = notUsed("maxWorkers")
  // Only `bytecode` is read; any other member fails loudly.
  val stateIngest: StateIngest = new StateIngest:
    def appState: AppStateStorage = notUsed("appState")
    def stateTries: StateStorage = notUsed("stateTries")
    def flatSlots: FlatSlotStorage = notUsed("flatSlots")
    def pathNodes: Option[PathNodeStorage] = notUsed("pathNodes")
    def bytecode: EvmCodeStorage = bytecodeStore
    def chainReader: BlockchainReader = notUsed("chainReader")
    def chainWriter: BlockchainWriter = notUsed("chainWriter")
    def validatorFactory: MptStorage => StateValidator = notUsed("validatorFactory")
    def durability: StateIngest.Durability = StateIngest.Durability.Default
    def trieStore(pivot: BigInt): MptStorage = notUsed("trieStore")
    def snapProgress: SnapSyncProgressStorage = notUsed("snapProgress")
    def storageDone: SnapStorageDoneStorage = notUsed("storageDone")
    def healingFrontier: Option[HealingFrontierStorage] = notUsed("healingFrontier")
    def bfsQueue: Option[BfsQueueStorage] = notUsed("bfsQueue")

  // ByteCodeDispatchApi and ByteCodeCompletionApi, recorded
  val calls: mutable.ArrayBuffer[String] = mutable.ArrayBuffer.empty
  val cooldowns: mutable.ArrayBuffer[(PeerId, FiniteDuration)] = mutable.ArrayBuffer.empty
  val idled: mutable.ArrayBuffer[WorkerRef] = mutable.ArrayBuffer.empty
  var clock: Long = 1000L
  var completionChecks: Int = 0
  def nowMillis: Long = clock
  def clearPeerFailures(peer: Peer): Unit = calls += s"clear ${peer.id.value}"
  def recordPeerCooldown(peer: Peer, base: FiniteDuration, reason: String): FiniteDuration =
    cooldowns += ((peer.id, base))
    base
  def responseBytesTargetFor(peer: Peer): BigInt = BigInt(4096)
  def adjustResponseBytesTargetOnSuccess(peer: Peer, requested: BigInt, received: BigInt): Unit =
    calls += s"grow ${peer.id.value} $requested $received"
  def adjustResponseBytesTargetOnFailure(peer: Peer, reason: String): Unit = calls += s"shrink ${peer.id.value}"
  def markWorkerIdle(worker: WorkerRef): Unit = idled += worker
  def checkCompletion(): Unit = completionChecks += 1

  private def notUsed(what: String): Nothing =
    throw new UnsupportedOperationException(s"$what is not reached by ByteCodeResponseHandlingSpec")

/** Spec 016 C-B1 stub test (FR-016 (b), T061): `ByteCodeResponseHandling` mixed into a stub of its state interface, the
  * coordinator environment and its two callee Api traits. The response cases read and write
  * `ByteCodeResponseHandlingState`; the intake cases cover the #1501 `skipPresent` check and the dedup pass.
  */
class ByteCodeResponseHandlingSpec extends AnyFlatSpec with Matchers:

  private val config = ByteCodeCoordinator.ByteCodePeerCooldownConfig.default

  private type Module = StubByteCodeResponseHandlingState & ByteCodeResponseHandling

  private def newModule(): (Module, TestInbox[SNAPSyncController.Command]) =
    val controller = TestInbox[SNAPSyncController.Command]()
    val module =
      new StubByteCodeResponseHandlingState(new TestEvmCodeStorage(), controller.ref) with ByteCodeResponseHandling
    (module, controller)

  private def peer(id: String): Peer =
    Peer(PeerId(id), new InetSocketAddress("127.0.0.1", 30303), TestInbox[PeerActor.Command]().ref, false)

  private def code(n: Int): ByteString = ByteString(Array.fill[Byte](10 + n)(n.toByte))

  /** One in-flight request (id 7) for `hashes`, on `worker` and `p`, asked for 1000 bytes. */
  private def inFlight(
      m: StubByteCodeResponseHandlingState,
      hashes: Seq[ByteString],
      worker: WorkerRef,
      p: Peer
  ): ByteCodeTask =
    val task = ByteCodeTask(hashes, pending = true)
    val request = ActiveByteCodeRequest(task, worker, p, requestedBytes = BigInt(1000), startedAtMillis = 0L)
    m.activeTasks.put(BigInt(7), request)
    task

  "ByteCodeResponseHandling" should "store a fully served response, credit progress and release the worker" taggedAs UnitTest in {
    val (m, controller) = newModule()
    val worker = TestInbox[ByteCodeWorker.Command]()
    val p = peer("p1")
    val codes = Seq(code(1), code(2))
    inFlight(m, codes.map(kec256(_)), worker.ref, p)
    m.consecutiveTaskFailures = 3

    m.handleByteCodesResponse(ByteCodes(BigInt(7), codes))

    codes.foreach(c => m.bytecodeStore.get(kec256(c)) shouldBe Some(c))
    m.activeTasks shouldBe empty
    m.pendingTasks shouldBe empty
    m.completedTaskCount shouldBe 1L
    m.bytecodesDownloaded shouldBe 2L
    m.bytesDownloaded shouldBe codes.map(_.size.toLong).sum
    m.consecutiveTaskFailures shouldBe 0
    m.hashFailureCounts shouldBe empty
    controller.receiveAll() shouldBe Seq(SNAPSyncController.ProgressBytecodesDownloaded(2L))
    worker.receiveAll() shouldBe Seq(ByteCodeWorkerRelease(BigInt(7)))
    m.idled.toSeq shouldBe Seq(worker.ref)
    m.calls.toSeq shouldBe Seq(s"grow p1 1000 ${codes.map(_.size).sum}", "clear p1")
    m.cooldowns shouldBe empty
    m.completionChecks shouldBe 1
  }

  it should "re-queue a partly served task's remainder and keep the task open" taggedAs UnitTest in {
    val (m, controller) = newModule()
    val worker = TestInbox[ByteCodeWorker.Command]()
    val p = peer("p1")
    val served = code(1)
    val missing = kec256(code(2))
    inFlight(m, Seq(kec256(served), missing), worker.ref, p)

    m.handleByteCodesResponse(ByteCodes(BigInt(7), Seq(served)))

    m.bytecodeStore.get(kec256(served)) shouldBe Some(served)
    m.pendingTasks.toSeq shouldBe Seq(ByteCodeTask(Seq(missing)))
    m.completedTaskCount shouldBe 0L
    m.bytecodesDownloaded shouldBe 1L
    m.activeTasks shouldBe empty
    controller.receiveAll() shouldBe Seq(SNAPSyncController.ProgressBytecodesDownloaded(1L))
    m.completionChecks shouldBe 1
  }

  it should "count a failure per hash on an empty response, cool the peer down and re-queue the hashes" taggedAs UnitTest in {
    val (m, _) = newModule()
    val worker = TestInbox[ByteCodeWorker.Command]()
    val p = peer("p1")
    val hashes = Seq(kec256(code(1)), kec256(code(2)))
    inFlight(m, hashes, worker.ref, p)

    m.handleByteCodesResponse(ByteCodes(BigInt(7), Seq.empty))

    m.hashFailureCounts.toMap shouldBe hashes.map(_ -> 1).toMap
    m.cooldowns.toSeq shouldBe Seq((p.id, config.baseEmpty))
    m.pendingTasks.toSeq shouldBe Seq(ByteCodeTask(hashes))
    m.bytecodesDownloaded shouldBe 0L
    m.activeTasks shouldBe empty
  }

  it should "reject an out-of-order response: nothing stored, the task re-queued, the peer cooled down" taggedAs UnitTest in {
    val (m, controller) = newModule()
    val worker = TestInbox[ByteCodeWorker.Command]()
    val p = peer("p1")
    val (a, b) = (code(1), code(2))
    val task = inFlight(m, Seq(kec256(a), kec256(b)), worker.ref, p)

    m.handleByteCodesResponse(ByteCodes(BigInt(7), Seq(b, a)))

    m.bytecodeStore.get(kec256(a)) shouldBe None
    m.bytecodeStore.get(kec256(b)) shouldBe None
    m.pendingTasks.toSeq shouldBe Seq(task.copy(pending = false))
    m.activeTasks shouldBe empty
    m.cooldowns.toSeq shouldBe Seq((p.id, config.baseInvalid))
    m.calls.toSeq shouldBe Seq("shrink p1")
    worker.receiveAll() shouldBe Seq(ByteCodeWorkerRelease(BigInt(7)))
    m.idled.toSeq shouldBe Seq(worker.ref)
    controller.hasMessages shouldBe false
    m.completionChecks shouldBe 1
  }

  it should "ignore a response for an unknown request" taggedAs UnitTest in {
    val (m, controller) = newModule()

    m.handleByteCodesResponse(ByteCodes(BigInt(99), Seq(code(1))))

    m.bytecodesDownloaded shouldBe 0L
    m.pendingTasks shouldBe empty
    controller.hasMessages shouldBe false
    m.completionChecks shouldBe 0
  }

  it should "drop codeHashes already in the bytecode store only when skipPresent is set (#1501)" taggedAs UnitTest in {
    val (m, _) = newModule()
    val (stored, absent) = (code(1), code(2))
    m.bytecodeStore.put(kec256(stored), stored).commit()
    val received = Seq(kec256(stored), kec256(absent))

    m.skipPresentCodeHashes(received, skipPresent = true) shouldBe Seq(kec256(absent))
    m.skipPresentCodeHashes(received, skipPresent = false) shouldBe received
  }

  it should "drop malformed, empty-code and duplicate codeHashes, keeping first-seen order" taggedAs UnitTest in {
    val (m, _) = newModule()
    val (h1, h2) = (kec256(code(1)), kec256(code(2)))

    m.filterAndDedupeCodeHashes(
      Seq(h1, ByteString(Array[Byte](1, 2, 3)), Account.EmptyCodeHash.value, h2, h1)
    ) shouldBe Seq(h1, h2)
  }
