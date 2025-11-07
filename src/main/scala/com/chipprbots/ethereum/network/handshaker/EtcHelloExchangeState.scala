package com.chipprbots.ethereum.network.handshaker

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.network.EtcPeerManagerActor.PeerInfo
import com.chipprbots.ethereum.network.handshaker.Handshaker.NextMessage
import com.chipprbots.ethereum.network.p2p.Message
import com.chipprbots.ethereum.network.p2p.messages.Capability
import com.chipprbots.ethereum.network.p2p.messages.WireProtocol.Disconnect
import com.chipprbots.ethereum.network.p2p.messages.WireProtocol.Hello
import com.chipprbots.ethereum.utils.Config
import com.chipprbots.ethereum.utils.Logger
import com.chipprbots.ethereum.utils.ServerStatus

case class EtcHelloExchangeState(handshakerConfiguration: EtcHandshakerConfiguration)
    extends InProgressState[PeerInfo]
    with Logger {

  import handshakerConfiguration._

  override def nextMessage: NextMessage = {
    val helloMsg = createHelloMsg()
    log.info(
      s"HELLO_EXCHANGE: Sending Hello - clientId='${Config.clientId}', " +
      s"capabilities=${handshakerConfiguration.blockchainConfig.capabilities.mkString("[", ",", "]")}, " +
      s"p2pVersion=${EtcHelloExchangeState.P2pVersion}"
    )
    NextMessage(
      messageToSend = helloMsg,
      timeout = peerConfiguration.waitForHelloTimeout
    )
  }

  override def applyResponseMessage: PartialFunction[Message, HandshakerState[PeerInfo]] = { case hello: Hello =>
    log.info(
      s"HELLO_EXCHANGE: Received Hello from client='${hello.clientId}', " +
      s"capabilities=${hello.capabilities.mkString("[", ",", "]")}, p2pVersion=${hello.p2pVersion}"
    )
    
    // FIXME in principle this should be already negotiated
    Capability.negotiate(hello.capabilities.toList, handshakerConfiguration.blockchainConfig.capabilities) match {
      case Some(Capability.ETC64) =>
        log.info(s"HELLO_EXCHANGE: Negotiated etc/64 with client '${hello.clientId}'")
        EtcNodeStatus64ExchangeState(handshakerConfiguration)
      case Some(Capability.ETH63) =>
        log.info(s"HELLO_EXCHANGE: Negotiated eth/63 with client '${hello.clientId}'")
        EthNodeStatus63ExchangeState(handshakerConfiguration)
      case Some(negotiated @ (Capability.ETH64 | Capability.ETH65 | Capability.ETH66 | Capability.ETH67 | Capability.ETH68)) =>
        log.info(s"HELLO_EXCHANGE: Negotiated ${negotiated} with client '${hello.clientId}'")
        EthNodeStatus64ExchangeState(handshakerConfiguration, negotiated)
      case _ =>
        log.warn(
          s"HELLO_EXCHANGE: No compatible protocol found. Peer capabilities: ${hello.capabilities.mkString("[", ",", "]")}, " +
          s"our capabilities: ${handshakerConfiguration.blockchainConfig.capabilities.mkString("[", ",", "]")}. Disconnecting."
        )
        DisconnectedState(Disconnect.Reasons.IncompatibleP2pProtocolVersion)
    }
  }

  override def processTimeout: HandshakerState[PeerInfo] = {
    log.debug("Timeout while waiting for Hello")
    DisconnectedState(Disconnect.Reasons.TimeoutOnReceivingAMessage)
  }

  private def createHelloMsg(): Hello = {
    val nodeStatus = nodeStatusHolder.get()
    val listenPort = nodeStatus.serverStatus match {
      case ServerStatus.Listening(address) => address.getPort
      case ServerStatus.NotListening       => 0
    }
    Hello(
      p2pVersion = EtcHelloExchangeState.P2pVersion,
      clientId = Config.clientId,
      capabilities = handshakerConfiguration.blockchainConfig.capabilities,
      listenPort = listenPort,
      nodeId = ByteString(nodeStatus.nodeId)
    )
  }
}

object EtcHelloExchangeState {
  val P2pVersion = 4
}
