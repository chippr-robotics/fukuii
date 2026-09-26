package com.chipprbots.ethereum.blockchain.sync
import org.apache.pekko.actor.ActorRef
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.typed.ActorRef as TypedActorRef
import org.apache.pekko.testkit.TestActor.AutoPilot
import org.apache.pekko.testkit.TestProbe
import org.apache.pekko.util.ByteString

import cats.effect.Deferred
import cats.effect.IO
import cats.effect.Resource
import cats.effect.unsafe.IORuntime

import fs2.Stream
import fs2.concurrent.Topic

import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.BlockBody
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.PeerInfo
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.SendMessageCmd
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.PeerEventBusActor
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerEvent.MessageFromPeer
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.BlockBodies
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.BlockHeaders
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.GetBlockBodies
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.GetBlockHeaders
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.GetNodeData
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.GetReceipts
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.NodeData
import com.chipprbots.ethereum.rlp.RLPList
import com.chipprbots.ethereum.utils.Config.SyncConfig

/** Stands in for `NetworkPeerManagerActor` in front of a set of fake peers.
  *
  * Requests arrive as `SendMessageCmd` from typed requesters (`PeerRequestHandler`, `PivotBlockSelector`), which carry
  * no sender and wait on the peer event bus for the reply. So each response is published to `peerEventBus`, the way
  * `PeerActor` publishes a decoded wire message; pass the same real bus to the actors under test.
  */
class NetworkPeerManagerFake(
    syncConfig: SyncConfig,
    peers: Map[Peer, PeerInfo],
    blocks: List[Block],
    peerEventBus: TypedActorRef[PeerEventBusActor.Command]
)(implicit system: ActorSystem, ioRuntime: IORuntime):
  private val responsesTopicIO: IO[Topic[IO, MessageFromPeer]] = Topic[IO, MessageFromPeer]
  private val requestsTopicIO: IO[Topic[IO, SendMessageCmd]] = Topic[IO, SendMessageCmd]
  private val responsesTopic: Topic[IO, MessageFromPeer] = responsesTopicIO.unsafeRunSync()
  private val requestsTopic: Topic[IO, SendMessageCmd] = requestsTopicIO.unsafeRunSync()
  private val peersConnectedDeferred = Deferred.unsafe[IO, Unit]

  val probe: TestProbe = TestProbe("network_peer_manager")
  val autoPilot =
    new NetworkPeerManagerFake.NetworkPeerManagerAutoPilot(
      requestsTopic,
      responsesTopic,
      peersConnectedDeferred,
      peers,
      blocks,
      peerEventBus
    )
  probe.setAutoPilot(autoPilot)

  def ref = probe.ref

  val requests: Stream[IO, SendMessageCmd] = requestsTopic.subscribe(100)
  val responses: Stream[IO, MessageFromPeer] = responsesTopic.subscribe(100)
  val onPeersConnected: IO[Unit] = peersConnectedDeferred.get
  val pivotBlockSelected: Stream[IO, BlockHeader] = pivotBlockSelectedIn(responses)

  val fetchedHeaders: Stream[IO, Seq[BlockHeader]] = responses.collect {
    case MessageFromPeer(BlockHeaders(_, headers), _) if headers.size == syncConfig.blockHeadersPerRequest =>
      headers
  }
  val fetchedBodies: Stream[IO, Seq[BlockBody]] = bodiesIn(responses)
  val requestedReceipts: Stream[IO, Seq[ByteString]] = receiptRequestsIn(requests)
  val fetchedBlocks: Stream[IO, List[Block]] = fetchedBlocksIn(responses, requests)

  /** The first element of [[pivotBlockSelected]], from a subscription registered before this IO completes.
    *
    * `Topic.subscribe` registers its subscriber only once the stream starts running, so a consumer fiber started just
    * before sync begins can miss the first responses; `IO.cede` after `.start` narrows that window but does not close
    * it. `subscribeAwait` registers on acquisition. The subscription is released once the element is taken.
    */
  val subscribePivotBlockSelected: IO[IO[BlockHeader]] =
    firstElementOf(responsesTopic.subscribeAwait(100).map(pivotBlockSelectedIn))

  /** As [[subscribePivotBlockSelected]], for [[fetchedBlocks]]. */
  val subscribeFetchedBlocks: IO[IO[List[Block]]] =
    firstElementOf(
      for
        responses <- responsesTopic.subscribeAwait(100)
        requests <- requestsTopic.subscribeAwait(100)
      yield fetchedBlocksIn(responses, requests)
    )

  // Cancelling the returned IO (a test's timeout) cancels the consumer too, which releases the subscription: a
  // subscriber left with a full queue would block the autopilot's publish1 and every actor that sends to it.
  private def firstElementOf[A](subscription: Resource[IO, Stream[IO, A]]): IO[IO[A]] =
    subscription.allocated.flatMap { case (stream, unsubscribe) =>
      stream.head.compile.lastOrError
        .guarantee(unsubscribe)
        .start
        .map(fiber =>
          fiber.joinWith(IO.raiseError(new RuntimeException("subscription fiber canceled"))).onCancel(fiber.cancel)
        )
    }

  private def pivotBlockSelectedIn(responses: Stream[IO, MessageFromPeer]): Stream[IO, BlockHeader] = responses
    .collect { case MessageFromPeer(BlockHeaders(_, Seq(header)), peer) =>
      (header, peer)
    }
    .chunkN(peers.size)
    .flatMap { headersFromPeersChunk =>
      val headersFromPeers = headersFromPeersChunk.toList
      val (headers, respondedPeers) = headersFromPeers.unzip

      if headers.distinct.size == 1 && respondedPeers.toSet == peers.keySet.map(_.id) then Stream.emit(headers.head)
      else Stream.empty
    }

  private def bodiesIn(responses: Stream[IO, MessageFromPeer]): Stream[IO, Seq[BlockBody]] =
    responses.collect { case MessageFromPeer(BlockBodies(_, bodies), _) => bodies }

  private def receiptRequestsIn(requests: Stream[IO, SendMessageCmd]): Stream[IO, Seq[ByteString]] = requests.collect(
    Function.unlift(msg =>
      msg.message.underlyingMsg match
        case GetReceipts(_, hashes) => Some(hashes)
        case _                      => None
    )
  )

  private def fetchedBlocksIn(
      responses: Stream[IO, MessageFromPeer],
      requests: Stream[IO, SendMessageCmd]
  ): Stream[IO, List[Block]] = bodiesIn(responses)
    .scan[(List[Block], List[Block])]((Nil, blocks)) { case ((_, remainingBlocks), bodies) =>
      remainingBlocks.splitAt(bodies.size)
    }
    .map(_._1)
    .zip(receiptRequestsIn(requests))
    .map { case (blocks, _) => blocks } // a big simplification, but should be sufficient here

  val fetchedState: Stream[IO, Seq[ByteString]] = responses.collect {
    case MessageFromPeer(ETHPackets.NodeData(values), _) => values
  }

object NetworkPeerManagerFake:
  class NetworkPeerManagerAutoPilot(
      requests: Topic[IO, SendMessageCmd],
      responses: Topic[IO, MessageFromPeer],
      peersConnected: Deferred[IO, Unit],
      peers: Map[Peer, PeerInfo],
      blocks: List[Block],
      peerEventBus: TypedActorRef[PeerEventBusActor.Command]
  )(implicit ioRuntime: IORuntime)
      extends AutoPilot:
    def run(sender: ActorRef, msg: Any): NetworkPeerManagerAutoPilot =
      msg match
        case NetworkPeerManagerActor.GetHandshakedPeersCmd(replyTo) =>
          replyTo ! NetworkPeerManagerActor.HandshakedPeers(peers)
          peersConnected.complete(()).handleError(_ => ()).unsafeRunSync()
        case sendMsg @ NetworkPeerManagerActor.SendMessageCmd(rawMsg, peerId) =>
          requests.publish1(sendMsg).unsafeRunSync()
          val response = rawMsg.underlyingMsg match
            case GetBlockHeaders(requestId, startingBlock, maxHeaders, skip, reverse) =>
              BlockHeaders(requestId, headersFor(startingBlock, maxHeaders, skip, reverse))

            case GetBlockBodies(requestId, hashes) =>
              BlockBodies(requestId, bodiesFor(hashes))

            case ETHPackets.GetReceipts(requestId, blockHashes) =>
              ETHPackets.Receipts68(requestId, emptyReceiptsRlp(blockHashes.size))

            case ETHPackets.GetNodeData(mptElementsHashes) =>
              ETHPackets.NodeData(Seq.empty)
          val theResponse = MessageFromPeer(response, peerId)
          peerEventBus ! PeerEventBusActor.PublishCmd(theResponse)
          responses.publish1(theResponse).unsafeRunSync()
      this

    private def headersFor(
        startingBlock: Either[BigInt, ByteString],
        maxHeaders: BigInt,
        skip: BigInt,
        reverse: Boolean
    ): Seq[BlockHeader] =
      val startIndex = blocks.indexWhere(blockMatchesStart(_, startingBlock))
      if startIndex < 0 then Seq.empty
      else
        val orderedBlocks = if reverse then blocks.take(startIndex + 1).reverse else blocks.drop(startIndex)
        val step = (skip + 1).toInt
        orderedBlocks.zipWithIndex
          .collect { case (block, index) if index % step == 0 => block }
          .take(maxHeaders.toInt)
          .map(_.header)

    private def bodiesFor(hashes: Seq[ByteString]): Seq[BlockBody] =
      hashes.flatMap(hash => blocks.find(_.hash.value == hash)).map(_.body)

    private def emptyReceiptsRlp(count: Int): RLPList =
      RLPList(List.fill(count)(RLPList())*)

    def blockMatchesStart(block: Block, startingBlock: Either[BigInt, ByteString]): Boolean =
      startingBlock.fold(nr => block.number.value == nr, hash => block.hash.value == hash)
