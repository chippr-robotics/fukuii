package com.chipprbots.ethereum.jsonrpc

import org.apache.pekko.actor.ActorRef
import org.apache.pekko.util.Timeout

import cats.effect.IO
import cats.syntax.traverse._

import scala.concurrent.duration._

import com.chipprbots.ethereum.jsonrpc.AkkaTaskOps._
import com.chipprbots.ethereum.jsonrpc.DebugService.ListPeersInfoRequest
import com.chipprbots.ethereum.jsonrpc.DebugService.ListPeersInfoResponse
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.PeerInfo
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.PeerInfoResponse
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.PeerActor
import com.chipprbots.ethereum.network.PeerId
import com.chipprbots.ethereum.network.PeerManagerActor
import com.chipprbots.ethereum.network.PeerManagerActor.Peers

object DebugService {
  case class ListPeersInfoRequest()
  case class ListPeersInfoResponse(peers: List[PeerInfo])
}

class DebugService(peerManager: ActorRef, networkPeerManager: ActorRef) {

  def listPeersInfo(getPeersInfoRequest: ListPeersInfoRequest): ServiceResponse[ListPeersInfoResponse] =
    for {
      ids <- getPeerIds
      peers <- ids.traverse(getPeerInfo)
    } yield Right(ListPeersInfoResponse(peers.flatten))

  private def getPeerIds: IO[List[PeerId]] = {
    implicit val timeout: Timeout = Timeout(20.seconds)

    peerManager
      .askFor[Peers](PeerManagerActor.GetPeers)
      .handleError(_ => Peers(Map.empty[Peer, PeerActor.Status]))
      .map(_.peers.keySet.map(_.id).toList)
  }

  private def getPeerInfo(peer: PeerId): IO[Option[PeerInfo]] = {
    implicit val timeout: Timeout = Timeout(20.seconds)

    networkPeerManager
      .askFor[PeerInfoResponse](NetworkPeerManagerActor.PeerInfoRequest(peer))
      .map(resp => resp.peerInfo)
  }
}
