package com.chipprbots.ethereum.blockchain.sync.snap.heal

import org.apache.pekko.util.ByteString

import scala.collection.mutable

import org.bouncycastle.util.encoders.Hex

/** The live heal requests of one pivot root (spec 022 D2, data-model.md "Heal request"): every request not yet
  * satisfied, whether still queued or in flight; the requests waiting on each node hash; and, per parent, how many
  * child edges are still outstanding.
  *
  * '''One fetch per hash.''' A node hash is fetched once however many requests wait on it, and a served node fills all
  * of them. Under the Path scheme those are positions (aliases: an identical subtree under two accounts, say); under
  * the Hash scheme there is one request per hash. The caller keeps one queue or in-flight entry per hash: [[add]] and
  * [[addChildren]] say when a request needs a new one.
  *
  * '''Live means pending or in flight.''' A request stays here from when it is recorded until [[satisfy]] (or
  * [[clear]], on a pivot move or force-complete). Dispatching it does not remove it, so a second parent that finds the
  * child while it is in flight joins the request already out instead of fetching it again.
  *
  * '''Parent edges.''' Each missing child a healed node references is one edge from that node (the parent, by its own
  * request key) to the child's request. A Hash-scheme child shared by two parents, or referenced twice by one, has
  * several edges, and its arrival decrements each. Slice 1 only counts them; slice 2 holds a parent until its count is
  * 0 (D3).
  *
  * '''A position wanted for another node.''' Under the Path scheme a request is keyed by position alone, so recording a
  * request whose position is live with a DIFFERENT hash is a conflict ([[HealRequestBook.Added.Conflict]], logged at
  * warn). The newer request replaces the live one, last writer wins as in geth `trie/sync.go` `scheduleNodeRequest`:
  * within one pivot root a position has one hash, the book is cleared on every re-peg, and the walk batches of a
  * superseded root are dropped before they reach it (review W1), so the newer request is the current root's. Keeping
  * the older one instead could never recover: the current root's walk would offer the right hash again and be refused
  * again. The replaced request's parent edges stay (they wait on the position). If it was the last request waiting on
  * its hash, that hash is retired: its fetch, still queued or in flight, fills nothing when it comes back and is not
  * queued again ([[isRetired]]); it costs at most one more round trip. Within one root this should never happen.
  *
  * '''Cost.''' Every operation is O(1) per request or edge, aliases included: removing one of many positions waiting on
  * a hash does not scan the others. The common case, one request per hash and one or two edges per child, allocates no
  * collection: the first request waiting on a hash is held directly, a set exists only for further ones (Path aliases),
  * and edges are an immutable list.
  *
  * Actor-confined: not thread-safe.
  */
final class HealRequestBook:
  import HealRequestBook.*

  private val requests = mutable.HashMap.empty[ByteString, HealRequest]
  // hash -> the first live request waiting on it (the fetch is queued with its pathset), and the further requests
  // waiting on the same hash, in the order they were recorded (Path aliases; absent when there are none).
  private val firstWaiting = mutable.HashMap.empty[ByteString, ByteString]
  private val moreWaiting = mutable.HashMap.empty[ByteString, mutable.LinkedHashSet[ByteString]]
  // child key -> its parents, one entry per edge, newest first.
  private val parentEdges = mutable.HashMap.empty[ByteString, List[ByteString]]
  private val outstanding = mutable.HashMap.empty[ByteString, Int]
  // hashes whose last waiting request was replaced by a conflicting one (see the class comment)
  private val retired = mutable.HashSet.empty[ByteString]

  /** Live requests. */
  def size: Int = requests.size

  def isEmpty: Boolean = requests.isEmpty

  def contains(key: ByteString): Boolean = requests.contains(key)

  def get(key: ByteString): Option[HealRequest] = requests.get(key)

  /** Whether a fetch of `hash` is live: some request waits on it. */
  def isFetching(hash: ByteString): Boolean = firstWaiting.contains(hash)

  /** Whether `hash` lost its last waiting request to a conflicting one: its fetch, if it comes back, fills nothing and
    * is not an error. Cleared when a request waits on the hash again, and by [[clear]].
    */
  def isRetired(hash: ByteString): Boolean = retired.contains(hash)

  /** The hashes with a live fetch. */
  def fetchingHashes: collection.Set[ByteString] = firstWaiting.keySet

  /** The live requests waiting on `hash`, in the order they were recorded. */
  def waitingOn(hash: ByteString): Seq[HealRequest] =
    firstWaiting.get(hash) match
      case None => Seq.empty
      case Some(first) =>
        (Iterator.single(first) ++ moreWaiting.get(hash).iterator.flatMap(_.iterator)).flatMap(requests.get).toSeq

  /** The first live request waiting on `hash` (the one its fetch is queued with), in O(1). */
  def firstWaiter(hash: ByteString): Option[HealRequest] = firstWaiting.get(hash).flatMap(requests.get)

  /** Whether a fetch of `hash` frees held memory: some request waiting on it has a parent edge, i.e. a held parent
    * waits on it (spec 022 D1, the pause policy). False for a fetch whose every request opens a new subtree.
    */
  def fetchFreesHeld(hash: ByteString): Boolean =
    firstWaiting.get(hash).exists { first =>
      parentEdges.contains(first) || moreWaiting.get(hash).exists(_.exists(parentEdges.contains))
    }

  /** The parents with an edge to request `key`, one entry per edge, in the order the edges were added. */
  def parentsOf(key: ByteString): Seq[ByteString] = parentEdges.get(key).fold(Seq.empty[ByteString])(_.reverse)

  /** How many of `parent`'s child edges are still outstanding. */
  def outstandingChildren(parent: ByteString): Int = outstanding.getOrElse(parent, 0)

  /** True for a request no recorded parent waits on: a root, a re-seed, an entry from a walk or the controller. Slice 2
    * pauses these at the held-memory cap (D1).
    */
  def opensNewSubtree(key: ByteString): Boolean = !parentEdges.contains(key)

  /** Record `request` with no parent edge (a root, a re-seed, a walk or controller entry). */
  def add(request: HealRequest): Added = record(request)

  /** Record the missing children of the healed node `parent` (its own request key), one entry per child edge. A child
    * already live gains the edge; a new one is recorded. Returns the children that need a new fetch, in order.
    *
    * A parent already recorded with outstanding children (a node processed a second time, e.g. re-queued by a walk)
    * gains edges only to children that are not live: its live children already carry its edges, so they are counted
    * once, while a child that is neither live nor stored (say, one lost with a failed buffered write, #1549) is
    * requested again. A child whose position is live with another hash replaces that request (a conflict, see the class
    * comment) and needs a fetch unless its hash is already being fetched.
    */
  def addChildren(parent: ByteString, children: Seq[HealRequest]): Seq[HealRequest] =
    val fresh =
      if outstanding.contains(parent) then children.filterNot(child => requests.contains(child.key)) else children
    if fresh.isEmpty then Seq.empty
    else
      outstanding(parent) = outstanding.getOrElse(parent, 0) + fresh.size
      fresh.flatMap { child =>
        val added = record(child)
        parentEdges(child.key) = parent :: parentEdges.getOrElse(child.key, Nil)
        if added.needsFetch then Some(child) else None
      }

  /** `request` was satisfied (its node is stored, or for Hash buffered for the store): drop it and decrement each of
    * its parent edges. Returns the parents whose outstanding count reached 0, each once, in edge order.
    *
    * Acts only when the live request under `request.key` expects `request.hash`: a node fills only the request it
    * hashes to, never a position now expecting another node (review W2). Otherwise nothing changes.
    */
  def satisfy(request: HealRequest): Seq[ByteString] =
    val key = request.key
    requests.get(key) match
      case Some(live) if live.hash == request.hash =>
        requests.remove(key)
        removeWaiting(request.hash, key)
        parentEdges.remove(key).fold(Seq.empty[ByteString]) { parents =>
          val done = mutable.ArrayBuffer.empty[ByteString]
          parents.reverseIterator.foreach { parent =>
            outstanding.get(parent).foreach { n =>
              if n <= 1 then
                outstanding.remove(parent)
                done += parent
              else outstanding(parent) = n - 1
            }
          }
          done.toSeq
        }
      case _ => Seq.empty

  /** Drop everything: a pivot move or a force-complete (FR-010: discovery restarts from the new root). */
  def clear(): Unit =
    requests.clear()
    firstWaiting.clear()
    moreWaiting.clear()
    parentEdges.clear()
    outstanding.clear()
    retired.clear()

  private def record(request: HealRequest): Added =
    requests.get(request.key) match
      case Some(live) if live.hash == request.hash => Added.Duplicate
      case Some(live) =>
        requests(request.key) = request
        if removeWaiting(live.hash, request.key) then retired += live.hash
        val newFetch = addWaiting(request.hash, request.key)
        log.warn(
          s"[HEAL-KEY-CONFLICT] Position ${Hex.toHexString(request.key.take(8).toArray)} was requested for node " +
            s"${Hex.toHexString(live.hash.take(4).toArray)} and is now requested for node " +
            s"${Hex.toHexString(request.hash.take(4).toArray)}: the newer request replaces it (last writer wins)"
        )
        Added.Conflict(live, newFetch)
      case None =>
        requests(request.key) = request
        if addWaiting(request.hash, request.key) then Added.NewFetch else Added.Joined

  /** Add `key` to the requests waiting on `hash`. True when it is the first, i.e. the hash needs a fetch. */
  private def addWaiting(hash: ByteString, key: ByteString): Boolean =
    if firstWaiting.contains(hash) then
      moreWaiting.getOrElseUpdate(hash, mutable.LinkedHashSet.empty) += key
      false
    else
      firstWaiting(hash) = key
      retired -= hash
      true

  /** Remove `key` from the requests waiting on `hash`, in O(1). When it was the first, the next one in order takes its
    * place. True when no request waits on `hash` any more.
    */
  private def removeWaiting(hash: ByteString, key: ByteString): Boolean =
    firstWaiting.get(hash) match
      case None => true
      case Some(first) if first == key =>
        moreWaiting.get(hash) match
          case Some(more) if more.nonEmpty =>
            val next = more.head
            more -= next
            if more.isEmpty then moreWaiting.remove(hash)
            firstWaiting(hash) = next
            false
          case _ =>
            firstWaiting.remove(hash)
            moreWaiting.remove(hash)
            true
      case Some(_) =>
        moreWaiting.get(hash).foreach { more =>
          more -= key
          if more.isEmpty then moreWaiting.remove(hash)
        }
        false

object HealRequestBook:

  private val log = org.slf4j.LoggerFactory.getLogger(classOf[HealRequestBook])

  /** What recording a request did. */
  enum Added:
    /** A request with this key and this hash is already live; nothing was recorded (an edge, if any, goes to it). */
    case Duplicate

    /** Recorded; a fetch of its hash is already live, so the served node fills it too (a Path alias). */
    case Joined

    /** Recorded, and its hash needs a fetch: the caller queues one. */
    case NewFetch

    /** Recorded in place of `previous`, the live request for the same position with another hash (see the class
      * comment). `newFetch`: its hash needs a fetch.
      */
    case Conflict(previous: HealRequest, newFetch: Boolean)

    /** Whether the caller must queue a fetch for the recorded request's hash. */
    def needsFetch: Boolean = this match
      case NewFetch              => true
      case Conflict(_, newFetch) => newFetch
      case _                     => false
