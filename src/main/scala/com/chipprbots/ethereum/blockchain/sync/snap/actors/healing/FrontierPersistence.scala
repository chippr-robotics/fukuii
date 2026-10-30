package com.chipprbots.ethereum.blockchain.sync.snap.actors.healing

import java.util.concurrent.atomic.AtomicInteger

import org.apache.pekko.util.ByteString

import scala.collection.mutable
import scala.concurrent.ExecutionContext
import scala.concurrent.blocking

import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncMetrics
import com.chipprbots.ethereum.blockchain.sync.snap.StorageScheme
import com.chipprbots.ethereum.blockchain.sync.snap.actors.TrieNodeHealingCoordinator.ActiveRequest
import com.chipprbots.ethereum.blockchain.sync.snap.actors.TrieNodeHealingCoordinator.FlushComplete
import com.chipprbots.ethereum.blockchain.sync.snap.actors.TrieNodeHealingCoordinator.FlushFailed
import com.chipprbots.ethereum.blockchain.sync.snap.actors.TrieNodeHealingCoordinator.HealingEntry
import com.chipprbots.ethereum.blockchain.sync.snap.heal.FlushBuffer
import com.chipprbots.ethereum.blockchain.sync.snap.heal.FlushTicket
import com.chipprbots.ethereum.blockchain.sync.snap.heal.HealTaskQueue

/** The coordinator state `FrontierPersistence` reads and writes (spec 016 C-H1, research.md R14b). The core's behaviour
  * arms and the code C-H2…C-H5 will move touch every one of them too, or rule (d) keeps them in the core
  * (`pendingBackpressure`, the `AtomicInteger` the frontier walk's thread reads; `prunedEnabled` and `healingWriterEc`,
  * derived vals), so the Impl keeps them as fields. `flushInFlight` is the `var` the module writes (spec 022 review
  * forge C1: the asynchronous flush in flight, by its ticket); it also mutates `rawNodeBuffer` and
  * `pendingSubtreeRecords`.
  */
private[actors] trait FrontierPersistenceState:
  def pendingTasks: HealTaskQueue[HealingEntry]
  def activeRequests: mutable.Map[BigInt, ActiveRequest]
  def pendingBackpressure: AtomicInteger
  def crossRootHealCount: Long
  def healAttempts: HealAttemptMap
  def rawNodeBuffer: FlushBuffer
  var flushInFlight: Option[FlushTicket]
  def pendingSubtreeRecords: mutable.Set[ByteString]
  def totalNodesHealed: Int
  def prunedEnabled: Boolean
  def healingWriterEc: ExecutionContext

/** What the frontier-rebuild walk (C-H2) calls in `FrontierPersistence`: the backpressure wait before each emitted
  * batch. A separate trait, so the C-H1 interfaces stay as recorded.
  */
private[actors] trait FrontierDrainApi:
  def awaitFrontierDrain(): Unit

/** What the heal dispatch (C-H4) calls in `FrontierPersistence`: the backlog gauges it refreshes after each throttle
  * update. A separate trait, so the C-H1 interfaces stay as recorded.
  */
private[actors] trait FrontierGaugesApi:
  def emitHealingFrontierGauges(): Unit

/** What the response processing (C-H5) calls in `FrontierPersistence`: the Layer-2 mirror write of newly queued
  * frontier entries and the asynchronous healed-node flush after each served response. A separate trait, so the C-H1
  * interfaces stay as recorded.
  */
private[actors] trait FrontierWriteApi:
  def persistFrontier(entries: Seq[HealingEntry]): Unit
  def flushRawNodesAsync(): Unit

/** TrieNodeHealingCoordinator's frontier persistence and healed-node flush (spec 016 C-H1,
  * coordinator-characterization.md §3.4): the Layer-2 persisted-frontier mirror (persist on enqueue, unpersist after a
  * durable flush, clear on invalidation; CF `g`), the backlog gauges and the frontier walk's backpressure wait, and the
  * batched raw-node flush to the trie store (synchronous at completion, asynchronous on the healing-writer dispatcher
  * otherwise), which writes the staged spec 005 subtree-complete records only after the nodes are durable. The
  * coordinator core keeps the behaviour arms that call it (`frontierPersistenceArms` handles `FlushComplete`).
  */
private[actors] trait FrontierPersistence extends FrontierDrainApi with FrontierGaugesApi with FrontierWriteApi:
  this: FrontierPersistenceState & TrieNodeHealingEnv =>

// --- Layer 2: persisted frontier (sync.snap-sync.healing-frontier-persistence) ---
  // When `healingFrontierStorage` is defined, the outstanding frontier is mirrored to a dedicated RocksDB
  // CF so a restart resumes (O(frontier)) instead of re-walking the full state. Invariant: the persisted
  // set equals `pendingTasks ∪ in-flight ∪ held` (spec 022: a held node is received but not on disk, so a restart must
  // fetch it again) — write on every new enqueue (queueNodes / inline child discovery / pivot reseed), delete on
  // heal-flush (NOT on dispatch, NOT on hold) and when a held node is dropped unwritten (Hash, `unpersistDroppedHeld`),
  // clear on force-complete / pivot-refresh.
  // See docs/design/healing-frontier-scale.md. No-ops when persistence is disabled (the common default).

  /** Persist newly-queued frontier entries (hash -> pathset). Idempotent: re-persisting an existing entry overwrites it
    * identically, so re-queues and resume-loads are harmless.
    */
  def persistFrontier(entries: Seq[HealingEntry]): Unit =
    if frontierPersistenceEnabled then
      stateIngest.healingFrontier.foreach { store =>
        if entries.nonEmpty then store.update(Nil, entries.map(e => e.hash -> e.pathset)).commit()
      }

  /** Delete healed nodes from the persisted frontier. Safe to call from the healing-writer thread (touches only the
    * immutable storage handle + thread-safe RocksDB). Removing an absent key is a no-op.
    */
  private def unpersistFrontier(hashes: Seq[ByteString]): Unit =
    if frontierPersistenceEnabled then
      stateIngest.healingFrontier.foreach { store =>
        if hashes.nonEmpty then store.update(hashes, Nil).commit()
      }

  /** Clear the entire persisted frontier by deleting every outstanding hash. Because the persisted set equals
    * `pendingTasks ∪ in-flight ∪ held`, deleting the pending and in-flight hashes here, and the held ones with
    * [[unpersistDroppedHeld]] where they are dropped, empties the CF without a namespace-wipe primitive. MUST be called
    * BEFORE the in-memory `pendingTasks`/`activeRequests` are cleared.
    */
  def clearPersistedFrontier(): Unit =
    // FR/T013: a same-root HealingPivotRefreshed no longer reaches here — its early guard returns first.
    // Reaching this method therefore always means a genuine invalidation (differing-root refresh or
    // abandonment), so dropping the completeness marker below is always correct.
    if frontierPersistenceEnabled then
      stateIngest.healingFrontier.foreach { store =>
        val outstanding =
          pendingTasks.iterator.map(_.hash).toSeq ++ activeRequests.values.iterator
            .flatMap(_.tasks.iterator.map(_.hash))
        if outstanding.nonEmpty then store.update(outstanding, Nil).commit()
        // The snapshot is no longer valid/complete — drop the marker so a restart re-walks rather than resuming stale.
        store.clearComplete()
      }

  /** Hash scheme: the held nodes `hashes` were dropped unwritten (a re-peg, a force-complete, the orphan guard), so
    * they leave the persisted frontier; nothing else would ever remove their entries (spec 022 review vault V5). A held
    * node keeps its entry while held: it is not on disk, and the entry is what lets a resume fetch it again. Path
    * scheme: its entries are never removed on heal (research R-5); slice 4 retires the CF.
    */
  def unpersistDroppedHeld(hashes: Seq[ByteString]): Unit =
    if storageScheme == StorageScheme.Hash then unpersistFrontier(hashes)

  /** Publish the live healing backlog/in-flight gauges for the Grafana healing-analytics section. */
  def emitHealingFrontierGauges(): Unit =
    pendingBackpressure.set(pendingTasks.size)
    SNAPSyncMetrics.setHealingFrontierPending(pendingTasks.size.toLong)
    SNAPSyncMetrics.setHealingActiveRequests(activeRequests.size.toLong)
    // spec 004 T019/FR-010: refresh the decoupling observability gauges on every pulse (cheap O(1) reads).
    if decoupledHealServeRoot then
      SNAPSyncMetrics.setHealingCrossRootHeals(crossRootHealCount)
      // A running count kept by the map, not a recount of it on every pulse (#1675).
      SNAPSyncMetrics.setHealingUnservableTasks(healAttempts.unservableCount.toLong)

  /** Frontier-emission backpressure, called from the BFS walk thread (healingWriterEc) before each FrontierRebuilt
    * batch. The walk reads the locally-stored trie fast (thousands of nodes/s) while healing drains over the network on
    * a handful of SNAP peers (tens-to-hundreds/s). With no gate, a verification walk that re-discovers a large frontier
    * floods pendingTasks/the actor mailbox until the heap is exhausted (observed live 2026-06-13: OOM at L7 under peer
    * scarcity). This pauses the walk when the backlog reaches the high-water mark and resumes once it drains below the
    * low-water mark. Reads only the AtomicInteger mirror + immutable vals — touches no actor state. Blocking is safe
    * here: healingWriterEc is the blocking healing dispatcher. A hard timeout guarantees the walk can never deadlock if
    * the drain stalls (fail loud, then resume).
    */
  def awaitFrontierDrain(): Unit =
    if pendingBackpressure.get() >= frontierHighWater then
      val startWait = System.currentTimeMillis()
      log.info(
        s"[HEAL-BFS] Backpressure: healing backlog ${pendingBackpressure.get()} >= high-water " +
          s"$frontierHighWater — pausing frontier emission until it drains below $frontierLowWater"
      )
      while pendingBackpressure.get() > frontierLowWater &&
        System.currentTimeMillis() - startWait < frontierBackpressureMaxWaitMs
      do
        // blocking{} lets a ForkJoin-backed EC (e.g. the test injected EC) compensate with a spare
        // thread while parked here. On the production healing-writer-dispatcher (fixed thread pool)
        // this is a no-op, but documents that the sleep is intentionally blocking — consistent with
        // the Await.result(blocking{...}) pattern already used in rebuildFrontierBFS.
        blocking(Thread.sleep(200))
      val waitedMs = System.currentTimeMillis() - startWait
      if pendingBackpressure.get() > frontierLowWater then
        log.warn(
          s"[HEAL-BFS] Backpressure wait hit the " +
            s"${frontierBackpressureMaxWaitMs / 1000}s safety timeout " +
            s"(backlog still ${pendingBackpressure.get()}) — resuming emission to avoid deadlock"
        )
      else log.info(s"[HEAL-BFS] Backpressure released after ${waitedMs}ms — resuming frontier emission")

  /** spec 005 C3b/D2/T009/T012: write the staged heal-side subtree-complete records whose node-bytes were just durably
    * committed by a flush. `toRecord` is the already-computed (on the actor thread) intersection of this flush's hashes
    * with `pendingSubtreeRecords`. This MUST be called ONLY after `mptStorage.persist()` returns (the subtree bytes are
    * durable) so the record-after-persist ordering (D3) holds; a `HealingFrontierStorage.markSubtreeComplete` is an
    * ordinary WAL-ordered `update` (loss ⇒ safe descend). No-op unless `prunedEnabled`.
    */
  private def writeDurableSubtreeRecords(toRecord: Seq[ByteString]): Unit =
    if prunedEnabled && toRecord.nonEmpty then
      stateIngest.healingFrontier.foreach { store =>
        toRecord.foreach(store.markSubtreeComplete)
        log.debug(s"[HEAL-VERIFY-PRUNED] Seeded ${toRecord.size} heal-closed subtree-complete records (post-flush)")
      }

  /** Whether an asynchronous flush is in flight: its ticket is set from its start until its own `FlushComplete` or
    * `FlushFailed`, or until a synchronous flush takes it over.
    */
  def flushing: Boolean = flushInFlight.isDefined

  /** Synchronous flush of the whole buffer, on the actor thread: at completion, a re-peg and a force-complete. True
    * when the buffer is empty afterwards. The buffer is cleared only once the write has returned (#1549, spec 022
    * T026); a throw is logged and leaves the buffer and the staged records as they were, for the next flush. A
    * completion gate must not declare completion on `false`.
    *
    * '''One writer (spec 022 review vault V1, hunter W4).''' Every healed-node write holds the store's lock
    * (`mptStorage`: the one instance every incarnation of this coordinator writes through). An asynchronous flush in
    * flight is superseded first: if it is still queued it never writes, and if it is writing this waits for it, then
    * releases what it wrote instead of writing it again. Afterwards no asynchronous flush is in flight, whether this
    * write succeeded or not.
    */
  def flushRawNodesSync(): Boolean =
    flushInFlight.foreach(_.supersede())
    val store = mptStorage
    store.synchronized {
      flushInFlight.filter(_.written).foreach(releaseWritten)
      flushInFlight = None
      if rawNodeBuffer.isEmpty then true
      else
        val flushed = rawNodeBuffer.snapshot
        // spec 005 T012: the staged records this flush makes durable, written only AFTER persist.
        val toRecord = flushed.iterator.map(_._1).filter(pendingSubtreeRecords.contains).toSeq
        val written =
          try
            store.storeRawNodes(flushed)
            store.persist()
            true
          catch
            case scala.util.control.NonFatal(e) =>
              log.error(s"Healed-node flush of ${flushed.size} node(s) failed — kept for the next flush", e)
              false
        if written then
          toRecord.foreach(pendingSubtreeRecords.remove)
          writeDurableSubtreeRecords(toRecord) // post-persist (D3) — bytes durable before the record
          unpersistFrontier(flushed.map(_._1)) // Layer 2: healed nodes leave the persisted frontier
          rawNodeBuffer.clear()
          log.info(s"Flushed ${flushed.size} healed nodes to disk (total: $totalNodesHealed)")
        written
    }

  /** Async flush on the dedicated `healing-writer-dispatcher`, so the blocking RocksDB write doesn't compete with sync
    * actors on `sync-dispatcher`. It writes a snapshot of the buffer and does NOT clear it: the nodes leave the buffer
    * only when this flush's own `FlushComplete` reports the write returned ([[flushCompleted]]), and a throw sends
    * `FlushFailed`, which ends the flush and keeps them ([[flushFailed]]) (#1549, spec 022 T026). Every node in the
    * buffer was committed with its whole subtree (spec 022 D3), so the snapshot, a prefix of the buffer, is complete.
    * The write holds the store's lock and skips itself once superseded (see [[flushRawNodesSync]] and [[FlushTicket]]).
    */
  def flushRawNodesAsync(): Unit =
    if rawNodeBuffer.nonEmpty && !flushing then
      val ticket = FlushTicket(rawNodeBuffer.snapshot)
      flushInFlight = Some(ticket)
      val nodes = ticket.snapshot
      // spec 005 T012: the staged records this flush will make durable, read on the ACTOR thread (pendingSubtreeRecords
      // is actor state), written on the worker thread AFTER persist, and unstaged only on FlushComplete.
      val toRecord = nodes.iterator.map(_._1).filter(pendingSubtreeRecords.contains).toSeq
      import scala.concurrent.{Future, blocking}
      val selfRef = self
      val ec = healingWriterEc
      val store = mptStorage
      Future {
        blocking {
          store.synchronized {
            if ticket.superseded then false
            else
              store.storeRawNodes(nodes)
              store.persist()
              writeDurableSubtreeRecords(toRecord) // post-persist (D3); CF g writes are DB-lock-safe off-thread
              unpersistFrontier(nodes.map(_._1)) // Layer 2: healed nodes leave the persisted frontier (post-durable)
              ticket.markWritten()
              true
          }
        }
      }(ec).onComplete {
        case scala.util.Success(true)  => selfRef ! FlushComplete(ticket.token, nodes.size)
        case scala.util.Success(false) => () // superseded: the synchronous flush that took it over wrote these nodes
        case scala.util.Failure(e)     => selfRef ! FlushFailed(ticket.token, e)
      }(ec)

  /** The asynchronous flush `token` wrote its snapshot: release those nodes and unstage their records. Only the flush
    * in flight counts. A completion of any other (one a synchronous flush took over, or one a previous incarnation of
    * this coordinator started before a supervisor restart) changes nothing (spec 022 review forge C1).
    */
  def flushCompleted(token: Long, count: Int): Unit =
    flushInFlight match
      case Some(ticket) if ticket.token == token =>
        flushInFlight = None
        releaseWritten(ticket)
      case _ =>
        log.debug(s"Ignoring the completion of healed-node flush $token ($count node(s)): not the flush in flight")

  /** The asynchronous flush `token` threw: nothing is released, the flush in flight ends if it is that one, and the
    * next flush writes the same nodes again. Another flush's failure (a previous incarnation's) changes nothing.
    */
  def flushFailed(token: Long, cause: Throwable): Unit =
    flushInFlight match
      case Some(ticket) if ticket.token == token =>
        log.error(s"Healed-node flush failed — ${rawNodeBuffer.size} node(s) kept for the next flush", cause)
        flushInFlight = None
      case _ =>
        log.error(s"Healed-node flush $token failed; it is not the flush in flight, so nothing here changes", cause)

  /** `ticket`'s snapshot is durable: drop exactly those entries from the head of the buffer (the very objects it took)
    * and unstage their records.
    */
  private def releaseWritten(ticket: FlushTicket): Unit =
    ticket.snapshot.foreach(n => pendingSubtreeRecords.remove(n._1))
    val dropped = rawNodeBuffer.dropWritten(ticket.snapshot)
    if dropped != ticket.snapshot.size then
      log.error(
        s"[HEAL-FLUSH] Flush ${ticket.token} wrote ${ticket.snapshot.size} node(s), but only the first $dropped are " +
          "still at the head of the buffer: the rest stay buffered and are written again (a bookkeeping fault)"
      )
