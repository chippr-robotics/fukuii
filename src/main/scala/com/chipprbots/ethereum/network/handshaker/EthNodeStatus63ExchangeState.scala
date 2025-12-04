package com.chipprbots.ethereum.network.handshaker

import com.chipprbots.ethereum.network.EtcPeerManagerActor.PeerInfo
import com.chipprbots.ethereum.network.EtcPeerManagerActor.RemoteStatus
import com.chipprbots.ethereum.network.p2p.Message
import com.chipprbots.ethereum.network.p2p.MessageSerializable
import com.chipprbots.ethereum.network.p2p.messages.BaseETH6XMessages
import com.chipprbots.ethereum.network.p2p.messages.Capability

case class EthNodeStatus63ExchangeState(
    handshakerConfiguration: EtcHandshakerConfiguration,
    supportsSnap: Boolean = false,
    peerCapabilities: List[Capability] = List.empty
) extends EtcNodeStatusExchangeState[BaseETH6XMessages.Status] {

  import handshakerConfiguration._

  def applyResponseMessage: PartialFunction[Message, HandshakerState[PeerInfo]] = {
    case status: BaseETH6XMessages.Status =>
      applyRemoteStatusMessage(RemoteStatus(status, Capability.ETH63, supportsSnap, peerCapabilities))
  }

  override protected def createStatusMsg(): MessageSerializable = {
    val bestBlockHeader = getBestBlockHeader()
    
    val chainWeight = blockchainReader
      .getChainWeightByHash(bestBlockHeader.hash)
      .getOrElse(
        throw new IllegalStateException(s"Chain weight not found for hash ${bestBlockHeader.hash}")
      )

    val status = BaseETH6XMessages.Status(
      protocolVersion = Capability.ETH63.version,
      networkId = peerConfiguration.networkId,
      totalDifficulty = chainWeight.totalDifficulty,
      bestHash = bestBlockHeader.hash,
      genesisHash = blockchainReader.genesisHeader.hash
    )

    log.debug(s"sending status $status")
    status
  }

}
