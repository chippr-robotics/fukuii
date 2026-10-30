package com.chipprbots.ethereum.blockchain.sync.snap.actors

import org.apache.pekko.actor.testkit.typed.scaladsl.{FishingOutcomes, ScalaTestWithActorTestKit}
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.util.ByteString

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*

import org.scalatest.concurrent.Eventually
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.snap.*
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.db.dataSource.{RocksDbConfig, RocksDbDataSource}
import com.chipprbots.ethereum.db.storage.{HealingFrontierStorage, Namespaces}
import com.chipprbots.ethereum.mpt.{BranchNode, HashNode, LeafNode, MptNode, MptTraversals, NullNode}
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.p2p.messages.SNAP
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.testing.{PeerTestHelpers, TestMptStorage}

import java.io.File
import java.nio.file.Files
import java.util.concurrent.{Executors, TimeUnit}

/** spec 005 — T-3 + T-8 (FR-006/SC-004, crash safety + never-record-from-unsound-closure).
  *
  *   - T-3 (record-after-persist + descend-on-missing-record): the subtree-complete record is written ONLY after the
  *     subtree's node-bytes are durably committed (`mptStorage.persist()`), so a record can never be observable before
  *     its subtree is durable. A heal that leaves a child still missing (bytes present for X but the subtree NOT
  *     closed) produces NO record for X — so on the next verification X is DESCENDED, not pruned, and reconciles.
  *   - T-8 (D2 guard): a record is NEVER staged/written from a subtree that did not genuinely close with zero missing
  *     descendants (a still-pending or still-missing child). (The former SNAP account-fragment seed was removed as
  *     unsound; seeding is now only the heal-side inductive closure — see the note at the end of this spec.)
  *
  * Driving: the coordinator stages a candidate in `pendingSubtreeRecords` during `discoverMissingChildren` and writes
  * `markSubtreeComplete` only inside the post-`persist` flush (`writeDurableSubtreeRecords`). The final completion
  * flush is the synchronous `flushRawNodesSync` (persist → THEN record), so after `StateHealingComplete` the record is
  * durable AND the bytes are durable; the "record before bytes" interleaving is structurally impossible. Harness
  * mirrors [[TrieNodeHealingScopedVerificationSpec]]. Deterministic: `awaitAssert` / `fishForMessage`, no
  * `Thread.sleep`.
  */
class PrunedHealCrashSafetySpec extends ScalaTestWithActorTestKit() with AnyFlatSpecLike with Matchers with Eventually:

  implicit private val classicSystem: org.apache.pekko.actor.ActorSystem = system.classicSystem
  implicit private val actorTestKit: org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit = testKit

  private def emptyChildren: Array[MptNode] = Array.fill[MptNode](16)(NullNode)

  private def deleteRecursively(f: File): Unit =
    Option(f.listFiles()).foreach(_.foreach(deleteRecursively))
    f.delete()
    ()

  private def awaitStateHealingComplete(
      controller: org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe[SNAPSyncController.Command]
  ): Unit =
    controller.fishForMessage(10.seconds) {
      case SNAPSyncController.StateHealingComplete   => FishingOutcomes.complete
      case _: SNAPSyncController.ProgressNodesHealed => FishingOutcomes.continueAndIgnore
      case _                                         => FishingOutcomes.continueAndIgnore
    }

  private def openFrontier(coordinator: ActorRef[TrieNodeHealingCoordinator.Command]): Int =
    val probe = testKit.createTestProbe[HealingStatistics]()
    coordinator ! TrieNodeHealingCoordinator.HealingGetProgress(probe.ref)
    val stats = probe.expectMessageType[HealingStatistics]
    stats.pendingTasks + stats.activeTasks

  /** True iff the node's bytes are durable in `mptStorage` (a `get` that does not throw). */
  private def nodeInStorage(storage: TestMptStorage, hash: ByteString): Boolean =
    try
      storage.get(hash.toArray); true
    catch case _: Throwable => false

  /** A clean storage-trie leaf (no children → genuine zero-missing closure ⇒ a subtree-complete candidate). */
  private def cleanLeaf(seed: Int): (Seq[ByteString], ByteString, ByteString) =
    val leaf =
      LeafNode(ByteString(Array[Byte](0x01)), ByteString(kec256(ByteString(s"crash-clean-leaf-$seed")).toArray))
    val encoded = MptTraversals.encodeNode(leaf)
    val hash = kec256(ByteString(encoded))
    val accountHash = kec256(ByteString(s"crash-clean-account-$seed"))
    (Seq(accountHash, ByteString(Array[Byte](0x20, seed.toByte))), hash, ByteString(encoded))

  /** A storage-trie BRANCH whose only child is MISSING (not in storage). Healing it commits X's bytes but does NOT
    * close X's subtree (a child remains missing). Returns (pathset, hash, encoded, missingChildHash).
    */
  private def branchWithMissingChild(seed: Int): (Seq[ByteString], ByteString, ByteString, ByteString) =
    val missingChild = kec256(ByteString(s"crash-gap-missing-child-$seed"))
    val children = emptyChildren
    children(3) = HashNode(missingChild.toArray)
    val branch = BranchNode(children, None)
    val encoded = MptTraversals.encodeNode(branch)
    val hash = kec256(ByteString(encoded))
    val accountHash = kec256(ByteString(s"crash-gap-account-$seed"))
    (Seq(accountHash, ByteString(Array[Byte](0x20, seed.toByte))), hash, ByteString(encoded), missingChild)

  /** `writerEc`: where the healed-node flushes run; the default is a single-thread pool. A case asserting a node is NOT
    * on disk passes `ExecutionContext.parasitic`, so a flush (a regression that wrote the node) completes inside the
    * message that started it, before the case's mailbox barrier returns (spec 022 review eye F6, forge N8).
    */
  private def withFixture(
      stateRoot: ByteString,
      storage: TestMptStorage,
      writerEc: Option[ExecutionContext] = None
  )(
      body: (
          ActorRef[TrieNodeHealingCoordinator.Command],
          HealingFrontierStorage,
          org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe[SNAPSyncController.Command]
      ) => Unit
  ): Unit =
    val pool = Executors.newSingleThreadExecutor()
    val ec = ExecutionContext.fromExecutorService(pool)
    val dbPath = Files.createTempDirectory("pruned-crash-rocksdb").toAbsolutePath.toString
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
    store.markComplete()

    val controller = testKit.createTestProbe[SNAPSyncController.Command]()
    val coordinator = HealingTrieFixtures.spawnCoordinator(
      stateRoot = stateRoot,
      networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]().ref,
      requestTracker = new SNAPRequestTracker()(classicSystem.scheduler),
      mptStorage = storage,
      batchSize = 64,
      snapSyncController = controller.ref,
      healingFrontierStorage = Some(store),
      healingWriterEcOverride = Some(writerEc.getOrElse(ec))
    )
    try body(coordinator, store, controller)
    finally
      testKit.stop(coordinator)
      pool.shutdown()
      pool.awaitTermination(5, TimeUnit.SECONDS)
      dataSource.destroy()
      deleteRecursively(new File(dbPath))

  private def storedRoot(storage: TestMptStorage): ByteString =
    val leaf = LeafNode(ByteString(Array[Byte](0x01)), ByteString(Array[Byte](0x02)))
    storage.putNode(leaf)
    ByteString(leaf.hash)

  // ── T-3: record written only after the subtree's bytes are durable ──────────────────────────────

  // spec 005 C3b — WIRED in the #1374 typed port (batch-5). `discoverMissingChildren` now stages a just-healed node's
  // own hash into `pendingSubtreeRecords` when its subtree is durably closed by induction (every hash child present AND
  // itself recorded subtree-complete); the durable record is written post-`persist()` by `writeDurableSubtreeRecords`
  // (D3 record-after-persist). A clean leaf (no children) is the vacuous inductive BASE — recorded as soon as its bytes
  // flush — so isSubtreeComplete(leaf) is observable after completion and only ever after the bytes are durable.
  "Crash safety (T-3, FR-006)" should
    "record a clean subtree complete only AFTER its bytes are durable — never observable before" taggedAs UnitTest in {
      val storage = new TestMptStorage()
      val root = storedRoot(storage)
      val (pathset, hash, encoded) = cleanLeaf(0)

      withFixture(root, storage) { (coordinator, store, controller) =>
        // Before any heal: the leaf is neither in storage nor recorded.
        nodeInStorage(storage, hash) shouldBe false
        store.isSubtreeComplete(hash) shouldBe false

        val peer = PeerTestHelpers.createTestPeer("crash-clean-peer", testKit.createTestProbe[Any]().ref.toClassic)
        coordinator ! TrieNodeHealingCoordinator.QueueMissingNodes(Seq((pathset, hash)))
        coordinator ! TrieNodeHealingCoordinator.HealingPeerAvailable(peer)
        coordinator ! TrieNodeHealingCoordinator.TrieNodesResponseMsg(
          SNAP.TrieNodes(requestId = 1, nodes = Seq(encoded))
        )

        awaitStateHealingComplete(controller)
        // After completion: the bytes are durable (flushed via persist) AND the record exists. The record is written
        // by `writeDurableSubtreeRecords`, which runs strictly AFTER `mptStorage.persist()` (record-after-persist,
        // D3/T012). So whenever the record is observable, the bytes are already durable.
        nodeInStorage(storage, hash) shouldBe true
        store.isSubtreeComplete(hash) shouldBe true
        // STRUCTURAL INVARIANT (enforced in flushRawNodesSync/Async: persist → then markSubtreeComplete): there is no
        // code path that writes the record before persist returns, so the "record present, bytes absent" state is
        // unreachable. The post-completion check above is the observable consequence.
      }
    }

  it should "NOT record a node whose subtree is not closed (child still missing) ⇒ next verification DESCENDS it" taggedAs UnitTest in {
    val storage = new TestMptStorage()
    val root = storedRoot(storage)
    val (pathset, hash, encoded, missingChild) = branchWithMissingChild(1)

    withFixture(root, storage, Some(ExecutionContext.parasitic)) { (coordinator, store, _) =>
      val peer = PeerTestHelpers.createTestPeer("crash-gap-peer", testKit.createTestProbe[Any]().ref.toClassic)
      coordinator ! TrieNodeHealingCoordinator.QueueMissingNodes(Seq((pathset, hash)))
      coordinator ! TrieNodeHealingCoordinator.HealingPeerAvailable(peer)
      coordinator ! TrieNodeHealingCoordinator.TrieNodesResponseMsg(SNAP.TrieNodes(requestId = 1, nodes = Seq(encoded)))

      // X's subtree did NOT close (the child is missing). The missing child surfaces in the open frontier and X is NEVER
      // recorded subtree-complete — so a later verification has no record to prune X by and MUST descend it (the
      // descend-on-missing-record fallback, FR-006). We assert the record-absence directly (the precondition that
      // forces descent; the descent itself is proven by T-2). spec 022 D3: X itself is held, not written, until its
      // subtree is present.
      eventually(timeout(5.seconds), interval(100.millis))(openFrontier(coordinator) should be >= 1)
      nodeInStorage(storage, hash) shouldBe false // X held, never on disk while its child is missing (spec 022 D3)
      store.isSubtreeComplete(hash) shouldBe false // … and X NOT recorded ⇒ descend-on-missing-record
      missingChild.length shouldBe 32
    }
  }

  // ── T-8: never record from an unsound (non-closed) descent ──────────────────────────────────────

  "Crash safety (T-8, D2 guard)" should
    "never record a subtree that did not genuinely close with zero missing descendants" taggedAs UnitTest in {
      // A node X whose child stays missing must NEVER acquire a subtree-complete record, no matter how many flushes
      // run — the staging gate requires zero missing children AND no pending child AND all-present-children-recorded.
      // (A `visitedLru`-faked queue-drain or a truncated/evicted descent can therefore never fabricate a record.)
      val storage = new TestMptStorage()
      val root = storedRoot(storage)
      val (pathset, hash, encoded, _) = branchWithMissingChild(2)

      withFixture(root, storage, Some(ExecutionContext.parasitic)) { (coordinator, store, _) =>
        val peer = PeerTestHelpers.createTestPeer("d2-guard-peer", testKit.createTestProbe[Any]().ref.toClassic)
        coordinator ! TrieNodeHealingCoordinator.QueueMissingNodes(Seq((pathset, hash)))
        coordinator ! TrieNodeHealingCoordinator.HealingPeerAvailable(peer)
        coordinator ! TrieNodeHealingCoordinator.TrieNodesResponseMsg(
          SNAP.TrieNodes(requestId = 1, nodes = Seq(encoded))
        )

        // The child is enqueued (open frontier); X is held (spec 022 D3: never on disk while its child is missing), and
        // the record must stay absent.
        eventually(timeout(5.seconds), interval(100.millis))(openFrontier(coordinator) should be >= 1)
        nodeInStorage(storage, hash) shouldBe false
        store.isSubtreeComplete(hash) shouldBe false
        // Issue a progress query (a barrier on the actor: every earlier message has been handled by the time the reply
        // returns, and with the parasitic writer every flush those messages started has run too), then re-confirm the
        // record is STILL absent. A late flush cannot fabricate a record because X was never staged.
        openFrontier(coordinator) should be >= 0
        store.isSubtreeComplete(hash) shouldBe false
      }

      // NOTE: SNAP account-fragment seeding (the former C3a/T008 path) was REMOVED as unsound (forge adversarial
      // review, 2026-06-19): an account-trie fragment root's subtree includes the storage tries hanging off its
      // account leaves, which are not yet downloaded when SNAP commits the account fragment — recording the fragment
      // root subtree-complete could prune a still-missing storage node and falsely complete. Seeding is now ONLY the
      // SOUND heal-side inductive closure (C3b: a node is recorded only when every present child — including the
      // account leaf's storageRoot — is itself recorded subtree-complete) plus the genuine zero-missing root record
      // at the completion chokepoint. This spec proves the heal-side (C3b) arm of the guard deterministically.
    }
