package com.chipprbots.ethereum.blockchain.sync.regular

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.ledger.BlockData
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.MissingNodeException

sealed trait BlockImportResult

case class BlockImportedToTop(blockImportData: List[BlockData]) extends BlockImportResult

case object BlockEnqueued extends BlockImportResult

case object DuplicateBlock extends BlockImportResult

case class ChainReorganised(
    oldBranch: List[Block],
    newBranch: List[Block],
    weights: List[ChainWeight]
) extends BlockImportResult

case class BlockImportFailed(error: String) extends BlockImportResult

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
  */
final class BlockImportFailedAt(message: String, val failingBlockHash: ByteString) extends BlockImportFailed(message)

case class BlockImportFailedDueToMissingNode(reason: MissingNodeException) extends BlockImportResult

case object UnknownParent extends BlockImportResult
