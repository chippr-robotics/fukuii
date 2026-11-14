package com.chipprbots.ethereum.console

import java.time.Duration

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags.*

/** The alert row: research §2.3's conditions, each at its threshold (spec 027 FR-002, FR-016, US3, T060). */
class AlertsSpec extends AnyFlatSpec with Matchers:

  private val renderer = TuiRenderer(TuiConfig.default)
  private val t0 = TuiFixtures.Now
  private val GiB = 1024L * 1024 * 1024

  private def texts(s: TuiState): Seq[String] = Alerts.of(s).map(_.text)
  private def severities(s: TuiState): Seq[Severity] = Alerts.of(s).map(_.severity)

  /** A synced node polled at `t0 + seconds` with `peers` peers, that had none since `t0`. */
  private def peersAt(seconds: Long, peers: Int): TuiState =
    TuiState(maxPeers = 50, startTime = t0, now = t0)
      .withStatus(TuiFixtures.snapshot(current = 100, best = 100, now = t0, peers = 0))
      .withStatus(TuiFixtures.snapshot(current = 100, best = 100, now = t0.plusSeconds(seconds), peers = peers))

  private def withLocal(s: TuiState, local: LocalSample): TuiState =
    s.copy(snapshot = s.snapshot.map(_.copy(local = local)))

  private def withDisk(s: TuiState, bytes: Long): TuiState =
    withLocal(s, s.snapshot.get.local.copy(diskFree = Some(bytes)))

  // ---- each §2.3 row this slice can see, at its boundary --------------------------------------------------------

  "The 0-peers alert" should "fire after 30 s without a handshaked peer, not at 30 s" taggedAs (UnitTest) in {
    peersAt(30, 0).zeroPeersSince shouldBe Some(t0)
    texts(peersAt(30, 0)) shouldBe empty
    texts(peersAt(31, 0)) shouldBe Seq("0 peers for 31s")
    severities(peersAt(31, 0)) shouldBe Seq(Severity.Error)
    texts(peersAt(4 * 60 + 12, 0)) shouldBe Seq("0 peers for 4m 12s")
  }

  it should "clear when a peer handshakes" taggedAs (UnitTest) in {
    val s = peersAt(31, 3)
    s.zeroPeersSince shouldBe None
    texts(s).filter(_.startsWith("0 peers")) shouldBe empty
  }

  "An unresponsive source" should "be an error naming the peer manager or the sync controller" taggedAs (UnitTest) in {
    val base = TuiFixtures.syncedEtc()
    val peers = TuiFixtures.withPeers(base, Sourced.Unresponsive("no reply within 500ms"))
    Alerts.of(peers) shouldBe Seq(Alert(Severity.Error, "peer manager Unresponsive (no reply within 500ms)"))
    val sync = base.copy(snapshot = base.snapshot.map(_.copy(sync = Sourced.Unresponsive("no reply within 500ms"))))
    Alerts.of(sync) shouldBe Seq(Alert(Severity.Error, "sync controller Unresponsive (no reply within 500ms)"))
    // Stale is not unresponsive: the source missed one wait.
    val stale = TuiFixtures.withPeers(base, Sourced.Stale(TuiFixtures.peerList(41), t0.minusSeconds(2)))
    Alerts.of(stale) shouldBe empty
  }

  "The stall alert" should "warn when the worm stalls: progress unchanged for 120 s with blocks remaining" taggedAs (
    UnitTest
  ) in {
    def stalledFor(seconds: Long) = TuiFixtures.regularSyncEtc().copy(lastAdvanceAt = Some(t0.minusSeconds(seconds)))
    texts(stalledFor(119)) shouldBe empty
    Alerts.of(stalledFor(120)) shouldBe Seq(Alert(Severity.Warn, "sync stalled: head 18,402,113 unchanged for 2m 0s"))
    texts(stalledFor(190)) shouldBe Seq("sync stalled: head 18,402,113 unchanged for 3m 10s")
    // At the head nothing remains, so nothing stalls.
    texts(TuiFixtures.syncedEtc().copy(lastAdvanceAt = Some(t0.minusSeconds(600)))) shouldBe empty
  }

  "The SNAP sample alert" should "warn when the sample is older than 90 s while SNAP runs" taggedAs (UnitTest) in {
    def sampledAgo(seconds: Long, running: Boolean = true): TuiState =
      val base = TuiFixtures.snapStorageMordor()
      val sample = SyncSample(0L, 0L, "Syncing (no target yet)", None, Some(t0.minusSeconds(seconds)), running)
      base.copy(snapshot = base.snapshot.map(_.copy(sync = Sourced.Fresh(sample, t0))))
    // The fixture's 11 of 50 peers are a warning of their own; only the SNAP sample's is looked at here.
    def snapAlerts(s: TuiState) = Alerts.of(s).filter(_.text.startsWith("SNAP"))
    snapAlerts(sampledAgo(90)) shouldBe empty
    snapAlerts(sampledAgo(91)) shouldBe
      Seq(Alert(Severity.Warn, "SNAP sample is 1m 31s old (the monitor samples every 30s)"))
    // SNAP no longer running (SyncSample.snapRunning): an aborted run's last sample is not a warning.
    snapAlerts(sampledAgo(600, running = false)) shouldBe empty
  }

  // prism, slice 3 review: after an in-process SNAP abort hands over to regular sync, the SNAP rows stayed on screen
  // for good, marked "sample Nh old". The rows and the alert now share one test, SyncSample.snapRunning.
  it should "go, with the SNAP rows, once SNAP stops running and regular sync takes over" taggedAs (UnitTest) in {
    val snapping = TuiFixtures.snapStorageMordor()
    snapping.snapSyncState should not be empty
    def regular(sampleAgo: Long, running: Boolean) =
      NodeStatusSnapshot(
        now = t0,
        maxPeers = 50,
        localBest = 2_000L,
        peers = Sourced.Fresh(TuiFixtures.peerList(11), t0),
        sync = Sourced.Fresh(
          SyncSample(2_000L, 9_118_004L, "Syncing", None, Some(t0.minusSeconds(sampleAgo)), snapRunning = running),
          t0
        )
      )
    // Still within SNAP's running window: the rows stay, marked with their age, and the alert row says so.
    val within = snapping.withStatus(regular(120, running = true))
    within.snapSyncState should not be empty
    Alerts.of(within).map(_.text) should contain("SNAP sample is 2m 0s old (the monitor samples every 30s)")
    // Past it: the rows and the warning go, and the regular sync's Blocks worm is what the Overview draws.
    val after = within.withStatus(regular(301, running = false))
    after.snapSyncState shouldBe None
    Alerts.of(after).filter(_.text.startsWith("SNAP")) shouldBe empty
    renderer.render(after, 80, 24).map(_.toString).exists(_.trim.startsWith("Blocks")) shouldBe true
  }

  "The disk alert" should "warn below 20G free and be an error below 5G" taggedAs (UnitTest) in {
    val base = TuiFixtures.syncedEtc()
    texts(withDisk(base, 20 * GiB)) shouldBe empty
    Alerts.of(withDisk(base, 20 * GiB - 1)) shouldBe
      Seq(Alert(Severity.Warn, "disk 20.0G free (warn < 20G)"))
    Alerts.of(withDisk(base, 5 * GiB)).map(_.severity) shouldBe Seq(Severity.Warn)
    Alerts.of(withDisk(base, 5 * GiB - 1)) shouldBe
      Seq(Alert(Severity.Error, "disk 5.0G free (error < 5G)"))
    texts(withLocal(base, LocalSample())) shouldBe empty // unknown is not low
  }

  "The peer-count alert" should "warn below 25 % of the maximum" taggedAs (UnitTest) in {
    texts(peersAt(1, 13)) shouldBe empty // 13 / 50 = 26 %
    texts(peersAt(1, 12)) shouldBe Seq("peers 12/50 below 25%") // 24 %
    severities(peersAt(1, 12)) shouldBe Seq(Severity.Warn)
    // No peer at all is the 0-peers rule's, with its 30 s grace.
    texts(peersAt(10, 0)) shouldBe empty
    texts(
      TuiState(maxPeers = 0, now = t0).withStatus(TuiFixtures.snapshot(1, 1, t0, peers = 1)).copy(maxPeers = 0)
    ) shouldBe
      empty
  }

  // C-2: the alert row's 25 % and the peer count's 50 % colour are different on purpose.
  it should "leave the peer count's colour at today's 50 % threshold" taggedAs (UnitTest) in {
    val theme = TuiTheme.Ansi16
    def peerStyle(peers: Int) =
      val s = TuiFixtures.syncedEtc().copy(peerCount = peers)
      val row = renderer.render(s, 80, 24, theme).find(_.toString.trim.startsWith("Peers")).get
      row.styleAt(row.toString.indexOf(s"$peers/50"))
    peerStyle(24) shouldBe theme.warn.bold() // 48 %: yellow, no alert
    peerStyle(25) shouldBe theme.healthy.bold()
    texts(TuiFixtures.syncedEtc().copy(peerCount = 24)) shouldBe empty
  }

  // ---- the row --------------------------------------------------------------------------------------------------

  private def alertRow(s: TuiState, w: Int = 80, h: Int = 24): String =
    renderer.render(s, w, h).map(_.toString).apply(2)

  "The alert row" should "read '✓ no alerts' when nothing holds" taggedAs (UnitTest) in {
    alertRow(TuiFixtures.syncedEtc()).trim shouldBe "✓ no alerts"
    alertRow(TuiFixtures.syncedEtc(GlyphSet.Ascii)).trim shouldBe "+ no alerts"
  }

  it should "list at most three conditions, errors first, with the count and the badge" taggedAs (UnitTest) in {
    val base = TuiFixtures.regularSyncEtc().copy(lastAdvanceAt = Some(t0.minusSeconds(190)), peerCount = 6)
    val many = withDisk(
      base.copy(snapshot = base.snapshot.map(_.copy(sync = Sourced.Unresponsive("no reply within 500ms")))),
      4 * GiB
    )
    severities(many) shouldBe Seq(Severity.Error, Severity.Error, Severity.Warn, Severity.Warn)
    val row = alertRow(many, 160, 48)
    row should startWith(" ✗ 2 errors 2 warns  ")
    row should include("sync controller Unresponsive (no reply within 500ms) · disk 4.0G free")
    row should include("· sync stalled")
    (row should not).include("peers 6/50") // the fourth is counted, not listed
  }

  it should "show a warning badge when only warnings hold" taggedAs (UnitTest) in {
    alertRow(TuiFixtures.lowDiskEtc()) should startWith(" ⚠ 2 warns   disk 14.2G free")
    alertRow(TuiFixtures.lowDiskEtc(GlyphSet.Ascii)) should startWith(" ! 2 warns   disk 14.2G free")
    alertRow(TuiFixtures.stalledEtc()) should startWith(" ⚠ 1 warn    sync stalled")
  }

  it should "be drawn in the error style for an error, the warn style for warnings" taggedAs (UnitTest) in {
    val theme = TuiTheme.Ansi16
    def style(s: TuiState) = renderer.render(s, 80, 24, theme)(2).styleAt(1)
    style(TuiFixtures.zeroPeersEtc()) shouldBe theme.error
    style(TuiFixtures.lowDiskEtc()) shouldBe theme.warn.bold()
    style(TuiFixtures.syncedEtc()) shouldBe theme.healthy
  }

  it should "be identical across ticks, at any motion" taggedAs (UnitTest) in {
    for (_, fixture) <- TuiFixtures.alerts; motion <- Motion.values do
      val s = fixture(GlyphSet.Emoji).copy(motion = motion)
      (0L to 11L).map(t => renderer.render(s.copy(tick = t), 80, 24)(2)).distinct should have size 1
  }

  it should "be the first body row on every view, under the header and its separator" taggedAs (UnitTest) in {
    val s = TuiFixtures.zeroPeersEtc()
    val views = TuiView.values.toSeq.map(v => s.copy(view = v)) :+ s.copy(helpOpen = true)
    for v <- views; (w, h) <- Seq((80, 24), (120, 40), (160, 48)) do
      withClue(s"${v.view} help=${v.helpOpen} ${w}x$h: ") {
        alertRow(v, w, h) should startWith(" ✗ 1 error   0 peers for 4m 12s")
      }
  }

  it should "stay on screen, under the header, on a terminal four rows high" taggedAs (UnitTest) in {
    val rows = renderer.render(TuiFixtures.zeroPeersEtc(), 80, 4).map(_.toString)
    rows(2) should include("0 peers for 4m 12s")
    rows(3) should include("q quit")
  }

  "Alerts.of" should "measure every duration on the snapshot's clock, never on ticks" taggedAs (UnitTest) in {
    val s = peersAt(20, 0)
    (1 to 1000).foldLeft(s)((st, _) => st.nextFrame(None)).zeroPeersSince shouldBe Some(t0)
    texts((1 to 1000).foldLeft(s)((st, _) => st.nextFrame(None))) shouldBe empty
    Duration.between(t0, s.now).getSeconds shouldBe 20
  }
