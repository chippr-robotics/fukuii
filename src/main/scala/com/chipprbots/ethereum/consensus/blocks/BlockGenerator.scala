package com.chipprbots.ethereum.consensus.blocks

import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.SignedTransaction
import com.chipprbots.ethereum.ledger.InMemoryWorldStateProxy
import com.chipprbots.ethereum.utils.BlockchainConfig

/** We use a `BlockGenerator` to create the next block. In a PoW setting, this is what a miner typically does. In
  * general, a [[BlockGenerator]] depends on and is provided by the [[Mining]].
  *
  * @note
  *   This is generally a stateful object.
  * @see
  *   [[Mining#blockGenerator]], [[com.chipprbots.ethereum.ledger.BlockPreparator BlockPreparator]]
  */
trait BlockGenerator {

  /** The type of consensus-specific data used in the block generation process. For example, under
    * [[com.chipprbots.ethereum.consensus.pow.PoWMining EthashConsensus]], this represents the
    * [[com.chipprbots.ethereum.domain.BlockBody#uncleNodesList ommers]].
    */
  type X

  /** An empty `X` */
  def emptyX: X

  /** This function returns the block currently being mined block with highest timestamp
    */
  def getPendingBlock: Option[PendingBlock]

  def getPendingBlockAndState: Option[PendingBlockAndState]

  /** Generates the next block.
    */
  def generateBlock(
      parent: Block,
      transactions: Seq[SignedTransaction],
      beneficiary: Address,
      x: X,
      initialWorldStateBeforeExecution: Option[InMemoryWorldStateProxy]
  )(implicit blockchainConfig: BlockchainConfig): PendingBlockAndState
}

/** Internal API, used for testing.
  *
  * This is a [[BlockGenerator]] API for the needs of the test suites.
  */
trait TestBlockGenerator extends BlockGenerator {
  def blockTimestampProvider: BlockTimestampProvider

  def withBlockTimestampProvider(blockTimestampProvider: BlockTimestampProvider): TestBlockGenerator
}
