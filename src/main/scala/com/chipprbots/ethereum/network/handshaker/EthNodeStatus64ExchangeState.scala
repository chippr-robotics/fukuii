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
  
  // Maximum threshold for bootstrap pivot block usage (100,000 blocks)
  // This limits the threshold to prevent using the pivot too long for very high pivot values
  private val MaxBootstrapPivotThreshold = BigInt(100000)

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
    
    // EIP-2124: Use bootstrap pivot block for ForkId calculation when syncing
    // This ensures we advertise a ForkId compatible with synced peers, avoiding
    // immediate disconnection due to ForkId mismatch (issue #574)
    // 
    // During initial sync (both fast sync and regular sync), we use the bootstrap
    // pivot block for ForkId calculation instead of the actual best block number.
    // This prevents peer disconnections that occur when:
    // - Regular sync: Node at block 1-1000 tries to connect to peers at block 19M+
    // - The low block number produces an incompatible ForkId causing peer rejection
    // 
    // We continue using the bootstrap pivot block until the node is within a threshold
    // distance from the pivot, where the threshold is the minimum of (10% of the pivot
    // block number, MaxBootstrapPivotThreshold (100,000) blocks).
    // 
    // For example, if the pivot is at 19,250,000:
    // - 10% = 1,925,000 blocks
    // - threshold = min(1,925,000, 100,000) = 100,000 blocks
    // - Use pivot when: bestBlockNumber < 19,150,000
    // - Switch to actual when: bestBlockNumber >= 19,150,000
    val bootstrapPivotBlock = appStateStorage.getBootstrapPivotBlock()
    val forkIdBlockNumber = if (bootstrapPivotBlock > 0) {
      // Calculate the threshold: maximum distance from pivot block before switching to actual number
      val threshold = (bootstrapPivotBlock / 10).min(MaxBootstrapPivotThreshold)
      val shouldUseBootstrap = bestBlockNumber < (bootstrapPivotBlock - threshold)
      
      if (shouldUseBootstrap) {
        log.info(
          "STATUS_EXCHANGE: Using bootstrap pivot block {} for ForkId calculation (actual best block: {}, threshold: {})",
          bootstrapPivotBlock,
          bestBlockNumber,
          threshold
        )
        bootstrapPivotBlock
      } else {
        log.info(
          "STATUS_EXCHANGE: Node synced close to pivot block - switching to actual block number {} for ForkId (pivot: {}, threshold: {})",
          bestBlockNumber,
          bootstrapPivotBlock,
          threshold
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
