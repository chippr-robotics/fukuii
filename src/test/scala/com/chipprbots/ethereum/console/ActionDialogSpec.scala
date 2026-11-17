package com.chipprbots.ethereum.console

import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.console.ops.ActionRequest
import com.chipprbots.ethereum.console.ops.Actions
import com.chipprbots.ethereum.console.ops.ActionsView
import com.chipprbots.ethereum.console.ops.Preview
import com.chipprbots.ethereum.console.ops.RiskClass
import com.chipprbots.ethereum.testing.Tags.*

/** Confirmation by class (spec 029 FR-048 to FR-050, FR-053, T096, T098; contracts/operator.md): what opens a dialog,
  * what answers it, and what cannot.
  */
class ActionDialogSpec extends AnyFlatSpec with Matchers with OptionValues:

  private val ctx = UpdateCtx(page = 10, configuredMotion = Motion.Reduced)
  private val synced = TuiFixtures.syncedEtc()

  private def press(s: TuiState, keys: Key*): TuiState = keys.foldLeft(s)((state, k) => Update(state, k, ctx)._1)
  private def typed(text: String): Seq[Key] = text.map(Key.Char(_))

  /** What typing `line` at the prompt and pressing Enter gives. */
  private def run(s: TuiState, line: String, context: UpdateCtx = ctx): (TuiState, Seq[Effect]) =
    Update(press(s, (Key.Char(':') +: typed(line))*), Key.Enter, context)

  private def dialog(s: TuiState): DialogKind.Confirm = s.overlay match
    case Overlay.Dialog(c: DialogKind.Confirm) => c
    case other                                 => fail(s"no confirmation is open: $other")

  /** `key` closes the dialog of `request` and runs nothing: the attempt is said to be cancelled. */
  private def cancels(asked: TuiState, key: Key, request: ActionRequest): Unit =
    val (after, effects) = Update(asked, key, ctx)
    after.overlay shouldBe Overlay.None
    effects shouldBe Seq(Effect.CancelAction(request))

  private val maxPeers = ActionRequest(Actions.MaxPeers.name, Vector("n" -> "40"))
  private val dbCheck = ActionRequest(Actions.DbCheck.name)
  private val stopMining = ActionRequest(Actions.MiningStop.name)
  private val ready = Preview(Vector("Sets the limit on outgoing connections from 25 to 40."))

  /** `s` with the runner having read `preview` for `request`, as a frame merges it in. */
  private def previewed(s: TuiState, request: ActionRequest, preview: Preview = ready): TuiState =
    s.withActions(s.actions.copy(previews = s.actions.previews.updated(request, preview)))

  // ---- opening ------------------------------------------------------------------------------------------------------

  "An action typed at the prompt" should "open its dialog and ask for its preview, and run nothing" taggedAs (
    UnitTest
  ) in {
    val (asked, effects) = run(synced, "maxpeers 40")
    dialog(asked) shouldBe DialogKind.Confirm(maxPeers, RiskClass.Adjust, None)
    effects shouldBe Seq(Effect.Preview(maxPeers))
    // The dialog opens with Cancel in focus and nothing typed, whatever the class.
    dialog(asked).focus shouldBe DialogFocus.Cancel
    dialog(run(synced, "dbcheck")._1) shouldBe DialogKind.Confirm(dbCheck, RiskClass.Disrupt, None)
    dialog(run(synced, "mining stop")._1) shouldBe DialogKind.Confirm(stopMining, RiskClass.Halt, None)
  }

  it should "not be confirmable while its preview is being read" taggedAs (UnitTest) in {
    val reading = run(synced, "dbcheck")._1
    for key <- Seq(Key.Char('y'), Key.Enter, Key.Tab) do Update(reading, key, ctx) shouldBe ((reading, Nil))
    // Esc still closes it, and the attempt is said to be cancelled.
    cancels(reading, Key.Esc, dbCheck)
  }

  it should "name a connected peer by the start of its id, and ask for the whole id" taggedAs (UnitTest) in {
    val peer = synced.orderedPeers.head
    val prefix = peer.id.take(12)
    val (asked, effects) = run(synced, s"peer drop $prefix")
    val whole = ActionRequest(Actions.PeerDrop.name, Vector("id" -> peer.id))
    dialog(asked).request shouldBe whole
    effects shouldBe Seq(Effect.Preview(whole))
    // An id that fits no peer, or more than one, is said, and nothing is asked.
    val none = run(synced, "peer drop ffffffffffffffffffffffff")
    none._1.overlay shouldBe Overlay.None
    none._1.notice.value should include("no connected peer's id starts with")
    none._2 shouldBe empty
    // Trust is of a node, connected or not: a whole id can lose it.
    val gone = "ab" * 64
    dialog(run(synced, s"peer untrust $gone")._1).request shouldBe
      ActionRequest(Actions.PeerUntrust.name, Vector("id" -> gone))
    run(synced, s"peer trust $gone")._1.notice.value should include("no connected peer")
  }

  it should "say an action is already running instead of asking again" taggedAs (UnitTest) in {
    val busy = synced.withActions(
      ActionsView(running = Vector(ops.ActionActivity(dbCheck, TuiFixtures.Now, "reading")))
    )
    val (s, effects) = run(busy, "dbcheck")
    s.overlay shouldBe Overlay.None
    s.notice.value should include("already running")
    effects shouldBe empty
  }

  // ---- Adjust -------------------------------------------------------------------------------------------------------

  "An Adjust" should "open with Cancel in focus, so that Enter alone changes nothing" taggedAs (UnitTest) in {
    val asked = previewed(run(synced, "maxpeers 40")._1, maxPeers)
    dialog(asked).preview shouldBe Some(ready)
    cancels(asked, Key.Enter, maxPeers)
  }

  it should "be applied by moving to Apply and accepting it" taggedAs (UnitTest) in {
    val asked = previewed(run(synced, "maxpeers 40")._1, maxPeers)
    for move <- Seq(Key.Tab, Key.Right, Key.Left, Key.BackTab) do
      val onApply = press(asked, move)
      dialog(onApply).focus shouldBe DialogFocus.Apply
      val (after, effects) = Update(onApply, Key.Enter, ctx)
      after.overlay shouldBe Overlay.None
      effects shouldBe Seq(Effect.Confirm(maxPeers))
      // Moving again returns to Cancel.
      dialog(press(onApply, move)).focus shouldBe DialogFocus.Cancel
    // y is a Disrupt's answer, not an Adjust's.
    Update(asked, Key.Char('y'), ctx) shouldBe ((asked, Nil))
  }

  // ---- Disrupt ------------------------------------------------------------------------------------------------------

  "A Disrupt" should "run on y, cancel on n or Esc, and take nothing else" taggedAs (UnitTest) in {
    val asked = previewed(run(synced, "dbcheck")._1, dbCheck)
    for yes <- Seq('y', 'Y') do
      val (after, effects) = Update(asked, Key.Char(yes), ctx)
      after.overlay shouldBe Overlay.None
      effects shouldBe Seq(Effect.Confirm(dbCheck))
    for no <- Seq(Key.Char('n'), Key.Char('N'), Key.Esc, Key.Backspace) do cancels(asked, no, dbCheck)
    for other <- Seq(Key.Enter, Key.Tab, Key.Char('x'), Key.Char('1'), Key.Up) do
      Update(asked, other, ctx) shouldBe ((asked, Nil))
  }

  // ---- Halt ---------------------------------------------------------------------------------------------------------

  "A Halt" should "need its word typed and Enter, and nothing shorter" taggedAs (UnitTest) in {
    val asked = previewed(run(synced, "mining stop")._1, stopMining)
    Update(asked, Key.Char('y'), ctx)._2 shouldBe empty
    Update(asked, Key.Enter, ctx) shouldBe ((asked, Nil))
    Update(press(asked, typed("sto")*), Key.Enter, ctx)._2 shouldBe empty
    Update(press(asked, typed("stopp")*), Key.Enter, ctx)._2 shouldBe empty
    dialog(press(asked, typed("st")*)).typed shouldBe "st"
    val (after, effects) = Update(press(asked, typed("stop")*), Key.Enter, ctx)
    after.overlay shouldBe Overlay.None
    effects shouldBe Seq(Effect.Confirm(stopMining))
    // Only stopping the node itself also ends the dashboard's loop.
    effects should not contain Effect.Quit
  }

  // ---- what never confirms ------------------------------------------------------------------------------------------

  "Pasted text" should "confirm nothing in any dialog, whatever it holds (FR-009)" taggedAs (UnitTest) in {
    val dialogs = Seq(
      previewed(run(synced, "maxpeers 40")._1, maxPeers),
      press(previewed(run(synced, "maxpeers 40")._1, maxPeers), Key.Tab),
      previewed(run(synced, "dbcheck")._1, dbCheck),
      previewed(run(synced, "mining stop")._1, stopMining),
      press(synced, Key.Char('q'))
    )
    for d <- dialogs; text <- Seq("y", "stop", "stop\n", "\ty\n", "n") do
      Update(d, Key.Paste(text), ctx) shouldBe ((d, Nil))
  }

  "An action that cannot be run now" should "say why and be confirmed by nothing" taggedAs (UnitTest) in {
    val refused = Preview(Vector.empty, Some("the built-in miner is already stopped"))
    val asked = previewed(run(synced, "mining stop")._1, stopMining, refused)
    Update(press(asked, typed("stop")*), Key.Enter, ctx)._2 shouldBe empty
    val disrupt = previewed(run(synced, "dbcheck")._1, dbCheck, Preview(Vector.empty, Some("not meaningful here")))
    Update(disrupt, Key.Char('y'), ctx) shouldBe ((disrupt, Nil))
    widgets.DialogBox.confirm(dialog(disrupt)).body.last shouldBe "Cannot be run now: not meaningful here"
    widgets.DialogBox.confirm(dialog(disrupt)).keys shouldBe "Esc  close"
  }

  "A dialog too small to show its preview" should "not be confirmable: what it says must be read first" taggedAs (
    UnitTest
  ) in {
    val long = Preview((1 to 12).map(i => s"Sentence $i of what this will change.").toVector)
    val asked = previewed(run(synced, "dbcheck")._1, dbCheck, long)
    val small = ctx.copy(bodyCols = 80, bodyRows = 10)
    val large = ctx.copy(bodyCols = 80, bodyRows = 40)
    widgets.DialogBox.fits(dialog(asked), 80, 10) shouldBe false
    widgets.DialogBox.fits(dialog(asked), 80, 40) shouldBe true
    Update(asked, Key.Char('y'), small) shouldBe ((asked, Nil))
    Update(asked, Key.Char('y'), large)._2 shouldBe Seq(Effect.Confirm(dbCheck))
    // It says so, and Esc still cancels.
    widgets.DialogBox.confirm(dialog(asked), fits = false).keys should include("enlarge the terminal")
    Update(asked, Key.Esc, small)._2 shouldBe Seq(Effect.CancelAction(dbCheck))
  }

  // ---- a preview that stops being true (FR-050) ---------------------------------------------------------------------

  "A preview that changes while the dialog is open" should "take the old one's place, and ask again from the start" taggedAs (
    UnitTest
  ) in {
    val onApply = press(previewed(run(synced, "maxpeers 40")._1, maxPeers), Key.Tab)
    dialog(onApply).focus shouldBe DialogFocus.Apply
    dialog(onApply).changed shouldBe false
    // The same preview read again changes nothing: the operator's place is kept.
    previewed(onApply, maxPeers) shouldBe onApply
    val later = Preview(Vector("Sets the limit on outgoing connections from 30 to 40."))
    val refreshed = previewed(onApply, maxPeers, later)
    dialog(refreshed) shouldBe DialogKind.Confirm(
      maxPeers,
      RiskClass.Adjust,
      Some(later),
      DialogFocus.Cancel,
      "",
      changed = true
    )
    widgets.DialogBox.confirm(dialog(refreshed)).body.head shouldBe
      "What this said has changed since it was first shown: read it again, then answer."
    // The word half typed is gone too.
    val typing = press(previewed(run(synced, "mining stop")._1, stopMining), typed("sto")*)
    dialog(previewed(typing, stopMining, later)).typed shouldBe ""
    // The first preview to arrive is not a change.
    dialog(previewed(run(synced, "dbcheck")._1, dbCheck)).changed shouldBe false
  }

  // The figures of a preview move every second (a peer's age, the best block): they are shown, and are no change.
  private val trusted = synced.orderedPeers.find(!_.incoming).value
  private val trust = ActionRequest(Actions.PeerTrust.name, Vector("id" -> trusted.id))
  private val trustRead = Preview(
    Vector("Trusts 9e37b952…, 203.0.113.11:30303, we dialled it.", "Trusted now: no. After: yes."),
    None,
    Vector("Connected 4h 4m, score 0.58.")
  )
  private val ChangedSentence = "What this said has changed since it was first shown: read it again, then answer."

  /** A peer-trust dialog with its preview read and the focus moved to Apply. */
  private def trustOnApply: TuiState =
    val (opened, effects) = run(synced, s"peer trust ${trusted.id.take(Update.PeerIdChars)}")
    dialog(opened) shouldBe DialogKind.Confirm(trust, RiskClass.Adjust, None)
    effects shouldBe Seq(Effect.Preview(trust))
    val onApply = press(previewed(opened, trust, trustRead), Key.Tab)
    dialog(onApply) shouldBe DialogKind.Confirm(trust, RiskClass.Adjust, Some(trustRead), DialogFocus.Apply)
    onApply

  "A preview whose figures alone moved" should "be shown, and leave the dialog as the operator left it" taggedAs (
    UnitTest
  ) in {
    val onApply = trustOnApply
    val ticked = trustRead.copy(figures = Vector("Connected 4h 5m, score 0.61."))
    trustRead.same(ticked) shouldBe true
    val moved = previewed(onApply, trust, ticked)
    dialog(moved) shouldBe
      DialogKind.Confirm(trust, RiskClass.Adjust, Some(ticked), DialogFocus.Apply, "", changed = false)
    // The figure on screen is the new one, under the lines, and nothing says that anything changed.
    val body = widgets.DialogBox.confirm(dialog(moved)).body
    body.take(3) shouldBe trustRead.lines :+ "Connected 4h 5m, score 0.61."
    body should not contain ChangedSentence
    // Enter applies it: the operator had moved to Apply and nothing took that back.
    val (after, effects) = Update(moved, Key.Enter, ctx)
    after.overlay shouldBe Overlay.None
    effects shouldBe Seq(Effect.Confirm(trust))
    // However often the figures move.
    val often = (1 to 20).foldLeft(onApply)((s, i) =>
      previewed(s, trust, trustRead.copy(figures = Vector(s"Connected 4h ${4 + i}m, score 0.58.")))
    )
    dialog(often).focus shouldBe DialogFocus.Apply
    dialog(often).changed shouldBe false
    Update(often, Key.Enter, ctx)._2 shouldBe Seq(Effect.Confirm(trust))
    // The word half typed for a Halt stays as well.
    val halt = Preview(Vector("The built-in miner stops."), None, Vector("1.20 MH/s now."))
    val typing = press(previewed(run(synced, "mining stop")._1, stopMining, halt), typed("sto")*)
    val slower = previewed(typing, stopMining, halt.copy(figures = Vector("1.10 MH/s now.")))
    dialog(slower).typed shouldBe "sto"
    dialog(slower).changed shouldBe false
    dialog(slower).preview shouldBe Some(halt.copy(figures = Vector("1.10 MH/s now.")))
    Update(press(slower, Key.Char('p')), Key.Enter, ctx)._2 shouldBe Seq(Effect.Confirm(stopMining))
  }

  "A preview whose lines changed" should "take the focus back to Cancel and say that it changed" taggedAs (UnitTest) in {
    val onApply = trustOnApply
    val other = trustRead.copy(lines = trustRead.lines.updated(1, "Trusted now: yes. After: yes."))
    trustRead.same(other) shouldBe false
    val reset = previewed(onApply, trust, other)
    dialog(reset) shouldBe
      DialogKind.Confirm(trust, RiskClass.Adjust, Some(other), DialogFocus.Cancel, "", changed = true)
    widgets.DialogBox.confirm(dialog(reset)).body.head shouldBe ChangedSentence
    // Enter, on its way to Apply when the preview changed, now cancels.
    cancels(reset, Key.Enter, trust)
    // The same with figures that moved too, and with a preview that became a refusal.
    val both = previewed(onApply, trust, other.copy(figures = Vector("Connected 4h 5m, score 0.58.")))
    dialog(both).focus shouldBe DialogFocus.Cancel
    dialog(both).changed shouldBe true
    val refused =
      previewed(onApply, trust, Preview(Vector.empty, Some("no connected peer has the id 9e37b952… any more")))
    dialog(refused).focus shouldBe DialogFocus.Cancel
    dialog(refused).changed shouldBe true
    Update(press(refused, Key.Tab), Key.Enter, ctx)._2 shouldBe empty
    // Said to have changed, it stays so while only its figures move after.
    val after = previewed(reset, trust, other.copy(figures = Vector("Connected 4h 6m, score 0.58.")))
    dialog(after).changed shouldBe true
    dialog(after).focus shouldBe DialogFocus.Cancel
  }

  "A Disrupt whose preview changed" should "take a first y as having read it, and run on the second" taggedAs (
    UnitTest
  ) in {
    val read = Preview(
      Vector("Reads the number-to-hash entry and the header of every 1000th block up to the best block."),
      None,
      Vector("The best block is 21884102 now: about 43770 entries.")
    )
    val asked = previewed(run(synced, "dbcheck")._1, dbCheck, read)
    // A figure that moved is no change: y runs at once.
    val ticked =
      previewed(asked, dbCheck, read.copy(figures = Vector("The best block is 21884103 now: about 43770 entries.")))
    dialog(ticked).changed shouldBe false
    Update(ticked, Key.Char('y'), ctx)._2 shouldBe Seq(Effect.Confirm(dbCheck))
    // Its lines changed: the y that was on its way confirms nothing.
    val other = read.copy(lines =
      Vector("Reads the number-to-hash entry and the header of every 500th block up to the best block.")
    )
    val changed = previewed(asked, dbCheck, other)
    dialog(changed).changed shouldBe true
    for yes <- Seq('y', 'Y') do
      val (once, none) = Update(changed, Key.Char(yes), ctx)
      none shouldBe empty
      none should not contain Effect.Confirm(dbCheck)
      dialog(once) shouldBe dialog(changed).copy(changed = false)
      widgets.DialogBox.confirm(dialog(once)).body should not contain ChangedSentence
      val (after, effects) = Update(once, Key.Char(yes), ctx)
      after.overlay shouldBe Overlay.None
      effects shouldBe Seq(Effect.Confirm(dbCheck))
    // n and Esc cancel it as before, changed or not.
    cancels(changed, Key.Char('n'), dbCheck)
    cancels(changed, Key.Esc, dbCheck)
    // It changes again after the first y: that y is taken back.
    val again = previewed(Update(changed, Key.Char('y'), ctx)._1, dbCheck, read)
    dialog(again).changed shouldBe true
    Update(again, Key.Char('y'), ctx)._2 shouldBe empty
  }

  "A dialog opened again" should "start with no preview, whatever an earlier one for the same request left" taggedAs (
    UnitTest
  ) in {
    val stale = synced.withActions(synced.actions.copy(previews = Map(maxPeers -> ready, dbCheck -> ready)))
    stale.actions.previews.get(maxPeers) shouldBe Some(ready)
    val (asked, effects) = run(stale, "maxpeers 40")
    dialog(asked) shouldBe DialogKind.Confirm(maxPeers, RiskClass.Adjust, None)
    effects shouldBe Seq(Effect.Preview(maxPeers))
    // Nothing confirms it until its own preview is read.
    Update(press(asked, Key.Tab), Key.Enter, ctx)._2 shouldBe empty
    val (disrupt, asking) = run(stale, "dbcheck")
    dialog(disrupt) shouldBe DialogKind.Confirm(dbCheck, RiskClass.Disrupt, None)
    asking shouldBe Seq(Effect.Preview(dbCheck))
    Update(disrupt, Key.Char('y'), ctx) shouldBe ((disrupt, Nil))
  }

  // ---- read-only (FR-053) -------------------------------------------------------------------------------------------

  "A read-only dashboard" should "open no dialog above Observe, say so, and hand the attempt over for the audit log" taggedAs (
    UnitTest
  ) in {
    val readOnly = ctx.copy(readOnly = true)
    for (line, request) <- Seq("maxpeers 40" -> maxPeers, "dbcheck" -> dbCheck, "mining stop" -> stopMining) do
      val (s, effects) = run(synced, line, readOnly)
      withClue(s"$line: ") {
        s.overlay shouldBe Overlay.None
        s.notice shouldBe Some(Update.readOnlyNotice(request.action))
        effects shouldBe Seq(Effect.Confirm(request))
      }
    // The Observe actions run: they change nothing.
    val (shown, effects) = run(synced, "tunables", readOnly)
    shown.screen shouldBe Some(Screen.Tunables)
    effects shouldBe Seq(Effect.Confirm(ActionRequest(Actions.Tunables.name)))
  }

  // ---- the Observe screens ------------------------------------------------------------------------------------------

  "An Observe action" should "open its screen and run at once, with no dialog" taggedAs (UnitTest) in {
    for (line, screen, action) <- Seq(
        ("config rpc", Screen.Config("rpc", 0), Actions.Config),
        ("tunables", Screen.Tunables, Actions.Tunables),
        ("test-rpc", Screen.TestRpc, Actions.TestRpc),
        ("net", Screen.Net, Actions.Net)
      )
    do
      val (s, effects) = run(synced, line)
      withClue(s"$line: ") {
        s.overlay shouldBe Overlay.None
        s.screen shouldBe Some(screen)
        effects shouldBe Seq(Effect.Confirm(ActionRequest(action.name)))
        // `r` reads it again; Esc returns to the view.
        Update(s, Key.Char('r'), ctx)._2 shouldBe Seq(Effect.Confirm(ActionRequest(action.name)), Effect.Redraw)
        press(s, Key.Esc).screen shouldBe None
      }
    // The dependencies are what the dashboard already knows: nothing is read for them.
    val (deps, none) = run(synced, "deps")
    deps.screen shouldBe Some(Screen.Deps)
    none shouldBe empty
  }

  // ---- the Ops view and `a` -----------------------------------------------------------------------------------------

  "The Ops view" should "select an action with the arrows and type its name at the prompt on Enter" taggedAs (UnitTest) in {
    val ops = press(synced, Key.Char('9'))
    ops.selectedAction shouldBe None
    val first = press(ops, Key.Down)
    first.selectedAction shouldBe Some(Actions.all.head.name)
    val last = press(ops, Key.End)
    last.selectedAction shouldBe Some(Actions.Quit.name)
    val (prompt, effects) = Update(last, Key.Enter, ctx)
    prompt.overlay shouldBe Overlay.Prompt("quit ", 5, None)
    effects shouldBe empty // nothing runs before the prompt's Enter, and then its dialog
  }

  "a" should "type the action that goes with the selected peer, and run nothing" taggedAs (UnitTest) in {
    val selected = press(synced, Key.Char('3'), Key.Down)
    val id = selected.orderedPeers.head.id
    val (prompt, effects) = Update(selected, Key.Char('a'), ctx)
    prompt.overlay shouldBe Overlay.Prompt(s"peer drop ${id.take(Update.PeerIdChars)}", 10 + Update.PeerIdChars, None)
    effects shouldBe empty
    // With nothing selected, and on a view with no action of its own, it does nothing.
    Update(press(synced, Key.Char('3')), Key.Char('a'), ctx)._1.overlay shouldBe Overlay.None
    Update(synced, Key.Char('a'), ctx)._1.overlay shouldBe Overlay.None
  }
