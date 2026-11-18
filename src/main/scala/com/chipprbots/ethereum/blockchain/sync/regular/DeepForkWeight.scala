package com.chipprbots.ethereum.blockchain.sync.regular

import cats.data.NonEmptyList

import com.chipprbots.ethereum.blockchain.sync.fast.FastSyncBranchResolverActor.BeyondReachVerdict
import com.chipprbots.ethereum.domain.BlockHash
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.ChainWeight

/** The weighing of a proof-of-work branch whose common block with this node's chain lies below the floor a fork
  * recovery rewinds to, on the hash layout (B-346): whether this node's state is to be given up for it.
  *
  * The rules of the import's fork choice (`BranchResolution.compareBranch`): heavier than this node's chain, and not
  * refused by MESS (ECBP-1100). Weighed from stored chain weights, as the escalation's feed weighs a branch out of
  * reach on the unified layout (`PowEscalationFeed`): this node's blocks above the common block may be thousands, and
  * are not read. A heavier branch's headers are then validated as an import validates them, the seal with them: a
  * re-SNAP is not to be had for headers no one mined.
  *
  * @param weightOf
  *   a stored chain weight, by block hash
  * @param headerOf
  *   a stored header, by block hash
  * @param onTheChain
  *   whether a header is the chain's block at its height
  * @param head
  *   this node's head and its weight, where the weight is exact; none otherwise, and no branch is then shown heavier
  * @param messActiveAt
  *   where the chain has MESS, whether it is active at a block number
  * @param messRefuses
  *   the MESS rule: from the time since the common block, this chain's difficulty above it and the branch's
  * @param firstInvalidHeader
  *   the first header of the branch that is not valid, with why
  */
final class DeepForkWeight(
    weightOf: BlockHash => Option[ChainWeight],
    headerOf: BlockHash => Option[BlockHeader],
    onTheChain: BlockHeader => Boolean,
    head: () => Option[(BlockHeader, ChainWeight)],
    messActiveAt: Option[BigInt => Boolean],
    messRefuses: (Long, BigInt, BigInt) => Boolean,
    firstInvalidHeader: List[BlockHeader] => Option[(BlockHeader, String)]
):

  /** The verdict for the branch of `headers`, from the child of the common block up. */
  def verdict(headers: NonEmptyList[BlockHeader]): BeyondReachVerdict =
    val weighed = for
      common <- headerOf(headers.head.parentHash)
      if onTheChain(common)
      commonWeight <- weightOf(common.hash)
      (headHeader, headWeight) <- head()
    yield (common, commonWeight, headHeader, headWeight, headers.toList.foldLeft(commonWeight)(_.increase(_)))
    weighed match
      case None                                                                    => BeyondReachVerdict.NotHeavier
      case Some((_, _, _, headWeight, branchWeight)) if branchWeight <= headWeight => BeyondReachVerdict.NotHeavier
      case Some((common, commonWeight, headHeader, headWeight, branchWeight))
          if messActiveAt.exists(_(headHeader.number.value)) && messRefuses(
            math.max(0L, headHeader.unixTimestamp - common.unixTimestamp),
            headWeight.totalDifficulty.value - commonWeight.totalDifficulty.value,
            branchWeight.totalDifficulty.value - commonWeight.totalDifficulty.value
          ) =>
        // a longer branch may yet carry the weight its depth asks for
        BeyondReachVerdict.NotHeavier
      case Some(_) =>
        firstInvalidHeader(headers.toList) match
          case Some((header, why)) => BeyondReachVerdict.Invalid(s"the header of block ${header.number}: $why")
          case None                => BeyondReachVerdict.Heavier
