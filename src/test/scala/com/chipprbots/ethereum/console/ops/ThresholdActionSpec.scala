package com.chipprbots.ethereum.console.ops

import java.time.Duration

import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.console.AlertThresholds
import com.chipprbots.ethereum.console.Alerts
import com.chipprbots.ethereum.console.TuiFixtures
import com.chipprbots.ethereum.console.TuiState
import com.chipprbots.ethereum.testing.Tags.*

/** `threshold` (spec 029 FR-046, FR-054, T097; contracts/actions.md): the threshold now and after, the alerts that fire
  * or clear at once, the line of configuration that keeps it, and that it changes the dashboard's own copy and nothing
  * else.
  */
class ThresholdActionSpec extends AnyFlatSpec with Matchers with OptionValues:

  // 41 peers of a limit of 50, and no alert.
  private val calm = TuiFixtures.syncedEtc()
  private val configured = AlertThresholds.Default

  private def set(name: String, value: String): ActionRequest =
    ActionRequest(Actions.Threshold.name, Vector("name" -> name, "value" -> value))
  private val reset = ActionRequest(Actions.Threshold.name, Vector("reset" -> "all"))

  private val SessionOnly =
    "For this session of the dashboard only: the node, and a dashboard started later, keep the configured value."

  private def alerts(state: TuiState): Seq[String] = Alerts.of(state).map(_.text)

  "The fixture" should "raise no alert as configured, and one when most of the peer limit is asked for" taggedAs (
    UnitTest
  ) in {
    alerts(calm) shouldBe empty
    alerts(calm.copy(thresholds = configured.copy(lowPeersFraction = 0.9))) should have size 1
  }

  "The preview" should "say the threshold now and after, that no alert changes, and the line that keeps it" taggedAs (
    UnitTest
  ) in {
    ThresholdAction.preview(calm, configured, set("stall-after", "5m")) shouldBe Preview(
      Vector(
        "stall-after: 2m now, 5m after.",
        "No alert fires or clears with it now.",
        SessionOnly,
        "To keep it: fukuii.tui.alerts.stall-after = 5m"
      )
    )
    ThresholdAction.preview(calm, configured, set("disk-warn", "50 GiB")).lines shouldBe Vector(
      "disk-warn: 20 GiB now, 50 GiB after.",
      "No alert fires or clears with it now.",
      SessionOnly,
      "To keep it: fukuii.tui.alerts.disk-warn = 50 GiB"
    )
  }

  it should "name the alert that fires at once" taggedAs (UnitTest) in {
    val fired = alerts(calm.copy(thresholds = configured.copy(lowPeersFraction = 0.9))).head
    ThresholdAction.preview(calm, configured, set("low-peers-fraction", "0.9")) shouldBe Preview(
      Vector(
        "low-peers-fraction: 0.25 now, 0.9 after.",
        s"Fires at once: $fired.",
        SessionOnly,
        "To keep it: fukuii.tui.alerts.low-peers-fraction = 0.9"
      )
    )
  }

  it should "name the alert that clears at once" taggedAs (UnitTest) in {
    val strict = calm.copy(thresholds = configured.copy(lowPeersFraction = 0.9))
    val showing = alerts(strict).head
    ThresholdAction.preview(strict, configured, set("low-peers-fraction", "0.5")) shouldBe Preview(
      Vector(
        "low-peers-fraction: 0.9 now, 0.5 after.",
        s"Clears at once: $showing.",
        SessionOnly,
        "To keep it: fukuii.tui.alerts.low-peers-fraction = 0.5"
      )
    )
  }

  it should "be refused for a name that is no threshold, naming the thresholds" taggedAs (UnitTest) in {
    val p = ThresholdAction.preview(calm, configured, set("peer-count", "3"))
    p.lines shouldBe empty
    p.refusal.value should startWith("there is no threshold 'peer-count'; the thresholds are zero-peers-after, ")
    for key <- AlertThresholds.Keys do p.refusal.value should include(key)
  }

  it should "be refused for a value the threshold does not take, with the reason" taggedAs (UnitTest) in {
    val fraction = ThresholdAction.preview(calm, configured, set("low-peers-fraction", "7"))
    fraction.lines shouldBe empty
    fraction.refusal.value should include("is not above 0 and at most 1")
    ThresholdAction.preview(calm, configured, set("stall-after", "soon")).refusal should not be empty
    ThresholdAction.preview(calm, configured, set("reorg-depth", "deep")).refusal should not be empty
    ThresholdAction.preview(calm, configured, set("stall-after", "0s")).refusal.value should include("above 0")
    // The disk's error never begins above its warning.
    ThresholdAction.preview(calm, configured, set("disk-warn", "1 GiB")).refusal shouldBe
      Some("the disk's error must begin at or below its warning (disk-error <= disk-warn)")
  }

  it should "be refused when nothing would change, and when it is asked for nothing" taggedAs (UnitTest) in {
    val same = Preview(Vector.empty, Some("the thresholds are already so: nothing would change"))
    ThresholdAction.preview(calm, configured, set("stall-after", "2m")) shouldBe same
    // The same length of time, written otherwise.
    ThresholdAction.preview(calm, configured, set("stall-after", "120s")) shouldBe same
    ThresholdAction.preview(calm, configured, reset) shouldBe same
    val usage = Preview(Vector.empty, Some("usage: threshold <name> <value> | threshold reset"))
    ThresholdAction.preview(calm, configured, ActionRequest(Actions.Threshold.name)) shouldBe usage
    ThresholdAction.preview(
      calm,
      configured,
      ActionRequest(Actions.Threshold.name, Vector("name" -> "stall-after"))
    ) shouldBe
      usage
  }

  "A reset" should "say every threshold that returns to its configured value, and the alerts that change with it" taggedAs (
    UnitTest
  ) in {
    val changed = calm.copy(thresholds =
      configured.copy(lowPeersFraction = 0.9, stallAfter = Duration.ofMinutes(5), reorgDepth = 7)
    )
    val showing = alerts(changed).head
    ThresholdAction.preview(changed, configured, reset) shouldBe Preview(
      Vector(
        // In the order of the keys.
        "low-peers-fraction: 0.9 now, 0.25 after.",
        "stall-after: 5m now, 2m after.",
        "reorg-depth: 7 now, 3 after.",
        s"Clears at once: $showing.",
        SessionOnly,
        "To keep it: fukuii.tui.alerts.low-peers-fraction = 0.25",
        "To keep it: fukuii.tui.alerts.stall-after = 2m",
        "To keep it: fukuii.tui.alerts.reorg-depth = 3"
      )
    )
  }

  it should "return to what the node started with, not to the defaults" taggedAs (UnitTest) in {
    val started = configured.copy(stallAfter = Duration.ofMinutes(10))
    val now = calm.copy(thresholds = started.copy(stallAfter = Duration.ofMinutes(1)))
    ThresholdAction.after(now.thresholds, started, reset) shouldBe Right(started)
    ThresholdAction.preview(now, started, reset).lines.head shouldBe "stall-after: 1m now, 10m after."
  }

  // ---- the handler --------------------------------------------------------------------------------------------------

  /** A dashboard's state, changed as a key changes it. */
  private class Dashboard(start: TuiState = calm):
    var state: TuiState = start
    var modified = 0
    val handler: ActionHandler = ThresholdAction.handler(
      () => state,
      change =>
        modified += 1
        state = change(state)
    )

  "The handler" should "change the dashboard's own thresholds through the function it was given, and say the value" taggedAs (
    UnitTest
  ) in {
    val d = new Dashboard
    d.handler.run(set("low-peers-fraction", "0.9"), _ => ()) shouldBe Right("low-peers-fraction is 0.9")
    d.modified shouldBe 1
    d.state.thresholds shouldBe configured.copy(lowPeersFraction = 0.9)
    // Nothing else of the state moved.
    d.state.copy(thresholds = configured) shouldBe calm
    // The next preview is of the state as it is now.
    d.handler.preview(set("low-peers-fraction", "0.5")).lines.head shouldBe "low-peers-fraction: 0.9 now, 0.5 after."
    d.handler.run(set("stall-after", "5m"), _ => ()) shouldBe Right("stall-after is 5m")
    d.state.thresholds shouldBe configured.copy(lowPeersFraction = 0.9, stallAfter = Duration.ofMinutes(5))
  }

  it should "reset to the thresholds the dashboard had when it was made" taggedAs (UnitTest) in {
    val started = configured.copy(reorgDepth = 5)
    val d = new Dashboard(calm.copy(thresholds = started))
    d.handler.run(set("reorg-depth", "9"), _ => ()) shouldBe Right("reorg-depth is 9")
    d.handler.preview(reset).lines.head shouldBe "reorg-depth: 9 now, 5 after."
    d.handler.run(reset, _ => ()) shouldBe Right("every threshold is as configured again")
    d.state.thresholds shouldBe started
    d.handler.preview(reset).refusal shouldBe Some("the thresholds are already so: nothing would change")
  }

  it should "change nothing, and say why, for a name or a value that is none" taggedAs (UnitTest) in {
    val d = new Dashboard
    d.handler.run(set("peer-count", "3"), _ => ()).left.toOption.value should startWith(
      "there is no threshold 'peer-count'"
    )
    d.handler.run(set("low-peers-fraction", "7"), _ => ()).left.toOption.value should include("at most 1")
    d.handler.run(ActionRequest(Actions.Threshold.name), _ => ()) shouldBe
      Left("usage: threshold <name> <value> | threshold reset")
    d.modified shouldBe 0
    d.state shouldBe calm
  }
