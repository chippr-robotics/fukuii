package com.chipprbots.ethereum.db.storage

import org.apache.pekko.util.ByteString

import scala.util.Try

import com.chipprbots.ethereum.utils.Hex

/** Resumable progress for the post-SNAP recovery scan, persisted so a crash/OOM mid-scan resumes from the last
  * completed shard instead of re-walking the whole ~86M-account ETC state trie from account 0.
  *
  * The scan partitions the state trie into disjoint shards (see [[com.chipprbots.ethereum.mpt.ShardEnumerator]]); each
  * shard, on completion, atomically records its index into [[completedShards]] together with the gaps it found in
  * [[missingBytecodes]]/[[missingStorageTries]]. Because both go into a single value under a single key, a crash can
  * never leave a shard "marked done" with its gaps lost (or vice versa) — the persisted state is always
  * self-consistent.
  *
  * @param scanRoot
  *   the state root being scanned (the on-disk SNAP-finalized/pivot root). Forms half the resume tag: re-enumerating
  *   shards from the SAME root is deterministic, so persisted shard indices stay meaningful only while the root is
  *   unchanged. A different root (pivot refresh) ⇒ different partition ⇒ stale indices ⇒ discard and re-scan.
  * @param shardCount
  *   number of shards the trie was partitioned into. The other half of the resume tag: a partition-fanout (depth)
  *   reconfig changes this, invalidating prior indices.
  * @param completedShards
  *   indices (0 until shardCount) of shards already fully walked; their gaps are already in the lists below.
  * @param missingBytecodes
  *   accumulated missing contract-code hashes (32 bytes each).
  * @param missingStorageTries
  *   accumulated missing contract storage tries as (accountHash, storageRoot) pairs. May contain the same storageRoot
  *   for accounts in different shards; cross-shard dedup is the driver's job, not the codec's.
  *
  * Invariants a persisted progress always satisfies (enforced by [[RecoveryProgress.deserialize]] — a value violating
  * any of them is treated as corrupt and read back as None, forcing a fresh scan):
  *   - `shardCount >= 1` — a checkpoint is only written for a non-empty trie; an empty trie has nothing to scan and is
  *     marked done directly, never via a checkpoint.
  *   - every hash (`scanRoot`, each bytecode, each account/storageRoot) is exactly 32 bytes.
  *   - `completedShards ⊆ [0, shardCount)`.
  */
final case class RecoveryProgress(
    scanRoot: ByteString,
    shardCount: Int,
    completedShards: Set[Int],
    missingBytecodes: Vector[ByteString],
    missingStorageTries: Vector[(ByteString, ByteString)]
) {

  /** True once every shard index has been walked — the gap lists are then complete and download can proceed. */
  def isComplete: Boolean = completedShards.size >= shardCount

  /** Shard indices still to be scanned on resume. */
  def remainingShards: Seq[Int] = (0 until shardCount).filterNot(completedShards)
}

object RecoveryProgress {

  /** Format version. Bump on any layout change; an unrecognised version deserialises to None (⇒ safe fresh scan). */
  private val Version = "v1"

  /** Section delimiter. Safe because every field below is hex `[0-9a-f]`, decimal digits, or those joined by `,`/`:` —
    * none of which contain a newline.
    */
  private val FieldSep = "\n"
  private val ListSep = ","
  private val PairSep = ":"

  /** Serialise to a single string value for [[AppStateStorage]]. Six newline-separated fields:
    * {{{v1 \n <scanRootHex> \n <shardCount> \n <completedIdxCsv> \n <bytecodeHexCsv> \n <accountHex:storageHex csv>}}}
    * Empty lists serialise to an empty field (not a stray token), so an all-empty progress round-trips faithfully.
    */
  def serialize(p: RecoveryProgress): String = {
    val completed = p.completedShards.toVector.sorted.map(_.toString).mkString(ListSep)
    val bytecodes = p.missingBytecodes.map(b => Hex.toHexString(b.toArray)).mkString(ListSep)
    val storage = p.missingStorageTries
      .map { case (acct, root) => Hex.toHexString(acct.toArray) + PairSep + Hex.toHexString(root.toArray) }
      .mkString(ListSep)
    Seq(Version, Hex.toHexString(p.scanRoot.toArray), p.shardCount.toString, completed, bytecodes, storage)
      .mkString(FieldSep)
  }

  /** Total inverse of [[serialize]]: ANY malformed, truncated, wrong-version, or invariant-violating input yields None
    * so the caller falls back to a fresh (always-correct) scan rather than trusting a partially-written or corrupt
    * checkpoint. The strict per-field validation below is deliberate defense-in-depth: it turns a value that would
    * otherwise deserialise to a *wrong-but-plausible* RecoveryProgress (a sub-32-byte hash, an out-of-range shard
    * index, shardCount 0 → spuriously "complete") into a clean None, so corruption can never masquerade as a finished
    * scan.
    */
  def deserialize(s: String): Option[RecoveryProgress] = Try {
    // -1 keeps trailing empty fields (e.g. an empty storage list as the last field).
    val fields = s.split(FieldSep, -1)
    require(fields.length == 6, "wrong field count")
    require(fields(0) == Version, "version mismatch")
    val scanRoot = decodeHash(fields(1))
    val shardCount = fields(2).toInt
    require(shardCount >= 1, "shardCount must be >= 1 (a checkpoint is only persisted for a non-empty trie)")
    val completed = splitList(fields(3)).iterator.map(_.toInt).toSet
    require(completed.forall(i => i >= 0 && i < shardCount), "completed shard index out of range")
    val bytecodes = splitList(fields(4)).iterator.map(decodeHash).toVector
    val storage = splitList(fields(5)).iterator.map { pair =>
      val parts = pair.split(PairSep, -1)
      require(parts.length == 2, "malformed storage pair")
      (decodeHash(parts(0)), decodeHash(parts(1)))
    }.toVector
    RecoveryProgress(scanRoot, shardCount, completed, bytecodes, storage)
  }.toOption

  /** Decode a 32-byte hash field, rejecting anything that isn't EXACTLY 64 hex chars. The length check is on the hex
    * string (not the decoded byte count) on purpose: `Hex.decode` parses a lone trailing nibble rather than dropping
    * it, so a 63-char field would still produce 32 bytes with a corrupted final byte and slip past a byte-length check.
    * Non-hex chars throw inside `Hex.decode` → caught by deserialize's `Try` → None.
    */
  private def decodeHash(hex: String): ByteString = {
    require(hex.length == 64, "hash field must be exactly 64 hex chars (32 bytes)")
    ByteString(Hex.decode(hex))
  }

  /** Split a CSV field, treating "" as the empty list (Java's `"".split(",")` returns `Array("")`, not `Array()`). */
  private def splitList(s: String): Array[String] = if (s.isEmpty) Array.empty else s.split(ListSep)
}
