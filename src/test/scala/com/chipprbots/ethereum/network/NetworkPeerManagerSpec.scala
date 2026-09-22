package com.chipprbots.ethereum.network

import java.net.InetSocketAddress

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.testkit.TestProbe
import org.apache.pekko.util.ByteString

import scala.concurrent.duration.*

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
import com.chipprbots.ethereum.domain.BlockNumber
import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.*
import com.chipprbots.ethereum.network.PeerActor.DisconnectPeer
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerEvent.MessageFromPeer
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerEvent.PeerDisconnected
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerEvent.PeerHandshakeSuccessful
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerSelector
import com.chipprbots.ethereum.network.PeerEventBusActor.SubscribeCmd
import com.chipprbots.ethereum.network.PeerEventBusActor.SubscriptionClassifier.*
import com.chipprbots.ethereum.network.p2p.messages.Capability
import com.chipprbots.ethereum.network.p2p.messages.Codes
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.BlockRangeUpdate
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.BlockHeaders
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.GetBlockHeaders
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.NewBlock
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.NewBlockHashes.BlockHash
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.NewBlockHashes.NewBlockHashes
import com.chipprbots.ethereum.network.p2p.messages.WireProtocol.Disconnect
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.Config

class NetworkPeerManagerSpec extends AnyFlatSpec with Matchers:

  it should "start with the peers initial info as provided" taggedAs (UnitTest, NetworkTest) in new TestSetup:
    expectInitialSubscriptions()
    setupNewPeer(peer1, peer1Probe, peer1Info)
    setupNewPeer(peer2, peer2Probe, peer2Info)

    // PeersInfoRequest should work properly
    peersInfoHolder ! PeerInfoRequestCmd(peer1.id, requestSender.ref)
    requestSender.expectMsg(PeerInfoResponse(Some(peer1Info)))
    peersInfoHolder ! PeerInfoRequestCmd(peer2.id, requestSender.ref)
    requestSender.expectMsg(PeerInfoResponse(Some(peer2Info)))
    peersInfoHolder ! PeerInfoRequestCmd(peer3.id, requestSender.ref)
    requestSender.expectMsg(PeerInfoResponse(None))

    // GetHandshakedPeers should work properly
    peersInfoHolder ! GetHandshakedPeersCmd(requestSender.ref)
    requestSender.expectMsg(HandshakedPeers(Map(peer1 -> peer1Info, peer2 -> peer2Info)))

  it should "update max peer when receiving new block (ETH68)" taggedAs (UnitTest, NetworkTest) in new TestSetup:
    expectInitialSubscriptions()
    setupNewPeer(peer1, peer1Probe, peer1Info)

    // given
    val newBlockWeight: ChainWeight = ChainWeight.totalDifficultyOnly(300)
    val firstHeader: BlockHeader = baseBlockHeader.copy(number = BlockNumber(peer1Info.maxBlockNumber + 4))
    val firstBlock: NewBlock = NewBlock(Block(firstHeader, BlockBody(Nil, Nil)), newBlockWeight.totalDifficulty.value)

    val secondHeader: BlockHeader = baseBlockHeader.copy(number = BlockNumber(peer2Info.maxBlockNumber + 2))
    val secondBlock: NewBlock = NewBlock(Block(secondHeader, BlockBody(Nil, Nil)), newBlockWeight.totalDifficulty.value)

    // when
    peersInfoHolder ! PeerEventCmd(MessageFromPeer(firstBlock, peer1.id))
    peersInfoHolder ! PeerEventCmd(MessageFromPeer(secondBlock, peer1.id))

    // then
    peersInfoHolder ! PeerInfoRequestCmd(peer1.id, requestSender.ref)
    val expectedPeerInfo: PeerInfo = initialPeerInfo
      .withBestBlockData(initialPeerInfo.maxBlockNumber + 4, firstHeader.hash.value)
      .withChainWeight(newBlockWeight)
    requestSender.expectMsg(PeerInfoResponse(Some(expectedPeerInfo)))

  it should "update max peer when receiving block header" taggedAs (UnitTest, NetworkTest) in new TestSetup:
    expectInitialSubscriptions()
    setupNewPeer(peer1, peer1Probe, peer1Info)

    // given
    val firstHeader: BlockHeader = baseBlockHeader.copy(number = BlockNumber(peer1Info.maxBlockNumber + 4))
    val secondHeader: BlockHeader = baseBlockHeader.copy(number = BlockNumber(peer1Info.maxBlockNumber + 2))

    // when
    peersInfoHolder ! PeerEventCmd(
      MessageFromPeer(BlockHeaders(BigInt(0), Seq(firstHeader, secondHeader, blockchainReader.genesisHeader)), peer1.id)
    )

    // then
    peersInfoHolder ! PeerInfoRequestCmd(peer1.id, requestSender.ref)
    requestSender.expectMsg(
      PeerInfoResponse(Some(peer1Info.withBestBlockData(initialPeerInfo.maxBlockNumber + 4, firstHeader.hash.value)))
    )

  it should "update max peer when receiving new block hashes" taggedAs (UnitTest, NetworkTest) in new TestSetup:
    expectInitialSubscriptions()
    setupNewPeer(peer1, peer1Probe, peer1Info)

    // given
    val firstBlockHash: BlockHash = BlockHash(ByteString(Hex.decode("00" * 32)), peer1Info.maxBlockNumber + 2)
    val secondBlockHash: BlockHash = BlockHash(ByteString(Hex.decode("01" * 32)), peer1Info.maxBlockNumber + 5)

    // when
    peersInfoHolder ! PeerEventCmd(MessageFromPeer(NewBlockHashes(Seq(firstBlockHash, secondBlockHash)), peer1.id))

    // then
    peersInfoHolder ! PeerInfoRequestCmd(peer1.id, requestSender.ref)
    requestSender.expectMsg(
      PeerInfoResponse(Some(peer1Info.withBestBlockData(peer1Info.maxBlockNumber + 5, secondBlockHash.hash)))
    )

  it should "update max peer block when receiving ETH69 BlockRangeUpdate" taggedAs (
    UnitTest,
    NetworkTest
  ) in new TestSetup:
    // ETH/69 peers send BlockRangeUpdate instead of NewBlock to announce their chain tip.
    // NetworkPeerManagerActor must update peerInfo.maxBlockNumber from BlockRangeUpdate.latestBlock.
    // The inbound decoder produces ETHPackets.BlockRangeUpdate (not ETH69.BlockRangeUpdate).
    expectInitialSubscriptions()
    setupNewPeer(peer1, peer1Probe, peer1Info)

    val newLatestBlock: BigInt = peer1Info.maxBlockNumber + 7
    val newLatestBlockHash: ByteString = ByteString(Array.fill(32)(0xab.toByte))
    val blockRangeUpdate: BlockRangeUpdate = BlockRangeUpdate(
      earliestBlock = BigInt(0),
      latestBlock = newLatestBlock,
      latestBlockHash = newLatestBlockHash
    )

    peersInfoHolder ! PeerEventCmd(MessageFromPeer(blockRangeUpdate, peer1.id))

    peersInfoHolder ! PeerInfoRequestCmd(peer1.id, requestSender.ref)
    requestSender.expectMsg(
      PeerInfoResponse(Some(peer1Info.withBestBlockData(newLatestBlock, newLatestBlockHash)))
    )

  it should "ignore ETH69 BlockRangeUpdate when latestBlock is not higher than current" taggedAs (
    UnitTest,
    NetworkTest
  ) in new TestSetup:
    expectInitialSubscriptions()
    setupNewPeer(peer1, peer1Probe, peer1Info)

    // Send a BlockRangeUpdate with a lower block number — peer info should not regress
    // The inbound decoder produces ETHPackets.BlockRangeUpdate (not ETH69.BlockRangeUpdate).
    val staleLatestBlock: BigInt = peer1Info.maxBlockNumber - 1
    val blockRangeUpdate: BlockRangeUpdate = BlockRangeUpdate(
      earliestBlock = BigInt(0),
      latestBlock = staleLatestBlock,
      latestBlockHash = ByteString(Array.fill(32)(0xcc.toByte))
    )

    peersInfoHolder ! PeerEventCmd(MessageFromPeer(blockRangeUpdate, peer1.id))

    peersInfoHolder ! PeerInfoRequestCmd(peer1.id, requestSender.ref)
    // maxBlockNumber should remain unchanged
    requestSender.expectMsg(PeerInfoResponse(Some(peer1Info)))

  it should "update the peer total difficulty when receiving a NewBlock" taggedAs (
    UnitTest,
    NetworkTest
  ) in new TestSetup:
    expectInitialSubscriptions()
    setupNewPeer(peer1, peer1Probe, peer1Info)

    // given
    val newBlock: NewBlock = NewBlock(baseBlock, initialPeerInfo.chainWeight.totalDifficulty.value + 1)

    // when
    peersInfoHolder ! PeerEventCmd(MessageFromPeer(newBlock, peer1.id))

    // then
    peersInfoHolder ! PeerInfoRequestCmd(peer1.id, requestSender.ref)
    requestSender.expectMsg(
      PeerInfoResponse(Some(peer1Info.withChainWeight(ChainWeight.totalDifficultyOnly(newBlock.totalDifficulty))))
    )

  it should "update the fork accepted when receiving the fork block" taggedAs (UnitTest, NetworkTest) in new TestSetup:
    expectInitialSubscriptions()
    setupNewPeer(peer1, peer1Probe, peer1Info)

    // given
    val blockHeaders: BlockHeaders = BlockHeaders(BigInt(0), Seq(DaoForkBlock.header))

    // when
    peersInfoHolder ! PeerEventCmd(MessageFromPeer(blockHeaders, peer1.id))

    // then
    peersInfoHolder ! PeerInfoRequestCmd(peer1.id, requestSender.ref)
    requestSender.expectMsg(PeerInfoResponse(Some(peer1Info.withForkAccepted(true))))

  it should "disconnect from a peer with different fork block" taggedAs (UnitTest, NetworkTest) in new TestSetup:
    expectInitialSubscriptions()
    setupNewPeer(peer1, peer1Probe, peer1Info)

    // given
    val blockHeaders: BlockHeaders =
      BlockHeaders(BigInt(0), Seq(Genesis.header.copy(number = Fixtures.Blocks.DaoForkBlock.header.number)))

    // when
    peersInfoHolder ! PeerEventCmd(MessageFromPeer(blockHeaders, peer1.id))

    // then
    peersInfoHolder ! PeerInfoRequestCmd(peer1.id, requestSender.ref)
    requestSender.expectMsg(PeerInfoResponse(Some(peer1Info)))
    peer1Probe.expectMsg(DisconnectPeer(Disconnect.Reasons.UselessPeer))

  it should "remove peers information when a peers is disconnected" taggedAs (UnitTest, NetworkTest) in new TestSetup:
    expectInitialSubscriptions()

    setupNewPeer(peer1, peer1Probe, peer1Info)
    setupNewPeer(peer2, peer2Probe, peer2Info)

    peersInfoHolder ! PeerEventCmd(PeerDisconnected(peer2.id))

    // PeersInfoRequest should work properly
    peersInfoHolder ! PeerInfoRequestCmd(peer1.id, requestSender.ref)
    requestSender.expectMsg(PeerInfoResponse(Some(peer1Info)))
    peersInfoHolder ! PeerInfoRequestCmd(peer2.id, requestSender.ref)
    requestSender.expectMsg(PeerInfoResponse(None))
    peersInfoHolder ! PeerInfoRequestCmd(peer3.id, requestSender.ref)
    requestSender.expectMsg(PeerInfoResponse(None))

    // GetHandshakedPeers should work properly
    peersInfoHolder ! GetHandshakedPeersCmd(requestSender.ref)
    requestSender.expectMsg(HandshakedPeers(Map(peer1 -> peer1Info)))

    peersInfoHolder ! PeerEventCmd(PeerDisconnected(peer1.id))

    // PeersInfoRequest should work properly
    peersInfoHolder ! PeerInfoRequestCmd(peer1.id, requestSender.ref)
    requestSender.expectMsg(PeerInfoResponse(None))
    peersInfoHolder ! PeerInfoRequestCmd(peer2.id, requestSender.ref)
    requestSender.expectMsg(PeerInfoResponse(None))
    peersInfoHolder ! PeerInfoRequestCmd(peer3.id, requestSender.ref)
    requestSender.expectMsg(PeerInfoResponse(None))

    // GetHandshakedPeers should work properly
    peersInfoHolder ! GetHandshakedPeersCmd(requestSender.ref)
    requestSender.expectMsg(HandshakedPeers(Map.empty))

  it should "provide handshaked peers only with best block number determined" taggedAs (
    UnitTest,
    NetworkTest
  ) in new TestSetup:
    expectInitialSubscriptions()
    // Freshly handshaked peer without best block determined
    setupNewPeer(freshPeer, freshPeerProbe, freshPeerInfo.copy(maxBlockNumber = 0))

    // All handshaked peers are now returned immediately (peerHasUpdatedBestBlock = always true,
    // Besu-aligned: ETH/68 peers always have maxBlockNumber=0 at handshake; gating deadlocks them)
    peersInfoHolder ! GetHandshakedPeersCmd(requestSender.ref)
    requestSender.expectMsg(HandshakedPeers(Map(freshPeer -> freshPeerInfo.copy(maxBlockNumber = 0))))

    val newMaxBlock: BigInt = freshPeerInfo.maxBlockNumber + 1
    val firstHeader: BlockHeader = baseBlockHeader.copy(number = BlockNumber(newMaxBlock))

    // Fresh peer received best block
    peersInfoHolder ! PeerEventCmd(MessageFromPeer(BlockHeaders(BigInt(0), Seq(firstHeader)), freshPeer.id))

    // After receiving peer best block number, peer should be provided as handshaked peer
    peersInfoHolder ! GetHandshakedPeersCmd(requestSender.ref)
    requestSender.expectMsg(
      HandshakedPeers(Map(freshPeer -> freshPeerInfo.withBestBlockData(newMaxBlock, firstHeader.hash.value)))
    )

  it should "provide handshaked peers only with best block number determined even if peers best block is its genesis" taggedAs (
    UnitTest,
    NetworkTest
  ) in new TestSetup:
    expectInitialSubscriptions()

    val genesisInfo: PeerInfo = createGenesisPeerInfo()

    // Freshly handshaked peer without best block determined
    setupNewPeer(freshPeer, freshPeerProbe, genesisInfo)

    // if peer best block is its genesis block then it is available right from the start
    peersInfoHolder ! GetHandshakedPeersCmd(requestSender.ref)
    requestSender.expectMsg(HandshakedPeers(Map(freshPeer -> genesisInfo)))

    // Fresh peer received best block
    peersInfoHolder ! PeerEventCmd(
      MessageFromPeer(BlockHeaders(BigInt(0), Seq(Fixtures.Blocks.Genesis.header)), freshPeer.id)
    )

    // receiving best block does not change a thing, as peer best block is it genesis
    peersInfoHolder ! GetHandshakedPeersCmd(requestSender.ref)
    requestSender.expectMsg(HandshakedPeers(Map(freshPeer -> genesisInfo)))

  it should "skip GetBlockHeaders request when peer is at genesis to avoid disconnect" taggedAs (
    UnitTest,
    NetworkTest
  ) in new TestSetup:
    expectInitialSubscriptions()

    // Create a peer at genesis (bestHash == genesisHash)
    val genesisInfo: PeerInfo = createGenesisPeerInfo()

    // Send handshake successful for peer at genesis
    peersInfoHolder ! PeerEventCmd(PeerHandshakeSuccessful(peer1, genesisInfo))

    // Expect subscriptions as usual
    peerEventBus.expectMsgType[SubscribeCmd].to shouldBe PeerDisconnectedClassifier(PeerSelector.WithId(peer1.id))
    peerEventBus.expectMsgType[SubscribeCmd].to shouldBe MessageClassifier(
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

    // Verify NO GetBlockHeaders request is sent to avoid disconnect with reason 0x10 (Other)
    // Many peers disconnect genesis-only nodes as a peer selection policy
    peer1Probe.expectNoMessage()
    peerManager.expectNoMessage(100.millis)

    // Verify peer is still added to handshaked peers
    peersInfoHolder ! PeerInfoRequestCmd(peer1.id, requestSender.ref)
    requestSender.expectMsg(PeerInfoResponse(Some(genesisInfo)))

  it should "send a best-block probe (GetBlockHeaders by bestHash) after handshake on ETH/64-/68" taggedAs (
    UnitTest,
    NetworkTest
  ) in new TestSetup:
    expectInitialSubscriptions()

    // peer1Info is built with capability = ETH63 above; override to ETH68 (modern peer)
    // and pin maxBlockNumber to 0 so we exercise the not-yet-known-number path.
    val eth68Status: RemoteStatus = peer1Info.remoteStatus.copy(capability = Capability.ETH68)
    val eth68Info: PeerInfo = peer1Info.copy(remoteStatus = eth68Status, maxBlockNumber = 0)

    peersInfoHolder ! PeerEventCmd(PeerHandshakeSuccessful(peer1, eth68Info))

    // Drain the two subscriptions that always follow handshake.
    peerEventBus.expectMsgType[SubscribeCmd].to shouldBe PeerDisconnectedClassifier(PeerSelector.WithId(peer1.id))
    peerEventBus.expectMsgType[SubscribeCmd]

    // The probe should land on the peerManager TestProbe as a SendMessage to peer1.
    val sent: PeerManagerActor.SendMessageCmd = peerManager.expectMsgClass(classOf[PeerManagerActor.SendMessageCmd])
    sent.peerId shouldBe peer1.id
    sent.message.code shouldBe Codes.GetBlockHeadersCode
    // ETH/66+ uses request-id-prefixed envelope.
    sent.message.underlyingMsg shouldBe a[com.chipprbots.ethereum.network.p2p.messages.ETHPackets.GetBlockHeaders]
    val gbh: GetBlockHeaders =
      sent.message.underlyingMsg.asInstanceOf[com.chipprbots.ethereum.network.p2p.messages.ETHPackets.GetBlockHeaders]
    gbh.block shouldBe Right(eth68Info.remoteStatus.bestHash)
    gbh.maxHeaders shouldBe BigInt(1)
    gbh.skip shouldBe BigInt(0)
    gbh.reverse shouldBe false

  it should "skip the best-block probe AND send no eager BlockRangeUpdate on ETH/69 (number is already in STATUS)" taggedAs (
    UnitTest,
    NetworkTest
  ) in new TestSetup:
    expectInitialSubscriptions()

    val eth69Status: RemoteStatus = peer1Info.remoteStatus.copy(capability = Capability.ETH69)
    val eth69Info: PeerInfo = peer1Info.copy(remoteStatus = eth69Status)

    peersInfoHolder ! PeerEventCmd(PeerHandshakeSuccessful(peer1, eth69Info))

    // Drain the two subscriptions.
    peerEventBus.expectMsgType[SubscribeCmd].to shouldBe PeerDisconnectedClassifier(PeerSelector.WithId(peer1.id))
    peerEventBus.expectMsgType[SubscribeCmd]
    // ETH/69: no GetBlockHeaders probe (latestBlock is in STATUS), and NO eager BlockRangeUpdate
    // either. Regression pin for the fix that removed the unsolicited post-handshake BRU send:
    // EIP-7642 defines BlockRangeUpdate (0x11) as a change notification, not a handshake greeting,
    // and go-ethereum only emits it from blockRangeLoop on ChainHeadEvent / snap-sync progress
    // (eth/handler.go), broadcast to all connected peers at that moment — never as a one-shot
    // reply to the peer that just handshaked. Sending it eagerly here broke hive's devp2p
    // Transaction/InvalidTxs/LargeTxRequest tests: the harness has no case for eth-relative msg
    // code 17 and panics with "unhandled eth msg code 17". fukuii's own range is already
    // communicated via the 7-field STATUS payload (see EthNodeStatus69ExchangeState), and later
    // range changes go out through BlockBroadcast.broadcastBlock / announceCanonicalHead instead
    // — neither of which fires during plain handshake completion.
    peerManager.expectNoMessage(100.millis)

  it should "discover peer block number from probe response on ETH/64-/68" taggedAs (
    UnitTest,
    NetworkTest
  ) in new TestSetup:
    expectInitialSubscriptions()

    // ETH/68 peer with no known block number yet.
    val eth68Status: RemoteStatus = peer1Info.remoteStatus.copy(capability = Capability.ETH68)
    val eth68Info: PeerInfo = peer1Info.copy(remoteStatus = eth68Status, maxBlockNumber = 0)

    setupNewPeer(peer1, peer1Probe, eth68Info)

    // Probe response arrives via the existing BlockHeadersCode subscription. The
    // header carries the bestHash from STATUS and a real block number; updateMaxBlock
    // should pick up the number and write it into PeerInfo.maxBlockNumber.
    val probeReply: BlockHeader = baseBlockHeader.copy(number = BlockNumber(24463116))
    peersInfoHolder ! PeerEventCmd(MessageFromPeer(BlockHeaders(BigInt(0), Seq(probeReply)), peer1.id))

    peersInfoHolder ! PeerInfoRequestCmd(peer1.id, requestSender.ref)
    val resp: PeerInfoResponse = requestSender.expectMsgType[PeerInfoResponse]
    resp.peerInfo.map(_.maxBlockNumber) shouldBe Some(BigInt(24463116))

  it should "route SNAP protocol messages to registered SNAPSyncController" taggedAs (
    UnitTest,
    NetworkTest
  ) in new TestSetupWithSnapSync:
    expectInitialSubscriptions()

    // Register SNAP sync controller
    peersInfoHolder ! RegisterSnapSyncControllerCmd(snapSyncController.ref.toTyped[SNAPSyncController.Command])

    // Setup a peer
    setupNewPeer(peer1, peer1Probe, peer1Info)

    // Create SNAP protocol messages
    import com.chipprbots.ethereum.network.p2p.messages.SNAP.*

    val accountRange: AccountRange = AccountRange(
      requestId = BigInt(1),
      accounts = Seq.empty,
      proof = Seq.empty
    )

    val storageRanges: StorageRanges = StorageRanges(
      requestId = BigInt(2),
      slots = Seq.empty,
      proof = Seq.empty
    )

    val trieNodes: TrieNodes = TrieNodes(
      requestId = BigInt(3),
      nodes = Seq.empty
    )

    val byteCodes: ByteCodes = ByteCodes(
      requestId = BigInt(4),
      codes = Seq.empty
    )

    // When SNAP messages are received from peer
    peersInfoHolder ! PeerEventCmd(MessageFromPeer(accountRange, peer1.id))
    peersInfoHolder ! PeerEventCmd(MessageFromPeer(storageRanges, peer1.id))
    peersInfoHolder ! PeerEventCmd(MessageFromPeer(trieNodes, peer1.id))
    peersInfoHolder ! PeerEventCmd(MessageFromPeer(byteCodes, peer1.id))

    // Then they should be routed to SNAPSyncController wrapped in Command ADT
    snapSyncController.expectMsg(SNAPSyncController.AccountRangeResponse(accountRange))
    snapSyncController.expectMsg(SNAPSyncController.StorageRangesResponse(storageRanges))
    snapSyncController.expectMsg(SNAPSyncController.TrieNodesResponse(trieNodes))
    snapSyncController.expectMsg(SNAPSyncController.ByteCodesResponse(byteCodes))

  it should "handle SNAP messages gracefully when SNAPSyncController is not registered" taggedAs (
    UnitTest,
    NetworkTest
  ) in new TestSetup:
    expectInitialSubscriptions()

    // Setup a peer without registering SNAP sync controller
    setupNewPeer(peer1, peer1Probe, peer1Info)

    // Create a SNAP protocol message
    import com.chipprbots.ethereum.network.p2p.messages.SNAP.*

    val accountRange: AccountRange = AccountRange(
      requestId = BigInt(1),
      accounts = Seq.empty,
      proof = Seq.empty
    )

    // When SNAP message is received without registered controller
    // It should not crash, just ignore the routing
    peersInfoHolder ! PeerEventCmd(MessageFromPeer(accountRange, peer1.id))

    // Peer info should still be updated normally
    peersInfoHolder ! PeerInfoRequestCmd(peer1.id, requestSender.ref)
    requestSender.expectMsg(PeerInfoResponse(Some(peer1Info)))

  trait TestSetup extends EphemBlockchainTestSetup:
    implicit override lazy val classicSystem: ActorSystem = ActorSystem("PeersInfoHolderSpec_System")

    blockchainWriter.storeBlockHeader(Fixtures.Blocks.Genesis.header).commit()

    override lazy val blockchainConfig = Config.blockchains.blockchainConfig
    val forkResolver = new ForkResolver.IrregularStateChangeDaoForkResolver(blockchainConfig.daoForkConfig.get)

    val peerStatus: RemoteStatus = RemoteStatus(
      capability = Capability.ETH63,
      networkId = 1,
      chainWeight = ChainWeight.totalDifficultyOnly(10000),
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

    // Helper to create a PeerInfo for a peer at genesis
    // Sets both bestHash and genesisHash to ensure isAtGenesis() returns true.
    // In production, genesisHash should already be set correctly from handshake,
    // but we explicitly set both here for test clarity and to avoid test brittleness.
    def createGenesisPeerInfo(basePeerInfo: PeerInfo = initialPeerInfo): PeerInfo =
      val genesisHash = Fixtures.Blocks.Genesis.header.hash.value
      val genesisStatus: RemoteStatus = basePeerInfo.remoteStatus.copy(
        bestHash = genesisHash,
        genesisHash = genesisHash // Explicitly set to match bestHash for isAtGenesis() == true
      )
      basePeerInfo.copy(
        remoteStatus = genesisStatus,
        maxBlockNumber = Fixtures.Blocks.Genesis.header.number.value,
        bestBlockHash = genesisHash
      )

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

    val peersInfoHolder = classicSystem
      .spawn(
        NetworkPeerManagerActor.behavior(
          peerManager.ref.toTyped[PeerManagerActor.Command],
          peerEventBus.ref.toTyped[PeerEventBusActor.Command],
          storagesInstance.storages.appStateStorage,
          Some(forkResolver),
          isPoWChain = true
        ),
        s"npma-spec-${java.util.UUID.randomUUID()}"
      )
      .toClassic

    val requestSender: TestProbe = TestProbe()

    val baseBlockHeader = Fixtures.Blocks.Block3125369.header
    val baseBlockBody: BlockBody = BlockBody(Nil, Nil)
    val baseBlock: Block = Block(baseBlockHeader, baseBlockBody)

    // NetworkPeerManagerActor subscribes at construction to (1) PeerHandshaked, and
    // (2) a global MessageClassifier for SNAP request codes so that hive test peers
    // — which fire GetAccountRange immediately after Hello, before PeerHandshakeSuccessful
    // — still reach the handler. Tests that expect the initial subscriptions must
    // consume both.
    def expectInitialSubscriptions(): Unit =
      peerEventBus.expectMsgType[SubscribeCmd].to shouldBe PeerHandshaked
      peerEventBus.expectMsgType[SubscribeCmd].to shouldBe MessageClassifier(
        Set(
          com.chipprbots.ethereum.network.p2p.messages.SNAP.Codes.GetAccountRangeCode,
          com.chipprbots.ethereum.network.p2p.messages.SNAP.Codes.GetStorageRangesCode,
          com.chipprbots.ethereum.network.p2p.messages.SNAP.Codes.GetTrieNodesCode,
          com.chipprbots.ethereum.network.p2p.messages.SNAP.Codes.GetByteCodesCode
        ),
        PeerSelector.AllPeers
      )

    def setupNewPeer(peer: Peer, peerProbe: TestProbe, peerInfo: PeerInfo): Unit =

      peersInfoHolder ! PeerEventCmd(PeerHandshakeSuccessful(peer, peerInfo))

      peerEventBus.expectMsgType[SubscribeCmd].to shouldBe PeerDisconnectedClassifier(PeerSelector.WithId(peer.id))

      peerEventBus.expectMsgType[SubscribeCmd].to shouldBe MessageClassifier(
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

      // After handshake completes, NetworkPeerManagerActor issues a Besu-style
      // best-block probe (GetBlockHeaders by bestHash, count=1) on ETH/64-/68 so the
      // peer's block number can be discovered — STATUS doesn't carry it on those
      // protocol versions. The probe is routed via `peerManagerActor ! SendMessage`,
      // so it lands on the `peerManager` TestProbe, never on the per-peer `peerProbe`.
      // Genesis peers and ETH/69 peers are skipped (see dedicated tests below).
      peerProbe.expectNoMessage(100.millis)
      val nonGenesis = peerInfo.remoteStatus.bestHash != peerInfo.remoteStatus.genesisHash
      val notEth69 = peerInfo.remoteStatus.capability != Capability.ETH69
      if nonGenesis && notEth69 then
        val probe = peerManager.expectMsgClass(classOf[PeerManagerActor.SendMessageCmd])
        probe.peerId shouldBe peer.id
        probe.message.code shouldBe Codes.GetBlockHeadersCode

  trait TestSetupWithSnapSync extends TestSetup:
    val snapSyncController: TestProbe = TestProbe()

  // Data helpers for archive-node detection tests.
  // Does NOT override peersInfoHolder — call newReaderHolder() in each test after
  // consuming the main actor's initial subscriptions to avoid double-actor confusion.
  trait TestSetupWithReader extends TestSetup:
    // A block header with a distinct hash (modified extraData) at the same block number.
    // Its hash → actualTD is stored in the test DB so DB_LOOKUP returns a low TD.
    val archiveProbeBlock: BlockHeader = baseBlockHeader.copy(
      extraData = org.apache.pekko.util.ByteString(0xde.toByte)
    )
    val actualTD: BigInt = BigInt(500)
    val inflatedTD: BigInt = BigInt(9999)
    val eth69Status: RemoteStatus = peerStatus.copy(capability = Capability.ETH69)
    val eth69PeerInfo: PeerInfo = initialPeerInfo.copy(
      remoteStatus = eth69Status,
      chainWeight = ChainWeight.totalDifficultyOnly(inflatedTD)
    )

    // Store the low TD for archiveProbeBlock.hash in the test DB.
    def storeArchiveWeight(): Unit =
      blockchainWriter
        .storeChainWeight(archiveProbeBlock.hash, ChainWeight.totalDifficultyOnly(actualTD))
        .commit()

    // Create a fresh actor wired with the real BlockchainReader.
    // Call this AFTER expectInitialSubscriptions() to avoid interleaving subscriptions.
    def newReaderHolder(): org.apache.pekko.actor.ActorRef = classicSystem
      .spawn(
        NetworkPeerManagerActor.behavior(
          peerManager.ref.toTyped[PeerManagerActor.Command],
          peerEventBus.ref.toTyped[PeerEventBusActor.Command],
          storagesInstance.storages.appStateStorage,
          Some(forkResolver),
          blockchainReader = Some(blockchainReader),
          isPoWChain = true
        ),
        s"npma-reader-${java.util.UUID.randomUUID()}"
      )
      .toClassic

    // Handshake a peer onto a specific holder actor, consuming the expected subscribe
    // messages and any post-handshake SendMessageCmd from peerManager.
    def setupPeerOnHolder(
        holder: org.apache.pekko.actor.ActorRef,
        peer: Peer,
        peerProbe: TestProbe,
        peerInfo: PeerInfo
    ): Unit =
      holder ! PeerEventCmd(PeerHandshakeSuccessful(peer, peerInfo))
      peerEventBus.expectMsgType[SubscribeCmd].to shouldBe PeerDisconnectedClassifier(
        PeerSelector.WithId(peer.id)
      )
      peerEventBus.expectMsgType[SubscribeCmd].to shouldBe MessageClassifier(
        Set(
          Codes.BlockHeadersCode,
          Codes.NewBlockCode,
          Codes.NewBlockHashesCode,
          Codes.BlockRangeUpdateCode,
          com.chipprbots.ethereum.network.p2p.messages.SNAP.Codes.AccountRangeCode,
          com.chipprbots.ethereum.network.p2p.messages.SNAP.Codes.StorageRangesCode,
          com.chipprbots.ethereum.network.p2p.messages.SNAP.Codes.TrieNodesCode,
          com.chipprbots.ethereum.network.p2p.messages.SNAP.Codes.ByteCodesCode,
          com.chipprbots.ethereum.network.p2p.messages.SNAP.Codes.GetAccountRangeCode,
          com.chipprbots.ethereum.network.p2p.messages.SNAP.Codes.GetStorageRangesCode,
          com.chipprbots.ethereum.network.p2p.messages.SNAP.Codes.GetTrieNodesCode,
          com.chipprbots.ethereum.network.p2p.messages.SNAP.Codes.GetByteCodesCode
        ),
        PeerSelector.WithId(peer.id)
      )
      peerProbe.expectNoMessage(100.millis)
      // ETH/69 peers get no post-handshake message at all: no GetBlockHeaders probe (number is
      // already in STATUS) and no eager BlockRangeUpdate (BRU is a change notification — see
      // BlockBroadcast.broadcastBlock / announceCanonicalHead — not a handshake greeting).
      // Nothing to drain here; callers proceed straight to their own peerManager expectations
      // (e.g. RefreshPeerBestBlocksTick probes).

  it should "ETH69 archive peer: correct inflated Tier3 chainWeight after 3 consecutive unchanged probes" taggedAs (
    UnitTest,
    NetworkTest
  ) in new TestSetupWithReader:
    // Drain peersInfoHolder (no blockchainReader) initial subscriptions.
    expectInitialSubscriptions()
    // Create the actor WITH blockchainReader and drain its initial subscriptions.
    val readerHolder = newReaderHolder()
    expectInitialSubscriptions()

    // Seed the DB with the low actualTD before any tick so DB_LOOKUP can resolve it.
    storeArchiveWeight()

    // Handshake an ETH69 peer with inflated Tier3 estimate onto the reader-backed actor.
    setupPeerOnHolder(readerHolder, peer1, peer1Probe, eth69PeerInfo)

    // No BlockHeaders response is sent during accumulation: sending a response would set
    // lastBlockSignalMs = now, causing the immediately-following tick to see recentlySignaled
    // = true and skip counter tracking (BlockSignalStaleAfter = 150s production value).
    // Consuming the probe from peerManager is sufficient — the counter is tracked at TICK time,
    // not at response time.
    def tick(): Unit =
      readerHolder ! RefreshPeerBestBlocksTick
      peerManager.expectMsgClass(classOf[PeerManagerActor.SendMessageCmd])

    // Tick 1: seeds lastProbeMaxBlock; counter stays 0 (first tick → case None → no increment).
    tick()
    // Tick 2: counter = 1.
    tick()
    // Tick 3: counter = 2.
    tick()
    // Tick 4: counter = 3 = StaticPeerProbeThreshold → isPeerStatic = true on next response.
    tick()

    // No response received yet — correction has not fired; chainWeight is still inflated.
    readerHolder ! PeerInfoRequestCmd(peer1.id, requestSender.ref)
    requestSender.expectMsgType[PeerInfoResponse].peerInfo.get.chainWeight.totalDifficulty.value shouldBe inflatedTD

    // Trigger correction: send probe response. isPeerStatic = true → DB_LOOKUP → actualTD.
    val probeResponse = BlockHeaders(BigInt(0), Seq(archiveProbeBlock))
    readerHolder ! PeerEventCmd(MessageFromPeer(probeResponse, peer1.id))

    readerHolder ! PeerInfoRequestCmd(peer1.id, requestSender.ref)
    requestSender.expectMsgType[PeerInfoResponse].peerInfo.get.chainWeight.totalDifficulty.value shouldBe actualTD

  it should "ETH69 mining peer: active block signal suppresses tick probes — monotonic guard stays active" taggedAs (
    UnitTest,
    NetworkTest
  ) in new TestSetupWithReader:
    // Drain peersInfoHolder initial subscriptions, then create and drain the reader actor.
    expectInitialSubscriptions()
    val readerHolder = newReaderHolder()
    expectInitialSubscriptions()

    storeArchiveWeight()
    setupPeerOnHolder(readerHolder, peer1, peer1Probe, eth69PeerInfo)

    // Tick 1: no recent signal → probe fires; seeds lastProbeMaxBlock, counter = 0 (case None).
    readerHolder ! RefreshPeerBestBlocksTick
    peerManager.expectMsgClass(classOf[PeerManagerActor.SendMessageCmd])

    // Mining peer sends a live block signal via BlockRangeUpdate — refreshes lastBlockSignalMs.
    // This simulates an active mining node that keeps its signal fresh across tick intervals.
    // The advancing block number is not in the DB so resolveETH69ChainWeight returns COLD_START
    // (shouldUpdate = false), leaving chainWeight at inflatedTD.
    val advancingBlock = archiveProbeBlock.copy(number = archiveProbeBlock.number + 1)
    readerHolder ! PeerEventCmd(
      MessageFromPeer(
        BlockRangeUpdate(
          earliestBlock = BigInt(0),
          latestBlock = advancingBlock.number.value,
          latestBlockHash = advancingBlock.hash.value
        ),
        peer1.id
      )
    )

    // Ticks 2 and 3: recentlySignaled = true (BlockRangeUpdate set lastBlockSignalMs < 150s ago)
    // → probes are suppressed → counter is never incremented past 0.
    readerHolder ! RefreshPeerBestBlocksTick
    peerManager.expectNoMessage(100.millis)
    readerHolder ! RefreshPeerBestBlocksTick
    peerManager.expectNoMessage(100.millis)

    // counter = 0 < StaticPeerProbeThreshold (3); monotonic guard remains active — no correction.
    readerHolder ! PeerInfoRequestCmd(peer1.id, requestSender.ref)
    requestSender.expectMsgType[PeerInfoResponse].peerInfo.get.chainWeight.totalDifficulty.value shouldBe inflatedTD
