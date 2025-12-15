package com.chipprbots.ethereum.network.handshaker

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.network.NetworkPeerManagerActor.PeerInfo
import com.chipprbots.ethereum.network.handshaker.Handshaker.NextMessage
import com.chipprbots.ethereum.network.p2p.Message
import com.chipprbots.ethereum.network.p2p.messages.Capability
import com.chipprbots.ethereum.network.p2p.messages.WireProtocol.Disconnect
import com.chipprbots.ethereum.network.p2p.messages.WireProtocol.Hello
import com.chipprbots.ethereum.network.rlpx.MessageCodec.CompressionPolicy
import com.chipprbots.ethereum.utils.Config
import com.chipprbots.ethereum.utils.Logger
import com.chipprbots.ethereum.utils.ServerStatus

case class EtcHelloExchangeState(handshakerConfiguration: NetworkHandshakerConfiguration)
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
    val peerListenPort = hello.listenPort

    // Enhanced logging for peer capabilities and protocol version
    log.info(
      "PEER_CAPABILITIES: clientId={}, p2pVersion={}, capabilities=[{}], listenPort={}",
      hello.clientId,
      hello.p2pVersion,
      peerCapabilities.mkString(", "),
      peerListenPort
    )
    
    // Log our advertised capabilities for comparison
    log.info(
      "OUR_CAPABILITIES: capabilities=[{}]",
      Config.supportedCapabilities.mkString(", ")
    )

    // Check if peer supports SNAP/1 protocol
    val supportsSnap = peerCapabilities.contains(Capability.SNAP1)
    log.info("PEER_SNAP_SUPPORT: supportsSnap={}, p2pVersion={}", supportsSnap, hello.p2pVersion)

    // Log compression decision based on p2p version
    val compressionPolicy =
      CompressionPolicy.fromHandshake(EtcHelloExchangeState.P2pVersion, hello.p2pVersion)
    log.info(
      "COMPRESSION_CONFIG: peerP2pVersion={}, ourP2pVersion={}, compressOutbound={}, expectInboundCompressed={}",
      hello.p2pVersion,
      EtcHelloExchangeState.P2pVersion,
      compressionPolicy.compressOutbound,
      compressionPolicy.expectInboundCompressed
    )

    // Negotiate protocol capability
    val negotiationResult = Capability.negotiate(peerCapabilities, Config.supportedCapabilities)
    log.info(
      "CAPABILITY_NEGOTIATION: peerCaps=[{}], ourCaps=[{}], negotiated={}",
      peerCapabilities.mkString(", "),
      Config.supportedCapabilities.mkString(", "),
      negotiationResult.map(_.toString).getOrElse("NONE")
    )

    negotiationResult match {
      case Some(Capability.ETH63) =>
        log.info("PROTOCOL_NEGOTIATED: clientId={}, protocol=eth/63, usesRequestId=false", hello.clientId)
        EthNodeStatus63ExchangeState(handshakerConfiguration, supportsSnap, peerCapabilities, peerListenPort)
      case Some(
            negotiated @ (Capability.ETH64 | Capability.ETH65 | Capability.ETH66 | Capability.ETH67 | Capability.ETH68)
          ) =>
        log.info(
          "PROTOCOL_NEGOTIATED: clientId={}, protocol={}, usesRequestId={}",
          hello.clientId,
          negotiated,
          Capability.usesRequestId(negotiated)
        )
        EthNodeStatus64ExchangeState(handshakerConfiguration, negotiated, supportsSnap, peerCapabilities, peerListenPort)
      case _ =>
        log.warn(
          "PROTOCOL_NEGOTIATION_FAILED: clientId={}, peerCaps=[{}], ourCaps=[{}], reason=IncompatibleP2pProtocolVersion",
          hello.clientId,
          peerCapabilities.mkString(", "),
          Config.supportedCapabilities.mkString(", ")
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
      capabilities = Config.supportedCapabilities,
      listenPort = listenPort,
      nodeId = ByteString(nodeStatus.nodeId)
    )
  }
}

object EtcHelloExchangeState {
  // Allow p2pVersion to be configured via fukuii.network.peer.p2p-version.
  // Default remains 5 (Snappy-capable), but can be overridden per environment for investigations.
  lazy val P2pVersion: Int = Config.Network.peer.p2pVersion
}
