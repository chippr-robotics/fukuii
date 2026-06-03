package com.chipprbots.ethereum.blockchain.sync

import org.apache.pekko.actor.Actor
import org.apache.pekko.actor.ActorLogging
import org.apache.pekko.actor.ActorRef
import org.apache.pekko.actor.Props
import org.apache.pekko.util.ByteString

import scala.concurrent.duration._

import org.apache.pekko.pattern.ask
import org.apache.pekko.util.Timeout

import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerEvent.MessageFromPeer
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerSelector
import com.chipprbots.ethereum.network.PeerEventBusActor.Subscribe
import com.chipprbots.ethereum.network.PeerEventBusActor.SubscriptionClassifier.MessageClassifier
import com.chipprbots.ethereum.network.PeerManagerActor.PeerConfiguration
import com.chipprbots.ethereum.network.p2p.Message
import com.chipprbots.ethereum.network.p2p.MessageSerializable
import com.chipprbots.ethereum.network.p2p.messages.Codes
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.GetNodeData
import com.chipprbots.ethereum.blockchain.sync.codec.MptNodeCodecs._
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.NodeData
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets
import com.chipprbots.ethereum.rlp.RLPList
import com.chipprbots.ethereum.transactions.PendingTransactionsManager
import com.chipprbots.ethereum.transactions.PendingTransactionsManager.PendingTransactionsResponse

/** BlockchainHost actor is in charge of replying to the peer's requests for blockchain data, which includes both node
  * and block data.
  */
class BlockchainHostActor(
    blockchainReader: BlockchainReader,
    evmCodeStorage: EvmCodeStorage,
    peerConfiguration: PeerConfiguration,
    peerEventBusActor: ActorRef,
    networkPeerManagerActor: ActorRef,
    pendingTransactionsManager: ActorRef
) extends Actor
    with ActorLogging {

  import context.dispatcher
  implicit val timeout: Timeout = Timeout(3.seconds)

  private val requestMsgsCodes =
    Set(
      Codes.GetNodeDataCode,
      Codes.GetReceiptsCode,
      Codes.GetBlockBodiesCode,
      Codes.GetBlockHeadersCode,
      Codes.GetPooledTransactionsCode
    )
  peerEventBusActor ! Subscribe(MessageClassifier(requestMsgsCodes, PeerSelector.AllPeers))

  override def receive: Receive = { case MessageFromPeer(message, peerId) =>
    // Handle GetPooledTransactions asynchronously (requires ask to PendingTransactionsManager)
    message match {
      case ETHPackets.GetPooledTransactions(requestId, txHashes) =>
        handleGetPooledTransactions(txHashes, Some(requestId), peerId)
      case _ =>
        val responseOpt = handleBlockFastDownload(message).orElse(handleEvmCodeMptFastDownload(message))
        responseOpt.foreach { response =>
          networkPeerManagerActor ! NetworkPeerManagerActor.SendMessage(response, peerId)
        }
    }
  }

  private def handleGetPooledTransactions(
      txHashes: Seq[ByteString],
      requestIdOpt: Option[BigInt],
      peerId: com.chipprbots.ethereum.network.PeerId
  ): Unit = {
    val hashSet = txHashes.toSet
    (pendingTransactionsManager ? PendingTransactionsManager.GetPendingTransactions)
      .mapTo[PendingTransactionsResponse]
      .foreach { response =>
        val matchingTxs = response.pendingTransactions
          .map(_.stx.tx)
          .filter(tx => hashSet.contains(tx.hash))
        // Include blob tx sidecar bytes for EIP-4844 network wrapping in PooledTransactions
        val matchingBlobBytes = response.blobTxNetworkBytes.filter { case (hash, _) => hashSet.contains(hash) }
        val responseMsg: MessageSerializable = requestIdOpt match {
          case Some(requestId) =>
            ETHPackets.PooledTransactions(requestId, matchingTxs, blobTxRawBytes = matchingBlobBytes)
          case None => ETHPackets.PooledTransactions(0, matchingTxs) // requestId=0 for no-requestId case
        }
        networkPeerManagerActor ! NetworkPeerManagerActor.SendMessage(responseMsg, peerId)
      }
  }

  /** Handles requests for node data, which includes both mpt nodes and evm code (both requested by hash). Both types of
    * node data are requested by the same GetNodeData message
    *
    * @param message
    *   to be processed
    * @return
    *   message response if message is a request for node data or None if not
    */
  private def handleEvmCodeMptFastDownload(message: Message): Option[MessageSerializable] = message match {
    case GetNodeData(mptElementsHashes) =>
      val hashesRequested =
        mptElementsHashes.take(peerConfiguration.fastSyncHostConfiguration.maxMptComponentsPerMessage)

      val nodeData: Seq[ByteString] = hashesRequested.flatMap { hash =>
        // Fetch mpt node by hash
        val maybeMptNodeData = blockchainReader.getMptNodeByHash(hash).map(e => e.toBytes: ByteString)

        // If no mpt node was found, fetch evm by hash
        maybeMptNodeData.orElse(evmCodeStorage.get(hash))
      }

      Some(NodeData(nodeData))

    case _ => None
  }

  /** Handles request for block data, which includes receipts, block bodies and headers (all requested by hash)
    *
    * @param message
    *   to be processed
    * @return
    *   message response if message is a request for block data or None if not
    */
  private def handleBlockFastDownload(message: Message): Option[MessageSerializable] = message match {
    // ETH68 GetReceipts — bloom-inclusive response
    case ETHPackets.GetReceipts(requestId, blockHashes) =>
      import ETHPackets.ReceiptBloomEnc
      val receipts = blockHashes
        .take(peerConfiguration.fastSyncHostConfiguration.maxReceiptsPerMessage)
        .flatMap(hash => blockchainReader.getReceiptsByHash(hash))
      val receiptsRLP = RLPList(receipts.map(rs => RLPList(rs.map(_.toRLPEncodable): _*)): _*)
      log.info("HOST_RECEIPTS_ETH68: requestId={} blocks={}", requestId, receipts.size)
      Some(ETHPackets.Receipts68(requestId, receiptsRLP))

    // ETH69 GetReceipts — bloom-ABSENT response per EIP-7642
    case ETHPackets.GetReceipts69(requestId, blockHashes) =>
      import ETHPackets.ReceiptBloomFreeEnc
      val receipts = blockHashes
        .take(peerConfiguration.fastSyncHostConfiguration.maxReceiptsPerMessage)
        .flatMap(hash => blockchainReader.getReceiptsByHash(hash))
      val receiptsRLP = RLPList(receipts.map(rs => RLPList(rs.map(_.toRLPEncodable): _*)): _*)
      log.info("HOST_RECEIPTS_ETH69: requestId={} blocks={} (bloom-absent, EIP-7642)", requestId, receipts.size)
      Some(ETHPackets.Receipts69(requestId, receiptsRLP))

    // ETH68 GetBlockBodies (via ETHPackets)
    case ETHPackets.GetBlockBodies(requestId, hashes) =>
      val blockBodies = hashes
        .take(peerConfiguration.fastSyncHostConfiguration.maxBlocksBodiesPerMessage)
        .flatMap(hash => blockchainReader.getBlockBodyByHash(hash))
      log.debug(
        "HOST_BLOCK_BODIES_ETH68: requestId={} requested={} returning={}",
        requestId,
        hashes.size,
        blockBodies.size
      )
      Some(ETHPackets.BlockBodies(requestId, blockBodies))

    // ETH68 GetBlockHeaders (via ETHPackets)
    case ETHPackets.GetBlockHeaders(requestId, block, maxHeaders, skip, reverse) =>
      handleGetBlockHeadersRequest(block, maxHeaders, skip, reverse, Some(requestId))

    case _ => None

  }

  /** Common logic for handling GetBlockHeaders requests from both ETH62 and ETH66+ protocols
    *
    * @param block
    *   Either block number or block hash
    * @param maxHeaders
    *   Maximum number of headers to return
    * @param skip
    *   Number of blocks to skip between headers
    * @param reverse
    *   Whether to return headers in reverse order
    * @param requestIdOpt
    *   Optional request ID for ETH66+ protocol
    * @return
    *   Response message with block headers or None if request is invalid
    */
  private def handleGetBlockHeadersRequest(
      block: Either[BigInt, ByteString],
      maxHeaders: BigInt,
      skip: BigInt,
      reverse: Boolean,
      requestIdOpt: Option[BigInt]
  ): Option[MessageSerializable] = {
    val blockNumber = block.fold(a => Some(a), b => blockchainReader.getBlockHeaderByHash(b).map(_.number))

    blockNumber match {
      case Some(startBlockNumber) if startBlockNumber >= 0 && maxHeaders >= 0 && skip >= 0 =>
        val headersCount: BigInt =
          maxHeaders.min(peerConfiguration.fastSyncHostConfiguration.maxBlocksHeadersPerMessage)

        val range = if (reverse) {
          startBlockNumber to (startBlockNumber - (skip + 1) * headersCount + 1) by -(skip + 1)
        } else {
          startBlockNumber to (startBlockNumber + (skip + 1) * headersCount - 1) by (skip + 1)
        }

        // Stop at first missing header (contiguous prefix — matches Besu EthServer.java break behavior)
        val blockHeaders: Seq[BlockHeader] =
          LazyList.from(range).map(a => blockchainReader.getBlockHeaderByNumber(a)).takeWhile(_.isDefined).flatten.toSeq

        log.debug(
          "GetBlockHeaders: start={} maxReq={} → returning {} headers",
          startBlockNumber,
          headersCount,
          blockHeaders.size
        )

        // Return with requestId (ETH68+ always uses request-id; fallback to 0)
        val requestId = requestIdOpt.getOrElse(BigInt(0))
        Some(ETHPackets.BlockHeaders(requestId, blockHeaders))

      case _ =>
        // Starting block not found in DB — respond with empty list (matches Besu EthServer.java:158-160).
        // Never leave the requester waiting with no response; timeouts cause immediate peer drops.
        log.warning(
          "GetBlockHeaders: starting block not found (block={} maxHeaders={} skip={} reverse={}) — responding empty",
          block.fold(_.toString, h => s"0x${h.toArray.map("%02x".format(_)).mkString}"),
          maxHeaders,
          skip,
          reverse
        )
        val requestId = requestIdOpt.getOrElse(BigInt(0))
        Some(ETHPackets.BlockHeaders(requestId, Seq.empty))
    }
  }

}

object BlockchainHostActor {

  def props(
      blockchainReader: BlockchainReader,
      evmCodeStorage: EvmCodeStorage,
      peerConfiguration: PeerConfiguration,
      peerEventBusActor: ActorRef,
      networkPeerManagerActor: ActorRef,
      pendingTransactionsManager: ActorRef
  ): Props =
    Props(
      new BlockchainHostActor(
        blockchainReader,
        evmCodeStorage,
        peerConfiguration,
        peerEventBusActor,
        networkPeerManagerActor,
        pendingTransactionsManager
      )
    )

}
