package com.chipprbots.ethereum.blockchain.sync.snap.actors

import org.apache.pekko.actor.{ActorRef, ActorSystem}
import org.apache.pekko.testkit.{ImplicitSender, TestKit, TestProbe}
import org.apache.pekko.util.ByteString

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.snap._
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.db.dataSource.{RocksDbConfig, RocksDbDataSource}
import com.chipprbots.ethereum.db.storage.{HealingFrontierStorage, Namespaces}
import com.chipprbots.ethereum.mpt.LeafNode
import com.chipprbots.ethereum.testing.Tags._
import com.chipprbots.ethereum.testing.TestMptStorage

import java.io.File
import java.nio.file.Files
import java.util.concurrent.{Executors, TimeUnit}

/** Layer-2 resume behaviour of [[TrieNodeHealingCoordinator]] — `sync.snap-sync.healing-frontier-persistence`.
  *
  * On `[HEAL-RESTART]` (root already in storage), a non-empty persisted frontier is loaded and the full-state DFS is
  * skipped; an empty/absent/disabled frontier falls back to the provably-complete walk. New enqueues are mirrored to
  * the persisted store. See docs/design/healing-frontier-scale.md (Layer 2).
  *
  * Storage is backed by a real (temp-dir) RocksDB because resume relies on `loadAll()` (namespace iteration) returning
  * bare hash keys, which `EphemDataSource` does not. Delete-on-heal and idempotent resume are covered by
  * HealingFrontierStorageSpec (storage round-trip) plus operational validation.
  *
  * Teardown discipline (critical): the resume runs as a `Future` on the supplied EC and opens a RocksDB iterator. The
  * fixture stops the actor (await termination) AND drains that EC before destroying the DataSource, so an in-flight
  * `loadAll` can never `newIterator` on a freed column-family handle (native SIGSEGV).
  */
class HealingFrontierResumeSpec
    extends TestKit(ActorSystem("HealingFrontierResumeSpec"))
    with ImplicitSender
    with AnyFlatSpecLike
    with Matchers
    with BeforeAndAfterAll {

  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)

  private def hash(i: Int): ByteString = kec256(ByteString(s"frontier-entry-$i"))
  private def pathset(i: Int): Seq[ByteString] = Seq(ByteString(Array[Byte](0x20, i.toByte)))

  /** A trivially-present root node so `StartTrieNodeHealing` takes the restart (resume/DFS) branch. A childless leaf ⇒
    * the fallback DFS discovers nothing ⇒ pendingTasks stays 0 unless resume populated it.
    */
  private def storedRoot(storage: TestMptStorage): ByteString = {
    val leaf = LeafNode(ByteString(1), ByteString(1))
    storage.putNode(leaf)
    ByteString(leaf.hash)
  }

  private def deleteRecursively(f: File): Unit = {
    Option(f.listFiles()).foreach(_.foreach(deleteRecursively))
    f.delete()
    ()
  }

  /** Owns the RocksDB store, a dedicated single-thread EC for the coordinator's resume/flush `Future`s, and the
    * coordinator actor. Tears down in the only safe order: stop actor → drain EC (resume `loadAll` finished) → destroy
    * DataSource → delete temp dir.
    */
  private def withResumeFixture(
      persistence: Boolean,
      prePopulate: Seq[(ByteString, Seq[ByteString])] = Nil,
      markComplete: Boolean = false,
      rootInStorage: Boolean = true
  )(body: (ActorRef, ByteString, HealingFrontierStorage, TestProbe) => Unit): Unit = {
    val pool = Executors.newSingleThreadExecutor()
    val ec = ExecutionContext.fromExecutorService(pool)
    val dbPath = Files.createTempDirectory("healing-frontier-resume-rocksdb").toAbsolutePath.toString
    val dataSource = RocksDbDataSource(
      new RocksDbConfig {
        override val createIfMissing: Boolean = true
        override val paranoidChecks: Boolean = true
        override val path: String = dbPath
        override val maxThreads: Int = 1
        override val maxOpenFiles: Int = 32
        override val verifyChecksums: Boolean = true
        override val levelCompaction: Boolean = true
        override val blockSize: Long = 16384
        override val blockCacheSize: Long = 33554432
      },
      Namespaces.nsSeq
    )
    val store = new HealingFrontierStorage(dataSource)
    if (prePopulate.nonEmpty) store.update(Nil, prePopulate).commit()
    if (markComplete) store.markComplete() // simulate a prior rebuild DFS that ran to completion

    val controllerProbe = TestProbe()
    val storage = new TestMptStorage()
    val root = if (rootInStorage) storedRoot(storage) else kec256(ByteString("write-on-queue-root"))
    val coordinator = system.actorOf(
      TrieNodeHealingCoordinator.props(
        stateRoot = root,
        networkPeerManager = TestProbe().ref,
        requestTracker = new SNAPRequestTracker()(system.scheduler),
        mptStorage = storage,
        batchSize = 16,
        snapSyncController = controllerProbe.ref,
        healingFrontierStorage = if (persistence) Some(store) else None,
        healingWriterEcOverride = Some(ec)
      )
    )
    val death = TestProbe()
    death.watch(coordinator)
    try body(coordinator, root, store, controllerProbe)
    finally {
      // 1) No more actor-thread RocksDB ops. 2) Drain the EC so the resume `loadAll` iterator is closed.
      system.stop(coordinator)
      death.expectTerminated(coordinator, 5.seconds)
      pool.shutdown()
      pool.awaitTermination(5, TimeUnit.SECONDS)
      // 3) Now nothing references the store — safe to free the native handles.
      dataSource.destroy()
      deleteRecursively(new File(dbPath))
    }
  }

  private def pendingTasks(coordinator: ActorRef): Int = {
    // Dedicated probe per query: the shared ImplicitSender inbox steals replies across tests when
    // the suite runs with test parallelism — one test's awaitAssert can consume another test's
    // HealingStatistics (observed as a deterministic-looking "0 was not equal to 7").
    val probe = TestProbe()
    coordinator.tell(Messages.HealingGetProgress, probe.ref)
    probe.expectMsgType[HealingStatistics](2.seconds).pendingTasks
  }

  "TrieNodeHealingCoordinator (Layer 2)" should
    "resume from a COMPLETE persisted frontier and skip the full-state DFS" taggedAs UnitTest in {
      val entries = (0 until 7).map(i => hash(i) -> pathset(i))
      withResumeFixture(persistence = true, prePopulate = entries, markComplete = true) { (coordinator, root, _, _) =>
        coordinator ! Messages.StartTrieNodeHealing(root)
        // Resume loads the 7 persisted entries (a childless-leaf-root DFS would have found 0).
        awaitAssert(pendingTasks(coordinator) shouldBe entries.size, 3.seconds, 100.millis)
      }
    }

  it should "NOT resume a partial frontier with no completeness marker — re-runs the full-state DFS" taggedAs UnitTest in {
    // A frontier persisted by an interrupted rebuild has entries but no marker. Resuming it would skip the
    // un-walked region and leave gaps; the coordinator must fall back to the full DFS instead.
    val partial = (0 until 5).map(i => hash(i) -> pathset(i))
    withResumeFixture(persistence = true, prePopulate = partial, markComplete = false) { (coordinator, root, _, _) =>
      coordinator ! Messages.StartTrieNodeHealing(root)
      // No resume of the 5 partial entries; the childless-leaf-root DFS finds nothing → pendingTasks stays 0.
      awaitAssert(pendingTasks(coordinator) shouldBe 0, 2.seconds, 100.millis)
    }
  }

  it should "fall back to the full-state DFS when persistence is disabled (Layer-1 parity)" taggedAs UnitTest in {
    // Store HAS entries, but the coordinator is wired with None — they must be ignored.
    withResumeFixture(persistence = false, prePopulate = (0 until 5).map(i => hash(i) -> pathset(i))) {
      (coordinator, root, _, _) =>
        coordinator ! Messages.StartTrieNodeHealing(root)
        // No resume; the childless-leaf-root DFS finds nothing. Contrast with the resume test (reaches 7).
        awaitAssert(pendingTasks(coordinator) shouldBe 0, 2.seconds, 100.millis)
    }
  }

  it should "fall back to the full-state DFS when the persisted frontier is empty" taggedAs UnitTest in
    withResumeFixture(persistence = true) { (coordinator, root, _, _) =>
      coordinator ! Messages.StartTrieNodeHealing(root)
      awaitAssert(pendingTasks(coordinator) shouldBe 0, 2.seconds, 100.millis)
    }

  it should "skip the walk and complete via verification when the snapshot is complete and the frontier empty" taggedAs UnitTest in {
    // Marker set + zero entries = the prior rebuild finished AND everything it found was healed
    // (entries are unpersisted on heal). The old gate required loaded.nonEmpty and fell through to a
    // full re-walk (~24-36h at mainnet scale). The fix skips the rebuild and runs the verification
    // pass directly; on the childless-leaf root it finds nothing and completion flows to the
    // controller — under the old behavior nothing reaches the controller until the watchdog era.
    withResumeFixture(persistence = true, markComplete = true) { (coordinator, root, _, controller) =>
      coordinator ! Messages.StartTrieNodeHealing(root)
      controller.expectMsg(10.seconds, SNAPSyncController.StateHealingComplete)
    }
  }

  it should "mirror newly-queued nodes into the persisted frontier (write-on-queue)" taggedAs UnitTest in
    withResumeFixture(persistence = true, rootInStorage = false) { (coordinator, _, store, _) =>
      val queued = (10 until 16).map(i => pathset(i) -> hash(i))
      coordinator ! Messages.QueueMissingNodes(queued)
      // queueNodes persists synchronously on the actor thread; the store should gain the queued hashes.
      awaitAssert(
        {
          val persisted = store.loadAll().map(_._1).toSet
          queued.map(_._2).foreach(h => persisted should contain(h))
        },
        3.seconds,
        100.millis
      )
    }
}
