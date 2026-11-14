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
import com.chipprbots.ethereum.db.storage.pathstate.ReorgTooDeep
import com.chipprbots.ethereum.db.storage.pathstate.StateFault
import com.chipprbots.ethereum.db.storage.pathstate.StateUnavailable
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.BlockAccessList
import com.chipprbots.ethereum.domain.BlockHash
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.domain.BlockchainWriter
import com.chipprbots.ethereum.domain.ChainWeight
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
  * push out the one below it before it is used, nor every other layer kept. A fork choice's branch longer than that is
  * committed in place instead (C4d part 4, [[adopt]]). A head whose recovery failed is not tried again for
  * [[StateRecovery.CoolDown]].
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
    writer: BlockchainWriter,
    execution: BlockExecution,
    state: PathStateStorage,
    val maxBlocks: Int,
    val maxAdoptedBlocks: Int = StateRecovery.DefaultMaxAdoptedBlocks,
    queueSize: Int = StateRecovery.QueueSize,
    coolDown: FiniteDuration = StateRecovery.CoolDown
)(implicit blockchainConfig: BlockchainConfig)
    extends Logger:
  import StateRecovery.*

  require(maxBlocks > 0, s"a recovery covers at least one block, not $maxBlocks")
  require(maxAdoptedBlocks >= maxBlocks, s"an adoption covers no fewer blocks than a recovery ($maxBlocks)")

  @volatile private var closed = false

  // by head and by what asked: a fork choice's recovery is not the payload's under way for the same head, which keeps
  // layers only, and runs after it
  private val running = new ConcurrentHashMap[(ByteString, Authority), Ticket]()

  // the heads whose recovery failed lately, and when: not tried again before the cool-down is over
  private val failed = new java.util.LinkedHashMap[ByteString, (Long, Outcome, Authority)](16, 0.75f, true):
    override def removeEldestEntry(eldest: java.util.Map.Entry[ByteString, (Long, Outcome, Authority)]): Boolean =
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
    * @param authority
    *   what asks: a payload has the blocks kept as forward layers, and nothing else; a fork choice that names `head`
    *   also has a branch longer than the layers may hold committed in place, the head moved with it (C4d part 4)
    * @return
    *   the recovery of `head`: a new one, the one under way, or one that is done already
    */
  def requestFor(head: ByteString, sink: Sink, why: String, authority: Authority = Authority.Payload): Ticket =
    if closed then Ticket.done(head, Outcome.Closed)
    else
      coolingDown(head, authority) match
        case Some(outcome) =>
          log.debug(s"[STATE-RECOVERY] Head ${hex(head)} not tried again yet: its recovery ended $outcome")
          Ticket.done(head, outcome)
        case None =>
          val ticket = new Ticket(head)
          // the last place in the queue is a fork choice's: a run of payloads during a long adoption does not leave the
          // consensus client's next fork choice Busy (beacon)
          if authority == Authority.Payload && executor.getQueue.remainingCapacity() <= 1 then
            log.info(s"[STATE-RECOVERY] Head ${hex(head)} ($why) not recovered: ${Outcome.Busy}")
            ticket.finish(Outcome.Busy)
            ticket
          else
            Option(running.putIfAbsent((head, authority), ticket)) match
              case Some(underWay) => underWay
              case None =>
                try
                  executor.execute(new Recovery(ticket, sink, why, authority))
                  ticket
                catch
                  case _: RejectedExecutionException =>
                    running.remove((head, authority), ticket)
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

  // a fork choice is not held back by what a payload's recovery could not do: it may do more
  private def coolingDown(head: ByteString, authority: Authority): Option[Outcome] =
    failed.synchronized {
      Option(failed.get(head)).flatMap { (at, outcome, by) =>
        if authority == Authority.ForkChoice && by == Authority.Payload then None
        else if System.nanoTime() - at < coolDown.toNanos then Some(outcome)
        else
          failed.remove(head)
          None
      }
    }

  final private class Recovery(ticket: Ticket, sink: Sink, why: String, authority: Authority) extends Runnable:
    def closedBeforeItRan(): Unit =
      running.remove((ticket.head, authority), ticket)
      ticket.finish(Outcome.Closed)

    def run(): Unit =
      val outcome =
        try recover(ticket.head, sink, why, authority, walksLeft = 1)
        catch
          case NonFatal(failure) =>
            log.error(s"[STATE-RECOVERY] The recovery of head ${hex(ticket.head)} failed", failure)
            Outcome.LocalFault(s"the recovery failed: ${failure.getMessage}")
      outcome match
        // a recovery that was superseded or stopped did not fail: the head may be asked for again at once (beacon)
        case Outcome.Recovered(_) | Outcome.Adopted(_) | Outcome.AlreadyHeld | Outcome.Closed | Outcome.Superseded => ()
        case _ => failed.synchronized(failed.put(ticket.head, (System.nanoTime(), outcome, authority)))
      running.remove((ticket.head, authority), ticket)
      ticket.finish(outcome)

  private def recover(head: ByteString, sink: Sink, why: String, authority: Authority, walksLeft: Int): Outcome =
    // a fork choice walks as far as a branch is adopted; a payload, as far as its blocks are kept as layers
    val reach = if authority == Authority.ForkChoice then maxAdoptedBlocks else maxBlocks
    walk(head, reach) match
      case Left(outcome) =>
        logEnd(head, why, outcome)
        outcome
      case Right((_, Nil)) => Outcome.AlreadyHeld
      case Right((held, chain))
          if authority == Authority.ForkChoice &&
            chain.size + state.heldDepth(refOf(held)).getOrElse(0) > maxBlocks =>
        // more than the layers may hold, with those it stands on: committed in place, as a long branch is (C4d part 4)
        adopt(head, held, chain, sink, why)
      case Right((_, chain)) =>
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
                recover(head, sink, why, authority, walksLeft - 1)
              case Moved =>
                logEnd(head, why, Outcome.Superseded)
                Outcome.Superseded
              case Done(Outcome.NotKept(_)) if authority == Authority.ForkChoice =>
                // a layer did not fit: what is left is committed in place, as a long branch is (beacon)
                walk(head, reach) match
                  case Right((_, Nil))     => Outcome.AlreadyHeld
                  case Right((held, rest)) => adopt(head, held, rest, sink, why)
                  case Left(outcome) =>
                    logEnd(head, why, outcome)
                    outcome
              case Done(outcome) =>
                logEnd(head, why, outcome)
                outcome

  /** The highest block of `head`'s chain whose state is held, and the blocks above it, oldest first: no more than
    * `reach` of them.
    */
  private def walk(head: ByteString, reach: Int): Either[Outcome, (BlockHeader, List[BlockHeader])] =
    @tailrec
    def down(at: BlockHeader, above: List[BlockHeader]): Either[Outcome, (BlockHeader, List[BlockHeader])] =
      if state.stateAvailable(refOf(at)) then Right((at, above))
      else if above.size >= reach then
        Left(
          Outcome.TooLong(
            s"more than $reach blocks below the head ${hex(head)} have no state held (block ${at.number.value} " +
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

  /** The adoption of a branch longer than the forward layers may hold, for a fork choice that names its head (spec 018
    * C4d part 4, data-model 6.4: reverse-then-commit, with no stack of layers at all). With the changeset records above
    * the fork held from retention, the head is moved, with the state, to `held` (down to the fork and up the layers
    * kept, or across), and the blocks above it are committed in place one by one, as an import commits them, each in
    * the batch that makes it the head. The consensus client is answered SYNCING meanwhile, and its next fork choice to
    * the head confirms it, as for any other (go-ethereum moves its head block by block while it inserts a chain it
    * syncs). The head the fork choice followed last is not changed here.
    *
    * It stops when the consensus client names another head, or the node stops. A block found invalid on the way (one
    * never executed) is handed to the [[Sink]] with what stands on it, and the head is moved back to the head of the
    * last fork choice followed, or to where it was: on proof of stake the consensus client's head stays the one it
    * validated (the user's decision of 2026-11-14). So it is too on this node's own fault.
    */
  private def adopt(
      head: ByteString,
      held: BlockHeader,
      chain: List[BlockHeader],
      sink: Sink,
      why: String
  ): Outcome =
    val outcome = bodiesOf(chain) match
      case Left(missing) => missing
      case Right(blocks) =>
        forkOf(held) match
          case None =>
            Outcome.NotAdopted(
              s"block ${refOf(held).show}, which the branch stands on, does not lead back to the chain whose state is " +
                "held"
            )
          case Some(fork) =>
            log.info(
              s"[STATE-RECOVERY] Committing ${blocks.size} block(s) in place, ${chain.head.number.value} to " +
                s"${chain.last.number.value}, for head ${hex(head)} ($why): more than the executed blocks kept beside " +
                s"the disk may hold. The head is moved to ${refOf(held).show} first; the fork is ${refOf(fork).show}"
            )
            val from = state.anchor
            val asked = () => stillAsked(head, sink)
            // the records are held from the fork, or from where the head of the last fork choice followed leaves the
            // index if that is lower: the way back to it is kept too (beacon)
            val holdFrom = sink.followedHead
              .flatMap(hash => reader.getBlockHeaderByHash(BlockHash(hash)))
              .flatMap(forkOf)
              .filter(_.number.value < fork.number.value)
              .getOrElse(fork)
            if !asked() then Outcome.Superseded
            else
              try
                state.withRecordsHeld(refOf(holdFrom)) {
                  val movedTo =
                    try
                      // a fork choice to another head meanwhile refuses the move before any of it is written: the head
                      // is not taken off a head the consensus client has gone on to (beacon)
                      if !state.isApplied(held.hash.value) then
                        writer.moveHeadInPlace(
                          held,
                          reader,
                          refuse = _ => Option.when(closed || !asked())("another head is named, or the node stops")
                        )
                      None
                    catch
                      case refused: StateFault =>
                        // a move of more than one batch may have written some of them: the head goes back, unless
                        // the consensus client has named another head, whose fork choice moves it
                        if asked() && state.anchor != from then moveBack(sink, from)
                        Some(if asked() then Outcome.NotAdopted(refused.getMessage) else Outcome.Superseded)
                  movedTo.getOrElse {
                    commitEach(head, blocks, sink) match
                      case refused @ (Outcome.Invalid(_, _) | Outcome.LocalFault(_)) =>
                        moveBack(sink, from)
                        refused
                      case other => other
                  }
                }
              catch case refused: ReorgTooDeep => Outcome.NotAdopted(refused.getMessage)
    logEnd(head, why, outcome)
    outcome

  /** Whether the consensus client still names `head`, or a block its chain leads to: a head that only extends the
    * branch does not stop its adoption, as go-ethereum goes on inserting a chain its new head extends (beacon).
    */
  private def stillAsked(head: ByteString, sink: Sink): Boolean =
    sink.requestedHead.exists { requested =>
      requested == head || reader.getBlockHeaderByHash(BlockHash(head)).exists { headHeader =>
        @tailrec
        def leadsTo(at: ByteString, left: Int): Boolean =
          if at == head then true
          else if left == 0 then false
          else
            reader.getBlockHeaderByHash(BlockHash(at)) match
              case Some(header) if header.number.value > headHeader.number.value =>
                leadsTo(header.parentHash.value, left - 1)
              case _ => false
        leadsTo(requested, ExtendsSearch)
      }
    }

  /** The blocks, each committed in place as the head, in order, while the consensus client still names `head` or a
    * block its chain leads to.
    */
  private def commitEach(head: ByteString, blocks: List[Block], sink: Sink): Outcome =
    def stillAsked: Boolean = this.stillAsked(head, sink)
    @tailrec
    def next(left: List[Block], committed: Int): Outcome =
      left match
        case Nil              => Outcome.Adopted(committed)
        case _ if closed      => Outcome.Closed
        case _ if !stillAsked => Outcome.Superseded
        case block :: rest =>
          val ref = refOf(block.header)
          val executedBefore = reader.getReceiptsByHash(BlockHash(block.header.hash.value)).isDefined
          reader.getChainWeightByHash(block.header.parentHash) match
            case None =>
              Outcome.LocalFault(s"the chain weight of the parent of block ${ref.show} is not stored")
            case Some(parentWeight) =>
              // looked at again after the block is validated and before it is committed: a fork choice to another head,
              // or the node stopping, meanwhile leaves it uncommitted
              val stop = () => Option.when(closed || !stillAsked)(Stopped)
              val result =
                try Right(adoptBlock(block, parentWeight, stop))
                catch case fault: StateFault => Left(fault)
              result match
                case Left(_)         => Outcome.Superseded
                case Right(Right(_)) => next(rest, committed + 1)
                case Right(Left(error)) if error eq Stopped =>
                  if closed then Outcome.Closed else Outcome.Superseded
                case Right(Left(BlockExecutionError.StateFaulted(_))) => Outcome.Superseded
                case Right(
                      Left(
                        error @ (BlockExecutionError.MPTError(_) | BlockExecutionError.MissingParentError |
                        BlockExecutionError.ValidationBeforeExecError(HeaderParentNotFoundError))
                      )
                    ) =>
                  Outcome.LocalFault(s"block ${ref.show} could not be executed: ${error.describe}")
                case Right(Left(error)) if executedBefore =>
                  log.error(
                    s"[STATE-RECOVERY] Block ${ref.show}, which this node executed before, fails now: " +
                      s"${error.describe}. It is not marked invalid: this is the node's own fault"
                  )
                  Outcome.LocalFault(s"block ${ref.show}, executed before, fails now: ${error.describe}")
                case Right(Left(error)) =>
                  sink.rejected(block, error, rest)
                  Outcome.Invalid(ref, error.describe)
    next(blocks, 0)

  /** One block committed in place as the head, as an import commits it. A test stands here. */
  protected def adoptBlock(
      block: Block,
      parentWeight: ChainWeight,
      beforeCommit: () => Option[BlockExecutionError]
  ): Either[BlockExecutionError, Any] =
    execution.executeAndAdoptBlockIf(block, parentWeight, beforeCommit)

  /** The head moved back after a branch failed: to the head of the last fork choice followed, or to where the adoption
    * found it. A way back that cannot be taken (its records unreadable) leaves the head on the last block committed,
    * all of whose blocks are valid: said at ERROR, and the state does not fail closed.
    */
  private def moveBack(sink: Sink, from: Option[BlockRef]): Unit =
    val targets = (sink.followedHead.toList ++ from.map(_.hash).toList).distinct
    val back = targets.iterator.flatMap(hash => reader.getBlockHeaderByHash(BlockHash(hash))).find { header =>
      state.isApplied(header.hash.value) || (
        try
          writer.moveHeadInPlace(header, reader)
          true
        catch
          case NonFatal(refused) =>
            log.warn(s"[STATE-RECOVERY] The head was not moved back to ${refOf(header).show}: ${refused.getMessage}")
            false
      )
    }
    back match
      case Some(header) => log.info(s"[STATE-RECOVERY] The head is back at ${refOf(header).show}")
      case None =>
        log.error(
          s"[STATE-RECOVERY] The head could not be moved back after the branch failed: it is left at " +
            s"${state.anchor.map(_.show).getOrElse("no block")}, whose blocks are valid"
        )

  /** The highest block of `held`'s chain that the index of blocks by number names: where it leaves the chain whose
    * state is held (the index is that chain, I6).
    */
  private def forkOf(held: BlockHeader): Option[BlockHeader] =
    @tailrec
    def down(at: BlockHeader, left: Int): Option[BlockHeader] =
      if reader.getCanonicalHashByNumber(at.number.value).exists(_ == at.hash) then Some(at)
      else if left == 0 || at.number.value == 0 then None
      else
        reader.getBlockHeaderByHash(BlockHash(at.parentHash.value)) match
          case Some(parent) => down(parent, left - 1)
          case None         => None
    down(held, ForkSearch)

  private def logEnd(head: ByteString, why: String, outcome: Outcome): Unit =
    outcome match
      case Outcome.Recovered(n) =>
        log.info(s"[STATE-RECOVERY] Head ${hex(head)} ($why): $n block(s) executed again, their state is held")
      case Outcome.Adopted(n) =>
        log.info(s"[STATE-RECOVERY] Head ${hex(head)} ($why): $n block(s) committed in place, the head is at it")
      case Outcome.AlreadyHeld | Outcome.Closed => ()
      case other => log.warn(s"[STATE-RECOVERY] Head ${hex(head)} ($why) not recovered: $other")

object StateRecovery:

  /** The name of the one thread recoveries run on. */
  val ThreadName: String = "state-recovery"

  /** How many blocks an adoption covers at most, by default: `state.recovery.max-blocks`, whose default it is. */
  val DefaultMaxAdoptedBlocks: Int = 64

  /** How far down a branch's chain the block it leaves the held chain at is looked for, at most. */
  private val ForkSearch = 65_536

  /** How many blocks above an adoption's head a new head of the consensus client's is followed down, at most. */
  private val ExtendsSearch = 1_024

  /** What asks for a recovery. */
  enum Authority:
    /** A payload: its blocks are kept as forward layers, and the head is never moved. */
    case Payload

    /** A fork choice that names the head: a branch longer than the layers may hold is also committed in place, the head
      * moved with it (C4d part 4).
      */
    case ForkChoice

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

    /** The head the consensus client names now, executed or not: an adoption goes on while it is its head, or a block
      * its chain leads to. With none named, an adoption never begins.
      */
    def requestedHead: Option[ByteString] = None

    /** The head of the last fork choice the node followed: where a branch found invalid takes the head back. */
    def followedHead: Option[ByteString] = None

    /** `block`, never executed before, fails a rule of consensus: it and what stands on it are invalid, `above` among
      * them (the blocks of the head's chain above it, up to the head, oldest first), with its parent the latest valid
      * block. A node that started again knows them from no payload.
      */
    def rejected(block: Block, error: BlockExecutionError, above: List[Block]): Unit

  enum Outcome:
    /** The head's state is held: `executed` blocks were executed for it. */
    case Recovered(executed: Int)

    /** A branch longer than the layers may hold was committed in place: `committed` blocks, the head moved to the last.
      */
    case Adopted(committed: Int)

    /** A branch longer than the layers may hold could not be committed in place: the head is where it was, or was moved
      * back to it.
      */
    case NotAdopted(detail: String)

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

  // what the check before an adoption's commit answers when it is to stop
  private val Stopped: BlockExecutionError =
    BlockExecutionError.StateFaulted(
      new StateUnavailable("the adoption was stopped before the commit: another head is named, or the node stops")
    )

  sealed private trait Step
  private case object Moved extends Step
  final private case class Done(outcome: Outcome) extends Step

  private def refOf(header: BlockHeader): BlockRef =
    BlockRef(header.hash.value, header.number.value, header.stateRoot.value)

  private def hex(bytes: ByteString): String = Hex.toHexString(bytes.toArray).take(16)
