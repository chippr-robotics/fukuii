package com.chipprbots.ethereum.jsonrpc

import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference

import org.apache.pekko.actor.ActorRef
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.testkit.TestProbe
import org.apache.pekko.util.ByteString

import cats.effect.unsafe.IORuntime

import scala.concurrent.duration._

import org.scalatest.concurrent.ScalaFutures
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.NormalPatience
import com.chipprbots.ethereum.blockchain.sync.CacheBasedBlacklist
import com.chipprbots.ethereum.crypto
import com.chipprbots.ethereum.jsonrpc.NetService._
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.PeerActor
import com.chipprbots.ethereum.network.PeerId
import com.chipprbots.ethereum.network.PeerManagerActor
import com.chipprbots.ethereum.security.SecureRandomBuilder
import com.chipprbots.ethereum.testing.Tags._
import com.chipprbots.ethereum.utils.NodeStatus
import com.chipprbots.ethereum.utils.ServerStatus
import scala.concurrent.Future

class NetServiceSpec extends AnyFlatSpec with Matchers with ScalaFutures with NormalPatience with SecureRandomBuilder {

  implicit val runtime: IORuntime = IORuntime.global

  "NetService" should "return handshaked peer count" taggedAs (UnitTest, RPCTest) in new TestSetup {
    val resF: Future[Either[JsonRpcError, PeerCountResponse]] = netService
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

  it should "return listening response" taggedAs (UnitTest, RPCTest) in new TestSetup {
    netService.listening(ListeningRequest()).unsafeRunSync() shouldBe Right(ListeningResponse(true))
  }

  it should "return version response" taggedAs (UnitTest, RPCTest) in new TestSetup {
    netService.version(VersionRequest()).unsafeRunSync() shouldBe Right(VersionResponse("42"))
  }

  // Enhanced peer management tests
  it should "list all peers with detailed information" taggedAs (UnitTest, RPCTest) in new TestSetup {
    val resF: Future[Either[JsonRpcError, ListPeersResponse]] = netService
      .listPeers(ListPeersRequest())
      .unsafeToFuture()

    peerManager.expectMsg(PeerManagerActor.GetPeers)
    peerManager.reply(
      PeerManagerActor.Peers(
        Map(
          Peer(
            PeerId("peer1"),
            new InetSocketAddress("192.168.1.1", 30303),
            testRef,
            false,
            nodeId = Some(ByteString("abcd1234"))
          ) -> PeerActor.Status.Handshaked,
          Peer(PeerId("peer2"), new InetSocketAddress("192.168.1.2", 30303), testRef, true) -> PeerActor.Status
            .Connecting
        )
      )
    )

    val result = resF.futureValue
    result.isRight shouldBe true
    val peers = result.toOption.get.peers
    peers should have size 2
    peers.exists(_.id == "peer1") shouldBe true
    peers.exists(_.incomingConnection == true) shouldBe true
  }

  it should "disconnect a peer by id" taggedAs (UnitTest, RPCTest) in new TestSetup {
    val resF: Future[Either[JsonRpcError, DisconnectPeerResponse]] = netService
      .disconnectPeer(DisconnectPeerRequest("peer1"))
      .unsafeToFuture()

    peerManager.expectMsg(PeerManagerActor.DisconnectPeerById(PeerId("peer1")))
    peerManager.reply(PeerManagerActor.DisconnectPeerResponse(disconnected = true))

    resF.futureValue shouldBe Right(DisconnectPeerResponse(success = true))
  }

  it should "handle disconnect peer failure" taggedAs (UnitTest, RPCTest) in new TestSetup {
    val resF: Future[Either[JsonRpcError, DisconnectPeerResponse]] = netService
      .disconnectPeer(DisconnectPeerRequest("nonexistent"))
      .unsafeToFuture()

    peerManager.expectMsg(PeerManagerActor.DisconnectPeerById(PeerId("nonexistent")))
    peerManager.reply(PeerManagerActor.DisconnectPeerResponse(disconnected = false))

    resF.futureValue shouldBe Right(DisconnectPeerResponse(success = false))
  }

  it should "connect to a new peer" taggedAs (UnitTest, RPCTest) in new TestSetup {
    val uri = "enode://abcd1234@192.168.1.100:30303"
    val result = netService.connectToPeer(ConnectToPeerRequest(uri)).unsafeRunSync()

    result.isRight shouldBe true
    result.toOption.get.success shouldBe true
    peerManager.expectMsgClass(classOf[PeerManagerActor.ConnectToPeer])
  }

  it should "reject invalid peer URI" taggedAs (UnitTest, RPCTest) in new TestSetup {
    val result = netService.connectToPeer(ConnectToPeerRequest("invalid-uri")).unsafeRunSync()

    result.isLeft shouldBe true
  }

  // Blacklist management tests
  it should "list blacklisted peers" taggedAs (UnitTest, RPCTest) in new TestSetup {
    // Note: Directly adding to blacklist here since we're testing the listing functionality
    // which queries the blacklist directly. The add/remove operations are tested separately.
    import com.chipprbots.ethereum.blockchain.sync.Blacklist.BlacklistReason
    blacklist.add(PeerManagerActor.PeerAddress("192.168.1.100"), 60.seconds, BlacklistReason.UselessPeer)
    blacklist.add(PeerManagerActor.PeerAddress("192.168.1.101"), 120.seconds, BlacklistReason.UselessPeer)

    val result = netService.listBlacklistedPeers(ListBlacklistedPeersRequest()).unsafeRunSync()

    result.isRight shouldBe true
    val blacklistedPeers = result.toOption.get.blacklistedPeers
    blacklistedPeers should have size 2
    blacklistedPeers.exists(_.id == "192.168.1.100") shouldBe true
    blacklistedPeers.exists(_.id == "192.168.1.101") shouldBe true
  }

  it should "add peer to blacklist with custom duration" taggedAs (UnitTest, RPCTest) in new TestSetup {
    val resF: Future[Either[JsonRpcError, AddToBlacklistResponse]] = netService
      .addToBlacklist(AddToBlacklistRequest("192.168.1.200", Some(300), "Test reason"))
      .unsafeToFuture()

    peerManager.expectMsgClass(classOf[PeerManagerActor.AddToBlacklistRequest])
    peerManager.reply(PeerManagerActor.AddToBlacklistResponse(added = true))

    resF.futureValue shouldBe Right(AddToBlacklistResponse(added = true))
  }

  it should "add peer to permanent blacklist when duration is None" taggedAs (UnitTest, RPCTest) in new TestSetup {
    val resF: Future[Either[JsonRpcError, AddToBlacklistResponse]] = netService
      .addToBlacklist(AddToBlacklistRequest("192.168.1.201", None, "Permanent ban"))
      .unsafeToFuture()

    peerManager.expectMsgClass(classOf[PeerManagerActor.AddToBlacklistRequest])
    peerManager.reply(PeerManagerActor.AddToBlacklistResponse(added = true))

    resF.futureValue shouldBe Right(AddToBlacklistResponse(added = true))
  }

  it should "remove peer from blacklist" taggedAs (UnitTest, RPCTest) in new TestSetup {
    val resF: Future[Either[JsonRpcError, RemoveFromBlacklistResponse]] = netService
      .removeFromBlacklist(RemoveFromBlacklistRequest("192.168.1.100"))
      .unsafeToFuture()

    peerManager.expectMsgClass(classOf[PeerManagerActor.RemoveFromBlacklistRequest])
    peerManager.reply(PeerManagerActor.RemoveFromBlacklistResponse(removed = true))

    resF.futureValue shouldBe Right(RemoveFromBlacklistResponse(removed = true))
  }

  trait TestSetup {
    implicit val system: ActorSystem = ActorSystem("Testsystem")

    val testRef: ActorRef = TestProbe().ref

    val peerManager: TestProbe = TestProbe()

    val blacklist = CacheBasedBlacklist.empty(100)

    val nodeStatus: NodeStatus = NodeStatus(
      crypto.generateKeyPair(secureRandom),
      ServerStatus.Listening(new InetSocketAddress(9000)),
      discoveryStatus = ServerStatus.NotListening
    )
    val netService =
      new NetService(new AtomicReference[NodeStatus](nodeStatus), peerManager.ref, blacklist, NetServiceConfig(5.seconds))
  }
}
