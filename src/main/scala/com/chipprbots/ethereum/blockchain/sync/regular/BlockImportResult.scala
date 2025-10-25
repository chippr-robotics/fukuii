package com.chipprbots.ethereum.blockchain.sync.regular

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

case class BlockImportFailedDueToMissingNode(reason: MissingNodeException) extends BlockImportResult

case object UnknownParent extends BlockImportResult
