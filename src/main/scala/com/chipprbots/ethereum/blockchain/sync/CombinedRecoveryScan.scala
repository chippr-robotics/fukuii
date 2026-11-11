package com.chipprbots.ethereum.blockchain.sync

import org.apache.pekko.util.ByteString

import scala.collection.mutable
import scala.util.Success

import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.domain.CodeHash
import com.chipprbots.ethereum.mpt.*
import com.chipprbots.ethereum.mpt.MptVisitors.PathTrackingLeafWalkVisitor

/** Single-pass post-SNAP recovery scan: ONE walk of the account trie that checks BOTH the contract bytecode
  * (`evmCodeStorage`) and the contract storage-root node (`mptStorage`) per account leaf.
  *
  * Replaces the two independent full-trie walks of [[BytecodeRecoveryActor]] (codeHash present in evmCodeStorage?) and
  * [[StorageRecoveryActor]] (storageRoot node present in MptStorage?), eliminating the redundant second RLP decode of
  * every node — the dominant CPU cost on a large chain. The per-leaf logic mirrors both legacy scans exactly so the
  * combined result is identical to running them separately.
  *
  * One instance accumulates one shard's gaps; the parallel driver merges per-shard results (storage-root dedup must be
  * re-applied across shards, since the same storageRoot can be referenced by accounts in different shards).
  */
final class CombinedRecoveryScan(
    mptStorage: MptStorage,
    evmCodeStorage: EvmCodeStorage,
    // Fired once per decoded account leaf with `isContract` (storageRoot != empty). The driver uses it to publish
    // live scan-progress gauges; default no-op keeps the unit tests metric-free. Must be cheap + thread-safe (it runs
    // inside parallel shard walks).
    onAccount: Boolean => Unit = _ => ()
):

  // The account trie, and whether a storage trie seen under one account stands for another's (spec 018 Track C).
  private val accountTrie = mptStorage.forOwner(None)
  private val shared = mptStorage.nodesSharedAcrossTries

  private val seenCodeHashes = mutable.HashSet.empty[ByteString]
  private val seenStorageRoots = mutable.HashSet.empty[ByteString]
  private val missingCode = mutable.ArrayBuffer.empty[ByteString]
  private val missingStorage = mutable.ArrayBuffer.empty[(ByteString, ByteString)]
  private var holeCount = 0L

  /** Per-leaf callback for a [[PathTrackingLeafWalkVisitor]] walk. `accountHash` is the 32-byte trie key
    * (keccak256(address)); `leaf` carries the RLP-encoded [[Account]].
    */
  def onLeaf(accountHash: ByteString, leaf: LeafNode): Unit =
    Account(leaf.value) match
      case Success(account) =>
        // Bytecode: a contract whose code is referenced but absent from EvmCodeStorage.
        if account.codeHash != Account.EmptyCodeHash && seenCodeHashes.add(account.codeHash.value) then
          if evmCodeStorage.get(account.codeHash.value).isEmpty then missingCode += account.codeHash.value
        // Storage: a contract whose storage-root node is referenced but absent from MptStorage.
        val isContract = account.storageRoot != Account.EmptyStorageRootHash
        if isContract && seenStorageRoots.add(CombinedRecoveryScan.storageTrieKey(shared, accountHash, account.storageRoot.value)) then
          try
            // the root of THIS account's storage trie
            val _ = mptStorage.forOwner(Some(accountHash)).getAt(NibblePath.Empty, account.storageRoot.toArray)
          catch case _: MerklePatriciaTrie.MPTException => missingStorage += ((accountHash, account.storageRoot.value))
        onAccount(isContract)
      case _ => () // skip malformed account RLP, as both legacy scans do

  /** Walk the whole account trie at `rootHash` (resolving the root via storage). */
  def scanFrom(rootHash: ByteString): Unit =
    MptTraversals.dispatch(
      HashNode(rootHash.toArray),
      new PathTrackingLeafWalkVisitor(accountTrie, ByteString.empty, onLeaf)
    )

  /** Walk the subtree `root`, with `pathPrefix` the nibbles consumed to reach it (for sharding). An absent node is
    * counted in [[holes]], and the walk goes on over its siblings (spec 022 T050a): a datadir handed off lazily has
    * holes, and a pass that stopped at the first of them would look at almost nothing.
    */
  def scanShard(root: MptNode, pathPrefix: ByteString): Unit =
    MptTraversals.dispatch(
      root,
      new PathTrackingLeafWalkVisitor(accountTrie, pathPrefix, onLeaf, Some(_ => holeCount += 1))
    )

  def missingBytecodes: Seq[ByteString] = missingCode.toSeq

  def missingStorageTries: Seq[(ByteString, ByteString)] = missingStorage.toSeq

  /** Subtrees [[scanShard]] could not read: nothing under each of them was looked at. */
  def holes: Long = holeCount

object CombinedRecoveryScan:

  /** What a scan tells a storage trie by, when it asks whether it has looked at this one already.
    *
    * Where a node is stored once whatever trie holds it (`shared`, a hash store), its root: the trie two accounts name
    * is one trie, looked at once and fetched once. Where each account has a trie of its own (a position store), the
    * account as well: the second account's trie can be missing while the first is whole (spec 022 V3), and a scan that
    * skipped it for having seen its root would never find that.
    */
  def storageTrieKey(shared: Boolean, accountHash: ByteString, storageRoot: ByteString): ByteString =
    if shared then storageRoot else accountHash ++ storageRoot
