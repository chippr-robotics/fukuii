package com.chipprbots.ethereum.console

import java.time.Duration
import java.time.Instant

/** A pending transaction as the Mempool view shows it (spec 029 FR-031 to FR-033).
  *
  * @param hash
  *   `0x` and 64 hexadecimal digits
  * @param tip
  *   what it offers above the head's base fee, per gas; on a chain with no base fee, its gas price
  * @param feeCap
  *   the most it pays per gas
  * @param local
  *   it was submitted to this node, not heard from a peer
  */
final case class PoolTx(
    hash: String,
    sender: String,
    nonce: BigInt,
    tip: BigInt,
    feeCap: BigInt,
    isBlob: Boolean,
    local: Boolean,
    addedAt: Instant
)

/** Where a pending transaction's fee stands against the fee the node itself would suggest (FR-032). */
enum FeeTier(val label: String):
  /** Below half the suggestion: it may wait long. */
  case Low extends FeeTier("low")

  /** From half to one and a half times the suggestion. */
  case Normal extends FeeTier("normal")

  /** From one and a half to three times. */
  case High extends FeeTier("high")

  /** More than three times the suggestion. */
  case Urgent extends FeeTier("urgent")

object FeeTier:

  /** The tier of `tip` against `suggested`; with a suggestion of nothing, every transaction pays at least it. */
  def of(tip: BigInt, suggested: BigInt): FeeTier =
    if suggested <= 0 then Normal
    else if tip * 2 < suggested then Low
    else if tip * 2 <= suggested * 3 then Normal
    else if tip <= suggested * 3 then High
    else Urgent

  /** The least fee in each tier, from `Normal` up: half of `suggested` rounded up, and the first wei above one and a
    * half and three times it. [[of]] puts each of these in its tier, and the wei below it in the tier below.
    */
  def boundaries(suggested: BigInt): Seq[(FeeTier, BigInt)] =
    Seq(Normal -> (suggested + 1) / 2, High -> (suggested * 3 / 2 + 1), Urgent -> (suggested * 3 + 1))

/** The pool at one poll, and what moved through it over the last minute (data-model.md).
  *
  * @param tiers
  *   the transactions in each tier; empty when the node has no suggestion to measure against
  * @param suggested
  *   the fee the node would suggest: a tip on a chain with a base fee, a gas price on one without
  * @param arrivedPerSecond
  *   over [[PoolObserver.Window]]; None until the pool has been watched for [[PoolObserver.MinSpan]]
  * @param at
  *   when the pool was read
  * @param watchedSince
  *   when this watch of the pool began: `replaced` and `leftUnchecked` count from then
  * @param awaiting
  *   transactions that left and are still to be told apart, when more left than a poll looks up
  * @param leftUnchecked
  *   transactions that left and were never told apart: the index could not be read, or too many waited
  * @param topSenders
  *   the senders with the most pending transactions, at most ten
  * @param txs
  *   the pending transactions by tip, highest first, at most [[PoolObserver.MaxListed]]
  */
final case class PoolSample(
    at: Instant,
    size: Int,
    blobs: Int,
    capacity: Int,
    local: Int,
    oldest: Option[Instant],
    suggested: Option[BigInt],
    tiers: Map[FeeTier, Int],
    arrivedPerSecond: Option[Double],
    includedPerSecond: Option[Double],
    droppedPerSecond: Option[Double],
    watchedSince: Instant,
    replaced: Long,
    awaiting: Int,
    leftUnchecked: Long,
    topSenders: Seq[(String, Int)],
    txs: Vector[PoolTx]
)

/** Watches the pool while the Mempool view is on screen (spec 029 FR-031 to FR-033, FR-037): from one poll's pending
  * transactions to the next's, what arrived and what left, and of what left, what was mined, replaced or dropped.
  *
  * Pure: the state is a value. A transaction that left is looked up in the node's transaction index to tell mined from
  * dropped, at most [[MaxChecks]] a poll.
  */
object PoolObserver:

  /** The window the rates are measured over, and the least of it that makes a rate. */
  val Window: Duration = Duration.ofSeconds(60)
  val MinSpan: Duration = Duration.ofSeconds(5)

  /** Two polls further apart than this are not compared: the view was closed between them, and what moved in the
    * meantime was not watched.
    */
  val MaxGap: Duration = Duration.ofSeconds(5)

  /** The most departed transactions looked up in the index in one poll; the rest wait for the next. */
  val MaxChecks: Int = 500

  /** The most transactions listed. */
  val MaxListed: Int = 500

  /** The most departures kept to look up in a later poll; past it they are counted as never looked up. */
  val MaxAwaiting: Int = 20_000

  /** The movements between two polls, `from` the earlier to `at` the later. A departure is in the step that looked it
    * up, which is the one it left in unless more left than a poll looks up.
    */
  final case class Step(from: Instant, at: Instant, arrived: Int, included: Int, dropped: Int)

  /** What the observer knows.
    *
    * @param previous
    *   the pool at the last poll, by hash
    * @param since
    *   when this watch of the pool began: the first poll, or the first after a gap longer than [[MaxGap]]
    * @param steps
    *   the movements of each poll within [[Window]]
    * @param replaced
    *   replacements seen since `since`
    * @param awaiting
    *   the hashes of transactions that left and are still to be looked up, oldest first
    * @param leftUnchecked
    *   departures since `since` that were never told apart: the lookup failed, or more waited than [[MaxAwaiting]]
    */
  final case class State(
      previous: Map[String, PoolTx] = Map.empty,
      seenAt: Option[Instant] = None,
      since: Option[Instant] = None,
      steps: Vector[Step] = Vector.empty,
      replaced: Long = 0L,
      awaiting: Vector[String] = Vector.empty,
      leftUnchecked: Long = 0L
  )

  /** The state after a poll that found `current`, and the sample of it.
    *
    * A transaction that left while another from its sender with its nonce is pending was replaced. Of the rest, one the
    * node's index holds was mined and the others were dropped. A transaction replaced by one that was itself mined
    * before the next poll is seen leaving with no successor in the pool: it counts as dropped, which it was.
    *
    * @param isMined
    *   whether the node's index holds this transaction (it is in a block the node imported); None when the index could
    *   not be read, and the transaction is then counted as not told apart, never as dropped
    * @param suggested
    *   the fee the node would suggest, if it has one
    */
  def observe(
      state: State,
      current: Seq[PoolTx],
      isMined: String => Option[Boolean],
      suggested: Option[BigInt],
      capacity: Int,
      now: Instant
  ): (State, PoolSample) =
    val byHash = current.iterator.map(tx => tx.hash -> tx).toMap
    val next = state.seenAt.filter(at => Duration.between(at, now).compareTo(MaxGap) <= 0) match
      // Not watched since the last poll (the view was just opened, or the pool stopped answering): nothing is
      // compared, and the watch starts again.
      case None => State(byHash, Some(now), Some(now))
      case Some(last) =>
        val arrived = byHash.keysIterator.count(hash => !state.previous.contains(hash))
        val left = state.previous.valuesIterator.filterNot(tx => byHash.contains(tx.hash)).toVector
        val slots = current.iterator.map(tx => (tx.sender, tx.nonce)).toSet
        val (replaced, gone) = left.partition(tx => slots.contains((tx.sender, tx.nonce)))
        // Those that left earlier and were not looked up go first: the index keeps a mined transaction, so a late
        // lookup tells it apart as well as a prompt one, and the rate is late, not wrong.
        val (checked, waiting) = (state.awaiting ++ gone.map(_.hash)).splitAt(MaxChecks)
        val answers = checked.map(isMined)
        val (kept, overflow) = waiting.splitAt(MaxAwaiting)
        val step = Step(last, now, arrived, answers.count(_.contains(true)), answers.count(_.contains(false)))
        State(
          byHash,
          Some(now),
          state.since.orElse(Some(last)),
          (state.steps :+ step).dropWhile(s => Duration.between(s.at, now).compareTo(Window) > 0),
          state.replaced + replaced.size,
          kept,
          state.leftUnchecked + answers.count(_.isEmpty) + overflow.size
        )
    (next, sample(next, current, suggested, capacity, now))

  private def sample(
      state: State,
      current: Seq[PoolTx],
      suggested: Option[BigInt],
      capacity: Int,
      now: Instant
  ): PoolSample =
    // The steps' own span, from the first one's start: what the sum was counted over.
    val span = state.steps.headOption.map(first => Duration.between(first.from, now))
    def rate(of: Step => Int): Option[Double] =
      span.filter(_.compareTo(MinSpan) >= 0).map(s => state.steps.map(of).sum.toDouble * 1000.0 / s.toMillis)
    PoolSample(
      at = now,
      size = current.size,
      blobs = current.count(_.isBlob),
      capacity = capacity,
      local = current.count(_.local),
      oldest = current.iterator.map(_.addedAt).minOption,
      suggested = suggested,
      tiers = suggested.fold(Map.empty[FeeTier, Int])(s =>
        current.groupMapReduce(tx => FeeTier.of(tx.tip, s))(_ => 1)(_ + _)
      ),
      arrivedPerSecond = rate(_.arrived),
      includedPerSecond = rate(_.included),
      droppedPerSecond = rate(_.dropped),
      watchedSince = state.since.getOrElse(now),
      replaced = state.replaced,
      awaiting = state.awaiting.size,
      leftUnchecked = state.leftUnchecked,
      topSenders = current.groupMapReduce(_.sender)(_ => 1)(_ + _).toSeq.sortBy((sender, n) => (-n, sender)).take(10),
      txs = current.sortBy(tx => (-tx.tip, tx.hash)).take(MaxListed).toVector
    )
