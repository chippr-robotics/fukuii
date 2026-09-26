package com.chipprbots.ethereum.network.handshaker

import cats.effect.SyncIO

import com.chipprbots.ethereum.forkid.ForkIdValidationResult.Connect
import com.chipprbots.ethereum.forkid.ForkId
import com.chipprbots.ethereum.forkid.ForkIdValidator
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.PeerInfo
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.RemoteStatus
import com.chipprbots.ethereum.network.p2p.Message
import com.chipprbots.ethereum.network.p2p.MessageSerializable
import com.chipprbots.ethereum.network.p2p.messages.Capability
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets
import com.chipprbots.ethereum.network.p2p.messages.WireProtocol.Disconnect

/** ETH/70, ETH/71, and ETH/72 status exchange handler.
  *
  * One class serves all three versions because none of them change the Status wire format: go-ethereum defines exactly
  * one `StatusPacket` struct spanning ETH69-72 (eth/protocols/eth/protocol.go — `Handshake()` sends it unconditionally
  * regardless of `p.version`). The differences between 70/71/72 are all in *other* messages (receipts partial-delivery,
  * block access lists, cell exchange) — see MessageDecoders.scala's ETH70/71/72MessageDecoder and ETHPackets.scala's
  * Status70 doc comment for why there is no Status71/Status72 type.
  *
  * `negotiatedCapability` (passed in by HelloExchangeState per the actual negotiated version) is what makes this class
  * version-correct despite being shared: it is threaded straight into the outbound Status's `protocolVersion` field and
  * into `RemoteStatus`, so a peer that negotiated eth/71 gets an eth/71 Status and a PeerInfo tagged ETH71 — nothing
  * here hardcodes "70".
  *
  * Key differences from ETH64-68 (same as ETH69):
  *   - Status message removes totalDifficulty field
  *   - Status message reorders fields: [version, networkId, genesis, forkId, earliestBlock, latestBlock,
  *     latestBlockHash]
  *   - ForkId validation is still performed
  *   - TD is recovered from local ChainWeightStorage via latestBlockHash when the peer's block is already in our chain;
  *     falls back to block-number proxy otherwise (BlockchainReader.resolveETH69ChainWeight — the method name predates
  *     ETH70+ but its logic is generic to any "no TD on the wire" status, not ETH69-specific)
  */
case class EthNodeStatus70ExchangeState(
    handshakerConfiguration: NetworkHandshakerConfiguration,
    negotiatedCapability: Capability,
    supportsSnap: Boolean = false,
    peerCapabilities: List[Capability] = List.empty,
    clientId: String = ""
) extends NodeStatusExchangeState[ETHPackets.Status70.Status70]:

  import ETHPackets.Status70.Status70.* // toBytes for createStatusMsg
  import handshakerConfiguration.*

  private val logPrefix: String = s"ETH_STATUS[${negotiatedCapability}]"

  def applyResponseMessage: PartialFunction[Message, HandshakerState[PeerInfo]] = {
    case status: ETHPackets.Status70.Status70 =>
      handleStatusFields(
        status.protocolVersion,
        status.networkId,
        status.genesisHash,
        status.forkId,
        status.earliestBlock,
        status.latestBlock,
        status.latestBlockHash
      )
  }

  private def handleStatusFields(
      protocolVersion: Int,
      networkId: Long,
      genesisHash: org.apache.pekko.util.ByteString,
      forkId: ForkId,
      earliestBlock: BigInt,
      latestBlock: BigInt,
      latestBlockHash: org.apache.pekko.util.ByteString
  ): HandshakerState[PeerInfo] =
    import ForkIdValidator.syncIoLogger
    log.debug(
      "{}: Received - protocolVersion={}, networkId={}, genesis={}, forkId={}, earliest={}, latest={}, latestHash={}",
      logPrefix,
      protocolVersion,
      networkId,
      genesisHash,
      forkId,
      earliestBlock,
      latestBlock,
      latestBlockHash
    )

    val localGenesisHash = blockchainReader.genesisHeader.hash.value

    if networkId != peerConfiguration.networkId then
      log.debug(
        "{}: NetworkId mismatch! Local: {}, Remote: {} - disconnecting",
        logPrefix,
        peerConfiguration.networkId,
        networkId
      )
      DisconnectedState[PeerInfo](Disconnect.Reasons.UselessPeer)
    else if genesisHash != localGenesisHash then
      log.debug(
        "{}: Genesis hash mismatch! Local: {}, Remote: {} - disconnecting",
        logPrefix,
        localGenesisHash,
        genesisHash
      )
      DisconnectedState[PeerInfo](Disconnect.Reasons.UselessPeer)
    else
      (for validationResult <-
          ForkIdValidator.validatePeer[SyncIO](
            blockchainReader.genesisHeader.hash.value,
            blockchainReader.genesisHeader.unixTimestamp.toLong,
            blockchainConfig
          )(
            blockchainReader.getBestBlockNumber,
            forkId
          )
      yield
        log.debug("{}: ForkId validation result: {}", logPrefix, validationResult)
        validationResult match
          case Connect =>
            log.info("{}: ForkId validation passed - accepting peer", logPrefix)
            val (resolvedChainWeight, resolvedSource) = blockchainReader.resolveETH69ChainWeight(
              latestBlockHash,
              latestBlock,
              isPoWChain = blockchainConfig.terminalTotalDifficulty.isEmpty
            )
            log.debug(
              "{}: TD resolved - totalDifficulty={}, latestBlock={}, source={}",
              logPrefix,
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
            log.debug("{}: ForkId validation failed: {} - disconnecting", logPrefix, other)
            DisconnectedState[PeerInfo](Disconnect.Reasons.UselessPeer)
      ).unsafeRunSync()

  override protected def createStatusMsg(): MessageSerializable =
    val bestBlockHeader = getBestBlockHeader()
    val bestBlockNumber = blockchainReader.getBestBlockNumber
    val genesisHeader = blockchainReader.genesisHeader
    val genesisHash = genesisHeader.hash.value

    // Compute ForkId from current block (same as ETH64-69). `bestBlockHeader` is a real header,
    // so a zero timestamp is the chain's actual genesis time, not a missing value.
    val forkId =
      ForkId.create(genesisHash, genesisHeader.unixTimestamp.toLong, blockchainConfig)(
        bestBlockNumber,
        bestBlockHeader.unixTimestamp.toLong
      )

    val status = ETHPackets.Status70.Status70(
      protocolVersion = negotiatedCapability.version,
      networkId = peerConfiguration.networkId,
      genesisHash = genesisHash,
      forkId = forkId,
      earliestBlock = BigInt(0), // Full archive node
      latestBlock = bestBlockNumber,
      latestBlockHash = bestBlockHeader.hash.value
    )

    log.debug(
      "{}: Sending - networkId={}, genesis={}, forkId={}, earliest={}, latest={}, latestHash={}",
      logPrefix,
      status.networkId,
      genesisHash,
      forkId,
      status.earliestBlock,
      status.latestBlock,
      bestBlockHeader.hash
    )

    status
