package com.chipprbots.ethereum.db.storage.pathstate

import java.util.concurrent.ConcurrentHashMap

import org.apache.pekko.util.ByteString

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
    */
  def register(layer: ForwardLayer)(using StateLockHeld): ForwardLayer =
    layerOf(layer.block.hash) match
      case Some(known) => known
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
