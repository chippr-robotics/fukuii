package com.chipprbots.ethereum.metrics

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

import scala.jdk.CollectionConverters.*
import scala.util.Try

import io.micrometer.core.instrument.*
import io.micrometer.core.instrument.binder.logging.LogbackMetrics
import io.micrometer.core.instrument.composite.CompositeMeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.prometheus.metrics.exporter.httpserver.HTTPServer as PrometheusHTTPServer
import io.prometheus.metrics.instrumentation.jvm.JvmMetrics
import kamon.Kamon
import org.slf4j.LoggerFactory

/** @param serverInterface
  *   the address the plaintext Prometheus server binds: loopback unless configured (spec 030 FR-016)
  * @param plaintextServer
  *   false where the endpoint is served over TLS by [[MetricsTlsServer]] instead
  */
case class Metrics(
    metricsPrefix: String,
    registry: MeterRegistry,
    serverPort: Int = 0,
    serverInterface: String = MetricsConfig.DefaultInterface,
    plaintextServer: Boolean = true
):

  private def mkName: String => String = MetricsUtils.mkNameWithPrefix(metricsPrefix)

  private lazy val server: Option[PrometheusHTTPServer] =
    Option.when(plaintextServer)(
      PrometheusHTTPServer.builder().hostname(serverInterface).port(serverPort).buildAndStart()
    )

  private var logbackMetricsBinder: Option[LogbackMetrics] = None

  def start(): Unit =
    val _ = server // We need this to evaluate the lazy val!
    JvmMetrics.builder().register()
    val lm = new LogbackMetrics()
    lm.bindTo(registry)
    logbackMetricsBinder = Some(lm)
    Kamon.init()

  def close(): Unit =
    logbackMetricsBinder.foreach(_.close())
    registry.close()
    server.foreach(_.close())

  /** Returns a [[io.micrometer.core.instrument.Gauge Gauge]].
    * @param computeValue
    *   A function that computes the current gauge value.
    */
  def gauge(name: String, computeValue: () => Double): Gauge =
    Gauge
      // Note Never use `null` as the value for the second parameter.
      //      If you do, you risk getting no metrics out of the gauge.
      //      So we just use a vanilla `this` but any other non-`null`
      //      value would also do.
      .builder(mkName(name), this, (_: Any) => computeValue()) // Any: Micrometer gauge state — library API
      .register(registry)

  /** Returns a [[io.micrometer.core.instrument.Counter Counter]].
    */
  def counter(name: String): Counter =
    Counter
      .builder(mkName(name))
      .register(registry)

  /** Returns a [[io.micrometer.core.instrument.Timer Timer]].
    */
  def timer(name: String, tags: String*): Timer =
    Timer
      .builder(mkName(name))
      .tags(tags*)
      .register(registry)

  /** Returns a [[io.micrometer.core.instrument.DistributionSummary DistributionSummary]].
    */
  def distribution(name: String): DistributionSummary =
    DistributionSummary
      .builder(mkName(name))
      .register(registry)

object Metrics:
  private val log = LoggerFactory.getLogger(getClass)

  final val MetricsPrefix = "app"

  // Multi-instance registry: maps instanceId → Metrics
  private val instances = new ConcurrentHashMap[String, Metrics]()

  /** The registry of the default instance before any is configured. It is a composite holding a throwaway
    * `SimpleMeterRegistry` (so reads of what was recorded still work), and `configure` adds the exported registry to
    * it. Micrometer registers the meters a composite already holds on a registry added to it, so a holder that was
    * first touched before `configure` (every `MetricsContainer` object built with the storages or the actors, B-427; as
    * B-400 for the allowlist, B-425 for the state layout) records into the exported registry too, with no change to the
    * holder and no cost on its hot path. What was counted before `configure` is not carried over.
    */
  final private val defaultComposite: CompositeMeterRegistry =
    val composite = new CompositeMeterRegistry()
    composite.add(new SimpleMeterRegistry())
    composite

  // Default/fallback instance for backward compatibility
  final private val defaultMetrics = Metrics(MetricsPrefix, defaultComposite)
  private val defaultRef = new AtomicReference[Metrics](defaultMetrics)

  /** Get the default metrics instance (backward compatible with single-instance mode). */
  def get(): Metrics = defaultRef.get()

  /** Runs `body` with `metrics` as the default, then puts the previous default back. For tests of a holder that must
    * follow `configure`, which would otherwise leave its registry installed for the rest of the JVM.
    */
  private[ethereum] def usingDefault[T](metrics: Metrics)(body: => T): T =
    val previous = defaultRef.getAndSet(metrics)
    try body
    finally defaultRef.set(previous)

  /** Makes `metrics` the default if none was configured before, and has the meters already made on the default registry
    * follow it (B-427, see [[defaultComposite]]). False when another instance is the default.
    */
  private def becomeDefault(metrics: Metrics): Boolean =
    val became = defaultRef.compareAndSet(defaultMetrics, metrics)
    if became then defaultComposite.add(metrics.registry)
    became

  /** The reverse of [[becomeDefault]], for a test that must not leave its registry installed for the rest of the JVM.
    */
  private[ethereum] def unseatDefault(metrics: Metrics): Unit =
    val _ = defaultComposite.remove(metrics.registry)
    val _ = defaultRef.compareAndSet(metrics, defaultMetrics)

  /** [[becomeDefault]] as a test enters it, in place of `configure`, which starts servers and binds ports. */
  private[ethereum] def becomeDefaultForTest(metrics: Metrics): Boolean = becomeDefault(metrics)

  /** Get metrics for a specific instance. Falls back to default if not registered. */
  def forInstance(instanceId: String): Metrics =
    Option(instances.get(instanceId)).getOrElse(get())

  /** Configure metrics for a specific instance. Thread-safe, supports multiple calls.
    *
    * **Multi-instance limitation (Bug 29)**: every `MetricsContainer`-mixed-in singleton (`SNAPSyncMetrics`,
    * `RegularSyncMetrics`, etc.) reads from `Metrics.get()` — the static `defaultRef`. The first instance to configure
    * wins the default; all subsequent instances write their samples into the SAME registry, so per-network dashboards
    * can't distinguish chains. Worse, the Prometheus HTTP exporter used here (`io.prometheus.client.HTTPServer`) serves
    * the shared Prometheus default registry, so both `/metrics` endpoints return byte-identical content regardless of
    * which instance they belong to.
    *
    * We emit a WARN at second-configure time so operators see the limitation loudly rather than silently. Workaround
    * for full per-chain observability: run one fukuii container per chain. Proper fix is out of scope here — it
    * requires either (a) refactoring the `object` metric holders to classes parameterised on a registry, or (b)
    * attaching per-network tags at write time and ensuring the exporter serves the correct Micrometer registry.
    */
  def configure(config: MetricsConfig, instanceId: String = "default"): Try[Unit] =
    Try {
      if config.enabled then
        val registry = MeterRegistryBuilder.build(MetricsPrefix)
        val metrics = new Metrics(MetricsPrefix, registry, config.port, config.interface, !config.tlsEnabled)
        val existing = instances.putIfAbsent(instanceId, metrics)
        if existing == null then
          metrics.start()
          // First instance also becomes the default
          val becameDefault = becomeDefault(metrics)
          if !becameDefault then
            // Identify the owner of `defaultRef` — that's the instance whose writes the shared
            // registry actually reflects. `instances` may have other entries too (3+-way
            // multi-instance), but the default-owner is the one operators need to know about.
            val activeDefault = defaultRef.get()
            val ownerId = instances.asScala
              .collectFirst { case (id, m) if m eq activeDefault => id }
              .getOrElse("<unknown>")
            log.warn(
              "Metrics registered for instance '{}' on port {}, but samples are written to the " +
                "shared static registry wired to instance '{}' (Bug 29). This /metrics endpoint will " +
                "serve the same data as the first-registered instance's endpoint. For per-chain " +
                "observability, run a separate fukuii container per chain until the metrics " +
                "refactor lands.",
              instanceId,
              config.port.toString,
              ownerId
            )
        else
          metrics.close()
          // Already configured for this instance — not an error in multi-instance mode
    }

  /** Shut down metrics for a specific instance. */
  def closeInstance(instanceId: String): Unit =
    Option(instances.remove(instanceId)).foreach { metrics =>
      val _ = defaultComposite.remove(metrics.registry)
      metrics.close()
    }
