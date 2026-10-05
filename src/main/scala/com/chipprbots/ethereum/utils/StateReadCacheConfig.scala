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

  /** Largest sum of the EXPLICITLY configured byte budgets (code, code-size, decoded-node, JUMPDEST, block memo) as a
    * share of the max heap. Above it every explicit budget is scaled down proportionally (the per-cache ceiling alone
    * would let five 50% caches commit 250% of the heap). The recommended total is 35% or less.
    */
  val ExplicitTotalFraction: Double = 0.6

  /** The size of one cache and whether the operator set it (`hasPath`). An explicit cache holds its values strongly
    * (bounded by its budget): the operator sized it to be effective. An unset one keeps soft references that give way
    * under heap pressure.
    */
  final case class Sizing(bytes: Long, explicit: Boolean)

  def isExplicit(section: Option[TsConfig], path: String): Boolean = section.exists(_.hasPath(path))

  /** (config key, absolute default, heap fraction capping the default) of every heap-bounded byte cache. */
  private val cacheSpecs: Seq[(String, Long, Double)] = Seq(
    ("code-cache-bytes", 256L * 1024 * 1024, 0.05),
    ("code-size-cache-bytes", 64L * 1024 * 1024, 0.02),
    ("decoded-node-cache-bytes", 96L * 1024 * 1024, 0.04),
    ("jumpdest-cache-bytes", 32L * 1024 * 1024, 0.01),
    ("jumpdest-block-memo-bytes", 64L * 1024 * 1024, 0.02)
  )

  /** Sizes of all heap-bounded caches: per cache [[effectiveBytes]], then, if the explicit budgets sum to more than
    * [[ExplicitTotalFraction]] of `maxHeap`, the explicit ones scaled down proportionally (WARN with before and after).
    */
  def resolveAll(section: Option[TsConfig], maxHeap: Long): Map[String, Sizing] =
    val each = cacheSpecs.map { (path, default, fraction) =>
      path -> Sizing(effectiveBytes(section, path, default, fraction, maxHeap), isExplicit(section, path))
    }
    val explicitSum = each.collect { case (_, Sizing(b, true)) => b }.sum
    val limit = (maxHeap * ExplicitTotalFraction).toLong
    if explicitSum > limit then
      val scale = limit.toDouble / explicitSum
      val scaled = each.map {
        case (path, Sizing(b, true)) => path -> Sizing((b * scale).toLong, true)
        case other                   => other
      }
      val scaledMap = scaled.toMap
      log.warn(
        s"state-read-caches: explicit sizes total $explicitSum bytes, over ${(ExplicitTotalFraction * 100).toInt}% of " +
          s"the max heap ($maxHeap); scaled to fit: " +
          each.collect { case (p, Sizing(b, true)) => s"$p $b -> ${scaledMap(p).bytes}" }.mkString(", ")
      )
      scaledMap
    else each.toMap

  private lazy val resolved: Map[String, Sizing] =
    val maxHeap = Runtime.getRuntime.maxMemory
    val all = resolveAll(section, maxHeap)
    cacheSpecs.foreach { (path, _, fraction) =>
      val Sizing(v, explicit) = all(path)
      val origin =
        if explicit then "configured, strong refs"
        else s"default capped at ${(fraction * 100).toInt}% of heap, soft refs"
      log.info(s"state-read-caches.$path effective size = $v bytes ($origin; max heap $maxHeap)")
    }
    all

  lazy val codeCacheStrong: Boolean = resolved("code-cache-bytes").explicit
  lazy val codeSizeCacheStrong: Boolean = resolved("code-size-cache-bytes").explicit
  lazy val decodedNodeCacheStrong: Boolean = resolved("decoded-node-cache-bytes").explicit

  /** Process-wide budget of the execution-side code cache (all `EvmCodeStorage` instances share it); 0 disables it. At
    * most 5% of the max heap unless the key is set explicitly (then up to 50%).
    */
  lazy val codeCacheBytes: Long = resolved("code-cache-bytes").bytes

  /** Process-wide budget of the code-size cache (code hash to length, so EXTCODESIZE-style checks never load code), at
    * about 256 bytes an entry (geth keeps 1,000,000 entries); 0 disables it. At most 2% of the max heap by default.
    */
  lazy val codeSizeCacheBytes: Long = resolved("code-size-cache-bytes").bytes

  /** Process-wide budget of the decoded-node cache, in estimated retained bytes (see `DecodedNodeCache`); 0 disables
    * it. At most 4% of the max heap by default.
    */
  lazy val decodedNodeCacheBytes: Long = resolved("decoded-node-cache-bytes").bytes

  /** Entries one world's base-trie read memos may hold in total; 0 disables them. At most one entry per 32 KiB of max
    * heap (about 150 bytes each, so under 0.5%).
    */
  lazy val worldReadMemoEntries: Int =
    math.min(bytes("world-read-memo-entries", 250000L), Runtime.getRuntime.maxMemory / 32768).toInt

  /** Process-wide budget of the JUMPDEST analysis cache; 0 disables it. At most 1% of the max heap. */
  lazy val jumpDestCacheBytes: Long = resolved("jumpdest-cache-bytes").bytes

  /** Budget of one block's JUMPDEST analysis memo (`JumpDestAnalysis.BlockMemo`); 0 disables it. At most 2% of the max
    * heap. Entries are truncated analyses (often a few words), so this is rarely approached.
    */
  lazy val jumpDestBlockMemoBytes: Long = resolved("jumpdest-block-memo-bytes").bytes

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
