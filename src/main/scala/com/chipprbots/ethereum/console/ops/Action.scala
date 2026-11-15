package com.chipprbots.ethereum.console.ops

import java.time.Instant

/** What an action can break, which sets how it is confirmed (spec 029 FR-047, FR-048, research §7.3). Ordered: a
  * read-only dashboard runs nothing above [[Observe]].
  */
enum RiskClass(val label: String, val meaning: String):
  /** Changes nothing: no confirmation. */
  case Observe extends RiskClass("Observe", "changes nothing")

  /** A setting changes, one step puts it back, nothing is interrupted: a dialog with the value before and after. */
  case Adjust extends RiskClass("Adjust", "a setting changes; one step puts it back")

  /** Something in flight is interrupted or made slow, and recovers: the preview, then `y`. */
  case Disrupt extends RiskClass("Disrupt", "something is interrupted or slowed, and recovers")

  /** The node stops doing its job until someone acts from outside: the preview, then a typed word. */
  case Halt extends RiskClass("Halt", "the node stops until someone acts from outside")

/** An action as it declares itself (FR-047): the row of contracts/actions.md.
  *
  * @param name
  *   what the prompt and the audit line call it: `loglevel`, `peer drop`, `rpc stop`
  * @param usage
  *   the command as it is typed, with its parameters
  * @param changes
  *   what it changes, in under fifty characters: the Ops view says it whole at 80 columns or not at all
  * @param undo
  *   how it is undone, or that there is nothing to undo
  */
final case class ActionSpec(name: String, usage: String, changes: String, risk: RiskClass, undo: String)

/** One attempt at an action: the action and its parameters, as the audit line states them. No parameter of any action
  * is a secret (contracts/actions.md).
  */
final case class ActionRequest(action: String, params: Vector[(String, String)] = Vector.empty):

  def param(name: String): Option[String] = params.collectFirst { case (`name`, value) => value }

  /** `peer drop id=0x1f3a…`, for a dialog's title and a notice. */
  def text: String = (action +: params.map((k, v) => s"$k=$v")).mkString(" ")

/** What a dialog shows before the operator confirms (FR-050): what will change, in terms that can be checked
  * afterwards, read from the node when the dialog opened.
  *
  * @param lines
  *   the preview, a sentence a line
  * @param refusal
  *   the action cannot be run now, and why (a peer that is gone, a server already stopped): nothing confirms it
  * @param figures
  *   what the node reads now and will read differently a second later (an age, a score, a block number): shown under
  *   the lines, and no part of whether the preview changed
  */
final case class Preview(lines: Vector[String], refusal: Option[String] = None, figures: Vector[String] = Vector.empty):

  /** Whether `other` says the same will change. The figures are left out: a peer's age or the best block's number
    * moves every second, and a dialog that started again each time one did could never be confirmed (FR-050 asks
    * again when what will change is different, not when a clock ticks).
    */
  def same(other: Preview): Boolean = lines == other.lines && refusal == other.refusal

/** How an attempt ended (FR-051): what the audit line's `outcome` says. */
enum ActionOutcome:
  /** It ran: `result` is a sentence saying what happened. */
  case Confirmed(result: String)
  case Cancelled
  case Refused(reason: String)
  case Failed(reason: String)

  /** The audit line's word, with its reason where there is one. */
  def word: String = this match
    case Confirmed(_)    => "confirmed"
    case Cancelled       => "cancelled"
    case Refused(reason) => s"refused: $reason"
    case Failed(reason)  => s"failed: $reason"

/** An action that is running (FR-057): what it says of its progress, and since when. */
final case class ActionActivity(request: ActionRequest, since: Instant, progress: String)

/** An attempt that has ended. */
final case class ActionRecord(request: ActionRequest, outcome: ActionOutcome, at: Instant)

/** What an Observe action found, for its screen: a row a line, and when it was read. */
final case class ActionReport(lines: Vector[String], at: Instant)

/** What the frame loop reads of the actions (it reads, and never waits): the previews asked for, what is running, what
  * has ended this session, newest last, what the Observe actions last found, by action, and why each action that is not
  * offered on this node is not.
  */
final case class ActionsView(
    previews: Map[ActionRequest, Preview] = Map.empty,
    running: Vector[ActionActivity] = Vector.empty,
    history: Vector[ActionRecord] = Vector.empty,
    reports: Map[String, ActionReport] = Map.empty,
    unavailable: Map[String, String] = Map.empty
):
  def isRunning(action: String): Boolean = running.exists(_.request.action == action)

/** What carries an action out: built by the node from the services it holds, called only on the action runner's
  * threads, never on the frame loop. Both may block; an exception is a failure with its message.
  */
trait ActionHandler:

  /** What will change, read from the node now. */
  def preview(request: ActionRequest): Preview

  /** Carries the action out. `progress` may be called with a sentence as it goes. A Left is why it failed; a Right says
    * what happened: one sentence, or, for an Observe action, what it found, a row a line.
    */
  def run(request: ActionRequest, progress: String => Unit): Either[String, String]

object ActionHandler:

  /** A handler from two functions. */
  def apply(
      previewing: ActionRequest => Preview,
      running: (ActionRequest, String => Unit) => Either[String, String]
  ): ActionHandler = new ActionHandler:
    def preview(request: ActionRequest): Preview = previewing(request)
    def run(request: ActionRequest, progress: String => Unit): Either[String, String] = running(request, progress)
