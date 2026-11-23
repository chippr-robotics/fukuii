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
  *
  * **First touch before `configure` (B-427)**: an object touched before the node configures its metrics holds meters
  * of the default registry, a composite that `Metrics.configure` adds the exported registry to; Micrometer registers
  * the meters it already holds on a registry added to it, so they are exported. Counts made before that are not.
  */
trait MetricsContainer:
  final lazy val metrics: Metrics = Metrics.get()
