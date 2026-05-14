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

    // See EthNodeStatus64ExchangeState. Falls back to TTD on post-merge chains,
    // ChainWeight.zero on pre-merge chains. ETH/63 doesn't really exist on post-merge
    // networks (sepolia/mainnet are ETH/68+ only), so this is primarily for ETC.
    val chainWeight = blockchainReader
      .getChainWeightByHash(bestBlockHeader.hash)
      .getOrElse {
        val ttdFallback = blockchainConfig.terminalTotalDifficulty
          .map(com.chipprbots.ethereum.domain.ChainWeight.totalDifficultyOnly)
          .getOrElse(com.chipprbots.ethereum.domain.ChainWeight.zero)
        log.debug(
          s"Chain weight not stored for best block ${bestBlockHeader.hash} (SNAP-sync state); " +
            s"advertising fallback TD=${ttdFallback.totalDifficulty} in ETH/63 STATUS"
        )
        ttdFallback
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
