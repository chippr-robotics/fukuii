package com.chipprbots.ethereum.blockchain.sync

import scala.util.Try
import scala.util.control.NonFatal

import com.chipprbots.ethereum.db.storage.pathstate.EscalationKind
import com.chipprbots.ethereum.db.storage.pathstate.GivenUpBranch
import com.chipprbots.ethereum.db.storage.pathstate.StateEscalation

/** Whether a verdict to give the state up is acted on (spec 018 C5a part 7; T164; the user's decisions of 2026-11-15).
  * Two guards, and it decides only: the clear and the sync are the handler's.
  *
  *   - '''The cool-down''': no escalation within `cooldownMillis` of the last (`state.escalation.last`, which is
  *     written with the clear and so is counted across restarts). A sync from scratch takes hours; a node that clears
  *     its state as often as it is told to never has one.
  *   - '''The loop guard, on proof of work''': a branch the state was given up for once is never escalated for again.
  *     If the sync that followed took its pivot from the branch, the branch is the node's chain and is not refused. If
  *     it is refused still, a second sync would end as the first did, and the node stays on the chain it has,
  *     knowingly, until an operator ends it. Another branch is another escalation.
  *
  * What it reads, it reads at each decision: both records are written by the clear, on another thread.
  *
  * '''A record that cannot be read says no''': an escalation that has just happened, a branch that was given up. Taken
  * for none, it would let a node clear its state again at once.
  *
  * @param last
  *   `state.escalation.last`; it throws when the stored value cannot be read
  * @param givenUp
  *   `state.escalation.given-up`; it throws when the stored value cannot be read
  */
final class EscalationGuard(
    cooldownMillis: Long,
    last: () => Option[StateEscalation],
    givenUp: () => Set[GivenUpBranch]
):
  require(cooldownMillis >= 0, "a cool-down is not negative")
  import EscalationGuard.*

  /** What is done about a verdict of `kind` at `nowMillis`. `branch` is given on proof of work.
    *
    * A branch was given up when '''any block of it''' is on the list: its blocks are walked down from the tip of what
    * was refused, as far as the lowest height on the list (forge). Its name is not compared alone: the name is worked
    * out from the number index, which the sync after an escalation rewrites.
    */
  def decide(kind: EscalationKind, branch: Option[EscalationWatch.Branch], nowMillis: Long): Decision =
    val wasGivenUp: Either[String, Boolean] = branch match
      case None => Right(false)
      case Some(refused) =>
        try
          val listed = givenUp()
          if listed.isEmpty then Right(false)
          else
            val lowest = listed.iterator.map(_.number).min
            Right(
              listed.contains(refused.first) ||
                refused.lineage().takeWhile(_.number >= lowest).exists(listed.contains)
            )
        catch case NonFatal(unreadable) => Left(unreadable.getMessage)
    wasGivenUp match
      case Left(why)   => Decision.Unreadable(why)
      case Right(true) => Decision.GivenUp
      case Right(false) =>
        Try(last()).fold(
          unreadable => Decision.Unreadable(unreadable.getMessage),
          {
            // A record dated after now (the clock went back) holds until the clock reaches its end, which is what is
            // reported: an end worked out from now would move with every call (forge).
            case Some(before) if cooldownMillis > 0 && nowMillis - before.atMillis < cooldownMillis =>
              Decision.CoolingDown(before, before.atMillis + cooldownMillis)
            case _ => Decision.Act(kind, branch.map(_.first))
          }
        )

object EscalationGuard:

  enum Decision:
    /** The state is given up: cleared, and synced again. `givenUpFor` is recorded with the clear. */
    case Act(kind: EscalationKind, givenUpFor: Option[GivenUpBranch])

    /** Not now: the last escalation was `last`, and none follows it before `untilMillis`. */
    case CoolingDown(last: StateEscalation, untilMillis: Long)

    /** Never, for this branch: the state was given up for it before, and it cannot be followed still. It stays so until
      * an operator ends it, also where the fault that began it has since been mended (forge).
      */
    case GivenUp

    /** Not while a record of the escalations cannot be read. */
    case Unreadable(why: String)
