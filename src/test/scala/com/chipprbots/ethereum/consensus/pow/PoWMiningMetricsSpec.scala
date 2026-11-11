package com.chipprbots.ethereum.consensus.pow

import io.micrometer.core.instrument.Gauge
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags.*

/** The DAG gauges the TUI's mining panel reads (spec 027 slice 5, M2), and the six gauges that were there before them
  * (forge, slice 5 pre-flight).
  *
  * `PoWMiningMetrics` is one object for the whole JVM, and `EthMiningService`'s specs record into it too, so the
  * assertions on the existing gauges are differences between two readings. The DAG gauges are this spec's to move: it
  * sets them before it reads them.
  */
class PoWMiningMetricsSpec extends AnyFlatSpec with Matchers:

  private val metrics = PoWMiningMetrics

  private def read(gauge: Gauge): Long = gauge.value().toLong

  /** The six gauges that were there before slice 5. */
  private val existing: Seq[Gauge] = Seq(
    metrics.workRequestsTotal,
    metrics.blocksMinedTotal,
    metrics.staleSharesTotal,
    metrics.currentHashrate,
    metrics.lastBlockMinedAt,
    metrics.lastMiningDurationMs
  )

  /** The gauges slice 5 adds. */
  private val added: Seq[Gauge] = Seq(metrics.dagEpoch, metrics.dagPhase, metrics.dagProgress, metrics.dagLoaded)

  private def names(gauges: Seq[Gauge]): Seq[String] = gauges.map(_.getId.getName)

  private def dag: (Long, Long, Long, Long) =
    (read(metrics.dagEpoch), read(metrics.dagPhase), read(metrics.dagProgress), read(metrics.dagLoaded))

  // ---- M2: the DAG gauges ----------------------------------------------------------------------------------------

  "The DAG gauges" should "name the epoch the DAG is being built for, with no progress and not loaded, when a build starts" taggedAs (
    UnitTest
  ) in {
    metrics.recordDagStarted(364L)
    // (epoch, phase, progress, loaded): the phase is 0, none, until the first progress step says which build it is.
    dag shouldBe ((364L, 0L, 0L, 0L))
  }

  it should "say the epoch is 0 for epoch 0: the DAG's epoch is a real number, not an absent one" taggedAs (UnitTest) in {
    metrics.recordDagStarted(0L)
    read(metrics.dagEpoch) shouldBe 0L
  }

  it should "follow a generation: the phase, then the percent, still not loaded" taggedAs (UnitTest) in {
    metrics.recordDagStarted(364L)
    metrics.recordDagGenerating(0)
    dag shouldBe ((364L, 2L, 0L, 0L))
    metrics.recordDagGenerating(37)
    dag shouldBe ((364L, 2L, 37L, 0L))
  }

  it should "follow a load from file: its own phase and percent" taggedAs (UnitTest) in {
    metrics.recordDagStarted(364L)
    metrics.recordDagLoading(42)
    dag shouldBe ((364L, 1L, 42L, 0L))
  }

  it should "show a load that failed and fell back to generating as a generation from 0" taggedAs (UnitTest) in {
    metrics.recordDagStarted(364L)
    metrics.recordDagLoading(80)
    metrics.recordDagGenerating(0)
    dag shouldBe ((364L, 2L, 0L, 0L))
  }

  it should "say loaded, at 100 percent, when the DAG is in memory" taggedAs (UnitTest) in {
    metrics.recordDagStarted(364L)
    metrics.recordDagGenerating(97)
    metrics.recordDagLoaded()
    (read(metrics.dagEpoch), read(metrics.dagProgress), read(metrics.dagLoaded)) shouldBe ((364L, 100L, 1L))
  }

  it should "start over at the next epoch: the new epoch, not loaded, from 0" taggedAs (UnitTest) in {
    metrics.recordDagStarted(364L)
    metrics.recordDagLoaded()
    metrics.recordDagStarted(365L)
    dag shouldBe ((365L, 0L, 0L, 0L))
  }

  it should "never show a new epoch with the last epoch's loaded flag: the flag is cleared before the epoch moves" taggedAs (
    UnitTest
  ) in {
    // The writer loads each epoch in turn. It publishes the epoch it is about to load BEFORE it calls recordDagLoaded, so
    // a reader that sees epoch e with loaded = 1, and then finds the writer has not published e, saw the flag of an
    // earlier epoch: recordDagStarted moved the epoch before it cleared the flag. A poll reads the epoch first
    // (`TuiStatusProbe`'s MiningReader), then the flag; so does this reader.
    val loading = new java.util.concurrent.atomic.AtomicLong(-1)
    val done = new java.util.concurrent.atomic.AtomicBoolean(false)
    val epochs = 200_000L
    val writer = new Thread(() =>
      var e = 0L
      while e < epochs do
        metrics.recordDagStarted(e)
        loading.set(e)
        metrics.recordDagLoaded()
        e += 1
      done.set(true)
    )
    writer.setDaemon(true)
    metrics.recordDagStarted(0L)
    writer.start()
    var wrong: Option[(Long, Long)] = None
    while !done.get() && wrong.isEmpty do
      val epoch = read(metrics.dagEpoch)
      val loadedFlag = read(metrics.dagLoaded)
      val published = loading.get()
      if loadedFlag == 1L && published < epoch then wrong = Some((epoch, published))
    writer.join()
    wrong shouldBe None
  }

  it should "be registered with no tag, one series each" taggedAs (UnitTest) in {
    for gauge <- added do
      val found = metrics.metrics.registry.find(gauge.getId.getName).gauges()
      found should have size 1
      found.iterator().next().getId.getTags.isEmpty shouldBe true
  }

  it should "have names no existing gauge has, and none a Prometheus exporter would rename" taggedAs (UnitTest) in {
    names(added) shouldBe Seq(
      "app.pow_dag_epoch",
      "app.pow_dag_phase",
      "app.pow_dag_progress",
      "app.pow_dag_loaded"
    )
    (names(added).toSet.intersect(names(existing).toSet)) shouldBe empty
    names(existing ++ added).distinct should have size (existing.size + added.size).toLong
    val reserved = Seq("_total", "_count", "_sum", "_bucket", "_created", "_info")
    for name <- names(added); suffix <- reserved do (name should not).endWith(suffix)
  }

  // ---- the six existing gauges keep their names and their values ---------------------------------------------------

  "The existing gauges" should "keep their six names" taggedAs (UnitTest) in {
    names(existing) shouldBe Seq(
      "app.pow_getwork_total",
      "app.pow_blocks_mined_total",
      "app.pow_stale_shares_total",
      "app.pow_hashrate_current",
      "app.pow_last_block_mined_timestamp",
      "app.pow_last_mining_duration_ms"
    )
  }

  it should "count a getWork, a stale share and a mined block as they did" taggedAs (UnitTest) in {
    val before = Seq(metrics.workRequestsTotal, metrics.staleSharesTotal, metrics.blocksMinedTotal).map(read)
    metrics.recordGetWork()
    metrics.recordStaleShare()
    metrics.recordStaleShare()
    metrics.recordBlockMined(1_500L)
    val after = Seq(metrics.workRequestsTotal, metrics.staleSharesTotal, metrics.blocksMinedTotal).map(read)
    after.zip(before).map((a, b) => a - b) shouldBe Seq(1L, 2L, 1L)
    read(metrics.lastMiningDurationMs) shouldBe 1_500L
    val minedAt = read(metrics.lastBlockMinedAt)
    minedAt should ((be > 1_700_000_000L).and(be <= System.currentTimeMillis() / 1000L))
  }

  it should "hold the hashrate the last submission set, as a whole number" taggedAs (UnitTest) in {
    metrics.updateHashrate(BigInt(48_200_000L))
    read(metrics.currentHashrate) shouldBe 48_200_000L
  }

  it should "leave the DAG gauges alone when mining records" taggedAs (UnitTest) in {
    metrics.recordDagStarted(7L)
    metrics.recordDagGenerating(55)
    metrics.recordGetWork()
    metrics.recordBlockMined(1L)
    metrics.updateHashrate(BigInt(1L))
    dag shouldBe ((7L, 2L, 55L, 0L))
  }
