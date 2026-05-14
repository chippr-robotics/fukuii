package com.chipprbots.ethereum.network.handshaker

import com.chipprbots.ethereum.network.NetworkPeerManagerActor.PeerInfo
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.RemoteStatus
import com.chipprbots.ethereum.network.p2p.Message
import com.chipprbots.ethereum.network.p2p.MessageSerializable
import com.chipprbots.ethereum.network.p2p.messages.BaseETH6XMessages
import com.chipprbots.ethereum.network.p2p.messages.Capability

case class EthNodeStatus63ExchangeState(
    handshakerConfiguration: NetworkHandshakerConfiguration,
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

    // See EthNodeStatus64ExchangeState — ChainWeightStorage isn't populated post-SNAP-sync
    // on post-merge chains; fall back to zero rather than throwing and killing the handshake.
    val chainWeight = blockchainReader
      .getChainWeightByHash(bestBlockHeader.hash)
      .getOrElse {
        log.debug(
          s"Chain weight not stored for best block ${bestBlockHeader.hash} (SNAP-sync state); " +
            s"advertising ChainWeight.zero in ETH/63 STATUS"
        )
        com.chipprbots.ethereum.domain.ChainWeight.zero
      }

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
