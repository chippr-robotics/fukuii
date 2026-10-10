package com.chipprbots.ethereum.blockchain.sync.snap.controller

import scala.concurrent.duration.*

import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncConfig

/** SNAP finalization's constants and its config decision (spec 016 M4): the deferred-backfill header-hold timing, the
  * ChainDownloader's empty-header back-off, and whether the backfill of bodies and receipts waits for finalization.
  * Re-exported by `object SNAPSyncController`, so every call site is unchanged.
  */
private[snap] object FinalizationPolicy:

  private[snap] val HeaderHoldTickInterval = 2.seconds
  private[snap] val HeaderHoldWarnIntervalMs = 60000L
  private[snap] val EmptyHeaderBackoff: FiniteDuration = 60.seconds
  private[snap] val HeaderHoldTimerKey = "HeaderHoldTick"

  /** Whether bodies and receipts are held back until SNAP state is finalised (headers keep downloading). */
  private[snap] def chainBackfillDeferredToFinalization(cfg: SNAPSyncConfig): Boolean =
    cfg.chainDownloadEnabled && cfg.deferChainBackfillUntilStateComplete
