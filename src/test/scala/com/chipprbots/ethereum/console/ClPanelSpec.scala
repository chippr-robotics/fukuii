package com.chipprbots.ethereum.console

import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags.*

/** The consensus-layer panel and its two alerts (spec 027 FR-022, FR-016, T110, research §3.3, §3.9): what the Engine
  * API last heard, and what the node did with it, on an ETH node; nothing on an ETC node.
  *
  * The alerts are each tested at the boundary. The text comes from the node's own metrics, never from outside it.
  */
class ClPanelSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll with TuiGoldenFiles:

  protected val goldenTerminal = new TuiTestTerminal()
  override def afterAll(): Unit = goldenTerminal.close()

  private val renderer = TuiRenderer(TuiConfig.default)
  private val sizes: Seq[(Int, Int)] = Seq((80, 24), (120, 40), (160, 48))
  private val t0 = TuiFixtures.Now

  private def still(s: TuiState): TuiState = s.copy(motion = Motion.Reduced)

  private def rows(s: TuiState, w: Int, h: Int): Seq[String] =
    TuiFixtures.panelRows(renderer.render(still(s), w, h).map(_.toString), w, h)

  /** The panel's rows: from its title row down to the separator before the next panel. */
  private def clRows(s: TuiState, w: Int, h: Int): Seq[String] =
    val all = rows(s, w, h)
    val start = all.indexWhere(r => r.startsWith(" Consensus layer") || r.startsWith(" CL "))
    if start < 0 then Nil
    else all.drop(start).takeWhile(r => !r.startsWith("──") && !r.startsWith("--")).filter(_.trim.nonEmpty)

  private def texts(s: TuiState): Seq[String] = Alerts.of(s).map(_.text)

  /** [[TuiFixtures.sepoliaNearHead]] whose consensus layer last called `fcu` and `payload` seconds ago. */
  private def callsAgo(fcu: Long, payload: Long, state: TuiState = TuiFixtures.sepoliaNearHead()): TuiState =
    TuiFixtures.withLocal(
      state,
      l =>
        l.copy(cl =
          l.cl.map(
            _.copy(forkchoiceUpdated = Some(TuiFixtures.clCall(fcu)), newPayload = Some(TuiFixtures.clCall(payload)))
          )
        )
    )

  /** At its head: the node's block is the highest block any peer has. */
  private def atHead(s: TuiState): TuiState = s.copy(currentBlock = s.bestBlock)

  // ---- the panel -------------------------------------------------------------------------------------------------

  "The consensus-layer panel" should "show the link and the last calls in a row, the blocks in a second, the payloads in a third, from tier M" taggedAs (
    UnitTest
  ) in {
    for (w, h) <- sizes.drop(1) do
      val panel = clRows(TuiFixtures.sepoliaNearHead(), w, h)
      withClue(s"${w}x$h:\n${panel.mkString("\n")}\n") {
        panel should have size 3
        panel.head should startWith(" Consensus layer ")
        panel.head should include("connected")
        panel.head should include("forkchoiceUpdated 4s ago (VALID)")
        panel.head should include("newPayload 4s ago (VALID)")
        panel(1) should include("head 0x7b3e…a91c")
        panel(1) should include("safe 7,411,977")
        panel(1) should include("finalized 7,411,945")
        panel(2) should include("payloads 184,203 (VALID 184,199 · ACCEPTED 4 · INVALID 0)")
      }
  }

  it should "fit the link and both calls on one row at tier S" taggedAs (UnitTest) in {
    val panel = clRows(TuiFixtures.sepoliaNearHead(), 80, 24)
    panel should have size 1
    panel.head should startWith(" CL ")
    panel.head should include("connected")
    panel.head should include("forkchoice 4s (VALID)")
    panel.head should include("payload 4s (VALID)")
  }

  it should "count the payloads as the node counts them: ACCEPTED apart from INVALID, SYNCING only when counted" taggedAs (
    UnitTest
  ) in {
    def payloads(counts: PayloadCounts): String =
      val s = TuiFixtures.withLocal(
        TuiFixtures.sepoliaNearHead(),
        l => l.copy(cl = l.cl.map(_.copy(payloads = counts)))
      )
      clRows(s, 120, 40)(2)
    payloads(PayloadCounts(total = 10, valid = 6, syncing = 0, accepted = 3, invalid = 1)) should
      include("payloads 10 (VALID 6 · ACCEPTED 3 · INVALID 1)")
    payloads(PayloadCounts(total = 10, valid = 5, syncing = 2, accepted = 3, invalid = 0)) should
      include("payloads 10 (VALID 5 · SYNCING 2 · ACCEPTED 3 · INVALID 0)")
  }

  it should "show the authentication failures only when there are some" taggedAs (UnitTest) in {
    (clRows(TuiFixtures.sepoliaNearHead(), 120, 40).mkString("\n") should not).include("auth")
    val failing =
      TuiFixtures.withLocal(TuiFixtures.sepoliaNearHead(), l => l.copy(cl = l.cl.map(_.copy(authFailures = 3L))))
    clRows(failing, 120, 40)(1) should include("auth failures 3")
  }

  it should "show a missing safe or finalized block as the missing glyph, not as zero" taggedAs (UnitTest) in {
    val none = TuiFixtures.withLocal(
      TuiFixtures.sepoliaNearHead(),
      l => l.copy(cl = l.cl.map(_.copy(safe = None, finalized = None)))
    )
    val row = clRows(none, 120, 40)(1)
    row should include("safe —")
    row should include("finalized —")
    clRows(none.copy(glyphs = GlyphSet.Ascii), 120, 40)(1) should include("safe -")
  }

  it should "say waiting, in one row, before the consensus layer has called" taggedAs (UnitTest) in {
    for (w, h) <- sizes do
      val panel = clRows(TuiFixtures.clWaitingSepolia(), w, h)
      withClue(s"${w}x$h:\n${panel.mkString("\n")}\n") {
        panel should have size 1
        panel.head should include("waiting for its first forkchoiceUpdated")
      }
  }

  it should "say DISCONNECTED when the last forkchoiceUpdated is more than a minute old" taggedAs (UnitTest) in {
    for (w, h) <- sizes do
      val panel = clRows(TuiFixtures.clDisconnectedSepolia(), w, h)
      withClue(s"${w}x$h:\n${panel.mkString("\n")}\n") {
        panel.head should include("DISCONNECTED")
        (panel.head should not).include("connected ·")
        panel.head should include("6m 3s")
        panel.head should include("6m 5s")
      }
  }

  it should "show the authentication failures on the first row while it is waiting, and at tier S, which have no second row" taggedAs (
    UnitTest
  ) in {
    def withAuth(s: TuiState, n: Long) =
      TuiFixtures.withLocal(s, l => l.copy(cl = l.cl.map(_.copy(authFailures = n))))
    // A wrong JWT secret leaves the consensus layer waiting for ever: the count is the one sign of it.
    for (w, h) <- sizes do
      clRows(withAuth(TuiFixtures.clWaitingSepolia(), 3L), w, h).head should include("3 auth failures")
      clRows(withAuth(TuiFixtures.clWaitingSepolia(), 1L), w, h).head should include("1 auth failure")
      (clRows(withAuth(TuiFixtures.clWaitingSepolia(), 1L), w, h).head should not).include("1 auth failures")
    // At tier S every state has one row, and the count outranks the statuses: they go before it is cut.
    val connected = clRows(withAuth(TuiFixtures.sepoliaNearHead(), 3L), 80, 24).head
    connected should include("3 auth failures")
    connected should include("forkchoice 4s")
    (connected should not).include("…")
    clRows(withAuth(TuiFixtures.clDisconnectedSepolia(), 3L), 80, 24).head should include("3 auth failures")
    // None when there are none.
    (clRows(TuiFixtures.clWaitingSepolia(), 80, 24).head should not).include("auth")
    (clRows(TuiFixtures.sepoliaNearHead(), 80, 24).head should not).include("auth")
    // From tier M a node that is calling has them on its second row, and only there.
    val m = clRows(withAuth(TuiFixtures.sepoliaNearHead(), 3L), 120, 40)
    (m.head should not).include("auth")
    m(1) should include("auth failures 3")
  }

  it should "give the link glyph to the emoji set only: the ASCII set's own is the label's `CL`" taggedAs (UnitTest) in {
    for
      (w, h) <- sizes.drop(1);
      fixture <- Seq(
        (g: GlyphSet) => TuiFixtures.sepoliaNearHead(g),
        (g: GlyphSet) => TuiFixtures.clWaitingSepolia(g),
        (g: GlyphSet) => TuiFixtures.clDisconnectedSepolia(g)
      )
    do
      val ascii = clRows(fixture(GlyphSet.Ascii), w, h).head
      withClue(s"${w}x$h: '$ascii'\n") {
        ascii should startWith(" Consensus layer ")
        (ascii should not).include("Consensus layer CL")
      }
      clRows(fixture(GlyphSet.Emoji), w, h).head should include("Consensus layer 🔗 ")
  }

  it should "use the compact form, without ago, when the long one would be cut, as it is at 100 columns" taggedAs (UnitTest) in {
    val disconnected = clRows(TuiFixtures.clDisconnectedSepolia(), NarrowM, 32)
    withClue(disconnected.mkString("\n")) {
      disconnected.head should include("DISCONNECTED")
      disconnected.head should include("forkchoiceUpdated 6m 3s (VALID)")
      disconnected.head should include("newPayload 6m 5s (VALID)")
      (disconnected.head should not).include("…")
      (disconnected.head should not).include(" ago")
    }
    // A row that fits keeps its long form, and a wider terminal keeps it for the disconnected one too.
    clRows(TuiFixtures.sepoliaNearHead(), NarrowM, 32).head should include("forkchoiceUpdated 4s ago (VALID)")
    clRows(TuiFixtures.clDisconnectedSepolia(), 120 + TuiFixtures.SidebarCols, 40).head should include(
      "forkchoiceUpdated 6m 3s ago (VALID)"
    )
  }

  it should "be on an ETH node only: an ETC node, whatever it mines, has no consensus-layer row" taggedAs (UnitTest) in {
    for (name, fixture) <- TuiFixtures.all; (w, h) <- sizes; set <- GlyphSet.values do
      val s = fixture(set)
      if s.chain.exists(_.family == ChainFamily.PoW) then
        withClue(s"$name ${w}x$h $set: ")(clRows(s, w, h) shouldBe empty)
  }

  it should "draw the same rows at every tick: nothing in it moves" taggedAs (UnitTest) in {
    for set <- GlyphSet.values; (w, h) <- sizes do
      val full = TuiFixtures.sepoliaNearHead(set).copy(motion = Motion.Full)
      val frames = (0L to 3L).map(t => clRows(full.copy(tick = t), w, h))
      frames.distinct should have size 1
  }

  it should "draw ASCII only under the ASCII set" taggedAs (UnitTest) in {
    for
      (w, h) <- sizes;
      fixture <- Seq(
        (g: GlyphSet) => TuiFixtures.sepoliaNearHead(g),
        (g: GlyphSet) => TuiFixtures.clDisconnectedSepolia(g)
      )
    do
      val panel = clRows(fixture(GlyphSet.Ascii), w, h)
      panel should not be empty
      for row <- panel do withClue(s"'$row': ")(row.forall(ch => ch >= ' ' && ch < '\u007f') shouldBe true)
  }

  // ---- the alert: no forkchoiceUpdated for more than 60 s is an error ----------------------------------------------

  "The forkchoiceUpdated alert" should "be an error after 60 s without a call, and not at 60 s" taggedAs (UnitTest) in {
    texts(callsAgo(fcu = 60, payload = 4)) shouldBe empty
    val silent = callsAgo(fcu = 61, payload = 4)
    Alerts.of(silent) shouldBe Seq(
      Alert(Severity.Error, "CL disconnected: no forkchoiceUpdated for 1m 1s (last VALID)")
    )
    texts(callsAgo(fcu = 363, payload = 365)) shouldBe Seq(
      "CL disconnected: no forkchoiceUpdated for 6m 3s (last VALID)"
    )
  }

  it should "name the status of the last call, as it was recorded" taggedAs (UnitTest) in {
    val invalid = TuiFixtures.withLocal(
      callsAgo(fcu = 120, payload = 4),
      l => l.copy(cl = l.cl.map(c => c.copy(forkchoiceUpdated = c.forkchoiceUpdated.map(_.copy(status = "SYNCING")))))
    )
    texts(invalid) shouldBe Seq("CL disconnected: no forkchoiceUpdated for 2m 0s (last SYNCING)")
  }

  it should "clear when the consensus layer calls again" taggedAs (UnitTest) in {
    texts(callsAgo(fcu = 400, payload = 400)).exists(_.startsWith("CL disconnected")) shouldBe true
    texts(callsAgo(fcu = 0, payload = 400)).exists(_.startsWith("CL disconnected")) shouldBe false
  }

  it should "not fire on an ETC node, or before the probe has read the Engine API" taggedAs (UnitTest) in {
    texts(TuiFixtures.syncedEtc()) shouldBe empty
    texts(TuiFixtures.sepoliaNearHead().copy(snapshot = None)) shouldBe empty
  }

  it should "show on the alert row, and in the panel, at 80×24" taggedAs (UnitTest) in {
    val frame = rows(TuiFixtures.clDisconnectedSepolia(), 80, 24)
    frame(2) should include("1 error")
    frame(2) should include("CL disconnected: no forkchoiceUpdated for 6m 3s (last VALID)")
  }

  "The waiting warning" should "come after 60 s of uptime without a first call, and not before" taggedAs (UnitTest) in {
    def waiting(uptimeSeconds: Long) = TuiFixtures.clWaitingSepolia().copy(startTime = t0.minusSeconds(uptimeSeconds))
    texts(waiting(60)) shouldBe empty
    Alerts.of(waiting(61)) shouldBe Seq(Alert(Severity.Warn, "no CL yet: no forkchoiceUpdated in 1m 1s"))
  }

  it should "say how many JWT checks failed: a wrong secret is a consensus layer that calls and is refused" taggedAs (
    UnitTest
  ) in {
    def refused(n: Long) =
      TuiFixtures
        .withLocal(
          TuiFixtures.clWaitingSepolia().copy(startTime = t0.minusSeconds(61)),
          l => l.copy(cl = l.cl.map(_.copy(authFailures = n)))
        )
    texts(refused(1L)) shouldBe Seq("no CL yet: no forkchoiceUpdated in 1m 1s (1 JWT failure)")
    texts(refused(40L)) shouldBe Seq("no CL yet: no forkchoiceUpdated in 1m 1s (40 JWT failures)")
    texts(refused(0L)) shouldBe Seq("no CL yet: no forkchoiceUpdated in 1m 1s")
  }

  it should "be a warning, not an error: a node waiting for its first consensus layer is not a node that lost one" taggedAs (
    UnitTest
  ) in {
    Alerts.of(TuiFixtures.clWaitingSepolia().copy(startTime = t0.minusSeconds(3_600))).map(_.severity) shouldBe
      Seq(Severity.Warn)
  }

  // ---- the alert: no newPayload for more than 5 min while peers advance is a warning -------------------------------

  "The newPayload alert" should "warn after 5 min without a newPayload while peers are ahead, and not at 5 min" taggedAs (
    UnitTest
  ) in {
    texts(callsAgo(fcu = 4, payload = 300)) shouldBe empty
    Alerts.of(callsAgo(fcu = 4, payload = 301)) shouldBe Seq(
      Alert(Severity.Warn, "no newPayload for 5m 1s while peers are ahead")
    )
    texts(callsAgo(fcu = 4, payload = 3_900)) shouldBe Seq("no newPayload for 1h 5m while peers are ahead")
  }

  it should "not warn when no peer is ahead: payloads the node already had record nothing, so a node at its head is quiet" taggedAs (
    UnitTest
  ) in {
    // A re-sent payload the node already holds is answered VALID without a record (EngineApiService.scala:448): the
    // last newPayload does not move. At the head no peer is ahead, and that silence is not an alert.
    texts(atHead(callsAgo(fcu = 4, payload = 3_900))) shouldBe empty
  }

  it should "leave the disconnection to the forkchoiceUpdated alert: one cause, one row" taggedAs (UnitTest) in {
    texts(callsAgo(fcu = 400, payload = 400)) shouldBe Seq(
      "CL disconnected: no forkchoiceUpdated for 6m 40s (last VALID)"
    )
  }

  it should "count a node's own start for a consensus layer seen before any state had its time" taggedAs (UnitTest) in {
    // The fixtures are not built poll by poll, so they carry no time the consensus layer was first seen: the start counts.
    val noPayload = TuiFixtures.withLocal(
      TuiFixtures.sepoliaNearHead().copy(startTime = t0.minusSeconds(301)),
      l => l.copy(cl = l.cl.map(_.copy(newPayload = None)))
    )
    texts(noPayload) shouldBe Seq("no newPayload for 5m 1s while peers are ahead")
    texts(noPayload.copy(startTime = t0.minusSeconds(300))) shouldBe empty
  }

  it should "count a payload never recorded from when the consensus layer was first seen, or the node started, whichever is later" taggedAs (
    UnitTest
  ) in {
    val noPayload = TuiFixtures.withLocal(
      TuiFixtures.sepoliaNearHead().copy(startTime = t0.minusSeconds(3_600)),
      l => l.copy(cl = l.cl.map(_.copy(newPayload = None)))
    )
    // Up for an hour, the consensus layer seen 100 s ago: it has been calling for 100 s, not for an hour.
    texts(noPayload.copy(clSeenSince = Some(t0.minusSeconds(100)))) shouldBe empty
    texts(noPayload.copy(clSeenSince = Some(t0.minusSeconds(300)))) shouldBe empty
    texts(noPayload.copy(clSeenSince = Some(t0.minusSeconds(301)))) shouldBe Seq(
      "no newPayload for 5m 1s while peers are ahead"
    )
    // Seen before the node started (a clock step, a restarted TUI): the start is the later of the two.
    texts(noPayload.copy(startTime = t0.minusSeconds(100), clSeenSince = Some(t0.minusSeconds(900)))) shouldBe empty
  }

  it should "need a peer ahead of the node's head: a peer's best block, or the sync's own target" taggedAs (UnitTest) in {
    val stale = callsAgo(fcu = 4, payload = 400)
    texts(stale) should not be empty
    // Only the peers' best blocks say the chain moved: the sync controller knows no target.
    texts(stale.copy(bestBlock = 0L)) should not be empty
    // Only the sync's target does: no peer's details.
    val noDetails = stale.copy(snapshot = stale.snapshot.map(_.copy(details = None)))
    texts(noDetails) should not be empty
    // Neither is above the head.
    texts(atHead(stale)) shouldBe empty
    texts(atHead(noDetails)) shouldBe empty
  }

  // ---- when the consensus layer was first seen: what a late attach is measured from ------------------------------------

  /** A poll `seconds` after t0: the head moves every poll, so nothing here is a stalled sync. */
  private def clPoll(state: TuiState, seconds: Long, cl: ClSample): TuiState =
    state.withStatus(
      TuiFixtures
        .snapshot(current = 100 + seconds, best = 200 + seconds, now = t0.plusSeconds(seconds))
        .copy(local = LocalSample(cl = Some(cl)))
    )

  /** A sample whose last forkchoiceUpdated came 4 s before `seconds`, and no payload. */
  private def calling(seconds: Long): ClSample =
    ClSample(forkchoiceUpdated = Some(ClCall(t0.plusSeconds(seconds - 4), "VALID")))

  "The time the consensus layer was first seen" should "be the first poll with a forkchoiceUpdated, and stay" taggedAs (
    UnitTest
  ) in {
    val start = TuiState(maxPeers = 50, startTime = t0, now = t0)
    val waiting = clPoll(start, 0, ClSample())
    waiting.clSeenSince shouldBe None
    val seen = clPoll(waiting, 100, calling(100))
    seen.clSeenSince shouldBe Some(t0.plusSeconds(100))
    clPoll(seen, 200, calling(200)).clSeenSince shouldBe Some(t0.plusSeconds(100))
    // A poll the probe could not read leaves it.
    seen.withStatus(TuiFixtures.snapshot(current = 100, best = 200, now = t0.plusSeconds(300))).clSeenSince shouldBe
      Some(t0.plusSeconds(100))
  }

  "A consensus layer that attaches to a node that has been up for an hour" should "not trip the newPayload warning on its first call" taggedAs (
    UnitTest
  ) in {
    val up = clPoll(clPoll(TuiState(maxPeers = 50, startTime = t0, now = t0), 0, ClSample()), 3_600, ClSample())
    val attached = clPoll(up, 3_700, calling(3_700))
    texts(attached) shouldBe empty
    texts(clPoll(attached, 4_000, calling(4_000))) shouldBe empty // 300 s of calling, no payload yet
    texts(clPoll(attached, 4_001, calling(4_001))) shouldBe Seq("no newPayload for 5m 1s while peers are ahead")
  }

  // ---- the rules of the row ------------------------------------------------------------------------------------------

  "The alert row" should "list the consensus layer's alert after the other errors, in §2.3's order" taggedAs (UnitTest) in {
    val s = TuiFixtures.withPeers(
      callsAgo(fcu = 363, payload = 365),
      Sourced.Unresponsive("no reply within 500ms")
    )
    texts(s) shouldBe Seq(
      "peer manager Unresponsive (no reply within 500ms)",
      "CL disconnected: no forkchoiceUpdated for 6m 3s (last VALID)"
    )
  }

  "The age of a call" should "never be negative, whatever the two clocks say" taggedAs (UnitTest) in {
    // A call stamped a little ahead of the snapshot: the machine's clock moved, or the two were read a moment apart.
    val ahead = TuiFixtures.withLocal(
      TuiFixtures.sepoliaNearHead(),
      l => l.copy(cl = l.cl.map(_.copy(forkchoiceUpdated = Some(ClCall(t0.plusSeconds(90), "VALID")))))
    )
    clRows(ahead, 120, 40).head should include("forkchoiceUpdated 0s ago (VALID)")
    texts(ahead) shouldBe empty
    ClCall(t0.plusSeconds(90), "VALID").ageAt(t0) shouldBe java.time.Duration.ZERO
    ClCall(t0.minusSeconds(61), "VALID").ageAt(t0) shouldBe java.time.Duration.ofSeconds(61)
  }

  // ---- goldens: research §3.3 and §3.9 ---------------------------------------------------------------------------

  private val goldens: Seq[(String, GlyphSet => TuiState)] = Seq(
    "connected" -> (g => TuiFixtures.sepoliaNearHead(g)),
    "waiting" -> (g => TuiFixtures.clWaitingSepolia(g)),
    "disconnected" -> (g => TuiFixtures.clDisconnectedSepolia(g))
  )

  private def fileName(width: Int, height: Int, state: String, set: GlyphSet): String =
    s"cl-${Tier.of(width, height).toString.toLowerCase}-$state-${set.toString.toLowerCase}.txt"

  "The consensus-layer goldens" should "cover three states, three tiers, two glyph sets: 18 files" taggedAs (UnitTest) in {
    val names = for (s, _) <- goldens; (w, h) <- sizes; set <- GlyphSet.values.toSeq yield fileName(w, h, s, set)
    names.distinct should have size 18
  }

  /** The narrowest tier M, 100 columns, where the disconnected row's long form was cut: `(VALI…`. */
  private def narrowName(set: GlyphSet): String = s"cl-m-100x32-disconnected-${set.toString.toLowerCase}.txt"

  /** The screen whose body, beside the sidebar (spec 029 FR-006), is those 100 columns. */
  private val NarrowM: Int = 100 + widgets.Sidebar.Cols + 1

  // The golden is the panel's three rows, not the frame: slice 3's Forks detail row on an ETH node is itself cut at 100
  // columns, in the middle of a timestamp, which TuiGoldenLintSpec would (rightly) refuse in a golden.
  for set <- GlyphSet.values do
    "The consensus-layer panel at the narrowest tier M" should s"match ${narrowName(set)}" taggedAs (UnitTest) in {
      val lines = renderer.render(still(TuiFixtures.clDisconnectedSepolia(set)), NarrowM, 32, TuiTheme.Ansi16)
      lines should have size 32L
      all(lines.map(_.columnLength())) shouldBe NarrowM
      val body = lines.map(_.columnSubSequence(widgets.Sidebar.Cols + 1, NarrowM))
      val start = body.indexWhere(_.toString.startsWith(" Consensus layer"))
      start should be >= 0
      matchGolden(narrowName(set), visibleFrame(body.slice(start, start + 3)))
    }

  for (stateName, fixture) <- goldens; (width, height) <- sizes; set <- GlyphSet.values do
    val name = fileName(width, height, stateName, set)

    "The Overview of an ETH node" should s"match $name" taggedAs (UnitTest) in {
      val lines = renderer.render(still(fixture(set)), width, height, TuiTheme.Ansi16)
      lines should have size height.toLong
      all(lines.map(_.columnLength())) shouldBe width
      matchGolden(name, visibleFrame(lines))
    }
