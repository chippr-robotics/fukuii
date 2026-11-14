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
  */
final case class TxLine(
    index: Int,
    hash: String,
    from: Option[String],
    to: Option[String],
    valueWei: BigInt,
    gasPrice: BigInt,
    gasLimit: BigInt,
    kind: String
)

/** A log a receipt carries. */
final case class EventLog(address: String, topics: Int, dataBytes: Int)

/** What a lookup found, ready to be drawn: the worker does the reading, the frame loop none. */
enum LookupResult:
  case Block(summary: BlockSummary, txs: Vector[TxLine], uncles: Int, withdrawals: Option[Int])

  case Tx(line: TxLine, blockNumber: Long, blockHash: String, nonce: BigInt, inputBytes: Int)

  /** @param status
    *   `success`, `failed`, or the state root of a receipt older than the status code
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

  /** Every lookup that is running or done, the oldest first. Replaced whole, never changed in place. */
  private val state = new AtomicReference(Vector.empty[(LookupQuery, LookupState)])
  private val queue = new LinkedBlockingQueue[LookupQuery]()

  /** What the worker is reading now. */
  private val running = new AtomicReference(Option.empty[LookupQuery])

  private val worker = new Thread(() => work(), "TuiLookups")
  worker.setDaemon(true)
  worker.start()

  /** The lookups and where each stands: a read that never blocks. */
  def results: Vector[(LookupQuery, LookupState)] = state.get

  /** Ask for `query`. One that is running or done is not asked again; a failed one is. */
  def submit(query: LookupQuery): Unit =
    val asked = state.getAndUpdate { all =>
      all.find(_._1 == query) match
        case Some((_, LookupState.Running(_) | LookupState.Done(_))) => all
        case _ => (all.filterNot(_._1 == query) :+ (query -> LookupState.Running(clock.instant()))).takeRight(keep)
    }
    val already = asked.exists {
      case (q, LookupState.Running(_) | LookupState.Done(_)) => q == query
      case _                                                 => false
    }
    if !already then queue.put(query)

  /** Forget `query`: a result that arrives for it later is discarded, and the worker is interrupted if it is reading it
    * now (Esc on a lookup that is taking long).
    */
  def cancel(query: LookupQuery): Unit =
    state.getAndUpdate(_.filterNot { case (q, s) => q == query && s.isInstanceOf[LookupState.Running] })
    if running.get.contains(query) then worker.interrupt()

  /** Stops the worker. */
  def shutdown(): Unit =
    stopped = true
    worker.interrupt()

  @volatile private var stopped = false

  private def work(): Unit =
    while !stopped do
      try
        val query = queue.take()
        // Cancelled while it waited: nothing to read.
        if state.get.exists { case (q, s) => q == query && s.isInstanceOf[LookupState.Running] } then
          running.set(Some(query))
          val outcome =
            try
              resolve(query) match
                case Right(result) => LookupState.Done(result)
                case Left(reason)  => LookupState.Failed(reason)
            catch
              case _: InterruptedException => LookupState.Failed("cancelled")
              case NonFatal(e) =>
                log.debug("TUI lookup {} failed: {}", query, e.toString)
                LookupState.Failed(
                  s"the node could not read it: ${Option(e.getMessage).getOrElse(e.getClass.getSimpleName)}"
                )
            finally running.set(None)
          // Published only if it is still wanted: a cancelled lookup's result goes nowhere.
          state.getAndUpdate(_.map {
            case (q, LookupState.Running(_)) if q == query => q -> outcome
            case other                                     => other
          })
          // An interrupt meant for this lookup must not end the next one.
          Thread.interrupted()
      catch case _: InterruptedException => ()

object Lookups:
  /** The results kept: enough to walk back from a receipt to its block. */
  val Keep: Int = 8

  /** How long a lookup runs before the screen says it is working (FR-029). */
  val SlowAfterMillis: Long = 2000L
