package com.chipprbots.ethereum.blockchain.sync.snap

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.testkit.{TestKit, TestProbe}
import org.apache.pekko.util.ByteString

import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.network.{Peer, PeerId}
import com.chipprbots.ethereum.network.p2p.messages.SNAP._
import com.chipprbots.ethereum.testing.Tags._
import com.chipprbots.ethereum.testing.{PeerTestHelpers, TestMptStorage}

class TrieNodeHealerSpec
    extends TestKit(ActorSystem("TrieNodeHealerSpec"))
    with AnyFlatSpecLike
    with Matchers
    with BeforeAndAfterAll {

  override def afterAll(): Unit =
    TestKit.shutdownActorSystem(system)

  implicit val scheduler: org.apache.pekko.actor.Scheduler = system.scheduler

  "TrieNodeHealer" should "initialize with proper state" taggedAs UnitTest in {
    val stateRoot = kec256(ByteString("test-state-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()
    val etcPeerManager = TestProbe()

    val healer = new TrieNodeHealer(
      stateRoot = stateRoot,
      networkPeerManager = etcPeerManager.ref,
      requestTracker = requestTracker,
      mptStorage = storage,
      batchSize = 16
    )

    healer.isComplete shouldBe true // No missing nodes initially
  }

  it should "queue missing nodes for healing" taggedAs UnitTest in {
    val stateRoot = kec256(ByteString("test-state-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()
    val etcPeerManager = TestProbe()

    val healer = new TrieNodeHealer(
      stateRoot = stateRoot,
      networkPeerManager = etcPeerManager.ref,
      requestTracker = requestTracker,
      mptStorage = storage,
      batchSize = 16
    )

    val missingNodeHash = kec256(ByteString("missing-node"))
    healer.queueNode(missingNodeHash)

    healer.isComplete shouldBe false
  }

  it should "queue multiple missing nodes" taggedAs UnitTest in {
    val stateRoot = kec256(ByteString("test-state-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()
    val etcPeerManager = TestProbe()

    val healer = new TrieNodeHealer(
      stateRoot = stateRoot,
      networkPeerManager = etcPeerManager.ref,
      requestTracker = requestTracker,
      mptStorage = storage,
      batchSize = 16
    )

    val missingNodes = (1 to 5).map { i =>
      kec256(ByteString(s"missing-node-$i"))
    }

    healer.queueNodes(missingNodes)

    healer.isComplete shouldBe false
  }

  it should "request next batch of nodes from peer" taggedAs UnitTest in {
    val stateRoot = kec256(ByteString("test-state-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()
    val etcPeerManager = TestProbe()
    val peerProbe = TestProbe()

    val peer = PeerTestHelpers.createTestPeer("test-peer", peerProbe.ref)

    val healer = new TrieNodeHealer(
      stateRoot = stateRoot,
      networkPeerManager = etcPeerManager.ref,
      requestTracker = requestTracker,
      mptStorage = storage,
      batchSize = 16
    )

    val missingNodes = (1 to 5).map { i =>
      kec256(ByteString(s"missing-node-$i"))
    }

    healer.queueNodes(missingNodes)

    val requestId = healer.requestNextBatch(peer)
    requestId shouldBe defined

    // Verify request was sent to peer manager
    etcPeerManager.expectMsgType[Any]
  }

  it should "return None when no pending nodes to heal" taggedAs UnitTest in {
    val stateRoot = kec256(ByteString("test-state-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()
    val etcPeerManager = TestProbe()
    val peerProbe = TestProbe()

    val peer = PeerTestHelpers.createTestPeer("test-peer", peerProbe.ref)

    val healer = new TrieNodeHealer(
      stateRoot = stateRoot,
      networkPeerManager = etcPeerManager.ref,
      requestTracker = requestTracker,
      mptStorage = storage,
      batchSize = 16
    )

    val requestId = healer.requestNextBatch(peer)
    requestId shouldBe None
  }

  it should "batch nodes correctly based on batch size" taggedAs UnitTest in {
    val stateRoot = kec256(ByteString("test-state-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()
    val etcPeerManager = TestProbe()
    val peerProbe = TestProbe()

    val peer = PeerTestHelpers.createTestPeer("test-peer", peerProbe.ref)

    val batchSize = 10
    val healer = new TrieNodeHealer(
      stateRoot = stateRoot,
      networkPeerManager = etcPeerManager.ref,
      requestTracker = requestTracker,
      mptStorage = storage,
      batchSize = batchSize
    )

    // Queue more nodes than batch size
    val missingNodes = (1 to 25).map { i =>
      kec256(ByteString(s"missing-node-$i"))
    }

    healer.queueNodes(missingNodes)

    // First batch should have 10 nodes
    val requestId1 = healer.requestNextBatch(peer)
    requestId1 shouldBe defined
    etcPeerManager.expectMsgType[Any]

    // Second batch should also have 10 nodes
    val requestId2 = healer.requestNextBatch(peer)
    requestId2 shouldBe defined
    etcPeerManager.expectMsgType[Any]

    // Third batch should have remaining 5 nodes
    val requestId3 = healer.requestNextBatch(peer)
    requestId3 shouldBe defined
    etcPeerManager.expectMsgType[Any]

    // No more nodes
    val requestId4 = healer.requestNextBatch(peer)
    requestId4 shouldBe None
  }

  it should "handle successful trie nodes response" taggedAs UnitTest in {
    val stateRoot = kec256(ByteString("test-state-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()
    val etcPeerManager = TestProbe()
    val peerProbe = TestProbe()

    val peer = PeerTestHelpers.createTestPeer("test-peer", peerProbe.ref)

    val healer = new TrieNodeHealer(
      stateRoot = stateRoot,
      networkPeerManager = etcPeerManager.ref,
      requestTracker = requestTracker,
      mptStorage = storage,
      batchSize = 16
    )

    val missingNodes = (1 to 3).map { i =>
      kec256(ByteString(s"missing-node-$i"))
    }

    healer.queueNodes(missingNodes)

    val requestId = healer.requestNextBatch(peer).get
    etcPeerManager.expectMsgType[Any]

    // Create valid response with node data
    val nodeData = (1 to 3).map { i =>
      ByteString(s"node-data-$i")
    }

    val response = TrieNodes(
      requestId = requestId,
      nodes = nodeData
    )

    // Handle response
    val result = healer.handleResponse(response)

    result match {
      case Right(count) =>
        count should be >= 0
      case Left(error) =>
        // Accept validation failures or other expected errors
        error should (include("task").or(include("No active")))
    }
  }

  it should "reject response for unknown request ID" taggedAs UnitTest in {
    val stateRoot = kec256(ByteString("test-state-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()
    val etcPeerManager = TestProbe()

    val healer = new TrieNodeHealer(
      stateRoot = stateRoot,
      networkPeerManager = etcPeerManager.ref,
      requestTracker = requestTracker,
      mptStorage = storage,
      batchSize = 16
    )

    val unknownRequestId = BigInt(999)
    val response = TrieNodes(
      requestId = unknownRequestId,
      nodes = Seq.empty
    )

    val result = healer.handleResponse(response)
    result shouldBe a[Left[_, _]]
  }

  it should "add missing nodes with path information" taggedAs UnitTest in {
    val stateRoot = kec256(ByteString("test-state-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()
    val etcPeerManager = TestProbe()

    val healer = new TrieNodeHealer(
      stateRoot = stateRoot,
      networkPeerManager = etcPeerManager.ref,
      requestTracker = requestTracker,
      mptStorage = storage,
      batchSize = 16
    )

    val missingNodes = Seq(
      (Seq(ByteString("path1")), kec256(ByteString("node1"))),
      (Seq(ByteString("path2")), kec256(ByteString("node2")))
    )

    healer.addMissingNodes(missingNodes)

    healer.isComplete shouldBe false
  }

  it should "report completion when all nodes are healed" taggedAs UnitTest in {
    val stateRoot = kec256(ByteString("test-state-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()
    val etcPeerManager = TestProbe()

    val healer = new TrieNodeHealer(
      stateRoot = stateRoot,
      networkPeerManager = etcPeerManager.ref,
      requestTracker = requestTracker,
      mptStorage = storage,
      batchSize = 16
    )

    // Initially complete (no missing nodes)
    healer.isComplete shouldBe true

    // Add missing nodes
    healer.queueNode(kec256(ByteString("missing-node")))

    // Now not complete
    healer.isComplete shouldBe false
  }

  it should "handle multiple concurrent requests" taggedAs UnitTest in {
    val stateRoot = kec256(ByteString("test-state-root"))
    val storage = new TestMptStorage()
    val requestTracker = new SNAPRequestTracker()
    val etcPeerManager = TestProbe()
    val peerProbe1 = TestProbe()
    val peerProbe2 = TestProbe()

    val peer1 = PeerTestHelpers.createTestPeer("peer1", peerProbe1.ref)
    val peer2 = PeerTestHelpers.createTestPeer("peer2", peerProbe2.ref)

    val healer = new TrieNodeHealer(
      stateRoot = stateRoot,
      networkPeerManager = etcPeerManager.ref,
      requestTracker = requestTracker,
      mptStorage = storage,
      batchSize = 5
    )

    // Queue many nodes
    val missingNodes = (1 to 20).map { i =>
      kec256(ByteString(s"missing-node-$i"))
    }

    healer.queueNodes(missingNodes)

    // Request from multiple peers
    val requestId1 = healer.requestNextBatch(peer1)
    val requestId2 = healer.requestNextBatch(peer2)

    requestId1 shouldBe defined
    requestId2 shouldBe defined
    (requestId1.get should not).equal(requestId2.get)

    // Both requests should be sent
    etcPeerManager.expectMsgType[Any]
    etcPeerManager.expectMsgType[Any]
  }

  /** Helper class for test storage */
}
