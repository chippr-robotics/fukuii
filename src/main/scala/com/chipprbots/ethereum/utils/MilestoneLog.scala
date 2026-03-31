package com.chipprbots.ethereum.utils

import org.slf4j.LoggerFactory

/** Dedicated milestone logger for sync lifecycle events.
  * Writes to a non-rotating milestone.log via logback.
  * Include metrics/context in every call — this is the sync flight recorder.
  */
object MilestoneLog {
  private val log = LoggerFactory.getLogger("fukuii.milestone")

  /** Major phase transition (SNAP start, regular sync start, etc.) */
  def phase(msg: String): Unit = log.info(s"[PHASE] $msg")

  /** Notable event within a phase (pivot refresh, fallback, recovery) */
  def event(msg: String): Unit = log.info(s"[EVENT] $msg")

  /** Periodic progress snapshot — caller must throttle (see shouldLog helper) */
  def progress(msg: String): Unit = log.info(s"[PROGRESS] $msg")

  /** Error that affects sync outcome */
  def error(msg: String): Unit = log.error(s"[ERROR] $msg")

  // Time-based throttle: returns true at most once per interval
  private val lastLogTimes = new java.util.concurrent.ConcurrentHashMap[String, Long]()
  def shouldLog(key: String, intervalMs: Long = 1800000L): Boolean = { // 30 min default
    val now = System.currentTimeMillis()
    val last = lastLogTimes.getOrDefault(key, 0L)
    if (now - last >= intervalMs) { lastLogTimes.put(key, now); true } else false
  }
}
