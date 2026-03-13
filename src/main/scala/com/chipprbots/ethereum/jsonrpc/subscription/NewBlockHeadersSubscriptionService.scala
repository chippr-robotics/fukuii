package com.chipprbots.ethereum.jsonrpc.subscription

import org.apache.pekko.actor.Actor
import org.apache.pekko.actor.ActorRef
import org.apache.pekko.actor.Props

import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.jsonrpc.subscription.SubscriptionEvents.BlockImported
import com.chipprbots.ethereum.jsonrpc.subscription.SubscriptionEvents.ChainReorg
import com.chipprbots.ethereum.jsonrpc.subscription.SubscriptionManager.NotifySubscribers
import com.chipprbots.ethereum.utils.Logger

/** Listens for block import events and pushes newHeads notifications.
  *
  * Emulates Besu's NewBlockHeadersSubscriptionService — stateless observer
  * registered once at startup, iterates matching subscriptions on each event.
  */
class NewBlockHeadersSubscriptionService(
    subscriptionManager: ActorRef,
    blockchainReader: BlockchainReader
) extends Actor
    with Logger {

  override def preStart(): Unit = {
    context.system.eventStream.subscribe(self, classOf[BlockImported])
    context.system.eventStream.subscribe(self, classOf[ChainReorg])
  }

  override def postStop(): Unit = {
    context.system.eventStream.unsubscribe(self)
  }

  override def receive: Receive = {
    case BlockImported(blockNumber) =>
      blockchainReader.getBlockHeaderByNumber(blockNumber).foreach { header =>
        val notification = SubscriptionJsonSerializers.serializeBlockHeader(header)
        subscriptionManager ! NotifySubscribers(SubscriptionType.NewHeads, notification)
      }

    case ChainReorg(_, addedBlocks) =>
      addedBlocks.foreach { block =>
        val notification = SubscriptionJsonSerializers.serializeBlockHeader(block.header)
        subscriptionManager ! NotifySubscribers(SubscriptionType.NewHeads, notification)
      }
  }
}

object NewBlockHeadersSubscriptionService {
  def props(subscriptionManager: ActorRef, blockchainReader: BlockchainReader): Props =
    Props(new NewBlockHeadersSubscriptionService(subscriptionManager, blockchainReader))
}
