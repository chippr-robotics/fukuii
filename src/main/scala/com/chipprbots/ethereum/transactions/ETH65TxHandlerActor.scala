package com.chipprbots.ethereum.transactions

import org.apache.pekko.actor.Actor
import org.apache.pekko.actor.ActorLogging
import org.apache.pekko.actor.ActorRef
import org.apache.pekko.actor.Props
import org.apache.pekko.util.ByteString

import java.util.concurrent.atomic.AtomicLong

import com.chipprbots.ethereum.domain.SignedTransactionWithSender
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerEvent.MessageFromPeer
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerSelector
import com.chipprbots.ethereum.network.PeerEventBusActor.Subscribe
import com.chipprbots.ethereum.network.PeerEventBusActor.SubscriptionClassifier.MessageClassifier
import com.chipprbots.ethereum.network.p2p.messages.Codes
import com.chipprbots.ethereum.network.p2p.messages.ETH66
import com.chipprbots.ethereum.network.p2p.messages.ETH66.GetPooledTransactions._
import com.chipprbots.ethereum.network.p2p.messages.ETH67
import com.chipprbots.ethereum.transactions.SignedTransactionsFilterActor.ProperSignedTransactions
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.Config

/** Handles ETH68 transaction gossip (NewPooledTransactionHashes / PooledTransactions):
  *   - Inbound NewPooledTransactionHashes (ETH67/68 format with types+sizes): fetches unknown
  *     transactions from the announcing peer via GetPooledTransactions (ETH66 requestId format)
  *   - Inbound PooledTransactions (ETH66/68 requestId format): validates sender and forwards
  *     to the pending tx pool
  *
  * Subscribes directly to the peer event bus (same pattern as SignedTransactionsFilterActor).
  * An LRU set of 32K recently-seen hashes avoids re-fetching (mirrors geth's maxKnownTxs = 32768).
  */
class ETH65TxHandlerActor(
    networkPeerManager: ActorRef,
    pendingTransactionsManager: ActorRef,
    peerEventBus: ActorRef
) extends Actor
    with ActorLogging {

  implicit val blockchainConfig: BlockchainConfig = Config.blockchains.blockchainConfig

  // LRU set of recently-seen tx hashes — evict eldest when over 32K entries.
  private val MaxKnownHashes = 32768
  private val knownHashes: java.util.LinkedHashMap[ByteString, Unit] =
    new java.util.LinkedHashMap[ByteString, Unit](MaxKnownHashes, 0.75f, true) {
      override def removeEldestEntry(eldest: java.util.Map.Entry[ByteString, Unit]): Boolean =
        size() > MaxKnownHashes
    }

  // Monotonically increasing request ID for ETH66-wrapped GetPooledTransactions.
  private val requestIdCounter = new AtomicLong(1L)

  peerEventBus ! Subscribe(
    MessageClassifier(
      Set(Codes.NewPooledTransactionHashesCode, Codes.PooledTransactionsCode),
      PeerSelector.AllPeers
    )
  )

  override def receive: Receive = {

    // ETH68 hash-only announcement (ETH67/68 format: types, sizes, hashes)
    // Reply with ETH66-wrapped GetPooledTransactions (requestId required for ETH68)
    case MessageFromPeer(msg: ETH67.NewPooledTransactionHashes, peerId) =>
      val unknownHashes = msg.hashes.filterNot(h => knownHashes.containsKey(h))
      if (unknownHashes.nonEmpty) {
        log.debug(
          "ETH68 NewPooledTransactionHashes from {}: requesting {}/{} unknown hashes",
          peerId,
          unknownHashes.size,
          msg.hashes.size
        )
        unknownHashes.foreach(h => knownHashes.put(h, ()))
        val requestId = requestIdCounter.getAndIncrement()
        networkPeerManager ! NetworkPeerManagerActor.SendMessage(
          ETH66.GetPooledTransactions(requestId, unknownHashes),
          peerId
        )
      }

    // ETH68 PooledTransactions response (ETH66/68 requestId format)
    case MessageFromPeer(msg: ETH66.PooledTransactions, peerId) =>
      if (msg.txs.nonEmpty) {
        log.debug(
          "ETH68 PooledTransactions from {}: {} txs (requestId={})",
          peerId,
          msg.txs.size,
          msg.requestId
        )
        val valid = SignedTransactionWithSender.getSignedTransactions(msg.txs)
        if (valid.nonEmpty)
          pendingTransactionsManager ! ProperSignedTransactions(valid.toSet, peerId)
      }
  }
}

object ETH65TxHandlerActor {
  def props(
      networkPeerManager: ActorRef,
      pendingTransactionsManager: ActorRef,
      peerEventBus: ActorRef
  ): Props =
    Props(new ETH65TxHandlerActor(networkPeerManager, pendingTransactionsManager, peerEventBus))
}
