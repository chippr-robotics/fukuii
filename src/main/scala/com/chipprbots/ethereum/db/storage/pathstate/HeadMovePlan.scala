package com.chipprbots.ethereum.db.storage.pathstate

import org.apache.pekko.util.ByteString

import scala.annotation.tailrec

import com.chipprbots.ethereum.utils.LazyLogger

/** How the disk's state gets from the block it holds to another: the blocks to reverse, by their changeset records, and
  * the blocks to apply, each from its record or from the layer its execution left (spec 018 data-model 7.3, plan.md
  * "C4b: the move core").
  *
  * '''It is made from the anchor, and from blocks named by their hash.''' The reverse set is the chain of the anchor's
  * own records, each naming its parent; the forward set is the chain of the target's headers, each naming its parent;
  * the two meet at the highest block they share, the '''fork'''. The canonical index of blocks by number is never
  * asked: it is ahead of the anchor when an executed block was indexed and not adopted, and behind it when a side
  * branch was written below the head. Nor is a chain weight: which branch is the heavier is the caller's question, and
  * was answered before it asked for a move.
  *
  * A plan changes nothing and holds no lock. It is of one anchor at one epoch, and whoever writes by it checks both
  * again under the state lock, and that each layer it names is still the one kept.
  *
  * @param anchor
  *   the block whose state the disk held when the plan was made
  * @param epoch
  *   the state's epoch then
  * @param fork
  *   the highest block the anchor's chain and the target's share: the anchor itself when nothing is reversed, the
  *   target when nothing is applied
  * @param reverse
  *   the blocks to reverse, newest first: the anchor, then its parent, down to the block above the fork
  * @param forward
  *   the blocks to apply, oldest first: the block above the fork, up to the target
  */
final case class HeadMovePlan(
    anchor: BlockRef,
    epoch: Long,
    fork: BlockRef,
    target: BlockRef,
    reverse: Vector[HeadMovePlan.Reversed],
    forward: Vector[HeadMovePlan.Forwarded]
):
  import HeadMovePlan.*

  /** How many blocks below the anchor the fork lies. */
  def depth: Int = reverse.size

  /** The target is the anchor: there is nothing to write but what the caller brings. */
  def isNoMove: Boolean = reverse.isEmpty && forward.isEmpty

  /** The blocks that are applied from a layer: they have no changeset record yet, and the move writes one for each. A
    * block applied from its record keeps the record it has.
    */
  def newRecords: Vector[BlockRef] = forward.collect { case Forwarded(block, _, Source.Layer(_)) => block }

  /** The move as a chain of smaller ones, of at most `maxBlocks` blocks each, reversed or applied. Each ends with the
    * disk holding the state of a block on the way from the anchor down to the fork and up to the target, which is where
    * a move that is interrupted leaves the head. A plan that moves nothing has none.
    */
  def chunks(maxBlocks: Int): Vector[Chunk] =
    require(maxBlocks > 0, s"a chunk of a head move holds at least one block, not $maxBlocks")
    val steps: Vector[Either[Reversed, Forwarded]] = reverse.map(Left(_)) ++ forward.map(Right(_))
    steps
      .grouped(maxBlocks)
      .foldLeft((Vector.empty[Chunk], anchor)) { case ((done, from), group) =>
        val head = group.last.fold(_.parent, _.block)
        (done :+ Chunk(from, head, group.collect { case Left(r) => r }, group.collect { case Right(f) => f }), head)
      }
      ._1

object HeadMovePlan extends LazyLogger:

  /** The most blocks one plan reverses where nothing else bounds it (the archive mode keeps every record, and has no
    * depth unless one is configured). A plan is held in memory whole, and a walk of this many records' headers takes
    * seconds: a move deeper than this is not what a plan is for.
    */
  val MaxBlocks: Int = 65536

  /** A block to reverse, and the block the disk holds once it is: its parent, as its record names it. */
  final case class Reversed(block: BlockRef, parent: BlockRef)

  /** Where the changes of a block to apply are taken from. */
  enum Source:
    /** Its changeset record: the block was applied before and reversed since, or was committed beside the head. */
    case Record

    /** The layer its execution left, which has no record yet. */
    case Layer(layer: ForwardLayer)

  /** A block to apply, on `parent`'s state. */
  final case class Forwarded(block: BlockRef, parent: BlockRef, source: Source)

  /** One batch of a move: from the state of `from` to that of `head`. */
  final case class Chunk(from: BlockRef, head: BlockRef, reverse: Vector[Reversed], forward: Vector[Forwarded])

  /** A block of the target's chain, and the block before it, as their headers say. */
  final case class Link(block: BlockRef, parentHash: ByteString)

  /** What a plan is made from, each asked by a block's hash. */
  trait Sources:

    /** The header of the changeset record of the block `blockHash`: `None` when there is none. */
    def recordHeader(blockHash: ByteString): Option[Either[BlockDiffCodec.Rejected, BlockDiffCodec.Header]]

    /** The layer kept of the executed block `block`, if one is. */
    def layer(block: BlockRef): Option[ForwardLayer]

    /** The block `blockHash` as its header gives it: the header stored under that hash, which is the hash of that
      * header. Never a row of the index of blocks by number.
      */
    def link(blockHash: ByteString): Option[Link]

  /** What bounds a plan.
    *
    * @param maxReverse
    *   policy: how many blocks may be reversed (`state.reorg.max-depth`, where it is a number). What can be reversed at
    *   all is decided by the records that are there
    * @param maxForward
    *   how many blocks above the anchor the target may lie. (Below that height the target's chain is walked one header
    *   for each block reversed, and what bounds the one bounds the other.)
    * @param prunedTo
    *   every height below it has had its records deleted, or never had one; `None` where no block was committed in
    *   place
    * @param origin
    *   the block this data directory's state began at: its genesis, the pivot of a sync, an imported checkpoint.
    *   Nothing below it was ever here to go back to
    * @param finalized
    *   the finalized block, where there is one. It is never reversed. A block of another hash at its height is not it:
    *   the anchor is then on a branch that was not finalized, and the way back to the one that was is open
    */
  final case class Limits(
      maxReverse: Int,
      maxForward: Int,
      prunedTo: Option[BigInt],
      origin: Option[BlockRef],
      finalized: Option[FinalizedBlock]
  )

  /** What came of planning a move. */
  enum Outcome:
    case Planned(plan: HeadMovePlan)

    /** The move cannot be made from what this node keeps, and executing blocks again does not change that. */
    case Refused(reason: ReorgRefusal, detail: String)

    /** A block on the way up to the target has neither a record nor a layer, or the target's chain cannot be followed
      * down to one this node's state leads to. Executing the blocks again is the answer to the first.
      *
      * @param heldTo
      *   the highest block of the target's chain that a move could be made to as things are: the fork, or a block above
      *   it whose every block from the fork up has a record or a layer. `None` when no fork was found
      */
    case NotHeld(detail: String, heldTo: Option[BlockRef])

  /** A record's header that is its block's, and not a tombstone's. */
  private enum Read:
    case Complete(header: BlockDiffCodec.Header)
    case Tombstone
    case Absent
    case Unreadable(why: String)

  private def read(sources: Sources, block: BlockRef): Read =
    sources.recordHeader(block.hash) match
      case None                 => Read.Absent
      case Some(Left(rejected)) => Read.Unreadable(s"it cannot be read (${rejected.reason}: ${rejected.detail})")
      case Some(Right(header)) =>
        if header.number != block.number || header.postStateRoot != block.stateRoot then
          Read.Unreadable(s"it is of block ${header.number} and leaves another state root than the block's")
        else if header.incomplete then Read.Tombstone
        else Read.Complete(header)

  /** The plan of the move from `anchor`, at `epoch`, to `target`.
    *
    * The two chains are walked down together, the higher one first, until they name one block: the anchor's by its
    * records' headers, the target's by its blocks' headers. A block is reversed only if it may be and can be, asked in
    * this order, so that the answer does not depend on what happens to have been pruned:
    *
    *   - the finalized block is never reversed (`Finalized`), and so nothing below it on its chain is reached;
    *   - nor the block the state began at (`Pivot`): there is no state before it here;
    *   - nor more blocks than the policy allows (`Deep`);
    *   - a record that is gone below the mark of what was pruned is `Deep`; one that is gone above it, or is not its
    *     block's, is this data directory's corruption (`Corrupt`); a tombstone is `Incomplete`.
    *
    * A fork exactly at the finalized block, at the origin, or at the last block that keeps its record is planned: that
    * block itself is not reversed, and its own record is never read.
    */
  def of(anchor: BlockRef, epoch: Long, target: BlockRef, sources: Sources, limits: Limits): Outcome =
    if target == anchor then Outcome.Planned(HeadMovePlan(anchor, epoch, anchor, anchor, Vector.empty, Vector.empty))
    else if target.number - anchor.number > limits.maxForward then
      Outcome.NotHeld(
        s"the target ${target.show} is more than ${limits.maxForward} blocks above the block this node's state is of",
        None
      )
    else
      sources.link(target.hash) match
        case None => Outcome.NotHeld(s"the header of the target ${target.show} is not there", None)
        case Some(link) if link.block != target =>
          Outcome.NotHeld(
            s"the header of the target ${target.show} is of block ${link.block.show} with another state root",
            None
          )
        case Some(link) => walk(anchor, epoch, target, sources, limits, anchor, link, Vector.empty, Nil)

  /** One step of the walk: `at` is where the anchor's chain has come down to, `up` where the target's has; `forward`
    * holds the blocks of the target's chain above `up`, oldest first.
    */
  @tailrec
  private def walk(
      anchor: BlockRef,
      epoch: Long,
      target: BlockRef,
      sources: Sources,
      limits: Limits,
      at: BlockRef,
      up: Link,
      reverse: Vector[Reversed],
      forward: List[Link]
  ): Outcome =
    if at.hash == up.block.hash then
      if at != up.block then
        Outcome.Refused(
          ReorgRefusal.Corrupt,
          s"the changeset records lead to block ${at.show} with another number or state root than its header's"
        )
      else sourced(HeadMovePlan(anchor, epoch, at, target, reverse, Vector.empty), forward, sources)
    else
      // the higher of the two comes down first; at one height, both
      val reversing = at.number >= up.block.number
      val forwarding = up.block.number >= at.number
      val reversed: Either[Outcome, BlockRef] =
        if !reversing then Right(at) else parentBelow(at, sources, limits, reverse.size)
      reversed match
        case Left(refused) => refused
        case Right(below) =>
          val stepped: Either[Outcome, (Link, List[Link])] =
            if !forwarding then Right((up, forward))
            else
              parentOf(up, sources) match
                case Left(why)     => Left(Outcome.NotHeld(why, None))
                case Right(parent) => Right((parent, up :: forward))
          stepped match
            case Left(notHeld) => notHeld
            case Right((nextUp, nextForward)) =>
              val nextReverse = if reversing then reverse :+ Reversed(at, below) else reverse
              walk(anchor, epoch, target, sources, limits, below, nextUp, nextReverse, nextForward)

  /** The block before `at` on the anchor's chain, if `at` may be reversed and can be. */
  private def parentBelow(
      at: BlockRef,
      sources: Sources,
      limits: Limits,
      reversedSoFar: Int
  ): Either[Outcome, BlockRef] =
    def refused(reason: ReorgRefusal, detail: String): Either[Outcome, BlockRef] =
      Left(Outcome.Refused(reason, detail))
    if limits.finalized.exists(finalized => at.number == finalized.number && at.hash == finalized.hash) then
      refused(ReorgRefusal.Finalized, s"it would reverse block ${at.show}, which is finalized")
    else if at.number == 0 then
      refused(
        ReorgRefusal.Pivot,
        s"the target's chain shares no block with this node's, down to the genesis ${at.show}"
      )
    else if limits.origin.exists(at.number <= _.number) then
      refused(ReorgRefusal.Pivot, s"it would reverse block ${at.show}, which this data directory's state began at")
    else if reversedSoFar >= limits.maxReverse then
      refused(ReorgRefusal.Deep, s"the fork point is more than ${limits.maxReverse} blocks below the head")
    else
      read(sources, at) match
        case Read.Complete(header) => Right(BlockRef(header.parentHash, at.number - 1, header.preStateRoot))
        case Read.Tombstone =>
          refused(ReorgRefusal.Incomplete, s"block ${at.show} changed more than a record holds, and cannot be reversed")
        case Read.Absent if limits.prunedTo.forall(at.number < _) =>
          refused(
            ReorgRefusal.Deep,
            s"the changeset record of block ${at.show} is no longer kept: the fork point is $reversedSoFar blocks " +
              "or more below the head"
          )
        case Read.Absent =>
          refused(ReorgRefusal.Corrupt, s"the changeset record of block ${at.show} is not there, and was not pruned")
        case Read.Unreadable(why) =>
          refused(ReorgRefusal.Corrupt, s"the changeset record of block ${at.show} is not that block's: $why")

  /** The block before `link` on the target's chain, by its header. */
  private def parentOf(link: Link, sources: Sources): Either[String, Link] =
    sources.link(link.parentHash) match
      case None => Left(s"the header of the parent of block ${link.block.show} is not there")
      case Some(parent) if parent.block.hash != link.parentHash || parent.block.number != link.block.number - 1 =>
        Left(s"the header of the parent of block ${link.block.show} is not that of the block before it")
      case Some(parent) => Right(parent)

  /** `plan`, which reaches the fork, with each block above it given what it is applied from: its record where it has a
    * complete one that leads from its parent, and a layer otherwise. A record is exact and needs no settling, so it
    * comes first where a block has both.
    */
  private def sourced(plan: HeadMovePlan, forward: List[Link], sources: Sources): Outcome =
    @tailrec
    def go(parent: BlockRef, left: List[Link], done: Vector[Forwarded]): Outcome = left match
      case Nil => Outcome.Planned(plan.copy(forward = done))
      case link :: above =>
        val block = link.block
        val byRecord = read(sources, block) match
          case Read.Complete(header) if header.parentHash == parent.hash && header.preStateRoot == parent.stateRoot =>
            Some(Source.Record)
          case Read.Complete(_) =>
            passedOver(block, s"it does not lead from the block before it, ${parent.show}")
          case Read.Unreadable(why)         => passedOver(block, why)
          case Read.Tombstone | Read.Absent => None
        byRecord.orElse(sources.layer(block).filter(_.parent == parent).map(Source.Layer(_))) match
          case Some(source) => go(block, above, done :+ Forwarded(block, parent, source))
          case None =>
            Outcome.NotHeld(
              s"block ${block.show} on the way to ${plan.target.show} has neither a changeset record nor an " +
                "executed block kept for it",
              Some(parent)
            )
    go(plan.fork, forward, Vector.empty)

  /** A record of a block to apply that is not that block's is this data directory's corruption, as it is of a block to
    * reverse. The block can still be applied from a layer, or executed again, and a record is then written over this
    * one: so it is said here, where it is last seen.
    */
  private def passedOver(block: BlockRef, why: String): Option[Source] =
    log.error(
      s"The changeset record of block ${block.show} is not that block's ($why): this data directory's changeset " +
        "records are corrupt. The block is applied from its execution instead"
    )
    None
