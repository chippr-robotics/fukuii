package com.chipprbots.ethereum.metrics

import io.micrometer.core.instrument.*
import io.micrometer.core.instrument.composite.CompositeMeterRegistry
import io.micrometer.core.instrument.config.MeterFilter
import io.micrometer.jmx.JmxMeterRegistry
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import io.prometheus.metrics.model.registry.PrometheusRegistry

import com.chipprbots.ethereum.utils.Logger
import com.chipprbots.ethereum.utils.LoggingUtils.getClassName

object MeterRegistryBuilder extends Logger:

  final private val StdMetricsClock = Clock.SYSTEM

  private def onMeterAdded(m: Meter): Unit =
    log.debug(s"New ${getClassName(m)} metric: " + m.getId.getName)

  /** Build our meter registry consist in:
    *   1. Create each Meter registry 2. Config the resultant composition
    */
  def build(metricsPrefix: String): MeterRegistry =
    build(metricsPrefix, PrometheusRegistry.defaultRegistry, withJmx = true)

  /** [[build]] with the Prometheus registry the meters are exported to given, and the JMX registry optional: what a
    * test uses to render the exposition `/metrics` serves without the JVM-wide default registry or MBeans (B-200).
    */
  private[ethereum] def build(
      metricsPrefix: String,
      prometheusRegistry: PrometheusRegistry,
      withJmx: Boolean
  ): MeterRegistry =

    // Every meter gets the `Prefix`. We are of course mainly interested in those that we do not control, e.g. those
    // coming from `JvmMemoryMetrics`. The filter is on each leaf registry as well as on the composite (B-200): a meter
    // made on `Metrics`'s default registry before `Metrics.configure` (B-427) reaches the leaves without passing this
    // composite, as Micrometer registers a composite's meters on the non-composite registries below the ones added to
    // it, and so without the composite's filters. Without the filter on the leaves such a meter was exported as
    // `snapsync_phase_current_gauge`, not `app_snapsync_phase_current_gauge`. The prefix is added once, whichever
    // filters a meter passes.
    val prefixFilter: MeterFilter = new MeterFilter:
      override def map(id: Meter.Id): Meter.Id =
        id.withName(MetricsUtils.mkNameWithPrefix(metricsPrefix)(id.getName))

    val jmxMeterRegistry: Option[MeterRegistry] =
      Option.when(withJmx)(new JmxMeterRegistry(new AppJmxConfig, StdMetricsClock))

    jmxMeterRegistry.foreach { r =>
      val _ = r.config().meterFilter(prefixFilter)
      log.info(s"Build JMX Meter Registry: ${r}")
    }

    // Wire Micrometer's PrometheusMeterRegistry to share the prometheus-1.x default
    // registry that `Metrics.start()`'s `PrometheusHTTPServer` serves. Without this, the
    // default-arg constructor creates an isolated registry, and every Counter/Gauge/Timer
    // written via Micrometer is stranded — only JVM metrics from `JvmMetrics.builder().register()`
    // (which writes directly to the default registry) ever reach /metrics.
    val prometheusMeterRegistry =
      new PrometheusMeterRegistry(PrometheusConfig.DEFAULT, prometheusRegistry, StdMetricsClock)
    val _ = prometheusMeterRegistry.config().meterFilter(prefixFilter)

    log.info(s"Build Prometheus Meter Registry: ${prometheusMeterRegistry}")

    val registry = new CompositeMeterRegistry(
      StdMetricsClock,
      java.util.Arrays.asList((jmxMeterRegistry.toList :+ prometheusMeterRegistry)*)
    )
    registry
      .config()
      .meterFilter(prefixFilter)
      .onMeterAdded(onMeterAdded)

    registry
