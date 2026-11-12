package com.chipprbots.ethereum.db.storage.pathstate

import org.apache.pekko.util.ByteString

import scala.collection.mutable

/** The stretch of one thread's work in which a caller reads the state of blocks '''behind''' the one the disk holds
  * (spec 018 Track C, C4a part 4).
  *
  * Such a state is made from the changeset records, and what is made lives as long as anything reads through it: a
  * storage, a world built on it. Nothing in a storage says when its reader is done, so the reader says it: it reads
  * inside [[run]], and when `run` returns it holds nothing any more. That is what lets the store count the reads
  * behind the head that are in flight, and refuse one more ([[PathStateStorage.readOnlyInScope]]).
  *
  * The scope is the calling thread's. Everything that reads through what was handed out inside `run` must be done
  * before `run` returns, on that thread: a world or a storage that is kept for later, or handed to another thread,
  * reads through a state the store no longer counts. A caller that reads outside any scope is given the state of the
  * block the disk holds and of no other, which is what every caller was given before there were views behind it.
  */
object ReadScope:

  /** What one scope holds: for each store and block it was handed a state of, how that is given back. */
  final class Scope private[ReadScope] ():
    private val held = mutable.LinkedHashMap.empty[(AnyRef, ByteString), () => Unit]

    /** Whether this scope holds a leave of `pool` for `block`: the one it has, or one `acquire` gives it now.
      * `acquire` returns how the leave is given back, or `None` when there is none to have.
      */
    private[pathstate] def leave(pool: AnyRef, block: ByteString)(acquire: => Option[() => Unit]): Boolean =
      held.contains((pool, block)) || acquire.exists { giveBack =>
        held.update((pool, block), giveBack)
        true
      }

    private[ReadScope] def end(): Unit =
      // every one of them, whatever one of them throws
      val failures = held.values.toList.flatMap { giveBack =>
        try
          giveBack()
          None
        catch case e: Throwable => Some(e)
      }
      held.clear()
      failures.headOption.foreach(throw _)

  private val current = new ThreadLocal[Scope]

  /** `body`, as one scope of reads: what it was handed of blocks behind the head is given back when it returns or
    * throws. Inside another scope of the same thread it is part of that one, which ends when the outer `run` does.
    */
  def run[A](body: => A): A =
    if current.get != null then body
    else
      val scope = new Scope
      current.set(scope)
      try body
      finally
        current.remove()
        scope.end()

  /** The scope the calling thread reads in, if it is in one. */
  private[pathstate] def ambient: Option[Scope] = Option(current.get)

  /** Whether the calling thread is inside a scope: for a test, or an assertion. */
  def isOpen: Boolean = current.get != null
