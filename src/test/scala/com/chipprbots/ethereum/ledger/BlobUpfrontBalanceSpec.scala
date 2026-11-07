package com.chipprbots.ethereum.ledger

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.consensus.engine.BlobGasUtils
import com.chipprbots.ethereum.consensus.validators.std.StdSignedTransactionValidator
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.BlockchainConfig

/** EIP-4844: the balance a blob transaction's sender must hold before execution is `gasLimit * maxFeePerGas + value +
  * blobGas * maxFeePerBlobGas`. The blob term is taken at the transaction's blob fee CAP, exactly as the execution term
  * is taken at its fee cap; only the debit uses the block's actual blob base fee.
  *
  * References:
  *   - EIP-4844 `validate_block`: `max_total_fee = tx.gas * tx.max_fee_per_gas + get_total_blob_gas(tx) *
  *     tx.max_fee_per_blob_gas`, then `assert signer(tx).balance >= max_total_fee`.
  *   - go-ethereum `buyGas` (core/state_transition.go): `blobBalanceCheck = blobGas * msg.BlobGasFeeCap` is added to
  *     `balanceCheck`; `mgval` (the debit) adds `blobGas * BlobBaseFee`.
  *   - execution-specs (Cancun onwards) `check_transaction`: `max_gas_fee += total_blob_gas * max_fee_per_blob_gas`.
  *
  * The cases drive `BlockPreparator.executeTransactions` — the block-import loop — with the real validator, on ETH
  * Osaka and ETH Amsterdam (both by timestamp). Every header has `excessBlobGas = 0`, so the blob base fee is the
  * minimum, 1, and the transaction's `maxFeePerBlobGas` is set well above it.
  */
// scalastyle:off magic.number
class BlobUpfrontBalanceSpec extends AnyFlatSpec with Matchers with AmsterdamFixtureVectors:

  private val Recipient: Address = Address(0x42)
  private val Coinbase: Address = Address(0xc0ffee)

  private val preparator =
    new BlockPreparator(new VMImpl, StdSignedTransactionValidator, setup.blockchain, setup.blockchainReader)

  private val GasPerBlob: BigInt = 131_072
  private val TxGasLimit: BigInt = 100_000
  private val MaxFeePerGas: BigInt = 10 // above every header's base fee (7)
  private val MaxFeePerBlobGas: BigInt = 1_000
  private val Value: BigInt = 1_000

  private val BlobHash: BlobVersionedHash =
    BlobVersionedHash(ByteString(0x01.toByte +: Array.fill[Byte](31)(0x07.toByte)))

  final private case class Chain(label: String, config: BlockchainConfig, header: BlockHeader):
    val baseFee: BigInt = header.baseFee.getOrElse(BigInt(0))
    val blobBaseFee: BigInt =
      BlobGasUtils.getBlobGasPrice(header.excessBlobGas.getOrElse(BigInt(0)), header.unixTimestamp, config)

  private val chains: Seq[Chain] = Seq(
    Chain(
      "ETH Osaka (forTimestamp)",
      preAmsterdamConfig,
      preAmsterdamHeader(46, 180).copy(beneficiary = Coinbase.bytes)
    ),
    Chain(
      "ETH Amsterdam (forTimestamp)",
      amsterdamConfig,
      amsterdamHeader(47, 360).copy(beneficiary = Coinbase.bytes)
    )
  )

  private def blobTx(chain: Chain): SignedTransaction =
    val chainId = chain.config.chainId.value
    val tx = BlobTransaction(
      chainId = chainId,
      nonce = 0,
      maxPriorityFeePerGas = 0,
      maxFeePerGas = MaxFeePerGas,
      gasLimit = GasAmount(TxGasLimit),
      receivingAddress = Some(Recipient),
      value = Value,
      payload = ByteString.empty,
      accessList = Nil,
      maxFeePerBlobGas = MaxFeePerBlobGas,
      blobVersionedHashes = List(BlobHash)
    )
    SignedTransaction.sign(tx, senderKeyPair, Some(chainId))

  /** EIP-4844 `max_total_fee + value`: what the sender must hold. */
  private def requiredAtCaps(stx: SignedTransaction): BigInt =
    TxGasLimit * MaxFeePerGas + stx.tx.value + GasPerBlob * MaxFeePerBlobGas

  /** The same figure with the blob term at the block's actual blob base fee instead of the transaction's cap. */
  private def requiredAtActualBlobFee(stx: SignedTransaction, chain: Chain): BigInt =
    TxGasLimit * MaxFeePerGas + stx.tx.value + GasPerBlob * chain.blobBaseFee

  private def world(senderBalance: BigInt): InMemoryWorldStateProxy =
    setup.emptyWorld
      .saveAccount(senderAddress, Account(nonce = UInt256(0), balance = UInt256(senderBalance)))
      .saveAccount(Recipient, Account(nonce = UInt256(0), balance = UInt256(1)))

  private val Watched: Seq[Address] = Seq(senderAddress, Recipient, Coinbase)

  private def balances(w: InMemoryWorldStateProxy): Seq[BigInt] =
    Watched.map(a => w.getAccount(a).map(_.balance.toBigInt).getOrElse(BigInt(0)))

  chains.foreach { chain =>
    chain.label should "price the blob term of the balance check at maxFeePerBlobGas (fixture sanity)" taggedAs (
      UnitTest,
      ConsensusTest
    ) in {
      chain.blobBaseFee shouldBe BigInt(1)
      MaxFeePerBlobGas should be > chain.blobBaseFee
      MaxFeePerGas should be >= chain.baseFee
    }

    it should "reject a blob transaction whose sender can pay the blob fee at the block's blob base fee but not at " +
      "maxFeePerBlobGas" taggedAs (UnitTest, ConsensusTest) in {
        val stx = blobTx(chain)
        val senderBalance = requiredAtCaps(stx) - 1
        // The balance covers everything at the actual blob base fee, so only the cap-priced blob term can fail it.
        senderBalance should be >= requiredAtActualBlobFee(stx, chain)
        val pre = world(senderBalance)
        preparator.executeTransactions(Seq(stx), pre, chain.header)(using chain.config) match
          case Left(err) =>
            err.reason should startWith("INSUFFICIENT_ACCOUNT_FUNDS")
            err.stateBeforeError.acumReceipts shouldBe empty
            balances(err.stateBeforeError.worldState) shouldBe balances(pre)
          case Right(br) =>
            val before = balances(pre)
            val after = balances(br.worldState)
            fail(
              s"[${chain.label}] expected INSUFFICIENT_ACCOUNT_FUNDS (EIP-4844 max_total_fee; go-ethereum buyGas " +
                s"blobBalanceCheck): sender held ${senderBalance}, required at caps ${requiredAtCaps(stx)}, but the " +
                s"block executed (gasUsed ${br.gasUsed}); balances sender/recipient/coinbase $before -> $after"
            )
      }

    it should "execute it from a sender holding exactly gasLimit * maxFeePerGas + value + blobGas * " +
      "maxFeePerBlobGas, debiting the blob fee at the block's blob base fee (control)" taggedAs (
        UnitTest,
        ConsensusTest
      ) in {
        val stx = blobTx(chain)
        val pre = world(requiredAtCaps(stx))
        val br = preparator
          .executeTransactions(Seq(stx), pre, chain.header)(using chain.config)
          .fold(err => fail(s"[${chain.label}] rejected: ${err.reason}"), identity)
        br.receipts.size shouldBe 1
        val gasUsed = br.receipts.head.cumulativeGasUsed
        val effectiveGasPrice = Transaction.effectiveGasPrice(stx.tx, chain.header.baseFee)
        val before = balances(pre)
        balances(br.worldState) shouldBe Seq(
          before(0) - gasUsed * effectiveGasPrice - Value - GasPerBlob * chain.blobBaseFee,
          before(1) + Value,
          before(2) + gasUsed * (effectiveGasPrice - chain.baseFee)
        )
      }
  }
