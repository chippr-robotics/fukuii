package com.chipprbots.ethereum.jsonrpc

import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference

import cats.effect.IO
import cats.effect.unsafe.IORuntime

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._

import com.chipprbots.ethereum.network.BlockedIPRegistry
import com.chipprbots.ethereum.testing.Tags._
import com.chipprbots.ethereum.utils.NodeStatus
import com.chipprbots.ethereum.utils.ServerStatus

/** Unit tests for AdminService — Besu admin_* namespace.
  *
  * Besu reference:
  *   AdminNodeInfo.java, AdminPeers.java, AdminAddPeer.java, AdminRemovePeer.java, AdminChangeLogLevel.java
  *   DefaultP2PNetwork.java (peer management)
  */
class AdminServiceSpec extends AnyFlatSpec with Matchers {

  implicit val runtime: IORuntime = IORuntime.global

  "AdminService.nodeInfo" should "return P2P info when server is listening" taggedAs UnitTest in new TestSetup {
    val result = service.nodeInfo(AdminService.AdminNodeInfoRequest()).unsafeRunSync()

    result shouldBe a[Right[_, _]]
    val info = result.toOption.get
    info.enode shouldBe defined
    info.enode.get should startWith("enode://")
    info.id should not be empty
    info.ip shouldBe defined
    info.listenAddr shouldBe defined
    info.ports should contain key "listener"
  }

  it should "return minimal info when server is not listening" taggedAs UnitTest in new TestSetup {
    val notListeningStatus = NodeStatus(
      com.chipprbots.ethereum.crypto.generateKeyPair(new java.security.SecureRandom),
      ServerStatus.NotListening,
      ServerStatus.NotListening
    )
    val holder = new AtomicReference(notListeningStatus)
    val svc = new AdminService(holder, null, null, 5.seconds, "/tmp", new BlockedIPRegistry(Set.empty))
    val result = svc.nodeInfo(AdminService.AdminNodeInfoRequest()).unsafeRunSync()

    val info = result.toOption.get
    info.enode shouldBe None
    info.listenAddr shouldBe None
    info.ports shouldBe empty
  }

  "AdminService.changeLogLevel" should "accept valid log level INFO" taggedAs UnitTest in new TestSetup {
    val result = service.changeLogLevel(AdminService.AdminChangeLogLevelRequest("INFO", None)).unsafeRunSync()
    result shouldBe a[Right[_, _]]
  }

  it should "accept valid log level DEBUG with package filter" taggedAs UnitTest in new TestSetup {
    val result = service.changeLogLevel(
      AdminService.AdminChangeLogLevelRequest("DEBUG", Some(List("com.chipprbots")))
    ).unsafeRunSync()
    result shouldBe a[Right[_, _]]
  }

  it should "reject invalid log level" taggedAs UnitTest in new TestSetup {
    val result = service.changeLogLevel(AdminService.AdminChangeLogLevelRequest("VERBOSE", None)).unsafeRunSync()
    result shouldBe a[Left[_, _]]
  }

  "AdminService.blockIP / unblockIP / listBlockedIPs" should "manage blocklist correctly" taggedAs UnitTest in new TestSetup {
    val blockResult = service.blockIP(AdminService.AdminBlockIPRequest("1.2.3.4")).unsafeRunSync()
    blockResult shouldBe Right(AdminService.AdminBlockIPResponse(true))

    val listResult = service.listBlockedIPs(AdminService.AdminListBlockedIPsRequest()).unsafeRunSync()
    listResult.toOption.get.ips should contain("1.2.3.4")

    val unblockResult = service.unblockIP(AdminService.AdminUnblockIPRequest("1.2.3.4")).unsafeRunSync()
    unblockResult shouldBe Right(AdminService.AdminUnblockIPResponse(true))

    val listAfter = service.listBlockedIPs(AdminService.AdminListBlockedIPsRequest()).unsafeRunSync()
    listAfter.toOption.get.ips should not contain "1.2.3.4"
  }

  it should "return false when unblocking an IP not in the list" taggedAs UnitTest in new TestSetup {
    val result = service.unblockIP(AdminService.AdminUnblockIPRequest("9.9.9.9")).unsafeRunSync()
    result shouldBe Right(AdminService.AdminUnblockIPResponse(false))
  }

  "AdminService.getDatadir" should "return configured datadir" taggedAs UnitTest in new TestSetup {
    val result = service.getDatadir(AdminService.AdminDatadirRequest()).unsafeRunSync()
    result shouldBe Right(AdminService.AdminDatadirResponse("/tmp/test-datadir"))
  }

  trait TestSetup {
    val keyPair = com.chipprbots.ethereum.crypto.generateKeyPair(new java.security.SecureRandom)
    val listenAddr = new InetSocketAddress("127.0.0.1", 30305)
    val nodeStatus = NodeStatus(keyPair, ServerStatus.Listening(listenAddr), ServerStatus.NotListening)
    val nodeStatusHolder = new AtomicReference(nodeStatus)
    val registry = new BlockedIPRegistry(Set.empty)

    val service = new AdminService(
      nodeStatusHolder,
      null, // peerManager — not used in unit tests (admin_peers / admin_addPeer / admin_removePeer need actor)
      null, // blockchainReader — not used in unit tests
      5.seconds,
      "/tmp/test-datadir",
      registry
    )
  }
}
