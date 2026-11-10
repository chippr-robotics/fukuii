package com.chipprbots.ethereum.console

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.console.TuiText.formatNumber
import com.chipprbots.ethereum.testing.Tags.*

/** The Peers view and the Overview's top peers (spec 027 FR-019, research §3.1, §3.4, T087, T088): each peer's client
  * and best block from `GetHandshakedPeersCmd`, its direction and age from `GetPeersCmd`, and its score with the trend
  * from slice 4a's `NetworkMetrics` export.
  */
class PeersPanelSpec extends AnyFlatSpec with Matchers:

  private val renderer = TuiRenderer(TuiConfig.default)

  private def frame(s: TuiState, w: Int, h: Int): Seq[String] =
    val lines = renderer.render(s.copy(motion = Motion.Reduced), w, h)
    lines should have size h.toLong
    all(lines.map(_.columnLength())) shouldBe w
    lines.map(_.toString)

  private def peersView(s: TuiState, w: Int, h: Int): Seq[String] = frame(s.withView(TuiView.Peers), w, h)

  /** The row of the peer with `id`, by its short id. */
  private def rowOf(rows: Seq[String], id: String, g: TuiGlyphs = TuiGlyphs.Emoji): String =
    val short = panels.Rows.shortId(id, g)
    rows.find(_.contains(short)).getOrElse(fail(s"no row for $short in:\n${rows.mkString("\n")}"))

  private val synced = TuiFixtures.syncedEtc()
  private val peers = TuiFixtures.peerList(41)

  /** The highest-scored peer: the first row of every table, at every size. */
  private val best = TuiFixtures.trends(peers).head._1
  private val bestAge = TuiText.formatShort(java.time.Duration.between(best.connectedAt, TuiFixtures.Now).getSeconds)

  /** Ten peers: every row fits at 160×48, the unscored seventh included. */
  private val fewPeers = TuiFixtures.peerList(10)
  private val few = TuiFixtures.polled(TuiFixtures.syncedEtc().copy(peerCount = 10), fewPeers, TuiFixtures.local())

  "The Peers view" should "show each peer's client, best block, direction and age (T087)" taggedAs (UnitTest) in {
    for (w, h) <- Seq((80, 24), (120, 40), (160, 48)) do
      val detail = TuiFixtures.peerDetails(peers)(best.id)
      val row = rowOf(peersView(synced, w, h), best.id)
      withClue(s"${w}x$h: '$row'\n") {
        row should include(TuiText.clientName(detail.clientId).take(12))
        row should include(formatNumber(detail.bestBlock))
        row should include(if best.incoming then " in " else " out ")
        row should include(bestAge)
      }
  }

  it should "add the address and the score from tier M, and the trend at tier L (research §3.1, T098)" taggedAs (
    UnitTest
  ) in {
    def heads(w: Int, h: Int): String = peersView(synced, w, h).find(_.trim.startsWith("id ")).getOrElse("")
    (heads(80, 24) should not).include("score")
    (heads(80, 24) should not).include("address")
    heads(120, 40) should include("address")
    heads(120, 40) should include("score")
    (heads(120, 40) should not).include("trend")
    heads(160, 48) should include("score")
    heads(160, 48) should include("trend")
  }

  it should "sort the peers by score, highest first, the unscored last" taggedAs (UnitTest) in {
    val rows = peersView(synced, 160, 48)
    val scores = TuiFixtures.peerScores(peers).scores
    val best = peers.maxBy(p => scores.getOrElse(p.id, -1.0))
    val firstRow = rows.indexWhere(_.trim.startsWith("id ")) + 1
    rows(firstRow) should include(panels.Rows.shortId(best.id, TuiGlyphs.Emoji))
  }

  it should "say Unresponsive when the network peer manager does not answer, and keep each peer's row (T087)" taggedAs (
    UnitTest
  ) in {
    val s =
      synced.copy(snapshot = synced.snapshot.map(_.copy(details = Some(Sourced.Unresponsive("no reply within 500ms")))))
    for (w, h) <- Seq((80, 24), (120, 40), (160, 48)) do
      val rows = peersView(s, w, h)
      withClue(s"${w}x$h:\n${rows.mkString("\n")}\n") {
        rows.mkString("\n") should include("Unresponsive")
        rowOf(rows, best.id) should include(bestAge)
      }
  }

  it should "draw a client id carrying ESC[2J without the escape, and the frame stays exact (C-16, T087)" taggedAs (
    UnitTest
  ) in {
    val p = best
    val nasty = PeerDetails("Geth\u001b[2J\u001b]0;pwned\u0007/v1.13.5\nsecond line", 21_884_102L)
    val s = synced.copy(snapshot =
      synced.snapshot.map(snap =>
        snap.copy(details = Some(Sourced.Fresh(TuiFixtures.peerDetails(peers).updated(p.id, nasty), TuiFixtures.Now)))
      )
    )
    for (w, h) <- Seq((80, 24), (120, 40), (160, 48)); set <- GlyphSet.values do
      val rows = peersView(s.copy(glyphs = set), w, h)
      withClue(s"${w}x$h $set:\n${rows.mkString("\n")}\n") {
        rows.exists(_.exists(ch => Character.isISOControl(ch))) shouldBe false
        (rows.mkString should not).include("pwned")
        rowOf(rows, p.id, TuiGlyphs.of(set)) should include("Geth/v1.13.5")
      }
  }

  it should "show the score read from slice 4a's snapshot, and — for a peer it has no score for, never zero (Q5, T088)" taggedAs (
    UnitTest
  ) in {
    val scores = TuiFixtures.peerScores(fewPeers).scores
    val scored = fewPeers.find(p => scores.contains(p.id)).get
    val unscored = fewPeers.find(p => !scores.contains(p.id)).get
    for (w, h) <- Seq((120, 40), (160, 48)) do
      val rows = peersView(few, w, h)
      rowOf(rows, scored.id) should include(String.format(java.util.Locale.ROOT, "%.3f", scores(scored.id)))
      val missing = rowOf(rows, unscored.id)
      missing should include("—")
      (missing should not).include("0.000")
  }

  it should "show the trend at L: up, down or steady against the previous snapshot (T088)" taggedAs (UnitTest) in {
    val rows = peersView(few, 160, 48)
    for (p, trend) <- TuiFixtures.trends(fewPeers) do
      val glyph = trend match
        case Trend.Up     => "↑"
        case Trend.Down   => "↓"
        case Trend.Steady => "→"
      rowOf(rows, p.id) should include(s" $glyph ")
  }

  "The Overview at tier L" should "list the top 8 peers by score (research §3.4, T087)" taggedAs (UnitTest) in {
    val rows = frame(synced, 160, 48)
    val title = rows.indexWhere(_.contains("Peers (top 8 of 41 by score)"))
    title should be >= 0
    val scores = TuiFixtures.peerScores(peers).scores
    val top = peers.filter(p => scores.contains(p.id)).sortBy(p => -scores(p.id)).take(8)
    val body = rows.slice(title + 2, title + 2 + 8)
    body.zip(top).foreach((row, p) => row should include(panels.Rows.shortId(p.id, TuiGlyphs.Emoji)))
    (rows(title + 10) should not).include("0x") // no ninth peer
  }
