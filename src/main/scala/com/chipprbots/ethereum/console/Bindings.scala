package com.chipprbots.ethereum.console

/** What a key does when no overlay has the keyboard (spec 029 FR-003, FR-006). */
enum Action:
  case OpenView(view: TuiView)
  case NextView, PreviousView
  case FocusNext, FocusPrevious
  case Up, Down, Left, Right
  case PageUp, PageDown, First, Last
  case Open, Back
  case OpenPrompt, OpenFilter
  case Help, Glyphs, Graphics, Pause, Redraw, Detach, AskQuit

/** One row of the key table: the keys, how Help names them, and what they do.
  *
  * @param label
  *   the keys as Help shows them, e.g. `Up k`
  */
final case class Binding(keys: Seq[Key], label: String, what: String, action: Action)

/** The one key table (spec 029 FR-006, SC-002): [[Update]] dispatches from it and Help is drawn from it, so no key
  * works that Help does not list. Every key that is an escape sequence stands beside a single character that does the
  * same, so a link that delivers no escape sequences loses no function (FR-003).
  */
object Bindings:

  private def chars(cs: Char*): Seq[Key] = cs.map(Key.Char(_))

  val Global: Seq[Binding] =
    val views =
      TuiView.values.toSeq.map(v => Binding(chars(v.key), v.key.toString, s"the ${v.title} view", Action.OpenView(v)))
    views ++ Seq(
      Binding(chars(']'), "]", "the next view (wraps)", Action.NextView),
      Binding(chars('['), "[", "the previous view (wraps)", Action.PreviousView),
      Binding(Seq(Key.Tab), "Tab", "the next panel of the view", Action.FocusNext),
      Binding(Seq(Key.BackTab), "Shift-Tab", "the previous panel of the view", Action.FocusPrevious),
      Binding(Key.Up +: chars('k'), "Up k", "the row above", Action.Up),
      Binding(Key.Down +: chars('j'), "Down j", "the row below", Action.Down),
      Binding(Key.Left +: chars(','), "Left ,", "across, to the left", Action.Left),
      Binding(Key.Right +: chars('.'), "Right .", "across, to the right", Action.Right),
      Binding(Key.PageUp +: chars('-'), "PgUp -", "a page up (Logs: older lines)", Action.PageUp),
      Binding(Key.PageDown +: chars('+', '='), "PgDn +", "a page down (Logs: newer lines)", Action.PageDown),
      Binding(Key.Home +: chars('<'), "Home <", "the first row", Action.First),
      Binding(Key.End +: chars('>'), "End >", "the last row", Action.Last),
      Binding(Seq(Key.Enter), "Enter", "open the selected row", Action.Open),
      Binding(Seq(Key.Esc, Key.Backspace), "Esc Bksp", "back; clear a filter", Action.Back),
      Binding(
        chars(':'),
        ":",
        "the prompt: a view's name, peer <id>, glyphs, graphics, art, detach, quit",
        Action.OpenPrompt
      ),
      Binding(chars('/'), "/", "filter the list on screen as you type", Action.OpenFilter),
      Binding(chars('?', 'h') :+ Key.F(1), "? h F1", "this help; any key but q, r and d returns", Action.Help),
      Binding(chars('l'), "l", "the Logs view", Action.OpenView(TuiView.Logs)),
      Binding(chars('g'), "g", "switch the glyph set: emoji / ascii", Action.Glyphs),
      Binding(chars('v'), "v", "switch the graphics: pixel / block / text", Action.Graphics),
      Binding(chars('p'), "p", "pause or resume the motion (polling continues)", Action.Pause),
      Binding(chars('r'), "r", "redraw the screen", Action.Redraw),
      Binding(chars('d'), "d", "detach: the TUI closes, the node keeps running", Action.Detach),
      Binding(chars('q'), "q", "ask to stop the node (Ctrl-C stops it at once)", Action.AskQuit)
    )

  private val byKey: Map[Key, Action] = Global.flatMap(b => b.keys.map(_ -> b.action)).toMap

  /** What `key` does. A letter means the same in either case, so Caps Lock breaks nothing. */
  def actionFor(key: Key): Option[Action] = key match
    case Key.Char(c) => byKey.get(Key.Char(c.toLower))
    case other       => byKey.get(other)

  /** The key table as Help shows it: the keys, then what they do. The nine view keys are one row. */
  def helpRows: Seq[(String, String)] =
    def views(of: Seq[TuiView]) = of.map(v => s"${v.key} ${v.title}").mkString("  ")
    val (first, second) = TuiView.values.toSeq.splitAt(5)
    val rest = Global.filter(_.action match
      case Action.OpenView(_) => false
      case _                  => true
    )
    (Seq("1-5" -> views(first), "6-9" -> views(second)) ++ rest.map(b => b.label -> b.what)) :+ ("l", "the Logs view")
