package com.chipprbots.ethereum.console

import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags.*

/** The worm reaches the brain: the once-per-process celebration and the quiet "back at head" frame (spec 027 FR-014,
  * Q8, research §3.8, T033).
  */
class CelebrationSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll with TuiGoldenFiles:

  protected val goldenTerminal = new TuiTestTerminal()
  override def afterAll(): Unit = goldenTerminal.close()

  private val renderer = TuiRenderer(TuiConfig.default)
  private val theme = TuiTheme.Ansi16
  private val now = TuiFixtures.Now

  /** Syncing two blocks from the head, then at the head: one catch-up. */
  private val syncing = TuiFixtures.snapshot(current = 21_884_100L, best = 21_884_102L, now = now)
  private val atHead = TuiFixtures.snapshot(current = 21_884_102L, best = 21_884_102L, now = now.plusSeconds(1))
  private val behind = TuiFixtures.snapshot(current = 21_884_102L, best = 21_884_110L, now = now.plusSeconds(2))
  private val backAtHead = TuiFixtures.snapshot(current = 21_884_110L, best = 21_884_110L, now = now.plusSeconds(3))

  private def caughtUp(motion: Motion = Motion.Full): TuiState =
    TuiState(motion = motion, startTime = now, now = now).withStatus(syncing).nextFrame(Some(atHead))

  private def blocksRow(s: TuiState): String =
    renderer.render(s, 80, 24, theme).map(_.toString).find(_.trim.startsWith("Blocks")).get

  private def header(s: TuiState) = renderer.render(s, 80, 24, theme).head

  "A catch-up" should "start the celebration once per process, on the first false → true" taggedAs (UnitTest) in {
    val s = caughtUp()
    s.celebrated shouldBe true
    s.celebrationStartedTick shouldBe Some(s.tick)
    s.catchup shouldBe Some(Catchup.Celebrating(0))
  }

  it should "not celebrate a node first seen at the head" taggedAs (UnitTest) in {
    val s = TuiState(startTime = now, now = now).nextFrame(Some(atHead))
    s.celebrated shouldBe false
    s.catchup shouldBe None
  }

  it should "run 12 frames, then draw the synced row" taggedAs (UnitTest) in {
    val frames = (0 until 14).scanLeft(caughtUp())((s, _) => s.nextFrame(None)).map(_.catchup)
    frames.take(12) shouldBe (0 until 12).map(i => Some(Catchup.Celebrating(i)))
    frames.drop(12).distinct shouldBe Seq(None)
    TuiState.CelebrationFrames shouldBe 12
  }

  it should "flash the header on frames 2–6 only" taggedAs (UnitTest) in {
    val start = caughtUp()
    val plain = header(start.copy(celebrationStartedTick = None))
    for frame <- 0 until 12 do
      val s = start.copy(tick = start.tick + frame)
      withClue(s"frame $frame: ") {
        s.headerFlash shouldBe (frame >= 2 && frame <= 6)
        header(s).toString shouldBe plain.toString // the text never moves
        if s.headerFlash then header(s) should not be plain else header(s) shouldBe plain
      }
  }

  it should "be cancelled by p" taggedAs (UnitTest) in {
    val paused = caughtUp().nextFrame(None).togglePause(Motion.Full)
    paused.catchup shouldBe None
    paused.headerFlash shouldBe false
    paused.togglePause(Motion.Full).catchup shouldBe None // resuming does not bring it back
    paused.celebrated shouldBe true
  }

  it should "show one frame under reduced motion" taggedAs (UnitTest) in {
    val s = caughtUp(Motion.Reduced)
    s.catchup shouldBe Some(Catchup.Celebrated)
    s.headerFlash shouldBe false
    s.nextFrame(None).catchup shouldBe None
    val frames = (0 until 12).scanLeft(s)((st, _) => st.nextFrame(None)).map(renderer.render(_, 80, 24, theme))
    frames.distinct should have size 2 // the one celebration frame, then the synced frame
  }

  // Q8: once per process; every later catch-up is one quiet frame.
  it should "show one quiet 'back at head' frame on a later catch-up, with no header flash" taggedAs (UnitTest) in {
    val done = (0 until 12).foldLeft(caughtUp())((s, _) => s.nextFrame(None))
    val again = done.nextFrame(Some(behind)).nextFrame(Some(backAtHead))
    again.catchup shouldBe Some(Catchup.BackAtHead)
    again.celebrationStartedTick shouldBe done.celebrationStartedTick // not restarted
    again.headerFlash shouldBe false
    blocksRow(again) should include(s"${TuiGlyphs.Emoji.check.text} back at head")
    blocksRow(again.copy(glyphs = GlyphSet.Ascii)) should include("+ back at head")
    again.nextFrame(None).catchup shouldBe None
  }

  it should "not start while paused, and still count as the process's celebration" taggedAs (UnitTest) in {
    val s = TuiState(motion = Motion.Paused, startTime = now, now = now).withStatus(syncing).nextFrame(Some(atHead))
    s.catchup shouldBe None
    s.celebrated shouldBe true
  }

  it should "draw the crawl of research §3.8 on the Blocks row" taggedAs (UnitTest) in {
    def row(frame: Int) = blocksRow(TuiFixtures.celebration(GlyphSet.Emoji, frame))
    row(0) should include("🪱.]🧠 99%")
    row(2) should include("=🪱]🧠 100%")
    row(4) should include("]🪱🧠 100%")
    row(6) should include("]✨🧠✨ synced")
    row(8) should include("🪱[COMPLETE ✓]🧠 ✨ synced")
    row(10) should include("🪱[COMPLETE ✓]🧠  synced")
    blocksRow(TuiFixtures.celebration(GlyphSet.Emoji, 12)) should include("🪱[COMPLETE ✓]🧠")
    blocksRow(TuiFixtures.celebration(GlyphSet.Emoji, 12)) should not include "synced"
  }

  "The celebration" should "match its golden frames at 80×24, frames 0/2/4/6/8/10, in both sets" taggedAs (UnitTest) in {
    for set <- GlyphSet.values; frame <- Seq(0, 2, 4, 6, 8, 10) do
      val lines = renderer.render(TuiFixtures.celebration(set, frame), 80, 24, theme)
      all(lines.map(_.columnLength())) shouldBe 80
      matchGolden(f"overview-s-celebration-f$frame%02d-${set.toString.toLowerCase}.txt", visibleFrame(lines))
  }
