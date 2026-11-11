package com.chipprbots.ethereum.db.storage.pathstate

import com.chipprbots.ethereum.db.storage.MptStorage

/** The state writes of '''one''' execution of one block on the position store, held until the block is accepted (spec
  * 018 data-model 6.1, 6.2).
  *
  * The block is executed on [[storage]]: its tries read the parent's state through a view, and what they write stays in
  * a layer of this object, by hash for the block's own reads and by position for its commit. Nothing reaches the disk
  * until [[PathStateStorage.commitCanonical]] writes it, in one batch with the block. A block that is rejected is
  * simply dropped with this object, and leaves nothing.
  *
  * '''One execution, one of these.''' A block that is executed again (a retry after a missing node, a stale view, the
  * same payload sent twice) starts from a fresh one: what an abandoned attempt wrote must not be in the block's
  * changes.
  *
  * @param parent
  *   the block whose state the execution starts from
  */
final class PathStagedState private[pathstate] (val parent: BlockRef, view: StateView):

  private[pathstate] val changes = new StagedChanges

  /** The storage the block's world reads and writes. */
  val storage: MptStorage = PathMptStorage.staged(view, changes)

  /** How many positions the block has touched so far. */
  def positionsTouched: Int = changes.size
