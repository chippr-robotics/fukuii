package com.chipprbots.ethereum.blockchain.sync.fast

import org.apache.pekko.util.ByteString

import cats.data.NonEmptyList

import scala.annotation.tailrec

import com.chipprbots.ethereum.blockchain.sync.fast.SyncStateScheduler.SyncResponse
import com.chipprbots.ethereum.blockchain.sync.fast.SyncStateSchedulerActor.NoUsefulDataInResponse
import com.chipprbots.ethereum.blockchain.sync.fast.SyncStateSchedulerActor.PeerRequest
import com.chipprbots.ethereum.blockchain.sync.fast.SyncStateSchedulerActor.ResponseProcessingResult
import com.chipprbots.ethereum.blockchain.sync.fast.SyncStateSchedulerActor.UnrequestedResponse
import com.chipprbots.ethereum.blockchain.sync.fast.SyncStateSchedulerActor.UsefulData
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.PeerId
import com.chipprbots.ethereum.network.p2p.messages.ETH63.NodeData

/** Request tracking state for fast sync state downloads.
  *
  * Supports per-peer pipelining: multiple concurrent requests per peer, tracked by unique requestId. This enables 2-5x
  * throughput improvement over the previous single-request-per-peer design.
  *
  * @param activeRequests
  *   in-flight requests keyed by requestId → (peerId, requestedHashes)
  * @param nodesToGet
  *   all hashes pending download, with optional peer assignment
  * @param nextRequestId
  *   monotonically increasing counter for unique request identification
  */
final case class DownloaderState(
    activeRequests: Map[Long, (PeerId, NonEmptyList[ByteString])],
    nodesToGet: Map[ByteString, Option[PeerId]],
    nextRequestId: Long = 0L
) {
  lazy val nonDownloadedNodes: Seq[ByteString] = nodesToGet.collect {
    case (hash, maybePeer) if maybePeer.isEmpty => hash
  }.toSeq

  /** Count of in-flight requests for a specific peer. */
  def inFlightCount(peerId: PeerId): Int =
    activeRequests.count { case (_, (pid, _)) => pid == peerId }

  def scheduleNewNodesForRetrieval(nodes: Seq[ByteString]): DownloaderState = {
    val newNodesToGet = nodes.foldLeft(nodesToGet) { case (map, node) =>
      if (map.contains(node)) {
        map
      } else {
        map + (node -> None)
      }
    }

    copy(nodesToGet = newNodesToGet)
  }

  private def addActiveRequest(peerRequest: PeerRequest): DownloaderState = {
    val requestId = nextRequestId
    val newNodesToget = peerRequest.nodes.foldLeft(nodesToGet) { case (map, node) =>
      map + (node -> Some(peerRequest.peer.id))
    }

    copy(
      activeRequests = activeRequests + (requestId -> (peerRequest.peer.id, peerRequest.nodes)),
      nodesToGet = newNodesToget,
      nextRequestId = nextRequestId + 1
    )
  }

  /** Handle a failed request by rescheduling its hashes for retry.
    *
    * @param requestId
    *   the unique request identifier
    * @return
    *   updated state with hashes freed for reassignment
    */
  def handleRequestFailure(requestId: Long): DownloaderState =
    activeRequests
      .get(requestId)
      .map { case (_, requestedNodes) =>
        val newNodesToGet = requestedNodes.foldLeft(nodesToGet) { case (map, node) =>
          map + (node -> None)
        }

        copy(activeRequests = activeRequests - requestId, nodesToGet = newNodesToGet)
      }
      .getOrElse(this)

  /** Responses from peers should be delivered in order, but can contain gaps or can be not full, so we cannot fail on
    * first not matching response. Matched responses are returned in correct order, the hashes to be rescheduled are
    * returned in no particular order as they will either way end up in map of hashes to be re-downloaded
    */
  def process(
      requested: NonEmptyList[ByteString],
      received: NonEmptyList[ByteString]
  ): (List[ByteString], List[SyncResponse]) = {
    @tailrec
    def go(
        remainingRequestedHashes: List[ByteString],
        nextResponse: SyncResponse,
        remainingResponses: List[ByteString],
        nonReceivedRequested: List[ByteString],
        processed: List[SyncResponse]
    ): (List[ByteString], List[SyncResponse]) =
      if (remainingRequestedHashes.isEmpty) {
        (nonReceivedRequested, processed.reverse)
      } else {
        val nextRequestedHash = remainingRequestedHashes.head
        if (nextRequestedHash == nextResponse.hash) {
          if (remainingResponses.isEmpty) {
            val finalNonReceived = remainingRequestedHashes.tail ::: nonReceivedRequested
            val finalProcessed = nextResponse :: processed
            (finalNonReceived, finalProcessed.reverse)
          } else {
            val nexExpectedResponse = SyncResponse(kec256(remainingResponses.head), remainingResponses.head)
            go(
              remainingRequestedHashes.tail,
              nexExpectedResponse,
              remainingResponses.tail,
              nonReceivedRequested,
              nextResponse :: processed
            )
          }
        } else {
          go(
            remainingRequestedHashes.tail,
            nextResponse,
            remainingResponses,
            nextRequestedHash :: nonReceivedRequested,
            processed
          )
        }
      }

    val firstReceivedResponse = SyncResponse(kec256(received.head), received.head)

    go(requested.toList, firstReceivedResponse, received.tail, List.empty, List.empty)
  }

  /** Handle a successful response by matching received data against the original request.
    *
    * @param requestId
    *   the unique request identifier
    * @param receivedMessage
    *   the NodeData response from the peer
    * @return
    *   processing result and updated state
    */
  def handleRequestSuccess(requestId: Long, receivedMessage: NodeData): (ResponseProcessingResult, DownloaderState) =
    activeRequests
      .get(requestId)
      .map { case (_, requestedHashes) =>
        if (receivedMessage.values.isEmpty) {
          val rescheduleRequestedHashes = requestedHashes.foldLeft(nodesToGet) { case (map, hash) =>
            map + (hash -> None)
          }
          (
            NoUsefulDataInResponse,
            copy(activeRequests = activeRequests - requestId, nodesToGet = rescheduleRequestedHashes)
          )
        } else {
          val (notReceived, received) =
            process(requestedHashes, NonEmptyList.fromListUnsafe(receivedMessage.values.toList))
          if (received.isEmpty) {
            val rescheduleRequestedHashes = notReceived.foldLeft(nodesToGet) { case (map, hash) =>
              map + (hash -> None)
            }
            (
              NoUsefulDataInResponse,
              copy(activeRequests = activeRequests - requestId, nodesToGet = rescheduleRequestedHashes)
            )
          } else {
            val afterNotReceive = notReceived.foldLeft(nodesToGet) { case (map, hash) => map + (hash -> None) }
            val afterReceived = received.foldLeft(afterNotReceive) { case (map, received) => map - received.hash }
            (UsefulData(received), copy(activeRequests = activeRequests - requestId, nodesToGet = afterReceived))
          }
        }
      }
      .getOrElse((UnrequestedResponse, this))

  /** Assign download tasks to available peers (with pipelining support).
    *
    * Each peer entry in the list represents one available request slot. A peer may appear multiple times if it has
    * multiple free slots (pipelining). Each slot assignment generates a unique requestId.
    */
  def assignTasksToPeers(
      peers: NonEmptyList[Peer],
      newNodes: Option[Seq[ByteString]],
      nodesPerPeerCapacity: Int
  ): (Seq[PeerRequest], DownloaderState) = {
    @tailrec
    def go(
        peersRemaining: List[Peer],
        nodesRemaining: Seq[ByteString],
        createdRequests: List[PeerRequest],
        currentState: DownloaderState
    ): (Seq[PeerRequest], DownloaderState) =
      if (peersRemaining.isEmpty || nodesRemaining.isEmpty) {
        (createdRequests.reverse, currentState.scheduleNewNodesForRetrieval(nodesRemaining))
      } else {
        val nextPeer = peersRemaining.head
        val (nodes, nodesAfterAssignment) = nodesRemaining.splitAt(nodesPerPeerCapacity)
        val peerRequest = PeerRequest(nextPeer, NonEmptyList.fromListUnsafe(nodes.toList), currentState.nextRequestId)
        go(
          peersRemaining.tail,
          nodesAfterAssignment,
          peerRequest :: createdRequests,
          currentState.addActiveRequest(peerRequest)
        )
      }

    val currentNodesToDeliver = newNodes.map(nodes => nonDownloadedNodes ++ nodes).getOrElse(nonDownloadedNodes)
    if (currentNodesToDeliver.isEmpty) {
      (Seq(), this)
    } else {
      go(peers.toList, currentNodesToDeliver, List.empty, this)
    }
  }

}

object DownloaderState {
  def apply(): DownloaderState = new DownloaderState(Map.empty, Map.empty, 0L)
}
