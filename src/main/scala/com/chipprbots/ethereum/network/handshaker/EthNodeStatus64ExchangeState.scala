package com.chipprbots.ethereum.network.handshaker

import cats.effect.SyncIO

import com.chipprbots.ethereum.forkid.Connect
import com.chipprbots.ethereum.forkid.ForkId
import com.chipprbots.ethereum.forkid.ForkIdValidator
import com.chipprbots.ethereum.network.EtcPeerManagerActor.PeerInfo
import com.chipprbots.ethereum.network.EtcPeerManagerActor.RemoteStatus
import com.chipprbots.ethereum.network.p2p.Message
import com.chipprbots.ethereum.network.p2p.MessageSerializable
import com.chipprbots.ethereum.network.p2p.messages.Capability
import com.chipprbots.ethereum.network.p2p.messages.ETH64
import com.chipprbots.ethereum.network.p2p.messages.WireProtocol.Disconnect

case class EthNodeStatus64ExchangeState(
    handshakerConfiguration: EtcHandshakerConfiguration,
    negotiatedCapability: Capability
) extends EtcNodeStatusExchangeState[ETH64.Status] {

  import handshakerConfiguration._

  def applyResponseMessage: PartialFunction[Message, HandshakerState[PeerInfo]] = { case status: ETH64.Status =>
    import ForkIdValidator.syncIoLogger
    
    log.info(
      s"STATUS_EXCHANGE: Received ETH Status - negotiated=${negotiatedCapability}, " +
      s"protocolVersion=${status.protocolVersion}, networkId=${status.networkId}, " +
      s"totalDifficulty=${status.totalDifficulty}, forkId=${status.forkId}"
    )
    
    // Validate that the remote peer sent the same protocol version we negotiated
    if (status.protocolVersion != negotiatedCapability.version) {
      log.warn(
        s"STATUS_EXCHANGE: Protocol version mismatch! Negotiated=${negotiatedCapability.version}, " +
        s"Received=${status.protocolVersion}. Peer may disconnect."
      )
    }
    
    (for {
      validationResult <-
        ForkIdValidator.validatePeer[SyncIO](blockchainReader.genesisHeader.hash, blockchainConfig)(
          blockchainReader.getBestBlockNumber(),
          status.forkId
        )
    } yield validationResult match {
      case Connect =>
        log.debug(s"STATUS_EXCHANGE: ForkId validation passed, accepting peer")
        applyRemoteStatusMessage(RemoteStatus(status, negotiatedCapability))
      case rejectionReason =>
        log.warn(s"STATUS_EXCHANGE: ForkId validation failed: $rejectionReason, rejecting peer")
        DisconnectedState[PeerInfo](Disconnect.Reasons.UselessPeer)
    }).unsafeRunSync()
  }

  override protected def createStatusMsg(): MessageSerializable = {
    val bestBlockHeader = getBestBlockHeader()
    val bestBlockNumber = blockchainReader.getBestBlockNumber()
    val chainWeight = blockchainReader
      .getChainWeightByHash(bestBlockHeader.hash)
      .getOrElse(
        throw new IllegalStateException(s"Chain weight not found for hash ${bestBlockHeader.hash}")
      )
    val genesisHash = blockchainReader.genesisHeader.hash
    val forkId = ForkId.create(genesisHash, blockchainConfig)(bestBlockNumber)

    val status = ETH64.Status(
      protocolVersion = negotiatedCapability.version,
      networkId = peerConfiguration.networkId,
      totalDifficulty = chainWeight.totalDifficulty,
      bestHash = bestBlockHeader.hash,
      genesisHash = genesisHash,
      forkId = forkId
    )

    log.info(
      s"STATUS_EXCHANGE: Sending ETH Status - negotiated=${negotiatedCapability}, " +
      s"protocolVersion=${status.protocolVersion}, networkId=${status.networkId}, " +
      s"bestBlock=${bestBlockNumber}, totalDifficulty=${status.totalDifficulty}, " +
      s"forkId=${forkId}"
    )
    status
  }

}
