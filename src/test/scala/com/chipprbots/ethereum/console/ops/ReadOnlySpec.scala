package com.chipprbots.ethereum.console.ops

import scala.collection.mutable

import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.console.Effect
import com.chipprbots.ethereum.console.Key
import com.chipprbots.ethereum.console.LookupQuery
import com.chipprbots.ethereum.console.Motion
import com.chipprbots.ethereum.console.Overlay
import com.chipprbots.ethereum.console.Screen
import com.chipprbots.ethereum.console.TuiConfig
import com.chipprbots.ethereum.console.TuiFixtures
import com.chipprbots.ethereum.console.TuiRenderer
import com.chipprbots.ethereum.console.TuiState
import com.chipprbots.ethereum.console.TuiView
import com.chipprbots.ethereum.console.Update
import com.chipprbots.ethereum.console.UpdateCtx
import com.chipprbots.ethereum.testing.Tags.*

/** A read-only dashboard (spec 029 FR-053, T098): the runner refuses every action above Observe and writes the attempt
  * to the audit log; every Observe action still runs; a key or a command that asks for one opens no dialog and says
  * why; and the Ops view says that the dashboard is read-only.
  */
class ReadOnlySpec extends AnyFlatSpec with Matchers with OptionValues:

  private val above: Vector[ActionSpec] = Actions.all.filter(_.risk != RiskClass.Observe)
  private val observe: Vector[ActionSpec] = Actions.all.filter(_.risk == RiskClass.Observe)

  // ---- the runner ---------------------------------------------------------------------------------------------------

  /** A runner that has a handler for every action of the registry, and does at once what it is asked. */
  private class Setup(readOnly: Boolean):
    val audit: mutable.Buffer[String] = mutable.Buffer.empty
    val previewed: mutable.Buffer[String] = mutable.Buffer.empty
    val ran: mutable.Buffer[String] = mutable.Buffer.empty
    private val handler = ActionHandler(
      request =>
        previewed += request.action
        Preview(Vector("it will change"))
      ,
      (request, _) =>
        ran += request.action
        Right("it is done")
    )
    private val inline = new SameThread
    val runner = new ActionRunner(
      Actions.all,
      Actions.all.map(_.name -> handler).toMap,
      readOnly = readOnly,
      clock = TuiFixtures.clock,
      audit = audit += _,
      previewThread = inline,
      actionThread = inline,
      observeThread = inline
    )

  "The registry" should "have actions of every class: the ones a read-only dashboard runs and the ones it does not" taggedAs (
    UnitTest
  ) in {
    observe.map(_.name) shouldBe Vector("config", "tunables", "deps", "test-rpc", "net", "art", "lookup")
    above.map(_.risk).toSet shouldBe Set(RiskClass.Adjust, RiskClass.Disrupt, RiskClass.Halt)
    above should have size 18
  }

  "A read-only runner" should "refuse every action above Observe with the one reason, and run none" taggedAs (UnitTest) in {
    val s = new Setup(readOnly = true)
    for spec <- above do
      withClue(s"${spec.name}: ") {
        s.runner.refusal(spec.name) shouldBe Some(ActionRunner.ReadOnly)
        s.audit.clear()
        val request = ActionRequest(spec.name)
        s.runner.confirmed(request)
        // One audit line an attempt: what was asked, and that it was refused, and why.
        s.audit shouldBe Seq(
          s"action=${spec.name} params=- outcome=refused: the dashboard is read-only (fukuii.tui.read-only) by=tui"
        )
        s.runner.view.history.last.request shouldBe request
        s.runner.view.history.last.outcome shouldBe ActionOutcome.Refused(ActionRunner.ReadOnly)
        s.runner.view.running shouldBe empty
      }
    s.ran shouldBe empty
    s.runner.view.history should have size above.size.toLong
    ActionRunner.ReadOnly shouldBe "the dashboard is read-only (fukuii.tui.read-only)"
  }

  it should "audit the parameters of the attempt it refused" taggedAs (UnitTest) in {
    val s = new Setup(readOnly = true)
    s.runner.confirmed(ActionRequest(Actions.PeerBlock.name, Vector("address" -> "203.0.113.9")))
    s.runner.confirmed(ActionRequest(Actions.LogLevel.name, Vector("level" -> "DEBUG", "logger" -> "ROOT")))
    s.audit shouldBe Seq(
      "action=peer block params=address=203.0.113.9 outcome=refused: the dashboard is read-only (fukuii.tui.read-only) by=tui",
      "action=loglevel params=level=DEBUG,logger=ROOT outcome=refused: the dashboard is read-only (fukuii.tui.read-only) by=tui"
    )
  }

  it should "give the same reason as the preview of each, without asking the node" taggedAs (UnitTest) in {
    val s = new Setup(readOnly = true)
    for spec <- above do
      val request = ActionRequest(spec.name)
      s.runner.preview(request)
      s.runner.view.previews(request) shouldBe Preview(Vector.empty, Some(ActionRunner.ReadOnly))
    s.previewed shouldBe empty
    // A preview is not an attempt: nothing is written for it.
    s.audit shouldBe empty
  }

  it should "say of each action above Observe, from the start, why it cannot run" taggedAs (UnitTest) in {
    val s = new Setup(readOnly = true)
    s.runner.view.unavailable shouldBe above.map(_.name -> ActionRunner.ReadOnly).toMap
  }

  it should "still run every Observe action" taggedAs (UnitTest) in {
    val s = new Setup(readOnly = true)
    for spec <- observe do
      withClue(s"${spec.name}: ") {
        s.runner.refusal(spec.name) shouldBe None
        s.audit.clear()
        s.runner.confirmed(ActionRequest(spec.name))
        s.ran.last shouldBe spec.name
        s.audit shouldBe Seq(s"action=${spec.name} params=- outcome=confirmed by=tui")
        s.runner.view.reports(spec.name).lines shouldBe Vector("it is done")
      }
    s.ran shouldBe observe.map(_.name)
  }

  "A runner that is not read-only" should "refuse none of them" taggedAs (UnitTest) in {
    val s = new Setup(readOnly = false)
    for spec <- Actions.all do s.runner.refusal(spec.name) shouldBe None
    s.runner.view.unavailable shouldBe empty
    s.runner.confirmed(ActionRequest(Actions.MaxPeers.name, Vector("n" -> "40")))
    s.ran shouldBe Seq("maxpeers")
  }

  // ---- the keys and the prompt --------------------------------------------------------------------------------------

  private val synced = TuiFixtures.syncedEtc()
  private val peer = synced.orderedPeers.head
  private val unlocked = UpdateCtx(page = 10, configuredMotion = Motion.Reduced)
  private val readOnly = unlocked.copy(readOnly = true)

  /** What typing `line` at the prompt and pressing Enter gives. */
  private def entered(line: String, ctx: UpdateCtx, from: TuiState = synced): (TuiState, Seq[Effect]) =
    val typed = (Key.Char(':') +: line.map(Key.Char(_))).foldLeft(from)((s, k) => Update(s, k, ctx)._1)
    Update(typed, Key.Enter, ctx)

  private def request(spec: ActionSpec, params: (String, String)*): ActionRequest =
    ActionRequest(spec.name, params.toVector)

  /** A command line for every action above Observe, with the request it asks for. */
  private val asked: Vector[(String, ActionRequest)] = Vector(
    "loglevel debug" -> request(Actions.LogLevel, "level" -> "DEBUG", "logger" -> "ROOT"),
    "maxpeers 40" -> request(Actions.MaxPeers, "n" -> "40"),
    s"peer trust ${peer.id.take(16)}" -> request(Actions.PeerTrust, "id" -> peer.id),
    s"peer untrust ${peer.id.take(16)}" -> request(Actions.PeerUntrust, "id" -> peer.id),
    "threshold stall-after 5m" -> request(Actions.Threshold, "name" -> "stall-after", "value" -> "5m"),
    s"peer add enode://${"ab12cd34" * 16}@198.51.100.7:30303" ->
      request(Actions.PeerAdd, "enode" -> s"enode://${"ab12cd34" * 16}@198.51.100.7:30303"),
    s"peer drop ${peer.id.take(16)}" -> request(Actions.PeerDrop, "id" -> peer.id),
    "peer block 203.0.113.9" -> request(Actions.PeerBlock, "address" -> "203.0.113.9"),
    "peer unblock 203.0.113.9" -> request(Actions.PeerUnblock, "address" -> "203.0.113.9"),
    "mining start" -> request(Actions.MiningStart),
    "coinbase 0x1000000000000000000000000000000000000001" ->
      request(Actions.Coinbase, "address" -> "0x1000000000000000000000000000000000000001"),
    "rpc stop http" -> request(Actions.RpcStop, "transport" -> "http"),
    "rpc start ws" -> request(Actions.RpcStart, "transport" -> "ws"),
    "dbcheck" -> request(Actions.DbCheck),
    "detect-ip" -> request(Actions.DetectIp),
    "bundle" -> request(Actions.Bundle),
    "mining stop" -> request(Actions.MiningStop),
    "quit" -> request(Actions.Quit)
  )

  "The commands tried here" should "be one for every action above Observe" taggedAs (UnitTest) in {
    asked.map(_._2.action) shouldBe above.map(_.name)
  }

  "A read-only dashboard" should "open no dialog for an action above Observe, say why, and hand the attempt over to be audited" taggedAs (
    UnitTest
  ) in {
    for (line, wanted) <- asked do
      withClue(s"'$line': ") {
        val (after, effects) = entered(line, readOnly)
        after.overlay shouldBe Overlay.None
        after.confirming shouldBe None
        after.notice shouldBe Some(
          if wanted.action == "quit" then Update.ReadOnlyNotice else Update.readOnlyNotice(wanted.action)
        )
        // No preview is asked for, and nothing else: only the attempt, for the runner to refuse in the audit log.
        effects shouldBe Seq(Effect.Confirm(wanted))
        after.screens shouldBe synced.screens
        after.view shouldBe synced.view
      }
  }

  it should "say the notice in words that name the setting" taggedAs (UnitTest) in {
    Update.readOnlyNotice("maxpeers") shouldBe "read-only: the TUI cannot run 'maxpeers' (fukuii.tui.read-only)"
    Update.ReadOnlyNotice shouldBe "read-only: the TUI cannot stop the node; Ctrl-C does"
  }

  it should "open the same commands' dialogs when it is not read-only" taggedAs (UnitTest) in {
    for (line, wanted) <- asked do
      withClue(s"'$line': ") {
        val (after, effects) = entered(line, unlocked)
        after.confirming shouldBe Some(wanted)
        after.notice shouldBe None
        effects should not contain Effect.Confirm(wanted)
      }
  }

  it should "not offer to stop the node on q, from any view or from Help" taggedAs (UnitTest) in {
    for state <- TuiView.values.toSeq.map(synced.withView) :+ synced.openHelp do
      val (after, effects) = Update(state, Key.Char('q'), readOnly)
      after.overlay shouldBe Overlay.None
      after.notice shouldBe Some(Update.ReadOnlyNotice)
      effects shouldBe Seq(Effect.Confirm(TuiFixtures.Quit))
      effects should not contain Effect.Quit
  }

  it should "not put an action at the prompt and then run it: Enter on the Ops view's row is refused the same" taggedAs (
    UnitTest
  ) in {
    val onRow = synced.withView(TuiView.Ops).copy(selectedAction = Some(Actions.DbCheck.name))
    // `a` types the action's name; nothing has run, and nothing is refused, until Enter.
    val (typed, none) = Update(onRow, Key.Char('a'), readOnly)
    typed.overlay shouldBe Overlay.Prompt("dbcheck ", 8, None)
    none shouldBe empty
    val (after, effects) = Update(typed, Key.Enter, readOnly)
    after.overlay shouldBe Overlay.None
    after.notice shouldBe Some(Update.readOnlyNotice("dbcheck"))
    effects shouldBe Seq(Effect.Confirm(ActionRequest(Actions.DbCheck.name)))
  }

  it should "still open every Observe action's screen, and read what it shows" taggedAs (UnitTest) in {
    val shows = Seq(
      "config" -> (Screen.Config("", 0), Seq(Effect.Confirm(ActionRequest(Actions.Config.name)))),
      "config rpc" -> (Screen.Config("rpc", 0), Seq(Effect.Confirm(ActionRequest(Actions.Config.name)))),
      "tunables" -> (Screen.Tunables, Seq(Effect.Confirm(ActionRequest(Actions.Tunables.name)))),
      "test-rpc" -> (Screen.TestRpc, Seq(Effect.Confirm(ActionRequest(Actions.TestRpc.name)))),
      "net" -> (Screen.Net, Seq(Effect.Confirm(ActionRequest(Actions.Net.name)))),
      // What the dashboard already reads: nothing is asked of the runner.
      "deps" -> (Screen.Deps, Nil),
      "art" -> (Screen.Art(0), Nil),
      "block latest" -> (Screen.Explore(LookupQuery.LatestBlock, 0), Seq(Effect.Lookup(LookupQuery.LatestBlock)))
    )
    for (line, (screen, effects)) <- shows do
      withClue(s"'$line': ") {
        val (after, did) = entered(line, readOnly)
        after.screens shouldBe List(screen)
        after.notice shouldBe None
        did shouldBe effects
        // The same as on a dashboard that is not read-only.
        entered(line, unlocked) shouldBe ((after, did))
      }
  }

  // ---- the Ops view -------------------------------------------------------------------------------------------------

  private val ReadOnlyMode =
    "READ-ONLY: only the Observe actions run (fukuii.tui.read-only); Ctrl-C still stops the node"

  private def opsView(config: TuiConfig, state: TuiState = synced, cols: Int = 120): Seq[String] =
    TuiRenderer(config).render(state.withView(TuiView.Ops), cols, 40).map(_.toString)

  "The Ops view" should "say that the dashboard is read-only, and what still runs" taggedAs (UnitTest) in {
    val frame = opsView(TuiConfig.default.copy(readOnly = true))
    val mode = frame.filter(_.contains("READ-ONLY"))
    mode should have size 1
    mode.head should include("Mode")
    mode.head should include("READ-ONLY: only the Observe actions run (fukuii.tui.read-only)")
    (frame.mkString("\n") should not).include("actions allowed")
    // Where there is room for it, the whole of it.
    opsView(TuiConfig.default.copy(readOnly = true), cols = 160).filter(_.contains(ReadOnlyMode)) should have size 1
  }

  it should "say that actions are allowed when it is not" taggedAs (UnitTest) in {
    val frame = opsView(TuiConfig.default).mkString("\n")
    frame should include("actions allowed")
    (frame should not).include("READ-ONLY")
  }

  it should "say of each action above Observe that it cannot run, once the runner has said so" taggedAs (UnitTest) in {
    val refused = new Setup(readOnly = true).runner.view
    val frame = TuiRenderer(TuiConfig.default.copy(readOnly = true))
      .render(synced.withView(TuiView.Ops).withActions(refused), 160, 60)
      .map(_.toString)
    val rows = frame.filter(_.contains("the dashboard is read-only (fukuii.tui.read-only)"))
    rows should have size above.size.toLong
    // The Observe actions say what they show, as ever.
    frame.mkString("\n") should include(Actions.Config.changes)
    frame.mkString("\n") should include(Actions.Net.changes)
  }
