package com.chipprbots.ethereum.console

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags.*

/** SC-003's render budget: a 160×48 frame renders in under 1 ms, median of 100 renders (spec 027 T057). The CPU share
  * of the TUI's threads is the manual half of T057 (`pidstat` on a live node).
  */
class TuiRenderBenchmarkSpec extends AnyFlatSpec with Matchers:

  private val renderer = TuiRenderer(TuiConfig.default)

  "A 160×48 frame" should "render in under 1 ms, median of 100 renders" taggedAs (BenchmarkTest) in {
    // The busiest fixture: five SNAP worm rows, rippling, and the logo column of tier L.
    val states = (0L until 12L).map(t => TuiFixtures.snapStorageMordor().copy(tick = t))
    (1 to 500).foreach(i => renderer.render(states(i % states.size), 160, 48))
    val nanos = (0 until 100).map { i =>
      val started = System.nanoTime()
      renderer.render(states(i % states.size), 160, 48)
      System.nanoTime() - started
    }.sorted
    val medianMs = nanos(nanos.size / 2) / 1e6
    info(f"median render of a 160×48 frame: $medianMs%.3f ms (p90 ${nanos(89) / 1e6}%.3f ms)")
    medianMs should be < 1.0
  }
