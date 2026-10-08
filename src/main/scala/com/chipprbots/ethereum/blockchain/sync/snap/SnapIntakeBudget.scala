package com.chipprbots.ethereum.blockchain.sync.snap

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

import com.chipprbots.ethereum.utils.Logger

/** Closed-loop admission gate for SNAP contract work (spec 014).
  *
  * The storage and bytecode queues are fed by AccountRangeCoordinator (fresh account responses and the replay of the
  * carried task-file prefix) and by the controller's accounts-complete recovery stream. The older watermark protocol
  * signals "pause" through two actor mailboxes (coordinator -> controller -> account coordinator). On Sepolia
  * (2026-10-08) that signal took 3 minutes to arrive: the carried replay had already pushed 2.5M storage tasks into the
  * mailboxes, and the storage queue held 2.2M tasks (~900 MB) with back-pressure nominally engaged.
  *
  * This gate is read synchronously by the producers, so its answer never waits on a mailbox. It counts work in two
  * parts: `inTransit` (reserved by a producer when it sends, released when the consumer receives it or when the
  * controller drops it) and `queued` (published by the consumer after it changes its queue). A producer may add work
  * only while `inTransit + queued` is below the ceiling and the heap watchdog reports no pressure. Nothing is ever
  * dropped: a paused producer keeps its cursor and resumes when the gate opens.
  *
  * Failure direction. Undercounting only lets one more chunk through; overcounting would pause intake forever. Every
  * imprecise path therefore errs low: counters clamp at zero, a consumer (re)start resets its counters, and in-transit
  * work that no consumer has acknowledged for `staleInTransitMs` while the gate is closed is written off with a WARN (a
  * message lost to a dead coordinator must not wedge the sync).
  *
  * @param maxPendingStorageTasks
  *   ceiling on storage tasks in transit plus queued in StorageRangeCoordinator
  * @param maxPendingByteCodeHashes
  *   ceiling on codeHashes in transit plus queued in ByteCodeCoordinator (counted in hashes, not batched tasks)
  */
final class SnapIntakeBudget(
    val maxPendingStorageTasks: Long,
    val maxPendingByteCodeHashes: Long,
    staleInTransitMs: Long = SnapIntakeBudget.DefaultStaleInTransitMs,
    nowMs: () => Long = () => System.currentTimeMillis()
) extends Logger:

  require(maxPendingStorageTasks > 0, s"maxPendingStorageTasks must be positive: $maxPendingStorageTasks")
  require(maxPendingByteCodeHashes > 0, s"maxPendingByteCodeHashes must be positive: $maxPendingByteCodeHashes")

  private val storageInTransit = new AtomicLong(0L)
  private val storageQueued = new AtomicLong(0L)
  private val byteCodeInTransit = new AtomicLong(0L)
  private val byteCodeQueued = new AtomicLong(0L)
  private val lastStorageReceiptMs = new AtomicLong(nowMs())
  private val lastByteCodeReceiptMs = new AtomicLong(nowMs())
  private val heapPressure = new AtomicBoolean(false)

  private def decrementClamped(counter: AtomicLong, n: Long): Unit =
    if n > 0 then
      val _ = counter.updateAndGet(v => math.max(0L, v - n))

  // ---------- producer side ----------

  /** A producer is about to send `storageTasks` storage tasks and `byteCodeHashes` codeHashes downstream. */
  def reserve(storageTasks: Int, byteCodeHashes: Int): Unit =
    if storageTasks > 0 then
      // A reservation after a long idle gap must not be mistaken for a stale one.
      if storageInTransit.getAndAdd(storageTasks.toLong) == 0L then lastStorageReceiptMs.set(nowMs())
    if byteCodeHashes > 0 then
      if byteCodeInTransit.getAndAdd(byteCodeHashes.toLong) == 0L then lastByteCodeReceiptMs.set(nowMs())

  /** Work reserved by a producer that will never reach a consumer (filtered or dropped on the way). */
  def release(storageTasks: Int, byteCodeHashes: Int): Unit =
    decrementClamped(storageInTransit, storageTasks.toLong)
    decrementClamped(byteCodeInTransit, byteCodeHashes.toLong)

  /** Why new intake must wait, or None when producers may proceed. Cheap; safe from any thread. */
  def intakeBlockedReason(): Option[String] =
    if heapPressure.get() then Some("heap pressure")
    else
      writeOffStaleInTransit()
      val storage = pendingStorageTasks
      val byteCodes = pendingByteCodeHashes
      if storage >= maxPendingStorageTasks then
        Some(s"storage tasks pending $storage >= ceiling $maxPendingStorageTasks")
      else if byteCodes >= maxPendingByteCodeHashes then
        Some(s"codeHashes pending $byteCodes >= ceiling $maxPendingByteCodeHashes")
      else None

  def intakeAllowed: Boolean = intakeBlockedReason().isEmpty

  // ---------- consumer side ----------

  /** A (new or restarted) StorageRangeCoordinator starts with an empty queue: forget the previous instance's counts. */
  def attachStorageConsumer(): Unit =
    storageInTransit.set(0L)
    storageQueued.set(0L)
    lastStorageReceiptMs.set(nowMs())

  /** A (new or restarted) ByteCodeCoordinator starts with an empty queue. */
  def attachByteCodeConsumer(): Unit =
    byteCodeInTransit.set(0L)
    byteCodeQueued.set(0L)
    lastByteCodeReceiptMs.set(nowMs())

  /** StorageRangeCoordinator received `n` tasks from a producer and now holds `queuedAfter` pending tasks. */
  def storageReceived(n: Int, queuedAfter: Long): Unit =
    decrementClamped(storageInTransit, n.toLong)
    storageQueued.set(math.max(0L, queuedAfter))
    lastStorageReceiptMs.set(nowMs())

  def storageQueueDepth(queued: Long): Unit = storageQueued.set(math.max(0L, queued))

  /** ByteCodeCoordinator received `n` codeHashes from a producer and now holds `queuedHashesAfter` pending hashes. */
  def byteCodeReceived(n: Int, queuedHashesAfter: Long): Unit =
    decrementClamped(byteCodeInTransit, n.toLong)
    byteCodeQueued.set(math.max(0L, queuedHashesAfter))
    lastByteCodeReceiptMs.set(nowMs())

  def byteCodeQueueDepth(queuedHashes: Long): Unit = byteCodeQueued.set(math.max(0L, queuedHashes))

  // ---------- heap watchdog ----------

  def setHeapPressure(active: Boolean): Unit = heapPressure.set(active)
  def heapPressureActive: Boolean = heapPressure.get()

  // ---------- observation ----------

  def pendingStorageTasks: Long = storageInTransit.get() + storageQueued.get()
  def pendingByteCodeHashes: Long = byteCodeInTransit.get() + byteCodeQueued.get()
  def storageTasksInTransit: Long = storageInTransit.get()
  def byteCodeHashesInTransit: Long = byteCodeInTransit.get()

  /** Push the gate's state to the Prometheus gauges. Called from the consumers' queue-depth updates and the watchdog.
    */
  def publishMetrics(): Unit =
    SNAPSyncMetrics.setIntakeStoragePending(pendingStorageTasks)
    SNAPSyncMetrics.setIntakeByteCodePending(pendingByteCodeHashes)
    SNAPSyncMetrics.setIntakePaused(
      heapPressure.get() || pendingStorageTasks >= maxPendingStorageTasks ||
        pendingByteCodeHashes >= maxPendingByteCodeHashes
    )
    SNAPSyncMetrics.setHeapPressure(heapPressure.get())

  def describe: String =
    s"storage=${storageInTransit.get()}+${storageQueued.get()}/$maxPendingStorageTasks " +
      s"codeHashes=${byteCodeInTransit.get()}+${byteCodeQueued.get()}/$maxPendingByteCodeHashes " +
      s"heapPressure=${heapPressure.get()}"

  /** In-transit work no consumer has acknowledged for `staleInTransitMs` cannot still be in a live mailbox: write it
    * off (loudly) so a message lost to a stopped coordinator does not hold intake shut forever.
    */
  private def writeOffStaleInTransit(): Unit =
    val now = nowMs()
    def check(what: String, inTransit: AtomicLong, lastReceipt: AtomicLong): Unit =
      val n = inTransit.get()
      if n > 0 && now - lastReceipt.get() > staleInTransitMs then
        inTransit.set(0L)
        lastReceipt.set(now)
        log.warn(
          s"[SNAP-INTAKE] wrote off $n $what in transit: no consumer acknowledged any for ${staleInTransitMs / 1000}s " +
            s"(lost to a stopped coordinator?). Intake gate now: $describe"
        )
    check("storage tasks", storageInTransit, lastStorageReceiptMs)
    check("codeHashes", byteCodeInTransit, lastByteCodeReceiptMs)

object SnapIntakeBudget:
  /** Two minutes: far longer than any healthy mailbox delay, short enough that a leak costs one stall, not a sync. */
  val DefaultStaleInTransitMs: Long = 120000L
