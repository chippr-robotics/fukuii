package com.chipprbots.ethereum.db.storage.pathstate

import org.apache.pekko.util.ByteString

import org.bouncycastle.util.encoders.Hex

import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.db.storage.DecodedNodeCache
import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.db.storage.PathNodeStorage
import com.chipprbots.ethereum.db.storage.PrefetchNodeReader

/** The state of the unified layout (spec 018 Track C): the position-keyed rows of CF `t` and CF `u`, the anchor that
  * says which block's state they hold, and the views a reader takes of them.
  *
  * This is its '''read side'''. It serves the state of the anchor and nothing else: the states of other blocks come
  * with the overlay (C4a), and every write (a block's commit, a head move, a repair) with the staged commit (C3b),
  * which is also the first caller of [[clock]]'s `publish`. It exists only behind the in-development guard
  * `fukuii.state.path-live`.
  *
  * @param maxViewAgeMoves
  *   how many state moves a view outlives (`state.overlay.max-view-age-moves`)
  */
final class PathStateStorage(rows: PathNodeStorage, appState: AppStateStorage, maxViewAgeMoves: Int = 8):

  /** The clock of in-place moves, started from the anchor the datadir holds. */
  val clock: StateMoveLog = new StateMoveLog(maxViewAgeMoves, appState.getStateAnchor())

  /** The block whose state the disk holds, or `None` when the datadir has no state yet (SNAP in progress). */
  def anchor: Option[BlockRef] = clock.anchor

  /** Whether a view of `block`'s state can be made right now. */
  def stateAvailable(block: BlockRef): Boolean = anchor.contains(block)

  /** A view of `block`'s state, made at a moment when the disk is at rest: a view is made at an even epoch (data-model
    * 6.3.1), so one made while a batch is being written waits for that batch.
    *
    * @throws StateUnavailable
    *   when `block` is not the anchor, or there is no anchor
    */
  def view(block: BlockRef): StateView =
    val (epoch, held) = clock.awaitRest()
    held match
      case Some(`block`) => new StateView(block, epoch, clock, rows)
      case Some(other) =>
        throw new StateUnavailable(s"the state of block ${block.show} is not held: the disk holds ${other.show}")
      case None => throw new StateUnavailable(s"the state of block ${block.show} is not held: there is no state anchor")

  /** `block`'s state for a reader that writes nothing. */
  def readOnly(block: BlockRef): MptStorage = PathMptStorage.readOnly(view(block))

  /** `block`'s state for an execution that commits nothing: what it persists is kept beside the view and dropped with
    * the storage (`eth_call`, a simulation, a trace, a payload being built).
    */
  def throwaway(block: BlockRef): MptStorage = PathMptStorage.throwaway(view(block))

  /** A reader of `parent`'s state for the access-list prefetch, or `None` when that state cannot be viewed: a prefetch
    * only warms caches, and a block whose parent is not the anchor simply executes without one.
    */
  def prefetchReader(
      parent: BlockRef,
      cache: Option[DecodedNodeCache],
      budgetBytes: Long,
      onLoaded: ByteString => Unit
  ): Option[PrefetchNodeReader] =
    try Some(PathPrefetchReader(view(parent), cache, budgetBytes, onLoaded))
    catch case _: StateFault => None

  /** The positions of one storage trie's rows: a prefix scan of CF `u`, merged with the layers above the disk. It
    * serves the replacement of a whole storage trie (data-model 3.6) and arrives with the staged commit (C3b).
    */
  def ownerRows(owner: ByteString): Iterator[PositionKey] =
    throw new UnsupportedOperationException(
      s"The rows of the storage trie of ${Hex.toHexString(owner.toArray)} cannot be listed yet"
    )
