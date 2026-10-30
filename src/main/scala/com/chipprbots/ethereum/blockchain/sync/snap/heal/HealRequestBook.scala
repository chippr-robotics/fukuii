package com.chipprbots.ethereum.blockchain.sync.snap.heal

import org.apache.pekko.util.ByteString

import scala.collection.mutable

import org.bouncycastle.util.encoders.Hex

/** The live heal requests of one pivot root (spec 022 D2, data-model.md "Heal request"): every request not yet
  * satisfied, whether still queued or in flight; the requests waiting on each node hash; and the parent edges of each.
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
  * '''Parent edges.''' Each missing child a healed node references is one edge from that node (the parent, by its held
  * id, see [[HeldNodeSet.idOf]]) to the child's request. A Hash-scheme child shared by two parents, or referenced twice
  * by one, has several edges. [[satisfy]] hands a filled request's edges to the caller, which moves them to the held
  * node (or commits it): a parent's count goes down only when the child's whole subtree is committed (spec 022 slice 2,
  * forge N5), and that count lives in [[HeldNodeSet]], not here.
  *
  * '''A position wanted for another node.''' Under the Path scheme a request is keyed by position alone, so recording a
  * request whose position is live with a DIFFERENT hash is a conflict ([[HealRequestBook.Added.Conflict]], logged at
  * warn, throttled). Only a parent's child ([[addChildren]]) may replace a live request; a parentless entry (a root, a
  * walk or controller entry) never replaces one that a held parent waits on, and is dropped instead
  * ([[HealRequestBook.Added.Refused]], beacon N1), so the outcome does not depend on which arrives first. Otherwise the
  * newer request replaces the live one, last writer wins as in geth `trie/sync.go` `scheduleNodeRequest`: within one
  * pivot root a position has one hash, the book is cleared on every re-peg, and the walk batches of a superseded root
  * are dropped before they reach it (review W1). The replaced request's parent edges are dropped with it: those parents
  * expect the other node there, and a node they do not reference must never close their count (they stay held, and are
  * dropped if nothing else completes them). If it was the last request waiting on its hash, that hash is retired: its
  * fetch, still queued or in flight, fills nothing when it comes back and is not queued again ([[isRetired]]); it costs
  * at most one more round trip. Within one root this should never happen.
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
  // child key -> its parents' held ids, one entry per edge, newest first.
  private val parentEdges = mutable.HashMap.empty[ByteString, List[ByteString]]
  // hashes whose last waiting request was replaced by a conflicting one (see the class comment)
  private val retired = mutable.HashSet.empty[ByteString]
  // Estimated heap of the live requests and their edges (spec 022 D1: counted against the held-memory bound).
  private var heldBytes: Long = 0L
  private var conflicts: Long = 0L

  /** Live requests. */
  def size: Int = requests.size

  /** Estimated heap of the live requests and their parent edges, in bytes (forge N4: four map entries and the edge list
    * per request).
    */
  def bytes: Long = heldBytes

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

  /** True for a request no recorded parent waits on: a root, a re-seed, an entry from a walk or the controller. Slice 2
    * pauses these at the held-memory cap (D1).
    */
  def opensNewSubtree(key: ByteString): Boolean = !parentEdges.contains(key)

  /** Record `request` with no parent edge (a root, a re-seed, a walk or controller entry). It never replaces a live
    * request a held parent waits on (beacon N1): that one is the current root's child, and the entry is dropped.
    */
  def add(request: HealRequest): Added =
    requests.get(request.key) match
      case Some(live) if live.hash != request.hash && parentEdges.contains(request.key) =>
        noteConflict(
          s"[HEAL-KEY-CONFLICT] Position ${hex(request.key, 8)} is requested for node ${hex(live.hash, 4)} by a held " +
            s"parent; a parentless request for node ${hex(request.hash, 4)} there is dropped"
        )
        Added.Refused(live)
      case _ => record(request)

  /** Record the missing children of the healed node `parent` (its held id), one edge per reference: a child referenced
    * `m` times gets `m` edges from `parent`, less those it already has from `parent` (forge N1), so a parent processed
    * twice is counted once and a parent's edge to a shared child is never confused with another parent's. A child
    * already live gains the edges; a new one is recorded. Returns the children that need a new fetch, in order, each
    * once.
    *
    * A child whose position is live with another hash replaces that request (a conflict, see the class comment) and
    * needs a fetch unless its hash is already being fetched.
    */
  def addChildren(parent: ByteString, children: Seq[HealRequest]): Seq[HealRequest] =
    val multiplicity = mutable.LinkedHashMap.empty[ByteString, (HealRequest, Int)]
    children.foreach { c =>
      multiplicity(c.key) = multiplicity.get(c.key).fold((c, 1)) { case (first, n) => (first, n + 1) }
    }
    multiplicity.valuesIterator.flatMap { case (child, m) =>
      val added = record(child)
      val have = parentEdges.get(child.key).fold(0)(_.count(_ == parent))
      val add = math.max(0, m - have)
      if add > 0 then
        parentEdges(child.key) = List.fill(add)(parent) ++ parentEdges.getOrElse(child.key, Nil)
        heldBytes += EdgeBytes * add
      if added.needsFetch then Some(child) else None
    }.toSeq

  /** `request` was satisfied (its node arrived): drop it and return its parent edges, one per edge, in the order they
    * were added. The caller moves them to the held node, or closes them when the node commits; nothing is decremented
    * here (forge N5).
    *
    * Acts only when the live request under `request.key` expects `request.hash`: a node fills only the request it
    * hashes to, never a position now expecting another node (review W2). Otherwise nothing changes and nothing is
    * returned.
    */
  def satisfy(request: HealRequest): Seq[ByteString] =
    val key = request.key
    requests.get(key) match
      case Some(live) if live.hash == request.hash =>
        requests.remove(key)
        heldBytes -= requestBytes(live)
        removeWaiting(request.hash, key)
        dropEdges(key).reverse
      case _ => Seq.empty

  /** Put back `request`, satisfied a moment ago, with the parent edges [[satisfy]] returned for it (`parents`, in that
    * order): its node arrived, but its children could not be looked up because a storage read failed, so it is fetched
    * again (spec 022 review forge W1). Its parents still wait on it. The caller queues the fetch, once per hash, after
    * every request the served node filled has been processed: a Path alias put back while another alias still waits
    * only joins the fetch that just came back, which is spent (spec 022 review hunter W1-r2).
    */
  def restore(request: HealRequest, parents: Seq[ByteString]): Unit =
    record(request): Unit
    if parents.nonEmpty then
      parentEdges(request.key) = parents.reverse.toList ++ parentEdges.getOrElse(request.key, Nil)
      heldBytes += EdgeBytes * parents.size

  /** The waiter after the one whose pathset is `carrier` among the live requests waiting on `hash`, cycling back to the
    * first (beacon Q1: a fetch put back goes out from the next alias position, so a peer that cannot serve one path
    * does not starve the others). O(1) when a single request waits on `hash`.
    */
  def nextWaiter(hash: ByteString, carrier: Seq[ByteString]): Option[HealRequest] =
    moreWaiting.get(hash) match
      case None => firstWaiter(hash)
      case Some(_) =>
        val all = waitingOn(hash)
        val at = all.indexWhere(_.pathset == carrier)
        if all.isEmpty then None else Some(all((at + 1) % all.size))

  /** Drop everything: a pivot move or a force-complete (FR-010: discovery restarts from the new root). */
  def clear(): Unit =
    requests.clear()
    firstWaiting.clear()
    moreWaiting.clear()
    parentEdges.clear()
    retired.clear()
    heldBytes = 0L

  private def record(request: HealRequest): Added =
    requests.get(request.key) match
      case Some(live) if live.hash == request.hash => Added.Duplicate
      case Some(live) =>
        requests(request.key) = request
        heldBytes += requestBytes(request) - requestBytes(live)
        if removeWaiting(live.hash, request.key) then retired += live.hash
        // The replaced request's parents expect the other node there (see the class comment).
        val dropped = dropEdges(request.key)
        val newFetch = addWaiting(request.hash, request.key)
        noteConflict(
          s"[HEAL-KEY-CONFLICT] Position ${hex(request.key, 8)} was requested for node ${hex(live.hash, 4)} and is now " +
            s"requested for node ${hex(request.hash, 4)}: the newer request replaces it (last writer wins); " +
            s"${dropped.size} parent edge(s) to the old node dropped"
        )
        Added.Conflict(live, newFetch)
      case None =>
        requests(request.key) = request
        heldBytes += requestBytes(request)
        if addWaiting(request.hash, request.key) then Added.NewFetch else Added.Joined

  private def dropEdges(key: ByteString): List[ByteString] =
    parentEdges.remove(key) match
      case Some(edges) =>
        heldBytes -= EdgeBytes * edges.size
        edges
      case None => Nil

  /** A conflict is logged at warn for the first [[ConflictWarnLimit]], then once every [[ConflictWarnEvery]] (beacon
    * N5: a frontier resume can emit many).
    */
  private def noteConflict(message: => String): Unit =
    conflicts += 1
    if conflicts <= ConflictWarnLimit || conflicts % ConflictWarnEvery == 0 then
      log.warn(s"$message (conflict #$conflicts)")
    else log.debug(message)

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

  private val ConflictWarnLimit: Long = 10L
  private val ConflictWarnEvery: Long = 1000L

  // Heap estimates (compressed oops): the HealRequest and its pathset Seq, the requests and firstWaiting map entries,
  // the ByteString wrappers; one parent edge (a list cell).
  private val RequestBytes: Long = 320L
  private val EdgeBytes: Long = 24L

  private def requestBytes(r: HealRequest): Long =
    RequestBytes + r.key.length + r.hash.length + r.pathset.iterator.map(_.length + 16L).sum

  private def hex(b: ByteString, n: Int): String = Hex.toHexString(b.take(n).toArray)

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

    /** Not recorded: a parentless request for a position a held parent waits on for `live`, another node (beacon N1).
      */
    case Refused(live: HealRequest)

    /** Whether the caller must queue a fetch for the recorded request's hash. */
    def needsFetch: Boolean = this match
      case NewFetch              => true
      case Conflict(_, newFetch) => newFetch
      case _                     => false
