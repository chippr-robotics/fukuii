package com.chipprbots.ethereum.db.storage.pathstate

import java.util.concurrent.ConcurrentHashMap

import org.apache.pekko.util.ByteString

import scala.jdk.CollectionConverters.*

/** The forward layers of a position store (spec 018 data-model 6.1): the executed blocks whose state is on no row, by
  * block hash, each linked to its parent.
  *
  * A layer is added and taken away under the state lock ([[PathStateStorage]] does both); it is read without it, by
  * whoever makes a view.
  *
  * @param maxLayers
  *   how many layers there may be at once. A stopgap: the budgets of data-model 6.4 (bytes, eviction, pinned branches)
  *   replace it before anything in the node makes a layer
  */
final private[pathstate] class StateOverlay(maxLayers: Int):

  private val forward = new ConcurrentHashMap[ByteString, ForwardLayer]()

  def layerOf(blockHash: ByteString): Option[ForwardLayer] = Option(forward.get(blockHash))

  /** The layer of exactly `block`: its hash, its number and its state root. */
  def layerOf(block: BlockRef): Option[ForwardLayer] = layerOf(block.hash).filter(_.block == block)

  def size: Int = forward.size

  /** Adds `layer`, or gives the layer there already is for its block: a block that is executed twice is one layer.
    *
    * @throws StateUnavailable
    *   when there are as many layers as there may be
    * @throws IllegalStateException
    *   when the layer there is for that hash is of another block, or on another parent: a block has one of each
    */
  def register(layer: ForwardLayer)(using StateLockHeld): ForwardLayer =
    layerOf(layer.block.hash) match
      case Some(known) if known.block == layer.block && known.parent == layer.parent => known
      case Some(known) =>
        throw new IllegalStateException(
          s"block ${layer.block.show} on ${layer.parent.show} has the hash of an executed block that is kept: " +
            s"${known.block.show} on ${known.parent.show}"
        )
      case None =>
        if forward.size >= maxLayers then
          throw new StateUnavailable(
            s"block ${layer.block.show} cannot be kept beside the state on disk: $maxLayers executed blocks are " +
              "kept there already"
          )
        forward.put(layer.block.hash, layer)
        layer

  def drop(blockHash: ByteString)(using StateLockHeld): Unit =
    forward.remove(blockHash)
    ()

  def clear()(using StateLockHeld): Unit = forward.clear()

  /** Drops every layer no chain leads to from `anchor` or from a block of `behind` (the anchor's ancestors that
    * changeset records still lead to), and every layer of a block that is the anchor or one of those: it is canonical,
    * and its state is the disk's or the records'. Says how many. What goes: the layer of a block that became the
    * anchor; what was built on a layer that was dropped; a fork whose first block stands on an ancestor the records no
    * longer reach. No view can be made of them, and nothing else would ever take them away.
    */
  def dropUnreachableFrom(anchor: BlockRef, behind: BlockRef => Boolean)(using StateLockHeld): Int =
    val gone = forward.values.asScala
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
