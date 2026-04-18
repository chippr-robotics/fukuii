package com.chipprbots.ethereum.transactions

import org.apache.pekko.actor.Actor
import org.apache.pekko.actor.ActorLogging
import org.apache.pekko.actor.ActorRef
import org.apache.pekko.actor.Props
import org.apache.pekko.dispatch.BoundedMessageQueueSemantics
import org.apache.pekko.dispatch.RequiresMessageQueue

import com.chipprbots.ethereum.domain.SignedTransactionWithSender
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerEvent.MessageFromPeer
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerSelector
import com.chipprbots.ethereum.network.PeerEventBusActor.Subscribe
import com.chipprbots.ethereum.network.PeerEventBusActor.SubscriptionClassifier.MessageClassifier
import com.chipprbots.ethereum.network.PeerId
import com.chipprbots.ethereum.network.p2p.messages.BaseETH6XMessages.SignedTransactions
import com.chipprbots.ethereum.network.p2p.messages.Codes
import com.chipprbots.ethereum.transactions.SignedTransactionsFilterActor.ProperSignedTransactions
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.Config

class SignedTransactionsFilterActor(pendingTransactionsManager: ActorRef, peerEventBus: ActorRef)
    extends Actor
    with ActorLogging
    with RequiresMessageQueue[BoundedMessageQueueSemantics] {

  implicit val blockchainConfig: BlockchainConfig = Config.blockchains.blockchainConfig

  peerEventBus ! Subscribe(MessageClassifier(Set(Codes.SignedTransactionsCode), PeerSelector.AllPeers))

  override def receive: Receive = { case MessageFromPeer(SignedTransactions(newTransactions), peerId) =>
    // Validate signatures asynchronously to avoid blocking the actor for large batches.
    // With 2000 txs, synchronous ECDSA recovery takes 24+ seconds, exceeding test timeouts.
    import scala.concurrent.Future
    val txs = newTransactions
    Future {
      SignedTransactionWithSender.getSignedTransactions(txs)
    }(context.dispatcher).foreach { correctTransactions =>
      pendingTransactionsManager ! ProperSignedTransactions(correctTransactions.toSet, peerId)
    }(context.dispatcher)
  }
}

object SignedTransactionsFilterActor {
  def props(pendingTransactionsManager: ActorRef, peerEventBus: ActorRef): Props =
    Props(new SignedTransactionsFilterActor(pendingTransactionsManager, peerEventBus))

  case class ProperSignedTransactions(signedTransactions: Set[SignedTransactionWithSender], peerId: PeerId)
}
