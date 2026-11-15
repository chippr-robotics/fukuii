package com.chipprbots.ethereum.console.ops

import java.time.Clock
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

import scala.util.control.NonFatal

import org.slf4j.LoggerFactory

import com.chipprbots.ethereum.console.Sanitize

/** Runs the operator's actions (spec 029 FR-051, FR-053, FR-057): previews on one daemon thread, the actions that
  * change something on another, and the Observe actions on a third, so that a preview or a look at the configuration is
  * never stuck behind an action that takes time, and none ever runs on the frame loop. The frame loop reads [[view]]
  * and nothing else.
  *
  * Every attempt ends in one INFO line from the logger [[ActionRunner.AuditLogger]]: `action=<name> params=<…>
  * outcome=<confirmed|cancelled|refused|failed: reason> by=tui`. An action that takes time also writes one when it
  * starts (`outcome=started`), so that a node made slow by a compaction says in its log when the compaction began and
  * from where.
  *
  * The rules no caller can get round are checked here, whatever the dialog allowed: an action above Observe does not
  * run on a read-only dashboard; an action is not started while it is running; an action with no handler is not offered
  * on this node.
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
    actionThread: ExecutorService = ActionRunner.thread("fukuii-tui-action"),
    observeThread: ExecutorService = ActionRunner.thread("fukuii-tui-observe")
):
  import ActionRunner.*

  private val byName: Map[String, ActionSpec] = specs.map(s => s.name -> s).toMap

  /** Why `action` cannot be run on this node, if it cannot: there is no such action, it is not offered here, or the
    * dashboard is read-only and the action changes something.
    */
  def refusal(action: String): Option[String] =
    byName.get(action) match
      case None => Some(s"there is no action '$action'")
      case Some(spec) =>
        if readOnly && spec.risk != RiskClass.Observe then Some(ReadOnly)
        else if !handlers.contains(action) && !Own(action) then
          Some(unavailable.getOrElse(action, "not offered on this node"))
        else None

  // What the Ops view says of each action that cannot be run here, from the start.
  private val state = new AtomicReference(
    ActionsView(unavailable = specs.flatMap(s => refusal(s.name).map(s.name -> _)).toMap)
  )

  /** What the frame loop reads. */
  def view: ActionsView = state.get

  // The requests whose dialog is open, and those being read now.
  private val wanted = java.util.concurrent.ConcurrentHashMap.newKeySet[ActionRequest]()
  private val reading = java.util.concurrent.ConcurrentHashMap.newKeySet[ActionRequest]()

  /** Reads what `request` would change, off the frame loop, and publishes it. Asked again, it reads again: a dialog
    * that stays open asks every poll, so what it shows is never older than that (FR-050).
    */
  def preview(request: ActionRequest): Unit =
    wanted.add(request)
    // One read of a request at a time: a handler slower than the poll is not queued behind itself.
    if reading.add(request) then
      try
        previewThread.execute { () =>
          try
            val read = refusal(request.action) match
              case Some(why) => Preview(Vector.empty, Some(why))
              case None =>
                handlers.get(request.action) match
                  case None => Preview(Vector.empty, Some(CarriedOutByDashboard))
                  case Some(handler) =>
                    try
                      val p = handler.preview(request)
                      // A preview can quote a peer's client id or an address the operator typed: cleaned like any
                      // other text from outside the node.
                      Preview(
                        p.lines.map(Sanitize.line(_)),
                        p.refusal.map(Sanitize.line(_)),
                        p.figures.map(Sanitize.line(_))
                      )
                    catch
                      case NonFatal(e) => Preview(Vector.empty, Some(s"the preview could not be read: ${reason(e)}"))
            // A dialog that closed while this was read does not get its preview back.
            if wanted.contains(request) then
              val _ = state.updateAndGet(v => v.copy(previews = v.previews.updated(request, read)))
          finally
            val _ = reading.remove(request)
        }
      catch
        // The runner was shut down under a dialog still open: there is nothing to read with.
        case _: java.util.concurrent.RejectedExecutionException =>
          val _ = reading.remove(request)

  /** The dialog for `request` closed: its preview is not wanted any more. */
  def forget(request: ActionRequest): Unit =
    val _ = wanted.remove(request)
    val _ = state.updateAndGet(v => v.copy(previews = v.previews - request))

  /** The operator cancelled `request`: said in the audit log, and nothing runs. */
  def cancelled(request: ActionRequest): Unit =
    forget(request)
    record(request, ActionOutcome.Cancelled)

  /** The operator confirmed `request`. It runs on the action thread, after whatever is running there; refused here, and
    * said so, if it may not run or is already running.
    */
  def confirmed(request: ActionRequest): Unit =
    forget(request)
    refusal(request.action) match
      case Some(why) => record(request, ActionOutcome.Refused(why))
      case None =>
        val at = clock.instant()
        // Whether this call put it there. The function may be tried more than once: what the last try decided stands.
        var added = false
        val _ = state.updateAndGet { v =>
          added = !v.isRunning(request.action)
          if added then v.copy(running = v.running :+ ActionActivity(request, at, "waiting to start")) else v
        }
        val observe = byName.get(request.action).exists(_.risk == RiskClass.Observe)
        def ended(outcome: ActionOutcome): Unit =
          val _ = state.updateAndGet(v => v.copy(running = v.running.filterNot(_.request == request)))
          record(request, outcome)
        // An action already running is not started a second time (FR-057). A reading asked for again while it is
        // being read is not an attempt: its screen already says it is reading.
        if !added then (if observe then () else record(request, ActionOutcome.Refused(AlreadyRunning)))
        else
          handlers.get(request.action) match
            case None => ended(ActionOutcome.Refused(CarriedOutByDashboard))
            case Some(handler) =>
              try
                (if observe then observeThread else actionThread).execute(() =>
                  carryOut(request, handler, observe, ended)
                )
              catch
                case _: java.util.concurrent.RejectedExecutionException =>
                  ended(ActionOutcome.Failed("the dashboard is closing"))

  private def carryOut(
      request: ActionRequest,
      handler: ActionHandler,
      observe: Boolean,
      ended: ActionOutcome => Unit
  ): Unit =
    // Whatever happens in the handler, the action stops being "running" and its end is recorded: an error the
    // handler does not survive (an interrupt at shutdown, a stack overflow) included, and then thrown on.
    var outcome: ActionOutcome = ActionOutcome.Failed("it ended without saying how")
    try
      progress(request, "running")
      if LongRunning.contains(request.action) then audit(line(request, "started"))
      outcome =
        try
          handler.run(request, said => progress(request, said)) match
            case Right(result) if observe =>
              // What it found is for its screen, a row a line; the audit line says only that it was read.
              val lines = result.split('\n').toVector.map(Sanitize.line(_, ReportLineChars))
              val _ = state.updateAndGet(v =>
                v.copy(reports = v.reports.updated(request.action, ActionReport(lines, clock.instant())))
              )
              ActionOutcome.Confirmed(s"${lines.size} lines")
            case Right(result) => ActionOutcome.Confirmed(Sanitize.line(result))
            case Left(why)     => ActionOutcome.Failed(Sanitize.line(why))
        catch
          case NonFatal(e) => ActionOutcome.Failed(reason(e))
          case e: Throwable =>
            outcome = ActionOutcome.Failed(reason(e))
            throw e
    finally
      // An Observe action that failed says so on its screen, where the operator is looking.
      outcome match
        case ActionOutcome.Failed(why) if observe =>
          val _ = state.updateAndGet(v =>
            v.copy(reports =
              v.reports.updated(request.action, ActionReport(Vector(s"could not be read: $why"), clock.instant()))
            )
          )
        case _ => ()
      ended(outcome)

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
    val _ = observeThread.shutdownNow()

object ActionRunner:

  /** The one logger every attempt is written from (FR-051, research §8.2). */
  val AuditLogger: String = "fukuii.tui.ops"

  val ReadOnly: String = "the dashboard is read-only (fukuii.tui.read-only)"
  val AlreadyRunning: String = "it is already running"
  val CarriedOutByDashboard: String = "the dashboard carries this out itself: there is nothing for the node to run"

  /** The longest row of a report: a configuration value can be long. */
  val ReportLineChars: Int = 400

  /** An attempt that no runner saw (the dashboard has not been handed the node's services yet, or the action is `quit`,
    * which needs none): written from the same logger, in the same form.
    */
  def audited(request: ActionRequest, outcome: ActionOutcome): Unit = auditLine(line(request, outcome.word))

  /** The attempts the Ops view remembers. */
  val MaxHistory: Int = 50

  /** The actions that take time: each writes a line when it starts as well as when it ends. */
  val LongRunning: Set[String] = Set(Actions.DbCheck.name, Actions.Bundle.name, Actions.DetectIp.name)

  /** The actions the dashboard carries out itself, with no handler from the node: stopping the node is its own quit
    * path, and the sprites, the dependencies and the lookups are screens of its own.
    */
  val Own: Set[String] = Set(Actions.Quit.name, Actions.Art.name, Actions.Deps.name, Actions.Lookup.name)

  private val logger = LoggerFactory.getLogger(AuditLogger)

  private[ops] def auditLine(text: String): Unit = logger.info(text)

  /** `action=peer drop params=id=0x1f3a… outcome=confirmed by=tui`. A parameter is the operator's text: cleaned, so a
    * line is one line.
    */
  def line(request: ActionRequest, outcome: String): String =
    // A value with a blank, a comma, a quote or `=` in it is quoted, so that no value can pass for a field of the line.
    def written(value: String): String =
      val clean = Sanitize.line(value)
      if clean.exists(ch => ch == ' ' || ch == '=' || ch == ',' || ch == '"') then
        "\"" + clean.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
      else clean
    val params =
      if request.params.isEmpty then "-" else request.params.map((k, v) => s"$k=${written(v)}").mkString(",")
    s"action=${request.action} params=$params outcome=${Sanitize.line(outcome)} by=tui"

  private def reason(e: Throwable): String = Sanitize.line(Option(e.getMessage).getOrElse(e.toString))

  private def thread(name: String): ExecutorService =
    Executors.newSingleThreadExecutor { (r: Runnable) =>
      val t = new Thread(r, name)
      t.setDaemon(true)
      t
    }
