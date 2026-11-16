package com.chipprbots.ethereum.blockchain.sync

import com.chipprbots.ethereum.db.storage.pathstate.ReorgRefusal
import com.chipprbots.ethereum.db.storage.pathstate.ReorgTooDeep
import com.chipprbots.ethereum.db.storage.pathstate.StateFault
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.BlockHash
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.ChainWeight

/** What the import of proof-of-work blocks tells the [[EscalationWatch]] (spec 018 C5a part 6b): a branch that this
  * node's own state could not take, and a head that moved. It says only, as the watch does.
  *
  * '''Only a branch that outweighs the head, with headers that are valid, is counted''' (forge, 2026-11-15). A refusal
  * is met before a block of the branch is validated: the fork is looked for, and the move back to it refused, on what
  * the headers claim. A peer that sends headers with a difficulty no one mined would otherwise be able to say, for
  * nothing, that this node cannot follow the chain, and cost it its state. With the seals checked, what is refused is a
  * branch that work was spent on and that outweighs the head: the chain this node is to follow, by the rule it follows
  * chains by.
  *
  * '''A move of the head ends the run only when the head is no lighter than the branch that was refused.''' On proof of
  * stake a fork choice that is followed says the node is where the consensus client is. Here a block on the node's own
  * chain says no such thing while a heavier branch stands refused: on a chain that is split the lighter side grows too,
  * and a node kept on it by its own fault would never be told.
  *
  * '''The weights are to be exact.''' A branch is weighed against the head from the block it stands on, and a head
  * whose weight was estimated at a pivot (a block number taken for a weight) is outweighed by any block below it
  * (forge). On the unified layout a sync on a chain that chooses by weight does not hand off on an estimate (part 5b);
  * `headWeight` gives none for a head whose stored weight is not its parent's plus its own difficulty.
  *
  * '''It never fails an import''': what it is told is told after the import is done, and whatever goes wrong in it is
  * the caller's to catch and log ([[com.chipprbots.ethereum.consensus.ConsensusAdapter]] does).
  *
  * Blocks of a chain after its merge (no difficulty) are not its matter: the Engine API feeds the watch for those.
  *
  * Not safe for use from more than one thread at a time: imports are one at a time.
  *
  * @param weightOf
  *   the chain weight stored for a block, by its hash
  * @param headWeight
  *   the chain weight of the head
  * @param note
  *   told what was left out of the count, and why
  */
final class PowEscalationFeed(
    watch: EscalationWatch,
    weightOf: BlockHash => Option[ChainWeight],
    headWeight: () => Option[ChainWeight],
    note: String => Unit = _ => ()
):
  import PowEscalationFeed.*

  @volatile private var refused: Option[ChainWeight] = None

  /** The weight of the heaviest branch that stands refused, if one does. */
  def refusedWeight: Option[ChainWeight] = refused

  /** `branch`, in the order of its blocks and as it was handed to consensus, was not judged: this node's state could
    * not take it. `firstInvalidHeader` says which header of a branch is not valid, and why, by every rule of a header,
    * the seal with it; it is asked only for a branch that would be counted.
    */
  def notJudged(
      branch: List[Block],
      fault: StateFault,
      firstInvalidHeader: List[Block] => Option[(BlockHeader, String)]
  ): Unit =
    if branch.nonEmpty && branch.last.header.difficulty.value > 0 then
      val name = s"[${branch.head.number}-${branch.last.number}]"
      val weight = weightOf(branch.head.header.parentHash).map(branch.foldLeft(_)((w, b) => w.increase(b.header)))
      (weight, headWeight()) match
        case (None, _) | (_, None) => note(s"branch $name is not counted: its weight, or the head's, is not stored")
        case (Some(of), Some(head)) if of <= head =>
          note(s"branch $name is not counted: it does not outweigh the head")
        case (Some(of), _) =>
          firstInvalidHeader(branch) match
            case Some((header, why)) =>
              note(s"branch $name is not counted: the header of its block ${header.number} is not valid ($why)")
            case None =>
              if refused.forall(_ < of) then refused = Some(of)
              classify(fault) match
                case Refusal.Unreachable => watch.branchUnreachable(name, fault.getMessage)
                case Refusal.Fault =>
                  watch.branchRefused(branch.head.header.parentHash, branch.last.number.value, name, fault.getMessage)

  /** Proof-of-work blocks became the head: by extension, or by a reorganisation. */
  def headMoved(newHead: Block): Unit =
    if newHead.header.difficulty.value > 0 then
      val stillBehind = for
        of <- refused
        head <- headWeight()
      yield head < of
      if !stillBehind.contains(true) then
        refused = None
        watch.headMoved()

object PowEscalationFeed:

  /** What a refusal of a branch says to the trigger. */
  enum Refusal:
    /** The branch leaves the chain where the state does not reach: no wait mends it. */
    case Unreachable

    /** A fault of this node: counted by how far the branch grows while it is refused. */
    case Fault

  /** The table of data-model D.1 for proof of work. '''At once''' only where the move back to the fork is refused for
    * its depth: below what the changeset records reach, across a block with no whole record, or below the block the
    * state began at. Records that are corrupt are this datadir's fault and wait, as every other fault of the node does:
    * a block that fails when it is executed again, a state that is held back after a failed write, a state that is not
    * held. One that passes (the head moved under the branch) is gone at the next try, and the run with it.
    */
  def classify(fault: StateFault): Refusal = fault match
    case deep: ReorgTooDeep if deep.reason != ReorgRefusal.Corrupt => Refusal.Unreachable
    case _                                                         => Refusal.Fault
