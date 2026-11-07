package com.chipprbots.ethereum.blockchain.sync.snap.actors

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.mpt.BranchNode
import com.chipprbots.ethereum.mpt.ExtensionNode
import com.chipprbots.ethereum.mpt.HashNode
import com.chipprbots.ethereum.mpt.LeafNode
import com.chipprbots.ethereum.mpt.MptNode
import com.chipprbots.ethereum.mpt.MptTraversals
import com.chipprbots.ethereum.mpt.NullNode
import com.chipprbots.ethereum.testing.TestMptStorage

/** Deterministic synthetic-trie fixtures for the #1374 moving-root delta heal tests.
  *
  * Unlike [[HealingTrieFixtures]] — which stores nodes object-for-object for the rebuild-WALK tests — these fixtures
  * exercise the FETCH path: the heal seeds a frontier task, sends a `GetTrieNodes`, and `handleResponse` decodes the
  * served RAW RLP bytes and content-verifies them (`kec256(bytes) == requested task hash`) before storing. So every
  * served node here is returned as the exact `MptTraversals.encodeNode(node)` bytes whose `kec256` equals the hash the
  * coordinator requests — the same shape the production peer serves.
  *
  * No randomness: every node derives from a fixed [[kec256]] seed string, so the same `(rootHash, deltaBytes…)` come
  * out on every run and every host.
  *
  *   - [[deltaTrie]]: a small account trie whose ROOT is deliberately ABSENT locally (so the flag-ON
  *     seed-from-absent-root path fires) with a KNOWN missing-node delta: root extension → one absent leaf child. A
  *     peer serving the root + the child heals the entire 2-node delta and the frontier drains to empty.
  *   - [[incompleteSubtree]]: a present interior BranchNode whose child is ABSENT — the "download-present but
  *     subtree-incomplete" shape. Used by the completion-gate soundness test (C5): delta-discovery alone does NOT
  *     descend into a present interior node, so the absent grandchild would be missed without the pruned descent.
  */
object MovingRootDeltaHealFixtures:

  /** A served node: the (pathset, hash, raw-encoded-bytes) triple `handleResponse` matches on. `kec256(encoded) ==
    * hash`, and `pathset` is the GetTrieNodes path the coordinator requests for it.
    */
  final case class ServedNode(pathset: Seq[ByteString], hash: ByteString, encoded: ByteString)

  /** A known-delta fixture rooted at an ABSENT node.
    *
    * @param storage
    *   the (empty) [[TestMptStorage]] — the root and every delta node start ABSENT, exactly the fresh-heal mosaic where
    *   the served head-64 root was never built locally.
    * @param rootHash
    *   the absent heal root; `GetTrieNodes(rootHash, [[emptyPath]])` returns `rootNode.encoded`.
    * @param rootNode
    *   the served root node (empty-path).
    * @param childNode
    *   the single missing descendant discovered when the root is healed.
    * @param deltaNodes
    *   every node the heal must fetch to fully close the delta, in discovery order: root first, then its child.
    */
  final case class DeltaFixture(
      storage: TestMptStorage,
      rootHash: ByteString,
      rootNode: ServedNode,
      childNode: ServedNode,
      deltaNodes: Seq[ServedNode]
  ):

    /** The total node count in this delta (root + descendants). Used to assert the heal requests ≈ delta, not O(total).
      */
    def deltaSize: Int = deltaNodes.size

  private def emptyChildren: Array[MptNode] = Array.fill[MptNode](16)(NullNode)

  /** The account-trie empty-path compact key (the GetTrieNodes path for a root / account-trie node seed). */
  val emptyPath: ByteString =
    ByteString(com.chipprbots.ethereum.mpt.HexPrefix.encode(Array.empty[Byte], isLeaf = false))

  private def served(node: MptNode, pathset: Seq[ByteString]): ServedNode =
    val encoded = MptTraversals.encodeNode(node)
    ServedNode(pathset, kec256(ByteString(encoded)), ByteString(encoded))

  /** Build the known-delta fixture.
    *
    * Shape (account trie, `isStorage = false`), nothing stored locally:
    * {{{
    *   root (ExtensionNode, ABSENT)  sharedKey = [0x4]
    *     └─ next → child (LeafNode, ABSENT, HashNode-referenced)
    *   child : an EOA account leaf (storageRoot == EmptyStorageRootHash ⇒ no further children)
    * }}}
    *
    * Healing the root (empty path) → `discoverMissingChildren` decodes the extension, sees its `HashNode` child is not
    * on disk, enqueues EXACTLY ONE child task; healing that child (an empty-storage account leaf) discovers nothing →
    * the frontier drains to empty. Total fetched = 2 nodes = the whole delta.
    */
  def deltaTrie(): DeltaFixture =
    val storage = new TestMptStorage()

    // The missing descendant: a normal externally-owned-account leaf with empty storage and empty code, so the
    // account-trie discovery arm finds no storage root to seed (storageRoot == EmptyStorageRootHash) ⇒ no children.
    // The leaf value is large enough (a 4-field account RLP, ~70+ bytes) that the leaf is HashNode-referenced (≥ 32B)
    // by its parent rather than inline-encoded, so the extension references it by 32-byte hash exactly as production.
    val accountLeafKey = ByteString(Array[Byte](0x0a, 0x0b, 0x0c)) // arbitrary deterministic leaf-key nibbles
    val accountValue = ByteString(Account.empty().toBytes)
    val childLeaf = LeafNode(accountLeafKey, accountValue)
    val childEncoded = MptTraversals.encodeNode(childLeaf)
    val childHash = kec256(ByteString(childEncoded))
    // Child path: parentNibbles ([] for the empty-path root) ++ ext.sharedKey ([0x4]) ⇒ compact-encode([0x4]).
    val childCompact = ByteString(com.chipprbots.ethereum.mpt.HexPrefix.encode(Array[Byte](0x4), isLeaf = false))
    val childPathset = Seq(childCompact)
    val childServed = ServedNode(childPathset, childHash, ByteString(childEncoded))

    // The root is an extension referencing the child by hash. ABSENT locally.
    val rootExt = ExtensionNode(ByteString(Array[Byte](0x4)), HashNode(childHash.toArray))
    val rootServed = served(rootExt, Seq(emptyPath))

    DeltaFixture(
      storage = storage,
      rootHash = rootServed.hash,
      rootNode = rootServed,
      childNode = childServed,
      deltaNodes = Seq(rootServed, childServed)
    )

  /** A present-interior-node-with-absent-child fixture for the pruned-descent completion-gate test (C5).
    *
    * @param storage
    *   holds the root and the present interior BranchNode, but NOT `absentGrandchildHash`.
    * @param rootHash
    *   the present heal root (a BranchNode → the present interior node).
    * @param presentInteriorHash
    *   a BranchNode present on disk whose child `absentGrandchildHash` is NOT present — the download-mosaic gap that
    *   delta-discovery alone cannot see (it does not descend into a present interior node).
    * @param absentGrandchildHash
    *   the referenced-but-absent node the PRUNED DESCENT must catch.
    * @param grandchildServed
    *   the (path, hash, bytes) the heal fetches to CLOSE the gap once the descent has re-enqueued it. `hash ==
    *   absentGrandchildHash` and `kec256(bytes) == hash`, so serving it passes the content gate. The grandchild is a
    *   storage-trie leaf (no further children) so the post-heal descent is clean and completion is declared.
    */
  final case class IncompleteSubtreeFixture(
      storage: TestMptStorage,
      rootHash: ByteString,
      presentInteriorHash: ByteString,
      absentGrandchildHash: ByteString,
      grandchildServed: ServedNode
  )

  /** Build the download-present-but-incomplete-subtree fixture. The root and the interior branch are stored
    * object-for-object (the heal reads them back as present); the interior branch references a grandchild hash that is
    * deliberately NOT stored. delta-discovery stops at the present interior node; only a descent into it finds the gap.
    *
    * The absent grandchild is a real LeafNode whose RLP `kec256` equals the referenced hash, so the completion-gate
    * test can also serve it (close the gap) and confirm a later clean descent declares completion. The grandchild's
    * served path is `[interior-slot-3-nibble]` — the HP-compact path the descent assigns when it re-enqueues slot 3 of
    * the (empty-path) interior branch reached via root slot 0 — but the heal does not assert on the path here; the test
    * fishes the actual re-enqueued GetTrieNodes and serves by the requested hash.
    */
  def incompleteSubtree(): IncompleteSubtreeFixture =
    val storage = new TestMptStorage()

    // The absent grandchild is a REAL storage-trie leaf (≥ 32B value ⇒ HashNode-referenced, served by hash). Its
    // kec256 IS the hash the interior branch references, so the descent's re-enqueued task accepts the served bytes.
    val grandLeaf =
      LeafNode(
        ByteString(Array[Byte](0x07)),
        ByteString(kec256(ByteString("moving-root/incomplete/grandchild")).toArray)
      )
    val grandEncoded = MptTraversals.encodeNode(grandLeaf)
    val absentGrandchildHash = kec256(ByteString(grandEncoded))

    // A PRESENT interior branch whose slot-3 child is the absent grandchild.
    val interiorChildren = emptyChildren
    interiorChildren(3) = HashNode(absentGrandchildHash.toArray)
    val interior = BranchNode(interiorChildren, None)
    storage.putNode(interior)
    val interiorHash = ByteString(interior.hash)

    // A PRESENT root branch referencing the interior node.
    val rootChildren = emptyChildren
    rootChildren(0) = HashNode(interior.hash)
    val root = BranchNode(rootChildren, None)
    storage.putNode(root)

    // Served-grandchild path: root-slot-0 → interior-slot-3 ⇒ account-trie nibbles [0x0, 0x3]. The heal fetches it by
    // hash; the path here documents the descent's child-path arithmetic for the reader (not asserted in the test).
    val grandCompact = ByteString(com.chipprbots.ethereum.mpt.HexPrefix.encode(Array[Byte](0x0, 0x3), isLeaf = false))

    IncompleteSubtreeFixture(
      storage = storage,
      rootHash = ByteString(root.hash),
      presentInteriorHash = interiorHash,
      absentGrandchildHash = absentGrandchildHash,
      grandchildServed = ServedNode(Seq(grandCompact), absentGrandchildHash, ByteString(grandEncoded))
    )
