package com.chipprbots.ethereum.console.widgets

import org.jline.utils.AttributedString

import com.chipprbots.ethereum.console.*
import com.chipprbots.ethereum.console.TuiText.fitToWidth
import com.chipprbots.ethereum.console.panels.Ctx

/** The column down the left of the screen, where the terminal is wide and tall enough (spec 029 FR-006): the network
  * and its family, then the views with the one on screen marked. Every row is exactly [[Cols]] columns.
  */
private[console] object Sidebar:

  /** The sidebar's columns; a divider column follows it. */
  val Cols: Int = 18

  /** The sidebar is drawn from tier M: below it the body needs every column. */
  def shown(width: Int, height: Int): Boolean = Tier.of(width, height) != Tier.S

  def rows(c: Ctx, height: Int): Seq[AttributedString] =
    val s = c.state
    def row(text: String, style: org.jline.utils.AttributedStyle): AttributedString =
      fitToWidth(new AttributedString(text, style), Cols)
    val network = row(s" ${TuiText.external(s.networkName, c.g)}", c.t.value.bold())
    val family = s.chain.map(ch => row(s" ${ch.family.label} ${c.g.dot.text} id ${ch.chainId}", c.t.label)).toSeq
    val views = TuiView.values.toSeq.map { v =>
      val current = v == s.view
      val line = s" [${v.key}] ${v.title}"
      if current then row(line, c.t.accent) else row(line, c.t.value)
    }
    val blank = row("", c.t.value)
    (Seq(network) ++ family ++ Seq(blank) ++ views).take(height.max(0)).padTo(height.max(0), blank)
