package com.chipprbots.ethereum.blockchain.sync.snap.actors

import org.apache.pekko.actor.testkit.typed.scaladsl.BehaviorTestKit
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed.PostStop
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.util.ByteString

import scala.collection.mutable
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*

import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.snap.*
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.FlatSlotStorage
import com.chipprbots.ethereum.db.storage.PathNodeStorage
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.p2p.messages.SNAP.StorageRanges
import com.chipprbots.ethereum.testing.PeerTestHelpers
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.testing.TestMptStorage
import com.chipprbots.ethereum.utils.ByteStringUtils.ByteStringOps

/** #1533: StorageRanges responses are verified (and whole-account tries built) on a worker pool, and applied on the
  * coordinator in arrival order. These tests drive the pipeline with an ExecutionContext that runs nothing until told
  * to, so job completion order, pivot refreshes and stops are deterministic.
  */
class StorageRangeCoordinatorOffActorSpec extends ScalaTestWithActorTestKit() with AnyFlatSpecLike with Matchers:

  implicit private val classicSystem: org.apache.pekko.actor.ActorSystem = system.classicSystem

  /** Runs nothing until told to; jobs can be completed in any order. */
  final private class ManualEc extends ExecutionContext:
    val queued: mutable.ArrayBuffer[Runnable] = mutable.ArrayBuffer.empty
    def execute(runnable: Runnable): Unit = queued += runnable
    def reportFailure(cause: Throwable): Unit = throw cause
    def run(index: Int): Unit = queued.remove(index).run()
    def runAll(): Unit = while queued.nonEmpty do run(0)

  /** Records every raw node the Hash-scheme coordinator stores. */
  final private class RecordingMptStorage extends TestMptStorage:
    val stored: mutable.Map[String, String] = mutable.Map.empty
    override def storeRawNodes(rawNodes: Seq[(ByteString, Array[Byte])]): Unit =
      rawNodes.foreach { case (hash, blob) => stored(hash.toHex) = ByteString(blob).toHex }
      super.storeRawNodes(rawNodes)

  private val offActor = StorageRangeCoordinator.ProcessingSettings(
    offActor = true,
    threads = 2,
    maxInFlightJobs = 4,
    maxPendingBytes = Long.MaxValue
  )

  final private case class Fixture(
      impl: StorageRangeCoordinatorImpl,
      kit: BehaviorTestKit[StorageRangeCoordinator.Command],
      ec: ManualEc,
      tracker: SNAPRequestTracker,
      pathNodes: PathNodeStorage,
      mpt: RecordingMptStorage
  )

  private def fixture(
      processing: StorageRangeCoordinator.ProcessingSettings = offActor,
      scheme: StorageScheme = StorageScheme.Path,
      maxInFlightRequests: Int = 8,
      useOwnedPool: Boolean = false
  ): Fixture =
    val ec = new ManualEc
    val tracker = new SNAPRequestTracker()(classicSystem.scheduler)
    val pathNodes = new PathNodeStorage(EphemDataSource())
    val mpt = new RecordingMptStorage
    var captured: StorageRangeCoordinatorImpl = null
    val behavior = Behaviors.setup[StorageRangeCoordinator.Command] { ctx =>
      Behaviors.withTimers { timers =>
        captured = new StorageRangeCoordinatorImpl(
          ctx,
          timers,
          initialStateRoot = kec256(ByteString("off-actor-state-root")),
          networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]().ref,
          requestTracker = tracker,
          mptStorage = mpt,
          flatSlotStorage = new FlatSlotStorage(EphemDataSource()),
          maxAccountsPerBatch = 1, // one request per account, so responses can be reordered
          maxInFlightRequests = maxInFlightRequests,
          requestTimeout = 30.seconds,
          snapSyncController = testKit.createTestProbe[SNAPSyncController.Command]().ref,
          deferredMerkleization = false, // production default: the storage trie is built and written
          flatBatchEcOverride = Some(ExecutionContext.parasitic),
          storageScheme = scheme,
          pathNodeStorage = Some(pathNodes),
          processing = processing,
          processingEcOverride = if useOwnedPool then None else Some(ec)
        )
        captured.start()
      }
    }
    val kit = BehaviorTestKit(behavior)
    Fixture(captured, kit, ec, tracker, pathNodes, mpt)

  private def drainSelf(kit: BehaviorTestKit[StorageRangeCoordinator.Command]): Unit =
    while kit.selfInbox().hasMessages do kit.runOne()

  /** `n` slots in ascending key order. */
  private def slotsFor(seed: String, n: Int): Seq[(ByteString, ByteString)] =
    (0 until n)
      .map(i => kec256(ByteString(s"$seed-$i")) -> ByteString(s"value-$seed-$i"))
      .sortWith((a, b) => java.util.Arrays.compareUnsigned(a._1.toArray, b._1.toArray) < 0)

  private def rootOf(slots: Seq[(ByteString, ByteString)]): ByteString =
    val trie = new SnapHashTrie(_ => ())
    slots.foreach { case (k, v) => trie.update(k.toArray, v.toArray) }
    trie.commit()

  private val peer: Peer =
    PeerTestHelpers.createTestPeer("off-actor-peer", testKit.createTestProbe[Any]().ref.toClassic)

  /** Queue whole-account tasks and dispatch them; returns the request id of each account. */
  private def dispatch(
      f: Fixture,
      accounts: Seq[(ByteString, Seq[(ByteString, ByteString)])]
  ): Map[ByteString, BigInt] =
    f.kit.run(
      StorageRangeCoordinator.AddStorageTasks(accounts.map { case (a, slots) =>
        StorageTask.createStorageTask(a, rootOf(slots))
      })
    )
    f.kit.run(StorageRangeCoordinator.StoragePeerAvailable(peer))
    f.impl.activeTasks.map { case (requestId, (_, tasks, _)) => tasks.head.accountHash -> requestId }.toMap

  private def respond(f: Fixture, requestId: BigInt, slots: Seq[(ByteString, ByteString)]): Unit =
    f.kit.run(StorageRangeCoordinator.StorageRangesResponseMsg(StorageRanges(requestId, Seq(slots), Seq.empty)))

  private def storedPathNodes(pathNodes: PathNodeStorage): Seq[(String, String)] =
    val all = mutable.ArrayBuffer.empty[(String, String)]
    pathNodes.foreachStorageNodeChunk(1000)(chunk =>
      all ++= chunk.map { case (k, v) => (ByteString(k).toHex, ByteString(v).toHex) }
    )
    all.toSeq.sorted

  "StorageRangeCoordinator off-actor processing" should "apply responses in arrival order when their jobs finish out of order" taggedAs UnitTest in {
    val f = fixture()
    val accountA = kec256(ByteString("account-a"))
    val accountB = kec256(ByteString("account-b"))
    val slotsA = slotsFor("a", 20)
    val slotsB = slotsFor("b", 30)
    val ids = dispatch(f, Seq(accountA -> slotsA, accountB -> slotsB))

    respond(f, ids(accountA), slotsA)
    respond(f, ids(accountB), slotsB)
    f.ec.queued.size shouldBe 2
    f.impl.pendingResponses.size shouldBe 2
    // Answered: the requests are complete although nothing has been applied yet (#1531 reply-before-timeout).
    f.impl.activeTasks shouldBe empty
    f.tracker.isPending(ids(accountA)) shouldBe false

    f.ec.run(1) // B's job finishes first
    drainSelf(f.kit)
    f.impl.completedAccountCount shouldBe 0L // B waits for A
    f.impl.pendingResponses.map(_.tasks.head.accountHash).toSeq shouldBe Seq(accountA, accountB)

    f.ec.run(0)
    drainSelf(f.kit)
    f.impl.completedAccountCount shouldBe 2L
    f.impl.pendingResponses shouldBe empty
    f.impl.pendingFlatBatchAccounts.map(_._1).toSeq shouldBe Seq(accountA, accountB)
    f.impl.runningProcessingJobs shouldBe 0
    f.impl.pendingResponseBytes shouldBe 0L
  }

  it should "not time out a request whose reply is still being processed" taggedAs UnitTest in {
    val f = fixture()
    val account = kec256(ByteString("timeout-account"))
    val slots = slotsFor("t", 10)
    val ids = dispatch(f, Seq(account -> slots))
    respond(f, ids(account), slots)

    f.kit.run(StorageRangeCoordinator.StorageRequestTimedOut(ids(account)))
    f.impl.peerHealth.consecutiveTimeouts(peer.id.value) shouldBe 0
    f.impl.tasks.exists(_.accountHash == account) shouldBe false

    f.ec.runAll()
    drainSelf(f.kit)
    f.impl.completedAccountCount shouldBe 1L
  }

  it should "drop a result of a superseded generation and re-queue its tasks on pivot refresh" taggedAs UnitTest in {
    val f = fixture()
    val account = kec256(ByteString("refresh-account"))
    val slots = slotsFor("r", 25)
    val ids = dispatch(f, Seq(account -> slots))
    respond(f, ids(account), slots)
    f.impl.pendingResponses.size shouldBe 1

    f.kit.run(StorageRangeCoordinator.StoragePivotRefreshed(kec256(ByteString("refreshed-root"))))
    f.impl.pendingResponses shouldBe empty
    f.impl.pendingResponseBytes shouldBe 0L
    f.impl.tasks.count(_.accountHash == account) shouldBe 1

    f.ec.runAll() // the old job finishes after the refresh
    drainSelf(f.kit)
    f.impl.runningProcessingJobs shouldBe 0
    f.impl.completedAccountCount shouldBe 0L
    f.impl.pendingFlatBatchAccounts shouldBe empty
    storedPathNodes(f.pathNodes) shouldBe empty // nothing written for the superseded root
  }

  it should "leave nothing behind when stopped with jobs in flight" taggedAs UnitTest in {
    val f = fixture(useOwnedPool = true)
    f.impl.processingPoolShutDown shouldBe Some(false)
    val account = kec256(ByteString("stop-account"))
    val slots = slotsFor("s", 15)
    val ids = dispatch(f, Seq(account -> slots))
    respond(f, ids(account), slots)

    f.kit.signal(PostStop)
    f.impl.pendingResponses shouldBe empty
    f.impl.pendingResponseBytes shouldBe 0L
    f.impl.processingPoolShutDown shouldBe Some(true)
    storedPathNodes(f.pathNodes) shouldBe empty
  }

  it should "count unapplied responses against the in-flight request budget" taggedAs UnitTest in {
    val f = fixture(maxInFlightRequests = 2)
    val accounts = (0 until 4).map(i => kec256(ByteString(s"budget-$i")) -> slotsFor(s"budget-$i", 5))
    val ids = dispatch(f, accounts)
    ids.size shouldBe 2 // the global cap

    val (first, firstSlots) = accounts.find(a => ids.contains(a._1)).get
    respond(f, ids(first), firstSlots)
    // Answered but not applied: it still holds its slot, so nothing new is dispatched.
    f.impl.activeTasks.size shouldBe 1
    f.impl.pendingResponses.size shouldBe 1
    f.impl.pendingResponseBytes shouldBe StorageRangeCoordinator.responsePayloadBytes(
      StorageRanges(ids(first), Seq(firstSlots), Seq.empty)
    )

    f.ec.runAll()
    drainSelf(f.kit)
    f.impl.pendingResponses shouldBe empty
    f.impl.pendingResponseBytes shouldBe 0L
    f.impl.runningProcessingJobs shouldBe 0
    f.impl.activeTasks.size shouldBe 2 // the freed slot was used again
  }

  it should "write byte-identical Path-scheme storage nodes whether processed inline or off the actor" taggedAs UnitTest in {
    val accounts = Seq(
      kec256(ByteString("ident-1")) -> slotsFor("ident-1", 1),
      kec256(ByteString("ident-2")) -> slotsFor("ident-2", 7),
      kec256(ByteString("ident-3")) -> slotsFor("ident-3", 300)
    )
    def run(processing: StorageRangeCoordinator.ProcessingSettings): Fixture =
      val f = fixture(processing = processing)
      val ids = dispatch(f, accounts)
      accounts.foreach { case (a, slots) => respond(f, ids(a), slots) }
      f.ec.runAll()
      drainSelf(f.kit)
      f.impl.completedAccountCount shouldBe accounts.size.toLong
      f
    val inline = run(StorageRangeCoordinator.ProcessingSettings.Inline)
    val worker = run(offActor)
    val nodes = storedPathNodes(worker.pathNodes)
    nodes should not be empty
    nodes shouldBe storedPathNodes(inline.pathNodes)
  }

  it should "store byte-identical Hash-scheme storage nodes whether processed inline or off the actor" taggedAs UnitTest in {
    val accounts = Seq(
      kec256(ByteString("hash-1")) -> slotsFor("hash-1", 3),
      kec256(ByteString("hash-2")) -> slotsFor("hash-2", 200)
    )
    def run(processing: StorageRangeCoordinator.ProcessingSettings): Fixture =
      val f = fixture(processing = processing, scheme = StorageScheme.Hash)
      val ids = dispatch(f, accounts)
      accounts.foreach { case (a, slots) => respond(f, ids(a), slots) }
      f.ec.runAll()
      drainSelf(f.kit)
      f.impl.completedAccountCount shouldBe accounts.size.toLong
      f
    val inline = run(StorageRangeCoordinator.ProcessingSettings.Inline)
    val worker = run(offActor)
    worker.mpt.stored should not be empty
    worker.mpt.stored.toMap shouldBe inline.mpt.stored.toMap
  }

  "verifyServedSlots" should "verify every served account and pre-build only whole accounts" taggedAs UnitTest in {
    val good = slotsFor("good", 12)
    val bad = slotsFor("bad", 4)
    val goodTask = StorageTask.createStorageTask(kec256(ByteString("good")), rootOf(good))
    val badTask = StorageTask.createStorageTask(kec256(ByteString("bad")), kec256(ByteString("wrong-root")))
    val skipped = StorageTask.createStorageTask(kec256(ByteString("skipped")), rootOf(good))
    val response = StorageRanges(BigInt(1), Seq(good, bad, good), Seq.empty)

    val result = StorageRangeCoordinator.verifyServedSlots(
      Seq(goodTask, badTask, skipped),
      response,
      skipAccounts = Set(skipped.accountHash),
      prebuildScheme = Some(StorageScheme.Path)
    )
    result.verifications.size shouldBe 3
    result.verifications(0) shouldBe Some(Right(()))
    result.verifications(1) shouldBe Some(Left("complete-range hash mismatch"))
    result.verifications(2) shouldBe None
    result.prebuilt.keySet shouldBe Set(0)
    result.prebuilt(0).root shouldBe goodTask.storageRoot
  }

  "indexedResponse" should "keep the response's content" taggedAs UnitTest in {
    val slots = slotsFor("idx", 5)
    val response = StorageRanges(BigInt(7), Seq(slots, Seq.empty), Seq(ByteString("p")))
    val indexed = StorageRangeCoordinator.indexedResponse(response)
    indexed shouldBe response
    indexed.slots.head shouldBe a[Vector[?]]
  }
