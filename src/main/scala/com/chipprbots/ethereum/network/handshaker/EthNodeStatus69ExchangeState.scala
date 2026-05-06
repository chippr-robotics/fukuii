package com.chipprbots.ethereum.network.handshaker

import cats.effect.SyncIO

import com.chipprbots.ethereum.forkid.Connect
import com.chipprbots.ethereum.forkid.ForkId
import com.chipprbots.ethereum.forkid.ForkIdValidator
import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.PeerInfo
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.RemoteStatus
import com.chipprbots.ethereum.network.p2p.Message
import com.chipprbots.ethereum.network.p2p.MessageSerializable
import com.chipprbots.ethereum.network.p2p.messages.Capability
import com.chipprbots.ethereum.network.p2p.messages.ETH69
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
    peerCapabilities: List[Capability] = List.empty
) extends EtcNodeStatusExchangeState[ETH69.Status] {

  import handshakerConfiguration._

  def applyResponseMessage: PartialFunction[Message, HandshakerState[PeerInfo]] = { case status: ETH69.Status =>
    import ForkIdValidator.syncIoLogger
    log.info(
      "ETH69_STATUS: Received - protocolVersion={}, networkId={}, genesis={}, forkId={}, earliest={}, latest={}, latestHash={}",
      status.protocolVersion,
      status.networkId,
      status.genesisHash,
      status.forkId,
      status.earliestBlock,
      status.latestBlock,
      status.latestBlockHash
    )

    val localGenesisHash = blockchainReader.genesisHeader.hash

    if (status.networkId != peerConfiguration.networkId) {
      log.debug(
        "ETH69_STATUS: NetworkId mismatch! Local: {}, Remote: {} - disconnecting (SUBPROTOCOL_TRIGGERED_MISMATCHED_NETWORK)",
        peerConfiguration.networkId,
        status.networkId
      )
      DisconnectedState[PeerInfo](Disconnect.Reasons.UselessPeer)
    } else if (status.genesisHash != localGenesisHash) {
      log.debug(
        "ETH69_STATUS: Genesis hash mismatch! Local: {}, Remote: {} - disconnecting",
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
        log.debug("ETH69_STATUS: ForkId validation result: {}", validationResult)
        validationResult match {
          case Connect =>
            log.info("ETH69_STATUS: ForkId validation passed - accepting peer")
            // Look up actual PoW TD from local chain DB using the peer's latestBlockHash.
            // Succeeds when the peer's block is in our ChainWeightStorage (peer at or behind our tip).
            // Falls back to block-number proxy when the peer is ahead of us.
            val resolvedChainWeight: ChainWeight =
              blockchainReader
                .getChainWeightByHash(status.latestBlockHash)
                .getOrElse(ChainWeight.totalDifficultyOnly(status.latestBlock))
            log.debug(
              "ETH69_STATUS: chainWeight for peer latestBlockHash {}: {} (localLookup={})",
              status.latestBlockHash,
              resolvedChainWeight,
              blockchainReader.getChainWeightByHash(status.latestBlockHash).isDefined
            )
            ConnectedState(
              PeerInfo.withForkAccepted(
                RemoteStatus.fromETH69Status(
                  status,
                  negotiatedCapability,
                  supportsSnap,
                  peerCapabilities,
                  resolvedChainWeight
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

    // Compute ForkId from current block. Per EIP-2124/EIP-6122 the wire forkId
    // must reflect the local chain state at the head block (head number + head
    // timestamp). go-ethereum's `forkid.NewID(config, genesis, head, time)` uses
    // the block's timestamp directly with no wall-clock fallback (eth/protocols/
    // eth/handler.go). A node at genesis with `unixTimestamp == 0` correctly
    // advertises `ForkId(genesisCRC, Some(firstFork))` — peers reach Connect
    // through EIP-2124's checkSubset/checkSuperset rules. Substituting wall-clock
    // here causes fukuii to advertise a forkId chain its actual chain has not
    // yet crossed, breaking interop with peers (geth, nethermind) whose own
    // chain state lags the wall clock — the symptom on hive sync sims where
    // `sync fukuii from go-ethereum` and `sync fukuii from nethermind` time
    // out at 60s while `sync fukuii from fukuii` succeeds (both sides share
    // the wall-clock skew so they happen to agree).
    val forkId = ForkId.create(genesisHash, blockchainConfig)(bestBlockNumber, bestBlockHeader.unixTimestamp)

    // ETH/69: no TD, use block range instead
    val status = ETH69.Status(
      protocolVersion = negotiatedCapability.version,
      networkId = peerConfiguration.networkId,
      genesisHash = genesisHash,
      forkId = forkId,
      earliestBlock = BigInt(0), // Full archive node
      latestBlock = bestBlockNumber,
      latestBlockHash = bestBlockHeader.hash
    )

    log.info(
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
