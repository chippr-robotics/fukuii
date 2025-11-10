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
    log.debug("Received status from peer: protocolVersion={}, networkId={}, totalDifficulty={}, bestHash={}, genesisHash={}, forkId={}", 
      status.protocolVersion, status.networkId, status.totalDifficulty, status.bestHash, status.genesisHash, status.forkId)
    
    val localBestBlock = blockchainReader.getBestBlockNumber()
    val localGenesisHash = blockchainReader.genesisHeader.hash
    
    log.debug("Local state for comparison: bestBlock={}, genesisHash={}", localBestBlock, localGenesisHash)
    
    if (status.genesisHash != localGenesisHash) {
      log.warn("Peer genesis hash mismatch! Local: {}, Remote: {} - disconnecting peer", 
        localGenesisHash, status.genesisHash)
      DisconnectedState[PeerInfo](Disconnect.Reasons.UselessPeer)
    } else {
      (for {
        validationResult <-
          ForkIdValidator.validatePeer[SyncIO](blockchainReader.genesisHeader.hash, blockchainConfig)(
            blockchainReader.getBestBlockNumber(),
            status.forkId
          )
      } yield {
        log.debug("ForkId validation result: {}", validationResult)
        validationResult match {
          case Connect => 
            log.debug("ForkId validation passed - accepting peer connection")
            applyRemoteStatusMessage(RemoteStatus(status, negotiatedCapability))
          case other =>
            log.warn("ForkId validation failed with result: {} - disconnecting peer as UselessPeer", other)
            DisconnectedState[PeerInfo](Disconnect.Reasons.UselessPeer)
        }
      }).unsafeRunSync()
    }
  }

  override protected def createStatusMsg(): MessageSerializable = {
    val bestBlockHeader = getBestBlockHeader()
    val chainWeight = blockchainReader
      .getChainWeightByHash(bestBlockHeader.hash)
      .getOrElse(
        throw new IllegalStateException(s"Chain weight not found for hash ${bestBlockHeader.hash}")
      )
    val genesisHash = blockchainReader.genesisHeader.hash
    val bestBlockNumber = blockchainReader.getBestBlockNumber()
    val forkId = ForkId.create(genesisHash, blockchainConfig)(bestBlockNumber)

    val status = ETH64.Status(
      protocolVersion = negotiatedCapability.version,
      networkId = peerConfiguration.networkId,
      totalDifficulty = chainWeight.totalDifficulty,
      bestHash = bestBlockHeader.hash,
      genesisHash = genesisHash,
      forkId = forkId
    )

    log.debug("Sending status: protocolVersion={}, networkId={}, totalDifficulty={}, bestBlock={}, bestHash={}, genesisHash={}, forkId={}", 
      status.protocolVersion, status.networkId, status.totalDifficulty, bestBlockNumber, 
      bestBlockHeader.hash, genesisHash, forkId)
    status
  }

}
