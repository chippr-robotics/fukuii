package com.chipprbots.ethereum.console

import java.time.Duration
import java.time.Instant

/** The priority fees of a block's transactions, in wei: the tenth, fiftieth and ninetieth percentile. On a chain with
  * no base fee they are the gas prices.
  */
final case class FeePercentiles(p10: BigInt, p50: BigInt, p90: BigInt)

object FeePercentiles:

  /** The percentiles of `fees`; none for a block with no transaction. */
  def of(fees: Seq[BigInt]): Option[FeePercentiles] =
    if fees.isEmpty then None
    else
      val sorted = fees.sorted.toIndexedSeq
      def at(percent: Int): BigInt = sorted(((sorted.size - 1) * percent / 100.0).round.toInt)
      Some(FeePercentiles(at(10), at(50), at(90)))

/** A block as the Chain view shows it (spec 029 FR-022, FR-023, data-model.md).
  *
  * @param hash
  *   `0x` and 64 hexadecimal digits
  * @param txCount
  *   None when the body was not read: it is not stored (a SNAP pivot's ancestors), or the poll's bound on body reads
  *   was reached
  * @param sizeBytes
  *   the block's encoded size; None with `txCount`
  * @param miner
  *   the header's beneficiary: the miner on a proof-of-work chain, the fee recipient on a proof-of-stake one
  * @param slot
  *   the slot, where the header carries it
  * @param fees
  *   the fees its transactions paid; None with `txCount`, and for a block with no transaction
  * @param seenAt
  *   when the TUI first saw the block
  */
final case class BlockSummary(
    number: Long,
    hash: String,
    parentHash: String,
    timestamp: Long,
    gasUsed: BigInt,
    gasLimit: BigInt,
    miner: String,
    difficulty: BigInt,
    seenAt: Instant,
    txCount: Option[Int] = None,
    sizeBytes: Option[Long] = None,
    baseFee: Option[BigInt] = None,
    slot: Option[Long] = None,
    fees: Option[FeePercentiles] = None
):
  /** The share of the gas limit the block used, from 0 to 1. */
  def gasShare: Double = if gasLimit <= 0 then 0.0 else (BigDecimal(gasUsed) / BigDecimal(gasLimit)).toDouble

/** A change of head to a block that does not descend from the head before it (spec 029 FR-026).
  *
  * @param depth
  *   how many blocks of the old branch were left
  * @param depthIsLowerBound
  *   the common ancestor was not found within the walk's bound: the reorg is at least `depth` deep
  */
final case class ReorgRecord(
    at: Instant,
    depth: Int,
    depthIsLowerBound: Boolean,
    oldTip: BlockSummary,
    newTip: BlockSummary,
    commonAncestor: Option[BlockSummary]
)

/** The chain as the TUI has seen it since it started (spec 029 data-model.md).
  *
  * @param recent
  *   the newest blocks seen, oldest first
  * @param sampled
  *   blocks arrived faster than they were read: `recent` is not every block, and says so
  * @param blocksPerSecond
  *   the head's advance over the last minute; None until two polls, five seconds apart, have seen it move
  * @param gasPerSecond
  *   the gas of the blocks seen in that minute, at that rate
  */
final case class ChainSample(
    recent: Vector[BlockSummary] = Vector.empty,
    reorgs: Vector[ReorgRecord] = Vector.empty,
    sampled: Boolean = false,
    blocksPerSecond: Option[Double] = None,
    gasPerSecond: Option[Double] = None
):
  def head: Option[BlockSummary] = recent.lastOption

/** Follows the head (spec 029 FR-023, FR-026, research §6.1). At each poll it walks from the new head back through its
  * parents to a block it already has: the blocks on the way are new, and a meeting point that is not the previous head
  * is a reorg. The walk is bounded, so a poll costs a bounded number of reads whatever the chain did.
  *
  * Pure: the state is a value, and the blocks come through `parentOf`.
  */
object HeadObserver:

  /** The most parents one poll walks through. */
  val MaxWalk: Int = 64

  /** The most blocks one poll reads the body of: the newest ones. */
  val MaxBodies: Int = 8

  /** The reorgs kept. */
  val MaxReorgs: Int = 64

  /** The window the rates are measured over, and the least of it that makes a rate. */
  val RateWindow: Duration = Duration.ofSeconds(60)
  val MinRateSpan: Duration = Duration.ofSeconds(5)

  /** What the observer knows.
    *
    * @param heads
    *   the head's number at each poll within [[RateWindow]]
    */
  final case class State(
      recent: Vector[BlockSummary] = Vector.empty,
      reorgs: Vector[ReorgRecord] = Vector.empty,
      heads: Vector[(Instant, Long)] = Vector.empty
  ):
    /** Whether `recent` skips blocks: a block in it is not the child of the one before. */
    def sampled: Boolean = recent.sliding(2).exists(pair => pair.size == 2 && pair(1).parentHash != pair(0).hash)

    def sample: ChainSample =
      val rate = heads.headOption.zip(heads.lastOption).flatMap { case ((from, first), (to, last)) =>
        val span = Duration.between(from, to)
        Option.when(span.compareTo(MinRateSpan) >= 0)((last - first).max(0L).toDouble * 1000.0 / span.toMillis)
      }
      val window = heads.headOption.map(_._1)
      val seen = recent.filter(b => window.exists(from => !b.seenAt.isBefore(from)))
      val meanGas = Option.when(seen.nonEmpty)(seen.map(b => BigDecimal(b.gasUsed)).sum.toDouble / seen.size)
      ChainSample(recent, reorgs, sampled, rate, rate.flatMap(r => meanGas.map(_ * r)))

  /** The state after a poll that found `head`.
    *
    * @param parentOf
    *   the block with this hash, with its body read when the flag is set; None when the node does not have it
    * @param capacity
    *   the blocks kept in `recent`
    */
  def observe(
      state: State,
      head: Option[BlockSummary],
      parentOf: (String, Boolean) => Option[BlockSummary],
      now: Instant,
      capacity: Int
  ): State =
    head.fold(state) { h =>
      val heads =
        (state.heads :+ (now -> h.number)).dropWhile((at, _) => Duration.between(at, now).compareTo(RateWindow) > 0)
      if state.recent.lastOption.exists(_.hash == h.hash) then state.copy(heads = heads)
      else
        val known = state.recent.map(_.hash).zipWithIndex.toMap
        // From the head back to a block already known, the newest first; the head's body is read by the caller.
        var walked = Vector(h)
        var meeting = known.get(h.parentHash)
        var missing = false
        while meeting.isEmpty && !missing && walked.size <= MaxWalk do
          parentOf(walked.last.parentHash, walked.size < MaxBodies) match
            case Some(parent) =>
              walked = walked :+ parent
              meeting = known.get(parent.parentHash)
            case None => missing = true
        val fresh = walked.reverse
        val (kept, reorg) = meeting match
          // The previous head is the meeting point: the chain went on.
          case Some(i) if i == state.recent.size - 1 => (state.recent, None)
          // An older block is: the blocks after it were left for another branch.
          case Some(i) =>
            val left = state.recent.drop(i + 1)
            (state.recent.take(i + 1), Some(ReorgRecord(now, left.size, false, left.last, h, Some(state.recent(i)))))
          case None =>
            // No meeting point within the walk. If what was walked lies wholly above the last block known, blocks
            // arrived faster than they are read: a gap, not a reorg. Else known blocks at those heights were left, and
            // more may have been below the walk's end: a reorg at least that deep.
            val lowest = fresh.head.number
            val left = state.recent.filter(_.number >= lowest)
            if left.isEmpty then (state.recent, None)
            else (state.recent.filter(_.number < lowest), Some(ReorgRecord(now, left.size, true, left.last, h, None)))
        State(
          (kept ++ fresh).takeRight(capacity.max(1)),
          (state.reorgs ++ reorg).takeRight(MaxReorgs),
          heads
        )
    }
