package com.chipprbots.ethereum.consensus.blocks

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.HefPostEcip1097
import com.chipprbots.ethereum.domain._
import com.chipprbots.ethereum.ledger.BloomFilter

class CheckpointBlockGeneratorSpec extends AnyFlatSpec with Matchers {

  it should "generate a proper block with checkpoint" in new TestSetup {

    val fakeCheckpoint = Checkpoint.empty

    val timestamp: Long = parentBlock.header.unixTimestamp + 1

    val generatedBlock: Block = checkpointBlockGenerator.generate(parentBlock, fakeCheckpoint)

    val expectedBlock: Block = Block(
      BlockHeader(
        parentHash = parentBlock.hash,
        ommersHash = BlockHeader.EmptyOmmers,
        beneficiary = BlockHeader.EmptyBeneficiary,
        stateRoot = parentBlock.header.stateRoot,
        transactionsRoot = BlockHeader.EmptyMpt,
        receiptsRoot = BlockHeader.EmptyMpt,
        logsBloom = BloomFilter.EmptyBloomFilter,
        difficulty = parentBlock.header.difficulty,
        number = parentBlock.number + 1,
        gasLimit = parentBlock.header.gasLimit,
        gasUsed = UInt256.Zero,
        unixTimestamp = timestamp,
        extraData = ByteString.empty,
        mixHash = ByteString.empty,
        nonce = ByteString.empty,
        extraFields = HefPostEcip1097(Some(fakeCheckpoint))
      ),
      BlockBody.empty
    )

    generatedBlock shouldEqual expectedBlock
  }

  trait TestSetup {
    val parentBlock = Fixtures.Blocks.ValidBlock.block

    val checkpointBlockGenerator = new CheckpointBlockGenerator()
  }
}
