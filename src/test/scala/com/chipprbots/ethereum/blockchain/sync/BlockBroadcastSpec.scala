package com.chipprbots.ethereum.blockchain.sync

import java.net.InetSocketAddress

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.testkit.TestProbe

import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.blockchain.sync.PeerListSupportNg.PeerWithInfo
import com.chipprbots.ethereum.blockchain.sync.regular.BlockBroadcast
import com.chipprbots.ethereum.blockchain.sync.regular.BlockBroadcast.BlockToBroadcast
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.BlockBody
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockNumber
import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.PeerInfo
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.RemoteStatus
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.PeerActor
import com.chipprbots.ethereum.network.PeerId
import com.chipprbots.ethereum.network.p2p.messages.Capability
import com.chipprbots.ethereum.network.p2p.messages.ETH69
import com.chipprbots.ethereum.network.p2p.messages.ETH69.BlockRangeUpdate
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.NewBlock
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.NewBlockHashes.BlockHash
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.NewBlockHashes.NewBlockHashes
import com.chipprbots.ethereum.testing.Tags.*

class BlockBroadcastSpec extends ScalaTestWithActorTestKit with AnyFlatSpecLike with Matchers:

  implicit private val classicSystem: org.apache.pekko.actor.ActorSystem = system.classicSystem

  it should "send a new block when it is not known by the peer (known by comparing chain weights)" taggedAs (
    UnitTest,
    SyncTest
  ) in new TestSetup:
    // given
    // Block that should be sent as it's total difficulty is higher than known by peer
    val blockHeader: BlockHeader = baseBlockHeader.copy(number = BlockNumber(initialPeerInfo.maxBlockNumber - 3))
    val newBlockNewHashes: NewBlockHashes =
      NewBlockHashes(Seq(BlockHash(blockHeader.hash.value, blockHeader.number.value)))
    val chainWeight: ChainWeight = initialPeerInfo.chainWeight.increaseTotalDifficulty(2)
    val block: Block = Block(blockHeader, BlockBody(Nil, Nil))
    val newBlockMsg: NewBlock = ETHPackets.NewBlock(block, chainWeight.totalDifficulty.value)

    // when
    blockBroadcast.broadcastBlock(
      BlockToBroadcast(block, chainWeight),
      Map(peer.id -> PeerWithInfo(peer, initialPeerInfo))
    )

    // then
    networkPeerManagerProbe.expectMsg(NetworkPeerManagerActor.SendMessageCmd(newBlockMsg, peer.id))
    networkPeerManagerProbe.expectMsg(NetworkPeerManagerActor.SendMessageCmd(newBlockNewHashes, peer.id))
    networkPeerManagerProbe.expectNoMessage()

  it should "send a new block when it is not known by the peer (known by comparing chain weights — ETH68 supportsSnap=true variant)" taggedAs (
    UnitTest,
    SyncTest
  ) in new TestSetup:
    // given
    // Block that should be sent as it's total difficulty is higher than known by peer
    val blockHeader: BlockHeader = baseBlockHeader.copy(number = BlockNumber(initialPeerInfo.maxBlockNumber - 3))
    val newBlockNewHashes: NewBlockHashes =
      NewBlockHashes(Seq(BlockHash(blockHeader.hash.value, blockHeader.number.value)))
    val peerInfo: PeerInfo = initialPeerInfo
      .copy(remoteStatus = peerStatus.copy(capability = Capability.ETH63))
      .withChainWeight(ChainWeight.totalDifficultyOnly(initialPeerInfo.chainWeight.totalDifficulty.value))
    val block: Block = Block(blockHeader, BlockBody(Nil, Nil))
    val newBlockMsg: NewBlock = ETHPackets.NewBlock(block, peerInfo.chainWeight.totalDifficulty.value + 2)

    // when
    blockBroadcast.broadcastBlock(
      BlockToBroadcast(block, ChainWeight.totalDifficultyOnly(newBlockMsg.totalDifficulty)),
      Map(peer.id -> PeerWithInfo(peer, peerInfo))
    )

    // then
    networkPeerManagerProbe.expectMsg(NetworkPeerManagerActor.SendMessageCmd(newBlockMsg, peer.id))
    networkPeerManagerProbe.expectMsg(NetworkPeerManagerActor.SendMessageCmd(newBlockNewHashes, peer.id))
    networkPeerManagerProbe.expectNoMessage()

  it should "not send a new block when it is known by the peer (known by comparing total difficulties)" taggedAs (
    UnitTest,
    SyncTest
  ) in new TestSetup:
    // given
    // Block that shouldn't be sent as it's number and total difficulty is lower than known by peer
    val blockHeader: BlockHeader = baseBlockHeader.copy(number = BlockNumber(initialPeerInfo.maxBlockNumber - 2))
    val chainWeight: ChainWeight = initialPeerInfo.chainWeight.increaseTotalDifficulty(-2)
    val block: Block = Block(blockHeader, BlockBody(Nil, Nil))

    // when
    blockBroadcast.broadcastBlock(
      BlockToBroadcast(block, chainWeight),
      Map(peer.id -> PeerWithInfo(peer, initialPeerInfo))
    )

    // then
    networkPeerManagerProbe.expectNoMessage()

  it should "send a new block when it is not known by the peer (known by comparing max block number)" taggedAs (
    UnitTest,
    SyncTest
  ) in new TestSetup:
    // given
    val blockHeader: BlockHeader = baseBlockHeader.copy(number = BlockNumber(initialPeerInfo.maxBlockNumber + 4))
    val newBlockNewHashes: NewBlockHashes =
      NewBlockHashes(Seq(BlockHash(blockHeader.hash.value, blockHeader.number.value)))
    val chainWeight: ChainWeight = initialPeerInfo.chainWeight.increaseTotalDifficulty(-2)
    val block: Block = Block(blockHeader, BlockBody(Nil, Nil))
    val newBlockMsg: NewBlock = ETHPackets.NewBlock(block, chainWeight.totalDifficulty.value)

    // when
    blockBroadcast.broadcastBlock(
      BlockToBroadcast(block, chainWeight),
      Map(peer.id -> PeerWithInfo(peer, initialPeerInfo))
    )

    // then
    networkPeerManagerProbe.expectMsg(NetworkPeerManagerActor.SendMessageCmd(newBlockMsg, peer.id))
    networkPeerManagerProbe.expectMsg(NetworkPeerManagerActor.SendMessageCmd(newBlockNewHashes, peer.id))
    networkPeerManagerProbe.expectNoMessage()

  it should "not send a new block only when it is known by the peer (known by comparing max block number)" taggedAs (
    UnitTest,
    SyncTest
  ) in new TestSetup:
    // given
    // Block should already be known by the peer due to max block known
    val blockHeader: BlockHeader = baseBlockHeader.copy(number = BlockNumber(initialPeerInfo.maxBlockNumber - 2))
    val chainWeight: ChainWeight = initialPeerInfo.chainWeight.increaseTotalDifficulty(-2)
    val block: Block = Block(blockHeader, BlockBody(Nil, Nil))

    // when
    blockBroadcast.broadcastBlock(
      BlockToBroadcast(block, chainWeight),
      Map(peer.id -> PeerWithInfo(peer, initialPeerInfo))
    )

    // then
    networkPeerManagerProbe.expectNoMessage()

  it should "send block hashes to all peers while the blocks only to sqrt of them" taggedAs (
    UnitTest,
    SyncTest
  ) in new TestSetup:
    // given
    val firstHeader: BlockHeader = baseBlockHeader.copy(number = BlockNumber(initialPeerInfo.maxBlockNumber + 4))
    val firstBlockNewHashes: NewBlockHashes =
      NewBlockHashes(Seq(BlockHash(firstHeader.hash.value, firstHeader.number.value)))
    val firstChainWeight: ChainWeight = initialPeerInfo.chainWeight.increaseTotalDifficulty(-2)
    val firstBlock: Block = Block(firstHeader, BlockBody(Nil, Nil))
    val firstBlockMsg: NewBlock = ETHPackets.NewBlock(firstBlock, firstChainWeight.totalDifficulty.value)

    val peer2Probe: TestProbe = TestProbe()
    val peer2: Peer =
      Peer(PeerId("peer2"), new InetSocketAddress("127.0.0.1", 0), peer2Probe.ref.toTyped[PeerActor.Command], false)
    val peer3Probe: TestProbe = TestProbe()
    val peer3: Peer =
      Peer(PeerId("peer3"), new InetSocketAddress("127.0.0.1", 0), peer3Probe.ref.toTyped[PeerActor.Command], false)
    val peer4Probe: TestProbe = TestProbe()
    val peer4: Peer =
      Peer(PeerId("peer4"), new InetSocketAddress("127.0.0.1", 0), peer4Probe.ref.toTyped[PeerActor.Command], false)

    // when
    val peers: Seq[Peer] = Seq(peer, peer2, peer3, peer4)
    val peersIds: Seq[PeerId] = peers.map(_.id)
    val peersWithInfo: Map[PeerId, PeerWithInfo] =
      peers.map(peer => peer.id -> PeerWithInfo(peer, initialPeerInfo)).toMap
    blockBroadcast.broadcastBlock(BlockToBroadcast(firstBlock, firstChainWeight), peersWithInfo)

    // then
    // Only two peers receive the complete block
    networkPeerManagerProbe.expectMsgPF() {
      case NetworkPeerManagerActor.SendMessageCmd(b, p) if b.underlyingMsg == firstBlockMsg && peersIds.contains(p) =>
        ()
    }
    networkPeerManagerProbe.expectMsgPF() {
      case NetworkPeerManagerActor.SendMessageCmd(b, p) if b.underlyingMsg == firstBlockMsg && peersIds.contains(p) =>
        ()
    }

    // All the peers should receive the block hashes
    networkPeerManagerProbe.expectMsg(NetworkPeerManagerActor.SendMessageCmd(firstBlockNewHashes, peer.id))
    networkPeerManagerProbe.expectMsg(NetworkPeerManagerActor.SendMessageCmd(firstBlockNewHashes, peer2.id))
    networkPeerManagerProbe.expectMsg(NetworkPeerManagerActor.SendMessageCmd(firstBlockNewHashes, peer3.id))
    networkPeerManagerProbe.expectMsg(NetworkPeerManagerActor.SendMessageCmd(firstBlockNewHashes, peer4.id))
    networkPeerManagerProbe.expectNoMessage()

  // ---- ETH/69 broadcast guard tests ----------------------------------------

  it should "not send a new block to an ETH69 peer when the peer is ahead by block number" taggedAs (
    UnitTest,
    SyncTest
  ) in new TestSetup:
    // Demonstrates the pre-fix bug: ETH69 chainWeight was a block-number proxy (~20M).
    // Our new block's actual TD (~10^26) was always > the proxy, so every ETH69 peer
    // was spammed. After the fix, only block-number comparison is used for ETH69.
    val peerLatestBlock: BigInt = BigInt(20_000_000)
    val actualTD: ChainWeight = ChainWeight.totalDifficultyOnly(BigInt("100000000000000000000000000"))
    val eth69Status: RemoteStatus = RemoteStatus(
      capability = Capability.ETH69,
      networkId = 1,
      chainWeight = actualTD,
      bestHash = Fixtures.Blocks.Block3125369.header.hash.value,
      genesisHash = Fixtures.Blocks.Genesis.header.hash.value,
      latestBlock = Some(peerLatestBlock)
    )
    val eth69PeerInfo: PeerInfo = PeerInfo(
      remoteStatus = eth69Status,
      chainWeight = actualTD,
      forkAccepted = true,
      maxBlockNumber = peerLatestBlock,
      bestBlockHash = eth69Status.bestHash
    )
    // Our block is behind the peer — should NOT be sent
    val blockHeader: BlockHeader = baseBlockHeader.copy(number = BlockNumber(peerLatestBlock - 100))
    val ourChainWeight: ChainWeight = ChainWeight.totalDifficultyOnly(BigInt("99000000000000000000000000"))
    val block: Block = Block(blockHeader, BlockBody(Nil, Nil))

    blockBroadcast.broadcastBlock(
      BlockToBroadcast(block, ourChainWeight),
      Map(peer.id -> PeerWithInfo(peer, eth69PeerInfo))
    )

    networkPeerManagerProbe.expectNoMessage()

  it should "send a new block to an ETH69 peer when our block number is higher" taggedAs (
    UnitTest,
    SyncTest
  ) in new TestSetup:
    val peerLatestBlock: BigInt = BigInt(20_000_000)
    val actualTD: ChainWeight = ChainWeight.totalDifficultyOnly(BigInt("100000000000000000000000000"))
    val eth69Status: RemoteStatus = RemoteStatus(
      capability = Capability.ETH69,
      networkId = 1,
      chainWeight = actualTD,
      bestHash = Fixtures.Blocks.Block3125369.header.hash.value,
      genesisHash = Fixtures.Blocks.Genesis.header.hash.value,
      latestBlock = Some(peerLatestBlock)
    )
    val eth69PeerInfo: PeerInfo = PeerInfo(
      remoteStatus = eth69Status,
      chainWeight = actualTD,
      forkAccepted = true,
      maxBlockNumber = peerLatestBlock,
      bestBlockHash = eth69Status.bestHash
    )
    // Our block is ahead of the peer — should be sent
    val blockHeader: BlockHeader = baseBlockHeader.copy(number = BlockNumber(peerLatestBlock + 1))
    val newBlockHashes: NewBlockHashes =
      NewBlockHashes(Seq(BlockHash(blockHeader.hash.value, blockHeader.number.value)))
    val ourChainWeight: ChainWeight = ChainWeight.totalDifficultyOnly(BigInt("101000000000000000000000000"))
    val block: Block = Block(blockHeader, BlockBody(Nil, Nil))
    val newBlockMsg: NewBlock = ETHPackets.NewBlock(block, ourChainWeight.totalDifficulty.value)

    blockBroadcast.broadcastBlock(
      BlockToBroadcast(block, ourChainWeight),
      Map(peer.id -> PeerWithInfo(peer, eth69PeerInfo))
    )

    val expectedBru: BlockRangeUpdate =
      ETH69.BlockRangeUpdate(BigInt(0), blockHeader.number.value, blockHeader.hash.value)
    networkPeerManagerProbe.expectMsg(NetworkPeerManagerActor.SendMessageCmd(newBlockMsg, peer.id))
    networkPeerManagerProbe.expectMsg(NetworkPeerManagerActor.SendMessageCmd(newBlockHashes, peer.id))
    networkPeerManagerProbe.expectMsg(NetworkPeerManagerActor.SendMessageCmd(expectedBru, peer.id))
    networkPeerManagerProbe.expectNoMessage()

  it should "not send a new block to an ETH69 peer at the same block number even if our actual TD is higher" taggedAs (
    UnitTest,
    SyncTest
  ) in new TestSetup:
    val peerLatestBlock: BigInt = BigInt(20_000_000)
    // Peer has actual TD stored (local lookup succeeded); our new block is at the same number
    val peerActualTD: ChainWeight = ChainWeight.totalDifficultyOnly(BigInt("100000000000000000000000000"))
    val eth69Status: RemoteStatus = RemoteStatus(
      capability = Capability.ETH69,
      networkId = 1,
      chainWeight = peerActualTD,
      bestHash = Fixtures.Blocks.Block3125369.header.hash.value,
      genesisHash = Fixtures.Blocks.Genesis.header.hash.value,
      latestBlock = Some(peerLatestBlock)
    )
    val eth69PeerInfo: PeerInfo = PeerInfo(
      remoteStatus = eth69Status,
      chainWeight = peerActualTD,
      forkAccepted = true,
      maxBlockNumber = peerLatestBlock,
      bestBlockHash = eth69Status.bestHash
    )
    val blockHeader: BlockHeader = baseBlockHeader.copy(number = BlockNumber(peerLatestBlock)) // same block number
    val ourChainWeight: ChainWeight = ChainWeight.totalDifficultyOnly(BigInt("100000000000000000000000001"))
    val block: Block = Block(blockHeader, BlockBody(Nil, Nil))

    blockBroadcast.broadcastBlock(
      BlockToBroadcast(block, ourChainWeight),
      Map(peer.id -> PeerWithInfo(peer, eth69PeerInfo))
    )

    networkPeerManagerProbe.expectNoMessage()

  // ---- Mixed ETH68/ETH69 interaction tests ---------------------------------

  it should "send to ETH68 peer (heavier chain) but NOT ETH69 peer when our block number is lower" taggedAs (
    UnitTest,
    SyncTest
  ) in new TestSetup:
    // Short-fork scenario: we are one block behind on a heavier chain.
    // ETH68 peer can see we have a heavier chain (TD comparison). ETH69 peer cannot
    // because TD comparison is disabled for ETH69 — only block number matters.
    val sharedBlockNr: BigInt = BigInt(1000)
    val peerTD: ChainWeight = ChainWeight.totalDifficultyOnly(BigInt(9999))

    val eth68Status: RemoteStatus = RemoteStatus(
      capability = Capability.ETH68,
      networkId = 1,
      chainWeight = peerTD,
      bestHash = Fixtures.Blocks.Block3125369.header.hash.value,
      genesisHash = Fixtures.Blocks.Genesis.header.hash.value
    )
    val eth68PeerInfo: PeerInfo = PeerInfo(
      remoteStatus = eth68Status,
      chainWeight = peerTD,
      forkAccepted = true,
      maxBlockNumber = sharedBlockNr + 1, // peer is one block ahead
      bestBlockHash = eth68Status.bestHash
    )

    val eth69Status: RemoteStatus = RemoteStatus(
      capability = Capability.ETH69,
      networkId = 1,
      chainWeight = peerTD,
      bestHash = Fixtures.Blocks.Block3125369.header.hash.value,
      genesisHash = Fixtures.Blocks.Genesis.header.hash.value,
      latestBlock = Some(sharedBlockNr + 1) // same position as ETH68 peer
    )
    val eth69PeerInfo: PeerInfo = PeerInfo(
      remoteStatus = eth69Status,
      chainWeight = peerTD,
      forkAccepted = true,
      maxBlockNumber = sharedBlockNr + 1,
      bestBlockHash = eth69Status.bestHash
    )

    val peer2Probe: TestProbe = TestProbe()
    val peer2: Peer = Peer(
      PeerId("peer2"),
      new java.net.InetSocketAddress("127.0.0.1", 0),
      peer2Probe.ref.toTyped[PeerActor.Command],
      false
    )

    // Our block is at sharedBlockNr (behind both peers by 1) but with heavier TD
    val ourBlockHdr: BlockHeader = baseBlockHeader.copy(number = BlockNumber(sharedBlockNr))
    val ourChainWeight: ChainWeight = ChainWeight.totalDifficultyOnly(BigInt(10001)) // heavier than peerTD
    val ourBlock: Block = Block(ourBlockHdr, BlockBody(Nil, Nil))
    val newBlockMsg: NewBlock = ETHPackets.NewBlock(ourBlock, ourChainWeight.totalDifficulty.value)
    val newBlockHashes: NewBlockHashes =
      NewBlockHashes(Seq(BlockHash(ourBlockHdr.hash.value, ourBlockHdr.number.value)))

    blockBroadcast.broadcastBlock(
      BlockToBroadcast(ourBlock, ourChainWeight),
      Map(
        peer.id -> PeerWithInfo(peer, eth68PeerInfo),
        peer2.id -> PeerWithInfo(peer2, eth69PeerInfo)
      )
    )

    // ETH68 peer: gets both the block body and the hash (only peer in peersWithoutBlock)
    // sqrt(1) = 1, so they receive the full NewBlock too
    networkPeerManagerProbe.expectMsg(NetworkPeerManagerActor.SendMessageCmd(newBlockMsg, peer.id))
    networkPeerManagerProbe.expectMsg(NetworkPeerManagerActor.SendMessageCmd(newBlockHashes, peer.id))
    // ETH69 peer: filtered out of peersWithoutBlock entirely — receives nothing
    networkPeerManagerProbe.expectNoMessage()

  it should "send to both ETH68 and ETH69 peers when our block number is higher" taggedAs (
    UnitTest,
    SyncTest
  ) in new TestSetup:
    val peerBlockNr: BigInt = BigInt(999)
    val peerTD: ChainWeight = ChainWeight.totalDifficultyOnly(BigInt(9000))

    val eth68Status: RemoteStatus = RemoteStatus(
      capability = Capability.ETH68,
      networkId = 1,
      chainWeight = peerTD,
      bestHash = Fixtures.Blocks.Block3125369.header.hash.value,
      genesisHash = Fixtures.Blocks.Genesis.header.hash.value
    )
    val eth68PeerInfo: PeerInfo = PeerInfo(
      remoteStatus = eth68Status,
      chainWeight = peerTD,
      forkAccepted = true,
      maxBlockNumber = peerBlockNr,
      bestBlockHash = eth68Status.bestHash
    )

    val eth69Status: RemoteStatus = RemoteStatus(
      capability = Capability.ETH69,
      networkId = 1,
      chainWeight = peerTD,
      bestHash = Fixtures.Blocks.Block3125369.header.hash.value,
      genesisHash = Fixtures.Blocks.Genesis.header.hash.value,
      latestBlock = Some(peerBlockNr)
    )
    val eth69PeerInfo: PeerInfo = PeerInfo(
      remoteStatus = eth69Status,
      chainWeight = peerTD,
      forkAccepted = true,
      maxBlockNumber = peerBlockNr,
      bestBlockHash = eth69Status.bestHash
    )

    val peer2Probe: TestProbe = TestProbe()
    val peer2: Peer = Peer(
      PeerId("peer2"),
      new java.net.InetSocketAddress("127.0.0.1", 0),
      peer2Probe.ref.toTyped[PeerActor.Command],
      false
    )

    // Our block is ahead of both peers
    val ourBlockHdr: BlockHeader = baseBlockHeader.copy(number = BlockNumber(peerBlockNr + 1))
    val ourChainWeight: ChainWeight = ChainWeight.totalDifficultyOnly(BigInt(9001))
    val ourBlock: Block = Block(ourBlockHdr, BlockBody(Nil, Nil))
    val newBlockHashes: NewBlockHashes =
      NewBlockHashes(Seq(BlockHash(ourBlockHdr.hash.value, ourBlockHdr.number.value)))

    blockBroadcast.broadcastBlock(
      BlockToBroadcast(ourBlock, ourChainWeight),
      Map(
        peer.id -> PeerWithInfo(peer, eth68PeerInfo),
        peer2.id -> PeerWithInfo(peer2, eth69PeerInfo)
      )
    )

    // With 2 peers: sqrt(2)=1 random peer gets NewBlock, both get NewBlockHashes,
    // and the ETH69 peer gets a BlockRangeUpdate — 4 messages total.
    // Collect all 4 messages (order non-deterministic).
    import scala.concurrent.duration.*
    val messages: Set[Object] = (1 to 4).map(_ => networkPeerManagerProbe.receiveOne(3.seconds)).toSet

    // One NewBlock to either peer
    messages.count {
      case NetworkPeerManagerActor.SendMessageCmd(msg, _) if msg.underlyingMsg == ourBlock => false
      case NetworkPeerManagerActor.SendMessageCmd(msg, _) if msg.underlyingMsg.isInstanceOf[ETHPackets.NewBlock] =>
        true
      case _ => false
    } shouldBe 1

    // NewBlockHashes to both peers
    val hashRecipients: Set[PeerId] = messages.collect {
      case NetworkPeerManagerActor.SendMessageCmd(msg, id) if msg.underlyingMsg == newBlockHashes => id
    }
    hashRecipients should contain(peer.id)
    hashRecipients should contain(peer2.id)

    // BlockRangeUpdate to the ETH69 peer only
    val expectedBru: BlockRangeUpdate =
      ETH69.BlockRangeUpdate(BigInt(0), ourBlockHdr.number.value, ourBlockHdr.hash.value)
    val bruRecipients: Set[PeerId] = messages.collect {
      case NetworkPeerManagerActor.SendMessageCmd(msg, id) if msg.underlyingMsg == expectedBru => id
    }
    bruRecipients should contain(peer2.id)
    bruRecipients should have size 1

    networkPeerManagerProbe.expectNoMessage()

  // ---- isPoWChain gating: NewBlock to ETH69 peers --------------------------

  it should "send NewBlock to ETH69 peer when isPoWChain=true (PoW: TD signal for ECBP-1100)" taggedAs (
    UnitTest,
    SyncTest
  ) in new TestSetup:
    val peerLatestBlock: BigInt = BigInt(1000)
    val eth69PeerInfo: PeerInfo = eth69PeerInfoAt(peerLatestBlock)
    val blockHeader: BlockHeader = baseBlockHeader.copy(number = BlockNumber(peerLatestBlock + 1))
    val ourWeight: ChainWeight = ChainWeight.totalDifficultyOnly(BigInt(99999))
    val block: Block = Block(blockHeader, BlockBody(Nil, Nil))

    // isPoWChain=true is set on the default TestSetup blockBroadcast
    blockBroadcast.broadcastBlock(
      BlockToBroadcast(block, ourWeight),
      Map(peer.id -> PeerWithInfo(peer, eth69PeerInfo))
    )

    import scala.concurrent.duration.*
    val messages: Seq[AnyRef] = networkPeerManagerProbe.receiveN(3, 3.seconds)
    val newBlocks: Seq[PeerId] = messages.collect {
      case NetworkPeerManagerActor.SendMessageCmd(msg, id) if msg.underlyingMsg.isInstanceOf[ETHPackets.NewBlock] => id
    }
    newBlocks should contain(peer.id) // ETH69 peer gets NewBlock on PoW chain

  it should "NOT send NewBlock to ETH69 peer when isPoWChain=false (PoS: go-ethereum aligned)" taggedAs (
    UnitTest,
    SyncTest
  ) in new PoSTestSetup:
    val peerLatestBlock: BigInt = BigInt(1000)
    val eth69PeerInfo: PeerInfo = eth69PeerInfoAt(peerLatestBlock)
    val blockHeader: BlockHeader = baseBlockHeader.copy(number = BlockNumber(peerLatestBlock + 1))
    val ourWeight: ChainWeight = ChainWeight.totalDifficultyOnly(BigInt(99999))
    val block: Block = Block(blockHeader, BlockBody(Nil, Nil))

    // isPoWChain=false → no NewBlock to ETH69 peers
    blockBroadcast.broadcastBlock(
      BlockToBroadcast(block, ourWeight),
      Map(peer.id -> PeerWithInfo(peer, eth69PeerInfo))
    )

    import scala.concurrent.duration.*
    // Only NewBlockHashes + BRU should arrive (no NewBlock)
    val messages: IndexedSeq[Object] = (1 to 2).map(_ => networkPeerManagerProbe.receiveOne(2.seconds))
    messages.foreach {
      case NetworkPeerManagerActor.SendMessageCmd(msg, _) =>
        msg.underlyingMsg should not be an[ETHPackets.NewBlock]
      case _ =>
    }
    networkPeerManagerProbe.expectNoMessage()

  // ---- isPoWChain gating: BRU frequency ------------------------------------

  it should "send BRU on every block when isPoWChain=true (3 blocks → 3 BRUs)" taggedAs (
    UnitTest,
    SyncTest
  ) in new TestSetup:
    val peerLatestBlock: BigInt = BigInt(999)
    val eth69PeerInfo: PeerInfo = eth69PeerInfoAt(peerLatestBlock)
    val ourWeight: ChainWeight = ChainWeight.totalDifficultyOnly(BigInt(9999))

    // Broadcast 3 consecutive blocks
    val blocks: IndexedSeq[Block] = (1 to 3).map { i =>
      val hdr = baseBlockHeader.copy(number = BlockNumber(peerLatestBlock + i))
      Block(hdr, BlockBody(Nil, Nil))
    }
    blocks.foreach { block =>
      blockBroadcast.broadcastBlock(
        BlockToBroadcast(block, ourWeight),
        Map(peer.id -> PeerWithInfo(peer, eth69PeerInfo))
      )
    }

    import scala.concurrent.duration.*
    val allMessages: Seq[AnyRef] = networkPeerManagerProbe.receiveN(9, 5.seconds) // 3 × (NewBlock + Hashes + BRU)
    val bruCount: Int = allMessages.count {
      case NetworkPeerManagerActor.SendMessageCmd(msg, _) =>
        msg.underlyingMsg.isInstanceOf[ETH69.BlockRangeUpdate]
      case _ => false
    }
    bruCount shouldEqual 3 // one BRU per block on PoW chain
    networkPeerManagerProbe.expectNoMessage()

  it should "send BRU only at block 32 when isPoWChain=false (PoS epoch gate)" taggedAs (
    UnitTest,
    SyncTest
  ) in new PoSTestSetup:
    // Start from block 1 so the peer is behind on all 33 blocks
    val startBlock: BigInt = BigInt(0)
    val eth69PeerInfo: PeerInfo = eth69PeerInfoAt(startBlock)
    val ourWeight: ChainWeight = ChainWeight.totalDifficultyOnly(BigInt(9999))

    def broadcastAt(n: Int): Unit =
      val hdr = baseBlockHeader.copy(number = BlockNumber(n))
      val block = Block(hdr, BlockBody(Nil, Nil))
      blockBroadcast.broadcastBlock(
        BlockToBroadcast(block, ourWeight),
        Map(peer.id -> PeerWithInfo(peer, eth69PeerInfo))
      )

    import scala.concurrent.duration.*

    // Blocks 1–31: no BRU (not at epoch boundary)
    (1 to 31).foreach(broadcastAt)
    // Each block → NewBlockHashes only (PoS: no NewBlock, no BRU until block 32)
    val pre32Messages: IndexedSeq[Object] = (1 to 31).map(_ => networkPeerManagerProbe.receiveN(1, 2.seconds)).flatten
    val pre32Brus: Int = pre32Messages.count {
      case NetworkPeerManagerActor.SendMessageCmd(msg, _) => msg.underlyingMsg.isInstanceOf[ETH69.BlockRangeUpdate]
      case _                                              => false
    }
    pre32Brus shouldEqual 0 // no BRU before epoch boundary

    // Block 32: BRU fires
    broadcastAt(32)
    val block32Messages: Seq[AnyRef] = networkPeerManagerProbe.receiveN(2, 2.seconds) // Hashes + BRU
    val block32Brus: Int = block32Messages.count {
      case NetworkPeerManagerActor.SendMessageCmd(msg, _) => msg.underlyingMsg.isInstanceOf[ETH69.BlockRangeUpdate]
      case _                                              => false
    }
    block32Brus shouldEqual 1 // BRU at epoch boundary (32 % 32 == 0)
    networkPeerManagerProbe.expectNoMessage()

  it should "send NewBlock to ETH68 peer regardless of isPoWChain" taggedAs (
    UnitTest,
    SyncTest
  ) in {
    // Both PoW and PoS configurations must send NewBlock to ETH68 peers
    for isPoW <- Seq(true, false) do
      val pm: TestProbe = TestProbe()(testKit.system.classicSystem)
      val bb = new BlockBroadcast(pm.ref, isPoWChain = isPoW)

      val blockHeader: BlockHeader = Fixtures.Blocks.Block3125369.header.copy(number = BlockNumber(1001))
      val ourWeight: ChainWeight = ChainWeight.totalDifficultyOnly(BigInt(99999))
      val block: Block = Block(blockHeader, BlockBody(Nil, Nil))

      val eth68Status: RemoteStatus = RemoteStatus(
        capability = Capability.ETH68,
        networkId = 1,
        chainWeight = ChainWeight.totalDifficultyOnly(BigInt(1000)),
        bestHash = Fixtures.Blocks.Block3125369.header.hash.value,
        genesisHash = Fixtures.Blocks.Genesis.header.hash.value
      )
      val eth68PeerInfo: PeerInfo = PeerInfo(
        remoteStatus = eth68Status,
        chainWeight = eth68Status.chainWeight,
        forkAccepted = true,
        maxBlockNumber = BigInt(1000),
        bestBlockHash = eth68Status.bestHash
      )
      val p: Peer =
        Peer(
          PeerId(s"eth68peer-$isPoW"),
          new java.net.InetSocketAddress("127.0.0.1", 0),
          TestProbe()(testKit.system.classicSystem).ref.toTyped[PeerActor.Command],
          false
        )

      bb.broadcastBlock(BlockToBroadcast(block, ourWeight), Map(p.id -> PeerWithInfo(p, eth68PeerInfo)))

      import scala.concurrent.duration.*
      val messages: Seq[AnyRef] = pm.receiveN(2, 3.seconds)
      val hasNewBlock: Boolean = messages.exists {
        case NetworkPeerManagerActor.SendMessageCmd(msg, _) => msg.underlyingMsg.isInstanceOf[ETHPackets.NewBlock]
        case _                                              => false
      }
      hasNewBlock shouldBe true // ETH68 always gets NewBlock
      pm.expectNoMessage()
  }

  it should "NEVER send BRU to ETH68 peer (BRU is ETH69-only)" taggedAs (
    UnitTest,
    SyncTest
  ) in new TestSetup:
    // ETH68 peer — should get NewBlock + NewBlockHashes, but NO BlockRangeUpdate
    val blockHeader: BlockHeader = baseBlockHeader.copy(number = BlockNumber(initialPeerInfo.maxBlockNumber + 1))
    val ourWeight: ChainWeight = ChainWeight.totalDifficultyOnly(initialPeerInfo.chainWeight.totalDifficulty.value + 1)
    val block: Block = Block(blockHeader, BlockBody(Nil, Nil))

    blockBroadcast.broadcastBlock(
      BlockToBroadcast(block, ourWeight),
      Map(peer.id -> PeerWithInfo(peer, initialPeerInfo)) // ETH68 peer from TestSetup
    )

    import scala.concurrent.duration.*
    val messages: Seq[AnyRef] = networkPeerManagerProbe.receiveN(2, 3.seconds)
    val hasBru: Boolean = messages.exists {
      case NetworkPeerManagerActor.SendMessageCmd(msg, _) => msg.underlyingMsg.isInstanceOf[ETH69.BlockRangeUpdate]
      case _                                              => false
    }
    hasBru shouldBe false // ETH68 peer never gets BRU
    networkPeerManagerProbe.expectNoMessage()

  // ---- announceCanonicalHead (PoS head-announce, no shouldSend gating) -----

  it should "send NewBlockHashes to a non-ETH69 handshaked peer via announceCanonicalHead" taggedAs (
    UnitTest,
    SyncTest
  ) in new PoSTestSetup:
    // ETH68 peer from TestSetup (initialPeerInfo uses Capability.ETH68)
    val header: BlockHeader = baseBlockHeader.copy(number = BlockNumber(999))
    val expectedHashes: NewBlockHashes = NewBlockHashes(Seq(BlockHash(header.hash.value, header.number.value)))

    blockBroadcast.announceCanonicalHead(header, Map(peer.id -> PeerWithInfo(peer, initialPeerInfo)))

    networkPeerManagerProbe.expectMsg(NetworkPeerManagerActor.SendMessageCmd(expectedHashes, peer.id))
    networkPeerManagerProbe.expectNoMessage()

  it should "send BlockRangeUpdate(0, number, hash) to an ETH69 handshaked peer via announceCanonicalHead" taggedAs (
    UnitTest,
    SyncTest
  ) in new PoSTestSetup:
    val peerLatestBlock: BigInt = BigInt(500)
    val eth69Info: PeerInfo = eth69PeerInfoAt(peerLatestBlock)
    val header: BlockHeader = baseBlockHeader.copy(number = BlockNumber(1000))
    val expectedBru: BlockRangeUpdate = ETH69.BlockRangeUpdate(BigInt(0), header.number.value, header.hash.value)

    blockBroadcast.announceCanonicalHead(header, Map(peer.id -> PeerWithInfo(peer, eth69Info)))

    networkPeerManagerProbe.expectMsg(NetworkPeerManagerActor.SendMessageCmd(expectedBru, peer.id))
    networkPeerManagerProbe.expectNoMessage()

  it should "announce to ALL peers including peers that shouldSendNewBlock would filter out (no gating)" taggedAs (
    UnitTest,
    SyncTest
  ) in new PoSTestSetup:
    // Set up a peer that is AHEAD of our block — broadcastBlock would filter it via shouldSendNewBlock
    // (blockAhead=false, heavierChain=false → shouldSend=false). announceCanonicalHead must bypass
    // this filter and always announce, because the downloader may have seen genesis=0 at STATUS time.
    val peerBlockNr: BigInt = BigInt(5000)
    val peerAheadInfo: PeerInfo = initialPeerInfo.copy(
      remoteStatus = peerStatus.copy(capability = Capability.ETH68),
      maxBlockNumber = peerBlockNr
    )
    // Our head is behind the peer's reported block
    val ourHeader: BlockHeader = baseBlockHeader.copy(number = BlockNumber(100))
    val expectedHashes: NewBlockHashes = NewBlockHashes(Seq(BlockHash(ourHeader.hash.value, ourHeader.number.value)))

    blockBroadcast.announceCanonicalHead(ourHeader, Map(peer.id -> PeerWithInfo(peer, peerAheadInfo)))

    // announceCanonicalHead sends unconditionally — the peer receives NewBlockHashes despite being "ahead"
    networkPeerManagerProbe.expectMsg(NetworkPeerManagerActor.SendMessageCmd(expectedHashes, peer.id))
    networkPeerManagerProbe.expectNoMessage()

  // ---- announceCanonicalHead BlockRangeUpdate cadence gate (shouldSendCanonicalHeadBRU) -------------------------
  //
  // Regression coverage for the hive devp2p `eth` suite panic: BlockBroadcasterActor re-announces the canonical
  // head to the full known-peer set on every genuine forkchoiceUpdated. Before this gate, that meant a fresh
  // BlockRangeUpdate to every ETH69+ peer on every single post-merge slot (~12s); the hive tool's `Conn.ReadEth`
  // has no case for an unsolicited 0x11 and panics (`TestLargeTxRequest`, `TestNewPooledTxs`). The gate mirrors
  // go-ethereum's `blockRangeState.shouldSend` (eth/handler.go): resend only on a 32+ block advance or a backward
  // move (reorg). The default `throttleBlockRangeUpdate = true` exercises the gate; an explicit `false` (the
  // newly-observed-peer catch-up shape) always sends.

  it should "throttle a repeated BlockRangeUpdate to the same known peer when the head advanced fewer than 32 blocks" taggedAs (
    UnitTest,
    SyncTest
  ) in new PoSTestSetup:
    val eth69Info: PeerInfo = eth69PeerInfoAt(BigInt(500))
    val peers = Map(peer.id -> PeerWithInfo(peer, eth69Info))
    val firstHead: BlockHeader = baseBlockHeader.copy(number = BlockNumber(1000))
    val secondHead: BlockHeader = baseBlockHeader.copy(number = BlockNumber(1010)) // +10, below the 32-block gate

    blockBroadcast.announceCanonicalHead(firstHead, peers)
    networkPeerManagerProbe.expectMsg(
      NetworkPeerManagerActor.SendMessageCmd(
        ETH69.BlockRangeUpdate(BigInt(0), firstHead.number.value, firstHead.hash.value),
        peer.id
      )
    )

    blockBroadcast.announceCanonicalHead(secondHead, peers)
    networkPeerManagerProbe.expectNoMessage() // throttled — no second BlockRangeUpdate

  it should "send BlockRangeUpdate again once the head has advanced by 32 or more blocks since the last broadcast" taggedAs (
    UnitTest,
    SyncTest
  ) in new PoSTestSetup:
    val eth69Info: PeerInfo = eth69PeerInfoAt(BigInt(500))
    val peers = Map(peer.id -> PeerWithInfo(peer, eth69Info))
    val firstHead: BlockHeader = baseBlockHeader.copy(number = BlockNumber(1000))
    val secondHead: BlockHeader = baseBlockHeader.copy(number = BlockNumber(1032)) // exactly +32

    blockBroadcast.announceCanonicalHead(firstHead, peers)
    networkPeerManagerProbe.expectMsg(
      NetworkPeerManagerActor.SendMessageCmd(
        ETH69.BlockRangeUpdate(BigInt(0), firstHead.number.value, firstHead.hash.value),
        peer.id
      )
    )

    blockBroadcast.announceCanonicalHead(secondHead, peers)
    networkPeerManagerProbe.expectMsg(
      NetworkPeerManagerActor.SendMessageCmd(
        ETH69.BlockRangeUpdate(BigInt(0), secondHead.number.value, secondHead.hash.value),
        peer.id
      )
    )

  it should "send BlockRangeUpdate immediately when the range moves backward (reorg), regardless of the 32-block gate" taggedAs (
    UnitTest,
    SyncTest
  ) in new PoSTestSetup:
    val eth69Info: PeerInfo = eth69PeerInfoAt(BigInt(500))
    val peers = Map(peer.id -> PeerWithInfo(peer, eth69Info))
    val firstHead: BlockHeader = baseBlockHeader.copy(number = BlockNumber(1000))
    val reorgedHead: BlockHeader = baseBlockHeader.copy(number = BlockNumber(900)) // moved backward

    blockBroadcast.announceCanonicalHead(firstHead, peers)
    networkPeerManagerProbe.expectMsg(
      NetworkPeerManagerActor.SendMessageCmd(
        ETH69.BlockRangeUpdate(BigInt(0), firstHead.number.value, firstHead.hash.value),
        peer.id
      )
    )

    blockBroadcast.announceCanonicalHead(reorgedHead, peers)
    networkPeerManagerProbe.expectMsg(
      NetworkPeerManagerActor.SendMessageCmd(
        ETH69.BlockRangeUpdate(BigInt(0), reorgedHead.number.value, reorgedHead.hash.value),
        peer.id
      )
    )

  it should "always send BlockRangeUpdate when throttleBlockRangeUpdate=false, bypassing the cadence gate (newly-observed-peer catch-up)" taggedAs (
    UnitTest,
    SyncTest
  ) in new PoSTestSetup:
    val eth69Info: PeerInfo = eth69PeerInfoAt(BigInt(500))
    val peers = Map(peer.id -> PeerWithInfo(peer, eth69Info))
    val firstHead: BlockHeader = baseBlockHeader.copy(number = BlockNumber(1000))
    val secondHead: BlockHeader =
      baseBlockHeader.copy(number = BlockNumber(1010)) // +10 — would be throttled by default

    blockBroadcast.announceCanonicalHead(firstHead, peers)
    networkPeerManagerProbe.expectMsg(
      NetworkPeerManagerActor.SendMessageCmd(
        ETH69.BlockRangeUpdate(BigInt(0), firstHead.number.value, firstHead.hash.value),
        peer.id
      )
    )

    // A newly-observed peer's catch-up call — never received a BlockRangeUpdate before, so the global
    // "have we told everyone recently" cadence must not suppress it.
    blockBroadcast.announceCanonicalHead(secondHead, peers, throttleBlockRangeUpdate = false)
    networkPeerManagerProbe.expectMsg(
      NetworkPeerManagerActor.SendMessageCmd(
        ETH69.BlockRangeUpdate(BigInt(0), secondHead.number.value, secondHead.hash.value),
        peer.id
      )
    )

  // -------------------------------------------------------------------------

  class TestSetup(implicit system: org.apache.pekko.actor.ActorSystem):
    val networkPeerManagerProbe: TestProbe = TestProbe()

    val blockBroadcast = new BlockBroadcast(networkPeerManagerProbe.ref, isPoWChain = true)

    val baseBlockHeader = Fixtures.Blocks.Block3125369.header

    val peerStatus: RemoteStatus = RemoteStatus(
      capability = Capability.ETH68,
      networkId = 1,
      chainWeight = ChainWeight.totalDifficultyOnly(BigInt(10000)),
      bestHash = Fixtures.Blocks.Block3125369.header.hash.value,
      genesisHash = Fixtures.Blocks.Genesis.header.hash.value
    )
    val initialPeerInfo: PeerInfo = PeerInfo(
      remoteStatus = peerStatus,
      chainWeight = peerStatus.chainWeight,
      forkAccepted = false,
      maxBlockNumber = Fixtures.Blocks.Block3125369.header.number.value,
      bestBlockHash = peerStatus.bestHash
    )

    val peerProbe: TestProbe = TestProbe()
    val peer: Peer =
      Peer(PeerId("peer"), new InetSocketAddress("127.0.0.1", 0), peerProbe.ref.toTyped[PeerActor.Command], false)

    /** Build an ETH69 PeerInfo whose maxBlockNumber is `latestBlock`. */
    def eth69PeerInfoAt(latestBlock: BigInt): PeerInfo =
      val status = RemoteStatus(
        capability = Capability.ETH69,
        networkId = 1,
        chainWeight = ChainWeight.totalDifficultyOnly(BigInt(9000)),
        bestHash = Fixtures.Blocks.Block3125369.header.hash.value,
        genesisHash = Fixtures.Blocks.Genesis.header.hash.value,
        latestBlock = Some(latestBlock)
      )
      PeerInfo(
        remoteStatus = status,
        chainWeight = status.chainWeight,
        forkAccepted = true,
        maxBlockNumber = latestBlock,
        bestBlockHash = status.bestHash
      )

  /** Same as TestSetup but with isPoWChain=false (PoS / ETH / Sepolia). */
  class PoSTestSetup(implicit system: org.apache.pekko.actor.ActorSystem) extends TestSetup:
    override val blockBroadcast = new BlockBroadcast(networkPeerManagerProbe.ref, isPoWChain = false)
