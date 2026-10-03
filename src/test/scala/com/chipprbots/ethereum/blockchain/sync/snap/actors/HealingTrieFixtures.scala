package com.chipprbots.ethereum.blockchain.sync.snap.actors

import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.typed.ActorRef as TypedActorRef
import org.apache.pekko.util.ByteString

import scala.concurrent.ExecutionContext

import com.chipprbots.ethereum.blockchain.sync.snap.*
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.db.storage.BfsQueueStorage
import com.chipprbots.ethereum.db.storage.HealingFrontierStorage
import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.db.storage.PathNodeStorage
import com.chipprbots.ethereum.mpt.BranchNode
import com.chipprbots.ethereum.mpt.ExtensionNode
import com.chipprbots.ethereum.mpt.HashNode
import com.chipprbots.ethereum.mpt.LeafNode
import com.chipprbots.ethereum.mpt.MptNode
import com.chipprbots.ethereum.mpt.NullNode
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.testing.TestMptStorage

/** Deterministic synthetic-trie fixtures for the post-SNAP frontier-rebuild walk
  * ([[TrieNodeHealingCoordinator.rebuildFrontierBFS]]) tests (spec 002 T003).
  *
  * Each fixture builds a small, fixed-byte account-trie ([[BranchNode]]/[[ExtensionNode]] only, `isStorage = false`) in
  * a [[TestMptStorage]] and returns the root hash plus the hash of a node deliberately LEFT OUT of storage so a test
  * can assert the walk discovers it.
  *
  * No randomness: every node hash derives from a fixed [[kec256]] seed string, so the same trie shape and the same
  * `(rootHash, missingHash)` come out on every run and on every host.
  *
  * '''Why a shared ancestor matters (FR-025).''' The walk's `visited` set de-dups a node the first time its hash is
  * seen; a second parent referencing the same hash is a no-op. If that de-dup ever short-circuited descent into the
  * shared node's OWN children, a missing grandchild behind the shared node would be silently skipped. These fixtures
  * place a genuinely-shared, genuinely-present branch/extension node — referenced by two distinct parents — directly
  * above an injected missing grandchild, so a test can prove the missing grandchild is still discovered exactly once
  * even though the shared ancestor is marked visited on first encounter.
  *
  * The MPT nodes here are stored object-for-object by [[TestMptStorage.putNode]] (no RLP round-trip), so children kept
  * as [[HashNode]] references are read back as `HashNode`s and the walk follows them by hash — exactly the production
  * path where intermediate children are stored separately and referenced by 32-byte hash.
  */
object HealingTrieFixtures:

  /** Test-only spawn shim for the now-Typed [[TrieNodeHealingCoordinator]] (Group S3). The coordinator's production
    * factory is `apply(...): Behavior[Command]`; these specs spawn it through an [[ActorTestKit]] supplied by
    * [[org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit]]. Returns the typed `ActorRef` directly
    * (no `PropsAdapter`) -- under the typed test kit's user guardian a `PropsAdapter` bridge child crashes the whole
    * system on stop (`StopChild` reaches a guardian that only accepts `TestKitCommand`), so the coordinator behaviour
    * is spawned natively instead. Mirrors the named params of the former `coordinatorProps(...)`.
    */
  def spawnCoordinator(
      stateRoot: ByteString,
      networkPeerManager: TypedActorRef[NetworkPeerManagerActor.Command],
      requestTracker: SNAPRequestTracker,
      mptStorage: MptStorage,
      batchSize: Int,
      snapSyncController: org.apache.pekko.actor.typed.ActorRef[SNAPSyncController.Command],
      concurrency: Int = 16,
      visitedCap: Int = TrieNodeHealingCoordinator.DefaultVisitedCap,
      healingFrontierStorage: Option[HealingFrontierStorage] = None,
      healingWriterEcOverride: Option[ExecutionContext] = None,
      healingReaderEcOverride: Option[ExecutionContext] = None,
      traversalParallelism: Int = TrieNodeHealingCoordinator.DefaultBfsParallelism,
      healingMinParallelism: Int = TrieNodeHealingCoordinator.DefaultMinParallelism,
      healingReservedCores: Int = TrieNodeHealingCoordinator.DefaultReservedCores,
      bfsQueueStorageOpt: Option[BfsQueueStorage] = None,
      storageScheme: StorageScheme = StorageScheme.Hash,
      pathNodeStorageOpt: Option[PathNodeStorage] = None,
      frontierHighWater: Int = TrieNodeHealingCoordinator.DefaultFrontierHighWater,
      frontierLowWater: Int = TrieNodeHealingCoordinator.DefaultFrontierLowWater,
      frontierBackpressureMaxWaitMs: Long = TrieNodeHealingCoordinator.FrontierBackpressureMaxWaitMs,
      scopedHealVerification: Boolean = true,
      scopedHealMaxPaths: Int = TrieNodeHealingCoordinator.DefaultScopedHealMaxPaths,
      // spec 005 pruned descend-and-stop. Restored test seam (the #1373 typed rewrite dropped this thread-through) so
      // PrunedHealFallbackSpec can spawn a flag-OFF coordinator. Defaults true to match the impl/factory default.
      prunedHealVerification: Boolean = true,
      // spec 002 frontier persistence. Default OFF to match the production constructor default and the spec-005
      // decoupling (PrunedHealParitySpec / PrunedHealFallbackSpec rely on the default-off, store-present case). Specs
      // that exercise the Layer-2 mirror writes / completeness markers (HealingFrontierResumeSpec) set this true.
      frontierPersistenceEnabled: Boolean = false,
      decoupledHealServeRoot: Boolean = false,
      decoupledHealMaxAttemptsNoRefresh: Int = TrieNodeHealingCoordinator.DefaultDecoupledHealMaxAttemptsNoRefresh,
      // spec 009 (Moving-Root Delta Heal). Default false so existing spec-004 tests spawn the flag-OFF coordinator
      // (unchanged); the moving-root tests pass true to exercise seed-from-absent-root / single-root fetch / re-peg.
      movingRootDeltaHeal: Boolean = false,
      // Where bytecode lives; Some(_) makes the coordinator report healed accounts whose code is absent from it.
      evmCodeStorage: Option[com.chipprbots.ethereum.db.storage.EvmCodeStorage] = None
  )(implicit testKit: ActorTestKit): TypedActorRef[TrieNodeHealingCoordinator.Command] =
    testKit.spawn(
      TrieNodeHealingCoordinator(
        stateRoot = stateRoot,
        networkPeerManager = networkPeerManager,
        requestTracker = requestTracker,
        mptStorage = mptStorage,
        batchSize = batchSize,
        snapSyncController = snapSyncController,
        concurrency = concurrency,
        visitedCap = visitedCap,
        healingFrontierStorage = healingFrontierStorage,
        healingWriterEcOverride = healingWriterEcOverride,
        healingReaderEcOverride = healingReaderEcOverride,
        traversalParallelism = traversalParallelism,
        healingMinParallelism = healingMinParallelism,
        healingReservedCores = healingReservedCores,
        bfsQueueStorageOpt = bfsQueueStorageOpt,
        storageScheme = storageScheme,
        pathNodeStorageOpt = pathNodeStorageOpt,
        frontierHighWater = frontierHighWater,
        frontierLowWater = frontierLowWater,
        frontierBackpressureMaxWaitMs = frontierBackpressureMaxWaitMs,
        scopedHealVerification = scopedHealVerification,
        scopedHealMaxPaths = scopedHealMaxPaths,
        prunedHealVerification = prunedHealVerification,
        frontierPersistenceEnabled = frontierPersistenceEnabled,
        decoupledHealServeRoot = decoupledHealServeRoot,
        decoupledHealMaxAttemptsNoRefresh = decoupledHealMaxAttemptsNoRefresh,
        movingRootDeltaHeal = movingRootDeltaHeal,
        evmCodeStorage = evmCodeStorage
      )
    )

  /** A stored synthetic state trie plus the hash of a deliberately-missing node.
    *
    * @param storage
    *   the [[TestMptStorage]] holding every node EXCEPT `missingNodeHash`.
    * @param rootHash
    *   the trie root to start the walk from (its node IS present in `storage`).
    * @param missingNodeHash
    *   a hash referenced by a stored node but NOT itself stored — the single frontier node the walk must discover.
    * @param sharedAncestorHash
    *   the hash of the present, stored node that two distinct parents reference (the shared ancestor) and that sits
    *   directly above `missingNodeHash`. Provided so a test can document the shape it is asserting over.
    */
  final case class SharedAncestorFixture(
      storage: TestMptStorage,
      rootHash: ByteString,
      missingNodeHash: ByteString,
      sharedAncestorHash: ByteString
  )

  private def seedHash(label: String): ByteString = kec256(ByteString(label))

  private def emptyChildren: Array[MptNode] = Array.fill[MptNode](16)(NullNode)

  private def branchWith(slots: (Int, MptNode)*): BranchNode =
    val children = emptyChildren
    slots.foreach { case (i, child) => children(i) = child }
    BranchNode(children, None)

  /** Build the canonical shared-ancestor fixture (T003).
    *
    * Shape (account trie, `isStorage = false`):
    * {{{
    *   root  (BranchNode, present)
    *     ├─[0]→ parentA (BranchNode, present)
    *     │        └─[0]→ shared (BranchNode, present)  ◄── referenced by BOTH parents
    *     └─[1]→ parentB (BranchNode, present)
    *              └─[1]→ shared (BranchNode, present)  ◄── same hash as above
    *   shared
    *     └─[2]→ missingGrandchild (HashNode, NOT stored)  ◄── the frontier node
    * }}}
    *
    * The walk visits `shared` exactly once (de-duped when reached via the second parent) and from it discovers
    * `missingGrandchild` exactly once. `missingGrandchild` is the only node absent from storage, so it is the only
    * frontier node the walk should emit.
    *
    * @param sharedIsExtension
    *   when true the shared ancestor is an [[ExtensionNode]] (`next` = the missing grandchild) instead of a
    *   [[BranchNode]], exercising the extension de-dup arm of `rebuildFrontierBFS`. Default false (branch).
    */
  def sharedAncestor(sharedIsExtension: Boolean = false): SharedAncestorFixture =
    val storage = new TestMptStorage()

    // The single deliberately-missing node: a hash referenced by `shared` but never stored.
    val missingGrandchildHash = seedHash("heal-fixture-shared-ancestor/missing-grandchild")

    // The genuinely-shared present node, holding ONLY a reference to the missing grandchild.
    val shared: MptNode =
      if sharedIsExtension then ExtensionNode(ByteString(Array[Byte](0x4)), HashNode(missingGrandchildHash.toArray))
      else branchWith(2 -> HashNode(missingGrandchildHash.toArray))
    storage.putNode(shared)
    val sharedHash = ByteString(shared.hash)

    // Two distinct parents, each referencing the SAME shared node by its hash (at different slots).
    val parentA = branchWith(0 -> HashNode(sharedHash.toArray))
    val parentB = branchWith(1 -> HashNode(sharedHash.toArray))
    storage.putNode(parentA)
    storage.putNode(parentB)

    // Root references both parents.
    val root = branchWith(0 -> HashNode(parentA.hash), 1 -> HashNode(parentB.hash))
    storage.putNode(root)

    SharedAncestorFixture(
      storage = storage,
      rootHash = ByteString(root.hash),
      missingNodeHash = missingGrandchildHash,
      sharedAncestorHash = sharedHash
    )

  /** A wider shared-ancestor fixture for the concurrency path (T031): the root fans out to `fanout` parents (a level
    * wide enough that a parallel walk can split it into sub-ranges), every one of which references the SAME shared
    * ancestor, which sits above the single injected missing grandchild.
    *
    * Even fully parallel — with every parent processed on a different reader thread — the shared ancestor must be
    * enqueued exactly once and its missing grandchild discovered exactly once.
    *
    * @param fanout
    *   number of distinct parents pointing at the shared ancestor (each at a distinct root slot; capped at 16).
    */
  def wideSharedAncestor(fanout: Int = 8): SharedAncestorFixture =
    val storage = new TestMptStorage()
    val width = math.min(math.max(fanout, 2), 16)

    val missingGrandchildHash = seedHash("heal-fixture-wide-shared-ancestor/missing-grandchild")
    val shared = branchWith(7 -> HashNode(missingGrandchildHash.toArray))
    storage.putNode(shared)
    val sharedHash = ByteString(shared.hash)

    // `width` distinct parents, all pointing at the same shared node (each via a distinct internal slot so the
    // parent hashes differ and the root references them at distinct positions).
    val parents = (0 until width).map { i =>
      val p = branchWith(i -> HashNode(sharedHash.toArray))
      storage.putNode(p)
      p
    }

    val rootChildren = emptyChildren
    parents.zipWithIndex.foreach { case (p, i) => rootChildren(i) = HashNode(p.hash) }
    val root = BranchNode(rootChildren, None)
    storage.putNode(root)

    SharedAncestorFixture(
      storage = storage,
      rootHash = ByteString(root.hash),
      missingNodeHash = missingGrandchildHash,
      sharedAncestorHash = sharedHash
    )

  /** A small fixed multi-node trie with several missing frontier nodes and at least one shared ancestor, used by the
    * serial-equivalence test (T027): the walk over it must emit the same frontier set regardless of whether a reader EC
    * is supplied or `traversalParallelism = 1`.
    *
    * Shape: root → 3 present parents; parent0 and parent1 both reference one shared present branch (de-dup), which has
    * a missing child; parent2 has its own missing child. Two distinct missing nodes total.
    */
  def multiNodeWithSharedAncestor(): MultiNodeFixture =
    val storage = new TestMptStorage()

    val sharedMissing = seedHash("heal-fixture-multinode/shared-missing")
    val directMissing = seedHash("heal-fixture-multinode/direct-missing")

    val shared = branchWith(3 -> HashNode(sharedMissing.toArray))
    storage.putNode(shared)
    val sharedHash = ByteString(shared.hash)

    val parent0 = branchWith(0 -> HashNode(sharedHash.toArray))
    val parent1 = branchWith(5 -> HashNode(sharedHash.toArray))
    val parent2 = branchWith(9 -> HashNode(directMissing.toArray))
    storage.putNode(parent0)
    storage.putNode(parent1)
    storage.putNode(parent2)

    val root = branchWith(
      0 -> HashNode(parent0.hash),
      1 -> HashNode(parent1.hash),
      2 -> HashNode(parent2.hash)
    )
    storage.putNode(root)

    MultiNodeFixture(
      storage = storage,
      rootHash = ByteString(root.hash),
      missingNodeHashes = Set(sharedMissing, directMissing)
    )

  /** A stored multi-node trie plus the full set of deliberately-missing frontier hashes. */
  final case class MultiNodeFixture(
      storage: TestMptStorage,
      rootHash: ByteString,
      missingNodeHashes: Set[ByteString]
  )

  /** A stored trie whose level-4 BFS frontier is wider than [[TrieNodeHealingCoordinator.BfsChunkSize]] (50,000), plus
    * the exact count of missing frontier nodes — used by the no-deadlock concurrency test (T029).
    */
  final case class WideLevelFixture(
      storage: TestMptStorage,
      rootHash: ByteString,
      expectedFrontier: Int
  )

  /** Build a 4-level full-ish 16-ary trie whose '''level-4 frontier exceeds 50,000 entries''', so the BFS walk's
    * parallel-split branch (`effectiveParallelism > 1 && levelSize > BfsChunkSize`) is GENUINELY taken — sub-ranges
    * dispatched as Futures on the reader EC while the parent walk Future parks on `Await` on the writer EC. This is the
    * only shape that reproduces forge's Await-on-same-pool starvation hazard, so it is the regression fixture for T029.
    *
    * Shape (all internal nodes present, every level-4 child missing):
    * {{{
    *   L0 root        : 1 BranchNode, 16 present children
    *   L1             : 16 BranchNodes, 16 present children each = 256
    *   L2             : 256 BranchNodes, 16 present children each = 4096
    *   L3             : 4096 BranchNodes, `childrenPerL3` MISSING children each
    *   L4 frontier    : 4096 × childrenPerL3 missing hashes  (> 50,000 with the default)
    * }}}
    *
    * `childrenPerL3 = 13` ⇒ 4096 × 13 = 53,248 frontier nodes, just over the 50,000 chunk threshold. Internal nodes
    * total 1 + 16 + 256 + 4096 = 4369 small `BranchNode`s — heavy but bounded, and deterministic (fixed-byte hashes).
    */
  def wideFrontierLevel(childrenPerL3: Int = 13): WideLevelFixture =
    val storage = new TestMptStorage()
    val perL3 = math.min(math.max(childrenPerL3, 1), 16)
    var missingCount = 0

    // Build bottom-up so each parent can reference its children by hash. A node's "address" in the tree is its
    // path of slot indices; the missing-leaf hashes derive from that path so they are all distinct and fixed.
    def missingChild(pathLabel: String): HashNode =
      HashNode(seedHash(s"heal-fixture-wide-frontier/missing/$pathLabel").toArray)

    // L3: 4096 branches, each with `perL3` MISSING children.
    val l3: Seq[BranchNode] = (0 until 4096).map { idx =>
      val children = emptyChildren
      (0 until perL3).foreach { j =>
        children(j) = missingChild(s"$idx-$j")
        missingCount += 1
      }
      val b = BranchNode(children, None)
      storage.putNode(b)
      b
    }

    // L2: 256 branches, each referencing 16 of the L3 branches by hash.
    val l2: Seq[BranchNode] = (0 until 256).map { idx =>
      val children = emptyChildren
      (0 until 16).foreach(j => children(j) = HashNode(l3(idx * 16 + j).hash))
      val b = BranchNode(children, None)
      storage.putNode(b)
      b
    }

    // L1: 16 branches, each referencing 16 of the L2 branches by hash.
    val l1: Seq[BranchNode] = (0 until 16).map { idx =>
      val children = emptyChildren
      (0 until 16).foreach(j => children(j) = HashNode(l2(idx * 16 + j).hash))
      val b = BranchNode(children, None)
      storage.putNode(b)
      b
    }

    // Root: references the 16 L1 branches.
    val rootChildren = emptyChildren
    l1.zipWithIndex.foreach { case (b, i) => rootChildren(i) = HashNode(b.hash) }
    val root = BranchNode(rootChildren, None)
    storage.putNode(root)

    WideLevelFixture(storage = storage, rootHash = ByteString(root.hash), expectedFrontier = missingCount)

  /** A childless present leaf root: the trivial trie used to take the restart (resume/DFS) branch without any walk
    * discovering anything. Mirrors `HealingFrontierResumeSpec.storedRoot`. Returns `(storage, rootHash)`.
    */
  def childlessLeafRoot(): (TestMptStorage, ByteString) =
    val storage = new TestMptStorage()
    val leaf = LeafNode(ByteString(1), ByteString(1))
    storage.putNode(leaf)
    (storage, ByteString(leaf.hash))
