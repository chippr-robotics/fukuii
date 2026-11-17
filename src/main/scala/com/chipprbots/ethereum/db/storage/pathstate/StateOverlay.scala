package com.chipprbots.ethereum.db.storage.pathstate

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

import org.apache.pekko.util.ByteString

import scala.jdk.CollectionConverters.*

/** The forward layers of a position store (spec 018 data-model 6.1): the executed blocks whose state is on no row, by
  * block hash, each linked to its parent; and the bounds they are kept within (data-model 6.4).
  *
  * A layer is added and taken away under the state lock ([[PathStateStorage]] does both); it is read without it, by
  * whoever makes a view.
  *
  * '''What is kept, and what goes.''' A layer is ''pinned'' while a branch under execution holds it ([[BranchPin]]),
  * and is otherwise not. The layers that are not pinned are bounded in number and in bytes; when one more would not
  * fit, layers are '''evicted''' to make room, and a block whose layer is evicted is executed again by whoever needs
  * its state. Only a '''tip''' is evicted, a layer no kept layer stands on: taking an inner layer away would take every
  * state above it with it. Never a pinned layer, and never one the new layer stands on. The pinned layers are bounded
  * in bytes alone, across every branch that is pinned, and a branch that would not fit is refused with
  * [[BranchTooLarge]], which is a fault to try again after and never a verdict on a block.
  *
  * '''What the bounds hold at any time.''' After a layer is kept, pinned or not, the layers that are not pinned are
  * within their bounds: a pinned one that is kept evicts the others' tips down to them too. Between the release of a
  * branch and the next layer that is kept, what the branch held is over them: a release evicts nothing, so that a
  * caller can promote what it adopts and execute again on what it keeps.
  *
  * '''Which tip goes first.''' A fork whose fork point is the oldest block the changeset records still lead back to: it
  * is the first that the anchor's moving on takes away in any case. Then the layer least recently ''used'': one an
  * execution was staged through, one a layer was registered on, one that was delivered again. A view made for a reader
  * that writes nothing is not a use (a caller of `eth_call` does not decide what a node keeps). Between layers used
  * alike, a fork's before one that stands on the anchor's own chain, and then the lower block.
  */
final private[pathstate] class StateOverlay(budget: StateOverlay.Budget):

  import StateOverlay.*

  /** A layer, and what is known of it beside what it holds. */
  final private class Held(val layer: ForwardLayer):
    @volatile var pins: Set[BranchPin] = Set.empty
    @volatile var lastUse: Long = 0L
    def pinned: Boolean = pins.nonEmpty

  private val forward = new ConcurrentHashMap[ByteString, Held]()
  private val uses = new AtomicLong(0L)
  private val evictions = new AtomicLong(0L)
  private val evictionsUntold = new AtomicLong(0L)

  def layerOf(blockHash: ByteString): Option[ForwardLayer] = Option(forward.get(blockHash)).map(_.layer)

  /** The layer of exactly `block`: its hash, its number and its state root. */
  def layerOf(block: BlockRef): Option[ForwardLayer] = layerOf(block.hash).filter(_.block == block)

  def size: Int = forward.size

  /** What is kept right now, and how many layers were evicted since the start. */
  def usage: PathStateStorage.OverlayUsage =
    val (pinned, free) = forward.values.asScala.toSeq.partition(_.pinned)
    PathStateStorage.OverlayUsage(
      layers = free.size,
      bytes = free.iterator.map(_.layer.bytes).sum,
      pinnedLayers = pinned.size,
      pinnedBytes = pinned.iterator.map(_.layer.bytes).sum,
      evictions = evictions.get
    )

  /** How many layers were evicted since this was last asked: for a counter, which takes each eviction once. */
  def evictionsSinceAsked(): Long = evictionsUntold.getAndSet(0L)

  /** `layers` were used just now: an execution was staged through them, or one of them was delivered again. Needs no
    * lock: it changes the order in which layers are evicted and nothing else.
    */
  def used(layers: Iterable[ForwardLayer]): Unit =
    if layers.nonEmpty then
      val now = uses.incrementAndGet()
      layers.foreach(layer => Option(forward.get(layer.block.hash)).filter(_.layer eq layer).foreach(_.lastUse = now))

  /** Keeps `layer`, or says why it is not kept; the layer there already is for its block is kept as it is, a block that
    * is executed twice being one layer.
    *
    * With a `pin` the layer and every kept layer it stands on are held by that branch until it is released: they are
    * not evicted and not counted against the bounds of the others.
    *
    * @param anchor
    *   the block whose state the disk holds
    * @param behind
    *   whether a block is an ancestor of the anchor that the changeset records lead back to
    * @param floor
    *   the oldest of those ancestors, when there is one
    * @throws BranchTooLarge
    *   when the pinned layers would hold more than they may: nothing is kept and nothing is pinned
    * @throws IllegalStateException
    *   when the layer there is for that hash is of another block, or on another parent: a block has one of each
    */
  def register(
      layer: ForwardLayer,
      pin: Option[BranchPin],
      anchor: BlockRef,
      behind: BlockRef => Boolean,
      floor: Option[BlockRef]
  )(using StateLockHeld): Registered =
    Option(forward.get(layer.block.hash)) match
      case Some(known) if known.layer.block != layer.block || known.layer.parent != layer.parent =>
        throw new IllegalStateException(
          s"block ${layer.block.show} on ${layer.parent.show} has the hash of an executed block that is kept: " +
            s"${known.layer.block.show} on ${known.layer.parent.show}"
        )
      case known =>
        val under = chainFrom(anchor, layer.parent, behind).fold(List.empty[ForwardLayer])(_._1).map(heldOf)
        val entry = known.getOrElse(new Held(layer))
        pin match
          case Some(branch) =>
            val joining = (entry :: under).filterNot(_.pinned)
            val bytes = pinnedBytes + joining.iterator.map(_.layer.bytes).sum
            if bytes > budget.pinnedMaxBytes then throw new BranchTooLarge(bytes, budget.pinnedMaxBytes)
            (entry :: under).foreach(held => held.pins = held.pins + branch)
            val kept = keep(entry, under)
            // What an earlier branch left when it was released is held to the bounds here too: a node that executes
            // one branch after another, and keeps nothing else in between, would otherwise hold them all.
            evict(victims(None, anchor, behind, floor)._1)
            kept
          case None if known.isDefined                    => keep(entry, under)
          case None if layer.bytes > budget.maxLayerBytes => Registered.TooLarge(layer.bytes, budget.maxLayerBytes)
          case None                                       =>
            // nothing goes for a layer that is not kept in the end
            victims(Some(layer), anchor, behind, floor) match
              case (_, false) => Registered.NoRoom
              case (going, true) =>
                evict(going)
                keep(entry, under)

  private def heldOf(layer: ForwardLayer): Held = forward.get(layer.block.hash)

  private def keep(entry: Held, under: List[Held]): Registered =
    forward.putIfAbsent(entry.layer.block.hash, entry)
    val now = uses.incrementAndGet()
    (entry :: under).foreach(_.lastUse = now)
    Registered.Kept(entry.layer)

  private def pinnedBytes: Long = forward.values.asScala.iterator.filter(_.pinned).map(_.layer.bytes).sum

  private def evict(going: List[Held]): Unit =
    going.foreach(victim => forward.remove(victim.layer.block.hash))
    evictions.addAndGet(going.size.toLong)
    evictionsUntold.addAndGet(going.size.toLong)
    ()

  /** The layers to evict so that those that are not pinned are within their bounds, in the order they go, and whether
    * they then are: with `joining`, a layer that is to be kept among them, counted with them, and whose parent is not
    * evicted for it.
    */
  private def victims(
      joining: Option[ForwardLayer],
      anchor: BlockRef,
      behind: BlockRef => Boolean,
      floor: Option[BlockRef]
  ): (List[Held], Boolean) =
    var kept = forward.values.asScala.toList
    var count = kept.count(!_.pinned) + joining.size
    var bytes = kept.iterator.filterNot(_.pinned).map(_.layer.bytes).sum + joining.fold(0L)(_.bytes)
    var going = List.empty[Held]
    var fits = count <= budget.maxLayers && bytes <= budget.maxBytes
    var none = false
    while !fits && !none do
      // what a kept layer stands on, and what the new one would: every layer under the new one is among them
      val parents = kept.iterator.map(_.layer.parent.hash).toSet ++ joining.map(_.parent.hash)
      val tips = kept.filter(held => !held.pinned && !parents(held.layer.block.hash))
      if tips.isEmpty then none = true
      else
        val victim = tips.minBy(order(_, anchor, behind, floor))
        going = victim :: going
        kept = kept.filterNot(_ eq victim)
        count -= 1
        bytes -= victim.layer.bytes
        fits = count <= budget.maxLayers && bytes <= budget.maxBytes
    (going.reverse, fits)

  /** What a tip is evicted before: see the class. */
  private def order(
      held: Held,
      anchor: BlockRef,
      behind: BlockRef => Boolean,
      floor: Option[BlockRef]
  ): (Int, Long, Int, BigInt, String) =
    val on = chainFrom(anchor, held.layer.block, behind).map(_._2)
    val atFloor = on.exists(floor.contains)
    val onFork = !on.contains(anchor)
    (
      if atFloor then 0 else 1,
      held.lastUse,
      if onFork then 0 else 1,
      held.layer.block.number,
      held.layer.block.show
    )

  /** Pins `layers`, each where it is still the layer kept for its block, to `pin`: a move of the head holds the layers
    * its plan applies for as long as it runs, so that nothing evicts one between two of its batches. They are held in
    * memory already, so the bound on pinned bytes is not asked: it bounds what a branch under execution may add.
    */
  def pinAll(layers: Iterable[ForwardLayer], pin: BranchPin)(using StateLockHeld): Unit =
    layers.foreach { layer =>
      Option(forward.get(layer.block.hash)).filter(_.layer eq layer).foreach(held => held.pins = held.pins + pin)
    }

  /** The branch of `pin` is no longer under execution: its layers are held by it no more, and are as any other from the
    * next registration on. Nothing is evicted here, and they count as used just now, so that what a branch left is not
    * the first to go.
    */
  def release(pin: BranchPin)(using StateLockHeld): Unit =
    val now = uses.incrementAndGet()
    forward.values.asScala.foreach { held =>
      if held.pins.contains(pin) then
        held.pins = held.pins - pin
        held.lastUse = now
    }

  def drop(blockHash: ByteString)(using StateLockHeld): Unit =
    forward.remove(blockHash)
    ()

  def clear()(using StateLockHeld): Unit = forward.clear()

  /** Drops every layer no chain leads to from `anchor` or from a block of `behind` (the anchor's ancestors that
    * changeset records still lead to), and every layer of a block that is the anchor or one of those: it is canonical,
    * and its state is the disk's or the records'. Says how many. What goes: the layer of a block that became the
    * anchor; what was built on a layer that was dropped; a fork whose first block stands on an ancestor the records no
    * longer reach. No view can be made of them, and nothing else would ever take them away. A pin does not keep such a
    * layer: it holds a branch against the bounds, and not a state nothing leads to.
    */
  def dropUnreachableFrom(anchor: BlockRef, behind: BlockRef => Boolean)(using StateLockHeld): Int =
    val gone = forward.values.asScala
      .map(_.layer)
      .filter { layer =>
        layer.block == anchor || behind(layer.block) || chainFrom(anchor, layer.block, behind).isEmpty
      }
      .map(_.block.hash)
      .toList
    gone.foreach(forward.remove)
    gone.size

  /** The layers that lead to the state of `target`, newest first, and the block the oldest of them stands on: the
    * anchor, or a block of `behind` (an ancestor of the anchor). `None` when there is no such chain: `target`'s layer,
    * its parent's, and so on down, never reach one of those.
    */
  def chainFrom(
      anchor: BlockRef,
      target: BlockRef,
      behind: BlockRef => Boolean = _ => false
  ): Option[(List[ForwardLayer], BlockRef)] =
    @annotation.tailrec
    def down(to: BlockRef, chain: List[ForwardLayer], left: Int): Option[(List[ForwardLayer], BlockRef)] =
      if left == 0 then None
      else
        layerOf(to) match
          case None => None
          case Some(layer) if layer.parent == anchor || behind(layer.parent) =>
            Some(((layer :: chain).reverse, layer.parent))
          case Some(layer) => down(layer.parent, layer :: chain, left - 1)
    // a chain has no more layers than there are
    down(target, Nil, forward.size)

private[pathstate] object StateOverlay:

  /** The bounds of the forward layers (data-model 6.4).
    *
    * @param maxLayers
    *   how many layers that are not pinned are kept
    * @param maxBytes
    *   how much they hold together, as [[ForwardLayer.bytes]] counts it
    * @param maxLayerBytes
    *   how much one of them may hold: a larger one is not kept unless a branch pins it
    * @param pinnedMaxBytes
    *   how much the pinned layers hold together, across every branch that is pinned
    */
  final case class Budget(maxLayers: Int, maxBytes: Long, maxLayerBytes: Long, pinnedMaxBytes: Long)

  /** What [[StateOverlay.register]] did with a layer. */
  enum Registered:
    case Kept(layer: ForwardLayer)
    case TooLarge(bytes: Long, max: Long)
    case NoRoom

/** A branch under execution, as what holds its layers in the overlay: the layers of a branch that is executed whole
  * before any of it is adopted (a heavier proof-of-work branch, a chain of payloads a consensus client builds on) are
  * all live at once, and are more than the overlay otherwise keeps (data-model 6.4). Made and released by
  * [[PathStateStorage.withPinnedBranch]].
  */
final class BranchPin private[pathstate] (val name: String):
  override def toString: String = s"BranchPin($name)"
