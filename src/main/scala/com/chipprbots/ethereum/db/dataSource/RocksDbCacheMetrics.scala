package com.chipprbots.ethereum.db.dataSource

import java.util.concurrent.atomic.AtomicBoolean

import scala.util.control.NonFatal

import com.chipprbots.ethereum.metrics.MetricsContainer
import com.chipprbots.ethereum.utils.Logger

/** Prometheus poll gauges for the RocksDB block-cache hit/miss tickers (spec 002 US2 / FR-005).
  *
  * These sample [[RocksDbDataSource.cacheStats]] at scrape time, so the operator can confirm a slow heal walk is
  * cache-miss-bound before raising `max-open-files` / `block-cache-size`. The data is populated only when
  * `db.rocksdb.enable-statistics = true`; with statistics off, `cacheStats == None` and every gauge reads `0.0`.
  *
  * Registered once at node startup against the live state [[RocksDbDataSource]] (see `BaseNode.startMetricsClient`).
  * The closures NEVER throw at scrape time: a `None` (flag off) or a zero denominator both read `0.0`.
  *
  * Exported series (the `app_` prefix + dot→underscore mapping is applied by the Prometheus exporter):
  *   - `app_db_rocksdb_block_cache_hit`
  *   - `app_db_rocksdb_block_cache_miss`
  *   - `app_db_rocksdb_block_cache_hit_rate` — `hit / (hit + miss)`, `0.0` when `None`/denominator 0
  *   - `app_db_rocksdb_index_filter_hit`
  *   - `app_db_rocksdb_index_filter_miss`
  *   - `app_db_rocksdb_statistics_enabled` — `1.0` when the tickers above are live, `0.0` when they read 0.0 only
  *     because `enable-statistics` is off
  *
  * Memory gauges (spec 015), always on, sampled at most once per [[MemorySampleInterval]] and served from that snapshot
  * in between, so scrapes never hammer JNI:
  *   - `app_db_rocksdb_block_cache_usage_bytes` — shared cache usage, INCLUDING the memtable reservation
  *   - `app_db_rocksdb_block_cache_pinned_usage_bytes`
  *   - `app_db_rocksdb_block_cache_capacity_bytes` — the configured budget (cache capacity)
  *   - `app_db_rocksdb_memtables_size_bytes` — `rocksdb.cur-size-all-mem-tables`, summed over column families
  *   - `app_db_rocksdb_memtables_budget_bytes` — WriteBufferManager limit; writes stall above it
  *   - `app_db_rocksdb_table_readers_mem_bytes` — `rocksdb.estimate-table-readers-mem`, summed over column families
  *
  * Mirrors the closure-poll-gauge pattern of `EngineApiMetrics` / `PoWMiningMetrics`.
  */
object RocksDbCacheMetrics extends MetricsContainer with Logger:

  private val registered = new AtomicBoolean(false)

  /** Minimum age of the memory snapshot before it is re-read from RocksDB. */
  val MemorySampleInterval: Long = 30_000L // ms

  /** Memoised [[RocksDbDataSource.memoryStats]]: re-reads RocksDB only when the last sample is older than `intervalMs`.
    * A closed DataSource or a failed read keeps the last good sample (all zeros before the first one); a scrape never
    * throws.
    */
  final class MemorySampler(read: () => Option[RocksDbMemoryStats], intervalMs: Long, now: () => Long):
    @volatile private var last: RocksDbMemoryStats = RocksDbMemoryStats(0L, 0L, 0L, 0L, 0L, 0L)
    @volatile private var sampledAt: Long = Long.MinValue

    def current: RocksDbMemoryStats =
      val t = now()
      if sampledAt == Long.MinValue || t - sampledAt >= intervalMs then
        synchronized {
          if sampledAt == Long.MinValue || t - sampledAt >= intervalMs then
            try read().foreach(s => last = s)
            catch
              case NonFatal(e) =>
                log.debug(s"RocksDB memory sample failed: ${e.getMessage}")
            sampledAt = t
        }
      last

  /** Register the block-cache poll gauges against the given DataSource.
    *
    * Idempotent: a second call is a no-op (avoids duplicate Micrometer registration). When the DataSource is not a
    * concrete [[RocksDbDataSource]] (e.g. an in-memory `EphemDataSource` in tests), registration is skipped.
    *
    * @param dataSource
    *   the production state DataSource; only `RocksDbDataSource` exposes `cacheStats`.
    */
  def register(dataSource: DataSource): Unit =
    dataSource match
      case rdb: RocksDbDataSource =>
        if registered.compareAndSet(false, true) then
          // hit
          val _ = metrics.gauge(
            "db.rocksdb.block_cache.hit",
            () => rdb.cacheStats.map(_._1.toDouble).getOrElse(0.0)
          )
          // miss
          val _ = metrics.gauge(
            "db.rocksdb.block_cache.miss",
            () => rdb.cacheStats.map(_._2.toDouble).getOrElse(0.0)
          )
          // hit_rate = hit / (hit + miss); 0.0 when None or denominator 0
          val _ = metrics.gauge(
            "db.rocksdb.block_cache.hit_rate",
            () =>
              rdb.cacheStats match
                case Some((hit, miss, _, _)) =>
                  val total = hit + miss
                  if total <= 0L then 0.0 else hit.toDouble / total.toDouble
                case None => 0.0
          )
          // index_filter hit / miss
          val _ = metrics.gauge(
            "db.rocksdb.index_filter.hit",
            () => rdb.cacheStats.map(_._3.toDouble).getOrElse(0.0)
          )
          val _ = metrics.gauge(
            "db.rocksdb.index_filter.miss",
            () => rdb.cacheStats.map(_._4.toDouble).getOrElse(0.0)
          )
          val _ = metrics.gauge(
            "db.rocksdb.statistics_enabled",
            () => if rdb.statisticsEnabled then 1.0 else 0.0
          )

          // spec 015: native memory gauges, one 30 s snapshot shared by all of them.
          val sampler = new MemorySampler(() => rdb.memoryStats, MemorySampleInterval, () => System.currentTimeMillis())
          val memoryGauges: Seq[(String, RocksDbMemoryStats => Long)] = Seq(
            ("db.rocksdb.block_cache.usage_bytes", (s: RocksDbMemoryStats) => s.blockCacheUsage),
            ("db.rocksdb.block_cache.pinned_usage_bytes", (s: RocksDbMemoryStats) => s.blockCachePinnedUsage),
            ("db.rocksdb.block_cache.capacity_bytes", (s: RocksDbMemoryStats) => s.blockCacheCapacity),
            ("db.rocksdb.memtables.size_bytes", (s: RocksDbMemoryStats) => s.memTables),
            ("db.rocksdb.memtables.budget_bytes", (s: RocksDbMemoryStats) => s.memTableBudget),
            ("db.rocksdb.table_readers.mem_bytes", (s: RocksDbMemoryStats) => s.tableReaders)
          )
          memoryGauges.foreach { case (name, field) =>
            val _ = metrics.gauge(name, () => field(sampler.current).toDouble)
          }
          log.info(
            "RocksDB metrics registered (app_db_rocksdb_block_cache_* / index_filter_* / memtables_* / " +
              "table_readers_*); hit/miss values are populated only when db.rocksdb.enable-statistics = true"
          )
      case _ =>
        // Not a RocksDbDataSource (e.g. EphemDataSource) — no cacheStats to poll; skip silently.
        ()
