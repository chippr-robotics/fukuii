package com.chipprbots.ethereum.ledger

import org.apache.pekko.util.ByteString

import org.scalatest.Tag
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.consensus.validators.std.StdSignedTransactionValidator
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.*
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.ForkTimestamps
import com.chipprbots.ethereum.utils.NetworkType

/** EIP-1559: once the fee market is active, EVERY transaction must be willing to pay the block's base fee. A legacy
  * (type 0) or access-list (type 1) transaction's `gasPrice` is its fee cap, so `gasPrice < baseFee` makes the block
  * invalid, exactly as `maxFeePerGas < baseFee` does for types 2, 3 and 4.
  *
  * References:
  *   - EIP-1559 `validate_block`: a legacy transaction is normalised to `max_fee_per_gas = max_priority_fee_per_gas =
  *     gas_price`, then `assert transaction.max_fee_per_gas >= block.base_fee_per_gas`.
  *   - execution-specs (London onwards) `check_transaction`: `if tx.gas_price < base_fee_per_gas: raise`.
  *   - go-ethereum `preCheck` (core/state_transition.go): from `IsLondon`, `msg.GasFeeCap.Cmp(BaseFee) < 0` returns
  *     `ErrFeeCapTooLow`, and `GasFeeCap` is the gas price for types 0 and 1. core-geth applies the same check once
  *     EIP-1559 is enabled, which on ETC is Olympia (ECIP-1111).
  *
  * The cases drive `BlockPreparator.executeTransactions` — the block-import loop — with the real validator, on ETH
  * Osaka and Amsterdam (by timestamp) and on ETC at Olympia (by block number). Each header carries a base fee of 7.
  */
// scalastyle:off magic.number
class LegacyGasPriceBelowBaseFeeSpec extends AnyFlatSpec with Matchers with AmsterdamFixtureVectors:

  private val Recipient: Address = Address(0x42)
  private val Coinbase: Address = Address(0xc0ffee)
  private val BaseFee: BigInt = 7
  private val TxGasLimit: BigInt = 100_000
  private val SenderBalance: BigInt = BigInt(10).pow(18)

  private val preparator =
    new BlockPreparator(new VMImpl, StdSignedTransactionValidator, setup.blockchain, setup.blockchainReader)

  final private case class Chain(label: String, config: BlockchainConfig, header: BlockHeader, tag: Tag)

  private val OlympiaBlock = BigInt(1000)

  /** Mordor-style ETC: chain id 63, every fork by block number, Olympia (EIP-1559 with the base fee to the treasury) at
    * [[OlympiaBlock]].
    */
  private val etcOlympiaConfig: BlockchainConfig =
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

  private val chains: Seq[Chain] = Seq(
    Chain(
      "ETH Osaka (forTimestamp)",
      preAmsterdamConfig,
      preAmsterdamHeader(46, 180).copy(beneficiary = Coinbase.bytes),
      ConsensusTest
    ),
    Chain(
      "ETH Amsterdam (forTimestamp)",
      amsterdamConfig,
      amsterdamHeader(47, 360).copy(beneficiary = Coinbase.bytes),
      ConsensusTest
    ),
    Chain(
      "ETC Mordor-style, Olympia active (forBlock)",
      etcOlympiaConfig,
      Fixtures.Blocks.ValidBlock.header.copy(
        number = BlockNumber(OlympiaBlock),
        beneficiary = Coinbase.bytes,
        gasLimit = GasAmount(8_000_000),
        gasUsed = GasAmount.Zero,
        extraFields = HefPostOlympia(BaseFee)
      ),
      OlympiaTest
    )
  )

  private def signed(txType: Int, gasPrice: BigInt, chain: Chain): SignedTransaction =
    val chainId = chain.config.chainId.value
    val unsigned: Transaction = txType match
      case 0 => LegacyTransaction(0, GasPrice(gasPrice), GasAmount(TxGasLimit), Some(Recipient), 0, ByteString.empty)
      case _ =>
        TransactionWithAccessList(
          chainId,
          0,
          GasPrice(gasPrice),
          GasAmount(TxGasLimit),
          Some(Recipient),
          0,
          ByteString.empty,
          Nil
        )
    SignedTransaction.sign(unsigned, senderKeyPair, Some(chainId))

  private def world: InMemoryWorldStateProxy =
    setup.emptyWorld
      .saveAccount(senderAddress, Account(nonce = UInt256(0), balance = UInt256(SenderBalance)))
      .saveAccount(Recipient, Account(nonce = UInt256(0), balance = UInt256(1)))

  private val Watched: Seq[Address] = Seq(senderAddress, Recipient, Coinbase)

  private def balances(w: InMemoryWorldStateProxy): Seq[BigInt] =
    Watched.map(a => w.getAccount(a).map(_.balance.toBigInt).getOrElse(BigInt(0)))

  chains.foreach { chain =>
    chain.label should "carry the base fee the cases assume (fixture sanity)" taggedAs (UnitTest, chain.tag) in {
      chain.header.baseFee shouldBe Some(BaseFee)
    }

    Seq(0, 1).foreach { txType =>
      it should s"reject a type-$txType transaction whose gasPrice is below the block's base fee" taggedAs (
        UnitTest,
        ConsensusTest,
        chain.tag
      ) in {
        val stx = signed(txType, BaseFee - 1, chain)
        val pre = world
        preparator.executeTransactions(Seq(stx), pre, chain.header)(using chain.config) match
          case Left(err) =>
            err.reason should include("INSUFFICIENT_MAX_FEE_PER_GAS")
            err.stateBeforeError.acumReceipts shouldBe empty
            balances(err.stateBeforeError.worldState) shouldBe balances(pre)
          case Right(br) =>
            fail(
              s"[${chain.label}] expected rejection (EIP-1559 gas_price >= base_fee; go-ethereum preCheck " +
                s"ErrFeeCapTooLow) for gasPrice ${BaseFee - 1} < baseFee $BaseFee, but the block executed " +
                s"(gasUsed ${br.gasUsed}); balances sender/recipient/coinbase ${balances(pre)} -> " +
                s"${balances(br.worldState)}"
            )
      }

      it should s"execute a type-$txType transaction whose gasPrice equals the block's base fee (control)" taggedAs (
        UnitTest,
        ConsensusTest,
        chain.tag
      ) in {
        val stx = signed(txType, BaseFee, chain)
        val pre = world
        val br = preparator
          .executeTransactions(Seq(stx), pre, chain.header)(using chain.config)
          .fold(err => fail(s"[${chain.label}] rejected: ${err.reason}"), identity)
        br.receipts.size shouldBe 1
        val gasUsed = br.receipts.head.cumulativeGasUsed
        // gasPrice == baseFee: the sender pays the base fee only and the coinbase receives no priority fee.
        balances(br.worldState) shouldBe Seq(SenderBalance - gasUsed * BaseFee, BigInt(1), BigInt(0))
      }
    }
  }
