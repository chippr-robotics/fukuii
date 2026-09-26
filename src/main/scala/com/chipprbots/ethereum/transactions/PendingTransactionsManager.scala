package com.chipprbots.ethereum.transactions

import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.MailboxSelector
import org.apache.pekko.actor.typed.pubsub.Topic
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.util.ByteString

import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

import com.google.common.cache.Cache
import com.google.common.cache.CacheBuilder
import com.google.common.cache.RemovalNotification

import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.SignedTransaction
import com.chipprbots.ethereum.domain.SignedTransactionWithSender
import com.chipprbots.ethereum.jsonrpc.NewPendingTransaction
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.PeerInfo
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.PeerEventBusActor.Command as PeerEventBusCommand
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerEvent
import com.chipprbots.ethereum.network.PeerEventBusActor.SubscribeCmd
import com.chipprbots.ethereum.network.PeerEventBusActor.SubscriptionClassifier
import com.chipprbots.ethereum.network.PeerId
import com.chipprbots.ethereum.network.PeerManagerActor
import com.chipprbots.ethereum.network.p2p.MessageSerializable
import com.chipprbots.ethereum.network.p2p.messages.Capability
import com.chipprbots.ethereum.network.p2p.messages.Codes
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.GetPooledTransactions.*
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.NewPooledTransactionHashes.*
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.NewPooledTransactionHashes72.*
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.ByteStringUtils.ByteStringOps
import com.chipprbots.ethereum.utils.Config
import com.chipprbots.ethereum.utils.TxPoolConfig

object PendingTransactionsManager:

  /** How far a delivered tx's size may differ from its announced size before the peer counts as lying about it. */
  private[transactions] val AnnouncedSizeSlackBytes: Int = 8

  sealed trait Command

  case class AddTransactions(signedTransactions: Set[SignedTransactionWithSender]) extends Command
  object AddTransactions:
    def apply(txs: SignedTransactionWithSender*): AddTransactions = AddTransactions(txs.toSet)

  case class AddUncheckedTransactions(signedTransactions: Seq[SignedTransaction]) extends Command

  case class AnnounceTransactions(signedTransactions: Seq[SignedTransaction], peerId: PeerId) extends Command

  case class AddOrOverrideTransaction(
      signedTransaction: SignedTransaction,
      blobTxRawBytes: Option[ByteString] = None
  ) extends Command

  private case class NotifyPeers(signedTransactions: Seq[SignedTransactionWithSender], peers: Seq[Peer]) extends Command

  // Bridges Classic peerEventBus PeerEvent messages into the typed Command stream via a messageAdapter.
  // The Classic bus delivers raw PeerEvent values; the typed ActorAdapter would ClassCastException trying
  // to coerce them to Command, so we wrap them here (same pattern as SignedTransactionsFilterActor).
  // Public so tests can inject peer events directly without standing up a real peerEventBus.
  case class WrappedPeerEvent(event: PeerEvent) extends Command

  // Typed ask (used by EngineApiService, BlockchainHostActor — in-scope callers):
  case class GetPendingTransactionsReq(replyTo: ActorRef[PendingTransactionsResponse]) extends Command

  // Legacy Classic case object — NOT a Command — used only by the Classic bridge actor in NodeBuilder
  // which translates it to GetPendingTransactionsReq for out-of-scope callers.
  case object GetPendingTransactions

  case class PendingTransactionsResponse(
      pendingTransactions: Seq[PendingTransaction],
      blobTxNetworkBytes: Map[ByteString, ByteString] = Map.empty
  )

  case class RemoveTransactions(signedTransactions: Seq[SignedTransaction]) extends Command

  case class PendingTransaction(
      stx: SignedTransactionWithSender,
      addTimestamp: Long,
      receivedFromLocalSource: Boolean = false
  )

  case object ClearPendingTransactions extends Command

  // Sent to PTM by SignedTransactionsFilterActor once sender recovery completes
  case class ProperSignedTransactions(signedTransactions: Set[SignedTransactionWithSender], peerId: PeerId)
      extends Command

  def apply(
      txPoolConfig: TxPoolConfig,
      peerManager: ActorRef[PeerManagerActor.Command],
      networkPeerManager: ActorRef[NetworkPeerManagerActor.Command],
      peerEventBus: ActorRef[PeerEventBusCommand],
      pendingTxTopic: ActorRef[Topic.Command[NewPendingTransaction]],
      blockchainReader: com.chipprbots.ethereum.domain.BlockchainReader = null,
      stateStorage: com.chipprbots.ethereum.db.storage.StateStorage = null,
      chainConfig: BlockchainConfig = Config.blockchains.blockchainConfig
  ): Behavior[Command] = Behaviors.setup { context =>

    given blockchainConfig: BlockchainConfig = chainConfig

    // Spawn STFA as a child with a bounded mailbox (backpressure from network layer)
    context.spawn(
      SignedTransactionsFilterActor(context.self, peerEventBus),
      "stfa",
      MailboxSelector.bounded(50000)
    )

    // Message adapter: bridges Classic PeerEvent → typed WrappedPeerEvent Command.
    // We subscribe to the Classic peerEventBus using this adapter's Classic ref as the sender,
    // so the bus delivers PeerEvent values to the adapter, which wraps and routes them as Commands.
    val peerEventAdapter: ActorRef[PeerEvent] =
      context.messageAdapter[PeerEvent](WrappedPeerEvent.apply)

    // Subscribe to peer events via the peerEventBus
    peerEventBus ! SubscribeCmd(SubscriptionClassifier.PeerHandshaked, peerEventAdapter)
    peerEventBus ! SubscribeCmd(
      SubscriptionClassifier.PeerDisconnectedClassifier(
        com.chipprbots.ethereum.network.PeerEventBusActor.PeerSelector.AllPeers
      ),
      peerEventAdapter
    )
    // Subscribe to NewPooledTransactionHashes and PooledTransactions for tx pool protocol
    peerEventBus ! SubscribeCmd(
      SubscriptionClassifier.MessageClassifier(
        Set(Codes.NewPooledTransactionHashesCode, Codes.PooledTransactionsCode),
        com.chipprbots.ethereum.network.PeerEventBusActor.PeerSelector.AllPeers
      ),
      peerEventAdapter
    )

    /** stores information which tx hashes are "known" by which peers */
    var knownTransactions: Map[ByteString, Set[PeerId]] = Map.empty

    /** Raw network-wrapped bytes for EIP-4844 blob txs (txHash → 0x03||rlp([payload,blobs,commitments,proofs])). Needed
      * to replay the sidecar in PooledTransactions responses (EIP-4844 requirement).
      */
    var blobTxNetworkBytes: Map[ByteString, ByteString] = Map.empty

    /** stores all pending transactions */
    val pendingTransactions: Cache[ByteString, PendingTransaction] = CacheBuilder
      .newBuilder()
      .expireAfterWrite(txPoolConfig.transactionTimeout._1, txPoolConfig.transactionTimeout._2)
      .maximumSize(txPoolConfig.txPoolSize)
      .removalListener(
        new com.google.common.cache.RemovalListener[ByteString, PendingTransaction]:
          def onRemoval(notification: RemovalNotification[ByteString, PendingTransaction]): Unit =
            if notification.wasEvicted() then
              context.log.debug("Evicting transaction: {} due to {}", notification.getKey.toHex, notification.getCause)
              knownTransactions = knownTransactions.filterNot(_._1 == notification.getKey)
              blobTxNetworkBytes -= notification.getKey
      )
      .build()

    /** Locally-cached set of connected peers, updated reactively via PeerHandshakeSuccessful/PeerDisconnected.
      * Eliminates the async ask to PeerManagerActor which added seconds of latency to tx propagation.
      */
    var connectedPeers: Map[PeerId, Peer] = Map.empty

    /** Negotiated ETH capability per connected peer, populated alongside `connectedPeers`. Needed so
      * `notifyPeersOfTransactions` can pick the correct NewPooledTransactionHashes wire shape: ETH72 replaced the
      * 3-field ETH68+ announcement with a 4-field form (adds a custody Mask, EIP-8070) — same wire code, but a peer
      * that negotiated ETH72 will fail to RLP-decode the 3-field form into its 4-field struct. Falls back to ETH68 (the
      * 3-field shape) for any peer this map has no entry for — matches the shape every version below ETH72 expects.
      */
    var connectedPeerCapabilities: Map[PeerId, Capability] = Map.empty

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

    /** Announce transaction hashes to connected peers via NewPooledTransactionHashes. */
    def notifyPeersOfTransactions(txs: Seq[SignedTransaction], peers: Seq[Peer]): Unit =
      if txs.isEmpty || peers.isEmpty then return
      import com.chipprbots.ethereum.domain.*

      val txSeq = txs.groupBy(_.hash).values.map(_.head).toSeq
      peers.foreach { peer =>
        val txsToNotify = txSeq.filterNot(stx => isTxKnown(stx, peer.id))
        if txsToNotify.nonEmpty then
          val hashes = txsToNotify.map(_.hash.value)
          val types = txsToNotify.map { stx =>
            stx.tx match
              case _: LegacyTransaction         => 0.toByte
              case _: TransactionWithAccessList => Transaction.Type01
              case _: TransactionWithDynamicFee => Transaction.Type02
              case _: BlobTransaction           => Transaction.Type03
              case _: SetCodeTransaction        => Transaction.Type04
          }
          // A blob tx travels in its network form, 0x03 || rlp([tx, blobs, commitments, proofs]). That is
          // what GetPooledTransactions serves, so its length is the size a peer checks the reply against.
          val sizes = txsToNotify.map { stx =>
            blobTxNetworkBytes.get(stx.hash.value) match
              case Some(networkForm) => BigInt(networkForm.length)
              case None              => BigInt(SignedTransaction.byteArraySerializable.toBytes(stx).length)
          }
          // ETH72 peers require the 4-field announcement (adds a custody Mask, EIP-8070) — same wire
          // code as the 3-field ETH68+ form, but a strictly different RLP shape (see
          // ETHPackets.NewPooledTransactionHashes72's doc comment). fukuii has no PeerDAS cell storage,
          // so it always advertises zero custody rather than claim cells it can't serve.
          val announcement: MessageSerializable =
            if connectedPeerCapabilities.get(peer.id).contains(Capability.ETH72) then
              ETHPackets.NewPooledTransactionHashes72(
                types,
                sizes,
                hashes,
                ETHPackets.NewPooledTransactionHashes72.NoCustody
              ): MessageSerializable
            else ETHPackets.NewPooledTransactionHashes(types, sizes, hashes): MessageSerializable
          networkPeerManager ! NetworkPeerManagerActor.SendMessageCmd(announcement, peer.id)
          txsToNotify.foreach(stx => setTxKnown(stx, peer.id))
      }

    /** Handle an inbound NewPooledTransactionHashes announcement (ETH67+ 3-field, or ETH72's 4-field form): request
      * every hash we don't already have pending, and record each announcement's (type, size) for later validation
      * against the PooledTransactions reply. Shared by both wire shapes — ETH72's custody Mask is not consulted here
      * (fukuii has no PeerDAS cell storage to fetch selectively against; see NewPooledTransactionHashes72's doc
      * comment) — so fetching unknown hashes is identical either way.
      */
    def requestUnknownAnnouncedHashes(
        hashes: Seq[ByteString],
        types: Seq[Byte],
        sizes: Seq[BigInt],
        peerId: PeerId
    ): Unit =
      val unknownHashes = hashes.filterNot(h => pendingTransactions.asMap().containsKey(h))
      if unknownHashes.nonEmpty then
        hashes.zip(types).zip(sizes).foreach { case ((hash, txType), size) =>
          pendingAnnouncements = pendingAnnouncements.updated(hash, (txType, size, peerId))
        }
        val requestId = ETHPackets.nextRequestId
        networkPeerManager ! NetworkPeerManagerActor.SendMessageCmd(
          ETHPackets.GetPooledTransactions(requestId, unknownHashes),
          peerId
        )

    /** Update pendingNonces high-water mark for accepted transactions. */
    def updatePendingNonces(txs: Iterable[SignedTransactionWithSender]): Unit =
      txs.foreach { stx =>
        val nextNonce = stx.tx.tx.nonce + 1
        val current = pendingNonces.getOrElse(stx.senderAddress, BigInt(0))
        if nextNonce > current then pendingNonces = pendingNonces.updated(stx.senderAddress, nextNonce)
      }

    /** Validate transactions against the current chain state. Rejects: stale nonces, insufficient balance for value +
      * gas. Returns only transactions that pass state validation.
      */
    def validateAgainstState(txs: Set[SignedTransactionWithSender]): Set[SignedTransactionWithSender] =
      // 1. Always apply pending nonce check first (no MPT state needed, immune to race conditions)
      val afterPendingNonceCheck = txs.filter { stx =>
        pendingNonces.get(stx.senderAddress) match
          case Some(nextExpected) => stx.tx.tx.nonce >= nextExpected
          case None               => true
      }

      // 2. Minimum tip.
      // ETC (ECIP-1122): the tip a tx pays at the current base fee must reach the floor. Pre-Olympia (Spiral):
      // 1 wei, matching core-geth's txpool.pricelimit default. At/after Olympia: blockchainConfig.minTip (1 gwei).
      // ETH-family (network-type = eth): go-ethereum's rule. The tx's tip CAP (maxPriorityFeePerGas, or gasPrice
      // for legacy and access-list txs) must reach minTip, geth's txpool.pricelimit (1 wei unless configured).
      // A tx whose fee cap sits exactly at today's base fee pays no tip at that base fee, yet it is includable
      // once the base fee holds or falls, and geth pools and announces it. Measuring the effective tip there
      // rejected it, so hive's devp2p Transaction/InvalidTxs/LargeTxRequest never saw it announced.
      val bestBlockOpt = Option(blockchainReader).flatMap(_.getBestBlock)
      val currentBaseFee = bestBlockOpt.flatMap(_.header.baseFee).getOrElse(blockchainConfig.baseFeeFloor)
      val isEthFamily = blockchainConfig.networkType == com.chipprbots.ethereum.utils.NetworkType.ETH
      val isOlympiaActive =
        bestBlockOpt.exists(_.header.number.value >= blockchainConfig.forkBlockNumbers.olympiaBlockNumber)
      val effectiveMinTip = if isEthFamily || isOlympiaActive then blockchainConfig.minTip else BigInt(1)
      def tipCap(tx: com.chipprbots.ethereum.domain.Transaction): BigInt =
        import com.chipprbots.ethereum.domain.*
        tx match
          case t: TransactionWithDynamicFee => t.maxPriorityFeePerGas
          case t: BlobTransaction           => t.maxPriorityFeePerGas
          case t: SetCodeTransaction        => t.maxPriorityFeePerGas
          case other                        => other.gasPrice.value
      val afterTipCheck = afterPendingNonceCheck.filter { stx =>
        val effectiveTip =
          if isEthFamily then tipCap(stx.tx.tx)
          else
            com.chipprbots.ethereum.domain.Transaction
              .effectiveGasPrice(stx.tx.tx, Some(currentBaseFee)) - currentBaseFee
        if effectiveTip < effectiveMinTip then
          context.log.debug(
            "Rejecting tx {} from {}: effectiveTip {} < minTip {}",
            stx.tx.hash.toHex,
            stx.senderAddress,
            effectiveTip,
            effectiveMinTip
          )
          false
        else true
      }

      if blockchainReader == null || stateStorage == null then return afterTipCheck
      try
        import com.chipprbots.ethereum.domain.Account
        import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
        import MerklePatriciaTrie.defaultByteArraySerializable

        val bestBlockOpt = blockchainReader.getBestBlock
        bestBlockOpt match
          case Some(bestBlock) =>
            val mptStorage = stateStorage.getReadOnlyStorage
            val stateTrie = MerklePatriciaTrie[Array[Byte], Account](bestBlock.header.stateRoot.toArray, mptStorage)(
              defaultByteArraySerializable,
              Account.accountSerializer
            )

            val accountsBySender = afterTipCheck
              .map(_.senderAddress)
              .map { senderAddress =>
                val addressHash = com.chipprbots.ethereum.crypto.kec256(senderAddress.toArray)
                senderAddress -> stateTrie.get(addressHash)
              }
              .toMap

            afterTipCheck.filter { stx =>
              accountsBySender.get(stx.senderAddress).flatten.exists { account =>
                val tx = stx.tx.tx
                val nonceValid = tx.nonce >= account.nonce.toBigInt && tx.nonce < account.nonce.toBigInt + 1024
                val maxGasCost = tx.gasLimit.value * tx.gasPrice.value
                val totalCost = tx.value + maxGasCost
                val balanceValid = account.balance.toBigInt >= totalCost
                nonceValid && balanceValid
              }
            }
          case None =>
            // No best block — only accept txs from senders we've already seen
            afterTipCheck.filter(stx => pendingNonces.contains(stx.senderAddress))
      catch
        case _: Exception =>
          // MPT failed — only accept txs from senders with established pending nonces
          // (unknown senders can't be validated without state)
          afterTipCheck.filter(stx => pendingNonces.contains(stx.senderAddress))

    def isTxKnown(signedTransaction: SignedTransaction, peerId: PeerId): Boolean =
      knownTransactions.getOrElse(signedTransaction.hash.value, Set.empty).contains(peerId)

    def setTxKnown(signedTransaction: SignedTransaction, peerId: PeerId): Unit =
      val currentPeers = knownTransactions.getOrElse(signedTransaction.hash.value, Set.empty)
      val newPeers = currentPeers + peerId
      knownTransactions += (signedTransaction.hash.value -> newPeers)

    // scalastyle:off method.length
    Behaviors.receiveMessage {
      case WrappedPeerEvent(PeerEvent.PeerHandshakeSuccessful(peer, handshakeResult)) =>
        connectedPeers += (peer.id -> peer)
        handshakeResult match
          case pi: PeerInfo => connectedPeerCapabilities += (peer.id -> pi.remoteStatus.capability)
          case _            => // non-ETH handshake result — leave unset, falls back to the ETH68 wire shape
        pendingTransactions.cleanUp()
        val stxs = pendingTransactions.asMap().values().asScala.toSeq.map(_.stx)
        context.self ! NotifyPeers(stxs, Seq(peer))
        Behaviors.same

      case WrappedPeerEvent(PeerEvent.PeerDisconnected(peerId)) =>
        connectedPeers -= peerId
        connectedPeerCapabilities -= peerId
        Behaviors.same

      case AddUncheckedTransactions(transactions) =>
        val validTxs = SignedTransactionWithSender.getSignedTransactions(transactions)
        context.self ! AddTransactions(validTxs.toSet)
        Behaviors.same

      case AnnounceTransactions(signedTransactions, peerId) =>
        signedTransactions.foreach(tx => setTxKnown(tx, peerId))
        notifyPeersOfTransactions(signedTransactions, connectedPeers.values.toSeq)
        Behaviors.same

      case AddTransactions(signedTransactions) =>
        pendingTransactions.cleanUp()
        val stxs = pendingTransactions.asMap().values().asScala.map(_.stx).toSet
        val newTxs = signedTransactions.diff(stxs)
        context.log.debug(
          "Adding {} txs ({} new, {} in pool)",
          signedTransactions.size,
          newTxs.size,
          stxs.size
        )
        // Validate against chain state (nonce, balance) before adding to pool
        val transactionsToAdd = validateAgainstState(newTxs)
        if transactionsToAdd.nonEmpty then
          val timestamp = System.currentTimeMillis()
          transactionsToAdd.foreach(t => pendingTransactions.put(t.tx.hash.value, PendingTransaction(t, timestamp)))
          updatePendingNonces(transactionsToAdd)
          transactionsToAdd.foreach(t => pendingTxTopic ! Topic.Publish(NewPendingTransaction(t)))
          val peers = connectedPeers.values.toSeq
          if peers.nonEmpty then context.self ! NotifyPeers(transactionsToAdd.toSeq, peers)
        Behaviors.same

      case AddOrOverrideTransaction(newStx, blobRawBytesOpt) =>
        pendingTransactions.cleanUp()
        context.log.debug("Overriding transaction: {}", newStx.hash.toHex)
        blobRawBytesOpt.foreach(raw => blobTxNetworkBytes += (newStx.hash.value -> raw))
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
        pendingTransactions.put(
          newStx.hash.value,
          PendingTransaction(newPendingTx, timestamp, receivedFromLocalSource = true)
        )
        updatePendingNonces(Seq(newPendingTx))
        pendingTxTopic ! Topic.Publish(NewPendingTransaction(newPendingTx))
        val peers = connectedPeers.values.toSeq
        if peers.nonEmpty then context.self ! NotifyPeers(Seq(newPendingTx), peers)
        Behaviors.same

      case NotifyPeers(signedTransactions, peers) =>
        pendingTransactions.cleanUp()
        context.log.debug(
          "Notifying peers {} about transactions {}",
          peers.map(_.nodeId.map(_.toHex)),
          signedTransactions.map(_.tx.hash.toHex)
        )
        val pendingTxMap = pendingTransactions.asMap()
        val stillPending = signedTransactions
          .filter(stx => pendingTxMap.containsKey(stx.tx.hash))
          .map(_.tx)

        notifyPeersOfTransactions(stillPending, peers)
        Behaviors.same

      // ETH67-71 NewPooledTransactionHashes (3-field) — request unknown tx hashes via GetPooledTransactions
      case WrappedPeerEvent(
            com.chipprbots.ethereum.network.PeerEventBusActor.PeerEvent
              .MessageFromPeer(msg: ETHPackets.NewPooledTransactionHashes, peerId)
          ) =>
        requestUnknownAnnouncedHashes(msg.hashes, msg.types, msg.sizes, peerId)
        Behaviors.same

      // ETH72 NewPooledTransactionHashes72 (4-field, adds a custody Mask — EIP-8070) — same wire code as
      // the 3-field form above but a DIFFERENT, non-subtype case class (see its doc comment in
      // ETHPackets.scala), so it needs its own case: a peer that negotiated ETH72 sends this shape, never
      // the 3-field one, and without this arm the message matched no case here at all — silently dropped,
      // so fukuii never issued GetPooledTransactions for an ETH72 peer's announcement. That is what hive's
      // TestNewPooledTxs and TestBlobViolations were observing as a read timeout waiting for
      // GetPooledTransactions ("reading pooled tx request failed: i/o timeout"), not a decode failure —
      // ETH72MessageDecoder already decoded the message correctly; nothing downstream ever acted on it.
      case WrappedPeerEvent(
            com.chipprbots.ethereum.network.PeerEventBusActor.PeerEvent
              .MessageFromPeer(msg: ETHPackets.NewPooledTransactionHashes72, peerId)
          ) =>
        requestUnknownAnnouncedHashes(msg.hashes, msg.types, msg.sizes, peerId)
        Behaviors.same

      // ETH66+ PooledTransactions response — add received txs to pool
      case WrappedPeerEvent(
            com.chipprbots.ethereum.network.PeerEventBusActor.PeerEvent
              .MessageFromPeer(msg: ETHPackets.PooledTransactions, peerId)
          ) =>
        // Validate received txs against their announcements (type/size mismatch = blob violation)
        import com.chipprbots.ethereum.domain.*
        val announcementViolation: Option[String] = msg.txs.zipWithIndex.iterator
          .flatMap { case (stx, idx) =>
            pendingAnnouncements.get(stx.hash.value).flatMap { case (announcedType, announcedSize, _) =>
              val actualType: Byte = stx.tx match
                case _: LegacyTransaction         => 0.toByte
                case _: TransactionWithAccessList => Transaction.Type01
                case _: TransactionWithDynamicFee => Transaction.Type02
                case _: BlobTransaction           => Transaction.Type03
                case _: SetCodeTransaction        => Transaction.Type04
              val typeMismatch = actualType != announcedType
              // Wire size from the PooledTransactions decode, against the announcement with go-ethereum's slack: it
              // drops a peer only when the two are more than 8 bytes apart (eth/fetcher/tx_fetcher.go), because the
              // size it announces is not always the exact wire length. For a blob tx its Transaction.Size() prices the
              // outer list header as if it wrapped the sidecar alone, one byte short when the sidecar is tiny — a blob
              // tx sent without blobs. Dropping on any difference disconnected go-ethereum peers over that byte.
              val deliveredSize = msg.originalSizes.lift(idx).map(BigInt(_))
              val sizeMismatch = deliveredSize.exists(size => (size - announcedSize).abs > AnnouncedSizeSlackBytes)
              Option.when(typeMismatch || sizeMismatch)(
                s"tx ${stx.hash.toHex} announced as type $announcedType, $announcedSize bytes; " +
                  s"delivered as type $actualType, ${deliveredSize.getOrElse("?")} bytes"
              )
            }
          }
          .nextOption()
        // Clean up announcements for received txs
        msg.txs.foreach(stx => pendingAnnouncements -= stx.hash.value)
        announcementViolation match
          case Some(violation) =>
            context.log.info(
              "PooledTransactions from peer {} contradicts its announcement — disconnecting: {}",
              peerId,
              violation
            )
            peerManager ! PeerManagerActor.DisconnectPeerFireAndForgetCmd(peerId)
          case None =>
            // Store blob tx sidecar bytes for PooledTransactions responses
            msg.blobTxRawBytes.foreach { case (hash, rawBytes) =>
              blobTxNetworkBytes += (hash -> rawBytes)
            }
            val validTxs = SignedTransactionWithSender.getSignedTransactions(msg.txs)
            if validTxs.nonEmpty then
              context.self ! AddTransactions(validTxs.toSet)
              validTxs.foreach(stx => setTxKnown(stx.tx, peerId))
        Behaviors.same

      case GetPendingTransactionsReq(replyTo) =>
        pendingTransactions.cleanUp()
        replyTo ! PendingTransactionsResponse(
          pendingTransactions.asMap().asScala.values.toSeq,
          blobTxNetworkBytes
        )
        Behaviors.same

      case RemoveTransactions(signedTransactions) =>
        pendingTransactions.invalidateAll(signedTransactions.map(_.hash.value).asJava)
        context.log.debug("Removing transactions: {}", signedTransactions.map(_.hash.toHex))
        knownTransactions = knownTransactions -- signedTransactions.map(_.hash.value)
        blobTxNetworkBytes = blobTxNetworkBytes -- signedTransactions.map(_.hash.value)
        Behaviors.same

      case ProperSignedTransactions(transactions, peerId) =>
        context.self ! AddTransactions(transactions)
        transactions.foreach(stx => setTxKnown(stx.tx, peerId))
        Behaviors.same

      case ClearPendingTransactions =>
        context.log.debug("Dropping all cached transactions")
        pendingTransactions.invalidateAll()
        pendingNonces = Map.empty
        blobTxNetworkBytes = Map.empty
        Behaviors.same

      // Any other PeerEvent (e.g. a MessageFromPeer whose payload isn't a tx-pool message we
      // subscribed for) is ignored — keeps the total match exhaustive without a MatchError.
      case WrappedPeerEvent(_) =>
        Behaviors.same
    }
    // scalastyle:on method.length
  }
