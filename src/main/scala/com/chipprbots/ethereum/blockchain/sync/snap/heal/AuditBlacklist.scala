package com.chipprbots.ethereum.blockchain.sync.snap.heal

import java.util.concurrent.ConcurrentHashMap

import scala.concurrent.duration.FiniteDuration
import scala.jdk.CollectionConverters.*

import com.chipprbots.ethereum.blockchain.sync.Blacklist
import com.chipprbots.ethereum.blockchain.sync.Blacklist.BlacklistId
import com.chipprbots.ethereum.blockchain.sync.Blacklist.BlacklistReason
import com.chipprbots.ethereum.blockchain.sync.Blacklist.BlacklistReason.BlacklistReasonType

/** The background audit's own blacklist (spec 022 decision 2). A peer that serves it a wrong node stays out of its
  * fetches and nobody else's, and nothing here touches the node's metrics: `CacheBasedBlacklist` sets a shared gauge to
  * its own size and bumps shared counters, so the audit's disconnect handling would reset what the dashboards read of
  * import's. Bounded: past `maxSize`, the entry soonest to expire makes way.
  */
final class AuditBlacklist(maxSize: Int, nowNanos: () => Long = () => System.nanoTime()) extends Blacklist:
  private val listed = new ConcurrentHashMap[BlacklistId, AuditBlacklist.Listed]()

  private def live(id: BlacklistId): Option[AuditBlacklist.Listed] =
    Option(listed.get(id)).filter(_.untilNanos - nowNanos() > 0)

  override def isBlacklisted(id: BlacklistId): Boolean = live(id).isDefined

  override def add(id: BlacklistId, duration: FiniteDuration, reason: BlacklistReason): Unit =
    if !listed.containsKey(id) && listed.size >= maxSize then
      dropExpired()
      if listed.size >= maxSize then
        listed.asScala
          .minByOption((_, entry) => entry.untilNanos - nowNanos())
          .foreach((soonest, _) => listed.remove(soonest))
    listed.put(id, AuditBlacklist.Listed(reason.reasonType, nowNanos() + duration.toNanos))

  override def remove(id: BlacklistId): Unit = listed.remove(id)

  override def keys: Set[BlacklistId] =
    dropExpired()
    listed.keySet.asScala.toSet

  override def reasonFor(id: BlacklistId): Option[BlacklistReasonType] = live(id).map(_.reason)

  private def dropExpired(): Unit =
    listed.entrySet.removeIf(entry => entry.getValue.untilNanos - nowNanos() <= 0)

object AuditBlacklist:
  final private case class Listed(reason: BlacklistReasonType, untilNanos: Long)
