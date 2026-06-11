package com.chipprbots.ethereum.blockchain.sync

import java.net.InetSocketAddress

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.testkit.TestProbe
import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.PeerListSupportNg.PeerWithInfo
import com.chipprbots.ethereum.domain.BlockBody
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.PeerInfo
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.RemoteStatus
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.PeerId
import com.chipprbots.ethereum.network.p2p.messages.Capability
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.BlockBodies
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.BlockHeaders
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.Receipts68
import com.chipprbots.ethereum.rlp.RLPList
import com.chipprbots.ethereum.testing.Tags._

/** Unit tests for the ConcurrentFetch infrastructure (ARCH-002).
  *
  * Tests queue reserve/unreserve/deliver lifecycle, expireStale, and the dispatchTo() dispatch loop. No actor
  * choreography — all assertions are deterministic.
  */
class ConcurrentFetchSpec extends AnyFlatSpec with Matchers {

  import Helpers._

  // ─── HeadersFetcherQueue ──────────────────────────────────────────────────

  "HeadersFetcherQueue" should "start with zero pending items" taggedAs UnitTest in {
    val q = new HeadersFetcherQueue(new PeerRateTracker())
    q.pending shouldBe 0
    q.inFlightCount shouldBe 0
    q.inFlightPeers shouldBe empty
  }

  it should "report pending after enqueue" taggedAs UnitTest in {
    val q = new HeadersFetcherQueue(new PeerRateTracker())
    q.enqueue(Seq(100, 101, 102))
    q.pending shouldBe 3
  }

  it should "reserve blocks from pending and record in-flight" taggedAs UnitTest in {
    val q = new HeadersFetcherQueue(new PeerRateTracker())
    q.enqueue(BigInt(100) to BigInt(104))

    val result = q.reserve(peer1, 3)
    result shouldBe defined

    val req = result.get
    req.block shouldBe Left(BigInt(100))
    req.maxHeaders shouldBe 3
    req.skip shouldBe 0
    req.reverse shouldBe false

    q.pending shouldBe 2
    q.inFlightPeers should contain(peer1.peer.id)
    q.inFlightCount shouldBe 1
  }

  it should "return None when queue is empty" taggedAs UnitTest in {
    val q = new HeadersFetcherQueue(new PeerRateTracker())
    q.reserve(peer1, 5) shouldBe None
    q.inFlightCount shouldBe 0
  }

  it should "clamp reserve count to MaxHeadersPerRequest" taggedAs UnitTest in {
    val q = new HeadersFetcherQueue(new PeerRateTracker())
    q.enqueue(BigInt(1) to BigInt(2000))
    val req = q.reserve(peer1, 9999).get
    req.maxHeaders shouldBe HeadersFetcherQueue.MaxHeadersPerRequest
    q.pending shouldBe 2000 - HeadersFetcherQueue.MaxHeadersPerRequest
  }

  it should "return blocks to the front of the queue on unreserve" taggedAs UnitTest in {
    val q = new HeadersFetcherQueue(new PeerRateTracker())
    q.enqueue(Seq(100, 101, 102, 103, 104))

    q.reserve(peer1, 3)
    q.pending shouldBe 2

    q.unreserve(peer1.peer.id)
    q.pending shouldBe 5
    q.inFlightPeers should not contain peer1.peer.id

    // The returned blocks should be at the front — next reserve gets 100 again
    val next = q.reserve(peer2, 3).get
    next.block shouldBe Left(BigInt(100))
  }

  it should "deliver a valid response and update the rate tracker" taggedAs UnitTest in {
    val tracker = new PeerRateTracker()
    val q = new HeadersFetcherQueue(tracker)
    q.enqueue(Seq(100, 101, 102))

    val req = q.reserve(peer1, 3).get
    val resp = BlockHeaders(req.requestId, Seq.fill(3)(stubHeader))

    q.deliver(peer1, resp, elapsedMs = 50L) shouldBe DeliveryResult.Delivered(3)
    q.inFlightCount shouldBe 0
  }

  it should "return Duplicate when no in-flight request exists for the peer" taggedAs UnitTest in {
    val q = new HeadersFetcherQueue(new PeerRateTracker())
    val bs = BlockHeaders(requestId = 1, headers = Seq.empty)
    q.deliver(peer1, bs, 50L) shouldBe DeliveryResult.Duplicate
  }

  it should "return Invalid on requestId mismatch" taggedAs UnitTest in {
    val q = new HeadersFetcherQueue(new PeerRateTracker())
    q.enqueue(Seq(100))
    val req = q.reserve(peer1, 1).get

    val wrongId = req.requestId + 999
    val resp = BlockHeaders(wrongId, Seq.empty)
    q.deliver(peer1, resp, 50L) match {
      case DeliveryResult.Invalid(_) => succeed
      case other                     => fail(s"Expected Invalid, got $other")
    }
  }

  it should "expire stale in-flight requests and return items to the queue" taggedAs UnitTest in {
    val q = new HeadersFetcherQueue(new PeerRateTracker())
    q.enqueue(Seq(100, 101, 102))
    q.reserve(peer1, 3)
    q.inFlightCount shouldBe 1

    val past = System.currentTimeMillis() + 1_000_000L
    val expired = q.expireStale(past)

    expired should have size 1
    expired.head._1 shouldBe peer1.peer.id
    q.inFlightCount shouldBe 0
    q.pending shouldBe 3
  }

  // ─── BodiesFetcherQueue ────────────────────────────────────────────────────

  "BodiesFetcherQueue" should "reserve hashes and record in-flight" taggedAs UnitTest in {
    val q = new BodiesFetcherQueue(new PeerRateTracker())
    val hashes = (1 to 5).map(i => ByteString(i.toByte))
    q.enqueue(hashes)
    q.pending shouldBe 5

    val req = q.reserve(peer1, 3).get
    req.hashes should have size 3
    req.hashes.head shouldBe hashes.head

    q.pending shouldBe 2
    q.inFlightPeers should contain(peer1.peer.id)
  }

  it should "return hashes to the front on unreserve" taggedAs UnitTest in {
    val q = new BodiesFetcherQueue(new PeerRateTracker())
    val hashes = (1 to 4).map(i => ByteString(i.toByte))
    q.enqueue(hashes)
    q.reserve(peer1, 2)
    q.pending shouldBe 2

    q.unreserve(peer1.peer.id)
    q.pending shouldBe 4

    val next = q.reserve(peer2, 2).get
    next.hashes.head shouldBe hashes.head
  }

  it should "deliver a valid response" taggedAs UnitTest in {
    val q = new BodiesFetcherQueue(new PeerRateTracker())
    val hashes = (1 to 3).map(i => ByteString(i.toByte))
    q.enqueue(hashes)
    val req = q.reserve(peer1, 3).get
    val resp = BlockBodies(req.requestId, Seq.fill(3)(BlockBody(Seq.empty, Seq.empty)))

    q.deliver(peer1, resp, 80L) shouldBe DeliveryResult.Delivered(3)
    q.inFlightCount shouldBe 0
  }

  it should "clamp reserve to MaxBodiesPerRequest" taggedAs UnitTest in {
    val q = new BodiesFetcherQueue(new PeerRateTracker())
    val hashes = (1 to 200).map(i => ByteString(i.toByte))
    q.enqueue(hashes)
    val req = q.reserve(peer1, 999).get
    req.hashes.size shouldBe BodiesFetcherQueue.MaxBodiesPerRequest
  }

  // ─── ReceiptsFetcherQueue ──────────────────────────────────────────────────

  "ReceiptsFetcherQueue" should "reserve hashes and record in-flight" taggedAs UnitTest in {
    val q = new ReceiptsFetcherQueue(new PeerRateTracker())
    val hashes = (1 to 5).map(i => ByteString(i.toByte))
    q.enqueue(hashes)
    val req = q.reserve(peer1, 3).get
    req.blockHashes should have size 3
    q.pending shouldBe 2
    q.inFlightPeers should contain(peer1.peer.id)
  }

  it should "deliver a valid Receipts68 response" taggedAs UnitTest in {
    val q = new ReceiptsFetcherQueue(new PeerRateTracker())
    val hashes = (1 to 2).map(i => ByteString(i.toByte))
    q.enqueue(hashes)
    val req = q.reserve(peer1, 2).get
    val resp = Receipts68(req.requestId, RLPList(RLPList(), RLPList()))

    q.deliver(peer1, resp, 60L) shouldBe DeliveryResult.Delivered(2)
    q.inFlightCount shouldBe 0
  }

  it should "clamp reserve to MaxReceiptsPerRequest" taggedAs UnitTest in {
    val q = new ReceiptsFetcherQueue(new PeerRateTracker())
    val hashes = (1 to 300).map(i => ByteString(i.toByte))
    q.enqueue(hashes)
    val req = q.reserve(peer1, 9999).get
    req.blockHashes.size shouldBe ReceiptsFetcherQueue.MaxReceiptsPerRequest
  }

  // ─── ConcurrentFetch.dispatchTo() ─────────────────────────────────────────

  "ConcurrentFetch.dispatchTo" should "return empty when queue is empty" taggedAs UnitTest in {
    val q = new HeadersFetcherQueue(new PeerRateTracker())
    val result = ConcurrentFetch.dispatchTo(q, Seq(peer1, peer2), 2000L, "headers", org.apache.pekko.event.NoLogging)
    result shouldBe empty
  }

  it should "return empty when no idle peers" taggedAs UnitTest in {
    val q = new HeadersFetcherQueue(new PeerRateTracker())
    q.enqueue(Seq(100, 101, 102))
    q.reserve(peer1, 1)

    val result = ConcurrentFetch.dispatchTo(q, Seq(peer1), 2000L, "headers", org.apache.pekko.event.NoLogging)
    result shouldBe empty
  }

  it should "assign work to all idle peers" taggedAs UnitTest in {
    val q = new HeadersFetcherQueue(new PeerRateTracker())
    q.enqueue(BigInt(100) to BigInt(110))

    val result = ConcurrentFetch.dispatchTo(q, Seq(peer1, peer2), 2000L, "headers", org.apache.pekko.event.NoLogging)

    result should have size 2
    val assignedPeers = result.map(_._1.peer.id).toSet
    assignedPeers should contain(peer1.peer.id)
    assignedPeers should contain(peer2.peer.id)
    q.inFlightCount shouldBe 2
  }

  it should "skip peers already in-flight" taggedAs UnitTest in {
    val q = new HeadersFetcherQueue(new PeerRateTracker())
    q.enqueue(BigInt(100) to BigInt(120))
    q.reserve(peer1, 3)

    val result = ConcurrentFetch.dispatchTo(q, Seq(peer1, peer2), 2000L, "headers", org.apache.pekko.event.NoLogging)

    result should have size 1
    result.head._1.peer.id shouldBe peer2.peer.id
  }

  it should "stop assigning when queue is drained" taggedAs UnitTest in {
    val q = new HeadersFetcherQueue(new PeerRateTracker())
    q.enqueue(Seq(100))

    val result =
      ConcurrentFetch.dispatchTo(q, Seq(peer1, peer2, peer3), 2000L, "headers", org.apache.pekko.event.NoLogging)

    result should have size 1
    q.pending shouldBe 0
  }

  it should "assign larger batch to peer with prior measurements than to cold peer" taggedAs UnitTest in {
    val tracker = new PeerRateTracker()
    tracker.addPeer(peer1.peer.id.value)
    tracker.addPeer(peer2.peer.id.value)
    // Warm peer1: 1 item in 50ms → measured=20/s, ema=2.0, capacity(targetRtt=2000ms)=5
    val warmQ = new HeadersFetcherQueue(tracker)
    warmQ.enqueue(Seq(BigInt(1)))
    val warmReq = warmQ.reserve(peer1, 1).get
    val warmResp = BlockHeaders(warmReq.requestId, Seq.fill(1)(stubHeader))
    warmQ.deliver(peer1, warmResp, 50L)

    val q2 = new HeadersFetcherQueue(tracker)
    q2.enqueue(BigInt(100) to BigInt(200))
    val result = ConcurrentFetch.dispatchTo(q2, Seq(peer1, peer2), 2000L, "headers", org.apache.pekko.event.NoLogging)

    result should have size 2
    val p1batch = result.find(_._1.peer.id == peer1.peer.id).get._2
    val p2batch = result.find(_._1.peer.id == peer2.peer.id).get._2
    p1batch.maxHeaders should be > p2batch.maxHeaders
  }

  // ─── HeadersFetcherQueue (additional tests) ───────────────────────────────

  "HeadersFetcherQueue" should "increase peer capacity estimate after successful deliver" taggedAs UnitTest in {
    val tracker = new PeerRateTracker()
    tracker.addPeer(peer1.peer.id.value)
    tracker.capacity(peer1.peer.id.value, PeerRateTracker.MsgGetBlockHeaders, 2000L) shouldBe 1

    val q = new HeadersFetcherQueue(tracker)
    q.enqueue(Seq(BigInt(100), BigInt(101), BigInt(102)))
    val req = q.reserve(peer1, 3).get
    val resp = BlockHeaders(req.requestId, Seq.fill(3)(stubHeader))
    q.deliver(peer1, resp, 50L) shouldBe DeliveryResult.Delivered(3)
    // 3 items in 50ms → measured=60/s, ema=6.0, capacity=(1+1.01×6.0×2.0).toInt=13
    tracker.capacity(peer1.peer.id.value, PeerRateTracker.MsgGetBlockHeaders, 2000L) shouldBe 13
  }

  it should "make items reusable after expireStale removes in-flight entry" taggedAs UnitTest in {
    val q = new HeadersFetcherQueue(new PeerRateTracker())
    q.enqueue(Seq(BigInt(100), BigInt(101), BigInt(102)))
    q.reserve(peer1, 3)
    q.pending shouldBe 0

    val expired = q.expireStale(System.currentTimeMillis() + 1_000_000L)
    expired should have size 1
    q.inFlightCount shouldBe 0
    q.pending shouldBe 3

    val next = q.reserve(peer2, 3)
    next shouldBe defined
    next.get.block shouldBe Left(BigInt(100))
  }

  // ─── Helpers ──────────────────────────────────────────────────────────────

  private object Helpers {
    implicit val system: ActorSystem = ActorSystem("ConcurrentFetch_System")

    val peer1: PeerWithInfo = mkPeer("peer-1")
    val peer2: PeerWithInfo = mkPeer("peer-2")
    val peer3: PeerWithInfo = mkPeer("peer-3")

    private val hash32 = ByteString(Array.fill(32)(0.toByte))
    private val bloom256 = ByteString(Array.fill(256)(0.toByte))
    private val beneficiary = ByteString(Array.fill(20)(0.toByte))
    private val nonce8 = ByteString(Array.fill(8)(0.toByte))

    private val remoteStatus = RemoteStatus(
      capability = Capability.ETH69,
      networkId = 1L,
      chainWeight = ChainWeight.zero,
      bestHash = hash32,
      genesisHash = hash32
    )

    def mkPeer(id: String): PeerWithInfo = {
      val peer = Peer(PeerId(id), new InetSocketAddress("127.0.0.1", 30303), TestProbe().ref, false)
      val peerInfo = PeerInfo(
        remoteStatus,
        ChainWeight(BigInt(1000)),
        forkAccepted = true,
        maxBlockNumber = 1000,
        bestBlockHash = hash32
      )
      PeerWithInfo(peer, peerInfo)
    }

    val stubHeader: BlockHeader = BlockHeader(
      parentHash = hash32,
      ommersHash = hash32,
      beneficiary = beneficiary,
      stateRoot = hash32,
      transactionsRoot = hash32,
      receiptsRoot = hash32,
      logsBloom = bloom256,
      difficulty = 1,
      number = 0,
      gasLimit = 1000000,
      gasUsed = 0,
      unixTimestamp = 0,
      extraData = ByteString.empty,
      mixHash = hash32,
      nonce = nonce8
    )
  }
}
