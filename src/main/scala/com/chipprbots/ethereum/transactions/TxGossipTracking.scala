package com.chipprbots.ethereum.transactions

import java.util.LinkedHashMap as JLinkedHashMap

import org.apache.pekko.util.ByteString

import scala.collection.mutable
import scala.concurrent.duration.*

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

  /** The most hashes one GetPooledTransactions asks for: go-ethereum's `maxTxRetrievals` (eth/fetcher/tx_fetcher.go,
    * 256 at c6e3a0d7, 2026-09-23), "a failsafe against malicious announces which might cause us to request more data
    * than we'd expect".
    */
  val MaxTxRetrievals: Int = 256

  /** The most announced bytes one GetPooledTransactions asks for: go-ethereum's `maxTxRetrievalSize` (128 KiB at
    * c6e3a0d7). Counted from the requested peer's own announced sizes, and checked after a hash is added, as
    * go-ethereum does: a request always holds at least one hash, however large its tx (B-028).
    */
  val MaxTxRetrievalBytes: Long = 128L * 1024

/** Announced transactions not yet delivered, which peer each one is being fetched from, and which peers have a request
  * in flight: go-ethereum's tx fetcher (eth/fetcher/tx_fetcher.go), reduced to what the pool needs.
  *
  *   - A peer has at most ONE GetPooledTransactions in flight (B-028, go-ethereum's `requests[peer]`). [[schedule]]
  *     builds a peer's next request only while it has none, from the hashes it announced that nobody is fetching: at
  *     most [[PendingAnnouncements.MaxTxRetrievals]] hashes and [[PendingAnnouncements.MaxTxRetrievalBytes]] announced
  *     bytes. The peer is free again once it answers ([[delivered]]) or is gone ([[removePeer]]).
  *   - A hash is requested from ONE announcer at a time. Every other peer that announces it is kept as an alternate,
  *     with its own (type, size), which is what that peer's own reply is checked against.
  *   - A request not answered within `fetchTimeout` (go-ethereum's `txFetchTimeout`, 5 s) gives its hashes up
  *     ([[timedOut]]): the requester is no longer an announcer of them, and the alternates may take them. The request
  *     itself stays the peer's one request in flight until the peer answers or is gone, as go-ethereum keeps it
  *     ("dangling, but never expire"), so a peer that stopped answering is asked for nothing more.
  *   - A hash a peer was asked for and left out of its answer is one it does not have: it stops being that peer's
  *     announcement, and the alternates may take it (go-ethereum's delivery cleanup).
  *
  * Each call that can make work for a peer returns the peers to [[schedule]]; the caller asks each one.
  *
  * Bounded by entry count and by age, as before (#1517): a hash whose announcers never deliver is forgotten after
  * `maxAge` even if every one of them stays connected. Plain mutable state owned by one actor (the pool); a per-peer
  * index makes dropping a peer O(its hashes), and the in-flight queue makes a timeout sweep O(requests timed out).
  */
final class PendingAnnouncements(
    maxEntries: Int,
    maxAge: FiniteDuration,
    fetchTimeout: FiniteDuration = PendingAnnouncements.DefaultFetchTimeout,
    clock: () => Long = () => System.currentTimeMillis(),
    maxRetrievals: Int = PendingAnnouncements.MaxTxRetrievals,
    maxRetrievalBytes: Long = PendingAnnouncements.MaxTxRetrievalBytes
):
  import PendingAnnouncements.Announcement

  require(maxEntries > 0, "maxEntries must be positive")
  require(maxRetrievals > 0, "maxRetrievals must be positive")

  final private class Entry(val announcedAt: Long):
    /** Every live announcer's announcement, in announcement order. */
    val announcers: mutable.LinkedHashMap[PeerId, Announcement] = mutable.LinkedHashMap.empty[PeerId, Announcement]
    var requestedFrom: Option[PeerId] = None

  private val entries = new JLinkedHashMap[ByteString, Entry]()

  /** Each peer's tracked announcements, in announcement order: the order its requests take them in. */
  private val byPeer = mutable.Map.empty[PeerId, mutable.LinkedHashSet[ByteString]]

  /** Each peer's one request in flight: its request id and the hashes it was asked for. A timed-out request stays here,
    * emptied of the hashes it gave up, until the peer answers it or is gone.
    */
  final private class Request(val id: BigInt, val hashes: mutable.Set[ByteString])
  private val requests = mutable.Map.empty[PeerId, Request]

  /** Hashes with a request in flight, oldest request first. */
  private val inFlight = new JLinkedHashMap[ByteString, Long]()
  private val maxAgeMillis = maxAge.toMillis
  private val fetchTimeoutMillis = fetchTimeout.toMillis

  def size: Int = entries.size

  /** `peerId`'s own live announcement of `hash`: what its reply for that hash is checked against. */
  def get(hash: ByteString, peerId: PeerId): Option[Announcement] =
    liveEntry(hash).flatMap(_.announcers.get(peerId))

  /** The peer `hash` is currently being requested from, if any. */
  def requestedFrom(hash: ByteString): Option[PeerId] = liveEntry(hash).flatMap(_.requestedFrom)

  /** Whether `peerId` has a request in flight (a timed-out one included, until it answers). */
  def busy(peerId: PeerId): Boolean = requests.contains(peerId)

  /** How many hashes `peerId` has announced that are still tracked: those requested from it and those it is an
    * alternate for (go-ethereum's `used` in the tx fetcher, checked against `maxTxAnnounces`). O(1).
    */
  def announcedBy(peerId: PeerId): Int = byPeer.get(peerId).fold(0)(_.size)

  /** Record that `peerId` announced `hash`. Nothing is requested here: [[schedule]] `peerId` afterwards. */
  def announce(hash: ByteString, txType: Byte, size: BigInt, peerId: PeerId): Unit =
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
    byPeer.getOrElseUpdate(peerId, mutable.LinkedHashSet.empty) += hash
    while entries.size > maxEntries do removeEldest()

  /** `peerId`'s next request, if it has none in flight: the hashes it announced that nobody is being asked for, in
    * announcement order, at most `maxRetrievals` of them and `maxRetrievalBytes` of its announced sizes (the hash that
    * reaches the byte cap is included). They are recorded as requested from `peerId` now; the caller must send them.
    * Empty when the peer is busy or has nothing to fetch.
    */
  def schedule(peerId: PeerId, requestId: BigInt): Seq[ByteString] =
    if requests.contains(peerId) then Seq.empty
    else
      val now = clock()
      expire(now)
      val picked = mutable.ArrayBuffer.empty[ByteString]
      var bytes = BigInt(0)
      byPeer.get(peerId).foreach { hashes =>
        val it = hashes.iterator
        while it.hasNext && picked.size < maxRetrievals && bytes < maxRetrievalBytes do
          val hash = it.next()
          val entry = entries.get(hash)
          if entry != null && entry.requestedFrom.isEmpty then
            picked += hash
            entry.announcers.get(peerId).foreach(a => bytes += a.size.max(0))
      }
      if picked.nonEmpty then
        picked.foreach { hash =>
          entries.get(hash).requestedFrom = Some(peerId)
          inFlight.remove(hash)
          inFlight.put(hash, now)
        }
        requests.update(peerId, new Request(requestId, mutable.Set.from(picked)))
      picked.toSeq

  /** `peerId` answered request `requestId`. The hashes it delivered are forgotten, alternates included, whatever the
    * request. When it answers the request it has in flight, that request is done: the hashes it was asked for and left
    * out it does not have, so it stops announcing them, and their other announcers may take them (or they are forgotten
    * when there are none). A reply to any other request (a duplicate, or one never asked) ends nothing: it cannot cut
    * short the request in flight. Returns the peers to [[schedule]], `peerId` first; none for such a reply.
    */
  def delivered(peerId: PeerId, requestId: BigInt, hashes: Iterable[ByteString]): Seq[PeerId] =
    hashes.foreach(remove)
    requests.get(peerId) match
      case Some(request) if request.id == requestId =>
        requests -= peerId
        val next = mutable.LinkedHashSet[PeerId](peerId)
        request.hashes.foreach { hash =>
          val entry = entries.get(hash)
          if entry != null && entry.requestedFrom.contains(peerId) then
            inFlight.remove(hash)
            giveUp(hash, entry, peerId, next)
        }
        next.toSeq
      case Some(_) => Seq.empty
      case None    => Seq(peerId)

  /** `peerId` is gone (or dropped for misbehaving): forget its announcements and its request, and give up every hash it
    * was asked for. Returns the peers to [[schedule]].
    */
  def removePeer(peerId: PeerId): Seq[PeerId] =
    requests -= peerId
    val next = mutable.LinkedHashSet.empty[PeerId]
    byPeer
      .remove(peerId)
      .foreach(_.foreach { hash =>
        val entry = entries.get(hash)
        if entry != null then
          entry.announcers -= peerId
          if entry.requestedFrom.contains(peerId) then
            inFlight.remove(hash)
            entry.requestedFrom = None
          if entry.announcers.isEmpty then remove(hash)
          else if entry.requestedFrom.isEmpty then next ++= entry.announcers.keysIterator
      })
    next.toSeq

  private var unanswered: Long = 0L

  /** Every request that went unanswered past the fetch timeout since this was made, whether its hash then moved to
    * another announcer or was forgotten for want of one. Counted for the dashboard (spec 029 FR-034); nothing here
    * reads it.
    */
  def unansweredRequests: Long = unanswered

  /** Hashes requested longer ago than the fetch timeout: each requester stops announcing the hash (it is not asked for
    * it again), and the hash goes to its other announcers, or is forgotten when there are none. The requester's request
    * stays in flight (see the class note). Returns the peers to [[schedule]].
    */
  def timedOut(): Seq[PeerId] =
    val now = clock()
    expire(now)
    val next = mutable.LinkedHashSet.empty[PeerId]
    var continue = true
    while continue && !inFlight.isEmpty do
      val oldest = inFlight.entrySet().iterator().next()
      if now - oldest.getValue <= fetchTimeoutMillis then continue = false
      else
        val hash = oldest.getKey
        inFlight.remove(hash)
        unanswered += 1
        val entry = entries.get(hash)
        if entry != null then
          entry.requestedFrom.foreach { stale =>
            requests.get(stale).foreach(_.hashes -= hash)
            giveUp(hash, entry, stale, next)
          }
    next.toSeq

  def clear(): Unit =
    entries.clear()
    byPeer.clear()
    requests.clear()
    inFlight.clear()

  private def liveEntry(hash: ByteString): Option[Entry] =
    val entry = entries.get(hash)
    if entry == null || clock() - entry.announcedAt > maxAgeMillis then None else Some(entry)

  /** `from` will not deliver `hash`: it stops announcing it, and the other announcers are added to `next` (or the hash
    * is forgotten when there are none).
    */
  private def giveUp(hash: ByteString, entry: Entry, from: PeerId, next: mutable.LinkedHashSet[PeerId]): Unit =
    entry.requestedFrom = None
    entry.announcers -= from
    unindex(hash, from)
    if entry.announcers.isEmpty then remove(hash)
    else next ++= entry.announcers.keysIterator

  /** `hash` was delivered or expired: forget it, alternates included, and take it out of its requester's request. That
    * request stays in flight, emptied or not, until the peer answers or is gone, as a timed-out one does.
    */
  def remove(hash: ByteString): Unit =
    val entry = entries.remove(hash)
    if entry != null then
      inFlight.remove(hash)
      entry.requestedFrom.foreach(requester => requests.get(requester).foreach(_.hashes -= hash))
      entry.announcers.keysIterator.foreach(unindex(hash, _))

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
