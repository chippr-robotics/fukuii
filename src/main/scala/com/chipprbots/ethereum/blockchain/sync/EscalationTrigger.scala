package com.chipprbots.ethereum.blockchain.sync

import com.chipprbots.ethereum.db.storage.pathstate.EscalationKind

/** When a node on the unified state layout gives its state up for a sync from scratch (spec 018 C5a part 6; data-model
  * D.1): the count of what it could not do, and the verdict. It decides only. What is done about an escalation (the
  * cool-down between two, the clear, the sync) is the handler's.
  *
  * Two kinds of refusal, and they are not counted alike (the user's decision of 2026-11-15):
  *   - '''a chain that cannot be reached''' with the state this node has (a head deeper than its records reach, or
  *     below the block its state began at): it '''escalates at once'''. No wait mends it: the records that are missing
  *     were pruned, and the chain moves on;
  *   - '''a fault of this node''' (a record that is corrupt, a block that fails when it is executed again): it
  *     '''waits'''. On proof of stake, for `refusals` refused fork choices in a row and no less than `minSpanMillis`
  *     since the first of them; on proof of work, for `refusals` blocks of growth of the branch that is refused. A
  *     fault that mends itself, or a head that is named once and dropped, costs nothing.
  *
  * '''One count''', whatever the fault, '''set back by any move of the head that succeeds''': a consensus client that
  * names a head this node cannot move to and then one it can has not left the node behind.
  *
  * Not safe for use from more than one thread: its owner is an actor, or holds a lock.
  *
  * @param refusals
  *   `state.recovery.escalation-refusals`
  * @param minSpanMillis
  *   the least time from the first refusal of a run to an escalation; zero where the count is of blocks
  */
final class EscalationTrigger(refusals: Int, minSpanMillis: Long = 0L):
  require(refusals > 0, "an escalation waits for at least one refusal")
  require(minSpanMillis >= 0, "a span of time is not negative")
  import EscalationTrigger.*

  private var count: Int = 0
  private var firstAtMillis: Long = 0L
  private var branchFirstSeenAt: Option[BigInt] = None

  /** How many refusals stand in the run that is open. */
  def refused: Int = count

  /** The head cannot be reached with the state this node has: an escalation, at once. The run of local faults is not
    * touched: it is another matter, and the handler's cool-down is what keeps two escalations apart.
    */
  def unreachable(): Verdict = Verdict.Escalate(EscalationKind.Unreachable)

  /** A fork choice was refused for a fault of this node, at `nowMillis` (proof of stake). */
  def localFault(nowMillis: Long): Verdict =
    if count == 0 then firstAtMillis = nowMillis
    count += 1
    if count >= refusals && nowMillis - firstAtMillis >= minSpanMillis then Verdict.Escalate(EscalationKind.LocalFault)
    else Verdict.Waiting(count)

  /** A branch was refused for a fault of this node when its tip was at `tipNumber` (proof of work). The run is of the
    * branch's growth: it escalates when the tip is `refusals` blocks above where the run began. A tip that is lower
    * than the run's first begins the run again there: it is another branch.
    */
  def localFaultOnBranch(tipNumber: BigInt): Verdict =
    val first = branchFirstSeenAt.filter(_ <= tipNumber).getOrElse {
      branchFirstSeenAt = Some(tipNumber)
      tipNumber
    }
    count = (tipNumber - first).min(BigInt(Int.MaxValue)).toInt
    if tipNumber - first >= refusals then Verdict.Escalate(EscalationKind.LocalFault) else Verdict.Waiting(count)

  /** The head moved: whatever was refused before, the node follows the chain. */
  def headMoved(): Unit =
    count = 0
    firstAtMillis = 0L
    branchFirstSeenAt = None

object EscalationTrigger:

  enum Verdict:
    /** Not yet: `seen` refusals stand in the run. */
    case Waiting(seen: Int)

    /** The state is to be given up, for this reason. */
    case Escalate(kind: EscalationKind)
