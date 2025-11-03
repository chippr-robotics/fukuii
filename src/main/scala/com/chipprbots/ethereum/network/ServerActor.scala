package com.chipprbots.ethereum.network

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
import org.apache.pekko.io.Tcp.CommandFailed
import org.apache.pekko.io.Tcp.Connected

import org.bouncycastle.util.encoders.Hex

import com.chipprbots.ethereum.utils.NodeStatus
import com.chipprbots.ethereum.utils.ServerStatus

class ServerActor(nodeStatusHolder: AtomicReference[NodeStatus], peerManager: ActorRef)
    extends Actor
    with ActorLogging {

  import ServerActor._
  import context.system

  override def receive: Receive = { case StartServer(address) =>
    IO(Tcp) ! Bind(self, address)
    context.become(waitingForBindingResult)
  }

  def waitingForBindingResult: Receive = {
    case Bound(localAddress) =>
      val nodeStatus = nodeStatusHolder.get()
      log.info("Listening on {}", localAddress)
      log.info(
        "Node address: enode://{}@{}:{}",
        Hex.toHexString(nodeStatus.nodeId),
        getHostName(localAddress.getAddress),
        localAddress.getPort
      )
      nodeStatusHolder.getAndUpdate(_.copy(serverStatus = ServerStatus.Listening(localAddress)))
      context.become(listening)

    case CommandFailed(b: Bind) =>
      log.warning("Binding to {} failed", b.localAddress)
      context.stop(self)
  }

  def listening: Receive = { case Connected(remoteAddress, _) =>
    val connection = sender()
    peerManager ! PeerManagerActor.HandlePeerConnection(connection, remoteAddress)
  }
}

object ServerActor {
  def props(nodeStatusHolder: AtomicReference[NodeStatus], peerManager: ActorRef): Props =
    Props(new ServerActor(nodeStatusHolder, peerManager))

  case class StartServer(address: InetSocketAddress)
}
