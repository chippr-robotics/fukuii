package com.chipprbots.ethereum.jsonrpc

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.testkit.TestProbe
import org.apache.pekko.util.ByteString

import cats.effect.unsafe.IORuntime

import scala.concurrent.duration.DurationInt

import com.typesafe.config.ConfigFactory
import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.scalamock.scalatest.MockFactory
import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.*
import com.chipprbots.ethereum.consensus.mining.Mining
import com.chipprbots.ethereum.crypto
import com.chipprbots.ethereum.crypto.ECDSASignature
import com.chipprbots.ethereum.db.storage.TransactionMappingStorage
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.*
import com.chipprbots.ethereum.domain.branch.BestBranch
import com.chipprbots.ethereum.jsonrpc.EthBlocksService.*
import com.chipprbots.ethereum.jsonrpc.EthTxService.*
import com.chipprbots.ethereum.ledger.BlockQueue
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.Config

/** The gas price oracle (go-ethereum `eth/gasprice/gasprice.go`, same package in core-geth) behind eth_gasPrice,
  * eth_maxPriorityFeePerGas and the send/sign/fill fee defaults.
  *
  * Coverage:
  *   - A. settings: geth `FullNodeGPO` defaults, `fukuii.network.rpc.gpo` keys, `NewOracle` sanitization
  *   - B. `getBlockValues`: lowest 3 effective tips, ignore price, coinbase and unrecoverable senders, base fee
  *   - C. `SuggestTipCap`: percentile index, max price, empty-block fallback, window extension, cache by head
  *   - D. eth_gasPrice / eth_maxPriorityFeePerGas on ETH (Sepolia) and ETC (Mordor) configs
  *   - E. TransactionRequest oracle injection
  *   - F. hive graphql `07_eth_gasPrice` (stale fixture) pin
  */
class GasPriceOracleSpec
    extends ScalaTestWithActorTestKit
    with AnyFlatSpecLike
    with Matchers
    with OptionValues
    with MockFactory:

  implicit val runtime: IORuntime = IORuntime.global
  implicit private val classicActorSystem: ActorSystem = system.toClassic

  private val gwei: BigInt = BigInt(10).pow(9)
  private val zeroAddr: ByteString = ByteString(Array.fill(20)(0.toByte))
  private val zeroHash: ByteString = ByteString(Array.fill(32)(0.toByte))

  private val mordorCfg: BlockchainConfig = Config.blockchains.blockchains("mordor")
  private val sepoliaCfg: BlockchainConfig = Config.blockchains.blockchains("sepolia")

  // geth FullNodeGPO with core-geth's 1 gwei start price: the settings on Mordor
  private val etcGpo: GasPriceOracle.Config = GasPriceOracle.defaultConfig(using mordorCfg)

  private val fixtureHeader: BlockHeader = Fixtures.Blocks.Block3125369.header

  private val sender: AsymmetricCipherKeyPair = crypto.keyPairFromPrvKey(BigInt(0x5eed))
  private val miner: AsymmetricCipherKeyPair = crypto.keyPairFromPrvKey(BigInt(0xc0b))
  private def addressOf(kp: AsymmetricCipherKeyPair): ByteString =
    ByteString(crypto.kec256(crypto.pubKeyFromKeyPair(kp)).drop(12))
  private val minerAddr: ByteString = addressOf(miner)

  // a fresh nonce per transaction keeps hashes (the sender cache key) distinct
  private val nonces = new java.util.concurrent.atomic.AtomicLong(0)

  /** A pre-EIP-155 legacy transaction at `price`, signed by `by`. */
  private def legacy(price: BigInt, by: AsymmetricCipherKeyPair = sender): SignedTransaction =
    SignedTransaction.sign(
      LegacyTransaction(
        nonces.incrementAndGet(),
        GasPrice(price),
        GasAmount(21000),
        Some(Address(1)),
        0,
        ByteString.empty
      ),
      by,
      None
    )

  /** An EIP-1559 transaction with tip cap `tip` and fee cap `maxFee`, signed for `chainId`. */
  private def dynamic(tip: BigInt, maxFee: BigInt, chainId: BigInt): SignedTransaction =
    SignedTransaction.sign(
      TransactionWithDynamicFee(
        chainId,
        nonces.incrementAndGet(),
        tip,
        maxFee,
        GasAmount(21000),
        Some(Address(1)),
        0,
        ByteString.empty,
        Nil
      ),
      sender,
      Some(chainId)
    )

  /** A transaction whose signature recovers no sender. */
  private def unsigned(price: BigInt): SignedTransaction = SignedTransaction(
    LegacyTransaction(
      nonces.incrementAndGet(),
      GasPrice(price),
      GasAmount(21000),
      Some(Address(1)),
      0,
      ByteString.empty
    ),
    ECDSASignature(r = BigInt(0), s = BigInt(0), v = BigInt(27))
  )

  private def blk(
      number: BigInt,
      txs: Seq[SignedTransaction],
      coinbase: ByteString = zeroAddr,
      baseFee: Option[BigInt] = None
  ): Block =
    Block(
      fixtureHeader.copy(
        number = BlockNumber(number),
        beneficiary = coinbase,
        extraFields = baseFee.fold[HeaderExtraFields](HefEmpty)(HefPostOlympia.apply)
      ),
      BlockBody(txs, Nil)
    )

  /** blocks(0) is block 1; the last block is the head. Blocks not in `fetchedOnce` may be read any number of times. */
  private def reader(
      blocks: Seq[Block],
      fetchedOnce: Set[BigInt] = Set.empty,
      neverFetched: Set[BigInt] = Set.empty
  ): BlockchainReader =
    val r = mock[BlockchainReader]
    val head = blocks.last
    val branch = BestBranch(zeroHash, head.header.number.value)
    (() => r.getBestBranch).expects().returning(branch).anyNumberOfTimes()
    (() => r.getBestBlock).expects().returning(Some(head)).anyNumberOfTimes()
    blocks.foreach { b =>
      val n = b.header.number.value
      if fetchedOnce.contains(n) then r.getBlockByNumber.expects(branch, n).returning(Some(b)).once()
      else if neverFetched.contains(n) then r.getBlockByNumber.expects(branch, n).never()
      else r.getBlockByNumber.expects(branch, n).returning(Some(b)).anyNumberOfTimes()
    }
    r

  /** `n` blocks, numbered from 1, each from `txsAt`. */
  private def chain(n: Int, baseFee: Option[BigInt] = None)(txsAt: Int => Seq[SignedTransaction]): Seq[Block] =
    (1 to n).map(i => blk(i, txsAt(i), baseFee = baseFee))

  private def oracle(
      r: BlockchainReader,
      config: Option[GasPriceOracle.Config] = None,
      cfg: BlockchainConfig = mordorCfg
  ): GasPriceOracle =
    new GasPriceOracle(r, config.getOrElse(GasPriceOracle.defaultConfig(using cfg)))(using cfg)

  private def txService(o: GasPriceOracle, r: BlockchainReader, cfg: BlockchainConfig): EthTxService =
    implicit val implCfg: BlockchainConfig = cfg
    new EthTxService(
      stub[Blockchain],
      r,
      stub[Mining],
      TestProbe().ref.toTyped[com.chipprbots.ethereum.transactions.PendingTransactionsManager.Command],
      5.seconds,
      stub[TransactionMappingStorage],
      system.scheduler,
      Some(o)
    )

  private def blocksService(o: GasPriceOracle, r: BlockchainReader, cfg: BlockchainConfig): EthBlocksService =
    new EthBlocksService(
      stub[Blockchain],
      r,
      stub[Mining],
      stub[BlockQueue],
      blockchainConfig = cfg,
      gasPriceOracleOpt = Some(o)
    )

  // ─── A. settings ────────────────────────────────────────────────────────────

  "GasPriceOracle.defaultConfig" should "carry go-ethereum's FullNodeGPO defaults and core-geth's 1 gwei start price on ETC" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    etcGpo shouldBe GasPriceOracle.Config(
      blocks = 20,
      percentile = 60,
      maxHeaderHistory = 1024,
      maxBlockHistory = 1024,
      maxPrice = 500 * gwei,
      ignorePrice = BigInt(2),
      defaultPrice = gwei
    )
  }

  it should "start at geth's 1 mwei on ETH-family chains, the family's reference client" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    sepoliaCfg.networkType shouldBe com.chipprbots.ethereum.utils.NetworkType.ETH
    GasPriceOracle.defaultConfig(using sepoliaCfg) shouldBe etcGpo.copy(defaultPrice = BigInt(1000000))
    GasPriceOracle.defaultPriceFor(Config.blockchains.blockchains("eth")) shouldBe BigInt(1000000)
    GasPriceOracle.defaultPriceFor(Config.blockchains.blockchains("etc")) shouldBe gwei
  }

  it should "take a chain conf's gpo-default-price over the family default" taggedAs (UnitTest, RPCTest) in {
    GasPriceOracle.defaultPriceFor(sepoliaCfg.copy(gasPriceOracleDefault = Some(BigInt(7)))) shouldBe BigInt(7)
    GasPriceOracle.defaultPriceFor(mordorCfg.copy(gasPriceOracleDefault = Some(BigInt(7)))) shouldBe BigInt(7)
  }

  it should "be what the shipped fukuii.network.rpc.gpo block configures, on either family" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    GasPriceOracle.configFrom(Config.config)(using mordorCfg) shouldBe etcGpo
    GasPriceOracle.configFrom(Config.config)(using sepoliaCfg) shouldBe GasPriceOracle.defaultConfig(using sepoliaCfg)
  }

  it should "read every key under network.rpc.gpo, keeping geth defaults for absent ones" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    val c = ConfigFactory.parseString(
      """network.rpc.gpo { blocks = 7, percentile = 40, max-price = "1000", ignore-price = "5",
        |default-price = "9", max-header-history = 8, max-block-history = 4 }""".stripMargin
    )
    // an explicit default-price overrides the chain's start price on both families
    GasPriceOracle.configFrom(c)(using mordorCfg) shouldBe
      GasPriceOracle.Config(7, 40, 8, 4, BigInt(1000), BigInt(5), BigInt(9))
    GasPriceOracle.configFrom(c)(using sepoliaCfg).defaultPrice shouldBe BigInt(9)
    GasPriceOracle.configFrom(ConfigFactory.empty())(using mordorCfg) shouldBe etcGpo
  }

  it should "sanitize out-of-range settings as geth NewOracle does" taggedAs (UnitTest, RPCTest) in {
    GasPriceOracle.Config(0, 150, 0, -1, BigInt(0), BigInt(-3), BigInt(-1)).sanitized shouldBe
      GasPriceOracle.Config(1, 100, 1, 1, 500 * gwei, BigInt(2), BigInt(0))
    GasPriceOracle.Config(5, -10, 2, 2, BigInt(7), BigInt(1), gwei).sanitized.percentile shouldBe 0
  }

  // ─── B. getBlockValues ──────────────────────────────────────────────────────

  "GasPriceOracle.blockValues" should "give the 3 lowest effective tips, ascending, whatever the block order" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    given BlockchainConfig = mordorCfg
    val b = blk(1, Seq(9, 1, 5, 7, 3).map(p => legacy(p * gwei)))
    GasPriceOracle.blockValues(b, 3, BigInt(2)) shouldBe Seq(1, 3, 5).map(_ * gwei)
  }

  it should "skip tips below the ignore price" taggedAs (UnitTest, RPCTest) in {
    given BlockchainConfig = mordorCfg
    val b = blk(1, Seq(legacy(1), legacy(2), legacy(10)))
    GasPriceOracle.blockValues(b, 3, BigInt(2)) shouldBe Seq(BigInt(2), BigInt(10))
  }

  it should "skip the coinbase's own transactions and transactions with no recoverable sender" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    given BlockchainConfig = mordorCfg
    val noSender = unsigned(4 * gwei)
    SignedTransaction.getSender(noSender) shouldBe None
    val b = blk(1, Seq(legacy(3 * gwei, miner), noSender, legacy(5 * gwei)), coinbase = minerAddr)
    GasPriceOracle.blockValues(b, 3, BigInt(2)) shouldBe Seq(5 * gwei)
  }

  it should "take the tip at the block's base fee: min(tipCap, feeCap - baseFee), or gasPrice - baseFee" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    given BlockchainConfig = sepoliaCfg
    val chainId = sepoliaCfg.chainId.value
    val b = blk(
      1,
      Seq(dynamic(3 * gwei, 100 * gwei, chainId), dynamic(5 * gwei, 12 * gwei, chainId), legacy(14 * gwei)),
      baseFee = Some(10 * gwei)
    )
    GasPriceOracle.blockValues(b, 3, BigInt(2)) shouldBe Seq(2 * gwei, 3 * gwei, 4 * gwei)
  }

  it should "take a fee-market transaction's tip cap as its tip where the block has no base fee" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    GasPriceOracle.effectiveTip(dynamic(3 * gwei, 100 * gwei, 1).tx, None) shouldBe 3 * gwei
    GasPriceOracle.effectiveTip(legacy(7 * gwei).tx, None) shouldBe 7 * gwei
  }

  // ─── C. SuggestTipCap ───────────────────────────────────────────────────────

  "GasPriceOracle.suggestTipCap" should "answer the start price at genesis (block 0 is never sampled)" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    val r = mock[BlockchainReader]
    (() => r.getBestBlock).expects().returning(Some(blk(0, Seq(legacy(50 * gwei))))).anyNumberOfTimes()
    oracle(r).suggestTipCap() shouldBe gwei
  }

  it should "take the sample at index (n - 1) * percentile / 100" taggedAs (UnitTest, RPCTest) in {
    // samples 1..5 gwei: geth index (5 - 1) * 60 / 100 = 2 -> 3 gwei (n * 60 / 100 = 3 would give 4 gwei)
    val blocks = Seq(blk(1, Seq(4, 5).map(p => legacy(p * gwei))), blk(2, Seq(1, 2, 3).map(p => legacy(p * gwei))))
    oracle(reader(blocks), Some(etcGpo.copy(blocks = 2))).suggestTipCap() shouldBe 3 * gwei
  }

  it should "sample 20 blocks by default, 3 transactions each" taggedAs (UnitTest, RPCTest) in {
    // blocks 1..30 each carry tips (n, n, n, 1000) gwei; blocks 11..30 are sampled -> 60 values, 3 of each n
    val blocks = chain(30)(n => Seq(n, n, n, 1000).map(p => legacy(p * gwei)))
    // sorted index (60 - 1) * 60 / 100 = 35 -> the 12th distinct value from 11 = 22 gwei
    oracle(reader(blocks)).suggestTipCap() shouldBe 22 * gwei
  }

  it should "cap the tip at max-price" taggedAs (UnitTest, RPCTest) in {
    val blocks = chain(3)(_ => Seq(legacy(600 * gwei)))
    oracle(reader(blocks)).suggestTipCap() shouldBe 500 * gwei
    oracle(reader(blocks), Some(etcGpo.copy(maxPrice = 7 * gwei))).suggestTipCap() shouldBe 7 * gwei
  }

  it should "answer the start price over a window of empty blocks" taggedAs (UnitTest, RPCTest) in {
    val blocks = chain(25)(_ => Nil)
    oracle(reader(blocks)).suggestTipCap() shouldBe gwei
    oracle(reader(blocks), Some(etcGpo.copy(defaultPrice = 1000000))).suggestTipCap() shouldBe 1000000
  }

  it should "count a block of only ignored or coinbase transactions as the last suggestion" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    val minerOnly = (1 to 5).map(n => blk(n, Seq(legacy(90 * gwei, miner), legacy(1)), coinbase = minerAddr))
    oracle(reader(minerOnly)).suggestTipCap() shouldBe gwei
  }

  it should "sample further blocks while blocks give a single value, up to twice the window" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    // window 2: blocks 6, 5 and 4 give one value each (empty -> last price), so 4 and 3 are added; 3 gives three
    // 100 gwei tips -> samples (1, 1, 1, 100, 100, 100) gwei, index 5 * 60 / 100 = 3 -> 100 gwei
    val blocks = Seq(blk(1, Nil), blk(2, Nil), blk(3, Seq.fill(3)(legacy(100 * gwei)))) ++ (4 to 6).map(blk(_, Nil))
    val o = oracle(reader(blocks, neverFetched = Set(1, 2)), Some(etcGpo.copy(blocks = 2)))
    o.suggestTipCap() shouldBe 100 * gwei
  }

  it should "stop extending once the samples and outstanding blocks reach twice the window" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    // window 2 over empty blocks: 10 (head), 9, then 8 and 7 are added; 6 never is
    val blocks = chain(10)(_ => Nil)
    val r = reader(blocks, fetchedOnce = Set(9, 8, 7), neverFetched = (1 to 6).map(BigInt(_)).toSet)
    oracle(r, Some(etcGpo.copy(blocks = 2))).suggestTipCap() shouldBe gwei
  }

  it should "skip a block that is not stored, sampling only stored blocks" taggedAs (UnitTest, RPCTest) in {
    val head = blk(3, Seq(legacy(40 * gwei)))
    val r = mock[BlockchainReader]
    val branch = BestBranch(zeroHash, 3)
    (() => r.getBestBranch).expects().returning(branch).anyNumberOfTimes()
    (() => r.getBestBlock).expects().returning(Some(head)).anyNumberOfTimes()
    r.getBlockByNumber.expects(branch, BigInt(2)).returning(None).anyNumberOfTimes()
    r.getBlockByNumber.expects(branch, BigInt(1)).returning(None).anyNumberOfTimes()
    // only the head is stored: samples (40) gwei, not (1, 1, 40) with the start price standing in for 1 and 2
    oracle(r).suggestTipCap() shouldBe 40 * gwei
  }

  it should "compute once per head and reuse the answer until the head moves" taggedAs (UnitTest, RPCTest) in {
    val blocks = chain(3)(n => Seq(legacy((10 * n) * gwei)))
    val o = oracle(reader(blocks, fetchedOnce = Set(1, 2)))
    o.suggestTipCap() shouldBe 20 * gwei
    o.suggestTipCap() shouldBe 20 * gwei
  }

  it should "carry the last suggestion into empty blocks at a new head, as geth's lastPrice does" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    val busy = chain(3)(_ => Seq(legacy(50 * gwei)))
    var current: Seq[Block] = busy
    val r = mock[BlockchainReader]
    (() => r.getBestBranch)
      .expects()
      .onCall(() => BestBranch(zeroHash, current.last.header.number.value))
      .anyNumberOfTimes()
    (() => r.getBestBlock).expects().onCall(() => Some(current.last)).anyNumberOfTimes()
    r.getBlockByNumber
      .expects(*, *)
      .onCall((_, n) => current.find(_.header.number.value == n))
      .anyNumberOfTimes()
    val o = oracle(r)
    o.suggestTipCap() shouldBe 50 * gwei
    // the chain moves on through empty blocks: still 50 gwei, not the 1 gwei start price
    current = busy ++ (4 to 30).map(blk(_, Nil))
    o.suggestTipCap() shouldBe 50 * gwei
  }

  // ─── D. eth_gasPrice / eth_maxPriorityFeePerGas ─────────────────────────────

  "eth_gasPrice and eth_maxPriorityFeePerGas" should "on ETH (Sepolia), answer tip + head base fee and the tip" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    val chainId = sepoliaCfg.chainId.value
    val blocks = chain(5, baseFee = Some(7 * gwei))(_ => Seq(dynamic(2 * gwei, 30 * gwei, chainId)))
    val r = reader(blocks)
    val o = oracle(r, cfg = sepoliaCfg)
    txService(o, r, sepoliaCfg).getGetGasPrice(GetGasPriceRequest()).unsafeRunSync() shouldBe
      Right(GetGasPriceResponse(9 * gwei))
    blocksService(o, r, sepoliaCfg).maxPriorityFeePerGas(MaxPriorityFeePerGasRequest()).unsafeRunSync() shouldBe
      Right(MaxPriorityFeePerGasResponse(2 * gwei))
  }

  it should "on ETH, add the base fee after the max-price cap, so eth_gasPrice can exceed it" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    val chainId = sepoliaCfg.chainId.value
    val blocks = chain(2, baseFee = Some(80 * gwei))(_ => Seq(dynamic(900 * gwei, 2000 * gwei, chainId)))
    val r = reader(blocks)
    val o = oracle(r, cfg = sepoliaCfg)
    txService(o, r, sepoliaCfg).suggestGasPrice() shouldBe 580 * gwei
  }

  it should "on ETC (Mordor) without a base fee, both answer the legacy gas price suggestion" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    val blocks = chain(5)(n => Seq(legacy((n + 1) * gwei)))
    val r = reader(blocks)
    val o = oracle(r, cfg = mordorCfg)
    // samples 2..6 gwei, index 4 * 60 / 100 = 2 -> 4 gwei
    txService(o, r, mordorCfg).getGetGasPrice(GetGasPriceRequest()).unsafeRunSync() shouldBe
      Right(GetGasPriceResponse(4 * gwei))
    blocksService(o, r, mordorCfg).maxPriorityFeePerGas(MaxPriorityFeePerGasRequest()).unsafeRunSync() shouldBe
      Right(MaxPriorityFeePerGasResponse(4 * gwei))
  }

  it should "no longer answer the configured min-tip for eth_maxPriorityFeePerGas" taggedAs (UnitTest, RPCTest) in {
    // Sepolia sets no min-tip (1 wei); before the oracle eth_maxPriorityFeePerGas answered exactly that. Over empty
    // blocks the oracle answers geth's 1 mwei start price.
    sepoliaCfg.minTip shouldBe BigInt(1)
    val blocks = chain(3, baseFee = Some(gwei))(_ => Nil)
    val r = reader(blocks)
    blocksService(oracle(r, cfg = sepoliaCfg), r, sepoliaCfg)
      .maxPriorityFeePerGas(MaxPriorityFeePerGasRequest())
      .unsafeRunSync() shouldBe Right(MaxPriorityFeePerGasResponse(BigInt(1000000)))
  }

  // ─── E. TransactionRequest oracle injection ─────────────────────────────────

  "TransactionRequest.toTransaction(nonce, suggestedGasPrice)" should "use user-supplied gasPrice when provided" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    val req = TransactionRequest(from = Address(zeroAddr), gasPrice = Some(5 * gwei))
    req.toTransaction(BigInt(0), 10 * gwei).gasPrice.value shouldEqual 5 * gwei
  }

  it should "use the oracle price when no gasPrice provided by the user" taggedAs (UnitTest, RPCTest) in {
    TransactionRequest(from = Address(zeroAddr)).toTransaction(BigInt(0), 4 * gwei).gasPrice.value shouldEqual 4 * gwei
  }

  it should "respect user's explicit gasPrice = 0 over any oracle value" taggedAs (UnitTest, RPCTest) in {
    val req = TransactionRequest(from = Address(zeroAddr), gasPrice = Some(BigInt(0)))
    req.toTransaction(BigInt(0), 5 * gwei).gasPrice.value shouldEqual BigInt(0)
  }

  // ─── F. hive graphql 07_eth_gasPrice ────────────────────────────────────────

  /** Pins hive `graphql` fixture `07_eth_gasPrice` (`{ gasPrice }`, accepts only `0x10` or `0x1`) as a stale fixture,
    * not a fukuii defect: see `.claude/agent-protocols/devp2p-hive-tool-interop-quirks.md`.
    *
    * The chain below mirrors `simulators/ethereum/graphql/init/testBlockchain.blocks` (decoded): blocks 1-32 carry one
    * legacy transaction at 1 wei each, block 33 (London, base fee 1 gwei) a 0x2 transaction with a 1 gwei tip cap and 3
    * gwei fee cap, block 34 (head, base fee 876019786) a 0x3 transaction with a 1 gwei tip cap. hive runs it on an
    * ETH-family chain conf (hive.conf), so the start price is geth's 1 mwei. 1-wei tips fall under the 2 wei ignore
    * price, so every Frontier block gives the start price: 32 samples of 1 mwei and two of 1 gwei, index 33 * 60 / 100
    * \= 19 -> 1 mwei. `{ gasPrice }` = 876019786 + 1 mwei = 0x3446428a, as geth's own resolver (`tipcap +
    * head.BaseFee`) gives. No conforming answer can be 16 or 1 wei.
    */
  "hive graphql 07_eth_gasPrice" should "stay unreachable: the head's base fee alone is ~876M wei" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    val headBaseFee = BigInt(876019786)
    val frontier = (1 to 32).map(n => blk(n, Seq(legacy(1))))
    val london33 = blk(33, Seq(dynamic(gwei, 3 * gwei, 1)), baseFee = Some(gwei))
    val head34 = blk(34, Seq(dynamic(gwei, 4 * gwei, 1)), baseFee = Some(headBaseFee))
    val r = reader(frontier :+ london33 :+ head34)
    val result = txService(oracle(r, cfg = sepoliaCfg), r, sepoliaCfg).suggestGasPrice()
    result shouldBe headBaseFee + BigInt(1000000)
    result shouldBe BigInt("3446428a", 16)
    result should be > BigInt(0x10)
  }
