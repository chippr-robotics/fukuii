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
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets
import com.chipprbots.ethereum.network.p2p.messages.WireProtocol.Disconnect

/** Handles ETH/64-68 STATUS handshake.
  *
  * Wire format: [version, networkId, td, bestHash, genesis, forkId] (6 fields, TD present).
  * ETC is PoW — TD is permanent, not a legacy field.
  *
  * Renamed from EthNodeStatus64ExchangeState to reflect actual minimum version support (ETH68).
  * Uses ETHPackets.Status68 to match what ETH68MessageDecoder decodes (no ETH64 import needed).
  */
case class EthNodeStatus68ExchangeState(
    handshakerConfiguration: NetworkHandshakerConfiguration,
    negotiatedCapability: Capability,
    supportsSnap: Boolean = false,
    peerCapabilities: List[Capability] = List.empty,
    clientId: String = ""
) extends EtcNodeStatusExchangeState[ETHPackets.Status68.Status68] {

  import ETHPackets.Status68.Status68._  // toBytes for createStatusMsg
  import handshakerConfiguration._

  def applyResponseMessage: PartialFunction[Message, HandshakerState[PeerInfo]] = {
    // ETH68 decoder path: ETHPackets.Status68 (ETH68MessageDecoder returns this)
    case status: ETHPackets.Status68.Status68 =>
      handleStatus68Fields(status.protocolVersion, status.networkId.toLong, status.totalDifficulty,
        status.bestHash, status.genesisHash, status.forkId)

    // Legacy decoder path: ETH64.Status (ETH64-67 decoders still return this)
    // Phase 3 (cap retirement) will remove this arm.
    case legacyStatus: ETH64.Status =>
      handleStatus68Fields(legacyStatus.protocolVersion, legacyStatus.networkId, legacyStatus.totalDifficulty,
        legacyStatus.bestHash, legacyStatus.genesisHash, legacyStatus.forkId)
  }

  private def handleStatus68Fields(
      protocolVersion: Int, networkId: Long, totalDifficulty: BigInt,
      bestHash: org.apache.pekko.util.ByteString, genesisHash: org.apache.pekko.util.ByteString,
      forkId: ForkId
  ): HandshakerState[PeerInfo] = {
    import ForkIdValidator.syncIoLogger
    log.info(
      "ETH{}_STATUS: Received - totalDifficulty={}, networkId={}, bestHash={}, genesisHash={}, forkId={}",
      protocolVersion, totalDifficulty, networkId, bestHash, genesisHash, forkId
    )

    val localBestBlock = blockchainReader.getBestBlockNumber()
    val localGenesisHash = blockchainReader.genesisHeader.hash
    val storedTimestamp = blockchainReader.getBlockHeaderByNumber(localBestBlock).map(_.unixTimestamp).getOrElse(0L)
    val localBestTimestamp = if (storedTimestamp == 0L) System.currentTimeMillis() / 1000 else storedTimestamp
    val localForkId = ForkId.create(localGenesisHash, blockchainConfig)(localBestBlock, localBestTimestamp)

    log.debug(
      "ETH{}_STATUS: Local state - bestBlock={}, genesisHash={}, localForkId={}",
      protocolVersion, localBestBlock, localGenesisHash, localForkId
    )

    if (networkId != peerConfiguration.networkId) {
      log.debug("ETH{}_STATUS: NetworkId mismatch - local={}, remote={} - disconnecting",
        protocolVersion, peerConfiguration.networkId, networkId)
      DisconnectedState[PeerInfo](Disconnect.Reasons.UselessPeer)
    } else if (genesisHash != localGenesisHash) {
      log.debug("ETH{}_STATUS: Genesis mismatch - local={}, remote={} - disconnecting",
        protocolVersion, localGenesisHash, genesisHash)
      DisconnectedState[PeerInfo](Disconnect.Reasons.UselessPeer)
    } else {
      (for {
        validationResult <-
          ForkIdValidator.validatePeer[SyncIO](blockchainReader.genesisHeader.hash, blockchainConfig)(
            blockchainReader.getBestBlockNumber(), forkId
          )
      } yield {
        log.debug("STATUS_EXCHANGE: ForkId validation result: {}", validationResult)
        validationResult match {
          case Connect =>
            log.info("ETH{}_STATUS: Accepted - totalDifficulty={}, latestBlock={} (forkId ok)",
              protocolVersion, totalDifficulty, bestHash)
            ConnectedState(
              PeerInfo.withForkAccepted(RemoteStatus(
                negotiatedCapability,
                networkId,
                com.chipprbots.ethereum.domain.ChainWeight.totalDifficultyOnly(totalDifficulty),
                bestHash,
                genesisHash,
                supportsSnap,
                peerCapabilities,
                remoteClientId = clientId
              ))
            )
          case other =>
            log.debug(
              "STATUS_EXCHANGE: ForkId validation failed: {} - disconnecting. Local: {}, Remote: {}",
              other, localForkId, forkId
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
            s"advertising fallback TD=${ttdFallback.totalDifficulty} in ETH/68 STATUS"
        )
        ttdFallback
      }

    val genesisHash = blockchainReader.genesisHeader.hash

    // ALIGNMENT WITH CORE-GETH: Use actual current block number for ForkId calculation.
    // Core-geth uses head.Number.Uint64() and head.Time for forkID — not checkpoints.
    val forkIdBlockNumber = bestBlockNumber
    val forkIdTimestamp =
      if (bestBlockHeader.unixTimestamp == 0L) System.currentTimeMillis() / 1000 else bestBlockHeader.unixTimestamp
    val forkId = ForkId.create(genesisHash, blockchainConfig)(forkIdBlockNumber, forkIdTimestamp)

    val status = ETHPackets.Status68.Status68(
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
