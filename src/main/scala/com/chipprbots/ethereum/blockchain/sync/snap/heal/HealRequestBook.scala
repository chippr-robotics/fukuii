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
  * Actor-confined: not thread-safe.
  */
final class HealRequestBook:
  import HealRequestBook.*

  private val requests = mutable.HashMap.empty[ByteString, HealRequest]
  private val waiting = mutable.HashMap.empty[ByteString, mutable.ArrayBuffer[ByteString]]
  private val parentEdges = mutable.HashMap.empty[ByteString, mutable.ArrayBuffer[ByteString]]
  private val outstanding = mutable.HashMap.empty[ByteString, Int]

  /** Live requests. */
  def size: Int = requests.size

  def isEmpty: Boolean = requests.isEmpty

  def contains(key: ByteString): Boolean = requests.contains(key)

  def get(key: ByteString): Option[HealRequest] = requests.get(key)

  /** Whether a fetch of `hash` is live: some request waits on it. */
  def isFetching(hash: ByteString): Boolean = waiting.contains(hash)

  /** The hashes with a live fetch. */
  def fetchingHashes: collection.Set[ByteString] = waiting.keySet

  /** The live requests waiting on `hash`, in the order they were recorded. */
  def waitingOn(hash: ByteString): Seq[HealRequest] =
    waiting.get(hash).fold(Seq.empty[HealRequest])(_.iterator.flatMap(requests.get).toSeq)

  /** The parents with an edge to request `key`, one entry per edge. */
  def parentsOf(key: ByteString): Seq[ByteString] = parentEdges.get(key).fold(Seq.empty[ByteString])(_.toSeq)

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
        parentEdges.getOrElseUpdate(child.key, mutable.ArrayBuffer.empty) += parent
        if added == Added.NewFetch then Some(child) else None
      }

  /** Request `key` was satisfied (its node is stored, or for Hash buffered for the store): drop it and decrement each
    * of its parent edges. Returns the parents whose outstanding count reached 0, each once, in edge order.
    */
  def satisfy(key: ByteString): Seq[ByteString] =
    requests.remove(key) match
      case None => Seq.empty
      case Some(request) =>
        waiting.get(request.hash).foreach { keys =>
          keys -= key
          if keys.isEmpty then waiting.remove(request.hash)
        }
        parentEdges.remove(key).fold(Seq.empty[ByteString]) { parents =>
          val done = mutable.ArrayBuffer.empty[ByteString]
          parents.foreach { parent =>
            outstanding.get(parent).foreach { n =>
              if n <= 1 then
                outstanding.remove(parent)
                done += parent
              else outstanding(parent) = n - 1
            }
          }
          done.toSeq
        }

  /** Drop everything: a pivot move or a force-complete (FR-010: discovery restarts from the new root). */
  def clear(): Unit =
    requests.clear()
    waiting.clear()
    parentEdges.clear()
    outstanding.clear()

  private def record(request: HealRequest): Added =
    if requests.contains(request.key) then Added.Duplicate
    else
      requests(request.key) = request
      waiting.get(request.hash) match
        case Some(keys) =>
          keys += request.key
          Added.Joined
        case None =>
          waiting(request.hash) = mutable.ArrayBuffer(request.key)
          Added.NewFetch

object HealRequestBook:

  /** What recording a request did. */
  enum Added:
    /** A request with this key is already live; nothing was recorded (an edge, if any, goes to the live one). */
    case Duplicate

    /** Recorded; a fetch of its hash is already live, so the served node fills it too (a Path alias). */
    case Joined

    /** Recorded, and its hash needs a fetch: the caller queues one. */
    case NewFetch
