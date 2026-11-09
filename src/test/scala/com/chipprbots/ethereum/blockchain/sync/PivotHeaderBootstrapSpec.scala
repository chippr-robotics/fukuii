package com.chipprbots.ethereum.blockchain.sync

import java.net.InetSocketAddress

import org.apache.pekko.actor.ActorRef
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.testkit.TestProbe

import scala.concurrent.duration.*

import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.blockchain.sync.Blacklist.BlacklistReason
import com.chipprbots.ethereum.consensus.validators.BlockHeaderError
import com.chipprbots.ethereum.consensus.validators.BlockHeaderValid
import com.chipprbots.ethereum.db.dataSource.DataSourceBatchUpdate
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockNumber
import com.chipprbots.ethereum.domain.BlockchainWriter
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.PeerId
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets
import com.chipprbots.ethereum.testing.Tags.*

class PivotHeaderBootstrapSpec extends ScalaTestWithActorTestKit() with AnyFlatSpecLike with Matchers:

  implicit private val classicSystem: org.apache.pekko.actor.ActorSystem = system.classicSystem

  val targetBlock: BigInt = 1000
  val correctHeader: BlockHeader = Fixtures.Blocks.Block3125369.header.copy(number = BlockNumber(targetBlock))
  val wrongHeader: BlockHeader = Fixtures.Blocks.Block3125369.header.copy(number = BlockNumber(999))

  val ds: EphemDataSource = EphemDataSource()
  val noopBatch: DataSourceBatchUpdate = DataSourceBatchUpdate(ds, Array.empty)

  val noopWriter: BlockchainWriter = new BlockchainWriter(null, null, null, null, null, null, null, null):
    override def storeBlockHeader(blockHeader: BlockHeader): DataSourceBatchUpdate = noopBatch

  val throwingWriter: BlockchainWriter = new BlockchainWriter(null, null, null, null, null, null, null, null):
    override def storeBlockHeader(blockHeader: BlockHeader): DataSourceBatchUpdate =
      throw new RuntimeException("storage error")

  val testPeer: Peer = Peer(PeerId("test-peer"), new InetSocketAddress("127.0.0.1", 9999), TestProbe().ref, false)
  val testPeer2: Peer = Peer(PeerId("test-peer-2"), new InetSocketAddress("127.0.0.1", 9998), TestProbe().ref, false)

  /** Spawns a (Typed) PivotHeaderBootstrap whose `replyTo` is `parentProbe`, so Completed/Failed land on the probe.
    * Returns the Classic-adapted ref so existing assertions still work.
    */
  def mkBootstrap(
      peersClientProbe: TestProbe,
      parentProbe: TestProbe,
      writer: BlockchainWriter = noopWriter,
      maxAttempts: Int = 1,
      retryDelay: FiniteDuration = 50.millis,
      waitForPeerDelay: FiniteDuration = 50.millis,
      preferSnapPeers: Boolean = false,
      headerCheck: BlockHeader => Either[BlockHeaderError, BlockHeaderValid] = PivotHeaderBootstrap.AcceptAll
  ): ActorRef =
    testKit
      .spawn(
        PivotHeaderBootstrap(
          peersClient = peersClientProbe.ref.toTyped[PeersClient.Command],
          blockchainWriter = writer,
          targetBlock = targetBlock,
          replyTo = parentProbe.ref,
          syncConfig = null,
          maxAttempts = maxAttempts,
          initialRetryDelay = retryDelay,
          maxRetryDelay = retryDelay,
          waitForPeerDelay = waitForPeerDelay,
          preferSnapPeers = preferSnapPeers,
          headerCheck = headerCheck
        )
      )
      .toClassic

  "PivotHeaderBootstrap" should "send Completed to parent when peer returns the correct header" taggedAs (
    UnitTest,
    SyncTest
  ) in {
    val peersClient = TestProbe()
    val parent = TestProbe()
    mkBootstrap(peersClient, parent)

    peersClient.expectMsgType[PeersClient.Request[ETHPackets.GetBlockHeaders]](3.seconds).replyTo ! PeersClient
      .Response(testPeer, ETHPackets.BlockHeaders(BigInt(0), Seq(correctHeader)))

    parent.expectMsg(3.seconds, PivotHeaderBootstrap.Completed(targetBlock, correctHeader))
  }

  it should "send Failed after maxAttempts when peer returns a wrong-number header" taggedAs (UnitTest, SyncTest) in {
    val peersClient = TestProbe()
    val parent = TestProbe()
    mkBootstrap(peersClient, parent)

    peersClient.expectMsgType[PeersClient.Request[ETHPackets.GetBlockHeaders]](3.seconds).replyTo ! PeersClient
      .Response(testPeer, ETHPackets.BlockHeaders(BigInt(0), Seq(wrongHeader)))

    parent.expectMsgType[PivotHeaderBootstrap.Failed](3.seconds)
  }

  it should "not send Failed on empty headers (WaitForPeer issued, no attempt consumed)" taggedAs (
    UnitTest,
    SyncTest
  ) in {
    val peersClient = TestProbe()
    val parent = TestProbe()
    mkBootstrap(peersClient, parent, waitForPeerDelay = 50.millis)

    peersClient.expectMsgType[PeersClient.Request[ETHPackets.GetBlockHeaders]](3.seconds).replyTo ! PeersClient
      .Response(testPeer, ETHPackets.BlockHeaders(BigInt(0), Seq.empty))

    // Empty headers → WaitForPeer; no attempt consumed → Failed must NOT arrive yet
    parent.expectNoMessage(200.millis)
  }

  it should "send Failed after maxAttempts on RequestFailed" taggedAs (UnitTest, SyncTest) in {
    val peersClient = TestProbe()
    val parent = TestProbe()
    mkBootstrap(peersClient, parent)

    peersClient.expectMsgType[PeersClient.Request[ETHPackets.GetBlockHeaders]](3.seconds).replyTo ! PeersClient
      .RequestFailed(testPeer, BlacklistReason.RegularSyncRequestFailed("timeout"))

    parent.expectMsgType[PivotHeaderBootstrap.Failed](3.seconds)
  }

  it should "fall back to BestPeerWithMinBlock and complete when preferSnapPeers is set but no SNAP peer available" taggedAs (
    UnitTest,
    SyncTest
  ) in {
    val peersClient = TestProbe()
    val parent = TestProbe()
    mkBootstrap(peersClient, parent, preferSnapPeers = true)

    // First request uses BestSnapPeerWithMinBlockExcluding(target, {}) — no SNAP peer available at all
    val req1 = peersClient.expectMsgType[PeersClient.Request[ETHPackets.GetBlockHeaders]](3.seconds)
    req1.peerSelector shouldBe PeersClient.BestSnapPeerWithMinBlockExcluding(targetBlock, Set.empty)
    req1.replyTo ! PeersClient.NoSuitablePeer

    // Fallback request uses BestPeerWithMinBlockExcluding — peer responds with the correct header
    peersClient.expectMsgType[PeersClient.Request[ETHPackets.GetBlockHeaders]](3.seconds).replyTo ! PeersClient
      .Response(testPeer, ETHPackets.BlockHeaders(BigInt(0), Seq(correctHeader)))

    parent.expectMsg(3.seconds, PivotHeaderBootstrap.Completed(targetBlock, correctHeader))
  }

  it should "exclude a SNAP peer that returned empty headers and fall through to the next peer (Besu ETH69 monopoly fix)" taggedAs (
    UnitTest,
    SyncTest
  ) in {
    val peersClient = TestProbe()
    val parent = TestProbe()
    // maxAttempts=3 so budget isn't consumed by the empty-header WaitForPeer path
    mkBootstrap(peersClient, parent, preferSnapPeers = true, maxAttempts = 3, waitForPeerDelay = 50.millis)

    // Attempt 1: BestSnapPeerWithMinBlockExcluding(target, {}) picks testPeer (e.g. Besu with synthetic high TD).
    // testPeer returns empty headers → testPeer added to triedPeers, WaitForPeer issued.
    val req1 = peersClient.expectMsgType[PeersClient.Request[ETHPackets.GetBlockHeaders]](3.seconds)
    req1.peerSelector shouldBe PeersClient.BestSnapPeerWithMinBlockExcluding(targetBlock, Set.empty)
    req1.replyTo ! PeersClient.Response(testPeer, ETHPackets.BlockHeaders(BigInt(0), Seq.empty))

    // WaitForPeer fires → retry: BestSnapPeerWithMinBlockExcluding(target, {testPeer}) → no SNAP peers left → NoSuitablePeer.
    // flatMap catches NoSuitablePeer and issues fallback: BestPeerWithMinBlockExcluding(target, {testPeer}).
    val req2 = peersClient.expectMsgType[PeersClient.Request[ETHPackets.GetBlockHeaders]](3.seconds)
    req2.peerSelector shouldBe PeersClient.BestSnapPeerWithMinBlockExcluding(targetBlock, Set(testPeer.id))
    req2.replyTo ! PeersClient.NoSuitablePeer

    // Fallback: testPeer2 (e.g. core-geth, non-SNAP or lower-TD SNAP) returns the correct header.
    peersClient.expectMsgType[PeersClient.Request[ETHPackets.GetBlockHeaders]](3.seconds).replyTo ! PeersClient
      .Response(testPeer2, ETHPackets.BlockHeaders(BigInt(0), Seq(correctHeader)))

    parent.expectMsg(3.seconds, PivotHeaderBootstrap.Completed(targetBlock, correctHeader))
  }

  it should "send Failed to parent when blockchainWriter throws during storeBlockHeader" taggedAs (
    UnitTest,
    SyncTest
  ) in {
    val peersClient = TestProbe()
    val parent = TestProbe()
    mkBootstrap(peersClient, parent, writer = throwingWriter)

    peersClient.expectMsgType[PeersClient.Request[ETHPackets.GetBlockHeaders]](3.seconds).replyTo ! PeersClient
      .Response(testPeer, ETHPackets.BlockHeaders(BigInt(0), Seq(correctHeader)))

    parent.expectMsgType[PivotHeaderBootstrap.Failed](3.seconds)
  }

  it should "try a different peer on the second attempt after the first returns a wrong header" taggedAs (
    UnitTest,
    SyncTest
  ) in {
    val peersClient = TestProbe()
    val parent = TestProbe()
    mkBootstrap(peersClient, parent, maxAttempts = 2)

    // Attempt 1 — testPeer returns wrong header (added to triedPeers)
    peersClient.expectMsgType[PeersClient.Request[ETHPackets.GetBlockHeaders]](3.seconds).replyTo ! PeersClient
      .Response(testPeer, ETHPackets.BlockHeaders(BigInt(0), Seq(wrongHeader)))

    // Attempt 2 — testPeer2 (testPeer excluded by BestPeerWithMinBlockExcluding) returns correct header
    peersClient.expectMsgType[PeersClient.Request[ETHPackets.GetBlockHeaders]](3.seconds).replyTo ! PeersClient
      .Response(testPeer2, ETHPackets.BlockHeaders(BigInt(0), Seq(correctHeader)))

    parent.expectMsg(3.seconds, PivotHeaderBootstrap.Completed(targetBlock, correctHeader))
  }

  it should "schedule a WaitForPeer delay without consuming an attempt when pool returns NoSuitablePeer" taggedAs (
    UnitTest,
    SyncTest
  ) in {
    val peersClient = TestProbe()
    val parent = TestProbe()
    // maxAttempts=1: if WaitForPeer incorrectly consumed an attempt, Failed would arrive during the wait
    mkBootstrap(peersClient, parent, maxAttempts = 1, waitForPeerDelay = 50.millis)

    // First request returns NoSuitablePeer → WaitForPeer scheduled, NOT Failed
    peersClient
      .expectMsgType[PeersClient.Request[ETHPackets.GetBlockHeaders]](3.seconds)
      .replyTo ! PeersClient.NoSuitablePeer

    // Must not send Failed during the WaitForPeer window
    parent.expectNoMessage(200.millis)

    // WaitForPeer fires, bootstrap retries — a fresh peer is now available
    peersClient.expectMsgType[PeersClient.Request[ETHPackets.GetBlockHeaders]](3.seconds).replyTo ! PeersClient
      .Response(testPeer, ETHPackets.BlockHeaders(BigInt(0), Seq(correctHeader)))

    parent.expectMsg(3.seconds, PivotHeaderBootstrap.Completed(targetBlock, correctHeader))
  }

  it should "not store a header its check rejects: blacklist the peer that served it and ask another peer" taggedAs UnitTest in {
    val peersClient = TestProbe()
    val parent = TestProbe()
    val rejected = correctHeader.copy(difficulty = com.chipprbots.ethereum.domain.Difficulty(BigInt(1)))
    mkBootstrap(
      peersClient,
      parent,
      maxAttempts = 3,
      preferSnapPeers = true,
      headerCheck = rejectDifficultyOne
    )

    peersClient.expectMsgType[PeersClient.Request[ETHPackets.GetBlockHeaders]](3.seconds).replyTo ! PeersClient
      .Response(testPeer, ETHPackets.BlockHeaders(BigInt(0), Seq(rejected)))
    peersClient.expectMsg(
      3.seconds,
      PeersClient.BlacklistPeer(testPeer.id, BlacklistReason.BlockHeaderValidationFailed)
    )

    val retry = peersClient.expectMsgType[PeersClient.Request[ETHPackets.GetBlockHeaders]](3.seconds)
    retry.peerSelector shouldBe PeersClient.BestSnapPeerWithMinBlockExcluding(targetBlock, Set(testPeer.id))
    retry.replyTo ! PeersClient.Response(testPeer2, ETHPackets.BlockHeaders(BigInt(0), Seq(correctHeader)))

    parent.expectMsg(3.seconds, PivotHeaderBootstrap.Completed(targetBlock, correctHeader))
  }

  it should "give up, not wait for a peer forever, when no eligible peer is left after a rejected header" taggedAs UnitTest in {
    val peersClient = TestProbe()
    val parent = TestProbe()
    val rejected = correctHeader.copy(difficulty = com.chipprbots.ethereum.domain.Difficulty(BigInt(1)))
    mkBootstrap(peersClient, parent, maxAttempts = 3, preferSnapPeers = true, headerCheck = rejectDifficultyOne)

    // The only peer that claims the target's height serves a header that fails the check.
    peersClient.expectMsgType[PeersClient.Request[ETHPackets.GetBlockHeaders]](3.seconds).replyTo ! PeersClient
      .Response(testPeer, ETHPackets.BlockHeaders(BigInt(0), Seq(rejected)))
    peersClient.expectMsg(
      3.seconds,
      PeersClient.BlacklistPeer(testPeer.id, BlacklistReason.BlockHeaderValidationFailed)
    )

    // The other peers are below the target: neither the SNAP selector nor its fallback finds one.
    peersClient
      .expectMsgType[PeersClient.Request[ETHPackets.GetBlockHeaders]](3.seconds)
      .replyTo ! PeersClient.NoSuitablePeer
    peersClient
      .expectMsgType[PeersClient.Request[ETHPackets.GetBlockHeaders]](3.seconds)
      .replyTo ! PeersClient.NoSuitablePeer

    parent.expectMsg(3.seconds, PivotHeaderBootstrap.Failed(PivotHeaderBootstrap.ExhaustedAfterRejection))
  }

  it should "still wait for a fresh peer when no peer is eligible and no header was rejected" taggedAs UnitTest in {
    val peersClient = TestProbe()
    val parent = TestProbe()
    mkBootstrap(peersClient, parent, maxAttempts = 3, preferSnapPeers = true, headerCheck = rejectDifficultyOne)

    peersClient
      .expectMsgType[PeersClient.Request[ETHPackets.GetBlockHeaders]](3.seconds)
      .replyTo ! PeersClient.NoSuitablePeer
    peersClient
      .expectMsgType[PeersClient.Request[ETHPackets.GetBlockHeaders]](3.seconds)
      .replyTo ! PeersClient.NoSuitablePeer

    // WaitForPeer fires after waitForPeerDelay and asks again; nothing is reported to the parent.
    peersClient.expectMsgType[PeersClient.Request[ETHPackets.GetBlockHeaders]](3.seconds)
    parent.expectNoMessage(100.millis)
  }

  /** Rejects a header with difficulty 1, as the header-only check rejects a header it cannot validate. */
  private val rejectDifficultyOne: BlockHeader => Either[BlockHeaderError, BlockHeaderValid] = h =>
    if h.difficulty.value == 1 then Left(BlockHeaderError.HeaderUnexpectedError("difficulty 1"))
    else Right(BlockHeaderValid)
