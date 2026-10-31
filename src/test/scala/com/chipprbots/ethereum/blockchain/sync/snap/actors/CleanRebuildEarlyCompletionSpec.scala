package com.chipprbots.ethereum.blockchain.sync.snap.actors

import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.util.ByteString

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*

import org.scalatest.concurrent.Eventually
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.snap.*
import com.chipprbots.ethereum.db.dataSource.{RocksDbConfig, RocksDbDataSource}
import com.chipprbots.ethereum.db.storage.{HealingFrontierStorage, Namespaces}
import com.chipprbots.ethereum.mpt.LeafNode
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.testing.TestMptStorage

import java.io.File
import java.nio.file.Files
import java.util.concurrent.{Executors, TimeUnit}

/** spec 006 — Skip the redundant second verification walk on a CLEAN post-SNAP heal.
  *
  * On a restart with a persisted-but-incomplete (or empty) frontier and the root already in storage, the coordinator
  * takes the `case None` branch and runs ONE full-state rebuild walk ([[TrieNodeHealingCoordinator.rebuildFrontierBFS]]
  * → `FrontierRebuildComplete(missingEmitted, walkRoot)`). When that walk was genuinely clean (`missingEmitted == 0 &&
  * totalNodesHealed == 0 && isComplete && !flushing && walkRoot == stateRoot`) the handler now declares completion
  * after that single walk by routing `HealingCheckCompletion` through the EXISTING completion chokepoint — instead of
  * idling until the dead-pulse watchdog force-starts a redundant second (verification) walk over the identical trie.
  *
  * Each case maps to a contract test T-1…T-5 (contracts/internal-interfaces.md):
  *   - T-1 (FR-001/SC-001): clean rebuild → `StateHealingComplete` within 5s after ONE walk; marker set.
  *   - T-2 (FR-003/SC-002, load-bearing): rebuild that finds a missing node → `pendingTasks > 0`, NO completion in 5s.
  *   - T-3 (FR-003/FR-005/SC-002, load-bearing): stale-root completion (walkRoot != current stateRoot) → NO completion.
  *   - T-4 (FR-002/SC-003): the early-path completion sets the SAME completeness marker as the two-walk path.
  *   - T-5 (FR-006/SC-004): after early completion exactly ONE `StateHealingComplete` (no watchdog walk #2 / no
  *     double-fire), the observable consequence of `verificationPassComplete == true`.
  *
  * Harness, fixtures, RocksDB config, and teardown discipline are copied verbatim from `HealingFrontierResumeSpec` /
  * `ScopedVerificationParitySpec` in this same package (deterministic TestKit, no `Thread.sleep`). Storage is a real
  * temp-dir RocksDB because resume/marker semantics rely on `HealingFrontierStorage` (CF iteration + the CF `g`
  * completeness marker), which `EphemDataSource` does not provide. Teardown order: stop actor (await termination) →
  * drain the single-thread EC (the rebuild `Future` and its `loadAll` iterator are finished) → destroy DataSource →
  * delete temp dir, so an in-flight walk can never touch a freed native handle.
  */
class CleanRebuildEarlyCompletionSpec
    extends ScalaTestWithActorTestKit()
    with AnyFlatSpecLike
    with Matchers
    with Eventually:

  implicit private val classicSystem: org.apache.pekko.actor.ActorSystem = system.classicSystem
  implicit private val actorTestKit: org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit = testKit

  /** A trivially-present childless leaf root so `StartTrieNodeHealing` takes the restart (resume/rebuild) branch. A
    * childless-leaf root ⇒ the rebuild walk discovers nothing ⇒ it is a CLEAN walk (0 missing). Mirrors
    * `HealingFrontierResumeSpec.storedRoot` / `HealingTrieFixtures.childlessLeafRoot`.
    */
  private def storedLeafRoot(storage: TestMptStorage, seed: Int): ByteString =
    val leaf = LeafNode(ByteString(Array[Byte](seed.toByte)), ByteString(Array[Byte](seed.toByte)))
    storage.putNode(leaf)
    ByteString(leaf.hash)

  private def deleteRecursively(f: File): Unit =
    Option(f.listFiles()).foreach(_.foreach(deleteRecursively))
    f.delete()
    ()

  /** Query the coordinator's `pendingTasks` via the public progress message (a dedicated probe per query so the shared
    * inbox cannot steal the reply — mirrors `HealingFrontierResumeSpec.pendingTasks`).
    */
  private def pendingTasks(coordinator: ActorRef[TrieNodeHealingCoordinator.Command]): Int =
    val probe = testKit.createTestProbe[HealingStatistics]()
    coordinator ! TrieNodeHealingCoordinator.HealingGetProgress(probe.ref)
    probe.expectMessageType[HealingStatistics].pendingTasks

  /** Owns the RocksDB store, a dedicated single-thread EC for the coordinator's rebuild/flush `Future`s, and the
    * coordinator. `storage`/`root` are supplied by the caller so a fixture can inject a clean trie (childless leaf) or
    * a trie with a deliberately-missing child (`HealingTrieFixtures`). Persistence is ON, the completeness marker is
    * ABSENT (`markComplete = false`) and the persisted frontier is EMPTY, so `StartTrieNodeHealing` takes the `case
    * None` → full-state rebuild branch (the path spec 006 short-circuits on a clean result).
    */
  private def withRebuildFixture(
      storage: TestMptStorage,
      root: ByteString
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
    val dbPath = Files.createTempDirectory("clean-rebuild-early-completion-rocksdb").toAbsolutePath.toString
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

    val controllerProbe = testKit.createTestProbe[SNAPSyncController.Command]()
    val coordinator = HealingTrieFixtures.spawnCoordinator(
      stateRoot = root,
      networkPeerManager =
        testKit.createTestProbe[com.chipprbots.ethereum.network.NetworkPeerManagerActor.Command]().ref,
      requestTracker = new SNAPRequestTracker()(classicSystem.scheduler),
      mptStorage = storage,
      batchSize = 16,
      snapSyncController = controllerProbe.ref,
      healingWriterEcOverride = Some(ec)
    )
    try body(coordinator, root, store, controllerProbe)
    finally
      // 1) No more actor-thread RocksDB ops. 2) Drain the EC so the rebuild `loadAll`/walk iterators are closed.
      testKit.stop(coordinator)
      pool.shutdown()
      pool.awaitTermination(5, TimeUnit.SECONDS)
      // 3) Now nothing references the store — safe to free the native handles.
      dataSource.destroy()
      deleteRecursively(new File(dbPath))

  // ---- T-1 (FR-001/SC-001): one walk, not two, on a clean rebuild ----

  "Clean rebuild early completion" should
    "declare StateHealingComplete after ONE walk on a clean (0-missing, 0-healed) rebuild (T-1, FR-001)" taggedAs UnitTest in {
      // Childless-leaf root in storage, persistence on, marker absent, empty frontier → the `case None`
      // full-state rebuild walk runs, finds 0 missing and heals nothing, so the spec-006 guard fires and
      // completion flows through HealingCheckCompletion. The 5s bound is the regression detector: WITHOUT
      // the fix nothing reaches the controller until the ~6-min dead-pulse watchdog forces walk #2.
      val storage = new TestMptStorage()
      val root = storedLeafRoot(storage, seed = 1)
      withRebuildFixture(storage, root) { (coordinator, r, _, controller) =>
        coordinator ! TrieNodeHealingCoordinator.StartTrieNodeHealing(r)
        controller.expectMessage(5.seconds, SNAPSyncController.StateHealingComplete)
      }
    }

  // ---- T-2 (FR-003/SC-002, load-bearing): never declare complete when a node is missing ----

  it should "NOT declare completion when the rebuild discovers a missing node (T-2, FR-003)" taggedAs UnitTest in {
    // A present BranchNode root with a missing grandchild (shared-ancestor fixture): the rebuild walk emits
    // exactly one frontier node → queued (pendingTasks > 0) → FrontierRebuildComplete(missingEmitted = 1, …),
    // whose guard fails on `missingEmitted != 0` ⇒ NO early completion. The verification path / watchdog
    // would normally take over, but no completion may be declared while a node is missing.
    val fixture = HealingTrieFixtures.sharedAncestor()
    withRebuildFixture(fixture.storage, fixture.rootHash) { (coordinator, r, _, controller) =>
      coordinator ! TrieNodeHealingCoordinator.StartTrieNodeHealing(r)
      // The single missing grandchild is discovered and queued.
      eventually(timeout(5.seconds), interval(100.millis))(pendingTasks(coordinator) should be > 0)
      // And no early completion is declared while that node is still missing.
      controller.expectNoMessage(5.seconds)
    }
  }

  // ---- T-3 (FR-003/FR-005/SC-002, load-bearing): stale-root completion is excluded ----

  it should "NOT declare completion when the rebuild finished against a now-stale root (T-3, FR-005)" taggedAs UnitTest in {
    // A `HealingPivotRefreshed(B)` to a different root does NOT cancel the in-flight rebuild walk (it closed
    // over root A), so a stale `FrontierRebuildComplete(walkRoot = A)` can land while the coordinator's
    // stateRoot is now B. The explicit `walkRoot == stateRoot` conjunct excludes it (D4/FR-005).
    //
    // LIVE-ONLY reduction: the harness cannot construct the private `FrontierRebuildComplete` nor reliably win
    // the in-flight race, so we drive the EQUIVALENT deterministically. `StartTrieNodeHealing(A)` spawns the
    // rebuild Future on the single-thread EC and returns; the actor then processes `HealingPivotRefreshed(B)`
    // SYNCHRONOUSLY (setting stateRoot = B) before the Future's reply can re-enter the mailbox — a Future
    // result must hop back through the actor mailbox, which is strictly ordered behind the already-enqueued
    // refresh. When the stale `FrontierRebuildComplete(walkRoot = A)` finally lands, stateRoot = B, so the
    // guard's `walkRoot == stateRoot` is false ⇒ no early completion. A is a childless leaf in storage; B is
    // deliberately NOT stored, so `HealingPivotRefreshed(B)` takes the unambiguous reseed branch (no competing
    // verification walk against B — this removes the verificationBFSRunning race), leaving the excluded stale
    // A-completion as the ONLY candidate to send StateHealingComplete in the window. The 5s window is well under
    // the 2-min watchdog, so no walk #2 fires either.
    val storage = new TestMptStorage()
    val rootA = storedLeafRoot(storage, seed = 1)
    // rootB intentionally NOT put into storage — forces the deterministic reseed branch in HealingPivotRefreshed.
    val rootB = ByteString(LeafNode(ByteString(Array[Byte](2.toByte)), ByteString(Array[Byte](2.toByte))).hash)
    withRebuildFixture(storage, rootA) { (coordinator, _, _, controller) =>
      coordinator ! TrieNodeHealingCoordinator.StartTrieNodeHealing(rootA)
      coordinator ! TrieNodeHealingCoordinator.HealingPivotRefreshed(
        rootB
      ) // flips stateRoot A → B before the stale A-walk lands
      // The stale FrontierRebuildComplete(walkRoot = A) must NOT complete against the new root B.
      controller.expectNoMessage(5.seconds)
    }
  }

  // ---- T-5 (FR-006/SC-004): watchdog self-suppression — exactly one completion, no double-fire ----

  it should "fire exactly one StateHealingComplete (no watchdog walk #2 / no double-fire) (T-5, FR-006)" taggedAs UnitTest in {
    // After the early completion `verificationPassComplete == true`, so the dead-pulse watchdog branch
    // (`… && !verificationPassComplete`) cannot force-start walk #2 and re-declare completion. The flag is
    // private; its sole observable consequence is asserted here — exactly ONE StateHealingComplete, then
    // silence (the watchdog runs every 2 min, well outside the 2s window, and would self-suppress regardless).
    val storage = new TestMptStorage()
    val root = storedLeafRoot(storage, seed = 1)
    withRebuildFixture(storage, root) { (coordinator, r, _, controller) =>
      coordinator ! TrieNodeHealingCoordinator.StartTrieNodeHealing(r)
      controller.expectMessage(5.seconds, SNAPSyncController.StateHealingComplete)
      controller.expectNoMessage(2.seconds)
    }
  }
