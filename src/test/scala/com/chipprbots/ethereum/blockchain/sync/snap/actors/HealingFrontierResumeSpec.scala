package com.chipprbots.ethereum.blockchain.sync.snap.actors

import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.util.ByteString

import scala.concurrent.ExecutionContext

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

/** spec 022 D6: a restart re-seeds from the current root. What an older binary left in CF `g` (a persisted frontier,
  * its completeness marker, subtree-complete records) is not read, whatever it says, and nothing is written there. Over
  * a real (temp-dir) RocksDB, since the store's contents are what must make no difference.
  *
  * Teardown discipline (critical): the walk runs on the supplied EC. The fixture stops the actor AND drains that EC
  * before destroying the DataSource, so nothing can touch a freed column-family handle (native SIGSEGV).
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

  /** Owns the RocksDB store, a dedicated single-thread EC for the coordinator's walks and flushes, and the coordinator
    * actor. Tears down in the only safe order: stop actor → drain EC → destroy DataSource → delete temp dir.
    */
  private def withResumeFixture(
      prePopulate: Seq[(ByteString, Seq[ByteString])] = Nil,
      markComplete: Boolean = false
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
    val root = storedRoot(storage)
    val coordinator = HealingTrieFixtures.spawnCoordinator(
      stateRoot = root,
      networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]().ref,
      requestTracker = new SNAPRequestTracker()(classicSystem.scheduler),
      mptStorage = storage,
      batchSize = 16,
      snapSyncController = controllerProbe.ref,
      healingFrontierStorage = Some(store), // wired in as production wires it: what must make no difference
      healingWriterEcOverride = Some(ec)
    )
    try body(coordinator, root, store, controllerProbe)
    finally
      // 1) No more actor-thread RocksDB ops. 2) Drain the EC.
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

  private val leftBehind = (0 until 7).map(i => hash(i) -> pathset(i))

  "A restart" should "walk from the current root, not resume a complete frontier an older binary persisted" taggedAs UnitTest in
    withResumeFixture(prePopulate = leftBehind, markComplete = true) { (coordinator, root, _, controller) =>
      coordinator ! TrieNodeHealingCoordinator.StartTrieNodeHealing(root)
      // Resuming would queue the 7 entries, none of which the root needs. The walk of this childless root finds nothing.
      controller.expectMessage(SNAPSyncController.StateHealingComplete)
      pendingTasks(coordinator) shouldBe 0
    }

  it should "walk from the current root over a partial frontier an older binary persisted" taggedAs UnitTest in
    withResumeFixture(prePopulate = leftBehind) { (coordinator, root, _, controller) =>
      coordinator ! TrieNodeHealingCoordinator.StartTrieNodeHealing(root)
      controller.expectMessage(SNAPSyncController.StateHealingComplete)
      pendingTasks(coordinator) shouldBe 0
    }

  it should "walk from the current root over an empty column family" taggedAs UnitTest in
    withResumeFixture() { (coordinator, root, _, controller) =>
      coordinator ! TrieNodeHealingCoordinator.StartTrieNodeHealing(root)
      controller.expectMessage(SNAPSyncController.StateHealingComplete)
    }

  it should "leave what an older binary persisted as it found it: only the one-time clear touches the family" taggedAs UnitTest in
    withResumeFixture(prePopulate = leftBehind, markComplete = true) { (coordinator, root, store, controller) =>
      coordinator ! TrieNodeHealingCoordinator.StartTrieNodeHealing(root)
      controller.expectMessage(SNAPSyncController.StateHealingComplete)
      coordinator ! TrieNodeHealingCoordinator.HealingPivotRefreshed(kec256(ByteString("a-genuinely-different-root")))
      pendingTasks(coordinator) shouldBe 1
      store.isComplete shouldBe true
      store.loadAll().map(_._1).toSet shouldBe leftBehind.map(_._1).toSet
    }
