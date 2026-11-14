package com.chipprbots.ethereum.console.panels

import org.jline.utils.AttributedString

import com.chipprbots.ethereum.console.*
import com.chipprbots.ethereum.console.panels.Rows.*

/** Help, over the current view (spec 027 research §2.1, §2.2): the keys, and the legend of the glyph set on screen, so
  * an ASCII terminal shows the ASCII mapping.
  */
private[console] object HelpPanel:

  /** Each key and what it does: drawn from the table the keys are dispatched from (spec 029 FR-006), so Help lists
    * exactly the keys that work.
    */
  def Keys: Seq[(String, String)] = Bindings.helpRows

  def panels(c: Ctx): Seq[Panel] =
    Seq(
      vitalsPanel(c, withSummary = true),
      Panel(2, Keys.size + 1, 2, (w, _) => keys(c, w), separated = true),
      Panel(2, 4, 3, (w, _) => legend(c, w), separated = true),
      fillPanel
    )

  private def keys(c: Ctx, w: Int): Seq[AttributedString] =
    fit(text(" Keys", c.t.healthy.bold()), w, c.g) +: Keys.map { (key, what) =>
      fit(concat(text(s"   ${key.padTo(11, ' ')}", c.t.label), text(what, c.t.value)), w, c.g)
    }

  /** The glyphs the dashboard draws, in the set on screen, packed into rows. */
  private def legend(c: Ctx, w: Int): Seq[AttributedString] =
    val g = c.g
    val items = Seq(
      g.worm -> "worm (progress)",
      g.brain -> "brain (done)",
      g.stalled -> "stalled",
      g.check -> "done / active",
      g.diamond -> "peer",
      g.warn -> "warning",
      g.error -> "error",
      g.arrow -> "next fork",
      g.dot -> "to come",
      g.storage -> "SNAP phase",
      Glyph(s"${g.up.text} ${g.down.text} ${g.steady.text}", 5) -> "score trend",
      g.missing -> "no score yet"
    ).map((glyph, what) => s"${glyph.text} $what")
    val title = fit(
      text(s" Glyphs (${g.set.toString.toLowerCase(java.util.Locale.ROOT)}, g switches)", c.t.healthy.bold()),
      w,
      c.g
    )
    val gap = "   "
    val rows = items.foldLeft(Vector.empty[String]) { (rows, item) =>
      rows.lastOption match
        case Some(last) if new AttributedString(s"   $last$gap$item").columnLength() <= w - 1 =>
          rows.init :+ s"$last$gap$item"
        case _ => rows :+ item
    }
    title +: rows.map(r => fit(text(s"   $r", c.t.value), w, c.g))
