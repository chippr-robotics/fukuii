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
import com.chipprbots.ethereum.network.p2p.messages.ETH69
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets
import com.chipprbots.ethereum.network.p2p.messages.WireProtocol.Disconnect

/** ETH/69 status exchange handler (EIP-7642).
  *
  * Key differences from ETH64-68:
  *   - Status message removes totalDifficulty field
  *   - Status message reorders fields: [version, networkId, genesis, forkId, earliestBlock, latestBlock,
  *     latestBlockHash]
  *   - ForkId validation is still performed
  *   - TD is recovered from local ChainWeightStorage via latestBlockHash when the peer's block is already in our chain;
  *     falls back to block-number proxy otherwise
  */
case class EthNodeStatus69ExchangeState(
    handshakerConfiguration: NetworkHandshakerConfiguration,
    negotiatedCapability: Capability,
    supportsSnap: Boolean = false,
    peerCapabilities: List[Capability] = List.empty,
    clientId: String = ""
) extends NodeStatusExchangeState[ETHPackets.Status69.Status69] {

  import ETHPackets.Status69.Status69._ // toBytes for createStatusMsg
  import handshakerConfiguration._

  def applyResponseMessage: PartialFunction[Message, HandshakerState[PeerInfo]] = {
    // ETH69MessageDecoder path: ETHPackets.Status69 (Phase 2b)
    case status: ETHPackets.Status69.Status69 =>
      handleStatus69Fields(
        status.protocolVersion,
        status.networkId,
        status.genesisHash,
        status.forkId,
        status.earliestBlock,
        status.latestBlock,
        status.latestBlockHash
      )

    // Legacy path: ETH69.Status (old decoder, for backward compat until Phase 3 cap retirement)
    case legacy: ETH69.Status =>
      handleStatus69Fields(
        legacy.protocolVersion,
        legacy.networkId,
        legacy.genesisHash,
        legacy.forkId,
        legacy.earliestBlock,
        legacy.latestBlock,
        legacy.latestBlockHash
      )
  }

  private def handleStatus69Fields(
      protocolVersion: Int,
      networkId: Long,
      genesisHash: org.apache.pekko.util.ByteString,
      forkId: ForkId,
      earliestBlock: BigInt,
      latestBlock: BigInt,
      latestBlockHash: org.apache.pekko.util.ByteString
  ): HandshakerState[PeerInfo] = {
    import ForkIdValidator.syncIoLogger
    log.debug(
      "ETH69_STATUS: Received - protocolVersion={}, networkId={}, genesis={}, forkId={}, earliest={}, latest={}, latestHash={}",
      protocolVersion,
      networkId,
      genesisHash,
      forkId,
      earliestBlock,
      latestBlock,
      latestBlockHash
    )

    val localGenesisHash = blockchainReader.genesisHeader.hash

    if (networkId != peerConfiguration.networkId) {
      log.debug(
        "ETH69_STATUS: NetworkId mismatch! Local: {}, Remote: {} - disconnecting",
        peerConfiguration.networkId,
        networkId
      )
      DisconnectedState[PeerInfo](Disconnect.Reasons.UselessPeer)
    } else if (genesisHash != localGenesisHash) {
      log.debug(
        "ETH69_STATUS: Genesis hash mismatch! Local: {}, Remote: {} - disconnecting",
        localGenesisHash,
        genesisHash
      )
      DisconnectedState[PeerInfo](Disconnect.Reasons.UselessPeer)
    } else {
      (for {
        validationResult <-
          ForkIdValidator.validatePeer[SyncIO](blockchainReader.genesisHeader.hash, blockchainConfig)(
            blockchainReader.getBestBlockNumber(),
            forkId
          )
      } yield {
        log.debug("ETH69_STATUS: ForkId validation result: {}", validationResult)
        validationResult match {
          case Connect =>
            log.info("ETH69_STATUS: ForkId validation passed - accepting peer")
            val (resolvedChainWeight, resolvedSource) = blockchainReader.resolveETH69ChainWeight(
              latestBlockHash,
              latestBlock,
              isPoWChain = blockchainConfig.terminalTotalDifficulty.isEmpty
            )
            log.debug(
              "ETH69_STATUS: TD resolved - totalDifficulty={}, latestBlock={}, source={}",
              resolvedChainWeight.totalDifficulty,
              latestBlock,
              resolvedSource
            )
            ConnectedState(
              PeerInfo.withForkAccepted(
                RemoteStatus(
                  negotiatedCapability,
                  networkId,
                  resolvedChainWeight,
                  latestBlockHash,
                  genesisHash,
                  supportsSnap,
                  peerCapabilities,
                  latestBlock = Some(latestBlock),
                  remoteClientId = clientId
                )
              )
            )
          case other =>
            log.debug("ETH69_STATUS: ForkId validation failed: {} - disconnecting", other)
            DisconnectedState[PeerInfo](Disconnect.Reasons.UselessPeer)
        }
      }).unsafeRunSync()
    }
  }

  override protected def createStatusMsg(): MessageSerializable = {
    val bestBlockHeader = getBestBlockHeader()
    val bestBlockNumber = blockchainReader.getBestBlockNumber()
    val genesisHash = blockchainReader.genesisHeader.hash

    // Compute ForkId from current block (same as ETH64-68)
    val forkIdTimestamp =
      if (bestBlockHeader.unixTimestamp == 0L) System.currentTimeMillis() / 1000 else bestBlockHeader.unixTimestamp
    val forkId = ForkId.create(genesisHash, blockchainConfig)(bestBlockNumber, forkIdTimestamp)

    // ETH/69: no TD, use block range instead. Use ETHPackets.Status69.Status69 (canonical type).
    val status = ETHPackets.Status69.Status69(
      protocolVersion = negotiatedCapability.version,
      networkId = peerConfiguration.networkId,
      genesisHash = genesisHash,
      forkId = forkId,
      earliestBlock = BigInt(0), // Full archive node
      latestBlock = bestBlockNumber,
      latestBlockHash = bestBlockHeader.hash
    )

    log.debug(
      "ETH69_STATUS: Sending - networkId={}, genesis={}, forkId={}, earliest={}, latest={}, latestHash={}",
      status.networkId,
      genesisHash,
      forkId,
      status.earliestBlock,
      status.latestBlock,
      bestBlockHeader.hash
    )

    status
  }
}
