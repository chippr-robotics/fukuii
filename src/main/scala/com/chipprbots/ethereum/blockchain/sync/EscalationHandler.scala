package com.chipprbots.ethereum.blockchain.sync

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.blockchain.sync.EscalationGuard.Decision
import com.chipprbots.ethereum.db.storage.pathstate.EscalationKind
import com.chipprbots.ethereum.db.storage.pathstate.GivenUpBranch
import com.chipprbots.ethereum.db.storage.pathstate.StateEscalation

/** What is done about each verdict to give the state up (spec 018 C5a part 7b; T164, T173): it asks the
  * [[EscalationGuard]], says what was decided, counts it, and answers what its owner is to do. It clears nothing: the
  * owner (the sync controller) stops the import, clears the state and begins the sync, on its own thread.
  *
  * '''A verdict is told again and again''' (the watch tells one each time a fork choice or a branch is refused): what
  * was already answered for the same branch, or on proof of stake for the same kind of verdict, is answered `Repeated`,
  * with nothing said and nothing counted, until the decision for it would be another (a cool-down that has ended).
  *
  * '''A branch that was given up is said at ERROR, once for the branch, and counted each time it is first met in a
  * process''': it is a knowing departure from the heaviest chain, and only an operator ends it.
  *
  * Not safe for use from more than one thread: its owner is an actor.
  *
  * @param numberOf
  *   the height of a stored header, by its hash: for the record of an escalation on proof of stake
  * @param suppressed
  *   counts a verdict that was not acted on (`recovery.escalations.suppressed`)
  * @param error
  *   an ERROR line
  * @param warn
  *   a WARN line
  */
final class EscalationHandler(
    guard: EscalationGuard,
    numberOf: ByteString => Option[BigInt],
    suppressed: () => Unit,
    error: String => Unit,
    warn: String => Unit,
    refusedClearHoldsMillis: Long = EscalationHandler.RefusedClearHoldsMillis
):
  import EscalationHandler.*

  // what was answered last for a subject, and until when it stands (a cool-down ends; the others do not)
  private var answered: Map[Subject, (Outcome, Long)] = Map.empty

  // no escalation is answered before this: a clear that could not be begun is not tried again with every verdict
  private var heldUntil: Long = Long.MinValue

  /** The clear an escalation asked for could not be begun, at `nowMillis`: a recovery of the state was still executing
    * a block after the wait, which says it is stuck. Nothing was cleared and nothing recorded, so the guard would say
    * "act" to the next verdict, a slot later, and to each after it: each would hold a thread for the whole wait and
    * refuse recoveries meanwhile (beacon). No verdict is asked about for a while.
    */
  def clearRefused(nowMillis: Long): Unit =
    heldUntil = nowMillis + refusedClearHoldsMillis
    suppressed()

  /** What the owner is to do about `request`, told at `nowMillis`. */
  def onRequest(request: EscalationWatch.Request, nowMillis: Long): Outcome =
    if nowMillis < heldUntil then Outcome.Repeated else decided(request, nowMillis)

  private def decided(request: EscalationWatch.Request, nowMillis: Long): Outcome =
    // On proof of stake the head named changes with every slot: a verdict is of the node, whichever head it was met
    // at, and is answered once for its kind (beacon). On proof of work it is of a branch.
    val subject: Subject =
      request.branch.map(branch => Subject.Branch(branch.first)).getOrElse(Subject.Stake(request.kind))
    answered.get(subject) match
      case Some((_, until)) if nowMillis < until => Outcome.Repeated
      case _ =>
        val (outcome, until) = guard.decide(request.kind, request.branch, nowMillis) match
          case Decision.Act(kind, givenUpFor) =>
            val (hash, number) = givenUpFor match
              case Some(branch) => (branch.hash, branch.number)
              case None =>
                val head = request.head.getOrElse(ByteString.empty)
                (head, numberOf(head).getOrElse(BigInt(0)))
            // an escalation is one event: whatever was answered before belongs to the state that is given up
            answered = Map.empty
            (
              Outcome.Escalate(StateEscalation(nowMillis, kind, pad(hash), number), givenUpFor, request.why),
              Long.MaxValue
            )
          case Decision.CoolingDown(last, untilMillis) =>
            suppressed()
            warn(
              s"[STATE-ESCALATION] This node cannot follow the chain with the state it has (${request.kind.text}): " +
                s"${request.why}. Its state is not given up now: it was given up at ${at(last.atMillis)} " +
                s"(${last.kind.text}), and not again before ${at(untilMillis)} (state.recovery.escalation-cooldown)."
            )
            (Outcome.Suppressed, untilMillis)
          case Decision.GivenUp =>
            suppressed()
            error(
              s"[STATE-ESCALATION-GIVEN-UP] ${request.why}. This node gave its state up for this branch before, and " +
                "still cannot follow it: it does not give its state up for it again, and stays on the chain it has. " +
                "This is a knowing departure from the heaviest chain. It ends only when an operator starts the node " +
                s"once with -D${ForgetSwitch}=true, also where the fault that began it has since been mended."
            )
            (Outcome.Dropped, Long.MaxValue)
          case Decision.Unreadable(why) =>
            suppressed()
            error(
              s"[STATE-ESCALATION] This node cannot follow the chain with the state it has (${request.kind.text}): " +
                s"${request.why}. Its state is not given up: the record of its escalations cannot be read ($why)."
            )
            (Outcome.Unreadable, nowMillis + UnreadableStandsMillis)
        outcome match
          case _: Outcome.Escalate => ()
          case _                   => answered = answered.updated(subject, (outcome, until))
        outcome

  /** Regular sync begins (again): what was answered before is of a sync the node has left. A branch that was dropped
    * stays dropped (the list says so), and is said again when it is next met.
    */
  def reset(): Unit = answered = Map.empty

object EscalationHandler:

  /** The switch an operator ends the loop guard with, for one start. */
  val ForgetSwitch: String = "fukuii.state.recovery.forget-given-up-branches"

  private val HashLength = 32

  /** At a start, before anything syncs: what the operator's switch does (the user's decision of 2026-11-15: the loop
    * guard of a proof-of-work escalation is ended by an operator only). With the switch set, the list of branches the
    * state was given up for is removed, synced, and what was on it is said. A list that cannot be read is removed too:
    * it is what the switch is for. '''It is done at every start the switch is set for''', and says so each time
    * (vault): left set, it keeps the loop guard off.
    *
    * A record of the last escalation that cannot be read is removed with it (forge): the guard says no to every verdict
    * while it stands, and nothing else takes it away. One that can be read is kept: the cool-down is not the switch's
    * to end.
    *
    * @param read
    *   `state.escalation.given-up`; it throws when the stored value cannot be read
    * @param removeSynced
    *   removes the list, synced
    * @return
    *   whether a list was removed
    */
  def forgetGivenUpAtStart(
      asked: Boolean,
      read: () => Set[GivenUpBranch],
      removeSynced: () => Unit,
      warn: String => Unit,
      readLast: () => Option[StateEscalation] = () => None,
      removeLastSynced: () => Unit = () => ()
  ): Boolean =
    if !asked then false
    else
      scala.util.Try(readLast()).failed.foreach { unreadable =>
        removeLastSynced()
        warn(
          s"[STATE-ESCALATION] $ForgetSwitch is set: the record of this node's last escalation could not be read " +
            s"(${unreadable.getMessage}) and is removed. The cool-down between two escalations starts again from none."
        )
      }
      val listed = scala.util.Try(read()).toEither
      listed match
        case Right(none) if none.isEmpty =>
          warn(
            s"[STATE-ESCALATION] $ForgetSwitch is set and no branch is on the list of those this node gave its state " +
              "up for. Unset it: while it is set, the node may give its state up for the same branch again and again."
          )
          false
        case _ =>
          removeSynced()
          val what = listed.fold(
            unreadable => s"a list that could not be read (${unreadable.getMessage})",
            branches =>
              branches.toList
                .sortBy(_.number)
                .map(branch => s"block ${branch.number} ${branch.hash.toArray.take(4).map("%02x".format(_)).mkString}")
                .mkString(", ")
          )
          warn(
            s"[STATE-ESCALATION] $ForgetSwitch is set: the branches this node gave its state up for are forgotten " +
              s"($what), and it may give its state up for them again. Unset the switch now: while it is set this is " +
              "done at every start."
          )
          true

  /** How long no verdict is asked about after a clear that could not be begun. */
  val RefusedClearHoldsMillis: Long = 10 * 60 * 1000L

  /** How long "the record cannot be read" stands before it is looked at, and said, again: a read that failed once may
    * not fail the next time, and one that keeps failing is not to be said with every slot.
    */
  val UnreadableStandsMillis: Long = 10 * 60 * 1000L

  /** What a verdict is about: answered once, then `Repeated`. */
  private enum Subject:
    case Stake(kind: EscalationKind)
    case Branch(first: GivenUpBranch)

  enum Outcome:
    /** The state is to be given up: the owner clears it with this record, and syncs again. */
    case Escalate(record: StateEscalation, givenUpFor: Option[GivenUpBranch], why: String)

    /** Not now: inside the cool-down. */
    case Suppressed

    /** Never, for this branch. */
    case Dropped

    /** Not while the record of the escalations cannot be read. */
    case Unreadable

    /** Answered before, and the answer stands. */
    case Repeated

  // the record holds a hash of 32 bytes; a verdict with no head named is recorded with zeros
  private def pad(hash: ByteString): ByteString =
    if hash.length == HashLength then hash else ByteString(new Array[Byte](HashLength))

  private def at(millis: Long): String = java.time.Instant.ofEpochMilli(millis).toString
