package com.chipprbots.ethereum.console.ops

import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit

import scala.collection.mutable

import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.console.TuiFixtures
import com.chipprbots.ethereum.testing.Tags.*

/** The action runner (spec 029 FR-051, FR-053, FR-057, T101): what runs, what is refused, what the audit log says of
  * each attempt, and that nothing runs on the caller's thread. Its threads are queues the test steps, so every order of
  * events is the one the test chose.
  */
class ActionRunnerSpec extends AnyFlatSpec with Matchers with OptionValues:

  /** An executor that keeps what it is given until the test runs it. */
  private class Stepped extends AbstractExecutorService:
    val queued: mutable.Queue[Runnable] = mutable.Queue.empty
    def execute(task: Runnable): Unit = queued.enqueue(task)
    def step(): Unit = queued.dequeue().run()
    def drain(): Unit = while queued.nonEmpty do step()
    def shutdown(): Unit = ()
    def shutdownNow(): java.util.List[Runnable] = java.util.List.of()
    def isShutdown: Boolean = false
    def isTerminated: Boolean = false
    def awaitTermination(timeout: Long, unit: TimeUnit): Boolean = true

  private class Setup(readOnly: Boolean = false, unavailable: Map[String, String] = Map.empty):
    val previews = new Stepped
    val actions = new Stepped
    val observes = new Stepped
    val audit: mutable.Buffer[String] = mutable.Buffer.empty
    val ran: mutable.Buffer[ActionRequest] = mutable.Buffer.empty
    var result: Either[String, String] = Right("it is done")
    var previewed: Preview = Preview(Vector("it will change"))
    // How often the handler was asked for a preview, and what its `run` throws, if it does.
    var previewsRead = 0
    var thrown: Option[Throwable] = None
    private val handler = ActionHandler(
      _ =>
        previewsRead += 1
        previewed
      ,
      (request, progress) =>
        ran += request
        progress("half way")
        thrown.foreach(e => throw e)
        result
    )
    val runner = new ActionRunner(
      Actions.all,
      Map(
        Actions.MaxPeers.name -> handler,
        Actions.DbCheck.name -> handler,
        Actions.Net.name -> handler,
        Actions.PeerDrop.name -> ActionHandler(
          _ => throw new IllegalStateException("no peers"),
          (_, _) => throw new IllegalStateException("gone\nsecond line")
        )
      ),
      unavailable,
      readOnly,
      TuiFixtures.clock,
      audit += _,
      previews,
      actions,
      observes
    )

  private val maxPeers = ActionRequest(Actions.MaxPeers.name, Vector("n" -> "40"))
  private val dbCheck = ActionRequest(Actions.DbCheck.name)
  private val net = ActionRequest(Actions.Net.name)

  "A preview" should "be read off the caller's thread and published when it is read" taggedAs (UnitTest) in {
    val s = new Setup
    s.runner.preview(maxPeers)
    s.runner.view.previews shouldBe empty // nothing ran on this thread
    s.previews.step()
    s.runner.view.previews shouldBe Map(maxPeers -> Preview(Vector("it will change")))
    // Asked again, it is read again: what the dialog shows is what the node says now.
    s.previewed = Preview(Vector("it will change otherwise"))
    s.runner.preview(maxPeers)
    s.previews.step()
    s.runner.view.previews(maxPeers) shouldBe Preview(Vector("it will change otherwise"))
    s.runner.forget(maxPeers)
    s.runner.view.previews shouldBe empty
    s.audit shouldBe empty // a preview is not an attempt
  }

  it should "say why when it cannot be read, and never fail the runner" taggedAs (UnitTest) in {
    val s = new Setup
    val drop = ActionRequest(Actions.PeerDrop.name, Vector("id" -> "ab"))
    s.runner.preview(drop)
    s.previews.step()
    s.runner.view.previews(drop).refusal.value should include("the preview could not be read: no peers")
  }

  it should "not be published when its dialog closed while it was read" taggedAs (UnitTest) in {
    val s = new Setup
    s.runner.preview(maxPeers)
    s.runner.forget(maxPeers)
    s.previews.step()
    s.previewsRead shouldBe 1
    s.runner.view.previews shouldBe empty
    // Cancelling and confirming close the dialog as well.
    s.runner.preview(maxPeers)
    s.runner.cancelled(maxPeers)
    s.previews.step()
    s.runner.view.previews shouldBe empty
    s.runner.preview(maxPeers)
    s.runner.confirmed(maxPeers)
    s.previews.step()
    s.runner.view.previews shouldBe empty
    s.actions.drain()
    // A dialog opened for it again is read for, and gets its preview.
    s.runner.preview(maxPeers)
    s.previews.step()
    s.runner.view.previews shouldBe Map(maxPeers -> Preview(Vector("it will change")))
    s.previews.queued shouldBe empty
  }

  it should "be read once at a time for a request, however often it is asked for meanwhile" taggedAs (UnitTest) in {
    val s = new Setup
    s.runner.preview(maxPeers)
    s.runner.preview(maxPeers)
    s.runner.preview(maxPeers)
    s.previews.queued should have size 1
    // Another request is not this one: it is read for itself.
    val other = maxPeers.copy(params = Vector("n" -> "41"))
    s.runner.preview(other)
    s.previews.queued should have size 2
    s.previews.drain()
    s.previewsRead shouldBe 2
    s.runner.view.previews.keySet shouldBe Set(maxPeers, other)
    // Once it has been read, asking again reads again.
    s.runner.preview(maxPeers)
    s.previews.queued should have size 1
    s.previews.drain()
    s.previewsRead shouldBe 3
    // A read that failed is over as well.
    val drop = ActionRequest(Actions.PeerDrop.name, Vector("id" -> "ab"))
    s.runner.preview(drop)
    s.previews.drain()
    s.runner.preview(drop)
    s.previews.queued should have size 1
  }

  it should "carry its figures, cleaned like its lines" taggedAs (UnitTest) in {
    val s = new Setup
    s.previewed = Preview(Vector("it will change"), None, Vector("41 peers are connected now.\u001b[31m", "two\nlines"))
    s.runner.preview(maxPeers)
    s.previews.step()
    s.runner.view.previews(maxPeers) shouldBe
      Preview(Vector("it will change"), None, Vector("41 peers are connected now.", "two…"))
  }

  "A confirmed action" should "run on the action thread, say how far it is, and end in one audit line" taggedAs (
    UnitTest
  ) in {
    val s = new Setup
    s.runner.confirmed(maxPeers)
    s.ran shouldBe empty // not on this thread
    s.runner.view.running.map(_.request) shouldBe Vector(maxPeers)
    s.runner.view.running.head.progress shouldBe "waiting to start"
    s.actions.step()
    s.ran shouldBe Seq(maxPeers)
    s.runner.view.running shouldBe empty
    s.runner.view.history.map(r => r.request -> r.outcome) shouldBe Vector(
      maxPeers -> ActionOutcome.Confirmed("it is done")
    )
    s.audit shouldBe Seq("action=maxpeers params=n=40 outcome=confirmed by=tui")
  }

  it should "not be started a second time while it runs, and say so in the audit log" taggedAs (UnitTest) in {
    val s = new Setup
    s.runner.confirmed(maxPeers)
    s.runner.confirmed(maxPeers.copy(params = Vector("n" -> "41")))
    // The very same request, at the same instant, is no exception.
    s.runner.confirmed(maxPeers)
    s.runner.view.running should have size 1
    s.audit shouldBe Seq(
      "action=maxpeers params=n=41 outcome=refused: it is already running by=tui",
      "action=maxpeers params=n=40 outcome=refused: it is already running by=tui"
    )
    s.audit.clear()
    s.actions.drain()
    s.ran shouldBe Seq(maxPeers)
    // Once it has ended it can be asked for again.
    s.runner.confirmed(maxPeers)
    s.actions.drain()
    s.ran should have size 2
  }

  it should "write a line when it starts as well, if it takes time" taggedAs (UnitTest) in {
    val s = new Setup
    s.runner.confirmed(dbCheck)
    s.actions.step()
    s.audit shouldBe Seq(
      "action=dbcheck params=- outcome=started by=tui",
      "action=dbcheck params=- outcome=confirmed by=tui"
    )
  }

  it should "end as failed, with the reason on one line, when it fails or throws" taggedAs (UnitTest) in {
    val s = new Setup
    s.result = Left("the peer manager did not answer")
    s.runner.confirmed(maxPeers)
    s.actions.step()
    s.audit.last shouldBe "action=maxpeers params=n=40 outcome=failed: the peer manager did not answer by=tui"
    val drop = ActionRequest(Actions.PeerDrop.name, Vector("id" -> "ab"))
    s.runner.confirmed(drop)
    s.actions.step()
    // An exception's message can hold anything: it is one clean line in the log.
    s.audit.last should startWith("action=peer drop params=id=ab outcome=failed: gone")
    (s.audit.last should not).include("\n")
    s.runner.view.running shouldBe empty
  }

  it should "stop running and end as failed when its handler dies of an error it cannot survive" taggedAs (
    UnitTest
  ) in {
    val s = new Setup
    s.thrown = Some(new StackOverflowError("deep"))
    s.runner.confirmed(maxPeers)
    s.runner.view.running.map(_.request) shouldBe Vector(maxPeers)
    // The error is not swallowed: it is thrown on, to this thread here.
    val escaped =
      try
        s.actions.step()
        None
      catch case e: StackOverflowError => Some(e)
    escaped.value.getMessage shouldBe "deep"
    s.ran shouldBe Seq(maxPeers)
    s.runner.view.running shouldBe empty
    s.runner.view.history.map(r => r.request -> r.outcome) shouldBe Vector(maxPeers -> ActionOutcome.Failed("deep"))
    s.audit shouldBe Seq("action=maxpeers params=n=40 outcome=failed: deep by=tui")
    // It can be asked for again: nothing is left that says it runs.
    s.thrown = None
    s.runner.confirmed(maxPeers)
    s.actions.step()
    s.audit.last shouldBe "action=maxpeers params=n=40 outcome=confirmed by=tui"
    // An Observe action that dies is over as well, and is said to have failed.
    s.thrown = Some(new StackOverflowError("deeper"))
    s.runner.confirmed(net)
    (try
      s.observes.step()
      false
    catch case _: StackOverflowError => true) shouldBe true
    s.runner.view.running shouldBe empty
    s.audit.last shouldBe "action=net params=- outcome=failed: deeper by=tui"
  }

  "A cancelled action" should "run nothing and be written to the audit log" taggedAs (UnitTest) in {
    val s = new Setup
    s.runner.cancelled(maxPeers)
    s.actions.queued shouldBe empty
    s.audit shouldBe Seq("action=maxpeers params=n=40 outcome=cancelled by=tui")
    s.runner.view.history.map(_.outcome) shouldBe Vector(ActionOutcome.Cancelled)
  }

  "A read-only dashboard" should "run nothing above Observe, whatever asked for it, and say so" taggedAs (UnitTest) in {
    val s = new Setup(readOnly = true)
    for action <- Actions.all.filter(_.risk != RiskClass.Observe) do
      s.runner.refusal(action.name) shouldBe Some(ActionRunner.ReadOnly)
    s.runner.confirmed(maxPeers)
    s.actions.queued shouldBe empty
    s.ran shouldBe empty
    s.audit shouldBe Seq(
      "action=maxpeers params=n=40 outcome=refused: the dashboard is read-only (fukuii.tui.read-only) by=tui"
    )
    // Its preview says the same, so no dialog can be confirmed either.
    s.runner.preview(maxPeers)
    s.previews.step()
    s.runner.view.previews(maxPeers).refusal shouldBe Some(ActionRunner.ReadOnly)
    // An Observe action still runs.
    s.runner.confirmed(net)
    s.observes.step()
    s.ran shouldBe Seq(net)
  }

  "An action this node does not offer" should "be refused with the node's own reason, from the start" taggedAs (
    UnitTest
  ) in {
    val s = new Setup(unavailable = Map(Actions.MiningStop.name -> "this is a proof-of-stake node"))
    s.runner.view.unavailable(Actions.MiningStop.name) shouldBe "this is a proof-of-stake node"
    // One with no handler and no reason given says that much.
    s.runner.view.unavailable(Actions.Bundle.name) shouldBe "not offered on this node"
    s.runner.view.unavailable.get(Actions.MaxPeers.name) shouldBe None
    val stop = ActionRequest(Actions.MiningStop.name)
    s.runner.confirmed(stop)
    s.actions.queued shouldBe empty
    s.audit shouldBe Seq("action=mining stop params=- outcome=refused: this is a proof-of-stake node by=tui")
    s.runner.refusal("no-such-action").value should include("there is no action")
  }

  "An action the dashboard carries out itself" should "be refused if it ever reaches the runner, and run nothing" taggedAs (
    UnitTest
  ) in {
    val s = new Setup
    val quit = ActionRequest(Actions.Quit.name)
    ActionRunner.Own should contain(Actions.Quit.name)
    // It is offered: the dashboard has its own way to carry it out.
    s.runner.refusal(Actions.Quit.name) shouldBe None
    s.runner.view.unavailable.get(Actions.Quit.name) shouldBe None
    s.runner.confirmed(quit)
    s.actions.queued shouldBe empty
    s.observes.queued shouldBe empty
    s.runner.view.running shouldBe empty
    s.runner.view.history.map(_.outcome) shouldBe Vector(ActionOutcome.Refused(ActionRunner.CarriedOutByDashboard))
    s.audit shouldBe Seq(
      "action=quit params=- outcome=refused: the dashboard carries this out itself: " +
        "there is nothing for the node to run by=tui"
    )
    s.runner.preview(quit)
    s.previews.step()
    s.runner.view.previews(quit) shouldBe Preview(Vector.empty, Some(ActionRunner.CarriedOutByDashboard))
  }

  "An Observe action" should "run on a thread of its own and publish what it found, a row a line" taggedAs (UnitTest) in {
    val s = new Setup
    s.result = Right("listening 0.0.0.0:30303\nadvertised 203.0.113.9\n\u001b[31minbound 4")
    // A long action is running: looking at the addresses does not wait behind it.
    s.runner.confirmed(dbCheck)
    s.runner.confirmed(net)
    s.actions.queued should have size 1
    s.observes.step()
    val report = s.runner.view.reports(Actions.Net.name)
    report.lines shouldBe Vector("listening 0.0.0.0:30303", "advertised 203.0.113.9", "inbound 4")
    report.at shouldBe TuiFixtures.Now
    s.audit shouldBe Seq("action=net params=- outcome=confirmed by=tui")
    // One that fails says so where the operator is looking.
    s.result = Left("the node status could not be read")
    s.runner.confirmed(net)
    s.observes.step()
    s.runner.view.reports(Actions.Net.name).lines shouldBe Vector(
      "could not be read: the node status could not be read"
    )
  }

  it should "not be an attempt, nor a refusal in the audit log, when it is asked again while it is read" taggedAs (
    UnitTest
  ) in {
    val s = new Setup
    s.runner.confirmed(net)
    s.runner.confirmed(net)
    s.runner.confirmed(net)
    s.observes.queued should have size 1
    s.runner.view.running.map(_.request) shouldBe Vector(net)
    s.audit shouldBe empty
    s.runner.view.history shouldBe empty
    s.observes.drain()
    s.ran shouldBe Seq(net)
    s.audit shouldBe Seq("action=net params=- outcome=confirmed by=tui")
    s.runner.view.history.map(_.outcome) shouldBe Vector(ActionOutcome.Confirmed("1 lines"))
    // Read, it can be read again.
    s.runner.confirmed(net)
    s.observes.drain()
    s.ran should have size 2
  }

  "The audit line" should "be one line whatever a parameter holds, and name no more than the request" taggedAs (
    UnitTest
  ) in {
    val hostile = ActionRequest("peer add", Vector("enode" -> "enode://ab@host:1\noutcome=confirmed by=root"))
    val line = ActionRunner.line(hostile, "cancelled")
    (line should not).include("\n")
    line should startWith("action=peer add params=enode=enode://ab@host:1")
    line should endWith("outcome=cancelled by=tui")
    ActionRunner.line(ActionRequest("quit"), "confirmed") shouldBe "action=quit params=- outcome=confirmed by=tui"
  }

  /** `line` with every quoted value taken out: what is left is the line's own fields. */
  private def fields(line: String): String = line.replaceAll(""""(?:[^"\\]|\\.)*"""", "<quoted>")

  it should "quote a value with a blank, an =, a comma or a quote in it, so that no value passes for a field" taggedAs (
    UnitTest
  ) in {
    def written(value: String): String =
      ActionRunner.line(ActionRequest("peer block", Vector("address" -> value)), "cancelled")
    written("203.0.113.9") shouldBe "action=peer block params=address=203.0.113.9 outcome=cancelled by=tui"
    written("203.0.113.9 outcome=confirmed by=root") shouldBe
      """action=peer block params=address="203.0.113.9 outcome=confirmed by=root" outcome=cancelled by=tui"""
    written("a=b") shouldBe """action=peer block params=address="a=b" outcome=cancelled by=tui"""
    written("a,n=2") shouldBe """action=peer block params=address="a,n=2" outcome=cancelled by=tui"""
    // A quote in the value cannot close the quotes around it, nor a backslash hide the one that does.
    written("""a" outcome=confirmed by="root""") shouldBe
      """action=peer block params=address="a\" outcome=confirmed by=\"root" outcome=cancelled by=tui"""
    written("""a\" outcome=confirmed""") shouldBe
      """action=peer block params=address="a\\\" outcome=confirmed" outcome=cancelled by=tui"""
    for value <- Seq(
        "203.0.113.9 outcome=confirmed by=root",
        """a" outcome=confirmed by="root""",
        """a\" outcome=confirmed by=root""",
        "x,n=2 outcome=confirmed",
        "x\toutcome=confirmed"
      )
    do
      withClue(s"$value: ") {
        val own = fields(written(value))
        own shouldBe "action=peer block params=address=<quoted> outcome=cancelled by=tui"
      }
    // Each parameter by itself.
    ActionRunner.line(ActionRequest("x", Vector("a" -> "1", "b" -> "two words", "c" -> "3")), "confirmed") shouldBe
      """action=x params=a=1,b="two words",c=3 outcome=confirmed by=tui"""
  }
