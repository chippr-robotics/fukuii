package com.chipprbots.ethereum.blockchain.sync

import com.chipprbots.ethereum.db.storage.PathHandoffSample

/** The hand-off's samples that stopped a sync in a row, on the unified state layout (B-304, spec 018 C5a part 5b).
  *
  * A sample that stops the hand-off restarts SNAP at a newer pivot, which heals and samples again. Only the same pivot
  * is refused a second time, so a failure that is systematic (a fault of the sync's writes, not of one pivot) would
  * repeat on every pivot for ever. The controller counts them here: the first [[HandoffSampleFailures.Bound]] - 1 are
  * restarted; the [[HandoffSampleFailures.Bound]]th is not, and the controller stops, for an operator.
  *
  * The count is of samples since the last hand-off that was made: another reason a sync is restarted between two of
  * them does not begin it again, since it is no evidence that the rows are now whole. It is kept in memory: a restart
  * of the process begins it again, and is the operator's act.
  */
final class HandoffSampleFailures:
  private var inARow: Int = 0

  /** One more sample that stopped the hand-off: what the controller is to do. */
  def stopped(reason: PathHandoffSample.StopReason): HandoffSampleFailures.Verdict =
    inARow += 1
    HandoffSampleFailures.Metrics.stopped(reason, inARow)
    if inARow >= HandoffSampleFailures.Bound then HandoffSampleFailures.Verdict.GiveUp(inARow)
    else HandoffSampleFailures.Verdict.Restart(inARow)

  /** A hand-off was made: the count begins again. */
  def handedOff(): Unit =
    inARow = 0
    HandoffSampleFailures.Metrics.reset()

  def consecutive: Int = inARow

object HandoffSampleFailures:
  /** The samples in a row that stop the restarts. */
  val Bound: Int = 3

  enum Verdict:
    /** Restart SNAP, as for any finalisation that could not hand off: the `n`th sample in a row. */
    case Restart(n: Int)

    /** Do not restart: `n` samples in a row stopped the hand-off. */
    case GiveUp(n: Int)

  /** `db.state.handoff.sample.stopped` by reason, and `db.state.handoff.sample.stopped.consecutive`. */
  private object Metrics extends com.chipprbots.ethereum.metrics.MetricsContainer:
    private val inARow = new java.util.concurrent.atomic.AtomicLong(0L)
    metrics.gauge("db.state.handoff.sample.stopped.consecutive", () => inARow.get.toDouble)
    // a counter with a tag is made on the registry, which does not put the prefix before the name
    private val stoppedName = s"${metrics.metricsPrefix}.db.state.handoff.sample.stopped"
    private val byReason: Map[PathHandoffSample.StopReason, io.micrometer.core.instrument.Counter] =
      PathHandoffSample.StopReason.values.iterator
        .map(reason => reason -> metrics.registry.counter(stoppedName, "reason", reason.text))
        .toMap

    def stopped(reason: PathHandoffSample.StopReason, n: Int): Unit =
      byReason(reason).increment()
      inARow.set(n.toLong)

    def reset(): Unit = inARow.set(0L)
