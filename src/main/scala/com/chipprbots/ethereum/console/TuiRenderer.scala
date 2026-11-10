package com.chipprbots.ethereum.console

import org.jline.utils.AttributedString
import org.jline.utils.AttributedStringBuilder
import org.jline.utils.AttributedStyle

import com.chipprbots.ethereum.console.TuiText.fitToWidth
import com.chipprbots.ethereum.console.TuiText.formatNumber
import com.chipprbots.ethereum.console.TuiText.formatShort
import com.chipprbots.ethereum.console.panels.Ctx
import com.chipprbots.ethereum.console.panels.Views

/** The TUI's compositor (spec 027 research §7.3): the header band, its separator and the alert row on top, the view's
  * panels laid out by [[Layout]], and the footer on the last row.
  *
  * The renderer is a function of (state, width, height, theme): the glyph set, the view and the frame counter `tick`
  * are the state's, every style is the theme's, and the size picks the [[Tier]] (spec 027 FR-003, FR-004). It reads no
  * clock and no random source, so one state always draws one frame.
  */
class TuiRenderer(config: TuiConfig):

  import TuiRenderer.*

  /** The complete display: exactly `height` lines (for `height > 0`) of exactly `width` columns. */
  def render(state: TuiState, width: Int, height: Int, theme: TuiTheme = TuiTheme.Ansi16): Seq[AttributedString] =
    val w = Math.max(width, 1)
    state.splashCover(height) match
      case Some(0)    => Splash.render(state, w, height, theme, config.showLogo)
      case Some(rows) => handover(state, w, height, theme, rows)
      case None       => dashboard(state, w, height, theme)

  /** The frame, and whether it moves with `tick`: the frame loop paces itself by it (Q1).
    *
    * Drawing it from the home position never scrolls or wraps the terminal. When the content is taller than the
    * terminal, the panels that matter least give up their rows first, and the footer with the keys stays on the last
    * line. While the splash is on screen its rows replace the dashboard's, all of them, or all but the top `k` during
    * the hand-over (§3.7). A dashboard frame moves when the next tick draws it differently: only rows on screen count,
    * and the celebration counts while it runs.
    */
  def frame(state: TuiState, width: Int, height: Int, theme: TuiTheme = TuiTheme.Ansi16): Frame =
    val w = Math.max(width, 1)
    state.splashCover(height) match
      case Some(0)    => Frame(Splash.render(state, w, height, theme, config.showLogo), moving(state))
      case Some(rows) => Frame(handover(state, w, height, theme, rows), true)
      case None =>
        val lines = dashboard(state, w, height, theme)
        def nextDiffers = dashboard(state.copy(tick = state.tick + 1), w, height, theme) != lines
        Frame(lines, moving(state) && (state.catchup.isDefined || (mayMove(state) && nextDiffers)))

  /** Whether anything at all can move: only under full motion. */
  private def moving(state: TuiState): Boolean = state.motion == Motion.Full

  /** Whether any row could move: SNAP's worm rows, a syncing Blocks worm, a spinner beside an in-flight status. A node
    * with none of them (at its head, connected, idle) is not drawn a second time to find out, which halves the frame
    * loop's render cost at the 1 s pace.
    */
  private def mayMove(state: TuiState): Boolean =
    state.snapSyncState.isDefined || (state.bestBlock > 0 && !state.isSynchronized) ||
      InFlight.contains(state.connectionStatus)

  private def handover(state: TuiState, w: Int, height: Int, theme: TuiTheme, rows: Int): Seq[AttributedString] =
    dashboard(state, w, height, theme).take(rows) ++ Splash.render(state, w, height, theme, config.showLogo).drop(rows)

  /** Header, separator, alert row, the view's panels, footer (FR-002). On a terminal too short for all four fixed rows
    * the separator goes first, then the header; the alert row stays above the footer down to two rows.
    */
  private def dashboard(state: TuiState, w: Int, height: Int, theme: TuiTheme): Seq[AttributedString] =
    val c = Ctx(state, Tier.of(w, height), TuiGlyphs.of(state.glyphs), theme, config)
    val header = createHeader(c, w)
    val alert = Alerts.row(state, w, c.g, theme)
    val footer = createFooter(c, w)
    val above = height - 1
    val top =
      if above >= 3 || height <= 0 then Seq(header, c.separator(w), alert)
      else if above == 2 then Seq(header, alert)
      else if above == 1 then Seq(alert)
      else Nil
    val bodyRows = if height <= 0 then 0 else above - top.size
    val body = Layout.render(Views.panels(c, w), w, bodyRows, c.separator(w))
    (top ++ body :+ footer).map(fitToWidth(_, w))

  /** ` ◆ FUKUII etc (61) · PoW · up 1h 23m … Overview `; from tier M the head and the peers, at tier L the version
    * (§3.1). In the accent style; during the celebration's flash (frames 2–6, §3.8) in the flash style.
    */
  private def createHeader(c: Ctx, width: Int): AttributedString =
    val s = c.state
    val style = if s.headerFlash then flashStyle(c.t) else c.t.accent
    val peers =
      if s.connectionStatus.startsWith("Unresponsive") then "peers ?" else s"${s.peerCount}/${s.maxPeers} peers"
    val parts =
      Seq(s.chain.fold(s.networkName)(ch => s"${s.networkName} (${ch.chainId})")) ++
        s.chain.map(_.family.label) ++
        (if c.tier == Tier.S then Nil else Seq(s"head ${formatNumber(s.currentBlock)}", peers)) ++
        Seq(s"up ${formatShort(s.uptimeSeconds)}") ++
        (if c.tier == Tier.L then
           s.chain.map(_.version).filter(_.nonEmpty).map(v => if v.startsWith("v") then v else s"v$v")
         else None)
    val left = new AttributedString(s" ${c.g.diamond.text} FUKUII  ${parts.mkString(s" ${c.g.dot.text} ")}", style)
    val right = new AttributedString(s"${s.viewTitle} ", style)
    val gap = width - left.columnLength() - right.columnLength()
    val sb = new AttributedStringBuilder()
    sb.append(left)
    if gap >= 1 then
      sb.append(" " * gap, style)
      sb.append(right)
    else sb.append(" " * width.max(0), style)
    fitToWidth(sb.toAttributedString, width)

  /** The keys: the views, then `p`, `?`, `r`, `d`, `q` (Q7: `d` is Detach). Short names below tier M. */
  private def createFooter(c: Ctx, width: Int): AttributedString =
    val divider = c.g.divider.text
    val text =
      if c.tier == Tier.S then
        s" ${TuiView.values.map(v => s"${v.key} ${v.short}").mkString(" ")} $divider p Pause ? Help r Redraw d Detach q Quit"
      else
        s" ${TuiView.values.map(v => s"${v.key} ${v.title}").mkString("  ")} $divider " +
          "p Pause  ? Help  r Redraw  d Detach  q Quit"
    val line = new AttributedString(text, c.t.accent)
    val sb = new AttributedStringBuilder()
    sb.append(line)
    sb.append(" " * (width - line.columnLength()).max(0), c.t.accent)
    fitToWidth(sb.toAttributedString, width)

/** A rendered frame, and whether it moves with `tick` (the frame loop's pace, Q1). */
final case class Frame(lines: Seq[AttributedString], animated: Boolean)

object TuiRenderer:
  /** Connection statuses that are waiting for something, each with the word the Peers cell shows: a spinner turns
    * beside it (§4.2).
    */
  private[console] val InFlight: Map[String, String] =
    Map("Initializing" -> "initializing", "Starting node..." -> "starting", "Searching for peers" -> "searching")

  /** The header band's flash: the accent's colour as its background, in bold, so it reads in every palette. */
  private[console] def flashStyle(t: TuiTheme): AttributedStyle = t.healthy.bold().inverse()

  /** Create a renderer with default configuration. */
  def default: TuiRenderer = new TuiRenderer(TuiConfig.default)

  /** Create a renderer with the specified configuration. */
  def apply(config: TuiConfig): TuiRenderer = new TuiRenderer(config)

  /** The 12-line logo. */
  private[console] val SmallLogo: Seq[String] = Seq(
    "                 --                ",
    "               .=+#+.              ",
    "              .+++*#*.             ",
    "             :++++*###-            ",
    "           .=+++++*####+.          ",
    "          .=++++++*#####+.         ",
    "         :++++++++*#######:        ",
    "        :+++++++++*########-       ",
    "       =++++++++++*#########+      ",
    "     .++++++++++++*##########*.    ",
    "   .:+++++++++++++*############:   ",
    "   -++++++++++++++*#############=  "
  )
