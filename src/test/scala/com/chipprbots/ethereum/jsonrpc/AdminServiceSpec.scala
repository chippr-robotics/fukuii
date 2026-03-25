package com.chipprbots.ethereum.jsonrpc

import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference

import org.apache.pekko.actor.ActorRef
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.testkit.TestProbe
import org.apache.pekko.util.ByteString

import cats.effect.unsafe.IORuntime

import scala.concurrent.Future
import scala.concurrent.duration._

import org.scalatest.concurrent.ScalaFutures
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.NormalPatience
import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.crypto
import com.chipprbots.ethereum.domain._
import com.chipprbots.ethereum.jsonrpc.AdminService._
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.PeerActor
import com.chipprbots.ethereum.network.PeerId
import com.chipprbots.ethereum.network.PeerManagerActor
import com.chipprbots.ethereum.security.SecureRandomBuilder
import com.chipprbots.ethereum.testing.Tags._
import com.chipprbots.ethereum.utils.NodeStatus
import com.chipprbots.ethereum.utils.ServerStatus

/** Tests for AdminService — admin_nodeInfo, admin_peers, admin_addPeer,
  * admin_removePeer, admin_datadir, admin_exportChain, admin_importChain.
  */
class AdminServiceSpec extends AnyFlatSpec with Matchers with ScalaFutures with NormalPatience with SecureRandomBuilder {

  implicit val runtime: IORuntime = IORuntime.global

  // ===== nodeInfo =====

  "AdminService" should "return enode URL when server is listening" taggedAs (UnitTest, RPCTest) in new TestSetup {
    val result = adminService.nodeInfo(AdminNodeInfoRequest()).unsafeRunSync()
    result shouldBe Symbol("right")
    val info = result.toOption.get
    info.enode shouldBe defined
    info.enode.get should startWith("enode://")
    info.enode.get should include(nodeId)
    info.ip shouldBe defined
    info.listenAddr shouldBe defined
    info.name shouldBe "fukuii/v0.1"
    info.ports should contain key "listener"
  }

  it should "return partial info when server is not listening" taggedAs (UnitTest, RPCTest) in new TestSetup {
    nodeStatusRef.set(nodeStatusRef.get().copy(serverStatus = ServerStatus.NotListening))

    val result = adminService.nodeInfo(AdminNodeInfoRequest()).unsafeRunSync()
    result shouldBe Symbol("right")
    val info = result.toOption.get
    info.enode shouldBe None
    info.ip shouldBe None
    info.listenAddr shouldBe None
    info.id shouldBe nodeId
    info.ports shouldBe empty
  }

  it should "include correct node ID from key" taggedAs (UnitTest, RPCTest) in new TestSetup {
    val result = adminService.nodeInfo(AdminNodeInfoRequest()).unsafeRunSync()
    result.toOption.get.id shouldBe nodeId
  }

  // ===== peers =====

  it should "return list of connected peers" taggedAs (UnitTest, RPCTest) in new TestSetup {
    val resF: Future[Either[JsonRpcError, AdminPeersResponse]] = adminService
      .peers(AdminPeersRequest())
      .unsafeToFuture()

    peerManager.expectMsg(PeerManagerActor.GetPeers)
    peerManager.reply(
      PeerManagerActor.Peers(
        Map(
          Peer(PeerId("peer1"), new InetSocketAddress("192.168.1.1", 30303), testRef, false,
            nodeId = Some(ByteString("abcd1234"))) -> PeerActor.Status.Handshaked,
          Peer(PeerId("peer2"), new InetSocketAddress("192.168.1.2", 30303), testRef, true) -> PeerActor.Status.Handshaked
        )
      )
    )

    val result = resF.futureValue
    result shouldBe Symbol("right")
    val peers = result.toOption.get.peers
    peers should have size 2
  }

  it should "return empty list when no peers" taggedAs (UnitTest, RPCTest) in new TestSetup {
    val resF: Future[Either[JsonRpcError, AdminPeersResponse]] = adminService
      .peers(AdminPeersRequest())
      .unsafeToFuture()

    peerManager.expectMsg(PeerManagerActor.GetPeers)
    peerManager.reply(PeerManagerActor.Peers(Map.empty))

    val result = resF.futureValue
    result shouldBe Symbol("right")
    result.toOption.get.peers shouldBe empty
  }

  // ===== addPeer =====

  it should "return success for valid enode URL in addPeer" taggedAs (UnitTest, RPCTest) in new TestSetup {
    val result = adminService.addPeer(AdminAddPeerRequest("enode://abcd1234@192.168.1.100:30303")).unsafeRunSync()
    result shouldBe Symbol("right")
    result.toOption.get.success shouldBe true
    peerManager.expectMsgClass(classOf[PeerManagerActor.ConnectToPeer])
  }

  it should "return false for malformed enode URL in addPeer" taggedAs (UnitTest, RPCTest) in new TestSetup {
    // Invalid URI with spaces triggers URISyntaxException
    val result = adminService.addPeer(AdminAddPeerRequest("not a valid uri with spaces")).unsafeRunSync()
    result shouldBe Symbol("right")
    result.toOption.get.success shouldBe false
  }

  // ===== removePeer =====

  it should "return success for valid enode URL with node ID in removePeer" taggedAs (UnitTest, RPCTest) in new TestSetup {
    val result = adminService.removePeer(AdminRemovePeerRequest("enode://abcd1234@192.168.1.100:30303")).unsafeRunSync()
    result shouldBe Symbol("right")
    result.toOption.get.success shouldBe true
    peerManager.expectMsgClass(classOf[PeerManagerActor.DisconnectPeerById])
  }

  it should "return false when no userInfo in URL for removePeer" taggedAs (UnitTest, RPCTest) in new TestSetup {
    val result = adminService.removePeer(AdminRemovePeerRequest("http://192.168.1.100:30303")).unsafeRunSync()
    result shouldBe Symbol("right")
    result.toOption.get.success shouldBe false
  }

  // ===== getDatadir =====

  it should "return configured datadir path" taggedAs (UnitTest, RPCTest) in new TestSetup {
    val result = adminService.getDatadir(AdminDatadirRequest()).unsafeRunSync()
    result shouldBe Right(AdminDatadirResponse("/tmp/fukuii-test"))
  }

  // ===== exportChain / importChain =====

  it should "export and import blocks round-trip" taggedAs (UnitTest, RPCTest) in new BlockchainTestSetup {
    storeTestBlocks()

    val tmpFile = File.createTempFile("fukuii-export-", ".rlp")
    tmpFile.deleteOnExit()

    // Export
    val exportResult = adminService.exportChain(AdminExportChainRequest(tmpFile.getAbsolutePath, Some(0), Some(1))).unsafeRunSync()
    exportResult shouldBe Symbol("right")
    exportResult.toOption.get.success shouldBe true
    tmpFile.length() should be > 0L

    // Import reads back without error
    val importResult = adminService.importChain(AdminImportChainRequest(tmpFile.getAbsolutePath)).unsafeRunSync()
    importResult shouldBe Symbol("right")
    importResult.toOption.get.success shouldBe true
  }

  it should "handle export of empty range" taggedAs (UnitTest, RPCTest) in new BlockchainTestSetup {
    val tmpFile = File.createTempFile("fukuii-export-empty-", ".rlp")
    tmpFile.deleteOnExit()

    val result = adminService.exportChain(AdminExportChainRequest(tmpFile.getAbsolutePath, Some(999), Some(999))).unsafeRunSync()
    result shouldBe Symbol("right")
    result.toOption.get.success shouldBe true
  }

  it should "handle import of missing file gracefully" taggedAs (UnitTest, RPCTest) in new TestSetup {
    val result = adminService.importChain(AdminImportChainRequest("/tmp/nonexistent-file-12345.rlp")).unsafeRunSync()
    result shouldBe Symbol("right")
    result.toOption.get.success shouldBe false
  }

  // ===== Test Setups =====

  trait TestSetup {
    implicit val system: ActorSystem = ActorSystem("AdminServiceSpec_System")

    val testRef: ActorRef = TestProbe().ref
    val peerManager: TestProbe = TestProbe()

    private val nodeKey = crypto.generateKeyPair(secureRandom)
    val nodeId: String = com.chipprbots.ethereum.utils.Hex.toHexString(
      NodeStatus(nodeKey, ServerStatus.Listening(new InetSocketAddress(30305)), ServerStatus.NotListening).nodeId
    )
    val nodeStatusRef: AtomicReference[NodeStatus] = new AtomicReference[NodeStatus](
      NodeStatus(
        nodeKey,
        ServerStatus.Listening(new InetSocketAddress(30305)),
        discoveryStatus = ServerStatus.NotListening
      )
    )

    val adminService = new AdminService(
      nodeStatusRef,
      peerManager.ref,
      null, // blockchainReader not needed for non-export tests
      5.seconds,
      "/tmp/fukuii-test"
    )
  }

  class BlockchainTestSetup extends EphemBlockchainTestSetup {
    override implicit lazy val system: ActorSystem = ActorSystem("AdminServiceSpec_Blockchain")
    val testRef: ActorRef = TestProbe().ref
    val peerManager: TestProbe = TestProbe()

    private val nodeKey = crypto.generateKeyPair(secureRandom)
    val nodeStatusRef: AtomicReference[NodeStatus] = new AtomicReference[NodeStatus](
      NodeStatus(
        nodeKey,
        ServerStatus.Listening(new InetSocketAddress(30305)),
        discoveryStatus = ServerStatus.NotListening
      )
    )

    val adminService = new AdminService(
      nodeStatusRef,
      peerManager.ref,
      blockchainReader,
      5.seconds,
      "/tmp/fukuii-test"
    )

    val block0: Block = Block(
      header = BlockHeader(
        parentHash = ByteString(new Array[Byte](32)),
        ommersHash = ByteString(org.bouncycastle.util.encoders.Hex.decode("1dcc4de8dec75d7aab85b567b6ccd41ad312451b948a7413f0a142fd40d49347")),
        beneficiary = ByteString(new Array[Byte](20)),
        stateRoot = ByteString(new Array[Byte](32)),
        transactionsRoot = ByteString(org.bouncycastle.util.encoders.Hex.decode("56e81f171bcc55a6ff8345e692c0f86e5b48e01b996cadc001622fb5e363b421")),
        receiptsRoot = ByteString(org.bouncycastle.util.encoders.Hex.decode("56e81f171bcc55a6ff8345e692c0f86e5b48e01b996cadc001622fb5e363b421")),
        logsBloom = ByteString(new Array[Byte](256)),
        difficulty = 1000,
        number = 0,
        gasLimit = 8000000,
        gasUsed = 0,
        unixTimestamp = 1000,
        extraData = ByteString.empty,
        mixHash = ByteString(new Array[Byte](32)),
        nonce = ByteString(new Array[Byte](8))
      ),
      body = BlockBody.empty
    )

    val block1: Block = Block(
      header = block0.header.copy(
        parentHash = block0.header.hash,
        number = 1,
        unixTimestamp = 1001
      ),
      body = BlockBody.empty
    )

    def storeTestBlocks(): Unit = {
      blockchainWriter.storeBlock(block0).commit()
      blockchainWriter.save(block0, Nil, ChainWeight.totalDifficultyOnly(block0.header.difficulty), true)
      blockchainWriter.storeBlock(block1).commit()
      blockchainWriter.save(block1, Nil, ChainWeight.totalDifficultyOnly(2000), true)
    }
  }
}
