package com.chipprbots.ethereum.ledger

import cats.data.NonEmptyList

import com.chipprbots.ethereum.consensus.mess.ArtificialFinality
import com.chipprbots.ethereum.consensus.mess.MESSConfig
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.utils.ByteStringUtils.hash2string
import com.chipprbots.ethereum.utils.Logger

class BranchResolution(blockchainReader: BlockchainReader) extends Logger {

  /** Optional MESS config for anti-reorg protection. Set by SyncController when configured. */
  private[ethereum] var messConfig: Option[MESSConfig] = None

  def resolveBranch(headers: NonEmptyList[BlockHeader]): BranchResolutionResult =
    if (!doHeadersFormChain(headers)) {
      InvalidBranch
    } else {
      val knownParentOrGenesis = blockchainReader
        .isInChain(
          blockchainReader.getBestBranch(),
          headers.head.parentHash
        ) || headers.head.hash == blockchainReader.genesisHeader.hash

      if (!knownParentOrGenesis)
        UnknownBranch
      else
        compareBranch(headers)
    }

  private[ledger] def doHeadersFormChain(headers: NonEmptyList[BlockHeader]): Boolean =
    headers.toList.zip(headers.tail).forall { case (parent, child) =>
      parent.hash == child.parentHash && parent.number + 1 == child.number
    }

  private[ledger] def compareBranch(headers: NonEmptyList[BlockHeader]): BranchResolutionResult = {
    val headersList = headers.toList
    val oldBlocksWithCommonPrefix = getTopBlocksFromNumber(headers.head.number)

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

    maybeParentWeight match {
      case Some(Right(parentWeight)) =>
        val oldWeight = oldBlocks.foldLeft(parentWeight) { (w, b) =>
          w.increase(b.header)
        }
        val newWeight = newHeaders.foldLeft(parentWeight) { (w, h) =>
          w.increase(h)
        }

        if (newWeight > oldWeight) {
          // New branch has higher TD — check MESS anti-reorg protection
          if (shouldMessReject(oldBlocks, newHeaders)) {
            NoChainSwitch
          } else {
            NewBetterBranch(oldBlocks)
          }
        } else if (newWeight == oldWeight && newHeaders.nonEmpty && oldBlocks.isEmpty) {
          // Post-merge: all blocks have difficulty=0, so weight never increases.
          // If the new branch extends the chain without conflicting (no old blocks to replace),
          // accept it. This is the normal case for regular sync importing new blocks.
          NewBetterBranch(Nil)
        } else {
          NoChainSwitch
        }

      case Some(Left(err)) =>
        log.error(err)
        NoChainSwitch

      case None =>
        // after removing common prefix both 'new' and 'old` were empty
        log.warn("Attempted to compare identical branches")
        NoChainSwitch
    }
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
    messConfig match {
      case Some(config) if oldBlocks.nonEmpty =>
        val currentHeadNumber = oldBlocks.last.header.number
        if (!config.isActiveAtBlock(currentHeadNumber)) return false

        val commonAncestorTimestamp = blockchainReader
          .getBlockHeaderByHash(oldBlocks.head.header.parentHash)
          .map(_.unixTimestamp)
          .getOrElse(0L)

        val currentHeadTimestamp = oldBlocks.last.header.unixTimestamp
        val timeDeltaSeconds = math.max(0L, currentHeadTimestamp - commonAncestorTimestamp)

        val localSubchainTD = oldBlocks.map(_.header.difficulty).foldLeft(BigInt(0))(_ + _)
        val proposedSubchainTD = newHeaders.map(_.difficulty).foldLeft(BigInt(0))(_ + _)

        val shouldReject = ArtificialFinality.shouldRejectReorg(timeDeltaSeconds, localSubchainTD, proposedSubchainTD)

        // Compute tdr/gravity ratio for logging: got/want where got=proposedTD*128, want=polyVal*localTD
        val polyVal = ArtificialFinality.polynomialV(BigInt(timeDeltaSeconds))
        val want = polyVal * localSubchainTD
        val got = proposedSubchainTD * BigInt(128)
        val tdrRatio = if (want > 0) got.toDouble / want.toDouble else 0.0

        val commonAncestorNumber = oldBlocks.head.header.number - 1
        val commonAncestorHash = oldBlocks.head.header.parentHash
        val currentHead = oldBlocks.last.header
        val proposedTip = newHeaders.last
        val proposedSpanSeconds = math.max(0L, proposedTip.unixTimestamp - commonAncestorTimestamp)

        BlockMetrics.setMessGravity(tdrRatio)
        if (shouldReject) {
          BlockMetrics.incrementMessRejected()
          log.warn(
            s"ECBP1100-MESS status=rejected age=${timeDeltaSeconds}s span.proposed=${proposedSpanSeconds}s " +
              s"tdr/gravity=${f"$tdrRatio%.4f"} " +
              s"common.bno=$commonAncestorNumber common.hash=${hash2string(commonAncestorHash).take(8)} " +
              s"current.bno=${currentHead.number} current.hash=${hash2string(currentHead.hash).take(8)} " +
              s"proposed.bno=${proposedTip.number} proposed.hash=${hash2string(proposedTip.hash).take(8)}"
          )
        } else if (currentHead.number - commonAncestorNumber > 2) {
          // Log MESS acceptance only for non-trivial reorgs (> 2 blocks), matching core-geth forkchoice.go:177
          BlockMetrics.incrementMessAccepted()
          log.info(
            s"ECBP1100-MESS status=accepted age=${timeDeltaSeconds}s span.proposed=${proposedSpanSeconds}s " +
              s"tdr/gravity=${f"$tdrRatio%.4f"} " +
              s"common.bno=$commonAncestorNumber common.hash=${hash2string(commonAncestorHash).take(8)} " +
              s"current.bno=${currentHead.number} current.hash=${hash2string(currentHead.hash).take(8)} " +
              s"proposed.bno=${proposedTip.number} proposed.hash=${hash2string(proposedTip.hash).take(8)}"
          )
        } else {
          BlockMetrics.incrementMessAccepted()
        }

        shouldReject

      case _ => false
    }

  private def getTopBlocksFromNumber(from: BigInt): List[Block] = {
    val bestBranch = blockchainReader.getBestBranch()
    (from to blockchainReader.getBestBlockNumber())
      .flatMap(nb => blockchainReader.getBlockByNumber(bestBranch, nb))
      .toList
  }
}

sealed trait BranchResolutionResult

case class NewBetterBranch(oldBranch: Seq[Block]) extends BranchResolutionResult

case object NoChainSwitch extends BranchResolutionResult

case object UnknownBranch extends BranchResolutionResult

case object InvalidBranch extends BranchResolutionResult
