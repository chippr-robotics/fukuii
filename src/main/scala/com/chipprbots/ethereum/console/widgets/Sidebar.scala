package com.chipprbots.ethereum.console.widgets

import org.jline.utils.AttributedString

import com.chipprbots.ethereum.console.*
import com.chipprbots.ethereum.console.TuiText.fitToWidth
import com.chipprbots.ethereum.console.gfx.Graphic
import com.chipprbots.ethereum.console.gfx.GraphicRegion
import com.chipprbots.ethereum.console.panels.Ctx

/** The column down the left of the screen, where the terminal is wide and tall enough (spec 029 FR-006): the icon of
  * the chain family the node is on (FR-061), the network in words, then the views with the one on screen marked, and
  * the graphics mode. Every row is exactly [[Cols]] columns.
  */
private[console] object Sidebar:

  /** The sidebar's columns; a divider column follows it. */
  val Cols: Int = 18

  /** The network icon's cells, one column in from the edge. */
  val IconCols: Int = 16
  val IconRows: Int = 8

  /** The sidebar is drawn from tier M: below it the body needs every column. */
  def shown(width: Int, height: Int): Boolean = Tier.of(width, height) != Tier.S

  /** The icon of the node's chain family, placed from the sidebar's top-left corner: Ethereum Classic's mark on a
    * proof-of-work chain, Ethereum's on a proof-of-stake one. None until the node has read its chain configuration.
    */
  def icon(c: Ctx): Option[GraphicRegion] =
    c.state.chain.map { chain =>
      val (sprite, words) = chain.family match
        case ChainFamily.PoW => ("network-etc", "ETC")
        case ChainFamily.PoS => ("network-eth", "ETH")
      GraphicRegion("network", 0, 1, IconCols, IconRows, Graphic.SpriteFrame(sprite, 0, 0), alt = words)
    }

  def regions(c: Ctx, height: Int): Seq[GraphicRegion] = icon(c).filter(_ => height >= IconRows).toSeq

  def rows(c: Ctx, height: Int): Seq[AttributedString] =
    val s = c.state
    def row(text: String, style: org.jline.utils.AttributedStyle): AttributedString =
      fitToWidth(new AttributedString(text, style), Cols)
    val blank = row("", c.t.value)
    val mark = icon(c) match
      case Some(region) =>
        Art
          .cells(c, region)
          .map(cells =>
            fitToWidth(AttributedString.join(new AttributedString(""), new AttributedString(" "), cells), Cols)
          )
      case None => Seq.fill(IconRows)(blank)
    val network = row(s" ${TuiText.external(s.networkName, c.g)}", c.t.value.bold())
    val family = s.chain.toSeq.flatMap { ch =>
      // A chain id is never cut: one too long to share the family's row (Platåberget's has ten digits) has its own.
      val together = s" ${ch.family.label} ${c.g.dot.text} id ${ch.chainId}"
      val identity =
        if together.length < Cols then Seq(row(together, c.t.label))
        else Seq(row(s" ${ch.family.label}", c.t.label), row(s" id ${ch.chainId}", c.t.label))
      identity ++
        (ch.kind match
          case NetworkKind.Test  => Seq(row(" test network", c.t.warn))
          case NetworkKind.Main  => Seq(row(" main network", c.t.label))
          case NetworkKind.Other => Nil
        )
    }
    val views = TuiView.values.toSeq.map { v =>
      val current = v == s.view
      val line = s" [${v.key}] ${v.title}"
      if current then row(line, c.t.accent) else row(line, c.t.value)
    }
    val graphics = row(s" graphics ${s.graphics.label}", c.t.muted)
    (mark ++ Seq(network) ++ family ++ Seq(blank) ++ views ++ Seq(blank, graphics))
      .take(height.max(0))
      .padTo(height.max(0), blank)
