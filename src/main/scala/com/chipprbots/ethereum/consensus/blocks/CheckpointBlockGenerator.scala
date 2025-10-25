package com.chipprbots.ethereum.consensus.blocks

import akka.util.ByteString

import com.chipprbots.ethereum.crypto.ECDSASignatureImplicits.ECDSASignatureOrdering
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.HefPostEcip1097
import com.chipprbots.ethereum.domain._
import com.chipprbots.ethereum.ledger.BloomFilter

class CheckpointBlockGenerator {

  def generate(parent: Block, checkpoint: Checkpoint): Block = {
    val blockNumber = parent.number + 1
    // we are using a predictable value for timestamp so that each federation node generates identical block
    // see ETCM-173
    val timestamp = parent.header.unixTimestamp + 1
    val checkpointWithSortedSignatures = Checkpoint(checkpoint.signatures.sorted)

    val header = BlockHeader(
      parentHash = parent.hash,
      ommersHash = BlockHeader.EmptyOmmers,
      beneficiary = BlockHeader.EmptyBeneficiary,
      difficulty = parent.header.difficulty,
      number = blockNumber,
      gasLimit = parent.header.gasLimit,
      unixTimestamp = timestamp,
      extraData = ByteString.empty,
      stateRoot = parent.header.stateRoot,
      transactionsRoot = BlockHeader.EmptyMpt,
      receiptsRoot = BlockHeader.EmptyMpt,
      logsBloom = BloomFilter.EmptyBloomFilter,
      gasUsed = UInt256.Zero,
      mixHash = ByteString.empty,
      nonce = ByteString.empty,
      extraFields = HefPostEcip1097(Some(checkpointWithSortedSignatures))
    )

    Block(header, BlockBody.empty)
  }
}
