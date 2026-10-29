package com.chipprbots.ethereum.blockchain.sync.snap.heal

import org.apache.pekko.util.ByteString

import scala.collection.mutable

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

  /** Live requests. */
  def size: Int = requests.size

  def isEmpty: Boolean = requests.isEmpty

  def contains(key: ByteString): Boolean = requests.contains(key)

  def get(key: ByteString): Option[HealRequest] = requests.get(key)

  /** Whether a fetch of `hash` is live: some request waits on it. */
  def isFetching(hash: ByteString): Boolean = firstWaiting.contains(hash)

  /** The hashes with a live fetch. */
  def fetchingHashes: collection.Set[ByteString] = firstWaiting.keySet

  /** The live requests waiting on `hash`, in the order they were recorded. */
  def waitingOn(hash: ByteString): Seq[HealRequest] =
    firstWaiting.get(hash) match
      case None => Seq.empty
      case Some(first) =>
        (Iterator.single(first) ++ moreWaiting.get(hash).iterator.flatMap(_.iterator)).flatMap(requests.get).toSeq

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
    * adds nothing: its edges are counted once. Under Path a live position keeps the hash it was recorded with: within
    * one root a position has one hash, so a different one can only come from a walk of a superseded root, and the edge
    * goes to the live request.
    */
  def addChildren(parent: ByteString, children: Seq[HealRequest]): Seq[HealRequest] =
    if children.isEmpty || outstanding.contains(parent) then Seq.empty
    else
      outstanding(parent) = children.size
      children.flatMap { child =>
        val added = record(child)
        parentEdges(child.key) = parent :: parentEdges.getOrElse(child.key, Nil)
        if added == Added.NewFetch then Some(child) else None
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

  private def record(request: HealRequest): Added =
    if requests.contains(request.key) then Added.Duplicate
    else
      requests(request.key) = request
      if addWaiting(request.hash, request.key) then Added.NewFetch else Added.Joined

  /** Add `key` to the requests waiting on `hash`. True when it is the first, i.e. the hash needs a fetch. */
  private def addWaiting(hash: ByteString, key: ByteString): Boolean =
    if firstWaiting.contains(hash) then
      moreWaiting.getOrElseUpdate(hash, mutable.LinkedHashSet.empty) += key
      false
    else
      firstWaiting(hash) = key
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

  /** What recording a request did. */
  enum Added:
    /** A request with this key is already live; nothing was recorded (an edge, if any, goes to the live one). */
    case Duplicate

    /** Recorded; a fetch of its hash is already live, so the served node fills it too (a Path alias). */
    case Joined

    /** Recorded, and its hash needs a fetch: the caller queues one. */
    case NewFetch
