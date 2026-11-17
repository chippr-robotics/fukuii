package com.chipprbots.ethereum.console

import org.apache.pekko.util.ByteString

import org.scalatest.BeforeAndAfterAll
import org.scalatest.EitherValues
import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.BlockHash
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BloomFilter
import com.chipprbots.ethereum.domain.FailureOutcome
import com.chipprbots.ethereum.domain.LegacyReceipt
import com.chipprbots.ethereum.domain.Receipt
import com.chipprbots.ethereum.domain.SuccessOutcome
import com.chipprbots.ethereum.domain.TxLogEntry
import com.chipprbots.ethereum.domain.UInt256
import com.chipprbots.ethereum.ledger.BloomFilter as Bloom
import com.chipprbots.ethereum.testing.Tags.*

/** The explorer (spec 029 FR-028, FR-029): the reads behind it, its commands, its keys and its screens. */
class ExplorerSpec
    extends AnyFlatSpec
    with Matchers
    with OptionValues
    with EitherValues
    with BeforeAndAfterAll
    with TuiGoldenFiles:

  protected val goldenTerminal = new TuiTestTerminal()
  override def afterAll(): Unit = goldenTerminal.close()

  private val fixture = Fixtures.Blocks.Block3125369
  private val header = fixture.header
  private val body = fixture.body
  private val blockHash = ChainReads.hex(header.hash.value)
  private val txHashes = body.transactionList.map(stx => ChainReads.hex(stx.hash.value))
  private val senderOf = Address(0xabcdefL)
  private val emptyBloom = BloomFilter(Bloom.EmptyBloomFilter)
  private val logger = Address(0x1234L)
  private val receipts: Seq[Receipt] = Seq(
    LegacyReceipt(SuccessOutcome, BigInt(21_000), emptyBloom, Nil),
    LegacyReceipt(
      FailureOutcome,
      BigInt(63_000),
      emptyBloom,
      Seq(TxLogEntry(logger, Seq(ByteString(1)), ByteString(1, 2, 3)))
    ),
    LegacyReceipt(SuccessOutcome, BigInt(84_000), emptyBloom, Nil),
    LegacyReceipt(SuccessOutcome, BigInt(105_000), emptyBloom, Nil)
  )
  private val contract = Address(0x777L)

  private def lookups(
      best: Option[BlockHeader] = Some(header),
      withBody: Boolean = true,
      withReceipts: Boolean = true,
      accountAt: (BlockHeader, Address) => Option[Account] = (_, address) =>
        if address == contract then Some(Account(nonce = UInt256(1), balance = UInt256(5), codeHash = contractCode))
        else if address == senderOf then
          Some(Account(nonce = UInt256(7), balance = UInt256(BigInt("1250000000000000000"))))
        else None,
      pending: ByteString => Option[ChainLookups.Pending] = _ => None
  ): ChainLookups =
    new ChainLookups(
      bestHeader = () => best,
      headerByNumber = n => Option.when(n == header.number.value)(header),
      headerByHash = (h: BlockHash) => Option.when(h == header.hash)(header),
      bodyByHash = (h: BlockHash) => Option.when(withBody && h == header.hash)(body),
      receiptsByHash = (h: BlockHash) => Option.when(withReceipts && h == header.hash)(receipts),
      txLocation = hash =>
        val at = body.transactionList.indexWhere(_.hash.value == hash)
        Option.when(at >= 0)((header.hash.value, at))
      ,
      accountAt = accountAt,
      codeSize = hash => Option.when(hash == contractCode.value)(2_048),
      sender = _ => Some(senderOf),
      now = () => TuiFixtures.Now,
      pending = pending
    )

  /** A transaction that is in the pool and in no block: signed with a fixed key, so its hash is the same every run. */
  private lazy val pooled: ChainLookups.Pending =
    val key = com.chipprbots.ethereum.crypto.keyPairFromPrvKey(BigInt(0x1234567890abcdefL))
    val tx = com.chipprbots.ethereum.domain.LegacyTransaction(
      41,
      com.chipprbots.ethereum.domain.GasPrice(BigInt(2_000_000_000L)),
      com.chipprbots.ethereum.domain.GasAmount(21_000),
      contract,
      BigInt("1500000000000000000"),
      ByteString()
    )
    ChainLookups.Pending(
      com.chipprbots.ethereum.domain.SignedTransaction.sign(tx, key, Some(BigInt(61))),
      Address(key),
      TuiFixtures.Now.minusSeconds(95).toEpochMilli,
      local = true
    )
  private lazy val pooledHash = ChainReads.hex(pooled.stx.hash.value)
  private def inPool(hash: ByteString): Option[ChainLookups.Pending] =
    Option.when(hash == pooled.stx.hash.value)(pooled)

  private lazy val contractCode = com.chipprbots.ethereum.domain.CodeHash(ByteString(Array.fill[Byte](32)(7)))

  // ---- the reads -----------------------------------------------------------------------------------------------------

  "A block" should "be found by number, by hash and as the head, with its transactions listed" taggedAs (UnitTest) in {
    val byNumber = lookups()(LookupQuery.BlockByNumber(header.number.toLong)).value
    lookups()(LookupQuery.BlockByHash(blockHash)).value shouldBe byNumber
    lookups()(LookupQuery.LatestBlock).value shouldBe byNumber
    byNumber match
      case LookupResult.Block(summary, txs, uncles, withdrawals) =>
        summary.hash shouldBe blockHash
        summary.txCount shouldBe Some(body.transactionList.size)
        txs.map(_.hash) shouldBe txHashes
        txs.map(_.index) shouldBe txs.indices
        all(txs.map(_.from)) shouldBe Some(ChainReads.hex(senderOf.bytes))
        txs.map(_.kind).distinct shouldBe Seq("legacy")
        uncles shouldBe body.uncleNodesList.size
        withdrawals shouldBe None
      case other => fail(s"not a block: $other")
  }

  it should "say what the node does not have, in words" taggedAs (UnitTest) in {
    lookups()(LookupQuery.BlockByNumber(5)).left.value shouldBe "the node has no block 5"
    lookups()(LookupQuery.BlockByHash("0x" + "ab" * 32)).left.value shouldBe "the node has no block with this hash"
    lookups()(LookupQuery.BlockByHash("0x1234")).left.value should include("64 hexadecimal digits")
    lookups(best = None)(LookupQuery.LatestBlock).left.value shouldBe "the node has no head block yet"
  }

  it should "be shown without its contents where the body is not stored" taggedAs (UnitTest) in {
    lookups(withBody = false)(LookupQuery.LatestBlock).value match
      case LookupResult.Block(summary, txs, _, _) =>
        summary.txCount shouldBe None
        summary.sizeBytes shouldBe None
        txs shouldBe empty
      case other => fail(s"not a block: $other")
  }

  "A transaction" should "be found through the node's index, with its block and its place in it" taggedAs (UnitTest) in {
    lookups()(LookupQuery.Tx(txHashes(2))).value match
      case LookupResult.Tx(line, blockNumber, hash, nonce, input) =>
        line.index shouldBe 2
        line.hash shouldBe txHashes(2)
        blockNumber shouldBe header.number.toLong
        hash shouldBe blockHash
        nonce shouldBe body.transactionList(2).tx.nonce
        input shouldBe body.transactionList(2).tx.payload.size
      case other => fail(s"not a transaction: $other")
    lookups()(LookupQuery.Tx("0x" + "cd" * 32)).left.value should include(
      "no transaction with this hash is in the node's index"
    )
    lookups(withBody = false)(LookupQuery.Tx(txHashes(0))).left.value should include("body")
  }

  // Spec 029 FR-036: a transaction opens whether it is mined or still pending.
  it should "be found in the pool when no block holds it, as pending and with what the pool knows of it" taggedAs (
    UnitTest
  ) in {
    lookups(pending = inPool)(LookupQuery.Tx(pooledHash)).value match
      case LookupResult.PendingTx(line, nonce, input, addedAt, local) =>
        line.hash shouldBe pooledHash
        line.from shouldBe Some(ChainReads.hex(pooled.sender.bytes))
        line.to shouldBe Some(ChainReads.hex(contract.bytes))
        line.gasPrice shouldBe BigInt(2_000_000_000L)
        line.feeCaps shouldBe None
        (nonce, input, local) shouldBe (BigInt(41), 0, true)
        addedAt shouldBe TuiFixtures.Now.minusSeconds(95)
      case other => fail(s"not a pending transaction: $other")
    // Without the pool it is nowhere, and the sentence names both places looked in.
    lookups()(LookupQuery.Tx(pooledHash)).left.value should include("in the node's index or in its pool")
    // A pending transaction has no receipt: the miss says why.
    lookups(pending = inPool)(LookupQuery.Receipt(pooledHash)).left.value shouldBe
      "this transaction is pending: it has no receipt until it is in a block"
    // A mined transaction whose block's body is gone says that, pool or no pool.
    lookups(withBody = false, pending = inPool)(LookupQuery.Tx(txHashes(0))).left.value should include("body")
  }

  // prism, slice 4: a pool too slow to answer was taken for a pool without the transaction, and the explorer said the
  // transaction was nowhere.
  it should "fail with the pool's own reason when the pool cannot be asked, and never say the transaction is nowhere" taggedAs (
    UnitTest
  ) in {
    val silent = lookups(pending = _ => throw new IllegalStateException("the pool did not answer within 1 s"))
    (the[IllegalStateException] thrownBy silent(LookupQuery.Tx(pooledHash)) should have)
      .message("the pool did not answer within 1 s")
    // The lookup worker turns what a read throws into the screen's sentence (`LookupsSpec`).
    // A mined transaction is read from its block and never asks the pool.
    silent(LookupQuery.Tx(txHashes(2))).value shouldBe a[LookupResult.Tx]
  }

  it should "be read from its block, not from the pool, once it is mined" taggedAs (UnitTest) in {
    // The pool still holds a transaction the index already has (it leaves the pool a moment after import).
    val both = lookups(pending = _ => Some(pooled.copy(stx = body.transactionList(2))))
    both(LookupQuery.Tx(txHashes(2))).value shouldBe a[LookupResult.Tx]
  }

  "A receipt" should "give the transaction's own gas, its status and its logs" taggedAs (UnitTest) in {
    lookups()(LookupQuery.Receipt(txHashes(1))).value match
      case r: LookupResult.Receipt =>
        r.status shouldBe "failed"
        r.gasUsed shouldBe BigInt(42_000) // 63,000 up to here, less the 21,000 before it
        r.cumulativeGasUsed shouldBe BigInt(63_000)
        r.logs shouldBe Vector(EventLog(ChainReads.hex(logger.bytes), 1, 3))
        r.index shouldBe 1
        r.from shouldBe Some(ChainReads.hex(senderOf.bytes))
      case other => fail(s"not a receipt: $other")
    lookups()(LookupQuery.Receipt(txHashes(0))).value match
      case r: LookupResult.Receipt =>
        r.status shouldBe "success"
        r.gasUsed shouldBe BigInt(21_000)
      case other => fail(s"not a receipt: $other")
    lookups(withReceipts = false)(LookupQuery.Receipt(txHashes(0))).left.value should include("receipts")
  }

  "An account" should "be read at the head: balance, nonce, and how much code it has" taggedAs (UnitTest) in {
    lookups()(LookupQuery.Account(ChainReads.hex(senderOf.bytes))).value shouldBe
      LookupResult.Account(
        ChainReads.hex(senderOf.bytes),
        header.number.toLong,
        BigInt("1250000000000000000"),
        BigInt(7),
        None
      )
    lookups()(LookupQuery.Account(ChainReads.hex(contract.bytes))).value match
      case LookupResult.Account(_, _, balance, nonce, code) =>
        (balance, nonce, code) shouldBe (BigInt(5), BigInt(1), Some(2_048))
      case other => fail(s"not an account: $other")
    // An address nothing was ever sent to is an empty account, not an error.
    lookups()(LookupQuery.Account("0x" + "00" * 19 + "99")).value match
      case LookupResult.Account(_, _, balance, nonce, code) =>
        (balance, nonce, code) shouldBe (BigInt(0), BigInt(0), None)
      case other => fail(s"not an account: $other")
    lookups()(LookupQuery.Account("0x1234")).left.value should include("40 hexadecimal digits")
    val pruned = lookups(accountAt = (_, _) => throw new IllegalStateException("missing trie node"))
    pruned(LookupQuery.Account(ChainReads.hex(senderOf.bytes))).left.value should include(
      "does not hold the state of its head"
    )
  }

  // ---- the commands --------------------------------------------------------------------------------------------------

  "The prompt" should "take block, tx, receipt and account, and say how each is used" taggedAs (UnitTest) in {
    Commands.parse("block") shouldBe Command.Explore(LookupQuery.LatestBlock)
    Commands.parse("block latest") shouldBe Command.Explore(LookupQuery.LatestBlock)
    Commands.parse("block 21,884,102") shouldBe Command.Explore(LookupQuery.BlockByNumber(21_884_102L))
    Commands.parse(s"block ${blockHash.toUpperCase.replace("0X", "0x")}") shouldBe
      Command.Explore(LookupQuery.BlockByHash(blockHash))
    Commands.parse(s"block ${blockHash.drop(2)}") shouldBe Command.Explore(LookupQuery.BlockByHash(blockHash))
    Commands.parse("block seven") shouldBe a[Command.Usage]
    Commands.parse(s"tx ${txHashes.head}") shouldBe Command.Explore(LookupQuery.Tx(txHashes.head))
    Commands.parse("tx 0x12") shouldBe a[Command.Usage]
    Commands.parse("tx") shouldBe a[Command.Usage]
    Commands.parse(s"receipt ${txHashes.head}") shouldBe Command.Explore(LookupQuery.Receipt(txHashes.head))
    Commands.parse("account 0x" + "AB" * 20) shouldBe Command.Explore(LookupQuery.Account("0x" + "ab" * 20))
    Commands.parse("account 0x" + "ab" * 32) shouldBe a[Command.Usage]
    Commands.complete("bl") shouldBe "block "
    Commands.complete("acc") shouldBe "account "
  }

  // ---- the keys ------------------------------------------------------------------------------------------------------

  private val ctx = UpdateCtx(page = 10, configuredMotion = Motion.Full)
  private def press(state: TuiState, keys: Key*): (TuiState, Seq[Effect]) =
    keys.foldLeft((state, Seq.empty[Effect])) { case ((s, effects), k) =>
      val (next, more) = Update(s, k, ctx)
      (next, effects ++ more)
    }
  private def typed(line: String): Seq[Key] = (Key.Char(':') +: line.map(Key.Char(_))) :+ Key.Enter
  private def found(state: TuiState, query: LookupQuery): TuiState =
    state.copy(lookups = state.lookups.filterNot(_._1 == query) :+ (query -> LookupState.Done(lookups()(query).value)))

  ":block" should "open the block's screen and ask the worker to read it" taggedAs (UnitTest) in {
    val (opened, effects) = press(TuiFixtures.syncedEtc(), typed("block 3125369")*)
    val query = LookupQuery.BlockByNumber(3_125_369L)
    opened.screen shouldBe Some(Screen.Explore(query, 0))
    opened.viewTitle shouldBe "Block"
    effects shouldBe Seq(Effect.Lookup(query))
  }

  "Enter on a row that leads somewhere" should "open it: a block's transaction, its receipt, an address" taggedAs (
    UnitTest
  ) in {
    val block = LookupQuery.BlockByNumber(3_125_369L)
    val start = found(press(TuiFixtures.syncedEtc(), typed("block 3125369")*)._1, block)
    // The rows: the parent, who made it, then the transactions. Down three times is the second transaction.
    val (onTx, _) = press(start, Key.Down, Key.Down, Key.Down)
    onTx.screen shouldBe Some(Screen.Explore(block, 3))
    val (tx, effects) = press(onTx, Key.Enter)
    val txQuery = LookupQuery.Tx(txHashes(1))
    tx.screens.take(2) shouldBe List(Screen.Explore(txQuery, 0), Screen.Explore(block, 3))
    effects shouldBe Seq(Effect.Lookup(txQuery))
    // From the transaction, its first row is its receipt.
    val (receipt, more) = press(found(tx, txQuery), Key.Enter)
    receipt.screen shouldBe Some(Screen.Explore(LookupQuery.Receipt(txHashes(1)), 0))
    more shouldBe Seq(Effect.Lookup(LookupQuery.Receipt(txHashes(1))))
    // The selection stops at the last row, and Home goes to the first.
    val (last, _) = press(start, Key.End)
    last.screen shouldBe Some(Screen.Explore(block, 1 + txHashes.size))
    press(last, Key.Down)._1.screen shouldBe last.screen
    press(last, Key.Home)._1.screen shouldBe Some(Screen.Explore(block, 0))
    // With nothing found yet, Enter opens nothing.
    press(press(TuiFixtures.syncedEtc(), typed("block 3125369")*)._1, Key.Enter)._2 shouldBe empty
  }

  "Esc" should "go back a screen, to the row it was opened from, and cancel a lookup that is still running" taggedAs (
    UnitTest
  ) in {
    val block = LookupQuery.BlockByNumber(3_125_369L)
    val start = found(press(TuiFixtures.syncedEtc(), typed("block 3125369")*)._1, block)
    val (tx, _) = press(start, Key.Down, Key.Down, Key.Enter)
    val txQuery = LookupQuery.Tx(txHashes(0))
    // Still running: it is cancelled.
    val running = tx.copy(lookups = tx.lookups :+ (txQuery -> LookupState.Running(TuiFixtures.Now)))
    val (back, effects) = press(running, Key.Esc)
    back.screen shouldBe Some(Screen.Explore(block, 2))
    effects shouldBe Seq(Effect.CancelLookup(txQuery))
    // Done: nothing to cancel.
    press(found(tx, txQuery), Key.Esc)._2 shouldBe empty
    press(back, Key.Esc)._1.screen shouldBe None
  }

  "The Block Observatory" should "have a selection kept by the block's hash, and open the block with Enter" taggedAs (
    UnitTest
  ) in {
    val chain = TuiFixtures.observedEtc().withView(TuiView.Chain)
    val newest = chain.observedBlocks
    val (first, _) = press(chain, Key.Down)
    first.selectedBlock shouldBe Some(newest.head.hash)
    val (third, _) = press(first, Key.Down, Key.Down)
    third.selectedBlock shouldBe Some(newest(2).hash)
    val (opened, effects) = press(third, Key.Enter)
    opened.screen shouldBe Some(Screen.Explore(LookupQuery.BlockByHash(newest(2).hash), 0))
    effects shouldBe Seq(Effect.Lookup(LookupQuery.BlockByHash(newest(2).hash)))
    // A new block arrives: the selection stays on its block, one row lower.
    val grown = TuiFixtures.withChain(
      third,
      ChainSample(TuiFixtures.blocks(third.currentBlock + 1, 65))
    )
    grown.selectedBlockIndex shouldBe Some(3)
    press(chain, Key.End)._1.selectedBlock shouldBe Some(newest.last.hash)
    press(chain, Key.Enter)._2 shouldBe empty // nothing selected yet
  }

  // ---- the screens ---------------------------------------------------------------------------------------------------

  private val renderer = TuiRenderer(TuiConfig.default)
  private def screen(
      query: LookupQuery,
      state: LookupState,
      selected: Int = 0,
      base: TuiState = TuiFixtures.syncedEtc()
  ) =
    base.copy(
      screens = List(Screen.Explore(query, selected)),
      lookups = Vector(query -> state),
      motion = Motion.Reduced
    )
  private def done(query: LookupQuery) = LookupState.Done(lookups()(query).value)
  private def text(s: TuiState, w: Int = 120, h: Int = 40): String = visibleFrame(renderer.render(s, w, h))

  private val goldens: Seq[(String, TuiState)] = Seq(
    "block" -> screen(LookupQuery.BlockByNumber(3_125_369L), done(LookupQuery.BlockByNumber(3_125_369L)), selected = 3),
    "tx" -> screen(LookupQuery.Tx(txHashes(1)), done(LookupQuery.Tx(txHashes(1)))),
    "receipt" -> screen(LookupQuery.Receipt(txHashes(1)), done(LookupQuery.Receipt(txHashes(1))), selected = 1),
    "account" -> screen(
      LookupQuery.Account(ChainReads.hex(contract.bytes)),
      done(LookupQuery.Account(ChainReads.hex(contract.bytes)))
    ),
    "pending" -> screen(
      LookupQuery.Tx(pooledHash),
      LookupState.Done(lookups(pending = inPool)(LookupQuery.Tx(pooledHash)).value)
    ),
    "missing" -> screen(LookupQuery.BlockByNumber(5), LookupState.Failed("the node has no block 5")),
    "slow" -> screen(LookupQuery.LatestBlock, LookupState.Running(TuiFixtures.Now.minusSeconds(7)))
  )

  for (name, state) <- goldens; (w, h) <- Seq((80, 24), (120, 40)) do
    val file = s"explorer-${Tier.of(w, h).toString.toLowerCase}-$name-emoji.txt"

    "An explorer screen" should s"match $file" taggedAs (UnitTest) in {
      val lines = renderer.render(state, w, h)
      lines should have size h.toLong
      all(lines.map(_.columnLength())) shouldBe w
      matchGolden(file, visibleFrame(lines))
    }

  "A pending transaction's screen" should "say it is pending, for how long, where it came from, and link its addresses" taggedAs (
    UnitTest
  ) in {
    val query = LookupQuery.Tx(pooledHash)
    val s = screen(query, LookupState.Done(lookups(pending = inPool)(query).value))
    val shown = text(s)
    shown should include("pending: in this node's pool for 1m 35s, in no block yet")
    shown should include("submitted to this node")
    shown should include("2.00 gwei")
    (shown should not).include("base fee") // a legacy transaction pays its gas price whatever the block
    (shown should not).include("receipt")
    panels.ExplorerPanels.links(lookups(pending = inPool)(query).value).map(_.query) shouldBe Vector(
      LookupQuery.Account(ChainReads.hex(pooled.sender.bytes)),
      LookupQuery.Account(ChainReads.hex(contract.bytes))
    )
  }

  // prism, slice 3: a value too long for its row lost the value and kept the label. An early ETC receipt's status is a
  // state root: 76 characters.
  "A value too long for its row" should "go whole onto a row of its own: no row is a label alone" taggedAs (UnitTest) in {
    val root = "root 0x" + "ab" * 32
    val old = LookupResult.Receipt(
      txHashes(0),
      3_125_369L,
      0,
      root,
      BigInt(21_000),
      BigInt(21_000),
      Vector.empty,
      None,
      None,
      None
    )
    val shown = text(screen(LookupQuery.Receipt(txHashes(0)), LookupState.Done(old)), 80, 24)
    shown should include(root)
    val wide = text(screen(LookupQuery.Receipt(txHashes(0)), LookupState.Done(old)), 160, 48)
    wide should include(s"status     $root")
  }

  "A fee-market transaction" should "say what it paid and what it offered; a receipt's total is called cumulative" taggedAs (
    UnitTest
  ) in {
    val line = TxLine(
      0,
      txHashes(0),
      None,
      None,
      BigInt(0),
      BigInt(2_000_000_007L),
      BigInt(250_000),
      "dynamic fee (type 2)",
      Some((BigInt(30_000_000_000L), BigInt(2_000_000_000L)))
    )
    val tx = LookupResult.Tx(line, 21_693L, blockHash, BigInt("18446744073709551616"), 0)
    val shown = text(screen(LookupQuery.Tx(txHashes(0)), LookupState.Done(tx)))
    shown should include("2.00 gwei paid (max fee 30.0 gwei, max tip 2.00 gwei)")
    shown should include("gas limit  250,000")
    shown should include("18,446,744,073,709,551,616") // a nonce past a Long is not wrapped
    val receipt = text(screen(LookupQuery.Receipt(txHashes(1)), done(LookupQuery.Receipt(txHashes(1)))))
    receipt should include("42,000 (cumulative 63,000)")
    (receipt should not).include("the block's")
  }

  "r on an explorer screen" should "read the node again, then redraw" taggedAs (UnitTest) in {
    val account = LookupQuery.Account(ChainReads.hex(senderOf.bytes))
    val (_, effects) = press(screen(account, done(account)), Key.Char('r'))
    effects shouldBe Seq(Effect.RefreshLookup(account), Effect.Redraw)
    text(screen(account, done(account))) should include("r reads it again")
  }

  "A lookup that is running" should "show nothing of itself for two seconds, then say it is working and how to leave" taggedAs (
    UnitTest
  ) in {
    val quick = text(screen(LookupQuery.LatestBlock, LookupState.Running(TuiFixtures.Now.minusMillis(500))))
    (quick should not).include("looking it up")
    val slow = text(screen(LookupQuery.LatestBlock, LookupState.Running(TuiFixtures.Now.minusSeconds(7))))
    slow should include("looking it up: 7s so far. Esc cancels")
  }

  "A value" should "be written in the chain's coin: ETC on a proof-of-work chain, ETH on a proof-of-stake one" taggedAs (
    UnitTest
  ) in {
    val account = LookupQuery.Account(ChainReads.hex(senderOf.bytes))
    text(screen(account, done(account))) should include("1.25 ETC")
    text(screen(account, done(account), base = TuiFixtures.sepoliaNearHead())) should include("1.25 ETH")
  }
