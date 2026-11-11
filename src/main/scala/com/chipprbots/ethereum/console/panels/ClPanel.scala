package com.chipprbots.ethereum.console.panels

import org.jline.utils.AttributedString
import org.jline.utils.AttributedStringBuilder

import com.chipprbots.ethereum.console.*
import com.chipprbots.ethereum.console.TuiText.formatNumber
import com.chipprbots.ethereum.console.TuiText.formatShort
import com.chipprbots.ethereum.console.panels.Rows.*

/** The consensus-layer panel of the Overview, on an ETH node (spec 027 FR-022, research §3.3, §3.9): whether the
  * consensus layer is calling the Engine API, and what the node made of its calls.
  *
  * Tier S has one row: the link and the two last calls. From tier M three rows: the link and the calls; the head, safe
  * and finalized blocks (and the authentication failures, when there are any); and the payload counts. Before the first
  * call, one row says the node is waiting. A panel with no second row (waiting, or at tier S) says its failed
  * authentications on the first: a JWT secret that does not match leaves the consensus layer waiting for ever.
  *
  * The panel exists only where the probe read the Engine API (`LocalSample.cl`), which `StdNode` does on a
  * proof-of-stake chain with the Engine API on: an ETC node has no consensus layer and no panel. It reads the snapshot
  * alone, as every panel does.
  *
  * The payload counts are the node's own, and the labels say what is counted. `engine_newpayload_invalid` has always
  * counted an ACCEPTED payload with the INVALID ones, so the panel reads the separate accepted count and shows both;
  * SYNCING is shown only when something counted it, as no caller records one today.
  */
private[console] object ClPanel:

  /** The panel, if the probe read the Engine API. */
  def panel(c: Ctx): Option[Panel] =
    c.state.snapshot.flatMap(_.local.cl).map { cl =>
      Panel(1, rowCount(c, cl), 4, (w, _) => rows(c, cl, w), separated = true)
    }

  private def rowCount(c: Ctx, cl: ClSample): Int =
    if c.tier == Tier.S || cl.link(c.state.now) == ClLink.Waiting then 1 else 3

  /** How the first row names its calls: in full with their ages, in full without "ago"; by their short names (tier S);
    * or by their short names with no status, the last resort of a row that must also say its failed authentications.
    */
  private enum Form:
    case Full, NoAgo, Compact, Brief

  /** The panel's rows, each fitted to `w`. */
  def rows(c: Ctx, cl: ClSample, w: Int): Seq[AttributedString] =
    val link = cl.link(c.state.now)
    val (word, style) = link match
      case ClLink.Waiting =>
        val patient = c.state.uptimeSeconds <= ClSample.ClFirstCallGrace.getSeconds
        ("waiting for its first forkchoiceUpdated", if patient then c.t.muted else c.t.warn.bold())
      case ClLink.Connected    => ("connected", c.t.healthy.bold())
      case ClLink.Disconnected => ("DISCONNECTED", c.t.error)
    val dot = s" ${c.g.dot.text} "
    val short = c.tier == Tier.S

    // A node with no second row (waiting, or tier S) says its failed authentications here: a JWT secret that does not
    // match leaves the consensus layer waiting for ever, and the count is the one sign of it.
    val refused =
      Option.when(cl.authFailures > 0 && (short || link == ClLink.Waiting)) {
        s"${formatNumber(cl.authFailures)} auth failure${if cl.authFailures == 1 then "" else "s"}"
      }

    def called(label: String, call: Option[ClCall], form: Form): String =
      call.fold(s"$label never") { k =>
        val age = formatShort(k.ageAt(c.state.now).getSeconds)
        form match
          case Form.Full  => s"$label $age ago (${k.status})"
          case Form.Brief => s"$label $age"
          case _          => s"$label $age (${k.status})"
      }

    def firstRow(form: Form): AttributedString =
      val calls =
        if link == ClLink.Waiting then Nil
        else
          val names =
            if form == Form.Compact || form == Form.Brief then ("forkchoice", "payload")
            else ("forkchoiceUpdated", "newPayload")
          Seq(called(names._1, cl.forkchoiceUpdated, form), called(names._2, cl.newPayload, form))
      val title = if short then field("CL", AttributedString.EMPTY, c.t) else text(" Consensus layer ", c.t.label)
      val first = new AttributedStringBuilder()
      first.append(title)
      // The glyph is the emoji set's: the ASCII set's own is `CL`, the label's, and tier S has the label alone.
      if !short && c.g.set == GlyphSet.Emoji then first.append(s"${c.g.clLink.text} ", style)
      first.append(word, style)
      val rest = calls ++ refused
      if rest.nonEmpty then first.append(dot + rest.mkString(dot), c.t.value)
      first.toAttributedString

    // The long form is cut at the narrowest tier M, 100 columns, in the middle of a status: drop "ago" first. Tier S, with
    // failed authentications to say as well, drops the statuses before it cuts one.
    val forms = if short then Seq(Form.Compact, Form.Brief) else Seq(Form.Full, Form.NoAgo)
    val row1 = forms.iterator.map(firstRow).find(_.columnLength() <= w - 1).getOrElse(firstRow(forms.last))

    val blocks = Seq(
      s"head ${cl.head.fold(c.g.missing.text)(shortHex(_, c.g))}",
      s"safe ${cl.safe.fold(c.g.missing.text)(formatNumber)}",
      s"finalized ${cl.finalized.fold(c.g.missing.text)(formatNumber)}"
    ) ++ Option.when(cl.authFailures > 0)(s"auth failures ${formatNumber(cl.authFailures)}")
    val p = cl.payloads
    val counts = Seq(s"VALID ${formatNumber(p.valid)}") ++
      Option.when(p.syncing > 0)(s"SYNCING ${formatNumber(p.syncing)}") ++
      Seq(s"ACCEPTED ${formatNumber(p.accepted)}", s"INVALID ${formatNumber(p.invalid)}")
    val payloads = s"payloads ${formatNumber(p.total)} (${counts.mkString(dot)})"

    val body =
      if short || link == ClLink.Waiting then Nil
      else Seq(text("   " + blocks.mkString("   "), c.t.value), text("   " + payloads, c.t.value))
    (row1 +: body).map(fit(_, w, c.g))

  /** `0x7b3e…a91c`: a hash's first four and last four hex digits, so both ends can be compared with another tool. */
  private[panels] def shortHex(hex: String, g: TuiGlyphs, head: Int = 4, tail: Int = 4): String =
    val digits = hex.stripPrefix("0x")
    if digits.length > head + tail then s"0x${digits.take(head)}${g.ellipsis.text}${digits.takeRight(tail)}" else hex
