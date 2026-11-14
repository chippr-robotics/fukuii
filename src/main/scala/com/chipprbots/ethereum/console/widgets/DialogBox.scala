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

  /** The quit dialog's content (spec 029 FR-049): what stops with the node, by what this node is doing. */
  def quit(c: Ctx): Content =
    val s = c.state
    val cl = s.snapshot.flatMap(_.local.cl).isDefined
    val mining = s.nodeSettings.miningEnabled
    val body = Seq("The node process ends: block import, its peers and every RPC client.") ++
      Option.when(cl)("The consensus client loses its execution client: a validator on it misses duties.") ++
      Option.when(mining)("Mining stops.") ++
      Seq("The TUI cannot start the node again.")
    Content("Stop this node?", body, "y  stop the node      n or Esc  cancel")

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
        sb.append(TuiText.fit(new AttributedString(s" $text", style), cols, c.g))
        fitToWidth(sb.toAttributedString, width)
      val rule = row(c.g.separator.text * (cols - 2).max(0), c.t.warn)
      val title = row(content.title, c.t.error)
      val keys = row(content.keys, c.t.value.bold())
      val full = Seq(rule, title) ++ content.body.map(row(_, c.t.value)) ++ Seq(row("", c.t.value), keys, rule)
      val drawn =
        if body.size >= full.size then full
        else if body.size >= 2 then Seq(title, keys)
        else Seq(row(s"${content.title}  ${content.keys}", c.t.error))
      val start = ((body.size - drawn.size) / 2).max(0)
      body.take(start) ++ drawn ++ body.drop(start + drawn.size)
