package com.chipprbots.ethereum.consensus.engine

import java.util.concurrent.atomic.AtomicLong

import com.chipprbots.ethereum.metrics.MetricsContainer

/** Prometheus metrics for Engine API interactions. Exposed via /metrics endpoint for Grafana dashboards.
  */
object EngineApiMetrics extends MetricsContainer {

  private val _newPayloadCount = new AtomicLong(0)
  private val _newPayloadValidCount = new AtomicLong(0)
  private val _newPayloadSyncingCount = new AtomicLong(0)
  private val _newPayloadInvalidCount = new AtomicLong(0)
  private val _forkchoiceUpdatedCount = new AtomicLong(0)
  private val _forkchoiceValidCount = new AtomicLong(0)
  private val _forkchoiceSyncingCount = new AtomicLong(0)
  private val _latestPayloadBlockNumber = new AtomicLong(0)
  private val _latestPayloadTimestamp = new AtomicLong(0)

  // Note: Metrics.mkName adds "app_" prefix, so "engine_foo" becomes "app_engine_foo" in Prometheus
  val newPayloadTotal = metrics.gauge("engine_newpayload_total", () => _newPayloadCount.get().toDouble)
  val newPayloadValid = metrics.gauge("engine_newpayload_valid", () => _newPayloadValidCount.get().toDouble)
  val newPayloadSyncing = metrics.gauge("engine_newpayload_syncing", () => _newPayloadSyncingCount.get().toDouble)
  val newPayloadInvalid = metrics.gauge("engine_newpayload_invalid", () => _newPayloadInvalidCount.get().toDouble)

  val forkchoiceUpdatedTotal =
    metrics.gauge("engine_forkchoice_total", () => _forkchoiceUpdatedCount.get().toDouble)
  val forkchoiceValid = metrics.gauge("engine_forkchoice_valid", () => _forkchoiceValidCount.get().toDouble)
  val forkchoiceSyncing = metrics.gauge("engine_forkchoice_syncing", () => _forkchoiceSyncingCount.get().toDouble)

  val latestPayloadBlock =
    metrics.gauge("engine_latest_payload_block", () => _latestPayloadBlockNumber.get().toDouble)
  val latestPayloadTimestamp =
    metrics.gauge("engine_latest_payload_timestamp", () => _latestPayloadTimestamp.get().toDouble)

  def recordNewPayload(status: String, blockNumber: Long, timestamp: Long): Unit = {
    _newPayloadCount.incrementAndGet()
    _latestPayloadBlockNumber.set(blockNumber)
    _latestPayloadTimestamp.set(timestamp)
    status match {
      case "VALID"   => _newPayloadValidCount.incrementAndGet()
      case "SYNCING" => _newPayloadSyncingCount.incrementAndGet()
      case _         => _newPayloadInvalidCount.incrementAndGet()
    }
  }

  def recordForkchoiceUpdated(status: String): Unit = {
    _forkchoiceUpdatedCount.incrementAndGet()
    status match {
      case "VALID"   => _forkchoiceValidCount.incrementAndGet()
      case "SYNCING" => _forkchoiceSyncingCount.incrementAndGet()
      case _         => ()
    }
  }
}
