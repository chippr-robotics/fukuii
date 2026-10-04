package com.chipprbots.ethereum.utils

import com.typesafe.config.ConfigFactory

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

  /** The configured size, never more than `fraction` of the JVM's maximum heap. A fixed absolute default is right for a
    * 4 GB node and fatal for a 1.5 GB test JVM (the EEST payload-builder corpus ran out of heap in CI with the caches
    * at their absolute defaults); a configured size of 0 stays 0 (cache off).
    */
  private def cappedByHeap(configured: Long, fraction: Double): Long =
    math.min(configured, (Runtime.getRuntime.maxMemory * fraction).toLong)

  /** Process-wide budget of the execution-side code cache (all `EvmCodeStorage` instances share it); 0 disables it. At
    * most 3% of the max heap.
    */
  lazy val codeCacheBytes: Long = cappedByHeap(bytes("code-cache-bytes", 64L * 1024 * 1024), 0.03)

  /** Process-wide budget of the decoded-node cache, in estimated retained bytes (see `DecodedNodeCache`); 0 disables
    * it. At most 4% of the max heap.
    */
  lazy val decodedNodeCacheBytes: Long = cappedByHeap(bytes("decoded-node-cache-bytes", 96L * 1024 * 1024), 0.04)

  /** Entries one world's base-trie read memos may hold in total; 0 disables them. At most one entry per 32 KiB of max
    * heap (about 150 bytes each, so under 0.5%).
    */
  lazy val worldReadMemoEntries: Int =
    math.min(bytes("world-read-memo-entries", 250000L), Runtime.getRuntime.maxMemory / 32768).toInt

  /** Process-wide budget of the JUMPDEST analysis cache; 0 disables it. At most 1% of the max heap. */
  lazy val jumpDestCacheBytes: Long = cappedByHeap(bytes("jumpdest-cache-bytes", 32L * 1024 * 1024), 0.01)
