package com.chipprbots.ethereum.transactions

import org.apache.pekko.actor.Actor
import org.apache.pekko.actor.ActorLogging
import org.apache.pekko.actor.ActorRef
import org.apache.pekko.actor.Props
import org.apache.pekko.util.ByteString
import org.apache.pekko.util.Timeout

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

import com.google.common.cache.Cache
import com.google.common.cache.CacheBuilder
import com.google.common.cache.RemovalNotification

import com.chipprbots.ethereum.domain.SignedTransaction
import com.chipprbots.ethereum.domain.SignedTransactionWithSender
import com.chipprbots.ethereum.metrics.MetricsContainer
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerEvent
import com.chipprbots.ethereum.network.PeerEventBusActor.Subscribe
import com.chipprbots.ethereum.network.PeerEventBusActor.SubscriptionClassifier
import com.chipprbots.ethereum.network.PeerId
import com.chipprbots.ethereum.network.PeerManagerActor
import com.chipprbots.ethereum.network.p2p.messages.BaseETH6XMessages.SignedTransactions
import com.chipprbots.ethereum.network.p2p.messages.Codes
import com.chipprbots.ethereum.network.p2p.messages.ETH66
import com.chipprbots.ethereum.network.p2p.messages.ETH66.GetPooledTransactions._
import com.chipprbots.ethereum.network.p2p.messages.ETH67
import com.chipprbots.ethereum.network.p2p.messages.ETH67.NewPooledTransactionHashes._
import com.chipprbots.ethereum.transactions.SignedTransactionsFilterActor.ProperSignedTransactions
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.ByteStringUtils.ByteStringOps
import com.chipprbots.ethereum.utils.Config
import com.chipprbots.ethereum.utils.TxPoolConfig

object PendingTransactionsManager {
  def props(
      txPoolConfig: TxPoolConfig,
      peerManager: ActorRef,
      networkPeerManager: ActorRef,
      peerMessageBus: ActorRef
  ): Props =
    Props(new PendingTransactionsManager(txPoolConfig, peerManager, networkPeerManager, peerMessageBus))

  case class AddTransactions(signedTransactions: Set[SignedTransactionWithSender])

  case class AddUncheckedTransactions(signedTransactions: Seq[SignedTransaction])

  object AddTransactions {
    def apply(txs: SignedTransactionWithSender*): AddTransactions = AddTransactions(txs.toSet)
  }

  case class AddOrOverrideTransaction(signedTransaction: SignedTransaction)

  private case class NotifyPeers(signedTransactions: Seq[SignedTransactionWithSender], peers: Seq[Peer])

  case object GetPendingTransactions
  case class PendingTransactionsResponse(pendingTransactions: Seq[PendingTransaction])

  case class RemoveTransactions(signedTransactions: Seq[SignedTransaction])

  case class PendingTransaction(stx: SignedTransactionWithSender, addTimestamp: Long)

  case object ClearPendingTransactions
}

class PendingTransactionsManager(
    txPoolConfig: TxPoolConfig,
    peerManager: ActorRef,
    networkPeerManager: ActorRef,
    peerEventBus: ActorRef
) extends Actor
    with MetricsContainer
    with ActorLogging {

  import PendingTransactionsManager._

  metrics.gauge(
    "transactions.pool.size.gauge",
    () => pendingTransactions.size().toDouble
  )

  /** stores information which tx hashes are "known" by which peers
    */
  var knownTransactions: Map[ByteString, Set[PeerId]] = Map.empty

  /** stores all pending transactions
    */
  val pendingTransactions: Cache[ByteString, PendingTransaction] = CacheBuilder
    .newBuilder()
    .expireAfterWrite(txPoolConfig.transactionTimeout._1, txPoolConfig.transactionTimeout._2)
    .maximumSize(txPoolConfig.txPoolSize)
    .removalListener(new com.google.common.cache.RemovalListener[ByteString, PendingTransaction] {
      def onRemoval(notification: RemovalNotification[ByteString, PendingTransaction]): Unit =
        if (notification.wasEvicted()) {
          log.debug("Evicting transaction: {} due to {}", notification.getKey.toHex, notification.getCause)
          knownTransactions = knownTransactions.filterNot(_._1 == notification.getKey)
        }
    })
    .build()

  implicit val timeout: Timeout = Timeout(3.seconds)

  /** Locally-cached set of connected peers, updated reactively via PeerHandshakeSuccessful/PeerDisconnected.
    * Eliminates the async ask to PeerManagerActor which added seconds of latency to tx propagation.
    */
  var connectedPeers: Map[PeerId, Peer] = Map.empty

  peerEventBus ! Subscribe(SubscriptionClassifier.PeerHandshaked)
  peerEventBus ! Subscribe(SubscriptionClassifier.PeerDisconnectedClassifier(
    com.chipprbots.ethereum.network.PeerEventBusActor.PeerSelector.AllPeers))
  // Subscribe to NewPooledTransactionHashes and PooledTransactions for tx pool protocol
  peerEventBus ! Subscribe(SubscriptionClassifier.MessageClassifier(
    Set(Codes.NewPooledTransactionHashesCode, Codes.PooledTransactionsCode),
    com.chipprbots.ethereum.network.PeerEventBusActor.PeerSelector.AllPeers
  ))

  val transactionFilter: ActorRef = context.actorOf(SignedTransactionsFilterActor.props(context.self, peerEventBus))

  implicit val blockchainConfig: BlockchainConfig = Config.blockchains.blockchainConfig

  // scalastyle:off method.length
  override def receive: Receive = {
    case PeerEvent.PeerHandshakeSuccessful(peer, _) =>
      connectedPeers += (peer.id -> peer)
      pendingTransactions.cleanUp()
      val stxs = pendingTransactions.asMap().values().asScala.toSeq.map(_.stx)
      self ! NotifyPeers(stxs, Seq(peer))

    case PeerEvent.PeerDisconnected(peerId) =>
      connectedPeers -= peerId

    case AddUncheckedTransactions(transactions) =>
      val validTxs = SignedTransactionWithSender.getSignedTransactions(transactions)
      self ! AddTransactions(validTxs.toSet)

    case AddTransactions(signedTransactions) =>
      pendingTransactions.cleanUp()
      log.debug("Adding transactions: {}", signedTransactions.map(_.tx.hash.toHex))
      val stxs = pendingTransactions.asMap().values().asScala.map(_.stx).toSet
      val transactionsToAdd = signedTransactions.diff(stxs)
      if (transactionsToAdd.nonEmpty) {
        val timestamp = System.currentTimeMillis()
        transactionsToAdd.foreach(t => pendingTransactions.put(t.tx.hash, PendingTransaction(t, timestamp)))
        val peers = connectedPeers.values.toSeq
        if (peers.nonEmpty) {
          self ! NotifyPeers(transactionsToAdd.toSeq, peers)
        }
      }

    case AddOrOverrideTransaction(newStx) =>
      pendingTransactions.cleanUp()
      log.debug("Overriding transaction: {}", newStx.hash.toHex)
      // Only validated transactions are added this way, it is safe to call get
      val newStxSender = SignedTransaction
        .getSender(newStx)
        .getOrElse(
          throw new IllegalStateException("Unable to get sender from validated transaction")
        )
      val obsoleteTxs = pendingTransactions
        .asMap()
        .asScala
        .filter(ptx => ptx._2.stx.senderAddress == newStxSender && ptx._2.stx.tx.tx.nonce == newStx.tx.nonce)
      pendingTransactions.invalidateAll(obsoleteTxs.keys.asJava)

      val timestamp = System.currentTimeMillis()
      val newPendingTx = SignedTransactionWithSender(newStx, newStxSender)
      pendingTransactions.put(newStx.hash, PendingTransaction(newPendingTx, timestamp))

      val peers = connectedPeers.values.toSeq
      if (peers.nonEmpty) {
        self ! NotifyPeers(Seq(newPendingTx), peers)
      }

    case NotifyPeers(signedTransactions, peers) =>
      pendingTransactions.cleanUp()
      log.debug(
        "Notifying peers {} about transactions {}",
        peers.map(_.nodeId.map(_.toHex)),
        signedTransactions.map(_.tx.hash.toHex)
      )
      val pendingTxMap = pendingTransactions.asMap()
      val stillPending = signedTransactions
        .filter(stx => pendingTxMap.containsKey(stx.tx.hash)) // signed transactions that are still pending

      peers.foreach { peer =>
        val txsToNotify = stillPending.filterNot(stx => isTxKnown(stx.tx, peer.id)) // and not known by peer
        if (txsToNotify.nonEmpty) {
          networkPeerManager ! NetworkPeerManagerActor.SendMessage(SignedTransactions(txsToNotify.map(_.tx)), peer.id)
          txsToNotify.foreach(stx => setTxKnown(stx.tx, peer.id))
        }
      }

    // ETH67+ NewPooledTransactionHashes — request unknown tx hashes via GetPooledTransactions
    case com.chipprbots.ethereum.network.PeerEventBusActor.PeerEvent.MessageFromPeer(
      msg: ETH67.NewPooledTransactionHashes, peerId) =>
      val unknownHashes = msg.hashes.filterNot(h => pendingTransactions.asMap().containsKey(h))
      if (unknownHashes.nonEmpty) {
        log.debug("Requesting {} unknown pooled transactions from peer {}", unknownHashes.size, peerId)
        val requestId = ETH66.nextRequestId
        networkPeerManager ! NetworkPeerManagerActor.SendMessage(
          ETH66.GetPooledTransactions(requestId, unknownHashes), peerId)
      }

    // ETH65 NewPooledTransactionHashes (legacy format — list of hashes only)
    case com.chipprbots.ethereum.network.PeerEventBusActor.PeerEvent.MessageFromPeer(
      msg: com.chipprbots.ethereum.network.p2p.messages.ETH65.NewPooledTransactionHashes, peerId) =>
      val unknownHashes = msg.txHashes.filterNot(h => pendingTransactions.asMap().containsKey(h))
      if (unknownHashes.nonEmpty) {
        log.debug("Requesting {} unknown pooled transactions from peer {}", unknownHashes.size, peerId)
        val requestId = ETH66.nextRequestId
        networkPeerManager ! NetworkPeerManagerActor.SendMessage(
          ETH66.GetPooledTransactions(requestId, unknownHashes), peerId)
      }

    // ETH66+ PooledTransactions response — add received txs to pool
    case com.chipprbots.ethereum.network.PeerEventBusActor.PeerEvent.MessageFromPeer(
      msg: ETH66.PooledTransactions, peerId) =>
      val validTxs = SignedTransactionWithSender.getSignedTransactions(msg.txs)
      if (validTxs.nonEmpty) {
        self ! AddTransactions(validTxs.toSet)
        validTxs.foreach(stx => setTxKnown(stx.tx, peerId))
      }

    case GetPendingTransactions =>
      pendingTransactions.cleanUp()
      sender() ! PendingTransactionsResponse(pendingTransactions.asMap().asScala.values.toSeq)

    case RemoveTransactions(signedTransactions) =>
      pendingTransactions.invalidateAll(signedTransactions.map(_.hash).asJava)
      log.debug("Removing transactions: {}", signedTransactions.map(_.hash.toHex))
      knownTransactions = knownTransactions -- signedTransactions.map(_.hash)

    case ProperSignedTransactions(transactions, peerId) =>
      self ! AddTransactions(transactions)
      transactions.foreach(stx => setTxKnown(stx.tx, peerId))
      // Announce new tx hashes to ALL peers (including sender) via NewPooledTransactionHashes.
      // Per ETH/68 spec, nodes announce hashes even to the peer that sent the full tx.
      // IMPORTANT: Always send directly to the sender peer by peerId, because
      // PeerHandshakeSuccessful may not have been processed yet (race condition).
      if (transactions.nonEmpty) {
        import com.chipprbots.ethereum.domain._
        val txSeq = transactions.toSeq
        val hashes = txSeq.map(_.tx.hash)
        val types = txSeq.map { stx => stx.tx.tx match {
          case _: LegacyTransaction         => 0.toByte
          case _: TransactionWithAccessList  => Transaction.Type01
          case _: TransactionWithDynamicFee  => Transaction.Type02
          case _: BlobTransaction            => Transaction.Type03
          case _: SetCodeTransaction         => Transaction.Type04
        }}
        val sizes = txSeq.map(stx => BigInt(SignedTransaction.byteArraySerializable.toBytes(stx.tx).length))
        val announcement = ETH67.NewPooledTransactionHashes(types, sizes, hashes)
        // Send to sender peer directly (always, even if not yet in connectedPeers)
        networkPeerManager ! NetworkPeerManagerActor.SendMessage(announcement, peerId)
        // Also send to all other connected peers
        connectedPeers.values.foreach { peer =>
          if (peer.id != peerId) {
            networkPeerManager ! NetworkPeerManagerActor.SendMessage(announcement, peer.id)
          }
        }
      }

    case ClearPendingTransactions =>
      log.debug("Dropping all cached transactions")
      pendingTransactions.invalidateAll()
  }

  private def isTxKnown(signedTransaction: SignedTransaction, peerId: PeerId): Boolean =
    knownTransactions.getOrElse(signedTransaction.hash, Set.empty).contains(peerId)

  private def setTxKnown(signedTransaction: SignedTransaction, peerId: PeerId): Unit = {
    val currentPeers = knownTransactions.getOrElse(signedTransaction.hash, Set.empty)
    val newPeers = currentPeers + peerId
    knownTransactions += (signedTransaction.hash -> newPeers)
  }
}
