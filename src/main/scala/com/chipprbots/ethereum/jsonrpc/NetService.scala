package com.chipprbots.ethereum.jsonrpc

import java.util.concurrent.atomic.AtomicReference

import org.apache.pekko.actor.ActorRef
import org.apache.pekko.util.Timeout

import cats.effect.IO

import scala.concurrent.duration._

import com.chipprbots.ethereum.jsonrpc.NetService.NetServiceConfig
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

  case class NetServiceConfig(peerManagerTimeout: FiniteDuration)

  object NetServiceConfig {
    def apply(etcClientConfig: com.typesafe.config.Config): NetServiceConfig = {
      val netServiceConfig = etcClientConfig.getConfig("network.rpc.net")
      NetServiceConfig(peerManagerTimeout = netServiceConfig.getDuration("peer-manager-timeout").toMillis.millis)
    }
  }
}

class NetService(nodeStatusHolder: AtomicReference[NodeStatus], peerManager: ActorRef, config: NetServiceConfig) {
  import NetService._

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
    import com.chipprbots.ethereum.jsonrpc.AkkaTaskOps._
    peerManager
      .askFor[PeerManagerActor.Peers](PeerManagerActor.GetPeers)
      .map(peers => Right(PeerCountResponse(peers.handshaked.size)))
  }
}
