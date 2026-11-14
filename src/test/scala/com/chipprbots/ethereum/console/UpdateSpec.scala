package com.chipprbots.ethereum.console

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags.*

/** What a key does to the state (spec 029 FR-003 to FR-010), with no terminal: [[Update]] is a pure function. */
class UpdateSpec extends AnyFlatSpec with Matchers:

  private val ctx = UpdateCtx(page = 10, configuredMotion = Motion.Full)
  private def press(s: TuiState, keys: Key*): TuiState = keys.foldLeft(s)((state, k) => Update(state, k, ctx)._1)
  private def typed(text: String): Seq[Key] = text.map(Key.Char(_))
  private val synced: TuiState = TuiFixtures.syncedEtc()
  private def peers(s: TuiState): Seq[String] = s.orderedPeers.map(_.id)

  "A view key" should "open its view, in either case of a letter" taggedAs (UnitTest) in {
    for v <- TuiView.values do press(synced, Key.Char(v.key)).view shouldBe v
    press(synced, Key.Char('L')).view shouldBe TuiView.Logs
    press(synced, Key.Char('l')).view shouldBe TuiView.Logs
  }

  "Every escape-sequence key" should "have a single character that does the same (FR-003)" taggedAs (UnitTest) in {
    val named: Seq[Key] =
      Seq(Key.Up, Key.Down, Key.Left, Key.Right, Key.PageUp, Key.PageDown, Key.Home, Key.End, Key.F(1))
    for key <- named do
      val action = Bindings.actionFor(key)
      withClue(s"$key: ") {
        action should not be empty
        Bindings.Global.find(_.action == action.get).get.keys.exists {
          case Key.Char(_) | Key.Enter | Key.Tab | Key.Backspace => true
          case _                                                 => false
        } shouldBe true
      }
    // Shift-Tab is the one without: Tab wraps, so it reaches every panel.
    Bindings.actionFor(Key.BackTab) shouldBe Some(Action.FocusPrevious)
  }

  "Help" should "list every key that is bound, and bind every key it lists (FR-006)" taggedAs (UnitTest) in {
    val listed = Bindings.helpRows.map(_._1).mkString(" ")
    for b <- Bindings.Global do
      b.action match
        case Action.OpenView(v) if v != TuiView.Logs || b.label != "l" =>
          listed should (include("1-5").and(include("6-9")))
        case _ => listed should include(b.label)
    Bindings.Global.flatMap(_.keys).distinct.size shouldBe Bindings.Global.flatMap(_.keys).size // no key bound twice
  }

  // ---- the Peers list -----------------------------------------------------------------------------------------------

  "Down and Up" should "move the Peers selection, by the arrow or by j and k, and stop at the ends" taggedAs (
    UnitTest
  ) in {
    val list = press(synced, Key.Char('3'))
    val ids = peers(list)
    ids.size should be > 3
    list.selectedPeer shouldBe None
    press(list, Key.Down).selectedPeer shouldBe Some(ids.head) // the first move selects the first row
    press(list, Key.Down, Key.Down, Key.Char('j')).selectedPeer shouldBe Some(ids(2))
    press(list, Key.Down, Key.Down, Key.Up).selectedPeer shouldBe Some(ids(0))
    press(list, Key.Down, Key.Char('k'), Key.Up).selectedPeer shouldBe Some(ids.head)
    press(list, Key.End).selectedPeer shouldBe Some(ids.last)
    press(list, Key.Char('>'), Key.Down).selectedPeer shouldBe Some(ids.last)
    press(list, Key.End, Key.Home).selectedPeer shouldBe Some(ids.head)
    press(list, Key.Down, Key.PageDown).selectedPeer shouldBe Some(ids((0 + ctx.page).min(ids.size - 1)))
  }

  they should "do nothing on a view with no list" taggedAs (UnitTest) in {
    for key <- Seq(Key.Down, Key.Up, Key.PageDown, Key.Home, Key.End, Key.Enter) do press(synced, key) shouldBe synced
  }

  "Enter" should "open the selected peer, and Esc return to the list with it still selected (FR-005)" taggedAs (
    UnitTest
  ) in {
    val list = press(synced, Key.Char('3'), Key.Down, Key.Down)
    val id = list.selectedPeer.get
    val detail = press(list, Key.Enter)
    detail.screen shouldBe Some(Screen.PeerDetail(id))
    detail.viewTitle shouldBe "Peer"
    press(detail, Key.Down).selectedPeer shouldBe Some(id) // the list under it does not move
    for back <- Seq(Key.Esc, Key.Backspace) do
      val returned = press(detail, back)
      returned.screen shouldBe None
      returned.view shouldBe TuiView.Peers
      returned.selectedPeer shouldBe Some(id)
  }

  it should "open nothing with no row selected" taggedAs (UnitTest) in {
    press(synced, Key.Char('3'), Key.Enter).screen shouldBe None
  }

  "A view key" should "close a screen opened over the view" taggedAs (UnitTest) in {
    val detail = press(synced, Key.Char('3'), Key.Down, Key.Enter)
    press(detail, Key.Char('1')).screen shouldBe None
    press(detail, Key.Char('3')).screen shouldBe None
  }

  // ---- filters ------------------------------------------------------------------------------------------------------

  "/" should "filter the Peers list as it is typed; Enter keeps the filter, Esc clears it (FR-008)" taggedAs (
    UnitTest
  ) in {
    val list = press(synced, Key.Char('3'))
    val wanted = peers(list).head.take(6)
    val typing = press(list, (Key.Char('/') +: typed(wanted))*)
    typing.overlay shouldBe Overlay.Filter(wanted)
    peers(typing) should not be empty
    all(peers(typing)) should startWith(wanted)
    val kept = press(typing, Key.Enter)
    kept.overlay shouldBe Overlay.None
    kept.filterOf(TuiView.Peers) shouldBe wanted
    press(kept, Key.Esc).filterOf(TuiView.Peers) shouldBe "" // Esc on the list clears it
    val cleared = press(typing, Key.Esc)
    cleared.overlay shouldBe Overlay.None
    peers(cleared) shouldBe peers(list)
  }

  it should "take digits and letters as text while it is typed, not as keys" taggedAs (UnitTest) in {
    val typing = press(synced, (Key.Char('3') +: Key.Char('/') +: typed("q1d"))*)
    typing.view shouldBe TuiView.Peers
    typing.overlay shouldBe Overlay.Filter("q1d")
    press(typing, Key.Backspace).overlay shouldBe Overlay.Filter("q1")
    press(typing, Key.Paste("xy")).overlay shouldBe Overlay.Filter("q1dxy")
  }

  it should "filter the log lines by text, and do nothing on a view with no list" taggedAs (UnitTest) in {
    val logs = press(synced, Key.Char('8'))
    val word = logs.logs.last.text.split(" ").maxBy(_.length)
    val filtered = press(logs, (Key.Char('/') +: typed(word) :+ Key.Enter)*)
    filtered.visibleLogs should not be empty
    filtered.visibleLogs.size should be <= logs.logs.size
    all(filtered.visibleLogs.map(_.text.toLowerCase)) should include(word.toLowerCase)
    press(synced, Key.Char('/')).overlay shouldBe Overlay.None
  }

  // ---- the prompt ---------------------------------------------------------------------------------------------------

  private def run(s: TuiState, line: String): (TuiState, Seq[Effect]) =
    val typing = press(s, (Key.Char(':') +: typed(line))*)
    Update(typing, Key.Enter, ctx)

  ":" should "open the prompt, and a view's name open the view (FR-007)" taggedAs (UnitTest) in {
    press(synced, Key.Char(':')).overlay shouldBe Overlay.Prompt("", 0, None)
    for v <- TuiView.values do
      val (s, effects) = run(synced, v.title.toLowerCase)
      s.view shouldBe v
      s.overlay shouldBe Overlay.None
      effects shouldBe empty
    run(synced, "  MEMPOOL ")._1.view shouldBe TuiView.Mempool
  }

  it should "take every character as text: q, d and digits type, they do not act" taggedAs (UnitTest) in {
    val typing = press(synced, (Key.Char(':') +: typed("qd9?"))*)
    typing.overlay shouldBe Overlay.Prompt("qd9?", 4, None)
    typing.view shouldBe synced.view
  }

  it should "edit the line: cursor keys, Backspace, a paste at the cursor" taggedAs (UnitTest) in {
    val typing = press(synced, (Key.Char(':') +: typed("pers"))*)
    val fixed = press(typing, Key.Left, Key.Left, Key.Char('e'))
    fixed.overlay shouldBe Overlay.Prompt("peers", 3, None)
    press(fixed, Key.Home, Key.Paste("x\ry")).overlay shouldBe Overlay.Prompt("x\rypeers", 3, None)
    press(fixed, Key.End, Key.Backspace).overlay shouldBe Overlay.Prompt("peer", 4, None)
    press(
      press(synced, Key.Char(':')),
      Key.Backspace
    ).overlay shouldBe Overlay.None // Backspace on an empty line closes
    press(typing, Key.Esc).overlay shouldBe Overlay.None
  }

  it should "complete a command with Tab" taggedAs (UnitTest) in {
    press(synced, Key.Char(':'), Key.Char('m'), Key.Tab).overlay shouldBe Overlay.Prompt("mempool", 7, None)
    press(synced, Key.Char(':'), Key.Char('p'), Key.Tab).overlay shouldBe Overlay.Prompt("peer", 4, None)
    press(synced, Key.Char(':'), Key.Char('z'), Key.Tab).overlay shouldBe Overlay.Prompt("z", 1, None)
  }

  it should "name the nearest commands for one it does not know, and do nothing else" taggedAs (UnitTest) in {
    val (s, effects) = run(synced, "mempol")
    effects shouldBe empty
    s.view shouldBe synced.view
    s.notice.get should (include("no command 'mempol'").and(include("mempool")))
    press(s, Key.Char('x')).notice shouldBe None // the notice lasts until the next key
  }

  it should "walk the history with Up and Down" taggedAs (UnitTest) in {
    val (a, _) = run(synced, "peers")
    val (b, _) = run(a, "logs")
    b.promptHistory shouldBe Vector("peers", "logs")
    val up = press(b, Key.Char(':'), Key.Up)
    up.overlay shouldBe Overlay.Prompt("logs", 4, Some(1))
    press(up, Key.Up).overlay shouldBe Overlay.Prompt("peers", 5, Some(0))
    press(up, Key.Up, Key.Up).overlay shouldBe Overlay.Prompt("peers", 5, Some(0))
    press(up, Key.Up, Key.Down, Key.Down).overlay shouldBe Overlay.Prompt("", 0, None)
  }

  "peer <id>" should "open the one peer whose id starts so, and say when none or several do" taggedAs (UnitTest) in {
    val ids = peers(synced)
    val unique = ids.head
    val (opened, _) = run(synced, s"peer $unique")
    opened.view shouldBe TuiView.Peers
    opened.screen shouldBe Some(Screen.PeerDetail(unique))
    opened.selectedPeer shouldBe Some(unique)
    run(synced, "peer zzzz")._1.notice.get should include("no connected peer")
    run(synced, "peer")._1.notice.get should include("usage")
    val shared = ids.map(_.take(1)).groupBy(identity).find(_._2.size > 1).map(_._1)
    shared.foreach(prefix => run(synced, s"peer $prefix")._1.notice.get should include("type more"))
  }

  "quit, detach, help and glyphs" should "do at the prompt what their keys do" taggedAs (UnitTest) in {
    run(synced, "quit")._1.overlay shouldBe Overlay.Dialog(DialogKind.Quit)
    run(synced, "quit")._2 shouldBe empty // the dialog asks; the prompt stops nothing by itself
    run(synced, "detach")._2 shouldBe Seq(Effect.Detach)
    run(synced, "help")._1.helpOpen shouldBe true
    run(synced, "glyphs ascii")._1.glyphs shouldBe GlyphSet.Ascii
    run(synced, "glyphs")._1.notice.get should include("usage")
  }

  // ---- the quit dialog, pastes, read-only ---------------------------------------------------------------------------

  "The quit dialog" should "take y to stop, n or Esc to cancel, and nothing else" taggedAs (UnitTest) in {
    val asked = press(synced, Key.Char('q'))
    asked.overlay shouldBe Overlay.Dialog(DialogKind.Quit)
    Update(asked, Key.Char('y'), ctx)._2 shouldBe Seq(Effect.Quit)
    Update(asked, Key.Char('Y'), ctx)._2 shouldBe Seq(Effect.Quit)
    for key <- Seq(Key.Enter, Key.Tab, Key.Char('1'), Key.Char('d'), Key.Paste("y"), Key.Up) do
      Update(asked, key, ctx) shouldBe ((asked, Nil))
    for key <- Seq(Key.Char('n'), Key.Esc, Key.Backspace) do Update(asked, key, ctx)._1.overlay shouldBe Overlay.None
  }

  "Pasted text" should "open nothing, move nothing and confirm nothing (FR-009)" taggedAs (UnitTest) in {
    for s <- Seq(synced, press(synced, Key.Char('3')), press(synced, Key.Char('?')), press(synced, Key.Char('q'))) do
      Update(s, Key.Paste("q\ny2d:/"), ctx) shouldBe ((s, Nil))
  }

  "In read-only mode q" should "say why it does nothing, and the prompt's quit too (FR-053)" taggedAs (UnitTest) in {
    val readOnly = ctx.copy(readOnly = true)
    val (s, effects) = Update(synced, Key.Char('q'), readOnly)
    s.overlay shouldBe Overlay.None
    s.notice shouldBe Some(Update.ReadOnlyNotice)
    effects shouldBe empty
    val typing = press(synced, (Key.Char(':') +: typed("quit"))*)
    Update(typing, Key.Enter, readOnly)._1.overlay shouldBe Overlay.None
    // Detaching stops nothing: it stays.
    Update(synced, Key.Char('d'), readOnly)._2 shouldBe Seq(Effect.Detach)
  }

  "With Help open" should "q, r and d keep their meaning and any other key close it" taggedAs (UnitTest) in {
    val help = press(synced, Key.Char('?'))
    Update(help, Key.Char('r'), ctx) shouldBe ((help, Seq(Effect.Redraw)))
    Update(help, Key.Char('d'), ctx)._2 shouldBe Seq(Effect.Detach)
    Update(help, Key.Char('q'), ctx)._1.overlay shouldBe Overlay.Dialog(DialogKind.Quit)
    for key <- Seq(Key.Char('2'), Key.Enter, Key.Esc, Key.Down, Key.Char('x')) do
      val closed = Update(help, key, ctx)._1
      closed.helpOpen shouldBe false
      closed.view shouldBe synced.view
  }

  "p, g and r" should "pause, switch glyphs and ask for a redraw" taggedAs (UnitTest) in {
    press(synced, Key.Char('p')).motion shouldBe Motion.Paused
    press(synced, Key.Char('p'), Key.Char('p')).motion shouldBe Motion.Full
    press(synced, Key.Char('g')).glyphs should not be synced.glyphs
    Update(synced, Key.Char('r'), ctx)._2 shouldBe Seq(Effect.Redraw)
  }
