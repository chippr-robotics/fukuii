package com.chipprbots.ethereum.blockchain.sync.snap.actors.healing

import java.util.concurrent.atomic.AtomicInteger

import scala.collection.mutable
import scala.concurrent.ExecutionContext
import scala.concurrent.blocking

import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncMetrics
import com.chipprbots.ethereum.blockchain.sync.snap.actors.TrieNodeHealingCoordinator.ActiveRequest
import com.chipprbots.ethereum.blockchain.sync.snap.actors.TrieNodeHealingCoordinator.FlushComplete
import com.chipprbots.ethereum.blockchain.sync.snap.actors.TrieNodeHealingCoordinator.FlushFailed
import com.chipprbots.ethereum.blockchain.sync.snap.actors.TrieNodeHealingCoordinator.HealingEntry
import com.chipprbots.ethereum.blockchain.sync.snap.heal.FlushBuffer
import com.chipprbots.ethereum.blockchain.sync.snap.heal.FlushTicket
import com.chipprbots.ethereum.blockchain.sync.snap.heal.HealTaskQueue

/** The coordinator state `FrontierPersistence` reads and writes (spec 016 C-H1, research.md R14b). The core's behaviour
  * arms and the code C-H2…C-H5 will move touch every one of them too, or rule (d) keeps them in the core
  * (`pendingBackpressure`, the `AtomicInteger` the frontier walk's thread reads; `healingWriterEc`, a derived val), so
  * the Impl keeps them as fields. `flushInFlight` is the `var` the module writes (spec 022 review forge C1: the
  * asynchronous flush in flight, by its ticket); it also mutates `rawNodeBuffer`.
  */
private[actors] trait FrontierPersistenceState:
  def pendingTasks: HealTaskQueue[HealingEntry]
  def activeRequests: mutable.Map[BigInt, ActiveRequest]
  def pendingBackpressure: AtomicInteger
  def crossRootHealCount: Long
  def healAttempts: HealAttemptMap
  def rawNodeBuffer: FlushBuffer
  var flushInFlight: Option[FlushTicket]
  def totalNodesHealed: Int
  def healingWriterEc: ExecutionContext

/** What the frontier-rebuild walk (C-H2) calls in `FrontierPersistence`: the backpressure wait before each emitted
  * batch. A separate trait, so the C-H1 interfaces stay as recorded.
  */
private[actors] trait FrontierDrainApi:
  def awaitFrontierDrain(cancelled: () => Boolean): Unit

/** What the heal dispatch (C-H4) calls in `FrontierPersistence`: the backlog gauges it refreshes after each throttle
  * update. A separate trait, so the C-H1 interfaces stay as recorded.
  */
private[actors] trait FrontierGaugesApi:
  def emitHealingFrontierGauges(): Unit

/** What the response processing (C-H5) calls in `FrontierPersistence`: the asynchronous healed-node flush after each
  * served response. A separate trait, so the C-H1 interfaces stay as recorded.
  */
private[actors] trait FrontierWriteApi:
  def flushRawNodesAsync(): Unit

/** What a walk launcher calls before it starts a frontier walk (the verification launchers in C-H3 `HealVerification`,
  * the core's crash-recovery rebuild): `flushedForWalk`, which writes the flush buffer first if it holds anything (spec
  * 022 review forge F1). A separate trait, so the C-H1 interfaces stay as recorded.
  */
private[actors] trait WalkFlushApi:
  def flushedForWalk(): Boolean

/** TrieNodeHealingCoordinator's healed-node flush (spec 016 C-H1, coordinator-characterization.md §3.4): the backlog
  * gauges and the frontier walk's backpressure wait, and the batched raw-node flush to the trie store (synchronous at
  * completion, asynchronous on the healing-writer dispatcher otherwise). The coordinator core keeps the behaviour arms
  * that call it (`frontierPersistenceArms` handles `FlushComplete`).
  *
  * The name is what is left of the persisted frontier this module used to mirror to CF `g`, retired by spec 022 D6: a
  * restart re-discovers from the current root, since a held node is never on disk. A fix may not rename a module file.
  */
private[actors] trait FrontierPersistence
    extends FrontierDrainApi
    with FrontierGaugesApi
    with FrontierWriteApi
    with WalkFlushApi:
  this: FrontierPersistenceState & TrieNodeHealingEnv =>

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
    * the drain stalls (fail loud, then resume). `cancelled` ends the wait at once: the gauge is the launching
    * incarnation's, and once that incarnation has ended nothing refreshes it, so a walk of a replaced incarnation would
    * otherwise sit out the timeout on a frozen number (spec 022 T038a).
    */
  def awaitFrontierDrain(cancelled: () => Boolean): Unit =
    if pendingBackpressure.get() >= frontierHighWater then
      val startWait = System.currentTimeMillis()
      log.info(
        s"[HEAL-BFS] Backpressure: healing backlog ${pendingBackpressure.get()} >= high-water " +
          s"$frontierHighWater — pausing frontier emission until it drains below $frontierLowWater"
      )
      while pendingBackpressure.get() > frontierLowWater && !cancelled() &&
        System.currentTimeMillis() - startWait < frontierBackpressureMaxWaitMs
      do
        // blocking{} lets a ForkJoin-backed EC (e.g. the test injected EC) compensate with a spare
        // thread while parked here. On the production healing-writer-dispatcher (fixed thread pool)
        // this is a no-op, but documents that the sleep is intentionally blocking — consistent with
        // the Await.result(blocking{...}) pattern already used in rebuildFrontierBFS.
        blocking(Thread.sleep(200))
      val waitedMs = System.currentTimeMillis() - startWait
      if cancelled() then log.info(s"[HEAL-BFS] Backpressure wait ended after ${waitedMs}ms: the walk was cancelled")
      else if pendingBackpressure.get() > frontierLowWater then
        log.warn(
          s"[HEAL-BFS] Backpressure wait hit the " +
            s"${frontierBackpressureMaxWaitMs / 1000}s safety timeout " +
            s"(backlog still ${pendingBackpressure.get()}) — resuming emission to avoid deadlock"
        )
      else log.info(s"[HEAL-BFS] Backpressure released after ${waitedMs}ms — resuming frontier emission")

  /** Whether an asynchronous flush is in flight: its ticket is set from its start until its own `FlushComplete` or
    * `FlushFailed`, or until a synchronous flush takes it over.
    */
  def flushing: Boolean = flushInFlight.isDefined

  /** Synchronous flush of the whole buffer, on the actor thread: at completion, a re-peg and a force-complete. True
    * when the buffer is empty afterwards. It writes prefixes of at most [[FlushBuffer.MaxFlushNodes]] nodes, one batch
    * each (spec 022 review vault V7: every prefix is complete, and a batch stays the size the storage protocol names).
    * Nodes leave the buffer only once their write has returned (#1549, spec 022 T026); a throw is logged and leaves
    * what is unwritten for the next flush. A completion gate must not declare completion on `false`.
    *
    * '''One writer (spec 022 review vault V1, hunter W4, forge F5, vault R4).''' Every healed-node write holds the
    * store's lock (`mptStorage`: the one instance every incarnation of this coordinator writes through). That lock
    * covers healing's writers only: the account and storage downloads write through their own paths and do not take it,
    * which is fine because they have finished before healing starts. An asynchronous flush in flight is superseded
    * first: if it is still queued it never writes, and if it is writing this waits for it, then releases what it wrote
    * instead of writing it again. Afterwards no asynchronous flush is in flight, whether this write succeeded or not.
    */
  def flushRawNodesSync(): Boolean =
    flushInFlight.foreach(_.supersede())
    val store = mptStorage
    store.synchronized {
      flushInFlight.filter(_.written).foreach(releaseWritten)
      flushInFlight = None
      var written = true
      while written && rawNodeBuffer.nonEmpty do
        val flushed = rawNodeBuffer.firstEntries(FlushBuffer.MaxFlushNodes)
        written =
          try
            store.storeRawNodes(flushed)
            store.persist()
            true
          catch
            case scala.util.control.NonFatal(e) =>
              log.error(s"Healed-node flush of ${flushed.size} node(s) failed — kept for the next flush", e)
              false
        if written then
          // spec 022 review vault: the entries are the buffer's own head, so this drops all of them. If it ever dropped
          // none the loop would write the same nodes forever on the actor's thread; the gate stays shut instead.
          if rawNodeBuffer.dropWritten(flushed) == 0 then
            log.error(s"Healed-node flush of ${flushed.size} node(s) wrote them, but the buffer's head had moved")
            written = false
          log.info(s"Flushed ${flushed.size} healed nodes to disk (total: $totalNodesHealed)")
      written
    }

  /** Whether a frontier walk may start: true when the flush buffer is empty, or once a synchronous flush has written it
    * (spec 022 review forge F1). A walk reads the store only, so a node committed with its whole subtree but still in
    * the buffer reads as missing there, and the walk's entry for it is refused (it is complete, only not written): the
    * walk ends dirty, and the next would too, while nothing writes the buffer. False means the write failed; the caller
    * starts no walk, and the completion check and the 2-minute pulse retry the write.
    */
  def flushedForWalk(): Boolean = rawNodeBuffer.isEmpty || flushRawNodesSync()

  /** Async flush on the dedicated `healing-writer-dispatcher`, so the blocking RocksDB write doesn't compete with sync
    * actors on `sync-dispatcher`. It writes a snapshot of the buffer and does NOT clear it: the nodes leave the buffer
    * only when this flush's own `FlushComplete` reports the write returned ([[flushCompleted]]), and a throw sends
    * `FlushFailed`, which ends the flush and keeps them ([[flushFailed]]) (#1549, spec 022 T026). Every node in the
    * buffer was committed with its whole subtree (spec 022 D3), so the snapshot, a prefix of the buffer of at most
    * [[FlushBuffer.MaxFlushNodes]] nodes, is complete; the `FlushComplete` arm flushes what follows it. The write holds
    * the store's lock and skips itself once superseded (see [[flushRawNodesSync]] and [[FlushTicket]]).
    */
  def flushRawNodesAsync(): Unit =
    if rawNodeBuffer.nonEmpty && !flushing then
      val ticket = FlushTicket(rawNodeBuffer.firstEntries(FlushBuffer.MaxFlushNodes))
      flushInFlight = Some(ticket)
      val nodes = ticket.snapshot
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
              ticket.markWritten()
              true
          }
        }
      }(ec).onComplete {
        case scala.util.Success(true)  => selfRef ! FlushComplete(ticket.token, nodes.size)
        case scala.util.Success(false) => () // superseded: the synchronous flush that took it over wrote these nodes
        case scala.util.Failure(e)     => selfRef ! FlushFailed(ticket.token, e)
      }(ec)

  /** The asynchronous flush `token` wrote its snapshot: release those nodes. Only the flush in flight counts. A
    * completion of any other (one a synchronous flush took over, or one a previous incarnation of this coordinator
    * started before a supervisor restart) changes nothing (spec 022 review forge C1).
    */
  def flushCompleted(token: Long, count: Int): Unit =
    flushInFlight match
      case Some(ticket) if ticket.token == token =>
        flushInFlight = None
        releaseWritten(ticket)
        log.info(s"Async flush complete: $count healed nodes written to disk (total: $totalNodesHealed)")
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

  /** `ticket`'s snapshot is durable: drop exactly those entries from the head of the buffer (the very objects it took).
    */
  private def releaseWritten(ticket: FlushTicket): Unit =
    val dropped = rawNodeBuffer.dropWritten(ticket.snapshot)
    if dropped != ticket.snapshot.size then
      log.error(
        s"[HEAL-FLUSH] Flush ${ticket.token} wrote ${ticket.snapshot.size} node(s), but only the first $dropped are " +
          "still at the head of the buffer: the rest stay buffered and are written again (a bookkeeping fault)"
      )
