package com.chipprbots.ethereum.blockchain.sync.regular

import org.apache.pekko.actor.ActorRef

import scala.util.Random

import com.chipprbots.ethereum.blockchain.sync.PeerListSupportNg.PeerWithInfo
import com.chipprbots.ethereum.blockchain.sync.regular.BlockBroadcast.BlockToBroadcast
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.PeerInfo
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.PeerId
import com.chipprbots.ethereum.network.p2p.MessageSerializable
import com.chipprbots.ethereum.network.p2p.messages.BaseETH6XMessages
import com.chipprbots.ethereum.network.p2p.messages.Capability
import com.chipprbots.ethereum.network.p2p.messages.ETH62
import com.chipprbots.ethereum.network.p2p.messages.ETH62.BlockHash

class BlockBroadcast(val networkPeerManager: ActorRef) {

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
  }

  private def shouldSendNewBlock(newBlock: BlockToBroadcast, peerInfo: PeerInfo): Boolean =
    newBlock.block.header.number > peerInfo.maxBlockNumber ||
      newBlock.chainWeight > peerInfo.chainWeight

  private def broadcastNewBlock(blockToBroadcast: BlockToBroadcast, peers: Map[PeerId, PeerWithInfo]): Unit =
    obtainRandomPeerSubset(peers.values.map(_.peer).toSet).foreach { peer =>
      val remoteStatus = peers(peer.id).peerInfo.remoteStatus

      val message: MessageSerializable = remoteStatus.capability match {
        case Capability.ETH63 => blockToBroadcast.as63
        case Capability.ETH64 | Capability.ETH65 | Capability.ETH66 | Capability.ETH67 | Capability.ETH68 =>
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
      val newBlockHashMsg = ETH62.NewBlockHashes(Seq(BlockHash(newBlockHeader.hash, newBlockHeader.number)))
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
    def as63: BaseETH6XMessages.NewBlock = BaseETH6XMessages.NewBlock(block, chainWeight.totalDifficulty)
  }
}
