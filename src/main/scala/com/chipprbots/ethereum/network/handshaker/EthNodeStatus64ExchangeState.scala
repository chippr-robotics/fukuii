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
      "STATUS_EXCHANGE: Received status from peer - protocolVersion={}, networkId={}, totalDifficulty={}, bestHash={}, genesisHash={}, forkId={}",
      status.protocolVersion,
      status.networkId,
      status.totalDifficulty,
      status.bestHash,
      status.genesisHash,
      status.forkId
    )

    val localBestBlock = blockchainReader.getBestBlockNumber()
    val localGenesisHash = blockchainReader.genesisHeader.hash
    val localForkId = ForkId.create(localGenesisHash, blockchainConfig)(localBestBlock)

    log.info(
      "STATUS_EXCHANGE: Local state - bestBlock={}, genesisHash={}, localForkId={}",
      localBestBlock,
      localGenesisHash,
      localForkId
    )

    if (status.genesisHash != localGenesisHash) {
      log.warn(
        "STATUS_EXCHANGE: Peer genesis hash mismatch! Local: {}, Remote: {} - disconnecting peer",
        localGenesisHash,
        status.genesisHash
      )
      DisconnectedState[PeerInfo](Disconnect.Reasons.UselessPeer)
    } else {
      (for {
        validationResult <-
          ForkIdValidator.validatePeer[SyncIO](blockchainReader.genesisHeader.hash, blockchainConfig)(
            blockchainReader.getBestBlockNumber(),
            status.forkId
          )
      } yield {
        log.info("STATUS_EXCHANGE: ForkId validation result: {}", validationResult)
        validationResult match {
          case Connect =>
            log.info("STATUS_EXCHANGE: ForkId validation passed - accepting peer connection")
            applyRemoteStatusMessage(RemoteStatus(status, negotiatedCapability))
          case other =>
            log.warn(
              "STATUS_EXCHANGE: ForkId validation failed with result: {} - disconnecting peer as UselessPeer. Local ForkId: {}, Remote ForkId: {}",
              other,
              localForkId,
              status.forkId
            )
            DisconnectedState[PeerInfo](Disconnect.Reasons.UselessPeer)
        }
      }).unsafeRunSync()
    }
  }

  override protected def createStatusMsg(): MessageSerializable = {
    val bestBlockHeader = getBestBlockHeader()
    val bestBlockNumber = blockchainReader.getBestBlockNumber()
    val bootstrapPivotNumber = appStateStorage.getBootstrapPivotBlock()
    val bootstrapPivotHash = appStateStorage.getBootstrapPivotBlockHash()
    
    // If we only have genesis but have a bootstrap pivot, use the pivot for the Status message
    // to avoid fork ID incompatibility and peer disconnections during initial sync
    val (effectiveHash, effectiveNumber) = 
      if (bestBlockNumber == 0 && bootstrapPivotNumber > 0 && bootstrapPivotHash.nonEmpty) {
        log.info(
          "Using bootstrap pivot block {} (hash: {}) for Status message instead of genesis to avoid peer disconnections",
          bootstrapPivotNumber,
          com.chipprbots.ethereum.utils.ByteStringUtils.hash2string(bootstrapPivotHash)
        )
        (bootstrapPivotHash, bootstrapPivotNumber)
      } else {
        (bestBlockHeader.hash, bestBlockNumber)
      }
    
    // Calculate chain weight and fork ID for the effective block
    val (chainWeight, forkId) = if (bestBlockNumber == 0 && bootstrapPivotNumber > 0) {
      // Estimate difficulty: assume average difficulty of ~2 TH for ETC mainnet
      val estimatedTotalDifficulty = bootstrapPivotNumber * BigInt(2000000000000L)
      val weight = com.chipprbots.ethereum.domain.ChainWeight(0, estimatedTotalDifficulty)
      val genesisHash = blockchainReader.genesisHeader.hash
      val forkIdForPivot = ForkId.create(genesisHash, blockchainConfig)(bootstrapPivotNumber)
      (weight, forkIdForPivot)
    } else {
      val weight = blockchainReader
        .getChainWeightByHash(bestBlockHeader.hash)
        .getOrElse(
          throw new IllegalStateException(s"Chain weight not found for hash ${bestBlockHeader.hash}")
        )
      val genesisHash = blockchainReader.genesisHeader.hash
      val forkIdForBest = ForkId.create(genesisHash, blockchainConfig)(bestBlockNumber)
      (weight, forkIdForBest)
    }

    val status = ETH64.Status(
      protocolVersion = negotiatedCapability.version,
      networkId = peerConfiguration.networkId,
      totalDifficulty = chainWeight.totalDifficulty,
      bestHash = effectiveHash,
      genesisHash = blockchainReader.genesisHeader.hash,
      forkId = forkId
    )

    log.info(
      "STATUS_EXCHANGE: Sending status - protocolVersion={}, networkId={}, totalDifficulty={}, bestBlock={}, bestHash={}, genesisHash={}, forkId={}",
      status.protocolVersion,
      status.networkId,
      status.totalDifficulty,
      effectiveNumber,
      effectiveHash,
      blockchainReader.genesisHeader.hash,
      forkId
    )

    if (bestBlockNumber == 0 && bootstrapPivotNumber == 0) {
      log.warn(
        "STATUS_EXCHANGE: WARNING - Sending genesis block as best block! This may cause peer disconnections. " +
        "Best block number: {}, best hash: {}, chain weight TD: {}",
        bestBlockNumber,
        bestBlockHeader.hash,
        chainWeight.totalDifficulty
      )
    }

    status
  }

}
