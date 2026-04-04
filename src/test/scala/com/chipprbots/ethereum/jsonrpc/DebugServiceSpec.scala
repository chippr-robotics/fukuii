package com.chipprbots.ethereum.jsonrpc

import java.net.InetSocketAddress

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.testkit.TestKit
import org.apache.pekko.testkit.TestProbe

import cats.effect.unsafe.IORuntime

import org.scalamock.scalatest.MockFactory
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.WithActorSystemShutDown
import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.jsonrpc.DebugService.ListPeersInfoRequest
import com.chipprbots.ethereum.jsonrpc.DebugService.ListPeersInfoResponse
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.PeerInfo
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.RemoteStatus
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.PeerActor
import com.chipprbots.ethereum.network.PeerId
import com.chipprbots.ethereum.network.PeerManagerActor
import com.chipprbots.ethereum.network.PeerManagerActor.Peers
import com.chipprbots.ethereum.network.p2p.messages.Capability
import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.db.storage.TransactionMappingStorage
import com.chipprbots.ethereum.domain.BlockchainReader
import scala.concurrent.Future
import scala.concurrent.Future
import scala.concurrent.Future
import com.chipprbots.ethereum.testing.Tags._

class DebugServiceSpec
    extends TestKit(ActorSystem("ActorSystem_DebugServiceSpec"))
    with AnyFlatSpecLike
    with WithActorSystemShutDown
    with Matchers
    with MockFactory
    with ScalaFutures {

  implicit val runtime: IORuntime = IORuntime.global
  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = scaled(org.scalatest.time.Span(5, org.scalatest.time.Seconds)))

  "DebugService" should "return list of peers info" taggedAs (UnitTest, RPCTest) in new TestSetup {
    val result: Future[Either[JsonRpcError, ListPeersInfoResponse]] =
      debugService.listPeersInfo(ListPeersInfoRequest()).unsafeToFuture()

    peerManager.expectMsg(PeerManagerActor.GetPeers)
    peerManager.reply(Peers(Map(peer1 -> PeerActor.Status.Connecting)))

    etcPeerManager.expectMsg(NetworkPeerManagerActor.PeerInfoRequest(peer1.id))
    etcPeerManager.reply(NetworkPeerManagerActor.PeerInfoResponse(Some(peer1Info)))

    result.futureValue shouldBe Right(ListPeersInfoResponse(List(peer1Info)))
  }

  it should "return empty list if there are no peers available" taggedAs (UnitTest, RPCTest) in new TestSetup {
    val result: Future[Either[JsonRpcError, ListPeersInfoResponse]] =
      debugService.listPeersInfo(ListPeersInfoRequest()).unsafeToFuture()

    peerManager.expectMsg(PeerManagerActor.GetPeers)
    peerManager.reply(Peers(Map.empty))

    result.futureValue shouldBe Right(ListPeersInfoResponse(List.empty))
  }

  it should "return empty list if there is no peer info" taggedAs (UnitTest, RPCTest) in new TestSetup {
    val result: Future[Either[JsonRpcError, ListPeersInfoResponse]] =
      debugService.listPeersInfo(ListPeersInfoRequest()).unsafeToFuture()

    peerManager.expectMsg(PeerManagerActor.GetPeers)
    peerManager.reply(Peers(Map(peer1 -> PeerActor.Status.Connecting)))

    etcPeerManager.expectMsg(NetworkPeerManagerActor.PeerInfoRequest(peer1.id))
    etcPeerManager.reply(NetworkPeerManagerActor.PeerInfoResponse(None))

    result.futureValue shouldBe Right(ListPeersInfoResponse(List.empty))
  }

  // CPU profiling via JFR (M-024)

  it should "start and stop CPU profiling" taggedAs (UnitTest, RPCTest) in new TestSetup {
    import DebugService._
    val startResult = debugService.startCpuProfile(StartCpuProfileRequest(None)).unsafeToFuture()
    startResult.futureValue shouldBe Right(StartCpuProfileResponse(true))

    val stopResult = debugService.stopCpuProfile(StopCpuProfileRequest()).unsafeToFuture()
    stopResult.futureValue match {
      case Right(resp) =>
        resp.file should endWith(".jfr")
        resp.sizeBytes should be > 0L
        // Clean up
        java.nio.file.Files.deleteIfExists(java.nio.file.Paths.get(resp.file))
      case Left(err) => fail(s"Expected success but got error: $err")
    }
  }

  it should "reject starting CPU profiling when already in progress" taggedAs (UnitTest, RPCTest) in new TestSetup {
    import DebugService._
    debugService.startCpuProfile(StartCpuProfileRequest(None)).unsafeToFuture().futureValue
    val secondStart = debugService.startCpuProfile(StartCpuProfileRequest(None)).unsafeToFuture()
    secondStart.futureValue shouldBe a[Left[_, _]]

    // Clean up
    val stopResult = debugService.stopCpuProfile(StopCpuProfileRequest()).unsafeToFuture().futureValue
    stopResult.foreach(r => java.nio.file.Files.deleteIfExists(java.nio.file.Paths.get(r.file)))
  }

  it should "reject stopping CPU profiling when none in progress" taggedAs (UnitTest, RPCTest) in new TestSetup {
    import DebugService._
    val result = debugService.stopCpuProfile(StopCpuProfileRequest()).unsafeToFuture()
    result.futureValue shouldBe a[Left[_, _]]
  }

  it should "write JFR profile to custom file path" taggedAs (UnitTest, RPCTest) in new TestSetup {
    import DebugService._
    val tmpFile = java.nio.file.Files.createTempFile("fukuii-test-", ".jfr")
    java.nio.file.Files.delete(tmpFile) // Delete so JFR creates it
    val startResult = debugService.startCpuProfile(StartCpuProfileRequest(Some(tmpFile.toString))).unsafeToFuture()
    startResult.futureValue shouldBe Right(StartCpuProfileResponse(true))

    val stopResult = debugService.stopCpuProfile(StopCpuProfileRequest()).unsafeToFuture()
    stopResult.futureValue match {
      case Right(resp) =>
        resp.file shouldBe tmpFile.toString
        resp.sizeBytes should be > 0L
        java.nio.file.Files.deleteIfExists(tmpFile)
      case Left(err) => fail(s"Expected success but got error: $err")
    }
  }

  class TestSetup(implicit system: ActorSystem) {
    val peerManager: TestProbe = TestProbe()
    val etcPeerManager: TestProbe = TestProbe()
    val mockBlockchainReader = mock[BlockchainReader]
    val mockAppStateStorage = mock[AppStateStorage]
    val mockTxMappingStorage = mock[TransactionMappingStorage]
    val debugService = new DebugService(peerManager.ref, etcPeerManager.ref, mockBlockchainReader, mockAppStateStorage, mockTxMappingStorage)

    val peerStatus: RemoteStatus = RemoteStatus(
      capability = Capability.ETH68,
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
    val peer1Probe: TestProbe = TestProbe()
    val peer1: Peer = Peer(PeerId("peer1"), new InetSocketAddress("127.0.0.1", 1), peer1Probe.ref, false)
    val peer1Info: PeerInfo = initialPeerInfo.withForkAccepted(false)
  }
}
