package com.chipprbots.ethereum.consensus.engine

import scala.collection.mutable

import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.BlobTransaction
import com.chipprbots.ethereum.domain.SignedTransaction
import com.chipprbots.ethereum.domain.Transaction

/** Which pool transactions an ETH proposer puts in a payload, and in what order: go-ethereum's miner (miner/worker.go
  * `fillTransactions` + `commitTransactions`, core/txpool/txorder `TransactionsByPriceAndNonce`, and the blob pool's
  * `Pending` filter), minus the parts that need execution. Pure: no chain, pool or clock access.
  *
  * WHY THIS EXISTS. The builder used to take the pool in (sender-address, nonce) order and check nothing but the blob
  * gas cap. Three hive `engine-cancun` failures traced to that:
  *   - `Blob Transactions On Block 1, *`: once six full-blob blocks raise the blob base fee to 2 wei, the last blob
  *     transaction (maxFeePerBlobGas 1) can no longer pay. go-ethereum's pool filter leaves it out and the block is
  *     empty; we packed it, the lenient build failed on it, and the payload came back carrying its 6 blobs ("expected 0
  *     blob, got 6").
  *   - `Blob Transaction Ordering, *`: which sender went first depended on the byte order of the sender addresses, not
  *     on price or arrival, so the per-block blob count was an accident of the test accounts' keys.
  *   - blob transactions were eligible before Cancun (the fork's blob cap was reported as 6 blobs pre-Cancun).
  *
  * The ETC miner does not come through here; this is the Engine API proposer only.
  */
object ProposerTxSelection:

  /** A pool transaction with the two facts the pool knows about it: its (already recovered) sender and when it arrived.
    */
  final case class Candidate(stx: SignedTransaction, sender: Address, arrivalMillis: Long)

  /** The effective miner tip per gas for a block with `baseFee`: `min(maxPriorityFeePerGas, maxFeePerGas - baseFee)`
    * for EIP-1559-style transactions, `gasPrice - baseFee` for legacy and access-list ones. Negative means the
    * transaction cannot pay the base fee (go-ethereum `ErrGasFeeCapTooLow`).
    */
  def effectiveTip(tx: Transaction, baseFee: BigInt): BigInt =
    Transaction.effectiveGasPrice(tx, Some(baseFee)) - baseFee

  /** Blobs carried, 0 for anything but a blob transaction. */
  def blobCount(tx: Transaction): Int = tx match
    case b: BlobTransaction => b.blobVersionedHashes.size
    case _                  => 0

  /** Can the child block pay for this transaction at all? Base fee for everyone; the blob base fee for blob
    * transactions, which are not payable at all before Cancun (`blobBaseFee = None`).
    */
  def payable(tx: Transaction, baseFee: BigInt, blobBaseFee: Option[BigInt]): Boolean =
    effectiveTip(tx, baseFee) >= 0 && (tx match
      case b: BlobTransaction => blobBaseFee.exists(fee => b.maxFeePerBlobGas >= fee)
      case _                  => true
    )

  /** Select and order `candidates` for one payload.
    *
    * @param candidates
    *   per sender, a nonce-contiguous run that is executable on the parent state (the caller has already dropped
    *   already-included nonces and gaps). Order across senders is irrelevant.
    * @param baseFee
    *   the child block's base fee.
    * @param blobBaseFee
    *   the child block's blob base fee, None before Cancun.
    * @param maxBlobGas
    *   the child block's MAX_BLOB_GAS_PER_BLOCK, 0 before Cancun.
    * @return
    *   the transactions to execute, in order:
    *   1. each sender's run is cut at its first transaction the child cannot pay for (later nonces cannot follow the
    *      gap);
    *   1. the runs are merged by effective tip, highest first, each sender's nonce order preserved; on a tie a non-blob
    *      transaction goes first (go-ethereum compares the plain and blob heaps with `ptip.Lt(btip)`), then the earlier
    *      arrival, then the lower hash so the order is total;
    *   1. a blob transaction that no longer fits `maxBlobGas` is dropped together with the rest of its sender's run
    *      (go-ethereum `txs.Pop()`); everyone else carries on.
    */
  def select(
      candidates: Seq[Candidate],
      baseFee: BigInt,
      blobBaseFee: Option[BigInt],
      maxBlobGas: BigInt
  ): Seq[SignedTransaction] =
    val runs: Map[Address, Vector[Candidate]] =
      candidates
        .groupBy(_.sender)
        .view
        .mapValues(_.sortBy(_.stx.tx.nonce).takeWhile(c => payable(c.stx.tx, baseFee, blobBaseFee)).toVector)
        .filter(_._2.nonEmpty)
        .toMap

    // A sender's next transaction, as a heap entry. Only one entry per sender is ever in the heap.
    case class Head(candidate: Candidate, index: Int):
      val tip: BigInt = effectiveTip(candidate.stx.tx, baseFee)
      val isBlob: Boolean = blobCount(candidate.stx.tx) > 0

    // PriorityQueue dequeues the GREATEST element, so "greater" means "goes first".
    val priority: Ordering[Head] = (a: Head, b: Head) =>
      val byTip = a.tip.compare(b.tip)
      if byTip != 0 then byTip
      else if a.isBlob != b.isBlob then (if a.isBlob then -1 else 1)
      else
        val byArrival = b.candidate.arrivalMillis.compare(a.candidate.arrivalMillis)
        if byArrival != 0 then byArrival
        else
          java.util.Arrays.compareUnsigned(
            b.candidate.stx.hash.value.toArray,
            a.candidate.stx.hash.value.toArray
          )

    val heap = mutable.PriorityQueue.empty[Head](using priority)
    runs.values.foreach(run => heap.enqueue(Head(run.head, 0)))

    val selected = Vector.newBuilder[SignedTransaction]
    var blobGasUsed = BigInt(0)
    while heap.nonEmpty do
      val head = heap.dequeue()
      val run = runs(head.candidate.sender)
      val blobGas = BlobGasUtils.GAS_PER_BLOB * blobCount(head.candidate.stx.tx)
      if blobGasUsed + blobGas <= maxBlobGas || blobGas == 0 then
        selected += head.candidate.stx
        blobGasUsed += blobGas
        val next = head.index + 1
        if next < run.size then heap.enqueue(Head(run(next), next))
      // else: the blob transaction does not fit what is left; its sender is done for this payload.
    selected.result()
