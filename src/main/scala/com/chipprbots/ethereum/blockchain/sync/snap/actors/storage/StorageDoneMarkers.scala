package com.chipprbots.ethereum.blockchain.sync.snap.actors.storage

import org.apache.pekko.util.ByteString

import scala.collection.mutable
import scala.concurrent.ExecutionContext

import com.chipprbots.ethereum.blockchain.sync.snap.StorageTask
import com.chipprbots.ethereum.blockchain.sync.snap.actors.*
import com.chipprbots.ethereum.blockchain.sync.snap.actors.StorageRangeCoordinator.ByteStringOrdering
import com.chipprbots.ethereum.blockchain.sync.snap.actors.StorageRangeCoordinator.FlatBatchFlushComplete
import com.chipprbots.ethereum.blockchain.sync.snap.actors.StorageRangeCoordinator.FlatBatchFlushFailed
import com.chipprbots.ethereum.blockchain.sync.snap.actors.StorageRangeCoordinator.FlatSlotBatch as DataSourceBatchUpdate
import com.chipprbots.ethereum.db.storage.SnapStorageDoneStorage

/** The coordinator state `StorageDoneMarkers` reads and writes (spec 016 C-S3, research.md R14b). The core's behaviour
  * arms (the flush-complete, flush-failed and periodic-flush arms, `onPostStop`, `isComplete`, progress) or the peer
  * dispatch touch every one of them too, or rule (d) keeps them in the core (`pendingFlatBatchAccounts`,
  * `pendingDoneMarkers`, `flatBatchesDoneAhead`, and the immutable `storageDoneStorage` and `flatBatchEc`), so the Impl
  * keeps them as fields. The four `var`s are the ones the module writes; `doneMarkersDisabled` is set only by the
  * core's flush-failed arm.
  */
private[actors] trait StorageDoneMarkersState:
  def stateRoot: ByteString
  var completedTaskCount: Long
  def pendingFlatBatchAccounts: mutable.ArrayBuffer[(ByteString, Seq[(ByteString, ByteString)])]
  var pendingFlatBatchEntries: Int
  var inFlightFlatBatches: Int
  def storageDoneStorage: Option[SnapStorageDoneStorage]
  def pendingDoneMarkers: mutable.ArrayBuffer[(ByteString, ByteString, Long)]
  def doneMarkersDisabled: Boolean
  var flatBatchSeq: Long
  def flatBatchesDoneAhead: mutable.Set[Long]
  def flatBatchEc: ExecutionContext

/** Storage-range flat-slot writes and completion markers (spec 016 C-S3, coordinator-characterization.md §3.3): the
  * flat-slot accumulator (`stageFlatSlotChunk`) and its off-actor batch commit on the storage-writer dispatcher
  * (`flushPendingFlatBatch`), the per-account completion markers that ride in those batches (`stageDoneMarker`,
  * `takeDoneMarkersFor`, `recordFlatBatchDone`; spec 010 R2a, the crash-consistency rule documented at
  * `storageDoneStorage` in the core) and the completed-task count. The coordinator core keeps the behaviour arms that
  * call it (flush complete and failed, the periodic marker flush, completion and pivot refresh) and `onPostStop`'s
  * final synchronous flush.
  */
private[actors] trait StorageDoneMarkers:
  this: StorageRangeCoordinatorImpl =>

  /** Every flat batch with a sequence number <= this has committed. Package-private for tests. */
  private[actors] var flatBatchesDoneThrough: Long = 0L

  def recordFlatBatchDone(seq: Long): Unit =
    if seq > flatBatchesDoneThrough then
      flatBatchesDoneAhead += seq
      while flatBatchesDoneAhead.remove(flatBatchesDoneThrough + 1) do flatBatchesDoneThrough += 1

  /** Stage the completion marker of an account whose data was just fully handed to storage. */
  def stageDoneMarker(accountHash: ByteString, storageRoot: ByteString): Unit =
    if storageDoneStorage.isDefined && !doneMarkersDisabled then
      pendingDoneMarkers += ((accountHash, storageRoot, flatBatchSeq + 1))

  /** Remove and return the staged markers that may ride in batch `batchSeq` (see the rule above). */
  def takeDoneMarkersFor(batchSeq: Long): Seq[(ByteString, ByteString)] =
    if storageDoneStorage.isEmpty || doneMarkersDisabled || pendingDoneMarkers.isEmpty then Seq.empty
    else
      val (ride, wait) = pendingDoneMarkers.partition { case (_, _, lastSlotBatch) =>
        flatBatchesDoneThrough >= lastSlotBatch ||
        (batchSeq == lastSlotBatch && flatBatchesDoneThrough >= lastSlotBatch - 1)
      }
      pendingDoneMarkers.clear()
      pendingDoneMarkers ++= wait
      ride.map { case (account, root, _) => (account, root) }.toList

  /** Append a per-response chunk of verified slots to the flat-slot accumulator. The streaming storage-trie path
    * commits the trie incrementally inside `SnapHashTrie`; flat-slot writes remain batched (sorted within each chunk)
    * and are flushed to RocksDB off-actor once the accumulator hits `flatBatchEntryThreshold` or the actor stops.
    */
  def stageFlatSlotChunk(
      accountHash: ByteString,
      slots: Seq[(ByteString, ByteString)]
  ): Unit =
    if slots.nonEmpty then
      val sorted = slots.sortBy(_._1)(ByteStringOrdering)
      pendingFlatBatchAccounts += ((accountHash, sorted))
      pendingFlatBatchEntries += sorted.size
      if pendingFlatBatchEntries >= flatBatchEntryThreshold then flushPendingFlatBatch()

  /** Hand the current accumulator off to the storage-writer dispatcher and reset it. The Future builds the combined
    * `DataSourceBatchUpdate` and commits it in one RocksDB write batch, then notifies the actor with
    * `FlatBatchFlushComplete` (or `FlatBatchFlushFailed`). The completion message carries `forStateRoot` so the actor
    * can drop bookkeeping for batches that pre-date a pivot refresh.
    */
  def flushPendingFlatBatch(): Unit =
    // A completion marker that rides in this batch vouches for its account's trie nodes: write them first.
    flushTrieNodeWrites()
    val batchSeq = flatBatchSeq + 1
    val doneMarkers = takeDoneMarkersFor(batchSeq)
    if pendingFlatBatchAccounts.nonEmpty || doneMarkers.nonEmpty then
      val batchAccounts = pendingFlatBatchAccounts.toList // immutable snapshot
      val entries = pendingFlatBatchEntries
      val forStateRoot = stateRoot
      pendingFlatBatchAccounts.clear()
      pendingFlatBatchEntries = 0
      inFlightFlatBatches += 1
      flatBatchSeq = batchSeq

      val selfRef = self
      val storage = flatSlotStorage // capture for Future
      val doneStorage = storageDoneStorage
      val ec = flatBatchEc
      import scala.concurrent.{Future, blocking}
      Future {
        blocking {
          val startMs = System.currentTimeMillis()
          var combined: DataSourceBatchUpdate = storage.emptyBatchUpdate
          batchAccounts.foreach { case (accountHash, slots) =>
            combined = combined.and(storage.putSlotsBatch(accountHash, slots))
          }
          // Same WriteBatch as the slots: the markers commit atomically with them, never before.
          if doneMarkers.nonEmpty then doneStorage.foreach(d => combined = combined.and(d.markDone(doneMarkers)))
          combined.commit()
          System.currentTimeMillis() - startMs
        }
      }(ec).onComplete {
        case scala.util.Success(elapsedMs) =>
          selfRef ! FlatBatchFlushComplete(forStateRoot, entries, elapsedMs, batchSeq)
        case scala.util.Failure(e) =>
          selfRef ! FlatBatchFlushFailed(forStateRoot, entries, e.getMessage)
      }(ec)

  /** Aggregate-counter sink for completed StorageTask objects. Previously this appended into an unbounded
    * `mutable.ArrayBuffer[StorageTask]` (one of the leak vectors behind the May 13 sepolia OOM at ~22M completed
    * tasks). All downstream consumers — progress %, SyncStatistics.tasksCompleted, the completion check — only ever
    * read the count, never the task data itself.
    */
  def recordCompletedTask(task: StorageTask): Unit =
    val _ = task // explicitly unused; kept in the signature to make call-site intent unambiguous
    completedTaskCount += 1L
