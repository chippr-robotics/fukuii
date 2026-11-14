package com.chipprbots.ethereum.console

import org.scalatest.BeforeAndAfterAll
import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.console.panels.EnginePanels
import com.chipprbots.ethereum.console.panels.RpcPanels
import com.chipprbots.ethereum.testing.Tags.*

/** The RPC view (spec 029 FR-038 to FR-041): the transports, the Engine Synchronization Monitor or the mining monitor,
  * the call log and the methods. Golden frames at three sizes in both glyph sets; [[TuiGoldenFiles]] says how to
  * regenerate them. The view on a node whose RPC was not read is `rpc-*-synced-*` in [[TuiViewGoldenSpec]].
  */
class RpcViewSpec extends AnyFlatSpec with Matchers with OptionValues with BeforeAndAfterAll with TuiGoldenFiles:

  protected val goldenTerminal = new TuiTestTerminal()
  override def afterAll(): Unit = goldenTerminal.close()

  private val renderer = TuiRenderer(TuiConfig.default)
  private val sizes: Seq[(Int, Int)] = Seq((80, 24), (120, 40), (160, 48))

  private def rpc(s: TuiState): TuiState = s.withView(TuiView.Rpc).copy(motion = Motion.Reduced)

  /** The view's rows as text, without the sidebar's columns. */
  private def rows(s: TuiState, w: Int = 160, h: Int = 48): Seq[String] =
    TuiFixtures.panelRows(visibleFrame(renderer.render(rpc(s), w, h)).split("\n", -1).toSeq, w, h)

  private def row(s: TuiState, label: String, w: Int = 160, h: Int = 48): String =
    rows(s, w, h).find(_.startsWith(s" $label ")).value

  private def withCl(s: TuiState, f: ClSample => ClSample): TuiState =
    TuiFixtures.withLocal(s, l => l.copy(cl = l.cl.map(f)))

  private val goldens: Seq[(String, GlyphSet => TuiState)] = Seq(
    "engine" -> (g => TuiFixtures.rpcSepolia(g)),
    "exposed" -> (g => TuiFixtures.rpcExposedSepolia(g)),
    "mining" -> (g => TuiFixtures.rpcEtc(g))
  )

  for (name, fixture) <- goldens; (w, h) <- sizes; set <- GlyphSet.values do
    val file = s"rpc-${Tier.of(w, h).toString.toLowerCase}-$name-${set.toString.toLowerCase}.txt"

    "The RPC view" should s"match $file" taggedAs (UnitTest) in {
      val lines = renderer.render(rpc(fixture(set)), w, h)
      lines should have size h.toLong
      all(lines.map(_.columnLength())) shouldBe w
      matchGolden(file, visibleFrame(lines))
    }

  it should "leave the last column blank on every row but the rules, at every size" taggedAs (UnitTest) in {
    for (w, h) <- sizes; (_, fixture) <- goldens; set <- GlyphSet.values do
      val text = renderer.render(rpc(fixture(set)), w, h).map(l => visibleFrame(Seq(l)))
      all(text.filterNot(l => l.endsWith("──") || l.endsWith("--")).map(_.last)) shouldBe ' '
  }

  // ---- transports ---------------------------------------------------------------------------------------------------

  "A transport" should "say whether it listens, where, what it serves, how it authenticates and what it was asked" taggedAs (
    UnitTest
  ) in {
    val s = TuiFixtures.rpcSepolia()
    val http = row(s, "HTTP")
    http should include(
      "listening · 127.0.0.1:8546 · eth net web3 fukuii · no authentication · 121,340 calls · 241 failed"
    )
    row(s, "IPC") should include("restricted to the socket file's owner")
    // The Engine API's own calls are the monitor's: this row counts what it passed on to JSON-RPC, and says so.
    row(s, "Engine") should include(
      "listening · 127.0.0.1:8551 · engine eth net web3 · authenticated by JWT · 1,204 eth, net and web3 calls passed on"
    )
    // One that is off says that and nothing more: its address is not where anything listens.
    row(s, "WS").trim shouldBe "WS      off"
    // A PoW node has no Engine API row at all.
    rows(TuiFixtures.rpcEtc()).exists(_.startsWith(" Engine ")) shouldBe false
  }

  it should "not be said to listen until its server has said so, and say when it could not bind" taggedAs (UnitTest) in {
    val s = TuiFixtures.rpcSepolia()
    def http(listening: Option[Boolean]): String =
      val sample = s.snapshot.value.local.rpc.value
      row(
        TuiFixtures.withRpc(
          s,
          sample.copy(transports =
            sample.transports.map(t => if t.kind == TransportKind.Http then t.copy(listening = listening) else t)
          )
        ),
        "HTTP"
      )
    http(None) should include("enabled; its server has not said it is bound")
    (http(None) should not).include("listening")
    http(Some(false)) should include("NOT LISTENING")
    row(TuiFixtures.rpcExposedSepolia(), "WS") should include("NOT LISTENING: it could not start, or was closed")
  }

  "A sensitive namespace served to other hosts" should "be a warning above the transports, naming the namespaces" taggedAs (
    UnitTest
  ) in {
    for (w, h) <- sizes do
      val all = rows(TuiFixtures.rpcExposedSepolia(), w, h)
      val warning = all.indexWhere(_.contains("HTTP serves debug, admin to other hosts"))
      withClue(s"${w}x$h: ") {
        warning should be >= 0
        warning should be < all.indexWhere(_.startsWith(" HTTP "))
      }
    rows(TuiFixtures.rpcSepolia()).exists(_.contains("to other hosts")) shouldBe false
    // Said whole where there is room, in fewer parts where there is not: never cut.
    rows(TuiFixtures.rpcExposedSepolia()).find(_.contains("to other hosts")).value should include(
      "HTTP serves debug, admin to other hosts · bound to 0.0.0.0:8546 · no authentication"
    )
    val narrow = rows(TuiFixtures.rpcExposedSepolia(), 80, 24).find(_.contains("to other hosts")).value
    narrow.trim should endWith("bound to 0.0.0.0:8546")
    (narrow should not).include("…")
  }

  "A short screen" should "keep the failed authentications before the blocks and the calls" taggedAs (UnitTest) in {
    val short = rows(TuiFixtures.rpcExposedSepolia(), 80, 24)
    short.exists(_.startsWith(" Link ")) shouldBe true
    short.find(_.startsWith(" Auth ")).value should include("41 requests refused")
    short.exists(_.startsWith(" Payload ")) shouldBe false
    // Before any call, a kind of call is said not to have been called yet.
    rows(TuiFixtures.rpcExposedSepolia()).find(_.startsWith(" Calls ")).value should include(
      "forkchoiceUpdated not called yet"
    )
  }

  "The rate limit" should "say what it is and how many it refused, or that it is off" taggedAs (UnitTest) in {
    row(TuiFixtures.rpcExposedSepolia(), "Limit") should include(
      "HTTP: one request each 5s from an address · 12 refused"
    )
    row(TuiFixtures.rpcSepolia(), "Limit").trim shouldBe "Limit   off"
    // An interval is said as it was configured: half a second is not a second.
    RpcPanels.interval(java.time.Duration.ofMillis(500)) shouldBe "500 ms"
    RpcPanels.interval(java.time.Duration.ofMillis(1_500)) shouldBe "1.5 s"
    RpcPanels.interval(java.time.Duration.ofSeconds(90)) shouldBe "1m 30s"
  }

  // prism, slice 5 (blocker): "none (TLS)" was a text the panel compared with "none", so an HTTPS transport read
  // "authenticated by none (TLS)".
  "An HTTPS transport" should "be said to have no authentication, and that it is encrypted" taggedAs (UnitTest) in {
    val s = TuiFixtures.rpcSepolia()
    val sample = s.snapshot.value.local.rpc.value
    val https = sample.transports.map(t => if t.kind == TransportKind.Http then t.copy(tls = true) else t)
    val http = row(TuiFixtures.withRpc(s, sample.copy(transports = https)), "HTTP")
    http should include("no authentication (TLS)")
    (http should not).include("authenticated by")
    for auth <- TransportAuth.values; tls <- Seq(true, false) do
      val said = RpcPanels.authentication(sample.transports.head.copy(auth = auth, tls = tls))
      withClue(s"$auth, tls $tls: ") {
        said.contains("no authentication") shouldBe (auth == TransportAuth.None)
        (said should not).include("none")
      }
  }

  "A transport's failures" should "never be said without the calls they are of" taggedAs (UnitTest) in {
    for (w, h) <- sizes; fixture <- Seq(TuiFixtures.rpcSepolia(), TuiFixtures.rpcEtc()) do
      for line <- rows(fixture, w, h).filter(l => Seq(" HTTP ", " WS ", " IPC ", " Engine ").exists(l.startsWith)) do
        withClue(s"${w}x$h: $line: ")(if line.contains(" failed") then line should include(" call"))
  }

  // prism, slice 5: a long socket path took the row, and the authentication and the counts after it were dropped.
  "A long address" should "keep both its ends, and leave the row its other parts" taggedAs (UnitTest) in {
    val s = TuiFixtures.rpcEtc()
    val sample = s.snapshot.value.local.rpc.value
    val path = "/srv/blockchain/ethereum-classic/mainnet/archive-node-07/data/sockets/fukuii.ipc"
    val long = sample.transports.map(t => if t.kind == TransportKind.Ipc then t.copy(address = path) else t)
    val ipc = row(TuiFixtures.withRpc(s, sample.copy(transports = long)), "IPC")
    ipc should include("/srv/blockchain/eth")
    ipc should include("fukuii.ipc")
    ipc should include("…")
    ipc should include("restricted to the socket file's owner")
    ipc should include("228 calls")
    // The namespaces are the longest part and the first to go: the parts after them are not lost with them.
    val crowded = long.map(t =>
      if t.kind == TransportKind.Ipc then t.copy(apis = t.apis ++ Seq("qa", "miner", "txpool", "trace")) else t
    )
    val tight = row(TuiFixtures.withRpc(s, sample.copy(transports = crowded)), "IPC", 120, 40)
    tight should include("fukuii.ipc")
    tight should include("228 calls")
    (tight should not).include("txpool")
    val narrow = row(TuiFixtures.withRpc(s, sample.copy(transports = long)), "IPC", 80, 24)
    narrow should include("fukuii.ipc")
    narrow.length should be <= 80
  }

  "Every sensitive namespace exposed at once" should "be a warning that is shorter, not cut, on a narrow screen" taggedAs (
    UnitTest
  ) in {
    val s = TuiFixtures.rpcExposedSepolia()
    val sample = s.snapshot.value.local.rpc.value
    val all5 = Seq("personal", "debug", "admin", "qa", "miner")
    val exposed = sample.transports.map(t =>
      if t.kind == TransportKind.Ws then t.copy(exposed = all5, address = "0.0.0.0:8552") else t
    )
    val drawn = rows(TuiFixtures.withRpc(s, sample.copy(transports = exposed)), 80, 24)
    val warning = drawn.find(_.contains("WebSocket serves")).value
    (warning should not).include("…")
    warning should (include("personal, debug, admin, qa, miner to other hosts").or(
      include("5 sensitive namespaces to other hosts")
    ))
    warning.length should be <= 80
  }

  "A node whose RPC was not read" should "say so, and list no transport as off" taggedAs (UnitTest) in {
    val all = rows(TuiFixtures.syncedEtc())
    all.find(_.startsWith(" RPC ")).value should include("not read on this node")
    all.exists(_.startsWith(" HTTP ")) shouldBe false
    all.find(_.startsWith(" Methods ")).value should include("none called since the node started")
  }

  // ---- methods ------------------------------------------------------------------------------------------------------

  "The methods" should "be listed most called first, each with its calls, failures, percentiles and last call" taggedAs (
    UnitTest
  ) in {
    val all = rows(TuiFixtures.rpcEtc())
    val title = all.find(_.startsWith(" Methods ")).value
    // Eight methods and the calls for names the node has not: the second is not a method, and is not counted as one.
    title should include("8 called · 121,568 calls · 245 failed · slowest at p99 eth_getLogs 1.24 s")
    val header = all.indexWhere(l => l.contains("method") && l.contains("p99"))
    all(header).trim.split(" +").toSeq shouldBe Seq("method", "calls", "failed", "p50", "p95", "p99", "last")
    all(header + 1).trim.split("  +").toSeq shouldBe
      Seq("eth_blockNumber", "91,204", "0", "0.31 ms", "0.84 ms", "2.10 ms", "1s ago")
    all(header + 4) should include("eth_getLogs")
    all(header + 4) should include("1.24 s")
    // Each figure ends under its column's name.
    for name <- Seq("calls", "failed", "p50", "p95", "p99", "last") do
      val end = all(header).indexOf(name) + name.length
      withClue(s"$name: ")(all(header + 1).charAt(end - 1) should not be ' ')
      all(header + 1).lift(end).forall(_ == ' ') shouldBe true
  }

  private def methodsHeader(s: TuiState, w: Int, h: Int): Seq[String] =
    rows(s, w, h).find(l => l.contains("method") && l.contains("calls")).value.trim.split(" +").toSeq

  it should "drop whole columns on a narrow screen, the percentiles in the middle first" taggedAs (UnitTest) in {
    // 80 columns have no room for seven: p95 goes first, and the last call stays.
    methodsHeader(TuiFixtures.rpcEtc(), 80, 24) shouldBe Seq("method", "calls", "failed", "p50", "p99", "last")
    // A longer name leaves less: p50 goes next, then the last call.
    val s = TuiFixtures.rpcEtc()
    val sample = s.snapshot.value.local.rpc.value
    val longer = sample.methods.head.copy(method = "debug_getRawTransactionByBlockHash")
    methodsHeader(TuiFixtures.withRpc(s, sample.copy(methods = longer +: sample.methods.tail)), 80, 24) shouldBe
      Seq("method", "calls", "failed", "p99")
    val medium = sample.methods.head.copy(method = "eth_getTransactionByBlockHash")
    methodsHeader(TuiFixtures.withRpc(s, sample.copy(methods = medium +: sample.methods.tail)), 80, 24) shouldBe
      Seq("method", "calls", "failed", "p99", "last")
    methodsHeader(TuiFixtures.rpcEtc(), 160, 48) shouldBe Seq("method", "calls", "failed", "p50", "p95", "p99", "last")
  }

  // prism, slice 5: the columns were as wide as the rows on screen, so a count gaining a digit, or a longer name
  // coming into view, dropped columns from under the reader.
  it should "show the same columns at a width whatever the figures are" taggedAs (UnitTest) in {
    val s = TuiFixtures.rpcEtc()
    val sample = s.snapshot.value.local.rpc.value
    val before = methodsHeader(s, 100, 32)
    val grown = sample.methods.map(m =>
      m.copy(calls = m.calls * 73, errors = m.errors * 11, lastAt = m.lastAt.map(_.minusSeconds(3_000)))
    )
    methodsHeader(TuiFixtures.withRpc(s, sample.copy(methods = grown)), 100, 32) shouldBe before
    // A method lower down than the screen shows changes nothing either: the widths are every method's.
    val short = sample.methods.take(2)
    methodsHeader(TuiFixtures.withRpc(s, sample.copy(methods = short)), 100, 32) shouldBe before
  }

  it should "cut a name too long for the screen, and never a figure" taggedAs (UnitTest) in {
    val s = TuiFixtures.rpcEtc()
    val sample = s.snapshot.value.local.rpc.value
    val long = sample.methods.head.copy(method = "debug_" + "x" * 58)
    val narrow = rows(TuiFixtures.withRpc(s, sample.copy(methods = long +: sample.methods.tail)), 80, 24)
    val line = narrow.find(_.contains("debug_xxx")).value
    line should include("…")
    line should include("91,204")
    // The figures beside it are still there: its calls, its failures and a percentile.
    methodsHeader(TuiFixtures.withRpc(s, sample.copy(methods = long +: sample.methods.tail)), 80, 24) shouldBe
      Seq("method", "calls", "failed", "p99")
    all(narrow.map(_.length)) should be <= 80
  }

  // prism, slice 5: a percentile under the histogram's range was drawn as exactly the range's start.
  it should "say a latency under the histogram's range is under it, not that it is the range's start" taggedAs (
    UnitTest
  ) in {
    val line = rows(TuiFixtures.rpcEtc()).find(_.contains("net_version")).value
    line should include("<0.10 ms")
    line should include(" 0.14 ms")
    RpcPanels.quantile(Quantile(java.time.Duration.ofNanos(100_000), below = true)) shouldBe "<0.10 ms"
    RpcPanels.quantile(Quantile(java.time.Duration.ofNanos(100_000))) shouldBe "0.10 ms"
  }

  "A latency" should "be said to three figures, in milliseconds or seconds" taggedAs (UnitTest) in {
    def said(nanos: Long): String = RpcPanels.latency(java.time.Duration.ofNanos(nanos))
    said(100_000) shouldBe "0.10 ms"
    said(310_000) shouldBe "0.31 ms"
    said(12_400_000) shouldBe "12.4 ms"
    said(212_000_000) shouldBe "212 ms"
    said(1_240_000_000L) shouldBe "1.24 s"
    said(84_000_000_000L) shouldBe "84 s"
    // The unit follows the figure as it rounds: three figures, never four.
    said(9_996_000) shouldBe "10.0 ms"
    said(99_960_000) shouldBe "100 ms"
    said(999_600_000) shouldBe "1.00 s"
    said(9_996_000_000L) shouldBe "10 s"
  }

  // ---- the Engine Synchronization Monitor ---------------------------------------------------------------------------

  "The monitor" should "say the link, who is at its other end, the blocks and how far apart they are" taggedAs (
    UnitTest
  ) in {
    val s = TuiFixtures.rpcSepolia()
    val engine = row(s, "Link")
    engine should include("connected")
    engine should include("Lighthouse v7.1.0 (cfb1f733)")
    engine should include("23 methods offered")
    engine should include("184,322 calls")
    val cl = s.snapshot.value.local.cl.value
    val blocks = row(s, "Blocks")
    blocks should include("head 7,412,011")
    blocks should include(
      s"safe ${TuiText.formatNumber(cl.safe.value)} (${TuiText.formatNumber(7_412_011L - cl.safe.value)} behind)"
    )
    blocks should include(s"finalized ${TuiText.formatNumber(cl.finalized.value)}")
  }

  it should "say what each kind of call was last answered, how long ago and how long it took" taggedAs (UnitTest) in {
    val calls = row(TuiFixtures.rpcSepolia(), "Calls")
    (calls should include).regex("""forkchoiceUpdated 3s ago VALID \d\.\d\d ms""")
    (calls should include).regex("""newPayload 3s ago VALID \d+ ms""")
    (calls should include).regex("""getPayload \d+m \d+s ago 41\.0 ms""")
    // A kind that is not among the calls kept is said to be none of them, not never.
    val s = withCl(
      TuiFixtures.rpcSepolia(),
      cl => cl.copy(calls = cl.calls.filterNot(_.method.startsWith("engine_getPayload")))
    )
    row(s, "Calls") should include("getPayload none of the last")
  }

  it should "say the payloads received by status and the ones built" taggedAs (UnitTest) in {
    val payloads = row(TuiFixtures.rpcSepolia(), "Payload")
    payloads should include("received (VALID")
    payloads should include("31 built")
    payloads should include("last built block 7,412,005 with 84 transactions 1m 27s ago")
    (payloads should not).include("could not be built")
    row(withCl(TuiFixtures.rpcSepolia(), _.copy(builds = PayloadBuilds(3, 2, None))), "Payload") should include(
      "2 could not be built"
    )
  }

  it should "say failed authentications by reason, the most frequent first, and no row when there are none" taggedAs (
    UnitTest
  ) in {
    val auth = row(TuiFixtures.rpcExposedSepolia(), "Auth")
    auth should include(
      "41 requests refused · 38 wrong signature (another secret) · 3 iat too far from now (clocks apart)"
    )
    rows(TuiFixtures.rpcSepolia()).exists(_.startsWith(" Auth ")) shouldBe false
    row(TuiFixtures.rpcExposedSepolia(), "Link") should include("waiting for its first forkchoiceUpdated")
    row(TuiFixtures.rpcExposedSepolia(), "Link") should include("the consensus client has not said what it is")
  }

  it should "say DISCONNECTED when the consensus client has gone quiet" taggedAs (UnitTest) in {
    val quiet = withCl(TuiFixtures.rpcSepolia(), _.copy(forkchoiceUpdated = Some(TuiFixtures.clCall(363))))
    row(quiet, "Link") should include("DISCONNECTED")
  }

  // FR-040: the link's animation moves once per call that arrived, and with nothing else.
  "The link's marker" should "move one place for each call, and stay where it is while none arrives" taggedAs (
    UnitTest
  ) in {
    val g = TuiGlyphs.Emoji
    val places = (0L to 30L).map(n => EnginePanels.link(n, g).indexOf('●'))
    places.sliding(2).foreach { case Seq(a, b) => (b - a == 1 || (a - b) == EnginePanels.LinkCols - 1) shouldBe true }
    places.take(EnginePanels.LinkCols).distinct should have size EnginePanels.LinkCols.toLong
    EnginePanels.link(7, g) shouldBe EnginePanels.link(7 + EnginePanels.LinkCols, g)
    EnginePanels.link(7, g).length shouldBe EnginePanels.link(0, g).length
    EnginePanels.link(3, TuiGlyphs.Ascii) shouldBe "EL ---o-------- CL"
    // The same calls drawn at another moment, another frame and under full motion: the marker has not moved.
    val s = TuiFixtures.rpcSepolia()
    val still = row(s, "Link")
    val later = s.copy(now = s.now.plusSeconds(2), tick = s.tick + 40, motion = Motion.Full)
    visibleFrame(renderer.render(later.withView(TuiView.Rpc), 160, 48))
      .split("\n")
      .find(_.contains(" Link "))
      .value should include(
      EnginePanels.link(184_322L, g)
    )
    still should include(EnginePanels.link(184_322L, g))
    row(withCl(s, _.copy(callsTotal = 184_323L)), "Link") should include(EnginePanels.link(184_323L, g))
    EnginePanels.link(184_322L, g) should not be EnginePanels.link(184_323L, g)
  }

  "The call log" should "list the last calls newest first: when, the method, what was told, how long, the block" taggedAs (
    UnitTest
  ) in {
    val s = TuiFixtures.rpcSepolia()
    val all = rows(s)
    all.find(_.startsWith(" Log ")).value should include("the last 42 of 184,322 calls · newest first · times in UTC")
    val header = all.indexWhere(l => l.contains("answered") && l.contains("told"))
    val newest = s.snapshot.value.local.cl.value.calls.last
    all(header + 1) should include("11:59:57")
    all(header + 1) should include(newest.method)
    all(header + 1) should include("VALID")
    val shown = all.drop(header + 1).takeWhile(_.trim.headOption.exists(_.isDigit))
    shown.size should be >= 6
    shown.map(_.trim.take(8)) shouldBe shown.map(_.trim.take(8)).sorted.reverse
    // A call with no status and one with no block show the glyph for none, never a 0.
    shown.find(_.contains("engine_forkchoiceUpdatedV3")).value.trim should endWith("—")
  }

  it should "say there is no call yet on a node its consensus client has not reached" taggedAs (UnitTest) in {
    rows(TuiFixtures.rpcExposedSepolia()).find(_.startsWith(" Log ")).value should include("no Engine API call yet")
  }

  it should "show the same columns at a width whatever the calls are, and end each figure under its name" taggedAs (
    UnitTest
  ) in {
    def header(s: TuiState, w: Int): String =
      rows(s, w, 40).find(l => l.contains("answered") && l.contains("told")).value
    val s = TuiFixtures.rpcSepolia()
    val before = header(s, 100).trim.split(" +").toSeq
    before shouldBe Seq("answered", "method", "told", "took", "block")
    // An error answer and a longer method come into the log: the columns stay.
    val odd = withCl(
      s,
      cl =>
        cl.copy(calls =
          cl.calls :+ cl.calls.last
            .copy(method = "engine_getPayloadBodiesByRangeV2", status = Some("error -38003"), block = None)
        )
    )
    header(odd, 100).trim.split(" +").toSeq shouldBe before
    val drawn = rows(s)
    val at = drawn.indexWhere(l => l.contains("answered") && l.contains("told"))
    val numbered = drawn.drop(at + 1).find(_.contains("7,412,0")).value
    numbered.trim should endWith("7,412,011")
    numbered.indexOf("7,412,011") + "7,412,011".length shouldBe drawn(at).indexOf("block") + "block".length
  }

  // prism, slice 5: what a consensus client says it is reached the screen through the control-character filter but
  // not through the ASCII glyph set's mapping.
  "A consensus client's own text" should "be drawn as any text from outside the node is, in both glyph sets" taggedAs (
    UnitTest
  ) in {
    val odd = ClClient("NB", "Nimbus™ 日本", "v25.9.2—rc", "cafe" * 15)
    val emoji = row(withCl(TuiFixtures.rpcSepolia(), _.copy(client = Some(odd))), "Link")
    emoji should include("Nimbus™ 日本 v25.9.2—rc")
    val ascii = row(withCl(TuiFixtures.rpcSepolia(GlyphSet.Ascii), _.copy(client = Some(odd))), "Link")
    ascii.forall(_ < 128) shouldBe true
    ascii should include("Nimbus")
    // The longest name a client may send, at 80 columns: said whole or left out, never cut.
    val long = ClClient("XX", "N" * 64, "v" + "9" * 63, "")
    val narrow = row(withCl(TuiFixtures.rpcSepolia(), _.copy(client = Some(long))), "Link", 80, 24)
    narrow.length should be <= 80
    (narrow should not).include("…")
    narrow should include("connected")
  }

  "The methods and the log together" should "each say what it has when the other has nothing" taggedAs (UnitTest) in {
    val s = TuiFixtures.rpcSepolia()
    val quiet = TuiFixtures.withRpc(s, s.snapshot.value.local.rpc.value.copy(methods = Vector.empty))
    val drawn = rows(quiet)
    drawn.find(_.startsWith(" Methods ")).value should include("none called since the node started")
    drawn.find(_.startsWith(" Log ")).value should include("the last 42 of 184,322 calls")
    drawn.exists(_.contains("engine_newPayloadV4")) shouldBe true
  }

  // ---- the mining monitor -------------------------------------------------------------------------------------------

  "The mining monitor" should "stand where the Engine monitor does on a node that mines, with ten minutes of history" taggedAs (
    UnitTest
  ) in {
    val s = TuiFixtures.rpcEtc()
    val all = rows(s)
    all.exists(_.startsWith(" Link ")) shouldBe false
    all.exists(_.startsWith(" Log ")) shouldBe false
    all.exists(_.startsWith(" Mining ")) shouldBe true
    val history = s.snapshot.value.local.mining.value.history
    val hashrate = all.find(l => l.trim.startsWith("Rate") && l.contains("highest")).value
    hashrate should include(s"now ${TuiText.formatHashrate(history.last.hashrate)}")
    // 120 readings five seconds apart span 9m 55s: the caption says what was recorded, not a round figure.
    hashrate should include(s"highest ${TuiText.formatHashrate(history.map(_.hashrate).max)} in 9m 55s")
    hashrate.count("▁▂▃▄▅▆▇█".contains(_)) should be >= 8
    // The chart is drawn against the highest the caption names: a full glyph stands where the peak was.
    hashrate should include("█")
    val work = all.find(l => l.trim.startsWith("Work") && l.contains("highest")).value
    work should include(s"now ${history.last.getWorkPerMinute.value}/min")
  }

  it should "draw no chart of a node that reports no hashrate and was never asked for work" taggedAs (UnitTest) in {
    val idle = TuiFixtures.withLocal(
      TuiFixtures.rpcEtc(),
      l =>
        l.copy(mining =
          l.mining.map(m => m.copy(history = m.history.map(_.copy(hashrate = 0, getWorkPerMinute = None))))
        )
    )
    rows(idle).exists(l => l.contains("highest")) shouldBe false
    def spark(values: Seq[Long], cols: Int, g: TuiGlyphs = TuiGlyphs.Emoji): String =
      panels.ObservatoryPanels.spark(values.map(Some(_)), cols, g)
    spark(Seq(0L, 0L, 0L), 3) shouldBe "▁▁▁"
    spark(Seq(5L, 10L), 8, TuiGlyphs.Ascii) shouldBe "=#"
    // More readings than glyphs: every reading is in some glyph, and a glyph is the highest of its own.
    spark(Seq(1L, 8L, 2L, 2L, 4L, 4L), 3) shouldBe "█▃▅"
    spark((1L to 100L), 10).last shouldBe '█'
    (spark((1L to 100L), 10) should have).length(10)
  }

  // prism, slice 5: the caption said "in 10 min" whatever was recorded, the chart drew only its last readings while
  // the caption's highest was of all of them, and a rate not yet known was drawn and said as nothing.
  it should "span every reading kept, say how long they span, and leave what is not known as a gap" taggedAs (
    UnitTest
  ) in {
    def history(points: Vector[MiningPoint]): TuiState =
      TuiFixtures.withLocal(TuiFixtures.rpcEtc(), l => l.copy(mining = l.mining.map(_.copy(history = points))))
    // A peak in the first minute is still on an 80-column chart, which has room for a fifth of the readings.
    val peaked =
      TuiFixtures.miningHistory.zipWithIndex.map((p, i) => if i == 3 then p.copy(hashrate = 900_000_000L) else p)
    val narrow = rows(history(peaked), 80, 24).find(_.trim.startsWith("Rate")).value
    narrow should include("█")
    narrow.indexOf('█') should be < narrow.indexOf("now")
    narrow.count(_ == '█') shouldBe 1
    // Two minutes of readings are said to be two minutes.
    val young = rows(history(TuiFixtures.miningHistory.takeRight(25))).find(_.trim.startsWith("Rate")).value
    young should include("in 2m 0s")
    // A work rate that is not known yet: a gap in the chart, and no figure for now.
    val unknown =
      TuiFixtures.miningHistory.zipWithIndex.map((p, i) => if i >= 110 then p.copy(getWorkPerMinute = None) else p)
    val work = rows(history(unknown)).find(_.trim.startsWith("Work")).value
    work should include("now —")
    (work should not).include("now 0/min")
    panels.ObservatoryPanels.spark(Seq(Some(4L), None, Some(8L)), 3, TuiGlyphs.Emoji) shouldBe "▅ █"
  }

  it should "say its caption whole or shorter at 80 columns, never cut" taggedAs (UnitTest) in {
    val big = TuiFixtures.withLocal(
      TuiFixtures.rpcEtc(),
      l =>
        l.copy(mining = l.mining.map(m => m.copy(history = m.history.map(p => p.copy(hashrate = p.hashrate * 40_000)))))
    )
    val narrow = rows(big, 80, 24)
    all(narrow.map(_.length)) should be <= 80
    narrow.exists(l => l.trim.startsWith("Rate") && l.contains("…")) shouldBe false
    narrow.find(_.trim.startsWith("Rate")).value should include("now ")
  }
