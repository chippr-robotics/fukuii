package com.chipprbots.ethereum.consensus.engine

import io.micrometer.core.instrument.Gauge
import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags.*

/** What `EngineApiMetrics` records for the TUI's consensus-layer panel (spec 027 slice 5, E1 and E3) while it keeps
  * every series it already had (beacon, slice 5 pre-flight): the time and the status of the last call of each method,
  * the ACCEPTED payloads apart from the INVALID ones, and the JWT authentication failures.
  *
  * `EngineApiMetrics` is one object for the whole JVM, and the engine specs that run before or after this one record
  * into it. So every assertion here is a difference between two readings, or a value this spec has just written.
  */
class EngineApiMetricsSpec extends AnyFlatSpec with Matchers with OptionValues:

  private val metrics = EngineApiMetrics

  private def read(gauge: Gauge): Long = gauge.value().toLong

  /** The ten gauges the object registered before slice 5, in their declaration order. */
  private val existing: Seq[Gauge] = Seq(
    metrics.newPayloadTotal,
    metrics.newPayloadValid,
    metrics.newPayloadSyncing,
    metrics.newPayloadInvalid,
    metrics.forkchoiceUpdatedTotal,
    metrics.forkchoiceValid,
    metrics.forkchoiceSyncing,
    metrics.importPathInvalidReports,
    metrics.latestPayloadBlock,
    metrics.latestPayloadTimestamp
  )

  /** The gauges slice 5 adds. */
  private val added: Seq[Gauge] = Seq(metrics.newPayloadAccepted, metrics.authFailures)

  private def names(gauges: Seq[Gauge]): Seq[String] = gauges.map(_.getId.getName)

  /** How much `body` moved each of `gauges`. */
  private def deltas(gauges: Gauge*)(body: => Unit): Seq[Long] =
    val before = gauges.map(read)
    body
    gauges.map(read).zip(before).map((after, was) => after - was)

  // ---- E1: the last call of each method -----------------------------------------------------------------------------

  "recordNewPayload" should "stamp the last newPayload with the clock it is given and the status as the caller said it" taggedAs (
    UnitTest
  ) in {
    metrics.recordNewPayload("ACCEPTED", 7_412_009L, 1_791_294_816L, nowMs = 5_000L)
    metrics.lastNewPayload shouldBe Some(EngineCall(5_000L, "ACCEPTED"))
  }

  "recordForkchoiceUpdated" should "stamp the last forkchoiceUpdated with the clock it is given and its status" taggedAs (
    UnitTest
  ) in {
    metrics.recordForkchoiceUpdated("SYNCING", nowMs = 6_000L)
    metrics.lastForkchoiceUpdated shouldBe Some(EngineCall(6_000L, "SYNCING"))
  }

  "The last call" should "be kept apart for each method, and apart from the payload's own timestamp" taggedAs (
    UnitTest
  ) in {
    metrics.recordNewPayload("VALID", 100L, 1_700_000_000L, nowMs = 1_000L)
    metrics.recordForkchoiceUpdated("INVALID", nowMs = 2_000L)
    metrics.lastNewPayload shouldBe Some(EngineCall(1_000L, "VALID"))
    metrics.lastForkchoiceUpdated shouldBe Some(EngineCall(2_000L, "INVALID"))
    // The payload's timestamp is a block field, not the call time: it keeps its own gauge.
    read(metrics.latestPayloadTimestamp) shouldBe 1_700_000_000L
    read(metrics.latestPayloadBlock) shouldBe 100L
  }

  it should "be the latest call, whatever the one before it said" taggedAs (UnitTest) in {
    metrics.recordNewPayload("INVALID", 1L, 1L, nowMs = 10L)
    metrics.recordNewPayload("VALID", 2L, 2L, nowMs = 20L)
    metrics.lastNewPayload shouldBe Some(EngineCall(20L, "VALID"))
  }

  it should "come from the system clock when the call is made without one" taggedAs (UnitTest) in {
    val before = System.currentTimeMillis()
    metrics.recordNewPayload("VALID", 3L, 3L)
    metrics.recordForkchoiceUpdated("VALID")
    val after = System.currentTimeMillis()
    metrics.lastNewPayload.map(_.atMs).value should ((be >= before).and(be <= after))
    metrics.lastForkchoiceUpdated.map(_.atMs).value should ((be >= before).and(be <= after))
  }

  it should "take any status without throwing, as the existing counters take any string" taggedAs (UnitTest) in {
    noException should be thrownBy metrics.recordNewPayload(null, 4L, 4L, nowMs = 30L)
    noException should be thrownBy metrics.recordForkchoiceUpdated(null, nowMs = 31L)
    metrics.recordNewPayload("SOMETHING_NEW", 5L, 5L, nowMs = 32L)
    metrics.lastNewPayload shouldBe Some(EngineCall(32L, "SOMETHING_NEW"))
  }

  // ---- the existing series keep their names and their values -----------------------------------------------------

  "The existing gauges" should "keep their ten names" taggedAs (UnitTest) in {
    names(existing) shouldBe Seq(
      "app.engine_newpayload_total",
      "app.engine_newpayload_valid",
      "app.engine_newpayload_syncing",
      "app.engine_newpayload_invalid",
      "app.engine_forkchoice_total",
      "app.engine_forkchoice_valid",
      "app.engine_forkchoice_syncing",
      "app.engine_import_path_invalid_reports",
      "app.engine_latest_payload_block",
      "app.engine_latest_payload_timestamp"
    )
  }

  it should "count a newPayload by its status as they always did: ACCEPTED lands in the invalid count" taggedAs (
    UnitTest
  ) in {
    val gauges =
      Seq(metrics.newPayloadTotal, metrics.newPayloadValid, metrics.newPayloadSyncing, metrics.newPayloadInvalid)
    deltas(gauges*)(metrics.recordNewPayload("VALID", 1L, 1L, nowMs = 1L)) shouldBe Seq(1L, 1L, 0L, 0L)
    deltas(gauges*)(metrics.recordNewPayload("SYNCING", 1L, 1L, nowMs = 1L)) shouldBe Seq(1L, 0L, 1L, 0L)
    deltas(gauges*)(metrics.recordNewPayload("INVALID", 1L, 1L, nowMs = 1L)) shouldBe Seq(1L, 0L, 0L, 1L)
    // The existing match has no ACCEPTED arm: it falls to the last one. The series stay as they were; the TUI reads
    // the new accepted count beside them instead.
    deltas(gauges*)(metrics.recordNewPayload("ACCEPTED", 1L, 1L, nowMs = 1L)) shouldBe Seq(1L, 0L, 0L, 1L)
  }

  it should "count a forkchoiceUpdated by its status as they always did" taggedAs (UnitTest) in {
    val gauges = Seq(metrics.forkchoiceUpdatedTotal, metrics.forkchoiceValid, metrics.forkchoiceSyncing)
    deltas(gauges*)(metrics.recordForkchoiceUpdated("VALID", nowMs = 1L)) shouldBe Seq(1L, 1L, 0L)
    deltas(gauges*)(metrics.recordForkchoiceUpdated("SYNCING", nowMs = 1L)) shouldBe Seq(1L, 0L, 1L)
    deltas(gauges*)(metrics.recordForkchoiceUpdated("INVALID", nowMs = 1L)) shouldBe Seq(1L, 0L, 0L)
  }

  it should "move the payload block and timestamp gauges with each newPayload" taggedAs (UnitTest) in {
    metrics.recordNewPayload("VALID", 7_412_011L, 1_791_295_000L, nowMs = 1L)
    (read(metrics.latestPayloadBlock), read(metrics.latestPayloadTimestamp)) shouldBe ((7_412_011L, 1_791_295_000L))
  }

  // ---- the new series --------------------------------------------------------------------------------------------

  "The new gauges" should "have names no existing gauge has, and none a Prometheus exporter would rename" taggedAs (
    UnitTest
  ) in {
    names(added) shouldBe Seq("app.engine_newpayload_accepted", "app.engine_auth_failures")
    // The in-memory registry of a unit test cannot say a name clashes in the Prometheus exporter: this can, for the
    // names of this object.
    (names(added).toSet.intersect(names(existing).toSet)) shouldBe empty
    names(existing ++ added).distinct should have size (existing.size + added.size).toLong
    val reserved = Seq("_total", "_count", "_sum", "_bucket", "_created", "_info")
    for name <- names(added); suffix <- reserved do (name should not).endWith(suffix)
  }

  it should "carry no tag: one series each, so request content can never make a new one" taggedAs (UnitTest) in {
    for gauge <- added do
      val found = metrics.metrics.registry.find(gauge.getId.getName).gauges()
      found should have size 1
      found.iterator().next().getId.getTags.isEmpty shouldBe true
  }

  "The accepted count" should "go up by one for each ACCEPTED newPayload, and for no other status" taggedAs (
    UnitTest
  ) in {
    deltas(metrics.newPayloadAccepted)(metrics.recordNewPayload("ACCEPTED", 1L, 1L, nowMs = 1L)) shouldBe Seq(1L)
    deltas(metrics.newPayloadAccepted) {
      metrics.recordNewPayload("VALID", 1L, 1L, nowMs = 1L)
      metrics.recordNewPayload("SYNCING", 1L, 1L, nowMs = 1L)
      metrics.recordNewPayload("INVALID", 1L, 1L, nowMs = 1L)
    } shouldBe Seq(0L)
  }

  // ---- E3: the authentication failures ---------------------------------------------------------------------------

  "recordAuthFailure" should "count one failure per call, without a tag" taggedAs (UnitTest) in {
    deltas(metrics.authFailures) {
      metrics.recordAuthFailure()
      metrics.recordAuthFailure()
      metrics.recordAuthFailure()
    } shouldBe Seq(3L)
  }
