package com.chipprbots.ethereum.console

import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.console.gfx.ArtStore
import com.chipprbots.ethereum.console.gfx.Graphic
import com.chipprbots.ethereum.console.panels.Ctx
import com.chipprbots.ethereum.console.widgets.Mascot
import com.chipprbots.ethereum.testing.Tags.*

/** The mascot is a status lamp (spec 029 FR-018, FR-019, research §5.5): one pose a state of the node. */
class PoseSpec extends AnyFlatSpec with Matchers with OptionValues:

  "The pose" should "be the node's state: every row of the table" taggedAs (UnitTest) in {
    Pose.of(TuiState.initial) shouldBe Pose.Starting
    Pose.of(TuiFixtures.regularSyncEtc()) shouldBe Pose.Syncing
    Pose.of(TuiFixtures.syncedEtc()) shouldBe Pose.Watching
    Pose.of(TuiFixtures.lowDiskEtc()) shouldBe Pose.Warning
    Pose.of(TuiFixtures.zeroPeersEtc()) shouldBe Pose.Error
    Pose.of(TuiFixtures.unresponsiveEtc()) shouldBe Pose.Error
    Pose.of(TuiFixtures.stalledEtc()) shouldBe Pose.Asleep
    Pose.of(TuiFixtures.celebration(frame = 3)) shouldBe Pose.Celebrating
  }

  it should "show the most urgent: an error before a warning, a warning before a stall" taggedAs (UnitTest) in {
    val stalled = TuiFixtures.stalledEtc()
    // A stall with a second warning beside it is a warning; with an error, an error.
    val low = TuiFixtures.lowDiskEtc()
    Alerts.of(low).map(_.severity) should contain(Severity.Warn)
    val stalledAndZeroPeers =
      stalled.copy(peerCount = 0, zeroPeersSince = Some(TuiFixtures.Now.minusSeconds(600L)))
    Alerts.of(stalledAndZeroPeers).map(_.severity) should contain(Severity.Error)
    Pose.of(stalledAndZeroPeers) shouldBe Pose.Error
    // The celebration gives way to an alert.
    val celebratingNoPeers =
      TuiFixtures.celebration(frame = 3).copy(peerCount = 0, zeroPeersSince = Some(TuiFixtures.Now.minusSeconds(600L)))
    Pose.of(celebratingNoPeers) shouldBe Pose.Error
  }

  it should "follow the sheets' rows, and be named in words and as a face" taggedAs (UnitTest) in {
    Pose.values.toSeq.map(_.label) shouldBe ArtStore.Poses
    Pose.values.toSeq.map(Pose.caption).distinct should have size Pose.values.length.toLong
    Pose.values.toSeq.map(Pose.face).distinct should have size Pose.values.length.toLong
    // A face is plain ASCII: it reads in either glyph set.
    all(Pose.values.toSeq.flatMap(Pose.face)) should ((be >= ' ').and(be <= '~'))
  }

  private def ctx(state: TuiState): Ctx =
    Ctx(state, Tier.M, TuiGlyphs.of(state.glyphs), TuiTheme.Ansi16, TuiConfig.default, TuiFixtures.art, Some(Tier.L))

  private def frameOf(state: TuiState): Int =
    Mascot.region(ctx(state), Mascot.Large).graphic match
      case Graphic.SpriteFrame(_, _, frame) => frame
      case other                            => fail(s"not a sprite: $other")

  "The mascot's frame" should "follow the pose's timeline at the frame counter" taggedAs (UnitTest) in {
    val syncing = TuiFixtures.regularSyncEtc().copy(graphics = GraphicsMode.Pixel(TuiFixtures.Cell))
    val timeline = TuiFixtures.art.sprite("mascot").value.poses(Pose.Syncing.ordinal)
    timeline.frames should be > 1
    (0L until 12L).map(t => frameOf(syncing.copy(tick = t))) shouldBe (0L until 12L).map(timeline.frameAt)
    (0L until 12L).map(t => frameOf(syncing.copy(tick = t))).distinct.size should be > 1
  }

  it should "stay on the first frame under reduced motion, and where it stopped under pause" taggedAs (UnitTest) in {
    val syncing = TuiFixtures.regularSyncEtc().copy(graphics = GraphicsMode.Pixel(TuiFixtures.Cell))
    (0L until 12L).map(t => frameOf(syncing.copy(tick = t, motion = Motion.Reduced))).distinct shouldBe Seq(0)
    // Pause stops the frame counter, so the frame on screen stays up: it does not jump back to the first.
    val timeline = TuiFixtures.art.sprite("mascot").value.poses(Pose.Syncing.ordinal)
    frameOf(syncing.copy(tick = 2, motion = Motion.Paused)) shouldBe timeline.frameAt(2)
    syncing.copy(tick = 2, motion = Motion.Paused).nextFrame(None).tick shouldBe 2
    // The pose still follows the state.
    Mascot.region(ctx(TuiFixtures.zeroPeersEtc().copy(motion = Motion.Reduced)), Mascot.Large).graphic shouldBe
      Graphic.SpriteFrame("mascot", Pose.Error.ordinal, 0)
  }

  "The mascot" should "take the large sprite on a wide, tall panel, the small one from tier M, none below" taggedAs (
    UnitTest
  ) in {
    def size(screen: Tier, w: Int, n: Int) =
      Mascot.sizeFor(ctx(TuiFixtures.syncedEtc()).copy(screen = Some(screen)), w, n)
    size(Tier.L, 141, 17) shouldBe Some(Mascot.Large)
    size(Tier.L, 113, 17) shouldBe Some(Mascot.Small) // the panel keeps 80 columns beside the large one
    size(Tier.L, 141, 16) shouldBe Some(Mascot.Small) // no row for the words under the large one
    size(Tier.M, 141, 17) shouldBe Some(Mascot.Small)
    size(Tier.M, 81, 9) shouldBe Some(Mascot.Small)
    size(Tier.M, 81, 8) shouldBe None
    size(Tier.S, 141, 40) shouldBe None
  }
