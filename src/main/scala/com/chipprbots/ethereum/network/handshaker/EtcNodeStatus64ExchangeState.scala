package com.chipprbots.ethereum.network.handshaker

import com.chipprbots.ethereum.network.EtcPeerManagerActor.PeerInfo
import com.chipprbots.ethereum.network.EtcPeerManagerActor.RemoteStatus
import com.chipprbots.ethereum.network.p2p.Message
import com.chipprbots.ethereum.network.p2p.MessageSerializable
import com.chipprbots.ethereum.network.p2p.messages.Capability
import com.chipprbots.ethereum.network.p2p.messages.ETC64

case class EtcNodeStatus64ExchangeState(
    handshakerConfiguration: EtcHandshakerConfiguration
) extends EtcNodeStatusExchangeState[ETC64.Status] {

  import handshakerConfiguration._

  def applyResponseMessage: PartialFunction[Message, HandshakerState[PeerInfo]] = { case status: ETC64.Status =>
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
    // For bootstrap pivot, we estimate the total difficulty based on the block number
    // This is a rough approximation but sufficient for Status exchange
    val chainWeight = if (bestBlockNumber == 0 && bootstrapPivotNumber > 0) {
      val estimatedTotalDifficulty = bootstrapPivotNumber * EtcNodeStatusExchangeState.EstimatedDifficultyPerBlock
      com.chipprbots.ethereum.domain.ChainWeight(0, estimatedTotalDifficulty)
    } else {
      blockchainReader
        .getChainWeightByHash(bestBlockHeader.hash)
        .getOrElse(
          throw new IllegalStateException(s"Chain weight not found for hash ${bestBlockHeader.hash}")
        )
    }

    val status = ETC64.Status(
      protocolVersion = Capability.ETC64.version,
      networkId = peerConfiguration.networkId,
      chainWeight = chainWeight,
      bestHash = effectiveHash,
      genesisHash = blockchainReader.genesisHeader.hash
    )

    log.debug(s"sending status $status")
    status
  }

}
