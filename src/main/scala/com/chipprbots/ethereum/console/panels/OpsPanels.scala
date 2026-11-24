package com.chipprbots.ethereum.console.panels

import java.time.Duration
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

import org.jline.utils.AttributedString
import org.jline.utils.AttributedStyle

import com.chipprbots.ethereum.console.*
import com.chipprbots.ethereum.console.TuiText.formatShort
import com.chipprbots.ethereum.console.ops.ActionOutcome
import com.chipprbots.ethereum.console.ops.ActionSpec
import com.chipprbots.ethereum.console.ops.Actions
import com.chipprbots.ethereum.console.ops.RiskClass
import com.chipprbots.ethereum.console.panels.Rows.*

/** The Ops view (spec 029 FR-047 to FR-057): whether actions can run, what is running and how far it is, what the last
  * attempts came to, every action with its class and what it changes, and what is not offered with the reason. And the
  * screens the Observe actions fill: the configuration, the tunables, the endpoint test, the addresses, the
  * dependencies.
  *
  * Nothing here runs anything. An action is typed at the prompt (`Enter` or `a` on its row types its name there) and
  * confirmed in its dialog; this view says what there is to type and what became of it.
  */
private[console] object OpsPanels:

  private val Clock = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneOffset.UTC)

  private def classStyle(c: Ctx, risk: RiskClass): AttributedStyle = risk match
    case RiskClass.Observe => c.t.muted
    case RiskClass.Adjust  => c.t.healthy
    case RiskClass.Disrupt => c.t.warn.bold()
    case RiskClass.Halt    => c.t.error

  /** Whether actions run, what is running, and the last attempt. */
  private def status(c: Ctx, w: Int): Seq[AttributedString] =
    val s = c.state
    val mode =
      if c.config.readOnly then
        fit(
          field(
            "Mode",
            text(
              "READ-ONLY: only the Observe actions run (fukuii.tui.read-only); Ctrl-C still stops the node",
              c.t.warn.bold()
            ),
            c.t
          ),
          w,
          c.g
        )
      else
        ObservatoryPanels.partsRow(
          c,
          "Mode",
          Seq("actions allowed", "each is confirmed in its dialog", "every attempt is logged by fukuii.tui.ops"),
          w
        )
    val running = s.actions.running.map { a =>
      val elapsed = formatShort(Duration.between(a.since, s.now).getSeconds.max(0L))
      fit(
        field(
          "Running",
          text(
            TuiText.external(s"${a.request.text} ${c.g.dot.text} $elapsed ${c.g.dot.text} ${a.progress}", c.g),
            c.t.warn.bold()
          ),
          c.t
        ),
        w,
        c.g
      )
    }
    val idle =
      if running.nonEmpty then Nil
      else Seq(fit(field("Running", text("nothing", c.t.muted), c.t), w, c.g))
    mode +: (running ++ idle)

  /** How an attempt ended, in words. */
  private def said(outcome: ActionOutcome): String = outcome match
    case ActionOutcome.Confirmed(result) => s"done: $result"
    case ActionOutcome.Cancelled         => "cancelled"
    case ActionOutcome.Refused(why)      => s"refused: $why"
    case ActionOutcome.Failed(why)       => s"FAILED: $why"

  /** The session's attempts, the newest first: when, what, and what became of it. */
  private def history(c: Ctx, w: Int, n: Int): Seq[AttributedString] =
    val records = c.state.actions.history.reverse
    val title = fit(
      field(
        "Last",
        text(
          if records.isEmpty then "no action has been attempted in this session"
          else s"${records.size} attempt${if records.size == 1 then "" else "s"} in this session, the newest first",
          c.t.value
        ),
        c.t
      ),
      w,
      c.g
    )
    val rows = records.map { r =>
      val look = r.outcome match
        case _: ActionOutcome.Failed  => c.t.error
        case _: ActionOutcome.Refused => c.t.warn.bold()
        case _                        => c.t.value
      // The indent is the dashboard's own: text from outside is trimmed.
      fit(
        text("   " + TuiText.external(s"${Clock.format(r.at)}Z  ${r.request.text}  ${said(r.outcome)}", c.g), look),
        w,
        c.g
      )
    }
    val room = (n - 1).max(0)
    if rows.size <= room then title +: rows
    else if room == 0 then Seq(title)
    else
      (title +: rows.take(room - 1)) :+ fit(
        text(s"   ${c.g.ellipsis.text} ${rows.size - (room - 1)} older attempts not shown at this height", c.t.muted),
        w,
        c.g
      )

  /** The columns of the class and of the command, the same on every row. */
  private val ClassCols: Int = RiskClass.values.map(_.label.length).max
  private val NameCols: Int = Actions.all.map(_.name.length).max

  private def actionRow(c: Ctx, spec: ActionSpec, selected: Boolean, w: Int): AttributedString =
    val mark = if selected then c.g.arrow.text else " "
    val why = c.state.actions.unavailable.get(spec.name)
    // The action's name: the command as it is typed, with its parameters, is the title's while the row is selected.
    val usage = text(spec.name.padTo(NameCols, ' '), if selected then c.t.value.inverse() else c.t.value)
    val about = why.fold(text(spec.changes, c.t.value))(reason =>
      text(TuiText.external(s"not on this node: $reason", c.g), c.t.muted)
    )
    val head = concat(
      text(s" $mark ", c.t.value),
      text(spec.risk.label.padTo(ClassCols, ' '), classStyle(c, spec.risk)),
      text("  ", c.t.value),
      usage,
      text("  ", c.t.value)
    )
    // What it changes is said whole or not at all: a sentence cut in the middle says something else.
    val room = w - 1 - head.columnLength()
    TuiText.fitToWidth(if about.columnLength() <= room then concat(head, about) else head, w)

  /** The actions: the page of them that holds the selection, so the selected row is always on screen, and a count of
    * the ones on other pages.
    */
  private def actions(c: Ctx, w: Int, n: Int): Seq[AttributedString] =
    val all = Actions.all
    val selected = c.state.selectedActionIndex
    val title = selected.map(all(_)) match
      // The selected action's command, as it is typed: Enter or `a` puts its name at the prompt.
      case Some(spec) =>
        ObservatoryPanels.partsRow(c, "Actions", Seq(s"type: ${spec.usage}", s"undo: ${spec.undo}"), w)
      case None =>
        ObservatoryPanels.partsRow(
          c,
          "Actions",
          Seq(s"${all.size}, by what they can break", "Up and Down select", "Enter or a types one at the prompt"),
          w
        )
    val room = (n - 1).max(0)
    if all.size <= room then title +: all.zipWithIndex.map((spec, i) => actionRow(c, spec, selected.contains(i), w))
    else if room <= 1 then Seq(title)
    else
      val shown = room - 1
      val first = selected.fold(0)(i => (i / shown) * shown)
      val page = all.slice(first, first + shown)
      (title +: page.zipWithIndex.map((spec, i) => actionRow(c, spec, selected.contains(first + i), w))) :+ fit(
        text(
          s" ${c.g.ellipsis.text} ${all.size - page.size} more actions on other pages (${first + 1} to ${first + page.size} of ${all.size} shown)",
          c.t.muted
        ),
        w,
        c.g
      )

  /** What the brief asked for and this release does not offer, with the reason (FR-056). */
  private def notOffered(c: Ctx, w: Int): Seq[AttributedString] =
    fit(text(" Not offered, and why", c.t.healthy.bold()), w, c.g) +: Actions.NotOffered.map((what, why) =>
      fit(concat(text(s" $what: ", c.t.label), text(why, c.t.muted)), w, c.g)
    )

  def panels(c: Ctx): Seq[Panel] =
    val running = c.state.actions.running.size.max(1)
    val attempts = c.state.actions.history.size
    Seq(
      vitalsPanel(c, withSummary = true),
      Panel(1 + running, 1 + running, 2, (w, _) => status(c, w), separated = true),
      Panel(4, Actions.all.size + 1, 3, (w, n) => actions(c, w, n), separated = true),
      Panel(1, (attempts + 1).min(8), 4, (w, n) => history(c, w, n), separated = true),
      Panel(0, Actions.NotOffered.size + 1, 5, (w, n) => notOffered(c, w).take(n), separated = true),
      fillPanel
    )

  // ---- the screens the Observe actions fill -----------------------------------------------------------------------

  /** What an Observe action found: a title saying how much there is and how old it is, then its rows, the ones with
    * `filter` in them, from row `scroll`. A row longer than the screen is cut with the ellipsis: a configuration value
    * is words, and the whole of it is in the file it came from.
    */
  def report(c: Ctx, spec: ActionSpec, filter: String, scroll: Int): Seq[Panel] =
    val s = c.state
    val found = s.actions.reports.get(spec.name)
    val reading = s.actions.isRunning(spec.name)
    val needle = filter.trim.toLowerCase(Locale.ROOT)
    val rows = found.fold(Vector.empty[String])(_.lines)
    val matching = if needle.isEmpty then rows else rows.filter(_.toLowerCase(Locale.ROOT).contains(needle))
    def title(w: Int): AttributedString =
      val parts = found match
        case None if reading => Seq(s"reading${c.g.ellipsis.text}")
        case None =>
          Seq(
            s.actions.unavailable
              .get(spec.name)
              .fold("not read yet: the node has not finished starting")(why => s"not on this node: $why")
          )
        case Some(r) =>
          val age = Duration.between(r.at, s.now).getSeconds.max(0L)
          Seq(s"${rows.size} rows") ++
            Option.when(needle.nonEmpty)(s"${matching.size} with '$needle'") ++
            Seq(if age <= 1 then "read now" else s"read ${formatShort(age)} ago") ++
            Option.when(reading)("reading again") ++
            Seq("r reads again", "Esc returns")
      ObservatoryPanels.partsRow(c, spec.name.take(FieldLabelCols - 1), parts, w)
    def body(w: Int, n: Int): Seq[AttributedString] =
      // The scroll never leaves the last page above an empty screen.
      val first = scroll.max(0).min((matching.size - n).max(0))
      val page = matching.slice(first, first + n)
      val above = first
      val below = matching.size - first - page.size
      val shown =
        if above == 0 && below == 0 then page
        else if n <= 2 then page
        else
          // The rows not on screen are counted on the first and the last row of the page, in place of a row each.
          val head = if above > 0 then Vector(s"${c.g.ellipsis.text} $above rows above (Up, PgUp)") else Vector.empty
          val tail = if below > 0 then Vector(s"${c.g.ellipsis.text} $below rows below (Down, PgDn)") else Vector.empty
          head ++ page.slice(head.size, page.size - tail.size) ++ tail
      shown.map(line =>
        fit(text(" " + TuiText.external(line, c.g), if line.startsWith(c.g.ellipsis.text) then c.t.muted else c.t.value), w, c.g)
      )
    Seq(
      vitalsPanel(c, withSummary = true),
      Panel(1, 1, 2, (w, _) => Seq(title(w)), separated = true),
      Panel(0, matching.size.max(1), 3, body, separated = true, fill = true)
    )

  /** What each subsystem needs to do its job: fixed, and said so that a bar that is low can be read against the ones
    * under it.
    */
  val DependsOn: Map[Subsystem, Seq[Subsystem]] = Map(
    Subsystem.P2p -> Nil,
    Subsystem.Chain -> Seq(Subsystem.P2p, Subsystem.Database, Subsystem.Disk),
    Subsystem.ClLink -> Seq(Subsystem.Chain),
    Subsystem.Mining -> Seq(Subsystem.Chain),
    Subsystem.Rpc -> Seq(Subsystem.Chain, Subsystem.Database),
    Subsystem.Database -> Seq(Subsystem.Disk, Subsystem.Memory),
    Subsystem.Disk -> Nil,
    Subsystem.Memory -> Nil
  )

  /** `deps`: each subsystem with its health bar, what it depends on, and the check that sets its bar. */
  def deps(c: Ctx): Seq[Panel] =
    val bars = c.state.healthBars
    def rows(w: Int): Seq[AttributedString] =
      if bars.isEmpty then Seq(fit(field("Deps", text("the node has not been read yet", c.t.muted), c.t), w, c.g))
      else
        fit(
          field("Deps", text("each subsystem, what it needs, and the check that sets its bar", c.t.value), c.t),
          w,
          c.g
        ) +: bars.flatMap { bar =>
          val needs = DependsOn.getOrElse(bar.subsystem, Nil).filter(d => bars.exists(_.subsystem == d))
          val low = needs.filter(d =>
            bars.exists(b => b.subsystem == d && (b.state == CheckState.Warn || b.state == CheckState.Error))
          )
          val parts = Seq(
            if needs.isEmpty then "needs nothing else here" else s"needs ${needs.map(_.label).mkString(", ")}"
          ) ++ Option.when(low.nonEmpty)(
            s"${low.map(_.label).mkString(", ")} raise${if low.size == 1 then "s" else ""} an alert"
          ) ++
            bar.limiting.map(check => s"set by ${check.name}: ${check.value}")
          Seq(HealthPanel.barRow(c, bar, w), ObservatoryPanels.partsRow(c, "", parts.map(TuiText.external(_, c.g)), w))
        }
    Seq(
      vitalsPanel(c, withSummary = true),
      Panel(1, bars.size * 2 + 1, 2, (w, n) => rows(w).take(n), separated = true),
      fillPanel
    )
