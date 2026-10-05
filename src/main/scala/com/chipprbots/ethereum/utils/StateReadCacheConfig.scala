package com.chipprbots.ethereum.utils

import com.typesafe.config.Config as TsConfig
import com.typesafe.config.ConfigFactory

import org.slf4j.LoggerFactory

/** Sizes of the in-process read caches on the block-import path (`state-read-caches` in `base/state-read-caches.conf`).
  *
  * Read straight from the typesafe config rather than through [[Config]], so the EVM and storage classes that hold
  * these caches as process-wide singletons do not drag the whole node configuration into a unit test. A missing key
  * falls back to the default below, which is also the value shipped in the conf file.
  */
object StateReadCacheConfig:

  private lazy val section: Option[com.typesafe.config.Config] =
    val root = ConfigFactory.load()
    if root.hasPath("fukuii.state-read-caches") then Some(root.getConfig("fukuii.state-read-caches")) else None

  private def bytes(path: String, default: Long): Long =
    section.filter(_.hasPath(path)).map(_.getLong(path)).getOrElse(default)

  private def flag(path: String, default: Boolean): Boolean =
    section.filter(_.hasPath(path)).map(_.getBoolean(path)).getOrElse(default)

  /** Whether the per-block `[IMPORT-TIMING]` line is logged at INFO (true) or DEBUG (false, the default). Lives in this
    * section because the timers instrument these caches and `ImportProfile` already reads it without initialising the
    * whole node [[Config]].
    */
  lazy val importTimingLog: Boolean = flag("import-timing-log", false)

  private lazy val log = LoggerFactory.getLogger(getClass)

  /** An explicitly configured size is honoured as-is up to this share of the max heap (a safety ceiling: a typo such as
    * a size in the wrong unit must not take the whole heap). Above it the size is clamped and a WARN is logged.
    */
  val ExplicitCeilingFraction: Double = 0.5

  /** The size of one cache. The heap-fraction cap applies to the DEFAULT only: an absolute default is right for a 4 GB
    * node and fatal for a 1.5 GB test JVM (the EEST payload-builder corpus ran out of heap in CI), so an unset key is
    * `min(default, fraction x max heap)`. A key the operator SET (`hasPath`) is honoured, bounded only by the safety
    * ceiling of [[ExplicitCeilingFraction]] of the max heap. A size of 0 stays 0 (cache off).
    */
  def effectiveBytes(
      section: Option[TsConfig],
      path: String,
      default: Long,
      fraction: Double,
      maxHeap: Long
  ): Long =
    section.filter(_.hasPath(path)).map(_.getLong(path)) match
      case Some(explicit) =>
        val ceiling = (maxHeap * ExplicitCeilingFraction).toLong
        if explicit > ceiling then
          log.warn(
            s"state-read-caches.$path = $explicit exceeds ${(ExplicitCeilingFraction * 100).toInt}% of the max heap " +
              s"($maxHeap); clamped to $ceiling"
          )
          ceiling
        else explicit
      case None => math.min(default, (maxHeap * fraction).toLong)

  private def cappedByHeap(path: String, default: Long, fraction: Double): Long =
    val maxHeap = Runtime.getRuntime.maxMemory
    val v = effectiveBytes(section, path, default, fraction, maxHeap)
    val origin =
      if section.exists(_.hasPath(path)) then "configured" else s"default, capped at ${(fraction * 100).toInt}% of heap"
    log.info(s"state-read-caches.$path effective size = $v bytes ($origin; max heap $maxHeap)")
    v

  /** Process-wide budget of the execution-side code cache (all `EvmCodeStorage` instances share it); 0 disables it. At
    * most 5% of the max heap unless the key is set explicitly (then up to 50%).
    */
  lazy val codeCacheBytes: Long = cappedByHeap("code-cache-bytes", 256L * 1024 * 1024, 0.05)

  /** Process-wide budget of the code-size cache (code hash to length, so EXTCODESIZE-style checks never load code), at
    * about 256 bytes an entry (geth keeps 1,000,000 entries); 0 disables it. At most 2% of the max heap.
    */
  lazy val codeSizeCacheBytes: Long = cappedByHeap("code-size-cache-bytes", 64L * 1024 * 1024, 0.02)

  /** Process-wide budget of the decoded-node cache, in estimated retained bytes (see `DecodedNodeCache`); 0 disables
    * it. At most 4% of the max heap.
    */
  lazy val decodedNodeCacheBytes: Long = cappedByHeap("decoded-node-cache-bytes", 96L * 1024 * 1024, 0.04)

  /** Entries one world's base-trie read memos may hold in total; 0 disables them. At most one entry per 32 KiB of max
    * heap (about 150 bytes each, so under 0.5%).
    */
  lazy val worldReadMemoEntries: Int =
    math.min(bytes("world-read-memo-entries", 250000L), Runtime.getRuntime.maxMemory / 32768).toInt

  /** Process-wide budget of the JUMPDEST analysis cache; 0 disables it. At most 1% of the max heap. */
  lazy val jumpDestCacheBytes: Long = cappedByHeap("jumpdest-cache-bytes", 32L * 1024 * 1024, 0.01)

  /** Budget of one block's JUMPDEST analysis memo (`JumpDestAnalysis.BlockMemo`); 0 disables it. At most 2% of the max
    * heap. Entries are truncated analyses (often a few words), so this is rarely approached.
    */
  lazy val jumpDestBlockMemoBytes: Long = cappedByHeap("jumpdest-block-memo-bytes", 64L * 1024 * 1024, 0.02)

  // BAL-driven prefetch (EIP-7928, Amsterdam blocks that arrive with their access list): see `BalPrefetcher`.

  /** Master switch of the BAL prefetch; it only warms caches, so off changes timing, never results. */
  lazy val balPrefetchEnabled: Boolean = flag("bal-prefetch-enabled", true)

  /** Threads of the process-wide prefetch pool (bounded queue depth of the device: more is not monotonically better).
    */
  lazy val balPrefetchThreads: Int = math.max(1, bytes("bal-prefetch-threads", 16L).toInt)

  /** Keys one prefetch task handles (accounts per task, slots per task). */
  lazy val balPrefetchBatchSize: Int = math.max(1, bytes("bal-prefetch-batch-size", 64L).toInt)

  /** Bytes of decoded trie nodes one block's prefetch may put in the decoded-node cache; 0 means half the cache. */
  lazy val balPrefetchNodeBudgetBytes: Long =
    val configured = bytes("bal-prefetch-node-budget-bytes", 0L)
    if configured > 0 then configured else decodedNodeCacheBytes / 2

  /** Bytes of contract code one block's prefetch may keep in the code cache; 0 means half the cache. Code beyond it is
    * still read (it warms the OS page cache) but not kept.
    */
  lazy val balPrefetchCodeBudgetBytes: Long =
    val configured = bytes("bal-prefetch-code-budget-bytes", 0L)
    if configured > 0 then configured else codeCacheBytes / 2
