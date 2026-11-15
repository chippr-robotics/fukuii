package com.chipprbots.ethereum.console.widgets

import org.jline.utils.AttributedString
import org.jline.utils.AttributedStringBuilder
import org.jline.utils.AttributedStyle

import com.chipprbots.ethereum.console.*
import com.chipprbots.ethereum.console.TuiText.fitToWidth
import com.chipprbots.ethereum.console.panels.Ctx

/** A dialog drawn over the body (spec 029 FR-048): a title, what will happen, and the keys that answer it. It is never
  * animated, and it never covers the alert row: it replaces body rows only.
  */
private[console] object DialogBox:

  /** The widest a dialog is drawn. */
  val MaxCols: Int = 72

  /** What a dialog says.
    *
    * @param body
    *   what will happen, a sentence a row
    * @param keys
    *   the keys that answer it
    */
  final case class Content(title: String, body: Seq[String], keys: String)

  /** What stops with the node (spec 029 FR-049), by what this node is doing: the preview of `quit`. It names what this
    * node is, not counts that change every second, so the dialog does not ask again while the operator reads it.
    */
  def quitLines(s: TuiState): Vector[String] =
    val cl = s.snapshot.flatMap(_.local.cl).isDefined
    val mining = s.nodeSettings.miningEnabled
    val n = s.nodeSettings
    val served = Seq(
      Option.when(n.rpcEnabled)("HTTP"),
      Option.when(n.wsEnabled)("WebSocket"),
      Option.when(n.ipcEnabled)("IPC"),
      Option.when(n.engineEnabled)("the Engine API")
    ).flatten
    Vector("The node process ends: block import stops and every peer is disconnected.") ++
      Option.when(cl)(
        "The consensus client loses its execution client: a validator on it misses duties until it is back."
      ) ++
      Option.when(mining)("Mining stops.") ++
      Option.when(served.nonEmpty)(s"Every RPC client loses its connection: ${served.mkString(", ")}.") ++
      Vector("The TUI cannot start the node again.")

  /** A confirmation's content (spec 029 FR-048): what the action will change, and the keys its class answers to.
    *
    * @param fits
    *   the screen has the rows for the whole of it; where it has not, nothing confirms it: what a dialog says will
    *   happen must be read before it is agreed to
    */
  def confirm(c: DialogKind.Confirm, fits: Boolean = true): Content =
    import ops.RiskClass
    val spec = ops.Actions.named(c.request.action)
    // Stopping the node keeps the question it has always asked; every other action is named with its class.
    val title =
      if c.request.action == ops.Actions.Quit.name then "Stop this node?" else s"${c.risk.label}: ${c.request.text}"
    val changed = Option.when(c.changed)("What this said has changed since it was first shown: read it again, then answer.")
    val body = c.preview match
      case None                                => Seq("Reading what this would change…")
      case Some(p) if p.refusal.isDefined => p.lines ++ p.refusal.map(why => s"Cannot be run now: $why")
      case Some(p) => changed.toSeq ++ p.lines ++ p.figures ++ spec.map(s => s"Undo: ${s.undo}.")
    val keys = c.preview match
      case None                           => "Esc  cancel"
      case Some(p) if p.refusal.isDefined => "Esc  close"
      case Some(_) if !fits               => "enlarge the terminal to read this before confirming      Esc  cancel"
      case Some(_) =>
        c.risk match
          case RiskClass.Observe => "Enter  run      Esc  cancel"
          case RiskClass.Adjust =>
            val (cancel, apply) =
              if c.focus == DialogFocus.Apply then ("  Cancel  ", "[ Apply ]") else ("[ Cancel ]", "  Apply  ")
            s"$cancel   $apply      Tab moves  Enter accepts  Esc cancels"
          case RiskClass.Disrupt => "y  run it      n or Esc  cancel"
          case RiskClass.Halt =>
            s"type ${ops.Actions.HaltWord} then Enter: ${c.typed}_      Esc  cancel"
    Content(title, body, keys)

  /** The columns a dialog's text has in a body `width` wide. */
  def textCols(width: Int): Int = ((width - 4).min(MaxCols).max(1) - 2).max(8)

  /** The rows the whole of `content` takes in a body `width` wide: two rules, the title, each sentence over as many
    * rows as it needs, a blank and the keys.
    */
  def rowsNeeded(content: Content, width: Int): Int =
    5 + content.body.map(wrap(_, textCols(width)).size).sum

  /** Whether a body of `width` by `rows` shows the whole of `c`'s dialog. */
  def fits(c: DialogKind.Confirm, width: Int, rows: Int): Boolean = rowsNeeded(confirm(c), width) <= rows

  /** `text` in rows of at most `cols` columns, broken between words. */
  def wrap(text: String, cols: Int): Seq[String] =
    text.split(' ').foldLeft(Vector.empty[String]) { (rows, word) =>
      rows.lastOption match
        case Some(last) if last.length + 1 + word.length <= cols => rows.init :+ s"$last $word"
        case _                                                   => rows :+ word
    }

  /** `body` with the dialog drawn over its middle rows. With too few rows for the whole dialog, the title and the keys
    * are kept; with one row, the question and the keys share it.
    */
  def over(body: Seq[AttributedString], content: Content, width: Int, c: Ctx): Seq[AttributedString] =
    if body.isEmpty then body
    else
      val cols = (width - 4).min(MaxCols).max(1)
      val left = ((width - cols) / 2).max(0)
      def row(text: String, style: AttributedStyle): AttributedString =
        val sb = new AttributedStringBuilder()
        sb.append(" " * left)
        // A dialog quotes what the operator typed and what the node read of a peer: drawn as text from outside.
        sb.append(TuiText.fit(new AttributedString(s" ${TuiText.external(text, c.g)}", style), cols, c.g))
        fitToWidth(sb.toAttributedString, width)
      val rule = row(c.g.separator.text * (cols - 2).max(0), c.t.warn)
      val title = row(content.title, c.t.error)
      val keys = row(content.keys, c.t.value.bold())
      // A sentence longer than the box goes on over the next row: what a dialog says will happen is never cut.
      val sentences = content.body.flatMap(wrap(_, textCols(width)))
      val full = Seq(rule, title) ++ sentences.map(row(_, c.t.value)) ++ Seq(row("", c.t.value), keys, rule)
      val drawn =
        if body.size >= full.size then full
        else if body.size >= 2 then Seq(title, keys)
        else Seq(row(s"${content.title}  ${content.keys}", c.t.error))
      val start = ((body.size - drawn.size) / 2).max(0)
      body.take(start) ++ drawn ++ body.drop(start + drawn.size)
