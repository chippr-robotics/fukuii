package com.chipprbots.ethereum.jsonrpc.subscription

import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.SignedTransactionWithSender

/** Events published to the Pekko eventStream for subscription services.
  *
  * These are the Pekko analog of geth's event.Feed types (ChainHeadEvent, NewTxsEvent, RemovedLogsEvent).
  * Each event type has its own case class for type-safe eventStream subscriptions.
  */
object SubscriptionEvents {

  /** Published by RegularSync when a new block is imported to the canonical chain. */
  case class BlockImported(blockNumber: BigInt)

  /** Published by BlockImporter on chain reorganization.
    * removedBlocks: blocks from the old (abandoned) branch
    * addedBlocks: blocks from the new canonical branch
    */
  case class ChainReorg(removedBlocks: Seq[Block], addedBlocks: Seq[Block])

  /** Published by PendingTransactionsManager when a new transaction is added to the mempool. */
  case class TransactionAdded(stx: SignedTransactionWithSender)
}
