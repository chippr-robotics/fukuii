package com.chipprbots.ethereum.blockchain.sync

import org.apache.pekko.util.ByteString

import scala.collection.mutable
import scala.util.Success

import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.mpt._
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
final class CombinedRecoveryScan(mptStorage: MptStorage, evmCodeStorage: EvmCodeStorage) {

  private val seenCodeHashes = mutable.HashSet.empty[ByteString]
  private val seenStorageRoots = mutable.HashSet.empty[ByteString]
  private val missingCode = mutable.ArrayBuffer.empty[ByteString]
  private val missingStorage = mutable.ArrayBuffer.empty[(ByteString, ByteString)]

  /** Per-leaf callback for a [[PathTrackingLeafWalkVisitor]] walk. `accountHash` is the 32-byte trie key
    * (keccak256(address)); `leaf` carries the RLP-encoded [[Account]].
    */
  def onLeaf(accountHash: ByteString, leaf: LeafNode): Unit =
    Account(leaf.value) match {
      case Success(account) =>
        // Bytecode: a contract whose code is referenced but absent from EvmCodeStorage.
        if (account.codeHash != Account.EmptyCodeHash && seenCodeHashes.add(account.codeHash)) {
          if (evmCodeStorage.get(account.codeHash).isEmpty) missingCode += account.codeHash
        }
        // Storage: a contract whose storage-root node is referenced but absent from MptStorage.
        if (account.storageRoot != Account.EmptyStorageRootHash && seenStorageRoots.add(account.storageRoot)) {
          try {
            val _ = mptStorage.get(account.storageRoot.toArray)
          } catch {
            case _: MerklePatriciaTrie.MPTException => missingStorage += ((accountHash, account.storageRoot))
          }
        }
      case _ => () // skip malformed account RLP, as both legacy scans do
    }

  /** Walk the whole account trie at `rootHash` (resolving the root via storage). */
  def scanFrom(rootHash: ByteString): Unit =
    MptTraversals.dispatch(HashNode(rootHash.toArray), new PathTrackingLeafWalkVisitor(mptStorage, ByteString.empty, onLeaf))

  /** Walk the (already-resolved) subtree `root`, with `pathPrefix` the nibbles consumed to reach it (for sharding). */
  def scanShard(root: MptNode, pathPrefix: ByteString): Unit =
    MptTraversals.dispatch(root, new PathTrackingLeafWalkVisitor(mptStorage, pathPrefix, onLeaf))

  def missingBytecodes: Seq[ByteString] = missingCode.toSeq

  def missingStorageTries: Seq[(ByteString, ByteString)] = missingStorage.toSeq
}
