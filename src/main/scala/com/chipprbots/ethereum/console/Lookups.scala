package com.chipprbots.ethereum.console

import java.time.Clock
import java.time.Instant
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicReference

import scala.util.control.NonFatal

import com.chipprbots.ethereum.utils.Logger

/** Something the operator asked to see (spec 029 FR-028): a block, a transaction, its receipt, an account at the head.
  * Hashes and addresses are `0x` and hexadecimal, lower case.
  */
enum LookupQuery:
  case LatestBlock
  case BlockByNumber(number: Long)
  case BlockByHash(hash: String)
  case Tx(hash: String)
  case Receipt(txHash: String)
  case Account(address: String)

  /** What the screen's title names. */
  def title: String = this match
    case LatestBlock      => "Block"
    case BlockByNumber(_) => "Block"
    case BlockByHash(_)   => "Block"
    case Tx(_)            => "Transaction"
    case Receipt(_)       => "Receipt"
    case Account(_)       => "Account"

/** A transaction as a block lists it.
  *
  * @param from
  *   the sender, recovered from the signature; None if it cannot be
  * @param to
  *   None for a contract creation
  * @param gasPrice
  *   what a unit of gas cost it in its block: the effective gas price
  * @param feeCaps
  *   a fee-market transaction's caps: the most it would pay a unit of gas, and the most of that for the tip
  */
final case class TxLine(
    index: Int,
    hash: String,
    from: Option[String],
    to: Option[String],
    valueWei: BigInt,
    gasPrice: BigInt,
    gasLimit: BigInt,
    kind: String,
    feeCaps: Option[(BigInt, BigInt)] = None
)

/** A log a receipt carries. */
final case class EventLog(address: String, topics: Int, dataBytes: Int)

/** What a lookup found, ready to be drawn: the worker does the reading, the frame loop none. */
enum LookupResult:
  case Block(summary: BlockSummary, txs: Vector[TxLine], uncles: Int, withdrawals: Option[Int])

  case Tx(line: TxLine, blockNumber: Long, blockHash: String, nonce: BigInt, inputBytes: Int)

  /** @param status
    *   `success`, `failed`, or `root 0x…`, the state root of a receipt older than the status code
    * @param gasUsed
    *   by this transaction alone
    * @param contract
    *   the address of the contract a creation made
    */
  case Receipt(
      txHash: String,
      blockNumber: Long,
      index: Int,
      status: String,
      gasUsed: BigInt,
      cumulativeGasUsed: BigInt,
      logs: Vector[EventLog],
      from: Option[String],
      to: Option[String],
      contract: Option[String]
  )

  /** @param codeBytes
    *   the size of its code; None for an account with none
    */
  case Account(address: String, atBlock: Long, balanceWei: BigInt, nonce: BigInt, codeBytes: Option[Int])

/** Where a lookup stands. */
enum LookupState:
  case Running(since: Instant)
  case Done(result: LookupResult)

  /** `reason` is a sentence for the operator: what was not found, or is not available (FR-029). */
  case Failed(reason: String)

/** Runs lookups off the frame loop and off the polling thread (spec 029 FR-029): one daemon thread, one lookup at a
  * time, the newest results kept so that going back to a screen costs nothing. The frame loop only reads [[results]].
  *
  * @param resolve
  *   does the reading. It may block, and it may fail: a Left is a sentence for the operator, and an exception becomes
  *   one
  * @param keep
  *   the results kept
  */
final class Lookups(
    resolve: LookupQuery => Either[String, LookupResult],
    clock: Clock = Clock.systemUTC(),
    keep: Int = Lookups.Keep
) extends Logger:

  /** One asking of a query. A query asked again after a cancel or a failure is a new ticket: what the worker finds for
    * the old one is never taken for the new.
    */
  final private case class Entry(query: LookupQuery, state: LookupState, ticket: Long)

  /** Every lookup that is running or done, the oldest first. Replaced whole, never changed in place. */
  private val state = new AtomicReference(Vector.empty[Entry])
  private val queue = new LinkedBlockingQueue[Entry]()
  private val tickets = new java.util.concurrent.atomic.AtomicLong(0L)

  @volatile private var stopped = false

  /** The ticket the worker is reading now. Guarded by `lock`, with the worker's interrupt: an interrupt is only ever
    * sent while the ticket it is meant for is the one being read, so it cannot land on the next lookup.
    */
  private val lock = new Object
  private var reading: Option[Long] = None

  private val worker = new Thread(() => work(), "TuiLookups")
  worker.setDaemon(true)
  worker.start()

  /** The lookups and where each stands: a read that never blocks. */
  def results: Vector[(LookupQuery, LookupState)] = state.get.map(e => e.query -> e.state)

  private def live(s: LookupState): Boolean = s match
    case LookupState.Running(_) | LookupState.Done(_) => true
    case LookupState.Failed(_)                        => false

  /** Ask for `query`. One that is running or done is not asked again; a failed one is. */
  def submit(query: LookupQuery): Unit =
    val entry = Entry(query, LookupState.Running(clock.instant()), tickets.incrementAndGet())
    val before = state.getAndUpdate { all =>
      if all.exists(e => e.query == query && live(e.state)) then all
      else bounded(all.filterNot(_.query == query) :+ entry)
    }
    if !before.exists(e => e.query == query && live(e.state)) then queue.put(entry)

  /** `all` with at most `keep` finished lookups, the newest: the older finished ones go. One that is still running is
    * never dropped here, and does not count: it is a screen the operator has open, and leaves by Esc.
    */
  private def bounded(all: Vector[Entry]): Vector[Entry] =
    val finished = all.filterNot(_.state.isInstanceOf[LookupState.Running])
    val gone = finished.dropRight(keep).map(_.ticket).toSet
    if gone.isEmpty then all else all.filterNot(e => gone.contains(e.ticket))

  /** Read `query` again, whatever is known of it: an account read a while ago is the head's of then. */
  def refresh(query: LookupQuery): Unit =
    cancel(query)
    state.getAndUpdate(_.filterNot(_.query == query))
    submit(query)

  /** Forget `query`: a result that arrives for it later is discarded, and the worker is interrupted if it is reading it
    * now (Esc on a lookup that is taking long).
    */
  def cancel(query: LookupQuery): Unit =
    val before = state.getAndUpdate(_.filterNot(e => e.query == query && e.state.isInstanceOf[LookupState.Running]))
    before.find(e => e.query == query && e.state.isInstanceOf[LookupState.Running]).foreach { cancelled =>
      lock.synchronized(if reading.contains(cancelled.ticket) then worker.interrupt())
    }

  /** Stops the worker. */
  def shutdown(): Unit =
    stopped = true
    worker.interrupt()

  private def wanted(entry: Entry): Boolean =
    state.get.exists(e => e.ticket == entry.ticket && e.state.isInstanceOf[LookupState.Running])

  private def work(): Unit =
    while !stopped do
      try
        val entry = queue.take()
        // Cancelled while it waited: nothing to read.
        if wanted(entry) then
          lock.synchronized {
            reading = Some(entry.ticket)
          }
          val outcome: Option[LookupState] =
            try
              Some(resolve(entry.query) match
                case Right(result) => LookupState.Done(result)
                case Left(reason)  => LookupState.Failed(reason)
              )
            catch
              // Cancelled, or the worker is being stopped: either way nobody wants an answer.
              case _: InterruptedException => None
              case NonFatal(e) =>
                log.debug("TUI lookup {} failed: {}", entry.query, e.toString)
                Some(failed(e))
              // A stack overflow in a deep trie, a linkage error: the lookup failed, and the worker must live for the
              // next one. Said at ERROR: it is a fault of the node's, not of what was asked.
              case e: Throwable =>
                log.error("TUI lookup {} failed with {}", entry.query, e.toString)
                Some(failed(e))
            finally
              lock.synchronized {
                reading = None
                // An interrupt sent for this lookup, taken or not, ends here: the next one starts clean.
                Thread.interrupted()
              }
          // Published only to the asking it was read for, and only if that asking is still wanted.
          outcome.foreach { result =>
            state.getAndUpdate(all =>
              bounded(all.map {
                case e if e.ticket == entry.ticket && e.state.isInstanceOf[LookupState.Running] =>
                  e.copy(state = result)
                case other => other
              })
            )
          }
      catch case _: InterruptedException => ()

  private def failed(e: Throwable): LookupState =
    LookupState.Failed(s"the node could not read it: ${Option(e.getMessage).getOrElse(e.getClass.getSimpleName)}")

object Lookups:
  /** The results kept: enough to walk back from a receipt to its block. */
  val Keep: Int = 8

  /** How long a lookup runs before the screen says it is working (FR-029). */
  val SlowAfterMillis: Long = 2000L
