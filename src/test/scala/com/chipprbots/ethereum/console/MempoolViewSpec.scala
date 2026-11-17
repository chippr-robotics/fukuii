package com.chipprbots.ethereum.console

import org.scalatest.BeforeAndAfterAll
import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.console.panels.MempoolPanels
import com.chipprbots.ethereum.testing.Tags.*

/** The Mempool view with the pool read whole (spec 029 FR-031 to FR-037): the pool, its flow, the Mempool Radar, the
  * senders, the counters and the pending transactions. Golden frames at three sizes in both glyph sets;
  * [[TuiGoldenFiles]] says how to regenerate them. The view before the first whole read is `mempool-*-synced-*` in
  * [[TuiViewGoldenSpec]].
  */
class MempoolViewSpec extends AnyFlatSpec with Matchers with OptionValues with BeforeAndAfterAll with TuiGoldenFiles:

  protected val goldenTerminal = new TuiTestTerminal()
  override def afterAll(): Unit = goldenTerminal.close()

  private val renderer = TuiRenderer(TuiConfig.default)
  private val sizes: Seq[(Int, Int)] = Seq((80, 24), (120, 40), (160, 48))

  private def mempool(s: TuiState): TuiState = s.withView(TuiView.Mempool).copy(motion = Motion.Reduced)

  /** The view's rows as text, without the sidebar's columns. */
  private def rows(s: TuiState, w: Int = 160, h: Int = 48): Seq[String] =
    TuiFixtures.panelRows(visibleFrame(renderer.render(mempool(s), w, h)).split("\n", -1).toSeq, w, h)

  private def row(s: TuiState, label: String, w: Int = 160, h: Int = 48): String =
    rows(s, w, h).find(_.contains(s" $label ")).value

  private val goldens: Seq[(String, GlyphSet => TuiState)] = Seq(
    "pool-etc" -> (g => TuiFixtures.mempoolEtc(g)),
    "pool-sepolia" -> (g => TuiFixtures.mempoolSepolia(g)),
    "pool-empty" -> (g => TuiFixtures.mempoolEmptyEtc(g))
  )

  for (name, fixture) <- goldens; (w, h) <- sizes; set <- GlyphSet.values do
    val file = s"mempool-${Tier.of(w, h).toString.toLowerCase}-$name-${set.toString.toLowerCase}.txt"

    "The Mempool view" should s"match $file" taggedAs (UnitTest) in {
      val lines = renderer.render(mempool(fixture(set)), w, h)
      lines should have size h.toLong
      all(lines.map(_.columnLength())) shouldBe w
      matchGolden(file, visibleFrame(lines))
    }

  "The pool's rows" should "say how many are pending, how full the pool is, the blobs, the oldest and this node's own" taggedAs (
    UnitTest
  ) in {
    val pool = row(TuiFixtures.mempoolSepolia(), "Pool")
    pool should include("1,240 pending")
    pool should include("30% of 4,096")
    (pool should not).include("read ") // a fresh reading does not say how old it is
    pool should include("73 blob")
    (pool should include).regex("oldest 1[45]m")
    pool should include("43 sent from this node")
    val flow = row(TuiFixtures.mempoolSepolia(), "Flow")
    flow should include("3.4 in/s")
    flow should include("2.9 mined/s")
    flow should include("0.1 dropped/s")
    flow should include("7 replaced in the 3m 12s watched")
  }

  it should "round how full the pool is down: a slot free is not 100%, a transaction held is not 0%" taggedAs (
    UnitTest
  ) in {
    val s = TuiFixtures.mempoolEtc()
    val pool = s.snapshot.value.pool.value
    row(TuiFixtures.withPool(s, pool.copy(size = 4_095)), "Pool") should include("99% of 4,096")
    row(TuiFixtures.withPool(s, pool.copy(size = 4_096)), "Pool") should include("100% of 4,096")
    row(TuiFixtures.withPool(s, pool.copy(size = 3)), "Pool") should include("<1% of 4,096")
    row(TuiFixtures.withPool(s, pool.copy(size = 0)), "Pool") should include(" 0% of 4,096")
  }

  it should "count blob transactions where the chain has them, and not say 0 blob on a chain that has none" taggedAs (
    UnitTest
  ) in {
    (row(TuiFixtures.mempoolEtc(), "Pool") should not).include("blob")
    val eth = TuiFixtures.mempoolSepolia()
    row(TuiFixtures.withPool(eth, eth.snapshot.value.pool.value.copy(blobs = 0)), "Pool") should include("0 blob")
  }

  // prism, slice 4: a poll that missed the pool emptied the view for a second. The last reading stays, and says its age.
  it should "say how old the reading is once the probe has not renewed it for three seconds" taggedAs (UnitTest) in {
    val s = TuiFixtures.mempoolEtc()
    val pool = s.snapshot.value.pool.value
    (row(TuiFixtures.withPool(s, pool.copy(at = TuiFixtures.Now.minusSeconds(2))), "Pool") should not).include("read ")
    row(TuiFixtures.withPool(s, pool.copy(at = TuiFixtures.Now.minusSeconds(4))), "Pool") should include(
      "142 pending · read 4s ago"
    )
  }

  it should "say it is measuring until the pool has been watched long enough for a rate" taggedAs (UnitTest) in {
    val s = TuiFixtures.mempoolEtc()
    val unmeasured = TuiFixtures.withPool(
      s,
      s.snapshot.value.pool.value.copy(arrivedPerSecond = None, includedPerSecond = None, droppedPerSecond = None)
    )
    val flow = row(unmeasured, "Flow")
    flow should include("in: measuring")
    flow should include("mined: measuring")
    (flow should not).include("/s")
  }

  it should "say how many left without being looked up, only when some did" taggedAs (UnitTest) in {
    val s = TuiFixtures.mempoolEtc()
    (row(s, "Flow") should not).include("told apart")
    val pool = s.snapshot.value.pool.value
    row(TuiFixtures.withPool(s, pool.copy(awaiting = 900)), "Flow") should include("900 that left not yet told apart")
    row(TuiFixtures.withPool(s, pool.copy(leftUnchecked = 1_200)), "Flow") should include(
      "1,200 that left never told apart"
    )
  }

  "The Mempool Radar" should "draw a row a tier from the most urgent, each with its count and where it starts" taggedAs (
    UnitTest
  ) in {
    val s = TuiFixtures.mempoolEtc()
    val all = rows(s)
    val fees = all.indexWhere(_.contains(" Radar "))
    all(fees) should include("gas prices against the price this node would suggest, 1.00 gwei")
    (all(fees) should not).include("base fee")
    row(TuiFixtures.mempoolSepolia(), "Radar") should include("tips against the tip this node would suggest, 1.50 gwei")
    row(TuiFixtures.mempoolSepolia(), "Radar") should include("above the base fee")
    val tiers = all.slice(fees + 1, fees + 5)
    tiers.map(_.trim.takeWhile(_ != ' ')) shouldBe Seq("urgent", "high", "normal", "low")
    val pool = s.snapshot.value.pool.value
    for (tier, line) <- FeeTier.values.toSeq.reverse.zip(tiers) do
      (line should include).regex(s""" ${pool.tiers.getOrElse(tier, 0)} +(from|below) """)
    tiers(0) should include("from 3.00 gwei")
    tiers(1) should include("from 1.50 gwei")
    tiers(2) should include("from 0.5 gwei")
    tiers(3) should include("below 0.5 gwei")
    pool.tiers.values.sum shouldBe pool.size
    // The fullest tier's bar is whole, and no bar is longer.
    val bars = tiers.map(_.count(_ == '█'))
    bars.max shouldBe MempoolPanels.BarCols
    tiers.map(_.count(c => c == '█' || c == '░')).distinct shouldBe Seq(MempoolPanels.BarCols)
  }

  // dev-node, slice 4, on Ephemery (a suggestion of 0.001 gwei): two tiers read "from 500,000 wei" beside two in
  // gwei, and 0.0015 gwei was rounded to 0.002, onto the tier above's 0.003.
  it should "say every boundary in gwei, however small the fees, each told apart from the next" taggedAs (UnitTest) in {
    val s = TuiFixtures.mempoolSepolia()
    val suggested = BigInt(1_000_000) // 0.001 gwei
    val low = TuiFixtures.withPool(
      s,
      TuiFixtures.poolSample(TuiFixtures.poolTxs(40, suggested, baseFee = true), Some(suggested))
    )
    val drawn = rows(low)
    val head = drawn.indexWhere(_.contains(" Radar "))
    drawn(head) should include("suggest, 0.001 gwei")
    val tiers = drawn.slice(head + 1, head + 5)
    tiers(0) should include("from 0.003 gwei")
    tiers(1) should include("from 0.0015 gwei")
    tiers(2) should include("from 0.0005 gwei")
    tiers(3) should include("below 0.0005 gwei")
    tiers.exists(_.contains(" wei")) shouldBe false
    panels.ObservatoryPanels.gweiOnly(BigInt(3)) shouldBe "0.000000003 gwei"
    panels.ObservatoryPanels.gweiOnly(BigInt(750_000_000)) shouldBe "0.75 gwei"
    panels.ObservatoryPanels.gweiOnly(BigInt(1_500_000_001L)) shouldBe "1.50 gwei"
    panels.ObservatoryPanels.gweiOnly(BigInt(0)) shouldBe "0 gwei"
  }

  it should "show a cell for a tier with one transaction among thousands, and none for an empty tier" taggedAs (
    UnitTest
  ) in {
    val s = TuiFixtures.mempoolEtc()
    val pool = s.snapshot.value.pool.value
    val lopsided = TuiFixtures.withPool(
      s,
      pool.copy(tiers = Map(FeeTier.Normal -> 5_000, FeeTier.Urgent -> 1, FeeTier.High -> 0))
    )
    val all = rows(lopsided)
    val fees = all.indexWhere(_.contains(" Radar "))
    all(fees + 1).count(_ == '█') shouldBe 1 // urgent: one in five thousand is still there
    all(fees + 2).count(_ == '█') shouldBe 0 // high: none
    all(fees + 4).count(_ == '█') shouldBe 0 // low: not in the map at all
  }

  it should "draw no tier while the node has no fee to suggest, and say so" taggedAs (UnitTest) in {
    val all = rows(TuiFixtures.mempoolEmptyEtc())
    all.find(_.contains(" Radar ")).value should include("the node has no fee to suggest yet")
    all.exists(_.trim.startsWith("urgent")) shouldBe false
  }

  it should "use ASCII bars in the ASCII glyph set" taggedAs (UnitTest) in {
    val all = rows(TuiFixtures.mempoolEtc(GlyphSet.Ascii))
    val fees = all.indexWhere(_.contains(" Radar "))
    all.slice(fees + 1, fees + 5).mkString.forall(_ < 128) shouldBe true
    all.slice(fees + 1, fees + 5).map(_.count(c => c == '#' || c == '.')).min should be >= MempoolPanels.BarCols
  }

  "The senders" should "be those with the most pending transactions, the most first" taggedAs (UnitTest) in {
    val s = TuiFixtures.mempoolEtc()
    val top = s.snapshot.value.pool.value.topSenders
    top.map(_._2) shouldBe top.map(_._2).sorted.reverse
    val senders = row(s, "Senders")
    senders should include(s"${top.head._1.take(6)}")
    senders should include(s" ${top.head._2}")
    row(TuiFixtures.mempoolEmptyEtc(), "Senders") should include("no pending transaction")
  }

  "The counters" should "name each refusal with its count, the most frequent first, and what was lost and fetched" taggedAs (
    UnitTest
  ) in {
    val s = TuiFixtures.mempoolEtc()
    val refused = row(s, "Refused")
    (refused should include).regex("Underpriced 1,204.*AlreadyKnown 311.*NonceTooLow 58.*NonceTooFarAhead 4")
    val lost = row(s, "Lost")
    lost should include("evicted for room 12")
    lost should include("expired unmined 96")
    lost should include("replaced 41")
    val fetch = row(s, "Fetch")
    fetch should include("announced 184,220")
    fetch should include("asked for 9,310")
    fetch should include("timed out 27")
    row(TuiFixtures.mempoolEmptyEtc(), "Refused") should include("none since the node started")
  }

  it should "say so on a node that does not count, and never show a zero for it" taggedAs (UnitTest) in {
    val uncounted = TuiFixtures.withLocal(TuiFixtures.mempoolEtc(), _.copy(poolCounters = None))
    row(uncounted, "Refused") should include("not counted on this node")
    rows(uncounted).exists(_.contains(" Lost ")) shouldBe false
  }

  "The queue" should "be said not to exist on this client, not shown as empty" taggedAs (UnitTest) in {
    row(TuiFixtures.mempoolEtc(), "Queue") should include("not kept by this client")
    row(TuiFixtures.syncedEtc(), "Queue") should include("not kept by this client")
  }

  "The view before the pool is read whole" should "show the count every view has, and say the pool is being read" taggedAs (
    UnitTest
  ) in {
    val all = rows(TuiFixtures.syncedEtc())
    all.mkString("\n") should include("reading the pool")
    all.find(_.contains(" Pool ")).value should include("142")
    all.exists(_.contains(" Pending ")) shouldBe false
  }

  "The pending transactions" should "be listed best-paying first, with what each pays and how long it has waited" taggedAs (
    UnitTest
  ) in {
    val s = TuiFixtures.mempoolSepolia()
    val pool = s.snapshot.value.pool.value
    pool.txs should have size PoolObserver.MaxListed.toLong
    pool.txs.map(_.tip) shouldBe pool.txs.map(_.tip).sorted.reverse
    val all = rows(s)
    all.find(_.contains(" Pending ")).value should include("the 500 best-paying of 1,240")
    val header = all.indexWhere(l => l.contains("hash") && l.contains("nonce") && l.contains("fee cap"))
    header should be >= 0
    val first = all(header + 1)
    first should include(pool.txs.head.hash.take(6))
    first should include(pool.txs.head.sender.take(6))
    first should include(panels.ObservatoryPanels.gwei(pool.txs.head.tip))
    first should include(panels.ObservatoryPanels.gwei(pool.txs.head.feeCap))
    row(TuiFixtures.mempoolEtc(), "Pending") should include("best-paying first")
    all.exists(l => l.contains(" blob") && l.contains("0x")) shouldBe true
  }

  it should "name its fee column for the chain: a tip above a base fee, a gas price without one" taggedAs (UnitTest) in {
    def header(s: TuiState): String = rows(s).find(l => l.contains("hash") && l.contains("nonce")).value
    header(TuiFixtures.mempoolSepolia()) should include(" tip ")
    header(TuiFixtures.mempoolEtc()) should include(" gas price ")
    // Without a base fee the cap is the gas price: it is not shown twice.
    (header(TuiFixtures.mempoolEtc()) should not).include("fee cap")
    header(TuiFixtures.mempoolEtc()) should include("kind")
    header(TuiFixtures.mempoolSepolia()) should include("fee cap")
  }

  it should "drop the kind, then the fee cap, for the whole table on a narrow screen, and line up in both glyph sets" taggedAs (
    UnitTest
  ) in {
    for set <- GlyphSet.values do
      val narrow = rows(TuiFixtures.mempoolSepolia(set), 80, 24)
      val at = narrow.indexWhere(l => l.contains("hash") && l.contains("nonce"))
      val header = narrow(at)
      (header should not).include("kind")
      header should include("fee cap")
      val first = narrow(at + 1)
      // Each column starts under its name.
      first.indexOf("0x") shouldBe header.indexOf("hash")
      first.indexOf("0x", first.indexOf("0x") + 1) shouldBe header.indexOf("from")
      first.indexOf("gwei") should be > header.indexOf("tip")
      (first should not).include("blob")
    val wide = rows(TuiFixtures.mempoolSepolia())
    wide.find(l => l.contains("hash") && l.contains("nonce")).value should include("kind")
  }

  // prism, slice 4: a nonce of a million or a fee of a million gwei was wider than its column and pushed the columns
  // after it out of line with their names.
  it should "widen a column for a value wider than it, and keep every column under its name" taggedAs (UnitTest) in {
    val s = TuiFixtures.mempoolSepolia()
    val pool = s.snapshot.value.pool.value
    val wide = pool.txs.head.copy(
      nonce = BigInt(12_345_678),
      tip = BigInt("1234567000000000"),
      feeCap = BigInt("9876543000000000"),
      isBlob = true,
      local = true
    )
    for (w, h) <- Seq((120, 40), (160, 48)) do
      val drawn = rows(TuiFixtures.withPool(s, pool.copy(txs = wide +: pool.txs.tail)), w, h)
      val at = drawn.indexWhere(l => l.contains("hash") && l.contains("nonce"))
      val (header, first, second) = (drawn(at), drawn(at + 1), drawn(at + 2))
      first should include("12,345,678")
      first should include("1,234,567 gwei")
      first should include("9,876,543 gwei")
      first should include("blob local")
      // Right-aligned columns end under their names; left-aligned ones start under them, on the wide row and the next.
      first.indexOf("12,345,678") + "12,345,678".length shouldBe header.indexOf("nonce") + "nonce".length
      first.indexOf("1,234,567 gwei") shouldBe header.indexOf("tip")
      first.indexOf("9,876,543 gwei") shouldBe header.indexOf("fee cap")
      first.indexOf("blob local") shouldBe header.indexOf("kind")
      second.indexOf(panels.ObservatoryPanels.gwei(pool.txs(1).tip)) shouldBe header.indexOf("tip")
      all(drawn.map(_.length)) should be <= w
  }

  it should "be drawn with its last column blank at every size" taggedAs (UnitTest) in {
    for (w, h) <- sizes; fixture <- Seq(TuiFixtures.mempoolEtc(), TuiFixtures.mempoolSepolia()) do
      val lines = renderer.render(mempool(fixture.copy(selectedTx = Some(fixture.pendingTxs(1).hash))), w, h)
      // Every row but the rules, which run the screen's width: the selected row's highlight stops a column short too.
      val text = lines.map(l => visibleFrame(Seq(l))).filterNot(_.endsWith("──"))
      text.count(_.contains(fixture.pendingTxs(1).hash.take(6))) should be >= 1
      all(text.map(_.last)) shouldBe ' '
  }

  "A short screen" should "keep the radar whole, what was refused and the queue's absence, and drop the rest" taggedAs (
    UnitTest
  ) in {
    val short = rows(TuiFixtures.mempoolEtc(), 80, 24)
    short.count(l => FeeTier.values.exists(t => l.trim.startsWith(t.label + " "))) shouldBe FeeTier.values.length
    short.exists(_.contains(" Refused ")) shouldBe true
    short.exists(_.contains(" Queue ")) shouldBe true
    short.exists(_.contains(" Fetch ")) shouldBe false
    short.exists(_.contains(" Flow ")) shouldBe true
  }

  "The vitals' txpool cell" should "keep the count whole and leave the blobs out where both do not fit" taggedAs (
    UnitTest
  ) in {
    val cell = rows(TuiFixtures.mempoolSepolia()).find(_.contains(" Txpool ")).value
    cell should include("Txpool  1,240 ")
    (cell should not).include("blo")
    // Where both fit, both are there.
    val s = TuiFixtures.mempoolSepolia()
    val few = TuiFixtures.withPool(s, s.snapshot.value.pool.value.copy(size = 1_204, blobs = 3))
    rows(few).find(_.contains(" Txpool ")).value should include("1,204 (3 blob)")
  }

  private val ctx = UpdateCtx(page = 10, configuredMotion = Motion.Full)

  "Up and Down" should "select a pending transaction by hash, and Enter open it in the explorer" taggedAs (UnitTest) in {
    val s = mempool(TuiFixtures.mempoolEtc())
    val txs = s.pendingTxs
    s.selectedTxIndex shouldBe None
    val (first, _) = Update(s, Key.Down, ctx)
    first.selectedTx shouldBe Some(txs.head.hash)
    val (second, _) = Update(first, Key.Down, ctx)
    second.selectedTx shouldBe Some(txs(1).hash)
    val (back, _) = Update(second, Key.Up, ctx)
    back.selectedTx shouldBe Some(txs.head.hash)
    val (opened, effects) = Update(second, Key.Enter, ctx)
    opened.screens.headOption.value shouldBe Screen.Explore(LookupQuery.Tx(txs(1).hash), 0)
    effects should contain(Effect.Lookup(LookupQuery.Tx(txs(1).hash)))
    // Enter with nothing selected opens nothing.
    Update(s, Key.Enter, ctx)._1.screens shouldBe s.screens
    // Enter on a selection whose transaction has left the pool opens nothing either.
    val gone = second.copy(selectedTx = Some("0x" + "ee" * 32))
    Update(gone, Key.Enter, ctx) shouldBe ((gone, Nil))
    // With no pending transaction there is nothing to select.
    val empty = mempool(TuiFixtures.mempoolEmptyEtc())
    Update(empty, Key.Down, ctx)._1.selectedTx shouldBe None
    Update(empty, Key.Up, ctx)._1.selectedTx shouldBe None
  }

  // Spec 029 FR-037. prism, slice 4: the pool kept being read whole under an explorer screen opened from this view.
  "The whole pool" should "be wanted only while the Mempool view itself is on screen" taggedAs (UnitTest) in {
    val s = mempool(TuiFixtures.mempoolEtc())
    s.wantsPool shouldBe true
    TuiView.values.filterNot(_ == TuiView.Mempool).foreach(v => s.withView(v).wantsPool shouldBe false)
    s.copy(helpOpen = true).wantsPool shouldBe false
    // Enter on a transaction opens the explorer over the view: the pool is no longer read whole.
    val (first, _) = Update(s, Key.Down, ctx)
    val (opened, _) = Update(first, Key.Enter, ctx)
    opened.wantsPool shouldBe false
    // Esc returns to the view, and to reading it.
    Update(opened, Key.Esc, ctx)._1.wantsPool shouldBe true
    // A paused dashboard still shows the node as it is.
    s.copy(motion = Motion.Paused).wantsPool shouldBe true
  }

  it should "keep the selection on its transaction when the list moves, and lose it when the transaction leaves" taggedAs (
    UnitTest
  ) in {
    val s = mempool(TuiFixtures.mempoolEtc())
    val pool = s.snapshot.value.pool.value
    val chosen = pool.txs(3)
    val selected = s.copy(selectedTx = Some(chosen.hash))
    selected.selectedTxIndex shouldBe Some(3)
    val moved = TuiFixtures.withPool(selected, pool.copy(txs = pool.txs.drop(2)))
    moved.selectedTxIndex shouldBe Some(1)
    rows(moved).count(_.contains(chosen.hash.take(6))) should be >= 1
    val gone = TuiFixtures.withPool(selected, pool.copy(txs = pool.txs.filterNot(_.hash == chosen.hash)))
    gone.selectedTxIndex shouldBe None
  }

  it should "scroll the table so the selected transaction is on screen" taggedAs (UnitTest) in {
    val s = mempool(TuiFixtures.mempoolEtc())
    val txs = s.pendingTxs
    val far = s.copy(selectedTx = Some(txs(100).hash))
    rows(far).exists(_.contains(txs(100).hash.take(6))) shouldBe true
    rows(s).exists(_.contains(txs(100).hash.take(6))) shouldBe false
  }
