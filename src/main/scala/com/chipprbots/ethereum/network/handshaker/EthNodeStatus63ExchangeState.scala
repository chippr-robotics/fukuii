package com.chipprbots.ethereum.network.handshaker

import com.chipprbots.ethereum.network.EtcPeerManagerActor.PeerInfo
import com.chipprbots.ethereum.network.EtcPeerManagerActor.RemoteStatus
import com.chipprbots.ethereum.network.p2p.Message
import com.chipprbots.ethereum.network.p2p.MessageSerializable
import com.chipprbots.ethereum.network.p2p.messages.BaseETH6XMessages
import com.chipprbots.ethereum.network.p2p.messages.Capability

case class EthNodeStatus63ExchangeState(
    handshakerConfiguration: EtcHandshakerConfiguration
) extends EtcNodeStatusExchangeState[BaseETH6XMessages.Status] {

  import handshakerConfiguration._

  def applyResponseMessage: PartialFunction[Message, HandshakerState[PeerInfo]] = {
    case status: BaseETH6XMessages.Status =>
      applyRemoteStatusMessage(RemoteStatus(status))
  }

  override protected def createStatusMsg(): MessageSerializable = {
    val bestBlockHeader = getBestBlockHeader()
    val bestBlockNumber = blockchainReader.getBestBlockNumber()
    val bootstrapPivotNumber = appStateStorage.getBootstrapPivotBlock()
    val bootstrapPivotHash = appStateStorage.getBootstrapPivotBlockHash()
    
    // If we only have genesis but have a bootstrap pivot, use the pivot for the Status message
    // to avoid fork ID incompatibility and peer disconnections during initial sync
    val effectiveHash = 
      if (bestBlockNumber == 0 && bootstrapPivotNumber > 0 && bootstrapPivotHash.nonEmpty) {
        log.info(
          "Using bootstrap pivot block {} (hash: {}) for Status message instead of genesis to avoid peer disconnections",
          bootstrapPivotNumber,
          com.chipprbots.ethereum.utils.ByteStringUtils.hash2string(bootstrapPivotHash)
        )
        bootstrapPivotHash
      } else {
        bestBlockHeader.hash
      }
    
    // Calculate chain weight for the effective block
    val chainWeight = if (bestBlockNumber == 0 && bootstrapPivotNumber > 0) {
      // Estimate difficulty: assume average difficulty of ~2 TH for ETC mainnet
      val estimatedTotalDifficulty = bootstrapPivotNumber * BigInt(2000000000000L)
      com.chipprbots.ethereum.domain.ChainWeight(0, estimatedTotalDifficulty)
    } else {
      blockchainReader
        .getChainWeightByHash(bestBlockHeader.hash)
        .getOrElse(
          throw new IllegalStateException(s"Chain weight not found for hash ${bestBlockHeader.hash}")
        )
    }

    val status = BaseETH6XMessages.Status(
      protocolVersion = Capability.ETH63.version,
      networkId = peerConfiguration.networkId,
      totalDifficulty = chainWeight.totalDifficulty,
      bestHash = effectiveHash,
      genesisHash = blockchainReader.genesisHeader.hash
    )

    log.debug(s"sending status $status")
    status
  }

}
