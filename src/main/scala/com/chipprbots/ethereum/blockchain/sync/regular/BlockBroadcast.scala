package com.chipprbots.ethereum.blockchain.sync.regular

import org.apache.pekko.actor.ActorRef

import scala.util.Random

import org.slf4j.LoggerFactory

import com.chipprbots.ethereum.blockchain.sync.PeerListSupportNg.PeerWithInfo
import com.chipprbots.ethereum.blockchain.sync.regular.BlockBroadcast.BlockToBroadcast
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.PeerInfo
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.PeerId
import com.chipprbots.ethereum.network.p2p.MessageSerializable
import com.chipprbots.ethereum.network.p2p.messages.Capability
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.NewBlockHashes.BlockHash
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets
import com.chipprbots.ethereum.network.p2p.messages.ETH69

class BlockBroadcast(val networkPeerManager: ActorRef) {
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
  def broadcastBlock(blockToBroadcast: BlockToBroadcast, handshakedPeers: Map[PeerId, PeerWithInfo]): Unit = {
    val peersWithoutBlock = handshakedPeers.filter { case (_, PeerWithInfo(_, peerInfo)) =>
      shouldSendNewBlock(blockToBroadcast, peerInfo)
    }

    broadcastNewBlock(blockToBroadcast, peersWithoutBlock)

    broadcastNewBlockHash(blockToBroadcast, peersWithoutBlock.values.map(_.peer).toSet)

    // ETH/69 (EIP-7642): send BlockRangeUpdate to ETH69 peers (replaces NewBlock).
    val newHeader = blockToBroadcast.block.header
    val bru = ETH69.BlockRangeUpdate(BigInt(0), newHeader.number, newHeader.hash)
    val eth69Peers = peersWithoutBlock.filter { case (_, PeerWithInfo(_, info)) =>
      info.remoteStatus.capability == Capability.ETH69
    }
    if (eth69Peers.nonEmpty) {
      log.info("ETH69_BRU_BROADCAST: block={} hash={} to {} ETH69 peers",
        newHeader.number, newHeader.hash, eth69Peers.size)
      eth69Peers.foreach { case (_, PeerWithInfo(peer, _)) =>
        networkPeerManager ! NetworkPeerManagerActor.SendMessage(bru, peer.id)
      }
    }
  }

  private def shouldSendNewBlock(newBlock: BlockToBroadcast, peerInfo: PeerInfo): Boolean = {
    val blockAhead = newBlock.block.header.number > peerInfo.maxBlockNumber
    // ETH/69 peers: chainWeight may be actual TD (local lookup) or a block-number proxy (peer
    // ahead of us). The proxy case makes the TD comparison always true, spamming every ETH69 peer.
    // Use block-number comparison only for ETH69 — maxBlockNumber is now correct (from latestBlock).
    val heavierChain = peerInfo.remoteStatus.capability != Capability.ETH69 &&
      newBlock.chainWeight > peerInfo.chainWeight
    blockAhead || heavierChain
  }

  private def broadcastNewBlock(blockToBroadcast: BlockToBroadcast, peers: Map[PeerId, PeerWithInfo]): Unit =
    obtainRandomPeerSubset(peers.values.map(_.peer).toSet).foreach { peer =>
      val remoteStatus = peers(peer.id).peerInfo.remoteStatus

      val message: MessageSerializable = remoteStatus.capability match {
        case Capability.ETH63 => blockToBroadcast.as63
        case Capability.ETH64 | Capability.ETH65 | Capability.ETH66 | Capability.ETH67 | Capability.ETH68 |
            Capability.ETH69 =>
          blockToBroadcast.as63
        case Capability.SNAP1 =>
          // SNAP is a satellite protocol for state sync, not for block broadcasting
          // Block broadcasting should use the ETH capability
          blockToBroadcast.as63
      }

      networkPeerManager ! NetworkPeerManagerActor.SendMessage(message, peer.id)
    }

  private def broadcastNewBlockHash(blockToBroadcast: BlockToBroadcast, peers: Set[Peer]): Unit = peers.foreach {
    peer =>
      val newBlockHeader = blockToBroadcast.block.header
      val newBlockHashMsg = ETHPackets.NewBlockHashes.NewBlockHashes(Seq(BlockHash(newBlockHeader.hash, newBlockHeader.number)))
      networkPeerManager ! NetworkPeerManagerActor.SendMessage(newBlockHashMsg, peer.id)
  }

  /** Obtains a random subset of peers. The returned set will verify: subsetPeers.size == sqrt(peers.size)
    *
    * @param peers
    * @return
    *   a random subset of peers
    */
  private[sync] def obtainRandomPeerSubset(peers: Set[Peer]): Set[Peer] = {
    val numberOfPeersToSend = Math.sqrt(peers.size).toInt
    Random.shuffle(peers.toSeq).take(numberOfPeersToSend).toSet
  }
}

object BlockBroadcast {

  /** BlockToBroadcast was created to decouple block information from protocol new block messages (they are different
    * versions of NewBlock msg)
    */
  case class BlockToBroadcast(block: Block, chainWeight: ChainWeight) {
    def as63: ETHPackets.NewBlock = ETHPackets.NewBlock(block, chainWeight.totalDifficulty)
  }
}
