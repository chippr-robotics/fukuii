package com.chipprbots.ethereum.network

import java.net.InetSocketAddress

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.Props
import org.apache.pekko.testkit.TestActorRef
import org.apache.pekko.testkit.TestProbe
import org.apache.pekko.util.ByteString

import scala.concurrent.duration._

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.network.NetworkPeerManagerActor._
import com.chipprbots.ethereum.network.PeerActor.SendMessage
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerEvent.MessageFromPeer
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerEvent.PeerHandshakeSuccessful
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerSelector
import com.chipprbots.ethereum.network.PeerEventBusActor.Subscribe
import com.chipprbots.ethereum.network.PeerEventBusActor.SubscriptionClassifier._
import com.chipprbots.ethereum.network.p2p.MessageSerializableImplicit
import com.chipprbots.ethereum.network.p2p.messages.Capability
import com.chipprbots.ethereum.network.p2p.messages.Codes
import com.chipprbots.ethereum.network.p2p.messages.SNAP._
import com.chipprbots.ethereum.testing.Tags._
import com.chipprbots.ethereum.utils.Config

/** Tests for the SNAP server-side handlers in NetworkPeerManagerActor:
  * handleGetByteCodes, handleGetAccountRange, handleGetStorageRanges, handleGetTrieNodes.
  */
class SNAPServerHandlerSpec extends AnyFlatSpec with Matchers {

  // ===== GetByteCodes =====

  "handleGetByteCodes" should "return bytecodes for known hashes" taggedAs (UnitTest, NetworkTest) in new TestSetup {
    val code1 = ByteString(Array.fill(100)(0x60.toByte))
    val code2 = ByteString(Array.fill(200)(0x61.toByte))
    val hash1 = kec256(code1)
    val hash2 = kec256(code2)

    evmCodeStorage.put(hash1, code1).commit()
    evmCodeStorage.put(hash2, code2).commit()

    peersInfoHolder ! MessageFromPeer(
      GetByteCodes(requestId = BigInt(1), hashes = Seq(hash1, hash2), responseBytes = BigInt(2 * 1024 * 1024)),
      peer1.id
    )

    val response = peer1Probe.expectMsgType[SendMessage](5.seconds)
    val byteCodes = response.message.asInstanceOf[MessageSerializableImplicit[ByteCodes]].msg
    byteCodes.requestId shouldBe BigInt(1)
    byteCodes.codes should have size 2
    byteCodes.codes(0) shouldBe code1
    byteCodes.codes(1) shouldBe code2
  }

  it should "skip unknown code hashes" taggedAs (UnitTest, NetworkTest) in new TestSetup {
    val code1 = ByteString(Array.fill(100)(0x60.toByte))
    val hash1 = kec256(code1)
    val unknownHash = ByteString(Array.fill(32)(0xFF.toByte))

    evmCodeStorage.put(hash1, code1).commit()

    peersInfoHolder ! MessageFromPeer(
      GetByteCodes(requestId = BigInt(2), hashes = Seq(unknownHash, hash1), responseBytes = BigInt(2 * 1024 * 1024)),
      peer1.id
    )

    val response = peer1Probe.expectMsgType[SendMessage](5.seconds)
    val byteCodes = response.message.asInstanceOf[MessageSerializableImplicit[ByteCodes]].msg
    byteCodes.codes should have size 1
    byteCodes.codes.head shouldBe code1
  }

  it should "respect byte limit" taggedAs (UnitTest, NetworkTest) in new TestSetup {
    // Store 3 large codes, set byte limit smaller than total
    val code1 = ByteString(Array.fill(500)(0x60.toByte))
    val code2 = ByteString(Array.fill(500)(0x61.toByte))
    val code3 = ByteString(Array.fill(500)(0x62.toByte))
    val hash1 = kec256(code1)
    val hash2 = kec256(code2)
    val hash3 = kec256(code3)

    evmCodeStorage.put(hash1, code1).commit()
    evmCodeStorage.put(hash2, code2).commit()
    evmCodeStorage.put(hash3, code3).commit()

    // Limit to 800 bytes — should only fit first code (500), then second (500 > remaining 300)
    // But handler allows one overshoot, so it returns 2 codes (1000 bytes)
    peersInfoHolder ! MessageFromPeer(
      GetByteCodes(requestId = BigInt(3), hashes = Seq(hash1, hash2, hash3), responseBytes = BigInt(800)),
      peer1.id
    )

    val response = peer1Probe.expectMsgType[SendMessage](5.seconds)
    val byteCodes = response.message.asInstanceOf[MessageSerializableImplicit[ByteCodes]].msg
    // Handler uses strict < comparison: stops when totalBytes >= limit
    // First code: 500 < 800, add it (total=500). Second: 500 < 800-500=300? No, check is totalBytes(500) < 800, yes. total=1000.
    // Third: totalBytes(1000) < 800? No. Stop.
    byteCodes.codes should have size 2
  }

  it should "return empty when no EvmCodeStorage available" taggedAs (UnitTest, NetworkTest) in new TestSetupNoStorage {
    peersInfoHolder ! MessageFromPeer(
      GetByteCodes(requestId = BigInt(4), hashes = Seq(ByteString(Array.fill(32)(0xAA.toByte))), responseBytes = BigInt(2 * 1024 * 1024)),
      peer1.id
    )

    val response = peer1Probe.expectMsgType[SendMessage](5.seconds)
    val byteCodes = response.message.asInstanceOf[MessageSerializableImplicit[ByteCodes]].msg
    byteCodes.codes shouldBe empty
  }

  // ===== GetAccountRange =====

  "handleGetAccountRange" should "return empty response when FlatAccountStorage uses non-RocksDB backend" taggedAs (UnitTest, NetworkTest) in new TestSetup {
    // EphemDataSource is not RocksDB, so seekFrom returns Stream.empty
    peersInfoHolder ! MessageFromPeer(
      GetAccountRange(
        requestId = BigInt(10),
        rootHash = ByteString(Array.fill(32)(0x11.toByte)),
        startingHash = ByteString(Array.fill(32)(0x00.toByte)),
        limitHash = ByteString(Array.fill(32)(0xFF.toByte)),
        responseBytes = BigInt(2 * 1024 * 1024)
      ),
      peer1.id
    )

    val response = peer1Probe.expectMsgType[SendMessage](5.seconds)
    val accountRange = response.message.asInstanceOf[MessageSerializableImplicit[AccountRange]].msg
    accountRange.requestId shouldBe BigInt(10)
    accountRange.accounts shouldBe empty
    accountRange.proof shouldBe empty
  }

  it should "return empty when no FlatAccountStorage available" taggedAs (UnitTest, NetworkTest) in new TestSetupNoStorage {
    peersInfoHolder ! MessageFromPeer(
      GetAccountRange(
        requestId = BigInt(11),
        rootHash = ByteString(Array.fill(32)(0x11.toByte)),
        startingHash = ByteString(Array.fill(32)(0x00.toByte)),
        limitHash = ByteString(Array.fill(32)(0xFF.toByte)),
        responseBytes = BigInt(2 * 1024 * 1024)
      ),
      peer1.id
    )

    val response = peer1Probe.expectMsgType[SendMessage](5.seconds)
    val accountRange = response.message.asInstanceOf[MessageSerializableImplicit[AccountRange]].msg
    accountRange.accounts shouldBe empty
  }

  // ===== GetStorageRanges =====

  "handleGetStorageRanges" should "return empty slots when FlatSlotStorage uses non-RocksDB backend" taggedAs (UnitTest, NetworkTest) in new TestSetup {
    val accountHash = ByteString(Array.fill(32)(0xAA.toByte))

    peersInfoHolder ! MessageFromPeer(
      GetStorageRanges(
        requestId = BigInt(20),
        rootHash = ByteString(Array.fill(32)(0x11.toByte)),
        accountHashes = Seq(accountHash),
        startingHash = ByteString(Array.fill(32)(0x00.toByte)),
        limitHash = ByteString(Array.fill(32)(0xFF.toByte)),
        responseBytes = BigInt(2 * 1024 * 1024)
      ),
      peer1.id
    )

    val response = peer1Probe.expectMsgType[SendMessage](5.seconds)
    val storageRanges = response.message.asInstanceOf[MessageSerializableImplicit[StorageRanges]].msg
    storageRanges.requestId shouldBe BigInt(20)
    // With non-RocksDB, seekStorageRange returns empty, so each account gets empty Seq
    storageRanges.slots should have size 1
    storageRanges.slots.head shouldBe empty
  }

  it should "return empty when no FlatSlotStorage available" taggedAs (UnitTest, NetworkTest) in new TestSetupNoStorage {
    peersInfoHolder ! MessageFromPeer(
      GetStorageRanges(
        requestId = BigInt(21),
        rootHash = ByteString(Array.fill(32)(0x11.toByte)),
        accountHashes = Seq(ByteString(Array.fill(32)(0xAA.toByte))),
        startingHash = ByteString(Array.fill(32)(0x00.toByte)),
        limitHash = ByteString(Array.fill(32)(0xFF.toByte)),
        responseBytes = BigInt(2 * 1024 * 1024)
      ),
      peer1.id
    )

    val response = peer1Probe.expectMsgType[SendMessage](5.seconds)
    val storageRanges = response.message.asInstanceOf[MessageSerializableImplicit[StorageRanges]].msg
    storageRanges.slots shouldBe empty
  }

  // ===== GetTrieNodes =====

  "handleGetTrieNodes" should "return empty when no NodeStorage available" taggedAs (UnitTest, NetworkTest) in new TestSetupNoStorage {
    peersInfoHolder ! MessageFromPeer(
      GetTrieNodes(
        requestId = BigInt(30),
        rootHash = ByteString(Array.fill(32)(0x11.toByte)),
        paths = Seq(Seq(ByteString(Array[Byte](0x20)))),
        responseBytes = BigInt(2 * 1024 * 1024)
      ),
      peer1.id
    )

    val response = peer1Probe.expectMsgType[SendMessage](5.seconds)
    val trieNodes = response.message.asInstanceOf[MessageSerializableImplicit[TrieNodes]].msg
    trieNodes.requestId shouldBe BigInt(30)
    trieNodes.nodes shouldBe empty
  }

  it should "return empty for non-existent paths" taggedAs (UnitTest, NetworkTest) in new TestSetup {
    peersInfoHolder ! MessageFromPeer(
      GetTrieNodes(
        requestId = BigInt(31),
        rootHash = ByteString(Array.fill(32)(0x11.toByte)),
        paths = Seq(Seq(ByteString(Array[Byte](0x20, 0x01, 0x02)))),
        responseBytes = BigInt(2 * 1024 * 1024)
      ),
      peer1.id
    )

    val response = peer1Probe.expectMsgType[SendMessage](5.seconds)
    val trieNodes = response.message.asInstanceOf[MessageSerializableImplicit[TrieNodes]].msg
    trieNodes.nodes shouldBe empty
  }

  it should "skip empty path groups" taggedAs (UnitTest, NetworkTest) in new TestSetup {
    peersInfoHolder ! MessageFromPeer(
      GetTrieNodes(
        requestId = BigInt(32),
        rootHash = ByteString(Array.fill(32)(0x11.toByte)),
        paths = Seq(Seq.empty, Seq.empty),
        responseBytes = BigInt(2 * 1024 * 1024)
      ),
      peer1.id
    )

    val response = peer1Probe.expectMsgType[SendMessage](5.seconds)
    val trieNodes = response.message.asInstanceOf[MessageSerializableImplicit[TrieNodes]].msg
    trieNodes.nodes shouldBe empty
  }

  // ===== Test Setups =====

  /** Full test setup with all storage backends (using EphemDataSource). */
  trait TestSetup extends EphemBlockchainTestSetup {
    implicit override lazy val system: ActorSystem = ActorSystem("SNAPServerHandlerSpec_System")

    override lazy val blockchainConfig = Config.blockchains.blockchainConfig
    val forkResolver = new ForkResolver.EtcForkResolver(blockchainConfig.daoForkConfig.get)

    blockchainWriter.storeBlockHeader(Fixtures.Blocks.Genesis.header).commit()

    val evmCodeStorage = storagesInstance.storages.evmCodeStorage
    val flatAccountStorage = storagesInstance.storages.flatAccountStorage
    val flatSlotStorage = storagesInstance.storages.flatSlotStorage
    val testNodeStorage = storagesInstance.storages.nodeStorage

    val peerManager: TestProbe = TestProbe()
    val peerEventBus: TestProbe = TestProbe()

    val peersInfoHolder: TestActorRef[Nothing] = TestActorRef(
      Props(
        new NetworkPeerManagerActor(
          peerManager.ref,
          peerEventBus.ref,
          storagesInstance.storages.appStateStorage,
          Some(forkResolver),
          evmCodeStorage = Some(evmCodeStorage),
          nodeStorage = Some(testNodeStorage),
          flatAccountStorage = Some(flatAccountStorage),
          flatSlotStorage = Some(flatSlotStorage)
        )
      )
    )

    val fakeNodeId: ByteString = ByteString()

    val peerStatus: RemoteStatus = RemoteStatus(
      capability = Capability.ETH63,
      networkId = 1,
      chainWeight = com.chipprbots.ethereum.domain.ChainWeight.totalDifficultyOnly(10000),
      bestHash = Fixtures.Blocks.Block3125369.header.hash,
      genesisHash = Fixtures.Blocks.Genesis.header.hash
    )

    val peer1Info: PeerInfo = PeerInfo(
      remoteStatus = peerStatus,
      chainWeight = peerStatus.chainWeight,
      forkAccepted = false,
      maxBlockNumber = Fixtures.Blocks.Block3125369.header.number,
      bestBlockHash = peerStatus.bestHash
    )

    val peer1Probe: TestProbe = TestProbe()
    val peer1: Peer = Peer(PeerId("peer1"), new InetSocketAddress("127.0.0.1", 1), peer1Probe.ref, false, nodeId = Some(fakeNodeId))

    // Register peer
    peerEventBus.expectMsg(Subscribe(PeerHandshaked))
    setupPeer(peer1, peer1Probe, peer1Info)

    def setupPeer(peer: Peer, peerProbe: TestProbe, peerInfo: PeerInfo): Unit = {
      peersInfoHolder ! PeerHandshakeSuccessful(peer, peerInfo)
      peerEventBus.expectMsg(Subscribe(PeerDisconnectedClassifier(PeerSelector.WithId(peer.id))))
      peerEventBus.expectMsg(
        Subscribe(
          MessageClassifier(
            Set(
              Codes.BlockHeadersCode,
              Codes.NewBlockCode,
              Codes.NewBlockHashesCode,
              com.chipprbots.ethereum.network.p2p.messages.SNAP.Codes.AccountRangeCode,
              com.chipprbots.ethereum.network.p2p.messages.SNAP.Codes.StorageRangesCode,
              com.chipprbots.ethereum.network.p2p.messages.SNAP.Codes.TrieNodesCode,
              com.chipprbots.ethereum.network.p2p.messages.SNAP.Codes.ByteCodesCode
            ),
            PeerSelector.WithId(peer.id)
          )
        )
      )
      peerProbe.expectMsg(
        PeerActor.SendMessage(
          com.chipprbots.ethereum.network.p2p.messages.ETH62.GetBlockHeaders(Right(peerInfo.remoteStatus.bestHash), 1, 0, false)
        )
      )
    }
  }

  /** Test setup without storage backends to verify empty/graceful responses. */
  trait TestSetupNoStorage extends EphemBlockchainTestSetup {
    implicit override lazy val system: ActorSystem = ActorSystem("SNAPServerHandlerSpec_NoStorage")

    override lazy val blockchainConfig = Config.blockchains.blockchainConfig
    val forkResolver = new ForkResolver.EtcForkResolver(blockchainConfig.daoForkConfig.get)

    blockchainWriter.storeBlockHeader(Fixtures.Blocks.Genesis.header).commit()

    val peerManager: TestProbe = TestProbe()
    val peerEventBus: TestProbe = TestProbe()

    val peersInfoHolder: TestActorRef[Nothing] = TestActorRef(
      Props(
        new NetworkPeerManagerActor(
          peerManager.ref,
          peerEventBus.ref,
          storagesInstance.storages.appStateStorage,
          Some(forkResolver),
          evmCodeStorage = None,
          nodeStorage = None,
          flatAccountStorage = None,
          flatSlotStorage = None
        )
      )
    )

    val fakeNodeId: ByteString = ByteString()

    val peerStatus: RemoteStatus = RemoteStatus(
      capability = Capability.ETH63,
      networkId = 1,
      chainWeight = com.chipprbots.ethereum.domain.ChainWeight.totalDifficultyOnly(10000),
      bestHash = Fixtures.Blocks.Block3125369.header.hash,
      genesisHash = Fixtures.Blocks.Genesis.header.hash
    )

    val peer1Info: PeerInfo = PeerInfo(
      remoteStatus = peerStatus,
      chainWeight = peerStatus.chainWeight,
      forkAccepted = false,
      maxBlockNumber = Fixtures.Blocks.Block3125369.header.number,
      bestBlockHash = peerStatus.bestHash
    )

    val peer1Probe: TestProbe = TestProbe()
    val peer1: Peer = Peer(PeerId("peer1"), new InetSocketAddress("127.0.0.1", 1), peer1Probe.ref, false, nodeId = Some(fakeNodeId))

    // Register peer
    peerEventBus.expectMsg(Subscribe(PeerHandshaked))
    peersInfoHolder ! PeerHandshakeSuccessful(peer1, peer1Info)
    peerEventBus.expectMsg(Subscribe(PeerDisconnectedClassifier(PeerSelector.WithId(peer1.id))))
    peerEventBus.expectMsg(
      Subscribe(
        MessageClassifier(
          Set(
            Codes.BlockHeadersCode,
            Codes.NewBlockCode,
            Codes.NewBlockHashesCode,
            com.chipprbots.ethereum.network.p2p.messages.SNAP.Codes.AccountRangeCode,
            com.chipprbots.ethereum.network.p2p.messages.SNAP.Codes.StorageRangesCode,
            com.chipprbots.ethereum.network.p2p.messages.SNAP.Codes.TrieNodesCode,
            com.chipprbots.ethereum.network.p2p.messages.SNAP.Codes.ByteCodesCode
          ),
          PeerSelector.WithId(peer1.id)
        )
      )
    )
    peer1Probe.expectMsg(
      PeerActor.SendMessage(
        com.chipprbots.ethereum.network.p2p.messages.ETH62.GetBlockHeaders(Right(peer1Info.remoteStatus.bestHash), 1, 0, false)
      )
    )
  }
}
