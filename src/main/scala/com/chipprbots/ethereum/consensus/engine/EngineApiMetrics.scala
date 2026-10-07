package com.chipprbots.ethereum.consensus.engine

import java.util.concurrent.atomic.AtomicLong

import io.micrometer.core.instrument.Gauge

import com.chipprbots.ethereum.metrics.MetricsContainer

/** Prometheus metrics for Engine API interactions. Exposed via /metrics endpoint for Grafana dashboards.
  */
object EngineApiMetrics extends MetricsContainer:

  private val _newPayloadCount = new AtomicLong(0)
  private val _newPayloadValidCount = new AtomicLong(0)
  private val _newPayloadSyncingCount = new AtomicLong(0)
  private val _newPayloadInvalidCount = new AtomicLong(0)
  private val _forkchoiceUpdatedCount = new AtomicLong(0)
  private val _forkchoiceValidCount = new AtomicLong(0)
  private val _forkchoiceSyncingCount = new AtomicLong(0)
  private val _importPathInvalidReports = new AtomicLong(0)
  private val _latestPayloadBlockNumber = new AtomicLong(0)
  private val _latestPayloadTimestamp = new AtomicLong(0)

  // Note: Metrics.mkName adds "app_" prefix, so "engine_foo" becomes "app_engine_foo" in Prometheus
  val newPayloadTotal: Gauge = metrics.gauge("engine_newpayload_total", () => _newPayloadCount.get().toDouble)
  val newPayloadValid: Gauge = metrics.gauge("engine_newpayload_valid", () => _newPayloadValidCount.get().toDouble)
  val newPayloadSyncing: Gauge =
    metrics.gauge("engine_newpayload_syncing", () => _newPayloadSyncingCount.get().toDouble)
  val newPayloadInvalid: Gauge =
    metrics.gauge("engine_newpayload_invalid", () => _newPayloadInvalidCount.get().toDouble)

  val forkchoiceUpdatedTotal: Gauge =
    metrics.gauge("engine_forkchoice_total", () => _forkchoiceUpdatedCount.get().toDouble)
  val forkchoiceValid: Gauge = metrics.gauge("engine_forkchoice_valid", () => _forkchoiceValidCount.get().toDouble)
  val forkchoiceSyncing: Gauge =
    metrics.gauge("engine_forkchoice_syncing", () => _forkchoiceSyncingCount.get().toDouble)

  /** Consensus-invalid verdicts pushed in from the p2p import path via `InvalidChainReporter`.
    *
    * Worth its own series because the diagnosis this channel came out of was exactly a zero here: across 48 hive engine
    * tests the node returned INVALID 0 times in 4,940 status replies while rejecting the chain internally.
    * `engine_newpayload_invalid` could not distinguish "nothing invalid arrived" from "invalidity had nowhere to go".
    * Flat at 0 on a syncing ETH node that is refusing blocks means the channel is unbound or misclassifying.
    */
  val importPathInvalidReports: Gauge =
    metrics.gauge("engine_import_path_invalid_reports", () => _importPathInvalidReports.get().toDouble)

  val latestPayloadBlock: Gauge =
    metrics.gauge("engine_latest_payload_block", () => _latestPayloadBlockNumber.get().toDouble)
  val latestPayloadTimestamp: Gauge =
    metrics.gauge("engine_latest_payload_timestamp", () => _latestPayloadTimestamp.get().toDouble)

  def recordNewPayload(status: String, blockNumber: Long, timestamp: Long): Unit =
    _newPayloadCount.incrementAndGet()
    _latestPayloadBlockNumber.set(blockNumber)
    _latestPayloadTimestamp.set(timestamp)
    status match
      case "VALID"   => _newPayloadValidCount.incrementAndGet()
      case "SYNCING" => _newPayloadSyncingCount.incrementAndGet()
      case _         => _newPayloadInvalidCount.incrementAndGet()

  /** One block reported consensus-invalid by the p2p import path (not by an engine_newPayload call). */
  def recordImportPathInvalidReport(): Unit =
    _importPathInvalidReports.incrementAndGet()
    ()

  def recordForkchoiceUpdated(status: String): Unit =
    _forkchoiceUpdatedCount.incrementAndGet()
    status match
      case "VALID"   => _forkchoiceValidCount.incrementAndGet()
      case "SYNCING" => _forkchoiceSyncingCount.incrementAndGet()
      case _         => ()
