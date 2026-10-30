package com.chipprbots.ethereum.blockchain.sync.snap.heal

import org.apache.pekko.util.ByteString

import java.nio.file.Files
import java.nio.file.Path

import scala.annotation.tailrec
import scala.util.Failure
import scala.util.Success
import scala.util.Try

import com.chipprbots.ethereum.blockchain.sync.snap.GatedTaskFileReplay
import com.chipprbots.ethereum.blockchain.sync.snap.StorageTaskFile
import com.chipprbots.ethereum.db.storage.ExcludedAccount
import com.chipprbots.ethereum.db.storage.ExcludedReason
import com.chipprbots.ethereum.db.storage.SnapStorageDoneStorage
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie

/** Spec 022 review hunter C1: the storage phase's list of what it owes, read back at a force-complete.
  *
  * The excluded set is otherwise written from the coordinator's memory, and a supervisor restart empties that: the
  * tasks it lost have a committed account leaf, incomplete storage and no entry. The task file holds every contract of
  * this cycle, and a force-complete does not wipe the done markers (R-5a), so the accounts it leaves incomplete are the
  * file's entries with no marker. That also covers the part of a recovery stream the force-complete cut short.
  *
  * A marker not yet written, or an account finished while this runs, only adds an entry that the first look at the root
  * removes.
  *
  * Runs off the actor: the file can hold 12M entries. Reads a chunk at a time, and writes (with its own fsync) only a
  * chunk that has unfinished accounts, so it holds a chunk in memory and costs few fsyncs when most markers are there.
  */
object ExcludedTaskFile:

  enum Outcome:
    /** The file was read to its end and all it found is durable. */
    case Recorded(accounts: Long)

    /** The file could not be read. What was recorded before that stands. */
    case Unreadable(cause: Throwable)

    /** The markers or the set could not be read or written. Worth trying again. */
    case StoreFailed(cause: Throwable)

  private val ZeroHash: ByteString = ByteString(new Array[Byte](32))
  private val EmptyRoot: ByteString = ByteString(MerklePatriciaTrie.EmptyRootHash)

  def recordUnfinished(
      path: Path,
      done: SnapStorageDoneStorage,
      record: Seq[ExcludedAccount] => Unit,
      chunkEntries: Int = GatedTaskFileReplay.DefaultChunkEntries
  ): Outcome =
    def recordChunk(entries: Vector[Array[Byte]]): Int =
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

    Try(Files.size(path) / StorageTaskFile.EntrySize) match
      case Failure(e) => Outcome.Unreadable(e)
      case Success(endEntry) =>
        val replay =
          new GatedTaskFileReplay(path, StorageTaskFile.EntrySize, endEntry, chunkEntries, gate = () => None)

        @tailrec
        def loop(total: Long): Outcome =
          if replay.done then Outcome.Recorded(total)
          else
            var chunk = Vector.empty[Array[Byte]]
            Try(replay.step(chunk = _)) match
              case Failure(e) => Outcome.Unreadable(e)
              case Success(_) =>
                Try(recordChunk(chunk)) match
                  case Failure(e) => Outcome.StoreFailed(e)
                  case Success(n) => loop(total + n)

        loop(0L)
