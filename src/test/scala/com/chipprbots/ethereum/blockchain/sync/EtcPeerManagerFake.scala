package com.chipprbots.ethereum.blockchain.sync
import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.testkit.TestActor.AutoPilot
import akka.testkit.TestProbe
import akka.util.ByteString

import cats.effect.Deferred
import cats.effect.IO
import cats.effect.unsafe.IORuntime

import fs2.Stream
import fs2.concurrent.Topic

import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.BlockBody
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.network.EtcPeerManagerActor
import com.chipprbots.ethereum.network.EtcPeerManagerActor.PeerInfo
import com.chipprbots.ethereum.network.EtcPeerManagerActor.SendMessage
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
import com.chipprbots.ethereum.utils.Config.SyncConfig

class EtcPeerManagerFake(
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

  val probe: TestProbe = TestProbe("etc_peer_manager")
  val autoPilot =
    new EtcPeerManagerFake.EtcPeerManagerAutoPilot(
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
    .collect { case MessageFromPeer(BlockHeaders(Seq(header)), peer) =>
      (header, peer)
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

  val fetchedHeaders: Stream[IO, Seq[BlockHeader]] = responses
    .collect {
      case MessageFromPeer(BlockHeaders(headers), _) if headers.size == syncConfig.blockHeadersPerRequest => headers
    }
  val fetchedBodies: Stream[IO, Seq[BlockBody]] = responses
    .collect { case MessageFromPeer(BlockBodies(bodies), _) =>
      bodies
    }
  val requestedReceipts: Stream[IO, Seq[ByteString]] = requests.collect(
    Function.unlift(msg =>
      msg.message.underlyingMsg match {
        case GetReceipts(hashes) => Some(hashes)
        case _                   => None
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

  val fetchedState: Stream[IO, Seq[ByteString]] = responses.collect { case MessageFromPeer(NodeData(values), _) =>
    values
  }

}
object EtcPeerManagerFake {
  class EtcPeerManagerAutoPilot(
      requests: Topic[IO, SendMessage],
      responses: Topic[IO, MessageFromPeer],
      peersConnected: Deferred[IO, Unit],
      peers: Map[Peer, PeerInfo],
      blocks: List[Block],
      getMptNodes: List[ByteString] => List[ByteString]
  )(implicit ioRuntime: IORuntime)
      extends AutoPilot {
    def run(sender: ActorRef, msg: Any): EtcPeerManagerAutoPilot = {
      msg match {
        case EtcPeerManagerActor.GetHandshakedPeers =>
          sender ! EtcPeerManagerActor.HandshakedPeers(peers)
          peersConnected.complete(()).handleError(_ => ()).unsafeRunSync()
        case sendMsg @ EtcPeerManagerActor.SendMessage(rawMsg, peerId) =>
          requests.publish1(sendMsg).unsafeRunSync()
          val response = rawMsg.underlyingMsg match {
            case GetBlockHeaders(startingBlock, maxHeaders, skip, false) =>
              val headers = blocks.tails
                .find(_.headOption.exists(blockMatchesStart(_, startingBlock)))
                .toList
                .flatten
                .zipWithIndex
                .collect { case (block, index) if index % (skip + 1) == 0 => block }
                .take(maxHeaders.toInt)
                .map(_.header)
              BlockHeaders(headers)

            case GetBlockBodies(hashes) =>
              val bodies = hashes.flatMap(hash => blocks.find(_.hash == hash)).map(_.body)
              BlockBodies(bodies)

            case GetReceipts(blockHashes) =>
              Receipts(blockHashes.map(_ => Nil))

            case GetNodeData(mptElementsHashes) =>
              NodeData(getMptNodes(mptElementsHashes.toList))
          }
          val theResponse = MessageFromPeer(response, peerId)
          sender ! theResponse
          responses.publish1(theResponse).unsafeRunSync()
      }
      this
    }

    def blockMatchesStart(block: Block, startingBlock: Either[BigInt, ByteString]): Boolean =
      startingBlock.fold(nr => block.number == nr, hash => block.hash == hash)
  }
}
