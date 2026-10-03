package com.chipprbots.ethereum.blockchain.sync.snap.actors

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.db.storage.PathNodeStorage
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.domain.TrieRoot
import com.chipprbots.ethereum.domain.UInt256
import com.chipprbots.ethereum.mpt.BranchNode
import com.chipprbots.ethereum.mpt.ExtensionNode
import com.chipprbots.ethereum.mpt.HashNode
import com.chipprbots.ethereum.mpt.HexPrefix
import com.chipprbots.ethereum.mpt.LeafNode
import com.chipprbots.ethereum.mpt.MptNode
import com.chipprbots.ethereum.mpt.NullNode
import com.chipprbots.ethereum.testing.TestMptStorage

/** Deterministic synthetic tries for the SNAP-path-heal-presence regression tests (`PathHealPresenceSpec`).
  *
  * Every node is built from fixed bytes (`kec256` of a fixed seed string), so the same tries — the same hashes, the
  * same expected request sets — come out on every run and every host. Each trie node is a [[Placed]]: the node plus the
  * trie position it is stored at under the Path scheme. A re-peg is modelled the way a real one behaves: the "old" and
  * "new" builds of a trie share every node whose content did not change (same bytes, same hash, same path) and differ
  * only along the path from the changed leaf up to the root, because a branch/extension hash commits to its child's
  * hash.
  *
  * Node sizes: every leaf encodes to ≥ 32 bytes and every branch/extension holds ≥ one 32-byte hash reference, so the
  * parent always references a child by [[HashNode]] (never inlined) — exactly the shape in which the coordinator's
  * `discoverMissingChildren` must look each child up on its own.
  */
object PathHealPresenceFixtures:

  /** A trie node and where it lives. `nibbles` is the raw nibble path (what `PathNodeStorage.writeAccountNode` /
    * `writeStorageNode` take — they HP-encode it internally); `accountHash` is set for a storage-trie node, whose
    * `GetTrieNodes` pathset is `Seq(accountHash, compactPath)`.
    */
  final case class Placed(label: String, accountHash: Option[ByteString], nibbles: Array[Byte], node: MptNode):
    def hash: ByteString = ByteString(node.hash)
    def encoded: ByteString = ByteString(node.encode)

    /** The pathset the coordinator queues and requests this node with (HP/compact-encoded, `isLeaf = false`). */
    def pathset: Seq[ByteString] = accountHash.toSeq :+ ByteString(HexPrefix.encode(nibbles, isLeaf = false))

  /** A whole trie: every node of one root, addressable by label. */
  final case class Trie(nodes: Seq[Placed]):
    private val byLabel: Map[String, Placed] = nodes.map(n => n.label -> n).toMap
    def apply(label: String): Placed = byLabel(label)
    def root: Placed = byLabel("root")

    /** What a serving peer holds for THIS trie: pathset → node bytes. A real peer resolves a requested path against its
      * own copy of the trie by position, not by the hash the requester expects.
      */
    def served: Map[Seq[ByteString], ByteString] = nodes.map(n => n.pathset -> n.encoded).toMap

    /** The nodes whose content (hence hash) differs from the same-labelled node of `old` — the re-peg delta. */
    def changedFrom(old: Trie): Seq[Placed] = nodes.filter(n => old(n.label).hash != n.hash)

    /** The nodes identical (same bytes, same hash, same path) to the same-labelled node of `old`. */
    def unchangedFrom(old: Trie): Seq[Placed] = nodes.filter(n => old(n.label).hash == n.hash)

  // ---- node builders -----------------------------------------------------------------------------------------------

  /** A leaf whose value is deliberately NOT an RLP account (`Account(value)` fails), so it is a plain trie leaf with no
    * storage trie to follow. First byte 0x01 + 32-byte digest ⇒ 33-byte value ⇒ the leaf encodes to ≥ 32 bytes.
    */
  private def plainLeaf(key: Array[Byte], seed: String): LeafNode =
    LeafNode(ByteString(key), ByteString(Array[Byte](0x01)) ++ kec256(ByteString(seed)))

  private def branch(children: (Int, MptNode)*): BranchNode =
    val slots = Array.fill[MptNode](16)(NullNode)
    children.foreach { case (i, child) => slots(i) = HashNode(child.hash) }
    BranchNode(slots, None)

  /** Pack 64 nibbles into the 32-byte account hash exactly as the coordinator does (`(hi << 4) | lo`). */
  private def packNibbles(nibbles: Array[Byte]): ByteString =
    ByteString(nibbles.grouped(2).map(g => ((g(0) << 4) | g(1)).toByte).toArray)

  // ---- trie A: branch → extension → branch, plus an untouched sibling subtree --------------------------------------

  /** Account trie (no storage tries):
    * {{{
    *   root (branch)  []
    *     ├─[0] ext (sharedKey 5,6)   [0]
    *     │        └─ brX (branch)    [0,5,6]
    *     │             ├─[1] leafX1  [0,5,6,1]   ← the leaf whose content differs between builds
    *     │             └─[2] leafX2  [0,5,6,2]
    *     └─[1] brY (branch)          [1]
    *           ├─[0] leafY0          [1,0]
    *           └─[1] leafY1          [1,1]
    * }}}
    * `leafX1Seed` selects leafX1's content. Two builds that differ only in it differ in exactly `leafX1, brX, ext,
    * root` (the path from the changed leaf to the root); `leafX2, brY, leafY0, leafY1` are byte-identical at the same
    * paths. That is 4 changed nodes of 8, and the extension means the changed path crosses an extension node.
    */
  def accountTrie(leafX1Seed: String): Trie =
    val leafX1 = plainLeaf(Array[Byte](0x0a), leafX1Seed)
    val leafX2 = plainLeaf(Array[Byte](0x0b), "path-heal-presence/leafX2")
    val brX = branch(1 -> leafX1, 2 -> leafX2)
    val ext = ExtensionNode(ByteString(Array[Byte](5, 6)), HashNode(brX.hash))
    val leafY0 = plainLeaf(Array[Byte](0x0c), "path-heal-presence/leafY0")
    val leafY1 = plainLeaf(Array[Byte](0x0d), "path-heal-presence/leafY1")
    val brY = branch(0 -> leafY0, 1 -> leafY1)
    val root = branch(0 -> ext, 1 -> brY)
    Trie(
      Seq(
        Placed("root", None, Array.empty[Byte], root),
        Placed("ext", None, Array[Byte](0), ext),
        Placed("brX", None, Array[Byte](0, 5, 6), brX),
        Placed("leafX1", None, Array[Byte](0, 5, 6, 1), leafX1),
        Placed("leafX2", None, Array[Byte](0, 5, 6, 2), leafX2),
        Placed("brY", None, Array[Byte](1), brY),
        Placed("leafY0", None, Array[Byte](1, 0), leafY0),
        Placed("leafY1", None, Array[Byte](1, 1), leafY1)
      )
    )

  def accountTrieOld(): Trie = accountTrie("path-heal-presence/leafX1-old")
  def accountTrieNew(): Trie = accountTrie("path-heal-presence/leafX1-new")

  // ---- trie B: one account with a storage trie ---------------------------------------------------------------------

  /** The 64 fixed nibbles of the single account's hashed address: the account trie is just that account's leaf. */
  private val accountNibbles: Array[Byte] = (0 until 64).map(i => ((i * 7 + 3) % 16).toByte).toArray
  val accountHash: ByteString = packNibbles(accountNibbles)

  /** Account trie = one leaf (the account) at the empty path; that account's storage trie hangs off it:
    * {{{
    *   root   = account leaf, path []                      storageRoot ↦ sRoot
    *   sRoot  (branch)   (accountHash, [])
    *     ├─[0] sLeaf0     (accountHash, [0])
    *     └─[1] sLeaf1     (accountHash, [1])   ← the slot whose content differs between builds
    * }}}
    * A changed `sLeaf1` changes `sRoot`, hence the `storageRoot` inside the account leaf, hence the account leaf (the
    * account-trie root). `sLeaf0` is byte-identical at the same storage path. This is the only fixture whose pathsets
    * are `Seq(accountHash, compactPath)`.
    */
  def storageTrie(slot1Seed: String): Trie =
    val sLeaf0 = plainLeaf(Array[Byte](0x01), "path-heal-presence/storage/slot0")
    val sLeaf1 = plainLeaf(Array[Byte](0x02), slot1Seed)
    val sRoot = branch(0 -> sLeaf0, 1 -> sLeaf1)
    val account =
      Account(nonce = UInt256(1L), balance = UInt256(1000L), storageRoot = TrieRoot(ByteString(sRoot.hash)))
    val accountLeaf = LeafNode(ByteString(accountNibbles), ByteString(Account.accountSerializer.toBytes(account)))
    Trie(
      Seq(
        Placed("root", None, Array.empty[Byte], accountLeaf),
        Placed("sRoot", Some(accountHash), Array.empty[Byte], sRoot),
        Placed("sLeaf0", Some(accountHash), Array[Byte](0), sLeaf0),
        Placed("sLeaf1", Some(accountHash), Array[Byte](1), sLeaf1)
      )
    )

  def storageTrieOld(): Trie = storageTrie("path-heal-presence/storage/slot1-old")
  def storageTrieNew(): Trie = storageTrie("path-heal-presence/storage/slot1-new")

  // ---- seeding -----------------------------------------------------------------------------------------------------

  /** Write `nodes` into `pns`, each at its own trie path (account CF or per-account storage CF) — "present locally". */
  def seedPath(pns: PathNodeStorage, nodes: Seq[Placed]): Unit =
    nodes.foreach { n =>
      n.accountHash match
        case None     => pns.writeAccountNode(n.nibbles, n.node.encode)
        case Some(ah) => pns.writeStorageNode(ah, n.nibbles, n.node.encode)
    }

  /** The bytes currently stored at `n`'s path (whatever they are — stale, right, or none). */
  def readAtPath(pns: PathNodeStorage, n: Placed): Option[Array[Byte]] =
    n.accountHash match
      case None     => pns.readAccountNode(n.nibbles)
      case Some(ah) => pns.readStorageNode(ah, n.nibbles)

  /** Hash-scheme equivalent of [[seedPath]]: store `nodes` content-addressed (position is irrelevant there). */
  def seedHash(storage: TestMptStorage, nodes: Seq[Placed]): Unit =
    nodes.foreach(n => storage.putNode(n.node))
