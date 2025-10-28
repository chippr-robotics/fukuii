package com.chipprbots.ethereum.jsonrpc

import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference

import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.testkit.TestProbe

import cats.effect.unsafe.IORuntime

import scala.concurrent.duration._

import org.scalatest.concurrent.ScalaFutures
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.NormalPatience
import com.chipprbots.ethereum.crypto
import com.chipprbots.ethereum.jsonrpc.NetService._
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.PeerActor
import com.chipprbots.ethereum.network.PeerId
import com.chipprbots.ethereum.network.PeerManagerActor
import com.chipprbots.ethereum.security.SecureRandomBuilder
import com.chipprbots.ethereum.utils.NodeStatus
import com.chipprbots.ethereum.utils.ServerStatus

class NetServiceSpec extends AnyFlatSpec with Matchers with ScalaFutures with NormalPatience with SecureRandomBuilder {

  implicit val runtime: IORuntime = IORuntime.global

  "NetService" should "return handshaked peer count" in new TestSetup {
    val resF = netService
      .peerCount(PeerCountRequest())
      .unsafeToFuture()

    peerManager.expectMsg(PeerManagerActor.GetPeers)
    peerManager.reply(
      PeerManagerActor.Peers(
        Map(
          Peer(PeerId("peer1"), new InetSocketAddress(1), testRef, false) -> PeerActor.Status.Handshaked,
          Peer(PeerId("peer2"), new InetSocketAddress(2), testRef, false) -> PeerActor.Status.Handshaked,
          Peer(PeerId("peer3"), new InetSocketAddress(3), testRef, false) -> PeerActor.Status.Connecting
        )
      )
    )

    resF.futureValue shouldBe Right(PeerCountResponse(2))
  }

  it should "return listening response" in new TestSetup {
    netService.listening(ListeningRequest()).unsafeRunSync() shouldBe Right(ListeningResponse(true))
  }

  it should "return version response" in new TestSetup {
    netService.version(VersionRequest()).unsafeRunSync() shouldBe Right(VersionResponse("42"))
  }

  trait TestSetup {
    implicit val system: ActorSystem = ActorSystem("Testsystem")

    val testRef: ActorRef = TestProbe().ref

    val peerManager: TestProbe = TestProbe()

    val nodeStatus: NodeStatus = NodeStatus(
      crypto.generateKeyPair(secureRandom),
      ServerStatus.Listening(new InetSocketAddress(9000)),
      discoveryStatus = ServerStatus.NotListening
    )
    val netService =
      new NetService(new AtomicReference[NodeStatus](nodeStatus), peerManager.ref, NetServiceConfig(5.seconds))
  }
}
