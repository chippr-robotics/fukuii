package com.chipprbots.ethereum.blockchain.sync.snap.heal

import org.apache.pekko.util.ByteString

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

import scala.annotation.tailrec
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.Failure
import scala.util.Success
import scala.util.Try

import com.chipprbots.ethereum.blockchain.sync.snap.GatedTaskFileReplay
import com.chipprbots.ethereum.blockchain.sync.snap.StorageTaskFile
import com.chipprbots.ethereum.db.storage.ExcludedAccount
import com.chipprbots.ethereum.db.storage.ExcludedReason
import com.chipprbots.ethereum.db.storage.SnapStorageDoneStorage
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie

/** Spec 022 review hunter C1: the phase's durable list of what it owes, read back when memory cannot say.
  *
  * The excluded set is otherwise written from the coordinator's memory, and a supervisor restart empties that: the
  * tasks it lost have a committed account leaf, incomplete storage (or missing code) and no entry. The storage task
  * file holds every contract of this cycle, and the done markers survive until the phase's clean end, so the accounts
  * left incomplete are the file's entries with no marker. That also covers the part of a recovery stream a
  * force-complete cut short. The code-hash file holds every code hash, so the code left missing is what the code store
  * does not have.
  *
  * A marker not yet written, or an account finished while this runs, only adds an entry that the first look at the root
  * removes.
  *
  * Runs off the actor: the files can hold millions of entries. Reads a chunk at a time, and writes (with its own fsync)
  * only a chunk that found something, so it holds a chunk in memory and costs few fsyncs when little is missing. It
  * stops at the next chunk once cancelled; a chunk under way still lands, which at worst adds entries the first look
  * removes.
  */
object ExcludedTaskFile:

  enum Outcome:
    /** The file was read to its end and all it found is durable. */
    case Recorded(entries: Long)

    /** The file is missing, not the size it should be, or could not be read. What was recorded before that stands. */
    case Unreadable(cause: Throwable)

    /** The markers, the code store or the set could not be read or written. Worth trying again. */
    case StoreFailed(cause: Throwable)

    /** Cancelled: whoever started it no longer wants the answer. */
    case Cancelled

  private val ZeroHash: ByteString = ByteString(new Array[Byte](32))
  private val EmptyRoot: ByteString = ByteString(MerklePatriciaTrie.EmptyRootHash)

  /** `expectedCount`: the entries the file was recorded as holding, if it was. */
  def recordUnfinished(
      path: Path,
      expectedCount: Option[Long],
      done: SnapStorageDoneStorage,
      record: Seq[ExcludedAccount] => Unit,
      cancelled: () => Boolean = () => false,
      chunkEntries: Int = GatedTaskFileReplay.DefaultChunkEntries
  ): Outcome =
    sweep(path, StorageTaskFile.EntrySize, expectedCount, chunkEntries, cancelled) { entries =>
      val tasks = entries.flatMap { entry =>
        val account = ByteString(java.util.Arrays.copyOfRange(entry, 0, 32))
        val root = ByteString(java.util.Arrays.copyOfRange(entry, 32, 64))
        Option.when(account != ZeroHash && root != EmptyRoot)(
          ExcludedAccount(account, ExcludedReason.StorageAbandoned, claimedStorageRoot = root)
        )
      }
      val unfinished = done.unfinished(tasks)(t => (t.subject, t.claimedStorageRoot))
      if unfinished.nonEmpty then record(unfinished)
      unfinished.size
    }

  /** As [[recordUnfinished]], over the code-hash file: what the code store does not hold. */
  def recordMissingCode(
      path: Path,
      expectedCount: Option[Long],
      codePresent: ByteString => Boolean,
      record: Seq[ExcludedAccount] => Unit,
      cancelled: () => Boolean = () => false,
      chunkEntries: Int = GatedTaskFileReplay.DefaultChunkEntries
  ): Outcome =
    sweep(path, StorageTaskFile.CodeHashEntrySize, expectedCount, chunkEntries, cancelled) { entries =>
      val missing = entries
        .map(ByteString(_))
        .filterNot(hash => hash == Account.EmptyCodeHash.value || codePresent(hash))
        .map(ExcludedAccount(_, ExcludedReason.CodeMissing))
      if missing.nonEmpty then record(missing)
      missing.size
    }

  private def sweep(
      path: Path,
      entrySize: Int,
      expectedCount: Option[Long],
      chunkEntries: Int,
      cancelled: () => Boolean
  )(
      recordChunk: Vector[Array[Byte]] => Int
  ): Outcome =
    if !StorageTaskFile.isUsable(path, entrySize, expectedCount) then
      Outcome.Unreadable(
        new java.io.IOException(
          s"$path is missing, or is not ${expectedCount.fold("whole entries")(n => s"$n entries")}"
        )
      )
    else
      Try(Files.size(path) / entrySize) match
        case Failure(e) => Outcome.Unreadable(e)
        case Success(endEntry) =>
          val replay = new GatedTaskFileReplay(path, entrySize, endEntry, chunkEntries, gate = () => None)

          @tailrec
          def loop(total: Long): Outcome =
            if cancelled() then Outcome.Cancelled
            else if replay.done then Outcome.Recorded(total)
            else
              var chunk = Vector.empty[Array[Byte]]
              Try(replay.step(chunk = _)) match
                case Failure(e) => Outcome.Unreadable(e)
                case Success(_) =>
                  Try(recordChunk(chunk)) match
                    case Failure(e) => Outcome.StoreFailed(e)
                    case Success(n) => loop(total + n)

          loop(0L)

/** One coordinator's running sweep: its token, so the end of one a replaced incarnation started matches nothing, and
  * its cancel flag. Actor-confined; `start`'s work runs on `ec`, and reports through `report`.
  */
final class ExcludedSweep:
  private var current: Option[(Long, AtomicBoolean)] = None

  def running: Boolean = current.isDefined

  /** Start `work`, which gets the cancel flag's read. Returns its token. An executor that refuses it reports a failure.
    */
  def start(ec: ExecutionContext)(work: (() => Boolean) => ExcludedTaskFile.Outcome)(
      report: (Long, ExcludedTaskFile.Outcome) => Unit
  ): Long =
    val token = ExcludedSweep.nextToken.incrementAndGet()
    val cancelled = new AtomicBoolean(false)
    current = Some((token, cancelled))
    Future(work(() => cancelled.get()))(ec).onComplete {
      case Success(outcome) => report(token, outcome)
      case Failure(e)       => report(token, ExcludedTaskFile.Outcome.StoreFailed(e))
    }(ExecutionContext.parasitic)
    token

  /** The sweep `token` has ended or timed out. True if it is the one running, which is then told to stop. */
  def finish(token: Long): Boolean =
    if current.exists(_._1 == token) then
      cancel()
      current = None
      true
    else false

  /** Whoever started it no longer wants the answer: it stops at its next chunk. */
  def cancel(): Unit = current.foreach(_._2.set(true))

object ExcludedSweep:
  private val nextToken = new AtomicLong(0L)
