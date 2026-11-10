package com.chipprbots.ethereum.console

import java.time.Duration

import org.scalacheck.Gen
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

import com.chipprbots.ethereum.testing.Tags.*

/** Frame geometry over generated states: every frame is exactly `height` rows of exactly `width` columns, whatever the
  * state holds (spec 027 FR-001, SC-001).
  */
class TuiWidthPropertySpec extends AnyFlatSpec with Matchers with ScalaCheckPropertyChecks:

  private val renderers = Seq(TuiRenderer(TuiConfig.default), TuiRenderer(TuiConfig.minimal))
  private val themes = Seq(TuiTheme.Ansi16, TuiTheme.Ansi256, TuiTheme.Mono)

  /** Text with the characters that break naive width arithmetic: emoji (two columns, surrogate pairs), CJK (two
    * columns), combining marks (zero) and Latin accents.
    */
  private val awkwardChar: Gen[String] = Gen.oneOf(
    Gen.alphaNumChar.map(_.toString),
    Gen.oneOf(" ", "-", "/", ".", ":"),
    Gen.oneOf("🪱", "🧠", "🚀", "😀", "💾", "✅", "✨"),
    Gen.oneOf("中", "文", "字", "符", "ネ", "ッ", "ト", "한"),
    Gen.oneOf("é", "ñ", "ü", "é", "ø"),
    Gen.oneOf("◆", "●", "…", "─", "│")
  )
  private val text: Gen[String] = Gen.chooseNum(0, 140).flatMap(n => Gen.listOfN(n, awkwardChar).map(_.mkString))
  private val count: Gen[Long] =
    Gen.frequency(4 -> Gen.chooseNum(0L, 30_000_000L), 1 -> Gen.chooseNum(0L, Long.MaxValue / 4))
  private val rate: Gen[Double] = Gen.oneOf(Gen.chooseNum(0.0, 100_000.0), Gen.const(0.0), Gen.const(1e12))

  private val phase: Gen[String] = Gen.oneOf(
    "AccountRangeSync",
    "ByteCodeAndStorageSync",
    "StateHealing",
    "StateValidation",
    "ChainDownloadCompletion",
    "Completed",
    "Idle"
  )

  private val snap: Gen[SnapSyncState] =
    for
      p <- phase
      accounts <- count
      bytecodes <- count
      slots <- count
      nodes <- count
      estAccounts <- count
      estBytecodes <- count
      estSlots <- count
      r <- rate
      progress <- Gen.chooseNum(-10, 200)
      elapsed <- Gen.chooseNum(0.0, 1e7)
      eta <- Gen.option(Gen.chooseNum(0L, 10_000_000L))
      contracts <- Gen.chooseNum(0, 1_000_000)
      chain <- Gen.chooseNum(0L, 30_000_000L)
      done <- Gen.oneOf(true, false)
    yield SnapSyncState(
      phase = p,
      accountsSynced = accounts,
      bytecodesDownloaded = bytecodes,
      storageSlotsSynced = slots,
      nodesHealed = nodes,
      elapsedSeconds = elapsed,
      recentAccountsPerSec = r,
      recentBytecodesPerSec = r,
      recentSlotsPerSec = r,
      recentNodesPerSec = r,
      phaseProgress = progress,
      estimatedTotalAccounts = estAccounts,
      estimatedTotalBytecodes = estBytecodes,
      estimatedTotalSlots = estSlots,
      etaSeconds = eta,
      storageContractsCompleted = contracts / 2,
      storageContractsTotal = contracts,
      chainHeaders = BigInt(chain / 2),
      chainBodies = BigInt(chain / 3),
      chainReceipts = BigInt(chain / 4),
      chainTarget = BigInt(chain),
      bytecodeComplete = done,
      phaseElapsedSeconds = elapsed / 2
    )

  private val peer: Gen[TuiPeer] =
    for
      id <- text
      address <- text
      incoming <- Gen.oneOf(true, false)
      ago <- Gen.chooseNum(-100L, 100_000_000L)
    yield TuiPeer(id, address, incoming, TuiFixtures.Now.minusSeconds(ago))

  private val local: Gen[LocalSample] =
    for
      disk <- Gen.option(Gen.oneOf(Gen.chooseNum(0L, Long.MaxValue), Gen.const(-1L)))
      heap <- Gen.option(Gen.zip(count, count, Gen.option(Gen.chooseNum(-1.0, 2.0))))
      discovery <- Gen.option(Gen.zip(count, count, count))
      head <- Gen.option(Gen.chooseNum(0L, 4_000_000_000L))
      counters <- Gen.option(count)
      pivot <- Gen.option(count)
    yield LocalSample(
      diskFree = disk,
      jvm = heap.map((u, m, gc) => JvmSample(u, m, gc)),
      discovery = discovery.map((k, p, b) => DiscoverySample(k, p, b)),
      headTimestamp = head,
      snapCounters = counters.map(n =>
        SnapCounters(
          accountRequests = n,
          accountFailures = n,
          storageRequests = n,
          storageFailures = n,
          bytecodeRequests = n,
          bytecodeFailures = n,
          healingRequests = n,
          healingFailures = n,
          timeouts = n,
          snapCapablePeers = n,
          blacklisted = n
        )
      ),
      snapPivot = pivot
    )

  /** A poll: each source fresh, stale or unresponsive, with awkward text in its reason and its peers. */
  private def snapshot(peers: Int, maxPeers: Int, current: Long, best: Long): Gen[NodeStatusSnapshot] =
    def sourced[A](value: Gen[A]): Gen[Sourced[A]] =
      Gen.oneOf(
        value.map(Sourced.Fresh(_, TuiFixtures.Now)),
        value.map(Sourced.Stale(_, TuiFixtures.Now.minusSeconds(3))),
        text.map(Sourced.Unresponsive(_))
      )
    for
      list <- Gen.listOfN(peers.min(60), peer)
      peerSource <- sourced(Gen.const(list))
      sampledAgo <- Gen.option(Gen.chooseNum(0L, 1_000L))
      status <- text
      running <- Gen.oneOf(true, false)
      syncSource <- sourced(
        Gen.const(SyncSample(current, best, status, None, sampledAgo.map(TuiFixtures.Now.minusSeconds), running))
      )
      l <- local
    yield NodeStatusSnapshot(TuiFixtures.Now, maxPeers, current, peerSource, syncSource, l)

  private val state: Gen[TuiState] =
    for
      network <- text
      connection <- text
      syncStatus <- text
      dataDir <- text
      peers <- Gen.chooseNum(0, 500)
      maxPeers <- Gen.chooseNum(0, 500)
      current <- count
      best <- count
      uptime <- Gen.chooseNum(-100L, 100_000_000L)
      snapState <- Gen.option(snap)
      glyphs <- Gen.oneOf(GlyphSet.Emoji, GlyphSet.Ascii)
      baselineAgo <- Gen.option(Gen.chooseNum(0L, 100_000L))
      tick <- Gen.chooseNum(0L, 200L)
      motion <- Gen.oneOf(Motion.values.toSeq)
      splashUntil <- Gen.option(Gen.chooseNum(0L, 40L))
      celebrationAt <- Gen.option(Gen.chooseNum(0L, 200L))
      backAtHead <- Gen.option(Gen.chooseNum(0L, 200L))
      advancedAgo <- Gen.option(Gen.chooseNum(0L, 1_000L))
      view <- Gen.oneOf(TuiView.values.toSeq)
      helpOpen <- Gen.oneOf(false, false, true)
      chain <- Gen.option(Gen.oneOf(TuiFixtures.chainEtc, TuiFixtures.chainMordor, TuiFixtures.chainSepolia))
      polled <- Gen.option(snapshot(peers, maxPeers, current, best))
      zeroSince <- Gen.option(Gen.chooseNum(0L, 100_000L))
    yield TuiState(
      networkName = network,
      connectionStatus = connection,
      peerCount = peers,
      maxPeers = maxPeers,
      currentBlock = current,
      bestBlock = best,
      syncStatus = syncStatus,
      startTime = TuiFixtures.Now.minus(Duration.ofSeconds(uptime)),
      nodeSettings = NodeSettings(dataDir = dataDir, network = network, syncMode = syncStatus, maxPeers = maxPeers),
      snapSyncState = snapState,
      syncBaseline = baselineAgo.map(ago => SyncBaseline(current / 2, TuiFixtures.Now.minusSeconds(ago))),
      glyphs = glyphs,
      now = TuiFixtures.Now,
      tick = tick,
      motion = motion,
      splashUntilTick = splashUntil,
      celebrationStartedTick = celebrationAt,
      backAtHeadTick = backAtHead,
      lastAdvanceAt = advancedAgo.map(TuiFixtures.Now.minusSeconds),
      view = view,
      helpOpen = helpOpen,
      chain = chain,
      snapshot = polled,
      zeroPeersSince = zeroSince.map(TuiFixtures.Now.minusSeconds)
    )

  private val size: Gen[(Int, Int)] = Gen.frequency(
    20 -> Gen.zip(Gen.chooseNum(20, 200), Gen.chooseNum(3, 80)),
    1 -> Gen.const((1, 1))
  )

  private def assertExact(s: TuiState, width: Int, height: Int, r: TuiRenderer, theme: TuiTheme): Unit =
    val lines = r.render(s, width, height, theme)
    withClue(s"${width}x$height ${s.glyphs}:\n${lines.map(_.toString).mkString("\n")}\n") {
      lines should have size height.toLong
      all(lines.map(_.columnLength())) shouldBe width
    }

  "TuiRenderer" should "draw exactly height × width for 1,000 generated states, widths 20–200, heights 3–80 and 1×1" taggedAs (
    UnitTest
  ) in {
    val cases = Gen.zip(state, size, Gen.oneOf(renderers), Gen.oneOf(themes))
    forAll(cases, minSuccessful(1000)) { case (s, (w, h), r, theme) => assertExact(s, w, h, r, theme) }
  }

  it should "draw every fixture exactly at 1×1 in both glyph sets" taggedAs (UnitTest) in {
    for (_, fixture) <- TuiFixtures.all; set <- GlyphSet.values; r <- renderers do
      assertExact(fixture(set), 1, 1, r, TuiTheme.Ansi16)
  }

  it should "draw the splash and its hand-over exactly, at every size" taggedAs (UnitTest) in {
    val cases = Gen.zip(Gen.chooseNum(1, 200), Gen.chooseNum(1, 80), Gen.chooseNum(0L, 40L), Gen.oneOf(renderers))
    forAll(cases, minSuccessful(300)) { case (w, h, tick, r) =>
      assertExact(TuiFixtures.splash(GlyphSet.Emoji, tick), w, h, r, TuiTheme.Ansi16)
      assertExact(TuiFixtures.splash(GlyphSet.Ascii, tick), w, h, r, TuiTheme.Mono)
    }
  }
