package com.chipprbots.ethereum.blockchain.sync.fast

import java.net.InetSocketAddress

import scala.io.Source

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.testkit.TestProbe
import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.BodiesFetcherQueue
import com.chipprbots.ethereum.blockchain.sync.ConcurrentFetch
import com.chipprbots.ethereum.blockchain.sync.DeliveryResult
import com.chipprbots.ethereum.blockchain.sync.HeadersFetcherQueue
import com.chipprbots.ethereum.blockchain.sync.PeerListSupportNg.PeerWithInfo
import com.chipprbots.ethereum.blockchain.sync.PeerRateTracker
import com.chipprbots.ethereum.blockchain.sync.ReceiptsFetcherQueue
import com.chipprbots.ethereum.domain.BlockBody
import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.PeerInfo
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.RemoteStatus
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.PeerId
import com.chipprbots.ethereum.network.p2p.messages.Capability
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.BlockBodies
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.BlockHeaders
import com.chipprbots.ethereum.testing.Tags._

/** Queue-level regression and isolation tests for FastSync's three concurrent fetch pipelines (ARCH-002 / [11b]).
  *
  * Verifies that headersFetcherQueue, bodiesFetcherQueue, and receiptsFetcherQueue operate independently. No actor
  * choreography — all assertions deterministic.
  */
class FastSyncConcurrentPipelineSpec extends AnyFlatSpec with Matchers {

  import Helpers._

  private val fastSyncSourcePath =
    "src/main/scala/com/chipprbots/ethereum/blockchain/sync/fast/FastSync.scala"

  "FastSync source" should "not contain BlockHeadersHandlerName" taggedAs UnitTest in {
    val source = Source.fromFile(fastSyncSourcePath).mkString
    (source should not).include("BlockHeadersHandlerName")
  }

  it should "not contain requestedHeaders field" taggedAs UnitTest in {
    val source = Source.fromFile(fastSyncSourcePath).mkString
    (source should not).include("requestedHeaders")
  }

  "FastSync concurrent pipelines" should "allow a peer to hold headers, bodies, and receipts in-flight simultaneously" taggedAs UnitTest in {
    val tracker = new PeerRateTracker()
    val headersQ = new HeadersFetcherQueue(tracker)
    val bodiesQ = new BodiesFetcherQueue(tracker)
    val receiptsQ = new ReceiptsFetcherQueue(tracker)

    headersQ.enqueue(Seq(BigInt(1000)))
    bodiesQ.enqueue(Seq(hash32))
    receiptsQ.enqueue(Seq(hash32))

    headersQ.reserve(peer1, 1) shouldBe defined
    bodiesQ.reserve(peer1, 1) shouldBe defined
    receiptsQ.reserve(peer1, 1) shouldBe defined

    headersQ.inFlightPeers should contain(peer1.peer.id)
    bodiesQ.inFlightPeers should contain(peer1.peer.id)
    receiptsQ.inFlightPeers should contain(peer1.peer.id)
  }

  it should "assign work from all three queues in a single dispatch tick" taggedAs UnitTest in {
    val tracker = new PeerRateTracker()
    val headersQ = new HeadersFetcherQueue(tracker)
    val bodiesQ = new BodiesFetcherQueue(tracker)
    val receiptsQ = new ReceiptsFetcherQueue(tracker)

    headersQ.enqueue(BigInt(1) to BigInt(10))
    bodiesQ.enqueue((1 to 10).map(i => ByteString(i.toByte)))
    receiptsQ.enqueue((1 to 10).map(i => ByteString(i.toByte)))

    val hAssign =
      ConcurrentFetch.dispatchTo(headersQ, Seq(peer1, peer2), 2000L, "headers", org.apache.pekko.event.NoLogging)
    val bAssign =
      ConcurrentFetch.dispatchTo(bodiesQ, Seq(peer1, peer2), 2000L, "bodies", org.apache.pekko.event.NoLogging)
    val rAssign =
      ConcurrentFetch.dispatchTo(receiptsQ, Seq(peer1, peer2), 2000L, "receipts", org.apache.pekko.event.NoLogging)

    hAssign should not be empty
    bAssign should not be empty
    rAssign should not be empty
    (hAssign.size + bAssign.size + rAssign.size) shouldBe 6
  }

  it should "expire header entries without affecting bodies or receipts in-flight" taggedAs UnitTest in {
    val tracker = new PeerRateTracker()
    val headersQ = new HeadersFetcherQueue(tracker)
    val bodiesQ = new BodiesFetcherQueue(tracker)
    val receiptsQ = new ReceiptsFetcherQueue(tracker)

    headersQ.enqueue(Seq(BigInt(100)))
    bodiesQ.enqueue(Seq(hash32))
    receiptsQ.enqueue(Seq(hash32))

    headersQ.reserve(peer1, 1)
    bodiesQ.reserve(peer1, 1)
    receiptsQ.reserve(peer1, 1)

    headersQ.expireStale(System.currentTimeMillis() + 1_000_000L)

    headersQ.inFlightCount shouldBe 0
    bodiesQ.inFlightCount shouldBe 1
    receiptsQ.inFlightCount shouldBe 1
  }

  it should "clear bodies in-flight without affecting headers or receipts" taggedAs UnitTest in {
    val tracker = new PeerRateTracker()
    val headersQ = new HeadersFetcherQueue(tracker)
    val bodiesQ = new BodiesFetcherQueue(tracker)
    val receiptsQ = new ReceiptsFetcherQueue(tracker)

    headersQ.enqueue(Seq(BigInt(100)))
    bodiesQ.enqueue(Seq(hash32))
    receiptsQ.enqueue(Seq(hash32))

    headersQ.reserve(peer1, 1)
    val bReq = bodiesQ.reserve(peer1, 1).get
    receiptsQ.reserve(peer1, 1)

    val bResp = BlockBodies(bReq.requestId, Seq(BlockBody(Seq.empty, Seq.empty)))
    bodiesQ.deliver(peer1, bResp, 50L) shouldBe DeliveryResult.Delivered(1)

    bodiesQ.inFlightCount shouldBe 0
    headersQ.inFlightCount shouldBe 1
    receiptsQ.inFlightCount shouldBe 1
  }

  // ─── Helpers ──────────────────────────────────────────────────────────────

  private object Helpers {
    implicit val system: ActorSystem = ActorSystem("FastSyncPipeline_System")

    val peer1: PeerWithInfo = mkPeer("peer-1")
    val peer2: PeerWithInfo = mkPeer("peer-2")

    val hash32: ByteString = ByteString(Array.fill(32)(0.toByte))
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
  }
}
