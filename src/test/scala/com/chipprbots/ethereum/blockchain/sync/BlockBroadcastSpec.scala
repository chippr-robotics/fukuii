package com.chipprbots.ethereum.blockchain.sync

import java.net.InetSocketAddress

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.testkit.TestKit
import org.apache.pekko.testkit.TestProbe

import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.WithActorSystemShutDown
import com.chipprbots.ethereum.testing.Tags._
import com.chipprbots.ethereum.blockchain.sync.PeerListSupportNg.PeerWithInfo
import com.chipprbots.ethereum.blockchain.sync.regular.BlockBroadcast
import com.chipprbots.ethereum.blockchain.sync.regular.BlockBroadcast.BlockToBroadcast
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.BlockBody
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.PeerInfo
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.RemoteStatus
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.PeerId
import com.chipprbots.ethereum.network.p2p.messages.BaseETH6XMessages
import com.chipprbots.ethereum.network.p2p.messages.Capability
import com.chipprbots.ethereum.network.p2p.messages.ETH62
import com.chipprbots.ethereum.network.p2p.messages.ETH62.NewBlockHashes

class BlockBroadcastSpec
    extends TestKit(ActorSystem("BlockBroadcastSpec_System"))
    with AnyFlatSpecLike
    with WithActorSystemShutDown
    with Matchers {

  it should "send a new block when it is not known by the peer (known by comparing chain weights)" taggedAs (
    UnitTest,
    SyncTest
  ) in new TestSetup {
    // given
    // Block that should be sent as it's total difficulty is higher than known by peer
    val blockHeader: BlockHeader = baseBlockHeader.copy(number = initialPeerInfo.maxBlockNumber - 3)
    val newBlockNewHashes: NewBlockHashes = NewBlockHashes(Seq(ETH62.BlockHash(blockHeader.hash, blockHeader.number)))
    val chainWeight = initialPeerInfo.chainWeight.increaseTotalDifficulty(2)
    val block = Block(blockHeader, BlockBody(Nil, Nil))
    val newBlockMsg = BaseETH6XMessages.NewBlock(block, chainWeight.totalDifficulty)

    // when
    blockBroadcast.broadcastBlock(
      BlockToBroadcast(block, chainWeight),
      Map(peer.id -> PeerWithInfo(peer, initialPeerInfo))
    )

    // then
    networkPeerManagerProbe.expectMsg(NetworkPeerManagerActor.SendMessage(newBlockMsg, peer.id))
    networkPeerManagerProbe.expectMsg(NetworkPeerManagerActor.SendMessage(newBlockNewHashes, peer.id))
    networkPeerManagerProbe.expectNoMessage()
  }

  it should "send a new block when it is not known by the peer (known by comparing chain weights) (ETH63)" taggedAs (
    UnitTest,
    SyncTest
  ) in new TestSetup {
    // given
    // Block that should be sent as it's total difficulty is higher than known by peer
    val blockHeader: BlockHeader = baseBlockHeader.copy(number = initialPeerInfo.maxBlockNumber - 3)
    val newBlockNewHashes: NewBlockHashes = NewBlockHashes(Seq(ETH62.BlockHash(blockHeader.hash, blockHeader.number)))
    val peerInfo: PeerInfo = initialPeerInfo
      .copy(remoteStatus = peerStatus.copy(capability = Capability.ETH63))
      .withChainWeight(ChainWeight.totalDifficultyOnly(initialPeerInfo.chainWeight.totalDifficulty))
    val block = Block(blockHeader, BlockBody(Nil, Nil))
    val newBlockMsg = BaseETH6XMessages.NewBlock(block, peerInfo.chainWeight.totalDifficulty + 2)

    // when
    blockBroadcast.broadcastBlock(
      BlockToBroadcast(block, ChainWeight.totalDifficultyOnly(newBlockMsg.totalDifficulty)),
      Map(peer.id -> PeerWithInfo(peer, peerInfo))
    )

    // then
    networkPeerManagerProbe.expectMsg(NetworkPeerManagerActor.SendMessage(newBlockMsg, peer.id))
    networkPeerManagerProbe.expectMsg(NetworkPeerManagerActor.SendMessage(newBlockNewHashes, peer.id))
    networkPeerManagerProbe.expectNoMessage()
  }

  it should "not send a new block when it is known by the peer (known by comparing total difficulties)" taggedAs (
    UnitTest,
    SyncTest
  ) in new TestSetup {
    // given
    // Block that shouldn't be sent as it's number and total difficulty is lower than known by peer
    val blockHeader: BlockHeader = baseBlockHeader.copy(number = initialPeerInfo.maxBlockNumber - 2)
    val chainWeight = initialPeerInfo.chainWeight.increaseTotalDifficulty(-2)
    val block = Block(blockHeader, BlockBody(Nil, Nil))

    // when
    blockBroadcast.broadcastBlock(
      BlockToBroadcast(block, chainWeight),
      Map(peer.id -> PeerWithInfo(peer, initialPeerInfo))
    )

    // then
    networkPeerManagerProbe.expectNoMessage()
  }

  it should "send a new block when it is not known by the peer (known by comparing max block number)" taggedAs (
    UnitTest,
    SyncTest
  ) in new TestSetup {
    // given
    val blockHeader: BlockHeader = baseBlockHeader.copy(number = initialPeerInfo.maxBlockNumber + 4)
    val newBlockNewHashes: NewBlockHashes = NewBlockHashes(Seq(ETH62.BlockHash(blockHeader.hash, blockHeader.number)))
    val chainWeight = initialPeerInfo.chainWeight.increaseTotalDifficulty(-2)
    val block = Block(blockHeader, BlockBody(Nil, Nil))
    val newBlockMsg = BaseETH6XMessages.NewBlock(block, chainWeight.totalDifficulty)

    // when
    blockBroadcast.broadcastBlock(
      BlockToBroadcast(block, chainWeight),
      Map(peer.id -> PeerWithInfo(peer, initialPeerInfo))
    )

    // then
    networkPeerManagerProbe.expectMsg(NetworkPeerManagerActor.SendMessage(newBlockMsg, peer.id))
    networkPeerManagerProbe.expectMsg(NetworkPeerManagerActor.SendMessage(newBlockNewHashes, peer.id))
    networkPeerManagerProbe.expectNoMessage()
  }

  it should "not send a new block only when it is known by the peer (known by comparing max block number)" taggedAs (
    UnitTest,
    SyncTest
  ) in new TestSetup {
    // given
    // Block should already be known by the peer due to max block known
    val blockHeader: BlockHeader = baseBlockHeader.copy(number = initialPeerInfo.maxBlockNumber - 2)
    val chainWeight = initialPeerInfo.chainWeight.increaseTotalDifficulty(-2)
    val block = Block(blockHeader, BlockBody(Nil, Nil))

    // when
    blockBroadcast.broadcastBlock(
      BlockToBroadcast(block, chainWeight),
      Map(peer.id -> PeerWithInfo(peer, initialPeerInfo))
    )

    // then
    networkPeerManagerProbe.expectNoMessage()
  }

  it should "send block hashes to all peers while the blocks only to sqrt of them" taggedAs (
    UnitTest,
    SyncTest
  ) in new TestSetup {
    // given
    val firstHeader: BlockHeader = baseBlockHeader.copy(number = initialPeerInfo.maxBlockNumber + 4)
    val firstBlockNewHashes: NewBlockHashes = NewBlockHashes(Seq(ETH62.BlockHash(firstHeader.hash, firstHeader.number)))
    val firstChainWeight = initialPeerInfo.chainWeight.increaseTotalDifficulty(-2)
    val firstBlock = Block(firstHeader, BlockBody(Nil, Nil))
    val firstBlockMsg = BaseETH6XMessages.NewBlock(firstBlock, firstChainWeight.totalDifficulty)

    val peer2Probe: TestProbe = TestProbe()
    val peer2: Peer = Peer(PeerId("peer2"), new InetSocketAddress("127.0.0.1", 0), peer2Probe.ref, false)
    val peer3Probe: TestProbe = TestProbe()
    val peer3: Peer = Peer(PeerId("peer3"), new InetSocketAddress("127.0.0.1", 0), peer3Probe.ref, false)
    val peer4Probe: TestProbe = TestProbe()
    val peer4: Peer = Peer(PeerId("peer4"), new InetSocketAddress("127.0.0.1", 0), peer4Probe.ref, false)

    // when
    val peers: Seq[Peer] = Seq(peer, peer2, peer3, peer4)
    val peersIds: Seq[PeerId] = peers.map(_.id)
    val peersWithInfo: Map[PeerId, PeerWithInfo] =
      peers.map(peer => peer.id -> PeerWithInfo(peer, initialPeerInfo)).toMap
    blockBroadcast.broadcastBlock(BlockToBroadcast(firstBlock, firstChainWeight), peersWithInfo)

    // then
    // Only two peers receive the complete block
    networkPeerManagerProbe.expectMsgPF() {
      case NetworkPeerManagerActor.SendMessage(b, p) if b.underlyingMsg == firstBlockMsg && peersIds.contains(p) => ()
    }
    networkPeerManagerProbe.expectMsgPF() {
      case NetworkPeerManagerActor.SendMessage(b, p) if b.underlyingMsg == firstBlockMsg && peersIds.contains(p) => ()
    }

    // All the peers should receive the block hashes
    networkPeerManagerProbe.expectMsg(NetworkPeerManagerActor.SendMessage(firstBlockNewHashes, peer.id))
    networkPeerManagerProbe.expectMsg(NetworkPeerManagerActor.SendMessage(firstBlockNewHashes, peer2.id))
    networkPeerManagerProbe.expectMsg(NetworkPeerManagerActor.SendMessage(firstBlockNewHashes, peer3.id))
    networkPeerManagerProbe.expectMsg(NetworkPeerManagerActor.SendMessage(firstBlockNewHashes, peer4.id))
    networkPeerManagerProbe.expectNoMessage()
  }

  class TestSetup(implicit system: ActorSystem) {
    val networkPeerManagerProbe: TestProbe = TestProbe()

    val blockBroadcast = new BlockBroadcast(networkPeerManagerProbe.ref)

    val baseBlockHeader = Fixtures.Blocks.Block3125369.header

    val peerStatus: RemoteStatus = RemoteStatus(
      capability = Capability.ETH68,
      networkId = 1,
      chainWeight = ChainWeight(10, 10000),
      bestHash = Fixtures.Blocks.Block3125369.header.hash,
      genesisHash = Fixtures.Blocks.Genesis.header.hash
    )
    val initialPeerInfo: PeerInfo = PeerInfo(
      remoteStatus = peerStatus,
      chainWeight = peerStatus.chainWeight,
      forkAccepted = false,
      maxBlockNumber = Fixtures.Blocks.Block3125369.header.number,
      bestBlockHash = peerStatus.bestHash
    )

    val peerProbe: TestProbe = TestProbe()
    val peer: Peer = Peer(PeerId("peer"), new InetSocketAddress("127.0.0.1", 0), peerProbe.ref, false)
  }
}
