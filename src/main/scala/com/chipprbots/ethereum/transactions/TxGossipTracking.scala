package com.chipprbots.ethereum.transactions

import java.util.LinkedHashMap as JLinkedHashMap

import scala.collection.mutable
import scala.concurrent.duration.FiniteDuration

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.network.PeerId

/** Which peers are known to hold which transaction, bounded by entry count and by age.
  *
  * Plain mutable state meant to be owned by one actor (the pool). Entries sit in a LinkedHashMap kept in last-touched
  * order, so size and age eviction both pop from the head: every operation is O(1) amortised, and the work of any one
  * message is O(its batch), never O(everything tracked). The previous immutable `Map` was rebuilt per pool eviction and
  * grew without bound for peers that stayed connected (#1517).
  *
  * Forgetting an entry only costs a possible duplicate announcement to a peer, which every peer must tolerate.
  */
final class KnownTransactions(
    maxEntries: Int,
    maxAge: FiniteDuration,
    clock: () => Long = () => System.currentTimeMillis()
):
  require(maxEntries > 0, "maxEntries must be positive")

  final private class Entry(val peers: mutable.Set[PeerId], var touchedAt: Long)

  // Iteration order = last-touched order (a touch removes and re-inserts).
  private val entries = new JLinkedHashMap[ByteString, Entry]()
  private val maxAgeMillis = maxAge.toMillis

  def size: Int = entries.size

  def isKnown(hash: ByteString, peerId: PeerId): Boolean =
    val entry = entries.get(hash)
    entry != null && !isExpired(entry, clock()) && entry.peers.contains(peerId)

  def markKnown(hash: ByteString, peerId: PeerId): Unit =
    val now = clock()
    expire(now)
    val existing = entries.remove(hash)
    val entry = if existing == null then new Entry(mutable.Set.empty[PeerId], now) else existing
    entry.peers += peerId
    entry.touchedAt = now
    entries.put(hash, entry)
    while entries.size > maxEntries do removeEldest()

  def remove(hash: ByteString): Unit = entries.remove(hash): Unit

  def removeAll(hashes: Iterable[ByteString]): Unit = hashes.foreach(remove)

  def clear(): Unit = entries.clear()

  private def isExpired(entry: Entry, now: Long): Boolean = now - entry.touchedAt > maxAgeMillis

  private def removeEldest(): Unit =
    val it = entries.entrySet().iterator()
    it.next()
    it.remove()

  private def expire(now: Long): Unit =
    val it = entries.entrySet().iterator()
    var continue = true
    while continue && it.hasNext do
      if isExpired(it.next().getValue, now) then it.remove()
      else continue = false

object PendingAnnouncements:
  final case class Announcement(txType: Byte, size: BigInt, peerId: PeerId)

/** What a peer announced for a hash we then requested: (type, size, announcer), checked against its reply.
  *
  * Bounded by entry count and by age: a peer that stays connected and never answers would otherwise leave its entries
  * forever. go-ethereum's fetcher likewise times an announcement out (`txFetchTimeout`) and caps announcements per
  * peer. A per-peer index makes dropping a disconnected peer's entries O(that peer's entries).
  */
final class PendingAnnouncements(
    maxEntries: Int,
    maxAge: FiniteDuration,
    clock: () => Long = () => System.currentTimeMillis()
):
  import PendingAnnouncements.Announcement

  require(maxEntries > 0, "maxEntries must be positive")

  final private class Entry(val announcement: Announcement, val announcedAt: Long)

  private val entries = new JLinkedHashMap[ByteString, Entry]()
  private val byPeer = mutable.Map.empty[PeerId, mutable.Set[ByteString]]
  private val maxAgeMillis = maxAge.toMillis

  def size: Int = entries.size

  /** The live announcement for `hash`; an expired one reads as absent. */
  def get(hash: ByteString): Option[Announcement] =
    val entry = entries.get(hash)
    if entry == null || clock() - entry.announcedAt > maxAgeMillis then None else Some(entry.announcement)

  def record(hash: ByteString, txType: Byte, size: BigInt, peerId: PeerId): Unit =
    val now = clock()
    expire(now)
    remove(hash)
    entries.put(hash, new Entry(Announcement(txType, size, peerId), now))
    byPeer.getOrElseUpdate(peerId, mutable.Set.empty) += hash
    while entries.size > maxEntries do removeEldest()

  def remove(hash: ByteString): Unit =
    val entry = entries.remove(hash)
    if entry != null then unindex(hash, entry.announcement.peerId)

  /** Drop everything `peerId` announced: its requests will never be answered. */
  def removePeer(peerId: PeerId): Unit =
    byPeer.remove(peerId).foreach(_.foreach(entries.remove(_): Unit))

  def clear(): Unit =
    entries.clear()
    byPeer.clear()

  private def unindex(hash: ByteString, peerId: PeerId): Unit =
    byPeer.get(peerId).foreach { hashes =>
      hashes -= hash
      if hashes.isEmpty then byPeer -= peerId
    }

  private def removeEldest(): Unit =
    val it = entries.entrySet().iterator()
    val eldest = it.next()
    it.remove()
    unindex(eldest.getKey, eldest.getValue.announcement.peerId)

  private def expire(now: Long): Unit =
    val it = entries.entrySet().iterator()
    var continue = true
    while continue && it.hasNext do
      val e = it.next()
      if now - e.getValue.announcedAt > maxAgeMillis then
        it.remove()
        unindex(e.getKey, e.getValue.announcement.peerId)
      else continue = false
