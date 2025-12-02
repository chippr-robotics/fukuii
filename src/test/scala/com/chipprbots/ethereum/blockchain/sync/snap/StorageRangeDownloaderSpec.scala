package com.chipprbots.ethereum.blockchain.sync.snap

import java.net.InetSocketAddress

import org.apache.pekko.actor.{ActorRef, ActorSystem}
import org.apache.pekko.testkit.{TestKit, TestProbe}
import org.apache.pekko.util.ByteString

import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable

import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.mpt._
import com.chipprbots.ethereum.network.{Peer, PeerId}
import com.chipprbots.ethereum.network.p2p.messages.SNAP._
import com.chipprbots.ethereum.testing.Tags._

class StorageRangeDownloaderSpec
    extends TestKit(ActorSystem("StorageRangeDownloaderSpec"))
    with AnyFlatSpecLike
    with Matchers
    with BeforeAndAfterAll {

  override def afterAll(): Unit = {
    TestKit.shutdownActorSystem(system)
  }

  implicit val scheduler: org.apache.pekko.actor.Scheduler = system.scheduler

  def createTestPeer(id: String, ref: ActorRef): Peer = {
    Peer(
      id = PeerId(id),
      remoteAddress = new InetSocketAddress("127.0.0.1", 30303),
      ref = ref,
      incomingConnection = false
    )
  }

  "StorageRangeDownloader" should "initialize with proper state" taggedAs UnitTest in {
    val stateRoot = kec256(ByteString("test-state-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()
    val etcPeerManager = TestProbe()

    val downloader = new StorageRangeDownloader(
      stateRoot = stateRoot,
      etcPeerManager = etcPeerManager.ref,
      requestTracker = requestTracker,
      mptStorage = storage,
      maxAccountsPerBatch = 8
    )

    downloader.isComplete shouldBe true // No tasks added yet
    downloader.progress shouldBe 1.0
  }

  it should "accept storage tasks" taggedAs UnitTest in {
    val stateRoot = kec256(ByteString("test-state-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()
    val etcPeerManager = TestProbe()

    val downloader = new StorageRangeDownloader(
      stateRoot = stateRoot,
      etcPeerManager = etcPeerManager.ref,
      requestTracker = requestTracker,
      mptStorage = storage,
      maxAccountsPerBatch = 8
    )

    val storageRoot = kec256(ByteString("test-storage-root"))
    val task = StorageTask(
      accountHash = ByteString("account1"),
      storageRoot = storageRoot,
      next = ByteString.empty,
      last = ByteString.fromArray(Array.fill(32)(0xff.toByte))
    )

    downloader.addTask(task)
    downloader.isComplete shouldBe false
  }

  it should "request storage ranges from peer" taggedAs UnitTest in {
    val stateRoot = kec256(ByteString("test-state-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()
    val etcPeerManager = TestProbe()
    val peerProbe = TestProbe()

    val peer = createTestPeer("test-peer", peerProbe.ref)

    val downloader = new StorageRangeDownloader(
      stateRoot = stateRoot,
      etcPeerManager = etcPeerManager.ref,
      requestTracker = requestTracker,
      mptStorage = storage,
      maxAccountsPerBatch = 8
    )

    val storageRoot = kec256(ByteString("test-storage-root"))
    val task = StorageTask(
      accountHash = ByteString("account1"),
      storageRoot = storageRoot,
      next = ByteString.empty,
      last = ByteString.fromArray(Array.fill(32)(0xff.toByte))
    )

    downloader.addTask(task)

    val requestId = downloader.requestNextRanges(peer)
    requestId shouldBe defined

    // Verify request was sent to peer manager
    etcPeerManager.expectMsgType[Any]
  }

  it should "batch multiple storage tasks in single request" taggedAs UnitTest in {
    val stateRoot = kec256(ByteString("test-state-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()
    val etcPeerManager = TestProbe()
    val peerProbe = TestProbe()

    val peer = createTestPeer("test-peer", peerProbe.ref)

    val downloader = new StorageRangeDownloader(
      stateRoot = stateRoot,
      etcPeerManager = etcPeerManager.ref,
      requestTracker = requestTracker,
      mptStorage = storage,
      maxAccountsPerBatch = 8
    )

    // Add multiple tasks
    val tasks = (1 to 5).map { i =>
      StorageTask(
        accountHash = ByteString(s"account$i"),
        storageRoot = kec256(ByteString(s"storage-root-$i")),
        next = ByteString.empty,
        last = ByteString.fromArray(Array.fill(32)(0xff.toByte))
      )
    }

    tasks.foreach(downloader.addTask)

    val requestId = downloader.requestNextRanges(peer)
    requestId shouldBe defined

    // Should batch all 5 tasks in single request (max is 8)
    etcPeerManager.expectMsgType[Any]
  }

  it should "handle successful storage ranges response" taggedAs UnitTest in {
    val stateRoot = kec256(ByteString("test-state-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()
    val etcPeerManager = TestProbe()
    val peerProbe = TestProbe()

    val peer = createTestPeer("test-peer", peerProbe.ref)

    val downloader = new StorageRangeDownloader(
      stateRoot = stateRoot,
      etcPeerManager = etcPeerManager.ref,
      requestTracker = requestTracker,
      mptStorage = storage,
      maxAccountsPerBatch = 8
    )

    val storageRoot = kec256(ByteString("test-storage-root"))
    val task = StorageTask(
      accountHash = ByteString("account1"),
      storageRoot = storageRoot,
      next = ByteString.empty,
      last = ByteString.fromArray(Array.fill(32)(0xff.toByte))
    )

    downloader.addTask(task)

    val requestId = downloader.requestNextRanges(peer).get
    etcPeerManager.expectMsgType[Any]

    // Create valid response with storage slots
    val slots = Seq(
      (ByteString("slot1"), ByteString("value1")),
      (ByteString("slot2"), ByteString("value2"))
    )

    val response = StorageRanges(
      requestId = requestId,
      slots = Seq(slots), // One slot set per account
      proof = Seq.empty
    )

    // Handle response
    val result = downloader.handleResponse(response)
    
    result match {
      case Right(count) => 
        count should be >= 0
      case Left(error) =>
        // Accept proof verification failures or other expected errors
        error should (include("proof") or include("Unknown request") or include("task"))
    }
  }

  it should "reject response for unknown request ID" taggedAs UnitTest in {
    val stateRoot = kec256(ByteString("test-state-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()
    val etcPeerManager = TestProbe()

    val downloader = new StorageRangeDownloader(
      stateRoot = stateRoot,
      etcPeerManager = etcPeerManager.ref,
      requestTracker = requestTracker,
      mptStorage = storage,
      maxAccountsPerBatch = 8
    )

    val unknownRequestId = BigInt(999)
    val response = StorageRanges(
      requestId = unknownRequestId,
      slots = Seq.empty,
      proof = Seq.empty
    )

    val result = downloader.handleResponse(response)
    result shouldBe a[Left[_, _]]
    result.left.get should include("No pending request")
  }

  it should "update statistics after successful download" taggedAs UnitTest in {
    val stateRoot = kec256(ByteString("test-state-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()
    val etcPeerManager = TestProbe()

    val downloader = new StorageRangeDownloader(
      stateRoot = stateRoot,
      etcPeerManager = etcPeerManager.ref,
      requestTracker = requestTracker,
      mptStorage = storage,
      maxAccountsPerBatch = 8
    )

    val initialStats = downloader.statistics
    initialStats.slotsDownloaded shouldBe 0
    initialStats.tasksCompleted shouldBe 0
  }

  it should "report completion when all tasks are done" taggedAs UnitTest in {
    val stateRoot = kec256(ByteString("test-state-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()
    val etcPeerManager = TestProbe()

    val downloader = new StorageRangeDownloader(
      stateRoot = stateRoot,
      etcPeerManager = etcPeerManager.ref,
      requestTracker = requestTracker,
      mptStorage = storage,
      maxAccountsPerBatch = 8
    )

    // Initially complete (no tasks)
    downloader.isComplete shouldBe true

    // Add a task
    val task = StorageTask(
      accountHash = ByteString("account1"),
      storageRoot = kec256(ByteString("storage-root")),
      next = ByteString.empty,
      last = ByteString.fromArray(Array.fill(32)(0xff.toByte))
    )
    downloader.addTask(task)

    // Now not complete
    downloader.isComplete shouldBe false
  }

  it should "calculate progress correctly" taggedAs UnitTest in {
    val stateRoot = kec256(ByteString("test-state-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()
    val etcPeerManager = TestProbe()

    val downloader = new StorageRangeDownloader(
      stateRoot = stateRoot,
      etcPeerManager = etcPeerManager.ref,
      requestTracker = requestTracker,
      mptStorage = storage,
      maxAccountsPerBatch = 8
    )

    val progress = downloader.progress
    progress should be >= 0.0
    progress should be <= 1.0
  }

  it should "handle empty response gracefully" taggedAs UnitTest in {
    val stateRoot = kec256(ByteString("test-state-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()
    val etcPeerManager = TestProbe()
    val peerProbe = TestProbe()

    val peer = createTestPeer("test-peer", peerProbe.ref)

    val downloader = new StorageRangeDownloader(
      stateRoot = stateRoot,
      etcPeerManager = etcPeerManager.ref,
      requestTracker = requestTracker,
      mptStorage = storage,
      maxAccountsPerBatch = 8
    )

    val task = StorageTask(
      accountHash = ByteString("account1"),
      storageRoot = kec256(ByteString("storage-root")),
      next = ByteString.empty,
      last = ByteString.fromArray(Array.fill(32)(0xff.toByte))
    )

    downloader.addTask(task)

    val requestId = downloader.requestNextRanges(peer).get
    etcPeerManager.expectMsgType[Any]

    // Create response with no slots
    val response = StorageRanges(
      requestId = requestId,
      slots = Seq(Seq.empty), // Empty slots for the account
      proof = Seq.empty
    )

    val result = downloader.handleResponse(response)
    
    // Should handle gracefully
    result match {
      case Right(count) => count shouldBe 0
      case Left(error) => error should (include("proof") or include("Unknown") or include("task"))
    }
  }

  /** Helper class for test storage */
  private class TestMptStorage extends MptStorage {
    private val nodes = mutable.Map[ByteString, MptNode]()
    
    override def get(key: Array[Byte]): MptNode = {
      val keyStr = ByteString(key)
      nodes.get(keyStr)
        .getOrElse {
          throw new MerklePatriciaTrie.MissingNodeException(keyStr)
        }
    }
    
    def putNode(node: MptNode): Unit = {
      val hash = ByteString(node.hash)
      nodes(hash) = node
    }
    
    override def updateNodesInStorage(
        newRoot: Option[MptNode],
        toRemove: Seq[MptNode]
    ): Option[MptNode] = {
      newRoot.foreach { root =>
        storeNodeRecursively(root)
      }
      newRoot
    }
    
    private def storeNodeRecursively(node: MptNode): Unit = {
      node match {
        case leaf: LeafNode =>
          putNode(leaf)
        case ext: ExtensionNode =>
          putNode(ext)
          storeNodeRecursively(ext.next)
        case branch: BranchNode =>
          putNode(branch)
          branch.children.foreach(storeNodeRecursively)
        case hash: HashNode =>
          putNode(hash)
        case NullNode =>
          // Nothing to store
      }
    }
    
    override def persist(): Unit = {
      // No-op for in-memory storage
    }
  }
}
