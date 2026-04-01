package com.chipprbots.ethereum.network

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

import org.apache.pekko.actor.Scheduler
import org.slf4j.LoggerFactory

/** Automatically blocks IPs that exhibit disruptive behavior, matching besu's PeerDenylistManager
  * pattern. Bridges failure events from the network stack to BlockedIPRegistry.
  *
  * Two signal types:
  *   - Soft (UDP send failures): tracked with threshold + time window. 3 failures in 5 min → 30 min block.
  *   - Hard (protocol violations): blocked immediately for 60 min.
  *
  * All blocks are temporary and auto-expire. State is in-memory only — consistent with
  * BlockedIPRegistry's existing design.
  */
class AutoBlocker(
    registry: BlockedIPRegistry,
    scheduler: Scheduler,
    executionContext: ExecutionContext,
    udpFailureThreshold: Int,
    udpWindow: FiniteDuration,
    udpBlockDuration: FiniteDuration,
    hardFailureBlockDuration: FiniteDuration
) {
  private val log = LoggerFactory.getLogger(getClass)

  // Per-IP UDP failure tracking: ip → (failureCount, windowStartMs)
  private val udpFailures = new ConcurrentHashMap[String, (AtomicInteger, Long)]()

  /** Called on every UDP send failure for an IP. Blocks if threshold exceeded within window. */
  def recordUdpFailure(ip: String): Unit = {
    val now = System.currentTimeMillis()
    val windowMs = udpWindow.toMillis

    val (counter, _) = udpFailures.compute(
      ip,
      (_, existing) =>
        if (existing == null || (now - existing._2) > windowMs)
          (new AtomicInteger(1), now)
        else {
          existing._1.incrementAndGet()
          existing
        }
    )

    val count = counter.get()
    if (count >= udpFailureThreshold && !registry.isBlocked(ip)) {
      registry.block(ip)
      log.warn(
        s"Auto-blocked $ip after $count UDP send failures within ${udpWindow.toSeconds}s window. " +
          s"Block expires in ${udpBlockDuration.toMinutes}min."
      )
      scheduler.scheduleOnce(udpBlockDuration, () => {
        registry.unblock(ip)
        udpFailures.remove(ip)
        log.info(s"Auto-unblocked $ip after ${udpBlockDuration.toMinutes}min UDP decay.")
      })(executionContext)
    }
  }

  /** Called on hard protocol violations (e.g. IncompatibleP2pProtocolVersion). Blocks immediately. */
  def recordHardFailure(ip: String, reason: String): Unit = {
    if (!registry.isBlocked(ip)) {
      registry.block(ip)
      log.warn(
        s"Auto-blocked $ip: $reason. Block expires in ${hardFailureBlockDuration.toMinutes}min."
      )
      scheduler.scheduleOnce(hardFailureBlockDuration, () => {
        registry.unblock(ip)
        log.info(s"Auto-unblocked $ip after ${hardFailureBlockDuration.toMinutes}min (hard failure decay).")
      })(executionContext)
    }
  }
}
