package com.chipprbots.ethereum.jsonrpc

import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference

import org.apache.pekko.actor.ActorSystem as ClassicActorSystem
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.typed
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.scaladsl.adapter.*

import cats.effect.unsafe.IORuntime

import scala.concurrent.duration.*

import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.domain.BlockHash
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.domain.branch.EmptyBranch
import com.chipprbots.ethereum.jsonrpc.AdminService.AdminBlockIPResponse
import com.chipprbots.ethereum.jsonrpc.AdminService.AdminChangeLogLevelResponse
import com.chipprbots.ethereum.jsonrpc.AdminService.AdminDatadirResponse
import com.chipprbots.ethereum.jsonrpc.AdminService.AdminListBlockedIPsResponse
import com.chipprbots.ethereum.jsonrpc.AdminService.AdminNodeInfoResponse
import com.chipprbots.ethereum.jsonrpc.AdminService.AdminUnblockIPResponse
import com.chipprbots.ethereum.jsonrpc.AdminService.EthProtocolInfo
import com.chipprbots.ethereum.network.BlockedIPRegistry
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.PeerActor
import com.chipprbots.ethereum.network.PeerId
import com.chipprbots.ethereum.network.PeerManagerActor
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.Config
import com.chipprbots.ethereum.utils.NodeStatus
import com.chipprbots.ethereum.utils.ServerStatus

/** Unit tests for AdminService — Besu admin_* namespace.
  *
  * Besu reference: AdminNodeInfo.java, AdminPeers.java, AdminAddPeer.java, AdminRemovePeer.java,
  * AdminChangeLogLevel.java DefaultP2PNetwork.java (peer management)
  */
class AdminServiceSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll:

  implicit val runtime: IORuntime = IORuntime.global

  private val testActorSystem: ClassicActorSystem = ClassicActorSystem("AdminServiceSpec")
  implicit val scheduler: typed.Scheduler = testActorSystem.toTyped.scheduler

  private val typedKit: ActorTestKit = ActorTestKit("AdminServiceSpecTyped")

  override def afterAll(): Unit =
    testActorSystem.terminate()
    typedKit.shutdownTestKit()
    super.afterAll()

  "AdminService.nodeInfo" should "return P2P info when server is listening" taggedAs UnitTest in new TestSetup:
    val result: Either[JsonRpcError, AdminNodeInfoResponse] =
      service.nodeInfo(AdminService.AdminNodeInfoRequest()).unsafeRunSync()

    result shouldBe a[Right[?, ?]]
    val info = result.toOption.get
    info.enode shouldBe defined
    info.enode.get should startWith("enode://")
    info.id should not be empty
    info.ip shouldBe defined
    info.listenAddr shouldBe defined
    (info.ports should contain).key("listener")

  it should "return protocols.eth with genesis, head, difficulty, network" taggedAs UnitTest in new TestSetup:
    val result: Either[JsonRpcError, AdminNodeInfoResponse] =
      service.nodeInfo(AdminService.AdminNodeInfoRequest()).unsafeRunSync()
    val info = result.toOption.get
    (info.protocols should contain).key("eth")
    val eth: EthProtocolInfo = info.protocols("eth")
    eth.genesis should startWith("0x")
    eth.head should startWith("0x")
    eth.difficulty should startWith("0x")
    eth.network shouldBe Config.blockchains.blockchainConfig.networkId

  it should "return activeFork as a non-empty string" taggedAs UnitTest in new TestSetup:
    val result: Either[JsonRpcError, AdminNodeInfoResponse] =
      service.nodeInfo(AdminService.AdminNodeInfoRequest()).unsafeRunSync()
    val info = result.toOption.get
    info.activeFork should not be empty

  it should "return minimal info when server is not listening" taggedAs UnitTest in new TestSetup:
    val notListeningStatus: NodeStatus = NodeStatus(
      com.chipprbots.ethereum.crypto.generateKeyPair(new java.security.SecureRandom),
      ServerStatus.NotListening,
      ServerStatus.NotListening
    )
    val holder = new AtomicReference(notListeningStatus)
    val svc = new AdminService(
      holder,
      null,
      stubBlockchainReader,
      Config.blockchains.blockchainConfig,
      5.seconds,
      "/tmp",
      new BlockedIPRegistry(Set.empty),
      null, // chainImporter — admin_importChain is covered by ChainExportImportSpec
      () => cats.effect.IO.pure(com.chipprbots.ethereum.blockchain.sync.SyncProtocol.Status.NotSyncing)
    )
    val result: Either[JsonRpcError, AdminNodeInfoResponse] =
      svc.nodeInfo(AdminService.AdminNodeInfoRequest()).unsafeRunSync()

    val info = result.toOption.get
    info.enode shouldBe None
    info.listenAddr shouldBe None
    info.ports shouldBe empty

  "AdminService.changeLogLevel" should "accept valid log level INFO" taggedAs UnitTest in new TestSetup:
    val result: Either[JsonRpcError, AdminChangeLogLevelResponse] =
      service.changeLogLevel(AdminService.AdminChangeLogLevelRequest("INFO", None)).unsafeRunSync()
    result shouldBe a[Right[?, ?]]

  it should "accept valid log level DEBUG with package filter" taggedAs UnitTest in new TestSetup:
    val result: Either[JsonRpcError, AdminChangeLogLevelResponse] = service
      .changeLogLevel(
        AdminService.AdminChangeLogLevelRequest("DEBUG", Some(List("com.chipprbots")))
      )
      .unsafeRunSync()
    result shouldBe a[Right[?, ?]]

  it should "reject invalid log level" taggedAs UnitTest in new TestSetup:
    val result: Either[JsonRpcError, AdminChangeLogLevelResponse] =
      service.changeLogLevel(AdminService.AdminChangeLogLevelRequest("VERBOSE", None)).unsafeRunSync()
    result shouldBe a[Left[?, ?]]

  "AdminService.blockIP / unblockIP / listBlockedIPs" should "manage blocklist correctly" taggedAs UnitTest in new TestSetup:
    val blockResult: Either[JsonRpcError, AdminBlockIPResponse] =
      service.blockIP(AdminService.AdminBlockIPRequest("1.2.3.4")).unsafeRunSync()
    blockResult shouldBe Right(AdminService.AdminBlockIPResponse(true))

    val listResult: Either[JsonRpcError, AdminListBlockedIPsResponse] =
      service.listBlockedIPs(AdminService.AdminListBlockedIPsRequest()).unsafeRunSync()
    listResult.toOption.get.ips should contain("1.2.3.4")

    val unblockResult: Either[JsonRpcError, AdminUnblockIPResponse] =
      service.unblockIP(AdminService.AdminUnblockIPRequest("1.2.3.4")).unsafeRunSync()
    unblockResult shouldBe Right(AdminService.AdminUnblockIPResponse(true))

    val listAfter: Either[JsonRpcError, AdminListBlockedIPsResponse] =
      service.listBlockedIPs(AdminService.AdminListBlockedIPsRequest()).unsafeRunSync()
    listAfter.toOption.get.ips should not contain "1.2.3.4"

  it should "return false when unblocking an IP not in the list" taggedAs UnitTest in new TestSetup:
    val result: Either[JsonRpcError, AdminUnblockIPResponse] =
      service.unblockIP(AdminService.AdminUnblockIPRequest("9.9.9.9")).unsafeRunSync()
    result shouldBe Right(AdminService.AdminUnblockIPResponse(false))

  // ---- B-277: a real IP block ---------------------------------------------------------------------------------

  "AdminService.blockIP" should "keep the canonical form of an IPv4 or IPv6 literal, and trim it" taggedAs UnitTest in new FakePeerSetup():
    service.blockIP(AdminService.AdminBlockIPRequest(" 8.8.8.8 ")).unsafeRunSync() shouldBe
      Right(AdminBlockIPResponse(true))
    service.blockIP(AdminService.AdminBlockIPRequest("2001:DB8::1")).unsafeRunSync() shouldBe
      Right(AdminBlockIPResponse(true))
    registry.all shouldBe Set("8.8.8.8", java.net.InetAddress.getByName("[2001:db8::1]").getHostAddress)
    // The same address again, in another spelling, is not newly blocked.
    service.blockIP(AdminService.AdminBlockIPRequest("8.8.8.8")).unsafeRunSync() shouldBe
      Right(AdminBlockIPResponse(false))

  it should "refuse anything that is not an IP literal, block nothing, and send nothing to the peer manager" taggedAs UnitTest in new FakePeerSetup():
    List("example.com", "", "  ", "1.2.3", "1.2.3.4.5", "01.2.3.4", "::zz", "fe80::1%eth0", "1.2.3.4:30303").foreach {
      raw =>
        withClue(s"'$raw': ")(
          service.blockIP(AdminService.AdminBlockIPRequest(raw)).unsafeRunSync() shouldBe a[Left[?, ?]]
        )
    }
    registry.all shouldBe empty
    sentToPeerManager() shouldBe empty

  it should "disconnect every peer connected from the address, inbound and outbound, and no other" taggedAs UnitTest in new FakePeerSetup(
    Seq(
      peerAt("p-out", "203.0.113.9", 30303, inbound = false),
      peerAt("p-in", "203.0.113.9", 51000, inbound = true),
      peerAt("p-other", "203.0.113.10", 30303, inbound = false)
    )
  ):
    service.blockIP(AdminService.AdminBlockIPRequest("203.0.113.9")).unsafeRunSync() shouldBe
      Right(AdminBlockIPResponse(true))
    sentToPeerManager().collect { case PeerManagerActor.DisconnectPeerFireAndForgetCmd(id) => id }.toSet shouldBe
      Set(PeerId("p-out"), PeerId("p-in"))

  it should "disconnect the peers at an address that was already blocked" taggedAs UnitTest in new FakePeerSetup(
    Seq(peerAt("p-late", "203.0.113.9", 30303, inbound = false))
  ):
    registry.block("203.0.113.9") shouldBe true
    service.blockIP(AdminService.AdminBlockIPRequest("203.0.113.9")).unsafeRunSync() shouldBe
      Right(AdminBlockIPResponse(false))
    sentToPeerManager().collect { case PeerManagerActor.DisconnectPeerFireAndForgetCmd(id) => id } shouldBe
      List(PeerId("p-late"))

  "AdminService.unblockIP" should "unblock by canonical form and clear the automatic blacklist entry of the address" taggedAs UnitTest in new FakePeerSetup():
    registry.block("1.2.3.4") shouldBe true
    service.unblockIP(AdminService.AdminUnblockIPRequest(" 1.2.3.4 ")).unsafeRunSync() shouldBe
      Right(AdminUnblockIPResponse(true))
    registry.all shouldBe empty
    sentToPeerManager().collect { case PeerManagerActor.RemoveFromBlacklistCmd(req, _) => req.address } shouldBe
      List("1.2.3.4")
    sentToPeerManager().collect { case PeerManagerActor.RedialMaintainedAtCmd(ip) => ip } shouldBe List("1.2.3.4")

  it should "refuse a non-literal, and report false for an address that was not blocked" taggedAs UnitTest in new FakePeerSetup():
    service.unblockIP(AdminService.AdminUnblockIPRequest("example.com")).unsafeRunSync() shouldBe a[Left[?, ?]]
    sentToPeerManager() shouldBe empty
    service.unblockIP(AdminService.AdminUnblockIPRequest("9.9.9.9")).unsafeRunSync() shouldBe
      Right(AdminUnblockIPResponse(false))
    // An address the operator did not block is left as it is: its automatic blacklist entry is not cleared.
    sentToPeerManager().collect {
      case c: PeerManagerActor.RemoveFromBlacklistCmd => c
      case c: PeerManagerActor.RedialMaintainedAtCmd  => c
    } shouldBe empty

  // ---- enode validation: one place, before anything is sent to the actor --------------------------------------

  private val goodId = "ab" * 64

  private val badEnodes: List[(String, String)] = List(
    "wrong scheme" -> s"http://$goodId@1.2.3.4:30303",
    "no scheme" -> s"$goodId@1.2.3.4:30303",
    "no node id" -> "enode://1.2.3.4:30303",
    "empty node id" -> "enode://@1.2.3.4:30303",
    "node id one digit short" -> s"enode://${goodId.dropRight(1)}@1.2.3.4:30303",
    "node id one digit long" -> s"enode://${goodId}a@1.2.3.4:30303",
    "node id not hex" -> s"enode://${"zz" * 64}@1.2.3.4:30303",
    "node id with 0x" -> s"enode://0x${goodId.drop(2)}@1.2.3.4:30303",
    "no host" -> s"enode://$goodId@:30303",
    "no port" -> s"enode://$goodId@1.2.3.4",
    "port zero" -> s"enode://$goodId@1.2.3.4:0",
    "port too high" -> s"enode://$goodId@1.2.3.4:65536",
    "not a URL" -> "enode://not a url",
    "empty" -> ""
  )

  "AdminService.parseEnode" should "refuse each bad shape and say why" taggedAs UnitTest in {
    badEnodes.foreach { case (name, raw) =>
      withClue(s"$name ('$raw'): ")(AdminService.parseEnode(raw) shouldBe a[Left[?, ?]])
    }
  }

  it should "accept a good enode, an IPv6 host, a name, upper-case hex and a ?discport query" taggedAs UnitTest in {
    List(
      s"enode://$goodId@1.2.3.4:30303",
      s"enode://${goodId.toUpperCase}@1.2.3.4:30303",
      s"enode://$goodId@[2001:db8::1]:30303",
      s"enode://$goodId@peer.example.org:30303",
      s"enode://$goodId@1.2.3.4:30303?discport=30301",
      s" enode://$goodId@1.2.3.4:65535 "
    ).foreach(raw => withClue(s"'$raw': ")(AdminService.parseEnode(raw) shouldBe a[Right[?, ?]]))
    AdminService.parseEnode(s"enode://$goodId@1.2.3.4:30303?discport=30301").toOption.get.getPort shouldBe 30303
  }

  "AdminService.addPeer / addTrustedPeer / removeTrustedPeer" should "refuse a bad enode with false and send nothing to the peer manager" taggedAs UnitTest in new FakePeerSetup():
    badEnodes.foreach { case (name, raw) =>
      withClue(s"addPeer, $name: ")(
        service.addPeer(AdminService.AdminAddPeerRequest(raw)).unsafeRunSync() shouldBe
          Right(AdminService.AdminAddPeerResponse(false))
      )
      withClue(s"addTrustedPeer, $name: ")(
        service.addTrustedPeer(AdminService.AdminAddTrustedPeerRequest(raw)).unsafeRunSync() shouldBe
          Right(AdminService.AdminAddTrustedPeerResponse(false))
      )
      withClue(s"removeTrustedPeer, $name: ")(
        service.removeTrustedPeer(AdminService.AdminRemoveTrustedPeerRequest(raw)).unsafeRunSync() shouldBe
          Right(AdminService.AdminRemoveTrustedPeerResponse(false))
      )
    }
    sentToPeerManager() shouldBe empty

  it should "send a good enode to the peer manager" taggedAs UnitTest in new FakePeerSetup():
    val enode = s"enode://${goodId.toUpperCase}@1.2.3.4:30303?discport=30301"
    service.addPeer(AdminService.AdminAddPeerRequest(enode)).unsafeRunSync() shouldBe
      Right(AdminService.AdminAddPeerResponse(true))
    service.addTrustedPeer(AdminService.AdminAddTrustedPeerRequest(enode)).unsafeRunSync() shouldBe
      Right(AdminService.AdminAddTrustedPeerResponse(true))
    service.removeTrustedPeer(AdminService.AdminRemoveTrustedPeerRequest(enode)).unsafeRunSync() shouldBe
      Right(AdminService.AdminRemoveTrustedPeerResponse(true))
    val sent = sentToPeerManager()
    sent.collect { case PeerManagerActor.AddMaintainedPeerCmd(uri, _) => uri.getHost } shouldBe List("1.2.3.4")
    sent.collect { case PeerManagerActor.AddTrustedPeerCmd(uri, _) => uri.getPort } shouldBe List(30303)
    sent.collect { case PeerManagerActor.RemoveTrustedPeerCmd(nodeId, _) => nodeId } shouldBe List(goodId)

  // ---- maxPeers range, admin state --------------------------------------------------------------------------

  "AdminService.maxPeers" should "refuse a negative limit and send nothing" taggedAs UnitTest in new FakePeerSetup():
    List(-1, Int.MinValue).foreach { n =>
      withClue(s"maxPeers($n): ")(
        service.maxPeers(AdminService.AdminMaxPeersRequest(n)).unsafeRunSync() shouldBe a[Left[?, ?]]
      )
    }
    sentToPeerManager() shouldBe empty

  it should "pass 0 and a large limit to the peer manager, as it always did" taggedAs UnitTest in new FakePeerSetup():
    service.maxPeers(AdminService.AdminMaxPeersRequest(0)).unsafeRunSync() shouldBe
      Right(AdminService.AdminMaxPeersResponse(true))
    service.maxPeers(AdminService.AdminMaxPeersRequest(5000)).unsafeRunSync() shouldBe
      Right(AdminService.AdminMaxPeersResponse(true))
    sentToPeerManager().collect { case PeerManagerActor.SetMaxPeersCmd(n, _) => n } shouldBe List(0, 5000)

  "AdminService.adminState" should "return what the peer manager holds, and the configured limits" taggedAs UnitTest in new FakePeerSetup():
    service.adminState().unsafeRunSync() shouldBe Right(fakeState)
    service.peerLimits shouldBe Some(AdminService.PeerLimits(minOutgoing = 10, maxOutgoing = 25, maxIncoming = 50))

  /** A peer manager that records every command it gets and answers the ones AdminService asks. */
  /** A connected peer at `host:port`: defined beside the setups, so that a setup's own arguments can use it. */
  private def peerAt(id: String, host: String, port: Int, inbound: Boolean): Peer =
    Peer(
      PeerId(id),
      new InetSocketAddress(host, port),
      typedKit.createTestProbe[PeerActor.Command]().ref,
      inbound
    )

  class FakePeerSetup(peers: Seq[Peer] = Seq.empty) extends TestSetup:
    val fakeState: PeerManagerActor.AdminState =
      PeerManagerActor.AdminState(Some(7), 7, Set("aa" * 64), Set("bb" * 64))
    private val received = new java.util.concurrent.ConcurrentLinkedQueue[PeerManagerActor.Command]()

    val fakePeerManager: typed.ActorRef[PeerManagerActor.Command] = typedKit.spawn(
      Behaviors.receiveMessage[PeerManagerActor.Command] { msg =>
        received.add(msg)
        msg match
          case PeerManagerActor.GetPeersCmd(r) =>
            r ! PeerManagerActor.Peers(peers.map(_ -> PeerActor.Status.Handshaked).toMap)
          case PeerManagerActor.AddMaintainedPeerCmd(_, r)   => r ! PeerManagerActor.AddMaintainedPeerResponse(true)
          case PeerManagerActor.AddTrustedPeerCmd(_, r)      => r ! PeerManagerActor.AddTrustedPeerResponse(true)
          case PeerManagerActor.RemoveTrustedPeerCmd(_, r)   => r ! PeerManagerActor.RemoveTrustedPeerResponse(true)
          case PeerManagerActor.SetMaxPeersCmd(_, r)         => r ! PeerManagerActor.SetMaxPeersResponse(true)
          case PeerManagerActor.GetAdminStateCmd(r)          => r ! fakeState
          case PeerManagerActor.RemoveFromBlacklistCmd(_, r) => r ! PeerManagerActor.RemoveFromBlacklistResponse(true)
          case _                                             => ()
        Behaviors.same
      }
    )

    override val service: AdminService = new AdminService(
      nodeStatusHolder,
      fakePeerManager,
      stubBlockchainReader,
      Config.blockchains.blockchainConfig,
      5.seconds,
      "/tmp/test-datadir",
      registry,
      null,
      () => cats.effect.IO.pure(com.chipprbots.ethereum.blockchain.sync.SyncProtocol.Status.NotSyncing),
      Some(AdminService.PeerLimits(minOutgoing = 10, maxOutgoing = 25, maxIncoming = 50))
    )

    /** What the peer manager received, in order, once it has handled everything sent before (an ask is answered after
      * the earlier messages), not counting the ask this makes.
      */
    def sentToPeerManager(): List[PeerManagerActor.Command] =
      val probe = typedKit.createTestProbe[PeerManagerActor.AdminState]()
      fakePeerManager ! PeerManagerActor.GetAdminStateCmd(probe.ref)
      probe.receiveMessage()
      import scala.jdk.CollectionConverters.*
      received.asScala.toList.dropRight(1)

  "AdminService.getDatadir" should "return configured datadir" taggedAs UnitTest in new TestSetup:
    val result: Either[JsonRpcError, AdminDatadirResponse] =
      service.getDatadir(AdminService.AdminDatadirRequest()).unsafeRunSync()
    result shouldBe Right(AdminService.AdminDatadirResponse("/tmp/test-datadir"))

  trait TestSetup:
    val keyPair: AsymmetricCipherKeyPair =
      com.chipprbots.ethereum.crypto.generateKeyPair(new java.security.SecureRandom)
    val listenAddr = new InetSocketAddress("127.0.0.1", 30305)
    val nodeStatus: NodeStatus = NodeStatus(keyPair, ServerStatus.Listening(listenAddr), ServerStatus.NotListening)
    val nodeStatusHolder = new AtomicReference(nodeStatus)
    val registry = new BlockedIPRegistry(Set.empty)

    /** Minimal BlockchainReader stub — only overrides the three methods used by nodeInfo. */
    val stubBlockchainReader: BlockchainReader = new BlockchainReader(null, null, null, null, null, null, null, null):
      override val genesisHeader = Fixtures.Blocks.Block3125369.header
      override def getBestBranch = EmptyBranch
      override def getChainWeightByHash(hash: BlockHash) = None

    val service: AdminService = new AdminService(
      nodeStatusHolder,
      null, // peerManager — not used in unit tests (admin_peers / admin_addPeer / admin_removePeer need actor)
      stubBlockchainReader,
      Config.blockchains.blockchainConfig,
      5.seconds,
      "/tmp/test-datadir",
      registry,
      null, // chainImporter — admin_importChain is covered by ChainExportImportSpec
      () => cats.effect.IO.pure(com.chipprbots.ethereum.blockchain.sync.SyncProtocol.Status.NotSyncing)
    )
