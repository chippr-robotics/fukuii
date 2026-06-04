package com.chipprbots.ethereum.mpt

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.db.storage.MptStorage

/** Splits a state trie into disjoint, exhaustive shards so the post-SNAP recovery scan can walk it in parallel.
  *
  * A [[Shard]] is a subtree rooted at the node reached by consuming `pathPrefix` nibbles from the state root. The set
  * of shards partitions the trie's account leaves: every leaf belongs to exactly one shard, and the union of all shard
  * subtrees is the whole trie. Because account keys are `keccak256(address)` (uniform), the state root is a 16-way
  * BranchNode, so `depth = 1` yields up to 16 shards (one per populated first nibble) — the natural parallel unit.
  *
  * Correctness rests on two cases:
  *   - BranchNode: each populated child becomes its own shard (or recurses one level deeper). The children are disjoint
  *     by construction (distinct nibble) and exhaustive (every leaf descends through exactly one child).
  *   - ExtensionNode: all leaves under it share `sharedKey`, so it does NOT branch — we descend through it WITHOUT
  *     spending a shard level, extending the prefix by its nibbles. This is the subtle case (a short/degenerate trie
  *     whose root is an extension): mishandled, it would split or skip the shared subtree.
  *
  * A `pathPrefix` is the nibble path (one nibble per byte) consumed to reach the shard root; seeding a
  * [[com.chipprbots.ethereum.mpt.MptVisitors.PathTrackingLeafWalkVisitor]] with it reconstructs full account hashes
  * during the shard walk.
  */
object ShardEnumerator {

  /** A disjoint subtree of the state trie. `root` is already resolved (never a bare [[HashNode]]); its children may
    * still be HashNodes that the walk resolves on demand.
    */
  final case class Shard(pathPrefix: ByteString, root: MptNode)

  /** Resolve `rootHash` and split into shards by descending `depth` branch levels (default 1 = up to 16 shards). An
    * empty trie (`EmptyRootHash`, never stored) yields no shards.
    */
  def enumShards(rootHash: ByteString, storage: MptStorage, depth: Int = 1): Vector[Shard] =
    if (java.util.Arrays.equals(rootHash.toArray, MerklePatriciaTrie.EmptyRootHash)) Vector.empty
    else expand(ByteString.empty, resolve(HashNode(rootHash.toArray), storage), storage, depth)

  private def resolve(node: MptNode, storage: MptStorage): MptNode = node match {
    case h: HashNode => storage.get(h.hashNode)
    case other       => other
  }

  private def expand(prefix: ByteString, node: MptNode, storage: MptStorage, depth: Int): Vector[Shard] =
    node match {
      case NullNode           => Vector.empty
      case _ if depth <= 0    => Vector(Shard(prefix, node))
      case _: LeafNode        => Vector(Shard(prefix, node)) // a single account — can't split further
      case ext: ExtensionNode =>
        // The extension consumes shared nibbles without branching, so it does NOT spend a shard level.
        expand(prefix ++ ext.sharedKey, resolve(ext.next, storage), storage, depth)
      case br: BranchNode =>
        (0 until BranchNode.numberOfChildren).iterator.flatMap { i =>
          br.children(i) match {
            case NullNode => Iterator.empty
            case child    => expand(prefix ++ ByteString(i.toByte), resolve(child, storage), storage, depth - 1)
          }
        }.toVector
      case h: HashNode => expand(prefix, storage.get(h.hashNode), storage, depth)
    }
}
