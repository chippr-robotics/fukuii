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
import com.chipprbots.ethereum.db.storage.pathstate.HeadMovePlan
import com.chipprbots.ethereum.db.storage.pathstate.PathStateStorage
import com.chipprbots.ethereum.db.storage.pathstate.ReorgTooDeep
import com.chipprbots.ethereum.db.storage.pathstate.StateBusy
import com.chipprbots.ethereum.db.storage.pathstate.StateFault
import com.chipprbots.ethereum.db.storage.pathstate.StateMetrics
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
    // how many blocks a straight extension of the head may be caught up over; none beyond an adoption when it is less
    catchUpBlocks: Int = 0,
    catchUpChunk: Int = StateRecovery.CatchUpChunk,
    queueSize: Int = StateRecovery.QueueSize,
    coolDown: FiniteDuration = StateRecovery.CoolDown
)(implicit blockchainConfig: BlockchainConfig)
    extends Logger:
  import StateRecovery.*

  require(maxBlocks > 0, s"a recovery covers at least one block, not $maxBlocks")
  require(maxAdoptedBlocks >= maxBlocks, s"an adoption covers no fewer blocks than a recovery ($maxBlocks)")
  require(catchUpChunk > 0, s"a catch-up commits at least one block at a time, not $catchUpChunk")

  /** How many blocks a fork choice's recovery walks down, at most: as far as a straight extension of the head is caught
    * up over (`state.recovery.max-catch-up`), and no less than a branch is adopted over.
    */
  val maxCatchUp: Int = catchUpBlocks.max(maxAdoptedBlocks)

  @volatile private var shut = false

  // a reset under way ([[reset]]): as stopping, for the recoveries that wait and the one that runs, and then over
  @volatile private var draining = false

  private def closed: Boolean = shut || draining

  // requests are taken under its read lock; a reset begins under its write lock
  private val taking = new java.util.concurrent.locks.ReentrantReadWriteLock()

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
    // taken or refused as one step against a reset: a request that saw no reset is in the queue before the reset drains
    // it, and one that comes after sees it (beacon)
    taking.readLock().lock()
    try requestNow(head, sink, why, authority)
    finally taking.readLock().unlock()

  private def requestNow(head: ByteString, sink: Sink, why: String, authority: Authority): Ticket =
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

  @volatile private var adopting: Option[Progress] = None

  /** The adoption of a long branch under way, if one is: the node's head walks down to its fork and up the branch
    * meanwhile, and `eth_syncing` says so (C4d part 4b, the user's decision of 2026-11-14).
    */
  def adoption: Option[Progress] = adopting

  /** Whether it is stopping, or has stopped: no recovery is taken, and none executes another block. */
  def isClosed: Boolean = closed

  /** Whether a [[reset]] is under way: no recovery is taken, and the one that runs ends after its block. */
  def isResetting: Boolean = draining

  /** Stops: a recovery under way ends after the block it is executing, and those that wait are answered
    * [[Outcome.Closed]]. Before the data source closes.
    */
  def close(): Unit =
    shut = true
    executor.shutdown()
    val waiting = new java.util.ArrayList[Runnable]()
    executor.getQueue.drainTo(waiting)
    waiting.forEach {
      case recovery: Recovery => recovery.closedBeforeItRan()
      case _                  => ()
    }
    if !executor.awaitTermination(CloseWait.toMillis, TimeUnit.MILLISECONDS) then
      log.warn(s"[STATE-RECOVERY] A recovery was still executing a block ${CloseWait} after the node began to stop")

  /** Before the state is cleared for a sync from scratch (spec 018 C5a, B-307; beacon): the recoveries that wait are
    * answered [[Outcome.Closed]], the one under way ends after the block it is executing, and what was remembered of
    * the heads whose recovery failed is forgotten. Then recoveries are taken again.
    *
    * Left to run, a recovery between two blocks it commits in place would move the head back over a state that is gone,
    * and one that then fails the state closed would keep the sync that follows from ever handing off. What is
    * remembered of a failure is of the state that is given up.
    *
    * '''The state is cleared inside''' (`whileNoneRuns`), with no recovery running and none taken: one asked for
    * meanwhile is answered [[Outcome.Closed]], as while the node stops. After it, a recovery finds no state and ends as
    * one that could not be done, which is what the node says while it syncs.
    *
    * @param whileNoneRuns
    *   run once no recovery runs, and before any is taken again; not run when the one under way did not end in time
    * @return
    *   false when the recovery under way was still executing a block after `wait`: nothing was run
    */
  def reset(wait: FiniteDuration = CloseWait)(whileNoneRuns: => Unit): Boolean =
    if shut then
      whileNoneRuns
      true
    else
      taking.writeLock().lock()
      try draining = true
      finally taking.writeLock().unlock()
      try
        val waiting = new java.util.ArrayList[Runnable]()
        executor.getQueue.drainTo(waiting)
        waiting.forEach {
          case recovery: Recovery => recovery.closedBeforeItRan()
          case _                  => ()
        }
        // one thread: what is submitted now runs when the recovery under way has ended
        val ended =
          try
            executor.submit((() => ()): Runnable).get(wait.toMillis, TimeUnit.MILLISECONDS)
            true
          catch
            case _: java.util.concurrent.TimeoutException => false
            case _: RejectedExecutionException            => shut
        if !ended then
          log.warn(s"[STATE-RECOVERY] A recovery was still executing a block $wait after a reset was asked for")
          false
        else if !running.isEmpty then
          // not met: no request is taken since the reset began, those that waited were ended, the one that ran has
          log.error(s"[STATE-RECOVERY] ${running.size} recoveries are still listed after a reset: nothing is cleared")
          false
        else
          failed.synchronized(failed.clear())
          adopting = None
          whileNoneRuns
          true
      finally draining = false

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
    // a fork choice walks as far as an extension of the head is caught up over; a payload, as far as its blocks are
    // kept as layers
    val reach = if authority == Authority.ForkChoice then maxCatchUp else maxBlocks
    // a fork choice's walk also ends where the head's chain meets the chain whose state is held, however far behind
    // what a view reaches: the head is moved there by the records (B-316)
    val byRecords = authority == Authority.ForkChoice
    walk(head, reach, byRecords) match
      case Left(outcome) =>
        logEnd(head, why, outcome)
        outcome
      case Right(Walked(_, Nil, _)) => Outcome.AlreadyHeld
      case Right(Walked(held, chain, _)) if authority == Authority.ForkChoice && chain.size > maxAdoptedBlocks =>
        // Beyond what a branch is adopted over (C5a part 8; the user's decision of 2026-11-15): only a straight
        // extension of the head, which is what stands between a sync's hand-off and the consensus client's head.
        // A branch that leaves the chain keeps its bound: its way back is kept in records, which reach that far.
        val outcome =
          if extendsTheHead(held) then catchUp(head, held, chain, sink, why)
          else
            Outcome.TooLong(
              s"${chain.size} blocks below the head ${hex(head)} have no state held, more than the " +
                s"$maxAdoptedBlocks a branch is adopted over, and they are no extension of this node's head"
            )
        logEnd(head, why, outcome)
        outcome
      case Right(Walked(held, chain, viewless))
          if authority == Authority.ForkChoice &&
            (viewless || chain.size + state.heldDepth(refOf(held)).getOrElse(0) > maxBlocks) =>
        // more than the layers may hold, with those it stands on: committed in place, as a long branch is (C4d part 4).
        // So too a branch whose fork no view reaches: nothing can be executed on a view of it, and the head is moved
        // down to it by the records first, as the import path adopts a heavier chain that forks that deep (C4e part 4)
        adopt(head, held, chain, sink, why)
      case Right(Walked(_, chain, _)) =>
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
                walk(head, reach, byRecords) match
                  case Right(Walked(_, Nil, _))     => Outcome.AlreadyHeld
                  case Right(Walked(held, rest, _)) => adopt(head, held, rest, sink, why)
                  case Left(outcome) =>
                    logEnd(head, why, outcome)
                    outcome
              case Done(outcome) =>
                logEnd(head, why, outcome)
                outcome

  /** The highest block of `head`'s chain whose state is held, and the blocks above it, oldest first: no more than
    * `reach` of them.
    *
    * With `byRecords`, for a fork choice that may move the head: or the block where `head`'s chain meets the chain
    * whose state is held (the index of blocks by number, I6), when no view of it can be made (B-316). In the archive
    * mode the records lead further back than a view reaches (`state.archive.view-reach`), and in the basic mode they
    * can when the floor lags: a branch that forks there is otherwise walked down past its fork to no state, and is
    * adopted by no one. Whether the records do lead there is the move's to say ([[adopt]]): it is refused, with nothing
    * written, when they do not.
    */
  private def walk(head: ByteString, reach: Int, byRecords: Boolean): Either[Outcome, Walked] =
    @tailrec
    def down(at: BlockHeader, above: List[BlockHeader]): Either[Outcome, Walked] =
      if state.stateAvailable(refOf(at)) then Right(Walked(at, above, viewless = false))
      // Only where the head's chain leaves the index: the block above it is not of the index. Never the head itself,
      // nor a block of a head that is an ancestor of the head, which a fork choice does not move the head down to (the
      // walk goes on down, and finds no state). And behind the anchor, where the index is the anchor's ancestry: it
      // may run ahead of the anchor (after a sync's hand-off), and a block there is reached by no record (B-480)
      else if byRecords && above.headOption.exists(child => !ofTheIndex(child)) && behindTheAnchor(at) then
        Right(Walked(at, above, viewless = true))
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
    def ofTheIndex(at: BlockHeader): Boolean = reader.getCanonicalHashByNumber(at.number.value).contains(at.hash)
    def behindTheAnchor(at: BlockHeader): Boolean = state.anchor.exists(_.number > at.number.value) && ofTheIndex(at)
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
              StateMetrics.reexecuted(1)
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
            // the head already on the branch, above `held`: another writer (the import path's move of the consensus
            // layer's branch, B-297) took it there since the walk. Moved to `held` it would go back down
            val ahead = aheadOfHeld(chain)
            if !asked() then Outcome.Superseded
            else
              // from the fork, where the chain the head leaves meets the branch, to the branch's tip (beacon)
              adopting = Some(Progress(fork.number.value, held.number.value, chain.last.number.value))
              // whether the hold was taken: one that was refused held nothing and wrote nothing
              var holding = false
              // what the move up to `held` makes canonical, read before it while the index is the old head's ancestry,
              // and the blocks committed above it: their weights are re-derived once the hold is let go (B-490), however
              // the adoption ends, an exception out of a commit included (B-510). A block's exact weight depends only on
              // its ancestry: right whether or not the head stays on it
              val madeCanonical = notCanonicalBelow(held)
              val committed = scala.collection.mutable.ListBuffer.empty[BlockHash]
              rederivingAfter(madeCanonical ++ committed) {
                try
                  // on the head read as `from`, which a move back leads to: a block committed on it since refuses the
                  // hold as busy, nothing written, and the next fork choice reads the head again (B-463)
                  state.withRecordsHeld(refOf(holdFrom), expectedAnchor = from) {
                    holding = true
                    val movedTo =
                      try
                        // a fork choice to another head meanwhile refuses the move before any of it is written: the head
                        // is not taken off a head the consensus client has gone on to (beacon)
                        if !state.isApplied(held.hash.value) then
                          writer.moveHeadInPlace(
                            held,
                            reader,
                            refuse = _ => Option.when(closed || !asked())("another head is named, or the node stops"),
                            admit = ahead.admit
                          )
                        None
                      catch
                        // nothing was written: the head is past `held` on the branch, and the next fork choice goes on
                        case _: StateFault if ahead.met => Some(Outcome.Superseded)
                        case refused: StateFault        =>
                          // a move of more than one batch may have written some of them: the head goes back, unless
                          // the consensus client has named another head, whose fork choice moves it
                          if asked() && state.anchor != from then moveBack(sink, from)
                          Some(if asked() then Outcome.NotAdopted(refused.getMessage) else Outcome.Superseded)
                    movedTo.getOrElse {
                      // the block this adoption's own writes leave the head on: `held`, then each block it commits
                      var leftOn = held.hash.value
                      commitEach(
                        head,
                        blocks,
                        sink,
                        asked = None,
                        onCommitted = block =>
                          leftOn = block.header.hash.value
                          committed += block.header.hash
                      ) match
                        case refused @ (Outcome.Invalid(_, _) | Outcome.LocalFault(_)) =>
                          // only from there (B-464): a fork choice to another head may have moved the head meanwhile,
                          // and not yet recorded it as the head it followed
                          moveBack(sink, from, leftOn = Some(leftOn))
                          refused
                        case other => other
                    }
                  }
                catch
                  case refused: ReorgTooDeep => Outcome.NotAdopted(refused.getMessage)
                  // another thread's adoption is moving the head (B-294): no fault of this node, and no verdict on the
                  // branch. Superseded, as a state that moved under it: asked for again at once, with no cool-down,
                  // and not counted by the escalation's watch (beacon). The hold refused is a `StateBusy` (B-462); so it
                  // is when a block was committed on the head since `from` was read (B-463)
                  case _: StateBusy if !holding => Outcome.Superseded
                finally adopting = None
              }
    logEnd(head, why, outcome)
    outcome

  /** Whether the chain above `held`, a block whose state is held, is a straight extension of the head: `held` is the
    * block the disk holds the state of, or stands on it by executed blocks kept beside the disk. No block of the node's
    * chain is left, and nothing is moved back.
    */
  private def extendsTheHead(held: BlockHeader): Boolean =
    forkOf(held).exists(fork => state.isApplied(fork.hash.value))

  /** The catch-up after a sync's hand-off, on proof of stake (spec 018 C5a part 8; the user's decision of 2026-11-15):
    * the blocks between the head and the head the consensus client names, a straight extension, committed in place one
    * by one, a chunk of bodies at a time, as far as `state.recovery.max-catch-up`. A hand-off leaves the head at the
    * pivot, at least 64 blocks below the consensus client's by construction: without this every hand-off would end in a
    * head that cannot be reached, and in a sync from scratch again.
    *
    * It differs from the adoption of a branch ([[adopt]]) where a branch needs what an extension does not:
    *   - '''no records are held and nothing is moved back'''. Nothing of the node's chain is left. A block found
    *     invalid on the way, or a fault of this node, leaves the head at the last block committed, which is the last
    *     valid block of the chain: after a hand-off there is no head the consensus client validated to go back to, and
    *     the blocks under the head cannot be undone below the block the state began at (beacon). The invalid block and
    *     what stands on it are handed to the [[Sink]], as ever;
    *   - '''the bodies are read a chunk at a time''': thousands of blocks at once do not fit a heap.
    *
    * It stops when the consensus client names a head that the chain does not lead to, or the node stops; what was
    * committed stays. A header or a body that is not stored ends it there: the blocks are asked for elsewhere, and the
    * next fork choice goes on from the head.
    */
  private def catchUp(head: ByteString, held: BlockHeader, chain: List[BlockHeader], sink: Sink, why: String): Outcome =
    log.info(
      s"[STATE-RECOVERY] Catching up ${chain.size} block(s) in place, ${chain.head.number.value} to " +
        s"${chain.last.number.value}, for head ${hex(head)} ($why): a straight extension of the head, $catchUpChunk " +
        "blocks at a time"
    )
    // A head the consensus client names whose header is not stored yet does not stop a catch-up (beacon): the fork
    // choice manager records the head it is told before it looks for its header, as when the consensus client is ahead
    // of the payloads it has sent. A straight extension of the head has nothing to undo, whichever head comes next.
    val asked = () =>
      stillAsked(head, sink) ||
        sink.requestedHead.exists(requested => reader.getBlockHeaderByHash(BlockHash(requested)).isEmpty)
    @tailrec
    def next(left: List[BlockHeader], committed: Int): Outcome =
      if left.isEmpty then Outcome.Adopted(committed)
      else if closed then Outcome.Closed
      else if !asked() then Outcome.Superseded
      else
        val (chunk, rest) = left.splitAt(catchUpChunk)
        bodiesOf(chunk) match
          // the chunks before it moved the head (spec 018 C5a part 10, beacon C6): that is what is told, and the next
          // fork choice to the head catches up from there once the body is stored. Told as missing, a catch-up that
          // committed thousands of blocks would be counted as a refusal
          case Left(missing) => if committed > 0 then Outcome.Adopted(committed) else missing
          case Right(blocks) =>
            val chunkCommitted = scala.collection.mutable.ListBuffer.empty[BlockHash]
            // what the chunk committed, once its commits have returned or one has thrown (B-510): on the chunk below,
            // re-derived already (B-490)
            rederivingAfter(chunkCommitted.toList) {
              commitEach(head, blocks, sink, Some(asked), onCommitted = chunkCommitted += _.header.hash)
            } match
              case Outcome.Adopted(count)              => next(rest, committed + count)
              case invalid @ Outcome.Invalid(found, _) =>
                // The blocks of the chunk above it were handed to the sink with it. Those of the chunks not read yet,
                // the head among them, stand on it too (beacon): left unmarked, the next fork choice to the head
                // would find a block of its chain gone, and be answered as a fault of this node for ever.
                chunk
                  .find(_.hash.value == found.hash)
                  .foreach(block => sink.rejectedAbove(block.parentHash.value, rest))
                invalid
              case other => other
    if !asked() then Outcome.Superseded
    else
      adopting = Some(Progress(held.number.value, held.number.value, chain.last.number.value))
      val ahead = aheadOfHeld(chain)
      // what the move up to `held` makes canonical, read before it while the index is the old head's ancestry (B-490)
      val madeCanonical = notCanonicalBelow(held)
      try
        // the executed blocks kept beside the disk, up to the block the chain stands on, become the head first. Their
        // weights are re-derived once the move has returned or thrown (a move of more than one batch may have written
        // some of them, B-510), and before the first chunk reads the weight of `held`; a block's exact weight depends
        // only on its ancestry, so it is right whether or not the move was made
        val refused = rederivingAfter(madeCanonical) {
          try
            if !state.isApplied(held.hash.value) then
              writer.moveHeadInPlace(
                held,
                reader,
                refuse = _ => Option.when(closed || !asked())("another head is named, or the node stops"),
                admit = ahead.admit
              )
            None
          catch
            case _: StateFault if ahead.met => Some(Outcome.Superseded)
            // refused while another thread's adoption moves the head (B-294, a `StateBusy`: B-462), or moved by another
            // writer between two of its batches (a `StateUnavailable` asking again): the head is where it was, or on the
            // extension, and nothing is to be moved back. Superseded, as for a commit refused on the way up: asked
            // again at once, and not counted (beacon)
            case _: StateBusy                            => Some(Outcome.Superseded)
            case busy: StateUnavailable if busy.askAgain => Some(Outcome.Superseded)
            case fault: StateFault => Some(if asked() then Outcome.NotAdopted(fault.getMessage) else Outcome.Superseded)
        }
        refused.getOrElse(next(chain, 0))
      finally adopting = None

  /** The check, as a move to the block a chain stands on is admitted, that the head is not on that chain already, above
    * it, nor on a chain that stands on its tip: another writer took it there after the walk (B-297, beacon). A move
    * then would take the head back down, below a block it had reached; it is refused before anything is written, and
    * `met` says so.
    */
  private def aheadOfHeld(chain: List[BlockHeader]): AheadOfHeld =
    val tip = chain.last
    // whether the block the head is at stands on the tip: followed down by its headers, at most as far as a new head of
    // the consensus client's is followed down
    def standsOnTheTip(anchor: BlockRef): Boolean =
      @tailrec
      def down(at: ByteString, left: Int): Boolean =
        reader.getBlockHeaderByHash(BlockHash(at)) match
          case Some(header) if header.number.value == tip.number.value => header.hash.value == tip.hash.value
          case Some(header) if header.number.value > tip.number.value && left > 0 =>
            down(header.parentHash.value, left - 1)
          case _ => false
      anchor.number > tip.number.value && down(anchor.hash, ExtendsSearch)
    new AheadOfHeld(chain.iterator.map(_.hash.value).toSet, standsOnTheTip)

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
    * block its chain leads to. `onCommitted` is told each block once its commit has returned: the caller re-derives
    * their weights (B-490).
    */
  private def commitEach(
      head: ByteString,
      blocks: List[Block],
      sink: Sink,
      asked: Option[() => Boolean],
      onCommitted: Block => Unit
  ): Outcome =
    def stillAsked: Boolean = asked.fold(this.stillAsked(head, sink))(_())
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
                case Left(_) => Outcome.Superseded
                case Right(Right(_)) =>
                  onCommitted(block)
                  adopting = adopting.map(_.copy(current = block.header.number.value))
                  StateMetrics.reexecuted(1)
                  next(rest, committed + 1)
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

  /** '''Re-derives the weights of the blocks an adoption or a catch-up made canonical''' (B-490;
    * [[AdoptedChainWeights.rederive]]): those the move up to `held` made canonical kept the weights derived when they
    * were executed, and each block committed in place took its parent's as read before the commit's lock. A
    * chain-weight repair (`ChainWeightReconcile`) that rewrote a parent's weight in between, and whose final step
    * checked the index before they were canonical, would leave them the old one under its marker. Run once the move or
    * the commits have returned, or one of them has thrown (B-510: a block committed before an exception is canonical
    * all the same), outside the hold of the records: the chain insert lock (shared) is taken after them, never inside.
    */
  private def rederivingAfter[A](madeCanonical: => Seq[BlockHash])(body: => A): A =
    AdoptedChainWeights.rederivingAfter(madeCanonical, reader, writer, WeightsBy)(body)

  /** What a move of the head up to `held` makes canonical ([[AdoptedChainWeights.notCanonicalBelow]]): nothing when the
    * head is on it already.
    */
  private def notCanonicalBelow(held: BlockHeader): Seq[BlockHash] =
    if state.isApplied(held.hash.value) then Nil else AdoptedChainWeights.notCanonicalBelow(held, reader, WeightsBy)

  private val WeightsBy = "[STATE-RECOVERY] A commit in place (B-490)"

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
    *
    * '''Only from `leftOn`''', when it is given: the block the adoption's own writes left the head on (B-464). A fork
    * choice moves the head while an adoption is open, and records the head it followed (`followedHead`) only once its
    * move has returned. One to another head that moved the head after the branch failed, and has not recorded it yet,
    * has the head where the consensus client is about to be told it is: moved back to the head followed before, the
    * head would leave it until the next fork choice. `stillAsked` does not close that: the fork choice may name its
    * head right after it is read. So the move back is refused when its plan finds the head elsewhere: the plan is made
    * under the lock that keeps other moves out, and no fork choice moves the head between that look and the move's
    * write. The head is then left where the fork choice put it.
    */
  private def moveBack(sink: Sink, from: Option[BlockRef], leftOn: Option[ByteString] = None): Unit =
    val targets = (sink.followedHead.toList ++ from.map(_.hash).toList).distinct
    // the block the head was found on, when it was not `leftOn`
    val movedTo = new java.util.concurrent.atomic.AtomicReference[Option[BlockRef]](None)
    val stillLeftOn: HeadMovePlan => Option[String] = plan =>
      Option.when(leftOn.exists(_ != plan.anchor.hash)) {
        movedTo.set(Some(plan.anchor))
        s"the head is at ${plan.anchor.show}, where another writer moved it after the branch failed"
      }
    val back = targets.iterator.flatMap(hash => reader.getBlockHeaderByHash(BlockHash(hash))).find { header =>
      movedTo.get.isEmpty && (state.isApplied(header.hash.value) || (
        try
          writer.moveHeadInPlace(header, reader, admit = stillLeftOn)
          true
        catch
          case NonFatal(refused) =>
            if movedTo.get.isEmpty then
              log.warn(s"[STATE-RECOVERY] The head was not moved back to ${refOf(header).show}: ${refused.getMessage}")
            false
      ))
    }
    (back, movedTo.get) match
      case (Some(header), _) => log.info(s"[STATE-RECOVERY] The head is back at ${refOf(header).show}")
      case (None, Some(at)) =>
        log.info(s"[STATE-RECOVERY] The head is not moved back: a fork choice moved it to ${at.show} meanwhile")
      case (None, None) =>
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

  /** How many blocks' bodies a catch-up reads at a time: as many as a move of the head writes in a batch. */
  val CatchUpChunk: Int = 256

  /** How far down a branch's chain the block it leaves the held chain at is looked for, at most. */
  private val ForkSearch = 65_536

  /** How many blocks above an adoption's head a new head of the consensus client's is followed down, at most. */
  private val ExtendsSearch = 1_024

  /** How far an adoption has got: the head it began from, the block the head is at, and the branch's tip. */
  final case class Progress(start: BigInt, current: BigInt, target: BigInt)

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

    /** More blocks that stand on a block that was [[rejected]], known by their headers only: those of a catch-up's
      * chunks that were not read. `latestValid` is the parent of the block that was rejected.
      */
    def rejectedAbove(latestValid: ByteString, above: List[BlockHeader]): Unit = ()

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

  /** [[StateRecovery.aheadOfHeld]]'s check, and whether it refused a move. */
  final private class AheadOfHeld(above: Set[ByteString], standsOnTheTip: BlockRef => Boolean):
    @volatile var met: Boolean = false
    val admit: com.chipprbots.ethereum.db.storage.pathstate.HeadMovePlan => Option[String] = plan =>
      met = above(plan.anchor.hash) || standsOnTheTip(plan.anchor)
      Option.when(met)(s"the head is at ${plan.anchor.show}, on the chain above the block it was to be moved to")

  /** What [[StateRecovery.walk]] found: `held`, the highest block of the head's chain whose state is held, and the
    * blocks above it, oldest first. `viewless` when no view of `held` can be made: it is reached by the records only.
    */
  final private case class Walked(held: BlockHeader, chain: List[BlockHeader], viewless: Boolean)

  sealed private trait Step
  private case object Moved extends Step
  final private case class Done(outcome: Outcome) extends Step

  private def refOf(header: BlockHeader): BlockRef =
    BlockRef(header.hash.value, header.number.value, header.stateRoot.value)

  private def hex(bytes: ByteString): String = Hex.toHexString(bytes.toArray).take(16)
