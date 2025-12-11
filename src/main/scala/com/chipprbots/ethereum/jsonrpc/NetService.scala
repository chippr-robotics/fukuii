package com.chipprbots.ethereum.jsonrpc

import java.net.URI
import java.util.concurrent.atomic.AtomicReference

import org.apache.pekko.actor.ActorRef
import org.apache.pekko.util.Timeout

import cats.effect.IO

import scala.concurrent.duration._

import com.chipprbots.ethereum.blockchain.sync.Blacklist
import com.chipprbots.ethereum.jsonrpc.NetService.NetServiceConfig
import com.chipprbots.ethereum.network.PeerId
import com.chipprbots.ethereum.network.PeerManagerActor
import com.chipprbots.ethereum.utils.Config
import com.chipprbots.ethereum.utils.NodeStatus
import com.chipprbots.ethereum.utils.ServerStatus.Listening
import com.chipprbots.ethereum.utils.ServerStatus.NotListening

object NetService {
  case class VersionRequest()
  case class VersionResponse(value: String)

  case class ListeningRequest()
  case class ListeningResponse(value: Boolean)

  case class PeerCountRequest()
  case class PeerCountResponse(value: Int)

  // Enhanced peer management requests/responses
  case class PeerInfo(
      id: String,
      remoteAddress: String,
      nodeId: Option[String],
      incomingConnection: Boolean,
      status: String
  )

  case class ListPeersRequest()
  case class ListPeersResponse(peers: List[PeerInfo])

  case class DisconnectPeerRequest(peerId: String)
  case class DisconnectPeerResponse(success: Boolean)

  case class ConnectToPeerRequest(uri: String)
  case class ConnectToPeerResponse(success: Boolean)

  // Blacklist management requests/responses
  case class BlacklistEntry(
      id: String,
      reason: String,
      addedAt: Long
  )

  case class ListBlacklistedPeersRequest()
  case class ListBlacklistedPeersResponse(blacklistedPeers: List[BlacklistEntry])

  case class AddToBlacklistRequest(
      address: String,
      duration: Option[Long], // Duration in seconds, None for permanent
      reason: String
  )
  case class AddToBlacklistResponse(added: Boolean)

  case class RemoveFromBlacklistRequest(address: String)
  case class RemoveFromBlacklistResponse(removed: Boolean)

  case class NetServiceConfig(peerManagerTimeout: FiniteDuration)

  object NetServiceConfig {
    def apply(etcClientConfig: com.typesafe.config.Config): NetServiceConfig = {
      val netServiceConfig = etcClientConfig.getConfig("network.rpc.net")
      NetServiceConfig(peerManagerTimeout = netServiceConfig.getDuration("peer-manager-timeout").toMillis.millis)
    }
  }
}

trait NetServiceAPI {
  import NetService._

  def version(req: VersionRequest): ServiceResponse[VersionResponse]
  def listening(req: ListeningRequest): ServiceResponse[ListeningResponse]
  def peerCount(req: PeerCountRequest): ServiceResponse[PeerCountResponse]

  // Enhanced peer management API
  def listPeers(req: ListPeersRequest): ServiceResponse[ListPeersResponse]
  def disconnectPeer(req: DisconnectPeerRequest): ServiceResponse[DisconnectPeerResponse]
  def connectToPeer(req: ConnectToPeerRequest): ServiceResponse[ConnectToPeerResponse]

  // Blacklist management API
  def listBlacklistedPeers(req: ListBlacklistedPeersRequest): ServiceResponse[ListBlacklistedPeersResponse]
  def addToBlacklist(req: AddToBlacklistRequest): ServiceResponse[AddToBlacklistResponse]
  def removeFromBlacklist(req: RemoveFromBlacklistRequest): ServiceResponse[RemoveFromBlacklistResponse]
}

class NetService(
    nodeStatusHolder: AtomicReference[NodeStatus],
    peerManager: ActorRef,
    blacklist: Blacklist,
    config: NetServiceConfig
) extends NetServiceAPI {
  import NetService._
  import com.chipprbots.ethereum.jsonrpc.AkkaTaskOps._

  def version(req: VersionRequest): ServiceResponse[VersionResponse] =
    IO.pure(Right(VersionResponse(Config.Network.peer.networkId.toString)))

  def listening(req: ListeningRequest): ServiceResponse[ListeningResponse] =
    IO.pure {
      Right(
        nodeStatusHolder.get().serverStatus match {
          case _: Listening => ListeningResponse(true)
          case NotListening => ListeningResponse(false)
        }
      )
    }

  def peerCount(req: PeerCountRequest): ServiceResponse[PeerCountResponse] = {
    implicit val timeout: Timeout = Timeout(config.peerManagerTimeout)
    peerManager
      .askFor[PeerManagerActor.Peers](PeerManagerActor.GetPeers)
      .map(peers => Right(PeerCountResponse(peers.handshaked.size)))
  }

  def listPeers(req: ListPeersRequest): ServiceResponse[ListPeersResponse] = {
    implicit val timeout: Timeout = Timeout(config.peerManagerTimeout)
    peerManager
      .askFor[PeerManagerActor.Peers](PeerManagerActor.GetPeers)
      .map { peersData =>
        val peerInfoList = peersData.peers.map { case (peer, status) =>
          PeerInfo(
            id = peer.id.value,
            remoteAddress = peer.remoteAddress.toString,
            nodeId = peer.nodeId.map(bs => org.bouncycastle.util.encoders.Hex.toHexString(bs.toArray)),
            incomingConnection = peer.incomingConnection,
            status = status.toString
          )
        }.toList
        Right(ListPeersResponse(peerInfoList))
      }
  }

  def disconnectPeer(req: DisconnectPeerRequest): ServiceResponse[DisconnectPeerResponse] = {
    implicit val timeout: Timeout = Timeout(config.peerManagerTimeout)
    peerManager
      .askFor[PeerManagerActor.DisconnectPeerResponse](
        PeerManagerActor.DisconnectPeerById(PeerId(req.peerId))
      )
      .map(response => Right(DisconnectPeerResponse(response.disconnected)))
  }

  def connectToPeer(req: ConnectToPeerRequest): ServiceResponse[ConnectToPeerResponse] =
    try {
      val uri = new URI(req.uri)
      // Note: This sends the connect message and returns immediately.
      // Success=true means the URI is valid and connection attempt was initiated,
      // not that the connection succeeded. Check net_listPeers to verify connection.
      peerManager ! PeerManagerActor.ConnectToPeer(uri)
      IO.pure(Right(ConnectToPeerResponse(success = true)))
    } catch {
      case e: Exception =>
        IO.pure(Left(JsonRpcError.InvalidParams(s"Invalid peer URI: ${e.getMessage}")))
    }

  def listBlacklistedPeers(req: ListBlacklistedPeersRequest): ServiceResponse[ListBlacklistedPeersResponse] =
    IO.pure {
      // Note: Current Blacklist implementation doesn't store reason or timestamp with entries.
      // These fields are populated with placeholder values. See GitHub issue for enhancement.
      val blacklistedPeers = blacklist.keys.map { id =>
        BlacklistEntry(
          id = id.value,
          reason = "Unknown", // Blacklist doesn't currently store reason with the entry
          addedAt = System.currentTimeMillis() // Placeholder, would need to extend Blacklist to track this
        )
      }.toList
      Right(ListBlacklistedPeersResponse(blacklistedPeers))
    }

  def addToBlacklist(req: AddToBlacklistRequest): ServiceResponse[AddToBlacklistResponse] = {
    implicit val timeout: Timeout = Timeout(config.peerManagerTimeout)
    peerManager
      .askFor[PeerManagerActor.AddToBlacklistResponse](
        PeerManagerActor.AddToBlacklistRequest(
          address = req.address,
          duration = req.duration.map(_.seconds),
          reason = req.reason
        )
      )
      .map(response => Right(AddToBlacklistResponse(response.added)))
  }

  def removeFromBlacklist(req: RemoveFromBlacklistRequest): ServiceResponse[RemoveFromBlacklistResponse] = {
    implicit val timeout: Timeout = Timeout(config.peerManagerTimeout)
    peerManager
      .askFor[PeerManagerActor.RemoveFromBlacklistResponse](
        PeerManagerActor.RemoveFromBlacklistRequest(req.address)
      )
      .map(response => Right(RemoveFromBlacklistResponse(response.removed)))
  }
}
