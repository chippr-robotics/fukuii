package com.chipprbots.ethereum.ledger

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.domain.Block

case class PreparedBlock(
    block: Block,
    blockResult: BlockResult,
    stateRootHash: ByteString,
    updatedWorld: InMemoryWorldStateProxy
)
