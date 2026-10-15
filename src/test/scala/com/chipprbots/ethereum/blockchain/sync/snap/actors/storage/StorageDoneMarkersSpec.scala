package com.chipprbots.ethereum.blockchain.sync.snap.actors.storage

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
import com.chipprbots.ethereum.blockchain.sync.snap.SnapIntakeBudget
import com.chipprbots.ethereum.blockchain.sync.snap.StateValidator
import com.chipprbots.ethereum.blockchain.sync.snap.StorageScheme
import com.chipprbots.ethereum.blockchain.sync.snap.StorageTask
import com.chipprbots.ethereum.blockchain.sync.snap.actors.StorageRangeCoordinator
import com.chipprbots.ethereum.blockchain.sync.snap.actors.StorageRangeCoordinator.FlatBatchFlushComplete
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
import com.chipprbots.ethereum.testing.Tags.UnitTest

/** Everything `StorageDoneMarkers` needs, as a stub (spec 016 FR-016 (b), C-S3): its `StorageDoneMarkersState`, the
  * coordinator's `StorageRangeEnv` (with a `StateIngest` whose only live member is `flatSlots`) and the
  * `StorageTrieFlushApi` it calls in the ordered apply. State is plain fields; the trie-node flushes are counted. The
  * flat batch runs on `ExecutionContext.parasitic`, so it commits, and its completion message reaches `self` (a
  * `TestInbox`), before the call returns. The core's flush-complete arm is not here: a case that needs a batch to count
  * as committed calls `recordFlatBatchDone` itself, as that arm does.
  */
private[actors] class StubStorageDoneMarkersState(
    val self: ActorRef[StorageRangeCoordinator.Command],
    flatSlotStore: FlatSlotStorage,
    recordStorageDone: Boolean,
    val flatBatchEntryThreshold: Int
) extends StorageDoneMarkersState
    with StorageRangeEnv
    with StorageTrieFlushApi:

  // StorageDoneMarkersState
  var stateRoot: ByteString = ByteString(Array.fill[Byte](32)(7))
  var completedTaskCount: Long = 0L
  val pendingFlatBatchAccounts: mutable.ArrayBuffer[(ByteString, Seq[(ByteString, ByteString)])] =
    mutable.ArrayBuffer.empty
  var pendingFlatBatchEntries: Int = 0
  var inFlightFlatBatches: Int = 0
  val storageDoneStorage: Option[SnapStorageDoneStorage] =
    Option.when(recordStorageDone)(new SnapStorageDoneStorage(flatSlotStore.dataSource))
  val pendingDoneMarkers: mutable.ArrayBuffer[(ByteString, ByteString, Long)] = mutable.ArrayBuffer.empty
  var doneMarkersDisabled: Boolean = false
  var flatBatchSeq: Long = 0L
  val flatBatchesDoneAhead: mutable.Set[Long] = mutable.Set.empty
  val flatBatchEc: ExecutionContext = ExecutionContext.parasitic

  // StorageRangeEnv (self and flatBatchEntryThreshold are constructor vals)
  val log: org.slf4j.Logger = org.slf4j.LoggerFactory.getLogger(classOf[StubStorageDoneMarkersState])
  def context: ActorContext[StorageRangeCoordinator.Command] = notReached("context")
  def networkPeerManager: ActorRef[NetworkPeerManagerActor.Command] = notReached("networkPeerManager")
  def requestTracker: SNAPRequestTracker = notReached("requestTracker")
  def snapSyncController: ActorRef[SNAPSyncController.Command] = notReached("snapSyncController")
  val maxAccountsPerBatch: Int = 4
  val maxInFlightRequests: Int = 10
  val requestTimeout: FiniteDuration = 30.seconds
  val configInitialResponseBytes: Int = 1048576
  val configMinResponseBytes: Int = 131072
  val deferredMerkleization: Boolean = true
  val maxConcurrentStorageAccounts: Int = 256
  val intakeBudget: Option[SnapIntakeBudget] = None
  val processing: StorageRangeCoordinator.ProcessingSettings = StorageRangeCoordinator.ProcessingSettings.Inline
  def mptStorage: MptStorage = notReached("mptStorage")
  val snapProgressStorage: Option[SnapSyncProgressStorage] = None
  val storageScheme: StorageScheme = StorageScheme.Hash
  val stateIngest: StateIngest = new StateIngest:
    def appState: AppStateStorage = notReached("StateIngest.appState")
    def stateTries: StateStorage = notReached("StateIngest.stateTries")
    def flatSlots: FlatSlotStorage = flatSlotStore
    def pathNodes: Option[PathNodeStorage] = notReached("StateIngest.pathNodes")
    def bytecode: EvmCodeStorage = notReached("StateIngest.bytecode")
    def chainReader: BlockchainReader = notReached("StateIngest.chainReader")
    def chainWriter: BlockchainWriter = notReached("StateIngest.chainWriter")
    def validatorFactory: MptStorage => StateValidator = notReached("StateIngest.validatorFactory")
    def durability: StateIngest.Durability = StateIngest.Durability.Default
    def trieStore(pivot: BigInt): MptStorage = notReached("StateIngest.trieStore")
    def snapProgress: SnapSyncProgressStorage = notReached("StateIngest.snapProgress")
    def storageDone: SnapStorageDoneStorage = notReached("StateIngest.storageDone")

  // StorageTrieFlushApi (counted)
  var trieFlushes: Int = 0

  def flushTrieNodeWrites(): Unit = trieFlushes += 1

  private def notReached(member: String): Nothing =
    throw new UnsupportedOperationException(s"StorageDoneMarkers read $member")

/** Spec 016 C-S3 stub test (FR-016 (b), T068): `StorageDoneMarkers` mixed into a stub of its state interface, the
  * coordinator environment and the trie-flush Api. The cases stage flat slots and completion markers, commit flat
  * batches to an Ephem `FlatSlotStorage` and read the markers back through `SnapStorageDoneStorage`, covering the spec
  * 010 R2a rule: a marker rides in its own slots' batch or a later one, once every batch up to its own has committed,
  * and never waits on a batch submitted after it.
  */
class StorageDoneMarkersSpec extends AnyFlatSpec with Matchers:

  private val AccountA = ByteString(Array.fill[Byte](32)(0x0a))
  private val AccountB = ByteString(Array.fill[Byte](32)(0x0b))
  private val RootA = ByteString(Array.fill[Byte](32)(0x1a))
  private val RootB = ByteString(Array.fill[Byte](32)(0x1b))

  /** A 32-byte slot key whose last byte is `b` (the rest zero). */
  private def key(b: Int): ByteString = ByteString(Array.fill[Byte](31)(0) :+ b.toByte)

  private def slots(bs: Int*): Seq[(ByteString, ByteString)] = bs.map(b => key(b) -> ByteString(s"value-$b"))

  private def module(
      self: TestInbox[StorageRangeCoordinator.Command],
      store: FlatSlotStorage,
      recordStorageDone: Boolean = true,
      threshold: Int = 1000
  ): StubStorageDoneMarkersState & StorageDoneMarkers =
    new StubStorageDoneMarkersState(self.ref, store, recordStorageDone, threshold) with StorageDoneMarkers

  /** The flush-complete messages `self` received, with the elapsed time zeroed (it is wall-clock). */
  private def completions(self: TestInbox[StorageRangeCoordinator.Command]): Seq[StorageRangeCoordinator.Command] =
    self.receiveAll().map {
      case c: FlatBatchFlushComplete => c.copy(elapsedMs = 0L)
      case other                     => other
    }

  "StorageDoneMarkers" should "keep a sorted chunk and submit nothing below the threshold" taggedAs UnitTest in {
    val self = TestInbox[StorageRangeCoordinator.Command]()
    val store = new FlatSlotStorage(EphemDataSource())
    val m = module(self, store)
    m.stageFlatSlotChunk(AccountA, slots(0x30, 0x10, 0x20))
    m.stageFlatSlotChunk(AccountB, Seq.empty)

    m.pendingFlatBatchAccounts.toSeq shouldBe Seq(AccountA -> slots(0x10, 0x20, 0x30))
    m.pendingFlatBatchEntries shouldBe 3
    m.flatBatchSeq shouldBe 0L
    m.inFlightFlatBatches shouldBe 0
    m.trieFlushes shouldBe 0
    store.getSlot(AccountA, key(0x10)) shouldBe None
    self.hasMessages shouldBe false
  }

  it should "submit a batch at the threshold: trie nodes, then slots, then the completion" taggedAs UnitTest in {
    val self = TestInbox[StorageRangeCoordinator.Command]()
    val store = new FlatSlotStorage(EphemDataSource())
    val m = module(self, store, threshold = 3)
    m.stageFlatSlotChunk(AccountA, slots(0x10, 0x20))
    m.trieFlushes shouldBe 0
    m.stageFlatSlotChunk(AccountB, slots(0x30))

    m.trieFlushes shouldBe 1
    m.flatBatchSeq shouldBe 1L
    m.inFlightFlatBatches shouldBe 1
    m.pendingFlatBatchAccounts shouldBe empty
    m.pendingFlatBatchEntries shouldBe 0
    store.getSlot(AccountA, key(0x20)) shouldBe Some(ByteString("value-32"))
    store.getSlot(AccountB, key(0x30)) shouldBe Some(ByteString("value-48"))
    completions(self) shouldBe Seq(FlatBatchFlushComplete(m.stateRoot, 3, 0L, 1L))
  }

  it should "flush the trie nodes but submit no batch when nothing is staged" taggedAs UnitTest in {
    val self = TestInbox[StorageRangeCoordinator.Command]()
    val m = module(self, new FlatSlotStorage(EphemDataSource()))
    m.flushPendingFlatBatch()

    m.trieFlushes shouldBe 1
    m.flatBatchSeq shouldBe 0L
    m.inFlightFlatBatches shouldBe 0
    self.hasMessages shouldBe false
  }

  it should "commit a marker in the same batch as its account's last slots" taggedAs UnitTest in {
    val self = TestInbox[StorageRangeCoordinator.Command]()
    val store = new FlatSlotStorage(EphemDataSource())
    val m = module(self, store)
    m.stageFlatSlotChunk(AccountA, slots(0x10))
    m.stageDoneMarker(AccountA, RootA)
    m.pendingDoneMarkers.toSeq shouldBe Seq((AccountA, RootA, 1L))

    m.flushPendingFlatBatch()
    m.pendingDoneMarkers shouldBe empty
    m.storageDoneStorage.exists(_.isDone(AccountA, RootA)) shouldBe true
    store.getSlot(AccountA, key(0x10)) shouldBe Some(ByteString("value-16"))
    completions(self) shouldBe Seq(FlatBatchFlushComplete(m.stateRoot, 1, 0L, 1L))
  }

  it should "hold a marker until every batch up to its own has committed, then write it alone" taggedAs UnitTest in {
    val self = TestInbox[StorageRangeCoordinator.Command]()
    val store = new FlatSlotStorage(EphemDataSource())
    val m = module(self, store)
    // Batch 1 is submitted; the core has not seen it commit. A marker staged now may have slots in batch 1 or in the
    // accumulator (batch 2), so it waits for both.
    m.stageFlatSlotChunk(AccountA, slots(0x10))
    m.flushPendingFlatBatch()
    m.stageDoneMarker(AccountA, RootA)
    m.pendingDoneMarkers.toSeq shouldBe Seq((AccountA, RootA, 2L))

    m.stageFlatSlotChunk(AccountB, slots(0x20))
    m.flushPendingFlatBatch() // batch 2: its own batch, but batch 1 is not done
    m.storageDoneStorage.exists(_.isDone(AccountA, RootA)) shouldBe false
    m.pendingDoneMarkers.size shouldBe 1

    m.recordFlatBatchDone(2L)
    m.recordFlatBatchDone(1L)
    m.flatBatchesDoneThrough shouldBe 2L
    m.flushPendingFlatBatch() // batch 3: markers only
    m.storageDoneStorage.exists(_.isDone(AccountA, RootA)) shouldBe true
    m.pendingDoneMarkers shouldBe empty
    m.flatBatchSeq shouldBe 3L
    completions(self) shouldBe Seq(
      FlatBatchFlushComplete(m.stateRoot, 1, 0L, 1L),
      FlatBatchFlushComplete(m.stateRoot, 1, 0L, 2L),
      FlatBatchFlushComplete(m.stateRoot, 0, 0L, 3L)
    )
  }

  it should "advance the committed watermark only over contiguous batches" taggedAs UnitTest in {
    val self = TestInbox[StorageRangeCoordinator.Command]()
    val m = module(self, new FlatSlotStorage(EphemDataSource()))
    m.recordFlatBatchDone(2L)
    m.flatBatchesDoneThrough shouldBe 0L
    m.flatBatchesDoneAhead.toSet shouldBe Set(2L)

    m.recordFlatBatchDone(1L)
    m.flatBatchesDoneThrough shouldBe 2L
    m.flatBatchesDoneAhead shouldBe empty

    m.recordFlatBatchDone(1L)
    m.flatBatchesDoneThrough shouldBe 2L
    m.flatBatchesDoneAhead shouldBe empty
  }

  it should "pick the markers that may ride in a batch and keep the rest staged" taggedAs UnitTest in {
    val self = TestInbox[StorageRangeCoordinator.Command]()
    val m = module(self, new FlatSlotStorage(EphemDataSource()))
    m.pendingDoneMarkers ++= Seq((AccountA, RootA, 1L), (AccountB, RootB, 3L))
    m.recordFlatBatchDone(1L)

    m.takeDoneMarkersFor(2L) shouldBe Seq(AccountA -> RootA)
    m.pendingDoneMarkers.toSeq shouldBe Seq((AccountB, RootB, 3L))
    m.takeDoneMarkersFor(3L) shouldBe empty
    m.recordFlatBatchDone(2L)
    m.takeDoneMarkersFor(3L) shouldBe Seq(AccountB -> RootB)
    m.pendingDoneMarkers shouldBe empty
  }

  it should "stage and write no marker when markers are off or disabled" taggedAs UnitTest in {
    val self = TestInbox[StorageRangeCoordinator.Command]()
    val off = module(self, new FlatSlotStorage(EphemDataSource()), recordStorageDone = false)
    off.stageDoneMarker(AccountA, RootA)
    off.pendingDoneMarkers shouldBe empty
    off.pendingDoneMarkers += ((AccountA, RootA, 1L))
    off.takeDoneMarkersFor(1L) shouldBe empty

    val disabled = module(self, new FlatSlotStorage(EphemDataSource()))
    disabled.doneMarkersDisabled = true
    disabled.stageDoneMarker(AccountA, RootA)
    disabled.pendingDoneMarkers shouldBe empty
    disabled.pendingDoneMarkers += ((AccountA, RootA, 1L))
    disabled.takeDoneMarkersFor(1L) shouldBe empty
  }

  it should "count completed tasks" taggedAs UnitTest in {
    val self = TestInbox[StorageRangeCoordinator.Command]()
    val m = module(self, new FlatSlotStorage(EphemDataSource()))
    m.recordCompletedTask(StorageTask(AccountA, RootA, key(0), key(0x7f)))
    m.recordCompletedTask(StorageTask(AccountB, RootB, key(0), key(0x7f)))
    m.completedTaskCount shouldBe 2L
  }
