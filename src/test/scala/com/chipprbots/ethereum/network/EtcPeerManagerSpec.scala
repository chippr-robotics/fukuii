package com.chipprbots.ethereum.network

import java.net.InetSocketAddress

import scala.concurrent.duration._

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.Props
import org.apache.pekko.testkit.TestActorRef
import org.apache.pekko.testkit.TestProbe
import org.apache.pekko.util.ByteString

import org.bouncycastle.util.encoders.Hex
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.Fixtures.Blocks.DaoForkBlock
import com.chipprbots.ethereum.Fixtures.Blocks.Genesis
import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.BlockBody
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.network.NetworkPeerManagerActor._
import com.chipprbots.ethereum.network.PeerActor.DisconnectPeer
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerEvent.MessageFromPeer
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerEvent.PeerDisconnected
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerEvent.PeerHandshakeSuccessful
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerSelector
import com.chipprbots.ethereum.network.PeerEventBusActor.Subscribe
import com.chipprbots.ethereum.network.PeerEventBusActor.SubscriptionClassifier._
import com.chipprbots.ethereum.network.p2p.messages.BaseETH6XMessages.NewBlock
import com.chipprbots.ethereum.network.p2p.messages.Capability
import com.chipprbots.ethereum.network.p2p.messages.Codes
import com.chipprbots.ethereum.network.p2p.messages.ETH62._
import com.chipprbots.ethereum.network.p2p.messages.WireProtocol.Disconnect
import com.chipprbots.ethereum.testing.Tags._
import com.chipprbots.ethereum.utils.Config

class EtcPeerManagerSpec extends AnyFlatSpec with Matchers {

  it should "start with the peers initial info as provided" taggedAs (UnitTest, NetworkTest) in new TestSetup {
    expectInitialSubscriptions()
    setupNewPeer(peer1, peer1Probe, peer1Info)
    setupNewPeer(peer2, peer2Probe, peer2Info)

    // PeersInfoRequest should work properly
    requestSender.send(peersInfoHolder, PeerInfoRequest(peer1.id))
    requestSender.expectMsg(PeerInfoResponse(Some(peer1Info)))
    requestSender.send(peersInfoHolder, PeerInfoRequest(peer2.id))
    requestSender.expectMsg(PeerInfoResponse(Some(peer2Info)))
    requestSender.send(peersInfoHolder, PeerInfoRequest(peer3.id))
    requestSender.expectMsg(PeerInfoResponse(None))

    // GetHandshakedPeers should work properly
    requestSender.send(peersInfoHolder, GetHandshakedPeers)
    requestSender.expectMsg(HandshakedPeers(Map(peer1 -> peer1Info, peer2 -> peer2Info)))
  }

  it should "update max peer when receiving new block ETH63" taggedAs (UnitTest, NetworkTest) in new TestSetup {
    expectInitialSubscriptions()
    setupNewPeer(peer1, peer1Probe, peer1Info)

    // given
    val newBlockWeight: ChainWeight = ChainWeight.totalDifficultyOnly(300)
    val firstHeader: BlockHeader = baseBlockHeader.copy(number = peer1Info.maxBlockNumber + 4)
    val firstBlock: NewBlock = NewBlock(Block(firstHeader, BlockBody(Nil, Nil)), newBlockWeight.totalDifficulty)

    val secondHeader: BlockHeader = baseBlockHeader.copy(number = peer2Info.maxBlockNumber + 2)
    val secondBlock: NewBlock = NewBlock(Block(secondHeader, BlockBody(Nil, Nil)), newBlockWeight.totalDifficulty)

    // when
    peersInfoHolder ! MessageFromPeer(firstBlock, peer1.id)
    peersInfoHolder ! MessageFromPeer(secondBlock, peer1.id)

    // then
    requestSender.send(peersInfoHolder, PeerInfoRequest(peer1.id))
    val expectedPeerInfo: PeerInfo = initialPeerInfo
      .withBestBlockData(initialPeerInfo.maxBlockNumber + 4, firstHeader.hash)
      .withChainWeight(newBlockWeight)
    requestSender.expectMsg(PeerInfoResponse(Some(expectedPeerInfo)))
  }

  it should "update max peer when receiving block header" taggedAs (UnitTest, NetworkTest) in new TestSetup {
    expectInitialSubscriptions()
    setupNewPeer(peer1, peer1Probe, peer1Info)

    // given
    val firstHeader: BlockHeader = baseBlockHeader.copy(number = peer1Info.maxBlockNumber + 4)
    val secondHeader: BlockHeader = baseBlockHeader.copy(number = peer1Info.maxBlockNumber + 2)

    // when
    peersInfoHolder ! MessageFromPeer(
      BlockHeaders(Seq(firstHeader, secondHeader, blockchainReader.genesisHeader)),
      peer1.id
    )

    // then
    requestSender.send(peersInfoHolder, PeerInfoRequest(peer1.id))
    requestSender.expectMsg(
      PeerInfoResponse(Some(peer1Info.withBestBlockData(initialPeerInfo.maxBlockNumber + 4, firstHeader.hash)))
    )
  }

  it should "update max peer when receiving new block hashes" taggedAs (UnitTest, NetworkTest) in new TestSetup {
    expectInitialSubscriptions()
    setupNewPeer(peer1, peer1Probe, peer1Info)

    // given
    val firstBlockHash: BlockHash = BlockHash(ByteString(Hex.decode("00" * 32)), peer1Info.maxBlockNumber + 2)
    val secondBlockHash: BlockHash = BlockHash(ByteString(Hex.decode("01" * 32)), peer1Info.maxBlockNumber + 5)

    // when
    peersInfoHolder ! MessageFromPeer(NewBlockHashes(Seq(firstBlockHash, secondBlockHash)), peer1.id)

    // then
    requestSender.send(peersInfoHolder, PeerInfoRequest(peer1.id))
    requestSender.expectMsg(
      PeerInfoResponse(Some(peer1Info.withBestBlockData(peer1Info.maxBlockNumber + 5, secondBlockHash.hash)))
    )
  }

  it should "update max peer block when receiving ETH69 BlockRangeUpdate" taggedAs (
    UnitTest,
    NetworkTest
  ) in new TestSetup {
    // ETH/69 peers send BlockRangeUpdate instead of NewBlock to announce their chain tip.
    // NetworkPeerManagerActor must update peerInfo.maxBlockNumber from BlockRangeUpdate.latestBlock.
    import com.chipprbots.ethereum.network.p2p.messages.ETH69

    expectInitialSubscriptions()
    setupNewPeer(peer1, peer1Probe, peer1Info)

    val newLatestBlock = peer1Info.maxBlockNumber + 7
    val newLatestBlockHash = ByteString(Array.fill(32)(0xab.toByte))
    val blockRangeUpdate = ETH69.BlockRangeUpdate(
      earliestBlock = BigInt(0),
      latestBlock = newLatestBlock,
      latestBlockHash = newLatestBlockHash
    )

    peersInfoHolder ! MessageFromPeer(blockRangeUpdate, peer1.id)

    requestSender.send(peersInfoHolder, PeerInfoRequest(peer1.id))
    requestSender.expectMsg(
      PeerInfoResponse(Some(peer1Info.withBestBlockData(newLatestBlock, newLatestBlockHash)))
    )
  }

  it should "ignore ETH69 BlockRangeUpdate when latestBlock is not higher than current" taggedAs (
    UnitTest,
    NetworkTest
  ) in new TestSetup {
    import com.chipprbots.ethereum.network.p2p.messages.ETH69

    expectInitialSubscriptions()
    setupNewPeer(peer1, peer1Probe, peer1Info)

    // Send a BlockRangeUpdate with a lower block number — peer info should not regress
    val staleLatestBlock = peer1Info.maxBlockNumber - 1
    val blockRangeUpdate = ETH69.BlockRangeUpdate(
      earliestBlock = BigInt(0),
      latestBlock = staleLatestBlock,
      latestBlockHash = ByteString(Array.fill(32)(0xcc.toByte))
    )

    peersInfoHolder ! MessageFromPeer(blockRangeUpdate, peer1.id)

    requestSender.send(peersInfoHolder, PeerInfoRequest(peer1.id))
    // maxBlockNumber should remain unchanged
    requestSender.expectMsg(PeerInfoResponse(Some(peer1Info)))
  }

  it should "update the peer total difficulty when receiving a NewBlock" taggedAs (
    UnitTest,
    NetworkTest
  ) in new TestSetup {
    expectInitialSubscriptions()
    setupNewPeer(peer1, peer1Probe, peer1Info)

    // given
    val newBlock: NewBlock = NewBlock(baseBlock, initialPeerInfo.chainWeight.totalDifficulty + 1)

    // when
    peersInfoHolder ! MessageFromPeer(newBlock, peer1.id)

    // then
    requestSender.send(peersInfoHolder, PeerInfoRequest(peer1.id))
    requestSender.expectMsg(
      PeerInfoResponse(Some(peer1Info.withChainWeight(ChainWeight.totalDifficultyOnly(newBlock.totalDifficulty))))
    )
  }

  it should "update the fork accepted when receiving the fork block" taggedAs (UnitTest, NetworkTest) in new TestSetup {
    expectInitialSubscriptions()
    setupNewPeer(peer1, peer1Probe, peer1Info)

    // given
    val blockHeaders: BlockHeaders = BlockHeaders(Seq(DaoForkBlock.header))

    // when
    peersInfoHolder ! MessageFromPeer(blockHeaders, peer1.id)

    // then
    requestSender.send(peersInfoHolder, PeerInfoRequest(peer1.id))
    requestSender.expectMsg(PeerInfoResponse(Some(peer1Info.withForkAccepted(true))))
  }

  it should "disconnect from a peer with different fork block" taggedAs (UnitTest, NetworkTest) in new TestSetup {
    expectInitialSubscriptions()
    setupNewPeer(peer1, peer1Probe, peer1Info)

    // given
    val blockHeaders: BlockHeaders =
      BlockHeaders(Seq(Genesis.header.copy(number = Fixtures.Blocks.DaoForkBlock.header.number)))

    // when
    peersInfoHolder ! MessageFromPeer(blockHeaders, peer1.id)

    // then
    requestSender.send(peersInfoHolder, PeerInfoRequest(peer1.id))
    requestSender.expectMsg(PeerInfoResponse(Some(peer1Info)))
    peer1Probe.expectMsg(DisconnectPeer(Disconnect.Reasons.UselessPeer))
  }

  it should "remove peers information when a peers is disconnected" taggedAs (UnitTest, NetworkTest) in new TestSetup {
    expectInitialSubscriptions()

    setupNewPeer(peer1, peer1Probe, peer1Info)
    setupNewPeer(peer2, peer2Probe, peer2Info)

    peersInfoHolder ! PeerDisconnected(peer2.id)

    // PeersInfoRequest should work properly
    requestSender.send(peersInfoHolder, PeerInfoRequest(peer1.id))
    requestSender.expectMsg(PeerInfoResponse(Some(peer1Info)))
    requestSender.send(peersInfoHolder, PeerInfoRequest(peer2.id))
    requestSender.expectMsg(PeerInfoResponse(None))
    requestSender.send(peersInfoHolder, PeerInfoRequest(peer3.id))
    requestSender.expectMsg(PeerInfoResponse(None))

    // GetHandshakedPeers should work properly
    requestSender.send(peersInfoHolder, GetHandshakedPeers)
    requestSender.expectMsg(HandshakedPeers(Map(peer1 -> peer1Info)))

    peersInfoHolder ! PeerDisconnected(peer1.id)

    // PeersInfoRequest should work properly
    requestSender.send(peersInfoHolder, PeerInfoRequest(peer1.id))
    requestSender.expectMsg(PeerInfoResponse(None))
    requestSender.send(peersInfoHolder, PeerInfoRequest(peer2.id))
    requestSender.expectMsg(PeerInfoResponse(None))
    requestSender.send(peersInfoHolder, PeerInfoRequest(peer3.id))
    requestSender.expectMsg(PeerInfoResponse(None))

    // GetHandshakedPeers should work properly
    requestSender.send(peersInfoHolder, GetHandshakedPeers)
    requestSender.expectMsg(HandshakedPeers(Map.empty))
  }

  it should "provide handshaked peers only with best block number determined" taggedAs (
    UnitTest,
    NetworkTest
  ) in new TestSetup {
    expectInitialSubscriptions()
    // Freshly handshaked peer without best block determined
    setupNewPeer(freshPeer, freshPeerProbe, freshPeerInfo.copy(maxBlockNumber = 0))

    // All handshaked peers are now returned immediately (peerHasUpdatedBestBlock = always true,
    // Besu-aligned: ETH/68 peers always have maxBlockNumber=0 at handshake; gating deadlocks them)
    requestSender.send(peersInfoHolder, GetHandshakedPeers)
    requestSender.expectMsg(HandshakedPeers(Map(freshPeer -> freshPeerInfo.copy(maxBlockNumber = 0))))

    val newMaxBlock: BigInt = freshPeerInfo.maxBlockNumber + 1
    val firstHeader: BlockHeader = baseBlockHeader.copy(number = newMaxBlock)

    // Fresh peer received best block
    peersInfoHolder ! MessageFromPeer(BlockHeaders(Seq(firstHeader)), freshPeer.id)

    // After receiving peer best block number, peer should be provided as handshaked peer
    requestSender.send(peersInfoHolder, GetHandshakedPeers)
    requestSender.expectMsg(
      HandshakedPeers(Map(freshPeer -> freshPeerInfo.withBestBlockData(newMaxBlock, firstHeader.hash)))
    )
  }

  it should "provide handshaked peers only with best block number determined even if peers best block is its genesis" taggedAs (
    UnitTest,
    NetworkTest
  ) in new TestSetup {
    expectInitialSubscriptions()

    val genesisInfo: PeerInfo = createGenesisPeerInfo()

    // Freshly handshaked peer without best block determined
    setupNewPeer(freshPeer, freshPeerProbe, genesisInfo)

    // if peer best block is its genesis block then it is available right from the start
    requestSender.send(peersInfoHolder, GetHandshakedPeers)
    requestSender.expectMsg(HandshakedPeers(Map(freshPeer -> genesisInfo)))

    // Fresh peer received best block
    peersInfoHolder ! MessageFromPeer(BlockHeaders(Seq(Fixtures.Blocks.Genesis.header)), freshPeer.id)

    // receiving best block does not change a thing, as peer best block is it genesis
    requestSender.send(peersInfoHolder, GetHandshakedPeers)
    requestSender.expectMsg(HandshakedPeers(Map(freshPeer -> genesisInfo)))
  }

  it should "skip GetBlockHeaders request when peer is at genesis to avoid disconnect" taggedAs (
    UnitTest,
    NetworkTest
  ) in new TestSetup {
    expectInitialSubscriptions()

    // Create a peer at genesis (bestHash == genesisHash)
    val genesisInfo: PeerInfo = createGenesisPeerInfo()

    // Send handshake successful for peer at genesis
    peersInfoHolder ! PeerHandshakeSuccessful(peer1, genesisInfo)

    // Expect subscriptions as usual
    peerEventBus.expectMsg(Subscribe(PeerDisconnectedClassifier(PeerSelector.WithId(peer1.id))))
    peerEventBus.expectMsg(
      Subscribe(
        MessageClassifier(
          Set(
            Codes.BlockHeadersCode,
            Codes.NewBlockCode,
            Codes.NewBlockHashesCode,
            Codes.BlockRangeUpdateCode,
            // SNAP protocol response codes
            com.chipprbots.ethereum.network.p2p.messages.SNAP.Codes.AccountRangeCode,
            com.chipprbots.ethereum.network.p2p.messages.SNAP.Codes.StorageRangesCode,
            com.chipprbots.ethereum.network.p2p.messages.SNAP.Codes.TrieNodesCode,
            com.chipprbots.ethereum.network.p2p.messages.SNAP.Codes.ByteCodesCode,
            // SNAP protocol request codes — server-side serving
            com.chipprbots.ethereum.network.p2p.messages.SNAP.Codes.GetAccountRangeCode,
            com.chipprbots.ethereum.network.p2p.messages.SNAP.Codes.GetStorageRangesCode,
            com.chipprbots.ethereum.network.p2p.messages.SNAP.Codes.GetTrieNodesCode,
            com.chipprbots.ethereum.network.p2p.messages.SNAP.Codes.GetByteCodesCode
          ),
          PeerSelector.WithId(peer1.id)
        )
      )
    )

    // Verify NO GetBlockHeaders request is sent to avoid disconnect with reason 0x10 (Other)
    // Many peers disconnect genesis-only nodes as a peer selection policy
    peer1Probe.expectNoMessage()
    peerManager.expectNoMessage(100.millis)

    // Verify peer is still added to handshaked peers
    requestSender.send(peersInfoHolder, PeerInfoRequest(peer1.id))
    requestSender.expectMsg(PeerInfoResponse(Some(genesisInfo)))
  }

  it should "send a best-block probe (GetBlockHeaders by bestHash) after handshake on ETH/64-/68" taggedAs (
    UnitTest,
    NetworkTest
  ) in new TestSetup {
    expectInitialSubscriptions()

    // peer1Info is built with capability = ETH63 above; override to ETH68 (modern peer)
    // and pin maxBlockNumber to 0 so we exercise the not-yet-known-number path.
    val eth68Status = peer1Info.remoteStatus.copy(capability = Capability.ETH68)
    val eth68Info = peer1Info.copy(remoteStatus = eth68Status, maxBlockNumber = 0)

    peersInfoHolder ! PeerHandshakeSuccessful(peer1, eth68Info)

    // Drain the two subscriptions that always follow handshake.
    peerEventBus.expectMsg(Subscribe(PeerDisconnectedClassifier(PeerSelector.WithId(peer1.id))))
    peerEventBus.expectMsgClass(classOf[Subscribe])

    // The probe should land on the peerManager TestProbe as a SendMessage to peer1.
    val sent = peerManager.expectMsgClass(classOf[PeerManagerActor.SendMessage])
    sent.peerId shouldBe peer1.id
    sent.message.code shouldBe Codes.GetBlockHeadersCode
    // ETH/66+ uses request-id-prefixed envelope.
    sent.message.underlyingMsg shouldBe a[com.chipprbots.ethereum.network.p2p.messages.ETH66.GetBlockHeaders]
    val gbh =
      sent.message.underlyingMsg.asInstanceOf[com.chipprbots.ethereum.network.p2p.messages.ETH66.GetBlockHeaders]
    gbh.block shouldBe Right(eth68Info.remoteStatus.bestHash)
    gbh.maxHeaders shouldBe BigInt(1)
    gbh.skip shouldBe BigInt(0)
    gbh.reverse shouldBe false
  }

  it should "skip the best-block probe on ETH/69 (number is in STATUS)" taggedAs (
    UnitTest,
    NetworkTest
  ) in new TestSetup {
    expectInitialSubscriptions()

    val eth69Status = peer1Info.remoteStatus.copy(capability = Capability.ETH69)
    val eth69Info = peer1Info.copy(remoteStatus = eth69Status)

    peersInfoHolder ! PeerHandshakeSuccessful(peer1, eth69Info)

    // Drain the two subscriptions and assert no probe.
    peerEventBus.expectMsg(Subscribe(PeerDisconnectedClassifier(PeerSelector.WithId(peer1.id))))
    peerEventBus.expectMsgClass(classOf[Subscribe])
    peerManager.expectNoMessage(100.millis)
  }

  it should "discover peer block number from probe response on ETH/64-/68" taggedAs (
    UnitTest,
    NetworkTest
  ) in new TestSetup {
    expectInitialSubscriptions()

    // ETH/68 peer with no known block number yet.
    val eth68Status = peer1Info.remoteStatus.copy(capability = Capability.ETH68)
    val eth68Info = peer1Info.copy(remoteStatus = eth68Status, maxBlockNumber = 0)

    setupNewPeer(peer1, peer1Probe, eth68Info)

    // Probe response arrives via the existing BlockHeadersCode subscription. The
    // header carries the bestHash from STATUS and a real block number; updateMaxBlock
    // should pick up the number and write it into PeerInfo.maxBlockNumber.
    val probeReply = baseBlockHeader.copy(number = 24463116)
    peersInfoHolder ! MessageFromPeer(BlockHeaders(Seq(probeReply)), peer1.id)

    requestSender.send(peersInfoHolder, PeerInfoRequest(peer1.id))
    val resp = requestSender.expectMsgType[PeerInfoResponse]
    resp.peerInfo.map(_.maxBlockNumber) shouldBe Some(BigInt(24463116))
  }

  it should "route SNAP protocol messages to registered SNAPSyncController" taggedAs (
    UnitTest,
    NetworkTest
  ) in new TestSetupWithSnapSync {
    expectInitialSubscriptions()

    // Register SNAP sync controller
    peersInfoHolder ! RegisterSnapSyncController(snapSyncController.ref)

    // Setup a peer
    setupNewPeer(peer1, peer1Probe, peer1Info)

    // Create SNAP protocol messages
    import com.chipprbots.ethereum.network.p2p.messages.SNAP._

    val accountRange = AccountRange(
      requestId = BigInt(1),
      accounts = Seq.empty,
      proof = Seq.empty
    )

    val storageRanges = StorageRanges(
      requestId = BigInt(2),
      slots = Seq.empty,
      proof = Seq.empty
    )

    val trieNodes = TrieNodes(
      requestId = BigInt(3),
      nodes = Seq.empty
    )

    val byteCodes = ByteCodes(
      requestId = BigInt(4),
      codes = Seq.empty
    )

    // When SNAP messages are received from peer
    peersInfoHolder ! MessageFromPeer(accountRange, peer1.id)
    peersInfoHolder ! MessageFromPeer(storageRanges, peer1.id)
    peersInfoHolder ! MessageFromPeer(trieNodes, peer1.id)
    peersInfoHolder ! MessageFromPeer(byteCodes, peer1.id)

    // Then they should be routed to SNAPSyncController
    snapSyncController.expectMsg(accountRange)
    snapSyncController.expectMsg(storageRanges)
    snapSyncController.expectMsg(trieNodes)
    snapSyncController.expectMsg(byteCodes)
  }

  it should "handle SNAP messages gracefully when SNAPSyncController is not registered" taggedAs (
    UnitTest,
    NetworkTest
  ) in new TestSetup {
    expectInitialSubscriptions()

    // Setup a peer without registering SNAP sync controller
    setupNewPeer(peer1, peer1Probe, peer1Info)

    // Create a SNAP protocol message
    import com.chipprbots.ethereum.network.p2p.messages.SNAP._

    val accountRange = AccountRange(
      requestId = BigInt(1),
      accounts = Seq.empty,
      proof = Seq.empty
    )

    // When SNAP message is received without registered controller
    // It should not crash, just ignore the routing
    peersInfoHolder ! MessageFromPeer(accountRange, peer1.id)

    // Peer info should still be updated normally
    requestSender.send(peersInfoHolder, PeerInfoRequest(peer1.id))
    requestSender.expectMsg(PeerInfoResponse(Some(peer1Info)))
  }

  trait TestSetup extends EphemBlockchainTestSetup {
    implicit override lazy val system: ActorSystem = ActorSystem("PeersInfoHolderSpec_System")

    blockchainWriter.storeBlockHeader(Fixtures.Blocks.Genesis.header).commit()

    override lazy val blockchainConfig = Config.blockchains.blockchainConfig
    val forkResolver = new ForkResolver.EtcForkResolver(blockchainConfig.daoForkConfig.get)

    val peerStatus: RemoteStatus = RemoteStatus(
      capability = Capability.ETH63,
      networkId = 1,
      chainWeight = ChainWeight.totalDifficultyOnly(10000),
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

    // Helper to create a PeerInfo for a peer at genesis
    // Sets both bestHash and genesisHash to ensure isAtGenesis() returns true.
    // In production, genesisHash should already be set correctly from handshake,
    // but we explicitly set both here for test clarity and to avoid test brittleness.
    def createGenesisPeerInfo(basePeerInfo: PeerInfo = initialPeerInfo): PeerInfo = {
      val genesisHash = Fixtures.Blocks.Genesis.header.hash
      val genesisStatus: RemoteStatus = basePeerInfo.remoteStatus.copy(
        bestHash = genesisHash,
        genesisHash = genesisHash // Explicitly set to match bestHash for isAtGenesis() == true
      )
      basePeerInfo.copy(
        remoteStatus = genesisStatus,
        maxBlockNumber = Fixtures.Blocks.Genesis.header.number,
        bestBlockHash = genesisHash
      )
    }

    val fakeNodeId: ByteString = ByteString()

    val peer1Probe: TestProbe = TestProbe()
    val peer1: Peer =
      Peer(PeerId("peer1"), new InetSocketAddress("127.0.0.1", 1), peer1Probe.ref, false, nodeId = Some(fakeNodeId))
    val peer1Info: PeerInfo = initialPeerInfo.withForkAccepted(false)
    val peer2Probe: TestProbe = TestProbe()
    val peer2: Peer =
      Peer(PeerId("peer2"), new InetSocketAddress("127.0.0.1", 2), peer2Probe.ref, false, nodeId = Some(fakeNodeId))
    val peer2Info: PeerInfo = initialPeerInfo.withForkAccepted(false)
    val peer3Probe: TestProbe = TestProbe()
    val peer3: Peer =
      Peer(PeerId("peer3"), new InetSocketAddress("127.0.0.1", 3), peer3Probe.ref, false, nodeId = Some(fakeNodeId))

    val freshPeerProbe: TestProbe = TestProbe()
    val freshPeer: Peer =
      Peer(PeerId(""), new InetSocketAddress("127.0.0.1", 4), freshPeerProbe.ref, false, nodeId = Some(fakeNodeId))
    val freshPeerInfo: PeerInfo = initialPeerInfo.withForkAccepted(false)

    val peerManager: TestProbe = TestProbe()
    val peerEventBus: TestProbe = TestProbe()

    val peersInfoHolder: TestActorRef[Nothing] = TestActorRef(
      Props(
        new NetworkPeerManagerActor(
          peerManager.ref,
          peerEventBus.ref,
          storagesInstance.storages.appStateStorage,
          Some(forkResolver)
        )
      )
    )

    val requestSender: TestProbe = TestProbe()

    val baseBlockHeader = Fixtures.Blocks.Block3125369.header
    val baseBlockBody: BlockBody = BlockBody(Nil, Nil)
    val baseBlock: Block = Block(baseBlockHeader, baseBlockBody)

    // NetworkPeerManagerActor subscribes at construction to (1) PeerHandshaked, and
    // (2) a global MessageClassifier for SNAP request codes so that hive test peers
    // — which fire GetAccountRange immediately after Hello, before PeerHandshakeSuccessful
    // — still reach the handler. Tests that expect the initial subscriptions must
    // consume both.
    def expectInitialSubscriptions(): Unit = {
      peerEventBus.expectMsg(Subscribe(PeerHandshaked))
      peerEventBus.expectMsg(
        Subscribe(
          MessageClassifier(
            Set(
              com.chipprbots.ethereum.network.p2p.messages.SNAP.Codes.GetAccountRangeCode,
              com.chipprbots.ethereum.network.p2p.messages.SNAP.Codes.GetStorageRangesCode,
              com.chipprbots.ethereum.network.p2p.messages.SNAP.Codes.GetTrieNodesCode,
              com.chipprbots.ethereum.network.p2p.messages.SNAP.Codes.GetByteCodesCode
            ),
            PeerSelector.AllPeers
          )
        )
      )
    }

    def setupNewPeer(peer: Peer, peerProbe: TestProbe, peerInfo: PeerInfo): Unit = {

      peersInfoHolder ! PeerHandshakeSuccessful(peer, peerInfo)

      peerEventBus.expectMsg(Subscribe(PeerDisconnectedClassifier(PeerSelector.WithId(peer.id))))

      peerEventBus.expectMsg(
        Subscribe(
          MessageClassifier(
            Set(
              Codes.BlockHeadersCode,
              Codes.NewBlockCode,
              Codes.NewBlockHashesCode,
              Codes.BlockRangeUpdateCode,
              // SNAP protocol response codes
              com.chipprbots.ethereum.network.p2p.messages.SNAP.Codes.AccountRangeCode,
              com.chipprbots.ethereum.network.p2p.messages.SNAP.Codes.StorageRangesCode,
              com.chipprbots.ethereum.network.p2p.messages.SNAP.Codes.TrieNodesCode,
              com.chipprbots.ethereum.network.p2p.messages.SNAP.Codes.ByteCodesCode,
              // SNAP protocol request codes — server-side serving
              com.chipprbots.ethereum.network.p2p.messages.SNAP.Codes.GetAccountRangeCode,
              com.chipprbots.ethereum.network.p2p.messages.SNAP.Codes.GetStorageRangesCode,
              com.chipprbots.ethereum.network.p2p.messages.SNAP.Codes.GetTrieNodesCode,
              com.chipprbots.ethereum.network.p2p.messages.SNAP.Codes.GetByteCodesCode
            ),
            PeerSelector.WithId(peer.id)
          )
        )
      )

      // After handshake completes, NetworkPeerManagerActor issues a Besu-style
      // best-block probe (GetBlockHeaders by bestHash, count=1) on ETH/64-/68 so the
      // peer's block number can be discovered — STATUS doesn't carry it on those
      // protocol versions. The probe is routed via `peerManagerActor ! SendMessage`,
      // so it lands on the `peerManager` TestProbe, never on the per-peer `peerProbe`.
      // Genesis peers and ETH/69 peers are skipped (see dedicated tests below).
      peerProbe.expectNoMessage(100.millis)
      val nonGenesis = peerInfo.remoteStatus.bestHash != peerInfo.remoteStatus.genesisHash
      val notEth69 = peerInfo.remoteStatus.capability != Capability.ETH69
      if (nonGenesis && notEth69) {
        val probe = peerManager.expectMsgClass(classOf[PeerManagerActor.SendMessage])
        probe.peerId shouldBe peer.id
        probe.message.code shouldBe Codes.GetBlockHeadersCode
      }
    }
  }

  trait TestSetupWithSnapSync extends TestSetup {
    val snapSyncController: TestProbe = TestProbe()
  }

}
