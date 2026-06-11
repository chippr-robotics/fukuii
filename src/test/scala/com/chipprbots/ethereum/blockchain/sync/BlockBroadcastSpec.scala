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
import com.chipprbots.ethereum.network.p2p.messages.Capability
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.NewBlockHashes.NewBlockHashes
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.NewBlockHashes.BlockHash
import com.chipprbots.ethereum.network.p2p.messages.ETH69

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
    val newBlockNewHashes: NewBlockHashes = NewBlockHashes(Seq(BlockHash(blockHeader.hash, blockHeader.number)))
    val chainWeight = initialPeerInfo.chainWeight.increaseTotalDifficulty(2)
    val block = Block(blockHeader, BlockBody(Nil, Nil))
    val newBlockMsg = ETHPackets.NewBlock(block, chainWeight.totalDifficulty)

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

  it should "send a new block when it is not known by the peer (known by comparing chain weights — ETH68 supportsSnap=true variant)" taggedAs (
    UnitTest,
    SyncTest
  ) in new TestSetup {
    // given
    // Block that should be sent as it's total difficulty is higher than known by peer
    val blockHeader: BlockHeader = baseBlockHeader.copy(number = initialPeerInfo.maxBlockNumber - 3)
    val newBlockNewHashes: NewBlockHashes = NewBlockHashes(Seq(BlockHash(blockHeader.hash, blockHeader.number)))
    val peerInfo: PeerInfo = initialPeerInfo
      .copy(remoteStatus = peerStatus.copy(capability = Capability.ETH63))
      .withChainWeight(ChainWeight.totalDifficultyOnly(initialPeerInfo.chainWeight.totalDifficulty))
    val block = Block(blockHeader, BlockBody(Nil, Nil))
    val newBlockMsg = ETHPackets.NewBlock(block, peerInfo.chainWeight.totalDifficulty + 2)

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
    val newBlockNewHashes: NewBlockHashes = NewBlockHashes(Seq(BlockHash(blockHeader.hash, blockHeader.number)))
    val chainWeight = initialPeerInfo.chainWeight.increaseTotalDifficulty(-2)
    val block = Block(blockHeader, BlockBody(Nil, Nil))
    val newBlockMsg = ETHPackets.NewBlock(block, chainWeight.totalDifficulty)

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
    val firstBlockNewHashes: NewBlockHashes = NewBlockHashes(Seq(BlockHash(firstHeader.hash, firstHeader.number)))
    val firstChainWeight = initialPeerInfo.chainWeight.increaseTotalDifficulty(-2)
    val firstBlock = Block(firstHeader, BlockBody(Nil, Nil))
    val firstBlockMsg = ETHPackets.NewBlock(firstBlock, firstChainWeight.totalDifficulty)

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

  // ---- ETH/69 broadcast guard tests ----------------------------------------

  it should "not send a new block to an ETH69 peer when the peer is ahead by block number" taggedAs (
    UnitTest,
    SyncTest
  ) in new TestSetup {
    // Demonstrates the pre-fix bug: ETH69 chainWeight was a block-number proxy (~20M).
    // Our new block's actual TD (~10^26) was always > the proxy, so every ETH69 peer
    // was spammed. After the fix, only block-number comparison is used for ETH69.
    val peerLatestBlock = BigInt(20_000_000)
    val actualTD = ChainWeight.totalDifficultyOnly(BigInt("100000000000000000000000000"))
    val eth69Status = RemoteStatus(
      capability = Capability.ETH69,
      networkId = 1,
      chainWeight = actualTD,
      bestHash = Fixtures.Blocks.Block3125369.header.hash,
      genesisHash = Fixtures.Blocks.Genesis.header.hash,
      latestBlock = Some(peerLatestBlock)
    )
    val eth69PeerInfo = PeerInfo(
      remoteStatus = eth69Status,
      chainWeight = actualTD,
      forkAccepted = true,
      maxBlockNumber = peerLatestBlock,
      bestBlockHash = eth69Status.bestHash
    )
    // Our block is behind the peer — should NOT be sent
    val blockHeader = baseBlockHeader.copy(number = peerLatestBlock - 100)
    val ourChainWeight = ChainWeight.totalDifficultyOnly(BigInt("99000000000000000000000000"))
    val block = Block(blockHeader, BlockBody(Nil, Nil))

    blockBroadcast.broadcastBlock(
      BlockToBroadcast(block, ourChainWeight),
      Map(peer.id -> PeerWithInfo(peer, eth69PeerInfo))
    )

    networkPeerManagerProbe.expectNoMessage()
  }

  it should "send a new block to an ETH69 peer when our block number is higher" taggedAs (
    UnitTest,
    SyncTest
  ) in new TestSetup {
    val peerLatestBlock = BigInt(20_000_000)
    val actualTD = ChainWeight.totalDifficultyOnly(BigInt("100000000000000000000000000"))
    val eth69Status = RemoteStatus(
      capability = Capability.ETH69,
      networkId = 1,
      chainWeight = actualTD,
      bestHash = Fixtures.Blocks.Block3125369.header.hash,
      genesisHash = Fixtures.Blocks.Genesis.header.hash,
      latestBlock = Some(peerLatestBlock)
    )
    val eth69PeerInfo = PeerInfo(
      remoteStatus = eth69Status,
      chainWeight = actualTD,
      forkAccepted = true,
      maxBlockNumber = peerLatestBlock,
      bestBlockHash = eth69Status.bestHash
    )
    // Our block is ahead of the peer — should be sent
    val blockHeader = baseBlockHeader.copy(number = peerLatestBlock + 1)
    val newBlockHashes = NewBlockHashes(Seq(BlockHash(blockHeader.hash, blockHeader.number)))
    val ourChainWeight = ChainWeight.totalDifficultyOnly(BigInt("101000000000000000000000000"))
    val block = Block(blockHeader, BlockBody(Nil, Nil))
    val newBlockMsg = ETHPackets.NewBlock(block, ourChainWeight.totalDifficulty)

    blockBroadcast.broadcastBlock(
      BlockToBroadcast(block, ourChainWeight),
      Map(peer.id -> PeerWithInfo(peer, eth69PeerInfo))
    )

    val expectedBru = ETH69.BlockRangeUpdate(BigInt(0), blockHeader.number, blockHeader.hash)
    networkPeerManagerProbe.expectMsg(NetworkPeerManagerActor.SendMessage(newBlockMsg, peer.id))
    networkPeerManagerProbe.expectMsg(NetworkPeerManagerActor.SendMessage(newBlockHashes, peer.id))
    networkPeerManagerProbe.expectMsg(NetworkPeerManagerActor.SendMessage(expectedBru, peer.id))
    networkPeerManagerProbe.expectNoMessage()
  }

  it should "not send a new block to an ETH69 peer at the same block number even if our actual TD is higher" taggedAs (
    UnitTest,
    SyncTest
  ) in new TestSetup {
    val peerLatestBlock = BigInt(20_000_000)
    // Peer has actual TD stored (local lookup succeeded); our new block is at the same number
    val peerActualTD = ChainWeight.totalDifficultyOnly(BigInt("100000000000000000000000000"))
    val eth69Status = RemoteStatus(
      capability = Capability.ETH69,
      networkId = 1,
      chainWeight = peerActualTD,
      bestHash = Fixtures.Blocks.Block3125369.header.hash,
      genesisHash = Fixtures.Blocks.Genesis.header.hash,
      latestBlock = Some(peerLatestBlock)
    )
    val eth69PeerInfo = PeerInfo(
      remoteStatus = eth69Status,
      chainWeight = peerActualTD,
      forkAccepted = true,
      maxBlockNumber = peerLatestBlock,
      bestBlockHash = eth69Status.bestHash
    )
    val blockHeader = baseBlockHeader.copy(number = peerLatestBlock) // same block number
    val ourChainWeight = ChainWeight.totalDifficultyOnly(BigInt("100000000000000000000000001"))
    val block = Block(blockHeader, BlockBody(Nil, Nil))

    blockBroadcast.broadcastBlock(
      BlockToBroadcast(block, ourChainWeight),
      Map(peer.id -> PeerWithInfo(peer, eth69PeerInfo))
    )

    networkPeerManagerProbe.expectNoMessage()
  }

  // ---- Mixed ETH68/ETH69 interaction tests ---------------------------------

  it should "send to ETH68 peer (heavier chain) but NOT ETH69 peer when our block number is lower" taggedAs (
    UnitTest,
    SyncTest
  ) in new TestSetup {
    // Short-fork scenario: we are one block behind on a heavier chain.
    // ETH68 peer can see we have a heavier chain (TD comparison). ETH69 peer cannot
    // because TD comparison is disabled for ETH69 — only block number matters.
    val sharedBlockNr = BigInt(1000)
    val peerTD = ChainWeight.totalDifficultyOnly(BigInt(9999))

    val eth68Status = RemoteStatus(
      capability = Capability.ETH68,
      networkId = 1,
      chainWeight = peerTD,
      bestHash = Fixtures.Blocks.Block3125369.header.hash,
      genesisHash = Fixtures.Blocks.Genesis.header.hash
    )
    val eth68PeerInfo = PeerInfo(
      remoteStatus = eth68Status,
      chainWeight = peerTD,
      forkAccepted = true,
      maxBlockNumber = sharedBlockNr + 1, // peer is one block ahead
      bestBlockHash = eth68Status.bestHash
    )

    val eth69Status = RemoteStatus(
      capability = Capability.ETH69,
      networkId = 1,
      chainWeight = peerTD,
      bestHash = Fixtures.Blocks.Block3125369.header.hash,
      genesisHash = Fixtures.Blocks.Genesis.header.hash,
      latestBlock = Some(sharedBlockNr + 1) // same position as ETH68 peer
    )
    val eth69PeerInfo = PeerInfo(
      remoteStatus = eth69Status,
      chainWeight = peerTD,
      forkAccepted = true,
      maxBlockNumber = sharedBlockNr + 1,
      bestBlockHash = eth69Status.bestHash
    )

    val peer2Probe = TestProbe()
    val peer2 = Peer(PeerId("peer2"), new java.net.InetSocketAddress("127.0.0.1", 0), peer2Probe.ref, false)

    // Our block is at sharedBlockNr (behind both peers by 1) but with heavier TD
    val ourBlockHdr = baseBlockHeader.copy(number = sharedBlockNr)
    val ourChainWeight = ChainWeight.totalDifficultyOnly(BigInt(10001)) // heavier than peerTD
    val ourBlock = Block(ourBlockHdr, BlockBody(Nil, Nil))
    val newBlockMsg = ETHPackets.NewBlock(ourBlock, ourChainWeight.totalDifficulty)
    val newBlockHashes = NewBlockHashes(Seq(BlockHash(ourBlockHdr.hash, ourBlockHdr.number)))

    blockBroadcast.broadcastBlock(
      BlockToBroadcast(ourBlock, ourChainWeight),
      Map(
        peer.id -> PeerWithInfo(peer, eth68PeerInfo),
        peer2.id -> PeerWithInfo(peer2, eth69PeerInfo)
      )
    )

    // ETH68 peer: gets both the block body and the hash (only peer in peersWithoutBlock)
    // sqrt(1) = 1, so they receive the full NewBlock too
    networkPeerManagerProbe.expectMsg(NetworkPeerManagerActor.SendMessage(newBlockMsg, peer.id))
    networkPeerManagerProbe.expectMsg(NetworkPeerManagerActor.SendMessage(newBlockHashes, peer.id))
    // ETH69 peer: filtered out of peersWithoutBlock entirely — receives nothing
    networkPeerManagerProbe.expectNoMessage()
  }

  it should "send to both ETH68 and ETH69 peers when our block number is higher" taggedAs (
    UnitTest,
    SyncTest
  ) in new TestSetup {
    val peerBlockNr = BigInt(999)
    val peerTD = ChainWeight.totalDifficultyOnly(BigInt(9000))

    val eth68Status = RemoteStatus(
      capability = Capability.ETH68,
      networkId = 1,
      chainWeight = peerTD,
      bestHash = Fixtures.Blocks.Block3125369.header.hash,
      genesisHash = Fixtures.Blocks.Genesis.header.hash
    )
    val eth68PeerInfo = PeerInfo(
      remoteStatus = eth68Status,
      chainWeight = peerTD,
      forkAccepted = true,
      maxBlockNumber = peerBlockNr,
      bestBlockHash = eth68Status.bestHash
    )

    val eth69Status = RemoteStatus(
      capability = Capability.ETH69,
      networkId = 1,
      chainWeight = peerTD,
      bestHash = Fixtures.Blocks.Block3125369.header.hash,
      genesisHash = Fixtures.Blocks.Genesis.header.hash,
      latestBlock = Some(peerBlockNr)
    )
    val eth69PeerInfo = PeerInfo(
      remoteStatus = eth69Status,
      chainWeight = peerTD,
      forkAccepted = true,
      maxBlockNumber = peerBlockNr,
      bestBlockHash = eth69Status.bestHash
    )

    val peer2Probe = TestProbe()
    val peer2 = Peer(PeerId("peer2"), new java.net.InetSocketAddress("127.0.0.1", 0), peer2Probe.ref, false)

    // Our block is ahead of both peers
    val ourBlockHdr = baseBlockHeader.copy(number = peerBlockNr + 1)
    val ourChainWeight = ChainWeight.totalDifficultyOnly(BigInt(9001))
    val ourBlock = Block(ourBlockHdr, BlockBody(Nil, Nil))
    val newBlockHashes = NewBlockHashes(Seq(BlockHash(ourBlockHdr.hash, ourBlockHdr.number)))

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
    import scala.concurrent.duration._
    val messages = (1 to 4).map(_ => networkPeerManagerProbe.receiveOne(3.seconds)).toSet

    // One NewBlock to either peer
    messages.count {
      case NetworkPeerManagerActor.SendMessage(msg, _) if msg.underlyingMsg == ourBlock => false
      case NetworkPeerManagerActor.SendMessage(msg, _) if msg.underlyingMsg.isInstanceOf[ETHPackets.NewBlock] =>
        true
      case _ => false
    } shouldBe 1

    // NewBlockHashes to both peers
    val hashRecipients = messages.collect {
      case NetworkPeerManagerActor.SendMessage(msg, id) if msg.underlyingMsg == newBlockHashes => id
    }
    hashRecipients should contain(peer.id)
    hashRecipients should contain(peer2.id)

    // BlockRangeUpdate to the ETH69 peer only
    val expectedBru = ETH69.BlockRangeUpdate(BigInt(0), ourBlockHdr.number, ourBlockHdr.hash)
    val bruRecipients = messages.collect {
      case NetworkPeerManagerActor.SendMessage(msg, id) if msg.underlyingMsg == expectedBru => id
    }
    bruRecipients should contain(peer2.id)
    bruRecipients should have size 1

    networkPeerManagerProbe.expectNoMessage()
  }

  // ---- isPoWChain gating: NewBlock to ETH69 peers --------------------------

  it should "send NewBlock to ETH69 peer when isPoWChain=true (PoW: TD signal for ECBP-1100)" taggedAs (
    UnitTest,
    SyncTest
  ) in new TestSetup {
    val peerLatestBlock = BigInt(1000)
    val eth69PeerInfo = eth69PeerInfoAt(peerLatestBlock)
    val blockHeader = baseBlockHeader.copy(number = peerLatestBlock + 1)
    val ourWeight = ChainWeight.totalDifficultyOnly(BigInt(99999))
    val block = Block(blockHeader, BlockBody(Nil, Nil))

    // isPoWChain=true is set on the default TestSetup blockBroadcast
    blockBroadcast.broadcastBlock(
      BlockToBroadcast(block, ourWeight),
      Map(peer.id -> PeerWithInfo(peer, eth69PeerInfo))
    )

    import scala.concurrent.duration._
    val messages = networkPeerManagerProbe.receiveN(3, 3.seconds)
    val newBlocks = messages.collect {
      case NetworkPeerManagerActor.SendMessage(msg, id) if msg.underlyingMsg.isInstanceOf[ETHPackets.NewBlock] => id
    }
    newBlocks should contain(peer.id) // ETH69 peer gets NewBlock on PoW chain
  }

  it should "NOT send NewBlock to ETH69 peer when isPoWChain=false (PoS: go-ethereum aligned)" taggedAs (
    UnitTest,
    SyncTest
  ) in new PoSTestSetup {
    val peerLatestBlock = BigInt(1000)
    val eth69PeerInfo = eth69PeerInfoAt(peerLatestBlock)
    val blockHeader = baseBlockHeader.copy(number = peerLatestBlock + 1)
    val ourWeight = ChainWeight.totalDifficultyOnly(BigInt(99999))
    val block = Block(blockHeader, BlockBody(Nil, Nil))

    // isPoWChain=false → no NewBlock to ETH69 peers
    blockBroadcast.broadcastBlock(
      BlockToBroadcast(block, ourWeight),
      Map(peer.id -> PeerWithInfo(peer, eth69PeerInfo))
    )

    import scala.concurrent.duration._
    // Only NewBlockHashes + BRU should arrive (no NewBlock)
    val messages = (1 to 2).map(_ => networkPeerManagerProbe.receiveOne(2.seconds))
    messages.foreach {
      case NetworkPeerManagerActor.SendMessage(msg, _) =>
        msg.underlyingMsg should not be an[ETHPackets.NewBlock]
      case _ =>
    }
    networkPeerManagerProbe.expectNoMessage()
  }

  // ---- isPoWChain gating: BRU frequency ------------------------------------

  it should "send BRU on every block when isPoWChain=true (3 blocks → 3 BRUs)" taggedAs (
    UnitTest,
    SyncTest
  ) in new TestSetup {
    val peerLatestBlock = BigInt(999)
    val eth69PeerInfo = eth69PeerInfoAt(peerLatestBlock)
    val ourWeight = ChainWeight.totalDifficultyOnly(BigInt(9999))

    // Broadcast 3 consecutive blocks
    val blocks = (1 to 3).map { i =>
      val hdr = baseBlockHeader.copy(number = peerLatestBlock + i)
      Block(hdr, BlockBody(Nil, Nil))
    }
    blocks.foreach { block =>
      blockBroadcast.broadcastBlock(
        BlockToBroadcast(block, ourWeight),
        Map(peer.id -> PeerWithInfo(peer, eth69PeerInfo))
      )
    }

    import scala.concurrent.duration._
    val allMessages = networkPeerManagerProbe.receiveN(9, 5.seconds) // 3 × (NewBlock + Hashes + BRU)
    val bruCount = allMessages.count {
      case NetworkPeerManagerActor.SendMessage(msg, _) =>
        msg.underlyingMsg.isInstanceOf[ETH69.BlockRangeUpdate]
      case _ => false
    }
    bruCount shouldEqual 3 // one BRU per block on PoW chain
    networkPeerManagerProbe.expectNoMessage()
  }

  it should "send BRU only at block 32 when isPoWChain=false (PoS epoch gate)" taggedAs (
    UnitTest,
    SyncTest
  ) in new PoSTestSetup {
    // Start from block 1 so the peer is behind on all 33 blocks
    val startBlock = BigInt(0)
    val eth69PeerInfo = eth69PeerInfoAt(startBlock)
    val ourWeight = ChainWeight.totalDifficultyOnly(BigInt(9999))

    def broadcastAt(n: Int): Unit = {
      val hdr = baseBlockHeader.copy(number = BigInt(n))
      val block = Block(hdr, BlockBody(Nil, Nil))
      blockBroadcast.broadcastBlock(
        BlockToBroadcast(block, ourWeight),
        Map(peer.id -> PeerWithInfo(peer, eth69PeerInfo))
      )
    }

    import scala.concurrent.duration._

    // Blocks 1–31: no BRU (not at epoch boundary)
    (1 to 31).foreach(broadcastAt)
    // Each block → NewBlockHashes only (PoS: no NewBlock, no BRU until block 32)
    val pre32Messages = (1 to 31).map(_ => networkPeerManagerProbe.receiveN(1, 2.seconds)).flatten
    val pre32Brus = pre32Messages.count {
      case NetworkPeerManagerActor.SendMessage(msg, _) => msg.underlyingMsg.isInstanceOf[ETH69.BlockRangeUpdate]
      case _                                           => false
    }
    pre32Brus shouldEqual 0 // no BRU before epoch boundary

    // Block 32: BRU fires
    broadcastAt(32)
    val block32Messages = networkPeerManagerProbe.receiveN(2, 2.seconds) // Hashes + BRU
    val block32Brus = block32Messages.count {
      case NetworkPeerManagerActor.SendMessage(msg, _) => msg.underlyingMsg.isInstanceOf[ETH69.BlockRangeUpdate]
      case _                                           => false
    }
    block32Brus shouldEqual 1 // BRU at epoch boundary (32 % 32 == 0)
    networkPeerManagerProbe.expectNoMessage()
  }

  it should "send NewBlock to ETH68 peer regardless of isPoWChain" taggedAs (
    UnitTest,
    SyncTest
  ) in {
    // Both PoW and PoS configurations must send NewBlock to ETH68 peers
    for (isPoW <- Seq(true, false))
      new TestKit(ActorSystem(s"BlockBroadcastSpec_eth68_$isPoW")) {
        val pm = TestProbe()
        val bb = new BlockBroadcast(pm.ref, isPoWChain = isPoW)

        val blockHeader = Fixtures.Blocks.Block3125369.header.copy(number = BigInt(1001))
        val ourWeight = ChainWeight.totalDifficultyOnly(BigInt(99999))
        val block = Block(blockHeader, BlockBody(Nil, Nil))

        val eth68Status = RemoteStatus(
          capability = Capability.ETH68,
          networkId = 1,
          chainWeight = ChainWeight.totalDifficultyOnly(BigInt(1000)),
          bestHash = Fixtures.Blocks.Block3125369.header.hash,
          genesisHash = Fixtures.Blocks.Genesis.header.hash
        )
        val eth68PeerInfo = PeerInfo(
          remoteStatus = eth68Status,
          chainWeight = eth68Status.chainWeight,
          forkAccepted = true,
          maxBlockNumber = BigInt(1000),
          bestBlockHash = eth68Status.bestHash
        )
        val p =
          Peer(PeerId(s"eth68peer-$isPoW"), new java.net.InetSocketAddress("127.0.0.1", 0), TestProbe().ref, false)

        bb.broadcastBlock(BlockToBroadcast(block, ourWeight), Map(p.id -> PeerWithInfo(p, eth68PeerInfo)))

        import scala.concurrent.duration._
        val messages = pm.receiveN(2, 3.seconds)
        val hasNewBlock = messages.exists {
          case NetworkPeerManagerActor.SendMessage(msg, _) => msg.underlyingMsg.isInstanceOf[ETHPackets.NewBlock]
          case _                                           => false
        }
        hasNewBlock shouldBe true // ETH68 always gets NewBlock
        pm.expectNoMessage()
        TestKit.shutdownActorSystem(system)
      }
  }

  it should "NEVER send BRU to ETH68 peer (BRU is ETH69-only)" taggedAs (
    UnitTest,
    SyncTest
  ) in new TestSetup {
    // ETH68 peer — should get NewBlock + NewBlockHashes, but NO BlockRangeUpdate
    val blockHeader = baseBlockHeader.copy(number = initialPeerInfo.maxBlockNumber + 1)
    val ourWeight = ChainWeight.totalDifficultyOnly(initialPeerInfo.chainWeight.totalDifficulty + 1)
    val block = Block(blockHeader, BlockBody(Nil, Nil))

    blockBroadcast.broadcastBlock(
      BlockToBroadcast(block, ourWeight),
      Map(peer.id -> PeerWithInfo(peer, initialPeerInfo)) // ETH68 peer from TestSetup
    )

    import scala.concurrent.duration._
    val messages = networkPeerManagerProbe.receiveN(2, 3.seconds)
    val hasBru = messages.exists {
      case NetworkPeerManagerActor.SendMessage(msg, _) => msg.underlyingMsg.isInstanceOf[ETH69.BlockRangeUpdate]
      case _                                           => false
    }
    hasBru shouldBe false // ETH68 peer never gets BRU
    networkPeerManagerProbe.expectNoMessage()
  }

  // -------------------------------------------------------------------------

  class TestSetup(implicit system: ActorSystem) {
    val networkPeerManagerProbe: TestProbe = TestProbe()

    val blockBroadcast = new BlockBroadcast(networkPeerManagerProbe.ref, isPoWChain = true)

    val baseBlockHeader = Fixtures.Blocks.Block3125369.header

    val peerStatus: RemoteStatus = RemoteStatus(
      capability = Capability.ETH68,
      networkId = 1,
      chainWeight = ChainWeight(BigInt(10000)),
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

    /** Build an ETH69 PeerInfo whose maxBlockNumber is `latestBlock`. */
    def eth69PeerInfoAt(latestBlock: BigInt): PeerInfo = {
      val status = RemoteStatus(
        capability = Capability.ETH69,
        networkId = 1,
        chainWeight = ChainWeight.totalDifficultyOnly(BigInt(9000)),
        bestHash = Fixtures.Blocks.Block3125369.header.hash,
        genesisHash = Fixtures.Blocks.Genesis.header.hash,
        latestBlock = Some(latestBlock)
      )
      PeerInfo(
        remoteStatus = status,
        chainWeight = status.chainWeight,
        forkAccepted = true,
        maxBlockNumber = latestBlock,
        bestBlockHash = status.bestHash
      )
    }
  }

  /** Same as TestSetup but with isPoWChain=false (PoS / ETH / Sepolia). */
  class PoSTestSetup(implicit system: ActorSystem) extends TestSetup {
    override val blockBroadcast = new BlockBroadcast(networkPeerManagerProbe.ref, isPoWChain = false)
  }
}
