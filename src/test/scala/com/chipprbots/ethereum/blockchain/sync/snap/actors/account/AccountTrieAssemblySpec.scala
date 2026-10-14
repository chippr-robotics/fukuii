package com.chipprbots.ethereum.blockchain.sync.snap.actors.account

import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Path

import org.apache.pekko.actor.testkit.typed.scaladsl.BehaviorTestKit
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.testkit.typed.scaladsl.TestInbox
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.PostStop
import org.apache.pekko.actor.typed.scaladsl.ActorContext
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.scaladsl.TimerScheduler
import org.apache.pekko.util.ByteString

import scala.collection.mutable

import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.snap.AccountTask
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPRequestTracker
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController
import com.chipprbots.ethereum.blockchain.sync.snap.SnapHashTrie
import com.chipprbots.ethereum.blockchain.sync.snap.SnapIntakeBudget
import com.chipprbots.ethereum.blockchain.sync.snap.SnapTrie
import com.chipprbots.ethereum.blockchain.sync.snap.StateValidator
import com.chipprbots.ethereum.blockchain.sync.snap.StorageScheme
import com.chipprbots.ethereum.blockchain.sync.snap.actors.AccountRangeCoordinator
import com.chipprbots.ethereum.blockchain.sync.snap.actors.AccountRangeCoordinator.AccountGetProgress
import com.chipprbots.ethereum.blockchain.sync.snap.actors.AccountRangeCoordinator.StoreAccountChunk
import com.chipprbots.ethereum.blockchain.sync.snap.actors.AccountRangeCoordinator.TrieFlushComplete
import com.chipprbots.ethereum.blockchain.sync.snap.actors.AccountRangeCoordinator.TrieFlushFailed
import com.chipprbots.ethereum.blockchain.sync.snap.actors.AccountRangeCoordinator.WorkerRef
import com.chipprbots.ethereum.blockchain.sync.snap.actors.AccountRangeStats
import com.chipprbots.ethereum.blockchain.sync.snap.controller.StateIngest
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.db.storage.FlatSlotStorage
import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.db.storage.PathNodeStorage
import com.chipprbots.ethereum.db.storage.SnapStorageDoneStorage
import com.chipprbots.ethereum.db.storage.SnapSyncProgressStorage
import com.chipprbots.ethereum.db.storage.StateStorage
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.domain.BlockchainWriter
import com.chipprbots.ethereum.domain.UInt256
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.testing.Tags.UnitTest
import com.chipprbots.ethereum.testing.TestMptStorage

/** An in-memory trie store that records every raw-node batch the Hash-scheme StackTrie flushes to it. */
private class RecordingMptStorage extends TestMptStorage:
  val flushed: mutable.ArrayBuffer[ByteString] = mutable.ArrayBuffer.empty
  override def storeRawNodes(rawNodes: Seq[(ByteString, Array[Byte])]): Unit =
    flushed ++= rawNodes.map(_._1)
    super.storeRawNodes(rawNodes)

/** A path-keyed node store that records the account-node paths the Path-scheme StackTrie writes. */
private class RecordingPathNodeStorage extends PathNodeStorage(EphemDataSource()):
  val written: mutable.ArrayBuffer[Seq[Byte]] = mutable.ArrayBuffer.empty
  override def writeAccountNode(nibblePath: Array[Byte], rlp: Array[Byte]): Unit =
    written += nibblePath.toSeq
    super.writeAccountNode(nibblePath, rlp)

/** Everything `AccountTrieAssembly` needs, as a stub (spec 016 FR-016 (b), C-A3): its `AccountTrieAssemblyState`, the
  * coordinator's `AccountRangeEnv` (with a `StateIngest` whose only live member is `pathNodes`), and the
  * `AccountActivePeersApi`, `AccountTaskLifecycleApi`, `AccountProgressApi`, `AccountRangeCoreApi` and
  * `ContractTaskFilesApi` it calls. State is plain fields; the Api members record their calls. The context is a
  * `BehaviorTestKit`'s, so messages to `ctx.self` land in its self inbox.
  */
private[actors] class StubAccountTrieAssemblyState(
    val ctx: ActorContext[AccountRangeCoordinator.Command],
    val timers: TimerScheduler[AccountRangeCoordinator.Command],
    val networkPeerManager: ActorRef[NetworkPeerManagerActor.Command],
    val requestTracker: SNAPRequestTracker,
    val snapSyncController: ActorRef[SNAPSyncController.Command],
    val storageScheme: StorageScheme,
    val mptStorage: MptStorage,
    pathNodeStore: Option[PathNodeStorage]
) extends AccountTrieAssemblyState
    with AccountRangeEnv
    with AccountActivePeersApi
    with AccountTaskLifecycleApi
    with AccountProgressApi
    with AccountRangeCoreApi
    with ContractTaskFilesApi:

  // AccountTrieAssemblyState
  var stateRoot: ByteString = ByteString(Array.fill[Byte](32)(7))
  val pendingTasks: mutable.PriorityQueue[AccountTask] =
    mutable.PriorityQueue.empty[AccountTask](Ordering.by[AccountTask, BigInt](_.remainingKeyspace).reverse)
  val activeTasks: mutable.Map[BigInt, (AccountTask, WorkerRef, Peer)] = mutable.Map.empty
  val completedTasks: mutable.ArrayBuffer[AccountTask] = mutable.ArrayBuffer.empty
  val workers: mutable.ArrayBuffer[WorkerRef] = mutable.ArrayBuffer.empty
  var accountsDownloaded: Long = 0L
  val startTime: Long = 0L
  var consumedKeyspace: BigInt = BigInt(0)
  val storingTasks: mutable.Map[ByteString, AccountTask] = mutable.Map.empty
  val taskStackTries: mutable.Map[ByteString, SnapTrie] = mutable.Map.empty
  var trieFlushGeneration: Long = 0L
  val contractAccountsFile: Path = Path.of("contract-accounts.bin")
  val contractAccountsOut: BufferedOutputStream = new BufferedOutputStream(new ByteArrayOutputStream())
  var contractAccountsCount: Long = 0L
  val contractStorageFile: Path = Path.of("contract-storage.bin")
  val contractStorageOut: BufferedOutputStream = new BufferedOutputStream(new ByteArrayOutputStream())
  var contractStorageCount: Long = 0L
  val uniqueCodeHashesFile: Path = Path.of("unique-codehashes.bin")
  val uniqueCodeHashesOut: BufferedOutputStream = new BufferedOutputStream(new ByteArrayOutputStream())
  var uniqueCodeHashesCount: Long = 0L

  // AccountRangeEnv (ctx, timers, networkPeerManager, requestTracker, snapSyncController, storageScheme and mptStorage
  // are constructor vals)
  val log: org.slf4j.Logger = org.slf4j.LoggerFactory.getLogger(classOf[StubAccountTrieAssemblyState])
  val futureLog: org.slf4j.Logger = log
  val concurrency: Int = 1
  val initialResponseBytesConfig: Int = 524288
  val minResponseBytesConfig: Int = 102400
  val intakeBudget: Option[SnapIntakeBudget] = None
  val resumeProgress: Map[ByteString, ByteString] = Map.empty
  def storageDone: Option[SnapStorageDoneStorage] =
    throw new UnsupportedOperationException("AccountTrieAssembly read storageDone")
  val stateIngest: StateIngest = new StateIngest:
    def appState: AppStateStorage = notReached("appState")
    def stateTries: StateStorage = notReached("stateTries")
    def flatSlots: FlatSlotStorage = notReached("flatSlots")
    def pathNodes: Option[PathNodeStorage] = pathNodeStore
    def bytecode: EvmCodeStorage = notReached("bytecode")
    def chainReader: BlockchainReader = notReached("chainReader")
    def chainWriter: BlockchainWriter = notReached("chainWriter")
    def validatorFactory: MptStorage => StateValidator = notReached("validatorFactory")
    def durability: StateIngest.Durability = StateIngest.Durability.Default
    def trieStore(pivot: BigInt): MptStorage = notReached("trieStore")
    def snapProgress: SnapSyncProgressStorage = notReached("snapProgress")
    def storageDone: SnapStorageDoneStorage = notReached("storageDone")

  private def notReached(member: String): Nothing =
    throw new UnsupportedOperationException(s"AccountTrieAssembly read StateIngest.$member")

  // AccountActivePeersApi
  def activePeerCount: Int = 0

  // AccountTaskLifecycleApi
  var redispatchCalls: Int = 0
  def tryRedispatchPendingTasks(): Unit = redispatchCalls += 1

  // AccountProgressApi
  val snapshots: mutable.ArrayBuffer[Boolean] = mutable.ArrayBuffer.empty
  def sendProgressSnapshot(durableRequested: Boolean = false): Unit = snapshots += durableRequested

  // AccountRangeCoreApi
  val stats: AccountRangeStats = AccountRangeStats(1L, 2L, 3, 0, 0, 1.0, 4L, 5L)
  var stops: Int = 0
  def calculateProgress(): AccountRangeStats = stats
  def onStop(): Unit = stops += 1

  // ContractTaskFilesApi
  def readContractFile(filePath: Path, out: BufferedOutputStream, count: Long): Seq[(ByteString, ByteString)] =
    Seq.empty
  def readUniqueCodeHashes(): Seq[ByteString] = Seq.empty

/** Spec 016 C-A3 stub test (FR-016 (b), T064): `AccountTrieAssembly` mixed into a stub of its state interface, the
  * coordinator environment and the Apis it calls, inside a `BehaviorTestKit` so that the context is real. The cases
  * call the module's chunked store, per-range StackTrie, finalisation and `finalizing` bodies, which read and write
  * `AccountTrieAssemblyState` and write trie nodes to the env's `mptStorage` (Hash) or `stateIngest.pathNodes` (Path).
  */
class AccountTrieAssemblySpec extends ScalaTestWithActorTestKit with AnyFlatSpecLike with Matchers:

  /** The module on its stub, the kit that runs it, and the controller inbox it talks to. `finalizingBehavior` runs the
    * module's `finalizing` behaviour instead of a driver that calls `action`.
    */
  final private class Harness(
      scheme: StorageScheme = StorageScheme.Hash,
      pathNodes: Option[PathNodeStorage] = None,
      finalizingBehavior: Boolean = false
  ):
    val controller: TestInbox[SNAPSyncController.Command] = TestInbox[SNAPSyncController.Command]()
    val mpt: RecordingMptStorage = new RecordingMptStorage
    private val peerManager = TestInbox[NetworkPeerManagerActor.Command]()
    private val tracker = new SNAPRequestTracker()(system.classicSystem.scheduler)
    private var action: () => Unit = () => ()
    private var captured: StubAccountTrieAssemblyState & AccountTrieAssembly = null
    val kit: BehaviorTestKit[AccountRangeCoordinator.Command] =
      BehaviorTestKit(Behaviors.setup[AccountRangeCoordinator.Command] { ctx =>
        Behaviors.withTimers { timers =>
          captured = new StubAccountTrieAssemblyState(
            ctx,
            timers,
            peerManager.ref,
            tracker,
            controller.ref,
            scheme,
            mpt,
            pathNodes
          ) with AccountTrieAssembly
          if finalizingBehavior then captured.finalizing()
          else
            Behaviors.receiveMessage { _ =>
              action()
              Behaviors.same
            }
        }
      })
    val module: StubAccountTrieAssemblyState & AccountTrieAssembly = captured

    /** Run `f` on the module while the kit processes a message, as the coordinator's arms do. */
    def call(f: StubAccountTrieAssemblyState & AccountTrieAssembly => Unit): Unit =
      action = () => f(module)
      kit.run(AccountRangeCoordinator.CheckCompletion)

    /** What the module sent to `ctx.self`. */
    def selfMessages(): Seq[AccountRangeCoordinator.Command] = kit.selfInbox().receiveAll()

  private def hash(last: Int): ByteString =
    ByteString(Array.fill[Byte](30)(0) ++ Array((last >> 8).toByte, last.toByte))

  private def accounts(n: Int): Seq[(ByteString, Account)] = (1 to n).map(i => hash(i) -> Account(nonce = UInt256(i)))

  /** The whole keyspace as one task, at the stub's state root, with its cursor past the stored accounts. */
  private def storedTask(m: StubAccountTrieAssemblyState, n: Int): AccountTask =
    AccountTask.createInitialTasks(m.stateRoot, 1).head.copy(next = hash(n + 1))

  "AccountTrieAssembly" should "store a partial response into the range's Hash-scheme StackTrie and re-queue the task" taggedAs UnitTest in {
    val h = new Harness
    val m = h.module
    val t = storedTask(m, 3)
    m.storingTasks(t.last) = t
    m.stateRoot = ByteString(Array.fill[Byte](32)(9))

    h.call(_.handleStoreAccountChunk(t, accounts(3), 3, storedSoFar = 0, isTaskRangeComplete = false))

    m.accountsDownloaded shouldBe 3L
    h.controller.receiveAll() shouldBe Seq(SNAPSyncController.ProgressAccountsSynced(3L))
    m.storingTasks shouldBe empty
    m.pendingTasks.dequeue() shouldBe t.copy(rootHash = m.stateRoot)
    m.redispatchCalls shouldBe 1
    m.snapshots.toSeq shouldBe Seq(false)
    m.taskStackTries.get(t.last).map(_.getClass) shouldBe Some(classOf[SnapHashTrie])
    m.completedTasks shouldBe empty
    h.selfMessages() shouldBe Seq(AccountRangeCoordinator.CheckCompletion)
  }

  it should "commit a completed range's StackTrie to the env's trie store and checkpoint durably" taggedAs UnitTest in {
    val h = new Harness
    val m = h.module
    val t = storedTask(m, 4)
    m.storingTasks(t.last) = t

    h.call(_.handleStoreAccountChunk(t, accounts(4), 4, storedSoFar = 0, isTaskRangeComplete = true))

    m.completedTasks.toSeq shouldBe Seq(t.copy(done = true))
    m.taskStackTries shouldBe empty
    h.mpt.flushed should not be empty
    m.snapshots.toSeq shouldBe Seq(true)
    m.pendingTasks shouldBe empty
    m.storingTasks shouldBe empty
    h.selfMessages() shouldBe Seq(AccountRangeCoordinator.CheckCompletion)
  }

  it should "yield to the mailbox after a full chunk and keep the task in storing" taggedAs UnitTest in {
    val h = new Harness
    val m = h.module
    val all = accounts(2001)
    val t = storedTask(m, 2001)
    m.storingTasks(t.last) = t

    h.call(_.handleStoreAccountChunk(t, all, 2001, storedSoFar = 0, isTaskRangeComplete = true))

    m.accountsDownloaded shouldBe 2000L
    m.storingTasks.keySet shouldBe Set(t.last)
    m.completedTasks shouldBe empty
    m.snapshots shouldBe empty
    h.selfMessages() shouldBe Seq(StoreAccountChunk(t, all.drop(2000), 2001, 2000, isTaskRangeComplete = true))
  }

  it should "write a completed Path-scheme range through stateIngest.pathNodes" taggedAs UnitTest in {
    val pns = new RecordingPathNodeStorage
    val h = new Harness(scheme = StorageScheme.Path, pathNodes = Some(pns))
    val m = h.module
    val t = storedTask(m, 4)
    m.storingTasks(t.last) = t

    h.call(_.handleStoreAccountChunk(t, accounts(4), 4, storedSoFar = 0, isTaskRangeComplete = true))

    pns.written should not be empty
    h.mpt.flushed shouldBe empty
    m.completedTasks.toSeq shouldBe Seq(t.copy(done = true))
    m.taskStackTries shouldBe empty
  }

  it should "re-queue the task when the Path scheme has no node store" taggedAs UnitTest in {
    val h = new Harness(scheme = StorageScheme.Path, pathNodes = None)
    val m = h.module
    val t = storedTask(m, 2)
    m.storingTasks(t.last) = t

    h.call(_.handleStoreAccountChunk(t, accounts(2), 2, storedSoFar = 0, isTaskRangeComplete = true))

    m.storingTasks shouldBe empty
    m.pendingTasks.dequeue() shouldBe t.copy(pending = false, done = false)
    m.completedTasks shouldBe empty
    m.accountsDownloaded shouldBe 0L
    h.selfMessages() shouldBe empty
  }

  it should "commit straggler StackTries on finalisation and report the pivot's root" taggedAs UnitTest in {
    val h = new Harness
    val m = h.module
    val straggler = new SnapHashTrie(batch => h.mpt.storeRawNodes(batch))
    accounts(2).foreach { case (k, a) => straggler.update(k.toArray, Account.accountSerializer.toBytes(a)) }
    m.taskStackTries(hash(99)) = straggler

    m.finalizeTrie() shouldBe Right(m.stateRoot)
    m.taskStackTries shouldBe empty
    h.mpt.flushed should not be empty
  }

  it should "drop a stale finalisation result, answer queries, and report the current generation's result" taggedAs UnitTest in {
    val h = new Harness(finalizingBehavior = true)
    val m = h.module
    m.trieFlushGeneration = 2L
    val root = ByteString(Array.fill[Byte](32)(3))

    h.kit.run(TrieFlushComplete(1L, Right(root)))
    h.kit.run(TrieFlushFailed(1L, "stale"))
    h.kit.isAlive shouldBe true
    h.controller.receiveAll() shouldBe empty

    val progress = TestInbox[AccountRangeStats]()
    h.kit.run(AccountGetProgress(progress.ref))
    progress.receiveAll() shouldBe Seq(m.stats)

    h.kit.signal(PostStop)
    m.stops shouldBe 1

    h.kit.run(TrieFlushComplete(2L, Right(root)))
    h.controller.receiveAll() shouldBe Seq(
      SNAPSyncController.AccountTrieFinalized(root),
      SNAPSyncController.ProgressAccountsTrieFinalized
    )
    h.kit.isAlive shouldBe false
  }

  it should "report a failed finalisation of the current generation" taggedAs UnitTest in {
    val h = new Harness(finalizingBehavior = true)

    h.kit.run(TrieFlushFailed(0L, "boom"))

    h.controller.receiveAll() shouldBe Seq(SNAPSyncController.AccountTrieFinalizationFailed("boom"))
    h.kit.isAlive shouldBe false
  }
