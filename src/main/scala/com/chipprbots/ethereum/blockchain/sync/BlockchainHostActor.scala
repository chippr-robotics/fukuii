package com.chipprbots.ethereum.blockchain.sync

import org.apache.pekko.actor.typed
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.Scheduler
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.util.ByteString
import org.apache.pekko.util.Timeout

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*

import com.chipprbots.ethereum.blockchain.sync.codec.MptNodeCodecs.*
import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.domain.BlockHash
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.domain.Receipt
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.PeerEventBusActor.Command as PeerEventBusCommand
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerEvent
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerEvent.MessageFromPeer
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerSelector
import com.chipprbots.ethereum.network.PeerEventBusActor.SubscribeCmd
import com.chipprbots.ethereum.network.PeerEventBusActor.SubscriptionClassifier.MessageClassifier
import com.chipprbots.ethereum.network.PeerManagerActor.PeerConfiguration
import com.chipprbots.ethereum.network.p2p.Message
import com.chipprbots.ethereum.network.p2p.MessageSerializable
import com.chipprbots.ethereum.network.p2p.messages.Codes
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.GetNodeData
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.NodeData
import com.chipprbots.ethereum.rlp.RLPEncodeable
import com.chipprbots.ethereum.rlp.RLPList
import com.chipprbots.ethereum.rlp.RLPValue
import com.chipprbots.ethereum.rlp.encode
import com.chipprbots.ethereum.transactions.PendingTransactionsManager
import com.chipprbots.ethereum.transactions.PendingTransactionsManager.PendingTransactionsResponse
import com.chipprbots.ethereum.utils.ByteStringUtils

/** BlockchainHost actor is in charge of replying to the peer's requests for blockchain data, which includes both node
  * and block data.
  */
object BlockchainHostActor:

  sealed trait Command
  final private[sync] case class PeerEventReceived(ev: PeerEvent) extends Command

  private val requestMsgsCodes =
    Set(
      Codes.GetNodeDataCode,
      Codes.GetReceiptsCode,
      Codes.GetBlockBodiesCode,
      Codes.GetBlockHeadersCode,
      Codes.GetPooledTransactionsCode,
      Codes.GetBlockAccessListsCode,
      Codes.GetCellsCode
    )

  def apply(
      blockchainReader: BlockchainReader,
      evmCodeStorage: EvmCodeStorage,
      peerConfiguration: PeerConfiguration,
      peerEventBusActor: typed.ActorRef[PeerEventBusCommand],
      networkPeerManagerActor: typed.ActorRef[NetworkPeerManagerActor.Command],
      pendingTransactionsManager: typed.ActorRef[
        com.chipprbots.ethereum.transactions.PendingTransactionsManager.Command
      ]
  ): Behavior[Command] = Behaviors.setup { context =>
    given ec: ExecutionContext = context.executionContext
    given timeout: Timeout = Timeout(3.seconds)
    given scheduler: Scheduler = context.system.scheduler

    // Typed subscriber ref: PEA's Classic shell captures sender() as the subscriber. The adapter lifts
    // delivered PeerEvents into this behavior's Command, then routes them back through the Classic event bus.
    val peerEventAdapter: typed.ActorRef[PeerEvent] =
      context.messageAdapter[PeerEvent](PeerEventReceived(_))

    peerEventBusActor ! SubscribeCmd(MessageClassifier(requestMsgsCodes, PeerSelector.AllPeers), peerEventAdapter)

    def handleGetPooledTransactions(
        txHashes: Seq[ByteString],
        requestIdOpt: Option[BigInt],
        peerId: com.chipprbots.ethereum.network.PeerId
    ): Unit =
      val hashSet = txHashes.toSet
      import org.apache.pekko.actor.typed.scaladsl.AskPattern.*
      pendingTransactionsManager
        .ask[PendingTransactionsResponse](ref => PendingTransactionsManager.GetPendingTransactionsReq(ref))
        .foreach { response =>
          val matchingTxs = response.pendingTransactions
            .map(_.stx.tx)
            .filter(tx => hashSet.contains(tx.hash.value))
          // Include blob tx sidecar bytes for EIP-4844 network wrapping in PooledTransactions
          val matchingBlobBytes = response.blobTxNetworkBytes.filter { case (hash, _) => hashSet.contains(hash) }
          val responseMsg: MessageSerializable = requestIdOpt match
            case Some(requestId) =>
              ETHPackets.PooledTransactions(requestId, matchingTxs, blobTxRawBytes = matchingBlobBytes)
            case None => ETHPackets.PooledTransactions(0, matchingTxs) // requestId=0 for no-requestId case
          networkPeerManagerActor ! NetworkPeerManagerActor.SendMessageCmd(responseMsg, peerId)
        }

    /** Handles requests for node data, which includes both mpt nodes and evm code (both requested by hash). Both types
      * of node data are requested by the same GetNodeData message
      *
      * @param message
      *   to be processed
      * @return
      *   message response if message is a request for node data or None if not
      */
    def handleEvmCodeMptFastDownload(message: Message): Option[MessageSerializable] = message match
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

    /** Receipts for the requested blocks, stopping at the first block we lack, as go-ethereum does (`if results == nil
      * { break }`). A reply is matched to the request by position, so skipping a block would shift every later block's
      * receipts onto the wrong hash in the requester's hands.
      */
    def receiptsPrefix(blockHashes: Seq[ByteString]): Seq[Seq[Receipt]] =
      blockHashes.iterator
        .take(peerConfiguration.fastSyncHostConfiguration.maxReceiptsPerMessage)
        .map(hash => blockchainReader.getReceiptsByHash(BlockHash(hash)))
        .takeWhile(_.isDefined)
        .flatten
        .toSeq

    /** Handles request for block data, which includes receipts, block bodies and headers (all requested by hash)
      *
      * @param message
      *   to be processed
      * @return
      *   message response if message is a request for block data or None if not
      */
    def handleBlockFastDownload(message: Message): Option[MessageSerializable] = message match
      // ETH68 GetReceipts — bloom-inclusive response
      case ETHPackets.GetReceipts(requestId, blockHashes) =>
        import ETHPackets.ReceiptBloomEnc
        val receipts = receiptsPrefix(blockHashes)
        val receiptsRLP = RLPList(receipts.map(rs => RLPList(rs.map(_.toRLPEncodable)*))*)
        context.log.info("HOST_RECEIPTS_ETH68: requestId={} blocks={}", requestId, receipts.size)
        Some(ETHPackets.Receipts68(requestId, receiptsRLP))

      // ETH69 GetReceipts — bloom-ABSENT response per EIP-7642
      case ETHPackets.GetReceipts69(requestId, blockHashes) =>
        import ETHPackets.ReceiptBloomFreeEnc
        val receipts = receiptsPrefix(blockHashes)
        val receiptsRLP = RLPList(receipts.map(rs => RLPList(rs.map(_.toRLPEncodable)*))*)
        context.log.info(
          "HOST_RECEIPTS_ETH69: requestId={} blocks={} (bloom-absent, EIP-7642)",
          requestId,
          receipts.size
        )
        Some(ETHPackets.Receipts69(requestId, receiptsRLP))

      // ETH70 GetReceipts — partial receipt delivery per EIP-7706, mirroring go-ethereum's
      // serviceGetReceiptsQuery70/blockReceiptsToNetwork (eth/protocols/eth/handlers.go, receipt.go).
      // firstBlockReceiptIndex: skip already-received receipts in the first requested block (client resume).
      //
      // Response budget is maxPacketSize (10 MiB — the devp2p wire packet limit go-ethereum's eth/handler.go
      // documents as "commonly enforced by clients"), NOT the 2 MiB softResponseLimit used for
      // headers/bodies/ETH69 receipts. EIP-7706's whole point is letting a receipts response approach the wire
      // limit via chunked delivery instead of being held to the conservative single-shot target — the smaller
      // constant chunked hive's >10 MiB TestGetLargeReceipts block far more finely than go-ethereum would. (That
      // test's empty-trie receipt root was a different defect: the shape of each receipt, see ReceiptBloomFreeEnc.)
      //
      // If the FIRST still-needed receipt of a block does not fit at all (`fittingEncs` empty while there was at
      // least one receipt left to serve), go-ethereum omits that block from the response entirely — never an
      // empty placeholder — and does not flag the response incomplete (`blockReceiptsToNetwork` returns
      // `(nil, false, nil)`; the caller `break`s without appending). Once at least one receipt of a block HAS
      // been included, hitting the limit on a later one truncates normally: that block's partial list is
      // included and `lastBlockIncomplete=true`. A block already fully resumed (`toServe` empty) still gets an
      // explicit empty list and does not stop serving — that is a confirmation, not a truncation.
      case ETHPackets.GetReceipts70(requestId, firstBlockReceiptIndex, blockHashes) =>
        import ETHPackets.ReceiptBloomFreeEnc
        val MaxResponseBytes = 10L * 1024 * 1024 // maxPacketSize per go-ethereum eth/handler.go

        // State: (accumulated per-block RLP lists, lastBlockIncomplete, cumulative bytes, done flag)
        val (blockReceiptLists, lastBlockIncomplete, _, _) =
          blockHashes
            .take(peerConfiguration.fastSyncHostConfiguration.maxReceiptsPerMessage)
            .zipWithIndex
            .foldLeft((Vector.empty[RLPList], false, 0L, false)) {
              case (acc @ (_, _, _, true), _) => acc // already stopped serving — skip remaining
              case ((lists, _, cumBytes, false), (hash, blockIdx)) =>
                blockchainReader.getReceiptsByHash(BlockHash(hash)) match
                  case None => (lists, false, cumBytes, true) // unknown block — stop, like the nil-results break below
                  case Some(receipts) =>
                    val toServe = if blockIdx == 0 then receipts.drop(firstBlockReceiptIndex.toInt) else receipts
                    // Encode per receipt, stopping once the NEXT one would exceed the budget.
                    val (fittingEncs, truncatedMidBlock, newBytes) =
                      toServe.foldLeft((Vector.empty[RLPEncodeable], false, cumBytes)) {
                        case (acc2 @ (_, true, _), _) => acc2
                        case ((encs, false, cb), receipt) =>
                          val enc = receipt.toRLPEncodable
                          val encBytes = encode(enc).length.toLong
                          if cb + encBytes > MaxResponseBytes then (encs, true, cb)
                          else (encs :+ enc, false, cb + encBytes)
                      }
                    if fittingEncs.isEmpty && toServe.nonEmpty then
                      // Nothing fit, even for the first still-needed receipt: omit this block and stop —
                      // matches go-ethereum's (nil, false, nil) / "if results == nil { break }".
                      (lists, false, cumBytes, true)
                    else
                      val blockRLP = RLPList(fittingEncs*)
                      (lists :+ blockRLP, truncatedMidBlock, newBytes, truncatedMidBlock)
            }

        val receiptsRLP = RLPList(blockReceiptLists*)
        context.log.info(
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
          .flatMap(hash => blockchainReader.getBlockBodyByHash(BlockHash(hash)))
        context.log.debug(
          "HOST_BLOCK_BODIES_ETH68: requestId={} requested={} returning={}",
          requestId,
          hashes.size,
          blockBodies.size
        )
        Some(ETHPackets.BlockBodies(requestId, blockBodies))

      // ETH68 GetBlockHeaders (via ETHPackets)
      case ETHPackets.GetBlockHeaders(requestId, block, maxHeaders, skip, reverse) =>
        handleGetBlockHeadersRequest(block, maxHeaders, skip, reverse, Some(requestId))

      // ETH71 GetBlockAccessLists (EIP-8159). fukuii has no EIP-7928 BAL storage, so every
      // requested hash gets the empty-string sentinel — an honest "unavailable", not fabricated
      // data, and the wire-correct answer regardless: an empty BAL response is spec-valid even for
      // a node that HAS the block, as long as it's not silently claiming to have data it doesn't.
      // Order is preserved 1:1 with the request; see ETHPackets.scala's GetBlockAccessLists doc.
      case ETHPackets.GetBlockAccessLists(requestId, blockHashes) =>
        val entries = blockHashes.map(_ => RLPValue(Array.emptyByteArray))
        context.log.debug(
          "HOST_BLOCK_ACCESS_LISTS: requestId={} requested={} (all-empty, no BAL storage)",
          requestId,
          blockHashes.size
        )
        Some(ETHPackets.BlockAccessLists(requestId, entries))

      // ETH72 GetCells (EIP-8070). fukuii has no PeerDAS cell/blob storage, so it answers with the
      // fully-empty response (zero hashes, zero cells) rather than invent cell data — the same
      // "honest absence" go-ethereum's own answerGetCells gives for any hash it has no blob data
      // for. The requested mask is echoed back unchanged, matching go-ethereum's ReplyCells.
      case ETHPackets.GetCells(requestId, hashes, mask) =>
        context.log.debug(
          "HOST_CELLS: requestId={} requested={} (empty, no cell storage)",
          requestId,
          hashes.size
        )
        Some(ETHPackets.Cells(requestId, Seq.empty, Seq.empty, mask))

      case _ => None

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
    def handleGetBlockHeadersRequest(
        block: Either[BigInt, ByteString],
        maxHeaders: BigInt,
        skip: BigInt,
        reverse: Boolean,
        requestIdOpt: Option[BigInt]
    ): Option[MessageSerializable] =
      val blockNumber =
        block.fold(a => Some(a), b => blockchainReader.getBlockHeaderByHash(BlockHash(b)).map(_.number.value))

      blockNumber match
        case Some(startBlockNumber) if startBlockNumber >= 0 && maxHeaders >= 0 && skip >= 0 =>
          val headersCount: BigInt =
            maxHeaders.min(peerConfiguration.fastSyncHostConfiguration.maxBlocksHeadersPerMessage)

          val range =
            if reverse then startBlockNumber to (startBlockNumber - (skip + 1) * headersCount + 1) by -(skip + 1)
            else startBlockNumber to (startBlockNumber + (skip + 1) * headersCount - 1) by (skip + 1)

          // Stop at first missing header (contiguous prefix — matches Besu EthServer.java break behavior)
          val blockHeaders: Seq[BlockHeader] =
            LazyList
              .from(range)
              .map(a => blockchainReader.getBlockHeaderByNumber(a))
              .takeWhile(_.isDefined)
              .flatten
              .toSeq

          context.log.debug(
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
          context.log.warn(
            "GetBlockHeaders: starting block not found (block={} maxHeaders={} skip={} reverse={}) — responding empty",
            block.fold(_.toString, h => s"0x${h.toArray.map("%02x".format(_)).mkString}"),
            maxHeaders,
            skip,
            reverse
          )
          val requestId = requestIdOpt.getOrElse(BigInt(0))
          Some(ETHPackets.BlockHeaders(requestId, Seq.empty))

    Behaviors.receiveMessage {
      case PeerEventReceived(MessageFromPeer(message, peerId)) =>
        // Handle GetPooledTransactions asynchronously (requires ask to PendingTransactionsManager)
        message match
          case ETHPackets.GetPooledTransactions(requestId, txHashes) =>
            handleGetPooledTransactions(txHashes, Some(requestId), peerId)
          case _ =>
            val responseOpt = handleBlockFastDownload(message).orElse(handleEvmCodeMptFastDownload(message))
            responseOpt.foreach { response =>
              networkPeerManagerActor ! NetworkPeerManagerActor.SendMessageCmd(response, peerId)
              // BLOCK-SERVE: INFO log so we can see which peers are requesting our chain
              // data — useful for detecting when we're serving from an orphan fork.
              val reqLabel = message match
                case ETHPackets.GetBlockHeaders(_, block, max, _, _) =>
                  s"GetBlockHeaders(start=${block.fold(_.toString, h => ByteStringUtils.hash2string(h).take(8))} max=$max)"
                case ETHPackets.GetBlockBodies(_, hashes) => s"GetBlockBodies(${hashes.size})"
                case ETHPackets.GetReceipts(_, hashes)    => s"GetReceipts(${hashes.size})"
                case ETHPackets.GetReceipts69(_, hashes)  => s"GetReceipts69(${hashes.size})"
                case ETHPackets.GetReceipts70(_, firstIdx, hashes) =>
                  s"GetReceipts70(${hashes.size} firstIdx=$firstIdx)"
                case other => other.getClass.getSimpleName
              context.log.debug("BLOCK-SERVE: peer={} req={}", peerId, reqLabel)
            }
        Behaviors.same

      case PeerEventReceived(_) =>
        Behaviors.same
    }
  }
