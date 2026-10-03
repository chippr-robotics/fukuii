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
import com.chipprbots.ethereum.db.storage.{HealingFrontierStorage, Namespaces, PathNodeStorage}
import com.chipprbots.ethereum.metrics.Metrics
import com.chipprbots.ethereum.mpt.{BranchNode, HashNode, LeafNode, MptNode, MptTraversals, NullNode}
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.p2p.messages.SNAP
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.testing.{PeerTestHelpers, TestMptStorage}

import java.io.File
import java.nio.file.Files
import java.util.concurrent.{Executors, TimeUnit}

/** spec 005 — T-6 (FR-007/SC-005, safe fallback) + the spec-002 default-off decoupling guard.
  *
  *   - Fallback: when `prunedHealVerification = false` OR `storageScheme == Path` OR there are no records, the
  *     verification takes the UNCHANGED full-trie walk (no pruning, byte-identical to pre-spec-005). The
  *     descend-and-stop oracle is inert: `prunedEnabled` is false (flag off / Path) or there is simply nothing to prune
  *     (no records). The mode gauge reports 0 for flag-off / Path, and 0 pruned-subtrees when records are absent.
  *   - Default-off decoupling guard (spec 002): with `frontierPersistenceEnabled = false` (default), NO frontier-mirror
  *     entries are written (`store.loadAll()` stays empty) and NO `markComplete()` snapshot marker is set
  *     (`store.isComplete` stays false). The spec-005 subtree records are a SEPARATE additive key space (excluded from
  *     `loadAll`), so the store can host them without re-enabling the spec-002 persistence features.
  *
  * Harness mirrors [[ScopedVerificationFallbackSpec]] / [[PrunedHealVerificationSpec]]. Deterministic: `fishForMessage`
  * / `awaitAssert`, no `Thread.sleep`.
  */
class PrunedHealFallbackSpec extends ScalaTestWithActorTestKit() with AnyFlatSpecLike with Matchers with Eventually:

  implicit private val classicSystem: org.apache.pekko.actor.ActorSystem = system.classicSystem
  implicit private val actorTestKit: org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit = testKit

  private def gaugeValue(name: String): Double =
    val gauge = Metrics.get().registry.find(name).gauge()
    if gauge == null then Double.NaN else gauge.value()

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

  private def storedLeaf(storage: TestMptStorage, seed: String): ByteString =
    val leaf = LeafNode(ByteString(Array[Byte](0x01)), ByteString(kec256(ByteString(seed)).toArray))
    storage.putNode(leaf)
    ByteString(leaf.hash)

  /** root(branch) → subtreeRoot(branch, present) → grandchild(leaf, present). Same shape as the prune fixture, used
    * here to prove a RECORDED subtree is NOT pruned when the flag is off / scheme is Path.
    */
  private def presentSubtree(storage: TestMptStorage): (ByteString, ByteString) =
    val grandchild = storedLeaf(storage, "fallback-present-grandchild")
    val subChildren = emptyChildren
    subChildren(4) = HashNode(grandchild.toArray)
    val subtreeRoot = BranchNode(subChildren, None)
    storage.putNode(subtreeRoot)
    val subtreeRootHash = ByteString(subtreeRoot.hash)
    val rootChildren = emptyChildren
    rootChildren(1) = HashNode(subtreeRootHash.toArray)
    val root = BranchNode(rootChildren, None)
    storage.putNode(root)
    (ByteString(root.hash), subtreeRootHash)

  /** A clean storage-trie leaf to heal. */
  private def cleanLeaf(seed: Int): (Seq[ByteString], ByteString, ByteString) =
    val leaf = LeafNode(ByteString(Array[Byte](0x01)), ByteString(kec256(ByteString(s"fallback-leaf-$seed")).toArray))
    val encoded = MptTraversals.encodeNode(leaf)
    val hash = kec256(ByteString(encoded))
    val accountHash = kec256(ByteString(s"fallback-account-$seed"))
    (Seq(accountHash, ByteString(Array[Byte](0x20, seed.toByte))), hash, ByteString(encoded))

  private def storedRoot(storage: TestMptStorage): ByteString =
    val leaf = LeafNode(ByteString(Array[Byte](0x01)), ByteString(Array[Byte](0x02)))
    storage.putNode(leaf)
    ByteString(leaf.hash)

  /** Copy every node reachable from `rootHash` in the hash-keyed `storage` into `pns` at its account-trie nibble path
    * (the root at the empty path, a branch child at `parentPath :+ slot`). Branch-only, which is all `presentSubtree`
    * builds. Gives a Path-scheme coordinator the same "already fully present" trie the Hash-scheme cases get from
    * `storage` itself.
    */
  private def mirrorIntoPathStorage(storage: TestMptStorage, rootHash: ByteString, pns: PathNodeStorage): Unit =
    def walk(hash: Array[Byte], path: Array[Byte]): Unit =
      val node = storage.get(hash)
      pns.writeAccountNode(path, node.encode)
      node match
        case branch: BranchNode =>
          branch.children.zipWithIndex.foreach {
            case (child: HashNode, slot) => walk(child.hashNode, path :+ slot.toByte)
            case _                       => ()
          }
        case _ => ()
    walk(rootHash.toArray, Array.empty[Byte])

  /** Build a verification-driving fixture. `markComplete()` + empty frontier + `frontierPersistenceEnabled = true`
    * routes `StartTrieNodeHealing` to the verification pass (so we can drive the WALK directly). For the default-off
    * decoupling test we instead build with persistence OFF and drive the HEAL flow.
    *
    * `seedPathTrie` is required only by the Path-scheme case. Under the Path scheme the coordinator reads EVERY node —
    * the root gate in `StartTrieNodeHealing` and each node the verification walk visits — through a `PathNodeStorage`
    * (path-keyed), never through the hash-keyed `mptStorage`. So a Path-scheme "already fully present" trie must live
    * in `PathNodeStorage`, each node at its trie path: `mirrorIntoPathStorage` copies the whole trie reachable from
    * `stateRoot` there (in this spec the three-node `presentSubtree`). Without it the walk (correctly) finds the
    * present subtree missing, waits for a peer that never arrives, and the test times out.
    */
  private def withVerificationFixture(
      stateRoot: ByteString,
      storage: TestMptStorage,
      storageScheme: StorageScheme,
      seedPathTrie: Boolean = false,
      prunedHealVerification: Boolean = true
  )(
      body: (
          ActorRef[TrieNodeHealingCoordinator.Command],
          HealingFrontierStorage,
          org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe[SNAPSyncController.Command]
      ) => Unit
  ): Unit =
    val pool = Executors.newSingleThreadExecutor()
    val ec = ExecutionContext.fromExecutorService(pool)
    val dbPath = Files.createTempDirectory("pruned-fallback-rocksdb").toAbsolutePath.toString
    val dataSource = RocksDbDataSource(rocksDbConfig(dbPath), Namespaces.nsSeq)
    val store = new HealingFrontierStorage(dataSource)
    store.markComplete()

    // Path-scheme only: the present trie must be readable path-keyed, node by node, for the verification walk.
    val pathNodeStorageOpt: Option[PathNodeStorage] =
      if seedPathTrie then
        val pns = new PathNodeStorage(dataSource)
        mirrorIntoPathStorage(storage, stateRoot, pns)
        Some(pns)
      else None

    val controller = testKit.createTestProbe[SNAPSyncController.Command]()
    val coordinator = HealingTrieFixtures.spawnCoordinator(
      stateRoot = stateRoot,
      networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]().ref,
      requestTracker = new SNAPRequestTracker()(classicSystem.scheduler),
      mptStorage = storage,
      batchSize = 64,
      snapSyncController = controller.ref,
      healingFrontierStorage = Some(store),
      healingWriterEcOverride = Some(ec),
      storageScheme = storageScheme,
      pathNodeStorageOpt = pathNodeStorageOpt,
      prunedHealVerification = prunedHealVerification
    )
    try body(coordinator, store, controller)
    finally
      testKit.stop(coordinator)
      pool.shutdown()
      pool.awaitTermination(5, TimeUnit.SECONDS)
      dataSource.destroy()
      deleteRecursively(new File(dbPath))

  private def rocksDbConfig(dbPath: String): RocksDbConfig =
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

  // ── T-6: flag off ⇒ full-trie walk, no pruning ─────────────────────────────────────────────────

  "Pruned verification fallback (T-6, FR-007)" should
    "take the full-trie walk and NOT prune a recorded subtree when the flag is OFF" taggedAs UnitTest in {
      val storage = new TestMptStorage()
      val (root, subtreeRoot) = presentSubtree(storage)
      withVerificationFixture(
        root,
        storage,
        storageScheme = StorageScheme.Hash,
        prunedHealVerification = false
      ) { (coordinator, store, controller) =>
        store.markSubtreeComplete(subtreeRoot) // a record EXISTS, but the flag is off ⇒ it must be ignored
        SNAPSyncMetrics.setHealingPrunedVerification(-1L)
        SNAPSyncMetrics.setHealingPrunedSubtrees(-1L)
        coordinator ! TrieNodeHealingCoordinator.StartTrieNodeHealing(root)
        awaitStateHealingComplete(controller)
        // Pruning disabled: engagement gauge 0, zero subtrees pruned (the recorded subtree was descended anyway).
        gaugeValue("snapsync.healing.pruned_verification.gauge") shouldBe 0.0 +- 1e-9
        gaugeValue("snapsync.healing.pruned_subtrees.gauge") shouldBe 0.0 +- 1e-9
      }
    }

  it should "take the full-trie walk and NOT prune a recorded subtree under the Path storage scheme" taggedAs UnitTest in {
    val storage = new TestMptStorage()
    val (root, subtreeRoot) = presentSubtree(storage)
    // Path scheme ⇒ prunedEnabled is false even with the flag on (D5: Hash-scheme only). The hash-keyed record must
    // never gate a Path-scheme verification. `seedPathTrie = true` mirrors the present subtree into the path-keyed
    // store, where a Path-scheme coordinator (root gate AND verification walk) actually reads it.
    withVerificationFixture(
      root,
      storage,
      storageScheme = StorageScheme.Path,
      seedPathTrie = true
    ) { (coordinator, store, controller) =>
      store.markSubtreeComplete(subtreeRoot)
      SNAPSyncMetrics.setHealingPrunedVerification(-1L)
      SNAPSyncMetrics.setHealingPrunedSubtrees(-1L)
      coordinator ! TrieNodeHealingCoordinator.StartTrieNodeHealing(root)
      awaitStateHealingComplete(controller)
      gaugeValue("snapsync.healing.pruned_verification.gauge") shouldBe 0.0 +- 1e-9
      gaugeValue("snapsync.healing.pruned_subtrees.gauge") shouldBe 0.0 +- 1e-9
    }
  }

  it should "engage the pruned path but prune NOTHING when the flag is on and NO records exist" taggedAs UnitTest in {
    // Flag on + Hash scheme + store present ⇒ prunedEnabled is true, so the engagement gauge reads 1. But with no
    // records, the oracle never fires: every present node is descended, exactly like the full walk. The completion
    // decision is unchanged.
    val storage = new TestMptStorage()
    val (root, subtreeRoot) = presentSubtree(storage)
    withVerificationFixture(root, storage, storageScheme = StorageScheme.Hash) { (coordinator, store, controller) =>
      store.isSubtreeComplete(subtreeRoot) shouldBe false // NO record seeded
      SNAPSyncMetrics.setHealingPrunedSubtrees(-1L)
      coordinator ! TrieNodeHealingCoordinator.StartTrieNodeHealing(root)
      awaitStateHealingComplete(controller)
      gaugeValue("snapsync.healing.pruned_verification.gauge") shouldBe 1.0 +- 1e-9 // engaged …
      gaugeValue("snapsync.healing.pruned_subtrees.gauge") shouldBe 0.0 +- 1e-9 // … but pruned nothing
    }
  }

  // ── spec-002 default-off decoupling guard ──────────────────────────────────────────────────────

  "Default-off decoupling guard" should
    "write NO frontier-mirror entries and set NO completeness snapshot marker when frontierPersistenceEnabled is false (default)" taggedAs UnitTest in {
      // Build with frontier persistence OFF (the default) and drive the HEAL flow. Even though the store is present
      // (to host spec-005 records), the spec-002 features must stay dark: no mirror writes, no snapshot marker.
      val pool = Executors.newSingleThreadExecutor()
      val ec = ExecutionContext.fromExecutorService(pool)
      val dbPath = Files.createTempDirectory("pruned-decouple-rocksdb").toAbsolutePath.toString
      val dataSource = RocksDbDataSource(rocksDbConfig(dbPath), Namespaces.nsSeq)
      val store = new HealingFrontierStorage(dataSource)

      val storage = new TestMptStorage()
      val root = storedRoot(storage)
      val nodes = (0 until 3).map(cleanLeaf)
      val controller = testKit.createTestProbe[SNAPSyncController.Command]()
      val coordinator = HealingTrieFixtures.spawnCoordinator(
        stateRoot = root,
        networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]().ref,
        requestTracker = new SNAPRequestTracker()(classicSystem.scheduler),
        mptStorage = storage,
        batchSize = 64,
        snapSyncController = controller.ref,
        healingFrontierStorage = Some(store),
        healingWriterEcOverride = Some(ec)
        // healingFrontierStorage is Some(store) for spec-005 records, but frontierPersistenceEnabled
        // is gone from the Typed API — persistence is now controlled solely by the presence of
        // healingFrontierStorage. The store is passed to host spec-005 subtree records.
      )
      try
        store.isComplete shouldBe false
        store.loadAll() shouldBe empty
        val peer = PeerTestHelpers.createTestPeer("decouple-peer", testKit.createTestProbe[Any]().ref.toClassic)
        coordinator ! TrieNodeHealingCoordinator.QueueMissingNodes(nodes.map { case (ps, h, _) => (ps, h) })
        coordinator ! TrieNodeHealingCoordinator.HealingPeerAvailable(peer)
        coordinator ! TrieNodeHealingCoordinator.TrieNodesResponseMsg(
          SNAP.TrieNodes(requestId = 1, nodes = nodes.map(_._3))
        )
        awaitStateHealingComplete(controller)

        // No spec-002 snapshot marker was set (persistence off) — byte-for-byte pre-spec-005 marker behaviour.
        store.isComplete shouldBe false
        // No frontier-mirror entries were written (persistence off). `loadAll` EXCLUDES the spec-005 root record
        // (markSubtreeComplete(stateRoot), gated on prunedEnabled), so the reconstructed frontier stays empty even
        // though that additive CF 'g' record may now exist.
        store.loadAll() shouldBe empty
      finally
        testKit.stop(coordinator)
        pool.shutdown()
        pool.awaitTermination(5, TimeUnit.SECONDS)
        dataSource.destroy()
        deleteRecursively(new File(dbPath))
    }
