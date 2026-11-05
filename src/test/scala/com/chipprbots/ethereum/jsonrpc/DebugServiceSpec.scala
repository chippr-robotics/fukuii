package com.chipprbots.ethereum.jsonrpc

import java.net.InetSocketAddress

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.testkit.TestProbe

import cats.effect.unsafe.IORuntime

import scala.concurrent.Future

import org.scalamock.scalatest.MockFactory
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.jsonrpc.DebugService.ListPeersInfoRequest
import com.chipprbots.ethereum.jsonrpc.DebugService.ListPeersInfoResponse
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.PeerInfo
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.PeerInfoRequestCmd
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.RemoteStatus
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.PeerActor
import com.chipprbots.ethereum.network.PeerId
import com.chipprbots.ethereum.network.PeerManagerActor
import com.chipprbots.ethereum.network.PeerManagerActor.GetPeersCmd
import com.chipprbots.ethereum.network.PeerManagerActor.Peers
import com.chipprbots.ethereum.network.p2p.messages.Capability
import com.chipprbots.ethereum.testing.Tags.*

class DebugServiceSpec
    extends ScalaTestWithActorTestKit
    with AnyFlatSpecLike
    with Matchers
    with MockFactory
    with ScalaFutures:

  implicit val runtime: IORuntime = IORuntime.global
  implicit private val classicActorSystem: ActorSystem = system.toClassic

  "DebugService" should "return list of peers info" taggedAs (UnitTest, RPCTest) in new TestSetup:
    val result: Future[Either[JsonRpcError, ListPeersInfoResponse]] =
      debugService.listPeersInfo(ListPeersInfoRequest()).unsafeToFuture()

    val cmd1: GetPeersCmd = peerManager.expectMsgType[PeerManagerActor.GetPeersCmd]
    cmd1.replyTo ! Peers(Map(peer1 -> PeerActor.Status.Connecting))

    val cmd1Npma: PeerInfoRequestCmd = etcPeerManager.expectMsgType[NetworkPeerManagerActor.PeerInfoRequestCmd]
    cmd1Npma.peerId shouldBe peer1.id
    cmd1Npma.replyTo ! NetworkPeerManagerActor.PeerInfoResponse(Some(peer1Info))

    result.futureValue shouldBe Right(ListPeersInfoResponse(List(peer1Info)))

  it should "return empty list if there are no peers available" taggedAs (UnitTest, RPCTest) in new TestSetup:
    val result: Future[Either[JsonRpcError, ListPeersInfoResponse]] =
      debugService.listPeersInfo(ListPeersInfoRequest()).unsafeToFuture()

    val cmd2: GetPeersCmd = peerManager.expectMsgType[PeerManagerActor.GetPeersCmd]
    cmd2.replyTo ! Peers(Map.empty)

    result.futureValue shouldBe Right(ListPeersInfoResponse(List.empty))

  it should "return empty list if there is no peer info" taggedAs (UnitTest, RPCTest) in new TestSetup:
    val result: Future[Either[JsonRpcError, ListPeersInfoResponse]] =
      debugService.listPeersInfo(ListPeersInfoRequest()).unsafeToFuture()

    val cmd3: GetPeersCmd = peerManager.expectMsgType[PeerManagerActor.GetPeersCmd]
    cmd3.replyTo ! Peers(Map(peer1 -> PeerActor.Status.Connecting))

    val cmd3Npma: PeerInfoRequestCmd = etcPeerManager.expectMsgType[NetworkPeerManagerActor.PeerInfoRequestCmd]
    cmd3Npma.peerId shouldBe peer1.id
    cmd3Npma.replyTo ! NetworkPeerManagerActor.PeerInfoResponse(None)

    result.futureValue shouldBe Right(ListPeersInfoResponse(List.empty))

  class TestSetup(implicit system: ActorSystem):
    implicit val scheduler: typed.Scheduler = system.toTyped.scheduler
    val peerManager: TestProbe = TestProbe()
    val etcPeerManager: TestProbe = TestProbe()
    val debugService = new DebugService(peerManager.ref.toTyped[PeerManagerActor.Command], etcPeerManager.ref)

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
    val peer1Probe: TestProbe = TestProbe()
    val peer1: Peer = Peer(PeerId("peer1"), new InetSocketAddress("127.0.0.1", 1), peer1Probe.ref, false)
    val peer1Info: PeerInfo = initialPeerInfo.withForkAccepted(false)
