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
  * each attempt, and that nothing runs on the caller's thread. Its threads are queues the test steps, so every order
  * of events is the one the test chose.
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
    private val handler = ActionHandler(
      _ => previewed,
      (request, progress) =>
        ran += request
        progress("half way")
        result
    )
    val runner = new ActionRunner(
      Actions.all,
      Map(
        Actions.MaxPeers.name -> handler,
        Actions.DbCheck.name -> handler,
        Actions.Net.name -> handler,
        Actions.PeerDrop.name -> ActionHandler(_ => throw new IllegalStateException("no peers"), (_, _) => throw new IllegalStateException("gone\nsecond line"))
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
    s.audit.last should not include "\n"
    s.runner.view.running shouldBe empty
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
    s.runner.view.reports(Actions.Net.name).lines shouldBe Vector("could not be read: the node status could not be read")
  }

  "The audit line" should "be one line whatever a parameter holds, and name no more than the request" taggedAs (
    UnitTest
  ) in {
    val hostile = ActionRequest("peer add", Vector("enode" -> "enode://ab@host:1\noutcome=confirmed by=root"))
    val line = ActionRunner.line(hostile, "cancelled")
    line should not include "\n"
    line should startWith("action=peer add params=enode=enode://ab@host:1")
    line should endWith("outcome=cancelled by=tui")
    ActionRunner.line(ActionRequest("quit"), "confirmed") shouldBe "action=quit params=- outcome=confirmed by=tui"
  }
