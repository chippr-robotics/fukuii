package com.chipprbots.ethereum.console

/** What [[Update]] needs beside the state and the key.
  *
  * @param page
  *   the rows a list shows: how far Page Up and Page Down move
  * @param configuredMotion
  *   the motion `p` resumes to
  * @param readOnly
  *   no operator action can run (spec 029 FR-053): `q` does not offer to stop the node
  */
final case class UpdateCtx(page: Int, configuredMotion: Motion, readOnly: Boolean = false)

/** What a key does (spec 029 FR-003 to FR-010): a pure function from the state and the key to the next state and the
  * effects [[Tui]] is to run. Every key the TUI understands passes through here, so every key is tested without a
  * terminal.
  *
  * The key goes to the open overlay if there is one (a dialog, the prompt, a filter being typed); else, with Help open,
  * `q`, `r` and `d` keep their meaning and any other key closes it; else it is looked up in [[Bindings.Global]]. Pasted
  * text reaches the prompt and a filter only: it can open nothing and confirm nothing (FR-009).
  */
object Update:

  /** The longest line the prompt or a filter takes. */
  val MaxLine: Int = 256

  /** The lines the prompt remembers. */
  val MaxHistory: Int = 100

  def apply(state: TuiState, key: Key, ctx: UpdateCtx): (TuiState, Seq[Effect]) =
    val s = state.copy(notice = None)
    s.overlay match
      case Overlay.Dialog(kind)   => dialog(s, kind, key)
      case prompt: Overlay.Prompt => typing(s, prompt, key, ctx)
      case Overlay.Filter(text)   => filtering(s, text, key)
      case Overlay.None =>
        key match
          case _: Key.Paste => (s, Nil)
          case _ =>
            Bindings.actionFor(key) match
              case Some(action) if !s.helpOpen || KeptInHelp.contains(action) => run(s, action, ctx)
              case _ if s.helpOpen                                            => (s.closeHelp, Nil)
              case _                                                          => (s, Nil)

  /** What keeps its meaning while Help is open; any other key closes Help. */
  private val KeptInHelp: Set[Action] = Set(Action.AskQuit, Action.Redraw, Action.Detach)

  private def run(s: TuiState, action: Action, ctx: UpdateCtx): (TuiState, Seq[Effect]) = action match
    case Action.OpenView(view) => (s.withView(view), Nil)
    case Action.NextView       => (s.nextView, Nil)
    case Action.PreviousView   => (s.previousView, Nil)
    // Every view has one panel that takes keys for now; the later slices of spec 029 add the ones Tab moves between.
    case Action.FocusNext | Action.FocusPrevious => (s, Nil)
    case Action.Up                               => (move(s, ctx, -1), Nil)
    case Action.Down                             => (move(s, ctx, 1), Nil)
    case Action.PageUp                           => (move(s, ctx, -ctx.page.max(1)), Nil)
    case Action.PageDown                         => (move(s, ctx, ctx.page.max(1)), Nil)
    case Action.First                            => (move(s, ctx, Int.MinValue), Nil)
    case Action.Last                             => (move(s, ctx, Int.MaxValue), Nil)
    case Action.Left | Action.Right              => (s, Nil)
    case Action.Open                             => (open(s), Nil)
    case Action.Back                             => (back(s), Nil)
    case Action.OpenPrompt                       => (s.copy(overlay = Overlay.Prompt("", 0, None)), Nil)
    case Action.OpenFilter =>
      if s.screen.isEmpty && !s.helpOpen && Filterable.contains(s.view) then
        (s.copy(overlay = Overlay.Filter(s.filters.getOrElse(s.view, ""))), Nil)
      else (s, Nil)
    case Action.Help    => (s.openHelp, Nil)
    case Action.Glyphs  => (s.toggleGlyphs, Nil)
    case Action.Pause   => (s.togglePause(ctx.configuredMotion), Nil)
    case Action.Redraw  => (s, Seq(Effect.Redraw))
    case Action.Detach  => (s, Seq(Effect.Detach))
    case Action.AskQuit => (askQuit(s, ctx), Nil)

  /** Stopping the node is an action: in read-only mode it is not offered. Ctrl-C is a signal and stops it regardless.
    */
  private def askQuit(s: TuiState, ctx: UpdateCtx): TuiState =
    if ctx.readOnly then s.copy(notice = Some(ReadOnlyNotice))
    else s.copy(overlay = Overlay.Dialog(DialogKind.Quit))

  val ReadOnlyNotice: String = "read-only: the TUI cannot stop the node; Ctrl-C does"

  /** The views whose list `/` filters. */
  val Filterable: Set[TuiView] = Set(TuiView.Peers, TuiView.Logs)

  /** The selection, or the scroll, of the list on screen moves by `by` rows: negative is up. `Int.MinValue` and
    * `Int.MaxValue` are the first and the last row.
    */
  private def move(s: TuiState, ctx: UpdateCtx, by: Int): TuiState =
    if s.helpOpen || s.screen.isDefined then s
    else
      s.view match
        case TuiView.Peers =>
          val peers = s.orderedPeers
          if peers.isEmpty then s
          else
            val last = peers.size - 1
            val target = by match
              case Int.MinValue => 0
              case Int.MaxValue => last
              // With nothing selected, the first move selects the first row.
              case _ => s.selectedPeerIndex.fold(0)(i => (i.toLong + by).max(0L).min(last.toLong).toInt)
            s.copy(selectedPeer = Some(peers(target).id))
        case TuiView.Logs =>
          // Up is older: the scroll counts lines back from the newest.
          by match
            case Int.MinValue => s.scrollLogs(s.visibleLogs.size, ctx.page)
            case Int.MaxValue => s.copy(logScroll = 0)
            case _            => s.scrollLogs(-by, ctx.page)
        case _ => s

  private def open(s: TuiState): TuiState =
    if s.helpOpen || s.screen.isDefined then s
    else
      s.view match
        case TuiView.Peers =>
          s.selectedPeerIndex.fold(s)(i => s.copy(screens = Screen.PeerDetail(s.orderedPeers(i).id) :: s.screens))
        case _ => s

  /** Esc: the screen on top closes; with none open, the view's filter is cleared. */
  private def back(s: TuiState): TuiState =
    if s.screens.nonEmpty then s.copy(screens = s.screens.tail)
    else if s.filterOf(s.view).nonEmpty then s.copy(filters = s.filters - s.view, logScroll = 0)
    else s

  // ---- the quit dialog ------------------------------------------------------------------------------------------

  private def dialog(s: TuiState, kind: DialogKind, key: Key): (TuiState, Seq[Effect]) = kind match
    case DialogKind.Quit =>
      key match
        case Key.Char('y' | 'Y')                           => (s.copy(overlay = Overlay.None), Seq(Effect.Quit))
        case Key.Char('n' | 'N') | Key.Esc | Key.Backspace => (s.copy(overlay = Overlay.None), Nil)
        // Anything else, pasted text included, neither confirms nor cancels.
        case _ => (s, Nil)

  // ---- the prompt -----------------------------------------------------------------------------------------------

  private def typing(s: TuiState, p: Overlay.Prompt, key: Key, ctx: UpdateCtx): (TuiState, Seq[Effect]) =
    def edited(text: String, cursor: Int): (TuiState, Seq[Effect]) =
      (s.copy(overlay = Overlay.Prompt(text.take(MaxLine), cursor.min(MaxLine).min(text.length).max(0), None)), Nil)
    def insert(t: String) = edited(p.text.take(p.cursor) + t + p.text.drop(p.cursor), p.cursor + t.length)
    key match
      case Key.Char(c)  => insert(c.toString)
      case Key.Paste(t) => insert(t)
      case Key.Backspace =>
        if p.text.isEmpty then (s.copy(overlay = Overlay.None), Nil)
        else if p.cursor == 0 then (s, Nil)
        else edited(p.text.take(p.cursor - 1) + p.text.drop(p.cursor), p.cursor - 1)
      case Key.Left  => (s.copy(overlay = p.copy(cursor = (p.cursor - 1).max(0))), Nil)
      case Key.Right => (s.copy(overlay = p.copy(cursor = (p.cursor + 1).min(p.text.length))), Nil)
      case Key.Home  => (s.copy(overlay = p.copy(cursor = 0)), Nil)
      case Key.End   => (s.copy(overlay = p.copy(cursor = p.text.length)), Nil)
      case Key.Tab =>
        val done = Commands.complete(p.text)
        edited(done, done.length)
      case Key.Up =>
        val at = p.historyAt.fold(s.promptHistory.size - 1)(_ - 1)
        if at < 0 || s.promptHistory.isEmpty then (s, Nil)
        else
          val line = s.promptHistory(at)
          (s.copy(overlay = Overlay.Prompt(line, line.length, Some(at))), Nil)
      case Key.Down =>
        p.historyAt match
          case Some(at) if at + 1 < s.promptHistory.size =>
            val line = s.promptHistory(at + 1)
            (s.copy(overlay = Overlay.Prompt(line, line.length, Some(at + 1))), Nil)
          case Some(_) => (s.copy(overlay = Overlay.Prompt("", 0, None)), Nil)
          case None    => (s, Nil)
      case Key.Esc   => (s.copy(overlay = Overlay.None), Nil)
      case Key.Enter => submit(s, p.text, ctx)
      case _         => (s, Nil)

  private def submit(s: TuiState, line: String, ctx: UpdateCtx): (TuiState, Seq[Effect]) =
    val text = line.trim
    val history =
      if text.isEmpty || s.promptHistory.lastOption.contains(text) then s.promptHistory
      else (s.promptHistory :+ text).takeRight(MaxHistory)
    val closed = s.copy(overlay = Overlay.None, promptHistory = history)
    if text.isEmpty then (closed, Nil)
    else
      Commands.parse(text) match
        case Command.OpenView(view) => (closed.withView(view), Nil)
        case Command.OpenPeer(prefix) =>
          val all = closed.copy(filters = closed.filters - TuiView.Peers).orderedPeers
          all.filter(
            _.id.toLowerCase(java.util.Locale.ROOT).startsWith(prefix.toLowerCase(java.util.Locale.ROOT))
          ) match
            case Seq(peer) =>
              val shown = closed.withView(TuiView.Peers)
              (
                shown.copy(
                  filters = shown.filters - TuiView.Peers,
                  selectedPeer = Some(peer.id),
                  screens = List(Screen.PeerDetail(peer.id))
                ),
                Nil
              )
            case Seq() => (closed.copy(notice = Some(s"no connected peer's id starts with '$prefix'")), Nil)
            case several =>
              (closed.copy(notice = Some(s"${several.size} peers' ids start with '$prefix': type more of it")), Nil)
        case Command.Quit           => (askQuit(closed, ctx), Nil)
        case Command.Detach         => (closed, Seq(Effect.Detach))
        case Command.Help           => (closed.openHelp, Nil)
        case Command.Glyphs(set)    => (closed.copy(glyphs = set), Nil)
        case Command.Usage(message) => (closed.copy(notice = Some(message)), Nil)
        case Command.Unknown(word, nearest) =>
          val hint = if nearest.isEmpty then "; ? lists the keys" else s"; did you mean ${nearest.mkString(", ")}?"
          (closed.copy(notice = Some(s"no command '$word'$hint")), Nil)

  // ---- a filter being typed -------------------------------------------------------------------------------------

  /** The filter applies as it is typed. Enter keeps it; Esc clears it. */
  private def filtering(s: TuiState, text: String, key: Key): (TuiState, Seq[Effect]) =
    def to(next: String): (TuiState, Seq[Effect]) =
      val kept = next.take(MaxLine)
      (s.copy(overlay = Overlay.Filter(kept), filters = s.filters.updated(s.view, kept), logScroll = 0), Nil)
    key match
      case Key.Char(c)   => to(text + c)
      case Key.Paste(t)  => to(text + t)
      case Key.Backspace => if text.isEmpty then (s.copy(overlay = Overlay.None), Nil) else to(text.dropRight(1))
      case Key.Enter     => (s.copy(overlay = Overlay.None), Nil)
      case Key.Esc       => (s.copy(overlay = Overlay.None, filters = s.filters - s.view, logScroll = 0), Nil)
      case _             => (s, Nil)
