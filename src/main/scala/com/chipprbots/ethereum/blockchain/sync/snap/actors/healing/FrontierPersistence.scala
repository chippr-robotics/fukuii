package com.chipprbots.ethereum.blockchain.sync.snap.actors.healing

import java.util.concurrent.atomic.AtomicInteger

import org.apache.pekko.util.ByteString

import scala.collection.mutable
import scala.concurrent.ExecutionContext
import scala.concurrent.blocking

import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncMetrics
import com.chipprbots.ethereum.blockchain.sync.snap.actors.TrieNodeHealingCoordinator.ActiveRequest
import com.chipprbots.ethereum.blockchain.sync.snap.actors.TrieNodeHealingCoordinator.FlushComplete
import com.chipprbots.ethereum.blockchain.sync.snap.actors.TrieNodeHealingCoordinator.HealingEntry

/** The coordinator state `FrontierPersistence` reads and writes (spec 016 C-H1, research.md R14b). The core's behaviour
  * arms and the code C-H2…C-H5 will move touch every one of them too, or rule (d) keeps them in the core
  * (`pendingBackpressure`, the `AtomicInteger` the frontier walk's thread reads; `prunedEnabled` and `healingWriterEc`,
  * derived vals), so the Impl keeps them as fields. `flushing` is the one `var` the module writes; it also mutates
  * `rawNodeBuffer` and `pendingSubtreeRecords`.
  */
private[actors] trait FrontierPersistenceState:
  def pendingTasks: mutable.ArrayDeque[HealingEntry]
  def activeRequests: mutable.Map[BigInt, ActiveRequest]
  def pendingBackpressure: AtomicInteger
  def crossRootHealCount: Long
  def healAttempts: mutable.Map[ByteString, Int]
  def rawNodeBuffer: mutable.ArrayBuffer[(ByteString, Array[Byte])]
  var flushing: Boolean
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

/** TrieNodeHealingCoordinator's frontier persistence and healed-node flush (spec 016 C-H1,
  * coordinator-characterization.md §3.4): the Layer-2 persisted-frontier mirror (persist on enqueue, unpersist after a
  * durable flush, clear on invalidation; CF `g`), the backlog gauges and the frontier walk's backpressure wait, and the
  * batched raw-node flush to the trie store (synchronous at completion, asynchronous on the healing-writer dispatcher
  * otherwise), which writes the staged spec 005 subtree-complete records only after the nodes are durable. The
  * coordinator core keeps the behaviour arms that call it (`frontierPersistenceArms` handles `FlushComplete`).
  */
private[actors] trait FrontierPersistence extends FrontierDrainApi with FrontierGaugesApi:
  this: FrontierPersistenceState & TrieNodeHealingEnv =>

// --- Layer 2: persisted frontier (sync.snap-sync.healing-frontier-persistence) ---
  // When `healingFrontierStorage` is defined, the outstanding frontier is mirrored to a dedicated RocksDB
  // CF so a restart resumes (O(frontier)) instead of re-walking the full state. Invariant: the persisted
  // set equals `pendingTasks ∪ in-flight` — write on every new enqueue (queueNodes / inline child discovery
  // / pivot reseed), delete on heal-flush (NOT on dispatch), clear on force-complete / pivot-refresh.
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
    * `pendingTasks ∪ in-flight`, deleting those hashes empties the CF without a namespace-wipe primitive. MUST be
    * called BEFORE the in-memory `pendingTasks`/`activeRequests` are cleared.
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

  /** Publish the live healing backlog/in-flight gauges for the Grafana healing-analytics section. */
  def emitHealingFrontierGauges(): Unit =
    pendingBackpressure.set(pendingTasks.size)
    SNAPSyncMetrics.setHealingFrontierPending(pendingTasks.size.toLong)
    SNAPSyncMetrics.setHealingActiveRequests(activeRequests.size.toLong)
    // spec 004 T019/FR-010: refresh the decoupling observability gauges on every pulse (cheap O(1) reads).
    if decoupledHealServeRoot then
      SNAPSyncMetrics.setHealingCrossRootHeals(crossRootHealCount)
      SNAPSyncMetrics.setHealingUnservableTasks(
        healAttempts.count { case (_, n) => n > decoupledHealMaxAttemptsNoRefresh }.toLong
      )

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

  /** Synchronous flush — used only for final completion flush (small buffer, safe to block). */
  def flushRawNodesSync(): Unit =
    if rawNodeBuffer.nonEmpty then
      val flushed = rawNodeBuffer.toSeq
      // spec 005 T012: compute the staged records this flush makes durable BEFORE persist, write them AFTER.
      val toRecord = flushed.iterator.map(_._1).filter(pendingSubtreeRecords.contains).toSeq
      toRecord.foreach(pendingSubtreeRecords.remove)
      mptStorage.storeRawNodes(flushed)
      mptStorage.persist()
      writeDurableSubtreeRecords(toRecord) // post-persist (D3) — bytes durable before the record
      unpersistFrontier(flushed.map(_._1)) // Layer 2: healed nodes leave the persisted frontier
      val count = flushed.size
      rawNodeBuffer.clear()
      log.info(s"Flushed $count healed nodes to disk (total: $totalNodesHealed)")

  /** Async flush — copies buffer, clears it, writes on the dedicated `healing-writer-dispatcher` so the blocking
    * RocksDB write doesn't compete with sync actors on `sync-dispatcher`.
    */
  def flushRawNodesAsync(): Unit =
    if rawNodeBuffer.nonEmpty && !flushing then
      flushing = true
      val nodes = rawNodeBuffer.toSeq
      rawNodeBuffer.clear()
      // spec 005 T012: snapshot the staged records this flush will make durable on the ACTOR thread (pendingSubtreeRecords
      // is actor state), then write them on the worker thread AFTER persist. Crash before persist ⇒ records dropped ⇒ safe.
      val toRecord = nodes.iterator.map(_._1).filter(pendingSubtreeRecords.contains).toSeq
      toRecord.foreach(pendingSubtreeRecords.remove)
      import scala.concurrent.{Future, blocking}
      val selfRef = self
      val ec = healingWriterEc
      Future {
        blocking {
          mptStorage.storeRawNodes(nodes)
          mptStorage.persist()
          writeDurableSubtreeRecords(
            toRecord
          ) // post-persist (D3); HealingFrontierStorage writes are DB-lock-safe off-thread
          unpersistFrontier(nodes.map(_._1)) // Layer 2: healed nodes leave the persisted frontier (post-durable-write)
          nodes.size
        }
      }(ec).foreach(n => selfRef ! FlushComplete(n))(ec)
