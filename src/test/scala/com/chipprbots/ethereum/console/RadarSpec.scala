package com.chipprbots.ethereum.console

import org.scalatest.BeforeAndAfterAll
import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.console.panels.RadarPanel
import com.chipprbots.ethereum.console.panels.RadarPanel.Blip
import com.chipprbots.ethereum.testing.Tags.*

/** The peer radar (spec 029 FR-030): what a blip's place, colour and shape mean, and what is never drawn. */
class RadarSpec extends AnyFlatSpec with Matchers with OptionValues with BeforeAndAfterAll with TuiGoldenFiles:

  protected val goldenTerminal = new TuiTestTerminal()
  override def afterAll(): Unit = goldenTerminal.close()

  private val renderer = TuiRenderer(TuiConfig.default)
  private def peersView(s: TuiState) = s.withView(TuiView.Peers).copy(motion = Motion.Reduced)

  "A blip's ring" should "be its latency band, and the outer ring for a peer not measured yet" taggedAs (UnitTest) in {
    RadarPanel.ringOf(Some(20.0)) shouldBe 0
    RadarPanel.ringOf(Some(99.9)) shouldBe 0
    RadarPanel.ringOf(Some(100.0)) shouldBe 1
    RadarPanel.ringOf(Some(499.0)) shouldBe 1
    RadarPanel.ringOf(Some(1_999.0)) shouldBe 2
    RadarPanel.ringOf(Some(60_000.0)) shouldBe 3 // slower than every band, but measured
    RadarPanel.ringOf(None) shouldBe RadarPanel.Rings - 1
    Blip("ab", RadarPanel.ringOf(None), 0.0, incoming = false, None, flashing = false).measured shouldBe false
    Blip("ab", RadarPanel.ringOf(Some(60_000.0)), 0.0, incoming = false, None, flashing = false).measured shouldBe true
  }

  "A blip's angle" should "be fixed by the node id, and by nothing else" taggedAs (UnitTest) in {
    RadarPanel.angleOf("00000000ffff") shouldBe 0.0
    RadarPanel.angleOf("80000000ffff") shouldBe 0.5
    RadarPanel.angleOf("0x40000000") shouldBe 0.25
    RadarPanel.angleOf("c0000000" + "ab" * 60) shouldBe 0.75
    val s = TuiFixtures.syncedEtc()
    val before = RadarPanel.blips(s).map(b => b.id -> b.angle).toMap
    // Another moment, other scores, a selection: the same angles.
    val later =
      s.copy(tick = s.tick + 99, selectedPeer = s.orderedPeers.lastOption.map(_.id), now = s.now.plusSeconds(60))
    RadarPanel.blips(later).map(b => b.id -> b.angle).toMap shouldBe before
    all(before.values) should ((be >= 0.0).and(be < 1.0))
  }

  "The radar" should "draw one blip a peer, each on its own cell, nearer the centre the faster the peer" taggedAs (
    UnitTest
  ) in {
    val s = TuiFixtures.syncedEtc()
    val blips = RadarPanel.blips(s)
    blips should have size s.orderedPeers.size.toLong
    val rows = 17
    val placed = RadarPanel.place(blips, rows)
    placed should have size blips.size.toLong // every peer has a cell of its own
    val centre = ((rows - 1) / 2, rows - 1)
    placed.keys should not contain centre
    def distance(cell: (Int, Int)): Double = math.hypot((cell._1 - centre._1).toDouble, (cell._2 - centre._2) / 2.0)
    val byRing = placed.toSeq.groupBy(_._2.ring).view.mapValues(_.map((cell, _) => distance(cell))).toMap
    // Each ring's blips lie farther out than the ring inside it.
    byRing.keys.toSeq.sorted.sliding(2).foreach {
      case Seq(inner, outer) => byRing(inner).max should be < byRing(outer).min + 1.5
      case _                 => ()
    }
    byRing(0).sum / byRing(0).size should be < byRing(RadarPanel.Rings - 1).sum / byRing(RadarPanel.Rings - 1).size
    // The same peers, the same cells: nothing about a frame moves a blip.
    RadarPanel.place(blips.reverse, rows) shouldBe placed
  }

  it should "draw nothing between two peers: every cell is a blip, a ring mark, the centre or blank" taggedAs (
    UnitTest
  ) in {
    for set <- GlyphSet.values do
      val s = TuiFixtures.syncedEtc(set)
      val c = panels.Ctx(s, Tier.M, TuiGlyphs.of(set), TuiTheme.Ansi16, TuiConfig.default, screen = Some(Tier.L))
      val grid = RadarPanel.draw(c, 17).map(_.toString.slice(1, 1 + RadarPanel.cols(17)))
      val allowed = if set == GlyphSet.Ascii then " .+ox" else " ·✛●◆"
      withClue(grid.mkString("\n")) {
        grid.flatten.toSet.diff(allowed.toSet) shouldBe empty
        grid.flatten.count(ch => "ox●◆".contains(ch)) shouldBe s.orderedPeers.size
      }
  }

  it should "show direction by shape, the score band by colour, and an answer just now in bold" taggedAs (UnitTest) in {
    val s = TuiFixtures.syncedEtc()
    val blips = RadarPanel.blips(s)
    blips.map(_.incoming).toSet shouldBe Set(true, false)
    // The fixture's first peer answered 400 ms ago; the others seconds ago.
    blips.count(_.flashing) shouldBe 1
    blips.find(_.flashing).value.id shouldBe s.orderedPeers
      .find(p => s.peerStats(p.id).exists(_.lastSeen.exists(_.isAfter(s.now.minusSeconds(1)))))
      .value
      .id
    // A peer with no score yet is still drawn: muted, on the ring its latency gives it, or the outer one.
    blips.count(_.score.isEmpty) should be > 0
  }

  it should "be on the Peers view from tier M, above a list that keeps its rows, and not below" taggedAs (UnitTest) in {
    def text(w: Int, h: Int) = visibleFrame(renderer.render(peersView(TuiFixtures.syncedEtc()), w, h))
    text(120, 40) should include("Radar")
    text(160, 48) should include("nearer = answers faster")
    (text(80, 24) should not).include("Radar")
    // With no peer there is nothing to draw.
    val alone = TuiFixtures.withPeers(TuiFixtures.syncedEtc(), Sourced.Fresh(Nil, TuiFixtures.Now))
    (visibleFrame(renderer.render(peersView(alone), 160, 48)) should not).include("Radar")
  }

  "A peer's details" should "say what its score is made of: latency, answers, faults, when it last answered" taggedAs (
    UnitTest
  ) in {
    val s = TuiFixtures.syncedEtc()
    val peer = s.orderedPeers.head
    val shown =
      visibleFrame(renderer.render(s.withView(TuiView.Peers).copy(screens = List(Screen.PeerDetail(peer.id))), 120, 40))
    val stats = s.peerStats(peer.id).value
    shown should include(s"${stats.latencyMs.value.round} ms")
    shown should include(s"${stats.responses} answered")
    shown should include("no protocol violation")
    shown should include("last answered")
  }

  for (w, h) <- Seq((120, 40), (160, 48)); set <- GlyphSet.values do
    val file = s"radar-${Tier.of(w, h).toString.toLowerCase}-synced-${set.toString.toLowerCase}.txt"

    "The Peers view with the radar" should s"match $file" taggedAs (UnitTest) in {
      val lines = renderer.render(peersView(TuiFixtures.syncedEtc(set)), w, h)
      lines should have size h.toLong
      all(lines.map(_.columnLength())) shouldBe w
      matchGolden(file, visibleFrame(lines))
    }
