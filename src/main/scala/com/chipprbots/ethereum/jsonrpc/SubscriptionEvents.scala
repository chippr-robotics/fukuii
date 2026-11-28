package com.chipprbots.ethereum.jsonrpc

import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.SignedTransactionWithSender

/** Events published to ActorSystem.eventStream for WS subscription delivery.
  *
  * Besu reference: SubscriptionManager.java listens to BlockAddedObserver (block events) and
  * PendingTransactionAddedListener (pending tx events). We use ActorSystem.eventStream as the Pekko equivalent of
  * Vert.x EventBus — no Vert.x verticle dependency needed.
  */
sealed trait BlockchainEvent

/** A block made canonical. `head` is false for a block below the new head that the same move made canonical (an Engine
  * API fork choice that adopts several blocks at once, B-245): its logs are delivered, but `newHeads` announces the
  * head only, as go-ethereum's `SetCanonical` sends one `ChainEvent`, for the head, and its `reorg` sends the adopted
  * blocks' logs alone.
  */
case class NewBlockImported(block: Block, head: Boolean = true) extends BlockchainEvent
case class NewPendingTransaction(stx: SignedTransactionWithSender) extends BlockchainEvent
