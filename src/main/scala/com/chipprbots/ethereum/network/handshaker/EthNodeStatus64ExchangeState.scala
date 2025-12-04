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
    negotiatedCapability: Capability,
    supportsSnap: Boolean = false,
    peerCapabilities: List[Capability] = List.empty
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
            // EIP-2124: ForkId validation replaces the fork block exchange for ETH64+
            // When ForkId validation passes, we go directly to connected state
            // without needing to do the old-style fork block handshake
            log.info("STATUS_EXCHANGE: ForkId validation passed - accepting peer connection (skipping fork block exchange per EIP-2124)")
            ConnectedState(PeerInfo.withForkAccepted(RemoteStatus(status, negotiatedCapability, supportsSnap, peerCapabilities)))
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
    
    val chainWeight = blockchainReader
      .getChainWeightByHash(bestBlockHeader.hash)
      .getOrElse(
        throw new IllegalStateException(s"Chain weight not found for hash ${bestBlockHeader.hash}")
      )
    
    val genesisHash = blockchainReader.genesisHeader.hash
    
    // Bootstrap pivot block handling for ForkId calculation
    // When syncing from a low block number after bootstrap, we need to use the bootstrap
    // pivot block for ForkId calculation to ensure compatibility with synced peers.
    // This prevents disconnections due to ForkId mismatches during regular sync.
    //
    // Logic:
    // 1. Get bootstrap pivot block from appStateStorage (if set)
    // 2. Calculate threshold = min(pivot / 10, 100000)
    // 3. If bestBlockNumber < (pivot - threshold), use pivot for ForkId
    // 4. Otherwise, use bestBlockNumber for ForkId
    val bootstrapPivotBlock = appStateStorage.getBootstrapPivotBlock()
    val forkIdBlockNumber = if (bootstrapPivotBlock > 0) {
      val threshold = (bootstrapPivotBlock / 10).min(BigInt(100000))
      if (bestBlockNumber < (bootstrapPivotBlock - threshold)) {
        log.debug(
          "Using bootstrap pivot block {} for ForkId (bestBlock={}, threshold={}, switch point={})",
          bootstrapPivotBlock,
          bestBlockNumber,
          threshold,
          bootstrapPivotBlock - threshold
        )
        bootstrapPivotBlock
      } else {
        log.debug(
          "Using actual block number {} for ForkId (bestBlock={}, pivot={}, threshold={}, switch point={})",
          bestBlockNumber,
          bestBlockNumber,
          bootstrapPivotBlock,
          threshold,
          bootstrapPivotBlock - threshold
        )
        bestBlockNumber
      }
    } else {
      bestBlockNumber
    }
    val forkId = ForkId.create(genesisHash, blockchainConfig)(forkIdBlockNumber)

    val status = ETH64.Status(
      protocolVersion = negotiatedCapability.version,
      networkId = peerConfiguration.networkId,
      totalDifficulty = chainWeight.totalDifficulty,
      bestHash = bestBlockHeader.hash,
      genesisHash = genesisHash,
      forkId = forkId
    )

    log.info(
      "STATUS_EXCHANGE: Sending status - protocolVersion={}, networkId={}, totalDifficulty={}, bestBlock={}, bestHash={}, genesisHash={}, forkId={}",
      status.protocolVersion,
      status.networkId,
      status.totalDifficulty,
      bestBlockNumber,
      bestBlockHeader.hash,
      genesisHash,
      forkId
    )

    // Debug: Log the raw RLP-encoded message bytes for protocol analysis
    // Only compute hex encoding when debug logging is enabled to avoid overhead
    if (log.underlying.isDebugEnabled()) {
      val encodedBytes = status.toBytes
      val hexBytes = org.bouncycastle.util.encoders.Hex.toHexString(encodedBytes)
      log.debug(
        "STATUS_EXCHANGE: Raw RLP bytes (len={}): {}",
        encodedBytes.length,
        if (hexBytes.length > 200) hexBytes.take(200) + "..." else hexBytes
      )
    }

    status
  }

}
