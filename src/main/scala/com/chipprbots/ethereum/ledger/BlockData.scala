package com.chipprbots.ethereum.ledger

import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.domain.Receipt

case class BlockData(block: Block, receipts: Seq[Receipt], weight: ChainWeight)
