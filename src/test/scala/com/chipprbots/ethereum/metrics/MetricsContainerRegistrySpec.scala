package com.chipprbots.ethereum.metrics

import java.util.concurrent.atomic.AtomicLong

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags.*

/** B-427: a `MetricsContainer` object is first touched while the node is building, before `Metrics.configure` installs
  * the exported registry. Its meters must still land in that registry.
  */
class MetricsContainerRegistrySpec extends AnyFlatSpec with Matchers:

  /** Holds meters from first use, as the production holders do. */
  object EarlyHolder extends MetricsContainer:
    val Plain: Counter = metrics.counter("b427.early.plain")
    val Tagged: Counter = metrics.registry.counter("b427.early.tagged", "kind", "x")
    val Gauged: AtomicLong = metrics.registry.gauge("b427.early.gauge", new AtomicLong(0L))

  "A holder touched before the exported registry is installed" should "record into it afterwards" taggedAs UnitTest in {
    // first touch, before any registry is installed
    EarlyHolder.Plain.increment(2.0)

    val exported = Metrics("app", new SimpleMeterRegistry())
    withClue("another default was installed: ")(Metrics.becomeDefaultForTest(exported) shouldBe true)
    try
      EarlyHolder.Plain.increment(3.0)
      EarlyHolder.Tagged.increment()
      EarlyHolder.Gauged.set(7L)
      exported.registry.get("app.b427.early.plain").counter().count() shouldBe 3.0
      exported.registry.get("b427.early.tagged").tag("kind", "x").counter().count() shouldBe 1.0
      exported.registry.get("b427.early.gauge").gauge().value() shouldBe 7.0
    finally Metrics.unseatDefault(exported)
  }
