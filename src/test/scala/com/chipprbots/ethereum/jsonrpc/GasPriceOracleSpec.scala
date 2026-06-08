package com.chipprbots.ethereum.jsonrpc

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.testkit.TestKit
import org.apache.pekko.testkit.TestProbe
import org.apache.pekko.util.ByteString

import cats.effect.IO
import cats.effect.unsafe.IORuntime

import scala.concurrent.duration.DurationInt

import org.scalactic.TypeCheckedTripleEquals
import org.scalamock.scalatest.MockFactory
import org.scalatest.OptionValues
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum._
import com.chipprbots.ethereum.consensus.mining.Mining
import com.chipprbots.ethereum.crypto.ECDSASignature
import com.chipprbots.ethereum.db.storage.TransactionMappingStorage
import com.chipprbots.ethereum.domain._
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields._
import com.chipprbots.ethereum.domain.branch.BestBranch
import com.chipprbots.ethereum.domain.branch.EmptyBranch
import com.chipprbots.ethereum.jsonrpc.EthBlocksService._
import com.chipprbots.ethereum.jsonrpc.EthTxService._
import com.chipprbots.ethereum.ledger.BlockQueue
import com.chipprbots.ethereum.testing.Tags._
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.Config

/** Comprehensive tests for the gas price oracle (eth_gasPrice / eth_maxPriorityFeePerGas).
  *
  * Coverage:
  *   - A. minimumGasPrice() — floor formula for all network states
  *   - B. suggestGasPrice() — algorithm correctness (percentile, coinbase exclusion, cap, per-block limit)
  *   - C. eth_gasPrice RPC wrapper
  *   - D. eth_maxPriorityFeePerGas stub
  *   - E. TransactionRequest.toTransaction oracle injection
  *   - F. Network-specific integration regression
  */
class GasPriceOracleSpec
    extends TestKit(ActorSystem("GasPriceOracleSpec_ActorSystem"))
    with AnyFlatSpecLike
    with WithActorSystemShutDown
    with Matchers
    with ScalaFutures
    with OptionValues
    with MockFactory
    with NormalPatience
    with TypeCheckedTripleEquals {

  implicit val runtime: IORuntime = IORuntime.global

  // ─── Shared constants ──────────────────────────────────────────────────────

  private val gwei: BigInt = BigInt(10).pow(9)
  private val cap500: BigInt = BigInt(500) * gwei
  private val zeroAddr: ByteString = ByteString(Array.fill(20)(0.toByte))
  private val zeroHash: ByteString = ByteString(Array.fill(32)(0.toByte))

  // Test config: chain-id=61, eip155 at 3000000, baseFeeFloor=0, minTip=1 (1 wei)
  private val defaultCfg: BlockchainConfig = Config.blockchains.blockchainConfig

  // ETC post-Olympia: baseFeeFloor = 1 gwei, minTip = 1 gwei
  private val etcOlympiaCfg: BlockchainConfig =
    defaultCfg.copy(baseFeeFloor = gwei, minTip = gwei)

  // ETH post-London: baseFeeFloor = 0, minTip = 1 gwei
  private val ethLondonCfg: BlockchainConfig =
    defaultCfg.copy(baseFeeFloor = BigInt(0), minTip = gwei)

  // Fixture data — real ETC block 3125369 with valid signed transactions
  private val fixtureHeader: BlockHeader = Fixtures.Blocks.Block3125369.header
  private val fixtureTxs: Seq[SignedTransaction] = Fixtures.Blocks.Block3125369.body.transactionList

  // ─── Helpers ──────────────────────────────────────────────────────────────

  /** Create a BlockHeader at the given number, with optional coinbase and baseFee. */
  private def hdr(
      number: BigInt,
      coinbase: ByteString = zeroAddr,
      baseFeeOpt: Option[BigInt] = None
  ): BlockHeader =
    fixtureHeader.copy(
      number = number,
      beneficiary = coinbase,
      extraFields = baseFeeOpt.fold[HeaderExtraFields](HefEmpty)(HefPostOlympia.apply)
    )

  /** Create a Block with the given transactions. */
  private def blk(
      number: BigInt,
      txs: Seq[SignedTransaction],
      coinbase: ByteString = zeroAddr,
      baseFeeOpt: Option[BigInt] = None
  ): Block =
    Block(hdr(number, coinbase, baseFeeOpt), BlockBody(txs, Nil))

  /** Create a fake SignedTransaction with the given gasPrice.
    *
    * The signature is invalid, so getSender returns None. Because None.contains(coinbase) == false, the
    * coinbase-exclusion filter does NOT exclude the tx — it contributes to the oracle sample. Use for
    * price-distribution tests where coinbase exclusion is not the focus.
    */
  private def fakeTx(price: BigInt): SignedTransaction = SignedTransaction(
    LegacyTransaction(
      nonce = 0,
      gasPrice = price,
      gasLimit = 21000,
      receivingAddress = Some(Address(zeroAddr)),
      value = 0,
      payload = ByteString.empty
    ),
    // v=27 = pre-EIP-155; r/s=1 = non-zero but invalid sig → getSender returns None
    ECDSASignature(r = BigInt(1), s = BigInt(1), v = BigInt(27))
  )

  /** Set up a mock BlockchainReader for oracle tests.
    *
    * @param bestNum
    *   The best block number the oracle reports.
    * @param window
    *   Map of block-number → Option[Block] covering all numbers the oracle will query.
    * @param bestBlock
    *   What getBestBlock() returns (for minimumGasPrice floor calculation).
    */
  private def mockReader(
      bestNum: BigInt,
      window: Map[BigInt, Option[Block]] = Map.empty,
      bestBlock: Option[Block] = None
  ): BlockchainReader = {
    val r = mock[BlockchainReader]
    val branch = if (bestNum > 0) BestBranch(zeroHash, bestNum) else EmptyBranch
    (r.getBestBlockNumber _).expects().returning(bestNum).anyNumberOfTimes()
    (r.getBestBranch _).expects().returning(branch).anyNumberOfTimes()
    (r.getBestBlock _).expects().returning(bestBlock).anyNumberOfTimes()
    window.foreach { case (n, bOpt) =>
      (r.getBlockByNumber _).expects(branch, n).returning(bOpt).anyNumberOfTimes()
    }
    r
  }

  /** Window covering bestNum-20..bestNum, all empty (no txs → oracle returns floor). */
  private def emptyWindow(bestNum: BigInt): Map[BigInt, Option[Block]] =
    (BigInt(0).max(bestNum - 20) to bestNum).map(n => n -> None).toMap

  /** Build EthTxService with a mocked reader and given config. */
  private def svc(reader: BlockchainReader, cfg: BlockchainConfig = defaultCfg): EthTxService = {
    implicit val implCfg: BlockchainConfig = cfg
    val probe = TestProbe()
    new EthTxService(
      stub[Blockchain],
      reader,
      stub[Mining],
      probe.ref,
      5.seconds,
      stub[TransactionMappingStorage]
    )
  }

  /** Build EthBlocksService with stubbed dependencies (uses Config singleton for blockchainConfig). */
  private def blocksSvc(): EthBlocksService =
    new EthBlocksService(
      stub[Blockchain],
      stub[BlockchainReader],
      stub[Mining],
      stub[BlockQueue]
    )

  // ─── A. minimumGasPrice() — floor formula ─────────────────────────────────

  "minimumGasPrice()" should "return 1 wei on a pre-EIP-1559 chain (baseFee absent)" taggedAs (UnitTest, RPCTest) in {
    val r = mockReader(bestNum = 5, window = emptyWindow(5), bestBlock = Some(blk(5, Nil)))
    // bestBlock has HefEmpty → baseFee = None → pre-EIP-1559 path → floor = 1 wei
    svc(r).minimumGasPrice() shouldEqual BigInt(1)
  }

  it should "return 1 wei when baseFee=0, baseFeeFloor=0, minTip=0 (degenerate config guard)" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    val zeroCfg = defaultCfg.copy(baseFeeFloor = BigInt(0), minTip = BigInt(0))
    val bestB = blk(1, Nil, baseFeeOpt = Some(BigInt(0)))
    val r = mockReader(bestNum = 1, window = emptyWindow(1), bestBlock = Some(bestB))
    // baseFee = 0, floor = 0, tip = 0 → raw = 0 → .max(1) = 1 wei
    svc(r, zeroCfg).minimumGasPrice() shouldEqual BigInt(1)
  }

  it should "return 2 gwei on ETC post-Olympia canonical (baseFee=1gwei, floor=1gwei, tip=1gwei)" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    val bestB = blk(100, Nil, baseFeeOpt = Some(gwei))
    val r = mockReader(bestNum = 100, window = emptyWindow(100), bestBlock = Some(bestB))
    svc(r, etcOlympiaCfg).minimumGasPrice() shouldEqual 2 * gwei
  }

  it should "use currentBaseFee when it exceeds baseFeeFloor (baseFee=3gwei, floor=1gwei, tip=1gwei)" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    val bestB = blk(100, Nil, baseFeeOpt = Some(3 * gwei))
    val r = mockReader(bestNum = 100, window = emptyWindow(100), bestBlock = Some(bestB))
    // floor = max(3g, 1g) + 1g = 3g + 1g = 4g
    svc(r, etcOlympiaCfg).minimumGasPrice() shouldEqual 4 * gwei
  }

  it should "return baseFee + 1gwei on ETH post-London (baseFee=5gwei, floor=0, tip=1gwei)" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    val bestB = blk(100, Nil, baseFeeOpt = Some(5 * gwei))
    val r = mockReader(bestNum = 100, window = emptyWindow(100), bestBlock = Some(bestB))
    svc(r, ethLondonCfg).minimumGasPrice() shouldEqual 6 * gwei
  }

  it should "handle a high baseFee (100gwei) on ETH correctly" taggedAs (UnitTest, RPCTest) in {
    val bestB = blk(100, Nil, baseFeeOpt = Some(100 * gwei))
    val r = mockReader(bestNum = 100, window = emptyWindow(100), bestBlock = Some(bestB))
    svc(r, ethLondonCfg).minimumGasPrice() shouldEqual 101 * gwei
  }

  it should "respect a custom minTip = 2 gwei with ETC floor" taggedAs (UnitTest, RPCTest) in {
    val cfg = defaultCfg.copy(baseFeeFloor = gwei, minTip = 2 * gwei)
    val bestB = blk(100, Nil, baseFeeOpt = Some(gwei))
    val r = mockReader(bestNum = 100, window = emptyWindow(100), bestBlock = Some(bestB))
    // max(1g, 1g) + 2g = 3g
    svc(r, cfg).minimumGasPrice() shouldEqual 3 * gwei
  }

  it should "return baseFee+tip when baseFeeFloor=0 and baseFee dominates (1gwei+1gwei)" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    val cfg = defaultCfg.copy(baseFeeFloor = BigInt(0), minTip = gwei)
    val bestB = blk(100, Nil, baseFeeOpt = Some(gwei))
    val r = mockReader(bestNum = 100, window = emptyWindow(100), bestBlock = Some(bestB))
    svc(r, cfg).minimumGasPrice() shouldEqual 2 * gwei
  }

  // ─── B. suggestGasPrice() — oracle algorithm ──────────────────────────────

  "suggestGasPrice()" should "return the floor on genesis (bestBlock=0, no txs)" taggedAs (UnitTest, RPCTest) in {
    val r = mockReader(bestNum = 0, window = Map(BigInt(0) -> None), bestBlock = None)
    // Pre-EIP-1559 default config: floor = 1 wei
    svc(r).suggestGasPrice() shouldEqual BigInt(1)
  }

  it should "return the ETC floor (2 gwei) when all blocks in the window are empty" taggedAs (UnitTest, RPCTest) in {
    val bestB = blk(20, Nil, baseFeeOpt = Some(gwei))
    val r = mockReader(bestNum = 20, window = emptyWindow(20), bestBlock = Some(bestB))
    svc(r, etcOlympiaCfg).suggestGasPrice() shouldEqual 2 * gwei
  }

  it should "guard against negative block range on a fresh chain (bestBlock=5)" taggedAs (UnitTest, RPCTest) in {
    // bestBlock=5 → oracle queries 0..5 (not -15..5)
    val bestB = blk(5, Nil, baseFeeOpt = Some(gwei))
    val window = (BigInt(0) to BigInt(5)).map(n => n -> None).toMap
    val r = mockReader(bestNum = 5, window = window, bestBlock = Some(bestB))
    // Should succeed without errors; no txs → returns floor
    noException should be thrownBy svc(r, etcOlympiaCfg).suggestGasPrice()
    svc(r, etcOlympiaCfg).suggestGasPrice() shouldEqual 2 * gwei
  }

  it should "return exactly floor when the single tx's gasPrice equals the floor" taggedAs (UnitTest, RPCTest) in {
    val bestB = blk(20, Nil, baseFeeOpt = Some(gwei))
    val oneTxBlk = blk(20, Seq(fakeTx(2 * gwei)), baseFeeOpt = Some(gwei))
    val window = emptyWindow(20) + (BigInt(20) -> Some(oneTxBlk))
    val r = mockReader(bestNum = 20, window = window, bestBlock = Some(bestB))
    svc(r, etcOlympiaCfg).suggestGasPrice() shouldEqual 2 * gwei
  }

  it should "clamp to floor when all sampled txs price below floor (post-Olympia)" taggedAs (UnitTest, RPCTest) in {
    val bestB = blk(20, Nil, baseFeeOpt = Some(gwei))
    val lowBlk = blk(20, Seq(fakeTx(BigInt(1))), baseFeeOpt = Some(gwei))
    val window = emptyWindow(20) + (BigInt(20) -> Some(lowBlk))
    val r = mockReader(bestNum = 20, window = window, bestBlock = Some(bestB))
    svc(r, etcOlympiaCfg).suggestGasPrice() shouldEqual 2 * gwei
  }

  it should "compute the 60th percentile of uniform-price txs (5 gwei)" taggedAs (UnitTest, RPCTest) in {
    // 20 blocks × 3 txs each at 5 gwei → 60 data points all = 5 gwei → 60th pct = 5 gwei
    val txs3 = Seq.fill(3)(fakeTx(5 * gwei))
    val window = (BigInt(0) to BigInt(20)).map { n =>
      n -> Some(blk(n, txs3, baseFeeOpt = Some(gwei)))
    }.toMap
    val bestB = blk(20, Nil, baseFeeOpt = Some(gwei))
    val r = mockReader(bestNum = 20, window = window, bestBlock = Some(bestB))
    svc(r, etcOlympiaCfg).suggestGasPrice() shouldEqual 5 * gwei
  }

  it should "take the 60th percentile of a spread of prices, not the median" taggedAs (UnitTest, RPCTest) in {
    // 60 txs with prices 1*gwei..60*gwei.
    // Sorted idx 0..59. 60th pct idx = min(60*60/100, 59) = min(36, 59) = 36 → price = 37*gwei.
    // But clamped to floor (2 gwei) → 37 gwei > 2 gwei, so result = 37 gwei.
    // 20 blocks × 3 txs = 60 data points covering prices 1g..60g.
    // Strategy: block i gets txs at prices i, i+20, i+40 gwei (i in 1..20).
    val txBlocks = (BigInt(1) to BigInt(20)).map { i =>
      val blockTxs = Seq(fakeTx(i * gwei), fakeTx((i + 20) * gwei), fakeTx((i + 40) * gwei))
      i -> Some(blk(i, blockTxs, baseFeeOpt = Some(gwei)))
    }.toMap
    val window = txBlocks + (BigInt(0) -> None) // oracle queries 0..20; block 0 is empty
    val bestB = blk(20, Nil, baseFeeOpt = Some(gwei))
    val r = mockReader(bestNum = 20, window = window, bestBlock = Some(bestB))
    val result = svc(r, etcOlympiaCfg).suggestGasPrice()
    // 60 sorted prices: 1g, 2g, ..., 60g. 60th pct idx = min(36, 59) = 36 → 37th price = 37g.
    result shouldEqual 37 * gwei
  }

  it should "cap at 500 gwei when all sampled prices exceed the cap" taggedAs (UnitTest, RPCTest) in {
    val txs = Seq.fill(3)(fakeTx(1000 * gwei))
    val window = (BigInt(0) to BigInt(20)).map(n => n -> Some(blk(n, txs))).toMap
    val r = mockReader(bestNum = 20, window = window, bestBlock = Some(blk(20, Nil)))
    svc(r).suggestGasPrice() shouldEqual cap500
  }

  it should "exclude coinbase transactions from the sample" taggedAs (UnitTest, RPCTest) in {
    // Use a real fixture tx (valid signature, recoverable sender).
    // Set the block coinbase = tx sender → the tx must be excluded.
    // Fill the rest of the window with blocks that have no txs.
    val tx = fixtureTxs.head
    val sender = SignedTransaction.getSender(tx)(defaultCfg).value

    val coinbase = sender.bytes
    val coinbaseBlk = blk(20, Seq(tx), coinbase = coinbase)
    val window = emptyWindow(20) + (BigInt(20) -> Some(coinbaseBlk))
    val r = mockReader(bestNum = 20, window = window, bestBlock = Some(blk(20, Nil)))
    // Only tx excluded → window is effectively empty → returns floor
    svc(r).suggestGasPrice() shouldEqual BigInt(1)
  }

  it should "include transactions that are NOT from the coinbase" taggedAs (UnitTest, RPCTest) in {
    val tx = fixtureTxs.head
    // Coinbase is some OTHER address (zeroAddr), not the tx sender
    val normalBlk = blk(20, Seq(tx), coinbase = zeroAddr)
    val window = emptyWindow(20) + (BigInt(20) -> Some(normalBlk))
    val r = mockReader(bestNum = 20, window = window, bestBlock = Some(blk(20, Nil)))
    // Tx is included → oracle returns tx.gasPrice (20 gwei) as the only sample
    val result = svc(r).suggestGasPrice()
    result shouldEqual tx.tx.gasPrice
  }

  it should "return the floor when an all-coinbase-tx block exhausts the window" taggedAs (UnitTest, RPCTest) in {
    // Every block in the window has only coinbase txs → oracle sees no valid samples → returns floor
    val tx = fixtureTxs.head
    val sender = SignedTransaction.getSender(tx)(defaultCfg).value
    val window = (BigInt(0) to BigInt(20)).map { n =>
      n -> Some(blk(n, Seq(tx), coinbase = sender.bytes))
    }.toMap
    val r = mockReader(bestNum = 20, window = window, bestBlock = Some(blk(20, Nil)))
    svc(r).suggestGasPrice() shouldEqual BigInt(1)
  }

  it should "sample at most 3 transactions per block (per-block limit)" taggedAs (UnitTest, RPCTest) in {
    // One block with 100 txs — the oracle should only take the first 3
    // All at price X; if it takes more it'd still be X, so we vary: first 3 at 5g, rest at 10g.
    val first3 = Seq.fill(3)(fakeTx(5 * gwei))
    val rest97 = Seq.fill(97)(fakeTx(10 * gwei))
    val bigBlk = blk(20, first3 ++ rest97)
    val window = emptyWindow(20) + (BigInt(20) -> Some(bigBlk))
    val r = mockReader(bestNum = 20, window = window, bestBlock = Some(blk(20, Nil)))
    // 3 samples at 5g → 60th pct = 5g (all equal)
    svc(r).suggestGasPrice() shouldEqual 5 * gwei
  }

  it should "sample consistently across multiple blocks (20 × 3 = 60 data points)" taggedAs (UnitTest, RPCTest) in {
    // Each of 20 blocks contributes 3 txs at 4 gwei. Total 60 samples all equal → pct60 = 4g.
    val txs3 = Seq.fill(3)(fakeTx(4 * gwei))
    val window = (BigInt(1) to BigInt(20)).map(n => n -> Some(blk(n, txs3))).toMap +
      (BigInt(0) -> None)
    val r = mockReader(bestNum = 20, window = window, bestBlock = Some(blk(20, Nil)))
    svc(r).suggestGasPrice() shouldEqual 4 * gwei
  }

  it should "never return 0 regardless of config (property: result ≥ 1)" taggedAs (UnitTest, RPCTest) in {
    // Zero-config chain; oracle window is empty
    val zeroCfg = defaultCfg.copy(baseFeeFloor = BigInt(0), minTip = BigInt(0))
    val r = mockReader(bestNum = 5, window = emptyWindow(5), bestBlock = Some(blk(5, Nil)))
    svc(r, zeroCfg).suggestGasPrice() should be >= BigInt(1)
  }

  it should "always stay at or below 500 gwei (property: result ≤ cap)" taggedAs (UnitTest, RPCTest) in {
    val extremeTxs = Seq.fill(3)(fakeTx(BigInt(10000) * gwei))
    val window = (BigInt(0) to BigInt(20)).map(n => n -> Some(blk(n, extremeTxs))).toMap
    val r = mockReader(bestNum = 20, window = window, bestBlock = Some(blk(20, Nil)))
    svc(r).suggestGasPrice() should be <= cap500
  }

  it should "always return at least the floor (property: result ≥ minimumGasPrice)" taggedAs (UnitTest, RPCTest) in {
    val txs = Seq.fill(3)(fakeTx(1 * gwei)) // 1g < 2g floor for etcOlympiaCfg
    val bestB = blk(20, Nil, baseFeeOpt = Some(gwei))
    val window = (BigInt(0) to BigInt(20)).map(n => n -> Some(blk(n, txs, baseFeeOpt = Some(gwei)))).toMap
    val r = mockReader(bestNum = 20, window = window, bestBlock = Some(bestB))
    val service = svc(r, etcOlympiaCfg)
    service.suggestGasPrice() should be >= service.minimumGasPrice()
  }

  // ─── C. eth_gasPrice RPC wrapper ──────────────────────────────────────────

  "getGetGasPrice()" should "return Right(GetGasPriceResponse(v)) where v ≥ 1" taggedAs (UnitTest, RPCTest) in {
    val r = mockReader(bestNum = 5, window = emptyWindow(5), bestBlock = None)
    val response = svc(r).getGetGasPrice(GetGasPriceRequest()).unsafeRunSync()
    response shouldBe a[Right[_, _]]
    response.toOption.value.price should be >= BigInt(1)
  }

  it should "always return a non-zero value (never 0x0 on any chain)" taggedAs (UnitTest, RPCTest) in {
    val zeroCfg = defaultCfg.copy(baseFeeFloor = BigInt(0), minTip = BigInt(0))
    val r = mockReader(bestNum = 0, window = Map(BigInt(0) -> None), bestBlock = None)
    val response = svc(r, zeroCfg).getGetGasPrice(GetGasPriceRequest()).unsafeRunSync()
    response.toOption.value.price should be >= BigInt(1)
  }

  it should "wrap suggestGasPrice() — RPC value equals oracle value" taggedAs (UnitTest, RPCTest) in {
    val txs = Seq.fill(3)(fakeTx(7 * gwei))
    val window = (BigInt(0) to BigInt(20)).map(n => n -> Some(blk(n, txs))).toMap
    val r = mockReader(bestNum = 20, window = window, bestBlock = Some(blk(20, Nil)))
    val service = svc(r)
    val oracleVal = service.suggestGasPrice()
    val rpcVal = service.getGetGasPrice(GetGasPriceRequest()).unsafeRunSync().toOption.value.price
    rpcVal shouldEqual oracleVal
  }

  // ─── D. eth_maxPriorityFeePerGas ──────────────────────────────────────────

  "maxPriorityFeePerGas()" should "return Right(MaxPriorityFeePerGasResponse(...))" taggedAs (UnitTest, RPCTest) in {
    val response = blocksSvc().maxPriorityFeePerGas(MaxPriorityFeePerGasRequest()).unsafeRunSync()
    response shouldBe a[Right[_, _]]
  }

  it should "return the value from blockchainConfig.minTip (not a hardcoded literal)" taggedAs (UnitTest, RPCTest) in {
    val response = blocksSvc().maxPriorityFeePerGas(MaxPriorityFeePerGasRequest()).unsafeRunSync()
    // EthBlocksService uses Config.blockchains.blockchainConfig; test config has minTip = 1 (default)
    response.toOption.value.maxPriorityFeePerGas shouldEqual Config.blockchains.blockchainConfig.minTip
  }

  it should "return a non-negative value on any network" taggedAs (UnitTest, RPCTest) in {
    val response = blocksSvc().maxPriorityFeePerGas(MaxPriorityFeePerGasRequest()).unsafeRunSync()
    response.toOption.value.maxPriorityFeePerGas should be >= BigInt(0)
  }

  // ─── E. TransactionRequest oracle injection ────────────────────────────────

  "TransactionRequest.toTransaction(nonce, suggestedGasPrice)" should "use user-supplied gasPrice when provided" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    val userPrice: BigInt = 5 * gwei
    val req = TransactionRequest(from = Address(zeroAddr), gasPrice = Some(userPrice))
    val oracle = 10 * gwei // oracle would have suggested 10g
    val tx = req.toTransaction(BigInt(0), oracle)
    tx.gasPrice shouldEqual userPrice
  }

  it should "use the oracle price when no gasPrice provided by the user" taggedAs (UnitTest, RPCTest) in {
    val oracle = 4 * gwei
    val req = TransactionRequest(from = Address(zeroAddr))
    val tx = req.toTransaction(BigInt(0), oracle)
    tx.gasPrice shouldEqual oracle
  }

  it should "use oracle price of 1 wei (minimum valid) when oracle returns 1 wei" taggedAs (UnitTest, RPCTest) in {
    val req = TransactionRequest(from = Address(zeroAddr))
    val tx = req.toTransaction(BigInt(0), BigInt(1))
    tx.gasPrice shouldEqual BigInt(1)
  }

  it should "respect user's explicit gasPrice = 0 over any oracle value" taggedAs (UnitTest, RPCTest) in {
    val req = TransactionRequest(from = Address(zeroAddr), gasPrice = Some(BigInt(0)))
    val tx = req.toTransaction(BigInt(0), 5 * gwei)
    tx.gasPrice shouldEqual BigInt(0)
  }

  // ─── F. Network-specific integration regression ───────────────────────────

  "Gas oracle network regression" should "return 1 wei on ETC pre-Olympia (legacy chain, no baseFee)" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    val legacyBestB = blk(100, Nil) // HefEmpty → baseFee = None
    val window = emptyWindow(100)
    val r = mockReader(bestNum = 100, window = window, bestBlock = Some(legacyBestB))
    // Test config baseFeeFloor=0, minTip=1 (1 wei). Pre-EIP-1559 path → 1 wei.
    svc(r, defaultCfg).suggestGasPrice() shouldEqual BigInt(1)
  }

  it should "return ≥ 2 gwei on ETC post-Olympia (1g baseFee + 1g floor + 1g tip)" taggedAs (UnitTest, RPCTest) in {
    val postB = blk(500, Nil, baseFeeOpt = Some(gwei))
    val window = emptyWindow(500)
    val r = mockReader(bestNum = 500, window = window, bestBlock = Some(postB))
    svc(r, etcOlympiaCfg).suggestGasPrice() should be >= 2 * gwei
  }

  it should "return ≥ 2 gwei on Mordor post-Olympia (same config as ETC mainnet)" taggedAs (UnitTest, RPCTest) in {
    // Mordor uses identical baseFeeFloor=1g, minTip=1g — config is the same
    val mordorB = blk(300, Nil, baseFeeOpt = Some(gwei))
    val window = emptyWindow(300)
    val r = mockReader(bestNum = 300, window = window, bestBlock = Some(mordorB))
    svc(r, etcOlympiaCfg).suggestGasPrice() should be >= 2 * gwei
  }

  it should "return ≥ 3 gwei on ETH post-London with 2 gwei baseFee (baseFeeFloor=0, minTip=1g)" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    val ethB = blk(1000, Nil, baseFeeOpt = Some(2 * gwei))
    val window = emptyWindow(1000)
    val r = mockReader(bestNum = 1000, window = window, bestBlock = Some(ethB))
    // floor = max(2g, 0) + 1g = 3g
    svc(r, ethLondonCfg).suggestGasPrice() should be >= 3 * gwei
  }

  it should "return ≥ 8 gwei on Sepolia with 7 gwei baseFee (floor=0, tip=1g)" taggedAs (UnitTest, RPCTest) in {
    val sepoliaB = blk(200, Nil, baseFeeOpt = Some(7 * gwei))
    val window = emptyWindow(200)
    val r = mockReader(bestNum = 200, window = window, bestBlock = Some(sepoliaB))
    svc(r, ethLondonCfg).suggestGasPrice() should be >= 8 * gwei
  }

  it should "track a rising baseFee — oracle floor increases proportionally" taggedAs (UnitTest, RPCTest) in {
    val lowBaseFee = blk(5, Nil, baseFeeOpt = Some(gwei))
    val highBaseFee = blk(5, Nil, baseFeeOpt = Some(50 * gwei))
    val window = emptyWindow(5)
    val rLow = mockReader(bestNum = 5, window = window, bestBlock = Some(lowBaseFee))
    val rHigh = mockReader(bestNum = 5, window = window, bestBlock = Some(highBaseFee))
    val lowResult = svc(rLow, ethLondonCfg).suggestGasPrice()
    val highResult = svc(rHigh, ethLondonCfg).suggestGasPrice()
    highResult should be > lowResult
  }
}
