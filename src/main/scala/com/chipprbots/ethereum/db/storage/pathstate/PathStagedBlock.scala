package com.chipprbots.ethereum.db.storage.pathstate

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.db.dataSource.DataSourceBatchUpdate
import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.db.storage.PrefetchNodeReader
import com.chipprbots.ethereum.db.storage.StagedBlockState

/** The staged state of one block on the unified layout, as block execution holds it (spec 018 data-model 7.1): the
  * block's layer over its parent's state, committed with the block in one batch or dropped.
  *
  * '''The layer is made when the execution first asks for the storage, not before.''' Execution stages a block before
  * it asks whether the block was executed already, and a layer can only be made over the state the disk holds: for a
  * block that is applied, that state is the block's own, not its parent's, and asking for a layer would be refused.
  * Such a block never reaches the storage. It reaches [[commitWith]] with nothing staged, where its own records are
  * written if it is still the block the disk holds.
  *
  * '''A block is committed in place''' ([[commitWith]]), in the batch that makes it the best block: the state on disk
  * and the best block move together; '''or apart''' ([[commitApart]]), as a forward layer beside the state the disk
  * holds, for a block executed without being adopted (a payload ahead of the head, C4d part 2a). A reorganisation's
  * blocks are still refused before they run (C4e).
  *
  * @param block
  *   the block, with the state root its '''header''' carries: what the staged changes are held to
  * @param parent
  *   the block it builds on, or `None` when its header is not known
  * @param apart
  *   staged to be committed apart ([[commitApart]]): its parent may be any block whose state is held, not only the
  *   anchor ([[PathStateStorage.stageOf]])
  */
final class PathStagedBlock(
    state: PathStateStorage,
    block: BlockRef,
    parent: () => Option[BlockRef],
    apart: Boolean = false
) extends StagedBlockState:

  private var layer: Option[PathStagedState] = None

  private def knownParent: BlockRef =
    parent().getOrElse(throw new StateUnavailable(s"the parent of block ${block.show} is not known"))

  /** @throws StateUnavailable
    *   when the parent's state is not the state the disk holds
    */
  override def storage: MptStorage = synchronized {
    layer.getOrElse {
      val made = if apart then state.stageOf(knownParent) else state.stage(knownParent)
      layer = Some(made)
      made
    }.storage
  }

  override def pending: Option[DataSourceBatchUpdate] = None

  override def commitsInPlace: Boolean = true

  /** A reader of the parent's rows for the access-list prefetch. It reads the view and records nothing; it warms the
    * database's caches, and a block whose parent's state cannot be viewed executes without one.
    */
  override def prefetchReader(budgetBytes: Long, onLoaded: ByteString => Unit): Option[PrefetchNodeReader] =
    parent().flatMap(state.prefetchReader(_, None, budgetBytes, onLoaded))

  /** Commits the block as the block whose state the disk holds, with `extra` in the same batch.
    *
    * @throws StateUnavailable
    *   when the block does not extend the state the disk holds, or `extra` is missing: a block's state is not committed
    *   here without the block
    */
  override def commitWith(blockNumber: BigInt, blockHash: ByteString, extra: Option[DataSourceBatchUpdate]): Unit =
    require(
      blockHash == block.hash && blockNumber == block.number,
      s"block ${block.show} was staged, and block $blockNumber is being committed"
    )
    if extra.isEmpty then
      throw new StateUnavailable(
        s"block ${block.show} cannot be committed without its own records: the state on disk moves with the best block"
      )
    val staged = synchronized {
      val taken = layer
      layer = None
      taken
    }
    state.commitCanonical(staged, block, extra)
    ()

  /** The block's state kept as a forward layer beside the state the disk holds ([[PathStateStorage.commitOverlay]]):
    * the head does not move, and only a fork choice moves it there (spec 018 C4d part 2a). A layer that is not kept
    * (too large, or no room) leaves nothing wrong with the block: whoever needs its state executes it again. A block
    * whose storage was never asked for (one the disk holds already) has nothing to keep.
    *
    * @throws StateUnavailable
    *   when the parent's state is not held any more
    */
  override def commitApart(blockNumber: BigInt, blockHash: ByteString): Unit =
    require(
      blockHash == block.hash && blockNumber == block.number,
      s"block ${block.show} was staged, and block $blockNumber is being committed"
    )
    val staged = synchronized {
      val taken = layer
      layer = None
      taken
    }
    staged.foreach(state.commitOverlay(_, block))

  /** The block's state is in force: it is the anchor, or, for a block staged apart, its forward layer is kept. So a
    * payload delivered again while its first execution runs takes the execution recorded for it, not a second run
    * (beacon); one whose layer was not kept, or was dropped, is executed again.
    */
  override def isBlockApplied(blockHash: ByteString): Boolean =
    state.isApplied(blockHash) || (apart && blockHash == block.hash && state.holdsLayer(block))

  override def discard(): Unit = synchronized { layer = None }
