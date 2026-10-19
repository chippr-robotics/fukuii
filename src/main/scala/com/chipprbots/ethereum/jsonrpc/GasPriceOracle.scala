package com.chipprbots.ethereum.jsonrpc

import scala.collection.mutable

import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.BlockHash
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.domain.SignedTransaction
import com.chipprbots.ethereum.domain.Transaction
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.Logger
import com.chipprbots.ethereum.utils.NetworkType

/** go-ethereum's gas price oracle (`eth/gasprice/gasprice.go`, `Oracle.SuggestTipCap` and `getBlockValues`): the tip
  * eth_maxPriorityFeePerGas answers, the tip eth_gasPrice adds the head's base fee to, and the fee default
  * eth_sendTransaction / eth_signTransaction / eth_fillTransaction fill in.
  *
  * core-geth runs the same package on ETC (its `eth/gasprice/gasprice.go` differs from geth master only in plumbing),
  * so one oracle serves both chain families. Before a base fee exists (ETC/Mordor before Olympia, ETH before London) a
  * transaction's effective tip is its whole gas price, and the suggestion is the legacy gas price suggestion.
  *
  * Algorithm, from the head down (never the genesis block):
  *   - each block gives the `SampleNumber` (3) lowest effective tips at its own base fee, skipping tips below
  *     `ignorePrice` and transactions sent by the block's coinbase;
  *   - a block that gives nothing (empty, or only ignored / coinbase transactions) gives the last suggestion instead;
  *   - `blocks` blocks are sampled; while blocks give a single value, more are sampled, up to `2 * blocks`;
  *   - the suggestion is the value at index `(n - 1) * percentile / 100` of the sorted samples, capped at `maxPrice`;
  *   - it is cached by head hash, and is the starting "last suggestion" (`defaultPrice`) until a block gives a sample:
  *     1 mwei on ETH-family chains (geth), 1 gwei on ETC (core-geth), see [[GasPriceOracle.defaultPriceFor]].
  *
  * Deviations from geth:
  *   - geth fetches the window's blocks concurrently, so which extra blocks it fetches depends on arrival order; here
  *     the blocks are taken head-first, one at a time, which is the outcome of in-order arrival. Deterministic.
  *   - a block of the window that is not stored locally (a node still syncing bodies) is skipped: it adds no sample and
  *     does not extend the window. geth fails the request with the lookup error; here eth_gasPrice keeps answering from
  *     the blocks that are stored, or the last suggestion when none are.
  */
class GasPriceOracle(blockchainReader: BlockchainReader, val config: GasPriceOracle.Config)(using
    blockchainConfig: BlockchainConfig
) extends Logger:
  import GasPriceOracle.*

  // (head hash the suggestion was computed at, suggestion): geth `lastHead` / `lastPrice`. Written under fetchLock.
  @volatile private var last: (Option[BlockHash], BigInt) = (None, config.defaultPrice)
  private val fetchLock = new Object

  /** go-ethereum `Oracle.SuggestTipCap`: the tip that gives a transaction a high chance of inclusion in the next
    * blocks. Add the head's base fee (when it has one) for a legacy gas price.
    */
  def suggestTipCap(): BigInt =
    blockchainReader.getBestBlock match
      case None => last._2
      case Some(head) =>
        val headHash = head.header.hash
        val (lastHead, lastPrice) = last
        if lastHead.contains(headHash) then lastPrice
        else
          fetchLock.synchronized {
            // the fetch that held the lock may have computed this head already
            val (lastHead, lastPrice) = last
            if lastHead.contains(headHash) then lastPrice
            else
              val price = sample(head, lastPrice)
              last = (Some(headHash), price)
              price
          }

  /** go-ethereum `EthereumAPI.GasPrice`: the suggested tip, plus the head's base fee when the head has one. */
  def suggestGasPrice(): BigInt =
    val tip = suggestTipCap()
    blockchainReader.getBestBlock.flatMap(_.header.baseFee).fold(tip)(tip + _)

  private def sample(head: Block, lastPrice: BigInt): BigInt =
    val pending = mutable.Queue.empty[BigInt]
    var number = head.header.number.value
    while pending.size < config.blocks && number > 0 do
      pending.enqueue(number)
      number -= 1
    val results = mutable.ArrayBuffer.empty[BigInt]
    while pending.nonEmpty do
      val blockNumber = pending.dequeue()
      val block =
        if blockNumber == head.header.number.value then Some(head)
        else blockchainReader.getBlockByNumber(blockchainReader.getBestBranch, blockNumber)
      block match
        case None =>
          // not stored (a node still syncing bodies): no data, rather than geth's lookup error
          log.debug("Gas price oracle: block {} is not stored, not sampled", blockNumber)
        case Some(b) =>
          val values =
            Some(blockValues(b, SampleNumber, config.ignorePrice)).filter(_.nonEmpty).getOrElse(Seq(lastPrice))
          // geth: `len(res.values) == 1 && len(results)+1+exp < checkBlocks*2 && number > 0`, exp being the fetches
          // still outstanding after this one
          if values.size == 1 && results.size + 1 + pending.size < config.blocks * 2 && number > 0 then
            pending.enqueue(number)
            number -= 1
          results ++= values
    val price =
      if results.isEmpty then lastPrice
      else results.sorted.apply((results.size - 1) * config.percentile / 100)
    price.min(config.maxPrice)

object GasPriceOracle:

  /** go-ethereum `sampleNumber`: transactions sampled per block. */
  val SampleNumber: Int = 3

  private val Gwei: BigInt = BigInt(1000000000)

  /** Config key holding the oracle settings (geth `--gpo.*`). */
  val ConfigPath: String = "network.rpc.gpo"

  /** go-ethereum `gasprice.Config`, plus the oracle's start price (geth's `startPrice` argument, `Miner.GasPrice`).
    *
    * @param blocks
    *   recent blocks sampled (`--gpo.blocks`)
    * @param percentile
    *   percentile of the sampled tips suggested (`--gpo.percentile`)
    * @param maxHeaderHistory
    *   most blocks eth_feeHistory answers for without reward percentiles
    * @param maxBlockHistory
    *   most blocks eth_feeHistory answers for with reward percentiles
    * @param maxPrice
    *   cap on the suggested tip, in wei (`--gpo.maxprice`); the base fee is added after the cap
    * @param ignorePrice
    *   tips below this, in wei, are not sampled (`--gpo.ignoreprice`)
    * @param defaultPrice
    *   the suggestion before any block gives a sample, and what a block without samples stands for until then
    */
  final case class Config(
      blocks: Int,
      percentile: Int,
      maxHeaderHistory: Int,
      maxBlockHistory: Int,
      maxPrice: BigInt,
      ignorePrice: BigInt,
      defaultPrice: BigInt
  ):
    /** go-ethereum `NewOracle` sanitization: out-of-range settings fall back as geth's do. */
    def sanitized: Config =
      Config(
        blocks = blocks.max(1),
        percentile = percentile.max(0).min(100),
        maxHeaderHistory = maxHeaderHistory.max(1),
        maxBlockHistory = maxBlockHistory.max(1),
        maxPrice = if maxPrice <= 0 then DefaultMaxPrice else maxPrice,
        ignorePrice = if ignorePrice <= 0 then DefaultIgnorePrice else ignorePrice,
        defaultPrice = defaultPrice.max(0)
      )

  /** go-ethereum `DefaultMaxPrice` (500 gwei) and `DefaultIgnorePrice` (2 wei). */
  val DefaultMaxPrice: BigInt = Gwei * 500
  val DefaultIgnorePrice: BigInt = BigInt(2)

  /** Start price on ETH-family chains: go-ethereum's `Miner.GasPrice` default, `params.GWei / 1000` = 1 mwei
    * (`miner/miner.go`, passed to `NewOracle` as `startPrice` by `eth/backend.go`).
    */
  val EthDefaultPrice: BigInt = Gwei / 1000

  /** Start price on ETC-family chains: core-geth's `Miner.GasPrice` default, 1 gwei (`miner/miner.go`, the oracle's
    * `Default` in `eth/backend.go`).
    */
  val EtcDefaultPrice: BigInt = Gwei

  /** The oracle's start price for the chain: its conf's `gpo-default-price` when set, else its family's reference
    * client's.
    */
  def defaultPriceFor(blockchainConfig: BlockchainConfig): BigInt =
    blockchainConfig.gasPriceOracleDefault.getOrElse(
      if blockchainConfig.networkType == NetworkType.ETH then EthDefaultPrice else EtcDefaultPrice
    )

  /** go-ethereum `ethconfig.FullNodeGPO`, with the chain's start price ([[defaultPriceFor]]). */
  def defaultConfig(using blockchainConfig: BlockchainConfig): Config = Config(
    blocks = 20,
    percentile = 60,
    maxHeaderHistory = 1024,
    maxBlockHistory = 1024,
    maxPrice = DefaultMaxPrice,
    ignorePrice = DefaultIgnorePrice,
    defaultPrice = defaultPriceFor(blockchainConfig)
  )

  /** The oracle settings under `fukuii.network.rpc.gpo`; an absent key keeps its default ([[defaultConfig]]), so
    * `default-price` there overrides the chain's start price only when set.
    */
  def configFrom(fukuiiConfig: com.typesafe.config.Config)(using blockchainConfig: BlockchainConfig): Config =
    def int(key: String, default: Int): Int =
      val path = s"$ConfigPath.$key"
      if fukuiiConfig.hasPath(path) then fukuiiConfig.getInt(path) else default
    def big(key: String, default: BigInt): BigInt =
      val path = s"$ConfigPath.$key"
      if fukuiiConfig.hasPath(path) then BigInt(fukuiiConfig.getString(path)) else default
    val d = defaultConfig
    Config(
      blocks = int("blocks", d.blocks),
      percentile = int("percentile", d.percentile),
      maxHeaderHistory = int("max-header-history", d.maxHeaderHistory),
      maxBlockHistory = int("max-block-history", d.maxBlockHistory),
      maxPrice = big("max-price", d.maxPrice),
      ignorePrice = big("ignore-price", d.ignorePrice),
      defaultPrice = big("default-price", d.defaultPrice)
    ).sanitized

  /** A transaction's effective tip at `baseFee` (go-ethereum `Transaction.EffectiveGasTip`): `min(tipCap, feeCap -
    * baseFee)` for fee-market transactions, `gasPrice - baseFee` for the others; with no base fee, the tip cap (the gas
    * price of a legacy or access-list transaction).
    */
  def effectiveTip(tx: Transaction, baseFee: Option[BigInt]): BigInt =
    Transaction.effectiveGasPrice(tx, baseFee) - baseFee.getOrElse(BigInt(0))

  /** go-ethereum `getBlockValues`: the `limit` lowest effective tips of `block` at its base fee, without tips below
    * `ignoreUnder` and transactions whose sender is the block's coinbase (or cannot be recovered).
    */
  def blockValues(block: Block, limit: Int, ignoreUnder: BigInt)(using blockchainConfig: BlockchainConfig): Seq[BigInt] =
    val baseFee = block.header.baseFee
    val coinbase = block.header.beneficiary
    block.body.transactionList.iterator
      .map(stx => (stx, effectiveTip(stx.tx, baseFee)))
      .toSeq
      .sortBy(_._2)
      .iterator
      .filter((_, tip) => tip >= ignoreUnder)
      .filter((stx, _) => SignedTransaction.getSender(stx).exists(_.bytes != coinbase))
      .map(_._2)
      .take(limit)
      .toSeq
