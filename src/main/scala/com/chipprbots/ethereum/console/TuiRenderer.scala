package com.chipprbots.ethereum.console

import org.jline.utils.AttributedString
import org.jline.utils.AttributedStringBuilder
import org.jline.utils.AttributedStyle

import com.chipprbots.ethereum.console.TuiText.fitToWidth
import com.chipprbots.ethereum.console.TuiText.formatNumber
import com.chipprbots.ethereum.console.TuiText.formatShort
import com.chipprbots.ethereum.console.panels.Ctx
import com.chipprbots.ethereum.console.panels.Views
import com.chipprbots.ethereum.console.widgets.DialogBox
import com.chipprbots.ethereum.console.widgets.Sidebar

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
    * the hand-over (§3.7). A dashboard frame moves when one of the next [[Lookahead]] ticks draws it differently: only
    * rows on screen count, and the celebration counts while it runs.
    */
  def frame(state: TuiState, width: Int, height: Int, theme: TuiTheme = TuiTheme.Ansi16): Frame =
    val w = Math.max(width, 1)
    state.splashCover(height) match
      case Some(0)    => Frame(Splash.render(state, w, height, theme, config.showLogo), moving(state))
      case Some(rows) => Frame(handover(state, w, height, theme, rows), true)
      case None =>
        val lines = dashboard(state, w, height, theme)
        // The next `Lookahead` ticks: a worm at cell 2 draws its ripple at cells 1, 0, 0, so ticks 1 and 2 (mod 3) draw
        // alike and one tick's lookahead would drop the loop to 1 fps for a beat. The later renders run only then.
        def differsAt(ahead: Long) = dashboard(state.copy(tick = state.tick + ahead), w, height, theme) != lines
        def nextDiffers = (1L to Lookahead).exists(differsAt)
        Frame(lines, moving(state) && (state.catchup.isDefined || (mayMove(state) && nextDiffers)))

  /** How many ticks ahead a frame is compared: the ripple's period less one, since the ripple is the one animation that
    * draws the same frame at consecutive ticks (the pulse and the spinner change at every tick).
    */
  private val Lookahead: Long = (WormBar.RipplePeriod - 1).toLong

  /** Whether anything at all can move: only under full motion. */
  private def moving(state: TuiState): Boolean = state.motion == Motion.Full

  /** Whether any row could move: SNAP's worm rows, a syncing Blocks worm, a spinner beside an in-flight status, the
    * spinner of a DAG that is being built. A node with none of them (at its head, connected, idle) is not drawn a
    * second time to find out, which halves the frame loop's render cost at the 1 s pace.
    */
  private def mayMove(state: TuiState): Boolean =
    state.snapSyncState.isDefined || (state.bestBlock > 0 && !state.isSynchronized) ||
      InFlight.contains(state.connectionStatus) || dagBuilding(state)

  /** The mining panel draws a spinner beside a DAG that is not yet loaded (`MiningPanel`). */
  private def dagBuilding(state: TuiState): Boolean =
    state.nodeSettings.miningEnabled && state.snapshot.exists(_.local.mining.exists(_.dag.exists(!_.loaded)))

  private def handover(state: TuiState, w: Int, height: Int, theme: TuiTheme, rows: Int): Seq[AttributedString] =
    dashboard(state, w, height, theme).take(rows) ++ Splash.render(state, w, height, theme, config.showLogo).drop(rows)

  /** Header, separator, alert row, the view's panels, footer (FR-002). On a terminal too short for all four fixed rows
    * the separator goes first, then the header; the alert row stays above the footer down to two rows.
    */
  private def dashboard(state: TuiState, w: Int, height: Int, theme: TuiTheme): Seq[AttributedString] =
    val g = TuiGlyphs.of(state.glyphs)
    // The header and the footer span the screen and take its tier. The body is what the sidebar leaves, and its panels
    // take the tier of that width, so a panel is never drawn for more columns than it gets (spec 029 FR-006).
    val frame = Ctx(state, Tier.of(w, height), g, theme, config)
    val side = if Sidebar.shown(w, height) then Sidebar.Cols + 1 else 0
    val bodyCols = w - side
    val c = Ctx(state, Tier.of(bodyCols, height), g, theme, config)
    val header = createHeader(frame, w)
    val alert = Alerts.row(state, w, g, theme)
    val footer = createFooter(frame, w)
    val above = height - 1
    val top =
      if above >= 3 || height <= 0 then Seq(header, frame.separator(w), alert)
      else if above == 2 then Seq(header, alert)
      else if above == 1 then Seq(alert)
      else Nil
    val bodyRows = if height <= 0 then 0 else above - top.size
    val panels = Layout.render(Views.panels(c), bodyCols, bodyRows, c.separator(bodyCols))
    // A dialog replaces rows of the body only: the alert row stays above it (spec 029 FR-048).
    val asked = state.overlay match
      case Overlay.Dialog(DialogKind.Quit) => DialogBox.over(panels, DialogBox.quit(c), bodyCols, c)
      case _                               => panels
    val body =
      if side == 0 then asked
      else
        Layout.columns(
          Sidebar.rows(frame, bodyRows),
          asked,
          Sidebar.Cols,
          bodyCols,
          new AttributedString(g.divider.text, theme.muted)
        )
    // Every row is already exactly `w` columns: Layout fits the body, and the fixed rows are fitted where they are made.
    (top ++ body) :+ footer

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

  /** The last row (spec 029 FR-006, FR-007): the prompt or the filter while one is being typed; else what the last
    * command had to say; else the keys, more of them the wider the screen. Help lists them all.
    */
  private def createFooter(c: Ctx, width: Int): AttributedString =
    c.state.overlay match
      case Overlay.Prompt(text, cursor, _) =>
        inputLine(c, ":", text, cursor, "Enter run  Tab complete  Esc close", width)
      case Overlay.Filter(text) => inputLine(c, "/", text, text.length, "Enter keep  Esc clear", width)
      case Overlay.Dialog(_)    => band(c, " a question is open: answer it, or Esc", width)
      case Overlay.None =>
        c.state.notice match
          case Some(notice) =>
            fitToWidth(
              new AttributedString(s" ${c.g.warn.text} ${TuiText.external(notice, c.g)}", c.t.warn.bold()),
              width
            )
          case None => band(c, FooterKeys(c.tier), width)

  /** A row of text on the accent band. */
  private def band(c: Ctx, text: String, width: Int): AttributedString =
    val line = new AttributedString(text, c.t.accent)
    val sb = new AttributedStringBuilder()
    sb.append(line)
    sb.append(" " * (width - line.columnLength()).max(0), c.t.accent)
    fitToWidth(sb.toAttributedString, width)

  /** A line being typed: the mark, the text with its cursor in reverse video, and the keys that end it. When the text
    * is longer than the row, its start scrolls off so that the cursor stays on screen.
    */
  private def inputLine(c: Ctx, mark: String, text: String, cursor: Int, keys: String, width: Int): AttributedString =
    val hint = s"  $keys "
    val room = (width - 1 - mark.length - (if width >= 60 then hint.length else 0) - 1).max(1)
    val from = (cursor - room + 1).max(0)
    val shown = text.slice(from, from + room)
    val at = cursor - from
    val sb = new AttributedStringBuilder()
    sb.append(s" $mark", c.t.healthy.bold())
    sb.append(shown.take(at), c.t.value)
    sb.append(shown.slice(at, at + 1).padTo(1, ' '), c.t.value.inverse())
    sb.append(shown.drop(at + 1), c.t.value)
    val typed = sb.toAttributedString
    if width < 60 then fitToWidth(typed, width)
    else
      val out = new AttributedStringBuilder()
      out.append(fitToWidth(typed, width - hint.length))
      out.append(hint, c.t.muted)
      fitToWidth(out.toAttributedString, width)

/** A rendered frame, and whether it moves with `tick` (the frame loop's pace, Q1). */
final case class Frame(lines: Seq[AttributedString], animated: Boolean)

object TuiRenderer:
  /** The footer's keys by tier: the essentials at S, more as the screen widens. Every key is in Help. */
  private[console] def FooterKeys(tier: Tier): String = tier match
    case Tier.S => " 1-9 view  : prompt  / filter  ? help  p pause  r redraw  d detach  q quit"
    case Tier.M => " 1-9 view  Enter open  Esc back  : prompt  / filter  ? help  p pause  r redraw  d detach  q quit"
    case Tier.L =>
      " 1-9 view  [ ] other views  Up Down select  Enter open  Esc back  : prompt  / filter  ? help  g glyphs  " +
        "p pause  r redraw  d detach  q quit"

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
