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

  /** Drops every layer no chain leads to from `anchor`, and says how many: the siblings of a block that became the
    * anchor and what was built on them, and what was built on a layer that was dropped. No view can be made of them,
    * and nothing else would ever take them away. (The reverse index gives the state of a block behind the anchor: when
    * it is there, a sibling of the anchor is reached through it.)
    */
  def dropUnreachableFrom(anchor: BlockRef)(using StateLockHeld): Int =
    val gone = forward.values.asScala.filter(layer => chainFrom(anchor, layer.block).isEmpty).map(_.block.hash).toList
    gone.foreach(forward.remove)
    gone.size

  /** The layers that lead from the state of `anchor` to the state of `target`, newest first, or `None` when there is no
    * such chain: `target`'s layer, its parent's, and so on down to a layer whose parent is `anchor`.
    */
  def chainFrom(anchor: BlockRef, target: BlockRef): Option[List[ForwardLayer]] =
    @annotation.tailrec
    def down(to: BlockRef, chain: List[ForwardLayer]): Option[List[ForwardLayer]] =
      if to.number <= anchor.number then None
      else
        layerOf(to) match
          case None                                  => None
          case Some(layer) if layer.parent == anchor => Some((layer :: chain).reverse)
          case Some(layer)                           => down(layer.parent, layer :: chain)
    down(target, Nil)
