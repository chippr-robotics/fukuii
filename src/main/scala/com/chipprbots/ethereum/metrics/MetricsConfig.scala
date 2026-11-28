package com.chipprbots.ethereum.metrics

import com.typesafe.config.Config as TypesafeConfig

/** `fukuii.metrics`.
  *
  * @param interface
  *   the address the endpoint binds (spec 030 FR-016): loopback by default; the wildcard address it bound before must
  *   now be asked for
  * @param tlsEnabled
  *   `metrics.tls.enabled`: the endpoint is served over TLS by [[MetricsTlsServer]] instead of the Prometheus server
  */
final case class MetricsConfig(
    enabled: Boolean,
    port: Int,
    interface: String = MetricsConfig.DefaultInterface,
    tlsEnabled: Boolean = false
)

object MetricsConfig:
  val DefaultInterface = "localhost"

  object Keys:
    final val Metrics = "metrics"

    final val Enabled = "enabled"
    final val Port = "port"
    final val Interface = "interface"
    final val TlsEnabled = "tls.enabled"

  def apply(config: TypesafeConfig): MetricsConfig =
    val metricsConfig = config.getConfig(Keys.Metrics)
    MetricsConfig(
      enabled = metricsConfig.getBoolean(Keys.Enabled),
      port = metricsConfig.getInt(Keys.Port),
      interface =
        if metricsConfig.hasPath(Keys.Interface) && !metricsConfig.getIsNull(Keys.Interface) then
          metricsConfig.getString(Keys.Interface)
        else DefaultInterface,
      tlsEnabled = metricsConfig.hasPath(Keys.TlsEnabled) && metricsConfig.getBoolean(Keys.TlsEnabled)
    )
