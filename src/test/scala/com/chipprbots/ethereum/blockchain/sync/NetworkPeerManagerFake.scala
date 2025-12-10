package com.chipprbots.ethereum.blockchain.sync
import org.apache.pekko.actor.ActorRef
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.testkit.TestActor.AutoPilot
import org.apache.pekko.testkit.TestProbe
import org.apache.pekko.util.ByteString

import cats.effect.Deferred
import cats.effect.IO
import cats.effect.unsafe.IORuntime

import fs2.Stream
import fs2.concurrent.Topic

import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.BlockBody
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.PeerInfo
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.SendMessage
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerEvent.MessageFromPeer
import com.chipprbots.ethereum.network.p2p.messages.ETH62.BlockBodies
import com.chipprbots.ethereum.network.p2p.messages.ETH62.BlockHeaders
import com.chipprbots.ethereum.network.p2p.messages.ETH62.GetBlockBodies
import com.chipprbots.ethereum.network.p2p.messages.ETH62.GetBlockHeaders
import com.chipprbots.ethereum.network.p2p.messages.ETH63.GetNodeData
import com.chipprbots.ethereum.network.p2p.messages.ETH63.GetReceipts
import com.chipprbots.ethereum.network.p2p.messages.ETH63.NodeData
import com.chipprbots.ethereum.network.p2p.messages.ETH63.Receipts
import com.chipprbots.ethereum.network.p2p.messages.ETH66
import com.chipprbots.ethereum.utils.Config.SyncConfig
import com.chipprbots.ethereum.rlp.RLPList
import com.chipprbots.ethereum.rlp.RLPValue

class NetworkPeerManagerFake(
    syncConfig: SyncConfig,
    peers: Map[Peer, PeerInfo],
    blocks: List[Block],
    getMptNodes: List[ByteString] => List[ByteString]
)(implicit system: ActorSystem, ioRuntime: IORuntime) {
  private val responsesTopicIO: IO[Topic[IO, MessageFromPeer]] = Topic[IO, MessageFromPeer]
  private val requestsTopicIO: IO[Topic[IO, SendMessage]] = Topic[IO, SendMessage]
  private val responsesTopic: Topic[IO, MessageFromPeer] = responsesTopicIO.unsafeRunSync()
  private val requestsTopic: Topic[IO, SendMessage] = requestsTopicIO.unsafeRunSync()
  private val peersConnectedDeferred = Deferred.unsafe[IO, Unit]

  val probe: TestProbe = TestProbe("network_peer_manager")
  val autoPilot =
    new NetworkPeerManagerFake.NetworkPeerManagerAutoPilot(
      requestsTopic,
      responsesTopic,
      peersConnectedDeferred,
      peers,
      blocks,
      getMptNodes
    )
  probe.setAutoPilot(autoPilot)

  def ref = probe.ref

  val requests: Stream[IO, SendMessage] = requestsTopic.subscribe(100)
  val responses: Stream[IO, MessageFromPeer] = responsesTopic.subscribe(100)
  val onPeersConnected: IO[Unit] = peersConnectedDeferred.get
  val pivotBlockSelected: Stream[IO, BlockHeader] = responses
    .collect {
      case MessageFromPeer(BlockHeaders(Seq(header)), peer) => (header, peer)
      case MessageFromPeer(ETH66.BlockHeaders(_, Seq(header)), peer) => (header, peer)
    }
    .chunkN(peers.size)
    .flatMap { headersFromPeersChunk =>
      val headersFromPeers = headersFromPeersChunk.toList
      val (headers, respondedPeers) = headersFromPeers.unzip

      if (headers.distinct.size == 1 && respondedPeers.toSet == peers.keySet.map(_.id)) {
        Stream.emit(headers.head)
      } else {
        Stream.empty
      }
    }

  val fetchedHeaders: Stream[IO, Seq[BlockHeader]] = responses.collect {
    case MessageFromPeer(BlockHeaders(headers), _)
        if headers.size == syncConfig.blockHeadersPerRequest =>
      headers
    case MessageFromPeer(ETH66.BlockHeaders(_, headers), _)
        if headers.size == syncConfig.blockHeadersPerRequest =>
      headers
  }
  val fetchedBodies: Stream[IO, Seq[BlockBody]] = responses.collect {
    case MessageFromPeer(BlockBodies(bodies), _)         => bodies
    case MessageFromPeer(ETH66.BlockBodies(_, bodies), _) => bodies
  }
  val requestedReceipts: Stream[IO, Seq[ByteString]] = requests.collect(
    Function.unlift(msg =>
      msg.message.underlyingMsg match {
        case GetReceipts(hashes)         => Some(hashes)
        case ETH66.GetReceipts(_, hashes) => Some(hashes)
        case _                           => None
      }
    )
  )
  val fetchedBlocks: Stream[IO, List[Block]] = fetchedBodies
    .scan[(List[Block], List[Block])]((Nil, blocks)) { case ((_, remainingBlocks), bodies) =>
      remainingBlocks.splitAt(bodies.size)
    }
    .map(_._1)
    .zip(requestedReceipts)
    .map { case (blocks, _) => blocks } // a big simplification, but should be sufficient here

  private def rlpListToByteStrings(list: RLPList): Seq[ByteString] =
    list.items.collect { case RLPValue(bytes) => ByteString(bytes) }

  val fetchedState: Stream[IO, Seq[ByteString]] = responses.collect {
    case MessageFromPeer(NodeData(values), _)             => values
    case MessageFromPeer(ETH66.NodeData(_, values), _) => rlpListToByteStrings(values)
  }

}
object NetworkPeerManagerFake {
  class NetworkPeerManagerAutoPilot(
      requests: Topic[IO, SendMessage],
      responses: Topic[IO, MessageFromPeer],
      peersConnected: Deferred[IO, Unit],
      peers: Map[Peer, PeerInfo],
      blocks: List[Block],
      getMptNodes: List[ByteString] => List[ByteString]
  )(implicit ioRuntime: IORuntime)
      extends AutoPilot {
    def run(sender: ActorRef, msg: Any): NetworkPeerManagerAutoPilot = {
      msg match {
        case NetworkPeerManagerActor.GetHandshakedPeers =>
          sender ! NetworkPeerManagerActor.HandshakedPeers(peers)
          peersConnected.complete(()).handleError(_ => ()).unsafeRunSync()
        case sendMsg @ NetworkPeerManagerActor.SendMessage(rawMsg, peerId) =>
          requests.publish1(sendMsg).unsafeRunSync()
          val response = rawMsg.underlyingMsg match {
            case GetBlockHeaders(startingBlock, maxHeaders, skip, reverse) =>
              BlockHeaders(headersFor(startingBlock, maxHeaders, skip, reverse))

            case ETH66.GetBlockHeaders(requestId, startingBlock, maxHeaders, skip, reverse) =>
              ETH66.BlockHeaders(requestId, headersFor(startingBlock, maxHeaders, skip, reverse))

            case GetBlockBodies(hashes) =>
              BlockBodies(bodiesFor(hashes))

            case ETH66.GetBlockBodies(requestId, hashes) =>
              ETH66.BlockBodies(requestId, bodiesFor(hashes))

            case GetReceipts(blockHashes) =>
              Receipts(blockHashes.map(_ => Nil))

            case ETH66.GetReceipts(requestId, blockHashes) =>
              ETH66.Receipts(requestId, emptyReceiptsRlp(blockHashes.size))

            case GetNodeData(mptElementsHashes) =>
              NodeData(getMptNodes(mptElementsHashes.toList))

            case ETH66.GetNodeData(requestId, mptElementsHashes) =>
              ETH66.NodeData(requestId, nodeDataRlp(getMptNodes(mptElementsHashes.toList)))
          }
          val theResponse = MessageFromPeer(response, peerId)
          sender ! theResponse
          responses.publish1(theResponse).unsafeRunSync()
      }
      this
    }

    private def headersFor(
        startingBlock: Either[BigInt, ByteString],
        maxHeaders: BigInt,
        skip: BigInt,
        reverse: Boolean
    ): Seq[BlockHeader] = {
      val startIndex = blocks.indexWhere(blockMatchesStart(_, startingBlock))
      if (startIndex < 0) Seq.empty
      else {
        val orderedBlocks = if (reverse) blocks.take(startIndex + 1).reverse else blocks.drop(startIndex)
        val step = (skip + 1).toInt
        orderedBlocks
          .zipWithIndex
          .collect { case (block, index) if index % step == 0 => block }
          .take(maxHeaders.toInt)
          .map(_.header)
      }
    }

    private def bodiesFor(hashes: Seq[ByteString]): Seq[BlockBody] =
      hashes.flatMap(hash => blocks.find(_.hash == hash)).map(_.body)

    private def emptyReceiptsRlp(count: Int): RLPList =
      RLPList(List.fill(count)(RLPList()): _*)

    private def nodeDataRlp(values: Seq[ByteString]): RLPList =
      RLPList(values.map(bs => RLPValue(bs.toArray[Byte])): _*)

    def blockMatchesStart(block: Block, startingBlock: Either[BigInt, ByteString]): Boolean =
      startingBlock.fold(nr => block.number == nr, hash => block.hash == hash)
  }
}
