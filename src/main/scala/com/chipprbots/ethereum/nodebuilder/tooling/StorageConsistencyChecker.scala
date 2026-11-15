package com.chipprbots.ethereum.nodebuilder.tooling

import com.typesafe.scalalogging.Logger

import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.db.storage.BlockHeadersStorage
import com.chipprbots.ethereum.db.storage.BlockNumberMappingStorage

object StorageConsistencyChecker:
  type ShutdownOp = () => Unit

  val DefaultStep = 1000

  /** How many missing points a [[ConsistencyResult.Checked]] lists; the count beyond it stays exact. */
  val FirstMissingCap = 100

  /** `scan` reports progress at most this often (in points checked), and once more at the end. */
  val ProgressEvery = 250

  /** Why the check is not meaningful on this node: in each case headers from genesis are absent by design, so a check
    * would report faults that are not faults.
    */
  enum SkipReason:
    case SnapDone, SnapInProgress, EngineApi

    /** A sentence for the operator. */
    def reason: String = this match
      case SnapDone =>
        "this node synced by SNAP: headers below the pivot are absent by design, so the check would report false faults"
      case SnapInProgress =>
        "SNAP sync is still in progress: only the pivot header is stored, so the check would report false faults"
      case EngineApi =>
        "this node is driven by the Engine API: optimistic import stores blocks at the tip without the header chain from genesis, so the check would report false faults"

  enum MissingKind:
    /** The number has no entry in the number-to-hash mapping. */
    case NoHashForNumber

    /** The number maps to a hash that has no header. */
    case NoHeaderForHash

  final case class Missing(number: BigInt, kind: MissingKind)

  sealed trait ConsistencyResult

  object ConsistencyResult:
    /** A scan ran (to its end, or until cancelled).
      *
      * @param best
      *   the best block number the scan was given
      * @param checked
      *   points looked at before it ended
      * @param total
      *   points it would have looked at in all
      * @param missingCount
      *   exact number of points that failed
      * @param firstMissing
      *   the first [[FirstMissingCap]] of them, in order
      * @param cancelled
      *   true if it stopped early (`checked < total`)
      */
    final case class Checked(
        best: BigInt,
        step: Int,
        checked: Long,
        total: Long,
        missingCount: Long,
        firstMissing: Seq[Missing],
        cancelled: Boolean
    ) extends ConsistencyResult

    final case class Skipped(reason: SkipReason) extends ConsistencyResult

  /** The one place that says whether the check is meaningful on this node. First match wins, in this order. */
  def applicability(app: AppStateStorage, engineApi: Boolean): Option[SkipReason] =
    if app.isSnapSyncDone() then Some(SkipReason.SnapDone)
    else if app.isSnapSyncInProgress() then Some(SkipReason.SnapInProgress)
    else if engineApi then Some(SkipReason.EngineApi)
    else None

  /** Samples every `step`th block number from 0 up to, but not including, `best`: the number must map to a hash and the
    * hash must have a header. Pure apart from the reads; never stops at a miss. `onMiss` is called at each miss as it is
    * found. `cancelled` is asked before each point.
    */
  def scan(
      best: BigInt,
      blockNumberMappingStorage: BlockNumberMappingStorage,
      blockHeadersStorage: BlockHeadersStorage,
      step: Int = DefaultStep,
      cancelled: () => Boolean = () => false,
      onMiss: Missing => Unit = _ => (),
      onProgress: (Long, Long) => Unit = (_, _) => ()
  ): ConsistencyResult.Checked =
    val points = Range(0, best.intValue, step)
    val total = points.size.toLong
    var checked = 0L
    var missingCount = 0L
    var reported = -1L
    val firstMissing = Vector.newBuilder[Missing]
    var wasCancelled = false
    val it = points.iterator
    while !wasCancelled && it.hasNext do
      if cancelled() then wasCancelled = true
      else
        val idx = it.next()
        val miss: Option[Missing] = blockNumberMappingStorage.get(idx) match
          case None => Some(Missing(BigInt(idx), MissingKind.NoHashForNumber))
          case Some(hash) =>
            if blockHeadersStorage.get(hash).isEmpty then Some(Missing(BigInt(idx), MissingKind.NoHeaderForHash))
            else None
        miss.foreach { m =>
          missingCount += 1
          if missingCount <= FirstMissingCap then firstMissing += m
          onMiss(m)
        }
        checked += 1
        if checked % ProgressEvery == 0 then
          onProgress(checked, total)
          reported = checked
    if reported != checked then onProgress(checked, total)
    ConsistencyResult.Checked(best, step, checked, total, missingCount, firstMissing.result(), wasCancelled)

  /** The start-up and periodic check: logs an error and calls `shutdown()` at every miss, and keeps going. */
  def checkStorageConsistency(
      bestBlockNumber: BigInt,
      blockNumberMappingStorage: BlockNumberMappingStorage,
      blockHeadersStorage: BlockHeadersStorage,
      shutdown: ShutdownOp,
      step: Int = DefaultStep
  )(implicit log: Logger): Unit =
    scan(
      bestBlockNumber,
      blockNumberMappingStorage,
      blockHeadersStorage,
      step,
      onMiss = { _ =>
        log.error("Database seems to be in inconsistent state, shutting down")
        shutdown()
      }
    )
    ()
