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

/** What `newPayload` met where missing state kept it from executing a block (spec 022 FR-014, #1694). It stores the
  * block by hash and answers ACCEPTED, and nothing on the Engine path fetches the state or runs the block again.
  * Regular sync's importer does both once it reaches that block, which took 12.8 and 24.5 s at Ephemery's head
  * (2026-11-01). So this is the first sign of a hole, ahead of import's on-demand counter, and holes that outlast it
  * are the ones to look at.
  *
  * Counts each distinct hole once, with what it is and where, and logs it. The consensus client did not send a stalled
  * payload again in those runs, but a block can be met twice, so the same hole logs again only once per `logEvery`,
  * with how many times it was hit since. It reads nothing and changes nothing: the answer to the consensus client is
  * decided by the caller, so `report` never throws. One call site is inside a `catch`, where a throw from here would
  * leave `newPayload` with no answer at all (forge, beacon).
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

  def report(blockNumber: BigInt, error: MPTException, parentStateRoot: Option[ByteString] = None): Unit =
    try record(blockNumber, error, parentStateRoot)
    catch
      case NonFatal(e) =>
        Try(log(s"[STATE-HOLE] newPayload #$blockNumber: could not record a hole: $e")): Unit

  private def record(blockNumber: BigInt, error: MPTException, parentStateRoot: Option[ByteString]): Unit =
    synchronized {
      val at = now()
      val key = error match
        case e: MissingNodeException => e.hash
        case e                       => ByteString(e.getMessage)
      seen.remove(key) match
        case None =>
          // Remembered and logged first: a counter that fails must not cost the line with the position, or repeat each slot.
          seen.put(key, Seen(at, 0L))
          if seen.size > remembered then seen.remove(seen.head._1)
          log(s"[STATE-HOLE] newPayload #$blockNumber not executed: ${describe(error, parentStateRoot)}")
          count(kindOf(error, parentStateRoot))
        case Some(entry) =>
          entry.hitsSinceLog += 1
          if at - entry.lastLoggedMs >= logEvery.toMillis then
            log(
              s"[STATE-HOLE] newPayload #$blockNumber still not executed, hit ${entry.hitsSinceLog} more time(s) since " +
                s"the last line: ${describe(error, parentStateRoot)}"
            )
            entry.lastLoggedMs = at
            entry.hitsSinceLog = 0L
          seen.put(key, entry)
      ()
    }

object EngineStateHoles:
  val DefaultLogEvery: FiniteDuration = 10.minutes
  val DefaultRemembered: Int = 1024

  /** The store throws `MissingRootNodeException` for any node it does not hold, as on a write's path, so that type does
    * not say the root is what is missing. The parent's root does.
    */
  private def kindOf(error: MPTException, parentStateRoot: Option[ByteString]): String = error match
    case _: MissingStorageNodeException                                  => "storage"
    case _: MissingAccountNodeException                                  => "account"
    case e: MissingRootNodeException if parentStateRoot.contains(e.hash) => "root"
    case _: MissingCodeException                                         => "code"
    case _: MissingNodeException                                         => "node"
    case _                                                               => "other"

  /** The hash, and the position as far as the exception knows it: a location, and the account above it. */
  private def describe(error: MPTException, parentStateRoot: Option[ByteString]): String =
    def hex(b: ByteString): String = Hex.toHexString(b.toArray)
    val account = error match
      case e: MissingStorageNodeException => s" account=${hex(e.accountAddress)}"
      case e: MissingAccountNodeException => s" account=${hex(e.accountAddress)}"
      case e: MissingCodeException        => s" account=${hex(e.accountAddress)}"
      case _                              => ""
    error match
      case e: MissingNodeException =>
        s"kind=${kindOf(e, parentStateRoot)} hash=${hex(e.hash)}$account position=${e.location.fold("unknown")(hex)}"
      case e => s"kind=${kindOf(e, parentStateRoot)} ${e.getMessage}"
