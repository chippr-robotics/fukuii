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
    val oldBlocksWithCommonPrefix = getTopBlocksFromNumber(headers.head.number.value)

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
    */
  private def messRejects(oldBlocks: List[Block], proposedSubchainTD: BigInt, proposedTip: BlockHeader): Boolean =
    messConfig match
      case Some(config) if oldBlocks.nonEmpty && config.isActiveAtBlock(oldBlocks.last.header.number.value) =>
        val commonAncestorTimestamp = blockchainReader
          .getBlockHeaderByHash(oldBlocks.head.header.parentHash)
          .map(_.unixTimestamp)
          .getOrElse(Timestamp.Zero)

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

      case _ => false

  /** The record of a side branch that was weighed against this node's chain and not taken (B-300 f; forge): what is
    * needed to weigh it again when more of it arrives, without fetching it again. None when the headers hold no block
    * that is not this node's, or the weight of the block they stand on is not stored.
    */
  def notTaken(headers: NonEmptyList[BlockHeader]): Option[BranchNotTaken] =
    val headersList = headers.toList
    val own = getTopBlocksFromNumber(headers.head.number.value)
    val shared = own.zip(headersList).takeWhile { case (block, header) => block.header == header }.length
    for
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

  /** `headers` stand on the tip of a branch that was not taken: the branch with them, and whether it would be taken
    * now. By the rules of [[compareBranch]]: heavier than this node's chain, and not refused by MESS. None when the
    * headers do not stand on that tip or do not form a chain, and when the block the branch leaves this chain at is no
    * longer on it (this chain has moved since): MESS would then be asked of another pair of branches (forge).
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
      val own = getTopBlocksFromNumber(branch.commonBlock + 1)
      // this node's head, or the common block where the head has come down to it
      val ownWeight = blockchainReader.getChainWeightByHash(
        own.lastOption.map(_.header.hash).getOrElse(com.chipprbots.ethereum.domain.BlockHash(branch.commonHash))
      )
      val taken = ownWeight.exists(longer.weight > _) &&
        !messRejects(own, longer.difficultyAboveTheCommonBlock, longer.tip)
      Some((longer, taken))

  private def getTopBlocksFromNumber(from: BigInt): List[Block] =
    val bestBranch = blockchainReader.getBestBranch
    (from to blockchainReader.getBestBlockNumber)
      .flatMap(nb => blockchainReader.getBlockByNumber(bestBranch, nb))
      .toList

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
