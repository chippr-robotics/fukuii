package com.chipprbots.ethereum.blockchain.sync.snap.heal

import org.apache.pekko.util.ByteString

/** What a heal request fetches (spec 022 data-model.md "Heal request", `kind`). Code requests (D4) arrive in slice 3.
  */
enum HealRequestKind:
  case AccountNode, StorageNode

/** One heal request (spec 022 D2, data-model.md "Heal request"): a trie node that healing must store, and where.
  *
  * Under the Path scheme a request is one trie POSITION, keyed by [[HealRequest.positionKey]]: the same node needed at
  * two positions (the identical storage tries of two accounts, say) is two requests, so neither position can be lost to
  * a de-duplication by hash. Under the Hash scheme a request is one NODE, keyed by its hash: storage is
  * content-addressed, so a node shared by several parents is stored once and requested once; its parents are counted as
  * edges in [[HealRequestBook]].
  *
  * @param key
  *   Path: the position key; Hash: the node hash. One `ByteString`, never a `Seq` (a `Seq[ByteString]` key hashes with
  *   `MurmurHash3.seqHash`, which showed up in the Mordor heal profile).
  * @param hash
  *   the keccak256 the stored node must have. A served node is accepted only when its keccak equals it (the spec 004
  *   consensus-safety invariant), and responses are matched by it: hash to every request waiting on it.
  * @param pathset
  *   where it is fetched from and stored at, as `GetTrieNodes` names it: `Seq(compactPath)` for an account-trie node,
  *   `Seq(accountHash, compactPath)` for a node of that account's storage trie, compact = HP-encoded with `isLeaf =
  *   false`.
  * @param depth
  *   nibbles from the state root: the account-trie path length, or 64 plus the storage-trie path length (a storage trie
  *   hangs below a 64-nibble account leaf). geth's request priority `len(path)`; dispatch becomes deepest first in
  *   slice 2 (D1).
  */
final case class HealRequest(key: ByteString, hash: ByteString, pathset: Seq[ByteString], depth: Int):
  def kind: HealRequestKind = if pathset.size > 1 then HealRequestKind.StorageNode else HealRequestKind.AccountNode

object HealRequest:

  /** Nibbles in a full account path: a storage trie's root sits this deep below the state root. */
  val AccountPathNibbles: Int = 64

  private val AccountTrieTag: Byte = 0
  private val StorageTrieTag: Byte = 1

  /** The Path-scheme request key of the position `pathset` names: a trie tag, then, for a storage trie, the owning
    * account hash (32 bytes), then the HP-encoded path. The same layout as the frontier walk's location key
    * (`FrontierRebuildBfs.locationKey`).
    *
    * The tag keeps the two tries apart: a 64-nibble account path HP-encodes to 33 bytes, as long as an account hash
    * plus an empty storage path, so without it two different positions could share a key. A pathset of more than one
    * element is a storage-trie position (owner `pathset.head`, path `pathset.last`), exactly as healing writes it.
    * Every heal source encodes paths with `isLeaf = false`, so equal positions have equal bytes.
    */
  def positionKey(pathset: Seq[ByteString]): ByteString =
    // One flat array (as `FrontierRebuildBfs.locationKey` builds it), not a rope of `++` chunks: the key is hashed and
    // compared on every lookup.
    val builder = ByteString.newBuilder
    if pathset.size > 1 then builder.putByte(StorageTrieTag).append(pathset.head).append(pathset.last)
    else builder.putByte(AccountTrieTag).append(pathset.lastOption.getOrElse(ByteString.empty))
    builder.result()

  /** Nibbles from the state root to the position `pathset` names (see `depth`). */
  def depthOf(pathset: Seq[ByteString]): Int =
    val inTrie = pathset.lastOption.fold(0)(compactNibbles)
    if pathset.size > 1 then AccountPathNibbles + inTrie else inTrie

  /** The nibble count of an HP-encoded path: two per byte after the flag byte, plus one when the odd flag is set. */
  private def compactNibbles(compact: ByteString): Int =
    if compact.isEmpty then 0
    else 2 * (compact.length - 1) + ((compact.head >> 4) & 1)

  /** The Path-scheme request for `hash` at `pathset`. */
  def atPosition(pathset: Seq[ByteString], hash: ByteString): HealRequest =
    HealRequest(positionKey(pathset), hash, pathset, depthOf(pathset))

  /** The Hash-scheme request for the node `hash`, fetched from `pathset`. */
  def ofNode(pathset: Seq[ByteString], hash: ByteString): HealRequest =
    HealRequest(hash, hash, pathset, depthOf(pathset))

/** What the bytes stored at a Path-scheme position say about the node expected there (spec 022 FR-008, geth `hasNode`).
  */
enum PathPresence:
  /** Nothing is stored at the position. */
  case Absent

  /** The bytes stored there hash to the expected node. */
  case Present

  /** Bytes are stored there, but they hash to another node: a stale node from an older root. The write that makes the
    * position reference the expected node deletes them in its own batch.
    */
  case Mismatch
