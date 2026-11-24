package com.chipprbots.ethereum.blockchain.sync.regular

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.db.storage.pathstate.StateFault
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.ledger.BlockData
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.MissingNodeException
import com.chipprbots.ethereum.utils.Hex

sealed trait BlockImportResult

case class BlockImportedToTop(blockImportData: List[BlockData]) extends BlockImportResult

case object BlockEnqueued extends BlockImportResult

case object DuplicateBlock extends BlockImportResult

/** The head moved onto `newBranch` (ascending, the new head last, one to one with `weights`), off `oldBranch`. The last
  * `branchBlocks` of `newBranch` are the blocks imported and executed by this call; the ones before them are side
  * blocks stored earlier that the branch stands on, canonical now (B-344).
  */
case class ChainReorganised(
    oldBranch: List[Block],
    newBranch: List[Block],
    weights: List[ChainWeight],
    branchBlocks: Int
) extends BlockImportResult

case class BlockImportFailed(error: String) extends BlockImportResult

/** A batch that moved the head part of the way (B-338): `headMove` is what it made the head, as an import that went
  * through tells it (an extension of the head, or a reorganisation with the blocks it moved off), and then block
  * `failingBlockHash` of it failed on its own `error`, a verdict on the block and never a fault of this node's state
  * (those are answered as [[BlockImportFailedAt]] with the prefix, or [[BlockImportStateFault]]).
  *
  * WHY ONE RESULT FOR BOTH. Before B-338 a partial extension was answered as [[BlockImportedToTop]], which dropped the
  * error, and a partial reorganisation as a bare [[BlockImportFailedAt]], which dropped the head's move: the pool was
  * not told of the blocks moved onto and off, and the importer counted nothing of the batch as imported and blamed the
  * batch's first block. core-geth's downloader inserts the prefix (`insertChain` writes it and the pool resets on the
  * new head) and drops the peer that served the block that failed. So the importer handles this as both: the head's
  * move as the import it is (pool, new-head subscribers, progress), through the same calls as [[BlockImportedToTop]]
  * and [[ChainReorganised]]; and the failing block as a failed one (a strike, and the fetcher rewound to it, blaming
  * the peer that served it).
  */
final case class ImportedPartially(
    headMove: BlockImportedToTop | ChainReorganised,
    failingBlockHash: ByteString,
    error: String
) extends BlockImportResult:

  /** Short: the error is what is read from it (the gas-used arm reads its text), the blocks are not. */
  override def toString: String =
    val moved = headMove match
      case BlockImportedToTop(data)          => s"extended the head by ${data.size} block(s)"
      case ChainReorganised(off, onto, _, _) => s"reorganised ${off.size} block(s) off, ${onto.size} onto"
    s"ImportedPartially($moved, then block ${Hex.toHexString(failingBlockHash.toArray).take(16)} " +
      s"failed: $error)"

  /** The answer a caller that is not regular sync's importer (test mode, the chain-file import) had for this before
    * B-338, which it keeps: the extension, or the reorganisation's failure at its failing block.
    */
  def answeredAsBefore: BlockImportResult = headMove match
    case extended: BlockImportedToTop => extended
    case _: ChainReorganised          => new BlockImportFailedAt(error, failingBlockHash)

/** A [[BlockImportFailed]] that also names the block execution stopped at.
  *
  * WHY THIS EXISTS. A failed reorganisation executes a prefix of the batch before it stops, so the failing block is
  * generally NOT the batch head. Collapsed to a bare `BlockImportFailed(error)`, that fact was lost, and
  * `BlockImporter`'s gas-used arm reported the batch HEAD — plus every hash-linked block behind it — as consensus-
  * invalid, with the head's parent as latestValidHash. Source-traced shape (hive `Invalid Missing Ancestor Syncing
  * ReOrg, GasUsed, CanonicalReOrg=True`): honest side blocks P1'..P7' poisoned, and the correct P8'/P9' verdict (lvh =
  * P7') overwritten with lvh = the fork point.
  *
  * WHY A SUBCLASS AND NOT A NEW FIELD. `BlockImportFailed` is matched as `BlockImportFailed(e)` /
  * `BlockImportFailed(_)` across the shared import path (BlockImporter, ImportMessages) and compared by equality in
  * tests; ETC/Mordor/ Gorgoroth run through all of it. A subclass inherits the extractor, `equals`, `hashCode` and
  * `toString` unchanged, so every existing consumer sees exactly the `BlockImportFailed(error)` it saw before. Only the
  * invalid-chain report in `BlockImporter` type-tests for this class and reads `failingBlockHash`.
  *
  * `imported` is the prefix of the batch that became the head before the failing block, set only when the failure is a
  * fault of this node's own state (`Consensus.isLocalStateFault`, B-336): those blocks are imported, and are accounted
  * for and announced as any imported blocks are, so that the escalation starts at the failing block. Empty otherwise: a
  * block's own error after a prefix that became the head is answered as [[ImportedPartially]] (B-338).
  */
final class BlockImportFailedAt(
    message: String,
    val failingBlockHash: ByteString,
    val imported: List[BlockData] = Nil
) extends BlockImportFailed(message)

object BlockImportFailedAt:
  /** The prefix a failed import adopted before its failing block: [[BlockImportFailedAt.imported]], or nothing. */
  def importedBefore(result: BlockImportResult): List[BlockData] = result match
    case at: BlockImportFailedAt => at.imported
    case _                       => Nil

/** `imported` is the validated prefix of the batch that was adopted before the missing node was hit; it is announced
  * like any imported blocks, and the retry starts at the first block after it.
  */
case class BlockImportFailedDueToMissingNode(reason: MissingNodeException, imported: List[BlockData] = Nil)
    extends BlockImportResult

/** This node's own state could not take a block of the batch (spec 018 Track C, C4b): see
  * [[com.chipprbots.ethereum.consensus.Consensus.ConsensusStateFault]]. `imported` is the prefix that was adopted
  * before it and is accounted for like any imported blocks; the blocks after it were not judged and are tried again.
  *
  * A result of its own and no [[BlockImportFailed]]: a failed import is a strike against the block and, in the end,
  * against the peer that sent it, and a fault of this node's state must reach neither. Being a case of its own, every
  * place that tells the results apart has to say what it does with it.
  */
case class BlockImportStateFault(fault: StateFault, imported: List[BlockData] = Nil) extends BlockImportResult

case object UnknownParent extends BlockImportResult
