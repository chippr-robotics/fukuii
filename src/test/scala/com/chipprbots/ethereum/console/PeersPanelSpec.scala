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
    TuiFixtures.panelRows(lines.map(_.toString), w, h)

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

  it should "draw a score that is not finite as — and sort it last, with no trend" taggedAs (UnitTest) in {
    val current = TuiFixtures.peerScores(fewPeers)
    val scored = fewPeers.filter(p => current.scores.contains(p.id))
    val (nan, up, down) = (scored(0), scored(1), scored(2))
    val broken = current.copy(scores =
      current.scores ++ Seq(
        nan.id -> Double.NaN,
        up.id -> Double.PositiveInfinity,
        down.id -> Double.NegativeInfinity
      )
    )
    val s = few.copy(scores = few.scores.copy(current = Some(broken)))
    for p <- Seq(nan, up, down) do
      s.peerScore(p.id) shouldBe None
      s.scoreTrend(p.id) shouldBe None
    val rows = peersView(s, 160, 48)
    for p <- Seq(nan, up, down) do
      val row = rowOf(rows, p.id)
      row should include("—")
      for text <- Seq("NaN", "Infinity", "0.000", "9223372036854775807") do (row should not).include(text)
    // A finite score stays as it was, and the three come after every scored peer.
    val finite = scored.drop(3).head
    s.peerScore(finite.id) shouldBe current.scores.get(finite.id)
    val at = (p: TuiPeer) => rows.indexWhere(_.contains(panels.Rows.shortId(p.id, TuiGlyphs.Emoji)))
    for bad <- Seq(nan, up, down); good <- scored.drop(3) do at(bad) should be > at(good)
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

  // ---- prism, slice 4 review -----------------------------------------------------------------------------------------

  "An Unresponsive reason" should "reach the Peers view through the sanitiser: ASCII only under the ASCII set" taggedAs (
    UnitTest
  ) in {
    val reason = "peer said \u001b[2J ünreachable 中"
    val peersDown = TuiFixtures.withPeers(synced, Sourced.Unresponsive(reason))
    val detailsDown =
      synced.copy(snapshot = synced.snapshot.map(_.copy(details = Some(Sourced.Unresponsive(reason)))))
    for s <- Seq(peersDown, detailsDown); (w, h) <- Seq((80, 24), (120, 40), (160, 48)) do
      val rows = peersView(s.copy(glyphs = GlyphSet.Ascii), w, h)
      withClue(s"${w}x$h:\n${rows.mkString("\n")}\n") {
        all(rows.mkString.map(_.toInt)) should be < 0x7f
        if w >= 160 then rows.mkString should include("?nreachable ??")
      }
  }

  "The Peers table" should "order peers whose scores round alike by how long they have been connected" taggedAs (
    UnitTest
  ) in {
    val two = TuiFixtures.peerList(2) // the first connected longest ago
    val s = TuiFixtures
      .polled(TuiFixtures.syncedEtc().copy(peerCount = 2), two, TuiFixtures.local())
      .copy(scores =
        ScoreHistory(None, Some(PeerScores(TuiFixtures.Now, Map(two(0).id -> 0.5001, two(1).id -> 0.5004))))
      )
    val rows = peersView(s, 120, 40)
    val first = rows.indexWhere(_.trim.startsWith("id ")) + 1
    rows(first) should include(panels.Rows.shortId(two(0).id, TuiGlyphs.Emoji)) // 0.500 and 0.500: the older first
  }

  "A peer id that is not hex" should "be drawn through the sanitiser, and short, whatever the set" taggedAs (UnitTest) in {
    val hostile = "peer\u001b[2J-ünïcode-中-with-a-long-name"
    for set <- GlyphSet.values do
      val shown = panels.Rows.shortId(hostile, TuiGlyphs.of(set))
      shown.exists(Character.isISOControl(_)) shouldBe false
      shown should startWith("peer-") // the escape sequence is gone, not the id
      shown.length should be < hostile.length // and the id is cut
      if set == GlyphSet.Ascii then all(shown.map(_.toInt)) should be < 0x7f
    panels.Rows.shortId("0x" + "ab" * 32, TuiGlyphs.Emoji) shouldBe "0xabab…ab" // a hex id is as it was
  }

  it should "be cut by code points, never inside a surrogate pair" taggedAs (UnitTest) in {
    panels.Rows.shortId("🪱" * 14, TuiGlyphs.Emoji) shouldBe "🪱" * 11 + "…"
    panels.Rows.shortId("🪱" * 12, TuiGlyphs.Emoji) shouldBe "🪱" * 12 // twelve fit
  }

  it should "not be taken for hex because of full-width digits" taggedAs (UnitTest) in {
    val fullWidth = "\uff10\uff11\uff12\uff13\uff14\uff15\uff16\uff17\uff18\uff19" // Character.digit says 0 to 9
    all(panels.Rows.shortId(fullWidth, TuiGlyphs.Ascii).map(_.toInt)) should be < 0x7f
  }

  "The score column" should "show the score at the units the order and the trend use, so the three agree" taggedAs (
    UnitTest
  ) in {
    // 0.5005 times 1000 is 500.49999999999994 in binary: it is 500 units, and `%.3f` would have drawn 0.501 beside a
    // peer at 0.5004 that it orders and trends as equal.
    TuiState.scoreUnits(0.5005) shouldBe TuiState.scoreUnits(0.5004)
    val two = TuiFixtures.peerList(2)
    val s = TuiFixtures
      .polled(TuiFixtures.syncedEtc().copy(peerCount = 2), two, TuiFixtures.local())
      .copy(scores =
        ScoreHistory(None, Some(PeerScores(TuiFixtures.Now, Map(two(0).id -> 0.5005, two(1).id -> 0.5004))))
      )
    val rows = peersView(s, 120, 40)
    val first = rows.indexWhere(_.trim.startsWith("id ")) + 1
    rows(first) should include(" 0.500 ")
    rows(first + 1) should include(" 0.500 ")
    (rows.mkString should not).include("0.501")
  }

  "The Overview's top table" should "not call a peer without a score one of the top by score" taggedAs (UnitTest) in {
    val s = few.copy(scores = ScoreHistory(None, Some(PeerScores(TuiFixtures.Now, Map(fewPeers(0).id -> 0.9)))))
    val title = frame(s, 160, 48).find(_.contains(" Peers (top ")).getOrElse(fail("no top table"))
    (title should not).include("by score")
    title should include("1 scored")
  }
