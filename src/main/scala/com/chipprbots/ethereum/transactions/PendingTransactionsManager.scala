package com.chipprbots.ethereum.transactions

import org.apache.pekko.actor.Actor
import org.apache.pekko.actor.ActorLogging
import org.apache.pekko.actor.ActorRef
import org.apache.pekko.actor.Props
import org.apache.pekko.util.ByteString
import org.apache.pekko.util.Timeout

import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

import com.google.common.cache.Cache
import com.google.common.cache.CacheBuilder
import com.google.common.cache.RemovalNotification

import com.chipprbots.ethereum.domain.Address
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
import com.chipprbots.ethereum.jsonrpc.NewPendingTransaction
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
      peerMessageBus: ActorRef,
      blockchainReader: com.chipprbots.ethereum.domain.BlockchainReader = null,
      stateStorage: com.chipprbots.ethereum.db.storage.StateStorage = null
  ): Props =
    Props(
      new PendingTransactionsManager(
        txPoolConfig,
        peerManager,
        networkPeerManager,
        peerMessageBus,
        blockchainReader,
        stateStorage
      )
    )

  case class AddTransactions(signedTransactions: Set[SignedTransactionWithSender])

  case class AddUncheckedTransactions(signedTransactions: Seq[SignedTransaction])

  object AddTransactions {
    def apply(txs: SignedTransactionWithSender*): AddTransactions = AddTransactions(txs.toSet)
  }

  case class AddOrOverrideTransaction(signedTransaction: SignedTransaction, blobTxRawBytes: Option[ByteString] = None)

  private case class NotifyPeers(signedTransactions: Seq[SignedTransactionWithSender], peers: Seq[Peer])

  case object GetPendingTransactions
  case class PendingTransactionsResponse(
      pendingTransactions: Seq[PendingTransaction],
      blobTxNetworkBytes: Map[ByteString, ByteString] = Map.empty
  )

  case class RemoveTransactions(signedTransactions: Seq[SignedTransaction])

  case class PendingTransaction(
      stx: SignedTransactionWithSender,
      addTimestamp: Long,
      receivedFromLocalSource: Boolean = false
  )

  case object ClearPendingTransactions
}

class PendingTransactionsManager(
    txPoolConfig: TxPoolConfig,
    peerManager: ActorRef,
    networkPeerManager: ActorRef,
    peerEventBus: ActorRef,
    blockchainReader: com.chipprbots.ethereum.domain.BlockchainReader,
    stateStorage: com.chipprbots.ethereum.db.storage.StateStorage
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

  /** Raw network-wrapped bytes for EIP-4844 blob txs (txHash → 0x03||rlp([payload,blobs,commitments,proofs])). Needed
    * to replay the sidecar in PooledTransactions responses (EIP-4844 requirement).
    */
  var blobTxNetworkBytes: Map[ByteString, ByteString] = Map.empty

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
          blobTxNetworkBytes -= notification.getKey
        }
    })
    .build()

  implicit val timeout: Timeout = Timeout(3.seconds)

  /** Locally-cached set of connected peers, updated reactively via PeerHandshakeSuccessful/PeerDisconnected. Eliminates
    * the async ask to PeerManagerActor which added seconds of latency to tx propagation.
    */
  var connectedPeers: Map[PeerId, Peer] = Map.empty

  /** High-water mark of the next expected nonce per sender address. Once nonce N is accepted, pendingNonces(sender) =
    * max(current, N+1). Never decremented on removal — only cleared on ClearPendingTransactions. Applied before MPT
    * state validation so it works even when state trie is unavailable.
    */
  var pendingNonces: Map[Address, BigInt] = Map.empty

  /** Tracks announced tx metadata (type, size) from NewPooledTransactionHashes. Used to validate PooledTransactions
    * responses — disconnect peers that send txs with type/size mismatched from their announcement (EIP-4844 blob
    * violations).
    */
  var pendingAnnouncements: Map[ByteString, (Byte, BigInt, PeerId)] = Map.empty

  peerEventBus ! Subscribe(SubscriptionClassifier.PeerHandshaked)
  peerEventBus ! Subscribe(
    SubscriptionClassifier.PeerDisconnectedClassifier(
      com.chipprbots.ethereum.network.PeerEventBusActor.PeerSelector.AllPeers
    )
  )
  // Subscribe to NewPooledTransactionHashes and PooledTransactions for tx pool protocol
  peerEventBus ! Subscribe(
    SubscriptionClassifier.MessageClassifier(
      Set(Codes.NewPooledTransactionHashesCode, Codes.PooledTransactionsCode),
      com.chipprbots.ethereum.network.PeerEventBusActor.PeerSelector.AllPeers
    )
  )

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
      val stxs = pendingTransactions.asMap().values().asScala.map(_.stx).toSet
      val newTxs = signedTransactions.diff(stxs)
      log.debug("Adding {} txs ({} new, {} in pool)", signedTransactions.size, newTxs.size, stxs.size)
      // Validate against chain state (nonce, balance) before adding to pool
      val transactionsToAdd = validateAgainstState(newTxs)
      if (transactionsToAdd.nonEmpty) {
        val timestamp = System.currentTimeMillis()
        transactionsToAdd.foreach(t => pendingTransactions.put(t.tx.hash, PendingTransaction(t, timestamp)))
        updatePendingNonces(transactionsToAdd)
        transactionsToAdd.foreach(t => context.system.eventStream.publish(NewPendingTransaction(t)))
        val peers = connectedPeers.values.toSeq
        if (peers.nonEmpty) {
          self ! NotifyPeers(transactionsToAdd.toSeq, peers)
        }
        // Announce validated tx hashes to ALL connected peers via NewPooledTransactionHashes
        announceNewTxHashes(transactionsToAdd)
      }

    case AddOrOverrideTransaction(newStx, blobRawBytesOpt) =>
      pendingTransactions.cleanUp()
      log.debug("Overriding transaction: {}", newStx.hash.toHex)
      blobRawBytesOpt.foreach(raw => blobTxNetworkBytes += (newStx.hash -> raw))
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
      pendingTransactions.put(newStx.hash, PendingTransaction(newPendingTx, timestamp, receivedFromLocalSource = true))
      updatePendingNonces(Seq(newPendingTx))
      context.system.eventStream.publish(NewPendingTransaction(newPendingTx))

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
        val txsToNotify = stillPending.filterNot(stx => isTxKnown(stx.tx, peer.id))
        if (txsToNotify.nonEmpty) {
          // Use NewPooledTransactionHashes (ETH/68 spec) instead of full SignedTransactions.
          // Peers can request full txs via GetPooledTransactions if interested.
          import com.chipprbots.ethereum.domain._
          val hashes = txsToNotify.map(_.tx.hash)
          val types = txsToNotify.map { stx =>
            stx.tx.tx match {
              case _: LegacyTransaction         => 0.toByte
              case _: TransactionWithAccessList => Transaction.Type01
              case _: TransactionWithDynamicFee => Transaction.Type02
              case _: BlobTransaction           => Transaction.Type03
              case _: SetCodeTransaction        => Transaction.Type04
            }
          }
          val sizes = txsToNotify.map(stx => BigInt(SignedTransaction.byteArraySerializable.toBytes(stx.tx).length))
          val announcement = ETH67.NewPooledTransactionHashes(types, sizes, hashes)
          networkPeerManager ! NetworkPeerManagerActor.SendMessage(announcement, peer.id)
          txsToNotify.foreach(stx => setTxKnown(stx.tx, peer.id))
        }
      }

    // ETH67+ NewPooledTransactionHashes — request unknown tx hashes via GetPooledTransactions
    case com.chipprbots.ethereum.network.PeerEventBusActor.PeerEvent
          .MessageFromPeer(msg: ETH67.NewPooledTransactionHashes, peerId) =>
      val unknownHashes = msg.hashes.filterNot(h => pendingTransactions.asMap().containsKey(h))
      if (unknownHashes.nonEmpty) {
        // Track announced types/sizes for validation when PooledTransactions arrives
        msg.hashes.zip(msg.types).zip(msg.sizes).foreach { case ((hash, txType), size) =>
          pendingAnnouncements = pendingAnnouncements.updated(hash, (txType, size, peerId))
        }
        val requestId = ETH66.nextRequestId
        networkPeerManager ! NetworkPeerManagerActor.SendMessage(
          ETH66.GetPooledTransactions(requestId, unknownHashes),
          peerId
        )
      }

    // ETH65 NewPooledTransactionHashes (legacy format — list of hashes only)
    case com.chipprbots.ethereum.network.PeerEventBusActor.PeerEvent.MessageFromPeer(
          msg: com.chipprbots.ethereum.network.p2p.messages.ETH65.NewPooledTransactionHashes,
          peerId
        ) =>
      val unknownHashes = msg.txHashes.filterNot(h => pendingTransactions.asMap().containsKey(h))
      if (unknownHashes.nonEmpty) {
        log.debug("Requesting {} unknown pooled transactions from peer {}", unknownHashes.size, peerId)
        val requestId = ETH66.nextRequestId
        networkPeerManager ! NetworkPeerManagerActor.SendMessage(
          ETH66.GetPooledTransactions(requestId, unknownHashes),
          peerId
        )
      }

    // ETH66+ PooledTransactions response — add received txs to pool
    case com.chipprbots.ethereum.network.PeerEventBusActor.PeerEvent
          .MessageFromPeer(msg: ETH66.PooledTransactions, peerId) =>
      // Validate received txs against their announcements (type/size mismatch = blob violation)
      import com.chipprbots.ethereum.domain._
      val announcementViolation = msg.txs.zipWithIndex.exists { case (stx, idx) =>
        pendingAnnouncements.get(stx.hash).exists { case (announcedType, announcedSize, _) =>
          val actualType: Byte = stx.tx match {
            case _: LegacyTransaction         => 0.toByte
            case _: TransactionWithAccessList => Transaction.Type01
            case _: TransactionWithDynamicFee => Transaction.Type02
            case _: BlobTransaction           => Transaction.Type03
            case _: SetCodeTransaction        => Transaction.Type04
          }
          val typeMismatch = actualType != announcedType
          // Use original wire size (from PooledTransactions decode) for accurate comparison
          val sizeMismatch = if (idx < msg.originalSizes.size) {
            BigInt(msg.originalSizes(idx)) != announcedSize
          } else false
          typeMismatch || sizeMismatch
        }
      }
      // Clean up announcements for received txs
      msg.txs.foreach(stx => pendingAnnouncements -= stx.hash)
      if (announcementViolation) {
        log.debug("PooledTransactions from peer {} has type/size mismatch with announcement — disconnecting", peerId)
        peerManager ! PeerManagerActor.DisconnectPeerById(peerId)
      } else {
        // Store blob tx sidecar bytes for PooledTransactions responses
        msg.blobTxRawBytes.foreach { case (hash, rawBytes) =>
          blobTxNetworkBytes += (hash -> rawBytes)
        }
        val validTxs = SignedTransactionWithSender.getSignedTransactions(msg.txs)
        if (validTxs.nonEmpty) {
          self ! AddTransactions(validTxs.toSet)
          validTxs.foreach(stx => setTxKnown(stx.tx, peerId))
        }
      }

    case GetPendingTransactions =>
      pendingTransactions.cleanUp()
      sender() ! PendingTransactionsResponse(pendingTransactions.asMap().asScala.values.toSeq, blobTxNetworkBytes)

    case RemoveTransactions(signedTransactions) =>
      pendingTransactions.invalidateAll(signedTransactions.map(_.hash).asJava)
      log.debug("Removing transactions: {}", signedTransactions.map(_.hash.toHex))
      knownTransactions = knownTransactions -- signedTransactions.map(_.hash)
      blobTxNetworkBytes = blobTxNetworkBytes -- signedTransactions.map(_.hash)

    case ProperSignedTransactions(transactions, peerId) =>
      self ! AddTransactions(transactions)
      transactions.foreach(stx => setTxKnown(stx.tx, peerId))

    case ClearPendingTransactions =>
      log.debug("Dropping all cached transactions")
      pendingTransactions.invalidateAll()
      pendingNonces = Map.empty
      blobTxNetworkBytes = Map.empty
  }

  /** Announce validated transaction hashes to all connected peers via NewPooledTransactionHashes (ETH/68 spec). */
  private def announceNewTxHashes(txs: Set[SignedTransactionWithSender]): Unit = {
    if (txs.isEmpty) return
    import com.chipprbots.ethereum.domain._
    val txSeq = txs.toSeq
    val hashes = txSeq.map(_.tx.hash)
    val types = txSeq.map { stx =>
      stx.tx.tx match {
        case _: LegacyTransaction         => 0.toByte
        case _: TransactionWithAccessList => Transaction.Type01
        case _: TransactionWithDynamicFee => Transaction.Type02
        case _: BlobTransaction           => Transaction.Type03
        case _: SetCodeTransaction        => Transaction.Type04
      }
    }
    val sizes = txSeq.map(stx => BigInt(SignedTransaction.byteArraySerializable.toBytes(stx.tx).length))
    val announcement = ETH67.NewPooledTransactionHashes(types, sizes, hashes)
    connectedPeers.values.foreach { peer =>
      networkPeerManager ! NetworkPeerManagerActor.SendMessage(announcement, peer.id)
    }
  }

  /** Update pendingNonces high-water mark for accepted transactions. */
  private def updatePendingNonces(txs: Iterable[SignedTransactionWithSender]): Unit =
    txs.foreach { stx =>
      val nextNonce = stx.tx.tx.nonce + 1
      val current = pendingNonces.getOrElse(stx.senderAddress, BigInt(0))
      if (nextNonce > current) pendingNonces = pendingNonces.updated(stx.senderAddress, nextNonce)
    }

  /** Validate transactions against the current chain state. Rejects: stale nonces, insufficient balance for value +
    * gas. Returns only transactions that pass state validation.
    */
  private def validateAgainstState(txs: Set[SignedTransactionWithSender]): Set[SignedTransactionWithSender] = {
    // 1. Always apply pending nonce check first (no MPT state needed, immune to race conditions)
    val afterPendingNonceCheck = txs.filter { stx =>
      pendingNonces.get(stx.senderAddress) match {
        case Some(nextExpected) => stx.tx.tx.nonce >= nextExpected
        case None               => true
      }
    }

    if (blockchainReader == null || stateStorage == null) return afterPendingNonceCheck
    try {
      import com.chipprbots.ethereum.domain.Account
      import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
      import MerklePatriciaTrie.defaultByteArraySerializable

      val bestBlockOpt = blockchainReader.getBestBlock()
      bestBlockOpt match {
        case Some(bestBlock) =>
          val mptStorage = stateStorage.getReadOnlyStorage
          val stateTrie = MerklePatriciaTrie[Array[Byte], Account](bestBlock.header.stateRoot.toArray, mptStorage)(
            defaultByteArraySerializable,
            Account.accountSerializer
          )

          afterPendingNonceCheck.filter { stx =>
            val addressHash = com.chipprbots.ethereum.crypto.kec256(stx.senderAddress.toArray)
            val accountOpt = stateTrie.get(addressHash)
            accountOpt.exists { account =>
              val tx = stx.tx.tx
              val nonceValid = tx.nonce >= account.nonce.toBigInt && tx.nonce < account.nonce.toBigInt + 1024
              val maxGasCost = tx.gasLimit * tx.gasPrice
              val totalCost = tx.value + maxGasCost
              val balanceValid = account.balance.toBigInt >= totalCost
              nonceValid && balanceValid
            }
          }
        case None =>
          // No best block — only accept txs from senders we've already seen
          afterPendingNonceCheck.filter(stx => pendingNonces.contains(stx.senderAddress))
      }
    } catch {
      case _: Exception =>
        // MPT failed — only accept txs from senders with established pending nonces
        // (unknown senders can't be validated without state)
        afterPendingNonceCheck.filter(stx => pendingNonces.contains(stx.senderAddress))
    }
  }

  private def isTxKnown(signedTransaction: SignedTransaction, peerId: PeerId): Boolean =
    knownTransactions.getOrElse(signedTransaction.hash, Set.empty).contains(peerId)

  private def setTxKnown(signedTransaction: SignedTransaction, peerId: PeerId): Unit = {
    val currentPeers = knownTransactions.getOrElse(signedTransaction.hash, Set.empty)
    val newPeers = currentPeers + peerId
    knownTransactions += (signedTransaction.hash -> newPeers)
  }
}
