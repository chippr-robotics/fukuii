package com.chipprbots.ethereum.blockchain.sync.snap.actors

import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.util.ByteString

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*

import org.scalatest.concurrent.Eventually
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.snap.*
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.db.dataSource.RocksDbConfig
import com.chipprbots.ethereum.db.dataSource.RocksDbDataSource
import com.chipprbots.ethereum.db.storage.HealingFrontierStorage
import com.chipprbots.ethereum.db.storage.Namespaces
import com.chipprbots.ethereum.mpt.LeafNode
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.testing.TestMptStorage

/** Layer-2 resume behaviour of [[TrieNodeHealingCoordinator]] — `sync.snap-sync.healing-frontier-persistence`.
  *
  * On `[HEAL-RESTART]` (root already in storage), a non-empty persisted frontier is loaded and the full-state BFS is
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
class HealingFrontierResumeSpec extends ScalaTestWithActorTestKit() with AnyFlatSpecLike with Matchers with Eventually:

  implicit private val classicSystem: org.apache.pekko.actor.ActorSystem = system.classicSystem
  implicit private val actorTestKit: org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit = testKit

  private def hash(i: Int): ByteString = kec256(ByteString(s"frontier-entry-$i"))
  private def pathset(i: Int): Seq[ByteString] = Seq(ByteString(Array[Byte](0x20, i.toByte)))

  /** A trivially-present root node so `StartTrieNodeHealing` takes the restart (resume/BFS) branch. A childless leaf ⇒
    * the fallback BFS discovers nothing ⇒ pendingTasks stays 0 unless resume populated it.
    */
  private def storedRoot(storage: TestMptStorage): ByteString =
    val leaf = LeafNode(ByteString(1), ByteString(1))
    storage.putNode(leaf)
    ByteString(leaf.hash)

  private def deleteRecursively(f: File): Unit =
    Option(f.listFiles()).foreach(_.foreach(deleteRecursively))
    f.delete()
    ()

  /** Owns the RocksDB store, a dedicated single-thread EC for the coordinator's resume/flush `Future`s, and the
    * coordinator actor. Tears down in the only safe order: stop actor → drain EC (resume `loadAll` finished) → destroy
    * DataSource → delete temp dir.
    */
  private def withResumeFixture(
      persistence: Boolean,
      prePopulate: Seq[(ByteString, Seq[ByteString])] = Nil,
      markComplete: Boolean = false,
      rootInStorage: Boolean = true
  )(
      body: (
          ActorRef[TrieNodeHealingCoordinator.Command],
          ByteString,
          HealingFrontierStorage,
          org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe[SNAPSyncController.Command]
      ) => Unit
  ): Unit =
    val pool = Executors.newSingleThreadExecutor()
    val ec = ExecutionContext.fromExecutorService(pool)
    val dbPath = Files.createTempDirectory("healing-frontier-resume-rocksdb").toAbsolutePath.toString
    val dataSource = RocksDbDataSource(
      new RocksDbConfig:
        override val createIfMissing: Boolean = true
        override val paranoidChecks: Boolean = true
        override val path: String = dbPath
        override val maxThreads: Int = 1
        override val maxOpenFiles: Int = 32
        override val verifyChecksums: Boolean = true
        override val levelCompaction: Boolean = true
        override val blockSize: Long = 16384
        override val blockCacheSize: Long = 33554432
      ,
      Namespaces.nsSeq
    )
    val store = new HealingFrontierStorage(dataSource)
    if prePopulate.nonEmpty then store.update(Nil, prePopulate).commit()
    if markComplete then store.markComplete() // simulate a prior rebuild BFS that ran to completion

    val controllerProbe = testKit.createTestProbe[SNAPSyncController.Command]()
    val storage = new TestMptStorage()
    val root = if rootInStorage then storedRoot(storage) else kec256(ByteString("write-on-queue-root"))
    val coordinator = HealingTrieFixtures.spawnCoordinator(
      stateRoot = root,
      networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]().ref,
      requestTracker = new SNAPRequestTracker()(classicSystem.scheduler),
      mptStorage = storage,
      batchSize = 16,
      snapSyncController = controllerProbe.ref,
      healingFrontierStorage = if persistence then Some(store) else None,
      // spec 005 decoupling: the coordinator gates the Layer-2 frontier-mirror writes + completeness
      // markers on `frontierPersistenceEnabled` (default OFF), separately from store PRESENCE. This
      // suite exercises exactly those persistence behaviours, so the flag tracks `persistence` here
      // (in production it is wired from sync.conf's healing-frontier-persistence). Without it the
      // write-on-queue + marker set/clear specs go dark. (#1384 dropped this arg; restored.)
      frontierPersistenceEnabled = persistence,
      healingWriterEcOverride = Some(ec)
    )
    try body(coordinator, root, store, controllerProbe)
    finally
      // 1) No more actor-thread RocksDB ops. 2) Drain the EC so the resume `loadAll` iterator is closed.
      testKit.stop(coordinator)
      pool.shutdown()
      pool.awaitTermination(5, TimeUnit.SECONDS)
      // 3) Now nothing references the store — safe to free the native handles.
      dataSource.destroy()
      deleteRecursively(new File(dbPath))

  private def pendingTasks(coordinator: ActorRef[TrieNodeHealingCoordinator.Command]): Int =
    // Dedicated probe per query: the shared ImplicitSender inbox steals replies across tests when
    // the suite runs with test parallelism — one test's awaitAssert can consume another test's
    // HealingStatistics (observed as a deterministic-looking "0 was not equal to 7").
    val probe = testKit.createTestProbe[HealingStatistics]()
    coordinator ! TrieNodeHealingCoordinator.HealingGetProgress(probe.ref)
    probe.expectMessageType[HealingStatistics].pendingTasks

  "TrieNodeHealingCoordinator (Layer 2)" should
    "resume from a COMPLETE persisted frontier and skip the full-state DFS" taggedAs UnitTest in {
      val entries = (0 until 7).map(i => hash(i) -> pathset(i))
      withResumeFixture(persistence = true, prePopulate = entries, markComplete = true) { (coordinator, root, _, _) =>
        coordinator ! TrieNodeHealingCoordinator.StartTrieNodeHealing(root)
        // Resume loads the 7 persisted entries (a childless-leaf-root BFS would have found 0).
        eventually(timeout(3.seconds), interval(100.millis))(pendingTasks(coordinator) shouldBe entries.size)
      }
    }

  it should "NOT resume a partial frontier with no completeness marker — re-runs the full-state DFS" taggedAs UnitTest in {
    // A frontier persisted by an interrupted rebuild has entries but no marker. Resuming it would skip the
    // un-walked region and leave gaps; the coordinator must fall back to the full DFS instead.
    val partial = (0 until 5).map(i => hash(i) -> pathset(i))
    withResumeFixture(persistence = true, prePopulate = partial, markComplete = false) { (coordinator, root, _, _) =>
      coordinator ! TrieNodeHealingCoordinator.StartTrieNodeHealing(root)
      // No resume of the 5 partial entries; the childless-leaf-root BFS finds nothing → pendingTasks stays 0.
      eventually(timeout(2.seconds), interval(100.millis))(pendingTasks(coordinator) shouldBe 0)
    }
  }

  it should "fall back to the full-state DFS when persistence is disabled (Layer-1 parity)" taggedAs UnitTest in {
    // Store HAS entries, but the coordinator is wired with None — they must be ignored.
    withResumeFixture(persistence = false, prePopulate = (0 until 5).map(i => hash(i) -> pathset(i))) {
      (coordinator, root, _, _) =>
        coordinator ! TrieNodeHealingCoordinator.StartTrieNodeHealing(root)
        // No resume; the childless-leaf-root BFS finds nothing. Contrast with the resume test (reaches 7).
        eventually(timeout(2.seconds), interval(100.millis))(pendingTasks(coordinator) shouldBe 0)
    }
  }

  it should "fall back to the full-state DFS when the persisted frontier is empty" taggedAs UnitTest in
    withResumeFixture(persistence = true) { (coordinator, root, _, _) =>
      coordinator ! TrieNodeHealingCoordinator.StartTrieNodeHealing(root)
      eventually(timeout(2.seconds), interval(100.millis))(pendingTasks(coordinator) shouldBe 0)
    }

  it should "skip the walk and complete via verification when the snapshot is complete and the frontier empty" taggedAs UnitTest in {
    // Marker set + zero entries = the prior rebuild finished AND everything it found was healed
    // (entries are unpersisted on heal). The old gate required loaded.nonEmpty and fell through to a
    // full re-walk (~24-36h at mainnet scale). The fix skips the rebuild and runs the verification
    // pass directly; on the childless-leaf root it finds nothing and completion flows to the
    // controller — under the old behavior nothing reaches the controller until the watchdog era.
    withResumeFixture(persistence = true, markComplete = true) { (coordinator, root, _, controller) =>
      coordinator ! TrieNodeHealingCoordinator.StartTrieNodeHealing(root)
      controller.expectMessage(SNAPSyncController.StateHealingComplete)
    }
  }

  it should "mirror newly-queued nodes into the persisted frontier (write-on-queue)" taggedAs UnitTest in
    withResumeFixture(persistence = true, rootInStorage = false) { (coordinator, _, store, _) =>
      val queued = (10 until 16).map(i => pathset(i) -> hash(i))
      coordinator ! TrieNodeHealingCoordinator.QueueMissingNodes(queued)
      // queueNodes persists synchronously on the actor thread; the store should gain the queued hashes.
      eventually(timeout(3.seconds), interval(100.millis)) {
        val persisted = store.loadAll().map(_._1).toSet
        queued.map(_._2).foreach(h => persisted should contain(h))
      }
    }

  // ---- US1 (spec 002): completeness-marker set/clear semantics ----

  it should "SET the completeness marker after a fresh full-state walk completes (FR-002) so a restart can skip" taggedAs UnitTest in
    // Fresh heal: no marker, empty frontier, childless-leaf root in storage → the coordinator runs the
    // full-state rebuild walk which, finding nothing missing, persists the completeness marker. The
    // user-visible FR-002 outcome is that a completed heal is recorded as complete so the next restart
    // skips the walk (the resume-skip tests above prove the skip fires once the marker is set). The
    // verification-path set-site (HealingCheckCompletion gated on verificationPassComplete) is exercised
    // by "skip the walk and complete via verification ..." above and proven invariant-safe by review.
    withResumeFixture(persistence = true, markComplete = false) { (coordinator, root, store, _) =>
      store.isComplete shouldBe false
      coordinator ! TrieNodeHealingCoordinator.StartTrieNodeHealing(root)
      eventually(timeout(5.seconds), interval(100.millis))(store.isComplete shouldBe true)
    }

  it should "PRESERVE the completeness marker on a same-root pivot refresh (FR-003 no-op)" taggedAs UnitTest in
    // A pivot refresh to the SAME state root must not wipe a valid marker: the new guard short-circuits
    // before clearPersistedFrontier(). Previously the unconditional clear dropped the marker on every
    // refresh (frequent on peer-scarce mainnet), silently defeating the skip-on-restart.
    withResumeFixture(persistence = true, markComplete = true) { (coordinator, root, store, _) =>
      store.isComplete shouldBe true
      coordinator ! TrieNodeHealingCoordinator.HealingPivotRefreshed(root) // same root as props stateRoot
      // The guard is synchronous on the actor thread; give the mailbox a moment and confirm it stayed set.
      eventually(timeout(2.seconds), interval(100.millis))(store.isComplete shouldBe true)
    }

  it should "CLEAR the completeness marker on a different-root pivot refresh (FR-003 genuine invalidation)" taggedAs UnitTest in
    // A refresh to a genuinely different root may invalidate already-healed subtries, so the marker is
    // dropped (conservative) and the new root reseeded for re-traversal.
    withResumeFixture(persistence = true, markComplete = true) { (coordinator, _, store, _) =>
      store.isComplete shouldBe true
      coordinator ! TrieNodeHealingCoordinator.HealingPivotRefreshed(kec256(ByteString("a-genuinely-different-root")))
      eventually(timeout(2.seconds), interval(100.millis))(store.isComplete shouldBe false)
    }
