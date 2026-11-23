package com.chipprbots.ethereum.consensus.engine

import java.security.SecureRandom

import org.apache.pekko.util.ByteString

import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.chipprbots.ethereum.consensus.engine.ProposerTxSelection.Candidate
import com.chipprbots.ethereum.crypto
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.Config

// scalastyle:off magic.number
/** go-ethereum miner semantics for the Engine API proposer's transaction selection (see [[ProposerTxSelection]]). The
  * blob scenarios are the hive `engine-cancun` ones, reduced to the selection they exercise.
  */
class ProposerTxSelectionSpec extends AnyWordSpec with Matchers:

  implicit private val blockchainConfig: BlockchainConfig = Config.blockchains.blockchainConfig
  private val chainId: BigInt = blockchainConfig.chainId.value
  private val random = new SecureRandom()
  private val to = Address(ByteString(Array.fill(20)(0x16.toByte)))

  private val BaseFee = BigInt(7)
  private val SixBlobs: BigInt = BlobGasUtils.CANCUN_MAX_BLOB_GAS

  private class Account:
    val key: AsymmetricCipherKeyPair = crypto.generateKeyPair(random)
    private def sign(tx: Transaction): SignedTransaction = SignedTransaction.sign(tx, key, Some(chainId))

    def dynamic(nonce: Int, tip: BigInt, feeCap: BigInt = 1000, data: Int = 0): SignedTransaction =
      sign(
        TransactionWithDynamicFee(
          chainId,
          nonce,
          tip,
          feeCap,
          GasAmount(21000),
          Some(to),
          1,
          ByteString(new Array[Byte](data)),
          Nil
        )
      )

    def legacy(nonce: Int, gasPrice: BigInt): SignedTransaction =
      sign(LegacyTransaction(nonce, GasPrice(gasPrice), GasAmount(21000), Some(to), 1, ByteString.empty))

    def blob(nonce: Int, blobs: Int, tip: BigInt = 1, blobFeeCap: BigInt = 100): SignedTransaction =
      sign(
        BlobTransaction(
          chainId,
          nonce,
          tip,
          BigInt(1000),
          GasAmount(100000),
          Some(to),
          0,
          ByteString.empty,
          Nil,
          blobFeeCap,
          List.tabulate(blobs)(i =>
            BlobVersionedHash(ByteString(Array(0x01.toByte) ++ Array.fill(31)((nonce * 16 + i).toByte)))
          )
        )
      )

  private def candidate(stx: SignedTransaction, arrivalMillis: Long): Candidate =
    Candidate(stx, SignedTransaction.getSender(stx).get, arrivalMillis)

  private def select(
      candidates: Seq[Candidate],
      blobBaseFee: Option[BigInt] = Some(BigInt(1)),
      maxBlobGas: BigInt = SixBlobs
  ): Seq[SignedTransaction] =
    ProposerTxSelection.select(candidates, BaseFee, blobBaseFee, maxBlobGas)

  "ProposerTxSelection.select" should {

    "order senders by effective tip, highest first, never reordering one sender's nonces" taggedAs UnitTest in {
      val a = new Account
      val b = new Account
      val a0 = a.dynamic(0, tip = 1)
      val a1 = a.dynamic(1, tip = 5)
      val b0 = b.dynamic(0, tip = 3)
      // a1 pays the most, but it cannot run before a0, which pays the least.
      select(Seq(candidate(a1, 1), candidate(a0, 1), candidate(b0, 1))) shouldBe Seq(b0, a0, a1)
    }

    "measure a legacy transaction's tip as gasPrice - baseFee" taggedAs UnitTest in {
      val a = new Account
      val b = new Account
      val legacy = a.legacy(0, gasPrice = BaseFee + 4) // tip 4
      val dynamic = b.dynamic(0, tip = 3, feeCap = BaseFee + 10) // tip 3
      select(Seq(candidate(dynamic, 1), candidate(legacy, 2))) shouldBe Seq(legacy, dynamic)
    }

    "break a tip tie by arrival, and prefer a non-blob transaction to a blob one" taggedAs UnitTest in {
      val a = new Account
      val b = new Account
      val c = new Account
      val late = a.dynamic(0, tip = 2)
      val early = b.dynamic(0, tip = 2)
      val earliestButBlob = c.blob(0, blobs = 1, tip = 2)
      select(Seq(candidate(late, 200), candidate(early, 100), candidate(earliestButBlob, 50))) shouldBe
        Seq(early, late, earliestButBlob)
    }

    "cut a sender's run at the first transaction that cannot pay the base fee" taggedAs UnitTest in {
      val a = new Account
      val b = new Account
      val a0 = a.dynamic(0, tip = 1)
      val a1 = a.dynamic(1, tip = 1, feeCap = BaseFee - 1) // cannot pay: a2 cannot follow the gap either
      val a2 = a.dynamic(2, tip = 1)
      val b0 = b.legacy(0, gasPrice = BaseFee - 1) // a legacy price below the base fee
      select(Seq(candidate(a0, 1), candidate(a1, 2), candidate(a2, 3), candidate(b0, 4))) shouldBe Seq(a0)
    }

    "leave out a blob transaction whose blob fee cap is below the blob base fee" taggedAs UnitTest in {
      // hive `Blob Transactions On Block 1, *`: six full-blob blocks lift the blob base fee to 2 wei, and the
      // pending blob transaction was signed with maxFeePerBlobGas = 1. go-ethereum's payload is empty.
      val a = new Account
      val pending = a.blob(0, blobs = 6, blobFeeCap = 1)
      select(Seq(candidate(pending, 1)), blobBaseFee = Some(BigInt(2))) shouldBe empty
      // One empty block later the blob base fee is back to 1 and it goes in.
      select(Seq(candidate(pending, 1)), blobBaseFee = Some(BigInt(1))) shouldBe Seq(pending)
    }

    "take no blob transaction at all before Cancun" taggedAs UnitTest in {
      val a = new Account
      val b = new Account
      val blobTx = a.blob(0, blobs = 1)
      val plain = b.dynamic(0, tip = 1)
      select(Seq(candidate(blobTx, 1), candidate(plain, 2)), blobBaseFee = None, maxBlobGas = 0) shouldBe Seq(plain)
    }

    "drop a sender whose next blob transaction no longer fits, and carry on with the others" taggedAs UnitTest in {
      // hive `Blob Transaction Ordering, Multiple Accounts`: A sends five 5-blob transactions, then B sends five
      // 1-blob transactions. Every payload must be full: one of A's and one of B's.
      val a = new Account
      val b = new Account
      val as = (0 until 5).map(n => a.blob(n, blobs = 5))
      val bs = (0 until 5).map(n => b.blob(n, blobs = 1))
      val pool = as.zipWithIndex.map((tx, i) => candidate(tx, 10L + i)) ++
        bs.zipWithIndex.map((tx, i) => candidate(tx, 100L + i))
      select(pool) shouldBe Seq(as.head, bs.head)
    }

    "keep one sender's blob transactions in nonce order even when a later one would fit" taggedAs UnitTest in {
      // hive `Blob Transaction Ordering, Single Account, Dual Blob`: five 5-blob, one 2-blob, four 1-blob
      // transactions from one account. The fifth payload is the last 5-blob transaction ALONE: the 2-blob one does
      // not fit beside it, and the 1-blob ones cannot jump the nonce queue.
      val a = new Account
      val txs = (0 until 5).map(n => a.blob(n, blobs = 5)) ++ Seq(a.blob(5, blobs = 2)) ++
        (6 until 10).map(n => a.blob(n, blobs = 1))
      val fromNonce4 = txs.drop(4).zipWithIndex.map((tx, i) => candidate(tx, i.toLong))
      select(fromNonce4) shouldBe Seq(txs(4))
      val fromNonce5 = txs.drop(5).zipWithIndex.map((tx, i) => candidate(tx, i.toLong))
      select(fromNonce5) shouldBe txs.drop(5) // 2 + 1 + 1 + 1 + 1 = 6 blobs
    }

    "never exceed the fork's blob cap" taggedAs UnitTest in {
      val accounts = Seq.fill(8)(new Account)
      val pool = accounts.zipWithIndex.map((acc, i) => candidate(acc.blob(0, blobs = 1), i.toLong))
      val chosen = select(pool)
      chosen.map(stx => ProposerTxSelection.blobCount(stx.tx)).sum shouldBe 6
      chosen shouldBe pool.take(6).map(_.stx) // first come, first served at equal tips
    }
  }

  "ProposerTxSelection.select with an EIP-7934 size budget" should {

    val Initial = 600L
    def budgetFor(sizes: Long*): ProposerTxSelection.SizeBudget =
      ProposerTxSelection.SizeBudget(Initial, Initial + sizes.sum)
    def size(stx: SignedTransaction): Long = ProposerTxSelection.blockItemSize(stx)

    "take a transaction only while the running size stays strictly below the limit (go-ethereum txFitsSize)" taggedAs (
      UnitTest,
      ConsensusTest
    ) in {
      val a = new Account
      val tx = a.dynamic(0, tip = 1, data = 1000)
      ProposerTxSelection.select(Seq(candidate(tx, 1)), BaseFee, None, 0, Some(budgetFor(size(tx), 1))) shouldBe
        Seq(tx)
      ProposerTxSelection.select(Seq(candidate(tx, 1)), BaseFee, None, 0, Some(budgetFor(size(tx)))) shouldBe empty
    }

    "end the selection at the first transaction that does not fit: a smaller one after it stays out (break)" taggedAs (
      UnitTest,
      ConsensusTest
    ) in {
      val a = new Account
      val b = new Account
      val c = new Account
      val first = a.dynamic(0, tip = 9)
      val big = b.dynamic(0, tip = 5, data = 5000)
      val small = c.dynamic(0, tip = 1)
      // Room for `first` and `small`, not for `big`: go-ethereum's commitTransactions breaks at `big`.
      val budget = budgetFor(size(first), size(small), 1)
      ProposerTxSelection.select(
        Seq(candidate(small, 1), candidate(big, 2), candidate(first, 3)),
        BaseFee,
        None,
        0,
        Some(budget)
      ) shouldBe Seq(first)
      // With no budget (before Osaka) all three go in, by tip.
      ProposerTxSelection.select(
        Seq(candidate(small, 1), candidate(big, 2), candidate(first, 3)),
        BaseFee,
        None,
        0,
        None
      ) shouldBe Seq(first, big, small)
    }

    "count a transaction as exactly the bytes it adds to the block's RLP, legacy and typed alike" taggedAs (
      UnitTest,
      ConsensusTest
    ) in {
      val a = new Account
      val b = new Account
      val header = com.chipprbots.ethereum.Fixtures.Blocks.ValidBlock.header
      // Both bodies' transaction lists and outer lists stay in one RLP length-prefix class, so the difference is the
      // item itself.
      val base = a.legacy(0, gasPrice = 10)
      def blockWith(txs: SignedTransaction*) = Block.size(Block(header, BlockBody(txs, Nil, Some(Nil))))
      val legacy = b.legacy(0, gasPrice = 10)
      val typed = b.dynamic(0, tip = 1)
      blockWith(base, legacy) - blockWith(base) shouldBe size(legacy)
      blockWith(base, typed) - blockWith(base) shouldBe size(typed)
    }
  }
