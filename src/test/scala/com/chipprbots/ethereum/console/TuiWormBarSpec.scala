package com.chipprbots.ethereum.console

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.WormToBrainBar
import com.chipprbots.ethereum.testing.Tags.*

/** [[WormBar]]: the TUI's worm-to-brain bar, budgeted in columns (spec 027 FR-006, research §4.1). */
class TuiWormBarSpec extends AnyFlatSpec with Matchers:

  private val sets = Seq(TuiGlyphs.Emoji, TuiGlyphs.Ascii)
  private val theme = TuiTheme.Ansi16
  private val fractions = Seq(-0.5, 0.0, 0.01, 0.1, 0.25, 0.37, 0.5, 0.52, 0.68, 0.9, 0.99, 0.999, 1.0, 1.5)
  private val states =
    fractions.map(WormProgress.Known(_)) ++ Seq(WormProgress.Queued, WormProgress.Active, WormProgress.Complete)

  /** The static bar: no ripple or pulse, as the log draws it. */
  private def text(budget: Int, p: WormProgress, g: TuiGlyphs): String =
    WormBar.render(budget, p, tick = 0L, g, theme, Motion.Reduced).toString

  private val motions = Seq(Motion.Full, Motion.Reduced, Motion.Paused)

  "WormBar" should "be exactly the budget wide for budgets 8 to 80, in both sets, at every tick and motion" taggedAs (
    UnitTest
  ) in {
    for g <- sets; budget <- 8 to 80; p <- states; tick <- 0L to 11L; motion <- motions; stalled <- Seq(false, true) do
      withClue(s"${g.set} budget=$budget $p tick=$tick $motion stalled=$stalled: ")(
        WormBar.render(budget, p, tick, g, theme, motion, stalled).columnLength() shouldBe budget
      )
  }

  it should "stay exact below 8 columns, down to 0" taggedAs (UnitTest) in {
    for g <- sets; budget <- 0 to 7; p <- states; tick <- 0L to 3L; stalled <- Seq(false, true) do
      WormBar.render(budget, p, tick, g, theme, Motion.Full, stalled).columnLength() shouldBe budget
  }

  it should "put the worm at (p × (slots − 1)).toInt, as WormToBrainBar.renderKnown does" taggedAs (UnitTest) in {
    for g <- sets; budget <- 20 to 80; p <- fractions if p > 0 && p < 1 do
      val slots = WormBar.slotsFor(budget, g)
      val bar = text(budget, WormProgress.Known(p), g)
      // '[' then `wormAt` '=' cells, then the worm.
      withClue(s"${g.set} budget=$budget p=$p bar=$bar: ") {
        bar.indexOf(g.worm.text) - 1 shouldBe (p * (slots - 1)).toInt
        bar.count(_ == '=') shouldBe (p * (slots - 1)).toInt
      }
  }

  it should "draw the log's bar at 20 slots in the emoji set" taggedAs (UnitTest) in {
    val budget = WormBar.budgetFor(20, TuiGlyphs.Emoji)
    budget shouldBe 30
    for p <- fractions do
      withClue(s"p=$p: ")(
        text(budget, WormProgress.Known(p), TuiGlyphs.Emoji).trim shouldBe WormToBrainBar.renderKnown(p)
      )
  }

  // Slice 2: [ACTIVE] is padded to the 11 columns of its pulse's widest frame, [ACTIVE...] (research §4.1).
  it should "give [QUEUED], [ACTIVE] and [COMPLETE ✓] fixed widths, the same in both sets" taggedAs (UnitTest) in {
    def label(p: WormProgress, g: TuiGlyphs) = WormBar.render(40, p, 0L, g, theme, Motion.Reduced).toString.trim
    for g <- sets do
      label(WormProgress.Queued, g) shouldBe s"${g.worm.text}[QUEUED]${g.brain.text}"
      label(WormProgress.Active, g) shouldBe s"${g.worm.text}[ACTIVE]   ${g.brain.text}"
      label(WormProgress.Complete, g) shouldBe s"${g.worm.text}[COMPLETE ${g.check.text}]${g.brain.text}"
    for p <- Seq(WormProgress.Queued, WormProgress.Active, WormProgress.Complete) do
      val widths = sets.map(g => org.jline.utils.AttributedString(label(p, g)).columnLength())
      withClue(p.toString)(widths.distinct should have size 1)
    WormBar.render(40, WormProgress.Queued, 0L, TuiGlyphs.Emoji, theme, Motion.Reduced).toString.trim shouldBe
      WormToBrainBar.renderUnknown(WormToBrainBar.WormState.Queued)
    WormBar.render(40, WormProgress.Complete, 0L, TuiGlyphs.Emoji, theme, Motion.Reduced).toString.trim shouldBe
      WormToBrainBar.renderUnknown(WormToBrainBar.WormState.Complete)
  }

  it should "show a finished known total as COMPLETE" taggedAs (UnitTest) in {
    for g <- sets do
      text(40, WormProgress.Known(1.0), g) shouldBe text(40, WormProgress.Complete, g)
      text(40, WormProgress.Known(7.0), g) shouldBe text(40, WormProgress.Complete, g)
  }

  it should "clamp progress below 0 and above 1" taggedAs (UnitTest) in {
    for g <- sets do
      text(40, WormProgress.Known(-3.0), g) shouldBe text(40, WormProgress.Known(0.0), g)
      text(40, WormProgress.Known(Double.NaN), g) shouldBe text(40, WormProgress.Known(0.0), g)
      text(40, WormProgress.Known(0.0), g) should include(" 0%")
  }

  it should "use ~o and @@ in ASCII, and no emoji" taggedAs (UnitTest) in {
    for p <- states do
      val bar = text(40, p, TuiGlyphs.Ascii)
      bar should include("~o")
      bar should include("@@")
      all(bar.map(_.toInt)) should be < 0x7f
  }

  it should "not move with the tick under reduced motion or pause" taggedAs (UnitTest) in {
    for g <- sets; p <- states; motion <- Seq(Motion.Reduced, Motion.Paused) do
      val frames = (0L to 11L).map(t => WormBar.render(50, p, t, g, theme, motion))
      frames.distinct should have size 1
  }

  it should "move with the tick under full motion only where it ripples or pulses" taggedAs (UnitTest) in {
    for g <- sets; p <- states do
      val frames = (0L to 11L).map(t => WormBar.render(50, p, t, g, theme, Motion.Full))
      // A known total ripples once the worm is two cells in; an unknown one pulses while active.
      val moves = p match
        case WormProgress.Known(f) if f < 1.0 => WormBar.wormAt(f, WormBar.slotsFor(50, g)) >= 2
        case WormProgress.Active              => true
        case _                                => false
      withClue(s"${g.set} $p: ")(frames.distinct.size > 1 shouldBe moves)
  }
