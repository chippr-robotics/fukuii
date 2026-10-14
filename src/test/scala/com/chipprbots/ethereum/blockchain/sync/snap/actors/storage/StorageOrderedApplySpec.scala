package com.chipprbots.ethereum.blockchain.sync.snap.actors.storage

import java.net.InetSocketAddress

import org.apache.pekko.actor.testkit.typed.scaladsl.TestInbox
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.scaladsl.ActorContext
import org.apache.pekko.util.ByteString

import scala.collection.mutable
import scala.concurrent.duration.*

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.snap.SNAPRequestTracker
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController
import com.chipprbots.ethereum.blockchain.sync.snap.SnapHashTrie
import com.chipprbots.ethereum.blockchain.sync.snap.SnapIntakeBudget
import com.chipprbots.ethereum.blockchain.sync.snap.SnapTrie
import com.chipprbots.ethereum.blockchain.sync.snap.StateValidator
import com.chipprbots.ethereum.blockchain.sync.snap.StorageScheme
import com.chipprbots.ethereum.blockchain.sync.snap.StorageTask
import com.chipprbots.ethereum.blockchain.sync.snap.actors.StorageRangeCoordinator
import com.chipprbots.ethereum.blockchain.sync.snap.actors.StorageRangeCoordinator.ByteStringOrdering
import com.chipprbots.ethereum.blockchain.sync.snap.actors.StorageRangeCoordinator.ReadyStorageChunk
import com.chipprbots.ethereum.blockchain.sync.snap.actors.StorageRangeCoordinator.TrieNodeWrite
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
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.domain.BlockchainWriter
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.PeerActor
import com.chipprbots.ethereum.network.PeerId
import com.chipprbots.ethereum.testing.Tags.UnitTest
import com.chipprbots.ethereum.testing.TestMptStorage

/** Everything `StorageOrderedApply` needs, as a stub (spec 016 FR-016 (b), C-S2): its `StorageOrderedApplyState`, the
  * coordinator's `StorageRangeEnv` (with a `StateIngest` whose only live member is `pathNodes`), the
  * `StorageApplyCoreApi` it calls in the core and the `StoragePeerFeedbackApi` it calls in the peer dispatch. State is
  * plain fields; the core and feedback calls are recorded. The module's bodies need no actor context: they log, send to
  * the controller (a `TestInbox`) and write the stores.
  */
private[actors] class StubStorageOrderedApplyState(
    val snapSyncController: ActorRef[SNAPSyncController.Command],
    val storageScheme: StorageScheme,
    val deferredMerkleization: Boolean,
    pathNodeStore: Option[PathNodeStorage]
) extends StorageOrderedApplyState
    with StorageRangeEnv
    with StorageApplyCoreApi
    with StoragePeerFeedbackApi:

  // StorageOrderedApplyState
  var stateRoot: ByteString = ByteString(Array.fill[Byte](32)(7))
  val tasks: mutable.Queue[StorageTask] = mutable.Queue.empty
  val accountSubtaskCounters: mutable.Map[ByteString, (Int, Int)] = mutable.Map.empty
  var completedAccountCount: Long = 0L
  var slotsDownloaded: Long = 0L
  var bytesDownloaded: Long = 0L
  val pendingAccountTries: mutable.Map[ByteString, SnapTrie] = mutable.Map.empty
  val storageTrieCursor: mutable.Map[ByteString, ByteString] = mutable.Map.empty
  val pendingOrderedChunks: mutable.Map[ByteString, mutable.TreeMap[ByteString, ReadyStorageChunk]] =
    mutable.Map.empty
  val zeroSlotHash: ByteString = ByteString(Array.fill[Byte](32)(0))
  val lastAppliedSlot: mutable.Map[ByteString, (ByteString, ByteString)] = mutable.Map.empty
  val trieNodeWrites: mutable.ArrayBuffer[TrieNodeWrite] = mutable.ArrayBuffer.empty
  var trieWriteNanos: Long = 0L

  // StorageRangeEnv (snapSyncController, storageScheme and deferredMerkleization are constructor vals)
  val log: org.slf4j.Logger = org.slf4j.LoggerFactory.getLogger(classOf[StubStorageOrderedApplyState])
  def context: ActorContext[StorageRangeCoordinator.Command] = notReached("context")
  def self: ActorRef[StorageRangeCoordinator.Command] = notReached("self")
  def networkPeerManager: ActorRef[NetworkPeerManagerActor.Command] = notReached("networkPeerManager")
  def requestTracker: SNAPRequestTracker = notReached("requestTracker")
  val maxAccountsPerBatch: Int = 4
  val maxInFlightRequests: Int = 10
  val requestTimeout: FiniteDuration = 30.seconds
  val configInitialResponseBytes: Int = 1048576
  val configMinResponseBytes: Int = 131072
  val maxConcurrentStorageAccounts: Int = 256
  val intakeBudget: Option[SnapIntakeBudget] = None
  val processing: StorageRangeCoordinator.ProcessingSettings = StorageRangeCoordinator.ProcessingSettings.Inline
  val mptStorage: TestMptStorage = new TestMptStorage()
  val snapProgressStorage: Option[SnapSyncProgressStorage] = None
  val stateIngest: StateIngest = new StateIngest:
    def appState: AppStateStorage = notReached("StateIngest.appState")
    def stateTries: StateStorage = notReached("StateIngest.stateTries")
    def flatSlots: FlatSlotStorage = notReached("StateIngest.flatSlots")
    def pathNodes: Option[PathNodeStorage] = pathNodeStore
    def bytecode: EvmCodeStorage = notReached("StateIngest.bytecode")
    def chainReader: BlockchainReader = notReached("StateIngest.chainReader")
    def chainWriter: BlockchainWriter = notReached("StateIngest.chainWriter")
    def validatorFactory: MptStorage => StateValidator = notReached("StateIngest.validatorFactory")
    def durability: StateIngest.Durability = StateIngest.Durability.Default
    def trieStore(pivot: BigInt): MptStorage = notReached("StateIngest.trieStore")
    def snapProgress: SnapSyncProgressStorage = notReached("StateIngest.snapProgress")
    def storageDone: SnapStorageDoneStorage = notReached("StateIngest.storageDone")

  // StorageApplyCoreApi (recorded; recordSubtaskCompletion counts as the core does)
  val stagedFlat: mutable.ArrayBuffer[(ByteString, Seq[(ByteString, ByteString)])] = mutable.ArrayBuffer.empty
  val stagedMarkers: mutable.ArrayBuffer[(ByteString, ByteString)] = mutable.ArrayBuffer.empty
  var completedTasks: Int = 0

  def stageFlatSlotChunk(accountHash: ByteString, slots: Seq[(ByteString, ByteString)]): Unit =
    stagedFlat += ((accountHash, slots))

  def createStorageSubTasks(task: StorageTask, lastSlotReceived: ByteString): Seq[StorageTask] = Seq.empty

  def recordSubtaskCompletion(accountHash: ByteString): Boolean =
    accountSubtaskCounters.get(accountHash) match
      case None =>
        completedAccountCount += 1
        true
      case Some((total, done)) =>
        if done + 1 >= total then
          completedAccountCount += 1
          accountSubtaskCounters.remove(accountHash)
          true
        else
          accountSubtaskCounters(accountHash) = (total, done + 1)
          false

  def stageDoneMarker(accountHash: ByteString, storageRoot: ByteString): Unit =
    stagedMarkers += ((accountHash, storageRoot))

  def recordCompletedTask(task: StorageTask): Unit = completedTasks += 1

  // StoragePeerFeedbackApi (recorded)
  val cooldowns: mutable.ArrayBuffer[String] = mutable.ArrayBuffer.empty
  val budgetShrinks: mutable.ArrayBuffer[String] = mutable.ArrayBuffer.empty

  def recordPeerCooldown(peer: Peer, reason: String): Unit = cooldowns += peer.id.value

  def adjustResponseBytesOnFailure(peer: Peer, reason: String): Unit = budgetShrinks += peer.id.value

  private def notReached(member: String): Nothing =
    throw new UnsupportedOperationException(s"StorageOrderedApply read $member")

/** Spec 016 C-S2 stub test (FR-016 (b), T067): `StorageOrderedApply` mixed into a stub of its state interface, the
  * coordinator environment and the two Api traits. The cases call the module's ordering gate, chunk apply, trie
  * lifecycle and batched node writes on both schemes, which read and write `StorageOrderedApplyState`.
  */
class StorageOrderedApplySpec extends AnyFlatSpec with Matchers:

  private val Account = ByteString(Array.fill[Byte](32)(0x0a))
  private val Root = ByteString(Array.fill[Byte](32)(0x0b))

  /** A 32-byte slot key whose last byte is `b` (the rest zero). */
  private def key(b: Int): ByteString = ByteString(Array.fill[Byte](31)(0) :+ b.toByte)

  private def slots(bs: Int*): Seq[(ByteString, ByteString)] = bs.map(b => key(b) -> ByteString(s"value-$b"))

  private def peer(id: String): Peer =
    Peer(PeerId(id), new InetSocketAddress("127.0.0.1", 30303), TestInbox[PeerActor.Command]().ref, false)

  private def module(
      controller: TestInbox[SNAPSyncController.Command],
      scheme: StorageScheme = StorageScheme.Hash,
      deferredMerkleization: Boolean = true,
      pathNodes: Option[PathNodeStorage] = None
  ): StubStorageOrderedApplyState & StorageOrderedApply =
    new StubStorageOrderedApplyState(controller.ref, scheme, deferredMerkleization, pathNodes) with StorageOrderedApply

  /** The root a fresh Hash-scheme trie computes for `kvs` (what the module's trie must commit to). */
  private def hashRoot(kvs: Seq[(ByteString, ByteString)]): ByteString =
    val trie = new SnapHashTrie(_ => ())
    kvs.foreach { case (k, v) => trie.update(k.toArray, v.toArray) }
    trie.commit()

  "StorageOrderedApply" should "apply a whole in-order chunk: flat slots, counters, marker" taggedAs UnitTest in {
    val controller = TestInbox[SNAPSyncController.Command]()
    val m = module(controller)
    val served = slots(0x10, 0x20)
    m.applyOrderedStorageChunk(peer("p1"), StorageTask(Account, Root, m.zeroSlotHash, key(0x7f)), served, Seq.empty)

    m.stagedFlat.toSeq shouldBe Seq(Account -> served)
    m.slotsDownloaded shouldBe 2L
    m.completedAccountCount shouldBe 1L
    m.stagedMarkers.toSeq shouldBe Seq(Account -> Root)
    m.completedTasks shouldBe 1
    m.storageTrieCursor.contains(Account) shouldBe false
    m.lastAppliedSlot.contains(Account) shouldBe false
    m.pendingAccountTries shouldBe empty
    controller.receiveAll() shouldBe Seq(SNAPSyncController.ProgressStorageSlotsSynced(2L))
  }

  it should "buffer an out-of-order chunk and drain it once the chunk before it lands" taggedAs UnitTest in {
    val controller = TestInbox[SNAPSyncController.Command]()
    val m = module(controller)
    m.accountSubtaskCounters(Account) = (2, 0)
    m.storageTrieCursor(Account) = key(0x10)
    val low = StorageTask(Account, Root, key(0x10), key(0x1f))
    val high = StorageTask(Account, Root, key(0x20), key(0x3f))

    m.applyOrderedStorageChunk(peer("p2"), high, slots(0x20, 0x25), Seq.empty)
    m.pendingOrderedChunks(Account).keySet.toSet shouldBe Set(key(0x20))
    m.stagedFlat shouldBe empty

    m.applyOrderedStorageChunk(peer("p1"), low, slots(0x10, 0x15), Seq.empty)
    m.stagedFlat.map(_._2.head._1).toSeq shouldBe Seq(key(0x10), key(0x20))
    m.pendingOrderedChunks.contains(Account) shouldBe false
    m.accountSubtaskCounters.contains(Account) shouldBe false
    m.completedAccountCount shouldBe 1L
    m.stagedMarkers.toSeq shouldBe Seq(Account -> Root)
    m.completedTasks shouldBe 2
  }

  it should "reject a re-served boundary slot with a new value: cooldown, budget, re-queue" taggedAs UnitTest in {
    val controller = TestInbox[SNAPSyncController.Command]()
    val m = module(controller)
    m.lastAppliedSlot(Account) = key(0x10) -> ByteString("value-16")
    val task = StorageTask(Account, Root, key(0x10), key(0x3f))
    m.applyReadyStorageChunk(peer("p3"), task, Seq(key(0x10) -> ByteString("other")), Seq.empty)

    m.cooldowns.toSeq shouldBe Seq("p3")
    m.budgetShrinks.toSeq shouldBe Seq("p3")
    m.tasks.toSeq shouldBe Seq(task)
    m.stagedFlat shouldBe empty
    m.completedTasks shouldBe 0
  }

  it should "clear an account's ordering state and return its buffered tasks" taggedAs UnitTest in {
    val controller = TestInbox[SNAPSyncController.Command]()
    val m = module(controller)
    val buffered = StorageTask(Account, Root, key(0x20), key(0x3f))
    m.storageTrieCursor(Account) = key(0x10)
    m.lastAppliedSlot(Account) = key(0x0f) -> ByteString("v")
    m.pendingOrderedChunks(Account) = mutable.TreeMap(
      key(0x20) -> ReadyStorageChunk(peer("p1"), buffered, slots(0x20), Seq.empty)
    )(using ByteStringOrdering)

    m.clearStorageOrderingState(Account) shouldBe Seq(buffered)
    m.storageTrieCursor.contains(Account) shouldBe false
    m.lastAppliedSlot.contains(Account) shouldBe false
    m.pendingOrderedChunks.contains(Account) shouldBe false
  }

  it should "reset an account's trie together with its ordering state" taggedAs UnitTest in {
    val controller = TestInbox[SNAPSyncController.Command]()
    val m = module(controller)
    m.pendingAccountTries(Account) = new SnapHashTrie(_ => ())
    m.storageTrieCursor(Account) = key(0x10)

    m.resetAccountTrie(Account)
    m.pendingAccountTries.contains(Account) shouldBe false
    m.storageTrieCursor.contains(Account) shouldBe false
  }

  it should "commit no trie for an empty chunk and mark the account against its claimed root" taggedAs UnitTest in {
    val controller = TestInbox[SNAPSyncController.Command]()
    val m = module(controller, deferredMerkleization = false)
    m.applyOrderedStorageChunk(peer("p1"), StorageTask(Account, Root, m.zeroSlotHash, key(0x7f)), Seq.empty, Seq.empty)

    m.pendingAccountTries shouldBe empty
    m.completedAccountCount shouldBe 1L
    m.stagedMarkers.toSeq shouldBe Seq(Account -> Root)
  }

  it should "stream a Hash-scheme chunk into a trie whose nodes reach mptStorage on commit" taggedAs UnitTest in {
    val controller = TestInbox[SNAPSyncController.Command]()
    val m = module(controller, deferredMerkleization = false)
    val served = slots(0x10, 0x20, 0x30)
    val root = hashRoot(served)
    m.applyOrderedStorageChunk(peer("p1"), StorageTask(Account, root, m.zeroSlotHash, key(0x7f)), served, Seq.empty)

    m.pendingAccountTries shouldBe empty
    m.trieNodeWrites shouldBe empty
    noException should be thrownBy m.mptStorage.get(root.toArray)
    m.stagedMarkers.toSeq shouldBe Seq(Account -> root)
  }

  it should "buffer Path-scheme node writes and commit them in one flush" taggedAs UnitTest in {
    val controller = TestInbox[SNAPSyncController.Command]()
    val store = new PathNodeStorage(EphemDataSource())
    val m = module(controller, StorageScheme.Path, deferredMerkleization = false, pathNodes = Some(store))
    val served = slots(0x10, 0x20, 0x30)
    m.applyOrderedStorageChunk(peer("p1"), StorageTask(Account, Root, m.zeroSlotHash, key(0x7f)), served, Seq.empty)

    m.trieNodeWrites should not be empty
    var stored = 0
    store.foreachStorageNodeChunk(1000)(chunk => stored += chunk.size)
    stored shouldBe 0

    m.flushTrieNodeWrites()
    m.trieNodeWrites shouldBe empty
    store.foreachStorageNodeChunk(1000)(chunk => stored += chunk.size)
    stored should be > 0
  }

  it should "not reach the Path node store when it flushes an empty buffer" taggedAs UnitTest in {
    val controller = TestInbox[SNAPSyncController.Command]()
    val m = module(controller)
    noException should be thrownBy m.flushTrieNodeWrites()
    m.trieWriteNanos shouldBe 0L
  }
