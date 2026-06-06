package com.chipprbots.ethereum.network

import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference

import org.apache.pekko.actor.Actor
import org.apache.pekko.actor.ActorLogging
import org.apache.pekko.actor.ActorRef
import org.apache.pekko.actor.Props
import org.apache.pekko.io.IO
import org.apache.pekko.io.Tcp
import org.apache.pekko.io.Tcp.Bind
import org.apache.pekko.io.Tcp.Bound
import org.apache.pekko.io.Tcp.Close
import org.apache.pekko.io.Tcp.CommandFailed
import org.apache.pekko.io.Tcp.Connected

import org.bouncycastle.util.encoders.Hex

import com.chipprbots.ethereum.blockchain.sync.Blacklist
import com.chipprbots.ethereum.utils.NodeStatus
import com.chipprbots.ethereum.utils.ServerStatus

class ServerActor(nodeStatusHolder: AtomicReference[NodeStatus], peerManager: ActorRef, blacklist: Blacklist)
    extends Actor
    with ActorLogging {

  import ServerActor._
  import context.system

  private var advertisedAddressOverride: Option[InetAddress] = None

  override def receive: Receive = { case StartServer(address, advertisedAddress) =>
    advertisedAddressOverride = advertisedAddress
    IO(Tcp) ! Bind(self, address)
    context.become(waitingForBindingResult)
  }

  def waitingForBindingResult: Receive = {
    case Bound(localAddress) =>
      val nodeStatus = nodeStatusHolder.get()
      val advertisedHost: InetAddress = advertisedAddressOverride.getOrElse {
        if (localAddress.getAddress.isAnyLocalAddress) {
          ExternalIPDetector.detect() match {
            case Some(ip) =>
              log.info("External IP detected for advertisement: {}", ip.getHostAddress)
              ip
            case None =>
              log.warning(
                "External IP detection failed (STUN/HTTP/interface all unavailable); " +
                  "advertising loopback — inbound peers on other hosts may not reach this node"
              )
              InetAddress.getLoopbackAddress
          }
        } else {
          localAddress.getAddress
        }
      }
      val advertisedAddress = new InetSocketAddress(advertisedHost, localAddress.getPort)
      log.info("Listening on {}", localAddress)
      log.info(
        "Node address: enode://{}@{}:{}",
        Hex.toHexString(nodeStatus.nodeId),
        getHostName(advertisedHost),
        advertisedAddress.getPort
      )
      nodeStatusHolder.getAndUpdate(_.copy(serverStatus = ServerStatus.Listening(advertisedAddress)))
      context.become(listening)

    case CommandFailed(b: Bind) =>
      log.warning("Binding to {} failed", b.localAddress)
      context.stop(self)
  }

  def listening: Receive = { case Connected(remoteAddress, _) =>
    val connection = sender()
    val addr = remoteAddress.getAddress
    val isLocal = addr.isLoopbackAddress || addr.isSiteLocalAddress
    if (!isLocal && blacklist.isBlacklisted(PeerManagerActor.PeerAddress(remoteAddress.getHostString))) {
      log.debug("Dropping inbound TCP from blacklisted {}", remoteAddress.getHostString)
      connection ! Close
    } else {
      peerManager ! PeerManagerActor.HandlePeerConnection(connection, remoteAddress)
    }
  }
}

object ServerActor {
  def props(nodeStatusHolder: AtomicReference[NodeStatus], peerManager: ActorRef, blacklist: Blacklist): Props =
    Props(new ServerActor(nodeStatusHolder, peerManager, blacklist))

  case class StartServer(address: InetSocketAddress, advertisedAddress: Option[InetAddress] = None)
}
