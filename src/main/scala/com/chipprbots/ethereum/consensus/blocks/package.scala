package com.chipprbots.ethereum.consensus

import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.Receipt
import com.chipprbots.ethereum.ledger.InMemoryWorldStateProxy

package object blocks {
  case class PendingBlock(block: Block, receipts: Seq[Receipt])
  case class PendingBlockAndState(pendingBlock: PendingBlock, worldState: InMemoryWorldStateProxy)
}
