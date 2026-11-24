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
  *   the state is held, and is not handed out right now: as many readers as may are being served, and the one who asked
  *   asks again. Not so when the state is not held at all, which asking again does not change
  * @param heldTo
  *   from a move of the head that a block on the way has no state for: the highest block of the target's chain a move
  *   could be made to as things are. The blocks above it are executed again, and the move asked for again
  */
final class StateUnavailable(
    val reason: String,
    val askAgain: Boolean = false,
    val heldTo: Option[BlockRef] = None
) extends StateFault(s"State unavailable: $reason")

/** A write of the head was refused, with nothing written, because another thread's adoption holds the head (spec 018
  * C4e part 4, B-294, B-462): it moves the head to a branch and commits the branch in place, and may move it back. It
  * is no fault of this node's state and no verdict on any block: the writer asks again once the adoption has ended. So
  * it is never counted by the escalation (forge, 2026-11-24), and it is said at WARN with `[STATE-BUSY]`, not at ERROR.
  *
  * Not a [[StateUnavailable]], whose `askAgain` is also said by refusals that are counted: a move of the head that the
  * state moved under between two of its batches, or that no plan of could be made. Thrown only where another attempt is
  * found open ([[PathStateStorage]]`.refuseInAnotherAttempt`).
  *
  * @param holder
  *   who holds the head: the thread of the adoption that is open, and what it holds
  * @param reason
  *   what was refused, and why
  */
final class StateBusy(val holder: String, val reason: String) extends StateFault(s"State busy: $reason")

/** A block of this node's own chain that a rule needs is not stored whole: the ommers of a block are checked against
  * the blocks of its six ancestors, and after a sync that began from a pivot the bodies below the pivot arrive later
  * (spec 018 C5a part 2, B-283). It says nothing of the block being validated: with the ancestors short, a valid ommer
  * reads as one whose parent is no ancestor, and an ommer used before reads as new. Retry once the block is stored.
  *
  * @param wanted
  *   how many ancestors the rule needs
  * @param found
  *   how many of them this node has whole
  */
final class AncestorNotStored(val wanted: Int, val found: Int, detail: String)
    extends StateFault(s"Of the $wanted ancestor block(s) the ommers are checked against, $found are stored: $detail")

/** A view over forward layers met a node that is not the one its parent names, with the disk under it at rest: the
  * layers and the rows do not make up the state the view is of. It is no fault of a block and no stale read: reading
  * again finds the same. The layers are dropped and their blocks executed again. It is never taken for a corrupt row:
  * what a corrupt row is answered with deletes it, and this row may be another state's good node (beacon).
  */
final class LayerInconsistent(val block: BlockRef, detail: String)
    extends StateFault(
      s"The executed blocks kept for the state at block ${block.show} do not make up that state: $detail"
    )

/** A block this node executed and accepted before fails now that it is executed again: its state, or its configuration,
  * is not what it was. It is no verdict on the block and no fault of a peer, and trying again finds the same: of the
  * faults of a node it is the one that says the state it holds is not to be trusted (spec 018 C5a part 6).
  */
final class ReexecutionDiverged(detail: String) extends StateFault(s"A block executed before fails now: $detail")

/** A node of the state this node holds is not stored, on the unified layout (spec 018 C5a part 10). Nothing fetches it:
  * the fetch of a node by hash writes the hash-keyed store, which this layout never reads. It is a fault of this node,
  * counted towards the escalation, which gives the state up for a sync from scratch if it does not pass.
  */
final class HeldStateIncomplete(val missing: com.chipprbots.ethereum.mpt.MerklePatriciaTrie.MissingNodeException)
    extends StateFault(s"A node of the state this node holds is not stored: ${missing.getMessage}")

/** Why a move of the head was refused. */
enum ReorgRefusal:

  /** The fork point lies further below the head than the retained records reach. */
  case Deep

  /** A block on the way has no complete record. */
  case Incomplete

  /** The move would cross the block this datadir's state started from. */
  case Pivot

  /** The move would reverse the finalized block. It is the one refusal that is not answered by syncing the state again:
    * that would follow a consensus client that broke finality.
    */
  case Finalized

  /** A changeset record on the way is not there though it was not pruned, or is not its block's: this data directory's
    * records are corrupt. Never said as `Deep`, which is an ordinary answer.
    */
  case Corrupt

  /** The branch reaches further above the block it leaves the chain at than the blocks the fetcher holds ready
    * (`sync.max-ready-blocks-queue-size`), so it is never fetched whole and weighed (B-300 g; the user's decision of
    * 2026-11-16). Said by regular sync on the unified layout before anything is asked of the state, and answered as
    * `Deep` is: a sync of the state again. Never said by a move of the head itself.
    */
  case BeyondTheFetch

/** A move of the head that was refused before anything was changed. */
final class ReorgTooDeep(val reason: ReorgRefusal, detail: String)
    extends StateFault(s"Head move refused ($reason): $detail")

/** The consensus layer's branch was refused for a reason no attempt changes while the head is where it is (spec 018
  * C5a, B-297): a move back to its fork would reverse the finalized block or go deeper than the records reach, or a
  * block it stands on failed when it was executed again. It is no verdict on the branch and no fault of a peer: the
  * branch is not queued again, the importer does not fetch it again, and what the node does about a chain it cannot
  * follow is the escalation's.
  */
final class RefusedForThisHead(val cause: StateFault)
    extends StateFault(s"Refused while the head is where it is: ${cause.getMessage}")

/** The consensus layer's branch is longer than the executed blocks kept beside the disk, or forks below what a view
  * reaches, and was handed to the fork choice's recovery, which adopts it in place (spec 018 C5a, B-297). Nothing was
  * judged; the branch's blocks are stored by their hash for the recovery.
  */
final class HandedToRecovery(detail: String) extends StateFault(s"Handed to the fork choice's recovery: $detail")

/** A branch under execution holds more than the pinned-layer cap allows. Retryable, and never an invalid block. */
final class BranchTooLarge(val bytes: Long, val cap: Long)
    extends StateFault(s"The branch under execution holds $bytes bytes of state changes, above the cap of $cap")

/** A clear of the state for a sync from scratch was not begun: an attempt holds the records (an adoption of a heavier
  * branch, [[PathStateStorage.withRecordsHeld]]), and its head may yet be one the chain follows (B-465, forge). Nothing
  * was written. It cannot wait for the attempt under the lock of the moves, which the attempt's next move takes: the
  * clear is asked for again once it has ended.
  *
  * Not a [[StateFault]]: it says nothing of the state, only that this is not the time to give it up.
  */
final class StateClearRefused(detail: String)
    extends RuntimeException(s"The state was not cleared for a sync from scratch: $detail")
