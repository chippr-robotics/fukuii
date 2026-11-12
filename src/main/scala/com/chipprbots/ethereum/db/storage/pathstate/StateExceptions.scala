package com.chipprbots.ethereum.db.storage.pathstate

/** A condition of this node's own state storage, as opposed to anything about the block being executed (spec 018
  * data-model 6.3.2).
  *
  * It is deliberately '''not''' an `MPTException`: block execution maps every `MPTException` to an `MPTError`, and from
  * there a state hole is reported and a node fetched, or the block is answered INVALID. None of that is right for a
  * view that went stale or a state this node no longer holds. A `StateFault` is never wrapped into a `Left`: each
  * caller handles it by name (the table of data-model 6.3.2), and none of them may turn it into a verdict on a block.
  *
  * A stored row that is not the node its parent names keeps its existing type, `CorruptNodeException`, which is a
  * `MissingNodeException` and is handled as a missing node everywhere.
  */
sealed abstract class StateFault(message: String) extends RuntimeException(message)

/** A view of the state can no longer say what it held: the disk has moved since the view was made, and either the log
  * of moves no longer reaches back to it, or the row it read is another view's. Retry with a fresh view of the same
  * block.
  *
  * @param createdAtEpoch
  *   the epoch at which the view was made
  * @param epochNow
  *   the epoch when the read found it stale
  */
final class StaleStateViewException(val block: BlockRef, val createdAtEpoch: Long, val epochNow: Long, detail: String)
    extends StateFault(
      s"The view of the state at block ${block.show} is stale (made at epoch $createdAtEpoch, now $epochNow): $detail"
    )

/** There is no layer, record or path from the anchor for the state asked for.
  *
  * @param askAgain
  *   the state is held, and is not handed out right now: as many readers as may are being served, and the one who
  *   asked asks again. Not so when the state is not held at all, which asking again does not change
  */
final class StateUnavailable(val reason: String, val askAgain: Boolean = false)
    extends StateFault(s"State unavailable: $reason")

/** A view over forward layers met a node that is not the one its parent names, with the disk under it at rest: the
  * layers and the rows do not make up the state the view is of. It is no fault of a block and no stale read: reading
  * again finds the same. The layers are dropped and their blocks executed again. It is never taken for a corrupt row:
  * what a corrupt row is answered with deletes it, and this row may be another state's good node (beacon).
  */
final class LayerInconsistent(val block: BlockRef, detail: String)
    extends StateFault(
      s"The executed blocks kept for the state at block ${block.show} do not make up that state: $detail"
    )

/** Why a move of the head was refused. */
enum ReorgRefusal:

  /** The fork point lies further below the head than the retained records reach. */
  case Deep

  /** A block on the way has no complete record. */
  case Incomplete

  /** The move would cross the block this datadir's state started from. */
  case Pivot

/** A move of the head that was refused before anything was changed. */
final class ReorgTooDeep(val reason: ReorgRefusal, detail: String)
    extends StateFault(s"Head move refused ($reason): $detail")

/** A branch under execution holds more than the pinned-layer cap allows. Retryable, and never an invalid block. */
final class BranchTooLarge(val bytes: Long, val cap: Long)
    extends StateFault(s"The branch under execution holds $bytes bytes of state changes, above the cap of $cap")
