package com.chipprbots.ethereum.blockchain.sync

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.consensus.PowForkChoice
import com.chipprbots.ethereum.db.storage.pathstate.GivenUpBranch
import com.chipprbots.ethereum.db.storage.pathstate.ReorgRefusal
import com.chipprbots.ethereum.db.storage.pathstate.ReorgTooDeep
import com.chipprbots.ethereum.db.storage.pathstate.StateBusy
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
  * @param chainHashAt
  *   the hash of the chain's block at a height, by the hash index: whether a block whose header is not stored is the
  *   node's own (B-383)
  * @param head
  *   the head's header, of the head `headWeight` weighs: where the weight of the block a branch stands on is not
  *   stored, it is that weight less the difficulty of the head's blocks above that block, found by their parent hashes
  *   (B-385); and the head a branch of the same weight is set against by the tie rule (B-284)
  * @param forkChoice
  *   the import's fork choice by weight, core-geth's `ReorgNeeded` with its tie rule (B-284): a node on a chain that
  *   declares no terminal total difficulty gives its consensus's, so that a branch is counted as the chain to follow by
  *   the rule the import follows chains by, a tie a coin decides as the import decided it. The default keeps the head
  *   on a tie, as this did before
  */
final class PowEscalationFeed(
    watch: EscalationWatch,
    weightOf: BlockHash => Option[ChainWeight],
    headWeight: () => Option[ChainWeight],
    headerOf: ByteString => Option[BlockHeader] = _ => None,
    onTheChain: BlockHeader => Boolean = _ => true,
    mess: Option[PowEscalationFeed.Mess] = None,
    note: String => Unit = _ => (),
    chainHashAt: BigInt => Option[BlockHash] = _ => None,
    head: () => Option[BlockHeader] = () => None,
    forkChoice: PowForkChoice = PowForkChoice.HeavierOnly
):
  import PowEscalationFeed.*

  @volatile private var refused: Option[ChainWeight] = None

  /** The weight of the heaviest branch that stands refused, if one does. */
  def refusedWeight: Option[ChainWeight] = refused

  /** `branch`, in the order of its blocks and as it was handed to consensus, was not judged: this node's state could
    * not take it. `firstInvalidHeader` says which header of a branch is not valid, and why, by every rule of a header,
    * the seal with it; it is asked only for a branch that would be counted. As [[notJudgedHeaders]], of its headers.
    */
  def notJudged(
      branch: List[Block],
      fault: StateFault,
      firstInvalidHeader: List[Block] => Option[(BlockHeader, String)]
  ): Unit =
    // the headers are the branch's, in its order: what is validated is the branch itself
    notJudgedHeaders(branch.map(_.header), fault, _ => firstInvalidHeader(branch))

  /** A branch known by its `headers` alone, in their order, was refused before any of its blocks was fetched: regular
    * sync could not fetch it whole, or the head could not be moved back to the block it leaves the chain at (B-300 g).
    * Counted by the same guards as a branch handed to consensus ([[notJudged]]): heavier than the head, with headers
    * that are valid, and not refused by MESS.
    */
  def notJudgedHeaders(
      headers: List[BlockHeader],
      fault: StateFault,
      firstInvalidHeader: List[BlockHeader] => Option[(BlockHeader, String)]
  ): Unit =
    // A post-merge branch (difficulty 0) is the Engine API's: this filter is also what keeps the consensus layer's
    // refusals (`RefusedForThisHead`, `HandedToRecovery`, B-297) out of the proof-of-work count, and keeps the feed
    // silent on proof of stake, a `StateBusy` included (beacon)
    if headers.nonEmpty && headers.last.difficulty.value > 0 then
      val name = s"[${headers.head.number}-${headers.last.number}]"
      // A branch refused because another thread's adoption holds the head (`StateBusy`, B-462) is no fault of this
      // node and no verdict on the branch: it is asked for again once the adoption ends. Never counted, and its weight
      // is not taken as refused, so that it cannot hold off the end of a run when the head moves (forge, 2026-11-24).
      // No bound: an adoption that never ends is said by the store, and by its gauge
      if fault.isInstanceOf[StateBusy] then
        note(s"branch $name is not counted: another adoption holds the head (${fault.getMessage})")
      else
        (weighed(headers, forkWeightOf), headWeight()) match
          case (None, _) | (_, None) => note(s"branch $name is not counted: its weight, or the head's, is not stored")
          case (Some(of), Some(headAt)) if !takesTheHead(headers.last, of, headAt) =>
            note(s"branch $name is not counted: it does not outweigh the head")
          case (Some(of), _) =>
            firstInvalidHeader(headers) match
              case Some((header, why)) =>
                note(s"branch $name is not counted: the header of its block ${header.number} is not valid ($why)")
              case None =>
                val first = firstAboveFork(headers.head, headerOf, onTheChain)
                messLeavesOut(first, of) match
                  case Some(why) => note(s"branch $name is not counted: $why")
                  case None      => counted(headers, name, of, first, fault)

  /** Whether the branch of `headers`, from the stored weight of the block it stands on, outweighs the head, by the
    * weights a branch is counted by. False where either weight is not stored. It reads and says only: nothing is
    * counted (B-300 g: a fetch of a branch's headers stops once they are enough).
    */
  def outweighsTheHead(headers: List[BlockHeader]): Boolean =
    headers.nonEmpty && (for
      of <- weighed(headers)
      headAt <- headWeight()
    yield takesTheHead(headers.last, of, headAt)).contains(true)

  // Whether a branch whose last header is `tip`, of weight `of`, takes the head, of weight `headAt`, by the import's
  // fork choice (B-284): heavier, or a tie it wins. Strictly heavier where the head's header is not given
  private def takesTheHead(tip: BlockHeader, of: ChainWeight, headAt: ChainWeight): Boolean =
    head().fold(of > headAt)(current => forkChoice.reorgNeeded(current, headAt, tip, of))

  // the chain weight at the last of `headers`, from the weight `of` gives the block the first stands on
  private def weighed(
      headers: List[BlockHeader],
      of: BlockHash => Option[ChainWeight] = weightOf
  ): Option[ChainWeight] =
    headers.headOption.flatMap(first => of(first.parentHash)).map(headers.foldLeft(_)(_.increase(_)))

  // The weight of the block of `hash`: as stored, or, where it is not stored and the block is of the head's ancestry,
  // the head's weight less the difficulty of the head's blocks above it (B-385). The branch is then weighed against the
  // head by the difficulty of each above the block they share, as `BranchResolution.compareBranch` weighs it: whatever
  // the two weights rest on cancels. The blocks above it are found by their parent hashes from the head, not by the
  // hash index (ancestry-not-index), up to `ForkWeightReach` of them; a header not stored on the way, or the head's
  // weight not exact, and nothing is weighed.
  private def forkWeightOf(hash: BlockHash): Option[ChainWeight] =
    weightOf(hash).orElse {
      val derived = for
        top <- head()
        weight <- headWeight()
        above <- difficultyAbove(top, hash, headerOf)
        if above <= weight.totalDifficulty.value
      yield ChainWeight.totalDifficultyOnly(weight.totalDifficulty.value - above)
      derived.foreach(of =>
        note(
          s"the weight of block ${hash.toHexString.take(10)} is not stored: it is " +
            s"weighed from the head, ${of.totalDifficulty.value} (B-385)"
        )
      )
      derived
    }

  private def counted(
      headers: List[BlockHeader],
      name: String,
      of: ChainWeight,
      first: BlockHeader,
      fault: StateFault
  ): Unit =
    if refused.forall(_ < of) then refused = Some(of)
    val refusedBranch = EscalationWatch.Branch(
      GivenUpBranch(first.number.value, first.hash.value),
      () => lineage(headers, headerOf)
    )
    classify(fault) match
      case Refusal.Unreachable => watch.branchUnreachable(refusedBranch, name, fault.getMessage)
      case Refusal.Fault       => watch.branchRefused(refusedBranch, headers.last.number.value, name, fault.getMessage)

  // Why the reorganisation to a branch of weight `of`, whose first block above the chain is `first`, keeps it out of the
  // count where MESS is in force (forge, B-306), if it does. The head's header and the head's weight are read apart:
  // they are of the same head because the feed is told of one import at a time, after it is done (ConsensusAdapter). A
  // batch that the fetcher resolves is never handed to consensus when MESS refuses it; a block a peer announces is, and
  // without this a deep branch spread that way would be counted as the chain to follow.
  //
  // The header of the block the branch stands on not stored, where the hash index names that block as this node's at
  // its height, is a hole in this node's datadir, and MESS does not refuse on it (B-383, spec 018 C6 PR-B,
  // BranchResolution.messRejects): the branch is judged by weight and consensus refuses it as a fault of this node
  // (`StateUnavailable`). It is counted as that fault, by how far it grows; read as a refusal by MESS it was never
  // counted, and the node waited with no end. Its first header reaches here by its seal alone: the validation the feed
  // is given checks it without its parent rules there, and only there (B-391,
  // BlockValidation.firstInvalidHeaderOfNotJudged). Where the block is not known to be the node's, or the walk down
  // the branch ended on a side block (its reach), what would be weighed is not what the rule weighs: not counted
  // (forge).
  private def messLeavesOut(first: BlockHeader, of: ChainWeight): Option[String] =
    for
      rule <- mess
      head <- rule.head()
      if rule.activeAt(head.number.value)
      why <- messWeighs(rule, head, first, of)
    yield why

  private def messWeighs(rule: Mess, head: BlockHeader, first: BlockHeader, of: ChainWeight): Option[String] =
    headerOf(first.parentHash.value) match
      case Some(fork) if onTheChain(fork) =>
        val weighed = for
          forkWeight <- forkWeightOf(first.parentHash)
          headWeight <- headWeight()
        yield rule.refuses(
          math.max(0L, head.unixTimestamp - fork.unixTimestamp),
          headWeight.totalDifficulty.value - forkWeight.totalDifficulty.value,
          of.totalDifficulty.value - forkWeight.totalDifficulty.value
        )
        weighed match
          case Some(false) => None
          case Some(true)  => Some("the reorganisation to it is one MESS refuses (ECBP-1100)")
          case None        => Some("MESS is in force and the weight it weighs from the shared block is not stored")
      case Some(_) =>
        Some("MESS is in force and the walk down the branch ended on a side block: the shared block is not known")
      case None if first.number.value > 0 && chainHashAt(first.number.value - 1).contains(first.parentHash) =>
        note(
          s"MESS cannot weigh the branch from block ${first.number}: the header of this node's block " +
            s"${first.number.value - 1} is not stored; it is counted as a fault of this node (B-383)"
        )
        None
      case None =>
        Some(
          s"MESS is in force and the header of the block under block ${first.number} is not stored, and it is " +
            "not this node's block at its height: the shared block is not known"
        )

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

  /** How many of the head's blocks are walked to weigh a block whose weight is not stored (B-385): as far as the walk
    * down a branch reaches ([[firstAboveFork]]).
    */
  val ForkWeightReach: Int = 1024

  /** The difficulty of the blocks of `top`'s ancestry above the block of `hash`, `top` with them, found by their parent
    * hashes. None where the block is not met within `reach` blocks, or a header on the way is not stored. The header of
    * the block of `hash` itself is not read.
    */
  def difficultyAbove(
      top: BlockHeader,
      hash: BlockHash,
      headerOf: ByteString => Option[BlockHeader],
      reach: Int = ForkWeightReach
  ): Option[BigInt] =
    @scala.annotation.tailrec
    def down(at: BlockHeader, sum: BigInt, left: Int): Option[BigInt] =
      if at.parentHash == hash then Some(sum + at.difficulty.value)
      else if left <= 1 then None
      else
        headerOf(at.parentHash.value) match
          case Some(parent) => down(parent, sum + at.difficulty.value, left - 1)
          case None         => None
    if top.hash == hash then Some(BigInt(0)) else down(top, BigInt(0), reach)

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
    * its depth: below what the changeset records reach, across a block with no whole record, below the block the state
    * began at; or where the branch reaches further above the fork than regular sync can fetch whole. Records that are
    * corrupt are this datadir's fault and wait, as every other fault of the node does: a block that fails when it is
    * executed again, a state that is held back after a failed write, a state that is not held. One that passes (the
    * head moved under the branch) is gone at the next try, and the run with it.
    */
  def classify(fault: StateFault): Refusal = fault match
    // never met: a `StateBusy` is left out before anything is counted ([[PowEscalationFeed.notJudgedHeaders]], B-462)
    // `Finalized` is Unreachable too, and never met on proof of work, which finalizes nothing. `BeyondTheFetch` is a
    // branch regular sync cannot fetch whole, which no wait mends either (B-300 g)
    case deep: ReorgTooDeep if deep.reason != ReorgRefusal.Corrupt => Refusal.Unreachable
    case _                                                         => Refusal.Fault
