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
    log.debug("RLPx connection established, sending Hello")
    NextMessage(
      messageToSend = createHelloMsg(),
      timeout = peerConfiguration.waitForHelloTimeout
    )
  }

  override def applyResponseMessage: PartialFunction[Message, HandshakerState[PeerInfo]] = { case hello: Hello =>
    log.debug("Protocol handshake finished with peer ({})", hello)
    // Store full capability list from peer
    val peerCapabilities = hello.capabilities.toList
    
    // Enhanced logging for peer capabilities and protocol version
    log.info("PEER_CAPABILITIES: clientId={}, p2pVersion={}, capabilities=[{}]", 
      hello.clientId, hello.p2pVersion, peerCapabilities.mkString(", "))
    
    // Check if peer supports SNAP/1 protocol
    val supportsSnap = peerCapabilities.contains(Capability.SNAP1)
    log.info("PEER_SNAP_SUPPORT: supportsSnap={}, p2pVersion={}", supportsSnap, hello.p2pVersion)
    
    // Log compression decision based on p2p version
    val compressionEnabled = hello.p2pVersion >= EtcHelloExchangeState.P2pVersion
    log.info("COMPRESSION_CONFIG: peerP2pVersion={}, ourP2pVersion={}, compressionEnabled={}", 
      hello.p2pVersion, EtcHelloExchangeState.P2pVersion, compressionEnabled)
    
    // FIXME in principle this should be already negotiated
    Capability.negotiate(peerCapabilities, handshakerConfiguration.blockchainConfig.capabilities) match {
      case Some(Capability.ETC64) =>
        log.debug("Negotiated protocol version with client {} is etc/64", hello.clientId)
        EtcNodeStatus64ExchangeState(handshakerConfiguration, supportsSnap, peerCapabilities)
      case Some(Capability.ETH63) =>
        log.debug("Negotiated protocol version with client {} is eth/63", hello.clientId)
        EthNodeStatus63ExchangeState(handshakerConfiguration, supportsSnap, peerCapabilities)
      case Some(
            negotiated @ (Capability.ETH64 | Capability.ETH65 | Capability.ETH66 | Capability.ETH67 | Capability.ETH68)
          ) =>
        log.debug("Negotiated protocol version with client {} is {}", hello.clientId, negotiated)
        EthNodeStatus64ExchangeState(handshakerConfiguration, negotiated, supportsSnap, peerCapabilities)
      case _ =>
        log.debug(
          s"Connected peer does not support eth/63-68 or etc/64 protocol. Disconnecting."
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
  // Use p2pVersion 5 to align with CoreGeth and enable Snappy compression
  // CoreGeth (and go-ethereum) only enable Snappy when p2pVersion >= 5
  // See: https://github.com/etclabscore/core-geth/blob/master/p2p/peer.go#L54
  val P2pVersion = 5
}
