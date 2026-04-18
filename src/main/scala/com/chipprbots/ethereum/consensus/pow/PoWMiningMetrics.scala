package com.chipprbots.ethereum.consensus.pow

import java.util.concurrent.atomic.AtomicLong

import com.chipprbots.ethereum.metrics.MetricsContainer

/** Prometheus metrics for PoW mining operations. Exposed via /metrics endpoint for Grafana dashboards.
  *
  * Mirrors the pattern established by EngineApiMetrics for the Engine API side.
  * core-geth reference: consensus/ethash/ethash.go hashrate metrics.Meter (lines 579-823)
  */
object PoWMiningMetrics extends MetricsContainer {

  private val _workRequestsTotal  = new AtomicLong(0)
  private val _blocksMinedTotal   = new AtomicLong(0)
  private val _staleSharesTotal   = new AtomicLong(0)
  private val _currentHashrate    = new AtomicLong(0)
  private val _lastBlockMinedAt   = new AtomicLong(0)
  private val _lastMiningDurationMs = new AtomicLong(0)

  // Note: Metrics.mkName adds "app_" prefix, so "pow_foo" becomes "app_pow_foo" in Prometheus
  val workRequestsTotal =
    metrics.gauge("pow_getwork_total", () => _workRequestsTotal.get().toDouble)

  val blocksMinedTotal =
    metrics.gauge("pow_blocks_mined_total", () => _blocksMinedTotal.get().toDouble)

  val staleSharesTotal =
    metrics.gauge("pow_stale_shares_total", () => _staleSharesTotal.get().toDouble)

  val currentHashrate =
    metrics.gauge("pow_hashrate_current", () => _currentHashrate.get().toDouble)

  val lastBlockMinedAt =
    metrics.gauge("pow_last_block_mined_timestamp", () => _lastBlockMinedAt.get().toDouble)

  val lastMiningDurationMs =
    metrics.gauge("pow_last_mining_duration_ms", () => _lastMiningDurationMs.get().toDouble)

  def recordGetWork(): Unit =
    _workRequestsTotal.incrementAndGet()

  def recordBlockMined(durationMs: Long): Unit = {
    _blocksMinedTotal.incrementAndGet()
    _lastBlockMinedAt.set(System.currentTimeMillis() / 1000L)
    _lastMiningDurationMs.set(durationMs)
  }

  def recordStaleShare(): Unit =
    _staleSharesTotal.incrementAndGet()

  def updateHashrate(hashrate: BigInt): Unit =
    _currentHashrate.set(hashrate.toLong)
}
