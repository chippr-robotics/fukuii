package com.chipprbots.ethereum.blockchain.sync

import com.chipprbots.ethereum.blockchain.sync.EscalationTrigger.Verdict
import com.chipprbots.ethereum.db.storage.pathstate.EscalationKind
import com.chipprbots.ethereum.ledger.StateRecovery.Outcome

/** What a node on the unified state layout makes of each recovery a fork choice asked for (spec 018 C5a part 6): it
  * feeds the [[EscalationTrigger]] and says so when the verdict is to give the state up.
  *
  * '''It says only.''' Nothing is cleared and no sync is begun: `escalate` is told each time the verdict is an
  * escalation, and `said` once for each run of them, for the log. Who acts is the handler of part 7.
  *
  * Safe for use from many threads: a recovery ends on its own thread.
  *
  * @param now
  *   the wall clock, in milliseconds
  * @param escalate
  *   told the kind, each time the verdict is to escalate
  * @param said
  *   told the kind and why, the first time of a run: until the head moves again
  */
final class EscalationWatch(
    trigger: EscalationTrigger,
    now: () => Long,
    escalate: EscalationKind => Unit,
    said: (EscalationKind, String) => Unit
):
  import EscalationWatch.*

  private var saying: Option[EscalationKind] = None
  private var run: Long = 0L

  /** The run of refusals that is open: it changes each time the head moves. A recovery is asked for in one run and may
    * end in a later one, and what it ended with is then of a head the node has since moved past.
    */
  def currentRun: Long = synchronized(run)

  /** A recovery that a fork choice asked for, in the run `askedIn`, ended with `outcome`, for the head `head` names (a
    * few hex digits). Dropped when the head has moved since it was asked for, unless it is itself that move.
    */
  def ended(outcome: Outcome, head: String, askedIn: Long): Unit = synchronized {
    classify(outcome) match
      case Signal.HeadMoved        => moved()
      case _ if askedIn != run     => ()
      case Signal.Nothing          => ()
      case Signal.Unreachable(why) => told(trigger.unreachable(), s"head $head cannot be reached: $why")
      case Signal.Waits(why)       => told(trigger.localFault(now()), s"head $head is not followed: $why")
  }

  /** A fork choice was refused where no recovery is asked for: its head is on a chain the state does not reach. */
  def refusedUnreachable(head: String, why: String): Unit = synchronized {
    told(trigger.unreachable(), s"head $head cannot be reached: $why")
  }

  /** A fork choice was refused where no recovery is asked for, for a fault of this node. */
  def refusedForAFault(head: String, why: String): Unit = synchronized {
    told(trigger.localFault(now()), s"head $head is not followed, for a fault of this node: $why")
  }

  /** The head is where the consensus client named it: a fork choice answered VALID, however it came to be. */
  def headMoved(): Unit = synchronized(moved())

  private def moved(): Unit =
    trigger.headMoved()
    saying = None
    run += 1

  private def told(verdict: Verdict, why: String): Unit = verdict match
    case Verdict.Waiting(_) => ()
    case Verdict.Escalate(kind) =>
      escalate(kind)
      if !saying.contains(kind) then
        saying = Some(kind)
        said(kind, why)

object EscalationWatch:

  /** A slot of the consensus layer: the least time a run of refused fork choices lasts is so many of them. */
  val SlotMillis: Long = 12000L

  /** What a recovery's end says to the trigger. */
  enum Signal:
    /** The head is where the consensus client named it: the run of refusals is over. */
    case HeadMoved

    /** The chain the head is on cannot be reached with the state this node has: known, and no wait changes it. */
    case Unreachable(why: String)

    /** The head was not followed, and a wait may yet mend it: a fault of this node, or a block of the head's chain that
      * is not stored and may be on its way.
      */
    case Waits(why: String)

    /** Neither: the node was busy, was stopping, or the block itself was at fault. */
    case Nothing

  /** The table of data-model D.1, as beacon read it against what a recovery can say.
    *
    * '''At once''' only for what is known not to be reachable: more blocks than a recovery covers have no state held.
    *
    * '''Waits''', with a fault of this node: a branch that could not be committed in place, and a header or a body of
    * the head's chain that is not stored. A branch is not adopted for many reasons, a fault of the state among them,
    * and the outcome does not say which; a block that is missing may be on its way (the download stores headers before
    * bodies, and a head's ancestors are fetched while it is still ACCEPTED). Counted as what may mend, they cost a node
    * that cannot follow the chain some thirteen minutes; counted as what cannot, they would cost a node that can its
    * state.
    *
    * '''Nothing''': a block that is invalid, a state that was not kept, a recovery that was superseded, refused for the
    * queue or cut short by a stop.
    */
  def classify(outcome: Outcome): Signal = outcome match
    case Outcome.Recovered(_) | Outcome.Adopted(_) => Signal.HeadMoved
    case Outcome.TooLong(detail)                   => Signal.Unreachable(detail)
    case Outcome.NotAdopted(detail)                => Signal.Waits(s"its branch was not committed in place: $detail")
    case Outcome.MissingHeader(_)                  => Signal.Waits("a header of its chain is not stored")
    case Outcome.MissingBody(block)                => Signal.Waits(s"the body of block ${block.number} is not stored")
    case Outcome.LocalFault(detail)                => Signal.Waits(s"a fault of this node: $detail")
    case Outcome.AlreadyHeld | Outcome.NotKept(_) | Outcome.Invalid(_, _) | Outcome.Superseded | Outcome.Closed |
        Outcome.Busy =>
      Signal.Nothing
