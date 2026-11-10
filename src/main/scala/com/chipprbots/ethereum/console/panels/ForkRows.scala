package com.chipprbots.ethereum.console.panels

import org.jline.utils.AttributedString

import com.chipprbots.ethereum.console.*
import com.chipprbots.ethereum.console.TuiText.fitToWidth
import com.chipprbots.ethereum.console.TuiText.formatNumber
import com.chipprbots.ethereum.console.panels.Rows.*

/** The fork timeline's rows, for the Overview's forks panel and the Chain view (spec 027 FR-017, research §3.2–§3.4):
  * by block number against the current block on ETC, by timestamp against the head's timestamp on ETH.
  */
private[console] object ForkRows:

  /** Where the chain stands on its timeline: the current block (ETC), or the best header's timestamp (ETH), if read. */
  def position(c: Ctx, chain: ChainFacts): Option[Long] = chain.schedule match
    case ForkSchedule.ByBlock     => Some(c.state.currentBlock)
    case ForkSchedule.ByTimestamp => c.state.snapshot.flatMap(_.local.headTimestamp)

  private def unknown(c: Ctx, w: Int): AttributedString =
    fitToWidth(field("Forks", text("head timestamp not read yet", c.t.muted), c.t), w)

  /** `▶ Spiral @ 19,250,000 (4.0M away)`, or the first unscheduled fork, or nothing to come. */
  private def ahead(c: Ctx, chain: ChainFacts, at: Long): String =
    chain.next(at) match
      case Some(next) => s"${c.g.arrow.text} ${next.name} @ ${chain.when(next)} (${chain.distance(next, at)})"
      case None => chain.notScheduled.headOption.fold("no fork scheduled")(n => s"${c.g.arrow.text} $n not scheduled")

  /** The Overview's forks: one row at S (`Forks Mystique ✓ ═══▶ Spiral @ …`), else a title, the latest active forks
    * with the next one, and a detail row.
    */
  def overview(c: Ctx, chain: ChainFacts, w: Int, oneLine: Boolean): Seq[AttributedString] =
    val check = c.g.check.text
    position(c, chain) match
      case None if oneLine => Seq(unknown(c, w))
      case None =>
        Seq(title(c, chain, w), unknown(c, w))
      case Some(at) =>
        val last = chain.last(at)
        val next = ahead(c, chain, at)
        if oneLine then
          val left = text(s" ${"Forks".padTo(FieldLabelCols, ' ')}", c.t.label)
          val done = text(last.fold("")(f => s"${f.name} $check "), c.t.healthy)
          val right = text(next, c.t.value)
          val room = w - 1 - left.columnLength() - done.columnLength() - right.columnLength()
          val track = text(c.g.timelineTrack.text * room.max(1), c.t.muted)
          Seq(fitToWidth(concat(left, done, track, right), w))
        else
          // As many of the latest active forks as fit before the track and the next one.
          val right = concat(text(c.g.timelineTrack.text * 8, c.t.muted), text(next, c.t.value))
          val room = w - 4 - right.columnLength()
          val active = chain.forks.filter(_.at <= at).map(f => s"${f.name} $check")
          val sep = s" ${c.g.separator.text} "
          val shown = active.reverse
            .scanLeft(Seq.empty[String])((acc, f) => f +: acc)
            .takeWhile(fs => new AttributedString(fs.mkString(sep) + " ").columnLength() <= room)
            .lastOption
            .getOrElse(Nil)
          val timeline =
            concat(
              text("   ", c.t.value),
              text(shown.mkString(sep) + (if shown.nonEmpty then " " else ""), c.t.healthy),
              right
            )
          Seq(title(c, chain, w), fitToWidth(timeline, w), fitToWidth(detail(c, chain, at, last), w))

  private def title(c: Ctx, chain: ChainFacts, w: Int): AttributedString =
    fitToWidth(text(s" Forks (${chain.scheduleLabel})", c.t.healthy.bold()), w)

  /** `   block 21,884,102 · Spiral since 19,250,000 · Olympia not scheduled on this network` */
  private def detail(c: Ctx, chain: ChainFacts, at: Long, last: Option[ForkPoint]): AttributedString =
    val dot = c.g.dot.text
    val where = chain.schedule match
      case ForkSchedule.ByBlock     => s"block ${formatNumber(at)}"
      case ForkSchedule.ByTimestamp => s"head ${ChainFacts.utc(at)}"
    val since = last.map(f => s"${f.name} since ${chain.when(f)}")
    val upcoming = chain.next(at) match
      case Some(next) if chain.schedule == ForkSchedule.ByTimestamp =>
        Some(s"${next.name} activates ${chain.when(next)}")
      case Some(_) => None
      case None    => chain.notScheduled.headOption.map(n => s"$n not scheduled on this network")
    text("   " + (Seq(where) ++ since ++ upcoming).mkString(s" $dot "), c.t.value)

  /** The Chain view's timeline, one row per fork: active (✓), next (▶, with its distance), later (·), and the forks not
    * scheduled.
    */
  def timeline(c: Ctx, chain: ChainFacts, w: Int): Seq[AttributedString] =
    val at = position(c, chain)
    val next = at.flatMap(chain.next)
    val nameCols = (chain.forks.map(_.name) ++ chain.notScheduled).map(_.length).maxOption.getOrElse(0) + 2
    def row(mark: String, markStyle: org.jline.utils.AttributedStyle, name: String, rest: String) =
      fitToWidth(
        concat(text(s"   $mark ", markStyle), text(name.padTo(nameCols, ' '), c.t.label), text(rest, c.t.value)),
        w
      )
    val scheduled = chain.forks.map { f =>
      val when = chain.schedule match
        case ForkSchedule.ByBlock     => s"block ${formatNumber(f.at)}"
        case ForkSchedule.ByTimestamp => s"${ChainFacts.utc(f.at)}  ${f.at}"
      if at.exists(f.at <= _) then row(c.g.check.text, c.t.healthy, f.name, when)
      else if next.contains(f) then
        row(c.g.arrow.text, c.t.warn.bold(), f.name, s"$when  ${at.fold("")(p => chain.distance(f, p))}")
      else row(c.g.dot.text, c.t.muted, f.name, when)
    }
    scheduled ++ chain.notScheduled.map(n => row(c.g.dot.text, c.t.muted, n, "not scheduled on this network"))

  /** The S-tier Chain view: the last fork and the next. */
  def lastAndNext(c: Ctx, chain: ChainFacts, w: Int): Seq[AttributedString] =
    position(c, chain) match
      case None => Seq(unknown(c, w))
      case Some(at) =>
        val last = chain.last(at).fold("none yet")(f => s"${f.name} ${c.g.check.text} since ${chain.when(f)}")
        Seq(
          fitToWidth(field("Last", text(last, c.t.healthy), c.t), w),
          fitToWidth(field("Next", text(ahead(c, chain, at).stripPrefix(c.g.arrow.text + " "), c.t.value), c.t), w)
        )
