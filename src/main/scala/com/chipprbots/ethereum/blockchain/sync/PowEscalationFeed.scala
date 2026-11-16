package com.chipprbots.ethereum.blockchain.sync

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.db.storage.pathstate.GivenUpBranch
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
  * @param headerOf
  *   a stored header, by its hash
  * @param onTheChain
  *   whether a header is the chain's block at its height. With `headerOf`, what a branch is named from
  *   ([[PowEscalationFeed.firstAboveFork]]): the same for every cut of the branch, and what the loop guard keeps
  * @param mess
  *   the rule that refuses a reorganisation whose branch has not the weight its depth asks for (ECBP-1100), where the
  *   chain has it
  * @param note
  *   told what was left out of the count, and why
  */
final class PowEscalationFeed(
    watch: EscalationWatch,
    weightOf: BlockHash => Option[ChainWeight],
    headWeight: () => Option[ChainWeight],
    headerOf: ByteString => Option[BlockHeader] = _ => None,
    onTheChain: BlockHeader => Boolean = _ => true,
    mess: Option[PowEscalationFeed.Mess] = None,
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
    // A post-merge branch (difficulty 0) is the Engine API's: this filter is also what keeps the consensus layer's
    // refusals (`RefusedForThisHead`, `HandedToRecovery`, B-297) out of the proof-of-work count
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
              val first = firstAboveFork(branch.head.header, headerOf, onTheChain)
              if messWouldRefuse(first, of) then
                note(s"branch $name is not counted: the reorganisation to it is one MESS refuses (ECBP-1100)")
              else counted(branch, name, of, first, fault)

  private def counted(branch: List[Block], name: String, of: ChainWeight, first: BlockHeader, fault: StateFault): Unit =
    if refused.forall(_ < of) then refused = Some(of)
    val refusedBranch = EscalationWatch.Branch(
      GivenUpBranch(first.number.value, first.hash.value),
      () => lineage(branch.map(_.header), headerOf)
    )
    classify(fault) match
      case Refusal.Unreachable => watch.branchUnreachable(refusedBranch, name, fault.getMessage)
      case Refusal.Fault       => watch.branchRefused(refusedBranch, branch.last.number.value, name, fault.getMessage)

  // Whether the reorganisation to a branch of weight `of`, whose first block above the chain is `first`, is one MESS
  // refuses (forge, B-306). The head's header and the head's weight are read apart: they are of the same head because
  // the feed is told of one import at a time, after it is done (ConsensusAdapter). A batch that the fetcher resolves is never handed to consensus when it is; a block a peer
  // announces is, and without this a deep branch spread that way would be counted as the chain to follow. Where the
  // rule is in force and what it weighs is not stored, the branch is not counted either: nothing says it would pass.
  private def messWouldRefuse(first: BlockHeader, of: ChainWeight): Boolean =
    mess.exists { rule =>
      rule.head().exists { head =>
        rule.activeAt(head.number.value) && {
          val weighed = for
            fork <- headerOf(first.parentHash.value)
            // the block the two chains share is of the node's chain. Where the walk down the branch ended on a side
            // block (its reach), what would be weighed is not what the rule weighs: the branch is not counted (forge)
            if onTheChain(fork)
            forkWeight <- weightOf(first.parentHash)
            headWeight <- headWeight()
          yield rule.refuses(
            math.max(0L, head.unixTimestamp - fork.unixTimestamp),
            headWeight.totalDifficulty.value - forkWeight.totalDifficulty.value,
            of.totalDifficulty.value - forkWeight.totalDifficulty.value
          )
          weighed.getOrElse(true)
        }
      }
    }

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

  /** The rule that refuses a reorganisation whose branch has not the weight its depth asks for (MESS, ECBP-1100).
    *
    * @param activeAt
    *   whether the rule is in force at a height of the head
    * @param head
    *   the head's header
    * @param refuses
    *   whether a reorganisation is refused: the seconds from the block the chains share to the head, the weight of the
    *   node's chain above that block, and the weight of the branch above it
    */
  final case class Mess(
      activeAt: BigInt => Boolean,
      head: () => Option[BlockHeader],
      refuses: (Long, BigInt, BigInt) => Boolean
  )

  /** The first block of the branch `block` is of above the block the branch leaves the chain at: `block` itself when it
    * stands on a block of the chain, else the lowest of the blocks under it that are not of the chain. A block whose
    * header is not stored ends the walk, as does `reach` blocks: the name is then of the lowest block found. The loop
    * guard does not rest on the name being the same after a sync ([[lineage]]).
    *
    * @param headerOf
    *   a stored header, by its hash
    * @param onTheChain
    *   whether a header is the chain's block at its height
    */
  def firstAboveFork(
      block: BlockHeader,
      headerOf: ByteString => Option[BlockHeader],
      onTheChain: BlockHeader => Boolean,
      reach: Int = 1024
  ): BlockHeader =
    @scala.annotation.tailrec
    def down(at: BlockHeader, left: Int): BlockHeader =
      if left == 0 then at
      else
        headerOf(at.parentHash.value) match
          case Some(parent) if !onTheChain(parent) => down(parent, left - 1)
          case _                                   => at
    down(block, reach)

  /** The blocks of a refused branch from its tip down, then the stored blocks under them, each read as it is asked for:
    * what a branch is known again by ([[com.chipprbots.ethereum.db.storage.pathstate.GivenUpBranches]]). It ends where
    * a header is not stored; whoever walks it stops sooner.
    */
  def lineage(branch: List[BlockHeader], headerOf: ByteString => Option[BlockHeader]): Iterator[GivenUpBranch] =
    val under = branch.headOption.iterator.flatMap { lowest =>
      Iterator.unfold(lowest.parentHash.value)(hash => headerOf(hash).map(header => (header, header.parentHash.value)))
    }
    (branch.reverseIterator ++ under).map(header => GivenUpBranch(header.number.value, header.hash.value))

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
