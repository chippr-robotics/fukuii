package com.chipprbots.ethereum.jsonrpc

import cats.effect.IO

import scala.annotation.tailrec

import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.db.storage.pathstate.ReadScope
import com.chipprbots.ethereum.db.storage.pathstate.StaleStateViewException
import com.chipprbots.ethereum.db.storage.pathstate.StateFault
import com.chipprbots.ethereum.db.storage.pathstate.StateUnavailable
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.utils.Logger

/** A read of a block's state from behind an RPC (spec 018 Track C, C2b).
  *
  * On the unified layout the state on disk moves under a reader. A view that the disk has moved away from does not
  * answer with another block's node: it says so. The read is then run again, all of it, on a fresh view of the same
  * block, so that nothing built on the stale view (a world, an account already read) outlives it. A state that is not
  * held, one that is not handed out right now, and a read that is still stale after [[Attempts]] views are each
  * answered with an error that says which ([[unavailable]]).
  *
  * The state of a block '''behind the head''' is made from the changeset records, and is handed out only to a caller
  * that reads inside a scope ([[scoped]]), which is how the node counts and bounds such reads in flight. A read made
  * through [[at]], [[scratchAt]] or [[retrying]] is in one. A method that executes on a state (`eth_call`, a trace)
  * wraps the synchronous stretch in which it takes the state and reads through it: [[reading]]. A method that does
  * neither reads the head's state and no other, as every method did.
  *
  * With the in-development guard off neither can be thrown, and a read is `read(reader.stateAt(header))`. A
  * `MissingNodeException` is not handled here: each method keeps the answer it gives for one today.
  */
object StateRead extends Logger:

  /** How many views of the same block a read is given. */
  val Attempts: Int = 3

  /** `read` on the state of `header`'s block, for a reader that writes nothing. */
  def at[A](reader: BlockchainReader, header: BlockHeader)(read: MptStorage => A): Either[JsonRpcError, A] =
    retrying(() => reader.stateAt(header))(read)

  /** `read` on the state of `header`'s block, for an execution that commits nothing: each attempt gets a scratch state
    * of its own, so what a stale attempt persisted is dropped with it.
    */
  def scratchAt[A](reader: BlockchainReader, header: BlockHeader)(read: MptStorage => A): Either[JsonRpcError, A] =
    retrying(() => reader.scratchStateAt(header))(read)

  /** `read` on `state()`, taken afresh for each attempt. */
  def retrying[A](state: () => MptStorage)(read: MptStorage => A): Either[JsonRpcError, A] =
    attempting(read(state()))

  /** `read`, which takes the state it reads inside itself: in one scope of reads, and run again, all of it, when the
    * state moved under it.
    */
  def attempting[A](read: => A): Either[JsonRpcError, A] =
    @tailrec def attempt(left: Int): Either[JsonRpcError, A] =
      val outcome: Option[Either[JsonRpcError, A]] =
        try Some(Right(scoped(read)))
        catch
          case _: StaleStateViewException if left > 1 => None
          case fault: StateFault                      => Some(Left(unavailable(fault, Attempts - left + 1)))
      outcome match
        case Some(answer) => answer
        case None         => attempt(left - 1)
    attempt(Attempts)

  /** `body`, as one scope of reads of the state: what it is handed of a block behind the head is counted while it runs
    * and given back when it returns or throws. Everything that reads through a state taken inside `body` must be done
    * before it returns, on the calling thread: a world kept for a later stage of an `IO` reads through a state the node
    * no longer counts.
    */
  def scoped[A](body: => A): A = ReadScope.run(body)

  /** `IO { scoped { body } }`: for the synchronous body of a method that takes a block's state and executes on it. */
  def reading[A](body: => A): IO[A] = IO(scoped(body))

  /** A whole request that executes on a block's state and changes nothing but its answer (`eth_call`, a simulation, a
    * trace). It builds a world and holds it across many reads, so no single read can be run again on its own: when the
    * state moves under it the request runs again from the start, and takes a fresh state when it does.
    *
    * Not for a request with an effect: it would have the effect once for each run.
    */
  def request[A](run: IO[Either[JsonRpcError, A]]): IO[Either[JsonRpcError, A]] =
    def attempt(left: Int): IO[Either[JsonRpcError, A]] =
      run.recoverWith {
        case _: StaleStateViewException if left > 1 => attempt(left - 1)
        case fault: StateFault                      => IO(Left(unavailable(fault, Attempts - left + 1)))
      }
    attempt(Attempts)

  /** What a client is told when the state it asked about cannot be read. Three answers, since a client does three
    * different things with them: a state that is not held is not asked for again ([[JsonRpcError.StateNotHeld]]); one
    * that is not handed out right now, and one that moved under every view of it, are ([[JsonRpcError.StateBusy]],
    * [[JsonRpcError.StateMoved]]). None of them is the execution error a client takes for a revert. The backstop of the
    * controllers answers a [[StateFault]] that escapes a method with these too.
    */
  def unavailable(fault: StateFault, attempts: Int = 1): JsonRpcError =
    // the client is told which of the three; why is said here
    log.debug(s"A read of the state is answered as unavailable after $attempts attempt(s): ${fault.getMessage}")
    fault match
      case _: StaleStateViewException                    => JsonRpcError.StateMoved
      case refused: StateUnavailable if refused.askAgain => JsonRpcError.StateBusy
      case _                                             => JsonRpcError.StateNotHeld
