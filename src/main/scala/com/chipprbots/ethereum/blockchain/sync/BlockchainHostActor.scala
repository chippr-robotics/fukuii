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
import com.chipprbots.ethereum.utils.ByteStringUtils
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
import com.chipprbots.ethereum.rlp.RLPEncodeable
import com.chipprbots.ethereum.rlp.RLPList
import com.chipprbots.ethereum.rlp.encode
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
          // BLOCK-SERVE: INFO log so we can see which peers are requesting our chain
          // data — useful for detecting when we're serving from an orphan fork.
          val reqLabel = message match {
            case ETHPackets.GetBlockHeaders(_, block, max, _, _) =>
              s"GetBlockHeaders(start=${block.fold(_.toString, h => ByteStringUtils.hash2string(h).take(8))} max=$max)"
            case ETHPackets.GetBlockBodies(_, hashes)          => s"GetBlockBodies(${hashes.size})"
            case ETHPackets.GetReceipts(_, hashes)             => s"GetReceipts(${hashes.size})"
            case ETHPackets.GetReceipts69(_, hashes)           => s"GetReceipts69(${hashes.size})"
            case ETHPackets.GetReceipts70(_, firstIdx, hashes) => s"GetReceipts70(${hashes.size} firstIdx=$firstIdx)"
            case other                                         => other.getClass.getSimpleName
          }
          log.debug("BLOCK-SERVE: peer={} req={}", peerId, reqLabel)
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

    // ETH70 GetReceipts — partial receipt delivery per EIP-7706.
    // firstBlockReceiptIndex: skip already-received receipts in the first block (client resume).
    // Applies 2 MiB soft limit per-receipt; sets lastBlockIncomplete=true when a block is truncated.
    case ETHPackets.GetReceipts70(requestId, firstBlockReceiptIndex, blockHashes) =>
      import ETHPackets.ReceiptBloomFreeEnc
      val MaxResponseBytes = 2L * 1024 * 1024 // 2 MiB soft limit per EIP-7706

      // State: (accumulated per-block RLP lists, lastBlockIncomplete, cumulative bytes, done flag)
      val (blockReceiptLists, lastBlockIncomplete, _, _) =
        blockHashes
          .take(peerConfiguration.fastSyncHostConfiguration.maxReceiptsPerMessage)
          .zipWithIndex
          .foldLeft((Vector.empty[RLPList], false, 0L, false)) {
            case (acc @ (_, _, _, true), _) => acc // already truncated mid-block — skip remaining
            case ((lists, _, cumBytes, false), (hash, blockIdx)) =>
              blockchainReader.getReceiptsByHash(hash) match {
                case None => (lists, false, cumBytes, false) // unknown block — skip silently
                case Some(receipts) =>
                  val toServe = if (blockIdx == 0) receipts.drop(firstBlockReceiptIndex.toInt) else receipts
                  // Encode per receipt, truncate at 2 MiB, track whether we finished the block
                  val (fittingEncs, incomplete, newBytes) =
                    toServe.foldLeft((Vector.empty[RLPEncodeable], false, cumBytes)) {
                      case (acc2 @ (_, true, _), _) => acc2
                      case ((encs, false, cb), receipt) =>
                        val enc = receipt.toRLPEncodable
                        val encBytes = encode(enc).length.toLong
                        if (cb + encBytes > MaxResponseBytes) (encs, true, cb)
                        else (encs :+ enc, false, cb + encBytes)
                    }
                  val blockRLP = RLPList(fittingEncs.map(e => e): _*)
                  (lists :+ blockRLP, incomplete, newBytes, incomplete)
              }
          }

      val receiptsRLP = RLPList(blockReceiptLists: _*)
      log.info(
        "HOST_RECEIPTS_ETH70: requestId={} blocks={} incomplete={}",
        requestId,
        blockReceiptLists.size,
        lastBlockIncomplete
      )
      Some(ETHPackets.Receipts70(requestId, lastBlockIncomplete, receiptsRLP))

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
