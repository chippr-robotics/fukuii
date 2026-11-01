package com.chipprbots.ethereum.consensus.engine

import org.apache.pekko.util.ByteString

import scala.collection.mutable
import scala.concurrent.duration.*
import scala.util.Try
import scala.util.control.NonFatal

import org.bouncycastle.util.encoders.Hex

import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.MPTException
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.MissingAccountNodeException
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.MissingCodeException
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.MissingNodeException
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.MissingRootNodeException
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.MissingStorageNodeException

/** What `newPayload` hit where it answered SYNCING for missing state (spec 022 FR-014; beacon, 5d). The Engine path has
  * no on-demand repair: import's on-demand counter stays at zero on a post-merge node while a hole holds execution up,
  * and only the background audit fills it. Without this, SC-004 would pass with nothing having been looked at.
  *
  * Counts each distinct hole once, with what it is and where, and logs it. The consensus client retries every slot, so
  * the same hole logs again only once per `logEvery`, with how many times it was hit since. It reads nothing and
  * changes nothing: the answer to the consensus client is decided by the caller, so `report` never throws. One call
  * site is inside a `catch`, where a throw from here would leave `newPayload` with no answer at all (forge, beacon).
  */
final class EngineStateHoles(
    log: String => Unit,
    count: String => Unit = EngineApiMetrics.recordStateHole,
    now: () => Long = () => System.currentTimeMillis(),
    logEvery: FiniteDuration = EngineStateHoles.DefaultLogEvery,
    remembered: Int = EngineStateHoles.DefaultRemembered
):
  import EngineStateHoles.*

  private case class Seen(var lastLoggedMs: Long, var hitsSinceLog: Long)

  // Least recently hit first. Bounded: past it, a hole hit again counts again, at worst.
  private val seen = mutable.LinkedHashMap.empty[ByteString, Seen]

  def report(blockNumber: BigInt, error: MPTException): Unit =
    try record(blockNumber, error)
    catch
      case NonFatal(e) =>
        Try(log(s"[STATE-HOLE] newPayload #$blockNumber: could not record a hole: $e")): Unit

  private def record(blockNumber: BigInt, error: MPTException): Unit = synchronized {
    val at = now()
    val key = error match
      case e: MissingNodeException => e.hash
      case e                       => ByteString(e.getMessage)
    seen.remove(key) match
      case None =>
        // Remembered and logged first: a counter that fails must not cost the line with the position, or repeat each slot.
        seen.put(key, Seen(at, 0L))
        if seen.size > remembered then seen.remove(seen.head._1)
        log(s"[STATE-HOLE] newPayload #$blockNumber answered SYNCING: ${describe(error)}")
        count(kindOf(error))
      case Some(entry) =>
        entry.hitsSinceLog += 1
        if at - entry.lastLoggedMs >= logEvery.toMillis then
          log(
            s"[STATE-HOLE] newPayload #$blockNumber still answers SYNCING, hit ${entry.hitsSinceLog} more time(s) since " +
              s"the last line: ${describe(error)}"
          )
          entry.lastLoggedMs = at
          entry.hitsSinceLog = 0L
        seen.put(key, entry)
    ()
  }

object EngineStateHoles:
  val DefaultLogEvery: FiniteDuration = 10.minutes
  val DefaultRemembered: Int = 1024

  private def kindOf(error: MPTException): String = error match
    case _: MissingStorageNodeException => "storage"
    case _: MissingAccountNodeException => "account"
    case _: MissingRootNodeException    => "root"
    case _: MissingCodeException        => "code"
    case _: MissingNodeException        => "node"
    case _                              => "other"

  /** The hash, and the position as far as the exception knows it: a location, and the account above it. */
  private def describe(error: MPTException): String =
    def hex(b: ByteString): String = Hex.toHexString(b.toArray)
    val account = error match
      case e: MissingStorageNodeException => s" account=${hex(e.accountAddress)}"
      case e: MissingAccountNodeException => s" account=${hex(e.accountAddress)}"
      case e: MissingCodeException        => s" account=${hex(e.accountAddress)}"
      case _                              => ""
    error match
      case e: MissingNodeException =>
        s"kind=${kindOf(e)} hash=${hex(e.hash)}$account position=${e.location.fold("unknown")(hex)}"
      case e => s"kind=${kindOf(e)} ${e.getMessage}"
