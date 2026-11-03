package com.chipprbots.ethereum.blockchain.sync.snap.heal

import org.apache.pekko.util.ByteString

import scala.annotation.tailrec
import scala.collection.immutable.Queue

import com.chipprbots.ethereum.mpt.MptNode

/** Finds where a missing storage node hangs, when the exception that reported it could not say (#1694). A fetch by
  * position needs that, and the hole is referenced from a node that is present, so a walk of what is present finds it.
  */
object StorageHolePosition:

  /** Reads before giving up, since this runs on the importer's thread. Breadth first, the first 273 read every node
    * down to depth 2, so every hole down to depth 3 is found, and the rest find most at depth 4: a trie of some
    * thousands of slots. A token's or an exchange's trie is deeper, and a hole in it is mostly not found.
    */
  val MaxReads: Int = 4096

  /** The pathset of `hole` under `storageRoot`, breadth first, so a bounded walk covers the shallow levels whole. None
    * where nothing read refers to it, within `maxReads`.
    */
  def locate(
      accountHash: ByteString,
      storageRoot: ByteString,
      hole: ByteString,
      read: ByteString => Option[MptNode],
      maxReads: Int = MaxReads
  ): Option[Seq[ByteString]] =
    val rootAt = AuditPosition.storageRootOf(accountHash)

    @tailrec def walk(queue: Queue[AuditFrame], reads: Int): Option[Seq[ByteString]] =
      queue.dequeueOption match
        case None                         => None
        case Some(_) if reads >= maxReads => None
        case Some((frame, rest)) =>
          val children =
            frame.hash.flatMap(read).fold(Vector.empty[AuditFrame])(AuditWalk.expand(_, frame.position).children)
          children.find(_.hash.contains(hole)) match
            case Some(found) => Some(found.position.pathset)
            case None        => walk(rest ++ children, reads + 1)

    if storageRoot == hole then Some(rootAt.pathset)
    else walk(Queue(AuditFrame(rootAt, Some(storageRoot))), 0)

  /** The pathsets to ask for a storage hole. Most carry their position. One met writing does not: deleting a slot
    * collapses a branch by reading its sibling, which no read of the slot passed through. Asking for the storage root
    * then, whatever the hole was, drew an honest reply of the wrong node, and the peer was blacklisted for it, so such
    * a hole went through every peer to the re-SNAP (beacon, #1694). None where it cannot be found, and the importer
    * then asks no peer at all: no answer is better than blaming a peer for ours.
    */
  def paths(
      location: Option[ByteString],
      accountHash: ByteString,
      hole: ByteString,
      storageRoot: => Option[ByteString],
      read: ByteString => Option[MptNode]
  ): Option[Seq[Seq[ByteString]]] =
    location
      .map(loc => Seq(Seq(accountHash, loc)))
      .orElse(storageRoot.flatMap(root => locate(accountHash, root, hole, read)).map(Seq(_)))
