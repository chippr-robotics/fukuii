package com.chipprbots.ethereum.consensus.pow

import java.util.concurrent.atomic.AtomicLong

import io.micrometer.core.instrument.Gauge

import com.chipprbots.ethereum.metrics.MetricsContainer

/** Prometheus metrics for PoW mining operations. Exposed via /metrics endpoint for Grafana dashboards.
  *
  * Mirrors the pattern established by EngineApiMetrics for the Engine API side. core-geth reference:
  * consensus/ethash/ethash.go hashrate metrics.Meter (lines 579-823)
  *
  * Spec 027 slice 5 (M2) added the DAG gauges the `--tui` mining panel reads. `EthashDAGManager` writes them on the
  * mining thread, inside the loops that generate or load a DAG and inside `Using` blocks whose failure paths delete and
  * regenerate the file. So every write is an atomic set and nothing else: no lock, no log, no registry call, no
  * allocation, nothing that can throw. The object must already be initialised when mining starts, so that a failed
  * initialiser surfaces at start-up and not on that thread (`StdNode.startMining`).
  */
object PoWMiningMetrics extends MetricsContainer:

  private val _workRequestsTotal = new AtomicLong(0)
  private val _blocksMinedTotal = new AtomicLong(0)
  private val _staleSharesTotal = new AtomicLong(0)
  private val _currentHashrate = new AtomicLong(0)
  private val _lastBlockMinedAt = new AtomicLong(0)
  private val _lastMiningDurationMs = new AtomicLong(0)

  // Slice 5 (M2): the DAG's epoch (-1 until the first build starts: epoch 0 is a real epoch), the phase of the build
  // (0 none yet, 1 loading from file, 2 generating), its percent, and 1 once the DAG is in memory.
  private val _dagEpoch = new AtomicLong(-1)
  private val _dagPhase = new AtomicLong(0)
  private val _dagProgress = new AtomicLong(0)
  private val _dagLoaded = new AtomicLong(0)

  // Note: Metrics.mkName adds "app_" prefix, so "pow_foo" becomes "app_pow_foo" in Prometheus
  val workRequestsTotal: Gauge =
    metrics.gauge("pow_getwork_total", () => _workRequestsTotal.get().toDouble)

  val blocksMinedTotal: Gauge =
    metrics.gauge("pow_blocks_mined_total", () => _blocksMinedTotal.get().toDouble)

  val staleSharesTotal: Gauge =
    metrics.gauge("pow_stale_shares_total", () => _staleSharesTotal.get().toDouble)

  val currentHashrate: Gauge =
    metrics.gauge("pow_hashrate_current", () => _currentHashrate.get().toDouble)

  val lastBlockMinedAt: Gauge =
    metrics.gauge("pow_last_block_mined_timestamp", () => _lastBlockMinedAt.get().toDouble)

  val lastMiningDurationMs: Gauge =
    metrics.gauge("pow_last_mining_duration_ms", () => _lastMiningDurationMs.get().toDouble)

  /** The epoch of the DAG the miner is building or has built: the ECIP-1099 epoch (60,000 blocks after Thanos), `-1`
    * before the first build starts.
    */
  val dagEpoch: Gauge =
    metrics.gauge("pow_dag_epoch", () => _dagEpoch.get().toDouble)

  /** What the build is doing: 0 not yet known (the cache is being made), 1 loading from file, 2 generating. */
  val dagPhase: Gauge =
    metrics.gauge("pow_dag_phase", () => _dagPhase.get().toDouble)

  /** How far the build is, in percent, as the log line gives it. */
  val dagProgress: Gauge =
    metrics.gauge("pow_dag_progress", () => _dagProgress.get().toDouble)

  /** 1 when the DAG for [[dagEpoch]] is in memory, 0 while it is being built. */
  val dagLoaded: Gauge =
    metrics.gauge("pow_dag_loaded", () => _dagLoaded.get().toDouble)

  def recordGetWork(): Unit =
    _workRequestsTotal.incrementAndGet()

  def recordBlockMined(durationMs: Long): Unit =
    _blocksMinedTotal.incrementAndGet()
    _lastBlockMinedAt.set(System.currentTimeMillis() / 1000L)
    _lastMiningDurationMs.set(durationMs)

  def recordStaleShare(): Unit =
    _staleSharesTotal.incrementAndGet()

  def updateHashrate(hashrate: BigInt): Unit =
    _currentHashrate.set(hashrate.toLong)

  /** A DAG build starts for `epoch`: not loaded, no progress, the phase not yet known. */
  def recordDagStarted(epoch: Long): Unit =
    _dagEpoch.set(epoch)
    _dagPhase.set(0)
    _dagProgress.set(0)
    _dagLoaded.set(0)

  /** A step of loading the DAG from its file, at `percent`. */
  def recordDagLoading(percent: Int): Unit =
    _dagPhase.set(1)
    _dagProgress.set(percent.toLong)

  /** A step of generating the DAG, at `percent`. A load that failed falls through to this. */
  def recordDagGenerating(percent: Int): Unit =
    _dagPhase.set(2)
    _dagProgress.set(percent.toLong)

  /** The DAG is in memory. */
  def recordDagLoaded(): Unit =
    _dagProgress.set(100)
    _dagLoaded.set(1)
