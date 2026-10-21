package com.chipprbots.ethereum.blockchain.sync.snap.controller

import org.apache.pekko.util.ByteString

import scala.concurrent.duration.*

import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncConfig
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.db.storage.PathNodeStorage

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

  /** Path scheme (#1555): true when the pivot state root's own node is stored nowhere the Path-to-hash publish can find
    * it — neither at the empty path of the path-keyed account trie (with keccak256 equal to `root`) nor by hash in the
    * hash-keyed store the publish writes to and verifies against. The publish's root-to-leaf verification then cannot
    * pass (`MissingNodeException: Root node not found`), so starting it only spends the whole-state scan (6-12 minutes
    * on Platåberget) before the certain failure. A node at the empty path whose hash differs is an older root's:
    * absent.
    *
    * Read-only. A `false` is not a completeness claim; the publish's own verification still decides.
    */
  private[snap] def pathPivotRootAbsent(
      pathNodes: PathNodeStorage,
      root: ByteString,
      hashStore: => MptStorage
  ): Boolean =
    val atEmptyPath =
      pathNodes.readAccountNode(Array.emptyByteArray).exists(rlp => java.util.Arrays.equals(kec256(rlp), root.toArray))
    !atEmptyPath && scala.util.Try(hashStore.get(root.toArray)).isFailure
