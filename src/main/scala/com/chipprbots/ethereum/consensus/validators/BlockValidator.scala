package com.chipprbots.ethereum.consensus.validators

import com.chipprbots.ethereum.consensus.validators.std.StdBlockValidator.BlockError
import com.chipprbots.ethereum.consensus.validators.std.StdBlockValidator.BlockValid
import com.chipprbots.ethereum.domain.BlockBody
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.Receipt

trait BlockValidator {
  def validateHeaderAndBody(blockHeader: BlockHeader, blockBody: BlockBody): Either[BlockError, BlockValid]

  def validateBlockAndReceipts(blockHeader: BlockHeader, receipts: Seq[Receipt]): Either[BlockError, BlockValid]
}
