package com.chipprbots.ethereum.jsonrpc

import cats.effect.IO

import scala.annotation.tailrec

import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.db.storage.pathstate.StaleStateViewException
import com.chipprbots.ethereum.db.storage.pathstate.StateFault
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockchainReader

/** A read of a block's state from behind an RPC (spec 018 Track C, C2b).
  *
  * On the unified layout the state on disk moves under a reader. A view that the disk has moved away from does not
  * answer with another block's node: it says so. The read is then run again, all of it, on a fresh view of the same
  * block, so that nothing built on the stale view (a world, an account already read) outlives it. A state that is not
  * held, or a read that is still stale after [[Attempts]] views, is answered with the error a client already gets for a
  * missing state node.
  *
  * With the in-development guard off neither can be thrown, and a read is `read(reader.stateAt(header))`. A
  * `MissingNodeException` is not handled here: each method keeps the answer it gives for one today.
  */
object StateRead:

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
    @tailrec def attempt(left: Int): Either[JsonRpcError, A] =
      val outcome: Option[Either[JsonRpcError, A]] =
        try Some(Right(read(state())))
        catch
          case _: StaleStateViewException if left > 1 => None
          case fault: StateFault                      => Some(Left(unavailable(fault)))
      outcome match
        case Some(answer) => answer
        case None         => attempt(left - 1)
    attempt(Attempts)

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
        case fault: StateFault                      => IO.pure(Left(unavailable(fault)))
      }
    attempt(Attempts)

  /** What a client is told when the state it asked about cannot be read: the error it already gets for a state node
    * that does not exist. The backstop of the controllers answers a [[StateFault]] that escapes a method with it too.
    */
  def unavailable(@scala.annotation.unused fault: StateFault): JsonRpcError = JsonRpcError.NodeNotFound
