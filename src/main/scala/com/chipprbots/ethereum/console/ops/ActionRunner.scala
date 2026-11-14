package com.chipprbots.ethereum.console.ops

import java.time.Clock
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

import scala.util.control.NonFatal

import org.slf4j.LoggerFactory

import com.chipprbots.ethereum.console.Sanitize

/** Runs the operator's actions (spec 029 FR-051, FR-053, FR-057): previews on one daemon thread, the actions
  * themselves on another, so that a preview is never stuck behind a compaction, and neither ever runs on the frame
  * loop. The frame loop reads [[view]] and nothing else.
  *
  * Every attempt ends in one INFO line from the logger [[ActionRunner.AuditLogger]]:
  * `action=<name> params=<…> outcome=<confirmed|cancelled|refused|failed: reason> by=tui`. An action that takes time
  * also writes one when it starts (`outcome=started`), so that a node made slow by a compaction says in its log when
  * the compaction began and from where.
  *
  * The rules no caller can get round are checked here, whatever the dialog allowed: an action above Observe does not
  * run on a read-only dashboard; an action is not started while it is running; an action with no handler is not
  * offered on this node.
  *
  * @param handlers
  *   what carries each action out, by its name; an action with none is not offered here (`unavailable` says why)
  * @param unavailable
  *   why an action with no handler is not offered on this node, by its name
  */
final class ActionRunner(
    specs: Seq[ActionSpec],
    handlers: Map[String, ActionHandler],
    unavailable: Map[String, String] = Map.empty,
    readOnly: Boolean = false,
    clock: Clock = Clock.systemUTC(),
    audit: String => Unit = ActionRunner.auditLine,
    previewThread: ExecutorService = ActionRunner.thread("fukuii-tui-preview"),
    actionThread: ExecutorService = ActionRunner.thread("fukuii-tui-action")
):
  import ActionRunner.*

  private val state = new AtomicReference(ActionsView())

  private val byName: Map[String, ActionSpec] = specs.map(s => s.name -> s).toMap

  /** What the frame loop reads. */
  def view: ActionsView = state.get

  /** Why `action` cannot be run on this node, if it cannot: there is no such action, it is not offered here, or the
    * dashboard is read-only and the action changes something.
    */
  def refusal(action: String): Option[String] =
    byName.get(action) match
      case None => Some(s"there is no action '$action'")
      case Some(spec) =>
        if readOnly && spec.risk != RiskClass.Observe then Some(ReadOnly)
        else if !handlers.contains(action) then
          Some(unavailable.getOrElse(action, "not offered on this node"))
        else None

  /** Reads what `request` would change, off the frame loop, and publishes it. Asked again, it reads again: a dialog
    * that stays open asks every poll, so what it shows is never older than that (FR-050).
    */
  def preview(request: ActionRequest): Unit =
    previewThread.execute { () =>
      val read = refusal(request.action) match
        case Some(why) => Preview(Vector.empty, Some(why))
        case None =>
          try
            val p = handlers(request.action).preview(request)
            // A preview can quote a peer's client id or an address the operator typed: cleaned like any other
            // text from outside the node.
            Preview(p.lines.map(Sanitize.line), p.refusal.map(Sanitize.line))
          catch case NonFatal(e) => Preview(Vector.empty, Some(s"the preview could not be read: ${reason(e)}"))
      val _ = state.updateAndGet(v => v.copy(previews = v.previews.updated(request, read)))
    }

  /** The dialog for `request` closed: its preview is not wanted any more. */
  def forget(request: ActionRequest): Unit =
    val _ = state.updateAndGet(v => v.copy(previews = v.previews - request))

  /** The operator cancelled `request`: said in the audit log, and nothing runs. */
  def cancelled(request: ActionRequest): Unit =
    forget(request)
    record(request, ActionOutcome.Cancelled)

  /** The operator confirmed `request`. It runs on the action thread, after whatever is running there; refused here,
    * and said so, if it may not run or is already running.
    */
  def confirmed(request: ActionRequest): Unit =
    forget(request)
    refusal(request.action) match
      case Some(why) => record(request, ActionOutcome.Refused(why))
      case None =>
        val at = clock.instant()
        val started = state.updateAndGet { v =>
          if v.isRunning(request.action) then v
          else v.copy(running = v.running :+ ActionActivity(request, at, "waiting to start"))
        }
        // Whether this call put it there: an action already running is not started a second time (FR-057).
        if !started.running.exists(a => a.request == request && a.since == at) then
          record(request, ActionOutcome.Refused(AlreadyRunning))
        else
          actionThread.execute { () =>
            progress(request, "running")
            if byName.get(request.action).exists(s => LongRunning.contains(s.name)) then
              audit(line(request, "started"))
            val outcome =
              try
                handlers(request.action).run(request, said => progress(request, said)) match
                  case Right(result) => ActionOutcome.Confirmed(Sanitize.line(result))
                  case Left(why)     => ActionOutcome.Failed(Sanitize.line(why))
              catch case NonFatal(e) => ActionOutcome.Failed(reason(e))
            val _ = state.updateAndGet(v => v.copy(running = v.running.filterNot(_.request == request)))
            record(request, outcome)
          }

  private def progress(request: ActionRequest, said: String): Unit =
    val _ = state.updateAndGet(v =>
      v.copy(running = v.running.map(a => if a.request == request then a.copy(progress = Sanitize.line(said)) else a))
    )

  /** One attempt, ended: the audit line, and the session's history. */
  private def record(request: ActionRequest, outcome: ActionOutcome): Unit =
    audit(line(request, outcome.word))
    val _ = state.updateAndGet(v =>
      v.copy(history = (v.history :+ ActionRecord(request, outcome, clock.instant())).takeRight(MaxHistory))
    )

  /** Stops both threads: nothing queued starts, and what is running is interrupted. */
  def shutdown(): Unit =
    val _ = previewThread.shutdownNow()
    val _ = actionThread.shutdownNow()

object ActionRunner:

  /** The one logger every attempt is written from (FR-051, research §8.2). */
  val AuditLogger: String = "fukuii.tui.ops"

  val ReadOnly: String = "the dashboard is read-only (fukuii.tui.read-only)"
  val AlreadyRunning: String = "it is already running"

  /** The attempts the Ops view remembers. */
  val MaxHistory: Int = 50

  /** The actions that take time: each writes a line when it starts as well as when it ends. */
  val LongRunning: Set[String] = Set("compact", "dbcheck", "bundle", "detect-ip")

  private val logger = LoggerFactory.getLogger(AuditLogger)

  private def auditLine(text: String): Unit = logger.info(text)

  /** `action=peer drop params=id=0x1f3a… outcome=confirmed by=tui`. A parameter is the operator's text: cleaned, so a
    * line is one line.
    */
  def line(request: ActionRequest, outcome: String): String =
    val params =
      if request.params.isEmpty then "-" else request.params.map((k, v) => s"$k=${Sanitize.line(v)}").mkString(",")
    s"action=${request.action} params=$params outcome=${Sanitize.line(outcome)} by=tui"

  private def reason(e: Throwable): String = Sanitize.line(Option(e.getMessage).getOrElse(e.toString))

  private def thread(name: String): ExecutorService =
    Executors.newSingleThreadExecutor { (r: Runnable) =>
      val t = new Thread(r, name)
      t.setDaemon(true)
      t
    }
