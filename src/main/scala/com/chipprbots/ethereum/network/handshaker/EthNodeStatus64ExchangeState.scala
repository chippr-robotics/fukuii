package com.chipprbots.ethereum.network.handshaker

import cats.effect.SyncIO

import com.chipprbots.ethereum.forkid.Connect
import com.chipprbots.ethereum.forkid.ForkId
import com.chipprbots.ethereum.forkid.ForkIdValidator
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.PeerInfo
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.RemoteStatus
import com.chipprbots.ethereum.network.p2p.Message
import com.chipprbots.ethereum.network.p2p.MessageSerializable
import com.chipprbots.ethereum.network.p2p.messages.Capability
import com.chipprbots.ethereum.network.p2p.messages.ETH64
import com.chipprbots.ethereum.network.p2p.messages.WireProtocol.Disconnect

case class EthNodeStatus64ExchangeState(
    handshakerConfiguration: NetworkHandshakerConfiguration,
    negotiatedCapability: Capability,
    supportsSnap: Boolean = false,
    peerCapabilities: List[Capability] = List.empty
) extends EtcNodeStatusExchangeState[ETH64.Status] {

  import handshakerConfiguration._

  def applyResponseMessage: PartialFunction[Message, HandshakerState[PeerInfo]] = { case status: ETH64.Status =>
    import ForkIdValidator.syncIoLogger
    log.info(
      "ETH{}_STATUS: Received - totalDifficulty={}, networkId={}, bestHash={}, genesisHash={}, forkId={}",
      status.protocolVersion,
      status.totalDifficulty,
      status.networkId,
      status.bestHash,
      status.genesisHash,
      status.forkId
    )

    val localBestBlock = blockchainReader.getBestBlockNumber()
    val localGenesisHash = blockchainReader.genesisHeader.hash
    // Use current system time if best block is genesis (timestamp 0) — this happens at startup
    // before Engine API imports any blocks. Without this, ForkID incorrectly reports pre-Shanghai
    // and all post-merge peers reject us.
    val storedTimestamp = blockchainReader.getBlockHeaderByNumber(localBestBlock).map(_.unixTimestamp).getOrElse(0L)
    val localBestTimestamp = if (storedTimestamp == 0L) System.currentTimeMillis() / 1000 else storedTimestamp
    val localForkId = ForkId.create(localGenesisHash, blockchainConfig)(localBestBlock, localBestTimestamp)

    log.info(
      "ETH{}_STATUS: Local state - bestBlock={}, genesisHash={}, localForkId={}",
      status.protocolVersion,
      localBestBlock,
      localGenesisHash,
      localForkId
    )

    if (status.networkId != peerConfiguration.networkId) {
      log.info(
        "ETH{}_STATUS: NetworkId mismatch - local={}, remote={} - disconnecting",
        status.protocolVersion,
        peerConfiguration.networkId,
        status.networkId
      )
      DisconnectedState[PeerInfo](Disconnect.Reasons.UselessPeer)
    } else if (status.genesisHash != localGenesisHash) {
      log.info(
        "ETH{}_STATUS: Genesis mismatch - local={}, remote={} - disconnecting",
        status.protocolVersion,
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
        log.debug("STATUS_EXCHANGE: ForkId validation result: {}", validationResult)
        validationResult match {
          case Connect =>
            log.info(
              "ETH{}_STATUS: Accepted - totalDifficulty={}, latestBlock={} (forkId ok)",
              status.protocolVersion,
              status.totalDifficulty,
              status.bestHash
            )
            ConnectedState(
              PeerInfo.withForkAccepted(RemoteStatus(status, negotiatedCapability, supportsSnap, peerCapabilities))
            )
          case other =>
            log.debug(
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

    // ChainWeightStorage isn't populated for blocks reached via SNAP sync (the pivot block
    // has no upstream PoW chain in our DB to sum difficulties over). On post-merge chains
    // this never gets backfilled because we don't import pre-merge blocks.
    //
    // Fallback ladder when chain weight isn't stored:
    //   1. Post-merge chains (terminalTotalDifficulty defined) → advertise TTD. Peers
    //      validate roughly that TD >= TTD post-merge; advertising 0 caused mass
    //      "Useless peer" disconnects on sepolia 2026-05-14 (~15 peers in 8 seconds).
    //   2. Pre-merge chains (TTD = None) → fall back to ChainWeight.zero. This is the
    //      old throw-path semantically; the handshake will likely still fail downstream
    //      on a peer's TD check, but at least we don't crash the PeerActor with an
    //      uncaught exception.
    //
    // ForkId remains the authoritative compat signal (EIP-2124); TD field is advisory.
    val chainWeight = blockchainReader
      .getChainWeightByHash(bestBlockHeader.hash)
      .getOrElse {
        val ttdFallback = blockchainConfig.terminalTotalDifficulty
          .map(com.chipprbots.ethereum.domain.ChainWeight.totalDifficultyOnly)
          .getOrElse(com.chipprbots.ethereum.domain.ChainWeight.zero)
        log.debug(
          s"Chain weight not stored for best block ${bestBlockHeader.hash} (SNAP-sync state); " +
            s"advertising fallback TD=${ttdFallback.totalDifficulty} in ETH/64-68 STATUS"
        )
        ttdFallback
      }

    val genesisHash = blockchainReader.genesisHeader.hash

    // ALIGNMENT WITH CORE-GETH: Use actual current block number for ForkId calculation
    // Core-geth implementation (eth/handler.go):
    //   head = h.chain.CurrentHeader()
    //   number = head.Number.Uint64()
    //   forkID := forkid.NewID(h.chain.Config(), genesis, number, head.Time)
    //
    // Core-geth does NOT use checkpoints or pivot blocks for status messages.
    // It always uses the actual current block for both bestHash and ForkId calculation.
    //
    // Previous implementation used bootstrap pivot block for ForkId to avoid peer
    // disconnections at genesis, but this creates a mismatch with core-geth behavior
    // where ForkId and bestHash refer to different blocks.
    //
    // To align with core-geth: Use actual bestBlockNumber for ForkId calculation.
    val forkIdBlockNumber = bestBlockNumber
    // Use system time when at genesis to correctly advertise post-merge fork status
    val forkIdTimestamp =
      if (bestBlockHeader.unixTimestamp == 0L) System.currentTimeMillis() / 1000 else bestBlockHeader.unixTimestamp
    val forkId = ForkId.create(genesisHash, blockchainConfig)(forkIdBlockNumber, forkIdTimestamp)

    val status = ETH64.Status(
      protocolVersion = negotiatedCapability.version,
      networkId = peerConfiguration.networkId,
      totalDifficulty = chainWeight.totalDifficulty,
      bestHash = bestBlockHeader.hash,
      genesisHash = genesisHash,
      forkId = forkId
    )

    log.info(
      "ETH{}_STATUS: Sending - totalDifficulty={}, networkId={}, bestBlock={}, bestHash={}, genesisHash={}, forkId={}",
      status.protocolVersion,
      status.totalDifficulty,
      status.networkId,
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
