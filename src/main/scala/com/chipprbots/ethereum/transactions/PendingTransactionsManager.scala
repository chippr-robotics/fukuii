package com.chipprbots.ethereum.transactions

import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.MailboxSelector
import org.apache.pekko.actor.typed.pubsub.Topic
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.util.ByteString

import scala.collection.immutable.VectorMap
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

import com.google.common.cache.Cache
import com.google.common.cache.CacheBuilder
import com.google.common.cache.RemovalCause
import com.google.common.cache.RemovalNotification

import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.SignedTransaction
import com.chipprbots.ethereum.domain.SignedTransactionWithSender
import com.chipprbots.ethereum.domain.Timestamp
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

  /** The most hashes one GetPooledTransactions asks for: go-ethereum's `maxTxRetrievals` (eth/fetcher/tx_fetcher.go,
    * 256 at c6e3a0d7, 2026-09-23), "a failsafe against malicious announces which might cause us to request more data
    * than we'd expect". An announcement can carry up to `announcement-max-entries` hashes; they are all still
    * requested, in batches of at most this many, so a reply (and the per-tx work on it) is bounded by our request.
    */
  private[transactions] val MaxTxRetrievals: Int = 256

  sealed trait Command

  case class AddTransactions(signedTransactions: Set[SignedTransactionWithSender]) extends Command
  object AddTransactions:
    def apply(txs: SignedTransactionWithSender*): AddTransactions = AddTransactions(txs.toSet)

  case class AddUncheckedTransactions(signedTransactions: Seq[SignedTransaction]) extends Command

  case class AnnounceTransactions(signedTransactions: Seq[SignedTransaction], peerId: PeerId) extends Command

  /** Put a transaction in the pool, replacing any pooled transaction from the same sender with the same nonce, with no
    * pool-state check. Local submission goes through [[SubmitLocalTransaction]] instead.
    */
  case class AddOrOverrideTransaction(
      signedTransaction: SignedTransaction,
      blobTxRawBytes: Option[ByteString] = None
  ) extends Command

  /** A locally submitted transaction (eth_sendRawTransaction, eth_sendTransaction, personal_sendTransaction, GraphQL
    * sendRawTransaction) that passed [[TxAdmission.validateLocal]] against `head`. The pool applies its own rules
    * against what it holds ([[TxAdmission.poolRejection]]: already known, overdraft, delegation limit, replacement
    * price bump) and, if they pass, adds it as [[AddOrOverrideTransaction]] does. The reply is the refusal, or `Right`
    * once the transaction is pooled; go-ethereum holds the pool lock across the same check and insert.
    *
    * @param account
    *   the sender's account in `head`'s state, as admission read it
    */
  case class SubmitLocalTransaction(
      signedTransaction: SignedTransaction,
      sender: Address,
      account: Account,
      head: BlockHeader,
      blobTxRawBytes: Option[ByteString],
      replyTo: ActorRef[Either[TxAdmission.Rejection, Unit]]
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

  /** Periodic: move GetPooledTransactions requests a peer has not answered in time to another announcer. */
  private case object FetchTimeoutTick extends Command

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
      chainConfig: BlockchainConfig = Config.blockchains.blockchainConfig,
      txGossipGate: TxGossipGate = TxGossipGate.alwaysOpen
  ): Behavior[Command] = Behaviors.setup { context =>

    given blockchainConfig: BlockchainConfig = chainConfig

    // The chain head's timestamp. The pool admits a transaction under the rules of the fork active at the head, as
    // go-ethereum's pool does (txpool.ValidateTransaction against pool.currentHead). The stateless filter reads it
    // only on ETH-family chains. Genesis rules (timestamp 0) when there is no head to read: a pool built without a
    // reader, as some specs build one, or storage that holds no best block yet.
    def headTimestamp(): Timestamp =
      Option(blockchainReader).flatMap(_.getBestBlockHeader).map(_.unixTimestamp).getOrElse(Timestamp.Zero)

    // Spawn STFA as a child with a bounded mailbox (backpressure from network layer)
    context.spawn(
      SignedTransactionsFilterActor(context.self, peerEventBus, () => headTimestamp(), () => txGossipGate.acceptTxs),
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

    /** stores information which tx hashes are "known" by which peers; bounded by size and age (see
      * [[KnownTransactions]])
      */
    val knownTransactions = new KnownTransactions(txPoolConfig.knownTxMaxEntries, txPoolConfig.knownTxTtl)

    /** Raw network-wrapped bytes for EIP-4844 blob txs (txHash → 0x03||rlp([payload,blobs,commitments,proofs])). Needed
      * to replay the sidecar in PooledTransactions responses (EIP-4844 requirement).
      *
      * Holds a sidecar only while its transaction is in `pendingTransactions`: it is stored after the transaction is
      * admitted, and the cache's removal listener drops it on every way out of the pool. Insertion-ordered, so the byte
      * budget evicts the oldest first. It used to be filled for every blob tx a peer delivered, BEFORE admission, and
      * emptied only by the listener's eviction branch (which never fires for a tx that was never admitted): a node in
      * SNAP sync, rejecting every tx for want of state, grew it to 2.6 GB and was OOM-killed.
      */
    var blobTxNetworkBytes: VectorMap[ByteString, ByteString] = VectorMap.empty

    /** Total length of the sidecars in `blobTxNetworkBytes`, kept within `txPoolConfig.blobSidecarBudgetBytes`. */
    var blobSidecarBytes: Long = 0L

    /** Peer tx messages dropped because the node is not synced (see [[TxGossipGate]]). Only for periodic logging. */
    var ignoredWhileUnsynced: Long = 0L

    /** Pooled set-code transactions by hash, with the authorities their authorizations recover to: go-ethereum's
      * `lookup.auths`, read by the delegation rule ([[TxAdmission.poolRejection]]). Dropped by the cache's removal
      * listener, so declared ahead of it.
      */
    var authoritiesByTx: Map[ByteString, Seq[Address]] = Map.empty

    /** Forget the sidecar of `hash`, if there is one. Defined ahead of the cache because its removal listener calls it.
      */
    def dropSidecar(hash: ByteString): Unit =
      blobTxNetworkBytes.get(hash).foreach { networkForm =>
        blobTxNetworkBytes -= hash
        blobSidecarBytes -= networkForm.length
      }

    /** stores all pending transactions */
    val pendingTransactions: Cache[ByteString, PendingTransaction] = CacheBuilder
      .newBuilder()
      .expireAfterWrite(txPoolConfig.transactionTimeout._1, txPoolConfig.transactionTimeout._2)
      .maximumSize(txPoolConfig.txPoolSize)
      .removalListener(
        new com.google.common.cache.RemovalListener[ByteString, PendingTransaction]:
          // Every way a transaction leaves the pool (size or age eviction, invalidate, invalidateAll) passes through
          // here, so this is the one place its per-tx state is dropped. REPLACED is a put over the same hash: the tx
          // stays, and so does its state.
          def onRemoval(notification: RemovalNotification[ByteString, PendingTransaction]): Unit =
            if notification.getCause != RemovalCause.REPLACED then
              if notification.wasEvicted() then
                context.log
                  .debug("Evicting transaction: {} due to {}", notification.getKey.toHex, notification.getCause)
              knownTransactions.remove(notification.getKey)
              dropSidecar(notification.getKey)
              authoritiesByTx -= notification.getKey
      )
      .build()

    /** Put `ptx` in the pool, recording a set-code transaction's authorities. */
    def putPooled(ptx: PendingTransaction): Unit =
      val hash = ptx.stx.tx.hash.value
      pendingTransactions.put(hash, ptx)
      ptx.stx.tx.tx match
        case sct: com.chipprbots.ethereum.domain.SetCodeTransaction if pendingTransactions.asMap().containsKey(hash) =>
          authoritiesByTx = authoritiesByTx.updated(hash, TxAdmission.setCodeAuthorities(sct))
        case _ => ()

    /** The pool by sender, read in one pass ([[PoolSenderIndex]]), for the pool-state rules. */
    def poolSenderIndex(): PoolSenderIndex =
      val pool = pendingTransactions.asMap()
      PoolSenderIndex(
        pool.values().asScala.iterator.map(ptx => (ptx.stx.senderAddress, ptx.stx.tx.hash.value, ptx.stx.tx.tx)),
        pool.containsKey
      )

    /** What the pool holds that [[TxAdmission.poolRejection]] reads for a transaction from `sender` (hash `hash`), read
      * from `index` — built once per batch by [[admit]] (once per local submission) — rather than from a copy of the
      * whole pool per transaction (#1675).
      */
    def poolView(sender: Address, hash: ByteString, index: PoolSenderIndex): TxAdmission.PoolView =
      TxAdmission.PoolView(
        senderTxs = index.pooledFrom(sender, excluding = Some(hash)),
        hasPendingAuthorization = authoritiesByTx.valuesIterator.exists(_.contains(sender)),
        pooledFrom = address => index.pooledFrom(address)
      )

    /** Keep `networkForm` for the pooled blob tx `hash`, evicting the oldest blob txs (sidecar and tx together) past
      * the byte budget. A sidecar larger than the whole budget cannot be kept, and a blob tx cannot be served without
      * it, so that tx leaves the pool.
      */
    def storeSidecar(hash: ByteString, networkForm: ByteString): Unit =
      val budget = txPoolConfig.blobSidecarBudgetBytes
      if networkForm.length.toLong > budget then
        context.log.warn(
          "Dropping blob tx {}: its {}-byte sidecar exceeds the whole sidecar budget of {} bytes",
          hash.toHex,
          networkForm.length,
          budget
        )
        pendingTransactions.invalidate(hash)
        dropSidecar(hash)
      else
        dropSidecar(hash)
        blobTxNetworkBytes = blobTxNetworkBytes.updated(hash, networkForm)
        blobSidecarBytes += networkForm.length
        while blobSidecarBytes > budget do
          val (oldest, _) = blobTxNetworkBytes.head
          context.log.debug("Blob sidecar budget of {} bytes reached: evicting blob tx {}", budget, oldest.toHex)
          // dropSidecar first: progress is guaranteed even for a sidecar whose tx has somehow already left the pool.
          dropSidecar(oldest)
          pendingTransactions.invalidate(oldest)

    /** Whether to process a peer's tx message. When not, the message is dropped whole: nothing requested, stored or
      * marked known — go-ethereum's `if !backend.AcceptTxs() { return nil }`.
      */
    def acceptPeerTxs(): Boolean =
      val accept = txGossipGate.acceptTxs
      if !accept then
        ignoredWhileUnsynced += 1
        if ignoredWhileUnsynced % 10000 == 1 then
          context.log.debug("Node not synced: ignoring peer transaction messages ({} so far)", ignoredWhileUnsynced)
      accept

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
    val pendingAnnouncements =
      new PendingAnnouncements(
        txPoolConfig.announcementMaxEntries,
        txPoolConfig.announcementTimeout,
        txPoolConfig.announcementFetchTimeout
      )

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

    /** Handle an inbound NewPooledTransactionHashes announcement (ETH67+ 3-field, or ETH72's 4-field form): record each
      * announcement's (type, size) for later validation against this peer's PooledTransactions reply, and request the
      * hashes we neither hold nor are already fetching from another peer (see [[PendingAnnouncements]]: one requester
      * per hash, the other announcers are alternates). Shared by both wire shapes — ETH72's custody Mask is not
      * consulted here (fukuii has no PeerDAS cell storage to fetch selectively against; see
      * NewPooledTransactionHashes72's doc comment) — so fetching unknown hashes is identical either way.
      */
    def requestUnknownAnnouncedHashes(
        hashes: Seq[ByteString],
        types: Seq[Byte],
        sizes: Seq[BigInt],
        peerId: PeerId
    ): Unit =
      val pool = pendingTransactions.asMap()
      val toRequest = hashes.zip(types).zip(sizes).collect {
        case ((hash, txType), size)
            if !pool.containsKey(hash) && pendingAnnouncements.announce(hash, txType, size, peerId) =>
          hash
      }
      if toRequest.nonEmpty then requestPooledTransactions(peerId, toRequest)

    /** Ask `peerId` for `hashes`, at most [[MaxTxRetrievals]] per request. Every hash is already recorded as requested
      * from `peerId` ([[PendingAnnouncements.announce]]), so none may be left out: the excess goes in further requests.
      */
    def requestPooledTransactions(peerId: PeerId, hashes: Seq[ByteString]): Unit =
      hashes.grouped(MaxTxRetrievals).foreach { batch =>
        networkPeerManager ! NetworkPeerManagerActor.SendMessageCmd(
          ETHPackets.GetPooledTransactions(ETHPackets.nextRequestId, batch),
          peerId
        )
      }

    /** Send the requests that moved to another announcer (its predecessor timed out, was dropped or disconnected). */
    def requestFromAlternates(reassigned: Map[PeerId, Seq[ByteString]], why: String): Unit =
      reassigned.foreach { case (peerId, hashes) =>
        context.log.debug("Requesting {} announced tx(s) from alternate peer {} ({})", hashes.size, peerId, why)
        requestPooledTransactions(peerId, hashes)
      }

    /** Update pendingNonces high-water mark for accepted transactions. */
    def updatePendingNonces(txs: Iterable[SignedTransactionWithSender]): Unit =
      txs.foreach { stx =>
        val nextNonce = stx.tx.tx.nonce + 1
        val current = pendingNonces.getOrElse(stx.senderAddress, BigInt(0))
        if nextNonce > current then pendingNonces = pendingNonces.updated(stx.senderAddress, nextNonce)
      }

    /** Peer transactions that passed [[validateAgainstState]], with what the pool-state rules read for them. */
    case class StateChecked(
        txs: Set[SignedTransactionWithSender],
        accounts: Map[Address, Account] = Map.empty,
        head: Option[BlockHeader] = None
    )

    /** Validate transactions against the current chain state. Rejects: stale nonces, insufficient balance for value +
      * gas. Returns only transactions that pass state validation, with the senders' accounts and the head read.
      */
    def validateAgainstState(txs: Set[SignedTransactionWithSender]): StateChecked =
      // 1. Always apply pending nonce check first (no MPT state needed, immune to race conditions)
      val afterPendingNonceCheck = txs.filter { stx =>
        pendingNonces.get(stx.senderAddress) match
          case Some(nextExpected) => stx.tx.tx.nonce >= nextExpected
          case None               => true
      }

      // 2. Minimum tip: the pool's rule for the head, stated once in TxAdmission.tipRejection (ETH: tip cap >= minTip,
      // geth's txpool.pricelimit; ETC, ECIP-1122: the tip paid at the current base fee >= 1 wei before Olympia,
      // minTip from it).
      val bestHeaderOpt = Option(blockchainReader).flatMap(_.getBestBlock).map(_.header)
      // 3. Type active at the head: TxAdmission.typeRejection, the gate local submission applies (go-ethereum and
      // core-geth reject in txpool.ValidateTransaction). Without it a pre-Olympia ETC pool admitted and gossiped peer
      // 0x2/0x4 txs the miner could then select. No head (a pool built without a reader): no gate, as before.
      val afterTipAndTypeCheck = afterPendingNonceCheck.filter { stx =>
        val rejection = TxAdmission
          .tipRejection(stx.tx.tx, bestHeaderOpt)
          .orElse(bestHeaderOpt.flatMap(TxAdmission.typeRejection(stx.tx.tx, _)))
        rejection match
          case Some(r) =>
            context.log.debug("Rejecting tx {} from {}: {}", stx.tx.hash.toHex, stx.senderAddress, r.message)
            false
          case None => true
      }

      if blockchainReader == null || stateStorage == null then return StateChecked(afterTipAndTypeCheck)
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

            val accountsBySender = afterTipAndTypeCheck
              .map(_.senderAddress)
              .map { senderAddress =>
                val addressHash = com.chipprbots.ethereum.crypto.kec256(senderAddress.toArray)
                senderAddress -> stateTrie.get(addressHash)
              }
              .toMap

            val passed = afterTipAndTypeCheck.filter { stx =>
              accountsBySender.get(stx.senderAddress).flatten.exists { account =>
                val tx = stx.tx.tx
                // Nonce not below the account's and balance covering the upfront cost: TxAdmission.stateRejection,
                // the rule local submission applies. A peer's tx may not run more than 1024 ahead of the account.
                TxAdmission.stateRejection(tx, account).isEmpty && tx.nonce < account.nonce.toBigInt + 1024
              }
            }
            StateChecked(
              passed,
              accountsBySender.collect { case (address, Some(account)) => address -> account },
              Some(bestBlock.header)
            )
          case None =>
            // No best block — only accept txs from senders we've already seen
            StateChecked(afterTipAndTypeCheck.filter(stx => pendingNonces.contains(stx.senderAddress)))
      catch
        case _: Exception =>
          // MPT failed — only accept txs from senders with established pending nonces
          // (unknown senders can't be validated without state)
          StateChecked(afterTipAndTypeCheck.filter(stx => pendingNonces.contains(stx.senderAddress)))

    def isTxKnown(signedTransaction: SignedTransaction, peerId: PeerId): Boolean =
      knownTransactions.isKnown(signedTransaction.hash.value, peerId)

    def setTxKnown(signedTransaction: SignedTransaction, peerId: PeerId): Unit =
      knownTransactions.markKnown(signedTransaction.hash.value, peerId)

    /** Record that `peerId` knows the txs it sent us, for those now in the pool. A tx the pool rejected has no entry to
      * remove it later, so marking it would leak; and it is never announced, so there is nothing to suppress.
      */
    def setPooledTxsKnown(txs: Iterable[SignedTransactionWithSender], peerId: PeerId): Unit =
      val pool = pendingTransactions.asMap()
      txs.foreach(stx => if pool.containsKey(stx.tx.hash.value) then setTxKnown(stx.tx, peerId))

    /** Admit `signedTransactions` to the pool: those not already pending that pass [[validateAgainstState]]. Blob txs
      * get their sidecar from `blobSidecars` (network form, by tx hash) once admitted, never before. Returns the txs
      * admitted.
      */
    def admit(
        signedTransactions: Set[SignedTransactionWithSender],
        blobSidecars: Map[ByteString, ByteString]
    ): Set[SignedTransactionWithSender] =
      pendingTransactions.cleanUp()
      val pool = pendingTransactions.asMap()
      val newTxs = signedTransactions.filterNot(stx => pool.containsKey(stx.tx.hash.value))
      context.log.debug(
        "Adding {} txs ({} new, {} in pool)",
        signedTransactions.size,
        newTxs.size,
        pool.size
      )
      // Validate against chain state (nonce, balance) before adding to pool
      val checked = validateAgainstState(newTxs)
      val transactionsToAdd = checked.txs
      if transactionsToAdd.isEmpty then Set.empty
      else
        val timestamp = System.currentTimeMillis()
        // The pool by sender, read once for the whole batch, and only where the pool-state rules read it (#1675).
        val senderIndex: Option[PoolSenderIndex] = Option.when(TxAdmission.poolStateRulesApply)(poolSenderIndex())
        // One tx at a time, its sidecar right after its put: a later put can size-evict an earlier tx, and the removal
        // listener must then find that tx's sidecar already stored to drop it. In nonce order per sender, so the
        // pool-state rules see each sender's earlier txs from this batch as pooled (each is indexed once it is in).
        transactionsToAdd.toSeq.sortBy(t => (t.senderAddress.toUnprefixedString, t.tx.tx.nonce)).foreach { t =>
          val hash = t.tx.hash.value
          if poolAdmits(t, checked, senderIndex) then
            putPooled(PendingTransaction(t, timestamp))
            if pool.containsKey(hash) then senderIndex.foreach(_.add(t.senderAddress, hash, t.tx.tx))
            blobSidecars.get(hash).foreach(storeSidecar(hash, _))
        }
        val admitted = transactionsToAdd.filter(t => pool.containsKey(t.tx.hash.value))
        updatePendingNonces(admitted)
        admitted.foreach(t => pendingTxTopic ! Topic.Publish(NewPendingTransaction(t)))
        val peers = connectedPeers.values.toSeq
        if peers.nonEmpty && admitted.nonEmpty then context.self ! NotifyPeers(admitted.toSeq, peers)
        admitted

    /** The pool-state rules ([[TxAdmission.poolRejection]]) for a peer's tx, as go-ethereum applies them to remote txs
      * too. Only where they apply ([[TxAdmission.poolStateRulesApply]], exactly where [[admit]] builds `senderIndex`)
      * and the head's state was read; a peer's tx with a pooled nonce never reaches here (the pending-nonce check
      * refuses it), so no replacement is decided here.
      */
    def poolAdmits(
        t: SignedTransactionWithSender,
        checked: StateChecked,
        senderIndex: Option[PoolSenderIndex]
    ): Boolean =
      senderIndex match
        case None => true // the pool-state rules do not apply on this chain
        case Some(index) =>
          (checked.accounts.get(t.senderAddress), checked.head) match
            case (Some(account), Some(head)) =>
              TxAdmission.poolRejection(
                t.tx.tx,
                alreadyKnown = false,
                account,
                poolView(t.senderAddress, t.tx.hash.value, index),
                head,
                txPoolConfig.priceBump,
                txPoolConfig.blobPriceBump
              ) match
                case Some(r) =>
                  context.log.debug("Rejecting tx {} from {}: {}", t.tx.hash.toHex, t.senderAddress, r.message)
                  false
                case None => true
            case _ => true

    /** Put a local tx in the pool, first dropping any pooled tx from the same sender with the same nonce. */
    def addOrOverride(newStx: SignedTransaction, newStxSender: Address, blobRawBytesOpt: Option[ByteString]): Unit =
      pendingTransactions.cleanUp()
      context.log.debug("Overriding transaction: {}", newStx.hash.toHex)
      // The same tx sent again is not obsolete: the put below replaces it in place and its state stays.
      val obsoleteTxs = pendingTransactions
        .asMap()
        .asScala
        .filter(ptx =>
          ptx._1 != newStx.hash.value &&
            ptx._2.stx.senderAddress == newStxSender && ptx._2.stx.tx.tx.nonce == newStx.tx.nonce
        )
      pendingTransactions.invalidateAll(obsoleteTxs.keys.asJava)

      val timestamp = System.currentTimeMillis()
      val newPendingTx = SignedTransactionWithSender(newStx, newStxSender)
      putPooled(PendingTransaction(newPendingTx, timestamp, receivedFromLocalSource = true))
      blobRawBytesOpt.foreach(raw => storeSidecar(newStx.hash.value, raw))
      updatePendingNonces(Seq(newPendingTx))
      pendingTxTopic ! Topic.Publish(NewPendingTransaction(newPendingTx))
      val peers = connectedPeers.values.toSeq
      if peers.nonEmpty then context.self ! NotifyPeers(Seq(newPendingTx), peers)

    // scalastyle:off method.length
    val receive: Behavior[Command] = Behaviors.receiveMessage {
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
        // Its outstanding requests will never be answered: ask the peers that also announced those txs.
        requestFromAlternates(pendingAnnouncements.removePeer(peerId), "requester disconnected")
        Behaviors.same

      case FetchTimeoutTick =>
        requestFromAlternates(pendingAnnouncements.timedOut(), "request timed out")
        Behaviors.same

      case AddUncheckedTransactions(transactions) =>
        val validTxs = SignedTransactionWithSender.getSignedTransactions(transactions, headTimestamp())
        context.self ! AddTransactions(validTxs.toSet)
        Behaviors.same

      case AnnounceTransactions(signedTransactions, peerId) =>
        signedTransactions.foreach(tx => setTxKnown(tx, peerId))
        notifyPeersOfTransactions(signedTransactions, connectedPeers.values.toSeq)
        Behaviors.same

      case AddTransactions(signedTransactions) =>
        admit(signedTransactions, Map.empty)
        Behaviors.same

      case AddOrOverrideTransaction(newStx, blobRawBytesOpt) =>
        // Only validated transactions are added this way, it is safe to call get
        val newStxSender = SignedTransaction
          .getSender(newStx)
          .getOrElse(
            throw new IllegalStateException("Unable to get sender from validated transaction")
          )
        addOrOverride(newStx, newStxSender, blobRawBytesOpt)
        Behaviors.same

      case SubmitLocalTransaction(stx, sender, account, head, blobRawBytesOpt, replyTo) =>
        pendingTransactions.cleanUp()
        val hash = stx.hash.value
        val rejection =
          if !TxAdmission.poolStateRulesApply then None
          else
            TxAdmission.poolRejection(
              stx.tx,
              alreadyKnown = pendingTransactions.asMap().containsKey(hash),
              account,
              poolView(sender, hash, poolSenderIndex()),
              head,
              txPoolConfig.priceBump,
              txPoolConfig.blobPriceBump
            )
        rejection match
          case Some(r) =>
            context.log.debug("Refusing local tx {} from {}: {}", stx.hash.toHex, sender, r.message)
            replyTo ! Left(r)
          case None =>
            addOrOverride(stx, sender, blobRawBytesOpt)
            replyTo ! Right(())
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
        if acceptPeerTxs() then requestUnknownAnnouncedHashes(msg.hashes, msg.types, msg.sizes, peerId)
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
        if acceptPeerTxs() then requestUnknownAnnouncedHashes(msg.hashes, msg.types, msg.sizes, peerId)
        Behaviors.same

      // ETH66+ PooledTransactions response — add received txs to pool
      case WrappedPeerEvent(
            com.chipprbots.ethereum.network.PeerEventBusActor.PeerEvent
              .MessageFromPeer(msg: ETHPackets.PooledTransactions, peerId)
          ) if acceptPeerTxs() =>
        // Validate received txs against their announcements (type/size mismatch = blob violation)
        import com.chipprbots.ethereum.domain.*
        // Read by each tx's position below. `originalSizes` is a decoded list of a length the peer chooses, and indexing
        // a linear Seq is O(i) per read, so index one indexed copy (no copy when it already is one) (#1675).
        val deliveredSizes: IndexedSeq[Int] = msg.originalSizes.toIndexedSeq
        val announcementViolation: Option[String] = msg.txs.zipWithIndex.iterator
          .flatMap { case (stx, idx) =>
            pendingAnnouncements.get(stx.hash.value, peerId).flatMap {
              case PendingAnnouncements.Announcement(announcedType, announcedSize, _) =>
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
                val deliveredSize = deliveredSizes.lift(idx).map(BigInt(_))
                val sizeMismatch = deliveredSize.exists(size => (size - announcedSize).abs > AnnouncedSizeSlackBytes)
                Option.when(typeMismatch || sizeMismatch)(
                  s"tx ${stx.hash.toHex} announced as type $announcedType, $announcedSize bytes; " +
                    s"delivered as type $actualType, ${deliveredSize.getOrElse("?")} bytes"
                )
            }
          }
          .nextOption()
        announcementViolation match
          case Some(violation) =>
            context.log.info(
              "PooledTransactions from peer {} contradicts its announcement — disconnecting: {}",
              peerId,
              violation
            )
            peerManager ! PeerManagerActor.DisconnectPeerFireAndForgetCmd(peerId)
            // Nothing it sent is taken, so the txs are still wanted: ask the other announcers now rather than when its
            // PeerDisconnected arrives (only after the disconnect-poison-pill-timeout).
            requestFromAlternates(pendingAnnouncements.removePeer(peerId), "requester dropped")
          case None =>
            msg.txs.foreach(stx => pendingAnnouncements.remove(stx.hash.value))
            // Sidecars travel with the txs and are kept only for those admitted (see `blobTxNetworkBytes`).
            val validTxs = SignedTransactionWithSender.getSignedTransactions(msg.txs, headTimestamp())
            if validTxs.nonEmpty then
              admit(validTxs.toSet, msg.blobTxRawBytes)
              setPooledTxsKnown(validTxs, peerId)
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
        // The removal listener has dropped what was pooled; a hash that was not pooled may still have an entry.
        knownTransactions.removeAll(signedTransactions.map(_.hash.value))
        signedTransactions.foreach(stx => dropSidecar(stx.hash.value))
        Behaviors.same

      // From SignedTransactionsFilterActor, which drops peer Transactions messages itself while the node is not synced.
      case ProperSignedTransactions(transactions, peerId) =>
        admit(transactions, Map.empty)
        setPooledTxsKnown(transactions, peerId)
        Behaviors.same

      case ClearPendingTransactions =>
        context.log.debug("Dropping all cached transactions")
        pendingTransactions.invalidateAll()
        pendingNonces = Map.empty
        authoritiesByTx = Map.empty
        knownTransactions.clear()
        pendingAnnouncements.clear()
        blobTxNetworkBytes = VectorMap.empty
        blobSidecarBytes = 0L
        Behaviors.same

      // Any other PeerEvent (e.g. a MessageFromPeer whose payload isn't a tx-pool message we
      // subscribed for) is ignored — keeps the total match exhaustive without a MatchError.
      case WrappedPeerEvent(_) =>
        Behaviors.same
    }
    // scalastyle:on method.length

    Behaviors.withTimers[Command] { timers =>
      // A request is moved on between fetchTimeout and 1.2 x fetchTimeout after it was sent.
      timers.startTimerWithFixedDelay(FetchTimeoutTick, (txPoolConfig.announcementFetchTimeout / 5).max(50.millis))
      receive
    }
  }
