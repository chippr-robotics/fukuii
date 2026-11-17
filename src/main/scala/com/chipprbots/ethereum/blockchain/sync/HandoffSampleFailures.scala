package com.chipprbots.ethereum.blockchain.sync

import com.chipprbots.ethereum.db.storage.PathHandoffSample

/** The hand-off's samples that stopped a sync since the last hand-off, on the unified state layout (B-304, spec 018 C5a
  * part 5b).
  *
  * A sample that stops the hand-off restarts SNAP at a newer pivot, which heals and samples again. Only the same pivot
  * is refused a second time, so a failure that is systematic (a fault of the sync's writes, not of one pivot) would
  * repeat on every pivot for ever. The controller counts them here: the first [[HandoffSampleFailures.Bound]] - 1 are
  * restarted; the [[HandoffSampleFailures.Bound]]th is not, and the controller stops, for an operator.
  *
  * The count is of samples since the last hand-off that was made, not of samples in a row: a sample that passes is not
  * told to the controller, and another reason a sync is restarted between two of them (a window that held too long, a
  * weight that was an estimate) is no evidence that the rows are now whole.
  *
  * '''A root that is absent at the pivot of the last stop is not counted''' (beacon): it is what a consensus client
  * that is frozen on a root no peer serves gives (#1555), a fault of the chain's head and not of the rows, and three of
  * them would stop a node for it. A root absent at a new pivot is counted.
  *
  * It is kept in memory: a restart of the process begins it again, and is the operator's act.
  */
final class HandoffSampleFailures:
  private var counted: Int = 0
  private var lastPivot: Option[BigInt] = None

  /** One more sample that stopped the hand-off, at `pivot`: what the controller is to do. */
  def stopped(reason: PathHandoffSample.StopReason, pivot: BigInt): HandoffSampleFailures.Verdict =
    val samePivot = lastPivot.contains(pivot)
    lastPivot = Some(pivot)
    if reason == PathHandoffSample.StopReason.RootAbsent && samePivot then
      HandoffSampleFailures.Metrics.stopped(reason, counted)
      HandoffSampleFailures.Verdict.NotCounted(counted)
    else
      counted += 1
      HandoffSampleFailures.Metrics.stopped(reason, counted)
      if counted >= HandoffSampleFailures.Bound then HandoffSampleFailures.Verdict.GiveUp(counted)
      else HandoffSampleFailures.Verdict.Restart(counted)

  /** A hand-off was made: the count begins again. */
  def handedOff(): Unit =
    counted = 0
    lastPivot = None
    HandoffSampleFailures.Metrics.reset()

  def consecutive: Int = counted

object HandoffSampleFailures:
  /** The samples since the last hand-off that stop the restarts. */
  val Bound: Int = 3

  enum Verdict:
    /** Restart SNAP, as for any finalisation that could not hand off: the `n`th sample counted. */
    case Restart(n: Int)

    /** Restart SNAP without counting: a root absent at the pivot of the last stop; `n` are counted. */
    case NotCounted(n: Int)

    /** Do not restart: `n` samples since the last hand-off stopped it. */
    case GiveUp(n: Int)

  /** `db.state.handoff.sample.stopped` by reason, and `db.state.handoff.sample.stopped.consecutive`. */
  private object Metrics extends com.chipprbots.ethereum.metrics.MetricsContainer:
    private val counted = new java.util.concurrent.atomic.AtomicLong(0L)
    metrics.gauge("db.state.handoff.sample.stopped.consecutive", () => counted.get.toDouble)
    // a counter with a tag is made on the registry, which does not put the prefix before the name
    private val stoppedName = s"${metrics.metricsPrefix}.db.state.handoff.sample.stopped"
    private val byReason: Map[PathHandoffSample.StopReason, io.micrometer.core.instrument.Counter] =
      PathHandoffSample.StopReason.values.iterator
        .map(reason => reason -> metrics.registry.counter(stoppedName, "reason", reason.text))
        .toMap

    def stopped(reason: PathHandoffSample.StopReason, n: Int): Unit =
      byReason(reason).increment()
      counted.set(n.toLong)

    def reset(): Unit = counted.set(0L)
