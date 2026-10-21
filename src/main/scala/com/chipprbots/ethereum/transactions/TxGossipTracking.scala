package com.chipprbots.ethereum.transactions

import java.util.LinkedHashMap as JLinkedHashMap

import scala.collection.mutable
import scala.concurrent.duration.*

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

  val DefaultFetchTimeout: FiniteDuration = com.chipprbots.ethereum.utils.TxPoolConfig.DefaultAnnouncementFetchTimeout

/** Announced transactions not yet delivered, and which peer each one is being fetched from: go-ethereum's tx fetcher
  * (eth/fetcher/tx_fetcher.go), reduced to what the pool needs.
  *
  *   - A hash is requested from ONE announcer at a time. Every other peer that announces it is kept as an alternate,
  *     with its own (type, size), which is what that peer's own reply is checked against.
  *   - A request not answered within `fetchTimeout` (go-ethereum's `txFetchTimeout`, 5 s) moves to the next alternate
  *     ([[timedOut]]), and so does every request of a peer that is dropped ([[removePeer]]).
  *
  * The pool used to request a hash from every peer that announced it and, once one of them was dropped, from nobody:
  * hive's devp2p `TestBlobTxWithoutSidecar` / `TestBlobTxWithMismatchedSidecar` (rewritten in go-ethereum #35869) have
  * three peers announce one blob tx, serve a bad copy from whichever peer is asked first, and then wait 12 s for the
  * node to ask one of the other two. Their read loop had already swallowed the duplicate requests sent to those two
  * while it waited for the first, and the dropped peer's `PeerDisconnected` only arrives after the 15 s
  * `disconnect-poison-pill-timeout`, so nothing was ever asked again. A request timeout does not depend on that event.
  *
  * Bounded by entry count and by age, as before (#1517): a hash whose announcers never deliver is forgotten after
  * `maxAge` even if every one of them stays connected. Plain mutable state owned by one actor (the pool); a per-peer
  * index makes dropping a peer O(its hashes), and the in-flight queue makes a timeout sweep O(requests timed out).
  */
final class PendingAnnouncements(
    maxEntries: Int,
    maxAge: FiniteDuration,
    fetchTimeout: FiniteDuration = PendingAnnouncements.DefaultFetchTimeout,
    clock: () => Long = () => System.currentTimeMillis()
):
  import PendingAnnouncements.Announcement

  require(maxEntries > 0, "maxEntries must be positive")

  final private class Entry(val announcedAt: Long):
    /** Every live announcer's announcement, in announcement order: the next alternate is the head. */
    val announcers = mutable.LinkedHashMap.empty[PeerId, Announcement]
    var requestedFrom: Option[PeerId] = None

  private val entries = new JLinkedHashMap[ByteString, Entry]()
  private val byPeer = mutable.Map.empty[PeerId, mutable.Set[ByteString]]

  /** Hashes with a request in flight, oldest request first (a re-request moves the hash to the back). */
  private val inFlight = new JLinkedHashMap[ByteString, Long]()
  private val maxAgeMillis = maxAge.toMillis
  private val fetchTimeoutMillis = fetchTimeout.toMillis

  def size: Int = entries.size

  /** `peerId`'s own live announcement of `hash`: what its reply for that hash is checked against. */
  def get(hash: ByteString, peerId: PeerId): Option[Announcement] =
    liveEntry(hash).flatMap(_.announcers.get(peerId))

  /** The peer `hash` is currently being requested from, if any. */
  def requestedFrom(hash: ByteString): Option[PeerId] = liveEntry(hash).flatMap(_.requestedFrom)

  /** Record that `peerId` announced `hash`. True iff the caller must request it from `peerId` now: nobody is being
    * asked for it yet. Otherwise `peerId` is an alternate, asked only if the current request fails.
    */
  def announce(hash: ByteString, txType: Byte, size: BigInt, peerId: PeerId): Boolean =
    val now = clock()
    expire(now)
    val existing = entries.get(hash)
    val entry =
      if existing != null then existing
      else
        val created = new Entry(now)
        entries.put(hash, created)
        created
    entry.announcers.update(peerId, Announcement(txType, size, peerId))
    byPeer.getOrElseUpdate(peerId, mutable.Set.empty) += hash
    val requestNow = entry.requestedFrom.isEmpty
    if requestNow then markRequested(hash, entry, peerId, now)
    while entries.size > maxEntries do removeEldest()
    requestNow

  /** `hash` was delivered: forget it, alternates included. */
  def remove(hash: ByteString): Unit =
    val entry = entries.remove(hash)
    if entry != null then
      inFlight.remove(hash)
      entry.announcers.keysIterator.foreach(unindex(hash, _))

  /** `peerId` is gone (or dropped for misbehaving): forget its announcements, and move every request it had to the next
    * alternate. Returns the requests to send, by peer.
    */
  def removePeer(peerId: PeerId): Map[PeerId, Seq[ByteString]] =
    val now = clock()
    val reassigned = mutable.LinkedHashMap.empty[PeerId, mutable.ArrayBuffer[ByteString]]
    byPeer
      .remove(peerId)
      .foreach(_.foreach { hash =>
        val entry = entries.get(hash)
        if entry != null then
          entry.announcers -= peerId
          if entry.requestedFrom.contains(peerId) then reassign(hash, entry, now, reassigned)
      })
    reassigned.view.mapValues(_.toSeq).toMap

  /** Requests unanswered for longer than the fetch timeout: each requester loses its claim on the hash (it is not asked
    * again), and the hash moves to the next alternate. A hash with no alternate left is forgotten. Returns the requests
    * to send, by peer.
    */
  def timedOut(): Map[PeerId, Seq[ByteString]] =
    val now = clock()
    expire(now)
    val reassigned = mutable.LinkedHashMap.empty[PeerId, mutable.ArrayBuffer[ByteString]]
    var continue = true
    while continue && !inFlight.isEmpty do
      val oldest = inFlight.entrySet().iterator().next()
      if now - oldest.getValue <= fetchTimeoutMillis then continue = false
      else
        val hash = oldest.getKey
        inFlight.remove(hash)
        val entry = entries.get(hash)
        if entry != null then
          entry.requestedFrom.foreach { stale =>
            entry.announcers -= stale
            unindex(hash, stale)
          }
          reassign(hash, entry, now, reassigned)
    reassigned.view.mapValues(_.toSeq).toMap

  def clear(): Unit =
    entries.clear()
    byPeer.clear()
    inFlight.clear()

  private def liveEntry(hash: ByteString): Option[Entry] =
    val entry = entries.get(hash)
    if entry == null || clock() - entry.announcedAt > maxAgeMillis then None else Some(entry)

  private def markRequested(hash: ByteString, entry: Entry, peerId: PeerId, now: Long): Unit =
    entry.requestedFrom = Some(peerId)
    inFlight.remove(hash)
    inFlight.put(hash, now)

  /** Ask the next alternate for `hash`, or forget the hash when there is none. */
  private def reassign(
      hash: ByteString,
      entry: Entry,
      now: Long,
      out: mutable.LinkedHashMap[PeerId, mutable.ArrayBuffer[ByteString]]
  ): Unit =
    entry.announcers.headOption match
      case Some((next, _)) =>
        markRequested(hash, entry, next, now)
        out.getOrElseUpdate(next, mutable.ArrayBuffer.empty) += hash
      case None =>
        remove(hash)

  private def unindex(hash: ByteString, peerId: PeerId): Unit =
    byPeer.get(peerId).foreach { hashes =>
      hashes -= hash
      if hashes.isEmpty then byPeer -= peerId
    }

  private def removeEldest(): Unit =
    remove(entries.entrySet().iterator().next().getKey)

  private def expire(now: Long): Unit =
    var continue = true
    while continue && !entries.isEmpty do
      val eldest = entries.entrySet().iterator().next()
      if now - eldest.getValue.announcedAt > maxAgeMillis then remove(eldest.getKey)
      else continue = false
