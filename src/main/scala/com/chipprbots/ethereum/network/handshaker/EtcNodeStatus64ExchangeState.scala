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
    log.info(
      s"STATUS_EXCHANGE: Received ETC Status - protocolVersion=${status.protocolVersion}, " +
      s"networkId=${status.networkId}, totalDifficulty=${status.chainWeight.totalDifficulty}"
    )
    applyRemoteStatusMessage(RemoteStatus(status))
  }

  override protected def createStatusMsg(): MessageSerializable = {
    val bestBlockHeader = getBestBlockHeader()
    val bestBlockNumber = blockchainReader.getBestBlockNumber()
    val chainWeight = blockchainReader
      .getChainWeightByHash(bestBlockHeader.hash)
      .getOrElse(
        throw new IllegalStateException(s"Chain weight not found for hash ${bestBlockHeader.hash}")
      )

    val status = ETC64.Status(
      protocolVersion = Capability.ETC64.version,
      networkId = peerConfiguration.networkId,
      chainWeight = chainWeight,
      bestHash = bestBlockHeader.hash,
      genesisHash = blockchainReader.genesisHeader.hash
    )

    log.info(
      s"STATUS_EXCHANGE: Sending ETC Status - protocolVersion=${status.protocolVersion}, " +
      s"networkId=${status.networkId}, bestBlock=${bestBlockNumber}, " +
      s"totalDifficulty=${chainWeight.totalDifficulty}, chainWeight=${chainWeight.chainWeight}"
    )
    status
  }

}
