package com.chipprbots.ethereum.blockchain.sync.snap

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.metrics.Metrics
import com.chipprbots.ethereum.testing.Tags.*

/** Setter round-trip for the spec 002 US2 healing-walk observability gauges (T019). The `SNAPSyncMetrics` push gauges
  * are registered directly on the (static default) `SimpleMeterRegistry` via `registry.gauge(name, AtomicLong)`, so the
  * meter name is the literal series string with no `app.` prefix. This test pushes a value through each new setter and
  * reads it back from the registry to confirm the wiring.
  */
class SNAPSyncMetricsSpec extends AnyFlatSpec with Matchers:

  private def gaugeValue(name: String): Double =
    val gauge = Metrics.get().registry.find(name).gauge()
    gauge should not be null
    gauge.value()

  "SNAPSyncMetrics" should "round-trip the new healing-walk phase/GC/inflation gauges via the registry" taggedAs UnitTest in {
    // Touch the object so the gauges are registered before we read them.
    SNAPSyncMetrics.setHealingPhaseQueueReadMs(12L)
    SNAPSyncMetrics.setHealingPhaseTrieReadMs(345L)
    SNAPSyncMetrics.setHealingPhaseQueueWriteMs(6L)
    SNAPSyncMetrics.setHealingGcPauseMs(78L)
    SNAPSyncMetrics.setHealingGcFraction(0.0125)
    SNAPSyncMetrics.setHealingInflationRatio(1.75)

    gaugeValue("snapsync.healing.phase.queue_read_ms.gauge") shouldBe 12.0 +- 1e-9
    gaugeValue("snapsync.healing.phase.trie_read_ms.gauge") shouldBe 345.0 +- 1e-9
    gaugeValue("snapsync.healing.phase.queue_write_ms.gauge") shouldBe 6.0 +- 1e-9
    gaugeValue("snapsync.healing.gc.pause_ms.gauge") shouldBe 78.0 +- 1e-9
    gaugeValue("snapsync.healing.gc.fraction.gauge") shouldBe 0.0125 +- 1e-9
    gaugeValue("snapsync.healing.inflation_ratio.gauge") shouldBe 1.75 +- 1e-9
  }

  // spec 022 T055, data-model.md "Metrics and logs": on the registry with no prefix, so the Prometheus names are those.
  it should "round-trip what FR-017 asks for, under the names data-model.md gives" taggedAs UnitTest in {
    SNAPSyncMetrics.setHealHeld(7L, 700L)
    SNAPSyncMetrics.setHealRequests(3L, 2L)
    SNAPSyncMetrics.setHealExcludedAccounts(5L)
    SNAPSyncMetrics.setHealRootAgeSeconds(90L)

    gaugeValue("snap.heal.held.nodes") shouldBe 7.0 +- 1e-9
    gaugeValue("snap.heal.held.bytes") shouldBe 700.0 +- 1e-9
    gaugeValue("snap.heal.pending") shouldBe 3.0 +- 1e-9
    gaugeValue("snap.heal.inflight") shouldBe 2.0 +- 1e-9
    gaugeValue("snap.heal.excluded.accounts") shouldBe 5.0 +- 1e-9
    gaugeValue("snap.heal.root.age.seconds") shouldBe 90.0 +- 1e-9
  }

  it should "record the nodes healed per re-peg as a distribution (SC-005)" taggedAs UnitTest in {
    SNAPSyncMetrics.recordHealNodesPerRepeg(3L)
    val summary = Metrics.get().registry.find("snap.heal.nodes.per.repeg").summary()
    summary should not be null
    summary.count() should be >= 1L
    summary.max() should be >= 3.0
  }

  it should "reflect the latest pushed value (last-write-wins gauges)" taggedAs UnitTest in {
    SNAPSyncMetrics.setHealingInflationRatio(1.0)
    gaugeValue("snapsync.healing.inflation_ratio.gauge") shouldBe 1.0 +- 1e-9

    SNAPSyncMetrics.setHealingInflationRatio(3.5)
    gaugeValue("snapsync.healing.inflation_ratio.gauge") shouldBe 3.5 +- 1e-9
  }

  // Spec 027 T040: the TUI ages the SNAP sample on its own clock, so it needs the time the sample was recorded.
  it should "report the latest progress with the time measure recorded it, and leave latestProgress unchanged" taggedAs UnitTest in {
    val progress = SyncProgress(
      phase = SNAPSyncController.SyncPhase.AccountRangeSync,
      accountsSynced = 1,
      bytecodesDownloaded = 0,
      storageSlotsSynced = 0,
      nodesHealed = 0,
      elapsedSeconds = 1,
      phaseElapsedSeconds = 1,
      accountsPerSec = 1,
      bytecodesPerSec = 0,
      slotsPerSec = 0,
      nodesPerSec = 0,
      recentAccountsPerSec = 1,
      recentBytecodesPerSec = 0,
      recentSlotsPerSec = 0,
      recentNodesPerSec = 0,
      phaseProgress = 1,
      estimatedTotalAccounts = 100,
      estimatedTotalBytecodes = 0,
      estimatedTotalSlots = 0,
      startTime = 0,
      phaseStartTime = 0
    )
    val before = System.currentTimeMillis()
    SNAPSyncMetrics.measure(progress)
    val after = System.currentTimeMillis()
    val sample = SNAPSyncMetrics.latestSample
    sample.map(_._1) shouldBe Some(progress)
    sample.get._2 should ((be >= before).and(be <= after))
    SNAPSyncMetrics.latestProgress(maxAgeMillis = 60_000L) shouldBe Some(progress)
    SNAPSyncMetrics.latestSample shouldBe sample // reading does not move it
  }

  // Spec 027 T064: the TUI's Sync view reads the request counters, the timeouts and the snap-capable peers.
  it should "read back the request, failure and timeout counters and the snap-capable peers from the registry" taggedAs UnitTest in {
    def counter(name: String): Double =
      val c = Metrics.get().registry.find(s"${Metrics.get().metricsPrefix}.$name").counter()
      c should not be null
      c.count()
    val before = Seq(
      SNAPSyncMetrics.accountRangeRequests,
      SNAPSyncMetrics.accountRangeFailures,
      SNAPSyncMetrics.storageRangeRequests,
      SNAPSyncMetrics.storageRangeFailures,
      SNAPSyncMetrics.bytecodeRequests,
      SNAPSyncMetrics.bytecodeFailures,
      SNAPSyncMetrics.healingRequests,
      SNAPSyncMetrics.healingFailures,
      SNAPSyncMetrics.requestTimeouts,
      SNAPSyncMetrics.peersBlacklisted
    )
    SNAPSyncMetrics.incrementAccountRangeRequests()
    SNAPSyncMetrics.incrementAccountRangeRequests()
    SNAPSyncMetrics.incrementAccountRangeFailures()
    SNAPSyncMetrics.incrementStorageRangeRequests()
    SNAPSyncMetrics.incrementStorageRangeFailures()
    SNAPSyncMetrics.incrementBytecodeRequests()
    SNAPSyncMetrics.incrementBytecodeFailures()
    SNAPSyncMetrics.incrementHealingRequests()
    SNAPSyncMetrics.incrementHealingFailures()
    SNAPSyncMetrics.incrementRequestTimeout()
    SNAPSyncMetrics.incrementPeerBlacklisted()
    SNAPSyncMetrics.setSnapCapablePeers(9)

    val after = Seq(
      SNAPSyncMetrics.accountRangeRequests,
      SNAPSyncMetrics.accountRangeFailures,
      SNAPSyncMetrics.storageRangeRequests,
      SNAPSyncMetrics.storageRangeFailures,
      SNAPSyncMetrics.bytecodeRequests,
      SNAPSyncMetrics.bytecodeFailures,
      SNAPSyncMetrics.healingRequests,
      SNAPSyncMetrics.healingFailures,
      SNAPSyncMetrics.requestTimeouts,
      SNAPSyncMetrics.peersBlacklisted
    )
    after.zip(before).map((a, b) => a - b) shouldBe Seq(2L, 1L, 1L, 1L, 1L, 1L, 1L, 1L, 1L, 1L)
    SNAPSyncMetrics.snapCapablePeers shouldBe 9L

    SNAPSyncMetrics.accountRangeRequests.toDouble shouldBe counter("snapsync.accounts.requests.total")
    SNAPSyncMetrics.accountRangeFailures.toDouble shouldBe counter("snapsync.accounts.requests.failed")
    SNAPSyncMetrics.storageRangeRequests.toDouble shouldBe counter("snapsync.storage.requests.total")
    SNAPSyncMetrics.storageRangeFailures.toDouble shouldBe counter("snapsync.storage.requests.failed")
    SNAPSyncMetrics.bytecodeRequests.toDouble shouldBe counter("snapsync.bytecodes.requests.total")
    SNAPSyncMetrics.bytecodeFailures.toDouble shouldBe counter("snapsync.bytecodes.requests.failed")
    SNAPSyncMetrics.healingRequests.toDouble shouldBe counter("snapsync.healing.requests.total")
    SNAPSyncMetrics.healingFailures.toDouble shouldBe counter("snapsync.healing.requests.failed")
    SNAPSyncMetrics.requestTimeouts.toDouble shouldBe counter("snapsync.requests.timeouts.total")
    SNAPSyncMetrics.peersBlacklisted.toDouble shouldBe counter("snapsync.peers.blacklisted.total")
    gaugeValue("snapsync.peers.capable.gauge") shouldBe 9.0 +- 1e-9
  }

  // prism, slice 3 review: the TUI shows the pivot as the target while SNAP runs.
  it should "read back the pivot block number it was given" taggedAs UnitTest in {
    val saved = SNAPSyncMetrics.pivotBlockNumber
    try
      SNAPSyncMetrics.setPivotBlockNumber(BigInt(9_118_000))
      SNAPSyncMetrics.pivotBlockNumber shouldBe 9_118_000L
      gaugeValue("snapsync.pivot.block.number.gauge") shouldBe 9_118_000.0 +- 1e-9
    finally SNAPSyncMetrics.setPivotBlockNumber(BigInt(saved)) // the gauge is process-wide
  }
