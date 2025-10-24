package com.chipprbots.ethereum.ledger

import com.chipprbots.ethereum.domain.Receipt

case class BlockResult(worldState: InMemoryWorldStateProxy, gasUsed: BigInt = 0, receipts: Seq[Receipt] = Nil)
