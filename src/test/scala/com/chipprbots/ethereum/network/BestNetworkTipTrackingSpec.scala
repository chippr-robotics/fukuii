package com.chipprbots.ethereum.network

import java.net.InetSocketAddress

import scala.concurrent.duration._

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.Props
import org.apache.pekko.testkit.TestActorRef
import org.apache.pekko.testkit.TestProbe
import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.blockchain.sync.SyncProtocol
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.BlockBody
import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.network.NetworkPeerManagerActor._
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerEvent.MessageFromPeer
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerEvent.PeerDisconnected
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerEvent.PeerHandshakeSuccessful
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerSelector
import com.chipprbots.ethereum.network.PeerEventBusActor.Subscribe
import com.chipprbots.ethereum.network.PeerEventBusActor.SubscriptionClassifier._
import com.chipprbots.ethereum.network.p2p.messages.Capability
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.NewBlock
import com.chipprbots.ethereum.network.p2p.messages.Codes
import com.chipprbots.ethereum.testing.Tags._
import com.chipprbots.ethereum.utils.Config

// scalastyle:off magic.number
class BestNetworkTipTrackingSpec extends AnyFlatSpec with Matchers {

  // ─── T1.1 ─────────────────────────────────────────────────────────────────
  "NPA bestNetworkTip" should "select the higher TD when two ETH68 peers connect" taggedAs (
    UnitTest,
    NetworkTest
  ) in new TestSetup {
    expectInitialSubscriptions()

    // peer1 with td=100, peer2 with td=200 (no block number yet — ETH68 STATUS)
    setupNewPeer(peer1, mkInfo(Capability.ETH68, td = BigInt(100), blockNum = 0))
    setupNewPeer(peer2, mkInfo(Capability.ETH68, td = BigInt(200), blockNum = 0))

    peersInfoHolder ! RegisterChainWeightCalibrationTarget(calibrationTarget.ref)
    peersInfoHolder ! CalibrateChainWeightNow

    calibrationTarget.expectMsg(SyncProtocol.CalibrateChainWeightFromPeer(BigInt(200), BigInt(0)))
  }

  // ─── T1.2 ─────────────────────────────────────────────────────────────────
  it should "prefer NewBlock (exact TD + blockNum) over STATUS-only TD" taggedAs (
    UnitTest,
    NetworkTest
  ) in new TestSetup {
    expectInitialSubscriptions()
    setupNewPeer(peer1, mkInfo(Capability.ETH68, td = BigInt(150), blockNum = 0))

    // NewBlock arrives with higher TD and exact block number
    val newBlockHeader = Fixtures.Blocks.Genesis.header.copy(number = BigInt(1000))
    val nb = NewBlock(Block(newBlockHeader, BlockBody(Nil, Nil)), BigInt(300))
    peersInfoHolder ! MessageFromPeer(nb, peer1.id)

    peersInfoHolder ! RegisterChainWeightCalibrationTarget(calibrationTarget.ref)
    peersInfoHolder ! CalibrateChainWeightNow

    calibrationTarget.expectMsg(SyncProtocol.CalibrateChainWeightFromPeer(BigInt(300), BigInt(1000)))
  }

  // ─── T1.3 ─────────────────────────────────────────────────────────────────
  it should "retain bestNetworkTip after peer disconnect" taggedAs (UnitTest, NetworkTest) in new TestSetup {
    expectInitialSubscriptions()
    setupNewPeer(peer1, mkInfo(Capability.ETH68, td = BigInt(500), blockNum = 0))

    // Simulate disconnect
    peersInfoHolder ! PeerDisconnected(peer1.id)

    peersInfoHolder ! RegisterChainWeightCalibrationTarget(calibrationTarget.ref)
    peersInfoHolder ! CalibrateChainWeightNow

    // TD must still be forwarded despite disconnect — bestNetworkTip is persistent
    calibrationTarget.expectMsg(SyncProtocol.CalibrateChainWeightFromPeer(BigInt(500), BigInt(0)))
  }

  // ─── T1.4 ─────────────────────────────────────────────────────────────────
  it should "forward sentinel (0, 0) when no peers ever connected" taggedAs (UnitTest, NetworkTest) in new TestSetup {
    expectInitialSubscriptions()

    peersInfoHolder ! RegisterChainWeightCalibrationTarget(calibrationTarget.ref)
    peersInfoHolder ! CalibrateChainWeightNow

    calibrationTarget.expectMsg(SyncProtocol.CalibrateChainWeightFromPeer(BigInt(0), BigInt(0)))
  }

  // ─── T1.5 ─────────────────────────────────────────────────────────────────
  it should "forward sentinel (0, 0) for pure ETH69 network (no TD in STATUS)" taggedAs (
    UnitTest,
    NetworkTest
  ) in new TestSetup {
    expectInitialSubscriptions()

    // ETH69 peers don't carry TD in STATUS — NPA must not update bestNetworkTip for them
    setupNewPeer(peer1, mkInfo(Capability.ETH69, td = BigInt(0), blockNum = BigInt(24720000)))
    setupNewPeer(peer2, mkInfo(Capability.ETH69, td = BigInt(0), blockNum = BigInt(24720000)))

    peersInfoHolder ! RegisterChainWeightCalibrationTarget(calibrationTarget.ref)
    peersInfoHolder ! CalibrateChainWeightNow

    calibrationTarget.expectMsg(SyncProtocol.CalibrateChainWeightFromPeer(BigInt(0), BigInt(0)))
  }

  // ─── T1.6 ─────────────────────────────────────────────────────────────────
  it should "not downgrade bestNetworkTip when a lower-TD NewBlock arrives" taggedAs (
    UnitTest,
    NetworkTest
  ) in new TestSetup {
    expectInitialSubscriptions()
    setupNewPeer(peer1, mkInfo(Capability.ETH68, td = BigInt(1000), blockNum = BigInt(5000)))

    // High-TD NewBlock arrives first
    val hdrHigh = Fixtures.Blocks.Genesis.header.copy(number = BigInt(5000))
    peersInfoHolder ! MessageFromPeer(NewBlock(Block(hdrHigh, BlockBody(Nil, Nil)), BigInt(1500)), peer1.id)

    // Lower-TD NewBlock — must NOT downgrade bestNetworkTip
    val hdrLow = Fixtures.Blocks.Genesis.header.copy(number = BigInt(4800))
    peersInfoHolder ! MessageFromPeer(NewBlock(Block(hdrLow, BlockBody(Nil, Nil)), BigInt(100)), peer1.id)

    peersInfoHolder ! RegisterChainWeightCalibrationTarget(calibrationTarget.ref)
    peersInfoHolder ! CalibrateChainWeightNow

    calibrationTarget.expectMsg(SyncProtocol.CalibrateChainWeightFromPeer(BigInt(1500), BigInt(5000)))
  }

  // ─── Test setup ───────────────────────────────────────────────────────────

  trait TestSetup extends EphemBlockchainTestSetup {
    implicit override lazy val system: ActorSystem = ActorSystem("BestNetworkTipTrackingSpec_System")

    blockchainWriter.storeBlockHeader(Fixtures.Blocks.Genesis.header).commit()

    override lazy val blockchainConfig = Config.blockchains.blockchainConfig
    private val forkResolver = new ForkResolver.IrregularStateChangeDaoForkResolver(blockchainConfig.daoForkConfig.get)

    val peerManager: TestProbe = TestProbe()
    val peerEventBus: TestProbe = TestProbe()
    val calibrationTarget: TestProbe = TestProbe()

    val peersInfoHolder: TestActorRef[Nothing] = TestActorRef(
      Props(
        new NetworkPeerManagerActor(
          peerManager.ref,
          peerEventBus.ref,
          storagesInstance.storages.appStateStorage,
          Some(forkResolver),
          isPoWChain = true
        )
      )
    )

    val fakeNodeId: ByteString = ByteString()

    val peer1: Peer =
      Peer(PeerId("peer1"), new InetSocketAddress("127.0.0.1", 1), TestProbe().ref, false, nodeId = Some(fakeNodeId))
    val peer2: Peer =
      Peer(PeerId("peer2"), new InetSocketAddress("127.0.0.1", 2), TestProbe().ref, false, nodeId = Some(fakeNodeId))

    def mkInfo(cap: Capability, td: BigInt, blockNum: BigInt): PeerInfo = {
      val status = RemoteStatus(
        capability = cap,
        networkId = 1,
        chainWeight = ChainWeight.totalDifficultyOnly(td),
        bestHash = Fixtures.Blocks.Genesis.header.hash,
        genesisHash = Fixtures.Blocks.Genesis.header.hash
      )
      PeerInfo(
        remoteStatus = status,
        forkAccepted = true,
        chainWeight = status.chainWeight,
        maxBlockNumber = blockNum,
        bestBlockHash = status.bestHash
      )
    }

    def setupNewPeer(peer: Peer, info: PeerInfo): Unit = {
      peersInfoHolder ! PeerHandshakeSuccessful(peer, info)
      // Each peer generates two peerEventBus Subscribe messages:
      //   1. PeerDisconnectedClassifier — so NPA can observe this peer's disconnect
      //   2. MessageClassifier for per-peer ETH/SNAP message codes
      peerEventBus.expectMsg(Subscribe(PeerDisconnectedClassifier(PeerSelector.WithId(peer.id))))
      peerEventBus.expectMsgClass(classOf[Subscribe]) // per-peer MessageClassifier
      // ETH68 non-genesis peers also fire a GetBlockHeaders probe via peerManager.
      // We use bestHash == genesisHash in mkInfo so no probe fires; nothing to drain here.
    }

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
  }
}
// scalastyle:on magic.number
