package com.chipprbots.ethereum.ledger

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

import org.apache.pekko.util.ByteString

import scala.annotation.tailrec
import scala.concurrent.duration.*
import scala.util.control.NonFatal

import com.chipprbots.ethereum.consensus.validators.BlockHeaderError.HeaderParentNotFoundError
import com.chipprbots.ethereum.db.storage.pathstate.BlockRef
import com.chipprbots.ethereum.db.storage.pathstate.PathStateStorage
import com.chipprbots.ethereum.db.storage.pathstate.StateFault
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.BlockAccessList
import com.chipprbots.ethereum.domain.BlockHash
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.domain.Receipt
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.Hex
import com.chipprbots.ethereum.utils.Logger

/** The recovery of a state that is no longer held, on the unified state layout (spec 018 data-model Part 2 D, T169;
  * plan.md "C4d part 3").
  *
  * A node that follows a consensus client keeps the state of each executed payload as a forward layer, in memory. A
  * restart loses them, and so does a layer that did not fit. The blocks are still stored, by their hash, and a node
  * that has no sync running on this layout (C5 brings it) has the state back only by executing them again: this does
  * that. Asked for a head, it walks the head's chain down by parent hash to a block whose state is held
  * ([[PathStateStorage.stateAvailable]]: the anchor, a block behind it that the changeset records lead to, or a block
  * that layers lead to), then executes the blocks above it, oldest first, each as a forward layer
  * ([[BlockExecution.executeAndValidateBlockFull]], as `engine_newPayload` executes one). It never moves the head: the
  * consensus client's next `forkchoiceUpdated` does, as for any executed payload. Meanwhile the Engine API answers
  * ACCEPTED or SYNCING, as go-ethereum does while it executes a stored chain again on its own.
  *
  * '''Bounded, and never inline.''' The executions run on one thread of their own, behind a queue of four heads; a head
  * asked for again while it is being recovered gets the recovery under way, and one more than the queue takes is
  * answered [[StateRecovery.Outcome.Busy]], with nothing done. A chain longer than `maxBlocks` is not executed at all:
  * the node's sync gets that state, or the escalation of C4d part 5 does. The node bounds it by
  * `state.recovery.max-blocks` and by half of `state.overlay.max-forward-layers`: a layer that is executed must not
  * push out the one below it before it is used, nor every other layer kept (the pins of a long branch come with C4d
  * part 4). A head whose recovery failed is not tried again for [[StateRecovery.CoolDown]].
  *
  * '''What a block's execution proves.''' Every block is validated in full. A block that was executed before (it has
  * receipts) and fails now failed by this node's own fault, never the block's: it is reported and not marked invalid. A
  * block that was never executed (an ACCEPTED payload, whose parent was not known) and fails a rule of consensus is
  * handed to the [[Sink]] as rejected, and the blocks above it are not executed: the Engine API marks it and its
  * descendants invalid, as `engine_newPayload` would have.
  *
  * Under the guard only: on the hash store nothing builds one.
  */
class StateRecovery(
    reader: BlockchainReader,
    execution: BlockExecution,
    state: PathStateStorage,
    val maxBlocks: Int,
    queueSize: Int = StateRecovery.QueueSize,
    coolDown: FiniteDuration = StateRecovery.CoolDown
)(implicit blockchainConfig: BlockchainConfig)
    extends Logger:
  import StateRecovery.*

  require(maxBlocks > 0, s"a recovery covers at least one block, not $maxBlocks")

  @volatile private var closed = false

  private val running = new ConcurrentHashMap[ByteString, Ticket]()

  // the heads whose recovery failed lately, and when: not tried again before the cool-down is over
  private val failed = new java.util.LinkedHashMap[ByteString, (Long, Outcome)](16, 0.75f, true):
    override def removeEldestEntry(eldest: java.util.Map.Entry[ByteString, (Long, Outcome)]): Boolean =
      size() > RememberedFailures

  private val executor =
    val pool = new ThreadPoolExecutor(
      1,
      1,
      0L,
      TimeUnit.MILLISECONDS,
      new ArrayBlockingQueue[Runnable](queueSize),
      (work: Runnable) =>
        val thread = new Thread(work, ThreadName)
        thread.setDaemon(true)
        thread
    )
    pool

  /** Asks for the state of the block `head` names, and of the blocks below it that lead to it, to be had again. Never
    * blocks, and never executes anything on the calling thread.
    *
    * @param sink
    *   what is done with each block's execution: what the Engine API does with an executed payload's
    * @param why
    *   for the log
    * @return
    *   the recovery of `head`: a new one, the one under way, or one that is done already
    */
  def requestFor(head: ByteString, sink: Sink, why: String): Ticket =
    if closed then Ticket.done(head, Outcome.Closed)
    else
      coolingDown(head) match
        case Some(outcome) =>
          log.debug(s"[STATE-RECOVERY] Head ${hex(head)} not tried again yet: its recovery ended $outcome")
          Ticket.done(head, outcome)
        case None =>
          val ticket = new Ticket(head)
          Option(running.putIfAbsent(head, ticket)) match
            case Some(underWay) => underWay
            case None =>
              try
                executor.execute(new Recovery(ticket, sink, why))
                ticket
              catch
                case _: RejectedExecutionException =>
                  running.remove(head, ticket)
                  val outcome = if closed then Outcome.Closed else Outcome.Busy
                  log.info(s"[STATE-RECOVERY] Head ${hex(head)} ($why) not recovered: $outcome")
                  ticket.finish(outcome)
                  ticket

  /** The heads being recovered, or waiting to be. */
  def inFlight: Int = running.size

  /** Whether it is stopping, or has stopped: no recovery is taken, and none executes another block. */
  def isClosed: Boolean = closed

  /** Stops: a recovery under way ends after the block it is executing, and those that wait are answered
    * [[Outcome.Closed]]. Before the data source closes.
    */
  def close(): Unit =
    closed = true
    executor.shutdown()
    val waiting = new java.util.ArrayList[Runnable]()
    executor.getQueue.drainTo(waiting)
    waiting.forEach {
      case recovery: Recovery => recovery.closedBeforeItRan()
      case _                  => ()
    }
    if !executor.awaitTermination(CloseWait.toMillis, TimeUnit.MILLISECONDS) then
      log.warn(s"[STATE-RECOVERY] A recovery was still executing a block ${CloseWait} after the node began to stop")

  /** One block's execution, as `engine_newPayload` executes a payload whose parent's state is held: its state is kept
    * as a forward layer. A test stands here.
    */
  protected def execute(
      block: Block,
      alreadyValidated: Boolean
  ): Either[BlockExecutionError, BlockImportGate.Executed] =
    execution.executeAndValidateBlockFull(block, alreadyValidated = alreadyValidated)

  private def coolingDown(head: ByteString): Option[Outcome] =
    failed.synchronized {
      Option(failed.get(head)).flatMap { (at, outcome) =>
        if System.nanoTime() - at < coolDown.toNanos then Some(outcome)
        else
          failed.remove(head)
          None
      }
    }

  final private class Recovery(ticket: Ticket, sink: Sink, why: String) extends Runnable:
    def closedBeforeItRan(): Unit =
      running.remove(ticket.head, ticket)
      ticket.finish(Outcome.Closed)

    def run(): Unit =
      val outcome =
        try recover(ticket.head, sink, why, walksLeft = 1)
        catch
          case NonFatal(failure) =>
            log.error(s"[STATE-RECOVERY] The recovery of head ${hex(ticket.head)} failed", failure)
            Outcome.LocalFault(s"the recovery failed: ${failure.getMessage}")
      outcome match
        case Outcome.Recovered(_) | Outcome.AlreadyHeld | Outcome.Closed => ()
        case _ => failed.synchronized(failed.put(ticket.head, (System.nanoTime(), outcome)))
      running.remove(ticket.head, ticket)
      ticket.finish(outcome)

  private def recover(head: ByteString, sink: Sink, why: String, walksLeft: Int): Outcome =
    walk(head) match
      case Left(outcome) =>
        logEnd(head, why, outcome)
        outcome
      case Right(Nil) => Outcome.AlreadyHeld
      case Right(chain) =>
        log.info(
          s"[STATE-RECOVERY] Executing ${chain.size} stored block(s) again, ${chain.head.number.value} to " +
            s"${chain.last.number.value}, for head ${hex(head)} ($why): their state is not held"
        )
        bodiesOf(chain) match
          case Left(outcome) =>
            logEnd(head, why, outcome)
            outcome
          case Right(blocks) =>
            executeAll(blocks, sink) match
              case Moved if walksLeft > 0 =>
                // the state moved under the execution: what it now holds is walked down to again, once
                recover(head, sink, why, walksLeft - 1)
              case Moved =>
                logEnd(head, why, Outcome.Superseded)
                Outcome.Superseded
              case Done(outcome) =>
                logEnd(head, why, outcome)
                outcome

  /** The blocks of `head`'s chain above the highest one whose state is held, oldest first. */
  private def walk(head: ByteString): Either[Outcome, List[BlockHeader]] =
    @tailrec
    def down(at: BlockHeader, above: List[BlockHeader]): Either[Outcome, List[BlockHeader]] =
      if state.stateAvailable(refOf(at)) then Right(above)
      else if above.size >= maxBlocks then
        Left(
          Outcome.TooLong(
            s"more than $maxBlocks blocks below the head ${hex(head)} have no state held (block ${at.number.value} " +
              "has none either)"
          )
        )
      else if at.number.value == 0 then
        Left(Outcome.TooLong("the head's chain was walked down to its genesis, and no state of it is held"))
      else
        reader.getBlockHeaderByHash(BlockHash(at.parentHash.value)) match
          case Some(parent) => down(parent, at :: above)
          case None         => Left(Outcome.MissingHeader(at.parentHash.value))
    reader.getBlockHeaderByHash(BlockHash(head)) match
      case None         => Left(Outcome.MissingHeader(head))
      case Some(header) => down(header, Nil)

  private def bodiesOf(chain: List[BlockHeader]): Either[Outcome, List[Block]] =
    chain
      .foldLeft[Either[Outcome, List[Block]]](Right(Nil)) { (found, header) =>
        found.flatMap { blocks =>
          reader
            .getBlockByHash(BlockHash(header.hash.value))
            .toRight(Outcome.MissingBody(refOf(header)))
            .map(_ :: blocks)
        }
      }
      .map(_.reverse)

  private def executeAll(blocks: List[Block], sink: Sink): Step =
    @tailrec
    def next(left: List[Block], executed: Int): Step =
      left match
        case Nil => Done(Outcome.Recovered(executed))
        case _ if closed =>
          Done(Outcome.Closed)
        case block :: rest =>
          val ref = refOf(block.header)
          // receipts say only whether a failure is the block's or this node's: every block is validated in full, as
          // go-ethereum's insertion of the blocks it recovers validates them (beacon)
          val executedBefore = reader.getReceiptsByHash(BlockHash(block.header.hash.value)).isDefined
          val result =
            try Right(execute(block, alreadyValidated = false))
            catch case fault: StateFault => Left(fault)
          result match
            case Left(_) => Moved
            // the node is stopping: nothing more is written, the data source is closed after this
            case Right(Right(_)) if closed => Done(Outcome.Closed)
            case Right(Right((receipts, _, accessList))) =>
              sink.executed(block, receipts, accessList)
              // its state is held: as a layer, or, once a fork choice moved the head past it meanwhile, behind the anchor
              if state.stateAvailable(ref) then next(rest, executed + 1)
              else Done(Outcome.NotKept(ref))
            case Right(Left(BlockExecutionError.StateFaulted(_))) => Moved
            case Right(
                  Left(
                    error @ (BlockExecutionError.MPTError(_) | BlockExecutionError.MissingParentError |
                    BlockExecutionError.ValidationBeforeExecError(HeaderParentNotFoundError))
                  )
                ) =>
              Done(Outcome.LocalFault(s"block ${ref.show} could not be executed: ${error.describe}"))
            case Right(Left(error)) if executedBefore =>
              log.error(
                s"[STATE-RECOVERY] Block ${ref.show}, which this node executed before, fails now: ${error.describe}. " +
                  "It is not marked invalid: this is the node's own fault"
              )
              Done(Outcome.LocalFault(s"block ${ref.show}, executed before, fails now: ${error.describe}"))
            case Right(Left(error)) =>
              sink.rejected(block, error, rest)
              Done(Outcome.Invalid(ref, error.describe))
    next(blocks, 0)

  private def logEnd(head: ByteString, why: String, outcome: Outcome): Unit =
    outcome match
      case Outcome.Recovered(n) =>
        log.info(s"[STATE-RECOVERY] Head ${hex(head)} ($why): $n block(s) executed again, their state is held")
      case Outcome.AlreadyHeld | Outcome.Closed => ()
      case other => log.warn(s"[STATE-RECOVERY] Head ${hex(head)} ($why) not recovered: $other")

object StateRecovery:

  /** The name of the one thread recoveries run on. */
  val ThreadName: String = "state-recovery"

  /** How many heads wait for a recovery at most, besides the one under way. */
  val QueueSize: Int = 4

  /** How long a head whose recovery failed is not tried again. */
  val CoolDown: FiniteDuration = 12.seconds

  private val RememberedFailures = 64

  // longer than the slowest block executes (a minute has been seen on Platåberget): the data source closes after it
  private val CloseWait: FiniteDuration = 2.minutes

  /** What is done with each block a recovery executes: what the Engine API does with an executed payload. */
  trait Sink:
    /** `block` was executed, and its state kept as a forward layer if it fitted: its receipts, its access list and its
      * chain weight are to be stored.
      */
    def executed(block: Block, receipts: Seq[Receipt], accessList: Option[BlockAccessList]): Unit

    /** `block`, never executed before, fails a rule of consensus: it and what stands on it are invalid, `above` among
      * them (the blocks of the head's chain above it, up to the head, oldest first), with its parent the latest valid
      * block. A node that started again knows them from no payload.
      */
    def rejected(block: Block, error: BlockExecutionError, above: List[Block]): Unit

  enum Outcome:
    /** The head's state is held: `executed` blocks were executed for it. */
    case Recovered(executed: Int)

    /** The head's state was held when it was looked at: nothing was executed. */
    case AlreadyHeld

    /** More blocks than a recovery covers have no state held, or none of the head's chain has. */
    case TooLong(detail: String)

    /** The header of the block `hash` names, on the head's chain, is not stored. */
    case MissingHeader(hash: ByteString)

    /** The body of `block`, on the head's chain, is not stored. Nothing was executed. */
    case MissingBody(block: BlockRef)

    /** `block` was executed and its state was not kept (data-model 6.4): the blocks above it were not executed. */
    case NotKept(block: BlockRef)

    /** `block`, never executed before, fails a rule of consensus: the blocks above it were not executed. */
    case Invalid(block: BlockRef, reason: String)

    /** This node's own fault, never the block's. */
    case LocalFault(detail: String)

    /** The state moved under the execution each time it was walked down to. */
    case Superseded

    /** The node is stopping. */
    case Closed

    /** More heads wait for a recovery than are queued: nothing was done. */
    case Busy

  /** The recovery of one head. */
  final class Ticket private[StateRecovery] (val head: ByteString):
    private val result = new CompletableFuture[Outcome]()

    /** Completes when the recovery has ended, however it ended. */
    def outcome: CompletableFuture[Outcome] = result

    private[StateRecovery] def finish(outcome: Outcome): Unit = result.complete(outcome)

  object Ticket:
    private[StateRecovery] def done(head: ByteString, outcome: Outcome): Ticket =
      val ticket = new Ticket(head)
      ticket.finish(outcome)
      ticket

  sealed private trait Step
  private case object Moved extends Step
  final private case class Done(outcome: Outcome) extends Step

  private def refOf(header: BlockHeader): BlockRef =
    BlockRef(header.hash.value, header.number.value, header.stateRoot.value)

  private def hex(bytes: ByteString): String = Hex.toHexString(bytes.toArray).take(16)
