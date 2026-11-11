package com.chipprbots.ethereum.consensus.engine

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge

import com.chipprbots.ethereum.metrics.MetricsContainer

/** The last call of one Engine API method that [[EngineApiMetrics]] recorded: when (wall clock, epoch milliseconds) and
  * the status the caller gave it, as it gave it (`VALID`, `SYNCING`, `INVALID`, `ACCEPTED`). Top level, so that
  * building one never starts `EngineApiMetrics`.
  */
final case class EngineCall(atMs: Long, status: String)

/** Prometheus metrics for Engine API interactions. Exposed via /metrics endpoint for Grafana dashboards.
  *
  * Spec 027 slice 5 added what the `--tui` consensus-layer panel reads (E1, E3), beside the series that were here: the
  * time and status of the last call of each method, the ACCEPTED count, and the authentication failures. Every record
  * method runs inside `engine_newPayload` and `engine_forkchoiceUpdated`, after their side effects and before their
  * reply is built, so each does a few atomic writes and nothing else: no lock, no log, no registry call, no allocation
  * but the one small [[EngineCall]], and nothing that can throw.
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

  // Slice 5: plain atomics, declared before the gauges that read them. A reference holds the call and its status
  // together, so a reader never sees the time of one call with the status of another; null until the first call.
  private val _lastNewPayload = new AtomicReference[EngineCall](null)
  private val _lastForkchoiceUpdated = new AtomicReference[EngineCall](null)
  private val _newPayloadAcceptedCount = new AtomicLong(0)
  private val _authFailureCount = new AtomicLong(0)

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

  /** `engine_newPayload` calls whose answer was ACCEPTED (stored, not executed). `engine_newpayload_invalid` has always
    * counted these too, as its `status match` has no arm for them; it keeps that, and this series is how to tell them
    * from the INVALID ones.
    */
  val newPayloadAccepted: Gauge =
    metrics.gauge("engine_newpayload_accepted", () => _newPayloadAcceptedCount.get().toDouble)

  /** Engine API requests answered 401 by the JWT check: one number, with no tag. A tag from the request (the reason
    * carries the token's own `iat`) would make a series for each unauthenticated request.
    */
  val authFailures: Gauge =
    metrics.gauge("engine_auth_failures", () => _authFailureCount.get().toDouble)

  /** The last `engine_newPayload` that was recorded, if any. A payload the node already held is answered VALID without
    * a record (`EngineApiService.newPayload`), so it does not move this.
    */
  def lastNewPayload: Option[EngineCall] = Option(_lastNewPayload.get())

  /** The last `engine_forkchoiceUpdated` that was recorded, if any. */
  def lastForkchoiceUpdated: Option[EngineCall] = Option(_lastForkchoiceUpdated.get())

  def recordNewPayload(status: String, blockNumber: Long, timestamp: Long): Unit =
    recordNewPayload(status, blockNumber, timestamp, System.currentTimeMillis())

  /** The same, with the call's time given: what a test uses to stop the clock. */
  private[engine] def recordNewPayload(status: String, blockNumber: Long, timestamp: Long, nowMs: Long): Unit =
    _newPayloadCount.incrementAndGet()
    _latestPayloadBlockNumber.set(blockNumber)
    _latestPayloadTimestamp.set(timestamp)
    status match
      case "VALID"   => _newPayloadValidCount.incrementAndGet()
      case "SYNCING" => _newPayloadSyncingCount.incrementAndGet()
      case _         => _newPayloadInvalidCount.incrementAndGet()
    _lastNewPayload.set(EngineCall(nowMs, status))
    status match
      case "ACCEPTED" => _newPayloadAcceptedCount.incrementAndGet()
      case _          => ()

  /** One distinct hole in the state that `newPayload` could not execute over, as `engine_state_hole_total{kind}`: see
    * [[EngineStateHoles]].
    */
  def recordStateHole(kind: String): Unit =
    Counter.builder("engine.state.hole").tag("kind", kind).register(metrics.registry).increment()

  /** One block reported consensus-invalid by the p2p import path (not by an engine_newPayload call). */
  def recordImportPathInvalidReport(): Unit =
    _importPathInvalidReports.incrementAndGet()
    ()

  /** One request the JWT check answered 401: see [[authFailures]]. */
  def recordAuthFailure(): Unit =
    _authFailureCount.incrementAndGet()
    ()

  def recordForkchoiceUpdated(status: String): Unit =
    recordForkchoiceUpdated(status, System.currentTimeMillis())

  /** The same, with the call's time given: what a test uses to stop the clock. */
  private[engine] def recordForkchoiceUpdated(status: String, nowMs: Long): Unit =
    _forkchoiceUpdatedCount.incrementAndGet()
    status match
      case "VALID"   => _forkchoiceValidCount.incrementAndGet()
      case "SYNCING" => _forkchoiceSyncingCount.incrementAndGet()
      case _         => ()
    _lastForkchoiceUpdated.set(EngineCall(nowMs, status))
