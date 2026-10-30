package com.chipprbots.ethereum.blockchain.sync.snap.actors.healing

import java.util.concurrent.atomic.AtomicLong

import org.apache.pekko.actor.testkit.typed.scaladsl.TestInbox
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.util.ByteString

import scala.collection.mutable

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.snap.SNAPRequestTracker
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController
import com.chipprbots.ethereum.blockchain.sync.snap.StateValidator
import com.chipprbots.ethereum.blockchain.sync.snap.StorageScheme
import com.chipprbots.ethereum.blockchain.sync.snap.actors.TrieNodeHealingCoordinator
import com.chipprbots.ethereum.blockchain.sync.snap.actors.TrieNodeHealingCoordinator.HealingCheckCompletion
import com.chipprbots.ethereum.blockchain.sync.snap.actors.TrieNodeHealingCoordinator.HealingEntry
import com.chipprbots.ethereum.blockchain.sync.snap.actors.TrieNodeHealingCoordinator.VerificationBFSComplete
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
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.domain.BlockchainWriter
import com.chipprbots.ethereum.mpt.HexPrefix
import com.chipprbots.ethereum.mpt.LeafNode
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.testing.Tags.*

/** `HealVerification`'s state, environment, walk launchers and completion check as plain fields (spec 016 C-H3). The
  * launchers record their seeds and keep `onComplete`, so a test can finish the walk by hand; `isComplete` is a
  * settable flag. `stateIngest` hands out only `pathNodes` (the spec's Path store, or `None` for the Hash scheme) and
  * `healingFrontier` (`None`).
  */
private class StubHealVerificationState(
    pathStore: Option[PathNodeStorage],
    val storageScheme: StorageScheme,
    val prunedEnabled: Boolean
) extends HealVerificationState
    with TrieNodeHealingEnv
    with FrontierRebuildApi
    with HealCompletionApi
    with WalkFlushApi:

  // HealVerificationState
  var stateRoot: ByteString = ByteString(Array.fill[Byte](32)(7))
  var healedPathsRoot: ByteString = ByteString(Array.fill[Byte](32)(7))
  var verificationBFSRunning: Boolean = false
  var verificationPassComplete: Boolean = true
  var trieWalkInProgress: Boolean = false
  var flushing: Boolean = false
  var scopedVerificationActive: Boolean = true
  var scopedVerificationSeedCount: Int = 0
  var scopedVerificationStartMs: Long = 0L
  var prunedVerificationActive: Boolean = true
  var prunedVerificationStartMs: Long = 0L
  val prunedSubtreeCount: AtomicLong = new AtomicLong(42L)

  // TrieNodeHealingEnv
  val log: org.slf4j.Logger = org.slf4j.LoggerFactory.getLogger(classOf[HealVerificationSpec])
  val asyncLog: org.slf4j.Logger = log
  val prunedHealVerification: Boolean = prunedEnabled
  def networkPeerManager: ActorRef[NetworkPeerManagerActor.Command] =
    throw new UnsupportedOperationException("HealVerification read networkPeerManager")
  def requestTracker: SNAPRequestTracker = throw new UnsupportedOperationException(
    "HealVerification read requestTracker"
  )
  def snapSyncController: ActorRef[SNAPSyncController.Command] =
    throw new UnsupportedOperationException("HealVerification read snapSyncController")
  val batchSize: Int = 32
  val movingRootDeltaHeal: Boolean = false
  val scopedHealMaxPaths: Int = 200_000
  val healedCodeCheck: Boolean = false
  val selfInbox: TestInbox[TrieNodeHealingCoordinator.Command] = TestInbox[TrieNodeHealingCoordinator.Command]()
  val self: ActorRef[TrieNodeHealingCoordinator.Command] = selfInbox.ref
  def mptStorage: MptStorage = notReached("mptStorage")
  val frontierPersistenceEnabled: Boolean = false
  val decoupledHealServeRoot: Boolean = false
  val decoupledHealMaxAttemptsNoRefresh: Int = 3
  val frontierHighWater: Int = 100
  val frontierLowWater: Int = 10
  val frontierBackpressureMaxWaitMs: Long = 0L
  val stateIngest: StateIngest = new StateIngest:
    def appState: AppStateStorage = notReached("appState")
    def stateTries: StateStorage = notReached("stateTries")
    def flatSlots: FlatSlotStorage = notReached("flatSlots")
    def pathNodes: Option[PathNodeStorage] = pathStore
    def bytecode: EvmCodeStorage = notReached("bytecode")
    def chainReader: BlockchainReader = notReached("chainReader")
    def chainWriter: BlockchainWriter = notReached("chainWriter")
    def validatorFactory: MptStorage => StateValidator = notReached("validatorFactory")
    def durability: StateIngest.Durability = StateIngest.Durability.Default
    def trieStore(pivot: BigInt): MptStorage = notReached("trieStore")
    def snapProgress: SnapSyncProgressStorage = notReached("snapProgress")
    def storageDone: SnapStorageDoneStorage = notReached("storageDone")
    def healingFrontier: Option[HealingFrontierStorage] = None
    def bfsQueue: Option[BfsQueueStorage] = notReached("bfsQueue")

  // FrontierRebuildApi: record each launch, set the running flag as the real launcher does when a walk starts, and keep
  // its completion callback (called with a walk token and the missing count)
  val singleSeedLaunches: mutable.Buffer[(ByteString, ByteString, Boolean)] = mutable.Buffer.empty
  val multiSeedLaunches: mutable.Buffer[Seq[(ByteString, Seq[ByteString], Boolean)]] = mutable.Buffer.empty
  var lastOnComplete: Option[(Long, Long) => Unit] = None
  def startFrontierBFS(
      root: ByteString,
      rootPath: ByteString,
      isStor: Boolean,
      onComplete: (Long, Long) => Unit
  ): Unit =
    singleSeedLaunches += ((root, rootPath, isStor))
    verificationBFSRunning = true
    lastOnComplete = Some(onComplete)
  def startFrontierBFS(seeds: Seq[(ByteString, Seq[ByteString], Boolean)], onComplete: (Long, Long) => Unit): Unit =
    multiSeedLaunches += seeds
    verificationBFSRunning = true
    lastOnComplete = Some(onComplete)

  // HealCompletionApi
  var isComplete: Boolean = true

  // WalkFlushApi: whether the flush buffer is written (or empty) when a walk asks, and how often a walk asked
  var bufferWritten: Boolean = true
  var walkFlushes: Int = 0
  def flushedForWalk(): Boolean =
    walkFlushes += 1
    bufferWritten

  private def notReached(member: String): Nothing =
    throw new UnsupportedOperationException(s"HealVerification read $member")

/** Spec 016 C-H3 stub test (FR-016 (b)): `HealVerification` mixed into a stub of its state, environment, the frontier
  * walk's launchers and the completion check. It pins the module's seams: the Path-scheme verified reads (present only
  * when the bytes at the entry's own path hash to the entry's hash; absent for a stale node, an empty slot, a pathset
  * of another shape, a missing Path store, or bytes that hash right but do not decode); `reverifyCurrentRoot`'s gate (a
  * direct full-root walk of the current root when nothing is pending, flushing or walking, else a
  * `HealingCheckCompletion` to `self`, the verdict reset either way); the full-root entry's pruned engagement and
  * counter reset; and the scoped entry's seed mapping, its observability, and its completion against the root it was
  * launched on.
  */
class HealVerificationSpec extends AnyFlatSpec with Matchers:

  private val emptyPath: ByteString = ByteString(HexPrefix.encode(Array.empty[Byte], isLeaf = false))

  private def compact(nibbles: Int*): ByteString = ByteString(HexPrefix.encode(nibbles.map(_.toByte).toArray, false))

  private def leaf(tag: String): LeafNode =
    LeafNode(ByteString(Array[Byte](1, 2, 3)), ByteString(s"heal-verification-$tag-".padTo(40, 'x').getBytes))

  private def stub(
      store: Option[PathNodeStorage],
      scheme: StorageScheme = StorageScheme.Path,
      pruned: Boolean = false
  ): StubHealVerificationState & HealVerification =
    new StubHealVerificationState(store, scheme, pruned) with HealVerification

  "HealVerification.readPathVerifiedRlp" should "return the account node stored at its own path when it hashes to the hash" taggedAs UnitTest in {
    val pns = new PathNodeStorage(EphemDataSource())
    val node = leaf("account")
    pns.writeAccountNode(Array[Byte](1, 2), node.encode)
    val s = stub(Some(pns))

    s.readPathVerifiedRlp(ByteString(node.hash), Seq(compact(1, 2))).map(ByteString(_)) shouldBe
      Some(ByteString(node.encode))
  }

  it should "return the storage node stored at its own path under its account" taggedAs UnitTest in {
    val pns = new PathNodeStorage(EphemDataSource())
    val node = leaf("storage")
    val accountHash = ByteString(Array.fill[Byte](32)(9))
    pns.writeStorageNode(accountHash, Array[Byte](3), node.encode)
    val s = stub(Some(pns))

    s.readPathVerifiedRlp(ByteString(node.hash), Seq(accountHash, compact(3))).map(ByteString(_)) shouldBe
      Some(ByteString(node.encode))
    s.readPathVerifiedRlp(ByteString(node.hash), Seq(compact(3))) shouldBe None
  }

  it should "report absent a stale node, an empty slot, an unexpected pathset shape and a missing Path store" taggedAs UnitTest in {
    val pns = new PathNodeStorage(EphemDataSource())
    val stored = leaf("stored")
    val wanted = leaf("wanted")
    pns.writeAccountNode(Array[Byte](4), stored.encode)
    val s = stub(Some(pns))

    s.readPathVerifiedRlp(ByteString(wanted.hash), Seq(compact(4))) shouldBe None
    s.readPathVerifiedRlp(ByteString(stored.hash), Seq(compact(5))) shouldBe None
    s.readPathVerifiedRlp(ByteString(stored.hash), Seq.empty) shouldBe None
    s.readPathVerifiedRlp(ByteString(stored.hash), Seq(compact(4), compact(4), compact(4))) shouldBe None
    stub(None, StorageScheme.Hash).readPathVerifiedRlp(ByteString(stored.hash), Seq(compact(4))) shouldBe None
  }

  "HealVerification.readPathVerifiedNode" should "decode a verified node and report absent bytes that hash right but do not decode" taggedAs UnitTest in {
    val pns = new PathNodeStorage(EphemDataSource())
    val node = leaf("decoded")
    val garbage = Array[Byte](0x01, 0x02, 0x03)
    pns.writeAccountNode(Array[Byte](6), node.encode)
    pns.writeAccountNode(Array[Byte](7), garbage)
    val s = stub(Some(pns))

    s.readPathVerifiedNode(ByteString(node.hash), Seq(compact(6))).map(n => ByteString(n.hash)) shouldBe
      Some(ByteString(node.hash))
    s.readPathVerifiedNode(ByteString(kec256(garbage)), Seq(compact(7))) shouldBe None
  }

  "HealVerification.reverifyCurrentRoot" should "walk the current root directly when nothing is pending, flushing or walking" taggedAs UnitTest in {
    val s = stub(None, StorageScheme.Hash)

    s.reverifyCurrentRoot()

    s.singleSeedLaunches.toSeq shouldBe Seq((s.stateRoot, emptyPath, false))
    s.verificationBFSRunning shouldBe true
    s.verificationPassComplete shouldBe false
    s.scopedVerificationActive shouldBe false
    s.prunedVerificationActive shouldBe false
    s.prunedSubtreeCount.get() shouldBe 0L
    s.selfInbox.hasMessages shouldBe false

    s.lastOnComplete.foreach(_(7L, 3L))
    s.selfInbox.receiveAll() shouldBe Seq(VerificationBFSComplete(s.stateRoot, 3L, 7L))
  }

  it should "defer to the completion gate when work is pending, a flush is in flight or a walk is running" taggedAs UnitTest in {
    for blocker <- Seq("pending", "flushing", "trieWalk", "verificationWalk") do
      val s = stub(None, StorageScheme.Hash)
      blocker match
        case "pending"  => s.isComplete = false
        case "flushing" => s.flushing = true
        case "trieWalk" => s.trieWalkInProgress = true
        case _          => s.verificationBFSRunning = true

      s.reverifyCurrentRoot()

      withClue(blocker) {
        s.singleSeedLaunches shouldBe empty
        s.multiSeedLaunches shouldBe empty
        s.verificationPassComplete shouldBe false
        s.scopedVerificationActive shouldBe false
        s.prunedVerificationActive shouldBe false
        s.selfInbox.receiveAll() shouldBe Seq(HealingCheckCompletion)
      }
  }

  it should "start no walk while the flush buffer cannot be written, and leave the retry to the completion gate (forge F1)" taggedAs UnitTest in {
    val s = stub(None, StorageScheme.Hash)
    s.bufferWritten = false

    s.reverifyCurrentRoot()

    s.walkFlushes shouldBe 1
    s.singleSeedLaunches shouldBe empty
    s.verificationBFSRunning shouldBe false
    s.verificationPassComplete shouldBe false
    s.selfInbox.receiveAll() shouldBe Seq(HealingCheckCompletion)
  }

  "HealVerification's walk launchers" should "write the flush buffer before every verification walk, full or scoped, and start none while it cannot be written (forge F1)" taggedAs UnitTest in {
    val s = stub(None, StorageScheme.Hash)
    val seeds = Seq(HealingEntry(Seq(emptyPath), ByteString(Array.fill[Byte](32)(5))))
    s.healedPathsRoot = s.stateRoot

    s.bufferWritten = false
    s.startVerificationBFS(s.stateRoot, emptyPath)
    s.startScopedVerification(seeds)
    s.walkFlushes shouldBe 2
    s.singleSeedLaunches shouldBe empty
    s.multiSeedLaunches shouldBe empty
    s.verificationBFSRunning shouldBe false
    s.scopedVerificationActive shouldBe true // untouched: no scoped run was started
    s.selfInbox.receiveAll() shouldBe Seq(HealingCheckCompletion, HealingCheckCompletion)

    s.bufferWritten = true
    s.startScopedVerification(seeds)
    s.walkFlushes shouldBe 3
    s.multiSeedLaunches.toSeq shouldBe Seq(Seq((seeds.head.hash, seeds.head.pathset, false)))
    s.verificationBFSRunning shouldBe true
  }

  "HealVerification.startVerificationBFS" should "engage the pruned walk only when pruning is enabled" taggedAs UnitTest in {
    val root = ByteString(Array.fill[Byte](32)(3))
    val rootPath = compact(1)
    for pruned <- Seq(true, false) do
      val s = stub(None, StorageScheme.Hash, pruned)

      s.startVerificationBFS(root, rootPath)

      withClue(s"pruned=$pruned") {
        s.singleSeedLaunches.toSeq shouldBe Seq((root, rootPath, false))
        s.prunedVerificationActive shouldBe pruned
        s.prunedSubtreeCount.get() shouldBe 0L
        s.scopedVerificationActive shouldBe false
        s.verificationBFSRunning shouldBe true
        s.lastOnComplete.foreach(_(7L, 0L))
        s.selfInbox.receiveAll() shouldBe Seq(VerificationBFSComplete(root, 0L, 7L))
      }
  }

  "HealVerification.startScopedVerification" should "refuse healed paths captured against another root (beacon contract)" taggedAs UnitTest in {
    val s = stub(None, StorageScheme.Hash)
    s.healedPathsRoot = ByteString(Array.fill[Byte](32)(9))
    an[IllegalArgumentException] should be thrownBy
      s.startScopedVerification(Seq(HealingEntry(Seq(compact(2)), ByteString(Array.fill[Byte](32)(1)))))
    s.multiSeedLaunches shouldBe empty
  }

  it should "seed the walk from the healed entries and complete against its launch root" taggedAs UnitTest in {
    val accountHash = ByteString(Array.fill[Byte](32)(5))
    val accountEntry = HealingEntry(Seq(compact(2)), ByteString(Array.fill[Byte](32)(1)))
    val storageEntry = HealingEntry(Seq(accountHash, emptyPath), ByteString(Array.fill[Byte](32)(2)))
    val s = stub(None, StorageScheme.Hash)
    val launchRoot = s.stateRoot

    s.startScopedVerification(Seq(accountEntry, storageEntry))

    s.multiSeedLaunches.toSeq shouldBe Seq(
      Seq(
        (accountEntry.hash, accountEntry.pathset, false),
        (storageEntry.hash, storageEntry.pathset, true)
      )
    )
    s.scopedVerificationActive shouldBe true
    s.scopedVerificationSeedCount shouldBe 2
    s.verificationBFSRunning shouldBe true

    s.stateRoot = ByteString(Array.fill[Byte](32)(8))
    s.lastOnComplete.foreach(_(7L, 1L))
    s.selfInbox.receiveAll() shouldBe Seq(VerificationBFSComplete(launchRoot, 1L, 7L))
  }
