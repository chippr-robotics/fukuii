package com.chipprbots.ethereum.ledger

import org.apache.pekko.util.ByteString

import org.scalatest.Tag
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.consensus.validators.std.StdSignedTransactionValidator
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.*
import com.chipprbots.ethereum.ledger.UpfrontCostOverflowSpec.*
import com.chipprbots.ethereum.ledger.UpfrontCostOverflowSpec.TxType.*
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.ForkTimestamps
import com.chipprbots.ethereum.utils.NetworkType

/** The balance a sender must hold before execution — `gasLimit * price + value`, plus the blob cost of a blob
  * transaction, where the price is `gasPrice`, or `maxFeePerGas` for the dynamic-fee types — is compared with the
  * balance as an unbounded integer, never reduced mod 2^256.
  *
  * Reference: go-ethereum and core-geth `buyGas` (core/state_transition.go) reject a required balance that does not fit
  * 256 bits as `ErrInsufficientFunds`, and otherwise compare the exact figure with the balance.
  *
  * The cases drive the block-execution loop itself — `BlockPreparator.executeTransactions`, which `BlockExecution`
  * calls and which hands the cost to `StdSignedTransactionValidator` — for every transaction type each chain admits:
  * ETC before Olympia (types 0, 1) and at Olympia (0, 1, 2, 4), both by block number; ETH Osaka and ETH Amsterdam (0 to
  * 4), both by timestamp. A rejected transaction must leave every balance as it was.
  *
  * The controls pin what must not move: an ordinary transaction executes; a cost under 2^256 that the sender cannot pay
  * is rejected; a sender holding the maximum balance pays a cost of exactly 2^256 - 1, with every balance exact; and a
  * dynamic-fee transaction's balance is checked at its fee cap while it is charged at the effective gas price.
  */
// scalastyle:off magic.number
class UpfrontCostOverflowSpec extends AnyFlatSpec with Matchers with AmsterdamFixtureVectors:

  private val Recipient: Address = Address(0x42)
  private val Coinbase: Address = Address(0xc0ffee)

  /** The real validator and VM: the test setup's own preparator validates nothing. */
  private val preparator =
    new BlockPreparator(new VMImpl, StdSignedTransactionValidator, setup.blockchain, setup.blockchainReader)

  // ── Chains ─────────────────────────────────────────────────────────────────────────────────────────────────────────

  final private case class Chain(
      label: String,
      config: BlockchainConfig,
      header: BlockHeader,
      types: Seq[TxType],
      // The gas a plain value transfer to an existing account uses, where it is the flat 21,000.
      plainTransferGas: Option[BigInt],
      tag: Tag
  ):
    val baseFee: BigInt = header.baseFee.getOrElse(BigInt(0))

  private val OlympiaBlock = BigInt(1000)

  /** Mordor-style ETC: chain id 63, every fork by block number, no timestamp forks at all. */
  private val etcConfig: BlockchainConfig =
    setup.blockchainConfig
      .copy(networkType = NetworkType.ETC, chainId = ChainId(63), forkTimestamps = ForkTimestamps())
      .withUpdatedForkBlocks(
        _.copy(
          frontierBlockNumber = 0,
          homesteadBlockNumber = 0,
          eip150BlockNumber = 0,
          eip155BlockNumber = 0,
          eip160BlockNumber = 0,
          eip161BlockNumber = 0,
          byzantiumBlockNumber = 0,
          constantinopleBlockNumber = 0,
          petersburgBlockNumber = 0,
          istanbulBlockNumber = 0,
          atlantisBlockNumber = 0,
          aghartaBlockNumber = 0,
          phoenixBlockNumber = 0,
          magnetoBlockNumber = 0,
          berlinBlockNumber = 0,
          mystiqueBlockNumber = 0,
          spiralBlockNumber = 0,
          olympiaBlockNumber = OlympiaBlock
        )
      )

  private def etcHeader(number: BigInt): BlockHeader =
    Fixtures.Blocks.ValidBlock.header.copy(
      number = BlockNumber(number),
      beneficiary = Coinbase.bytes,
      gasLimit = GasAmount(8_000_000),
      gasUsed = GasAmount.Zero,
      extraFields = if number >= OlympiaBlock then HefPostOlympia(BigInt(1)) else HefEmpty
    )

  /** ETH, Osaka by timestamp (Shanghai 0 / Cancun 60 / Prague 120 / Osaka 180), base fee 1 so a price of 1 is legal. */
  private val ethOsakaHeader: BlockHeader =
    val h = preAmsterdamHeader(46, 180)
    val extra = h.extraFields match
      case p: HefPostPrague => p.copy(baseFee = BigInt(1))
      case other            => throw new IllegalStateException(s"unexpected header shape $other")
    h.copy(beneficiary = Coinbase.bytes, extraFields = extra)

  /** ETH, Amsterdam by timestamp (the same schedule, Amsterdam at 360), base fee 1. */
  private val ethAmsterdamHeader: BlockHeader =
    val h = amsterdamHeader(47, 360)
    val extra = h.extraFields match
      case p: HefPostAmsterdam => p.copy(baseFee = BigInt(1))
      case other               => throw new IllegalStateException(s"unexpected header shape $other")
    h.copy(beneficiary = Coinbase.bytes, extraFields = extra)

  private val chains: Seq[Chain] = Seq(
    Chain(
      "ETC Mordor-style, Spiral rules (pre-Olympia, forBlock)",
      etcConfig,
      etcHeader(OlympiaBlock - 1),
      Seq(Legacy, AccessList),
      Some(BigInt(21_000)),
      ConsensusTest
    ),
    Chain(
      "ETC Mordor-style, Olympia active (forBlock)",
      etcConfig,
      etcHeader(OlympiaBlock),
      Seq(Legacy, AccessList, DynamicFee, SetCode),
      Some(BigInt(21_000)),
      OlympiaTest
    ),
    Chain(
      "ETH Osaka (forTimestamp)",
      preAmsterdamConfig,
      ethOsakaHeader,
      Seq(Legacy, AccessList, DynamicFee, Blob, SetCode),
      Some(BigInt(21_000)),
      ConsensusTest
    ),
    Chain(
      "ETH Amsterdam (forTimestamp)",
      amsterdamConfig,
      ethAmsterdamHeader,
      Seq(Legacy, AccessList, DynamicFee, Blob, SetCode),
      None,
      ConsensusTest
    )
  )

  // ── Transactions, world, observation ───────────────────────────────────────────────────────────────────────────────

  private val BlobHash: BlobVersionedHash =
    BlobVersionedHash(ByteString(0x01.toByte +: Array.fill[Byte](31)(0x07.toByte)))

  /** One authorization tuple whose signature values are out of range (`s = 0`), so it is skipped without touching any
    * account: the list is non-empty, and only the transaction's cost is under test.
    */
  private def skippedAuthorization(chainId: BigInt): SetCodeAuthorization =
    SetCodeAuthorization(chainId, Recipient, nonce = BigInt(0), v = BigInt(0), r = BigInt(1), s = BigInt(0))

  /** A transaction of `txType` to [[Recipient]], reserving [[TxGas]] at `price` per gas — `gasPrice`, or `maxFeePerGas`
    * with no priority fee — carrying `value`, and one blob if it is a blob transaction.
    */
  private def tx(txType: TxType, price: BigInt, value: BigInt, chain: Chain): SignedTransaction =
    val chainId = chain.config.chainId.value
    val nonce = BigInt(0)
    val noTip = BigInt(0)
    val gas = GasAmount(TxGas)
    val to = Some(Recipient)
    val data = ByteString.empty
    val unsigned: Transaction = txType match
      case Legacy =>
        LegacyTransaction(nonce, GasPrice(price), gas, to, value, data)
      case AccessList =>
        TransactionWithAccessList(chainId, nonce, GasPrice(price), gas, to, value, data, Nil)
      case DynamicFee =>
        TransactionWithDynamicFee(chainId, nonce, noTip, price, gas, to, value, data, Nil)
      case Blob =>
        BlobTransaction(chainId, nonce, noTip, price, gas, to, value, data, Nil, BlobBaseFee, List(BlobHash))
      case SetCode =>
        SetCodeTransaction(chainId, nonce, noTip, price, gas, to, value, data, Nil, List(skippedAuthorization(chainId)))
    SignedTransaction.sign(unsigned, senderKeyPair, Some(chainId))

  /** What block execution requires the sender to hold, computed here independently, unbounded. */
  private def requiredBalance(stx: SignedTransaction, txType: TxType): BigInt =
    stx.tx.gasLimit.value * stx.tx.gasPrice.value + stx.tx.value + txType.blobCost

  /** The sender with `senderBalance`, and the recipient already existing (1 wei), so a value transfer creates nothing.
    */
  private def world(senderBalance: BigInt): InMemoryWorldStateProxy =
    setup.emptyWorld
      .saveAccount(senderAddress, Account(nonce = UInt256(0), balance = UInt256(senderBalance)))
      .saveAccount(Recipient, Account(nonce = UInt256(0), balance = UInt256(1)))

  private val Watched: Seq[(String, Address)] =
    Seq("sender" -> senderAddress, "recipient" -> Recipient, "coinbase" -> Coinbase)

  private def balances(w: InMemoryWorldStateProxy): Seq[BigInt] =
    Watched.map { case (_, a) => w.getAccount(a).map(_.balance.toBigInt).getOrElse(BigInt(0)) }

  final private case class Run(
      pre: InMemoryWorldStateProxy,
      result: Either[BlockExecutionError.TxsExecutionError, BlockResult]
  )

  private def run(chain: Chain, stx: SignedTransaction, senderBalance: BigInt): Run =
    val pre = world(senderBalance)
    Run(pre, preparator.executeTransactions(Seq(stx), pre, chain.header)(using chain.config))

  /** What fukuii did with the transaction, in words: the rejection reason, or the balances it produced. */
  private def describe(r: Run): String = r.result match
    case Left(err) => s"rejected: ${err.reason}"
    case Right(br) =>
      val before = balances(r.pre)
      val after = balances(br.worldState)
      val moves = Watched.zip(before.zip(after)).map { case ((name, _), (b, a)) => s"$name $b -> $a" }
      s"ACCEPTED it (gasUsed ${br.gasUsed}, ${br.receipts.size} receipt(s)); balances ${moves.mkString(", ")}; " +
        s"sum of balances changed by ${after.sum - before.sum} wei"

  /** Rejected as insufficient funds by the validator, before anything ran: no receipt, every balance as it was. */
  private def assertRejectedForFunds(chain: Chain, stx: SignedTransaction, senderBalance: BigInt): Unit =
    val r = run(chain, stx, senderBalance)
    r.result match
      case Left(err) =>
        err.reason should startWith("INSUFFICIENT_ACCOUNT_FUNDS")
        err.stateBeforeError.acumReceipts shouldBe empty
        balances(err.stateBeforeError.worldState) shouldBe balances(r.pre)
      case Right(_) =>
        fail(
          s"[${chain.label}] expected INSUFFICIENT_ACCOUNT_FUNDS (go-ethereum / core-geth buyGas), but fukuii ${describe(r)}"
        )

  /** Executed, with each watched balance moved by exactly what the transaction pays and transfers. */
  private def assertPaidExactly(
      chain: Chain,
      txType: TxType,
      stx: SignedTransaction,
      senderBalance: BigInt
  ): BlockResult =
    val r = run(chain, stx, senderBalance)
    val br = r.result.fold(err => fail(s"[${chain.label}] ${txType.label} rejected: ${err.reason}"), identity)
    br.receipts.size shouldBe 1
    val gasUsed = br.receipts.head.cumulativeGasUsed
    gasUsed should ((be > BigInt(0)).and(be <= TxGas))
    val effectiveGasPrice = Transaction.effectiveGasPrice(stx.tx, chain.header.baseFee)
    val value = stx.tx.value
    val before = balances(r.pre)
    withClue(s"[${chain.label}] ${txType.label}: ${describe(r)}: ") {
      balances(br.worldState) shouldBe Seq(
        before(0) - gasUsed * effectiveGasPrice - value - txType.blobCost,
        before(1) + value,
        before(2) + gasUsed * (effectiveGasPrice - chain.baseFee)
      )
    }
    br

  // ── Tests ──────────────────────────────────────────────────────────────────────────────────────────────────────────

  chains.foreach { chain =>
    chain.label should "execute an ordinary affordable transaction (control)" taggedAs (
      UnitTest,
      ConsensusTest,
      chain.tag
    ) in {
      val stx = tx(Legacy, BigInt(1_000_000_000), 1000, chain)
      val br = assertPaidExactly(chain, Legacy, stx, senderBalance = BigInt(10).pow(18))
      chain.plainTransferGas.foreach(g => br.receipts.map(_.cumulativeGasUsed) shouldBe Seq(g))
    }

    chain.types.foreach { txType =>
      val t = txType.label

      it should s"reject a $t transaction whose gas cost alone exceeds 256 bits, leaving every balance unchanged" taggedAs (
        UnitTest,
        ConsensusTest,
        chain.tag
      ) in {
        val stx = tx(txType, TwoPow256 / TxGas, 0, chain)
        (stx.tx.gasLimit.value * stx.tx.gasPrice.value) shouldBe TwoPow256
        stx.tx.gasLimit.value should be <= StdSignedTransactionValidator.TxGasLimitCap
        stx.tx.gasLimit.value should be <= chain.header.gasLimit.value
        assertRejectedForFunds(chain, stx, senderBalance = 0)
      }

      val addedTerms = if txType == Blob then "value and blob cost" else "value"
      it should s"reject a $t transaction whose cost exceeds 256 bits only once its $addedTerms is added" taggedAs (
        UnitTest,
        ConsensusTest,
        chain.tag
      ) in {
        val stx = tx(txType, 1, TwoPow256 - TxGas - txType.blobCost, chain)
        (stx.tx.gasLimit.value * stx.tx.gasPrice.value) should be <= MaxBalance
        if txType == Blob then (stx.tx.gasLimit.value * stx.tx.gasPrice.value + stx.tx.value) should be <= MaxBalance
        requiredBalance(stx, txType) shouldBe TwoPow256
        assertRejectedForFunds(chain, stx, senderBalance = 0)
      }

      it should s"reject a $t transaction costing more than 256 bits even from a sender holding the maximum balance" taggedAs (
        UnitTest,
        ConsensusTest,
        chain.tag
      ) in {
        val stx = tx(txType, TwoPow256 / TxGas, 0, chain)
        requiredBalance(stx, txType) should be > MaxBalance
        assertRejectedForFunds(chain, stx, senderBalance = MaxBalance)
      }

      it should s"reject a $t cost under 2^256 that an empty sender cannot pay (control: no wrap)" taggedAs (
        UnitTest,
        ConsensusTest,
        chain.tag
      ) in {
        val stx = tx(txType, TwoPow256 / TxGas - 1, 0, chain)
        requiredBalance(stx, txType) should be < TwoPow256
        assertRejectedForFunds(chain, stx, senderBalance = 0)
      }

      it should s"let a sender holding the maximum balance pay a $t cost of exactly 2^256 - 1 (control)" taggedAs (
        UnitTest,
        ConsensusTest,
        chain.tag
      ) in {
        val price = TwoPow256 / TxGas - 2
        val stx = tx(txType, price, MaxBalance - TxGas * price - txType.blobCost, chain)
        requiredBalance(stx, txType) shouldBe MaxBalance
        assertPaidExactly(chain, txType, stx, senderBalance = MaxBalance)
      }

      if txType.hasFeeCap then
        it should s"check a $t transaction's balance at maxFeePerGas, not at the effective gas price (control)" taggedAs (
          UnitTest,
          ConsensusTest,
          chain.tag
        ) in {
          // With no priority fee the effective price is the base fee, far below the fee cap: a balance one wei short of
          // the cost at the cap is rejected although it covers the cost at the effective price; the full cost is paid.
          val stx = tx(txType, BigInt(1_000_000_000), 1000, chain)
          val required = requiredBalance(stx, txType)
          Transaction.effectiveGasPrice(stx.tx, chain.header.baseFee) shouldBe chain.baseFee
          assertRejectedForFunds(chain, stx, senderBalance = required - 1)
          assertPaidExactly(chain, txType, stx, senderBalance = required)
        }
    }
  }

object UpfrontCostOverflowSpec:

  val TwoPow256: BigInt = BigInt(2).pow(256)
  val MaxBalance: BigInt = UInt256.MaxValue.toBigInt

  /** Every transaction here reserves this much gas: a power of two, so a price of 2^256 / TxGas makes the product
    * exactly 2^256. It is under EIP-7825's per-transaction cap and every block gas limit used here.
    */
  val TxGas: BigInt = BigInt(2).pow(20)

  /** EIP-4844 GAS_PER_BLOB. Every header here has excessBlobGas 0, so the blob base fee is the minimum, 1. */
  val GasPerBlob: BigInt = 131_072
  val BlobBaseFee: BigInt = 1

  enum TxType(val number: Int):
    case Legacy extends TxType(0)
    case AccessList extends TxType(1)
    case DynamicFee extends TxType(2)
    case Blob extends TxType(3)
    case SetCode extends TxType(4)

    def label: String = s"type-$number"

    /** Whether the price is a fee cap (`maxFeePerGas`), the transaction then being charged the effective gas price. */
    def hasFeeCap: Boolean = number >= 2

    /** The blob cost the upfront check adds: one blob at the base fee. Zero for every other type. */
    def blobCost: BigInt = if this == Blob then GasPerBlob * BlobBaseFee else BigInt(0)
