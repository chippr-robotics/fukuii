package com.chipprbots.ethereum.jsonrpc

import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference

import org.apache.pekko.actor.ActorSystem as ClassicActorSystem
import org.apache.pekko.actor.typed
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

  override def afterAll(): Unit =
    testActorSystem.terminate()
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
      new BlockedIPRegistry(Set.empty)
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

    val service = new AdminService(
      nodeStatusHolder,
      null, // peerManager — not used in unit tests (admin_peers / admin_addPeer / admin_removePeer need actor)
      stubBlockchainReader,
      Config.blockchains.blockchainConfig,
      5.seconds,
      "/tmp/test-datadir",
      registry
    )
