package com.chipprbots.ethereum.transactions

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.LongAdder

import scala.jdk.CollectionConverters.*

/** What the transaction pool refused, lost and fetched, counted since the node started (spec 029 FR-034).
  *
  * Counting changes nothing the pool decides: each method is called beside a decision the pool has already made, and
  * only adds one. The counters are read by the `--tui` dashboard's Mempool view; nothing in the node reads them.
  *
  * The refusals are a lower bound: a peer's transaction dropped because its sender has no account, or because the
  * sender already has transactions pending and this one's nonce is not the next, is dropped without a named rule and is
  * not counted.
  *
  * An instance, not an object, so that a test counts its own pool's.
  */
final class TxPoolMetrics:

  private val rejections = new ConcurrentHashMap[String, LongAdder]()
  private val evictions = new LongAdder
  private val expiries = new LongAdder
  private val replacements = new LongAdder
  private val announced = new LongAdder
  private val fetches = new LongAdder
  private val timeouts = new LongAdder

  /** A transaction was refused: `reason` is the rule's name (`NonceTooLow`, `ReplaceUnderpriced`). */
  def rejected(reason: String): Unit = rejections.computeIfAbsent(reason, _ => new LongAdder).increment()

  /** A transaction was refused by `rejection`. */
  def rejected(rejection: TxAdmission.Rejection): Unit = rejected(rejection.productPrefix)

  /** A transaction was pushed out to make room: the pool was full, or the blob sidecars were past their byte budget. */
  def evicted(): Unit = evictions.increment()

  /** A transaction stayed in the pool for its whole lifetime and was dropped unmined. */
  def expired(): Unit = expiries.increment()

  /** A transaction took the place of `count` others from the same sender with the same nonce. */
  def replaced(count: Int): Unit = if count > 0 then replacements.add(count.toLong)

  /** Peers announced `count` transaction hashes the pool did not hold: counted a hash an announcement, so a hash two
    * peers announce is two, and before the per-peer cap on announcements kept.
    */
  def announcementsReceived(count: Int): Unit = if count > 0 then announced.add(count.toLong)

  /** `count` announced transactions were asked for: a hash a request, so one asked of a second peer after the first did
    * not answer, or left, is two.
    */
  def fetchRequested(count: Int): Unit = if count > 0 then fetches.add(count.toLong)

  /** `count` requests for announced transactions went unanswered and were moved to another peer, or given up. */
  def fetchTimedOut(count: Int): Unit = if count > 0 then timeouts.add(count.toLong)

  /** Every counter at this instant. */
  def snapshot: TxPoolMetrics.Snapshot =
    TxPoolMetrics.Snapshot(
      rejections.asScala.iterator.map((reason, n) => reason -> n.sum()).toMap,
      evictions.sum(),
      expiries.sum(),
      replacements.sum(),
      announced.sum(),
      fetches.sum(),
      timeouts.sum()
    )

object TxPoolMetrics:

  /** The node's own pool's counters. */
  val Node: TxPoolMetrics = new TxPoolMetrics

  /** The refusals that are not a [[TxAdmission.Rejection]]: a peer's transaction more than 1024 nonces ahead of its
    * account, and a blob transaction whose sidecar alone is larger than the pool's whole sidecar budget.
    */
  val NonceTooFarAhead: String = "NonceTooFarAhead"
  val SidecarOverBudget: String = "SidecarOverBudget"

  /** The counters, each since the node started.
    *
    * @param rejected
    *   by the rule that refused
    */
  final case class Snapshot(
      rejected: Map[String, Long],
      evicted: Long,
      expired: Long,
      replaced: Long,
      announced: Long,
      fetchRequested: Long,
      fetchTimedOut: Long
  )
