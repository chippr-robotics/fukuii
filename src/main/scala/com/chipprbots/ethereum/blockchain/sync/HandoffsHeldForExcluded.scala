package com.chipprbots.ethereum.blockchain.sync

/** The hand-offs on the unified layout held back because the sync left accounts out, since the last hand-off (spec 018
  * C5a part 10, the user's decision 1 of 2026-11-17). Each one restarts healing, as decided: it is not bounded. What is
  * bounded is the quiet: from [[ErrorFrom]] in a row each is an ERROR, and the count is a gauge, so that a node that
  * heals for ever over an account no peer serves is seen (beacon).
  *
  * Owned by the sync controller's actor: not thread-safe.
  */
final class HandoffsHeldForExcluded:
  private var count = 0

  /** One more hand-off held back; how many since the last hand-off. */
  def held(): Int =
    count += 1
    HandoffsHeldForExcluded.Metrics.set(count)
    count

  /** A sync handed off: the count begins again. */
  def handedOff(): Unit =
    count = 0
    HandoffsHeldForExcluded.Metrics.set(0)

object HandoffsHeldForExcluded:

  /** From this many in a row, each is an ERROR. */
  val ErrorFrom: Int = 3

  /** `db.state.handoff.held.excluded.consecutive`. */
  private object Metrics extends com.chipprbots.ethereum.metrics.MetricsContainer:
    private val counted = new java.util.concurrent.atomic.AtomicLong(0L)
    metrics.gauge("db.state.handoff.held.excluded.consecutive", () => counted.get.toDouble)
    def set(n: Int): Unit = counted.set(n.toLong)
