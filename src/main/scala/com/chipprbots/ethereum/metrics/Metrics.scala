package com.chipprbots.ethereum.metrics

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

import scala.jdk.CollectionConverters._
import scala.util.Try

import io.micrometer.core.instrument._
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.prometheus.client.exporter.HTTPServer
import io.prometheus.client.hotspot.DefaultExports
import kamon.Kamon
import org.slf4j.LoggerFactory

case class Metrics(metricsPrefix: String, registry: MeterRegistry, serverPort: Int = 0) {

  private[this] def mkName: String => String = MetricsUtils.mkNameWithPrefix(metricsPrefix)

  private lazy val server: HTTPServer = new HTTPServer(serverPort)

  def start(): Unit = {
    server // We need this to evaluate the lazy val!
    DefaultExports.initialize()
    Kamon.init()
  }

  def close(): Unit = {
    registry.close()
    server.stop()
  }

  def deltaSpike(name: String): DeltaSpikeGauge =
    new DeltaSpikeGauge(mkName(name), this)

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
      .builder(mkName(name), this, (_: Any) => computeValue())
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
      .tags(tags: _*)
      .register(registry)

  /** Returns a [[io.micrometer.core.instrument.DistributionSummary DistributionSummary]].
    */
  def distribution(name: String): DistributionSummary =
    DistributionSummary
      .builder(mkName(name))
      .register(registry)
}

object Metrics {
  private val log = LoggerFactory.getLogger(getClass)

  final val MetricsPrefix = "app"

  // Multi-instance registry: maps instanceId → Metrics
  private val instances = new ConcurrentHashMap[String, Metrics]()

  // Default/fallback instance for backward compatibility
  final private[this] val defaultMetrics = Metrics(MetricsPrefix, new SimpleMeterRegistry())
  private val defaultRef = new AtomicReference[Metrics](defaultMetrics)

  /** Get the default metrics instance (backward compatible with single-instance mode). */
  def get(): Metrics = defaultRef.get()

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
      if (config.enabled) {
        val registry = MeterRegistryBuilder.build(MetricsPrefix)
        val metrics = new Metrics(MetricsPrefix, registry, config.port)
        val existing = instances.putIfAbsent(instanceId, metrics)
        if (existing == null) {
          metrics.start()
          // First instance also becomes the default
          val becameDefault = defaultRef.compareAndSet(defaultMetrics, metrics)
          if (!becameDefault) {
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
          }
        } else {
          metrics.close()
          // Already configured for this instance — not an error in multi-instance mode
        }
      }
    }

  /** Shut down metrics for a specific instance. */
  def closeInstance(instanceId: String): Unit =
    Option(instances.remove(instanceId)).foreach(_.close())
}
