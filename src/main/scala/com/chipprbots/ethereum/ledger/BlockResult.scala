package com.chipprbots.ethereum.ledger

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.domain.Receipt

case class BlockResult(
    worldState: InMemoryWorldStateProxy,
    gasUsed: BigInt = 0,
    receipts: Seq[Receipt] = Nil,
    // EIP-7685 typed requests (type_byte || data), in canonical order:
    // deposits (0x00) → withdrawals (0x01) → consolidations (0x02). Present only
    // when the block is post-Prague AND the request list is non-empty.
    executionRequests: Seq[ByteString] = Nil
)
