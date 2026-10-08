package com.chipprbots.ethereum.blockchain.sync.snap

import java.lang.management.ManagementFactory
import java.lang.management.MemoryNotificationInfo
import java.lang.management.MemoryPoolMXBean
import java.lang.management.MemoryType
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import javax.management.Notification
import javax.management.NotificationEmitter
import javax.management.NotificationListener

import scala.concurrent.duration.FiniteDuration
import scala.jdk.CollectionConverters.*

import com.chipprbots.ethereum.utils.Logger

/** Hysteresis core of the heap watchdog (spec 014): pure, so the engage/release rule is unit-tested without a JVM heap.
  *
  * Engages when old-gen occupancy measured right after a collection of that pool reaches `highFraction` of the max
  * heap. Releases once occupancy is back at or below `lowFraction`, judged on the lower of the post-GC reading and the
  * current usage: current usage is never below the live set, so a low current value proves the live set is low even
  * when no old-gen collection has run since intake paused (the post-GC reading would otherwise stay stale-high and pin
  * the pause).
  */
final class HeapPressureHysteresis(val highFraction: Double, val lowFraction: Double):
  require(
    lowFraction > 0.0 && lowFraction < highFraction && highFraction < 1.0,
    s"heap watchdog thresholds need 0 < low < high < 1, got low=$lowFraction high=$highFraction"
  )

  private var engaged: Boolean = false

  def isEngaged: Boolean = synchronized(engaged)

  /** Feed one reading. Returns `Some(true)` on engage, `Some(false)` on release, `None` when nothing changed. */
  def observe(postGcUsed: Long, currentUsed: Long, max: Long): Option[Boolean] = synchronized {
    if max <= 0 then None
    else if !engaged && postGcUsed.toDouble / max >= highFraction then
      engaged = true
      Some(true)
    else if engaged && math.min(postGcUsed, currentUsed).toDouble / max <= lowFraction then
      engaged = false
      Some(false)
    else None
  }

/** One reading of the old-generation pool: occupancy after its last collection, current occupancy, and the ceiling. */
final case class OldGenReading(postGcUsed: Long, currentUsed: Long, max: Long):
  def postGcFraction: Double = if max > 0 then postGcUsed.toDouble / max else 0.0

/** What the watchdog does when a pause is not helping (spec 014 review). */
enum HeapWatchdogEscape:
  /** Paused, yet the SNAP queues have been empty for `idleMs`: the heap is held by something the pause cannot drain. */
  case PauseIneffective(pausedMs: Long, idleMs: Long)

  /** Paused for `pausedMs` (>= the configured maximum) with the queues empty throughout the ineffective window: intake
    * is released despite the heap reading. It stays released until occupancy falls below the resume threshold and rises
    * past the pause threshold again.
    */
  case ForcedRelease(pausedMs: Long)

/** @param ineffectiveAfterMs
  *   how long the queues must be empty while paused before the pause is declared ineffective (WARN)
  * @param maxPauseMs
  *   force-release an ineffective pause once it has lasted this long; 0 = never force-release
  */
final case class HeapWatchdogEscapePolicy(ineffectiveAfterMs: Long, maxPauseMs: Long):
  require(ineffectiveAfterMs >= 0 && maxPauseMs >= 0, s"negative escape policy: $this")

object HeapWatchdogEscapePolicy:
  val Default: HeapWatchdogEscapePolicy = HeapWatchdogEscapePolicy(ineffectiveAfterMs = 60000L, maxPauseMs = 300000L)

/** Heap watchdog for SNAP sync (spec 014). Sets the old-gen pool's collection-usage threshold and listens for the JMX
  * MEMORY_COLLECTION_THRESHOLD_EXCEEDED notification (immediate engage), and polls the pool on a single daemon thread
  * (engage if a notification was missed; release, which JMX never signals). Transitions go to `onChange`, which the
  * controller wires to [[SnapIntakeBudget.setHeapPressure]] — a synchronous flag the producers read, so the pause never
  * waits on an actor mailbox.
  *
  * Engage lag: on G1 the old pool's collection usage is updated by mixed and full collections (and the concurrent
  * cycle), not by young-only GCs, so a reading can trail the live set by one marking cycle. Current usage is not used
  * to engage because it includes garbage a collection would free; the pending-work ceilings bound SNAP's own growth in
  * the meantime.
  *
  * @param read
  *   source of old-gen readings; the JMX pool in production, a stub in tests
  * @param onChange
  *   called with `true` on engage and `false` on release, with the reading that caused it
  * @param queuesEmpty
  *   whether the SNAP storage and bytecode queues (in transit + queued) are empty
  * @param onEscape
  *   called when a pause is ineffective (once per pause) and when it is force-released
  */
final class SnapHeapWatchdog(
    hysteresis: HeapPressureHysteresis,
    read: () => Option[OldGenReading],
    onChange: (Boolean, OldGenReading) => Unit,
    queuesEmpty: () => Boolean = () => false,
    policy: HeapWatchdogEscapePolicy = HeapWatchdogEscapePolicy.Default,
    onEscape: (HeapWatchdogEscape, OldGenReading) => Unit = (_, _) => (),
    nowMs: () => Long = () => System.currentTimeMillis()
):

  // Escape bookkeeping for the current pause. Guarded by `this` (evaluate is synchronized).
  private var pausedSinceMs: Long = 0L
  private var queuesEmptySinceMs: Option[Long] = None
  private var warnedIneffective: Boolean = false
  private var forcedRelease: Boolean = false

  /** Evaluate one reading. Called from the poll thread and the JMX notification thread, hence synchronized. */
  def evaluate(): Unit = synchronized {
    read().foreach { r =>
      SNAPSyncMetrics.setOldGenPostGc(r.postGcUsed, r.postGcFraction)
      hysteresis.observe(r.postGcUsed, r.currentUsed, r.max).foreach { engaged =>
        pausedSinceMs = nowMs()
        queuesEmptySinceMs = None
        warnedIneffective = false
        forcedRelease = false
        onChange(engaged, r)
      }
      if hysteresis.isEngaged && !forcedRelease then checkEscape(r)
    }
  }

  /** A pause exists to let the SNAP queues drain. Once they are empty and stay empty, the heap is held by something
    * else and pausing only stalls the sync: say so, and after `maxPauseMs` let intake run again.
    */
  private def checkEscape(r: OldGenReading): Unit =
    val now = nowMs()
    if !queuesEmpty() then queuesEmptySinceMs = None
    else
      val emptySince = queuesEmptySinceMs.getOrElse(now)
      queuesEmptySinceMs = Some(emptySince)
      val idleMs = now - emptySince
      val pausedMs = now - pausedSinceMs
      if idleMs >= policy.ineffectiveAfterMs then
        if !warnedIneffective then
          warnedIneffective = true
          onEscape(HeapWatchdogEscape.PauseIneffective(pausedMs, idleMs), r)
        if policy.maxPauseMs > 0 && pausedMs >= policy.maxPauseMs then
          forcedRelease = true
          onEscape(HeapWatchdogEscape.ForcedRelease(pausedMs), r)

  /** True while intake was force-released and the hysteresis has not yet released on its own. */
  def isForceReleased: Boolean = synchronized(forcedRelease)

  def isEngaged: Boolean = hysteresis.isEngaged

object SnapHeapWatchdog extends Logger:

  /** The heap pool that holds long-lived objects and reports post-collection usage: "G1 Old Gen", "PS Old Gen",
    * "Tenured Gen", "ZGC Old Generation", ...
    */
  def findOldGenPool(): Option[MemoryPoolMXBean] =
    ManagementFactory.getMemoryPoolMXBeans.asScala.find { p =>
      p.getType == MemoryType.HEAP && p.isCollectionUsageThresholdSupported &&
      (p.getName.contains("Old") || p.getName.contains("Tenured"))
    }

  private def maxOf(pool: MemoryPoolMXBean): Long =
    val poolMax = pool.getUsage.getMax
    // G1 reports the whole heap as the old gen's max; -1 means undefined, so fall back to -Xmx.
    if poolMax > 0 then poolMax else Runtime.getRuntime.maxMemory

  private def readPool(pool: MemoryPoolMXBean): Option[OldGenReading] =
    val current = pool.getUsage
    Option(pool.getCollectionUsage).map { postGc =>
      OldGenReading(postGcUsed = postGc.getUsed, currentUsed = current.getUsed, max = maxOf(pool))
    }

  /** A running watchdog and the way to stop it. */
  final class Handle private[SnapHeapWatchdog] (
      val watchdog: SnapHeapWatchdog,
      executor: ScheduledExecutorService,
      unregister: () => Unit
  ):
    def stop(): Unit =
      unregister()
      executor.shutdownNow()
      ()

  /** Start the JMX-backed watchdog, or return None (logged at WARN) when this JVM has no suitable old-gen pool. */
  def start(
      highFraction: Double,
      lowFraction: Double,
      pollInterval: FiniteDuration,
      onChange: (Boolean, OldGenReading) => Unit,
      queuesEmpty: () => Boolean,
      policy: HeapWatchdogEscapePolicy,
      onEscape: (HeapWatchdogEscape, OldGenReading) => Unit
  ): Option[Handle] =
    findOldGenPool() match
      case None =>
        log.warn(
          "[SNAP-HEAP] no old-generation heap pool with collection-usage thresholds in this JVM: the SNAP heap " +
            "watchdog is OFF (the pending-work ceilings still apply)"
        )
        None
      case Some(pool) =>
        val hysteresis = new HeapPressureHysteresis(highFraction, lowFraction)
        val watchdog =
          new SnapHeapWatchdog(hysteresis, () => readPool(pool), onChange, queuesEmpty, policy, onEscape)
        val threshold = (maxOf(pool) * highFraction).toLong
        pool.setCollectionUsageThreshold(threshold)
        val emitter = ManagementFactory.getMemoryMXBean.asInstanceOf[NotificationEmitter]
        val listener = new NotificationListener:
          override def handleNotification(n: Notification, handback: Object): Unit =
            val _ = handback // no handback is registered
            if n.getType == MemoryNotificationInfo.MEMORY_COLLECTION_THRESHOLD_EXCEEDED then watchdog.evaluate()
        emitter.addNotificationListener(listener, null, null)
        val executor = Executors.newSingleThreadScheduledExecutor { (r: Runnable) =>
          val t = new Thread(r, "snap-heap-watchdog")
          t.setDaemon(true)
          t
        }
        val period = math.max(1L, pollInterval.toMillis)
        val poll: Runnable = () =>
          try watchdog.evaluate()
          catch case e: Exception => log.error(s"[SNAP-HEAP] watchdog poll failed: ${e.getMessage}", e)
        val _ = executor.scheduleWithFixedDelay(poll, period, period, TimeUnit.MILLISECONDS)
        log.info(
          s"[SNAP-HEAP] watchdog on pool '${pool.getName}': pause SNAP intake at ${(highFraction * 100).round}% " +
            s"post-GC occupancy (${threshold / (1024 * 1024)} MiB of ${maxOf(pool) / (1024 * 1024)} MiB), resume at " +
            s"${(lowFraction * 100).round}%"
        )
        val unregister = () =>
          try emitter.removeNotificationListener(listener)
          catch case _: javax.management.ListenerNotFoundException => ()
        Some(new Handle(watchdog, executor, unregister))
