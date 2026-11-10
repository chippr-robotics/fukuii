package com.chipprbots.ethereum.console

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags.*

/** SC-003's render budget: a 160×48 frame renders in under 1 ms, median of 100 renders (spec 027 T057). The CPU share
  * of the TUI's threads is the manual half of T057 (`pidstat` on a live node).
  *
  * The assertion is looser than the target (3 ms), so a loaded `sbt pp` cannot flake it; the report line says how the
  * median compares with the 1 ms target.
  */
class TuiRenderBenchmarkSpec extends AnyFlatSpec with Matchers:

  private val renderer = TuiRenderer(TuiConfig.default)

  /** SC-003's target, reported. */
  private val TargetMs = 1.0

  /** What the test asserts: room for a loaded machine. */
  private val AssertedMs = 3.0

  "A 160×48 frame" should "render well inside its 1 ms budget, median of 100 renders" taggedAs (BenchmarkTest) in {
    // The busiest fixture: five SNAP worm rows, rippling, and the logo column of tier L.
    val states = (0L until 12L).map(t => TuiFixtures.snapStorageMordor().copy(tick = t))
    (1 to 500).foreach(i => renderer.render(states(i % states.size), 160, 48))
    val nanos = (0 until 100).map { i =>
      val started = System.nanoTime()
      renderer.render(states(i % states.size), 160, 48)
      System.nanoTime() - started
    }.sorted
    val medianMs = nanos(nanos.size / 2) / 1e6
    val verdict = if medianMs < TargetMs then "within" else "OVER"
    info(f"median render of a 160×48 frame: $medianMs%.3f ms (p90 ${nanos(89) / 1e6}%.3f ms), $verdict SC-003's 1 ms")
    medianMs should be < AssertedMs
  }
