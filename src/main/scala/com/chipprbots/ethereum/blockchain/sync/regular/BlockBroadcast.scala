package com.chipprbots.ethereum.blockchain.sync.regular

import org.apache.pekko.actor.typed.ActorRef as TypedActorRef

import scala.util.Random

import org.slf4j.LoggerFactory

import com.chipprbots.ethereum.blockchain.sync.PeerListSupportNg.PeerWithInfo
import com.chipprbots.ethereum.blockchain.sync.regular.BlockBroadcast.BlockToBroadcast
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.PeerInfo
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.PeerId
import com.chipprbots.ethereum.network.p2p.MessageSerializable
import com.chipprbots.ethereum.network.p2p.messages.Capability
import com.chipprbots.ethereum.network.p2p.messages.ETH69
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.NewBlockHashes.BlockHash

class BlockBroadcast(
    val networkPeerManager: TypedActorRef[NetworkPeerManagerActor.Command],
    val isPoWChain: Boolean = false
):
  private val log = LoggerFactory.getLogger(getClass)

  /** Broadcasts various NewBlock's messages to handshaked peers, considering that a block should not be sent to a peer
    * that is thought to know it. The hash of the block is sent to all of those peers while the block itself is only
    * sent to the square root of the total number of those peers, with the subset being obtained randomly.
    *
    * @param blockToBroadcast,
    *   block to broadcast
    * @param handshakedPeers,
    *   to which the blocks will be broadcasted to
    */
  def broadcastBlock(blockToBroadcast: BlockToBroadcast, handshakedPeers: Map[PeerId, PeerWithInfo]): Unit =
    val peersWithoutBlock = handshakedPeers.filter { case (_, PeerWithInfo(_, peerInfo)) =>
      shouldSendNewBlock(blockToBroadcast, peerInfo)
    }

    broadcastNewBlock(blockToBroadcast, peersWithoutBlock)

    broadcastNewBlockHash(blockToBroadcast, peersWithoutBlock.values.map(_.peer).toSet)

    // ETH/69 (EIP-7642): send BlockRangeUpdate to ETH69 peers (replaces NewBlock).
    // BRU frequency: PoW (ETC/Mordor) = every block — matches ETH68 NewBlock TD cadence, required
    // for ECBP-1100 chain weight tracking. PoS (ETH/Sepolia) = every 32 blocks — EIP-7642 epoch
    // gate, go-ethereum aligned (shouldSend() returns true only every 32 blocks forward).
    val newHeader = blockToBroadcast.block.header
    val shouldSendBRU = isPoWChain || (newHeader.number.value % 32 == 0)
    if shouldSendBRU then
      val bru = ETH69.BlockRangeUpdate(BigInt(0), newHeader.number.value, newHeader.hash.value)
      val eth69Peers = peersWithoutBlock.filter { case (_, PeerWithInfo(_, info)) =>
        Capability.isEth69Plus(info.remoteStatus.capability)
      }
      if eth69Peers.nonEmpty then
        log.info(
          "ETH69_BRU_BROADCAST: block={} hash={} to {} ETH69 peers (isPoW={})",
          newHeader.number,
          newHeader.hash,
          eth69Peers.size,
          isPoWChain
        )
        eth69Peers.foreach { case (_, PeerWithInfo(peer, _)) =>
          networkPeerManager ! NetworkPeerManagerActor.SendMessageCmd(bru, peer.id)
        }

  /** Announces a CL-canonical head to ALL handshaked peers without sending a NewBlock message.
    *
    * Used on post-merge (PoS / ETH) chains when `forkchoiceUpdated` advances the head: peers that completed the ETH
    * STATUS handshake before the FCU was processed saw `bestBlockNumber=0` (genesis) and never received a corrective
    * announcement. After FCU the normal `broadcastBlock` path is bypassed (engine heads bypass `BlockImporter`), so
    * without this call those peers time out believing fukuii has nothing to offer. See Hive run/sync bug analysis.
    *
    * Wire behaviour (mirrors go-ethereum `handler.BroadcastBlock` head-only path):
    *   - ETH68 (and all non-ETH69) peers → `NewBlockHashes(hash, number)`: the standard head-announce signal.
    *   - ETH69 peers → `BlockRangeUpdate(0, number, hash)`: replaces NewBlock on PoS per EIP-7642.
    *
    * No `shouldSend` gating: a CL head-advance must be announced to every connected peer regardless of what block
    * number that peer last reported. This differs intentionally from `broadcastBlock` which filters by
    * `shouldSendNewBlock`. `broadcastBlock`'s existing behaviour is unchanged.
    *
    * Safety: on PoW chains (ETC/Mordor) `BeaconHead` is never published (`clPivotEnabled=false`), so this method is
    * never called — there is ZERO PoW/ETC regression risk.
    */
  def announceCanonicalHead(head: BlockHeader, handshakedPeers: Map[PeerId, PeerWithInfo]): Unit =
    if handshakedPeers.isEmpty then
      // Logged at INFO (not DEBUG): the empty-peer case is the diagnostic signature of the FCU-before-peer-map race.
      // BlockBroadcasterActor recovers by re-announcing to peers as the scan discovers them; this line records that
      // the immediate FCU-time announce reached nobody so the recovery path is the one that actually delivers.
      log.info("CANONICAL_HEAD_ANNOUNCE: no handshaked peers yet for block {} — deferring to peer-scan", head.number)
    else
      val newBlockHashMsg = ETHPackets.NewBlockHashes.NewBlockHashes(Seq(BlockHash(head.hash.value, head.number.value)))
      val bru = ETH69.BlockRangeUpdate(BigInt(0), head.number.value, head.hash.value)
      log.info(
        "CANONICAL_HEAD_ANNOUNCE: block={} hash={} to {} handshaked peers",
        head.number,
        head.hash,
        handshakedPeers.size
      )
      handshakedPeers.foreach { case (_, PeerWithInfo(peer, peerInfo)) =>
        if Capability.isEth69Plus(peerInfo.remoteStatus.capability) then
          networkPeerManager ! NetworkPeerManagerActor.SendMessageCmd(bru, peer.id)
        else networkPeerManager ! NetworkPeerManagerActor.SendMessageCmd(newBlockHashMsg, peer.id)
      }

  private def shouldSendNewBlock(newBlock: BlockToBroadcast, peerInfo: PeerInfo): Boolean =
    val blockAhead = newBlock.block.header.number.value > peerInfo.maxBlockNumber
    // ETH/69 peers: chainWeight may be actual TD (local lookup) or a block-number proxy (peer
    // ahead of us). The proxy case makes the TD comparison always true, spamming every ETH69 peer.
    // Use block-number comparison only for ETH69 — maxBlockNumber is now correct (from latestBlock).
    val heavierChain = !Capability.isEth69Plus(peerInfo.remoteStatus.capability) &&
      newBlock.chainWeight > peerInfo.chainWeight
    blockAhead || heavierChain

  private def broadcastNewBlock(blockToBroadcast: BlockToBroadcast, peers: Map[PeerId, PeerWithInfo]): Unit =
    obtainRandomPeerSubset(peers.values.map(_.peer).toSet).foreach { peer =>
      val remoteStatus = peers(peer.id).peerInfo.remoteStatus

      val messageOpt: Option[MessageSerializable] = remoteStatus.capability match
        case Capability.ETH63 | Capability.ETH64 | Capability.ETH65 | Capability.ETH66 | Capability.ETH67 |
            Capability.ETH68 =>
          Some(blockToBroadcast.as63)
        case Capability.ETH69 if isPoWChain =>
          Some(blockToBroadcast.as63) // PoW: send NewBlock with TD — ECBP-1100 chain weight signal
        case Capability.ETH69 =>
          None // PoS: no NewBlock — go-ethereum aligned
        case Capability.ETH70 | Capability.ETH71 | Capability.ETH72 if isPoWChain =>
          Some(blockToBroadcast.as63) // PoW: send NewBlock with TD — same as ETH69
        case Capability.ETH70 | Capability.ETH71 | Capability.ETH72 =>
          None // PoS: no NewBlock — same as ETH69. NewBlock is unaffected by EIP-7975/8159/8070.
        case Capability.SNAP1 | Capability.SNAP2 =>
          Some(blockToBroadcast.as63)

      messageOpt.foreach(msg => networkPeerManager ! NetworkPeerManagerActor.SendMessageCmd(msg, peer.id))
    }

  private def broadcastNewBlockHash(blockToBroadcast: BlockToBroadcast, peers: Set[Peer]): Unit = peers.foreach {
    peer =>
      val newBlockHeader = blockToBroadcast.block.header
      val newBlockHashMsg =
        ETHPackets.NewBlockHashes.NewBlockHashes(Seq(BlockHash(newBlockHeader.hash.value, newBlockHeader.number.value)))
      networkPeerManager ! NetworkPeerManagerActor.SendMessageCmd(newBlockHashMsg, peer.id)
  }

  /** Obtains a random subset of peers. The returned set will verify: subsetPeers.size == sqrt(peers.size)
    *
    * @param peers
    * @return
    *   a random subset of peers
    */
  private[sync] def obtainRandomPeerSubset(peers: Set[Peer]): Set[Peer] =
    val numberOfPeersToSend = Math.sqrt(peers.size).toInt
    Random.shuffle(peers.toSeq).take(numberOfPeersToSend).toSet

object BlockBroadcast:

  /** BlockToBroadcast was created to decouple block information from protocol new block messages (they are different
    * versions of NewBlock msg)
    */
  case class BlockToBroadcast(block: Block, chainWeight: ChainWeight):
    def as63: ETHPackets.NewBlock = ETHPackets.NewBlock(block, chainWeight.totalDifficulty.value)
