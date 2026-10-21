package com.chipprbots.ethereum.blockchain.sync.snap.controller

/** Pure decisions of the stagnation watchdog (spec 016 M1). Re-exported by `object SNAPSyncController`. */
private[snap] object StagnationPolicy:

  /** Baseline for the storage tail-livelock backstop: the lowest remaining-work (pending+active) seen so far, when it
    * was set, and the coordinator's cumulative stale-local-root failure count at that moment.
    */
  final private[snap] case class StorageTailBaseline(lowWork: Int, sinceMs: Long, staleFailuresAtLow: Long)

  private[snap] object StorageTailBaseline:
    def fresh(nowMs: Long): StorageTailBaseline = StorageTailBaseline(Int.MaxValue, nowMs, 0L)

  /** Minimum number of stale-root verification failures, within one stall window, that makes a flat queue evidence of a
    * livelock rather than a healthy slow tail (matches the coordinator's per-account give-up count K=3: one account's
    * worth of repeated failures).
    */
  private[snap] val MinStaleFailuresForTailLivelock: Long = 3L

  /** Pure state machine for the storage tail-livelock backstop (extracted so it is unit-testable; the actor is
    * file-private). The baseline advances only when remaining work is STRICTLY lower than the last low point
    * (oscillation does not count as progress). The result is `true` only when the low point has stood for `thresholdMs`
    * AND at least [[MinStaleFailuresForTailLivelock]] stale-root failures were recorded since it was set — a depth-only
    * trigger would misfire on huge-account continuation chains and post-split regrowth.
    */
  private[snap] def evaluateStorageTail(
      baseline: StorageTailBaseline,
      remainingWork: Int,
      staleRootFailureEvents: Long,
      nowMs: Long,
      thresholdMs: Long
  ): (StorageTailBaseline, Boolean) =
    val next =
      if remainingWork < baseline.lowWork then StorageTailBaseline(remainingWork, nowMs, staleRootFailureEvents)
      else baseline
    val stalledMs = nowMs - next.sinceMs
    val failuresInWindow = staleRootFailureEvents - next.staleFailuresAtLow
    (next, stalledMs >= thresholdMs && failuresInWindow >= MinStaleFailuresForTailLivelock)
