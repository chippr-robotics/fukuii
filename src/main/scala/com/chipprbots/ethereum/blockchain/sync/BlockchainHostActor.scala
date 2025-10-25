package com.chipprbots.ethereum.blockchain.sync

import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorRef
import akka.actor.Props
import akka.util.ByteString

import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.network.EtcPeerManagerActor
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerEvent.MessageFromPeer
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerSelector
import com.chipprbots.ethereum.network.PeerEventBusActor.Subscribe
import com.chipprbots.ethereum.network.PeerEventBusActor.SubscriptionClassifier.MessageClassifier
import com.chipprbots.ethereum.network.PeerManagerActor.PeerConfiguration
import com.chipprbots.ethereum.network.p2p.Message
import com.chipprbots.ethereum.network.p2p.MessageSerializable
import com.chipprbots.ethereum.network.p2p.messages.Codes
import com.chipprbots.ethereum.network.p2p.messages.ETH62.BlockBodies
import com.chipprbots.ethereum.network.p2p.messages.ETH62.BlockHeaders
import com.chipprbots.ethereum.network.p2p.messages.ETH62.GetBlockBodies
import com.chipprbots.ethereum.network.p2p.messages.ETH62.GetBlockHeaders
import com.chipprbots.ethereum.network.p2p.messages.ETH63.GetNodeData
import com.chipprbots.ethereum.network.p2p.messages.ETH63.GetReceipts
import com.chipprbots.ethereum.network.p2p.messages.ETH63.MptNodeEncoders._
import com.chipprbots.ethereum.network.p2p.messages.ETH63.NodeData
import com.chipprbots.ethereum.network.p2p.messages.ETH63.Receipts

/** BlockchainHost actor is in charge of replying to the peer's requests for blockchain data, which includes both
  * node and block data.
  */
class BlockchainHostActor(
    blockchainReader: BlockchainReader,
    evmCodeStorage: EvmCodeStorage,
    peerConfiguration: PeerConfiguration,
    peerEventBusActor: ActorRef,
    etcPeerManagerActor: ActorRef
) extends Actor
    with ActorLogging {

  private val requestMsgsCodes =
    Set(Codes.GetNodeDataCode, Codes.GetReceiptsCode, Codes.GetBlockBodiesCode, Codes.GetBlockHeadersCode)
  peerEventBusActor ! Subscribe(MessageClassifier(requestMsgsCodes, PeerSelector.AllPeers))

  override def receive: Receive = { case MessageFromPeer(message, peerId) =>
    val responseOpt = handleBlockFastDownload(message).orElse(handleEvmCodeMptFastDownload(message))
    responseOpt.foreach { response =>
      etcPeerManagerActor ! EtcPeerManagerActor.SendMessage(response, peerId)
    }
  }

  /** Handles requests for node data, which includes both mpt nodes and evm code (both requested by hash).
    * Both types of node data are requested by the same GetNodeData message
    *
    * @param message to be processed
    * @return message response if message is a request for node data or None if not
    */
  private def handleEvmCodeMptFastDownload(message: Message): Option[MessageSerializable] = message match {
    case GetNodeData(mptElementsHashes) =>
      val hashesRequested =
        mptElementsHashes.take(peerConfiguration.fastSyncHostConfiguration.maxMptComponentsPerMessage)

      val nodeData: Seq[ByteString] = hashesRequested.flatMap { hash =>
        //Fetch mpt node by hash
        val maybeMptNodeData = blockchainReader.getMptNodeByHash(hash).map(e => e.toBytes: ByteString)

        //If no mpt node was found, fetch evm by hash
        maybeMptNodeData.orElse(evmCodeStorage.get(hash))
      }

      Some(NodeData(nodeData))

    case _ => None
  }

  /** Handles request for block data, which includes receipts, block bodies and headers (all requested by hash)
    *
    * @param message to be processed
    * @return message response if message is a request for block data or None if not
    */
  private def handleBlockFastDownload(message: Message): Option[MessageSerializable] = message match {
    case request: GetReceipts =>
      val receipts = request.blockHashes
        .take(peerConfiguration.fastSyncHostConfiguration.maxReceiptsPerMessage)
        .flatMap(hash => blockchainReader.getReceiptsByHash(hash))

      Some(Receipts(receipts))

    case request: GetBlockBodies =>
      val blockBodies = request.hashes
        .take(peerConfiguration.fastSyncHostConfiguration.maxBlocksBodiesPerMessage)
        .flatMap(hash => blockchainReader.getBlockBodyByHash(hash))

      Some(BlockBodies(blockBodies))

    case request: GetBlockHeaders =>
      val blockNumber = request.block.fold(a => Some(a), b => blockchainReader.getBlockHeaderByHash(b).map(_.number))

      blockNumber match {
        case Some(startBlockNumber) if startBlockNumber >= 0 && request.maxHeaders >= 0 && request.skip >= 0 =>
          val headersCount: BigInt =
            request.maxHeaders.min(peerConfiguration.fastSyncHostConfiguration.maxBlocksHeadersPerMessage)

          val range = if (request.reverse) {
            startBlockNumber to (startBlockNumber - (request.skip + 1) * headersCount + 1) by -(request.skip + 1)
          } else {
            startBlockNumber to (startBlockNumber + (request.skip + 1) * headersCount - 1) by (request.skip + 1)
          }

          val blockHeaders: Seq[BlockHeader] = range.flatMap { a: BigInt => blockchainReader.getBlockHeaderByNumber(a) }

          Some(BlockHeaders(blockHeaders))

        case _ =>
          log.warning("got request for block headers with invalid block hash/number: {}", request)
          None
      }

    case _ => None

  }

}

object BlockchainHostActor {

  def props(
      blockchainReader: BlockchainReader,
      evmCodeStorage: EvmCodeStorage,
      peerConfiguration: PeerConfiguration,
      peerEventBusActor: ActorRef,
      etcPeerManagerActor: ActorRef
  ): Props =
    Props(
      new BlockchainHostActor(
        blockchainReader,
        evmCodeStorage,
        peerConfiguration,
        peerEventBusActor,
        etcPeerManagerActor
      )
    )

}
