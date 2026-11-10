package com.chipprbots.ethereum

import java.util.regex.Pattern

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.console.*
import com.chipprbots.ethereum.testing.Tags.*

/** Tests for TuiRenderer - rendering logic for terminal output. */
class TuiRendererSpec extends AnyFlatSpec with Matchers:

  val defaultConfig: TuiConfig = TuiConfig.default
  val minimalConfig: TuiConfig = TuiConfig.minimal
  val renderer: TuiRenderer = TuiRenderer(defaultConfig)
  val minimalRenderer: TuiRenderer = TuiRenderer(minimalConfig)

  private def rows(state: TuiState, width: Int, height: Int, r: TuiRenderer = renderer): Seq[String] =
    r.render(state, width, height).map(_.toString)

  private def occurrences(text: String, of: String): Int = text.split(Pattern.quote(of), -1).length - 1

  /** The rows of a frame that draw a worm bar, in order. */
  private def wormRows(lines: Seq[String], g: TuiGlyphs): Seq[String] = lines.filter(_.contains(g.brain.text))

  private val snapLabels = Seq("Overall", "Accounts", "Storage", "Bytecode", "Healing")

  "TuiRenderer" should "render content with correct dimensions" taggedAs (UnitTest) in {
    val state = TuiState.initial
    val width = 80
    val height = 24

    val lines = renderer.render(state, width, height)

    // All lines should fill the height (minus 1 for the last line)
    lines.length should be >= height - 1
  }

  it should "include network information" taggedAs (UnitTest) in {
    val state = TuiState.initial.withNetworkName("ethereum-classic")
    val width = 100
    val height = 40

    val lines = renderer.render(state, width, height)

    // Slice 3: the network is in the header band, on every view (FR-018).
    lines.head.toString should include("ethereum-classic")
  }

  it should "include peer status" taggedAs (UnitTest) in {
    val state = TuiState.initial.withPeerCount(5, 25)
    val width = 100
    val height = 40

    val lines = renderer.render(state, width, height)
    val output = lines.map(_.toString).mkString("\n")

    output should include("Peers")
    output should include("5")
    output should include("25")
  }

  it should "include block information" taggedAs (UnitTest) in {
    val state = TuiState.initial.withBlockInfo(12345, 100000)
    val width = 100
    val height = 40

    val lines = renderer.render(state, width, height)
    val output = lines.map(_.toString).mkString("\n")

    // Slice 3: the vitals strip's Block cell, current → best (research §3.2).
    output should include("Block  12,345 → 100,000 (−87,655)")
  }

  it should "show synchronized status when synced" taggedAs (UnitTest) in {
    val state = TuiState.initial.withBlockInfo(100000, 100000)
    val width = 100
    val height = 40

    val lines = renderer.render(state, width, height)
    val output = lines.map(_.toString).mkString("\n")

    output should include("at head")
    // US1 scenario 2: the worm has reached the brain.
    output should include("🪱[COMPLETE ✓]🧠")
  }

  it should "show the Blocks worm when syncing (with config enabled)" taggedAs (UnitTest) in {
    val state = TuiState.initial.withBlockInfo(50000, 100000)
    val config = TuiConfig(showProgressBar = true)
    val rendererWithProgress = TuiRenderer(config)
    val width = 100
    val height = 40

    val lines = rendererWithProgress.render(state, width, height)
    val output = lines.map(_.toString).mkString("\n")

    output should include("Blocks")
    output should include("🧠 50%")
    output should include("(−50,000)") // the blocks remaining, in the Block cell
    (output should not).include("Sync Progress")
  }

  it should "not show the worm when the progress bar is disabled" taggedAs (UnitTest) in {
    val state = TuiState.initial.withBlockInfo(50000, 100000)
    val config = TuiConfig(showProgressBar = false)
    val rendererNoProgress = TuiRenderer(config)
    val width = 100
    val height = 40

    val lines = rendererNoProgress.render(state, width, height)
    val output = lines.map(_.toString).mkString("\n")

    (output should not).include("Sync Progress")
    (output should not).include(TuiGlyphs.Emoji.brain.text)
    output should include("(−50,000)")
  }

  it should "include runtime section with uptime" taggedAs (UnitTest) in {
    val state = TuiState.initial
    val width = 100
    val height = 40

    val lines = renderer.render(state, width, height)

    // Slice 3: the uptime is in the header band (research §3.1).
    lines.head.toString should include("up 0s")
  }

  it should "include footer with keyboard commands, labelling d Detach (Q7)" taggedAs (UnitTest) in {
    val state = TuiState.initial
    val width = 100
    val height = 40

    val lines = renderer.render(state, width, height)
    val footer = lines.last.toString

    footer should include("r Redraw")
    footer should include("d Detach")
    footer should include("q Quit")
    (footer should not).include("Disable")
  }

  it should "show node settings when enabled and available" taggedAs (UnitTest) in {
    val settings = NodeSettings(
      dataDir = "/data/fukuii",
      network = "etc",
      syncMode = "fast",
      pruningMode = "archive",
      maxPeers = 25,
      rpcEnabled = true,
      rpcPort = 8545,
      miningEnabled = false
    )
    val state = TuiState.initial.withNodeSettings(settings)
    val config = TuiConfig(showNodeSettings = true)
    val rendererWithSettings = TuiRenderer(config)
    val width = 100
    val height = 50

    val lines = rendererWithSettings.render(state, width, height)
    val output = lines.map(_.toString).mkString("\n")

    // Slice 3: one Node row on the Overview.
    output should include("Node")
    output should include("/data/fukuii")
    output should include("fast sync")
    output should include("archive pruning")
  }

  it should "not show node settings when disabled" taggedAs (UnitTest) in {
    val settings = NodeSettings(
      dataDir = "/data/fukuii",
      network = "etc",
      syncMode = "fast",
      pruningMode = "archive",
      maxPeers = 25,
      rpcEnabled = true,
      rpcPort = 8545,
      miningEnabled = false
    )
    val state = TuiState.initial.withNodeSettings(settings)
    val config = TuiConfig(showNodeSettings = false)
    val rendererNoSettings = TuiRenderer(config)
    val width = 100
    val height = 50

    val lines = rendererNoSettings.render(state, width, height)
    val output = lines.map(_.toString).mkString("\n")

    (output should not).include("/data/fukuii")
  }

  it should "create renderer with default config" taggedAs (UnitTest) in {
    val defaultRenderer = TuiRenderer.default
    defaultRenderer should not be null
  }

  it should "handle small terminal dimensions gracefully" taggedAs (UnitTest) in {
    val state = TuiState.initial
    val width = 40
    val height = 10

    // Should not throw
    val lines = renderer.render(state, width, height)
    lines should not be empty
  }

  it should "handle large terminal dimensions" taggedAs (UnitTest) in {
    val state = TuiState.initial
    val width = 200
    val height = 60

    // Should not throw
    val lines = renderer.render(state, width, height)
    lines should not be empty
    lines.length should be >= height - 1
  }

  // Frame geometry: Tui draws the frame from the home position with no trailing newline, so a frame that is taller or
  // wider than the terminal scrolls or wraps it and corrupts the display.
  it should "render exactly `height` lines of exactly `width` columns" taggedAs (UnitTest) in {
    for (width, height) <- Seq((40, 10), (80, 24), (100, 40), (200, 60)) do
      val lines = renderer.render(TuiState.initial.withBlockInfo(10, 100), width, height)
      lines.length shouldBe height
      all(lines.map(_.columnLength())) shouldBe width
  }

  // A two-column emoji cut at the last column used to leave the row one column short.
  it should "keep a row exact when a wide glyph straddles the right edge" taggedAs (UnitTest) in {
    val state = TuiFixtures.snapStorageMordor()
    for width <- 20 to 120; height <- Seq(24, 40) do
      all(renderer.render(state, width, height).map(_.columnLength())) shouldBe width
  }

  it should "keep the keyboard commands on the bottom row when the content does not fit" taggedAs (UnitTest) in {
    val lines = renderer.render(TuiState.initial, 80, 8)
    lines.length shouldBe 8
    lines.last.toString should include("q Quit")
  }

  it should "truncate an over-long value instead of wrapping" taggedAs (UnitTest) in {
    val state = TuiState.initial.withNodeSettings(NodeSettings(dataDir = "/x" * 100, network = "etc"))
    val lines = renderer.render(state, 60, 50)
    all(lines.map(_.columnLength())) shouldBe 60
  }

  // §3.1: the logo is in the dashboard only at tier L, in a right-hand column.
  it should "show the logo in a right-hand column at tier L only" taggedAs (UnitTest) in {
    def logoRows(width: Int, height: Int, r: TuiRenderer = renderer) =
      r.render(TuiState.initial, width, height).map(_.toString).filter(_.contains("=+#+."))
    logoRows(160, 48) should have size 1
    logoRows(160, 48).head.indexOf("=+#+.") should be > 100
    logoRows(160, 48).head should include(TuiGlyphs.Emoji.divider.text)
    logoRows(100, 60) shouldBe empty // M
    logoRows(120, 40) shouldBe empty // M
    logoRows(80, 24) shouldBe empty // S
    logoRows(160, 48, minimalRenderer) shouldBe empty // showLogo = false
  }

  it should "show an unknown network head as 'unknown', not 0" taggedAs (UnitTest) in {
    val output = renderer.render(TuiState.initial.withBlockInfo(5, 0), 100, 40).map(_.toString).mkString("\n")
    output should include("Block  5 → unknown")
  }

  it should "keep styles as attributes rather than embedded escape codes" taggedAs (UnitTest) in {
    val lines = renderer.render(TuiState.initial.withPeerCount(3, 10), 100, 40)
    (all(lines.map(_.toString)) should not).include("\u001b")
  }

  // Spec 027 FR-008: "⏸️  Idle" carried U+FE0F, which JLine counts as 0 columns and many terminals draw as 2.
  it should "draw the idle phase without U+FE0F" taggedAs (UnitTest) in {
    for g <- Seq(TuiGlyphs.Emoji, TuiGlyphs.Ascii); phase <- Seq("Idle", "Unknown") do
      (SnapSyncState(phase = phase).phaseDescription(g) should not).include("\ufe0f")
    val idle = TuiState.initial.withBlockInfo(0, 100).withSnapSyncState(SnapSyncState(phase = "Idle"))
    (rows(idle, 100, 40).mkString should not).include("\ufe0f")
  }

  it should "pad the phase glyph to four columns in both sets" taggedAs (UnitTest) in {
    SnapSyncState(phase = "ByteCodeAndStorageSync").phaseDescription(TuiGlyphs.Emoji) shouldBe
      "💾   Downloading bytecodes & storage"
    SnapSyncState(phase = "ByteCodeAndStorageSync").phaseDescription(TuiGlyphs.Ascii) shouldBe
      "[S]  Downloading bytecodes & storage"
    SnapSyncState(phase = "Completed").phaseDescription(TuiGlyphs.Ascii) shouldBe "[ok] SNAP sync complete"
  }

  // FR-007: five worm rows, in order, in both glyph sets.
  it should "render five SNAP worm rows: Overall, Accounts, Storage, Bytecode, Healing" taggedAs (UnitTest) in {
    for set <- Seq(GlyphSet.Emoji, GlyphSet.Ascii) do
      val g = TuiGlyphs.of(set)
      val lines = rows(TuiFixtures.snapStorageMordor(set), 120, 40)
      val snap = wormRows(lines, g).filterNot(_.trim.startsWith("Blocks"))
      withClue(s"$set:\n${lines.mkString("\n")}\n") {
        snap.map(_.trim.takeWhile(_ != ' ')) shouldBe snapLabels
        snap(1) should include(s"${g.worm.text}[COMPLETE ${g.check.text}]${g.brain.text}") // accounts are done
        snap(2) should include(s"${g.brain.text} 37%") // storage 119.6M of 323M slots
        // Bytecodes found so far: their total is not known until all are found, so no percentage (B-044).
        snap(3) should include(s"${g.worm.text}[ACTIVE]")
        snap(3) should include("412.0K @ 310/s")
        snap(4) should include(s"${g.worm.text}[QUEUED]${g.brain.text}") // healing
      }
  }

  it should "show the fields the probe used to drop: contracts, chain download, phase time" taggedAs (UnitTest) in {
    val text = rows(TuiFixtures.snapStorageMordor(), 160, 48).mkString("\n")
    // At tier L the logo column narrows the body; the row still keeps its rate and its contract count.
    text should include("119.6M slots @ 21.4K/s · 2,114/5,702 contracts")
    text should include("headers 4.2M / 9.1M")
    // Slice 3: the time in the phase is on the Sync view's phase row.
    rows(TuiFixtures.snapStorageMordor().withView(TuiView.Sync), 160, 48).mkString("\n") should include("phase 1h 8m")
  }

  // Slice 1 review W4: at the tier L minimum the logo column leaves the body 93 columns. The rate survives; the contract
  // count, last on the row, is what is cut.
  it should "keep the Storage rate at the tier L minimum, 140×45, and cut the contract count" taggedAs (UnitTest) in {
    val lines = rows(TuiFixtures.snapStorageMordor(), 140, 45)
    lines.exists(_.contains("=+#+.")) shouldBe true // the logo column is drawn
    val storage = lines.find(_.trim.startsWith("Storage")).get
    storage should include("119.6M slots @ 21.4K/s")
    (storage should not).include("contracts")
  }

  it should "draw the network name the same under a Turkish default locale" taggedAs (UnitTest) in {
    val text = TuiFixtures.withDefaultLocale(TuiFixtures.Turkish)(rows(TuiFixtures.sepoliaNearHead(), 100, 40).mkString)
    text should include("sepolia (11155111)")
    text should include("7,412,009 → 7,412,011")
  }

  it should "keep each SNAP row's rate on an 80-column screen" taggedAs (UnitTest) in {
    val lines = rows(TuiFixtures.snapStorageMordor(), 80, 24)
    lines.find(_.trim.startsWith("Storage")).get should include("119.6M slots @ 21.4K/s")
    lines.find(_.trim.startsWith("Bytecode")).get should include("@ 310/s")
  }

  // Live run 2026-11-08: a restarted node's counters start at zero, and a finished storage row read "0 slots".
  it should "show no zero count on a finished SNAP row" taggedAs (UnitTest) in {
    val validating = SnapSyncState(phase = "StateValidation", bytecodesDownloaded = 1, nodesHealed = 25_400)
    val lines = rows(TuiState.initial.withSnapSyncState(validating), 120, 40)
    def row(label: String) = lines.find(_.trim.startsWith(label)).get
    row("Storage") should include("🪱[COMPLETE ✓]🧠")
    (row("Storage") should not).include("0 slots")
    row("Accounts").trim shouldBe "Accounts 🪱[COMPLETE ✓]🧠"
    row("Healing") should include("25.4K nodes")
  }

  it should "mark a finished bytecode download complete" taggedAs (UnitTest) in {
    val base = TuiFixtures.snapStorageMordor()
    val done = base.copy(snapSyncState = base.snapSyncState.map(_.copy(bytecodeComplete = true)))
    rows(done, 120, 40).find(_.trim.startsWith("Bytecode")).get should include("🪱[COMPLETE ✓]🧠")
  }

  // Live run 2026-11-07: the account count and its rate were drawn twice (the phase's "primary metric" and the
  // per-type detail line).
  it should "show each SNAP count and rate once" taggedAs (UnitTest) in {
    val snap = SnapSyncState(
      phase = "AccountRangeSync",
      accountsSynced = 1_234_567L,
      recentAccountsPerSec = 2_345.0,
      estimatedTotalAccounts = 4_000_000L,
      phaseProgress = 30
    )
    val text = rows(TuiState.initial.withBlockInfo(0, 9_000_000).withSnapSyncState(snap), 120, 40).mkString("\n")
    occurrences(text, "1.2M") shouldBe 1
    occurrences(text, "2.3K/s") shouldBe 1
    (text should not).include("Current Rate")
  }

  it should "render one Blocks worm in regular sync" taggedAs (UnitTest) in {
    for set <- Seq(GlyphSet.Emoji, GlyphSet.Ascii) do
      val lines = rows(TuiFixtures.regularSyncEtc(set), 120, 40)
      val worms = wormRows(lines, TuiGlyphs.of(set))
      worms should have size 1
      worms.head.trim should startWith("Blocks")
      worms.head should include("18,402,113 / 21,884,102")
  }

  it should "draw every row with the ASCII set when it is selected" taggedAs (UnitTest) in {
    for (name, fixture) <- TuiFixtures.all; (w, h) <- Seq((80, 24), (120, 40), (160, 48)) do
      val text = rows(fixture(GlyphSet.Ascii), w, h).mkString("\n")
      withClue(s"$name at ${w}x$h:\n$text\n")(all(text.map(_.toInt)) should be < 0x7f)
  }

  // B-044: the running phase's row draws the monitor's phaseProgress, which never falls within a phase.
  it should "draw the running phase's row from phaseProgress, not from the raw counts" taggedAs (UnitTest) in {
    def row(snap: SnapSyncState, label: String) =
      rows(TuiState.initial.withSnapSyncState(snap).copy(motion = Motion.Reduced), 120, 40)
        .find(_.trim.startsWith(label))
        .get
    // 1.2M of an estimated 4.0M accounts is 30 %, but the monitor says 41: the row says 41.
    val accounts = SnapSyncState(
      phase = "AccountRangeSync",
      accountsSynced = 1_200_000L,
      estimatedTotalAccounts = 4_000_000L,
      storageSlotsSynced = 5_000L,
      estimatedTotalSlots = 50_000L,
      phaseProgress = 41
    )
    row(accounts, "Accounts") should include("🧠 41%")
    // Storage found during the account phase is a count so far: ACTIVE, no percentage.
    row(accounts, "Storage") should include("[ACTIVE]")
    (row(accounts, "Storage") should not).include("%")
    val storage = accounts.copy(phase = "ByteCodeAndStorageSync", phaseProgress = 12)
    row(storage, "Storage") should include("🧠 12%")
  }

  // prism, slice 3 review: the views say so when they have nothing to show yet.
  it should "say what is not known yet on each view" taggedAs (UnitTest) in {
    def text(s: TuiState, w: Int = 120, h: Int = 40) = rows(s, w, h).mkString("\n")
    text(TuiState.initial.withView(TuiView.Peers)) should include("Peers   not polled yet")
    val noPeers = TuiFixtures.withPeers(TuiFixtures.syncedEtc(), Sourced.Fresh(Nil, TuiFixtures.Now))
    text(noPeers.withView(TuiView.Peers)) should include("no handshaked peer")
    val silent = TuiFixtures.withPeers(TuiFixtures.syncedEtc(), Sourced.Unresponsive("no reply within 500ms"))
    text(silent.withView(TuiView.Peers)) should include("peer manager Unresponsive (no reply within 500ms)")
    text(TuiState.initial.withView(TuiView.Chain)) should include("Chain   not read yet")
    val noHead = TuiFixtures.sepoliaNearHead()
    val unread = noHead.copy(snapshot = noHead.snapshot.map(s => s.copy(local = s.local.copy(headTimestamp = None))))
    text(unread) should include("Forks   head timestamp not read yet")
    text(unread.withView(TuiView.Chain)) should include("head timestamp not read yet")
  }

  it should "say how many more peers there are when the Peers table runs out of rows" taggedAs (UnitTest) in {
    val lines = rows(TuiFixtures.syncedEtc().withView(TuiView.Peers), 80, 24)
    lines.exists(_.trim.matches("""\+ \d+ more""")) shouldBe true
    rows(TuiFixtures.lowDiskEtc().withView(TuiView.Peers), 80, 24).exists(_.contains("more")) shouldBe false // 6 fit
  }

  // prism, slice 3 review: during SNAP the sync controller reports no target and the local best block is 0.
  it should "show SNAP's pivot as the Block cell's target and the fork position while SNAP runs" taggedAs (UnitTest) in {
    val snap = TuiFixtures.snapStorageMordor()
    val text = rows(snap, 80, 24).mkString("\n")
    text should include("Block  0 → 9,118,000 (pivot)")
    // Mordor's Spiral is at 9,957,000, past the pivot: the timeline stands at the pivot, not at block 0.
    text should include("Mystique ✓")
    text should include("Spiral @ 9,957,000 (839.0K away)")
  }

  it should "say only Unresponsive in the Peers cell; the alert row names the source" taggedAs (UnitTest) in {
    val row = rows(TuiFixtures.unresponsiveEtc(), 80, 24).find(_.trim.startsWith("Peers")).get
    row should include("Peers   Unresponsive ")
    (row should not).include("(peer")
  }
