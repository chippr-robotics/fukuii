package com.chipprbots.ethereum.jsonrpc.subscription

import org.apache.pekko.actor.Actor
import org.apache.pekko.actor.ActorRef
import org.apache.pekko.actor.Props

import com.chipprbots.ethereum.jsonrpc.subscription.SubscriptionEvents.TransactionAdded
import com.chipprbots.ethereum.jsonrpc.subscription.SubscriptionManager.NotifySubscribers
import com.chipprbots.ethereum.utils.Logger

/** Listens for new pending transactions and pushes notifications.
  *
  * Emulates Besu's PendingTransactionSubscriptionService — pushes tx hash
  * (or full tx if fullTx=true) to all newPendingTransactions subscribers.
  */
class PendingTxSubscriptionService(
    subscriptionManager: ActorRef
) extends Actor
    with Logger {

  override def preStart(): Unit = {
    context.system.eventStream.subscribe(self, classOf[TransactionAdded])
  }

  override def postStop(): Unit = {
    context.system.eventStream.unsubscribe(self)
  }

  override def receive: Receive = {
    case TransactionAdded(stx) =>
      val txHash = SubscriptionJsonSerializers.serializeTxHash(stx.tx.hash)
      subscriptionManager ! NotifySubscribers(SubscriptionType.NewPendingTransactions, txHash)
  }
}

object PendingTxSubscriptionService {
  def props(subscriptionManager: ActorRef): Props =
    Props(new PendingTxSubscriptionService(subscriptionManager))
}
