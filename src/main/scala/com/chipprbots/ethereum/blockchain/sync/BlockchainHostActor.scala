package com.chipprbots.ethereum.blockchain.sync

import org.apache.pekko.actor.Actor
import org.apache.pekko.actor.ActorLogging
import org.apache.pekko.actor.ActorRef
import org.apache.pekko.actor.Props
import org.apache.pekko.util.ByteString

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
import com.chipprbots.ethereum.network.p2p.messages.ETH62
import com.chipprbots.ethereum.network.p2p.messages.ETH62.BlockBodies
import com.chipprbots.ethereum.network.p2p.messages.ETH62.BlockHeaders
import com.chipprbots.ethereum.network.p2p.messages.ETH62.GetBlockBodies
import com.chipprbots.ethereum.network.p2p.messages.ETH62.GetBlockHeaders
import com.chipprbots.ethereum.network.p2p.messages.ETH63.GetNodeData
import com.chipprbots.ethereum.network.p2p.messages.ETH63.GetReceipts
import com.chipprbots.ethereum.network.p2p.messages.ETH63.MptNodeEncoders._
import com.chipprbots.ethereum.network.p2p.messages.ETH63.NodeData
import com.chipprbots.ethereum.network.p2p.messages.ETH63.ReceiptImplicits._
import com.chipprbots.ethereum.network.p2p.messages.ETH63.Receipts
import com.chipprbots.ethereum.network.p2p.messages.ETH66
import com.chipprbots.ethereum.rlp.RLPList

/** BlockchainHost actor is in charge of replying to the peer's requests for blockchain data, which includes both node
  * and block data.
  */
class BlockchainHostActor(
    blockchainReader: BlockchainReader,
    evmCodeStorage: EvmCodeStorage,
    peerConfiguration: PeerConfiguration,
    peerEventBusActor: ActorRef,
    networkPeerManagerActor: ActorRef
) extends Actor
    with ActorLogging {

  private val requestMsgsCodes =
    Set(Codes.GetNodeDataCode, Codes.GetReceiptsCode, Codes.GetBlockBodiesCode, Codes.GetBlockHeadersCode)
  peerEventBusActor ! Subscribe(MessageClassifier(requestMsgsCodes, PeerSelector.AllPeers))

  override def receive: Receive = { case MessageFromPeer(message, peerId) =>
    val responseOpt = handleBlockFastDownload(message).orElse(handleEvmCodeMptFastDownload(message))
    responseOpt.foreach { response =>
      networkPeerManagerActor ! NetworkPeerManagerActor.SendMessage(response, peerId)
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
    // ETH63 GetReceipts
    case request: GetReceipts =>
      val receipts = request.blockHashes
        .take(peerConfiguration.fastSyncHostConfiguration.maxReceiptsPerMessage)
        .flatMap(hash => blockchainReader.getReceiptsByHash(hash))

      Some(Receipts(receipts))

    // ETH66+ GetReceipts with request ID
    case ETH66.GetReceipts(requestId, blockHashes) =>
      val receipts = blockHashes
        .take(peerConfiguration.fastSyncHostConfiguration.maxReceiptsPerMessage)
        .flatMap(hash => blockchainReader.getReceiptsByHash(hash))

      // Convert receipts to RLPList for ETH66 format
      val receiptsRLP = RLPList(receipts.map(rs => RLPList(rs.map(_.toRLPEncodable): _*)): _*)
      Some(ETH66.Receipts(requestId, receiptsRLP))

    // ETH62 GetBlockBodies
    case request: GetBlockBodies =>
      val blockBodies = request.hashes
        .take(peerConfiguration.fastSyncHostConfiguration.maxBlocksBodiesPerMessage)
        .flatMap(hash => blockchainReader.getBlockBodyByHash(hash))

      Some(BlockBodies(blockBodies))

    // ETH66+ GetBlockBodies with request ID
    case ETH66.GetBlockBodies(requestId, hashes) =>
      val blockBodies = hashes
        .take(peerConfiguration.fastSyncHostConfiguration.maxBlocksBodiesPerMessage)
        .flatMap(hash => blockchainReader.getBlockBodyByHash(hash))

      Some(ETH66.BlockBodies(requestId, blockBodies))

    // ETH62 GetBlockHeaders
    case request: GetBlockHeaders =>
      handleGetBlockHeadersRequest(request.block, request.maxHeaders, request.skip, request.reverse, None)

    // ETH66+ GetBlockHeaders with request ID
    case ETH66.GetBlockHeaders(requestId, block, maxHeaders, skip, reverse) =>
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

        val blockHeaders: Seq[BlockHeader] = range.flatMap { (a: BigInt) =>
          blockchainReader.getBlockHeaderByNumber(a)
        }

        // Return ETH66+ format if requestId is provided, otherwise ETH62 format
        requestIdOpt match {
          case Some(requestId) => Some(ETH66.BlockHeaders(requestId, blockHeaders))
          case None            => Some(ETH62.BlockHeaders(blockHeaders))
        }

      case _ =>
        log.warning(
          "got request for block headers with invalid block hash/number: block={}, maxHeaders={}, skip={}, reverse={}",
          block.fold(_.toString, h => s"0x${h.toArray.map("%02x".format(_)).mkString}"),
          maxHeaders,
          skip,
          reverse
        )
        None
    }
  }

}

object BlockchainHostActor {

  def props(
      blockchainReader: BlockchainReader,
      evmCodeStorage: EvmCodeStorage,
      peerConfiguration: PeerConfiguration,
      peerEventBusActor: ActorRef,
      networkPeerManagerActor: ActorRef
  ): Props =
    Props(
      new BlockchainHostActor(
        blockchainReader,
        evmCodeStorage,
        peerConfiguration,
        peerEventBusActor,
        networkPeerManagerActor
      )
    )

}
