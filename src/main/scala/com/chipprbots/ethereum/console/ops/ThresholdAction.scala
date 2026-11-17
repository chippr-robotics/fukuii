package com.chipprbots.ethereum.console.ops

import com.chipprbots.ethereum.console.AlertThresholds
import com.chipprbots.ethereum.console.Alerts
import com.chipprbots.ethereum.console.TuiState

/** `threshold <name> <value>` and `threshold reset` (spec 029 FR-054, contracts/actions.md): where an alert begins, for
  * this session of the dashboard. It changes nothing in the node: only the dashboard's own copy of the thresholds,
  * which the alert row and the health bars read. The preview says the threshold now and after, the alerts that would
  * fire or clear at once, and the line of configuration that makes it permanent.
  */
object ThresholdAction:

  /** The thresholds after `request`, or why it cannot be applied. `configured` is what the node started with. */
  def after(
      now: AlertThresholds,
      configured: AlertThresholds,
      request: ActionRequest
  ): Either[String, AlertThresholds] =
    (request.param("reset"), request.param("name"), request.param("value")) match
      case (Some(_), _, _)             => Right(configured)
      case (None, Some(name), Some(v)) => AlertThresholds.set(now, name, v)
      case _                           => Left("usage: threshold <name> <value> | threshold reset")

  /** What will change: each threshold that differs, the alerts that appear or clear with it, and how to keep it. */
  def preview(state: TuiState, configured: AlertThresholds, request: ActionRequest): Preview =
    after(state.thresholds, configured, request) match
      case Left(why) => Preview(Vector.empty, Some(why))
      case Right(next) =>
        val changed = AlertThresholds.Keys.flatMap { key =>
          for
            before <- AlertThresholds.show(state.thresholds, key)
            later <- AlertThresholds.show(next, key)
            if before != later
          yield (key, before, later)
        }
        if changed.isEmpty then Preview(Vector.empty, Some("the thresholds are already so: nothing would change"))
        else
          val was = Alerts.of(state).map(_.text)
          val will = Alerts.of(state.copy(thresholds = next)).map(_.text)
          val raised = will.filterNot(was.contains)
          val cleared = was.filterNot(will.contains)
          Preview(
            changed.map((key, before, later) => s"$key: $before now, $later after.").toVector ++
              (if raised.isEmpty && cleared.isEmpty then Vector("No alert fires or clears with it now.")
               else
                 raised.map(text => s"Fires at once: $text.").toVector ++
                   cleared.map(text => s"Clears at once: $text.").toVector
              ) ++
              Vector(
                "For this session of the dashboard only: the node, and a dashboard started later, keep the configured value."
              ) ++
              changed.map((key, _, later) => s"To keep it: fukuii.tui.alerts.$key = $later").toVector
          )

  /** The handler: it reads the state on the runner's thread and changes it through `modify`, as a key does. */
  def handler(state: () => TuiState, modify: (TuiState => TuiState) => Unit): ActionHandler =
    // What the node started with: the thresholds the dashboard had when this was made.
    val configured = state().thresholds
    ActionHandler(
      request => preview(state(), configured, request),
      (request, _) =>
        after(state().thresholds, configured, request).map { next =>
          modify(_.copy(thresholds = next))
          if request.param("reset").isDefined then "every threshold is as configured again"
          else
            s"${request.param("name").getOrElse("")} is ${request.param("name").flatMap(AlertThresholds.show(next, _)).getOrElse("")}"
        }
    )
