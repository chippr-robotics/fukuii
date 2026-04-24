package com.chipprbots.ethereum.metrics

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Locks the behavior documented in [[Metrics.configure]] for multi-instance registration (Bug 29).
  *
  * These tests don't fix the shared-registry semantics — that requires an architectural refactor of the `object`-level
  * `MetricsContainer` singletons. What they DO is guarantee that the fallback contract (`Metrics.forInstance(unknownId)
  * \== Metrics.get()`) remains the observable API, so any change to the multi-instance wiring at least surfaces in this
  * test first.
  *
  * Avoids real port binding — we don't go through `configure` because it calls `Metrics.start()` which binds a real
  * Prometheus HTTP server. Port-binding tests belong in an integration suite with explicit lifecycle control; the Bug
  * 29 *semantic* we want to lock is the lookup fallback, which doesn't require binding.
  */
class MetricsMultiInstanceSpec extends AnyFlatSpec with Matchers {

  "Metrics.forInstance" should "fall back to Metrics.get() for unknown instance ids" in {
    val any = Metrics.forInstance("definitely-not-registered-12345")
    any shouldBe Metrics.get()
  }

  it should "return the same reference for repeated lookups of the default" in {
    val a = Metrics.get()
    val b = Metrics.get()
    a shouldBe b
  }

  "MetricsContainer.metrics" should "resolve to Metrics.get() (Bug 29 — shared registry semantic)" in {
    // The whole point of Bug 29 is that `MetricsContainer` singletons write to the static default
    // instead of to a per-instance registry. Assert that explicitly so a future refactor that
    // changes the wiring has to update this test along with the production change.
    object Probe extends MetricsContainer
    Probe.metrics shouldBe Metrics.get()
  }
}
