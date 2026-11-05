package com.chipprbots.ethereum.blockchain.sync.snap.heal

import org.apache.pekko.util.ByteString

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

import scala.annotation.tailrec
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.*
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
      chunkEntries: Int = GatedTaskFileReplay.DefaultChunkEntries,
      chunkDone: () => Unit = () => ()
  ): Outcome =
    sweep(path, StorageTaskFile.EntrySize, expectedCount, chunkEntries, cancelled, chunkDone) { entries =>
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
      chunkEntries: Int = GatedTaskFileReplay.DefaultChunkEntries,
      chunkDone: () => Unit = () => ()
  ): Outcome =
    sweep(path, StorageTaskFile.CodeHashEntrySize, expectedCount, chunkEntries, cancelled, chunkDone) { entries =>
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
      cancelled: () => Boolean,
      chunkDone: () => Unit
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
                    case Success(n) =>
                      chunkDone()
                      loop(total + n)

          loop(0L)

/** One coordinator's running sweep: its token, so the end of one a replaced incarnation started matches nothing, its
  * cancel flag, and how many chunks it has finished. Actor-confined; `start`'s work runs on `ec`, and reports through
  * `report`.
  */
final class ExcludedSweep:
  import ExcludedSweep.*

  final private class Running(val token: Long, val work: Work):
    var chunksSeen: Long = 0L
    var begunAtLastLook: Boolean = false

  private var current: Option[Running] = None

  /** How the last sweep to finish went, for the log: what its limit (`NoChunkLimit`) has to stand on. */
  private var lastRunSummary: String = "no sweep yet"

  def running: Boolean = current.isDefined

  def lastRun: String = lastRunSummary

  /** Start `work`. Returns its token. An executor that refuses it reports a failure. */
  def start(ec: ExecutionContext)(work: Work => ExcludedTaskFile.Outcome)(
      report: (Long, ExcludedTaskFile.Outcome) => Unit
  ): Long =
    val token = ExcludedSweep.nextToken.incrementAndGet()
    val handle = new Work
    current = Some(new Running(token, handle))
    Future {
      handle.begin()
      work(handle)
    }(using ec).onComplete {
      case Success(outcome) => report(token, outcome)
      case Failure(e)       => report(token, ExcludedTaskFile.Outcome.StoreFailed(e))
    }(using ExecutionContext.parasitic)
    token

  /** A look by the sweep's watchdog: `Some(false)` when `token`, if it is the one running, has been at its work for the
    * whole time since the last look and finished no chunk. A sweep stuck on one chunk is hung however long it has been
    * healthy, and a slow one that keeps finishing chunks is not, however long it takes.
    *
    * The clock starts when the work starts, not when it is queued. A storage sweep shares a one-thread dispatcher with
    * flat-batch writes, so it can wait behind them for longer than a look, and a sweep that has not begun is not hung
    * (beacon F4). Nor is one that began just before this look: it has had no whole look, so that gets no verdict
    * either, which is why a read hung from its first chunk is seen at the second look. A dispatcher that is wedged
    * means this waits for good. That is acceptable: everything else on it has stopped too.
    */
  def chunkFinishedSinceLastLook(token: Long): Option[Boolean] =
    current.filter(_.token == token).map { sweep =>
      val chunks = sweep.work.chunks
      val finished = chunks != sweep.chunksSeen
      sweep.chunksSeen = chunks
      val hadAWholeLook = sweep.begunAtLastLook
      sweep.begunAtLastLook = sweep.work.begun
      finished || !hadAWholeLook
    }

  /** The sweep `token` has ended or is taken as hung. True if it is the one running, which is then told to stop. */
  def finish(token: Long): Boolean =
    current.filter(_.token == token) match
      case Some(sweep) =>
        lastRunSummary = sweep.work.runningNanos.fold("did not begin") { nanos =>
          s"${sweep.work.chunks} chunk(s) in ${nanos / 1_000_000_000L} s"
        }
        cancel()
        current = None
        true
      case None => false

  /** Whoever started it no longer wants the answer: it stops at its next chunk. */
  def cancel(): Unit = current.foreach(_.work.cancel())

object ExcludedSweep:
  private val nextToken = new AtomicLong(0L)

  /** What a sweep's work is handed: read from its own thread, as `chunkDone` is written. */
  final class Work:
    private val cancelFlag = new AtomicBoolean(false)
    private val chunkCount = new AtomicLong(0L)
    private val begunNanos = new AtomicLong(NotBegun)
    val cancelled: () => Boolean = () => cancelFlag.get()
    val chunkDone: () => Unit = () =>
      chunkCount.incrementAndGet(); ()
    private[ExcludedSweep] def begin(): Unit = begunNanos.set(System.nanoTime())
    private[ExcludedSweep] def begun: Boolean = begunNanos.get() != NotBegun
    private[ExcludedSweep] def runningNanos: Option[Long] =
      Option.when(begun)(System.nanoTime() - begunNanos.get())
    private[ExcludedSweep] def cancel(): Unit = cancelFlag.set(true)
    private[ExcludedSweep] def chunks: Long = chunkCount.get()

  private val NotBegun = Long.MinValue

  /** How often a sweep's watchdog looks: a sweep is taken as hung when it was at work for the whole time between two
    * looks and no chunk finished, so a hung read is seen after this long and at most twice it. A wall-clock cap on the
    * whole sweep was wrong: at one `code.get` a hash and about 0.8 ms each (vault W2), Sepolia's 16M code hashes take
    * about 3.5 h. A chunk is 4,096 entries: about 20 ms from a task file on the root SSD (measured, 2M entries in 9 to
    * 12 s) and about 3 s for code hashes, so this leaves a chunk some 200 times the slower of those, which also covers
    * a raidz HDD at 20 ms a read (82 s) and a RocksDB write stall behind the excluded set's synced write. Past that the
    * disk is dead, not slow. The cost of being wrong is one walk and one scan, not a stop. Not measured on this path at
    * mainnet's scale: the end of each sweep logs its chunks and time (`lastRun`).
    */
  val NoChunkLimit: FiniteDuration = 10.minutes

  /** How long a restarted coordinator waits for its file's pointer at a clean end. The pointer is written on the
    * controller's thread just after the account phase's end reaches the coordinator, so this only has to outlast the
    * controller's mailbox. It is the two minutes that four 30-second pulses were meant to be.
    */
  val PointerWaitLimit: FiniteDuration = 2.minutes
