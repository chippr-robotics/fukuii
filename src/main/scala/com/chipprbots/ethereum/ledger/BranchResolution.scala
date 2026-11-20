package com.chipprbots.ethereum.ledger

import org.apache.pekko.util.ByteString

import cats.data.NonEmptyList

import com.chipprbots.ethereum.consensus.engine.DesignatedHead
import com.chipprbots.ethereum.consensus.mess.ArtificialFinality
import com.chipprbots.ethereum.consensus.mess.MESSConfig
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.domain.Timestamp
import com.chipprbots.ethereum.utils.ByteStringUtils.hash2string
import com.chipprbots.ethereum.utils.Logger

/** @param designatedHead
  *   PoS fork choice, or `None`. `None` on every PoW chain: SyncController supplies a `Some` only when
  *   terminal-total-difficulty is configured (see [[DesignatedHead]]). The default is `None` too, so any construction
  *   site that does not explicitly opt in keeps the pre-merge behaviour untouched.
  */
class BranchResolution(
    blockchainReader: BlockchainReader,
    designatedHead: Option[DesignatedHead] = None
) extends Logger:

  /** Optional MESS config for anti-reorg protection. Set by SyncController when configured. */
  private[ethereum] var messConfig: Option[MESSConfig] = None

  /** Does this import path follow a consensus layer, i.e. is this a post-merge (terminal-total-difficulty) chain?
    *
    * Exactly the gate of the PoS arm below: `designatedHead` is `Some` only when SyncController sees a configured
    * terminal-total-difficulty, which no ETC/Mordor/Gorgoroth config sets. `BlockImporter` reads it so that every
    * post-merge-only behaviour of the p2p import path hangs off this one gate rather than a second one that could
    * disagree with it.
    */
  def followsConsensusLayer: Boolean = designatedHead.isDefined

  def resolveBranch(headers: NonEmptyList[BlockHeader]): BranchResolutionResult =
    if !doHeadersFormChain(headers) then InvalidBranch
    else
      val knownParentOrGenesis = blockchainReader
        .isInChain(
          blockchainReader.getBestBranch,
          headers.head.parentHash
        ) || headers.head.hash == blockchainReader.genesisHeader.hash

      if !knownParentOrGenesis then UnknownBranch
      else compareBranch(headers)

  private[ledger] def doHeadersFormChain(headers: NonEmptyList[BlockHeader]): Boolean =
    headers.toList.zip(headers.tail).forall { case (parent, child) =>
      parent.hash == child.parentHash && parent.number + 1 == child.number
    }

  private[ledger] def compareBranch(headers: NonEmptyList[BlockHeader]): BranchResolutionResult =
    val headersList = headers.toList
    val own = getTopBlocksFromNumber(headers.head.number.value)
    own.firstMissing match
      // A block of this node's chain at or above the batch's first height not stored: the blocks this branch would
      // replace, and their weight, are not all known, so no fork choice is made (B-395). Not a verdict on the branch:
      // as where the weight of the block it stands on is not stored (below), NoChainSwitch with an error.
      case Some(missing) =>
        logGap("Branch not weighed", missing, headersList)
        NoChainSwitch
      case None => compareWithOwn(headersList, own.blocks)

  // compareBranch over this node's blocks from the batch's first height to the head, none missing
  private def compareWithOwn(
      headersList: List[BlockHeader],
      oldBlocksWithCommonPrefix: List[Block]
  ): BranchResolutionResult =
    val commonPrefixLength = oldBlocksWithCommonPrefix
      .zip(headersList)
      .takeWhile { case (oldBlock, newHeader) => oldBlock.header == newHeader }
      .length

    val oldBlocks = oldBlocksWithCommonPrefix.drop(commonPrefixLength)
    val newHeaders = headersList.drop(commonPrefixLength)

    val maybeParentWeight: Option[Either[String, ChainWeight]] =
      oldBlocks.headOption
        .map(_.header)
        .orElse(newHeaders.headOption)
        .map { header =>
          blockchainReader
            .getChainWeightByHash(header.parentHash)
            .toRight(s"ChainWeight for ${header.idTag} not found when resolving branch: $newHeaders")
        }

    maybeParentWeight match
      case Some(Right(parentWeight)) =>
        val oldWeight = oldBlocks.foldLeft(parentWeight) { (w, b) =>
          w.increase(b.header)
        }
        val newWeight = newHeaders.foldLeft(parentWeight) { (w, h) =>
          w.increase(h)
        }

        if newWeight > oldWeight then
          // New branch has higher TD — check MESS anti-reorg protection
          if shouldMessReject(oldBlocks, newHeaders) then NoChainSwitch
          else NewBetterBranch(oldBlocks)
        else if newWeight == oldWeight && newHeaders.nonEmpty && oldBlocks.isEmpty then
          // Post-merge: all blocks have difficulty=0, so weight never increases.
          // If the new branch extends the chain without conflicting (no old blocks to replace),
          // accept it. This is the normal case for regular sync importing new blocks.
          //
          // When the batch also RE-PRESENTS blocks we already hold as canonical (commonPrefixLength > 0) the caller
          // must be told how many, because handing consensus the whole batch sends it down the side-branch path:
          // `ConsensusImpl.handleBranchImport` sees a parent that is not the head, `importToNewBranch` finds equal
          // weight, the designated-head walk cannot reach a CL head the node is still far behind, and the answer is
          // `KeptCurrentBestBranch` — the head's own extension silently dropped. Every post-rewind batch has this shape
          // (the fetcher re-serves from below the head), so a post-merge node never recovered from its first rewind.
          // Measured on Platåberget: head stuck at 200 for `135..210` → "Imported no blocks" → `211..260` → "Unknown
          // branch" → rewind, forever (#1432). No side branch is accepted here: `oldBlocks` is empty, nothing is
          // displaced. PoS only (`followsConsensusLayer`), so a PoW chain keeps `NewBetterBranch(Nil)` here; on PoW
          // the importer drops the canonical prefix itself (BlockImporter.resolveBranch, #1592).
          if commonPrefixLength > 0 && followsConsensusLayer then ExtendsCanonicalHead(commonPrefixLength)
          else NewBetterBranch(Nil)
        else if newHeaders.nonEmpty && leadsToDesignatedHead(newHeaders) then
          // PoS arm. It replaces only the old final `else NoChainSwitch`, so whenever its predicate is false the
          // result is exactly what it was before. On ETC/Mordor/Gorgoroth the predicate is always false because
          // `designatedHead` is `None`: SyncController builds it only when terminal-total-difficulty is configured,
          // which no PoW chain config does. That TTD check is the ONLY gate on this path — Node always supplies a
          // ForkChoiceManager to sync, Engine API enabled or not. See DesignatedHead's scaladoc.
          //
          // The arm exists because the two branches above cannot decide a post-merge fork AT ALL. Every header has
          // difficulty 0, so newWeight == oldWeight always; the second branch then requires oldBlocks.isEmpty, which
          // is precisely the non-reorg case. A genuine competing branch — oldBlocks non-empty — therefore always fell
          // through to NoChainSwitch and was dropped WITHOUT BEING EXECUTED. Measured on hive `engine` 6c8bc97: that
          // is 24 `Invalid Missing Ancestor Syncing ReOrg … CanonicalReOrg=True` failures plus 4
          // `Withdrawals … Re-Org Sync` timeouts, all of which fetch the branch and then discard it here.
          //
          // The replacement test is not "heavier" — nothing is heavier post-merge — it is "did the consensus layer
          // ask for this branch". That is the correct and only fork choice rule on PoS: the CL names a head and the
          // EL follows. MESS is not consulted and must not be: it is an ECIP-1100 PoW anti-reorg rule and this arm is
          // unreachable on the chains it governs.
          NewBetterBranch(oldBlocks)
        else NoChainSwitch

      case Some(Left(err)) =>
        log.error(err)
        NoChainSwitch

      case None =>
        // after removing common prefix both 'new' and 'old` were empty
        log.warn("Attempted to compare identical branches")
        NoChainSwitch

  /** Is the tip of this candidate branch on the path to the head the consensus layer designated?
    *
    * Delegates to the shared [[DesignatedHead.leadsToDesignatedHead]] so this and `ConsensusImpl.importToNewBranch` —
    * the two sites that must agree — cannot drift apart. False whenever `designatedHead` is `None`, i.e. always on a
    * PoW chain.
    */
  private def leadsToDesignatedHead(newHeaders: List[BlockHeader]): Boolean =
    newHeaders.lastOption.exists { tip =>
      DesignatedHead.leadsToDesignatedHead(designatedHead, blockchainReader, tip.hash.value)
    }

  /** Check if MESS should reject the proposed reorg.
    *
    * Per ECIP-1100: compare subchain total difficulties with the antigravity polynomial. Time delta is computed from
    * block timestamps: current head timestamp minus common ancestor timestamp.
    */
  private def shouldMessReject(
      oldBlocks: List[Block],
      newHeaders: List[BlockHeader]
  ): Boolean =
    newHeaders.lastOption.exists { proposedTip =>
      messRejects(oldBlocks, newHeaders.map(_.difficulty.value).foldLeft(BigInt(0))(_ + _), proposedTip)
    }

  /** The same check for a proposed branch given by the difficulty of its blocks above the common block and by its tip:
    * all the check reads of it. So that a branch this node holds only the tip's record of ([[BranchNotTaken]]) is
    * judged by the one rule.
    *
    * '''The common block's header not stored is a fault of this node, and MESS does not refuse on it''' (spec 018 C6,
    * forge). The rule weighs the seconds from the common block to the head; without the common block's header there is
    * nothing to weigh. Reading its time as zero (as this did) took the age as decades, gave the maximum antigravity (31
    * times the weight) and refused a heavier branch for a hole in this node's own datadir, and a refused branch is
    * remembered as not taken: the node stopped following the chain. MESS is a node's own rule over its fork choice, not
    * a rule of block validity (ECIP-1100). core-geth does not refuse on it either: `ForkChoice.CommonAncestor` fails,
    * and `ReorgNeeded`'s error fails the import, a fault and not a verdict. Here the branch is judged by weight, as
    * with MESS off, and handed to consensus, whose reorganisation meets the same hole as a state fault (on the unified
    * layout `StateUnavailable`: the block the branch leaves the chain at is not stored): not judged, never INVALID, and
    * told to the escalation watch. Said at ERROR and counted (`chain.mess.unjudged.total`). With the header stored,
    * nothing here changes.
    */
  private def messRejects(oldBlocks: List[Block], proposedSubchainTD: BigInt, proposedTip: BlockHeader): Boolean =
    messConfig match
      case Some(config) if oldBlocks.nonEmpty && config.isActiveAtBlock(oldBlocks.last.header.number.value) =>
        blockchainReader.getBlockHeaderByHash(oldBlocks.head.header.parentHash) match
          case Some(commonAncestor) =>
            messWeighs(oldBlocks, commonAncestor.unixTimestamp, proposedSubchainTD, proposedTip)
          case None =>
            BlockMetrics.incrementMessUnjudged()
            log.error(
              "ECBP1100-MESS status=unjudged: the header of the common block is not stored, so MESS cannot weigh " +
                "this reorganisation and does not refuse it; the branch is judged by weight alone " +
                s"common.bno=${oldBlocks.head.header.number - 1} " +
                s"common.hash=${hash2string(oldBlocks.head.header.parentHash.value).take(8)} " +
                s"current.bno=${oldBlocks.last.header.number} " +
                s"proposed.bno=${proposedTip.number} proposed.hash=${hash2string(proposedTip.hash.value).take(8)}"
            )
            false

      case _ => false

  // ECBP-1100 itself, over a common block whose time is known
  private def messWeighs(
      oldBlocks: List[Block],
      commonAncestorTimestamp: Timestamp,
      proposedSubchainTD: BigInt,
      proposedTip: BlockHeader
  ): Boolean =
    val currentHeadTimestamp = oldBlocks.last.header.unixTimestamp
    val timeDeltaSeconds = math.max(0L, currentHeadTimestamp - commonAncestorTimestamp)

    val localSubchainTD = oldBlocks.map(_.header.difficulty.value).foldLeft(BigInt(0))(_ + _)

    val shouldReject = ArtificialFinality.shouldRejectReorg(timeDeltaSeconds, localSubchainTD, proposedSubchainTD)

    // Compute tdr/gravity ratio for logging: got/want where got=proposedTD*128, want=polyVal*localTD
    val polyVal = ArtificialFinality.polynomialV(BigInt(timeDeltaSeconds))
    val want = polyVal * localSubchainTD
    val got = proposedSubchainTD * BigInt(128)
    val tdrRatio = if want > 0 then got.toDouble / want.toDouble else 0.0

    val commonAncestorNumber = oldBlocks.head.header.number - 1
    val commonAncestorHash = oldBlocks.head.header.parentHash
    val currentHead = oldBlocks.last.header
    val proposedSpanSeconds = math.max(0L, proposedTip.unixTimestamp - commonAncestorTimestamp)

    BlockMetrics.setMessGravity(tdrRatio)
    if shouldReject then
      BlockMetrics.incrementMessRejected()
      log.warn(
        s"ECBP1100-MESS status=rejected age=${timeDeltaSeconds}s span.proposed=${proposedSpanSeconds}s " +
          s"tdr/gravity=${f"$tdrRatio%.4f"} " +
          s"common.bno=$commonAncestorNumber common.hash=${hash2string(commonAncestorHash.value).take(8)} " +
          s"current.bno=${currentHead.number} current.hash=${hash2string(currentHead.hash.value).take(8)} " +
          s"proposed.bno=${proposedTip.number} proposed.hash=${hash2string(proposedTip.hash.value).take(8)}"
      )
    else if (currentHead.number - commonAncestorNumber).value > 2 then
      // Log MESS acceptance only for non-trivial reorgs (> 2 blocks), matching core-geth forkchoice.go:177
      BlockMetrics.incrementMessAccepted()
      log.info(
        s"ECBP1100-MESS status=accepted age=${timeDeltaSeconds}s span.proposed=${proposedSpanSeconds}s " +
          s"tdr/gravity=${f"$tdrRatio%.4f"} " +
          s"common.bno=$commonAncestorNumber common.hash=${hash2string(commonAncestorHash.value).take(8)} " +
          s"current.bno=${currentHead.number} current.hash=${hash2string(currentHead.hash.value).take(8)} " +
          s"proposed.bno=${proposedTip.number} proposed.hash=${hash2string(proposedTip.hash.value).take(8)}"
      )
    else BlockMetrics.incrementMessAccepted()

    shouldReject

  /** The record of a side branch that was weighed against this node's chain and not taken (B-300 f; forge): what is
    * needed to weigh it again when more of it arrives, without fetching it again. None when the headers hold no block
    * that is not this node's, or the weight of the block they stand on is not stored, or a block of this node's chain
    * is not stored where the headers might still be its own (B-395).
    */
  def notTaken(headers: NonEmptyList[BlockHeader]): Option[BranchNotTaken] =
    val headersList = headers.toList
    for
      // a gap in this node's chain where the headers might still be its own: the block they leave it at is not known
      shared <- getTopBlocksFromNumber(headers.head.number.value).sharedWith(headersList)
      first <- headersList.drop(shared).headOption
      parentWeight <- blockchainReader.getChainWeightByHash(first.parentHash)
    yield
      val side = headersList.drop(shared)
      BranchNotTaken(
        tip = side.last,
        commonBlock = first.number.value - 1,
        commonHash = first.parentHash.value,
        weight = side.foldLeft(parentWeight)((weight, header) => weight.increase(header)),
        difficultyAboveTheCommonBlock = side.map(_.difficulty.value).foldLeft(BigInt(0))(_ + _)
      )

  /** The headers of `headers` above those this node holds, where the chain weight of the block they stand on is not
    * stored: the branch [[compareBranch]] cannot weigh, and answers `NoChainSwitch` for, with an error (B-385). None
    * where that weight is stored, where no header is above those this node holds, and where a block of this node's
    * chain is not stored where the headers might still be its own (B-395: the block weighed from is then not known). It
    * reads only, as [[compareBranch]] does, and says nothing of whether the branch is the heavier: the escalation's
    * feed weighs it from the head (`PowEscalationFeed`).
    */
  def withoutForkWeight(headers: NonEmptyList[BlockHeader]): Option[NonEmptyList[BlockHeader]] =
    val headersList = headers.toList
    val own = getTopBlocksFromNumber(headers.head.number.value)
    for
      // where a gap in this node's chain leaves the shared blocks unknown, the block weighed from is not known either
      // (B-395): not named here, compareBranch having answered for the gap
      shared <- own.sharedWith(headersList)
      side <- NonEmptyList.fromList(headersList.drop(shared))
      // the block compareBranch weighs from: the one under this node's first block above the shared ones, else under
      // the branch's first
      under = own.blocks.drop(shared).headOption.map(_.header).getOrElse(side.head).parentHash
      if blockchainReader.getChainWeightByHash(under).isEmpty
    yield side

  /** `headers` stand on the tip of a branch that was not taken: the branch with them, and whether it would be taken
    * now. By the rules of [[compareBranch]]: heavier than this node's chain, and not refused by MESS. None when the
    * headers do not stand on that tip or do not form a chain, and when the block the branch leaves this chain at is no
    * longer on it (this chain has moved since): MESS would then be asked of another pair of branches (forge). Where a
    * block of this node's chain above the common block is not stored, not taken (B-395).
    *
    * Proof of work only: it is not asked on a chain that follows a consensus layer.
    */
  def extended(branch: BranchNotTaken, headers: NonEmptyList[BlockHeader]): Option[(BranchNotTaken, Boolean)] =
    if headers.head.parentHash != branch.tip.hash || !doHeadersFormChain(headers) then None
    else if !blockchainReader.getCanonicalHashByNumber(branch.commonBlock).exists(_.value == branch.commonHash) then
      None
    else
      val longer = branch.copy(
        tip = headers.last,
        weight = headers.foldLeft(branch.weight)((weight, header) => weight.increase(header)),
        difficultyAboveTheCommonBlock =
          branch.difficultyAboveTheCommonBlock + headers.toList.map(_.difficulty.value).foldLeft(BigInt(0))(_ + _)
      )
      getTopBlocksFromNumber(branch.commonBlock + 1) match
        // a block of this node's chain above the common block not stored: neither the head's weight is read from the
        // last block found nor MESS asked of a chain with a block left out (B-395). Not taken, as compareBranch would
        // answer, and remembered as it is
        case OwnBlocks(_, Some(missing)) =>
          logGap("Branch not taken weighed again", missing, headers.toList)
          Some((longer, false))
        case OwnBlocks(own, None) =>
          // this node's head, or the common block where the head has come down to it
          val ownWeight = blockchainReader.getChainWeightByHash(
            own.lastOption.map(_.header.hash).getOrElse(com.chipprbots.ethereum.domain.BlockHash(branch.commonHash))
          )
          val taken = ownWeight.exists(longer.weight > _) &&
            !messRejects(own, longer.difficultyAboveTheCommonBlock, longer.tip)
          Some((longer, taken))

  /** This node's blocks from `from` up to the head, by the hash index, as far as the first that is not stored (B-395).
    * A block not stored is not skipped: skipped, the blocks above it moved down one place, so that each was set against
    * the header one height below its own, and the shared prefix, the block a branch was weighed from and the blocks it
    * would replace were all one block out. Where none is missing, the blocks read and their order are those read
    * before.
    */
  private def getTopBlocksFromNumber(from: BigInt): OwnBlocks =
    val bestBranch = blockchainReader.getBestBranch
    val best = blockchainReader.getBestBlockNumber
    @scala.annotation.tailrec
    def up(number: BigInt, found: List[Block]): OwnBlocks =
      if number > best then OwnBlocks(found.reverse, None)
      else
        blockchainReader.getBlockByNumber(bestBranch, number) match
          case Some(block) => up(number + 1, block :: found)
          case None        => OwnBlocks(found.reverse, Some(number))
    up(from, Nil)

  // what a gap in this node's chain leaves undecided is said at ERROR: a hole in this node's datadir, not a verdict
  private def logGap(what: String, firstMissing: BigInt, headers: List[BlockHeader]): Unit =
    log.error(
      s"$what: block $firstMissing of this node's chain is not stored, so its blocks above it cannot be set against " +
        s"the headers ${headers.headOption.fold("-")(_.number.toString)}-${headers.lastOption.fold("-")(_.number.toString)}; " +
        "no chain switch (B-395)"
    )

/** This node's blocks from a height up to the head ([[BranchResolution]]): all of them where `firstMissing` is None,
  * else those below the first not stored.
  */
final private[ledger] case class OwnBlocks(blocks: List[Block], firstMissing: Option[BigInt]):

  /** How many of `headers` are this node's own blocks, from the first: None where they all are as far as the blocks
    * read go, a block is missing there, and `headers` go on past it. Whether the header at the missing height is this
    * node's is then not known, and a prefix counted on regardless would be one block out (B-395).
    */
  def sharedWith(headers: List[BlockHeader]): Option[Int] =
    val shared = blocks.zip(headers).takeWhile { case (block, header) => block.header == header }.length
    if firstMissing.isDefined && shared == blocks.length && headers.length > shared then None else Some(shared)

/** A side branch that was weighed and not taken, as [[BranchResolution.notTaken]] records it.
  *
  * @param tip
  *   its highest block seen
  * @param commonBlock
  *   the number of the block it leaves this node's chain at
  * @param commonHash
  *   that block's hash
  * @param weight
  *   the chain weight at its tip
  * @param difficultyAboveTheCommonBlock
  *   the sum of the difficulties of its blocks, which MESS compares with this node's
  */
final case class BranchNotTaken(
    tip: BlockHeader,
    commonBlock: BigInt,
    commonHash: ByteString,
    weight: ChainWeight,
    difficultyAboveTheCommonBlock: BigInt
)

/** The branches that were weighed and not taken, by the hash of their tip: the last `kept` remembered, the oldest
  * forgotten first (B-300 f). A branch forgotten is fetched again from 64 blocks back, as before. Owned by one actor.
  */
final class BranchesNotTaken(kept: Int):
  private var byTip: scala.collection.immutable.ListMap[ByteString, BranchNotTaken] =
    scala.collection.immutable.ListMap.empty

  /** Remembered under its tip; one remembered under that tip already is replaced, and counts as the newest. */
  def remember(branch: BranchNotTaken): Unit =
    byTip = (byTip - branch.tip.hash.value + (branch.tip.hash.value -> branch)).takeRight(kept)

  /** The branch whose tip is `parentHash`, taken out: what follows it is weighed with it, and remembered anew if it is
    * still not taken.
    */
  def take(parentHash: ByteString): Option[BranchNotTaken] =
    val found = byTip.get(parentHash)
    byTip -= parentHash
    found

  /** The branch whose tip is `parentHash`, left where it is. */
  def get(parentHash: ByteString): Option[BranchNotTaken] = byTip.get(parentHash)

  def size: Int = byTip.size

sealed trait BranchResolutionResult

case class NewBetterBranch(oldBranch: Seq[Block]) extends BranchResolutionResult

/** The batch is our own canonical chain for its first `alreadyCanonical` headers, followed by a pure extension of the
  * canonical head: nothing is displaced, so this is not a side branch and no fork choice is being made. Only the
  * extension — the batch minus that prefix — is left to import, and its parent is the head.
  *
  * Produced only on a chain that follows a consensus layer (see `BranchResolution.followsConsensusLayer`); a PoW chain
  * gets `NewBetterBranch(Nil)` for the same input, as before, and `BlockImporter` drops the prefix from it (#1592).
  */
case class ExtendsCanonicalHead(alreadyCanonical: Int) extends BranchResolutionResult

case object NoChainSwitch extends BranchResolutionResult

case object UnknownBranch extends BranchResolutionResult

case object InvalidBranch extends BranchResolutionResult
