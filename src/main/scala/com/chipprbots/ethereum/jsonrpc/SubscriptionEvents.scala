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
case class NewBlockImported(block: Block) extends BlockchainEvent
case class NewPendingTransaction(stx: SignedTransactionWithSender) extends BlockchainEvent
