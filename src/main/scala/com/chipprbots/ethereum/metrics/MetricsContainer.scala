package com.chipprbots.ethereum.metrics

/** An object that contains metrics, typically owned by an application component. We also use it as a marker trait, so
  * that subclasses can easily give us an idea of what metrics we implement across the application.
  *
  * **Multi-instance caveat (Bug 29)**: `metrics` resolves to `Metrics.get()`, which is the static `defaultRef` —
  * effectively the first-registered `Metrics` instance. Every `MetricsContainer` subclass across every running chain
  * instance therefore writes to the SAME registry. In a single-instance deployment this is fine; in `fukuii-runtime`
  * multi-instance deployments it means per-chain `/metrics` endpoints cannot be distinguished. Run one fukuii container
  * per chain for per-chain observability until the registry-per-instance refactor lands. See `Metrics.configure` for
  * the WARN that fires when a second instance is registered.
  */
trait MetricsContainer {
  final lazy val metrics: Metrics = Metrics.get()
}
